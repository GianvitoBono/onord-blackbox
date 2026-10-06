package com.opnord.blackbox.obd

/** Mode 01 PIDs with fixed, known SAE J1979 response lengths and formulas. */
enum class ObdPid(val code: Int, val label: String, val unit: String, val bytes: Int, val decode: (Int, Int) -> Double) {
    FUEL_SYSTEM_STATUS(0x03,"fuel_system_status","bitfield",2,{a,b->a * 256.0 + b}),
    ENGINE_LOAD(0x04,"engine_load","%",1,{a,_->a*100.0/255}),
    COOLANT(0x05,"coolant","C",1,{a,_->a-40.0}),
    SHORT_FUEL_TRIM_1(0x06,"short_fuel_trim_1","%",1,{a,_->(a-128)*100.0/128}),
    LONG_FUEL_TRIM_1(0x07,"long_fuel_trim_1","%",1,{a,_->(a-128)*100.0/128}),
    SHORT_FUEL_TRIM_2(0x08,"short_fuel_trim_2","%",1,{a,_->(a-128)*100.0/128}),
    LONG_FUEL_TRIM_2(0x09,"long_fuel_trim_2","%",1,{a,_->(a-128)*100.0/128}),
    FUEL_PRESSURE(0x0A,"fuel_pressure","kPa",1,{a,_->a*3.0}),
    MAP(0x0B,"map","kPa_abs",1,{a,_->a.toDouble()}),
    RPM(0x0C,"rpm","rpm",2,{a,b->(a*256+b)/4.0}),
    SPEED(0x0D,"speed","km/h",1,{a,_->a.toDouble()}),
    TIMING_ADVANCE(0x0E,"timing_advance","deg_before_tdc",1,{a,_->a/2.0-64}),
    INTAKE_TEMP(0x0F,"intake_temp","C",1,{a,_->a-40.0}),
    MAF(0x10,"maf","g/s",2,{a,b->(a*256+b)/100.0}),
    THROTTLE(0x11,"throttle","%",1,{a,_->a*100.0/255}),
    SECONDARY_AIR_STATUS(0x12,"secondary_air_status","bitfield",1,{a,_->a.toDouble()}),
    OXYGEN_SENSORS_PRESENT(0x13,"oxygen_sensors_present","bitfield",1,{a,_->a.toDouble()}),
    OBD_STANDARDS(0x1C,"obd_standards","code",1,{a,_->a.toDouble()}),
    OXYGEN_SENSORS_PRESENT_4B(0x1D,"oxygen_sensors_present_4b","bitfield",1,{a,_->a.toDouble()}),
    AUX_INPUT(0x1E,"aux_input","bitfield",1,{a,_->a.toDouble()}),
    RUN_TIME(0x1F,"run_time","s",2,{a,b->(a*256+b).toDouble()}),
    DISTANCE_MIL(0x21,"distance_mil","km",2,{a,b->(a*256+b).toDouble()}),
    RAIL_PRESSURE_VACUUM(0x22,"rail_pressure_vacuum","kPa",2,{a,b->(a*256+b)*0.079}),
    RAIL_PRESSURE(0x23,"rail_pressure","kPa",2,{a,b->(a*256+b)*10.0}),
    OXYGEN_SENSOR_1(0x14,"oxygen_sensor_1_voltage","V",2,{a,_->a/200.0}),
    OXYGEN_SENSOR_2(0x15,"oxygen_sensor_2_voltage","V",2,{a,_->a/200.0}),
    OXYGEN_SENSOR_3(0x16,"oxygen_sensor_3_voltage","V",2,{a,_->a/200.0}),
    OXYGEN_SENSOR_4(0x17,"oxygen_sensor_4_voltage","V",2,{a,_->a/200.0}),
    OXYGEN_SENSOR_5(0x18,"oxygen_sensor_5_voltage","V",2,{a,_->a/200.0}),
    OXYGEN_SENSOR_6(0x19,"oxygen_sensor_6_voltage","V",2,{a,_->a/200.0}),
    OXYGEN_SENSOR_7(0x1A,"oxygen_sensor_7_voltage","V",2,{a,_->a/200.0}),
    OXYGEN_SENSOR_8(0x1B,"oxygen_sensor_8_voltage","V",2,{a,_->a/200.0}),
    OXYGEN_SENSOR_1_WIDEBAND(0x24,"oxygen_sensor_1_equivalence_ratio","ratio",4,{a,b->(a*256+b)/32768.0}),
    OXYGEN_SENSOR_2_WIDEBAND(0x25,"oxygen_sensor_2_equivalence_ratio","ratio",4,{a,b->(a*256+b)/32768.0}),
    OXYGEN_SENSOR_3_WIDEBAND(0x26,"oxygen_sensor_3_equivalence_ratio","ratio",4,{a,b->(a*256+b)/32768.0}),
    OXYGEN_SENSOR_4_WIDEBAND(0x27,"oxygen_sensor_4_equivalence_ratio","ratio",4,{a,b->(a*256+b)/32768.0}),
    OXYGEN_SENSOR_5_WIDEBAND(0x28,"oxygen_sensor_5_equivalence_ratio","ratio",4,{a,b->(a*256+b)/32768.0}),
    OXYGEN_SENSOR_6_WIDEBAND(0x29,"oxygen_sensor_6_equivalence_ratio","ratio",4,{a,b->(a*256+b)/32768.0}),
    OXYGEN_SENSOR_7_WIDEBAND(0x2A,"oxygen_sensor_7_equivalence_ratio","ratio",4,{a,b->(a*256+b)/32768.0}),
    OXYGEN_SENSOR_8_WIDEBAND(0x2B,"oxygen_sensor_8_equivalence_ratio","ratio",4,{a,b->(a*256+b)/32768.0}),
    OXYGEN_SENSOR_1_WIDEBAND_MODE34(0x34,"oxygen_sensor_1_equivalence_ratio","ratio",4,{a,b->(a*256+b)/32768.0}),
    EGR_COMMAND(0x2C,"egr_command","%",1,{a,_->a*100.0/255}),
    EGR_ERROR(0x2D,"egr_error","%",1,{a,_->(a-128)*100.0/128}),
    EVAP_PURGE(0x2E,"evap_purge","%",1,{a,_->a*100.0/255}),
    FUEL_LEVEL(0x2F,"fuel_level","%",1,{a,_->a*100.0/255}),
    WARMUPS_SINCE_CLEAR(0x30,"warmups_since_clear","count",1,{a,_->a.toDouble()}),
    DISTANCE_SINCE_CLEAR(0x31,"distance_since_clear","km",2,{a,b->(a*256+b).toDouble()}),
    BAROMETRIC_PRESSURE(0x33,"barometric_pressure","kPa_abs",1,{a,_->a.toDouble()}),
    CATALYST_TEMP_B1S1(0x3C,"catalyst_temp_b1s1","C",2,{a,b->(a*256+b)/10.0-40}),
    CATALYST_TEMP_B2S1(0x3D,"catalyst_temp_b2s1","C",2,{a,b->(a*256+b)/10.0-40}),
    CATALYST_TEMP_B1S2(0x3E,"catalyst_temp_b1s2","C",2,{a,b->(a*256+b)/10.0-40}),
    CATALYST_TEMP_B2S2(0x3F,"catalyst_temp_b2s2","C",2,{a,b->(a*256+b)/10.0-40}),
    CONTROL_MODULE_VOLTAGE(0x42,"control_module_voltage","V",2,{a,b->(a*256+b)/1000.0}),
    ABSOLUTE_LOAD(0x43,"absolute_load","%",2,{a,b->(a*256+b)*100.0/255.0}),
    EQUIVALENCE_RATIO(0x44,"equivalence_ratio","ratio",2,{a,b->(a*256+b)/32768.0}),
    RELATIVE_THROTTLE(0x45,"relative_throttle","%",1,{a,_->a*100.0/255}),
    AMBIENT_TEMP(0x46,"ambient_temp","C",1,{a,_->a-40.0}),
    ABSOLUTE_THROTTLE_B(0x47,"absolute_throttle_b","%",1,{a,_->a*100.0/255}),
    ABSOLUTE_THROTTLE_C(0x48,"absolute_throttle_c","%",1,{a,_->a*100.0/255}),
    ACCELERATOR_PEDAL_D(0x49,"accelerator_pedal_d","%",1,{a,_->a*100.0/255}),
    ACCELERATOR_PEDAL_E(0x4A,"accelerator_pedal_e","%",1,{a,_->a*100.0/255}),
    ACCELERATOR_PEDAL_F(0x4B,"accelerator_pedal_f","%",1,{a,_->a*100.0/255}),
    THROTTLE_ACTUATOR(0x4C,"throttle_actuator","%",1,{a,_->a*100.0/255}),
    TIME_MIL(0x4D,"time_mil","min",2,{a,b->(a*256+b).toDouble()}),
    TIME_SINCE_CLEAR(0x4E,"time_since_clear","min",2,{a,b->(a*256+b).toDouble()}),
    FUEL_TYPE(0x51,"fuel_type","code",1,{a,_->a.toDouble()}),
    ETHANOL(0x52,"ethanol","%",1,{a,_->a*100.0/255}),
    ABSOLUTE_EVAP_PRESSURE(0x53,"absolute_evap_pressure","kPa",2,{a,b->(a*256+b)/200.0}),
    RAIL_PRESSURE_ABS(0x59,"rail_pressure_abs","kPa",2,{a,b->(a*256+b)*10.0}),
    ACCELERATOR_PEDAL(0x5A,"accelerator_pedal","%",1,{a,_->a*100.0/255}),
    HYBRID_BATTERY(0x5B,"hybrid_battery","%",1,{a,_->a*100.0/255}),
    OIL_TEMP(0x5C,"oil_temp","C",1,{a,_->a-40.0}),
    INJECTION_TIMING(0x5D,"injection_timing","deg",2,{a,b->(a*256+b)/128.0-210}),
    FUEL_RATE(0x5E,"fuel_rate","L/h",2,{a,b->(a*256+b)/20.0}),
    EMISSIONS_REQUIREMENTS(0x5F,"emissions_requirements","bitfield",1,{a,_->a.toDouble()}),
    DEMANDED_TORQUE(0x61,"demanded_torque","%",1,{a,_->a-125.0}),
    ACTUAL_TORQUE(0x62,"actual_torque","%",1,{a,_->a-125.0}),
    REFERENCE_TORQUE(0x63,"reference_torque","Nm",2,{a,b->(a*256+b).toDouble()}),
    ENGINE_FRICTION_TORQUE(0x8E,"engine_friction_torque","%",1,{a,_->a-125.0});

    companion object { fun fromCode(code: Int) = entries.firstOrNull { it.code == code } }
}

sealed interface ObdResult {
    data class Value(val pid: ObdPid, val value: Double, val subvalues: Map<String, Double> = emptyMap()) : ObdResult
    data class Unsupported(val pid: ObdPid) : ObdResult
    data class AdapterError(val message: String) : ObdResult
    data class Malformed(val message: String) : ObdResult
}

object Elm327Parser {
    private val adapterErrors = listOf("UNABLE TO CONNECT", "BUS INIT", "CAN ERROR", "BUS ERROR", "BUFFER FULL", "STOPPED", "DATA ERROR", "?")
    internal fun responseBytes(raw: String): List<Int> = raw.uppercase().split(Regex("[^A-Z0-9]+"))
        .filter { it.length >= 2 && it.length % 2 == 0 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
        .flatMap { token -> token.chunked(2).map { it.toInt(16) } }

    fun parse(raw: String, pid: ObdPid): ObdResult {
        val text = raw.uppercase().replace(">", " ")
        adapterErrors.firstOrNull { text.contains(it) }?.let { return ObdResult.AdapterError(it) }
        if (text.contains("NO DATA")) return ObdResult.Unsupported(pid)
        val bytes = responseBytes(text)
        // Match positive Mode 01 response and require the full PID width; never decode echoed commands.
        val responses = (0 until bytes.size - 1).filter { bytes[it] == 0x41 && bytes[it + 1] == pid.code }
        if (responses.isEmpty()) return ObdResult.Malformed("No 41 ${"%02X".format(pid.code)} data in response")
        // Multiple ECU replies cannot be attributed to a single decoded value safely.
        if (responses.size != 1) return ObdResult.Malformed("Ambiguous multi-ECU response for ${pid.label}")
        val response = responses.single()
        val data = bytes.drop(response + 2)
        if (pid.code == 0x34 && response > 0 && bytes[response - 1] in 1..7 && bytes[response - 1] != 6) {
            return ObdResult.Malformed("Short ISO-TP payload for oxygen sensor 1 wideband PID")
        }
        if (data.size < pid.bytes) return ObdResult.Malformed("Incomplete ${pid.label} response")
        val a = data[0]; val b = data.getOrElse(1) { 0 }
        if (pid.code == 0x34) {
            // SAE PID 0134 has two independent 16-bit fields: lambda*32768 and current*256+32768.
            // Require all four bytes before returning either measurement.
            val lambda = (data[0] * 256 + data[1]) / 32768.0
            val currentMa = (data[2] * 256 + data[3]) / 256.0 - 128.0
            return ObdResult.Value(pid, lambda, mapOf("current_ma" to currentMa))
        }
        return ObdResult.Value(pid, pid.decode(a, b))
    }

    /** Preserve an advertised but unmapped PID only when its single-ECU reply is unambiguous. */
    fun rawUnsignedValue(raw: String, pidCode: Int): Long? {
        val text = raw.uppercase().replace(">", " ")
        if (adapterErrors.any(text::contains) || text.contains("NO DATA")) return null
        val bytes = responseBytes(text)
        val replies = (0 until bytes.size - 1).filter { bytes[it] == 0x41 && bytes[it + 1] == pidCode }
        if (replies.size != 1) return null
        val offset = replies.single()
        val data = bytes.drop(offset + 2)
        // Unknown PID widths vary. Reject empty, oversized, multi-response, and multi-ECU replies.
        if (data.isEmpty() || data.size > 4) return null
        return data.fold(0L) { value, byte -> (value shl 8) or byte.toLong() }
    }

    /** Decode the four-byte support bitmap returned by 0100, 0120, 0140, 0160, or 0180. */
    fun supportedPids(raw: String, rangeStart: Int): Set<ObdPid> {
        return supportedPidCodes(raw, rangeStart).mapNotNullTo(mutableSetOf(), ObdPid::fromCode)
    }

    fun supportedPidCodes(raw: String, rangeStart: Int): Set<Int> {
        val bytes = responseBytes(raw)
        val offset = (0 until bytes.size - 1).firstOrNull { bytes[it] == 0x41 && bytes[it + 1] == rangeStart } ?: return emptySet()
        if (bytes.size < offset + 6) return emptySet()
        val bitmap = bytes.subList(offset + 2, offset + 6)
        return (0..31).mapNotNull { bit ->
            val code = rangeStart + bit + 1
            // The final bitmap bit advertises the next bitmap range; it is not a sensor PID.
            code.takeIf { bit < 31 && (bitmap[bit / 8] and (1 shl (7 - bit % 8))) != 0 }
        }.toSet()
    }
}
