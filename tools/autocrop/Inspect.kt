import cloud.depup.office.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Periksa satu foto: kandidat bernilai tertinggi (nilai, IoU terhadap sudut benar, fitur mentah) + kandidat terbaik
 * menurut IoU, hasil akhir, dan gambar overlay (hijau = benar, merah = hasil, kuning = kandidat berikutnya).
 * Pemakaian: java -cp h.jar InspectKt <foto> [8 pecahan sudut benar, dipisah koma] [overlay.png]
 * Env: N = jumlah kandidat dicetak, P = regex baris rincian (mis. "STAGE|TRIM side|EXPAND s"; "ALL" = semua),
 *      W / MS = bobot & ambang yakin pengganti.
 */
fun main(args: Array<String>) {
    System.getenv("W")?.let { w -> w.split(",").forEachIndexed { i, v -> DocFind.W[i] = v.toFloat() } }
    System.getenv("MS")?.let { DocFind.MIN_SCORE = it.toFloat() }
    val f = File(args[0])
    val gt = args.getOrNull(1)?.takeIf { it.isNotEmpty() }?.split(",")?.map { it.toFloat() }?.toFloatArray()?.let { Quad.order(it) }
    val (s, _, _) = loadSmall(f)
    val cands = ArrayList<Pair<FloatArray, Float>>()
    val feats = HashMap<Int, FloatArray>()
    var lastF: FloatArray? = null
    DocFind.debugFeat = { _, ft -> lastF = ft.copyOf() }
    DocFind.debug = { q, sc -> cands.add(q.copyOf() to sc); lastF?.let { feats[cands.size - 1] = it }; lastF = null }
    System.getenv("P")?.let { pf -> val re = Regex(pf); DocFind.debugParts = { if (pf == "ALL" || re.containsMatchIn(it)) println("   " + it) } }
    val r = ScanCore.findDocument(s)
    DocFind.debug = null; DocFind.debugParts = null; DocFind.debugFeat = null
    fun norm(q: FloatArray) = Quad.order(FloatArray(8) { if (it % 2 == 0) q[it] / s.w else q[it] / s.h })
    val top = cands.sortedByDescending { it.second }.take((System.getenv("N") ?: "12").toInt())
    println("kandidat=${cands.size}  w=${s.w} h=${s.h}")
    fun ft(p: Pair<FloatArray, Float>): String = feats[cands.indexOf(p)]?.joinToString(" ") { "%.2f".format(it) } ?: ""
    for (p in top) { val (q, sc) = p; println("  s=%.4f iou=%s  %s  [%s]".format(sc, gt?.let { "%.2f".format(iou(norm(q), it)) } ?: "-", norm(q).joinToString(",") { "%.2f".format(it) }, ft(p))) }
    // kandidat dengan IoU terbaik
    if (gt != null) {
        val best = cands.maxByOrNull { iou(norm(it.first), gt) }
        if (best != null) println("  TERBAIK-IoU: s=%.4f iou=%.2f rank=%d  %s  [%s]".format(best.second, iou(norm(best.first), gt), cands.sortedByDescending { it.second }.indexOf(best), norm(best.first).joinToString(",") { "%.2f".format(it) }, ft(best)))
    }
    println("HASIL: " + (r?.let { q -> (gt?.let { "iou=%.2f ".format(iou(Quad.order(q), it)) } ?: "") + q.joinToString(",") { "%.2f".format(it) } } ?: "null"))
    val out = args.getOrNull(2) ?: return
    val img = ImageIO.read(f)
    val W = 600; val H = img.height * W / img.width
    val o = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
    val g = o.createGraphics()
    g.drawImage(img, 0, 0, W, H, null)
    fun draw(q: FloatArray, c: Color, wd: Float) {
        g.color = c; g.stroke = BasicStroke(wd)
        val xs = IntArray(4) { (q[2 * it] * W).toInt() }; val ys = IntArray(4) { (q[2 * it + 1] * H).toInt() }
        g.drawPolygon(xs, ys, 4)
    }
    for ((q, _) in top.drop(1).take(5).reversed()) draw(norm(q), Color(255, 220, 0, 170), 1.5f)
    gt?.let { draw(it, Color.GREEN, 2.5f) }
    r?.let { draw(Quad.order(it), Color.RED, 2.5f) }
    g.dispose(); ImageIO.write(o, "png", File(out))
}
