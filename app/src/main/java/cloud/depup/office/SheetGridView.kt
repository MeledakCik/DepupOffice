package cloud.depup.office

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Grid spreadsheet gaya Excel tanpa batas baris/kolom buatan: hanya sel yang TERLIHAT yang digambar (langsung ke
 * kanvas), jadi ukuran tabel hanya dibatasi memori HP.
 *
 * Sentuhan:
 * - ketuk sel = pilih; ketuk dua kali = sunting; geser = gulir (terkunci ke sumbu dominan), lempar = gulir berinersia;
 * - TEKAN LAMA lalu geser = pilih rentang (layar ikut bergulir di tepi); lepas = menu aksi rentang;
 * - tarik bulatan di sudut seleksi = perluas/perkecil seleksi;
 * - ketuk / geser di judul kolom (A, B, …) atau nomor baris = pilih kolom/baris utuh; ketuk sudut kiri-atas = pilih semua;
 * - tarik tepi judul kolom/baris = ubah lebar/tinggi; ketuk dua kali tepinya = sesuaikan otomatis dengan isi.
 *
 * Tampilan: garis kisi, warna isi & teks, tebal/miring/garis bawah/coret, ukuran huruf, perataan (otomatis: angka
 * kanan, teks kiri), teks dibungkus, sel gabungan, teks panjang meluber ke sel kosong di sebelahnya (seperti Excel),
 * angka yang tidak muat tampil "###".
 */
@SuppressLint("ViewConstructor")
class SheetGridView(ctx: Context, private val src: Source) : View(ctx) {

    interface Source {
        fun sheet(): SheetData
        /** Teks tampilan (rumus sudah dihitung, format angka diterapkan); "" = kosong. */
        fun text(r: Int, c: Int): String
        /** Perataan efektif: [HAlign.LEFT], [HAlign.CENTER], atau [HAlign.RIGHT]. */
        fun align(r: Int, c: Int): Int
        fun numeric(r: Int, c: Int): Boolean
        fun selection(): CellRange
        fun activeRow(): Int
        fun activeCol(): Int
        fun gridlines(): Boolean
        /**
         * Seleksi berubah. [kind]: [KIND_CELLS], [KIND_ROWS], [KIND_COLS], [KIND_ALL]. [phase]: [PHASE_START],
         * [PHASE_MOVE], [PHASE_END]. [menu] = dibuat dengan tekan lama / bulatan (tampilkan menu aksi saat selesai).
         */
        fun select(kind: Int, ar: Int, ac: Int, r: Int, c: Int, phase: Int, menu: Boolean)
        fun doubleTap(r: Int, c: Int)
        /** Ukuran kolom/baris berubah (dp). [done] = jari diangkat (catat untuk Urungkan). */
        fun resized(col: Boolean, index: Int, from: Float, sizeDp: Float, done: Boolean)
    }

    companion object {
        const val KIND_CELLS = 0; const val KIND_ROWS = 1; const val KIND_COLS = 2; const val KIND_ALL = 3
        const val PHASE_START = 0; const val PHASE_MOVE = 1; const val PHASE_END = 2
        private const val STRIDE = 4096
        /** Ukuran huruf sel (pt) → sp: 11 pt bawaan tampil 12 sp. */
        private const val SP_PER_PT = 12f / 11f
    }

    private val density = resources.displayMetrics.density
    private fun px(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val headH = px(28f)
    private var headW = px(40f)
    private val textPad = px(5f)
    private val edgeGrab = px(12f)
    private val handleR = px(7f)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    /** Geser isi (piksel) — Double: tabel jutaan baris melampaui presisi Float. */
    private var offX = 0.0
    private var offY = 0.0
    private val scroller = OverScroller(ctx)

    private var pulse = 1f
    private var pulseAnim: ValueAnimator? = null

    private val fillP = Paint().apply { style = Paint.Style.FILL }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lineP = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val selP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cellP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val headP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(11f); textAlign = Paint.Align.CENTER }
    private val rf = RectF()
    private val selRect = RectF()
    private val corner = Path()
    private val cornerFx = android.graphics.CornerPathEffect(px(2.5f))
    private val bodyTf = Fonts.body(ctx)
    private val headTf = Fonts.head(ctx)

    init {
        headP.typeface = headTf
        isFocusable = true
        isClickable = true
    }

    private val sheet get() = src.sheet()

    // ---------- geometri (piksel; ukuran disimpan dp) ----------

    private fun colX(c: Int): Double = sheet.colW.pos(c) * density
    private fun rowY(r: Int): Double = sheet.rowH.pos(r) * density
    private fun colWpx(c: Int): Float = sheet.colW.size(c) * density
    private fun rowHpx(r: Int): Float = sheet.rowH.size(r) * density
    private fun colAt(x: Double): Int = sheet.colW.indexAt(x / density)
    private fun rowAt(y: Double): Int = sheet.rowH.indexAt(y / density)

    private fun metrics() {
        headW = max(px(40f), headP.measureText(sheet.rows.size.coerceAtLeast(1).toString()) + px(16f))
    }

    private fun contentW(): Double = colX(sheet.cols.coerceAtLeast(1))
    private fun contentH(): Double = rowY(sheet.rows.size)
    private fun maxX(): Double = max(0.0, headW + contentW() + px(24f) - width)
    private fun maxY(): Double = max(0.0, headH + contentH() + px(24f) - height)

    private fun clamp() {
        offX = offX.coerceIn(0.0, maxX())
        offY = offY.coerceIn(0.0, maxY())
    }

    /** Tabel pendek tidak mengisi seluruh layar: tinggi mengikuti isi, paling tinggi sebesar ruang yang ada. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val want = (headH + contentH() + px(8f)).let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }
        setMeasuredDimension(w, resolveSize(want, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { metrics(); clamp() }

    /** Struktur berubah (baris/kolom/ukuran/sheet): ukur ulang & gambar ulang. */
    fun structureChanged() {
        snapSel = true
        metrics(); clamp()
        requestLayout(); invalidate()
        a11y.invalidateRoot()
    }

    fun scrollToOrigin() { scroller.forceFinished(true); offX = 0.0; offY = 0.0; invalidate() }

    // ---------- gambar ----------

    private val mergesInView = ArrayList<CellRange>()

    override fun onDraw(canvas: Canvas) {
        metrics()
        val sh = sheet
        val rows = sh.rows.size; val cols = sh.cols
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(C.SURFACE)
        if (cols <= 0) return

        val r0 = if (rows > 0) rowAt(offY).coerceIn(0, rows - 1) else 0
        val r1 = if (rows > 0) rowAt(offY + h - headH).coerceIn(0, rows - 1) else -1
        val c0 = colAt(offX).coerceIn(0, cols - 1)
        val c1 = colAt(offX + w - headW).coerceIn(0, cols - 1)

        mergesInView.clear()
        if (r1 >= r0) {
            val view = CellRange(r0, c0, r1, c1)
            for (m in sh.merges) if (m.intersects(view)) mergesInView.add(m)
        }

        canvas.save()
        canvas.clipRect(headW, headH, w, h)
        if (r1 >= r0) {
            // 1) warna isi sel
            for (r in r0..r1) {
                val rh = rowHpx(r); if (rh <= 0f) continue
                val y = sy(rowY(r))
                for (c in c0..c1) {
                    val cw = colWpx(c); if (cw <= 0f) continue
                    if (covered(r, c)) continue
                    val fill = sh.cell(r, c)?.style?.fill ?: 0
                    if (fill != 0) { fillP.color = fill; val x = sx(colX(c)); canvas.drawRect(x, y, x + cw, y + rh, fillP) }
                }
            }
            // 2) garis kisi
            if (src.gridlines()) {
                lineP.color = C.LINE
                val bottom = min(h, sy(rowY(r1 + 1)))
                val right = min(w, sx(colX(c1 + 1)))
                for (c in c0..c1 + 1) { val x = sx(colX(c)); canvas.drawLine(x, headH, x, bottom, lineP) }
                for (r in r0..r1 + 1) { val y = sy(rowY(r)); canvas.drawLine(headW, y, right, y, lineP) }
            }
            // sel gabungan: satu kotak utuh (menutup garis kisi di dalamnya)
            for (m in mergesInView) {
                mergeRect(m)
                val fill = sh.cell(m.top, m.left)?.style?.fill ?: 0
                fillP.color = if (fill != 0) fill else C.SURFACE
                canvas.drawRect(rf.left + 1, rf.top + 1, rf.right - 1, rf.bottom - 1, fillP)
                if (src.gridlines()) { lineP.color = C.LINE; canvas.drawRect(rf, lineP) }
            }
            // 3) teks
            for (r in r0..r1) {
                val rh = rowHpx(r); if (rh <= 0f) continue
                val y = sy(rowY(r))
                for (c in c0..c1) {
                    val cw = colWpx(c); if (cw <= 0f) continue
                    if (covered(r, c)) continue
                    drawText(canvas, r, c, sx(colX(c)), y, cw, rh, c1)
                }
            }
            for (m in mergesInView) {
                mergeRect(m)
                val x = rf.left; val y = rf.top; val ww = rf.width(); val hh = rf.height()
                drawText(canvas, m.top, m.left, x, y, ww, hh, m.left)
            }
            // 4) seleksi & sel aktif
            drawSelection(canvas)
        }
        canvas.restore()

        drawHeaders(canvas, r0, r1, c0, c1, w, h)
    }

    private fun sx(x: Double) = (headW + x - offX).toFloat()
    private fun sy(y: Double) = (headH + y - offY).toFloat()

    private fun mergeRect(m: CellRange) {
        rf.set(sx(colX(m.left)), sy(rowY(m.top)), sx(colX(m.right + 1)), sy(rowY(m.bottom + 1)))
    }

    /** Sel tertutup sel gabungan yang tampak (termasuk sel kiri-atasnya — digambar terpisah). */
    private fun covered(r: Int, c: Int): Boolean {
        for (m in mergesInView) if (m.contains(r, c)) return true
        return false
    }

    private fun autoColor(fill: Int): Int {
        if (fill == 0) return C.TEXT
        return if (Color.luminance(fill) > 0.45f) 0xFF1A1A1A.toInt() else Color.WHITE
    }

    private fun chroma(c: Int): Int {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    private fun applyStyle(st: CellStyle) {
        cellP.typeface = if (st.bold) headTf else bodyTf
        cellP.textSkewX = if (st.italic) -0.22f else 0f
        cellP.isUnderlineText = st.underline
        cellP.isStrikeThruText = st.strike
        cellP.textSize = sp((if (st.size > 0f) st.size else CellStyle.BASE_SIZE) * SP_PER_PT)
        var color = if (st.color != 0) st.color else autoColor(st.fill)
        // tema gelap tanpa warna isi: teks gelap eksplisit tetap terbaca (seperti Google Sheets mode gelap)
        // (hanya hitam/abu-abu gelap — warna seperti merah tua tetap warnanya)
        if (st.color != 0 && st.fill == 0 && C.dark && Color.luminance(color) < 0.2f && chroma(color) < 48) color = C.TEXT
        cellP.color = color
    }

    private fun drawText(canvas: Canvas, r: Int, c: Int, x: Float, y: Float, cw: Float, rh: Float, lastVisibleCol: Int) {
        val text = src.text(r, c)
        if (text.isEmpty()) return
        val st = sheet.cell(r, c)?.style ?: CellStyle.DEFAULT
        applyStyle(st)
        val align = src.align(r, c)
        val inner = cw - textPad * 2
        if (inner <= 0f) return
        canvas.save()
        if (st.wrap) {
            canvas.clipRect(x, y, x + cw, y + rh)
            val la = when (align) { HAlign.CENTER -> Layout.Alignment.ALIGN_CENTER; HAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
            val sl = StaticLayout.Builder.obtain(text, 0, text.length, cellP, inner.toInt().coerceAtLeast(1)).setAlignment(la).build()
            canvas.translate(x + textPad, y + max(textPad / 2, (rh - sl.height) / 2f))
            sl.draw(canvas)
            canvas.restore()
            return
        }
        var shown: String = text
        val tw = cellP.measureText(shown)
        var left = x; var right = x + cw
        if (tw > inner) {
            if (src.numeric(r, c)) {
                shown = "###"
            } else if (align == HAlign.LEFT) {
                // teks meluber ke sel kosong di kanan (berhenti di sel berisi / sel gabungan)
                var cc = c + 1
                while (right - x - textPad * 2 < tw && cc < sheet.cols && cc <= lastVisibleCol + 1) {
                    if (src.text(r, cc).isNotEmpty() || covered(r, cc)) break
                    right += colWpx(cc); cc++
                }
            } else if (align == HAlign.RIGHT) {
                var cc = c - 1
                while (right - left - textPad * 2 < tw && cc >= 0) {
                    if (src.text(r, cc).isNotEmpty() || covered(r, cc)) break
                    left -= colWpx(cc); cc--
                }
            }
        }
        canvas.clipRect(left, y, right, y + rh)
        val w2 = cellP.measureText(shown)
        val tx = when (align) {
            HAlign.RIGHT -> right - textPad - w2
            HAlign.CENTER -> x + (cw - w2) / 2f
            else -> left + textPad
        }
        canvas.drawText(shown, tx, y + rh / 2f - (cellP.descent() + cellP.ascent()) / 2f, cellP)
        canvas.restore()
    }

    // ---------- seleksi beranimasi ----------
    // Kotak seleksi & sel aktif tidak melompat: tepinya mengalir ke posisi baru (peluruhan eksponensial per frame,
    // jadi saat jari menyeret seleksi pun tetap menempel tanpa jeda). Disimpan dalam koordinat isi (tanpa geseran),
    // sehingga menggulir tidak ikut dianimasikan.

    /** Tampil sekarang: [0..3] = seleksi (kiri, atas, kanan, bawah), [4..7] = sel aktif. */
    private val shown = DoubleArray(8)
    private val goal = DoubleArray(8)
    private var shownInit = false
    private var lastFrame = 0L
    /** Lewati animasi pada gambar berikutnya (ukuran kolom/baris berubah, ganti sheet). */
    private var snapSel = true

    private fun settle(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(1L, 48L)
        lastFrame = now
        var far = 0.0
        for (i in 0 until 8) far = max(far, abs(goal[i] - shown[i]))
        val snap = !shownInit || snapSel || !Motion.on() || mode == Mode.RESIZE_COL || mode == Mode.RESIZE_ROW ||
            far > 4.0 * max(width, height)
        shownInit = true; snapSel = false
        if (snap) { System.arraycopy(goal, 0, shown, 0, 8); return false }
        val k = 1.0 - kotlin.math.exp(-dt / 55.0)
        var moving = false
        for (i in 0 until 8) {
            val d = goal[i] - shown[i]
            if (abs(d) < 0.5) shown[i] = goal[i] else { shown[i] += d * k; moving = true }
        }
        return moving
    }

    private fun roundBox(canvas: Canvas, r: RectF, radius: Float, p: Paint) {
        val rr = min(radius, min(r.width(), r.height()) / 2f).coerceAtLeast(0f)
        canvas.drawRoundRect(r, rr, rr, p)
    }

    private fun drawSelection(canvas: Canvas) {
        val sel = src.selection()
        val ar = src.activeRow(); val ac = src.activeCol()
        val rows = sheet.rows.size; val cols = sheet.cols
        if (rows == 0 || cols == 0) return
        val top = sel.top.coerceIn(0, rows - 1); val bottom = sel.bottom.coerceIn(0, rows - 1)
        val left = sel.left.coerceIn(0, cols - 1); val right = sel.right.coerceIn(0, cols - 1)
        val am = sheet.mergeAt(ar, ac)
        goal[0] = colX(left); goal[1] = rowY(top); goal[2] = colX(right + 1); goal[3] = rowY(bottom + 1)
        if (am != null) { goal[4] = colX(am.left); goal[5] = rowY(am.top); goal[6] = colX(am.right + 1); goal[7] = rowY(am.bottom + 1) }
        else { goal[4] = colX(ac); goal[5] = rowY(ar); goal[6] = colX(ac + 1); goal[7] = rowY(ar + 1) }
        if (settle()) postInvalidateOnAnimation()

        val one = sheet.mergeAt(top, left)
        val multi = !(top == bottom && left == right) &&
            !(one != null && one.top == top && one.left == left && one.bottom == bottom && one.right == right)
        val rad = px(6f)
        selRect.set(sx(shown[0]), sy(shown[1]), sx(shown[2]), sy(shown[3]))
        if (multi) {
            fillP.color = (C.TEAL and 0x00FFFFFF) or 0x26000000
            fillP.isAntiAlias = true
            roundBox(canvas, selRect, rad, fillP)
            // sel aktif di dalam seleksi tetap polos (warna isinya sendiri) — di posisi aslinya, bukan yang mengalir
            val tr = am?.top ?: ar; val tc = am?.left ?: ac
            rf.set(sx(goal[4]), sy(goal[5]), sx(goal[6]), sy(goal[7]))
            val fill = sheet.cell(tr, tc)?.style?.fill ?: 0
            fillP.color = if (fill != 0) fill else C.SURFACE
            rf.inset(1f, 1f)
            roundBox(canvas, rf, px(4f), fillP)
            rf.inset(-1f, -1f)
            drawText(canvas, tr, tc, rf.left, rf.top, rf.width(), rf.height(), tc)
            fillP.isAntiAlias = false
        }
        rf.set(sx(shown[4]), sy(shown[5]), sx(shown[6]), sy(shown[7]))
        if (pulse != 1f && !multi) {
            val cx = rf.centerX(); val cy = rf.centerY(); val hw = rf.width() / 2f * pulse; val hh = rf.height() / 2f * pulse
            rf.set(cx - hw, cy - hh, cx + hw, cy + hh)
        }
        val box = if (multi) selRect else rf
        selP.color = C.TEAL
        selP.strokeWidth = px(2f)
        roundBox(canvas, box, rad, selP)
        if (multi) {
            selP.strokeWidth = px(1.25f)
            rf.inset(px(1f), px(1f))
            roundBox(canvas, rf, px(4f), selP)
        }
        // bulatan penarik di sudut kiri-atas & kanan-bawah seleksi
        dotP.color = C.TEAL
        canvas.drawCircle(box.left, box.top, handleR, dotP)
        canvas.drawCircle(box.right, box.bottom, handleR, dotP)
        dotP.color = C.SURFACE
        canvas.drawCircle(box.left, box.top, handleR * 0.45f, dotP)
        canvas.drawCircle(box.right, box.bottom, handleR * 0.45f, dotP)
        handleTL.set(box.left, box.top); handleBR.set(box.right, box.bottom)
    }

    private val handleTL = PointF(-9999f, -9999f)
    private val handleBR = PointF(-9999f, -9999f)

    private fun drawHeaders(canvas: Canvas, r0: Int, r1: Int, c0: Int, c1: Int, w: Float, h: Float) {
        val sel = src.selection()
        val rows = sheet.rows.size
        val fullCols = rows > 0 && sel.top <= 0 && sel.bottom >= rows - 1
        val fullRows = sel.left <= 0 && sel.right >= sheet.cols - 1
        // kolom nomor baris
        fillP.color = C.SURFACE2
        canvas.drawRect(0f, headH, headW, h, fillP)
        canvas.save()
        canvas.clipRect(0f, headH, headW, h)
        lineP.color = C.LINE
        // penanda seleksi di judul = satu pil membulat yang ikut mengalir bersama kotak seleksi
        if (r1 >= r0 && shownInit) {
            fillP.color = if (fullRows) C.TEAL else C.TEAL_SOFT; fillP.isAntiAlias = true
            rf.set(px(4f), sy(shown[1]) + px(2f), headW - px(4f), sy(shown[3]) - px(2f))
            if (rf.height() > 0f) roundBox(canvas, rf, px(8f), fillP)
            fillP.isAntiAlias = false
        }
        if (r1 >= r0) for (r in r0..r1) {
            val rh = rowHpx(r); if (rh <= 0f) continue
            val y = sy(rowY(r))
            val inSel = r in sel.top..sel.bottom
            headP.color = if (inSel) (if (fullRows) C.ON_TEAL else C.TEAL) else C.MUTED
            canvas.drawText((r + 1).toString(), headW / 2f, y + rh / 2f - (headP.descent() + headP.ascent()) / 2f, headP)
        }
        canvas.restore()
        // baris judul kolom
        fillP.color = C.SURFACE2
        canvas.drawRect(0f, 0f, w, headH, fillP)
        canvas.save()
        canvas.clipRect(headW, 0f, w, headH)
        if (shownInit) {
            fillP.color = if (fullCols) C.TEAL else C.TEAL_SOFT; fillP.isAntiAlias = true
            rf.set(sx(shown[0]) + px(2f), px(4f), sx(shown[2]) - px(2f), headH - px(4f))
            if (rf.width() > 0f) roundBox(canvas, rf, px(8f), fillP)
            fillP.isAntiAlias = false
        }
        for (c in c0..c1) {
            val cw = colWpx(c); if (cw <= 0f) continue
            val x = sx(colX(c))
            val inSel = c in sel.left..sel.right
            headP.color = if (inSel) (if (fullCols) C.ON_TEAL else C.TEAL) else C.MUTED
            canvas.drawText(CellRange.colName(c), x + cw / 2f, headH / 2f - (headP.descent() + headP.ascent()) / 2f, headP)
            // batas kolom: garis pendek membulat di tengah (tetap jelas tempat menarik lebar kolom)
            lineP.color = C.LINE
            canvas.drawLine(x + cw, headH * 0.3f, x + cw, headH * 0.7f, lineP)
        }
        canvas.restore()
        // sudut kiri-atas: pilih semua
        fillP.color = C.SURFACE2
        canvas.drawRect(0f, 0f, headW, headH, fillP)
        dotP.color = C.MUTED
        dotP.pathEffect = cornerFx
        corner.reset()
        corner.moveTo(headW - px(6f), headH - px(16f)); corner.lineTo(headW - px(6f), headH - px(6f)); corner.lineTo(headW - px(16f), headH - px(6f)); corner.close()
        canvas.drawPath(corner, dotP)
        dotP.pathEffect = null
        canvas.drawLine(0f, headH, w, headH, lineP)
        canvas.drawLine(headW, 0f, headW, h, lineP)
    }

    // ---------- sentuhan ----------

    private enum class Mode { NONE, CELLS, SELECT, COL_HEAD, ROW_HEAD, RESIZE_COL, RESIZE_ROW, CORNER }
    private var mode = Mode.NONE
    private var axis = 0
    private var stopTap = false
    private var downX = 0f; private var downY = 0f
    private var anchorR = 0; private var anchorC = 0
    private var lastR = -1; private var lastC = -1
    private var resizeIdx = -1; private var resizeFrom = 0f; private var resizeStart = 0f
    private var lastResizeTap = 0L; private var lastResizeIdx = -1; private var lastResizeCol = false
    private var lastX = 0f; private var lastY = 0f
    private var selectMenu = false
    private var headMoved = false

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // ketukan yang menghentikan guliran yang masih meluncur hanya berhenti, tidak ikut memilih sel
            stopTap = !scroller.isFinished
            scroller.forceFinished(true); axis = 0
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (mode != Mode.CELLS) return false
            if (axis == 0) axis = when { abs(dx) > abs(dy) * 2f -> 1; abs(dy) > abs(dx) * 2f -> 2; else -> 3 }
            parent?.requestDisallowInterceptTouchEvent(true)
            if (axis != 2) offX += dx
            if (axis != 1) offY += dy
            clamp(); invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (mode != Mode.CELLS) return false
            scroller.fling(
                offX.toInt(), offY.toInt(), if (axis == 2) 0 else -vx.toInt(), if (axis == 1) 0 else -vy.toInt(),
                0, maxX().coerceAtMost(Int.MAX_VALUE.toDouble()).toInt(), 0, maxY().coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode != Mode.CELLS || stopTap) return true
            val hit = cellAt(e.x, e.y) ?: return false
            playSoundEffect(SoundEffectConstants.CLICK)
            src.select(KIND_CELLS, hit.first, hit.second, hit.first, hit.second, PHASE_END, false)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (mode != Mode.CELLS) return false
            val hit = cellAt(e.x, e.y) ?: return false
            src.doubleTap(hit.first, hit.second)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode != Mode.CELLS || axis != 0) return
            val hit = cellAt(e.x, e.y) ?: return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            mode = Mode.SELECT; selectMenu = true
            anchorR = hit.first; anchorC = hit.second; lastR = hit.first; lastC = hit.second
            lastX = e.x; lastY = e.y
            parent?.requestDisallowInterceptTouchEvent(true)
            src.select(KIND_CELLS, anchorR, anchorC, lastR, lastC, PHASE_START, true)
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
            headMoved = false; selectMenu = false
            mode = classifyDown(e.x, e.y)
            if (mode != Mode.CELLS) {
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                if (mode == Mode.SELECT) src.select(KIND_CELLS, anchorR, anchorC, lastR, lastC, PHASE_START, true)
                else startHeaderGesture(e)
                if (mode != Mode.SELECT) headerMove(e)
                return true
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE) { lastX = e.x; lastY = e.y }
        return when (mode) {
            Mode.CELLS -> {
                val handled = gestures.onTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) endGesture()
                handled || super.onTouchEvent(e)
            }
            Mode.SELECT -> {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> { dragSelect(e.x, e.y); startAutoScroll() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        src.select(KIND_CELLS, anchorR, anchorC, lastR, lastC, PHASE_END, selectMenu)
                        endGesture()
                    }
                }
                true
            }
            Mode.NONE -> super.onTouchEvent(e)
            else -> { headerMove(e); true }
        }
    }

    private fun endGesture() {
        mode = Mode.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
        a11y.invalidateRoot()
    }

    private fun classifyDown(x: Float, y: Float): Mode {
        if (y > headH && x > headW) {
            // bulatan penarik seleksi
            val sel = src.selection()
            val grab = handleR * 3.2f
            if (abs(x - handleBR.x) < grab && abs(y - handleBR.y) < grab) {
                anchorR = sel.top; anchorC = sel.left; lastR = sel.bottom; lastC = sel.right
                selectMenu = true; return Mode.SELECT
            }
            if (abs(x - handleTL.x) < grab && abs(y - handleTL.y) < grab) {
                anchorR = sel.bottom; anchorC = sel.right; lastR = sel.top; lastC = sel.left
                selectMenu = true; return Mode.SELECT
            }
            return Mode.CELLS
        }
        if (x <= headW && y <= headH) return Mode.CORNER
        if (y <= headH) {
            // dekat tepi kolom → ubah lebar
            val c = colAt(x - headW + offX)
            val left = sx(colX(c)); val right = left + colWpx(c)
            val idx = when {
                abs(x - right) < edgeGrab -> c
                abs(x - left) < edgeGrab && c > 0 -> prevVisible(c, true)
                else -> -1
            }
            if (idx in 0 until sheet.cols) { resizeIdx = idx; return Mode.RESIZE_COL }
            return Mode.COL_HEAD
        }
        val r = rowAt(y - headH + offY)
        val top = sy(rowY(r)); val bottom = top + rowHpx(r)
        val idx = when {
            abs(y - bottom) < edgeGrab -> r
            abs(y - top) < edgeGrab && r > 0 -> prevVisible(r, false)
            else -> -1
        }
        if (idx in 0 until sheet.rows.size) { resizeIdx = idx; return Mode.RESIZE_ROW }
        return Mode.ROW_HEAD
    }

    private fun prevVisible(i: Int, col: Boolean): Int {
        var k = i - 1
        while (k > 0 && (if (col) sheet.colW.size(k) else sheet.rowH.size(k)) <= 0f) k--
        return k
    }

    private fun startHeaderGesture(e: MotionEvent) {
        when (mode) {
            Mode.RESIZE_COL, Mode.RESIZE_ROW -> {
                val col = mode == Mode.RESIZE_COL
                resizeFrom = if (col) sheet.colW.size(resizeIdx) else sheet.rowH.size(resizeIdx)
                resizeStart = if (col) e.x else e.y
            }
            Mode.COL_HEAD -> { val c = colAt(e.x - headW + offX).coerceIn(0, (sheet.cols - 1).coerceAtLeast(0)); anchorC = c; lastC = -1 }
            Mode.ROW_HEAD -> { val r = rowAt(e.y - headH + offY).coerceIn(0, (sheet.rows.size - 1).coerceAtLeast(0)); anchorR = r; lastR = -1 }
            else -> {}
        }
    }

    private fun headerMove(e: MotionEvent) {
        val act = e.actionMasked
        if (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop) headMoved = true
        val end = act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL
        when (mode) {
            Mode.RESIZE_COL, Mode.RESIZE_ROW -> {
                val col = mode == Mode.RESIZE_COL
                if (headMoved) {
                    val delta = ((if (col) e.x else e.y) - resizeStart) / density
                    val size = (resizeFrom + delta).coerceIn(if (col) 16f else 18f, if (col) 1200f else 600f)
                    if (col) sheet.colW.set(resizeIdx, size) else sheet.rowH.set(resizeIdx, size)
                    structureChanged()
                    src.resized(col, resizeIdx, resizeFrom, size, end)
                }
                if (end) {
                    if (!headMoved) {
                        // ketuk dua kali tepi judul = sesuaikan otomatis dengan isi
                        val now = SystemClock.uptimeMillis()
                        if (now - lastResizeTap < 400 && lastResizeIdx == resizeIdx && lastResizeCol == col) {
                            val fit = if (col) fitColumn(resizeIdx) else fitRow(resizeIdx)
                            if (col) sheet.colW.set(resizeIdx, fit) else sheet.rowH.set(resizeIdx, fit)
                            structureChanged()
                            src.resized(col, resizeIdx, resizeFrom, fit, true)
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            lastResizeTap = 0
                        } else { lastResizeTap = now; lastResizeIdx = resizeIdx; lastResizeCol = col }
                    }
                    endGesture()
                }
            }
            Mode.COL_HEAD -> {
                if (sheet.cols <= 0 || sheet.rows.isEmpty()) { if (end) endGesture(); return }
                val c = colAt(e.x.coerceAtLeast(headW) - headW + offX).coerceIn(0, sheet.cols - 1)
                val phase = if (end) PHASE_END else if (act == MotionEvent.ACTION_DOWN) PHASE_START else PHASE_MOVE
                if (c != lastC || end) { lastC = c; src.select(KIND_COLS, 0, anchorC, sheet.rows.size - 1, c, phase, false) }
                if (!end) edgeScrollX(e.x) else endGesture()
            }
            Mode.ROW_HEAD -> {
                if (sheet.rows.isEmpty()) { if (end) endGesture(); return }
                val r = rowAt(e.y.coerceAtLeast(headH) - headH + offY).coerceIn(0, sheet.rows.size - 1)
                val phase = if (end) PHASE_END else if (act == MotionEvent.ACTION_DOWN) PHASE_START else PHASE_MOVE
                if (r != lastR || end) { lastR = r; src.select(KIND_ROWS, anchorR, 0, r, sheet.cols - 1, phase, false) }
                if (!end) edgeScrollY(e.y) else endGesture()
            }
            Mode.CORNER -> if (end) {
                if (!headMoved && sheet.rows.isNotEmpty()) src.select(KIND_ALL, 0, 0, sheet.rows.size - 1, sheet.cols - 1, PHASE_END, false)
                endGesture()
            }
            else -> if (end) endGesture()
        }
    }

    private fun edgeScrollX(x: Float) {
        val zone = px(36f)
        val d = when { x > width - zone -> px(14f); x < headW + zone && offX > 0 -> -px(14f); else -> 0f }
        if (d != 0f) { offX += d; clamp(); invalidate() }
    }

    private fun edgeScrollY(y: Float) {
        val zone = px(36f)
        val d = when { y > height - zone -> px(14f); y < headH + zone && offY > 0 -> -px(14f); else -> 0f }
        if (d != 0f) { offY += d; clamp(); invalidate() }
    }

    private fun dragSelect(x: Float, y: Float) {
        val rows = sheet.rows.size; val cols = sheet.cols
        if (rows == 0 || cols == 0) return
        val r = rowAt(y.coerceAtLeast(headH) - headH + offY).coerceIn(0, rows - 1)
        val c = colAt(x.coerceAtLeast(headW) - headW + offX).coerceIn(0, cols - 1)
        if (r != lastR || c != lastC) {
            lastR = r; lastC = c
            src.select(KIND_CELLS, anchorR, anchorC, r, c, PHASE_MOVE, selectMenu)
        }
    }

    /** Selama menyeleksi, jari di dekat tepi = layar ikut bergulir. */
    private val autoScroll = object : Runnable {
        override fun run() {
            if (mode != Mode.SELECT) { scrolling = false; return }
            val zone = px(40f); val step = px(16f)
            var dx = 0f; var dy = 0f
            if (lastX > width - zone) dx = step else if (lastX < headW + zone && offX > 0) dx = -step
            if (lastY > height - zone) dy = step else if (lastY < headH + zone && offY > 0) dy = -step
            if (dx != 0f || dy != 0f) {
                offX += dx; offY += dy; clamp()
                dragSelect(lastX, lastY)
                invalidate()
                postOnAnimation(this)
            } else scrolling = false
        }
    }
    private var scrolling = false
    private fun startAutoScroll() { if (!scrolling) { scrolling = true; postOnAnimation(autoScroll) } }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.currX.toDouble(); offY = scroller.currY.toDouble()
            clamp()
            postInvalidateOnAnimation()
            if (scroller.isFinished) a11y.invalidateRoot()
        }
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (x < headW || y < headH) return null
        val c = colAt(x - headW + offX)
        val r = rowAt(y - headH + offY)
        if (r !in 0 until sheet.rows.size || c !in 0 until sheet.cols) return null
        val m = sheet.mergeAt(r, c)
        return if (m != null) m.top to m.left else r to c
    }

    // ---------- ukur isi (sesuaikan otomatis) ----------

    /** Lebar kolom yang pas dengan isi terpanjang (dp). Lembar besar: 5.000 baris pertama + yang tampak. */
    fun fitColumn(c: Int): Float {
        var widest = 0f
        val n = sheet.rows.size
        val visTop = rowAt(offY); val limit = 5000
        fun measure(r: Int) {
            val m = sheet.mergeAt(r, c); if (m != null) return
            val t = src.text(r, c); if (t.isEmpty()) return
            val st = sheet.cell(r, c)?.style ?: CellStyle.DEFAULT
            if (st.wrap) return
            applyStyle(st)
            widest = max(widest, cellP.measureText(t))
        }
        for (r in 0 until min(n, limit)) measure(r)
        for (r in max(visTop, limit) until min(n, visTop + 60)) measure(r)
        return if (widest == 0f) SheetData.DEFAULT_COL_W else ((widest + textPad * 2 + px(6f)) / density).coerceIn(24f, 1200f)
    }

    /** Tinggi baris yang pas: teks dibungkus / huruf besar (dp). */
    fun fitRow(r: Int): Float {
        var tallest = 0f
        for (c in 0 until sheet.cols) {
            val t = src.text(r, c); if (t.isEmpty()) continue
            val st = sheet.cell(r, c)?.style ?: CellStyle.DEFAULT
            applyStyle(st)
            val h = if (st.wrap) {
                val inner = (colWpx(c) - textPad * 2).toInt().coerceAtLeast(1)
                StaticLayout.Builder.obtain(t, 0, t.length, cellP, inner).build().height.toFloat()
            } else cellP.fontMetrics.let { it.descent - it.ascent }
            tallest = max(tallest, h)
        }
        return if (tallest == 0f) SheetData.DEFAULT_ROW_H else max(24f, (tallest + px(14f)) / density)
    }

    // ---------- gulir terprogram ----------

    /** Gulir seperlunya supaya sel (r, c) tampak utuh. */
    fun ensureVisible(r: Int, c: Int, animate: Boolean = true) {
        if (width == 0 || height == 0) { post { ensureVisible(r, c, false) }; return }
        metrics()
        val viewW = width - headW; val viewH = height - headH
        val left = colX(c); val top = rowY(r)
        val cw = colWpx(c); val rh = rowHpx(r)
        var nx = offX; var ny = offY
        if (left < nx) nx = left else if (left + cw > nx + viewW) nx = left + cw - viewW
        if (top < ny) ny = top else if (top + rh > ny + viewH) ny = top + rh - viewH
        nx = nx.coerceIn(0.0, maxX()); ny = ny.coerceIn(0.0, maxY())
        if (nx == offX && ny == offY) return
        scroller.forceFinished(true)
        if (animate && Motion.on() && abs(nx - offX) + abs(ny - offY) < height * 4.0) {
            scroller.startScroll(offX.toInt(), offY.toInt(), (nx - offX).toInt(), (ny - offY).toInt(), 260)
            postInvalidateOnAnimation()
        } else {
            offX = nx; offY = ny; invalidate()
            a11y.invalidateRoot()
        }
    }

    /** Posisi (dalam view) sudut kanan-bawah rentang — untuk menu aksi. */
    fun rangeCorner(r: Int, c: Int): Pair<Float, Float> =
        sx(colX(c + 1)).coerceIn(headW, width.toFloat()) to sy(rowY(r + 1)).coerceIn(headH, height.toFloat())

    /** Sel aktif "mendarat" memantul kecil. */
    fun pulseActive(from: Float = 0.92f) {
        pulseAnim?.cancel()
        if (!Motion.on()) { pulse = 1f; invalidate(); return }
        pulseAnim = ValueAnimator.ofFloat(from, 1f).apply {
            duration = Motion.SPRING_MS
            interpolator = Motion.SPRING
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnim?.cancel(); scroller.forceFinished(true); removeCallbacks(autoScroll)
        super.onDetachedFromWindow()
    }

    // ---------- aksesibilitas: sel tampak = simpul virtual ----------

    private var winR0 = 0
    private var winC0 = 0

    private fun visibleWindow(): IntArray? {
        val rows = sheet.rows.size; val cols = sheet.cols
        if (rows <= 0 || cols <= 0 || width == 0 || height == 0) return null
        metrics()
        return intArrayOf(
            rowAt(offY).coerceIn(0, rows - 1), rowAt(offY + height - headH).coerceIn(0, rows - 1),
            colAt(offX).coerceIn(0, cols - 1), colAt(offX + width - headW).coerceIn(0, cols - 1)
        )
    }

    private val a11y = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val hit = cellAt(x, y) ?: return INVALID_ID
            val win = visibleWindow() ?: return INVALID_ID
            winR0 = win[0]; winC0 = win[2]
            val dr = hit.first - winR0; val dc = hit.second - winC0
            if (dr < 0 || dc < 0 || dc >= STRIDE) return INVALID_ID
            return dr * STRIDE + dc
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            val win = visibleWindow() ?: return
            winR0 = win[0]; winC0 = win[2]
            for (r in win[0]..win[1]) for (c in win[2]..min(win[3], win[2] + STRIDE - 1)) ids.add((r - winR0) * STRIDE + (c - winC0))
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val r = winR0 + id / STRIDE; val c = winC0 + id % STRIDE
            if (r !in 0 until sheet.rows.size || c !in 0 until sheet.cols) {
                node.contentDescription = ""
                @Suppress("DEPRECATION") node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = "${CellRange.a1(r, c)}: ${src.text(r, c).ifBlank { "kosong" }}"
            node.isSelected = src.selection().contains(r, c)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.isClickable = true
            val x = sx(colX(c)).toInt(); val y = sy(rowY(r)).toInt()
            val b = Rect(x, y, x + colWpx(c).toInt().coerceAtLeast(1), y + rowHpx(r).toInt().coerceAtLeast(1))
            if (!b.intersect(headW.toInt(), headH.toInt(), width, height)) b.set(0, 0, 1, 1)
            @Suppress("DEPRECATION") node.setBoundsInParent(b)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val r = winR0 + id / STRIDE; val c = winC0 + id % STRIDE
            if (r !in 0 until sheet.rows.size || c !in 0 until sheet.cols) return false
            src.select(KIND_CELLS, r, c, r, c, PHASE_END, false)
            invalidateVirtualView(id)
            return true
        }
    }

    init { ViewCompat.setAccessibilityDelegate(this, a11y) }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
}
