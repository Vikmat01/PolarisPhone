package com.polaris.phone

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.spring
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Increments whenever the call log, contacts or blacklist change; screens reload on it. */
val LocalVersion = compositionLocalOf { 0 }

object Events {
    private val listeners = mutableSetOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    fun changed() { synchronized(listeners) { listeners.toList() }.forEach { it() } }
}

sealed interface Screen {
    data object Home : Screen
    data object Blacklist : Screen
    data object Settings : Screen
    data class EditContact(val id: Long?, val number: String? = null) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onStart() { super.onStart(); AppVisibility.onStart() }
    override fun onStop() { AppVisibility.onStop(); super.onStop() }

    private lateinit var prefs: Prefs
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    private var tab by mutableIntStateOf(0)
    private var dialNumber by mutableStateOf("")
    private var version by mutableIntStateOf(0)
    private var isDefault by mutableStateOf(false)
    private var roleFailed by mutableStateOf(false)
    private var now by mutableLongStateOf(System.currentTimeMillis())
    private val bump: () -> Unit = { runOnUiThread { version++ } }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { version++ }
    }

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = PhoneData.isDefault(this)
        if (!isDefault) roleFailed = true
        version++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        CallNotify.ensureChannels(this)
        isDefault = PhoneData.isDefault(this)
        handle(intent)
        Events.listen(bump)
        runCatching { contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer) }
        setContent { App() }
        Updater.autoCheck(this)
        requestMaxRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        requestMaxRefreshRate()
        isDefault = PhoneData.isDefault(this)
        now = System.currentTimeMillis()
        PhoneData.clearNames()
        version++
    }

    override fun onDestroy() {
        Events.unlisten(bump)
        runCatching { contentResolver.unregisterContentObserver(observer) }
        super.onDestroy()
    }

    /** "tel:" links and the dial intent from other apps open the keypad with the number filled in. */
    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW) {
            numberFrom(intent.data)?.let { dialNumber = it }
            tab = 2
            stack.clear(); stack.add(Screen.Home)
        }
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

    private fun requestRole() {
        if (PhoneData.isDefault(this)) { isDefault = true; return }
        val rm = getSystemService(RoleManager::class.java)
        if (!roleFailed && rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            runCatching { roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }
                .onSuccess { return }
        }
        roleFailed = true
        for (i in listOf(
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )) if (runCatching { startActivity(i) }.isSuccess) return
    }

    @Composable
    private fun App() {
        var mode by remember { mutableStateOf(prefs.mode) }
        var accentDark by remember { mutableIntStateOf(prefs.bubble(true)) }
        var accentLight by remember { mutableIntStateOf(prefs.bubble(false)) }
        var geo by remember { mutableStateOf(prefs.lat to prefs.lon) }
        var hasGeo by remember { mutableStateOf(Geo.granted(this)) }

        LaunchedEffect(Unit) {
            while (true) { delay(60_000); now = System.currentTimeMillis() }
        }
        val refreshGeo = { Geo.refresh(this, prefs) { geo = prefs.lat to prefs.lon } }

        val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasGeo = Geo.granted(this)
            if (hasGeo) refreshGeo()
            PhoneData.clearNames()
            version++
        }
        val geoPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            hasGeo = it
            if (it) refreshGeo()
        }
        LaunchedEffect(isDefault) {
            if (isDefault) perms.launch(
                arrayOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.WRITE_CALL_LOG,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        LaunchedEffect(hasGeo) { if (hasGeo) refreshGeo() }

        val night = Sun.isNight(geo.first, geo.second, now)
        val dark = when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.AUTO -> night
        }
        val colors = animated(if (dark) darkColors(Color(accentDark)) else lightColors(Color(accentLight)))

        LaunchedEffect(dark) {
            val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }

        CompositionLocalProvider(LocalColors provides colors, LocalVersion provides version) {
            Box(Modifier.fillMaxSize().background(colors.bg)) {
                if (!isDefault) {
                    WelcomeScreen(roleFailed, onMakeDefault = ::requestRole)
                    return@Box
                }
                BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
                val current = stack.last()
                val depth = stack.size
                AnimatedContent(
                    targetState = current to depth,
                    transitionSpec = {
                        val forward = targetState.second >= initialState.second
                        val dur = 420
                        if (forward) {
                            (slideInHorizontally(tween(dur)) { it / 3 } + fadeIn(tween(dur)) + scaleIn(tween(dur), 0.96f)) togetherWith
                                (slideOutHorizontally(tween(dur)) { -it / 6 } + fadeOut(tween(dur / 2)) + scaleOut(tween(dur), 0.98f))
                        } else {
                            (slideInHorizontally(tween(dur)) { -it / 6 } + fadeIn(tween(dur)) + scaleIn(tween(dur), 0.98f)) togetherWith
                                (slideOutHorizontally(tween(dur)) { it / 3 } + fadeOut(tween(dur / 2)) + scaleOut(tween(dur), 0.96f))
                        }
                    },
                    label = "nav"
                ) { (screen, _) ->
                    when (screen) {
                        Screen.Home -> HomeScreen()
                        Screen.Blacklist -> BlacklistScreen(onBack = ::pop)
                        is Screen.EditContact -> ContactEditorScreen(screen.id, screen.number, onDone = ::pop)
                        Screen.Settings -> SettingsScreen(
                            mode = mode,
                            onMode = { mode = it; prefs.mode = it },
                            accentDark = Color(accentDark),
                            accentLight = Color(accentLight),
                            onAccent = { d, col ->
                                if (d) accentDark = col.toArgb() else accentLight = col.toArgb()
                                prefs.setBubble(d, col.toArgb())
                            },
                            lat = geo.first, lon = geo.second, hasGeo = hasGeo,
                            onRequestGeo = {
                                if (hasGeo) refreshGeo() else geoPerm.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            },
                            onClearLog = {
                                PhoneData.clearLog(this@MainActivity)
                                Events.changed()
                            },
                            onBack = ::pop
                        )
                    }
                }
            }
        }
    }

    /** Recents / Contacts / Keypad with a floating glass header and a liquid-glass tab switch. */
    @Composable
    private fun HomeScreen() {
        val c = LocalColors.current
        val ctx = LocalContext.current
        val version = LocalVersion.current
        val backdrop = rememberLayerBackdrop()
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        var query by remember { mutableStateOf("") }
        var recentMenu by remember { mutableStateOf<CallEntry?>(null) }
        var contactOpen by remember { mutableStateOf<Contact?>(null) }
        val contacts by produceState<List<Contact>?>(null, version) {
            value = withContext(Dispatchers.IO) { PhoneData.contacts(ctx) }
        }
        val blocked by produceState(emptySet<String>(), version) {
            value = withContext(Dispatchers.IO) { PhoneData.blocked(ctx).toSet() }
        }

        Box(Modifier.fillMaxSize().imePadding()) {
            // Everything captured here is what the glass refracts
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                LivingBackground()
                // Shared-axis switch: the old tab is gone (fast fade) before the new one fades in,
                // both drift a little in the direction of travel — no double exposure.
                AnimatedContent(
                    tab,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(230, delayMillis = 90, easing = LinearOutSlowInEasing)) +
                            slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { dir * it / 10 }) togetherWith
                            (fadeOut(tween(90, easing = FastOutLinearInEasing)) +
                                slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -dir * it / 10 })
                    },
                    label = "tab"
                ) { t ->
                    // Each tab keeps its own top inset, so nothing jumps while it animates out
                    when (t) {
                        0 -> RecentsList(top + 72.dp) { recentMenu = it }
                        1 -> ContactsList(top + 136.dp, contacts, query) { contactOpen = it }
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }

            // Keypad lives outside the captured layer so its keys are real glass
            AnimatedVisibility(
                tab == 2,
                enter = fadeIn(tween(230, delayMillis = 90, easing = LinearOutSlowInEasing)) +
                    slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it / 14 },
                exit = fadeOut(tween(90, easing = FastOutLinearInEasing)) +
                    slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { it / 14 }
            ) {
                Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.navigationBarsPadding()) {
                        DialpadTab(
                            backdrop, dialNumber, { dialNumber = it }, contacts ?: emptyList(),
                            onAddContact = { n -> stack.add(Screen.EditContact(null, n)) }
                        )
                    }
                }
            }

            // Header: blacklist + settings, and the contacts search field
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Title sits on a frosted glass pill, so the list scrolling underneath never shows through it
                    Box(
                        Modifier.weight(1f).height(44.dp).frostedGlass(backdrop, Pill).padding(horizontal = 18.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        AnimatedContent(
                            when (tab) { 0 -> "Недавние"; 1 -> "Контакты"; else -> "Клавиатура" },
                            transitionSpec = {
                                (fadeIn(tween(220, delayMillis = 80)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 2 }) togetherWith
                                    (fadeOut(tween(90)) + slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { -it / 2 })
                            },
                            modifier = Modifier.clipToBounds(),
                            label = "title"
                        ) { title ->
                            Text(title, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    // The "add contact" button grows/shrinks its width, so the title pill next to it
                    // narrows and widens smoothly instead of snapping in one frame.
                    androidx.compose.animation.AnimatedVisibility(
                        tab == 1,
                        enter = expandHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f), expandFrom = Alignment.Start) +
                            fadeIn(tween(220, delayMillis = 60)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 380f), initialScale = 0.6f),
                        exit = shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 420f), shrinkTowards = Alignment.Start) +
                            fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.6f)
                    ) {
                        Row {
                            GlassIconButton(backdrop, onClick = { stack.add(Screen.EditContact(null)) }) {
                                Icon(Icons.Filled.PersonAdd, "Новый контакт", tint = c.text)
                            }
                            Spacer(Modifier.size(10.dp))
                        }
                    }
                    GlassIconButton(backdrop, onClick = { stack.add(Screen.Blacklist) }) {
                        Icon(Icons.Filled.Block, "Чёрный список", tint = c.text)
                    }
                    Spacer(Modifier.size(10.dp))
                    GlassIconButton(backdrop, onClick = { stack.add(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, "Настройки", tint = c.text)
                    }
                }
                AnimatedVisibility(
                    tab == 1,
                    enter = fadeIn(tween(230, delayMillis = 90)) + expandVertically(tween(320, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(90)) + shrinkVertically(tween(280, easing = FastOutSlowInEasing))
                ) {
                    Box(
                        Modifier.padding(top = 10.dp).fillMaxWidth()
                            .frostedGlass(backdrop, Pill)
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        if (query.isEmpty()) Text("Поиск по имени или номеру", color = c.dim, fontSize = 16.sp)
                        BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = TextStyle(color = c.text, fontSize = 16.sp),
                            cursorBrush = SolidColor(c.bubble),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // A call is in progress — glass banner to return to it
            AnimatedVisibility(
                CallManager.calls.isNotEmpty(),
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it }
            ) {
                Box(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                    GlassButton(backdrop, "Вернуться к вызову", accent = true) {
                        runCatching { ctx.startActivity(CallActivity.intent(ctx)) }
                    }
                }
            }

            // Bottom tab switch — the same draggable glass lens as the theme switch
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Segmented(backdrop, listOf("Недавние", "Контакты", "Клавиатура"), tab) { tab = it }
            }

            UpdateSheet(backdrop)

            val r = recentMenu
            GlassSheet(r != null, backdrop, onDismiss = { recentMenu = null }) {
                if (r != null) RecentSheet(
                    r, blocked.any { numbersMatch(it, r.number) },
                    onEdit = { id, n -> recentMenu = null; stack.add(Screen.EditContact(id, n)) }
                ) { recentMenu = null }
            }
            val ct = contactOpen
            GlassSheet(ct != null, backdrop, onDismiss = { contactOpen = null }) {
                if (ct != null) ContactSheet(
                    backdrop, ct, blocked,
                    onEdit = { contactOpen = null; stack.add(Screen.EditContact(ct.id)) }
                ) { contactOpen = null }
            }
        }
    }

    private fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}
