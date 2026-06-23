package com.dartrack.ui.game

import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dartrack.data.GameRepository
import com.dartrack.model.CRICKET_MARKS_TO_CLOSE
import com.dartrack.model.CRICKET_TARGETS
import com.dartrack.model.CricketState
import com.dartrack.viewmodel.GameViewModel

@Composable
fun CricketGameScreen(
    recordId: String,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { GameRepository.get(context) }
    val vm: GameViewModel = viewModel(
        key = recordId,
        factory = GameViewModel.Factory(repo, recordId),
    )
    val record by vm.record.collectAsState()
    val state = record?.state as? CricketState ?: return

    val pending = remember { mutableStateMapOf<Int, Int>() }
    val pendingTotal = pending.values.sum()
    val canConfirm = !state.isFinished

    val caller = rememberCaller()
    var callerOn by rememberSaveable { mutableStateOf(false) }

    // Announce a Cricket turn. The win outcome is computed by running the model's
    // own pure applyTurn (which returns a new state and does not mutate anything)
    // so the announcement always matches the real result.
    fun announceCricket(marks: Map<Int, Int>) {
        if (!callerOn) return
        val total = marks.values.sum()
        val whoActed = state.currentPlayerIndex
        val won = state.applyTurn(marks).winnerIndices.contains(whoActed)
        val text = when {
            won -> "game shot!"
            total == 1 -> "1 mark"
            else -> "$total marks"
        }
        caller.speak(text, callerOn)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 8.dp),
    ) {
        // ---- Top bar: mode summary, caller toggle, exit. ------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.cutThroat) "Cricket · Cut-throat (lowest wins)" else "Cricket",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { callerOn = !callerOn }) {
                Icon(
                    if (callerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = if (callerOn) "Mute caller" else "Enable caller",
                    tint = if (callerOn) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onExit) { Text("Exit") }
        }

        // ---- Players area: scoreboard absorbs spare vertical space so the
        // screen never scrolls. The active thrower's row is the prominent one
        // (bold name + larger animated score); no separate duplicate header.
        Column(modifier = Modifier.weight(1f)) {
        // ---- Targets legend (shared across the player cards below). -------
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1.6f)) {
                    Text(
                        "Player",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CRICKET_TARGETS.forEach { t ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            if (t == 25) "B" else t.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "Pts",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- Per-player cards: bold accent for the active thrower. --------
        state.players.forEachIndexed { idx, p ->
            val ps = state.perPlayer[idx]
            val cum = ps.cumulativeMarks()
            val active = idx == state.currentPlayerIndex && !state.isFinished
            val isWinner = state.winnerIndices.contains(idx)

            val containerColor = when {
                isWinner -> MaterialTheme.colorScheme.secondary
                active -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val contentColor = when {
                isWinner -> MaterialTheme.colorScheme.onSecondary
                active -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            val animatedScore by animateIntAsState(
                targetValue = state.scoreFor(idx),
                label = "cricketScore$idx",
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                ),
                elevation = CardDefaults.elevatedCardElevation(
                    defaultElevation = if (active || isWinner) 8.dp else 1.dp,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Name + per-target marks grid.
                    Column(modifier = Modifier.weight(1.6f)) {
                        Text(
                            p.name + if (isWinner) "  🏆" else "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        )
                    }
                    CRICKET_TARGETS.forEach { t ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            MarksGlyph(cum[t] ?: 0)
                        }
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            animatedScore.toString(),
                            fontSize = if (active) 24.sp else 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        } // end players area

        if (state.isFinished) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Winner: ${state.winnerIndices.joinToString { state.players[it].name }}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = onExit) { Text("Back to home") }
                        TextButton(onClick = { vm.undoCricket() }) { Text("Undo last turn") }
                    }
                }
            }
            return
        }

        // ---- Pending entry header. ----------------------------------------
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.players[state.currentPlayerIndex].name} to throw",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$pendingTotal / 9 marks",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ---- Pending entry: one row per target, +/- to add marks, max 9. --
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                CRICKET_TARGETS.forEach { t ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (t == 25) "Bull" else t.toString(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val v = pending[t] ?: 0
                        OutlinedButton(
                            onClick = { if (v > 0) pending[t] = v - 1 },
                            enabled = v > 0,
                        ) { Text("−") }
                        Text(
                            v.toString(),
                            modifier = Modifier.padding(horizontal = 12.dp).width(28.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = {
                                if (pendingTotal < 9) pending[t] = v + 1
                            },
                            enabled = pendingTotal < 9,
                        ) { Text("+") }
                    }
                }
            }
        }

        // ---- Bottom controls: pinned clear of the Android nav bar. --------
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { vm.undoCricket(); pending.clear() },
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                Icon(Icons.Default.Undo, contentDescription = "Undo last turn")
                Spacer(Modifier.size(4.dp))
                Text("Undo")
            }
            OutlinedButton(
                onClick = { pending.clear() },
                modifier = Modifier.weight(1f).height(54.dp),
            ) { Text("Clear") }
            Button(
                onClick = {
                    announceCricket(pending.toMap())
                    vm.applyCricketTurn(pending.toMap())
                    pending.clear()
                },
                enabled = canConfirm,
                modifier = Modifier.weight(2f).height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Default.Check, contentDescription = "Confirm turn")
                Spacer(Modifier.size(4.dp))
                Text(
                    "Confirm turn",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Visual: shows /, X, Ⓧ for marks 1, 2, 3 closing; scoring hits past 3 shown as "+n". */
@Composable
private fun MarksGlyph(marks: Int) {
    val txt = when {
        marks <= 0 -> ""
        marks == 1 -> "/"
        marks == 2 -> "X"
        else -> "Ⓧ"
    }
    val extra = if (marks > CRICKET_MARKS_TO_CLOSE) " +${marks - CRICKET_MARKS_TO_CLOSE}" else ""
    Text(
        txt + extra,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}
