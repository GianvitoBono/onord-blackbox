package com.opnord.blackbox.trip

import android.location.Location
import java.util.UUID

data class StopBoundary(
    val stopId: String,
    val observedAt: Long,
    val latitude: Double,
    val longitude: Double
)

data class ObdMotionEvidence(
    val speedKmh: Double? = null,
    val speedAt: Long = 0L,
    val rpm: Double? = null,
    val rpmAt: Long = 0L,
    val acceleratorPct: Double? = null,
    val acceleratorAt: Long = 0L
) {
    fun recentSpeed(at: Long): Double? = speedKmh?.takeIf { speedAt > 0 && kotlin.math.abs(at - speedAt) <= 10_000L }
    fun recentRpm(at: Long): Double? = rpm?.takeIf { rpmAt > 0 && kotlin.math.abs(at - rpmAt) <= 10_000L }
    fun recentAccelerator(at: Long): Double? = acceleratorPct?.takeIf { acceleratorAt > 0 && kotlin.math.abs(at - acceleratorAt) <= 10_000L }
}

data class JourneyDecision(
    val startTrip: Boolean = false,
    val stopStart: StopBoundary? = null,
    val stopEnd: StopBoundary? = null,
    val closeTrip: Boolean = false
)

/** GPS/OBD motion owns trip boundaries. Power changes never enter this detector. */
class JourneyDetector(
    initialTripActive: Boolean = false,
    openStop: StopBoundary? = null
) {
    companion object {
        const val STOP_CONFIRM_MS = 10_000L
        const val TRIP_END_IDLE_MS = 20 * 60_000L
        private const val MAX_MOTION_ACCURACY_M = 35f
        private const val IDLE_ANCHOR_MAX_AGE_MS = 5 * 60_000L
        private const val STATIONARY_RADIUS_M = 25f
    }

    private var tripActive = initialTripActive
    private var lastObservedAt = 0L
    private var idleAnchor: Location? = null
    private var movingFixes = 0
    private var motionAnchor: Location? = openStop?.let { point(it) }
    private var stationarySince: Long? = openStop?.observedAt
    private var stationaryPoint: Location? = openStop?.let { point(it) }
    private var currentStop = openStop
    private var departureCandidate: Location? = null
    private var stationaryPedalPct: Double? = null
    private var stationaryFixes = if (openStop != null) 2 else 0
    private var stationaryObdConfirmed = openStop != null

    fun observe(location: Location, obd: ObdMotionEvidence = ObdMotionEvidence()): JourneyDecision {
        if (location.time <= lastObservedAt) return JourneyDecision()
        lastObservedAt = location.time
        if (location.hasAccuracy() && location.accuracy > MAX_MOTION_ACCURACY_M) return JourneyDecision()

        if (!tripActive) {
            val anchor = idleAnchor
            if (anchor == null || location.time - anchor.time > IDLE_ANCHOR_MAX_AGE_MS) {
                idleAnchor = Location(location)
                movingFixes = 0
                return JourneyDecision()
            }
            val distance = anchor.distanceTo(location)
            val seconds = ((location.time - anchor.time) / 1000f).coerceAtLeast(1f)
            val moving = (distance >= 45f && location.hasSpeed() && location.speed >= 2f) ||
                (distance >= 100f && distance / seconds >= 1.2f)
            movingFixes = when {
                moving -> movingFixes + 1
                distance < 20f -> 0
                else -> movingFixes
            }
            if (movingFixes < 2) return JourneyDecision()
            tripActive = true
            idleAnchor = null
            movingFixes = 0
            motionAnchor = Location(location)
            stationarySince = null
            stationaryPoint = null
            stationaryPedalPct = null
            stationaryFixes = 0
            stationaryObdConfirmed = false
            return JourneyDecision(startTrip = true)
        }

        val anchor = motionAnchor
        if (anchor == null) {
            motionAnchor = Location(location)
            return JourneyDecision()
        }
        val distance = anchor.distanceTo(location)
        val engineStopped = obd.recentRpm(location.time)?.let { it < 300.0 } == true
        val obdSpeed = if (engineStopped) 0.0 else obd.recentSpeed(location.time)
        val gpsSpeed = location.speed.takeIf { location.hasSpeed() }
        val obdStopped = engineStopped || (obdSpeed != null && obdSpeed <= 1.0)
        val pedalRise = stationaryPedalPct?.let { baseline ->
            obd.recentAccelerator(location.time)?.let { it - baseline >= 8.0 }
        } == true
        val departureDistance = stationaryPoint?.distanceTo(location) ?: distance
        val moving = when {
            obdSpeed != null && obdSpeed >= 4.0 -> (gpsSpeed != null && gpsSpeed >= 1f) || distance >= 15f
            pedalRise && obdSpeed != null && obdSpeed >= 2.0 && gpsSpeed != null && gpsSpeed >= 0.8f -> true
            obdStopped -> departureDistance >= 100f && gpsSpeed != null && gpsSpeed >= 3f
            stationarySince != null -> departureDistance >= 60f
            else -> distance >= 60f || (distance >= 20f && gpsSpeed != null && gpsSpeed >= 3f)
        }
        if (moving) {
            if (currentStop != null) {
                val candidate = departureCandidate
                if (candidate == null) {
                    departureCandidate = Location(location)
                    return JourneyDecision()
                }
                // A single displaced GPS fix must not end a stop. Require progress or two fast fixes.
                val progressed = candidate.distanceTo(location) >= 15f ||
                    (candidate.hasSpeed() && location.hasSpeed() && candidate.speed >= 3f && location.speed >= 3f)
                if (!progressed) return JourneyDecision()
                val ended = currentStop?.copy(
                    observedAt = candidate.time,
                    latitude = candidate.latitude,
                    longitude = candidate.longitude
                )
                departureCandidate = null
                currentStop = null
                motionAnchor = Location(location)
                stationarySince = null
                stationaryPoint = null
                stationaryPedalPct = null
                stationaryFixes = 0
                stationaryObdConfirmed = false
                return JourneyDecision(stopEnd = ended)
            }
            motionAnchor = Location(location)
            stationarySince = null
            stationaryPoint = null
            stationaryPedalPct = null
            stationaryFixes = 0
            stationaryObdConfirmed = false
            return JourneyDecision()
        }
        departureCandidate = null

        val stationaryAnchor = stationaryPoint
        val stationary = if (obdStopped) {
            stationaryAnchor == null || stationaryAnchor.distanceTo(location) <= 50f
        } else {
            (obdSpeed == null || engineStopped) && (!location.hasAccuracy() || location.accuracy <= 20f) &&
                (stationaryAnchor == null || stationaryAnchor.distanceTo(location) <= STATIONARY_RADIUS_M)
        }
        if (!stationary) {
            stationarySince = null
            stationaryPoint = null
            stationaryPedalPct = null
            stationaryFixes = 0
            stationaryObdConfirmed = false
            return JourneyDecision()
        }

        val since = stationarySince ?: location.time.also {
            stationarySince = it
            stationaryPoint = Location(location)
            stationaryPedalPct = obd.recentAccelerator(location.time)
        }
        stationaryFixes++
        if (obdStopped) stationaryObdConfirmed = true
        return advanceStationary(location.time, obd)
    }

    /** Advance confirmed standstill even when Android stops delivering stationary GPS fixes. */
    fun tick(now: Long, obd: ObdMotionEvidence = ObdMotionEvidence()): JourneyDecision =
        advanceStationary(now, obd)

    private fun advanceStationary(now: Long, obd: ObdMotionEvidence): JourneyDecision {
        val since = stationarySince ?: return JourneyDecision()
        val point = stationaryPoint ?: return JourneyDecision()
        if (!tripActive || (stationaryFixes < 2 && !stationaryObdConfirmed) ||
            now < since + STOP_CONFIRM_MS) return JourneyDecision()
        val started = if (currentStop == null) StopBoundary(
            UUID.randomUUID().toString(), since, point.latitude, point.longitude
        ).also { currentStop = it } else null
        val engineRunningRecently = obd.rpm != null && obd.rpm >= 300.0 &&
            obd.rpmAt > 0 && now >= obd.rpmAt && now - obd.rpmAt < 2 * 60_000L
        if (now < since + TRIP_END_IDLE_MS || engineRunningRecently) {
            return JourneyDecision(stopStart = started)
        }
        val ended = currentStop?.copy(observedAt = now, latitude = point.latitude, longitude = point.longitude)
        tripActive = false
        currentStop = null
        stationarySince = null
        stationaryPoint = null
        stationaryPedalPct = null
        stationaryFixes = 0
        stationaryObdConfirmed = false
        idleAnchor = Location(point)
        movingFixes = 0
        return JourneyDecision(stopStart = started, stopEnd = ended, closeTrip = true)
    }

    private fun point(boundary: StopBoundary) = Location("stored-stop").apply {
        time = boundary.observedAt
        latitude = boundary.latitude
        longitude = boundary.longitude
    }
}
