package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mesin rumus editor Sheet: operator, fungsi gaya Excel, galat, rujukan melingkar, rantai panjang. */
class SheetFormulaTest {

    private class Grid(rows: List<List<String>>) : FormulaEngine.Source {
        val m = rows.map { it.toMutableList() }.toMutableList()
        override fun raw(r: Int, c: Int) = m.getOrNull(r)?.getOrNull(c)
        override fun rowCount() = m.size
        override fun colCount() = m.maxOfOrNull { it.size } ?: 0
        override fun number(text: String): Double? = text.replace("Rp", "").replace(".", "").replace(',', '.').trim().toDoubleOrNull()
    }

    private fun eng(vararg rows: List<String>) = FormulaEngine(Grid(rows.toList()))
    private fun FormulaEngine.f(x: String) = evalFormula(x)

    @Test
    fun arithmeticAndPrecedence() {
        val e = eng(listOf("2", "3"))
        assertEquals(14.0, e.f("=A1+B1*4"))
        assertEquals(20.0, e.f("=(A1+B1)*4"))
        assertEquals(8.0, e.f("=A1^3"))
        assertEquals(0.5, e.f("=50%"))
        assertEquals("#DIV/0!", e.f("=A1/0").toString())
        assertEquals("23", e.f("=A1&B1"))
        assertEquals(true, e.f("=A1<B1"))
        assertEquals(false, e.f("=A1=B1"))
    }

    @Test
    fun aggregatesIgnoreTextInRanges() {
        val e = eng(listOf("Qty"), listOf("2"), listOf("x"), listOf("5"), listOf(""))
        assertEquals(7.0, e.f("=SUM(A1:A100)"))
        assertEquals(3.5, e.f("=AVERAGE(A2:A5)"))
        assertEquals(2.0, e.f("=COUNT(A1:A5)"))
        assertEquals(4.0, e.f("=COUNTA(A1:A5)"))
        assertEquals(5.0, e.f("=MAX(A1:A5)"))
        assertEquals(0.0, e.f("=SUM(A3)"))   // satu sel teks diabaikan, seperti Excel
    }

    @Test
    fun logicAndText() {
        val e = eng(listOf("150", "Budi"))
        assertEquals("Lulus", e.f("=IF(A1>100,\"Lulus\",\"Tidak\")"))
        assertEquals("aman", e.f("=IFERROR(1/0,\"aman\")"))
        assertEquals("Budi Santoso", e.f("=CONCAT(B1,\" \",\"Santoso\")"))
        assertEquals("BUD", e.f("=UPPER(LEFT(B1,3))"))
        assertEquals(4.0, e.f("=LEN(B1)"))
        assertEquals("ud", e.f("=MID(B1,2,2)"))
        assertEquals(true, e.f("=AND(A1>1,B1=\"budi\")"))
        assertEquals(true, e.f("=ISERROR(1/0)"))
        // cabang yang tidak dipakai tidak dihitung
        assertEquals(1.0, e.f("=IF(TRUE,1,1/0)"))
    }

    @Test
    fun conditionalAndLookup() {
        val e = eng(
            listOf("Apel", "10"), listOf("Jeruk", "20"), listOf("Apel", "5"), listOf("Mangga", "40")
        )
        assertEquals(15.0, e.f("=SUMIF(A1:A4,\"Apel\",B1:B4)"))
        assertEquals(2.0, e.f("=COUNTIF(B1:B4,\">=20\")"))
        assertEquals(3.0, e.f("=COUNTIF(A1:A4,\"*e*\")"))   // Apel, Jeruk, Apel
        assertEquals(20.0, e.f("=VLOOKUP(\"jeruk\",A1:B4,2,FALSE)"))
        assertEquals("#N/A", e.f("=VLOOKUP(\"Nanas\",A1:B4,2,FALSE)").toString())
        assertEquals(4.0, e.f("=MATCH(\"Mangga\",A1:A4,0)"))
        assertEquals(40.0, e.f("=INDEX(B1:B4,4)"))
    }

    @Test
    fun roundingAndDates() {
        val e = eng(listOf("2,345"))
        assertEquals(2.35, e.f("=ROUND(A1,2)") as Double, 1e-9)
        assertEquals(2.4, e.f("=ROUNDUP(A1,1)") as Double, 1e-9)
        assertEquals(2.3, e.f("=ROUNDDOWN(A1,1)") as Double, 1e-9)
        assertEquals(45658.0, e.f("=DATE(2025,1,1)"))
        assertEquals(2025.0, e.f("=YEAR(DATE(2025,1,1))"))
        assertEquals(12.0, e.f("=MONTH(45657)"))
    }

    @Test
    fun cellReferencesCyclesAndErrors() {
        val g = Grid(listOf(listOf("=B1+1", "=A1+1"), listOf("=C9", "=FOO(1)")))
        val e = FormulaEngine(g)
        assertEquals("#SIKLUS!", e.value(0, 0).toString())
        assertEquals(null, e.value(1, 0))            // sel kosong
        assertEquals("#NAME?", e.value(1, 1).toString())
    }

    @Test
    fun longChainsDoNotOverflow() {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("1"))
        for (i in 1 until 20_000) rows.add(listOf("=A$i+1"))
        val e = FormulaEngine(Grid(rows))
        assertEquals(20_000.0, e.value(19_999, 0))
    }

    @Test
    fun supportedDetection() {
        assertTrue(FormulaEngine.supported("=SUM(A1:A3)+IF(B1>2,1,0)"))
        assertTrue(!FormulaEngine.supported("=XLOOKUP(A1,B:B,C:C)"))
        assertTrue(!FormulaEngine.supported("=Sheet2!A1"))
    }
}
