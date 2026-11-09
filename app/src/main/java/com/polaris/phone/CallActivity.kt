package com.polaris.phone

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

/** Full-screen call UI: incoming (answer / decline) and ongoing (mute, keypad, speaker, hold…). */
class CallActivity : ComponentActivity() {
    override fun onStart() { super.onStart(); AppVisibility.onStart() }
    override fun onStop() { AppVisibility.onStop(); super.onStop() }

    companion object {
        fun intent(ctx: Context) = Intent(ctx, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }

    private var proximity: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val prefs = Prefs(this)
        val dark = themeIsDark(prefs)
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        requestMaxRefreshRate()
        proximity = getSystemService(PowerManager::class.java)
            ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "polaris:proximity")

        setContent {
            val colors = animated(themeColors(prefs, dark))
            CompositionLocalProvider(LocalColors provides colors) {
                CallScreen(onFinished = { finishAndRemoveTask() }, onProximity = ::setProximity)
            }
        }
    }

    /** Screen goes dark when held to the ear (only for earpiece, not speaker). */
    private fun setProximity(on: Boolean) {
        val wl = proximity ?: return
        if (on && !wl.isHeld) wl.acquire(3 * 60 * 60 * 1000L)
        if (!on && wl.isHeld) wl.release()
    }

    override fun onDestroy() {
        setProximity(false)
        super.onDestroy()
    }

    private fun requestMaxRefreshRate() {
        val d = display ?: return
        val cur = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also {
            it.preferredDisplayModeId = best.modeId
            it.preferredRefreshRate = best.refreshRate
        }
        if (Build.VERSION.SDK_INT >= 35) {
            window.setFrameRatePowerSavingsBalanced(false)
            window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }
}

private enum class Phase { INCOMING, DIALING, ACTIVE, HOLDING, ENDED }

private fun phaseOf(state: Int?): Phase = when (state) {
    Call.STATE_RINGING -> Phase.INCOMING
    Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_PULLING_CALL -> Phase.DIALING
    Call.STATE_ACTIVE -> Phase.ACTIVE
    Call.STATE_HOLDING -> Phase.HOLDING
    else -> Phase.ENDED
}

@Composable
private fun CallScreen(onFinished: () -> Unit, onProximity: (Boolean) -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val backdrop = rememberLayerBackdrop()

    // Remember the last call so the "ended" state can still show who it was
    val live = CallManager.primary
    var lastCall by remember { mutableStateOf<Call?>(null) }
    if (live != null) lastCall = live
    val call = live ?: lastCall
    val phase = if (live == null) Phase.ENDED else phaseOf(CallManager.states[live])

    val number = call?.let { CallManager.number(it) } ?: ""
    val name = remember(number) { PhoneData.name(ctx, number) }
    val title = name ?: number.ifBlank { "Скрытый номер" }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }

    val haptics = rememberHaptics()
    var lastPhase by remember { mutableStateOf<Phase?>(null) }
    LaunchedEffect(phase) {
        // Feel the call connect and end, even with the phone at the ear
        if (lastPhase != null && phase == Phase.ACTIVE && lastPhase != Phase.HOLDING) haptics.confirm()
        if (lastPhase != null && phase == Phase.ENDED) haptics.end()
        lastPhase = phase
        if (phase == Phase.ENDED) { delay(1300); onFinished() }
    }
    LaunchedEffect(phase, CallManager.speaker) {
        onProximity((phase == Phase.ACTIVE || phase == Phase.DIALING) && !CallManager.speaker)
    }

    var keypad by remember { mutableStateOf(false) }

    val status = when (phase) {
        Phase.INCOMING -> "Входящий вызов"
        Phase.DIALING -> "Вызов…"
        Phase.HOLDING -> "На удержании"
        Phase.ENDED -> "Вызов завершён"
        Phase.ACTIVE -> {
            val start = call?.details?.connectTimeMillis ?: 0L
            if (start > 0) fmtDuration(((now - start) / 1000).coerceAtLeast(0)) else "00:00"
        }
    }

    Box(Modifier.fillMaxSize()) {
        LivingBackground(Modifier.layerBackdrop(backdrop))

        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // Shorter screens (or long names with emoji) get a smaller avatar so nothing collides
        val compact = maxHeight < 760.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(if (compact) 20.dp else 48.dp))
            PulsingAvatar(
                title, number, ringing = phase == Phase.INCOMING || phase == Phase.DIALING,
                box = if (compact) 160.dp else 200.dp, avatar = if (compact) 100.dp else 120.dp
            )
            Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
            Text(
                title, color = c.text, fontSize = if (compact) 26.sp else 30.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = if (compact) 32.sp else 36.sp
            )
            if (name != null) Text(number, color = c.dim, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            // The whole line animates only when its meaning changes (Вызов… → timer → Завершён);
            // while talking, only the digits that actually change roll over.
            val isTimer = phase == Phase.ACTIVE
            AnimatedContent(
                if (isTimer) "timer" else status,
                transitionSpec = {
                    (fadeIn(tween(250)) + slideInVertically { it / 3 }) togetherWith (fadeOut(tween(150)) + slideOutVertically { -it / 3 })
                },
                modifier = Modifier.clipToBounds(),
                label = "status"
            ) { key ->
                if (key == "timer") RollingText(status, c.dim, 17)
                else Text(key, color = if (phase == Phase.ENDED) c.danger else c.dim, fontSize = 17.sp)
            }

            // Always keep air between the status line and the buttons
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.weight(1f))

            AnimatedContent(
                when (phase) { Phase.INCOMING -> 0; Phase.ENDED -> 2; else -> 1 },
                transitionSpec = { (fadeIn(tween(300)) + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut(tween(200)) + scaleOut(targetScale = 0.9f)) },
                label = "controls"
            ) { mode ->
                when (mode) {
                    0 -> IncomingControls(backdrop, call)
                    1 -> OngoingControls(backdrop, call, phase, onKeypad = { keypad = true })
                    // Call over: the buttons fade away, the "Вызов завершён" line stays centred and clear
                    else -> Spacer(Modifier.fillMaxWidth().height(1.dp))
                }
            }
            Spacer(Modifier.height(if (compact) 20.dp else 36.dp))
        }
        }

        // DTMF keypad sheet
        GlassSheet(keypad && phase != Phase.ENDED, backdrop, onDismiss = { keypad = false }) {
            InCallKeypad(backdrop, call) { keypad = false }
        }
    }
}

/** Text whose characters roll vertically one by one — only the ones that changed move. */
@Composable
private fun RollingText(text: String, color: Color, sizeSp: Int) {
    Row {
        // Key characters from the right, so "9:59" → "10:00" keeps seconds aligned with seconds
        text.forEachIndexed { i, ch ->
            val fromRight = text.length - i
            androidx.compose.runtime.key(fromRight) {
                AnimatedContent(
                    ch,
                    transitionSpec = {
                        (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { it } + fadeIn(tween(200))) togetherWith
                            (slideOutVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { -it } + fadeOut(tween(150)))
                    },
                    label = "digit"
                ) { d ->
                    Text(
                        d.toString(), color = color, fontSize = sizeSp.sp,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                    )
                }
            }
        }
    }
}

/** Big avatar with soft rings radiating while the call is ringing or dialing. */
@Composable
private fun PulsingAvatar(title: String, number: String, ringing: Boolean, box: Dp = 200.dp, avatar: Dp = 120.dp) {
    val c = LocalColors.current
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")
    Box(Modifier.size(box), contentAlignment = Alignment.Center) {
        if (ringing) {
            Canvas(Modifier.fillMaxSize()) {
                for (k in 0..2) {
                    val f = (p + k / 3f) % 1f
                    drawCircle(
                        c.bubble.copy(alpha = (1f - f) * 0.45f),
                        radius = size.minDimension / 2 * (0.6f + 0.4f * f),
                        style = Stroke(2.dp.toPx())
                    )
                }
            }
        }
        Avatar(title, number, avatar, lookup = true, full = true)
    }
}

@Composable
private fun IncomingControls(backdrop: Backdrop, call: Call?) {
    val c = LocalColors.current
    // The whole green button hops: quick jump, fall, small rebound, then a pause — like it's asking to be tapped.
    // The handset rocks while the button is in the air.
    val hopAnim = remember { Animatable(0f) }
    val rockAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            launch {
                rockAnim.animateTo(-14f, tween(120))
                rockAnim.animateTo(12f, tween(140))
                rockAnim.animateTo(-8f, tween(140))
                rockAnim.animateTo(0f, tween(160))
            }
            hopAnim.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
            hopAnim.animateTo(0f, tween(230, easing = FastOutLinearInEasing))
            hopAnim.animateTo(0.32f, tween(130, easing = LinearOutSlowInEasing))
            hopAnim.animateTo(0f, tween(140, easing = FastOutLinearInEasing))
            delay(800)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(backdrop, Icons.Filled.CallEnd, "Отклонить", c.danger, 78.dp) {
            call?.let { CallManager.reject(it) }
        }
        RoundAction(
            backdrop, Icons.Filled.Call, "Ответить", CallGreen, 78.dp,
            // Layout offset (not a graphics layer) so the liquid glass keeps sampling the right spot
            buttonOffset = { IntOffset(0, -(14.dp.toPx() * hopAnim.value).roundToInt()) },
            iconModifier = Modifier.graphicsLayer { rotationZ = rockAnim.value }
        ) {
            call?.let { CallManager.answer(it) }
        }
    }
}

@Composable
private fun OngoingControls(backdrop: Backdrop, call: Call?, phase: Phase, onKeypad: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val active = phase == Phase.ACTIVE || phase == Phase.HOLDING
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToggleAction(backdrop, if (CallManager.muted) Icons.Filled.MicOff else Icons.Filled.Mic, "Без звука", CallManager.muted) {
                CallManager.setMuted(!CallManager.muted)
            }
            ToggleAction(backdrop, Icons.Filled.Dialpad, "Клавиатура", false, enabled = active) { onKeypad() }
            ToggleAction(backdrop, Icons.AutoMirrored.Filled.VolumeUp, "Динамик", CallManager.speaker) {
                CallManager.setSpeaker(!CallManager.speaker)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToggleAction(backdrop, Icons.Filled.Pause, "Удержание", phase == Phase.HOLDING, enabled = active) {
                call?.let { CallManager.toggleHold(it) }
            }
            ToggleAction(
                backdrop, Icons.Filled.Bluetooth, "Bluetooth",
                CallManager.route == android.telecom.CallAudioState.ROUTE_BLUETOOTH,
                enabled = CallManager.bluetoothAvailable
            ) { CallManager.toggleBluetooth() }
            ToggleAction(backdrop, Icons.Filled.Sms, "Сообщение", false) {
                call?.let { PhoneData.sms(ctx, CallManager.number(it)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        RoundAction(
            backdrop, Icons.Filled.CallEnd, null, c.danger, 78.dp,
            enabled = phase != Phase.ENDED
        ) { call?.let { CallManager.hangup(it) } }
    }
}

/** Large coloured glass button (answer / decline / hang up) that swells when pressed. */
@Composable
private fun RoundAction(
    backdrop: Backdrop,
    icon: ImageVector,
    label: String?,
    color: Color,
    size: Dp,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier,
    buttonOffset: (Density.() -> IntOffset)? = null,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    val press = rememberPress()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.then(if (buttonOffset != null) Modifier.offset(buttonOffset) else Modifier)
                .size(size)
                .liquidGlass(backdrop, Pill, color.copy(alpha = if (enabled) 0.88f else 0.35f), 14.dp, 28.dp, press = press)
                .pressable(press, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = Color.White, modifier = iconModifier.size(32.dp))
        }
        if (label != null) {
            Text(label, color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Round glass toggle: fills with the accent colour when on. */
@Composable
private fun ToggleAction(
    backdrop: Backdrop,
    icon: ImageVector,
    label: String,
    on: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    val press = rememberPress()
    val tint by animateColorAsState(
        when {
            on -> c.bubble.copy(alpha = 0.9f)
            c.dark -> Color.White.copy(alpha = 0.08f)
            else -> Color.White.copy(alpha = 0.5f)
        },
        spring(stiffness = Spring.StiffnessMediumLow), label = "tint"
    )
    val iconColor by animateColorAsState(if (on) c.bubbleText else c.text, label = "icon")
    Column(
        Modifier.width(86.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(68.dp)
                .liquidGlass(backdrop, Pill, tint, 12.dp, 24.dp, press = press)
                .pressable(press, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = iconColor, modifier = Modifier.size(28.dp)) }
        Text(label, color = c.text, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
    }
}

/** DTMF keypad shown during a call; plays a local tone and sends the digit to the network. */
@Composable
private fun InCallKeypad(backdrop: Backdrop, call: Call?, onHide: () -> Unit) {
    val c = LocalColors.current
    var typed by remember { mutableStateOf("") }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 70) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            typed.ifEmpty { " " }, color = c.text, fontSize = 30.sp, maxLines = 1,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        DialGrid(backdrop, keySize = 66.dp, onKeyDown = { k ->
            dtmfTone(k)?.let { tone?.startTone(it, 120) }
            call?.playDtmfTone(k)
        }, onKey = { k ->
            call?.stopDtmfTone()
            typed += k
        })
        Spacer(Modifier.height(12.dp))
        GlassButton(backdrop, "Скрыть", Modifier.fillMaxWidth(), onClick = onHide)
    }
}
