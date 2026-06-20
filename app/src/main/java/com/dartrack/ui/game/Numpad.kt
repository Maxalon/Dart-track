package com.dartrack.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Numeric keypad for entering a 3-dart turn total. Shows the in-progress
 * value, allows backspace correction, has Undo (last confirmed turn) and
 * Confirm. [maxValue] caps the valid turn entry (180 for X01, otherwise can
 * be set lower for Half-It rounds).
 *
 * @param entry current pending entry as a string (caller-owned for state)
 * @param onEntryChange called when digit/backspace/clear is tapped
 * @param onConfirm called with the parsed integer value (already validated <= maxValue)
 * @param onUndo called when undo is tapped (may be null to hide)
 * @param maxValue inclusive upper bound on the entered value
 * @param confirmEnabled whether the confirm button should be enabled
 */
@Composable
fun ScoreNumpad(
    entry: String,
    onEntryChange: (String) -> Unit,
    onConfirm: (Int) -> Unit,
    onUndo: (() -> Unit)?,
    maxValue: Int,
    confirmEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val parsed = entry.toIntOrNull()
    val withinRange = parsed != null && parsed in 0..maxValue
    val displayed = if (entry.isBlank()) "0" else entry
    val invalid = parsed == null || parsed > maxValue

    // Large, glanceable targets sized for arm's-length use on an 8.dp rhythm.
    val keyHeight = 68.dp
    val actionHeight = 64.dp
    val rowSpacing = 8.dp

    Column(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        // Pending value display: kept prominent and centered.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = displayed,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = if (invalid) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onBackground,
            )
        }
        if (invalid) {
            Text(
                "Max $maxValue",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = rowSpacing / 2),
                horizontalArrangement = Arrangement.spacedBy(rowSpacing),
            ) {
                row.forEach { d ->
                    DigitButton(
                        label = d,
                        height = keyHeight,
                        modifier = Modifier.weight(1f),
                    ) {
                        onEntryChange(appendDigit(entry, d, maxValue))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = rowSpacing / 2),
            horizontalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            // Clear: tonal, visually distinct from digits and from confirm.
            FilledTonalButton(
                onClick = {
                    onEntryChange("")
                },
                modifier = Modifier.weight(1f).height(keyHeight),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("C", style = MaterialTheme.typography.titleLarge)
            }
            DigitButton(
                label = "0",
                height = keyHeight,
                modifier = Modifier.weight(1f),
            ) {
                onEntryChange(appendDigit(entry, "0", maxValue))
            }
            // Backspace: tonal, matches clear styling.
            FilledTonalButton(
                onClick = {
                    if (entry.isNotEmpty()) {
                        onEntryChange(entry.dropLast(1))
                    }
                },
                modifier = Modifier.weight(1f).height(keyHeight),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Default.Backspace, contentDescription = "Backspace")
            }
        }
        Spacer(Modifier.height(rowSpacing))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            if (onUndo != null) {
                // Undo: outlined, secondary affordance.
                OutlinedButton(
                    onClick = {
                        onUndo()
                    },
                    modifier = Modifier.weight(1f).height(actionHeight),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Default.Undo, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Undo", style = MaterialTheme.typography.titleMedium)
                }
            }
            // Confirm: primary, filled, dominant target.
            Button(
                onClick = {
                    val v = entry.toIntOrNull() ?: return@Button
                    if (v in 0..maxValue) {
                        onConfirm(v)
                    }
                },
                enabled = confirmEnabled && withinRange,
                modifier = Modifier.weight(2f).height(actionHeight),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Confirm",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun appendDigit(current: String, digit: String, maxValue: Int): String {
    val candidate = (current + digit).trimStart('0').ifEmpty { "0" }
    val parsed = candidate.toIntOrNull() ?: return current
    if (parsed > maxValue) return current
    if (candidate.length > 4) return current
    return candidate
}

@Composable
private fun DigitButton(
    label: String,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
