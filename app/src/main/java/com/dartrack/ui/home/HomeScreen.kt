package com.dartrack.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    onNewGame: () -> Unit,
    onHistory: () -> Unit,
    onStats: () -> Unit,
    onPlayerStats: () -> Unit,
    onManagePlayers: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Dart-track", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(48.dp))
        Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth()) {
            Text("New game", fontSize = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Text("History")
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
        OutlinedButton(onClick = onManagePlayers, modifier = Modifier.fillMaxWidth()) {
            Text("Manage players")
        }
    }
}
