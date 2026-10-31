package cloud.depup.office

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Filter "Magic Color" cerdas, murni Kotlin tanpa dependensi Android (bisa diuji di JVM), 100% offline.
 *
 * Berbeda dengan matriks warna statis, filter ini MENGUKUR terang kertas di tiap area gambar lalu menormalkannya:
 * bayangan tangan/ponsel, gradasi lampu, dan sudut gelap hilang; kertas jadi putih bersih; tinta tetap pekat;
 * warna (stempel, tanda tangan biru, logo) tetap hidup karena ketiga kanal dikali gain yang sama (rona terjaga).
 */
object DocFilter {

    /** Ubah [px] (ARGB, [w]x[h]) di tempat. */
    fun magic(px: IntArray, w: Int, h: Int) {
        if (w < 8 || h < 8 || px.size < w * h) return
        val cell = max(8, max(w, h) / 40)
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val bg = paperMap(px, w, h, cell, gw, gh)

        // Gain per sel = 255 / (96% terang kertas): kertas tepat jenuh jadi putih, bintik noise kertas terpangkas.
        val gain = FloatArray(gw * gh) { 255f / (bg[it] * 0.96f) }

        // LUT kurva: gamma ringan memperpekat tinta tanpa membuat warna pastel jadi kelabu.
        val lut = IntArray(256) { (255.0 * (it / 255.0).pow(1.2)).toInt().coerceIn(0, 255) }

        // Interpolasi bilinear gain antar pusat sel supaya tidak ada kotak-kotak.
        val ix0 = IntArray(w); val ix1 = IntArray(w); val tx = FloatArray(w)
        for (x in 0 until w) {
            val f = (x + 0.5f) / cell - 0.5f
            val i0 = f.toInt().let { if (f < 0f) 0 else it }
            ix0[x] = i0.coerceIn(0, gw - 1)
            ix1[x] = (i0 + 1).coerceIn(0, gw - 1)
            tx[x] = (f - i0).coerceIn(0f, 1f).let { if (f < 0f) 0f else it }
        }
        val sat = 1.12f
        for (y in 0 until h) {
            val f = (y + 0.5f) / cell - 0.5f
            val j0 = (if (f < 0f) 0 else f.toInt()).coerceIn(0, gh - 1)
            val j1 = (j0 + 1).coerceIn(0, gh - 1)
            val ty = if (f < 0f) 0f else (f - j0).coerceIn(0f, 1f)
            val r0 = j0 * gw; val r1 = j1 * gw
            val row = y * w
            for (x in 0 until w) {
                val a = ix0[x]; val b = ix1[x]; val t = tx[x]
                val g0 = gain[r0 + a] * (1f - t) + gain[r0 + b] * t
                val g1 = gain[r1 + a] * (1f - t) + gain[r1 + b] * t
                val g = g0 * (1f - ty) + g1 * ty
                val c = px[row + x]
                val r = min(255f, ((c shr 16) and 255) * g)
                val gr = min(255f, ((c shr 8) and 255) * g)
                val bl = min(255f, (c and 255) * g)
                // Naikkan saturasi sedikit di sekitar luminans (hanya kalau bukan sudah putih/abu).
                val lum = r * 0.299f + gr * 0.587f + bl * 0.114f
                val r2 = (lum + (r - lum) * sat).toInt().coerceIn(0, 255)
                val g2 = (lum + (gr - lum) * sat).toInt().coerceIn(0, 255)
                val b2 = (lum + (bl - lum) * sat).toInt().coerceIn(0, 255)
                px[row + x] = (c and 0xFF000000.toInt()) or (lut[r2] shl 16) or (lut[g2] shl 8) or lut[b2]
            }
        }
    }

    /**
     * Peta terang kertas per sel: persentil ke-90 luminans (tinta jarang >10% area sel) lalu dilasi 5x5 + blur
     * supaya blok gelap besar (foto, logo, header tabel) tidak dianggap bayangan dan di-"putihkan".
     */
    private fun paperMap(px: IntArray, w: Int, h: Int, cell: Int, gw: Int, gh: Int): FloatArray {
        val p90 = FloatArray(gw * gh)
        val hist = IntArray(256)
        val step = max(1, cell / 16)
        for (cy in 0 until gh) for (cx in 0 until gw) {
            java.util.Arrays.fill(hist, 0)
            var n = 0
            val x0 = cx * cell; val x1 = min(w, x0 + cell)
            val y0 = cy * cell; val y1 = min(h, y0 + cell)
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = px[y * w + x]
                    hist[(((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000]++
                    n++; x += step
                }
                y += step
            }
            var acc = 0; var v = 255
            val target = n * 0.9f
            for (i in 0..255) { acc += hist[i]; if (acc >= target) { v = i; break } }
            p90[cy * gw + cx] = max(8, v).toFloat()
        }
        // Closing (dilasi lalu erosi, jendela 9x9 sel): blok gelap sempit (foto, logo, header tabel) terisi nilai kertas
        // di sekitarnya, tetapi gradasi bayangan yang mulus kembali PERSIS (closing pada tanjakan = identitas),
        // jadi tidak ada bias naik seperti pada dilasi saja.
        val clo = closing(p90, gw, gh, 4)
        // Hanya "lubang" yang DALAM (>~40% lebih gelap dari sekitarnya) yang diisi. Bayangan tangan/ponsel biasanya
        // lebih redup (10-30%) walau sempit dan tajam, jadi tetap dikoreksi apa adanya.
        val adj = FloatArray(gw * gh)
        for (i in adj.indices) {
            val dip = (clo[i] - p90[i]) / clo[i]
            val wgt = ((dip - 0.22f) / 0.18f).coerceIn(0f, 1f)
            adj[i] = p90[i] + wgt * (clo[i] - p90[i])
        }
        val sm = adj   // tanpa blur: interpolasi bilinear antar pusat sel sudah cukup halus dan tepi bayangan tetap tajam
        // Lantai: jangan menganggap kertas lebih gelap dari 55% terang kertas global (median). Blok gelap yang lebih
        // lebar dari jendela closing tetap tidak terangkat lebih dari ~1,8x, jadi foto besar tidak "terbakar".
        val sorted = sm.copyOf(); sorted.sort()
        val median = sorted[sorted.size / 2]
        val floor = max(8f, median * 0.55f)
        for (i in sm.indices) sm[i] = max(sm[i], floor)
        return sm
    }

    /** Closing persegi (2r+1)x(2r+1) pada peta [a] gw x gh; tepi direplikasi agar gradasi di pinggir tetap benar. */
    private fun closing(a: FloatArray, gw: Int, gh: Int, r: Int): FloatArray {
        val pw = gw + 2 * r; val ph = gh + 2 * r
        var cur = FloatArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            cur[y * pw + x] = a[(y - r).coerceIn(0, gh - 1) * gw + (x - r).coerceIn(0, gw - 1)]
        }
        fun pass(src: FloatArray, horiz: Boolean, takeMax: Boolean): FloatArray {
            val o = FloatArray(pw * ph)
            for (y in 0 until ph) for (x in 0 until pw) {
                var v = if (takeMax) -1f else 1e9f
                for (d in -r..r) {
                    val xx = if (horiz) x + d else x
                    val yy = if (horiz) y else y + d
                    if (xx < 0 || yy < 0 || xx >= pw || yy >= ph) continue
                    val q = src[yy * pw + xx]
                    if (takeMax) { if (q > v) v = q } else { if (q < v) v = q }
                }
                o[y * pw + x] = v
            }
            return o
        }
        cur = pass(pass(cur, true, true), false, true)
        cur = pass(pass(cur, true, false), false, false)
        val out = FloatArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) out[y * gw + x] = cur[(y + r) * pw + x + r]
        return out
    }
}
