package com.dartrack.ui.setup

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.dartrack.data.GameRecord
import com.dartrack.data.GameRepository
import com.dartrack.model.CricketState
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.HalfItState
import com.dartrack.model.X01State
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun NewGameScreen(
    onCancel: () -> Unit,
    onStart: (recordId: String) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { GameRepository.get(context) }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(GameMode.X01) }
    var startScore by remember { mutableStateOf(501) }
    var doubleOut by remember { mutableStateOf(true) }
    val playerNames = remember { mutableStateListOf("", "") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("New game", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Text("Mode", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GameMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = {
                        Text(
                            when (m) {
                                GameMode.X01 -> "X01"
                                GameMode.CRICKET -> "Cricket"
                                GameMode.HALF_IT -> "Half-It"
                            }
                        )
                    },
                )
            }
        }

        if (mode == GameMode.X01) {
            Spacer(Modifier.height(16.dp))
            Text("Start score", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                X01State.SUPPORTED_STARTS.forEach { s ->
                    FilterChip(
                        selected = startScore == s,
                        onClick = { startScore = s },
                        label = { Text(s.toString()) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = doubleOut, onCheckedChange = { doubleOut = it })
                Text("  Finish on a double (double-out)")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Players (${playerNames.size})", fontWeight = FontWeight.SemiBold)
        Text(
            "Long-press the drag handle to reorder.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReorderablePlayerList(
            names = playerNames,
            onNameChange = { idx, v -> playerNames[idx] = v },
            onRemove = { idx -> if (playerNames.size > 1) playerNames.removeAt(idx) },
            onMove = { from, to ->
                if (from in playerNames.indices && to in playerNames.indices && from != to) {
                    val v = playerNames.removeAt(from)
                    playerNames.add(to, v)
                }
            },
        )
        if (playerNames.size < 4) {
            OutlinedButton(
                onClick = { playerNames.add("") },
            ) { Text("Add player") }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    val players = playerNames.mapIndexed { i, n ->
                        GamePlayer(n.trim().ifBlank { "Player ${i + 1}" })
                    }
                    val state = when (mode) {
                        GameMode.X01 -> X01State.new(players, startScore, doubleOut)
                        GameMode.CRICKET -> CricketState.new(players)
                        GameMode.HALF_IT -> HalfItState.new(players)
                    }
                    val now = System.currentTimeMillis()
                    val record = GameRecord(
                        id = UUID.randomUUID().toString(),
                        mode = mode,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        state = state,
                    )
                    scope.launch {
                        repo.upsert(record)
                        onStart(record.id)
                    }
                },
                enabled = playerNames.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Start") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Vertical list of player rows with a drag handle. Long-press the handle to
 * pick a row up; drag vertically to slot it elsewhere; release to commit.
 */
@Composable
private fun ReorderablePlayerList(
    names: List<String>,
    onNameChange: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val rowHeightDp = 72.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeightDp.toPx() }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    val hoverIndex = remember { mutableIntStateOf(-1) }

    Column(modifier = Modifier.fillMaxWidth()) {
        names.forEachIndexed { idx, name ->
            val isDragging = dragFrom == idx
            val translation = if (isDragging) dragOffsetPx else 0f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeightDp)
                    .padding(vertical = 4.dp)
                    .graphicsLayer { translationY = translation }
                    .zIndex(if (isDragging) 1f else 0f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .pointerInput(idx, names.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragFrom = idx
                                    dragOffsetPx = 0f
                                    hoverIndex.intValue = idx
                                },
                                onDragEnd = {
                                    val from = dragFrom
                                    val to = hoverIndex.intValue
                                    dragFrom = null
                                    dragOffsetPx = 0f
                                    hoverIndex.intValue = -1
                                    if (from != null && to >= 0 && to != from) onMove(from, to)
                                },
                                onDragCancel = {
                                    dragFrom = null
                                    dragOffsetPx = 0f
                                    hoverIndex.intValue = -1
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    dragOffsetPx += drag.y
                                    val from = dragFrom ?: return@detectDragGesturesAfterLongPress
                                    val deltaSlots = (dragOffsetPx / rowHeightPx).toInt()
                                    hoverIndex.intValue = (from + deltaSlots)
                                        .coerceIn(0, names.size - 1)
                                },
                            )
                        },
                ) {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.DragHandle, contentDescription = "Drag to reorder")
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { onNameChange(idx, it) },
                    placeholder = { Text("Player ${idx + 1}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (names.size > 1) {
                    TextButton(onClick = { onRemove(idx) }) { Text("Remove") }
                }
            }
        }
    }
}
