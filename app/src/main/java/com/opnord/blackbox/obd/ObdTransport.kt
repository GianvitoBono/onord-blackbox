package com.opnord.blackbox.obd

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
    var linkMode: String = "non connesso"
        private set
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        val beforeBondState = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
        if (beforeBondState != BluetoothDevice.BOND_BONDED) {
            throw IOException("associazione Bluetooth assente (stato=$beforeBondState); associa adattatore nelle impostazioni Android")
        }
        try {
            openAndInitialize(secure = true)
            return@withContext
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (secureError: Exception) {
            // Some ELM clones expose serial SPP but cannot complete authenticated RFCOMM or ELM setup.
            disconnect()
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                throw IOException("SPP secure failed; Bluetooth bond lost: ${secureError.message}", secureError)
            }
            try {
                openAndInitialize(secure = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                throw e
            } catch (insecureError: Exception) {
                throw IOException("SPP secure failed: ${secureError.message}; compatible SPP failed: ${insecureError.message}", insecureError)
            }
        }
    }

    private suspend fun openAndInitialize(secure: Boolean) {
        socket = openSocket(secure)
        linkMode = if (secure) "SPP sicuro" else "SPP compatibile"
        try {
            exchange("ATZ", 5_000)
            for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
                val reply = exchange(cmd, 5_000)
                if (!reply.contains("OK", ignoreCase = true)) throw IOException("ELM setup $cmd: ${reply.trim().take(80)}")
            }
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    private suspend fun openSocket(secure: Boolean): BluetoothSocket {
        val candidate = if (secure) device.createRfcommSocketToServiceRecord(SPP_UUID)
            else device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        val watchdog = watchdogScope.launch { delay(10_000); runCatching { candidate.close() } }
        try {
            candidate.connect()
        } catch (e: CancellationException) {
            runCatching { candidate.close() }
            throw e
        } catch (e: Exception) {
            runCatching { candidate.close() }
            throw IOException("${if (secure) "secure" else "compatible"} SPP connect failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            watchdog.cancel()
        }
        return candidate
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
        runCatching { socket?.close() }; socket = null; linkMode = "non connesso"
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
