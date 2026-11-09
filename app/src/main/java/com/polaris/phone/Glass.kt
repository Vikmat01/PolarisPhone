package com.polaris.phone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

val Pill = RoundedCornerShape(50)
val Card = RoundedCornerShape(28.dp)

/** Shared springs so every movement in the app feels the same. */
object Motion {
    val press = spring<Float>(dampingRatio = 0.55f, stiffness = 600f)
    val soft = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
}

/** Press state for a glass control: 0 = idle, 1 = fully pressed. Animated with a bouncy spring. */
class PressState(val anim: Animatable<Float, AnimationVector1D>) {
    val value get() = anim.value
}

@Composable
fun rememberPress() = remember { PressState(Animatable(0f)) }

/** Detects press/release, animates [press], fires [onClick]; a soft click on touch and a tick on release. */
fun Modifier.pressable(press: PressState, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val click by rememberUpdatedState(onClick)
    if (!enabled) return@composed Modifier
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            haptics.press()
            scope.launch { press.anim.animateTo(1f, Motion.press) }
            val up = waitForUpOrCancellation()
            scope.launch { press.anim.animateTo(0f, Motion.press) }
            if (up != null) {
                // Consume the release so a clickable parent (e.g. a Recents row) doesn't also fire
                up.consume()
                haptics.release()
                click()
            }
        }
    }
}

/**
 * Like [pressable], plus an immediate [onDown] (for key tones) and [onLongClick]
 * fired when the finger stays down longer than the system long-press timeout.
 */
fun Modifier.pressableLong(
    press: PressState,
    onDown: () -> Unit = {},
    onUp: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    systemHaptics: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    val down by rememberUpdatedState(onDown)
    val upCb by rememberUpdatedState(onUp)
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            down()
            if (systemHaptics) haptics.press()
            scope.launch { press.anim.animateTo(1f, Motion.press) }
            val l = long
            if (l == null) {
                val up = waitForUpOrCancellation()
                scope.launch { press.anim.animateTo(0f, Motion.press) }
                if (up != null) { up.consume(); if (systemHaptics) haptics.release(); upCb(); click() }
            } else {
                val timeout = viewConfiguration.longPressTimeoutMillis
                val t0 = System.currentTimeMillis()
                val up = withTimeoutOrNull(timeout) { waitForUpOrCancellation() }
                // null + time elapsed = long press; null early = gesture cancelled
                val timedOut = up == null && System.currentTimeMillis() - t0 >= timeout - 20 &&
                    currentEvent.changes.any { it.pressed }
                if (timedOut) {
                    if (systemHaptics) haptics.long()
                    l()
                    waitForUpOrCancellation()?.consume()
                    scope.launch { press.anim.animateTo(0f, Motion.press) }
                } else {
                    scope.launch { press.anim.animateTo(0f, Motion.press) }
                    if (up != null) { up.consume(); if (systemHaptics) haptics.release(); upCb(); click() }
                }
            }
        }
    }
}

/**
 * Real liquid glass (Kyant0/backdrop): blur + vibrancy + lens refraction of what's behind.
 * The press animation goes through layerBlock, so the glass itself swells and the refraction
 * stays correct instead of the whole layer being scaled.
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: Shape = Card,
    tint: Color = Color.Transparent,
    lensHeight: Dp = 16.dp,
    lensAmount: Dp = 32.dp,
    blurRadius: Dp = 6.dp,
    press: PressState? = null,
    swell: Dp = 6.dp,
    extraSurface: (DrawScope.() -> Unit)? = null
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
        lens(lensHeight.toPx(), lensAmount.toPx())
    },
    layerBlock = if (press != null) {
        {
            val s = 1f + (swell.toPx() / size.height.coerceAtLeast(1f)) * press.value
            scaleX = s
            scaleY = s
        }
    } else {
        null
    },
    onDrawSurface = {
        if (tint.alpha > 0f) drawRect(tint)
        extraSurface?.invoke(this)
    }
)

/** Glass that follows the current theme: whitish in light mode, faint in dark mode. */
@Composable
fun Modifier.themedGlass(
    backdrop: Backdrop,
    shape: Shape = Card,
    press: PressState? = null,
    lensHeight: Dp = 16.dp,
    lensAmount: Dp = 32.dp
): Modifier {
    val c = LocalColors.current
    val tint = if (c.dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.45f)
    return liquidGlass(backdrop, shape, tint, lensHeight, lensAmount, press = press)
}

/**
 * Frosted glass for bars and buttons that float over content (headers, back/menu buttons):
 * strong blur and a denser tint, so text behind never shows through sharply and what's on the
 * glass stays readable. Still refracts at the edges.
 */
@Composable
fun Modifier.frostedGlass(
    backdrop: Backdrop,
    shape: Shape = Pill,
    press: PressState? = null
): Modifier {
    val c = LocalColors.current
    val tint = if (c.dark) Color(0xFF14131B).copy(alpha = 0.58f) else Color.White.copy(alpha = 0.72f)
    return liquidGlass(backdrop, shape, tint, 10.dp, 20.dp, blurRadius = 16.dp, press = press)
}

/** Round glass button that swells when pressed. */
@Composable
fun GlassIconButton(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val press = rememberPress()
    Box(
        modifier
            .frostedGlass(backdrop, Pill, press)
            .pressable(press, onClick = onClick)
            .size(size),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Scale-on-press for non-glass elements (list rows, bubbles). */
fun Modifier.pressScale(press: PressState, amount: Float = 0.04f) =
    graphicsLayer {
        val s = 1f - amount * press.value
        scaleX = s
        scaleY = s
    }

/** Scale-on-press driven by a clickable's interaction source (for combinedClickable rows and bubbles). */
@Composable
fun Modifier.pressScale(src: MutableInteractionSource, amount: Float = 0.04f): Modifier {
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 1f - amount else 1f, Motion.press, label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** Background: theme base colour with two slowly drifting blobs of light. Gives the glass something to refract. */
@Composable
fun LivingBackground(modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val t = rememberInfiniteTransition(label = "bg")
    val a by t.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "a"
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(c.bg)
        val w = size.width
        val h = size.height
        val p1 = Offset(w * (0.25f + 0.18f * cos(a)), h * (0.22f + 0.10f * sin(a)))
        val p2 = Offset(w * (0.78f + 0.15f * sin(a * 0.7f)), h * (0.70f + 0.12f * cos(a * 0.7f)))
        drawCircle(Brush.radialGradient(listOf(c.glow1, Color.Transparent), p1, w * 0.75f), w * 0.75f, p1)
        drawCircle(Brush.radialGradient(listOf(c.glow2, Color.Transparent), p2, w * 0.8f), w * 0.8f, p2)
    }
}
