package cloud.depup.office

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * Layar DepUp yang dibuka sesaat setelah ketukan TUMBUH dari elemen yang diketuk (baris berkas, ubin alat,
 * tombol) — seperti aplikasi yang terbuka dari ikonnya di iPhone. Elemen dicari dari titik ketukan terakhir:
 * tampilan terdalam yang bisa diketuk di titik itu. Aplikasi lain (bagikan, buka dengan) tidak terpengaruh.
 * Mati otomatis bila animasi HP dimatikan.
 */
object LaunchFrom {
    private var x = 0
    private var y = 0
    private var at = 0L
    private const val FRESH_MS = 900L

    /** Panggil dari dispatchTouchEvent activity. */
    fun record(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            x = ev.rawX.toInt(); y = ev.rawY.toInt(); at = SystemClock.uptimeMillis()
        }
    }

    /** Opsi "tumbuh dari elemen" untuk [intent], atau null (biarkan transisi tema biasa). */
    fun options(act: Activity, intent: Intent): Bundle? {
        if (!Motion.on() || SystemClock.uptimeMillis() - at > FRESH_MS) return null
        val pkg = intent.component?.packageName ?: intent.`package`
        if (pkg != act.packageName) return null
        val v = hit(act.window.decorView) ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        at = 0L   // satu ketukan = satu layar
        return ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
    }

    private val loc = IntArray(2)

    /** Tampilan terdalam yang bisa diketuk & terlihat di titik ketukan (anak teratas diperiksa lebih dulu). */
    private fun hit(v: View): View? {
        if (!v.isShown) return null
        v.getLocationOnScreen(loc)
        if (x < loc[0] || y < loc[1] || x >= loc[0] + v.width * v.scaleX || y >= loc[1] + v.height * v.scaleY) return null
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) hit(v.getChildAt(i))?.let { return it }
        return if (v.isClickable && v !is ViewGroup || v.isClickable && v.hasOnClickListeners()) v else null
    }
}
