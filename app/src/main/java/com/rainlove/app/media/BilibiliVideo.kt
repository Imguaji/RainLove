package com.rainlove.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rainlove.app.BilibiliLaunchActivity

object BilibiliVideo {
    private val bvidPattern = Regex("(?i)(?<![0-9A-Za-z])BV[0-9A-Za-z]{10}(?![0-9A-Za-z])")

    fun normalizeBvid(value: String): String? {
        val match = bvidPattern.find(value.trim())?.value ?: return null
        return "BV${match.drop(2)}"
    }

    fun urlFor(value: String): Uri? = normalizeBvid(value)?.let {
        Uri.parse("https://www.bilibili.com/video/$it")
    }

    fun open(context: Context, value: String, autoPlay: Boolean = true): Boolean {
        val bvid = normalizeBvid(value) ?: return false
        return runCatching {
            context.startActivity(
                Intent(context, BilibiliLaunchActivity::class.java).apply {
                    putExtra(BilibiliLaunchActivity.EXTRA_BVID, bvid)
                    putExtra(BilibiliLaunchActivity.EXTRA_AUTO_PLAY, autoPlay)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.isSuccess
    }
}

enum class TriggerTarget {
    LOCAL_MUSIC,
    BILIBILI_VIDEO,
    NETEASE_MUSIC,
    EXTERNAL_LINK,
}
