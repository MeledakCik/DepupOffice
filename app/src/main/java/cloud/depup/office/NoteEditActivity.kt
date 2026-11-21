package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Isian satu baris daftar centang. Tombol hapus (⌫) di awal baris diteruskan ke [onBackAtStart] — dari papan ketik
 * layar (lewat InputConnection, karena banyak papan ketik tidak mengirim tombol hapus sebagai KeyEvent) maupun fisik.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
private class ItemEdit(ctx: Context) : EditText(ctx) {
    /** true = sudah ditangani (baris digabung/dihapus). */
    var onBackAtStart: (() -> Boolean)? = null

    private fun atStart() = selectionStart == 0 && selectionEnd == 0

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && atStart() && onBackAtStart?.invoke() == true) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && atStart() && onBackAtStart?.invoke() == true) return true
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && atStart() && onBackAtStart?.invoke() == true) return true
        return super.onKeyDown(keyCode, event)
    }

    // Enter (papan ketik fisik) ditangani saat ditekan; saat dilepas fokus sudah di item baru — jangan pindah fokus lagi.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) return true
        return super.onKeyUp(keyCode, event)
    }
}

private fun watch(f: (String) -> Unit) = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) = f(s?.toString() ?: "")
}

/**
 * Editor catatan: judul + teks bebas, atau daftar centang (Enter = item baru, hapus di awal item = gabung ke item
 * sebelumnya). Tersimpan otomatis (offline) beberapa saat setelah berhenti mengetik dan saat layar ditinggalkan;
 * widget di layar utama ikut diperbarui. Catatan baru yang tetap kosong tidak disimpan.
 */
class NoteEditActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "depup_note_id"
        const val EXTRA_CHECKLIST = "depup_note_checklist"
        /** Buka langsung dengan papan ketik (catatan kosong yang baru dibuat dari widget). */
        const val EXTRA_FOCUS = "depup_note_focus"
        private const val SAVE_DELAY = 600L
        private const val STATE_NOTE = "depup_note_state"
        private const val STATE_ID = "depup_note_state_id"
        private const val STATE_FRESH = "depup_note_fresh"
        private const val STATE_SAVED = "depup_note_saved"
    }

    private class Row(val item: NoteItem, val view: LinearLayout, val box: ImageView, val et: ItemEdit, val del: ImageView)

    private lateinit var note: Note
    /** Catatan baru dari layar ini: selama masih kosong tidak disimpan (dan dibuang saat ditutup). */
    private var fresh = false
    /** Waktu ubah versi tersimpan yang sedang ditampilkan (0 = belum pernah tersimpan) — pembanding perubahan dari widget. */
    private var savedAt = 0L
    private var dirty = false
    private var touched = false
    private var deleted = false
    /** Teks diubah oleh kode (bukan pengguna): pengamat teks tidak bereaksi. */
    private var muting = false

    private lateinit var scroll: ScrollView
    private lateinit var titleEt: EditText
    private lateinit var meta: TextView
    private lateinit var pinBtn: ImageView
    private var bodyEt: EditText? = null
    private var itemsBox: LinearLayout? = null
    private val rows = ArrayList<Row>()
    private val handler = Handler(Looper.getMainLooper())
    private val saver = Runnable { saveNow() }

    private fun ready() = ::note.isInitialized

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restored = savedInstanceState?.getString(STATE_NOTE)?.let { s ->
            try { NoteCodec.decode(s).firstOrNull() } catch (_: Exception) { null }
        } ?: savedInstanceState?.getLong(STATE_ID, -1L)?.takeIf { it >= 0 }?.let { Notes.get(this, it) }
        var focus = intent.getBooleanExtra(EXTRA_FOCUS, false)
        if (restored != null && savedInstanceState != null) {
            note = restored
            fresh = savedInstanceState.getBoolean(STATE_FRESH)
            savedAt = savedInstanceState.getLong(STATE_SAVED)
            focus = false
        } else {
            val id = intent.getLongExtra(EXTRA_ID, -1L)
            if (id >= 0) {
                val n = Notes.get(this, id)
                if (n == null) { toast("Catatan ini sudah dihapus"); finish(); return }
                note = n
                savedAt = n.updated
            } else {
                note = Note(Notes.newId(this), checklist = intent.getBooleanExtra(EXTRA_CHECKLIST, false))
                if (note.checklist) note.items.add(NoteItem(""))
                fresh = true
                focus = true
            }
        }
        onBackPressedDispatcher.addCallback(this, PreviewBack(this) { close() })
        window.setSoftInputMode(
            (if (focus) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN) or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        build(focus)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!ready()) return
        outState.putLong(STATE_ID, note.id)
        outState.putBoolean(STATE_FRESH, fresh)
        outState.putLong(STATE_SAVED, savedAt)
        val s = NoteCodec.encode(listOf(note))
        if (s.length < 200_000) outState.putString(STATE_NOTE, s)   // catatan raksasa: dibaca ulang dari penyimpanan
    }

    override fun onResume() {
        super.onResume()
        if (!ready() || deleted || savedAt == 0L) return
        // Item bisa dicentang dari widget selama layar ini di belakang: tampilkan versi terbaru.
        val cur = Notes.get(this, note.id)
        if (cur == null) { toast("Catatan ini sudah dihapus"); deleted = true; finish(); return }
        if (cur.updated != savedAt && !dirty) {
            note = cur
            savedAt = cur.updated
            build(focus = false)
        }
    }

    override fun onPause() {
        super.onPause()
        if (ready()) saveNow()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saver)
        super.onDestroy()
    }

    private fun close() {
        if (ready()) {
            saveNow()
            if (fresh && note.blank && touched && !deleted) toast("Catatan kosong tidak disimpan")
        }
        finish()
    }

    // ------------------------------------------------------------------ simpan

    private fun changed() {
        dirty = true
        touched = true
        handler.removeCallbacks(saver)
        handler.postDelayed(saver, SAVE_DELAY)
    }

    private fun saveNow() {
        handler.removeCallbacks(saver)
        if (!dirty || deleted) return
        dirty = false
        if (fresh && note.blank) {
            // catatan baru yang dikosongkan lagi: jangan tinggalkan catatan kosong
            if (savedAt != 0L) { Notes.delete(this, note.id); savedAt = 0L }
            paintMeta()
            return
        }
        savedAt = Notes.save(this, note)
        note.updated = savedAt
        paintMeta()
    }

    // ------------------------------------------------------------------ tampilan

    private fun build(focus: Boolean) {
        rows.clear(); bodyEt = null; itemsBox = null
        val pageColor = NoteViews.page(note.color)
        val mu = NoteViews.muted(note.color)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pageColor) }

        // bilah atas: kapsul kaca melayang
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        bar.add(icon(R.drawable.ic_back, C.TEXT, 24).asButton("Kembali").apply { setOnClickListener { close() } }, w = dp(48), h = dp(48))
        bar.add(View(this), w = 0, h = dp(1), weight = 1f)
        pinBtn = icon(R.drawable.ic_pin, C.TEXT, 24).asButton("Sematkan").apply { setOnClickListener { togglePin() } }
        bar.add(pinBtn, w = dp(48), h = dp(48))
        paintPin()
        bar.add(icon(R.drawable.ic_palette, C.TEXT, 24).asButton("Warna catatan").apply { setOnClickListener { colorSheet() } },
            w = dp(48), h = dp(48))
        bar.add(icon(if (note.checklist) R.drawable.ic_note else R.drawable.ic_checklist, C.TEXT, 24)
            .asButton(if (note.checklist) "Jadikan teks biasa" else "Jadikan daftar centang").apply { setOnClickListener { toggleMode() } },
            w = dp(48), h = dp(48))
        bar.add(icon(R.drawable.ic_more, C.TEXT, 22).asButton("Opsi lain").apply { setOnClickListener { more() } }, w = dp(48), h = dp(48))
        root.add(bar)

        val col = vbox().apply { setPadding(dp(22), dp(4), dp(22), dp(40)) }
        titleEt = plainEdit("Judul", 22f, bold = true).apply {
            setText(note.title)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_NEXT || action == EditorInfo.IME_ACTION_DONE ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { focusContent(start = true); true }
                else false
            }
            addTextChangedListener(watch { t -> if (!muting) { note.title = t; changed() } })
        }
        col.add(titleEt)
        meta = tv("", 12f, mu).apply { maxLines = 1 }
        col.add(meta, top = 0)
        if (note.checklist) buildItems(col) else buildBody(col)

        scroll = ScrollView(this).apply { isFillViewport = true; addView(col) }
        root.add(scroll, h = 0, weight = 1f)
        setContentView(Glass.scaffold(root, floatTop = true))
        // jendela & bilah sistem berwarna kertas catatan (bilah status/navigasi transparan di Android 15+)
        paintBars(pageColor)
        paintMeta()
        if (focus) focusContent(start = false)
    }

    /** Jendela & bilah sistem berwarna kertas catatan (di Android 15+ bilahnya transparan: latar jendela yang tampil). */
    @Suppress("DEPRECATION")
    private fun paintBars(c: Int) {
        window.setBackgroundDrawable(ColorDrawable(c))
        Theme.apply(this, c)
        window.navigationBarColor = c
    }

    private fun plainEdit(hint: String, sp: Float, bold: Boolean = false, edit: EditText = EditText(this)): EditText = edit.apply {
        this.hint = hint
        setHintTextColor(NoteViews.muted(note.color))
        setTextColor(C.TEXT)
        textSize = sp
        typeface = if (bold) Fonts.head(this@NoteEditActivity) else Fonts.body(this@NoteEditActivity)
        background = null
        setPadding(0, dp(8), 0, dp(8))
        setLineSpacing(0f, 1.2f)
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    private fun buildBody(col: LinearLayout) {
        val et = plainEdit("Tulis catatan…", 16f).apply {
            setText(note.body)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            minHeight = dp(160)
            addTextChangedListener(watch { t -> if (!muting) { note.body = t; changed() } })
        }
        bodyEt = et
        col.add(et, top = 8, h = 0, weight = 1f)
    }

    private fun buildItems(col: LinearLayout) {
        if (note.items.isEmpty()) note.items.add(NoteItem(""))
        val box = vbox()
        itemsBox = box
        col.add(box, top = 6)
        note.items.forEach { addRowView(it, rows.size) }
        val mu = NoteViews.muted(note.color)
        val add = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            background = rippleBg(14)
            isClickable = true; isFocusable = true
            contentDescription = "Tambah item"
            setOnClickListener {
                val r = insertItem(rows.size, "")
                r.et.requestFocus()
                showIme(r.et)
            }
        }
        add.add(icon(R.drawable.ic_plus, mu, 22).apply { setPadding(dp(11), dp(11), dp(11), dp(11)) }, w = dp(44), h = dp(44))
        add.add(tv("Tambah item", 16f, mu).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, w = WRAP, side = 4)
        col.add(add, top = 2)
        // ruang kosong di bawah daftar: ketuk = item baru (seperti mengetuk kertas kosong)
        col.add(View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener {
                val last = rows.lastOrNull()
                if (last != null && last.et.length() == 0) { last.et.requestFocus(); showIme(last.et) }
                else { val r = insertItem(rows.size, ""); r.et.requestFocus(); showIme(r.et) }
            }
        }, h = 0, weight = 1f)
    }

    private fun addRowView(item: NoteItem, at: Int): Row {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val box = ImageView(this).apply {
            val p = dp(11); setPadding(p, p, p, p)
            background = rippleBg(100)
            isClickable = true; isFocusable = true
        }
        val et = ItemEdit(this)
        plainEdit("Item", 16f, edit = et)
        et.setPadding(dp(4), dp(10), dp(4), dp(10))
        et.setText(item.text)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.setHorizontallyScrolling(false)
        et.maxLines = Int.MAX_VALUE
        et.imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val del = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(NoteViews.muted(note.color))
            val p = dp(13); setPadding(p, p, p, p)
            background = rippleBg(100)
            contentDescription = "Hapus item"
            isClickable = true; isFocusable = true
            visibility = View.INVISIBLE
        }
        row.add(box, w = dp(44), h = dp(44))
        row.add(et, w = 0, weight = 1f)
        row.add(del, w = dp(44), h = dp(44))
        val r = Row(item, row, box, et, del)
        box.setOnClickListener { toggleItem(r) }
        del.setOnClickListener { removeRow(r) }
        et.onBackAtStart = { backAtStart(r) }
        et.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_NEXT || action == EditorInfo.IME_ACTION_DONE ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { splitAtCursor(r); true }
            else false
        }
        et.setOnFocusChangeListener { _, has -> del.visibility = if (has) View.VISIBLE else View.INVISIBLE }
        et.addTextChangedListener(watch { t -> if (!muting) itemTyped(r, t) })
        rows.add(at, r)
        itemsBox?.addView(row, at, LinearLayout.LayoutParams(MATCH, WRAP))
        paintRow(r)
        return r
    }

    private fun paintRow(r: Row) {
        val mu = NoteViews.muted(note.color)
        val done = r.item.done
        r.box.setImageResource(if (done) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox)
        r.box.setColorFilter(if (done) mu else C.TEXT)
        r.box.contentDescription = if (done) "Lepas centang" else "Centang"
        r.et.setTextColor(if (done) mu else C.TEXT)
        r.et.paintFlags = if (done) r.et.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else r.et.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
    }

    private fun paintPin() {
        pinBtn.setColorFilter(if (note.pinned) C.PRIMARY else C.TEXT)
        pinBtn.background = rippleBg(100, if (note.pinned) C.PRIMARY_C else null)
        val d = if (note.pinned) "Lepas sematan" else "Sematkan"
        pinBtn.contentDescription = d
        pinBtn.tooltipText = d
    }

    private fun paintMeta() {
        if (!::meta.isInitialized) return
        val parts = ArrayList<String>()
        if (note.checklist) {
            val (done, total) = note.progress()
            if (total > 0) parts.add("$done/$total selesai")
        }
        parts.add(if (savedAt == 0L) "Belum tersimpan" else NoteViews.edited(note))
        meta.text = parts.joinToString(" · ")
    }

    /** Fokus ke isi: awal (dari judul) atau akhir (membuka catatan baru). */
    private fun focusContent(start: Boolean) {
        val target: EditText = bodyEt ?: rows.firstOrNull()?.et ?: titleEt
        target.requestFocus()
        target.setSelection(if (start) 0 else target.length())
        showIme(target)
    }

    private fun showIme(v: View) {
        v.postDelayed({
            if (isFinishing || !v.isAttachedToWindow) return@postDelayed
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }, 120L)
    }

    // ------------------------------------------------------------------ daftar centang

    private fun setRowText(r: Row, t: String) {
        muting = true
        try { r.et.setText(t) } finally { muting = false }
        r.item.text = t
    }

    private fun itemTyped(r: Row, t: String) {
        if (t.contains('\n')) {
            // tempel beberapa baris / papan ketik yang mengirim baris baru: tiap baris jadi item sendiri
            val parts = t.split('\n')
            setRowText(r, parts[0])
            var at = rows.indexOf(r)
            var last = r
            val rest = parts.drop(1)
            rest.forEachIndexed { k, p ->
                val item = Note.parseLine(p)
                if (item.text.isEmpty() && k < rest.lastIndex) return@forEachIndexed   // baris kosong di tengah tempelan
                at++
                last = insertItem(at, item.text, item.done)
            }
            last.et.requestFocus()
            last.et.setSelection(last.et.length())
            changed()
            return
        }
        r.item.text = t
        changed()
    }

    private fun insertItem(at: Int, text: String, done: Boolean = false): Row {
        val item = NoteItem(text, done)
        val i = at.coerceIn(0, note.items.size)
        note.items.add(i, item)
        val r = addRowView(item, i)
        changed()
        return r
    }

    private fun splitAtCursor(r: Row) {
        val e = r.et
        val t = e.text.toString()
        val pos = e.selectionStart.coerceIn(0, t.length)
        if (t.isBlank() && rows.indexOf(r) == rows.lastIndex) {
            // Enter di item kosong terakhir: selesai menulis daftar
            hideIme(e)
            return
        }
        val head = t.substring(0, pos).trimEnd()
        val tail = t.substring(pos).trimStart()
        if (head != t) setRowText(r, head)
        val nr = insertItem(rows.indexOf(r) + 1, tail)
        nr.et.requestFocus()
        nr.et.setSelection(0)
    }

    private fun hideIme(v: View) {
        v.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Hapus di awal item: item kosong dihapus, item berisi digabung ke item sebelumnya. */
    private fun backAtStart(r: Row): Boolean {
        val i = rows.indexOf(r)
        if (i <= 0) return false
        scroll.post {
            val j = rows.indexOf(r)
            if (j <= 0) return@post
            val prev = rows[j - 1]
            val t = r.et.text.toString()
            val join = prev.et.length()
            prev.et.requestFocus()
            if (t.isNotEmpty()) prev.et.append(t)
            prev.et.setSelection(join.coerceAtMost(prev.et.length()))
            dropRow(j)
        }
        return true
    }

    private fun removeRow(r: Row) {
        val i = rows.indexOf(r)
        if (i < 0) return
        if (rows.size == 1) {
            // daftar tidak pernah kosong sama sekali: item terakhir dikosongkan saja
            setRowText(r, "")
            r.item.done = false
            paintRow(r)
            changed()
            return
        }
        val target = rows.getOrNull(i - 1) ?: rows.getOrNull(i + 1)
        if (r.et.hasFocus() && target != null) { target.et.requestFocus(); target.et.setSelection(target.et.length()) }
        dropRow(i)
    }

    private fun dropRow(i: Int) {
        val r = rows.removeAt(i)
        note.items.removeAt(i)
        itemsBox?.removeView(r.view)
        changed()
        paintMeta()
    }

    private fun toggleItem(r: Row) {
        r.item.done = !r.item.done
        haptic(r.box)
        r.box.boing(0.8f)
        paintRow(r)
        changed()
        paintMeta()
    }

    // ------------------------------------------------------------------ aksi bilah atas

    private fun togglePin() {
        note.pinned = !note.pinned
        haptic(pinBtn)
        paintPin()
        toast(if (note.pinned) "Disematkan di atas" else "Sematan dilepas")
        changed()
    }

    private fun toggleMode() {
        if (note.checklist) note.toText() else note.toChecklist()
        changed()
        build(focus = true)
    }

    private fun colorSheet() {
        sheet { body, dismiss ->
            sheetHeader(body, R.drawable.ic_palette, C.AMBER, C.AMBER_BG, "Warna catatan", "Warna kertas di aplikasi & widget", dismiss)
            val pad = vbox().apply { setPadding(dp(20), dp(4), dp(20), dp(8)) }
            pad.add(noteSwatches(note.color) { c ->
                dismiss()
                if (c != note.color) {
                    note.color = c
                    changed()
                    val y = scroll.scrollY
                    build(focus = false)
                    scroll.post { scroll.scrollTo(0, y) }
                }
            })
            body.add(pad)
            body.add(View(this), h = dp(16))
        }
    }

    private fun more() {
        sheet { body, dismiss ->
            sheetHeader(body, if (note.checklist) R.drawable.ic_checklist else R.drawable.ic_note, C.AMBER, C.AMBER_BG,
                NoteViews.headline(note), if (savedAt == 0L) "Belum tersimpan" else NoteViews.edited(note), dismiss)
            val pad = vbox().apply { setPadding(dp(20), dp(4), dp(20), dp(8)) }
            pad.add(actionRow(R.drawable.ic_widget, "Pasang di layar utama", "Jadikan widget, daftar centang bisa dicentang dari sana",
                C.AMBER, C.AMBER_BG) {
                dismiss()
                saveNow()
                if (note.blank) toast("Tulis sesuatu dulu") else pinNoteToHome(note.id)
            })
            pad.add(actionRow(R.drawable.ic_share, "Bagikan", "Kirim sebagai teks", C.PRIMARY, C.PRIMARY_C) { dismiss(); shareNote(note) }, top = 8)
            pad.add(actionRow(R.drawable.ic_copy, "Salin teks", "Tempel di aplikasi lain", C.PRIMARY, C.PRIMARY_C) { dismiss(); copyNote(note) }, top = 8)
            pad.add(actionRow(R.drawable.ic_trash, "Hapus", "Hapus catatan dari HP ini", C.ERROR, C.ERROR_C) {
                dismiss()
                if (savedAt == 0L && fresh) { deleted = true; finish(); return@actionRow }
                confirmDeleteNote(note) { deleted = true; dirty = false; toast("Catatan dihapus"); finish() }
            }, top = 8)
            body.add(pad)
            body.add(View(this), h = dp(12))
        }
    }
}
