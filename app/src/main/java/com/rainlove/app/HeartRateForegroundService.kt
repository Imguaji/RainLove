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
import com.rainlove.app.trigger.TriggerConfig
import com.rainlove.app.trigger.TriggerProgress
import java.util.concurrent.Executors

class HeartRateForegroundService : Service(), HeartRateSource.Listener {
    private lateinit var musicPlayer: MusicPlayer
    private lateinit var history: HeartRateHistory
    private val historyExecutor = Executors.newSingleThreadExecutor()
    private var lastHistorySampleElapsedMs = 0L
    private var heartRateSource: HeartRateSource? = null
    private var heartRateTransport = HeartRateTransport.BLE
    private var engine = HeartRateTriggerEngine()
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
    private var lastHeartRateBpm: Int? = null
    private val stateAdvanceRunnable = Runnable { lastHeartRateBpm?.let(::processHeartRate) }

    override fun onCreate() {
        super.onCreate()
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
        lastHeartRateBpm = null
        lastHistorySampleElapsedMs = 0L
        currentBpm = null
        isRunning = true
        currentStatus = "正在启动心率监测…"
        startForeground(NOTIFICATION_ID, buildNotification("正在启动心率监测…"))
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
        }.also { it.start(this) }
    }

    private fun loadSessionSettings() {
        val preferences = getSharedPreferences(RainLovePreferences.NAME, MODE_PRIVATE)
        val triggerBpm = preferences.getInt(RainLovePreferences.TRIGGER_BPM, 120).coerceIn(80, 200)
        engine = HeartRateTriggerEngine(
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
        if (!isRunning) return
        lastHeartRateBpm = bpm
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
        processHeartRate(bpm)
    }

    override fun onConnectionChanged(connected: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onConnectionChanged(connected) }
            return
        }
        if (connected || !isRunning) return
        lastHeartRateBpm = null
        mainHandler.removeCallbacks(stateAdvanceRunnable)
        engine.reset(SystemClock.elapsedRealtime())
        broadcastState()
    }

    private fun processHeartRate(bpm: Int) {
        val nowMs = SystemClock.elapsedRealtime()
        when (engine.onHeartRate(bpm, nowMs)) {
            HeartRateTriggerEngine.Event.StartPlayback -> {
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
            null -> Unit
        }
        broadcastState(bpm = bpm)
        scheduleStateAdvance(nowMs)
    }

    private fun scheduleStateAdvance(nowMs: Long) {
        mainHandler.removeCallbacks(stateAdvanceRunnable)
        engine.nextTransitionDelayMs(nowMs)?.let { delayMs ->
            mainHandler.postDelayed(stateAdvanceRunnable, delayMs)
        }
    }

    override fun onStatus(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onStatus(message) }
            return
        }
        if (!isRunning) return
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
            MusicPlayer.Event.Started -> onStatus("本地音乐已开始播放")
            MusicPlayer.Event.Stopped -> Unit
            is MusicPlayer.Event.Error -> onStatus(
                "音乐播放失败：${event.detail}，请重新选择文件"
            )
        }
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
        isRunning = false
        currentStatus = status
        getSharedPreferences(RainLovePreferences.NAME, MODE_PRIVATE).edit()
            .putBoolean(RainLovePreferences.MONITORING_DESIRED, false)
            .commit()
        lastHeartRateBpm = null
        mainHandler.removeCallbacks(stateAdvanceRunnable)
        val source = heartRateSource
        heartRateSource = null
        source?.stop()
        musicPlayer.pause()
        engine.reset(SystemClock.elapsedRealtime())
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
        bpm: Int? = null,
        device: BleHeartRateDevice? = null,
    ) {
        currentProgress = engine.progress(SystemClock.elapsedRealtime())
        bpm?.let { currentBpm = it }
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            monitoring?.let { putExtra(EXTRA_MONITORING, it) }
            status?.let { putExtra(EXTRA_STATUS, it) }
            bpm?.let { putExtra(EXTRA_BPM, it) }
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
