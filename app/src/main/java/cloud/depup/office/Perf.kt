package cloud.depup.office

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Profil kemampuan perangkat. Dihitung sekali saat aplikasi mulai, lalu dipakai untuk menyesuaikan
 * ukuran cache, resolusi render, dan resolusi kamera supaya HP kelas menengah ke bawah tetap lancar.
 */
object Perf {
    /** true untuk HP RAM ≤ 3 GB, heap kecil, ≤ 4 inti, atau yang ditandai "low RAM" oleh sistem. */
    @Volatile var low = false
        private set

    /** Batas heap Java aplikasi (byte). */
    @Volatile var heap = Runtime.getRuntime().maxMemory()
        private set

    private val trimHooks = mutableListOf<() -> Unit>()

    fun init(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val ramGb = mi.totalMem / (1024.0 * 1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        heap = Runtime.getRuntime().maxMemory()
        low = am?.isLowRamDevice == true ||
            (am?.memoryClass ?: 256) <= 192 ||
            (mi.totalMem > 0 && ramGb <= 3.2) ||
            cores <= 4
    }

    /** Ukuran cache bitmap: 1/8 heap (HP lemah) atau 1/5 heap, dibatasi 16–64 MB. */
    fun bitmapCacheBytes(): Int {
        val frac = if (low) heap / 8 else heap / 5
        return frac.coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024).toInt()
    }

    /** Sisi terpanjang maksimum saat merender halaman PDF di layar. */
    fun pageMaxPx(): Int = if (low) 1800 else 2600

    /** Batas dimensi dekode untuk OCR/ekspor resolusi tinggi. */
    fun ocrCap(): Int = if (low) 2800 else 3600

    /** Jumlah pratinjau halaman pindaian yang disimpan di memori. */
    fun previewSlots(): Int = if (low) 2 else 4

    /** Thread latar bersama untuk pekerjaan kecil (dekode gambar, thumbnail) — tidak membuat thread baru tiap tugas. */
    val io: ExecutorService by lazy {
        val n = AtomicInteger()
        Executors.newFixedThreadPool(if (low) 2 else 3) { r ->
            Thread(r, "depup-io-${n.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    /** Daftarkan pembersih cache yang dipanggil saat sistem kehabisan memori. */
    fun onTrim(hook: () -> Unit) { synchronized(trimHooks) { trimHooks.add(hook) } }
    fun removeTrim(hook: () -> Unit) { synchronized(trimHooks) { trimHooks.remove(hook) } }

    fun trim() {
        val hooks = synchronized(trimHooks) { trimHooks.toList() }
        hooks.forEach { try { it() } catch (_: Throwable) {} }
    }
}
