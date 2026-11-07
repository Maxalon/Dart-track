package com.dartrack.ui.players

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.dartrack.data.AccountRepository
import com.dartrack.data.FriendsRepository
import com.dartrack.data.GameRepository
import com.dartrack.data.MergeDecision
import com.dartrack.data.PlayerRepository
import com.dartrack.data.friendsFor
import com.dartrack.data.mergeDecision
import com.dartrack.net.FriendEntry
import com.dartrack.data.normalizePlayerName
import com.dartrack.data.transferableGuests
import com.dartrack.data.visiblePlayers
import com.dartrack.model.Player
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Player management: rename, delete, and merge registered players. Reads the
 * registry from [PlayerRepository]; mutations run on a coroutine scope and
 * surface a brief Toast. Merge reassigns a source player's games to a target
 * (via [PlayerRepository.merge] -> [GameRepository.reassignPlayer]) then drops
 * the source from the registry. Logged in, "Move a guest's games to a friend"
 * opens [TransferGuestDialog]. Hidden players (synced strangers) aren't listed.
 * Merge follows [mergeDecision]: guest → guest merges locally; guest → friend
 * becomes the (server-logged, one-per-friend) transfer; anything else touching
 * an account-linked player is refused.
 */
@Composable
fun PlayerManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val playerRepo = remember { PlayerRepository.get(context) }
    val gameRepo = remember { GameRepository.get(context) }
    val scope = rememberCoroutineScope()

    val allPlayers by playerRepo.players.collectAsState()
    val players = visiblePlayers(allPlayers)
    val accountRepo = remember { AccountRepository.get(context) }
    val friendsRepo = remember { FriendsRepository.get(context) }
    val account by accountRepo.account.collectAsState()
    val friendsCache by friendsRepo.cache.collectAsState()
    val canTransfer = account != null &&
        friendsFor(friendsCache, account).friends.isNotEmpty() &&
        transferableGuests(allPlayers, account?.mePlayerId).isNotEmpty()
    var showTransfer by remember { mutableStateOf(false) }
    // Guest → friend chosen in "Merge players": handed to the transfer flow.
    var mergeTransfer by remember { mutableStateOf<Pair<String, FriendEntry>?>(null) }

    var renameTarget by remember { mutableStateOf<Player?>(null) }
    var deleteTarget by remember { mutableStateOf<Player?>(null) }
    var showMerge by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Manage players",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(4.dp))

        if (players.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No players yet. Add players when starting a game.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        OutlinedButton(
            onClick = { showMerge = true },
            enabled = players.size >= 2,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            Text("Merge players")
        }
        if (canTransfer) {
            OutlinedButton(
                onClick = { showTransfer = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                Text("Move a guest's games to a friend")
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        ) {
            items(players, key = { it.id }) { player ->
                PlayerRow(
                    player = player,
                    onRename = { renameTarget = player },
                    onDelete = { deleteTarget = player },
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            player = target,
            existing = players,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                scope.launch {
                    val ok = playerRepo.rename(target.id, newName)
                    toast(if (ok) "Renamed to ${newName.trim()}" else "Rename failed (name in use?)")
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete player?") },
            text = {
                Text(
                    "Remove \"${target.name}\" from the player list? " +
                        "Existing games keep their recorded names.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val ok = playerRepo.delete(target.id)
                        toast(if (ok) "Deleted ${target.name}" else "Nothing to delete")
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showTransfer) {
        TransferGuestDialog(
            onDismiss = { showTransfer = false },
            onDone = { msg ->
                showTransfer = false
                toast(msg)
            },
        )
    }

    mergeTransfer?.let { (guestId, friend) ->
        TransferGuestDialog(
            initialFriend = friend,
            initialGuestId = guestId,
            onDismiss = { mergeTransfer = null },
            onDone = { msg ->
                mergeTransfer = null
                toast(msg)
            },
        )
    }

    if (showMerge) {
        val friends = friendsFor(friendsCache, account).friends
        MergeDialog(
            players = players,
            decide = { from, into -> mergeDecision(from, into, friends) },
            onDismiss = { showMerge = false },
            onConfirm = { from, into ->
                showMerge = false
                when (val decision = mergeDecision(from, into, friends)) {
                    MergeDecision.Allowed -> scope.launch {
                        val moved = playerRepo.merge(from.id, into.id, gameRepo)
                        toast("Merged ${from.name} into ${into.name} ($moved games moved)")
                    }
                    is MergeDecision.Transfer -> mergeTransfer = from.id to decision.friend
                    is MergeDecision.Blocked -> toast(decision.message)
                }
            },
        )
    }
}

@Composable
private fun PlayerRow(
    player: Player,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                player.name,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRename) { Text("Rename") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun RenameDialog(
    player: Player,
    existing: List<Player>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(player.name) }
    val trimmed = text.trim()
    val key = normalizePlayerName(trimmed)
    val clashes = trimmed.isNotEmpty() &&
        existing.any { it.id != player.id && normalizePlayerName(it.name) == key }
    val valid = trimmed.isNotEmpty() && !clashes

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename player") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Name") },
                    isError = trimmed.isNotEmpty() && clashes,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (clashes) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Another player already uses that name.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = valid) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun MergeDialog(
    players: List<Player>,
    decide: (from: Player, into: Player) -> MergeDecision,
    onDismiss: () -> Unit,
    onConfirm: (from: Player, into: Player) -> Unit,
) {
    var from by remember { mutableStateOf<Player?>(null) }
    var into by remember { mutableStateOf<Player?>(null) }
    val distinct = from != null && into != null && from!!.id != into!!.id
    val decision = if (distinct) decide(from!!, into!!) else null
    val valid = distinct && decision !is MergeDecision.Blocked

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge players") },
        text = {
            Column {
                Text(
                    "Move all games from one player to another, then remove the first.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                PlayerDropdown(
                    label = "Move games from",
                    players = players,
                    selected = from,
                    onSelect = { from = it },
                )
                Spacer(Modifier.height(8.dp))
                PlayerDropdown(
                    label = "Into",
                    players = players,
                    selected = into,
                    onSelect = { into = it },
                )
                if (from != null && into != null && from!!.id == into!!.id) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pick two different players.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
                when (decision) {
                    is MergeDecision.Blocked -> {
                        Spacer(Modifier.height(4.dp))
                        Text(decision.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    is MergeDecision.Transfer -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${into!!.name} is your friend: this moves ${from!!.name}'s games to their account " +
                                "(one guest per friend).",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(from!!, into!!) },
                enabled = valid,
            ) { Text(if (decision is MergeDecision.Transfer) "Next" else "Merge") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerDropdown(
    label: String,
    players: List<Player>,
    selected: Player?,
    onSelect: (Player) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            players.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    onClick = {
                        onSelect(p)
                        expanded = false
                    },
                )
            }
        }
    }
}
