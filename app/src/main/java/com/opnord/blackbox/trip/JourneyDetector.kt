package com.opnord.blackbox.trip

import android.location.Location
import java.util.UUID

data class StopBoundary(
    val stopId: String,
    val observedAt: Long,
    val latitude: Double,
    val longitude: Double
)

data class JourneyDecision(
    val startTrip: Boolean = false,
    val stopStart: StopBoundary? = null,
    val stopEnd: StopBoundary? = null,
    val closeTrip: Boolean = false
)

/** GPS motion owns trip boundaries. Power changes never enter this detector. */
class JourneyDetector(
    initialTripActive: Boolean = false,
    openStop: StopBoundary? = null
) {
    companion object {
        const val STOP_CONFIRM_MS = 2 * 60_000L
        const val TRIP_END_IDLE_MS = 90 * 60_000L
        private const val MAX_MOTION_ACCURACY_M = 35f
        private const val IDLE_ANCHOR_MAX_AGE_MS = 5 * 60_000L
    }

    private var tripActive = initialTripActive
    private var lastObservedAt = 0L
    private var idleAnchor: Location? = null
    private var movingFixes = 0
    private var motionAnchor: Location? = openStop?.let { point(it) }
    private var stationarySince: Long? = openStop?.observedAt
    private var stationaryPoint: Location? = openStop?.let { point(it) }
    private var currentStop = openStop

    fun observe(location: Location): JourneyDecision {
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
            return JourneyDecision(startTrip = true)
        }

        val anchor = motionAnchor
        if (anchor == null) {
            motionAnchor = Location(location)
            return JourneyDecision()
        }
        val distance = anchor.distanceTo(location)
        val moving = distance >= 60f ||
            (distance >= 20f && location.hasSpeed() && location.speed >= 3f)
        if (moving) {
            motionAnchor = Location(location)
            stationarySince = null
            stationaryPoint = null
            val ended = currentStop?.copy(
                observedAt = location.time,
                latitude = location.latitude,
                longitude = location.longitude
            )
            currentStop = null
            return JourneyDecision(stopEnd = ended)
        }

        val since = stationarySince ?: location.time.also {
            stationarySince = it
            stationaryPoint = Location(location)
        }
        if (currentStop == null && location.time - since >= STOP_CONFIRM_MS) {
            val point = stationaryPoint ?: location
            currentStop = StopBoundary(UUID.randomUUID().toString(), since, point.latitude, point.longitude)
            return JourneyDecision(stopStart = currentStop)
        }
        if (location.time - since >= TRIP_END_IDLE_MS) {
            val ended = currentStop?.copy(
                observedAt = location.time,
                latitude = location.latitude,
                longitude = location.longitude
            )
            tripActive = false
            currentStop = null
            stationarySince = null
            stationaryPoint = null
            idleAnchor = Location(location)
            movingFixes = 0
            return JourneyDecision(stopEnd = ended, closeTrip = true)
        }
        return JourneyDecision()
    }

    private fun point(boundary: StopBoundary) = Location("stored-stop").apply {
        time = boundary.observedAt
        latitude = boundary.latitude
        longitude = boundary.longitude
    }
}
