package cloud.depup.office

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Uji regresi "AI potong" dengan foto ASLI pengguna yang pernah gagal di HP (tools/autocrop/real/): sudut hasil
 * deteksi harus menutupi dokumen yang benar (IoU ≥ 0,85). Termasuk papan merah tergantung (dokumen berwarna — dulu
 * kalah oleh atap putih) dan struk yang dipegang tangan (dulu sisi kanannya melebar ke silau lampu).
 */
class AutoCropRealTest {
    private fun realDir(): File =
        listOf(File("../tools/autocrop/real"), File("tools/autocrop/real")).firstOrNull { File(it, "cases.json").isFile }
            ?: error("folder tools/autocrop/real tidak ditemukan")

    /** Sama dengan ScanAi.small: dibaca maks 1000 px, lalu diperkecil bertahap (maks 1/2 per langkah, bilinear). */
    private fun small(f: File): ScanCore.Small {
        val img = ImageIO.read(f)
        fun scale(src: BufferedImage, ww: Int, hh: Int): BufferedImage {
            val o = BufferedImage(ww, hh, BufferedImage.TYPE_INT_ARGB)
            val g = o.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, ww, hh, null); g.dispose(); return o
        }
        var cur = img
        val d = 1000f / maxOf(img.width, img.height)
        if (d < 1f) cur = scale(img, (img.width * d).toInt(), (img.height * d).toInt())
        val s = ScanCore.SIDE_PX.toFloat() / maxOf(cur.width, cur.height)
        val w = (cur.width * s).toInt(); val h = (cur.height * s).toInt()
        while (cur.width > w * 2 + 1 || cur.height > h * 2 + 1) cur = scale(cur, maxOf(w, cur.width / 2), maxOf(h, cur.height / 2))
        if (cur.width != w || cur.height != h) cur = scale(cur, w, h)
        val px = IntArray(w * h); cur.getRGB(0, 0, w, h, px, 0, w)
        return ScanCore.fromPixels(px, w, h)
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
            val q = ScanCore.findDocument(small(File(dir, m.groupValues[1])))
            val v = if (q == null) 0f else iou(Quad.order(q), gt)
            if (v < 0.85f) fails.add("${m.groupValues[1]} IoU %.2f".format(v))
        }
        assertTrue("gagal: $fails", fails.isEmpty())
    }
}
