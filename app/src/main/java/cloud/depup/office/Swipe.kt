package cloud.depup.office

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Baris daftar yang bisa digeser ala iOS. Geser ke KIRI → tombol [trailing] muncul di kanan (Bagikan, Ubah nama,
 * Hapus); geser ke KANAN → tombol [leading] di kiri (Sematkan). Tombol melebar sebanding geseran; geser jauh melewati
 * batas → tombol terluar melebar menutupi semuanya (getar halus) dan dijalankan saat jari dilepas.
 * Hanya satu baris terbuka sekaligus; ketukan pada isi baris yang terbuka hanya menutupnya. Semua aksi juga tersedia
 * lewat menu tindakan TalkBack — geser bukan satu-satunya jalan.
 */
@SuppressLint("ViewConstructor")
class SwipeRow(ctx: Context, val content: View) : FrameLayout(ctx) {
    /**
     * [removes] = aksi yang menghapus baris (mis. Hapus): isi digeser keluar dulu, lalu [run] — [run] wajib menghapus
     * barisnya atau memanggil [close] bila dibatalkan. Aksi lain: baris menutup sendiri sambil [run] berjalan.
     */
    class Action(val label: String, val icon: Int, val color: Int, val desc: String = label, val removes: Boolean = false,
                 val run: (SwipeRow) -> Unit)

    companion object {
        private var openRow: WeakReference<SwipeRow>? = null

        /** Tutup baris yang sedang terbuka (mis. saat halaman digulir). */
        fun closeAll() { openRow?.get()?.close(); openRow = null }

        // warna tombol: teks putih di atasnya kontras >= 4,5:1 — terbaca di tema terang & gelap
        const val BLUE = 0xFF0066D6.toInt()
        const val GRAY = 0xFF6C6C70.toInt()
        const val RED = 0xFFD92C21.toInt()
        const val ORANGE = 0xFFB85E00.toInt()
    }

    private class Btn(val view: FrameLayout, val col: View, val action: Action)

    var leading: List<Action> = emptyList()
        set(v) { field = v; build(true) }
    var trailing: List<Action> = emptyList()
        set(v) { field = v; build(false) }

    private val holder = FrameLayout(ctx)
    private val leftSide = FrameLayout(ctx)
    private val rightSide = FrameLayout(ctx)
    private val leftBtns = ArrayList<Btn>()     // indeks 0 = terluar (tepi kiri)
    private val rightBtns = ArrayList<Btn>()    // indeks terakhir = terluar (tepi kanan)
    private val a11y = ArrayList<Int>()

    private var off = 0f                        // geseran isi: < 0 = tombol kanan terbuka, > 0 = tombol kiri
    private var bwL = 0f; private var bwR = 0f  // lebar satu tombol saat terbuka penuh
    private var armF = 0f; private var armed = false
    private var anim: ValueAnimator? = null
    private var armAnim: ValueAnimator? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = ctx.dp(420).toFloat()
    private var downX = 0f; private var downY = 0f; private var startOff = 0f
    private var dragging = false; private var refused = false; private var tapClose = false
    private var vt: VelocityTracker? = null

    init {
        leftSide.visibility = INVISIBLE; rightSide.visibility = INVISIBLE
        addView(leftSide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(rightSide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        holder.setBackgroundColor(C.SURFACE)      // menutupi tombol; sewarna kartu daftar
        holder.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(holder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun build(lead: Boolean) {
        val side = if (lead) leftSide else rightSide
        val list = if (lead) leftBtns else rightBtns
        side.removeAllViews(); list.clear()
        for (a in if (lead) leading else trailing) {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(context.dp(6), 0, context.dp(6), 0)
                addView(context.icon(a.icon, Color.WHITE, 22), LinearLayout.LayoutParams(context.dp(22), context.dp(22)))
                addView(context.tv(a.label, 12f, Color.WHITE, true).apply { maxLines = 1; gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4) })
            }
            val v = FrameLayout(context).apply {
                setBackgroundColor(a.color)
                // sentuhan saja; TalkBack memakai aksi kustom pada isi baris
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setOnClickListener { fire(a, lead) }
            }
            v.addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, if (lead) Gravity.END else Gravity.START))
            list.add(Btn(v, col, a))
        }
        // yang lebih dekat ke isi digambar paling atas agar irisannya tidak tertutup tetangganya
        for (b in if (lead) list.asReversed() else list) side.addView(b.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        sizeButtons()
        place()
        a11y.forEach { ViewCompat.removeAccessibilityAction(content, it) }
        a11y.clear()
        for (a in leading + trailing) a11y.add(ViewCompat.addAccessibilityAction(content, a.desc) { _, _ -> a.run(this); true })
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        sizeButtons()
        place()
    }

    private fun sizeButtons() {
        val w = width.toFloat()
        bwL = if (leftBtns.isEmpty()) 0f else min(context.dp(76).toFloat(), w * 0.72f / leftBtns.size)
        bwR = if (rightBtns.isEmpty()) 0f else min(context.dp(76).toFloat(), w * 0.72f / rightBtns.size)
    }

    private fun maxOpen(lead: Boolean) = if (lead) bwL * leftBtns.size else bwR * rightBtns.size

    /** Geser sejauh ini → aksi terluar siap dijalankan (geser penuh). */
    private fun fullAt(lead: Boolean): Float { val w = width.toFloat(); return min(max(maxOpen(lead) + context.dp(56), w * 0.6f), w * 0.88f) }

    /** Letak tiap tombol mengikuti [off]: terbuka sebanding, tombol terluar melebar saat [armF] → 1. */
    private fun place() {
        val w = width.toFloat()
        holder.translationX = off
        rightSide.visibility = if (off < -0.5f && rightBtns.isNotEmpty()) VISIBLE else INVISIBLE
        leftSide.visibility = if (off > 0.5f && leftBtns.isNotEmpty()) VISIBLE else INVISIBLE
        if (w <= 0f) return
        if (off < 0f) {
            val r = -off; val n = rightBtns.size
            rightBtns.forEachIndexed { i, b ->
                val outer = i == n - 1
                val a = if (outer) armF else 0f
                var x = w - r * (n - i) / n
                if (outer) x += (w - r - x) * a
                val s = (if (outer) w else w - r * (n - i - 1) / n) - x
                b.view.translationX = x
                val cw = b.col.width.toFloat()
                b.col.translationX = (s - cw) / 2f + ((bwR - cw) / 2f - (s - cw) / 2f) * a
                b.col.alpha = ((s / bwR - 0.35f) / 0.45f).coerceIn(0f, 1f)
            }
        }
        if (off > 0f) {
            val r = off; val n = leftBtns.size
            leftBtns.forEachIndexed { i, b ->
                val outer = i == 0
                val a = if (outer) armF else 0f
                var right = r * (i + 1) / n
                if (outer) right += (r - right) * a
                val s = right - (if (outer) 0f else r * i / n)
                b.view.translationX = right - w
                val cw = b.col.width.toFloat()
                b.col.translationX = -((s - cw) / 2f + ((bwL - cw) / 2f - (s - cw) / 2f) * a)
                b.col.alpha = ((s / bwL - 0.35f) / 0.45f).coerceIn(0f, 1f)
            }
        }
    }

    private fun setOff(o: Float) {
        val w = width.toFloat()
        off = when {
            o < 0f -> if (rightBtns.isEmpty()) 0f else max(o, -w)
            o > 0f -> if (leftBtns.isEmpty()) 0f else min(o, w)
            else -> 0f
        }
        val lead = off > 0f
        val want = off != 0f && abs(off) > fullAt(lead)
        if (want != armed) {
            armed = want
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            animateArm(if (want) 1f else 0f)
        }
        place()
    }

    private fun animateArm(to: Float) {
        armAnim?.cancel()
        if (!Motion.on()) { armF = to; place(); return }
        armAnim = ValueAnimator.ofFloat(armF, to).apply {
            duration = Motion.FAST
            interpolator = Motion.STANDARD
            addUpdateListener { armF = it.animatedValue as Float; place() }
            start()
        }
    }

    private fun animateTo(target: Float, spring: Boolean, dur: Long = 0L, then: (() -> Unit)? = null) {
        anim?.cancel()
        if (target != 0f) openRow = WeakReference(this)
        if (!Motion.on() || !isAttachedToWindow) { off = target; place(); then?.invoke(); return }
        anim = ValueAnimator.ofFloat(off, target).apply {
            duration = if (dur > 0) dur else if (spring) Motion.SPRING_SOFT_MS else Motion.MED + 40L
            interpolator = if (spring) Motion.SPRING_SOFT else Motion.STANDARD
            addUpdateListener { off = it.animatedValue as Float; place() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) then?.invoke() }
            })
            start()
        }
    }

    /** Tutup baris (tombol tersembunyi lagi). */
    fun close(animate: Boolean = true, then: (() -> Unit)? = null) {
        if (openRow?.get() === this) openRow = null
        armed = false
        if (armF != 0f) animateArm(0f)
        if (!animate) { anim?.cancel(); armAnim?.cancel(); armF = 0f; off = 0f; place(); then?.invoke(); return }
        animateTo(0f, spring = false, then = then)
    }

    private fun fire(a: Action, lead: Boolean) {
        val outer = if (lead) leftBtns.firstOrNull()?.action === a else rightBtns.lastOrNull()?.action === a
        if (a.removes) {
            if (openRow?.get() === this) openRow = null    // jangan ikut ditutup saat halaman bergeser karena baris hilang
            if (outer && armF < 1f) { armed = true; animateArm(1f) }
            val w = width.toFloat()
            animateTo(if (lead) w else -w, spring = false, dur = Motion.FAST + 70L) { a.run(this) }
        } else {
            close()
            a.run(this)
        }
    }

    private fun onContent(x: Float) = if (off < 0f) x < width + off else x > off

    private fun canDrag(dx: Float) = isEnabled && if (dx < 0f) rightBtns.isNotEmpty() || off > 0f else leftBtns.isNotEmpty() || off < 0f

    private fun beginDrag(dx: Float) {
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        downX += if (dx > 0f) slop.toFloat() else -slop.toFloat()   // tanpa lompatan sebesar slop
        anim?.cancel()
        startOff = off
        openRow?.get()?.let { if (it !== this) it.close() }
        openRow = WeakReference(this)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dragging = false; refused = false; tapClose = false
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(ev) }
                openRow?.get()?.let { if (it !== this) closeAll() }
                anim?.cancel()
                startOff = off
                // baris terbuka: sentuhan pada isinya hanya menutup (tidak membuka berkas)
                if (off != 0f && onContent(ev.x)) { tapClose = true; return true }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (refused) return false
                val dx = ev.x - downX; val dy = ev.y - downY
                if (abs(dy) > slop && abs(dy) > abs(dx)) { refused = true; return false }
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.3f && canDrag(dx)) { beginDrag(dx); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                if (!dragging) {
                    if (abs(dx) > slop && canDrag(dx)) { tapClose = false; beginDrag(dx) } else return true
                }
                setOff(startOff + (ev.x - downX))
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) { vt?.computeCurrentVelocity(1000); settle(vt?.xVelocity ?: 0f) }
                else if (tapClose) close()
                dragging = false; tapClose = false
                vt?.recycle(); vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging || tapClose) settle(0f)
                dragging = false; tapClose = false
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun settle(v: Float) {
        val lead = off > 0f
        val r = abs(off)
        if (r < 0.5f) { close(animate = false); return }
        if (armed) {
            val b = if (lead) leftBtns.firstOrNull() else rightBtns.lastOrNull()
            if (b != null) { fire(b.action, lead); return }
        }
        val flingOpen = if (lead) v > minFling else v < -minFling
        val flingClose = if (lead) v < -minFling else v > minFling
        val open = when {
            flingOpen -> true
            flingClose -> false
            else -> r > maxOpen(lead) * 0.5f
        }
        if (open) animateTo(if (lead) maxOpen(true) else -maxOpen(false), spring = true) else close()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); armAnim?.cancel()
        if (openRow?.get() === this) openRow = null
        off = 0f; armF = 0f; armed = false
        place()
        super.onDetachedFromWindow()
    }
}
