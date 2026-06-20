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
import com.dartrack.model.HALF_IT_ROUNDS
import com.dartrack.model.HalfItPlayerState
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
                "Half-It · ${HALF_IT_ROUNDS.size} rounds",
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

        // ---- HERO: the current round + target, dominant & glanceable. -----
        if (!state.isFinished) {
            HalfItHero(state, target)
        }

        // ---- Per-player cards: bold accent for the active thrower. --------
        state.players.forEachIndexed { idx, p ->
            val ps = state.perPlayer[idx]
            val active = idx == state.currentPlayerIndex && !state.isFinished
            val isWinner = state.winnerIndices.contains(idx)

            PlayerCard(
                name = p.name,
                ps = ps,
                active = active,
                isWinner = isWinner,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (state.isFinished) {
            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Winner: ${state.winnerIndices.joinToString { state.players[it].name }}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
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

/**
 * Hero panel: the current round and its target, the dominant element on the
 * screen so the thrower can tell at a glance what they are aiming at. Mirrors the
 * X01 hero treatment (primary surface, extra-large shape, elevation).
 */
@Composable
private fun HalfItHero(state: HalfItState, target: HalfItTarget?) {
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
                "Round ${state.currentRound + 1}/${HALF_IT_ROUNDS.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                target?.label ?: "Complete",
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Text(
                if (activeName.isNotEmpty()) "$activeName to throw · miss halves your score"
                else "miss halves your score",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Per-player card: bold primaryContainer accent + elevation for the active
 * thrower, secondary for the winner, surfaceVariant otherwise — matching the X01
 * scoreboard. The running total is animated so it counts to its new value.
 */
@Composable
private fun PlayerCard(
    name: String,
    ps: HalfItPlayerState,
    active: Boolean,
    isWinner: Boolean,
) {
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

    val animatedTotal by animateIntAsState(targetValue = ps.total, label = "halfItTotal")

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
            Text(
                name + if (isWinner) "  🏆" else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                animatedTotal.toString(),
                fontSize = if (active) 44.sp else 34.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        val recentRounds = ps.rounds.takeLast(9)
        if (recentRounds.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                recentRounds.forEach { r ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (r.pointsScored == 0) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (r.pointsScored == 0) "½" else "+${r.pointsScored}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (r.pointsScored == 0) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
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
