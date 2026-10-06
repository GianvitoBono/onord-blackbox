package com.opnord.blackbox.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Bounded, read-only capture of actual Mode 01 replies, including ECU headers and ISO-TP frames. */
class Mode01Capture {
    private val pids = listOf(
        0x00, 0x01, 0x20, 0x34, 0x40, 0x4F, 0x60, 0x68, 0x69, 0x6D, 0x71,
        0x78, 0x7A, 0x80, 0x83, 0x85, 0x88, 0x8B, 0x8E, 0x8F, 0x92, 0x9D, 0xA0, 0xA1
    )

    suspend fun scan(transport: ObdTransport, onProgress: (Int, Int) -> Unit = { _, _ -> }): JSONObject {
        val report = JSONObject().put("schema_version", 1)
            .put("captured_at", Instant.now().toString())
            .put("purpose", "read_only_mode_01_capture")
        val setup = JSONObject()
        val reads = JSONArray()
        report.put("setup", setup).put("reads", reads)
        try {
            withTimeout(10_000) { transport.connect() }
            // Standard ELM formatting: retain source CAN IDs and request automatic flow control.
            for (command in listOf("ATI", "ATDPN", "ATDP", "ATS1", "ATH1", "ATCAF1", "ATCFC1")) {
                setup.put(command, exchange(transport, command))
            }
            pids.forEachIndexed { index, code ->
                onProgress(index + 1, pids.size)
                val command = "01%02X".format(code)
                val observedAt = Instant.now().toString()
                val raw = try {
                    exchange(transport, command)
                } catch (error: TimeoutCancellationException) {
                    "ERROR: timeout su $command"
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    "ERROR: ${error.message ?: error.javaClass.simpleName}"
                }
                val decoded = EcuIdentityScanner.parseCanPayloads(raw, command)
                val perEcu = JSONObject()
                decoded.forEach { (ecu, bytes) ->
                    perEcu.put(ecu, JSONObject()
                        .put("data_hex", bytes.joinToString("") { "%02X".format(it) })
                        .put("data_bytes", bytes.size))
                }
                reads.put(JSONObject().put("command", command).put("observed_at", observedAt)
                    .put("raw", raw).put("complete_by_ecu", perEcu)
                    .put("status", when {
                        decoded.isNotEmpty() -> "complete"
                        raw.contains("NO DATA", ignoreCase = true) -> "no_data"
                        raw.startsWith("ERROR:") -> "error"
                        else -> "unverified_or_incomplete"
                    }))
            }
            return report
        } finally {
            withContext(NonCancellable) { runCatching { transport.disconnect() } }
        }
    }

    private suspend fun exchange(transport: ObdTransport, command: String): String =
        withTimeout(5_000) { transport.exchange(command, 4_000) }.replace(">", "").trim()
}
