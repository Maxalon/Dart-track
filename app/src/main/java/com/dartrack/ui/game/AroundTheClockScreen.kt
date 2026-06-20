package com.dartrack.ui.game

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.dartrack.model.AROUND_CLOCK_LAST_TARGET
import com.dartrack.model.AROUND_CLOCK_MAX_HITS
import com.dartrack.model.AroundTheClockState
import com.dartrack.viewmodel.GameViewModel

@Composable
fun AroundTheClockScreen(
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
    val state = record?.state as? AroundTheClockState ?: return

    var pending by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        // ---- Top bar: mode summary, exit. --------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Around the Clock · 1–$AROUND_CLOCK_LAST_TARGET",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onExit) { Text("Exit") }
        }

        // ---- HERO: the active player's current target, dominant. ---------
        if (!state.isFinished) {
            HeroTarget(state)
        }

        // ---- Per-player cards: bold accent for the active thrower. --------
        state.players.forEachIndexed { idx, p ->
            val ps = state.perPlayer[idx]
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            p.name + (if (isWinner) "  🏆" else ""),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (ps.finished) "Finished · ${ps.darts} darts"
                            else "on ${ps.currentTarget} / $AROUND_CLOCK_LAST_TARGET · ${ps.darts} darts",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${ps.cleared}/$AROUND_CLOCK_LAST_TARGET",
                            fontSize = if (active) 40.sp else 32.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "cleared",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.isFinished) {
            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Winner: " + state.winnerIndices.joinToString { state.players[it].name },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onExit) { Text("Back to home") }
                    TextButton(onClick = { vm.undoAroundClock(); pending = null }) {
                        Text("Undo last turn")
                    }
                }
            }
        } else {
            // ---- Hits entry: 0..AROUND_CLOCK_MAX_HITS. --------------------
            Text(
                "How many of the next targets did you hit?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (0..AROUND_CLOCK_MAX_HITS).forEach { n ->
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.undoAroundClock(); pending = null },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Undo") }
                Button(
                    onClick = {
                        val v = pending ?: return@Button
                        vm.applyAroundClockTurn(v)
                        pending = null
                    },
                    enabled = pending != null,
                    modifier = Modifier.weight(2f).height(56.dp),
                ) {
                    Text("Confirm", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Hero panel: the active player's current target as the dominant element on the
 * screen, animated so it counts to its new value after each turn. Readable across
 * a room off a wall/stand.
 */
@Composable
private fun HeroTarget(state: AroundTheClockState) {
    val idx = state.currentPlayerIndex
    val target = state.currentTarget(idx)
    val animated by animateIntAsState(targetValue = target, label = "heroTarget")
    val activeName = state.players.getOrNull(idx)?.name ?: ""

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "$activeName to throw",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                animated.toString(),
                fontSize = 96.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Text(
                "aiming at",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}
