package com.opnord.blackbox.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Saves every manual DTC scan until the server acknowledges it. No clear/reset commands. */
class DiagnosticReportSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val config = SyncConfigurationStore.read(applicationContext) ?: run {
            prefs.edit().putString("diagnostic_sync_status", "Configura prima il dispositivo").apply()
            return@withContext Result.retry()
        }
        if (!config.baseUrl.startsWith("https://", true) || config.token.isBlank()) {
            prefs.edit().putString("diagnostic_sync_status", "URL HTTPS o token non validi").apply()
            return@withContext Result.retry()
        }
        val network = applicationContext.getSystemService(ConnectivityManager::class.java).run {
            activeNetwork?.let(::getNetworkCapabilities)
        }
        if (network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            prefs.edit().putString("diagnostic_sync_status", "In attesa del Wi-Fi").apply()
            return@withContext Result.retry()
        }
        while (true) {
            val next = synchronized(lock) { pending(prefs).optJSONObject(0) } ?: return@withContext Result.success()
            val observedAt = next.optLong("observed_at_ms", 0L)
            val report = next.optJSONObject("report")
            if (observedAt <= 0 || report == null) {
                prefs.edit().putString("diagnostic_sync_status", "Report locale non valido").apply()
                return@withContext Result.failure()
            }
            val body = JSONObject()
                .put("deviceId", config.deviceId)
                .put("observedAt", Instant.ofEpochMilli(observedAt).toString())
                .put("report", report).toString()
            val code = try {
                val connection = (URL(config.baseUrl.trimEnd('/') + "/api/v1/devices/diagnostics-report")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    doOutput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Authorization", "Bearer ${config.token}")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    connection.responseCode
                } finally { connection.disconnect() }
            } catch (error: Exception) {
                prefs.edit().putString("diagnostic_sync_status", "Invio diagnosi fallito: ${error.javaClass.simpleName}").apply()
                return@withContext Result.retry()
            }
            if (code !in 200..299) {
                prefs.edit().putString("diagnostic_sync_status", "Server diagnosi HTTP $code").apply()
                return@withContext Result.retry()
            }
            synchronized(lock) {
                val queue = pending(prefs)
                val remaining = JSONArray()
                for (index in 0 until queue.length()) {
                    val item = queue.optJSONObject(index) ?: continue
                    if (item.optLong("observed_at_ms") != observedAt) remaining.put(item)
                }
                prefs.edit().putString(queueKey, remaining.toString())
                    .putLong("diagnostic_synced_at", observedAt)
                    .putString("diagnostic_synced_device_id", config.deviceId)
                    .putString("diagnostic_sync_status", "Scansione ricevuta dal server").apply()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.success()
    }

    companion object {
        private const val queueKey = "diagnostic_pending_reports"
        private val lock = Any()

        private fun pending(prefs: android.content.SharedPreferences): JSONArray =
            runCatching { JSONArray(prefs.getString(queueKey, "[]")) }.getOrElse { JSONArray() }

        fun enqueue(context: Context, observedAt: Long, reportJson: String) {
            val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
            synchronized(lock) {
                val queue = pending(prefs)
                val alreadyQueued = (0 until queue.length()).any { queue.optJSONObject(it)?.optLong("observed_at_ms") == observedAt }
                if (!alreadyQueued) {
                    queue.put(JSONObject().put("observed_at_ms", observedAt).put("report", JSONObject(reportJson)))
                    prefs.edit().putString(queueKey, queue.toString()).apply()
                }
            }
            scheduleOnce(context)
        }

        fun scheduleOnce(context: Context) {
            val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
            val observedAt = prefs.getLong("diagnostic_at", 0L)
            val report = prefs.getString("diagnostic_report", null)
            val config = SyncConfigurationStore.read(context)
            if (observedAt > 0 && !report.isNullOrBlank() &&
                (observedAt > prefs.getLong("diagnostic_synced_at", 0L) ||
                    config?.deviceId != prefs.getString("diagnostic_synced_device_id", null))) {
                synchronized(lock) {
                    val queue = pending(prefs)
                    if ((0 until queue.length()).none { queue.optJSONObject(it)?.optLong("observed_at_ms") == observedAt }) {
                        runCatching { queue.put(JSONObject().put("observed_at_ms", observedAt).put("report", JSONObject(report))) }
                        prefs.edit().putString(queueKey, queue.toString()).apply()
                    }
                }
            }
            if (synchronized(lock) { pending(prefs).length() } == 0) return
            val request = OneTimeWorkRequestBuilder<DiagnosticReportSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("diagnostic-report-sync", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
