package com.dartrack.ui.sync

import android.text.format.DateUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.dartrack.data.SyncStatus

/**
 * One quiet line about game-history sync: "Syncing…", the last error, or
 * "Synced 5 min. ago". Shown only while logged in (callers decide).
 */
@Composable
fun SyncStatusLine(status: SyncStatus, modifier: Modifier = Modifier) {
    val (text, isError) = when {
        status.running -> "Syncing…" to false
        status.lastError != null -> "Sync failed: ${status.lastError}" to true
        status.lastSyncedAt != null -> "Synced " + DateUtils.getRelativeTimeSpanString(
            status.lastSyncedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ) to false
        else -> "Not synced yet" to false
    }
    Text(
        text,
        fontSize = 12.sp,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
