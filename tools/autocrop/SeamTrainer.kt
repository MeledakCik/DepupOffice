import cloud.depup.office.*
import java.io.File
import javax.imageio.ImageIO

/**
 * Melatih parameter [DocFind.SEAM] (potong di garis tepi samar: kertas putih di atas kain/papan putih).
 * Hasil deteksi SEBELUM tahap itu disimpan sekali per foto, lalu tiap kombinasi parameter hanya menjalankan
 * [DocFind.cutAtSeam] — cepat. Latih: set 6 (umum) + 8 (putih di atas putih). Pilih: set 7 + 9 (tidak ikut dilatih).
 * Syarat: tidak boleh ada kemunduran berarti di set umum (6/7), dan foto asli (real/) wajib lolos.
 *
 * Pemakaian: CROP=<folder data> java -cp h.jar SeamTrainer
 */
object SeamTrainer {
    class Case(val s: ScanCore.Small, val pre: FloatArray?, val mask: BooleanArray?, val gt: FloatArray?)

    private fun iouMask(q: FloatArray, m: BooleanArray): Float {
        var inter = 0; var uni = 0
        for (yy in 0 until 100) for (xx in 0 until 100) {
            val a = inside(q, (xx + 0.5f) / 100, (yy + 0.5f) / 100); val b = m[(yy * 2) * 200 + xx * 2]
            if (a && b) inter++; if (a || b) uni++
        }
        return inter.toFloat() / maxOf(1, uni)
    }

    private fun load(dir: File, cj: String, d: String): List<Case> =
        Regex("\"file\": \"([^\"]+)\"").findAll(File(dir, cj).readText()).map { it.groupValues[1] }.map { n ->
            val (s, _, _) = loadSmall(File(dir, "$d/$n"))
            val mk = ImageIO.read(File(dir, "$d/" + n.replace(".jpg", "_mask.png")))
            val m = BooleanArray(200 * 200) { (mk.getRGB(it % 200, it / 200) and 0xff) > 127 }
            Case(s, pre(s), m, null)
        }.toList()

    private fun pre(s: ScanCore.Small): FloatArray? {
        ScanCore.seamCut = false
        val r = ScanCore.findDocument(s)
        ScanCore.seamCut = true
        return r
    }

    /** IoU tiap kasus setelah cutAtSeam, untuk tiap set parameter: hasil[p][kasus]. Foto di luar, parameter di dalam. */
    private fun evalAll(cs: List<Case>, Ps: List<FloatArray>): Array<FloatArray> {
        val out = Array(Ps.size) { FloatArray(cs.size) }
        for ((i, c) in cs.withIndex()) {
            val p = c.pre
            for ((k, P) in Ps.withIndex()) {
                for (j in P.indices) DocFind.SEAM[j] = P[j]
                val q = if (p == null) Quad.full() else {
                    val px = FloatArray(8) { if (it % 2 == 0) p[it] * c.s.w else p[it] * c.s.h }
                    val r = DocFind.cutAtSeam(c.s.g, c.s.argb, c.s.w, c.s.h, px)
                    Quad.order(FloatArray(8) { if (it % 2 == 0) r[it] / c.s.w else r[it] / c.s.h })
                }
                out[k][i] = if (c.mask != null) iouMask(q, c.mask) else iou(q, c.gt!!)
            }
        }
        return out
    }
    private fun eval(cs: List<Case>): FloatArray = evalAll(cs, listOf(DocFind.SEAM.copyOf()))[0]

    private fun ok(v: FloatArray) = v.count { it >= 0.85f }

    @JvmStatic fun main(args: Array<String>) {
        val dir = File(System.getenv("CROP") ?: "/home/claude/crop")
        val t0 = System.currentTimeMillis()
        val s6 = load(dir, "cases6.json", "imgs6"); val s8 = load(dir, "cases8.json", "imgs8")
        val s7 = load(dir, "cases7.json", "imgs7"); val s9 = load(dir, "cases9.json", "imgs9")
        val real = Regex("\\{\"file\": \"([^\"]+)\", \"quad\": \\[([^\\]]+)\\]").findAll(File(dir, "real/cases.json").readText()).map { m ->
            val (s, _, _) = loadSmall(File(dir, "real/" + m.groupValues[1]))
            Case(s, pre(s), null, Quad.order(m.groupValues[2].split(",").map { it.trim().toFloat() }.toFloatArray()))
        }.toList()
        println("dimuat dalam ${(System.currentTimeMillis() - t0) / 1000}s")
        val off = floatArrayOf(999f, 2f, 0.12f, 0f, 1f)   // praktis mati: ambang garis mustahil
        val P0 = DocFind.SEAM.copyOf()
        fun set(P: FloatArray) { for (i in P.indices) DocFind.SEAM[i] = P[i] }
        set(off)
        val b6 = eval(s6); val b7 = eval(s7); val b8 = eval(s8); val b9 = eval(s9)
        println("tanpa potong-garis: 6=%d/%d 7=%d/%d 8=%d/%d 9=%d/%d".format(ok(b6), s6.size, ok(b7), s7.size, ok(b8), s8.size, ok(b9), s9.size))
        // nilai: kenaikan di set putih, kemunduran di set umum dihukum 3x lipat (lebih baik melewatkan daripada merusak)
        fun fit(a6: FloatArray, a8: FloatArray): Float {
            var v = 0f
            for (i in a6.indices) { val d = a6[i] - b6[i]; v += if (d < 0) 3 * d else d }
            for (i in a8.indices) v += a8[i] - b8[i]
            return v + 0.5f * (ok(a8) - ok(b8)) - 1.5f * maxOf(0, ok(b6) - ok(a6))
        }
        data class R(val P: FloatArray, val f: Float, val r6: Int, val r8: Int)
        val grid = ArrayList<FloatArray>()
        for (th in floatArrayOf(1.5f, 2f, 2.5f, 3f)) for (cov in floatArrayOf(0.55f, 0.65f, 0.75f, 0.85f))
            for (band in floatArrayOf(0.06f, 0.1f, 0.14f)) for (inkTol in floatArrayOf(0.002f, 0.006f, 0.015f)) for (rest in floatArrayOf(0.005f, 0.02f))
                grid.add(floatArrayOf(th, cov, band, inkTol, rest))
        val g6 = evalAll(s6, grid); val g8 = evalAll(s8, grid)
        println("grid ${grid.size} selesai dalam ${(System.currentTimeMillis() - t0) / 1000}s")
        val res = grid.indices.map { R(grid[it], fit(g6[it], g8[it]), ok(g6[it]), ok(g8[it])) }.toMutableList()
        res.sortByDescending { it.f }
        println("-- 8 terbaik di set latih, diuji di set terpisah (7/9) + foto asli:")
        var pick: FloatArray? = null; var pickV = -1e9f
        for (r in res.take(8)) {
            set(r.P)
            val a7 = eval(s7); val a9 = eval(s9); val ar = eval(real)
            var hv = 0f
            for (i in a7.indices) { val d = a7[i] - b7[i]; hv += if (d < 0) 3 * d else d }
            for (i in a9.indices) hv += a9[i] - b9[i]
            val realOk = ok(ar) == real.size
            println("P=%s latih 6=%d 8=%d | uji 7=%d/%d 9=%d/%d asli=%d/%d nilai-uji=%.2f".format(
                r.P.joinToString(",") { "%.3f".format(it) }, r.r6, r.r8, ok(a7), s7.size, ok(a9), s9.size, ok(ar), real.size, hv))
            if (realOk && ok(a7) >= ok(b7) - 1 && hv > pickV) { pickV = hv; pick = r.P }
        }
        set(P0)
        println("TERPILIH: " + (pick?.joinToString(",") { "%.3f".format(it) } ?: "(tidak ada — pertahankan P0)"))
    }
}
