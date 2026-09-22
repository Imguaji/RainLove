package com.rainlove.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.rainlove.app.sensor.HeartRateTransport

class BootResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val preferences = context.getSharedPreferences(RainLovePreferences.NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(RainLovePreferences.RESUME_ON_BOOT, false) ||
            !preferences.getBoolean(RainLovePreferences.MONITORING_DESIRED, false) ||
            preferences.getBoolean(RainLovePreferences.DEMO_MODE, true)
        ) return
        val transport = preferences.getString(RainLovePreferences.HEART_RATE_TRANSPORT, null)
            ?.let { runCatching { HeartRateTransport.valueOf(it) }.getOrNull() }
            ?: HeartRateTransport.BLE
        if (!hasRequiredPermission(context, transport)) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HeartRateForegroundService::class.java)
                    .setAction(HeartRateForegroundService.ACTION_START),
            )
        }
    }

    private fun hasRequiredPermission(context: Context, transport: HeartRateTransport): Boolean {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 31 && transport == HeartRateTransport.BLE ->
                granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
            Build.VERSION.SDK_INT >= 31 -> granted(Manifest.permission.BLUETOOTH_CONNECT)
            transport == HeartRateTransport.BLE -> granted(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> true
        }
    }
}
