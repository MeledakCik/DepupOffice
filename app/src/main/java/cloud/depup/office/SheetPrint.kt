package cloud.depup.office

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lembar kerja → halaman A4 rapi (PDF siap cetak, dan pratinjau "Mode Dokumen" di editor — keduanya memakai
 * [drawPage] yang sama, jadi yang tampak = yang dicetak): tabel bergaris, warna isi & teks, tebal/miring/garis
 * bawah/coret, perataan, sel gabungan, judul di halaman pertama, nomor halaman di kaki. Tata letak dari [SheetLayout].
 */
object SheetPrint {
    private const val INK = 0xFF1A1A1A.toInt()
    private const val MUTED = 0xFF6B6B6B.toInt()
    private const val GRID = 0xFFBDBDBD.toInt()

    /** Warna latar sel lama (ikut tema, bisa gelap) → warna cetak yang selalu muda. 0 = tanpa latar. */
    fun printFill(bg: Int): Int {
        if (bg == 0 || Color.alpha(bg) == 0) return 0
        val r = Color.red(bg); val g = Color.green(bg); val b = Color.blue(bg)
        return if (b > r + 24 && b > g + 8) 0xFFE6EDFF.toInt() else 0xFFEDEDED.toInt()
    }

    /** Alat gambar untuk satu dokumen (huruf & tata letak dihitung sekali). */
    class Renderer(val title: String, val grids: List<SheetLayout.Grid>, val gridlines: Boolean = true) {
        private val regular = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = SheetLayout.FONT; typeface = Typeface.DEFAULT; color = INK }
        private val bold = TextPaint(regular).apply { typeface = Typeface.DEFAULT_BOLD }
        val plan: SheetLayout.Plan = SheetLayout.plan(grids) { t, b -> (if (b) bold else regular).measureText(t) }
        private val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = SheetLayout.FONT }
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; color = INK }
        private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; typeface = Typeface.DEFAULT; color = MUTED }
        private val footPaint = TextPaint(subPaint).apply { textSize = 8f; textAlign = Paint.Align.CENTER }
        private val gridPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = GRID }
        private val fillPaint = Paint().apply { style = Paint.Style.FILL }
        private val fm = regular.fontMetrics
        private val baseOff = (SheetLayout.LINE - (fm.descent - fm.ascent)) / 2f - fm.ascent
        private val date = SimpleDateFormat("d MMM yyyy", Locale("id", "ID")).format(Date())

        val pageCount get() = plan.pages.size
        fun pageW(i: Int) = if (plan.pages[i].landscape) SheetLayout.A4_H else SheetLayout.A4_W
        fun pageH(i: Int) = if (plan.pages[i].landscape) SheetLayout.A4_W else SheetLayout.A4_H

        /** Gambar halaman [pi] ke [c] (satuan pt; kanvas sudah diskalakan pemanggil). */
        fun drawPage(c: Canvas, pi: Int) {
            val pg = plan.pages[pi]
            val pw = pageW(pi); val ph = pageH(pi)
            val total = plan.pages.size
            val grid = grids[pg.grid]
            c.drawColor(Color.WHITE)
            c.drawText(ellipsize("$title · Halaman ${pi + 1} dari $total", footPaint, pw - 2 * SheetLayout.MARGIN),
                pw / 2f, ph - SheetLayout.MARGIN / 2f, footPaint)
            c.save()
            c.translate(SheetLayout.MARGIN, SheetLayout.MARGIN)
            c.scale(pg.scale, pg.scale)
            val avail = (pw - 2 * SheetLayout.MARGIN) / pg.scale
            var y = 0f
            if (pg.title) {
                c.drawText(ellipsize(title, titlePaint, avail), 0f, 15f, titlePaint)
                val sub = listOf(if (grids.size > 1 || grid.name != title) grid.name else "", date).filter { it.isNotBlank() }.joinToString(" · ")
                c.drawText(ellipsize(sub, subPaint, avail), 0f, 28f, subPaint)
                y += SheetLayout.TITLE_H
            }
            if (pg.bands > 1 && pg.cols.isNotEmpty()) {
                val a = SheetLayout.colName(pg.cols.first()); val b = SheetLayout.colName(pg.cols.last())
                c.drawText("Kolom ${if (a == b) a else "$a–$b"} · bagian ${pg.band + 1} dari ${pg.bands}", 0f, y + 10f, subPaint)
                y += SheetLayout.BAND_H
            }
            if (pg.rows.isEmpty()) c.drawText("Lembar ini kosong.", 0f, y + 12f, subPaint)
            val colW = plan.colW[pg.grid]; val rowH = plan.rowH[pg.grid]; val lines = plan.lines[pg.grid]
            val header = plan.header[pg.grid]
            // posisi kolom & baris di halaman ini (untuk sel gabungan)
            val colX = HashMap<Int, Float>(); run { var x = 0f; for (col in pg.cols) { colX[col] = x; x += colW[col] } }
            val tableW = pg.cols.sumOf { colW[it].toDouble() }.toFloat()
            val rowY = HashMap<Int, Float>(); run { var yy = y; for (r in pg.rows) { rowY[r] = yy; yy += rowH[r] } }
            for (r in pg.rows) {
                val h = rowH[r]
                val ry = rowY[r]!!
                for (col in pg.cols) {
                    val x = colX[col]!!
                    val w = colW[col]
                    val mg = grid.mergeAt(r, col)
                    if (mg != null && (mg.top != r || mg.left != col)) {
                        // tertutup sel gabungan: bila sel kiri-atasnya tidak ada di halaman ini, isi & garis tetap digambar per sel
                        if (rowY.containsKey(mg.top) && colX.containsKey(mg.left)) continue
                    }
                    var cw = w; var chh = h
                    if (mg != null && mg.top == r && mg.left == col) {
                        cw = (mg.left..mg.right).filter { colX.containsKey(it) }.sumOf { colW[it].toDouble() }.toFloat()
                        chh = (mg.top..mg.bottom).filter { rowY.containsKey(it) }.sumOf { rowH[it].toDouble() }.toFloat()
                    }
                    val cell = grid.rows[r].getOrNull(col)
                    val fill = cell?.fill ?: 0
                    if (fill != 0) { fillPaint.color = fill; c.drawRect(x, ry, x + cw, ry + chh, fillPaint) }
                    if (gridlines) c.drawRect(x, ry, x + cw, ry + chh, gridPaint)
                    val ls = lines[r][col]
                    if (cell != null && ls.isNotEmpty()) {
                        val p = cellPaint
                        p.typeface = if (cell.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        p.textSkewX = if (cell.italic) -0.22f else 0f
                        p.isUnderlineText = cell.underline
                        p.isStrikeThruText = cell.strike
                        p.color = if (cell.color != 0) cell.color else INK
                        val textH = ls.size * SheetLayout.LINE
                        val top = ry + if (chh > textH + 2 * SheetLayout.PAD_Y) (chh - textH) / 2f else SheetLayout.PAD_Y
                        val align = when (cell.align) {
                            HAlign.LEFT, HAlign.CENTER, HAlign.RIGHT -> cell.align
                            else -> if (cell.num) HAlign.RIGHT else HAlign.LEFT
                        }
                        c.save()
                        c.clipRect(x, ry, x + cw, ry + chh)
                        ls.forEachIndexed { li, t ->
                            val by = top + li * SheetLayout.LINE + baseOff
                            val tw = p.measureText(t)
                            val tx = when (align) {
                                HAlign.RIGHT -> x + cw - SheetLayout.PAD_X - tw
                                HAlign.CENTER -> x + (cw - tw) / 2f
                                else -> x + SheetLayout.PAD_X
                            }
                            c.drawText(t, tx, by, p)
                        }
                        c.restore()
                    }
                }
                // tanpa garis kisi: judul kolom tetap dipisah garis tipis
                if (!gridlines && r == header) c.drawLine(0f, ry + h, tableW, ry + h, gridPaint)
            }
            c.restore()
        }
    }

    fun pdf(title: String, grids: List<SheetLayout.Grid>, out: File, gridlines: Boolean = true) {
        val rd = Renderer(title, grids, gridlines)
        val doc = PdfDocument()
        try {
            for (pi in 0 until rd.pageCount) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(rd.pageW(pi).roundToInt(), rd.pageH(pi).roundToInt(), pi + 1).create())
                rd.drawPage(page.canvas, pi)
                doc.finishPage(page)
            }
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    private fun ellipsize(t: String, p: Paint, w: Float): String {
        if (p.measureText(t) <= w) return t
        var s = t
        while (s.isNotEmpty() && p.measureText("$s…") > w) s = s.dropLast(1)
        return "$s…"
    }
}
