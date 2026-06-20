package com.dartrack.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Dart-track brand palette.
 *
 * Brand identity is dartboard red (the [#B91C1C] / [#EF4444] family) used as the
 * primary role, with a contrasting "scored / checkout" green as the tertiary
 * accent. Secondary is a muted slate-blue that ties the reds to the dark
 * #0F172A surface family.
 *
 * Raw color values only; ColorScheme assembly lives in Theme.kt.
 */

// --- Brand: dartboard red (primary) ---
val Red50 = Color(0xFFFEF2F2)
val Red100 = Color(0xFFFEE2E2)
val Red200 = Color(0xFFFECACA)
val Red300 = Color(0xFFFCA5A5)
val Red400 = Color(0xFFF87171)
val Red500 = Color(0xFFEF4444) // dark-mode primary
val Red600 = Color(0xFFDC2626)
val Red700 = Color(0xFFB91C1C) // light-mode primary
val Red800 = Color(0xFF991B1B)
val Red900 = Color(0xFF7F1D1D)
val Red950 = Color(0xFF450A0A)

// --- Accent: "scored / checkout" green (tertiary) ---
val Green50 = Color(0xFFF0FDF4)
val Green100 = Color(0xFFDCFCE7)
val Green200 = Color(0xFFBBF7D0)
val Green300 = Color(0xFF86EFAC)
val Green400 = Color(0xFF4ADE80)
val Green500 = Color(0xFF22C55E) // dark-mode tertiary
val Green600 = Color(0xFF16A34A)
val Green700 = Color(0xFF15803D) // light-mode tertiary
val Green800 = Color(0xFF166534)
val Green900 = Color(0xFF14532D)
val Green950 = Color(0xFF052E16)

// --- Secondary: slate-blue (neutral-variant brand support) ---
val Slate50 = Color(0xFFF8FAFC)
val Slate100 = Color(0xFFF1F5F9)
val Slate200 = Color(0xFFE2E8F0)
val Slate300 = Color(0xFFCBD5E1)
val Slate400 = Color(0xFF94A3B8)
val Slate500 = Color(0xFF64748B)
val Slate600 = Color(0xFF475569)
val Slate700 = Color(0xFF334155)
val Slate800 = Color(0xFF1E293B)
val Slate900 = Color(0xFF0F172A)
val Slate950 = Color(0xFF020617)

// --- Error: orange-leaning red so it reads distinct from the brand red ---
val ErrorRed40 = Color(0xFFBA1A1A)
val ErrorRedContainerLight = Color(0xFFFFDAD6)
val OnErrorRedContainerLight = Color(0xFF410002)
val ErrorRed80 = Color(0xFFFFB4AB)
val ErrorRedContainerDark = Color(0xFF93000A)
val OnErrorRedContainerDark = Color(0xFFFFDAD6)

// --- Neutrals shared by both schemes ---
val White = Color(0xFFFFFFFF)
val Black = Color(0xFF000000)
