package com.rainlove.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rainlove.app.NeteaseLaunchActivity

object NeteaseMusic {
    private val directIdPattern = Regex("^[0-9]{1,20}$")
    private val webUrlPattern = Regex(
        "(?i)(?:https?://)?(?:y\\.)?music\\.163\\.com/[^\\s]*?[?&#]id=([0-9]{1,20})(?![0-9])"
    )
    private val deepLinkPattern = Regex("(?i)^orpheus://song/([0-9]{1,20})(?:[/?#].*)?$")

    fun normalizeSongId(value: String): String? {
        val input = value.trim()
        return when {
            directIdPattern.matches(input) -> input
            else -> webUrlPattern.find(input)?.groupValues?.get(1)
                ?: deepLinkPattern.find(input)?.groupValues?.get(1)
        }
    }

    fun webUrlFor(value: String): Uri? = normalizeSongId(value)?.let {
        Uri.parse("https://music.163.com/song?id=$it")
    }

    fun deepLinkFor(value: String): Uri? = normalizeSongId(value)?.let {
        Uri.parse("orpheus://song/$it")
    }

    fun open(context: Context, value: String, autoPlay: Boolean = true): Boolean {
        val songId = normalizeSongId(value) ?: return false
        return runCatching {
            context.startActivity(
                Intent(context, NeteaseLaunchActivity::class.java).apply {
                    putExtra(NeteaseLaunchActivity.EXTRA_SONG_ID, songId)
                    putExtra(NeteaseLaunchActivity.EXTRA_AUTO_PLAY, autoPlay)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.isSuccess
    }
}
