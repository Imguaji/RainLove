package com.rainlove.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class MusicPlayer(context: Context) {
    private val player = ExoPlayer.Builder(context).build()
    private var selectedUri: Uri? = null

    fun select(uri: Uri) {
        selectedUri = uri
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun play(): Boolean {
        if (selectedUri == null) return false
        player.seekTo(0)
        player.play()
        return true
    }

    fun pause() = player.pause()
    fun release() = player.release()
}
