package com.dartrack.ui.home

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    onNewGame: () -> Unit,
    onHistory: () -> Unit,
    onStats: () -> Unit,
    onPlayerStats: () -> Unit,
    onLeaderboards: () -> Unit,
    onTournaments: () -> Unit,
    onManagePlayers: () -> Unit,
    onSettings: () -> Unit,
    onHowToPlay: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        // Settings gear pinned to the top-end corner, above the centered menu.
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Dart-track", fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(48.dp))

            // Expressive button group: the two primary actions sit in a connected
            // group with springy press animations from the expressive motion scheme.
            // "New game" takes more width to keep it the clear primary action.
            val newGameInteraction = remember { MutableInteractionSource() }
            val historyInteraction = remember { MutableInteractionSource() }
            ButtonGroup(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onNewGame,
                    modifier = Modifier
                        .weight(2f)
                        .animateWidth(newGameInteraction),
                    interactionSource = newGameInteraction,
                ) {
                    Text("New game", fontSize = 18.sp)
                }
                OutlinedButton(
                    onClick = onHistory,
                    modifier = Modifier
                        .weight(1f)
                        .animateWidth(historyInteraction),
                    interactionSource = historyInteraction,
                ) {
                    Text("History")
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onStats, modifier = Modifier.fillMaxWidth()) {
                Text("Statistics")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onPlayerStats, modifier = Modifier.fillMaxWidth()) {
                Text("Player stats")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onLeaderboards, modifier = Modifier.fillMaxWidth()) {
                Text("Leaderboards")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onTournaments, modifier = Modifier.fillMaxWidth()) {
                Text("Tournaments")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onManagePlayers, modifier = Modifier.fillMaxWidth()) {
                Text("Manage players")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onHowToPlay, modifier = Modifier.fillMaxWidth()) {
                Text("How to play")
            }
        }
    }
}
