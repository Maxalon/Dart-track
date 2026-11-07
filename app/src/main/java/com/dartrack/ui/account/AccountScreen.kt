package com.dartrack.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dartrack.data.Account
import com.dartrack.data.AccountRepository
import com.dartrack.net.ApiResult
import com.dartrack.net.userMessage
import kotlinx.coroutines.launch

/** Client-side mirror of the server's username rule: 3–20 of [A-Za-z0-9_]. */
private val USERNAME_RE = Regex("^[A-Za-z0-9_]{3,20}$")
private const val MIN_PASSWORD = 6

/**
 * Log in / create an account. Header idiom matches SettingsScreen (title +
 * "Back"). A Login / Sign up chip toggle switches the form; server errors are
 * shown inline in plain language. When the server answers
 * `password_reset_required` (an admin reset the password) the screen switches
 * to a "choose a new password" step (new + confirm) that repeats the login
 * with the new password. On success [onDone] is called; AppRoot then asks
 * "which player is you?" if needed.
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val accountRepo = remember { AccountRepository.get(context) }
    val scope = rememberCoroutineScope()

    var signUp by remember { mutableStateOf(false) }
    var resetStep by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> ApiResult<Account>) {
        loading = true
        error = null
        scope.launch {
            val result = block()
            loading = false
            when {
                result is ApiResult.Ok -> onDone()
                result is ApiResult.HttpError && result.code == "password_reset_required" -> {
                    resetStep = true
                    newPassword = ""
                    confirm = ""
                }
                result is ApiResult.Failure -> error = result.userMessage()
            }
        }
    }

    // Client-side checks so obvious mistakes don't cost a round trip.
    val localError: String? = when {
        resetStep -> when {
            newPassword.length < MIN_PASSWORD -> "Password needs at least $MIN_PASSWORD characters."
            confirm != newPassword -> "Passwords don't match."
            else -> null
        }
        signUp -> when {
            !USERNAME_RE.matches(username.trim()) -> "Username: 3–20 letters, digits or _."
            password.length < MIN_PASSWORD -> "Password needs at least $MIN_PASSWORD characters."
            else -> null
        }
        else -> if (username.isBlank() || password.isEmpty()) "" else null
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Account",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            Text(
                "An account is optional. It lets you add friends. " +
                    "Everything else keeps working offline without one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (resetStep) {
                Text("Choose a new password", fontWeight = FontWeight.SemiBold)
                Text(
                    "The password for \"${username.trim()}\" was reset. Pick a new one to log in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                PasswordField("New password", newPassword, enabled = !loading) { newPassword = it }
                PasswordField("Confirm new password", confirm, enabled = !loading) { confirm = it }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !signUp,
                        onClick = { signUp = false; error = null },
                        label = { Text("Log in") },
                    )
                    FilterChip(
                        selected = signUp,
                        onClick = { signUp = true; error = null },
                        label = { Text("Create account") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField("Password", password, enabled = !loading) { password = it }
            }

            // Show the local hint only once the user has typed something.
            val hint = error ?: localError?.takeIf {
                it.isNotEmpty() && (password.isNotEmpty() || newPassword.isNotEmpty())
            }
            if (hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(hint, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (resetStep) {
                    TextButton(
                        onClick = { resetStep = false; error = null },
                        enabled = !loading,
                    ) { Text("Cancel") }
                }
                Button(
                    onClick = {
                        val name = username.trim()
                        when {
                            resetStep -> run { accountRepo.loginWithNewPassword(name, newPassword) }
                            signUp -> run { accountRepo.signup(name, password) }
                            else -> run { accountRepo.login(name, password) }
                        }
                    },
                    enabled = !loading && localError == null,
                    modifier = Modifier.weight(1f),
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            when {
                                resetStep -> "Set password & log in"
                                signUp -> "Create account"
                                else -> "Log in"
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun PasswordField(
    label: String,
    value: String,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
