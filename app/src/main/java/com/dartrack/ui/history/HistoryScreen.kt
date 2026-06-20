package com.dartrack.ui.history

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dartrack.data.GameRepository
import com.dartrack.data.HistoryExport
import com.dartrack.data.HistoryImport
import com.dartrack.model.GameMode
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onResume: (String) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { GameRepository.get(context) }
    val games by repo.games.collectAsState()
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: error("Could not open file.")
                HistoryImport.importInto(repo, text)
            }
            result.fold(
                onSuccess = { r ->
                    Toast.makeText(
                        context,
                        "Imported ${r.imported} games (${r.skipped} duplicates skipped)",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = {
                    Toast.makeText(
                        context,
                        "Import failed: not a valid history file.",
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("History", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    // OpenDocument takes an array of MIME types; allow JSON with
                    // a wildcard fallback for pickers/providers that mislabel it.
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                },
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = "Import history")
            }
            IconButton(
                onClick = { HistoryExport.shareHistory(context, games) },
                enabled = games.isNotEmpty(),
            ) {
                Icon(Icons.Default.Share, contentDescription = "Export history")
            }
            TextButton(onClick = onBack) { Text("Back") }
        }
        if (games.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No games yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(games, key = { it.id }) { rec ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (rec.isFinished)
                                MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when (rec.mode) {
                                        GameMode.X01 -> "X01"
                                        GameMode.CRICKET -> "Cricket"
                                        GameMode.HALF_IT -> "Half-It"
                                        GameMode.AROUND_CLOCK -> "Around the Clock"
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    df.format(Date(rec.updatedAtEpochMs)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(rec.playerNames.joinToString(" · "))
                            if (rec.isFinished) {
                                Text("Winner: ${rec.winnerNames.joinToString()}",
                                    fontWeight = FontWeight.SemiBold)
                            } else {
                                Text("In progress",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { onOpen(rec.id) }) { Text("Details") }
                                if (!rec.isFinished) {
                                    TextButton(onClick = { onResume(rec.id) }) { Text("Resume") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
