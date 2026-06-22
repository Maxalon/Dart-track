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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.dartrack.data.GameRepository
import com.dartrack.data.H2HRecord
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

@Composable
fun PlayerStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val gameRepo = remember { GameRepository.get(context) }
    val playerRepo = remember { PlayerRepository.get(context) }

    val games by gameRepo.games.collectAsState()
    val players by playerRepo.players.collectAsState()

    var selectedId by remember { mutableStateOf<String?>(null) }
    // Default to the first player; keep the selection valid as the list changes.
    val selected: Player? = remember(players, selectedId) {
        players.firstOrNull { it.id == selectedId } ?: players.firstOrNull()
    }

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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

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
        val h2h = remember(filtered, selected?.id) {
            headToHead(selected?.id.orEmpty(), filtered)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSelector(
    players: List<Player>,
    selected: Player?,
    onSelect: (Player) -> Unit,
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
                    text = { Text(p.name) },
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
        if (s.checkoutTrainer.gamesPlayed > 0) {
            add("Checkout Trainer" to "${s.checkoutTrainer.gamesPlayed} games · wins ${s.checkoutTrainer.gamesWon} · best ${s.checkoutTrainer.best} hits")
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
