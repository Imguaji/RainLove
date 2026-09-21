package com.rainlove.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rainlove.app.media.BilibiliVideo
import com.rainlove.app.media.TriggerTarget

class MainActivity : ComponentActivity() {
    private val viewModel: RainLoveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RainLoveScreen(viewModel) } }
    }

    override fun onStart() {
        super.onStart()
        HeartRateForegroundService.setAppVisible(true)
    }

    override fun onStop() {
        HeartRateForegroundService.setAppVisible(false)
        super.onStop()
    }
}

@Composable
private fun RainLoveScreen(vm: RainLoveViewModel) {
    val state by vm.ui.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingBluetoothAction by remember { mutableStateOf(BluetoothAction.NONE) }
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
            Text("RainLove", style = MaterialTheme.typography.headlineMedium)
            Text("${state.bpm}", fontSize = 72.sp)
            Text("BPM · ${state.triggerState.name}")
            Text(state.status)

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
            if (state.triggerTarget == TriggerTarget.LOCAL_MUSIC) {
                Text("音乐：${state.musicName}")
                Button(
                    onClick = { musicLauncher.launch(arrayOf("audio/*")) },
                    enabled = !state.monitoring,
                ) { Text("选择本地音乐") }
                Button(
                    onClick = vm::toggleMusicPreview,
                    enabled = !state.monitoring && state.musicName != "尚未选择音乐",
                ) { Text(if (state.musicPlaying) "停止测试播放" else "测试播放本地音乐") }
            } else {
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
                Text("RainLove 在前台时会直接打开视频；后台或锁屏受 Android 限制，仍需点击通知。恢复后不会关闭 B 站。")
                Button(
                    onClick = vm::testBilibiliVideo,
                    enabled = !state.monitoring && BilibiliVideo.normalizeBvid(state.bilibiliBvid) != null,
                ) { Text("测试打开视频") }
            }
            Button(onClick = {
                val permissions = missingMonitoringPermissions(context)
                if (!state.demoMode && permissions.isNotEmpty()) {
                    pendingBluetoothAction = BluetoothAction.START
                    permissionLauncher.launch(permissions.toTypedArray())
                } else vm.toggleMonitoring()
            }) {
                Text(if (state.monitoring) "停止心动模式" else "开启心动模式")
            }
            Text("娱乐项目，不用于诊断或监测疾病。", style = MaterialTheme.typography.bodySmall)
        }
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

private fun missingMonitoringPermissions(context: android.content.Context): List<String> {
    val permissions = requiredBluetoothPermissions().toMutableList()
    if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
    return permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
}

private enum class BluetoothAction { NONE, SCAN, START }
