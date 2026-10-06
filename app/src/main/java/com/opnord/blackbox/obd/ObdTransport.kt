package com.opnord.blackbox.obd

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

interface ObdTransport {
    suspend fun connect()
    /** Sends one ELM command (without CR) and returns the response through the prompt. */
    suspend fun exchange(command: String, timeoutMs: Long = 2_500): String
    suspend fun disconnect()
}

class BluetoothSppObdTransport(private val device: BluetoothDevice) : ObdTransport {
    private var socket: BluetoothSocket? = null
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        val candidate = device.createRfcommSocketToServiceRecord(SPP_UUID)
        val watchdog = watchdogScope.launch { delay(10_000); runCatching { candidate.close() } }
        try { candidate.connect() } finally { watchdog.cancel() }
        socket = candidate
        // Basic ELM setup; errors are passed through and will be handled by the client.
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) exchange(cmd, 5_000)
    }

    override suspend fun exchange(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val active = socket ?: throw IOException("OBD adapter is disconnected")
        active.outputStream.write((command.trim() + "\r").toByteArray(Charsets.US_ASCII))
        active.outputStream.flush()
        val out = StringBuilder()
        val buffer = ByteArray(128)
        val watchdog = watchdogScope.launch { delay(timeoutMs); runCatching { active.close() } }
        try {
            while (out.length < 2048) {
                val count = active.inputStream.read(buffer)
                if (count < 0) break
                val chunk = String(buffer, 0, count, Charsets.US_ASCII)
                out.append(chunk)
                if (chunk.contains('>')) break
            }
        } finally { watchdog.cancel() }
        if (out.isEmpty()) throw IOException("Empty OBD response")
        out.toString()
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }; socket = null
    }

    companion object { val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") }
}

/** Deterministic transport for parser/scheduler tests and development without an adapter. */
class FakeObdTransport(private val responses: Map<String, String> = defaultResponses) : ObdTransport {
    var connected = false
        private set
    val commands = mutableListOf<String>()
    override suspend fun connect() { connected = true }
    override suspend fun exchange(command: String, timeoutMs: Long): String {
        check(connected) { "fake adapter disconnected" }
        commands += command.uppercase()
        return responses[command.uppercase()] ?: "NO DATA>"
    }
    override suspend fun disconnect() { connected = false }

    companion object {
        val defaultResponses = mapOf(
            "0100" to "41 00 10 18 00 01>", // supports 0104, 010C, 010D and continues
            "0120" to "41 20 00 02 00 01>", // supports 012F and continues
            "0140" to "41 40 40 00 00 00>", // supports 0142
            "010C" to "41 0C 1A F8>", "010D" to "41 0D 28>", "010F" to "41 0F 82>"
        )
    }
}
