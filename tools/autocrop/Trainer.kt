import cloud.depup.office.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/** Satu kandidat: fitur mentah + IoU terhadap ground truth. */
class Cand(val f: FloatArray, val iou: Float)

fun iouMask(q: FloatArray, mk: java.awt.image.BufferedImage): Float {
    var inter = 0; var uni = 0
    for (yy in 0 until 100) for (xx in 0 until 100) {
        val a = inside(q, (xx + 0.5f) / 100, (yy + 0.5f) / 100)
        val b = (mk.getRGB(xx * mk.width / 100, yy * mk.height / 100) and 0xff) > 127
        if (a && b) inter++; if (a || b) uni++
    }
    return inter.toFloat() / maxOf(1, uni)
}

fun collect(files: List<Pair<File, (FloatArray) -> Float>>): List<List<Cand>> {
    val out = ArrayList<List<Cand>>()
    for ((f, gtIoU) in files) {
        val (s, _, _) = loadSmall(f)
        val L = ArrayList<Cand>()
        DocFind.debugFeat = { q, feat ->
            val qn = Quad.order(FloatArray(8) { if (it % 2 == 0) q[it] / s.w else q[it] / s.h })
            L.add(Cand(feat.copyOf(), gtIoU(qn)))
        }
        ScanCore.findDocument(s)
        DocFind.debugFeat = null
        out.add(L)
    }
    return out
}

fun scoreOf(f: FloatArray, P: FloatArray): Float {
    val mn = f[0]; val mean = f[1]; val nb = f[2].toInt(); val homog = f[3]; val paperF = f[4]; val med = f[5]
    val con = f[6]; val area = f[7]; val dc = f[8]
    if (nb >= 3) return 0f
    val edge = (P[1] * mn + (1 - P[1]) * mean) * (if (nb == 2) P[8] else 1f)
    if (edge < 0.25f) return 0f
    val darkF = if (med < 60) 0.45f else if (med < 90) 0.75f else 1f
    val centerF = 1f - P[7] * min(1f, dc)
    return edge.toDouble().pow(P[0].toDouble()).toFloat() * (1 - P[2] + P[2] * homog) * (1 - P[3] + P[3] * paperF) *
        darkF.toDouble().pow(P[4].toDouble()).toFloat() * (1 - P[5] + P[5] * con) * area.toDouble().pow(P[6].toDouble()).toFloat() * centerF
}

fun objective(data: List<List<Cand>>, P: FloatArray): Pair<Int, Float> {
    var ok = 0; var sum = 0f
    for (L in data) {
        var best: Cand? = null; var bs = 0f
        for (c in L) { val v = scoreOf(c.f, P); if (v > bs) { bs = v; best = c } }
        val i = best?.iou ?: 0f
        if (i >= 0.85f) ok++; sum += i
    }
    return ok to sum / data.size
}

object Trainer { @JvmStatic fun main(args: Array<String>) {
    val dir = File("/home/claude/crop")
    fun bigSet(cj: String, d: String) = Regex("\"file\": \"([^\"]+)\"").findAll(File(dir, cj).readText()).map { it.groupValues[1] }.map { n ->
        val mk = ImageIO.read(File(dir, "$d/" + n.replace(".jpg", "_mask.png")))
        File(dir, "$d/$n") to { q: FloatArray -> iouMask(q, mk) }
    }.toList()
    fun quadSet(cj: String = "cases.json", d: String = "imgs"): List<Pair<File, (FloatArray) -> Float>> =
        Regex("\\{\"file\": \"([^\"]+)\", \"quad\": \\[([^\\]]+)\\], \"w\": (\\d+), \"h\": (\\d+)\\}").findAll(File(dir, cj).readText()).map { m ->
            val w = m.groupValues[3].toFloat(); val h = m.groupValues[4].toFloat()
            val gt = m.groupValues[2].split(",").map { it.trim().toFloat() }.toFloatArray().let { q -> Quad.order(FloatArray(8) { if (it % 2 == 0) q[it] / w else q[it] / h }) }
            File(dir, "$d/" + m.groupValues[1]) to { q: FloatArray -> iou(q, gt) }
        }.toList()
    val train = collect(bigSet("cases6.json", "imgs6") + bigSet("cases3.json", "imgs3b") + quadSet() + quadSet("cases2.json", "imgs2"))
    val held = collect(bigSet("cases7.json", "imgs7"))
    val P0 = DocFind.W.copyOf()
    fun show(tag: String, P: FloatArray) {
        val (a, b) = objective(train, P); val (c, d) = objective(held, P)
        println("%-8s latih %d/%d (IoU %.3f) | uji %d/%d (IoU %.3f)  P=%s".format(tag, a, train.size, b, c, held.size, d, P.joinToString(",") { "%.3f".format(it) }))
    }
    show("awal", P0)
    // batas wajar tiap bobot
    val lo = floatArrayOf(0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f)
    val hi = floatArrayOf(4f, 1f, 0.95f, 0.95f, 4f, 0.95f, 1.2f, 0.9f, 1f)
    fun fit(P: FloatArray): Float { val (a, b) = objective(train, P); return a + 0.5f * b * train.size / 10f }
    var best = P0.copyOf(); var bestV = fit(best)
    val rnd = java.util.Random(7)
    repeat(6) { restart ->
        var P = if (restart == 0) P0.copyOf() else FloatArray(P0.size) { lo[it] + rnd.nextFloat() * (hi[it] - lo[it]) }
        var v = fit(P)
        var step = 0.25f
        while (step > 0.01f) {
            var improved = false
            for (i in P.indices) for (sgn in floatArrayOf(1f, -1f)) {
                val Q = P.copyOf(); Q[i] = (Q[i] + sgn * step * (hi[i] - lo[i])).coerceIn(lo[i], hi[i])
                val vq = fit(Q)
                if (vq > v + 1e-4f) { P = Q; v = vq; improved = true }
            }
            if (!improved) step /= 2
        }
        show("ulang$restart", P)
        if (v > bestV) { bestV = v; best = P }
    }
    show("TERBAIK", best)
}
}
