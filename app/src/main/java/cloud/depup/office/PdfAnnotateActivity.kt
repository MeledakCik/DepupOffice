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
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    // ---------------- tanda tangan: pilih, geser, ubah ukuran bebas, putar, hapus
    /** TTD terpilih (pegangan tampil di sekelilingnya). */
    var selected: PageMarks.Stamp? = null
        set(v) { field = v; onSelect?.invoke(v); invalidate() }
    var onSelect: ((PageMarks.Stamp?) -> Unit)? = null
    private enum class Drag { NONE, MOVE, CORNER, RIGHT, BOTTOM, ROTATE, DELETE, PINCH }
    private var drag = Drag.NONE
    private var hw0 = 0f; private var hh0 = 0f            // setengah ukuran (px) saat mulai menarik
    private var pinchDist = 0f; private var pinchAng = 0f; private var pinchRot = 0f
    private val handleR get() = dp(13).toFloat()
    private val hitR get() = dp(24).toFloat()
    private val hPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x55000000) }
    private val hStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ACCENT; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND }
    private val delPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 38, 38); style = Paint.Style.FILL }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND }

    /** [cx, cy, setengahLebar, setengahTinggi, sudutRadian] TTD dalam piksel layar. */
    private fun geo(s: PageMarks.Stamp): FloatArray {
        val l = dst.left + s.box.left * dst.width(); val t = dst.top + s.box.top * dst.height()
        val r = dst.left + s.box.right * dst.width(); val b = dst.top + s.box.bottom * dst.height()
        return floatArrayOf((l + r) / 2, (t + b) / 2, (r - l) / 2, (b - t) / 2, Math.toRadians(s.rot.toDouble()).toFloat())
    }

    /** Titik lokal (lx, ly) relatif pusat TTD → layar. */
    private fun toScreen(g: FloatArray, lx: Float, ly: Float): FloatArray {
        val c = kotlin.math.cos(g[4]); val sn = kotlin.math.sin(g[4])
        return floatArrayOf(g[0] + lx * c - ly * sn, g[1] + lx * sn + ly * c)
    }

    /** Titik layar → lokal (putaran dibatalkan). */
    private fun toLocal(g: FloatArray, x: Float, y: Float): FloatArray {
        val c = kotlin.math.cos(-g[4]); val sn = kotlin.math.sin(-g[4])
        val dx = x - g[0]; val dy = y - g[1]
        return floatArrayOf(dx * c - dy * sn, dx * sn + dy * c)
    }

    private fun setGeo(s: PageMarks.Stamp, cx: Float, cy: Float, hw: Float, hh: Float) {
        val w = dst.width(); val h = dst.height()
        val mhw = max(hw, dp(14).toFloat()); val mhh = max(hh, dp(8).toFloat())
        // pusat tetap di dalam halaman
        val x = cx.coerceIn(dst.left, dst.right); val y = cy.coerceIn(dst.top, dst.bottom)
        s.box.set((x - mhw - dst.left) / w, (y - mhh - dst.top) / h, (x + mhw - dst.left) / w, (y + mhh - dst.top) / h)
    }

    private fun insideStamp(s: PageMarks.Stamp, x: Float, y: Float): Boolean {
        val g = geo(s); val p = toLocal(g, x, y)
        return kotlin.math.abs(p[0]) <= g[2] + dp(6) && kotlin.math.abs(p[1]) <= g[3] + dp(6)
    }

    private fun near(p: FloatArray, x: Float, y: Float) = kotlin.math.hypot(p[0] - x, p[1] - y) <= hitR

    /** Letak pegangan di layar. */
    private fun handles(s: PageMarks.Stamp): Map<Drag, FloatArray> {
        val g = geo(s)
        return linkedMapOf(
            Drag.DELETE to toScreen(g, -g[2], -g[3]),
            Drag.ROTATE to toScreen(g, 0f, -g[3] - dp(30)),
            Drag.CORNER to toScreen(g, g[2], g[3]),
            Drag.RIGHT to toScreen(g, g[2], 0f),
            Drag.BOTTOM to toScreen(g, 0f, g[3])
        )
    }

    private fun snap(a0: Float, tol: Float): Float {
        var a = ((a0 % 360f) + 360f) % 360f
        for (sn in floatArrayOf(0f, 90f, 180f, 270f, 360f)) if (kotlin.math.abs(a - sn) < tol) a = sn % 360f
        return a
    }

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
        if (tool == Tool.SIGN) selected?.takeIf { marks.items.contains(it) }?.let { s ->
            val g = geo(s)
            c.save(); c.rotate(s.rot, g[0], g[1])
            c.drawRect(g[0] - g[2], g[1] - g[3], g[0] + g[2], g[1] + g[3], sel)
            c.restore()
            val hs = handles(s)
            val top = toScreen(g, 0f, -g[3])
            hs[Drag.ROTATE]?.let { r ->
                c.drawLine(top[0], top[1], r[0], r[1], hStroke)
                c.drawCircle(r[0], r[1], handleR, hPaint)
                val o = RectF(r[0] - handleR * 0.5f, r[1] - handleR * 0.5f, r[0] + handleR * 0.5f, r[1] + handleR * 0.5f)
                c.drawArc(o, -60f, 280f, false, hStroke)          // panah putar
            }
            for (d in listOf(Drag.CORNER, Drag.RIGHT, Drag.BOTTOM)) hs[d]?.let { p ->
                val rr = if (d == Drag.CORNER) handleR else handleR * 0.75f
                c.drawCircle(p[0], p[1], rr, hPaint); c.drawCircle(p[0], p[1], rr, hStroke)
            }
            hs[Drag.DELETE]?.let { p ->
                c.drawCircle(p[0], p[1], handleR, delPaint)
                val k = handleR * 0.4f
                c.drawLine(p[0] - k, p[1] - k, p[0] + k, p[1] + k, xPaint); c.drawLine(p[0] + k, p[1] - k, p[0] - k, p[1] + k, xPaint)
            }
        }

    }

    private fun fx(x: Float) = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
    private fun fy(y: Float) = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (page == null || dst.width() <= 0f) return false
        if (tool == Tool.SIGN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                    drag = Drag.NONE
                    val cur = selected?.takeIf { marks.items.contains(it) }
                    if (cur != null) {
                        val h = handles(cur).entries.firstOrNull { near(it.value, e.x, e.y) }?.key
                        if (h == Drag.DELETE) { marks.items.remove(cur); selected = null; onChanged?.invoke(); return true }
                        if (h != null) { drag = h; val g = geo(cur); hw0 = g[2]; hh0 = g[3]; return true }
                    }
                    val hitS = marks.items.lastOrNull { it is PageMarks.Stamp && insideStamp(it, e.x, e.y) } as? PageMarks.Stamp
                    if (hitS != null) { selected = hitS; drag = Drag.MOVE } else selected = null
                }
                MotionEvent.ACTION_POINTER_DOWN -> selected?.let { s ->
                    if (e.pointerCount == 2) {
                        drag = Drag.PINCH
                        pinchDist = kotlin.math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
                        pinchAng = Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                        pinchRot = s.rot
                        val g = geo(s); hw0 = g[2]; hh0 = g[3]
                    }
                }
                MotionEvent.ACTION_MOVE -> selected?.let { s ->
                    val g = geo(s)
                    when (drag) {
                        Drag.MOVE -> if (e.pointerCount == 1) setGeo(s, g[0] + e.x - lastX, g[1] + e.y - lastY, g[2], g[3])
                        Drag.CORNER -> {
                            // proporsional, pusat tetap: proyeksi jari ke diagonal sudut
                            val p = toLocal(g, e.x, e.y)
                            val k = ((p[0] * hw0 + p[1] * hh0) / (hw0 * hw0 + hh0 * hh0)).coerceIn(0.15f, 12f)
                            setGeo(s, g[0], g[1], hw0 * k, hh0 * k)
                        }
                        Drag.RIGHT -> { val p = toLocal(g, e.x, e.y); setGeo(s, g[0], g[1], kotlin.math.abs(p[0]), g[3]) }
                        Drag.BOTTOM -> { val p = toLocal(g, e.x, e.y); setGeo(s, g[0], g[1], g[2], kotlin.math.abs(p[1])) }
                        Drag.ROTATE -> s.rot = snap(Math.toDegrees(kotlin.math.atan2((e.y - g[1]).toDouble(), (e.x - g[0]).toDouble())).toFloat() + 90f, 5f)
                        Drag.PINCH -> if (e.pointerCount >= 2) {
                            val d = kotlin.math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                            val k = (d / pinchDist).coerceIn(0.15f, 12f)
                            val ang = Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                            s.rot = snap(pinchRot + ang - pinchAng, 4f)
                            setGeo(s, g[0], g[1], hw0 * k, hh0 * k)
                        }
                        else -> {}
                    }
                    lastX = e.x; lastY = e.y
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> if (drag == Drag.PINCH) drag = Drag.NONE
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (drag != Drag.NONE) onChanged?.invoke(); drag = Drag.NONE; invalidate() }
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
    companion object {
        const val EXTRA_URI = "uri"
        /** Buka langsung di mode tanda tangan (dari menu "Tanda tangan PDF"). */
        const val EXTRA_SIGN = "sign"
    }

    /** Ambil foto tanda tangan / stempel → latar dibuang (PNG transparan) → tempel. */
    private val pickSigPhoto = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { u ->
        if (u == null) return@registerForActivityResult
        work("Membuang latar kertas…", {
            val raw = Pdf.decode(this, u, 1400)
            try { Pdf.extractTransparentSignature(raw) } finally { raw.recycle() }
        }) { r -> r.onSuccess { placeSig(it, remember = true) }.onFailure { fail(it) } }
    }

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
        mv.onSelect = { refreshTools() }
        if (intent.getBooleanExtra(EXTRA_SIGN, false)) { mv.tool = MarkView.Tool.SIGN; go(total - 1) } else go(0)
        refreshTools()
        if (intent.getBooleanExtra(EXTRA_SIGN, false)) signDialog()
    }

    private fun refreshTools() {
        toolRow.removeAllViews()
        fun chip(t: MarkView.Tool, label: String, icon: Int) {
            toolRow.add(filterChip(label, mv.tool == t, icon) {
                mv.tool = t
                if (t != MarkView.Tool.SIGN) mv.selected = null
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
                val col = vbox()
                val s = mv.selected?.takeIf { pageMarks(cur).items.contains(it) }
                val row1 = hbox()
                row1.add(btn("+ TTD", false, C.ACCENT) { signDialog() }, w = WRAP)
                if (s == null) {
                    row1.add(tv("Ketuk tanda tangan untuk memilih. Geser = pindah, titik sudut = ukuran, titik sisi = lebar/tinggi bebas, titik atas = putar, dua jari = cubit & putar.", 12f, C.MUTED), w = 0, weight = 1f, side = 10)
                    col.add(row1)
                } else {
                    col.add(row1)
                    val acts = hbox()
                    sigInks.forEachIndexed { i, c ->
                        acts.add(View(this).apply {
                            background = shape(c, 100, C.LINE, 1)
                            tappable("Tinta ${sigInkNames[i]}") { s.bmp = PdfTools.recolor(s.bmp, c); dirty = true; mv.invalidate() }
                        }, w = dp(32), h = dp(32), side = 5)
                    }
                    acts.add(btn("Putar 90°", false) { s.rot = (s.rot + 90f) % 360f; dirty = true; mv.invalidate() }, w = WRAP, side = 6)
                    acts.add(btn("Gandakan", false) { val c = s.copy(0.03f, 0.03f); pageMarks(cur).items.add(c); mv.selected = c; dirty = true; mv.invalidate() }, w = WRAP, side = 6)
                    acts.add(btn("Ke semua halaman", false) { copyToAllPages(s) }, w = WRAP, side = 6)
                    acts.add(btn("Hapus", false, C.RED) { pageMarks(cur).items.remove(s); mv.selected = null; dirty = true; mv.invalidate() }, w = WRAP, side = 6)
                    col.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(acts) }, top = 8)
                }
                colorRow.add(col, w = MATCH)
            }
        }
    }

    private fun pageMarks(i: Int) = marks.getOrPut(i) { PageMarks() }

    private fun go(i: Int) {
        if (i < 0 || i >= total) return
        cur = i
        if (::mv.isInitialized) mv.selected = null
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

    private val sigInks = intArrayOf(Color.rgb(17, 24, 39), Color.rgb(11, 31, 107), Color.rgb(29, 78, 216), Color.rgb(185, 28, 28))
    private val sigInkNames = arrayOf("hitam", "biru tua", "biru", "merah")
    private val savedSig get() = java.io.File(filesDir, "ttd/terakhir.png")

    /** Buat tanda tangan: gambar (pilih tinta & tebal), dari foto, atau pakai yang tersimpan. */
    private fun signDialog() {
        val pad = SignatureView(this)
        val box = vbox(20)
        box.add(tv("Gambar tanda tangan di kotak putih", 13f, C.MUTED))
        box.add(pad, top = 8, h = dp(180))
        val inks = hbox()
        sigInks.forEachIndexed { i, c ->
            inks.add(View(this).apply {
                background = shape(c, 100, C.LINE, 1)
                tappable("Tinta ${sigInkNames[i]}") { pad.color = c }
            }, w = dp(32), h = dp(32), side = 5)
        }
        inks.add(btn("Tipis", false) { pad.stroke = dp(2).toFloat() }, w = WRAP, side = 8)
        inks.add(btn("Tebal", false) { pad.stroke = 4.5f * resources.displayMetrics.density }, w = WRAP, side = 4)
        box.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(inks) }, top = 8)
        val more = hbox()
        more.add(btn("Ulangi", false) { pad.clear() }, w = WRAP)
        var d: AlertDialog? = null
        more.add(btn("Dari foto", false, C.ACCENT) { d?.dismiss(); pickSigPhoto.launch("image/*") }, w = WRAP, side = 6)
        if (savedSig.exists()) more.add(btn("TTD tersimpan", false, C.ACCENT) {
            d?.dismiss()
            android.graphics.BitmapFactory.decodeFile(savedSig.path)?.let { placeSig(it, remember = false) } ?: toast("TTD tersimpan tidak bisa dibaca")
        }, w = WRAP, side = 6)
        box.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(more) }, top = 8)
        d = AlertDialog.Builder(this).setTitle("Tanda tangan")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Tempel", null).setNegativeButton("Batal", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val sig = pad.toBitmap() ?: run { toast("Tanda tangan masih kosong"); return@setOnClickListener }
            d.dismiss()
            placeSig(sig, remember = true)
        }
    }

    /** Tempel TTD di halaman ini (kanan bawah), langsung terpilih untuk diatur. [remember] = simpan untuk dipakai lagi. */
    private fun placeSig(sig: Bitmap, remember: Boolean) {
        if (remember) Thread {
            try { savedSig.parentFile?.mkdirs(); java.io.FileOutputStream(savedSig).use { sig.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) {}
        }.start()
        val page = mv.page
        val pageRatio = if (page != null) page.width.toFloat() / page.height else 0.707f
        val w = 0.32f
        val h = w * pageRatio * sig.height / sig.width
        val st = PageMarks.Stamp(sig, RectF(0.62f, 0.78f, 0.62f + w, 0.78f + h).also {
            if (it.right > 0.97f) it.offset(0.97f - it.right, 0f)
            if (it.bottom > 0.97f) it.offset(0f, 0.97f - it.bottom)
        })
        pageMarks(cur).items.add(st)
        mv.tool = MarkView.Tool.SIGN
        mv.selected = st
        dirty = true
        refreshTools(); mv.invalidate()
    }

    /** Salin TTD terpilih (posisi, ukuran, putaran sama) ke semua halaman lain. */
    private fun copyToAllPages(s: PageMarks.Stamp) {
        confirm("Ke semua halaman", "Tanda tangan ini ditempel di posisi yang sama pada ${total - 1} halaman lainnya.", "Tempel") {
            for (i in 0 until total) if (i != cur) pageMarks(i).items.add(s.copy())
            dirty = true
            toast("Ditempel di ${total - 1} halaman lain")
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
