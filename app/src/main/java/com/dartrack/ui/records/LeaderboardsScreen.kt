package com.dartrack.ui.records

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dartrack.data.AccountRepository
import com.dartrack.data.FriendStatsRepository
import com.dartrack.data.FriendsRepository
import com.dartrack.data.GameRepository
import com.dartrack.data.LeaderboardCategory
import com.dartrack.data.LeaderboardEntry
import com.dartrack.data.PlayerRepository
import com.dartrack.data.allLeaderboards
import com.dartrack.data.friendStatsFor
import com.dartrack.data.friendsFor
import com.dartrack.data.leaderboardInputs
import com.dartrack.data.statsSubjects

/**
 * Cross-player leaderboards & records. Reads the same [GameRepository] /
 * [PlayerRepository] singletons as the rest of the stats flow and computes every
 * ranking purely via [allLeaderboards] — Compose stays free of metric math, it
 * only formats the already-ranked, already-formatted [LeaderboardEntry] rows.
 *
 * One [Card] per [LeaderboardCategory] (iterated via `values()` for a stable,
 * catalog order). A category with no qualifying entries shows a muted placeholder
 * rather than being hidden, so the board reads the same regardless of data.
 *
 * Who's ranked follows [statsSubjects]; logged in, friends are ranked on the
 * all-time stats snapshot they shared ([leaderboardInputs]).
 */
@Composable
fun LeaderboardsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val gameRepo = remember { GameRepository.get(context) }
    val playerRepo = remember { PlayerRepository.get(context) }

    val games by gameRepo.games.collectAsState()
    val allPlayers by playerRepo.players.collectAsState()
    val accountRepo = remember { AccountRepository.get(context) }
    val friendsRepo = remember { FriendsRepository.get(context) }
    val friendStatsRepo = remember { FriendStatsRepository.get(context) }
    val account by accountRepo.account.collectAsState()
    val friendsCache by friendsRepo.cache.collectAsState()
    val friendStatsCache by friendStatsRepo.cache.collectAsState()
    LaunchedEffect(account?.userId) {
        friendStatsRepo.load()
        if (account != null) friendStatsRepo.refresh(friendsFor(friendsRepo.cache.value, account).friends.map { it.id })
    }
    val inputs = remember(allPlayers, account, friendsCache, friendStatsCache) {
        leaderboardInputs(
            statsSubjects(allPlayers, account, friendsFor(friendsCache, account).friends),
            friendStatsFor(friendStatsCache, account?.userId),
        )
    }
    val players = inputs.players

    // All rankings recomputed only when the inputs change; pure, no Context needed.
    val boards = remember(games, inputs) { allLeaderboards(games, players, statsOverride = inputs.statsOverride) }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Leaderboards",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(4.dp))

        if (players.isEmpty() || (games.isEmpty() && inputs.statsOverride.isEmpty())) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No games yet. Play some games to build the leaderboards.",
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
            LeaderboardCategory.values().forEach { category ->
                CategoryCard(category, boards[category].orEmpty())
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CategoryCard(category: LeaderboardCategory, entries: List<LeaderboardEntry>) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                category.label,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            if (entries.isEmpty()) {
                Text(
                    "— Not enough data",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            entries.forEach { entry ->
                EntryRow(entry)
            }
        }
    }
}

@Composable
private fun EntryRow(entry: LeaderboardEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#${entry.rank}",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(entry.playerName, modifier = Modifier.weight(1f))
        Text(entry.display, fontWeight = FontWeight.SemiBold)
    }
}
