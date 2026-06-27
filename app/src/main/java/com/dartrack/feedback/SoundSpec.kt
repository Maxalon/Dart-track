package com.dartrack.feedback

/**
 * Pure-Kotlin (no Android imports) description of the procedural audio feedback.
 *
 * This file is intentionally data-only and dependency-free so it can be unit
 * tested on a plain JVM. It never performs IO and nothing here can throw.
 */

/** The discrete moments in a game that can produce audio/haptic feedback. */
enum class FeedbackEvent {
    SCORE_CONFIRM,
    BIG_SCORE,
    BUST,
    TURN_CHANGE,
    BOT_CUE,
    LIFE_LOST,
    LEG_WIN,
    GAME_WIN,
}

/**
 * A single sine tone.
 *
 * @param freqHz frequency in Hz (must be > 0 for an audible tone).
 * @param durationMs length in milliseconds (>= 0; 0 yields silence).
 * @param volume linear amplitude scale in the range 0.0..1.0.
 */
data class Tone(
    val freqHz: Double,
    val durationMs: Int,
    val volume: Double = 1.0,
)

/** An ordered list of [Tone]s played back-to-back. */
data class SoundSpec(val tones: List<Tone>)

/**
 * Total, pure mapping from a [FeedbackEvent] to its [SoundSpec].
 *
 * Volumes here are the per-tone musical balance only; the player applies an
 * additional ~0.6 master scaling so the overall output stays moderate.
 */
fun specFor(event: FeedbackEvent): SoundSpec = when (event) {
    FeedbackEvent.SCORE_CONFIRM -> SoundSpec(
        listOf(Tone(880.0, 45)),
    )
    FeedbackEvent.BIG_SCORE -> SoundSpec(
        listOf(Tone(660.0, 90), Tone(990.0, 90)),
    )
    FeedbackEvent.BUST -> SoundSpec(
        listOf(Tone(150.0, 200, volume = 0.9)),
    )
    FeedbackEvent.TURN_CHANGE -> SoundSpec(
        listOf(Tone(520.0, 30, volume = 0.5)),
    )
    FeedbackEvent.BOT_CUE -> SoundSpec(
        listOf(Tone(700.0, 40), Tone(700.0, 40)),
    )
    FeedbackEvent.LIFE_LOST -> SoundSpec(
        listOf(Tone(600.0, 80), Tone(500.0, 80)),
    )
    FeedbackEvent.LEG_WIN -> SoundSpec(
        listOf(Tone(523.0, 110), Tone(659.0, 110), Tone(784.0, 110)),
    )
    FeedbackEvent.GAME_WIN -> SoundSpec(
        listOf(Tone(523.0, 120), Tone(659.0, 120), Tone(784.0, 120), Tone(1047.0, 120)),
    )
}
