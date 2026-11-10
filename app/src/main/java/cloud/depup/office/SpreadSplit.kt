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
    @JvmStatic var debug: ((String) -> Unit)? = null
    private const val MIN_ENERGY = 2.5f
    private const val MIN_RATIO = 2.5f
    private const val CONTENT = 0.045f  // pecahan piksel bertepi vertikal (beda > 10): sisi berisi
    private const val BLANK = 0.022f    // di bawah ini: sisi kosong

    /** [q] = 8 angka piksel di gambar kecil [g] (KA, KaA, KaB, KiB). Mengembalikan sudut baru dalam piksel yang sama, atau null. */
    fun apply(g: IntArray, w: Int, h: Int, q: FloatArray): FloatArray? {
        if (g.size < w * h || q.size < 8) return null
        val qw = (dist(q, 0, 1) + dist(q, 3, 2)) / 2f
        val qh = (dist(q, 0, 3) + dist(q, 1, 2)) / 2f
        if (qw < 64f || qh < 64f) return null
        // 2 halaman berdampingan hampir selalu melebar (masing-masing halaman tegak). Bila satu halaman terpotong bingkai
        // foto, bentuknya bisa mendekati bujur sangkar — tapi dokumen TEGAK (A4, struk: lebar < 0,85 x tinggi) tidak
        // pernah dipecah: margin kiri teks / bayangan bisa tampak seperti lipatan.
        if (qw < qh * 0.85f) return null
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
        val nx = xMax - xMin
        val all = FloatArray(17 * nx)
        for (di in 0 until 17) {
            val tn = tan((di - 8) * PI / 180.0).toFloat()
            for (x0 in xMin until xMax) {
                var sum = 0f; var cnt = 0
                for (y in 0 until dh) {
                    val xs = x0 + (y - dh / 2f) * tn
                    val xl = (xs - 2f).roundToInt(); val xr = (xs + 2f).roundToInt()
                    if (xl < 0 || xr >= dw) continue
                    sum += abs(c[y * dw + xr] - c[y * dw + xl]); cnt++
                }
                all[di * nx + (x0 - xMin)] = if (cnt < dh * 0.8f) 0f else sum / cnt
            }
        }
        val sorted = all.copyOf(); sorted.sort()
        val med = sorted[sorted.size / 2]

        /** Tepi kandidat membentang dari atas sampai bawah? (lipatan buku ya; tepi foto KTP / benda lain tidak) */
        fun coverage(x0: Int, tn: Float, e: Float): FloatArray {
            var on = 0; var tot = 0
            val onT = IntArray(3); val totT = IntArray(3)
            val thrE = max(3f, e * 0.4f)
            for (y in 0 until dh) {
                val xs = x0 + (y - dh / 2f) * tn
                val xl = (xs - 2f).roundToInt(); val xr = (xs + 2f).roundToInt()
                if (xl < 0 || xr >= dw) continue
                tot++
                val t3 = min(2, y * 3 / dh); totT[t3]++
                if (abs(c[y * dw + xr] - c[y * dw + xl]) > thrE) { on++; onT[t3]++ }
            }
            if (tot == 0) return floatArrayOf(0f, 0f)
            val minThird = (0 until 3).minOf { if (totT[it] == 0) 0f else onT[it].toFloat() / totT[it] }
            return floatArrayOf(on.toFloat() / tot, minThird, onT[0].toFloat() / max(1, totT[0]), onT[1].toFloat() / max(1, totT[1]), onT[2].toFloat() / max(1, totT[2]))
        }

        // Tepi terkuat belum tentu lipatan (tepi laptop / keyboard yang ikut masuk kotak lebih kontras).
        // Coba beberapa tepi terkuat yang letaknya berbeda; pakai yang pertama lolos syarat lipatan.
        val order = all.indices.sortedByDescending { all[it] }
        val tried = ArrayList<Int>()
        var best = 0f; var bx = -1; var bdeg = 0
        for (idx in order) {
            val e = all[idx]
            if (e < MIN_ENERGY || e < 1.8f * max(med, 0.3f)) break
            val x0 = xMin + idx % nx; val deg = idx / nx - 8
            if (tried.any { abs(it - x0) < 10 }) continue
            tried.add(x0)
            val cov = coverage(x0, tan(deg * PI / 180.0).toFloat(), e)
            debug?.invoke("SPLIT cand x=$x0 deg=$deg e=$e cov=${cov[0]} thirds=${cov.drop(2)}")
            // menerus di (setidaknya) dua dari tiga bagian tinggi halaman — sepertiga sisanya boleh hilang
            // (kotak dokumen sedikit lebih panjang dari buku, bayangan, jari). Tepi foto KTP hanya kuat di tengah.
            val strongThirds = (2..4).count { cov[it] >= 0.8f }
            if (cov[0] >= 0.6f && strongThirds >= 2) { best = e; bx = x0; bdeg = deg; break }
            if (tried.size >= 8) break
        }
        debug?.invoke("SPLIT best=$best med=$med bx=$bx deg=$bdeg dh=$dh")
        if (bx < 0) return null
        val tn = tan(bdeg * PI / 180.0).toFloat()
        val xt = bx + (0 - dh / 2f) * tn
        val xb = bx + (dh - dh / 2f) * tn
        val lo = min(xt, xb); val hi = max(xt, xb)
        val gap = 8
        val lx1 = (lo - gap).toInt(); val rx0 = (hi + gap).toInt()
        if (lx1 < 20 || dw - 4 - rx0 < 20) return null     // tiap sisi minimal ~8% lebar

        // Kedua sisi lipatan harus sama-sama KERTAS (terang serupa). Batas pita judul gelap / foto / kotak berwarna
        // dengan badan dokumen bukan lipatan buku.
        run {
            fun meanC(x0: Int, x1: Int): Float {
                var sum = 0L; var n = 0
                for (y in 4 until dh - 4) for (x in max(0, x0) until min(dw, x1)) { sum += c[y * dw + x]; n++ }
                return if (n == 0) 0f else sum.toFloat() / n
            }
            val ml = meanC(4, lx1); val mr = meanC(rx0, dw - 4)
            debug?.invoke("SPLIT sideMean l=$ml r=$mr")
            if (min(ml, mr) < 0.7f * max(ml, mr) || abs(ml - mr) > 45f) return null
        }
        // ambang 10 (bukan 18): goresan pulpen tipis di foto kecil sudah sangat kabur
        // jauhi bayangan lipatan & pinggir halaman (gradasi di sana bukan tulisan)
        val fl = vEdges(d, dw, dh, 12, lx1 - 8, 10)
        val fr = vEdges(d, dw, dh, rx0 + 8, dw - 12, 10)
        debug?.invoke("SPLIT fl=$fl fr=$fr")
        // Berisi vs kosong dinilai RELATIF juga: tulisan tangan tipis / pensil hanya menghasilkan sedikit tepi, tapi
        // tetap jauh lebih banyak daripada halaman bergaris yang kosong.
        fun content(a: Float, b: Float) = a >= CONTENT || (a >= 0.02f && a >= 3f * b + 0.01f)
        var keepLeft = content(fl, fr) && fr < BLANK
        var keepRight = content(fr, fl) && fl < BLANK
        if (!keepLeft && !keepRight) {
            // Kedua halaman berisi: bila satu halaman terpotong bingkai foto, yang dituju jelas halaman yang utuh.
            fun nearBorder(x: Float) = x < 3f || x > w - 4f
            val leftCut = nearBorder(q[0]) && nearBorder(q[6])
            val rightCut = nearBorder(q[2]) && nearBorder(q[4])
            if (leftCut && !rightCut && fr >= 0.02f) keepRight = true
            else if (rightCut && !leftCut && fl >= 0.02f) keepLeft = true
        }
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
    private fun vEdges(d: IntArray, dw: Int, dh: Int, x0: Int, x1: Int, thr: Int = 18): Float {
        var hit = 0; var tot = 0
        for (y in 6 until dh - 6) for (x in max(x0, 4) + 1 until min(x1, dw - 4) - 1) {
            tot++
            if (abs(d[y * dw + x + 1] - d[y * dw + x - 1]) > thr) hit++
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
