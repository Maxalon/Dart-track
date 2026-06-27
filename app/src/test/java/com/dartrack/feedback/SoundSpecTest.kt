package com.dartrack.feedback

import kotlin.test.Test
import kotlin.test.assertTrue

class SoundSpecTest {

    @Test
    fun specForIsTotalAndNonEmpty() {
        for (event in FeedbackEvent.values()) {
            val spec = specFor(event)
            assertTrue(spec.tones.isNotEmpty(), "spec for $event must have tones")
        }
    }

    @Test
    fun allToneFieldsAreSane() {
        for (event in FeedbackEvent.values()) {
            val spec = specFor(event)
            for (tone in spec.tones) {
                assertTrue(tone.freqHz > 0.0, "freq must be > 0 for $event")
                assertTrue(tone.durationMs > 0, "duration must be > 0 for $event")
                assertTrue(
                    tone.volume in 0.0..1.0,
                    "volume must be in 0..1 for $event (was ${tone.volume})",
                )
            }
        }
    }

    @Test
    fun specForNeverThrows() {
        for (event in FeedbackEvent.values()) {
            // Should simply not throw.
            specFor(event)
        }
    }

    @Test
    fun knownSpecsMatchPalette() {
        assertTrue(specFor(FeedbackEvent.SCORE_CONFIRM).tones.size == 1)
        assertTrue(specFor(FeedbackEvent.BIG_SCORE).tones.size == 2)
        assertTrue(specFor(FeedbackEvent.LEG_WIN).tones.size == 3)
        assertTrue(specFor(FeedbackEvent.GAME_WIN).tones.size == 4)

        val bust = specFor(FeedbackEvent.BUST).tones.single()
        assertTrue(bust.freqHz == 150.0 && bust.durationMs == 200 && bust.volume == 0.9)
    }
}
