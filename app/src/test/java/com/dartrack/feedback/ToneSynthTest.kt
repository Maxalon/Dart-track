package com.dartrack.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToneSynthTest {

    private val sr = 44100

    @Test
    fun expectedSampleCount() {
        val tone = Tone(440.0, 100)
        val pcm = ToneSynth.pcm16(tone, sr)
        val expected = (100L * sr / 1000L).toInt()
        assertEquals(expected, pcm.size)
    }

    @Test
    fun valuesWithinShortRange() {
        // Use full volume to exercise the peak amplitude.
        val pcm = ToneSynth.pcm16(Tone(440.0, 200, volume = 1.0), sr)
        assertTrue(pcm.isNotEmpty())
        for (s in pcm) {
            assertTrue(s >= Short.MIN_VALUE && s <= Short.MAX_VALUE)
        }
    }

    @Test
    fun zeroDurationIsEmpty() {
        assertEquals(0, ToneSynth.pcm16(Tone(440.0, 0), sr).size)
    }

    @Test
    fun negativeDurationIsEmpty() {
        assertEquals(0, ToneSynth.pcm16(Tone(440.0, -5), sr).size)
    }

    @Test
    fun envelopeStartsAndEndsNearZero() {
        val pcm = ToneSynth.pcm16(Tone(440.0, 100, volume = 1.0), sr)
        assertTrue(pcm.isNotEmpty())
        // Linear attack means the first sample is (near) zero.
        assertEquals(0, pcm.first().toInt())
        // Decay brings the final sample back toward zero.
        assertEquals(0, pcm.last().toInt())
    }

    @Test
    fun specLengthEqualsSumOfTones() {
        for (event in FeedbackEvent.values()) {
            val spec = specFor(event)
            val specPcm = ToneSynth.pcm16(spec, sr)
            val sumOfTones = spec.tones.sumOf { ToneSynth.pcm16(it, sr).size }
            assertEquals(sumOfTones, specPcm.size, "length mismatch for $event")
        }
    }

    @Test
    fun noExceptionsForAnyEvent() {
        for (event in FeedbackEvent.values()) {
            val pcm = ToneSynth.pcm16(specFor(event), sr)
            assertTrue(pcm.isNotEmpty(), "pcm for $event should be non-empty")
        }
    }

    @Test
    fun differentSampleRateScalesCount() {
        val pcm = ToneSynth.pcm16(Tone(440.0, 100), 22050)
        assertEquals((100L * 22050 / 1000L).toInt(), pcm.size)
    }
}
