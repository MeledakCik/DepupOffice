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

    private class Scored(val q: FloatArray, val s: Float, val edge: Float, val area: Float, val dark: Boolean, val f: FloatArray)
    private const val BORDER = 0.55f
    @JvmStatic var INSET = 0.5f

    private fun pairPage(gt: Gather, win: Scored, scored: List<Scored>, P: FloatArray): Scored? {
        val w = gt.w; val h = gt.h
        var best: Scored? = null; var bestV = 0f
        for (c in scored) {
            if (c === win || c.s < win.s * rr(0.4f, P) || c.edge < 0.6f) continue
            if (c.area < win.area * 0.45f || c.area > win.area * 2.2f) continue
            val m = gt.memo.getOrPut("P" + key(win.q) + "|" + key(c.q)) { pairQuad(gt.img, win.q, c.q) } as FloatArray? ?: continue
            val v = c.s
            if (v > bestV) { bestV = v; best = Scored(Quad.order(m.copyOf(8)), win.s, max(win.edge, c.edge), m[8] / (w * h).toFloat(), win.dark, win.f) }
        }
        return best
    }

    /** Gabungan dua halaman [a] & [b] yang berdampingan rapat & sama-sama kertas (8 sudut + luas), atau null. */
    private fun pairQuad(img: Img, a: FloatArray, b: FloatArray): FloatArray? {
        val w = img.w; val h = img.h
        // Saringan cepat (geometri kontinu, batas jauh lebih longgar daripada uji piksel di bawah): pasangan yang jelas
        // bertumpuk atau berjauhan ditolak tanpa membangun masker — hasilnya sama, jauh lebih hemat.
        val aA = polyArea(a); val aB = polyArea(b)
        val inter = interArea(a, b)
        if (inter > 0.35f * min(aA, aB)) return null
        if (hullQuadArea(a, b) > 1.5f * (aA + aB - inter)) return null
        // tidak saling tumpuk banyak
        val mask = BooleanArray(w * h)
        var inA = 0; var inB = 0; var both = 0
        val b1 = Quad.bbox(a); val b2 = Quad.bbox(b)
        val bb = floatArrayOf(min(b1[0], b2[0]), min(b1[1], b2[1]), max(b1[2], b2[2]), max(b1[3], b2[3]))
        for (y in max(0, bb[1].toInt()) until min(h, bb[3].toInt() + 1)) for (x in max(0, bb[0].toInt()) until min(w, bb[2].toInt() + 1)) {
            val ia = inside(a, x + 0.5f, y + 0.5f); val ib = inside(b, x + 0.5f, y + 0.5f)
            if (ia) inA++; if (ib) inB++; if (ia && ib) both++
            if (ia || ib) mask[y * w + x] = true
        }
        if (both > 0.15f * min(inA, inB)) return null
        val u = DocQuad.quadFromMask(mask, w, h) ?: return null
        val ua = polyArea(u)
        debugParts?.invoke("PAIR? c=${b.joinToString(",") { it.toInt().toString() }} ua=$ua sum=${inA + inB - both} paper=${extraIsPaper(img, u, a)}")
        // berdampingan rapat: gabungan hampir sama dengan jumlah luas keduanya (celah punggung buku kecil)
        if (ua > (inA + inB - both) * 1.15f) return null
        if (!extraIsPaper(img, u, a)) return null
        return u.copyOf(9).also { it[8] = ua }
    }

    private fun key(q: FloatArray): String = q.joinToString(",")

    /** Luas irisan dua segi-empat cembung (pemotongan Sutherland–Hodgman). */
    private fun interArea(a: FloatArray, b: FloatArray): Float {
        var poly = ArrayList<Float>(16).apply { for (v in a) add(v) }
        var sgn = 0f
        for (k in 0 until 4) { val j = (k + 1) % 4; sgn += b[2 * k] * b[2 * j + 1] - b[2 * j] * b[2 * k + 1] }
        val o = if (sgn >= 0f) 1f else -1f
        for (k in 0 until 4) {
            if (poly.size < 6) return 0f
            val j = (k + 1) % 4
            val px = b[2 * k]; val py = b[2 * k + 1]; val ex = b[2 * j] - px; val ey = b[2 * j + 1] - py
            fun side(x: Float, y: Float) = o * (ex * (y - py) - ey * (x - px))
            val out = ArrayList<Float>(16)
            val n = poly.size / 2
            for (i in 0 until n) {
                val x0 = poly[2 * i]; val y0 = poly[2 * i + 1]; val i1 = (i + 1) % n
                val x1 = poly[2 * i1]; val y1 = poly[2 * i1 + 1]
                val s0 = side(x0, y0); val s1 = side(x1, y1)
                if (s0 >= 0f) { out.add(x0); out.add(y0) }
                if ((s0 >= 0f) != (s1 >= 0f)) { val t = s0 / (s0 - s1); out.add(x0 + (x1 - x0) * t); out.add(y0 + (y1 - y0) * t) }
            }
            poly = out
        }
        val n = poly.size / 2
        if (n < 3) return 0f
        var ar = 0f
        for (i in 0 until n) { val j = (i + 1) % n; ar += poly[2 * i] * poly[2 * j + 1] - poly[2 * j] * poly[2 * i + 1] }
        return abs(ar) / 2f
    }

    /** Luas segi-empat terbesar yang sudutnya titik-titik selubung cembung kedua segi-empat (seperti quadFromMask). */
    private fun hullQuadArea(a: FloatArray, b: FloatArray): Float {
        val pts = (0 until 8).map { if (it < 4) a[2 * it] to a[2 * it + 1] else b[2 * (it - 4)] to b[2 * (it - 4) + 1] }
            .sortedWith(compareBy({ it.first }, { it.second }))
        fun cr(o: Pair<Float, Float>, p: Pair<Float, Float>, q: Pair<Float, Float>) =
            (p.first - o.first) * (q.second - o.second) - (p.second - o.second) * (q.first - o.first)
        val hv = ArrayList<Pair<Float, Float>>()
        for (p in pts) { while (hv.size >= 2 && cr(hv[hv.size - 2], hv[hv.size - 1], p) <= 0f) hv.removeAt(hv.size - 1); hv.add(p) }
        val lower = hv.size + 1
        for (p in pts.asReversed().drop(1)) { while (hv.size >= lower && cr(hv[hv.size - 2], hv[hv.size - 1], p) <= 0f) hv.removeAt(hv.size - 1); hv.add(p) }
        hv.removeAt(hv.size - 1)
        val m = hv.size
        if (m < 4) return 0f
        var best = 0f
        for (i in 0 until m) for (j in i + 1 until m) for (k in j + 1 until m) for (l in k + 1 until m) {
            val q = floatArrayOf(hv[i].first, hv[i].second, hv[j].first, hv[j].second, hv[k].first, hv[k].second, hv[l].first, hv[l].second)
            best = max(best, polyArea(q))
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

    /** Nilai minimum supaya hasil dipercaya (di bawah ini sudut dibiarkan untuk diatur manual). Ikut dilatih. */
    @JvmField var MIN_SCORE = 0.058f

    /**
     * Bobot penilaian kandidat — HASIL PELATIHAN (tools/autocrop/Trainer.kt): coordinate descent dengan ALUR PENUH di
     * dalam lingkaran pada ±700 foto sintetis (set 6 umum, 8 putih di atas putih, 10 dokumen berwarna & dipegang tangan)
     * + foto asli pengguna + 17 foto contoh label/kartu/brosur/koran (tools/autocrop/mock), dipilih pada ±450 foto uji
     * terpisah (set 7, 9, 11).
     * [0] pangkat tepi, [1] bobot sisi terlemah, [2] bobot keseragaman isi, [3] bobot "mirip kertas putih" (kini ±0:
     * dokumen berwarna tidak lagi kalah oleh bidang putih), [4] pangkat penalti benda gelap, [5] bobot kontras,
     * [6] pangkat luas, [7] penalti jauh dari tengah, [8] penalti 2 sisi menempel bingkai, [9] bobot isi/tinta (bidang
     * polos — lampu, jendela, dinding — bukan dokumen), [10] pecahan tinta untuk nilai penuh, [11] bobot kesejajaran tepi
     * rata-rata, [12] bobot kesejajaran sisi terlemah (keduanya ±0: kesejajaran dipakai lewat aturan [ALIGN_DROP] &
     * [MESSY], yang ternyata lebih baik pada set uji daripada sebagai faktor nilai).
     */
    @JvmField val W = floatArrayOf(1.945f, 0f, 0.662f, 0f, 0.6f, 0.285f, 0.49f, 0.299f, 0.916f, 0.95f, 0.12f, 0.047f, 0f)

    /**
     * Ambang perbandingan nilai antar-kandidat dirancang untuk skala lama (tepi pangkat 2). Setelah bobot dilatih,
     * skala nilai berubah; [rr] menerjemahkan ambang lama ke skala baru supaya aturannya tetap sama ketatnya.
     */
    private fun rr(r: Float, P: FloatArray = W): Float = Math.pow(r.toDouble(), (P[0] / 2f).toDouble()).toFloat()

    /**
     * Nilai kandidat dari fitur mentahnya [f] (urutan seperti [debugFeat]) dengan bobot [P]. Satu-satunya rumus
     * penilaian — alat latih (tools/autocrop/Trainer.kt) memanggil fungsi yang sama.
     */
    @JvmStatic
    fun scoreFeat(f: FloatArray, P: FloatArray = W): Float {
        val edge = edgeOf(f, P)
        if (edge < 0.25f || f[3] < 0f) return 0f
        val med = f[5]
        val darkF = if (med < 60) 0.45f else if (med < 90) 0.75f else 1f
        val centerF = 1f - P[7] * min(1f, f[8])
        // isi: dokumen hampir selalu memuat tulisan/gambar; bidang polos (lampu, jendela, dinding) tidak
        val ink = min(1f, f[9] / max(1e-3f, P[10]))
        return Math.pow(edge.toDouble(), P[0].toDouble()).toFloat() * (1f - P[2] + P[2] * f[3]) * (1f - P[3] + P[3] * f[4]) *
            Math.pow(darkF.toDouble(), P[4].toDouble()).toFloat() * (1f - P[5] + P[5] * f[6]) *
            Math.pow(f[7].toDouble(), P[6].toDouble()).toFloat() * centerF * (1f - P[9] + P[9] * ink) *
            // sisi mengikuti tepi lurus yang nyata (rata-rata & sisi terlemah)
            (1f - P[11] + P[11] * f[10]) * (1f - P[12] + P[12] * f[11])
    }

    /** Kekuatan tepi gabungan (0 bila 3-4 sisi menempel bingkai foto). */
    private fun edgeOf(f: FloatArray, P: FloatArray): Float {
        val nb = f[2].toInt()
        if (nb >= 3) return 0f
        return (P[1] * f[0] + (1f - P[1]) * f[1]) * (if (nb == 2) P[8] else 1f)
    }

    /**
     * Kait uji: fitur mentah tiap kandidat [mn, mean, nb, homog, paperF, median, kontras, luas, jarak-tengah, tinta,
     * kesejajaran-rata2, kesejajaran-min].
     */
    @JvmStatic var debugFeat: ((FloatArray, FloatArray) -> Unit)? = null

    /** Kait untuk uji JVM: dipanggil untuk tiap kandidat beserta nilainya. */
    @JvmStatic var debug: ((FloatArray, Float) -> Unit)? = null
    @JvmStatic var debugParts: ((String) -> Unit)? = null

    /** r/gr/b = warna ter-blur; cr/cg/cb = warna yang teks/garis gelap tipisnya sudah "ditutup" (untuk menilai tepi kertas). */
    internal class Img(
        val w: Int, val h: Int, val g: IntArray, val r: IntArray, val gr: IntArray, val b: IntArray, val closed: IntArray,
        val cr: IntArray, val cg: IntArray, val cb: IntArray,
        /** abu-abu dengan penutupan lebar: teks (dan garis tipis) hilang total — untuk tepi kertas yang samar */
        val c4: IntArray,
        /** minimum lokal (5x5) abu-abu: menandai tinta/teks di dekat titik */
        val mn: IntArray,
        /** maksimum lokal (5x5) abu-abu: bersama [mn] menandai isi (teks gelap ATAU tulisan putih di papan berwarna) */
        val mx: IntArray
    )

    /** [g] abu-abu ter-blur, [argb] warna asli; keduanya [w]x[h]. Kembalikan segi-empat piksel (KA, KaA, KaB, KiB). */
    @Synchronized
    fun find(g: IntArray, argb: IntArray, w: Int, h: Int): Result? = gather(g, argb, w, h)?.let { select(it) }

    /** Kandidat + fitur mentahnya (tidak bergantung bobot [W]). */
    internal class Cand(val q: FloatArray, val area: Float, val feat: FloatArray)

    /**
     * Semua yang TIDAK bergantung bobot untuk satu foto: kandidat beserta fiturnya, plus memo tahap-tahap sesudahnya.
     * [find] = [gather] lalu [select]; alat latih (tools/autocrop) memanggil [select] berulang kali dengan bobot lain
     * tanpa menghitung ulang kandidat — alur penuh, bukan perkiraan.
     */
    class Gather internal constructor(
        internal val g: IntArray, internal val argb: IntArray, internal val w: Int, internal val h: Int,
        internal val cands: List<Cand>, img: Img?
    ) {
        private var imgRef: Img? = img
        internal val img: Img get() = imgRef ?: prepare(g, argb, w, h).also { imgRef = it }
        internal val memo = HashMap<String, Any?>()
        /** Lepas gambar olahan (dibangun ulang otomatis bila diperlukan) — menghemat memori alat latih. */
        fun release() { imgRef = null }
    }

    /**
     * Kumpulkan kandidat dari semua sumber dan hitung fiturnya. Aman dipanggil paralel (tanpa state bersama).
     * [refineAll] (alat latih): rapikan SEMUA kandidat sekarang, bukan hanya 10 teratas saat [select] — supaya
     * penilaian ulang dengan bobot lain tidak perlu menyentuh gambar lagi.
     */
    fun gather(g: IntArray, argb: IntArray, w: Int, h: Int, refineAll: Boolean = false): Gather? {
        if (w < 32 || h < 32) return null
        val img = prepare(g, argb, w, h)
        val raw = ArrayList<FloatArray>()
        val minA = 0.012f
        try { raw.addAll(LineQuad.candidates(g, w, h, minA, 40)) } catch (_: Throwable) {}
        // versi tanpa teks: baris-baris tulisan tidak lagi "merebut" garis Hough dari tepi kertas
        try { raw.addAll(LineQuad.candidates(ScanCore.closeGray(g, w, h, 3), w, h, minA, 40)) } catch (_: Throwable) {}
        try { raw.addAll(DocQuad.candidates(g, w, h, minA)) } catch (_: Throwable) {}
        try { regionCandidates(img, raw) } catch (_: Throwable) {}
        val total = (w * h).toFloat()
        val cands = ArrayList<Cand>()
        val feats = HashMap<String, FloatArray>()
        for (c0 in raw) {
            val c = Quad.order(c0)
            if (!convex(c)) continue
            val a = Quad.area(c) / total
            if (a < minA || a > 0.97f) continue
            cands.add(Cand(c, a, feats.getOrPut(key(c)) { features(img, c) }))
        }
        val gt = Gather(g, argb, w, h, cands, img)
        if (refineAll) for (c in cands) refinedOf(gt, c.q)
        return gt
    }

    /** Hasil [refine] (jangkauan 6) kandidat [q] beserta fiturnya, atau null bila bentuknya tak layak. Di-memo. */
    private fun refinedOf(gt: Gather, q: FloatArray): Cand? = gt.memo.getOrPut("R" + key(q)) {
        val r = refine(gt.img, q, 6)
        val a = Quad.area(r) / (gt.w * gt.h).toFloat()
        if (!convex(r) || a < 0.012f || a > 0.97f) null else Cand(r, a, featOf(gt, r))
    } as Cand?

    /** Fitur [q] dengan memo per foto. */
    private fun featOf(gt: Gather, q: FloatArray): FloatArray =
        gt.memo.getOrPut("F" + key(q)) { features(gt.img, q) } as FloatArray

    private fun scoreQ(gt: Gather, q: FloatArray, P: FloatArray): Float = scoreFeat(featOf(gt, q), P)

    /** Pilih dokumen dari kandidat [gt] dengan bobot [P]. */
    fun select(gt: Gather, P: FloatArray = W): Result? {
        val w = gt.w; val h = gt.h
        val scored = ArrayList<Scored>()
        for (c in gt.cands) {
            val s = scoreFeat(c.feat, P)
            if (c.feat[3] >= 0f) debugFeat?.invoke(c.q, c.feat)
            debug?.invoke(c.q, s)
            if (s > 0f) scored.add(Scored(c.q, s, edgeOf(c.feat, P), c.area, c.feat[5] < 70f, c.feat))
        }
        if (scored.isEmpty()) return null
        // 10 kandidat teratas dirapikan (tiap ujung sisi digeser sendiri, jadi kemiringan sisi ikut dibetulkan) lalu dinilai ulang
        for (c in scored.sortedByDescending { it.s }.take(10)) {
            val r = refinedOf(gt, c.q) ?: continue
            val s = scoreFeat(r.feat, P)
            if (r.feat[3] >= 0f) debugFeat?.invoke(r.q, r.feat)
            debug?.invoke(r.q, s)
            if (s > c.s) scored.add(Scored(r.q, s, edgeOf(r.feat, P), r.area, r.feat[5] < 70f, r.feat))
        }
        var win: Scored = scored.maxByOrNull { it.s } ?: return null
        // Kebalikan aturan "lebih besar" di bawah: pemenang berupa wilayah CAMPURAN (struk + tangan + latar ramai) yang
        // MEMUAT satu lembar bersih bertepi lurus & bertulisan → lembar itulah dokumennya (struk kecil yang dipegang).
        if (win.f[3] in 0f..MESSY) {
            val cur = win
            val inner = scored.filter {
                it.area < cur.area * 0.8f && it.edge >= 0.7f && it.f[3] >= cur.f[3] + 0.3f && it.f[11] >= cur.f[11] &&
                    it.f[9] >= 0.03f && it.s >= cur.s * rr(0.5f, P) && contains(cur.q, it.q, 0.02f * max(w, h))
            }.maxByOrNull { it.s }
            if (inner != null) {
                debugParts?.invoke("INNER %s s=%.3f hom=%.2f al=%.2f".format(key(inner.q), inner.s, inner.f[3], inner.f[11]))
                win = inner
            }
        }
        // Wilayah yang salah satu sisinya bukan tepi lurus nyata (koran + permukaan meja di sekitarnya) MEMUAT lembar
        // yang keempat sisinya tepi lurus nyata, isinya lebih seragam & bertulisan, nilainya hampir sama → lembar itulah
        // dokumennya — asal hampir seri, tidak kecil, dan tidak lebih jauh dari tengah. Isi dokumen (foto, tabel) jarang
        // lolos: biasanya lebih ramai, bukan lebih seragam, dari kertasnya.
        if (win.f[11] < 1f - INNER_AL) {
            val cur = win
            val inner = scored.filter {
                it.area < cur.area * 0.9f && it.area > cur.area * 0.45f && it.edge >= 0.85f &&
                    it.f[11] >= max(0.8f, cur.f[11] + INNER_AL) && it.f[3] >= cur.f[3] + 0.1f && it.f[9] >= 0.03f &&
                    it.f[8] <= cur.f[8] + 0.05f && it.s >= cur.s * rr(0.95f, P) && contains(cur.q, it.q, 0.02f * max(w, h))
            }.maxByOrNull { it.s }
            if (inner != null) {
                debugParts?.invoke("ALIGNED %s s=%.3f hom=%.2f al=%.2f".format(key(inner.q), inner.s, inner.f[3], inner.f[11]))
                win = inner
            }
        }
        // Isi dokumen (foto KTP, pita judul, kotak QR) juga berupa segi-empat bertepi tegas. Bila ada kandidat yang
        // lebih besar, MEMUAT pemenang, tepinya sama kuat dan nilainya hampir sama, itulah dokumennya.
        var changed = true
        while (changed) {
            changed = false
            val cur = win
            val bigger = scored.filter {
                it.area > cur.area * 1.05f && it.edge >= 0.7f && contains(it.q, cur.q, 0.025f * max(w, h)) &&
                    // sisi-sisi yang lebih besar harus tetap tepi lurus yang nyata — bukan batas kabur silau/latar terang di
                    // samping struk yang dipegang (yang juga "terang seperti kertas")
                    it.f[11] >= cur.f[11] - ALIGN_DROP &&
                    // hampir seri tapi lebih besar & tepinya sama tegas: dokumen utuh (pita judul faktur ikut)
                    ((it.edge >= 0.85f && it.edge >= cur.edge - 0.05f && it.s >= cur.s * rr(0.92f, P)) ||
                     (it.area > cur.area * 1.25f && it.edge >= 0.85f && it.s >= cur.s * rr(if (it.edge >= cur.edge + 0.1f) 0.5f else 0.8f, P)) ||
                        // halaman sebelah di buku terbuka: tambahan wilayahnya juga kertas → ambil dua halaman,
                        // lalu SpreadSplit yang memutuskan halaman mana yang dituju
                        (it.area > cur.area * 1.25f && it.s >= cur.s * rr(0.45f, P) &&
                            gt.memo.getOrPut("X" + key(it.q) + "|" + key(cur.q)) { extraIsPaper(gt.img, it.q, cur.q) } as Boolean))
            }.maxByOrNull { it.s }
            if (bigger != null) {
                debugParts?.invoke("BIGGER %s s=%.3f hom=%.2f al=%.2f".format(key(bigger.q), bigger.s, bigger.f[3], bigger.f[11]))
                win = bigger; changed = true
            }
        }
        // Buku terbuka: kedua halaman sering terdeteksi TERPISAH (punggung buku memutus wilayah). Bila ada halaman
        // tetangga sewarna yang menempel pada pemenang, gabungkan; SpreadSplit lalu memilih halaman yang dituju.
        try {
            val pair = pairPage(gt, win, scored, P)
            if (pair != null) { win = pair; debugParts?.invoke("PAIR merged " + pair.q.joinToString(",") { it.toInt().toString() }) }
        } catch (e: Throwable) { debugParts?.invoke("PAIR merged ERR $e") }
        var q = win.q
        val bestS = win.s
        // Warna nyaru: yang tertangkap sering hanya blok tulisan (tepinya paling tegas). Bila di luar sisi-sisinya
        // masih "kertas" (warna sama dengan kertas), dorong sisi itu sampai kertasnya habis.
        try {
            val ex = gt.memo.getOrPut("E" + key(q)) { expandToPaper(gt.img, q) } as FloatArray?
            if (ex != null && convex(ex)) {
                val fe = featOf(gt, ex)
                val se = scoreFeat(fe, P); val ee = edgeOf(fe, P)
                debugParts?.invoke("EXPAND s=%.3f e=%.2f hom=%.2f/%.2f".format(se, ee, fe[3], featOf(gt, q)[3]))
                if (ee >= 0.45f && se >= bestS * rr(0.5f, P)) q = ex
            }
        } catch (_: Throwable) {}
        val q0 = q
        val refined = gt.memo.getOrPut("r" + key(q0)) { refine(gt.img, q0, 3) } as FloatArray
        val out = if (convex(refined) && scoreQ(gt, refined, P) >= scoreQ(gt, q, P) * 0.97f) refined else q
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
            ScanCore.closeGray(g, w, h, 4), minFilter(g, w, h, 2), minFilter(g, w, h, 2, true)
        )
    }

    /** Minimum (atau maksimum bila [max]) lokal kotak (2r+1)². */
    private fun minFilter(src: IntArray, w: Int, h: Int, r: Int, max: Boolean = false): IntArray {
        val t = IntArray(w * h); val o = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = if (max) 0 else 255
            for (d in max(0, x - r)..min(w - 1, x + r)) v = if (max) max(v, src[y * w + d]) else min(v, src[y * w + d])
            t[y * w + x] = v
        }
        for (y in 0 until h) for (x in 0 until w) {
            var v = if (max) 0 else 255
            for (d in max(0, y - r)..min(h - 1, y + r)) v = if (max) max(v, t[d * w + x]) else min(v, t[d * w + x])
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

    /**
     * Kesejajaran tepi satu sisi: pecahan titik sepanjang sisi yang gradien warnanya kuat DAN tegak lurus sisi — tepi
     * lurus yang nyata (pinggir kertas/papan). Garis sembarang di latar ramai (rangka plafon, orang, rak) memang sering
     * melewati beda warna, tapi arah gradiennya acak. -1 bila sisi berimpit dengan pinggir foto (netral).
     */
    private fun sideAlign(img: Img, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val w = img.w; val h = img.h
        val dx = x1 - x0; val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy); if (len < 4f) return 0f
        if ((max(x0, x1) < 2.5f) || (min(x0, x1) > w - 3.5f) || (max(y0, y1) < 2.5f) || (min(y0, y1) > h - 3.5f)) return -1f
        val nx = -dy / len; val ny = dx / len
        val steps = max(8, (len / 3f).toInt())
        var hit = 0; var cnt = 0
        for (s in 0 until steps) {
            val t = 0.08f + 0.84f * s / (steps - 1)
            val px = x0 + dx * t; val py = y0 + dy * t
            var bm = 0f; var ok = false; var any = false
            for (o in ALIGN_OFF) {
                val xi = (px + nx * o + 0.5f).toInt(); val yi = (py + ny * o + 0.5f).toInt()
                if (xi < 1 || yi < 1 || xi >= w - 1 || yi >= h - 1) continue
                any = true
                val i = yi * w + xi
                // kanal dengan beda terbesar: tepi merah di atas abu-abu yang sama terangnya tetap terlihat
                var gx = 0f; var gy = 0f; var m2 = -1f
                for (ch in 0 until 3) {
                    val a = when (ch) { 0 -> img.r; 1 -> img.gr; else -> img.b }
                    val cx = (a[i + 1] - a[i - 1]).toFloat(); val cy = (a[i + w] - a[i - w]).toFloat()
                    val v = cx * cx + cy * cy
                    if (v > m2) { m2 = v; gx = cx; gy = cy }
                }
                val m = sqrt(m2)
                if (m > bm) { bm = m; ok = m >= 10f && abs(gx * nx + gy * ny) >= 0.85f * m }
            }
            if (!any) continue
            cnt++; if (ok) hit++
        }
        return if (cnt == 0) -1f else hit.toFloat() / cnt
    }

    private val ALIGN_OFF = floatArrayOf(-1.5f, -0.5f, 0.5f, 1.5f)

    /** Batas turunnya kesejajaran sisi terlemah saat pemenang diganti kandidat yang lebih besar / diperluas. */
    @JvmField var ALIGN_DROP = 0.17f

    /** Keseragaman isi di bawah ini = wilayah campuran beberapa benda, bukan satu lembar dokumen. */
    @JvmField var MESSY = 0.63f

    /** Selisih kesejajaran sisi terlemah minimum agar lembar bertepi nyata di dalam pemenang menggantikannya. */
    @JvmField var INNER_AL = 0.4f

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

    /**
     * Fitur mentah kandidat [q] (tidak bergantung bobot): [mn, mean, nb, homog, paperF, median, kontras, luas,
     * jarak-tengah, tinta, kesejajaran-rata2, kesejajaran-min]. homog = -1 bila 3-4 sisi menempel bingkai atau isinya
     * terlalu sedikit untuk dinilai.
     */
    private fun features(img: Img, q: FloatArray): FloatArray {
        val w = img.w; val h = img.h
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val sup = FloatArray(4) { k -> val j = (k + 1) % 4; sideSupport(img, q[2 * k], q[2 * k + 1], q[2 * j], q[2 * j + 1], cx, cy) }
        val mn = sup.min(); val mean = sup.average().toFloat()
        // 3-4 sisi menempel bingkai = latar/seluruh foto, bukan dokumen; 2 sisi = dokumen besar terpotong (agak ragu)
        val nb = sup.count { it == BORDER }
        // 3-4 sisi di bingkai, atau tepi terlalu lemah untuk bobot apa pun (nilai tepi ≤ rata-rata sisi): tak perlu isi
        if (nb >= 3 || mean < 0.25f) return floatArrayOf(mn, mean, nb.toFloat(), -1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val al = FloatArray(4) { k -> val j = (k + 1) % 4; sideAlign(img, q[2 * k], q[2 * k + 1], q[2 * j], q[2 * j + 1]) }
        var aSum = 0f; var aN = 0; var aMin = 1f
        for (v in al) if (v >= 0f) { aSum += v; aN++; aMin = min(aMin, v) }
        val aMean = if (aN > 0) aSum / aN else 0f
        if (aN == 0) aMin = 0f

        // isi: seragam (pada gambar abu-abu yang teksnya ditutup) dan seperti kertas
        val shr = FloatArray(8) { i -> if (i % 2 == 0) cx + (q[i] - cx) * 0.85f else cy + (q[i] - cy) * 0.85f }
        val bb = Quad.bbox(shr)
        val step = max(1, (max(bb[2] - bb[0], bb[3] - bb[1]) / 28f).toInt())
        val vals = ArrayList<Int>(900)
        var paper = 0; var inkN = 0; var sumR = 0L; var sumG = 0L; var sumB = 0L
        var y = bb[1].toInt()
        while (y <= bb[3].toInt()) {
            var x = bb[0].toInt()
            while (x <= bb[2].toInt()) {
                if (x in 0 until w && y in 0 until h && inside(shr, x.toFloat(), y.toFloat())) {
                    val i = y * w + x
                    vals.add(img.closed[i])
                    if (sat(img, i) < 55 && img.closed[i] > 110) paper++
                    // ambang 18 (bukan 28): garis gambar & tulisan halus di label berwarna tetap terhitung isi
                    if (img.mx[i] - img.mn[i] >= 18) inkN++
                    sumR += img.cr[i]; sumG += img.cg[i]; sumB += img.cb[i]
                }
                x += step
            }
            y += step
        }
        if (vals.size < 12) return floatArrayOf(mn, mean, nb.toFloat(), -1f, 0f, 0f, 0f, 0f, 0f, 0f, aMean, aMin)
        vals.sort()
        val med = vals[vals.size / 2]
        var near = 0
        for (v in vals) if (abs(v - med) <= 28) near++
        val homog = near.toFloat() / vals.size
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
        // pusat foto: orang biasanya membidik dokumen di tengah
        val dcx = abs(cx / w - 0.5f); val dcy = abs(cy / h - 0.5f)
        val feat = floatArrayOf(mn, mean, nb.toFloat(), homog, paperF, med.toFloat(), contrast, area, dcx + dcy, inkN.toFloat() / vals.size, aMean, aMin)
        debugParts?.invoke("e=[%s] al=[%s] hom=%.2f pap=%.2f con=%.2f a=%.3f c=%.2f ink=%.3f".format(sup.joinToString("/") { "%.2f".format(it) }, al.joinToString("/") { "%.2f".format(it) }, homog, paperF, contrast, area, dcx + dcy, feat[9]))
        return feat
    }

    // ---------------------------------------------------------------- perapian

    /**
     * Kebalikan [expandToPaper]: bila di DALAM sisi ada jalur permukaan lain yang terang tapi BUKAN kertas (bodi laptop,
     * meja putih, halaman sebelah yang ronanya beda) lalu baru kertasnya, tarik sisi itu ke tepi kertas.
     * Hanya untuk kertas yang hampir tak berwarna (putih/krem) — kartu berwarna (KTP) dengan pita judul tidak disentuh.
     * [q] dalam piksel gambar kecil; mengembalikan [q] bila tak ada yang diubah.
     */
    fun trimToPaper(g: IntArray, argb: IntArray, w: Int, h: Int, q: FloatArray): FloatArray =
        try { trimToPaper(prepared(g, argb, w, h), q) } catch (_: Throwable) { q }

    /** [trimToPaper] memakai gambar olahan milik [gt] (tanpa menyiapkan ulang foto yang sama). */
    fun trimToPaper(gt: Gather, q: FloatArray): FloatArray = try { trimToPaper(gt.img, q) } catch (_: Throwable) { q }

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
        // Dokumen berwarna bertulisan putih (papan merah, sampul): yang paling terang hanya tulisannya, bukan kertasnya.
        if (paperShare(img, q, 0.5f, pR, pG, pB, paper) < SHARE) return q
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
     * Kertas putih di atas kain / sprei / papan putih, foto terlalu terang: kertas dan permukaan di sebelahnya sama-sama
     * putih jenuh, jadi wilayah yang tertangkap ikut membawa permukaan itu. Satu-satunya batas biasanya GARIS BAYANGAN
     * TIPIS di tepi kertas (lebih gelap beberapa tingkat saja) — terhapus oleh penutupan abu-abu yang dipakai penilai.
     * Untuk tiap sisi dicari garis lurus samar di dalamnya; sisi dipotong ke garis itu bila pita yang dibuang benar-benar
     * polos (tanpa tinta, seterang kertas) dan sisanya masih memuat tulisan. Maks. 2 sisi. Kasus nyata: lihat
     * tools/autocrop (real/user1.png, set gen8).
     */
    fun cutAtSeam(g: IntArray, argb: IntArray, w: Int, h: Int, q: FloatArray): FloatArray =
        try { cutAtSeam(prepared(g, argb, w, h), q) } catch (_: Throwable) { q }

    /** [cutAtSeam] memakai gambar olahan milik [gt]. */
    fun cutAtSeam(gt: Gather, q: FloatArray): FloatArray = try { cutAtSeam(gt.img, q) } catch (_: Throwable) { q }

    /** Persiapan foto terakhir dipakai ulang (trimToPaper lalu cutAtSeam memakai foto yang sama). */
    private var prepKey: IntArray? = null
    private var prepImg: Img? = null
    @Synchronized private fun prepared(g: IntArray, argb: IntArray, w: Int, h: Int): Img {
        prepImg?.let { if (prepKey === g && it.w == w && it.h == h) return it }
        return prepare(g, argb, w, h).also { prepKey = g; prepImg = it }
    }

    /**
     * Parameter [cutAtSeam] — HASIL PELATIHAN (tools/autocrop/SeamTrainer.kt, grid 288 kombinasi): dilatih pada set 6
     * (300 foto umum) + set 8 (160 foto putih-di-atas-putih), dipilih pada set 7 + 9 yang tidak ikut dilatih, dengan
     * syarat foto asli pengguna (real/) lolos & set umum tidak mundur.
     * [0] lekuk/undak minimum (tingkat abu-abu), [1] cakupan garis minimum, [2] lebar pita minimum (pecahan),
     * [3] tinta maksimum di pita, [4] tinta minimum di sisa dokumen.
     */
    @JvmField val SEAM = floatArrayOf(1.5f, 0.55f, 0.14f, 0.015f, 0.005f)

    private fun cutAtSeam(img: Img, q0: FloatArray): FloatArray {
        var q = q0
        val done = BooleanArray(4)
        repeat(2) {
            var best: FloatArray? = null; var bestV = 0f; var bestK = -1
            for (k in 0 until 4) {
                if (done[k]) continue
                val r = seamSide(img, q, k) ?: continue
                if (r.second > bestV) { bestV = r.second; best = r.first; bestK = k }
            }
            val b = best ?: return q
            if (!convex(b)) return q
            debugParts?.invoke("SEAM cut side=$bestK cov=%.2f".format(bestV))
            q = b; done[bestK] = true
        }
        return q
    }

    /** Garis samar terbaik sejajar sisi [k] (sudut k → k+1); hasil = segi-empat baru + cakupan garisnya. */
    private fun seamSide(img: Img, q: FloatArray, k: Int): Pair<FloatArray, Float>? {
        val w = img.w; val h = img.h
        val ax = q[2 * k]; val ay = q[2 * k + 1]
        val k1 = (k + 1) % 4; val k2 = (k + 2) % 4; val k3 = (k + 3) % 4
        val bx = q[2 * k1]; val by = q[2 * k1 + 1]
        val cx = q[2 * k2]; val cy = q[2 * k2 + 1]          // berseberangan dengan B
        val dx = q[2 * k3]; val dy = q[2 * k3 + 1]          // berseberangan dengan A
        val depth = max(sqrt((dx - ax) * (dx - ax) + (dy - ay) * (dy - ay)), sqrt((cx - bx) * (cx - bx) + (cy - by) * (cy - by)))
        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
        if (depth < 30f || len < 24f) return null
        val nt = depth.toInt()
        val mu = 40
        // titik P(u,t): t=0 di sisi k, t=1 di sisi seberang
        fun px(u: Float, t: Float) = (1 - t) * (ax + (bx - ax) * u) + t * (dx + (cx - dx) * u)
        fun py(u: Float, t: Float) = (1 - t) * (ay + (by - ay) * u) + t * (dy + (cy - dy) * u)
        fun gat(x: Float, y: Float): Int { val xi = x.toInt().coerceIn(0, w - 1); val yi = y.toInt().coerceIn(0, h - 1); return img.g[yi * w + xi] }
        // kecerahan kertas (persentil-90 abu-abu tertutup di isi)
        val c4s = ArrayList<Int>()
        for (j in 0 until 12) for (i in 0 until 12) {
            val x = px(0.1f + 0.8f * j / 11f, 0.1f + 0.8f * i / 11f).toInt(); val y = py(0.1f + 0.8f * j / 11f, 0.1f + 0.8f * i / 11f).toInt()
            if (x in 0 until w && y in 0 until h) c4s.add(img.c4[y * w + x])
        }
        if (c4s.size < 40) return null
        c4s.sort()
        val paper = c4s[c4s.size * 9 / 10]
        if (paper < 170) return null                        // hanya kertas terang
        val hit = Array(nt + 1) { BooleanArray(mu) }
        val ink = Array(nt + 1) { BooleanArray(mu) }
        val bright = Array(nt + 1) { BooleanArray(mu) }
        for (i in 0..nt) {
            val t = i.toFloat() / nt
            for (j in 0 until mu) {
                val u = 0.05f + 0.9f * j / (mu - 1)
                val x = px(u, t); val y = py(u, t)
                val xi = x.toInt(); val yi = y.toInt()
                if (xi !in 0 until w || yi !in 0 until h) continue
                val p = yi * w + xi
                ink[i][j] = img.mn[p] < paper - 45
                bright[i][j] = img.c4[p] >= paper - 20
                // arah tegak lurus garis = arah t (1 px)
                val ex = px(u, t + 1f / nt) - x; val ey = py(u, t + 1f / nt) - y
                val a = gat(x - 2 * ex, y - 2 * ey); val b = gat(x + 2 * ex, y + 2 * ey)
                val c = min(img.g[p], min(gat(x - ex, y - ey), gat(x + ex, y + ey)))
                val valley = (a + b) / 2f - c
                hit[i][j] = valley >= SEAM[0] || abs(a - b) >= SEAM[0] * 1.5f
            }
        }
        val iMin = max(6, (nt * SEAM[2]).toInt()); val iMax = (nt * 0.55f).toInt()
        if (iMax <= iMin) return null
        val dMax = max(2, (nt * 0.1f).toInt())
        // rekap tinta & kecerahan per baris t (pita = baris 1..garis-2)
        val inkRow = IntArray(nt + 1) { i -> ink[i].count { it } }
        val brightRow = IntArray(nt + 1) { i -> bright[i].count { it } }
        val inkAfter = IntArray(nt + 2)
        for (i in nt downTo 0) inkAfter[i] = inkAfter[i + 1] + inkRow[i]
        var best: FloatArray? = null; var bestCov = 0f; var bestT = 0f
        if (debugParts != null) {
            var mc = 0f; var a0 = 0; var a1 = 0
            for (i0 in iMin..iMax) for (i1 in max(iMin, i0 - dMax)..min(iMax, i0 + dMax)) { val c = lineCov(hit, ink, nt, mu, i0, i1); if (c > mc) { mc = c; a0 = i0; a1 = i1 } }
            val lo = min(a0, a1) - 2
            var inkN = 0; var brN = 0; var n = 0
            for (i in 1..lo) { inkN += inkRow[i]; brN += brightRow[i]; n += mu }
            debugParts?.invoke("SEAM? side=$k nt=$nt paper=$paper maxcov=%.2f at %d/%d band ink=%.4f bright=%.2f rest=%.3f".format(mc, a0, a1, inkN / max(1f, n.toFloat()), brN / max(1f, n.toFloat()), inkAfter[max(a0, a1) + 2].toFloat() / ((nt - max(a0, a1)) * mu).coerceAtLeast(1)))
        }
        for (i0 in iMin..iMax) for (i1 in max(iMin, i0 - dMax)..min(iMax, i0 + dMax)) {
            val cov = lineCov(hit, ink, nt, mu, i0, i1)
            if (cov < SEAM[1] || cov < bestCov - 1e-4f) continue
            // kertas bergaris / tabel: garis sejajar lain berulang di sisi dalam → ini garis cetak, bukan tepi kertas
            var rep = 0; var gap = 4
            while (max(i0, i1) + gap + 1 <= nt && rep < 2) {
                val o = gap
                if (lineCov(hit, ink, nt, mu, min(nt, i0 + o), min(nt, i1 + o)) >= SEAM[1]) { rep++; gap += 4 } else gap++
                if (gap > nt / 3) break
            }
            if (rep >= 2) continue
            // pita yang dibuang: polos & seterang kertas
            val lo = min(i0, i1) - 2
            var inkN = 0; var brN = 0; var n = 0
            for (i in 1..lo) { inkN += inkRow[i]; brN += brightRow[i]; n += mu }
            if (n == 0 || inkN > n * SEAM[3] || brN < n * 0.95f) continue
            // sisanya harus memuat tulisan
            val rest = inkAfter[max(i0, i1) + 2].toFloat() / ((nt - max(i0, i1)) * mu).coerceAtLeast(1)
            if (rest < SEAM[4]) continue
            val tm = (i0 + i1) / 2f
            if (cov > bestCov + 1e-4f || tm > bestT) {
                bestCov = cov; bestT = tm
                val t0 = i0.toFloat() / nt; val t1 = i1.toFloat() / nt
                val out = q.copyOf()
                out[2 * k] = px(0f, t0); out[2 * k + 1] = py(0f, t0)
                out[2 * k1] = px(1f, t1); out[2 * k1 + 1] = py(1f, t1)
                best = out
            }
        }
        return best?.let { it to bestCov }
    }

    /**
     * Cakupan garis (i0 di u=0 → i1 di u=1): pecahan sampel yang berupa lekuk/undak samar (±1 baris) dan TIDAK
     * bertinta di sekitarnya — garis tabel, garis buku tulis, dan ujung baris teks tidak dihitung.
     */
    private fun lineCov(hit: Array<BooleanArray>, ink: Array<BooleanArray>, nt: Int, mu: Int, i0: Int, i1: Int): Float {
        var c = 0
        for (j in 0 until mu) {
            val u = 0.05f + 0.9f * j / (mu - 1)
            val ii = (i0 + (i1 - i0) * u).toInt()
            var inked = false
            for (d in -3..3) if (ink[(ii + d).coerceIn(0, nt)][j]) { inked = true; break }
            if (inked) continue
            if (hit[ii][j] || hit[max(0, ii - 1)][j] || hit[min(nt, ii + 1)][j]) c++
        }
        return c.toFloat() / mu
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
        if (paperShare(img, q, 0.9f, pR, pG, pB, paper) < SHARE) return null
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

    /** Batas [paperShare]: di bawah ini "kertas" (bagian paling terang) bukan permukaan utama dokumen. */
    @JvmField var SHARE = 0.4f

    /**
     * Pecahan titik isi [q] (diperkecil ke [f] dari pusat) yang rona & terangnya seperti kertas ([pR],[pG],[pB], terang
     * [paper]). Kertas putih bertulisan ≈ 0,7–1 (teks sudah ditutup); papan merah bertulisan putih ≈ 0,1–0,3 — di sana
     * yang paling terang hanya tulisan/gambar putihnya, sehingga perluasan/pemotongan "ke tepi kertas" salah sasaran.
     */
    private fun paperShare(img: Img, q: FloatArray, f: Float, pR: Float, pG: Float, pB: Float, paper: Float): Float {
        val w = img.w; val h = img.h
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val inner = FloatArray(8) { i -> if (i % 2 == 0) cx + (q[i] - cx) * f else cy + (q[i] - cy) * f }
        val pm = max(20f, (pR + pG + pB) / 3f)
        val pcr = (pR - pm) * 200f / pm; val pcb = (pB - pm) * 200f / pm
        val bb = Quad.bbox(inner)
        val st = max(1f, max(bb[2] - bb[0], bb[3] - bb[1]) / 24f)
        var n = 0; var k = 0
        var y = bb[1]
        while (y <= bb[3]) {
            var x = bb[0]
            while (x <= bb[2]) {
                val xi = x.toInt(); val yi = y.toInt()
                if (xi in 0 until w && yi in 0 until h && inside(inner, x, y)) {
                    val i = yi * w + xi
                    n++
                    val m = max(20f, (img.cr[i] + img.cg[i] + img.cb[i]) / 3f)
                    val cr = (img.cr[i] - m) * 200f / m - pcr; val cb = (img.cb[i] - m) * 200f / m - pcb
                    if (img.c4[i] >= paper * 0.6f && cr * cr + cb * cb <= 144f) k++
                }
                x += st
            }
            y += st
        }
        return if (n < 20) 1f else k.toFloat() / n
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
