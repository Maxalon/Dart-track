package com.dartrack.data

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export of the full game history to a JSON file, shared via the Android
 * share sheet (ACTION_SEND) backed by a [FileProvider].
 *
 * The serialization reuses the exact same configuration that
 * [GameRepository] persists with ([GameJson.format]) — same
 * `classDiscriminator = "type"`, same `ignoreUnknownKeys`, so the exported
 * polymorphic [com.dartrack.model.GameState] objects carry the identical
 * "type" discriminators as the on-disk games.json. The only difference is
 * [Json.prettyPrint] is enabled for a human-readable backup; copying from the
 * shared instance via `Json(from = ...) { prettyPrint = true }` guarantees we
 * do not fork a second, divergent Json setup.
 */
object HistoryExport {

    /** Pretty-printing view of the canonical [GameJson.format] config. */
    private val exportFormat: Json = Json(from = GameJson.format) {
        prettyPrint = true
    }

    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Serializes the full list of records to a pretty JSON string. */
    fun toJson(records: List<GameRecord>): String =
        exportFormat.encodeToString(
            ListSerializer(GameRecord.serializer()), records
        )

    /**
     * Writes the history JSON to a cache file, obtains a content:// URI via the
     * app's FileProvider and launches an ACTION_SEND chooser.
     *
     * Empty history is handled gracefully: nothing is shared and a short toast
     * informs the user instead of producing an empty/confusing share.
     */
    fun shareHistory(context: Context, records: List<GameRecord>) {
        if (records.isEmpty()) {
            Toast.makeText(context, "No games to export.", Toast.LENGTH_SHORT).show()
            return
        }

        val json = toJson(records)

        val exportDir = File(context.cacheDir, "export")
        exportDir.mkdirs()
        val outFile = File(exportDir, "dart-track-history-${fileStamp.format(Date())}.json")
        outFile.writeText(json)

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, outFile)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, outFile.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendIntent, "Export history").apply {
            // createChooser may start outside an Activity context.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }
}
