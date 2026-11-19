package cloud.depup.office

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
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
 * - Micro-motion PEGAS ([SpringInterpolator]): tekan-lepas memantul seperti jeli ([springPress]), ikon & lencana
 *   muncul memantul ([boing]), isi tab/lembar masuk bertahap dalam gelombang singkat ([cascadeIn]), lembar & dialog
 *   mendarat dengan pantulan lembut. Pegas selalu berakhir TEPAT di nilai tujuan.
 * - Pil penanda / lensa tab ([PillIndicator]) digerakkan pegas fisika sungguhan — mengikuti kecepatan & jari.
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

    /** Pegas memantul (lepas tekan, ikon muncul): pantulan ±12%, diam dalam [SPRING_MS]. */
    val SPRING = SpringInterpolator(0.52f, 10.5f)
    /** Pegas lembut (isi masuk, lembar, notifikasi): pantulan ±2%, terasa "mendarat". */
    val SPRING_SOFT = SpringInterpolator(0.78f, 7.2f)
    const val SPRING_MS = 480L
    const val SPRING_SOFT_MS = 560L
    /** Jeda antar-anak pada gelombang masuk ([cascadeIn]) & batas anak yang diberi jeda. */
    const val CASCADE_STEP = 28L
    const val CASCADE_MAX = 8

    /** false saat render ulang yang tidak perlu dianimasikan (mis. kembali dari layar lain). */
    @Volatile var quiet = false

    fun on(): Boolean = !quiet && ValueAnimator.areAnimatorsEnabled()

    /** Nomor gerak terakhir tiap tampilan: pengaman akhir gerak lama tidak memotong gerak yang lebih baru. */
    internal val gen = WeakHashMap<View, Int>()
    internal fun next(v: View): Int { val n = (gen[v] ?: 0) + 1; gen[v] = n; return n }
}

/**
 * Kurva pegas teredam untuk animator berdurasi: x(t) = 1 − e^(−ζωt)·(cos ω_d t + ζω/ω_d · sin ω_d t), t ∈ [0, 1].
 * ζ < 1 → memantul lembut lalu diam; nilai 1 tepat di akhir (tidak pernah "melompat" di frame terakhir).
 * [omega] dipilih sehingga ζ·ω ≥ ~5,5 → simpangan sudah < 0,5% sebelum durasi habis.
 */
class SpringInterpolator(private val zeta: Float, private val omega: Float) : android.view.animation.Interpolator {
    private val wd = omega * kotlin.math.sqrt(1f - zeta * zeta)
    private val end = raw(1f)
    private fun raw(t: Float): Float {
        val e = kotlin.math.exp(-zeta * omega * t)
        return 1f - e * (kotlin.math.cos(wd * t) + zeta * omega / wd * kotlin.math.sin(wd * t))
    }
    override fun getInterpolation(t: Float): Float = if (t >= 1f) 1f else raw(t) / end
}

/**
 * Tekan-lepas seperti jeli: turun cepat ke [to] saat ditekan, dilepas memantul kembali ke 1 ([Motion.SPRING]).
 * Lewat StateListAnimator (status "ditekan") — TIDAK memasang pendengar sentuh, jadi aman dipakai bersama
 * ketukan, seret, tekan lama & ripple apa pun.
 */
fun View.springPress(to: Float = 0.97f) {
    val down = android.animation.AnimatorSet().apply {
        playTogether(android.animation.ObjectAnimator.ofFloat(this@springPress, View.SCALE_X, to),
            android.animation.ObjectAnimator.ofFloat(this@springPress, View.SCALE_Y, to))
        duration = Motion.PRESS_DOWN; interpolator = Motion.STANDARD
    }
    val up = android.animation.AnimatorSet().apply {
        playTogether(android.animation.ObjectAnimator.ofFloat(this@springPress, View.SCALE_X, 1f),
            android.animation.ObjectAnimator.ofFloat(this@springPress, View.SCALE_Y, 1f))
        duration = Motion.SPRING_MS; interpolator = Motion.SPRING
    }
    stateListAnimator = android.animation.StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), down)
        addState(intArrayOf(), up)
    }
}

/**
 * Tampilkan/sembunyikan dengan membuka-melipat seperti akordeon: tinggi tumbuh/menyusut halus, isi menebal sesudah
 * ada ruang (dan memudar sebelum dilipat). Belum tampil di layar / animasi mati → langsung. Selalu berakhir di
 * VISIBLE + tinggi asli, atau GONE.
 */
fun View.showIf(show: Boolean) {
    val isShown0 = visibility == View.VISIBLE
    val running = getTag(R.id.reveal_anim) as? ValueAnimator
    if (show == isShown0 && running == null) return
    running?.cancel()
    val p = parent as? View
    if (!Motion.on() || !isAttachedToWindow || p == null || !p.isShown || p.width == 0) {
        visibility = if (show) View.VISIBLE else View.GONE; alpha = 1f; return
    }
    val lp = layoutParams ?: run { visibility = if (show) View.VISIBLE else View.GONE; return }
    val keepH = (getTag(R.id.reveal_h) as? Int) ?: lp.height
    setTag(R.id.reveal_h, keepH)
    val avail = p.width - p.paddingLeft - p.paddingRight - ((lp as? ViewGroup.MarginLayoutParams)?.let { it.leftMargin + it.rightMargin } ?: 0)
    measure(View.MeasureSpec.makeMeasureSpec(maxOf(0, avail), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val full = measuredHeight
    val from = if (isShown0) height else 0
    val to = if (show) full else 0
    visibility = View.VISIBLE
    noLayer()
    val a0 = alpha
    val a = ValueAnimator.ofFloat(0f, 1f)
    a.duration = if (show) Motion.SLOW else Motion.MED
    a.interpolator = Motion.STANDARD
    a.addUpdateListener {
        val f = it.animatedValue as Float
        lp.height = (from + (to - from) * f).toInt().coerceAtLeast(0)
        alpha = if (show) (a0 + (1f - a0) * ((f - 0.3f) / 0.7f).coerceIn(0f, 1f)) else a0 * (1f - (f / 0.6f).coerceIn(0f, 1f))
        layoutParams = lp
        glassAncestor()?.invalidate()
    }
    var cancelled = false
    a.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationCancel(animation: Animator) { cancelled = true }
        override fun onAnimationEnd(animation: Animator) {
            setTag(R.id.reveal_anim, null)
            if (cancelled) return              // animasi baru mengambil alih dari titik ini
            lp.height = keepH; layoutParams = lp
            setTag(R.id.reveal_h, null)
            alpha = 1f
            visibility = if (show) View.VISIBLE else View.GONE
        }
    })
    setTag(R.id.reveal_anim, a)
    a.start()
}

/**
 * Geleng "salah" ala iOS: bergoyang mendatar makin lemah lalu diam tepat di tempat (PIN salah, isian tidak sah).
 * Ikut getar penolakan bila tersedia.
 */
fun View.shake(ampDp: Int = 10) {
    performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.REJECT
        else android.view.HapticFeedbackConstants.LONG_PRESS)
    if (!Motion.on()) return
    val amp = dp(ampDp).toFloat()
    (getTag(R.id.shake_anim) as? ValueAnimator)?.cancel()
    val a = ValueAnimator.ofFloat(0f, 1f)
    a.duration = 460
    a.interpolator = android.view.animation.LinearInterpolator()
    a.addUpdateListener {
        val t = it.animatedValue as Float
        translationX = amp * kotlin.math.sin(t * Math.PI * 8).toFloat() * (1f - t) * (1f - t)
    }
    a.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { translationX = 0f } })
    setTag(R.id.shake_anim, a)
    a.start()
}

/** Muncul memantul dari [from] ke ukuran asli (ikon, lencana, tanda sukses). */
fun View.boing(from: Float = 0.82f, delay: Long = 0L) {
    if (!Motion.on()) return
    animate().cancel()
    scaleX = from; scaleY = from
    animate().scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING)
        .setUpdateListener(null).withEndAction { scaleX = 1f; scaleY = 1f }.start()
}

/**
 * Gelombang masuk: anak-anak wadah ini meluncur ke tempatnya satu per satu (jeda [Motion.CASCADE_STEP], pegas
 * lembut). [fade] = ikut menebal — JANGAN di dalam wadah kaca (blur waktu-nyata); di sana cukup posisi.
 * Dengan pengaman: setiap anak selalu berakhir di posisi & transparansi aslinya.
 */
fun ViewGroup.cascadeIn(dyDp: Int = 16, dxDp: Int = 0, fade: Boolean = false, delay: Long = 0L, skip: Int = 0) {
    if (!Motion.on()) return
    var k = 0
    for (i in skip until childCount) {
        val c = getChildAt(i)
        if (c.visibility != View.VISIBLE) continue
        val d = delay + minOf(k, Motion.CASCADE_MAX) * Motion.CASCADE_STEP
        k++
        val n = Motion.next(c)
        c.animate().cancel()
        val a0 = if (fade) c.alpha else -1f            // transparansi asli (mis. tombol nonaktif) dipertahankan
        val done = {
            if (Motion.gen[c] == n) {
                c.animate().cancel(); c.translationX = 0f; c.translationY = 0f
                if (a0 >= 0f) c.alpha = a0
                c.invalidate(); c.glassAncestor()?.invalidate()   // kaca di atasnya ikut memperbarui latar
            }
        }
        c.translationX = dp(dxDp).toFloat(); c.translationY = dp(dyDp).toFloat()
        val anim = c.animate().translationX(0f).translationY(0f)
        if (a0 >= 0f) { c.noLayer(); c.alpha = 0f; anim.alpha(a0) }
        anim.setStartDelay(d).setDuration(Motion.SPRING_SOFT_MS).setInterpolator(Motion.SPRING_SOFT).setUpdateListener(null)
            .withEndAction(done).start()
        c.postDelayed(done, d + Motion.SPRING_SOFT_MS + 150L)
    }
}

/**
 * Baris pergi dari daftar: meluncur ke kiri sambil memudar, lalu tingginya (dan jarak atasnya) menyusut sehingga
 * baris di bawahnya naik mulus mengisi tempatnya — baru setelah itu [then] (hapus data / bangun ulang daftar).
 */
fun View.collapseOut(then: () -> Unit) {
    if (!Motion.on() || !isAttachedToWindow || height == 0) { then(); return }
    isEnabled = false
    val h0 = height
    animate().cancel()
    noLayer()
    var done = false
    val finish = { if (!done) { done = true; then() } }
    animate().translationX(-width * 0.35f).alpha(0f).setStartDelay(0).setDuration(Motion.FAST + 70L).setInterpolator(Motion.EXIT)
        .setUpdateListener(null).withEndAction {
            val lp = layoutParams
            if (lp == null) { finish(); return@withEndAction }
            val m0 = (lp as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            val a = ValueAnimator.ofFloat(1f, 0f)
            a.duration = Motion.MED
            a.interpolator = Motion.STANDARD
            a.addUpdateListener {
                val f = it.animatedValue as Float
                lp.height = (h0 * f).roundToInt()
                (lp as? ViewGroup.MarginLayoutParams)?.topMargin = (m0 * f).roundToInt()
                requestLayout()
                glassAncestor()?.invalidate()
            }
            a.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) = finish() })
            a.start()
        }.start()
    postDelayed({ finish() }, Motion.FAST + 70L + Motion.MED + 300L)   // pengaman
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
    // Isi lama dari pergantian SEBELUMNYA yang masih memudar dibuang dulu — ganti cepat beruntun tidak pernah
    // menumpuk "hantu" (dulu: animasi terpotong → withEndAction tak jalan → isi lama tertinggal di lapisan atas).
    clearGhosts()
    if (!Motion.on() || !isAttachedToWindow || width == 0 || !isShown) { rebuild(); return }
    val olds = ArrayList<View>(childCount)
    for (i in 0 until childCount) olds.add(getChildAt(i))
    rebuild()
    val ov = overlay
    val ghosts = ArrayList<View>()
    for (v in olds) {
        if (v.parent != null) continue            // dipakai ulang oleh rebuild
        v.animate().cancel()
        try { ov.add(v) } catch (_: Throwable) { continue }
        v.noLayer()
        ghosts.add(v)
    }
    if (ghosts.isNotEmpty()) {
        // satu animator milik wadah (bukan ViewPropertyAnimator per anak yang bisa dibatalkan pihak lain)
        val a = ValueAnimator.ofFloat(1f, 0f)
        a.duration = Motion.FAST
        a.interpolator = Motion.STANDARD
        val a0 = FloatArray(ghosts.size) { ghosts[it].alpha }
        a.addUpdateListener { val f = it.animatedValue as Float; for (k in ghosts.indices) ghosts[k].alpha = a0[k] * f }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { clearGhosts() }
            override fun onAnimationCancel(animation: Animator) { clearGhosts() }
        })
        setTag(R.id.swap_ghosts, ghosts)
        setTag(R.id.swap_anim, a)
        a.start()
        postDelayed({ if (getTag(R.id.swap_ghosts) === ghosts) clearGhosts() }, Motion.FAST + 120L)   // pengaman mutlak
    }
    // isi baru masuk dalam gelombang singkat berpegas (hanya posisi: aman di wadah kaca, tidak pernah kosong)
    cascadeIn(dyDp = dyDp * 2, dxDp = dxDp * 2)
}

/** Buang semua sisa isi lama [swapContent] dari lapisan atas wadah ini, seketika. */
fun ViewGroup.clearGhosts() {
    @Suppress("UNCHECKED_CAST")
    val g = getTag(R.id.swap_ghosts) as? ArrayList<View> ?: return
    setTag(R.id.swap_ghosts, null)
    (getTag(R.id.swap_anim) as? ValueAnimator)?.let { setTag(R.id.swap_anim, null); it.removeAllListeners(); it.cancel() }
    for (v in g) { try { overlay.remove(v) } catch (_: Throwable) {}; v.alpha = 1f }
    invalidate()
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

/** Muncul dengan sedikit membesar ke ukuran asli, mendarat dengan pantulan lembut. */
fun View.pop(from: Float = 0.92f, delay: Long = 0L) {
    if (!Motion.on()) return
    animate().cancel()
    scaleX = from; scaleY = from
    animate().scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(Motion.SPRING_SOFT_MS).setInterpolator(Motion.SPRING_SOFT)
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

/**
 * Ganti teks dengan gulir halus: teks lama naik & memudar cepat, teks baru masuk dari bawah dengan pegas.
 * Teks sama → tidak ada animasi. Selalu berakhir di posisi & transparansi asli.
 */
fun TextView.morphText(t: CharSequence) {
    if (text.toString() == t.toString()) return
    if (!Motion.on() || !isAttachedToWindow || !isShown) { text = t; return }
    val n = Motion.next(this)
    val d = dp(6).toFloat()
    animate().cancel()
    animate().alpha(0f).translationY(-d).setStartDelay(0).setDuration(Motion.FAST - 40).setInterpolator(Motion.EXIT)
        .setUpdateListener(null).withEndAction {
            if (Motion.gen[this] != n) return@withEndAction
            text = t
            translationY = d
            animate().alpha(1f).translationY(0f).setDuration(Motion.SPRING_SOFT_MS).setInterpolator(Motion.SPRING_SOFT)
                .withEndAction { alpha = 1f; translationY = 0f }.start()
        }.start()
    postDelayed({ if (Motion.gen[this] == n && text.toString() != t.toString()) { text = t; alpha = 1f; translationY = 0f } },
        Motion.FAST + Motion.SPRING_SOFT_MS + 150L)
}

/** Angka bergulir mulus dari nilai yang sedang tampil ke [to] (mis. "100%" → "150%"). Teks akhir selalu tepat. */
fun TextView.rollTo(to: Int, suffix: String = "") {
    val from = text.toString().removeSuffix(suffix).trim().toIntOrNull()
    (getTag(R.id.roll_anim) as? ValueAnimator)?.cancel()
    if (from == null || from == to || !Motion.on()) { text = "$to$suffix"; return }
    val a = ValueAnimator.ofInt(from, to)
    a.duration = Motion.MED + 60L
    a.interpolator = Motion.STANDARD
    a.addUpdateListener { text = "${it.animatedValue as Int}$suffix" }
    a.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { text = "$to$suffix" } })
    setTag(R.id.roll_anim, a)
    a.start()
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
