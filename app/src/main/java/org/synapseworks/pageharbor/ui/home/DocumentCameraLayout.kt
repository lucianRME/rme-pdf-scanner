package org.synapseworks.pageharbor.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class ScanGuide { DOCUMENT, CARD, QR }

/** Preview owns the window; only the controls reserve safe space for system bars. */
@Composable
internal fun DocumentCameraLayout(
    modifier: Modifier = Modifier,
    viewfinder: @Composable (Modifier) -> Unit,
    controls: @Composable (Modifier) -> Unit,
    modeSelector: (@Composable () -> Unit)? = null,
    guide: ScanGuide = ScanGuide.DOCUMENT,
    cameraActive: Boolean = false,
    capturing: Boolean = false,
    shutter: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val maxDockHeight = (maxHeight * .30f).coerceAtLeast(64.dp)
        val rail = (maxWidth * .30f).coerceIn(144.dp, 250.dp)
        viewfinder(Modifier.matchParentSize())
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            if (cameraActive) {
                val guideArea = if (wide) Modifier.absolutePadding(right = rail)
                    else Modifier.padding(bottom = 112.dp)
                ScanFeedback(guide, capturing, Modifier.fillMaxSize().then(guideArea))
            }
            val dock = if (wide) Modifier.align(AbsoluteAlignment.CenterRight).width(rail).fillMaxHeight()
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            Surface(modifier = dock, color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
                if (wide) {
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        modeSelector?.invoke()
                        controls(Modifier.weight(1f).fillMaxWidth())
                        Box(Modifier.fillMaxWidth(), contentAlignment = AbsoluteAlignment.BottomRight) { shutter() }
                    }
                } else {
                    Column {
                        modeSelector?.invoke()
                        Box(Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 64.dp, max = maxDockHeight)) {
                            Box(Modifier.fillMaxWidth().absolutePadding(right = 76.dp).align(Alignment.CenterStart)) {
                                controls(Modifier.fillMaxWidth())
                            }
                            Box(Modifier.align(AbsoluteAlignment.BottomRight)) { shutter() }
                        }
                    }
                }
            }
        }
    }
}

/** A framing aid only. It never participates in hit testing or suggests a successful decode. */
@Composable
private fun ScanFeedback(guide: ScanGuide, capturing: Boolean, modifier: Modifier) {
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(guide, capturing) {
        do {
            if ((coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) == 0f) break
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(720, easing = LinearOutSlowInEasing))
            if (guide == ScanGuide.QR) delay(1600)
        } while (guide == ScanGuide.QR)
    }
    val accent = Color(0xFF74F2DC)
    Canvas(modifier) {
        val ratio = when (guide) { ScanGuide.CARD -> 1.586f; ScanGuide.QR -> 1f; ScanGuide.DOCUMENT -> .78f }
        val w = minOf(size.width * .82f, size.height * .78f * ratio)
        val h = w / ratio
        val x = (size.width - w) / 2
        val y = (size.height - h) / 2
        val tick = minOf(20.dp.toPx(), w / 8, h / 8)
        for (i in 0..3) {
            val dx = if (i % 2 == 0) 1 else -1
            val dy = if (i < 2) 1 else -1
            val corner = Offset(if (dx == 1) x else x + w, if (dy == 1) y else y + h)
            val horizontal = corner + Offset(dx * tick, 0f)
            val vertical = corner + Offset(0f, dy * tick)
            val shadow = Color.Black.copy(alpha = .55f)
            drawLine(shadow, corner, horizontal, 4.dp.toPx())
            drawLine(shadow, corner, vertical, 4.dp.toPx())
            drawLine(accent, corner, horizontal, 2.dp.toPx())
            drawLine(accent, corner, vertical, 2.dp.toPx())
        }
        val position = sweep.value
        if (position > 0f && position < 1f) {
            val line = y + h * position
            val trail = minOf(26.dp.toPx(), line - y)
            val fade = minOf(1f, (1f - position) * 6)
            if (trail > 0f) drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = .23f * fade)), line - trail, line),
                Offset(x, line - trail), Size(w, trail),
            )
            drawLine(accent.copy(alpha = .88f * fade), Offset(x, line), Offset(x + w, line), 2.dp.toPx())
        }
    }
}

@Composable
internal fun CaptureShutter(label: String, tag: String, enabled: Boolean, icon: ImageVector = Icons.Default.CameraAlt, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(64.dp).border(2.dp, if (enabled) colors.primary else colors.outline, CircleShape)
            .padding(4.dp).testTag(tag),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
    }
}
