package cloud.depup.office

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Teks & warna catatan yang dipakai bersama layar catatan, Beranda, dan widget. */
object NoteViews {
    private val WS = Regex("\\s+")
    private fun flat(s: String) = s.replace(WS, " ").trim()

    /** Judul tampilan: judul, atau baris/item pertama bila tanpa judul. */
    fun headline(n: Note): String {
        flat(n.title).takeIf { it.isNotEmpty() }?.let { return it.take(120) }
        val first = if (n.checklist) n.items.firstOrNull { it.text.isNotBlank() }?.text
        else n.body.lineSequence().firstOrNull { it.isNotBlank() }
        return first?.let { flat(it).take(120) } ?: if (n.checklist) "Daftar centang" else "Catatan kosong"
    }

    /** Ringkasan pendek di bawah judul (widget daftar, pilih catatan). Tidak pernah kosong. */
    fun summary(n: Note, head: String = headline(n)): String {
        val titled = n.title.isNotBlank()
        val s = if (n.checklist) {
            val (done, total) = n.progress()
            var open = n.items.filter { it.text.isNotBlank() && !it.done }.map { flat(it.text) }
            if (!titled && open.firstOrNull() == head) open = open.drop(1)
            val list = open.take(6).joinToString(", ")
            when {
                total == 0 -> ""
                list.isEmpty() -> if (done == total) "Semua selesai ($total)" else "$done/$total selesai"
                else -> "$done/$total selesai · $list"
            }
        } else {
            val lines = n.body.lines().map { flat(it) }.filter { it.isNotEmpty() }
            (if (titled) lines else lines.drop(1)).joinToString(" ")
        }
        return s.take(160).ifEmpty { Files.relTime(n.updated) }
    }

    /** Isian kartu catatan di tema saat ini (bawaan = permukaan kartu). */
    fun fill(color: Int): Int =
        if (color <= 0) C.SURFACE else NoteColors.of(color).let { if (C.dark) it.dark else it.light }

    /** Latar halaman editor (bawaan = latar halaman aplikasi). */
    fun page(color: Int): Int = if (color <= 0) C.BG else fill(color)

    /** Teks sekunder yang tetap terbaca di atas warna catatan. */
    fun muted(color: Int): Int =
        if (color <= 0) C.MUTED else if (C.dark) NoteColors.MUTED_D else NoteColors.MUTED_L

    /** "Diubah 5 menit lalu", "Diubah kemarin, 08.15", "Diubah 3 Nov 2026". */
    fun edited(n: Note): String {
        val r = Files.relTime(n.updated)
        return when {
            r.isEmpty() -> ""
            r == "Baru saja" -> "Diubah baru saja"
            r.startsWith("Kemarin") -> "Diubah kemarin" + r.removePrefix("Kemarin")
            else -> "Diubah $r"
        }
    }

    fun matches(n: Note, q: String): Boolean {
        if (q.isBlank()) return true
        val needle = q.trim().lowercase()
        return n.title.lowercase().contains(needle) || n.body.lowercase().contains(needle) ||
            n.items.any { it.text.lowercase().contains(needle) }
    }
}

/** Buka editor catatan: [id] ≥ 0 = catatan yang ada; selain itu catatan baru (teks / daftar centang). */
fun Context.openNote(id: Long, checklist: Boolean = false) {
    val i = Intent(this, NoteEditActivity::class.java)
    if (id >= 0) i.putExtra(NoteEditActivity.EXTRA_ID, id) else i.putExtra(NoteEditActivity.EXTRA_CHECKLIST, checklist)
    startActivity(i)
}

/**
 * Kartu catatan seperti kertas tempel: judul, cuplikan isi (atau item daftar centang), sematan & waktu ubah.
 * [widthDp] > 0 = lebar tetap (korsel Beranda); 0 = mengikuti kolom (kisi layar Catatan).
 */
fun AppCompatActivity.noteCard(n: Note, widthDp: Int = 0, lines: Int = 10, onOpen: () -> Unit, onLong: (() -> Unit)? = null): LinearLayout {
    val fg = C.TEXT
    val mu = NoteViews.muted(n.color)
    val cw = if (widthDp > 0) dp(widthDp - 28) else MATCH
    val box = vbox(14)
    box.background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), squircle(NoteViews.fill(n.color), 20), squircle(Color.WHITE, 20))
    if (widthDp > 0) box.minimumHeight = dp(128)
    box.isClickable = true; box.isFocusable = true
    val head = NoteViews.headline(n)
    box.contentDescription = buildString {
        append(head); append(". "); append(NoteViews.summary(n, head))
        if (n.pinned) append(". Disematkan")
        if (onLong != null) append(". Tekan lama untuk opsi")
    }
    box.setOnClickListener { haptic(box); onOpen() }
    if (onLong != null) box.setOnLongClickListener { haptic(box); onLong(); true }
    box.springPress(0.97f)

    val titled = n.title.isNotBlank()
    if (titled) box.add(tv(n.title.trim(), 15f, fg, true).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, w = cw)
    if (n.checklist) {
        val its = n.items.filter { it.text.isNotBlank() }
        val cap = (lines / 2).coerceIn(2, 8)
        its.take(cap).forEachIndexed { i, it ->
            val r = hbox(Gravity.TOP)
            r.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            r.add(icon(if (it.done) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox, if (it.done) mu else fg, 16), w = dp(16), h = dp(16))
            r.add(tv(it.text.trim(), 13f, if (it.done) mu else fg).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                if (it.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }, w = 0, weight = 1f, side = 0)
            (r.getChildAt(1).layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            box.add(r, top = if (i == 0 && !titled) 0 else 6, w = cw)
        }
        if (its.size > cap) box.add(tv("+${its.size - cap} item lagi", 12f, mu).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, top = 6, w = cw)
        if (its.isEmpty() && !titled) box.add(tv("Daftar centang kosong", 13f, mu), w = cw)
    } else {
        val b = n.body.trim()
        if (b.isNotEmpty()) box.add(tv(b, 13f, fg).apply {
            maxLines = lines; ellipsize = TextUtils.TruncateAt.END; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, top = if (titled) 4 else 0, w = cw)
        else if (!titled) box.add(tv("Catatan kosong", 13f, mu), w = cw)
    }
    val foot = hbox()
    foot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    if (n.pinned) {
        foot.add(icon(R.drawable.ic_pin, mu, 13), w = dp(13), h = dp(13))
        foot.add(View(this), w = dp(5), h = dp(1))
    }
    foot.add(tv(Files.relTime(n.updated), 12f, mu).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, w = 0, weight = 1f)
    box.add(foot, top = 10, w = cw)
    return box
}

/** Kartu "Catatan baru" di depan korsel catatan Beranda (ukurannya sama dengan [noteCard]). */
fun AppCompatActivity.newNoteCard(widthDp: Int, onClick: () -> Unit): LinearLayout {
    val cw = dp(widthDp - 28)
    val box = vbox(14)
    box.background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), squircle(C.SURFACE, 20), squircle(Color.WHITE, 20))
    box.minimumHeight = dp(128)
    box.isClickable = true; box.isFocusable = true
    box.contentDescription = "Catatan baru"
    box.setOnClickListener { haptic(box); onClick() }
    box.springPress(0.97f)
    box.add(iconBadge(R.drawable.ic_plus, C.ON_PRIMARY, C.PRIMARY, 100, 20), w = dp(40), h = dp(40))
    box.add(tv("Catatan baru", 15f, C.TEXT, true).apply { maxLines = 1; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, top = 12, w = cw)
    box.add(tv("Teks atau daftar centang", 13f, C.MUTED).apply { maxLines = 2; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, top = 2, w = cw)
    return box
}

/** Baris catatan ringkas (pilih catatan untuk widget). */
fun AppCompatActivity.noteRow(n: Note, onClick: () -> Unit): View {
    val head = NoteViews.headline(n)
    val sub = NoteViews.summary(n, head)
    val r = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(12), dp(12))
        background = rippleCard(16)
        minimumHeight = dp(64)
        isClickable = true; isFocusable = true
        contentDescription = "$head. $sub"
        setOnClickListener { onClick() }
        springPress(0.975f)
    }
    val badge = FrameLayout(this).apply {
        background = squircle(NoteViews.fill(n.color), 12, strokeColor = C.LINE, strokeDp = 1f)
        addView(icon(if (n.checklist) R.drawable.ic_checklist else R.drawable.ic_note, C.TEXT, 20),
            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    }
    r.add(badge, w = dp(40), h = dp(40))
    val col = vbox()
    col.add(tv(head, 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    col.add(tv(sub, 13f, C.MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, top = 2)
    r.add(col, w = 0, weight = 1f, side = 12)
    return r
}

/** Deret pilihan warna catatan (lingkaran 40 dp). */
fun AppCompatActivity.noteSwatches(selected: Int, onPick: (Int) -> Unit): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    NoteColors.ALL.forEachIndexed { i, t ->
        val on = i == selected
        val sw = FrameLayout(this).apply {
            background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE),
                shape(NoteViews.fill(i), 100, if (on) C.INK else C.LINE_STRONG, if (on) 2 else 1), null)
            isClickable = true; isFocusable = true
            contentDescription = "Warna ${t.name}" + if (on) ", dipilih" else ""
            setOnClickListener { haptic(this); onPick(i) }
            springPress(0.9f)
            if (on) addView(icon(R.drawable.ic_check, C.TEXT, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        row.addView(sw, LinearLayout.LayoutParams(dp(40), dp(40)).apply { if (i > 0) marginStart = dp(6) })
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
        addView(row)
    }
}

fun Context.shareNote(n: Note) {
    val t = n.plainText()
    if (t.isBlank()) { toast("Catatan masih kosong"); return }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
    if (n.title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, n.title.trim())
    try { startActivity(Intent.createChooser(send, "Bagikan catatan")) } catch (_: Exception) { toast("Tidak ada aplikasi untuk berbagi") }
}

fun Context.copyNote(n: Note) {
    val t = n.plainText()
    if (t.isBlank()) { toast("Catatan masih kosong"); return }
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Catatan", t))
    if (Build.VERSION.SDK_INT < 33) toast("Teks catatan disalin")   // Android 13+ menampilkan pemberitahuannya sendiri
}

/** Cara memasang widget sendiri (peluncur yang tidak mendukung pemasangan dari aplikasi). */
fun AppCompatActivity.noteWidgetHelp() {
    AlertDialog.Builder(this)
        .setTitle("Pasang widget dari layar utama")
        .setMessage("Tekan lama area kosong di layar utama HP, pilih Widget, cari Depup Office, lalu tekan lama " +
            "“Catatan” (satu catatan) atau “Daftar catatan” dan letakkan di layar utama.")
        .setPositiveButton("Mengerti", null)
        .show()
}

/** Pasang widget satu catatan untuk [id] (peluncur menampilkan konfirmasinya sendiri). */
fun AppCompatActivity.pinNoteToHome(id: Long) {
    if (!NoteWidgets.pinNote(this, id)) noteWidgetHelp()
}

/** Lembar widget catatan: pilih jenis widget yang dipasang di layar utama. */
fun AppCompatActivity.noteWidgetSheet() {
    val can = NoteWidgets.canPin(this)
    sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_widget, C.AMBER, C.AMBER_BG, "Widget catatan", "Catatan langsung di layar utama HP", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        pad.add(actionRow(R.drawable.ic_checklist, "Satu catatan", "Seperti kertas tempel. Daftar centang bisa dicentang dari layar utama.",
            C.AMBER, C.AMBER_BG) { dismiss(); if (can) pickNoteForWidget() else noteWidgetHelp() })
        pad.add(actionRow(R.drawable.ic_note, "Daftar catatan", "Catatan terbaru, plus tombol tulis catatan & daftar centang baru.",
            C.PRIMARY, C.PRIMARY_C) { dismiss(); if (!can || !NoteWidgets.pinList(this)) noteWidgetHelp() }, top = 8)
        if (!can) pad.add(tv("Peluncur HP ini belum mendukung pemasangan langsung dari aplikasi. Pilih salah satu untuk melihat caranya.",
            13f, C.MUTED), top = 12)
        body.add(pad)
        body.add(View(this), h = dp(12))
    }
}

/** Pilih catatan untuk widget satu catatan, lalu minta peluncur memasangnya. */
fun AppCompatActivity.pickNoteForWidget() {
    val all = Notes.all(this)
    if (all.isEmpty()) { pinNoteToHome(-1L); return }   // widget meminta catatan dipilih setelah dipasang
    sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_widget, C.AMBER, C.AMBER_BG, "Pilih catatan", "Catatan yang tampil di widget", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        all.take(60).forEachIndexed { i, n ->
            pad.add(noteRow(n) { dismiss(); pinNoteToHome(n.id) }, top = if (i == 0) 0 else 8)
        }
        body.add(pad)
        body.add(View(this), h = dp(12))
    }
}

/** Hapus catatan setelah konfirmasi. */
fun AppCompatActivity.confirmDeleteNote(n: Note, onDeleted: () -> Unit) {
    confirm("Hapus catatan?", "“${NoteViews.headline(n)}” dihapus dari HP ini. Widget yang menampilkannya akan meminta catatan lain.", "Hapus") {
        Notes.delete(this, n.id)
        onDeleted()
    }
}

/** Lembar opsi catatan (tekan lama kartu): warna, sematkan, pasang di layar utama, bagikan, salin, hapus. */
fun AppCompatActivity.noteActions(id: Long, onChanged: () -> Unit, onDeleted: () -> Unit) {
    val n = Notes.get(this, id) ?: run { onChanged(); return }
    sheet { body, dismiss ->
        val head = NoteViews.headline(n)
        sheetHeader(body, if (n.checklist) R.drawable.ic_checklist else R.drawable.ic_note, C.AMBER, C.AMBER_BG, head,
            NoteViews.edited(n), dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(4), dp(20), dp(8)) }
        pad.add(tv("Warna", 14f, C.TEXT, true))
        pad.add(noteSwatches(n.color) { c ->
            dismiss()
            if (c != n.color) { Notes.edit(this, id) { it.color = c }; onChanged() }
        }, top = 8)
        pad.add(actionRow(R.drawable.ic_pin, if (n.pinned) "Lepas sematan" else "Sematkan",
            if (n.pinned) "Kembalikan ke urutan biasa" else "Selalu tampil paling atas", C.PRIMARY, C.PRIMARY_C) {
            dismiss(); Notes.edit(this, id) { it.pinned = !it.pinned }; onChanged()
        }, top = 16)
        pad.add(actionRow(R.drawable.ic_widget, "Pasang di layar utama", "Jadikan widget, daftar centang bisa dicentang dari sana",
            C.AMBER, C.AMBER_BG) { dismiss(); pinNoteToHome(id) }, top = 8)
        pad.add(actionRow(R.drawable.ic_share, "Bagikan", "Kirim sebagai teks", C.PRIMARY, C.PRIMARY_C) { dismiss(); shareNote(n) }, top = 8)
        pad.add(actionRow(R.drawable.ic_copy, "Salin teks", "Tempel di aplikasi lain", C.PRIMARY, C.PRIMARY_C) { dismiss(); copyNote(n) }, top = 8)
        pad.add(actionRow(R.drawable.ic_trash, "Hapus", "Hapus catatan dari HP ini", C.ERROR, C.ERROR_C) {
            dismiss(); confirmDeleteNote(n, onDeleted)
        }, top = 8)
        body.add(pad)
        body.add(View(this), h = dp(12))
    }
}
