package com.dartrack.ui.game

import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    val caller = rememberCaller()
    var callerOn by rememberSaveable { mutableStateOf(false) }

    // Announce an X01 turn. Outcome is derived from the same pure rules the model
    // uses (see X01State.applyTurn) without mutating any state.
    fun announceX01(entered: Int, finishedOnDouble: Boolean) {
        if (!callerOn) return
        val before = state.currentPlayerScore()
        val after = before - entered
        val bust = when {
            after < 0 -> true
            after == 0 && state.doubleOut && !finishedOnDouble -> true
            after == 1 && state.doubleOut -> true
            else -> false
        }
        val text = when {
            bust -> "No score, bust"
            after == 0 -> "scored $entered, game shot!"
            else -> "scored $entered, $after remaining"
        }
        caller.speak(text, callerOn)
    }

    // Apply a confirmed turn with the caller announcement.
    fun confirmTurn(v: Int, finishedOnDouble: Boolean) {
        announceX01(v, finishedOnDouble = finishedOnDouble)
        vm.applyX01Turn(v, finishedOnDouble = finishedOnDouble)
        entry = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        // ---- Top bar: mode summary, caller toggle, exit. ------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "X01 · start ${state.startScore}${if (state.doubleOut) " · DO" else ""}" +
                    when {
                        state.setsToWin > 1 ->
                            " · first to ${state.setsToWin} sets · ${state.legsToWin} legs/set"
                        state.legsToWin > 1 -> " · first to ${state.legsToWin} legs"
                        else -> ""
                    },
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

        // ---- Match leg scoreboard (only for multi-leg matches). ----------
        if (state.isMatch) {
            LegScoreboard(state)
        }

        // ---- HERO: the current player's remaining score, dominant. -------
        if (!state.isFinished) {
            HeroRemaining(state)
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
                            p.name +
                                (when {
                                    state.setsToWin > 1 ->
                                        "  (${state.setsWonBy(idx)} sets · ${state.legsWonBy(idx)} legs)"
                                    state.isMatch -> "  (${state.legsWonBy(idx)} legs)"
                                    else -> ""
                                }) +
                                (if (isWinner) "  🏆" else ""),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "avg ${"%.1f".format(X01Stats.threeDartAverage(ps, state.startScore))}" +
                                " · darts ${ps.turns.size * 3}" +
                                (X01Stats.highestTurn(ps).takeIf { it > 0 }?.let { " · best $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ps.turns.lastOrNull()?.let { last ->
                            Text(
                                "last " + if (last.bust) "BUST" else last.entered.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Text(
                        state.scoreFor(idx).toString(),
                        fontSize = if (active) 44.sp else 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val recentTurns = ps.turns.takeLast(3)
                if (recentTurns.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    if (t.bust) "BUST" else t.entered.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (t.bust) MaterialTheme.colorScheme.onErrorContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.isFinished) {
            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        (if (state.isMatch) "Match winner: " else "Winner: ") +
                            state.winnerIndices.joinToString { state.players[it].name } +
                            when {
                                state.setsToWin > 1 -> "  (${state.winnerIndices.joinToString {
                                    "${state.setsWonBy(it)} sets · ${state.legsWonBy(it)} legs"
                                }})"
                                state.isMatch -> "  (${state.winnerIndices.joinToString {
                                    state.legsWonBy(it).toString()
                                }} legs)"
                                else -> ""
                            },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onExit) { Text("Back to home") }
                    TextButton(onClick = { vm.undoX01() }) { Text("Undo last turn") }
                }
            }
        } else {
            // ---- Prominent checkout chip (only when finishable). ----------
            val routes = Checkout.suggest(state.currentPlayerScore(), state.doubleOut)
            if (routes.isNotEmpty()) {
                CheckoutChip(routes)
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
                        confirmTurn(v, finishedOnDouble = !state.doubleOut)
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
                    confirmTurn(v, finishedOnDouble = true)
                    pendingFinish = null
                }) { Text("Yes — winner") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmTurn(v, finishedOnDouble = false)
                    pendingFinish = null
                }) { Text("No — bust") }
            },
        )
    }
}

/**
 * Hero panel: the active player's remaining score as the dominant element on the
 * screen, animated so it counts to its new value after each turn. Readable across
 * a room off a wall/stand.
 */
@Composable
private fun HeroRemaining(state: X01State) {
    val target = state.currentPlayerScore()
    val animated by animateIntAsState(targetValue = target, label = "heroRemaining")
    val activeName = state.players.getOrNull(state.currentPlayerIndex)?.name ?: ""

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
                "remaining",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Match leg scoreboard: legs won per player, plus the current leg number.
 */
@Composable
private fun LegScoreboard(state: X01State) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (state.setsToWin > 1) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sets",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        state.players.indices.joinToString("  –  ") {
                            state.setsWonBy(it).toString()
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Legs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    state.players.indices.joinToString("  –  ") {
                        state.legsWonBy(it).toString()
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                if (state.setsToWin > 1) "Set ${state.setWins.sum() + 1} · Leg ${state.legWins.sum() + 1}"
                else "Leg ${state.completedLegs.size + 1}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Prominent checkout suggestion using the "checkout/scored" accent
 * (tertiaryContainer). Surfaces up to two routes from [Checkout.suggest].
 */
@Composable
private fun CheckoutChip(routes: List<String>) {
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
                "Checkout",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "  " + routes.take(2).joinToString("   ·   "),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
