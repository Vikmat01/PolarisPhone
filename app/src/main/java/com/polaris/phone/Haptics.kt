package com.polaris.phone

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Soft, "glassy" key haptics built from the vibrator's primitives:
 * - press:   a crisp but light click as the finger lands
 * - release: an even lighter tick as it lifts, so each key feels like a real button going down and up
 * - glide:   a barely-there low tick when the finger slides onto another key
 * - long:    a fuller thud for long-press actions ("+", clear)
 * …plus selection / sheet / confirm / end for the rest of the interface.
 * Falls back to predefined effects on phones without primitives.
 * Uses the "touch" usage, so the system "vibration on touch" switch still turns it off.
 */
class Haptics private constructor(ctx: Context) {
    companion object {
        @Volatile private var instance: Haptics? = null
        fun of(ctx: Context): Haptics =
            instance ?: synchronized(this) { instance ?: Haptics(ctx.applicationContext).also { instance = it } }
    }

    private val vibrator: Vibrator? =
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)

    private val primitives = vibrator?.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        VibrationEffect.Composition.PRIMITIVE_THUD
    ) == true

    private fun prim(id: Int, scale: Float): VibrationEffect =
        VibrationEffect.startComposition().addPrimitive(id, scale).compose()

    private val pressFx = if (primitives) prim(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f)
    else VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)

    private val releaseFx = if (primitives) prim(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f)
    else VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

    private val glideFx = if (primitives) prim(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.5f)
    else VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

    private val longFx = if (primitives)
        VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.6f)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 40)
            .compose()
    else VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)

    private val selectionFx = if (primitives) prim(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
    else VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

    private val sheetFx = if (primitives) prim(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f)
    else VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

    private val confirmFx = if (primitives)
        VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 70)
            .compose()
    else VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)

    private val endFx = if (primitives) prim(VibrationEffect.Composition.PRIMITIVE_THUD, 0.5f)
    else VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)

    private fun play(fx: VibrationEffect) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(fx, attrs) }
    }

    fun press() = play(pressFx)
    fun release() = play(releaseFx)
    fun glide() = play(glideFx)
    fun long() = play(longFx)
    /** Tab / theme switch landing on a new option, or the thumb passing one while dragged. */
    fun selection() = play(selectionFx)
    /** A sheet or menu opening. */
    fun sheet() = play(sheetFx)
    /** Something important succeeded: call answered / connected, contact saved. */
    fun confirm() = play(confirmFx)
    /** Call ended. */
    fun end() = play(endFx)
}

@Composable
fun rememberHaptics(): Haptics {
    val ctx = LocalContext.current
    return remember { Haptics.of(ctx) }
}
