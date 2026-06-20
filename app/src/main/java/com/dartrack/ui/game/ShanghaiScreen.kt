package com.dartrack.ui.game

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
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
import com.dartrack.model.SHANGHAI_MAX_DARTS
import com.dartrack.model.ShanghaiState
import com.dartrack.viewmodel.GameViewModel

/**
 * Shanghai entry screen, built to the app's single-screen layout convention:
 * everything fits on one screen (no verticalScroll). A compact header shows the
 * current target number, a weighted players area highlights the active thrower
 * with a big total while inactive players stay compact, and the bottom entry
 * controls (Singles / Doubles / Triples counters) stay clear of the system bars.
 */
@Composable
fun ShanghaiScreen(
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
    val state = record?.state as? ShanghaiState ?: return

    var singles by remember { mutableStateOf(0) }
    var doubles by remember { mutableStateOf(0) }
    var triples by remember { mutableStateOf(0) }

    fun reset() { singles = 0; doubles = 0; triples = 0 }

    val entered = singles + doubles + triples
    val remaining = SHANGHAI_MAX_DARTS - entered

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ---- Compact header: current target + "to throw" + Shanghai hint. ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (state.isFinished) {
                    Text(
                        "Shanghai · finished",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val activeIdx = state.currentPlayerIndex
                    val activeName = state.players.getOrNull(activeIdx)?.name ?: ""
                    val target = state.currentTarget(activeIdx)
                    Text(
                        "$activeName to throw",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Number $target",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        "S + D + T of $target = instant Shanghai win",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                                    else -> "on ${state.currentTarget(idx)} · ${ps.darts} darts"
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
                        onClick = { vm.undoShanghai(); reset() },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Undo") }
                    Button(
                        onClick = onExit,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Home") }
                }
            } else {
                val target = state.currentTarget(state.currentPlayerIndex)
                Text(
                    "Darts on $target · $remaining left",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                CounterRow(
                    label = "Singles",
                    value = singles,
                    onMinus = { if (singles > 0) singles-- },
                    onPlus = { if (remaining > 0) singles++ },
                    plusEnabled = remaining > 0,
                )
                Spacer(Modifier.height(6.dp))
                CounterRow(
                    label = "Doubles",
                    value = doubles,
                    onMinus = { if (doubles > 0) doubles-- },
                    onPlus = { if (remaining > 0) doubles++ },
                    plusEnabled = remaining > 0,
                )
                Spacer(Modifier.height(6.dp))
                CounterRow(
                    label = "Triples",
                    value = triples,
                    onMinus = { if (triples > 0) triples-- },
                    onPlus = { if (remaining > 0) triples++ },
                    plusEnabled = remaining > 0,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.undoShanghai(); reset() },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Undo") }
                    Button(
                        onClick = {
                            vm.applyShanghaiTurn(singles, doubles, triples)
                            reset()
                        },
                        modifier = Modifier.weight(2f).height(56.dp),
                    ) {
                        Text("Confirm", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CounterRow(
    label: String,
    value: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    plusEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = onMinus,
            enabled = value > 0,
            modifier = Modifier.size(56.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text("–", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            value.toString(),
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .width(36.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        FilledTonalButton(
            onClick = onPlus,
            enabled = plusEnabled,
            modifier = Modifier.size(56.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
