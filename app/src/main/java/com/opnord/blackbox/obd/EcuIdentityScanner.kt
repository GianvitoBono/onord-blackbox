package com.opnord.blackbox.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Read-only ECU identification. Raw replies are retained when an adapter uses unfamiliar framing. */
data class EcuIdentityRead(
    val command: String,
    val status: DiagnosticStatus,
    val raw: String,
    val valuesByEcu: Map<String, String> = emptyMap(),
    val detail: String? = null
)

data class EcuIdentityReport(
    val adapter: String?,
    val protocolNumber: String?,
    val protocolName: String?,
    val reads: List<EcuIdentityRead>,
    val error: String? = null
) {
    fun summary(): String = buildList {
        error?.let { add("Errore: $it") }
        protocolName?.let { add("Protocollo: $it") }
        adapter?.let { add("Adattatore: $it") }
        val ecuIds = reads.flatMap { it.valuesByEcu.keys }.distinct()
        if (ecuIds.isNotEmpty()) add("ECU rispondenti: ${ecuIds.joinToString()}")
        reads.forEach { read ->
            val label = when (read.command) {
                "0904" -> "ID calibrazione"
                "0906" -> "CVN"
                "090A" -> "Nome ECU"
                "0900" -> "PID Mode 09 supportati"
                else -> read.command
            }
            if (read.valuesByEcu.isEmpty()) add("$label: ${read.detail ?: read.status.name.lowercase()}")
            else read.valuesByEcu.forEach { (ecu, value) -> add("$label [$ecu]: $value") }
        }
    }.joinToString("\n")

    fun toJson(): String = JSONObject().apply {
        put("summary", summary())
        put("adapter", adapter ?: JSONObject.NULL)
        put("protocol_number", protocolNumber ?: JSONObject.NULL)
        put("protocol_name", protocolName ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("reads", JSONArray().apply { reads.forEach { read ->
            put(JSONObject().apply {
                put("command", read.command)
                put("status", read.status.name.lowercase())
                put("raw", read.raw)
                put("detail", read.detail ?: JSONObject.NULL)
                put("values_by_ecu", JSONObject(read.valuesByEcu))
            })
        } })
    }.toString()
}

class EcuIdentityScanner(private val timeoutMs: Long = 6_000) {
    suspend fun scan(transport: ObdTransport): EcuIdentityReport {
        try {
            withTimeout(10_000) { transport.connect() }
            // ATSP0 chooses the concrete protocol only after the first vehicle request.
            command(transport, "0100")
            val adapter = command(transport, "ATI").trimReply()
            val protocolNumber = command(transport, "ATDPN").trimReply()
            val protocolName = command(transport, "ATDP").trimReply()
            // Keep spaces and CAN headers so simultaneous ECU replies can be separated safely.
            require(command(transport, "ATS1").contains("OK", ignoreCase = true)) { "Adattatore non accetta ATS1" }
            require(command(transport, "ATH1").contains("OK", ignoreCase = true)) { "Adattatore non accetta ATH1" }
            val reads = listOf("0900", "0904", "0906", "090A").map { request ->
                try {
                    val raw = command(transport, request).trimReply()
                    val payloads = parseCanPayloads(raw, request)
                    val decoded = payloads.mapValues { (_, bytes) -> decode(request, bytes) }
                        .filterValues { it.isNotBlank() }
                    val unsupported = raw.contains("NO DATA", true) || raw.contains("?", true)
                    val status = when {
                        decoded.isNotEmpty() -> DiagnosticStatus.SUCCESS
                        unsupported -> DiagnosticStatus.UNSUPPORTED
                        else -> DiagnosticStatus.MALFORMED
                    }
                    EcuIdentityRead(request, status, raw, decoded,
                        if (decoded.isEmpty()) if (unsupported) "nessuna risposta" else "risposta grezza disponibile" else null)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { EcuIdentityRead(request, DiagnosticStatus.ERROR, "", detail = e.message ?: "lettura fallita") }
            }
            return EcuIdentityReport(adapter, protocolNumber, protocolName, reads)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return EcuIdentityReport(null, null, null, emptyList(), e.message ?: "connessione fallita") }
        finally { withContext(NonCancellable) { runCatching { transport.disconnect() } } }
    }

    private suspend fun command(transport: ObdTransport, value: String): String = try {
        withTimeout(timeoutMs) { transport.exchange(value, timeoutMs) }
    } catch (e: TimeoutCancellationException) { throw IllegalStateException("Timeout su $value") }

    private fun String.trimReply(): String = replace(">", "").trim()

    companion object {
        private val header = Regex("^(?:[0-9A-F]{3}|[0-9A-F]{8})$", RegexOption.IGNORE_CASE)
        private val hexBytes = Regex("^[0-9A-F]{2,}$", RegexOption.IGNORE_CASE)

        /** ELM CAN formatting with ATH1/ATS1: one ISO-TP stream per response CAN ID. */
        internal fun parseCanPayloads(raw: String, request: String): Map<String, List<Int>> {
            val frames = linkedMapOf<String, MutableList<List<Int>>>()
            raw.lines().forEach { line ->
                val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.size < 2 || !header.matches(parts[0])) return@forEach
                val bytes = parts.drop(1).flatMap { part ->
                    if (!hexBytes.matches(part) || part.length % 2 != 0) emptyList()
                    else part.chunked(2).map { it.toInt(16) }
                }
                if (bytes.isNotEmpty()) frames.getOrPut(parts[0].uppercase()) { mutableListOf() }.add(bytes)
            }
            val mode = request.substring(0, 2).toInt(16) + 0x40
            val pid = request.substring(2, 4).toInt(16)
            return frames.mapNotNull { (ecu, chunks) ->
                val payload = reassemble(chunks) ?: return@mapNotNull null
                if (payload.size < 3 || payload[0] != mode || payload[1] != pid) return@mapNotNull null
                ecu to payload.drop(2)
            }.toMap()
        }

        private fun reassemble(frames: List<List<Int>>): List<Int>? {
            val first = frames.firstOrNull() ?: return null
            val pci = first.firstOrNull() ?: return null
            if (pci ushr 4 == 0) {
                val length = pci and 0x0F
                return first.drop(1).takeIf { it.size >= length }?.take(length)
            }
            if (pci ushr 4 != 1 || first.size < 3) return null
            val length = ((pci and 0x0F) shl 8) or first[1]
            if (length !in 1..512) return null
            val result = first.drop(2).toMutableList()
            var nextSequence = 1
            for (frame in frames.drop(1)) {
                if (frame.isEmpty() || frame[0] ushr 4 != 2 || (frame[0] and 0x0F) != nextSequence % 16) return null
                result.addAll(frame.drop(1))
                nextSequence++
                if (result.size >= length) break
            }
            return result.takeIf { it.size >= length }?.take(length)
        }

        private fun decode(request: String, payload: List<Int>): String {
            if (request == "0900") return payload.takeIf { it.size == 4 }?.joinToString("") { "%02X".format(it) }.orEmpty()
            if (payload.isEmpty() || payload[0] == 0) return ""
            val data = payload.drop(1)
            if (request == "0906") return data.takeIf { it.isNotEmpty() && it.size % 4 == 0 }
                ?.chunked(4)?.joinToString(", ") { chunk -> chunk.joinToString("") { "%02X".format(it) } }.orEmpty()
            if (request == "0904" || request == "090A") {
                if (data.isEmpty() || data.any { it != 0 && it !in 0x20..0x7E }) return ""
                return data.map { it.toChar() }.joinToString("").trim('\u0000', ' ')
            }
            return ""
        }
    }
}
