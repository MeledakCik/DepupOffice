package cloud.depup.office

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.View
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Liquid Glass: kaca yang mensimulasikan LENSA secara waktu-nyata, bukan sekadar blur transparan.
 *
 * Lapisan efek yang ditumpuk (satu shader AGSL, Android 13+):
 *  1. Refraksi — bentuk kaca didefinisikan sebagai SDF (signed distance field) rounded rectangle. Dari gradiennya
 *     dihitung kemiringan permukaan: di tengah datar (latar tidak bergeser), di dekat tepi melengkung (titik sampel
 *     latar digeser ke dalam sepanjang normal) → isi di belakang tampak membesar & membengkok di pinggiran seperti
 *     tepi lensa tebal. Lengkungnya monoton (isi tidak pernah terlipat/terbalik).
 *  2. Warna tidak nyaru — blur + saturasi 160%, tint tipis putih/hitam, lalu kaca ADAPTIF per piksel: kecerahannya
 *     dijaga di pita yang kontras dengan ikon/teks di atasnya (latar gelap lewat → kaca terang menebal, dan
 *     sebaliknya), jadi isi bilah selalu terbaca.
 *  3. Highlight spekular — garis cahaya tipis di tepi: terang di sisi yang menghadap sumber cahaya, redup di sisi
 *     seberangnya. Arah cahaya mengikuti kemiringan HP ([GlassLight]).
 *  4. Kedalaman — cahaya dalam (inner glow) di sisi terang & bayangan dalam di sisi seberang memberi volume;
 *     bayangan luar lembut & adaptif diatur [GlassScaffold.setDepth].
 *  5. Dispersi kromatik — di tepi, kanal R, G, B digeser sedikit berbeda → semburat pelangi halus.
 *  6. Gerak — saat disentuh, kaca di bawah jari menggembung seperti gel (isi di bawahnya membesar) dan menyala
 *     dari titik sentuh (uniform `touch`); kelenturan bentuk & morph antar-state diatur komponennya.
 *
 * Tingkatan perangkat:
 *  - Android 13+: semua lapisan di atas (RuntimeShader lewat RenderEffect, dirantai SETELAH blur GPU).
 *  - Android 12: blur GPU + saturasi + tint; highlight tepi, cahaya dalam & cahaya sentuh digambar kanvas.
 *  - Android 10–11: blur perangkat lunak (bitmap 1/8) + lapisan kanvas yang sama.
 *  - Blur mati / isi tidak bisa direkam: warna semi-transparan pekat + garis tepi.
 *
 * Aturan desain: kaca hanya untuk lapisan navigasi yang mengambang (bilah atas/bawah, kapsul, notifikasi), bukan
 * untuk isi, dan tidak ada kaca di atas kaca.
 *
 * Ketahanan: efek GPU (RenderEffect) hanya menghitung masukannya di area yang sedang digambar ulang — di luar itu
 * sampel bernilai transparan. Karena itu (a) semua sampel ditahan di dalam bentuk kaca yang tampil, (b) sampel yang
 * tetap jatuh di luar area sah otomatis diganti warna titik itu sendiri (tidak pernah ada garis hitam/sian/abu), dan
 * (c) permukaan kaca selalu digambar ulang UTUH (lihat [GlassSurface], [GlassScaffold]).
 */
object LiquidGlass {
    /** Setelan pengguna "Kaca cair" (Saya → Tampilan). */
    @Volatile var ON = true
        private set
    /** true sekali setelah kaca cair dimatikan otomatis — layar berikutnya memberi tahu pengguna. */
    @Volatile var autoDisabled = false
    @Volatile private var failed = false

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** Shader lensa benar-benar dipakai: Android 13+, blur menyala, setelan menyala, shader tidak pernah gagal. */
    fun active(): Boolean = ON && !failed && supported && Glass.BLUR

    private fun sp(ctx: Context) = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    /** Panggil sekali di Application.onCreate (setelah [Glass.init]). */
    fun init(ctx: Context) {
        val p = sp(ctx.applicationContext)
        ON = p.getBoolean("liquid", true)
        if (p.getBoolean("liquidAutoOff", false)) { autoDisabled = true; p.edit().remove("liquidAutoOff").apply() }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("liquid", on).apply()
        ON = on
    }

    /** Penjaga/penangkap galat: kaca cair dimatikan lebih dulu — blur biasa tetap menyala. */
    fun disableAuto(ctx: Context) {
        setEnabled(ctx, false)
        autoDisabled = true
    }

    /** Dipakai penangkap galat (sebelum proses berhenti): tulis langsung ke setelan. */
    internal fun disableNow(ctx: Context) {
        try { sp(ctx).edit().putBoolean("liquid", false).putBoolean("liquidAutoOff", true).commit() } catch (_: Throwable) {}
    }

    /** Shader gagal dibuat di HP ini (mis. driver menolak): semua permukaan memakai jalur blur biasa. */
    internal fun fail() { failed = true }

    /**
     * Lensa kaca untuk latar STATIS yang sudah di-blur (mis. tangkapan layar notifikasi kapsul), sebagai Drawable.
     * [darkGlass] = isi di atasnya terang. null bila tidak tersedia/gagal (pemanggil memakai jalur biasa).
     */
    @android.annotation.TargetApi(33)
    internal fun lens(ctx: Context, blurred: Bitmap, darkGlass: Boolean, tint: Int, tintAmt: Float): Drawable? {
        if (!active()) return null
        return try { LensDrawable(blurred, ctx.resources.displayMetrics.density, darkGlass, tint, tintAmt) }
        catch (_: Throwable) { fail(); null }
    }

    /** Urutan nilai uniform di [LiquidFx.update]. */
    const val U = 28

    /**
     * Shader AGSL. `content` = isi di belakang kaca yang SUDAH di-blur (efek blur dirantai lebih dulu).
     * Koordinat = koordinat node kaca (piksel); bentuk kaca = [origin, origin + size].
     */
    const val SRC = """
uniform shader content;
uniform float2 origin;      // sudut kiri-atas bentuk kaca (px)
uniform float2 size;        // ukuran bentuk kaca (px)
uniform float radius;       // radius sudut (px)
uniform float slab;         // 0 = kapsul; 1 = hanya tepi bawah nyata (bilah atas); -1 = hanya tepi atas nyata
uniform float bezel;        // lebar zona tepi yang melengkung (px)
uniform float refraction;   // pergeseran sampel maksimum di tepi (px); <= bezel/2 agar lengkungnya monoton
uniform float dispersion;   // kekuatan dispersi kromatik (0..1)
uniform float2 light;       // arah ke sumber cahaya (vektor satuan, y ke bawah)
uniform float4 tint;        // rgb tint + kekuatan
uniform float dark;         // 1 = kaca gelap (isi terang di atasnya)
uniform float4 touch;       // titik sentuh xy (px), kekuatan, jari-jari (px)
uniform float spec;         // kekuatan highlight spekular
uniform float px;           // 1 dp dalam px
uniform float4 box;         // batas aman sampel = area kaca yang tampil (kiri, atas, kanan, bawah)
uniform float2 band;        // pita kontras adaptif dari setelan transparansi: (min. kaca terang, maks. kaca gelap)

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

float shapeDist(float2 xy) {
    if (slab > 0.5) { return xy.y - (origin.y + size.y); }
    if (slab < -0.5) { return origin.y - xy.y; }
    float2 hb = size * 0.5;
    float r = min(radius, min(hb.x, hb.y));
    return sdRoundRect(xy - origin - hb, hb, r);
}

// Normal permukaan (arah keluar) dihitung langsung dari bentuknya — satu evaluasi, bukan empat beda hingga SDF.
float2 shapeNormal(float2 xy) {
    if (slab > 0.5) { return float2(0.0, 1.0); }
    if (slab < -0.5) { return float2(0.0, -1.0); }
    float2 hb = size * 0.5;
    float r = min(radius, min(hb.x, hb.y));
    float2 p = xy - origin - hb;
    float2 q = abs(p) - hb + float2(r, r);
    float2 sg = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    if (q.x > 0.0 && q.y > 0.0) { return normalize(q) * sg; }
    return q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y);
}

// Sampel dikomposit di atas warna cadangan. Masukan efek GPU hanya sah di area yang sedang digambar ulang; sampel di
// luar area itu bernilai transparan (alfa 0) dan otomatis diganti warna titik itu sendiri — yang selalu sah.
float3 over(half4 s, float3 under) {
    return float3(s.rgb) + (1.0 - float(s.a)) * under;
}

half4 main(float2 xy) {
    half4 here = content.eval(xy);
    float d = shapeDist(xy);
    if (d > 1.5) { return here; }
    float3 c0 = over(here, tint.rgb);
    float bz = max(bezel, 1.0);
    float inside = max(-d, 0.0);
    // Hanya zona tepi (selebar bezel) yang punya lengkung & cahaya; bagian tengah kaca — sebagian besar piksel —
    // melewati semua perhitungan itu (hasilnya sama persis, GPU jauh lebih ringan).
    bool edge = inside < bz;

    // 1. refraksi: tengah datar (k = 0), tepi melengkung (k -> 1). Sampel digeser KE DALAM: isi dekat tepi tampak
    //    membesar seperti tepi lensa tebal, dan sampel tidak pernah keluar dari bentuk kaca yang tampil.
    float2 n = float2(0.0, -1.0);
    float slope = 0.0;
    float2 disp = float2(0.0, 0.0);
    if (edge) {
        n = shapeNormal(xy);
        float k = clamp(1.0 + d / bz, 0.0, 1.0);
        slope = k * k;
        disp = -n * (refraction * slope);
    }

    // 6a. sentuhan: kaca di bawah jari sedikit menggembung seperti gel -> isi di bawahnya membesar (lensa)
    float tf = 0.0;
    if (touch.z > 0.001) {
        float2 dv = xy - touch.xy;
        tf = touch.z * exp(-dot(dv, dv) / (2.0 * touch.w * touch.w));
        disp -= dv * (0.16 * tf);
    }
    float2 lo = box.xy;
    float2 hi = box.zw;

    // 5. dispersi kromatik: R, G, B dibiaskan sedikit berbeda di tepi
    float3 col = c0;
    if (dot(disp, disp) > 0.0025) {
        if (dispersion > 0.001) {
            float sp = dispersion * slope;
            col = float3(over(content.eval(clamp(xy + disp * (1.0 + sp), lo, hi)), c0).r,
                         over(content.eval(clamp(xy + disp, lo, hi)), c0).g,
                         over(content.eval(clamp(xy + disp * (1.0 - sp), lo, hi)), c0).b);
        } else {
            col = over(content.eval(clamp(xy + disp, lo, hi)), c0);
        }
    }

    // 2. warna: saturasi 160% (blur saja membuat warna keruh), tint tipis, lalu jaga kontras (adaptif)
    float3 W = float3(0.2126, 0.7152, 0.0722);
    float l0 = dot(col, W);
    col = clamp(mix(float3(l0, l0, l0), col, 1.6), 0.0, 1.0);
    col = mix(col, tint.rgb, tint.a);
    // kaca adaptif: kecerahan dijaga di pita yang kontras dengan isi di atasnya (kaca terang >= ~0,84, kaca gelap
    // <= ~0,16; sebagian kecil variasi latar tetap terasa). Batasnya lengkung halus (maks/min lembut) — tidak ada
    // "lutut" tajam yang tampak sebagai pita/garis di atas isi gelap.
    float lum = dot(col, W);
    if (dark > 0.5) {
        float cap = band.y + 0.08 * lum;
        float hd = lum - cap;
        float to = 0.5 * (lum + cap - sqrt(hd * hd + 0.0025));
        col *= clamp(to / max(lum, 0.0001), 0.0, 1.0);
    } else {
        float flo = band.x + 0.06 * lum;
        float hl = lum - flo;
        float to = 0.5 * (lum + flo + sqrt(hl * hl + 0.0064));
        col = mix(col, float3(1.0, 1.0, 1.0), clamp((to - lum) / max(1.0 - lum, 0.0001), 0.0, 1.0));
    }

    // 3 & 4. cahaya: rim tipis (terang menghadap cahaya, redup di seberang), cahaya dalam & bayangan dalam
    if (edge) {
        float facing = dot(n, light);
        // bayangan dalam di sisi yang membelakangi cahaya. Kaca terang: digelapkan tipis (5%). Kaca gelap: hanya
        // diredupkan RELATIF (maks 12%) di zona yang lebih sempit — pengurangan mutlak pada kaca yang sudah gelap
        // membuat tepi bawahnya hitam pekat seperti bayangan tebal.
        float shadeZone = dark > 0.5 ? bz * 0.45 : bz * 0.6;
        float shade = (1.0 - smoothstep(0.0, shadeZone, inside)) * max(-facing, 0.0);
        if (dark > 0.5) { col *= 1.0 - shade * 0.12; } else { col -= float3(1.0, 1.0, 1.0) * (shade * 0.05); }
        float rimBand = 1.0 - smoothstep(0.0, 1.5 * px, inside);
        float rim = rimBand * (max(facing, 0.0) * 0.9 + max(-facing, 0.0) * 0.35);
        float zone = 1.0 - smoothstep(0.0, bz, inside);
        float glow = zone * zone * (0.55 + 0.45 * facing);
        float gk = dark > 0.5 ? 0.05 : 0.09;
        col += float3(1.0, 1.0, 1.0) * (spec * (rim * 0.55 + glow * gk));
    }

    // 6b. cahaya sentuh: menyala dari titik sentuh
    col += float3(1.0, 1.0, 1.0) * (tf * (dark > 0.5 ? 0.14 : 0.20));
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""
}

/**
 * Pembungkus shader per permukaan kaca (Android 13+). Efek GPU dibuat ulang HANYA bila nilai uniform berubah
 * (ukuran, arah cahaya, sentuhan, tema) — selebihnya node memakai efek yang sama setiap frame.
 * Kelas terpisah agar kelas Android 13 tidak disentuh di HP yang lebih lama.
 */
@android.annotation.TargetApi(33)
internal class LiquidFx {
    private val shader = RuntimeShader(LiquidGlass.SRC)   // galat kompilasi → dilempar ke pemanggil
    private val last = FloatArray(LiquidGlass.U)
    private var lastBlur = -1f
    var effect: RenderEffect? = null
        private set

    /**
     * [u] berisi: origin(2) size(2) radius slab bezel refraction dispersion light(2) tint(4) dark touch(4) spec px
     * box(4) band(2). Mengembalikan true bila efek baru dibuat (pasang ulang ke node / tampilan).
     */
    fun update(u: FloatArray, blur: Float): Boolean {
        if (effect != null && blur == lastBlur && u.contentEquals(last)) return false
        u.copyInto(last); lastBlur = blur
        val s = shader
        s.setFloatUniform("origin", u[0], u[1])
        s.setFloatUniform("size", u[2], u[3])
        s.setFloatUniform("radius", u[4])
        s.setFloatUniform("slab", u[5])
        s.setFloatUniform("bezel", u[6])
        s.setFloatUniform("refraction", u[7])
        s.setFloatUniform("dispersion", u[8])
        s.setFloatUniform("light", u[9], u[10])
        s.setFloatUniform("tint", u[11], u[12], u[13], u[14])
        s.setFloatUniform("dark", u[15])
        s.setFloatUniform("touch", u[16], u[17], u[18], u[19])
        s.setFloatUniform("spec", u[20])
        s.setFloatUniform("px", u[21])
        s.setFloatUniform("box", u[22], u[23], u[24], u[25])
        s.setFloatUniform("band", u[26], u[27])
        val glass = RenderEffect.createRuntimeShaderEffect(s, "content")
        effect = if (blur > 0f) RenderEffect.createChainEffect(glass, blurFx(blur)) else glass
        return true
    }

    // Blur tidak berubah saat cahaya/sentuhan bergeser: dibuat sekali saja, hanya lapisan lensanya yang dibuat ulang.
    private var blurEffect: RenderEffect? = null
    private var blurEffectR = -1f
    private fun blurFx(r: Float): RenderEffect {
        blurEffect?.let { if (blurEffectR == r) return it }
        return RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP).also { blurEffect = it; blurEffectR = r }
    }
}

/**
 * Lensa kaca cair untuk latar statis yang sudah di-blur (notifikasi kapsul). Shader dipakai sebagai CAT (Paint) dengan
 * isi = BitmapShader, bukan sebagai efek GPU: sampelnya selalu sah di mana pun, jadi lensa tetap utuh walau HP hanya
 * menggambar ulang sebagian layar (mis. isi di belakang notifikasi sedang beranimasi).
 */
@android.annotation.TargetApi(33)
internal class LensDrawable(
    private val bmp: Bitmap,
    private val density: Float,
    private val darkGlass: Boolean,
    private val tintColor: Int,
    private val tintAmt: Float,
) : Drawable() {
    private val shader = RuntimeShader(LiquidGlass.SRC)   // galat kompilasi → dilempar ke pemanggil
    private val paint = Paint()
    private val m = Matrix()

    init { setup(Rect(0, 0, bmp.width, bmp.height)) }

    override fun onBoundsChange(bounds: Rect) { setup(bounds) }

    private fun setup(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f || bmp.width <= 0 || bmp.height <= 0) return
        val bs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bs.filterMode = BitmapShader.FILTER_MODE_LINEAR
        m.setScale(w / bmp.width, h / bmp.height)
        m.postTranslate(b.left.toFloat(), b.top.toFloat())
        bs.setLocalMatrix(m)
        val s = shader
        s.setInputShader("content", bs)
        val bezel = minOf(14f * density, h * 0.4f)
        s.setFloatUniform("origin", b.left.toFloat(), b.top.toFloat())
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("radius", h / 2f)
        s.setFloatUniform("slab", 0f)
        s.setFloatUniform("bezel", bezel)
        s.setFloatUniform("refraction", bezel * 0.5f)
        s.setFloatUniform("dispersion", if (Perf.low) 0f else 0.22f)
        s.setFloatUniform("light", GlassLight.x, GlassLight.y)
        s.setFloatUniform("tint", android.graphics.Color.red(tintColor) / 255f, android.graphics.Color.green(tintColor) / 255f,
            android.graphics.Color.blue(tintColor) / 255f, tintAmt)
        s.setFloatUniform("dark", if (darkGlass) 1f else 0f)
        s.setFloatUniform("touch", 0f, 0f, 0f, 1f)
        s.setFloatUniform("spec", 0.9f)
        s.setFloatUniform("px", density)
        s.setFloatUniform("box", b.left + 0.5f, b.top + 0.5f, b.right - 0.5f, b.bottom - 0.5f)
        s.setFloatUniform("band", Glass.toneFloor(), Glass.toneCap())
        paint.shader = s
    }

    override fun draw(canvas: Canvas) {
        if (!bounds.isEmpty) canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Sumber cahaya kaca. Arah cahaya mengikuti kemiringan HP (sensor gravitasi, cadangan: akselerometer), dihaluskan,
 * dan dibulatkan ke langkah ±1,5° supaya efek kaca tidak dibuat ulang tiap getaran kecil. Sensor hanya hidup selama
 * ada layar di depan & animasi HP menyala; bila mati, cahaya tetap dari kiri-atas.
 */
object GlassLight : SensorEventListener {
    private const val DX = -0.28f
    private const val DY = -0.96f
    /** Arah ke sumber cahaya (satuan, koordinat layar: y ke bawah) — nilai yang dipakai kaca. */
    @Volatile var x = DX
        private set
    @Volatile var y = DY
        private set

    private var fx = DX
    private var fy = DY
    /** Sudut cahaya terakhir yang dipakai kaca (radian, belum dibulatkan). */
    private var emitAng = atan2(DY, DX)
    private var rotation = Surface.ROTATION_0
    private var sm: SensorManager? = null
    /** Layar yang sedang di depan (di antara onResume & onPause). */
    private var front: WeakReference<Activity>? = null
    private val views: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private const val STEP = 0.026f   // ≈1,5°

    // Sensor hanya hidup bila layar yang di depan benar-benar memuat kaca. Layar tanpa kaca (penampil, pemindai,
    // anotasi, …) tidak menyalakan sensor sama sekali — hemat baterai.
    fun attach(v: View) { views.add(v); sync() }
    fun detach(v: View) { views.remove(v); sync() }

    /** Setelan kaca berubah (transparansi / blur): gambar ulang semua kaca yang sedang terpasang. */
    fun refreshAll() { for (v in views.toList()) v.invalidate() }

    fun start(a: Activity) { front = WeakReference(a); sync() }

    /** Layar [a] tidak lagi di depan (bila null: hentikan apa pun). */
    fun stop(a: Activity?) {
        if (a == null || front?.get() === a) front = null
        sync()
    }

    private fun activityOf(v: View): Activity? {
        var c: Context? = v.context
        while (c is android.content.ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    private fun sync() {
        val a = front?.get()
        if (a == null || !Glass.BLUR || !Motion.on()) {
            off()
            if (a != null) reset()
            return
        }
        val want = views.any { it.isAttachedToWindow && activityOf(it) === a }
        if (!want) { off(); return }
        if (sm != null) return
        val m = a.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val s = m.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        rotation = try {
            if (Build.VERSION.SDK_INT >= 30) a.display?.rotation ?: Surface.ROTATION_0
            else @Suppress("DEPRECATION") a.windowManager.defaultDisplay.rotation
        } catch (_: Throwable) { Surface.ROTATION_0 }
        try { m.registerListener(this, s, SensorManager.SENSOR_DELAY_UI) } catch (_: Throwable) { return }
        sm = m
    }

    private fun off() {
        try { sm?.unregisterListener(this) } catch (_: Throwable) {}
        sm = null
    }

    private fun reset() {
        emitAng = atan2(DY, DX)
        if (x != DX || y != DY) { x = DX; y = DY; fx = DX; fy = DY; notifyViews() }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.values.size < 2) return
        var sx = e.values[0]; var sy = e.values[1]
        when (rotation) {
            Surface.ROTATION_90 -> { val t = sx; sx = -sy; sy = t }
            Surface.ROTATION_180 -> { sx = -sx; sy = -sy }
            Surface.ROTATION_270 -> { val t = sx; sx = sy; sy = -t }
        }
        // arah "atas" layar (y ke bawah) = arah sumber cahaya; ditambah sedikit arah bawaan (kiri-atas) supaya
        // tetap stabil saat HP diletakkan mendatar
        var ux = sx / SensorManager.GRAVITY_EARTH + DX * 0.35f
        var uy = -sy / SensorManager.GRAVITY_EARTH + DY * 0.35f
        val len = sqrt(ux * ux + uy * uy)
        if (len < 1e-3f) return
        ux /= len; uy /= len
        fx += (ux - fx) * 0.2f; fy += (uy - fy) * 0.2f   // dihaluskan
        val fl = sqrt(fx * fx + fy * fy)
        if (fl < 1e-3f) return
        val ang = atan2(fy / fl, fx / fl)
        // Histeresis: kaca baru digambar ulang bila cahaya benar-benar bergeser >= 1,5° dari posisi terakhir. Tanpa
        // ini, getaran kecil tangan yang diam di sekitar batas pembulatan menggambar ulang kaca berulang-ulang.
        var diff = ang - emitAng
        if (diff > PI.toFloat()) diff -= (2 * PI).toFloat() else if (diff < -PI.toFloat()) diff += (2 * PI).toFloat()
        if (abs(diff) < STEP) return
        emitAng = ang
        val q = (ang / STEP).roundToInt() * STEP          // dibulatkan per langkah kecil
        val qx = cos(q); val qy = sin(q)
        if (abs(qx - x) > 1e-4f || abs(qy - y) > 1e-4f) { x = qx; y = qy; notifyViews() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun notifyViews() {
        for (v in views.toList()) if (v.isAttachedToWindow && v.isShown) v.postInvalidateOnAnimation()
    }
}
