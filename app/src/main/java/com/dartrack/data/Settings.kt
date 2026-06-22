package com.dartrack.data

import kotlinx.serialization.Serializable

/**
 * User-selectable theme. [SYSTEM] follows the device dark/light setting; the
 * others force a mode. Persisted by name via kotlinx.serialization, so renaming
 * a constant is an on-disk schema break (handle in [decodeSettings] if ever).
 */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * All persisted app preferences, stored as a single small JSON document next to
 * the games store (we reuse [GameJson.format] rather than DataStore so the whole
 * app shares one serialization config). Every field has a default so older files
 * that predate a setting decode cleanly (ignoreUnknownKeys + defaults give us
 * forward/backward compatibility — see [decodeSettings]).
 */
@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val voiceCallerDefault: Boolean = false,
    val keepScreenOn: Boolean = true,
    val soundEffects: Boolean = true,
    val haptics: Boolean = true,
    val confirmBeforeDelete: Boolean = true,
    val defaultX01StartScore: Int = 501,
    val defaultDoubleOut: Boolean = true,
)

/**
 * The X01 start scores the UI offers, and the only values [Settings.sanitized]
 * accepts for [Settings.defaultX01StartScore]. Mirrors
 * [com.dartrack.model.X01State.SUPPORTED_STARTS] (the canonical set used by the
 * new-game screen); kept here too so the data layer stays Android-free.
 */
val X01_START_SCORES = listOf(101, 201, 301, 401, 501, 701, 901)

/** The fallback start score, used by [Settings.sanitized] and the default. */
private const val DEFAULT_X01_START_SCORE = 501

/**
 * Return a copy with any out-of-range values clamped/repaired to safe defaults.
 * Pure and total: callers can apply it to anything decoded from disk (or built
 * by hand) and trust the result. Currently the only repairable field is
 * [Settings.defaultX01StartScore]; valid values are preserved unchanged.
 */
fun Settings.sanitized(): Settings =
    if (defaultX01StartScore in X01_START_SCORES) this
    else copy(defaultX01StartScore = DEFAULT_X01_START_SCORE)

/**
 * Pure, Android-free decode of the settings document from raw file text. Returns
 * defaults ([Settings]) when the text is blank, fails to parse, or carries an
 * invalid enum value — it NEVER throws, mirroring [decodeGameStore]: any failure
 * is swallowed and treated as "start from defaults". A successful decode is run
 * through [Settings.sanitized] so out-of-range values can't reach the app.
 */
fun decodeSettings(text: String): Settings {
    if (text.isBlank()) return Settings()
    val settings = runCatching {
        GameJson.format.decodeFromString(Settings.serializer(), text)
    }.getOrNull() ?: return Settings() // garbage / bad enum / corrupt -> defaults
    return settings.sanitized()
}

/** Pure encode of the settings document to file text. */
fun encodeSettings(settings: Settings): String =
    GameJson.format.encodeToString(Settings.serializer(), settings)
