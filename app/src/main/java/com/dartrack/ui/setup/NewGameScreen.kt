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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.dartrack.data.PlayerRepository
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CricketState
import com.dartrack.model.HalfItState
import com.dartrack.model.Player
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
    val playerRepo = remember { PlayerRepository.get(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { playerRepo.load() }
    val players by playerRepo.players.collectAsState()

    var mode by remember { mutableStateOf(GameMode.X01) }
    var startScore by remember { mutableStateOf(501) }
    var doubleOut by remember { mutableStateOf(true) }
    var legsToWin by remember { mutableStateOf(1) }
    var setsToWin by remember { mutableStateOf(1) }
    // One nullable selected Player per seat; start with two empty seats.
    val seats = remember { mutableStateListOf<Player?>(null, null) }

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
        Text("Players (${seats.size})", fontWeight = FontWeight.SemiBold)
        seats.forEachIndexed { idx, selected ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SeatPicker(
                    label = "Player ${idx + 1}",
                    selected = selected,
                    allPlayers = players,
                    // Exclude players already chosen in OTHER seats.
                    takenIds = seats.filterIndexed { i, p -> i != idx && p != null }
                        .mapNotNull { it?.id }
                        .toSet(),
                    onSelect = { seats[idx] = it },
                    onCreate = { query ->
                        scope.launch {
                            val created = playerRepo.addPlayer(query)
                            seats[idx] = created
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (seats.size > 1) {
                    Spacer(Modifier.height(0.dp))
                    TextButton(onClick = { seats.removeAt(idx) }) { Text("Remove") }
                }
            }
        }
        if (seats.size < 4) {
            OutlinedButton(
                onClick = { seats.add(null) },
            ) { Text("Add player") }
        }

        Spacer(Modifier.height(24.dp))
        val selectedPlayers = seats.filterNotNull()
        val allSeatsFilled = selectedPlayers.size == seats.size
        val distinct = selectedPlayers.map { it.id }.toSet().size == selectedPlayers.size
        val canStart = seats.isNotEmpty() && allSeatsFilled && distinct
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    val gamePlayers = seats.filterNotNull()
                        .map { GamePlayer(name = it.name, id = it.id) }
                    val state = when (mode) {
                        GameMode.X01 -> X01State.new(gamePlayers, startScore, doubleOut, legsToWin, setsToWin)
                        GameMode.CRICKET -> CricketState.new(gamePlayers)
                        GameMode.HALF_IT -> HalfItState.new(gamePlayers)
                        GameMode.AROUND_CLOCK -> AroundTheClockState.new(gamePlayers)
                        GameMode.BOBS_27 -> BobsTwentySevenState.new(gamePlayers)
                        GameMode.SHANGHAI -> ShanghaiState.new(gamePlayers)
                        GameMode.CATCH_40 -> Catch40State.new(gamePlayers)
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
                enabled = canStart,
                modifier = Modifier.weight(1f),
            ) { Text("Start") }
        }
        if (!canStart) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (!allSeatsFilled) "Pick a registered player for every seat."
                else "Each seat must be a different player.",
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * A single seat: an [OutlinedTextField] whose typed text acts as a filter query,
 * with an anchored [ExposedDropdownMenuBox] listing matching registered players
 * (case-insensitive substring), excluding players already chosen in other seats,
 * plus a "Create new player" item at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeatPicker(
    label: String,
    selected: Player?,
    allPlayers: List<Player>,
    takenIds: Set<String>,
    onSelect: (Player) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // The text shown/typed. When a player is selected we display its name.
    var query by remember { mutableStateOf("") }

    // Keep the field text in sync with the selected player (e.g. after create).
    LaunchedEffect(selected) {
        if (selected != null) query = selected.name
    }

    val trimmed = query.trim()
    val matches = allPlayers.filter { p ->
        p.id !in takenIds &&
            (trimmed.isEmpty() || p.name.contains(trimmed, ignoreCase = true))
    }
    val exactExists = allPlayers.any { it.name.equals(trimmed, ignoreCase = true) }
    val canCreate = trimmed.isNotEmpty() && !exactExists

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = true
            },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            matches.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    onClick = {
                        onSelect(p)
                        query = p.name
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(if (trimmed.isEmpty()) "＋ Create new player" else "＋ Create \"$trimmed\"") },
                enabled = canCreate,
                onClick = {
                    onCreate(trimmed)
                    expanded = false
                },
            )
        }
    }
}
