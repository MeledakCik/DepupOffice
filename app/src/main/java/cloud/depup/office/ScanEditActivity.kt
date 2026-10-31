package cloud.depup.office

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Satu halaman scan: berkas foto + putaran + luruskan halus (derajat) + area potong + sensur + watermark. */
class ScanPage(
    var file: File,
    var rot: Int = 0,
    val crop: RectF = RectF(0f, 0f, 1f, 1f),
    var flip: Boolean = false,
    var skew: Float = 0f,
    val redactions: MutableList<RectF> = mutableListOf(),
    var watermark: String? = null
)

/** Kanvas potong: gambar + 4 sudut + 4 sisi yang bisa digeser, area luar diredupkan. */
class CropView(ctx: Context) : View(ctx) {
    private var shown: Bitmap? = null
    val crop = RectF(0f, 0f, 1f, 1f)
    var onChange: (() -> Unit)? = null

    private val img = RectF()
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat()
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1).toFloat()
    }
    private val hFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.ACCENT; style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat()
    }
    private var drag = 0
    private var lx = 0f
    private var ly = 0f
    private val minSize = 0.08f

    init { background = ctx.shape(0xFF283044.toInt(), 16) }

    fun setBitmap(base: Bitmap, rot: Int, flip: Boolean, c: RectF, skew: Float = 0f) {
        val o = Pdf.orient(base, rot, flip)
        val d = Pdf.deskew(o, skew)
        if (o !== base && o !== d) o.recycle()
        shown = d
        crop.set(c)
        fit()
        invalidate()
    }

    fun setFilter(mode: Int, bright: Int, contrast: Int) {
        val m = Pdf.adjustMatrix(bright, contrast)
        when (mode) {
            1 -> m.postConcat(ColorMatrix().apply { setSaturation(0f) })
            3 -> m.postConcat(Pdf.magicMatrix())
            2 -> {
                m.postConcat(ColorMatrix().apply { setSaturation(0f) })
                m.postConcat(ColorMatrix(floatArrayOf(
                    2.4f, 0f, 0f, 0f, -150f,
                    0f, 2.4f, 0f, 0f, -150f,
                    0f, 0f, 2.4f, 0f, -150f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
        }
        imgPaint.colorFilter = ColorMatrixColorFilter(m)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun fit() {
        val b = shown ?: return
        if (width == 0 || height == 0) return
        val pad = context.dp(22).toFloat()
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        val s = minOf(aw / b.width, ah / b.height)
        val w = b.width * s
        val h = b.height * s
        img.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun cropPx() = RectF(
        img.left + crop.left * img.width(), img.top + crop.top * img.height(),
        img.left + crop.right * img.width(), img.top + crop.bottom * img.height()
    )

    override fun onDraw(c: Canvas) {
        val b = shown ?: return
        c.drawBitmap(b, null, img, imgPaint)
        val r = cropPx()
        c.drawRect(img.left, img.top, img.right, r.top, dim)
        c.drawRect(img.left, r.bottom, img.right, img.bottom, dim)
        c.drawRect(img.left, r.top, r.left, r.bottom, dim)
        c.drawRect(r.right, r.top, img.right, r.bottom, dim)
        for (i in 1..2) {
            val x = r.left + r.width() * i / 3f
            val y = r.top + r.height() * i / 3f
            c.drawLine(x, r.top, x, r.bottom, grid)
            c.drawLine(r.left, y, r.right, y, grid)
        }
        c.drawRect(r, line)
        val rad = context.dp(9).toFloat()
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val pts = arrayOf(
            r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom,
            cx to r.top, cx to r.bottom, r.left to cy, r.right to cy
        )
        pts.forEach { (x, y) -> c.drawCircle(x, y, rad, hFill); c.drawCircle(x, y, rad, hRing) }

        // Lukis sensur hitam (blackout/redaksi)
        if (activeRedactions.isNotEmpty()) {
            val rFill = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
            val rStroke = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = context.dp(2).toFloat() }
            for (red in activeRedactions) {
                val rx1 = img.left + red.left * img.width()
                val ry1 = img.top + red.top * img.height()
                val rx2 = img.left + red.right * img.width()
                val ry2 = img.top + red.bottom * img.height()
                c.drawRect(rx1, ry1, rx2, ry2, rFill)
                c.drawRect(rx1, ry1, rx2, ry2, rStroke)
            }
        }

        val curR = currentRedactRect
        if (isRedactMode && curR != null) {
            val rFill = Paint().apply { color = 0xAA000000.toInt(); style = Paint.Style.FILL }
            val rStroke = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = context.dp(2).toFloat() }
            c.drawRect(curR, rFill)
            c.drawRect(curR, rStroke)
        }

        // Lukis pratinjau watermark
        if (!activeWatermark.isNullOrBlank()) {
            c.save()
            c.rotate(-32f, img.centerX(), img.centerY())
            val wmPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x66DC2626.toInt()
                textSize = minOf(img.width(), img.height()) * 0.08f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            c.drawText(activeWatermark!!, img.centerX(), img.centerY(), wmPaint)
            c.restore()
        }

        if (drag != 0 && shown != null && !isRedactMode) {
            val rx = when {
                drag and 1 != 0 -> crop.left
                drag and 4 != 0 -> crop.right
                else -> (crop.left + crop.right) / 2f
            }
            val ry = when {
                drag and 2 != 0 -> crop.top
                drag and 8 != 0 -> crop.bottom
                else -> (crop.top + crop.bottom) / 2f
            }
            val px = (rx * b.width).toInt().coerceIn(0, b.width - 1)
            val py = (ry * b.height).toInt().coerceIn(0, b.height - 1)

            val size = context.dp(44).toFloat()
            val lCx = width - size - context.dp(16)
            val lCy = size + context.dp(16)

            c.save()
            val path = android.graphics.Path().apply { addCircle(lCx, lCy, size, android.graphics.Path.Direction.CW) }
            c.clipPath(path)
            c.drawColor(Color.WHITE)

            val srcR = android.graphics.Rect(
                (px - size / 2.5f).toInt().coerceAtLeast(0),
                (py - size / 2.5f).toInt().coerceAtLeast(0),
                (px + size / 2.5f).toInt().coerceAtMost(b.width),
                (py + size / 2.5f).toInt().coerceAtMost(b.height)
            )
            val dstR = RectF(lCx - size, lCy - size, lCx + size, lCy + size)
            c.drawBitmap(b, srcR, dstR, imgPaint)

            c.drawLine(lCx - 16, lCy, lCx + 16, lCy, hRing)
            c.drawLine(lCx, lCy - 16, lCx, lCy + 16, hRing)
            c.restore()

            c.drawCircle(lCx, lCy, size, hRing)

            val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = context.dp(10).toFloat(); typeface = Fonts.label(context)
            }
            c.drawRoundRect(lCx - 18, lCy + size - 18, lCx + 18, lCy + size - 2, 8f, 8f, hFill)
            c.drawText("2.5x", lCx - 12, lCy + size - 6, pText)
        }
    }

    val activeRedactions = mutableListOf<RectF>()
    var activeWatermark: String? = null
    var isRedactMode = false
    private var startRx = 0f
    private var startRy = 0f
    private var currentRedactRect: RectF? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (isRedactMode) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRx = e.x
                    startRy = e.y
                    currentRedactRect = RectF(e.x, e.y, e.x, e.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    currentRedactRect = RectF(
                        minOf(startRx, e.x), minOf(startRy, e.y),
                        maxOf(startRx, e.x), maxOf(startRy, e.y)
                    )
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val cr = currentRedactRect
                    if (cr != null && cr.width() > 10f && cr.height() > 10f && img.width() > 0f) {
                        val relLeft = ((cr.left - img.left) / img.width()).coerceIn(0f, 1f)
                        val relTop = ((cr.top - img.top) / img.height()).coerceIn(0f, 1f)
                        val relRight = ((cr.right - img.left) / img.width()).coerceIn(0f, 1f)
                        val relBottom = ((cr.bottom - img.top) / img.height()).coerceIn(0f, 1f)
                        activeRedactions.add(RectF(relLeft, relTop, relRight, relBottom))
                        onChange?.invoke()
                    }
                    currentRedactRect = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = cropPx()
                val t = context.dp(28).toFloat()
                val inY = e.y > c.top - t && e.y < c.bottom + t
                val inX = e.x > c.left - t && e.x < c.right + t
                var m = 0
                if (abs(e.x - c.left) < t && inY) m = m or 1 else if (abs(e.x - c.right) < t && inY) m = m or 4
                if (abs(e.y - c.top) < t && inX) m = m or 2 else if (abs(e.y - c.bottom) < t && inX) m = m or 8
                if (m == 0 && c.contains(e.x, e.y)) m = 16
                drag = m
                lx = e.x; ly = e.y
                if (m != 0) parent?.requestDisallowInterceptTouchEvent(true)
                return m != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == 0 || img.width() <= 0f) return false
                val dx = (e.x - lx) / img.width()
                val dy = (e.y - ly) / img.height()
                lx = e.x; ly = e.y
                if (drag == 16) {
                    val w = crop.width()
                    val h = crop.height()
                    val nl = (crop.left + dx).coerceIn(0f, 1f - w)
                    val nt = (crop.top + dy).coerceIn(0f, 1f - h)
                    crop.set(nl, nt, nl + w, nt + h)
                } else {
                    if (drag and 1 != 0) crop.left = (crop.left + dx).coerceIn(0f, crop.right - minSize)
                    if (drag and 4 != 0) crop.right = (crop.right + dx).coerceIn(crop.left + minSize, 1f)
                    if (drag and 2 != 0) crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minSize)
                    if (drag and 8 != 0) crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minSize, 1f)
                }
                invalidate()
                onChange?.invoke()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}

/** "Edit Hasil Scan": potong, putar, balikkan, filter, kecerahan/kontras, lalu simpan jadi PDF atau JPG. */
class ScanEditActivity : AppCompatActivity() {
    private val pages = mutableListOf<ScanPage>()
    private var cur = 0
    private var mode = 3      // 0 asli, 1 abu-abu, 2 dokumen hitam-putih, 3 magic color
    private var bright = 0
    private var contrast = 0

    private lateinit var cv: CropView
    private lateinit var sub: TextView
    private lateinit var chips: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var brightLabel: TextView
    private lateinit var contrastLabel: TextView
    private lateinit var cropPanel: LinearLayout
    private lateinit var filterPanel: LinearLayout
    private lateinit var segCrop: TextView
    private lateinit var segFilter: TextView
    private val filterCards = mutableListOf<LinearLayout>()
    private val checks = mutableListOf<View>()
    private val previews = LruCache<String, Bitmap>(4)
    private var onPic: ((Boolean) -> Unit)? = null

    private val takePic = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> onPic?.invoke(ok) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringArrayListExtra("files")?.forEach { pages.add(ScanPage(File(it))) }
        if (pages.isEmpty()) { finish(); return }
        mode = intent.getIntExtra("mode", 3)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        // ----- bar atas -----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(8), dp(8), dp(16), dp(8))
            elevation = dp(2).toFloat()
        }
        bar.add(icon(R.drawable.ic_close, C.TEXT, 24).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { askDiscard() }
        }, w = dp(40), h = dp(40))
        val tc = vbox()
        tc.add(tv("Edit Hasil Scan", 18f, C.TEXT, true))
        sub = tv("Hal 1 dari ${pages.size}", 12f, C.MUTED)
        tc.add(sub)
        bar.add(tc, w = 0, weight = 1f, side = 8)

        bar.add(icon(R.drawable.ic_more, C.TEXT, 22).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { v ->
                val pm = android.widget.PopupMenu(this@ScanEditActivity, v)
                pm.menu.add(0, 1, 0, "🔊 Bacakan Teks OCR (Voice Reader)")
                pm.menu.add(0, 2, 1, "🖨️ Cetak Hasil Scan")
                pm.setOnMenuItemClickListener { m ->
                    when (m.itemId) {
                        1 -> {
                            work("Membaca teks dari gambar scan…", {
                                val pg = pages.getOrNull(cur)
                                if (pg != null) {
                                    val bmp = try { Export.process(this@ScanEditActivity, pg, mode, Export.ocrDim(pg), 0, 0) } catch (_: Throwable) { null }
                                    if (bmp != null) Ocr.run(bmp).text else ""
                                } else ""
                            }) { res ->
                                res.onSuccess { txt ->
                                    if (txt.isNotBlank()) VoiceReaderManager.showVoiceReaderDialog(this@ScanEditActivity, "Hasil Scan", txt)
                                    else toast("Tidak ada teks terdeteksi pada gambar scan")
                                }
                            }
                        }
                        2 -> {
                            val pg = pages.getOrNull(cur)
                            val bmp = if (pg != null) try { Export.process(this@ScanEditActivity, pg, mode, 1800, 0, 0) } catch (_: Exception) { null } else null
                            if (bmp != null) PrintHelper.printBitmap(this@ScanEditActivity, bmp, "Hasil_Scan_${cur + 1}")
                            else toast("Gagal memproses gambar untuk dicetak")
                        }
                    }
                    true
                }
                pm.show()
            }
        }, w = dp(40), h = dp(40))

        bar.add(btn("Lanjut  →", true) { save() }, w = WRAP)
        root.add(bar)

        // ----- isi (bisa digulir) -----
        val body = vbox(16).apply { setPadding(dp(16), dp(14), dp(16), dp(20)) }

        // baris: pil potong manual + segmen Potong/Filter
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        seg.add(iconPill(R.drawable.ic_crop, "Potong Manual", C.ACCENT, C.BLUE_BG, 12f), w = WRAP)
        seg.add(View(this), w = 0, weight = 1f)
        val sw = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(C.SURFACE2, 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        segCrop = tv("Potong", 13f, C.ACCENT, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { showTab(0) }
        }
        segFilter = tv("Filter", 13f, C.MUTED, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { showTab(1) }
        }
        sw.add(segCrop, w = WRAP); sw.add(segFilter, w = WRAP)
        seg.add(sw, w = WRAP)
        body.add(seg)

        // kanvas potong + chip Penuh / Reset
        cv = CropView(this)
        val stage = FrameLayout(this)
        stage.addView(cv, FrameLayout.LayoutParams(MATCH, MATCH))
        val ov = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun overlay(res: Int, label: String, f: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(0x99111827.toInt(), 100)
            setPadding(dp(12), dp(7), dp(14), dp(7))
            add(icon(res, Color.WHITE, 16), w = dp(16), h = dp(16))
            add(tv(label, 12f, Color.WHITE, true), w = WRAP, side = 6)
            setOnClickListener { f() }
        }
        ov.add(overlay(R.drawable.ic_full, "Penuh") { resetCrop() }, w = WRAP)
        ov.add(overlay(R.drawable.ic_rotate, "Reset") { resetAll() }, top = 8, w = WRAP)
        stage.addView(ov, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })
        body.add(stage, top = 12, h = dp(380))

        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(chips)
        body.add(hs, top = 12)

        // ---- panel Potong ----
        cropPanel = vbox()
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(C.SURFACE, 16, C.LINE, 1)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        fun tool(res: Int, label: String, tint: Int = C.TEXT, f: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener { f() }
            add(icon(res, tint, 24), w = dp(24), h = dp(24))
            add(tv(label, 12f, tint, true), top = 4, w = WRAP)
        }
        tools.add(tool(R.drawable.ic_rotate, "Putar 90°") { rotate() }, w = 0, weight = 1f)
        tools.add(tool(R.drawable.ic_flip, "Balikkan") { flipPage() }, w = 0, weight = 1f)
        tools.add(tool(R.drawable.ic_full, "Potong Penuh") { resetCrop() }, w = 0, weight = 1f)
        tools.add(tool(R.drawable.ic_trash, "Hapus Hal.", C.RED) { removePage() }, w = 0, weight = 1f)
        cropPanel.add(tools)
        val ai = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(C.BLUE_BG, 16, C.BLUE_LINE, 1)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        ai.add(tool(R.drawable.ic_wand, "Auto-crop AI", C.ACCENT) { autoCrop() }, w = 0, weight = 1f)
        ai.add(tool(R.drawable.ic_tune, "Luruskan", C.ACCENT) { straighten() }, w = 0, weight = 1f)
        ai.add(tool(R.drawable.ic_note, "Teks (OCR)", C.ACCENT) { runOcr() }, w = 0, weight = 1f)
        cropPanel.add(ai, top = 10)

        val secTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(0xFFFFFBEB.toInt(), 16, 0xFFFCD34D.toInt(), 1)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        secTools.add(tool(R.drawable.ic_lock, "Sensur NIK/Data", 0xFFD97706.toInt()) { toggleRedactMode() }, w = 0, weight = 1f)
        secTools.add(tool(R.drawable.ic_spark, "Cap Watermark", 0xFFD97706.toInt()) { showWatermarkDialog() }, w = 0, weight = 1f)
        secTools.add(tool(R.drawable.ic_trash, "Hapus Sensur", C.RED) { clearRedactions() }, w = 0, weight = 1f)
        cropPanel.add(secTools, top = 10)

        if (intent.getBooleanExtra("isKtp", false) || pages.size >= 2) {
            val ktpBanner = card(12, 12).apply {
                background = shape(C.BLUE_BG, 12, C.ACCENT, 1)
                isClickable = true
                setOnClickListener { layoutKtpA4() }
            }
            val kRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            kRow.add(iconBadge(R.drawable.ic_user, C.ACCENT, Color.WHITE, 100, 20), w = dp(38), h = dp(38))
            val kCol = vbox()
            kCol.add(tv("Tata KTP 2-Sisi ke 1 Halaman A4", 14f, C.TEXT, true))
            kCol.add(tv("Gabung KTP Sisi Depan & Belakang dalam 1 lembar A4 siap cetak/upload", 11f, C.MUTED), top = 2)
            kRow.add(kCol, w = 0, weight = 1f, side = 8)
            kRow.add(tv("Tata KTP A4", 11f, Color.WHITE, true).apply {
                background = shape(C.ACCENT, 8)
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }, w = WRAP)
            ktpBanner.add(kRow)
            cropPanel.add(ktpBanner, top = 10)
        }

        cropPanel.add(tv("Seret sudut atau sisi bingkai untuk memotong. Geser bagian dalam untuk memindahkan.", 12f, C.MUTED), top = 10)
        body.add(cropPanel, top = 12)

        // ---- panel Filter ----
        filterPanel = vbox()
        val fh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fh.add(tv("Gaya Filter Dokumen", 17f, C.TEXT, true), w = 0, weight = 1f)
        fh.add(tv("4 PRESET", 11f, C.ACCENT, true), w = WRAP)
        filterPanel.add(fh)
        val fr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf(
            Triple(3, "Magic Color", "Rekomendasi"),
            Triple(2, "B&W Dokumen", "Teks Tajam"),
            Triple(1, "Grayscale", "Foto Netral"),
            Triple(0, "Warna Asli", "Foto & Ilustrasi")
        )
        defs.forEachIndexed { i, (m, name, desc) ->
            val c = vbox(10).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener { mode = m; paintFilters(); applyFilter() }
                tag = m
            }
            val bg = when (m) { 2 -> Color.BLACK; 1 -> 0xFFCBD5E1.toInt(); 3 -> 0xFFE0ECFF.toInt(); else -> 0xFFF1F5F9.toInt() }
            val bc = when (m) { 2 -> Color.WHITE; 1 -> 0xFF475569.toInt(); else -> C.ACCENT }
            val swatch = FrameLayout(this)
            val inner = vbox(8).apply { background = shape(bg, 10) }
            inner.add(View(this).apply { background = shape(bc, 3) }, h = dp(5))
            inner.add(View(this).apply { background = shape(bc and 0x00FFFFFF or 0x88000000.toInt(), 3) }, h = dp(4), top = 5)
            inner.add(View(this).apply { background = shape(bc and 0x00FFFFFF or 0x88000000.toInt(), 3) }, h = dp(4), top = 5)
            swatch.addView(inner, FrameLayout.LayoutParams(MATCH, MATCH))
            val ck = icon(R.drawable.ic_check_ok, C.ACCENT, 18)
            checks.add(ck)
            swatch.addView(ck, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
            c.add(swatch, h = dp(72))
            c.add(tv(name, 12f, C.TEXT, true).apply { gravity = Gravity.CENTER }, top = 8)
            c.add(tv(desc, 10f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 1)
            filterCards.add(c)
            fr.add(c, w = dp(104)).also { if (i > 0) (c.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
        }
        filterPanel.add(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(fr)
        }, top = 10)

        val sl = card(16, 16)
        brightLabel = tv("+0%", 14f, C.ACCENT, true)
        contrastLabel = tv("+0%", 14f, C.ACCENT, true)
        sl.add(sliderRow(R.drawable.ic_bright, "Kecerahan", brightLabel) { bright = it; applyFilter() })
        sl.add(sliderRow(R.drawable.ic_contrast, "Kontras Teks", contrastLabel) { contrast = it; applyFilter() }, top = 14)
        filterPanel.add(sl, top = 16)
        filterPanel.add(tv("Pratinjau filter hanya perkiraan; hasil akhir diproses saat file dibuat.", 11f, C.MUTED), top = 8)
        filterPanel.visibility = View.GONE
        body.add(filterPanel, top = 12)

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        body.add(dots, top = 14)

        root.add(ScrollView(this).apply { addView(body) }, w = MATCH, h = 0, weight = 1f)

        // ----- bar bawah -----
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(8).toFloat()
        }
        foot.add(iconBadge(R.drawable.ic_camera, C.ACCENT, C.BLUE_BG, 12, 22).apply { setOnClickListener { retake() } }, w = dp(48), h = dp(48))
        foot.add(btn("+ Halaman", false, C.ACCENT, R.drawable.ic_add_photo) { addPage() }, w = WRAP, side = 8)
        foot.add(btn("Selesai & Simpan", true, C.ACCENT, R.drawable.ic_pdf) { save() }, w = 0, weight = 1f, side = 0)
            .also { (foot.getChildAt(foot.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
        root.add(foot)

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { askDiscard() }
        })
        paintFilters()
        show()
        autoDetect(pages.toList(), crop = true, skew = true, announce = true)
    }

    private fun showTab(i: Int) {
        cropPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
        filterPanel.visibility = if (i == 1) View.VISIBLE else View.GONE
        segCrop.setTextColor(if (i == 0) C.ACCENT else C.MUTED)
        segCrop.background = if (i == 0) shape(Color.WHITE, 10) else null
        segFilter.setTextColor(if (i == 1) C.ACCENT else C.MUTED)
        segFilter.background = if (i == 1) shape(Color.WHITE, 10) else null
    }

    private fun sliderRow(res: Int, label: String, value: TextView, onChange: (Int) -> Unit): View {
        val box = vbox()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.add(icon(res, C.TEXT, 20), w = dp(20), h = dp(20))
        r.add(tv(label, 14f, C.TEXT, true), w = 0, weight = 1f, side = 8)
        r.add(value, w = WRAP)
        box.add(r)
        val sb = SeekBar(this).apply {
            max = 100
            progress = 50
            progressTintList = ColorStateList.valueOf(C.ACCENT)
            thumbTintList = ColorStateList.valueOf(C.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(C.LINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p - 50
                    value.text = (if (v >= 0) "+" else "") + "$v%"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.add(sb, top = 4)
        return box
    }

    private fun paintFilters() {
        filterCards.forEachIndexed { i, c ->
            val on = c.tag == mode
            c.background = if (on) shape(C.BLUE_BG, 14, C.ACCENT, 2) else shape(C.SURFACE, 14, C.LINE, 1)
            checks[i].visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun applyFilter() = cv.setFilter(mode, bright, contrast)

    private fun preview(pg: ScanPage): Bitmap? {
        val key = pg.file.absolutePath
        previews.get(key)?.let { return it }
        return try {
            Pdf.decode(this, Uri.fromFile(pg.file), 1400).also { previews.put(key, it) }
        } catch (t: Throwable) { fail(t); null }
    }

    private fun show() {
        cur = cur.coerceIn(0, pages.size - 1)
        val pg = pages[cur]
        val b = preview(pg) ?: return
        cv.onChange = { pg.crop.set(cv.crop) }
        cv.setBitmap(b, pg.rot, pg.flip, pg.crop, pg.skew)
        applyFilter()
        sub.text = "Hal ${cur + 1} dari ${pages.size}"
        chips.removeAllViews()
        dots.removeAllViews()
        pages.forEachIndexed { i, _ ->
            val on = i == cur
            chips.add(tv("${i + 1}", 13f, if (on) Color.WHITE else C.MUTED, true).apply {
                gravity = Gravity.CENTER
                background = shape(if (on) C.ACCENT else C.SURFACE2, 100)
                setOnClickListener { cur = i; show() }
            }, w = dp(36), h = dp(36), side = 0).also {
                (chips.getChildAt(chips.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
            }
            dots.add(View(this).apply { background = shape(if (on) C.ACCENT else 0xFFCBD5E1.toInt(), 100) },
                w = dp(if (on) 18 else 8), h = dp(8), side = 0).also {
                (dots.getChildAt(dots.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6)
            }
        }
    }

    private fun rotate() {
        val pg = pages[cur]
        val c = pg.crop
        pg.crop.set(RectF(1f - c.bottom, c.left, 1f - c.top, c.right))
        // Bila sudah dibalik, putar searah jarum jam di layar = kurangi putaran dasar.
        pg.rot = (pg.rot + (if (pg.flip) 270 else 90)) % 360
        show()
    }

    private fun flipPage() {
        val pg = pages[cur]
        val c = pg.crop
        pg.crop.set(RectF(1f - c.right, c.top, 1f - c.left, c.bottom))
        pg.skew = -pg.skew
        pg.flip = !pg.flip
        show()
    }

    private fun resetCrop() { pages[cur].crop.set(0f, 0f, 1f, 1f); show() }

    private fun resetAll() {
        val pg = pages[cur]
        pg.rot = 0; pg.flip = false; pg.skew = 0f; pg.crop.set(0f, 0f, 1f, 1f)
        show()
    }

    private fun removePage() {
        if (pages.size == 1) { toast("Minimal harus ada satu halaman"); return }
        confirm("Hapus halaman ${cur + 1}?", "Halaman ini dibuang dari hasil scan.", "Hapus") {
            pages.removeAt(cur).file.delete()
            show()
        }
    }

    private fun toggleRedactMode() {
        cv.isRedactMode = !cv.isRedactMode
        if (cv.isRedactMode) {
            toast("Mode Sensur Aktif: Gambar/geser kotak di atas NIK or data rahasia untuk menutupi")
        } else {
            toast("Mode Sensur Nonaktif")
        }
        cv.invalidate()
    }

    private fun clearRedactions() {
        pages[cur].redactions.clear()
        cv.activeRedactions.clear()
        cv.invalidate()
        toast("Sensur pada halaman ini dibersihkan")
    }

    private fun showWatermarkDialog() {
        val et = field("Teks Watermark (misal: HANYA UNTUK VERIFIKASI)").apply {
            setText(pages.firstOrNull()?.watermark.orEmpty())
        }
        val box = vbox(12).apply {
            add(tv("Tambahkan Watermark / Cap Air Diagonal", 14f, C.TEXT, true))
            add(et, top = 6)
            add(tv("Pilih Preset Cepat:", 12f, C.MUTED), top = 8)
        }

        val presets = listOf("HANYA UNTUK VERIFIKASI", "DOKUMEN RAHASIA", "CPNS 2026", "SALINAN / COPY", "DRAFT")
        val pRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEachIndexed { i, pText ->
            val chip = tv(pText, 10f, C.ACCENT, true).apply {
                background = shape(C.BLUE_BG, 8)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { et.setText(pText) }
            }
            pRow.add(chip, w = WRAP).also { if (i > 0) (chip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(4) }
        }
        box.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pRow) }, top = 4)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Cap Watermark")
            .setView(box)
            .setPositiveButton("Terapkan ke Semua") { _, _ ->
                val text = et.text.toString().trim()
                pages.forEach { it.watermark = text.ifBlank { null } }
                cv.activeWatermark = text.ifBlank { null }
                cv.invalidate()
                toast(if (text.isNotBlank()) "Watermark '$text' diterapkan ke semua halaman" else "Watermark dihapus")
            }
            .setNegativeButton("Hapus Watermark") { _, _ ->
                pages.forEach { it.watermark = null }
                cv.activeWatermark = null
                cv.invalidate()
                toast("Watermark dihapus")
            }
            .show()
    }

    private fun layoutKtpA4() {
        if (pages.size < 2) {
            toast("KTP 2-Sisi membutuhkan minimal 2 foto (Depan & Belakang)")
            return
        }
        val front = pages[0]
        val back = pages[1]
        work("Menata KTP Depan & Belakang dalam 1 Lembar A4…", {
            renderKtpA4(this, front, back, 1800, bright, contrast)
        }) { r ->
            r.onSuccess { ktpPg ->
                pages.clear()
                pages.add(ktpPg)
                cur = 0
                show()
                toast("✓ KTP Sisi Depan & Belakang berhasil ditata dalam 1 Halaman A4!")
            }.onFailure { fail(it) }
        }
    }

    private fun renderKtpA4(ctx: Context, frontPg: ScanPage, backPg: ScanPage, maxDim: Int, bright: Int, contrast: Int): ScanPage {
        val fBmp = Pdf.renderScan(ctx, frontPg, maxDim, bright, contrast)
        val bBmp = Pdf.renderScan(ctx, backPg, maxDim, bright, contrast)

        val a4W = 1800
        val a4H = 2545
        val page = Bitmap.createBitmap(a4W, a4H, Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.WHITE)
        val canvas = Canvas(page)

        val headerPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E293B.toInt()
            textSize = 48f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val subPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("SALINAN KARTU IDENTITAS (KTP / SIM / ID CARD)", a4W / 2f, 120f, headerPaint)
        val stamp = SimpleDateFormat("dd MMMM yyyy", Locale("id")).format(Date())
        canvas.drawText("Dipindai via Depup Office • Tanggal: $stamp", a4W / 2f, 170f, subPaint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        canvas.drawLine(100f, 210f, a4W - 100f, 210f, linePaint)

        val cardW = 1200f
        val cardH = cardW / 1.586f
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        val labelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0284C7.toInt(); textSize = 32f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }

        // --- Depan ---
        val fLeft = (a4W - cardW) / 2f
        val fTop = 320f
        val fRect = RectF(fLeft, fTop, fLeft + cardW, fTop + cardH)
        canvas.drawBitmap(fBmp, null, fRect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawRoundRect(fRect, 20f, 20f, borderPaint)
        canvas.drawText("● SISI DEPAN (FRONT)", a4W / 2f, fTop + cardH + 50f, labelPaint)

        // --- Belakang ---
        val bLeft = (a4W - cardW) / 2f
        val bTop = fTop + cardH + 180f
        val bRect = RectF(bLeft, bTop, bLeft + cardW, bTop + cardH)
        canvas.drawBitmap(bBmp, null, bRect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawRoundRect(bRect, 20f, 20f, borderPaint)
        canvas.drawText("● SISI BELAKANG (BACK)", a4W / 2f, bTop + cardH + 50f, labelPaint)

        fBmp.recycle(); bBmp.recycle()

        val ktpFile = File(ctx.cacheDir, "scan/ktp_a4_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        java.io.FileOutputStream(ktpFile).use { page.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        page.recycle()

        return ScanPage(ktpFile)
    }

    private fun newFile(): File {
        val dir = File(cacheDir, "scan").apply { mkdirs() }
        return File(dir, "scan_${System.currentTimeMillis()}.jpg")
    }

    private fun addPage() {
        val f = newFile()
        onPic = { ok ->
            if (ok) { pages.add(ScanPage(f)); cur = pages.size - 1; show() } else f.delete()
        }
        takePic.launch(Files.uriFor(this, f))
    }

    private fun retake() {
        val f = newFile()
        onPic = { ok ->
            if (ok) {
                val pg = pages[cur]
                pg.file.delete()
                previews.remove(pg.file.absolutePath)
                pg.file = f; pg.rot = 0; pg.flip = false; pg.skew = 0f; pg.crop.set(0f, 0f, 1f, 1f)
                show()
            } else f.delete()
        }
        takePic.launch(Files.uriFor(this, f))
    }

    private fun askDiscard() {
        confirm("Buang hasil scan?", "Semua halaman yang belum disimpan akan dihapus.", "Buang") { cleanup(); finish() }
    }

    private fun cleanup() { pages.forEach { it.file.delete() } }

    // ================= AUTO-CROP AI / LURUSKAN / OCR =================
    private class Auto(val crop: RectF?, val skew: Float)

    /** Jalankan di thread latar: deteksi tepi dokumen dan/atau kemiringan pada hasil putar/balik saat ini. */
    private fun detectFor(pg: ScanPage, doCrop: Boolean, doSkew: Boolean): Auto {
        val base = Pdf.decode(this, Uri.fromFile(pg.file), 1000)
        val o = Pdf.orient(base, pg.rot, pg.flip)
        var d: Bitmap? = null
        try {
            var skew = pg.skew
            if (doSkew) skew = ScanAi.detectSkew(o, ScanAi.detectCrop(o))
            d = Pdf.deskew(o, skew)
            val crop = if (doCrop) ScanAi.detectCrop(d) else null
            return Auto(crop, skew)
        } finally {
            if (d != null && d !== o) d.recycle()
            if (o !== base) o.recycle()
            base.recycle()
        }
    }

    private fun autoDetect(list: List<ScanPage>, crop: Boolean, skew: Boolean, announce: Boolean) {
        work("Mendeteksi tepi dokumen…", { list.map { detectFor(it, crop, skew) } }) { r ->
            r.onSuccess { res ->
                var found = 0; var fixed = 0; var full = 0
                list.forEachIndexed { i, pg ->
                    val a = res[i]
                    if (skew && kotlin.math.abs(a.skew - pg.skew) > 0.05f) { pg.skew = a.skew; fixed++ }
                    if (crop) a.crop?.let { c ->
                        pg.crop.set(c)
                        if (c.width() * c.height() < 0.97f) found++ else full++
                    }
                }
                if (pages.isNotEmpty()) show()
                if (announce) {
                    if (found > 0) toast("Tepi terdeteksi di $found halaman" + if (fixed > 0) " · $fixed diluruskan" else "")
                    else if (fixed > 0) toast("$fixed halaman diluruskan")
                } else {
                    if (crop && found == 0) toast(
                        if (full > 0) "Dokumen sudah memenuhi bingkai." else "Tepi dokumen tidak terdeteksi. Atur bingkai secara manual."
                    )
                    if (crop && found > 0) toast("Tepi dokumen terdeteksi")
                    if (!crop && skew) toast(if (fixed > 0) "Halaman diluruskan" else "Halaman sudah lurus")
                }
            }.onFailure { fail(it) }
        }
    }

    private fun autoCrop() = autoDetect(listOf(pages[cur]), crop = true, skew = false, announce = false)

    private fun straighten() = autoDetect(listOf(pages[cur]), crop = false, skew = true, announce = false)

    private fun runOcr() {
        val pg = pages[cur]
        val m = mode; val b = bright; val c = contrast
        work("Membaca teks (OCR)…", { Export.ocrPage(this, pg, m, b, c) }) { r ->
            r.onSuccess { showOcr(it) }.onFailure { fail(it) }
        }
    }

    private fun showOcr(res: OcrPage) = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_note, C.ACCENT, C.BLUE_BG, "Teks Hasil OCR Dokumen", "Halaman ${cur + 1} · ${res.paras.size} paragraf, ${res.blocks.count { it is Table }} tabel terdeteksi", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
        if (res.text.isBlank()) {
            pad.add(tv("Tidak ada teks yang terbaca. Coba gunakan filter Magic Color atau B&W Dokumen, dan pastikan pencahayaan foto tajam.", 13f, C.MUTED))
        } else {
            if (res.conf < Ocr.LOW_CONF) {
                pad.add(tv("⚠️ OCR kurang yakin (${(res.conf * 100).toInt()}%). Biasanya karena tulisan tangan atau foto buram — OCR offline hanya andal untuk teks cetak. Untuk hasil terbaik: foto lebih dekat & terang (ketuk layar untuk fokus), atau simpan sebagai PDF/Foto.", 12f, C.RED))
            }
            val t = tv(res.text, 14f, C.TEXT).apply {
                setTextIsSelectable(true)
                background = shape(C.SURFACE2, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            pad.add(t)

            // Penerjemah Teks Dokumen
            var isTranslated = false
            val transBtn = btn("🌐 Terjemahkan Dokumen (Inggris ↔ Indonesia)", false, C.INDIGO, R.drawable.ic_spark) {
                isTranslated = !isTranslated
                t.text = Pdf.translateDocText(res.text, isTranslated)
                toast(if (isTranslated) "Teks diterjemahkan ke Bahasa Indonesia" else "Teks kembali ke Asli")
            }
            pad.add(transBtn, top = 10)

            // Ringkasan Poin-Poin Utama Dokumen
            val summary = Pdf.extractDocSummary(res.text)
            if (summary.dates.isNotEmpty() || summary.numbers.isNotEmpty() || summary.amounts.isNotEmpty()) {
                val sCard = card(12, 12).apply { background = shape(0xFFFEF3C7.toInt(), 12, 0xFFD97706.toInt(), 1) }
                val sCol = vbox()
                sCol.add(tv("📌 RINGKASAN POIN KRUSIAL DOKUMEN", 12f, 0xFFD97706.toInt(), true))
                if (summary.numbers.isNotEmpty()) sCol.add(tv("Nomor/Pasal: ${summary.numbers.joinToString(", ")}", 12f, C.TEXT, true), top = 2)
                if (summary.dates.isNotEmpty()) sCol.add(tv("Tanggal: ${summary.dates.joinToString(", ")}", 12f, C.TEXT, true), top = 2)
                if (summary.amounts.isNotEmpty()) sCol.add(tv("Nominal: ${summary.amounts.joinToString(", ")}", 12f, C.TEXT, true), top = 2)
                sCard.add(sCol)
                pad.add(sCard, top = 10)
            }

            // Deteksi Struk / Nota Keuangan
            val receipt = Pdf.parseReceipt(res.text)
            if (receipt.total != "Rp 0") {
                val rCard = card(12, 12).apply { background = shape(0xFFE0F2FE.toInt(), 12, 0xFF0284C7.toInt(), 1) }
                val rCol = vbox()
                rCol.add(tv("🧾 DETEKSI STRUK / NOTA KEUANGAN", 12f, 0xFF0284C7.toInt(), true))
                rCol.add(tv("Merchant: ${receipt.merchant} • Tanggal: ${receipt.date}", 12f, C.TEXT, true), top = 2)
                rCol.add(tv("Total Bayar: ${receipt.total}", 15f, 0xFF0284C7.toInt(), true), top = 2)
                rCard.add(rCol)
                rCard.add(btn("Ekspor Struk ke Excel (.xlsx)", true, C.TEAL, R.drawable.ic_sheet) {
                    dismiss()
                    startActivity(Intent(this@ScanEditActivity, SheetEditorActivity::class.java))
                }, top = 10)
                pad.add(rCard, top = 10)
            }

            pad.add(tv("Pilihan Format Simpan / Ekspor (seperti WPS Office):", 13f, C.TEXT, true), top = 14)

            val expGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row1.add(btn("📷 Cuma Foto (JPG)", false, C.SKY, R.drawable.ic_image) {
                dismiss()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                export("Scan_Foto_$stamp", Export.F_JPG, 1, Export.ASPECTS[0], false, null)
            }, w = 0, weight = 1f)

            row1.add(btn("📄 Dokumen PDF", false, C.RED, R.drawable.ic_pdf) {
                dismiss()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                export("Scan_PDF_$stamp", Export.F_PDF, 1, Export.ASPECTS[0], true, null)
            }, w = 0, weight = 1f, side = 6)
            expGrid.add(row1)

            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row2.add(btn("📝 Teks / Word (.docx)", false, C.ACCENT, R.drawable.ic_doc) {
                dismiss()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                export("Scan_Word_$stamp", Export.F_WORD, 0, Export.ASPECTS[0], true, null)
            }, w = 0, weight = 1f)

            row2.add(btn("Salin Teks", true, C.ACCENT, R.drawable.ic_check) {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                // HTML + teks: Word/Google Docs menempel dengan pangkat, indeks, dan tabel asli; aplikasi lain dapat teks (tabel = TAB).
                val clip = if (res.html.isNotBlank())
                    android.content.ClipData.newHtmlText("OCR", t.text.toString(), res.html)
                else android.content.ClipData.newPlainText("OCR", t.text.toString())
                cm.setPrimaryClip(clip)
                toast("Disalin — tempel ke Word untuk tabel & pangkat sesuai aslinya")
            }, w = 0, weight = 1f, side = 6)
            expGrid.add(row2, top = 8)

            pad.add(expGrid, top = 8)
        }
        body.add(pad)
    }

    // ================= MODAL SIMPAN & EKSPOR =================
    private class OptDef(val title: String, val desc: String, val tag: String? = null)

    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(C.SURFACE, 12, C.LINE, 1)
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }
        val col = vbox()
        col.add(tv(title, 14f, C.TEXT, true))
        col.add(tv(desc, 12f, C.MUTED), top = 2)
        r.add(col, w = 0, weight = 1f)
        val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = checked
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(st, intArrayOf(0x661E60D5, 0xFFCBD5E1.toInt()))
            thumbTintList = ColorStateList(st, intArrayOf(C.ACCENT, 0xFFF8FAFC.toInt()))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        r.add(sw, w = WRAP, side = 8)
        return r
    }

    private fun save() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        var fmt = Export.F_PDF
        var opt = 1
        var aIdx = 0
        var ocr = false
        var lock = false
        var token = 0
        val total = pages.size
        sheet { body, dismiss ->
            sheetHeader(body, R.drawable.ic_image, C.ACCENT, C.BLUE_BG, "Simpan & Ekspor Dokumen",
                "$total halaman · pilih format, rasio & kualitas", dismiss)
            val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }

            val ext = pill(".pdf", C.ACCENT, C.BLUE_BG, 12f)
            val et = android.widget.EditText(this).apply {
                setText("Scan_Dokumen_$stamp")
                setTextColor(C.TEXT); textSize = 15f
                typeface = Fonts.body(this@ScanEditActivity)
                isSingleLine = true; background = null
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setSelectAllOnFocus(true)
            }
            val optBox = vbox()
            val optCards = mutableListOf<LinearLayout>()
            val marks = mutableListOf<ImageView>()
            val sizeTvs = mutableListOf<TextView>()
            val fmtCards = mutableListOf<LinearLayout>()
            val aspectChips = mutableListOf<TextView>()
            val aspectSection = vbox()
            val extraSection = vbox()
            val passBox = vbox()
            val passEt = android.widget.EditText(this).apply {
                hint = "Password (min. 4 karakter)"
                setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 14f
                typeface = Fonts.body(this@ScanEditActivity)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                background = shape(C.SURFACE, 12, C.LINE, 1)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }

            fun defs(): List<OptDef> = when (fmt) {
                Export.F_PDF -> listOf(
                    OptDef("Kualitas Tinggi", "2000 px · detail maksimal"),
                    OptDef("Kompresi Cerdas", "1600 px · teks tajam, siap cetak/arsip", "Rekomendasi"),
                    OptDef("Ukuran Sangat Ringan", "1000 px · cocok WhatsApp, email & chat")
                )
                Export.F_WORD -> listOf(
                    OptDef("Teks bisa diedit (OCR)", "Teks dikenali otomatis, bisa disunting di Word", "Rekomendasi"),
                    OptDef("Halaman sebagai gambar", "Tampilan persis seperti hasil scan")
                )
                else -> listOf(
                    OptDef("1080p Full HD", "1920 px · cepat untuk media sosial / WhatsApp"),
                    OptDef("2K Quad HD", "2560 px · teks & detail seimbang", "Rekomendasi"),
                    OptDef("4K Ultra HD", "3840 px · kualitas cetak maksimal")
                )
            }

            fun updateVisibility() {
                aspectSection.visibility = if (fmt == Export.F_WORD && opt == 0) View.GONE else View.VISIBLE
                extraSection.visibility = if (fmt == Export.F_PDF) View.VISIBLE else View.GONE
                passBox.visibility = if (lock) View.VISIBLE else View.GONE
            }

            fun paintOpts() {
                optCards.forEachIndexed { i, c ->
                    val on = i == opt
                    c.background = if (on) shape(C.BLUE_BG, 12, C.ACCENT, 2) else shape(C.SURFACE, 12, C.LINE, 1)
                    if (on) { marks[i].setImageResource(R.drawable.ic_check_ok); marks[i].setColorFilter(C.ACCENT) }
                    else {
                        marks[i].clearColorFilter()
                        marks[i].setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Color.WHITE); setStroke(dp(2), 0xFFCBD5E1.toInt()); setSize(dp(22), dp(22))
                        })
                    }
                }
            }

            /** Hitung perkiraan ukuran file tiap pilihan (memproses halaman 1 sebagai contoh) di thread latar. */
            fun refreshSizes() {
                val my = ++token
                val f = fmt; val a = Export.ASPECTS[aIdx]; val o = ocr
                val m = mode; val br = bright; val ct = contrast
                val first = pages.firstOrNull() ?: return
                sizeTvs.forEach { it.text = "Menghitung…" }
                val count = sizeTvs.size
                Thread {
                    for (i in 0 until count) {
                        if (token != my || isFinishing) return@Thread
                        val text = if (f == Export.F_WORD && i == 0) "Ringan"
                        else try { "≈ " + Files.fmtSize(Export.estimatePage(this, first, f, i, m, br, ct, a, o) * total) }
                        catch (t: Throwable) { "–" }
                        runOnUiThread { if (token == my && i < sizeTvs.size) sizeTvs[i].text = text }
                    }
                }.start()
            }

            fun buildOpts() {
                optBox.removeAllViews(); optCards.clear(); marks.clear(); sizeTvs.clear()
                defs().forEachIndexed { i, d ->
                    val c = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                        isClickable = true
                        setOnClickListener { opt = i; paintOpts(); updateVisibility() }
                    }
                    val mk = ImageView(this); marks.add(mk)
                    c.add(mk, w = dp(22), h = dp(22))
                    val col = vbox()
                    val t = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    t.add(tv(d.title, 15f, C.TEXT, true), w = WRAP)
                    if (d.tag != null) t.add(pill(d.tag, Color.WHITE, C.ACCENT, 10f), w = WRAP).also {
                        (t.getChildAt(t.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
                    }
                    col.add(t)
                    col.add(tv(d.desc, 12f, C.MUTED), top = 3)
                    c.add(col, w = 0, weight = 1f, side = 12)
                    val sz = tv("", 12f, C.TEAL, true)
                    sizeTvs.add(sz)
                    c.add(sz, w = WRAP)
                    optCards.add(c)
                    optBox.add(c, top = if (i == 0) 0 else 8)
                }
                paintOpts()
                updateVisibility()
                refreshSizes()
            }

            fun paintFmt() {
                val cardIdx = when (fmt) { Export.F_PDF -> 0; Export.F_JPG -> 1; else -> 2 }
                fmtCards.forEachIndexed { i, c ->
                    c.background = if (i == cardIdx) shape(C.BLUE_BG, 14, C.ACCENT, 2) else shape(C.SURFACE, 14, C.LINE, 1)
                }
                ext.text = when (fmt) { Export.F_PDF -> ".pdf"; Export.F_WORD -> ".docx"; else -> ".jpg" }
                opt = if (fmt == Export.F_WORD) 0 else 1
                buildOpts()
            }

            fun paintAspect() {
                aspectChips.forEachIndexed { i, c ->
                    val on = i == aIdx
                    c.setTextColor(if (on) Color.WHITE else C.MUTED)
                    c.background = shape(if (on) C.ACCENT else C.SURFACE2, 100)
                }
            }

            // ----- Format Berkas -----
            pad.add(tv("Pilih Mode Simpan (seperti WPS Office)", 14f, C.TEXT, true))
            val fmtRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                Triple(R.drawable.ic_pdf, "Dokumen PDF", ".PDF"),
                Triple(R.drawable.ic_image, "Cuma Foto (JPG)", ".JPG"),
                Triple(R.drawable.ic_doc, "Teks / Word", ".DOCX")
            ).forEachIndexed { i, (res, name, e) ->
                val targetFmt = when (i) { 0 -> Export.F_PDF; 1 -> Export.F_JPG; else -> Export.F_WORD }
                val c = vbox(12).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    setOnClickListener { fmt = targetFmt; paintFmt() }
                }
                c.add(icon(res, C.ACCENT, 24), w = dp(24), h = dp(24))
                c.add(tv(name, 12f, C.TEXT, true).apply { gravity = Gravity.CENTER }, top = 6)
                c.add(pill(e, C.ACCENT, C.BLUE_BG, 10f), top = 4, w = WRAP)
                fmtCards.add(c)
                fmtRow.add(c, w = 0, weight = 1f).also { if (i > 0) (c.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
            }
            pad.add(fmtRow, top = 8)

            // ----- Nama Berkas -----
            pad.add(tv("Nama Berkas", 14f, C.TEXT, true), top = 18)
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(C.SURFACE, 12, C.LINE, 1)
                setPadding(dp(14), dp(4), dp(10), dp(4))
            }
            nameRow.add(icon(R.drawable.ic_doc, C.MUTED, 20), w = dp(20), h = dp(20))
            nameRow.add(et, w = 0, weight = 1f)
            nameRow.add(ext, w = WRAP)
            pad.add(nameRow, top = 8)

            // ----- Rasio Aspek -----
            aspectSection.add(tv("Rasio Aspek", 14f, C.TEXT, true), top = 18)
            val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            Export.ASPECTS.forEachIndexed { i, a ->
                val ch = tv(a.name, 13f, C.MUTED, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    setOnClickListener { aIdx = i; paintAspect(); refreshSizes() }
                }
                aspectChips.add(ch)
                chipRow.add(ch, w = WRAP).also { (ch.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
            }
            aspectSection.add(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipRow)
            }, top = 8)
            aspectSection.add(tv("Otomatis = A4 (ikut arah halaman) · Asli = ukuran gambar · lainnya = kanvas putih bermargin.", 11f, C.MUTED), top = 6)
            pad.add(aspectSection)

            // ----- Kualitas & Ukuran -----
            pad.add(tv("Kualitas & Ukuran File", 14f, C.TEXT, true), top = 18)
            pad.add(optBox, top = 8)
            pad.add(tv("Ukuran adalah perkiraan dari halaman 1 × jumlah halaman; hasil akhir bisa sedikit berbeda.", 11f, C.MUTED), top = 8)

            // ----- Opsi PDF: OCR + password -----
            extraSection.add(tv("Opsi Tambahan PDF", 14f, C.TEXT, true), top = 18)
            extraSection.add(switchRow("Teks bisa dicari & disalin (OCR)",
                "Lapisan teks tak terlihat. Lebih lama; resolusi minimal 1600 px.", false) { ocr = it; refreshSizes() }, top = 8)
            extraSection.add(switchRow("Kunci dengan password",
                "Enkripsi AES-128. Password tidak bisa dipulihkan jika lupa.", false) { lock = it; updateVisibility() }, top = 8)
            passBox.add(passEt, top = 8)
            passBox.add(tv("PDF terkunci dibuka lewat aplikasi PDF lain (Chrome, Drive, Adobe), bukan pembaca bawaan Depup.", 11f, C.MUTED), top = 6)
            extraSection.add(passBox)
            pad.add(extraSection)

            paintAspect()
            paintFmt()
            body.add(pad)

            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(20), dp(12), dp(20), dp(16))
            }
            bar.add(btn("Batal", false) { token++; dismiss() }, w = WRAP)
            bar.add(btn("Konfirmasi Simpan", true, C.ACCENT, R.drawable.ic_check) {
                val pw = passEt.text.toString()
                if (fmt == Export.F_PDF && lock && pw.length < 4) { toast("Password minimal 4 karakter"); return@btn }
                val clean = et.text.toString().trim().replace(Regex("[\\/:*?\"<>|]+"), "_")
                    .removeSuffix(".pdf").removeSuffix(".docx").removeSuffix(".jpg").ifBlank { "Scan_Dokumen_$stamp" }
                token++
                dismiss()
                export(clean, fmt, opt, Export.ASPECTS[aIdx], ocr, if (fmt == Export.F_PDF && lock) pw else null)
            }, w = 0, weight = 1f, side = 6)
            body.add(bar)
        }
    }

    private fun export(name: String, fmt: Int, opt: Int, a: AspectOpt, ocr: Boolean, password: String?) {
        val list = pages.toList()
        val m = mode; val b = bright; val c = contrast
        fun done(out: File) {
            cleanup()
            setResult(RESULT_OK, Intent().putExtra("out", out.absolutePath))
            finish()
        }
        when (fmt) {
            Export.F_PDF -> {
                val out = Files.namedOut(this, name, "pdf")
                val msg = (if (ocr) "Membaca teks & membuat PDF…" else "Memproses & membuat PDF…") + if (password != null) " (terkunci)" else ""
                work(msg, { Export.pdf(this, list, out, m, b, c, opt, a, ocr, password) }) { r ->
                    r.onSuccess { done(out) }.onFailure { fail(it) }
                }
            }
            Export.F_WORD -> {
                val out = Files.namedOut(this, name, "docx")
                work(if (opt == 0) "Membaca teks & membuat Word…" else "Membuat dokumen Word…",
                    { Export.docx(this, list, out, m, b, c, asImage = opt == 1, a = a) }) { r ->
                    r.onSuccess { done(out) }.onFailure { fail(it) }
                }
            }
            else -> {
                work("Memproses & menyimpan JPG…", { Export.jpg(this, list, name, m, b, c, opt, a) }) { r ->
                    r.onSuccess { n ->
                        cleanup()
                        setResult(RESULT_OK, Intent().putExtra("jpg", n))
                        finish()
                    }.onFailure { fail(it) }
                }
            }
        }
    }
}
