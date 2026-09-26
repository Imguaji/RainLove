package com.rainlove.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rainlove.app.history.HeartRateHistory
import com.rainlove.app.history.TriggerEventLog
import com.rainlove.app.history.TriggerEventRecord
import com.rainlove.app.history.TriggerEventType
import com.rainlove.app.media.MusicPlayer
import com.rainlove.app.media.BilibiliVideo
import com.rainlove.app.media.NeteaseMusic
import com.rainlove.app.media.ExternalLink
import com.rainlove.app.media.TriggerTarget
import com.rainlove.app.sensor.BleHeartRateDevice
import com.rainlove.app.sensor.BleHeartRateSource
import com.rainlove.app.sensor.AntPlusHeartRateSource
import com.rainlove.app.sensor.HeartRateSource
import com.rainlove.app.sensor.HeartRateTransport
import com.rainlove.app.trigger.HeartRateTriggerEngine
import com.rainlove.app.trigger.HeartRateMonitor
import com.rainlove.app.trigger.TriggerConfig
import com.rainlove.app.trigger.TriggerProgress
import java.util.concurrent.Executors

class HeartRateForegroundService : Service(), HeartRateSource.Listener {
    private lateinit var musicPlayer: MusicPlayer
    private lateinit var history: HeartRateHistory
    private lateinit var eventLog: TriggerEventLog
    private var localMusicWasPlaying = false
    private val historyExecutor = Executors.newSingleThreadExecutor()
    private var lastHistorySampleElapsedMs = 0L
    private var heartRateSource: HeartRateSource? = null
    private var heartRateTransport = HeartRateTransport.BLE
    private var monitor = HeartRateMonitor()
    private var triggerTarget = TriggerTarget.LOCAL_MUSIC
    private var bilibiliBvid = ""
    private var bilibiliAutoPlay = true
    private var neteaseSongId = ""
    private var neteaseAutoPlay = true
    private var externalLink = ""
    private var externalPackage = ""
    private var externalAutoPlay = false
    private var externalBackgroundDirect = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateAdvanceRunnable = Runnable {
        if (isRunning) {
            processMonitorEvents(monitor.onTimer(SystemClock.elapsedRealtime()))
        }
    }

    override fun onCreate() {
        super.onCreate()
        eventLog = TriggerEventLog.get(this)
        musicPlayer = MusicPlayer(this, ::onMusicEvent)
        history = HeartRateHistory(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring("已停止")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoring() {
        if (heartRateSource != null) return
        loadSessionSettings()
        mainHandler.removeCallbacks(stateAdvanceRunnable)
        lastHistorySampleElapsedMs = 0L
        currentBpm = null
        isRunning = true
        currentStatus = "正在启动心率监测…"
        startForeground(NOTIFICATION_ID, buildNotification("正在启动心率监测…"))
        recordEvent(TriggerEventType.SESSION_STARTED, "开始监测；心率数据有效期 ${HeartRateMonitor.STALE_AFTER_MS / 1_000} 秒")
        getSharedPreferences(RainLovePreferences.NAME, MODE_PRIVATE).edit()
            .putBoolean(RainLovePreferences.MONITORING_DESIRED, true)
            .commit()
        broadcastState(monitoring = true, status = "准备连接")
        val preferences = getSharedPreferences(RainLovePreferences.NAME, MODE_PRIVATE)
        heartRateSource = when (heartRateTransport) {
            HeartRateTransport.BLE -> {
                val scanner = getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?.bluetoothLeScanner
                if (scanner == null) {
                    onError("蓝牙不可用")
                    return
                }
                BleHeartRateSource(
                    context = this,
                    scanner = scanner,
                    targetAddress = preferences.getString(RainLovePreferences.DEVICE_ADDRESS, null),
                    onConnectedDevice = ::rememberConnectedDevice,
                )
            }
            HeartRateTransport.ANT_PLUS -> AntPlusHeartRateSource(this)
        }
        heartRateSource?.start(this)
    }

    private fun loadSessionSettings() {
        val preferences = getSharedPreferences(RainLovePreferences.NAME, MODE_PRIVATE)
        val triggerBpm = preferences.getInt(RainLovePreferences.TRIGGER_BPM, 120).coerceIn(80, 200)
        monitor = HeartRateMonitor(
            TriggerConfig(
                triggerBpm = triggerBpm,
                triggerDurationMs = preferences.getInt(RainLovePreferences.TRIGGER_SECONDS, 5).coerceIn(1, 30) * 1_000L,
                recoveryBpm = preferences.getInt(RainLovePreferences.RECOVERY_BPM, 110).coerceIn(50, triggerBpm - 1),
                recoveryDurationMs = preferences.getInt(RainLovePreferences.RECOVERY_SECONDS, 10).coerceIn(1, 30) * 1_000L,
                cooldownMs = preferences.getInt(RainLovePreferences.COOLDOWN_SECONDS, 60).coerceIn(0, 300) * 1_000L,
            )
        )
        preferences.getString(RainLovePreferences.MUSIC_URI, null)?.let {
            musicPlayer.select(android.net.Uri.parse(it))
        }
        triggerTarget = preferences.getString(RainLovePreferences.TRIGGER_TARGET, null)
            ?.let { runCatching { TriggerTarget.valueOf(it) }.getOrNull() }
            ?: TriggerTarget.LOCAL_MUSIC
        bilibiliBvid = preferences.getString(RainLovePreferences.BILIBILI_BVID, "") ?: ""
        bilibiliAutoPlay = preferences.getBoolean(RainLovePreferences.BILIBILI_AUTO_PLAY, true)
        neteaseSongId = preferences.getString(RainLovePreferences.NETEASE_SONG_ID, "") ?: ""
        neteaseAutoPlay = preferences.getBoolean(RainLovePreferences.NETEASE_AUTO_PLAY, true)
        externalLink = preferences.getString(RainLovePreferences.EXTERNAL_LINK, "") ?: ""
        externalPackage = preferences.getString(RainLovePreferences.EXTERNAL_PACKAGE, "") ?: ""
        externalAutoPlay = preferences.getBoolean(RainLovePreferences.EXTERNAL_AUTO_PLAY, false)
        externalBackgroundDirect = if (preferences.contains(RainLovePreferences.EXTERNAL_BACKGROUND_DIRECT)) {
            preferences.getBoolean(RainLovePreferences.EXTERNAL_BACKGROUND_DIRECT, false)
        } else {
            preferences.getBoolean(RainLovePreferences.BILIBILI_BACKGROUND_DIRECT, false)
        }
        heartRateTransport = preferences.getString(RainLovePreferences.HEART_RATE_TRANSPORT, null)
            ?.let { runCatching { HeartRateTransport.valueOf(it) }.getOrNull() }
            ?: HeartRateTransport.BLE
    }

    override fun onHeartRate(bpm: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onHeartRate(bpm) }
            return
        }
        if (!isRunning || bpm !in 1..255) return
        val nowMs = SystemClock.elapsedRealtime()
        if (lastHistorySampleElapsedMs == 0L || nowMs - lastHistorySampleElapsedMs >= 1_000L) {
            lastHistorySampleElapsedMs = nowMs
            val timestampMs = System.currentTimeMillis()
            val source = heartRateTransport.name
            historyExecutor.execute {
                runCatching { history.record(timestampMs, bpm, source) }
                    .onFailure { Log.w("RainLoveHistory", "Failed to store heart rate sample", it) }
            }
        }
        processMonitorEvents(monitor.onSample(bpm, nowMs))
    }

    override fun onConnectionChanged(connected: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onConnectionChanged(connected) }
            return
        }
        if (connected || !isRunning) return
        processMonitorEvents(monitor.disconnect(SystemClock.elapsedRealtime()))
    }

    private fun processMonitorEvents(events: List<HeartRateMonitor.Event>) {
        events.forEach { event ->
            when (event) {
                is HeartRateMonitor.Event.SignalLost -> {
                    musicPlayer.pause()
                    val message = when (event.reason) {
                        HeartRateMonitor.LossReason.TIMEOUT -> "心率数据已过期：${HeartRateMonitor.STALE_AFTER_MS / 1_000} 秒未更新；已暂停本地音乐，等待新心率"
                        HeartRateMonitor.LossReason.DISCONNECTED -> "心率连接已断开；已暂停本地音乐，等待重新连接"
                    }
                    publishStatus(message, TriggerEventType.SIGNAL_LOST)
                }
                is HeartRateMonitor.Event.SignalAvailable -> publishStatus(
                    if (event.restored) "心率数据已恢复，重新计时" else "已收到有效心率，开始判断触发条件",
                    TriggerEventType.SIGNAL_AVAILABLE,
                )
                is HeartRateMonitor.Event.StateChanged -> recordEvent(
                    TriggerEventType.STATE_CHANGED,
                    "${TriggerProgress(event.before).label} → ${TriggerProgress(event.after).label}",
                )
                is HeartRateMonitor.Event.Playback -> handlePlaybackEvent(event.event)
            }
        }
        broadcastState()
        scheduleStateAdvance(SystemClock.elapsedRealtime())
    }

    private fun handlePlaybackEvent(event: HeartRateTriggerEngine.Event) {
        when (event) {
            HeartRateTriggerEngine.Event.StartPlayback -> {
                recordEvent(TriggerEventType.TRIGGER_REQUEST, "达到触发条件，执行${triggerTarget.label}")
                when (triggerTarget) {
                    TriggerTarget.LOCAL_MUSIC -> {
                        if (musicPlayer.play()) onStatus("达到触发条件，正在启动音乐…")
                        else onStatus("已触发，但尚未选择音乐")
                    }
                    TriggerTarget.BILIBILI_VIDEO -> notifyBilibiliTrigger()
                    TriggerTarget.NETEASE_MUSIC -> notifyNeteaseTrigger()
                    TriggerTarget.EXTERNAL_LINK -> notifyExternalLinkTrigger()
                }
            }
            HeartRateTriggerEngine.Event.StopPlayback -> {
                if (triggerTarget == TriggerTarget.LOCAL_MUSIC) musicPlayer.pause()
                onStatus("心率已恢复，进入冷却")
            }
        }
    }

    private fun scheduleStateAdvance(nowMs: Long) {
        mainHandler.removeCallbacks(stateAdvanceRunnable)
        monitor.nextCheckDelayMs(nowMs)?.let { delayMs ->
            mainHandler.postDelayed(stateAdvanceRunnable, delayMs)
        }
    }

    override fun onStatus(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onStatus(message) }
            return
        }
        publishStatus(message)
    }

    private fun publishStatus(message: String, type: TriggerEventType = TriggerEventType.STATUS) {
        if (!isRunning) return
        if (message != currentStatus || type != TriggerEventType.STATUS) recordEvent(type, message)
        currentStatus = message
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
        broadcastState(monitoring = true, status = message)
    }

    override fun onError(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onError(message) }
            return
        }
        if (isRunning) stopMonitoring(message)
    }

    private fun onMusicEvent(event: MusicPlayer.Event) {
        when (event) {
            MusicPlayer.Event.Started -> {
                localMusicWasPlaying = true
                publishStatus("本地音乐已开始播放", TriggerEventType.PLAYBACK_STARTED)
            }
            MusicPlayer.Event.Stopped -> {
                if (localMusicWasPlaying) recordEvent(TriggerEventType.PLAYBACK_STOPPED, "本地音乐已暂停或结束")
                localMusicWasPlaying = false
            }
            is MusicPlayer.Event.Error -> {
                localMusicWasPlaying = false
                publishStatus("音乐播放失败：${event.detail}，请重新选择文件", TriggerEventType.PLAYBACK_ERROR)
            }
        }
    }

    private fun recordEvent(type: TriggerEventType, message: String) {
        eventLog.record(TriggerEventRecord(
            System.currentTimeMillis(), heartRateTransport.name, type, message,
            if (type == TriggerEventType.SIGNAL_LOST) null else monitor.bpm,
        ))
    }

    private fun rememberConnectedDevice(device: BleHeartRateDevice) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { rememberConnectedDevice(device) }
            return
        }
        getSharedPreferences(RainLovePreferences.NAME, MODE_PRIVATE).edit()
            .putString(RainLovePreferences.DEVICE_ADDRESS, device.address)
            .putString(RainLovePreferences.DEVICE_NAME, device.name)
            .apply()
        broadcastState(device = device)
    }

    private fun stopMonitoring(status: String) {
        if (isRunning) recordEvent(TriggerEventType.SESSION_STOPPED, status)
        isRunning = false
        currentStatus = status
        getSharedPreferences(RainLovePreferences.NAME, MODE_PRIVATE).edit()
            .putBoolean(RainLovePreferences.MONITORING_DESIRED, false)
            .commit()
        mainHandler.removeCallbacks(stateAdvanceRunnable)
        val source = heartRateSource
        heartRateSource = null
        source?.stop()
        musicPlayer.pause()
        monitor.reset(SystemClock.elapsedRealtime())
        broadcastState(monitoring = false, status = status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        val source = heartRateSource
        heartRateSource = null
        source?.stop()
        musicPlayer.release()
        historyExecutor.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "心率监测",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "rainy love 后台心率连接状态" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val triggerChannel = NotificationChannel(
            TRIGGER_NOTIFICATION_CHANNEL_ID,
            "心动触发提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "心率达到条件后的播放提醒" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(triggerChannel)
    }

    private fun notifyBilibiliTrigger() {
        val bvid = BilibiliVideo.normalizeBvid(bilibiliBvid)
        val uri = BilibiliVideo.urlFor(bilibiliBvid)
        if (bvid == null || uri == null) {
            onStatus("已触发，但 BV 号无效")
            return
        }
        val canOpenDirectly = canOpenExternalDirectly()
        if (canOpenDirectly && BilibiliVideo.open(this, bvid, bilibiliAutoPlay)) {
            getSystemService(NotificationManager::class.java).cancel(TRIGGER_NOTIFICATION_ID)
            onStatus("达到触发条件，正在打开 B 站视频…")
            return
        }
        val openVideo = PendingIntent.getActivity(
            this,
            2,
            Intent(this, BilibiliLaunchActivity::class.java).apply {
                putExtra(BilibiliLaunchActivity.EXTRA_BVID, bvid)
                putExtra(BilibiliLaunchActivity.EXTRA_AUTO_PLAY, bilibiliAutoPlay)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, TRIGGER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("心率已达到触发条件")
            .setContentText("点击打开哔哩哔哩视频 $bvid")
            .setContentIntent(openVideo)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_media_play, "打开视频", openVideo)
            .build()
        getSystemService(NotificationManager::class.java).notify(TRIGGER_NOTIFICATION_ID, notification)
        onStatus("达到触发条件；应用在后台，请点击通知打开 B 站视频")
    }

    private fun notifyNeteaseTrigger() {
        val songId = NeteaseMusic.normalizeSongId(neteaseSongId)
        if (songId == null) {
            onStatus("已触发，但网易云歌曲 ID 无效")
            return
        }
        if (canOpenExternalDirectly() && NeteaseMusic.open(this, songId, neteaseAutoPlay)) {
            getSystemService(NotificationManager::class.java).cancel(TRIGGER_NOTIFICATION_ID)
            onStatus("达到触发条件，正在打开网易云歌曲…")
            return
        }
        val openSong = PendingIntent.getActivity(
            this,
            3,
            Intent(this, NeteaseLaunchActivity::class.java).apply {
                putExtra(NeteaseLaunchActivity.EXTRA_SONG_ID, songId)
                putExtra(NeteaseLaunchActivity.EXTRA_AUTO_PLAY, neteaseAutoPlay)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, TRIGGER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("心率已达到触发条件")
            .setContentText("点击打开网易云歌曲 $songId")
            .setContentIntent(openSong)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_media_play, "打开歌曲", openSong)
            .build()
        getSystemService(NotificationManager::class.java).notify(TRIGGER_NOTIFICATION_ID, notification)
        onStatus("达到触发条件；应用在后台，请点击通知打开网易云歌曲")
    }

    private fun canOpenExternalDirectly(): Boolean = isAppVisible ||
        (externalBackgroundDirect && Settings.canDrawOverlays(this))

    private fun notifyExternalLinkTrigger() {
        val url = ExternalLink.normalizeUrl(externalLink)
        val packageName = ExternalLink.normalizePackage(externalPackage)
        if (url == null || packageName == null) {
            onStatus("已触发，但外部媒体链接或包名无效")
            return
        }
        if (canOpenExternalDirectly() && ExternalLink.open(this, url, packageName, externalAutoPlay)) {
            getSystemService(NotificationManager::class.java).cancel(TRIGGER_NOTIFICATION_ID)
            onStatus("达到触发条件，正在打开外部媒体…")
            return
        }
        val openLink = PendingIntent.getActivity(
            this,
            4,
            Intent(this, ExternalLinkLaunchActivity::class.java).apply {
                putExtra(ExternalLinkLaunchActivity.EXTRA_URL, url)
                putExtra(ExternalLinkLaunchActivity.EXTRA_PACKAGE, packageName)
                putExtra(ExternalLinkLaunchActivity.EXTRA_AUTO_PLAY, externalAutoPlay)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, TRIGGER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("心率已达到触发条件")
            .setContentText("点击打开指定媒体")
            .setContentIntent(openLink)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_media_play, "打开媒体", openLink)
            .build()
        getSystemService(NotificationManager::class.java).notify(TRIGGER_NOTIFICATION_ID, notification)
        onStatus("达到触发条件；应用在后台，请点击通知打开外部媒体")
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, HeartRateForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("rainy love 正在监测心率")
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .build()
    }

    private fun broadcastState(
        monitoring: Boolean? = null,
        status: String? = null,
        device: BleHeartRateDevice? = null,
    ) {
        currentProgress = monitor.progress(SystemClock.elapsedRealtime())
        currentBpm = monitor.bpm
        currentSignal = monitor.signal
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            monitoring?.let { putExtra(EXTRA_MONITORING, it) }
            status?.let { putExtra(EXTRA_STATUS, it) }
            putExtra(EXTRA_BPM, currentBpm ?: 0)
            putExtra(EXTRA_SIGNAL, currentSignal.name)
            putExtra(EXTRA_TRIGGER_STATE, currentProgress.state.name)
            putExtra(EXTRA_TRIGGER_DEADLINE, currentProgress.deadlineMs ?: -1L)
            device?.let {
                putExtra(EXTRA_DEVICE_ADDRESS, it.address)
                putExtra(EXTRA_DEVICE_NAME, it.name)
            }
        })
    }

    companion object {
        @Volatile
        var currentSignal: HeartRateMonitor.Signal = HeartRateMonitor.Signal.WAITING
            private set

        @Volatile
        var currentProgress: TriggerProgress = TriggerProgress()
            private set

        @Volatile
        var currentBpm: Int? = null
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentStatus: String = "等待启动"
            private set

        @Volatile
        private var isAppVisible: Boolean = false

        fun setAppVisible(visible: Boolean) {
            isAppVisible = visible
        }

        const val ACTION_START = "com.rainlove.app.action.START_MONITORING"
        const val ACTION_STOP = "com.rainlove.app.action.STOP_MONITORING"
        const val ACTION_STATE = "com.rainlove.app.action.MONITORING_STATE"
        const val EXTRA_MONITORING = "monitoring"
        const val EXTRA_STATUS = "status"
        const val EXTRA_BPM = "bpm"
        const val EXTRA_SIGNAL = "signal"
        const val EXTRA_TRIGGER_STATE = "trigger_state"
        const val EXTRA_TRIGGER_DEADLINE = "trigger_deadline"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val NOTIFICATION_CHANNEL_ID = "heart_rate_monitoring"
        private const val TRIGGER_NOTIFICATION_CHANNEL_ID = "heart_rate_trigger"
        private const val NOTIFICATION_ID = 1001
        private const val TRIGGER_NOTIFICATION_ID = 1002
    }
}
