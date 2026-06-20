package com.dartrack.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Around the Clock", fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onExit) { Text("Exit") }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!state.isFinished) {
                    val curPlayer = state.players[state.currentPlayerIndex]
                    val curTarget = state.currentTarget(state.currentPlayerIndex)
                    Text("${curPlayer.name}'s turn", fontSize = 14.sp)
                    Text(
                        "Aiming at: $curTarget",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Enter how many of the next targets you hit (0–$AROUND_CLOCK_MAX_HITS).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Game complete", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        state.players.forEachIndexed { idx, p ->
            val ps = state.perPlayer[idx]
            val active = idx == state.currentPlayerIndex && !state.isFinished
            val isWinner = state.winnerIndices.contains(idx)
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
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
                            p.name + if (isWinner) " 🏆" else "",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (ps.finished) "Finished · ${ps.darts} darts"
                            else "Aiming ${ps.currentTarget} · ${ps.darts} darts",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${ps.cleared}/$AROUND_CLOCK_LAST_TARGET",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        if (state.isFinished) {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Winner: ${state.winnerIndices.joinToString { state.players[it].name }}",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onExit) { Text("Back to home") }
                    TextButton(onClick = { vm.undoAroundClock(); pending = null }) {
                        Text("Undo last turn")
                    }
                }
            }
        } else {
            // Hit selector: 0..AROUND_CLOCK_MAX_HITS.
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
