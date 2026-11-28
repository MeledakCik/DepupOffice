package cloud.depup.office

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Layar berkerangka kaca digambar sampai tepi layar: bilah status & navigasi sistem dibuat bening di atas isinya. */
object Edge {
    private const val TAG = 0x7f0e_d9e1.toInt()

    /** [GlassScaffold] yang memenuhi layar ini (anak atau cucu wadah isi), atau null. */
    fun scaffoldOf(content: View): GlassScaffold? {
        val g = content as? android.view.ViewGroup ?: return null
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is GlassScaffold) return c
            if (c is android.widget.FrameLayout) for (j in 0 until c.childCount) (c.getChildAt(j) as? GlassScaffold)?.let { return it }
        }
        return null
    }

    fun on(a: Activity): Boolean = a.window?.decorView?.getTag(TAG) == true

    @Suppress("DEPRECATION")
    fun mark(a: Activity, on: Boolean) {
        val w = a.window ?: return
        val was = on(a)
        w.decorView.setTag(TAG, on)
        if (on) {
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        } else if (was) Theme.apply(a)
    }
}

/** Layar yang menggambar sampai tepi layar (di belakang bilah status & navigasi) dan mengatur jaraknya sendiri. */
interface FullBleed

/**
 * targetSdk 35/36 memaksa edge-to-edge. Semua Activity di app ini membangun UI sendiri,
 * jadi inset (status bar, nav bar, cutout, keyboard) diterapkan sekali di sini sebagai
 * padding root content supaya UI tidak tertimpa system bar (kecuali layar [FullBleed]).
 */
class DepupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Berkas setelan yang dibaca saat aplikasi mulai: getSharedPreferences langsung memulai pemuatannya di thread
        // latar, jadi ketiganya dibaca dari disk BERSAMAAN — pembacaan pertama di bawah tidak lagi menunggu satu per satu.
        for (n in PREFS_AT_START) getSharedPreferences(n, MODE_PRIVATE)
        Integrity.init(this)
        // Salinan terdekripsi yang tertinggal (aplikasi ditutup paksa saat membaca dokumen terkunci) dihapus di latar.
        val started = System.currentTimeMillis()
        Thread {
            try {
                // hanya sisa sesi sebelumnya (lebih tua dari proses ini) — salinan yang sedang dibuka tidak tersentuh
                java.io.File(cacheDir, "unlocked").listFiles()?.filter { it.lastModified() < started }?.forEach { it.deleteRecursively() }
                cacheDir.listFiles()?.filter { it.name.startsWith("decrypted_") && it.lastModified() < started }?.forEach { it.delete() }
            } catch (_: Throwable) {}
        }.start()
        Perf.init(this)
        Fonts.warm(this)
        Glass.init(this)
        LiquidGlass.init(this)
        applyNightMode(Theme.mode(this))
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            // Hitung token warna (terang/gelap) SEBELUM layar membangun tampilannya.
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Android 10–14 juga digambar sampai tepi (Android 15+ sudah memaksanya); jarak diatur listener di bawah.
                activity.window?.let { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false) }
                Theme.apply(activity)
                ThemeFade.built(activity, C.dark)
            }
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                val content = activity.findViewById<View>(android.R.id.content) ?: return
                // Layar biasa: isi diberi jarak dari bilah sistem. Layar [FullBleed] (kamera) menggambar sampai tepi
                // dan mengatur jaraknya sendiri.
                if (activity !is FullBleed) {
                    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                        val sc = Edge.scaffoldOf(v)
                        if (sc != null) {
                            // Layar berkerangka kaca: isi digambar sampai tepi atas & bawah layar (di balik bilah status
                            // dan navigasi); bilah kaca sendiri yang memberi jarak. Keyboard tetap mendorong isi ke atas.
                            val imeUp = ime.bottom > bars.bottom
                            v.setPadding(bars.left, 0, bars.right, if (imeUp) ime.bottom else 0)
                            sc.setSystemInsets(bars.top, if (imeUp) 0 else bars.bottom)
                            Edge.mark(activity, true)
                        } else {
                            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                            Edge.mark(activity, false)
                        }
                        WindowInsetsCompat.CONSUMED
                    }
                    ViewCompat.requestApplyInsets(content)
                }
                // Tema HP berganti (uiMode ditangani sendiri, lihat manifest): bila terang/gelap berubah, layar dibangun
                // ulang dengan transisi; layar yang baru dibangun karena ganti tema membuka potret layar lamanya.
                (activity as? androidx.activity.ComponentActivity)?.addOnConfigurationChangedListener { ThemeFade.onConfig(activity) }
                ThemeFade.play(activity)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) { C.dark = Theme.isDark(a) }
            override fun onActivityResumed(a: Activity) {
                GlassGuard.start(a)
                GlassLight.start(a)   // arah kilau kaca mengikuti kemiringan HP selama layar di depan
                GlassPerf.start(a)    // kaca diringankan otomatis bila GPU kewalahan
                if (Glass.autoDisabled) {
                    Glass.autoDisabled = false
                    a.window?.decorView?.postDelayed({
                        if (!a.isFinishing && !a.isDestroyed)
                            a.toast("Efek kaca dimatikan otomatis karena layar sempat gagal tampil. Bisa dinyalakan lagi di Saya → Tampilan.")
                    }, 900L)
                } else if (LiquidGlass.autoDisabled) {
                    LiquidGlass.autoDisabled = false
                    a.window?.decorView?.postDelayed({
                        if (!a.isFinishing && !a.isDestroyed)
                            a.toast("Kaca cair dimatikan otomatis karena layar sempat gagal tampil; blur biasa tetap menyala. Bisa dinyalakan lagi di Saya → Tampilan.")
                    }, 900L)
                }
            }
            override fun onActivityPaused(a: Activity) { GlassGuard.stop(a); GlassLight.stop(a); GlassPerf.stop() }
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // RUNNING_LOW (10) ke atas, atau aplikasi di latar: lepaskan cache bitmap.
        if (level >= TRIM_MEMORY_RUNNING_LOW) Perf.trim()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Perf.trim()
    }

    companion object {
        private val PREFS_AT_START = arrayOf("ui", "glass", "depup")

        /** Terapkan pilihan tema (ikuti sistem / terang / gelap) ke dialog & sumber daya sistem. */
        fun applyNightMode(mode: Int) {
            AppCompatDelegate.setDefaultNightMode(when (mode) {
                Theme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Theme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            })
        }
    }
}
