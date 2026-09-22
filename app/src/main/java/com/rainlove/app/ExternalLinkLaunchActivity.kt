package com.rainlove.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.rainlove.app.media.ExternalLink

class ExternalLinkLaunchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)?.let(ExternalLink::normalizeUrl)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)?.let(ExternalLink::normalizePackage)
        if (url == null || packageName == null) {
            finish()
            return
        }
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                if (packageName.isNotEmpty()) setPackage(packageName)
            })
        }.isSuccess
        if (opened && packageName.isNotEmpty() && intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)) {
            AUTO_PLAY_DELAYS_MS.forEach { delay -> handler.postDelayed(::sendPlayCommand, delay) }
        }
        handler.postDelayed(::finish, FINISH_DELAY_MS)
    }

    private fun sendPlayCommand() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        )
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_AUTO_PLAY = "auto_play"
        private val AUTO_PLAY_DELAYS_MS = longArrayOf(1_500L, 3_000L, 5_000L)
        private const val FINISH_DELAY_MS = 5_500L
    }
}
