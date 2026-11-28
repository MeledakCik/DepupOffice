package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.min

/**
 * "Mode Dokumen" editor Sheet: lembar kerja tampil sebagai halaman A4 bertumpuk — persis seperti hasil Word/PDF
 * (memakai [SheetPrint.Renderer] yang sama). Gulir, lempar, cubit untuk memperbesar (1×–4×), ketuk dua kali untuk
 * memperbesar/kembali. Hanya halaman yang tampak yang digambar.
 */
@SuppressLint("ViewConstructor")
class SheetDocView(ctx: Context) : View(ctx) {

    var renderer: SheetPrint.Renderer? = null
        set(v) { field = v; zoom = 1f; offX = 0f; offY = 0f; invalidate(); contentDescription = v?.let { "Pratinjau dokumen, ${it.pageCount} halaman" } }

    private val density = resources.displayMetrics.density
    private val pad = 16 * density
    private val gap = 14 * density
    private var zoom = 1f
    private var offX = 0f
    private var offY = 0f
    private val scroller = OverScroller(ctx)
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val pageP = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val pillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = Fonts.head(ctx)
    }
    private val rf = RectF()

    /** Skala pt → px pada zoom 1 (halaman terlebar memenuhi lebar layar). */
    private fun fit(): Float {
        val rd = renderer ?: return 1f
        var maxW = 1f
        for (i in 0 until rd.pageCount) maxW = max(maxW, rd.pageW(i))
        return ((width - 2 * pad) / maxW).coerceAtLeast(0.05f)
    }

    private fun scale() = fit() * zoom

    private fun contentH(): Float {
        val rd = renderer ?: return 0f
        val s = scale()
        var h = pad
        for (i in 0 until rd.pageCount) h += rd.pageH(i) * s + gap
        return h + pad
    }

    private fun contentW(): Float {
        val rd = renderer ?: return 0f
        var w = 0f
        for (i in 0 until rd.pageCount) w = max(w, rd.pageW(i))
        return w * scale() + 2 * pad
    }

    private fun clamp() {
        offX = offX.coerceIn(0f, max(0f, contentW() - width))
        offY = offY.coerceIn(0f, max(0f, contentH() - height))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = clamp()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(C.SURFACE2)
        val rd = renderer ?: return
        val s = scale()
        var y = pad - offY
        var first = -1
        for (i in 0 until rd.pageCount) {
            val pw = rd.pageW(i) * s; val ph = rd.pageH(i) * s
            if (y > height) break
            if (y + ph >= 0) {
                if (first < 0) first = i
                val x = max(pad, (contentW() - pw) / 2f) - offX
                rf.set(x + 2 * density, y + 3 * density, x + pw + 2 * density, y + ph + 3 * density)
                canvas.drawRect(rf, shadowP)
                rf.set(x, y, x + pw, y + ph)
                canvas.drawRect(rf, pageP)
                canvas.save()
                canvas.clipRect(rf)
                canvas.translate(x, y)
                canvas.scale(s, s)
                rd.drawPage(canvas, i)
                canvas.restore()
            }
            y += ph + gap
        }
        // penunjuk halaman
        if (first >= 0) {
            val label = "Halaman ${first + 1} dari ${rd.pageCount}" + if (zoom > 1.01f) " · ${(zoom * 100).toInt()}%" else ""
            val tw = pillText.measureText(label) + 24 * density
            val th = 28 * density
            rf.set((width - tw) / 2f, height - th - 12 * density, (width + tw) / 2f, height - 12 * density)
            pillP.color = 0xCC1A1A1A.toInt()
            canvas.drawRoundRect(rf, th / 2, th / 2, pillP)
            pillText.color = 0xFFFFFFFF.toInt()
            canvas.drawText(label, rf.centerX(), rf.centerY() - (pillText.descent() + pillText.ascent()) / 2f, pillText)
        }
    }

    // ---------- sentuhan ----------

    private fun zoomAt(newZoom: Float, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(1f, 4f)
        if (z == zoom) return
        // titik di bawah jari tetap di tempatnya
        val cx = (offX + fx) / zoom; val cy = (offY + fy) / zoom
        zoom = z
        offX = cx * zoom - fx; offY = cy * zoom - fy
        clamp(); invalidate()
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomAt(zoom * d.scaleFactor, d.focusX, d.focusY); return true }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            offX += dx; offY += dy; clamp(); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(offX.toInt(), offY.toInt(), -vx.toInt(), -vy.toInt(), 0, max(0f, contentW() - width).toInt(), 0, max(0f, contentH() - height).toInt())
            postInvalidateOnAnimation(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomAt(if (zoom > 1.2f) 1f else 2.5f, e.x, e.y); return true }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.currX.toFloat(); offY = scroller.currY.toFloat()
            clamp(); postInvalidateOnAnimation()
        }
    }

    fun pageAtTop(): Int {
        val rd = renderer ?: return 0
        val s = scale()
        var y = pad
        for (i in 0 until rd.pageCount) { y += rd.pageH(i) * s + gap; if (y > offY) return i }
        return max(0, rd.pageCount - 1)
    }

    @Suppress("unused") private fun minOfZero(a: Float) = min(a, 0f)
}
