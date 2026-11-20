package cloud.depup.office

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
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
 *  1. Refraksi — bentuk kaca didefinisikan sebagai SDF (signed distance field) rounded rectangle. Permukaannya
 *     datar di tengah lalu melengkung turun di zona tepi (bezel) dengan profil squircle cembung; tiap piksel
 *     dibiaskan dengan hukum Snell (n = 1,5) sesuai kemiringan permukaannya. Hasilnya seperti kaca tebal: pembiasan
 *     menumpuk tepat di dekat tepi (garis di belakangnya membengkok tajam ke arah tepi) dan cepat hilang ke tengah.
 *     Ketebalan kaca ikut ukurannya — elemen besar = kaca lebih tebal, pembiasan lebih kuat (seperti Liquid Glass).
 *     Zona tepi digambar dari isi yang blurnya jauh lebih ringan (lapisan kedua, uniform `layer`) agar lengkungnya
 *     benar-benar terlihat; bagian tengah tetap buram ("frosted") sesuai setelan blur.
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
 *  - Android 13+: semua lapisan di atas (RuntimeShader lewat RenderEffect, dirantai SETELAH blur GPU; zona tepi
 *    ditumpuk dari blur ringan kecuali di HP kelas bawah).
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
    /**
     * Setelan pengguna "Dispersi warna" (Saya → Tampilan): semburat pelangi tipis di tepi kaca cair & lensa tab.
     * Mati = tepi kaca bening tanpa pelangi (juga sedikit lebih ringan: satu sampel, bukan tiga).
     */
    @Volatile var DISPERSION = true
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
        ON = p.getBoolean("liquid", !Perf.low)
        DISPERSION = p.getBoolean("dispersion", true)
        if (p.getBoolean("liquidAutoOff", false)) { autoDisabled = true; p.edit().remove("liquidAutoOff").apply() }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("liquid", on).apply()
        ON = on
    }

    fun setDispersion(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("dispersion", on).apply()
        DISPERSION = on
    }

    /** Kekuatan dispersi untuk shader: 0 bila dimatikan pengguna atau HP kelas bawah (satu sampel, lebih ringan). */
    internal fun dispersionAmount(): Float = if (Perf.low || !DISPERSION || GlassPerf.level >= 2) 0f else 0.2f

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
    const val U = 30

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
uniform float thick;        // tebal kaca (px) di bagian datar; makin tebal, makin kuat pembiasan di tepi
uniform float dispersion;   // kekuatan dispersi kromatik (0..1)
uniform float2 light;       // arah ke sumber cahaya (vektor satuan, y ke bawah)
uniform float4 tint;        // rgb tint + kekuatan
uniform float dark;         // 1 = kaca gelap (isi terang di atasnya)
uniform float4 touch;       // titik sentuh xy (px), kekuatan, jari-jari (px)
uniform float spec;         // kekuatan highlight spekular
uniform float px;           // 1 dp dalam px
uniform float4 box;         // batas aman sampel = area kaca yang tampil (kiri, atas, kanan, bawah)
uniform float2 band;        // pita kontras adaptif dari setelan transparansi: (min. kaca terang, maks. kaca gelap)
uniform float mag;          // pembesaran lensa ke arah pusat bentuk (0 = datar; 0,15 ≈ isi tampak 1,18×)
uniform float layer;        // 0 = kaca utuh; > 0 = lapisan TEPI saja (isi blur ringan), alfa maks = nilai ini, memudar ke dalam
uniform float mat;          // 0..1: kaca mewujud (muncul/hilang ala Liquid Glass): 0 = isi apa adanya, 1 = kaca penuh
uniform float stage;        // 0 = lengkap; 1 = hanya lensa (warna mentah hasil pembiasan); 2 = hanya warna & cahaya
                            //   (isi sudah dibiaskan lalu di-blur lagi — lihat LiquidFx: blur sesudah lensa)

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
    float d = shapeDist(xy);
    float bz = max(bezel, 1.0);
    float inside = max(-d, 0.0);
    // lapisan tepi: hanya zona lengkung yang digambar; sisanya transparan (kaca utuh di bawahnya yang tampil)
    if (layer > 0.0 && (d > 1.5 || inside >= bz * 0.9)) { return half4(0.0); }
    bool lens = stage < 1.5;   // tahap 2: isi sudah dibiaskan — tidak digeser lagi
    half4 here = content.eval(xy);
    if (d > 1.5) { return here; }
    float3 c0 = over(here, tint.rgb);
    // Hanya zona tepi (selebar bezel) yang punya lengkung & cahaya; bagian tengah kaca — sebagian besar piksel —
    // melewati semua perhitungan itu (hasilnya sama persis, GPU jauh lebih ringan).
    bool edge = inside < bz;

    // 1. refraksi (hukum Snell, n = 1,5): permukaan squircle cembung — tinggi h = (1 - k^4)^(1/4), k = 1 di garis tepi,
    //    0 di awal bagian datar. Sinar dari mata menembus permukaan miring lalu dibelokkan sebesar (θ1 − θ2) dan
    //    menempuh tebal kaca di titik itu sebelum mengenai isi: sampel digeser KE DALAM sejauh h·thick·tan(θ1 − θ2).
    //    Geseran menumpuk tepat di dekat tepi (garis membengkok tajam seperti kaca tebal) lalu cepat hilang ke tengah;
    //    sampel tidak pernah keluar dari bentuk kaca yang tampil.
    float2 n = float2(0.0, -1.0);
    float slope = 0.0;
    float2 disp = float2(0.0, 0.0);
    if (edge) {
        n = shapeNormal(xy);
        float k = clamp(1.0 + d / bz, 0.0, 0.9995);
        slope = k * k;                                    // faktor tepi untuk dispersi
        if (lens) {
        float k3 = k * k * k;
        float hgt = sqrt(sqrt(1.0 - k3 * k));             // tinggi permukaan (0 di garis tepi … 1 di bagian datar)
        float s = k3 / (hgt * hgt * hgt) * (thick / bz);  // kemiringan permukaan = tan θ1
        float s1 = s * inversesqrt(1.0 + s * s);          // sin θ1
        float s2 = s1 * 0.6666667;                        // sin θ2 = sin θ1 / n
        float t2 = s2 * inversesqrt(1.0 - s2 * s2);       // tan θ2
        disp = -n * (hgt * thick * (s - t2) / (1.0 + s * t2));
        }
    }

    // 6a. sentuhan: kaca di bawah jari sedikit menggembung seperti gel -> isi di bawahnya membesar (lensa)
    float tf = 0.0;
    if (touch.z > 0.001) {
        float2 dv = xy - touch.xy;
        tf = touch.z * exp(-dot(dv, dv) / (2.0 * touch.w * touch.w));
        if (lens) { disp -= dv * (0.16 * tf); }
    }
    // 6c. lensa pembesar (lensa tab): seluruh isi di dalam bentuk ditarik ke pusat -> tampak membesar
    if (lens && mag > 0.0001) { disp -= (xy - origin - size * 0.5) * mag; }
    float2 lo = box.xy;
    float2 hi = box.zw;

    // 5. dispersi kromatik: cahaya merah dibiaskan sedikit lebih jauh daripada ungu. Lima sampel sepanjang arah
    //    pembiasan, masing-masing diberi warna spektrum (merah → jingga → hijau → biru → ungu) lalu dinormalkan per
    //    kanal: hasilnya pelangi bergradasi halus, bukan dua bayangan merah/biru yang tampak seperti garis. Sebarannya
    //    sebanding dengan pembiasan, dibatasi ±2 dp (kaca tebal membiaskan sampai puluhan dp di tepi). Isi yang rata
    //    tetap tidak berwarna (bobot tiap kanal berjumlah 1).
    float3 col = c0;
    if (dot(disp, disp) > 0.0025) {
        if (dispersion > 0.001) {
            float2 dd = disp * (dispersion * slope);
            float dm = length(dd);
            float cap = 2.0 * px;
            if (dm > cap) { dd *= cap / dm; }
            float2 p = xy + disp;
            float3 s0 = over(content.eval(clamp(p + dd, lo, hi)), c0);
            float3 s1 = over(content.eval(clamp(p + dd * 0.5, lo, hi)), c0);
            float3 s2 = over(content.eval(clamp(p, lo, hi)), c0);
            float3 s3 = over(content.eval(clamp(p - dd * 0.5, lo, hi)), c0);
            float3 s4 = over(content.eval(clamp(p - dd, lo, hi)), c0);
            col = float3((s0.r * 1.0 + s1.r * 0.6 + s4.r * 0.4) * 0.5,
                         (s1.g * 0.55 + s2.g * 0.9 + s3.g * 0.45) / 1.9,
                         (s2.b * 0.1 + s3.b * 0.6 + s4.b * 0.9) / 1.6);
        } else {
            col = over(content.eval(clamp(xy + disp, lo, hi)), c0);
        }
    }

    // tahap 1: keluarkan warna hasil pembiasan apa adanya — di-blur lagi, lalu warna & cahaya di tahap 2
    if (stage > 0.5 && stage < 1.5) { return half4(half3(col), 1.0); }

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
        // Garis kilau tepi: terang di sisi yang menghadap cahaya, sangat redup di seberangnya. Di kaca GELAP garis putih
        // yang sama mencolok di atas latar gelap dan melingkari kapsul seperti outline — dibuat lebih tipis & redup,
        // sisi seberangnya nyaris hilang (kilau, bukan garis tepi).
        float rimBand = 1.0 - smoothstep(0.0, (dark > 0.5 ? 1.0 : 1.5) * px, inside);
        float rim = rimBand * (dark > 0.5 ? max(facing, 0.0) * 0.7 + max(-facing, 0.0) * 0.12
                                          : max(facing, 0.0) * 0.9 + max(-facing, 0.0) * 0.25);
        float zone = 1.0 - smoothstep(0.0, bz, inside);
        float glow = zone * zone * (0.55 + 0.45 * facing);
        float gk = dark > 0.5 ? 0.05 : 0.09;
        col += float3(1.0, 1.0, 1.0) * (spec * (rim * 0.55 + glow * gk));
    }

    // 6b. cahaya sentuh: menyala dari titik sentuh
    col += float3(1.0, 1.0, 1.0) * (tf * (dark > 0.5 ? 0.14 : 0.20));
    col = clamp(col, 0.0, 1.0);
    // kaca yang sedang mewujud: saturasi, tint, pita kontras & cahaya tumbuh dari nol (lensa & blur sudah diskalakan)
    col = mix(c0, col, mat);
    // lapisan tepi: alfa penuh di tepi, memudar ke dalam → menyatu mulus dengan kaca utuh (yang lebih buram) di bawahnya
    if (layer > 0.0) {
        float a = (1.0 - smoothstep(0.3 * bz, 0.9 * bz, inside)) * min(layer, 1.0);
        return half4(half3(col * a), half(a));
    }
    return half4(half3(col), 1.0);
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
    private var rimShader: RuntimeShader? = null           // lapisan tepi — dibuat saat pertama dipakai
    private var lookShader: RuntimeShader? = null          // tahap warna & cahaya (blur sesudah lensa)
    private var lastPost = -1f
    private var lastRim = -1f
    private val last = FloatArray(LiquidGlass.U)
    private var lastBlur = -1f
    private var lastEdge = -1f
    var effect: RenderEffect? = null
        private set

    /**
     * [u] berisi: origin(2) size(2) radius slab bezel thick dispersion light(2) tint(4) dark touch(4) spec px
     * box(4) band(2) mag mat. [blur] = radius blur isi (px). [edgeBlur] >= 0 (dan < [blur]): zona tepi ditumpuk lagi dari
     * isi yang blurnya jauh lebih ringan, jadi lengkung lensanya terlihat walau bagian tengah buram — tepi membiaskan,
     * tengah tetap "frosted", seperti Liquid Glass. Kedua lapisan satu efek GPU (dua cabang blur → shader → ditumpuk).
     * Mengembalikan true bila efek baru dibuat (pasang ulang ke node / tampilan).
     */
    fun update(u: FloatArray, blur: Float, edgeBlur: Float = -1f, post: Float = 0f, rimAmt: Float = 1f): Boolean {
        if (effect != null && blur == lastBlur && edgeBlur == lastEdge && post == lastPost && rimAmt == lastRim &&
            u.contentEquals(last)) return false
        u.copyInto(last); lastBlur = blur; lastEdge = edgeBlur; lastPost = post; lastRim = rimAmt
        var fx: RenderEffect
        if (post >= 0.5f) {
            // Blur SESUDAH lensa: di tepi, lensa memampatkan isi yang jauh ke pita tipis — isi yang dimampatkan ikut
            // menipis blurnya (tampak tajam walau blur maksimal). Dibiaskan dulu (tahap 1), di-blur lagi, baru warna &
            // cahaya (tahap 2, garis cahaya tepi tetap tajam) → seluruh kaca buram rata sampai ke pinggir.
            load(shader, u, 0f, 1f)
            val bent = RenderEffect.createRuntimeShaderEffect(shader, "content")
            val pre = if (blur > 0f) RenderEffect.createChainEffect(bent, blurFx(blur)) else bent
            val ls = lookShader ?: RuntimeShader(LiquidGlass.SRC).also { lookShader = it }
            load(ls, u, 0f, 2f)
            fx = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(ls, "content"),
                RenderEffect.createChainEffect(postBlurFx(post), pre))
        } else {
            load(shader, u, 0f, 0f)
            val glass = RenderEffect.createRuntimeShaderEffect(shader, "content")
            fx = if (blur > 0f) RenderEffect.createChainEffect(glass, blurFx(blur)) else glass
        }
        if (edgeBlur >= 0f && rimAmt > 0.02f) {
            val rs = rimShader ?: RuntimeShader(LiquidGlass.SRC).also { rimShader = it }
            load(rs, u, rimAmt.coerceAtMost(1f), 0f)
            val rim = RenderEffect.createRuntimeShaderEffect(rs, "content")
            val rimFx = if (edgeBlur > 0f) RenderEffect.createChainEffect(rim, rimBlurFx(edgeBlur)) else rim
            fx = RenderEffect.createBlendModeEffect(fx, rimFx, BlendMode.SRC_OVER)
        }
        effect = fx
        return true
    }

    private fun load(s: RuntimeShader, u: FloatArray, layer: Float, stage: Float) {
        s.setFloatUniform("origin", u[0], u[1])
        s.setFloatUniform("size", u[2], u[3])
        s.setFloatUniform("radius", u[4])
        s.setFloatUniform("slab", u[5])
        s.setFloatUniform("bezel", u[6])
        s.setFloatUniform("thick", u[7])
        s.setFloatUniform("dispersion", u[8])
        s.setFloatUniform("light", u[9], u[10])
        s.setFloatUniform("tint", u[11], u[12], u[13], u[14])
        s.setFloatUniform("dark", u[15])
        s.setFloatUniform("touch", u[16], u[17], u[18], u[19])
        s.setFloatUniform("spec", u[20])
        s.setFloatUniform("px", u[21])
        s.setFloatUniform("box", u[22], u[23], u[24], u[25])
        s.setFloatUniform("band", u[26], u[27])
        s.setFloatUniform("mag", u[28])
        s.setFloatUniform("layer", layer)
        s.setFloatUniform("stage", stage)
        s.setFloatUniform("mat", u[29])
    }

    // Blur tidak berubah saat cahaya/sentuhan bergeser: dibuat sekali saja, hanya lapisan lensanya yang dibuat ulang.
    private var blurEffect: RenderEffect? = null
    private var blurEffectR = -1f
    private fun blurFx(r: Float): RenderEffect {
        blurEffect?.let { if (blurEffectR == r) return it }
        return RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP).also { blurEffect = it; blurEffectR = r }
    }
    private var postBlur: RenderEffect? = null
    private var postBlurR = -1f
    private fun postBlurFx(r: Float): RenderEffect {
        postBlur?.let { if (postBlurR == r) return it }
        return RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP).also { postBlur = it; postBlurR = r }
    }
    private var rimBlur: RenderEffect? = null
    private var rimBlurR = -1f
    private fun rimBlurFx(r: Float): RenderEffect {
        rimBlur?.let { if (rimBlurR == r) return it }
        return RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP).also { rimBlur = it; rimBlurR = r }
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
        s.setFloatUniform("thick", bezel * 1.6f)
        s.setFloatUniform("dispersion", LiquidGlass.dispersionAmount())
        s.setFloatUniform("light", GlassLight.x, GlassLight.y)
        s.setFloatUniform("tint", android.graphics.Color.red(tintColor) / 255f, android.graphics.Color.green(tintColor) / 255f,
            android.graphics.Color.blue(tintColor) / 255f, tintAmt)
        s.setFloatUniform("dark", if (darkGlass) 1f else 0f)
        s.setFloatUniform("touch", 0f, 0f, 0f, 1f)
        s.setFloatUniform("spec", 0.9f)
        s.setFloatUniform("px", density)
        s.setFloatUniform("box", b.left + 0.5f, b.top + 0.5f, b.right - 0.5f, b.bottom - 0.5f)
        s.setFloatUniform("band", Glass.toneFloor(), Glass.toneCap())
        s.setFloatUniform("mag", 0f)
        s.setFloatUniform("layer", 0f)
        s.setFloatUniform("stage", 0f)
        s.setFloatUniform("mat", 1f)
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
