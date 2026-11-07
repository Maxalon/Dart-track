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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import com.dartrack.data.AccountRepository
import com.dartrack.data.FriendsRepository
import com.dartrack.data.GameRecord
import com.dartrack.data.GameRepository
import com.dartrack.data.PlayerOption
import com.dartrack.data.PlayerRepository
import com.dartrack.data.friendsFor
import com.dartrack.data.playerOptions
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.Player
import com.dartrack.model.stateForMode
import com.dartrack.model.bot.BotLevel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * What a single seat is filled with on the setup screen. A seat is either a
 * registered [Human] player (possibly not yet chosen, hence the nullable
 * [Player]) or a [Cpu] opponent at a fixed [BotLevel]. CPU seats are only
 * offered for X01 / Count-Up / Cricket (see [NewGameScreen]); for every other
 * mode the seat list only ever holds [Human]s.
 */
private sealed interface SeatChoice {
    data class Human(val player: Player?) : SeatChoice
    data class Cpu(val level: BotLevel) : SeatChoice
}

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

    val accountRepo = remember { AccountRepository.get(context) }
    val friendsRepo = remember { FriendsRepository.get(context) }

    LaunchedEffect(Unit) { playerRepo.load() }
    val players by playerRepo.players.collectAsState()
    val account by accountRepo.account.collectAsState()
    val friendsCache by friendsRepo.cache.collectAsState()
    // Local players plus (when logged in) accepted friends from the cached list;
    // the logged-in user's own "me" player is listed first.
    val pickOptions = playerOptions(players, friendsFor(friendsCache, account).friends, account?.mePlayerId)

    var mode by remember { mutableStateOf(GameMode.X01) }
    // Per-mode options (X01 seeded from the user's saved defaults); the same
    // editor + options->state mapping is used by NewTournamentScreen.
    var options by rememberSeededGameOptions()
    // One choice per seat; start with two empty human seats.
    val seats = remember {
        mutableStateListOf<SeatChoice>(SeatChoice.Human(null), SeatChoice.Human(null))
    }
    // Logged in: seat 1 defaults to the "me" player (once, as soon as it's known).
    var meSeeded by remember { mutableStateOf(false) }
    val mePlayer = account?.mePlayerId?.let { id -> players.firstOrNull { it.id == id } }
    LaunchedEffect(mePlayer) {
        if (meSeeded || mePlayer == null) return@LaunchedEffect
        meSeeded = true
        val first = seats.firstOrNull()
        val alreadySeated = seats.any { it is SeatChoice.Human && it.player?.id == mePlayer.id }
        if (first is SeatChoice.Human && first.player == null && !alreadySeated) {
            seats[0] = SeatChoice.Human(mePlayer)
        }
    }
    // CPU seats only make sense for the solo-friendly scoring modes.
    val botsAllowed = mode == GameMode.X01 || mode == GameMode.COUNT_UP ||
        mode == GameMode.CRICKET

    // Switching to a mode that doesn't support CPU opponents must not silently
    // carry bot seats over: revert any CPU seat back to an empty human seat so
    // those modes behave exactly as before this feature.
    LaunchedEffect(botsAllowed) {
        if (!botsAllowed) {
            seats.forEachIndexed { i, s ->
                if (s is SeatChoice.Cpu) seats[i] = SeatChoice.Human(null)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
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
                        GameMode.COUNT_UP -> "Count-Up"
                        GameMode.CHECKOUT_TRAINER -> "Checkout Trainer"
                        GameMode.BASEBALL -> "Baseball"
                        GameMode.GOLF -> "Golf"
                        GameMode.GOTCHA -> "Gotcha"
                        GameMode.KILLER -> "Killer"
                        GameMode.BERMUDA -> "Bermuda"
                    }) },
                )
            }
        }

        GameOptionsEditor(mode = mode, options = options, onChange = { options = it })

        Spacer(Modifier.height(16.dp))
        Text("Players (${seats.size})", fontWeight = FontWeight.SemiBold)
        // Ids already taken by registered-player (human) seats — used to stop the
        // same player being picked twice. CPU seats have no registry id.
        val takenIds = seats.filterIsInstance<SeatChoice.Human>()
            .mapNotNull { it.player?.id }
            .toSet()
        seats.forEachIndexed { idx, seat ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (seat) {
                        is SeatChoice.Human -> SeatPicker(
                            label = "Player ${idx + 1}",
                            selected = seat.player,
                            options = pickOptions,
                            // Exclude players already chosen in OTHER human seats.
                            takenIds = takenIds - (seat.player?.id?.let { setOf(it) } ?: emptySet()),
                            onSelect = { option ->
                                val p = option.player
                                if (p != null) {
                                    seats[idx] = SeatChoice.Human(p)
                                } else {
                                    // A friend without a local player yet: create it
                                    // (linked to their account) and seat it.
                                    scope.launch {
                                        seats[idx] = SeatChoice.Human(friendsRepo.playerFor(option.friend!!))
                                    }
                                }
                            },
                            onCreate = { query ->
                                scope.launch {
                                    val created = playerRepo.addPlayer(query)
                                    seats[idx] = SeatChoice.Human(created)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        is SeatChoice.Cpu -> Text(
                            "Player ${idx + 1}: CPU",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (seats.size > 1) {
                        Spacer(Modifier.height(0.dp))
                        TextButton(onClick = { seats.removeAt(idx) }) { Text("Remove") }
                    }
                }
                // CPU controls — only offered for X01 / Count-Up. Toggling here
                // swaps the seat between a registered player and a CPU; for a CPU
                // seat the level chips pick its difficulty.
                if (botsAllowed) {
                    FlowRow(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = seat is SeatChoice.Human,
                            onClick = { seats[idx] = SeatChoice.Human(null) },
                            label = { Text("Player") },
                        )
                        BotLevel.entries.forEach { level ->
                            FilterChip(
                                selected = seat is SeatChoice.Cpu && seat.level == level,
                                onClick = { seats[idx] = SeatChoice.Cpu(level) },
                                label = { Text("CPU ${level.label}") },
                            )
                        }
                    }
                }
            }
        }
        if (seats.size < 4) {
            OutlinedButton(
                onClick = { seats.add(SeatChoice.Human(null)) },
            ) { Text("Add player") }
        }

        Spacer(Modifier.height(24.dp))
        // A human seat counts as "filled" only once a player is chosen; a CPU
        // seat is always filled. We additionally require at least one human.
        val humanSeats = seats.filterIsInstance<SeatChoice.Human>()
        val chosenHumans = humanSeats.mapNotNull { it.player }
        val allSeatsFilled = humanSeats.all { it.player != null }
        val distinctHumans = chosenHumans.map { it.id }.toSet().size == chosenHumans.size
        val hasHuman = chosenHumans.isNotEmpty()
        // Killer requires at least two players (the model rejects a single seat).
        val enoughForKiller = mode != GameMode.KILLER || seats.size >= 2
        val canStart = seats.isNotEmpty() && allSeatsFilled && distinctHumans && hasHuman &&
            enoughForKiller
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    val gamePlayers = seats.map { seat ->
                        when (seat) {
                            is SeatChoice.Human -> {
                                // canStart guarantees every human seat has a player.
                                val p = seat.player!!
                                GamePlayer(name = p.name, id = p.id)
                            }
                            is SeatChoice.Cpu -> GamePlayer(
                                name = "CPU · ${seat.level.label}",
                                id = "bot:" + UUID.randomUUID().toString(),
                                isBot = true,
                                botLevel = seat.level,
                            )
                        }
                    }
                    val state = stateForMode(mode, gamePlayers, options)
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
                when {
                    !enoughForKiller -> "Killer needs at least 2 players."
                    !allSeatsFilled -> "Pick a registered player for every non-CPU seat."
                    !hasHuman -> "Add at least one human player."
                    else -> "Each seat must be a different player."
                },
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * A single seat: an [OutlinedTextField] whose typed text acts as a filter query,
 * with an anchored [ExposedDropdownMenuBox] listing matching [options]
 * (registered players and friends, case-insensitive substring; friends carry a
 * small "friend" label), excluding players already chosen in other seats, plus
 * a "Create new player" item at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeatPicker(
    label: String,
    selected: Player?,
    options: List<PlayerOption>,
    takenIds: Set<String>,
    onSelect: (PlayerOption) -> Unit,
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
    val matches = options.filter { o ->
        o.player?.id !in takenIds &&
            (trimmed.isEmpty() || o.name.contains(trimmed, ignoreCase = true))
    }
    val exactExists = options.any { it.name.equals(trimmed, ignoreCase = true) }
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
            matches.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.name) },
                    trailingIcon = if (o.isFriend) ({ FriendLabel() }) else null,
                    onClick = {
                        onSelect(o)
                        query = o.name
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
