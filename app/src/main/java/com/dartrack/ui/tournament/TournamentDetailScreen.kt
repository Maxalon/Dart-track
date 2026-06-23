package com.dartrack.ui.tournament

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
import androidx.compose.foundation.layout.width
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
import com.dartrack.data.GameRepository
import com.dartrack.data.Standing
import com.dartrack.data.TournamentMatch
import com.dartrack.data.TournamentRepository
import com.dartrack.data.TournamentState
import com.dartrack.data.buildMatchGameRecord
import com.dartrack.data.champions
import com.dartrack.data.isComplete
import com.dartrack.data.linkMatchGame
import com.dartrack.data.matchGameId
import com.dartrack.data.nextUnplayedMatch
import com.dartrack.data.standings
import com.dartrack.data.syncedWith
import kotlinx.coroutines.launch

/**
 * Detail view for a single tournament: champion banner (when finished), a
 * standings table, and the fixtures grouped by round with a Play/Resume button
 * per match. Starting a match lazily creates its [com.dartrack.data.GameRecord]
 * (deterministic id via [matchGameId]) and links it to the match, then hands the
 * game id to [onPlayMatch] to open the live game screen.
 *
 * On load it reconciles finished games back into the tournament via
 * [syncedWith], persisting only when something actually changed, so results show
 * up here as soon as a match's game finishes. Mirrors LeaderboardsScreen's shell
 * (Column + statusBarsPadding, header Row + Back, Card tables).
 */
@Composable
fun TournamentDetailScreen(
    tournamentId: String,
    onBack: () -> Unit,
    onPlayMatch: (gameId: String) -> Unit,
) {
    val context = LocalContext.current
    val tournamentRepo = remember { TournamentRepository.get(context) }
    val gameRepo = remember { GameRepository.get(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { tournamentRepo.load() }
    LaunchedEffect(Unit) { gameRepo.load() }
    val tournaments by tournamentRepo.tournaments.collectAsState()
    val games by gameRepo.games.collectAsState()

    val tournament = tournaments.firstOrNull { it.id == tournamentId }

    // Fold any finished, linked games back into the tournament. Only persist when
    // the sync actually changed something so we don't loop on a stable state.
    LaunchedEffect(games, tournamentId) {
        val current = tournaments.firstOrNull { it.id == tournamentId } ?: return@LaunchedEffect
        val synced = current.syncedWith(games)
        if (synced != current) scope.launch { tournamentRepo.upsert(synced) }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tournament?.name ?: "Tournament",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back") }
        }

        if (tournament == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Tournament not found.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // Start (or resume) the game for [match]; creates+links the record the
        // first time, then opens it. Resume just re-opens the existing record.
        val play: (TournamentMatch) -> Unit = { match ->
            val gid = matchGameId(tournament.id, match.id)
            val existing = games.firstOrNull { it.id == gid }
            if (existing == null) {
                val rec = buildMatchGameRecord(tournament, match, gid, System.currentTimeMillis())
                scope.launch {
                    gameRepo.upsert(rec)
                    tournamentRepo.upsert(linkMatchGame(tournament, match.id, gid))
                }
            }
            onPlayMatch(gid)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Text(
                modeLabel(tournament.mode),
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isComplete(tournament)) {
                val names = champions(tournament).joinToString(" · ") {
                    tournament.competitors[it].name
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("🏆 Champion", fontWeight = FontWeight.Bold)
                        Text(names, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    }
                }
            } else {
                val next = nextUnplayedMatch(tournament)
                if (next != null) {
                    OutlinedButton(
                        onClick = { play(next) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) { Text("Play next match") }
                }
            }

            StandingsCard(tournament)
            FixturesCard(tournament, onPlay = play)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StandingsCard(tournament: TournamentState) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Standings",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            standings(tournament).forEachIndexed { rank, row ->
                StandingRow(rank + 1, row, tournament)
            }
        }
    }
}

@Composable
private fun StandingRow(rank: Int, row: Standing, tournament: TournamentState) {
    val competitor = tournament.competitors[row.competitorIndex]
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#$rank",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(competitor.name + if (competitor.isBot) " (CPU)" else "")
            Text(
                "P:${row.played} W:${row.won} D:${row.drawn} L:${row.lost}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("${row.points} pts", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FixturesCard(
    tournament: TournamentState,
    onPlay: (TournamentMatch) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Fixtures",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            // Matches are emitted in round order; group them so each round gets a
            // header. The map preserves insertion (round) order.
            val byRound = tournament.matches.groupBy { it.round }
            byRound.forEach { (round, matches) ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "Round ${round + 1}",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                matches.forEach { match ->
                    FixtureRow(match, tournament, onPlay)
                }
            }
        }
    }
}

@Composable
private fun FixtureRow(
    match: TournamentMatch,
    tournament: TournamentState,
    onPlay: (TournamentMatch) -> Unit,
) {
    val home = tournament.competitors[match.homeIndex].name
    val away = tournament.competitors[match.awayIndex].name
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$home vs $away", modifier = Modifier.weight(1f))
        if (match.played) {
            val outcome = when (match.winnerIndex) {
                null -> "Draw"
                else -> tournament.competitors[match.winnerIndex].name
            }
            Text(outcome, fontWeight = FontWeight.SemiBold)
        } else {
            // "Resume" once a game has been created for this match, else "Play".
            val label = if (match.gameId != null) "Resume" else "Play"
            TextButton(onClick = { onPlay(match) }) { Text(label) }
        }
    }
}
