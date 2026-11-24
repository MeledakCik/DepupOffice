package cloud.depup.office

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dokumen Terlindungi buatan iPhone dibuka oleh Android. Fixture ios_protected_v3.pdf identik byte-per-byte dengan
 * keluaran ProtectedDocWriter.cover di iOS (dibuktikan oleh DepupOfficeTests.testWriterOutputMatchesAndroidFixture).
 */
class CrossPlatformTest {
    private fun fixture(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("ios_protected_v3.pdf")).use { it.readBytes() }

    @Test
    fun androidOpensIosProtectedDocument() {
        val f = PdfFile.open(fixture())
        val ref = f.catalog["DepupSecureData"] as PRef
        val pkg = f.decode(f.res(ref) as PStream, null)
        val (body, tag) = SecurePackage.split(pkg)
        val meta = SecurePackage.parseMeta(body)
        assertEquals(3, meta.version)
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", meta.docId)
        val plain = SecurePackage.openV3(body, tag, meta, "7kq2-m9xd-4rtw")
        assertArrayEquals("%PDF-1.4 contoh isi rahasia".toByteArray(), plain)
        assertTrue(SecurePackage.editPasswordMatches(meta, "GuruRahasia!"))
        assertEquals(1, f.pages().size)
    }
}
