package com.rainlove.app.media

import android.content.Context
import android.content.Intent
import com.rainlove.app.ExternalLinkLaunchActivity

object ExternalLink {
    private val linkPattern = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s]+$")
    private val packagePattern = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")
    private val blockedSchemes = setOf("file", "content", "intent", "javascript", "data", "market")

    fun normalizeUrl(value: String): String? {
        val link = value.trim()
        if (!linkPattern.matches(link)) return null
        val scheme = link.substringBefore(":").lowercase()
        return link.takeUnless { scheme in blockedSchemes }
    }

    fun normalizePackage(value: String): String? {
        val packageName = value.trim()
        return if (packageName.isBlank()) "" else packageName.takeIf(packagePattern::matches)
    }

    fun open(context: Context, url: String, packageName: String, autoPlay: Boolean): Boolean {
        val normalizedUrl = normalizeUrl(url) ?: return false
        val normalizedPackage = normalizePackage(packageName) ?: return false
        return runCatching {
            context.startActivity(
                Intent(context, ExternalLinkLaunchActivity::class.java).apply {
                    putExtra(ExternalLinkLaunchActivity.EXTRA_URL, normalizedUrl)
                    putExtra(ExternalLinkLaunchActivity.EXTRA_PACKAGE, normalizedPackage)
                    putExtra(ExternalLinkLaunchActivity.EXTRA_AUTO_PLAY, autoPlay)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.isSuccess
    }
}
