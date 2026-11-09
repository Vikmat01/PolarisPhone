package com.polaris.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

// ---------- Theme mode ----------

enum class ThemeMode(val label: String) { DARK("Чёрная"), LIGHT("Светлая"), AUTO("Авто") }

// ---------- Preferences ----------

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var mode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(p.getString("mode", null) ?: "AUTO") }.getOrDefault(ThemeMode.AUTO)
        set(v) = p.edit().putString("mode", v.name).apply()

    /** Bubble colour, stored separately for the dark and light themes. */
    fun bubble(dark: Boolean): Int =
        p.getInt(if (dark) "bubble_dark" else "bubble_light", if (dark) 0xFF2FD06B.toInt() else 0xFF17A857.toInt())

    fun setBubble(dark: Boolean, argb: Int) = p.edit().putInt(if (dark) "bubble_dark" else "bubble_light", argb).apply()

    var lat: Double?
        get() = if (p.contains("lat")) p.getFloat("lat", 0f).toDouble() else null
        set(v) { if (v != null) p.edit().putFloat("lat", v.toFloat()).apply() }
    var lon: Double?
        get() = if (p.contains("lon")) p.getFloat("lon", 0f).toDouble() else null
        set(v) { if (v != null) p.edit().putFloat("lon", v.toFloat()).apply() }
}

// ---------- Sunrise / sunset (NOAA algorithm) ----------

data class SunTimes(val sunrise: Long?, val sunset: Long?, val polarDay: Boolean = false, val polarNight: Boolean = false)

object Sun {
    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI
    private fun norm(x: Double, m: Double) = ((x % m) + m) % m

    /** UTC time of sunrise or sunset as an hour (0..24) for the given day, null if it doesn't happen. */
    private fun utcHour(dayOfYear: Int, lat: Double, lon: Double, rising: Boolean): Double? {
        val zenith = 90.833
        val lngHour = lon / 15
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(zenith)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15
        val localMean = h + ra - 0.06571 * t - 6.622
        return norm(localMean - lngHour, 24.0)
    }

    fun times(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): SunTimes {
        val local = Calendar.getInstance().apply { timeInMillis = now }
        // Midnight UTC of the local calendar day
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }
        val doy = local.get(Calendar.DAY_OF_YEAR)
        val base = utc.timeInMillis
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun toLocalDay(h: Double): Long {
            var t = base + (h * 3_600_000).toLong()
            // Bring the moment into the local calendar day
            while (t < dayStart) t += 86_400_000L
            while (t >= dayStart + 86_400_000L) t -= 86_400_000L
            return t
        }
        val r = utcHour(doy, lat, lon, true)
        val s = utcHour(doy, lat, lon, false)
        if (r == null || s == null) {
            // Polar: summer → day all the time, winter → night all the time
            val summer = (lat > 0) == (local.get(Calendar.MONTH) in Calendar.APRIL..Calendar.SEPTEMBER)
            return SunTimes(null, null, polarDay = summer, polarNight = !summer)
        }
        return SunTimes(toLocalDay(r), toLocalDay(s))
    }

    /** true if it's dark outside right now at this place. Without coordinates: 7:00–21:00 is day. */
    fun isNight(lat: Double?, lon: Double?, now: Long = System.currentTimeMillis()): Boolean {
        if (lat == null || lon == null) {
            val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
            return h < 7 || h >= 21
        }
        val t = times(lat, lon, now)
        if (t.polarDay) return false
        if (t.polarNight) return true
        val rise = t.sunrise!!
        val set = t.sunset!!
        return if (rise < set) now < rise || now >= set else now >= set && now < rise
    }
}

// ---------- Location ----------

object Geo {
    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Last known coarse location, then a single fresh fix. Saves coordinates to prefs. */
    @SuppressLint("MissingPermission")
    fun refresh(ctx: Context, prefs: Prefs, onDone: () -> Unit) {
        if (!granted(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last: Location? = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) {
            prefs.lat = last.latitude; prefs.lon = last.longitude; onDone()
        }
        val provider = providers.firstOrNull() ?: return
        runCatching {
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc ->
                if (loc != null) {
                    prefs.lat = loc.latitude; prefs.lon = loc.longitude; onDone()
                }
            }
        }
    }
}

// ---------- Colours ----------

@Immutable
data class AppColors(
    val dark: Boolean,
    val bg: Color,
    val glow1: Color,
    val glow2: Color,
    val text: Color,
    val dim: Color,
    val line: Color,
    val surface: Color,
    val incoming: Color,
    val incomingText: Color,
    val bubble: Color,
    val bubbleText: Color,
    val danger: Color
)

fun onColor(c: Color): Color = if (c.luminance() > 0.55f) Color(0xFF101014) else Color.White

fun darkColors(bubble: Color) = AppColors(
    dark = true,
    bg = Color(0xFF000000),
    glow1 = bubble.copy(alpha = 0.55f),
    glow2 = Color(0xFF2B1B5A).copy(alpha = 0.7f),
    text = Color.White,
    dim = Color(0xFF9A98A6),
    line = Color.White.copy(alpha = 0.08f),
    surface = Color.White.copy(alpha = 0.07f),
    incoming = Color.White.copy(alpha = 0.12f),
    incomingText = Color.White,
    bubble = bubble,
    bubbleText = onColor(bubble),
    danger = Color(0xFFFF5A6A)
)

fun lightColors(bubble: Color) = AppColors(
    dark = false,
    bg = Color(0xFFF3F2F7),
    glow1 = bubble.copy(alpha = 0.35f),
    glow2 = Color(0xFFFFC6E4).copy(alpha = 0.7f),
    text = Color(0xFF111116),
    dim = Color(0xFF6E6C78),
    line = Color.Black.copy(alpha = 0.07f),
    surface = Color.White.copy(alpha = 0.55f),
    incoming = Color.White.copy(alpha = 0.85f),
    incomingText = Color(0xFF111116),
    bubble = bubble,
    bubbleText = onColor(bubble),
    danger = Color(0xFFE5304A)
)

val LocalColors = staticCompositionLocalOf { darkColors(Color(0xFF7C5CFF)) }

/** Smoothly cross-fades every colour when the theme or the bubble colour changes. */
@Composable
fun animated(target: AppColors): AppColors {
    val spec = tween<Color>(600)
    val bg by animateColorAsState(target.bg, spec, label = "bg")
    val g1 by animateColorAsState(target.glow1, spec, label = "g1")
    val g2 by animateColorAsState(target.glow2, spec, label = "g2")
    val text by animateColorAsState(target.text, spec, label = "text")
    val dim by animateColorAsState(target.dim, spec, label = "dim")
    val line by animateColorAsState(target.line, spec, label = "line")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val inc by animateColorAsState(target.incoming, spec, label = "inc")
    val incT by animateColorAsState(target.incomingText, spec, label = "incT")
    val bub by animateColorAsState(target.bubble, spec, label = "bub")
    val bubT by animateColorAsState(target.bubbleText, spec, label = "bubT")
    val danger by animateColorAsState(target.danger, spec, label = "danger")
    return AppColors(target.dark, bg, g1, g2, text, dim, line, surface, inc, incT, bub, bubT, danger)
}

/** Whether the app should be dark right now, from the saved mode and the last known location. */
fun themeIsDark(prefs: Prefs, now: Long = System.currentTimeMillis()): Boolean = when (prefs.mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.AUTO -> Sun.isNight(prefs.lat, prefs.lon, now)
}

/** Colours for a given darkness using the user's accent for that theme. */
fun themeColors(prefs: Prefs, dark: Boolean): AppColors {
    val accent = Color(prefs.bubble(dark))
    return if (dark) darkColors(accent) else lightColors(accent)
}

val CallGreen = Color(0xFF2FD06B)
