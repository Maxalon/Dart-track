package com.dartrack.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dartrack.data.TrendPoint

/**
 * Minimal line chart of a player's 3-dart average over time, drawn with the
 * standard Compose [Canvas] (no external chart libraries).
 *
 * X is the chronological index of each game (evenly spaced); Y is the 3-dart
 * average scaled to the data's min/max with a little vertical padding. The line
 * and a dot per point are plotted, plus min/max y-axis labels and the latest
 * value. Gracefully handles 0 points (a "no data yet" hint) and 1 point (a
 * single centered dot) without dividing by zero.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No data yet",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val values = points.map { it.threeDartAvg }
    val minV = values.min()
    val maxV = values.max()
    val latest = values.last()

    Box(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            drawTrend(
                values = values,
                minV = minV,
                maxV = maxV,
                lineColor = lineColor,
                dotColor = dotColor,
            )
        }
        // Axis / value labels overlaid as text (Canvas text drawing needs the
        // platform paint; Compose Text is simpler and theme-aware).
        Text(
            text = "%.1f".format(maxV),
            color = labelColor,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            text = "%.1f".format(minV),
            color = labelColor,
            modifier = Modifier.align(Alignment.BottomStart),
        )
        Text(
            text = "latest %.1f".format(latest),
            color = labelColor,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

private fun DrawScope.drawTrend(
    values: List<Double>,
    minV: Double,
    maxV: Double,
    lineColor: Color,
    dotColor: Color,
) {
    val w = size.width
    val h = size.height
    // Inset so dots and labels are not clipped at the edges.
    val padX = 12f
    val padY = 16f
    val plotW = (w - padX * 2).coerceAtLeast(1f)
    val plotH = (h - padY * 2).coerceAtLeast(1f)

    // Vertical scale: map value range to plot height. Pad the range a touch so
    // the extremes don't sit exactly on the top/bottom edges. When all values
    // are equal (or there is a single point) the range is zero -> draw flat at
    // the vertical middle to avoid divide-by-zero.
    val rawRange = maxV - minV
    val range = if (rawRange <= 0.0) 0.0 else rawRange
    val padFrac = 0.1
    val lo = minV - range * padFrac
    val span = range * (1 + padFrac * 2)

    fun yFor(v: Double): Float {
        if (span <= 0.0) return padY + plotH / 2f
        val frac = ((v - lo) / span).toFloat().coerceIn(0f, 1f)
        // Higher value -> higher on screen (smaller y).
        return padY + (1f - frac) * plotH
    }

    fun xFor(index: Int): Float {
        if (values.size == 1) return padX + plotW / 2f
        val frac = index.toFloat() / (values.size - 1).toFloat()
        return padX + frac * plotW
    }

    // Connecting line (only meaningful with >= 2 points).
    if (values.size >= 2) {
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = xFor(i)
            val y = yFor(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3f),
        )
    }

    // Point dots (also covers the single-point case).
    values.forEachIndexed { i, v ->
        drawCircle(
            color = dotColor,
            radius = 4f,
            center = Offset(xFor(i), yFor(v)),
        )
    }
}
