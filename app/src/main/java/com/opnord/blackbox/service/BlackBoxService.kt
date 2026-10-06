package com.opnord.blackbox.service

import android.app.*
import android.content.Intent
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
    private val lifecycleMutex = Mutex()
    private val locationQueue = Channel<Location>(Channel.UNLIMITED)
    private val recentLocations = ArrayDeque<Location>()
    private var lastProcessedLocationAt = 0L
    private var tripId: String? = null
    private var sampler: Job? = null
    private var obdJob: Job? = null
    private var recovery: Job? = null
    private lateinit var locations: LocationCollector
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var monitorStarted = false

    override fun onCreate() {
        super.onCreate()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        scope.launch {
            recovery?.join()
            when (action) {
                ACTION_START_MONITOR -> lifecycleMutex.withLock { startMonitoring() }
                ACTION_STOP_MONITOR -> stopMonitoring()
                null -> lifecycleMutex.withLock { startMonitoring() } // START_STICKY recovery.
            }
        }
        return START_STICKY
    }

    private suspend fun startMonitoring() {
        if (monitorStarted) {
            startLocations(idle = tripId == null)
            return
        }
        monitorStarted = true
        if (tripId != null) startActiveCollection(tripId!!)
        startLocations(idle = tripId == null)
        updateNotification(if (tripId == null) "Ricerca movimento" else "Viaggio in corso")
    }

    private suspend fun stopMonitoring() {
        lifecycleMutex.withLock {
            monitorStarted = false
            locations.stop()
            stopActiveCollection()
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

        val decision = detector.observe(location)
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
        if (decision.closeTrip) {
            tripId = null
            recentLocations.clear()
            recentLocations.addLast(Location(location))
            startLocations(idle = true)
            updateNotification("Ricerca movimento")
            TelemetrySyncWorker.scheduleOnce(this)
        }
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
            .putLong("last_gps", location.time)
            .putString("gps_status", if (decision.closeTrip) "Ricerca movimento" else "GPS attivo").apply()
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
        startObdCollection(forTripId)
        sampler?.cancel()
        sampler = scope.launch {
            while (isActive) {
                val battery = getSystemService(BatteryManager::class.java)
                val connected = battery.isCharging
                val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 }
                val temperature = (registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1).takeIf { it >= 0 }?.div(10f)
                db.samples().insert(TelemetrySampleEntity(UUID.randomUUID().toString(), forTripId, System.currentTimeMillis(), powerConnected = connected, batteryLevel = level, batteryTemperatureC = temperature))
                delay(1_000)
            }
        }
    }

    private suspend fun stopActiveCollection() {
        sampler?.cancelAndJoin(); sampler = null
        obdJob?.cancelAndJoin(); obdJob = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }

    private fun startObdCollection(forTripId: String) {
        obdJob?.cancel(); obdJob = null
        val address = getSharedPreferences("obd_configuration", MODE_PRIVATE).getString("address", "").orEmpty()
        if (address.isBlank()) return
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("obd_status", "Bluetooth permission missing").apply(); return
        }
        obdJob = scope.launch {
            try {
                val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
                if (device == null) return@launch
                ObdPoller(BluetoothSppObdTransport(device), onReading = { reading ->
                    val v = reading.values
                    db.samples().insert(TelemetrySampleEntity(
                        UUID.randomUUID().toString(), forTripId, reading.observedAt,
                        powerConnected = getSystemService(BatteryManager::class.java).isCharging,
                        obdRpm = v[ObdPid.RPM], obdSpeedKmh = v[ObdPid.SPEED], obdEngineLoadPct = v[ObdPid.ENGINE_LOAD],
                        obdThrottlePct = v[ObdPid.THROTTLE], obdCoolantC = v[ObdPid.COOLANT], obdIntakeTempC = v[ObdPid.INTAKE_TEMP],
                        obdMafGps = v[ObdPid.MAF], obdFuelLevelPct = v[ObdPid.FUEL_LEVEL], obdVoltageV = v[ObdPid.CONTROL_MODULE_VOLTAGE],
                        obdValuesJson = org.json.JSONObject().apply {
                            v.forEach { (pid, value) -> put("%02X".format(pid.code), value) }
                            reading.derived.forEach { (name, value) -> put(name, value) }
                        }.toString()
                    ))
                }, onStatus = { status -> getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("obd_status", status).apply() }).run()
            } catch (e: SecurityException) {
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("obd_status", "Bluetooth permission missing").apply()
            } catch (e: Exception) {
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("obd_status", e.message ?: "adapter error").apply()
            }
        }
    }

    override fun onDestroy() {
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
        internal var locationCollectorFactory: (android.content.Context) -> LocationCollector = ::FusedLocationCollector
        private const val CHANNEL="blackbox"
    }
}
