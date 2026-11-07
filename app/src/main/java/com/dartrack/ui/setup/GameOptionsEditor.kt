package com.dartrack.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dartrack.data.SettingsRepository
import com.dartrack.model.GOTCHA_ALLOWED_TARGETS
import com.dartrack.model.GameMode
import com.dartrack.model.GameOptions
import com.dartrack.model.X01State
import com.dartrack.model.bestOfToFirstTo
import com.dartrack.model.firstToToBestOf

/**
 * [GameOptions] state for a setup screen, seeded once from the user's saved X01
 * defaults (start score + double-out). The settings flow starts at in-memory
 * defaults and is replaced once load() finishes, so we can't just read it into
 * the initial remember (we'd miss the loaded value). Instead a one-shot
 * LaunchedEffect applies the defaults exactly once after settings have loaded —
 * before the user has had a chance to change anything — and never fights
 * subsequent user edits. defaultX01StartScore is guaranteed valid by
 * Settings.sanitized(), but we filter defensively.
 */
@Composable
fun rememberSeededGameOptions(): MutableState<GameOptions> {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository.get(context) }
    LaunchedEffect(Unit) { settingsRepo.load() }
    val settings by settingsRepo.settings.collectAsState()

    val options = remember { mutableStateOf(GameOptions()) }
    var defaultsApplied by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        if (!defaultsApplied) {
            val current = options.value
            options.value = current.copy(
                x01StartScore = settings.defaultX01StartScore
                    .takeIf { it in X01State.SUPPORTED_STARTS } ?: current.x01StartScore,
                x01DoubleOut = settings.defaultDoubleOut,
            )
            defaultsApplied = true
        }
    }
    return options
}

/**
 * The per-mode options block shown under the mode picker on both the New Game
 * and New Tournament screens, so the two can't drift. Renders only the knobs
 * that apply to [mode] (nothing for modes without any) and reports every edit
 * as a new [GameOptions] through [onChange].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameOptionsEditor(
    mode: GameMode,
    options: GameOptions,
    onChange: (GameOptions) -> Unit,
) {
    Column {
        when (mode) {
            GameMode.X01 -> {
                Spacer(Modifier.height(16.dp))
                Text("Start score", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    X01State.SUPPORTED_STARTS.forEach { s ->
                        FilterChip(
                            selected = options.x01StartScore == s,
                            onClick = { onChange(options.copy(x01StartScore = s)) },
                            label = { Text(s.toString()) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = options.x01DoubleOut,
                        onCheckedChange = { onChange(options.copy(x01DoubleOut = it)) },
                    )
                    Text("  Finish on a double (double-out)")
                }
                Spacer(Modifier.height(16.dp))
                // Legs and sets side by side; both pick a "best of" and persist
                // the equivalent "first to" count the X01 model uses.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BestOfDropdown(
                        label = "Legs · best of",
                        firstTo = options.x01LegsToWin,
                        onFirstTo = { onChange(options.copy(x01LegsToWin = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    BestOfDropdown(
                        label = "Sets · best of",
                        firstTo = options.x01SetsToWin,
                        onFirstTo = { onChange(options.copy(x01SetsToWin = it)) },
                        oneLabel = "1 (no sets)",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            GameMode.CRICKET -> {
                Spacer(Modifier.height(16.dp))
                Text("Variant", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = options.cricketCutThroat,
                        onClick = {
                            onChange(options.copy(cricketCutThroat = !options.cricketCutThroat))
                        },
                        label = { Text("Cut-throat") },
                    )
                }
            }
            GameMode.GOTCHA -> {
                Spacer(Modifier.height(16.dp))
                Text("Target", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GOTCHA_ALLOWED_TARGETS.forEach { t ->
                        FilterChip(
                            selected = options.gotchaTarget == t,
                            onClick = { onChange(options.copy(gotchaTarget = t)) },
                            label = { Text(t.toString()) },
                        )
                    }
                }
            }
            GameMode.KILLER -> {
                Spacer(Modifier.height(16.dp))
                Text("Lives", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(3, 5).forEach { n ->
                        FilterChip(
                            selected = options.killerLives == n,
                            onClick = { onChange(options.copy(killerLives = n)) },
                            label = { Text(n.toString()) },
                        )
                    }
                }
            }
            else -> Unit // no per-mode options
        }
    }
}

/**
 * A read-only [OutlinedTextField] + anchored menu offering the
 * [X01State.BEST_OF_OPTIONS]. Shows and picks "best of" values but takes and
 * reports the persisted "first to" count ([firstTo] / [onFirstTo]). [oneLabel]
 * overrides how the "1" entry reads (e.g. "1 (no sets)").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BestOfDropdown(
    label: String,
    firstTo: Int,
    onFirstTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
    oneLabel: String = "1",
) {
    var expanded by remember { mutableStateOf(false) }
    val text = { n: Int -> if (n == 1) oneLabel else n.toString() }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = text(firstToToBestOf(firstTo)),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            X01State.BEST_OF_OPTIONS.forEach { n ->
                DropdownMenuItem(
                    text = { Text(text(n)) },
                    onClick = {
                        onFirstTo(bestOfToFirstTo(n))
                        expanded = false
                    },
                )
            }
        }
    }
}
