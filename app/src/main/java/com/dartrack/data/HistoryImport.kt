package com.dartrack.data

import kotlinx.serialization.builtins.ListSerializer

/**
 * Result of an import: how many records were newly added vs. skipped as
 * duplicates (an id that already existed in the repository).
 */
data class ImportResult(val imported: Int, val skipped: Int)

/**
 * Restores game history from a JSON backup produced by [HistoryExport].
 *
 * The round-trip is exact: this parses with the *same* canonical
 * [GameJson.format] config the repository persists with (same
 * `classDiscriminator = "type"`, same `ignoreUnknownKeys`) and the same
 * `ListSerializer(GameRecord.serializer())` shape that export wrote. The
 * pretty-printing used on export does not change the parsed structure, so a
 * file written by [HistoryExport.toJson] decodes cleanly here. We deliberately
 * reuse [GameJson.format] rather than forking a second, divergent Json setup.
 */
object HistoryImport {

    /**
     * Parses a history JSON string into a list of [GameRecord].
     *
     * Throws [kotlinx.serialization.SerializationException] (or
     * [IllegalArgumentException]) on malformed input — callers should wrap this
     * in a try/catch (or `runCatching`) and surface an error to the user rather
     * than letting it crash.
     */
    fun parse(json: String): List<GameRecord> =
        GameJson.format.decodeFromString(
            ListSerializer(GameRecord.serializer()), json
        )

    /**
     * Parses [json] and merges the records into [repo] by id (see
     * [GameRepository.importRecords] for the merge + atomic persist semantics).
     *
     * Parsing failures propagate as exceptions so the UI can show an error.
     */
    suspend fun importInto(repo: GameRepository, json: String): ImportResult {
        val records = parse(json)
        return repo.importRecords(records)
    }
}
