package com.dartrack.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dartrack.data.GameRepository
import com.dartrack.data.StatsAggregator
import com.dartrack.data.TrendStats

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { GameRepository.get(context) }
    val games by repo.games.collectAsState()
    val stats = remember(games) { StatsAggregator.aggregate(games) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Statistics", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(4.dp))
        if (stats.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No games yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(stats, key = { it.name }) { s ->
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(s.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Games: ${s.gamesPlayed} · wins: ${s.gamesWon}")
                            if (s.x01GamesPlayed > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "X01",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text("${s.x01GamesPlayed} games · " +
                                    "avg ${"%.1f".format(s.x01ThreeDartAvg)} · " +
                                    "first 9 ${"%.1f".format(s.x01FirstNineAvg)}")
                                Text("Best turn ${s.x01HighestTurn} · " +
                                    "best checkout ${s.x01HighestCheckout}")
                                Text("100+: ${s.x01TonPlus} · " +
                                    "140+: ${s.x01OneFortyPlus} · " +
                                    "180: ${s.x01OneEighties}")
                                Text("Checkout %: ${"%.1f".format(s.x01CheckoutPct * 100)}% " +
                                    "(${s.x01CheckoutHits}/${s.x01CheckoutAttempts})")
                                val bestLeg = if (s.x01BestLegDarts > 0)
                                    "${s.x01BestLegDarts} darts" else "—"
                                Text("Best leg: $bestLeg · " +
                                    "avg darts/leg ${"%.1f".format(s.x01AvgDartsPerLeg)}")

                                val trend = remember(games, s.name) {
                                    TrendStats.threeDartAverageTrend(games, s.name)
                                }
                                if (trend.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "3-dart average trend",
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    TrendChart(
                                        points = trend,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            if (s.cricketGamesPlayed > 0) {
                                Text("Cricket: ${s.cricketGamesPlayed} games · " +
                                    "wins ${s.cricketGamesWon}")
                            }
                            if (s.halfItGamesPlayed > 0) {
                                Text("Half-It: ${s.halfItGamesPlayed} games · " +
                                    "high score ${s.halfItHighScore}")
                            }
                        }
                    }
                }
            }
        }
    }
}
