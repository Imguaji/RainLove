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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel: RainLoveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RainLoveScreen(viewModel) } }
    }
}

@Composable
private fun RainLoveScreen(vm: RainLoveViewModel) {
    val state by vm.ui.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.toggleMonitoring()
    }
    val musicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "已选择的音乐"
        vm.selectMusic(uri, name)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
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
            }

            SettingSlider("触发心率", state.triggerBpm, 80..200, vm::setTriggerBpm)
            SettingSlider("恢复心率", state.recoveryBpm, 50..180, vm::setRecoveryBpm)

            Text("音乐：${state.musicName}")
            Button(onClick = { musicLauncher.launch(arrayOf("audio/*")) }) { Text("选择本地音乐") }
            Button(onClick = {
                val permissions = requiredBluetoothPermissions().filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (!state.demoMode && permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
                else vm.toggleMonitoring()
            }) {
                Text(if (state.monitoring) "停止心动模式" else "开启心动模式")
            }
            Text("娱乐项目，不用于诊断或监测疾病。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("$label：$value BPM")
        Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

private fun requiredBluetoothPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
