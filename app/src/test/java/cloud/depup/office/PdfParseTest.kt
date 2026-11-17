package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.ISO_8859_1

class PdfParseTest {
    @Test
    fun parsesPageTreeAndContentFromClassicXref() {
        val pdf = PdfFile.open(onePagePdf())

        assertEquals("1.4", pdf.version)
        val pages = pdf.pages()
        assertEquals(1, pages.size)
        assertEquals(listOf(0f, 0f, 300f, 400f), pages.single().box.toList())
        assertEquals("BT (QA page) Tj ET", pdf.contents(pages.single()).toString(ISO_8859_1).trim())
    }

    @Test
    fun rebuildsXrefWhenStartOffsetIsInvalid() {
        val pdf = PdfFile.open(onePagePdf(invalidStartXref = true))

        assertEquals(1, pdf.pages().size)
        assertTrue(pdf.objectNumbers().containsAll(listOf(1, 2, 3, 4)))
    }

    private fun onePagePdf(invalidStartXref: Boolean = false): ByteArray {
        val content = "BT (QA page) Tj ET".toByteArray(ISO_8859_1)
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(ISO_8859_1),
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(ISO_8859_1),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << >> /Contents 4 0 R >>".toByteArray(ISO_8859_1),
            ("<< /Length ${content.size} >>\nstream\n".toByteArray(ISO_8859_1) + content + "\nendstream".toByteArray(ISO_8859_1))
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(ISO_8859_1))
        val offsets = mutableListOf(0)
        objects.forEachIndexed { index, body ->
            offsets += out.size()
            out.write("${index + 1} 0 obj\n".toByteArray(ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(ISO_8859_1))
        }
        val xrefOffset = out.size()
        out.write("xref\n0 5\n0000000000 65535 f \n".toByteArray(ISO_8859_1))
        offsets.drop(1).forEach { offset -> out.write("%010d 00000 n \n".format(offset).toByteArray(ISO_8859_1)) }
        out.write("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n".toByteArray(ISO_8859_1))
        out.write((if (invalidStartXref) 0 else xrefOffset).toString().toByteArray(ISO_8859_1))
        out.write("\n%%EOF\n".toByteArray(ISO_8859_1))
        return out.toByteArray()
    }
}
