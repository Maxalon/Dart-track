package com.dartrack.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dartrack.data.Settings
import com.dartrack.data.ThemeMode
import com.dartrack.data.X01_START_SCORES

/**
 * Settings screen. Stateless: it renders the supplied [settings] and reports
 * every change through [onChange] with the next [Settings] (the caller persists
 * via SettingsRepository). Mirrors the sibling screens' header idiom (a header
 * Row + a "Back" TextButton under statusBarsPadding) and their selector/toggle
 * idioms (FilterChip rows for choices, Switch rows for booleans). Content
 * scrolls and clears the navigation bar via safe padding.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: (Settings) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Settings",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            // ---- Appearance ---------------------------------------------------
            SectionHeader("Appearance")
            Text("Theme", fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { onChange(settings.copy(themeMode = mode)) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                },
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                label = "Material You (Android 12+)",
                checked = settings.dynamicColor,
                onCheckedChange = { onChange(settings.copy(dynamicColor = it)) },
            )

            // ---- Gameplay -----------------------------------------------------
            SectionHeader("Gameplay")
            SettingSwitch(
                label = "Keep screen on",
                checked = settings.keepScreenOn,
                onCheckedChange = { onChange(settings.copy(keepScreenOn = it)) },
            )
            SettingSwitch(
                label = "Voice caller on by default",
                checked = settings.voiceCallerDefault,
                onCheckedChange = { onChange(settings.copy(voiceCallerDefault = it)) },
            )
            SettingSwitch(
                label = "Sound effects",
                checked = settings.soundEffects,
                onCheckedChange = { onChange(settings.copy(soundEffects = it)) },
            )
            SettingSwitch(
                label = "Haptics",
                checked = settings.haptics,
                onCheckedChange = { onChange(settings.copy(haptics = it)) },
            )
            SettingSwitch(
                label = "Confirm before delete",
                checked = settings.confirmBeforeDelete,
                onCheckedChange = { onChange(settings.copy(confirmBeforeDelete = it)) },
            )

            // ---- X01 defaults -------------------------------------------------
            SectionHeader("X01 defaults")
            Text("Default start score", fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                X01_START_SCORES.forEach { score ->
                    FilterChip(
                        selected = settings.defaultX01StartScore == score,
                        onClick = { onChange(settings.copy(defaultX01StartScore = score)) },
                        label = { Text(score.toString()) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                label = "Finish on a double (double-out)",
                checked = settings.defaultDoubleOut,
                onCheckedChange = { onChange(settings.copy(defaultDoubleOut = it)) },
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Group heading with consistent spacing above/below. */
@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
}

/** A labelled boolean row: label fills the width, Switch on the trailing edge. */
@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
