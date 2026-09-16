package com.rainlove.app

import android.app.Application
import android.bluetooth.BluetoothManager
import android.net.Uri
import android.os.SystemClock
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
    val cooldownSeconds: Int = 60,
    val musicName: String = "尚未选择音乐",
    val demoMode: Boolean = true,
    val monitoring: Boolean = false,
    val triggerState: HeartRateTriggerEngine.State = HeartRateTriggerEngine.State.ARMED,
)

class RainLoveViewModel(application: Application) : AndroidViewModel(application), HeartRateSource.Listener {
    private val _ui = MutableStateFlow(RainLoveUiState())
    val ui = _ui.asStateFlow()
    private var engine = HeartRateTriggerEngine()
    private val musicPlayer = MusicPlayer(application)
    private val bluetoothManager = application.getSystemService(BluetoothManager::class.java)
    private var bleSource: BleHeartRateSource? = null

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
    }

    fun setDemoBpm(value: Int) {
        _ui.value = _ui.value.copy(bpm = value)
        if (_ui.value.monitoring && _ui.value.demoMode) handleHeartRate(value)
    }

    fun setTriggerBpm(value: Int) {
        _ui.value = _ui.value.copy(triggerBpm = value, recoveryBpm = minOf(_ui.value.recoveryBpm, value - 1))
        rebuildEngine()
    }

    fun setRecoveryBpm(value: Int) {
        _ui.value = _ui.value.copy(recoveryBpm = value.coerceAtMost(_ui.value.triggerBpm - 1))
        rebuildEngine()
    }

    fun selectMusic(uri: Uri, name: String) {
        musicPlayer.select(uri)
        _ui.value = _ui.value.copy(musicName = name)
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
        val s = _ui.value
        engine = HeartRateTriggerEngine(
            TriggerConfig(
                triggerBpm = s.triggerBpm,
                triggerDurationMs = s.triggerSeconds * 1_000L,
                recoveryBpm = s.recoveryBpm,
                cooldownMs = s.cooldownSeconds * 1_000L,
            )
        )
    }

    override fun onCleared() {
        bleSource?.stop()
        musicPlayer.release()
    }
}
