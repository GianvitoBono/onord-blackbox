package com.opnord.blackbox.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.opnord.blackbox.storage.BlackBoxDatabase
import com.opnord.blackbox.storage.PendingSyncBatchEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

class TelemetrySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = SyncConfigurationStore.read(applicationContext) ?: return@withContext Result.success()
        if (!config.baseUrl.startsWith("https://", ignoreCase = true) || config.token.isBlank()) return@withContext Result.failure()
        if (!isWifiConnected()) return@withContext Result.retry()

        val db = BlackBoxDatabase.get(applicationContext)
        val started = SystemClock.elapsedRealtime()
        var completed = 0
        while (completed < MAX_BATCHES_PER_RUN && SystemClock.elapsedRealtime() - started < MAX_RUN_MILLIS) {
            if (!isWifiConnected()) return@withContext Result.retry()
            val batch = try { loadOrCreateBatch(db, config.deviceId) } catch (_: Exception) { return@withContext Result.retry() }
                ?: return@withContext Result.success()
            val response = try { post(config, batch) } catch (_: Exception) { return@withContext Result.retry() }
            if (response.code !in 200..299) {
                response.connection.disconnect()
                return@withContext if (response.code == 408 || response.code == 429 || response.code >= 500) Result.retry() else Result.failure()
            }
            val accepted = runCatching {
                val ack = JSONObject(response.body)
                val payload = JSONObject(batch.payload)
                ack.optBoolean("accepted") && ack.optString("batchId") == batch.batchId &&
                    ack.optInt("schemaVersion") == 1 &&
                    ack.optInt("gpsAccepted", -1) == payload.getJSONArray("gpsSamples").length() &&
                    ack.optInt("deviceAccepted", -1) == payload.getJSONArray("deviceSamples").length() &&
                    ack.optInt("obdAccepted", -1) == payload.getJSONArray("obdSamples").length()
            }.getOrDefault(false)
            response.connection.disconnect()
            if (!accepted) return@withContext Result.retry()

            try {
                db.withTransaction {
                    val ids = batch.sampleIds.split(',').filter(String::isNotBlank)
                    if (ids.isNotEmpty()) db.samples().markSynced(ids, System.currentTimeMillis())
                    if (db.samples().unsyncedCountForTrip(batch.tripId) == 0) db.trips().markSynced(batch.tripId, System.currentTimeMillis())
                    db.pendingBatches().delete(batch.batchId)
                }
            } catch (_: Exception) { return@withContext Result.retry() }
            completed++
        }
        val morePending = try {
            db.pendingBatches().first() != null || db.samples().nextUnsyncedTripId() != null
        } catch (_: Exception) { return@withContext Result.retry() }
        if (morePending) enqueueContinuation(applicationContext)
        Result.success()
    }

    private suspend fun loadOrCreateBatch(db: BlackBoxDatabase, deviceId: String): PendingSyncBatchEntity? = db.withTransaction {
        db.pendingBatches().first()?.let { pending ->
            val queuedDeviceId = runCatching { JSONObject(pending.payload).optString("deviceId") }.getOrNull()
            if (queuedDeviceId == deviceId) return@withTransaction pending
            // The configured device changed while a batch was queued. Rebuild from unsynced samples.
            db.pendingBatches().delete(pending.batchId)
        }
        val tripId = db.samples().nextUnsyncedTripId() ?: return@withTransaction null
        val samples = db.samples().unsyncedForTrip(tripId, MAX_SAMPLES_PER_BATCH)
        val trip = db.trips().byId(tripId) ?: return@withTransaction null
        if (samples.isEmpty()) return@withTransaction null
        val batchId = UUID.randomUUID().toString()
        val encoded = BatchPayload.encode(deviceId, batchId, trip, samples)
        PendingSyncBatchEntity(batchId, tripId, encoded.payload, encoded.sampleIds.joinToString(",")).also {
            db.pendingBatches().insert(it)
        }
    }

    private fun isWifiConnected(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun post(config: SyncConfiguration, batch: PendingSyncBatchEntity): HttpResponse {
        val url = URL(config.baseUrl.trimEnd('/') + "/api/v1/telemetry/batches")
        require(url.protocol.equals("https", ignoreCase = true))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer ${config.token}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use { it.write(batch.payload.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return HttpResponse(connection, code, body)
    }

    private data class HttpResponse(val connection: HttpURLConnection, val code: Int, val body: String)

    companion object {
        const val UNIQUE_PERIODIC = "telemetry-sync-periodic"
        const val UNIQUE_ONCE = "telemetry-sync-once"
        private const val MAX_SAMPLES_PER_BATCH = 200
        private const val MAX_BATCHES_PER_RUN = 50
        private const val MAX_RUN_MILLIS = 4 * 60 * 1000L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TelemetrySyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
            manager.enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TelemetrySyncWorker>().setConstraints(constraints).build())
        }

        fun scheduleOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<TelemetrySyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP, request)
        }

        private fun enqueueContinuation(context: Context) {
            val request = OneTimeWorkRequestBuilder<TelemetrySyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
