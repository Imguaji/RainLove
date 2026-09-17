package com.rainlove.app.sensor

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import java.util.UUID

data class BleHeartRateDevice(
    val name: String,
    val address: String,
)

internal fun mergeHeartRateDevices(
    current: List<BleHeartRateDevice>,
    candidate: BleHeartRateDevice,
): List<BleHeartRateDevice> = (current + candidate)
    .distinctBy(BleHeartRateDevice::address)
    .sortedBy(BleHeartRateDevice::name)

@SuppressLint("MissingPermission")
class BleHeartRateScanner(private val scanner: BluetoothLeScanner) {
    private var running = false
    private var onDevice: ((BleHeartRateDevice) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onFinished: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start(
        onDevice: (BleHeartRateDevice) -> Unit,
        onError: (String) -> Unit,
        onFinished: () -> Unit,
    ) {
        stop()
        this.onDevice = onDevice
        this.onError = onError
        this.onFinished = onFinished
        running = true
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        handler.postDelayed(timeout, SCAN_DURATION_MS)
    }

    fun stop() {
        handler.removeCallbacks(timeout)
        if (running) scanner.stopScan(scanCallback)
        running = false
        onDevice = null
        onError = null
        onFinished = null
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onDevice?.invoke(
                BleHeartRateDevice(
                    name = result.device.name?.takeIf(String::isNotBlank) ?: "未命名心率设备",
                    address = result.device.address,
                )
            )
        }

        override fun onScanFailed(errorCode: Int) {
            handler.removeCallbacks(timeout)
            running = false
            onError?.invoke("BLE 扫描失败：$errorCode")
        }
    }

    private val timeout: Runnable = Runnable {
        if (!running) return@Runnable
        scanner.stopScan(scanCallback)
        running = false
        onFinished?.invoke()
    }

    companion object {
        private val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private const val SCAN_DURATION_MS = 15_000L
    }
}
