package com.dartrack.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tap-to-count keypad for Half-It number rounds. The user taps a number 0-9
 * for total marks scored on the current target this turn (max 9 = 3 darts ×
 * triple). The score is calculated automatically from `marks × targetValue`.
 * No confirm step — taps apply immediately. An Undo button reverts the last
 * confirmed turn.
 */
@Composable
fun HitCountPad(
    targetValue: Int,
    onApply: (marks: Int) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        Text(
            "Tap the number of marks scored. Score = marks × $targetValue.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp),
        )
        val rows = listOf(
            listOf(1, 2, 3),
            listOf(4, 5, 6),
            listOf(7, 8, 9),
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { n ->
                    HitButton(n, targetValue, modifier = Modifier.weight(1f)) { onApply(n) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HitButton(0, targetValue, modifier = Modifier.weight(2f)) { onApply(0) }
            OutlinedButton(
                onClick = onUndo,
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Icon(Icons.Default.Undo, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text("Undo")
            }
        }
    }
}

@Composable
private fun HitButton(
    marks: Int,
    targetValue: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (marks == 0) MaterialTheme.colorScheme.errorContainer
                             else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text(marks.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (marks == 0) "halve" else "${marks * targetValue}",
                fontSize = 10.sp,
            )
        }
    }
}
