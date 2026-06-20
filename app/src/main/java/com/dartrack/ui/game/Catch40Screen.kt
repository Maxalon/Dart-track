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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dartrack.data.GameRepository
import com.dartrack.model.CATCH40_MAX_HITS
import com.dartrack.model.Catch40State
import com.dartrack.viewmodel.GameViewModel

/**
 * Catch 40 entry screen, built to the app's single-screen layout convention:
 * everything fits on one screen (no verticalScroll). A compact header shows the
 * active player's current double on the catch-or-stay ladder, a weighted players
 * area highlights the active thrower with a big score while inactive players stay
 * compact, and the bottom entry controls stay clear of the system bars.
 */
@Composable
fun Catch40Screen(
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
    val state = record?.state as? Catch40State ?: return

    var pending by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ---- Compact header: active player's current double + "to throw". --
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (state.isFinished) {
                    Text(
                        "Catch 40 · finished",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val activeIdx = state.currentPlayerIndex
                    val activeName = state.players.getOrNull(activeIdx)?.name ?: ""
                    val dbl = state.currentDouble(activeIdx)
                    val value = state.currentDoubleValue(activeIdx)
                    Text(
                        "$activeName to throw",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Catch D$dbl ($value)",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
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
                    ps.finished -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val contentColor = when {
                    isWinner -> MaterialTheme.colorScheme.onSecondaryContainer
                    active -> MaterialTheme.colorScheme.onPrimaryContainer
                    ps.finished -> MaterialTheme.colorScheme.onTertiaryContainer
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
                                    ps.finished -> "Caught D1 · ${ps.darts} darts"
                                    state.isFinished -> "on D${ps.doubleNumber} · ${ps.darts} darts"
                                    else -> "on D${ps.doubleNumber} (${ps.doubleValue}) · ${ps.darts} darts"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            ps.score.toString(),
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
                        onClick = { vm.undoCatch40(); pending = null },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Undo") }
                    Button(
                        onClick = onExit,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Home") }
                }
            } else {
                Text(
                    "How many darts hit D${state.currentDouble(state.currentPlayerIndex)}?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (0..CATCH40_MAX_HITS).forEach { n ->
                        val selected = pending == n
                        Button(
                            onClick = { pending = n },
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected)
                                    MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Text(n.toString(), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.undoCatch40(); pending = null },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Undo") }
                    Button(
                        onClick = {
                            val v = pending ?: return@Button
                            vm.applyCatch40Turn(v)
                            pending = null
                        },
                        enabled = pending != null,
                        modifier = Modifier.weight(2f).height(56.dp),
                    ) {
                        Text("Confirm", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
