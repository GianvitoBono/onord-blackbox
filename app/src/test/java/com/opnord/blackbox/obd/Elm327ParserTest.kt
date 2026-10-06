package com.opnord.blackbox.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327ParserTest {
    @Test fun decodesStandardPidValuesAndElmHeaders() {
        assertEquals(1726.0, (Elm327Parser.parse("010C\r7E8 04 41 0C 1A F8>", ObdPid.RPM) as ObdResult.Value).value, 0.0)
        assertEquals(40.0, (Elm327Parser.parse("41 0D 28>", ObdPid.SPEED) as ObdResult.Value).value, 0.0)
        assertEquals(90.0, (Elm327Parser.parse("41 05 82>", ObdPid.COOLANT) as ObdResult.Value).value, 0.0)
        assertEquals(12.0, (Elm327Parser.parse("41 42 2E E0>", ObdPid.CONTROL_MODULE_VOLTAGE) as ObdResult.Value).value, 0.0)
    }

    @Test fun classifiesAdapterErrorsAndMissingResponses() {
        assertTrue(Elm327Parser.parse("CAN ERROR>", ObdPid.SPEED) is ObdResult.AdapterError)
        assertTrue(Elm327Parser.parse("NO DATA>", ObdPid.SPEED) is ObdResult.Unsupported)
        assertTrue(Elm327Parser.parse("41 0C 12>", ObdPid.RPM) is ObdResult.Malformed)
    }

    @Test fun discoversRequestedPidsFromStandardSupportBitmap() {
        val found = Elm327Parser.supportedPids("41 00 10 18 00 00>", 0)
        assertEquals(setOf(ObdPid.ENGINE_LOAD, ObdPid.RPM, ObdPid.SPEED), found)
    }
}
