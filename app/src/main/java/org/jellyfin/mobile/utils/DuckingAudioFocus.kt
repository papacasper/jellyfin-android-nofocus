package org.jellyfin.mobile.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.media3.common.Player

/**
 * Custom audio focus handling: instead of pausing on a transient focus loss, keep playing.
 * During a phone call playback stays at full volume, so the call doesn't change what you hear and the
 * media volume (see [isInCall]) controls it; other transient losses (e.g. navigation prompts) duck it.
 * A permanent focus loss still pauses playback.
 */
class DuckingAudioFocus(
    context: Context,
    private val player: Player,
) : AudioManager.OnAudioFocusChangeListener, Player.Listener {
    private val audioManager: AudioManager = context.getSystemService()!!

    private var focusRequest: AudioFocusRequest? = null

    private var hasFocus = true

    // Telecom may hand us focus when playback starts during a call, so also follow the audio mode
    private val modeListener: AudioManager.OnModeChangedListener? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnModeChangedListener { applyVolume() }
    } else {
        null
    }

    val isInCall: Boolean
        get() = audioManager.mode == AudioManager.MODE_IN_CALL

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnModeChangedListener(ContextCompat.getMainExecutor(context), modeListener!!)
        }
        player.addListener(this)
        if (player.playWhenReady) requestFocus()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) requestFocus() else abandonFocus()
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> setHasFocus(true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> setHasFocus(false)
            AudioManager.AUDIOFOCUS_LOSS -> {
                setHasFocus(true)
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
        // Focus may be held by someone else: play anyway
        setHasFocus(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    private fun setHasFocus(focus: Boolean) {
        hasFocus = focus
        applyVolume()
    }

    private fun applyVolume() {
        player.volume = if (hasFocus || isInCall) 1f else DUCKED_VOLUME
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
        setHasFocus(true)
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.removeOnModeChangedListener(modeListener!!)
        }
        player.removeListener(this)
        abandonFocus()
    }

    private companion object {
        const val DUCKED_VOLUME = 0.25f
    }
}
