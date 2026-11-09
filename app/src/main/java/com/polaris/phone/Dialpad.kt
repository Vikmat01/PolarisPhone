package com.polaris.phone

import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import com.kyant.backdrop.Backdrop

private val Keys = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF",
    '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '*' to "", '0' to "+", '#' to ""
)

fun dtmfTone(k: Char): Int? = when (k) {
    '0' -> ToneGenerator.TONE_DTMF_0; '1' -> ToneGenerator.TONE_DTMF_1; '2' -> ToneGenerator.TONE_DTMF_2
    '3' -> ToneGenerator.TONE_DTMF_3; '4' -> ToneGenerator.TONE_DTMF_4; '5' -> ToneGenerator.TONE_DTMF_5
    '6' -> ToneGenerator.TONE_DTMF_6; '7' -> ToneGenerator.TONE_DTMF_7; '8' -> ToneGenerator.TONE_DTMF_8
    '9' -> ToneGenerator.TONE_DTMF_9; '*' -> ToneGenerator.TONE_DTMF_S; '#' -> ToneGenerator.TONE_DTMF_P
    else -> null
}

/** Where the finger is over the grid (grid-local px), or null; plus how strongly the light is shown. */
private class Light {
    var finger by mutableStateOf<Offset?>(null)
    val amount = Animatable(0f)
}

/**
 * 3×4 grid of round glass keys.
 * - The pressed key swells, brightens and its letters lift.
 * - A soft light follows the finger: it glows inside the keys it passes over and catches on the rims
 *   of nearby keys, as if the glass were reflecting a lamp held above it.
 * - Long-press "0" gives "+".
 */
@Composable
fun DialGrid(
    backdrop: Backdrop,
    keySize: Dp = 76.dp,
    onKeyDown: (Char) -> Unit = {},
    onLongZero: (() -> Unit)? = null,
    onKey: (Char) -> Unit
) {
    val light = remember { Light() }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sizePx = with(density) { keySize.toPx() }
    val gapX = with(density) { 26.dp.toPx() }
    val gapY = with(density) { 14.dp.toPx() }

    Column(
        // Watches the finger on the Initial pass, so keys still get their own taps
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                light.finger = down.position
                scope.launch { light.amount.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 500f)) }
                var overKey = keyAt(down.position, sizePx, gapX, gapY)
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    light.finger = ch.position
                    // A whisper of a tick each time the finger glides onto another key
                    val k = keyAt(ch.position, sizePx, gapX, gapY)
                    if (k != null && k != overKey) haptics.glide()
                    overKey = k
                }
                scope.launch { light.amount.animateTo(0f, tween(450)) }
            }
        },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Keys.chunked(3).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                row.forEachIndexed { col, (k, letters) ->
                    val origin = Offset(col * (sizePx + gapX), r * (sizePx + gapY))
                    DialKey(
                        backdrop, k, letters, keySize, light, origin,
                        onDown = { haptics.press(); onKeyDown(k) },
                        onUp = { haptics.release() },
                        onLong = if (k == '0') onLongZero?.let { f -> { haptics.long(); f() } } else null,
                        onClick = { onKey(k) }
                    )
                }
            }
        }
    }
}

/** Which key (index 0..11) is under a grid-local point, or null when between keys. */
private fun keyAt(p: Offset, size: Float, gapX: Float, gapY: Float): Int? {
    if (p.x < 0 || p.y < 0) return null
    val col = (p.x / (size + gapX)).toInt()
    val row = (p.y / (size + gapY)).toInt()
    if (col > 2 || row > 3) return null
    if (p.x - col * (size + gapX) > size || p.y - row * (size + gapY) > size) return null
    return row * 3 + col
}

@Composable
private fun DialKey(
    backdrop: Backdrop,
    k: Char,
    letters: String,
    size: Dp,
    light: Light,
    origin: Offset,
    onDown: () -> Unit,
    onUp: () -> Unit,
    onLong: (() -> Unit)?,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    val press = rememberPress()
    val tint = if (c.dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.45f)
    val glowColor = if (c.dark) Color.White else c.bubble
    Box(
        Modifier.size(size)
            .liquidGlass(
                backdrop, Pill, tint, 12.dp, 24.dp, press = press, swell = 10.dp,
                extraSurface = surface@{
                    // Pressed: a quick bright flash filling the key
                    val p = press.value.coerceIn(0f, 1f)
                    if (p > 0f) drawRect(glowColor.copy(alpha = 0.16f * p))
                    val f = light.finger ?: return@surface
                    val a = light.amount.value
                    if (a <= 0f) return@surface
                    val local = f - origin
                    val center = Offset(this.size.width / 2, this.size.height / 2)
                    val r = this.size.minDimension / 2
                    val dist = (local - center).getDistance()
                    // 1 over the key, fading to 0 about two keys away
                    val near = (1f - dist / (r * 4.2f)).coerceIn(0f, 1f)
                    if (near <= 0f) return@surface
                    // Soft light inside the glass, centred where the finger is
                    drawCircle(
                        Brush.radialGradient(
                            listOf(glowColor.copy(alpha = 0.32f * a * near * near), Color.Transparent),
                            center = local, radius = r * 1.6f
                        ),
                        radius = r * 1.6f, center = local
                    )
                    // Rim catches the light on the side facing the finger
                    val dir = if (dist > 1f) (local - center) / dist else Offset(0f, -1f)
                    drawCircle(
                        Brush.linearGradient(
                            listOf(glowColor.copy(alpha = 0.85f * a * near), Color.Transparent),
                            start = center + dir * r, end = center - dir * (r * 0.4f)
                        ),
                        radius = r - 1.dp.toPx(), center = center, style = Stroke(1.6.dp.toPx())
                    )
                }
            )
            .pressableLong(press, onDown = onDown, onUp = onUp, onLongClick = onLong, systemHaptics = false, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                // Digit dips slightly and letters lift as the key is pressed
                val p = press.value
                scaleX = 1f - 0.06f * p; scaleY = 1f - 0.06f * p
            }
        ) {
            Text(k.toString(), color = c.text, fontSize = 30.sp, fontWeight = FontWeight.Normal, lineHeight = 32.sp)
            if (letters.isNotEmpty()) Text(letters, color = c.dim, fontSize = 10.sp, letterSpacing = 1.5.sp, lineHeight = 12.sp)
        }
    }
}

/** The "Клавиатура" tab: number field, contact suggestion, keys, call and backspace. */
@Composable
fun DialpadTab(
    backdrop: Backdrop,
    number: String,
    onNumber: (String) -> Unit,
    contacts: List<Contact>,
    onAddContact: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val tonesOn = remember {
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1 }
            .getOrDefault(true)
    }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 60) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    val match = remember(number, contacts) { PhoneData.matchDigits(contacts, number).firstOrNull() }
    val haptics = rememberHaptics()

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Number — shrinks as it gets longer
        val target = when {
            number.length > 15 -> 26f
            number.length > 11 -> 32f
            else -> 40f
        }
        val fs by animateFloatAsState(target, spring(stiffness = Spring.StiffnessMediumLow), label = "fs")
        Text(
            number.ifEmpty { " " }, color = c.text, fontSize = fs.sp, fontWeight = FontWeight.Light,
            maxLines = 1, overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.padding(horizontal = 24.dp).animateContentSize()
        )
        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(
                match != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                val m = match
                if (m != null) {
                    GlassButton(backdrop, "${m.first.name} · ${m.second.number}") { onNumber(m.second.number) }
                }
            }
            // Unknown number typed: offer to save it
            androidx.compose.animation.AnimatedVisibility(
                match == null && number.count { it.isDigit() } >= 3,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                GlassButton(backdrop, "+ Добавить в контакты") { onAddContact(number) }
            }
        }
        Spacer(Modifier.height(12.dp))
        DialGrid(
            backdrop,
            onKeyDown = { k -> if (tonesOn) dtmfTone(k)?.let { tone?.startTone(it, 100) } },
            onLongZero = { onNumber(number + "+") },
            onKey = { k -> onNumber(number + k) }
        )
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.size(56.dp))
            val press = rememberPress()
            val canCall = number.isNotBlank()
            Box(
                Modifier.size(76.dp)
                    .liquidGlass(backdrop, Pill, CallGreen.copy(alpha = if (canCall) 0.9f else 0.45f), 14.dp, 28.dp, press = press)
                    .pressable(press) { if (canCall) PhoneData.call(ctx, number) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Call, "Позвонить", tint = Color.White, modifier = Modifier.size(32.dp)) }
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(
                    number.isNotEmpty(),
                    enter = scaleIn(spring(0.5f, 500f)) + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    val bp = rememberPress()
                    Box(
                        Modifier.size(56.dp).graphicsLayer {
                            val s = 1f - 0.1f * bp.value
                            scaleX = s; scaleY = s
                        }.pressableLong(
                            bp, onDown = { haptics.press() }, onUp = { haptics.release() },
                            onLongClick = { haptics.long(); onNumber("") }, systemHaptics = false
                        ) { onNumber(number.dropLast(1)) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.AutoMirrored.Filled.Backspace, "Стереть", tint = c.dim, modifier = Modifier.size(28.dp)) }
                }
            }
        }
    }
}
