package com.rainlove.app.trigger

/** A monotonic deadline shared by the service and UI; reading it never advances the engine. */
data class TriggerProgress(
    val state: HeartRateTriggerEngine.State = HeartRateTriggerEngine.State.ARMED,
    val deadlineMs: Long? = null,
) {
    fun remainingSeconds(nowMs: Long): Long? = deadlineMs?.let {
        val remainingMs = (it - nowMs).coerceAtLeast(0)
        remainingMs / 1_000 + if (remainingMs % 1_000 > 0) 1 else 0
    }

    val label: String
        get() = when (state) {
            HeartRateTriggerEngine.State.ARMED -> "等待达到阈值"
            HeartRateTriggerEngine.State.HIGH_PENDING -> "达到阈值，正在计时"
            HeartRateTriggerEngine.State.PLAYING -> "已触发"
            HeartRateTriggerEngine.State.RECOVERY_PENDING -> "心率恢复，正在计时"
            HeartRateTriggerEngine.State.COOLDOWN -> "冷却中"
        }
}
