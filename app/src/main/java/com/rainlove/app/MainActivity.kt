package com.rainlove.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.rainlove.app.media.BilibiliVideo
import com.rainlove.app.history.HeartRateRecord
import com.rainlove.app.history.TriggerEventLog
import com.rainlove.app.media.NeteaseMusic
import com.rainlove.app.media.ExternalLink
import com.rainlove.app.media.TriggerTarget
import com.rainlove.app.profiles.TriggerProfileStore
import com.rainlove.app.sensor.HeartRateTransport
import com.rainlove.app.trigger.HeartRateTriggerEngine
import com.rainlove.app.trigger.HeartRateMonitor
import com.rainlove.app.trigger.TriggerProgress
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val viewModel: RainLoveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RainLoveScreen(viewModel, lifecycle) } }
    }

    override fun onStart() {
        super.onStart()
        HeartRateForegroundService.setAppVisible(true)
        viewModel.reconcileExternalBackgroundDirectPermission(Settings.canDrawOverlays(this))
    }

    override fun onStop() {
        HeartRateForegroundService.setAppVisible(false)
        super.onStop()
    }
}

@Composable
private fun RainLoveScreen(vm: RainLoveViewModel, lifecycle: Lifecycle) {
    val state by vm.ui.collectAsState()
    var countdownNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.triggerDeadlineMs, state.monitoring, lifecycle) {
        if (!state.monitoring) return@LaunchedEffect
        val deadline = state.triggerDeadlineMs ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                countdownNowMs = SystemClock.elapsedRealtime()
                if (countdownNowMs >= deadline) break
                delay(250L)
            } while (true)
        }
    }
    val progress = TriggerProgress(state.triggerState, state.triggerDeadlineMs)
    val remainingSeconds = progress.remainingSeconds(countdownNowMs)
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingBluetoothAction by remember { mutableStateOf(BluetoothAction.NONE) }
    var showHistory by remember { mutableStateOf(false) }
    var pendingClearHistory by remember { mutableStateOf(false) }
    var showEventLog by remember { mutableStateOf(false) }
    var pendingClearEvents by remember { mutableStateOf(false) }
    val eventTimeFormat = remember {
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }
    var profileName by remember { mutableStateOf("") }
    var pendingDeleteProfile by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when (pendingBluetoothAction) {
            BluetoothAction.SCAN -> if (missingBluetoothPermissions(context).isEmpty()) vm.scanForDevices()
            BluetoothAction.START -> if (missingBluetoothPermissions(context).isEmpty()) vm.toggleMonitoring()
            BluetoothAction.NONE -> Unit
        }
        pendingBluetoothAction = BluetoothAction.NONE
    }
    val musicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        vm.selectMusic(uri)
    }
    val historyExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let(vm::exportHistory)
    }
    val eventExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportEventLog)
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.setExternalBackgroundDirect(Settings.canDrawOverlays(context))
    }

    pendingDeleteProfile?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProfile = null },
            title = { Text("删除触发方案？") },
            text = { Text("将永久删除“$name”方案；当前设置不会改变。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProfile(name)
                    if (profileName == name) profileName = ""
                    pendingDeleteProfile = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProfile = null }) { Text("取消") }
            },
        )
    }
    if (pendingClearHistory) {
        AlertDialog(
            onDismissRequest = { pendingClearHistory = false },
            title = { Text("清空心率历史？") },
            text = { Text("将永久删除 rainy love 保存的全部本地心率记录。此前导出的 CSV 文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    pendingClearHistory = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearHistory = false }) { Text("取消") }
            },
        )
    }

    if (showEventLog && !pendingClearEvents) {
        AlertDialog(
            onDismissRequest = { showEventLog = false },
            title = { Text("触发事件日志") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本机保留最近 ${TriggerEventLog.MAX_EVENTS} 条，显示最新 100 条。")
                    Text(state.eventLogStatus)
                    Row {
                        TextButton(onClick = vm::refreshEventLog, enabled = !state.eventLogBusy) { Text("刷新") }
                        TextButton(
                            onClick = { eventExportLauncher.launch("rainy-love-events.csv") },
                            enabled = !state.eventLogBusy,
                        ) { Text("导出 CSV") }
                    }
                    LazyColumn(Modifier.fillMaxWidth().height(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.eventRecords.isEmpty()) item { Text("暂无事件，开始监测后会自动记录。") }
                        items(state.eventRecords) { event ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${eventTimeFormat.format(Instant.ofEpochMilli(event.timestampMs))} · ${event.source}")
                                Text(event.type.label, style = MaterialTheme.typography.titleSmall)
                                Text(event.message)
                                event.bpm?.let { Text("$it BPM", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEventLog = false }) { Text("关闭") } },
            dismissButton = {
                TextButton(
                    onClick = { pendingClearEvents = true },
                    enabled = !state.monitoring && !state.musicPlaying && !state.eventLogBusy,
                ) { Text("清空日志") }
            },
        )
    }
    if (pendingClearEvents) {
        AlertDialog(
            onDismissRequest = { pendingClearEvents = false },
            title = { Text("清空事件日志？") },
            text = { Text("将永久删除本机触发事件日志；心率历史和已导出的文件保留。") },
            confirmButton = { TextButton(onClick = {
                vm.clearEventLog()
                pendingClearEvents = false
            }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { pendingClearEvents = false }) { Text("取消") } },
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("rainy love", style = MaterialTheme.typography.headlineMedium)
            val hasCurrentReading = state.demoMode || state.signal == HeartRateMonitor.Signal.LIVE
            Text(if (hasCurrentReading) "${state.bpm}" else "—", fontSize = 72.sp)
            val triggerLabel = when {
                !state.monitoring -> "未监测"
                !hasCurrentReading -> "等待新心率"
                else -> progress.label
            }
            Text("BPM · $triggerLabel")
            if (state.monitoring && !hasCurrentReading) {
                Text(if (state.signal == HeartRateMonitor.Signal.STALE) {
                    "心率已超过 ${HeartRateMonitor.STALE_AFTER_MS / 1_000} 秒未更新；收到新数据后重新计时。"
                } else "等待设备发送有效心率。")
            }
            if (state.monitoring && hasCurrentReading) {
                val waitText = when (state.triggerState) {
                    HeartRateTriggerEngine.State.ARMED -> "心率达到 ${state.triggerBpm} BPM 并保持 ${state.triggerSeconds} 秒后触发"
                    HeartRateTriggerEngine.State.HIGH_PENDING -> "继续保持 ≥ ${state.triggerBpm} BPM，还需 ${remainingSeconds ?: "…"} 秒；低于阈值会重新计时"
                    HeartRateTriggerEngine.State.PLAYING -> "触发条件已满足；实际播放或打开结果见下方状态"
                    HeartRateTriggerEngine.State.RECOVERY_PENDING -> "继续保持 ≤ ${state.recoveryBpm} BPM，还需 ${remainingSeconds ?: "…"} 秒后进入冷却"
                    HeartRateTriggerEngine.State.COOLDOWN -> "还需 ${remainingSeconds ?: "…"} 秒重新允许触发"
                }
                Text(waitText)
            }
            Text(state.status)
            TextButton(onClick = {
                showEventLog = true
                vm.refreshEventLog()
            }) { Text("查看触发事件日志") }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Demo 模拟心率")
                Switch(checked = state.demoMode, onCheckedChange = vm::setDemoMode, enabled = !state.monitoring)
            }
            if (state.demoMode) {
                Slider(
                    value = state.bpm.toFloat(),
                    onValueChange = { vm.setDemoBpm(it.toInt()) },
                    valueRange = 40f..200f,
                )
            } else {
                Text("心率连接方式")
                TriggerTargetOption(
                    label = "Bluetooth LE",
                    selected = state.heartRateTransport == HeartRateTransport.BLE,
                    enabled = !state.monitoring,
                    onClick = { vm.setHeartRateTransport(HeartRateTransport.BLE) },
                )
                TriggerTargetOption(
                    label = "ANT+",
                    selected = state.heartRateTransport == HeartRateTransport.ANT_PLUS,
                    enabled = !state.monitoring,
                    onClick = { vm.setHeartRateTransport(HeartRateTransport.ANT_PLUS) },
                )
                if (state.heartRateTransport == HeartRateTransport.BLE) {
                    Text("心率设备：${state.selectedDeviceName ?: "自动选择"}")
                    Button(onClick = {
                        val permissions = missingBluetoothPermissions(context)
                        if (permissions.isEmpty()) vm.scanForDevices()
                        else {
                            pendingBluetoothAction = BluetoothAction.SCAN
                            permissionLauncher.launch(permissions.toTypedArray())
                        }
                    }, enabled = !state.monitoring) {
                        Text(if (state.scanningDevices) "正在扫描…" else "扫描心率设备")
                    }
                    if (state.selectedDeviceAddress != null) {
                        Button(onClick = { vm.selectDevice(null) }, enabled = !state.monitoring) { Text("改为自动选择") }
                    }
                    state.availableDevices.forEach { device ->
                        Button(onClick = { vm.selectDevice(device) }, enabled = !state.monitoring) {
                            Text("${device.name} · ${device.address}")
                        }
                    }
                } else {
                    val radioInstalled = isPackageInstalled(context, ANT_RADIO_SERVICE_PACKAGE)
                    val pluginsInstalled = isPackageInstalled(context, ANT_PLUS_PLUGINS_PACKAGE)
                    Text("ANT Radio Service：${if (radioInstalled) "已安装" else "未安装"}")
                    Text("ANT+ Plugins Service：${if (pluginsInstalled) "已安装" else "未安装"}")
                    Text("将自动连接第一个可用的 ANT+ 心率设备；手机需具备 ANT+ 硬件或兼容的 ANT USB 适配器。")
                    if (!radioInstalled) {
                        Button(
                            onClick = { openStore(context, ANT_RADIO_SERVICE_PACKAGE) },
                            enabled = !state.monitoring,
                        ) { Text("安装 ANT Radio Service") }
                    }
                    if (!pluginsInstalled) {
                        Button(
                            onClick = { openStore(context, ANT_PLUS_PLUGINS_PACKAGE) },
                            enabled = !state.monitoring,
                        ) { Text("安装 ANT+ Plugins Service") }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("开机自动恢复监测")
                    Switch(checked = state.resumeOnBoot, onCheckedChange = vm::setResumeOnBoot)
                }
                Text("仅在关机前正在监测、且所需权限仍有效时自动恢复；手动停止后不会自动启动。")
            }

            SettingSlider("触发心率", state.triggerBpm, 80..200, vm::setTriggerBpm, enabled = !state.monitoring)
            SettingSlider("恢复心率", state.recoveryBpm, 50..180, vm::setRecoveryBpm, enabled = !state.monitoring)
            SettingSlider("触发保持", state.triggerSeconds, 1..30, vm::setTriggerSeconds, unit = "秒", enabled = !state.monitoring)
            SettingSlider("恢复保持", state.recoverySeconds, 1..30, vm::setRecoverySeconds, unit = "秒", enabled = !state.monitoring)
            SettingSlider("冷却时间", state.cooldownSeconds, 0..300, vm::setCooldownSeconds, unit = "秒", enabled = !state.monitoring)

            Text("触发后执行")
            TriggerTargetOption(
                label = "播放本地音乐",
                selected = state.triggerTarget == TriggerTarget.LOCAL_MUSIC,
                enabled = !state.monitoring,
                onClick = { vm.setTriggerTarget(TriggerTarget.LOCAL_MUSIC) },
            )
            TriggerTargetOption(
                label = "打开哔哩哔哩视频",
                selected = state.triggerTarget == TriggerTarget.BILIBILI_VIDEO,
                enabled = !state.monitoring,
                onClick = { vm.setTriggerTarget(TriggerTarget.BILIBILI_VIDEO) },
            )
            TriggerTargetOption(
                label = "打开网易云指定歌曲",
                selected = state.triggerTarget == TriggerTarget.NETEASE_MUSIC,
                enabled = !state.monitoring,
                onClick = { vm.setTriggerTarget(TriggerTarget.NETEASE_MUSIC) },
            )
            TriggerTargetOption(
                label = "打开其他音乐 App 链接",
                selected = state.triggerTarget == TriggerTarget.EXTERNAL_LINK,
                enabled = !state.monitoring,
                onClick = { vm.setTriggerTarget(TriggerTarget.EXTERNAL_LINK) },
            )
            when (state.triggerTarget) {
                TriggerTarget.LOCAL_MUSIC -> {
                    Text("音乐：${state.musicName}")
                    Button(
                        onClick = { musicLauncher.launch(arrayOf("audio/*")) },
                        enabled = !state.monitoring,
                    ) { Text("选择本地音乐") }
                    Button(
                        onClick = vm::toggleMusicPreview,
                        enabled = !state.monitoring && state.musicName != "尚未选择音乐",
                    ) { Text(if (state.musicPlaying) "停止测试播放" else "测试播放本地音乐") }
                }
                TriggerTarget.BILIBILI_VIDEO -> {
                    OutlinedTextField(
                        value = state.bilibiliBvid,
                        onValueChange = vm::setBilibiliBvid,
                        label = { Text("BV 号或完整视频链接") },
                        singleLine = true,
                        enabled = !state.monitoring,
                        isError = state.bilibiliBvid.isNotBlank() &&
                            BilibiliVideo.normalizeBvid(state.bilibiliBvid) == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("打开后尝试自动播放")
                        Switch(
                            checked = state.bilibiliAutoPlay,
                            onCheckedChange = vm::setBilibiliAutoPlay,
                            enabled = !state.monitoring,
                        )
                    }
                    Button(
                        onClick = vm::testBilibiliVideo,
                        enabled = !state.monitoring && BilibiliVideo.normalizeBvid(state.bilibiliBvid) != null,
                    ) { Text("测试打开视频") }
                }
                TriggerTarget.NETEASE_MUSIC -> {
                    OutlinedTextField(
                        value = state.neteaseSongId,
                        onValueChange = vm::setNeteaseSongId,
                        label = { Text("网易云歌曲 ID 或分享链接") },
                        singleLine = true,
                        enabled = !state.monitoring,
                        isError = state.neteaseSongId.isNotBlank() &&
                            NeteaseMusic.normalizeSongId(state.neteaseSongId) == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("打开后尝试自动播放")
                        Switch(
                            checked = state.neteaseAutoPlay,
                            onCheckedChange = vm::setNeteaseAutoPlay,
                            enabled = !state.monitoring,
                        )
                    }
                    Text("优先打开网易云音乐 App；未安装时回退到歌曲网页。")
                    Button(
                        onClick = vm::testNeteaseSong,
                        enabled = !state.monitoring &&
                            NeteaseMusic.normalizeSongId(state.neteaseSongId) != null,
                    ) { Text("测试打开歌曲") }
                }
                TriggerTarget.EXTERNAL_LINK -> {
                    OutlinedTextField(
                        value = state.externalLink,
                        onValueChange = vm::setExternalLink,
                        label = { Text("歌曲/内容链接或 App 深链") },
                        singleLine = true,
                        enabled = !state.monitoring,
                        isError = state.externalLink.isNotBlank() &&
                            ExternalLink.normalizeUrl(state.externalLink) == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.externalPackage,
                        onValueChange = vm::setExternalPackage,
                        label = { Text("目标 App 包名（可留空）") },
                        singleLine = true,
                        enabled = !state.monitoring,
                        isError = ExternalLink.normalizePackage(state.externalPackage) == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("指定包名可限定由该 App 打开；留空时由 Android 选择。目标 App 必须支持此链接，无法保证自动播放。")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("打开后尝试自动播放")
                        Switch(
                            checked = state.externalAutoPlay,
                            onCheckedChange = vm::setExternalAutoPlay,
                            enabled = !state.monitoring && state.externalPackage.isNotBlank(),
                        )
                    }
                    Button(
                        onClick = vm::testExternalLink,
                        enabled = !state.monitoring &&
                            ExternalLink.normalizeUrl(state.externalLink) != null &&
                            ExternalLink.normalizePackage(state.externalPackage) != null,
                    ) { Text("测试打开链接") }
                }
            }
            if (state.triggerTarget != TriggerTarget.LOCAL_MUSIC) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("后台直接打开")
                    Switch(
                        checked = state.externalBackgroundDirect,
                        onCheckedChange = { enabled ->
                            when {
                                !enabled -> vm.setExternalBackgroundDirect(false)
                                Settings.canDrawOverlays(context) -> vm.setExternalBackgroundDirect(true)
                                else -> overlayPermissionLauncher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }
                        },
                        enabled = !state.monitoring,
                    )
                }
                Text("开启后需授予“显示在其他应用上层”权限；rainy love 不会显示悬浮窗，只用该权限请求后台直接跳转。")
                Text(
                    if (state.externalBackgroundDirect) {
                        "rainy love 在前台或后台触发时都会尝试直接打开目标；权限失效时自动降级为通知。"
                    } else {
                        "rainy love 在前台时直接打开目标；后台受 Android 限制，需点击通知。"
                    }
                )
            }
            Button(onClick = {
                val permissions = missingMonitoringPermissions(context, state.heartRateTransport)
                if (!state.demoMode && permissions.isNotEmpty()) {
                    pendingBluetoothAction = BluetoothAction.START
                    permissionLauncher.launch(permissions.toTypedArray())
                } else vm.toggleMonitoring()
            }) {
                Text(if (state.monitoring) "停止心动模式" else "开启心动模式")
            }
            Button(onClick = {
                showHistory = !showHistory
                if (showHistory) vm.refreshHistory()
            }) {
                Text(if (showHistory) "收起心率历史" else "查看心率历史")
            }
            if (showHistory) {
                Text("最近 ${state.historyRecords.size} 个采样点（最多显示 300 个）")
                HeartRateHistoryChart(state.historyRecords)
                Button(onClick = vm::refreshHistory) { Text("刷新历史") }
                Button(onClick = { historyExportLauncher.launch("rainy-love-heart-rate.csv") }) {
                    Text("导出全部心率记录 CSV")
                }
                TextButton(onClick = { pendingClearHistory = true }, enabled = !state.monitoring) {
                    Text("清空本地心率历史")
                }
            }
            Text("触发方案")
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text("方案名称，如骑行或日常") },
                singleLine = true,
                enabled = !state.monitoring,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.saveProfile(profileName) },
                enabled = !state.monitoring && TriggerProfileStore.normalizeName(profileName) != null,
            ) { Text("保存/覆盖当前方案") }
            state.profileNames.forEach { name ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.loadProfile(name)
                            profileName = name
                        },
                        enabled = !state.monitoring,
                        modifier = Modifier.weight(1f),
                    ) { Text("切换到 $name") }
                    TextButton(
                        onClick = { pendingDeleteProfile = name },
                        enabled = !state.monitoring,
                    ) { Text("删除") }
                }
            }
            Text("娱乐项目，不用于诊断或监测疾病。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HeartRateHistoryChart(records: List<HeartRateRecord>) {
    if (records.size < 2) {
        Text("暂无足够的历史心率数据")
        return
    }
    val minBpm = records.minOf { it.bpm } - 5
    val maxBpm = records.maxOf { it.bpm } + 5
    Text("${minBpm + 5}–${maxBpm - 5} BPM")
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val firstTime = records.first().timestampMs
        val timeSpan = (records.last().timestampMs - firstTime).coerceAtLeast(1L).toFloat()
        val bpmSpan = (maxBpm - minBpm).coerceAtLeast(1).toFloat()
        val path = Path()
        records.forEachIndexed { index, record ->
            val x = (record.timestampMs - firstTime) / timeSpan * size.width
            val y = size.height - (record.bpm - minBpm) / bpmSpan * size.height
            if (index == 0 || record.timestampMs - records[index - 1].timestampMs > 120_000L) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path, color = Color(0xFFE91E63), style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun TriggerTargetOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    unit: String = "BPM",
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("$label：$value $unit")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
        )
    }
}

private fun requiredBluetoothPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun missingBluetoothPermissions(context: android.content.Context) = requiredBluetoothPermissions().filter {
    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
}

private fun missingMonitoringPermissions(
    context: android.content.Context,
    transport: HeartRateTransport,
): List<String> {
    val permissions = if (transport == HeartRateTransport.BLE) {
        requiredBluetoothPermissions().toMutableList()
    } else {
        if (Build.VERSION.SDK_INT >= 31) {
            mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            mutableListOf()
        }
    }
    if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
    return permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
}

private fun isPackageInstalled(context: android.content.Context, packageName: String): Boolean =
    runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

private fun openStore(context: android.content.Context, packageName: String) {
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(marketIntent) }.getOrElse {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private const val ANT_RADIO_SERVICE_PACKAGE = "com.dsi.ant.service.socket"
private const val ANT_PLUS_PLUGINS_PACKAGE = "com.dsi.ant.plugins.antplus"

private enum class BluetoothAction { NONE, SCAN, START }
