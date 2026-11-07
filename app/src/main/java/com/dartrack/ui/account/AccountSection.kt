package com.dartrack.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.dartrack.data.Account
import com.dartrack.data.AccountRepository
import com.dartrack.data.PlayerRepository
import com.dartrack.data.SyncManager
import com.dartrack.data.eligibleMePlayers
import com.dartrack.data.normalizePlayerName
import com.dartrack.net.ApiResult
import com.dartrack.net.userMessage
import com.dartrack.ui.sync.SyncStatusLine
import kotlinx.coroutines.launch

/**
 * The "Account" block at the top of Settings. Logged out: a short pitch and a
 * "Log in / Create account" button ([onLogIn]). Logged in: the username, the
 * "me" player (changeable via [MePlayerDialog]), "Change password" and
 * "Log out" (with a confirm; logging out keeps all local data), plus the
 * Friends screen ([onFriends]), the game-history sync status ("Sync now") and
 * the "Hide my stats from friends" switch.
 */
@Composable
fun AccountSection(onLogIn: () -> Unit, onFriends: () -> Unit) {
    val context = LocalContext.current
    val accountRepo = remember { AccountRepository.get(context) }
    val playerRepo = remember { PlayerRepository.get(context) }
    val account by accountRepo.account.collectAsState()
    val players by playerRepo.players.collectAsState()
    val sync = remember { SyncManager.get(context) }
    val syncStatus by sync.status.collectAsState()
    val scope = rememberCoroutineScope()

    var statsError by remember { mutableStateOf<String?>(null) }
    var savingStats by remember { mutableStateOf(false) }
    var showMePicker by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }

    val acc = account
    if (acc == null) {
        Text(
            "Optional — log in to add friends and sync your games and stats with them. " +
                "The app works fully offline without an account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onLogIn, modifier = Modifier.fillMaxWidth()) {
            Text("Log in / Create account")
        }
        return
    }

    val meName = players.firstOrNull { it.id == acc.mePlayerId }?.name
    Text("Logged in as ${acc.username}", fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "You play as: ${meName ?: "not chosen"}",
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { showMePicker = true }) { Text(if (meName == null) "Choose" else "Change") }
    }
    Button(onClick = onFriends, modifier = Modifier.fillMaxWidth()) { Text("Friends") }
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SyncStatusLine(syncStatus, modifier = Modifier.weight(1f))
        TextButton(enabled = !syncStatus.running, onClick = { sync.requestSync() }) { Text("Sync now") }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Hide my stats from friends")
            Text(
                statsError ?: "Friends still see the games you played together.",
                fontSize = 12.sp,
                color = if (statsError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = acc.statsHidden,
            enabled = !savingStats,
            onCheckedChange = { hidden ->
                savingStats = true
                statsError = null
                scope.launch {
                    val result = accountRepo.setStatsHidden(hidden)
                    savingStats = false
                    if (result is ApiResult.Failure) statsError = result.userMessage()
                }
            },
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showChangePassword = true }) { Text("Change password") }
        OutlinedButton(onClick = { showLogout = true }) { Text("Log out") }
    }

    if (showMePicker) {
        MePlayerDialog(account = acc, onDismiss = { showMePicker = false })
    }
    if (showChangePassword) {
        ChangePasswordDialog(onDismiss = { showChangePassword = false })
    }
    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("Log out?") },
            text = {
                Text(
                    "You'll be logged out on this device. All your games and players stay on the phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogout = false
                    scope.launch { accountRepo.logout() }
                }) { Text("Log out") }
            },
            dismissButton = {
                TextButton(onClick = { showLogout = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * "Which player is you?" — lists local players that aren't linked to another
 * account, plus "Create new player" (prefilled with the username). The pick
 * becomes the account's "me" player; its local name is kept as is.
 */
@Composable
fun MePlayerDialog(account: Account, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val accountRepo = remember { AccountRepository.get(context) }
    val playerRepo = remember { PlayerRepository.get(context) }
    val players by playerRepo.players.collectAsState()
    val scope = rememberCoroutineScope()

    val eligible = eligibleMePlayers(players, account.userId)
    // null = "create new player"; preselect the current me player if any.
    var selectedId by remember { mutableStateOf(account.mePlayerId?.takeIf { id -> eligible.any { it.id == id } }) }
    var newName by remember { mutableStateOf(account.username) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which player is you?") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Pick the local player that represents ${account.username}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                eligible.forEach { p ->
                    ChoiceRow(
                        selected = selectedId == p.id,
                        label = p.name,
                        onClick = { selectedId = p.id; error = null },
                    )
                }
                if (eligible.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ChoiceRow(
                    selected = selectedId == null,
                    label = "Create new player",
                    onClick = { selectedId = null; error = null },
                )
                if (selectedId == null) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it; error = null },
                        label = { Text("Player name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedId != null || newName.isNotBlank(),
                onClick = {
                    val pick = selectedId
                    scope.launch {
                        if (pick != null) {
                            accountRepo.setMePlayer(pick)
                            onDismiss()
                            return@launch
                        }
                        // addPlayer is get-or-create by name; refuse to hijack a
                        // player that belongs to a different account.
                        val clash = players.firstOrNull {
                            normalizePlayerName(it.name) == normalizePlayerName(newName) &&
                                it.accountId != null && it.accountId != account.userId
                        }
                        if (clash != null) {
                            error = "A player named \"${clash.name}\" already exists."
                            return@launch
                        }
                        val created = playerRepo.addPlayer(newName)
                        accountRepo.setMePlayer(created.id)
                        onDismiss()
                    }
                },
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        },
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

/** Current + new + confirm; server errors (e.g. wrong current password) inline. */
@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val accountRepo = remember { AccountRepository.get(context) }
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    val localError = when {
        next.isNotEmpty() && next.length < 6 -> "New password needs at least 6 characters."
        confirm.isNotEmpty() && confirm != next -> "Passwords don't match."
        else -> null
    }
    val canSubmit = !loading && current.isNotEmpty() && next.length >= 6 && confirm == next

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            if (done) {
                Text("Your password was changed.")
            } else {
                Column {
                    PasswordField("Current password", current, enabled = !loading) { current = it; error = null }
                    PasswordField("New password", next, enabled = !loading) { next = it; error = null }
                    PasswordField("Confirm new password", confirm, enabled = !loading) { confirm = it; error = null }
                    (error ?: localError)?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("OK") }
            } else {
                TextButton(
                    enabled = canSubmit,
                    onClick = {
                        loading = true
                        scope.launch {
                            val result = accountRepo.changePassword(current, next)
                            loading = false
                            when (result) {
                                is ApiResult.Ok -> done = true
                                is ApiResult.Failure ->
                                    if (result is ApiResult.HttpError && result.status == 401) {
                                        onDismiss() // logged out; AppRoot shows the notice
                                    } else {
                                        error = result.userMessage()
                                    }
                            }
                        }
                    },
                ) { Text(if (loading) "Saving…" else "Change") }
            }
        },
        dismissButton = {
            if (!done) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
