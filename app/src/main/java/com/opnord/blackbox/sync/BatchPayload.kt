package com.opnord.blackbox.sync

import com.opnord.blackbox.obd.ObdPid
import com.opnord.blackbox.storage.TelemetrySampleEntity
import com.opnord.blackbox.storage.TripEventEntity
import com.opnord.blackbox.storage.TripEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class EncodedBatch(val payload: String, val sampleIds: List<String>, val eventIds: List<String>)

internal object BatchPayload {
    fun encode(deviceId: String, batchId: String, trip: TripEntity, samples: List<TelemetrySampleEntity>): EncodedBatch =
        encode(deviceId, batchId, trip, samples, emptyList())

    fun encode(deviceId: String, batchId: String, trip: TripEntity, samples: List<TelemetrySampleEntity>, events: List<TripEventEntity>): EncodedBatch {
        val tripJson = JSONObject()
            .put("id", trip.id).put("startedAt", timestamp(trip.startedAt))
            .put("endedAt", trip.endedAt?.let(::timestamp) ?: JSONObject.NULL)
            .put("startReason", trip.startReason).put("endReason", trip.endReason ?: JSONObject.NULL)
            .put("distanceGpsM", JSONObject.NULL).put("distanceObdM", JSONObject.NULL)
        val gps = JSONArray()
        val obd = JSONArray()
        val device = JSONArray()
        val tripEvents = JSONArray()
        events.forEach { event ->
            tripEvents.put(JSONObject().put("eventId", event.eventId).put("stopId", event.stopId)
                .put("kind", event.kind).put("observedAt", timestamp(event.observedAt))
                .put("latitude", event.latitude).put("longitude", event.longitude))
        }
        samples.forEach { sample ->
            if (sample.latitude != null && sample.longitude != null) {
                gps.put(JSONObject().put("sampleId", sample.id).put("observedAt", timestamp(sample.timestamp))
                    .put("latitude", sample.latitude).put("longitude", sample.longitude)
                    .put("altitudeM", sample.altitude ?: JSONObject.NULL)
                    .put("speedMps", sample.gpsSpeedMps ?: JSONObject.NULL)
                    .put("bearingDeg", sample.bearing ?: JSONObject.NULL)
                    .put("horizontalAccuracyM", sample.horizontalAccuracy ?: JSONObject.NULL))
            }
            if (sample.latitude == null || sample.batteryLevel != null || sample.batteryTemperatureC != null) {
                device.put(JSONObject().put("sampleId", sample.id).put("observedAt", timestamp(sample.timestamp))
                    .put("tripId", sample.tripId).put("powerConnected", sample.powerConnected)
                    .put("batteryPct", sample.batteryLevel?.toFloat() ?: JSONObject.NULL)
                    .put("batteryTempC", sample.batteryTemperatureC ?: JSONObject.NULL))
            }
            val values = runCatching { JSONObject(sample.obdValuesJson) }.getOrDefault(JSONObject())
            // Older rows predate the generic PID snapshot; retain their original nine fields.
            if (values.length() == 0) listOf("0C" to sample.obdRpm, "0D" to sample.obdSpeedKmh, "04" to sample.obdEngineLoadPct,
                "11" to sample.obdThrottlePct, "05" to sample.obdCoolantC, "0F" to sample.obdIntakeTempC,
                "10" to sample.obdMafGps, "2F" to sample.obdFuelLevelPct, "42" to sample.obdVoltageV)
                .forEach { (pid, value) -> if (value != null) values.put(pid, value) }
            values.keys().asSequence().toList().sorted().forEach { shortPid ->
                val isRaw = shortPid.startsWith("raw.")
                val code = if (isRaw) shortPid.removePrefix("raw.") else shortPid
                val pid = if (shortPid.startsWith("calc.")) shortPid else "01$code"
                val value = values.optDouble(shortPid, Double.NaN)
                if (value.isFinite()) {
                    val id = java.util.UUID.nameUUIDFromBytes("${sample.id}:$pid".toByteArray(Charsets.UTF_8)).toString()
                    val definition = if (isRaw) null else shortPid.toIntOrNull(16)?.let(ObdPid::fromCode)
                    val decodedField = when (shortPid) {
                        "34.equivalence_ratio" -> "Sonda O₂ 1 · rapporto aria/carburante" to "ratio"
                        "34.current_ma" -> "Sonda O₂ 1 · corrente" to "mA"
                        "8E.friction_torque_pct" -> "Coppia assorbita da attriti motore" to "%"
                        else -> null
                    }
                    val derivedName = when (shortPid) {
                        "calc.manifold_gauge_pressure_kpa" -> "Estimated manifold gauge pressure (MAP − barometric)"
                        "calc.engine_torque_nm" -> "Estimated engine torque (actual % × reference Nm)"
                        else -> null
                    }
                    val derivedUnit = when (shortPid) {
                        "calc.manifold_gauge_pressure_kpa" -> "kPa"
                        "calc.engine_torque_nm" -> "Nm"
                        else -> null
                    }
                    val rawName = if (isRaw) "Mode 01 PID $code raw unsigned integer" else null
                    val rawUnit = if (isRaw) "raw_unsigned_integer" else null
                    obd.put(JSONObject().put("sampleId", id).put("observedAt", timestamp(sample.timestamp)).put("pid", pid).put("value", value)
                        .put("name", decodedField?.first ?: definition?.label?.replace('_', ' ') ?: derivedName ?: rawName ?: JSONObject.NULL)
                        .put("unit", decodedField?.second ?: definition?.unit ?: derivedUnit ?: rawUnit ?: JSONObject.NULL))
                }
            }
        }
        val body = JSONObject().put("schemaVersion", 1).put("deviceId", deviceId).put("batchId", batchId)
            .put("trip", tripJson).put("gpsSamples", gps).put("obdSamples", obd).put("deviceSamples", device)
            .put("tripEvents", tripEvents)
        return EncodedBatch(body.toString(), samples.map { it.id }, events.map { it.eventId })
    }

    private fun timestamp(millis: Long) = Instant.ofEpochMilli(millis).toString()
}
