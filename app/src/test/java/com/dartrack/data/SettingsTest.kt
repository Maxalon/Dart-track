package com.dartrack.data

import com.dartrack.model.X01State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the settings model and its safe JSON persistence helpers. These use
 * the SAME [GameJson.format] instance the rest of the app persists with
 * (ignoreUnknownKeys = true), so they guard the on-disk schema and the
 * never-throw decode contract that mirrors [decodeGameStore].
 */
class SettingsTest {

    @Test
    fun defaults_matchSpec() {
        val s = Settings()
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertTrue(s.dynamicColor)
        assertFalse(s.voiceCallerDefault)
        assertTrue(s.keepScreenOn)
        assertTrue(s.soundEffects)
        assertTrue(s.haptics)
        assertTrue(s.confirmBeforeDelete)
        assertEquals(501, s.defaultX01StartScore)
        assertTrue(s.defaultDoubleOut)
    }

    @Test
    fun startScores_matchCanonicalX01Set() {
        // The allowed start scores must stay in lockstep with the model's set.
        assertEquals(listOf(101, 201, 301, 401, 501, 701, 901), X01_START_SCORES)
        assertEquals(X01State.SUPPORTED_STARTS, X01_START_SCORES)
    }

    @Test
    fun encodeDecode_roundTrips() {
        val original = Settings(
            themeMode = ThemeMode.DARK,
            dynamicColor = false,
            voiceCallerDefault = true,
            keepScreenOn = false,
            soundEffects = false,
            haptics = false,
            confirmBeforeDelete = false,
            defaultX01StartScore = 301,
            defaultDoubleOut = false,
        )
        assertEquals(original, decodeSettings(encodeSettings(original)))
    }

    @Test
    fun decode_blank_returnsDefaults() {
        assertEquals(Settings(), decodeSettings(""))
        assertEquals(Settings(), decodeSettings("   \n  "))
    }

    @Test
    fun decode_garbage_returnsDefaultsWithoutThrowing() {
        assertEquals(Settings(), decodeSettings("not json{"))
        assertEquals(Settings(), decodeSettings("[1,2,3]"))
        assertEquals(Settings(), decodeSettings("42"))
    }

    @Test
    fun decode_omittedFields_takeDefaults() {
        // Only one field present; every other field must fall back to its default
        // (forward/backward compatibility via defaults + ignoreUnknownKeys).
        val partial = """{"themeMode":"LIGHT"}"""
        val decoded = decodeSettings(partial)
        assertEquals(ThemeMode.LIGHT, decoded.themeMode)
        // The rest equal a default Settings with only themeMode changed.
        assertEquals(Settings(themeMode = ThemeMode.LIGHT), decoded)
    }

    @Test
    fun decode_unknownExtraKey_isIgnored() {
        val withExtra = """{"themeMode":"DARK","futureFeature":true,"nested":{"x":1}}"""
        val decoded = decodeSettings(withExtra)
        assertEquals(Settings(themeMode = ThemeMode.DARK), decoded)
    }

    @Test
    fun decode_appliesSanitization_outOfRangeStartScore() {
        // A persisted-but-invalid start score (e.g. 500 isn't an offered value)
        // is repaired to the 501 default by the decode path.
        val bad = """{"defaultX01StartScore":500}"""
        assertEquals(501, decodeSettings(bad).defaultX01StartScore)
    }

    @Test
    fun decode_invalidEnum_returnsDefaultsWithoutThrowing() {
        // A hand-written/corrupt enum value must not crash the decode.
        val badEnum = """{"themeMode":"NEON"}"""
        assertEquals(Settings(), decodeSettings(badEnum))
    }

    @Test
    fun sanitized_repairsOutOfRangeStartScore() {
        assertEquals(501, Settings(defaultX01StartScore = 500).sanitized().defaultX01StartScore)
        assertEquals(501, Settings(defaultX01StartScore = 0).sanitized().defaultX01StartScore)
        assertEquals(501, Settings(defaultX01StartScore = -1).sanitized().defaultX01StartScore)
    }

    @Test
    fun sanitized_preservesEveryValidStartScore() {
        for (score in X01_START_SCORES) {
            val s = Settings(defaultX01StartScore = score)
            assertEquals(s, s.sanitized(), "valid start score $score must be preserved")
        }
    }

    @Test
    fun sanitized_leavesOtherFieldsUntouched() {
        // Repairing the start score must not perturb unrelated fields.
        val s = Settings(
            themeMode = ThemeMode.DARK,
            haptics = false,
            defaultX01StartScore = 999, // invalid -> repaired
        )
        val cleaned = s.sanitized()
        assertEquals(501, cleaned.defaultX01StartScore)
        assertEquals(s.copy(defaultX01StartScore = 501), cleaned)
    }
}
