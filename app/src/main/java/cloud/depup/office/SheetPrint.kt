package cloud.depup.office

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
 * Lembar kerja → PDF rapi untuk dicetak / "PDF siap cetak" ekspor: tabel bergaris, angka rata kanan, baris tebal
 * berlatar abu-abu muda, judul di halaman pertama, nomor halaman di kaki. Tata letak dari [SheetLayout].
 */
object SheetPrint {
    private const val INK = 0xFF1A1A1A.toInt()
    private const val MUTED = 0xFF6B6B6B.toInt()
    private const val GRID = 0xFFBDBDBD.toInt()

    /** Warna latar sel di layar (ikut tema, bisa gelap) → warna cetak yang selalu muda. 0 = tanpa latar. */
    fun printFill(bg: Int): Int {
        if (bg == 0 || Color.alpha(bg) == 0) return 0
        val r = Color.red(bg); val g = Color.green(bg); val b = Color.blue(bg)
        return if (b > r + 24 && b > g + 8) 0xFFE6EDFF.toInt() else 0xFFEDEDED.toInt()
    }

    fun pdf(title: String, grids: List<SheetLayout.Grid>, out: File, gridlines: Boolean = true) {
        val regular = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = SheetLayout.FONT; typeface = Typeface.DEFAULT; color = INK }
        val bold = TextPaint(regular).apply { typeface = Typeface.DEFAULT_BOLD }
        val plan = SheetLayout.plan(grids) { t, b -> (if (b) bold else regular).measureText(t) }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; color = INK }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; typeface = Typeface.DEFAULT; color = MUTED }
        val footPaint = TextPaint(subPaint).apply { textSize = 8f; textAlign = Paint.Align.CENTER }
        val gridPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = GRID }
        val fillPaint = Paint().apply { style = Paint.Style.FILL }
        val fm = regular.fontMetrics
        // garis dasar teks di tengah kotak satu baris
        val baseOff = (SheetLayout.LINE - (fm.descent - fm.ascent)) / 2f - fm.ascent
        val date = SimpleDateFormat("d MMM yyyy", Locale("id", "ID")).format(Date())
        val total = plan.pages.size
        val doc = PdfDocument()
        try {
            plan.pages.forEachIndexed { pi, pg ->
                val pw = if (pg.landscape) SheetLayout.A4_H else SheetLayout.A4_W
                val ph = if (pg.landscape) SheetLayout.A4_W else SheetLayout.A4_H
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw.roundToInt(), ph.roundToInt(), pi + 1).create())
                val c = page.canvas
                val grid = grids[pg.grid]
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
                for (r in pg.rows) {
                    val h = rowH[r]
                    var x = 0f
                    for (col in pg.cols) {
                        val w = colW[col]
                        val cell = grid.rows[r].getOrNull(col)
                        val fill = cell?.fill ?: 0
                        if (fill != 0) { fillPaint.color = fill; c.drawRect(x, y, x + w, y + h, fillPaint) }
                        if (gridlines) c.drawRect(x, y, x + w, y + h, gridPaint)
                        val ls = lines[r][col]
                        if (cell != null && ls.isNotEmpty()) {
                            val p = if (cell.bold) bold else regular
                            ls.forEachIndexed { li, t ->
                                val by = y + SheetLayout.PAD_Y + li * SheetLayout.LINE + baseOff
                                val tx = if (cell.num && ls.size == 1) x + w - SheetLayout.PAD_X - p.measureText(t) else x + SheetLayout.PAD_X
                                c.drawText(t, tx, by, p)
                            }
                        }
                        x += w
                    }
                    // tanpa garis kisi: judul kolom tetap dipisah garis tipis
                    if (!gridlines && r == header) c.drawLine(0f, y + h, x, y + h, gridPaint)
                    y += h
                }
                c.restore()
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
