package com.opnord.blackbox.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.text.format.DateFormat
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.opnord.blackbox.service.BlackBoxService
import com.opnord.blackbox.service.MonitorStartup
import com.opnord.blackbox.storage.BlackBoxDatabase
import com.opnord.blackbox.sync.SyncConfigurationStore
import com.opnord.blackbox.sync.EcuIdentitySyncWorker
import com.opnord.blackbox.sync.DiagnosticReportSyncWorker
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
    private val diagnosticsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { refresh() } }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showPairedObdDevices() else refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 24)
        }
        content.addView(TextView(this).apply { text = "Vehicle Blackbox"; textSize = 25f })
        info = TextView(this).apply { textSize = 16f; setPadding(0, 22, 0, 18) }
        content.addView(info)
        content.addView(TextView(this).apply { text = "Connessione e dispositivo"; textSize = 20f; setPadding(0, 18, 0, 8) })
        content.addView(TextView(this).apply {
            text = "L'ID dispositivo è un UUID stabile, diverso dal nome del mezzo. Se lo cambi, i batch locali vengono rigenerati con il nuovo ID; il server riassocia i viaggi dello stesso mezzo."
        })
        backendUrl = EditText(this).apply {
            hint = "Backend URL HTTPS (es. https://blackbox.example)"
            setText(getSharedPreferences("sync_configuration", MODE_PRIVATE).getString("base_url", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        deviceId = EditText(this).apply {
            hint = "ID dispositivo (UUID stabile)"
            setText(SyncConfigurationStore.deviceId(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        bearerToken = EditText(this).apply {
            hint = "Token dispositivo (vuoto = conserva quello salvato)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(backendUrl)
        content.addView(deviceId)
        content.addView(bearerToken)
        obdAddress = EditText(this).apply {
            hint = "Indirizzo MAC adattatore OBD Bluetooth associato (opzionale)"
            setText(getSharedPreferences("obd_configuration", MODE_PRIVATE).getString("address", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        content.addView(obdAddress)
        content.addView(Button(this).apply { text = "Scegli dispositivo Bluetooth associato"; setOnClickListener { selectObdDevice() } })
        content.addView(Button(this).apply { text = "Salva adattatore OBD"; setOnClickListener { saveObdConfiguration() } })
        content.addView(Button(this).apply { text = "Scansiona errori OBD motore"; setOnClickListener { scanDiagnostics() } })
        content.addView(Button(this).apply { text = "Copia risultato scansione"; setOnClickListener { copyDiagnosticReport() } })
        content.addView(Button(this).apply { text = "Identifica centralina motore"; setOnClickListener { identifyEcu() } })
        content.addView(Button(this).apply { text = "Copia identificazione ECU"; setOnClickListener { copyEcuIdentity() } })
        content.addView(Button(this).apply { text = "Acquisisci risposte OBD grezze"; setOnClickListener { captureMode01() } })
        content.addView(Button(this).apply { text = "Copia risposte OBD grezze"; setOnClickListener { copyMode01Capture() } })
        content.addView(Button(this).apply { text = "Salva configurazione e sincronizza"; setOnClickListener { saveSyncConfiguration() } })
        content.addView(Button(this).apply { text = "Sincronizza ora"; setOnClickListener { TelemetrySyncWorker.retryNow(this@MainActivity); refresh() } })
        content.addView(Button(this).apply { text = "Configura permessi posizione"; setOnClickListener { requestLocationPermissions() } })
        content.addView(Button(this).apply { text = "Avvia logger"; setOnClickListener {
            if (!hasBackgroundLocation()) requestLocationPermissions()
            else ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, BlackBoxService::class.java).setAction(BlackBoxService.ACTION_START_MONITOR))
        } })
        content.addView(Button(this).apply { text = "Ferma monitor"; setOnClickListener {
            startService(Intent(this@MainActivity, BlackBoxService::class.java).setAction(BlackBoxService.ACTION_STOP_MONITOR))
        } })
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        setContentView(scrollView)
        EcuIdentitySyncWorker.scheduleOnce(this)
        DiagnosticReportSyncWorker.scheduleOnce(this)
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
    override fun onStart() {
        super.onStart()
        getSharedPreferences("diagnostics", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(diagnosticsListener)
    }
    override fun onStop() {
        getSharedPreferences("diagnostics", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(diagnosticsListener)
        super.onStop()
    }

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
        val diagnostics = getSharedPreferences("diagnostics", MODE_PRIVATE)
        val lastGps = diagnostics.getLong("last_gps", 0L)
        val gpsStatus = diagnostics.getString("gps_status", null) ?: if (lastGps > 0) "GPS attivo" else "in attesa di un punto GPS"
        val syncStatus = diagnostics.getString("sync_status", "mai sincronizzato") ?: "mai sincronizzato"
        val lastAttempt = diagnostics.getLong("last_sync_attempt", 0L)
        val lastSuccess = diagnostics.getLong("last_sync_success", 0L)
        val lastObd = diagnostics.getLong("last_obd", 0L)
        val obdSupported = diagnostics.getInt("obd_supported", -1)
        val obdLastError = diagnostics.getString("obd_last_error", null)
        val diagnosticStatus = diagnostics.getString("diagnostic_status", null)
        val diagnosticSummary = diagnostics.getString("diagnostic_summary", null)
        val diagnosticAt = diagnostics.getLong("diagnostic_at", 0L)
        val diagnosticSyncStatus = diagnostics.getString("diagnostic_sync_status", null)
        val ecuStatus = diagnostics.getString("ecu_identity_status", null)
        val ecuSummary = diagnostics.getString("ecu_identity_summary", null)
        val ecuAt = diagnostics.getLong("ecu_identity_at", 0L)
        val ecuSyncStatus = diagnostics.getString("ecu_sync_status", null)
        val captureStatus = diagnostics.getString("mode01_capture_status", null)
        val captureAt = diagnostics.getLong("mode01_capture_at", 0L)
        val bootStatus = diagnostics.getString("boot_status", null)
        val bootReceivedAt = diagnostics.getLong("boot_received_at", 0L)
        val monitorStartedAt = diagnostics.getLong("monitor_started_at", 0L)
        val monitorStatus = diagnostics.getString("monitor_status", null)
        info.text = "Permesso posizione: ${if (hasBackgroundLocation()) "sempre" else "mancante"}\n" +
            "Avvio dopo riavvio: ${if (MonitorStartup.isEnabled(this)) "attivo" else "disattivo"}\n" +
            (if (bootStatus != null) "Ultimo boot: $bootStatus${if (bootReceivedAt > 0) " · ${DateFormat.format("dd/MM HH:mm:ss", bootReceivedAt)}" else ""}\n" else "") +
            (if (monitorStatus != null) "Monitor: $monitorStatus${if (monitorStartedAt > 0) " · ultimo avvio ${DateFormat.format("dd/MM HH:mm:ss", monitorStartedAt)}" else ""}\n" else "") +
            "Viaggi salvati: $tripCount\nCampioni locali: $sampleCount\nIn attesa di sincronizzazione: $pendingCount\n\n" +
            "GPS: $gpsStatus${if (lastGps > 0) " · ultimo punto ${DateFormat.format("dd/MM HH:mm:ss", lastGps)}" else ""}\n" +
            "OBD: ${diagnostics.getString("obd_status", "non connesso") ?: "non connesso"}\n" +
            (if (obdLastError != null) "Ultimo errore OBD: $obdLastError\n" else "") +
            (if (obdSupported >= 0) "PID OBD dichiarati: $obdSupported · decodificati: ${diagnostics.getInt("obd_known", 0)} · raw: ${diagnostics.getInt("obd_raw", 0)}\n" else "") +
            (if (lastObd > 0) "Ultimi valori OBD: ${diagnostics.getInt("obd_last_values", 0)} · ${DateFormat.format("dd/MM HH:mm:ss", lastObd)}\n" else "") +
            (if (diagnosticStatus != null) "Scansione OBD: $diagnosticStatus${if (diagnosticAt > 0) " · ${DateFormat.format("dd/MM HH:mm", diagnosticAt)}" else ""}\n" else "") +
            (if (diagnosticSummary != null) "$diagnosticSummary\n" else "") +
            (if (diagnosticSyncStatus != null) "Invio diagnosi: $diagnosticSyncStatus\n" else "") +
            (if (ecuStatus != null) "Identificazione ECU: $ecuStatus${if (ecuAt > 0) " · ${DateFormat.format("dd/MM HH:mm", ecuAt)}" else ""}\n" else "") +
            (if (ecuSummary != null) "$ecuSummary\n" else "") +
            (if (ecuSyncStatus != null) "Invio ECU: $ecuSyncStatus\n" else "") +
            (if (captureStatus != null) "Risposte OBD grezze: $captureStatus${if (captureAt > 0) " · ${DateFormat.format("dd/MM HH:mm", captureAt)}" else ""}\n" else "") +
            "Sincronizzazione: $syncStatus\n" +
            "Ultimo tentativo: ${if (lastAttempt > 0) DateFormat.format("dd/MM HH:mm", lastAttempt) else "mai"}\n" +
            "Ultimo successo: ${if (lastSuccess > 0) DateFormat.format("dd/MM HH:mm", lastSuccess) else "mai"}\n\n" +
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
        val saved = SyncConfigurationStore.read(this)
        if (token.isBlank() && (saved == null || saved.token.isBlank() || saved.deviceId != id)) {
            info.text = "Inserisci il token del nuovo dispositivo. Verrà cifrato tramite Android Keystore."
            return
        }
        SyncConfigurationStore.save(this, url, id, token)
        bearerToken.text.clear()
        TelemetrySyncWorker.schedule(this)
        TelemetrySyncWorker.retryNow(this)
        EcuIdentitySyncWorker.scheduleOnce(this)
        DiagnosticReportSyncWorker.scheduleOnce(this)
        info.text = "Configurazione salvata. I batch locali useranno l'ID attuale; sincronizzazione avviata su Wi-Fi.\n" +
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
        info.text = if (address.isEmpty()) "Raccolta OBD disattivata; GPS e logger restano attivi." else "Adattatore salvato. Con logger avviato, connessione OBD anche prima del viaggio quando il telefono è alimentato; retry ogni 5 secondi."
    }

    private fun scanDiagnostics() {
        val address = getSharedPreferences("obd_configuration", MODE_PRIVATE).getString("address", "").orEmpty()
        if (address.isBlank()) {
            info.text = "Scegli e salva prima l'adattatore OBD Bluetooth."
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, BlackBoxService::class.java).setAction(BlackBoxService.ACTION_SCAN_DIAGNOSTICS))
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("diagnostic_status", "scansione in corso").apply()
    }

    private fun copyDiagnosticReport() {
        val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
        val summary = prefs.getString("diagnostic_summary", null)
        if (summary.isNullOrBlank()) {
            info.text = "Nessuna scansione disponibile. Esegui prima la scansione OBD."
            return
        }
        val report = summary + "\n\n" + prefs.getString("diagnostic_report", "").orEmpty()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diagnostica OBD", report))
        info.text = "Risultato OBD copiato. Incollalo per analizzare codici e misure."
    }

    private fun identifyEcu() {
        val address = getSharedPreferences("obd_configuration", MODE_PRIVATE).getString("address", "").orEmpty()
        if (address.isBlank()) {
            info.text = "Scegli e salva prima l'adattatore OBD Bluetooth."
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, BlackBoxService::class.java).setAction(BlackBoxService.ACTION_IDENTIFY_ECU))
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("ecu_identity_status", "lettura in corso").apply()
    }

    private fun copyEcuIdentity() {
        val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
        val summary = prefs.getString("ecu_identity_summary", null)
        if (summary.isNullOrBlank()) {
            info.text = "Nessuna identificazione disponibile. Esegui prima la lettura ECU."
            return
        }
        val report = summary + "\n\n" + prefs.getString("ecu_identity_report", "").orEmpty()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Identificazione ECU", report))
        info.text = "Identificazione ECU copiata. Incollala per verificare centralina e PID Alfa/Fiat."
    }

    private fun captureMode01() {
        val address = getSharedPreferences("obd_configuration", MODE_PRIVATE).getString("address", "").orEmpty()
        if (address.isBlank()) {
            info.text = "Scegli e salva prima l'adattatore OBD Bluetooth."
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, BlackBoxService::class.java).setAction(BlackBoxService.ACTION_CAPTURE_MODE01))
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("mode01_capture_status", "acquisizione in corso").apply()
    }

    private fun copyMode01Capture() {
        val report = getSharedPreferences("diagnostics", MODE_PRIVATE).getString("mode01_capture_report", null)
        if (report.isNullOrBlank()) {
            info.text = "Nessuna acquisizione completa. Esegui prima la scansione delle risposte grezze."
            return
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Risposte OBD Mode 01", report))
        info.text = "Risposte OBD copiate. Incolla il JSON per analizzare PID e byte di ogni ECU."
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
