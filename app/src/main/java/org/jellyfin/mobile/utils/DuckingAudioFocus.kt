package org.jellyfin.mobile.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Custom audio focus handling: instead of pausing on a transient focus loss, keep playing.
 * During a phone call playback stays at full volume, so the call doesn't change what you hear and the
 * media volume controls it (see [isInCall]); other transient losses (e.g. navigation prompts) duck it.
 * A permanent focus loss still pauses playback.
 *
 * While the call uses the earpiece, playback is routed to the speaker, since media would otherwise follow
 * the call to the earpiece and become barely audible.
 *
 * Android's audio policy also caps media (USAGE_MEDIA) during a call at the call volume, so volume keys can't
 * raise it. Assistant audio is exempt from that cap (its own volume group), is always aliased to the media
 * volume (STREAM_ASSISTANT -> STREAM_MUSIC) and is routed like media, so during a call playback switches to
 * USAGE_ASSISTANT and back afterwards: it sounds the same as without a call. (Accessibility audio is also exempt,
 * but can have an independent volume that apps can't change, which left the volume keys without effect.)
 */
class DuckingAudioFocus(
    context: Context,
    private val player: ExoPlayer,
) : AudioManager.OnAudioFocusChangeListener, Player.Listener {
    private val audioManager: AudioManager = context.getSystemService()!!

    private var focusRequest: AudioFocusRequest? = null

    private var hasFocus = true

    // Usage the player was built with (applyDefaultAudioAttributes)
    private var usage = C.USAGE_MEDIA

    // Telecom may hand us focus when playback starts during a call, so also follow the audio mode
    private val modeListener: AudioManager.OnModeChangedListener? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnModeChangedListener {
            // Focus refused during a call is never handed back on its own: ask again once the call ends,
            // otherwise playback stays ducked
            if (!isInCall && !hasFocus && player.playWhenReady) requestFocus() else applyVolume()
            applyUsage()
            applyRoute()
        }
    } else {
        null
    }

    private val communicationDeviceListener: AudioManager.OnCommunicationDeviceChangedListener? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnCommunicationDeviceChangedListener { applyRoute() }
        } else {
            null
        }

    private val isInCall: Boolean
        get() = audioManager.isInCall

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val executor = ContextCompat.getMainExecutor(context)
            audioManager.addOnModeChangedListener(executor, modeListener!!)
            audioManager.addOnCommunicationDeviceChangedListener(executor, communicationDeviceListener!!)
        }
        applyUsage()
        applyRoute()
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
                .setAcceptsDelayedFocusGain(true)
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

    private fun applyUsage() {
        val wanted = if (isInCall) C.USAGE_ASSISTANT else C.USAGE_MEDIA
        if (wanted == usage) return // changing attributes recreates the AudioTrack: only do it on a real change
        usage = wanted
        val attributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(wanted)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player.setAudioAttributes(attributes, false)
    }

    private fun applyRoute() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val onEarpiece = isInCall && audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val speaker = if (onEarpiece) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { device -> device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            null
        }
        player.setPreferredAudioDevice(speaker)
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
            audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceListener!!)
        }
        player.removeListener(this)
        abandonFocus()
    }

    private companion object {
        const val DUCKED_VOLUME = 0.25f
    }
}

/** True during a phone call or a VoIP call (e.g. Discord, WhatsApp). */
val AudioManager.isInCall: Boolean
    get() = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
