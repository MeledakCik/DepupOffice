package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max
import kotlin.math.min

/**
 * Kanvas satu halaman PDF: menampilkan halaman (pas layar) + coretan, dan menangani sentuhan sesuai alat aktif.
 * Koordinat coretan disimpan sebagai pecahan halaman (0..1), lihat [PageMarks].
 */
@SuppressLint("ViewConstructor")
class MarkView(ctx: Context) : View(ctx) {
    enum class Tool { PEN, HIGHLIGHT, NOTE, SIGN }

    var page: Bitmap? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var marks = PageMarks()
        set(v) { field = v; invalidate() }
    var tool = Tool.PEN
    var penColor = Color.rgb(220, 38, 38)
    var hlColor = Color.argb(255, 255, 230, 0)
    /** Ketuk saat alat Catatan: (x, y pecahan halaman, catatan yang tersentuh atau null). */
    var onNoteTap: ((Float, Float, PageMarks.Note?) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private val dst = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.ACCENT; style = Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private var cur: PageMarks.Stroke? = null
    private var dragStamp: PageMarks.Stamp? = null
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val s = dragStamp ?: lastStamp() ?: return false
            val f = d.scaleFactor
            val b = s.box
            val cx = b.centerX(); val cy = b.centerY()
            val nw = (b.width() * f).coerceIn(0.06f, 0.9f)
            val nh = nw * b.height() / b.width()
            b.set(cx - nw / 2, cy - nh / 2, cx + nw / 2, cy + nh / 2)
            invalidate(); return true
        }
    })

    private fun lastStamp(): PageMarks.Stamp? = marks.items.lastOrNull { it is PageMarks.Stamp } as? PageMarks.Stamp

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit() }

    private fun fit() {
        val b = page ?: return
        val pad = dp(10).toFloat()
        val aw = width - pad * 2; val ah = height - pad * 2
        if (aw <= 0 || ah <= 0) return
        val s = min(aw / b.width, ah / b.height)
        val w = b.width * s; val h = b.height * s
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    override fun onDraw(c: Canvas) {
        val b = page ?: return
        fit()
        // Lapisan terpisah supaya mode MULTIPLY stabilo bercampur dengan halaman, bukan dengan latar layar.
        val save = c.saveLayer(dst, null)
        c.drawColor(Color.WHITE, android.graphics.PorterDuff.Mode.SRC)
        c.drawBitmap(b, null, dst, bmpPaint)
        c.translate(dst.left, dst.top)
        PdfTools.drawMarks(c, dst.width(), dst.height(), marks)
        c.restoreToCount(save)
        c.drawRect(dst, edge)
        if (tool == Tool.SIGN) (dragStamp ?: lastStamp())?.let { s ->
            c.drawRect(dst.left + s.box.left * dst.width(), dst.top + s.box.top * dst.height(),
                dst.left + s.box.right * dst.width(), dst.top + s.box.bottom * dst.height(), sel)
        }
    }

    private fun fx(x: Float) = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
    private fun fy(y: Float) = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (page == null || dst.width() <= 0f) return false
        if (tool == Tool.SIGN) {
            scaler.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val x = fx(e.x); val y = fy(e.y)
                    dragStamp = marks.items.lastOrNull { it is PageMarks.Stamp && it.box.contains(x, y) } as? PageMarks.Stamp
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) dragStamp?.let { s ->
                    val dx = (e.x - lastX) / dst.width(); val dy = (e.y - lastY) / dst.height()
                    s.box.offset(dx, dy)
                    // tetap di dalam halaman
                    if (s.box.left < 0) s.box.offset(-s.box.left, 0f)
                    if (s.box.top < 0) s.box.offset(0f, -s.box.top)
                    if (s.box.right > 1) s.box.offset(1 - s.box.right, 0f)
                    if (s.box.bottom > 1) s.box.offset(0f, 1 - s.box.bottom)
                    lastX = e.x; lastY = e.y
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onChanged?.invoke() }
            }
            return true
        }
        if (tool == Tool.NOTE) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                MotionEvent.ACTION_UP -> if (kotlin.math.abs(e.x - downX) < dp(12) && kotlin.math.abs(e.y - downY) < dp(12) && dst.contains(e.x, e.y)) {
                    val hit = marks.items.lastOrNull {
                        it is PageMarks.Note && PdfTools.noteRect(dst.width(), dst.height(), it).contains(e.x - dst.left, e.y - dst.top)
                    } as? PageMarks.Note
                    onNoteTap?.invoke(fx(e.x), fy(e.y), hit)
                }
            }
            return true
        }
        // Pena / stabilo
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val hl = tool == Tool.HIGHLIGHT
                cur = PageMarks.Stroke(if (hl) hlColor else penColor, if (hl) 0.028f else 0.004f, hl).also {
                    it.pts.add(fx(e.x)); it.pts.add(fy(e.y))
                    marks.items.add(it)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> cur?.let { s ->
                for (h in 0 until e.historySize) { s.pts.add(fx(e.getHistoricalX(h))); s.pts.add(fy(e.getHistoricalY(h))) }
                s.pts.add(fx(e.x)); s.pts.add(fy(e.y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (cur != null) { cur = null; onChanged?.invoke() } }
        }
        return true
    }
}

/** Coret-coret PDF: pena, stabilo, catatan, dan tanda tangan langsung di halaman. */
class PdfAnnotateActivity : AppCompatActivity() {
    companion object { const val EXTRA_URI = "uri" }

    private lateinit var uri: Uri
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val lock = Any()
    private var total = 0
    private var cur = 0
    private var loadGen = 0
    private val marks = HashMap<Int, PageMarks>()
    private var dirty = false
    private var name = "Dokumen"

    private lateinit var mv: MarkView
    private lateinit var counter: TextView
    private lateinit var toolRow: LinearLayout
    private lateinit var colorRow: LinearLayout

    private val penColors = intArrayOf(Color.rgb(220, 38, 38), Color.rgb(17, 24, 39), Color.rgb(37, 99, 235), Color.rgb(22, 163, 74))
    private val penNames = arrayOf("Merah", "Hitam", "Biru", "Hijau")
    private val hlColors = intArrayOf(Color.rgb(255, 230, 0), Color.rgb(134, 239, 172), Color.rgb(249, 168, 212), Color.rgb(125, 211, 252))
    private val hlNames = arrayOf("Kuning", "Hijau", "Merah muda", "Biru")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) } ?: run { finish(); return }
        name = Files.nameOf(this, raw)

        val loading = vbox().apply { gravity = Gravity.CENTER; setBackgroundColor(C.CANVAS) }
        loading.add(android.widget.ProgressBar(this), w = dp(40), h = dp(40))
        loading.add(tv("Membuka $name…", 14f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 12)
        setContentView(loading)

        Thread {
            val local = Files.cacheCopy(this, raw)
            val ok = try {
                synchronized(lock) {
                    pfd = contentResolver.openFileDescriptor(local, "r")
                    renderer = PdfRenderer(pfd!!)
                    total = renderer!!.pageCount
                }
                total > 0
            } catch (_: SecurityException) { null } catch (_: Throwable) { false }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (ok) {
                    true -> { uri = local; build() }
                    null -> { toast("PDF ini dikunci password, buka kuncinya dulu."); finish() }
                    else -> { toast("PDF tidak bisa dibuka"); finish() }
                }
            }
        }.start()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (dirty) confirm("Buang coretan?", "Coretan yang belum disimpan akan hilang.", "Buang") { finish() }
                else finish()
            }
        })
    }

    private fun build() {
        val root = vbox().apply { setBackgroundColor(C.CANVAS) }

        val bar = hbox().apply { setBackgroundColor(C.SURFACE); setPadding(dp(4), dp(6), dp(8), dp(6)) }
        bar.add(iconBtn(R.drawable.ic_back, "Kembali") { onBackPressedDispatcher.onBackPressed() }, w = dp(48), h = dp(48))
        val col = vbox()
        col.add(tv(name, 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        counter = tv("", 12f, C.MUTED)
        col.add(counter)
        bar.add(col, w = 0, weight = 1f, side = 4)
        bar.add(iconBtn(R.drawable.ic_undo, "Urungkan coretan terakhir") { undo() }, w = dp(48), h = dp(48))
        bar.add(btn("Simpan", true, C.ACCENT) { save() }, w = WRAP, side = 4)
        root.add(bar)

        mv = MarkView(this).apply {
            onChanged = { dirty = true }
            onNoteTap = { x, y, hit -> noteDialog(x, y, hit) }
        }
        root.add(mv, w = MATCH, h = 0, weight = 1f)

        val nav = hbox().apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(2), dp(8), dp(2)) }
        nav.add(iconBtn(R.drawable.ic_back, "Halaman sebelumnya") { go(cur - 1) }, w = dp(48), h = dp(48))
        nav.add(tv("Halaman", 13f, C.MUTED).apply { gravity = Gravity.CENTER; tappable("Lompat ke halaman") { jumpDialog() } }, w = WRAP, side = 8)
        nav.add(iconBtn(R.drawable.ic_next, "Halaman berikutnya") { go(cur + 1) }, w = dp(48), h = dp(48), side = 8)
        root.add(nav)

        val tools = vbox().apply { setBackgroundColor(C.SURFACE); setPadding(dp(12), dp(8), dp(12), dp(10)) }
        toolRow = hbox()
        tools.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(toolRow) })
        colorRow = hbox()
        tools.add(colorRow, top = 8)
        root.add(tools)

        setContentView(Glass.scaffold(root))
        refreshTools()
        go(0)
    }

    private fun refreshTools() {
        toolRow.removeAllViews()
        fun chip(t: MarkView.Tool, label: String, icon: Int) {
            toolRow.add(filterChip(label, mv.tool == t, icon) {
                mv.tool = t
                if (t == MarkView.Tool.SIGN && marks[cur]?.items?.none { it is PageMarks.Stamp } != false) signDialog()
                refreshTools(); mv.invalidate()
            }, w = WRAP, side = 4)
        }
        chip(MarkView.Tool.PEN, "Pena", R.drawable.ic_sign)
        chip(MarkView.Tool.HIGHLIGHT, "Stabilo", R.drawable.ic_bright)
        chip(MarkView.Tool.NOTE, "Catatan", R.drawable.ic_note)
        chip(MarkView.Tool.SIGN, "Tanda tangan", R.drawable.ic_verified)

        colorRow.removeAllViews()
        when (mv.tool) {
            MarkView.Tool.PEN, MarkView.Tool.HIGHLIGHT -> {
                val hl = mv.tool == MarkView.Tool.HIGHLIGHT
                val colors = if (hl) hlColors else penColors
                val names = if (hl) hlNames else penNames
                colors.forEachIndexed { i, c ->
                    val on = (if (hl) mv.hlColor else mv.penColor) == c
                    colorRow.add(View(this).apply {
                        background = shape(c, 100, if (on) C.TEXT else C.LINE, if (on) 3 else 1)
                        tappable("Warna ${names[i]}${if (on) ", terpilih" else ""}") {
                            if (hl) mv.hlColor = c else mv.penColor = c
                            refreshTools()
                        }
                    }, w = dp(36), h = dp(36), side = 5)
                }
                colorRow.add(tv(if (hl) "Sapukan di atas teks" else "Gambar dengan jari", 12f, C.MUTED), w = 0, weight = 1f, side = 12)
            }
            MarkView.Tool.NOTE -> colorRow.add(tv("Ketuk halaman untuk menambah catatan. Ketuk catatan untuk mengubah/menghapus.", 12f, C.MUTED))
            MarkView.Tool.SIGN -> {
                colorRow.add(tv("Geser untuk memindah, cubit untuk ubah ukuran.", 12f, C.MUTED), w = 0, weight = 1f)
                colorRow.add(btn("+ TTD", false, C.ACCENT) { signDialog() }, w = WRAP, side = 8)
            }
        }
    }

    private fun pageMarks(i: Int) = marks.getOrPut(i) { PageMarks() }

    private fun go(i: Int) {
        if (i < 0 || i >= total) return
        cur = i
        counter.text = "Halaman ${i + 1} dari $total"
        mv.marks = pageMarks(i)
        val gen = ++loadGen
        val targetW = min(resources.displayMetrics.widthPixels, Perf.pageMaxPx())
        Perf.io.execute {
            val bmp = try {
                synchronized(lock) { renderer?.let { Pdf.render(it, i, 1f, targetW, Perf.pageMaxPx()).bmp } }
            } catch (_: Throwable) { null }
            runOnUiThread {
                if (gen != loadGen || isDestroyed) { bmp?.recycle(); return@runOnUiThread }
                val old = mv.page
                mv.page = bmp
                if (old != null && old !== bmp) old.recycle()
            }
        }
    }

    private fun undo() {
        val m = marks[cur] ?: return
        if (m.items.isEmpty()) { toast("Tidak ada coretan di halaman ini"); return }
        m.items.removeAt(m.items.size - 1)
        dirty = marks.values.any { !it.isEmpty() }
        mv.invalidate()
    }

    private fun noteDialog(x: Float, y: Float, hit: PageMarks.Note?) {
        val f = field("Tulis catatan…", multi = true).apply { hit?.let { setText(it.text) } }
        val b = AlertDialog.Builder(this).setTitle(if (hit == null) "Catatan baru" else "Ubah catatan")
            .setView(vbox(20).apply { add(f) })
            .setPositiveButton("Simpan") { _, _ ->
                val t = f.text.toString().trim()
                val m = pageMarks(cur)
                val idx = if (hit != null) m.items.indexOf(hit) else -1
                if (t.isEmpty()) { if (idx >= 0) m.items.removeAt(idx) }
                else {
                    val n = PageMarks.Note(t, hit?.x ?: x.coerceAtMost(0.6f), hit?.y ?: y.coerceAtMost(0.92f))
                    if (idx >= 0) m.items[idx] = n else m.items.add(n)
                }
                dirty = true; mv.invalidate()
            }
            .setNegativeButton("Batal", null)
        if (hit != null) b.setNeutralButton("Hapus") { _, _ -> pageMarks(cur).items.remove(hit); dirty = true; mv.invalidate() }
        b.show()
    }

    private fun signDialog() {
        val pad = SignatureView(this)
        val box = vbox(20)
        box.add(tv("Gambar tanda tangan di kotak putih", 13f, C.MUTED))
        box.add(pad, top = 8, h = dp(180))
        box.add(btn("Ulangi", false) { pad.clear() }, top = 8)
        val d = AlertDialog.Builder(this).setTitle("Tanda tangan")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Tempel", null).setNegativeButton("Batal", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val sig = pad.toBitmap() ?: run { toast("Tanda tangan masih kosong"); return@setOnClickListener }
            d.dismiss()
            val page = mv.page
            val pageRatio = if (page != null) page.width.toFloat() / page.height else 0.707f
            val w = 0.32f
            val h = w * pageRatio * sig.height / sig.width
            pageMarks(cur).items.add(PageMarks.Stamp(sig, RectF(0.62f - 0.0f, 0.78f, 0.62f + w, 0.78f + h).also {
                if (it.right > 0.97f) it.offset(0.97f - it.right, 0f)
                if (it.bottom > 0.97f) it.offset(0f, 0.97f - it.bottom)
            }))
            mv.tool = MarkView.Tool.SIGN
            dirty = true
            refreshTools(); mv.invalidate()
        }
    }

    private fun jumpDialog() {
        val f = field("Nomor halaman (1-$total)", number = true)
        AlertDialog.Builder(this).setTitle("Lompat ke halaman").setView(vbox(20).apply { add(f) })
            .setPositiveButton("Buka") { _, _ -> f.text.toString().toIntOrNull()?.let { go(it - 1) } }
            .setNegativeButton("Batal", null).show()
    }

    private fun save() {
        if (marks.values.all { it.isEmpty() }) { toast("Belum ada coretan"); return }
        val out = Files.newOut(this, Files.baseName(name) + "_coretan", "pdf")
        val snapshot = HashMap(marks)
        val dlg = busy("Menyimpan coretan…")
        Thread {
            val r = runCatching { PdfTools.saveAnnotated(this, uri, snapshot, out) }
            runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                if (isFinishing) return@runOnUiThread
                r.onSuccess {
                    dirty = false
                    showSaved(out)
                }.onFailure { fail(it) }
            }
        }.start()
    }

    private fun showSaved(file: java.io.File) {
        val u = Files.uriFor(this, file)
        Recent.add(this, u, file.name, PDF)
        Meta.setLastJob(this, "Coretan PDF disimpan", file)
        resultSheet("Coretan disimpan", "${file.name} · ${Files.fmtSize(file.length())}", "",
            "Buka Dokumen", { Files.open(this, u, PDF) },
            listOf(
                SheetAction(R.drawable.ic_download, "Simpan ke Unduhan") {
                    work("Menyimpan…", { Files.saveToDownloads(this, u, file.name, PDF) }) { r ->
                        r.onSuccess { toast("Tersimpan di Unduhan/Depup Office") }.onFailure { fail(it) }
                    }
                },
                SheetAction(R.drawable.ic_chat, "Kirim via WhatsApp", C.TEAL, C.TEAL_BG) { Files.share(this, u, PDF, true) },
                SheetAction(R.drawable.ic_share, "Bagikan ke aplikasi lain") { Files.share(this, u, PDF, false) }
            ))
    }

    override fun onDestroy() {
        loadGen++
        synchronized(lock) {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            renderer = null
        }
        super.onDestroy()
    }
}
