package com.rainlove.app.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

@SuppressLint("MissingPermission")
class BleHeartRateSource(
    private val context: Context,
    private val scanner: BluetoothLeScanner,
    private val targetAddress: String? = null,
    private val onConnectedDevice: (BleHeartRateDevice) -> Unit = {},
) : HeartRateSource {
    private var listener: HeartRateSource.Listener? = null
    private var gatt: BluetoothGatt? = null
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    override fun start(listener: HeartRateSource.Listener) {
        this.listener = listener
        running = true
        startScan()
    }

    private fun startScan() {
        if (!running) return
        listener?.onStatus(if (targetAddress == null) "正在扫描标准 BLE 心率设备…" else "正在查找已选心率设备…")
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
    }

    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        scanner.stopScan(scanCallback)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        listener?.onStatus("已断开")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (targetAddress != null && result.device.address != targetAddress) return
            if (gatt != null) return
            scanner.stopScan(this)
            listener?.onStatus("已发现 ${result.device.name ?: "心率设备"}，正在连接…")
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            listener?.onError("BLE 扫描失败：$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener?.onConnectionChanged(true)
                onConnectedDevice(
                    BleHeartRateDevice(
                        name = gatt.device.name?.takeIf(String::isNotBlank) ?: "未命名心率设备",
                        address = gatt.device.address,
                    )
                )
                listener?.onStatus("已连接，正在读取心率服务…")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                if (this@BleHeartRateSource.gatt === gatt) this@BleHeartRateSource.gatt = null
                listener?.onConnectionChanged(false)
                if (running) {
                    listener?.onStatus("设备已断开，2 秒后重连…")
                    handler.postDelayed(::startScan, RECONNECT_DELAY_MS)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(HEART_RATE_SERVICE)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                listener?.onError("设备未提供标准心率测量特征")
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor == null) {
                listener?.onError("无法启用心率通知")
                return
            }
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            listener?.onStatus("正在接收实时心率")
        }

        @Deprecated("Deprecated in Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            parseHeartRate(characteristic.value)?.let { listener?.onHeartRate(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            parseHeartRate(value)?.let { listener?.onHeartRate(it) }
        }
    }

    companion object {
        private val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val RECONNECT_DELAY_MS = 2_000L

        internal fun parseHeartRate(value: ByteArray): Int? {
            if (value.size < 2) return null
            val isUInt16 = value[0].toInt() and 0x01 != 0
            return if (isUInt16) {
                if (value.size < 3) null else (value[1].toInt() and 0xff) or ((value[2].toInt() and 0xff) shl 8)
            } else value[1].toInt() and 0xff
        }
    }
}
