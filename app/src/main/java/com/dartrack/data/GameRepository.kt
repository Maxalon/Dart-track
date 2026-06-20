package com.dartrack.data

import android.content.Context
import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CricketState
import com.dartrack.model.GamePlayer
import com.dartrack.model.GameState
import com.dartrack.model.HalfItState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single-file JSON repository for completed and in-progress games.
 * For personal use this scales fine into the hundreds of games.
 */
class GameRepository private constructor(private val file: File) {

    private val mutex = Mutex()
    private val _games = MutableStateFlow<List<GameRecord>>(emptyList())
    val games: StateFlow<List<GameRecord>> = _games.asStateFlow()

    /**
     * Loads the games store. The store is a versioned envelope ([GameStore]);
     * see [decodeGameStore] for the WIPE policy. If the on-disk data is legacy
     * (the old unversioned bare-array of name-only games), corrupt, or from an
     * older schema, [decodeGameStore] yields an empty list and we OVERWRITE the
     * file with a fresh, current-version store so the legacy data is gone and
     * never re-read. Decode never throws, so old data cannot crash us.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) {
                _games.value = emptyList()
                return@withLock
            }
            val text = runCatching { file.readText() }.getOrDefault("")
            val parsed = decodeGameStore(text)
            // If we read nothing usable but the file held *something*, it was
            // legacy/incompatible/corrupt -> rewrite a clean current store so
            // the wipe is durable. Reuses the atomic write path.
            if (parsed.isEmpty() && text.isNotBlank()) {
                runCatching { persistLocked(emptyList()) }
            } else {
                _games.value = parsed.sortedByDescending { it.updatedAtEpochMs }
            }
        }
    }

    suspend fun upsert(record: GameRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _games.value.toMutableList()
            val idx = list.indexOfFirst { it.id == record.id }
            if (idx >= 0) list[idx] = record else list.add(record)
            persistLocked(list)
        }
    }

    /**
     * Merges [records] into the store by [GameRecord.id]: ids that already
     * exist are skipped (idempotent re-import), new ids are added. The whole
     * merge + persist runs under the same [mutex] and reuses [persistLocked]'s
     * atomic write path, so the on-disk games.json can never be left partially
     * written. Returns how many records were imported vs. skipped.
     */
    suspend fun importRecords(records: List<GameRecord>): ImportResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val list = _games.value.toMutableList()
                val existing = list.mapTo(HashSet()) { it.id }
                var imported = 0
                var skipped = 0
                for (record in records) {
                    if (existing.add(record.id)) {
                        list.add(record)
                        imported++
                    } else {
                        skipped++
                    }
                }
                if (imported > 0) persistLocked(list)
                ImportResult(imported = imported, skipped = skipped)
            }
        }

    /**
     * Reassigns every game record's seats from player [fromId] to [intoId],
     * giving the reassigned seats the canonical [intoName]. Used by player
     * merge: after this, games that referenced [fromId] reference [intoId].
     * Runs under the same [mutex] and persists via the existing atomic
     * [persistLocked] path. Returns how many records changed (0 -> no persist).
     */
    suspend fun reassignPlayer(fromId: String, intoId: String, intoName: String): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = _games.value
                val next = reassignPlayerInRecords(current, fromId, intoId, intoName)
                val changed = next.count { it.changed }
                if (changed > 0) persistLocked(next.map { it.record })
                changed
            }
        }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _games.value.filterNot { it.id == id }
            persistLocked(list)
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        mutex.withLock { persistLocked(emptyList()) }
    }

    private fun persistLocked(list: List<GameRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encodeGameStore(list))
        if (!tmp.renameTo(file)) {
            // Fallback: overwrite
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _games.value = list.sortedByDescending { it.updatedAtEpochMs }
    }

    companion object {
        @Volatile private var instance: GameRepository? = null
        fun get(context: Context): GameRepository =
            instance ?: synchronized(this) {
                instance ?: GameRepository(
                    File(context.applicationContext.filesDir, "games.json")
                ).also { instance = it }
            }
    }
}

// ---------------------------------------------------------------------------
// Pure, Android-free logic for player reassignment (unit-testable).
// ---------------------------------------------------------------------------

/**
 * Returns a copy of this state with its [GameState.players] seat list replaced.
 * The sealed interface exposes `players` read-only, so we dispatch over each
 * concrete state to use its generated `copy`. Used by player merge to retarget
 * seats. Only the seat list (`players`) is touched; all per-player gameplay
 * sub-state (scores etc., keyed positionally) is preserved.
 */
fun GameState.withPlayers(players: List<GamePlayer>): GameState = when (this) {
    is X01State -> copy(players = players)
    is CricketState -> copy(players = players)
    is HalfItState -> copy(players = players)
    is AroundTheClockState -> copy(players = players)
    is BobsTwentySevenState -> copy(players = players)
    is ShanghaiState -> copy(players = players)
    is Catch40State -> copy(players = players)
}

/** A game record paired with whether [reassignPlayerInRecords] changed it. */
data class ReassignedRecord(val record: GameRecord, val changed: Boolean)

/**
 * Pure reassignment: for each record in [records], any seat whose
 * [GamePlayer.id] equals [fromId] is rewritten to `id = intoId, name = intoName`.
 * Records with no matching seat are returned unchanged (and flagged
 * `changed = false`). No-op (all unchanged) when [fromId] == [intoId]. Order is
 * preserved.
 */
fun reassignPlayerInRecords(
    records: List<GameRecord>,
    fromId: String,
    intoId: String,
    intoName: String,
): List<ReassignedRecord> {
    if (fromId == intoId) return records.map { ReassignedRecord(it, changed = false) }
    return records.map { record ->
        val seats = record.state.players
        if (seats.none { it.id == fromId }) {
            ReassignedRecord(record, changed = false)
        } else {
            val newSeats = seats.map { seat ->
                if (seat.id == fromId) seat.copy(id = intoId, name = intoName) else seat
            }
            ReassignedRecord(record.copy(state = record.state.withPlayers(newSeats)), changed = true)
        }
    }
}
