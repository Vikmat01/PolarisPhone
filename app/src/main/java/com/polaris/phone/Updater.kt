package com.polaris.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Self-update from GitHub Releases (same scheme as Polaris Browser): the latest release's tag
 * is the version (v0.2 → 0.2), its .apk asset is downloaded and handed to the system installer.
 * The APK must be signed with the same key, otherwise Android refuses to install it over the app.
 */
object Updater {
    const val REPO = "Vikmat01/PolarisPhone"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val PAGE = "https://github.com/$REPO"
    private const val ACTION_DONE = "com.polaris.phone.INSTALL_DONE"
    private const val UA = "PolarisPhone"

    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    /** Release offered to the user right now (drives the update sheet). */
    var offer by mutableStateOf<Release?>(null)
    /** Download progress 0..100 while downloading, null otherwise. */
    var progress by mutableStateOf<Int?>(null)
    var failed by mutableStateOf(false)

    fun current(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "0"

    /** 1 if a > b, -1 if a < b, 0 if equal ("0.10" > "0.9"). */
    fun compare(a: String, b: String): Int {
        val x = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun fetchLatest(): Release? = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", UA)
        if (c.responseCode != 200) return@runCatching null
        val o = JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8))
        val assets = o.optJSONArray("assets") ?: return@runCatching null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true)) {
                return@runCatching Release(
                    o.optString("tag_name").removePrefix("v"), o.optString("body"),
                    a.optString("browser_download_url"), a.optLong("size")
                )
            }
        }
        null
    }.getOrNull()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** Background check on launch, at most every 12 hours; shows the sheet only when there's something new. */
    fun autoCheck(ctx: Context) {
        val p = prefs(ctx)
        if (System.currentTimeMillis() - p.getLong("checked", 0) < 12 * 3600_000L) return
        thread {
            val r = fetchLatest()
            p.edit().putLong("checked", System.currentTimeMillis()).apply()
            if (r != null && compare(r.version, current(ctx)) > 0 && p.getString("skip", "") != r.version) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { failed = false; offer = r }
            }
        }
    }

    /** Manual check from Settings: always reports the result. */
    fun manualCheck(ctx: Context) {
        Toast.makeText(ctx, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
        thread {
            val r = fetchLatest()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                when {
                    r == null -> Toast.makeText(ctx, "Не удалось связаться с GitHub", Toast.LENGTH_SHORT).show()
                    compare(r.version, current(ctx)) > 0 -> { failed = false; offer = r }
                    else -> Toast.makeText(ctx, "У вас последняя версия ${current(ctx)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun later(ctx: Context) {
        offer?.let { prefs(ctx).edit().putString("skip", it.version).apply() }
        offer = null
    }

    fun update(ctx: Context) {
        val r = offer ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, "Разрешите установку приложений и нажмите «Обновить» ещё раз", Toast.LENGTH_LONG).show()
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        failed = false
        progress = 0
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        thread {
            val out = File(ctx.cacheDir, "update.apk")
            val ok = runCatching {
                val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", UA)
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                c.inputStream.use { inp ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024); var got = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n <= 0) break
                            o.write(buf, 0, n); got += n
                            if (total > 0) {
                                val pct = (got * 100 / total).toInt()
                                if (pct != last) { last = pct; main.post { progress = pct } }
                            }
                        }
                    }
                }
                out.length() > 0
            }.getOrDefault(false)
            main.post {
                if (!ok) { progress = null; failed = true; return@post }
                progress = 100
                install(ctx.applicationContext, out)
                offer = null
                progress = null
            }
        }
    }

    private fun install(app: Context, apk: File) {
        runCatching {
            val pi = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { session ->
                session.openWrite("update.apk", 0, apk.length()).use { o ->
                    apk.inputStream().use { it.copyTo(o) }; session.fsync(o)
                }
                app.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { c.startActivity(confirm) }
                            }
                            PackageInstaller.STATUS_SUCCESS -> runCatching { app.unregisterReceiver(this) }
                            else -> {
                                runCatching { app.unregisterReceiver(this) }
                                Toast.makeText(
                                    c, "Установка не удалась: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }, IntentFilter(ACTION_DONE), Context.RECEIVER_NOT_EXPORTED)
                val pending = PendingIntent.getBroadcast(
                    app, id, Intent(ACTION_DONE).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
        }.onFailure { Toast.makeText(app, "Установка не удалась: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}

/** Glass sheet offering an update: what's new, size, progress bar, "Позже" / "Обновить". */
@Composable
fun UpdateSheet(backdrop: Backdrop) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val r = Updater.offer
    GlassSheet(r != null, backdrop, onDismiss = { if (Updater.progress == null) Updater.later(ctx) }) {
        if (r != null) {
            Text("Доступна версия ${r.version}", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            val size = if (r.size > 0) " · " + android.text.format.Formatter.formatShortFileSize(ctx, r.size) else ""
            Text("Сейчас установлена ${Updater.current(ctx)}$size", color = c.dim, fontSize = 13.sp)
            if (r.notes.isNotBlank()) {
                Text(
                    r.notes.replace(Regex("[#*`>]+ ?"), "").trim(), color = c.text, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                )
            }
            val p = Updater.progress
            AnimatedVisibility(p != null) {
                val f by animateFloatAsState((p ?: 0) / 100f, label = "dl")
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(6.dp).background(c.surface, Pill)) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(c.bubble, Pill))
                }
            }
            if (Updater.failed) Text("Не удалось скачать обновление", color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.size(14.dp))
            Row {
                GlassButton(backdrop, "Позже", Modifier.weight(1f)) { Updater.later(ctx) }
                Spacer(Modifier.size(10.dp))
                GlassButton(
                    backdrop,
                    when { p != null -> "Загрузка $p%"; Updater.failed -> "Повторить"; else -> "Обновить" },
                    Modifier.weight(1f), accent = true
                ) { if (Updater.progress == null) Updater.update(ctx) }
            }
        }
    }
}

/** "О приложении" card for Settings: version, manual check, project page. */
@Composable
fun AboutCard(backdrop: Backdrop, appName: String) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().themedGlass(backdrop, Card).padding(18.dp)
    ) {
        Text("О приложении", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("$appName ${Updater.current(ctx)}", color = c.dim, fontSize = 13.sp)
        Spacer(Modifier.size(12.dp))
        GlassButton(backdrop, "Проверить обновления", Modifier.fillMaxWidth()) { Updater.manualCheck(ctx) }
        Spacer(Modifier.size(8.dp))
        GlassButton(backdrop, "Страница проекта на GitHub", Modifier.fillMaxWidth()) {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
