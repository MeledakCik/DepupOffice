package cloud.depup.office

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Sistem gerak aplikasi — satu bahasa gerak yang rapi dan tenang.
 *
 * - Tiga kurva saja:
 *   [STANDARD] untuk semua yang berubah di tempat (penanda tab, isi yang bergeser, label membuka, memudar);
 *   [ENTER] untuk yang masuk dari luar layar (lembar bawah, kapsul, notifikasi);
 *   [EXIT] untuk yang pergi.
 * - Tiga durasi saja: [FAST] 150 ms (pergi / memudar keluar), [MED] 250 ms (memudar masuk, isi berganti),
 *   [SLOW] 350 ms (pindah tab, penanda, lembar, kapsul). Umpan balik tekan: [PRESS_DOWN] turun, [PRESS_UP] naik.
 * - Tanpa pantulan (overshoot) dan tanpa gelombang bertahap: elemen yang bergerak bersama mulai & berhenti
 *   bersamaan, ke satu arah, dengan jarak geser yang sama ([SHIFT_DP]).
 *
 * Aturan penting di dalam wadah kaca: animasi transparansi selalu TANPA lapisan offscreen ([noLayer]) —
 * lebih ringan, dan aman untuk blur waktu-nyata.
 *
 * Semua animasi otomatis mati bila "Hapus animasi" / skala animasi 0 diaktifkan di HP (aksesibilitas).
 */
object Motion {
    /** Berubah di tempat: mulai lembut, mendarat panjang & halus. */
    val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** Masuk dari luar layar: melesat di awal, mendarat lembut. */
    val ENTER = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** Keluar: mulai pelan lalu cepat pergi. */
    val EXIT = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    const val FAST = 150L
    const val MED = 250L
    const val SLOW = 350L
    const val PRESS_DOWN = 100L
    const val PRESS_UP = 200L
    /** Jarak geser standar untuk isi yang masuk/berganti (dp). */
    const val SHIFT_DP = 10

    /** false saat render ulang yang tidak perlu dianimasikan (mis. kembali dari layar lain). */
    @Volatile var quiet = false

    fun on(): Boolean = !quiet && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())

    /** Nomor gerak terakhir tiap tampilan: pengaman akhir gerak lama tidak memotong gerak yang lebih baru. */
    internal val gen = WeakHashMap<View, Int>()
    internal fun next(v: View): Int { val n = (gen[v] ?: 0) + 1; gen[v] = n; return n }
}

/** Transparansi tanpa lapisan offscreen: alfa dikalikan langsung ke tiap gambar anak (ringan & aman di wadah kaca). */
fun View.noLayer(): View { forceHasOverlappingRendering(false); return this }

/** Jalankan [block] tepat sebelum gambar berikutnya (setelah layout) — mis. memulihkan posisi gulir tanpa lompatan. */
fun View.beforeDraw(block: () -> Unit) {
    viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
            block()
            return true
        }
    })
}

/**
 * Ganti isi wadah ini TANPA kedip: isi lama dipindah ke lapisan atas (overlay) lalu memudar cepat, sementara isi
 * baru sudah tampil penuh di bawahnya dan bergeser halus ke tempatnya — SEMUA anak bersama-sama (satu blok,
 * jarak & waktu sama), jadi tidak ada yang tertinggal atau bergelombang. Tidak pernah ada satu frame pun kosong.
 */
fun ViewGroup.swapContent(dxDp: Int = 0, dyDp: Int = 6, rebuild: () -> Unit) {
    if (!Motion.on() || !isAttachedToWindow || width == 0 || !isShown) { rebuild(); return }
    val olds = ArrayList<View>(childCount)
    for (i in 0 until childCount) olds.add(getChildAt(i))
    rebuild()
    val ov = overlay
    for (v in olds) {
        if (v.parent != null) continue            // dipakai ulang oleh rebuild
        v.animate().cancel()
        try { ov.add(v) } catch (_: Throwable) { continue }
        v.noLayer()
        v.animate().alpha(0f).setStartDelay(0).setDuration(Motion.FAST).setInterpolator(Motion.STANDARD).setUpdateListener(null)
            .withEndAction { ov.remove(v) }.start()
    }
    val sx = dp(dxDp).toFloat()
    val sy = dp(dyDp).toFloat()
    for (i in 0 until childCount) {
        val c = getChildAt(i)
        if (c.visibility != View.VISIBLE) continue
        c.animate().cancel()
        c.translationX = sx; c.translationY = sy
        c.animate().translationX(0f).translationY(0f).setStartDelay(0).setDuration(Motion.MED).setInterpolator(Motion.STANDARD)
            .setUpdateListener(null).withEndAction { c.translationX = 0f; c.translationY = 0f }.start()
    }
}

/**
 * Bergeser masuk ke posisi asli dari ([dxDp], [dyDp]) — HANYA posisi (tanpa transparansi): isi utuh sejak frame
 * pertama, jadi tidak pernah kosong atau berkedip. Dengan pengaman: selalu berakhir tepat di posisi asli.
 */
fun View.shiftIn(dxDp: Int = 0, dyDp: Int = Motion.SHIFT_DP, delay: Long = 0L, duration: Long = Motion.SLOW) {
    if (!Motion.on()) return
    val n = Motion.next(this)
    animate().cancel()
    translationX = dp(dxDp).toFloat()
    translationY = dp(dyDp).toFloat()
    animate().translationX(0f).translationY(0f)
        .setStartDelay(delay).setDuration(duration).setInterpolator(Motion.STANDARD)
        .setUpdateListener(null).withEndAction { if (Motion.gen[this] == n) settle() }
        .start()
    postDelayed({ if (Motion.gen[this] == n) settle() }, delay + duration + 150L)
}

/** Meluncur ke tempatnya tanpa menghilang dulu (nama lama, kini sama dengan [shiftIn]). */
fun View.glide(delay: Long = 0L, dyDp: Int = Motion.SHIFT_DP, dxDp: Int = 0) = shiftIn(dxDp, dyDp, delay)

/** Muncul dengan sedikit membesar ke ukuran asli — tanpa pantulan. */
fun View.pop(from: Float = 0.92f, delay: Long = 0L) {
    if (!Motion.on()) return
    animate().cancel()
    scaleX = from; scaleY = from
    animate().scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(Motion.MED).setInterpolator(Motion.STANDARD)
        .setUpdateListener(null).withEndAction { scaleX = 1f; scaleY = 1f }.start()
}

/** Paksa ke keadaan akhir (terlihat penuh, posisi asli) dan gambar ulang wadah kaca di atasnya. */
fun View.settle() {
    if (alpha != 1f || translationX != 0f || translationY != 0f) {
        animate().cancel()
        alpha = 1f; translationX = 0f; translationY = 0f
    }
    invalidate()
    glassAncestor()?.invalidate()
}

private fun View.glassAncestor(): View? {
    var p = parent
    while (p is View) { if (p is GlassContent) return p; p = p.parent }
    return null
}

/** Tumbuh mendatar dari tepi kiri (bilah kemajuan). Dengan pengaman: selalu berakhir di ukuran penuh. */
fun View.growX(delay: Long = 120L, duration: Long = 500L) {
    if (!Motion.on()) return
    pivotX = 0f; scaleX = 0f
    animate().scaleX(1f).setStartDelay(delay).setDuration(duration).setInterpolator(Motion.STANDARD)
        .withEndAction { scaleX = 1f }.start()
    postDelayed({ if (scaleX != 1f) { animate().cancel(); scaleX = 1f; invalidate(); glassAncestor()?.invalidate() } }, delay + duration + 150L)
}

/**
 * Tersingkap dari kiri ke kanan sebagai SATU bagian (bilah bertumpuk): semua segmen terbuka bersamaan, tidak
 * bergantian. Dengan pengaman: selalu berakhir terlihat penuh.
 */
fun View.wipeIn(delay: Long = 120L, duration: Long = 500L) {
    if (!Motion.on()) return
    clipBounds = Rect(0, 0, 0, 0)
    val a = ValueAnimator.ofFloat(0f, 1f)
    a.startDelay = delay
    a.duration = duration
    a.interpolator = Motion.STANDARD
    a.addUpdateListener { if (width > 0) clipBounds = Rect(0, 0, (width * (it.animatedValue as Float)).roundToInt(), height) }
    a.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) { clipBounds = null }
    })
    a.start()
    postDelayed({ if (clipBounds != null) { a.cancel(); clipBounds = null; invalidate() } }, delay + duration + 150L)
}

/** Angka menghitung naik ke [target] (ubin statistik). Teks akhir selalu tepat walau animasi mati. */
fun TextView.countUp(target: Int, delay: Long = 120L) {
    text = target.toString()
    if (!Motion.on() || target <= 0) return
    val a = ValueAnimator.ofInt(0, target)
    a.startDelay = delay
    a.duration = 600L
    a.interpolator = Motion.STANDARD
    a.addUpdateListener { text = (it.animatedValue as Int).toString() }
    a.start()
}
