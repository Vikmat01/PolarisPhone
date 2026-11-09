package com.polaris.phone

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================
// Welcome — become the default phone app
// =====================================================================

@Composable
fun WelcomeScreen(roleFailed: Boolean, onMakeDefault: () -> Unit) {
    val c = LocalColors.current
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        LivingBackground(Modifier.layerBackdrop(backdrop))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.fillMaxWidth().themedGlass(backdrop, Card).padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Polaris Phone", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Чтобы звонить, принимать вызовы и вести чёрный список, сделайте приложение основным для звонков.",
                    color = c.dim, fontSize = 15.sp, textAlign = TextAlign.Center
                )
                AnimatedVisibility(roleFailed) {
                    Text(
                        "Система не показала выбор. Откройте «Приложения по умолчанию» → «Телефон» и выберите Polaris Phone, затем вернитесь сюда.",
                        color = c.text, fontSize = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                }
                Spacer(Modifier.height(22.dp))
                GlassButton(
                    backdrop, if (roleFailed) "Открыть настройки" else "Сделать основным",
                    Modifier.fillMaxWidth(), accent = true, onClick = onMakeDefault
                )
            }
        }
    }
}

// =====================================================================
// Recents
// =====================================================================

@Composable
fun RecentsList(top: androidx.compose.ui.unit.Dp, onMenu: (CallEntry) -> Unit) {
    val c = LocalColors.current
    val haptics = rememberHaptics()
    val ctx = LocalContext.current
    val version = LocalVersion.current
    val list by produceState<List<CallEntry>?>(null, version) {
        value = withContext(Dispatchers.IO) { PhoneData.recents(ctx) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top, bottom = 130.dp)) {
        if (list?.isEmpty() == true) item(key = "empty") { Empty("Вызовов пока не было") }
        items(list ?: emptyList(), key = { it.id }) { e ->
            val src = remember { MutableInteractionSource() }
            val missed = e.kind == CallKind.MISSED || e.kind == CallKind.REJECTED || e.kind == CallKind.BLOCKED
            Row(
                Modifier.fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(350), fadeOutSpec = tween(250),
                        placementSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)
                    )
                    .pressScale(src, 0.03f)
                    .combinedClickable(
                        interactionSource = src, indication = null,
                        onLongClick = { haptics.long(); onMenu(e) },
                        onClick = { haptics.press(); PhoneData.call(ctx, e.number) }
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(e.title, e.number, lookup = true)
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        e.title + if (e.count > 1) " (${e.count})" else "",
                        color = if (missed) c.danger else c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(e.kind), null, tint = if (missed) c.danger else c.dim, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(4.dp))
                        val extra = if (e.kind == CallKind.INCOMING || e.kind == CallKind.OUTGOING) {
                            if (e.duration > 0) " · ${fmtDuration(e.duration)}" else ""
                        } else ""
                        Text(kindLabel(e.kind) + extra, color = c.dim, fontSize = 13.sp)
                    }
                }
                Text(fmtListTime(e.date), color = c.dim, fontSize = 12.sp)
                Spacer(Modifier.size(8.dp))
                MenuDots { onMenu(e) }
            }
        }
    }
}

fun kindIcon(k: CallKind): ImageVector = when (k) {
    CallKind.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
    CallKind.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
    CallKind.BLOCKED -> Icons.Filled.Block
    else -> Icons.AutoMirrored.Filled.CallMissed
}

@Composable
private fun MenuDots(onClick: () -> Unit) {
    val c = LocalColors.current
    val press = rememberPress()
    Box(
        Modifier.size(34.dp).pressScale(press, 0.15f).background(c.surface, CircleShape).pressable(press, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text("i", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun BigTitle(text: String) {
    val c = LocalColors.current
    Text(
        text, color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 22.dp, bottom = 10.dp)
    )
}

@Composable
private fun Empty(text: String) {
    val c = LocalColors.current
    Text(
        text, color = c.dim, fontSize = 15.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp)
    )
}

// =====================================================================
// Contacts
// =====================================================================

@Composable
fun ContactsList(top: androidx.compose.ui.unit.Dp, contacts: List<Contact>?, query: String, onOpen: (Contact) -> Unit) {
    val c = LocalColors.current
    val filtered = remember(contacts, query) {
        val q = query.trim()
        val digits = q.filter { it.isDigit() }
        (contacts ?: emptyList()).filter { ct ->
            q.isEmpty() || ct.name.contains(q, ignoreCase = true) ||
                (digits.length >= 2 && ct.numbers.any { it.number.filter { d -> d.isDigit() }.contains(digits) })
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top, bottom = 130.dp)) {
        if (contacts != null && filtered.isEmpty()) item(key = "empty") {
            Empty(if (query.isBlank()) "Контактов нет" else "Ничего не найдено")
        }
        var lastLetter: Char? = null
        filtered.forEach { ct ->
            val letter = ct.name.firstOrNull()?.uppercaseChar() ?: '#'
            if (letter != lastLetter) {
                lastLetter = letter
                item(key = "h$letter${ct.id}") {
                    Text(
                        letter.toString(), color = c.bubble, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp).animateItem()
                    )
                }
            }
            item(key = ct.id) {
                val press = rememberPress()
                Row(
                    Modifier.fillMaxWidth().animateItem().pressScale(press, 0.03f)
                        .pressable(press) { onOpen(ct) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(ct.name, ct.numbers.firstOrNull()?.number ?: ct.name, 44.dp, photo = ct.photo)
                    Spacer(Modifier.size(14.dp))
                    Text(ct.name, color = c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Bottom-sheet content for a contact: every number with call and SMS buttons. */
@Composable
fun ContactSheet(backdrop: Backdrop, contact: Contact, blocked: Set<String>, onEdit: () -> Unit, onDone: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(contact.name, contact.numbers.firstOrNull()?.number ?: contact.name, 56.dp, photo = contact.photo)
        Spacer(Modifier.size(14.dp))
        Text(contact.name, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(14.dp))
    SheetAction("Изменить контакт") { onEdit() }
    contact.numbers.forEach { n ->
        val isBlocked = blocked.any { numbersMatch(it, n.number) }
        Row(
            Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(n.number, color = c.text, fontSize = 16.sp)
                Text(if (isBlocked) "${n.label} · в чёрном списке" else n.label, color = if (isBlocked) c.danger else c.dim, fontSize = 12.sp)
            }
            SmallRound(backdrop, Icons.Filled.Sms, c.bubble) { PhoneData.sms(ctx, n.number); onDone() }
            Spacer(Modifier.size(8.dp))
            SmallRound(backdrop, Icons.Filled.Call, CallGreen) { PhoneData.call(ctx, n.number); onDone() }
        }
        Spacer(Modifier.height(8.dp))
        SheetAction(if (isBlocked) "Убрать ${n.number} из чёрного списка" else "Заблокировать ${n.number}", danger = !isBlocked) {
            scope.launch(Dispatchers.IO) {
                if (isBlocked) PhoneData.unblock(ctx, n.number) else PhoneData.block(ctx, n.number)
                Events.changed()
            }
            onDone()
        }
    }
}

@Composable
fun SmallRound(backdrop: Backdrop, icon: ImageVector, color: Color, onClick: () -> Unit) {
    val press = rememberPress()
    Box(
        Modifier.size(42.dp)
            .liquidGlass(backdrop, Pill, color.copy(alpha = 0.85f), 8.dp, 16.dp, press = press)
            .pressable(press, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

/** Bottom-sheet content for a recents row. */
@Composable
fun RecentSheet(entry: CallEntry, blocked: Boolean, onEdit: (Long?, String) -> Unit, onDone: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clock = remember { SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru")) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(entry.title, entry.number, 56.dp, lookup = true)
        Spacer(Modifier.size(14.dp))
        Column {
            Text(entry.title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (entry.name != null) Text(entry.number, color = c.dim, fontSize = 14.sp)
            Text("${kindLabel(entry.kind)} · ${clock.format(Date(entry.date))}", color = c.dim, fontSize = 13.sp)
        }
    }
    Spacer(Modifier.height(14.dp))
    if (entry.number.isNotBlank()) {
        SheetAction("Позвонить") { PhoneData.call(ctx, entry.number); onDone() }
        SheetAction("Написать SMS") { PhoneData.sms(ctx, entry.number); onDone() }
        val contactId = remember(entry.number) { PhoneData.contactIdFor(ctx, entry.number) }
        SheetAction(if (contactId != null) "Изменить контакт" else "Создать контакт") {
            onEdit(contactId, entry.number)
        }
        SheetAction("Копировать номер") {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("номер", entry.number))
            onDone()
        }
        SheetAction(if (blocked) "Убрать из чёрного списка" else "Добавить в чёрный список", danger = !blocked) {
            scope.launch(Dispatchers.IO) {
                if (blocked) PhoneData.unblock(ctx, entry.number) else PhoneData.block(ctx, entry.number)
                Events.changed()
            }
            onDone()
        }
    }
    SheetAction("Удалить из журнала", danger = true) {
        scope.launch(Dispatchers.IO) { PhoneData.deleteLog(ctx, entry); Events.changed() }
        onDone()
    }
}

// =====================================================================
// Blacklist
// =====================================================================

@Composable
fun BlacklistScreen(onBack: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val version = LocalVersion.current
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    var number by rememberSaveable { mutableStateOf("") }
    val list by produceState<List<String>?>(null, version) {
        value = withContext(Dispatchers.IO) { PhoneData.blocked(ctx) }
    }
    val canBlock = remember(version) { PhoneData.canBlock(ctx) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().imePadding()) {
        LivingBackground(Modifier.layerBackdrop(backdrop))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = top + 76.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!canBlock) item(key = "warn") {
                Text(
                    "Чёрный список доступен, только когда приложение выбрано основным для звонков.",
                    color = c.danger, fontSize = 14.sp, modifier = Modifier.padding(8.dp)
                )
            }
            if (list?.isEmpty() == true) item(key = "empty") {
                Text(
                    "Список пуст.\nДобавьте номер внизу или через меню вызова в «Недавних».\nОн общий с Polaris SMS: заблокированный номер не сможет ни звонить, ни писать.",
                    color = c.dim, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp).animateItem()
                )
            }
            items(list?.distinct() ?: emptyList(), key = { it }) { n ->
                val name = remember(n) { PhoneData.name(ctx, n) }
                Row(
                    Modifier.fillMaxWidth()
                        .animateItem(
                            fadeInSpec = tween(300), fadeOutSpec = tween(250),
                            placementSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)
                        )
                        .themedGlass(backdrop, RoundedCornerShape(24.dp), lensHeight = 12.dp, lensAmount = 24.dp)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(name ?: n, n, 42.dp, lookup = true)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name ?: n, color = c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (name != null) Text(n, color = c.dim, fontSize = 13.sp)
                    }
                    GlassButton(backdrop, "Убрать") {
                        scope.launch(Dispatchers.IO) { PhoneData.unblock(ctx, n); Events.changed() }
                    }
                }
            }
        }

        GlassHeader(backdrop, "Чёрный список", subtitle = list?.let { "Номеров: ${it.size}" }, onBack = onBack)

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.weight(1f).themedGlass(backdrop, Pill, lensHeight = 10.dp, lensAmount = 20.dp)
                    .padding(horizontal = 20.dp, vertical = 15.dp)
            ) {
                if (number.isEmpty()) Text("Номер телефона", color = c.dim, fontSize = 16.sp)
                BasicTextField(
                    number, { number = it }, singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(c.bubble),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AnimatedVisibility(number.count { it.isDigit() } >= 3, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                val press = rememberPress()
                Box(
                    Modifier.padding(start = 8.dp).size(50.dp)
                        .liquidGlass(backdrop, Pill, c.danger.copy(alpha = 0.85f), 10.dp, 20.dp, press = press)
                        .pressable(press) {
                            val n = number.trim()
                            number = ""
                            scope.launch(Dispatchers.IO) { PhoneData.block(ctx, n); Events.changed() }
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Add, "Заблокировать", tint = Color.White) }
            }
        }
    }
}

// =====================================================================
// Settings — theme & accent colours
// =====================================================================

@Composable
fun SettingsScreen(
    mode: ThemeMode,
    onMode: (ThemeMode) -> Unit,
    accentDark: Color,
    accentLight: Color,
    onAccent: (Boolean, Color) -> Unit,
    lat: Double?,
    lon: Double?,
    hasGeo: Boolean,
    onRequestGeo: () -> Unit,
    onClearLog: () -> Unit,
    onBack: () -> Unit
) {
    val c = LocalColors.current
    val backdrop = rememberLayerBackdrop()
    var editing by remember { mutableStateOf<Boolean?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val clock = remember { SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")) }

    Box(Modifier.fillMaxSize()) {
        LivingBackground(Modifier.layerBackdrop(backdrop))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = top + 76.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().themedGlass(backdrop, Card).padding(18.dp)
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            ) {
                Text("Тема", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Segmented(backdrop, ThemeMode.entries.map { it.label }, mode.ordinal) { onMode(ThemeMode.entries[it]) }
                AnimatedVisibility(mode == ThemeMode.AUTO) {
                    Column(Modifier.padding(top = 14.dp)) {
                        if (lat != null && lon != null) {
                            val t = Sun.times(lat, lon)
                            val line = when {
                                t.polarDay -> "Сейчас полярный день — светлая тема весь день"
                                t.polarNight -> "Сейчас полярная ночь — тёмная тема весь день"
                                else -> "Восход ${clock.format(Date(t.sunrise!!))} · закат ${clock.format(Date(t.sunset!!))}"
                            }
                            Text(line, color = c.text, fontSize = 15.sp)
                            Text("Светлая днём, чёрная после заката", color = c.dim, fontSize = 13.sp)
                        } else {
                            Text(
                                if (hasGeo) "Определяю местоположение…" else "Нет доступа к геопозиции — пока переключаю в 7:00 и 21:00",
                                color = c.dim, fontSize = 14.sp
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        GlassButton(backdrop, if (hasGeo) "Обновить геопозицию" else "Разрешить геопозицию", onClick = onRequestGeo)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().themedGlass(backdrop, Card).padding(18.dp)) {
                Text("Цвет акцента", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Кнопки, переключатели и подсветка — для каждой темы свой", color = c.dim, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                ColorRow("В чёрной теме", accentDark) { editing = true }
                Spacer(Modifier.height(8.dp))
                ColorRow("В светлой теме", accentLight) { editing = false }
            }

            Column(Modifier.fillMaxWidth().themedGlass(backdrop, Card).padding(18.dp)) {
                Text("Журнал вызовов", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                GlassButton(backdrop, if (confirmClear) "Точно очистить? Нажмите ещё раз" else "Очистить журнал", Modifier.fillMaxWidth()) {
                    if (confirmClear) { onClearLog(); confirmClear = false } else confirmClear = true
                }
            }

            AboutCard(backdrop, "Polaris Phone")
        }

        GlassHeader(backdrop, "Настройки", onBack = onBack)
        UpdateSheet(backdrop)

        val e = editing
        GlassSheet(e != null, backdrop, onDismiss = { editing = null }) {
            if (e != null) {
                Text(
                    if (e) "Цвет в чёрной теме" else "Цвет в светлой теме",
                    color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(16.dp))
                androidx.compose.runtime.key(e) {
                    ColorWheel(if (e) accentDark else accentLight) { onAccent(e, it) }
                }
                Spacer(Modifier.height(16.dp))
                GlassButton(backdrop, "Готово", Modifier.fillMaxWidth(), accent = true) { editing = null }
            }
        }
    }
}

@Composable
private fun ColorRow(title: String, color: Color, onClick: () -> Unit) {
    val c = LocalColors.current
    val press = rememberPress()
    Row(
        Modifier.fillMaxWidth().pressScale(press, 0.03f)
            .background(c.surface, RoundedCornerShape(18.dp))
            .pressable(press, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(28.dp).background(color, CircleShape))
    }
}
