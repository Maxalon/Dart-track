package com.dartrack.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dartrack.data.GameRepository
import com.dartrack.model.GameMode
import com.dartrack.model.AROUND_CLOCK_LAST_TARGET
import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BOBS27_LAST_DOUBLE
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CountUpState
import com.dartrack.model.CheckoutTrainerState
import com.dartrack.model.CRICKET_TARGETS
import com.dartrack.model.CricketState
import com.dartrack.model.HalfItState
import com.dartrack.model.SHANGHAI_ROUNDS
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State
import com.dartrack.model.X01Stats
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun GameDetailScreen(
    recordId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { GameRepository.get(context) }
    val games by repo.games.collectAsState()
    val rec = games.firstOrNull { it.id == recordId }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Game details", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        if (rec == null) {
            Text("Game not found.", modifier = Modifier.padding(16.dp))
            return
        }
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(when (rec.mode) {
                    GameMode.X01 -> "X01"
                    GameMode.CRICKET -> "Cricket"
                    GameMode.HALF_IT -> "Half-It"
                    GameMode.AROUND_CLOCK -> "Around the Clock"
                    GameMode.BOBS_27 -> "Bob's 27"
                    GameMode.SHANGHAI -> "Shanghai"
                    GameMode.CATCH_40 -> "Catch 40"
                    GameMode.COUNT_UP -> "Count-Up"
                    GameMode.CHECKOUT_TRAINER -> "Checkout Trainer"
                }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Started: ${df.format(Date(rec.createdAtEpochMs))}")
                Text("Updated: ${df.format(Date(rec.updatedAtEpochMs))}")
                Text("Status: ${if (rec.isFinished) "finished" else "in progress"}")
                if (rec.isFinished) Text("Winner: ${rec.winnerNames.joinToString()}",
                    fontWeight = FontWeight.SemiBold)
            }
        }

        when (val s = rec.state) {
            is X01State -> X01Detail(s)
            is CricketState -> CricketDetail(s)
            is HalfItState -> HalfItDetail(s)
            is AroundTheClockState -> AroundTheClockDetail(s)
            is BobsTwentySevenState -> BobsTwentySevenDetail(s)
            is ShanghaiState -> ShanghaiDetail(s)
            is Catch40State -> Catch40Detail(s)
            is CountUpState -> CountUpDetail(s)
            is CheckoutTrainerState -> CheckoutTrainerDetail(s)
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { confirmDelete = true },
            modifier = Modifier.padding(8.dp)) {
            Text("Delete game", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this game?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.delete(recordId)
                        confirmDelete = false
                        onBack()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun X01Detail(s: X01State) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Start ${s.startScore}${if (s.doubleOut) " · double-out" else ""}" +
                    if (s.isMatch) " · first to ${s.legsToWin} legs" else "",
                fontWeight = FontWeight.SemiBold,
            )
            if (s.isMatch) {
                Text(
                    "Legs: " + s.players.indices.joinToString("  –  ") {
                        s.legsWonBy(it).toString()
                    } + "  (${s.completedLegs.size} completed)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            // Aggregate stats across every leg (completed + current).
            s.players.forEachIndexed { idx, p ->
                val legs = s.allLegStatesFor(idx)
                Text(
                    "${p.name}: avg ${"%.1f".format(X01Stats.threeDartAverage(legs, s.startScore))}" +
                        " · best ${X01Stats.highestTurn(legs)}" +
                        (X01Stats.highestCheckout(legs)?.let { " · checkout $it" } ?: "") +
                        " · darts ${legs.sumOf { it.turns.size } * 3}",
                )
                // Per-leg turn summary so the full history is visible.
                legs.forEachIndexed { legIndex, ps ->
                    val turnSummary = ps.turns.joinToString(" · ") {
                        if (it.bust) "BUST" else it.entered.toString()
                    }
                    if (turnSummary.isNotEmpty()) {
                        Text(
                            (if (s.isMatch) "leg ${legIndex + 1}: " else "") + turnSummary,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CricketDetail(s: CricketState) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            s.players.forEachIndexed { idx, p ->
                val ps = s.perPlayer[idx]
                val cum = ps.cumulativeMarks()
                Text("${p.name}: ${s.scoreFor(idx)} pts", fontWeight = FontWeight.SemiBold)
                Text(
                    CRICKET_TARGETS.joinToString("  ") { t ->
                        val label = if (t == 25) "B" else t.toString()
                        "$label:${cum[t] ?: 0}"
                    },
                    fontSize = 12.sp,
                )
                Text("Turns: ${ps.turns.size}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun AroundTheClockDetail(s: AroundTheClockState) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            s.players.forEachIndexed { idx, p ->
                val ps = s.perPlayer[idx]
                Text(
                    "${p.name}: ${ps.cleared}/$AROUND_CLOCK_LAST_TARGET" +
                        if (ps.finished) " · ${ps.darts} darts" else "",
                    fontWeight = FontWeight.SemiBold,
                )
                if (ps.turns.isNotEmpty()) {
                    Text(
                        ps.turns.joinToString(" · ") { it.hits.toString() },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun BobsTwentySevenDetail(s: BobsTwentySevenState) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            s.players.forEachIndexed { idx, p ->
                val ps = s.perPlayer[idx]
                Text(
                    "${p.name}: ${ps.score}" +
                        (if (ps.out) " · OUT" else "") +
                        " · ${ps.darts} darts",
                    fontWeight = FontWeight.SemiBold,
                )
                if (ps.turns.isNotEmpty()) {
                    Text(
                        "doubles 1–$BOBS27_LAST_DOUBLE hits: " +
                            ps.turns.joinToString(" · ") { it.hits.toString() },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ShanghaiDetail(s: ShanghaiState) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            s.players.forEachIndexed { idx, p ->
                val ps = s.perPlayer[idx]
                Text(
                    "${p.name}: ${ps.total} · ${ps.darts} darts",
                    fontWeight = FontWeight.SemiBold,
                )
                if (ps.turns.isNotEmpty()) {
                    Text(
                        "rounds 1–$SHANGHAI_ROUNDS (s/d/t): " +
                            ps.turns.joinToString(" · ") {
                                "${it.singles}/${it.doubles}/${it.triples}"
                            },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun Catch40Detail(s: Catch40State) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            s.players.forEachIndexed { idx, p ->
                val ps = s.perPlayer[idx]
                Text(
                    "${p.name}: ${ps.score}" +
                        (if (ps.finished) " · CAUGHT D1" else " · on D${ps.doubleNumber}") +
                        " · ${ps.darts} darts",
                    fontWeight = FontWeight.SemiBold,
                )
                if (ps.turns.isNotEmpty()) {
                    Text(
                        "hits per turn: " +
                            ps.turns.joinToString(" · ") { it.hits.toString() },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CountUpDetail(s: CountUpState) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            s.players.forEachIndexed { idx, p ->
                val ps = s.perPlayer[idx]
                Text(
                    "${p.name}: ${ps.total} · ${ps.darts} darts",
                    fontWeight = FontWeight.SemiBold,
                )
                if (ps.turns.isNotEmpty()) {
                    Text(
                        "per round: " + ps.turns.joinToString(" · ") { it.toString() },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CheckoutTrainerDetail(s: CheckoutTrainerState) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "targets: " + s.targets.joinToString(" · "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            s.players.forEachIndexed { idx, p ->
                val ps = s.perPlayer[idx]
                Text(
                    "${p.name}: ${ps.hits} hits · ${ps.dartsOnHits} darts on hits",
                    fontWeight = FontWeight.SemiBold,
                )
                if (ps.attempts.isNotEmpty()) {
                    Text(
                        "attempts (target → result): " +
                            ps.attempts.mapIndexed { i, a ->
                                val tgt = s.targets.getOrNull(i) ?: "?"
                                "$tgt→" + (if (a.hit) "✓${a.darts}" else "✗")
                            }.joinToString(" · "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun HalfItDetail(s: HalfItState) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            s.players.forEachIndexed { idx, p ->
                val ps = s.perPlayer[idx]
                Text("${p.name}: ${ps.total}", fontWeight = FontWeight.SemiBold)
                if (ps.rounds.isNotEmpty()) {
                    Text(
                        ps.rounds.joinToString(" · ") { "+${it.pointsScored}=${it.totalAfter}" },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}
