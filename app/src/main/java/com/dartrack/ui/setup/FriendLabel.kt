package com.dartrack.ui.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Small "friend" tag shown next to an account friend in the player pickers. */
@Composable
fun FriendLabel() {
    Text(
        "friend",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
