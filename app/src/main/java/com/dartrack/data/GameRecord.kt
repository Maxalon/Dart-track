package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GameState
import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BaseballState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CountUpState
import com.dartrack.model.CheckoutTrainerState
import com.dartrack.model.CricketState
import com.dartrack.model.GolfState
import com.dartrack.model.GotchaState
import com.dartrack.model.HalfItState
import com.dartrack.model.KillerState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persisted record of a (possibly in-progress) game. The polymorphic
 * [state] field uses kotlinx.serialization's sealed-interface support.
 */
@Serializable
data class GameRecord(
    val id: String,
    val mode: GameMode,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val state: GameState,
) {
    val isFinished: Boolean get() = state.isFinished
    val playerNames: List<String> get() = state.players.map { it.name }
    val winnerNames: List<String> get() = state.winnerIndices.map { state.players[it].name }
}

object GameJson {
    val format: Json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
}

/**
 * Versioned on-disk envelope for the games store.
 *
 * The move to id-based players is a HARD schema break: old games were
 * name-only and are NOT migrated (product decision: wipe existing history).
 * To honor that without crashing on legacy data we persist games inside this
 * envelope with an explicit [schemaVersion]. On load:
 *  - The legacy format was a bare JSON array of GameRecord (no envelope), which
 *    cannot decode into this object and therefore reads as "incompatible".
 *  - Any envelope whose [schemaVersion] is older than [GameStore.SCHEMA_VERSION]
 *    is also treated as incompatible.
 * In both cases [decodeGameStore] returns an empty list and the caller
 * overwrites the file with a fresh, current-version store. See
 * [decodeGameStore] for the pure (Android-free) decode logic.
 */
@Serializable
data class GameStore(
    val schemaVersion: Int = 0,
    val games: List<GameRecord> = emptyList(),
) {
    companion object {
        /**
         * Bump whenever a non-migratable schema break lands. Version 2 is the
         * id-based-player era; anything earlier (including the unversioned
         * legacy bare-array format) is discarded on load.
         */
        const val SCHEMA_VERSION = 2
    }
}

/**
 * Pure, Android-free decode of the games store from raw file text. Returns the
 * games to surface in the StateFlow. Returns an empty list when the text is
 * blank, fails to parse, or comes from an older/legacy schema (the WIPE path).
 * Never throws: any decode failure is swallowed and treated as "start empty".
 */
fun decodeGameStore(text: String): List<GameRecord> {
    if (text.isBlank()) return emptyList()
    val store = runCatching {
        GameJson.format.decodeFromString(GameStore.serializer(), text)
    }.getOrNull() ?: return emptyList() // legacy bare-array or corrupt -> wipe
    if (store.schemaVersion < GameStore.SCHEMA_VERSION) return emptyList() // old schema -> wipe
    return store.games
}

/** Pure encode of the current-version games store to file text. */
fun encodeGameStore(games: List<GameRecord>): String =
    GameJson.format.encodeToString(
        GameStore.serializer(),
        GameStore(schemaVersion = GameStore.SCHEMA_VERSION, games = games),
    )

fun GameState.toMode(): GameMode = when (this) {
    is X01State -> GameMode.X01
    is CricketState -> GameMode.CRICKET
    is HalfItState -> GameMode.HALF_IT
    is AroundTheClockState -> GameMode.AROUND_CLOCK
    is BobsTwentySevenState -> GameMode.BOBS_27
    is ShanghaiState -> GameMode.SHANGHAI
    is Catch40State -> GameMode.CATCH_40
    is CountUpState -> GameMode.COUNT_UP
    is CheckoutTrainerState -> GameMode.CHECKOUT_TRAINER
    is BaseballState -> GameMode.BASEBALL
    is GolfState -> GameMode.GOLF
    is GotchaState -> GameMode.GOTCHA
    is KillerState -> GameMode.KILLER
}
