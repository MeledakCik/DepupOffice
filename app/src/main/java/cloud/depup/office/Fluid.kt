package cloud.depup.office

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
 * Pil penanda yang berpindah antar-anak sebuah baris seperti tetes cair, tetapi rapi: satu kurva
 * ([Motion.STANDARD], [Motion.SLOW]); di tengah perjalanan pil sedikit memanjang searah gerak & memipih (morph),
 * lalu kembali bulat TEPAT saat tiba — tanpa pantulan. Selalu mengikuti ukuran & skala anak tujuan secara langsung
 * (ikut melebar saat label tab terbuka, ikut mengecil saat ditekan). Dipakai bilah tab bawah, tab pita editor &
 * kontrol segmen.
 */
class PillIndicator(private val host: View) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Jarak minimum pil dari tepi kiri/kanan [host] (px): pil tidak pernah terpotong tepi. */
    var edge = 0f
    var target: View? = null
        private set
    /** Dipanggil tiap frame animasi — mis. mewarnai ulang ikon/teks yang sedang dilewati pil. */
    var onFrame: (() -> Unit)? = null

    private val from = RectF()
    private val to = RectF()
    private val now = RectF()
    private var t = 1f
    private var pulse = 0f
    private var move: ValueAnimator? = null
    private var beat: ValueAnimator? = null

    private fun bounds(v: View, out: RectF) {
        val cx = v.left + v.width / 2f + v.translationX
        val cy = v.top + v.height / 2f + v.translationY
        val hw = v.width * v.scaleX / 2f
        val hh = v.height * v.scaleY / 2f
        out.set(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    /** Bentuk pil saat ini (koordinat [host]); null bila tujuannya belum terukur. */
    fun rect(): RectF? {
        val v = target ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        bounds(v, to)
        if (t >= 1f) now.set(to) else {
            // t sudah melalui kurva animator: keempat tepi berpindah serempak
            now.left = from.left + (to.left - from.left) * t
            now.right = from.right + (to.right - from.right) * t
            now.top = from.top + (to.top - from.top) * t
            now.bottom = from.bottom + (to.bottom - from.bottom) * t
            // morph cair: memanjang searah gerak & sedikit memipih di tengah perjalanan, kembali bulat tepat saat tiba
            // (amplop 4t(1−t): nol di awal & akhir — tidak ada pantulan)
            val env = 4f * t * (1f - t)
            val ext = min(abs(to.centerX() - from.centerX()) * 0.2f, now.height() * 0.6f) * env
            now.left -= ext / 2f; now.right += ext / 2f
            val sq = now.height() * 0.06f * env
            now.top += sq / 2f; now.bottom -= sq / 2f
        }
        if (pulse != 0f) {
            // denyut: membesar merata sedikit (bentuk tetap pil yang sama)
            val dx = now.height() * 0.06f * pulse
            now.left -= dx; now.right += dx; now.top -= dx; now.bottom += dx
        }
        if (host.width > 0) {
            if (now.left < edge) now.left = edge
            if (now.right > host.width - edge) now.right = host.width - edge
        }
        return now
    }

    fun draw(c: Canvas) {
        val r = rect() ?: return
        val rad = r.height() / 2f
        c.drawRoundRect(r, rad, rad, paint)
    }

    /** Pindah ke [v]. Tanpa animasi bila [animate] false / belum ada posisi awal / animasi HP dimatikan. */
    fun moveTo(v: View, animate: Boolean) {
        val cur = rect()?.let { RectF(it) }
        target = v
        val prev = move
        move = null
        prev?.cancel()
        if (!animate || cur == null || cur.isEmpty || !Motion.on()) {
            t = 1f; onFrame?.invoke(); host.invalidate(); return
        }
        from.set(cur)
        bounds(v, to)
        t = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = Motion.SLOW
        a.interpolator = Motion.STANDARD
        a.addUpdateListener { t = it.animatedValue as Float; onFrame?.invoke(); host.invalidate() }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (move === animation) { t = 1f; onFrame?.invoke(); host.invalidate() }
            }
        })
        move = a
        a.start()
    }

    /** Denyut tunggal (tab aktif diketuk lagi): membesar sedikit lalu kembali — satu kali, tanpa getar. */
    fun beat() {
        if (!Motion.on()) return
        beat?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = Motion.SLOW
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            val x = it.animatedValue as Float
            pulse = sin(PI.toFloat() * x)     // naik lalu turun mulus, sekali
            onFrame?.invoke(); host.invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { pulse = 0f; host.invalidate() }
        })
        beat = a
        a.start()
    }
}

/** Umpan balik tekan: mengecil sedikit, dilepas kembali mulus (tanpa pantulan). [onFrame] dipanggil tiap frame. */
@SuppressLint("ClickableViewAccessibility")
internal fun View.squish(to: Float = 0.95f, onFrame: (() -> Unit)? = null) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(to).scaleY(to).setStartDelay(0).setDuration(Motion.PRESS_DOWN)
                .setInterpolator(Motion.STANDARD).setUpdateListener { onFrame?.invoke() }.start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setStartDelay(0)
                .setDuration(Motion.PRESS_UP).setInterpolator(Motion.STANDARD).setUpdateListener { onFrame?.invoke() }.start()
        }
        false
    }
}

/**
 * Bilah tab bawah.
 * - Satu pil tinta meluncur antar-tab (lihat [PillIndicator]); ikon & teks tab asal/tujuan berubah warna
 *   mengikuti seberapa tertutup mereka — seperti lensa yang lewat di atasnya.
 * - Nama tab terpilih membuka (lebar, jarak, transparansi) dan nama tab lama menutup SERENTAK dengan gerak pil:
 *   kurva & durasi yang sama, jadi pil, label, dan tab lain yang bergeser tiba bersamaan.
 * - Tiap tombol mengecil sedikit saat ditekan & kembali mulus; tab aktif yang diketuk lagi membuat pil berdenyut sekali.
 */
@SuppressLint("ViewConstructor")
class TabBar(ctx: Context) : LinearLayout(ctx) {
    private class Tab(val index: Int, val pill: LinearLayout, val icon: ImageView, val label: TextView, val natural: Int) {
        var open = 0f
        var openAlpha = 0f
        var iconColor = 0
    }

    private val tabs = ArrayList<Tab>()
    private val ind = PillIndicator(this)
    private var opening: ValueAnimator? = null
    var selected = -1
        private set
    /** Tab yang sedang ditinggalkan — hanya tab asal & tujuan yang berganti warna (tab yang dilewati tidak berkedip). */
    private var leaving = -1

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        ind.paint.color = C.INK
        ind.edge = context.dp(3).toFloat()
        ind.onFrame = { tint() }
    }

    fun gap() = addView(View(context), LayoutParams(0, 1, 1f))

    fun addTab(index: Int, iconRes: Int, label: String, onTap: () -> Unit) {
        val ic = context.icon(iconRes, C.MUTED, 22)
        val lb = context.tv(label, 14f, C.ON_INK, true).apply {
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
            background = context.rippleBg(100)
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
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), context.shape(C.INK, 100), null)
            addView(context.icon(iconRes, C.ON_INK, 22), LayoutParams(dp(22), dp(22)))
            setOnClickListener { onTap() }
        }
        b.squish(0.92f)
        addView(b, LayoutParams(dp(48), dp(48)))
    }

    fun select(i: Int, animate: Boolean) {
        val t = tabs.firstOrNull { it.index == i } ?: return
        val anim = animate && selected >= 0 && selected != i && Motion.on()
        leaving = if (anim) selected else -1
        selected = i
        for (x in tabs) x.pill.isSelected = x.index == i
        ind.moveTo(t.pill, anim)
        openTo(i, anim)
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
            override fun onAnimationEnd(animation: Animator) { if (opening === animation) { leaving = -1; tint() } }
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
    private fun setOpen(t: Tab, f: Float) {
        t.open = f
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
     * Ikon tab asal & tujuan berganti warna mengikuti seberapa tertutup pil tinta (berpindah halus, bukan saklar).
     * Nama tab selalu putih di atas pil dan hanya tampak di bagian yang tertutup pil — tidak pernah muncul abu lalu
     * berganti warna. Tab yang sekadar dilewati pil tetap abu (tidak berkedip).
     */
    private fun tint() {
        val r = ind.rect()
        for (t in tabs) {
            val active = t.index == selected || t.index == leaving
            if (r == null || !active) {
                val ci = if (r == null && t.index == selected) C.ON_INK else C.MUTED
                if (ci != t.iconColor) { t.iconColor = ci; t.icon.setColorFilter(ci) }
                continue
            }
            val px = t.pill.left + t.pill.translationX
            val il = px + t.icon.left
            val iw = t.icon.width.toFloat()
            val ci = mixColor(C.MUTED, C.ON_INK, cover(r, il + iw * 0.3f, il + iw * 0.7f))
            if (ci != t.iconColor) { t.iconColor = ci; t.icon.setColorFilter(ci) }
            val lw = t.label.width
            if (lw > 0) {
                val lc = cover(r, px + t.label.left, px + t.label.left + lw)
                t.label.alpha = minOf(t.openAlpha, lc)
            }
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
 * Baris pil: anak terpilih ditandai pil tinta yang meluncur rapi (lihat [PillIndicator]); warna teks anak asal &
 * tujuan berganti mengikuti seberapa tertutup pil — dipakai tab pita editor & kontrol segmen.
 */
@SuppressLint("ViewConstructor")
open class PillRow(ctx: Context) : LinearLayout(ctx) {
    protected val ind = PillIndicator(this)
    var textOn = C.ON_INK
    var textOff = C.MUTED
    /** Jarak minimum pil dari tepi kiri/kanan baris (px) — mis. di dalam jalur kapsul. */
    var pillEdge: Float
        get() = ind.edge
        set(v) { ind.edge = v }
    private val last = HashMap<View, Int>()

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        ind.paint.color = C.INK
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
