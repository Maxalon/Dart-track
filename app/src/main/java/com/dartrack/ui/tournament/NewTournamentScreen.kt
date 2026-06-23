package com.dartrack.ui.tournament

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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.dartrack.data.PlayerRepository
import com.dartrack.data.TournamentCompetitor
import com.dartrack.data.TournamentFormat
import com.dartrack.data.TournamentRepository
import com.dartrack.data.createSingleEliminationTournament
import com.dartrack.data.createTournament
import com.dartrack.data.fillWithCpus
import com.dartrack.model.GameMode
import com.dartrack.model.Player
import com.dartrack.model.bot.BotLevel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Setup screen for a new round-robin tournament: a name field, a competitor
 * builder (add registered players and/or CPUs, remove any), a "fill to N with
 * CPUs" convenience, and a mode picker over all 12 [GameMode]s. "Create" builds
 * the [com.dartrack.data.TournamentState] via [createTournament], persists it, and
 * navigates to it.
 *
 * Mirrors NewGameScreen's conventions: the same registered-[Player] picker
 * (anchored [ExposedDropdownMenuBox] over [PlayerRepository]), the same
 * [BotLevel] chip idiom, the mode [FilterChip] list, and the
 * safeDrawingPadding + verticalScroll modal shell.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewTournamentScreen(
    onCancel: () -> Unit,
    onCreated: (id: String) -> Unit,
) {
    val context = LocalContext.current
    val playerRepo = remember { PlayerRepository.get(context) }
    val tournamentRepo = remember { TournamentRepository.get(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { playerRepo.load() }
    val players by playerRepo.players.collectAsState()

    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(GameMode.X01) }
    // Round-robin (every pairing once) vs single-elimination knockout bracket.
    var format by remember { mutableStateOf(TournamentFormat.ROUND_ROBIN) }
    // The competitors built so far, in pick order. CPUs cycle through BotLevel as
    // they are added so a quick "Add CPU" gives a believable mixed field.
    val competitors = remember { mutableStateListOf<TournamentCompetitor>() }
    // Target size for the "fill to N with CPUs" stepper; defaults to a small bracket.
    var fillTarget by remember { mutableStateOf(4) }
    // Difficulty for the next CPU added via "Add CPU".
    var cpuLevel by remember { mutableStateOf(BotLevel.MEDIUM) }

    // Registered ids already chosen, so the picker can hide duplicates.
    val takenIds = competitors.mapNotNull { it.playerId }.toSet()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("New tournament", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Text("Format", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = format == TournamentFormat.ROUND_ROBIN,
                onClick = { format = TournamentFormat.ROUND_ROBIN },
                label = { Text("Round robin") },
            )
            FilterChip(
                selected = format == TournamentFormat.SINGLE_ELIMINATION,
                onClick = { format = TournamentFormat.SINGLE_ELIMINATION },
                label = { Text("Knockout") },
            )
        }

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
                    label = { Text(modeLabel(m)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Competitors (${competitors.size})", fontWeight = FontWeight.SemiBold)
        if (competitors.isEmpty()) {
            Text(
                "Add at least 2 competitors.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        competitors.forEachIndexed { idx, c ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${idx + 1}. ${c.name}",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { competitors.removeAt(idx) }) { Text("Remove") }
            }
        }

        Spacer(Modifier.height(8.dp))
        // Add a registered player via the same picker idiom as NewGameScreen.
        CompetitorPicker(
            allPlayers = players,
            takenIds = takenIds,
            onSelect = { p ->
                competitors.add(TournamentCompetitor(name = p.name, playerId = p.id))
            },
            onCreate = { query ->
                scope.launch {
                    val created = playerRepo.addPlayer(query)
                    if (competitors.none { it.playerId == created.id }) {
                        competitors.add(
                            TournamentCompetitor(name = created.name, playerId = created.id),
                        )
                    }
                }
            },
        )

        Spacer(Modifier.height(12.dp))
        Text("Add CPU", fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BotLevel.entries.forEach { level ->
                FilterChip(
                    selected = cpuLevel == level,
                    onClick = { cpuLevel = level },
                    label = { Text(level.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                competitors.add(
                    TournamentCompetitor(
                        name = "CPU (${cpuLevel.label})",
                        isBot = true,
                        botLevel = cpuLevel,
                    ),
                )
            },
        ) { Text("Add CPU (${cpuLevel.label})") }

        Spacer(Modifier.height(16.dp))
        // Fill-to-N convenience: pads the current field up to N with cycling CPUs.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Fill with CPUs", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Target size", modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { if (fillTarget > 2) fillTarget-- },
                        enabled = fillTarget > 2,
                    ) { Text("−") }
                    Text(
                        "$fillTarget",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    OutlinedButton(
                        onClick = { if (fillTarget < MAX_FILL) fillTarget++ },
                        enabled = fillTarget < MAX_FILL,
                    ) { Text("+") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val filled = fillWithCpus(
                            humans = competitors.toList(),
                            targetSize = fillTarget,
                            levels = BotLevel.entries.toList(),
                        )
                        competitors.clear()
                        competitors.addAll(filled)
                    },
                    enabled = competitors.size < fillTarget,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Fill to $fillTarget with CPUs") }
            }
        }

        Spacer(Modifier.height(24.dp))
        val canCreate = name.isNotBlank() && competitors.size >= 2
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    val id = UUID.randomUUID().toString()
                    val t = if (format == TournamentFormat.SINGLE_ELIMINATION) {
                        createSingleEliminationTournament(
                            id = id,
                            name = name.trim(),
                            competitors = competitors.toList(),
                            mode = mode,
                            createdAtEpochMs = System.currentTimeMillis(),
                        )
                    } else {
                        createTournament(
                            id = id,
                            name = name.trim(),
                            competitors = competitors.toList(),
                            mode = mode,
                            createdAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                    scope.launch {
                        tournamentRepo.upsert(t)
                        onCreated(t.id)
                    }
                },
                enabled = canCreate,
                modifier = Modifier.weight(1f),
            ) { Text("Create") }
        }
        if (!canCreate) {
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    name.isBlank() -> "Give the tournament a name."
                    else -> "Add at least 2 competitors."
                },
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * An [OutlinedTextField] whose typed text filters registered players in an
 * anchored [ExposedDropdownMenuBox]; choosing one calls [onSelect] (and clears
 * the field so the next pick starts fresh), and a "Create" item at the bottom
 * adds a brand-new player. Mirrors NewGameScreen's SeatPicker, adapted to APPEND
 * to a list rather than fill a fixed seat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompetitorPicker(
    allPlayers: List<Player>,
    takenIds: Set<String>,
    onSelect: (Player) -> Unit,
    onCreate: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

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
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = true
            },
            label = { Text("Add player") },
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
                        query = ""
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(if (trimmed.isEmpty()) "＋ Create new player" else "＋ Create \"$trimmed\"") },
                enabled = canCreate,
                onClick = {
                    onCreate(trimmed)
                    query = ""
                    expanded = false
                },
            )
        }
    }
}

/** Upper bound for the fill-to-N stepper; keeps brackets sane for personal use. */
private const val MAX_FILL = 16
