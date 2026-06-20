package com.dartrack.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dartrack.data.GameRecord
import com.dartrack.data.GameRepository
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CricketState
import com.dartrack.model.HalfItState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewGameScreen(
    onCancel: () -> Unit,
    onStart: (recordId: String) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { GameRepository.get(context) }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(GameMode.X01) }
    var startScore by remember { mutableStateOf(501) }
    var doubleOut by remember { mutableStateOf(true) }
    var legsToWin by remember { mutableStateOf(1) }
    var setsToWin by remember { mutableStateOf(1) }
    val playerNames = remember { mutableStateListOf("Player 1", "Player 2") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("New game", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Text("Mode", fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GameMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = { Text(when (m) {
                        GameMode.X01 -> "X01"
                        GameMode.CRICKET -> "Cricket"
                        GameMode.HALF_IT -> "Half-It"
                        GameMode.AROUND_CLOCK -> "Around the Clock"
                        GameMode.BOBS_27 -> "Bob's 27"
                        GameMode.SHANGHAI -> "Shanghai"
                        GameMode.CATCH_40 -> "Catch 40"
                    }) },
                )
            }
        }

        if (mode == GameMode.X01) {
            Spacer(Modifier.height(16.dp))
            Text("Start score", fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                X01State.SUPPORTED_STARTS.forEach { s ->
                    FilterChip(
                        selected = startScore == s,
                        onClick = { startScore = s },
                        label = { Text(s.toString()) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = doubleOut, onCheckedChange = { doubleOut = it })
                Spacer(Modifier.height(0.dp))
                Text("  Finish on a double (double-out)")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (setsToWin > 1) "Legs per set (first to)" else "Legs (first to)",
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                X01State.SUPPORTED_LEGS.forEach { n ->
                    FilterChip(
                        selected = legsToWin == n,
                        onClick = { legsToWin = n },
                        label = { Text(if (n == 1) "Single leg" else "First to $n") },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Sets (first to)", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                X01State.SUPPORTED_SETS.forEach { n ->
                    FilterChip(
                        selected = setsToWin == n,
                        onClick = { setsToWin = n },
                        label = { Text(if (n == 1) "No sets" else "First to $n") },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Players (${playerNames.size})", fontWeight = FontWeight.SemiBold)
        playerNames.forEachIndexed { idx, name ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { playerNames[idx] = it },
                    label = { Text("Player ${idx + 1}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (playerNames.size > 1) {
                    Spacer(Modifier.height(0.dp))
                    TextButton(onClick = { playerNames.removeAt(idx) }) { Text("Remove") }
                }
            }
        }
        if (playerNames.size < 4) {
            OutlinedButton(
                onClick = { playerNames.add("Player ${playerNames.size + 1}") },
            ) { Text("Add player") }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    val players = playerNames
                        .map { it.trim().ifBlank { "Player" } }
                        .map { GamePlayer(it) }
                    val state = when (mode) {
                        GameMode.X01 -> X01State.new(players, startScore, doubleOut, legsToWin, setsToWin)
                        GameMode.CRICKET -> CricketState.new(players)
                        GameMode.HALF_IT -> HalfItState.new(players)
                        GameMode.AROUND_CLOCK -> AroundTheClockState.new(players)
                        GameMode.BOBS_27 -> BobsTwentySevenState.new(players)
                        GameMode.SHANGHAI -> ShanghaiState.new(players)
                        GameMode.CATCH_40 -> Catch40State.new(players)
                    }
                    val now = System.currentTimeMillis()
                    val record = GameRecord(
                        id = UUID.randomUUID().toString(),
                        mode = mode,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        state = state,
                    )
                    scope.launch {
                        repo.upsert(record)
                        onStart(record.id)
                    }
                },
                enabled = playerNames.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Start") }
        }
        Spacer(Modifier.height(16.dp))
    }
}
