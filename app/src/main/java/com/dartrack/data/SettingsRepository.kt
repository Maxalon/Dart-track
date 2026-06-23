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
 * Single-file JSON repository for user [Settings], mirroring [PlayerRepository]
 * and [GameRepository]: a singleton [get], a [settings] StateFlow, a [Mutex]
 * guarding an atomic temp-file + rename write via [persistLocked], reusing the
 * pure [decodeSettings] / [encodeSettings] codec. Decode never throws, so a
 * corrupt or legacy file degrades to defaults rather than crashing.
 */
class SettingsRepository private constructor(private val file: File) {

    private val mutex = Mutex()
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) {
                _settings.value = Settings()
                return@withLock
            }
            val text = runCatching { file.readText() }.getOrDefault("")
            // decodeSettings never throws and sanitizes the result.
            _settings.value = decodeSettings(text)
        }
    }

    /**
     * Applies [transform] to the current settings, [sanitized]s the result,
     * atomically persists it (temp-file + rename), and updates [settings].
     * Runs under [mutex] so concurrent updates can't interleave or corrupt the
     * file. A no-op transform still persists, which is harmless.
     */
    suspend fun update(transform: (Settings) -> Settings): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(_settings.value).sanitized()
            persistLocked(next)
        }
    }

    private fun persistLocked(value: Settings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encodeSettings(value))
        if (!tmp.renameTo(file)) {
            // Fallback: overwrite
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _settings.value = value
    }

    companion object {
        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(
                    File(context.applicationContext.filesDir, "settings.json")
                ).also { instance = it }
            }
    }
}
