package com.dartrack.data

import kotlinx.serialization.Serializable

/**
 * How the app picks its color scheme.
 *  - [SYSTEM] follows the OS dark-theme setting (isSystemInDarkTheme()).
 *  - [LIGHT] / [DARK] force that scheme regardless of the OS.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * User-tweakable application preferences, persisted as a single JSON object by
 * [SettingsRepository]. Pure and Android-free so it (and its codec below) can be
 * unit-tested without a Context. All fields carry sensible defaults so a missing
 * or partial file decodes to a complete, valid object.
 *
 * Effects are wired incrementally: [themeMode], [dynamicColor] and
 * [keepScreenOn] take effect immediately (see MainActivity / DartTrackTheme);
 * the remaining flags are persisted here and consumed by their owning screens.
 */
@Serializable
data class Settings(
    /** Light / dark / follow-system color scheme selection. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Use Material You dynamic color (Android 12+); falls back to brand colors. */
    val dynamicColor: Boolean = true,
    /** Whether the spoken caller starts enabled on new game screens. */
    val voiceCallerDefault: Boolean = false,
    /** Keep the screen awake while the app is in the foreground. */
    val keepScreenOn: Boolean = false,
    /** Play short sound effects for key actions. */
    val soundEffects: Boolean = true,
    /** Use haptic feedback for key actions. */
    val haptics: Boolean = true,
    /** Ask for confirmation before destructive deletes. */
    val confirmBeforeDelete: Boolean = true,
    /** Pre-selected X01 start score on the new-game screen. */
    val defaultX01StartScore: Int = 501,
    /** Pre-selected "finish on a double" toggle on the new-game screen. */
    val defaultDoubleOut: Boolean = true,
)

/** Allowed X01 start scores offered by the settings/new-game selectors. */
val X01_START_SCORES: List<Int> = listOf(101, 201, 301, 501, 701, 1001)

/**
 * Returns a copy of these settings with any out-of-range field clamped back to a
 * valid value. Currently the only constrained field is [Settings.defaultX01StartScore],
 * which must be one of [X01_START_SCORES]; an unknown value falls back to 501.
 * Pure: no IO, no Context. Applied on every load and before every persist so the
 * on-disk file can never hold an invalid value.
 */
fun Settings.sanitized(): Settings {
    val score = if (defaultX01StartScore in X01_START_SCORES) defaultX01StartScore else 501
    return if (score == defaultX01StartScore) this else copy(defaultX01StartScore = score)
}

private val settingsJson = kotlinx.serialization.json.Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Pure, Android-free decode of settings from raw file text. NEVER throws: blank,
 * corrupt, or partially-unknown text falls back to defaults (and unknown keys are
 * ignored), so an old or damaged file can never crash the app. The result is
 * [sanitized] so out-of-range values are repaired on read.
 */
fun decodeSettings(text: String): Settings {
    if (text.isBlank()) return Settings()
    val parsed = runCatching {
        settingsJson.decodeFromString(Settings.serializer(), text)
    }.getOrNull() ?: return Settings()
    return parsed.sanitized()
}

/** Pure encode of [settings] to file text (compact JSON, defaults included). */
fun encodeSettings(settings: Settings): String =
    settingsJson.encodeToString(Settings.serializer(), settings)
