package cloud.depup.office

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/** Satu baris daftar centang. */
data class NoteItem(var text: String, var done: Boolean = false)

/**
 * Catatan: teks biasa ATAU daftar centang ([checklist] → isi di [items], [body] kosong). Disimpan offline di perangkat
 * ([Notes]) dan bisa dipasang di layar utama HP lewat widget ([NoteWidget] satu catatan, [NotesListWidget] daftar).
 */
class Note(
    val id: Long,
    var title: String = "",
    var body: String = "",
    val items: MutableList<NoteItem> = mutableListOf(),
    var checklist: Boolean = false,
    /** Indeks [NoteColors.ALL]; 0 = bawaan. */
    var color: Int = 0,
    var pinned: Boolean = false,
    val created: Long = System.currentTimeMillis(),
    var updated: Long = created,
) {
    fun copy(): Note = Note(id, title, body, items.mapTo(mutableListOf()) { it.copy() }, checklist, color, pinned, created, updated)

    /** Tanpa judul dan tanpa isi (item kosong tidak dihitung). */
    val blank: Boolean
        get() = title.isBlank() && if (checklist) items.all { it.text.isBlank() } else body.isBlank()

    /** Item yang berisi: (sudah dicentang, semuanya). */
    fun progress(): Pair<Int, Int> {
        val f = items.filter { it.text.isNotBlank() }
        return f.count { it.done } to f.size
    }

    /** Teks ↔ daftar centang: tiap baris teks jadi satu item (poin "- ", "• ", "[x] " dikenali). */
    fun toChecklist() {
        if (checklist) return
        items.clear()
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { items.add(parseLine(it)) }
        if (items.isEmpty()) items.add(NoteItem(""))
        body = ""
        checklist = true
    }

    /** Daftar centang → teks biasa: satu baris per item (tanda centang dilepas). */
    fun toText() {
        if (!checklist) return
        body = items.filter { it.text.isNotBlank() }.joinToString("\n") { it.text.trim() }
        items.clear()
        checklist = false
    }

    /** Teks polos untuk dibagikan / disalin; item ditulis "- [x] …" / "- [ ] …" (tanpa emoji). */
    fun plainText(): String = buildString {
        if (title.isNotBlank()) append(title.trim())
        val content = if (checklist) items.filter { it.text.isNotBlank() }
            .joinToString("\n") { (if (it.done) "- [x] " else "- [ ] ") + it.text.trim() } else body.trim()
        if (content.isNotEmpty()) { if (isNotEmpty()) append("\n\n"); append(content) }
    }

    companion object {
        private val BOX = Regex("""^[-*•]?\s*\[([ xX])]\s*""")
        private val BULLET = Regex("""^[-*•]\s+""")

        /** "- [x] Susu" → (Susu, dicentang); "• Roti" → (Roti, belum). */
        fun parseLine(line: String): NoteItem {
            val t = line.trim()
            BOX.find(t)?.let { m -> return NoteItem(t.substring(m.range.last + 1).trim(), m.groupValues[1].isNotBlank()) }
            return NoteItem(t.replaceFirst(BULLET, "").trim())
        }
    }
}

/** Warna catatan seperti kertas tempel: pasangan terang/gelap. 0 = bawaan (permukaan kartu / latar halaman). */
object NoteColors {
    class Tone(val name: String, val light: Int, val dark: Int)

    val ALL = listOf(
        Tone("Bawaan", 0xFFFFFFFF.toInt(), 0xFF1B1B1A.toInt()),
        Tone("Kuning", 0xFFFFF0B3.toInt(), 0xFF4A3E10.toInt()),
        Tone("Oranye", 0xFFFFDDC4.toInt(), 0xFF4E2D16.toInt()),
        Tone("Merah muda", 0xFFFAD5E0.toInt(), 0xFF4B2131.toInt()),
        Tone("Ungu", 0xFFE5DBFA.toInt(), 0xFF34294F.toInt()),
        Tone("Biru", 0xFFD4E4FC.toInt(), 0xFF1E3350.toInt()),
        Tone("Hijau", 0xFFD6F0DA.toInt(), 0xFF1E3B27.toInt()),
    )

    fun of(i: Int): Tone = ALL.getOrElse(i) { ALL[0] }

    // teks di atas warna catatan (>= 4.5:1 di semua warna)
    const val TEXT_L = 0xFF111111.toInt()
    const val TEXT_D = 0xFFF2F1EE.toInt()
    const val MUTED_L = 0xFF5C5953.toInt()
    const val MUTED_D = 0xFFB3B0AA.toInt()
    const val PRIMARY_L = 0xFF1652F6.toInt()
    const val PRIMARY_D = 0xFF1D56FF.toInt()
}

/** Simpan/baca catatan sebagai JSON (format v1). Murni — diuji di JVM ([NotesTest]). */
object NoteCodec {
    fun encode(list: List<Note>): String {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        return JSONObject().put("v", 1).put("notes", a).toString()
    }

    fun toJson(n: Note): JSONObject {
        val items = JSONArray()
        n.items.forEach { items.put(JSONObject().put("x", it.text).put("d", it.done)) }
        return JSONObject().put("id", n.id).put("t", n.title).put("b", n.body).put("c", n.checklist).put("i", items)
            .put("k", n.color).put("p", n.pinned).put("cr", n.created).put("up", n.updated)
    }

    /** Entri rusak dilewati; berkas rusak total → daftar kosong (pemanggil menyimpan cadangannya). */
    fun decode(s: String): MutableList<Note> {
        val out = mutableListOf<Note>()
        val a = JSONObject(s).optJSONArray("notes") ?: return out
        for (k in 0 until a.length()) {
            val o = a.optJSONObject(k) ?: continue
            if (!o.has("id")) continue
            try {
                val items = mutableListOf<NoteItem>()
                o.optJSONArray("i")?.let { ia ->
                    for (j in 0 until ia.length()) ia.optJSONObject(j)?.let { items.add(NoteItem(it.optString("x"), it.optBoolean("d"))) }
                }
                val created = o.optLong("cr", System.currentTimeMillis())
                out.add(Note(
                    o.getLong("id"), o.optString("t"), o.optString("b"), items, o.optBoolean("c"),
                    o.optInt("k").coerceIn(0, NoteColors.ALL.size - 1), o.optBoolean("p"), created, o.optLong("up", created)
                ))
            } catch (_: Exception) {}
        }
        return out
    }
}

/**
 * Penyimpanan catatan: satu berkas JSON di penyimpanan internal (tidak pernah keluar dari HP), ditulis atomik.
 * Isi di-cache di memori; semua akses lewat kunci (layar, widget & layanan widget berjalan di proses yang sama).
 * Tiap perubahan langsung memperbarui widget di layar utama.
 */
object Notes {
    private const val FILE = "notes.json"
    private val lock = Any()
    private var cache: MutableList<Note>? = null
    private var lastId = 0L

    private fun file(ctx: Context) = AtomicFile(File(ctx.applicationContext.filesDir, FILE))

    private fun loaded(ctx: Context): MutableList<Note> {
        cache?.let { return it }
        val f = file(ctx)
        val l = try {
            NoteCodec.decode(String(f.readFully(), Charsets.UTF_8))
        } catch (_: java.io.FileNotFoundException) {
            mutableListOf()   // belum pernah ada catatan
        } catch (_: Throwable) {
            // berkas rusak: simpan salinannya (tidak dihapus) lalu mulai dari daftar kosong
            try { f.baseFile.copyTo(File(f.baseFile.parentFile, "notes-rusak-${System.currentTimeMillis()}.json"), true) } catch (_: Throwable) {}
            mutableListOf()
        }
        cache = l
        return l
    }

    private fun persist(ctx: Context, l: List<Note>) {
        val f = file(ctx)
        var out: FileOutputStream? = null
        try {
            out = f.startWrite()
            out.write(NoteCodec.encode(l).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (_: Throwable) {
            out?.let { f.failWrite(it) }
        }
    }

    /** Semua catatan (salinan): disematkan dulu, lalu yang terakhir diubah. */
    fun all(ctx: Context): List<Note> = synchronized(lock) { loaded(ctx).map { it.copy() } }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })

    fun get(ctx: Context, id: Long): Note? = synchronized(lock) { loaded(ctx).firstOrNull { it.id == id }?.copy() }

    fun count(ctx: Context): Int = synchronized(lock) { loaded(ctx).size }

    /** Id baru yang belum dipakai (berbasis waktu, selalu naik). */
    fun newId(ctx: Context): Long = synchronized(lock) {
        val l = loaded(ctx)
        var id = maxOf(System.currentTimeMillis(), lastId + 1)
        while (l.any { it.id == id }) id++
        lastId = id
        id
    }

    /** Simpan (tambah atau timpa). [touch] = waktu ubah diperbarui. Mengembalikan waktu ubah yang tersimpan. */
    fun save(ctx: Context, n: Note, touch: Boolean = true): Long {
        val c = n.copy()
        if (touch) c.updated = System.currentTimeMillis()
        synchronized(lock) {
            val l = loaded(ctx)
            val i = l.indexOfFirst { it.id == c.id }
            if (i >= 0) l[i] = c else l.add(c)
            persist(ctx, l)
        }
        NoteWidgets.refresh(ctx)
        return c.updated
    }

    fun delete(ctx: Context, id: Long) {
        synchronized(lock) {
            val l = loaded(ctx)
            if (l.removeAll { it.id == id }) persist(ctx, l)
        }
        NoteWidgets.refresh(ctx)
    }

    /**
     * Centang/lepas satu item (dari widget). [expect] = teks item saat widget digambar: bila catatan sudah diubah
     * sesudahnya (item bergeser), item yang salah tidak ikut tercentang. false = catatan/item sudah tidak ada.
     */
    fun toggle(ctx: Context, id: Long, index: Int, expect: String? = null): Boolean {
        synchronized(lock) {
            val n = loaded(ctx).firstOrNull { it.id == id } ?: return false
            val item = n.items.getOrNull(index)?.takeIf { expect == null || it.text == expect } ?: return false
            item.done = !item.done
            n.updated = System.currentTimeMillis()
            persist(ctx, loaded(ctx))
        }
        NoteWidgets.refresh(ctx)
        return true
    }

    /** Ubah sebagian (sematkan, warna) tanpa membuka editor. */
    fun edit(ctx: Context, id: Long, touch: Boolean = false, block: (Note) -> Unit) {
        val n = get(ctx, id) ?: return
        block(n)
        save(ctx, n, touch)
    }
}

/** Penghubung catatan ↔ widget layar utama: widget mana menampilkan catatan mana, dan pemasangan dari aplikasi. */
object NoteWidgets {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("depup_note_widgets", Context.MODE_PRIVATE)

    /** Catatan yang ditampilkan widget [widgetId]; -1 = belum dipilih. */
    fun noteFor(ctx: Context, widgetId: Int): Long = sp(ctx).getLong("w$widgetId", -1L)

    /** Kapan widget terakhir diikat ke catatan (ms) — layar pilih catatan tidak muncul lagi tepat sesudah dipasang. */
    fun boundAt(ctx: Context, widgetId: Int): Long = sp(ctx).getLong("t$widgetId", 0L)

    fun bind(ctx: Context, widgetId: Int, noteId: Long) {
        sp(ctx).edit().putLong("w$widgetId", noteId).putLong("t$widgetId", System.currentTimeMillis()).apply()
        NoteWidget.updateAll(ctx)
    }

    fun unbind(ctx: Context, ids: IntArray) {
        val e = sp(ctx).edit()
        ids.forEach { e.remove("w$it").remove("t$it") }
        e.apply()
    }

    /** Token acak per pemasangan: siaran centang/pasang dari widget hanya diterima bila membawanya. */
    fun token(ctx: Context): String {
        val s = sp(ctx)
        s.getString("tok", null)?.let { return it }
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val t = b.joinToString("") { "%02x".format(it) }
        s.edit().putString("tok", t).apply()
        return t
    }

    /** Perbarui semua widget catatan di layar utama (dipanggil tiap catatan berubah). */
    fun refresh(ctx: Context) {
        try { NoteWidget.updateAll(ctx) } catch (_: Throwable) {}
        try { NotesListWidget.updateAll(ctx) } catch (_: Throwable) {}
    }

    /** Sudah ada widget catatan (jenis apa pun) di layar utama. */
    fun placed(ctx: Context): Boolean = try {
        val am = AppWidgetManager.getInstance(ctx)
        am.getAppWidgetIds(ComponentName(ctx, NoteWidget::class.java)).isNotEmpty() ||
            am.getAppWidgetIds(ComponentName(ctx, NotesListWidget::class.java)).isNotEmpty()
    } catch (_: Throwable) { false }

    /** Peluncur HP mendukung "pasang widget dari aplikasi" (Android 8+, tergantung peluncur). */
    fun canPin(ctx: Context): Boolean = try {
        AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported
    } catch (_: Throwable) { false }

    /**
     * Minta peluncur memasang widget satu catatan untuk [noteId] (−1 = catatan dipilih nanti di widget). Peluncur
     * menampilkan dialog konfirmasinya sendiri; setelah terpasang, [NoteWidget] menerima id widget lewat callback.
     */
    fun pinNote(ctx: Context, noteId: Long): Boolean {
        if (!canPin(ctx)) return false
        val cb = Intent(ctx, NoteWidget::class.java).setAction(NoteWidget.ACTION_PINNED)
            .setData(android.net.Uri.parse("depup://catatan/pasang/$noteId"))
            .putExtra(NoteWidget.EXTRA_NOTE, noteId).putExtra(NoteWidget.EXTRA_TOK, token(ctx))
        val pi = PendingIntent.getBroadcast(ctx, 0, cb, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        return try {
            AppWidgetManager.getInstance(ctx).requestPinAppWidget(ComponentName(ctx, NoteWidget::class.java), null, pi)
        } catch (_: Throwable) { false }
    }

    /** Minta peluncur memasang widget daftar catatan. */
    fun pinList(ctx: Context): Boolean {
        if (!canPin(ctx)) return false
        return try {
            AppWidgetManager.getInstance(ctx).requestPinAppWidget(ComponentName(ctx, NotesListWidget::class.java), null, null)
        } catch (_: Throwable) { false }
    }
}
