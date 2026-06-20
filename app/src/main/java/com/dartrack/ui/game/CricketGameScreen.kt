package com.dartrack.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Cricket", fontSize = 14.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { callerOn = !callerOn }) {
                Icon(
                    if (callerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = if (callerOn) "Mute caller" else "Enable caller",
                )
            }
            TextButton(onClick = onExit) { Text("Exit") }
        }

        // Scoreboard: targets header + a row per player.
        Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1.6f)) { Text("Player", fontWeight = FontWeight.SemiBold) }
                    CRICKET_TARGETS.forEach { t ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(if (t == 25) "B" else t.toString(),
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text("Pts", fontWeight = FontWeight.SemiBold)
                    }
                }
                state.players.forEachIndexed { idx, p ->
                    val ps = state.perPlayer[idx]
                    val cum = ps.cumulativeMarks()
                    val active = idx == state.currentPlayerIndex && !state.isFinished
                    val isWinner = state.winnerIndices.contains(idx)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(
                                when {
                                    isWinner -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                    active -> MaterialTheme.colorScheme.primaryContainer
                                    else -> Color.Transparent
                                }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1.6f).padding(horizontal = 4.dp)) {
                            Text(p.name + if (isWinner) " 🏆" else "", fontSize = 14.sp)
                        }
                        CRICKET_TARGETS.forEach { t ->
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                MarksGlyph(cum[t] ?: 0)
                            }
                        }
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(state.scoreFor(idx).toString(),
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.isFinished) {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Winner: ${state.winnerIndices.joinToString { state.players[it].name }}",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onExit) { Text("Back to home") }
                    TextButton(onClick = { vm.undoCricket() }) { Text("Undo last turn") }
                }
            }
            return
        }

        // Pending entry section: one row per target, +/- to add marks, max 9 total.
        Text(
            "${state.players[state.currentPlayerIndex].name} — pending marks ($pendingTotal/9)",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                CRICKET_TARGETS.forEach { t ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (t == 25) "Bull" else t.toString(),
                            modifier = Modifier.weight(1f),
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
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
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

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { vm.undoCricket(); pending.clear() },
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Icon(Icons.Default.Undo, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text("Undo")
            }
            OutlinedButton(
                onClick = { pending.clear() },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text("Clear") }
            Button(
                onClick = {
                    announceCricket(pending.toMap())
                    vm.applyCricketTurn(pending.toMap())
                    pending.clear()
                },
                enabled = canConfirm,
                modifier = Modifier.weight(2f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text("Confirm turn", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Visual: shows /, X, ⊘ for marks 1, 2, 3 closing; numbers above 3. */
@Composable
private fun MarksGlyph(marks: Int) {
    val txt = when {
        marks <= 0 -> ""
        marks == 1 -> "/"
        marks == 2 -> "X"
        else -> "Ⓧ"
    }
    val extra = if (marks > CRICKET_MARKS_TO_CLOSE) " +${marks - CRICKET_MARKS_TO_CLOSE}" else ""
    Text(txt + extra, fontWeight = FontWeight.Bold)
}

