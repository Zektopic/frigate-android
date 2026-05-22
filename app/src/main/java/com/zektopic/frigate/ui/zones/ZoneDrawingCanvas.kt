package com.zektopic.frigate.ui.zones

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zektopic.frigate.ui.theme.CyberCyan
import com.zektopic.frigate.ui.theme.DarkVoid
import com.zektopic.frigate.ui.theme.ElectricEmerald
import com.zektopic.frigate.ui.theme.HotPink

@Composable
fun ZoneDrawingCanvas(
    onZoneSaved: (List<PointF>) -> Unit,
    onClose: () -> Unit
) {
    val points = remember { mutableStateListOf<Offset>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkVoid.copy(alpha = 0.95f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Draw Header Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DEFINE ACTIVE ZONE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Text(
                    text = "Tap screen to add polygon vertices. 3+ points required.",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = { points.clear() },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = HotPink.copy(alpha = 0.15f))
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear", tint = HotPink)
                }

                IconButton(
                    onClick = {
                        if (points.size >= 3) {
                            val convertedPoints = points.map { PointF(it.x, it.y) }
                            onZoneSaved(convertedPoints)
                        }
                    },
                    enabled = points.size >= 3,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (points.size >= 3) ElectricEmerald.copy(alpha = 0.15f) else Color.DarkGray
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Save",
                        tint = if (points.size >= 3) ElectricEmerald else Color.Gray
                    )
                }
            }
        }

        // Draw Touch Interactive Canvas Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        points.add(offset)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (points.isNotEmpty()) {
                    // Draw Vertices
                    for (point in points) {
                        drawCircle(
                            color = CyberCyan,
                            radius = 8.dp.toPx(),
                            center = point
                        )
                    }

                    // Draw connecting boundary paths
                    val path = Path().apply {
                        moveTo(points[0].x, points[0].y)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x, points[i].y)
                        }
                        if (points.size >= 3) {
                            close()
                        }
                    }

                    drawPath(
                        path = path,
                        color = CyberCyan,
                        style = Stroke(width = 3.dp.toPx())
                    )

                    // Fill polygon slightly to show coverage
                    if (points.size >= 3) {
                        drawPath(
                            path = path,
                            color = CyberCyan.copy(alpha = 0.1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Geometric helper class containing the classic Ray-Casting algorithm.
 */
object GeometryEngine {

    /**
     * Determines if a point is inside a polygon using standard Ray-Casting.
     */
    fun isPointInPolygon(point: PointF, polygon: List<PointF>): Boolean {
        if (polygon.size < 3) return false
        var isInside = false
        var i = 0
        var j = polygon.size - 1
        
        while (i < polygon.size) {
            val xi = polygon[i].x
            val yi = polygon[i].y
            val xj = polygon[j].x
            val yj = polygon[j].y

            val intersect = ((yi > point.y) != (yj > point.y)) &&
                    (point.x < (xj - xi) * (point.y - yi) / (yj - yi) + xi)
            
            if (intersect) {
                isInside = !isInside
            }
            j = i++
        }
        return isInside
    }
}
