package com.dartrack.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Light brand scheme.
 *
 * Primary = dartboard red 700 (#B91C1C), tertiary = checkout green 700
 * (#15803D), secondary = slate-blue. Dark text on light surfaces.
 */
private val Light = lightColorScheme(
    primary = Red700,
    onPrimary = White,
    primaryContainer = Red100,
    onPrimaryContainer = Red950,

    secondary = Slate600,
    onSecondary = White,
    secondaryContainer = Slate200,
    onSecondaryContainer = Slate900,

    tertiary = Green700,
    onTertiary = White,
    tertiaryContainer = Green100,
    onTertiaryContainer = Green950,

    error = ErrorRed40,
    onError = White,
    errorContainer = ErrorRedContainerLight,
    onErrorContainer = OnErrorRedContainerLight,

    background = Slate50,
    onBackground = Slate900,

    surface = White,
    onSurface = Slate900,
    surfaceVariant = Slate200,
    onSurfaceVariant = Slate700,

    surfaceTint = Red700,
    surfaceBright = White,
    surfaceDim = Slate200,
    surfaceContainerLowest = White,
    surfaceContainerLow = Slate50,
    surfaceContainer = Slate100,
    surfaceContainerHigh = Slate200,
    surfaceContainerHighest = Slate300,

    outline = Slate400,
    outlineVariant = Slate200,

    inverseSurface = Slate900,
    inverseOnSurface = Slate100,
    inversePrimary = Red400,

    scrim = Black,
)

/**
 * Dark brand scheme.
 *
 * Primary = dartboard red 500 (#EF4444), tertiary = checkout green 500
 * (#22C55E), secondary = slate-blue. Light text on the #0F172A slate surface
 * family.
 */
private val Dark = darkColorScheme(
    primary = Red500,
    onPrimary = Red950,
    primaryContainer = Red800,
    onPrimaryContainer = Red100,

    secondary = Slate400,
    onSecondary = Slate900,
    secondaryContainer = Slate700,
    onSecondaryContainer = Slate100,

    tertiary = Green500,
    onTertiary = Green950,
    tertiaryContainer = Green800,
    onTertiaryContainer = Green100,

    error = ErrorRed80,
    onError = OnErrorRedContainerLight,
    errorContainer = ErrorRedContainerDark,
    onErrorContainer = OnErrorRedContainerDark,

    background = Slate900,
    onBackground = Slate100,

    surface = Slate900,
    onSurface = Slate100,
    surfaceVariant = Slate700,
    onSurfaceVariant = Slate300,

    surfaceTint = Red500,
    surfaceBright = Slate700,
    surfaceDim = Slate950,
    surfaceContainerLowest = Slate950,
    surfaceContainerLow = Slate900,
    surfaceContainer = Slate800,
    surfaceContainerHigh = Slate700,
    surfaceContainerHighest = Slate600,

    outline = Slate500,
    outlineVariant = Slate700,

    inverseSurface = Slate100,
    inverseOnSurface = Slate900,
    inversePrimary = Red700,

    scrim = Black,
)

@Composable
fun DartTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> Dark
        else -> Light
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
