package com.dartrack.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dartrack.data.GameRepository
import com.dartrack.model.GOLF_HOLES
import com.dartrack.model.GolfResult
import com.dartrack.model.GolfState
import com.dartrack.viewmodel.GameViewModel

/**
 * Golf entry screen, built to the app's single-screen layout convention:
 * everything fits on one screen (no verticalScroll). A compact header shows the
 * active player and the position in the round ("Hole X/9"); a weighted players
 * area highlights the active thrower with a big stroke total (lower is better)
 * while inactive players stay compact; and the bottom entry buttons record the
 * player's BEST dart for the hole as strokes (Triple→1 · Double→2 · Single→3 ·
 * Miss→5). The buttons stay clear of the system bars.
 */
@Composable
fun GolfScreen(
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
    val state = record?.state as? GolfState ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ---- Compact header: active player + hole position. ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (state.isFinished) {
                    Text(
                        "Golf · finished",
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
                        "Hole ${state.currentHoleNumber(activeIdx)}/$GOLF_HOLES",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        "best dart · lowest strokes wins",
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
                                "${ps.holesPlayed}/$GOLF_HOLES holes",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                ps.strokes.toString(),
                                fontSize = if (active) 56.sp else 30.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                if (ps.strokes == 1) "stroke" else "strokes",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
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
                        onClick = { vm.undoGolf() },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Undo") }
                    Button(
                        onClick = onExit,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Home") }
                }
            } else {
                Text(
                    "Best dart this hole?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                // One tap records the hole's best dart as strokes.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ResultButton("Triple", "1", GolfResult.TRIPLE) { vm.applyGolfResult(it) }
                    ResultButton("Double", "2", GolfResult.DOUBLE) { vm.applyGolfResult(it) }
                    ResultButton("Single", "3", GolfResult.SINGLE) { vm.applyGolfResult(it) }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.undoGolf() },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Undo") }
                    Button(
                        onClick = { vm.applyGolfResult(GolfResult.MISS) },
                        modifier = Modifier.weight(2f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text("Miss (5)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ResultButton(
    label: String,
    strokes: String,
    result: GolfResult,
    onClick: (GolfResult) -> Unit,
) {
    Button(
        onClick = { onClick(result) },
        modifier = Modifier.weight(1f).height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(strokes, fontSize = 12.sp)
        }
    }
}
