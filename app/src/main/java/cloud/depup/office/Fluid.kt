package cloud.depup.office

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Campur dua warna ARGB ([f] 0..1). */
fun mixColor(a: Int, b: Int, f: Float): Int {
    if (f <= 0f) return a
    if (f >= 1f) return b
    val g = 1f - f
    return Color.argb(
        (Color.alpha(a) * g + Color.alpha(b) * f).roundToInt(),
        (Color.red(a) * g + Color.red(b) * f).roundToInt(),
        (Color.green(a) * g + Color.green(b) * f).roundToInt(),
        (Color.blue(a) * g + Color.blue(b) * f).roundToInt())
}

/** Bagian rentang mendatar [a, b] yang tertutup [r] (0..1). */
internal fun cover(r: RectF, a: Float, b: Float): Float =
    if (b <= a) 0f else ((min(r.right, b) - max(r.left, a)) / (b - a)).coerceIn(0f, 1f)

/**
 * Pil penanda yang berpindah antar-anak sebuah baris seperti tetes air: digerakkan PEGAS (bukan durasi tetap), jadi
 * geraknya mengikuti kecepatan, bisa disela/dibelokkan kapan saja tanpa patah, dan mendarat dengan pantulan kecil
 * yang lembut (±5%). Saat melaju, pil memanjang searah gerak & memipih sebanding kecepatannya, lalu kembali bulat
 * saat melambat. Selalu mengikuti ukuran & skala anak tujuan (ikut melebar saat label tab terbuka, ikut mengecil
 * saat ditekan). Dipakai bilah tab bawah, tab pita editor & kontrol segmen.
 *
 * [lens] = true (bilah tab bawah): saat bergerak/diseret pil "terangkat" menjadi lensa KACA bening — isinya
 * memudar dari warna solid ke [glass], sedikit membesar, berkilau, bertepi terang, dan (bila setelan Dispersi menyala)
 * bertepi pelangi tipis. Saat melambat & diam, lensa kembali menjadi kapsul solid. [lift] dipakai pemanggil untuk
 * memperbesar & mewarnai ulang ikon yang sedang dilewati lensa.
 */
class PillIndicator(private val host: View) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Jarak minimum pil dari tepi kiri/kanan [host] (px): pil tidak pernah terpotong tepi. */
    var edge = 0f
    var target: View? = null
        private set
    /** Dipanggil tiap frame animasi — mis. mewarnai ulang ikon/teks yang sedang dilewati pil. */
    var onFrame: (() -> Unit)? = null
    /** Tampilan lensa kaca saat bergerak (lihat kelas). */
    var lens = false
    /** Warna isi lensa saat terangkat penuh (kaca bening, biasanya semi-transparan). */
    var glass = 0x14000000
    /** 0..1: seberapa terangkat lensa saat ini (0 = diam di tempat). */
    val lift: Float get() = if (lens) l.coerceIn(0f, 1f) else 0f
    /**
     * Mode "meletup" (bilah tab bawah, desain Figma "Switch"): tiap pindah tab lensa terangkat penuh, membesar
     * terutama ke ATAS-BAWAH (±1,85× tinggi pil — keluar dari kapsul navigasi) lalu mengendap kembali menjadi pil
     * solid. Bentuknya tidak dibatasi tinggi [host]; pemanggil menggambar lensa di lapisan yang tidak terpotong.
     */
    var pop = false

    private val dp = host.resources.displayMetrics.density
    private val to = RectF()
    private val now = RectF()
    private val tmp = RectF()

    // Keadaan pegas (koordinat host, px & px/detik). x = NaN: diam tepat di tujuan (tidak ada simulasi).
    private var x = Float.NaN
    private var vx = 0f
    private var w = 0f
    private var vw = 0f
    private var l = 0f
    private var vl = 0f
    /** Posisi jari saat lensa diseret (NaN = tidak diseret). */
    private var dragX = Float.NaN
    private var running = false
    private var lastNs = 0L
    private val frame = Choreographer.FrameCallback { step(it) }

    private var pulse = 0f
    private var beat: ValueAnimator? = null

    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun bounds(v: View, out: RectF) {
        val cx = v.left + v.width / 2f + v.translationX
        val cy = v.top + v.height / 2f + v.translationY
        val hw = v.width * v.scaleX / 2f
        val hh = v.height * v.scaleY / 2f
        out.set(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    /** 0..1: kecepatan mendatar relatif (untuk regangan & dispersi). */
    private fun speed(): Float = min(abs(vx) / (1600f * dp), 1f)

    /** Bentuk pil saat ini (koordinat [host]); null bila tujuannya belum terukur. [lifted] false = tanpa pembesaran lensa. */
    fun rect(lifted: Boolean = true): RectF? {
        val v = target ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        bounds(v, to)
        now.set(to)
        val h = to.height()
        if (!x.isNaN()) {
            now.left = x - w / 2f; now.right = x + w / 2f
            // regangan cair: memanjang searah gerak & memipih sebanding kecepatan (mode meletup: lensa tetap bulat)
            val s = speed() * (if (pop) 1f - 0.35f * lift else 1f)   // meletup: tetap meregang seperti gel
            val ext = s * h * 0.5f
            now.left -= ext / 2f; now.right += ext / 2f
            val sq = s * h * 0.12f
            now.top += sq / 2f; now.bottom -= sq / 2f
        }
        // meletup: pembesaran boleh sedikit melewati 1 (pantulan pegas angkat terlihat sebagai "denyut" gel)
        val lf = if (!lifted || !lens) 0f else if (pop) l.coerceIn(0f, 1.25f) else lift
        if (lf > 0f) {
            if (pop) {
                // membesar ke atas-bawah (±1,44× tinggi pil, sedikit keluar dari kapsul navigasi), lebar hampir tetap
                now.inset(-h * 0.05f * lf, -h * 0.22f * lf)
            } else { val g = h * 0.11f * lf; now.inset(-g, -g) }   // lensa terangkat sedikit lebih besar
        }
        if (pulse != 0f) {
            // denyut: membesar merata sedikit (bentuk tetap pil yang sama)
            val dx = h * 0.06f * pulse
            now.left -= dx; now.right += dx; now.top -= dx; now.bottom += dx
        }
        if (host.width > 0) {
            if (now.left < edge) now.left = edge
            if (now.right > host.width - edge) now.right = host.width - edge
            if (!pop && now.top < 0f) now.top = 0f
            if (!pop && now.bottom > host.height) now.bottom = host.height.toFloat()
        }
        return now
    }

    fun draw(c: Canvas) { drawFill(c); drawEdges(c, false) }

    /** Isi pil: kapsul solid, memudar ke kaca bening ([glass]) sebanding seberapa terangkat. */
    fun drawFill(c: Canvas) {
        val lf = lift
        if (pop && lf >= 0.01f) {
            // kapsul solid tetap seukuran pil & memudar; kaca yang membesar digambar pemanggil di lapisan atas
            val r = rect(lifted = false) ?: return
            val rad = r.height() / 2f
            fill.color = paint.color
            fill.alpha = (Color.alpha(paint.color) * (1f - lf)).roundToInt().coerceIn(0, 255)
            c.drawRoundRect(r, rad, rad, fill)
            return
        }
        val r = rect() ?: return
        val rad = r.height() / 2f
        if (lf < 0.01f) { c.drawRoundRect(r, rad, rad, paint); return }
        fill.color = mixColor(paint.color, glass, lf)
        c.drawRoundRect(r, rad, rad, fill)
    }

    /**
     * Tepi lensa tiruan, digambar DI ATAS isi & ikon — hanya bila shader kaca cair TIDAK dipakai ([real] = false):
     * kilau lembut di separuh atas & tepi terang tipis. Dengan shader ([real] = true) tidak ada yang digambar di sini:
     * refraksi, kilau, cahaya tepi & dispersi sudah dihitung shader yang sama dengan navigasi. Garis luar gelap & cincin
     * pelangi lukisan dulu menumpuk di atas hasil shader dan membuat kapsul tampak ber-outline — dihapus.
     */
    fun drawEdges(c: Canvas, real: Boolean) {
        val lf = lift
        if (lf < 0.01f || real) return
        val r = rect() ?: return
        val rad = r.height() / 2f
        // kilau kaca tiruan: pantulan lembut di separuh atas
        sheen.shader = LinearGradient(0f, r.top, 0f, r.centerY(),
            Color.argb((64 * lf).roundToInt(), 255, 255, 255), 0, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, sheen)
        // tepi kaca terang tiruan, tipis & lembut (bukan garis luar)
        rim.strokeWidth = 1f * dp
        rim.color = Color.argb((70 * lf).roundToInt(), 255, 255, 255)
        tmp.set(r); tmp.inset(0.5f * dp, 0.5f * dp)
        c.drawRoundRect(tmp, rad, rad, rim)
    }

    /** Pindah ke [v]. Tanpa animasi bila [animate] false / belum ada posisi awal / animasi HP dimatikan. */
    fun moveTo(v: View, animate: Boolean) {
        val old = target
        target = v
        dragX = Float.NaN
        if (!animate || !Motion.on()) { rest(); onFrame?.invoke(); host.invalidate(); return }
        if (x.isNaN()) {
            // mulai dari posisi diam sebelumnya; kecepatan yang sedang berjalan (mis. sisa seretan) dipertahankan
            if (old == null || old.width <= 0) { rest(); onFrame?.invoke(); host.invalidate(); return }
            bounds(old, tmp)
            x = tmp.centerX(); w = tmp.width(); vx = 0f; vw = 0f
        }
        // mode meletup: lensa langsung terdorong naik, juga untuk lompatan ke tab sebelah yang pendek
        if (pop && old !== v) vl = max(vl, 9f)
        kick()
    }

    /** Lensa mengikuti jari di [px] (koordinat host, sudah dibatasi pemanggil). */
    fun drag(px: Float) {
        if (!Motion.on()) return
        if (x.isNaN()) {
            val v = target ?: return
            if (v.width <= 0) return
            bounds(v, tmp)
            x = tmp.centerX(); w = tmp.width()
        }
        dragX = px
        kick()
    }

    /** Jari dilepas: lensa kembali ke tujuan ([moveTo] berikutnya bisa langsung membelokkannya). */
    fun release() {
        dragX = Float.NaN
        if (!x.isNaN()) kick()
    }

    private fun rest() { x = Float.NaN; vx = 0f; vw = 0f; l = 0f; vl = 0f }

    private fun kick() {
        if (running) return
        running = true; lastNs = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun step(ns: Long) {
        val v = target
        if (v == null || x.isNaN() || !host.isAttachedToWindow) { running = false; rest(); return }
        val dt = (if (lastNs == 0L) 1f / 60f else (ns - lastNs) / 1e9f).coerceIn(0f, 1f / 24f)
        lastNs = ns
        bounds(v, to)
        val dragging = !dragX.isNaN()
        val gx = if (dragging) dragX else to.centerX()
        val gw = to.width()
        val kx = if (dragging) K_DRAG else if (pop) K_POP else K
        val z = if (pop) Z_POP else Z
        val cx = 2f * z * sqrt(kx)
        val cw = 2f * z * sqrt(K)
        val kl = if (pop) KL_POP else KL
        val cl = 2f * (if (pop) ZL_POP else ZL) * sqrt(kl)
        // sub-langkah kecil (≤ 4 ms): stabil di layar 60–144 Hz maupun saat frame terlambat
        val n = ceil(dt / 0.004f).toInt().coerceAtLeast(1)
        val h = dt / n
        repeat(n) {
            vx += (kx * (gx - x) - cx * vx) * h; x += vx * h
            vw += (K * (gw - w) - cw * vw) * h; w += vw * h
            // lensa terangkat selama diseret / masih jauh dari tujuan, turun lembut saat mendekat
            val lt = if (dragging) 1f else min(abs(gx - x) / ((if (pop) 6f else 28f) * dp), 1f)
            vl += (kl * (lt - l) - cl * vl) * h; l += vl * h
        }
        val lGoal = if (dragging) 1f else 0f
        val still = abs(gx - x) < 0.4f && abs(vx) < 4f * dp && abs(gw - w) < 0.4f && abs(vw) < 4f * dp &&
            abs(lGoal - l) < 0.004f && abs(vl) < 0.02f
        if (still) {
            running = false
            if (!dragging) rest() else { x = gx; vx = 0f; l = 1f; vl = 0f }
        } else {
            Choreographer.getInstance().postFrameCallback(frame)
        }
        onFrame?.invoke(); host.invalidate()
    }

    /** Denyut tunggal (tab aktif diketuk lagi): membesar sedikit lalu kembali — satu kali, tanpa getar. */
    fun beat() {
        if (!Motion.on()) return
        beat?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = Motion.SLOW
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            val p = it.animatedValue as Float
            pulse = sin(PI.toFloat() * p)     // naik lalu turun mulus, sekali
            onFrame?.invoke(); host.invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { pulse = 0f; host.invalidate() }
        })
        beat = a
        a.start()
    }

    private companion object {
        /** Kekakuan pegas posisi & lebar (ω ≈ 22 rad/s → tiba ± 0,3 s). */
        const val K = 480f
        /** Saat diseret lebih kaku: lensa menempel di jari tapi tetap terasa cair. */
        const val K_DRAG = 900f
        /** Rasio redaman < 1: pantulan kecil ±5% saat mendarat. */
        const val Z = 0.68f
        /** Pegas "terangkat"-nya lensa: lebih lunak & hampir tanpa pantulan. */
        const val KL = 380f
        const val ZL = 0.9f
        /** Mode meletup: naik cepat lalu mengendap dengan pantulan kecil (lensa "sempat membesar"). */
        const val KL_POP = 520f
        const val ZL_POP = 0.42f
        /** Mode meletup: luncuran lebih lentur — melewati tab tujuan ±12% lalu memantul kembali. */
        const val K_POP = 420f
        const val Z_POP = 0.55f
    }
}

/** Umpan balik tekan: mengecil sedikit, dilepas memantul kembali seperti jeli ([Motion.SPRING]). [onFrame] tiap frame. */
@SuppressLint("ClickableViewAccessibility")
internal fun View.squish(to: Float = 0.95f, onFrame: (() -> Unit)? = null) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(to).scaleY(to).setStartDelay(0).setDuration(Motion.PRESS_DOWN)
                .setInterpolator(Motion.STANDARD).setUpdateListener { onFrame?.invoke() }.start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setStartDelay(0)
                .setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).setUpdateListener { onFrame?.invoke() }.start()
        }
        false
    }
}

/**
 * Bilah tab bawah.
 * - Satu pil tinta meluncur antar-tab dengan pegas (lihat [PillIndicator]); selama bergerak pil terangkat menjadi
 *   lensa kaca — Android 13+: shader kaca cair yang sama dengan navigasi ([TabLensFx]), selain itu tiruan kanvas.
 *   Ikon yang dilewatinya berubah warna & membesar mengikuti seberapa tertutup mereka; diam = kapsul solid lagi.
 * - Seret mendatar di atas bilah: lensa mengikuti jari (getar halus tiap melewati tab); saat dilepas, tab di
 *   bawahnya terpilih dan lensa menempel ke sana.
 * - Nama tab terpilih membuka (lebar, jarak, transparansi) dan nama tab lama menutup bersamaan dengan gerak pil;
 *   pegas lebar pil mengikuti lebar tab yang berubah, jadi pil & label tiba bersama.
 * - Tiap tombol mengecil sedikit saat ditekan & kembali mulus; tab aktif yang diketuk lagi membuat pil berdenyut sekali.
 */
@SuppressLint("ViewConstructor")
class TabBar(ctx: Context) : LinearLayout(ctx), GlassScaffold.Minimizable {
    private class Tab(val index: Int, val pill: LinearLayout, val icon: ImageView, val label: TextView, val natural: Int) {
        var open = 0f
        var openAlpha = 0f
        var iconColor = 0
        var labelColor = 0
    }

    private val tabs = ArrayList<Tab>()
    private val ind = PillIndicator(this)
    private var opening: ValueAnimator? = null
    var selected = -1
        private set

    var onTabSelected: ((Int) -> Unit)? = null

    /**
     * 0..1: navigasi diperkecil ala iOS 26 — saat isi digulir ke bawah hanya tab aktif (ikon) yang tersisa, di tengah
     * lingkaran kaca di kiri; tab lain & tombol Scan memudar, nama tab menutup. Diatur [GlassScaffold.setMinimized].
     */
    override var collapse = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (field != c) { field = c; applyCollapse() } }
    /** Lingkaran navigasi yang diperkecil diketuk → minta dibesarkan lagi. */
    override var onExpand: (() -> Unit)? = null

    /** (x bilah ini di dalam kaca pembungkusnya, tinggi kaca) — lingkaran diperkecil = setinggi kaca, di kiri. */
    private fun glassFrame(): Pair<Float, Float>? {
        var ox = 0f
        var v: View = this
        while (true) {
            if (v is GlassSurface) return ox to v.height.toFloat()
            ox += v.left
            v = v.parent as? View ?: return null
        }
    }

    private fun applyCollapse() {
        val c = collapse
        val k = (c / 0.55f).coerceIn(0f, 1f)
        val others = 1f - k * k * (3f - 2f * k)
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (tabs.none { it.pill === ch && it.index == selected }) ch.alpha = others
        }
        for (t in tabs) setOpen(t, t.open)
        placeSelected()
        tint(); invalidate()
    }

    /** Tab aktif digeser ke tengah lingkaran sebanding [collapse]; tab lain di tempatnya. */
    private fun placeSelected() {
        val g = glassFrame()
        for (t in tabs) {
            val tx = if (t.index == selected && g != null && collapse > 0f)
                collapse * (g.second / 2f - g.first - (t.pill.left + t.pill.width / 2f)) else 0f
            if (t.pill.translationX != tx) t.pill.translationX = tx
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (collapse < 0.5f) return super.dispatchTouchEvent(ev)
        // diperkecil: hanya lingkaran di kiri yang menerima sentuhan (sisanya milik kapsul di sebelahnya) → membesar
        val g = glassFrame()
        val r = (g?.second ?: height.toFloat()) / 2f
        val cx = r - (g?.first ?: 0f)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && abs(ev.x - cx) > r) return false
        if (ev.actionMasked == MotionEvent.ACTION_UP && abs(ev.x - cx) <= r * 1.2f) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onExpand?.invoke()
        }
        return true
    }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var dragging = false
    /** Tab yang sedang di bawah lensa saat diseret (getar halus tiap berganti). */
    private var hover = -1

    private fun centerOf(t: Tab): Float = t.pill.left + t.pill.translationX + t.pill.width / 2f

    /** Tab yang diketuk di [x] (toleransi 10 dp di kiri-kanan pil). */
    private fun findTabAtX(x: Float): Tab? {
        val tol = context.dp(10)
        return tabs.firstOrNull { x >= it.pill.left - tol && x <= it.pill.right + tol }
    }

    /** Tab yang pusatnya paling dekat [x]. */
    private fun nearest(x: Float): Tab? = tabs.minByOrNull { abs(centerOf(it) - x) }

    private fun canDrag() = selected >= 0 && tabs.size > 1 && Motion.on()

    private fun startDrag(x: Float) {
        dragging = true
        hover = selected
        parent?.requestDisallowInterceptTouchEvent(true)
        dragTo(x)
    }

    /** Lensa mengikuti jari, dibatasi antara tab pertama & terakhir. */
    private fun dragTo(x: Float) {
        if (tabs.isEmpty()) return
        val lo = centerOf(tabs.first())
        val hi = centerOf(tabs.last())
        ind.drag(x.coerceIn(min(lo, hi), max(lo, hi)))
        val t = nearest(x) ?: return
        if (t.index != hover) { hover = t.index; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }

    /** Jari dilepas: tab di bawah lensa terpilih (halaman berganti sekarang), lensa meluncur & menempel ke sana. */
    private fun endDrag(x: Float) {
        dragging = false
        ind.release()
        val t = nearest(x)
        if (t != null && t.index != selected) onTabSelected?.invoke(t.index)
        // tab tidak berganti (atau pemanggil menolak): lensa kembali ke tab aktif
        tabs.firstOrNull { it.index == selected }?.let { if (ind.target === it.pill) ind.moveTo(it.pill, true) }
        hover = -1
    }

    private fun cancelDrag() {
        dragging = false
        ind.release()
        tabs.firstOrNull { it.index == selected }?.let { ind.moveTo(it.pill, true) }
        hover = -1
    }

    /** Seret mendatar di atas bilah = menggeser lensa (iOS); ketukan biasa tetap sampai ke tombol tab. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; dragging = false }
            MotionEvent.ACTION_MOVE -> if (!dragging && canDrag() && abs(ev.x - downX) > slop) { startDrag(ev.x); return true }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; dragging = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && canDrag() && abs(ev.x - downX) > slop) startDrag(ev.x)
                else if (dragging) dragTo(ev.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) endDrag(ev.x)
                else findTabAtX(ev.x)?.let { if (it.index != selected) onTabSelected?.invoke(it.index) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging) cancelDrag(); return true }
        }
        return super.onTouchEvent(ev)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        ind.paint.color = C.PRIMARY
        ind.edge = context.dp(3).toFloat()
        ind.lens = true
        ind.pop = true
        ind.glass = if (C.dark) 0x26FFFFFF else 0x14000000
        // lensa di lapisan atas ikut digambar ulang pada frame yang sama dengan pil
        ind.onFrame = { tint(); lensLayer.invalidateSelf() }
    }

    fun gap() = addView(View(context), LayoutParams(0, 1, 1f))

    fun addTab(index: Int, iconRes: Int, label: String, onTap: () -> Unit) {
        // tab tak terpilih: warna teks utama (putih di tema gelap, hitam di tema terang), bukan abu — jelas di atas kaca
        val ic = context.icon(iconRes, C.TEXT, 22)
        val lb = context.tv(label, 14f, C.ON_PRIMARY, true).apply {
            isSingleLine = true   // saat menyempit, teks terpotong rapi — tidak pindah baris
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            forceHasOverlappingRendering(false)
        }
        lb.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val pill = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            contentDescription = label
            // tanpa riak: umpan balik tekan = mengecil + lensa (riak abu tertinggal sebagai "jejak" saat lensa pergi)
            background = null
            minimumHeight = dp(48); minimumWidth = dp(48)
            addView(ic, LayoutParams(dp(22), dp(22)))
            addView(lb, LayoutParams(0, LayoutParams.WRAP_CONTENT))
            setOnClickListener { onTap() }
        }
        pill.squish(0.94f) { invalidate() }
        val t = Tab(index, pill, ic, lb, lb.measuredWidth)
        tabs.add(t)
        setOpen(t, 0f)
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
    }

    /** Tombol aksi bulat (mis. Scan): bukan tab, tidak dilewati warna penanda. */
    fun addAction(iconRes: Int, desc: String, onTap: () -> Unit) {
        val b = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            contentDescription = desc
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), context.shape(C.PRIMARY, 100), null)
            addView(context.icon(iconRes, C.ON_PRIMARY, 22), LayoutParams(dp(22), dp(22)))
            setOnClickListener { onTap() }
        }
        b.squish(0.92f)
        addView(b, LayoutParams(dp(48), dp(48)))
    }

    fun select(i: Int, animate: Boolean) {
        val t = tabs.firstOrNull { it.index == i } ?: return
        val anim = animate && selected >= 0 && selected != i && Motion.on()
        selected = i
        for (x in tabs) x.pill.isSelected = x.index == i
        ind.moveTo(t.pill, anim)
        openTo(i, anim)
        if (collapse > 0f) applyCollapse()
    }

    /** Tab aktif diketuk lagi. */
    fun beat() {
        ind.beat()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun openTo(i: Int, animate: Boolean) {
        val prev = opening
        opening = null
        prev?.cancel()
        if (!animate) {
            for (x in tabs) setOpen(x, if (x.index == i) 1f else 0f)
            tint(); return
        }
        val start = FloatArray(tabs.size) { tabs[it].open }
        val a = ValueAnimator.ofFloat(0f, 1f)
        // sama persis dengan gerak pil (durasi & kurva): label membuka/menutup dan pil tiba bersamaan
        a.duration = Motion.SLOW
        a.interpolator = Motion.STANDARD
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (opening === animation) tint() }
        })
        a.addUpdateListener {
            val c = it.animatedValue as Float
            for (k in tabs.indices) {
                val tb = tabs[k]
                val s = start[k]
                val goal = if (tb.index == i) 1f else 0f
                if (s == goal) continue
                setOpen(tb, s + (goal - s) * c)
            }
            tint()
        }
        opening = a
        a.start()
    }

    /** 0 = ikon saja, 1 = ikon + nama. Lebar, jarak, transparansi & geser label mengikuti. */
    private fun setOpen(t: Tab, open: Float) {
        t.open = open
        val f = open * (1f - collapse)   // navigasi diperkecil: nama tab ikut menutup
        val lp = t.label.layoutParams as LayoutParams
        val w = (t.natural * f).roundToInt()
        val m = (dp(8) * f).roundToInt()
        if (lp.width != w || lp.marginStart != m) {
            lp.width = w; lp.marginStart = m
            t.label.layoutParams = lp
        }
        t.openAlpha = ((f - 0.4f) / 0.6f).coerceIn(0f, 1f)   // teks muncul saat pil sudah di bawahnya
        t.label.alpha = t.openAlpha
        t.label.translationX = -(1f - f) * dp(4)
        val pl = (dp(13) + (dp(16) - dp(13)) * f).roundToInt()
        val pr = (dp(13) + (dp(20) - dp(13)) * f).roundToInt()
        if (t.pill.paddingLeft != pl || t.pill.paddingRight != pr) t.pill.setPadding(pl, 0, pr, 0)
    }

    /**
     * Ikon berganti warna mengikuti seberapa tertutup lensa — tab mana pun yang sedang dilewati, seperti lensa iOS
     * (berpindah halus, bukan saklar) — dan membesar sedikit selama lensa terangkat. Tab tak terpilih memakai warna teks
     * utama (putih di tema gelap, hitam di tema terang). Di atas kapsul solid isi tab
     * putih; di atas lensa kaca bening isi tab berwarna merek (biru), berpindah halus mengikuti [PillIndicator.lift].
     * Nama tab hanya tampak di bagian yang tertutup pil — tidak pernah muncul abu lalu berganti warna.
     */
    private fun tint() {
        val r = ind.rect()
        val lift = ind.lift
        val onLens = mixColor(C.ON_PRIMARY, C.PRIMARY, lift)
        for (t in tabs) {
            if (r == null) {
                val ci = if (t.index == selected) C.ON_PRIMARY else C.TEXT
                if (ci != t.iconColor) { t.iconColor = ci; t.icon.setColorFilter(ci) }
                if (t.icon.scaleX != 1f) { t.icon.scaleX = 1f; t.icon.scaleY = 1f }
                continue
            }
            val px = t.pill.left + t.pill.translationX
            val il = px + t.icon.left
            val iw = t.icon.width.toFloat()
            val cov = cover(r, il + iw * 0.3f, il + iw * 0.7f)
            val ci = mixColor(C.TEXT, onLens, cov)
            if (ci != t.iconColor) { t.iconColor = ci; t.icon.setColorFilter(ci) }
            // pembesaran lensa: ikon di bawah lensa yang terangkat tampak membesar
            val sc = if (realGlass) 1f else 1f + 0.18f * lift * cov   // shader membesarkan sendiri
            if (abs(t.icon.scaleX - sc) > 0.002f) { t.icon.scaleX = sc; t.icon.scaleY = sc }
            if (onLens != t.labelColor) { t.labelColor = onLens; t.label.setTextColor(onLens) }
            val lw = t.label.width
            if (lw > 0) {
                val lc = cover(r, px + t.label.left, px + t.label.left + lw)
                t.label.alpha = minOf(t.openAlpha, lc)
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (collapse > 0f) placeSelected()   // lebar pil berubah (nama menutup) → pusatkan ulang di lingkaran
        tint()
    }

    /** Lensa kaca shader (Android 13+); null = belum dibuat / tidak tersedia (lensa kanvas). */
    private var lensFx: TabLensFx? = null
    /** true bila frame terakhir memakai kaca shader (pembesaran ikon dilakukan shader, bukan skala tampilan). */
    private var realGlass = false

    private fun drawKids(c: Canvas) = super.dispatchDraw(c)

    /**
     * Isi di balik lensa = persis yang tampil di bilah: kaca navigasi di belakang (halaman buram + tint, lihat
     * [GlassSurface.drawBackdrop]) + ikon & nama tab. Karena sama dengan layar, bagian di luar bentuk kapsul tidak
     * pernah tampak sebagai sudut/kotak, dan lensa tembus pandang seperti navigasi.
     */
    private fun recordBehindLens(rc: Canvas) {
        var ox = 0f
        var oy = 0f
        var v: View = this
        var gs: GlassSurface? = null
        while (true) {
            if (v is GlassSurface) { gs = v; break }
            ox += v.left + v.translationX
            oy += v.top + v.translationY
            v = v.parent as? View ?: break
        }
        var ok = false
        if (gs != null) {
            rc.save()
            rc.translate(-ox, -oy)
            ok = try { gs.drawBackdrop(rc) } catch (_: Throwable) { false }
            rc.restore()
        }
        if (!ok) rc.drawColor(C.SURFACE)
        drawKids(rc)
    }

    /**
     * Lensa kaca digambar di lapisan atas jendela (overlay akar), bukan di kanvas bilah: saat terangkat ia membesar
     * keluar dari kapsul navigasi (yang memotong isinya ke bentuk kapsul), seperti desain Figma "Switch".
     */
    private val lensLayer = object : android.graphics.drawable.Drawable() {
        override fun draw(c: Canvas) = drawLensOnTop(c)
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    private var layerRoot: View? = null
    private val toGlobal = android.graphics.Matrix()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = android.graphics.Path()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val root = rootView
        if (root !== this) { root.overlay.add(lensLayer); layerRoot = root }
    }

    override fun onDetachedFromWindow() {
        layerRoot?.overlay?.remove(lensLayer)
        layerRoot = null
        super.onDetachedFromWindow()
    }

    private fun drawLensOnTop(c: Canvas) {
        if (!isAttachedToWindow || !isShown) return
        val lf = ind.lift
        if (lf < 0.01f) { if (realGlass) { realGlass = false; post { tint() } }; return }
        val r = ind.rect() ?: return
        toGlobal.reset()
        transformMatrixToGlobal(toGlobal)
        c.save()
        c.concat(toGlobal)
        // bayangan lembut di sekeliling lensa (Figma: 0 4 95 hitam 10%) — hanya di luar bentuk lensa
        val rad = r.height() / 2f
        clipPath.rewind()
        clipPath.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
        c.save()
        c.clipOutPath(clipPath)
        val d = resources.displayMetrics.density
        shadowPaint.color = C.BG
        shadowPaint.setShadowLayer(22f * d, 0f, 3f * d,
            Color.argb(((if (C.dark) 70 else 30) * lf).roundToInt(), 0, 0, 0))
        // bentuk pembawa bayangan sedikit lebih kecil dari lensa: seluruhnya terpotong, tak ada tepi warna latar tersisa
        val sh = RectF(r).apply { inset(1.5f * d, 1.5f * d) }
        c.drawRoundRect(sh, sh.height() / 2f, sh.height() / 2f, shadowPaint)
        c.restore()
        if (!drawLens(c, r, lf)) {
            // tanpa shader: kaca bening tiruan (semburat tipis) + kilau & tepi dari [PillIndicator.drawEdges]
            shadowPaint.clearShadowLayer()
            shadowPaint.color = ind.glass
            shadowPaint.alpha = (Color.alpha(ind.glass) * lf).roundToInt().coerceIn(0, 255)
            c.drawRoundRect(r, rad, rad, shadowPaint)
        }
        ind.drawEdges(c, realGlass)
        c.restore()
    }

    /** Lensa shader (Android 13+). true = tergambar. */
    private fun drawLens(canvas: Canvas, r: RectF, lf: Float): Boolean {
        var real = false
        if (Build.VERSION.SDK_INT >= 33 && LiquidGlass.active() && canvas.isHardwareAccelerated) {
            val fx = lensFx ?: try { TabLensFx().also { lensFx = it } } catch (_: Throwable) { LiquidGlass.fail(); null }
            if (fx != null) {
                real = try {
                    fx.draw(canvas, this, RectF(r), lf) { rc -> recordBehindLens(rc) }
                    true
                } catch (_: Throwable) { LiquidGlass.fail(); lensFx = null; false }
            }
        }
        if (real != realGlass) { realGlass = real; post { tint() } }
        return real
    }

    override fun dispatchDraw(canvas: Canvas) {
        ind.drawFill(canvas)
        super.dispatchDraw(canvas)
        val root = layerRoot
        if (root != null) {
            lensLayer.setBounds(0, 0, root.width, root.height)
            lensLayer.invalidateSelf()
            return
        }
        val lf = ind.lift
        val r = if (lf > 0.01f) ind.rect()?.let { RectF(it) } else null
        var real = false
        if (r != null && Build.VERSION.SDK_INT >= 33 && LiquidGlass.active() && canvas.isHardwareAccelerated) {
            val fx = lensFx ?: try { TabLensFx().also { lensFx = it } } catch (_: Throwable) { LiquidGlass.fail(); null }
            if (fx != null) {
                real = try {
                    fx.draw(canvas, this, r, lf) { rc -> recordBehindLens(rc) }
                    true
                } catch (_: Throwable) { LiquidGlass.fail(); lensFx = null; false }
            }
        }
        if (real != realGlass) { realGlass = real; tint() }
        ind.drawEdges(canvas, real)
    }
}

/**
 * Lensa tab dengan shader kaca cair yang SAMA dengan navigasi ([LiquidGlass.SRC]): isi bilah direkam ke satu
 * RenderNode tiap frame, lalu digambar ulang di posisi lensa lewat shader — refraksi tepi, kilau spekular mengikuti
 * kemiringan HP, cahaya & bayangan dalam, dispersi kromatik asli, dan pembesaran lensa (uniform `mag`): ikon yang
 * baru sebagian tertutup lensa tampak membesar HANYA di bagian yang tertutup, seperti lensa iOS. Tanpa blur (lensa
 * bening). Kelas terpisah agar kelas Android 13 tidak disentuh di HP yang lebih lama.
 */
@android.annotation.TargetApi(33)
private class TabLensFx {
    private val fx = LiquidFx()   // galat kompilasi shader → dilempar ke pemanggil
    private val content = RenderNode("tabLensContent")
    private val lens = RenderNode("tabLens")
    private val u = FloatArray(LiquidGlass.U)
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    private val outside = android.graphics.Path()

    fun draw(canvas: Canvas, host: View, r: RectF, lift: Float, record: (Canvas) -> Unit) {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        content.setPosition(0, 0, w, h)
        val rc = content.beginRecording(w, h)
        try { record(rc) } finally { content.endRecording() }

        // node lensa mencakup bilah + bagian lensa yang keluar dari bilah (mode meletup): semua posisi shader
        // dihitung relatif ke sudut kiri-atas node ini (ox, oy)
        val ox = min(0f, floor(r.left)); val oy = min(0f, floor(r.top))
        val nw = (max(w.toFloat(), ceil(r.right)) - ox).toInt()
        val nh = (max(h.toFloat(), ceil(r.bottom)) - oy).toInt()
        val d = host.resources.displayMetrics.density
        val hh = r.height()
        val bezel = min(16f * d, hh * 0.42f)
        // semburat sangat tipis: kilau putih & bayangan dalam tetap terbaca di atas bilah yang polos
        val base = if (C.dark) 0xFF4A4A46.toInt() else C.SURFACE2
        u[0] = r.left - ox; u[1] = r.top - oy; u[2] = r.width(); u[3] = hh
        u[4] = hh / 2f
        u[5] = 0f
        u[6] = bezel
        u[7] = bezel * 1.6f                                 // tetesan lensa: kaca kecil tapi tebal, tepi membias kuat
        u[8] = LiquidGlass.dispersionAmount() * 1.3f        // tetesan lensa: pelangi sedikit lebih terasa, seperti iOS
        u[29] = 1f                                          // lensa selalu kaca penuh
        u[9] = GlassLight.x; u[10] = GlassLight.y
        u[11] = Color.red(base) / 255f; u[12] = Color.green(base) / 255f; u[13] = Color.blue(base) / 255f
        u[14] = 0.06f                                       // kaca bening: isinya = kaca navigasi asli
        u[15] = if (C.dark) 1f else 0f
        u[16] = 0f; u[17] = 0f; u[18] = 0f; u[19] = 1f
        u[20] = if (C.dark) 0.9f else 1.1f
        u[21] = d
        // contoh piksel dibatasi ke bilah: bagian lensa di luar bilah memantulkan tepi bilah (kaca tetap utuh)
        u[22] = max(r.left, 0f) + 1.5f - ox; u[23] = max(r.top, 0f) + 1.5f - oy
        u[24] = min(r.right, w.toFloat()) - 1.5f - ox; u[25] = min(r.bottom, h.toFloat()) - 1.5f - oy
        // tanpa pita kontras adaptif: ikon di bawah lensa harus tetap tajam & berwarna, bukan dipudarkan ke pita
        u[26] = -1f; u[27] = 2f
        u[28] = 0.15f * lift                                // pembesaran ±1,18× saat terangkat penuh
        if (fx.update(u, 0f)) lens.setRenderEffect(fx.effect)
        lens.setPosition(ox.toInt(), oy.toInt(), ox.toInt() + nw, oy.toInt() + nh)
        lens.setAlpha(lift)   // kapsul solid di bawahnya memudar bersamaan → peralihan solid ↔ kaca mulus
        val lc = lens.beginRecording(nw, nh)
        try { lc.translate(-ox, -oy); lc.drawRenderNode(content) } finally { lens.endRecording() }
        // potong ke bentuk KAPSUL (bertepi halus), bukan kotak: bagian lensa di luar bilah tidak boleh tampak bersudut
        val sl = canvas.saveLayer(r.left - 1f, r.top - 1f, r.right + 1f, r.bottom + 1f, null)
        canvas.drawRenderNode(lens)
        // hapus SEMUA di luar kapsul (isian terbalik): DST_IN hanya mengenai piksel yang tergambar, sudut kotak tersisa
        outside.rewind()
        outside.addRoundRect(r, hh / 2f, hh / 2f, android.graphics.Path.Direction.CW)
        outside.fillType = android.graphics.Path.FillType.INVERSE_WINDING
        canvas.drawPath(outside, mask)
        canvas.restoreToCount(sl)
    }
}

/**
 * Baris pil: anak terpilih ditandai pil tinta yang meluncur rapi (lihat [PillIndicator]); warna teks anak asal &
 * tujuan berganti mengikuti seberapa tertutup pil — dipakai tab pita editor & kontrol segmen.
 */
@SuppressLint("ViewConstructor")
open class PillRow(ctx: Context) : LinearLayout(ctx) {
    protected val ind = PillIndicator(this)
    var textOn = C.ON_PRIMARY
    var textOff = C.MUTED
    /** Warna pil penanda (bawaan: warna utama). */
    var pillColor: Int
        get() = ind.paint.color
        set(v) { ind.paint.color = v; invalidate() }
    /** Jarak minimum pil dari tepi kiri/kanan baris (px) — mis. di dalam jalur kapsul. */
    var pillEdge: Float
        get() = ind.edge
        set(v) { ind.edge = v }
    private val last = HashMap<View, Int>()

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        ind.paint.color = C.PRIMARY
        ind.onFrame = { tint() }
    }

    private var cur: View? = null
    private var prev: View? = null

    /** Tandai [v] (anak langsung baris ini). */
    fun mark(v: View, animate: Boolean) {
        prev = if (animate) cur else null
        cur = v
        ind.moveTo(v, animate)
    }

    /** Hanya anak asal & tujuan yang berganti warna mengikuti pil; anak yang sekadar dilewati tetap (tidak berkedip). */
    private fun tint() {
        val r = ind.rect() ?: return
        for (i in 0 until childCount) {
            val c = getChildAt(i) as? TextView ?: continue
            if (c.visibility != View.VISIBLE) continue
            val col = if (c === cur || c === prev) mixColor(textOff, textOn, cover(r, c.left + c.width * 0.25f, c.right - c.width * 0.25f))
                else textOff
            if (last[c] != col) { last[c] = col; c.setTextColor(col) }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        tint()
    }

    override fun dispatchDraw(canvas: Canvas) {
        ind.draw(canvas)
        super.dispatchDraw(canvas)
    }
}

/**
 * Kontrol segmen dengan ibu jari tinta yang meluncur rapi (sama dengan bilah tab).
 * [onPick] langsung dipanggil saat diketuk, jadi isi yang berganti (mis. daftar) bergerak serentak dengan ibu jari.
 * Bila pemanggil membangun ulang kontrol INI sendiri (mis. seluruh halaman), pakai [waitThumb] = true: [onPick]
 * ditunda sampai ibu jari tiba, sehingga kontrol baru muncul tepat di posisi akhir — tidak ada ibu jari ganda.
 */
@SuppressLint("ViewConstructor")
class SegmentedBar(ctx: Context, private var selected: Int, private val waitThumb: Boolean = false, private val onPick: (Int) -> Unit) : PillRow(ctx) {
    private val segs = ArrayList<TextView>()
    private val fire = Runnable { onPick(selected) }

    init { ind.edge = context.dp(4).toFloat() }

    fun addSegment(label: String) {
        val i = segs.size
        val s = context.tv(label, 14f, C.MUTED, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            isClickable = true; isFocusable = true
            background = context.rippleBg(100)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true; info.isChecked = host.isSelected
                }
            }
            setOnClickListener { pick(i) }
        }
        s.squish(0.96f) { invalidate() }
        segs.add(s)
        addView(s, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        s.isSelected = i == selected
        if (i == selected) mark(s, false)
    }

    /** Pilih segmen [i] dari kode (mis. ketuk gambar halaman → "Pilih halaman"); [onPick] ikut dipanggil. */
    fun select(i: Int) { if (i in segs.indices) pick(i) }

    private fun pick(i: Int) {
        if (i == selected) return
        selected = i
        segs.forEachIndexed { k, s -> s.isSelected = k == i }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        mark(segs[i], true)
        removeCallbacks(fire)
        if (waitThumb && Motion.on()) postDelayed(fire, Motion.SLOW + 30L) else onPick(i)
    }
}
