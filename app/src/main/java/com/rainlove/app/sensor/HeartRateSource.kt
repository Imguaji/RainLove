package com.rainlove.app.sensor

interface HeartRateSource {
    interface Listener {
        fun onHeartRate(bpm: Int)
        fun onStatus(message: String)
        fun onError(message: String)
        fun onConnectionChanged(connected: Boolean) = Unit
    }

    fun start(listener: Listener)
    fun stop()
}
