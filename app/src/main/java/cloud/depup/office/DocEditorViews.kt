package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/** Tipe huruf tampilan untuk nama font Word (Calibri, Times New Roman, ...). */
object DocFonts {
    private val cache = HashMap<String, Typeface>()
    // nama font Word → keluarga generik; dipanggil sangat sering saat mengukur teks, jadi tanpa alokasi string
    private val generic = HashMap<String?, String>()
    private val byName = HashMap<String?, Array<Typeface?>>()
    fun get(name: String?, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val slots = byName.getOrPut(name) { arrayOfNulls(4) }
        slots[style]?.let { return it }
        val g = generic.getOrPut(name) { Fonts2.generic(name) }
        val tf = cache.getOrPut("$g/$style") { Typeface.create(Typeface.create(g, Typeface.NORMAL), style) }
        slots[style] = tf
        return tf
    }
}

/** Satu rentang teks dengan format karakter Word. Tampilan = gaya paragraf + format ini. */
class FmtSpan(val fmt: CharFmt, private val host: ParaEdit) : MetricAffectingSpan() {
    // Hasil gabungan gaya dasar + format disimpan; dihitung ulang hanya bila gaya dasar / zoom berubah.
    // (Dipanggil untuk tiap potongan teks setiap kali diukur & digambar — termasuk tiap kedip kursor.)
    private var cBase: CharFmt? = null
    private var cPt = -1f
    private var cEff: CharFmt = CharFmt.EMPTY
    private var cSize = 0f
    private var cTf: Typeface = Typeface.DEFAULT

    private fun eff(): CharFmt {
        val b = host.base
        if (b !== cBase || host.ptPx != cPt) {
            cBase = b; cPt = host.ptPx
            val e = b.over(fmt)
            cEff = e
            var size = cPt * (e.sz ?: 22) / 2f
            if (e.va == 1 || e.va == 2) size *= 0.68f
            cSize = size
            cTf = DocFonts.get(e.font, e.b == true, e.i == true)
        }
        return cEff
    }

    override fun updateMeasureState(tp: TextPaint) = metrics(tp)

    override fun updateDrawState(tp: TextPaint) {
        metrics(tp)
        val e = cEff
        val c = e.color
        tp.color = if (c == null || c == CharFmt.AUTO) host.autoColor else c
        val bg = e.bg
        tp.bgColor = if (bg == null || bg == CharFmt.NONE) 0 else bg
        tp.isUnderlineText = e.u == true
        tp.isStrikeThruText = e.s == true
    }

    private fun metrics(tp: TextPaint) {
        val e = eff()
        tp.textSize = cSize
        tp.typeface = cTf
        if (e.va == 1) tp.baselineShift += (tp.ascent() / 2).toInt()
        if (e.va == 2) tp.baselineShift -= (tp.ascent() / 2).toInt()
    }
}

/**
 * Editor satu paragraf Word.
 * - Enter = paragraf baru (dipecah oleh activity), tombol "Baris Baru" = pindah baris di paragraf yang sama.
 * - Backspace di awal paragraf = gabung dengan paragraf sebelumnya.
 */
@SuppressLint("ViewConstructor")
class ParaEdit(ctx: Context, private val host: DocEditorActivity) : androidx.appcompat.widget.AppCompatEditText(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val t = System.nanoTime()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        EdPerf.paraMeasureNs += System.nanoTime() - t; EdPerf.paraMeasures++
    }

    override fun requestLayout() {
        EdPerf.paraRequests++
        // contoh siapa yang meminta ukur ulang (diagnosis): ambil sesekali, buang bingkai framework yang tak berguna
        if (EdPerf.paraRequests % 40 == 1) EdPerf.reqWho = Throwable().stackTrace.drop(1)
            .filter { !it.className.startsWith("android.widget.TextView") && !it.className.startsWith("android.view.View") }
            .take(4).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }
        super.requestLayout()
    }

    override fun onDraw(canvas: Canvas) {
        val t = System.nanoTime()
        super.onDraw(canvas)
        EdPerf.paraDrawNs += System.nanoTime() - t; EdPerf.paraDraws++
    }

    companion object Follow {
        const val BASE_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        /** Saat terakhir teks/kursor paragraf mana pun berubah (uptime ms) — lihat [EditorScroll]. */
        @JvmStatic var lastChange = 0L
        /** Uji performa: ejaan aktif di semua paragraf (perilaku lama). */
        @JvmStatic var spellAll = false
        /**
         * Perbaikan lag gulir (lihat [onCheckIsTextEditor]). false = perilaku lama, hanya untuk uji A/B.
         */
        @JvmStatic var trackGuard = true
    }

    /**
     * PENYEBAB LAG: Android 15 melacak SETIAP kolom teks yang mengaku "editor" untuk tulisan-tangan stylus
     * (TextView.shouldTrackHandwritingArea → onCheckIsTextEditor, tetap aktif walau tulisan-tangan dimatikan, demi
     * pesan "tidak didukung"). Kolom yang dilacak dipasangi pendengar posisi RenderNode: tiap kali bergeser satu
     * piksel, ia memposting pembaruan → ViewRootImpl memeriksa ulang SEMUA kolom terlacak (MSG 30 & 35, gesture
     * exclusion + keep-clear). Ratusan paragraf/sel × ratusan pesan per frame = kuadratik → < 1 fps saat digulir.
     *
     * Paragraf yang tidak sedang diketik tidak perlu dianggap editor: keyboard (IMM) hanya bertanya pada kolom yang
     * fokus, dan ketukan memakai isTextEditable(), bukan fungsi ini.
     */
    override fun requestFocus(direction: Int, previouslyFocusedRect: android.graphics.Rect?): Boolean {
        if (!isAttachedToWindow) ensureAttached(this)
        return super.requestFocus(direction, previouslyFocusedRect)
    }

    override fun onCheckIsTextEditor(): Boolean {
        if (trackGuard && !isFocused) { EdPerf.editorChecks++; return false }
        return super.onCheckIsTextEditor()
    }

    lateinit var para: EPara
    var base: CharFmt = CharFmt(sz = 22)
    var ptPx = 1f
    var autoColor = Color.BLACK
    var suppress = false
    /** Isi editor berbeda dari [para] (perlu sync). Editor bersih tidak disalin ulang saat membuat titik urungkan. */
    var dirty = false
    /** Naik setiap isi berubah — dipakai untuk cache hitungan kata. */
    var version = 0
    private var binding = false
    /** Inden baris pertama yang diterapkan ulang setiap kali teks diikat. */
    private var firstLinePx = 0
    private var insStart = 0
    private var insCount = 0
    private var firstLineSpan: LeadingMarginSpan.Standard? = null

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (!suppress && ::para.isInitialized) host.beforeEdit(this@ParaEdit)
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            insStart = start; insCount = count
        }

        override fun afterTextChanged(e: Editable) {
            if (!binding) { dirty = true; version++; lastChange = android.os.SystemClock.uptimeMillis() }
            if (suppress || !::para.isInitialized) return
            host.pending?.let { pd ->
                if (pd.editor === this@ParaEdit && insCount > 0) {
                    val f = readFmts()
                    for (k in insStart until minOf(f.size, insStart + insCount)) f[k] = pd.fmt
                    setFmts(f)
                    host.pending = null
                }
            }
            normalize()
            val end = minOf(e.length, insStart + insCount)
            val hasNl = insCount > 0 && (insStart until end).any { e[it] == '\n' }
            if (hasNl) {
                if (host.softBreak) host.softBreak = false
                else { val s0 = insStart; val c0 = insCount; post { host.splitAtNewlines(this@ParaEdit, s0, c0) } }
            }
            host.afterEdit(this@ParaEdit)
        }
    }

    init {
        background = null
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        // lanskap: keyboard tidak mengambil alih layar penuh — dokumen tetap terlihat saat mengetik
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isSingleLine = false
        setHorizontallyScrolling(false)
        // Pemeriksa ejaan HANYA untuk paragraf yang sedang diketik. Setiap EditText yang tampil otomatis mengirim
        // seluruh teksnya ke pemeriksa ejaan keyboard (Gboard) — dokumen 600-an paragraf = ratusan sesi pemeriksaan,
        // lalu garis bawah hasilnya terus menempel → tata letak dihitung ulang tanpa henti: editor patah-patah parah
        // bahkan untuk berkas ratusan KB. Saran keyboard tetap jalan normal saat paragraf difokuskan.
        setRawInputType(if (spellAll) BASE_TYPE else BASE_TYPE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
        // Android 13+: tiap kolom teks didaftarkan ke pelacak tulisan-tangan stylus dan posisinya diperbarui setiap
        // kali bergeser — ratusan paragraf/sel ⇒ kerja besar di thread UI pada SETIAP frame gulir. Tidak dipakai di sini.
        if (android.os.Build.VERSION.SDK_INT >= 33) isAutoHandwritingEnabled = false
        minLines = 1
        setPadding(0, 0, 0, 0)
        highlightColor = 0x553B5BFF
        addTextChangedListener(watcher)
        setOnKeyListener { _, kc, ev ->
            if (kc == KeyEvent.KEYCODE_DEL && ev.action == KeyEvent.ACTION_DOWN && selectionStart == 0 && selectionEnd == 0) {
                host.backspaceAtStart(this); true
            } else false
        }
    }

    fun bind(p: EPara) {
        para = p
        suppress = true; binding = true
        val sb = SpannableStringBuilder(p.text)
        setText(sb, BufferType.EDITABLE)
        setFmts(p.fmts.copyOf())
        applyFirstLine()
        suppress = false; binding = false
        dirty = false; version++
    }

    /** Format per karakter dari span (span yang mulai paling akhir menang bila tumpang tindih). */
    fun readFmts(): Array<CharFmt> {
        val e = text ?: return emptyArray()
        val out = Array(e.length) { CharFmt.EMPTY }
        val spans = e.getSpans(0, e.length, FmtSpan::class.java).sortedBy { e.getSpanStart(it) }
        for (s in spans) {
            val a = e.getSpanStart(s); val b = e.getSpanEnd(s)
            for (k in a until minOf(b, out.size)) out[k] = s.fmt
        }
        return out
    }

    /** Ganti semua span format dengan segmen-segmen dari [f]. */
    fun setFmts(f: Array<CharFmt>, keepEmpty: CharFmt? = null) {
        val e = text ?: return
        val wasSuppress = suppress
        suppress = true
        if (!binding) { dirty = true; version++ }
        e.getSpans(0, e.length, FmtSpan::class.java).forEach { e.removeSpan(it) }
        var k = 0
        val n = minOf(f.size, e.length)
        while (k < n) {
            var j = k
            while (j < n && f[j] == f[k]) j++
            if (f[k] != CharFmt.EMPTY) e.setSpan(FmtSpan(f[k], this), k, j,
                if (k == 0) Spanned.SPAN_INCLUSIVE_INCLUSIVE else Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            k = j
        }
        if (e.isEmpty() && keepEmpty != null && keepEmpty != CharFmt.EMPTY) e.setSpan(FmtSpan(keepEmpty, this), 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        suppress = wasSuppress
    }

    /** Rapikan span yang tumpang tindih / kosong setelah pengetikan. */
    private fun normalize() {
        val e = text ?: return
        val spans = e.getSpans(0, e.length, FmtSpan::class.java).sortedBy { e.getSpanStart(it) }
        var need = false
        var lastEnd = -1
        for (s in spans) {
            val a = e.getSpanStart(s); val b = e.getSpanEnd(s)
            if ((a == b && e.isNotEmpty()) || a < lastEnd) { need = true; break }
            lastEnd = b
        }
        if (!need) return
        val keep = if (e.isEmpty()) spans.lastOrNull()?.fmt else null
        setFmts(readFmts(), keep)
    }

    fun sync() {
        para.text = text?.toString().orEmpty()
        para.fmts = readFmts()
        dirty = false
    }

    /** Inden baris pertama positif (gantung ditangani oleh label daftar / padding). */
    fun updateFirstLine(px: Int) {
        firstLinePx = px
        applyFirstLine()
    }

    private fun applyFirstLine() {
        val px = firstLinePx
        val e = text ?: return
        firstLineSpan?.let { e.removeSpan(it) }
        firstLineSpan = null
        if (px > 0) {
            val s = LeadingMarginSpan.Standard(px, 0)
            firstLineSpan = s
            val was = suppress; suppress = true
            e.setSpan(s, 0, e.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            suppress = was
        }
    }

    /** Ukur ulang setelah gaya dasar paragraf berubah. */
    fun relayout() {
        val s = selectionStart; val en = selectionEnd
        val was = suppress; suppress = true
        val cur = text
        setText(cur, BufferType.EDITABLE)
        if (s >= 0) runCatching { setSelection(s.coerceAtMost(length()), en.coerceAtMost(length())) }
        suppress = was
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (::para.isInitialized && !suppress) { lastChange = android.os.SystemClock.uptimeMillis(); host.onSel(this) }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        // fokus: saran & ejaan keyboard aktif; lepas fokus: dimatikan lagi (lihat init) dan garis ejaannya dibuang
        setRawInputType(if (focused || spellAll) BASE_TYPE else BASE_TYPE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        if (!focused && !spellAll) text?.let { t -> for (sp in t.getSpans(0, t.length, android.text.style.SuggestionSpan::class.java)) t.removeSpan(sp) }
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        // lepas fokus: hitung ulang pelacakan (menyetel ini memanggil updatePositionUpdateListener) → pendengar dibuang
        if (!focused && trackGuard && android.os.Build.VERSION.SDK_INT >= 33) isAutoHandwritingEnabled = false
        if (focused && ::para.isInitialized) host.onSel(this)
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        if (id == android.R.id.paste) super.onTextContextMenuItem(android.R.id.pasteAsPlainText) else super.onTextContextMenuItem(id)

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && selectionStart == 0 && selectionEnd == 0) { host.backspaceAtStart(this@ParaEdit); return true }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 && selectionEnd == 0) {
                    host.backspaceAtStart(this@ParaEdit); return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}

/** Ikon garis sederhana untuk pita menu (rata, daftar, inden, spasi, halaman, tabel...). */
class GlyphIcon(ctx: Context, private val kind: Int, private val tint: Int = C.TEXT) : View(ctx) {
    companion object {
        const val ALIGN_L = 0; const val ALIGN_C = 1; const val ALIGN_R = 2; const val ALIGN_J = 3
        const val BULLET = 4; const val NUMBER = 5; const val INDENT_IN = 6; const val INDENT_OUT = 7
        const val SPACING = 8; const val MARGIN = 9; const val PAGE = 10; const val ORIENT = 11
        const val TABLE = 12; const val BREAK = 13; const val ROW_ADD = 14; const val ROW_DEL = 15
        const val COL_ADD = 16; const val COL_DEL = 17; const val PARA_SPACE = 18; const val STYLE = 19
    }

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; strokeWidth = 1.8f * d; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.FILL; textSize = 7.5f * d; typeface = Typeface.DEFAULT_BOLD }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = w * 0.12f; val r = w * 0.88f; val t = h * 0.2f; val b = h * 0.8f
        val ys = (0 until 4).map { t + (b - t) * it / 3f }
        fun line(x1: Float, y: Float, x2: Float) = c.drawLine(x1, y, x2, y, p)
        when (kind) {
            ALIGN_L -> listOf(1f, .6f, 1f, .7f).forEachIndexed { i, f -> line(l, ys[i], l + (r - l) * f) }
            ALIGN_R -> listOf(1f, .6f, 1f, .7f).forEachIndexed { i, f -> line(r - (r - l) * f, ys[i], r) }
            ALIGN_C -> listOf(1f, .6f, .85f, .5f).forEachIndexed { i, f -> val m = (l + r) / 2; val hw = (r - l) * f / 2; line(m - hw, ys[i], m + hw) }
            ALIGN_J -> ys.forEach { line(l, it, r) }
            BULLET -> listOf(ys[0], (ys[1] + ys[2]) / 2, ys[3]).forEach { y -> c.drawCircle(l + 1.5f * d, y, 1.6f * d, fill); line(l + w * 0.25f, y, r) }
            NUMBER -> listOf(ys[0], (ys[1] + ys[2]) / 2, ys[3]).forEachIndexed { i, y -> c.drawText("${i + 1}", l - 1f * d, y + 2.6f * d, fill); line(l + w * 0.25f, y, r) }
            INDENT_IN, INDENT_OUT -> {
                line(l, ys[0], r); line(l + w * 0.38f, ys[1], r); line(l + w * 0.38f, ys[2], r); line(l, ys[3], r)
                val my = (ys[1] + ys[2]) / 2; val ax = l; val bx = l + w * 0.26f
                if (kind == INDENT_IN) { line(ax, my, bx); c.drawLine(bx, my, bx - 3 * d, my - 3 * d, p); c.drawLine(bx, my, bx - 3 * d, my + 3 * d, p) }
                else { line(ax, my, bx); c.drawLine(ax, my, ax + 3 * d, my - 3 * d, p); c.drawLine(ax, my, ax + 3 * d, my + 3 * d, p) }
            }
            SPACING, PARA_SPACE -> {
                ys.forEach { line(l + w * 0.32f, it, r) }
                val x = l + w * 0.1f
                c.drawLine(x, t - 1 * d, x, b + 1 * d, p)
                c.drawLine(x, t - 1 * d, x - 2.5f * d, t + 2 * d, p); c.drawLine(x, t - 1 * d, x + 2.5f * d, t + 2 * d, p)
                c.drawLine(x, b + 1 * d, x - 2.5f * d, b - 2 * d, p); c.drawLine(x, b + 1 * d, x + 2.5f * d, b - 2 * d, p)
            }
            MARGIN -> {
                c.drawRect(RectF(w * .22f, h * .1f, w * .78f, h * .9f), p)
                val dp = Paint(p).apply { pathEffect = DashPathEffect(floatArrayOf(2 * d, 2 * d), 0f); strokeWidth = 1.2f * d }
                c.drawRect(RectF(w * .32f, h * .22f, w * .68f, h * .78f), dp)
            }
            PAGE -> { c.drawRect(RectF(w * .25f, h * .1f, w * .75f, h * .9f), p); line(w * .34f, h * .35f, w * .66f); line(w * .34f, h * .5f, w * .66f); line(w * .34f, h * .65f, w * .56f) }
            ORIENT -> { c.drawRect(RectF(w * .12f, h * .3f, w * .7f, h * .75f), p); c.drawRect(RectF(w * .45f, h * .12f, w * .88f, h * .62f), p) }
            TABLE, ROW_ADD, ROW_DEL, COL_ADD, COL_DEL -> {
                val rr = RectF(l, t, r, b)
                c.drawRect(rr, p)
                line(l, t + (b - t) / 3, r); line(l, t + 2 * (b - t) / 3, r)
                c.drawLine(l + (r - l) / 3, t, l + (r - l) / 3, b, p); c.drawLine(l + 2 * (r - l) / 3, t, l + 2 * (r - l) / 3, b, p)
                val ap = Paint(fill).apply { color = if (kind == ROW_DEL || kind == COL_DEL) C.RED else C.TEAL; textSize = 11f * d }
                when (kind) {
                    ROW_ADD, COL_ADD -> c.drawText("+", r - 6 * d, b + 2 * d, ap)
                    ROW_DEL, COL_DEL -> c.drawText("−", r - 6 * d, b + 2 * d, ap)
                }
                if (kind == ROW_ADD || kind == ROW_DEL) c.drawRect(RectF(l, t + 2 * (b - t) / 3, r, b), Paint(fill).apply { alpha = 60 })
                if (kind == COL_ADD || kind == COL_DEL) c.drawRect(RectF(l + 2 * (r - l) / 3, t, r, b), Paint(fill).apply { alpha = 60 })
            }
            BREAK -> {
                c.drawRect(RectF(w * .25f, h * .05f, w * .75f, h * .4f), p)
                c.drawRect(RectF(w * .25f, h * .6f, w * .75f, h * .95f), p)
                val dp = Paint(p).apply { pathEffect = DashPathEffect(floatArrayOf(2 * d, 2 * d), 0f) }
                c.drawLine(w * .1f, h * .5f, w * .9f, h * .5f, dp)
            }
            STYLE -> { fill.textSize = 13f * d; c.drawText("Aa", l, b, fill) }
        }
    }
}


/**
 * Gulir dokumen editor. Kolom teks yang sedang fokus meminta "tampilkan kursor" setiap kali tata letaknya dihitung
 * ulang (gambar selesai dimuat, blok lanjutan dibangun, kedip pratinjau…) — dulu halaman terus MELOMPAT kembali ke
 * paragraf yang fokus saat digulir, terasa sangat patah-patah bahkan di dokumen ratusan KB. Permintaan itu kini hanya
 * dituruti bila memang ada ketikan / perpindahan kursor barusan, dan tidak pernah selagi jari menggulir atau halaman
 * masih meluncur.
 */
class EditorScroll(ctx: Context) : GlassScrollView(ctx) {
    private var touching = false
    private var lastMove = 0L

    /** Wadah zoom visual; bila dipasang, cubit dua jari memperbesar/memperkecil halaman. */
    var zoomFrame: ZoomFrame? = null
    private var pinching = false
    private val scaleDet = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            zoomFrame?.zoomBy(d.scaleFactor, d.focusX, d.focusY); return true
        }
    }).apply { isQuickScaleEnabled = false }

    /**
     * Cubit ditangani DI SINI sebelum sampai ke paragraf / gulir: begitu jari kedua turun, isi menerima CANCEL
     * (tidak ada kursor pindah / teks terpilih / gulir tak sengaja), dan semua gerakan sampai jari terakhir
     * terangkat dipakai untuk zoom.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val zf = zoomFrame ?: return super.dispatchTouchEvent(ev)
        scaleDet.onTouchEvent(ev)
        val m = ev.actionMasked
        if (!pinching && m == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            pinching = true; touching = true
            val c = android.view.MotionEvent.obtain(ev); c.action = android.view.MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(c); c.recycle()
        }
        if (pinching) {
            if (m == android.view.MotionEvent.ACTION_UP || m == android.view.MotionEvent.ACTION_CANCEL) {
                pinching = false; touching = false; lastMove = android.os.SystemClock.uptimeMillis()
                zf.settle()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean { track(ev); return super.onInterceptTouchEvent(ev) }
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean { track(ev); return super.onTouchEvent(ev) }
    private fun track(ev: android.view.MotionEvent) {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> touching = true
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { touching = false; lastMove = android.os.SystemClock.uptimeMillis() }
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        lastMove = android.os.SystemClock.uptimeMillis()   // termasuk luncuran (fling) setelah jari lepas
        if (t != oldt) Cull.scrolled()
    }

    override fun draw(canvas: Canvas) {
        val t = System.nanoTime()
        super.draw(canvas)
        EdPerf.drawNs += System.nanoTime() - t; EdPerf.draws++
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val t0 = System.nanoTime()
        super.onLayout(changed, l, t, r, b)
        EdPerf.layoutNs += System.nanoTime() - t0; EdPerf.layouts++
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val t0 = System.nanoTime()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        EdPerf.measureNs += System.nanoTime() - t0; EdPerf.measures++
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect, immediate: Boolean): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (touching) return false
        if (now - ParaEdit.lastChange > 700) return false              // bukan karena ketikan / kursor dipindah
        if (now - lastMove < 250 && ParaEdit.lastChange < lastMove) return false   // masih meluncur
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }
}

/**
 * Tata letak per lembar seperti Word: blok dokumen disusun ke lembar berukuran kertas ASLI (rasio & margin dari
 * dokumen) dengan jarak antarlembar. Blok yang tidak muat di sisa lembar pindah utuh ke lembar berikutnya; blok yang
 * lebih tinggi dari satu lembar (tabel/paragraf sangat panjang) dibiarkan melintas. Lembar putih digambar sendiri.
 * Margin kiri/kanan = padding; margin atas/bawah = [mTop]/[mBottom].
 */
class PagedBox(ctx: Context) : android.widget.LinearLayout(ctx), VirtHost {
    /** tinggi ÷ lebar kertas */
    var ratio = 16838f / 11906f
    var mTop = 0
    var mBottom = 0
    var gap = (14 * ctx.resources.displayMetrics.density).toInt()
    /** Mulai di lembar baru sebelum view ini (paragraf "pemisah halaman sebelum"). */
    val breakBefore: MutableSet<View> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    /** Lembar baru sesudah view ini (blok pemisah halaman). */
    val breakAfter: MutableSet<View> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    var pages = 1
        private set
    /** false = tanpa pemenggalan lembar (satu kolom panjang) — untuk pengujian performa. */
    var paged = true
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }
    private var tops = IntArray(64)
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x24000000 }
    private val shade = Paint().apply { color = 0x12000000 }

    init { orientation = VERTICAL; setWillNotDraw(false) }
    private val cull = CullHelper(this)

    override fun virtDetach(i: Int) {
        val c = getChildAt(i); val lp = c.layoutParams
        detachViewFromParent(i); removeDetachedView(c, false); attachViewToParent(c, i, lp)
    }
    override fun virtAttach(i: Int) {
        val c = getChildAt(i); val lp = c.layoutParams
        detachViewFromParent(i); addViewInLayout(c, i, lp, true)
    }
    override fun virtDrop(i: Int) { detachViewFromParent(i); requestLayout(); invalidate() }
    // Anak yang sedang terlepas (virtualisasi) sudah menerima "lepas dari jendela": buang tanpa melepasnya dua kali.
    override fun removeViewAt(index: Int) {
        val c = getChildAt(index)
        if (c != null && !c.isAttachedToWindow && isAttachedToWindow) virtDrop(index) else super.removeViewAt(index)
    }
    override fun removeView(view: View) {
        val i = indexOfChild(view)
        if (i >= 0 && !view.isAttachedToWindow && isAttachedToWindow) virtDrop(i) else super.removeView(view)
    }
    override fun removeViewInLayout(view: View) {
        val i = indexOfChild(view)
        if (i >= 0 && !view.isAttachedToWindow && isAttachedToWindow) { detachViewFromParent(i) } else super.removeViewInLayout(view)
    }
    override fun addFocusables(views: java.util.ArrayList<View>, direction: Int, focusableMode: Int) {
        super.addFocusables(views, direction, focusableMode)
        views.removeAll { !it.isAttachedToWindow && isAttachedToWindow }
    }
    override fun addChildrenForAccessibility(outChildren: java.util.ArrayList<View>) {
        super.addChildrenForAccessibility(outChildren)
        if (isAttachedToWindow) outChildren.removeAll { !it.isAttachedToWindow }
    }
    override fun removeAllViews() { cull.dropDetached(); super.removeAllViews() }

    override fun dispatchDraw(canvas: Canvas) {
        val t = System.nanoTime()
        cull.begin()
        super.dispatchDraw(canvas)
        EdPerf.boxDrawNs += System.nanoTime() - t; EdPerf.boxDraws++
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (cull.skip(child)) { EdPerf.culled++; return false }
        EdPerf.drawnKids++
        return super.drawChild(canvas, child, drawingTime)
    }

    fun pageH(w: Int = width): Int = Math.round(w * ratio)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val ph = if (paged) pageH(w) else Int.MAX_VALUE / 4; val stride = ph + gap
        val bodyH = (ph - mTop - mBottom).coerceAtLeast(1)
        val unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        if (tops.size < childCount) tops = IntArray(childCount + 64)
        var page = 0
        var y = mTop
        var force = false
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChildWithMargins(c, widthMeasureSpec, 0, unspec, 0)
            val lp = c.layoutParams as LayoutParams
            val h = c.measuredHeight + lp.topMargin + lp.bottomMargin
            val atTop = y == page * stride + mTop
            if (!atTop && (force || c in breakBefore || (y + h > page * stride + ph - mBottom && h <= bodyH))) {
                page++; y = page * stride + mTop
            }
            tops[i] = y + lp.topMargin
            y += h
            force = c in breakAfter
            // blok yang melintasi lembar: lanjut dari lembar tempat ia berakhir
            while (y > page * stride + ph - mBottom) page++
            if (y < page * stride + mTop) y = page * stride + mTop
        }
        pages = page + 1
        setMeasuredDimension(w, if (paged) pages * stride - gap else y + mBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inner = r - l - paddingLeft - paddingRight
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams as LayoutParams
            val cw = c.measuredWidth
            val x = when ((if (lp.gravity >= 0) lp.gravity else 0) and android.view.Gravity.HORIZONTAL_GRAVITY_MASK) {
                android.view.Gravity.CENTER_HORIZONTAL -> paddingLeft + (inner - cw) / 2 + lp.leftMargin - lp.rightMargin
                android.view.Gravity.RIGHT, android.view.Gravity.END -> paddingLeft + inner - cw - lp.rightMargin
                else -> paddingLeft + lp.leftMargin
            }
            c.layout(x, tops[i], x + cw, tops[i] + c.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!paged) { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paper); return }
        val ph = pageH(); val stride = ph + gap
        val w = width.toFloat()
        for (p in 0 until pages) {
            val top = (p * stride).toFloat()
            canvas.drawRect(1f, top + 2f, w + 1f, top + ph + 2f, shade)
            canvas.drawRect(0f, top, w, top + ph, paper)
            canvas.drawRect(0.5f, top + 0.5f, w - 0.5f, top + ph - 0.5f, edge)
        }
    }
}


/**
 * Gambar hanya anak yang dekat layar. Dokumen ratusan paragraf = ribuan view; tanpa ini SETIAP frame gulir merekam
 * dan menyinkronkan seluruh pohon RenderNode ke GPU (thread UI menunggu), padahal yang tampil ± sepersepuluhnya.
 * Anak yang dilewati tetap ada & tetap ditata (kursor, fokus, ukuran lembar tidak berubah) — hanya tidak digambar.
 * Jendela gambar = layar ± 1 layar; begitu gulir mendekati tepi jendela, wadah digambar ulang dengan jendela baru.
 */
object Cull {
    @JvmStatic var on = true
    private val hosts = java.util.WeakHashMap<CullHelper, Boolean>()
    fun register(h: CullHelper) { hosts[h] = true }
    fun scrolled() { if (on) for (h in hosts.keys.toList()) h.recheck() }
    fun invalidateAll() { for (h in hosts.keys.toList()) { h.v.invalidate(); h.scheduleSync() } }
    /** Activity selesai: buang anak terlepas dulu supaya pembongkaran jendela tidak "melepas" mereka dua kali. */
    fun releaseAll() { for (h in hosts.keys.toList()) try { h.dropDetached() } catch (_: Throwable) {} }
    /** Jumlah anak terlepas (diagnosis). */
    fun detachedCount(): Int = hosts.keys.toList().sumOf { h -> (0 until h.v.childCount).count { !h.v.getChildAt(it).isAttachedToWindow } }
}

/** Wadah yang bisa melepas anak dari jendela tanpa mengeluarkannya dari daftar anak (lihat [CullHelper.syncAttach]). */
interface VirtHost {
    fun virtDetach(i: Int)
    fun virtAttach(i: Int)
    /** Keluarkan anak yang SUDAH terlepas dari daftar anak, tanpa pemberitahuan "lepas" kedua. */
    fun virtDrop(i: Int)
}

/**
 * Pasang ulang [v] (dan leluhurnya yang terlepas) ke jendela — sebelum fokus/kursor dipindah ke sana.
 */
fun ensureAttached(v: View) {
    var c: View = v
    var p = c.parent
    while (p is View) {
        if (p is VirtHost && p is android.view.ViewGroup && p.isAttachedToWindow && !c.isAttachedToWindow) {
            val i = p.indexOfChild(c)
            if (i >= 0) { p.virtAttach(i); c.requestLayout() }
        }
        c = p; p = c.parent
    }
}

class CullHelper(val v: android.view.ViewGroup) {
    private var lo = 0; private var hi = -1; private var active = false
    private val loc = IntArray(2)
    init { Cull.register(this) }

    /** Skala total (zoom visual) dari [v] ke layar. */
    private fun scale(): Float {
        var s = 1f; var c: View = v
        while (true) { s *= c.scaleY; c = c.parent as? View ?: break }
        return if (s > 0.01f) s else 1f
    }
    private var marginLocal = 0

    /** Rentang layar dalam koordinat [v]: [top, bottom]; false bila belum terpasang. */
    private fun span(out: IntArray): Boolean {
        if (!v.isAttachedToWindow) return false
        v.getLocationInWindow(loc)
        val h = v.rootView.height
        if (h <= 0) return false
        val s = scale()
        out[0] = (-loc[1] / s).toInt(); out[1] = ((h - loc[1]) / s).toInt()
        marginLocal = (h / s).toInt()
        return true
    }
    private val tmp = IntArray(2)

    /** Dipanggil di awal dispatchDraw: tetapkan jendela gambar untuk rekaman ini. */
    fun begin() {
        scheduleSync()
        active = false
        if (!Cull.on || !span(tmp)) return
        val m = marginLocal
        lo = tmp[0] - m; hi = tmp[1] + m
        active = true
    }

    fun skip(c: View): Boolean {
        if (!c.isAttachedToWindow && v.isAttachedToWindow) { scheduleSync(); return true }   // terlepas: tak ada isi untuk digambar
        if (!active) return false
        val t = c.top + c.translationY; val b = c.bottom + c.translationY
        return b < lo || t > hi
    }

    // ---- virtualisasi: lepas dari jendela anak yang jauh dari layar ----
    // Android memasang pendengar posisi (Editor.PositionListener) pada SETIAP kolom teks yang terpasang di jendela;
    // tiap frame ia menghitung posisi kolom itu menelusuri ±20 tingkat view — 662 paragraf ⇒ ±20 ms/frame terbuang
    // (hasilnya hanya dipakai untuk kolom yang sedang diketik). Anak yang terlepas tetap di daftar anak (indeks &
    // tata letak tak berubah, kode penyuntingan tak terganggu), hanya tidak terdaftar di jendela.
    private var pending = false
    private val syncRun = Runnable { pending = false; syncAttach() }
    fun scheduleSync() { if (!pending && v.isAttachedToWindow) { pending = true; v.postOnAnimation(syncRun) } }

    fun syncAttach() {
        val host = v as? VirtHost ?: return
        if (!v.isAttachedToWindow) return
        var attached = 0
        if (!Cull.on) {
            for (i in 0 until v.childCount) if (!v.getChildAt(i).isAttachedToWindow) { host.virtAttach(i); v.getChildAt(i).requestLayout(); attached++ }
            if (attached > 0) v.invalidate()
            return
        }
        if (!span(tmp)) return
        val m = marginLocal + marginLocal / 2
        val lo2 = tmp[0] - m; val hi2 = tmp[1] + m
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c.visibility == View.GONE) continue
            val near = c.bottom >= lo2 && c.top <= hi2
            val on = c.isAttachedToWindow
            if (near && !on) { host.virtAttach(i); c.requestLayout(); attached++; EdPerf.virtAttach++ }
            else if (!near && on && !c.hasFocus() && c.isLaidOut) { host.virtDetach(i); EdPerf.virtDetach++ }
        }
        if (attached > 0) v.invalidate()
    }

    /** Sebelum seluruh anak dibuang: keluarkan dulu anak yang terlepas dengan aman (tanpa "lepas" dua kali). */
    fun dropDetached() {
        val host = v as? VirtHost
        for (i in v.childCount - 1 downTo 0) {
            val c = v.getChildAt(i)
            if (!c.isAttachedToWindow) { if (host != null && v.isAttachedToWindow) host.virtDrop(i) else v.removeViewInLayout(c) }
        }
    }

    /** Setelah gulir: bila layar tinggal setengah layar dari tepi jendela yang terekam, rekam ulang. */
    fun recheck() {
        if (!active || !v.isShown || !span(tmp)) return
        val half = marginLocal / 2
        val hgt = v.height
        val needLo = (tmp[0] - half).coerceIn(0, hgt); val needHi = (tmp[1] + half).coerceIn(0, hgt)
        if (needLo < lo.coerceIn(0, hgt) || needHi > hi.coerceIn(0, hgt)) { EdPerf.cullRedraws++; v.invalidate() }
        scheduleSync()
    }
}

/**
 * Zoom VISUAL seperti tampilan cetak Word: halaman ditata SEKALI pada 100% lalu hanya diperbesar/diperkecil sebagai
 * gambar (scale RenderNode). Dulu zoom menata & membangun ulang seluruh dokumen (ribuan view) setiap kali → macet.
 * Ukuran wadah = isi × zoom, jadi gulir tegak & mendatar ([hScroll]) tetap pas. Titik di bawah jari tetap di bawah
 * jari selama dicubit.
 */
class ZoomFrame(ctx: Context) : android.widget.FrameLayout(ctx) {
    var zoom = 1f
        private set
    var minZoom = 0.5f
    var maxZoom = 4f
    var onZoom: ((Float) -> Unit)? = null
    var vScroll: android.widget.ScrollView? = null
    var hScroll: android.widget.HorizontalScrollView? = null
    /** cx, cy (koordinat isi) dan fx, fy (posisi di layar gulir) yang harus tetap berimpit sesudah tata letak. */
    private var anchor: FloatArray? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val c = getChildAt(0)
        if (c == null) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        val un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        c.measure(un, un)                 // spesifikasi selalu sama → hasil ukur di-cache, zoom tidak mengukur ulang isi
        val cw = c.measuredWidth; val ch = c.measuredHeight
        var w = Math.round(cw * zoom); var h = Math.round(ch * zoom)
        w = maxOf(w, if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else cw)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) h = maxOf(h, MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val c = getChildAt(0) ?: return
        val cw = c.measuredWidth; val ch = c.measuredHeight
        val sw = cw * zoom
        val x = if (sw < width) ((width - sw) / 2f).toInt() else 0
        c.pivotX = 0f; c.pivotY = 0f; c.scaleX = zoom; c.scaleY = zoom
        c.layout(x, 0, x + cw, ch)
        anchor?.let { a ->
            anchor = null
            hScroll?.scrollTo(Math.round(c.left + a[0] * zoom - a[2]), 0)
            vScroll?.scrollTo(0, Math.round(c.top + a[1] * zoom - a[3]))
        }
        Cull.scrolled()
    }

    /** Ubah zoom dengan faktor [f]; (fx, fy) = titik fokus relatif terhadap layar gulir. */
    fun zoomBy(f: Float, fx: Float, fy: Float) = zoomTo(zoom * f, fx, fy)

    fun zoomTo(z: Float, fx: Float, fy: Float) {
        val nz = z.coerceIn(minZoom, maxZoom)
        if (Math.abs(nz - zoom) < 1e-4f) return
        val c = getChildAt(0) ?: return
        val fxAbs = (hScroll?.scrollX ?: 0) + fx; val fyAbs = (vScroll?.scrollY ?: 0) + fy
        anchor = floatArrayOf((fxAbs - c.left) / zoom, (fyAbs - c.top) / zoom, fx, fy)
        zoom = nz
        requestLayout(); invalidate()
        onZoom?.invoke(zoom)
    }

    /** Jari terangkat: dekat 100% → pas 100%. */
    fun settle() {
        if (zoom != 1f && Math.abs(zoom - 1f) < 0.06f) {
            val vs = vScroll
            zoomTo(1f, (vs?.width ?: 0) / 2f, (vs?.height ?: 0) / 2f)
        }
    }
}

/** Wadah baris tabel yang ikut di-cull (tabel panjang bisa setinggi beberapa layar). */
class CullLinear(ctx: Context) : android.widget.LinearLayout(ctx), VirtHost {
    private val cull = CullHelper(this)
    override fun virtDetach(i: Int) {
        val c = getChildAt(i); val lp = c.layoutParams
        detachViewFromParent(i); removeDetachedView(c, false); attachViewToParent(c, i, lp)
    }
    override fun virtAttach(i: Int) {
        val c = getChildAt(i); val lp = c.layoutParams
        detachViewFromParent(i); addViewInLayout(c, i, lp, true)
    }
    override fun virtDrop(i: Int) { detachViewFromParent(i); requestLayout(); invalidate() }
    // Anak yang sedang terlepas (virtualisasi) sudah menerima "lepas dari jendela": buang tanpa melepasnya dua kali.
    override fun removeViewAt(index: Int) {
        val c = getChildAt(index)
        if (c != null && !c.isAttachedToWindow && isAttachedToWindow) virtDrop(index) else super.removeViewAt(index)
    }
    override fun removeView(view: View) {
        val i = indexOfChild(view)
        if (i >= 0 && !view.isAttachedToWindow && isAttachedToWindow) virtDrop(i) else super.removeView(view)
    }
    override fun removeViewInLayout(view: View) {
        val i = indexOfChild(view)
        if (i >= 0 && !view.isAttachedToWindow && isAttachedToWindow) { detachViewFromParent(i) } else super.removeViewInLayout(view)
    }
    override fun addFocusables(views: java.util.ArrayList<View>, direction: Int, focusableMode: Int) {
        super.addFocusables(views, direction, focusableMode)
        views.removeAll { !it.isAttachedToWindow && isAttachedToWindow }
    }
    override fun addChildrenForAccessibility(outChildren: java.util.ArrayList<View>) {
        super.addChildrenForAccessibility(outChildren)
        if (isAttachedToWindow) outChildren.removeAll { !it.isAttachedToWindow }
    }
    override fun removeAllViews() { cull.dropDetached(); super.removeAllViews() }

    override fun dispatchDraw(canvas: Canvas) { cull.begin(); super.dispatchDraw(canvas) }
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (cull.skip(child)) { EdPerf.culled++; return false }
        EdPerf.drawnKids++
        return super.drawChild(canvas, child, drawingTime)
    }
}

/**
 * Profiler sampling (diagnosis): utas latar memotret tumpukan panggilan thread UI tiap [intervalMs] dan menghitung
 * fungsi mana yang paling sering sedang berjalan (daun) / ada di tumpukan (inklusif). Menunjukkan PERSIS kode
 * framework mana yang memakan waktu, tanpa perlu Android Studio.
 */
class StackSampler(private val intervalMs: Long = 2) {
    private val leaf = HashMap<String, Int>()
    private val incl = HashMap<String, Int>()
    private val chains = HashMap<String, Int>()
    private var n = 0
    @Volatile private var running = false
    private var th: Thread? = null

    private fun fmt(f: StackTraceElement) = f.className.substringAfterLast('.') + "." + f.methodName

    fun start() {
        running = true
        val main = android.os.Looper.getMainLooper().thread
        th = Thread {
            while (running) {
                val st = try { main.stackTrace } catch (_: Throwable) { emptyArray<StackTraceElement>() }
                if (st.isNotEmpty()) synchronized(this) {
                    n++
                    val l = fmt(st[0]); leaf[l] = (leaf[l] ?: 0) + 1
                    val seen = HashSet<String>()
                    for (f in st) { val k = fmt(f); if (seen.add(k)) incl[k] = (incl[k] ?: 0) + 1 }
                    val c = st.take(9).joinToString(" < ") { fmt(it) }
                    chains[c] = (chains[c] ?: 0) + 1
                }
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
        }.apply { name = "depup-sampler"; priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
    }

    fun stop() { running = false; try { th?.join(300) } catch (_: Exception) {} }

    private val ROOTS = setOf("ZygoteInit.main", "RuntimeInit\$MethodAndArgsCaller.run", "Method.invoke", "ActivityThread.main",
        "Looper.loop", "Looper.loopOnce", "Handler.dispatchMessage", "Handler.handleCallback",
        "Choreographer\$FrameDisplayEventReceiver.run", "Choreographer.doFrame", "Choreographer.doCallbacks",
        "Choreographer\$CallbackRecord.run", "ViewRootImpl\$TraversalRunnable.run", "ViewRootImpl.doTraversal")

    @Synchronized fun report(): String {
        if (n == 0) return "sampel: 0 (gagal membaca tumpukan)\n"
        fun pc(c: Int) = "%.0f%%".format(c * 100.0 / n)
        val sb = StringBuilder("sampel tumpukan thread UI: $n\n daun teratas:\n")
        leaf.entries.sortedByDescending { it.value }.take(8).forEach { sb.append("  ${pc(it.value)} ${it.key}\n") }
        sb.append(" inklusif teratas:\n")
        incl.entries.filter { it.key !in ROOTS }.sortedByDescending { it.value }.take(14).forEach { sb.append("  ${pc(it.value)} ${it.key}\n") }
        sb.append(" jalur terpanas:\n")
        chains.entries.sortedByDescending { it.value }.take(4).forEach { sb.append("  ${pc(it.value)} ${it.key}\n") }
        return sb.toString()
    }
}

/** Penghitung performa editor (sementara, untuk diagnosis di HP pengguna). Semua di thread UI. */
object EdPerf {
    var measureNs = 0L; var measures = 0
    var layoutNs = 0L; var layouts = 0
    var drawNs = 0L; var draws = 0
    var paraMeasureNs = 0L; var paraMeasures = 0
    var paraDrawNs = 0L; var paraDraws = 0
    var paraRequests = 0
    var slowMsgMs = 0L; var slowMsg = ""
    var reqWho = ""
    var editorChecks = 0
    var msgAllMs = 0L
    var boxDrawNs = 0L; var boxDraws = 0; var culled = 0; var drawnKids = 0; var cullRedraws = 0
    var virtAttach = 0; var virtDetach = 0
    var glassRecNs = 0L; var glassRecs = 0; var glassBarNs = 0L; var glassBars = 0
    var rootMeasureNs = 0L; var rootMeasures = 0; var rootLayoutNs = 0L; var rootLayouts = 0
    // pemecahan waktu frame di thread UI (ns, dijumlah): mulai pesan → anim selesai → onDraw → pesan selesai
    var frameMsgStart = 0L; var stampAnim = 0L; var stampPre = 0L; var stampDraw = 0L
    var stampPreLast = 0L; var stampScroll = 0L
    var phN = 0; var phAnim = 0L; var phLayout = 0L; var phPre = 0L; var phDraw = 0L
    var phPreRest = 0L; var phPreN = 0; var phScroll = 0L; var phScrollN = 0; var phToDraw = 0L
    /** Dipanggil saat pesan frame Choreographer selesai. */
    fun frameDone(end: Long) {
        val s0 = frameMsgStart
        if (s0 != 0L && stampDraw > s0) {
            val a = if (stampAnim > s0) stampAnim else s0
            val p = if (stampPre > a) stampPre else stampDraw
            phN++; phAnim += a - s0; phLayout += p - a; phPre += stampDraw - p; phDraw += end - stampDraw
            // pecahan preDraw: [preDraw kita → preDraw terakhir] · [→ sesudah pendengar gulir] · [→ onDraw]
            if (stampPreLast in p..stampDraw) {
                phPreN++; phPreRest += stampPreLast - p
                if (stampScroll in stampPreLast..stampDraw) { phScrollN++; phScroll += stampScroll - stampPreLast; phToDraw += stampDraw - stampScroll }
            }
        }
        frameMsgStart = 0L
    }
    /** TOTAL_DURATION tiap frame (ns) — untuk persentil. */
    val frameTotals = ArrayList<Long>()
    /** Total waktu thread UI per jenis pesan (ms) & jumlahnya. */
    val msgTotal = HashMap<String, LongArray>()
    fun addMsg(name: String, ms: Long) { val a = msgTotal.getOrPut(name) { LongArray(2) }; a[0] += ms; a[1]++; msgAllMs += ms }

    private val VRI = mapOf(1 to "INVALIDATE", 2 to "INVALIDATE_RECT", 4 to "RESIZED", 5 to "RESIZED_REPORT",
        6 to "WINDOW_FOCUS_CHANGED", 7 to "DISPATCH_INPUT_EVENT", 13 to "CHECK_FOCUS", 19 to "PROCESS_INPUT_EVENTS",
        21 to "CLEAR_A11Y_FOCUS", 24 to "SYNTHESIZE_INPUT", 29 to "INSETS_CONTROL_CHANGED",
        30 to "SYSTEM_GESTURE_EXCLUSION_CHANGED", 35 to "KEEP_CLEAR_RECTS_CHANGED", 36 to "REPORT_KEEP_CLEAR_RECTS")

    /** ">>>>> Dispatching to Handler (kelas) {hash} callback: what" → nama ringkas yang bisa dibaca. */
    fun msgKey(line: String): String {
        val h = line.substringAfter("Handler (", "").substringBefore(')')
        val rest = line.substringAfter("} ", line)
        val cb = rest.substringBeforeLast(": ").substringBefore('@')
        val what = rest.substringAfterLast(": ").trim().toIntOrNull()
        val hs = h.substringAfterLast('.')
        return if (cb != "null" && cb.isNotEmpty()) "${cb.substringAfterLast('.')} [$hs]"
        else if (h.endsWith("ViewRootImpl\$ViewRootHandler")) "ViewRoot#$what ${VRI[what] ?: ""}"
        else "$hs#$what"
    }
    // FrameMetrics (ns): jumlah & maks per fase
    private val sum = LongArray(9); private val mx = LongArray(9); private var fm = 0
    fun addFrame(vararg v: Long) { fm++; frameTotals.add(v[8]); for (i in v.indices) { sum[i] += v[i]; if (v[i] > mx[i]) mx[i] = v[i] } }
    val frames get() = fm
    fun reset() {
        measureNs = 0; measures = 0; layoutNs = 0; layouts = 0; drawNs = 0; draws = 0; paraMeasureNs = 0; paraMeasures = 0
        paraDrawNs = 0; paraDraws = 0; paraRequests = 0; slowMsgMs = 0; slowMsg = ""; reqWho = ""; msgTotal.clear()
        editorChecks = 0; msgAllMs = 0; frameTotals.clear()
        boxDrawNs = 0; boxDraws = 0; culled = 0; drawnKids = 0; cullRedraws = 0; virtAttach = 0; virtDetach = 0
        glassRecNs = 0; glassRecs = 0; glassBarNs = 0; glassBars = 0
        rootMeasureNs = 0; rootMeasures = 0; rootLayoutNs = 0; rootLayouts = 0
        phN = 0; phAnim = 0; phLayout = 0; phPre = 0; phDraw = 0
        phPreRest = 0; phPreN = 0; phScroll = 0; phScrollN = 0; phToDraw = 0
        java.util.Arrays.fill(sum, 0); java.util.Arrays.fill(mx, 0); fm = 0
    }
    fun summary(): String {
        fun ms(ns: Long) = ns / 1_000_000
        val names = arrayOf("tunda", "input", "anim", "ukur", "gambar", "sinkron", "GPU-cmd", "swap", "TOTAL")
        val sb = StringBuilder()
        sb.append("ukur ${measures}x ${ms(measureNs)}ms tata ${layouts}x ${ms(layoutNs)}ms gbr ${draws}x ${ms(drawNs)}ms\n")
        sb.append("para-ukur ${paraMeasures}x ${ms(paraMeasureNs)}ms minta ${paraRequests}x\n")
        if (fm > 0) {
            sb.append("frame $fm, rata/maks ms:\n")
            for (i in names.indices) sb.append(names[i]).append(' ').append(ms(sum[i] / fm)).append('/').append(ms(mx[i])).append(if (i % 3 == 2) "\n" else "  ")
        }
        if (slowMsgMs > 0) sb.append("pesan terlama ${slowMsgMs}ms: $slowMsg\n")
        msgTotal.entries.sortedByDescending { it.value[0] }.take(3).forEach { (k, v) -> sb.append("Σ ${v[0]}ms ${v[1]}x: ${k.take(60)}\n") }
        if (reqWho.isNotEmpty()) sb.append("peminta ukur: $reqWho")
        return sb.toString()
    }

    /** Laporan lengkap satu putaran uji (wall = lama putaran, ns). */
    fun report(wallNs: Long, callbacks: Int, scrolledPx: Int, gcN: Long, gcMs: Long): String {
        fun ms(ns: Long) = ns / 1_000_000
        val sec = wallNs / 1e9
        val sb = StringBuilder()
        sb.append("frame digambar $fm (%.1f fps) · callback $callbacks · gulir ${scrolledPx}px\n".format(fm / sec))
        if (frameTotals.isNotEmpty()) {
            val s = frameTotals.sorted()
            fun pc(q: Double) = ms(s[((s.size - 1) * q).toInt()])
            val jank = s.count { it > 16_700_000L }
            sb.append("frame ms p50 ${pc(0.5)} · p90 ${pc(0.9)} · p99 ${pc(0.99)} · maks ${ms(s.last())} · >16ms ${jank}x\n")
            val names = arrayOf("tunda", "input", "anim", "ukur", "gambar", "sinkron", "GPU", "swap")
            sb.append("fase rata: ")
            for (i in names.indices) sb.append(names[i]).append(' ').append("%.1f".format(sum[i] / fm / 1e6)).append(if (i == 3) "\n  " else " ")
            sb.append('\n')
        }
        sb.append("thread UI sibuk ${msgAllMs}ms (%.0f%%) · GC ${gcN}x ${gcMs}ms\n".format(msgAllMs / 10.0 / sec))
        sb.append("traversal: ukur ${measures}x ${ms(measureNs)}ms · tata ${layouts}x ${ms(layoutNs)}ms · gambar ${draws}x ${ms(drawNs)}ms\n")
        if (phN > 0) sb.append("thread UI per frame ($phN): anim %.1f · ukur+tata %.1f · preDraw %.1f · rekam+sinkron %.1f ms\n"
            .format(phAnim / phN / 1e6, phLayout / phN / 1e6, phPre / phN / 1e6, phDraw / phN / 1e6))
        if (phPreN > 0) sb.append("  preDraw dipecah ($phPreN): pendengar-preDraw lain %.1f".format(phPreRest / phPreN / 1e6) +
            (if (phScrollN > 0) " · scrollToFocus+pendengar-gulir %.1f · sisa→onDraw %.1f ms ($phScrollN)".format(phScroll / phScrollN / 1e6, phToDraw / phScrollN / 1e6) else " · (gulir tak terpicu)") + "\n")
        sb.append("akar: ukur ${rootMeasures}x ${ms(rootMeasureNs)}ms · tata ${rootLayouts}x ${ms(rootLayoutNs)}ms\n")
        sb.append("lembar: rekam ${boxDraws}x ${ms(boxDrawNs)}ms · anak digambar ${drawnKids} · dilewati ${culled} · rekam-ulang-cull ${cullRedraws} · pasang ${virtAttach} · lepas ${virtDetach} · terlepas kini ${Cull.detachedCount()}\n")
        sb.append("kaca: rekam isi ${glassRecs}x ${ms(glassRecNs)}ms · gambar bilah ${glassBars}x ${ms(glassBarNs)}ms\n")
        sb.append("ParaEdit: ukur ${paraMeasures}x ${ms(paraMeasureNs)}ms · gambar ${paraDraws}x ${ms(paraDrawNs)}ms · minta-ukur ${paraRequests}x · cek-editor ${editorChecks}x\n")
        sb.append("pesan thread UI (total ms · jumlah · rata):\n")
        msgTotal.entries.sortedByDescending { it.value[0] }.take(6).forEach { (k, v) ->
            sb.append("  ${v[0]} · ${v[1]}x · %.2f  ".format(v[0].toDouble() / v[1])).append(k.take(56)).append('\n')
        }
        if (reqWho.isNotEmpty()) sb.append("peminta ukur: $reqWho\n")
        return sb.toString()
    }
}
