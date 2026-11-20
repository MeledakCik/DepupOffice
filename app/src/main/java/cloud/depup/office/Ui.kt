package cloud.depup.office

import android.app.Activity
import android.app.Dialog
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import androidx.core.content.res.ResourcesCompat
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Depup Office — sistem desain "Depup Fresh" (v3).
 *
 * - Palet indigo yang segar dengan warna jenis berkas yang langsung dikenali (PDF merah, Word biru,
 *   Excel hijau, Gambar ungu).
 * - Mode TERANG & GELAP mengikuti pengaturan HP (atau pilihan di tab Saya). Semua token di [C] dihitung
 *   ulang saat tema berganti, jadi seluruh layar ikut tanpa mengubah pemanggilnya.
 * - Aksesibilitas: setiap pasangan teks/latar >= 4.5:1 (WCAG AA) di kedua tema, kontrol non-teks >= 3:1,
 *   target sentuh minimal 48dp, teks minimal 12sp dan mengikuti ukuran huruf sistem, label pembaca layar
 *   untuk tombol ikon, judul ditandai sebagai heading untuk TalkBack.
 *
 * Nama konstanta lama (NAVY, BLUE_BG, TEAL, ...) dipertahankan sebagai alias peran agar semua layar ikut
 * berganti. Rasio kontras dihitung di DESIGN_SYSTEM.md.
 */
object C {
    /** true = tema gelap aktif (diatur otomatis oleh [Theme.apply]). */
    @Volatile var dark = false

    private fun t(light: Long, night: Long): Int = (if (dark) night else light).toInt()

    // ---------- Permukaan ----------
    // Minimalis netral-hangat: halaman abu batu muda, kartu putih rata, tinta hitam untuk aksi utama.
    // Tidak ada gradien; kedalaman hanya dari beda nada. Warna kategori dipakai kecil-kecil (ikon, titik status).
    val BG get() = t(0xFFF1F0EC, 0xFF0F0F0E)          // latar halaman
    val SURFACE get() = t(0xFFFFFFFF, 0xFF1B1B1A)     // kartu, sel, sheet, navigasi
    val SURFACE2 get() = t(0xFFE8E6E1, 0xFF272725)    // isian, chip, trek segmen
    val SURFACE3 get() = t(0xFFDCD9D3, 0xFF34342F)    // ditekan / terpilih netral
    val SOFT get() = t(0xFFF3F2EE, 0xFF242422)        // wadah ikon di dalam kartu putih
    val LINE get() = t(0xFFE4E2DD, 0xFF2C2C29)        // pemisah & garis tepi tipis
    val LINE_STRONG get() = t(0xFF84817B, 0xFF6F6D68) // batas kontrol (>= 3:1)

    // ---------- Warna Biru Utama (Kotak & Kapsul) ----------
    val BLUE_ACCENT get() = t(0xFF1652F6, 0xFF1D56FF)

    // ---------- Tinta: aksi utama & panel kontras ----------
    val INK get() = t(0xFF111111, 0xFFF2F1EE)
    val ON_INK get() = t(0xFFFFFFFF, 0xFF111111)
    val ON_INK_MUTED get() = t(0xB3FFFFFF, 0xB3111111)
    /** Tombol di atas panel tinta: pil putih (terang) / biru (gelap). */
    val INK_ACCENT get() = 0xFFFFFFFF.toInt()
    val ON_INK_ACCENT get() = BLUE_ACCENT

    // ---------- Panel sorotan (kartu Scan, ubin angka, notifikasi): warna biru royal di terang & gelap ----------
    val PANEL get() = BLUE_ACCENT
    val ON_PANEL get() = 0xFFFFFFFF.toInt()
    val ON_PANEL_MUTED get() = 0xD9FFFFFF.toInt()
    val PANEL_BTN get() = 0xFFFFFFFF.toInt()
    val ON_PANEL_BTN get() = BLUE_ACCENT

    // ---------- Teks ----------
    val TEXT get() = t(0xFF111111, 0xFFF2F1EE)
    val MUTED get() = t(0xFF65625D, 0xFF9C9A95)       // teks sekunder (>= 4.8:1 di halaman & kartu)

    // ---------- Warna utama / aksen (biru royal di tema terang dan tema gelap) ----------
    val PRIMARY get() = BLUE_ACCENT
    val ON_PRIMARY get() = 0xFFFFFFFF.toInt()
    val PRIMARY_C get() = t(0xFFE8EEFF, 0xFF19254D)    // wadah lembut biru
    val ON_PRIMARY_C get() = BLUE_ACCENT

    // ---------- Status ----------
    val SUCCESS get() = t(0xFF15703A, 0xFF5BD88A)
    val SUCCESS_C get() = t(0xFFDCF5E4, 0xFF15301F)
    val SUCCESS_T get() = t(0xFF14532D, 0xFFBDF2CF)
    val WARN_T get() = t(0xFF8A4508, 0xFFF6C35A)
    val WARN_C get() = t(0xFFFDF0CC, 0xFF372A0C)
    val ERROR get() = t(0xFFC0262D, 0xFFFF8A8A)
    val ERROR_C get() = t(0xFFFCE4E4, 0xFF3A1618)
    val ERROR_T get() = t(0xFF8B1A1A, 0xFFFFC9C9)
    val AMBER_FILL get() = t(0xFFF5A524, 0xFFF5B53D)  // sorotan hangat (teks di atasnya pakai [on])
    val LILAC_C get() = t(0xFFECE7FD, 0xFF2A2357)

    // ---------- Jenis berkas: warna kuat (ikon/label) + wadah lembut ----------
    // nada diredam agar selaras dengan netral hangat (terakota, biru batu, hijau sage, ungu lavender)
    val PDF_FG get() = t(0xFFB0472F, 0xFFEE9A84); val PDF_BG get() = t(0xFFF5E6E1, 0xFF33221E); val PDF_TINT get() = PDF_BG
    val DOC_FG get() = t(0xFF33609F, 0xFF97B4E6); val DOC_BG get() = t(0xFFE4EAF3, 0xFF1E2631); val DOC_TINT get() = DOC_BG
    val XLS_FG get() = t(0xFF35754C, 0xFF8CC9A0); val XLS_BG get() = t(0xFFE3EEE6, 0xFF1C2A21); val XLS_TINT get() = XLS_BG
    val IMG_FG get() = t(0xFF72539E, 0xFFC2A9E6); val IMG_BG get() = t(0xFFECE6F3, 0xFF292230); val IMG_TINT get() = IMG_BG

    // ---------- Alias peran lama (dipakai di seluruh layar) ----------
    val NAVY get() = PRIMARY
    val PAPER get() = SURFACE
    val CREAM get() = SURFACE
    val MIST get() = BG
    val WHITE get() = SURFACE
    val BLUE get() = PRIMARY
    val GOLD get() = AMBER_FILL
    val MINT get() = SUCCESS
    val LILAC get() = IMG_FG
    val TINT80 get() = MUTED
    val TINT60 get() = MUTED
    val TINT40 get() = LINE_STRONG
    val TINT20 get() = SURFACE2
    val TINT10 get() = BG
    val NEU_LIGHT get() = SURFACE
    val NEU_DARK = 0x00000000
    val ACCENT get() = PRIMARY
    val ACCENT_DARK get() = ON_PRIMARY_C
    val ACCENT_SOFT get() = PRIMARY
    val FAINT get() = LINE_STRONG
    val TRACK get() = SURFACE3
    val CANVAS get() = SURFACE3
    val GOLD_DEEP get() = AMBER_FILL
    val NAVY_DEEP get() = ON_PRIMARY_C
    /** Panel gelap di belakang foto/kanvas (selalu gelap di kedua tema agar gambar menonjol). */
    val NAVY_PANEL get() = t(0xFF1F2230, 0xFF07080C)
    val ON_NAVY get() = ON_PRIMARY
    val ON_NAVY_FAINT get() = ON_PRIMARY
    val BLUE_BG get() = PRIMARY_C
    val BLUE_LINE get() = PRIMARY_C
    val INFO get() = PRIMARY
    val INFO_TEXT get() = ON_PRIMARY_C
    val INDIGO get() = PRIMARY
    val INDIGO_BG get() = LILAC_C
    val SKY get() = WARN_T
    val SKY_BG get() = WARN_C
    val AMBER get() = WARN_T
    val AMBER_BG get() = WARN_C
    val AMBER_DARK get() = WARN_T
    val WARN get() = AMBER_FILL
    val AMBER_LINE get() = WARN_C
    val TEAL get() = SUCCESS
    val TEAL_BG get() = SUCCESS_C
    val TEAL_SOFT get() = SUCCESS_C
    val TEAL_DARK get() = SUCCESS_T
    val RED get() = ERROR
    val RED_BG get() = ERROR_C
    val RED_LINE get() = ERROR_C
    val RED_TEXT get() = ERROR_T

    /** Warna ripple sentuh yang terlihat di kedua tema. */
    val RIPPLE get() = t(0x14111111, 0x24F2F1EE)

    /** Teks/ikon yang terbaca di atas warna isian [bg] (hitam pekat atau putih, dipilih dari kecerahannya). */
    fun on(bg: Int): Int {
        fun ch(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
        val l = 0.2126 * ch(Color.red(bg)) + 0.7152 * ch(Color.green(bg)) + 0.0722 * ch(Color.blue(bg))
        // Pilih yang kontrasnya lebih tinggi terhadap latar
        return if (1.05 / (l + 0.05) >= (l + 0.05) / 0.0585) Color.WHITE else 0xFF151823.toInt()
    }
}

/** Mengatur tema terang/gelap: dipanggil otomatis untuk setiap Activity (lihat DepupApp). */
object Theme {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    private fun sp(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
    fun mode(ctx: Context): Int = sp(ctx).getInt("theme", SYSTEM)
    fun setMode(ctx: Context, m: Int) { sp(ctx).edit().putInt("theme", m).apply() }

    /** Tema HP saat ini (abaikan pilihan tema aplikasi). */
    fun systemDark(): Boolean =
        (android.content.res.Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Tema gelap dipakai? "Ikuti HP" membaca tema HP dari konfigurasi SISTEM, bukan konfigurasi layar: konfigurasi
     * layar ikut ditimpa mode malam AppCompat dan baru diperbarui di tengah onCreate, jadi bisa berbeda sesaat —
     * dulu membuat halaman dibangun terang sementara bilah kaca & bilah status sudah gelap.
     */
    fun isDark(ctx: Context): Boolean = when (mode(ctx)) {
        LIGHT -> false
        DARK -> true
        else -> systemDark()
    }

    /** Hitung ulang token warna & warnai bilah status/navigasi sistem. */
    @Suppress("DEPRECATION")
    fun apply(a: Activity, statusColor: Int? = null) {
        C.dark = isDark(a)
        val w = a.window ?: return
        val bar = statusColor ?: C.BG
        w.statusBarColor = bar
        w.navigationBarColor = C.BG   // menyatu dengan halaman; navigasi kapsul melayang di atasnya
        val lightBars = Color.luminance(bar) > 0.5f
        var f = w.decorView.systemUiVisibility
        f = if (lightBars) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        f = if (!C.dark) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        w.decorView.systemUiVisibility = f
    }
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

/** Notifikasi kapsul kaca buram di atas layar aktif; tanpa Activity (mis. konteks aplikasi) pakai Toast sistem. */
fun Context.toast(s: String) {
    var c: Context? = this
    while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
    val act = c as? android.app.Activity
    if (act != null) GlassToast.show(act, s) else Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** Bentuk rounded-rect. [strokeColor]/[strokeDp] menggambar garis tepi bila diisi. */
fun Context.shape(color: Int, radiusDp: Int = 16, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0 && strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
    }

/**
 * Sudut "kontinu" seperti ikon & kartu Apple: lengkungnya mulai lebih awal dan menyatu halus ke sisi lurus
 * (tidak ada sambungan tajam antara busur dan garis seperti rounded-rect biasa). Koefisien mengikuti kurva
 * UIBezierPath(roundedRect:) iOS. Bila radius terlalu besar untuk ukurannya (bentuk pil), dipakai busur biasa.
 */
fun android.graphics.Path.addContinuousRect(l: Float, t: Float, r: Float, b: Float, radius: Float) {
    val w = r - l; val h = b - t
    val k = 1.52866f
    if (radius <= 0f) { addRect(l, t, r, b, android.graphics.Path.Direction.CW); return }
    if (radius * k * 2f > minOf(w, h)) { val rr = minOf(radius, w / 2f, h / 2f); addRoundRect(l, t, r, b, rr, rr, android.graphics.Path.Direction.CW); return }
    val rad = radius
    // titik P(a, c) di sekitar sudut O: mundur a·r dari sudut di sisi masuk, maju c·r di sisi keluar
    fun corner(ox: Float, oy: Float, ix: Float, iy: Float, jx: Float, jy: Float, first: Boolean) {
        fun px(a: Float, c: Float) = ox - a * rad * ix + c * rad * jx
        fun py(a: Float, c: Float) = oy - a * rad * iy + c * rad * jy
        if (first) moveTo(px(k, 0f), py(k, 0f)) else lineTo(px(k, 0f), py(k, 0f))
        cubicTo(px(1.08849f, 0f), py(1.08849f, 0f), px(0.86840f, 0f), py(0.86840f, 0f), px(0.66993f, 0.06549f), py(0.66993f, 0.06549f))
        lineTo(px(0.63149f, 0.07491f), py(0.63149f, 0.07491f))
        cubicTo(px(0.37282f, 0.16906f), py(0.37282f, 0.16906f), px(0.16906f, 0.37282f), py(0.16906f, 0.37282f), px(0.07491f, 0.63149f), py(0.07491f, 0.63149f))
        lineTo(px(0.06549f, 0.66993f), py(0.06549f, 0.66993f))
        cubicTo(px(0f, 0.86840f), py(0f, 0.86840f), px(0f, 1.08849f), py(0f, 1.08849f), px(0f, k), py(0f, k))
    }
    corner(r, t, 1f, 0f, 0f, 1f, true)     // kanan atas
    corner(r, b, 0f, 1f, -1f, 0f, false)   // kanan bawah
    corner(l, b, -1f, 0f, 0f, -1f, false)  // kiri bawah
    corner(l, t, 0f, -1f, 1f, 0f, false)   // kiri atas
    close()
}

/** Isian bersudut kontinu: warna rata atau gradien atas→bawah ([color2]), garis tepi opsional. */
class SquircleDrawable(
    private val color: Int, private val radiusPx: Float,
    private val color2: Int? = null, private val strokeColor: Int = 0, private val strokePx: Float = 0f
) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    override fun onBoundsChange(bounds: android.graphics.Rect) {
        path.reset(); path.addContinuousRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), radiusPx)
        paint.shader = null   // gaya rata: color2 diabaikan (tanpa gradien)
    }
    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawPath(path, paint)
        if (strokePx > 0f && strokeColor != 0) {
            val sh = paint.shader; paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = strokePx; paint.color = strokeColor
            canvas.drawPath(path, paint)
            paint.shader = sh
        }
    }
    override fun getOutline(outline: android.graphics.Outline) {
        if (Build.VERSION.SDK_INT >= 30) outline.setPath(path) else outline.setRoundRect(bounds, radiusPx)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** Bentuk bersudut kontinu (pengganti [shape] untuk kartu, ubin, sampul). */
fun Context.squircle(color: Int, radiusDp: Int = 20, color2: Int? = null, strokeColor: Int = 0, strokeDp: Float = 0f): SquircleDrawable =
    SquircleDrawable(color, dp(radiusDp).toFloat(), color2, strokeColor, strokeDp * resources.displayMetrics.density)

/** Warna "ikon Pengaturan": versi cerah & pekat dari warna kategori, sama di tema terang maupun gelap. */
fun vivid(c: Int, lighter: Boolean = false): Int {
    val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
    return Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1].coerceAtLeast(0.55f) * 1.0f).coerceAtMost(0.82f), if (lighter) 0.95f else 0.80f))
}

/**
 * Isian permukaan rata (tanpa gradien). Kartu di tema gelap diberi garis tepi tipis karena bayangan
 * hampir tak terlihat di latar gelap. [pressed] = isian kolom/area masuk (SURFACE2).
 */
class SurfaceDrawable(
    private val fill: Int,
    private val radiusPx: Float,
    @Suppress("unused") private val depthPx: Float,
    private val pressed: Boolean = false,
    private val strokePx: Float = 0f
) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()
    private val path = android.graphics.Path()
    private val pathFor = android.graphics.Rect()   // batas tempat [path] terakhir dibangun

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return
        val r = minOf(radiusPx, rect.width() / 2f, rect.height() / 2f)
        paint.style = Paint.Style.FILL
        paint.color = if (pressed && fill == C.SURFACE) C.SURFACE2 else fill
        // jalur sudut kontinu (12 kurva) hanya dibangun ulang bila ukurannya berubah, bukan setiap kali digambar
        if (pathFor != bounds) {
            pathFor.set(bounds)
            path.reset(); path.addContinuousRect(rect.left, rect.top, rect.right, rect.bottom, r)
        }
        canvas.drawPath(path, paint)
        if (false && strokePx > 0f && fill == C.SURFACE && !pressed) {   // gaya rata: tanpa garis tepi
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokePx
            paint.color = C.LINE
            rect.inset(strokePx / 2f, strokePx / 2f)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** Matikan pemotongan anak pada semua induk [v] sampai (tidak termasuk) ScrollView terdekat. */
fun unclipAncestors(v: View) {
    var p = v.parent
    while (p is ViewGroup) {
        if (p is ScrollView || p is android.widget.HorizontalScrollView) break
        p.clipChildren = false
        p.clipToPadding = false
        p = p.parent
    }
}

fun Context.raised(fill: Int = C.SURFACE, radiusDp: Int = 16, depthDp: Float = 3f, pressed: Boolean = false): SurfaceDrawable =
    SurfaceDrawable(fill, dp(radiusDp).toFloat(), depthDp * resources.displayMetrics.density, pressed, resources.displayMetrics.density)

/** Latar pilihan: terpilih = wadah warna utama + cincin 2dp; tidak = permukaan bergaris tepi. */
fun Context.choiceBg(on: Boolean, radiusDp: Int = 14): android.graphics.drawable.Drawable =
    if (on) shape(C.PRIMARY_C, radiusDp, C.PRIMARY, 2)
    else shape(C.SURFACE, radiusDp, C.LINE, 1)

/** Thumbnail halaman putih: garis tipis supaya tepi tetap terlihat di kedua tema. */
fun Context.paperThumbBg(radiusDp: Int = 8): GradientDrawable =
    shape(Color.WHITE, radiusDp, C.LINE, 1)

/** Kontrol melayang di atas isi (zoom, navigasi halaman): permukaan, garis tipis, bayangan lembut. */
fun View.floating(radiusDp: Int) {
    val ctx = context
    background = ctx.squircle(C.SURFACE, radiusDp, strokeColor = C.LINE, strokeDp = 1f)
    outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            if (view.width <= 0 || view.height <= 0) return
            outline.setRoundRect(0, 0, view.width, view.height, minOf(ctx.dp(radiusDp).toFloat(), view.height / 2f))
        }
    }
    elevation = ctx.dp(10).toFloat()
    outlineAmbientShadowColor = 0x22000000; outlineSpotShadowColor = 0x33000000
}

/** Bayangan lembut (hanya terlihat di tema terang; tema gelap memakai garis tepi kartu). */
fun View.elevate(radiusDp: Int, elevationDp: Float = 3f, tint: Int = C.TEXT) {
    val ctx = context
    outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            if (view.width <= 0 || view.height <= 0) return
            outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(radiusDp).toFloat())
        }
    }
    clipToOutline = false
    elevation = 0f   // gaya rata: kedalaman lewat warna permukaan & kaca, bukan bayangan
    outlineAmbientShadowColor = tint
    outlineSpotShadowColor = tint
}

/** Satu keluarga huruf (Funnel Display). Ukuran mengikuti setelan huruf sistem. */
object Fonts {
    private val cache = HashMap<Int, Typeface?>()
    private fun get(ctx: Context, id: Int): Typeface? = synchronized(cache) {
        cache.getOrPut(id) { try { ResourcesCompat.getFont(ctx, id) } catch (_: Exception) { null } }
    }

    /** Baca ketiga berkas huruf di thread latar saat aplikasi mulai — layar pertama tidak menunggu membacanya. */
    fun warm(ctx: Context) {
        val app = ctx.applicationContext
        Perf.io.execute { try { body(app); label(app); head(app) } catch (_: Throwable) {} }
    }

    // Funnel Display untuk seluruh antarmuka. Ketebalan maksimum SemiBold (tidak ada Bold):
    // isi = Regular, label & tombol = Medium, judul = SemiBold.
    fun body(ctx: Context): Typeface = get(ctx, R.font.funnel_display_regular) ?: Typeface.DEFAULT
    fun label(ctx: Context): Typeface = get(ctx, R.font.funnel_display_medium) ?: Typeface.DEFAULT
    fun head(ctx: Context): Typeface = get(ctx, R.font.funnel_display_semibold) ?: Typeface.DEFAULT
    fun headSemi(ctx: Context): Typeface = label(ctx)
}

/** Ukuran teks minimum demi keterbacaan (aturan aksesibilitas: tidak ada teks di bawah 12sp). */
const val MIN_SP = 12f

fun Context.tv(t: String, sp: Float = 15f, color: Int = C.TEXT, bold: Boolean = false): TextView {
    val ctx = this
    val size = sp.coerceAtLeast(MIN_SP)
    return TextView(ctx).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (size >= 28f) letterSpacing = -0.03f else if (size >= 20f) letterSpacing = -0.02f
        setLineSpacing(0f, if (size >= 20f) 1.12f else 1.25f)
        typeface = when {
            bold -> Fonts.head(ctx)
            else -> Fonts.body(ctx)
        }
    }
}

/** Judul (ditandai sebagai heading untuk TalkBack sehingga bisa dilompati per judul). */
fun Context.headingTv(t: String, sp: Float = 20f, color: Int = C.TEXT): TextView =
    tv(t, sp, color, true).apply { markHeading() }

fun View.markHeading() {
    isAccessibilityHeading = true
}

/** Ikon dekoratif (diabaikan pembaca layar). Untuk ikon yang bisa diketuk, pakai [iconBtn]. */
/** Ikon garis (vektor Depup) di depan teks tombol/label — pengganti emoji. */
fun TextView.leadingIcon(res: Int, tint: Int, sizeDp: Int = 18) {
    val d = androidx.core.content.ContextCompat.getDrawable(context, res)?.mutate() ?: return
    d.setTint(tint)
    val px = context.dp(sizeDp)
    d.setBounds(0, 0, px, px)
    setCompoundDrawablesRelative(d, null, null, null)
    compoundDrawablePadding = context.dp(6)
    gravity = gravity or android.view.Gravity.CENTER_VERTICAL
}

fun Context.icon(res: Int, tint: Int, sizeDp: Int = 22): ImageView = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(tint)
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** Latar ripple bundar/kotak tanpa isi, untuk tombol ikon & item yang bisa diketuk. */
fun Context.rippleBg(radiusDp: Int = 100, fill: Int? = null): android.graphics.drawable.Drawable =
    RippleDrawable(ColorStateList.valueOf(C.RIPPLE), fill?.let { shape(it, radiusDp) }, shape(Color.WHITE, radiusDp))

// ------------------------------------------------------------------ bilah atas layar kerja (editor & pembaca)
// Gaya kaca ala iOS 26, sama dengan tombol cari di beranda: tombol bulat & kapsul putih bergaris tipis di atas bilah
// kaca, aksi utama berupa kapsul tinta. Satu bahasa desain untuk editor Word, editor Excel & pembaca.

/** Tombol bulat 40 dp di bilah atas (kembali, dll.). */
fun Context.navCircle(res: Int, desc: String, tint: Int = C.TEXT, onClick: (View) -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        val p = dp(9); setPadding(p, p, p, p)
        contentDescription = desc
        tooltipText = desc
        isClickable = true; isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), shape(C.SURFACE, 100, C.LINE, 1), null)
        setOnClickListener { onClick(it) }
        springPress(0.88f)
    }

/** Kapsul pengelompok tombol ikon (mis. urungkan/ulangi, aksi pembaca) — isi dengan [capsuleBtn]. */
fun Context.navCapsule(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = shape(C.SURFACE, 100, C.LINE, 1)
    setPadding(dp(2), 0, dp(2), 0)
}

/** Tombol ikon di dalam [navCapsule]; riak membulat. */
fun Context.capsuleBtn(res: Int, desc: String, tint: Int = C.TEXT, padDp: Int = 9, onClick: (View) -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        val p = dp(padDp); setPadding(p, p, p, p)
        contentDescription = desc
        tooltipText = desc
        isClickable = true; isFocusable = true
        background = rippleBg(100)
        setOnClickListener { onClick(it) }
        springPress(0.86f)
    }

/** Aksi utama bilah atas (Simpan, Ekspor): kapsul tinta setinggi tombol lain. */
fun Context.navPrimary(label: String, onClick: () -> Unit): TextView = tv(label, 14f, C.ON_PRIMARY, true).apply {
    gravity = Gravity.CENTER
    setPadding(dp(14), 0, dp(14), 0)
    maxLines = 1
    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(C.PRIMARY, 100), null)
    isClickable = true; isFocusable = true
    setOnClickListener { onClick() }
    springPress(0.94f)
}

/** Tombol ikon 48x48dp dengan label pembaca layar & tooltip (tekan lama). */
fun Context.iconBtn(res: Int, desc: String, tint: Int = C.TEXT, sizeDp: Int = 24, onClick: (View) -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        val p = (dp(48) - dp(sizeDp)) / 2
        setPadding(p, p, p, p)
        contentDescription = desc
        tooltipText = desc
        isClickable = true
        isFocusable = true
        background = rippleBg()
        minimumWidth = dp(48); minimumHeight = dp(48)
        setOnClickListener { onClick(it) }
        layoutParams = ViewGroup.LayoutParams(dp(48), dp(48))
        springPress(0.86f)
    }

/** Jadikan View bisa diketuk dengan umpan balik ripple, target sentuh >= 48dp, dan label opsional. */
fun View.tappable(desc: String? = null, onClick: () -> Unit) {
    isClickable = true
    isFocusable = true
    if (desc != null) contentDescription = desc
    minimumHeight = context.dp(48)
    if (background == null) background = context.rippleBg(12)
    else if (background !is RippleDrawable) background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), background, null)
    setOnClickListener { onClick() }
    springPress(0.975f)
}

fun Context.vbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    clipChildren = false
    clipToPadding = false
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
}

fun LinearLayout.add(
    v: View, top: Int = 0, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, side: Int = 0
) {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.topMargin = context.dp(top)
    p.marginStart = context.dp(side)
    p.marginEnd = context.dp(side)
    addView(v, p)
}

/** Tombol dasar: label + ikon opsional, tinggi minimal 48dp, ripple, sedikit mengecil saat ditekan. */
fun Context.pressable(label: String, iconRes: Int, fg: Int, bg: android.graphics.drawable.Drawable, onClick: () -> Unit): LinearLayout =
    pressable(label, iconRes, fg, bg, false, onClick)

/** [iconEnd] = ikon di kanan teks (mis. "Berikutnya ›"). */
fun Context.pressable(label: String, iconRes: Int, fg: Int, bg: android.graphics.drawable.Drawable, iconEnd: Boolean, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = RippleDrawable(ColorStateList.valueOf(if (Color.luminance(fg) > 0.5f) 0x33FFFFFF else C.RIPPLE), bg, null)
        minimumHeight = dp(52)
        setPadding(dp(22), dp(10), dp(22), dp(10))
        isClickable = true
        isFocusable = true
        contentDescription = label
        if (iconRes != 0 && !iconEnd) add(icon(iconRes, fg, 20), w = dp(20), h = dp(20))
        add(tv(label, 15f, fg, true).apply {
            maxLines = 2; gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, w = WRAP, side = if (iconRes != 0 && !iconEnd) 4 else 0)
        if (iconRes != 0 && iconEnd) add(icon(iconRes, fg, 20), w = dp(20), h = dp(20), side = 4)
        setOnClickListener { haptic(this); onClick() }
        springPress(0.97f)
    }

/** Getar sentuh ringan (ikut setelan getar sentuh di HP). */
fun haptic(v: View) {
    v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
}

/** Tombol utama (terisi) atau sekunder (tonal). Warna teks dipilih otomatis agar selalu kontras. */
fun Context.btn(label: String, filled: Boolean = true, color: Int = C.ACCENT, iconRes: Int = 0, iconEnd: Boolean = false, onClick: () -> Unit): View =
    if (filled) pressable(label, iconRes, C.on(color), shape(color, 100), iconEnd, onClick)
    else pressable(label, iconRes, if (color == C.ACCENT) C.TEXT else color, shape(C.SURFACE, 100, C.LINE, 1), iconEnd, onClick)

/**
 * Teks + ikon dari paket ikon aplikasi di dalam satu baris (untuk tempat yang hanya menerima teks, mis. PopupMenu).
 * Pengganti emoji: ikon tetap seragam gayanya, ikut warna tema, dan tajam di semua HP.
 */
fun Context.withIcon(text: CharSequence, iconRes: Int, color: Int, sizeDp: Int = 16, end: Boolean = true): CharSequence {
    val d = androidx.core.content.ContextCompat.getDrawable(this, iconRes)?.mutate() ?: return text
    d.setTint(color)
    d.setBounds(0, 0, dp(sizeDp), dp(sizeDp))
    val span = android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_CENTER)
    val sb = android.text.SpannableStringBuilder()
    if (end) { sb.append(text).append("  "); val at = sb.length; sb.append("\u2009"); sb.setSpan(span, at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    else { sb.append("\u2009"); sb.setSpan(span, 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.append("  ").append(text) }
    return sb
}

/** Tombol bergaris tepi (aksi ketiga). */
fun Context.outlineBtn(label: String, iconRes: Int = 0, onClick: () -> Unit): View =
    pressable(label, iconRes, C.TEXT, shape(Color.TRANSPARENT, 100, C.LINE_STRONG, 1), onClick)

fun Context.field(hintText: String, multi: Boolean = false, number: Boolean = false, pin: Boolean = false): EditText =
    EditText(this).apply {
        hint = hintText
        setHintTextColor(C.MUTED)
        setTextColor(C.TEXT)
        textSize = 16f
        typeface = Fonts.body(this@field)
        background = shape(C.SURFACE, 14, C.LINE, 1)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minHeight = dp(48)
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
        if (pin) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        // HP dimiringkan: keyboard TIDAK menutup layar penuh (mode "extract") — isian & isinya tetap terlihat
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        // Cincin fokus 2dp warna utama: penanda fokus yang jelas (juga untuk keyboard fisik).
        onFocusChangeListener = View.OnFocusChangeListener { _, has ->
            background = shape(C.SURFACE, 14, if (has) C.INK else C.LINE, if (has) 2 else 1)
        }
    }

/** Baris aksi: ikon dalam wadah, judul, subjudul, chevron. Seluruh baris = satu target sentuh. */
fun Context.actionRow(
    iconRes: Int, title: String, sub: String,
    tint: Int = C.ACCENT, tintBg: Int = C.BLUE_BG, shadow: Boolean = true, onClick: () -> Unit
): View {
    val r = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(12), dp(12))
        background = rippleCard(16)
        if (shadow) elevate(16, 1f)
        minimumHeight = dp(64)
        isClickable = true
        isFocusable = true
        contentDescription = if (sub.isBlank()) title else "$title. $sub"
        setOnClickListener { onClick() }
        springPress(0.975f)
    }
    r.add(iconBadge(iconRes, tint, tintBg, 12), w = dp(40), h = dp(40))
    val col = vbox()
    col.add(tv(title, 16f, C.TEXT, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
    if (sub.isNotBlank()) col.add(tv(sub, 13f, C.MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, top = 2)
    r.add(col, w = 0, weight = 1f, side = 12)
    r.add(icon(R.drawable.ic_chev, C.MUTED, 20), w = dp(20), h = dp(20))
    return r
}

/** Latar kartu + ripple di dalam latar. */
fun Context.rippleCard(radius: Int = 16): android.graphics.drawable.Drawable =
    (if (radius >= 16) 22 else radius).let { r -> RippleDrawable(ColorStateList.valueOf(C.RIPPLE), raised(C.SURFACE, r, 1f), squircle(Color.WHITE, r)) }

/** Kartu permukaan: radius 16, garis tepi halus (gelap) / bayangan tipis (terang). */
fun Context.card(pad: Int = 16, radius: Int = 16, shadow: Boolean = true): LinearLayout = vbox(pad).apply {
    background = raised(C.SURFACE, if (radius >= 16) 22 else radius, 1f)
}

/** Lencana ikon di atas wadah warna kategori (dekoratif). */
fun Context.iconBadge(res: Int, tint: Int, bg: Int = C.SURFACE, radius: Int = 12, iconDp: Int = 22): FrameLayout =
    FrameLayout(this).apply {
        background = if (radius >= 100) shape(bg, radius) else squircle(bg, radius)
        addView(icon(res, tint, iconDp), FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
    }

/** Pil dengan ikon kecil di kiri. */
fun Context.iconPill(iconRes: Int, text: String, fg: Int, bg: Int, sp: Float = 12f): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(bg, 100)
        setPadding(dp(10), dp(5), dp(12), dp(5))
        val f = if (fg == Color.WHITE) C.on(bg) else fg
        add(icon(iconRes, f, 14), w = dp(14), h = dp(14))
        add(tv(text, sp, f, true), w = WRAP, side = 0).also {
            (getChildAt(childCount - 1).layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        }
    }

fun Context.pill(text: String, fg: Int = C.ACCENT, bg: Int = C.BLUE_BG, sp: Float = 12f): TextView =
    tv(text, sp, if (fg == Color.WHITE) C.on(bg) else fg, true).apply {
        background = shape(bg, 100)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

/** Judul seksi + tautan opsional ("Lihat semua") dengan target sentuh 48dp. */
fun Context.sectionTitle(title: String, link: String? = null, onLink: (() -> Unit)? = null): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        add(headingTv(title, 18f), w = 0, weight = 1f)
        if (link != null) add(tv(link, 14f, C.MUTED, true).apply {
            // panah teks diganti chevron dari paket ikon
            val ch = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_chev)?.mutate()?.apply {
                setTint(C.MUTED); setBounds(0, 0, dp(14), dp(14))
            }
            setCompoundDrawablesRelative(null, null, ch, null)
            compoundDrawablePadding = dp(4)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(4), 0)
            minHeight = dp(48)
            tappable { onLink?.invoke() }
        }, w = WRAP)
    }

/** Kartu ubin (grid 2 kolom): ikon, label opsional, judul, deskripsi. Satu target sentuh. */
fun Context.tile(
    iconRes: Int, tint: Int, tintBg: Int, title: String, sub: String,
    tag: String? = null, tagFg: Int = tint, tagBg: Int = tintBg, chev: Boolean = false, onClick: () -> Unit
): LinearLayout {
    val ctx = this
    val box = vbox(14)
    box.background = rippleCard(16)
    box.minimumHeight = dp(132)
    box.springPress(0.97f)
    box.isClickable = true
    box.isFocusable = true
    box.contentDescription = listOfNotNull(title, sub, tag).joinToString(". ")
    box.setOnClickListener { onClick() }

    val head = LinearLayout(ctx)
    head.orientation = LinearLayout.HORIZONTAL
    head.gravity = Gravity.CENTER_VERTICAL
    head.add(iconBadge(iconRes, tint, tintBg, 12, 22), w = dp(42), h = dp(42))
    head.add(View(ctx), w = 0, h = dp(1), weight = 1f)
    if (tag != null) {
        val muted = tagFg == C.MUTED
        head.add(pill(tag, if (muted) C.MUTED else tagFg, if (muted) C.SURFACE2 else tagBg, 12f), w = WRAP, h = WRAP)
    }
    box.add(head)
    box.add(tv(title, 16f, C.TEXT, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, top = 12)
    box.add(tv(sub, 13f, C.MUTED).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }, top = 3)
    return box
}

/** Garis kemajuan: [frac] 0..1. */
fun Context.progressBar(frac: Float, color: Int = C.ACCENT, track: Int = C.TRACK, heightDp: Int = 8): View {
    val f = frac.coerceIn(0f, 1f)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(track, 100)
        if (f > 0f) add(View(context).apply {
            background = shape(color, 100)
            growX()
        }, w = 0, h = MATCH, weight = f)
        if (f < 1f) add(View(context), w = 0, h = MATCH, weight = 1f - f)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

/** Bar bertumpuk (penyimpanan). */
fun Context.stackedBar(parts: List<Pair<Long, Int>>, track: Int = C.TRACK, heightDp: Int = 10): View {
    val total = parts.sumOf { it.first }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(track, 100)
        clipToOutline = true
        if (total > 0) {
            // semua bagian tersingkap bersama dari kiri sebagai satu bilah (bukan bergantian)
            val fill = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            parts.filter { it.first > 0 }.forEach { (v, col) ->
                fill.add(View(context).apply { setBackgroundColor(col) }, w = 0, h = MATCH, weight = v.toFloat() / total)
            }
            add(fill, w = MATCH, h = MATCH)
            fill.wipeIn()
        }
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

/** Kartu kecil: ikon + label. */
fun Context.miniTile(iconRes: Int, label: String, tint: Int = C.INDIGO): LinearLayout = vbox(10).apply {
    background = shape(C.SURFACE2, 12)
    gravity = Gravity.CENTER_HORIZONTAL
    add(icon(iconRes, tint, 20), w = dp(20), h = dp(20))
    add(tv(label, 12f, C.TEXT, true).apply { gravity = Gravity.CENTER }, top = 6)
}

/** Deretan item sama lebar dengan jarak [gapDp]. */
fun Context.evenRow(views: List<View>, gapDp: Int = 8): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    views.forEachIndexed { i, v ->
        add(v, w = 0, h = MATCH, weight = 1f).also {
            if (i > 0) (v.layoutParams as LinearLayout.LayoutParams).marginStart = dp(gapDp)
        }
    }
}

/** Ilustrasi bingkai pindai (sudut + kertas). */
class ScanFrameView(ctx: Context) : View(ctx) {
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (C.dark) 0xFFE8E6E1.toInt() else Color.WHITE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55151823 }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        corner.color = C.PANEL_BTN
        val w = width.toFloat(); val h = height.toFloat()
        val pw = w * 0.34f; val ph = h * 0.74f
        val pl = (w - pw) / 2f; val pt = (h - ph) / 2f
        c.drawRoundRect(pl, pt, pl + pw, pt + ph, dp(4).toFloat(), dp(4).toFloat(), paper)
        for (i in 0..4) {
            val y = pt + ph * (0.16f + i * 0.16f)
            c.drawRoundRect(pl + pw * 0.12f, y, pl + pw * (if (i % 2 == 0) 0.88f else 0.62f), y + dp(3), dp(2).toFloat(), dp(2).toFloat(), ink)
        }
        val m = dp(10).toFloat(); val L = dp(20).toFloat()
        val x1 = pl - m; val y1 = pt - m; val x2 = pl + pw + m; val y2 = pt + ph + m
        c.drawLines(floatArrayOf(
            x1, y1 + L, x1, y1, x1, y1, x1 + L, y1,
            x2 - L, y1, x2, y1, x2, y1, x2, y1 + L,
            x1, y2 - L, x1, y2, x1, y2, x1 + L, y2,
            x2 - L, y2, x2, y2, x2, y2, x2, y2 - L
        ), corner)
    }
}

/** Dua ubin sejajar. */
fun Context.twoCols(a: View, b: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    clipChildren = false
    clipToPadding = false
    isBaselineAligned = false
    add(a, w = 0, h = MATCH, weight = 1f)
    add(b, w = 0, h = MATCH, weight = 1f).also { (b.layoutParams as LinearLayout.LayoutParams).marginStart = dp(12) }
}

/** Kartu sorotan: wadah lembut warna utama (bukan blok gelap) — teks di dalamnya pakai ON_PRIMARY_C. */
fun Context.hero(pad: Int = 20): LinearLayout = vbox(pad).apply {
    // panel tinta dengan kilau halus di bagian atas (gradien tipis), sudut kontinu
    background = squircle(C.PANEL, 24)
}

/** Ubin angka (bento): angka besar + label. [ink] = versi panel tinta kontras tinggi. Satu target sentuh. */
fun Context.statTile(value: String, label: String, ink: Boolean = false, iconRes: Int = 0, onClick: () -> Unit): LinearLayout = vbox(16).apply {
    val fg = if (ink) C.ON_PANEL else C.TEXT
    background = RippleDrawable(ColorStateList.valueOf(if (ink) 0x33FFFFFF else C.RIPPLE),
        if (ink) squircle(C.PANEL, 20) else squircle(C.SURFACE, 20), squircle(Color.WHITE, 20))
    springPress(0.97f)
    minimumHeight = dp(96)
    isClickable = true; isFocusable = true
    contentDescription = "$value $label"
    setOnClickListener { onClick() }
    val top = hbox()
    top.add(tv(value, 30f, fg, true).apply { maxLines = 1; includeFontPadding = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, w = 0, weight = 1f)
    if (iconRes != 0) top.add(FrameLayout(context).apply {
        background = shape(if (ink) 0x26FFFFFF else C.SOFT, 100)
        addView(icon(iconRes, fg, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
    }, w = dp(36), h = dp(36))
    add(top)
    add(tv(label, 13f, if (ink) C.ON_PANEL_MUTED else C.MUTED).apply { maxLines = 1; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, top = 6)
}

/** Judul halaman besar (gaya pustaka): 34sp, tebal, rapat. Ditandai heading untuk TalkBack. */
fun Context.largeTitle(t: String, second: String? = null): TextView = tv(t, 32f, C.TEXT, true).apply {
    // judul besar rapat; baris kedua opsional berwarna abu (dua nada, tanpa warna tambahan)
    markHeading(); maxLines = 3; includeFontPadding = false; letterSpacing = -0.025f; setLineSpacing(0f, 1.06f)
    typeface = Fonts.head(context)
    if (second != null) text = android.text.SpannableString("$t\n$second").apply {
        setSpan(android.text.style.ForegroundColorSpan(C.MUTED), t.length + 1, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/**
 * "Sampul" bergradien untuk kartu korsel: warna kategori dibuat hidup seperti sampul album,
 * dengan ikon/teks putih di atasnya. [label] opsional tampil besar di pojok kiri bawah.
 */
fun Context.cover(color: Int, iconRes: Int = 0, label: String? = null, radiusDp: Int = 14): FrameLayout = FrameLayout(this).apply {
    // rata: wadah lembut netral, ikon & label memakai warna kategori (tanpa gradien)
    background = squircle(C.SOFT, radiusDp)
    if (iconRes != 0) addView(icon(iconRes, color, 28), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
    if (label != null) addView(tv(label, 12f, color, true).apply {
        maxLines = 1; letterSpacing = 0.04f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = shape(C.SURFACE, 100); setPadding(dp(8), dp(2), dp(8), dp(2))
    }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })
}

/** Kartu korsel: sampul persegi + judul & subjudul di bawahnya (seperti deretan album). Satu target sentuh. */
fun Context.coverCard(cover: View, title: String, sub: String, sizeDp: Int = 148, desc: String? = null, onClick: () -> Unit): LinearLayout =
    vbox(8).apply {
        background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), squircle(C.SURFACE, 20), squircle(Color.WHITE, 20))
        isClickable = true; isFocusable = true
        contentDescription = desc ?: "$title. $sub"
        setOnClickListener { haptic(this); onClick() }
        add(cover, w = dp(sizeDp), h = dp((sizeDp * 0.62f).toInt()))
        val txt = vbox().apply { setPadding(dp(6), dp(10), dp(6), dp(6)) }
        txt.add(tv(title, 14f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, w = dp(sizeDp - 12))
        txt.add(tv(sub, 13f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, top = 2, w = dp(sizeDp - 12))
        add(txt)
        springPress(0.97f)
    }

/** Korsel mendatar yang menjorok melewati tepi halaman (isi "mengintip" di kanan), jarak antar kartu 12dp. */
fun Context.carousel(cards: List<View>, bleedDp: Int = 20): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(bleedDp), 0, dp(bleedDp), 0) }
    cards.forEachIndexed { i, c ->
        row.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(12) })
    }
    // seluruh deret kartu bergeser masuk dari kanan sebagai satu blok (tanpa hilang dulu, tanpa bergantian)
    row.glide(60L, 0, Motion.SHIFT_DP)
    return android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
        addView(row)
    }
}

/** Tombol utama di dalam kartu sorotan. */
fun Context.heroBtn(label: String, iconRes: Int = 0, onClick: () -> Unit): View =
    pressable(label, iconRes, C.ON_PANEL_BTN, shape(C.PANEL_BTN, 100), onClick)

/** Segmen pilihan (seperti tab kecil): tiap opsi >= 48dp, status terpilih diumumkan TalkBack. */
fun Context.segmented(labels: List<String>, selected: Int, waitThumb: Boolean = false, onPick: (Int) -> Unit): LinearLayout {
    // ibu jari tinta meluncur rapi antar-segmen (lihat SegmentedBar), teks berganti warna mengikutinya
    val row = SegmentedBar(this, selected, waitThumb, onPick).apply {
        background = shape(C.SURFACE, 100, C.LINE, 1)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    labels.forEach { row.addSegment(it) }
    return row
}

/** Chip filter (bisa diketuk, 40dp + area sentuh 48dp lewat margin). */
fun Context.filterChip(label: String, on: Boolean, iconRes: Int = 0, onClick: () -> Unit): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val fg = if (on) C.ON_INK else C.TEXT
        background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE),
            if (on) shape(C.INK, 100) else shape(C.SURFACE, 100, C.LINE, 1), null)
        setPadding(dp(14), 0, dp(16), 0)
        minimumHeight = dp(40)
        isClickable = true; isFocusable = true
        isSelected = on
        contentDescription = label + if (on) ", dipilih" else ""
        if (on) add(icon(R.drawable.ic_check, fg, 16), w = dp(16), h = dp(16), side = 0)
        else if (iconRes != 0) add(icon(iconRes, fg, 16), w = dp(16), h = dp(16))
        add(tv(label, 14f, fg, true), w = WRAP, side = if (on || iconRes != 0) 6 else 0)
        setOnClickListener { onClick() }
        springPress(0.93f)
    }

/** Keadaan kosong yang ramah: ikon, judul, penjelasan, dan aksi opsional. */
fun Context.emptyState(iconRes: Int, title: String, msg: String, action: String? = null, onAction: (() -> Unit)? = null): View =
    vbox(20).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        background = squircle(C.SURFACE, 22)
        add(iconBadge(iconRes, C.MUTED, C.SURFACE2, 100, 26).apply { post { if (isShown) boing(0.7f, 80L) } }, w = dp(56), h = dp(56))
        add(tv(title, 16f, C.TEXT, true).apply { gravity = Gravity.CENTER }, top = 12, w = WRAP)
        add(tv(msg, 14f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 4)
        if (action != null && onAction != null) add(btn(action, false, C.ACCENT) { onAction() }, top = 14, w = WRAP)
    }

/**
 * Jendela dialog TIDAK mewarisi FLAG_SECURE dari activity: tanpa ini, lembar yang menampilkan isi dokumen terkunci
 * (slideshow, susun halaman, pembaca suara) bisa ditangkap layar / direkam.
 */
fun Dialog.inheritSecure(owner: Activity) {
    val secure = (owner.window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
    if (secure) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

// ================= BOTTOM SHEET =================

/** Dialog bergaya bottom sheet (sudut atas 24dp, handle, latar redup). */
/**
 * Wadah lembar bawah yang bisa DITARIK seperti lembar iOS: tarik ke bawah (saat isi sudah di paling atas) → lembar
 * mengikuti jari dan latar meredup berkurang; dilepas jauh/cepat → lembar meluncur pergi searah lemparan; dilepas
 * dekat → memantul kembali dengan pegas.
 * Ketukan biasa ke tombol di dalam lembar tidak terganggu (diambil alih hanya setelah jari benar-benar menyeret).
 */
@SuppressLint("ViewConstructor")
class SheetFrame(ctx: Context, private val scroll: ScrollView) : FrameLayout(ctx) {
    /** Lembar dilempar/ditarik jauh: [vy] kecepatan (px/dtk) untuk meneruskan gerak. */
    var onFling: ((vy: Float) -> Unit)? = null
    /** 0 = posisi normal, 1 = sudah turun setinggi lembar (untuk meredupkan latar). */
    var onDrag: ((frac: Float) -> Unit)? = null
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var vt: android.view.VelocityTracker? = null

    init { addView(scroll, LayoutParams(MATCH, WRAP)) }

    /** Hanya ke bawah: jendela dialog memotong apa pun di atas tepinya (sudut membulat lembar ikut terpotong). */
    private fun offsetFor(dy: Float): Float = dy.coerceAtLeast(0f)

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; dragging = false
                vt?.recycle(); vt = android.view.VelocityTracker.obtain(); vt?.addMovement(ev)
                scroll.animate().cancel()
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                if (!dragging && dy > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f && !scroll.canScrollVertically(-1)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downY = ev.rawY - slop   // tanpa lompatan saat mulai menyeret
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> return true
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val off = offsetFor(ev.rawY - downY)
                scroll.translationY = off
                onDrag?.invoke((off / scroll.height.coerceAtLeast(1)).coerceIn(0f, 1f))
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return true
                dragging = false
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                val off = scroll.translationY
                val far = off > scroll.height * 0.32f
                val fast = vy > context.dp(1100)
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && (far || fast) && vy > -context.dp(300)) {
                    onFling?.invoke(vy)
                } else {
                    // kembali ke tempatnya dengan pantulan pegas
                    scroll.animate().translationY(0f).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING)
                        .setUpdateListener { onDrag?.invoke((scroll.translationY / scroll.height.coerceAtLeast(1)).coerceIn(0f, 1f)) }
                        .start()
                }
            }
        }
        return true
    }
}

fun AppCompatActivity.sheet(build: (body: LinearLayout, dismiss: () -> Unit) -> Unit): Dialog {
    // ComponentDialog: tombol/gestur Kembali lewat dispatcher (wajib untuk kembali prediktif Android 14+)
    val d = androidx.activity.ComponentDialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val body = vbox(0)
    val r = dp(24).toFloat()
    body.background = GradientDrawable().apply {
        setColor(if (C.dark) 0xFF121214.toInt() else C.BG)   // lembar di atas halaman: kartu abu di dalamnya tetap terlihat
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
    val handle = View(this).apply { background = shape(C.LINE_STRONG, 4); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    val hw = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
    hw.add(handle, w = dp(40), h = dp(4))
    body.add(hw, top = 10)
    val scroll = ScrollView(this).apply { addView(body) }
    val frame = SheetFrame(this, scroll)
    val dim = if (C.dark) 0.6f else 0.4f
    var closing = false
    fun dismissNow() { try { d.dismiss() } catch (_: Exception) {} }
    // Tutup: lembar turun + memudar sebagai satu bagian (bukan hilang mendadak); juga untuk tombol Kembali.
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            if (Motion.on() && d.isShowing) body.animate().translationY(dp(48).toFloat()).alpha(0f).setStartDelay(0)
                .setDuration(Motion.FAST + 50L).setInterpolator(Motion.EXIT).setUpdateListener(null).withLayer()
                .withEndAction { dismissNow() }.start()
            else dismissNow()
        }
    }
    // Ditarik/dilempar: lembar meneruskan gerak jari sampai keluar layar (cepat = singkat), latar ikut terang
    frame.onFling = { vy ->
        if (!closing) {
            closing = true
            val left = (scroll.height - scroll.translationY).coerceAtLeast(1f)
            val ms = if (vy > 1f) (left / vy * 1000f).toLong().coerceIn(120L, 280L) else 240L
            scroll.animate().translationY(scroll.height.toFloat() + dp(24)).setDuration(ms)
                .setInterpolator(android.view.animation.LinearInterpolator())
                .setUpdateListener { d.window?.setDimAmount(dim * (1f - (scroll.translationY / scroll.height.coerceAtLeast(1)).coerceIn(0f, 1f))) }
                .withEndAction { dismissNow() }.start()
        }
    }
    frame.onDrag = { f -> d.window?.setDimAmount(dim * (1f - f)) }
    build(body, close)
    // Kembali: dengan gestur prediktif (Android 14+) lembar turun mengikuti ibu jari; dibatalkan → memantul kembali
    d.onBackPressedDispatcher.addCallback(d, object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackProgressed(backEvent: androidx.activity.BackEventCompat) {
            val p = backEvent.progress
            scroll.translationY = dp(56) * p
            d.window?.setDimAmount(dim * (1f - 0.35f * p))
        }
        override fun handleOnBackCancelled() {
            scroll.animate().translationY(0f).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).setUpdateListener(null).start()
            d.window?.setDimAmount(dim)
        }
        override fun handleOnBackPressed() = close()
    })
    d.setContentView(frame)
    d.inheritSecure(this)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(0))
        setGravity(Gravity.BOTTOM)
        setLayout(MATCH, WRAP)
        setDimAmount(dim)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    // Masuk: lembar naik dengan pegas lembut (mendarat sedikit memantul), isinya menyusul dalam gelombang singkat
    if (Motion.on()) {
        body.translationY = dp(72).toFloat()
        body.alpha = 0f
        d.setOnShowListener {
            body.animate().translationY(0f).setStartDelay(0).setDuration(Motion.SPRING_SOFT_MS).setInterpolator(Motion.SPRING_SOFT)
                .setUpdateListener(null).start()
            android.animation.ObjectAnimator.ofFloat(body, View.ALPHA, 1f).apply { duration = Motion.FAST + 40L }.start()
            body.cascadeIn(dyDp = 14, fade = true, delay = 40L, skip = 1)   // anak ke-0 = pegangan lembar
        }
    }
    d.show()
    return d
}

fun AppCompatActivity.sheetHeader(body: LinearLayout, iconRes: Int, tint: Int, bg: Int, title: String, sub: String, dismiss: () -> Unit) {
    val h = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(14))
    }
    h.add(iconBadge(iconRes, tint, bg, 12).apply { boing(0.6f, 120L) }, w = dp(44), h = dp(44))
    val c = vbox()
    c.add(headingTv(title, 19f))
    if (sub.isNotBlank()) c.add(tv(sub, 13f, C.MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, top = 2)
    h.add(c, w = 0, weight = 1f, side = 12)
    h.add(iconBtn(R.drawable.ic_close, "Tutup", C.TEXT, 22) { dismiss() }.apply { background = rippleBg(100, C.SURFACE2) }, w = dp(48), h = dp(48))
    body.add(h)
}

class SaveOption(val title: String, val desc: String, val tag: String? = null, val note: String? = null)

/** Modal "Simpan & Ekspor": nama berkas + (opsional) pilihan kualitas. */
fun AppCompatActivity.saveSheet(
    title: String, sub: String, defaultName: String, options: List<SaveOption>?, defaultOpt: Int,
    confirmLabel: String, ext: String = "pdf", onConfirm: (name: String, opt: Int) -> Unit
) = sheet { body, dismiss ->
    sheetHeader(body, R.drawable.ic_pdf, C.ACCENT, C.BLUE_BG, title, sub, dismiss)
    val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
    pad.add(tv("Nama Berkas", 14f, C.TEXT, true))
    val nameRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(C.SURFACE, 14, C.LINE, 1)
        setPadding(dp(14), dp(4), dp(10), dp(4))
    }
    nameRow.add(icon(R.drawable.ic_doc, C.MUTED, 20), w = dp(20), h = dp(20))
    val et = android.widget.EditText(this).apply {
        setText(defaultName)
        setTextColor(C.TEXT)
        textSize = 15f
        typeface = Fonts.body(this@saveSheet)
        isSingleLine = true
        background = null
        setPadding(dp(10), dp(12), dp(10), dp(12))
        minHeight = dp(48)
        contentDescription = "Nama berkas"
        setSelectAllOnFocus(true)
    }
    nameRow.add(et, w = 0, weight = 1f)
    nameRow.add(pill(".$ext", C.ACCENT, C.BLUE_BG, 12f), w = WRAP)
    pad.add(nameRow, top = 8)

    var sel = defaultOpt
    if (options != null) {
        pad.add(tv("Kualitas & Ukuran Berkas", 14f, C.TEXT, true), top = 20)
        val cards = mutableListOf<LinearLayout>()
        val marks = mutableListOf<ImageView>()
        fun paint() {
            cards.forEachIndexed { i, c ->
                val on = i == sel
                c.background = choiceBg(on, 14)
                if (on) {
                    marks[i].setImageResource(R.drawable.ic_check_ok)
                    marks[i].setColorFilter(C.ACCENT)
                } else {
                    marks[i].clearColorFilter()
                    marks[i].setImageDrawable(GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(C.SURFACE2)
                        setSize(dp(22), dp(22))
                    })
                }
            }
        }
        options.forEachIndexed { i, o ->
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                isClickable = true
                isFocusable = true
                minimumHeight = dp(56)
                setOnClickListener { val was = sel; sel = i; paint(); if (was != i) marks[i].boing(0.5f) }
                springPress(0.98f)
            }
            val mk = ImageView(this)
            marks.add(mk)
            c.add(mk, w = dp(22), h = dp(22))
            val col = vbox()
            val t = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            t.add(tv(o.title, 15f, C.TEXT, true), w = WRAP)
            if (o.tag != null) t.add(pill(o.tag, Color.WHITE, C.ACCENT, 10f), w = WRAP, side = 0).also {
                (t.getChildAt(t.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            }
            col.add(t)
            col.add(tv(o.desc, 12f, C.MUTED), top = 3)
            c.add(col, w = 0, weight = 1f, side = 12)
            if (o.note != null) c.add(tv(o.note, 12f, C.TEAL, true), w = WRAP)
            cards.add(c)
            pad.add(c, top = if (i == 0) 10 else 8)
        }
        paint()
    }
    body.add(pad)

    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), dp(12), dp(20), dp(16))
    }
    bar.add(btn("Batal", false) { dismiss() }, w = WRAP)
    bar.add(btn(confirmLabel, true, C.ACCENT, R.drawable.ic_check) {
        val clean = et.text.toString().trim().replace(Regex("[\\/:*?\"<>|]+"), "_").removeSuffix(".$ext")
        dismiss()
        onConfirm(clean.ifBlank { defaultName }, sel)
    }, w = 0, weight = 1f, side = 6)
    body.add(bar)
}

class SheetAction(val iconRes: Int, val label: String, val tint: Int = C.ACCENT, val bg: Int = C.BLUE_BG, val run: () -> Unit)

/** Sheet hasil: tombol utama + daftar aksi (simpan, brankas, WA, bagikan). */
fun AppCompatActivity.resultSheet(
    title: String, sub: String, extra: String, primary: String, onPrimary: () -> Unit, actions: List<SheetAction>
) = sheet { body, dismiss ->
    sheetHeader(body, R.drawable.ic_check_ok, C.TEAL, C.TEAL_BG, title, sub, dismiss)
    val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(8)) }
    if (extra.isNotBlank()) pad.add(tv(extra.trim(), 13f, C.MUTED).apply {
        background = shape(C.SURFACE2, 14)
        setPadding(dp(14), dp(10), dp(14), dp(10))
    })
    pad.add(btn(primary, true, C.ACCENT, R.drawable.ic_doc) { dismiss(); onPrimary() }, top = if (extra.isBlank()) 0 else 12)
    pad.add(tv("Simpan & Bagikan", 14f, C.TEXT, true), top = 18)
    actions.forEachIndexed { i, a ->
        pad.add(actionRow(a.iconRes, a.label, "", a.tint, a.bg) { dismiss(); a.run() }, top = 8)
    }
    body.add(pad)
    body.add(View(this), h = dp(12))
}

fun AppCompatActivity.confirm(title: String, msg: String, ok: String, onCancel: (() -> Unit)? = null, onOk: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        .setPositiveButton(ok) { _, _ -> onOk() }
        .setNegativeButton("Batal") { _, _ -> onCancel?.invoke() }
        .setOnCancelListener { onCancel?.invoke() }.show()
}

fun AppCompatActivity.fail(t: Throwable) {
    val m = when (t) {
        is SecurityException -> "PDF dilindungi password atau tidak bisa dibuka."
        is OutOfMemoryError -> "Memori perangkat tidak cukup untuk file sebesar ini."
        else -> t.message ?: t.javaClass.simpleName
    }
    AlertDialog.Builder(this).setTitle("Gagal").setMessage(m).setPositiveButton("OK", null).show()
}

/**
 * "Sedang diproses": kartu DepUp kecil melayang di tengah (putaran biru + pesan), bukan dialog peringatan bawaan.
 * Tidak bisa ditutup pengguna; pemanggil menutupnya sendiri.
 */
fun AppCompatActivity.busy(msg: String): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(C.SURFACE, 22)
        elevation = dp(10).toFloat()
        setPadding(dp(20), dp(18), dp(24), dp(18))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        contentDescription = msg
    }
    card.add(ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(C.ACCENT) }, w = dp(26), h = dp(26))
    card.add(tv(msg, 15f, C.TEXT, true).apply { setPadding(dp(14), 0, 0, 0); maxWidth = resources.displayMetrics.widthPixels - dp(150) }, w = WRAP)
    // ruang di sekeliling kartu agar bayangannya tidak terpotong jendela
    val frame = FrameLayout(this).apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
    frame.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    d.setContentView(frame)
    d.setCancelable(false)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(0))
        setLayout(WRAP, WRAP)
        setDimAmount(if (C.dark) 0.55f else 0.28f)
        setWindowAnimations(R.style.DlgMotion)
    }
    d.inheritSecure(this)
    d.show()
    return d
}

/** Jalankan pekerjaan berat di thread terpisah dengan dialog "sedang diproses". */
fun <T> AppCompatActivity.work(msg: String, job: () -> T, done: (Result<T>) -> Unit) {
    val d = busy(msg)
    Thread {
        val r = runCatching { job() }
        runOnUiThread {
            try { d.dismiss() } catch (_: Exception) {}
            if (!isFinishing) done(r)
        }
    }.start()
}


/**
 * Dialog ubah nama yang dipakai Beranda & Viewer. Mengubah berkas ASLI; bila tidak diizinkan
 * (mis. file manager hanya memberi akses baca), menawarkan simpan salinan bernama baru di Documents/DepUp Office.
 * [copySource] = sumber isi untuk salinan (boleh salinan cache yang sudah terbaca penuh).
 */
fun AppCompatActivity.renameFlow(origin: android.net.Uri, currentName: String, copySource: android.net.Uri = origin, onDone: (Renamed) -> Unit) {
    val et = field("Nama file baru").apply { setText(currentName.substringBeforeLast('.')) }
    AlertDialog.Builder(this)
        .setTitle("Ubah Nama Berkas")
        .setView(vbox(16).apply { add(et) })
        .setPositiveButton("Simpan Nama") { _, _ ->
            val newName = et.text.toString().trim()
            if (newName.isBlank()) return@setPositiveButton
            val res = try {
                Files.rename(this, origin, newName)
            } catch (e: Files.RenameConflict) {
                toast(e.message ?: "Nama sudah dipakai"); return@setPositiveButton
            }
            if (res != null) {
                toast("Nama file diubah ke ${res.name}")
                onDone(res)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Tidak bisa mengubah file asli")
                    .setMessage("Aplikasi file manager hanya memberi akses baca untuk file ini, jadi namanya tidak bisa diubah di tempat asalnya. Simpan salinan bernama \"$newName\" ke Documents/DepUp Office?")
                    .setPositiveButton("Simpan Salinan") { _, _ ->
                        try {
                            val c = Files.saveCopy(this, copySource, newName, currentName)
                            toast("Salinan disimpan di Documents/DepUp Office: ${c.name}")
                            onDone(c)
                        } catch (t: Throwable) { fail(t) }
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }
        .setNegativeButton("Batal", null)
        .show()
}

/**
 * Jadikan ikon sebagai tombol yang aksesibel: label pembaca layar, tooltip, ripple, dan padding agar
 * target sentuhnya 48x48dp (pasang dengan lebar/tinggi 48dp).
 */
fun ImageView.asButton(desc: String): ImageView = apply {
    contentDescription = desc
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    isFocusable = true
    isClickable = true
    tooltipText = desc
    background = context.rippleBg()
    val p = context.dp(12)
    setPadding(p, p, p, p)
}

/** Dialog pratinjau gambar penuh dengan fitur cubit-zoom (pinch zoom), geser (pan) & ketuk 2x. */
fun AppCompatActivity.showImagePreviewDialog(bmp: Bitmap, title: String = "Pratinjau Gambar") {
    sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_image, C.ACCENT, C.BLUE_BG, title, "${bmp.width} × ${bmp.height} px • Cubit dua jari / ketuk 2x untuk zoom", dismiss)
        val pad = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
        val iv = ZoomImageView(this).apply {
            setImageBitmap(bmp)
            background = shape(C.SURFACE2, 16)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        pad.add(iv, h = dp(420))
        pad.add(btn("Tutup", true, C.ACCENT) { dismiss() }, top = 12)
        body.add(pad)
    }
}
