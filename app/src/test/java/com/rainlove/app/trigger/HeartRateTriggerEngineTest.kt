package com.rainlove.app.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateTriggerEngineTest {
    private val config = TriggerConfig(
        triggerBpm = 120,
        triggerDurationMs = 5_000,
        recoveryBpm = 110,
        recoveryDurationMs = 10_000,
        cooldownMs = 60_000,
    )

    @Test fun `high heart rate must persist before playback`() {
        val engine = HeartRateTriggerEngine(config)
        assertNull(engine.onHeartRate(121, 1_000))
        assertEquals(HeartRateTriggerEngine.State.HIGH_PENDING, engine.state)
        assertEquals(5_000L, engine.nextTransitionDelayMs(1_000))
        assertEquals(2_000L, engine.nextTransitionDelayMs(4_000))
        assertNull(engine.onHeartRate(125, 5_999))
        assertEquals(HeartRateTriggerEngine.Event.StartPlayback, engine.onHeartRate(125, 6_000))
        assertEquals(HeartRateTriggerEngine.State.PLAYING, engine.state)
        assertNull(engine.nextTransitionDelayMs(6_000))
    }

    @Test fun `brief spike returns to armed`() {
        val engine = HeartRateTriggerEngine(config)
        engine.onHeartRate(121, 1_000)
        assertNull(engine.onHeartRate(119, 2_000))
        assertEquals(HeartRateTriggerEngine.State.ARMED, engine.state)
    }

    @Test fun `recovery pauses and enters cooldown`() {
        val engine = HeartRateTriggerEngine(config)
        engine.onHeartRate(130, 0)
        engine.onHeartRate(130, 5_000)
        engine.onHeartRate(100, 6_000)
        assertEquals(HeartRateTriggerEngine.Event.StopPlayback, engine.onHeartRate(100, 16_000))
        assertEquals(HeartRateTriggerEngine.State.COOLDOWN, engine.state)
        assertNull(engine.onHeartRate(140, 50_000))
        engine.onHeartRate(100, 76_000)
        assertEquals(HeartRateTriggerEngine.State.ARMED, engine.state)
    }

    @Test fun `current high reading is reconsidered after cooldown expires`() {
        val engine = HeartRateTriggerEngine(config)
        engine.onHeartRate(130, 0)
        engine.onHeartRate(130, 5_000)
        engine.onHeartRate(100, 6_000)
        engine.onHeartRate(100, 16_000)

        assertNull(engine.onHeartRate(130, 76_000))
        assertEquals(HeartRateTriggerEngine.State.HIGH_PENDING, engine.state)
        assertEquals(HeartRateTriggerEngine.Event.StartPlayback, engine.onHeartRate(130, 81_000))
    }
}
