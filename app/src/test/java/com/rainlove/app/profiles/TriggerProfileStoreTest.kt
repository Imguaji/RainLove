package com.rainlove.app.profiles

import android.content.SharedPreferences
import com.rainlove.app.media.TriggerTarget
import com.rainlove.app.sensor.HeartRateTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerProfileStoreTest {
    @Test
    fun acceptsShortNamesAndTrimsWhitespace() {
        assertEquals("骑行", TriggerProfileStore.normalizeName("  骑行  "))
    }

    @Test
    fun rejectsEmptyLongOrControlCharacterNames() {
        assertNull(TriggerProfileStore.normalizeName(" "))
        assertNull(TriggerProfileStore.normalizeName("a".repeat(31)))
        assertNull(TriggerProfileStore.normalizeName("骑行\n夜间"))
    }

    @Test
    fun savesAndRestoresProfilesIndependently() {
        val preferences = MemoryPreferences()
        val store = TriggerProfileStore(preferences)
        val riding = sampleProfile()
        val daily = riding.copy(
            triggerBpm = 130,
            recoveryBpm = 100,
            demoMode = true,
            transport = HeartRateTransport.ANT_PLUS,
            deviceAddress = null,
            triggerTarget = TriggerTarget.NETEASE_MUSIC,
            neteaseSongId = "123456",
            externalBackgroundDirect = false,
        )

        assertEquals("骑行", store.save(" 骑行 ", riding))
        assertEquals("日常", store.save("日常", daily))
        assertEquals(listOf("日常", "骑行"), store.names())
        assertEquals(riding, store.load("骑行"))
        assertEquals(daily, store.load("日常"))
        assertNull(store.load("不存在"))

        val updated = riding.copy(triggerSeconds = 30, musicUri = null)
        store.save("骑行", updated)
        assertEquals(updated, store.load("骑行"))
        assertEquals(daily, store.load("日常"))

        assertTrue(store.delete("骑行"))
        assertFalse(store.delete("骑行"))
        assertNull(store.load("骑行"))
        assertEquals(listOf("日常"), store.names())
        assertEquals(daily, store.load("日常"))
        assertFalse(preferences.all.keys.any { it.startsWith("profile.骑行.") })
    }

    private fun sampleProfile() = TriggerProfile(
        triggerBpm = 120,
        recoveryBpm = 110,
        triggerSeconds = 5,
        recoverySeconds = 10,
        cooldownSeconds = 60,
        demoMode = false,
        transport = HeartRateTransport.BLE,
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        deviceName = "Heart Rate",
        triggerTarget = TriggerTarget.LOCAL_MUSIC,
        musicUri = "content://music/1",
        musicName = "rain.mp3",
        bilibiliBvid = "BV1234567890",
        bilibiliAutoPlay = true,
        neteaseSongId = "",
        neteaseAutoPlay = true,
        externalLink = "https://example.com/song",
        externalPackage = "com.example.music",
        externalAutoPlay = false,
        externalBackgroundDirect = true,
    )

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = Editor()

        private inner class Editor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                put(key, values?.toSet())
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor = apply { removed.add(key.orEmpty()) }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) values.clear()
                removed.forEach(values::remove)
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                return true
            }
            override fun apply() { commit() }

            private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply {
                updates[key.orEmpty()] = value
            }
        }
    }
}
