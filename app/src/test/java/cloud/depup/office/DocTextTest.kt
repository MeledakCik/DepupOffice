package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.ISO_8859_1

/** Pencarian isi dokumen (bagian murni): normalisasi, cuplikan, teks Word/Excel/PowerPoint, dan teks PDF. */
class DocTextTest {
    private fun find(raw: String, q: String) = DocText.snippet(raw, DocText.norm(raw), DocText.norm(q))
    private fun DocText.Snip.hit() = text.substring(start, end)

    @Test
    fun matchIgnoresCaseAndSpaces() {
        val raw = "Total   pembayaran:\n Rp 1.250.000,-  lunas"
        val s = find(raw, "rp 1.250")!!
        assertEquals("Rp 1.250", s.hit())
        assertEquals("Total pembayaran: Rp 1.250.000,- lunas", s.text)
        // kata yang terpecah tanpa spasi di PDF tetap ketemu
        assertEquals("Rp 1.250.000", find(raw, "RP1.250.000")!!.hit())
        assertNull(find(raw, "transfer"))
    }

    @Test
    fun snippetDoesNotCutWordsAndMarksEllipsis() {
        val raw = "a".repeat(10) + " " + "kata ".repeat(20) + "SURAT KEPUTUSAN nomor 12 " + "lanjut ".repeat(30)
        val s = find(raw, "surat keputusan")!!
        assertEquals("SURAT KEPUTUSAN", s.hit())
        assertTrue(s.text.startsWith("…kata "))
        assertTrue(s.text.endsWith("…"))
    }

    @Test
    fun softHyphenAndNbspAreInvisible() {
        val raw = "Ang­garan Desa"
        val s = find(raw, "anggaran desa")!!
        assertEquals("Anggaran Desa", s.hit())
    }

    @Test
    fun wordXmlParagraphsTabsAndFieldCodes() {
        val xml = """<?xml version="1.0"?><w:document><w:body>""" +
            """<w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr><w:r><w:t>Laporan</w:t></w:r>""" +
            """<w:r><w:t xml:space="preserve"> Keuangan &amp; Pajak</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r><w:t>Halaman</w:t></w:r>""" +
            """<w:r><w:tab/></w:r><w:r><w:t>2</w:t></w:r><w:r><w:br/></w:r><w:r><w:t>&#8364; 5 &#x2013; selesai</w:t></w:r></w:p>""" +
            """</w:body></w:document>"""
        val sb = StringBuilder()
        DocText.xmlText(xml, sb)
        assertEquals("Laporan Keuangan & Pajak\nHalaman 2\n€ 5 – selesai\n", sb.toString())
    }

    @Test
    fun excelSharedStringsAndSheetCells() {
        val sb = StringBuilder()
        DocText.xmlText("""<sst><si><t>Nama Barang</t></si><si><r><t>Kopi </t></r><r><t>Bubuk</t></r></si></sst>""", sb)
        DocText.sheet("""<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" s="2"/>""" +
            """<c r="C1"><v>125000</v></c><c r="D1" t="inlineStr"><is><t>Lunas &lt;tunai&gt;</t></is></c>""" +
            """<c r="E1" t="str"><f>A1&amp;B1</f><v>gabung</v></c></row></sheetData></worksheet>""", sb)
        assertEquals("Nama Barang\nKopi Bubuk\n125000 Lunas <tunai> gabung ", sb.toString())
        // indeks teks bersama ("0") tidak ikut terbaca sebagai angka
        assertTrue(!sb.toString().contains(" 0 "))
    }

    @Test
    fun slideText() {
        val sb = StringBuilder()
        DocText.xmlText("""<p:sld><a:p><a:r><a:t>Rencana</a:t></a:r><a:br/><a:r><a:t>Kerja 2026</a:t></a:r></a:p></p:sld>""", sb)
        assertEquals("Rencana\nKerja 2026\n", sb.toString())
    }

    @Test
    fun pdfTextLayerIsSearchable() {
        val pdf = PdfFile.open(textPdf())
        val sb = StringBuilder()
        for (p in pdf.pages()) DocText.glyphText(PdfLayout.page(pdf, p, textOnly = true).glyphs, sb)
        val raw = sb.toString()
        assertNotNull(raw, find(raw, "laporan keuangan 2026"))
        assertEquals("Rp 1.250.000", find(raw, "rp 1.250.000")!!.hit())
        // dua baris terpisah, bukan satu untai
        assertTrue(raw.contains('\n'))
    }

    private fun textPdf(): ByteArray {
        val content = "BT /F1 12 Tf 72 700 Td (Laporan Keuangan 2026) Tj 0 -18 Td (Total: Rp 1.250.000) Tj ET".toByteArray(ISO_8859_1)
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            null,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(ISO_8859_1))
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.size()
            out.write("${i + 1} 0 obj\n".toByteArray(ISO_8859_1))
            if (body != null) out.write(body.toByteArray(ISO_8859_1))
            else {
                out.write("<< /Length ${content.size} >>\nstream\n".toByteArray(ISO_8859_1))
                out.write(content)
                out.write("\nendstream".toByteArray(ISO_8859_1))
            }
            out.write("\nendobj\n".toByteArray(ISO_8859_1))
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray(ISO_8859_1))
        offsets.forEach { out.write("%010d 00000 n \n".format(it).toByteArray(ISO_8859_1)) }
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray(ISO_8859_1))
        return out.toByteArray()
    }
}
