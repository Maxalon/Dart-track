package com.dartrack.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Dart-track shape scale.
 *
 * Slightly rounder than the M3 baseline for a soft, modern card-and-chip feel.
 * extraSmall/extraLarge are populated too so components that read those tiers
 * (chips, bottom sheets, large containers) stay consistent.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
