package com.opnord.blackbox.service

import android.app.*
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Build
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.room.withTransaction
import com.opnord.blackbox.storage.*
import com.opnord.blackbox.sync.TelemetrySyncWorker
import com.opnord.blackbox.sync.EcuIdentitySyncWorker
import com.opnord.blackbox.sync.DiagnosticReportSyncWorker
import com.opnord.blackbox.trip.*
import com.opnord.blackbox.obd.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class BlackBoxService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: BlackBoxDatabase
    private var detector = JourneyDetector()
    @Volatile private var obdMotion = ObdMotionEvidence()
    private val lifecycleMutex = Mutex()
    private val locationQueue = Channel<Location>(Channel.UNLIMITED)
    private val recentLocations = ArrayDeque<Location>()
    private var lastProcessedLocationAt = 0L
    @Volatile private var tripId: String? = null
    private var sampler: Job? = null
    private var obdJob: Job? = null
    private var obdAddressInUse: String? = null
    private var recovery: Job? = null
    private lateinit var locations: LocationCollector
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var monitorStarted = false
    private val pairingReceiver = ObdPairingReceiver()

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, pairingReceiver,
            IntentFilter(android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
                addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                priority = 1000
            }, ContextCompat.RECEIVER_EXPORTED)
        db = BlackBoxDatabase.get(this)
        locations = locationCollectorFactory(this)
        val channel = NotificationChannel(CHANNEL, "Blackbox logger", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, notification("Ricerca movimento"))
        recovery = scope.launch {
            val active = db.trips().active() ?: return@launch
            val lastGps = db.samples().latestGpsAt(active.id)
            val openStop = db.events().openStop(active.id)
            if (lastGps != null && System.currentTimeMillis() - lastGps < JourneyDetector.TRIP_END_IDLE_MS) {
                tripId = active.id
                lastProcessedLocationAt = lastGps
                detector = JourneyDetector(initialTripActive = true, openStop = openStop?.let {
                    StopBoundary(it.stopId, it.observedAt, it.latitude, it.longitude)
                })
            } else {
                val endedAt = (lastGps ?: System.currentTimeMillis()).coerceAtLeast(active.startedAt)
                db.withTransaction {
                    if (openStop != null) insertStopBoundary(active.id, openStop.stopId, "stop_end", endedAt,
                        openStop.latitude, openStop.longitude)
                    db.trips().close(active.id, endedAt, "recovery_timeout")
                }
                TelemetrySyncWorker.scheduleOnce(this@BlackBoxService)
            }
        }
        scope.launch {
            for (location in locationQueue) {
                try {
                    lifecycleMutex.withLock { handleLocation(location) }
                } catch (error: Exception) {
                    val open = try { tripId?.let { db.events().openStop(it) } } catch (_: Exception) { null }
                    detector = JourneyDetector(initialTripActive = tripId != null, openStop = open?.let {
                        StopBoundary(it.stopId, it.observedAt, it.latitude, it.longitude)
                    })
                    getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                        .putString("gps_status", "Errore salvataggio GPS: ${error.javaClass.simpleName}").apply()
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                lifecycleMutex.withLock { handleTripClock() }
            }
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                lifecycleMutex.withLock { reconcileObdCollection() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        scope.launch {
            recovery?.join()
            when (action) {
                ACTION_START_MONITOR -> lifecycleMutex.withLock { startMonitoring() }
                ACTION_STOP_MONITOR -> stopMonitoring()
                ACTION_SCAN_DIAGNOSTICS -> lifecycleMutex.withLock { scanDiagnostics() }
                ACTION_IDENTIFY_ECU -> lifecycleMutex.withLock { identifyEcu() }
                ACTION_CAPTURE_MODE01 -> lifecycleMutex.withLock { captureMode01() }
                null -> if (MonitorStartup.isEnabled(this@BlackBoxService))
                    lifecycleMutex.withLock { startMonitoring() } else stopMonitoring() // START_STICKY recovery.
            }
        }
        return START_STICKY
    }

    private suspend fun startMonitoring() {
        MonitorStartup.setEnabled(this, true)
        if (monitorStarted) {
            startLocations(idle = tripId == null)
            reconcileObdCollection()
            return
        }
        monitorStarted = true
        if (tripId != null) startActiveCollection(tripId!!)
        startLocations(idle = tripId == null)
        reconcileObdCollection()
        val stopped = tripId?.let { db.events().openStop(it) } != null
        updateNotification(when {
            tripId == null -> "Ricerca movimento"
            stopped -> "Sosta in corso"
            else -> "Viaggio in corso"
        })
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
            .putString("monitor_status", "logger avviato")
            .putLong("monitor_started_at", System.currentTimeMillis()).apply()
    }

    private suspend fun stopMonitoring() {
        lifecycleMutex.withLock {
            MonitorStartup.setEnabled(this@BlackBoxService, false)
            getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                .putString("monitor_status", "logger fermato dall'utente").apply()
            monitorStarted = false
            locations.stop()
            stopActiveCollection()
            stopObdCollection()
            getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("obd_status", "monitor fermo").apply()
            tripId?.let { activeId ->
                val endedAt = System.currentTimeMillis()
                db.withTransaction {
                    db.events().openStop(activeId)?.let { open ->
                        insertStopBoundary(activeId, open.stopId, "stop_end", endedAt, open.latitude, open.longitude)
                    }
                    db.trips().close(activeId, endedAt, "monitor_stopped")
                }
            }
            tripId = null
            detector = JourneyDetector()
            recentLocations.clear()
            TelemetrySyncWorker.scheduleOnce(this@BlackBoxService)
            stopSelf()
        }
    }

    private fun startLocations(idle: Boolean) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("gps_status", "Permesso posizione mancante").apply()
            return
        }
        try {
            locations.start({ location -> locationQueue.trySend(location) }, idle)
        } catch (_: SecurityException) {
            getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("gps_status", "Permesso posizione mancante").apply()
        }
    }

    private suspend fun handleLocation(location: Location) {
        if (!monitorStarted || location.time <= lastProcessedLocationAt || location.latitude !in -90.0..90.0 ||
            location.longitude !in -180.0..180.0 || (location.hasAccuracy() && location.accuracy > 60f)) return
        lastProcessedLocationAt = location.time
        recentLocations.addLast(Location(location))
        while (recentLocations.isNotEmpty() && location.time - recentLocations.first().time > 120_000L)
            recentLocations.removeFirst()

        val decision = detector.observe(location, obdMotion)
        if (decision.startTrip) {
            startTrip()
            return
        }
        val activeId = tripId ?: return
        if (decision.closeTrip) stopActiveCollection()
        db.withTransaction {
            persistGps(activeId, location)
            decision.stopStart?.let { insertStopBoundary(activeId, it.stopId, "stop_start", it.observedAt, it.latitude, it.longitude) }
            decision.stopEnd?.let { insertStopBoundary(activeId, it.stopId, "stop_end", it.observedAt, it.latitude, it.longitude) }
            if (decision.closeTrip) db.trips().close(activeId, location.time, "stationary_timeout")
        }
        if (decision.stopStart != null) {
            updateNotification("Sosta in corso")
            TelemetrySyncWorker.scheduleOnce(this)
        }
        if (decision.stopEnd != null && !decision.closeTrip) updateNotification("Viaggio in corso")
        if (decision.closeTrip) {
            tripId = null
            recentLocations.clear()
            recentLocations.addLast(Location(location))
            startLocations(idle = true)
            reconcileObdCollection()
            updateNotification("Ricerca movimento")
            TelemetrySyncWorker.scheduleOnce(this)
        }
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
            .putLong("last_gps", location.time)
            .putString("gps_status", if (decision.closeTrip) "Ricerca movimento" else "GPS attivo").apply()
    }

    private suspend fun handleTripClock() {
        if (!monitorStarted) return
        val activeId = tripId ?: return
        val now = System.currentTimeMillis()
        val decision = detector.tick(now, obdMotion)
        if (decision.stopStart == null && !decision.closeTrip) return
        if (decision.closeTrip) stopActiveCollection()
        db.withTransaction {
            decision.stopStart?.let { insertStopBoundary(activeId, it.stopId, "stop_start", it.observedAt, it.latitude, it.longitude) }
            decision.stopEnd?.let { insertStopBoundary(activeId, it.stopId, "stop_end", it.observedAt, it.latitude, it.longitude) }
            if (decision.closeTrip) db.trips().close(activeId, now, "stationary_timeout")
        }
        if (decision.closeTrip) {
            tripId = null
            recentLocations.clear()
            startLocations(idle = true)
            reconcileObdCollection()
            updateNotification("Ricerca movimento")
        } else updateNotification("Sosta in corso")
        TelemetrySyncWorker.scheduleOnce(this)
    }

    private suspend fun startTrip() {
        val first = recentLocations.firstOrNull() ?: return
        val trip = TripEntity(UUID.randomUUID().toString(), first.time, startReason = "movement_detected")
        db.withTransaction {
            db.trips().insert(trip)
            recentLocations.forEach { persistGps(trip.id, it) }
        }
        tripId = trip.id
        startActiveCollection(trip.id)
        reconcileObdCollection()
        startLocations(idle = false)
        updateNotification("Viaggio in corso")
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putLong("last_gps", recentLocations.last().time)
            .putString("gps_status", "GPS attivo").apply()
    }

    private suspend fun persistGps(forTripId: String, location: Location) {
        db.samples().insert(TelemetrySampleEntity(
            UUID.randomUUID().toString(), forTripId, location.time,
            latitude = location.latitude, longitude = location.longitude,
            altitude = location.altitude.takeIf { location.hasAltitude() },
            gpsSpeedMps = location.speed.takeIf { location.hasSpeed() },
            bearing = location.bearing.takeIf { location.hasBearing() },
            horizontalAccuracy = location.accuracy.takeIf { location.hasAccuracy() },
            powerConnected = getSystemService(BatteryManager::class.java).isCharging
        ))
    }

    private suspend fun insertStopBoundary(activeId: String, stopId: String, kind: String, at: Long,
                                           latitude: Double, longitude: Double) {
        db.events().insert(TripEventEntity(UUID.randomUUID().toString(), stopId, activeId, kind, at, latitude, longitude))
    }

    private fun notification(status: String) = Notification.Builder(this, CHANNEL)
        .setContentTitle("Vehicle Blackbox").setContentText(status)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation).build()

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(status))
    }

    private fun startActiveCollection(forTripId: String) {
        if (wakeLock == null) {
            wakeLock = (getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VehicleBlackbox:logging").apply { setReferenceCounted(false); acquire() })
        }
        sampler?.cancel()
        sampler = scope.launch {
            while (isActive) {
                val battery = getSystemService(BatteryManager::class.java)
                val connected = battery.isCharging
                val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 }
                val temperature = (registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1).takeIf { it >= 0 }?.div(10f)
                db.samples().insert(TelemetrySampleEntity(UUID.randomUUID().toString(), forTripId, System.currentTimeMillis(), powerConnected = connected, batteryLevel = level, batteryTemperatureC = temperature))
                delay(2_000)
            }
        }
    }

    private suspend fun stopActiveCollection() {
        obdMotion = ObdMotionEvidence()
        sampler?.cancelAndJoin(); sampler = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }

    private fun externallyPowered(): Boolean {
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 ||
            getSystemService(BatteryManager::class.java).isCharging
    }

    private suspend fun stopObdCollection() {
        obdJob?.cancelAndJoin(); obdJob = null
        obdAddressInUse = null
    }

    private suspend fun reconcileObdCollection() {
        val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
        val address = getSharedPreferences("obd_configuration", MODE_PRIVATE).getString("address", "").orEmpty()
        val wanted = monitorStarted && address.isNotBlank() && (externallyPowered() || tripId != null)
        if (!wanted) {
            if (obdJob != null) stopObdCollection()
            prefs.edit().putString("obd_status", when {
                !monitorStarted -> "monitor fermo"
                address.isBlank() -> "adattatore OBD non configurato"
                else -> "in attesa di alimentazione"
            }).apply()
            return
        }
        if (obdJob?.isActive == true && obdAddressInUse == address) return
        stopObdCollection()
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putString("obd_status", "Permesso Bluetooth mancante").apply(); return
        }
        obdAddressInUse = address
        obdJob = scope.launch {
            try {
                val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
                    ?: throw IllegalStateException("Bluetooth non disponibile")
                val transport = BluetoothSppObdTransport(device)
                ObdPoller(transport, onReading = { reading ->
                    val v = reading.values
                    val speed = v[ObdPid.SPEED]
                    val rpm = v[ObdPid.RPM]
                    val accelerator = v[ObdPid.ACCELERATOR_PEDAL_D] ?: v[ObdPid.ACCELERATOR_PEDAL_E] ?: v[ObdPid.ACCELERATOR_PEDAL]
                    val previousMotion = obdMotion
                    obdMotion = previousMotion.copy(
                        speedKmh = speed ?: previousMotion.speedKmh,
                        speedAt = if (speed != null) reading.observedAt else previousMotion.speedAt,
                        rpm = rpm ?: previousMotion.rpm,
                        rpmAt = if (rpm != null) reading.observedAt else previousMotion.rpmAt,
                        acceleratorPct = accelerator ?: previousMotion.acceleratorPct,
                        acceleratorAt = if (accelerator != null) reading.observedAt else previousMotion.acceleratorAt
                    )
                    val activeId = tripId
                    if (activeId != null) db.samples().insert(TelemetrySampleEntity(
                        UUID.randomUUID().toString(), activeId, reading.observedAt,
                        powerConnected = getSystemService(BatteryManager::class.java).isCharging,
                        obdRpm = v[ObdPid.RPM], obdSpeedKmh = v[ObdPid.SPEED], obdEngineLoadPct = v[ObdPid.ENGINE_LOAD],
                        obdThrottlePct = v[ObdPid.THROTTLE], obdCoolantC = v[ObdPid.COOLANT], obdIntakeTempC = v[ObdPid.INTAKE_TEMP],
                        obdMafGps = v[ObdPid.MAF], obdFuelLevelPct = v[ObdPid.FUEL_LEVEL], obdVoltageV = v[ObdPid.CONTROL_MODULE_VOLTAGE],
                        obdValuesJson = org.json.JSONObject().apply {
                            v.forEach { (pid, value) ->
                                // Keep decoded fields separate from older raw series with the same PID.
                                val field = when (pid) {
                                    ObdPid.OXYGEN_SENSOR_1_WIDEBAND_MODE34 -> "34.equivalence_ratio"
                                    ObdPid.ENGINE_FRICTION_TORQUE -> "8E.friction_torque_pct"
                                    else -> "%02X".format(pid.code)
                                }
                                put(field, value)
                            }
                            reading.subvalues.forEach { (field, value) -> put(field, value) }
                            reading.rawValues.forEach { (code, value) -> put("raw.%02X".format(code), value) }
                            reading.derived.forEach { (name, value) -> put(name, value) }
                        }.toString()
                    ))
                    getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                        .putLong("last_obd", reading.observedAt)
                        .putInt("obd_last_values", v.size + reading.subvalues.size + reading.rawValues.size + reading.derived.size).apply()
                }, onRawReply = { reply ->
                    try {
                        val activeId = tripId
                        if (activeId != null) db.rawReplies().insert(ObdRawReplyEntity(
                            UUID.randomUUID().toString(), activeId, reply.observedAt, reply.mode, reply.pid, reply.command,
                            reply.ecuId, reply.responseBytes.joinToString("") { "%02X".format(it) }, reply.rawResponse, reply.parseStatus
                        ))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                            .putString("obd_raw_status", "Salvataggio risposta OBD fallito: ${error.javaClass.simpleName}").apply()
                    }
                }, onStatus = { status ->
                    val detail = if (status.startsWith("connected;")) "$status; ${transport.linkMode}" else status
                    val edit = getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                        .putString("obd_status", detail).putLong("obd_status_at", System.currentTimeMillis())
                    if (status.startsWith("OBD non connesso:")) edit.putString("obd_last_error", status)
                    if (status.startsWith("connected;")) edit.remove("obd_last_error")
                    edit.apply()
                },
                    onDiscovery = { supported, known, raw -> getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                        .putInt("obd_supported", supported).putInt("obd_known", known).putInt("obd_raw", raw).apply() }).run()
            } catch (e: SecurityException) {
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("obd_status", "Bluetooth permission missing").apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("obd_status", e.message ?: "adapter error").apply()
            }
        }
    }

    private suspend fun scanDiagnostics() {
        val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
        prefs.edit().putString("diagnostic_status", "scansione in corso").remove("diagnostic_summary").apply()
        stopObdCollection()
        try {
            val address = getSharedPreferences("obd_configuration", MODE_PRIVATE).getString("address", "").orEmpty()
            require(address.isNotBlank()) { "Adattatore OBD non configurato" }
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Permesso Bluetooth mancante")
            val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
                ?: throw IllegalStateException("Bluetooth non disponibile")
            val report = DiagnosticScanner().scan(BluetoothSppObdTransport(device))
            val useful = listOf(report.emissionsStatus, report.storedCodes, report.pendingCodes, report.permanentCodes)
                .any { it.status == DiagnosticStatus.SUCCESS } || report.snapshot.values.any { it.status == DiagnosticStatus.SUCCESS }
            val observedAt = maxOf(System.currentTimeMillis(), prefs.getLong("diagnostic_at", 0L) + 1L)
            val reportJson = report.toJson()
            prefs.edit().putString("diagnostic_status", if (useful) "completata" else "nessuna risposta utile")
                .putString("diagnostic_summary", report.summary())
                .putString("diagnostic_report", reportJson)
                .putLong("diagnostic_at", observedAt).apply()
            DiagnosticReportSyncWorker.enqueue(this, observedAt, reportJson)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            prefs.edit().putString("diagnostic_status", "errore: ${error.message ?: error.javaClass.simpleName}").apply()
        } finally {
            when {
                monitorStarted -> reconcileObdCollection()
                !monitorStarted && tripId != null -> startMonitoring()
                !monitorStarted -> stopSelf()
            }
        }
    }

    private suspend fun identifyEcu() {
        val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
        prefs.edit().putString("ecu_identity_status", "lettura in corso")
            .remove("ecu_identity_summary").remove("ecu_identity_report").apply()
        stopObdCollection()
        try {
            val address = getSharedPreferences("obd_configuration", MODE_PRIVATE).getString("address", "").orEmpty()
            require(address.isNotBlank()) { "Adattatore OBD non configurato" }
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Permesso Bluetooth mancante")
            val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
                ?: throw IllegalStateException("Bluetooth non disponibile")
            val report = EcuIdentityScanner().scan(BluetoothSppObdTransport(device))
            val useful = report.reads.any { it.status == DiagnosticStatus.SUCCESS }
            val observedAt = maxOf(System.currentTimeMillis(), prefs.getLong("ecu_identity_at", 0L) + 1L)
            prefs.edit().putString("ecu_identity_status", if (useful) "completata" else "nessuna identificazione disponibile")
                .putString("ecu_identity_summary", report.summary())
                .putString("ecu_identity_report", report.toJson())
                .putLong("ecu_identity_at", observedAt).apply()
            EcuIdentitySyncWorker.scheduleOnce(this)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            prefs.edit().putString("ecu_identity_status", "errore: ${error.message ?: error.javaClass.simpleName}").apply()
        } finally {
            when {
                monitorStarted -> reconcileObdCollection()
                !monitorStarted && tripId != null -> startMonitoring()
                !monitorStarted -> stopSelf()
            }
        }
    }

    private suspend fun captureMode01() {
        val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
        prefs.edit().putString("mode01_capture_status", "acquisizione in corso")
            .remove("mode01_capture_report").apply()
        stopObdCollection()
        try {
            val address = getSharedPreferences("obd_configuration", MODE_PRIVATE).getString("address", "").orEmpty()
            require(address.isNotBlank()) { "Adattatore OBD non configurato" }
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Permesso Bluetooth mancante")
            val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
                ?: throw IllegalStateException("Bluetooth non disponibile")
            val report = Mode01Capture().scan(BluetoothSppObdTransport(device)) { current, total ->
                prefs.edit().putString("mode01_capture_status", "lettura $current/$total").apply()
            }
            val reads = report.getJSONArray("reads")
            val complete = (0 until reads.length()).count { reads.getJSONObject(it).getString("status") == "complete" }
            prefs.edit().putString("mode01_capture_status", "completata: $complete/${reads.length()} risposte complete")
                .putString("mode01_capture_report", report.toString())
                .putLong("mode01_capture_at", System.currentTimeMillis()).apply()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            prefs.edit().putString("mode01_capture_status", "errore: ${error.message ?: error.javaClass.simpleName}").apply()
        } finally {
            when {
                monitorStarted -> reconcileObdCollection()
                !monitorStarted && tripId != null -> startMonitoring()
                !monitorStarted -> stopSelf()
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(pairingReceiver)
        monitorStarted = false
        locations.stop()
        locationQueue.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
    companion object {
        const val ACTION_START_MONITOR="start_monitor"
        const val ACTION_STOP_MONITOR="stop_monitor"
        const val ACTION_SCAN_DIAGNOSTICS="scan_diagnostics"
        const val ACTION_IDENTIFY_ECU="identify_ecu"
        const val ACTION_CAPTURE_MODE01="capture_mode01"
        internal var locationCollectorFactory: (android.content.Context) -> LocationCollector = ::FusedLocationCollector
        private const val CHANNEL="blackbox"
    }
}
