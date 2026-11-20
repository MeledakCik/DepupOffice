package cloud.depup.office

import android.graphics.Color
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Menu seleksi teks (Potong / Salin / Tempel / Pilih semua) sebagai kapsul KACA hidup yang membaca isi layar
 * real-time (lihat [GlassScaffold.addFloating]): kaca terang di tema terang (seperti menu iOS 26), kaca gelap berasap
 * di tema gelap — tiap aksi berikon biru DepUp. Dulu kapsul hitam pekat yang mirip bilah "Tempel" bawaan Android.
 * Menggantikan bilah mengambang bawaan Android; pegangan seleksi & kaca pembesar tetap milik sistem.
 *
 * Satu menu untuk seluruh layar: muncul memantul di atas seleksi (atau di bawahnya bila terlalu dekat bilah atas),
 * berpindah mulus saat seleksi berpindah, dan memudar saat seleksi hilang / digeser / layar digulir.
 */
object SelectionGlass {
    private const val INK = 0xFF1C1C1F.toInt()

    class Item(val label: String, val action: () -> Unit, val icon: Int = 0)

    private var cap: GlassSurface? = null
    private var row: LinearLayout? = null
    private var owner: View? = null

    /** Cara menampilkan ulang menu untuk pemilik terakhir (dipakai sesudah gulir berhenti). */
    var reshow: (() -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val reshowRun = Runnable { reshow?.invoke() }

    /** Isi digulir: menu menyingkir dan muncul lagi ~300 ms sesudah gulir berhenti. */
    fun scrolled(v: View) {
        if (cap == null && !main.hasCallbacks(reshowRun)) return
        if (cap != null) hide()
        main.removeCallbacks(reshowRun)
        main.postDelayed(reshowRun, 300)
    }

    /** Menu sedang tampil untuk [v]? */
    fun showingFor(v: View) = cap != null && owner === v

    /**
     * Tampilkan menu untuk [anchor] di dekat [local] (kotak seleksi dalam koordinat [anchor], sebelum transformasi
     * zoom). Item kosong → menu disembunyikan.
     */
    fun show(anchor: View, local: RectF, items: List<Item>) {
        if (items.isEmpty()) { hide(); return }
        val sc = GlassContent.find(anchor.rootView)?.parent as? GlassScaffold ?: return
        if (!sc.isAttachedToWindow || sc.width == 0) return
        val ctx = anchor.context

        // kotak seleksi → koordinat scaffold (memperhitungkan zoom/skala & gulir semua induk)
        val r = RectF(local); SelectionKit.localToSc(anchor, sc).mapRect(r)

        val c = cap
        val existing = c != null && c.parent === sc
        val dark = C.dark
        val fg = if (dark) Color.WHITE else C.TEXT
        val iconFg = if (dark) 0xFFA9BEFF.toInt() else C.ACCENT
        val surface = if (existing) c!! else GlassSurface(ctx).apply {
            tintColor = { if (C.dark) INK else Color.WHITE }
            tintAlpha = if (dark) 214 else 222
            liquidTint = if (dark) 0.64f else 0.56f
            darkGlass = dark
            corner = ctx.dp(100).toFloat()
            elevation = ctx.dp(10).toFloat()
            outlineSpotShadowColor = 0x59000000
            outlineAmbientShadowColor = 0x33000000
            isClickable = true
        }
        val lr = row ?: LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(6), 0, ctx.dp(6), 0)
        }
        if (!existing) surface.addView(lr, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, ctx.dp(44)))
        lr.removeAllViews()
        items.forEachIndexed { i, it ->
            if (i > 0) lr.addView(View(ctx).apply { setBackgroundColor(if (dark) 0x33FFFFFF else 0x1F000000) },
                LinearLayout.LayoutParams(ctx.dp(1), ctx.dp(18)))
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(ctx.dp(if (it.icon != 0) 11 else 14), 0, ctx.dp(14), 0)
                minimumWidth = ctx.dp(48)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x1F1652F6), null,
                    android.graphics.drawable.ColorDrawable(Color.WHITE))
                isClickable = true
                contentDescription = it.label
                setOnClickListener { _ -> haptic(this); it.action() }
            }
            if (it.icon != 0) cell.addView(ctx.icon(it.icon, iconFg, 17).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(ctx.dp(17), ctx.dp(17)).apply { marginEnd = ctx.dp(6) })
            cell.addView(TextView(ctx).apply {
                text = it.label
                textSize = 14f
                setTextColor(fg)
                try { typeface = Fonts.label(ctx) } catch (_: Throwable) {}
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            lr.addView(cell, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        row = lr

        // ukur dulu supaya bisa diletakkan tepat di atas / bawah seleksi
        lr.measure(View.MeasureSpec.makeMeasureSpec(sc.width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(ctx.dp(44), View.MeasureSpec.EXACTLY))
        val w = lr.measuredWidth; val h = ctx.dp(44)
        val gap = ctx.dp(12)
        val topLimit = (if (sc.topBar.visibility == View.VISIBLE) sc.topBar.bottom + sc.topBar.translationY.toInt() else 0) + ctx.dp(6)
        val botLimit = (if (sc.bottomBar.visibility == View.VISIBLE) sc.bottomBar.top + sc.bottomBar.translationY.toInt() else sc.height) - ctx.dp(6)
        var y = r.top - gap - h
        if (y < topLimit) y = r.bottom + gap + ctx.dp(14)          // di bawah seleksi (melewati pegangan)
        y = y.coerceIn(topLimit.toFloat(), max(topLimit, botLimit - h).toFloat())
        val margin = ctx.dp(10)
        val x = (r.centerX() - w / 2f).coerceIn(margin.toFloat(), max(margin, sc.width - w - margin).toFloat())

        val lp = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START).apply { leftMargin = 0; topMargin = 0 }
        if (!existing) {
            surface.translationX = x; surface.translationY = y
            sc.addFloating(surface, lp)
            cap = surface; owner = anchor
            if (Motion.on()) {
                // kaca mewujud ala Liquid Glass (pembiasan & blur tumbuh dari nol) sambil membesar dengan pegas
                surface.materializeNow(0f); lr.alpha = 0f
                surface.scaleX = 0.88f; surface.scaleY = 0.88f
                surface.pivotX = w / 2f; surface.pivotY = if (y > r.top) 0f else h.toFloat()
                sc.holdWhole(Motion.SPRING_MS + 200L)
                surface.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).start()
                surface.materializeTo(1f, Motion.MED + 120L, Motion.STANDARD, lr)
            }
        } else {
            surface.layoutParams = lp
            owner = anchor
            surface.animate().cancel()
            surface.alpha = 1f; surface.scaleX = 1f; surface.scaleY = 1f
            surface.materializeNow(1f); lr.alpha = 1f
            // sudah tampil: meluncur ke posisi baru (kata → kalimat, pindah paragraf)
            if (Motion.on()) {
                sc.holdWhole(Motion.SPRING_SOFT_MS + 200L)
                surface.animate().translationX(x).translationY(y).setDuration(Motion.SPRING_SOFT_MS).setInterpolator(Motion.SPRING_SOFT).start()
            } else { surface.translationX = x; surface.translationY = y }
        }
    }

    /** Sembunyikan (memudar & mengecil sedikit). [only] = hanya bila menu milik tampilan ini. */
    fun hide(only: View? = null) {
        val c = cap ?: return
        if (only != null && owner !== only) return
        if (only != null) { main.removeCallbacks(reshowRun); reshow = null }
        cap = null; row = null; owner = null
        val sc = c.parent as? GlassScaffold
        val gone = { if (c.parent != null) (c.parent as? GlassScaffold)?.removeFloating(c) ?: (c.parent as? android.view.ViewGroup)?.removeView(c) }
        if (!Motion.on() || sc == null) { gone(); return }
        sc.holdWhole(Motion.FAST + 200L)
        c.animate().cancel()
        // kaca melebur (kebalikan mewujud) sambil mengecil sedikit
        c.animate().scaleX(0.94f).scaleY(0.94f).setDuration(Motion.FAST + 60L).setInterpolator(Motion.EXIT).start()
        c.materializeTo(0f, Motion.FAST + 60L, Motion.EXIT, c.getChildAt(0)) { gone() }
        c.postDelayed({ gone() }, Motion.FAST + 200L)
    }
}
