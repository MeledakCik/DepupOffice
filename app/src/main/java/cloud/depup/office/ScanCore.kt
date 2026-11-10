package cloud.depup.office

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Inti deteksi dokumen (murni Kotlin, tanpa Android — diuji di JVM):
 *  - [detectCrop]  : segmentasi kertas (ambang Otsu + komponen terbesar) lalu tepi dipertajam dengan gradien Sobel.
 *  - [detectSkew]  : kemiringan teks lewat profil proyeksi (baris teks paling "tajam" = lurus).
 */
object ScanCore {
    private const val SIDE = 320

    /** Gambar kecil (±320 px): abu-abu ter-blur + warna asli (ARGB) untuk deteksi berbasis warna kertas. */
    class Small(val w: Int, val h: Int, val g: IntArray, val argb: IntArray)

    const val SIDE_PX = SIDE

    /** [px] = piksel ARGB gambar yang SUDAH diperkecil (sisi terpanjang ±[SIDE_PX]). */
    fun fromPixels(px: IntArray, w: Int, h: Int): Small {
        val g = IntArray(w * h)
        for (i in 0 until w * h) {
            val c = px[i]
            g[i] = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
        }
        return Small(w, h, blur(g, w, h), px)
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

    // ---------- util morfologi / flood fill (semua di gambar kecil ~320px) ----------

    /** Penutupan abu-abu (dilasi lalu erosi, separabel): menghapus goresan teks gelap tipis di atas kertas terang. */
    internal fun closeGray(g: IntArray, w: Int, h: Int, r: Int): IntArray {
        fun pass(src: IntArray, horiz: Boolean, max: Boolean): IntArray {
            val o = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var v = if (max) -1 else 256
                for (d in -r..r) {
                    val xx = if (horiz) x + d else x
                    val yy = if (horiz) y else y + d
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val q = src[yy * w + xx]
                    if (max) { if (q > v) v = q } else { if (q < v) v = q }
                }
                o[y * w + x] = v
            }
            return o
        }
        val dil = pass(pass(g, true, true), false, true)
        return pass(pass(dil, true, false), false, false)
    }


    /** Kemiringan terang per piksel (maks selisih tengah horizontal/vertikal / 2): tinggi di tepi kertas, ~0 di kertas halus. */
    private fun slope(c: IntArray, w: Int, h: Int): IntArray {
        val o = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            o[i] = max(abs(c[i + 1] - c[i - 1]), abs(c[i + w] - c[i - w])) / 2
        }
        return o
    }

    private class Region(val l: Int, val t: Int, val r: Int, val b: Int, val filled: Int)

    /**
     * Tumbuhkan wilayah dari [seed] selama kemiringan terang piksel <= [maxSlope]. Tepi kertas = "dinding"
     * kemiringan tajam (tidak bisa dirambati walau tepinya miring/antialias), bayangan & gradasi halus lolos.
     * Lubang di dalam wilayah (mis. blok teks) diisi.
     */
    private fun grow(c: IntArray, sl: IntArray, w: Int, h: Int, seed: Int, maxSlope: Int, queue: IntArray): Region? {
        val tau = maxSlope * 3
        val mask = BooleanArray(w * h)
        if (sl[seed] > maxSlope) return null
        var head = 0; var tail = 0
        queue[tail++] = seed; mask[seed] = true
        var l = w; var t = h; var r = 0; var b = 0
        while (head < tail) {
            val p = queue[head++]
            val x = p % w; val y = p / w
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            val v = c[p]
            if (x > 0 && !mask[p - 1] && sl[p - 1] <= maxSlope && abs(c[p - 1] - v) <= tau) { mask[p - 1] = true; queue[tail++] = p - 1 }
            if (x < w - 1 && !mask[p + 1] && sl[p + 1] <= maxSlope && abs(c[p + 1] - v) <= tau) { mask[p + 1] = true; queue[tail++] = p + 1 }
            if (y > 0 && !mask[p - w] && sl[p - w] <= maxSlope && abs(c[p - w] - v) <= tau) { mask[p - w] = true; queue[tail++] = p - w }
            if (y < h - 1 && !mask[p + w] && sl[p + w] <= maxSlope && abs(c[p + w] - v) <= tau) { mask[p + w] = true; queue[tail++] = p + w }
        }
        // Isi lubang: piksel bukan-wilayah yang tidak terhubung ke pinggir gambar.
        val outside = BooleanArray(w * h)
        head = 0; tail = 0
        fun push(i: Int) { if (!mask[i] && !outside[i]) { outside[i] = true; queue[tail++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        var outCount = 0
        while (head < tail) {
            val p = queue[head++]; outCount++
            val x = p % w; val y = p / w
            if (x > 0) push(p - 1); if (x < w - 1) push(p + 1)
            if (y > 0) push(p - w); if (y < h - 1) push(p + w)
        }
        return Region(l, t, r, b, w * h - outCount)
    }

    /** Kotak terbaik (l,t,r,b dalam piksel gambar kecil) via region-growing, atau null. */
    private fun growBox(c: IntArray, w: Int, h: Int): IntArray? {
        val queue = IntArray(w * h + 8)
        val sl = slope(c, w, h)
        val seeds = arrayOf(0.5f to 0.5f, 0.38f to 0.38f, 0.62f to 0.38f, 0.38f to 0.62f, 0.62f to 0.62f)
        var best: Region? = null; var bestScore = 0f
        for ((fx, fy) in seeds) {
            val seed = (fy * h).toInt().coerceIn(0, h - 1) * w + (fx * w).toInt().coerceIn(0, w - 1)
            for (ms in intArrayOf(10, 6, 4)) {
                val rg = grow(c, sl, w, h, seed, ms, queue) ?: continue
                val bw = rg.r - rg.l + 1; val bh = rg.b - rg.t + 1
                val area = rg.filled.toFloat() / (w * h)
                val rect = rg.filled.toFloat() / (bw.toFloat() * bh)
                        // area ~1 = wilayah bocor menelan seluruh frame (tanpa tepi yang terdeteksi): bukan dokumen.
                if (area < 0.15f || area > 0.985f || rect < 0.62f) continue
                val score = rect * rect * area
                if (score > bestScore) { bestScore = score; best = rg }
            }
        }
        return best?.let { intArrayOf(it.l, it.t, it.r, it.b) }
    }

    /** Cadangan: ambang Otsu + komponen terhubung terbesar (cocok untuk kontras tinggi). */
    private fun otsuBox(s: Small): IntArray? {
        val w = s.w; val h = s.h
        val thr = otsu(s.g)
        var borderBright = 0; var borderTotal = 0
        fun countBorder(x: Int, y: Int) { borderTotal++; if (s.g[y * w + x] > thr) borderBright++ }
        for (x in 0 until w) { countBorder(x, 0); countBorder(x, h - 1) }
        for (y in 1 until h - 1) { countBorder(0, y); countBorder(w - 1, y) }
        val docIsBright = borderBright * 2 < borderTotal
        val mask = BooleanArray(w * h) { (s.g[it] > thr) == docIsBright }
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
        return intArrayOf(l, t, r, b)
    }

    /** Kotak dokumen sebagai pecahan 0..1, atau null bila tidak yakin (biarkan potong penuh). */
    fun detectCrop(s: Small): FloatArray? {
        val w = s.w; val h = s.h
        val closed = closeGray(s.g, w, h, 3)

        val box0 = growBox(closed, w, h) ?: otsuBox(s) ?: return null
        // Buang sisi yang ternyata benda gelap / meja / tangan (bukan kertas) sebelum menghitung luas & menajamkan tepi.
        val box = CropTrim.trimDark(closed, w, h, box0)
        val l = box[0]; val t = box[1]; val r = box[2]; val b = box[3]
        val area = (r - l + 1).toFloat() * (b - t + 1) / (w * h)
        if (area < 0.15f) return null          // terlalu kecil: kemungkinan bukan dokumen
        if (area > 0.97f) return floatArrayOf(0f, 0f, 1f, 1f)   // sudah memenuhi bingkai

        // Pertajam tepi pada gambar yang sudah bebas teks: gradien Sobel terkuat dekat tiap sisi,
        // dengan bias ke posisi awal supaya tidak "lompat" ke garis lain.
        val g = closed
        val gx = IntArray(w * h); val gy = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            gx[i] = abs(-g[i - w - 1] + g[i - w + 1] - 2 * g[i - 1] + 2 * g[i + 1] - g[i + w - 1] + g[i + w + 1])
            gy[i] = abs(-g[i - w - 1] - 2 * g[i - w] - g[i - w + 1] + g[i + w - 1] + 2 * g[i + w] + g[i + w + 1])
        }
        val win = max(3, (max(w, h) * 0.025f).toInt())
        fun refineX(x0: Int): Int {
            var best = x0; var bs = -1.0
            for (x in max(1, x0 - win)..min(w - 2, x0 + win)) {
                var sum = 0L
                for (y in t..b) sum += gx[y * w + x]
                val sc = sum * (1.0 - 0.4 * abs(x - x0) / win)
                if (sc > bs) { bs = sc; best = x }
            }
            return best
        }
        fun refineY(y0: Int): Int {
            var best = y0; var bs = -1.0
            for (y in max(1, y0 - win)..min(h - 2, y0 + win)) {
                var sum = 0L
                for (x in l..r) sum += gy[y * w + x]
                val sc = sum * (1.0 - 0.4 * abs(y - y0) / win)
                if (sc > bs) { bs = sc; best = y }
            }
            return best
        }
        val rl = refineX(l); val rr = refineX(r); val rt = refineY(t); val rb = refineY(b)
        if (rr - rl < w * 0.2f || rb - rt < h * 0.2f) return floatArrayOf(l.toFloat() / w, t.toFloat() / h, (r + 1f) / w, (b + 1f) / h)

        val pad = 0.006f
        return floatArrayOf(
            (rl.toFloat() / w - pad).coerceIn(0f, 1f), (rt.toFloat() / h - pad).coerceIn(0f, 1f),
            ((rr + 1f) / w + pad).coerceIn(0f, 1f), ((rb + 1f) / h + pad).coerceIn(0f, 1f)
        )
    }

    /**
     * 4 sudut dokumen (KiriAtas, KananAtas, KananBawah, KiriBawah) sebagai pecahan 0..1 [x0,y0,...,x3,y3] untuk
     * koreksi perspektif, atau null bila tidak yakin / dokumen sudah berupa persegi panjang sejajar bingkai
     * (jalur [detectCrop] yang menangani). Logika ada di [DocQuad] (murni Kotlin, diuji di JVM).
     */
    fun detectQuad(s: Small): FloatArray? {
        lastSplit = false
        // Garis tepi (Hough) dulu: mengikuti tepi kertas yang miring/berperspektif; wilayah-tumbuh (DocQuad) sebagai cadangan.
        var q: FloatArray? = LineQuad.detect(s.g, s.w, s.h) ?: DocQuad.detect(s.g, s.w, s.h)
        var fromRect = false
        if (q == null) {
            // Dokumen sejajar bingkai: pakai kotak potong biasa sebagai segi-empat, supaya tetap bisa dicek 2 halaman.
            val r = detectCrop(s) ?: return null
            q = floatArrayOf(
                r[0] * s.w, r[1] * s.h, r[2] * s.w, r[1] * s.h,
                r[2] * s.w, r[3] * s.h, r[0] * s.w, r[3] * s.h
            )
            fromRect = true
        }
        // Buku terbuka 2 halaman: bila satu sisi kosong, ambil sisi yang berisi saja.
        val split = try { SpreadSplit.apply(s.g, s.w, s.h, q) } catch (_: Throwable) { null }
        val out: FloatArray
        if (split != null) { out = split; lastSplit = true }
        else if (fromRect) return null          // tidak ada yang perlu diluruskan: biar jalur potong kotak yang bekerja
        else out = q
        val ins = inset(out, 0.005f)
        return FloatArray(8) { if (it % 2 == 0) ins[it] / s.w else ins[it] / s.h }
    }

    /** true bila deteksi terakhir memilih 1 dari 2 halaman (untuk pemberitahuan ke pengguna). */
    @Volatile var lastSplit = false

    /** Geser tiap sudut [f] (pecahan) ke arah pusat: membuang pinggir kertas yang bergradasi gelap / bayangan tepi. */
    private fun inset(q: FloatArray, f: Float): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        return FloatArray(8) { i -> if (i % 2 == 0) q[i] + (cx - q[i]) * f * 2f else q[i] + (cy - q[i]) * f * 2f }
    }

    /**
     * Deteksi dokumen terpadu untuk "AI potong": 4 sudut pecahan 0..1 (KA, KaA, KaB, KiB) atau null.
     * Memakai [DocFind] (banyak kandidat + penilaian). Bila tidak yakin: null (titik sudut dibiarkan untuk diatur
     * manual) — lebih baik daripada memasang potongan asal-asalan.
     * Buku terbuka 2 halaman tetap dicek ([lastSplit]).
     */
    fun findDocument(s0: Small): FloatArray? {
        lastSplit = false
        val s = normalized(s0)
        val r = try { DocFind.find(s.g, s.argb, s.w, s.h) } catch (_: Throwable) { null }
        if (r != null && r.confident) {
            var q = r.quad
            // lipatan bisa tegak ATAU mendatar (foto buku diambil menyamping): coba juga dengan sudut digeser satu
            val split = try {
                SpreadSplit.apply(s.g, s.w, s.h, q)
                    ?: SpreadSplit.apply(s.g, s.w, s.h, FloatArray(8) { q[(it + 2) % 8] })
            } catch (_: Throwable) { null }
            if (split != null) { q = split; lastSplit = true }
            // jalur permukaan lain (bodi laptop, halaman sebelah) yang masih ikut di pinggir: tarik ke tepi kertas
            q = DocFind.trimToPaper(s.g, s.argb, s.w, s.h, q)
            return FloatArray(8) { if (it % 2 == 0) (q[it] / s.w).coerceIn(0f, 1f) else (q[it] / s.h).coerceIn(0f, 1f) }
        }
        return null
    }

    /**
     * Foto redup (malam, lampu kamar, bayangan badan): seluruh gambar dicerahkan dulu supaya kertas kembali "putih"
     * (persentil-98 → ±235). Ambang-ambang deteksi (kertas terang, benda gelap, tinta) jadi berlaku seperti di foto
     * yang terang. Foto yang sudah cukup terang tidak diubah.
     */
    private fun normalized(s: Small): Small {
        val hist = IntArray(256)
        for (v in s.g) hist[v.coerceIn(0, 255)]++
        var acc = 0; var p98 = 255
        val lim = (s.g.size * 0.02f).toInt()
        for (t in 255 downTo 0) { acc += hist[t]; if (acc >= lim) { p98 = t; break } }
        if (p98 >= 200 || p98 < 20) return s
        val gain = (235f / p98).coerceAtMost(3.5f)
        val px = IntArray(s.argb.size) { i ->
            val c = s.argb[i]
            val r = (((c shr 16) and 255) * gain).toInt().coerceAtMost(255)
            val g = (((c shr 8) and 255) * gain).toInt().coerceAtMost(255)
            val b = ((c and 255) * gain).toInt().coerceAtMost(255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return fromPixels(px, s.w, s.h)
    }

    /** Nilai keyakinan deteksi terakhir (untuk uji). */
    internal fun debugScore(s: Small): Float = DocFind.find(s.g, s.argb, s.w, s.h)?.score ?: 0f
}
