package com.opnord.blackbox.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val startReason: String,
    val endReason: String? = null,
    val state: String = "ACTIVE",
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null
)

@Entity(tableName = "samples", primaryKeys = ["id"])
data class TelemetrySampleEntity(
    val id: String,
    val tripId: String,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val gpsSpeedMps: Float? = null,
    val bearing: Float? = null,
    val horizontalAccuracy: Float? = null,
    val powerConnected: Boolean,
    val batteryLevel: Int? = null,
    val batteryTemperatureC: Float? = null,
    val obdRpm: Double? = null,
    val obdSpeedKmh: Double? = null,
    val obdEngineLoadPct: Double? = null,
    val obdThrottlePct: Double? = null,
    val obdCoolantC: Double? = null,
    val obdIntakeTempC: Double? = null,
    val obdMafGps: Double? = null,
    val obdFuelLevelPct: Double? = null,
    val obdVoltageV: Double? = null,
    /** Complete numeric Mode 01 snapshot keyed by two-digit PID hex (for forward-compatible catalog growth). */
    val obdValuesJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null
)

@Entity(tableName = "pending_sync_batches")
data class PendingSyncBatchEntity(
    @PrimaryKey val batchId: String,
    val tripId: String,
    val payload: String,
    val sampleIds: String,
    val createdAt: Long = System.currentTimeMillis()
)
