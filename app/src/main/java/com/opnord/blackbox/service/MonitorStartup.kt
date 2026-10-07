package com.opnord.blackbox.service

import android.content.Context

/** Persist the user's logger choice independently from the current service process. */
object MonitorStartup {
    private const val PREFS = "monitor_configuration"
    private const val ENABLED = "enabled"

    // Existing installations have no saved choice: enable boot recovery until the user presses Stop.
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).commit()
    }
}
