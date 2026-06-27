package com.dartrack.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dartrack.data.GameRepository
import com.dartrack.model.CountUpState
import com.dartrack.model.ROUNDS
import com.dartrack.viewmodel.GameViewModel

/**
 * Count-Up entry screen, built to the app's single-screen layout convention:
 * everything fits on one screen (no verticalScroll). A compact header shows the
 * current round (X/8) and the active thrower, a weighted players area highlights
 * the active player with a big cumulative total while inactive players stay
 * compact, and the bottom [ScoreNumpad] (capped at 180) enters a 3-dart total.
 * An optional [Caller] announces each score aloud and "Game shot!" on finish.
 */
@Composable
fun CountUpScreen(
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
    val state = record?.state as? CountUpState ?: return

    var entry by remember { mutableStateOf("") }

    // The active seat may be a CPU opponent; the viewmodel auto-plays its turn,
    // so we hide the numpad and show a "throwing" indicator instead of input.
    val isBotTurn = !state.isFinished &&
        state.players.getOrNull(state.currentPlayerIndex)?.isBot == true

    val caller = rememberCaller()
    var callerOn by rememberCallerOnDefault()

    // Announce the just-entered total, then "Game shot!" if that turn finished
    // the game (computed from the immutable next state so we don't race the VM).
    fun announce(total: Int, finishedAfter: Boolean) {
        if (!callerOn) return
        caller.speak(if (total == 1) "1" else "$total", callerOn)
        if (finishedAfter) caller.speak("Game shot!", callerOn)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ---- Compact header: round + active player + caller toggle + exit. --
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (state.isFinished) {
                    Text(
                        "Count-Up · finished",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val activeIdx = state.currentPlayerIndex
                    val activeName = state.players.getOrNull(activeIdx)?.name ?: ""
                    Text(
                        "$activeName to throw",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Round ${state.currentRoundNumber(activeIdx)}/$ROUNDS",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
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

        // ---- Winner banner. -----------------------------------------------
        if (state.isFinished) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Text(
                    "Winner: " + state.winnerIndices.joinToString { state.players[it].name },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // ---- Players area: active player expanded, others compact. --------
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.players.forEachIndexed { idx, p ->
                val ps = state.perPlayer[idx]
                val active = idx == state.currentPlayerIndex && !state.isFinished
                val isWinner = state.winnerIndices.contains(idx)

                val containerColor = when {
                    isWinner -> MaterialTheme.colorScheme.secondaryContainer
                    active -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val contentColor = when {
                    isWinner -> MaterialTheme.colorScheme.onSecondaryContainer
                    active -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = containerColor,
                        contentColor = contentColor,
                    ),
                    elevation = CardDefaults.elevatedCardElevation(
                        defaultElevation = if (active) 8.dp else 1.dp,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 16.dp,
                                vertical = if (active) 20.dp else 10.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.name + (if (isWinner) "  🏆" else ""),
                                style = if (active) MaterialTheme.typography.titleLarge
                                        else MaterialTheme.typography.titleMedium,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                            )
                            Text(
                                when {
                                    state.isFinished -> "${ps.darts} darts"
                                    else -> "round ${state.currentRoundNumber(idx)}/$ROUNDS · ${ps.darts} darts"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            ps.total.toString(),
                            fontSize = if (active) 56.sp else 30.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
        }

        // ---- Bottom entry controls (clear of the nav bar). ----------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (state.isFinished) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.undoCountUp(); entry = "" },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Undo") }
                    Button(
                        onClick = onExit,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Home") }
                }
            } else if (isBotTurn) {
                // CPU seat: the viewmodel is taking the turn — no input. Undo is
                // still offered (undoing a bot turn is fine).
                BotThrowingIndicator(onUndo = { vm.undoCountUp(); entry = "" })
            } else {
                ScoreNumpad(
                    entry = entry,
                    onEntryChange = { entry = it },
                    onConfirm = { v ->
                        // Decide the announcement from the immutable next state.
                        val finishedAfter = state.applyTurn(v).isFinished
                        announce(v, finishedAfter)
                        vm.applyCountUpTurn(v)
                        entry = ""
                    },
                    onUndo = { vm.undoCountUp(); entry = "" },
                    maxValue = 180,
                )
            }
        }
    }
}
