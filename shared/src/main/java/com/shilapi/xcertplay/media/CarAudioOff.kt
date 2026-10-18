package com.shilapi.xcertplay.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Ford/Lincoln head unit's "audio off" key.
 *
 * The key does not mute a stream in the usual way: the car's WindowManager takes audio focus and
 * broadcasts `android.intent.action.audiooff` with `audio_off=true`, then mutes STREAM_MUSIC.
 * This head unit's HAL reports `set_volume: Function not implemented`, so that mute never reaches
 * DiPlay's AudioTrack and CarPlay keeps playing. Watching the broadcast lets the renderer silence
 * its own PCM, which is the only path that works here.
 *
 * Only media and guidance are silenced; a telephone call stays audible, matching what the car
 * itself does (calls play on STREAM_VOICE_CALL, which audio-off does not touch).
 */
object CarAudioOff {
    private const val TAG = "DiPlay-AudioOff"
    const val ACTION = "android.intent.action.audiooff"
    private const val EXTRA = "audio_off"
    internal const val STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
    internal const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    internal const val STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    private enum class Source { NONE, SYSTEM, CAR }
    @Volatile private var source = Source.NONE

    @Volatile
    private var currentMuted: Boolean = false
    val muted: Boolean get() = currentMuted

    val muteSource: String get() = source.name.lowercase()

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    /** Starts watching the car's key; a new session also reconciles an inferred system mute. */
    @Synchronized
    fun attach(context: Context) {
        if (receiver != null) {
            refreshSystemMute()
            return
        }
        val app = context.applicationContext
        appContext = app
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION) {
                    if (!intent.hasExtra(EXTRA)) return
                    val off = intent.getBooleanExtra(EXTRA, false)
                    update(if (off) Source.CAR else Source.NONE)
                } else if (intent.getIntExtra(STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) {
                    refreshSystemMute()
                }
            }
        }
        val filter = IntentFilter(ACTION).apply {
            addAction(STREAM_MUTE_CHANGED)
            addAction(VOLUME_CHANGED)
        }
        // The car sends it from system_server. Android 13+ requires an explicit flag for runtime
        // receivers; the sender is the platform, so an exported registration is the one that
        // receives it. The action only carries a boolean, so this exposes nothing.
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(next, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(next, filter)
            }
        }
        registered
            .onSuccess { receiver = next }
            .onFailure { Log.w(TAG, "could not watch the car's audio-off key", it) }
        // The key may already have been pressed while DiPlay was not running; the car keeps
        // STREAM_MUSIC muted, so start from that rather than from "audible".
        val alreadyMuted = runCatching {
            app.getSystemService(AudioManager::class.java)?.isStreamMute(AudioManager.STREAM_MUSIC)
        }.getOrNull() == true
        if (alreadyMuted) {
            Log.i(TAG, "media stream already muted when audio-off attached")
            // A real car-key broadcast has stronger semantics than this startup inference.
            if (source != Source.CAR) update(Source.SYSTEM)
        }
    }

    /**
     * Only a mute inferred from STREAM_MUSIC follows that stream back to normal. A car-key mute
     * remains authoritative until the car releases it, even on HALs that ignore system mute.
     */
    @Synchronized
    fun refreshSystemMute() {
        if (source != Source.SYSTEM) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        val isMuted = runCatching { audio.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull()
        if (isMuted == false) update(Source.NONE)
    }

    @Synchronized
    private fun update(next: Source) {
        source = next
        val off = next != Source.NONE
        if (currentMuted == off) return
        currentMuted = off
        Log.i(TAG, "car audio off=$off source=$muteSource")
        listeners.forEach { listener -> runCatching { listener(off) } }
    }

    @Synchronized
    fun detach() {
        val current = receiver
        receiver = null
        if (current != null) runCatching { appContext?.unregisterReceiver(current) }
        appContext = null
        source = Source.NONE
        currentMuted = false
    }

    /** Called with true when the car muted its audio and false when it came back. */
    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }
}
