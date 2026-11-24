package cloud.depup.office

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** EXE → PDF: PDF polos, kunci VaySoft, dan kunci acak yang dipulihkan dari tabel xref. */
class ExeToPdfTest {
    private val K = 12

    private fun samplePdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%" + "x".repeat(300) + "\n")
        sb.append("xref\n0 40\n0000000000 65535 f\r\n")
        for (i in 1 until 40) sb.append(String.format("%010d 00000 n\r\n", i * 37 + 15))
        sb.append("trailer\n<< /Size 40 /Root 1 0 R >>\nstartxref\n9\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun wrap(pdf: ByteArray, key: ByteArray?, prefix: Int): ByteArray {
        val pre = ByteArray(prefix).also { it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte() }
        val body = if (key == null) pdf else ByteArray(pdf.size) { i -> (pdf[i].toInt() xor key[Math.floorMod(prefix + i, K)].toInt()).toByte() }
        return pre + body
    }

    @Test
    fun plainOverlay() {
        val pdf = samplePdf()
        val r = ExeToPdf.extract(wrap(pdf, null, 128))
        assertArrayEquals(pdf, r.pdf)
        assertEquals("PDF tanpa penyamaran", r.method)
    }

    @Test
    fun randomKeysRecoveredExactly() {
        val rnd = java.util.Random(20261124)
        repeat(25) {
            val key = ByteArray(K).also(rnd::nextBytes)
            val pdf = samplePdf()
            val r = ExeToPdf.extract(wrap(pdf, key, 100 + rnd.nextInt(200)))
            assertArrayEquals("kunci ${key.joinToString { "%02x".format(it) }}", pdf, r.pdf)
        }
    }

    @Test
    fun notAPdfFails() {
        assertTrue(runCatching { ExeToPdf.extract(ByteArray(4096) { (it * 7).toByte() }) }.isFailure)
    }
}
