package com.rainlove.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri

object BilibiliVideo {
    private val bvidPattern = Regex("(?i)(?<![0-9A-Za-z])BV[0-9A-Za-z]{10}(?![0-9A-Za-z])")

    fun normalizeBvid(value: String): String? {
        val match = bvidPattern.find(value.trim())?.value ?: return null
        return "BV${match.drop(2)}"
    }

    fun urlFor(value: String): Uri? = normalizeBvid(value)?.let {
        Uri.parse("https://www.bilibili.com/video/$it")
    }

    fun open(context: Context, value: String): Boolean {
        val uri = urlFor(value) ?: return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.isSuccess
    }
}

enum class TriggerTarget {
    LOCAL_MUSIC,
    BILIBILI_VIDEO,
}
