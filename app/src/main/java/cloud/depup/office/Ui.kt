package cloud.depup.office

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Palet "Depup PDF System" (desain Stitch): kanvas terang, aksen biru PDF, kategori berkode warna. */
object C {
    val BG = 0xFFF8FAFC.toInt()
    val SURFACE = 0xFFFFFFFF.toInt()
    val SURFACE2 = 0xFFF1F5F9.toInt()
    val ACCENT = 0xFF1E60D5.toInt()
    val ACCENT_DARK = 0xFF1A52B8.toInt()
    val ACCENT_SOFT = 0xFF1E60D5.toInt()
    val TEXT = 0xFF0F172A.toInt()
    val MUTED = 0xFF64748B.toInt()
    val LINE = 0xFFE2E8F0.toInt()

    val BLUE_BG = 0xFFEFF6FF.toInt()
    val BLUE_LINE = 0xFFBFDBFE.toInt()
    val INDIGO = 0xFF4F46E5.toInt()
    val INDIGO_BG = 0xFFEEF2FF.toInt()
    val SKY = 0xFF0284C7.toInt()
    val SKY_BG = 0xFFE0F2FE.toInt()
    val AMBER = 0xFFD97706.toInt()
    val AMBER_BG = 0xFFFEF3C7.toInt()
    val TEAL = 0xFF0D9488.toInt()
    val TEAL_BG = 0xFFECFDF5.toInt()
    val RED = 0xFFDC2626.toInt()
    val RED_BG = 0xFFFEF2F2.toInt()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun Context.shape(color: Int, radiusDp: Int = 16, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

/** Plus Jakarta Sans untuk judul, Inter untuk isi & label (sesuai sistem desain). */
object Fonts {
    private val cache = HashMap<Int, Typeface?>()
    private fun get(ctx: Context, id: Int): Typeface? =
        cache.getOrPut(id) { try { ResourcesCompat.getFont(ctx, id) } catch (_: Exception) { null } }
    fun body(ctx: Context): Typeface = get(ctx, R.font.inter_regular) ?: Typeface.DEFAULT
    fun label(ctx: Context): Typeface = get(ctx, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
    fun head(ctx: Context): Typeface = get(ctx, R.font.jakarta_bold) ?: Typeface.DEFAULT_BOLD
    fun headSemi(ctx: Context): Typeface = get(ctx, R.font.jakarta_semibold) ?: Typeface.DEFAULT_BOLD
}

fun Context.tv(t: String, sp: Float = 14f, color: Int = C.TEXT, bold: Boolean = false): TextView {
    val ctx = this
    return TextView(ctx).apply {
        text = t
        textSize = sp
        setTextColor(color)
        typeface = when {
            bold && sp >= 16f -> Fonts.head(ctx)
            bold -> Fonts.label(ctx)
            else -> Fonts.body(ctx)
        }
    }
}

fun Context.icon(res: Int, tint: Int, sizeDp: Int = 22): ImageView = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(tint)
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.vbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
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

/** Tombol dasar: label + ikon opsional di kiri, tengah-rata. */
fun Context.pressable(label: String, iconRes: Int, fg: Int, bg: android.graphics.drawable.Drawable, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = bg
        minimumHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isClickable = true
        isFocusable = true
        if (iconRes != 0) add(icon(iconRes, fg, 20), w = dp(20), h = dp(20))
        add(tv(label, 14f, fg, true), w = WRAP, side = if (iconRes != 0) 4 else 0)
        setOnClickListener { onClick() }
    }

fun Context.btn(label: String, filled: Boolean = true, color: Int = C.ACCENT, iconRes: Int = 0, onClick: () -> Unit): View =
    pressable(
        label, iconRes, if (filled) Color.WHITE else C.ACCENT,
        if (filled) shape(color, 8) else shape(C.BLUE_BG, 8, C.BLUE_LINE, 1), onClick
    )

fun Context.field(hintText: String, multi: Boolean = false, number: Boolean = false, pin: Boolean = false): EditText =
    EditText(this).apply {
        hint = hintText
        setHintTextColor(C.MUTED)
        setTextColor(C.TEXT)
        textSize = 14f
        typeface = Fonts.body(this@field)
        background = shape(C.SURFACE, 8, C.LINE, 1)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
        if (pin) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
    }

fun Context.actionRow(
    iconRes: Int, title: String, sub: String,
    tint: Int = C.ACCENT, tintBg: Int = C.BLUE_BG, onClick: () -> Unit
): View {
    val r = card(12, 16).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rippleCard(16)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
    r.add(iconBadge(iconRes, tint, tintBg), w = dp(40), h = dp(40))
    val col = vbox()
    col.add(tv(title, 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    if (sub.isNotBlank()) col.add(tv(sub, 12f, C.MUTED), top = 2)
    r.add(col, w = 0, weight = 1f, side = 12)
    r.add(icon(R.drawable.ic_chev, C.MUTED, 20), w = dp(20), h = dp(20))
    return r
}

/** Latar kartu + efek ripple di DALAM latar (bukan foreground, supaya tidak menimpa teks/ikon). */
fun Context.rippleCard(radius: Int = 16): android.graphics.drawable.Drawable =
    RippleDrawable(
        ColorStateList.valueOf(0x331E60D5), shape(C.SURFACE, radius, C.LINE, 1), shape(Color.WHITE, radius)
    )

/** Kartu putih: border 1dp + bayangan tipis (Level 1 di sistem desain). */
fun Context.card(pad: Int = 16, radius: Int = 16): LinearLayout = vbox(pad).apply {
    background = shape(C.SURFACE, radius, C.LINE, 1)
    elevation = dp(1).toFloat()
}

/** Lencana ikon vektor di atas latar tint kategori. */
fun Context.iconBadge(res: Int, tint: Int, bg: Int, radius: Int = 12, iconDp: Int = 22): FrameLayout =
    FrameLayout(this).apply {
        background = shape(bg, radius)
        addView(icon(res, tint, iconDp), FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
    }

/** Pil dengan ikon kecil di kiri. */
fun Context.iconPill(iconRes: Int, text: String, fg: Int, bg: Int, sp: Float = 11f): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(bg, 100)
        setPadding(dp(10), dp(4), dp(12), dp(4))
        add(icon(iconRes, fg, 14), w = dp(14), h = dp(14))
        add(tv(text, sp, fg, true), w = WRAP, side = 0).also {
            (getChildAt(childCount - 1).layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        }
    }

fun Context.pill(text: String, fg: Int = C.ACCENT, bg: Int = C.BLUE_BG, sp: Float = 11f): TextView =
    tv(text, sp, fg, true).apply {
        background = shape(bg, 100)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

/** Judul seksi + tautan opsional di kanan ("Lihat Semua"). */
fun Context.sectionTitle(title: String, link: String? = null, onLink: (() -> Unit)? = null): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        add(tv(title, 18f, C.TEXT, true), w = 0, weight = 1f)
        if (link != null) add(tv(link, 13f, C.ACCENT, true).apply {
            setPadding(dp(8), dp(6), 0, dp(6))
            setOnClickListener { onLink?.invoke() }
        }, w = WRAP)
    }

/** Kartu ubin untuk grid 2 kolom: ikon, tag/chevron opsional, judul, deskripsi (maks 2 baris). */
fun Context.tile(
    iconRes: Int, tint: Int, tintBg: Int, title: String, sub: String,
    tag: String? = null, tagFg: Int = tint, tagBg: Int = tintBg, chev: Boolean = false, onClick: () -> Unit
): LinearLayout {
    val ctx = this
    val box = card(16, 16)
    box.background = rippleCard(16)
    box.minimumHeight = dp(150)
    box.isClickable = true
    box.isFocusable = true
    box.setOnClickListener { onClick() }

    val head = LinearLayout(ctx)
    head.orientation = LinearLayout.HORIZONTAL
    head.gravity = Gravity.CENTER_VERTICAL
    head.add(iconBadge(iconRes, tint, tintBg, 14, 24), w = dp(48), h = dp(48))
    head.add(View(ctx), w = 0, h = dp(1), weight = 1f)
    if (tag != null) head.add(pill(tag, tagFg, tagBg, 10f), w = WRAP, h = WRAP)
    else if (chev) head.add(icon(R.drawable.ic_chev, 0xFF94A3B8.toInt(), 18), w = dp(18), h = dp(18))
    box.add(head)

    // Judul & deskripsi dibuat eksplisit (warna, ukuran, tinggi minimal) supaya selalu tampil.
    val titleTv = TextView(ctx).apply {
        text = title
        textSize = 16f
        setTextColor(C.TEXT)
        typeface = Fonts.head(ctx)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        visibility = View.VISIBLE
    }
    val subTv = TextView(ctx).apply {
        text = sub
        textSize = 12f
        setTextColor(C.MUTED)
        typeface = Fonts.body(ctx)
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        visibility = View.VISIBLE
    }
    box.add(titleTv, top = 14)
    box.add(subTv, top = 4)
    return box
}

/** Garis kemajuan: [frac] 0..1. */
fun Context.progressBar(frac: Float, color: Int = C.ACCENT, track: Int = 0xFFDBE4F8.toInt(), heightDp: Int = 8): View {
    val f = frac.coerceIn(0f, 1f)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(track, 100)
        if (f > 0f) add(View(context).apply { background = shape(color, 100) }, w = 0, h = MATCH, weight = f)
        if (f < 1f) add(View(context), w = 0, h = MATCH, weight = 1f - f)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    }
}

/** Bar bertumpuk beberapa segmen (untuk penyimpanan). Nilai 0 semua -> hanya latar. */
fun Context.stackedBar(parts: List<Pair<Long, Int>>, track: Int = 0xFFE2E8F0.toInt(), heightDp: Int = 10): View {
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
    }
}

/** Satu kartu kecil abu-abu: ikon + label (dipakai baris fitur di kartu Buat). */
fun Context.miniTile(iconRes: Int, label: String, tint: Int = C.INDIGO): LinearLayout = vbox(10).apply {
    background = shape(C.SURFACE2, 12)
    gravity = Gravity.CENTER_HORIZONTAL
    add(icon(iconRes, tint, 20), w = dp(20), h = dp(20))
    add(tv(label, 11f, C.TEXT, true).apply { gravity = Gravity.CENTER }, top = 6)
}

/** Deretan item sama lebar dengan jarak 8dp. */
fun Context.evenRow(views: List<View>, gapDp: Int = 8): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    views.forEachIndexed { i, v ->
        add(v, w = 0, h = MATCH, weight = 1f).also {
            if (i > 0) (v.layoutParams as LinearLayout.LayoutParams).marginStart = dp(gapDp)
        }
    }
}

/** Ilustrasi bingkai pindai (sudut putih + kertas) untuk kartu hero Buat. */
class ScanFrameView(ctx: Context) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4D0B2A6B }
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x661E3A8A }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRoundRect(0f, 0f, w, h, dp(14).toFloat(), dp(14).toFloat(), bg)
        val pw = w * 0.34f; val ph = h * 0.74f
        val pl = (w - pw) / 2f; val pt = (h - ph) / 2f
        c.drawRoundRect(pl, pt, pl + pw, pt + ph, dp(4).toFloat(), dp(4).toFloat(), paper)
        for (i in 0..4) {
            val y = pt + ph * (0.16f + i * 0.16f)
            c.drawRoundRect(pl + pw * 0.12f, y, pl + pw * (if (i % 2 == 0) 0.88f else 0.62f), y + dp(3), dp(2).toFloat(), dp(2).toFloat(), ink)
        }
        val m = dp(14).toFloat(); val L = dp(22).toFloat()
        val x1 = m; val y1 = m; val x2 = w - m; val y2 = h - m
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
    isBaselineAligned = false
    add(a, w = 0, h = WRAP, weight = 1f)
    add(b, w = 0, h = WRAP, weight = 1f).also { (b.layoutParams as LinearLayout.LayoutParams).marginStart = dp(12) }
}

/** Kartu gradien biru->indigo untuk hero (scan, brankas). */
fun Context.hero(pad: Int = 20): LinearLayout = vbox(pad).apply {
    background = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1E60D5.toInt(), 0xFF4F46E5.toInt())
    ).apply { cornerRadius = dp(20).toFloat() }
    elevation = dp(3).toFloat()
}

/** Tombol putih besar di atas hero. */
fun Context.heroBtn(label: String, iconRes: Int = 0, onClick: () -> Unit): View =
    pressable(label, iconRes, C.ACCENT, shape(Color.WHITE, 12), onClick)

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
    val handle = View(this).apply { background = shape(C.LINE, 4) }
    val hw = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
    hw.add(handle, w = dp(40), h = dp(4))
    body.add(hw, top = 10)
    build(body) { d.dismiss() }
    d.setContentView(ScrollView(this).apply { addView(body) })
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(0))
        setGravity(Gravity.BOTTOM)
        setLayout(MATCH, WRAP)
        setDimAmount(0.45f)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
    h.add(iconBadge(iconRes, tint, bg, 100), w = dp(44), h = dp(44))
    val c = vbox()
    c.add(tv(title, 18f, C.TEXT, true))
    c.add(tv(sub, 12f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, top = 1)
    h.add(c, w = 0, weight = 1f, side = 12)
    h.add(iconBadge(R.drawable.ic_close, C.TEXT, C.BLUE_BG, 100, 20).apply { setOnClickListener { dismiss() } }, w = dp(40), h = dp(40))
    body.add(h)
    body.add(View(this).apply { setBackgroundColor(C.LINE) }, h = dp(1))
}

class SaveOption(val title: String, val desc: String, val tag: String? = null, val note: String? = null)

/** Modal "Simpan & Ekspor": nama berkas + (opsional) pilihan kualitas. */
fun AppCompatActivity.saveSheet(
    title: String, sub: String, defaultName: String, options: List<SaveOption>?, defaultOpt: Int,
    confirmLabel: String, onConfirm: (name: String, opt: Int) -> Unit
) = sheet { body, dismiss ->
    sheetHeader(body, R.drawable.ic_pdf, C.ACCENT, C.BLUE_BG, title, sub, dismiss)
    val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
    pad.add(tv("Nama Berkas", 14f, C.TEXT, true))
    val nameRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(C.SURFACE, 12, C.LINE, 1)
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
        setSelectAllOnFocus(true)
    }
    nameRow.add(et, w = 0, weight = 1f)
    nameRow.add(pill(".pdf", C.ACCENT, C.BLUE_BG, 12f), w = WRAP)
    pad.add(nameRow, top = 8)

    var sel = defaultOpt
    if (options != null) {
        pad.add(tv("Kualitas & Ukuran Berkas", 14f, C.TEXT, true), top = 20)
        val cards = mutableListOf<LinearLayout>()
        val marks = mutableListOf<ImageView>()
        fun paint() {
            cards.forEachIndexed { i, c ->
                val on = i == sel
                c.background = if (on) shape(C.BLUE_BG, 12, C.ACCENT, 2) else shape(C.SURFACE, 12, C.LINE, 1)
                if (on) {
                    marks[i].setImageResource(R.drawable.ic_check_ok)
                    marks[i].setColorFilter(C.ACCENT)
                } else {
                    marks[i].clearColorFilter()
                    marks[i].setImageDrawable(GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                        setStroke(dp(2), 0xFFCBD5E1.toInt())
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
        val clean = et.text.toString().trim().replace(Regex("[\\/:*?\"<>|]+"), "_").removeSuffix(".pdf")
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
    if (extra.isNotBlank()) pad.add(tv(extra.trim(), 12f, C.MUTED).apply {
        background = shape(C.SURFACE2, 12)
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
