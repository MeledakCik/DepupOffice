package cloud.depup.office

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deteksi dokumen tanpa library tambahan (murni Kotlin):
 *  - [detectCrop]  : segmentasi kertas (ambang Otsu + komponen terbesar) lalu tepi dipertajam dengan gradien Sobel.
 *  - [detectSkew]  : kemiringan teks lewat profil proyeksi (baris teks paling "tajam" = lurus).
 */
object ScanAi {
    private const val SIDE = 320

    private class Small(val w: Int, val h: Int, val g: IntArray)

    private fun small(src: Bitmap): Small {
        val s = SIDE.toFloat() / max(src.width, src.height)
        val w = max(8, (src.width * min(1f, s)).toInt())
        val h = max(8, (src.height * min(1f, s)).toInt())
        val bm = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        if (bm !== src) bm.recycle()
        val g = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            g[i] = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
        }
        return Small(w, h, blur(g, w, h))
    }

    /** Blur kotak 3x3 sederhana untuk meredam noise sebelum ambang / Sobel. */
    private fun blur(g: IntArray, w: Int, h: Int): IntArray {
        val o = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var n = 0
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    sum += g[yy * w + xx]; n++
                }
            }
            o[y * w + x] = sum / n
        }
        return o
    }

    private fun otsu(g: IntArray): Int {
        val hist = IntArray(256)
        for (v in g) hist[v]++
        val total = g.size
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var wB = 0; var sumB = 0L
        var best = 0.0; var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; thr = t }
        }
        return thr
    }

    /** Kotak dokumen sebagai pecahan 0..1, atau null bila tidak yakin (biarkan potong penuh). */
    fun detectCrop(src: Bitmap): RectF? {
        val s = small(src)
        val w = s.w; val h = s.h
        val thr = otsu(s.g)

        // Latar = kelas piksel yang paling banyak menyentuh pinggir gambar.
        var borderBright = 0; var borderTotal = 0
        fun countBorder(x: Int, y: Int) { borderTotal++; if (s.g[y * w + x] > thr) borderBright++ }
        for (x in 0 until w) { countBorder(x, 0); countBorder(x, h - 1) }
        for (y in 1 until h - 1) { countBorder(0, y); countBorder(w - 1, y) }
        val docIsBright = borderBright * 2 < borderTotal   // pinggir gelap -> kertas terang

        val mask = BooleanArray(w * h) { (s.g[it] > thr) == docIsBright }

        // Komponen terhubung terbesar (BFS iteratif, 4-arah).
        val label = IntArray(w * h)
        var bestId = 0; var bestSize = 0; var id = 0
        val queue = IntArray(w * h)
        for (i in mask.indices) {
            if (!mask[i] || label[i] != 0) continue
            id++
            var head = 0; var tail = 0
            queue[tail++] = i; label[i] = id
            var size = 0
            while (head < tail) {
                val p = queue[head++]; size++
                val x = p % w; val y = p / w
                if (x > 0 && mask[p - 1] && label[p - 1] == 0) { label[p - 1] = id; queue[tail++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && label[p + 1] == 0) { label[p + 1] = id; queue[tail++] = p + 1 }
                if (y > 0 && mask[p - w] && label[p - w] == 0) { label[p - w] = id; queue[tail++] = p - w }
                if (y < h - 1 && mask[p + w] && label[p + w] == 0) { label[p + w] = id; queue[tail++] = p + w }
            }
            if (size > bestSize) { bestSize = size; bestId = id }
        }
        if (bestId == 0) return null
        var l = w; var t = h; var r = 0; var b = 0
        for (i in label.indices) if (label[i] == bestId) {
            val x = i % w; val y = i / w
            if (x < l) l = x; if (x > r) r = x
            if (y < t) t = y; if (y > b) b = y
        }
        val area = (r - l + 1).toFloat() * (b - t + 1) / (w * h)
        if (area < 0.15f) return null          // terlalu kecil: kemungkinan bukan dokumen
        if (area > 0.97f) return RectF(0f, 0f, 1f, 1f)   // sudah memenuhi bingkai

        // Pertajam tepi: cari garis gradien (Sobel) terkuat di sekitar tiap sisi.
        val gx = IntArray(w * h); val gy = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            gx[i] = abs(-s.g[i - w - 1] + s.g[i - w + 1] - 2 * s.g[i - 1] + 2 * s.g[i + 1] - s.g[i + w - 1] + s.g[i + w + 1])
            gy[i] = abs(-s.g[i - w - 1] - 2 * s.g[i - w] - s.g[i - w + 1] + s.g[i + w - 1] + 2 * s.g[i + w] + s.g[i + w + 1])
        }
        val win = max(3, (max(w, h) * 0.04f).toInt())
        fun refineX(x0: Int): Int {
            var best = x0; var bs = -1L
            for (x in max(1, x0 - win)..min(w - 2, x0 + win)) {
                var sum = 0L
                for (y in t..b) sum += gx[y * w + x]
                if (sum > bs) { bs = sum; best = x }
            }
            return best
        }
        fun refineY(y0: Int): Int {
            var best = y0; var bs = -1L
            for (y in max(1, y0 - win)..min(h - 2, y0 + win)) {
                var sum = 0L
                for (x in l..r) sum += gy[y * w + x]
                if (sum > bs) { bs = sum; best = y }
            }
            return best
        }
        val rl = refineX(l); val rr = refineX(r); val rt = refineY(t); val rb = refineY(b)
        if (rr - rl < w * 0.2f || rb - rt < h * 0.2f) return RectF(l.toFloat() / w, t.toFloat() / h, (r + 1f) / w, (b + 1f) / h)

        val pad = 0.004f
        return RectF(
            (rl.toFloat() / w - pad).coerceIn(0f, 1f), (rt.toFloat() / h - pad).coerceIn(0f, 1f),
            ((rr + 1f) / w + pad).coerceIn(0f, 1f), ((rb + 1f) / h + pad).coerceIn(0f, 1f)
        )
    }

    /**
     * Sudut (derajat) yang harus dipakai pada Matrix.postRotate agar teks jadi lurus. Rentang -10..+10.
     * [area] = pecahan area dokumen (null = seluruh gambar).
     */
    fun detectSkew(src: Bitmap, area: RectF?): Float {
        val s = small(src)
        val w = s.w; val h = s.h
        val a = area ?: RectF(0f, 0f, 1f, 1f)
        val insetX = (a.right - a.left) * 0.04f; val insetY = (a.bottom - a.top) * 0.04f
        val x0 = ((a.left + insetX) * w).toInt().coerceIn(0, w - 1)
        val x1 = ((a.right - insetX) * w).toInt().coerceIn(x0 + 1, w)
        val y0 = ((a.top + insetY) * h).toInt().coerceIn(0, h - 1)
        val y1 = ((a.bottom - insetY) * h).toInt().coerceIn(y0 + 1, h)

        var sum = 0L; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { sum += s.g[y * w + x]; n++ }
        if (n < 400) return 0f
        val mean = (sum / n).toInt()
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        val limit = mean * 80 / 100          // piksel teks = jauh lebih gelap dari rata-rata kertas
        for (y in y0 until y1) for (x in x0 until x1) if (s.g[y * w + x] < limit) { xs.add(x); ys.add(y) }
        if (xs.size < 150 || xs.size > n * 0.5f) return 0f   // terlalu sedikit teks / bukan halaman teks
        val step = max(1, xs.size / 20000)

        val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f
        val diag = sqrt((w * w + h * h).toFloat())
        val bins = (diag * 1.2f).toInt() + 4
        val hist = IntArray(bins)

        fun score(deg: Float): Double {
            java.util.Arrays.fill(hist, 0)
            val rad = deg * PI / 180.0
            val sn = sin(rad).toFloat(); val cs = cos(rad).toFloat()
            var i = 0
            while (i < xs.size) {
                val dx = xs[i] - cx; val dy = ys[i] - cy
                val v = (-dx * sn + dy * cs + diag * 0.6f).toInt()
                if (v in 0 until bins) hist[v]++
                i += step
            }
            var sq = 0.0
            for (c in hist) sq += c.toDouble() * c
            return sq
        }

        val base = score(0f)
        var bestDeg = 0f; var best = base
        var d = -10f
        while (d <= 10f) { val sc = score(d); if (sc > best) { best = sc; bestDeg = d }; d += 0.5f }
        var f = bestDeg - 0.5f
        val fEnd = bestDeg + 0.5f
        while (f <= fEnd) { val sc = score(f); if (sc > best) { best = sc; bestDeg = f }; f += 0.1f }
        if (best < base * 1.03 || abs(bestDeg) < 0.3f) return 0f
        // bestDeg = kemiringan garis teks (searah jarum jam di layar); untuk meluruskan putar berlawanan.
        return -bestDeg
    }
}
