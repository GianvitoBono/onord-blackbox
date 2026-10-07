package com.opnord.blackbox.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Restore an explicitly enabled location logger after reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!MonitorStartup.isEnabled(context)) return
        val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBackground = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation || !hasBackground) {
            prefs.edit().putString("boot_status", "avvio automatico sospeso: autorizza Posizione sempre").apply()
            return
        }
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, BlackBoxService::class.java).setAction(BlackBoxService.ACTION_START_MONITOR))
            prefs.edit().putString("boot_status", "avvio logger richiesto dopo riavvio/aggiornamento").apply()
        } catch (error: Exception) {
            val detail = "avvio automatico fallito: ${error.javaClass.simpleName}: ${error.message ?: "errore"}"
            Log.w("VehicleBlackbox", detail, error)
            prefs.edit().putString("boot_status", detail).apply()
        }
    }
}
