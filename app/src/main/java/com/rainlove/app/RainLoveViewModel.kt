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
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rainlove.app.history.HeartRateHistory
import com.rainlove.app.history.HeartRateRecord
import com.rainlove.app.media.MusicPlayer
import com.rainlove.app.media.BilibiliVideo
import com.rainlove.app.media.NeteaseMusic
import com.rainlove.app.media.ExternalLink
import com.rainlove.app.media.TriggerTarget
import com.rainlove.app.profiles.TriggerProfile
import com.rainlove.app.profiles.TriggerProfileStore
import com.rainlove.app.sensor.BleHeartRateDevice
import com.rainlove.app.sensor.BleHeartRateScanner
import com.rainlove.app.sensor.HeartRateTransport
import com.rainlove.app.sensor.mergeHeartRateDevices
import com.rainlove.app.trigger.HeartRateTriggerEngine
import com.rainlove.app.trigger.TriggerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val neteaseSongId: String = "",
    val neteaseAutoPlay: Boolean = true,
    val externalLink: String = "",
    val externalPackage: String = "",
    val externalAutoPlay: Boolean = false,
    val externalBackgroundDirect: Boolean = false,
    val demoMode: Boolean = true,
    val resumeOnBoot: Boolean = false,
    val heartRateTransport: HeartRateTransport = HeartRateTransport.BLE,
    val monitoring: Boolean = false,
    val scanningDevices: Boolean = false,
    val availableDevices: List<BleHeartRateDevice> = emptyList(),
    val selectedDeviceAddress: String? = null,
    val selectedDeviceName: String? = null,
    val triggerState: HeartRateTriggerEngine.State = HeartRateTriggerEngine.State.ARMED,
    val historyRecords: List<HeartRateRecord> = emptyList(),
    val profileNames: List<String> = emptyList(),
)

class RainLoveViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(RainLovePreferences.NAME, Context.MODE_PRIVATE)
    private val bluetoothManager = application.getSystemService(BluetoothManager::class.java)
    private val _ui = MutableStateFlow(loadState())
    val ui = _ui.asStateFlow()
    private val musicPlayer = MusicPlayer(application, ::onMusicEvent)
    private val history = HeartRateHistory(application)
    private val profileStore = TriggerProfileStore(preferences)
    private var lastDemoHistorySampleElapsedMs = 0L
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
        if (_ui.value.triggerTarget == TriggerTarget.NETEASE_MUSIC &&
            NeteaseMusic.normalizeSongId(_ui.value.neteaseSongId) == null
        ) {
            _ui.value = _ui.value.copy(status = "请输入有效的网易云歌曲 ID 或链接")
            return
        }
        if (_ui.value.triggerTarget == TriggerTarget.EXTERNAL_LINK &&
            (ExternalLink.normalizeUrl(_ui.value.externalLink) == null ||
                ExternalLink.normalizePackage(_ui.value.externalPackage) == null)
        ) {
            _ui.value = _ui.value.copy(status = "请输入有效的媒体链接和 App 包名")
            return
        }
        rebuildEngine()
        if (_ui.value.demoMode) {
            lastDemoHistorySampleElapsedMs = 0L
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

    fun setResumeOnBoot(enabled: Boolean) {
        _ui.value = _ui.value.copy(resumeOnBoot = enabled)
        preferences.edit().putBoolean(RainLovePreferences.RESUME_ON_BOOT, enabled).apply()
    }

    fun saveProfile(name: String) {
        if (_ui.value.monitoring) return
        val state = _ui.value
        val profile = TriggerProfile(
            triggerBpm = state.triggerBpm,
            recoveryBpm = state.recoveryBpm,
            triggerSeconds = state.triggerSeconds,
            recoverySeconds = state.recoverySeconds,
            cooldownSeconds = state.cooldownSeconds,
            demoMode = state.demoMode,
            transport = state.heartRateTransport,
            deviceAddress = state.selectedDeviceAddress,
            deviceName = state.selectedDeviceName,
            triggerTarget = state.triggerTarget,
            musicUri = preferences.getString(RainLovePreferences.MUSIC_URI, null),
            musicName = state.musicName,
            bilibiliBvid = state.bilibiliBvid,
            bilibiliAutoPlay = state.bilibiliAutoPlay,
            neteaseSongId = state.neteaseSongId,
            neteaseAutoPlay = state.neteaseAutoPlay,
            externalLink = state.externalLink,
            externalPackage = state.externalPackage,
            externalAutoPlay = state.externalAutoPlay,
            externalBackgroundDirect = state.externalBackgroundDirect,
        )
        val savedName = profileStore.save(name, profile)
        _ui.value = _ui.value.copy(
            profileNames = profileStore.names(),
            status = if (savedName == null) "方案名称不能为空、超长或包含控制字符"
            else "已保存方案：$savedName",
        )
    }

    fun loadProfile(name: String) {
        if (_ui.value.monitoring) return
        val profile = profileStore.load(name) ?: return
        stopDeviceScan()
        musicPlayer.pause()
        val canOpenBackground = profile.externalBackgroundDirect && Settings.canDrawOverlays(getApplication())
        if (profile.musicUri == null) musicPlayer.clearSelection()
        else musicPlayer.select(Uri.parse(profile.musicUri))
        preferences.edit()
            .putInt(RainLovePreferences.TRIGGER_BPM, profile.triggerBpm)
            .putInt(RainLovePreferences.RECOVERY_BPM, profile.recoveryBpm)
            .putInt(RainLovePreferences.TRIGGER_SECONDS, profile.triggerSeconds)
            .putInt(RainLovePreferences.RECOVERY_SECONDS, profile.recoverySeconds)
            .putInt(RainLovePreferences.COOLDOWN_SECONDS, profile.cooldownSeconds)
            .putBoolean(RainLovePreferences.DEMO_MODE, profile.demoMode)
            .putString(RainLovePreferences.HEART_RATE_TRANSPORT, profile.transport.name)
            .putString(RainLovePreferences.DEVICE_ADDRESS, profile.deviceAddress)
            .putString(RainLovePreferences.DEVICE_NAME, profile.deviceName)
            .putString(RainLovePreferences.TRIGGER_TARGET, profile.triggerTarget.name)
            .putString(RainLovePreferences.MUSIC_URI, profile.musicUri)
            .putString(RainLovePreferences.MUSIC_NAME, profile.musicName)
            .putString(RainLovePreferences.BILIBILI_BVID, profile.bilibiliBvid)
            .putBoolean(RainLovePreferences.BILIBILI_AUTO_PLAY, profile.bilibiliAutoPlay)
            .putString(RainLovePreferences.NETEASE_SONG_ID, profile.neteaseSongId)
            .putBoolean(RainLovePreferences.NETEASE_AUTO_PLAY, profile.neteaseAutoPlay)
            .putString(RainLovePreferences.EXTERNAL_LINK, profile.externalLink)
            .putString(RainLovePreferences.EXTERNAL_PACKAGE, profile.externalPackage)
            .putBoolean(RainLovePreferences.EXTERNAL_AUTO_PLAY, profile.externalAutoPlay)
            .putBoolean(RainLovePreferences.EXTERNAL_BACKGROUND_DIRECT, canOpenBackground)
            .apply()
        _ui.value = _ui.value.copy(
            triggerBpm = profile.triggerBpm,
            recoveryBpm = profile.recoveryBpm,
            triggerSeconds = profile.triggerSeconds,
            recoverySeconds = profile.recoverySeconds,
            cooldownSeconds = profile.cooldownSeconds,
            demoMode = profile.demoMode,
            heartRateTransport = profile.transport,
            selectedDeviceAddress = profile.deviceAddress,
            selectedDeviceName = profile.deviceName,
            triggerTarget = profile.triggerTarget,
            musicName = profile.musicName,
            musicPlaying = false,
            bilibiliBvid = profile.bilibiliBvid,
            bilibiliAutoPlay = profile.bilibiliAutoPlay,
            neteaseSongId = profile.neteaseSongId,
            neteaseAutoPlay = profile.neteaseAutoPlay,
            externalLink = profile.externalLink,
            externalPackage = profile.externalPackage,
            externalAutoPlay = profile.externalAutoPlay,
            externalBackgroundDirect = canOpenBackground,
            status = "已切换到方案：$name",
        )
        rebuildEngine()
    }

    fun deleteProfile(name: String) {
        if (_ui.value.monitoring) return
        if (profileStore.delete(name)) {
            _ui.value = _ui.value.copy(
                profileNames = profileStore.names(),
                status = "已删除方案：$name；当前设置保持不变",
            )
        }
    }

    fun setDemoBpm(value: Int) {
        _ui.value = _ui.value.copy(bpm = value)
        if (_ui.value.monitoring && _ui.value.demoMode) handleDemoHeartRate(value)
    }

    fun refreshHistory() {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) { history.latest() }
            _ui.value = _ui.value.copy(historyRecords = records)
        }
    }

    fun clearHistory() {
        if (_ui.value.monitoring || HeartRateForegroundService.isRunning) {
            _ui.value = _ui.value.copy(status = "请先停止监测，再清空历史")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { history.deleteAll() } }
            _ui.value = if (result.isSuccess) {
                _ui.value.copy(
                    historyRecords = emptyList(),
                    status = "已删除 ${result.getOrThrow()} 条本地心率记录",
                )
            } else {
                _ui.value.copy(status = "清理心率记录失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
            }
        }
    }

    fun exportHistory(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("无法创建导出文件")
                    history.exportCsv(output)
                }
            }
            _ui.value = _ui.value.copy(
                status = if (result.isSuccess) "心率历史已导出为 CSV"
                else "导出心率历史失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
            )
        }
    }

    fun setHeartRateTransport(transport: HeartRateTransport) {
        if (_ui.value.monitoring) return
        stopDeviceScan()
        _ui.value = _ui.value.copy(
            heartRateTransport = transport,
            status = when (transport) {
                HeartRateTransport.BLE -> "已选择 Bluetooth LE 心率"
                HeartRateTransport.ANT_PLUS -> "已选择 ANT+ 心率，将自动连接第一个可用设备"
            },
        )
        preferences.edit()
            .putString(RainLovePreferences.HEART_RATE_TRANSPORT, transport.name)
            .apply()
    }

    fun scanForDevices() {
        if (_ui.value.monitoring || _ui.value.demoMode ||
            _ui.value.heartRateTransport != HeartRateTransport.BLE
        ) return
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

    fun setExternalBackgroundDirect(enabled: Boolean) {
        if (_ui.value.monitoring) return
        _ui.value = _ui.value.copy(
            externalBackgroundDirect = enabled,
            status = if (enabled) "已允许后台直接打开外部媒体 App" else _ui.value.status,
        )
        preferences.edit().putBoolean(RainLovePreferences.EXTERNAL_BACKGROUND_DIRECT, enabled).apply()
    }

    fun reconcileExternalBackgroundDirectPermission(permissionGranted: Boolean) {
        if (_ui.value.externalBackgroundDirect && !permissionGranted) {
            _ui.value = _ui.value.copy(externalBackgroundDirect = false)
            preferences.edit().putBoolean(RainLovePreferences.EXTERNAL_BACKGROUND_DIRECT, false).apply()
        }
    }

    fun setNeteaseSongId(value: String) {
        if (_ui.value.monitoring) return
        _ui.value = _ui.value.copy(neteaseSongId = value)
        preferences.edit().putString(RainLovePreferences.NETEASE_SONG_ID, value).apply()
    }

    fun setNeteaseAutoPlay(enabled: Boolean) {
        if (_ui.value.monitoring) return
        _ui.value = _ui.value.copy(neteaseAutoPlay = enabled)
        preferences.edit().putBoolean(RainLovePreferences.NETEASE_AUTO_PLAY, enabled).apply()
    }

    fun setExternalLink(value: String) {
        if (_ui.value.monitoring) return
        _ui.value = _ui.value.copy(externalLink = value)
        preferences.edit().putString(RainLovePreferences.EXTERNAL_LINK, value).apply()
    }

    fun setExternalPackage(value: String) {
        if (_ui.value.monitoring) return
        _ui.value = _ui.value.copy(externalPackage = value)
        preferences.edit().putString(RainLovePreferences.EXTERNAL_PACKAGE, value).apply()
    }

    fun setExternalAutoPlay(enabled: Boolean) {
        if (_ui.value.monitoring) return
        _ui.value = _ui.value.copy(externalAutoPlay = enabled)
        preferences.edit().putBoolean(RainLovePreferences.EXTERNAL_AUTO_PLAY, enabled).apply()
    }

    fun testExternalLink() {
        _ui.value = _ui.value.copy(
            status = if (ExternalLink.open(
                    getApplication(),
                    _ui.value.externalLink,
                    _ui.value.externalPackage,
                    _ui.value.externalAutoPlay,
                )) {
                "已发送外部媒体打开请求；请确认目标 App 是否正确打开"
            } else {
                "外部媒体链接或包名无效"
            }
        )
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

    fun testNeteaseSong() {
        val songId = NeteaseMusic.normalizeSongId(_ui.value.neteaseSongId)
        _ui.value = _ui.value.copy(
            status = when {
                songId == null -> "请输入有效的网易云歌曲 ID 或链接"
                NeteaseMusic.open(getApplication(), songId, _ui.value.neteaseAutoPlay) ->
                    "已打开网易云歌曲 $songId"
                else -> "无法打开网易云歌曲"
            }
        )
    }

    private fun handleDemoHeartRate(bpm: Int) {
        val nowMs = SystemClock.elapsedRealtime()
        if (lastDemoHistorySampleElapsedMs == 0L || nowMs - lastDemoHistorySampleElapsedMs >= 1_000L) {
            lastDemoHistorySampleElapsedMs = nowMs
            val timestampMs = System.currentTimeMillis()
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { history.record(timestampMs, bpm, "DEMO") }
            }
        }
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
                    TriggerTarget.NETEASE_MUSIC -> {
                        if (NeteaseMusic.open(
                                getApplication(),
                                _ui.value.neteaseSongId,
                                _ui.value.neteaseAutoPlay,
                            )
                        ) {
                            "达到触发条件，已打开网易云歌曲"
                        } else {
                            "已触发，但无法打开网易云歌曲"
                        }
                    }
                    TriggerTarget.EXTERNAL_LINK -> {
                        if (ExternalLink.open(
                                getApplication(),
                                _ui.value.externalLink,
                                _ui.value.externalPackage,
                                _ui.value.externalAutoPlay,
                            )
                        ) {
                            "已发送外部媒体打开请求"
                        } else {
                            "已触发，但外部媒体链接或包名无效"
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
            neteaseSongId = preferences.getString(RainLovePreferences.NETEASE_SONG_ID, "") ?: "",
            neteaseAutoPlay = preferences.getBoolean(RainLovePreferences.NETEASE_AUTO_PLAY, true),
            externalLink = preferences.getString(RainLovePreferences.EXTERNAL_LINK, "") ?: "",
            externalPackage = preferences.getString(RainLovePreferences.EXTERNAL_PACKAGE, "") ?: "",
            externalAutoPlay = preferences.getBoolean(RainLovePreferences.EXTERNAL_AUTO_PLAY, false),
            externalBackgroundDirect = if (preferences.contains(RainLovePreferences.EXTERNAL_BACKGROUND_DIRECT)) {
                preferences.getBoolean(RainLovePreferences.EXTERNAL_BACKGROUND_DIRECT, false)
            } else {
                preferences.getBoolean(RainLovePreferences.BILIBILI_BACKGROUND_DIRECT, false)
            },
            demoMode = preferences.getBoolean(RainLovePreferences.DEMO_MODE, true),
            resumeOnBoot = preferences.getBoolean(RainLovePreferences.RESUME_ON_BOOT, false),
            heartRateTransport = preferences.getString(RainLovePreferences.HEART_RATE_TRANSPORT, null)
                ?.let { runCatching { HeartRateTransport.valueOf(it) }.getOrNull() }
                ?: HeartRateTransport.BLE,
            selectedDeviceAddress = preferences.getString(RainLovePreferences.DEVICE_ADDRESS, null),
            selectedDeviceName = preferences.getString(RainLovePreferences.DEVICE_NAME, null),
            profileNames = preferences.getStringSet(RainLovePreferences.PROFILE_NAMES, emptySet())
                .orEmpty().sorted(),
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
