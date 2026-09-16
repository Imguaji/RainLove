package com.rainlove.app.trigger

data class TriggerConfig(
    val triggerBpm: Int = 120,
    val triggerDurationMs: Long = 5_000,
    val recoveryBpm: Int = 110,
    val recoveryDurationMs: Long = 10_000,
    val cooldownMs: Long = 60_000,
) {
    init {
        require(triggerBpm in 40..240)
        require(recoveryBpm in 30 until triggerBpm)
        require(triggerDurationMs >= 0)
        require(recoveryDurationMs >= 0)
        require(cooldownMs >= 0)
    }
}
