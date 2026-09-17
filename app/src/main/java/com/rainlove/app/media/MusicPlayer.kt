package com.rainlove.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class MusicPlayer(
    context: Context,
    private val onEvent: (Event) -> Unit = {},
) {
    sealed interface Event {
        data object Started : Event
        data object Stopped : Event
        data class Error(val detail: String) : Event
    }

    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
    private var selectedUri: Uri? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onEvent(if (isPlaying) Event.Started else Event.Stopped)
            }

            override fun onPlayerError(error: PlaybackException) {
                onEvent(Event.Error(error.errorCodeName))
            }
        })
    }

    fun select(uri: Uri) {
        selectedUri = uri
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun play(): Boolean {
        if (selectedUri == null) return false
        player.seekTo(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = true
        return true
    }

    fun pause() = player.pause()
    fun isPlaying(): Boolean = player.isPlaying
    fun release() = player.release()
}
