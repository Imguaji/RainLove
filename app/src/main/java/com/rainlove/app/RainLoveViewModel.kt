package com.rainlove.app

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import com.rainlove.app.media.MusicPlayer
import com.rainlove.app.sensor.BleHeartRateSource
import com.rainlove.app.sensor.HeartRateSource
import com.rainlove.app.trigger.HeartRateTriggerEngine
import com.rainlove.app.trigger.TriggerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RainLoveUiState(
    val bpm: Int = 72,
    val status: String = "等待启动",
    val triggerBpm: Int = 120,
    val recoveryBpm: Int = 110,
    val triggerSeconds: Int = 5,
    val recoverySeconds: Int = 10,
    val cooldownSeconds: Int = 60,
    val musicName: String = "尚未选择音乐",
    val demoMode: Boolean = true,
    val monitoring: Boolean = false,
    val triggerState: HeartRateTriggerEngine.State = HeartRateTriggerEngine.State.ARMED,
)

class RainLoveViewModel(application: Application) : AndroidViewModel(application), HeartRateSource.Listener {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val musicPlayer = MusicPlayer(application)
    private val bluetoothManager = application.getSystemService(BluetoothManager::class.java)
    private val _ui = MutableStateFlow(loadState())
    val ui = _ui.asStateFlow()
    private var engine = HeartRateTriggerEngine(_ui.value.toTriggerConfig())
    private var bleSource: BleHeartRateSource? = null

    init {
        preferences.getString(KEY_MUSIC_URI, null)?.let { musicPlayer.select(Uri.parse(it)) }
    }

    fun toggleMonitoring() {
        if (_ui.value.monitoring) stop() else start()
    }

    private fun start() {
        rebuildEngine()
        _ui.value = _ui.value.copy(monitoring = true, status = if (_ui.value.demoMode) "Demo 模式运行中" else "准备扫描")
        if (!_ui.value.demoMode) {
            val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
            if (scanner == null) onError("蓝牙不可用")
            else BleHeartRateSource(getApplication(), scanner).also { bleSource = it }.start(this)
        }
    }

    private fun stop() {
        bleSource?.stop()
        bleSource = null
        musicPlayer.pause()
        engine.reset(SystemClock.elapsedRealtime())
        _ui.value = _ui.value.copy(monitoring = false, status = "已停止", triggerState = engine.state)
    }

    fun setDemoMode(enabled: Boolean) {
        if (_ui.value.monitoring) stop()
        _ui.value = _ui.value.copy(demoMode = enabled)
        preferences.edit().putBoolean(KEY_DEMO_MODE, enabled).apply()
    }

    fun setDemoBpm(value: Int) {
        _ui.value = _ui.value.copy(bpm = value)
        if (_ui.value.monitoring && _ui.value.demoMode) handleHeartRate(value)
    }

    fun setTriggerBpm(value: Int) {
        _ui.value = _ui.value.copy(triggerBpm = value, recoveryBpm = minOf(_ui.value.recoveryBpm, value - 1))
        persistSettings()
        rebuildEngine()
    }

    fun setRecoveryBpm(value: Int) {
        _ui.value = _ui.value.copy(recoveryBpm = value.coerceAtMost(_ui.value.triggerBpm - 1))
        persistSettings()
        rebuildEngine()
    }

    fun setTriggerSeconds(value: Int) {
        _ui.value = _ui.value.copy(triggerSeconds = value.coerceIn(1, 30))
        persistSettings()
        rebuildEngine()
    }

    fun setRecoverySeconds(value: Int) {
        _ui.value = _ui.value.copy(recoverySeconds = value.coerceIn(1, 30))
        persistSettings()
        rebuildEngine()
    }

    fun setCooldownSeconds(value: Int) {
        _ui.value = _ui.value.copy(cooldownSeconds = value.coerceIn(0, 300))
        persistSettings()
        rebuildEngine()
    }

    fun selectMusic(uri: Uri) {
        val name = getApplication<Application>().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "已选择的音乐"
        musicPlayer.select(uri)
        _ui.value = _ui.value.copy(musicName = name)
        preferences.edit()
            .putString(KEY_MUSIC_URI, uri.toString())
            .putString(KEY_MUSIC_NAME, name)
            .apply()
    }

    override fun onHeartRate(bpm: Int) = handleHeartRate(bpm)
    override fun onStatus(message: String) { _ui.value = _ui.value.copy(status = message) }
    override fun onError(message: String) { _ui.value = _ui.value.copy(status = message, monitoring = false) }

    private fun handleHeartRate(bpm: Int) {
        when (engine.onHeartRate(bpm, SystemClock.elapsedRealtime())) {
            HeartRateTriggerEngine.Event.StartPlayback -> {
                if (!musicPlayer.play()) onStatus("已触发，但尚未选择音乐") else onStatus("达到触发条件，正在播放")
            }
            HeartRateTriggerEngine.Event.StopPlayback -> {
                musicPlayer.pause()
                onStatus("心率已恢复，进入冷却")
            }
            null -> Unit
        }
        _ui.value = _ui.value.copy(bpm = bpm, triggerState = engine.state)
    }

    private fun rebuildEngine() {
        engine = HeartRateTriggerEngine(_ui.value.toTriggerConfig())
    }

    private fun loadState(): RainLoveUiState {
        val triggerBpm = preferences.getInt(KEY_TRIGGER_BPM, 120).coerceIn(80, 200)
        return RainLoveUiState(
            triggerBpm = triggerBpm,
            recoveryBpm = preferences.getInt(KEY_RECOVERY_BPM, 110).coerceIn(50, triggerBpm - 1),
            triggerSeconds = preferences.getInt(KEY_TRIGGER_SECONDS, 5).coerceIn(1, 30),
            recoverySeconds = preferences.getInt(KEY_RECOVERY_SECONDS, 10).coerceIn(1, 30),
            cooldownSeconds = preferences.getInt(KEY_COOLDOWN_SECONDS, 60).coerceIn(0, 300),
            musicName = preferences.getString(KEY_MUSIC_NAME, null) ?: "尚未选择音乐",
            demoMode = preferences.getBoolean(KEY_DEMO_MODE, true),
        )
    }

    private fun persistSettings() {
        val state = _ui.value
        preferences.edit()
            .putInt(KEY_TRIGGER_BPM, state.triggerBpm)
            .putInt(KEY_RECOVERY_BPM, state.recoveryBpm)
            .putInt(KEY_TRIGGER_SECONDS, state.triggerSeconds)
            .putInt(KEY_RECOVERY_SECONDS, state.recoverySeconds)
            .putInt(KEY_COOLDOWN_SECONDS, state.cooldownSeconds)
            .apply()
    }

    override fun onCleared() {
        bleSource?.stop()
        musicPlayer.release()
    }

    private fun RainLoveUiState.toTriggerConfig() = TriggerConfig(
        triggerBpm = triggerBpm,
        triggerDurationMs = triggerSeconds * 1_000L,
        recoveryBpm = recoveryBpm,
        recoveryDurationMs = recoverySeconds * 1_000L,
        cooldownMs = cooldownSeconds * 1_000L,
    )

    companion object {
        private const val PREFERENCES_NAME = "rainlove_settings"
        private const val KEY_TRIGGER_BPM = "trigger_bpm"
        private const val KEY_RECOVERY_BPM = "recovery_bpm"
        private const val KEY_TRIGGER_SECONDS = "trigger_seconds"
        private const val KEY_RECOVERY_SECONDS = "recovery_seconds"
        private const val KEY_COOLDOWN_SECONDS = "cooldown_seconds"
        private const val KEY_DEMO_MODE = "demo_mode"
        private const val KEY_MUSIC_URI = "music_uri"
        private const val KEY_MUSIC_NAME = "music_name"
    }
}
