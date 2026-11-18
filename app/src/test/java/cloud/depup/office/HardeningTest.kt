package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.util.zip.ZipFile

/** Uji untuk perbaikan audit: batas baca, PDF bersarang jahat, rumus ke .xlsx, tautan aman. */
class HardeningTest {

    @Test
    fun readCappedStopsAtLimitInsteadOfExhaustingMemory() {
        val data = ByteArray(10_000) { 7 }
        assertEquals(10_000, Files.readCapped(ByteArrayInputStream(data), 10_000).size)
        try {
            Files.readCapped(ByteArrayInputStream(data), 9_999)
            fail("harus melempar TooLargeException")
        } catch (_: Files.TooLargeException) {
        }
    }

    @Test
    fun deeplyNestedPdfArrayIsRejectedWithoutStackOverflow() {
        val evil = "[".repeat(200_000).toByteArray(ISO_8859_1)
        try {
            PdfLexer(evil).obj()
            fail("harus menolak struktur yang terlalu dalam")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("terlalu dalam"))
        }
    }

    @Test
    fun normalNestingStillParses() {
        val v = PdfLexer("[1 [2 [3]] << /A [4] >>]".toByteArray(ISO_8859_1)).obj()
        assertTrue(v is List<*>)
        assertEquals(3, (v as List<*>).size)
    }

    @Test
    fun xlsxKeepsFormulasAsRealExcelFormulas() {
        val f = File.createTempFile("rumus", ".xlsx")
        try {
            val rows = listOf(listOf("1", "2", "3"), listOf("", "", ""))
            XlsxWriter.write(f, "Uji", rows, emptySet(), 3) { r, c ->
                when {
                    r == 1 && c == 0 -> "=sum(A1:C1)" to 6.0
                    r == 1 && c == 1 -> "=AVG(A1:C1)" to 2.0
                    else -> null
                }
            }
            val sheet = ZipFile(f).use { z -> z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readBytes().toString(Charsets.UTF_8) }
            assertTrue(sheet, sheet.contains("<c r=\"A2\"><f>SUM(A1:C1)</f><v>6</v></c>"))
            assertTrue(sheet, sheet.contains("<f>AVERAGE(A1:C1)</f><v>2</v>"))
            assertTrue(sheet, sheet.contains("<c r=\"A1\"><v>1</v></c>"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun documentLinksOnlyAllowSafeSchemes() {
        assertEquals("https://depup.web.id", OxUtil.safeHref(" https://depup.web.id "))
        assertEquals("mailto:a@b.c", OxUtil.safeHref("mailto:a@b.c"))
        assertNull(OxUtil.safeHref("javascript:alert(1)"))
        assertNull(OxUtil.safeHref("JavaScript:alert(1)"))
        assertNull(OxUtil.safeHref("intent://x#Intent;end"))
        assertNull(OxUtil.safeHref("file:///data/data/x"))
        assertFalse(OxUtil.safeHref(null) != null)
    }
}
