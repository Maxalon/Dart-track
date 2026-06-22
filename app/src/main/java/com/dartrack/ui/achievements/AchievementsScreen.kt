package com.dartrack.ui.achievements

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dartrack.data.AchievementStatus
import com.dartrack.data.AchievementSummary

/**
 * Read-only achievements board for a single player. All data is computed by the
 * pure achievements engine (`achievementsFor` / `achievementSummary`) up in the
 * navigation route and passed in here, mirroring how the rest of the stats flow
 * keeps Compose free of computation.
 *
 * [statuses] are rendered in the order the engine emits them (catalog order) and
 * are grouped under their [com.dartrack.data.Achievement.category] header, with
 * categories appearing in first-seen order so the catalog's intended ordering is
 * preserved.
 */
@Composable
fun AchievementsScreen(
    playerName: String,
    statuses: List<AchievementStatus>,
    summary: AchievementSummary,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Achievements",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(4.dp))

        SummaryHeader(
            playerName = playerName,
            summary = summary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(8.dp))

        // Group in first-seen (catalog) order; LinkedHashMap from groupBy keeps it.
        val grouped: Map<String, List<AchievementStatus>> =
            statuses.groupBy { it.achievement.category }

        LazyColumn(
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        ) {
            grouped.forEach { (category, rows) ->
                item(key = "header_$category") {
                    Text(
                        category,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                items(rows, key = { it.achievement.id }) { status ->
                    AchievementRow(status)
                }
            }
        }
    }
}

@Composable
private fun SummaryHeader(
    playerName: String,
    summary: AchievementSummary,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "$playerName · ${summary.unlocked}/${summary.total} unlocked",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            val fraction = if (summary.total > 0) {
                summary.unlocked.toFloat() / summary.total.toFloat()
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AchievementRow(status: AchievementStatus) {
    val unlocked = status.unlocked
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (unlocked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (unlocked) Icons.Default.EmojiEvents else Icons.Default.Lock,
                contentDescription = if (unlocked) "Unlocked" else "Locked",
                tint = if (unlocked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    status.achievement.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    status.achievement.description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Locked, multi-step achievements show how far along the player is.
                if (!unlocked && status.target > 1) {
                    Spacer(Modifier.height(6.dp))
                    val fraction = status.progress.toFloat() / status.target.toFloat()
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${status.progress}/${status.target}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
