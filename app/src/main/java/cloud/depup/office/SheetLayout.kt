package cloud.depup.office

import kotlin.math.max
import kotlin.math.min

/**
 * Tata letak lembar kerja untuk dicetak / PDF — murni (tanpa Android; lebar teks diukur lewat [Measure]), diuji JVM di
 * SheetLayoutTest. Semua baris & kolom yang berisi ikut (dulu hanya 28 baris × 4 kolom pertama):
 * - lebar kolom mengikuti isi (sempit 26 pt … lebar 190 pt; teks lebih panjang dibungkus, maks. 8 baris);
 * - kertas A4 tegak bila muat; sedikit lebih lebar → tegak diperkecil; lebih lebar lagi → mendatar (diperkecil sampai
 *   75% bila perlu); masih tidak muat → kolom dibagi ke beberapa halaman (urutan: ke bawah dulu, lalu ke kanan);
 * - baris dipecah per halaman, baris judul kolom (baris pertama yang tebal) diulang di atas tiap halaman lanjutan.
 * Satuan: pt pada skala 100%; halaman yang diperkecil digambar dengan kanvas berskala [Page.scale].
 */
object SheetLayout {
    class Cell(
        val text: String, val bold: Boolean = false, val num: Boolean = false, val fill: Int = 0,
        val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false,
        /** Warna teks ARGB; 0 = hitam. */
        val color: Int = 0,
        /** [HAlign]; AUTO = angka kanan, teks kiri. */
        val align: Int = 0
    )

    /**
     * [widths] = lebar kolom dari editor (pt; 0 = disembunyikan) — null: lebar mengikuti isi. [hiddenRows] tidak
     * dicetak. [merges] = sel gabungan (teks sel kiri-atas mengisi seluruh area).
     */
    class Grid(
        val name: String, val rows: List<List<Cell>>,
        val widths: FloatArray? = null,
        val hiddenRows: Set<Int> = emptySet(),
        val merges: List<CellRange> = emptyList()
    ) {
        fun mergeAt(r: Int, c: Int): CellRange? { for (m in merges) if (m.contains(r, c)) return m; return null }
    }

    fun interface Measure { fun width(text: String, bold: Boolean): Float }

    const val FONT = 9f
    const val LINE = 11.5f
    const val PAD_X = 5f
    const val PAD_Y = 4f
    const val MIN_W = 26f
    const val MAX_W = 190f
    const val MAX_LINES = 8
    const val MARGIN = 36f
    const val TITLE_H = 36f
    const val BAND_H = 16f
    const val FOOT_H = 22f
    const val A4_W = 595f
    const val A4_H = 842f
    const val MIN_SCALE = 0.75f

    /** Satu halaman: lembar ke-[grid], kolom [cols] (indeks asli), baris [rows] (judul kolom ulangan ada di depan). */
    class Page(
        val grid: Int, val landscape: Boolean, val scale: Float, val cols: List<Int>, val rows: List<Int>,
        val title: Boolean, val band: Int, val bands: Int
    )

    /** Hasil tata letak: halaman + ukuran kolom/baris & teks terbungkus per lembar. */
    class Plan(
        val pages: List<Page>,
        val colW: List<FloatArray>,
        val rowH: List<FloatArray>,
        /** [lembar][baris][kolom] → baris-baris teks setelah dibungkus */
        val lines: List<List<List<List<String>>>>,
        val header: List<Int?>
    )

    private const val ELLIPSIS = "…"

    /** Bungkus [text] ke lebar [w]: per kata, kata yang terlalu panjang dipotong per huruf; maks. [MAX_LINES] baris. */
    fun wrap(text: String, w: Float, bold: Boolean, m: Measure): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (para in text.replace("\r", "").split('\n')) {
            if (para.isEmpty()) { out.add(""); continue }
            var line = ""
            for (word in para.split(' ')) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (m.width(cand, bold) <= w) { line = cand; continue }
                if (line.isNotEmpty()) { out.add(line); line = "" }
                var rest = word
                while (rest.isNotEmpty() && m.width(rest, bold) > w) {
                    var k = rest.length - 1
                    while (k > 1 && m.width(rest.substring(0, k), bold) > w) k--
                    out.add(rest.substring(0, k)); rest = rest.substring(k)
                }
                line = rest
            }
            out.add(line)
        }
        if (out.size <= MAX_LINES) return out
        val keep = ArrayList(out.subList(0, MAX_LINES))
        var last = keep[MAX_LINES - 1]
        while (last.isNotEmpty() && m.width(last + ELLIPSIS, bold) > w) last = last.dropLast(1)
        keep[MAX_LINES - 1] = last + ELLIPSIS
        return keep
    }

    /** Nama kolom gaya spreadsheet: 0 → A, 25 → Z, 26 → AA. */
    fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    fun plan(grids: List<Grid>, m: Measure): Plan {
        val pages = ArrayList<Page>()
        val colWs = ArrayList<FloatArray>(); val rowHs = ArrayList<FloatArray>()
        val linesAll = ArrayList<List<List<List<String>>>>(); val headers = ArrayList<Int?>()
        grids.forEachIndexed { gi, g ->
            // rentang terpakai: baris/kolom kosong di ujung tidak dicetak
            var lastRow = -1; var lastCol = -1; var firstRow = -1
            g.rows.forEachIndexed { r, row ->
                if (r in g.hiddenRows) return@forEachIndexed
                row.forEachIndexed { c, cell ->
                    if (cell.text.isNotBlank() || cell.fill != 0) {
                        if (firstRow < 0) firstRow = r
                        lastRow = r; lastCol = max(lastCol, c)
                    }
                }
            }
            for (mg in g.merges) if (mg.top <= lastRow) lastCol = max(lastCol, mg.right)
            val nCols = lastCol + 1
            val widths = g.widths
            val colW = FloatArray(max(nCols, 0)) { c ->
                if (widths != null) return@FloatArray (widths.getOrNull(c) ?: 48f).let { if (it <= 0f) 0f else it.coerceIn(MIN_W / 2, MAX_W * 3) }
                var widest = 0f
                for (r in 0..lastRow) {
                    val cell = g.rows[r].getOrNull(c) ?: continue
                    if (g.mergeAt(r, c) != null) continue
                    for (p in cell.text.split('\n')) widest = max(widest, m.width(p, cell.bold))
                }
                (widest + 2 * PAD_X).coerceIn(MIN_W, MAX_W)
            }
            val lines = (0..lastRow).map { r ->
                (0 until nCols).map { c ->
                    val cell = g.rows[r].getOrNull(c)
                    val mg = g.mergeAt(r, c)
                    when {
                        cell == null || cell.text.isBlank() || colW[c] <= 0f -> emptyList()
                        mg != null && (mg.top != r || mg.left != c) -> emptyList()
                        mg != null -> wrap(cell.text, (mg.left..minOf(mg.right, nCols - 1)).sumOf { colW[it].toDouble() }.toFloat() - 2 * PAD_X, cell.bold, m)
                        else -> wrap(cell.text, colW[c] - 2 * PAD_X, cell.bold, m)
                    }
                }
            }
            val rowH = FloatArray(max(lastRow + 1, 0)) { r ->
                if (r in g.hiddenRows) return@FloatArray 0f
                var n = 1
                for (c in 0 until nCols) {
                    val mg = g.mergeAt(r, c)
                    if (mg != null && mg.bottom > mg.top) continue   // gabungan beberapa baris: tidak menambah tinggi satu baris
                    n = max(n, lines[r][c].size)
                }
                n * LINE + 2 * PAD_Y
            }
            // baris judul kolom: baris berisi pertama yang semua isinya tebal (dan masih ada baris sesudahnya)
            val header = firstRow.takeIf { it in 0 until lastRow }?.takeIf { r ->
                val filled = g.rows[r].filter { it.text.isNotBlank() }
                filled.isNotEmpty() && filled.all { it.bold }
            }
            colWs.add(colW); rowHs.add(rowH); linesAll.add(lines); headers.add(header)

            // kertas & skala
            val total = colW.sum()
            val availP = A4_W - 2 * MARGIN; val availL = A4_H - 2 * MARGIN
            val (land, scale) = when {
                total <= availP -> false to 1f
                total <= availP / 0.9f -> false to availP / total             // sedikit lebih lebar: tetap tegak
                total <= availL -> true to 1f
                total * MIN_SCALE <= availL -> true to availL / total
                else -> true to MIN_SCALE
            }
            val width = (if (land) availL else availP) / scale
            val height = ((if (land) A4_W else A4_H) - 2 * MARGIN - FOOT_H) / scale
            // kolom dibagi ke beberapa halaman bila masih tidak muat
            val bands = ArrayList<List<Int>>()
            var cur = ArrayList<Int>(); var used = 0f
            for (c in 0 until nCols) {
                if (colW[c] <= 0f) continue   // kolom tersembunyi
                if (cur.isNotEmpty() && used + colW[c] > width + 0.01f) { bands.add(cur); cur = ArrayList(); used = 0f }
                cur.add(c); used += colW[c]
            }
            if (cur.isNotEmpty() || bands.isEmpty()) bands.add(cur)

            if (firstRow < 0) {   // lembar kosong: satu halaman berjudul
                pages.add(Page(gi, land, scale, emptyList(), emptyList(), true, 0, 1))
                return@forEachIndexed
            }
            bands.forEachIndexed { bi, cols ->
                var rows = ArrayList<Int>(); var y = 0f; var first = true
                fun avail(firstOfGrid: Boolean) = height - (if (firstOfGrid) TITLE_H else 0f) - (if (bands.size > 1) BAND_H else 0f)
                fun close() {
                    pages.add(Page(gi, land, scale, cols, rows, first && bi == 0, bi, bands.size))
                    first = false; rows = ArrayList(); y = 0f
                }
                for (r in firstRow..lastRow) {
                    if (r in g.hiddenRows) continue
                    val h = rowH[r]
                    val isFirstPage = first && bi == 0
                    // halaman penuh (dan sudah berisi baris selain judul kolom ulangan): lanjut ke halaman baru
                    if (rows.isNotEmpty() && y + h > avail(isFirstPage) && rows.any { it != header }) {
                        close()
                        if (header != null && r != header) { rows.add(header); y += rowH[header] }
                    }
                    rows.add(r); y += h
                }
                if (rows.isNotEmpty()) close()
            }
        }
        return Plan(pages, colWs, rowHs, linesAll, headers)
    }
}
