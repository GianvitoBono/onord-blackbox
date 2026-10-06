package com.opnord.blackbox.obd

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale

/** Result of one read-only diagnostic request. Unsupported means the ECU/adapter rejected the mode. */
enum class DiagnosticStatus { SUCCESS, UNSUPPORTED, ERROR, MALFORMED }

data class DiagnosticQuery<T>(
    val status: DiagnosticStatus,
    val value: T? = null,
    val detail: String? = null
)

/** Mode 01 PID 01 status, including raw monitor bytes for conservative interpretation. */
data class EmissionsStatus(
    val milOn: Boolean,
    val storedDtcCount: Int,
    val ignitionType: IgnitionType,
    /** Raw PID 01 monitor bytes, retained to avoid claiming unsupported monitor semantics. */
    val readinessBytes: List<Int>
)

enum class IgnitionType { SPARK, COMPRESSION }

data class DiagnosticTroubleCode(
    val code: String,
    val ecu: String? = null
)

data class DiagnosticReport(
    val emissionsStatus: DiagnosticQuery<EmissionsStatus>,
    val storedCodes: DiagnosticQuery<List<DiagnosticTroubleCode>>,
    val pendingCodes: DiagnosticQuery<List<DiagnosticTroubleCode>>,
    val permanentCodes: DiagnosticQuery<List<DiagnosticTroubleCode>>,
    val snapshot: Map<String, DiagnosticQuery<Double>> = emptyMap(),
    val observations: List<String> = emptyList(),
    val elmProtocol: DiagnosticQuery<Int> = DiagnosticQuery(DiagnosticStatus.UNSUPPORTED, detail = "non rilevato"),
    val rawResponses: Map<String, String> = emptyMap()
) {
    /** Compact Italian status line suitable for the app's diagnostics screen. */
    fun summary(): String = buildList {
        emissionsStatus.value?.let { status ->
            add("MIL ${if (status.milOn) "accesa" else "spenta"}; ${status.storedDtcCount} DTC indicati")
            add("Readiness raw: ${status.readinessBytes.joinToString(" ") { "%02X".format(it) }}")
        } ?: emissionsStatus.detail?.let { add("Stato MIL: ${unavailable(emissionsStatus, it)}") }
        addCodes("Memorizzati", storedCodes)
        addCodes("In attesa", pendingCodes)
        addCodes("Permanenti", permanentCodes)
        snapshot.forEach { (name, query) -> query.value?.let { add("$name=${String.format(Locale.ROOT, "%.1f", it)}") } }
        if (observations.isNotEmpty()) add("Da verificare: ${observations.joinToString()}")
        elmProtocol.value?.let { add("Protocollo ELM $it") }
    }.joinToString(" · ")

    /** Stable JSON representation for SharedPreferences and future API upload. */
    fun toJson(): String = JSONObject().apply {
        put("summary", summary())
        put("emissions_status", emissionsStatus.toJson { value -> JSONObject().apply {
            put("mil_on", value.milOn)
            put("stored_dtc_count", value.storedDtcCount)
            put("ignition_type", value.ignitionType.name.lowercase())
            put("readiness_bytes", JSONArray(value.readinessBytes))
        } })
        put("stored_codes", storedCodes.toJson(::codesToJson))
        put("pending_codes", pendingCodes.toJson(::codesToJson))
        put("permanent_codes", permanentCodes.toJson(::codesToJson))
        put("snapshot", JSONObject().apply { snapshot.forEach { (name, query) ->
            put(name, query.toJson { it })
        } })
        put("observations", JSONArray(observations))
        put("elm_protocol", elmProtocol.toJson { it })
        put("raw_responses", JSONObject(rawResponses))
    }.toString()

    private fun <T> DiagnosticQuery<T>.toJson(encode: (T) -> Any): JSONObject = JSONObject().apply {
        put("status", status.name.lowercase())
        put("detail", detail ?: JSONObject.NULL)
        put("value", value?.let(encode) ?: JSONObject.NULL)
    }

    private fun codesToJson(codes: List<DiagnosticTroubleCode>): JSONArray = JSONArray().apply {
        codes.forEach { put(JSONObject().put("code", it.code).put("ecu", it.ecu ?: JSONObject.NULL)) }
    }

    private fun MutableList<String>.addCodes(label: String, query: DiagnosticQuery<List<DiagnosticTroubleCode>>) {
        when (query.status) {
            DiagnosticStatus.SUCCESS -> {
                val codes = query.value.orEmpty().map { if (it.ecu == null) it.code else "${it.code} [${it.ecu}]" }
                add("$label: ${codes.ifEmpty { listOf("nessuno") }.joinToString()}")
            }
            else -> add("$label: ${unavailable(query, query.detail ?: "non disponibili")}")
        }
    }

    private fun unavailable(query: DiagnosticQuery<*>, detail: String): String = when (query.status) {
        DiagnosticStatus.UNSUPPORTED -> "nessuna risposta o servizio non disponibile ($detail)"
        DiagnosticStatus.ERROR -> "errore ($detail)"
        DiagnosticStatus.MALFORMED -> "risposta non valida ($detail)"
        DiagnosticStatus.SUCCESS -> detail
    }
}

/** Read-only SAE OBD-II scan. Never sends Mode 04 or any other clear/reset command. */
class DiagnosticScanner(
    private val commandTimeoutMs: Long = 4_000,
    private val connectTimeoutMs: Long = 10_000
) {
    suspend fun scan(transport: ObdTransport): DiagnosticReport {
        try {
            withTimeout(connectTimeoutMs) { transport.connect() }
            val rawResponses = linkedMapOf<String, String>()
            val protocol = request(transport, "ATDPN", ::parseElmProtocol) { rawResponses["ATDPN"] = it }
            val canProtocol = protocol.value?.let { it in 6..9 }
            val emissions = request(transport, "0101", ::parseEmissionsStatus) { rawResponses["0101"] = it }
            var canHeaders = false
            if (canProtocol == true) {
                try {
                    transport.exchange("ATCFC1", commandTimeoutMs)
                    canHeaders = transport.exchange("ATH1", commandTimeoutMs).contains("OK", ignoreCase = true)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Fall back to adapter-formatted response. */ }
            }
            val stored = request(transport, "03", { parseTroubleCodes(it, 0x43, canProtocol, canHeaders) }) { rawResponses["03"] = it }
            val pending = request(transport, "07", { parseTroubleCodes(it, 0x47, canProtocol, canHeaders) }) { rawResponses["07"] = it }
            val permanent = request(transport, "0A", { parseTroubleCodes(it, 0x4A, canProtocol, canHeaders) }) { rawResponses["0A"] = it }
            val snapshot = readSnapshot(transport)
            val rpm = snapshot["rpm"]?.value
            val observations = buildList {
                snapshot["coolant_c"]?.value?.takeIf { it > 115.0 }?.let { add("liquido refrigerante oltre 115 °C") }
                val voltage = snapshot["module_voltage_v"]?.value
                if (rpm != null && rpm > 500.0 && voltage != null && (voltage < 11.0 || voltage > 16.0)) {
                    add("tensione centralina fuori 11–16 V a motore avviato")
                }
            }
            return DiagnosticReport(
                emissionsStatus = emissions,
                storedCodes = stored,
                pendingCodes = pending,
                permanentCodes = permanent,
                snapshot = snapshot,
                observations = observations,
                elmProtocol = protocol,
                rawResponses = rawResponses
            )
        } catch (e: TimeoutCancellationException) {
            val failed = e.message ?: "OBD connection timed out"
            return DiagnosticReport(
                DiagnosticQuery(DiagnosticStatus.ERROR, detail = failed),
                DiagnosticQuery(DiagnosticStatus.ERROR, detail = failed),
                DiagnosticQuery(DiagnosticStatus.ERROR, detail = failed),
                DiagnosticQuery(DiagnosticStatus.ERROR, detail = failed)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = e.message ?: "OBD connection failed"
            return DiagnosticReport(
                DiagnosticQuery(DiagnosticStatus.ERROR, detail = detail),
                DiagnosticQuery(DiagnosticStatus.ERROR, detail = detail),
                DiagnosticQuery(DiagnosticStatus.ERROR, detail = detail),
                DiagnosticQuery(DiagnosticStatus.ERROR, detail = detail)
            )
        } finally {
            withContext(NonCancellable) { runCatching { transport.disconnect() } }
        }
    }

    private suspend fun <T> request(
        transport: ObdTransport,
        command: String,
        parser: (String) -> DiagnosticQuery<T>,
        rawCapture: (String) -> Unit = {}
    ): DiagnosticQuery<T> {
        return try {
            val response = withTimeout(commandTimeoutMs) {
                transport.exchange(command, commandTimeoutMs)
            }
            rawCapture(response)
            parser(response)
        } catch (e: TimeoutCancellationException) {
            DiagnosticQuery(DiagnosticStatus.ERROR, detail = "OBD request timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticQuery(DiagnosticStatus.ERROR, detail = e.message ?: "Adapter request failed")
        }
    }

    companion object {
        private val adapterErrorMarkers = listOf("UNABLE TO CONNECT", "BUS INIT", "CAN ERROR", "BUS ERROR", "STOPPED")

        fun parseEmissionsStatus(raw: String): DiagnosticQuery<EmissionsStatus> {
            val bytes = Elm327Parser.responseBytes(raw)
            val index = bytes.windowed(2).indexOfFirst { it[0] == 0x41 && it[1] == 0x01 }
            if (index >= 0) {
                if (bytes.size < index + 6) return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "PID 01 response is truncated")
                val a = bytes[index + 2]
                val b = bytes[index + 3]
                val c = bytes[index + 4]
                val d = bytes[index + 5]
                val compression = (b and 0x08) != 0
                return DiagnosticQuery(
                    DiagnosticStatus.SUCCESS,
                    EmissionsStatus(
                        milOn = (a and 0x80) != 0,
                        storedDtcCount = a and 0x7F,
                        ignitionType = if (compression) IgnitionType.COMPRESSION else IgnitionType.SPARK,
                        readinessBytes = listOf(b, c, d)
                    )
                )
            }
            return classifyMissing(raw)
        }

        fun parseElmProtocol(raw: String): DiagnosticQuery<Int> {
            val match = Regex("(?:^|[^0-9A-F])A?([0-9])(?:[^0-9A-F]|$)", RegexOption.IGNORE_CASE).find(raw.trim())
            val protocol = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            return if (protocol != null && protocol in 0..9) DiagnosticQuery(DiagnosticStatus.SUCCESS, protocol)
            else classifyMissing(raw)
        }

        fun parseTroubleCodes(
            raw: String,
            positiveService: Int,
            canProtocol: Boolean? = null,
            canHeaders: Boolean = false
        ): DiagnosticQuery<List<DiagnosticTroubleCode>> {
            if (canProtocol == true && canHeaders) {
                val byEcu = EcuIdentityScanner.parseCanServicePayloads(raw, positiveService)
                if (byEcu.isNotEmpty()) {
                    val decoded = mutableListOf<DiagnosticTroubleCode>()
                    for ((ecu, body) in byEcu) {
                        if (body.isEmpty()) return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "Conteggio DTC CAN mancante [$ecu]")
                        val expected = body[0] * 2
                        val records = body.drop(1)
                        if (records.size < expected || records.drop(expected).any { it != 0 })
                            return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "Frame DTC CAN incoerente [$ecu]")
                        decoded += records.take(expected).chunked(2).mapNotNull { pair -> decodeDtc(pair, ecu) }
                    }
                    return DiagnosticQuery(DiagnosticStatus.SUCCESS, decoded.distinct())
                }
                if (raw.contains("NO DATA", true) || raw.contains("?", true)) return classifyMissing(raw)
                return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "Risposta CAN incompleta o senza frame DTC decodificabile")
            }
            // ELM may return multiple ECU responses. Without CAN headers, associating flattened
            // data with a response is unsafe, so report ambiguity instead of inventing codes.
            val responseLines = raw.replace(">", "").split(Regex("[\\r\\n]+")).filter { it.isNotBlank() }
            val positiveLines = responseLines.filter { line -> Elm327Parser.responseBytes(line).contains(positiveService) }
            if (positiveLines.size > 1) return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "Più ECU hanno risposto; DTC non attribuibili")
            val bytes = Elm327Parser.responseBytes(positiveLines.singleOrNull() ?: raw)
            val serviceIndex = bytes.indexOf(positiveService)
            if (serviceIndex < 0) return classifyMissing(raw)

            var payload = bytes.drop(serviceIndex + 1)
            // Never merge a later positive response from another ECU into this one.
            val nextService = payload.indexOf(positiveService)
            if (nextService >= 0) return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "Più risposte ECU concatenate")
            if (canProtocol == true) {
                if (payload.isEmpty()) return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "Conteggio DTC CAN mancante")
                val count = payload[0]
                val expected = count * 2
                val records = payload.drop(1)
                if (records.size < expected || records.drop(expected).any { it != 0 }) {
                    return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "Frame DTC CAN incoerente con il conteggio")
                }
                payload = records.take(expected)
            } else if (canProtocol == null && isPlausibleCanCount(payload)) {
                return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "Formato DTC ambiguo: protocollo ELM non rilevato")
            }
            if (payload.size % 2 != 0) return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "DTC response has an incomplete code")

            val codes = payload.chunked(2).mapNotNull { pair -> decodeDtc(pair) }
            return DiagnosticQuery(DiagnosticStatus.SUCCESS, codes)
        }

        private fun decodeDtc(pair: List<Int>, ecu: String? = null): DiagnosticTroubleCode? {
            val first = pair[0]
            val second = pair[1]
            if (first == 0 && second == 0) return null
            val family = when ((first ushr 6) and 0x03) { 0 -> 'P'; 1 -> 'C'; 2 -> 'B'; else -> 'U' }
            return DiagnosticTroubleCode("$family${(first ushr 4) and 0x03}${first and 0x0F}${second.toString(16).uppercase().padStart(2, '0')}", ecu)
        }

        private fun isPlausibleCanCount(payload: List<Int>): Boolean {
            if (payload.isEmpty() || payload[0] > 0x7F) return false
            val expected = payload[0] * 2
            val remaining = payload.drop(1)
            return remaining.size >= expected && remaining.drop(expected).all { it == 0 }
        }

        private suspend fun readSnapshot(transport: ObdTransport): Map<String, DiagnosticQuery<Double>> {
            val specs = listOf(
                SnapshotSpec("coolant_c", 0x05, 1) { a -> (a[0] - 40).toDouble() },
                SnapshotSpec("module_voltage_v", 0x42, 2) { a -> (a[0] * 256 + a[1]) / 1000.0 },
                SnapshotSpec("rpm", 0x0C, 2) { a -> (a[0] * 256 + a[1]) / 4.0 },
                SnapshotSpec("map_kpa", 0x0B, 1) { a -> a[0].toDouble() },
                SnapshotSpec("barometric_kpa", 0x33, 1) { a -> a[0].toDouble() }
            )
            val values = linkedMapOf<String, DiagnosticQuery<Double>>()
            specs.forEach { spec ->
                values[spec.name] = try {
                    val raw = withTimeout(4_000) { transport.exchange("01%02X".format(spec.pid), 4_000) }
                    val bytes = Elm327Parser.responseBytes(raw)
                    val index = bytes.windowed(2).indexOfFirst { it[0] == 0x41 && it[1] == spec.pid }
                    if (index < 0) classifyMissing(raw)
                    else if (bytes.size < index + 2 + spec.length) DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "PID %02X truncated".format(spec.pid))
                    else DiagnosticQuery(DiagnosticStatus.SUCCESS, spec.decode(bytes.subList(index + 2, index + 2 + spec.length)))
                } catch (e: TimeoutCancellationException) { DiagnosticQuery(DiagnosticStatus.ERROR, detail = "OBD request timed out") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { DiagnosticQuery(DiagnosticStatus.ERROR, detail = e.message ?: "Adapter request failed") }
            }
            return values
        }

        private data class SnapshotSpec(val name: String, val pid: Int, val length: Int, val decode: (List<Int>) -> Double)

        private fun <T> classifyMissing(raw: String): DiagnosticQuery<T> {
            val upper = raw.uppercase()
            adapterErrorMarkers.firstOrNull { upper.contains(it) }?.let { return DiagnosticQuery(DiagnosticStatus.ERROR, detail = it) }
            if (upper.contains("NO DATA") || upper.contains("?"))
                return DiagnosticQuery(DiagnosticStatus.UNSUPPORTED, detail = if (upper.contains("NO DATA")) "NO DATA" else "comando rifiutato")
            return DiagnosticQuery(DiagnosticStatus.MALFORMED, detail = "No matching OBD response in adapter output")
        }

    }
}
