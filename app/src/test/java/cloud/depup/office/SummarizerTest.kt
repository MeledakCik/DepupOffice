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
}
