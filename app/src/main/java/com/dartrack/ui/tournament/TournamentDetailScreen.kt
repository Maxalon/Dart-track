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
import com.dartrack.data.TBD
import com.dartrack.data.TournamentFormat
import com.dartrack.data.TournamentMatch
import com.dartrack.data.TournamentRepository
import com.dartrack.data.TournamentState
import com.dartrack.data.bracketChampion
import com.dartrack.data.buildMatchGameRecord
import com.dartrack.data.champions
import com.dartrack.data.isComplete
import com.dartrack.data.linkMatchGame
import com.dartrack.data.matchGameId
import com.dartrack.data.nextPlayableBracketMatch
import com.dartrack.data.nextUnplayedMatch
import com.dartrack.data.standings
import com.dartrack.data.syncedBracketWith
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
    // the sync actually changed something so we don't loop on a stable state. A
    // knockout advances its tree via syncedBracketWith; a league uses syncedWith.
    LaunchedEffect(games, tournamentId) {
        val current = tournaments.firstOrNull { it.id == tournamentId } ?: return@LaunchedEffect
        val synced = if (current.format == TournamentFormat.SINGLE_ELIMINATION) {
            current.syncedBracketWith(games)
        } else {
            current.syncedWith(games)
        }
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

            if (tournament.format == TournamentFormat.SINGLE_ELIMINATION) {
                // Knockout: champion is the final's winner; the bracket view owns
                // its own "Play next match" (no league standings/fixtures).
                val championName = bracketChampion(tournament)?.let {
                    tournament.competitors[it].name
                }
                if (championName != null) {
                    ChampionBanner(championName)
                }
                BracketView(tournament, onPlay = play)
            } else {
                if (isComplete(tournament)) {
                    val names = champions(tournament).joinToString(" · ") {
                        tournament.competitors[it].name
                    }
                    ChampionBanner(names)
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
            }
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

/** The shared "🏆 Champion" banner Card used by both formats once a winner exists. */
@Composable
private fun ChampionBanner(names: String) {
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
}

/**
 * Knockout bracket view: a "Play next match" shortcut over the first playable match
 * ([nextPlayableBracketMatch]) followed by every match grouped by [TournamentMatch.round]
 * (ascending). Each round gets a header — "Final" for the last round, else "Round N" —
 * and each match is a [BracketMatchRow]. Mirrors the Card styling of the league's
 * FixturesCard and the screen's Play/Resume flow.
 */
@Composable
private fun BracketView(
    tournament: TournamentState,
    onPlay: (TournamentMatch) -> Unit,
) {
    val next = nextPlayableBracketMatch(tournament)
    if (next != null) {
        OutlinedButton(
            onClick = { onPlay(next) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) { Text("Play next match") }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Bracket",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            // Matches are emitted in round order (round 1 first); group them so each
            // round gets a header. The map preserves insertion (round) order.
            val byRound = tournament.matches.groupBy { it.round }
            val lastRound = byRound.keys.maxOrNull()
            byRound.forEach { (round, matches) ->
                Spacer(Modifier.height(8.dp))
                Text(
                    if (round == lastRound) "Final" else "Round $round",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                matches.forEach { match ->
                    BracketMatchRow(match, tournament, onPlay)
                }
            }
        }
    }
}

/**
 * One bracket match: its two slots (a [TBD] slot reads "TBD", else the competitor's
 * name, with the winner marked once [TournamentMatch.played]) and — only when BOTH
 * slots are real competitors and the match is unplayed — a Play/Resume button. A
 * match still awaiting a feeder ([TBD] on either side) shows no action.
 */
@Composable
private fun BracketMatchRow(
    match: TournamentMatch,
    tournament: TournamentState,
    onPlay: (TournamentMatch) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BracketSlot(match.homeIndex, match, tournament)
            BracketSlot(match.awayIndex, match, tournament)
        }
        val bothReal = match.homeIndex != TBD && match.awayIndex != TBD
        if (!match.played && bothReal) {
            // "Resume" once a game has been created for this match, else "Play".
            val label = if (match.gameId != null) "Resume" else "Play"
            TextButton(onClick = { onPlay(match) }) { Text(label) }
        }
    }
}

/**
 * A single bracket slot for competitor [index] in [match]: "TBD" when the slot is
 * still an unresolved feeder ([index] == [TBD]), otherwise the competitor's name.
 * The slot is bold and prefixed "✓" when it is the played match's winner.
 */
@Composable
private fun BracketSlot(index: Int, match: TournamentMatch, tournament: TournamentState) {
    if (index == TBD) {
        Text("TBD", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val isWinner = match.played && match.winnerIndex == index
    Text(
        (if (isWinner) "✓ " else "") + tournament.competitors[index].name,
        fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal,
    )
}
