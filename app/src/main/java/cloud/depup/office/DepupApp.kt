package cloud.depup.office

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 35/36 memaksa edge-to-edge. Semua Activity di app ini membangun UI sendiri,
 * jadi inset (status bar, nav bar, cutout, keyboard) diterapkan sekali di sini sebagai
 * padding root content supaya UI tidak tertimpa system bar.
 */
class DepupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Berkas setelan yang dibaca saat aplikasi mulai: getSharedPreferences langsung memulai pemuatannya di thread
        // latar, jadi ketiganya dibaca dari disk BERSAMAAN — pembacaan pertama di bawah tidak lagi menunggu satu per satu.
        for (n in PREFS_AT_START) getSharedPreferences(n, MODE_PRIVATE)
        Perf.init(this)
        Fonts.warm(this)
        Glass.init(this)
        LiquidGlass.init(this)
        applyNightMode(Theme.mode(this))
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            // Hitung token warna (terang/gelap) SEBELUM layar membangun tampilannya.
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                Theme.apply(activity)
            }
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                val content = activity.findViewById<View>(android.R.id.content) ?: return
                ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                    val i = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout() or
                            WindowInsetsCompat.Type.ime()
                    )
                    v.setPadding(i.left, i.top, i.right, i.bottom)
                    WindowInsetsCompat.CONSUMED
                }
                ViewCompat.requestApplyInsets(content)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) { C.dark = Theme.isDark(a) }
            override fun onActivityResumed(a: Activity) {
                GlassGuard.start(a)
                GlassLight.start(a)   // arah kilau kaca mengikuti kemiringan HP selama layar di depan
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
            override fun onActivityPaused(a: Activity) { GlassGuard.stop(a); GlassLight.stop(a) }
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
