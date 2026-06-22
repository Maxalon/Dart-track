package com.dartrack.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.dartrack.ui.game.AroundTheClockScreen
import com.dartrack.ui.game.BobsTwentySevenScreen
import com.dartrack.ui.game.Catch40Screen
import com.dartrack.ui.game.CountUpScreen
import com.dartrack.ui.game.CheckoutTrainerScreen
import com.dartrack.ui.game.CricketGameScreen
import com.dartrack.ui.game.HalfItGameScreen
import com.dartrack.ui.game.ShanghaiScreen
import com.dartrack.ui.game.X01GameScreen
import com.dartrack.ui.history.GameDetailScreen
import com.dartrack.ui.history.HistoryScreen
import com.dartrack.ui.home.HomeScreen
import com.dartrack.ui.players.PlayerManagementScreen
import com.dartrack.ui.setup.NewGameScreen
import com.dartrack.ui.stats.PlayerStatsScreen
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

    NavHost(
        navController = nav,
        startDestination = "home",
        // Forward navigation: new screen slides in from the right while fading in.
        enterTransition = {
            slideIntoContainer(SlideDirection.Start, animationSpec = tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) +
                fadeIn(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing))
        },
        // Forward navigation: current screen slides out to the left while fading out.
        exitTransition = {
            slideOutOfContainer(SlideDirection.Start, animationSpec = tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) +
                fadeOut(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing))
        },
        // Back (pop): mirror — previous screen slides in from the left.
        popEnterTransition = {
            slideIntoContainer(SlideDirection.End, animationSpec = tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) +
                fadeIn(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing))
        },
        // Back (pop): current screen slides out to the right.
        popExitTransition = {
            slideOutOfContainer(SlideDirection.End, animationSpec = tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) +
                fadeOut(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing))
        },
    ) {
        composable("home") {
            HomeScreen(
                onNewGame = { nav.navigate("new_game") },
                onHistory = { nav.navigate("history") },
                onStats = { nav.navigate("stats") },
                onPlayerStats = { nav.navigate("player_stats") },
                onManagePlayers = { nav.navigate("players") },
            )
        }
        composable(
            "new_game",
            // Modal-like setup screen: slides up from the bottom and back down on dismiss.
            enterTransition = {
                slideIntoContainer(SlideDirection.Up, animationSpec = tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) +
                    fadeIn(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing))
            },
            popExitTransition = {
                slideOutOfContainer(SlideDirection.Down, animationSpec = tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) +
                    fadeOut(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing))
            },
        ) {
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
            when (record.mode) {
                com.dartrack.model.GameMode.X01 -> X01GameScreen(
                    recordId = id,
                    onExit = { nav.popBackStack("home", inclusive = false) },
                )
                com.dartrack.model.GameMode.CRICKET -> CricketGameScreen(
                    recordId = id,
                    onExit = { nav.popBackStack("home", inclusive = false) },
                )
                com.dartrack.model.GameMode.HALF_IT -> HalfItGameScreen(
                    recordId = id,
                    onExit = { nav.popBackStack("home", inclusive = false) },
                )
                com.dartrack.model.GameMode.AROUND_CLOCK -> AroundTheClockScreen(
                    recordId = id,
                    onExit = { nav.popBackStack("home", inclusive = false) },
                )
                com.dartrack.model.GameMode.BOBS_27 -> BobsTwentySevenScreen(
                    recordId = id,
                    onExit = { nav.popBackStack("home", inclusive = false) },
                )
                com.dartrack.model.GameMode.SHANGHAI -> ShanghaiScreen(
                    recordId = id,
                    onExit = { nav.popBackStack("home", inclusive = false) },
                )
                com.dartrack.model.GameMode.CATCH_40 -> Catch40Screen(
                    recordId = id,
                    onExit = { nav.popBackStack("home", inclusive = false) },
                )
                com.dartrack.model.GameMode.COUNT_UP -> CountUpScreen(
                com.dartrack.model.GameMode.CHECKOUT_TRAINER -> CheckoutTrainerScreen(
                    recordId = id,
                    onExit = { nav.popBackStack("home", inclusive = false) },
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
        composable("player_stats") {
            PlayerStatsScreen(onBack = { nav.popBackStack() })
        }
        composable("players") {
            PlayerManagementScreen(onBack = { nav.popBackStack() })
        }
    }
}

/** Short, snappy navigation transition duration so the app still feels instant. */
private const val NAV_ANIM_MS = 250
