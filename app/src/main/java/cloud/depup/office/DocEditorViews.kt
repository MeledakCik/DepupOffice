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

    override fun requestLayout() { EdPerf.paraRequests++; super.requestLayout() }

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
class PagedBox(ctx: Context) : android.widget.LinearLayout(ctx) {
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


/** Penghitung performa editor (sementara, untuk diagnosis di HP pengguna). Semua di thread UI. */
object EdPerf {
    var measureNs = 0L; var measures = 0
    var layoutNs = 0L; var layouts = 0
    var drawNs = 0L; var draws = 0
    var paraMeasureNs = 0L; var paraMeasures = 0
    var paraDrawNs = 0L; var paraDraws = 0
    var paraRequests = 0
    fun reset() { measureNs = 0; measures = 0; layoutNs = 0; layouts = 0; drawNs = 0; draws = 0; paraMeasureNs = 0; paraMeasures = 0; paraDrawNs = 0; paraDraws = 0; paraRequests = 0 }
}
