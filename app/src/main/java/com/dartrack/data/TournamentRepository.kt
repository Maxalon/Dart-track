package com.dartrack.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single-file JSON repository for round-robin tournaments, mirroring
 * [GameRepository] / [PlayerRepository]'s structure exactly (singleton [get], a
 * [tournaments] StateFlow, a [Mutex] guarding an atomic temp-file + rename write
 * via [persistLocked], and reusing the versioned-store codec from Tournament.kt).
 *
 * The store is a versioned envelope ([TournamentStore]); [decodeTournamentStore]
 * applies the WIPE policy (blank / corrupt / older-schema data decodes to an
 * empty list and is rewritten as a fresh current store, exactly like games.json).
 * For personal use this scales fine into the hundreds of tournaments.
 */
class TournamentRepository private constructor(private val file: File) {

    private val mutex = Mutex()
    private val _tournaments = MutableStateFlow<List<TournamentState>>(emptyList())
    val tournaments: StateFlow<List<TournamentState>> = _tournaments.asStateFlow()

    /**
     * Loads the tournaments store. If the on-disk data is corrupt or from an
     * older schema, [decodeTournamentStore] yields an empty list and we OVERWRITE
     * the file with a fresh, current-version store so the legacy data is gone and
     * never re-read. Decode never throws, so old data cannot crash us. The
     * StateFlow is ordered newest-created first.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) {
                _tournaments.value = emptyList()
                return@withLock
            }
            val text = runCatching { file.readText() }.getOrDefault("")
            val parsed = decodeTournamentStore(text)
            // If we read nothing usable but the file held *something*, it was
            // incompatible/corrupt -> rewrite a clean current store so the wipe is
            // durable. Reuses the atomic write path.
            if (parsed.isEmpty() && text.isNotBlank()) {
                runCatching { persistLocked(emptyList()) }
            } else {
                _tournaments.value = parsed.sortedByDescending { it.createdAtEpochMs }
            }
        }
    }

    /** Inserts [t], or replaces the existing tournament with the same id. */
    suspend fun upsert(t: TournamentState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _tournaments.value.toMutableList()
            val idx = list.indexOfFirst { it.id == t.id }
            if (idx >= 0) list[idx] = t else list.add(t)
            persistLocked(list)
        }
    }

    /** Removes the tournament [id] from the store and persists. No-op if absent. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _tournaments.value.filterNot { it.id == id }
            persistLocked(list)
        }
    }

    private fun persistLocked(list: List<TournamentState>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encodeTournamentStore(list))
        if (!tmp.renameTo(file)) {
            // Fallback: overwrite
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _tournaments.value = list.sortedByDescending { it.createdAtEpochMs }
    }

    companion object {
        @Volatile private var instance: TournamentRepository? = null
        fun get(context: Context): TournamentRepository =
            instance ?: synchronized(this) {
                instance ?: TournamentRepository(
                    File(context.applicationContext.filesDir, "tournaments.json")
                ).also { instance = it }
            }
    }
}
