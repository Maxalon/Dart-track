package com.dartrack.ui.tournament

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.dartrack.data.TournamentRepository
import com.dartrack.data.TournamentState
import com.dartrack.data.champions
import com.dartrack.data.isComplete
import kotlinx.coroutines.launch

/**
 * Lists every saved round-robin tournament (newest-created first, as the
 * [TournamentRepository] already orders its flow). Each row is a [Card] showing
 * the name, mode, match progress, and — once finished — the champion(s); tapping
 * its "Open" opens the detail screen. A "New tournament" button starts the setup
 * flow, and each card carries a "Delete" affordance.
 *
 * Mirrors LeaderboardsScreen / HistoryScreen exactly: a Column + statusBarsPadding
 * shell, a header Row (title + Back), and Card-based rows. No Scaffold/TopAppBar.
 */
@Composable
fun TournamentsScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (id: String) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { TournamentRepository.get(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { repo.load() }
    val tournaments by repo.tournaments.collectAsState()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tournaments",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(4.dp))

        OutlinedButton(
            onClick = onNew,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) { Text("New tournament") }
        Spacer(Modifier.height(8.dp))

        if (tournaments.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No tournaments yet. Create one to get started.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            tournaments.forEach { t ->
                TournamentCard(
                    tournament = t,
                    onOpen = { onOpen(t.id) },
                    onDelete = { scope.launch { repo.delete(t.id) } },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TournamentCard(
    tournament: TournamentState,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val played = tournament.matches.count { it.played }
    val total = tournament.matches.size
    val complete = isComplete(tournament)

    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (complete)
                MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tournament.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    modeLabel(tournament.mode),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("$played/$total matches played")
            if (complete) {
                val names = champions(tournament).joinToString(" · ") {
                    tournament.competitors[it].name
                }
                Text(
                    "Complete · 🏆 $names",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onOpen) { Text("Open") }
            }
        }
    }
}
