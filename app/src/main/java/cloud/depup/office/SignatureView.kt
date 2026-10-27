package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Kotak gambar tanda tangan. toBitmap() menghasilkan PNG transparan yang sudah dipotong pas. */
class SignatureView(ctx: Context) : View(ctx) {
    private val path = Path()
    private var empty = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1F6B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init { background = ctx.shape(Color.WHITE, 12) }

    fun clear() { path.reset(); empty = true; invalidate() }

    override fun onDraw(canvas: Canvas) { canvas.drawPath(path, paint) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> path.moveTo(e.x, e.y)
            MotionEvent.ACTION_MOVE -> { path.lineTo(e.x, e.y); empty = false }
        }
        invalidate()
        return true
    }

    fun toBitmap(): Bitmap? {
        if (empty || width <= 0 || height <= 0) return null
        val b = RectF()
        path.computeBounds(b, true)
        val pad = paint.strokeWidth * 2
        val l = max(0f, b.left - pad).toInt()
        val t = max(0f, b.top - pad).toInt()
        val r = min(width.toFloat(), b.right + pad).toInt()
        val bt = min(height.toFloat(), b.bottom + pad).toInt()
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(full).drawPath(path, paint)
        return Bitmap.createBitmap(full, l, t, max(1, r - l), max(1, bt - t))
    }
}
