package com.opnord.blackbox.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ObdReading(
    val observedAt: Long,
    val values: Map<ObdPid, Double>,
    val errors: List<ObdResult>,
    val derived: Map<String, Double> = emptyMap(),
    /** Advertised standard PIDs without a known SAE formula, represented as unsigned raw integers. */
    val rawValues: Map<Int, Long> = emptyMap(),
    /** Separately decoded fields of a multi-value standard PID. */
    val subvalues: Map<String, Double> = emptyMap()
)

data class RawObdReply(val observedAt: Long, val command: String, val mode: Int, val pid: Int?,
    val responseBytes: List<Int>, val rawResponse: String, val parseStatus: String, val ecuId: String? = null)

/** Serial, bounded discovery/poll loop. Errors are reported and never terminate the caller's logger. */
class ObdPoller(
    private val transport: ObdTransport,
    private val intervalMs: Long = 2_000,
    private val commandTimeoutMs: Long = 2_500,
    private val onReading: suspend (ObdReading) -> Unit,
    private val onRawReply: suspend (RawObdReply) -> Unit = {},
    private val onStatus: (String) -> Unit = {},
    private val onDiscovery: (supported: Int, known: Int, raw: Int) -> Unit = { _, _, _ -> }
) {
    suspend fun run() {
        var retry = 0
        try {
        while (true) {
            try {
                onStatus("connecting")
                withTimeout(10_000) { transport.connect() }
                // Preserve ECU headers when supported; a clone rejecting this setting must not stop polling.
                try {
                    transport.exchange("ATH1", commandTimeoutMs)
                    transport.exchange("ATS1", commandTimeoutMs)
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { onStatus("connected; ECU header unavailable") }
                retry = 0
                val supported = discover()
                // Frequent driving signals first; slower/less useful values rotate in bounded batches.
                val priority = listOf(ObdPid.RPM, ObdPid.SPEED, ObdPid.ENGINE_LOAD, ObdPid.THROTTLE,
                    ObdPid.MAP, ObdPid.BAROMETRIC_PRESSURE, ObdPid.DEMANDED_TORQUE, ObdPid.ACTUAL_TORQUE)
                val known = supported.mapNotNull(ObdPid::fromCode)
                val requested = priority.filter { it.code in supported } + known.filter { it !in priority }
                val rawRequested = supported.filter { ObdPid.fromCode(it) == null }.sorted()
                onDiscovery(supported.size, known.size, rawRequested.size)
                onStatus("connected; supported_pids=${supported.size}; known=${known.size}; raw=${rawRequested.size}")
                var cursor = 0
                var rawCursor = 0
                var emptyRounds = 0
                val latest = mutableMapOf<ObdPid, Pair<Long, Double>>()
                while (true) {
                    val roundStarted = System.currentTimeMillis()
                    val values = mutableMapOf<ObdPid, Double>()
                    val rawValues = mutableMapOf<Int, Long>()
                    val subvalues = mutableMapOf<String, Double>()
                    val errors = mutableListOf<ObdResult>()
                    // Keep each sample responsive even when ECU advertises dozens of PIDs.
                    val batch = if (requested.size <= 8) requested else {
                        val rotating = requested.filterNot { it in priority }
                        val extras = if (rotating.isEmpty()) emptyList() else
                            (0 until minOf(4, rotating.size)).map { rotating[(cursor + it) % rotating.size] }
                        (requested.filter { it in priority } + extras).distinct()
                    }
                    batch.forEach { pid ->
                        val command = "01%02X".format(pid.code)
                        try {
                            val response = withTimeout(commandTimeoutMs) { transport.exchange(command, commandTimeoutMs) }
                            val parsed = Elm327Parser.parse(response, pid)
                            onRawReply(rawReply(command, response, when (parsed) {
                                is ObdResult.Value -> "complete"
                                is ObdResult.Unsupported -> "unsupported"
                                is ObdResult.Malformed -> "malformed"
                                is ObdResult.AdapterError -> "adapter_error"
                            }))
                            when (val result = parsed) {
                                is ObdResult.Value -> {
                                    values[pid] = result.value
                                    result.subvalues.forEach { (field, value) -> subvalues["%02X.$field".format(pid.code)] = value }
                                }
                                else -> errors += result
                            }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            onRawReply(rawReply(command, "ERROR: timeout", "timeout"))
                            errors += ObdResult.AdapterError("command timeout")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            onRawReply(rawReply(command, "ERROR: ${e.message ?: e.javaClass.simpleName}", "error"))
                            errors += ObdResult.AdapterError(e.message ?: "command failed")
                        }
                        delay(75)
                    }
                    val rotatingCount = requested.count { it !in priority }
                    if (rotatingCount > 0) cursor = (cursor + minOf(4, rotatingCount)) % rotatingCount
                    val rawBatch = if (rawRequested.isEmpty()) emptyList() else
                        (0 until minOf(2, rawRequested.size)).map { rawRequested[(rawCursor + it) % rawRequested.size] }
                    rawBatch.forEach { code ->
                        val command = "01%02X".format(code)
                        try {
                            val response = withTimeout(commandTimeoutMs) { transport.exchange(command, commandTimeoutMs) }
                            val value = Elm327Parser.rawUnsignedValue(response, code)
                            onRawReply(rawReply(command, response, Elm327Parser.rawStatus(response, code)))
                            value?.let { rawValues[code] = it }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            onRawReply(rawReply(command, "ERROR: timeout", "timeout"))
                            errors += ObdResult.AdapterError("command timeout")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            onRawReply(rawReply(command, "ERROR: ${e.message ?: e.javaClass.simpleName}", "error"))
                            errors += ObdResult.AdapterError(e.message ?: "command failed")
                        }
                        delay(75)
                    }
                    if (rawRequested.isNotEmpty()) rawCursor = (rawCursor + minOf(2, rawRequested.size)) % rawRequested.size
                    val observedAt = System.currentTimeMillis()
                    values.forEach { (pid, value) -> latest[pid] = observedAt to value }
                    val derived = mutableMapOf<String, Double>()
                    val map = latest[ObdPid.MAP]
                    val baro = latest[ObdPid.BAROMETRIC_PRESSURE]
                    if (map != null && baro != null && observedAt - map.first < 30_000 && observedAt - baro.first < 300_000 &&
                        (ObdPid.MAP in values || ObdPid.BAROMETRIC_PRESSURE in values)) {
                        derived["calc.manifold_gauge_pressure_kpa"] = map.second - baro.second
                    }
                    val actual = latest[ObdPid.ACTUAL_TORQUE]
                    val reference = latest[ObdPid.REFERENCE_TORQUE]
                    if (actual != null && reference != null && observedAt - actual.first < 30_000 && observedAt - reference.first < 300_000 &&
                        (ObdPid.ACTUAL_TORQUE in values || ObdPid.REFERENCE_TORQUE in values)) {
                        derived["calc.engine_torque_nm"] = actual.second * reference.second / 100.0
                    }
                    if (values.isNotEmpty() || rawValues.isNotEmpty()) {
                        emptyRounds = 0
                        onReading(ObdReading(observedAt, values, errors, derived, rawValues, subvalues))
                    } else if (requested.isNotEmpty() || rawRequested.isNotEmpty()) {
                        emptyRounds++
                        onStatus("connected; supported_pids=${supported.size}; no_data_rounds=$emptyRounds; errors=${errors.size}")
                        if (emptyRounds >= 3) throw IllegalStateException("No OBD data after 3 polling rounds")
                    }
                    delay((intervalMs.coerceIn(250, 10_000) - (System.currentTimeMillis() - roundStarted)).coerceAtLeast(0L))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                onStatus("disconnected: ${e.message ?: "adapter error"}")
                runCatching { transport.disconnect() }
                delay((1_000L shl retry.coerceIn(0, 4)).coerceAtMost(15_000))
                retry++
            }
        }
        } finally {
            withContext(NonCancellable) { runCatching { transport.disconnect() } }
        }
    }

    private suspend fun discover(): Set<Int> {
        val discovered = mutableSetOf<Int>()
        var start = 0x00
        while (start <= 0xE0) {
            val command = "01%02X".format(start)
            try {
                val response = withTimeout(commandTimeoutMs) { transport.exchange(command, commandTimeoutMs) }
                onRawReply(rawReply(command, response, Elm327Parser.rawStatus(response, start)))
                discovered += Elm327Parser.supportedPidCodes(response, start)
                // A clear continuation bit means later 32-PID blocks are unavailable.
                val bytes = Elm327Parser.responseBytes(response)
                val ix = (0 until bytes.size - 1).firstOrNull { bytes[it] == 0x41 && bytes[it + 1] == start }
                if (ix == null || bytes.size < ix + 6 || (bytes[ix + 5] and 1) == 0) break
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { break }
            start += 0x20
        }
        return discovered
    }

    private fun rawReply(command: String, raw: String, status: String) = RawObdReply(
        System.currentTimeMillis(), command, command.take(2).toIntOrNull(16) ?: 1,
        command.drop(2).take(2).toIntOrNull(16), Elm327Parser.responseBytes(raw), raw.take(4096), status,
        ecuId = raw.lineSequence().mapNotNull { line ->
            val text = line.trim().uppercase()
            Regex("^[0-9A-F]{8}(?=\\s)").find(text)?.value
                ?: Regex("^18\\s+D[AB]\\s+[0-9A-F]{2}\\s+[0-9A-F]{2}(?=\\s)")
                    .find(text)?.value?.replace(Regex("\\s+"), "")
                ?: Regex("^[0-9A-F]{3}(?=\\s)").find(text)?.value
        }.distinct().singleOrNull()
    )
}
