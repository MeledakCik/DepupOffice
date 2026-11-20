package cloud.depup.office

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Uji regresi "AI potong" dengan foto ASLI pengguna yang pernah gagal di HP (tools/autocrop/real/): sudut hasil
 * deteksi harus menutupi dokumen yang benar (IoU ≥ 0,85). Termasuk papan merah tergantung (dokumen berwarna — dulu
 * kalah oleh atap putih) dan struk yang dipegang tangan (dulu sisi kanannya melebar ke silau lampu).
 *
 * Classpath uji Android tidak memuat javax.imageio, jadi yang dibaca adalah `<foto>.small.gz`: foto yang sudah
 * diperkecil persis seperti ScanAi.small (dibuat oleh `java -cp h.jar HarnessKt new realsmall <folder real>`).
 */
class AutoCropRealTest {
    private fun realDir(): File =
        listOf(File("../tools/autocrop/real"), File("tools/autocrop/real")).firstOrNull { File(it, "cases.json").isFile }
            ?: error("folder tools/autocrop/real tidak ditemukan")

    private fun small(f: File): ScanCore.Small = DataInputStream(GZIPInputStream(f.inputStream())).use { i ->
        val w = i.readInt(); val h = i.readInt()
        val px = IntArray(w * h) {
            val r = i.readUnsignedByte(); val g = i.readUnsignedByte(); val b = i.readUnsignedByte()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        ScanCore.fromPixels(px, w, h)
    }

    private fun inside(q: FloatArray, x: Float, y: Float): Boolean {
        var c = false
        for (i in 0 until 4) {
            val j = (i + 3) % 4
            val xi = q[2 * i]; val yi = q[2 * i + 1]; val xj = q[2 * j]; val yj = q[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
        }
        return c
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        var inter = 0; var uni = 0
        for (yy in 0 until 200) for (xx in 0 until 200) {
            val x = (xx + 0.5f) / 200; val y = (yy + 0.5f) / 200
            val ia = inside(a, x, y); val ib = inside(b, x, y)
            if (ia && ib) inter++; if (ia || ib) uni++
        }
        return if (uni == 0) 0f else inter.toFloat() / uni
    }

    @Test
    fun realPhotosAreCroppedToTheRightDocument() {
        val dir = realDir()
        val cases = Regex("\\{\"file\": \"([^\"]+)\", \"quad\": \\[([^\\]]+)\\]").findAll(File(dir, "cases.json").readText()).toList()
        assertTrue("kasus asli kosong", cases.size >= 3)
        val fails = ArrayList<String>()
        for (m in cases) {
            val gt = Quad.order(m.groupValues[2].split(",").map { it.trim().toFloat() }.toFloatArray())
            val q = ScanCore.findDocument(small(File(dir, m.groupValues[1] + ".small.gz")))
            val v = if (q == null) 0f else iou(Quad.order(q), gt)
            if (v < 0.85f) fails.add(m.groupValues[1] + " IoU " + v)
        }
        assertTrue("gagal: $fails", fails.isEmpty())
    }
}
