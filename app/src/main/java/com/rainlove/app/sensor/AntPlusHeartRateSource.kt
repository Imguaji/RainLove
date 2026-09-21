package com.rainlove.app.sensor

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle

class AntPlusHeartRateSource(
    context: Context,
) : HeartRateSource {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var listener: HeartRateSource.Listener? = null
    private var releaseHandle: PccReleaseHandle<AntPlusHeartRatePcc>? = null
    private var running = false
    private var requestGeneration = 0
    private val retryRunnable = Runnable { requestAccess() }

    override fun start(listener: HeartRateSource.Listener) {
        if (running) return
        this.listener = listener
        running = true
        requestAccess()
    }

    override fun stop() {
        running = false
        requestGeneration++
        handler.removeCallbacks(retryRunnable)
        val handle = releaseHandle
        releaseHandle = null
        handle?.close()
        listener?.onConnectionChanged(false)
        listener?.onStatus("ANT+ 已断开")
        listener = null
    }

    private fun requestAccess() {
        if (!running) return
        handler.removeCallbacks(retryRunnable)
        val generation = ++requestGeneration
        val oldHandle = releaseHandle
        releaseHandle = null
        oldHandle?.close()
        listener?.onStatus("正在搜索 ANT+ 心率设备…")

        releaseHandle = try {
            AntPlusHeartRatePcc.requestAccess(
                appContext,
                0,
                0,
                AntPluginPcc.IPluginAccessResultReceiver { result, resultCode, initialState ->
                    if (!running || generation != requestGeneration) return@IPluginAccessResultReceiver
                    handleAccessResult(result, resultCode, initialState)
                },
                AntPluginPcc.IDeviceStateChangeReceiver { state ->
                    if (!running || generation != requestGeneration) return@IDeviceStateChangeReceiver
                    handleDeviceState(state)
                },
            )
        } catch (error: RuntimeException) {
            listener?.onError("ANT+ 启动失败：${error.message ?: error.javaClass.simpleName}")
            null
        }
    }

    private fun handleAccessResult(
        pcc: AntPlusHeartRatePcc?,
        result: RequestAccessResult,
        initialState: DeviceState?,
    ) {
        when (result) {
            RequestAccessResult.SUCCESS -> {
                if (pcc == null) {
                    listener?.onError("ANT+ 连接失败：服务未返回心率设备")
                    return
                }
                listener?.onConnectionChanged(true)
                listener?.onStatus("已连接 ANT+：${pcc.deviceName}")
                pcc.subscribeHeartRateDataEvent { _, _, bpm, _, _, _ ->
                    if (running && bpm > 0) listener?.onHeartRate(bpm)
                }
                initialState?.let(::handleDeviceState)
            }
            RequestAccessResult.SEARCH_TIMEOUT -> scheduleRetry("未发现 ANT+ 心率设备")
            RequestAccessResult.DEPENDENCY_NOT_INSTALLED -> listener?.onError(
                "缺少 ANT+ Plugins Service，请先安装 ANT Radio Service 和 ANT+ Plugins Service"
            )
            RequestAccessResult.ADAPTER_NOT_DETECTED -> listener?.onError(
                "未检测到 ANT+ 硬件；请确认手机支持 ANT+，或已连接兼容的 ANT USB 适配器"
            )
            RequestAccessResult.CHANNEL_NOT_AVAILABLE -> listener?.onError("没有可用的 ANT+ 通道")
            RequestAccessResult.DEVICE_ALREADY_IN_USE -> listener?.onError("ANT+ 心率设备正被其他应用占用")
            RequestAccessResult.USER_CANCELLED -> listener?.onError("已取消 ANT+ 设备连接")
            else -> listener?.onError("ANT+ 连接失败：$result")
        }
    }

    private fun handleDeviceState(state: DeviceState) {
        when (state) {
            DeviceState.TRACKING -> listener?.onConnectionChanged(true)
            DeviceState.SEARCHING -> listener?.onStatus("正在搜索 ANT+ 心率设备…")
            DeviceState.PROCESSING_REQUEST -> listener?.onStatus("正在连接 ANT+ 心率设备…")
            DeviceState.DEAD, DeviceState.CLOSED -> scheduleRetry("ANT+ 设备已断开")
            DeviceState.UNRECOGNIZED -> scheduleRetry("ANT+ 设备状态异常")
        }
    }

    private fun scheduleRetry(message: String) {
        if (!running) return
        listener?.onConnectionChanged(false)
        listener?.onStatus("$message，2 秒后重试…")
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, RECONNECT_DELAY_MS)
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 2_000L
    }
}
