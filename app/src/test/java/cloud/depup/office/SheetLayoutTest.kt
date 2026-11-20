package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tata letak cetak lembar kerja: semua baris & kolom ikut, halaman, kertas, judul kolom diulang, teks dibungkus. */
class SheetLayoutTest {
    /** Ukuran palsu: 5 pt per huruf (tebal 5,5 pt). */
    private val m = SheetLayout.Measure { t, b -> t.length * (if (b) 5.5f else 5f) }

    private fun grid(rows: List<List<String>>, boldRows: Set<Int> = setOf(0)) =
        SheetLayout.Grid("Lembar", rows.mapIndexed { r, row -> row.map { SheetLayout.Cell(it, r in boldRows) } })

    /** Tiap sel berisi muncul tepat sekali di seluruh halaman (judul kolom ulangan tidak dihitung). */
    private fun assertEveryCellOnce(plan: SheetLayout.Plan, g: SheetLayout.Grid, gi: Int = 0) {
        val seen = HashMap<Pair<Int, Int>, Int>()
        for (p in plan.pages.filter { it.grid == gi }) {
            p.rows.forEachIndexed { i, r ->
                val repeated = i == 0 && r == plan.header[gi] && p.rows.size > 1 && p !== plan.pages.first { it.grid == gi && it.band == p.band }
                if (!repeated) for (c in p.cols) seen.merge(r to c, 1, Int::plus)
            }
        }
        g.rows.forEachIndexed { r, row -> row.forEachIndexed { c, cell ->
            if (cell.text.isNotBlank()) assertEquals("sel ${SheetLayout.colName(c)}${r + 1}", 1, seen[r to c] ?: 0)
        } }
    }

    @Test
    fun smallSheetFitsOnePortraitPage() {
        val g = grid(listOf(listOf("Produk", "Qty", "Harga", "Total"), listOf("Kertas A4", "2", "45000", "90000"), listOf("TOTAL", "", "", "90000")))
        val plan = SheetLayout.plan(listOf(g), m)
        assertEquals(1, plan.pages.size)
        val p = plan.pages[0]
        assertTrue(!p.landscape); assertEquals(1f, p.scale); assertTrue(p.title)
        assertEquals(listOf(0, 1, 2, 3), p.cols); assertEquals(listOf(0, 1, 2), p.rows)
    }

    @Test
    fun longSheetsBreakIntoPagesAndRepeatTheHeader() {
        val rows = listOf(listOf("No", "Nama", "Nilai")) + (1..300).map { listOf("$it", "Siswa $it", "${60 + it % 40}") }
        val g = grid(rows)
        val plan = SheetLayout.plan(listOf(g), m)
        assertTrue("lebih dari satu halaman", plan.pages.size > 3)
        assertEquals(0, plan.header[0])
        plan.pages.drop(1).forEach { assertEquals("judul kolom di atas tiap halaman", 0, it.rows.first()) }
        plan.pages.forEach { p ->
            val h = p.rows.sumOf { plan.rowH[0][it].toDouble() } + (if (p.title) SheetLayout.TITLE_H else 0f)
            assertTrue("isi muat di halaman", h <= SheetLayout.A4_H - 2 * SheetLayout.MARGIN - SheetLayout.FOOT_H + 0.01)
        }
        assertEveryCellOnce(plan, g)
        assertEquals(listOf(true) + List(plan.pages.size - 1) { false }, plan.pages.map { it.title })
    }

    @Test
    fun wideSheetsGoLandscapeShrinkThenSplitColumns() {
        // 5 kolom × 153 pt = 765 pt: mendatar 100%
        val mid = grid(listOf(List(5) { "x".repeat(26) }, List(5) { "y" }))
        SheetLayout.plan(listOf(mid), m).pages.single().let { assertTrue(it.landscape); assertEquals(1f, it.scale) }
        // 6 kolom × 153 pt = 918 pt: mendatar diperkecil (> 75%)
        val wide = grid(listOf(List(6) { "x".repeat(26) }, List(6) { "y" }))
        SheetLayout.plan(listOf(wide), m).pages.single().let { assertTrue(it.landscape); assertTrue(it.scale in 0.75f..0.99f) }
        // 8 kolom × 190 pt = 1520 pt: kolom dibagi ke beberapa halaman (ke bawah dulu, lalu ke kanan)
        val rows = listOf(List(8) { "Judul ${it + 1} " + "x".repeat(40) }) + (1..80).map { r -> List(8) { c -> "r$r c$c" } }
        val huge = grid(rows)
        val plan = SheetLayout.plan(listOf(huge), m)
        val bands = plan.pages.map { it.band }
        assertTrue(plan.pages.all { it.landscape && it.scale == SheetLayout.MIN_SCALE })
        assertTrue("lebih dari satu bagian kolom", plan.pages.first().bands > 1)
        assertEquals("bagian kolom berurutan", bands.sorted(), bands)
        assertEquals((0 until 8).toList(), plan.pages.distinctBy { it.band }.flatMap { it.cols })
        assertEveryCellOnce(plan, huge)
    }

    @Test
    fun slightlyWideSheetsStayPortraitButShrink() {
        // 4 kolom × 142 pt = 568 pt (lebar tegak 523 pt): tetap tegak, diperkecil ±92%
        val g = grid(listOf(List(4) { "x".repeat(24) }, List(4) { "y" }))
        SheetLayout.plan(listOf(g), m).pages.single().let { assertTrue(!it.landscape); assertTrue(it.scale in 0.9f..0.99f) }
    }

    @Test
    fun longTextWrapsAndIsCapped() {
        val words = (1..30).joinToString(" ") { "kata$it" }
        val g = grid(listOf(listOf("Catatan"), listOf(words)), boldRows = emptySet())
        val plan = SheetLayout.plan(listOf(g), m)
        assertEquals(SheetLayout.MAX_W, plan.colW[0][0])
        val lines = plan.lines[0][1][0]
        assertTrue(lines.size in 2..SheetLayout.MAX_LINES)
        assertEquals(words, lines.joinToString(" "))
        assertEquals(lines.size * SheetLayout.LINE + 2 * SheetLayout.PAD_Y, plan.rowH[0][1])
        // teks sangat panjang: maks. 8 baris, diakhiri "…"
        val capped = SheetLayout.wrap("a".repeat(2000), 100f, false, m)
        assertEquals(SheetLayout.MAX_LINES, capped.size)
        assertTrue(capped.last().endsWith("…"))
        assertTrue(capped.all { m.width(it, false) <= 100f })
    }

    @Test
    fun emptyEdgesAreTrimmedAndEmptySheetsStillPrint() {
        val rows = listOf(listOf("A", "B", "", "", ""), listOf("1", "2", "", "", "")) + List(20) { List(5) { "" } }
        val plan = SheetLayout.plan(listOf(grid(rows)), m)
        assertEquals(listOf(0, 1), plan.pages.single().cols)
        assertEquals(listOf(0, 1), plan.pages.single().rows)
        val empty = SheetLayout.plan(listOf(grid(List(5) { List(3) { "" } })), m)
        assertEquals(1, empty.pages.size)
        assertTrue(empty.pages[0].rows.isEmpty() && empty.pages[0].title)
    }

    @Test
    fun everySheetStartsOnItsOwnPage() {
        val a = grid(listOf(listOf("A"), listOf("1")))
        val b = grid(listOf(listOf("B"), listOf("2")))
        val plan = SheetLayout.plan(listOf(a, b), m)
        assertEquals(listOf(0, 1), plan.pages.map { it.grid })
        assertTrue(plan.pages.all { it.title })
    }

    @Test
    fun columnNames() {
        assertEquals(listOf("A", "H", "Z", "AA", "AB", "AZ", "BA"), listOf(0, 7, 25, 26, 27, 51, 52).map { SheetLayout.colName(it) })
    }
}
