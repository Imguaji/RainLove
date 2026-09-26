package com.rainlove.app.trigger

/** Applies a freshness limit to real sensor readings, using elapsed (not wall-clock) time. */
class HeartRateMonitor(
    config: TriggerConfig = TriggerConfig(),
    private val staleAfterMs: Long = STALE_AFTER_MS,
) {
    enum class Signal { WAITING, LIVE, STALE }
    enum class LossReason { TIMEOUT, DISCONNECTED }
    sealed interface Event {
        data class SignalLost(val reason: LossReason) : Event
        data class SignalAvailable(val restored: Boolean) : Event
        data class StateChanged(
            val before: HeartRateTriggerEngine.State,
            val after: HeartRateTriggerEngine.State,
        ) : Event
        data class Playback(val event: HeartRateTriggerEngine.Event) : Event
    }

    private val engine = HeartRateTriggerEngine(config)
    private var lastSampleMs: Long? = null
    var bpm: Int? = null
        private set
    var signal: Signal = Signal.WAITING
        private set

    init { require(staleAfterMs > 0) }

    fun progress(nowMs: Long): TriggerProgress = engine.progress(nowMs)

    fun onSample(value: Int, nowMs: Long): List<Event> {
        if (value !in 1..255) return onTimer(nowMs)
        val events = mutableListOf<Event>()
        // A delayed Handler callback must not let a new sample extend an expired hold.
        expire(nowMs, events)
        if (signal != Signal.LIVE) events += Event.SignalAvailable(signal == Signal.STALE)
        signal = Signal.LIVE
        bpm = value
        lastSampleMs = nowMs
        advance(nowMs, events)
        return events
    }

    fun onTimer(nowMs: Long): List<Event> {
        val events = mutableListOf<Event>()
        // Expiry wins when the trigger and expiry deadlines coincide.
        expire(nowMs, events)
        advance(nowMs, events)
        return events
    }

    fun disconnect(nowMs: Long): List<Event> {
        val hadSignal = signal != Signal.WAITING
        reset(nowMs)
        return if (hadSignal) listOf(Event.SignalLost(LossReason.DISCONNECTED)) else emptyList()
    }

    fun reset(nowMs: Long) {
        bpm = null
        lastSampleMs = null
        signal = Signal.WAITING
        engine.reset(nowMs)
    }

    fun nextCheckDelayMs(nowMs: Long): Long? {
        val receivedAt = lastSampleMs ?: return null
        val expiryDelay = (staleAfterMs - (nowMs - receivedAt)).coerceAtLeast(0)
        return engine.nextTransitionDelayMs(nowMs)?.coerceAtMost(expiryDelay) ?: expiryDelay
    }

    private fun expire(nowMs: Long, events: MutableList<Event>) {
        val receivedAt = lastSampleMs ?: return
        if (nowMs - receivedAt < staleAfterMs) return
        reset(nowMs)
        signal = Signal.STALE
        events += Event.SignalLost(LossReason.TIMEOUT)
    }

    private fun advance(nowMs: Long, events: MutableList<Event>) {
        val value = bpm ?: return
        val before = engine.state
        val playback = engine.onHeartRate(value, nowMs)
        if (engine.state != before) events += Event.StateChanged(before, engine.state)
        playback?.let { events += Event.Playback(it) }
    }

    companion object {
        const val STALE_AFTER_MS = 15_000L
    }
}
