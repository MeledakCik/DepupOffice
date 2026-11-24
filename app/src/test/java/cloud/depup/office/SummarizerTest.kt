package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ringkasan: baris PDF yang terpotong disambung & poin ganda dibuang (sama dengan iOS). */
class SummarizerTest {
    @Test
    fun joinsWrappedPdfLinesAndDropsDuplicates() {
        val text = "Kegiatan bulan ini berjalan lancar. Kesimpulan penting: anggaran disetujui\n" +
            "dan laporan dikirim tepat waktu.\n\nKegiatan bulan ini berjalan lancar. Kesimpulan penting: anggaran disetujui\n" +
            "dan laporan dikirim tepat waktu."
        val points = DocumentSummarizerManager.extractKeyPoints(text)
        assertEquals(1, points.size)
        assertEquals("Kegiatan bulan ini berjalan lancar. Kesimpulan penting: anggaran disetujui dan laporan dikirim tepat waktu.", points[0])
    }

    @Test
    fun headingStaysSeparateAndRepeatedPageCountsOnce() {
        val page = "Kegiatan bulan ini berjalan lancar. Kesimpulan penting: anggaran disetujui\n" +
            "dan laporan dikirim tepat waktu. Catatan: rapat berikutnya hari Senin pukul 09.00."
        val points = DocumentSummarizerManager.extractKeyPoints("Laporan Kegiatan\n$page\nLampiran\n$page")
        assertEquals(1, points.size)
        assertEquals(true, points[0].startsWith("Kegiatan bulan ini"))
    }
}
