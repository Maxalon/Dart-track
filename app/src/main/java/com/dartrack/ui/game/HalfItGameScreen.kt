package com.dartrack.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dartrack.data.GameRepository
import com.dartrack.model.HALF_IT_ROUNDS
import com.dartrack.model.HalfItState
import com.dartrack.model.HalfItTarget
import com.dartrack.viewmodel.GameViewModel

@Composable
fun HalfItGameScreen(
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
    val state = record?.state as? HalfItState ?: return

    var entry by remember { mutableStateOf("") }
    val target = state.currentTarget()
    val maxValue = maxValueForTarget(target)

    val caller = rememberCaller()
    var callerOn by rememberSaveable { mutableStateOf(false) }

    // Announce a Half-It round: the points scored, or "Halved" when nothing
    // was hit (which halves the running total per HalfItState.applyTurn).
    fun announceHalfIt(points: Int) {
        if (!callerOn) return
        val text = when {
            points == 0 -> "Halved"
            points == 1 -> "scored 1 point"
            else -> "scored $points points"
        }
        caller.speak(text, callerOn)
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Half-It", fontSize = 14.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { callerOn = !callerOn }) {
                Icon(
                    if (callerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = if (callerOn) "Mute caller" else "Enable caller",
                )
            }
            TextButton(onClick = onExit) { Text("Exit") }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (target != null) {
                    Text(
                        "Round ${state.currentRound + 1} of ${HALF_IT_ROUNDS.size}",
                        fontSize = 14.sp,
                    )
                    Text(
                        "Target: ${target.label}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Hit nothing → score halved.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Game complete", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        state.players.forEachIndexed { idx, p ->
            val ps = state.perPlayer[idx]
            val active = idx == state.currentPlayerIndex && !state.isFinished
            val isWinner = state.winnerIndices.contains(idx)
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isWinner -> MaterialTheme.colorScheme.secondary
                        active -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        p.name + if (isWinner) " 🏆" else "",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        ps.total.toString(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (ps.rounds.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ps.rounds.takeLast(9).forEach { r ->
                            Text(
                                "+${r.pointsScored}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        if (state.isFinished) {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Winner: ${state.winnerIndices.joinToString { state.players[it].name }}",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onExit) { Text("Back to home") }
                    TextButton(onClick = { vm.undoHalfIt() }) { Text("Undo last turn") }
                }
            }
        } else {
            ScoreNumpad(
                entry = entry,
                onEntryChange = { entry = it },
                onConfirm = { v ->
                    announceHalfIt(v)
                    vm.applyHalfItTurn(v)
                    entry = ""
                },
                onUndo = { vm.undoHalfIt(); entry = "" },
                maxValue = maxValue,
            )
        }
    }
}

private fun maxValueForTarget(target: HalfItTarget?): Int = when (target) {
    null -> 0
    is HalfItTarget.Number -> target.n * 3 * 3 // 3 darts at triple
    HalfItTarget.AnyDouble -> 120  // 3 × D20
    HalfItTarget.AnyTriple -> 180  // 3 × T20
    HalfItTarget.Bullseye -> 150   // 3 × double-bull
}
