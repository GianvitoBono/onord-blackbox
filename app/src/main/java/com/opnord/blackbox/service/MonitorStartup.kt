package com.opnord.blackbox.service

import android.content.Context

/** Persist the user's logger choice independently from the current service process. */
object MonitorStartup {
    private const val PREFS = "monitor_configuration"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).commit()
    }
}
