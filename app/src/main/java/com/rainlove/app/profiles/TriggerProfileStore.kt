package com.rainlove.app.profiles

import android.content.SharedPreferences
import com.rainlove.app.RainLovePreferences
import com.rainlove.app.media.TriggerTarget
import com.rainlove.app.sensor.HeartRateTransport

data class TriggerProfile(
    val triggerBpm: Int,
    val recoveryBpm: Int,
    val triggerSeconds: Int,
    val recoverySeconds: Int,
    val cooldownSeconds: Int,
    val demoMode: Boolean,
    val transport: HeartRateTransport,
    val deviceAddress: String?,
    val deviceName: String?,
    val triggerTarget: TriggerTarget,
    val musicUri: String?,
    val musicName: String,
    val bilibiliBvid: String,
    val bilibiliAutoPlay: Boolean,
    val neteaseSongId: String,
    val neteaseAutoPlay: Boolean,
    val externalLink: String,
    val externalPackage: String,
    val externalAutoPlay: Boolean,
    val externalBackgroundDirect: Boolean,
)

class TriggerProfileStore(private val preferences: SharedPreferences) {
    fun names(): List<String> =
        preferences.getStringSet(RainLovePreferences.PROFILE_NAMES, emptySet()).orEmpty().sorted()

    fun save(rawName: String, profile: TriggerProfile): String? {
        val name = normalizeName(rawName) ?: return null
        val names = names().toMutableSet().apply { add(name) }
        preferences.edit().apply {
            putInt(key(name, "triggerBpm"), profile.triggerBpm)
            putInt(key(name, "recoveryBpm"), profile.recoveryBpm)
            putInt(key(name, "triggerSeconds"), profile.triggerSeconds)
            putInt(key(name, "recoverySeconds"), profile.recoverySeconds)
            putInt(key(name, "cooldownSeconds"), profile.cooldownSeconds)
            putBoolean(key(name, "demoMode"), profile.demoMode)
            putString(key(name, "transport"), profile.transport.name)
            putString(key(name, "deviceAddress"), profile.deviceAddress)
            putString(key(name, "deviceName"), profile.deviceName)
            putString(key(name, "triggerTarget"), profile.triggerTarget.name)
            putString(key(name, "musicUri"), profile.musicUri)
            putString(key(name, "musicName"), profile.musicName)
            putString(key(name, "bilibiliBvid"), profile.bilibiliBvid)
            putBoolean(key(name, "bilibiliAutoPlay"), profile.bilibiliAutoPlay)
            putString(key(name, "neteaseSongId"), profile.neteaseSongId)
            putBoolean(key(name, "neteaseAutoPlay"), profile.neteaseAutoPlay)
            putString(key(name, "externalLink"), profile.externalLink)
            putString(key(name, "externalPackage"), profile.externalPackage)
            putBoolean(key(name, "externalAutoPlay"), profile.externalAutoPlay)
            putBoolean(key(name, "externalBackgroundDirect"), profile.externalBackgroundDirect)
            putStringSet(RainLovePreferences.PROFILE_NAMES, names)
            apply()
        }
        return name
    }

    fun load(rawName: String): TriggerProfile? {
        val name = normalizeName(rawName) ?: return null
        if (name !in names()) return null
        val triggerBpm = preferences.getInt(key(name, "triggerBpm"), 120).coerceIn(80, 200)
        return TriggerProfile(
            triggerBpm = triggerBpm,
            recoveryBpm = preferences.getInt(key(name, "recoveryBpm"), 110).coerceIn(50, triggerBpm - 1),
            triggerSeconds = preferences.getInt(key(name, "triggerSeconds"), 5).coerceIn(1, 30),
            recoverySeconds = preferences.getInt(key(name, "recoverySeconds"), 10).coerceIn(1, 30),
            cooldownSeconds = preferences.getInt(key(name, "cooldownSeconds"), 60).coerceIn(0, 300),
            demoMode = preferences.getBoolean(key(name, "demoMode"), false),
            transport = preferences.getString(key(name, "transport"), null)
                ?.let { runCatching { HeartRateTransport.valueOf(it) }.getOrNull() }
                ?: HeartRateTransport.BLE,
            deviceAddress = preferences.getString(key(name, "deviceAddress"), null),
            deviceName = preferences.getString(key(name, "deviceName"), null),
            triggerTarget = preferences.getString(key(name, "triggerTarget"), null)
                ?.let { runCatching { TriggerTarget.valueOf(it) }.getOrNull() }
                ?: TriggerTarget.LOCAL_MUSIC,
            musicUri = preferences.getString(key(name, "musicUri"), null),
            musicName = preferences.getString(key(name, "musicName"), "尚未选择音乐") ?: "尚未选择音乐",
            bilibiliBvid = preferences.getString(key(name, "bilibiliBvid"), "") ?: "",
            bilibiliAutoPlay = preferences.getBoolean(key(name, "bilibiliAutoPlay"), true),
            neteaseSongId = preferences.getString(key(name, "neteaseSongId"), "") ?: "",
            neteaseAutoPlay = preferences.getBoolean(key(name, "neteaseAutoPlay"), true),
            externalLink = preferences.getString(key(name, "externalLink"), "") ?: "",
            externalPackage = preferences.getString(key(name, "externalPackage"), "") ?: "",
            externalAutoPlay = preferences.getBoolean(key(name, "externalAutoPlay"), false),
            externalBackgroundDirect = preferences.getBoolean(key(name, "externalBackgroundDirect"), false),
        )
    }

    fun delete(rawName: String): Boolean {
        val name = normalizeName(rawName) ?: return false
        val remaining = names().toMutableSet()
        if (!remaining.remove(name)) return false
        preferences.edit().apply {
            PROFILE_FIELDS.forEach { remove(key(name, it)) }
            putStringSet(RainLovePreferences.PROFILE_NAMES, remaining)
            apply()
        }
        return true
    }

    companion object {
        private val PROFILE_FIELDS = listOf(
            "triggerBpm", "recoveryBpm", "triggerSeconds", "recoverySeconds", "cooldownSeconds",
            "demoMode", "transport", "deviceAddress", "deviceName", "triggerTarget", "musicUri",
            "musicName", "bilibiliBvid", "bilibiliAutoPlay", "neteaseSongId", "neteaseAutoPlay",
            "externalLink", "externalPackage", "externalAutoPlay", "externalBackgroundDirect",
        )

        fun normalizeName(value: String): String? = value.trim().takeIf {
            it.isNotEmpty() && it.length <= 30 && it.none(Char::isISOControl)
        }

        private fun key(name: String, field: String) = "profile.$name.$field"
    }
}
