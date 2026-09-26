package com.rainlove.app.trigger

import org.junit.Assert.*
import org.junit.Test

class HeartRateMonitorTest {
    private fun monitor(holdMs: Long = 5_000) = HeartRateMonitor(TriggerConfig(
        triggerBpm = 120, triggerDurationMs = holdMs,
        recoveryBpm = 110, recoveryDurationMs = 10_000, cooldownMs = 60_000,
    ))

    private fun List<HeartRateMonitor.Event>.startsPlayback() = any {
        it == HeartRateMonitor.Event.Playback(HeartRateTriggerEngine.Event.StartPlayback)
    }

    @Test fun `sparse but fresh heart rate still triggers on schedule`() {
        val monitor = monitor()
        monitor.onSample(130, 0)
        assertEquals(5_000L, monitor.nextCheckDelayMs(0))
        assertFalse(monitor.onTimer(4_999).startsPlayback())
        assertTrue(monitor.onTimer(5_000).startsPlayback())
        assertEquals(10_000L, monitor.nextCheckDelayMs(5_000))
        assertEquals(HeartRateMonitor.Signal.LIVE, monitor.signal)
    }

    @Test fun `stale reading cannot finish a long hold`() {
        val monitor = monitor(30_000)
        monitor.onSample(130, 0)
        assertEquals(15_000L, monitor.nextCheckDelayMs(0))
        val expired = monitor.onTimer(15_000)
        assertEquals(listOf(HeartRateMonitor.Event.SignalLost(HeartRateMonitor.LossReason.TIMEOUT)), expired)
        assertFalse(monitor.onTimer(30_000).startsPlayback())
        assertNull(monitor.bpm)
        assertNull(monitor.nextCheckDelayMs(30_000))
        assertNull(monitor.progress(30_000).deadlineMs)
        assertEquals(HeartRateMonitor.Signal.STALE, monitor.signal)
    }

    @Test fun `expiry wins when both deadlines are equal`() {
        val monitor = monitor(15_000)
        monitor.onSample(130, 0)
        assertFalse(monitor.onTimer(15_000).startsPlayback())
        assertEquals(HeartRateTriggerEngine.State.ARMED, monitor.progress(15_000).state)
    }

    @Test fun `late sample restarts hold even when expiry callback was delayed`() {
        val monitor = monitor()
        monitor.onSample(130, 0)
        val resumed = monitor.onSample(135, 40_000)
        assertTrue(resumed.contains(HeartRateMonitor.Event.SignalLost(HeartRateMonitor.LossReason.TIMEOUT)))
        assertTrue(resumed.contains(HeartRateMonitor.Event.SignalAvailable(restored = true)))
        assertFalse(resumed.startsPlayback())
        assertEquals(45_000L, monitor.progress(40_000).deadlineMs)
        assertTrue(monitor.onTimer(45_000).startsPlayback())
    }

    @Test fun `timers cannot refresh the sample or report expiry repeatedly`() {
        val monitor = monitor()
        assertTrue(monitor.onTimer(0).isEmpty())
        assertNull(monitor.nextCheckDelayMs(0))
        monitor.onSample(130, 0)
        monitor.onTimer(5_000)
        monitor.onTimer(14_999)
        assertEquals(1L, monitor.nextCheckDelayMs(14_999))
        assertTrue(monitor.onTimer(15_000).any { it is HeartRateMonitor.Event.SignalLost })
        assertTrue(monitor.onTimer(15_001).isEmpty())
    }

    @Test fun `regular samples allow a hold longer than freshness window`() {
        val monitor = monitor(30_000)
        monitor.onSample(130, 0)
        monitor.onSample(130, 10_000)
        monitor.onSample(130, 20_000)
        assertTrue(monitor.onTimer(30_000).startsPlayback())
        assertEquals(HeartRateMonitor.Signal.LIVE, monitor.signal)
    }

    @Test fun `invalid heart rates do not refresh or trigger`() {
        val monitor = monitor(30_000)
        assertTrue(monitor.onSample(0, 0).isEmpty())
        assertNull(monitor.bpm)
        monitor.onSample(130, 0)
        monitor.onSample(-1, 10_000)
        monitor.onSample(256, 14_000)
        assertEquals(1_000L, monitor.nextCheckDelayMs(14_000))
        assertTrue(monitor.onSample(0, 15_000).any { it is HeartRateMonitor.Event.SignalLost })
        assertNull(monitor.bpm)
    }

    @Test fun `disconnect clears pending and playing state then reconnect starts anew`() {
        val monitor = monitor()
        monitor.onSample(130, 0)
        monitor.disconnect(4_000)
        assertFalse(monitor.onTimer(5_000).startsPlayback())
        assertEquals(HeartRateMonitor.Signal.WAITING, monitor.signal)
        monitor.onSample(130, 6_000)
        assertTrue(monitor.onTimer(11_000).startsPlayback())
        assertTrue(monitor.disconnect(12_000).contains(
            HeartRateMonitor.Event.SignalLost(HeartRateMonitor.LossReason.DISCONNECTED),
        ))
        assertNull(monitor.nextCheckDelayMs(12_000))
        assertNull(monitor.bpm)
        assertEquals(HeartRateTriggerEngine.State.ARMED, monitor.progress(12_000).state)
    }

    @Test fun `recovery and cooldown also expire without fresh samples`() {
        val monitor = monitor()
        monitor.onSample(130, 0)
        monitor.onTimer(5_000)
        monitor.onSample(100, 6_000)
        assertTrue(monitor.onTimer(16_000).contains(
            HeartRateMonitor.Event.Playback(HeartRateTriggerEngine.Event.StopPlayback),
        ))
        assertEquals(HeartRateTriggerEngine.State.COOLDOWN, monitor.progress(16_000).state)
        assertEquals(5_000L, monitor.nextCheckDelayMs(16_000))
        monitor.onTimer(21_000)
        assertEquals(HeartRateMonitor.Signal.STALE, monitor.signal)
        assertNull(monitor.progress(21_000).deadlineMs)
    }
}
