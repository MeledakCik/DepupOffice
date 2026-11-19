package cloud.depup.office

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.Magnifier
import kotlin.math.hypot

/**
 * Sistem seleksi teks milik Depup (editor Word) — menggantikan pegangan & kaca pembesar bawaan Android, yang
 * digambar sistem dan tidak bisa diberi gaya:
 *
 * - Pegangan seleksi minimalis: garis setinggi baris + bulatan warna aksen polos (flat) di ujung atas/bawah.
 * - Pegangan kursor: bulatan aksen di bawah kursor (muncul sesudah mengetuk, hilang saat mengetik / beberapa detik).
 * - Kaca pembesar (loupe) kapsul dengan satu garis tepi warna aksen polos — terpisah dari halaman tanpa efek.
 *
 * Semua digambar di satu lapisan transparan di atas scaffold kaca; sentuhan yang tidak mengenai pegangan diteruskan
 * ke isi di bawahnya. Logika gerak (tekan-lama, ketuk-ganda/tiga) ada di [ParaEdit].
 */
object SelectionKit {
    const val NONE = 0
    const val SELECT = 1
    const val CARET = 2

    private var mode = NONE
    private var para: ParaEdit? = null
    private var overlay: HandleOverlay? = null
    private var loupe: Magnifier? = null
    private var loupeHost: View? = null
    private var appear = 1f
    private var appearAnim: android.animation.ValueAnimator? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val caretTimeout = Runnable { if (mode == CARET) hide() }

    fun activeFor(p: ParaEdit) = para === p && mode != NONE

    /** Tampilkan pegangan seleksi untuk [p] (seleksi sudah dipasang di [p]). */
    fun showSelection(p: ParaEdit) = show(p, SELECT)

    /** Tampilkan pegangan kursor sebentar (ketukan tunggal di paragraf yang sedang diketik). */
    fun showCaret(p: ParaEdit) {
        show(p, CARET)
        main.removeCallbacks(caretTimeout); main.postDelayed(caretTimeout, 4000)
    }

    private fun show(p: ParaEdit, m: Int) {
        val sc = scaffoldOf(p) ?: return
        val o = overlay?.takeIf { it.parent === sc } ?: HandleOverlay(sc.context).also { ov ->
            overlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
            sc.addView(ov, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            overlay = ov
        }
        val fresh = mode != m || para !== p
        para = p; mode = m
        o.bringToFront()
        if (fresh) {
            appearAnim?.cancel()
            if (Motion.on()) {
                appear = 0f
                appearAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = Motion.SPRING_MS; interpolator = Motion.SPRING
                    addUpdateListener { appear = it.animatedValue as Float; o.invalidate() }
                    start()
                }
            } else appear = 1f
        }
        o.invalidate()
    }

    /** Sembunyikan semua pegangan & loupe. [only] = hanya bila milik paragraf ini. */
    fun hide(only: ParaEdit? = null) {
        if (only != null && para !== only) return
        mode = NONE; para = null
        main.removeCallbacks(caretTimeout)
        hideLoupe()
        overlay?.invalidate()
    }

    /** Posisi pegangan berubah (seleksi, gulir, zoom): gambar ulang. */
    fun refresh() { overlay?.invalidate() }

    /** Mengetik: pegangan kursor hilang (seperti Android/iOS). */
    fun typed(p: ParaEdit) { if (para === p && mode == CARET) hide() }

    // ---------------------------------------------------------------------------------------------
    // koordinat: lokal paragraf ↔ scaffold (memperhitungkan zoom, skala & gulir semua induk)
    // ---------------------------------------------------------------------------------------------
    fun scaffoldOf(p: View): GlassScaffold? = GlassContent.find(p.rootView)?.parent as? GlassScaffold

    fun localToSc(p: View, sc: View): Matrix {
        val m = Matrix(); p.transformMatrixToGlobal(m)
        val s = Matrix(); sc.transformMatrixToGlobal(s)
        val inv = Matrix(); s.invert(inv)
        m.postConcat(inv)
        return m
    }

    fun map(m: Matrix, x: Float, y: Float): FloatArray { val a = floatArrayOf(x, y); m.mapPoints(a); return a }

    // ---------------------------------------------------------------------------------------------
    // kaca pembesar
    // ---------------------------------------------------------------------------------------------
    /** Tampilkan loupe yang memperbesar titik [x],[y] (koordinat lokal [p], tengah baris teks). */
    fun showLoupe(p: ParaEdit, x: Float, y: Float) {
        val sc = scaffoldOf(p) ?: return
        val l = loupe?.takeIf { loupeHost === sc } ?: run {
            loupe?.dismiss()
            val d = sc.resources.displayMetrics.density
            Magnifier.Builder(sc)
                .setSize((128 * d).toInt(), (54 * d).toInt())
                .setCornerRadius(27 * d)
                .setInitialZoom(1.4f)
                .setElevation(0f)
                .setDefaultSourceToMagnifierOffset(0, -(60 * d).toInt())
                .setOverlay(LoupeGlass(d))
                .build().also { loupe = it; loupeHost = sc }
        }
        val pt = map(localToSc(p, sc), x, y)
        l.show(pt[0], pt[1])
    }

    fun hideLoupe() { loupe?.dismiss() }

    // ---------------------------------------------------------------------------------------------
    // lapisan pegangan
    // ---------------------------------------------------------------------------------------------
    /** Butir & garis pegangan dalam koordinat scaffold — dihitung tiap kali digambar. */
    private class Knobs(val ax: Float, val aTop: Float, val aBot: Float, val bx: Float, val bTop: Float, val bBot: Float)

    private fun knobs(sc: View): Knobs? {
        val p = para ?: return null
        if (!p.isAttachedToWindow || !p.isFocused) return null
        val l = p.layout ?: return null
        val n = p.text?.length ?: 0
        val a = p.selectionStart.coerceIn(0, n); val b = p.selectionEnd.coerceIn(0, n)
        if (mode == SELECT && a == b) return null
        val m = localToSc(p, sc)
        val ox = (p.totalPaddingLeft - p.scrollX).toFloat(); val oy = (p.totalPaddingTop - p.scrollY).toFloat()
        fun pos(off: Int): FloatArray {
            val line = l.getLineForOffset(off)
            val x = l.getPrimaryHorizontal(off) + ox
            val t = map(m, x, l.getLineTop(line) + oy); val bt = map(m, x, l.getLineBottom(line) + oy)
            return floatArrayOf(t[0], t[1], bt[1])
        }
        val s = pos(minOf(a, b)); val e = pos(maxOf(a, b))
        return Knobs(s[0], s[1], s[2], e[0], e[1], e[2])
    }

    @android.annotation.SuppressLint("ViewConstructor")
    private class HandleOverlay(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = 6f * d
        private var grab = 0          // 0 tidak ada, 1 awal, 2 akhir, 3 kursor
        private var fixed = 0         // ujung seleksi yang diam saat menggeser
        private var grabDy = 0f

        private val scrollL = ViewTreeObserver.OnScrollChangedListener { if (mode != NONE) invalidate() }
        private val layoutL = ViewTreeObserver.OnGlobalLayoutListener { if (mode != NONE) invalidate() }

        init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnScrollChangedListener(scrollL); viewTreeObserver.addOnGlobalLayoutListener(layoutL)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnScrollChangedListener(scrollL); viewTreeObserver.removeOnGlobalLayoutListener(layoutL)
            super.onDetachedFromWindow()
        }

        /** Butir pegangan: lingkaran warna aksen polos (flat) — tanpa bayangan, tepi, atau kilau. */
        private fun bead(c: Canvas, x: Float, y: Float, s: Float) {
            val rr = r * s
            if (rr <= 0.5f) return
            fill.color = C.ACCENT
            c.drawCircle(x, y, rr, fill)
        }

        override fun onDraw(c: Canvas) {
            if (mode == NONE) return
            val k = knobs(this) ?: return
            val s = appear
            bar.color = C.ACCENT
            if (mode == SELECT) {
                c.drawLine(k.ax, k.aTop, k.ax, k.aBot, bar)
                c.drawLine(k.bx, k.bTop, k.bx, k.bBot, bar)
                bead(c, k.ax, k.aTop - r * 0.6f, s * (if (grab == 1) 1.25f else 1f))
                bead(c, k.bx, k.bBot + r * 0.6f, s * (if (grab == 2) 1.25f else 1f))
            } else if (mode == CARET) {
                bead(c, k.ax, k.aBot + r * 1.1f, s * (if (grab == 3) 1.25f else 1f))
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = para
            if (mode == NONE || p == null) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val k = knobs(this) ?: return false
                    val hit = 26f * d
                    grab = when {
                        mode == SELECT && hypot(e.x - k.bx, e.y - (k.bBot + r)) < hit -> 2
                        mode == SELECT && hypot(e.x - k.ax, e.y - (k.aTop - r)) < hit -> 1
                        mode == SELECT && e.y in k.bTop..k.bBot && kotlin.math.abs(e.x - k.bx) < hit * 0.6f -> 2
                        mode == SELECT && e.y in k.aTop..k.aBot && kotlin.math.abs(e.x - k.ax) < hit * 0.6f -> 1
                        mode == CARET && hypot(e.x - k.ax, e.y - (k.aBot + r * 1.1f)) < hit -> 3
                        else -> 0
                    }
                    if (grab == 0) return false            // bukan pegangan: sentuhan diteruskan ke isi
                    // jari menyentuh butir; yang dipindah adalah titik di tengah baris → simpan selisihnya
                    grabDy = when (grab) { 1 -> (k.aTop + k.aBot) / 2f - e.y; 2 -> (k.bTop + k.bBot) / 2f - e.y; else -> (k.aTop + k.aBot) / 2f - e.y }
                    fixed = if (grab == 1) maxOf(p.selectionStart, p.selectionEnd) else minOf(p.selectionStart, p.selectionEnd)
                    main.removeCallbacks(caretTimeout)
                    p.handleDragging(true)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (grab == 0) return false
                    val sc = parent as? View ?: return true
                    val inv = Matrix(); localToSc(p, sc).invert(inv)
                    val pt = map(inv, e.x, e.y + grabDy)
                    val off = p.getOffsetForPosition(pt[0], pt[1])
                    if (grab == 3) p.setSelection(off)
                    else if (off != fixed) p.setSelection(minOf(fixed, off), maxOf(fixed, off))
                    // loupe mengikuti jari mendatar, tegak dikunci ke tengah baris teks
                    val l = p.layout
                    if (l != null) {
                        val line = l.getLineForOffset(off)
                        val cy = (l.getLineTop(line) + l.getLineBottom(line)) / 2f + p.totalPaddingTop - p.scrollY
                        showLoupe(p, pt[0], cy)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (grab == 0) return false
                    val was = grab
                    grab = 0
                    hideLoupe()
                    p.handleDragging(false)
                    if (was == 3) main.postDelayed(caretTimeout, 4000)
                    invalidate()
                    return true
                }
            }
            return grab != 0
        }
    }

    /**
     * Bingkai loupe minimalis: satu garis tepi warna aksen polos (flat) — cukup untuk memisahkan loupe dari halaman
     * tanpa bayangan, kilau, atau efek kaca.
     */
    private class LoupeGlass(private val d: Float) : Drawable() {
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
        private val rf = RectF()
        override fun draw(c: Canvas) {
            edge.color = C.ACCENT
            rf.set(bounds); rf.inset(1f * d, 1f * d)
            c.drawRoundRect(rf, rf.height() / 2f, rf.height() / 2f, edge)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
