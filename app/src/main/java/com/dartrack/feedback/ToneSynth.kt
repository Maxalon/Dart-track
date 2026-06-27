package com.dartrack.feedback

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-Kotlin (no Android imports) sine-wave synthesizer.
 *
 * Produces 16-bit signed mono PCM that the Android player can hand straight to
 * an AudioTrack. It is deliberately dependency-free and total: every function
 * returns a valid (possibly empty) [ShortArray] and never throws for any
 * non-negative input.
 */
object ToneSynth {

    /** Peak amplitude before per-tone volume scaling. Leaves headroom < Short.MAX. */
    private const val PEAK = 30000.0

    /** Attack ramp length in milliseconds (linear fade-in to avoid a click). */
    private const val ATTACK_MS = 5.0

    /** Maximum decay ramp length in milliseconds (linear fade-out). */
    private const val DECAY_MS = 15.0

    /**
     * Synthesize a single [tone] into 16-bit mono PCM.
     *
     * A short linear attack (~5ms) and decay (~up to 15ms) envelope is applied
     * to avoid clicks/pops; for very short tones the ramps are shrunk so they
     * never overlap past the buffer. Amplitude is scaled by [Tone.volume]
     * (clamped to 0..1) and clamped to the [Short] range.
     *
     * A 0ms (or negative) duration yields an empty array.
     */
    fun pcm16(tone: Tone, sampleRate: Int = 44100): ShortArray {
        if (tone.durationMs <= 0 || sampleRate <= 0) return ShortArray(0)

        val totalSamples = (tone.durationMs.toLong() * sampleRate / 1000L).toInt()
        if (totalSamples <= 0) return ShortArray(0)

        val volume = min(1.0, max(0.0, tone.volume))
        val amplitude = PEAK * volume

        // Envelope lengths in samples, never exceeding half the buffer each so
        // attack and decay can coexist on short tones.
        val maxRamp = totalSamples / 2
        val attackSamples = min(((ATTACK_MS / 1000.0) * sampleRate).roundToInt(), maxRamp)
        val decaySamples = min(((DECAY_MS / 1000.0) * sampleRate).roundToInt(), maxRamp)

        val out = ShortArray(totalSamples)
        val angularStep = 2.0 * PI * tone.freqHz / sampleRate

        for (i in 0 until totalSamples) {
            var env = 1.0
            if (attackSamples > 0 && i < attackSamples) {
                env = i.toDouble() / attackSamples
            }
            if (decaySamples > 0) {
                val fromEnd = totalSamples - 1 - i
                if (fromEnd < decaySamples) {
                    val decayEnv = fromEnd.toDouble() / decaySamples
                    if (decayEnv < env) env = decayEnv
                }
            }
            val sample = sin(angularStep * i) * amplitude * env
            val clamped = min(Short.MAX_VALUE.toDouble(), max(Short.MIN_VALUE.toDouble(), sample))
            out[i] = clamped.roundToInt().toShort()
        }
        return out
    }

    /**
     * Synthesize a whole [spec] by concatenating its tones' PCM buffers in order.
     * The resulting length equals the sum of the per-tone lengths.
     */
    fun pcm16(spec: SoundSpec, sampleRate: Int = 44100): ShortArray {
        if (spec.tones.isEmpty()) return ShortArray(0)
        val buffers = spec.tones.map { pcm16(it, sampleRate) }
        val total = buffers.sumOf { it.size }
        if (total == 0) return ShortArray(0)
        val out = ShortArray(total)
        var offset = 0
        for (buf in buffers) {
            buf.copyInto(out, offset)
            offset += buf.size
        }
        return out
    }
}
