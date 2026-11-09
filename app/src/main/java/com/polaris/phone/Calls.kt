@file:Suppress("DEPRECATION")

package com.polaris.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.os.Build
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Holds every live call and its state as Compose state, so CallActivity simply observes it.
 * Telecom drives everything: it binds PolarisInCallService while there are calls.
 */
@android.annotation.SuppressLint("NewApi") // endpoint calls are guarded by useEndpoints (API 34+)
object CallManager {
    val calls = mutableStateListOf<Call>()
    val states = mutableStateMapOf<Call, Int>()
    var audio by mutableStateOf<CallAudioState?>(null)
    var service: InCallService? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            states[call] = state
            service?.let { CallNotify.update(it) }
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) {
            states[call] = stateOf(call)
        }
    }

    fun stateOf(call: Call): Int = call.details.state

    fun add(call: Call) {
        if (call in calls) return
        calls += call
        states[call] = stateOf(call)
        call.registerCallback(callback)
    }

    fun remove(call: Call) {
        call.unregisterCallback(callback)
        calls -= call
        states -= call
    }

    /** The call the screen should be about: a ringing one first, then the active one. */
    val primary: Call?
        get() = calls.firstOrNull { states[it] == Call.STATE_RINGING }
            ?: calls.firstOrNull { states[it] == Call.STATE_ACTIVE }
            ?: calls.firstOrNull { states[it] != Call.STATE_DISCONNECTED }
            ?: calls.firstOrNull()

    fun number(call: Call): String = numberFrom(call.details.handle) ?: ""

    fun answer(call: Call) = call.answer(VideoProfile.STATE_AUDIO_ONLY)
    fun reject(call: Call) = call.reject(false, null)
    fun hangup(call: Call) {
        if (states[call] == Call.STATE_RINGING) reject(call) else call.disconnect()
    }

    fun toggleHold(call: Call) {
        if (states[call] == Call.STATE_HOLDING) call.unhold() else call.hold()
    }

    // ---- Audio routing ----
    // Android 14+ (API 34) routes audio through CallEndpoint; older versions through CallAudioState.
    // We keep both, and prefer the endpoint API whenever the system has reported endpoints.

    val endpoints = mutableStateListOf<CallEndpoint>()
    var endpoint by mutableStateOf<CallEndpoint?>(null)
    private var mutedNew by mutableStateOf<Boolean?>(null)

    private val useEndpoints get() = Build.VERSION.SDK_INT >= 34 && endpoints.isNotEmpty()

    val muted get() = mutedNew ?: (audio?.isMuted == true)

    /** Current route expressed as a CallAudioState route constant, whichever API reported it. */
    val route: Int
        get() {
            val e = endpoint
            if (Build.VERSION.SDK_INT >= 34 && e != null) return when (e.endpointType) {
                CallEndpoint.TYPE_SPEAKER -> CallAudioState.ROUTE_SPEAKER
                CallEndpoint.TYPE_BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
                CallEndpoint.TYPE_WIRED_HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
                else -> CallAudioState.ROUTE_EARPIECE
            }
            return audio?.route ?: CallAudioState.ROUTE_EARPIECE
        }

    val speaker get() = route == CallAudioState.ROUTE_SPEAKER

    val bluetoothAvailable: Boolean
        get() = if (useEndpoints) endpoints.any { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
        else (audio?.supportedRouteMask ?: 0) and CallAudioState.ROUTE_BLUETOOTH != 0

    fun setMuted(m: Boolean) {
        mutedNew = m          // flip the button at once; the system confirms via onMuteStateChanged
        service?.setMuted(m)
    }

    fun setSpeaker(on: Boolean) {
        if (on) routeTo(CallEndpoint.TYPE_SPEAKER, CallAudioState.ROUTE_SPEAKER)
        else routeToPrivate()
    }

    fun toggleBluetooth() {
        if (route == CallAudioState.ROUTE_BLUETOOTH) routeToPrivate()
        else routeTo(CallEndpoint.TYPE_BLUETOOTH, CallAudioState.ROUTE_BLUETOOTH)
    }

    /** Back to the ear: wired headset if plugged in, otherwise the earpiece. */
    private fun routeToPrivate() {
        val wired = useEndpoints && endpoints.any { it.endpointType == CallEndpoint.TYPE_WIRED_HEADSET }
        if (wired) routeTo(CallEndpoint.TYPE_WIRED_HEADSET, CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        else routeTo(CallEndpoint.TYPE_EARPIECE, CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    }

    private fun routeTo(endpointType: Int, legacyRoute: Int) {
        val svc = service ?: return
        if (useEndpoints) {
            val target = endpoints.firstOrNull { it.endpointType == endpointType }
            if (target != null) {
                svc.requestCallEndpointChange(
                    target, svc.mainExecutor,
                    object : OutcomeReceiver<Void, CallEndpointException> {
                        override fun onResult(result: Void?) { endpoint = target }
                        override fun onError(error: CallEndpointException) { svc.setAudioRoute(legacyRoute) }
                    }
                )
                return
            }
        }
        svc.setAudioRoute(legacyRoute)
    }

    fun setMutedFromSystem(m: Boolean) { mutedNew = m }

    fun resetAudio() {
        endpoints.clear()
        endpoint = null
        mutedNew = null
        audio = null
    }
}

/** Whether one of our screens is currently visible (counted in onStart/onStop). */
object AppVisibility {
    @Volatile private var started = 0
    val foreground get() = started > 0
    fun onStart() { started++ }
    fun onStop() { started = (started - 1).coerceAtLeast(0) }
}

/** Screen off or keyguard showing — a full-screen call UI is expected then. */
fun deviceIdle(ctx: Context): Boolean {
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
    return pm?.isInteractive == false || km?.isKeyguardLocked == true
}

/** The system binds this service while calls exist, because we are the default dialer. */
class PolarisInCallService : InCallService() {
    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
    }

    override fun onDestroy() {
        CallManager.service = null
        CallNotify.cancel(this)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.add(call)
        CallNotify.update(this)
        // Open the full call screen only when it won't interrupt anything:
        //  - outgoing calls (the user just pressed "call"),
        //  - Polaris Phone is already on screen,
        //  - the phone is locked or the screen is off.
        // While a game, YouTube or any other app is in use, an incoming call shows only as the
        // heads-up notification (Answer / Decline), and tapping it opens the call screen.
        val incoming = call.details.state == Call.STATE_RINGING
        if (!incoming || AppVisibility.foreground || deviceIdle(this)) {
            runCatching { startActivity(CallActivity.intent(this)) }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallManager.remove(call)
        if (CallManager.calls.isEmpty()) {
            CallNotify.cancel(this)
            CallManager.resetAudio()
        } else CallNotify.update(this)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.audio = audioState
    }

    // Android 14+ reports routes and mute through these instead
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        CallManager.endpoint = callEndpoint
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        CallManager.endpoints.clear()
        CallManager.endpoints.addAll(availableEndpoints)
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        CallManager.setMutedFromSystem(isMuted)
    }
}

/** Ongoing / incoming call notification (CallStyle) with answer, decline and hang-up actions. */
object CallNotify {
    private const val CHANNEL_INCOMING = "incoming"
    private const val CHANNEL_ONGOING = "ongoing"
    private const val ID = 7

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "Входящие вызовы", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // Telecom plays the ringtone itself
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Текущий вызов", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
            }
        )
    }

    private fun action(ctx: Context, a: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, CallActionReceiver::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun update(ctx: Context) {
        val call = CallManager.primary ?: return cancel(ctx)
        ensureChannels(ctx)
        val number = CallManager.number(call)
        val who = PhoneData.name(ctx, number) ?: number.ifBlank { "Скрытый номер" }
        val person = Person.Builder().setName(who).setImportant(true).build()
        val open = PendingIntent.getActivity(
            ctx, 0, CallActivity.intent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ringing = CallManager.states[call] == Call.STATE_RINGING
        val b = Notification.Builder(ctx, if (ringing) CHANNEL_INCOMING else CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (ringing) {
            b.setFullScreenIntent(open, true)
                .setStyle(
                    Notification.CallStyle.forIncomingCall(
                        person, action(ctx, CallActionReceiver.DECLINE, 1), action(ctx, CallActionReceiver.ANSWER, 2)
                    )
                )
        } else {
            // CallStyle for ongoing calls needs a foreground service, so use a plain notification + action
            b.setContentTitle(who)
                .setContentText(if (CallManager.states[call] == Call.STATE_HOLDING) "На удержании" else "Текущий вызов")
                .addAction(
                    Notification.Action.Builder(null, "Завершить", action(ctx, CallActionReceiver.HANGUP, 3)).build()
                )
            call.details.connectTimeMillis.takeIf { it > 0 }?.let { b.setWhen(it).setUsesChronometer(true).setShowWhen(true) }
        }
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(ID, b.build()) }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ANSWER = "com.polaris.phone.ANSWER"
        const val DECLINE = "com.polaris.phone.DECLINE"
        const val HANGUP = "com.polaris.phone.HANGUP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val call = CallManager.primary ?: return
        when (intent.action) {
            ANSWER -> {
                CallManager.answer(call)
                // Answered from the heads-up over another app: stay in that app, the call continues
                // with the ongoing notification (tap it for the full call screen).
                if (AppVisibility.foreground || deviceIdle(ctx)) {
                    runCatching { ctx.startActivity(CallActivity.intent(ctx)) }
                }
            }
            DECLINE -> CallManager.reject(call)
            HANGUP -> CallManager.hangup(call)
        }
    }
}
