package com.polaris.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ---------- Models ----------

enum class CallKind { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED }

data class CallEntry(
    val id: Long,
    val number: String,
    val name: String?,
    val kind: CallKind,
    val date: Long,
    val duration: Long,
    /** How many consecutive calls of the same number/kind are folded into this row. */
    val count: Int = 1,
    val ids: List<Long> = listOf(id)
) {
    val title get() = name ?: number.ifBlank { "Скрытый номер" }
}

data class ContactNumber(val number: String, val label: String)

data class Contact(val id: Long, val name: String, val numbers: List<ContactNumber>, val photo: String? = null)

// ---------- Phone: role, calls, call log, contacts, blacklist ----------

object PhoneData {
    private val names = ConcurrentHashMap<String, String>()

    fun isDefault(ctx: Context): Boolean {
        val role = runCatching {
            ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        }.getOrDefault(false)
        val telecom = runCatching {
            ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == ctx.packageName
        }.getOrDefault(false)
        return role || telecom
    }

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Places a call through the system — Telecom then binds our InCallService and we show CallActivity. */
    @SuppressLint("MissingPermission")
    fun call(ctx: Context, number: String) {
        val n = number.trim()
        if (n.isEmpty()) return
        val uri = Uri.fromParts("tel", n, null)
        val tm = ctx.getSystemService(TelecomManager::class.java)
        if (tm != null && has(ctx, Manifest.permission.CALL_PHONE)) {
            runCatching { tm.placeCall(uri, null) }.onSuccess { return }
        }
        // No permission yet: hand the number to the dial screen instead
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun sms(ctx: Context, number: String) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---- names ----

    fun name(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        names[number]?.let { return it.ifEmpty { null } }
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val n = runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        names[number] = n ?: ""
        return n
    }

    private val photosByNumber = ConcurrentHashMap<String, String>()

    /** Contact photo for a phone number (thumbnail or full size), or null if none. */
    fun photoFor(ctx: Context, number: String, full: Boolean = false): String? {
        if (number.isBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return null
        val key = (if (full) "F" else "T") + number
        photosByNumber[key]?.let { return it.ifEmpty { null } }
        val col = if (full) ContactsContract.PhoneLookup.PHOTO_URI else ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val p = runCatching {
            ctx.contentResolver.query(uri, arrayOf(col), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        photosByNumber[key] = p ?: ""
        return p
    }

    /** Contact id for a phone number, so "Изменить" works from Recents. */
    fun contactIdFor(ctx: Context, number: String): Long? {
        if (number.isBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull()
    }

    fun clearNames() { names.clear(); photosByNumber.clear() }

    // ---- call log ----

    fun recents(ctx: Context, limit: Int = 500): List<CallEntry> {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val raw = runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION
                ),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val out = ArrayList<CallEntry>()
                while (c.moveToNext() && out.size < limit) {
                    val kind = when (c.getInt(3)) {
                        CallLog.Calls.OUTGOING_TYPE -> CallKind.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> CallKind.MISSED
                        CallLog.Calls.REJECTED_TYPE -> CallKind.REJECTED
                        CallLog.Calls.BLOCKED_TYPE -> CallKind.BLOCKED
                        else -> CallKind.INCOMING
                    }
                    val number = c.getString(1) ?: ""
                    val cached = c.getString(2)?.ifBlank { null }
                    out += CallEntry(c.getLong(0), number, cached ?: name(ctx, number), kind, c.getLong(4), c.getLong(5))
                }
                out
            }
        }.getOrNull() ?: return emptyList()

        // Fold consecutive calls from the same number of the same kind on the same day: "Анна (3)"
        val folded = ArrayList<CallEntry>()
        for (e in raw) {
            val prev = folded.lastOrNull()
            if (prev != null && prev.kind == e.kind && numbersMatch(prev.number, e.number) && sameDay(prev.date, e.date)) {
                folded[folded.lastIndex] = prev.copy(count = prev.count + 1, ids = prev.ids + e.id)
            } else folded += e
        }
        return folded
    }

    fun deleteLog(ctx: Context, e: CallEntry) {
        if (!has(ctx, Manifest.permission.WRITE_CALL_LOG)) return
        runCatching {
            ctx.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} IN (${e.ids.joinToString(",")})", null
            )
        }
    }

    fun clearLog(ctx: Context) {
        if (!has(ctx, Manifest.permission.WRITE_CALL_LOG)) return
        runCatching { ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null) }
    }

    // ---- contacts ----

    fun contacts(ctx: Context): List<Contact> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val map = LinkedHashMap<Long, Pair<String, MutableList<ContactNumber>>>()
        val photos = HashMap<Long, String>()
        runCatching {
            ctx.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI),
                null, null, "${Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val number = c.getString(2) ?: continue
                    val label = Phone.getTypeLabel(ctx.resources, c.getInt(3), c.getString(4)).toString()
                    c.getString(5)?.let { photos[id] = it }
                    val entry = map.getOrPut(id) { name to mutableListOf() }
                    if (entry.second.none { numbersMatch(it.number, number) }) entry.second += ContactNumber(number, label)
                }
            }
        }
        return map.map { (id, v) -> Contact(id, v.first, v.second, photos[id]) }
    }

    /** Dial-pad suggestions: contacts whose number contains the typed digits. */
    fun matchDigits(all: List<Contact>, digits: String): List<Pair<Contact, ContactNumber>> {
        val d = digits.filter { it.isDigit() }
        if (d.length < 2) return emptyList()
        return all.asSequence().flatMap { c -> c.numbers.asSequence().map { c to it } }
            .filter { (_, n) -> n.number.filter { it.isDigit() }.contains(d) }
            .take(3).toList()
    }

    // ---- blacklist (system list, shared with SMS; needs the default-dialer role) ----

    fun canBlock(ctx: Context) =
        runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(ctx) }.getOrDefault(false)

    fun blocked(ctx: Context): List<String> = runCatching {
        ctx.contentResolver.query(
            BlockedNumberContract.BlockedNumbers.CONTENT_URI,
            arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null
        )?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let { add(it) } } }
    }.getOrNull() ?: emptyList()

    fun isBlocked(ctx: Context, number: String) =
        runCatching { BlockedNumberContract.isBlocked(ctx, number) }.getOrDefault(false)

    fun block(ctx: Context, number: String) {
        runCatching {
            ctx.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
            )
        }
    }

    fun unblock(ctx: Context, number: String) {
        runCatching { BlockedNumberContract.unblock(ctx, number) }
    }
}

// ---------- Helpers ----------

/** Compares numbers by their last 10 digits so +7 999… and 8 999… match. */
fun numbersMatch(a: String, b: String): Boolean {
    val x = a.filter { it.isDigit() }.takeLast(10)
    val y = b.filter { it.isDigit() }.takeLast(10)
    return if (x.isEmpty() || y.isEmpty()) a.equals(b, ignoreCase = true) else x == y
}

/** "tel:+7999…" → "+7999…" */
fun numberFrom(uri: Uri?): String? =
    uri?.schemeSpecificPart?.substringBefore('?')?.let { Uri.decode(it) }?.trim()?.ifEmpty { null }

private val ru: Locale = Locale.forLanguageTag("ru")

fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

fun fmtListTime(t: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(t, now) -> SimpleDateFormat("HH:mm", ru).format(Date(t))
        sameDay(t, now - 86_400_000L) -> "Вчера"
        now - t < 6 * 86_400_000L -> SimpleDateFormat("EEEE", ru).format(Date(t))
        else -> SimpleDateFormat("d MMM", ru).format(Date(t))
    }
}

/** 75 → "1:15", 3725 → "1:02:05" */
fun fmtDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun kindLabel(k: CallKind) = when (k) {
    CallKind.INCOMING -> "Входящий"
    CallKind.OUTGOING -> "Исходящий"
    CallKind.MISSED -> "Пропущенный"
    CallKind.REJECTED -> "Отклонённый"
    CallKind.BLOCKED -> "Заблокирован"
}
