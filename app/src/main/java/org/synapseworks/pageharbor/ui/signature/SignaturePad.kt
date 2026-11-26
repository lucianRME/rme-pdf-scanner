package org.synapseworks.pageharbor.ui.signature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.document.signature.MAX_SIGNATURE_POINTS
import org.synapseworks.pageharbor.document.signature.MAX_SIGNATURE_POINTS_PER_STROKE
import org.synapseworks.pageharbor.document.signature.MAX_SIGNATURE_STROKES
import org.synapseworks.pageharbor.document.signature.SignaturePoint
import org.synapseworks.pageharbor.document.signature.SignatureStroke

/** Handwriting stays in caller-owned memory with explicit correction, Save and placement controls. */
@Composable
fun SignaturePad(
    strokes: List<SignatureStroke>,
    onStrokesChange: (List<SignatureStroke>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = "Draw a handwritten signature",
    aspectRatio: Float = 3f,
    strokeWidthFraction: Float = .012f,
    onRejected: () -> Unit = {},
) {
    val latestStrokes by rememberUpdatedState(strokes)
    val latestOnChange by rememberUpdatedState(onStrokesChange)
    val reject by rememberUpdatedState(onRejected)
    var activeStroke by remember { mutableStateOf<List<SignaturePoint>>(emptyList()) }
    val outline = MaterialTheme.colorScheme.outline
    val padDescription = contentDescription
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .background(Color.White)
            .border(1.dp, outline)
            .semantics {
                this.contentDescription = padDescription
                if (!enabled) disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (latestStrokes.size >= MAX_SIGNATURE_STROKES ||
                            latestStrokes.sumOf { it.points.size } >= MAX_SIGNATURE_POINTS ||
                            size.width <= 0 || size.height <= 0
                        ) { reject(); return@awaitEachGesture }
                        fun normalized(x: Float, y: Float) = SignaturePoint(
                            (x / size.width).coerceIn(0f, 1f),
                            (y / size.height).coerceIn(0f, 1f),
                        )
                        activeStroke = listOf(normalized(down.position.x, down.position.y))
                        down.consume()
                        var released = false
                        var overflow = false
                        var cancelled = false
                        while (!released) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1 || event.changes.any { it.isConsumed }) { cancelled = true; break }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                activeStroke = emptyList()
                                break
                            }
                            val point = normalized(change.position.x, change.position.y)
                            if (point != activeStroke.lastOrNull()) {
                                if (activeStroke.size >= MAX_SIGNATURE_POINTS_PER_STROKE ||
                                    latestStrokes.sumOf { it.points.size } + activeStroke.size >= MAX_SIGNATURE_POINTS) overflow = true
                                else activeStroke = activeStroke + point
                            }
                            change.consume()
                            released = !change.pressed
                            if (released && activeStroke.isNotEmpty() && !cancelled) {
                                if (overflow) reject() else latestOnChange(latestStrokes + SignatureStroke(activeStroke.toList()))
                                activeStroke = emptyList()
                            }
                        }
                        activeStroke = emptyList()
                    }
                } finally {
                    activeStroke = emptyList()
                }
            },
    ) {
        val width = (minOf(size.width,size.height) * strokeWidthFraction).coerceAtLeast(1f)
        val visible = strokes.map { it.points } + listOf(activeStroke)
        visible.filter { it.isNotEmpty() }.forEach { points ->
            if (points.size == 1) {
                drawCircle(
                    color = Color.Black,
                    radius = width / 2,
                    center = androidx.compose.ui.geometry.Offset(points[0].x * size.width, points[0].y * size.height),
                )
            } else {
                val path = Path().apply {
                    moveTo(points.first().x * size.width, points.first().y * size.height)
                    points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                }
                drawPath(path, Color.Black, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
