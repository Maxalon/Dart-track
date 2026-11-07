package com.dartrack.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dartrack.data.AccountRepository
import com.dartrack.data.FriendStatsRepository
import com.dartrack.data.FriendStatsStatus
import com.dartrack.data.FriendsRepository
import com.dartrack.data.GameRepository
import com.dartrack.data.friendsFor
import com.dartrack.data.PlayerRepository
import com.dartrack.data.SettingsRepository
import com.dartrack.data.SyncManager
import com.dartrack.data.achievementStatuses
import com.dartrack.data.achievementSummary
import com.dartrack.data.achievementsFor
import com.dartrack.data.friendStatsFor
import com.dartrack.data.statsSubjects
import com.dartrack.ui.account.AccountScreen
import com.dartrack.ui.account.AccountSection
import com.dartrack.ui.account.MePlayerDialog
import com.dartrack.ui.achievements.AchievementsScreen
import com.dartrack.ui.friends.FriendsScreen
import com.dartrack.ui.game.AroundTheClockScreen
import com.dartrack.ui.game.BaseballScreen
import com.dartrack.ui.game.BermudaScreen
import com.dartrack.ui.game.BobsTwentySevenScreen
import com.dartrack.ui.game.Catch40Screen
import com.dartrack.ui.game.CountUpScreen
import com.dartrack.ui.game.CheckoutTrainerScreen
import com.dartrack.ui.game.CricketGameScreen
import com.dartrack.ui.game.GolfScreen
import com.dartrack.ui.game.GotchaScreen
import com.dartrack.ui.game.HalfItGameScreen
import com.dartrack.ui.game.KillerScreen
import com.dartrack.ui.game.ShanghaiScreen
import com.dartrack.ui.game.X01GameScreen
import com.dartrack.ui.help.HowToPlayScreen
import com.dartrack.ui.history.GameDetailScreen
import com.dartrack.ui.history.HistoryScreen
import com.dartrack.ui.home.HomeScreen
import com.dartrack.ui.players.PlayerManagementScreen
import com.dartrack.ui.records.LeaderboardsScreen
import com.dartrack.ui.settings.SettingsScreen
import com.dartrack.ui.setup.NewGameScreen
import com.dartrack.ui.stats.PlayerStatsScreen
import com.dartrack.ui.stats.StatsScreen
import com.dartrack.ui.tournament.NewTournamentScreen
import com.dartrack.ui.tournament.TournamentDetailScreen
import com.dartrack.ui.tournament.TournamentsScreen
import com.dartrack.viewmodel.AppViewModel
import com.dartrack.viewmodel.SettingsViewModel

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repo = GameRepository.get(context)
    val nav = rememberNavController()

    val appVm: AppViewModel = viewModel(factory = AppViewModel.Factory(repo))
    val games by appVm.games.collectAsState()

    // Settings: MainActivity owns the initial load (and applies theme/keep-on);
    // here we only expose the StateFlow + an update lambda to the settings route.
    val settingsRepo = SettingsRepository.get(context)
    val settingsVm: SettingsViewModel =
        viewModel(factory = SettingsViewModel.Factory(settingsRepo))
    val settings by settingsVm.settings.collectAsState()

    // Optional server account. Load it once; when logged in, re-check the token
    // (a 401 here means an admin reset/deleted the account → logged out + notice).
    val accountRepo = remember { AccountRepository.get(context) }
    val registry = remember { PlayerRepository.get(context) }
    val account by accountRepo.account.collectAsState()
    val accountNotice by accountRepo.notice.collectAsState()
    val friendsRepo = remember { FriendsRepository.get(context) }
    val friendsCache by friendsRepo.cache.collectAsState()
    val sync = remember { SyncManager.get(context) }
    // Everything is read from disk before sync may start, so a sync can never
    // write over a store that hasn't been loaded yet.
    var storesLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repo.load()
        registry.load()
        friendsRepo.load()
        accountRepo.load()
        sync.load()
        storesLoaded = true
        // Automatic triggers: a game finishing, connectivity returning.
        sync.start(context)
    }
    // Game-history sync on app start, after a login and once "which player is
    // you?" is answered (sync needs the me player).
    LaunchedEffect(storesLoaded, account?.userId, account?.mePlayerId) {
        if (storesLoaded && account?.mePlayerId != null) sync.requestSync()
    }
    // Friends (and the Home badge count) refresh on app start and after a login;
    // so does the profile (also how a revoked token is noticed, and where the
    // "hide my stats" flag comes from).
    LaunchedEffect(account?.userId) {
        if (account != null) {
            accountRepo.refreshMe()
            friendsRepo.refresh()
        }
    }
    // "Which player is you?" right after a login that found no linked player.
    // "Later" hides it for this login; Settings → Account can still choose.
    var mePickerDismissedFor by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(accountNotice) {
        val message = accountNotice ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = message,
            actionLabel = "Log in",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        accountRepo.dismissNotice()
        if (result == SnackbarResult.ActionPerformed) nav.navigate("account")
    }

    Box(Modifier.fillMaxSize()) {
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
                    onLeaderboards = { nav.navigate("leaderboards") },
                    onTournaments = { nav.navigate("tournaments") },
                    onManagePlayers = { nav.navigate("players") },
                    onSettings = { nav.navigate("settings") },
                    onHowToPlay = { nav.navigate("how_to_play") },
                    onFriends = { nav.navigate("friends") },
                    friendRequestCount = friendsFor(friendsCache, account).incoming.size,
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
                        recordId = id,
                        onExit = { nav.popBackStack("home", inclusive = false) },
                    )
                    com.dartrack.model.GameMode.CHECKOUT_TRAINER -> CheckoutTrainerScreen(
                        recordId = id,
                        onExit = { nav.popBackStack("home", inclusive = false) },
                    )
                    com.dartrack.model.GameMode.BASEBALL -> BaseballScreen(
                        recordId = id,
                        onExit = { nav.popBackStack("home", inclusive = false) },
                    )
                    com.dartrack.model.GameMode.GOLF -> GolfScreen(
                        recordId = id,
                        onExit = { nav.popBackStack("home", inclusive = false) },
                    )
                    com.dartrack.model.GameMode.GOTCHA -> GotchaScreen(
                        recordId = id,
                        onExit = { nav.popBackStack("home", inclusive = false) },
                    )
                    com.dartrack.model.GameMode.KILLER -> KillerScreen(
                        recordId = id,
                        onExit = { nav.popBackStack("home", inclusive = false) },
                    )
                    com.dartrack.model.GameMode.BERMUDA -> BermudaScreen(
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
                PlayerStatsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenAchievements = { playerId -> nav.navigate("achievements/$playerId") },
                )
            }
            composable("leaderboards") {
                LeaderboardsScreen(onBack = { nav.popBackStack() })
            }
            composable("tournaments") {
                TournamentsScreen(
                    onBack = { nav.popBackStack() },
                    onNew = { nav.navigate("new_tournament") },
                    onOpen = { id -> nav.navigate("tournament/$id") },
                )
            }
            composable("new_tournament") {
                NewTournamentScreen(
                    onCancel = { nav.popBackStack() },
                    onCreated = { id ->
                        nav.navigate("tournament/$id") {
                            popUpTo("tournaments")
                        }
                    },
                )
            }
            composable("tournament/{id}") { backstack ->
                val id = backstack.arguments?.getString("id") ?: return@composable
                TournamentDetailScreen(
                    tournamentId = id,
                    onBack = { nav.popBackStack() },
                    onPlayMatch = { gid -> nav.navigate("game/$gid") },
                )
            }
            composable("how_to_play") {
                HowToPlayScreen(onBack = { nav.popBackStack() })
            }
            composable("achievements/{playerId}") { backstack ->
                val playerId = backstack.arguments?.getString("playerId") ?: return@composable
                // Resolve the display name from the same registry PlayerStatsScreen
                // uses; fall back to the player's stored seat name in any game (so a
                // name still shows even if the registry has not loaded), then a
                // generic label as a last resort.
                val playerRepo = remember { PlayerRepository.get(context) }
                val players by playerRepo.players.collectAsState()
                val friendStatsCache by remember { FriendStatsRepository.get(context) }.cache.collectAsState()
                // Logged in, a friend's board comes from the snapshot they shared.
                val subject = statsSubjects(players, account, friendsFor(friendsCache, account).friends)
                    .firstOrNull { it.player.id == playerId }
                val snapshot = subject?.takeIf { it.isFriend }?.player?.accountId
                    ?.let { friendStatsFor(friendStatsCache, account?.userId)[it] }
                    ?.takeIf { it.status == FriendStatsStatus.OK }?.snapshot
                val playerName = subject?.player?.name
                    ?: players.firstOrNull { it.id == playerId }?.name
                    ?: games.firstNotNullOfOrNull { rec ->
                        rec.state.players.firstOrNull { it.id == playerId }?.name
                    }
                    ?: "Player"
                val statuses = remember(games, playerId, snapshot) {
                    snapshot?.achievementStatuses() ?: achievementsFor(playerId, games)
                }
                val summary = remember(games, playerId, snapshot) {
                    snapshot?.achievementSummary() ?: achievementSummary(playerId, games)
                }
                AchievementsScreen(
                    playerName = playerName,
                    statuses = statuses,
                    summary = summary,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("players") {
                PlayerManagementScreen(onBack = { nav.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(
                    settings = settings,
                    onChange = { next -> settingsVm.update { next } },
                    onBack = { nav.popBackStack() },
                    accountSection = {
                        AccountSection(
                            onLogIn = { nav.navigate("account") },
                            onFriends = { nav.navigate("friends") },
                        )
                    },
                )
            }
            composable("account") {
                AccountScreen(
                    onBack = { nav.popBackStack() },
                    onDone = { nav.popBackStack() },
                )
            }
            composable("friends") {
                FriendsScreen(
                    onBack = { nav.popBackStack() },
                    onLogIn = { nav.navigate("account") },
                )
            }
        }

        val acc = account
        if (acc != null && acc.mePlayerId == null && mePickerDismissedFor != acc.token) {
            MePlayerDialog(account = acc, onDismiss = { mePickerDismissedFor = acc.token })
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

/** Short, snappy navigation transition duration so the app still feels instant. */
private const val NAV_ANIM_MS = 250
