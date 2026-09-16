package com.rainlove.app.trigger

class HeartRateTriggerEngine(private var config: TriggerConfig = TriggerConfig()) {
    enum class State { ARMED, HIGH_PENDING, PLAYING, RECOVERY_PENDING, COOLDOWN }
    sealed interface Event {
        data object StartPlayback : Event
        data object StopPlayback : Event
    }

    var state: State = State.ARMED
        private set
    private var stateSinceMs: Long = 0

    fun updateConfig(value: TriggerConfig, nowMs: Long) {
        config = value
        reset(nowMs)
    }

    fun reset(nowMs: Long = 0) {
        state = State.ARMED
        stateSinceMs = nowMs
    }

    fun onHeartRate(bpm: Int, nowMs: Long): Event? {
        return when (state) {
            State.ARMED -> {
                if (bpm >= config.triggerBpm) transition(State.HIGH_PENDING, nowMs)
                null
            }
            State.HIGH_PENDING -> when {
                bpm < config.triggerBpm -> {
                    transition(State.ARMED, nowMs)
                    null
                }
                nowMs - stateSinceMs >= config.triggerDurationMs -> {
                    transition(State.PLAYING, nowMs)
                    Event.StartPlayback
                }
                else -> null
            }
            State.PLAYING -> {
                if (bpm <= config.recoveryBpm) transition(State.RECOVERY_PENDING, nowMs)
                null
            }
            State.RECOVERY_PENDING -> when {
                bpm > config.recoveryBpm -> {
                    transition(State.PLAYING, nowMs)
                    null
                }
                nowMs - stateSinceMs >= config.recoveryDurationMs -> {
                    transition(State.COOLDOWN, nowMs)
                    Event.StopPlayback
                }
                else -> null
            }
            State.COOLDOWN -> {
                if (nowMs - stateSinceMs >= config.cooldownMs) transition(State.ARMED, nowMs)
                null
            }
        }
    }

    private fun transition(next: State, nowMs: Long) {
        state = next
        stateSinceMs = nowMs
    }
}
