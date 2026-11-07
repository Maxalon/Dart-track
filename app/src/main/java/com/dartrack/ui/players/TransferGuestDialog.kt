package com.dartrack.ui.players

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.dartrack.data.GameRepository
import com.dartrack.data.LinkOutcome
import com.dartrack.data.PlayerRepository
import com.dartrack.data.SyncManager
import com.dartrack.data.friendsFor
import com.dartrack.data.guestGameIds
import com.dartrack.data.transferableGuests
import com.dartrack.net.FriendEntry
import kotlinx.coroutines.launch

/**
 * "Move a guest's games to a friend": pick a local guest and an accepted
 * friend (either may be preselected), then confirm. The guest's games move to
 * the friend for good — on this phone (the guest merges into the friend's
 * player) and on the server (they join the friend's history). Only one guest
 * per friend; the dialog checks that before offering to confirm.
 * [onDone] gets a short result message.
 */
@Composable
fun TransferGuestDialog(
    initialFriend: FriendEntry? = null,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val context = LocalContext.current
    val accountRepo = remember { AccountRepository.get(context) }
    val friendsRepo = remember { FriendsRepository.get(context) }
    val playerRepo = remember { PlayerRepository.get(context) }
    val gameRepo = remember { GameRepository.get(context) }
    val sync = remember { SyncManager.get(context) }
    val account by accountRepo.account.collectAsState()
    val cache by friendsRepo.cache.collectAsState()
    val players by playerRepo.players.collectAsState()
    val games by gameRepo.games.collectAsState()
    val scope = rememberCoroutineScope()

    val friends = friendsFor(cache, account).friends
    val guests = transferableGuests(players, account?.mePlayerId)
    var friend by remember { mutableStateOf(initialFriend) }
    var guestId by remember { mutableStateOf<String?>(null) }
    var blocked by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // One guest per friend: re-check (locally and against the server's links)
    // whenever the chosen friend changes.
    LaunchedEffect(friend?.id) {
        blocked = null
        val f = friend ?: return@LaunchedEffect
        checking = true
        blocked = sync.linkBlockReason(f)
        checking = false
    }

    val guest = guests.firstOrNull { it.id == guestId }
    val count = guest?.let { guestGameIds(games, it.id).size } ?: 0

    if (confirming && guest != null && friend != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirming = false },
            title = { Text("Move games?") },
            text = {
                Column {
                    Text("$count games of ${guest.name} will move to ${friend!!.username}. This can't be undone from the app.")
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val outcome = sync.linkGuest(guest.id, friend!!)
                            busy = false
                            when (outcome) {
                                is LinkOutcome.Done ->
                                    onDone("Moved ${outcome.moved} games of ${guest.name} to ${friend!!.username}.")
                                is LinkOutcome.Blocked -> error = outcome.message
                                is LinkOutcome.Failed -> error = outcome.message
                            }
                        }
                    },
                ) { Text(if (busy) "Moving…" else "Move") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { confirming = false }) { Text("Cancel") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move a guest's games to a friend") },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Played with a friend as a guest before they had an account? Move that guest's games " +
                        "to your friend: the guest disappears and the games count for your friend.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Label("Guest")
                if (guests.isEmpty()) {
                    Text("No guests on this phone.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                guests.forEach { p ->
                    Choice(selected = guestId == p.id, label = p.name) { guestId = p.id }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Label("Friend")
                if (friends.isEmpty()) {
                    Text("No friends yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                friends.forEach { f ->
                    Choice(selected = friend?.id == f.id, label = f.username) { friend = f }
                }
                val note = when {
                    checking -> "Checking…"
                    else -> blocked
                }
                note?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = if (blocked != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = guest != null && friend != null && blocked == null && !checking,
                onClick = { error = null; confirming = true },
            ) { Text("Next") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
