package com.dartrack

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.dartrack.data.SettingsRepository
import com.dartrack.feedback.GameFeedback
import com.dartrack.ui.AppRoot
import com.dartrack.ui.theme.DartTrackTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge: the window background extends under the status and
        // navigation bars. We deliberately do NOT apply safeDrawingPadding() at
        // the root — each screen owns its own insets (statusBarsPadding /
        // navigationBarsPadding / safeDrawing content padding) so that lists can
        // scroll content under the bars while keeping the last item reachable,
        // and the game screens keep their single-screen no-scroll layout.
        enableEdgeToEdge()

        // Settings drive the theme + a few window-level effects. The repo is a
        // singleton; load once here so the very first frame can read persisted
        // preferences (the screens just observe the same StateFlow).
        val settingsRepo = SettingsRepository.get(this)
        lifecycleScope.launch { settingsRepo.load() }

        // Build the in-game sound/haptic engine once for the whole process; the
        // view-model plays through it. It stays inert until a turn is taken AND
        // the user has the channels enabled (synced from settings below).
        GameFeedback.init(this)

        setContent {
            val settings by settingsRepo.settings.collectAsState()

            // Keep the feedback engine's channels in sync with the user's prefs.
            LaunchedEffect(settings.soundEffects, settings.haptics) {
                GameFeedback.soundOn = settings.soundEffects
                GameFeedback.hapticsOn = settings.haptics
            }

            // Keep-screen-on: toggle the window flag to match the setting while
            // this composition is active; clear it on dispose so it never leaks.
            DisposableEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            DartTrackTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}
