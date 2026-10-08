package com.opnord.blackbox.obd

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.opnord.blackbox.sync.SyncConfigurationStore

/** Supply an explicitly saved PIN only to the configured OBD adapter. */
class ObdPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST &&
            intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val configured = context.getSharedPreferences("obd_configuration", Context.MODE_PRIVATE)
            .getString("address", null) ?: return
        if (!device.address.equals(configured, ignoreCase = true)) return
        val diagnostics = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
            val status = when (state) {
                BluetoothDevice.BOND_BONDED -> "Associazione OBD completata"
                BluetoothDevice.BOND_BONDING -> "Associazione OBD in corso"
                BluetoothDevice.BOND_NONE -> "Associazione OBD assente o persa"
                else -> "Stato associazione OBD $state"
            }
            diagnostics.edit().putString("obd_pairing_status", "$status ($previous → $state)")
                .putLong("obd_pairing_at", System.currentTimeMillis()).apply()
            return
        }
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        val pin = SyncConfigurationStore.readObdPin(context, configured)
        val status = when {
            pin == null -> "Richiesta pairing OBD: PIN non salvato; completa nelle impostazioni"
            variant != BluetoothDevice.PAIRING_VARIANT_PIN -> "Richiesta pairing OBD: metodo $variant richiede conferma Android"
            else -> try {
                if (device.setPin(pin.toByteArray(Charsets.US_ASCII))) {
                    if (isOrderedBroadcast) abortBroadcast()
                    "PIN OBD inviato automaticamente; in attesa dell'associazione"
                } else "Android ha rifiutato il PIN automatico; completa nelle impostazioni"
            } catch (_: SecurityException) {
                "Android non consente PIN automatico; completa nelle impostazioni"
            }
        }
        diagnostics.edit().putString("obd_pairing_status", status)
            .putLong("obd_pairing_at", System.currentTimeMillis()).apply()
    }
}
