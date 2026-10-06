package com.opnord.blackbox.service

import android.app.*
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class BlackBoxService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: BlackBoxDatabase
    private var machine = TripStateMachine()
    private val lifecycleMutex = Mutex()
    @Volatile private var collectionGeneration = 0L
    private var tripId: String? = null
    private var sampler: Job? = null
    private var obdJob: Job? = null
    private var grace: Job? = null
    private var recovery: Job? = null
    private lateinit var locations: LocationCollector
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var monitorStarted = false
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> onEvent(TripEvent.PowerOn)
                Intent.ACTION_POWER_DISCONNECTED -> onEvent(TripEvent.PowerOff)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = BlackBoxDatabase.get(this)
        locations = locationCollectorFactory(this)
        val channel = NotificationChannel(CHANNEL, "Blackbox logger", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, CHANNEL).setContentTitle("Vehicle Blackbox").setContentText("Monitoraggio alimentazione attivo").setSmallIcon(android.R.drawable.ic_menu_mylocation).build())
        recovery = scope.launch {
            val recoveredTrip = db.withTransaction {
                db.trips().active()?.also { db.trips().close(it.id, System.currentTimeMillis(), "unexpected_shutdown") }
            }
            if (recoveredTrip != null) TelemetrySyncWorker.scheduleOnce(this@BlackBoxService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        scope.launch {
            recovery?.join()
            when (action) {
                ACTION_START_MONITOR -> startMonitoring()
                ACTION_STOP_MONITOR -> stopMonitoring()
                null -> startMonitoring() // START_STICKY recovery after process recreation.
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitorStarted) return
        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, null, Handler(Looper.getMainLooper()))
        monitorStarted = true
        // A connection may have happened before the receiver was registered.
        if (getSystemService(BatteryManager::class.java).isCharging) onEvent(TripEvent.PowerOn)
    }

    private suspend fun stopMonitoring() {
        if (monitorStarted) {
            unregisterReceiver(powerReceiver)
            monitorStarted = false
        }
        lifecycleMutex.withLock {
            grace?.cancel()
            stopCollection()
            tripId?.let { db.trips().close(it, System.currentTimeMillis(), "monitor_stopped") }
            tripId = null
            machine = TripStateMachine()
            TelemetrySyncWorker.scheduleOnce(this@BlackBoxService)
            stopSelf()
        }
    }

    private fun onEvent(event: TripEvent) {
        scope.launch { lifecycleMutex.withLock { handleEvent(event) } }
    }

    private suspend fun handleEvent(event: TripEvent) {
        if (!monitorStarted) return
        val transition = machine.on(event)
        when (transition.action) {
            "start_trip" -> startTrip()
            "start_grace" -> {
                grace?.cancel(); grace = scope.launch { delay(90_000); onEvent(TripEvent.GraceExpired) }
            }
            "cancel_grace" -> grace?.cancel()
            "close_trip" -> {
                stopCollection()
                tripId?.let { db.trips().close(it, System.currentTimeMillis(), "power_disconnected") }
                tripId = null
                TelemetrySyncWorker.scheduleOnce(this@BlackBoxService)
            }
        }
    }

    private suspend fun startTrip() {
        val trip = db.trips().active() ?: TripEntity(UUID.randomUUID().toString(), System.currentTimeMillis(), startReason = "power_connected")
        db.trips().insert(trip); tripId = trip.id
        val generation = ++collectionGeneration
        machine.on(TripEvent.DataSourceReady)
        if (wakeLock == null) {
            wakeLock = (getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VehicleBlackbox:logging").apply { setReferenceCounted(false); acquire() })
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locations.start { location ->
                    val currentTrip = trip.id
                    scope.launch {
                        lifecycleMutex.withLock {
                        if (generation != collectionGeneration || tripId != currentTrip) return@withLock
                        db.samples().insert(TelemetrySampleEntity(
                            UUID.randomUUID().toString(), currentTrip, location.time,
                            latitude = location.latitude, longitude = location.longitude,
                            altitude = location.altitude.takeIf { location.hasAltitude() },
                            gpsSpeedMps = location.speed.takeIf { location.hasSpeed() },
                            bearing = location.bearing.takeIf { location.hasBearing() },
                            horizontalAccuracy = location.accuracy.takeIf { location.hasAccuracy() },
                            powerConnected = getSystemService(BatteryManager::class.java).isCharging
                        ))
                        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putLong("last_gps", location.time).apply()
                        }
                    }
                }
            } catch (_: SecurityException) {
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("gps_status", "Location permission missing").apply()
            }
        } else getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("gps_status", "Location permission missing").apply()
        startObdCollection(trip.id)
        sampler?.cancel()
        sampler = scope.launch {
            while (isActive) {
                val battery = getSystemService(BatteryManager::class.java)
                val connected = battery.isCharging
                val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 }
                val temperature = (registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1).takeIf { it >= 0 }?.div(10f)
                db.samples().insert(TelemetrySampleEntity(UUID.randomUUID().toString(), trip.id, System.currentTimeMillis(), powerConnected = connected, batteryLevel = level, batteryTemperatureC = temperature))
                delay(1_000)
            }
        }
    }

    private suspend fun stopCollection() {
        collectionGeneration++
        locations.stop()
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
        if (monitorStarted) runCatching { unregisterReceiver(powerReceiver) }
        locations.stop(); wakeLock?.let { if (it.isHeld) it.release() }; scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
    companion object {
        const val ACTION_START_MONITOR="start_monitor"
        const val ACTION_STOP_MONITOR="stop_monitor"
        internal var locationCollectorFactory: (android.content.Context) -> LocationCollector = ::FusedLocationCollector
        private const val CHANNEL="blackbox"
    }
}
