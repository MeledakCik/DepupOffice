package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView interaktif dengan fitur cubit-zoom (pinch to zoom), geser (pan), dan ketuk dua kali (double tap).
 * Kompatibel penuh dengan ImageView standar.
 */
class ZoomImageView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : AppCompatImageView(ctx, attrs, defStyle) {

    private val imgMatrix = Matrix()
    private var mode = NONE

    var zoomScale = 1f
        private set
    private val minScale = 1f
    private val maxScale = 5f

    var onZoomChanged: ((Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mode = ZOOM
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var mScaleFactor = detector.scaleFactor
            val origScale = zoomScale
            zoomScale *= mScaleFactor

            if (zoomScale > maxScale) {
                zoomScale = maxScale
                mScaleFactor = maxScale / origScale
            } else if (zoomScale < minScale) {
                zoomScale = minScale
                mScaleFactor = minScale / origScale
            }

            if (origScale != zoomScale) {
                imgMatrix.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)
                checkMatrixAndBounds()
                onZoomChanged?.invoke(zoomScale)
            }
            return true
        }
    })

    private var zoomAnimator: android.animation.ValueAnimator? = null

    fun animateScaleTo(targetScale: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        val startScale = zoomScale
        val finalScale = targetScale.coerceIn(minScale, maxScale)
        if (Math.abs(startScale - finalScale) < 0.01f) return

        zoomAnimator = android.animation.ValueAnimator.ofFloat(startScale, finalScale).apply {
            duration = Motion.SPRING_SOFT_MS - 120L   // pegas lembut: mendarat halus, sedikit "mengendap"
            interpolator = Motion.SPRING_SOFT
            addUpdateListener { anim ->
                val currScale = anim.animatedValue as Float
                val factor = currScale / zoomScale
                zoomScale = currScale
                imgMatrix.postScale(factor, factor, focusX, focusY)
                checkMatrixAndBounds()
                imageMatrix = imgMatrix
                onZoomChanged?.invoke(zoomScale)
            }
            start()
        }
    }

    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (zoomScale > 1.2f) 1f else 2.5f
            animateScaleTo(targetScale, e.x, e.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = imgMatrix
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    fun resetZoom() {
        zoomScale = 1f
        imgMatrix.reset()
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()

        if (dw <= 0f || dh <= 0f || vw <= 0f || vh <= 0f) {
            post { if (width > 0 && height > 0) resetZoom() }
            return
        }

        val scaleX = vw / dw
        val scaleY = vh / dh
        val minFitScale = Math.min(scaleX, scaleY)

        val dx = (vw - dw * minFitScale) / 2f
        val dy = (vh - dh * minFitScale) / 2f

        imgMatrix.postScale(minFitScale, minFitScale)
        imgMatrix.postTranslate(dx, dy)
        imageMatrix = imgMatrix
        onZoomChanged?.invoke(zoomScale)
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        val currentX = event.x
        val currentY = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = currentX
                lastTouchY = currentY
                mode = DRAG
                parent?.requestDisallowInterceptTouchEvent(zoomScale > 1.05f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = ZOOM
                val index = event.actionIndex
                lastTouchX = event.getX(index)
                lastTouchY = event.getY(index)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && zoomScale > 1.05f) {
                    val dx = currentX - lastTouchX
                    val dy = currentY - lastTouchY
                    imgMatrix.postTranslate(dx, dy)
                    checkMatrixAndBounds()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                lastTouchX = currentX
                lastTouchY = currentY
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                val index = event.actionIndex
                val newIndex = if (index == 0) 1 else 0
                if (newIndex < event.pointerCount) {
                    lastTouchX = event.getX(newIndex)
                    lastTouchY = event.getY(newIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = NONE
                if (zoomScale < 1.0f) {
                    animateScaleTo(1.0f, width / 2f, height / 2f)
                } else {
                    checkMatrixAndBounds()
                }
            }
        }
        imageMatrix = imgMatrix
        return true
    }

    private fun checkMatrixAndBounds() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imgMatrix.mapRect(rect)

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() >= viewW) {
            if (rect.left > 0) deltaX = -rect.left
            if (rect.right < viewW) deltaX = viewW - rect.right
        } else {
            deltaX = viewW / 2f - (rect.left + rect.width() / 2f)
        }

        if (rect.height() >= viewH) {
            if (rect.top > 0) deltaY = -rect.top
            if (rect.bottom < viewH) deltaY = viewH - rect.bottom
        } else {
            deltaY = viewH / 2f - (rect.top + rect.height() / 2f)
        }

        imgMatrix.postTranslate(deltaX, deltaY)
        imageMatrix = imgMatrix
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
