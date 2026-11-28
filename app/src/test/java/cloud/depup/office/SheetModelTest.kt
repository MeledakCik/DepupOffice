package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model lembar kerja: ukuran baris/kolom, penggeseran rujukan rumus, gaya bersama, Urungkan/Ulangi. */
class SheetModelTest {

    @Test
    fun axisPositionsWithCustomSizes() {
        val a = Axis(10f)
        assertEquals(0.0, a.pos(0), 0.0)
        assertEquals(50.0, a.pos(5), 0.0)
        a.set(2, 30f)      // +20
        a.set(4, 0f)       // tersembunyi: -10
        assertEquals(20.0, a.pos(2), 0.0)
        assertEquals(50.0, a.pos(3), 0.0)
        assertEquals(60.0, a.pos(4), 0.0)
        assertEquals(60.0, a.pos(5), 0.0)
        assertEquals(1_000_000.0 * 10 + 10, a.pos(1_000_000), 0.0)
        // indeks dari posisi
        assertEquals(0, a.indexAt(5.0))
        assertEquals(2, a.indexAt(20.0))
        assertEquals(2, a.indexAt(49.9))
        assertEquals(3, a.indexAt(50.0))
        assertEquals(5, a.indexAt(60.0))   // baris 4 tersembunyi dilewati
        assertEquals(1_000_000, a.indexAt(a.pos(1_000_000) + 1))
    }

    @Test
    fun axisShiftOnInsertAndDelete() {
        val a = Axis(10f)
        a.set(3, 25f); a.set(8, 40f)
        a.shift(2, 2)        // sisip 2 di indeks 2
        assertEquals(25f, a.size(5)); assertEquals(40f, a.size(10)); assertEquals(10f, a.size(3))
        a.shift(4, -2)       // hapus indeks 4,5 → ukuran khusus di 5 ikut terhapus
        assertEquals(10f, a.size(4)); assertEquals(10f, a.size(5)); assertEquals(40f, a.size(8))
    }

    @Test
    fun formulaShiftCopiesRelativeRefsLikeExcel() {
        assertEquals("=B3*C3", FormulaRefs.shift("=B2*C2", 1, 0))
        assertEquals("=SUM(\$A\$1:B4)+C3", FormulaRefs.shift("=SUM(\$A\$1:A3)+B2", 1, 1))
        assertEquals("=\$B3*C\$2", FormulaRefs.shift("=\$B2*B\$2", 1, 1))
        // teks dalam kutip & nama fungsi tidak disentuh
        assertEquals("=CONCAT(\"A1\",B2)&LOG10(C2)", FormulaRefs.shift("=CONCAT(\"A1\",A1)&LOG10(B1)", 1, 1))
        // keluar lembar → #REF!
        assertEquals("=#REF!+1", FormulaRefs.shift("=A1+1", -1, 0))
        // bukan rumus: tidak diubah
        assertEquals("A1", FormulaRefs.shift("A1", 3, 3))
    }

    @Test
    fun formulaRefsFollowInsertedAndDeletedRows() {
        assertEquals("=SUM(D2:D6)", FormulaRefs.structural("=SUM(D2:D5)", true, 3, 1))     // sisip di tengah: memanjang
        assertEquals("=SUM(D2:D5)", FormulaRefs.structural("=SUM(D2:D5)", true, 10, 1))    // sesudahnya: tetap
        assertEquals("=SUM(D2:D4)", FormulaRefs.structural("=SUM(D2:D5)", true, 2, -1))    // hapus di tengah: menyusut
        assertEquals("=#REF!", FormulaRefs.structural("=B3", true, 2, -1))                 // sel yang dirujuk dihapus
        assertEquals("=B3*\$C\$3", FormulaRefs.structural("=B2*\$C\$2", true, 0, 1))        // $ juga ikut (seperti Excel)
        assertEquals("=SUM(A1:B1)", FormulaRefs.structural("=SUM(A1:C1)", false, 1, -1))  // hapus kolom B
        assertEquals("=SUM(#REF!)", FormulaRefs.structural("=SUM(B1:C1)", false, 1, -2))   // seperti Excel
    }

    @Test
    fun stylesAreShared() {
        val a = SheetCell("x", CellStyle(bold = true, fill = 0xFFFF0000.toInt()))
        val b = SheetCell("y", CellStyle(bold = true, fill = 0xFFFF0000.toInt()))
        assertSame(a.style, b.style)
        assertTrue(SheetCell("").style.isDefault)
    }

    @Test
    fun undoRedo() {
        val u = UndoStack()
        var v = 0
        u.push(UndoStack.Edit("a", { v = 0 }, { v = 1 })); v = 1
        u.push(UndoStack.Edit("b", { v = 1 }, { v = 2 })); v = 2
        u.undo(); assertEquals(1, v)
        u.undo(); assertEquals(0, v)
        u.redo(); assertEquals(1, v)
        assertTrue(u.canRedo)
        u.push(UndoStack.Edit("c", { v = 1 }, { v = 5 })); v = 5
        assertTrue(!u.canRedo)
    }

    @Test
    fun rangeRef() {
        assertEquals("B2:D5", CellRange(4, 3, 1, 1).ref())
        assertEquals("AA10", CellRange(9, 26, 9, 26).ref())
    }
}
