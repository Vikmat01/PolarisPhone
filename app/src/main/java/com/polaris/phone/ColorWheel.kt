package com.polaris.phone

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private fun hsv(h: Float, s: Float, v: Float): Color {
    val hsv = floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun toHsv(c: Color): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }

val Presets = listOf(
    Color(0xFF7C5CFF), Color(0xFF2F7BFF), Color(0xFF00B8D9), Color(0xFF22C55E),
    Color(0xFFFFB020), Color(0xFFFF6A3D), Color(0xFFFF3D7F), Color(0xFFB04DFF)
)

/**
 * Colour wheel: angle = hue, distance from centre = saturation, slider below = brightness.
 * The thumb follows the finger, the preview bubble cross-fades.
 */
@Composable
fun ColorWheel(initial: Color, onChange: (Color) -> Unit) {
    val c = LocalColors.current
    val start = remember { toHsv(initial) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2].coerceAtLeast(0.25f)) }
    val cb by rememberUpdatedState(onChange)
    fun emit() = cb(hsv(hue, sat, value))

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            fun pick(o: Offset, w: Float) {
                val r = w / 2
                val dx = o.x - r
                val dy = o.y - r
                hue = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()) + 360f) % 360f
                sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
                emit()
            }
            Canvas(
                Modifier.size(240.dp)
                    .pointerInput(Unit) { detectTapGestures { pick(it, size.width.toFloat()) } }
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { pick(it, size.width.toFloat()) }) { ch, _ ->
                            ch.consume(); pick(ch.position, size.width.toFloat())
                        }
                    }
            ) {
                val r = min(size.width, size.height) / 2
                val hues = (0..12).map { hsv(it * 30f, 1f, value) }
                drawCircle(Brush.sweepGradient(hues, center), r)
                drawCircle(Brush.radialGradient(listOf(hsv(0f, 0f, value), Color.Transparent), center, r), r)
                drawCircle(Color.White.copy(alpha = 0.35f), r, style = Stroke(1.dp.toPx()))
                // Thumb
                val a = Math.toRadians(hue.toDouble())
                val p = Offset(center.x + (cos(a) * sat * r).toFloat(), center.y + (sin(a) * sat * r).toFloat())
                drawCircle(Color.Black.copy(alpha = 0.25f), 17.dp.toPx(), p + Offset(0f, 2.dp.toPx()))
                drawCircle(Color.White, 16.dp.toPx(), p)
                drawCircle(hsv(hue, sat, value), 12.dp.toPx(), p)
            }
        }

        // Brightness
        Canvas(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures { value = (it.x / size.width).coerceIn(0.15f, 1f); emit() }
                }
                .pointerInput(Unit) {
                    detectDragGestures { ch, _ ->
                        ch.consume(); value = (ch.position.x / size.width).coerceIn(0.15f, 1f); emit()
                    }
                }
        ) {
            val h = size.height
            drawRoundRect(
                Brush.horizontalGradient(listOf(Color.Black, hsv(hue, sat, 1f))),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2)
            )
            val x = value * size.width
            drawCircle(Color.White, h / 2 - 2.dp.toPx(), Offset(x.coerceIn(h / 2, size.width - h / 2), h / 2))
        }

        // Presets
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val current = hsv(hue, sat, value)
            Presets.forEach { p ->
                val hsvP = toHsv(p)
                val selected = current.toArgb() == p.toArgb()
                val ring by animateDpAsState(if (selected) 3.dp else 0.dp, label = "ring")
                Box(
                    Modifier.size(30.dp).background(p, CircleShape)
                        .border(ring, c.text, CircleShape)
                        .pointerInput(p) {
                            detectTapGestures {
                                hue = hsvP[0]; sat = hsvP[1]; value = hsvP[2]; emit()
                            }
                        }
                )
            }
        }

        // Preview
        val preview by animateColorAsState(hsv(hue, sat, value), label = "preview")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Box(
                Modifier.background(preview, CircleShape).padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                androidx.compose.material3.Text("Так будут выглядеть кнопки и акценты", color = onColor(preview))
            }
        }
    }
}
