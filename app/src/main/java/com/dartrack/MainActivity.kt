package com.dartrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.dartrack.ui.AppRoot
import com.dartrack.ui.theme.DartTrackTheme

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
        setContent {
            DartTrackTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}
