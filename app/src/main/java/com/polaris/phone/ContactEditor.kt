package com.polaris.phone

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

// =====================================================================
// Data: read / write contacts in the system address book
// =====================================================================

/** Phone types offered in the editor. */
val PhoneTypes = listOf(
    Phone.TYPE_MOBILE to "Мобильный",
    Phone.TYPE_HOME to "Домашний",
    Phone.TYPE_WORK to "Рабочий",
    Phone.TYPE_OTHER to "Другой"
)

class EditPhone(number: String, type: Int) {
    var number by mutableStateOf(number)
    var type by mutableStateOf(type)
}

data class ContactDraft(
    val id: Long?,
    val name: String,
    val phones: List<Pair<String, Int>>,
    val photo: String?
)

object ContactStore {
    fun canWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun load(ctx: Context, id: Long): ContactDraft? = runCatching {
        var name = ""
        var photo: String? = null
        ctx.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_URI),
            "${ContactsContract.Contacts._ID}=?", arrayOf(id.toString()), null
        )?.use { if (it.moveToFirst()) { name = it.getString(0) ?: ""; photo = it.getString(1) } }
        val phones = ArrayList<Pair<String, Int>>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.NUMBER, Phone.TYPE),
            "${Phone.CONTACT_ID}=?", arrayOf(id.toString()), null
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { phones += it to c.getInt(1) } }
        ContactDraft(id, name, phones, photo)
    }.getOrNull()

    // ---- Which accounts we may write to ----
    // A contact can be stitched together from several "raw contacts": phone memory, Google,
    // SIM, and read-only ones that messengers (Telegram, WhatsApp…) attach. Writing into a
    // messenger's raw contact fails, so we always edit/create in an account that allows it.

    private data class Account(val type: String?, val name: String?)

    /** Account types whose sync adapter is read-only (messengers etc.). */
    private fun readOnlyTypes(ctx: Context): Set<String> =
        android.content.ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && !it.supportsUploading() }
            .map { it.accountType }.toSet()

    private fun writable(type: String?, readOnly: Set<String>): Boolean {
        if (type == null) return true                       // plain phone memory
        if (type in readOnly) return false                  // messenger / read-only adapter
        if ("sim" in type.lowercase()) return false         // SIM: tiny limits, often refuses photos
        return true
    }

    /** First writable raw contact of this contact, or null if all are read-only. */
    private fun writableRawId(ctx: Context, contactId: Long): Long? {
        val ro = readOnlyTypes(ctx)
        ctx.contentResolver.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()), null
        )?.use { c -> while (c.moveToNext()) if (writable(c.getString(1), ro)) return c.getLong(0) }
        return null
    }

    private fun anyRawId(ctx: Context, contactId: Long): Long? =
        ctx.contentResolver.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()), null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    /**
     * Where to create new contacts, best first: the phone's own local account that the
     * address book already uses (Honor/Huawei/Xiaomi have their own), then "no account",
     * then a Google account.
     */
    private fun newContactAccounts(ctx: Context): List<Account> {
        val ro = readOnlyTypes(ctx)
        val seen = LinkedHashMap<Account, Int>()
        ctx.contentResolver.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.DELETED}=0", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val acc = Account(c.getString(0), c.getString(1))
                if (writable(acc.type, ro)) seen[acc] = (seen[acc] ?: 0) + 1
            }
        }
        val local = seen.keys.filter { a ->
            val t = a.type?.lowercase() ?: return@filter false
            "phone" in t || "local" in t || "device" in t
        }
        val google = seen.keys.filter { it.type == "com.google" }
        val others = seen.keys.filter { it !in local && it !in google && it.type != null }
        return (local + listOf(Account(null, null)) + google + others).distinct()
    }

    /**
     * Saves a new or existing contact. [photoBytes] = new photo (JPEG); [removePhoto] clears it.
     * Returns null on success, or a short reason on failure.
     */
    fun save(
        ctx: Context,
        id: Long?,
        name: String,
        phones: List<Pair<String, Int>>,
        photoBytes: ByteArray?,
        removePhoto: Boolean
    ): String? {
        val raw = id?.let { runCatching { writableRawId(ctx, it) }.getOrNull() }
        if (id != null && raw != null) {
            return runCatching { ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, editOps(raw, name, phones, photoBytes, removePhoto)) }
                .exceptionOrNull()?.let { reason(it) }
        }
        // New contact — or an existing one made only of read-only parts (e.g. only from a messenger):
        // create a writable copy and, for the existing one, glue it to the original.
        val linkTo = id?.let { runCatching { anyRawId(ctx, it) }.getOrNull() }
        var lastError: Throwable? = null
        for (acc in runCatching { newContactAccounts(ctx) }.getOrDefault(listOf(Account(null, null)))) {
            val ops = insertOps(acc, name, phones, photoBytes)
            val result = runCatching { ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }
            val newRaw = result.getOrNull()?.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
            if (result.isSuccess && newRaw != null) {
                if (linkTo != null) runCatching {
                    ctx.contentResolver.applyBatch(
                        ContactsContract.AUTHORITY,
                        arrayListOf(
                            ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                                .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, linkTo)
                                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, newRaw)
                                .build()
                        )
                    )
                }
                return null
            }
            lastError = result.exceptionOrNull()
        }
        return lastError?.let { reason(it) } ?: "Не удалось сохранить контакт"
    }

    private fun reason(e: Throwable): String = when (e) {
        is SecurityException -> "Нет разрешения на изменение контактов"
        else -> "Не удалось сохранить контакт" + (e.message?.take(80)?.let { ": $it" } ?: "")
    }

    private fun insertOps(acc: Account, name: String, phones: List<Pair<String, Int>>, photo: ByteArray?): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, acc.type)
            .withValue(RawContacts.ACCOUNT_NAME, acc.name)
            .build()
        fun data(mime: String) = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, 0)
            .withValue(Data.MIMETYPE, mime)
        ops += data(StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build()
        phones.forEach { (n, t) ->
            ops += data(Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, n).withValue(Phone.TYPE, t).build()
        }
        photo?.let { ops += data(Photo.CONTENT_ITEM_TYPE).withValue(Photo.PHOTO, it).build() }
        return ops
    }

    private fun editOps(raw: Long, name: String, phones: List<Pair<String, Int>>, photo: ByteArray?, removePhoto: Boolean): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        val where = "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?"
        fun args(mime: String) = arrayOf(raw.toString(), mime)
        fun insert(mime: String) = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, raw)
            .withValue(Data.MIMETYPE, mime)
        ops += ContentProviderOperation.newDelete(Data.CONTENT_URI).withSelection(where, args(StructuredName.CONTENT_ITEM_TYPE)).build()
        ops += insert(StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build()
        ops += ContentProviderOperation.newDelete(Data.CONTENT_URI).withSelection(where, args(Phone.CONTENT_ITEM_TYPE)).build()
        phones.forEach { (n, t) ->
            ops += insert(Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, n).withValue(Phone.TYPE, t).build()
        }
        if (photo != null || removePhoto) {
            ops += ContentProviderOperation.newDelete(Data.CONTENT_URI).withSelection(where, args(Photo.CONTENT_ITEM_TYPE)).build()
        }
        photo?.let { ops += insert(Photo.CONTENT_ITEM_TYPE).withValue(Photo.PHOTO, it).build() }
        return ops
    }

    fun delete(ctx: Context, id: Long): Boolean = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id.toString())
        ctx.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    /** Picked image → square-ish JPEG no larger than 720 px, the size the address book keeps. */
    fun encodePhoto(ctx: Context, uri: Uri): Pair<ByteArray, ImageBitmap>? = runCatching {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = (720f / maxOf(w, h)).coerceAtMost(1f)
            decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // Centre-crop to a square so the round avatar looks right everywhere
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val out = ByteArrayOutputStream()
        square.compress(Bitmap.CompressFormat.JPEG, 88, out)
        out.toByteArray() to square.asImageBitmap()
    }.getOrNull()
}

// =====================================================================
// Screen: create / edit a contact
// =====================================================================

@Composable
fun ContactEditorScreen(contactId: Long?, prefillNumber: String?, onDone: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var name by remember { mutableStateOf("") }
    val phones = remember { mutableStateListOf<EditPhone>() }
    var oldPhoto by remember { mutableStateOf<String?>(null) }
    var newPhoto by remember { mutableStateOf<Pair<ByteArray, ImageBitmap>?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(contactId == null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var canWrite by remember { mutableStateOf(ContactStore.canWrite(ctx)) }

    val writePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canWrite = it }
    LaunchedEffect(Unit) { if (!canWrite) writePerm.launch(Manifest.permission.WRITE_CONTACTS) }

    LaunchedEffect(contactId) {
        if (contactId != null) {
            val d = withContext(Dispatchers.IO) { ContactStore.load(ctx, contactId) }
            if (d != null) {
                name = d.name
                phones.clear()
                d.phones.forEach { phones += EditPhone(it.first, it.second) }
                oldPhoto = d.photo
            }
            loaded = true
        }
        if (phones.isEmpty()) phones += EditPhone(prefillNumber ?: "", Phone.TYPE_MOBILE)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val enc = withContext(Dispatchers.IO) { ContactStore.encodePhoto(ctx, uri) }
            if (enc != null) { newPhoto = enc; removePhoto = false } else error = "Не удалось открыть фото"
        }
    }

    val valid = name.isNotBlank() && phones.any { it.number.any(Char::isDigit) }

    fun save() {
        if (!valid || saving) return
        if (!canWrite) { writePerm.launch(Manifest.permission.WRITE_CONTACTS); return }
        saving = true
        error = null
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                ContactStore.save(
                    ctx, contactId, name.trim(),
                    phones.filter { it.number.isNotBlank() }.map { it.number.trim() to it.type },
                    newPhoto?.first, removePhoto
                )
            }
            saving = false
            if (err == null) {
                Haptics.of(ctx).confirm()
                PhoneData.clearNames(); Photos.clear(); Events.changed(); onDone()
            } else error = err
        }
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        LivingBackground(Modifier.layerBackdrop(backdrop))

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = top + 80.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Photo
            val press = rememberPress()
            Box(
                Modifier.size(132.dp).pressScale(press, 0.05f).pressable(press) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                contentAlignment = Alignment.Center
            ) {
                val np = newPhoto
                when {
                    np != null -> Image(
                        np.second, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(132.dp).clip(CircleShape)
                    )
                    oldPhoto != null && !removePhoto -> Avatar(name.ifBlank { "#" }, name, 132.dp, photo = oldPhoto)
                    else -> Avatar(name.ifBlank { "#" }, name.ifBlank { prefillNumber ?: "" }, 132.dp)
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(40.dp)
                        .liquidGlass(backdrop, Pill, c.bubble.copy(alpha = 0.9f), 8.dp, 16.dp),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.CameraAlt, "Фото", tint = c.bubbleText, modifier = Modifier.size(20.dp)) }
            }
            AnimatedVisibility(newPhoto != null || (oldPhoto != null && !removePhoto)) {
                Text(
                    "Убрать фото", color = c.danger, fontSize = 14.sp,
                    modifier = Modifier.padding(4.dp).pressable(rememberPress()) { newPhoto = null; removePhoto = true }
                )
            }

            // Name
            Field(backdrop, name, { name = it }, "Имя и фамилия", KeyboardType.Text)

            // Phones
            Column(
                Modifier.fillMaxWidth().themedGlass(backdrop, Card).padding(14.dp)
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Телефоны", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                phones.forEachIndexed { i, ph ->
                    androidx.compose.runtime.key(ph) {
                        PhoneRow(ph, canRemove = phones.size > 1) { phones.removeAt(i) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().pressable(rememberPress()) { phones += EditPhone("", Phone.TYPE_MOBILE) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, null, tint = c.bubble)
                    Spacer(Modifier.size(8.dp))
                    Text("Добавить номер", color = c.bubble, fontSize = 15.sp)
                }
            }

            AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(error ?: "", color = c.danger, fontSize = 14.sp)
            }
            AnimatedVisibility(!canWrite) {
                Text("Нужно разрешение на изменение контактов", color = c.danger, fontSize = 14.sp)
            }

            if (contactId != null) {
                Spacer(Modifier.height(8.dp))
                GlassButton(
                    backdrop, if (confirmDelete) "Точно удалить? Нажмите ещё раз" else "Удалить контакт",
                    Modifier.fillMaxWidth()
                ) {
                    if (!confirmDelete) { confirmDelete = true; return@GlassButton }
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ContactStore.delete(ctx, contactId) }
                        if (ok) { PhoneData.clearNames(); Events.changed(); onDone() } else error = "Не удалось удалить"
                    }
                }
            }
        }

        GlassHeader(backdrop, if (contactId == null) "Новый контакт" else "Изменить контакт", onBack = onDone) {
            val press = rememberPress()
            Box(
                Modifier.size(44.dp)
                    .liquidGlass(backdrop, Pill, c.bubble.copy(alpha = if (valid && loaded) 0.9f else 0.35f), 10.dp, 20.dp, press = press)
                    .pressable(press, enabled = valid && loaded) { save() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Check, "Сохранить", tint = c.bubbleText) }
        }
    }
}

@Composable
private fun Field(
    backdrop: com.kyant.backdrop.Backdrop,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    type: KeyboardType
) {
    val c = LocalColors.current
    Box(
        Modifier.fillMaxWidth().themedGlass(backdrop, Pill, lensHeight = 10.dp, lensAmount = 20.dp)
            .padding(horizontal = 20.dp, vertical = 15.dp)
    ) {
        if (value.isEmpty()) Text(hint, color = c.dim, fontSize = 16.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 17.sp),
            cursorBrush = SolidColor(c.bubble),
            keyboardOptions = KeyboardOptions(keyboardType = type),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PhoneRow(ph: EditPhone, canRemove: Boolean, onRemove: () -> Unit) {
    val c = LocalColors.current
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (ph.number.isEmpty()) Text("Номер телефона", color = c.dim, fontSize = 16.sp)
                BasicTextField(
                    ph.number, { ph.number = it }, singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(c.bubble),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (canRemove) {
                val press = rememberPress()
                Box(
                    Modifier.size(30.dp).pressScale(press, 0.15f).background(c.danger.copy(alpha = 0.15f), CircleShape)
                        .pressable(press, onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Close, "Удалить номер", tint = c.danger, modifier = Modifier.size(16.dp)) }
            }
        }
        // Type chips
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PhoneTypes.forEach { (t, label) ->
                val on = ph.type == t
                val press = rememberPress()
                Box(
                    Modifier.pressScale(press, 0.06f)
                        .background(if (on) c.bubble else c.surface, Pill)
                        .pressable(press) { ph.type = t }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { Text(label, color = if (on) c.bubbleText else c.dim, fontSize = 12.sp) }
            }
        }
    }
}
