package com.dartrack.ui.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dartrack.data.AccountRepository
import com.dartrack.data.FriendsRepository
import com.dartrack.data.PlayerRepository
import com.dartrack.data.SyncManager
import com.dartrack.data.friendsFor
import com.dartrack.data.transferableGuests
import com.dartrack.ui.players.TransferGuestDialog
import com.dartrack.net.ApiResult
import com.dartrack.net.FriendEntry
import com.dartrack.net.userMessage
import kotlinx.coroutines.launch

/**
 * Friends: an "Add friend" field (exact username, case ignored) with result
 * feedback, then Requests (accept / decline), Friends (remove, confirmed) and
 * Sent (cancel). Pull down to refresh (which also syncs game history); it also
 * refreshes on open. A friend's "Move games" moves a local guest's games to
 * them ([TransferGuestDialog]). Shows the cached list when offline. Logged out, it only shows a "log in" hint
 * ([onLogIn]). Header idiom matches SettingsScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onBack: () -> Unit,
    onLogIn: () -> Unit,
) {
    val context = LocalContext.current
    val accountRepo = remember { AccountRepository.get(context) }
    val friendsRepo = remember { FriendsRepository.get(context) }
    val account by accountRepo.account.collectAsState()
    val cache by friendsRepo.cache.collectAsState()
    val refreshing by friendsRepo.refreshing.collectAsState()
    val sync = remember { SyncManager.get(context) }
    val players by remember { PlayerRepository.get(context) }.players.collectAsState()
    val scope = rememberCoroutineScope()
    var transferTo by remember { mutableStateOf<FriendEntry?>(null) }

    // Feedback line under the add field / the last action's error.
    var feedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var offline by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<FriendEntry?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            offline = friendsRepo.refresh() is ApiResult.NetworkError
        }
    }

    /** Runs a friend action; shows its error (if any) in the feedback line. */
    fun act(success: String?, block: suspend () -> ApiResult<*>) {
        busy = true
        scope.launch {
            val result = block()
            busy = false
            feedback = when (result) {
                is ApiResult.Ok -> success?.let { it to false }
                is ApiResult.Failure -> result.userMessage() to true
            }
        }
    }

    LaunchedEffect(account?.userId) {
        if (account != null) {
            friendsRepo.load()
            refresh()
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Friends",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(4.dp))

        if (account == null) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(
                    "Log in to add friends. Friends can be picked as players when you start a game.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onLogIn, modifier = Modifier.fillMaxWidth()) {
                    Text("Log in / Create account")
                }
            }
            return@Column
        }

        val list = friendsFor(cache, account)
        var username by remember { mutableStateOf("") }
        fun send() {
            val name = username.trim()
            if (name.isEmpty() || busy) return
            busy = true
            scope.launch {
                val result = friendsRepo.sendRequest(name)
                busy = false
                feedback = when (result) {
                    is ApiResult.Ok -> {
                        username = ""
                        val who = result.value.user.username
                        if (result.value.becameFriends) {
                            "You and $who are now friends." to false
                        } else {
                            "Request sent to $who." to false
                        }
                    }
                    is ApiResult.Failure -> result.userMessage() to true
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refresh()
                sync.requestSync()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                item {
                    SectionHeader("Add friend")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it; feedback = null },
                            label = { Text("Exact username") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Send,
                            ),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.padding(4.dp))
                        Button(onClick = { send() }, enabled = !busy && username.isNotBlank()) {
                            Text("Add")
                        }
                    }
                    feedback?.let { (text, isError) ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text,
                            fontSize = 13.sp,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (offline) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Offline — showing the last known list.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (list.incoming.isNotEmpty()) {
                    header("Requests (${list.incoming.size})")
                    items(list.incoming, key = { "in-" + it.id }) { req ->
                        PersonRow(req.username) {
                            TextButton(
                                enabled = !busy,
                                onClick = { act("You and ${req.username} are now friends.") { friendsRepo.accept(req.id) } },
                            ) { Text("Accept") }
                            TextButton(
                                enabled = !busy,
                                onClick = { act(null) { friendsRepo.declineOrCancel(req.id) } },
                            ) { Text("Decline") }
                        }
                    }
                }

                header("Friends (${list.friends.size})")
                if (list.friends.isEmpty()) {
                    item { EmptyHint("No friends yet. Add someone by their username.") }
                }
                val hasGuests = transferableGuests(players, account?.mePlayerId).isNotEmpty()
                items(list.friends, key = { "f-" + it.id }) { friend ->
                    PersonRow(friend.username) {
                        if (hasGuests) {
                            TextButton(enabled = !busy, onClick = { transferTo = friend }) { Text("Move games") }
                        }
                        TextButton(enabled = !busy, onClick = { removeTarget = friend }) { Text("Remove") }
                    }
                }

                if (list.outgoing.isNotEmpty()) {
                    header("Sent (${list.outgoing.size})")
                    items(list.outgoing, key = { "out-" + it.id }) { req ->
                        PersonRow(req.username, subtitle = "Waiting for them to accept") {
                            TextButton(
                                enabled = !busy,
                                onClick = { act(null) { friendsRepo.declineOrCancel(req.id) } },
                            ) { Text("Cancel") }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    transferTo?.let { target ->
        TransferGuestDialog(
            initialFriend = target,
            onDismiss = { transferTo = null },
            onDone = { msg ->
                transferTo = null
                feedback = msg to false
            },
        )
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove friend?") },
            text = {
                Text(
                    "Remove ${target.username} from your friends? Their local player and " +
                        "your games together stay on this phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    act("Removed ${target.username}.") { friendsRepo.remove(target.id) }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

private fun LazyListScope.header(text: String) {
    item { SectionHeader(text) }
}

/** Group heading, same look as the Settings sections. */
@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PersonRow(
    name: String,
    subtitle: String? = null,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
    }
}
