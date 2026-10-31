package cloud.depup.office

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deteksi 4 SUDUT dokumen (untuk koreksi perspektif ala CamScanner). Murni Kotlin tanpa dependensi Android,
 * jadi bisa diuji di JVM. 100% offline, tanpa model/AI berbayar.
 *
 * Cara kerja: wilayah kertas ditumbuhkan dari tengah gambar (tepi kertas = "dinding" kemiringan terang yang tajam),
 * lubang diisi, lalu 4 sudut dicari sebagai segi-empat berluas maksimum di dalam cembung (convex hull) wilayah itu.
 * Ini tahan terhadap dokumen yang miring/diputar, bukan hanya yang sejajar bingkai.
 */
object DocQuad {

    /**
     * [g] = gambar abu-abu kecil (±320 px, sudah di-blur ringan), [w]x[h].
     * Mengembalikan 8 angka piksel (x,y) berurutan KIRI-ATAS, KANAN-ATAS, KANAN-BAWAH, KIRI-BAWAH,
     * atau null bila tidak yakin ATAU dokumen praktis sudah berupa persegi panjang sejajar bingkai
     * (biar jalur potong biasa yang menanganinya, tanpa resampling yang tak perlu).
     */
    fun detect(g: IntArray, w: Int, h: Int): FloatArray? {
        if (w < 16 || h < 16 || g.size < w * h) return null
        val closed = closeGray(g, w, h, 3)
        val queue = IntArray(w * h + 8)
        var best: FloatArray? = null
        var bestScore = 0f

        fun consider(mask: BooleanArray) {
            val inside = BooleanArray(w * h)
            val count = fillHoles(mask, inside, w, h, queue)
            val q = quadOf(inside, w, h) ?: return
            val qa = polyArea(q)
            val area = qa / (w * h).toFloat()
            if (area < 0.15f || area > 0.985f) return      // area ~1 = wilayah bocor menelan seluruh frame
            val fill = count / qa
            val fq = if (fill > 1f) 1f / fill else fill
            if (fq < 0.80f) return                           // wilayah tidak mirip segi-empat
            // Latar (meja) juga bisa tampak seperti segi-empat. Dokumen biasanya memuat titik tengah foto dan
            // tidak menempel ke hampir seluruh pinggir bingkai, sedangkan latar sebaliknya.
            var score = fq * fq * area
            if (!inside[(h / 2) * w + w / 2]) score *= 0.6f
            if (borderFraction(inside, w, h) > 0.5f) score *= 0.5f
            if (score > bestScore) { bestScore = score; best = q }
        }

        val sl = slope(closed, w, h)
        val seeds = arrayOf(0.5f to 0.5f, 0.38f to 0.38f, 0.62f to 0.38f, 0.38f to 0.62f, 0.62f to 0.62f)
        for ((fx, fy) in seeds) {
            val seed = (fy * h).toInt().coerceIn(0, h - 1) * w + (fx * w).toInt().coerceIn(0, w - 1)
            for (ms in intArrayOf(10, 6, 4)) {
                val m = grow(closed, sl, w, h, seed, ms, queue) ?: continue
                consider(m)
            }
        }
        if (best == null) otsuMask(g, w, h, queue)?.let { consider(it) }

        val q = best ?: return null
        // Lebarkan sedikit ke luar (wilayah tumbuh berhenti 1 piksel sebelum garis tepi kertas).
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val o = FloatArray(8)
        for (i in 0 until 4) {
            val dx = q[2 * i] - cx; val dy = q[2 * i + 1] - cy
            val d = max(1e-3f, sqrt(dx * dx + dy * dy))
            o[2 * i] = (q[2 * i] + dx / d * 1.2f).coerceIn(0f, w.toFloat())
            o[2 * i + 1] = (q[2 * i + 1] + dy / d * 1.2f).coerceIn(0f, h.toFloat())
        }
        return if (isTrivial(o, w, h)) null else o
    }

    /** Pecahan piksel pinggir bingkai yang termasuk wilayah. */
    private fun borderFraction(inside: BooleanArray, w: Int, h: Int): Float {
        var n = 0; var total = 0
        for (x in 0 until w) { total += 2; if (inside[x]) n++; if (inside[(h - 1) * w + x]) n++ }
        for (y in 1 until h - 1) { total += 2; if (inside[y * w]) n++; if (inside[y * w + w - 1]) n++ }
        return n.toFloat() / total
    }

    /** Segi-empat yang praktis = persegi panjang sejajar bingkai (atau memenuhi bingkai): tak perlu perspektif. */
    private fun isTrivial(q: FloatArray, w: Int, h: Int): Boolean {
        val l = min(min(q[0], q[6]), min(q[2], q[4])); val r = max(max(q[0], q[6]), max(q[2], q[4]))
        val t = min(min(q[1], q[3]), min(q[5], q[7])); val b = max(max(q[1], q[3]), max(q[5], q[7]))
        val tx = 0.03f * w; val ty = 0.03f * h
        val aligned = abs(q[0] - l) < tx && abs(q[1] - t) < ty &&
            abs(q[2] - r) < tx && abs(q[3] - t) < ty &&
            abs(q[4] - r) < tx && abs(q[5] - b) < ty &&
            abs(q[6] - l) < tx && abs(q[7] - b) < ty
        return aligned
    }

    // ---------------- morfologi / region growing ----------------

    /** Penutupan abu-abu (dilasi lalu erosi, separabel): menghapus goresan teks gelap tipis di atas kertas terang. */
    private fun closeGray(g: IntArray, w: Int, h: Int, r: Int): IntArray {
        fun pass(src: IntArray, horiz: Boolean, takeMax: Boolean): IntArray {
            val o = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var v = if (takeMax) -1 else 256
                for (d in -r..r) {
                    val xx = if (horiz) x + d else x
                    val yy = if (horiz) y else y + d
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val q = src[yy * w + xx]
                    if (takeMax) { if (q > v) v = q } else { if (q < v) v = q }
                }
                o[y * w + x] = v
            }
            return o
        }
        val dil = pass(pass(g, true, true), false, true)
        return pass(pass(dil, true, false), false, false)
    }

    /** Kemiringan terang per piksel: tinggi di tepi kertas, ~0 di kertas halus. */
    private fun slope(c: IntArray, w: Int, h: Int): IntArray {
        val o = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            o[i] = max(abs(c[i + 1] - c[i - 1]), abs(c[i + w] - c[i - w])) / 2
        }
        return o
    }

    /** Tumbuhkan wilayah dari [seed] selama kemiringan <= [maxSlope]; null bila seed sendiri di tepi tajam. */
    private fun grow(c: IntArray, sl: IntArray, w: Int, h: Int, seed: Int, maxSlope: Int, queue: IntArray): BooleanArray? {
        if (sl[seed] > maxSlope) return null
        val tau = maxSlope * 3
        val mask = BooleanArray(w * h)
        var head = 0; var tail = 0
        queue[tail++] = seed; mask[seed] = true
        while (head < tail) {
            val p = queue[head++]
            val x = p % w; val y = p / w
            val v = c[p]
            if (x > 0 && !mask[p - 1] && sl[p - 1] <= maxSlope && abs(c[p - 1] - v) <= tau) { mask[p - 1] = true; queue[tail++] = p - 1 }
            if (x < w - 1 && !mask[p + 1] && sl[p + 1] <= maxSlope && abs(c[p + 1] - v) <= tau) { mask[p + 1] = true; queue[tail++] = p + 1 }
            if (y > 0 && !mask[p - w] && sl[p - w] <= maxSlope && abs(c[p - w] - v) <= tau) { mask[p - w] = true; queue[tail++] = p - w }
            if (y < h - 1 && !mask[p + w] && sl[p + w] <= maxSlope && abs(c[p + w] - v) <= tau) { mask[p + w] = true; queue[tail++] = p + w }
        }
        return mask
    }

    /** Isi lubang: [inside] = piksel wilayah + piksel yang tidak terhubung ke pinggir gambar. Mengembalikan jumlahnya. */
    private fun fillHoles(mask: BooleanArray, inside: BooleanArray, w: Int, h: Int, queue: IntArray): Int {
        val outside = BooleanArray(w * h)
        var head = 0; var tail = 0
        fun push(i: Int) { if (!mask[i] && !outside[i]) { outside[i] = true; queue[tail++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) {
            val p = queue[head++]
            val x = p % w; val y = p / w
            if (x > 0) push(p - 1); if (x < w - 1) push(p + 1)
            if (y > 0) push(p - w); if (y < h - 1) push(p + w)
        }
        var n = 0
        for (i in 0 until w * h) { val v = !outside[i]; inside[i] = v; if (v) n++ }
        return n
    }

    /** Cadangan kontras tinggi: ambang Otsu + komponen terhubung terbesar. */
    private fun otsuMask(g: IntArray, w: Int, h: Int, queue: IntArray): BooleanArray? {
        val hist = IntArray(256)
        for (i in 0 until w * h) hist[g[i].coerceIn(0, 255)]++
        val total = w * h
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var wB = 0; var sumB = 0L; var bestV = 0.0; var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > bestV) { bestV = v; thr = t }
        }
        var borderBright = 0; var borderTotal = 0
        fun cb(x: Int, y: Int) { borderTotal++; if (g[y * w + x] > thr) borderBright++ }
        for (x in 0 until w) { cb(x, 0); cb(x, h - 1) }
        for (y in 1 until h - 1) { cb(0, y); cb(w - 1, y) }
        val docIsBright = borderBright * 2 < borderTotal
        val fg = BooleanArray(w * h) { (g[it] > thr) == docIsBright }
        val seen = BooleanArray(w * h)
        var bestStart = -1; var bestSize = 0
        for (s in 0 until w * h) {
            if (!fg[s] || seen[s]) continue
            var head = 0; var tail = 0
            queue[tail++] = s; seen[s] = true
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                if (x > 0 && fg[p - 1] && !seen[p - 1]) { seen[p - 1] = true; queue[tail++] = p - 1 }
                if (x < w - 1 && fg[p + 1] && !seen[p + 1]) { seen[p + 1] = true; queue[tail++] = p + 1 }
                if (y > 0 && fg[p - w] && !seen[p - w]) { seen[p - w] = true; queue[tail++] = p - w }
                if (y < h - 1 && fg[p + w] && !seen[p + w]) { seen[p + w] = true; queue[tail++] = p + w }
            }
            if (tail > bestSize) { bestSize = tail; bestStart = s }
        }
        if (bestStart < 0) return null
        // Ulangi flood dari komponen terbesar untuk membuat maskernya.
        val mask = BooleanArray(w * h)
        var head = 0; var tail = 0
        queue[tail++] = bestStart; mask[bestStart] = true
        while (head < tail) {
            val p = queue[head++]
            val x = p % w; val y = p / w
            if (x > 0 && fg[p - 1] && !mask[p - 1]) { mask[p - 1] = true; queue[tail++] = p - 1 }
            if (x < w - 1 && fg[p + 1] && !mask[p + 1]) { mask[p + 1] = true; queue[tail++] = p + 1 }
            if (y > 0 && fg[p - w] && !mask[p - w]) { mask[p - w] = true; queue[tail++] = p - w }
            if (y < h - 1 && fg[p + w] && !mask[p + w]) { mask[p + w] = true; queue[tail++] = p + w }
        }
        return mask
    }

    // ---------------- geometri: convex hull -> segi-empat luas maksimum ----------------

    private class P(val x: Int, val y: Int)

    private fun cross(o: P, a: P, b: P): Long =
        (a.x - o.x).toLong() * (b.y - o.y) - (a.y - o.y).toLong() * (b.x - o.x)

    private fun hull(pts: List<P>): List<P> {
        val s = pts.sortedWith(compareBy<P>({ it.x }, { it.y }))
        if (s.size < 3) return s
        val lo = ArrayList<P>()
        for (p in s) { while (lo.size >= 2 && cross(lo[lo.size - 2], lo[lo.size - 1], p) <= 0) lo.removeAt(lo.size - 1); lo.add(p) }
        val up = ArrayList<P>()
        for (p in s.asReversed()) { while (up.size >= 2 && cross(up[up.size - 2], up[up.size - 1], p) <= 0) up.removeAt(up.size - 1); up.add(p) }
        lo.removeAt(lo.size - 1); up.removeAt(up.size - 1)
        return lo + up
    }

    /** Buang titik hull yang paling "tidak penting" (segitiga terkecil) sampai tersisa [target]: sudut sejati tetap. */
    private fun simplify(h: MutableList<P>, target: Int) {
        while (h.size > target) {
            var bi = 0; var ba = Long.MAX_VALUE
            for (i in h.indices) {
                val a = abs(cross(h[(i + h.size - 1) % h.size], h[i], h[(i + 1) % h.size]))
                if (a < ba) { ba = a; bi = i }
            }
            h.removeAt(bi)
        }
    }

    /** 4 sudut (urut KA, KanAtas, KanBawah, KiBawah; piksel) dari wilayah [inside], atau null. */
    private fun quadOf(inside: BooleanArray, w: Int, h: Int): FloatArray? {
        val pts = ArrayList<P>()
        for (y in 0 until h) {
            var l = -1; var r = -1
            for (x in 0 until w) if (inside[y * w + x]) { if (l < 0) l = x; r = x }
            if (l < 0) continue
            pts.add(P(l, y)); pts.add(P(r + 1, y)); pts.add(P(l, y + 1)); pts.add(P(r + 1, y + 1))
        }
        if (pts.size < 8) return null
        val hv = hull(pts).toMutableList()
        if (hv.size < 4) return null
        simplify(hv, 40)
        val m = hv.size

        var bi = -1; var bj = -1; var bk = -1; var bl = -1; var bt = -1L
        for (i in 0 until m) for (k in i + 2 until m) {
            var j1 = -1; var a1 = -1L
            for (j in i + 1 until k) { val a = abs(cross(hv[i], hv[j], hv[k])); if (a > a1) { a1 = a; j1 = j } }
            var l1 = -1; var a2 = -1L
            for (l in k + 1 until m) { val a = abs(cross(hv[k], hv[l], hv[i])); if (a > a2) { a2 = a; l1 = l } }
            for (l in 0 until i) { val a = abs(cross(hv[k], hv[l], hv[i])); if (a > a2) { a2 = a; l1 = l } }
            if (j1 < 0 || l1 < 0) continue
            if (a1 + a2 > bt) { bt = a1 + a2; bi = i; bj = j1; bk = k; bl = l1 }
        }
        if (bi < 0) return null
        val c = listOf(hv[bi], hv[bj], hv[bk], hv[bl])

        // Urutkan searah jarum jam di layar lalu mulai dari sudut kiri-atas (x+y terkecil).
        val cx = c.sumOf { it.x } / 4.0; val cy = c.sumOf { it.y } / 4.0
        val cw = c.sortedBy { atan2(it.y - cy, it.x - cx) }
        var s = 0
        for (i in 1 until 4) if (cw[i].x + cw[i].y < cw[s].x + cw[s].y) s = i
        val o = FloatArray(8)
        for (i in 0 until 4) { val p = cw[(s + i) % 4]; o[2 * i] = p.x.toFloat(); o[2 * i + 1] = p.y.toFloat() }
        return if (validQuad(o, w, h)) o else null
    }

    private fun validQuad(q: FloatArray, w: Int, h: Int): Boolean {
        val minEdge = 0.10f * max(w, h)
        for (i in 0 until 4) {
            val ax = q[2 * i]; val ay = q[2 * i + 1]
            val bx = q[2 * ((i + 1) % 4)]; val by = q[2 * ((i + 1) % 4) + 1]
            val px = q[2 * ((i + 3) % 4)]; val py = q[2 * ((i + 3) % 4) + 1]
            val e1x = bx - ax; val e1y = by - ay; val e2x = px - ax; val e2y = py - ay
            val l1 = sqrt(e1x * e1x + e1y * e1y); val l2 = sqrt(e2x * e2x + e2y * e2y)
            if (l1 < minEdge || l2 < minEdge) return false
            val cos = ((e1x * e2x + e1y * e2y) / (l1 * l2)).coerceIn(-1f, 1f)
            val deg = Math.toDegrees(acos(cos).toDouble())
            if (deg < 50.0 || deg > 130.0) return false
        }
        return true
    }

    private fun polyArea(q: FloatArray): Float {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
        }
        return abs(s) / 2f
    }
}
