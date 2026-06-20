package com.dartrack.ui.game

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Thin, crash-proof wrapper around the device's offline [TextToSpeech] engine
 * so the app can optionally "call" scores aloud.
 *
 * Design notes:
 *  - Uses the on-device TTS engine only: no INTERNET permission and no manifest
 *    changes are required.
 *  - TTS initialization is asynchronous and can fail on some devices (no engine
 *    installed, no voice data, etc.). Every failure path degrades silently to a
 *    no-op so a broken engine can never crash the game.
 *  - [speak] is a no-op until [ready] is true (i.e. onInit succeeded and a
 *    usable language was set).
 */
class Caller internal constructor(context: Context) {

    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var shuttingDown: Boolean = false

    private val tts: TextToSpeech? = try {
        TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS && !shuttingDown) {
                ready = applyLanguage()
            }
        }
    } catch (t: Throwable) {
        // Constructing the engine can throw on misconfigured devices.
        null
    }

    /** Pick a usable language: default locale, then UK, then US. */
    private fun applyLanguage(): Boolean {
        val engine = tts ?: return false
        val candidates = listOf(Locale.getDefault(), Locale.UK, Locale.US)
        for (locale in candidates) {
            val result = try {
                engine.setLanguage(locale)
            } catch (t: Throwable) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Speak [text], flushing anything currently queued. No-op when [enabled] is
     * false, before the engine is ready, or if the engine has been shut down.
     */
    fun speak(text: String, enabled: Boolean = true) {
        if (!enabled || !ready || shuttingDown) return
        val engine = tts ?: return
        if (text.isBlank()) return
        try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dartrack-caller")
        } catch (t: Throwable) {
            // Never let a speech failure bubble into the UI.
        }
    }

    /** Stop speech and release engine resources. Safe to call multiple times. */
    fun shutdown() {
        shuttingDown = true
        ready = false
        try {
            tts?.stop()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            tts?.shutdown()
        } catch (t: Throwable) {
            // ignore
        }
    }
}

/**
 * Remembers a [Caller] tied to the current composition. The engine is lazily
 * created on first composition and shut down in [DisposableEffect]'s onDispose,
 * so it follows the screen's lifecycle and never leaks.
 */
@Composable
fun rememberCaller(): Caller {
    val context = LocalContext.current
    val caller = remember { Caller(context) }
    DisposableEffect(caller) {
        onDispose { caller.shutdown() }
    }
    return caller
}
