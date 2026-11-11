package com.zektopic.frigate.ui.system

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.ui.components.RoundedMd
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** The values the web UI's graphs turn orange and red at. */
data class Threshold(val warning: Float, val error: Float)

// SystemGraph.tsx: bar colours by threshold, and the axis label grey
private val BarOk = Color(0xFF217930)
private val BarWarning = Color(0xFFFF9966)
private val BarError = Color(0xFFFA5252)
private val AxisLabel = Color(0xFF6B6B6B)

/** LineGraph.tsx: the three series colours of a camera graph. */
val GraphColors = listOf(Color(0xFF5C7CFA), Color(0xFFED5CFA), Color(0xFFFAD75C))

private val axisStyle = TextStyle(fontFamily = FrigateType.xs.fontFamily, fontSize = FrigateType.xs.fontSize, color = AxisLabel)
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun niceMax(values: List<Float>, floor: Float): Float {
    val peak = (values.maxOrNull() ?: 0f).coerceAtLeast(floor)
    return ceil(peak * 1.1f)
}

/**
 * The web UI's `ThresholdBarGraph`: one bar per sample, green until the value crosses
 * the warning threshold, then orange, then red. The current value is in the caption.
 */
@Composable
fun ThresholdBarGraph(
    name: String,
    unit: String,
    values: List<Float>,
    timestamps: List<Long>,
    threshold: Threshold,
    modifier: Modifier = Modifier,
    minMax: Float = 10f
) {
    val colors = FrigateTheme.colors
    val measurer = rememberTextMeasurer()
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = FrigateType.xs, color = colors.secondaryForeground)
            Text(values.lastOrNull()?.let { formatValue(it) + unit } ?: "—", style = FrigateType.xs, color = colors.primary)
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp)) {
            val yMax = niceMax(values, minMax)
            val topLabel = measurer.measure(yMax.toInt().toString(), axisStyle)
            val zeroLabel = measurer.measure("0", axisStyle)
            val axisWidth = maxOf(topLabel.size.width, zeroLabel.size.width) + 8.dp.toPx()
            val bottom = size.height - topLabel.size.height - 4.dp.toPx()
            val plotWidth = size.width - axisWidth

            drawText(topLabel, topLeft = Offset(0f, 0f))
            drawText(zeroLabel, topLeft = Offset(0f, bottom - zeroLabel.size.height))

            if (values.isNotEmpty()) {
                // Bars are sized for a full history, so a short one fills from the right
                // and the graph does not rescale as samples arrive.
                val slots = maxOf(values.size, MIN_SLOTS)
                val slot = plotWidth / slots
                val barWidth = (slot * 0.7f).coerceAtLeast(1f)
                val first = slots - values.size
                values.forEachIndexed { index, value ->
                    val height = (value / yMax).coerceIn(0f, 1f) * bottom
                    val color = when {
                        value >= threshold.error -> BarError
                        value >= threshold.warning -> BarWarning
                        else -> BarOk
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(axisWidth + (first + index) * slot + (slot - barWidth) / 2, bottom - height),
                        size = Size(barWidth, height.coerceAtLeast(1f)),
                        cornerRadius = CornerRadius(1.dp.toPx())
                    )
                }
                val start = measurer.measure(timeFormat.format(Date(timestamps.first())), axisStyle)
                val end = measurer.measure(timeFormat.format(Date(timestamps.last())), axisStyle)
                val labelY = size.height - start.size.height
                if (first == 0) drawText(start, topLeft = Offset(axisWidth, labelY))
                drawText(end, topLeft = Offset(size.width - end.size.width, labelY))
            }
        }
    }
}

data class GraphSeries(val label: String, val values: List<Float>)

/** The web UI's `CameraLineGraph`: up to three smoothed lines with a legend above. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraLineGraph(
    series: List<GraphSeries>,
    unit: String,
    timestamps: List<Long>,
    modifier: Modifier = Modifier,
    minMax: Float = 5f
) {
    val colors = FrigateTheme.colors
    val measurer = rememberTextMeasurer()
    Column(modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            series.forEachIndexed { index, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(GraphColors[index % GraphColors.size]))
                    Text(s.label, style = FrigateType.xs, color = colors.secondaryForeground)
                    Text(s.values.lastOrNull()?.let { formatValue(it) + unit } ?: "—", style = FrigateType.xs, color = colors.primary)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp)) {
            val yMax = niceMax(series.flatMap { it.values }, minMax)
            val topLabel = measurer.measure(yMax.toInt().toString(), axisStyle)
            val zeroLabel = measurer.measure("0", axisStyle)
            val axisWidth = maxOf(topLabel.size.width, zeroLabel.size.width) + 8.dp.toPx()
            val bottom = size.height - topLabel.size.height - 4.dp.toPx()
            val top = 2.dp.toPx()
            val plotWidth = size.width - axisWidth

            drawText(topLabel, topLeft = Offset(0f, 0f))
            drawText(zeroLabel, topLeft = Offset(0f, bottom - zeroLabel.size.height))

            series.forEachIndexed { index, s ->
                if (s.values.size < 2) return@forEachIndexed
                val slots = maxOf(s.values.size, MIN_SLOTS) - 1
                val first = slots - (s.values.size - 1)
                fun point(i: Int) = Offset(
                    axisWidth + (first + i) * plotWidth / slots,
                    bottom - (s.values[i] / yMax).coerceIn(0f, 1f) * (bottom - top)
                )
                val path = Path().apply {
                    val start = point(0)
                    moveTo(start.x, start.y)
                    for (i in 1 until s.values.size) {
                        // Midpoint control points: a smooth curve that cannot overshoot
                        val previous = point(i - 1)
                        val current = point(i)
                        val midX = (previous.x + current.x) / 2
                        cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
                    }
                }
                drawPath(
                    path,
                    GraphColors[index % GraphColors.size],
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            if (timestamps.isNotEmpty()) {
                val end = measurer.measure(timeFormat.format(Date(timestamps.last())), axisStyle)
                drawText(end, topLeft = Offset(size.width - end.size.width, size.height - end.size.height))
            }
        }
    }
}

/** The web UI's `StorageGraph`: "used / total" over a filled bar. */
@Composable
fun StorageBar(usedLabel: String, totalLabel: String, fraction: Float, modifier: Modifier = Modifier) {
    val colors = FrigateTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(usedLabel, style = FrigateType.xs, color = colors.primary)
            Text("/", style = FrigateType.xs, color = colors.primary)
            Text(totalLabel, style = FrigateType.xs, color = colors.mutedForeground)
        }
        Box(Modifier.fillMaxWidth().height(20.dp).clip(RoundedMd).background(colors.secondary)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(colors.selected))
        }
    }
}

/** A bar split into coloured segments, one per camera, in proportion to its share. */
@Composable
fun SegmentedBar(fractions: List<Pair<Color, Float>>, modifier: Modifier = Modifier) {
    val colors = FrigateTheme.colors
    Row(modifier.fillMaxWidth().height(20.dp).clip(RoundedMd).background(colors.secondary)) {
        for ((color, fraction) in fractions) {
            if (fraction > 0f) Box(Modifier.fillMaxHeight().weight(fraction).background(color))
        }
        val rest = 1f - fractions.sumOf { it.second.toDouble() }.toFloat()
        if (rest > 0.0001f) Box(Modifier.fillMaxHeight().weight(rest))
    }
}

private fun formatValue(value: Float): String =
    if (value >= 100f || value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

/** Bars and points are laid out for at least this many samples (one minute of history). */
private const val MIN_SLOTS = 30
