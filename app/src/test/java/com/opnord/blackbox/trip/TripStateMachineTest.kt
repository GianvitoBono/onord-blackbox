package com.opnord.blackbox.trip

import org.junit.Assert.*
import org.junit.Test

class TripStateMachineTest {
    @Test fun powerCycleWithinGraceKeepsTripActive() {
        val machine = TripStateMachine()
        assertEquals("start_trip", machine.on(TripEvent.PowerOn).action)
        machine.on(TripEvent.DataSourceReady)
        assertEquals("start_grace", machine.on(TripEvent.PowerOff).action)
        assertEquals("cancel_grace", machine.on(TripEvent.PowerOn).action)
        assertEquals(TripState.ACTIVE, machine.state)
    }
    @Test fun graceExpiryClosesTrip() {
        val machine = TripStateMachine()
        machine.on(TripEvent.PowerOn); machine.on(TripEvent.DataSourceReady); machine.on(TripEvent.PowerOff)
        assertEquals("close_trip", machine.on(TripEvent.GraceExpired).action)
        assertEquals(TripState.IDLE, machine.state)
    }
}
