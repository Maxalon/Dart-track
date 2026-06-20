package com.dartrack.data

import android.content.Context
import com.dartrack.model.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * Single-file JSON repository for the registered-player registry, mirroring
 * [GameRepository]'s structure (singleton [get], a [players] StateFlow, a
 * [Mutex] guarding an atomic temp-file + rename write via [persistLocked],
 * and reusing [GameJson.format] for serialization).
 *
 * Uniqueness rule: player names are unique case-insensitively after trimming.
 * [addPlayer] is therefore "get-or-create": if a player with the same
 * normalized name already exists it is RETURNED unchanged rather than creating
 * a duplicate. Blank names are rejected with [IllegalArgumentException].
 */
class PlayerRepository private constructor(private val file: File) {

    private val mutex = Mutex()
    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) {
                _players.value = emptyList()
                return@withLock
            }
            val text = runCatching { file.readText() }.getOrDefault("")
            if (text.isBlank()) {
                _players.value = emptyList()
                return@withLock
            }
            val parsed = runCatching {
                GameJson.format.decodeFromString(
                    ListSerializer(Player.serializer()), text
                )
            }.getOrDefault(emptyList())
            _players.value = sortByName(parsed)
        }
    }

    /**
     * Adds a player by name, or returns the existing one if a player with the
     * same name (case-insensitive, trimmed) already exists ("get-or-create").
     * Trims [name]; rejects blank with [IllegalArgumentException]; assigns a
     * fresh unique UUID; persists and updates [players] (sorted by name).
     */
    suspend fun addPlayer(name: String): Player = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _players.value
            val outcome = resolveAddPlayer(current, name) { UUID.randomUUID().toString() }
            if (outcome.changed) persistLocked(outcome.players)
            outcome.added
        }
    }

    fun byId(id: String): Player? = _players.value.firstOrNull { it.id == id }

    /** Snapshot of the registry, sorted by name, for selection UIs. */
    fun all(): List<Player> = _players.value

    private fun persistLocked(list: List<Player>) {
        val sorted = sortByName(list)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(
            GameJson.format.encodeToString(
                ListSerializer(Player.serializer()), sorted
            )
        )
        if (!tmp.renameTo(file)) {
            // Fallback: overwrite
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _players.value = sorted
    }

    companion object {
        @Volatile private var instance: PlayerRepository? = null
        fun get(context: Context): PlayerRepository =
            instance ?: synchronized(this) {
                instance ?: PlayerRepository(
                    File(context.applicationContext.filesDir, "players.json")
                ).also { instance = it }
            }
    }
}

// ---------------------------------------------------------------------------
// Pure, Android-free logic (unit-testable without a Context or file IO).
// ---------------------------------------------------------------------------

/** Normalized comparison key for a player name: trimmed, lowercased. */
fun normalizePlayerName(name: String): String = name.trim().lowercase()

/** Result of attempting to add a player to an existing list. */
data class AddPlayerOutcome(
    val players: List<Player>,
    val added: Player,
    /** True if a new player was created (the list/store changed). */
    val changed: Boolean,
)

/**
 * Pure get-or-create logic for the registry. Trims [name] and rejects blank.
 * If a player with the same normalized name already exists, returns it with
 * [AddPlayerOutcome.changed] = false and the list unchanged. Otherwise creates
 * a new player using [idGenerator], retrying until the id is unique within
 * [existing], appends it, and returns the new list with changed = true.
 */
fun resolveAddPlayer(
    existing: List<Player>,
    name: String,
    idGenerator: () -> String,
): AddPlayerOutcome {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty()) { "Player name must not be blank" }
    val key = normalizePlayerName(trimmed)
    val match = existing.firstOrNull { normalizePlayerName(it.name) == key }
    if (match != null) {
        return AddPlayerOutcome(players = existing, added = match, changed = false)
    }
    val usedIds = existing.mapTo(HashSet()) { it.id }
    var id = idGenerator()
    while (id in usedIds) id = idGenerator()
    val created = Player(id = id, name = trimmed)
    return AddPlayerOutcome(players = existing + created, added = created, changed = true)
}

/** Stable ordering for the registry: by name (case-insensitive), then id. */
fun sortByName(players: List<Player>): List<Player> =
    players.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
