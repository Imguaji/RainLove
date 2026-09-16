package com.rainlove.app.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

@SuppressLint("MissingPermission")
class BleHeartRateSource(
    private val context: Context,
    private val scanner: BluetoothLeScanner,
) : HeartRateSource {
    private var listener: HeartRateSource.Listener? = null
    private var gatt: BluetoothGatt? = null

    override fun start(listener: HeartRateSource.Listener) {
        this.listener = listener
        listener.onStatus("正在扫描标准 BLE 心率设备…")
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
    }

    override fun stop() {
        scanner.stopScan(scanCallback)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        listener?.onStatus("已断开")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanner.stopScan(this)
            listener?.onStatus("已发现 ${result.device.name ?: "心率设备"}，正在连接…")
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothGatt.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            listener?.onError("BLE 扫描失败：$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener?.onStatus("已连接，正在读取心率服务…")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener?.onStatus("设备已断开")
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

        internal fun parseHeartRate(value: ByteArray): Int? {
            if (value.size < 2) return null
            val isUInt16 = value[0].toInt() and 0x01 != 0
            return if (isUInt16) {
                if (value.size < 3) null else (value[1].toInt() and 0xff) or ((value[2].toInt() and 0xff) shl 8)
            } else value[1].toInt() and 0xff
        }
    }
}
