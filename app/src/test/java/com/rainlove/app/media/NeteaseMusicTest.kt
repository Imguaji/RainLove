package com.rainlove.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseMusicTest {
    @Test
    fun acceptsSongId() {
        assertEquals("1396535910", NeteaseMusic.normalizeSongId("1396535910"))
    }

    @Test
    fun extractsSongIdFromSupportedLinks() {
        assertEquals(
            "1396535910",
            NeteaseMusic.normalizeSongId("https://music.163.com/#/song?id=1396535910"),
        )
        assertEquals(
            "2054825077",
            NeteaseMusic.normalizeSongId("https://y.music.163.com/m/song?id=2054825077&userid=1"),
        )
        assertEquals("17241424", NeteaseMusic.normalizeSongId("orpheus://song/17241424"))
    }

    @Test
    fun rejectsUnrelatedOrMalformedValues() {
        assertNull(NeteaseMusic.normalizeSongId(""))
        assertNull(NeteaseMusic.normalizeSongId("https://example.com/song?id=1396535910"))
        assertNull(NeteaseMusic.normalizeSongId("song name"))
    }
}
