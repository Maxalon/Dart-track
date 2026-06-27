package com.dartrack.feedback

import android.content.Context

/**
 * Process-wide entry point for in-game audio/haptic feedback.
 *
 * The game view-model's single mutate path calls [play] on every applied turn
 * (human or CPU), so all 13 modes get consistent feedback from one place without
 * each screen wiring its own [Feedback]. [MainActivity] [init]s it once with the
 * application context and keeps [soundOn]/[hapticsOn] in sync with the user's
 * settings; until then every call is a silent no-op.
 *
 * All state is @Volatile and every call is best-effort and crash-proof (the
 * underlying [Feedback] swallows all failures), so this is safe to touch from the
 * view-model coroutine without further synchronization.
 */
object GameFeedback {

    @Volatile
    private var feedback: Feedback? = null

    /** Mirrors Settings.soundEffects; updated by [MainActivity]. */
    @Volatile
    var soundOn: Boolean = false

    /** Mirrors Settings.haptics; updated by [MainActivity]. */
    @Volatile
    var hapticsOn: Boolean = false

    /** Build the backing [Feedback] once from the application context. */
    fun init(context: Context) {
        if (feedback != null) return
        synchronized(this) {
            if (feedback == null) {
                feedback = try {
                    Feedback(context.applicationContext)
                } catch (t: Throwable) {
                    null
                }
            }
        }
    }

    /** Play [event] if initialized and at least one channel is enabled. */
    fun play(event: FeedbackEvent) {
        val f = feedback ?: return
        if (!soundOn && !hapticsOn) return
        f.play(event, soundOn, hapticsOn)
    }

    /** Release the backing engine (optional; the singleton normally outlives screens). */
    fun release() {
        feedback?.release()
        feedback = null
    }
}
