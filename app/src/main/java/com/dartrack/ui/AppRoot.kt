package com.dartrack.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dartrack.data.GameRepository
import com.dartrack.ui.game.CricketGameScreen
import com.dartrack.ui.game.HalfItGameScreen
import com.dartrack.ui.game.X01GameScreen
import com.dartrack.ui.history.GameDetailScreen
import com.dartrack.ui.history.HistoryScreen
import com.dartrack.ui.home.HomeScreen
import com.dartrack.ui.setup.NewGameScreen
import com.dartrack.ui.stats.StatsScreen
import com.dartrack.viewmodel.AppViewModel

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repo = GameRepository.get(context)
    val nav = rememberNavController()

    LaunchedEffect(Unit) { repo.load() }

    val appVm: AppViewModel = viewModel(factory = AppViewModel.Factory(repo))
    val games by appVm.games.collectAsState()

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNewGame = { nav.navigate("new_game") },
                onHistory = { nav.navigate("history") },
                onStats = { nav.navigate("stats") },
            )
        }
        composable("new_game") {
            NewGameScreen(
                onCancel = { nav.popBackStack() },
                onStart = { id ->
                    nav.navigate("game/$id") {
                        popUpTo("home")
                    }
                },
            )
        }
        composable("game/{id}") { backstack ->
            val id = backstack.arguments?.getString("id") ?: return@composable
            val record = games.firstOrNull { it.id == id }
            if (record == null) {
                // Game not found yet (race during creation) — pop.
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            val onRematch: () -> Unit = {
                appVm.rematch(record) { newId ->
                    nav.navigate("game/$newId") {
                        popUpTo("home")
                    }
                }
            }
            val onExit: () -> Unit = { nav.popBackStack("home", inclusive = false) }
            when (record.mode) {
                com.dartrack.model.GameMode.X01 -> X01GameScreen(
                    recordId = id,
                    onExit = onExit,
                    onRematch = onRematch,
                )
                com.dartrack.model.GameMode.CRICKET -> CricketGameScreen(
                    recordId = id,
                    onExit = onExit,
                    onRematch = onRematch,
                )
                com.dartrack.model.GameMode.HALF_IT -> HalfItGameScreen(
                    recordId = id,
                    onExit = onExit,
                    onRematch = onRematch,
                )
            }
        }
        composable("history") {
            HistoryScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("history/$id") },
                onResume = { id -> nav.navigate("game/$id") },
            )
        }
        composable("history/{id}") { backstack ->
            val id = backstack.arguments?.getString("id") ?: return@composable
            GameDetailScreen(
                recordId = id,
                onBack = { nav.popBackStack() },
            )
        }
        composable("stats") {
            StatsScreen(onBack = { nav.popBackStack() })
        }
    }
}
