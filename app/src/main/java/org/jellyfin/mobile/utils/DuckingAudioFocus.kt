package org.jellyfin.mobile.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService
import androidx.media3.common.Player

/**
 * Custom audio focus handling: instead of pausing on a transient focus loss (e.g. a phone call),
 * keep playing at a reduced volume and restore it when focus returns.
 * A permanent focus loss still pauses playback.
 */
class DuckingAudioFocus(
    context: Context,
    private val player: Player,
) : AudioManager.OnAudioFocusChangeListener, Player.Listener {
    private val audioManager: AudioManager = context.getSystemService()!!

    private var focusRequest: AudioFocusRequest? = null

    init {
        player.addListener(this)
        if (player.playWhenReady) requestFocus()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) requestFocus() else abandonFocus()
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> player.volume = 1f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> player.volume = DUCKED_VOLUME
            AudioManager.AUDIOFOCUS_LOSS -> {
                player.volume = 1f
                player.pause()
            }
        }
    }

    private fun requestFocus() {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener(this)
                .setWillPauseWhenDucked(false)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        // Focus is held by someone else (e.g. a call in progress): play anyway, but quietly
        player.volume = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) 1f else DUCKED_VOLUME
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
        player.volume = 1f
    }

    fun release() {
        player.removeListener(this)
        abandonFocus()
    }

    private companion object {
        const val DUCKED_VOLUME = 0.25f
    }
}
