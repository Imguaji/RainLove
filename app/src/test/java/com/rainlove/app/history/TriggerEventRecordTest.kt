package com.rainlove.app.history

import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerEventRecordTest {
    @Test fun `csv preserves Chinese commas quotes and line breaks`() {
        val event = TriggerEventRecord(0, "BLE", TriggerEventType.STATUS, "设备,\"心率\"\n重连", 130)
        assertEquals(
            "\"1970-01-01T00:00:00Z\",\"BLE\",\"STATUS\",\"130\",\"设备,\"\"心率\"\"\n重连\"\n",
            event.csvRow(),
        )
    }

    @Test fun `missing heart rate exports empty value rather than a false zero`() {
        assertEquals(
            "\"1970-01-01T00:00:00Z\",\"ANT_PLUS\",\"SIGNAL_LOST\",\"\",\"数据过期\"\n",
            TriggerEventRecord(0, "ANT_PLUS", TriggerEventType.SIGNAL_LOST, "数据过期").csvRow(),
        )
    }
}
