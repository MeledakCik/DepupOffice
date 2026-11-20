package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Lembar Cetak DepUp: nomor halaman ("1-3, 5") & PDF tugas cetak (halaman pilihan × salinan, tanpa render ulang). */
class PrintPagesTest {
    @Test
    fun parsesPageRanges() {
        assertEquals(listOf(0, 1, 2, 4), PrintPages.parse("1-3, 5", 10))
        assertEquals(listOf(0, 1, 2), PrintPages.parse("1 - 3", 10))
        assertEquals(listOf(0, 1, 2), PrintPages.parse("3-1", 10))
        assertEquals(listOf(7, 8, 9), PrintPages.parse("8-", 10))
        assertEquals(listOf(0, 1, 2), PrintPages.parse("-3", 10))
        assertEquals(listOf(0, 1), PrintPages.parse("2,2 1", 10))
        assertNull(PrintPages.parse("", 10))
        assertNull(PrintPages.parse("0", 10))
        assertNull(PrintPages.parse("11", 10))
        assertNull(PrintPages.parse("1-x", 10))
        assertNull(PrintPages.parse("-", 10))
    }

    @Test
    fun writesCompactRanges() {
        assertEquals("1-3, 5", PrintPages.text(listOf(4, 0, 2, 1)))
        assertEquals("6", PrintPages.text(listOf(5)))
        assertEquals("1, 3, 5-6", PrintPages.text(listOf(0, 2, 4, 5)))
        assertEquals("", PrintPages.text(emptyList()))
    }

    /** PDF 3 halaman; ukuran & sumber daya halaman 1 & 3 DIWARISI dari simpul /Pages (harus tetap terbawa). */
    private fun samplePdf(): File {
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R /PageLabels << /Nums [0 << /S /r >>] >> >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 /MediaBox [0 0 200 300] /Resources << /ProcSet [/PDF] >> >>",
            "<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>",
            "<< /Type /Page /Parent 2 0 R /Contents 7 0 R /MediaBox [0 0 300 200] >>",
            "<< /Type /Page /Parent 2 0 R /Contents 8 0 R >>",
            "stream:% HALAMAN-1",
            "stream:% HALAMAN-2",
            "stream:% HALAMAN-3"
        )
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        w("%PDF-1.4\n")
        val offs = ArrayList<Int>()
        objs.forEachIndexed { i, o ->
            offs.add(out.size())
            if (o.startsWith("stream:")) {
                val body = o.removePrefix("stream:")
                w("${i + 1} 0 obj\n<< /Length ${body.length} >>\nstream\n$body\nendstream\nendobj\n")
            } else w("${i + 1} 0 obj\n$o\nendobj\n")
        }
        val xref = out.size()
        w("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (o in offs) w(String.format("%010d 00000 n \n", o))
        w("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return File.createTempFile("cetak", ".pdf").apply { writeBytes(out.toByteArray()); deleteOnExit() }
    }

    /** Penanda isi tiap halaman PDF [f] ("1", "2", "3", atau "-" untuk halaman kosong). */
    private fun marks(f: File): List<String> {
        val pdf = PdfFile.open(f.readBytes())
        return pdf.pages().map { p ->
            Regex("HALAMAN-(\\d)").find(String(pdf.contents(p), Charsets.ISO_8859_1))?.groupValues?.get(1) ?: "-"
        }
    }

    @Test
    fun buildsSelectedPagesAndCollatedCopiesWithoutRerendering() {
        val src = samplePdf()
        assertEquals(listOf("1", "2", "3"), marks(src))
        val out = File.createTempFile("tugas", ".pdf").apply { deleteOnExit() }

        // halaman 3 & 1, 2 salinan tersusun (jumlah halaman genap: tanpa halaman kosong)
        assertTrue(PrintPages.build(src, listOf(2, 0), 2, true, out))
        assertEquals(listOf("3", "1", "3", "1"), marks(out))
        assertEquals(4, PrintPages.jobPages(2, 2, true))

        // satu halaman, 3 salinan, bolak-balik: tiap salinan mulai di lembar baru
        assertTrue(PrintPages.build(src, listOf(1), 3, true, out))
        assertEquals(listOf("2", "-", "2", "-", "2"), marks(out))
        assertEquals(5, PrintPages.jobPages(1, 3, true))

        // satu sisi: tanpa halaman kosong
        assertTrue(PrintPages.build(src, listOf(1), 3, false, out))
        assertEquals(listOf("2", "2", "2"), marks(out))

        // ukuran warisan dibawa ke halaman sendiri; ukuran milik halaman tetap; label halaman lama dibuang
        assertTrue(PrintPages.build(src, listOf(0, 1), 1, false, out))
        val pdf = PdfFile.open(out.readBytes())
        val pages = pdf.pages()
        assertEquals(2, pages.size)
        assertEquals(listOf(0f, 0f, 200f, 300f), pages[0].box.toList())
        assertEquals(listOf(0f, 0f, 300f, 200f), pages[1].box.toList())
        assertNotNull(pages[0].dict["MediaBox"])
        assertNotNull(pages[0].dict["Resources"])
        assertNull(pdf.catalog["PageLabels"])
    }

    @Test
    fun refusesWhatItCannotBuild() {
        val src = samplePdf()
        val out = File.createTempFile("tugas", ".pdf").apply { deleteOnExit() }
        assertTrue(!PrintPages.build(src, listOf(5), 1, false, out))     // di luar dokumen
        assertTrue(!PrintPages.build(src, emptyList(), 1, false, out))
        val junk = File.createTempFile("rusak", ".pdf").apply { writeText("bukan pdf"); deleteOnExit() }
        assertTrue(!PrintPages.build(junk, listOf(0), 2, false, out))
    }
}
