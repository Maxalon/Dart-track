package com.dartrack.ui.stats

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dartrack.data.AccountRepository
import com.dartrack.data.FriendStatsRepository
import com.dartrack.data.FriendStatsStatus
import com.dartrack.data.FriendsRepository
import com.dartrack.data.GameRepository
import com.dartrack.data.H2HRecord
import com.dartrack.data.StatsSubject
import com.dartrack.data.friendStatsFor
import com.dartrack.data.friendStatsNote
import com.dartrack.data.friendsFor
import com.dartrack.data.statsSubjects
import com.dartrack.data.visibleHeadToHead
import com.dartrack.data.PlayerStatsData
import com.dartrack.data.PlayerRepository
import com.dartrack.data.ScoreBandDistribution
import com.dartrack.data.StatsRange
import com.dartrack.data.filterByRange
import com.dartrack.data.headToHead
import com.dartrack.data.label
import com.dartrack.data.playerStats
import com.dartrack.data.threeDartAvgTrendById
import com.dartrack.model.Player

/**
 * Per-player stats. The players offered follow [statsSubjects]: logged out,
 * every local player; logged in, me, local guests and friends. A friend's
 * numbers are the all-time snapshot they uploaded (fetched on open, cached
 * offline); only the head-to-head between me and them is computed here, from
 * our shared local games.
 */
@Composable
fun PlayerStatsScreen(
    onBack: () -> Unit,
    onOpenAchievements: (playerId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val gameRepo = remember { GameRepository.get(context) }
    val playerRepo = remember { PlayerRepository.get(context) }
    val accountRepo = remember { AccountRepository.get(context) }
    val friendsRepo = remember { FriendsRepository.get(context) }
    val friendStatsRepo = remember { FriendStatsRepository.get(context) }

    val games by gameRepo.games.collectAsState()
    val allPlayers by playerRepo.players.collectAsState()
    val account by accountRepo.account.collectAsState()
    val friendsCache by friendsRepo.cache.collectAsState()
    val friendStatsCache by friendStatsRepo.cache.collectAsState()
    val friends = friendsFor(friendsCache, account).friends
    val subjects = remember(allPlayers, account, friends) { statsSubjects(allPlayers, account, friends) }
    val players = subjects.map { it.player }
    val friendStats = friendStatsFor(friendStatsCache, account?.userId)
    LaunchedEffect(account?.userId) {
        friendStatsRepo.load()
        if (account != null) friendStatsRepo.refresh(friendsFor(friendsRepo.cache.value, account).friends.map { it.id })
    }

    var selectedId by remember { mutableStateOf<String?>(null) }
    // Default to the first player; keep the selection valid as the list changes.
    val selectedSubject: StatsSubject? = remember(subjects, selectedId) {
        subjects.firstOrNull { it.player.id == selectedId } ?: subjects.firstOrNull()
    }
    val selected: Player? = selectedSubject?.player

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Player stats",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { selected?.let { onOpenAchievements(it.id) } },
                enabled = selected != null,
            ) {
                Icon(
                    Icons.Default.EmojiEvents,
                    contentDescription = "Achievements",
                )
            }
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(4.dp))

        if (players.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No players yet. Add players when starting a game.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        PlayerSelector(
            players = players,
            selected = selected,
            onSelect = { selectedId = it.id },
            friendIds = subjects.filter { it.isFriend }.mapTo(HashSet()) { it.player.id },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

        if (selectedSubject?.isFriend == true) {
            val fs = selected?.accountId?.let { friendStats[it] }
            // Head-to-head with a friend: only our shared games, from this phone.
            val meId = account?.mePlayerId
            val h2h = remember(games, selected?.id, meId) {
                headToHead(selected?.id.orEmpty(), games).filter { it.opponentId == meId }
            }
            FriendStatsBody(name = selected!!.name, status = fs?.status, computedAt = fs?.computedAt ?: 0) {
                val snap = fs?.snapshot?.takeIf { fs.status == FriendStatsStatus.OK }
                if (snap != null) {
                    OverallCard(snap.stats)
                    if (snap.stats.x01GamesPlayed > 0) {
                        X01Card(snap.stats)
                        ScoreBandCard(snap.stats.scoreBands)
                    }
                    TrendCard(snap.trend)
                }
                HeadToHeadCard(h2h)
                if (snap != null) ModeSummariesCard(snap.stats)
            }
            return@Column
        }

        var range by remember { mutableStateOf(StatsRange.ALL) }
        RangeSelector(
            selected = range,
            onSelect = { range = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )

        // The filter only changes the INPUT list; every stat function recomputes
        // from the filtered games (calendar-period semantics live in filterByRange).
        val filtered = remember(games, range) {
            filterByRange(games, range, System.currentTimeMillis())
        }
        val stats = remember(filtered, selected?.id) {
            playerStats(selected?.id.orEmpty(), filtered)
        }
        val trend = remember(filtered, selected?.id) {
            threeDartAvgTrendById(selected?.id.orEmpty(), filtered)
        }
        val h2h = remember(filtered, selected?.id, subjects) {
            visibleHeadToHead(headToHead(selected?.id.orEmpty(), filtered), allPlayers, subjects)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            if (stats.gamesPlayed == 0) {
                EmptyPeriodCard()
            }
            OverallCard(stats)
            if (stats.x01GamesPlayed > 0) {
                X01Card(stats)
                ScoreBandCard(stats.scoreBands)
            }
            TrendCard(trend)
            HeadToHeadCard(h2h)
            ModeSummariesCard(stats)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * A friend's stats: a caption (whose snapshot, from when) or the reason none
 * can be shown, then [content] in the usual scrolling column. All-time only —
 * the snapshot carries no per-period data, so there's no range selector.
 */
@Composable
private fun FriendStatsBody(
    name: String,
    status: FriendStatsStatus?,
    computedAt: Long,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        val note = friendStatsNote(name, status)
        val caption = note ?: (
            "All-time stats shared by $name" +
                if (computedAt > 0) " · updated " + java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT,
                ).format(java.util.Date(computedAt)) else ""
            )
        Text(
            caption,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSelector(
    players: List<Player>,
    selected: Player?,
    onSelect: (Player) -> Unit,
    friendIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Player") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            players.forEach { p ->
                DropdownMenuItem(
                    text = { Text(if (p.id in friendIds) "${p.name} · friend" else p.name) },
                    onClick = {
                        onSelect(p)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Day / Month / Year / All-time period selector. Single-choice segmented row;
 * default selection (All-time) is owned by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RangeSelector(
    selected: StatsRange,
    onSelect: (StatsRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranges = StatsRange.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        ranges.forEachIndexed { index, range ->
            SegmentedButton(
                selected = range == selected,
                onClick = { onSelect(range) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ranges.size),
            ) {
                Text(range.label())
            }
        }
    }
}

@Composable
private fun EmptyPeriodCard() {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "No games in this period",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun OverallCard(s: PlayerStatsData) {
    StatsCard("Overall") {
        Text("Games played: ${s.gamesPlayed}")
        Text("Games won: ${s.gamesWon}")
        Text("Win %: ${pct(s.winPct)}")
    }
}

@Composable
private fun X01Card(s: PlayerStatsData) {
    StatsCard("X01") {
        Text("Games: ${s.x01GamesPlayed}")
        Text("3-dart average: ${dec(s.x01ThreeDartAvg)}")
        Text("First-9 average: ${dec(s.x01FirstNineAvg)}")
        Text("Checkout %: ${pct(s.x01CheckoutPct)} (${s.x01CheckoutHits}/${s.x01CheckoutAttempts})")
        Text("180s: ${s.x01OneEighties} · 140+: ${s.x01OneFortyPlus} · 100+: ${s.x01TonPlus}")
        val bestLeg = if (s.x01BestLegDarts > 0) "${s.x01BestLegDarts} darts" else "—"
        Text("Best leg: $bestLeg · avg darts/leg: ${dec(s.x01AvgDartsPerLeg)}")
        Text("Legs: ${s.x01LegsWon} · sets: ${s.x01SetsWon} · matches: ${s.x01MatchesWon}")
    }
}

@Composable
private fun ScoreBandCard(b: ScoreBandDistribution) {
    StatsCard("Score bands (per visit)") {
        if (b.total == 0) {
            Text("No visits recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@StatsCard
        }
        BandHeaderRow()
        Spacer(Modifier.height(2.dp))
        BandRow("No score", b.noScore, b.pctNoScore)
        BandRow("1-19", b.b1to19, b.pct1to19)
        BandRow("20+", b.b20Plus, b.pct20Plus)
        BandRow("40+", b.b40Plus, b.pct40Plus)
        BandRow("60+", b.b60Plus, b.pct60Plus)
        BandRow("80+", b.b80Plus, b.pct80Plus)
        BandRow("100+", b.b100Plus, b.pct100Plus)
        BandRow("120+", b.b120Plus, b.pct120Plus)
        BandRow("140+", b.b140Plus, b.pct140Plus)
        BandRow("160+", b.b160Plus, b.pct160Plus)
        BandRow("180", b.b180, b.pct180)
        Spacer(Modifier.height(4.dp))
        Text(
            "Total visits: ${b.total}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BandHeaderRow() {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("Band", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Count", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("%", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BandRow(label: String, count: Int, fraction: Double) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text("$count", modifier = Modifier.weight(1f))
        Text(pct(fraction), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TrendCard(points: List<com.dartrack.data.TrendPoint>) {
    StatsCard("3-dart average trend") {
        // TrendChart renders its own "No data yet" placeholder when empty.
        TrendChart(points = points, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HeadToHeadCard(records: List<H2HRecord>) {
    StatsCard("Head-to-head") {
        if (records.isEmpty()) {
            Text(
                "No data yet",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@StatsCard
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Opponent", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(2f))
            Text("P", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("W", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("L", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(2.dp))
        records.forEach { rec ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(rec.opponentName, modifier = Modifier.weight(2f))
                Text("${rec.played}", modifier = Modifier.weight(1f))
                Text("${rec.won}", modifier = Modifier.weight(1f))
                Text("${rec.lost}", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeSummariesCard(s: PlayerStatsData) {
    val rows = buildList {
        if (s.cricket.gamesPlayed > 0) {
            add("Cricket" to "${s.cricket.gamesPlayed} games · wins ${s.cricket.gamesWon}")
        }
        if (s.halfIt.gamesPlayed > 0) {
            add("Half-It" to "${s.halfIt.gamesPlayed} games · wins ${s.halfIt.gamesWon} · high ${s.halfIt.best}")
        }
        if (s.bermuda.gamesPlayed > 0) {
            add("Bermuda" to "${s.bermuda.gamesPlayed} games · best ${s.bermuda.best}")
        }
        if (s.aroundTheClock.gamesPlayed > 0) {
            val best = if (s.aroundTheClock.best > 0) "${s.aroundTheClock.best} darts" else "—"
            add("Around the Clock" to "${s.aroundTheClock.gamesPlayed} games · wins ${s.aroundTheClock.gamesWon} · best $best")
        }
        if (s.bobs27.gamesPlayed > 0) {
            add("Bob's 27" to "${s.bobs27.gamesPlayed} games · wins ${s.bobs27.gamesWon} · high ${s.bobs27.best}")
        }
        if (s.shanghai.gamesPlayed > 0) {
            add("Shanghai" to "${s.shanghai.gamesPlayed} games · wins ${s.shanghai.gamesWon} · high ${s.shanghai.best}")
        }
        if (s.catch40.gamesPlayed > 0) {
            add("Catch 40" to "${s.catch40.gamesPlayed} games · wins ${s.catch40.gamesWon} · high ${s.catch40.best}")
        }
        if (s.countUp.gamesPlayed > 0) {
            add("Count-Up" to "${s.countUp.gamesPlayed} games · wins ${s.countUp.gamesWon} · high ${s.countUp.best}")
        }
        if (s.checkoutTrainer.gamesPlayed > 0) {
            add("Checkout Trainer" to "${s.checkoutTrainer.gamesPlayed} games · wins ${s.checkoutTrainer.gamesWon} · best ${s.checkoutTrainer.best} hits")
        }
        if (s.baseball.gamesPlayed > 0) {
            add("Baseball" to "${s.baseball.gamesPlayed} games · wins ${s.baseball.gamesWon} · high ${s.baseball.best}")
        }
        if (s.golf.gamesPlayed > 0) {
            // Golf is lowest-strokes; 0 means no completed result yet.
            val best = if (s.golf.best > 0) "${s.golf.best} strokes" else "—"
            add("Golf" to "${s.golf.gamesPlayed} games · wins ${s.golf.gamesWon} · best $best")
        }
        if (s.gotcha.gamesPlayed > 0) {
            add("Gotcha" to "${s.gotcha.gamesPlayed} games · wins ${s.gotcha.gamesWon}")
        }
        if (s.killer.gamesPlayed > 0) {
            add("Killer" to "${s.killer.gamesPlayed} games · wins ${s.killer.gamesWon}")
        }
    }
    if (rows.isEmpty()) return
    StatsCard("Other modes") {
        rows.forEach { (mode, detail) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(mode, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(detail, modifier = Modifier.weight(2f))
            }
        }
    }
}

/** Format a fraction (0.0..1.0) as a one-decimal percentage string. */
private fun pct(fraction: Double): String = "%.1f%%".format(fraction * 100)

/** Format a double to one decimal place. */
private fun dec(value: Double): String = "%.1f".format(value)
