package com.rainlove.app.history

import java.time.Instant

enum class TriggerEventType(val label: String) {
    SESSION_STARTED("开始监测"), SESSION_STOPPED("停止监测"),
    STATE_CHANGED("触发状态变化"), TRIGGER_REQUEST("执行触发"),
    PLAYBACK_STARTED("本地音乐已播放"), PLAYBACK_STOPPED("本地音乐已停止"),
    PLAYBACK_ERROR("播放失败"), SIGNAL_LOST("心率数据中断"),
    SIGNAL_AVAILABLE("收到有效心率"), STATUS("运行提示"),
}

data class TriggerEventRecord(
    val timestampMs: Long,
    val source: String,
    val type: TriggerEventType,
    val message: String,
    val bpm: Int? = null,
) {
    fun csvRow(): String = listOf(
        Instant.ofEpochMilli(timestampMs).toString(), source, type.name,
        bpm?.toString().orEmpty(), message,
    ).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } + "\n"
}
