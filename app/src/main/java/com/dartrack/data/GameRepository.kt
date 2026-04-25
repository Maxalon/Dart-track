package com.dartrack.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Single-file JSON repository for completed and in-progress games.
 * For personal use this scales fine into the hundreds of games.
 */
class GameRepository private constructor(private val file: File) {

    private val mutex = Mutex()
    private val _games = MutableStateFlow<List<GameRecord>>(emptyList())
    val games: StateFlow<List<GameRecord>> = _games.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) {
                _games.value = emptyList()
                return@withLock
            }
            val text = runCatching { file.readText() }.getOrDefault("")
            if (text.isBlank()) {
                _games.value = emptyList()
                return@withLock
            }
            val parsed = runCatching {
                GameJson.format.decodeFromString(
                    ListSerializer(GameRecord.serializer()), text
                )
            }.getOrDefault(emptyList())
            _games.value = parsed.sortedByDescending { it.updatedAtEpochMs }
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
        tmp.writeText(
            GameJson.format.encodeToString(
                ListSerializer(GameRecord.serializer()), list
            )
        )
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
