package com.rainlove.app

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.rainlove.app.media.MusicPlayer
import com.rainlove.app.media.BilibiliVideo
import com.rainlove.app.media.TriggerTarget
import com.rainlove.app.sensor.BleHeartRateDevice
import com.rainlove.app.sensor.BleHeartRateScanner
import com.rainlove.app.sensor.mergeHeartRateDevices
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
    val musicPlaying: Boolean = false,
    val triggerTarget: TriggerTarget = TriggerTarget.LOCAL_MUSIC,
    val bilibiliBvid: String = "",
    val bilibiliAutoPlay: Boolean = true,
    val demoMode: Boolean = true,
    val monitoring: Boolean = false,
    val scanningDevices: Boolean = false,
    val availableDevices: List<BleHeartRateDevice> = emptyList(),
    val selectedDeviceAddress: String? = null,
    val selectedDeviceName: String? = null,
    val triggerState: HeartRateTriggerEngine.State = HeartRateTriggerEngine.State.ARMED,
)

class RainLoveViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(RainLovePreferences.NAME, Context.MODE_PRIVATE)
    private val bluetoothManager = application.getSystemService(BluetoothManager::class.java)
    private val _ui = MutableStateFlow(loadState())
    val ui = _ui.asStateFlow()
    private val musicPlayer = MusicPlayer(application, ::onMusicEvent)
    private var engine = HeartRateTriggerEngine(_ui.value.toTriggerConfig())
    private var deviceScanner: BleHeartRateScanner? = null
    private val demoHandler = Handler(Looper.getMainLooper())
    private val demoTick = object : Runnable {
        override fun run() {
            if (!_ui.value.monitoring || !_ui.value.demoMode) return
            handleDemoHeartRate(_ui.value.bpm)
            demoHandler.postDelayed(this, DEMO_TICK_MS)
        }
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != HeartRateForegroundService.ACTION_STATE) return
            var state = _ui.value
            if (intent.hasExtra(HeartRateForegroundService.EXTRA_MONITORING)) {
                state = state.copy(
                    monitoring = intent.getBooleanExtra(HeartRateForegroundService.EXTRA_MONITORING, false)
                )
            }
            intent.getStringExtra(HeartRateForegroundService.EXTRA_STATUS)?.let {
                state = state.copy(status = it)
            }
            if (intent.hasExtra(HeartRateForegroundService.EXTRA_BPM)) {
                state = state.copy(
                    bpm = intent.getIntExtra(HeartRateForegroundService.EXTRA_BPM, state.bpm)
                )
            }
            intent.getStringExtra(HeartRateForegroundService.EXTRA_TRIGGER_STATE)?.let { rawState ->
                runCatching { HeartRateTriggerEngine.State.valueOf(rawState) }.getOrNull()?.let {
                    state = state.copy(triggerState = it)
                }
            }
            val address = intent.getStringExtra(HeartRateForegroundService.EXTRA_DEVICE_ADDRESS)
            val name = intent.getStringExtra(HeartRateForegroundService.EXTRA_DEVICE_NAME)
            if (address != null && name != null) {
                state = state.copy(selectedDeviceAddress = address, selectedDeviceName = name)
            }
            _ui.value = state
        }
    }

    init {
        preferences.getString(RainLovePreferences.MUSIC_URI, null)?.let {
            musicPlayer.select(Uri.parse(it))
        }
        val filter = IntentFilter(HeartRateForegroundService.ACTION_STATE)
        ContextCompat.registerReceiver(
            application,
            serviceStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (HeartRateForegroundService.isRunning) {
            _ui.value = _ui.value.copy(
                monitoring = true,
                demoMode = false,
                status = HeartRateForegroundService.currentStatus,
            )
        }
    }

    fun toggleMonitoring() {
        if (_ui.value.monitoring) stop() else start()
    }

    private fun start() {
        stopDeviceScan()
        musicPlayer.pause()
        _ui.value = _ui.value.copy(musicPlaying = false)
        if (_ui.value.triggerTarget == TriggerTarget.BILIBILI_VIDEO &&
            BilibiliVideo.normalizeBvid(_ui.value.bilibiliBvid) == null
        ) {
            _ui.value = _ui.value.copy(status = "请输入有效的 BV 号")
            return
        }
        rebuildEngine()
        if (_ui.value.demoMode) {
            _ui.value = _ui.value.copy(monitoring = true, status = "Demo 模式运行中")
            startDemoTicker()
        } else {
            _ui.value = _ui.value.copy(monitoring = true, status = "正在启动后台监测…")
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), HeartRateForegroundService::class.java)
                    .setAction(HeartRateForegroundService.ACTION_START),
            )
        }
    }

    private fun stop() {
        stopDemoTicker()
        if (_ui.value.demoMode) {
            musicPlayer.pause()
            engine.reset(SystemClock.elapsedRealtime())
            _ui.value = _ui.value.copy(monitoring = false, status = "已停止", triggerState = engine.state)
        } else {
            getApplication<Application>().startService(
                Intent(getApplication(), HeartRateForegroundService::class.java)
                    .setAction(HeartRateForegroundService.ACTION_STOP)
            )
            _ui.value = _ui.value.copy(monitoring = false, status = "已停止")
        }
    }

    fun setDemoMode(enabled: Boolean) {
        if (_ui.value.monitoring) stop()
        if (enabled) stopDeviceScan()
        _ui.value = _ui.value.copy(demoMode = enabled)
        preferences.edit().putBoolean(RainLovePreferences.DEMO_MODE, enabled).apply()
    }

    fun setDemoBpm(value: Int) {
        _ui.value = _ui.value.copy(bpm = value)
        if (_ui.value.monitoring && _ui.value.demoMode) handleDemoHeartRate(value)
    }

    fun scanForDevices() {
        if (_ui.value.monitoring || _ui.value.demoMode) return
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            _ui.value = _ui.value.copy(status = "蓝牙不可用")
            return
        }
        deviceScanner?.stop()
        _ui.value = _ui.value.copy(
            scanningDevices = true,
            availableDevices = emptyList(),
            status = "正在查找心率设备…",
        )
        BleHeartRateScanner(scanner).also { discovery ->
            deviceScanner = discovery
            discovery.start(
                onDevice = { device ->
                    _ui.value = _ui.value.copy(
                        availableDevices = mergeHeartRateDevices(_ui.value.availableDevices, device),
                        status = "请选择心率设备",
                    )
                },
                onError = { message ->
                    deviceScanner = null
                    _ui.value = _ui.value.copy(scanningDevices = false, status = message)
                },
                onFinished = {
                    deviceScanner = null
                    _ui.value = _ui.value.copy(
                        scanningDevices = false,
                        status = if (_ui.value.availableDevices.isEmpty()) {
                            "未发现心率设备，请确认设备正在广播"
                        } else {
                            "扫描完成，请选择心率设备"
                        },
                    )
                },
            )
        }
    }

    fun selectDevice(device: BleHeartRateDevice?) {
        stopDeviceScan()
        _ui.value = _ui.value.copy(
            selectedDeviceAddress = device?.address,
            selectedDeviceName = device?.name,
            status = if (device == null) "将自动连接附近兼容设备" else "已选择 ${device.name}",
        )
        preferences.edit()
            .putString(RainLovePreferences.DEVICE_ADDRESS, device?.address)
            .putString(RainLovePreferences.DEVICE_NAME, device?.name)
            .apply()
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
            .putString(RainLovePreferences.MUSIC_URI, uri.toString())
            .putString(RainLovePreferences.MUSIC_NAME, name)
            .apply()
    }

    fun toggleMusicPreview() {
        if (_ui.value.monitoring) return
        if (musicPlayer.isPlaying()) {
            musicPlayer.pause()
            _ui.value = _ui.value.copy(status = "已停止测试播放")
        } else {
            val status = if (musicPlayer.play()) {
                "正在启动测试播放…"
            } else {
                "请先选择本地音乐"
            }
            _ui.value = _ui.value.copy(status = status)
        }
    }

    fun setTriggerTarget(target: TriggerTarget) {
        if (_ui.value.monitoring) return
        if (target != TriggerTarget.LOCAL_MUSIC) musicPlayer.pause()
        _ui.value = _ui.value.copy(triggerTarget = target)
        preferences.edit().putString(RainLovePreferences.TRIGGER_TARGET, target.name).apply()
    }

    fun setBilibiliBvid(value: String) {
        if (_ui.value.monitoring) return
        _ui.value = _ui.value.copy(bilibiliBvid = value)
        preferences.edit().putString(RainLovePreferences.BILIBILI_BVID, value).apply()
    }

    fun setBilibiliAutoPlay(enabled: Boolean) {
        if (_ui.value.monitoring) return
        _ui.value = _ui.value.copy(bilibiliAutoPlay = enabled)
        preferences.edit().putBoolean(RainLovePreferences.BILIBILI_AUTO_PLAY, enabled).apply()
    }

    fun testBilibiliVideo() {
        val bvid = BilibiliVideo.normalizeBvid(_ui.value.bilibiliBvid)
        _ui.value = _ui.value.copy(
            status = when {
                bvid == null -> "请输入有效的 BV 号"
                BilibiliVideo.open(getApplication(), bvid, _ui.value.bilibiliAutoPlay) -> "已打开 $bvid"
                else -> "无法打开视频链接"
            }
        )
    }

    private fun handleDemoHeartRate(bpm: Int) {
        when (engine.onHeartRate(bpm, SystemClock.elapsedRealtime())) {
            HeartRateTriggerEngine.Event.StartPlayback -> {
                val status = when (_ui.value.triggerTarget) {
                    TriggerTarget.LOCAL_MUSIC -> {
                        if (musicPlayer.play()) "达到触发条件，正在启动音乐…" else "已触发，但尚未选择音乐"
                    }
                    TriggerTarget.BILIBILI_VIDEO -> {
                        if (BilibiliVideo.open(
                                getApplication(),
                                _ui.value.bilibiliBvid,
                                _ui.value.bilibiliAutoPlay,
                            )
                        ) {
                            "达到触发条件，已打开 B 站视频"
                        } else {
                            "已触发，但无法打开 B 站视频"
                        }
                    }
                }
                _ui.value = _ui.value.copy(status = status)
            }
            HeartRateTriggerEngine.Event.StopPlayback -> {
                if (_ui.value.triggerTarget == TriggerTarget.LOCAL_MUSIC) musicPlayer.pause()
                _ui.value = _ui.value.copy(status = "心率已恢复，进入冷却")
            }
            null -> Unit
        }
        _ui.value = _ui.value.copy(bpm = bpm, triggerState = engine.state)
    }

    private fun rebuildEngine() {
        engine = HeartRateTriggerEngine(_ui.value.toTriggerConfig())
    }

    private fun onMusicEvent(event: MusicPlayer.Event) {
        _ui.value = when (event) {
            MusicPlayer.Event.Started -> _ui.value.copy(
                musicPlaying = true,
                status = "本地音乐已开始播放",
            )
            MusicPlayer.Event.Stopped -> _ui.value.copy(musicPlaying = false)
            is MusicPlayer.Event.Error -> _ui.value.copy(
                musicPlaying = false,
                status = "音乐播放失败：${event.detail}，请重新选择文件",
            )
        }
    }

    private fun loadState(): RainLoveUiState {
        val triggerBpm = preferences.getInt(RainLovePreferences.TRIGGER_BPM, 120).coerceIn(80, 200)
        return RainLoveUiState(
            triggerBpm = triggerBpm,
            recoveryBpm = preferences.getInt(RainLovePreferences.RECOVERY_BPM, 110).coerceIn(50, triggerBpm - 1),
            triggerSeconds = preferences.getInt(RainLovePreferences.TRIGGER_SECONDS, 5).coerceIn(1, 30),
            recoverySeconds = preferences.getInt(RainLovePreferences.RECOVERY_SECONDS, 10).coerceIn(1, 30),
            cooldownSeconds = preferences.getInt(RainLovePreferences.COOLDOWN_SECONDS, 60).coerceIn(0, 300),
            musicName = preferences.getString(RainLovePreferences.MUSIC_NAME, null) ?: "尚未选择音乐",
            triggerTarget = preferences.getString(RainLovePreferences.TRIGGER_TARGET, null)
                ?.let { runCatching { TriggerTarget.valueOf(it) }.getOrNull() }
                ?: TriggerTarget.LOCAL_MUSIC,
            bilibiliBvid = preferences.getString(RainLovePreferences.BILIBILI_BVID, "") ?: "",
            bilibiliAutoPlay = preferences.getBoolean(RainLovePreferences.BILIBILI_AUTO_PLAY, true),
            demoMode = preferences.getBoolean(RainLovePreferences.DEMO_MODE, true),
            selectedDeviceAddress = preferences.getString(RainLovePreferences.DEVICE_ADDRESS, null),
            selectedDeviceName = preferences.getString(RainLovePreferences.DEVICE_NAME, null),
        )
    }

    private fun persistSettings() {
        val state = _ui.value
        preferences.edit()
            .putInt(RainLovePreferences.TRIGGER_BPM, state.triggerBpm)
            .putInt(RainLovePreferences.RECOVERY_BPM, state.recoveryBpm)
            .putInt(RainLovePreferences.TRIGGER_SECONDS, state.triggerSeconds)
            .putInt(RainLovePreferences.RECOVERY_SECONDS, state.recoverySeconds)
            .putInt(RainLovePreferences.COOLDOWN_SECONDS, state.cooldownSeconds)
            .apply()
    }

    override fun onCleared() {
        stopDemoTicker()
        deviceScanner?.stop()
        getApplication<Application>().unregisterReceiver(serviceStateReceiver)
        musicPlayer.release()
    }

    private fun stopDeviceScan() {
        deviceScanner?.stop()
        deviceScanner = null
        _ui.value = _ui.value.copy(scanningDevices = false)
    }

    private fun startDemoTicker() {
        demoHandler.removeCallbacks(demoTick)
        demoHandler.post(demoTick)
    }

    private fun stopDemoTicker() {
        demoHandler.removeCallbacks(demoTick)
    }

    private fun RainLoveUiState.toTriggerConfig() = TriggerConfig(
        triggerBpm = triggerBpm,
        triggerDurationMs = triggerSeconds * 1_000L,
        recoveryBpm = recoveryBpm,
        recoveryDurationMs = recoverySeconds * 1_000L,
        cooldownMs = cooldownSeconds * 1_000L,
    )

    private companion object {
        const val DEMO_TICK_MS = 250L
    }
}
