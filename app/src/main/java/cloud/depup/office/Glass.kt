package cloud.depup.office

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.WebView
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wadah isi layar yang menjadi SUMBER kaca.
 *
 * Android 12+: seluruh isi direkam ke satu RenderNode ([node]) lalu node itu digambar ke layar. Permukaan kaca
 * ([GlassSurface]) menggambar NODE YANG SAMA sekali lagi lewat efek blur GPU. Karena yang dibagi adalah node-nya
 * (bukan salinan gambar), setiap perubahan di dalam isi — gulir, animasi, ketikan, kedip kursor — otomatis ikut
 * tampil di kaca pada frame yang sama, tanpa perlu merekam ulang atau invalidate apa pun.
 */
class GlassContent(ctx: Context) : FrameLayout(ctx) {
    // Hanya dibuat bila blur GPU diaktifkan. Mati = wadah biasa yang menggambar isinya langsung (paling stabil).
    val node: RenderNode? = if (Glass.BLUR && Build.VERSION.SDK_INT >= 31) RenderNode("glassContent") else null

    /** true bila isi memuat WebView / pratinjau kamera: digambar biasa, kaca memakai mode pekat. */
    var direct = false
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun dispatchDraw(canvas: Canvas) {
        val n = node
        if (n != null && Glass.BLUR && !direct && canvas.isHardwareAccelerated && width > 0 && height > 0) {
            n.setPosition(0, 0, width, height)
            val rc = n.beginRecording(width, height)
            try { super.dispatchDraw(rc) } finally { n.endRecording() }
            canvas.drawRenderNode(n)
        } else super.dispatchDraw(canvas)
    }

    companion object {
        /** Cari wadah isi kaca pertama di bawah [root] (dipakai notifikasi kapsul). */
        fun find(root: View, depth: Int = 0): GlassContent? {
            if (root is GlassContent) return root
            if (root is ViewGroup && depth < 8) for (i in 0 until root.childCount) find(root.getChildAt(i), depth + 1)?.let { return it }
            return null
        }
    }
}

/**
 * Permukaan "liquid glass": apa pun yang ada di belakangnya (di dalam [content]) tampil lewat lensa kaca,
 * real-time — lihat [LiquidGlass] untuk lapisan efeknya.
 *
 * - Android 13+: blur GPU atas node isi yang dibagi (lihat [GlassContent]) → shader lensa AGSL (refraksi tepi,
 *   dispersi, saturasi, tint adaptif, highlight spekular mengikuti cahaya, cahaya dalam, cahaya sentuh).
 * - Android 12: blur GPU + saturasi 160%; highlight tepi, cahaya dalam & cahaya sentuh digambar kanvas.
 * - Android 10–11: isi digambar ke bitmap 1/8 ukuran tepat sebelum tiap frame, lalu di-blur (tanpa invalidate:
 *   bitmap yang sudah tertaut ke kanvas otomatis tampil dengan isi terbarunya) + lapisan kanvas yang sama.
 * - Tidak bisa direkam (WebView/kamera di belakangnya, HP lemah tanpa blur GPU): kaca pekat tanpa blur.
 */
open class GlassSurface(ctx: Context) : FrameLayout(ctx) {
    var content: GlassContent? = null
        set(v) { field = v; invalidate() }

    /** Warna dasar layar di belakang isi (untuk area yang kosong). */
    var baseColor: Int? = null

    /** Radius sudut (px). > 0 = kapsul melayang: dipotong membulat & diberi tepi kaca. */
    var corner = 0f
        set(v) { field = v; clipToOutline = v > 0f; invalidateOutline(); invalidate() }

    /** Warna lapisan kaca tanpa alfa; null = tidak digambar (pemanggil melapisi sendiri). */
    var tintColor: (() -> Int)? = { C.SURFACE }
    /** Alfa lapisan kaca saat blur biasa aktif; null = bawaan (tipis). */
    var tintAlpha: Int? = null
    /** Kekuatan tint pada kaca cair (0..1); null = bawaan (kapsul tipis, bilah rata sedikit lebih pekat). */
    var liquidTint: Float? = null
    /** Tepi mana yang nyata untuk bilah rata (diberi tepi kaca): -1 bawah, 1 atas, 0 tanpa. */
    var edge = 0

    /**
     * 0..1: bentuk kaca tumbuh dari bawah — untuk MORPH muncul/hilang (kapsul tumbuh dari navigasi, bukan memudar).
     * Isi kapsul tidak ikut diskalakan, hanya terungkap mengikuti bentuknya.
     */
    var reveal = 1f
        set(v) { val c = v.coerceIn(0f, 1f); if (field != c) { field = c; invalidateOutline(); invalidate() } }

    var blocked = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Cahaya sentuh (koordinat permukaan) dan kekuatannya 0..1. */
    internal var touchX = 0f
    internal var touchY = 0f
    internal var touchAmt = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    private val gpu = Build.VERSION.SDK_INT >= 31
    private val node: RenderNode? = if (Glass.BLUR && gpu) RenderNode("glass") else null
    private val density = ctx.resources.displayMetrics.density
    private var radius = 16f * density            // radius blur — mengikuti setelan [Glass.blurAmt] (lihat [syncBlur])
    private var mx = ctx.dp(24)                   // rekam lebih lebar & tinggi dari permukaan: tepi blur & refraksi
    private var my = ctx.dp(32)                   //   mengambil isi asli di sekitarnya
    private var appliedR = -1f                    // radius efek blur biasa yang sedang terpasang di node
    private val tint = Paint()
    private val line = Paint()
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val me = IntArray(2)
    private val them = IntArray(2)
    private val rel = Matrix()      // koordinat isi → koordinat permukaan ini
    private val relTmp = Matrix()
    private val relInv = Matrix()
    private var mode = 0            // efek di node: 0 belum, 1 blur biasa, 2 kaca cair
    private var fx: Any? = null     // LiquidFx (Android 13+)
    private val u = FloatArray(LiquidGlass.U)
    private var liquidNow = false
    private var rimKey = Long.MIN_VALUE

    // jalur perangkat lunak (Android 10–11)
    private var soft: Bitmap? = null
    private var softCanvas: Canvas? = null
    private var softBuf = IntArray(0)
    private var softTmp = IntArray(0)
    private var softOk = false
    private val pre = ViewTreeObserver.OnPreDrawListener { if (Glass.BLUR && !gpu) captureSoft(); true }

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                val top = revealTop()
                o.setRoundRect(0, top, v.width, v.height, min(corner, (v.height - top) / 2f))
            }
        }
    }

    /** Batas atas bentuk kaca saat [reveal] < 1 (px). */
    private fun revealTop(): Int = if (reveal >= 1f || corner <= 0f) 0 else ((1f - reveal) * height).roundToInt()

    /** Radius blur dari setelan; margin rekam ikut melebar untuk blur kuat agar tepinya tetap mengambil isi asli. */
    private fun syncBlur() {
        val r = Glass.blurPx(density)
        if (r == radius) return
        radius = r
        mx = max((24 * density).roundToInt(), (r * 1.2f).roundToInt())
        my = max((32 * density).roundToInt(), (r * 1.5f).roundToInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!gpu) viewTreeObserver.addOnPreDrawListener(pre)
        GlassLight.attach(this)
    }

    override fun onDetachedFromWindow() {
        if (!gpu) viewTreeObserver.removeOnPreDrawListener(pre)
        GlassLight.detach(this)
        node?.discardDisplayList()
        soft?.recycle(); soft = null; softCanvas = null; softOk = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); rimKey = Long.MIN_VALUE }

    /**
     * Kaca cair: anak kaca berubah (ikon, pil tab, riak sentuh) → gambar ulang SELURUH permukaan, bukan hanya area
     * anak itu. Efek lensa GPU hanya menghitung masukannya di area yang digambar ulang; tanpa ini, tepi area kecil itu
     * bisa mengambil sampel kosong (garis/bercak di kaca).
     */
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (mode == 2 && !healing) { healing = true; try { invalidate() } finally { healing = false } }
    }

    /** true selama permukaan ini menggambar ulang dirinya sendiri karena anaknya berubah (lihat [GlassScaffold]). */
    internal var healing = false
        private set

    /**
     * Matriks koordinat [content] → koordinat permukaan ini ([rel]), termasuk translasi & SKALA animasi keduanya.
     * Saat kapsul melentur (gerak gel, morph), skalanya dibatalkan untuk isi di belakang: latar buram tetap diam di
     * tempatnya dan selalu selaras dengan isi asli di sekeliling kaca — hanya bentuk kacanya yang bergerak.
     */
    private fun relate(c: View): Boolean {
        if (!c.isAttachedToWindow || c.width <= 0) return false
        if (c.parent === parent && parent != null) {
            relTmp.set(c.matrix); relTmp.postTranslate(c.left.toFloat(), c.top.toFloat())
            relInv.set(matrix); relInv.postTranslate(left.toFloat(), top.toFloat())
            if (relInv.invert(rel)) { rel.preConcat(relTmp); return true }
        }
        // cadangan (bukan bersaudara): posisi di jendela
        getLocationInWindow(me); c.getLocationInWindow(them)
        rel.setTranslate((them[0] - me[0]).toFloat(), (them[1] - me[1]).toFloat())
        return true
    }

    private fun drawGpu(canvas: Canvas, c: GlassContent): Boolean {
        val n = node ?: return false
        val cn = c.node ?: return false
        if (Build.VERSION.SDK_INT < 31 || c.direct || !canvas.isHardwareAccelerated) return false
        if (!cn.hasDisplayList()) { postInvalidateOnAnimation(); return false }
        if (!relate(c)) return false
        syncBlur()
        liquidNow = Build.VERSION.SDK_INT >= 33 && LiquidGlass.active() && liquid(n)
        if (!liquidNow && (mode != 1 || appliedR != radius)) { GlassFx31.apply(n, radius); mode = 1; appliedR = radius }
        n.setPosition(-mx, -my, width + mx, height + my)
        val rc = n.beginRecording(width + mx * 2, height + my * 2)
        try {
            rc.drawColor(baseColor ?: C.BG)
            rc.translate(mx.toFloat(), my.toFloat())
            rc.concat(rel)
            rc.drawRenderNode(cn)     // node isi yang sama dengan yang tampil di layar → selalu mutakhir
        } finally { n.endRecording() }
        canvas.save(); canvas.clipRect(0, revealTop(), width, height)
        canvas.drawRenderNode(n)
        canvas.restore()
        return true
    }

    /** Pasang (bila berubah) efek kaca cair ke node. false = jalur blur biasa. */
    @android.annotation.TargetApi(33)
    private fun liquid(n: RenderNode): Boolean {
        val f = fx as? LiquidFx ?: try { LiquidFx().also { fx = it } } catch (_: Throwable) { LiquidGlass.fail(); return false }
        return try {
            fillUniforms()
            if (f.update(u, radius) || mode != 2) { n.setRenderEffect(f.effect); mode = 2 }
            true
        } catch (_: Throwable) { LiquidGlass.fail(); mode = 0; false }
    }

    /** Nilai uniform shader (urutan: lihat [LiquidFx.update]). Semua dalam koordinat node (px). */
    private fun fillUniforms() {
        val d = resources.displayMetrics.density
        val top = revealTop().toFloat()
        val w = width.toFloat()
        val h = height - top
        val cap = corner > 0f
        val dark = C.dark
        u[0] = mx.toFloat(); u[1] = my + top
        u[2] = w; u[3] = h
        u[4] = if (cap) min(corner, h / 2f) else 0f
        u[5] = if (cap) 0f else if (edge > 0) -1f else 1f
        // zona tepi melengkung & pergeseran maksimum = setengahnya: lengkung lensa monoton (isi membesar di tepi,
        // tidak pernah terlipat/terbalik)
        val bezel = if (cap) min(18f * d, h * 0.42f) else 12f * d
        u[6] = bezel
        u[7] = bezel * 0.5f
        u[8] = if (Perf.low) 0f else 0.22f   // HP kelas bawah: satu sampel saja (lebih ringan)
        u[9] = GlassLight.x; u[10] = GlassLight.y
        val base = tintColor?.invoke() ?: (baseColor ?: C.BG)
        u[11] = Color.red(base) / 255f; u[12] = Color.green(base) / 255f; u[13] = Color.blue(base) / 255f
        u[14] = Glass.toneTint(liquidTint ?: if (cap) (if (dark) 0.36f else 0.22f) else (if (dark) 0.5f else 0.42f))
        u[15] = if (dark) 1f else 0f
        u[16] = mx + touchX; u[17] = my + touchY; u[18] = touchAmt; u[19] = 44f * d
        u[20] = if (dark) 0.8f else 1f
        u[21] = d
        // Batas sampel = area kaca yang TAMPIL (bukan seluruh node rekaman). Efek GPU hanya menghitung masukannya di
        // dalam area yang digambar; sampel di luar itu bernilai kosong (hitam transparan) — penyebab garis sian &
        // potongan abu di tepi bawah. Diberi jarak 1,5 px dari tepi agar tidak menyentuh piksel tepi yang di-antialias.
        u[22] = mx + 1.5f; u[23] = my + top + 1.5f; u[24] = mx + w - 1.5f; u[25] = my + height - 1.5f
        // pita kontras adaptif dari setelan transparansi (bening ↔ pekat)
        u[26] = Glass.toneFloor(); u[27] = Glass.toneCap()
    }

    /** Android 10–11: gambar isi ke bitmap kecil + blur, dipanggil tepat sebelum tiap frame. */
    private fun captureSoft() {
        val c = content ?: return
        if (blocked || Perf.low || c.direct || width <= 0 || height <= 0 || !isShown || !relate(c)) { softOk = false; return }
        syncBlur()
        try {
            val sc = 8
            val bw = (width + sc - 1) / sc; val bh = (height + my * 2 + sc - 1) / sc
            var b = soft
            if (b == null || b.width != bw || b.height != bh) {
                b?.recycle()
                b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); soft = b; softCanvas = Canvas(b)
                softBuf = IntArray(bw * bh); softTmp = IntArray(bw * bh)
                softOk = false
            }
            val k = softCanvas!!
            val save = k.save()
            try {
                k.scale(1f / sc, 1f / sc)
                k.drawColor(baseColor ?: C.BG)
                k.translate(0f, my.toFloat())
                k.concat(rel)
                c.draw(k)
            } finally { k.restoreToCount(save) }
            val br = Glass.softRadius()
            if (br > 0) {
                b.getPixels(softBuf, 0, bw, 0, 0, bw, bh)
                repeat(2) { boxBlur(softBuf, softTmp, bw, bh, br) }
                b.setPixels(softBuf, 0, bw, 0, 0, bw, bh)
            }
            if (!softOk) { softOk = true; invalidate() }   // sekali saja, agar bitmap tertaut ke kanvas kita
        } catch (_: Throwable) {
            blocked = true; softOk = false   // mis. gambar "hardware bitmap" tidak bisa digambar perangkat lunak
        }
    }

    private fun drawSoft(canvas: Canvas): Boolean {
        val b = soft ?: return false
        if (!softOk || blocked) return false
        canvas.save(); canvas.clipRect(0, revealTop(), width, height)
        canvas.translate(0f, -my.toFloat()); canvas.scale(8f, 8f)
        canvas.drawBitmap(b, 0f, 0f, bmpPaint)
        canvas.restore()
        return true
    }

    /** Blur kotak terpisah (mendatar lalu tegak) dengan jumlah berjalan — O(piksel), bitmapnya hanya puluhan baris. */
    private fun boxBlur(px: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
        val d = 2 * r + 1
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0; val o = y * w
            for (i in -r..r) { val p = px[o + i.coerceIn(0, w - 1)]; sr += p shr 16 and 255; sg += p shr 8 and 255; sb += p and 255 }
            for (x in 0 until w) {
                tmp[o + x] = -0x1000000 or (sr / d shl 16) or (sg / d shl 8) or (sb / d)
                val a = px[o + (x + r + 1).coerceAtMost(w - 1)]; val z = px[o + (x - r).coerceAtLeast(0)]
                sr += (a shr 16 and 255) - (z shr 16 and 255); sg += (a shr 8 and 255) - (z shr 8 and 255); sb += (a and 255) - (z and 255)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val p = tmp[i.coerceIn(0, h - 1) * w + x]; sr += p shr 16 and 255; sg += p shr 8 and 255; sb += p and 255 }
            for (y in 0 until h) {
                px[y * w + x] = -0x1000000 or (sr / d shl 16) or (sg / d shl 8) or (sb / d)
                val a = tmp[(y + r + 1).coerceAtMost(h - 1) * w + x]; val z = tmp[(y - r).coerceAtLeast(0) * w + x]
                sr += (a shr 16 and 255) - (z shr 16 and 255); sg += (a shr 8 and 255) - (z shr 8 and 255); sb += (a and 255) - (z and 255)
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val dark = C.dark
        val c = content
        val top = revealTop().toFloat()
        val w = width.toFloat()
        val h = height.toFloat()
        val base = tintColor?.invoke()
        liquidNow = false
        // Asuransi: lapisan kaca dasar digambar lebih dulu. Kaca (blur maupun cair) yang tergambar menutupinya penuh;
        // bila efek GPU gagal di HP tertentu, bilah tetap punya latar yang terbaca — tidak pernah tembus kosong.
        if (base != null && gpu && Glass.BLUR && !blocked) {
            tint.color = Color.argb(tintAlpha ?: 200, Color.red(base), Color.green(base), Color.blue(base))
            canvas.drawRect(0f, top, w, h, tint)
        }
        val blurOn = Glass.BLUR && c != null && !blocked && width > 0 && height > 0 &&
            (try { if (gpu) drawGpu(canvas, c) else drawSoft(canvas) } catch (_: Throwable) { blocked = true; false })
        if (!liquidNow) {
            // jalur biasa: tint + tepi kaca, cahaya dalam & cahaya sentuh digambar kanvas (arah cahaya tetap dari [GlassLight])
            base?.let { b ->
                // tanpa blur: pekat agar terbaca. Dengan blur: alfa bawaan digeser setelan transparansi (bening ↔ pekat)
                val a = if (!blurOn) 240 else Glass.toneAlpha(tintAlpha ?: if (corner > 0f) (if (dark) 120 else 128) else (if (dark) 146 else 152))
                tint.color = Color.argb(a, Color.red(b), Color.green(b), Color.blue(b))
                canvas.drawRect(0f, top, w, h, tint)
            }
            drawLight(canvas, top, dark)
        }
        super.dispatchDraw(canvas)
    }

    /** Tepi kaca, cahaya dalam & cahaya sentuh untuk HP tanpa shader (Android 12 ke bawah / kaca cair mati). */
    private fun drawLight(canvas: Canvas, top: Float, dark: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (corner > 0f) {
            val r = min(corner, (h - top) / 2f)
            val key = (w.toLong() shl 40) xor (h.toLong() shl 24) xor (top.toLong() shl 8) xor
                ((GlassLight.x * 1000).toLong() shl 2) xor (GlassLight.y * 1000).toLong() xor (if (dark) 1L else 0L)
            if (key != rimKey) {
                rimKey = key
                // gradien sepanjang arah cahaya: sisi yang menghadap cahaya terang, sisi seberang redup
                val cx = w / 2f; val cy = (top + h) / 2f; val ext = max(w, h - top) / 2f
                val x0 = cx + GlassLight.x * ext; val y0 = cy + GlassLight.y * ext
                val x1 = cx - GlassLight.x * ext; val y1 = cy - GlassLight.y * ext
                fun white(a: Int) = Color.argb(a, 255, 255, 255)
                rim.shader = LinearGradient(x0, y0, x1, y1,
                    intArrayOf(white(if (dark) 110 else 220), white(if (dark) 16 else 40), white(if (dark) 44 else 96)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                rim.strokeWidth = dp(1).toFloat()
                inner.shader = LinearGradient(x0, y0, x1, y1,
                    intArrayOf(white(if (dark) 24 else 44), white(0), white(if (dark) 8 else 14)),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                inner.strokeWidth = dp(6).toFloat()
            }
            if (!dark) {   // garis luar tipis: batas kapsul tetap jelas di atas latar putih
                line.style = Paint.Style.STROKE; line.strokeWidth = 1f; line.isAntiAlias = true
                line.color = Color.argb(28, 0, 0, 0)
                canvas.drawRoundRect(0.5f, top + 0.5f, w - 0.5f, h - 0.5f, r, r, line)
                line.style = Paint.Style.FILL
            }
            val gi = inner.strokeWidth / 2f
            canvas.drawRoundRect(gi, top + gi, w - gi, h - gi, max(0f, r - gi), max(0f, r - gi), inner)
            val i = rim.strokeWidth / 2f
            canvas.drawRoundRect(i, top + i, w - i, h - i, max(0f, r - i), max(0f, r - i), rim)
        } else if (edge != 0) {
            line.style = Paint.Style.FILL
            line.color = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(38, 20, 30, 60)
            val y = if (edge < 0) h - 1f else 0f
            canvas.drawRect(0f, y, w, y + 1f, line)
        }
        if (touchAmt > 0.01f) {
            val rr = dp(70).toFloat()
            touchPaint.shader = RadialGradient(touchX, touchY, rr,
                Color.argb((touchAmt * (if (dark) 40 else 70)).roundToInt().coerceIn(0, 255), 255, 255, 255),
                Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, top, w, h, touchPaint)
        }
    }
}

/** Pembuatan efek GPU dipisah agar kelas Android 12 tidak disentuh di perangkat yang lebih lama. */
@android.annotation.TargetApi(31)
private object GlassFx31 {
    /** Blur + saturasi 160%: blur saja membuat warna abu keruh, saturasi mengembalikan "hidup"-nya. */
    fun apply(n: RenderNode, radius: Float) {
        val sat = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.6f) })
        // blur nyaris nol (setelan "tajam"): cukup saturasi, tanpa efek blur
        if (radius < 1f) { n.setRenderEffect(RenderEffect.createColorFilterEffect(sat)); return }
        val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR)
        n.setRenderEffect(RenderEffect.createColorFilterEffect(sat, blur))
    }
}

/** Bilah navigasi kaca: [GlassSurface] berisi tumpukan vertikal anak-anak bilah. */
@SuppressLint("ViewConstructor")
class GlassBar(ctx: Context, private val atTop: Boolean) : GlassSurface(ctx) {
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    init {
        addView(box, LayoutParams(MATCH, WRAP))
        edge = if (atTop) -1 else 1
        // Warna kaca mengikuti bilah sistem di sebelahnya (atas = latar halaman, bawah = permukaan) agar menyatu.
        // Kapsul melayang di tema terang memakai abu lembut supaya tetap terlihat di atas halaman putih.
        tintColor = { if (atTop) C.BG else if (corner > 0f && !C.dark) C.SURFACE2 else C.SURFACE }
        // Latar buram milik anak-anak bilah dibuang supaya kacanya terlihat; garis pemisah digambar sendiri.
        box.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) { child?.let { strip(it) } }
            override fun onChildViewRemoved(parent: View?, child: View?) {}
        })
    }

    /** Latar netral dibuang (kaca terlihat); latar berwarna (mis. bilah judul biru) menjadi kaca berwarna. */
    private fun strip(v: View) {
        val bg = v.background
        if (bg !is ColorDrawable || Color.alpha(bg.color) != 255) return
        val c = bg.color
        if (c == C.BG || c == C.SURFACE || c == C.SURFACE2 || c == C.SURFACE3 || c == C.CANVAS) v.background = null
        else v.setBackgroundColor(Color.argb(196, Color.red(c), Color.green(c), Color.blue(c)))
    }

    private var glow: ValueAnimator? = null

    /** Cahaya sentuh menebal / memudar ke [target]. */
    private fun glowTo(target: Float, dur: Long) {
        glow?.cancel()
        if (!Motion.on()) { touchAmt = 0f; return }
        val a = ValueAnimator.ofFloat(touchAmt, target)
        a.duration = dur
        a.interpolator = Motion.STANDARD
        a.addUpdateListener { touchAmt = it.animatedValue as Float }
        glow = a
        a.start()
    }

    /**
     * Kapsul melayang bereaksi seperti gel: saat ditekan sedikit melebar & memipih (condong ke arah jari), menyala
     * dari titik sentuh, cahayanya mengikuti jari selama digeser, lalu kembali mulus saat dilepas — tanpa memantul.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (corner > 0f) when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = ev.x; touchY = ev.y
                glowTo(1f, Motion.PRESS_DOWN + 40L)
                if (abs(scaleX - 1f) < 0.002f) { pivotX = width / 2f + (ev.x - width / 2f) * 0.4f; pivotY = height / 2f }
                // kaca digambar ulang tiap frame selama bergerak: isi buram di dalamnya tetap selaras dengan layar
                animate().scaleX(1.03f).scaleY(0.985f).setStartDelay(0).setDuration(Motion.PRESS_DOWN + 40L)
                    .setInterpolator(Motion.STANDARD).setUpdateListener { invalidate() }.start()
            }
            MotionEvent.ACTION_MOVE -> { touchX = ev.x; touchY = ev.y; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                glowTo(0f, Motion.SLOW)
                animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(Motion.MED).setInterpolator(Motion.STANDARD)
                    .setUpdateListener { invalidate() }.start()
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * Pratinjau setelan kaca: gambar warna-warni + kapsul kaca SUNGGUHAN di atasnya (jalur render yang sama dengan bilah,
 * navigasi & notifikasi), jadi penggeser transparansi & blur langsung terlihat hasilnya. Kapsul bisa disentuh.
 */
@SuppressLint("ViewConstructor")
class GlassPreview(ctx: Context, icons: List<Int>, label: String) : FrameLayout(ctx) {
    private val art = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.WHITE }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // latar bergradasi warna merek + bentuk & teks tajam: blur dan tingkat bening terbaca jelas di kapsul
            p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(C.DOC_FG, C.IMG_FG, C.PDF_FG), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
            p.color = C.XLS_FG; c.drawCircle(w * 0.14f, h * 0.22f, h * 0.40f, p)
            p.color = C.AMBER_FILL; c.drawCircle(w * 0.90f, h * 0.86f, h * 0.34f, p)
            p.color = Color.argb(64, 255, 255, 255); c.drawCircle(w * 0.62f, h * 0.10f, h * 0.24f, p)
            txt.textSize = min(h * 0.30f, w * 0.13f)
            val t = "Depup Office"
            c.drawText(t, (w - txt.measureText(t)) / 2f, h * 0.5f + txt.textSize * 0.36f, txt)
            // baris "dokumen" tipis di bawah: detail halus untuk menilai kekuatan blur
            p.color = Color.argb(150, 255, 255, 255)
            val lh = h * 0.045f
            var y = h * 0.74f
            for (k in 0 until 3) {
                val lw = w * (if (k == 2) 0.34f else 0.56f)
                c.drawRoundRect((w - lw) / 2f, y, (w + lw) / 2f, y + lh, lh / 2f, lh / 2f, p)
                y += lh * 2.2f
            }
        }
    }
    val content = GlassContent(ctx)
    val capsule = GlassBar(ctx, false)

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.dp(18).toFloat()) }
        }
        clipToOutline = true
        content.addView(art, LayoutParams(MATCH, MATCH))
        addView(content, LayoutParams(MATCH, MATCH))
        capsule.content = content
        capsule.corner = ctx.dp(26).toFloat()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(18), 0, ctx.dp(20), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        for ((i, r) in icons.withIndex()) row.addView(ctx.icon(r, C.TEXT, 22),
            LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)).apply { marginStart = if (i == 0) 0 else ctx.dp(16) })
        row.addView(ctx.tv(label, 14f, C.TEXT, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(14) })
        capsule.box.addView(row, LinearLayout.LayoutParams(WRAP, ctx.dp(52)))
        addView(capsule, LayoutParams(WRAP, ctx.dp(52), Gravity.CENTER))
        contentDescription = "Pratinjau kaca"
    }
}

/** ScrollView yang tahu sebagian areanya tertutup bilah kaca: kursor/fokus tidak digulir ke balik bilah. */
open class GlassScrollView(ctx: Context) : ScrollView(ctx) {
    private val tmp = Rect()
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (clipToPadding || (paddingTop == 0 && paddingBottom == 0)) return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        tmp.set(rect.left, rect.top - paddingTop, rect.right, rect.bottom + paddingBottom)
        return super.computeScrollDeltaToGetChildRectOnScreen(tmp)
    }
}

/**
 * Kerangka layar: isi memenuhi layar, bilah atas & bawah melayang di atasnya sebagai kaca.
 * Jarak aman (padding) untuk isi dihitung otomatis: bila bagian teratas/terbawah isi adalah daftar gulir,
 * daftar itu yang diberi padding (isinya lewat di bawah kaca); bila bukan, wadah isinya yang digeser.
 */
@SuppressLint("ViewConstructor")
class GlassScaffold(ctx: Context, val content: View, private val floatBottom: Boolean = false) : FrameLayout(ctx) {
    val topBar = GlassBar(ctx, true)
    val bottomBar = GlassBar(ctx, false)
    val top: LinearLayout get() = topBar.box
    val bottom: LinearLayout get() = bottomBar.box

    /** Paksa kaca pekat tanpa blur (uji performa). */
    var forceDirect = false
        set(v) { field = v; requestLayout(); wholeGlass() }

    /** Kapsul kaca tambahan yang melayang tepat di atas navigasi bawah (mis. "Lanjutkan dokumen"). */
    val miniBar = GlassBar(ctx, false)

    /** Layar yang isinya tidak pernah kosong polos (beranda): area isi di antara bilah ikut diawasi [GlassGuard]. */
    var watchContent = false

    /**
     * Untuk [GlassGuard]: area yang diperiksa, dalam koordinat jendela. false = jangan periksa sekarang (mis. bilah
     * sedang disembunyikan — tanpa bilah, layar polos bisa saja wajar, contohnya halaman PDF kosong).
     */
    fun probeArea(out: Rect): Boolean {
        if (!isAttachedToWindow || !isShown || width <= 0 || height <= 0) return false
        fun shown(b: GlassBar) = b.visibility == View.VISIBLE && b.height > 0 && b.alpha > 0.5f && abs(b.translationY) < b.height / 2f
        val topOn = shown(topBar); val botOn = shown(bottomBar)
        if (!topOn && !botOn) return false
        val loc = IntArray(2); getLocationInWindow(loc)
        if (!watchContent) { out.set(loc[0], loc[1], loc[0] + width, loc[1] + height); return true }
        val t = (if (topOn) topBar.bottom else 0) + dp(4)
        var b = if (botOn) bottomBar.top else height
        if (miniBar.visibility == View.VISIBLE && miniBar.height > 0) b = minOf(b, miniBar.top)
        b -= dp(28)   // bayangan lembut kapsul melayang tidak ikut terhitung
        if (b - t < dp(160)) return false
        out.set(loc[0], loc[1] + t, loc[0] + width, loc[1] + b)
        return true
    }

    /** Isi berganti (mis. pindah tab): rekam ulang sumber kaca & gambar ulang semua bilah. */
    fun refresh() {
        wrap.invalidate(); topBar.invalidate(); bottomBar.invalidate(); miniBar.invalidate()
        post { wrap.invalidate(); topBar.invalidate(); bottomBar.invalidate(); miniBar.invalidate() }
    }

    private var morph: ValueAnimator? = null

    /**
     * Pasang / ganti / lepas (null) isi kapsul tambahan. Muncul & hilang dengan MORPH, bukan memudar: kapsul tumbuh
     * dari arah navigasi (bentuk kacanya memanjang ke atas, isinya terungkap mengikuti), dan menyusut kembali ke sana.
     */
    fun setAccessory(v: View?) {
        val wasHidden = miniBar.visibility != View.VISIBLE
        morph?.cancel(); morph = null
        if (v == null) {
            if (wasHidden) { miniBar.box.removeAllViews(); return }
            if (!Motion.on() || !isAttachedToWindow) { hideAccessory(); return }
            morphAccessory(false) { hideAccessory() }
            return
        }
        miniBar.box.removeAllViews()
        miniBar.box.addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
        miniBar.visibility = View.VISIBLE
        post { applyInsets() }
        if (wasHidden && Motion.on()) morphAccessory(true) {} else settleAccessory()
    }

    private fun hideAccessory() {
        miniBar.box.removeAllViews()
        miniBar.visibility = View.GONE
        settleAccessory()
        post { applyInsets() }
    }

    private fun settleAccessory() {
        miniBar.reveal = 1f; miniBar.translationY = 0f; miniBar.box.alpha = 1f
        miniBar.invalidate()
    }

    /** Morph kapsul: [grow] = tumbuh dari bentuk pipih dekat navigasi; false = menyusut kembali ke sana. */
    private fun morphAccessory(grow: Boolean, done: () -> Unit) {
        val low = 0.32f
        val drop = dp(14).toFloat()
        val from = if (grow) 0f else 1f
        val to = if (grow) 1f else 0f
        if (grow) { miniBar.reveal = low; miniBar.translationY = drop; miniBar.box.alpha = 0f }
        val a = ValueAnimator.ofFloat(from, to)
        a.duration = if (grow) Motion.SLOW else Motion.MED
        a.interpolator = Motion.STANDARD
        a.addUpdateListener {
            val f = it.animatedValue as Float
            miniBar.reveal = low + (1f - low) * f
            miniBar.translationY = drop * (1f - f)
            miniBar.box.alpha = ((f - 0.35f) / 0.65f).coerceIn(0f, 1f)
            miniBar.invalidate()   // isi buram & bentuk kaca ikut selaras tiap frame
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (morph === animation) morph = null
                if (cancelled) return
                if (grow) settleAccessory()
                done()
            }
        })
        morph = a
        a.start()
    }

    /**
     * Kedalaman adaptif: bayangan kapsul melayang lebih pekat di atas isi yang ramai, lebih tipis di atas bidang polos.
     * [busy] 0..1 diukur [GlassGuard] dari keragaman warna isi yang tampil.
     */
    fun setDepth(busy: Float) {
        if (Build.VERSION.SDK_INT < 28) return
        val b = busy.coerceIn(0f, 1f)
        for (bar in listOf(bottomBar, miniBar)) if (bar.corner > 0f) {
            bar.outlineSpotShadowColor = Color.argb(shadowA(0x1C + (0x40 - 0x1C) * b), 0, 0, 0)
            bar.outlineAmbientShadowColor = Color.argb(shadowA(0x10 + (0x22 - 0x10) * b), 0, 0, 0)
        }
    }

    /** Alfa bayangan luar kapsul. Tema gelap lebih tipis: bayangan hitam di atas latar gelap hanya membuat tepi keruh. */
    private fun shadowA(a: Float): Int = (if (C.dark) a * 0.6f else a).roundToInt().coerceIn(0, 255)

    /**
     * Tepi atas kaca bawah tertinggi yang sedang tampil (koordinat jendela), atau null. Notifikasi kapsul diletakkan
     * di atasnya — tidak ada kaca di atas kaca.
     */
    fun floatingTop(): Int? {
        val loc = IntArray(2)
        var best: Int? = null
        for (b in listOf(miniBar, bottomBar)) {
            if (b.visibility != View.VISIBLE || b.height <= 0 || !b.isShown) continue
            b.getLocationInWindow(loc)
            val t = loc[1] + (b.height * (1f - b.reveal)).roundToInt()
            if (best == null || t < best) best = t
        }
        return best
    }

    private val orig = WeakHashMap<View, IntArray>()   // padding asli: atas, bawah, clipToPadding(1/0)
    private val pinned = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /**
     * Halaman yang SELALU diberi jarak aman, walau sedang tersembunyi (halaman tab yang disimpan untuk dipakai
     * ulang). Jarak diberikan saat itu juga — halaman tidak pernah tampil sekejap di balik bilah, dan tidak perlu
     * ditata ulang ketika ditampilkan.
     */
    fun pin(v: View) {
        pinned.add(v)
        if (!isAttachedToWindow) return
        val s = barSpace()
        if (s[0] != 0 || s[1] != 0) pad(v, s[0], s[1], true)
    }

    fun unpin(v: View) {
        pinned.remove(v); orig.remove(v)
        if (topTarget === v) topTarget = null
        if (bottomTarget === v) bottomTarget = null
    }

    /** Tinggi yang tertutup bilah atas & bawah (px), termasuk kapsul tambahan. */
    private fun barSpace(): IntArray {
        val th = if (top.childCount > 0) topBar.height else 0
        var bh = if (bottom.childCount > 0) bottomBar.height + (if (floatBottom) gap * 2 else 0) else 0
        if (miniBar.visibility == View.VISIBLE && miniBar.box.childCount > 0)
            bh = bh + (if (floatBottom) -gap + dp(8) else dp(8)) + miniBar.height + dp(4)
        return intArrayOf(th, bh)
    }
    private var topTarget: View? = null
    private var bottomTarget: View? = null
    private var applying = false
    /** Pembungkus isi: sumber kaca untuk bilah & notifikasi. */
    private val wrap = GlassContent(ctx)
    private val gap = if (floatBottom) ctx.dp(12) else 0
    private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { applyInsets() }

    init {
        wrap.addView(content, LayoutParams(MATCH, MATCH))
        addView(wrap, LayoutParams(MATCH, MATCH))
        addView(topBar, LayoutParams(MATCH, WRAP, Gravity.TOP))
        addView(bottomBar, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(gap, 0, gap, gap * 5 / 6) })
        addView(miniBar, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(ctx.dp(12), 0, ctx.dp(12), 0) })
        miniBar.visibility = View.GONE
        miniBar.corner = ctx.dp(22).toFloat()
        topBar.content = wrap; bottomBar.content = wrap; miniBar.content = wrap
        miniBar.isClickable = true
        if (floatBottom) bottomBar.corner = ctx.dp(30).toFloat()
        // bayangan lembut di bawah kapsul melayang (seperti referensi), bukan garis keras
        for (b in listOf(bottomBar, miniBar)) if (b.corner > 0f) {
            b.elevation = ctx.dp(8).toFloat()
            if (Build.VERSION.SDK_INT >= 28) {
                b.outlineAmbientShadowColor = Color.argb(shadowA(0x18.toFloat()), 0, 0, 0)
                b.outlineSpotShadowColor = Color.argb(shadowA(0x2C.toFloat()), 0, 0, 0)
            }
        }
        // sentuhan di area bilah tidak boleh tembus ke isi di bawahnya
        topBar.isClickable = true; bottomBar.isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(onLayout)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
        removeCallbacks(holdTick); holdUntil = 0L
        super.onDetachedFromWindow()
    }

    /** Gambar ulang semua kaca yang tampil secara UTUH. */
    fun wholeGlass() {
        val was = fanning
        fanning = true
        try {
            if (topBar.visibility == View.VISIBLE) topBar.invalidate()
            if (bottomBar.visibility == View.VISIBLE) bottomBar.invalidate()
            if (miniBar.visibility == View.VISIBLE) miniBar.invalidate()
        } finally { fanning = was }
    }

    /** Semua kaca yang tampil memang sudah akan digambar ulang di frame berikutnya. */
    private fun glassDirty(): Boolean =
        (topBar.visibility != View.VISIBLE || topBar.isDirty) &&
            (bottomBar.visibility != View.VISIBLE || bottomBar.isDirty) &&
            (miniBar.visibility != View.VISIBLE || miniBar.isDirty)

    private val tmpRect = Rect()
    private val tmpLoc = IntArray(2)
    private val reach = ctx.dp(40)   // jangkauan blur (3 sigma) + refraksi: perubahan sejauh ini ikut tampil di kaca

    /** Kaca yang berada dalam jangkauan [target] digambar ulang utuh. true bila ada kaca yang terjangkau. */
    private fun fanOut(target: View): Boolean {
        val r = tmpRect
        val visible = try { target.getGlobalVisibleRect(r) } catch (_: Throwable) { false }
        if (!visible) return false
        getLocationInWindow(tmpLoc)
        r.offset(-tmpLoc[0], -tmpLoc[1])
        r.inset(-reach, -reach)
        var hit = false
        for (b in arrayOf(topBar, bottomBar, miniBar)) {
            if (b.visibility != View.VISIBLE) continue
            val l = (b.left + b.translationX).toInt(); val t = (b.top + b.translationY).toInt()
            if (r.intersects(l, t, l + b.width, t + b.height)) { hit = true; b.invalidate() }
        }
        return hit
    }

    private fun ripples(v: View): Boolean =
        v.background is RippleDrawable || (Build.VERSION.SDK_INT >= 23 && v.foreground is RippleDrawable)

    private var fanning = false

    /**
     * Kaca cair: efek lensa GPU hanya menghitung masukannya di area yang sedang digambar ulang. Bila sebagian kecil
     * layar di dekat kaca berubah (angka beranimasi di balik kapsul, ikon di dalam bilah, kapsul tetangga yang sedang
     * morph), kaca itu digambar ulang UTUH — tidak pernah setengah. Perubahan yang jauh dari kaca tidak menyentuhnya.
     * Riak sentuh (ripple) dianimasikan langsung oleh RenderThread tanpa lewat sini, jadi selama riak di dekat kaca
     * berjalan, kaca dijaga utuh tiap frame ([holdWhole]).
     */
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (fanning || !LiquidGlass.active()) return
        val rippling = ripples(target)
        // tampilan DI DALAM sebuah bilah (ikon, pil tab): bilah itu sendiri digambar ulang utuh ([GlassSurface])
        if (child !== wrap && child !== target) { if (rippling) holdWhole(1200L); return }
        if ((target as? GlassSurface)?.healing == true) return
        if (!rippling && glassDirty()) return
        fanning = true
        try { if (fanOut(target) && rippling) holdWhole(1200L) } finally { fanning = false }
    }

    private var holdUntil = 0L
    private val holdTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !LiquidGlass.active()) { holdUntil = 0L; return }
            wholeGlass()
            if (SystemClock.uptimeMillis() < holdUntil) postOnAnimation(this) else holdUntil = 0L
        }
    }

    /** Jaga kaca tetap digambar utuh tiap frame selama [ms] ke depan (riak sentuh, animasi notifikasi kapsul). */
    fun holdWhole(ms: Long) {
        if (!LiquidGlass.active() || !isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        val running = holdUntil > now
        holdUntil = max(holdUntil, now + ms)
        if (!running) postOnAnimation(holdTick)
    }

    // Diperiksa dengan tipe, bukan nama kelas: di build rilis R8 bisa menyamarkan nama kelas pustaka (RecyclerView
    // jadi "a.b.c"), sehingga pencocokan nama diam-diam gagal.
    private fun isScroller(v: View): Boolean =
        v is ScrollView || v is AbsListView || v is androidx.recyclerview.widget.RecyclerView || v is androidx.core.widget.NestedScrollView

    private fun unrecordable(v: View, depth: Int = 0): Boolean {
        if (v.visibility != View.VISIBLE) return false   // halaman tersembunyi tidak ikut diperiksa (lebih ringan)
        if (v is WebView || v is SurfaceView || v is TextureView) return true
        if (v is ViewGroup && depth < 6) for (i in 0 until v.childCount) if (unrecordable(v.getChildAt(i), depth + 1)) return true
        return false
    }

    /** Telusuri dari wadah isi ke anak paling atas / paling bawah sampai ketemu daftar gulir. */
    private fun findTarget(fromTop: Boolean): View? {
        var cur: View = content
        if (isScroller(cur)) return cur
        for (depth in 0 until 8) {
            val g = cur
            if (isScroller(g)) return g
            if (g is WebView || g !is ViewGroup) return null
            val kids = ArrayList<View>()
            for (i in 0 until g.childCount) g.getChildAt(i).let { if (it.visibility == View.VISIBLE) kids.add(it) }
            cur = when {
                g is LinearLayout && g.orientation == LinearLayout.VERTICAL -> (if (fromTop) kids.firstOrNull() else kids.lastOrNull()) ?: return null
                g is LinearLayout -> return null
                else -> kids.firstOrNull { it.layoutParams?.height == MATCH } ?: return null
            }
        }
        return null
    }

    private fun restore(v: View?) {
        if (v == null || v in pinned) return   // halaman tersimpan tetap berjarak aman
        val o = orig.remove(v) ?: return
        v.setPadding(v.paddingLeft, o[0], v.paddingRight, o[1])
        (v as? ViewGroup)?.clipToPadding = o[2] == 1
    }

    private fun pad(v: View, topPx: Int?, bottomPx: Int?, scroller: Boolean) {
        val o = orig.getOrPut(v) { intArrayOf(v.paddingTop, v.paddingBottom, if ((v as? ViewGroup)?.clipToPadding != false) 1 else 0) }
        val t = if (topPx != null) o[0] + topPx else v.paddingTop
        val b = if (bottomPx != null) o[1] + bottomPx else v.paddingBottom
        if (t != v.paddingTop || b != v.paddingBottom) v.setPadding(v.paddingLeft, t, v.paddingRight, b)
        if (scroller && v is ViewGroup && v.clipToPadding) v.clipToPadding = false
    }

    private fun applyInsets() {
        if (applying || !isAttachedToWindow) return
        applying = true
        try {
            val hasTop = top.childCount > 0; val hasBottom = bottom.childCount > 0
            topBar.visibility = if (hasTop) View.VISIBLE else View.GONE
            bottomBar.visibility = if (hasBottom) View.VISIBLE else View.GONE
            val th = if (hasTop) topBar.height else 0
            var bh = if (hasBottom) bottomBar.height + (if (floatBottom) gap * 2 else 0) else 0
            if (miniBar.visibility == View.VISIBLE && miniBar.box.childCount > 0) {
                // kapsul tambahan duduk 8dp di atas navigasi
                val mlp = miniBar.layoutParams as LayoutParams
                val want = bh + (if (floatBottom) -gap + dp(8) else dp(8))
                if (mlp.bottomMargin != want) { mlp.bottomMargin = want; miniBar.layoutParams = mlp }
                bh = want + miniBar.height + dp(4)
            }
            // Daftar gulir → diberi padding (isinya lewat di bawah kaca). Selain itu → isi digeser dengan margin,
            // aman untuk tampilan khusus yang tidak mengenal padding (kanvas coretan, WebView, dst.).
            val tt = findTarget(true)
            val bt = findTarget(false)
            if (topTarget !== tt && topTarget !== bt) restore(topTarget)
            if (bottomTarget !== bt && bottomTarget !== tt) restore(bottomTarget)
            topTarget = tt; bottomTarget = bt
            if (tt != null && tt === bt) pad(tt, th, bh, true)
            else { tt?.let { pad(it, th, null, true) }; bt?.let { pad(it, null, bh, true) } }
            for (p in pinned) if (p.parent != null) pad(p, th, bh, true)
            val lp = wrap.layoutParams as LayoutParams
            val mt = if (tt == null) th else 0; val mb = if (bt == null) bh else 0
            if (lp.topMargin != mt || lp.bottomMargin != mb) { lp.topMargin = mt; lp.bottomMargin = mb; wrap.layoutParams = lp
                topBar.invalidate(); bottomBar.invalidate(); miniBar.invalidate() }
            val block = forceDirect || unrecordable(content)
            wrap.direct = block
            topBar.blocked = block; bottomBar.blocked = block; miniBar.blocked = block
        } finally { applying = false }
    }
}

object Glass {
    /**
     * Blur kaca waktu-nyata. Dibaca dari setelan saat aplikasi mulai dan bisa diubah pengguna di Saya → Tampilan.
     * Dimatikan otomatis oleh [GlassGuard] bila layar terdeteksi gagal tergambar, atau bila aplikasi tertutup paksa
     * karena galat di kode kaca — jadi pengguna tidak pernah terjebak di layar hitam/kosong.
     */
    @Volatile var BLUR = true
        private set
    /** true sekali setelah blur dimatikan otomatis — layar berikutnya yang tampil memberi tahu pengguna. */
    @Volatile var autoDisabled = false

    /**
     * Transparansi kaca, seperti penggeser Liquid Glass di iOS 27: 0 = paling bening … 1 = paling pekat (tinted).
     * [DEFAULT] = tampilan bawaan. Dibaca setiap kali kaca digambar, jadi perubahannya langsung terlihat.
     */
    @Volatile var tone = DEFAULT
        private set
    /** Kekuatan blur: 0 = hampir tanpa blur (kaca bening tajam) … 1 = sangat buram. [DEFAULT] = 16 dp seperti dulu. */
    @Volatile var blurAmt = DEFAULT
        private set
    const val DEFAULT = 0.5f

    /** Penggeser transparansi/blur bermakna di HP ini: blur menyala & benar-benar dirender (GPU, atau perangkat lunak
     *  di Android 10–11 yang bukan kelas bawah). Selain itu kaca selalu pekat. */
    val adjustable: Boolean get() = BLUR && (Build.VERSION.SDK_INT >= 31 || !Perf.low)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    /**
     * Ubah tampilan kaca. Semua kaca yang sedang tampil langsung digambar ulang (pratinjau hidup selama penggeser
     * ditarik); [save] = simpan ke setelan (cukup saat jari dilepas).
     */
    fun setLook(ctx: Context, tone: Float = this.tone, blur: Float = this.blurAmt, save: Boolean = true) {
        this.tone = tone.coerceIn(0f, 1f)
        blurAmt = blur.coerceIn(0f, 1f)
        if (save) sp(ctx).edit().putFloat("tone", this.tone).putFloat("blurAmt", blurAmt).apply()
        GlassLight.refreshAll()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
    /** Dua sisi dari titik bawaan: < bawaan menuju bening, > bawaan menuju pekat. */
    private fun twoWay(t: Float, clear: Float, def: Float, tinted: Float) =
        if (t <= DEFAULT) lerp(clear, def, t / DEFAULT) else lerp(def, tinted, (t - DEFAULT) / (1f - DEFAULT))

    /** Alfa lapisan warna kaca (jalur blur biasa) dari nilai bawaan [def] pada transparansi saat ini. */
    fun toneAlpha(def: Int): Int = twoWay(tone, def * 0.14f, def.toFloat(), 250f).roundToInt().coerceIn(0, 255)
    /** Kekuatan tint kaca cair dari nilai bawaan [def]. */
    fun toneTint(def: Float): Float = twoWay(tone, def * 0.08f, def, 0.94f)
    /**
     * Pita kontras adaptif kaca cair: kecerahan minimum kaca terang & maksimum kaca gelap. Makin bening, makin longgar
     * (isi di belakang makin terlihat apa adanya); makin pekat, makin rapat (kaca makin "susu"/gelap merata).
     */
    fun toneFloor(): Float = twoWay(tone, 0.34f, 0.7896f, 0.93f)
    fun toneCap(): Float = twoWay(tone, 0.36f, 0.1472f, 0.06f)
    /** Radius blur (px) dari [blurAmt]: ±1 dp … 16 dp (bawaan) … 44 dp. */
    fun blurPx(density: Float): Float = twoWay(blurAmt, 1f, 16f, 44f) * density
    /** Jari-jari blur kotak untuk bitmap 1/8 (Android 10–11 & notifikasi): 0 = tanpa blur, bawaan 2. */
    fun softRadius(): Int = (twoWay(blurAmt, 0f, 2f, 5.5f)).roundToInt().coerceIn(0, 6)

    /** Panggil sekali di Application.onCreate. */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        val p = sp(app)
        BLUR = p.getBoolean("blur", true)
        tone = p.getFloat("tone", DEFAULT).coerceIn(0f, 1f)
        blurAmt = p.getFloat("blurAmt", DEFAULT).coerceIn(0f, 1f)
        if (p.getBoolean("autoOff", false)) { autoDisabled = true; p.edit().remove("autoOff").apply() }
        // Galat yang berasal langsung dari kode kaca → dimatikan bertahap sebelum aplikasi tertutup: galat shader lensa
        // hanya mematikan kaca cair (blur biasa tetap), galat kaca lainnya mematikan blur.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                if (LiquidGlass.ON && fromLiquid(e)) LiquidGlass.disableNow(app)
                else if (BLUR && fromGlass(e)) sp(app).edit().putBoolean("blur", false).putBoolean("autoOff", true).commit()
            } catch (_: Throwable) {}
            if (prev != null) prev.uncaughtException(t, e) else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10) }
        }
    }

    /** Galat dari shader lensa (kaca cair). */
    private fun fromLiquid(e: Throwable): Boolean {
        var x: Throwable? = e
        var depth = 0
        while (x != null && depth++ < 8) {
            for (f in x.stackTrace.take(5)) {
                val c = f.className
                if (c.startsWith("cloud.depup.office.LiquidFx") || c.startsWith("cloud.depup.office.LiquidGlass") ||
                    c == "android.graphics.RuntimeShader" ||
                    (c.startsWith("cloud.depup.office.GlassSurface") && (f.methodName == "liquid" || f.methodName == "fillUniforms"))) return true
            }
            x = x.cause
        }
        return false
    }

    /** Hanya bila galat dilempar DI kode kaca (beberapa bingkai teratas), bukan sekadar lewat saat menggambar isi. */
    private fun fromGlass(e: Throwable): Boolean {
        var x: Throwable? = e
        var depth = 0
        while (x != null && depth++ < 8) {
            for (f in x.stackTrace.take(4)) {
                val c = f.className
                if (c.startsWith("cloud.depup.office.GlassContent") || c.startsWith("cloud.depup.office.GlassSurface") ||
                    c.startsWith("cloud.depup.office.GlassFx") || c == "android.graphics.RenderNode" || c == "android.graphics.RenderEffect") return true
            }
            x = x.cause
        }
        return false
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("blur", on).apply()
        BLUR = on
    }

    /** Dipanggil penjaga: matikan blur & beri tahu pengguna sekali. */
    fun disableAuto(ctx: Context) {
        setEnabled(ctx, false)
        autoDisabled = true
    }

    fun scaffold(root: LinearLayout, floatBottom: Boolean = false): View {
        if (root.orientation != LinearLayout.VERTICAL) return root
        val kids = (0 until root.childCount).map { root.getChildAt(it) }
        val ci = kids.indexOfFirst { ((it.layoutParams as? LinearLayout.LayoutParams)?.weight ?: 0f) > 0f }
        if (ci < 0) return root
        root.removeAllViews()
        val sc = GlassScaffold(root.context, kids[ci], floatBottom)
        sc.background = root.background
        (root.background as? ColorDrawable)?.color?.let { sc.topBar.baseColor = it; sc.bottomBar.baseColor = it; sc.miniBar.baseColor = it }
        val thin = root.context.dp(2)
        fun move(v: View, into: LinearLayout) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            // garis pemisah lama tidak dipakai lagi: kaca punya garis tepinya sendiri
            if (v.background is ColorDrawable && lp.height in 1..thin && (v !is ViewGroup || v.childCount == 0)) return
            into.addView(v, LinearLayout.LayoutParams(lp.width, lp.height).apply {
                setMargins(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin); gravity = lp.gravity
            })
        }
        for (i in 0 until ci) move(kids[i], sc.top)
        for (i in ci + 1 until kids.size) move(kids[i], sc.bottom)
        return sc
    }
}

/**
 * Penjaga kaca. Selama blur menyala, layar berkaca yang sedang tampil diperiksa sangat ringan: satu tangkapan
 * kecil (1/8 ukuran) tiap 1,5 detik, hanya saat aplikasi di depan, plus sekali tepat setelah pindah tab.
 * Layar berkaca selalu punya bilah, ikon & teks — bila tangkapannya ternyata SATU WARNA RATA, isi gagal tergambar
 * (gejala "layar mendadak hitam/kosong" di sebagian HP). Blur langsung dimatikan dan layar dibangun ulang tanpa
 * kaca, lalu pengguna diberi tahu — tidak pernah terjebak di layar kosong.
 */
object GlassGuard {
    private val main = Handler(Looper.getMainLooper())
    private var target: WeakReference<Activity>? = null
    private var strikes = 0
    private var busy = false
    private var waits = 0
    private val tick = Runnable { probe() }
    private const val EVERY = 3000L
    /** Pemeriksaan berturut-turut yang lolos: setelah kaca terbukti sehat, pemeriksaan dijarangkan (hemat GPU). */
    private var passes = 0
    private fun calm(): Long = if (passes >= 4) 9000L else EVERY
    /** Kapan layar terakhir digambar: tangkapan hanya diambil saat layar DIAM, supaya tidak pernah menyendat animasi. */
    @Volatile private var lastDraw = 0L
    private val drawn = ViewTreeObserver.OnDrawListener { lastDraw = android.os.SystemClock.uptimeMillis() }
    private var observed: WeakReference<View>? = null
    /** [lastDraw] saat pemeriksaan terakhir yang lolos: layar yang belum digambar ulang sejak itu tidak perlu disalin lagi. */
    private var okDraw = -1L

    fun start(a: Activity) {
        halt()
        if (!Glass.BLUR || Build.VERSION.SDK_INT < 31) return   // hanya jalur blur GPU yang diawasi
        target = WeakReference(a)
        a.window?.decorView?.let { d ->
            try { d.viewTreeObserver.addOnDrawListener(drawn); observed = WeakReference(d) } catch (_: Throwable) {}
        }
        main.postDelayed(tick, EVERY)
    }

    fun stop(a: Activity) { if (target?.get() === a) halt() }

    /** Periksa setelah pindah tab (momen gejala biasanya muncul) — begitu transisi selesai & layar diam. */
    fun poke() {
        if (target?.get() == null || busy) return
        main.removeCallbacks(tick); main.postDelayed(tick, 900L)
    }

    private fun halt() {
        target = null; strikes = 0; waits = 0; okDraw = -1L; main.removeCallbacks(tick)
        observed?.get()?.viewTreeObserver?.let { o -> try { if (o.isAlive) o.removeOnDrawListener(drawn) } catch (_: Throwable) {} }
        observed = null
    }

    private fun later(ms: Long = EVERY) {
        main.removeCallbacks(tick)
        if (target?.get() != null) main.postDelayed(tick, ms)
    }

    private fun probe() {
        val a = target?.get() ?: return
        if (!Glass.BLUR || a.isFinishing || a.isDestroyed) { halt(); return }
        if (busy) return later()
        // layar sedang bergerak (animasi/gulir): tunggu sampai diam — menyalin layar memakai GPU sesaat
        if (android.os.SystemClock.uptimeMillis() - lastDraw < 350L && waits < 6) { waits++; return later(400L) }
        waits = 0
        // tidak ada yang tergambar sejak pemeriksaan terakhir yang lolos → hasilnya pasti sama; lewati penyalinan layar
        val drawnAt = lastDraw
        if (strikes == 0 && drawnAt == okDraw) return later(calm())
        val w = a.window ?: return later()
        val decor = w.decorView
        val g = GlassContent.find(decor) ?: return later(3000L)   // layar tanpa kaca
        val sc = g.parent as? GlassScaffold
        val secure = (w.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        val src = Rect()
        if (g.node == null || g.direct || secure || sc == null || !decor.isAttachedToWindow || !a.hasWindowFocus() || !sc.probeArea(src)) return later()
        if (!src.intersect(0, 0, decor.width, decor.height) || src.width() < 16 || src.height() < 16) return later()
        val bmp = try { Bitmap.createBitmap(src.width() / 8, src.height() / 8, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { return later() }
        busy = true
        try {
            PixelCopy.request(w, src, bmp, { res ->
                busy = false
                val ok = res == PixelCopy.SUCCESS
                val flat = ok && isFlat(bmp)
                val busyness = if (ok && !flat) busyness(bmp) else -1f
                bmp.recycle()
                if (target?.get() !== a) return@request          // layar sudah berganti selama menyalin
                if (busyness >= 0f) sc.setDepth(busyness)        // kedalaman adaptif: bayangan ikut keramaian isi
                when {
                    !flat -> { strikes = 0; okDraw = drawnAt; passes++; later(calm()) }
                    ++strikes >= 2 -> { passes = 0; trip(a) }
                    else -> later(450L)   // pastikan sekali lagi: bukan sekadar satu frame peralihan
                }
            }, main)
        } catch (_: Throwable) { busy = false; bmp.recycle(); later() }
    }

    /** Semua piksel (per kanal warna) hampir sama = tidak ada teks/ikon/kartu yang tergambar. */
    private fun isFlat(b: Bitmap): Boolean {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var r0 = 255; var r1 = 0; var g0 = 255; var g1 = 0; var b0 = 255; var b1 = 0
        for (p in px) {
            val r = p shr 16 and 255; val g = p shr 8 and 255; val bl = p and 255
            if (r < r0) r0 = r; if (r > r1) r1 = r
            if (g < g0) g0 = g; if (g > g1) g1 = g
            if (bl < b0) b0 = bl; if (bl > b1) b1 = bl
        }
        return r1 - r0 <= 6 && g1 - g0 <= 6 && b1 - b0 <= 6
    }

    /** Keramaian isi 0..1: simpangan baku kecerahan (bidang polos ≈ 0, foto/kartu berwarna ramai → 1). */
    private fun busyness(b: Bitmap): Float {
        val n = b.width * b.height
        if (n <= 0) return 0f
        val px = IntArray(n)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var sum = 0.0; var sq = 0.0
        for (p in px) {
            val l = 0.2126 * (p shr 16 and 255) + 0.7152 * (p shr 8 and 255) + 0.0722 * (p and 255)
            sum += l; sq += l * l
        }
        val mean = sum / n
        val sd = kotlin.math.sqrt(kotlin.math.max(0.0, sq / n - mean * mean))
        return (sd / 64.0).toFloat().coerceIn(0f, 1f)
    }

    /** Bertahap: kaca cair dimatikan lebih dulu (blur biasa tetap); bila masih gagal, blur dimatikan. */
    private fun trip(a: Activity) {
        halt()
        if (LiquidGlass.active()) LiquidGlass.disableAuto(a) else Glass.disableAuto(a)
        try { a.recreate() } catch (_: Throwable) {}
    }
}
