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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dartrack.data.GameRepository
import com.dartrack.model.Checkout
import com.dartrack.model.X01State
import com.dartrack.model.X01Stats
import com.dartrack.viewmodel.GameViewModel

@Composable
fun X01GameScreen(
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
    val state = record?.state as? X01State ?: return

    var entry by remember { mutableStateOf("") }
    var pendingFinish by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "X01 · start ${state.startScore}${if (state.doubleOut) " · DO" else ""}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onExit) { Text("Exit") }
        }

        state.players.forEachIndexed { idx, p ->
            val ps = state.perPlayer[idx]
            val active = idx == state.currentPlayerIndex && !state.isFinished
            val isWinner = state.winnerIndices.contains(idx)
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            p.name + if (isWinner) "  🏆" else "",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        Text(
                            "avg ${"%.1f".format(X01Stats.threeDartAverage(ps, state.startScore))}" +
                                " · darts ${ps.turns.size * 3}" +
                                (X01Stats.highestTurn(ps).takeIf { it > 0 }?.let { " · best $it" } ?: ""),
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        state.scoreFor(idx).toString(),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val recentTurns = ps.turns.takeLast(3)
                if (recentTurns.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        recentTurns.forEach { t ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (t.bust) MaterialTheme.colorScheme.errorContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    if (t.bust) "BUST" else t.entered.toString(),
                                    fontSize = 12.sp,
                                )
                            }
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
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onExit) { Text("Back to home") }
                    TextButton(onClick = { vm.undoX01() }) { Text("Undo last turn") }
                }
            }
        } else {
            val routes = Checkout.suggest(state.currentPlayerScore(), state.doubleOut)
            if (routes.isNotEmpty()) {
                Text(
                    "Checkout: " + routes.take(2).joinToString(" · "),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            ScoreNumpad(
                entry = entry,
                onEntryChange = { entry = it },
                onConfirm = { v ->
                    val before = state.currentPlayerScore()
                    val wouldFinish = (before - v) == 0
                    if (state.doubleOut && wouldFinish) {
                        pendingFinish = v
                    } else {
                        vm.applyX01Turn(v, finishedOnDouble = !state.doubleOut)
                        entry = ""
                    }
                },
                onUndo = { vm.undoX01(); entry = "" },
                maxValue = 180,
            )
        }
    }

    pendingFinish?.let { v ->
        AlertDialog(
            onDismissRequest = { pendingFinish = null },
            title = { Text("Finish on a double?") },
            text = {
                Text("$v would take you to 0. Did the final dart land on a double?")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.applyX01Turn(v, finishedOnDouble = true)
                    pendingFinish = null
                    entry = ""
                }) { Text("Yes — winner") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.applyX01Turn(v, finishedOnDouble = false)
                    pendingFinish = null
                    entry = ""
                }) { Text("No — bust") }
            },
        )
    }
}
