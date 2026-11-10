package cloud.depup.office

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pencari dokumen "AI potong" generasi baru (murni Kotlin, diuji di JVM).
 *
 * Masalah alur lama: tiap detektor hanya mengembalikan SATU tebakan dan menolak apa pun < 15% luas foto, sehingga struk
 * kecil di atas meja kalah oleh tepi meja/lantai yang lebih besar. Di sini semua kemungkinan dikumpulkan dulu:
 *  1. segi-empat garis tepi (Hough) dari [LineQuad],
 *  2. wilayah-tumbuh dari [DocQuad],
 *  3. komponen wilayah "halus" (tanpa tepi tajam) di seluruh foto — menangkap kertas kecil di mana pun letaknya,
 *  4. komponen kertas (terang & tidak berwarna) dan komponen ambang Otsu,
 * lalu masing-masing DINILAI: seberapa kuat keempat sisinya berupa tepi warna yang nyata, seberapa seragam isinya
 * (kertas polos + teks, bukan campuran meja + lantai), seberapa mirip kertas, dan ukurannya. Pemenang dirapikan
 * dengan menggeser tiap sisi ke tepi terkuat di dekatnya.
 */
object DocFind {

    class Result(val quad: FloatArray, val score: Float, val edge: Float, val dark: Boolean) {
        /**
         * Cukup yakin untuk dipakai: nilai total tinggi, ATAU keempat tepinya jelas meski kontras/isinya lemah
         * (kertas putih di lantai putih). Benda gelap (ponsel) hanya lolos lewat nilai total.
         */
        val confident get() = (score >= MIN_SCORE && edge >= 0.55f) || (!dark && edge >= 0.7f && score >= 0.03f)
    }

    private class Scored(val q: FloatArray, val s: Float, val edge: Float, val area: Float, val dark: Boolean)

    private var lastEdge = 0f
    private var lastDark = false
    private const val BORDER = 0.55f
    @JvmStatic var INSET = 0.5f

    private fun pairPage(img: Img, win: Scored, scored: List<Scored>): Scored? {
        val w = img.w; val h = img.h
        var best: Scored? = null; var bestV = 0f
        for (c in scored) {
            if (c === win || c.s < win.s * rr(0.4f) || c.edge < 0.6f) continue
            if (c.area < win.area * 0.45f || c.area > win.area * 2.2f) continue
            // tidak saling tumpuk banyak
            val mask = BooleanArray(w * h)
            var inA = 0; var inB = 0; var both = 0
            val b1 = Quad.bbox(win.q); val b2 = Quad.bbox(c.q)
            val bb = floatArrayOf(min(b1[0], b2[0]), min(b1[1], b2[1]), max(b1[2], b2[2]), max(b1[3], b2[3]))
            for (y in max(0, bb[1].toInt()) until min(h, bb[3].toInt() + 1)) for (x in max(0, bb[0].toInt()) until min(w, bb[2].toInt() + 1)) {
                val a = inside(win.q, x + 0.5f, y + 0.5f); val b = inside(c.q, x + 0.5f, y + 0.5f)
                if (a) inA++; if (b) inB++; if (a && b) both++
                if (a || b) mask[y * w + x] = true
            }
            if (both > 0.15f * min(inA, inB)) continue
            val u = DocQuad.quadFromMask(mask, w, h) ?: continue
            val ua = polyArea(u)
            debugParts?.invoke("PAIR? c=${c.q.joinToString(",") { it.toInt().toString() }} ua=$ua sum=${inA + inB - both} paper=${extraIsPaper(img, u, win.q)}")
            // berdampingan rapat: gabungan hampir sama dengan jumlah luas keduanya (celah punggung buku kecil)
            if (ua > (inA + inB - both) * 1.15f) continue
            if (!extraIsPaper(img, u, win.q)) continue
            val v = c.s
            if (v > bestV) { bestV = v; best = Scored(Quad.order(u), win.s, max(win.edge, c.edge), ua / (w * h).toFloat(), win.dark) }
        }
        return best
    }

    private fun polyArea(q: FloatArray): Float {
        var a = 0f
        for (k in 0 until 4) { val j = (k + 1) % 4; a += q[2 * k] * q[2 * j + 1] - q[2 * j] * q[2 * k + 1] }
        return abs(a) / 2f
    }

    /** Wilayah [outer] di luar [inner] berwarna & seterang kertas di dalam [inner] (bukan keyboard / meja / latar). */
    private fun extraIsPaper(img: Img, outer: FloatArray, inner: FloatArray): Boolean {
        val w = img.w; val h = img.h
        fun stats(q: FloatArray, excl: FloatArray?): FloatArray? {
            val bb = Quad.bbox(q)
            val ex = excl?.let { grow(it, 2f) }
            val st = max(1f, max(bb[2] - bb[0], bb[3] - bb[1]) / 50f)
            val idx = ArrayList<Int>()
            var y = bb[1]
            while (y <= bb[3]) {
                var x = bb[0]
                while (x <= bb[2]) {
                    val xi = x.toInt(); val yi = y.toInt()
                    if (xi in 0 until w && yi in 0 until h && inside(q, x, y) && (ex == null || !inside(ex, x, y))) idx.add(yi * w + xi)
                    x += st
                }
                y += st
            }
            if (idx.size < 20) return null
            val g = idx.map { img.c4[it] }.sorted()
            val n = g.size.toFloat()
            val p90 = g[g.size * 9 / 10]
            val dark = g.count { it < p90 - 60 } / n
            // warna KERTAS saja (titik seterang kertas): tulisan tangan / tinta tidak menggeser rata-rata
            var r = 0f; var gg = 0f; var b = 0f; var m = 0
            for (i in idx) if (img.c4[i] >= p90 - 8) { r += img.cr[i]; gg += img.cg[i]; b += img.cb[i]; m++ }
            if (m == 0) return null
            return floatArrayOf(p90.toFloat(), g[g.size / 2].toFloat(), r / m, gg / m, b / m, dark)
        }
        val a = stats(inner, null) ?: return false
        val e = stats(outer, inner) ?: return false
        val cd = sqrt((a[2] - e[2]) * (a[2] - e[2]) + (a[3] - e[3]) * (a[3] - e[3]) + (a[4] - e[4]) * (a[4] - e[4]))
        debugParts?.invoke("EXTRAPAPER p=${a[0]}/${e[0]} med=${a[1]}/${e[1]} cd=$cd dark=${e[5]}")
        return abs(a[0] - e[0]) <= 22f && e[5] < 0.2f && cd <= 35f
    }

    /** Keempat sudut [inner] berada di dalam [outer] (dengan toleransi [tol] piksel). */
    private fun contains(outer: FloatArray, inner: FloatArray, tol: Float): Boolean {
        val g = grow(outer, tol)
        for (k in 0 until 4) if (!inside(g, inner[2 * k], inner[2 * k + 1])) return false
        return true
    }

    /** Nilai minimum supaya hasil dipercaya (di bawah ini kembali ke alur lama). */
    const val MIN_SCORE = 0.10f

    /**
     * Bobot penilaian kandidat. Nilai di sini HASIL PELATIHAN: pencarian bobot (coordinate descent, 6 titik awal)
     * pada ±390 foto sintetis beragam latar, jenis dokumen, cahaya & sudut kamera, lalu dipilih yang terbaik pada
     * 120 foto uji terpisah yang tidak ikut dilatih. [0] pangkat tepi, [1] bobot sisi terlemah, [2] bobot keseragaman isi,
     * [3] bobot "mirip kertas", [4] pangkat penalti benda gelap, [5] bobot kontras, [6] pangkat luas,
     * [7] penalti jauh dari tengah, [8] penalti 2 sisi menempel bingkai.
     */
    @JvmField val W = floatArrayOf(1.07f, 0.25f, 0.045f, 0.569f, 1f, 0f, 0.25f, 0.816f, 0.772f)

    /**
     * Ambang perbandingan nilai antar-kandidat dirancang untuk skala lama (tepi pangkat 2). Setelah bobot dilatih,
     * skala nilai berubah; [rr] menerjemahkan ambang lama ke skala baru supaya aturannya tetap sama ketatnya.
     */
    private fun rr(r: Float): Float = Math.pow(r.toDouble(), (W[0] / 2f).toDouble()).toFloat()

    private fun combine(edge: Float, homog: Float, paperF: Float, darkF: Float, contrast: Float, area: Float, centerF: Float): Float =
        Math.pow(edge.toDouble(), W[0].toDouble()).toFloat() * (1f - W[2] + W[2] * homog) * (1f - W[3] + W[3] * paperF) *
            Math.pow(darkF.toDouble(), W[4].toDouble()).toFloat() * (1f - W[5] + W[5] * contrast) *
            Math.pow(area.toDouble(), W[6].toDouble()).toFloat() * centerF

    /** Kait uji: fitur mentah tiap kandidat [mn, mean, nb, homog, paperF, median, kontras, luas, jarak-tengah]. */
    @JvmStatic var debugFeat: ((FloatArray, FloatArray) -> Unit)? = null

    /** Kait untuk uji JVM: dipanggil untuk tiap kandidat beserta nilainya. */
    @JvmStatic var debug: ((FloatArray, Float) -> Unit)? = null
    @JvmStatic var debugParts: ((String) -> Unit)? = null

    /** r/gr/b = warna ter-blur; cr/cg/cb = warna yang teks/garis gelap tipisnya sudah "ditutup" (untuk menilai tepi kertas). */
    private class Img(
        val w: Int, val h: Int, val g: IntArray, val r: IntArray, val gr: IntArray, val b: IntArray, val closed: IntArray,
        val cr: IntArray, val cg: IntArray, val cb: IntArray,
        /** abu-abu dengan penutupan lebar: teks (dan garis tipis) hilang total — untuk tepi kertas yang samar */
        val c4: IntArray,
        /** minimum lokal (5x5) abu-abu: menandai tinta/teks di dekat titik */
        val mn: IntArray
    )

    /** [g] abu-abu ter-blur, [argb] warna asli; keduanya [w]x[h]. Kembalikan segi-empat piksel (KA, KaA, KaB, KiB). */
    @Synchronized
    fun find(g: IntArray, argb: IntArray, w: Int, h: Int): Result? {
        if (w < 32 || h < 32) return null
        val img = prepare(g, argb, w, h)
        val cands = ArrayList<FloatArray>()
        val minA = 0.012f
        try { cands.addAll(LineQuad.candidates(g, w, h, minA, 40)) } catch (_: Throwable) {}
        // versi tanpa teks: baris-baris tulisan tidak lagi "merebut" garis Hough dari tepi kertas
        try { cands.addAll(LineQuad.candidates(ScanCore.closeGray(g, w, h, 3), w, h, minA, 40)) } catch (_: Throwable) {}
        try { cands.addAll(DocQuad.candidates(g, w, h, minA)) } catch (_: Throwable) {}
        try { regionCandidates(img, cands) } catch (_: Throwable) {}
        val total = (w * h).toFloat()
        val scored = ArrayList<Scored>()
        for (c0 in cands) {
            val c = Quad.order(c0)
            if (!convex(c)) continue
            val a = Quad.area(c) / total
            if (a < minA || a > 0.97f) continue
            lastEdge = 0f
            val s = score(img, c)
            debug?.invoke(c, s)
            if (s > 0f) scored.add(Scored(c, s, lastEdge, a, lastDark))
        }
        if (scored.isEmpty()) return null
        // 10 kandidat teratas dirapikan (tiap ujung sisi digeser sendiri, jadi kemiringan sisi ikut dibetulkan) lalu dinilai ulang
        for (c in scored.sortedByDescending { it.s }.take(10)) {
            val r = refine(img, c.q, 6)
            if (!convex(r)) continue
            val a = Quad.area(r) / total
            if (a < minA || a > 0.97f) continue
            lastEdge = 0f
            val s = score(img, r)
            debug?.invoke(r, s)
            if (s > c.s) scored.add(Scored(r, s, lastEdge, a, lastDark))
        }
        var win: Scored = scored.maxByOrNull { it.s } ?: return null
        // Isi dokumen (foto KTP, pita judul, kotak QR) juga berupa segi-empat bertepi tegas. Bila ada kandidat yang
        // lebih besar, MEMUAT pemenang, tepinya sama kuat dan nilainya hampir sama, itulah dokumennya.
        var changed = true
        while (changed) {
            changed = false
            val cur = win
            val bigger = scored.filter {
                it.area > cur.area * 1.05f && it.edge >= 0.7f && contains(it.q, cur.q, 0.025f * max(w, h)) &&
                    // hampir seri tapi lebih besar & tepinya sama tegas: dokumen utuh (pita judul faktur ikut)
                    ((it.edge >= 0.85f && it.edge >= cur.edge - 0.05f && it.s >= cur.s * rr(0.92f)) ||
                     (it.area > cur.area * 1.25f && it.edge >= 0.85f && it.s >= cur.s * rr(if (it.edge >= cur.edge + 0.1f) 0.5f else 0.8f)) ||
                        // halaman sebelah di buku terbuka: tambahan wilayahnya juga kertas → ambil dua halaman,
                        // lalu SpreadSplit yang memutuskan halaman mana yang dituju
                        (it.area > cur.area * 1.25f && it.s >= cur.s * rr(0.45f) && extraIsPaper(img, it.q, cur.q)))
            }.maxByOrNull { it.s }
            if (bigger != null) { win = bigger; changed = true }
        }
        // Buku terbuka: kedua halaman sering terdeteksi TERPISAH (punggung buku memutus wilayah). Bila ada halaman
        // tetangga sewarna yang menempel pada pemenang, gabungkan; SpreadSplit lalu memilih halaman yang dituju.
        try {
            val pair = pairPage(img, win, scored)
            if (pair != null) { win = pair; debugParts?.invoke("PAIR merged " + pair.q.joinToString(",") { it.toInt().toString() }) }
        } catch (e: Throwable) { debugParts?.invoke("PAIR merged ERR $e") }
        var q = win.q
        val bestS = win.s
        // Warna nyaru: yang tertangkap sering hanya blok tulisan (tepinya paling tegas). Bila di luar sisi-sisinya
        // masih "kertas" (warna sama dengan kertas), dorong sisi itu sampai kertasnya habis.
        try {
            val ex = expandToPaper(img, q)
            if (ex != null && convex(ex)) {
                lastEdge = 0f
                val se = score(img, ex)
                debugParts?.invoke("EXPAND s=%.3f e=%.2f".format(se, lastEdge))
                if (lastEdge >= 0.45f && se >= bestS * rr(0.5f)) q = ex
            }
        } catch (_: Throwable) {}
        val refined = refine(img, q, 3)
        val out = if (convex(refined) && score(img, refined) >= score(img, q) * 0.97f) refined else q
        // Sedikit masuk ke dalam (±1 px gambar kecil ≈ 0,3%): lebih baik kehilangan sehelai pinggir putih kertas daripada
        // membawa garis meja/lantai yang langsung terlihat setelah diluruskan.
        return Result(grow(out, -INSET), bestS, win.edge, win.dark)
    }

    // ---------------------------------------------------------------- persiapan

    private fun prepare(g: IntArray, argb: IntArray, w: Int, h: Int): Img {
        val n = w * h
        val r = IntArray(n); val gg = IntArray(n); val b = IntArray(n)
        // blur kotak 3x3 per kanal (meredam noise JPEG / tekstur halus)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0; var c = 0
            for (dy in -1..1) {
                val yy = y + dy; if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx; if (xx < 0 || xx >= w) continue
                    val p = argb[yy * w + xx]
                    sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255; c++
                }
            }
            val i = y * w + x
            r[i] = sr / c; gg[i] = sg / c; b[i] = sb / c
        }
        return Img(
            w, h, g, r, gg, b, ScanCore.closeGray(g, w, h, 2),
            ScanCore.closeGray(r, w, h, 3), ScanCore.closeGray(gg, w, h, 3), ScanCore.closeGray(b, w, h, 3),
            ScanCore.closeGray(g, w, h, 4), minFilter(g, w, h, 2)
        )
    }

    private fun minFilter(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val t = IntArray(w * h); val o = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 255
            for (d in max(0, x - r)..min(w - 1, x + r)) v = min(v, src[y * w + d])
            t[y * w + x] = v
        }
        for (y in 0 until h) for (x in 0 until w) {
            var v = 255
            for (d in max(0, y - r)..min(h - 1, y + r)) v = min(v, t[d * w + x])
            o[y * w + x] = v
        }
        return o
    }

    private fun sat(img: Img, i: Int): Int {
        val mx = max(img.r[i], max(img.gr[i], img.b[i])); val mn = min(img.r[i], min(img.gr[i], img.b[i]))
        return mx - mn
    }

    private fun colorDist(img: Img, i: Int, j: Int): Float {
        val dr = (img.r[i] - img.r[j]).toFloat(); val dg = (img.gr[i] - img.gr[j]).toFloat(); val db = (img.b[i] - img.b[j]).toFloat()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    // ---------------------------------------------------------------- kandidat dari wilayah

    private fun regionCandidates(img: Img, out: MutableList<FloatArray>) {
        val w = img.w; val h = img.h; val n = w * h
        // (a) wilayah halus: gradien warna kecil pada gambar yang teksnya sudah "ditutup"
        val grad = IntArray(n)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = abs(img.closed[i + 1] - img.closed[i - 1]) + abs(img.r[i + 1] - img.r[i - 1]) / 2 + abs(img.b[i + 1] - img.b[i - 1]) / 2
            val gy = abs(img.closed[i + w] - img.closed[i - w]) + abs(img.r[i + w] - img.r[i - w]) / 2 + abs(img.b[i + w] - img.b[i - w]) / 2
            grad[i] = max(gx, gy)
        }
        for (t in intArrayOf(10, 18, 30)) {
            val m = BooleanArray(n) { grad[it] <= t }
            components(m, w, h, out)
        }
        // (b) kertas: terang relatif terhadap foto dan hampir tak berwarna
        val hist = IntArray(256)
        for (i in 0 until n) hist[img.closed[i]]++
        val thr = otsu(hist, n)
        val paper = BooleanArray(n) { img.closed[it] > thr && sat(img, it) < 60 }
        components(paper, w, h, out)
        val bright = BooleanArray(n) { img.closed[it] > thr }
        components(bright, w, h, out)
        // (c) terang lokal: lebih terang dari rata-rata area sekitarnya (kertas di atas lantai putih yang sedikit lebih gelap)
        val mean = boxMean(img.closed, w, h, max(8, min(w, h) / 6))
        for (off in intArrayOf(8, 16)) components(BooleanArray(n) { img.closed[it] > mean[it] + off }, w, h, out)
        // (c2) bagian paling terang foto (kertas putih di atas lantai/ubin yang hampir sama terang)
        var acc = 0
        for (pct in floatArrayOf(0.97f, 0.93f, 0.85f)) {
            acc = 0; var t = 255
            while (t > 0 && acc + hist[t] < n * (1f - pct)) { acc += hist[t]; t-- }
            if (t < thr) continue
            components(BooleanArray(n) { img.closed[it] > t }, w, h, out)
        }
        // (d) bukan-latar: warna latar diambil dari pinggir foto (bisa 2-3 warna: meja + lantai), semua yang berbeda
        //     dari latar = benda. Garis tipis (kabel, nat ubin) dibuang dengan opening; teks/foto di dalam kartu ikut
        //     menjadi bagian benda sehingga KTP/kartu berwarna tetap utuh.
        try { colorClusters(img, out) } catch (_: Throwable) {}
        val bg = borderColors(img)
        if (bg.isNotEmpty()) {
            val dist = FloatArray(n) { i ->
                var d = Float.MAX_VALUE
                for (c in bg) {
                    val dr = img.r[i] - c[0]; val dg = img.gr[i] - c[1]; val db = img.b[i] - c[2]
                    val v = sqrt(dr * dr + dg * dg + db * db)
                    if (v < d) d = v
                }
                d
            }
            for (t in floatArrayOf(22f, 38f, 60f)) {
                val fg = BooleanArray(n) { dist[it] > t }
                val opened = dilate(erode(fg, w, h, 2), w, h, 2)
                val closedM = erode(dilate(opened, w, h, 3), w, h, 3)
                components(closedM, w, h, out)
            }
        }
    }

    /**
     * (e) kelompok warna (k-means pada warna yang teksnya sudah ditutup): memisahkan kertas putih dari permukaan yang
     * HAMPIR sama terangnya tapi sedikit berbeda rona (laptop perak kekuningan, meja krem, sprei kebiruan).
     */
    private fun colorClusters(img: Img, out: MutableList<FloatArray>) {
        val w = img.w; val h = img.h; val n = w * h
        val k = 7
        // awal: sampel merata menurut terang
        val idx = (0 until n step 7).sortedBy { img.cr[it] + img.cg[it] + img.cb[it] }
        val cen = Array(k) { j -> val i = idx[(idx.size - 1) * (2 * j + 1) / (2 * k)]; floatArrayOf(img.cr[i].toFloat(), img.cg[i].toFloat(), img.cb[i].toFloat()) }
        val lab = IntArray(n)
        repeat(7) {
            val acc = Array(k) { DoubleArray(4) }
            for (i in 0 until n) {
                var bj = 0; var bd = Float.MAX_VALUE
                val r = img.cr[i].toFloat(); val g = img.cg[i].toFloat(); val b = img.cb[i].toFloat()
                for (j in 0 until k) {
                    val dr = r - cen[j][0]; val dg = g - cen[j][1]; val db = b - cen[j][2]
                    // rona (selisih antar-kanal) diberi bobot lebih: beda kuning/biru tipis tetap memisahkan
                    val d = dr * dr + dg * dg + db * db + 2f * ((dr - db) * (dr - db) + (dg - db) * (dg - db))
                    if (d < bd) { bd = d; bj = j }
                }
                lab[i] = bj
                acc[bj][0] += r.toDouble(); acc[bj][1] += g.toDouble(); acc[bj][2] += b.toDouble(); acc[bj][3] += 1.0
            }
            for (j in 0 until k) if (acc[j][3] > 0) for (c in 0 until 3) cen[j][c] = (acc[j][c] / acc[j][3]).toFloat()
        }
        for (j in 0 until k) {
            val m = BooleanArray(n) { lab[it] == j }
            val cleaned = erode(dilate(dilate(erode(m, w, h, 1), w, h, 1), w, h, 2), w, h, 2)
            components(cleaned, w, h, out)
        }
    }

    /** Warna latar dominan di pinggir foto (k-means kecil, k<=4); kelompok yang terlalu jarang dibuang. */
    private fun borderColors(img: Img): List<FloatArray> {
        val w = img.w; val h = img.h
        val px = ArrayList<Int>()
        for (d in 0 until 3) {
            var x = 0; while (x < w) { px.add(d * w + x); px.add((h - 1 - d) * w + x); x += 2 }
            var y = 0; while (y < h) { px.add(y * w + d); px.add(y * w + w - 1 - d); y += 2 }
        }
        if (px.size < 16) return emptyList()
        val sorted = px.sortedBy { img.r[it] + img.gr[it] + img.b[it] }
        val k = 4
        val cen = Array(k) { j -> val i = sorted[(sorted.size - 1) * (2 * j + 1) / (2 * k)]; floatArrayOf(img.r[i].toFloat(), img.gr[i].toFloat(), img.b[i].toFloat()) }
        val cnt = IntArray(k)
        repeat(8) {
            val acc = Array(k) { FloatArray(3) }
            java.util.Arrays.fill(cnt, 0)
            for (i in px) {
                var bj = 0; var bd = Float.MAX_VALUE
                for (j in 0 until k) {
                    val dr = img.r[i] - cen[j][0]; val dg = img.gr[i] - cen[j][1]; val db = img.b[i] - cen[j][2]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bd) { bd = d; bj = j }
                }
                acc[bj][0] += img.r[i].toFloat(); acc[bj][1] += img.gr[i].toFloat(); acc[bj][2] += img.b[i].toFloat(); cnt[bj]++
            }
            for (j in 0 until k) if (cnt[j] > 0) for (c in 0 until 3) cen[j][c] = acc[j][c] / cnt[j]
        }
        return (0 until k).filter { cnt[it] >= px.size * 0.08f }.map { cen[it] }
    }

    private fun boxCount(m: BooleanArray, w: Int, h: Int, r: Int): IntArray {
        val ii = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) { var row = 0; for (x in 0 until w) { if (m[y * w + x]) row++; ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row } }
        val o = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = max(0, x - r); val x1 = min(w, x + r + 1); val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            o[y * w + x] = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
        }
        return o
    }

    private fun erode(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val c = boxCount(m, w, h, r)
        return BooleanArray(w * h) { i ->
            val x = i % w; val y = i / w
            val full = (min(w, x + r + 1) - max(0, x - r)) * (min(h, y + r + 1) - max(0, y - r))
            c[i] == full
        }
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val c = boxCount(m, w, h, r)
        return BooleanArray(w * h) { c[it] > 0 }
    }

    private fun otsu(hist: IntArray, n: Int): Int {
        var sum = 0.0; for (i in 0 until 256) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0; var best = 0.0; var thr = 128
        for (t in 0 until 256) {
            wB += hist[t]; if (wB == 0) continue
            val wF = n - wB; if (wF == 0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; thr = t }
        }
        return thr
    }

    private fun boxMean(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val ii = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) { var row = 0L; for (x in 0 until w) { row += src[y * w + x]; ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row } }
        val o = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = max(0, x - r); val x1 = min(w, x + r + 1); val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            val s = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
            o[y * w + x] = (s / ((x1 - x0) * (y1 - y0))).toInt()
        }
        return o
    }

    /** Label komponen 4-tetangga; tiap komponen yang masuk akal → segi-empat (luas maksimum dalam cembung). */
    private fun components(m: BooleanArray, w: Int, h: Int, out: MutableList<FloatArray>) {
        val n = w * h
        val lab = IntArray(n)
        val queue = IntArray(n)
        var next = 0
        val minPx = (0.012f * n).toInt(); val maxPx = (0.95f * n).toInt()
        var added = 0
        for (s in 0 until n) {
            if (!m[s] || lab[s] != 0) continue
            next++
            var head = 0; var tail = 0
            queue[tail++] = s; lab[s] = next
            var border = 0
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border++
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && m[p - 1] && lab[p - 1] == 0) { lab[p - 1] = next; queue[tail++] = p - 1 }
                if (x < w - 1 && m[p + 1] && lab[p + 1] == 0) { lab[p + 1] = next; queue[tail++] = p + 1 }
                if (y > 0 && m[p - w] && lab[p - w] == 0) { lab[p - w] = next; queue[tail++] = p - w }
                if (y < h - 1 && m[p + w] && lab[p + w] == 0) { lab[p + w] = next; queue[tail++] = p + w }
            }
            if (tail < minPx || tail > maxPx) continue
            // latar belakang menempel ke banyak pinggir foto; dokumen paling banyak terpotong sebagian
            if (border > (w + h) * 0.9f) continue
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (tail < 0.35f * bw * bh) continue          // komponen berserabut (bukan bidang kertas)
            val mask = BooleanArray(n)
            for (k in 0 until tail) mask[queue[k]] = true
            DocQuad.quadFromMask(mask, w, h)?.let { out.add(grow(it, 1.0f)); added++ }
            if (added >= 12) return
        }
    }

    /** Lebarkan segi-empat [d] piksel ke luar dari pusatnya. */
    private fun grow(q: FloatArray, d: Float): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val o = FloatArray(8)
        for (k in 0 until 4) {
            val dx = q[2 * k] - cx; val dy = q[2 * k + 1] - cy
            val l = max(1e-3f, sqrt(dx * dx + dy * dy))
            o[2 * k] = q[2 * k] + dx / l * d; o[2 * k + 1] = q[2 * k + 1] + dy / l * d
        }
        return o
    }

    // ---------------------------------------------------------------- penilaian

    private fun convex(q: FloatArray): Boolean {
        var sign = 0
        for (k in 0 until 4) {
            val a = k; val b = (k + 1) % 4; val c = (k + 2) % 4
            val cr = (q[2 * b] - q[2 * a]) * (q[2 * c + 1] - q[2 * b + 1]) - (q[2 * b + 1] - q[2 * a + 1]) * (q[2 * c] - q[2 * b])
            val s = if (cr > 0) 1 else if (cr < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        // sudut tidak boleh terlalu lancip (bukan bentuk kertas yang difoto wajar)
        for (k in 0 until 4) {
            val p = (k + 3) % 4; val nx = (k + 1) % 4
            val ax = q[2 * p] - q[2 * k]; val ay = q[2 * p + 1] - q[2 * k + 1]
            val bx = q[2 * nx] - q[2 * k]; val by = q[2 * nx + 1] - q[2 * k + 1]
            val cos = (ax * bx + ay * by) / max(1e-3f, sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by))
            if (abs(cos) > 0.6f) return false
        }
        return true
    }

    /** Dukungan tepi satu sisi: pecahan titik sampel yang warnanya berbeda nyata antara dalam dan luar. */
    private fun sideSupport(img: Img, x0: Float, y0: Float, x1: Float, y1: Float, cx: Float, cy: Float): Float {
        val w = img.w; val h = img.h
        val dx = x1 - x0; val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy); if (len < 4f) return 0f
        var nx = -dy / len; var ny = dx / len
        val mx = (x0 + x1) / 2f; val my = (y0 + y1) / 2f
        if ((cx - mx) * nx + (cy - my) * ny > 0) { nx = -nx; ny = -ny }     // n menunjuk ke LUAR
        // sisi berimpit dengan pinggir foto = dokumen terpotong bingkai: netral
        val onBorder = (max(x0, x1) < 2.5f) || (min(x0, x1) > w - 3.5f) || (max(y0, y1) < 2.5f) || (min(y0, y1) > h - 3.5f)
        if (onBorder) return BORDER
        val steps = max(8, (len / 3f).toInt())
        var hit = 0; var cnt = 0; var neutral = 0
        val diffs = FloatArray(steps); var nd = 0
        for (s in 0 until steps) {
            val t = 0.08f + 0.84f * s / (steps - 1)
            val px = x0 + dx * t; val py = y0 + dy * t
            val ox = (px + nx * 3f).toInt(); val oy = (py + ny * 3f).toInt()
            if (ox < 0 || oy < 0 || ox >= w || oy >= h) { cnt++; neutral++; continue }   // di luar bingkai: netral
            // titik "dalam": yang paling terang dari 3 kedalaman, supaya tidak jatuh tepat di baris teks gelap
            var i = -1
            for (d in floatArrayOf(3f, 5f, 8f)) {
                val xx = (px - nx * d).toInt(); val yy = (py - ny * d).toInt()
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val j = yy * w + xx
                if (i < 0 || img.g[j] > img.g[i]) i = j
            }
            if (i < 0) continue
            cnt++
            val o = oy * w + ox
            val fx = (px + nx * 6f).toInt(); val fy = (py + ny * 6f).toInt()
            val gx = (px + nx * 10f).toInt(); val gy = (py + ny * 10f).toInt()
            val f = if (fx in 0 until w && fy in 0 until h) fy * w + fx else o
            val f2 = if (gx in 0 until w && gy in 0 until h) gy * w + gx else f
            // tepi "tangga" (kertas → latar), bukan garis tipis (nat ubin, garis meja, baris teks): beda juga 6 & 10 px di luar
            fun differs(a: Int, b: Int) = colorDist(img, a, b) >= 26f || abs(img.closed[a] - img.closed[b]) >= 14
            if (differs(i, o) && differs(i, f) && differs(i, f2)) hit++
            // beda terang tipis tapi KONSISTEN sepanjang sisi (kertas putih di atas latar putih/krem)
            val ii = i
            val oo = if (f2 != f) f else o
            diffs[nd++] = (img.c4[ii] - (img.c4[o] + img.c4[oo]) / 2f)
        }
        if (cnt < 4) return 0.55f
        val strong = (hit + 0.55f * neutral) / cnt
        if (nd < 6) return strong
        var m = 0f; for (k in 0 until nd) m += diffs[k]; m /= nd
        var same = 0; for (k in 0 until nd) if (abs(diffs[k]) >= 2f && (diffs[k] > 0) == (m > 0)) same++
        val weak = 0.9f * same / nd * min(1f, abs(m) / 7f) * (nd.toFloat() / cnt)
        return max(strong, weak)
    }

    /** Titik di dalam segi-empat cembung — tak peduli urutan sudutnya searah / berlawanan jarum jam. */
    private fun inside(q: FloatArray, x: Float, y: Float): Boolean {
        var pos = false; var neg = false
        for (k in 0 until 4) {
            val j = (k + 1) % 4
            val cr = (q[2 * j] - q[2 * k]) * (y - q[2 * k + 1]) - (q[2 * j + 1] - q[2 * k + 1]) * (x - q[2 * k])
            if (cr > 0) pos = true else if (cr < 0) neg = true
            if (pos && neg) return false
        }
        return true
    }

    private fun score(img: Img, q: FloatArray): Float {
        val w = img.w; val h = img.h
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val sup = FloatArray(4) { k -> val j = (k + 1) % 4; sideSupport(img, q[2 * k], q[2 * k + 1], q[2 * j], q[2 * j + 1], cx, cy) }
        val mn = sup.min(); val mean = sup.average().toFloat()
        // 3-4 sisi menempel bingkai = latar/seluruh foto, bukan dokumen; 2 sisi = dokumen besar terpotong (agak ragu)
        val nb = sup.count { it == BORDER }
        if (nb >= 3) { lastEdge = 0f; return 0f }
        val edge = (W[1] * mn + (1f - W[1]) * mean) * (if (nb == 2) W[8] else 1f)
        lastEdge = edge
        if (edge < 0.25f) return 0f

        // isi: seragam (pada gambar abu-abu yang teksnya ditutup) dan seperti kertas
        val shr = FloatArray(8) { i -> if (i % 2 == 0) cx + (q[i] - cx) * 0.85f else cy + (q[i] - cy) * 0.85f }
        val bb = Quad.bbox(shr)
        val step = max(1, (max(bb[2] - bb[0], bb[3] - bb[1]) / 28f).toInt())
        val vals = ArrayList<Int>(900)
        var paper = 0; var sumR = 0L; var sumG = 0L; var sumB = 0L
        var y = bb[1].toInt()
        while (y <= bb[3].toInt()) {
            var x = bb[0].toInt()
            while (x <= bb[2].toInt()) {
                if (x in 0 until w && y in 0 until h && inside(shr, x.toFloat(), y.toFloat())) {
                    val i = y * w + x
                    vals.add(img.closed[i])
                    if (sat(img, i) < 55 && img.closed[i] > 110) paper++
                    sumR += img.cr[i]; sumG += img.cg[i]; sumB += img.cb[i]
                }
                x += step
            }
            y += step
        }
        if (vals.size < 12) return 0f
        vals.sort()
        val med = vals[vals.size / 2]
        var near = 0
        for (v in vals) if (abs(v - med) <= 28) near++
        val homog = near.toFloat() / vals.size
        // benda gelap pekat (ponsel, laptop, dompet) hampir tidak pernah dokumen
        val darkF = if (med < 60) 0.45f else if (med < 90) 0.75f else 1f
        lastDark = med < 70
        val paperF = paper.toFloat() / vals.size

        // kontras isi vs cincin di luar
        val mr = sumR / vals.size; val mg = sumG / vals.size; val mb = sumB / vals.size
        var rr = 0L; var rg = 0L; var rb = 0L; var rc = 0
        for (k in 0 until 4) {
            val j = (k + 1) % 4
            for (s in 1..9) {
                val t = s / 10f
                val px = q[2 * k] + (q[2 * j] - q[2 * k]) * t; val py = q[2 * k + 1] + (q[2 * j + 1] - q[2 * k + 1]) * t
                val dx = px - cx; val dy = py - cy; val l = max(1f, sqrt(dx * dx + dy * dy))
                val ox = (px + dx / l * 6f).toInt(); val oy = (py + dy / l * 6f).toInt()
                if (ox !in 0 until w || oy !in 0 until h) continue
                val i = oy * w + ox
                rr += img.cr[i]; rg += img.cg[i]; rb += img.cb[i]; rc++
            }
        }
        val contrast = if (rc < 6) 0.5f else {
            val d = sqrt(((mr - rr / rc) * (mr - rr / rc) + (mg - rg / rc) * (mg - rg / rc) + (mb - rb / rc) * (mb - rb / rc)).toFloat())
            (d / 60f).coerceIn(0.15f, 1f)
        }

        val area = Quad.area(q) / (w * h).toFloat()
        val areaF = sqrt(sqrt(area))                       // ukuran: pengaruh lembut, bukan syarat
        // pusat foto: orang biasanya membidik dokumen di tengah
        val dcx = abs(cx / w - 0.5f); val dcy = abs(cy / h - 0.5f)
        val centerF = 1f - W[7] * min(1f, (dcx + dcy))
        debugFeat?.invoke(q, floatArrayOf(mn, mean, nb.toFloat(), homog, paperF, med.toFloat(), contrast, area, dcx + dcy))
        debugParts?.invoke("e=%.2f[%s] hom=%.2f pap=%.2f con=%.2f a=%.3f c=%.2f".format(edge, sup.joinToString("/") { "%.2f".format(it) }, homog, paperF, contrast, area, centerF))
        return combine(edge, homog, paperF, darkF, contrast, area, centerF)
    }

    // ---------------------------------------------------------------- perapian

    /**
     * Kebalikan [expandToPaper]: bila di DALAM sisi ada jalur permukaan lain yang terang tapi BUKAN kertas (bodi laptop,
     * meja putih, halaman sebelah yang ronanya beda) lalu baru kertasnya, tarik sisi itu ke tepi kertas.
     * Hanya untuk kertas yang hampir tak berwarna (putih/krem) — kartu berwarna (KTP) dengan pita judul tidak disentuh.
     * [q] dalam piksel gambar kecil; mengembalikan [q] bila tak ada yang diubah.
     */
    fun trimToPaper(g: IntArray, argb: IntArray, w: Int, h: Int, q: FloatArray): FloatArray =
        try { trimToPaper(prepare(g, argb, w, h), q) } catch (_: Throwable) { q }

    private fun trimToPaper(img: Img, q: FloatArray): FloatArray {
        val w = img.w; val h = img.h
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        // warna kertas dari bagian tengah (setengah ukuran) — tepi yang meragukan tidak ikut
        val inner = FloatArray(8) { i -> if (i % 2 == 0) cx + (q[i] - cx) * 0.5f else cy + (q[i] - cy) * 0.5f }
        val bb = Quad.bbox(inner)
        val st = max(1f, max(bb[2] - bb[0], bb[3] - bb[1]) / 30f)
        val c4s = ArrayList<Int>()
        var y = bb[1]
        while (y <= bb[3]) { var x = bb[0]; while (x <= bb[2]) { val xi = x.toInt(); val yi = y.toInt(); if (xi in 0 until w && yi in 0 until h) c4s.add(img.c4[yi * w + xi]); x += st }; y += st }
        if (c4s.size < 20) return q
        c4s.sort()
        val paper = c4s[c4s.size * 9 / 10].toFloat()
        var pR = 0f; var pG = 0f; var pB = 0f; var pN = 0
        y = bb[1]
        while (y <= bb[3]) {
            var x = bb[0]
            while (x <= bb[2]) {
                val xi = x.toInt(); val yi = y.toInt()
                if (xi in 0 until w && yi in 0 until h && img.c4[yi * w + xi] >= paper - 4) { val i = yi * w + xi; pR += img.cr[i]; pG += img.cg[i]; pB += img.cb[i]; pN++ }
                x += st
            }
            y += st
        }
        if (pN < 5) return q
        pR /= pN; pG /= pN; pB /= pN
        if (max(pR, max(pG, pB)) - min(pR, min(pG, pB)) > 45f || paper < 120f) return q   // kertas berwarna / gelap: lewati
        // Bandingkan RONA yang dinormalkan terhadap terang (cahaya miring / bayangan tidak mengubah rona kertas).
        val pm = (pR + pG + pB) / 3f
        val pcr = (pR - pm) * 200f / pm; val pcb = (pB - pm) * 200f / pm
        fun chromaDist(i: Int): Float {
            val m = max(20f, (img.cr[i] + img.cg[i] + img.cb[i]) / 3f)
            val cr = (img.cr[i] - m) * 200f / m; val cb = (img.cb[i] - m) * 200f / m
            return sqrt((cr - pcr) * (cr - pcr) + (cb - pcb) * (cb - pcb))
        }
        fun isPaper(i: Int) = img.c4[i] >= paper * 0.6f && chromaDist(i) <= 10f
        fun brightOther(i: Int) = img.c4[i] >= paper * 0.55f && chromaDist(i) >= 18f
        var changed = false
        val lines = Array(4) { k ->
            val j = (k + 1) % 4
            val x0 = q[2 * k]; val y0 = q[2 * k + 1]; val x1 = q[2 * j]; val y1 = q[2 * j + 1]
            val dx = x1 - x0; val dy = y1 - y0; val l = max(1e-3f, sqrt(dx * dx + dy * dy))
            var nx = -dy / l; var ny = dx / l                       // n menunjuk ke DALAM
            if ((cx - (x0 + x1) / 2) * nx + (cy - (y0 + y1) / 2) * ny < 0) { nx = -nx; ny = -ny }
            val dc = abs((cx - x0) * nx + (cy - y0) * ny)
            val maxIn = (dc * 0.6f).toInt()
            val offs = FloatArray(2)
            val unknown = BooleanArray(2) { true }
            val strong = BooleanArray(2)
            if (maxIn >= 6) for (half in 0..1) {
                val pf = FloatArray(maxIn); val bf = FloatArray(maxIn)
                for (o in 0 until maxIn) {
                    var c = 0; var np = 0; var nb = 0
                    for (sI in 0 until 8) {
                        val t = half * 0.5f + 0.08f + 0.34f * sI / 7f
                        val xi = (x0 + dx * t + nx * o).toInt(); val yi = (y0 + dy * t + ny * o).toInt()
                        if (xi !in 0 until w || yi !in 0 until h) continue
                        val i = yi * w + xi
                        c++; if (isPaper(i)) np++; if (brightOther(i)) nb++
                    }
                    if (c > 0) { pf[o] = np.toFloat() / c; bf[o] = nb.toFloat() / c }
                }
                // awal kertas: 4 langkah berturut-turut mayoritas kertas
                var o0 = -1
                for (o in 0 until maxIn - 4) if ((0 until 4).all { pf[o + it] >= 0.6f }) { o0 = o; break }
                debugParts?.invoke("TRIM? side=$k half=$half o0=$o0 pf=" + pf.take(min(40, maxIn)).joinToString(",") { "%.1f".format(it) } + " bf=" + bf.take(min(40, maxIn)).joinToString(",") { "%.1f".format(it) })
                if (o0 >= 0) unknown[half] = false
                if (o0 < 4) continue
                var pfm = 0f; var bfm = 0f
                for (o in 0 until o0) { pfm += pf[o]; bfm += bf[o] }
                pfm /= o0; bfm /= o0
                debugParts?.invoke("TRIM side=$k half=$half o0=$o0 paperFrac=$pfm otherFrac=$bfm")
                // jalur bukan-kertas: sebagian besar permukaan terang lain; boleh bercampur benda gelap (earphone, tombol)
                if ((pfm <= 0.25f && bfm >= 0.4f) || (pfm <= 0.1f && bfm >= 0.2f)) { offs[half] = o0.toFloat(); strong[half] = bfm >= 0.6f && pfm <= 0.15f }
            }
            // satu paruh tak menemukan kertas sama sekali (jalurnya lebih lebar / tertutup benda): ikut paruh lainnya
            if (offs[0] > 0f && strong[0] && unknown[1]) offs[1] = offs[0]
            if (offs[1] > 0f && strong[1] && unknown[0]) offs[0] = offs[1]
            // kedua paruh harus sepakat (jalur permukaan lain sepanjang sisi), selisih wajar
            if (offs[0] > 0f && offs[1] > 0f && abs(offs[0] - offs[1]) <= max(6f, 0.5f * max(offs[0], offs[1]))) changed = true
            else { offs[0] = 0f; offs[1] = 0f }
            val oa = offs[0] - (offs[1] - offs[0]) * 0.5f
            val ob = offs[1] + (offs[1] - offs[0]) * 0.5f
            floatArrayOf(x0 + nx * oa, y0 + ny * oa, x1 + nx * ob, y1 + ny * ob)
        }
        if (!changed) return q
        val out = FloatArray(8)
        for (k in 0 until 4) {
            val p = intersect(lines[(k + 3) % 4], lines[k]) ?: return q
            out[2 * k] = p.first.coerceIn(0f, w.toFloat()); out[2 * k + 1] = p.second.coerceIn(0f, h.toFloat())
        }
        return if (convex(out)) out else q
    }

    /**
     * Dorong tiap sisi [q] ke luar selama yang dilewati masih berwarna kertas (abu-abu tertutup [Img.c4], jadi teks
     * tidak mengganggu); berhenti di tempat warnanya berubah ke latar. Tiap sisi diukur di dua paruh agar sisi boleh
     * berbelok mengikuti tepi kertas yang sebenarnya. Null bila tidak ada sisi yang bergeser.
     */
    private fun expandToPaper(img: Img, q: FloatArray): FloatArray? {
        val w = img.w; val h = img.h
        // warna kertas = persentil-90 isi (teks sudah tertutup)
        val bb = Quad.bbox(q)
        val vals = ArrayList<Int>()
        val st = max(1, (max(bb[2] - bb[0], bb[3] - bb[1]) / 30f).toInt())
        var yy = bb[1].toInt()
        while (yy <= bb[3]) {
            var xx = bb[0].toInt()
            while (xx <= bb[2]) {
                if (xx in 0 until w && yy in 0 until h && inside(q, xx.toFloat(), yy.toFloat())) vals.add(img.c4[yy * w + xx])
                xx += st
            }
            yy += st
        }
        if (vals.size < 20) return null
        vals.sort()
        val paper = vals[(vals.size * 0.9f).toInt().coerceAtMost(vals.size - 1)].toFloat()
        // rona kertas: rata-rata warna (tertutup) dari titik-titik isi yang seterang kertas
        var pR = 0f; var pG = 0f; var pB = 0f; var pN = 0
        yy = bb[1].toInt()
        while (yy <= bb[3]) {
            var xx = bb[0].toInt()
            while (xx <= bb[2]) {
                if (xx in 0 until w && yy in 0 until h && inside(q, xx.toFloat(), yy.toFloat())) {
                    val i = yy * w + xx
                    if (img.c4[i] >= paper - 3) { pR += img.cr[i]; pG += img.cg[i]; pB += img.cb[i]; pN++ }
                }
                xx += st
            }
            yy += st
        }
        if (pN < 5) return null
        pR /= pN; pG /= pN; pB /= pN
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        var moved = false
        val lines = Array(4) { k ->
            val j = (k + 1) % 4
            val x0 = q[2 * k]; val y0 = q[2 * k + 1]; val x1 = q[2 * j]; val y1 = q[2 * j + 1]
            val dx = x1 - x0; val dy = y1 - y0; val l = max(1e-3f, sqrt(dx * dx + dy * dy))
            var nx = -dy / l; var ny = dx / l
            if ((cx - (x0 + x1) / 2) * nx + (cy - (y0 + y1) / 2) * ny > 0) { nx = -nx; ny = -ny }
            // jarak sisi ke pusat: batas dorongan (dokumen tidak mungkin "tumbuh" lebih dari ~setinggi dirinya)
            val dc = abs((cx - x0) * nx + (cy - y0) * ny)
            val maxO = min(140, (dc * 2.6f).toInt()).coerceAtLeast(4)
            val offs = FloatArray(2)
            val found = BooleanArray(2)
            for (half in 0..1) {
                val prof = FloatArray(maxO + 6)
                val inkF = FloatArray(maxO + 6)
                val colD = FloatArray(maxO + 6)
                var valid = maxO + 6
                for (o in 0 until maxO + 6) {
                    var sum = 0f; var c = 0; var inkN = 0; var sr = 0f; var sg = 0f; var sb = 0f
                    for (sI in 0 until 8) {
                        val t = half * 0.5f + 0.06f + 0.38f * sI / 7f
                        val px = x0 + dx * t + nx * o; val py = y0 + dy * t + ny * o
                        val xi = px.toInt(); val yi = py.toInt()
                        if (xi !in 0 until w || yi !in 0 until h) continue
                        sum += img.c4[yi * w + xi]; c++
                        sr += img.cr[yi * w + xi]; sg += img.cg[yi * w + xi]; sb += img.cb[yi * w + xi]
                        if (img.mn[yi * w + xi] < img.c4[yi * w + xi] - 25) inkN++
                    }
                    if (c < 5) { valid = o; break }
                    prof[o] = sum / c
                    inkF[o] = inkN.toFloat() / c
                    val dr = sr / c - pR; val dg = sg / c - pG; val db = sb / c - pB
                    colD[o] = sqrt(dr * dr + dg * dg + db * db)
                }
                if (valid < 8) { offs[half] = 0f; continue }
                val far = prof[valid - 1]
                val contrast = abs(paper - far)
                if (contrast < 4f) { offs[half] = 0f; continue }      // tidak ada bukti batas kertas/latar
                val thr = (contrast * 0.45f).coerceIn(3f, 14f)
                // beda rona kertas vs latar (laptop perak kekuningan vs struk putih netral)
                val cthr = (0.5f * colD[valid - 1]).coerceIn(8f, 18f)
                // 2 px pertama dilewati: tepat di garis sisi sering ada pinggir blok teks / bayangan tipis
                var o = 2
                // tinta/kotak QR (jauh lebih gelap dari kertas DAN latar) boleh dilewati, tapi hanya bila sesudahnya
                // kertas lagi dan tidak terlalu lebar — keyboard / benda gelap besar menghentikan dorongan.
                val ink = min(paper, far) - max(25f, 2f * thr)
                val maxInk = max(6f, dc * 0.8f).toInt()
                var lastPaper = -1; var inkRun = 0; var broke = false
                while (o < valid - 1) {
                    val paperLike = abs(prof[o] - paper) <= thr && colD[o] <= cthr
                    val inky = prof[o] < ink || inkF[o] >= 0.15f
                    if (paperLike) { lastPaper = o; inkRun = 0 }
                    else if (inky && inkRun < maxInk) inkRun++
                    else { broke = true; break }
                    o++
                }
                found[half] = broke
                offs[half] = if (!broke || lastPaper <= 3) 0f else lastPaper.toFloat()  // tidak ketemu ujung: jangan geser
                debugParts?.invoke("EXPAND side=$k half=$half paper=$paper far=$far thr=$thr o=$o valid=$valid prof=" + prof.take(valid).joinToString(",") { it.toInt().toString() })
            }
            // satu paruh tidak menemukan ujung kertas (latar terlalu mirip): anggap sisi sejajar, pakai paruh lainnya
            if (!found[0] && found[1]) offs[0] = offs[1]
            if (!found[1] && found[0]) offs[1] = offs[0]
            if (offs[0] > 1.5f || offs[1] > 1.5f) moved = true
            // offset di t=0.25 dan t=0.75 → ujung-ujung sisi
            val oa = offs[0] - (offs[1] - offs[0]) * 0.5f
            val ob = offs[1] + (offs[1] - offs[0]) * 0.5f
            floatArrayOf(x0 + nx * oa, y0 + ny * oa, x1 + nx * ob, y1 + ny * ob)
        }
        if (!moved) return null
        val out = FloatArray(8)
        for (k in 0 until 4) {
            val p = intersect(lines[(k + 3) % 4], lines[k]) ?: return null
            out[2 * k] = p.first.coerceIn(0f, w.toFloat()); out[2 * k + 1] = p.second.coerceIn(0f, h.toFloat())
        }
        return out
    }

    /**
     * Geser kedua ujung tiap sisi (masing-masing -[range]..+[range] px, tegak lurus sisi) ke posisi dengan beda warna
     * dalam/luar terbesar; sudut baru = perpotongan sisi-sisi itu.
     */
    private fun refine(img: Img, q0: FloatArray, range: Int): FloatArray {
        val w = img.w; val h = img.h
        var q = q0.copyOf()
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val lines = Array(4) { k ->
            val j = (k + 1) % 4
            val x0 = q[2 * k]; val y0 = q[2 * k + 1]; val x1 = q[2 * j]; val y1 = q[2 * j + 1]
            val dx = x1 - x0; val dy = y1 - y0; val l = max(1e-3f, sqrt(dx * dx + dy * dy))
            var nx = -dy / l; var ny = dx / l
            if ((cx - (x0 + x1) / 2) * nx + (cy - (y0 + y1) / 2) * ny > 0) { nx = -nx; ny = -ny }
            var bestA = 0f; var bestB = 0f; var bestV = -1f
            val stepO = if (range > 3) 1f else 0.5f
            var oa = -range.toFloat()
            while (oa <= range) {
                var ob = -range.toFloat()
                while (ob <= range) {
                    var v = 0f
                    for (s in 1..15) {
                        val t = s / 16f
                        val off = oa * (1 - t) + ob * t
                        val px = x0 + dx * t + nx * off; val py = y0 + dy * t + ny * off
                        val ax = (px - nx * 1.5f).toInt(); val ay = (py - ny * 1.5f).toInt()
                        val bx = (px + nx * 1.5f).toInt(); val by = (py + ny * 1.5f).toInt()
                        if (ax !in 0 until w || ay !in 0 until h || bx !in 0 until w || by !in 0 until h) continue
                        v += colorDist(img, ay * w + ax, by * w + bx)
                    }
                    // sedikit memihak posisi semula supaya tidak melompat tanpa alasan
                    v -= (abs(oa) + abs(ob)) * 0.5f
                    if (v > bestV + 1e-3f) { bestV = v; bestA = oa; bestB = ob }
                    ob += stepO
                }
                oa += stepO
            }
            floatArrayOf(x0 + nx * bestA, y0 + ny * bestA, x1 + nx * bestB, y1 + ny * bestB)
        }
        // sudut baru = perpotongan sisi berurutan
        val out = FloatArray(8)
        for (k in 0 until 4) {
            val a = lines[(k + 3) % 4]; val b = lines[k]
            val p = intersect(a, b) ?: return q0
            out[2 * k] = p.first.coerceIn(0f, w.toFloat()); out[2 * k + 1] = p.second.coerceIn(0f, h.toFloat())
        }
        q = out
        return q
    }

    private fun intersect(a: FloatArray, b: FloatArray): Pair<Float, Float>? {
        val x1 = a[0]; val y1 = a[1]; val x2 = a[2]; val y2 = a[3]
        val x3 = b[0]; val y3 = b[1]; val x4 = b[2]; val y4 = b[3]
        val d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(d) < 1e-4f) return null
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d
        return (x1 + t * (x2 - x1)) to (y1 + t * (y2 - y1))
    }
}
