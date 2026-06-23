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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dartrack.data.GameRepository
import com.dartrack.model.Checkout
import com.dartrack.model.CheckoutTrainerState
import com.dartrack.viewmodel.GameViewModel

/**
 * Checkout Trainer entry screen, built to the app's single-screen layout
 * convention: everything fits on one screen (no verticalScroll). A compact header
 * shows the active player, the position in the ladder ("Target X/N") and the
 * current finish ("Finish 80"); a checkout-suggestion chip surfaces the suggested
 * route for that target (from [Checkout]); a weighted players area highlights the
 * active thrower with a big hit count while inactive players stay compact; and the
 * bottom entry buttons (Hit 1 / Hit 2 / Hit 3 / Miss) stay clear of the system
 * bars. "Hit N" records a checkout using N darts; "Miss" records a failed attempt.
 */
@Composable
fun CheckoutTrainerScreen(
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
    val state = record?.state as? CheckoutTrainerState ?: return

    val caller = rememberCaller()
    var callerOn by rememberSaveable { mutableStateOf(false) }

    // Record an attempt, announcing the outcome. "Checkout!" on a hit; when the
    // hit is the final attempt of the ladder it becomes "Game shot!". A miss is
    // announced as "No checkout". Mirrors the other screens' caller behaviour.
    fun recordAttempt(hit: Boolean, darts: Int) {
        if (callerOn) {
            // The attempt that finishes the game is the last seat on the last
            // target; derive that from the pre-apply state without mutating it.
            val lastTarget = state.currentTargetIndex >= state.targets.lastIndex
            val lastSeat = state.currentPlayerIndex >= state.players.lastIndex
            val finishing = lastTarget && lastSeat
            val text = when {
                hit && finishing -> "Checkout! Game shot!"
                hit -> "Checkout!"
                else -> "No checkout"
            }
            caller.speak(text, callerOn)
        }
        vm.applyCheckoutAttempt(hit, darts)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ---- Compact header: active player + ladder position + finish. ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (state.isFinished) {
                    Text(
                        "Checkout Trainer · finished",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val activeIdx = state.currentPlayerIndex
                    val activeName = state.players.getOrNull(activeIdx)?.name ?: ""
                    Text(
                        "Target ${state.currentTargetIndex + 1}/${state.targets.size} · $activeName to throw",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Finish ${state.currentTarget}",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            TextButton(onClick = { callerOn = !callerOn }) {
                Text(if (callerOn) "🔊" else "🔇")
            }
            TextButton(onClick = onExit) { Text("Exit") }
        }

        // ---- Checkout suggestion chip for the current target. -------------
        if (!state.isFinished) {
            val routes = Checkout.suggest(state.currentTarget, doubleOut = true)
            if (routes.isNotEmpty()) {
                CheckoutSuggestionChip(state.currentTarget, routes)
            }
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
                                "${ps.attempts.size} attempts · ${ps.dartsOnHits} darts on hits",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                ps.hits.toString(),
                                fontSize = if (active) 56.sp else 30.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                if (ps.hits == 1) "hit" else "hits",
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
                        onClick = { vm.undoCheckout() },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Undo") }
                    Button(
                        onClick = onExit,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Home") }
                }
            } else {
                Text(
                    "Did they check out ${state.currentTarget}?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                // Hit N = checked out using N darts. One tap records the attempt.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (1..3).forEach { n ->
                        Button(
                            onClick = { recordAttempt(hit = true, darts = n) },
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text("Hit $n", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.undoCheckout() },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Undo") }
                    Button(
                        onClick = { recordAttempt(hit = false, darts = 0) },
                        modifier = Modifier.weight(2f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text("Miss", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * Prominent checkout suggestion for the current target, using the
 * "checkout/scored" accent (tertiaryContainer) consistent with the X01 screen.
 * Surfaces up to two routes from [Checkout.suggest].
 */
@Composable
private fun CheckoutSuggestionChip(target: Int, routes: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
                "Route",
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
