package cloud.depup.office

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import java.util.WeakHashMap

/**
 * Transisi saat tema berganti terang ↔ gelap, sederhana seperti iOS: seluruh layar memudar (crossfade) dari tema lama
 * ke tema baru dalam ±0,3 detik. Semua tampilan Depup dibangun dengan warna token ([C]), jadi berganti tema berarti
 * tampilan dibangun ulang. Alurnya:
 *  1. jendela yang tampil dipotret apa adanya (PixelCopy: ikut kaca GPU, blur, dst.) dan dipasang di atas layar;
 *  2. di balik potret itu tampilan dibangun ulang dengan tema baru — di TEMPAT ([Host], layar utama: cepat, tanpa
 *     membangun ulang activity) atau dengan recreate (layar lain, saat tema HP berganti);
 *  3. potret memudar. Warna bilah status/navigasi sistem ikut berganti di tengah pudaran.
 * Mengikuti setelan animasi HP: animasi dimatikan = langsung tanpa transisi.
 */
object ThemeFade {
    /** Layar yang bisa membangun ulang tampilannya sendiri untuk tema baru (tanpa recreate). */
    interface Host {
        /** Bangun ulang seluruh tampilan dengan token warna tema saat ini (C.dark sudah diperbarui). */
        fun rebuildForTheme()
    }

    private const val FADE_MS = 300L
    private val EASE = PathInterpolator(0.42f, 0f, 0.58f, 1f)   // ease-in-out, kurva bawaan animasi iOS

    private class Shot(val bmp: Bitmap, val at: Long)

    private var pending: Shot? = null
    private val busy = WeakHashMap<Activity, Boolean>()
    private val built = WeakHashMap<Activity, Boolean>()

    /** Catat tema (gelap?) yang dipakai saat layar [a] dibangun — pembanding saat konfigurasi berubah. */
    fun built(a: Activity, dark: Boolean) { built[a] = dark }

    /**
     * Ganti tema dengan transisi. [apply] mengubah setelan (mis. mode tema & mode malam AppCompat). Layar [Host]
     * dibangun ulang di tempat; layar lain di-recreate.
     */
    fun change(a: Activity, apply: () -> Unit = {}) {
        if (busy[a] == true) return
        busy[a] = true
        capture(a) { shot ->
            apply()
            val host = a as? Host
            if (host == null) {
                pending = shot
                if (!a.isFinishing && !a.isDestroyed) a.recreate() else shot?.bmp?.recycle()
                return@capture
            }
            val veil = shot?.let { cover(a, it) }
            try {
                C.dark = Theme.isDark(a)
                host.rebuildForTheme()
            } finally {
                built[a] = C.dark
                busy.remove(a)
            }
            if (veil == null) Theme.apply(a)
            else veil.fadeOut(onHalf = { Theme.apply(a) })
        }
    }

    /** Konfigurasi berubah (mis. tema HP berganti): bila terang/gelap berubah, bangun ulang dengan transisi. */
    fun onConfig(a: Activity) {
        if (busy[a] != true && Theme.isDark(a) != (built[a] ?: C.dark)) change(a)
    }

    /** Dipanggil sesudah layar (hasil recreate) dibuat: tutupi dengan potret layar lama, lalu pudarkan. */
    fun play(a: Activity) {
        val s = pending ?: return
        pending = null
        if (SystemClock.uptimeMillis() - s.at > 4000L || a.window?.decorView !is ViewGroup) { s.bmp.recycle(); return }
        cover(a, s).fadeOut()
    }

    private fun capture(a: Activity, then: (Shot?) -> Unit) {
        val w = a.window
        val dv = w?.decorView
        val bmp = if (w != null && dv != null && dv.width > 0 && dv.height > 0 && Motion.on() && dv.isShown)
            try { Bitmap.createBitmap(dv.width, dv.height, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        else null
        if (w == null || bmp == null) { then(null); return }
        val main = Handler(Looper.getMainLooper())
        var done = false
        fun finish(ok: Boolean) {
            if (done) return
            done = true
            if (ok) then(Shot(bmp, SystemClock.uptimeMillis())) else { bmp.recycle(); then(null) }
        }
        try {
            PixelCopy.request(w, bmp, { r -> finish(r == PixelCopy.SUCCESS) }, main)
            main.postDelayed({ finish(false) }, 300L)   // salinan tak kunjung selesai → ganti tanpa transisi
        } catch (_: Throwable) { finish(false) }
    }

    private fun cover(a: Activity, s: Shot): Veil {
        val dv = a.window.decorView as ViewGroup
        val v = Veil(a, s.bmp)
        dv.addView(v, ViewGroup.LayoutParams(MATCH, MATCH))
        return v
    }

    /** Potret layar lama di atas segalanya (juga menahan sentuhan selama pudaran yang singkat). */
    private class Veil(ctx: Context, private val bmp: Bitmap) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        /** Mulai memudar sesudah layar baru selesai digambar sekali di balik potret (tidak ada kilatan). */
        fun fadeOut(onHalf: () -> Unit = {}) {
            post {
                postOnAnimation {
                    postDelayed({ onHalf() }, FADE_MS / 2)
                    animate().alpha(0f).setDuration(FADE_MS).setInterpolator(EASE).withEndAction {
                        (parent as? ViewGroup)?.removeView(this)
                        bmp.recycle()
                    }.start()
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = true

        override fun onDraw(c: Canvas) {
            if (!bmp.isRecycled) c.drawBitmap(bmp, 0f, 0f, paint)
        }
    }
}
