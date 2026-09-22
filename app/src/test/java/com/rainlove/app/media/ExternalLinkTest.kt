package com.rainlove.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalLinkTest {
    @Test
    fun acceptsWebAndAppLinks() {
        assertEquals("https://example.com/song/123", ExternalLink.normalizeUrl(" https://example.com/song/123 "))
        assertEquals("qqmusic://song/123", ExternalLink.normalizeUrl("qqmusic://song/123"))
    }

    @Test
    fun blocksUnsafeOrMalformedLinks() {
        assertNull(ExternalLink.normalizeUrl("file:///sdcard/song.mp3"))
        assertNull(ExternalLink.normalizeUrl("intent://song"))
        assertNull(ExternalLink.normalizeUrl("javascript://example"))
        assertNull(ExternalLink.normalizeUrl("https://example.com/a b"))
        assertNull(ExternalLink.normalizeUrl("example.com/song"))
    }

    @Test
    fun validatesOptionalPackage() {
        assertEquals("", ExternalLink.normalizePackage(""))
        assertEquals("com.example.music", ExternalLink.normalizePackage("com.example.music"))
        assertNull(ExternalLink.normalizePackage("com.example.music;rm"))
    }
}
