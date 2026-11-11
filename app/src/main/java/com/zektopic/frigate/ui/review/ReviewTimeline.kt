package com.zektopic.frigate.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The strip down the right of the Review page, newest at the top: hour ticks with
 * labels, a coloured mark for every item, and a handle at the time of the first item
 * in view. Tapping it scrolls the grid to that time.
 *
 * Simpler than the web UI's virtualized, zoomable timeline: the whole range is fitted
 * to the height of the screen rather than scrolled.
 */
@Composable
fun ReviewTimeline(
    events: List<EventEntity>,
    rangeStart: Long,
    rangeEnd: Long,
    currentTime: Long?,
    wide: Boolean,
    use24Hour: Boolean,
    onSeek: (Long) -> Unit
) {
    val colors = FrigateTheme.colors
    val measurer = rememberTextMeasurer()
    val span = (rangeEnd - rangeStart).coerceAtLeast(60_000L)
    val seek by rememberUpdatedState(onSeek)
    val labelStyle = FrigateType.xs.copy(fontSize = if (wide) 11.sp else 9.sp, color = colors.mutedForeground)
    val format = SimpleDateFormat(if (use24Hour) "HH:mm" else "h a", Locale.getDefault())

    Canvas(
        modifier = Modifier
            .width(if (wide) 110.dp else 65.dp)
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .pointerInput(rangeStart, rangeEnd) {
                detectTapGestures { tap ->
                    val fraction = (tap.y / size.height).coerceIn(0f, 1f)
                    seek(rangeEnd - (span * fraction).toLong())
                }
            }
            .fillMaxSize()
    ) {
        fun yOf(time: Long): Float = ((rangeEnd - time).toFloat() / span).coerceIn(0f, 1f) * size.height

        val markWidth = 10.dp.toPx()
        val markLeft = size.width - markWidth
        val tickRight = markLeft - 6.dp.toPx()

        // Hour ticks, labelled as often as there is room for
        val hourMs = 3_600_000L
        val hours = (span / hourMs).toInt().coerceAtLeast(1)
        val labelEvery = when {
            size.height / hours >= 28.dp.toPx() -> 1
            size.height / hours >= 14.dp.toPx() -> 2
            else -> 4
        }
        var tick = rangeEnd - (rangeEnd % hourMs)
        var index = 0
        while (tick >= rangeStart) {
            val y = yOf(tick)
            val major = index % labelEvery == 0
            drawLine(
                color = if (major) colors.neutral else colors.secondaryHighlight,
                start = Offset(tickRight - (if (major) 14.dp.toPx() else 8.dp.toPx()), y),
                end = Offset(tickRight, y),
                strokeWidth = 1.dp.toPx()
            )
            if (major) {
                val text = measurer.measure(format.format(Date(tick)), labelStyle)
                val top = (y - text.size.height / 2f).coerceIn(0f, (size.height - text.size.height).coerceAtLeast(0f))
                val left = (tickRight - 18.dp.toPx() - text.size.width).coerceAtLeast(0f)
                drawText(text, topLeft = Offset(left, top))
            }
            tick -= hourMs
            index++
        }

        // The summary strip: one mark per item, dimmed once reviewed
        drawRoundRect(
            color = colors.secondary,
            topLeft = Offset(markLeft, 0f),
            size = Size(markWidth, size.height),
            cornerRadius = CornerRadius(markWidth / 2)
        )
        val minHeight = 3.dp.toPx()
        for (event in events) {
            val top = yOf(event.endTime ?: event.timestamp)
            val bottom = yOf(event.timestamp)
            drawRoundRect(
                color = colors.severityColor(event.reviewSeverity, dimmed = event.hasBeenReviewed),
                topLeft = Offset(markLeft, top),
                size = Size(markWidth, (bottom - top).coerceAtLeast(minHeight)),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }

        // Handle at the time of the first item on screen
        if (currentTime != null) {
            val y = yOf(currentTime)
            drawLine(colors.selected, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
        }
    }
}
