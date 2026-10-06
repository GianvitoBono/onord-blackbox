package com.opnord.blackbox.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.opnord.blackbox.service.BlackBoxService
import com.opnord.blackbox.storage.BlackBoxDatabase
import com.opnord.blackbox.sync.SyncConfigurationStore
import com.opnord.blackbox.sync.TelemetrySyncWorker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var info: TextView
    private val foregroundPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private var tripCount = 0
    private var sampleCount = 0
    private var pendingCount = 0
    private lateinit var backendUrl: EditText
    private lateinit var deviceId: EditText
    private lateinit var bearerToken: EditText
    private lateinit var obdAddress: EditText
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showPairedObdDevices() else refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 24) }
        root.addView(TextView(this).apply { text = "Vehicle Blackbox"; textSize = 25f })
        info = TextView(this).apply { textSize = 16f; setPadding(0, 22, 0, 18) }
        root.addView(info)
        backendUrl = EditText(this).apply {
            hint = "Backend URL HTTPS (es. https://blackbox.example)"
            setText(getSharedPreferences("sync_configuration", MODE_PRIVATE).getString("base_url", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        deviceId = EditText(this).apply {
            hint = "UUID dispositivo"
            setText(SyncConfigurationStore.deviceId(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        bearerToken = EditText(this).apply {
            hint = "Token dispositivo (archiviato cifrato)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(backendUrl)
        root.addView(deviceId)
        root.addView(bearerToken)
        obdAddress = EditText(this).apply {
            hint = "Indirizzo MAC adattatore OBD Bluetooth associato (opzionale)"
            setText(getSharedPreferences("obd_configuration", MODE_PRIVATE).getString("address", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        root.addView(obdAddress)
        root.addView(Button(this).apply { text = "Scegli dispositivo Bluetooth associato"; setOnClickListener { selectObdDevice() } })
        root.addView(Button(this).apply { text = "Salva adattatore OBD"; setOnClickListener { saveObdConfiguration() } })
        root.addView(Button(this).apply { text = "Salva configurazione e sincronizza"; setOnClickListener { saveSyncConfiguration() } })
        root.addView(Button(this).apply { text = "Configura permessi posizione"; setOnClickListener { requestLocationPermissions() } })
        root.addView(Button(this).apply { text = "Avvia logger"; setOnClickListener {
            if (!hasBackgroundLocation()) requestLocationPermissions()
            else ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, BlackBoxService::class.java).setAction(BlackBoxService.ACTION_START_MONITOR))
        } })
        root.addView(Button(this).apply { text = "Ferma monitor"; setOnClickListener {
            startService(Intent(this@MainActivity, BlackBoxService::class.java).setAction(BlackBoxService.ACTION_STOP_MONITOR))
        } })
        setContentView(root)
        val db = BlackBoxDatabase.get(this)
        lifecycleScope.launch {
            combine(db.trips().countFlow(), db.samples().countFlow(), db.samples().unsyncedCountFlow()) { trips, samples, pending -> Triple(trips, samples, pending) }
                .collect { (trips, samples, pending) ->
                    tripCount = trips; sampleCount = samples; pendingCount = pending
                    refresh()
                }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun requestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (!hasBackgroundLocation()) {
            // Android's background location grant must be changed from app settings on Android 11+.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun hasBackgroundLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        if (!::info.isInitialized) return
        info.text = "Permesso posizione: ${if (hasBackgroundLocation()) "sempre" else "mancante"}\n" +
            "Viaggi salvati: $tripCount\nCampioni locali: $sampleCount\nIn attesa di sincronizzazione: $pendingCount\n\n" +
            "OBD: ${getSharedPreferences("diagnostics", MODE_PRIVATE).getString("obd_status", "non connesso") ?: "non connesso"}\n\n" +
            "Per registrare a schermo spento, avvia il monitor da questa schermata e imposta Posizione su Consenti sempre nelle impostazioni dell'app. OxygenOS può richiedere di consentire l'attività in background e rimuovere l'ottimizzazione batteria."
    }

    private fun saveSyncConfiguration() {
        val url = backendUrl.text.toString().trim().trimEnd('/')
        val id = deviceId.text.toString().trim()
        val token = bearerToken.text.toString()
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host.isNullOrBlank()) {
            info.text = "Inserisci un URL HTTPS valido."
            return
        }
        if (runCatching { java.util.UUID.fromString(id) }.isFailure) {
            info.text = "L'ID dispositivo deve essere un UUID valido."
            return
        }
        if (token.isBlank()) {
            info.text = "Inserisci il token bearer del dispositivo. Verrà cifrato tramite Android Keystore."
            return
        }
        SyncConfigurationStore.save(this, url, id, token)
        bearerToken.text.clear()
        TelemetrySyncWorker.schedule(this)
        info.text = "Configurazione salvata. Sincronizzazione pianificata solo su Wi-Fi.\n" +
            "Viaggi salvati: $tripCount\nCampioni locali: $sampleCount\nIn attesa di sincronizzazione: $pendingCount"
    }

    private fun saveObdConfiguration() {
        val address = obdAddress.text.toString().trim().uppercase()
        if (address.isNotEmpty() && !android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) {
            info.text = "Indirizzo MAC Bluetooth non valido."; return
        }
        getSharedPreferences("obd_configuration", MODE_PRIVATE).edit().putString("address", address).apply()
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        info.text = if (address.isEmpty()) "Raccolta OBD disattivata; GPS e logger restano attivi." else "Adattatore salvato. Avvia il logger con il dispositivo Bluetooth già associato."
    }

    private fun selectObdDevice() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        showPairedObdDevices()
    }

    private fun showPairedObdDevices() {
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { info.text = "Questo telefono non dispone di Bluetooth."; return }
            val devices = adapter.bondedDevices.orEmpty().sortedBy { it.name.orEmpty() }
            if (devices.isEmpty()) { info.text = "Nessun dispositivo associato. Associa l'adattatore OBD nelle impostazioni Bluetooth e riprova."; return }
            val labels = devices.map { "${it.name ?: "Dispositivo Bluetooth"} · ${it.address}" }.toTypedArray()
            android.app.AlertDialog.Builder(this).setTitle("Adattatori Bluetooth associati")
                .setItems(labels) { _, which -> obdAddress.setText(devices[which].address); saveObdConfiguration() }
                .setNegativeButton("Annulla", null).show()
        } catch (_: SecurityException) { info.text = "Concedi il permesso Bluetooth per scegliere l'adattatore." }
    }
}
