package cloud.depup.office

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.animation.ValueAnimator
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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
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
    var watermark: String? = null,
    /** Foto asli sebelum koreksi perspektif (null = belum pernah diluruskan). Dipakai untuk "Reset". */
    var origFile: File? = null
) {
    /**
     * Area potong bebas: 4 titik sudut (lihat [Quad]). Boleh miring / tidak sejajar; saat disimpan hasilnya
     * otomatis ditegakkan menjadi persegi panjang 90°. [crop] selalu = kotak pembatasnya.
     */
    var quad: FloatArray = Quad.full()
        private set

    fun setQuad(q: FloatArray) {
        quad = q.copyOf()
        val b = Quad.bbox(quad)
        crop.set(b[0], b[1], b[2], b[3])
    }
}

/**
 * Kanvas potong BEBAS: 4 titik sudut bisa digeser ke mana saja (mengikuti dokumen yang miring / berperspektif),
 * 4 pegangan di tengah sisi menggeser satu sisi, dan bagian dalam menggeser seluruh area. Area luar diredupkan.
 * Hasil akhirnya otomatis ditegakkan jadi persegi panjang bersudut 90° (lihat [Pdf.warp]).
 */
class CropView(ctx: Context) : View(ctx) {
    private var shown: Bitmap? = null
    /** 4 titik sudut (pecahan 0..1): KiriAtas, KananAtas, KananBawah, KiriBawah. */
    var quad: FloatArray = Quad.full()
        private set
    var onChange: (() -> Unit)? = null
    /** Dipanggil saat jari diangkat setelah menggeser (untuk riwayat/hint). */
    var onRelease: (() -> Unit)? = null
    /** Garis pindai di atas foto selama AI mendeteksi tepi kertas / meluruskan halaman. */
    val sweep = ScanSweep(this)
    /** Sedang mendeteksi: sentuhan diabaikan (hasil deteksi akan memasang titik sudut). */
    var busy = false
    private val scanDim = Paint().apply { color = Color.BLACK }

    private val img = RectF()
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.GOLD; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1).toFloat()
    }
    private val hFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.ACCENT; style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat()
    }
    private val hActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.GOLD }
    private val bad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.RED; style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat()
    }
    // Objek gambar dipakai ulang: kanvas ini digambar ulang tiap frame selama sudut digeser (60 fps) — membuat
    // Path/RectF/Paint baru di setiap frame memicu GC dan menyendat gerakan.
    private val qPath = android.graphics.Path()
    private val outPath = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
    private val loupePath = android.graphics.Path()
    private val tmpRF = RectF()
    private val srcR = android.graphics.Rect()
    private val dstR = RectF()
    private val redFill = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val redLive = Paint().apply { color = 0xAA000000.toInt(); style = Paint.Style.FILL }
    private val redStroke = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat() }
    private val wmPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66B2402F.toInt()
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    // drag: 0 = tidak ada, 1..4 = sudut ke-(n-1), 11..14 = sisi ke-(n-11), 20 = geser semua
    private var drag = 0
    private var lx = 0f
    private var ly = 0f
    private var rejected = false

    // Pratinjau Magic Color yang SEBENARNYA (normalisasi bayangan) dihitung di thread latar; sementara itu dipakai
    // pendekatan matriks warna supaya tidak ada kedipan. Urutan (filter lalu kecerahan/kontras) = sama dengan ekspor.
    private var magicBmp: Bitmap? = null
    private var magicGen = 0
    private var magicStarted = -1
    private var fMode = 0
    private var fBright = 0
    private var fContrast = 0

    // ---- transisi tampilan (hanya visual; data halaman sudah berubah duluan) ----
    private var spin = 0f          // derajat putar sementara di sekitar pusat gambar
    private var spinScale = 1f     // skala sementara supaya ukuran lama → baru menyambung mulus
    private var flipS = 1f         // -1..1: balik horizontal seperti kartu
    private var tAnim: ValueAnimator? = null
    private var qAnim: ValueAnimator? = null
    private var fAnim: ValueAnimator? = null
    private var curM = FloatArray(20).also { ColorMatrix().getArray().copyInto(it) }

    init { background = ctx.shape(C.NAVY_PANEL, 16) }

    /** Kotak gambar saat ini (salinan) — simpan SEBELUM ganti bitmap untuk [spinIn]. */
    fun imgRect() = RectF(img)

    /** Sesudah diputar 90° searah jarum jam: gambar baru masuk dengan berputar dari posisi lama, berpegas. */
    fun spinIn(fromDeg: Float, old: RectF) {
        tAnim?.cancel()
        if (!Motion.on() || img.height() == 0f) { spin = 0f; spinScale = 1f; invalidate(); return }
        val s0 = if (img.height() > 0f) old.width() / img.height() else 1f
        tAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.SPRING_MS; interpolator = Motion.SPRING
            addUpdateListener { val f = it.animatedValue as Float; spin = fromDeg * (1f - f); spinScale = s0 + (1f - s0) * f; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { spin = 0f; spinScale = 1f; invalidate() }
            })
            start()
        }
    }

    /** Sesudah dibalik: gambar berbalik seperti kartu (lebar −1 → 1), berpegas lembut. */
    fun flipIn() {
        tAnim?.cancel()
        if (!Motion.on()) { flipS = 1f; invalidate(); return }
        tAnim = ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = Motion.SPRING_SOFT_MS; interpolator = Motion.SPRING_SOFT
            addUpdateListener { flipS = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { flipS = 1f; invalidate() }
            })
            start()
        }
    }

    /** Titik sudut meluncur dari posisi [old] ke posisinya sekarang (Penuh, Auto-crop, Reset). */
    fun morphQuadFrom(old: FloatArray) {
        qAnim?.cancel()
        val to = quad.copyOf()
        if (!Motion.on() || old.size != to.size || old.contentEquals(to)) return
        val q = FloatArray(to.size)
        qAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.SPRING_SOFT_MS; interpolator = Motion.SPRING_SOFT
            addUpdateListener {
                val f = it.animatedValue as Float
                for (i in q.indices) q[i] = (old[i] + (to[i] - old[i]) * f).coerceIn(0f, 1f)
                quad = q; invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { quad = to; invalidate() }
                override fun onAnimationCancel(a: android.animation.Animator) { quad = to; invalidate() }
            })
            start()
        }
    }

    /** Gambar tampilan (sudah diputar/dibalik/diluruskan) — dipakai untuk pratinjau hasil. */
    val bitmap: Bitmap? get() = shown

    fun setBitmap(base: Bitmap, rot: Int, flip: Boolean, q: FloatArray, skew: Float = 0f) {
        val o = Pdf.orient(base, rot, flip)
        val d = Pdf.deskew(o, skew)
        if (o !== base && o !== d) o.recycle()
        shown = d
        magicBmp = null
        magicGen++
        quad = q.copyOf()
        fit()
        invalidate()
    }

    fun setQuad(q: FloatArray) { quad = q.copyOf(); invalidate() }

    private fun startMagic() {
        val src = shown ?: return
        if (magicStarted == magicGen) return
        magicStarted = magicGen
        val gen = magicGen
        Thread {
            val out = try { Pdf.magicBitmap(src) } catch (_: Throwable) { null }
            post {
                if (out != null && gen == magicGen && shown === src) {
                    magicBmp = out
                    setFilter(fMode, fBright, fContrast)
                }
            }
        }.start()
    }

    fun setFilter(mode: Int, bright: Int, contrast: Int, animate: Boolean = false) {
        val bmpChange = (mode == 3) != (fMode == 3) && magicBmp != null
        fMode = mode; fBright = bright; fContrast = contrast
        val m = Pdf.adjustMatrix(bright, contrast)
        when (mode) {
            1 -> m.postConcat(ColorMatrix().apply { setSaturation(0f) })
            3 -> if (magicBmp == null) { m.postConcat(Pdf.magicMatrix()); startMagic() }
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
        val target = m.getArray().copyOf()
        fAnim?.cancel()
        if (!animate || bmpChange || !Motion.on()) {
            target.copyInto(curM); imgPaint.colorFilter = ColorMatrixColorFilter(m); invalidate(); return
        }
        // filter baru "mengalir" masuk: matriks warna diinterpolasi dari yang tampil sekarang
        val from = curM.copyOf()
        val tmp = ColorMatrix()
        fAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.SLOW; interpolator = Motion.STANDARD
            addUpdateListener {
                val f = it.animatedValue as Float
                for (i in 0 until 20) curM[i] = from[i] + (target[i] - from[i]) * f
                tmp.set(curM); imgPaint.colorFilter = ColorMatrixColorFilter(tmp); invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    target.copyInto(curM); imgPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix(target)); invalidate()
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun fit() {
        val b = shown ?: return
        if (width == 0 || height == 0) return
        val pad = context.dp(24).toFloat()
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        val s = minOf(aw / b.width, ah / b.height)
        val w = b.width * s
        val h = b.height * s
        img.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun px(i: Int) = img.left + quad[2 * i] * img.width()
    private fun py(i: Int) = img.top + quad[2 * i + 1] * img.height()

    /** Jalur segi-empat potong (objek yang sama dipakai ulang — jangan disimpan pemanggil). */
    private fun quadPath(): android.graphics.Path = qPath.apply {
        reset()
        moveTo(px(0), py(0)); lineTo(px(1), py(1)); lineTo(px(2), py(2)); lineTo(px(3), py(3)); close()
    }

    override fun onDraw(c: Canvas) {
        val b = shown ?: return
        val tx = spin != 0f || spinScale != 1f || flipS != 1f
        if (tx) {
            c.save()
            c.rotate(spin, img.centerX(), img.centerY())
            c.scale(spinScale * flipS, spinScale, img.centerX(), img.centerY())
        }
        try { drawAll(c, b) } finally { if (tx) c.restore() }
    }

    private fun drawAll(c: Canvas, b: Bitmap) {
        c.drawBitmap(if (fMode == 3) (magicBmp ?: b) else b, null, img, imgPaint)

        // Redupkan area di luar segi-empat
        val outside = outPath.apply {
            reset()
            fillType = android.graphics.Path.FillType.EVEN_ODD
            addRect(img, android.graphics.Path.Direction.CW)
            addPath(quadPath())
        }
        c.drawPath(outside, dim)

        // Pindaian AI: foto sedikit diredupkan agar garis pindai menonjol; garis naik-turun di bawah titik sudut
        // (titik & garis potong tetap tajam di atasnya)
        if (sweep.level > 0.01f) {
            scanDim.alpha = (sweep.level * 70).toInt()
            c.drawRect(img, scanDim)
            sweep.draw(c, img.left, img.top, img.right, img.bottom)
        }

        // Kisi perspektif 3x3 di dalam area
        fun lerp(a: Float, b2: Float, t: Float) = a + (b2 - a) * t
        for (k in 1..2) {
            val t = k / 3f
            c.drawLine(lerp(px(0), px(1), t), lerp(py(0), py(1), t), lerp(px(3), px(2), t), lerp(py(3), py(2), t), grid)
            c.drawLine(lerp(px(0), px(3), t), lerp(py(0), py(3), t), lerp(px(1), px(2), t), lerp(py(1), py(2), t), grid)
        }
        c.drawPath(quadPath(), if (rejected) bad else line)

        // Pegangan tengah sisi (kapsul kecil) & sudut (lingkaran besar)
        val r = context.dp(5).toFloat()
        for (k in 0 until 4) {
            val j = (k + 1) % 4
            val mx = (px(k) + px(j)) / 2f; val my = (py(k) + py(j)) / 2f
            val ang = Math.toDegrees(kotlin.math.atan2((py(j) - py(k)).toDouble(), (px(j) - px(k)).toDouble())).toFloat()
            c.save(); c.rotate(ang, mx, my)
            val rr = tmpRF.apply { set(mx - r * 3, my - r, mx + r * 3, my + r) }
            c.drawRoundRect(rr, r, r, if (drag == 11 + k) hActive else hFill)
            c.drawRoundRect(rr, r, r, hRing)
            c.restore()
        }
        val rad = context.dp(10).toFloat()
        for (k in 0 until 4) {
            c.drawCircle(px(k), py(k), rad, if (drag == 1 + k) hActive else hFill)
            c.drawCircle(px(k), py(k), rad, hRing)
        }

        // Lukis sensur hitam (blackout/redaksi)
        if (activeRedactions.isNotEmpty()) {
            val rFill = redFill
            val rStroke = redStroke
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
            c.drawRect(curR, redLive)
            c.drawRect(curR, redStroke)
        }

        // Lukis pratinjau watermark
        if (!activeWatermark.isNullOrBlank()) {
            c.save()
            c.rotate(-32f, img.centerX(), img.centerY())
            wmPaint.textSize = minOf(img.width(), img.height()) * 0.08f
            c.drawText(activeWatermark!!, img.centerX(), img.centerY(), wmPaint)
            c.restore()
        }

        // Kaca pembesar saat menggeser sudut / sisi (agar sudut kertas bisa dipaskan presisi)
        if (drag in 1..4 || drag in 11..14) {
            val (rx, ry) = if (drag in 1..4) quad[2 * (drag - 1)] to quad[2 * (drag - 1) + 1] else {
                val k = drag - 11; val j = (k + 1) % 4
                (quad[2 * k] + quad[2 * j]) / 2f to (quad[2 * k + 1] + quad[2 * j + 1]) / 2f
            }
            val bx = (rx * b.width).toInt().coerceIn(0, b.width - 1)
            val by = (ry * b.height).toInt().coerceIn(0, b.height - 1)
            val size = context.dp(48).toFloat()
            // Kaca pembesar menjauhi jari: pindah ke sisi kiri bila jari di kanan atas
            val fingerRight = img.left + rx * img.width() > width / 2f
            val fingerTop = img.top + ry * img.height() < height / 2f
            val lCx = if (fingerRight && fingerTop) size + context.dp(16) else width - size - context.dp(16)
            val lCy = size + context.dp(16)

            c.save()
            val path = loupePath.apply { reset(); addCircle(lCx, lCy, size, android.graphics.Path.Direction.CW) }
            c.clipPath(path)
            c.drawColor(Color.WHITE)
            val half = (size / 2.5f) * (b.width / img.width()).coerceAtLeast(0.6f)
            srcR.set(
                (bx - half).toInt().coerceAtLeast(0), (by - half).toInt().coerceAtLeast(0),
                (bx + half).toInt().coerceAtMost(b.width), (by + half).toInt().coerceAtMost(b.height)
            )
            dstR.set(
                lCx - size * (bx - srcR.left) / half, lCy - size * (by - srcR.top) / half,
                lCx + size * (srcR.right - bx) / half, lCy + size * (srcR.bottom - by) / half
            )
            c.drawBitmap(b, srcR, dstR, imgPaint)
            c.drawLine(lCx - 16, lCy, lCx + 16, lCy, hRing)
            c.drawLine(lCx, lCy - 16, lCx, lCy + 16, hRing)
            c.restore()
            c.drawCircle(lCx, lCy, size, hRing)
        }
    }

    val activeRedactions = mutableListOf<RectF>()
    var activeWatermark: String? = null
    var isRedactMode = false
    private var startRx = 0f
    private var startRy = 0f
    private var currentRedactRect: RectF? = null

    override fun onDetachedFromWindow() {
        sweep.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (busy) return true
        if (e.actionMasked == MotionEvent.ACTION_DOWN) qAnim?.cancel()   // jari menang atas animasi
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
                if (img.width() <= 0f) return false
                val t = context.dp(30).toFloat()
                var best = -1; var bestD = t * t
                for (k in 0 until 4) {
                    val dx = e.x - px(k); val dy = e.y - py(k); val d = dx * dx + dy * dy
                    if (d < bestD) { bestD = d; best = 1 + k }
                }
                if (best < 0) {
                    val te = context.dp(26).toFloat(); var bd = te * te
                    for (k in 0 until 4) {
                        val j = (k + 1) % 4
                        val dx = e.x - (px(k) + px(j)) / 2f; val dy = e.y - (py(k) + py(j)) / 2f; val d = dx * dx + dy * dy
                        if (d < bd) { bd = d; best = 11 + k }
                    }
                }
                if (best < 0) {
                    val r = android.graphics.Region()
                    val bounds = android.graphics.Region(0, 0, width, height)
                    r.setPath(quadPath(), bounds)
                    if (r.contains(e.x.toInt(), e.y.toInt())) best = 20
                }
                drag = if (best < 0) 0 else best
                lx = e.x; ly = e.y
                if (drag != 0) { parent?.requestDisallowInterceptTouchEvent(true); invalidate() }
                return drag != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == 0 || img.width() <= 0f) return false
                val dx = (e.x - lx) / img.width()
                val dy = (e.y - ly) / img.height()
                lx = e.x; ly = e.y
                val n = quad.copyOf()
                when (drag) {
                    in 1..4 -> { val k = drag - 1; n[2 * k] += dx; n[2 * k + 1] += dy }
                    in 11..14 -> {
                        val k = drag - 11; val j = (k + 1) % 4
                        n[2 * k] += dx; n[2 * k + 1] += dy; n[2 * j] += dx; n[2 * j + 1] += dy
                    }
                    20 -> {
                        val bb = Quad.bbox(quad)
                        val mx = dx.coerceIn(-bb[0], 1f - bb[2]); val my = dy.coerceIn(-bb[1], 1f - bb[3])
                        for (k in 0 until 4) { n[2 * k] += mx; n[2 * k + 1] += my }
                    }
                }
                val cl = Quad.clamp(n)
                // Tolak bentuk tidak sah (sudut saling silang / cekung): tetap di posisi terakhir yang sah.
                rejected = !Quad.isValid(cl)
                if (!rejected) { quad = cl; onChange?.invoke() }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = drag
                drag = 0; rejected = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (was != 0) onRelease?.invoke()
            }
        }
        return true
    }
}

/** "Edit Hasil Scan": potong, putar, balikkan, filter, kecerahan/kontras, lalu simpan jadi PDF atau JPG. */
class ScanEditActivity : AppCompatActivity() {
    private val pages = mutableListOf<ScanPage>()
    private var cur = 0
    private var mode = 0      // 0 asli (bawaan), 1 abu-abu, 2 dokumen hitam-putih, 3 magic color
    private var bright = 0
    private var contrast = 0

    private lateinit var cv: CropView
    private lateinit var scanTag: TextView
    private var detecting = false
    private var blocker: View? = null
    private lateinit var sub: TextView
    private lateinit var chips: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var brightLabel: TextView
    private lateinit var contrastLabel: TextView
    private lateinit var cropPanel: LinearLayout
    private lateinit var cropHint: TextView
    private lateinit var filterPanel: LinearLayout
    private val filterCards = mutableListOf<LinearLayout>()
    private val checks = mutableListOf<View>()
    private val previews = LruCache<String, Bitmap>(Perf.previewSlots())
    private val trimPreviews: () -> Unit = { runOnUiThread { previews.evictAll() } }
    private var onPic: ((Boolean) -> Unit)? = null

    private val takePic = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> onPic?.invoke(ok) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Perf.onTrim(trimPreviews)
        intent.getStringArrayListExtra("files")?.forEach { pages.add(ScanPage(File(it))) }
        if (pages.isEmpty()) { finish(); return }
        mode = intent.getIntExtra("mode", 0)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        // ----- bar atas -----
        // tanpa latar sendiri: kapsul kaca cair melayang (Glass.scaffold floatTop) seperti navigasi Beranda — bukan blur
        // progresif, yang di atas foto & tombol biru hanya tampak sebagai noda buram. Jarak dalam 6 dp: tombol tutup &
        // "Lanjut" sepusat dengan ujung kapsul.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        bar.add(icon(R.drawable.ic_close, C.TEXT, 24).asButton("Tutup editor scan").apply {
            setOnClickListener { askDiscard() }
        }, w = dp(48), h = dp(48))
        val tc = vbox()
        tc.add(tv("Edit Hasil Scan", 18f, C.TEXT, true))
        sub = tv("Hal 1 dari ${pages.size}", 12f, C.MUTED)
        tc.add(sub)
        bar.add(tc, w = 0, weight = 1f, side = 8)

        bar.add(icon(R.drawable.ic_more, C.TEXT, 22).asButton("Opsi lain").apply {
            setOnClickListener { v ->
                val pm = android.widget.PopupMenu(this@ScanEditActivity, v)
                pm.menu.add(0, 1, 0, "Bacakan Teks OCR (Voice Reader)")
                pm.menu.add(0, 2, 1, "Cetak Hasil Scan")
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
                            // semua halaman jadi satu PDF (sama seperti simpan PDF), lalu lembar Cetak DepUp:
                            // halaman tertentu, salinan, kertas, warna & bolak-balik dipilih di sana
                            val all = pages.toList()
                            val out = PrintKit.newFile(this@ScanEditActivity, "scan")
                            work("Menyiapkan hasil scan…", {
                                Export.pdf(this@ScanEditActivity, all, out, mode, bright, contrast, 0, Export.ASPECTS[0], false, null)
                            }) { r ->
                                if (r.isSuccess) printSheet(out, "Hasil Scan")
                                else { out.delete(); toast("Gagal memproses gambar untuk dicetak") }
                            }
                        }
                    }
                    true
                }
                pm.show()
            }
        }, w = dp(40), h = dp(40))

        bar.add(btn("Lanjut", true, iconRes = R.drawable.ic_next) { save() }, w = WRAP)
        root.add(bar)

        // ----- isi (bisa digulir) -----
        val body = vbox(16).apply { setPadding(dp(16), dp(14), dp(16), dp(20)) }

        // segmen Potong / Filter: kapsul meluncur seperti di seluruh aplikasi
        body.add(segmented(listOf("Potong", "Filter"), 0) { showTab(it) })

        // kanvas potong; alat cepatnya di bawah kanvas (dulu menumpuk di pojok foto, menutupi titik sudut kiri atas)
        cv = CropView(this)
        val stage = FrameLayout(this)
        stage.addView(cv, FrameLayout.LayoutParams(MATCH, MATCH))
        // keterangan saat AI memindai foto (garis pindai bergerak di kanvas)
        scanTag = tv("", 13f, Color.WHITE, true).apply {
            background = shape(0xB3141414.toInt(), 100, 0x33FFFFFF, 1)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        stage.addView(scanTag, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        body.add(stage, top = 12, h = dp(380))
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(
            Triple(R.drawable.ic_full, "Penuh") { resetCrop() },
            Triple(R.drawable.ic_rotate, "Reset") { resetAll() },
            Triple(R.drawable.ic_check_ok, "Lihat hasil") { previewResult() }
        ).forEachIndexed { i, (res, label, f) ->
            val chip = filterChip(label, false, res) { f() }
            quick.add(chip, w = WRAP, side = 0)
            if (i > 0) (chip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        }
        body.add(quick, top = 10)

        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(chips)
        body.add(hs, top = 12)
        if (pages.size > 1) body.add(tv("Tahan nomor halaman lalu geser untuk mengubah urutan", 12f, C.MUTED), top = 4)

        // ---- panel Potong ----
        // Alat dikelompokkan dalam kartu bulat berlabel (seperti daftar di seluruh aplikasi); ikon di wadah warna lembut.
        cropPanel = vbox()
        fun tool(res: Int, label: String, fg: Int, bg: Int, f: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
            background = rippleBg(16)
            isClickable = true; isFocusable = true
            contentDescription = label
            val badge = iconBadge(res, fg, bg, 14, 22).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            setOnClickListener { badge.boing(0.8f); f() }
            springPress(0.92f)
            add(badge, w = dp(46), h = dp(46))
            add(tv(label, 12f, if (fg == C.RED) C.RED else C.TEXT, true).apply {
                gravity = Gravity.CENTER; maxLines = 2
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, top = 6, w = MATCH)
        }
        fun group(title: String, items: List<View>, top: Int) {
            cropPanel.add(tv(title, 13f, C.MUTED, true).apply { setPadding(dp(6), 0, 0, 0) }, top = top)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = squircle(C.SURFACE, 22)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            items.forEach { row.add(it, w = 0, weight = 1f) }
            cropPanel.add(row, top = 6)
        }
        group("Halaman", listOf(
            tool(R.drawable.ic_rotate, "Putar 90°", C.TEXT, C.SOFT) { rotate() },
            tool(R.drawable.ic_flip, "Balikkan", C.TEXT, C.SOFT) { flipPage() },
            tool(R.drawable.ic_full, "Potong penuh", C.TEXT, C.SOFT) { resetCrop() },
            tool(R.drawable.ic_trash, "Hapus hal.", C.RED, C.ERROR_C) { removePage() }
        ), 0)
        group("Otomatis", listOf(
            tool(R.drawable.ic_wand, "Auto-crop AI", C.ACCENT, C.BLUE_BG) { autoCrop() },
            tool(R.drawable.ic_tune, "Luruskan", C.ACCENT, C.BLUE_BG) { straighten() },
            tool(R.drawable.ic_note, "Teks (OCR)", C.ACCENT, C.BLUE_BG) { runOcr() }
        ), 16)
        group("Privasi & tanda", listOf(
            tool(R.drawable.ic_lock, "Sensur NIK/data", C.AMBER, C.AMBER_BG) { toggleRedactMode() },
            tool(R.drawable.ic_spark, "Cap watermark", C.AMBER, C.AMBER_BG) { showWatermarkDialog() },
            tool(R.drawable.ic_trash, "Hapus sensur", C.RED, C.ERROR_C) { clearRedactions() }
        ), 16)

        if (intent.getBooleanExtra("isKtp", false) || pages.size >= 2) {
            val ktpBanner = card(14, 22).apply {
                background = squircle(C.SURFACE, 22)
                isClickable = true
                setOnClickListener { layoutKtpA4() }
            }
            val kRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            kRow.add(iconBadge(R.drawable.ic_user, C.ACCENT, C.BLUE_BG, 100, 20), w = dp(38), h = dp(38))
            val kCol = vbox()
            kCol.add(tv("Tata KTP 2-Sisi ke 1 Halaman A4", 14f, C.TEXT, true))
            kCol.add(tv("Gabung KTP Sisi Depan & Belakang dalam 1 lembar A4 siap cetak/upload", 11f, C.MUTED), top = 2)
            kRow.add(kCol, w = 0, weight = 1f, side = 8)
            kRow.add(tv("Tata KTP A4", 12f, C.ON_PRIMARY, true).apply {
                background = shape(C.ACCENT, 8)
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }, w = WRAP)
            ktpBanner.add(kRow)
            cropPanel.add(ktpBanner, top = 16)
        }

        cropHint = tv("", 12f, C.MUTED)
        cropPanel.add(cropHint, top = 10)
        body.add(cropPanel, top = 12)

        // ---- panel Filter ----
        filterPanel = vbox()
        val fh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fh.add(tv("Gaya Filter Dokumen", 17f, C.TEXT, true), w = 0, weight = 1f)
        fh.add(tv("4 PRESET", 11f, C.ACCENT, true), w = WRAP)
        filterPanel.add(fh)
        val fr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf(
            Triple(0, "Warna Asli", "Bawaan · apa adanya"),
            Triple(3, "Magic Color", "Teks lebih jelas"),
            Triple(2, "B&W Dokumen", "Teks Tajam"),
            Triple(1, "Grayscale", "Foto Netral")
        )
        defs.forEachIndexed { i, (m, name, desc) ->
            val c = vbox(10).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener { if (mode != m) { mode = m; paintFilters(true); applyFilter(true) } }
                springPress(0.95f)
                tag = m
            }
            val bg = when (m) { 2 -> Color.BLACK; 1 -> C.LINE_STRONG; 3 -> C.BLUE_BG; else -> C.SURFACE2 }
            val bc = when (m) { 2 -> Color.WHITE; 1 -> 0xFF5A5853.toInt(); else -> C.ACCENT }
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
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        foot.add(iconBadge(R.drawable.ic_camera, C.ACCENT, C.BLUE_BG, 12, 22).apply { contentDescription = "Foto ulang halaman ini"; isFocusable = true; setOnClickListener { retake() } }, w = dp(48), h = dp(48))
        foot.add(btn("Tambah", false, C.ACCENT, R.drawable.ic_add_photo) { addPage() }.apply {
            contentDescription = "Tambah halaman"
        }, w = WRAP, side = 8)
        foot.add(btn("Simpan", true, C.ACCENT, R.drawable.ic_pdf) { save() }.apply {
            contentDescription = "Selesai dan simpan"
        }, w = 0, weight = 1f, side = 0)
            .also { (foot.getChildAt(foot.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
        root.add(foot)

        setContentView(Glass.scaffold(root, floatTop = true))
        onBackPressedDispatcher.addCallback(this, PreviewBack(this) { askDiscard() })
        paintFilters()
        show()
        autoDetect(pages.toList(), crop = true, skew = true, announce = true)
    }

    private var tabNow = 0

    private fun showTab(i: Int) {
        val changed = i != tabNow
        tabNow = i
        if (changed) {
            // panel baru meluncur masuk dari arah tab yang dipilih; tab aktif memantul kecil
            (if (i == 0) cropPanel else filterPanel).cascadeIn(dyDp = 10, dxDp = if (i == 0) -14 else 14)
        }
        cropPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
        filterPanel.visibility = if (i == 1) View.VISIBLE else View.GONE
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

    private fun paintFilters(animate: Boolean = false) {
        filterCards.forEachIndexed { i, c ->
            val on = c.tag == mode
            val was = checks[i].visibility == View.VISIBLE
            c.background = choiceBg(on, 14)
            checks[i].visibility = if (on) View.VISIBLE else View.INVISIBLE
            if (animate && on && !was) checks[i].boing(0.4f)
        }
    }

    private fun applyFilter(animate: Boolean = false) = cv.setFilter(mode, bright, contrast, animate)

    override fun onDestroy() {
        Perf.removeTrim(trimPreviews)
        super.onDestroy()
    }

    private fun preview(pg: ScanPage): Bitmap? {
        val key = pg.file.absolutePath
        previews.get(key)?.let { return it }
        return try {
            Pdf.decode(this, Uri.fromFile(pg.file), 1400).also { previews.put(key, it) }
        } catch (t: Throwable) { fail(t); null }
    }

    private var lastShown = -1

    private fun show() {
        cur = cur.coerceIn(0, pages.size - 1)
        val pg = pages[cur]
        val b = preview(pg) ?: return
        val prevShown = lastShown
        val pageChanged = prevShown != cur
        lastShown = cur
        // halaman berganti: gambar bergeser masuk sedikit dari arah halaman berikutnya/sebelumnya
        if (pageChanged && prevShown >= 0) cv.glide(0, 0, if (cur > prevShown) Motion.SHIFT_DP + 2 else -(Motion.SHIFT_DP + 2))
        cv.onChange = { pg.setQuad(cv.quad) }
        cv.onRelease = { updateCropHint() }
        cv.setBitmap(b, pg.rot, pg.flip, pg.quad, pg.skew)
        updateCropHint()
        applyFilter()
        sub.text = "Hal ${cur + 1} dari ${pages.size}"
        chips.removeAllViews()
        dots.removeAllViews()
        pages.forEachIndexed { i, _ ->
            val on = i == cur
            chips.add(tv("${i + 1}", 13f, if (on) C.ON_PRIMARY else C.MUTED, true).apply {
                gravity = Gravity.CENTER
                background = shape(if (on) C.ACCENT else C.SURFACE2, 100)
                setOnClickListener { cur = i; show() }
                contentDescription = "Halaman ${i + 1}${if (on) ", sedang dibuka" else ""}. Tahan lalu geser untuk memindah urutan"
                // Tahan lalu geser ke nomor lain untuk menyusun ulang urutan halaman.
                if (pages.size > 1) setOnLongClickListener { v ->
                    v.startDragAndDrop(android.content.ClipData.newPlainText("page", "$i"), View.DragShadowBuilder(v), i, 0)
                    true
                }
                setOnDragListener { v, e ->
                    when (e.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> e.localState is Int
                        android.view.DragEvent.ACTION_DRAG_ENTERED -> { v.animate().scaleX(1.2f).scaleY(1.2f).setStartDelay(0).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).start(); true }
                        android.view.DragEvent.ACTION_DRAG_EXITED, android.view.DragEvent.ACTION_DRAG_ENDED -> { v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).start(); true }
                        android.view.DragEvent.ACTION_DROP -> { movePage(e.localState as Int, i); true }
                        else -> true
                    }
                }
            }, w = dp(36), h = dp(36), side = 0).also {
                val chip = chips.getChildAt(chips.childCount - 1)
                (chip.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
                chip.springPress(0.88f)
                if (on && pageChanged && prevShown >= 0) chip.boing(0.8f)   // nomor halaman aktif memantul
            }
            val dot = View(this).apply { background = shape(if (on) C.ACCENT else C.LINE_STRONG, 100) }
            dots.add(dot, w = dp(if (on && !(pageChanged && Motion.on())) 18 else 8), h = dp(8), side = 0).also {
                (dots.getChildAt(dots.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6)
            }
            // titik halaman aktif memanjang dari bulat ke pil (seperti indikator halaman iOS)
            if (on && pageChanged && Motion.on()) android.animation.ValueAnimator.ofInt(dp(8), dp(18)).apply {
                duration = Motion.SPRING_MS; interpolator = Motion.SPRING
                addUpdateListener { a -> dot.layoutParams = dot.layoutParams.also { it.width = a.animatedValue as Int } }
                start()
            }
        }
    }

    /** Pindahkan halaman [from] ke posisi [to]; halaman yang sedang dibuka tetap diikuti. */
    private fun movePage(from: Int, to: Int) {
        if (from == to || from !in pages.indices || to !in pages.indices) return
        val curPage = pages[cur]
        val pg = pages.removeAt(from)
        pages.add(to, pg)
        cur = pages.indexOf(curPage).coerceAtLeast(0)
        toast("Halaman ${from + 1} dipindah ke urutan ${to + 1}")
        show()
    }

    private fun rotate() {
        val pg = pages[cur]
        pg.setQuad(Quad.rot90(pg.quad))
        // Bila sudah dibalik, putar searah jarum jam di layar = kurangi putaran dasar.
        pg.rot = (pg.rot + (if (pg.flip) 270 else 90)) % 360
        val old = cv.imgRect()
        show()
        cv.spinIn(-90f, old)
    }

    private fun flipPage() {
        val pg = pages[cur]
        pg.setQuad(Quad.flipH(pg.quad))
        pg.skew = -pg.skew
        pg.flip = !pg.flip
        show()
        cv.flipIn()
    }

    private fun resetCrop() {
        val old = cv.quad.copyOf()
        pages[cur].setQuad(Quad.full()); show()
        cv.morphQuadFrom(old)
    }

    private fun resetAll() {
        val pg = pages[cur]
        pg.origFile?.let { orig ->
            previews.remove(pg.file.absolutePath)
            pg.file.delete()
            pg.file = orig
            pg.origFile = null
        }
        val geom = pg.rot != 0 || pg.flip || pg.skew != 0f
        val old = cv.quad.copyOf()
        pg.rot = 0; pg.flip = false; pg.skew = 0f; pg.setQuad(Quad.full())
        show()
        if (geom) cv.pop(0.94f) else cv.morphQuadFrom(old)
    }

    private fun removePage() {
        if (pages.size == 1) { toast("Minimal harus ada satu halaman"); return }
        confirm("Hapus halaman ${cur + 1}?", "Halaman ini dibuang dari hasil scan.", "Hapus") {
            pages.removeAt(cur).let { it.file.delete(); it.origFile?.delete() }
            lastShown = -1          // halaman berikutnya muncul memantul, bukan bergeser
            show()
            cv.pop(0.9f)
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
                toast("KTP Sisi Depan & Belakang berhasil ditata dalam 1 Halaman A4!")
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
        canvas.drawText("SISI DEPAN (FRONT)", a4W / 2f, fTop + cardH + 50f, labelPaint)

        // --- Belakang ---
        val bLeft = (a4W - cardW) / 2f
        val bTop = fTop + cardH + 180f
        val bRect = RectF(bLeft, bTop, bLeft + cardW, bTop + cardH)
        canvas.drawBitmap(bBmp, null, bRect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawRoundRect(bRect, 20f, 20f, borderPaint)
        canvas.drawText("SISI BELAKANG (BACK)", a4W / 2f, bTop + cardH + 50f, labelPaint)

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
                pg.origFile?.delete(); pg.origFile = null
                previews.remove(pg.file.absolutePath)
                pg.file = f; pg.rot = 0; pg.flip = false; pg.skew = 0f; pg.setQuad(Quad.full())
                show()
            } else f.delete()
        }
        takePic.launch(Files.uriFor(this, f))
    }

    private fun askDiscard() {
        confirm("Buang hasil scan?", "Semua halaman yang belum disimpan akan dihapus.", "Buang") { cleanup(); finish() }
    }

    private fun cleanup() { pages.forEach { it.file.delete(); it.origFile?.delete() } }

    // ================= AUTO-CROP AI / LURUSKAN / OCR =================
    /** Hasil deteksi: 4 sudut dokumen (null = tidak ditemukan) dan kemiringan halus. */
    private class Auto(val quad: FloatArray?, val skew: Float, val split: Boolean = false)

    /**
     * Thread latar. AI mencari 4 sudut dokumen pada foto yang sudah diputar/dibalik — boleh miring atau
     * berperspektif. Hasilnya ditaruh sebagai titik sudut yang MASIH BISA digeser pengguna, lalu saat disimpan
     * otomatis ditegakkan jadi persegi panjang 90°. Bila tidak ada dokumen terdeteksi, kemiringan teks saja
     * yang diluruskan (bila diminta).
     */
    private fun detectFor(pg: ScanPage, doCrop: Boolean, doSkew: Boolean): Auto {
        val base = Pdf.decode(this, Uri.fromFile(pg.file), 1000)
        val o = Pdf.orient(base, pg.rot, pg.flip)
        try {
            if (doCrop) {
                val q = ScanAi.findDocument(o)
                val split = q != null && ScanAi.lastSplit
                val quad = q?.let { Quad.order(Quad.clamp(it)) }?.takeIf { Quad.isValid(it, 0.02f) }
                if (quad != null) return Auto(quad, 0f, split)
            }
            if (!doSkew) return Auto(null, pg.skew)
            val d = Pdf.deskew(o, pg.skew)
            val area = Quad.bbox(pg.quad).let { RectF(it[0], it[1], it[2], it[3]) }
            val delta = try { ScanAi.detectSkew(d, area.takeIf { !Quad.isFull(pg.quad) }) } finally { if (d !== o) d.recycle() }
            return Auto(null, pg.skew + delta)
        } finally {
            if (o !== base) o.recycle()
            base.recycle()
        }
    }

    /**
     * Mulai / akhiri tampilan "AI sedang memindai": garis pindai naik-turun di atas foto + kapsul keterangan.
     * Sentuhan di seluruh layar ditahan sementara (seperti dialog proses dulu) tanpa menutupi tampilan.
     */
    private fun scanUi(on: Boolean, msg: String = "") {
        detecting = on
        cv.busy = on
        if (on) {
            cv.sweep.start()
            scanTag.text = msg
            scanTag.visibility = View.VISIBLE
            if (Motion.on()) {
                scanTag.alpha = 0f; scanTag.translationY = -dp(6).toFloat()
                scanTag.animate().alpha(1f).translationY(0f).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).start()
            } else scanTag.alpha = 1f
            if (blocker == null) {
                val content = findViewById<ViewGroup>(android.R.id.content)
                blocker = View(this).apply {
                    isClickable = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }.also { content?.addView(it, ViewGroup.LayoutParams(MATCH, MATCH)) }
            }
        } else {
            cv.sweep.stop()
            if (Motion.on()) scanTag.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(Motion.FAST)
                .setInterpolator(Motion.STANDARD).withEndAction { if (!detecting) scanTag.visibility = View.GONE }.start()
            else scanTag.visibility = View.GONE
            blocker?.let { (it.parent as? ViewGroup)?.removeView(it) }
            blocker = null
        }
    }

    private fun autoDetect(list: List<ScanPage>, crop: Boolean, skew: Boolean, announce: Boolean) {
        if (detecting || list.isEmpty()) return
        val label = if (crop) "Mendeteksi tepi kertas…" else "Meluruskan halaman…"
        scanUi(true, label)
        val started = android.os.SystemClock.uptimeMillis()
        Thread {
            val r = runCatching {
                list.mapIndexed { i, pg ->
                    if (list.size > 1) runOnUiThread { if (detecting) scanTag.text = "$label ${i + 1}/${list.size}" }
                    detectFor(pg, crop, skew)
                }
            }
            // garis pindai tampil minimal satu lintasan penuh (deteksi sering < 0,5 detik)
            val rest = 1100L - (android.os.SystemClock.uptimeMillis() - started)
            if (rest > 0 && Motion.on()) try { Thread.sleep(rest) } catch (_: InterruptedException) {}
            runOnUiThread {
                scanUi(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                afterDetect(list, crop, skew, announce, r)
            }
        }.start()
    }

    private fun afterDetect(list: List<ScanPage>, crop: Boolean, skew: Boolean, announce: Boolean, r: Result<List<Auto>>) {
        r.onSuccess { res ->
            val curPg = pages.getOrNull(cur)
            val oldQ = if (curPg != null && curPg in list) cv.quad.copyOf() else null
            var found = 0; var fixed = 0; var full = 0; var split = false
            list.forEachIndexed { i, pg ->
                val a = res[i]
                val q = a.quad
                if (q != null) {
                    pg.skew = 0f
                    pg.setQuad(q)
                    if (Quad.isFull(q, 0.03f)) full++ else found++
                    if (a.split) split = true
                } else if (skew && kotlin.math.abs(a.skew - pg.skew) > 0.05f) {
                    // Titik sudut ikut berputar bersama gambar supaya tetap menempel di dokumen.
                    val b = preview(pg)
                    val (w, h) = if (b != null) {
                        if ((pg.rot / 90) % 2 == 1) b.height to b.width else b.width to b.height
                    } else 1000 to 1400
                    pg.setQuad(Quad.rotate(pg.quad, a.skew - pg.skew, w, h))
                    pg.skew = a.skew
                    fixed++
                }
            }
            if (pages.isNotEmpty()) show()
            // titik sudut meluncur dari tempat lama ke tepi dokumen yang terdeteksi
            if (oldQ != null && pages.getOrNull(cur) === curPg) cv.morphQuadFrom(oldQ)
            when {
                split -> toast("Buku terbuka terdeteksi — sudut dipasang pada halaman yang dituju (yang berisi / utuh). Geser titik bila ingin halaman lain.")
                crop && found > 0 && announce -> toast("Sudut dokumen terdeteksi di $found halaman. Geser titik sudut bila perlu.")
                crop && found > 0 -> toast("Sudut dokumen terdeteksi. Geser titik sudut bila perlu — hasil otomatis jadi kotak 90°.")
                crop && full > 0 && !announce -> toast("Dokumen sudah memenuhi bingkai.")
                crop && !announce -> toast("Sudut dokumen tidak terdeteksi. Geser 4 titik sudut ke ujung dokumen secara manual.")
                !crop && skew -> toast(if (fixed > 0) "Halaman diluruskan" else "Halaman sudah lurus")
            }
        }.onFailure { fail(it) }
    }

    /** Auto-crop AI ala CamScanner: pasang 4 titik sudut pada tepi dokumen (bisa disesuaikan pengguna). */
    private fun autoCrop() = autoDetect(listOf(pages[cur]), crop = true, skew = false, announce = false)

    /** Keterangan di bawah kanvas: menjelaskan apa yang akan terjadi pada hasil. */
    private fun updateCropHint() {
        if (!::cropHint.isInitialized) return
        val q = pages.getOrNull(cur)?.quad ?: return
        cropHint.text = when {
            Quad.isFull(q) -> "Geser 4 titik sudut ke ujung dokumen — boleh miring atau tidak sejajar. Hasilnya otomatis diluruskan jadi kotak 90°."
            Quad.isRect(q) -> "Area potong berbentuk kotak. Geser titik sudut secara bebas bila dokumen miring/berperspektif."
            else -> "Area miring akan otomatis diluruskan jadi persegi panjang 90° saat disimpan. Ketuk “Lihat Hasil” untuk pratinjau."
        }
    }

    /** Pratinjau cepat hasil luruskan dari gambar tampilan saat ini. */
    private fun previewResult() {
        val src = cv.bitmap ?: return
        val pg = pages[cur]
        val q = pg.quad
        val res = try {
            if (Quad.isRect(q)) {
                val b = Quad.bbox(q)
                val x = (b[0] * src.width).toInt().coerceIn(0, src.width - 2); val y = (b[1] * src.height).toInt().coerceIn(0, src.height - 2)
                Bitmap.createBitmap(src, x, y, ((b[2] - b[0]) * src.width).toInt().coerceIn(2, src.width - x), ((b[3] - b[1]) * src.height).toInt().coerceIn(2, src.height - y))
            } else Pdf.warp(src, q, 1400)
        } catch (t: Throwable) { fail(t); return }
        sheet { body, dismiss ->
            sheetHeader(body, R.drawable.ic_check_ok, C.ACCENT, C.BLUE_BG, "Pratinjau Hasil", "${res.width} × ${res.height} px · Cubit/ketuk 2x untuk zoom", dismiss)
            val iv = ZoomImageView(this).apply {
                setImageBitmap(res)
                background = shape(C.NAVY_PANEL, 12)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            val pad = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
            pad.add(iv, h = dp(380))
            pad.add(btn("Oke, lanjut edit", true, C.ACCENT) { dismiss() }, top = 12)
            body.add(pad)
        }
    }

    /**
     * Nama berkas cerdas: OCR halaman pertama di latar (offline), kenali jenis dokumen (KTP, struk, surat, ...) atau
     * pakai judul barisnya. Nama hanya diganti bila pengguna belum mengetik apa pun di kolomnya.
     */
    private fun suggestName(et: android.widget.EditText, default: String, stamp: String) {
        val pg = pages.firstOrNull() ?: return
        val m = mode; val b = bright; val c = contrast
        Thread {
            val name = try {
                val bmp = Export.process(this, pg, m, Export.OCR_MIN_DIM, b, c)
                val text = try { Ocr.run(bmp).text } finally { bmp.recycle() }
                SmartDoc.suggestName(text, stamp)
            } catch (_: Throwable) { null }
            if (name != null) runOnUiThread {
                if (!isFinishing && !isDestroyed && et.text.toString() == default) et.setText(name)
            }
        }.start()
    }

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
                pad.add(tv("OCR kurang yakin (${(res.conf * 100).toInt()}%). Biasanya karena tulisan tangan atau foto buram — OCR offline hanya andal untuk teks cetak. Untuk hasil terbaik: foto lebih dekat & terang (ketuk layar untuk fokus), atau simpan sebagai PDF/Foto.", 12f, C.RED))
            }
            val t = tv(res.text, 14f, C.TEXT).apply {
                setTextIsSelectable(true)
                background = shape(C.SURFACE2, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            pad.add(t)

            // Penerjemah Teks Dokumen
            var isTranslated = false
            val transBtn = btn("Terjemahkan Dokumen (Inggris ↔ Indonesia)", false, C.INDIGO, R.drawable.ic_spark) {
                isTranslated = !isTranslated
                t.text = Pdf.translateDocText(res.text, isTranslated)
                toast(if (isTranslated) "Teks diterjemahkan ke Bahasa Indonesia" else "Teks kembali ke Asli")
            }
            pad.add(transBtn, top = 10)

            // Ringkasan Poin-Poin Utama Dokumen
            val summary = Pdf.extractDocSummary(res.text)
            if (summary.dates.isNotEmpty() || summary.numbers.isNotEmpty() || summary.amounts.isNotEmpty()) {
                val sCard = card(12, 12).apply { background = shape(C.AMBER_BG, 12, C.AMBER, 1) }
                val sCol = vbox()
                sCol.add(tv("RINGKASAN POIN KRUSIAL DOKUMEN", 12f, C.AMBER, true))
                if (summary.numbers.isNotEmpty()) sCol.add(tv("Nomor/Pasal: ${summary.numbers.joinToString(", ")}", 12f, C.TEXT, true), top = 2)
                if (summary.dates.isNotEmpty()) sCol.add(tv("Tanggal: ${summary.dates.joinToString(", ")}", 12f, C.TEXT, true), top = 2)
                if (summary.amounts.isNotEmpty()) sCol.add(tv("Nominal: ${summary.amounts.joinToString(", ")}", 12f, C.TEXT, true), top = 2)
                sCard.add(sCol)
                pad.add(sCard, top = 10)
            }

            // Deteksi Struk / Nota Keuangan
            val receipt = Pdf.parseReceipt(res.text)
            if (receipt.total != "Rp 0") {
                val rCard = card(12, 12).apply { background = shape(C.BLUE_BG, 12, C.INDIGO, 1) }
                val rCol = vbox()
                rCol.add(tv("DETEKSI STRUK / NOTA KEUANGAN", 12f, C.INDIGO, true))
                rCol.add(tv("Merchant: ${receipt.merchant} • Tanggal: ${receipt.date}", 12f, C.TEXT, true), top = 2)
                rCol.add(tv("Total Bayar: ${receipt.total}", 15f, C.INDIGO, true), top = 2)
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
            row1.add(btn("Cuma Foto (JPG)", false, C.SKY, R.drawable.ic_image) {
                dismiss()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                export("Scan_Foto_$stamp", Export.F_JPG, 1, Export.ASPECTS[0], false, null)
            }, w = 0, weight = 1f)

            row1.add(btn("Dokumen PDF", false, C.RED, R.drawable.ic_pdf) {
                dismiss()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                export("Scan_PDF_$stamp", Export.F_PDF, 1, Export.ASPECTS[0], true, null)
            }, w = 0, weight = 1f, side = 6)
            expGrid.add(row1)

            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row2.add(btn("Teks / Word (.docx)", false, C.ACCENT, R.drawable.ic_doc) {
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
            background = raised(C.SURFACE, 12, 2f)
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }
        val col = vbox()
        col.add(tv(title, 14f, C.TEXT, true))
        col.add(tv(desc, 12f, C.MUTED), top = 2)
        r.add(col, w = 0, weight = 1f)
        val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = checked
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(st, intArrayOf((C.PRIMARY and 0x00FFFFFF) or 0x66000000, C.LINE_STRONG))
            thumbTintList = ColorStateList(st, intArrayOf(C.ACCENT, C.BG))
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
            suggestName(et, "Scan_Dokumen_$stamp", stamp)
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
                background = raised(C.SURFACE, 12, 2f, pressed = true)
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
                extraSection.showIf(fmt == Export.F_PDF)
                passBox.showIf(lock)
            }

            fun paintOpts() {
                optCards.forEachIndexed { i, c ->
                    val on = i == opt
                    c.background = choiceBg(on, 14)
                    if (on) { marks[i].setImageResource(R.drawable.ic_check_ok); marks[i].setColorFilter(C.ACCENT) }
                    else {
                        marks[i].clearColorFilter()
                        marks[i].setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(C.SURFACE2); setSize(dp(22), dp(22))
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
                    c.background = choiceBg(i == cardIdx, 14)
                }
                ext.text = when (fmt) { Export.F_PDF -> ".pdf"; Export.F_WORD -> ".docx"; else -> ".jpg" }
                opt = if (fmt == Export.F_WORD) 0 else 1
                buildOpts()
            }

            fun paintAspect() {
                aspectChips.forEachIndexed { i, c ->
                    val on = i == aIdx
                    c.setTextColor(if (on) C.ON_PRIMARY else C.MUTED)
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
                background = raised(C.SURFACE, 12, 2f, pressed = true)
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
