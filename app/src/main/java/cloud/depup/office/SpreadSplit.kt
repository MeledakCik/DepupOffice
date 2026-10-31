package cloud.depup.office

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Buku/buku tulis terbuka = 2 halaman dalam satu foto. Bila ada garis lipatan (tepi halaman / bayangan punggung buku)
 * dan SATU sisinya kosong (hanya garis buku), ambil sisi yang berisi saja. Murni Kotlin, offline.
 *
 *  1. Luruskan area dokumen ke gambar kecil D (256 px lebar) dengan interpolasi bilinear pada 4 sudut.
 *  2. Cari garis lipatan: tepi vertikal terkuat (boleh miring +-8 derajat) pada D yang goresan tipisnya sudah dihapus
 *     (closing), jadi tulisan & garis buku tidak mengacaukan; hanya tepi lebar (tepi halaman, bayangan) yang tersisa.
 *  3. Sisi berisi = banyak tepi VERTIKAL (goresan huruf/angka). Garis buku hampir murni mendatar, jadi sisi yang
 *     hanya berisi garis buku punya tepi vertikal sangat sedikit.
 *  4. Hanya bila satu sisi jelas berisi dan sisi lain jelas kosong -> kembalikan 4 sudut baru (sisi yang berisi,
 *     dipotong tepat di lipatan, miring mengikuti lipatan). Selain itu null (dua-duanya berisi / tidak yakin).
 */
object SpreadSplit {
    private const val DW = 256
    private const val MIN_ENERGY = 2.5f
    private const val MIN_RATIO = 2.5f
    private const val CONTENT = 0.06f   // pecahan piksel bertepi vertikal: sisi berisi
    private const val BLANK = 0.035f    // di bawah ini: sisi kosong

    /** [q] = 8 angka piksel di gambar kecil [g] (KA, KaA, KaB, KiB). Mengembalikan sudut baru dalam piksel yang sama, atau null. */
    fun apply(g: IntArray, w: Int, h: Int, q: FloatArray): FloatArray? {
        if (g.size < w * h || q.size < 8) return null
        val qw = (dist(q, 0, 1) + dist(q, 3, 2)) / 2f
        val qh = (dist(q, 0, 3) + dist(q, 1, 2)) / 2f
        if (qw < 64f || qh < 64f) return null
        val dw = DW
        val dh = (dw * qh / qw).toInt().coerceIn(96, 512)

        val d = IntArray(dw * dh)
        for (y in 0 until dh) {
            val v = (y + 0.5f) / dh
            for (x in 0 until dw) {
                val u = (x + 0.5f) / dw
                d[y * dw + x] = sample(g, w, h, px(q, u, v), py(q, u, v))
            }
        }
        val c = closeGray(d, dw, dh, 2)

        // ---- garis lipatan ----
        val xMin = (0.2f * dw).toInt(); val xMax = (0.85f * dw).toInt()
        val all = FloatArray(17 * (xMax - xMin)); var n = 0
        var best = 0f; var bx = 0; var bdeg = 0
        for (deg in -8..8) {
            val tn = tan(deg * PI / 180.0).toFloat()
            for (x0 in xMin until xMax) {
                var sum = 0f; var cnt = 0
                for (y in 0 until dh) {
                    val xs = x0 + (y - dh / 2f) * tn
                    val xl = (xs - 2f).roundToInt(); val xr = (xs + 2f).roundToInt()
                    if (xl < 0 || xr >= dw) continue
                    sum += abs(c[y * dw + xr] - c[y * dw + xl]); cnt++
                }
                if (cnt < dh * 0.8f) { all[n++] = 0f; continue }
                val e = sum / cnt
                all[n++] = e
                if (e > best) { best = e; bx = x0; bdeg = deg }
            }
        }
        val sorted = all.copyOf(n); sorted.sort()
        val med = sorted[n / 2]
        if (best < MIN_ENERGY || best < MIN_RATIO * max(med, 0.3f)) return null

        val tn = tan(bdeg * PI / 180.0).toFloat()
        val xt = bx + (0 - dh / 2f) * tn
        val xb = bx + (dh - dh / 2f) * tn
        val lo = min(xt, xb); val hi = max(xt, xb)
        val gap = 8
        val lx1 = (lo - gap).toInt(); val rx0 = (hi + gap).toInt()
        if (lx1 < 20 || dw - 4 - rx0 < 20) return null     // tiap sisi minimal ~8% lebar

        val fl = vEdges(d, dw, dh, 4, lx1)
        val fr = vEdges(d, dw, dh, rx0, dw - 4)
        val keepLeft = fl >= CONTENT && fr < BLANK
        val keepRight = fr >= CONTENT && fl < BLANK
        if (!keepLeft && !keepRight) return null

        val m = 0.025f * dw    // jarak aman dari bayangan lipatan
        val o = FloatArray(8)
        fun put(i: Int, u: Float, v: Float) { o[2 * i] = px(q, u, v); o[2 * i + 1] = py(q, u, v) }
        if (keepLeft) {
            put(0, 0f, 0f); put(1, ((xt - m) / dw).coerceIn(0.05f, 1f), 0f)
            put(2, ((xb - m) / dw).coerceIn(0.05f, 1f), 1f); put(3, 0f, 1f)
        } else {
            put(0, ((xt + m) / dw).coerceIn(0f, 0.95f), 0f); put(1, 1f, 0f)
            put(2, 1f, 1f); put(3, ((xb + m) / dw).coerceIn(0f, 0.95f), 1f)
        }
        return o
    }

    /** Pecahan piksel bertepi vertikal kuat (selisih kiri-kanan > 18) pada kolom [x0, x1). */
    private fun vEdges(d: IntArray, dw: Int, dh: Int, x0: Int, x1: Int): Float {
        var hit = 0; var tot = 0
        for (y in 6 until dh - 6) for (x in max(x0, 4) + 1 until min(x1, dw - 4) - 1) {
            tot++
            if (abs(d[y * dw + x + 1] - d[y * dw + x - 1]) > 18) hit++
        }
        return if (tot == 0) 0f else hit.toFloat() / tot
    }

    private fun dist(q: FloatArray, i: Int, j: Int): Float {
        val dx = q[2 * i] - q[2 * j]; val dy = q[2 * i + 1] - q[2 * j + 1]
        return sqrt(dx * dx + dy * dy)
    }

    // bilinear: KA=0, KaA=1, KaB=2, KiB=3
    private fun px(q: FloatArray, u: Float, v: Float) = (1 - v) * ((1 - u) * q[0] + u * q[2]) + v * ((1 - u) * q[6] + u * q[4])
    private fun py(q: FloatArray, u: Float, v: Float) = (1 - v) * ((1 - u) * q[1] + u * q[3]) + v * ((1 - u) * q[7] + u * q[5])

    private fun sample(g: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val fx = (x - 0.5f).coerceIn(0f, w - 1.001f); val fy = (y - 0.5f).coerceIn(0f, h - 1.001f)
        val x0 = fx.toInt(); val y0 = fy.toInt(); val tx = fx - x0; val ty = fy - y0
        val a = g[y0 * w + x0]; val b = g[y0 * w + x0 + 1]; val c = g[(y0 + 1) * w + x0]; val d = g[(y0 + 1) * w + x0 + 1]
        return ((a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty).roundToInt()
    }

    /** Penutupan abu-abu separabel (dilasi lalu erosi): menghapus goresan gelap tipis. */
    private fun closeGray(g: IntArray, w: Int, h: Int, r: Int): IntArray {
        fun pass(src: IntArray, horiz: Boolean, takeMax: Boolean): IntArray {
            val o = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var v = if (takeMax) -1 else 256
                for (dd in -r..r) {
                    val xx = if (horiz) x + dd else x
                    val yy = if (horiz) y else y + dd
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val s = src[yy * w + xx]
                    if (takeMax) { if (s > v) v = s } else { if (s < v) v = s }
                }
                o[y * w + x] = v
            }
            return o
        }
        val dil = pass(pass(g, true, true), false, true)
        return pass(pass(dil, true, false), false, false)
    }
}
