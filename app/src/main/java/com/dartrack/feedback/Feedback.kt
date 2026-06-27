package com.dartrack.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.Executors

/**
 * Thin, crash-proof, fully-offline wrapper that plays procedurally synthesized
 * sound effects (via [AudioTrack]) and short haptics (via [Vibrator]) for game
 * feedback events.
 *
 * Design notes (mirrors [com.dartrack.ui.game.Caller]):
 *  - No assets, no third-party libraries, no network: all audio is synthesized
 *    in code by [ToneSynth], so there is no cost and nothing to license.
 *  - No INTERNET permission is needed. Haptics use the standard VIBRATE
 *    permission; the wiring engineer must add the following line to
 *    AndroidManifest.xml (this class intentionally does NOT edit the manifest):
 *
 *        <uses-permission android:name="android.permission.VIBRATE"/>
 *
 *  - Every failure path degrades silently to a no-op. A device with no
 *    vibrator, no audio output, or a flaky AudioTrack can never crash the game.
 *  - Audio playback runs on a small background executor so the UI thread is
 *    never blocked; each effect uses a short-lived MODE_STATIC AudioTrack that
 *    is released as soon as it finishes.
 */
class Feedback internal constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var released: Boolean = false

    /** Single background thread for non-blocking AudioTrack work. */
    private val audioExecutor = try {
        Executors.newSingleThreadExecutor()
    } catch (t: Throwable) {
        null
    }

    private val vibrator: Vibrator? = obtainVibrator(appContext)

    private fun obtainVibrator(ctx: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Play feedback for [event]. Sound is emitted when [soundOn] is true and
     * haptics when [hapticsOn] is true. Both are independent and best-effort:
     * any failure is swallowed and becomes a silent no-op.
     */
    fun play(event: FeedbackEvent, soundOn: Boolean, hapticsOn: Boolean) {
        if (released) return
        if (soundOn) playSound(event)
        if (hapticsOn) playHaptic(event)
    }

    // ---- Sound -------------------------------------------------------------

    /** Additional master scaling applied on top of the per-tone volumes. */
    private val masterVolume = 0.6f

    private fun playSound(event: FeedbackEvent) {
        val exec = audioExecutor ?: return
        try {
            exec.execute {
                if (released) return@execute
                try {
                    val pcm = ToneSynth.pcm16(specFor(event), SAMPLE_RATE)
                    if (pcm.isEmpty()) return@execute
                    playPcm(pcm)
                } catch (t: Throwable) {
                    // Synthesis/playback failure: silent no-op.
                }
            }
        } catch (t: Throwable) {
            // Executor rejected the task (e.g. shutting down): no-op.
        }
    }

    private fun playPcm(pcm: ShortArray) {
        val sizeBytes = pcm.size * 2
        var track: AudioTrack? = null
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            track = AudioTrack(
                attributes,
                format,
                sizeBytes,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return
            }

            try {
                track.setVolume(masterVolume)
            } catch (t: Throwable) {
                // Volume control optional; ignore.
            }

            track.write(pcm, 0, pcm.size)
            track.play()

            // MODE_STATIC buffer is fully written; wait for the (very short)
            // clip to finish on this background thread, then release.
            val durationMs = (pcm.size.toLong() * 1000L / SAMPLE_RATE) + 30L
            try {
                Thread.sleep(durationMs)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } catch (t: Throwable) {
            // Any AudioTrack failure degrades to silence.
        } finally {
            try {
                track?.stop()
            } catch (t: Throwable) {
                // ignore
            }
            try {
                track?.release()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    // ---- Haptics -----------------------------------------------------------

    private fun playHaptic(event: FeedbackEvent) {
        val vib = vibrator ?: return
        try {
            if (!vib.hasVibrator()) return
        } catch (t: Throwable) {
            return
        }
        try {
            val effect = vibrationFor(event) ?: return
            vib.vibrate(effect)
        } catch (t: Throwable) {
            // No vibrator / permission missing / OEM quirk: silent no-op.
        }
    }

    /**
     * Map an event to a short [VibrationEffect]. Light tick for confirmations,
     * a stronger longer buzz for [FeedbackEvent.BUST], and a double-buzz
     * celebration for [FeedbackEvent.GAME_WIN].
     */
    private fun vibrationFor(event: FeedbackEvent): VibrationEffect? = try {
        when (event) {
            FeedbackEvent.SCORE_CONFIRM,
            FeedbackEvent.TURN_CHANGE,
            FeedbackEvent.BOT_CUE,
            -> VibrationEffect.createOneShot(15L, 80)

            FeedbackEvent.BIG_SCORE,
            FeedbackEvent.LEG_WIN,
            -> VibrationEffect.createOneShot(40L, 160)

            FeedbackEvent.LIFE_LOST -> VibrationEffect.createOneShot(60L, 200)

            FeedbackEvent.BUST -> VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE)

            FeedbackEvent.GAME_WIN -> VibrationEffect.createWaveform(
                longArrayOf(0L, 60L, 80L, 60L),
                intArrayOf(0, 200, 0, 200),
                -1,
            )
        }
    } catch (t: Throwable) {
        null
    }

    /** Free held resources. Safe to call multiple times. */
    fun release() {
        released = true
        try {
            audioExecutor?.shutdownNow()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            vibrator?.cancel()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44100
    }
}

/**
 * Remembers a [Feedback] tied to the current composition. It is created on
 * first composition and released in [DisposableEffect]'s onDispose, so it
 * follows the screen's lifecycle and never leaks (mirrors `rememberCaller`).
 */
@Composable
fun rememberFeedback(): Feedback {
    val context = LocalContext.current
    val feedback = remember { Feedback(context) }
    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }
    return feedback
}
