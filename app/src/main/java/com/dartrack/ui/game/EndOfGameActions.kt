package com.dartrack.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * End-of-game card with winner banner and Undo / Back / Rematch actions.
 * Reused across X01, Cricket, and Half-It.
 */
@Composable
fun EndOfGameActions(
    winnerLabel: String,
    onExit: () -> Unit,
    onRematch: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Winner: $winnerLabel",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onUndo,
                    modifier = Modifier.weight(1f),
                ) { Text("Undo") }
                TextButton(
                    onClick = onExit,
                    modifier = Modifier.weight(1f),
                ) { Text("Back to home") }
                Button(
                    onClick = onRematch,
                    modifier = Modifier.weight(1f),
                ) { Text("Rematch") }
            }
        }
    }
}
