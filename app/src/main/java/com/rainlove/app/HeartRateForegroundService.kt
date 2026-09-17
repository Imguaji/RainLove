package com.rainlove.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.rainlove.app.media.MusicPlayer
import com.rainlove.app.media.BilibiliVideo
import com.rainlove.app.media.TriggerTarget
import com.rainlove.app.sensor.BleHeartRateDevice
import com.rainlove.app.sensor.BleHeartRateSource
import com.rainlove.app.sensor.HeartRateSource
import com.rainlove.app.trigger.HeartRateTriggerEngine
import com.rainlove.app.trigger.TriggerConfig

class HeartRateForegroundService : Service(), HeartRateSource.Listener {
    private lateinit var musicPlayer: MusicPlayer
    private var heartRateSource: BleHeartRateSource? = null
    private var engine = HeartRateTriggerEngine()
    private var triggerTarget = TriggerTarget.LOCAL_MUSIC
    private var bilibiliBvid = ""

    override fun onCreate() {
        super.onCreate()
        musicPlayer = MusicPlayer(this)
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
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            startForeground(NOTIFICATION_ID, buildNotification("蓝牙不可用"))
            onError("蓝牙不可用")
            return
        }

        loadSessionSettings()
        isRunning = true
        currentStatus = "正在启动心率监测…"
        startForeground(NOTIFICATION_ID, buildNotification("正在启动心率监测…"))
        broadcastState(monitoring = true, status = "准备扫描")
        val preferences = getSharedPreferences(RainLovePreferences.NAME, MODE_PRIVATE)
        heartRateSource = BleHeartRateSource(
            context = this,
            scanner = scanner,
            targetAddress = preferences.getString(RainLovePreferences.DEVICE_ADDRESS, null),
            onConnectedDevice = ::rememberConnectedDevice,
        ).also { it.start(this) }
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
    }

    override fun onHeartRate(bpm: Int) {
        if (!isRunning) return
        when (engine.onHeartRate(bpm, SystemClock.elapsedRealtime())) {
            HeartRateTriggerEngine.Event.StartPlayback -> {
                when (triggerTarget) {
                    TriggerTarget.LOCAL_MUSIC -> {
                        if (musicPlayer.play()) onStatus("达到触发条件，正在播放")
                        else onStatus("已触发，但尚未选择音乐")
                    }
                    TriggerTarget.BILIBILI_VIDEO -> notifyBilibiliTrigger()
                }
            }
            HeartRateTriggerEngine.Event.StopPlayback -> {
                if (triggerTarget == TriggerTarget.LOCAL_MUSIC) musicPlayer.pause()
                onStatus("心率已恢复，进入冷却")
            }
            null -> Unit
        }
        broadcastState(bpm = bpm, triggerState = engine.state)
    }

    override fun onStatus(message: String) {
        if (!isRunning) return
        currentStatus = message
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
        broadcastState(monitoring = true, status = message, triggerState = engine.state)
    }

    override fun onError(message: String) {
        if (isRunning) stopMonitoring(message)
    }

    private fun rememberConnectedDevice(device: BleHeartRateDevice) {
        getSharedPreferences(RainLovePreferences.NAME, MODE_PRIVATE).edit()
            .putString(RainLovePreferences.DEVICE_ADDRESS, device.address)
            .putString(RainLovePreferences.DEVICE_NAME, device.name)
            .apply()
        broadcastState(device = device)
    }

    private fun stopMonitoring(status: String) {
        isRunning = false
        currentStatus = status
        val source = heartRateSource
        heartRateSource = null
        source?.stop()
        musicPlayer.pause()
        engine.reset(SystemClock.elapsedRealtime())
        broadcastState(monitoring = false, status = status, triggerState = engine.state)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        val source = heartRateSource
        heartRateSource = null
        source?.stop()
        musicPlayer.release()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "心率监测",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "RainLove 后台心率连接状态" }
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
        val openVideo = PendingIntent.getActivity(
            this,
            2,
            Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
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
        onStatus("达到触发条件，请点击通知打开 B 站视频")
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
            .setContentTitle("RainLove 正在监测心率")
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
        triggerState: HeartRateTriggerEngine.State? = null,
        device: BleHeartRateDevice? = null,
    ) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            monitoring?.let { putExtra(EXTRA_MONITORING, it) }
            status?.let { putExtra(EXTRA_STATUS, it) }
            bpm?.let { putExtra(EXTRA_BPM, it) }
            triggerState?.let { putExtra(EXTRA_TRIGGER_STATE, it.name) }
            device?.let {
                putExtra(EXTRA_DEVICE_ADDRESS, it.address)
                putExtra(EXTRA_DEVICE_NAME, it.name)
            }
        })
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentStatus: String = "等待启动"
            private set

        const val ACTION_START = "com.rainlove.app.action.START_MONITORING"
        const val ACTION_STOP = "com.rainlove.app.action.STOP_MONITORING"
        const val ACTION_STATE = "com.rainlove.app.action.MONITORING_STATE"
        const val EXTRA_MONITORING = "monitoring"
        const val EXTRA_STATUS = "status"
        const val EXTRA_BPM = "bpm"
        const val EXTRA_TRIGGER_STATE = "trigger_state"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val NOTIFICATION_CHANNEL_ID = "heart_rate_monitoring"
        private const val TRIGGER_NOTIFICATION_CHANNEL_ID = "heart_rate_trigger"
        private const val NOTIFICATION_ID = 1001
        private const val TRIGGER_NOTIFICATION_ID = 1002
    }
}
