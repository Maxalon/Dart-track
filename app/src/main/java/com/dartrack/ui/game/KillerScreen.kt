package com.dartrack.ui.game

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dartrack.data.GameRepository
import com.dartrack.model.KILLER_MAX_DARTS
import com.dartrack.model.KillerState
import com.dartrack.viewmodel.GameViewModel

@Composable
fun KillerScreen(
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
    val state = record?.state as? KillerState ?: return

    // Pending entry keyed by SEAT INDEX -> count of that seat's DOUBLE hit this
    // turn. The total across all seats is capped at KILLER_MAX_DARTS (3).
    val pending = remember { mutableStateMapOf<Int, Int>() }
    val pendingTotal = pending.values.sum()
    val canConfirm = !state.isFinished

    val caller = rememberCaller()
    var callerOn by rememberSaveable { mutableStateOf(false) }

    // Expand the pending map into a flat hits list of seat indices, placing the
    // active player's OWN hits FIRST so arming happens before opponent damage in
    // the same visit (the rules require you to be armed before you can deal it).
    fun buildHits(): List<Int> {
        val me = state.currentPlayerIndex
        val mine = List(pending[me] ?: 0) { me }
        val others = pending.entries
            .filter { it.key != me }
            .flatMap { (seat, count) -> List(count) { seat } }
        return mine + others
    }

    // Announce a Killer turn via the pure model (no mutation): "game shot!" if the
    // active player wins; "armed" when they just became a killer; otherwise the
    // damage dealt as "{n} lives". Crash-proof and only speaks when callerOn.
    fun announceKiller(hits: List<Int>) {
        if (!callerOn) return
        val me = state.currentPlayerIndex
        val before = state.perPlayer
        val result = state.applyTurn(hits)
        val won = result.winnerIndices.contains(me)
        val justArmed = !before[me].isKiller && result.perPlayer[me].isKiller
        // Total opponent lives removed this turn (excludes the active player).
        val livesLost = before.indices.sumOf { i ->
            if (i == me) 0 else (before[i].lives - result.perPlayer[i].lives).coerceAtLeast(0)
        }
        val text = when {
            won -> "game shot!"
            justArmed -> "armed"
            livesLost == 1 -> "1 life"
            livesLost > 1 -> "$livesLost lives"
            else -> ""
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
                "Killer",
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

        // ---- Players area: one card per seat, absorbs spare vertical space.
        Column(modifier = Modifier.weight(1f)) {
            state.players.forEachIndexed { idx, p ->
                val ps = state.perPlayer[idx]
                val active = idx == state.currentPlayerIndex && !state.isFinished
                val isWinner = state.winnerIndices.contains(idx)
                val eliminated = ps.isEliminated

                val containerColor = when {
                    isWinner -> MaterialTheme.colorScheme.secondary
                    eliminated -> MaterialTheme.colorScheme.surfaceVariant
                    active -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val contentColor = when {
                    isWinner -> MaterialTheme.colorScheme.onSecondary
                    eliminated -> MaterialTheme.colorScheme.onSurfaceVariant
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
                        defaultElevation = if ((active || isWinner) && !eliminated) 8.dp else 1.dp,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1.6f)) {
                            Text(
                                p.name + if (isWinner) "  🏆" else "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                            )
                            Text(
                                "D${ps.number}",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor,
                            )
                        }
                        // Status badge: ARMED vs the number still needed to arm.
                        Box(modifier = Modifier.weight(1.4f), contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    eliminated -> ""
                                    ps.isKiller -> "ARMED"
                                    else -> "needs D${ps.number}"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (ps.isKiller) FontWeight.Bold else FontWeight.Normal,
                                color = if (ps.isKiller) MaterialTheme.colorScheme.error
                                        else contentColor,
                            )
                        }
                        // Lives as hearts, or OUT when eliminated.
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                if (eliminated) "OUT" else "♥".repeat(ps.lives),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        } // end players area

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
                        TextButton(onClick = { vm.undoKiller() }) { Text("Undo last turn") }
                    }
                }
            }
            return
        }

        val me = state.currentPlayerIndex
        val meKiller = state.perPlayer[me].isKiller

        // ---- Pending entry header: who throws + a contextual hint. ---------
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.players[me].name} to throw",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$pendingTotal / $KILLER_MAX_DARTS darts",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    if (meKiller) "Hit opponents' doubles to take lives"
                    else "Hit D${state.perPlayer[me].number} to arm",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // ---- Pending entry: one row per seat, +/- to record that seat's
        // double, capped at KILLER_MAX_DARTS total. The active player's OWN
        // row is highlighted (that is how you arm / self-kill). ------------
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                state.players.forEachIndexed { seat, p ->
                    val ps = state.perPlayer[seat]
                    val isMe = seat == me
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "D${ps.number} (${p.name})" + if (isMe) " — you" else "",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isMe) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isMe) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val v = pending[seat] ?: 0
                        // An eliminated opponent can't lose more lives, so its row
                        // is inert; the active player's own row is never eliminated
                        // (eliminated seats are skipped), so it always stays usable.
                        val rowUsable = isMe || !ps.isEliminated
                        OutlinedButton(
                            onClick = { if (v > 0) pending[seat] = v - 1 },
                            enabled = v > 0,
                        ) { Text("−") }
                        Text(
                            v.toString(),
                            modifier = Modifier.padding(horizontal = 12.dp).width(28.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = {
                                if (pendingTotal < KILLER_MAX_DARTS && rowUsable) pending[seat] = v + 1
                            },
                            enabled = pendingTotal < KILLER_MAX_DARTS && rowUsable,
                        ) { Text("+") }
                    }
                }
            }
        }

        // ---- Bottom controls: pinned clear of the Android nav bar. --------
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { vm.undoKiller(); pending.clear() },
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                Icon(Icons.Default.Undo, contentDescription = "Undo last turn")
                Spacer(Modifier.size(4.dp))
                Text("Undo")
            }
            OutlinedButton(
                onClick = { pending.clear() },
                modifier = Modifier.weight(1f).height(54.dp),
            ) { Text("Clear") }
            Button(
                onClick = {
                    val hits = buildHits()
                    announceKiller(hits)
                    vm.applyKillerTurn(hits)
                    pending.clear()
                },
                enabled = canConfirm,
                modifier = Modifier.weight(2f).height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Default.Check, contentDescription = "Confirm turn")
                Spacer(Modifier.size(4.dp))
                Text(
                    "Confirm turn",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
