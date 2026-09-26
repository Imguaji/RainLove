package com.rainlove.app.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriggerProgressTest {
    private val config = TriggerConfig(
        triggerBpm = 120,
        triggerDurationMs = 5_000,
        recoveryBpm = 110,
        recoveryDurationMs = 10_000,
        cooldownMs = 60_000,
    )

    @Test fun `remaining seconds round up and never become negative`() {
        val progress = TriggerProgress(HeartRateTriggerEngine.State.HIGH_PENDING, 6_000)
        assertEquals(5L, progress.remainingSeconds(1_000))
        assertEquals(5L, progress.remainingSeconds(1_001))
        assertEquals(1L, progress.remainingSeconds(5_999))
        assertEquals(0L, progress.remainingSeconds(6_000))
        assertEquals(0L, progress.remainingSeconds(10_000))
    }

    @Test fun `new samples and page reopen keep the same trigger deadline`() {
        val engine = HeartRateTriggerEngine(config)
        engine.onHeartRate(130, 1_000)
        val initial = engine.progress(1_000)
        engine.onHeartRate(135, 3_000)
        val reopened = engine.progress(4_000)
        assertEquals(initial.deadlineMs, reopened.deadlineMs)
        assertEquals(2L, reopened.remainingSeconds(4_000))
        // Drawing an expired countdown must not itself trigger playback.
        assertEquals(0L, reopened.remainingSeconds(6_000))
        assertEquals(HeartRateTriggerEngine.State.HIGH_PENDING, engine.state)
        assertEquals(HeartRateTriggerEngine.Event.StartPlayback, engine.onHeartRate(130, 6_000))
        assertNull(engine.progress(6_000).deadlineMs)
    }

    @Test fun `falling below threshold and disconnect reset remove countdown`() {
        val engine = HeartRateTriggerEngine(config)
        engine.onHeartRate(130, 0)
        engine.onHeartRate(119, 2_000)
        assertNull(engine.progress(2_000).deadlineMs)
        engine.onHeartRate(130, 3_000)
        assertEquals(8_000L, engine.progress(3_000).deadlineMs)
        engine.reset(4_000)
        assertEquals(HeartRateTriggerEngine.State.ARMED, engine.progress(4_000).state)
        assertNull(engine.progress(4_000).remainingSeconds(4_000))
    }

    @Test fun `recovery cancellation and cooldown show their own deadlines`() {
        val engine = HeartRateTriggerEngine(config)
        engine.onHeartRate(130, 0)
        engine.onHeartRate(130, 5_000)
        engine.onHeartRate(100, 6_000)
        assertEquals(16_000L, engine.progress(7_000).deadlineMs)
        engine.onHeartRate(115, 8_000)
        assertNull(engine.progress(8_000).deadlineMs)
        engine.onHeartRate(100, 9_000)
        assertEquals(19_000L, engine.progress(9_000).deadlineMs)
        engine.onHeartRate(100, 19_000)
        val cooldown = engine.progress(20_000)
        assertEquals(HeartRateTriggerEngine.State.COOLDOWN, cooldown.state)
        assertEquals(59L, cooldown.remainingSeconds(20_000))
        engine.onHeartRate(100, 79_000)
        assertNull(engine.progress(79_000).deadlineMs)
    }
}
