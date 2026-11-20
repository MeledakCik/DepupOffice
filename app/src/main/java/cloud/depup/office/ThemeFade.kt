package cloud.depup.office

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import java.util.WeakHashMap
import kotlin.math.hypot
import kotlin.math.max

/**
 * Transisi saat tema berganti terang ↔ gelap. Semua tampilan Depup dibangun dengan warna token ([C]), jadi berganti tema
 * berarti layar dibangun ulang (recreate) — tanpa transisi, layar langsung "meloncat". Alurnya:
 *  1. jendela yang tampil dipotret apa adanya (PixelCopy: ikut kaca GPU, blur, dst.);
 *  2. tema diganti & layar dibangun ulang di balik potret itu;
 *  3. potret dibuka: lingkaran berpinggir lembut yang melebar dari titik ketukan (pilihan Tema di Saya), atau memudar
 *     bila pergantian datang dari HP (tema sistem berganti saat "Ikuti HP").
 * Mengikuti setelan animasi HP: animasi dimatikan = langsung tanpa transisi.
 */
object ThemeFade {
    private class Shot(val bmp: Bitmap, val origin: PointF?, val at: Long)

    private var pending: Shot? = null
    private var tx = 0f
    private var ty = 0f
    private var tAt = 0L
    private val busy = WeakHashMap<Activity, Boolean>()
    private val built = WeakHashMap<Activity, Boolean>()

    /** Catat tema (gelap?) yang dipakai saat layar [a] dibangun — pembanding saat konfigurasi berubah. */
    fun built(a: Activity, dark: Boolean) { built[a] = dark }

    /** Catat ketukan terakhir (koordinat jendela) — titik asal lingkaran transisi. Panggil dari dispatchTouchEvent. */
    fun touch(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_UP) { tx = ev.x; ty = ev.y; tAt = SystemClock.uptimeMillis() }
    }

    /**
     * Ganti tema [a] dengan transisi: potret layar lama, jalankan [apply] (ubah setelan tema), lalu bangun ulang layar.
     * [fromTouch] = lingkaran melebar dari ketukan terakhir; false = memudar.
     */
    fun switchTo(a: Activity, fromTouch: Boolean = true, apply: () -> Unit = {}) {
        if (busy[a] == true) return
        busy[a] = true
        val origin = if (fromTouch && SystemClock.uptimeMillis() - tAt < 2000L) PointF(tx, ty) else null
        capture(a, origin) {
            apply()
            if (!a.isFinishing && !a.isDestroyed) a.recreate()
        }
    }

    /** Konfigurasi berubah (mis. tema HP berganti): bila terang/gelap berubah, bangun ulang layar dengan transisi. */
    fun onConfig(a: Activity) {
        // dibandingkan dengan tema saat layar INI dibangun, bukan C.dark global (bisa sudah diperbarui layar lain)
        if (busy[a] != true && Theme.isDark(a) != (built[a] ?: C.dark)) switchTo(a, fromTouch = false)
    }

    private fun capture(a: Activity, origin: PointF?, then: () -> Unit) {
        val w = a.window
        val dv = w?.decorView
        val bmp = if (w != null && dv != null && dv.width > 0 && dv.height > 0 && Motion.on() && dv.isShown)
            try { Bitmap.createBitmap(dv.width, dv.height, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        else null
        if (w == null || bmp == null) { pending = null; then(); return }
        val main = Handler(Looper.getMainLooper())
        var done = false
        fun finish(ok: Boolean) {
            if (done) return
            done = true
            if (ok) pending = Shot(bmp, origin, SystemClock.uptimeMillis()) else { pending = null; bmp.recycle() }
            then()
        }
        try {
            PixelCopy.request(w, bmp, { r -> finish(r == PixelCopy.SUCCESS) }, main)
            main.postDelayed({ finish(false) }, 300L)   // salinan tak kunjung selesai → ganti tanpa transisi
        } catch (_: Throwable) { finish(false) }
    }

    /** Dipanggil sesudah layar (baru) dibuat: tutupi dengan potret layar lama, lalu buka dengan animasi. */
    fun play(a: Activity) {
        val s = pending ?: return
        pending = null
        val dv = a.window?.decorView as? ViewGroup
        if (dv == null || SystemClock.uptimeMillis() - s.at > 4000L) { s.bmp.recycle(); return }
        val veil = Veil(a, s.bmp, s.origin)
        dv.addView(veil, ViewGroup.LayoutParams(MATCH, MATCH))
        veil.start { dv.removeView(veil); s.bmp.recycle() }
    }

    /** Lapisan potret layar lama di atas segalanya (juga menahan sentuhan selama transisi yang singkat). */
    private class Veil(ctx: Context, private val bmp: Bitmap, private val origin: PointF?) : View(ctx) {
        private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
        private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        private val feather = 48f * resources.displayMetrics.density
        private var r = 0f
        private var alphaK = 1f

        fun start(end: () -> Unit) {
            val o = origin
            val maxR = if (o == null) 0f else {
                val w = max(width, bmp.width).toFloat(); val h = max(height, bmp.height).toFloat()
                maxOf(hypot(o.x, o.y), hypot(w - o.x, o.y), hypot(o.x, h - o.y), hypot(w - o.x, h - o.y)) + feather
            }
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (o != null) 620L else 380L
                interpolator = PathInterpolator(0.4f, 0f, 0.15f, 1f)
                addUpdateListener { t ->
                    val f = t.animatedValue as Float
                    if (o != null) r = f * maxR else alphaK = 1f - f
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { end() }
                })
            }
            // mulai sesudah layar baru selesai digambar sekali di balik potret (tidak ada kilatan kosong)
            post { postOnAnimation { anim.start() } }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = true

        override fun onDraw(c: Canvas) {
            val o = origin
            if (o == null) {
                bp.alpha = (alphaK * 255).toInt().coerceIn(0, 255)
                c.drawBitmap(bmp, 0f, 0f, bp)
                return
            }
            val sc = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            c.drawBitmap(bmp, 0f, 0f, bp)
            if (r > 0f) {
                // lubang lingkaran berpinggir lembut: penuh sampai r, memudar selebar [feather]
                val rr = r + feather
                hole.shader = RadialGradient(o.x, o.y, rr, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                    floatArrayOf(0f, r / rr, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(o.x, o.y, rr, hole)
            }
            c.restoreToCount(sc)
        }
    }
}
