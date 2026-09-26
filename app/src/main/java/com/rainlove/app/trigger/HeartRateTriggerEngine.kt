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

    /** Returns the remaining delay before the current state can advance without a new BLE sample. */
    fun nextTransitionDelayMs(nowMs: Long): Long? = when (state) {
        State.HIGH_PENDING -> (config.triggerDurationMs - (nowMs - stateSinceMs)).coerceAtLeast(0)
        State.RECOVERY_PENDING -> (config.recoveryDurationMs - (nowMs - stateSinceMs)).coerceAtLeast(0)
        State.COOLDOWN -> (config.cooldownMs - (nowMs - stateSinceMs)).coerceAtLeast(0)
        State.ARMED, State.PLAYING -> null
    }

    fun progress(nowMs: Long): TriggerProgress = TriggerProgress(
        state = state,
        deadlineMs = nextTransitionDelayMs(nowMs)?.let { nowMs + it },
    )

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
                if (nowMs - stateSinceMs < config.cooldownMs) return null
                transition(State.ARMED, nowMs)
                onHeartRate(bpm, nowMs)
            }
        }
    }

    private fun transition(next: State, nowMs: Long) {
        state = next
        stateSinceMs = nowMs
    }
}
