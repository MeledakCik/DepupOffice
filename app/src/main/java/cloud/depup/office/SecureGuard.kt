package cloud.depup.office

import android.app.Activity
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mode aman untuk dokumen TERKUNCI (PDF berpassword, Word "hanya bisa diedit dengan password", Dokumen Terlindungi):
 *  - FLAG_SECURE: tangkapan layar ditolak, rekaman layar & casting hanya merekam hitam, gambar riwayat aplikasi gelap;
 *  - Android 13+: snapshot riwayat aplikasi dimatikan;
 *  - layar ditutup hitam saat: aplikasi ke latar (riwayat aplikasi), layar terpisah / jendela mengambang,
 *    layar sedang direkam (deteksi Android 15+), atau ada layar kedua (casting / mirroring ke TV/proyektor).
 * Pasang dengan [enable]; teruskan onPause/onResume/onMultiWindowModeChanged; lepas di onDestroy.
 */
class SecureGuard(private val a: Activity) {
    var on = false; private set
    private var paused = false
    private var recording = false
    private var cover: View? = null
    private var msg: TextView? = null
    private var recCb: java.util.function.Consumer<Int>? = null
    private val dm by lazy { a.getSystemService(DisplayManager::class.java) }
    private val dl = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = update()
        override fun onDisplayRemoved(id: Int) = update()
        override fun onDisplayChanged(id: Int) = update()
    }

    fun enable() {
        if (on) return
        on = true
        a.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) try { a.setRecentsScreenshotEnabled(false) } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= 35) try {
            val cb = java.util.function.Consumer<Int> { st ->
                recording = st == WindowManager.SCREEN_RECORDING_STATE_VISIBLE
                a.runOnUiThread { update() }
            }
            recording = a.windowManager.addScreenRecordingCallback(a.mainExecutor, cb) == WindowManager.SCREEN_RECORDING_STATE_VISIBLE
            recCb = cb
        } catch (_: Throwable) {}
        try { dm?.registerDisplayListener(dl, null) } catch (_: Throwable) {}
        update()
    }

    fun disable() {
        if (!on) return
        on = false
        if (Build.VERSION.SDK_INT >= 35) recCb?.let { try { a.windowManager.removeScreenRecordingCallback(it) } catch (_: Throwable) {} }
        recCb = null
        try { dm?.unregisterDisplayListener(dl) } catch (_: Throwable) {}
    }

    fun onPause() { paused = true; update() }
    fun onResume() { paused = false; update() }
    fun onMultiWindow() = update()

    /** Ada layar lain yang menampilkan isi (casting / mirroring / layar presentasi). */
    private fun extraDisplay(): Boolean = try {
        // hanya layar kategori presentasi (casting/proyektor) — layar sampul HP lipat tidak ikut terhitung
        dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.any { it.displayId != Display.DEFAULT_DISPLAY && it.state == Display.STATE_ON } == true
    } catch (_: Throwable) { false }

    private fun update() {
        if (!on) { cover?.visibility = View.GONE; return }
        val reason = when {
            recording -> "Layar sedang direkam. Dokumen terkunci tidak ditampilkan selama perekaman layar."
            a.isInMultiWindowMode -> "Dokumen terkunci tidak ditampilkan di layar terpisah atau jendela mengambang."
            extraDisplay() -> "Dokumen terkunci tidak ditampilkan saat layar dibagikan ke perangkat lain (casting/mirroring)."
            paused -> ""
            else -> null
        }
        if (reason == null) { cover?.visibility = View.GONE; return }
        val c = cover ?: build().also { cover = it }
        msg?.text = reason
        msg?.visibility = if (reason.isEmpty()) View.GONE else View.VISIBLE
        c.visibility = View.VISIBLE
        c.bringToFront()
    }

    private fun build(): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(a.dp(32), a.dp(32), a.dp(32), a.dp(32))
        }
        box.addView(a.icon(R.drawable.ic_lock, Color.WHITE, 40), LinearLayout.LayoutParams(a.dp(40), a.dp(40)))
        msg = TextView(a).apply { setTextColor(0xFFDDDDDD.toInt()); textSize = 14f; gravity = Gravity.CENTER }
        box.addView(msg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(14) })
        val f = FrameLayout(a).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true; isFocusable = true      // sentuhan tidak tembus ke dokumen
            addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            elevation = 1000f
        }
        (a.window.decorView as ViewGroup).addView(f, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return f
    }
}
