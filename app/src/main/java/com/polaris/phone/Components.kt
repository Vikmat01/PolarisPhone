package com.polaris.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kyant.backdrop.Backdrop
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

fun RoundedBubble(outgoing: Boolean) = RoundedCornerShape(
    topStart = 20.dp, topEnd = 20.dp,
    bottomEnd = if (outgoing) 6.dp else 20.dp,
    bottomStart = if (outgoing) 20.dp else 6.dp
)

/** Floating glass header: back button + title pill + optional actions. */
@Composable
fun GlassHeader(
    backdrop: Backdrop,
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (onBack != null) {
            GlassIconButton(backdrop, onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = c.text)
            }
        }
        Column(
            Modifier.weight(1f).frostedGlass(backdrop, Pill)
                .padding(horizontal = 18.dp, vertical = if (subtitle == null) 12.dp else 6.dp)
        ) {
            Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(
                subtitle, color = c.text.copy(alpha = 0.7f), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        actions()
    }
}

/** Glass pill button with text, swells on press. */
@Composable
fun GlassButton(
    backdrop: Backdrop,
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    val press = rememberPress()
    val tint = when {
        accent -> c.bubble.copy(alpha = 0.85f)
        c.dark -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.5f)
    }
    Box(
        modifier.liquidGlass(backdrop, Pill, tint, 10.dp, 20.dp, press = press)
            .pressable(press, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (accent) c.bubbleText else c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Bottom sheet made of glass: slides up on a spring, scrim fades. */
@Composable
fun GlassSheet(
    visible: Boolean,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = LocalColors.current
    val haptics = rememberHaptics()
    LaunchedEffect(visible) { if (visible) haptics.sheet() }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = if (c.dark) 0.5f else 0.22f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible,
            enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it } + fadeOut()
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(10.dp).fillMaxWidth()
                    .liquidGlass(
                        backdrop, RoundedCornerShape(34.dp),
                        if (c.dark) Color(0xFF15141C).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.72f),
                        20.dp, 40.dp, blurRadius = 16.dp
                    )
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                content = content
            )
        }
    }
}

@Composable
fun SheetAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = LocalColors.current
    val press = rememberPress()
    Box(
        Modifier.fillMaxWidth().pressScale(press, 0.03f)
            .background(c.surface, RoundedCornerShape(18.dp))
            .pressable(press, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp)
    ) {
        Text(text, color = if (danger) c.danger else c.text, fontSize = 16.sp)
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Contact avatar: the contact's photo if there is one (fades in once loaded),
 * otherwise a gradient circle with the initial. Pass [photo] directly, or set [lookup]
 * to find the photo by phone number ([address]).
 */
@Composable
fun Avatar(
    title: String,
    address: String,
    size: Dp = 48.dp,
    photo: String? = null,
    lookup: Boolean = false,
    full: Boolean = false
) {
    val ctx = LocalContext.current
    val version = LocalVersion.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val uri by produceState(photo, photo, address, lookup, version) {
        value = photo ?: if (lookup) withContext(Dispatchers.IO) { PhoneData.photoFor(ctx, address, full) } else null
    }
    val bitmap by produceState(uri?.let { Photos.cached(it, px) }, uri, px) {
        value = uri?.let { Photos.load(ctx, it, px) }
    }
    val shown by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(260), label = "photo")

    val hue = ((address.hashCode() % 360) + 360) % 360f
    val base = Color.hsv(hue, 0.45f, 0.85f)
    val letter = title.firstOrNull { it.isLetter() }?.uppercase()
    Box(
        Modifier.size(size).clip(CircleShape).background(
            Brush.linearGradient(listOf(base, Color.hsv((hue + 40) % 360, 0.55f, 0.65f)))
        ),
        contentAlignment = Alignment.Center
    ) {
        if (shown < 1f) {
            Text(letter ?: "#", color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold)
        }
        bitmap?.let {
            Image(
                it, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown }
            )
        }
    }
}

/**
 * Liquid-glass switch (tabs, theme): a frosted glass thumb filled with the accent that you can tap
 * or drag. It springs to the nearest option on release, swells while held, has a slight rainbow
 * edge, and labels (drawn above it, always crisp) change colour as the thumb passes under them.
 */
@Composable
fun Segmented(backdrop: Backdrop, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    val last = options.lastIndex
    val spec = spring<Float>(dampingRatio = 0.72f, stiffness = 380f)
    val select by rememberUpdatedState(onSelect)
    val haptics = rememberHaptics()

    BoxWithConstraints(
        Modifier.fillMaxWidth().height(50.dp)
            // Frosted track: strong blur + denser tint so the list behind never competes with the labels
            .liquidGlass(
                backdrop, Pill,
                if (c.dark) Color(0xFF14131B).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.72f),
                10.dp, 20.dp, blurRadius = 16.dp
            )
            .padding(4.dp)
    ) {
        val w = maxWidth / options.size
        val wPx = with(LocalDensity.current) { w.toPx() }
        val pos = remember { Animatable(0f) }          // thumb offset in px
        val hold = remember { Animatable(0f) }         // 0..1, swelling while touched
        var dragging by remember { mutableStateOf(false) }
        var ready by remember { mutableStateOf(false) }

        LaunchedEffect(selected, wPx) {
            if (!ready) { pos.snapTo(wPx * selected); ready = true }
            else if (!dragging) pos.animateTo(wPx * selected, spec)
        }

        // Tick whenever the thumb's nearest option changes — while dragging and while it springs
        var lastNear by remember { mutableStateOf(selected) }
        LaunchedEffect(wPx) {
            snapshotFlow { (pos.value / wPx).roundToInt().coerceIn(0, last) }.collect { n ->
                if (ready && n != lastNear) haptics.selection()
                lastNear = n
            }
        }

        fun settle(target: Int) {
            scope.launch { pos.animateTo(wPx * target, spec) }
            scope.launch { hold.animateTo(0f, Motion.press) }
            if (target != selected) select(target)
        }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(wPx, selected) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            scope.launch { hold.animateTo(1f, Motion.press) }
                        },
                        onDragEnd = {
                            dragging = false
                            settle((pos.value / wPx).roundToInt().coerceIn(0, last))
                        },
                        onDragCancel = {
                            dragging = false
                            settle(selected)
                        },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            scope.launch { pos.snapTo((pos.value + dx).coerceIn(0f, wPx * last)) }
                        }
                    )
                }
                .pointerInput(wPx, selected) {
                    detectTapGestures(
                        onPress = {
                            haptics.press()
                            scope.launch { hold.animateTo(1f, Motion.press) }
                            tryAwaitRelease()
                            scope.launch { hold.animateTo(0f, Motion.press) }
                        },
                        onTap = { o -> settle((o.x / wPx).toInt().coerceIn(0, last)) }
                    )
                }
        ) {
            // The glass thumb: frosted (blurred) so nothing behind shows through sharply,
            // filled with the accent so the selected label stays readable
            Box(
                Modifier.offset { IntOffset(pos.value.roundToInt(), 0) }
                    .width(w).fillMaxHeight()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Pill },
                        effects = {
                            vibrancy()
                            blur(10f.dp.toPx())
                            lens(8f.dp.toPx(), 10f.dp.toPx(), chromaticAberration = true)
                        },
                        layerBlock = {
                            val s = 1f + (8.dp.toPx() / size.height.coerceAtLeast(1f)) * hold.value
                            scaleX = s + 0.04f * hold.value
                            scaleY = s
                        },
                        onDrawSurface = {
                            drawRect(c.bubble.copy(alpha = 0.82f))
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.06f))
                                )
                            )
                        }
                    )
            )
            // Labels on top of the thumb — always crisp
            Row(Modifier.fillMaxSize()) {
                options.forEachIndexed { i, o ->
                    val near = (1f - abs(pos.value / wPx - i)).coerceIn(0f, 1f)
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            o, color = lerp(c.text.copy(alpha = 0.75f), c.bubbleText, near),
                            fontSize = 14.sp, fontWeight = if (near > 0.5f) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
