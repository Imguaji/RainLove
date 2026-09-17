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
import com.rainlove.app.media.BilibiliVideo

class BilibiliLaunchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bvid = intent.getStringExtra(EXTRA_BVID)
        val uri = bvid?.let(BilibiliVideo::urlFor)
        if (uri == null) {
            finish()
            return
        }

        val openedInBilibili = try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                setPackage(BILIBILI_PACKAGE)
            })
            true
        } catch (_: ActivityNotFoundException) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                })
            }
            false
        }

        if (openedInBilibili && intent.getBooleanExtra(EXTRA_AUTO_PLAY, true)) {
            AUTO_PLAY_DELAYS_MS.forEach { delay ->
                handler.postDelayed(::sendPlayCommand, delay)
            }
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
        const val EXTRA_BVID = "bvid"
        const val EXTRA_AUTO_PLAY = "auto_play"
        private const val BILIBILI_PACKAGE = "tv.danmaku.bili"
        private val AUTO_PLAY_DELAYS_MS = longArrayOf(1_200L, 3_000L, 5_000L)
        private const val FINISH_DELAY_MS = 5_500L
    }
}
