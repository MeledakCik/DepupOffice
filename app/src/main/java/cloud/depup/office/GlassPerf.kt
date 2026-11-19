package cloud.depup.office

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import java.lang.ref.WeakReference

/**
 * Kualitas kaca adaptif. Bagian kaca yang paling berat untuk GPU adalah lapisan tepi bening, blur progresif empat
 * lapis di bilah atas, dan dispersi lima warna. Selama layar di depan, waktu GPU tiap frame diukur (FrameMetrics,
 * Android 12+). Hanya frame yang benar-benar digambar yang dihitung (gulir, animasi), jadi layar diam tidak memicu apa
 * pun. Bila dari 120 frame setidaknya 15% memakai GPU lebih lama dari batas waktu frame, kualitas turun satu tingkat:
 *  - tingkat 1: tanpa lapisan tepi bening; blur progresif 3 lapis
 *  - tingkat 2: blur progresif 2 lapis; dispersi mati
 * Tampilan tetap kaca, hanya lebih ringan. Tingkat tidak disimpan: aplikasi dibuka ulang = mulai dari kualitas penuh.
 */
object GlassPerf {
    /** 0 = penuh, 1 = ringan, 2 = paling ringan. Dibaca setiap kali kaca digambar. */
    @Volatile var level = 0
        private set

    private const val WINDOW = 120
    private const val HEAVY_PCT = 15

    private var handler: Handler? = null
    private var window: WeakReference<Window>? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var frames = 0
    private var heavy = 0

    /** Mulai mengukur jendela [a] (dipanggil saat layar tampil). */
    fun start(a: Activity) {
        if (Build.VERSION.SDK_INT < 31 || level >= 2) return
        stop()
        val w = a.window ?: return
        val h = handler ?: Handler(HandlerThread("glassPerf").also { it.start() }.looper).also { handler = it }
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ -> onFrame(m) }
        try {
            w.addOnFrameMetricsAvailableListener(l, h)
            window = WeakReference(w); listener = l
        } catch (_: Throwable) {}
    }

    /** Berhenti mengukur (layar ke belakang). */
    fun stop() {
        val l = listener ?: return
        try { window?.get()?.removeOnFrameMetricsAvailableListener(l) } catch (_: Throwable) {}
        listener = null; window = null
        frames = 0; heavy = 0
    }

    @android.annotation.TargetApi(31)
    private fun onFrame(m: FrameMetrics) {
        if (!Glass.BLUR || level >= 2) return
        val gpu = m.getMetric(FrameMetrics.GPU_DURATION)
        val budget = m.getMetric(FrameMetrics.DEADLINE)
        if (gpu <= 0L || budget <= 0L) return
        frames++
        if (gpu > budget) heavy++
        if (frames < WINDOW) return
        val tooHeavy = heavy * 100 >= frames * HEAVY_PCT
        frames = 0; heavy = 0
        if (!tooHeavy) return
        level++
        // gambar ulang semua kaca dengan tingkat baru (di utas utama)
        Handler(Looper.getMainLooper()).post { GlassLight.refreshAll() }
    }
}
