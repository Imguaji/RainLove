package com.rainlove.app.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateHistoryTest {
    @Test
    fun csvRowUsesUtcAndSource() {
        assertEquals(
            "2026-09-22T00:00:00Z,145,BLE\n",
            HeartRateHistory.csvRow(HeartRateRecord(1_790_035_200_000L, 145, "BLE")),
        )
    }
}
