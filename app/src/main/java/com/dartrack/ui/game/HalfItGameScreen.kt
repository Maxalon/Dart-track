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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
            .statusBarsPadding()
            .padding(horizontal = 8.dp),
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

        // ---- Compact round/target indicator (not a giant hero card). ------
        if (!state.isFinished) {
            HalfItHeader(state, target)
        }

        // ---- Players area: absorbs spare vertical space (no scroll). The
        // active thrower's row is the prominent/expanded one (PlayerCard
        // already bolds + enlarges the active row).
        Column(modifier = Modifier.weight(1f)) {
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
        }

        if (state.isFinished) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Winner: ${state.winnerIndices.joinToString { state.players[it].name }}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = onExit) { Text("Back to home") }
                        TextButton(onClick = { vm.undoHalfIt() }) { Text("Undo last turn") }
                    }
                }
            }
        } else {
            // ---- Bottom controls: numpad kept clear of the nav bar. -------
            Column(modifier = Modifier.navigationBarsPadding()) {
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
}

/**
 * Compact round/target indicator (e.g. "Round 6/9 · Any Triple") as a small
 * header — not a giant hero card — so the screen fits without scrolling.
 */
@Composable
private fun HalfItHeader(state: HalfItState, target: HalfItTarget?) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Round ${state.currentRound + 1}/${HALF_IT_ROUNDS.size}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "  ·  " + (target?.label ?: "Complete"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "miss halves",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
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
