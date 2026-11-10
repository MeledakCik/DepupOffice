package cloud.depup.office

import android.app.Activity
import android.app.Dialog
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
    val BG get() = t(0xFFF5F6FA, 0xFF0E1016)          // latar halaman
    val SURFACE get() = t(0xFFFFFFFF, 0xFF181B23)     // kartu, sheet
    val SURFACE2 get() = t(0xFFEEF0F5, 0xFF232733)    // isian, chip, kolom, baris di dalam kartu
    val SURFACE3 get() = t(0xFFE3E6EE, 0xFF2E3342)    // ditekan / terpilih netral
    val LINE get() = t(0xFFD5D9E3, 0xFF373C4C)        // pemisah halus
    val LINE_STRONG get() = t(0xFF7C8295, 0xFF6A7184) // batas kontrol (>= 3:1)

    // ---------- Teks ----------
    val TEXT get() = t(0xFF151823, 0xFFECEEF4)
    val MUTED get() = t(0xFF535A6C, 0xFFA9AFC1)       // teks sekunder (>= 5.5:1 di semua permukaan)

    // ---------- Warna utama ----------
    val PRIMARY get() = t(0xFF4338CA, 0xFFA8B1FF)
    val ON_PRIMARY get() = t(0xFFFFFFFF, 0xFF171452)
    val PRIMARY_C get() = t(0xFFE7E6FC, 0xFF2E2B6E)   // wadah lembut warna utama
    val ON_PRIMARY_C get() = t(0xFF2E2A87, 0xFFE2E4FF)

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
    val PDF_FG get() = t(0xFFB42318, 0xFFFF9C90); val PDF_BG get() = t(0xFFFDE4E1, 0xFF3B1A18); val PDF_TINT get() = PDF_BG
    val DOC_FG get() = t(0xFF1D4ED8, 0xFF93B6FF); val DOC_BG get() = t(0xFFDDE8FD, 0xFF182A52); val DOC_TINT get() = DOC_BG
    val XLS_FG get() = t(0xFF157A3B, 0xFF7DDDA0); val XLS_BG get() = t(0xFFDBF3E2, 0xFF15301F); val XLS_TINT get() = XLS_BG
    val IMG_FG get() = t(0xFF7A2BC4, 0xFFD3A8FF); val IMG_BG get() = t(0xFFF1E6FD, 0xFF2E1B47); val IMG_TINT get() = IMG_BG

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
    val RIPPLE get() = t(0x1F4338CA, 0x33A8B1FF)

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

    fun isDark(ctx: Context): Boolean = when (mode(ctx)) {
        LIGHT -> false
        DARK -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Hitung ulang token warna & warnai bilah status/navigasi sistem. */
    @Suppress("DEPRECATION")
    fun apply(a: Activity, statusColor: Int? = null) {
        C.dark = isDark(a)
        val w = a.window ?: return
        val bar = statusColor ?: C.BG
        w.statusBarColor = bar
        w.navigationBarColor = C.SURFACE
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

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

/** Bentuk rounded-rect. [strokeColor]/[strokeDp] menggambar garis tepi bila diisi. */
fun Context.shape(color: Int, radiusDp: Int = 16, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0 && strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
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

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return
        val r = minOf(radiusPx, rect.width() / 2f, rect.height() / 2f)
        paint.style = Paint.Style.FILL
        paint.color = if (pressed && fill == C.SURFACE) C.SURFACE2 else fill
        canvas.drawRoundRect(rect, r, r, paint)
        if (strokePx > 0f && fill == C.SURFACE && !pressed) {
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
    elevation = if (C.dark) 0f else ctx.resources.displayMetrics.density * elevationDp.coerceAtMost(4f)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        outlineAmbientShadowColor = tint
        outlineSpotShadowColor = tint
    }
}

/** Satu keluarga huruf (Inter Tight) — paling jelas dibaca di layar kecil. Ukuran mengikuti setelan huruf sistem. */
object Fonts {
    private val cache = HashMap<Int, Typeface?>()
    private fun get(ctx: Context, id: Int): Typeface? = cache.getOrPut(id) {
        try { ResourcesCompat.getFont(ctx, id) } catch (_: Exception) { null }
    }
    fun body(ctx: Context): Typeface = get(ctx, R.font.inter_tight_regular) ?: Typeface.DEFAULT
    fun label(ctx: Context): Typeface = get(ctx, R.font.inter_tight_semibold) ?: Typeface.DEFAULT_BOLD
    fun head(ctx: Context): Typeface = get(ctx, R.font.inter_tight_bold) ?: Typeface.DEFAULT_BOLD
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
        if (size >= 20f) letterSpacing = -0.015f
        setLineSpacing(0f, if (size >= 20f) 1.12f else 1.25f)
        typeface = when {
            bold && size >= 20f -> Fonts.head(ctx)
            bold -> Fonts.label(ctx)
            else -> Fonts.body(ctx)
        }
    }
}

/** Judul (ditandai sebagai heading untuk TalkBack sehingga bisa dilompati per judul). */
fun Context.headingTv(t: String, sp: Float = 20f, color: Int = C.TEXT): TextView =
    tv(t, sp, color, true).apply { markHeading() }

fun View.markHeading() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
}

/** Ikon dekoratif (diabaikan pembaca layar). Untuk ikon yang bisa diketuk, pakai [iconBtn]. */
fun Context.icon(res: Int, tint: Int, sizeDp: Int = 22): ImageView = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(tint)
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** Latar ripple bundar/kotak tanpa isi, untuk tombol ikon & item yang bisa diketuk. */
fun Context.rippleBg(radiusDp: Int = 100, fill: Int? = null): android.graphics.drawable.Drawable =
    RippleDrawable(ColorStateList.valueOf(C.RIPPLE), fill?.let { shape(it, radiusDp) }, shape(Color.WHITE, radiusDp))

/** Tombol ikon 48x48dp dengan label pembaca layar & tooltip (tekan lama). */
fun Context.iconBtn(res: Int, desc: String, tint: Int = C.TEXT, sizeDp: Int = 24, onClick: (View) -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        val p = (dp(48) - dp(sizeDp)) / 2
        setPadding(p, p, p, p)
        contentDescription = desc
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = desc
        isClickable = true
        isFocusable = true
        background = rippleBg()
        minimumWidth = dp(48); minimumHeight = dp(48)
        setOnClickListener { onClick(it) }
        layoutParams = ViewGroup.LayoutParams(dp(48), dp(48))
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
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = RippleDrawable(ColorStateList.valueOf(if (Color.luminance(fg) > 0.5f) 0x33FFFFFF else C.RIPPLE), bg, null)
        minimumHeight = dp(48)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isClickable = true
        isFocusable = true
        contentDescription = label
        if (iconRes != 0) add(icon(iconRes, fg, 20), w = dp(20), h = dp(20))
        add(tv(label, 15f, fg, true).apply {
            maxLines = 2; gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, w = WRAP, side = if (iconRes != 0) 4 else 0)
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(100).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

/** Tombol utama (terisi) atau sekunder (tonal). Warna teks dipilih otomatis agar selalu kontras. */
fun Context.btn(label: String, filled: Boolean = true, color: Int = C.ACCENT, iconRes: Int = 0, onClick: () -> Unit): View =
    if (filled) pressable(label, iconRes, C.on(color), shape(color, 14), onClick)
    else pressable(label, iconRes, if (color == C.ACCENT) C.PRIMARY else color, shape(C.SURFACE2, 14), onClick)

/** Tombol bergaris tepi (aksi ketiga). */
fun Context.outlineBtn(label: String, iconRes: Int = 0, onClick: () -> Unit): View =
    pressable(label, iconRes, C.PRIMARY, shape(Color.TRANSPARENT, 14, C.LINE_STRONG, 1), onClick)

fun Context.field(hintText: String, multi: Boolean = false, number: Boolean = false, pin: Boolean = false): EditText =
    EditText(this).apply {
        hint = hintText
        setHintTextColor(C.MUTED)
        setTextColor(C.TEXT)
        textSize = 16f
        typeface = Fonts.body(this@field)
        background = shape(C.SURFACE2, 12, C.LINE, 1)
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
        // Cincin fokus 2dp warna utama: penanda fokus yang jelas (juga untuk keyboard fisik).
        onFocusChangeListener = View.OnFocusChangeListener { _, has ->
            background = shape(C.SURFACE2, 12, if (has) C.PRIMARY else C.LINE, if (has) 2 else 1)
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
    RippleDrawable(ColorStateList.valueOf(C.RIPPLE), raised(C.SURFACE, radius, 1f), shape(Color.WHITE, radius))

/** Kartu permukaan: radius 16, garis tepi halus (gelap) / bayangan tipis (terang). */
fun Context.card(pad: Int = 16, radius: Int = 16, shadow: Boolean = true): LinearLayout = vbox(pad).apply {
    background = raised(C.SURFACE, radius.coerceAtMost(20), 1f)
    if (shadow) elevate(radius.coerceAtMost(20), 1f)
}

/** Lencana ikon di atas wadah warna kategori (dekoratif). */
fun Context.iconBadge(res: Int, tint: Int, bg: Int, radius: Int = 12, iconDp: Int = 22): FrameLayout =
    FrameLayout(this).apply {
        background = shape(bg, radius)
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
        if (link != null) add(tv(link, 14f, C.PRIMARY, true).apply {
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
    box.elevate(16, 1f)
    box.minimumHeight = dp(132)
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
        if (f > 0f) add(View(context).apply { background = shape(color, 100) }, w = 0, h = MATCH, weight = f)
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
        if (total > 0) parts.filter { it.first > 0 }.forEach { (v, col) ->
            add(View(context).apply { setBackgroundColor(col) }, w = 0, h = MATCH, weight = v.toFloat() / total)
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
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55151823 }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        corner.color = C.ON_PRIMARY_C
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
fun Context.hero(pad: Int = 18): LinearLayout = vbox(pad).apply {
    background = shape(C.PRIMARY_C, 20)
}

/** Tombol utama di dalam kartu sorotan. */
fun Context.heroBtn(label: String, iconRes: Int = 0, onClick: () -> Unit): View =
    pressable(label, iconRes, C.ON_PRIMARY, shape(C.PRIMARY, 14), onClick)

/** Segmen pilihan (seperti tab kecil): tiap opsi >= 48dp, status terpilih diumumkan TalkBack. */
fun Context.segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(C.SURFACE2, 14)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    labels.forEachIndexed { i, l ->
        val on = i == selected
        row.add(tv(l, 14f, if (on) C.ON_PRIMARY else C.TEXT, true).apply {
            gravity = Gravity.CENTER
            background = if (on) shape(C.PRIMARY, 11) else rippleBg(11)
            minHeight = dp(44)
            isSelected = on
            isClickable = true; isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true; info.isChecked = on
                }
            }
            setOnClickListener { onPick(i) }
        }, w = 0, weight = 1f)
    }
    return row
}

/** Chip filter (bisa diketuk, 40dp + area sentuh 48dp lewat margin). */
fun Context.filterChip(label: String, on: Boolean, iconRes: Int = 0, onClick: () -> Unit): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val fg = if (on) C.ON_PRIMARY_C else C.TEXT
        background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE),
            if (on) shape(C.PRIMARY_C, 100, C.PRIMARY, 1) else shape(C.SURFACE, 100, C.LINE_STRONG, 1), null)
        setPadding(dp(14), 0, dp(16), 0)
        minimumHeight = dp(40)
        isClickable = true; isFocusable = true
        isSelected = on
        contentDescription = label + if (on) ", dipilih" else ""
        if (on) add(icon(R.drawable.ic_check, fg, 16), w = dp(16), h = dp(16), side = 0)
        else if (iconRes != 0) add(icon(iconRes, fg, 16), w = dp(16), h = dp(16))
        add(tv(label, 14f, fg, true), w = WRAP, side = if (on || iconRes != 0) 6 else 0)
        setOnClickListener { onClick() }
    }

/** Keadaan kosong yang ramah: ikon, judul, penjelasan, dan aksi opsional. */
fun Context.emptyState(iconRes: Int, title: String, msg: String, action: String? = null, onAction: (() -> Unit)? = null): View =
    vbox(20).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        background = shape(C.SURFACE, 16, C.LINE, 1)
        add(iconBadge(iconRes, C.MUTED, C.SURFACE2, 100, 26), w = dp(56), h = dp(56))
        add(tv(title, 16f, C.TEXT, true).apply { gravity = Gravity.CENTER }, top = 12, w = WRAP)
        add(tv(msg, 14f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 4)
        if (action != null && onAction != null) add(btn(action, false, C.ACCENT) { onAction() }, top = 14, w = WRAP)
    }

// ================= BOTTOM SHEET =================

/** Dialog bergaya bottom sheet (sudut atas 24dp, handle, latar redup). */
fun AppCompatActivity.sheet(build: (body: LinearLayout, dismiss: () -> Unit) -> Unit): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val body = vbox(0)
    val r = dp(24).toFloat()
    body.background = GradientDrawable().apply {
        setColor(C.SURFACE)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
    val handle = View(this).apply { background = shape(C.LINE_STRONG, 4); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    val hw = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
    hw.add(handle, w = dp(40), h = dp(4))
    body.add(hw, top = 10)
    build(body) { d.dismiss() }
    d.setContentView(ScrollView(this).apply { addView(body) })
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(0))
        setGravity(Gravity.BOTTOM)
        setLayout(MATCH, WRAP)
        setDimAmount(if (C.dark) 0.6f else 0.4f)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    // Masuk naik + memudar halus, ala depup.cloud ".anim-panel" (bukan muncul langsung/statis)
    body.translationY = dp(24).toFloat()
    body.alpha = 0f
    d.setOnShowListener {
        body.animate().translationY(0f).alpha(1f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
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
    h.add(iconBadge(iconRes, tint, bg, 12), w = dp(44), h = dp(44))
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
        background = shape(C.SURFACE2, 12, C.LINE, 1)
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
                setOnClickListener { sel = i; paint() }
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

fun AppCompatActivity.confirm(title: String, msg: String, ok: String, onOk: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        .setPositiveButton(ok) { _, _ -> onOk() }
        .setNegativeButton("Batal", null).show()
}

fun AppCompatActivity.fail(t: Throwable) {
    val m = when (t) {
        is SecurityException -> "PDF dilindungi password atau tidak bisa dibuka."
        is OutOfMemoryError -> "Memori perangkat tidak cukup untuk file sebesar ini."
        else -> t.message ?: t.javaClass.simpleName
    }
    AlertDialog.Builder(this).setTitle("Gagal").setMessage(m).setPositiveButton("OK", null).show()
}

fun AppCompatActivity.busy(msg: String): AlertDialog {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(22), dp(22), dp(22))
    }
    row.add(ProgressBar(this), w = dp(32), h = dp(32))
    row.add(tv(msg, 14f), w = WRAP, side = 16)
    return AlertDialog.Builder(this).setView(row).setCancelable(false).show()
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
 * (mis. file manager hanya memberi akses baca), menawarkan simpan salinan bernama baru di Unduhan/Depup Office.
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
                    .setMessage("Aplikasi file manager hanya memberi akses baca untuk file ini, jadi namanya tidak bisa diubah di tempat asalnya. Simpan salinan bernama \"$newName\" ke Unduhan/Depup Office?")
                    .setPositiveButton("Simpan Salinan") { _, _ ->
                        try {
                            val c = Files.saveCopy(this, copySource, newName, currentName)
                            toast("Salinan disimpan di Unduhan/Depup Office: ${c.name}")
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = desc
    background = context.rippleBg()
    val p = context.dp(12)
    setPadding(p, p, p, p)
}
