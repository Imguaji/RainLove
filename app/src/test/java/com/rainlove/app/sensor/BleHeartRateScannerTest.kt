package com.rainlove.app.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

class BleHeartRateScannerTest {
    @Test
    fun `discovered devices are deduplicated by address and sorted by name`() {
        val first = BleHeartRateDevice("Garmin", "AA:BB:CC:DD:EE:01")
        val second = BleHeartRateDevice("Polar", "AA:BB:CC:DD:EE:02")

        var devices = mergeHeartRateDevices(emptyList(), second)
        devices = mergeHeartRateDevices(devices, first)
        devices = mergeHeartRateDevices(devices, first.copy(name = "Renamed Garmin"))

        assertEquals(listOf(first, second), devices)
    }
}
