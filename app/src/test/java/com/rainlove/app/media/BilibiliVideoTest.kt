package com.rainlove.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BilibiliVideoTest {
    @Test fun `accepts a raw BV id`() {
        assertEquals("BV1La411N7Nd", BilibiliVideo.normalizeBvid("BV1La411N7Nd"))
    }

    @Test fun `extracts BV id from a video URL`() {
        assertEquals(
            "BV1La411N7Nd",
            BilibiliVideo.normalizeBvid("https://www.bilibili.com/video/BV1La411N7Nd/?p=2"),
        )
    }

    @Test fun `rejects values without a complete BV id`() {
        assertNull(BilibiliVideo.normalizeBvid("BV123"))
        assertNull(BilibiliVideo.normalizeBvid("BV1La411N7NdX"))
        assertNull(BilibiliVideo.normalizeBvid("https://b23.tv/abc123"))
    }
}
