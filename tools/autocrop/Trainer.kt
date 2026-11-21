import cloud.depup.office.*
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Melatih bobot penilaian [DocFind.W] + ambang yakin [DocFind.MIN_SCORE] dengan ALUR PENUH di dalam lingkaran
 * pelatihan (bukan perkiraan "kandidat bernilai tertinggi"): kandidat & fiturnya disiapkan SEKALI per foto
 * ([ScanCore.prepareFind]), lalu tiap set bobot dinilai dengan [ScanCore.findWith] — termasuk pilih-yang-lebih-besar,
 * gabung halaman, perluas/potong ke tepi kertas, SpreadSplit, trimToPaper & cutAtSeam. Hasil tahap yang mahal
 * di-memo per foto, jadi satu penilaian seluruh set hanya butuh ±1 detik.
 *
 * Latih: set 6 (umum), 8 (putih di atas putih), 10 (berwarna & dipegang) + foto asli real/ (wajib lolos, bobot besar)
 * + foto contoh mock/ (label, kartu, brosur, koran di kardus/kain/kayu; poligon, bobot 4 — gambarnya tidak di-commit).
 * Pilih: set 7, 9, 11 (tidak ikut dilatih) + jumlah mock yang lolos. Coordinate descent dari bobot sekarang + beberapa titik awal acak di
 * sekitarnya. Kemunduran di set 6/8 dihukum lebih berat supaya perbaikan dokumen berwarna tidak merusak yang lama.
 *
 * Pemakaian: CROP=<folder data> java -Xmx3g -cp h.jar Trainer      (env R = jumlah titik awal, T = jumlah thread)
 */
object Trainer {
    class Item(val set: String, val prep: ScanCore.Prep, val gt: (FloatArray) -> Float)

    private fun iouMask(q: FloatArray, m: BooleanArray): Float {
        var inter = 0; var uni = 0
        for (yy in 0 until 200) for (xx in 0 until 200) {
            val a = inside(q, (xx + 0.5f) / 200, (yy + 0.5f) / 200); val b = m[yy * 200 + xx]
            if (a && b) inter++; if (a || b) uni++
        }
        return inter.toFloat() / maxOf(1, uni)
    }

    private fun maskGt(f: File): (FloatArray) -> Float {
        val mk = ImageIO.read(f)
        val m = BooleanArray(200 * 200) { i -> (mk.getRGB(i % 200 * mk.width / 200, i / 200 * mk.height / 200) and 0xff) > 127 }
        return { q -> iouMask(q, m) }
    }

    private fun quadGt(g: FloatArray): (FloatArray) -> Float = { q -> iou(q, g) }

    /** Poligon (boleh >4 sudut) + alternatif: IoU terbaik di antaranya. */
    private fun polyGt(gs: List<FloatArray>): (FloatArray) -> Float {
        val ms = gs.map { p -> BooleanArray(200 * 200) { i -> inside(p, (i % 200 + 0.5f) / 200, (i / 200 + 0.5f) / 200) } }
        return { q -> ms.maxOf { iouMask(q, it) } }
    }

    private val pool = Executors.newFixedThreadPool((System.getenv("T") ?: Runtime.getRuntime().availableProcessors().toString()).toInt())

    private fun <T> par(n: Int, f: (Int) -> T): List<T> {
        val k = (System.getenv("T") ?: Runtime.getRuntime().availableProcessors().toString()).toInt()
        val chunks = (0 until k).map { c -> Callable { (c until n step k).map { it to f(it) } } }
        return pool.invokeAll(chunks).flatMap { it.get() }.sortedBy { it.first }.map { it.second }
    }

    private fun load(dir: File): List<Item> {
        val jobs = ArrayList<Triple<String, File, Any>>()   // set, gambar, masker(File) | sudut(FloatArray)
        for ((tag, cj, d) in listOf(Triple("6", "cases6.json", "imgs6"), Triple("8", "cases8.json", "imgs8"),
                Triple("10", "cases10.json", "imgs10"), Triple("7", "cases7.json", "imgs7"),
                Triple("9", "cases9.json", "imgs9"), Triple("11", "cases11.json", "imgs11"))) {
            for (m in Regex("\"file\": \"([^\"]+)\"").findAll(File(dir, cj).readText()))
                jobs.add(Triple(tag, File(dir, "$d/${m.groupValues[1]}"), File(dir, "$d/" + m.groupValues[1].replace(".jpg", "_mask.png"))))
        }
        for (m in Regex("\\{\"file\": \"([^\"]+)\", \"quad\": \\[([^\\]]+)\\]").findAll(File(dir, "real/cases.json").readText()))
            jobs.add(Triple("asli", File(dir, "real/" + m.groupValues[1]), Quad.order(m.groupValues[2].split(",").map { it.trim().toFloat() }.toFloatArray())))
        if (File(dir, "mock/cases.json").exists()) for ((name, gts) in mockCases(File(dir, "mock")))
            if (File(dir, "mock/$name").exists()) jobs.add(Triple("mock", File(dir, "mock/$name"), gts))
        return par(jobs.size) { i ->
            val (tag, f, g) = jobs[i]
            val (s, _, _) = loadSmall(f)
            // set latih: gambar olahan disimpan & semua kandidat dirapikan dulu (penilaian ulang cepat);
            // set uji: hanya dinilai beberapa kali, gambar olahan dilepas supaya memori cukup
            val train = tag in TRAIN
            val prep = ScanCore.prepareFind(s, refineAll = train).also { if (!train) it.release() }
            @Suppress("UNCHECKED_CAST")
            Item(tag, prep, when (g) { is File -> maskGt(g); is FloatArray -> quadGt(g); else -> polyGt(g as List<FloatArray>) })
        }
    }

    private val TRAIN = setOf("6", "8", "10", "asli", "mock")

    /** P = [NW] bobot W + MIN_SCORE, ALIGN_DROP, MESSY, INNER_AL. IoU tiap foto. */
    private val NW = DocFind.W.size
    private fun eval(items: List<Item>, P: FloatArray): FloatArray {
        DocFind.MIN_SCORE = P[NW]; DocFind.ALIGN_DROP = P[NW + 1]; DocFind.MESSY = P[NW + 2]; DocFind.INNER_AL = P[NW + 3]
        val W = P.copyOf(NW)
        val r = par(items.size) { i ->
            val it = items[i]
            val q = ScanCore.findWith(it.prep, W)
            if (it.set !in TRAIN) it.prep.release()
            it.gt(Quad.order(q ?: Quad.full()))
        }
        return r.toFloatArray()
    }

    @JvmStatic fun main(args: Array<String>) {
        val dir = File(System.getenv("CROP") ?: "/home/claude/crop")
        val t0 = System.currentTimeMillis()
        val items = load(dir)
        val sets = listOf("6", "8", "10", "asli", "mock", "7", "9", "11")
        val idx = sets.associateWith { s -> items.indices.filter { items[it].set == s } }
        println("dimuat ${items.size} foto dalam ${(System.currentTimeMillis() - t0) / 1000} s")
        val trainItems = items.filter { it.set in TRAIN }; val heldItems = items.filter { it.set !in TRAIN }
        fun okOf(v: FloatArray, s: String) = idx[s]!!.count { v[it] >= 0.85f }
        fun iouOf(v: FloatArray, s: String) = idx[s]!!.sumOf { v[it].toDouble() }.toFloat()
        // latih: 6 & 8 dihukum 1,5x (jangan merusak yang lama), foto asli 5x, foto contoh 4x
        fun fitTrain(v: FloatArray) = 1.5f * (okOf(v, "6") + okOf(v, "8")) + okOf(v, "10") + 5f * okOf(v, "asli") + 4f * okOf(v, "mock") +
            0.05f * (iouOf(v, "6") + iouOf(v, "8") + iouOf(v, "10") + 5f * iouOf(v, "asli") + 4f * iouOf(v, "mock"))
        fun fitHeld(v: FloatArray) = 1.5f * (okOf(v, "7") + okOf(v, "9")) + okOf(v, "11") + 0.05f * (iouOf(v, "7") + iouOf(v, "9") + iouOf(v, "11"))
        val cache = HashMap<String, FloatArray>()
        val hcache = HashMap<String, FloatArray>()
        fun pk(P: FloatArray) = P.joinToString(",") { "%.4f".format(it) }
        // hasil per foto dalam urutan [items]: bagian latih dari cache latih, bagian uji (bila diminta) dari cache uji
        fun ev(P: FloatArray, held: Boolean = false): FloatArray {
            val t = cache.getOrPut(pk(P)) { eval(trainItems, P) }
            val h = if (held) hcache.getOrPut(pk(P)) { eval(heldItems, P) } else null
            val out = FloatArray(items.size)
            var a = 0; var b = 0
            for ((i, it) in items.withIndex()) out[i] = if (it.set in TRAIN) t[a++] else (h?.get(b++) ?: 0f)
            return out
        }
        fun show(tag: String, P: FloatArray) {
            val v = ev(P, held = true)
            println("%-8s latih %.1f | uji %.1f | %s\n         P=%s".format(tag, fitTrain(v), fitHeld(v),
                sets.joinToString(" ") { "$it:${okOf(v, it)}/${idx[it]!!.size}" }, P.joinToString(",") { "%.3f".format(it) }))
        }
        val P0 = DocFind.W.copyOf() + floatArrayOf(DocFind.MIN_SCORE, DocFind.ALIGN_DROP, DocFind.MESSY, DocFind.INNER_AL)
        val t1 = System.currentTimeMillis(); show("awal", P0)
        println("penilaian pertama ${(System.currentTimeMillis() - t1) / 1000} s")
        // [0] pangkat tepi, [1] sisi terlemah, [2] keseragaman, [3] mirip kertas, [4] benda gelap, [5] kontras, [6] luas,
        // [7] jauh dari tengah, [8] 2 sisi di bingkai, [9] isi/tinta, [10] skala tinta, [11] kesejajaran rata-rata,
        // [12] kesejajaran sisi terlemah, [13] tinta pengganti lembar polos bersih, [14] MIN_SCORE, [15] ALIGN_DROP,
        // [16] MESSY, [17] INNER_AL
        val lo = floatArrayOf(0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0.01f, 0f, 0f, 0f, 0.02f, 0f, 0f, 0.2f)
        val hi = floatArrayOf(4f, 1f, 0.95f, 0.95f, 4f, 0.95f, 1.2f, 0.9f, 1f, 0.95f, 0.12f, 0.95f, 0.95f, 0.15f, 0.3f, 0.6f, 0.8f, 0.8f)
        val fixed = (System.getenv("FIX") ?: "").split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet()
        // titik awal ikut bersaing: hasil latih yang tidak lebih baik di set uji tidak dipakai
        var best = P0.copyOf()
        var bestH = ev(P0, held = true).let { fitHeld(it) + 4f * okOf(it, "mock") + (if (okOf(it, "asli") == idx["asli"]!!.size) 1000f else 0f) }
        var bestT = fitTrain(ev(P0))
        val rnd = java.util.Random(11)
        val restarts = (System.getenv("R") ?: "4").toInt()
        repeat(restarts) { restart ->
            // titik awal: bobot sekarang, lalu gangguan acak di sekitar yang terbaik sejauh ini
            var P = if (restart == 0) P0.copyOf() else FloatArray(P0.size) { i ->
                if (i in fixed) best[i] else (best[i] + (rnd.nextFloat() - 0.5f) * 0.6f * (hi[i] - lo[i])).coerceIn(lo[i], hi[i]) }
            var v = fitTrain(ev(P))
            var step = 0.2f
            while (step > 0.012f) {
                var improved = false
                for (i in P.indices) {
                    if (i in fixed) continue
                    for (sgn in floatArrayOf(1f, -1f)) {
                        val Q = P.copyOf(); Q[i] = (Q[i] + sgn * step * (hi[i] - lo[i])).coerceIn(lo[i], hi[i])
                        if (Q[i] == P[i]) continue
                        val vq = fitTrain(ev(Q))
                        if (vq > v + 1e-3f) {
                            P = Q; v = vq; improved = true
                            println("  [%d] %.1f  P[%d]=%.3f  (%d penilaian, %d s)".format(restart, v, i, Q[i], cache.size, (System.currentTimeMillis() - t0) / 1000))
                        }
                    }
                }
                if (!improved) step /= 2
            }
            show("ulang$restart", P)
            val e = ev(P, held = true)
            // pilih dengan set uji; foto asli wajib lolos semua
            val h = fitHeld(e) + 4f * okOf(e, "mock") + (if (okOf(e, "asli") == idx["asli"]!!.size) 1000f else 0f)
            if (h > bestH || (h == bestH && v > bestT)) { bestH = h; bestT = v; best = P }
        }
        show("TERBAIK", best)
        println("total ${(System.currentTimeMillis() - t0) / 1000} s")
        pool.shutdown()
    }
}
