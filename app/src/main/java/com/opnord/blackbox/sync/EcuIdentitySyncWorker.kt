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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Uploads latest read-only ECU identification when Wi-Fi and device credentials are available. */
class EcuIdentitySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val observedAt = prefs.getLong("ecu_identity_at", 0L)
        val rawReport = prefs.getString("ecu_identity_report", null)
        if (observedAt <= 0 || rawReport.isNullOrBlank())
            return@withContext Result.success()

        val config = SyncConfigurationStore.read(applicationContext) ?: run {
            prefs.edit().putString("ecu_sync_status", "Configura prima il dispositivo").apply()
            return@withContext Result.retry()
        }
        if (observedAt <= prefs.getLong("ecu_identity_synced_at", 0L) &&
            config.deviceId == prefs.getString("ecu_identity_synced_device_id", null))
            return@withContext Result.success()
        if (!config.baseUrl.startsWith("https://", true) || config.token.isBlank()) {
            prefs.edit().putString("ecu_sync_status", "URL HTTPS o token non validi").apply()
            return@withContext Result.retry()
        }
        val network = applicationContext.getSystemService(ConnectivityManager::class.java).run {
            activeNetwork?.let(::getNetworkCapabilities)
        }
        if (network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            prefs.edit().putString("ecu_sync_status", "In attesa del Wi-Fi").apply()
            return@withContext Result.retry()
        }
        val body = runCatching {
            JSONObject()
                .put("deviceId", config.deviceId)
                .put("observedAt", Instant.ofEpochMilli(observedAt).toString())
                .put("report", JSONObject(rawReport))
                .toString()
        }.getOrElse {
            prefs.edit().putString("ecu_sync_status", "Report ECU locale non valido").apply()
            return@withContext Result.failure()
        }

        val code = try {
            val url = URL(config.baseUrl.trimEnd('/') + "/api/v1/devices/ecu-identity")
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
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                connection.responseCode
            } finally { connection.disconnect() }
        } catch (e: Exception) {
            prefs.edit().putString("ecu_sync_status", "Invio ECU fallito: ${e.javaClass.simpleName}").apply()
            return@withContext Result.retry()
        }
        if (code !in 200..299) {
            prefs.edit().putString("ecu_sync_status", "Server ECU HTTP $code").apply()
            return@withContext Result.retry()
        }
        prefs.edit().putLong("ecu_identity_synced_at", observedAt)
            .putString("ecu_identity_synced_device_id", config.deviceId)
            .putString("ecu_sync_status", "Identificazione ECU ricevuta dal server").apply()
        Result.success()
    }

    companion object {
        fun scheduleOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<EcuIdentitySyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("ecu-identity-sync", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
