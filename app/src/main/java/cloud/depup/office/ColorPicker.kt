package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

/**
 * Pemilih warna bebas gaya Excel: warna tema (10 warna × terang/gelap), warna standar, warna terakhir dipakai,
 * dan warna khusus (kotak saturasi/kecerahan + pita rona + kode hex). 0 = otomatis / tanpa warna.
 */
object ColorPicker {
    private const val PREF = "depup.colors"
    private const val KEY_RECENT = "recent"

    /** Warna tema Office. */
    private val THEME = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE7E6E6.toInt(), 0xFF44546A.toInt(), 0xFF4472C4.toInt(),
        0xFFED7D31.toInt(), 0xFFA5A5A5.toInt(), 0xFFFFC000.toInt(), 0xFF5B9BD5.toInt(), 0xFF70AD47.toInt()
    )
    private val STANDARD = intArrayOf(
        0xFFC00000.toInt(), 0xFFFF0000.toInt(), 0xFFFFC000.toInt(), 0xFFFFFF00.toInt(), 0xFF92D050.toInt(),
        0xFF00B050.toInt(), 0xFF00B0F0.toInt(), 0xFF0070C0.toInt(), 0xFF002060.toInt(), 0xFF7030A0.toInt()
    )

    /** Terangkan (t > 0) / gelapkan (t < 0) seperti "tint" Excel. */
    private fun tint(c: Int, t: Float): Int {
        fun ch(v: Int) = if (t >= 0) (v + (255 - v) * t).toInt() else (v * (1 + t)).toInt()
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    fun recent(ctx: Context): List<Int> =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_RECENT, "").orEmpty()
            .split(',').mapNotNull { it.toLongOrNull()?.toInt() }

    private fun remember(ctx: Context, c: Int) {
        if (c == 0) return
        val list = (listOf(c) + recent(ctx).filter { it != c }).take(10)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT, list.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()
    }

    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    /**
     * Tampilkan pemilih. [noneLabel] = pilihan "tanpa warna/otomatis" (mengembalikan 0); null = tidak ada.
     */
    fun show(a: AppCompatActivity, title: String, current: Int, noneLabel: String?, onPick: (Int) -> Unit) = a.sheet { body, dismiss ->
        a.sheetHeader(body, R.drawable.ic_tune, C.ACCENT, C.BLUE_BG, title, if (current != 0) "Sekarang: ${hex(current)}" else "Pilih warna", dismiss)
        val pad = a.vbox(10).apply { setPadding(a.dp(16), 0, a.dp(16), a.dp(20)) }
        fun pick(c: Int) { remember(a, c); dismiss(); onPick(c) }

        fun swatch(c: Int, size: Int = 28): View = View(a).apply {
            val sel = c == current && c != 0
            background = a.shape(c, 6, if (sel) C.TEAL else if (Color.luminance(c) > 0.85f) C.LINE else 0, if (sel) 3 else 1)
            contentDescription = "Warna ${hex(c)}"
            isClickable = true
            setOnClickListener { pick(c) }
            layoutParams = LinearLayout.LayoutParams(a.dp(size), a.dp(size)).apply { marginEnd = a.dp(4); bottomMargin = a.dp(4) }
        }
        fun row(colors: IntArray): LinearLayout = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            colors.forEach { addView(swatch(it)) }
        }

        if (noneLabel != null) {
            pad.add(a.tv(noneLabel, 13f, C.TEXT, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = a.shape(C.SURFACE2, 10)
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
                isClickable = true
                setOnClickListener { dismiss(); onPick(0) }
            })
        }
        pad.add(a.tv("Warna tema", 12f, C.MUTED, true), top = 4)
        pad.add(row(THEME))
        for (t in floatArrayOf(0.8f, 0.6f, 0.4f, -0.25f, -0.5f)) pad.add(row(IntArray(THEME.size) { tint(THEME[it], t) }))
        pad.add(a.tv("Warna standar", 12f, C.MUTED, true), top = 4)
        pad.add(row(STANDARD))
        val rec = recent(a)
        if (rec.isNotEmpty()) {
            pad.add(a.tv("Terakhir dipakai", 12f, C.MUTED, true), top = 4)
            pad.add(row(rec.toIntArray()))
        }

        // warna khusus
        pad.add(a.tv("Warna khusus", 12f, C.MUTED, true), top = 4)
        val hsv = FloatArray(3)
        Color.colorToHSV(if (current != 0) current else 0xFF4472C4.toInt(), hsv)
        val preview = View(a)
        val hexEt = a.field("#RRGGBB", multi = false).apply {
            filters = arrayOf(InputFilter.LengthFilter(7))
            textSize = 14f
        }
        var syncing = false
        val sv = SvView(a, hsv)
        val hue = HueView(a, hsv)
        fun refresh(fromHex: Boolean) {
            val c = Color.HSVToColor(hsv)
            preview.background = a.shape(c, 10, C.LINE, 1)
            if (!fromHex) { syncing = true; hexEt.setText(hex(c)); hexEt.setSelection(hexEt.text.length); syncing = false }
            sv.invalidate(); hue.invalidate()
        }
        sv.onChange = { refresh(false) }
        hue.onChange = { refresh(false) }
        hexEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val t = s?.toString()?.trim().orEmpty().removePrefix("#")
                if (t.length == 6) t.toLongOrNull(16)?.let { Color.colorToHSV((0xFF000000 or it).toInt(), hsv); refresh(true) }
            }
            override fun beforeTextChanged(s: CharSequence?, a1: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a1: Int, b: Int, c: Int) {}
        })
        val pickRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        pickRow.addView(sv, LinearLayout.LayoutParams(0, a.dp(150), 1f))
        pickRow.addView(hue, LinearLayout.LayoutParams(a.dp(28), a.dp(150)).apply { marginStart = a.dp(10) })
        pad.add(pickRow)
        val hexRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hexRow.addView(preview, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
        hexRow.addView(hexEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = a.dp(10) })
        hexRow.addView(a.btn("Pakai", true, C.PRIMARY) { pick(Color.HSVToColor(hsv)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = a.dp(10) })
        pad.add(hexRow)
        refresh(false)

        body.add(ScrollView(a).apply { addView(pad) }, h = a.dp(460))
    }

    /** Kotak saturasi (→) × kecerahan (↓) untuk rona [hsv]&#91;0&#93;. */
    @SuppressLint("ViewConstructor")
    private class SvView(ctx: Context, private val hsv: FloatArray) : View(ctx) {
        var onChange: () -> Unit = {}
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat() }
        private val rect = RectF()
        private val r = ctx.dp(8).toFloat()

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
            val sat = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
            val dark = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            p.shader = ComposeShader(sat, dark, PorterDuff.Mode.SRC_OVER)
            canvas.drawRoundRect(rect, r, r, p)
            val x = hsv[1] * width; val y = (1 - hsv[2]) * height
            ring.color = Color.WHITE; canvas.drawCircle(x, y, r, ring)
            ring.color = Color.BLACK; canvas.drawCircle(x, y, r + ring.strokeWidth, ring)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            hsv[1] = (e.x / width).coerceIn(0f, 1f)
            hsv[2] = 1f - (e.y / height).coerceIn(0f, 1f)
            onChange()
            return true
        }
    }

    /** Pita rona 0–360° (atas ke bawah). */
    @SuppressLint("ViewConstructor")
    private class HueView(ctx: Context, private val hsv: FloatArray) : View(ctx) {
        var onChange: () -> Unit = {}
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat() }
        private val rect = RectF()
        private val r = ctx.dp(8).toFloat()

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            val colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
            p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), colors, null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, r, r, p)
            val y = hsv[0] / 360f * height
            mark.color = Color.WHITE
            canvas.drawRoundRect(-2f, y - r / 2, width + 2f, y + r / 2, r / 2, r / 2, mark)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            hsv[0] = ((e.y / height).coerceIn(0f, 1f) * 360f).coerceAtMost(359.9f)
            onChange()
            return true
        }
    }
}
