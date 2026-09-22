package com.rainlove.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.rainlove.app.media.NeteaseMusic

class NeteaseLaunchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val songId = intent.getStringExtra(EXTRA_SONG_ID)
        val deepLink = songId?.let(NeteaseMusic::deepLinkFor)
        val webUrl = songId?.let(NeteaseMusic::webUrlFor)
        if (deepLink == null || webUrl == null) {
            finish()
            return
        }

        val openedInNetease = openInNetease(deepLink) || openInNetease(webUrl)
        if (!openedInNetease) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, webUrl).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                })
            }
        } else if (intent.getBooleanExtra(EXTRA_AUTO_PLAY, true)) {
            AUTO_PLAY_DELAYS_MS.forEach { delay -> handler.postDelayed(::sendPlayCommand, delay) }
        }
        handler.postDelayed(::finish, FINISH_DELAY_MS)
    }

    private fun openInNetease(uri: android.net.Uri): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(NETEASE_PACKAGE)
        })
        true
    } catch (_: ActivityNotFoundException) {
        false
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
        const val EXTRA_SONG_ID = "song_id"
        const val EXTRA_AUTO_PLAY = "auto_play"
        private const val NETEASE_PACKAGE = "com.netease.cloudmusic"
        private val AUTO_PLAY_DELAYS_MS = longArrayOf(1_500L, 3_000L, 5_000L)
        private const val FINISH_DELAY_MS = 5_500L
    }
}
