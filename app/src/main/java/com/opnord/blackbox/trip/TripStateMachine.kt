package com.opnord.blackbox.trip

enum class TripState { IDLE, STARTING, ACTIVE, STOPPING }
sealed interface TripEvent {
    data object PowerOn : TripEvent
    data object DataSourceReady : TripEvent
    data object PowerOff : TripEvent
    data object GraceExpired : TripEvent
}

data class TripTransition(val state: TripState, val action: String? = null)

class TripStateMachine(initial: TripState = TripState.IDLE) {
    var state: TripState = initial; private set
    fun on(event: TripEvent): TripTransition {
        val action = when (state to event) {
            TripState.IDLE to TripEvent.PowerOn -> { state = TripState.STARTING; "start_trip" }
            TripState.STARTING to TripEvent.DataSourceReady -> { state = TripState.ACTIVE; null }
            TripState.STARTING to TripEvent.PowerOff -> { state = TripState.STOPPING; "start_grace" }
            TripState.ACTIVE to TripEvent.PowerOff -> { state = TripState.STOPPING; "start_grace" }
            TripState.STOPPING to TripEvent.PowerOn -> { state = TripState.ACTIVE; "cancel_grace" }
            TripState.STOPPING to TripEvent.GraceExpired -> { state = TripState.IDLE; "close_trip" }
            else -> null
        }
        return TripTransition(state, action)
    }
}
