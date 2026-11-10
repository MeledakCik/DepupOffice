import cloud.depup.office.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun loadSmall(f: File): Triple<ScanCore.Small, Int, Int> {
    val img = ImageIO.read(f)
    val s = ScanCore.SIDE_PX.toFloat() / maxOf(img.width, img.height)
    // Android createScaledBitmap(filter=true) ~ bilinear
    fun scale(src: BufferedImage, ww: Int, hh: Int): BufferedImage {
        val o = BufferedImage(ww, hh, BufferedImage.TYPE_INT_ARGB)
        val g = o.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, ww, hh, null); g.dispose(); return o
    }
    // sama dengan ScanAi.small: decode maks 1000 px, lalu perkecil bertahap (maks 1/2 per langkah)
    var cur: BufferedImage = img
    val d = 1000f / maxOf(img.width, img.height)
    if (d < 1f) cur = scale(img, (img.width * d).toInt(), (img.height * d).toInt())
    val w2 = (cur.width * (ScanCore.SIDE_PX.toFloat() / maxOf(cur.width, cur.height))).toInt()
    val h2 = (cur.height * (ScanCore.SIDE_PX.toFloat() / maxOf(cur.width, cur.height))).toInt()
    while (cur.width > w2 * 2 + 1 || cur.height > h2 * 2 + 1) cur = scale(cur, maxOf(w2, cur.width / 2), maxOf(h2, cur.height / 2))
    val out = if (cur.width != w2 || cur.height != h2) scale(cur, w2, h2) else cur
    val w = w2; val h = h2
    val px = IntArray(w * h); out.getRGB(0, 0, w, h, px, 0, w)
    return Triple(ScanCore.fromPixels(px, w, h), img.width, img.height)
}

fun inside(q: FloatArray, x: Float, y: Float): Boolean {
    var c = false
    for (i in 0 until 4) {
        val j = (i + 3) % 4
        val xi = q[2 * i]; val yi = q[2 * i + 1]; val xj = q[2 * j]; val yj = q[2 * j + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
    }
    return c
}

fun iou(a: FloatArray, b: FloatArray): Float {
    var inter = 0; var uni = 0
    val n = 200
    for (yy in 0 until n) for (xx in 0 until n) {
        val x = (xx + 0.5f) / n; val y = (yy + 0.5f) / n
        val ia = inside(a, x, y); val ib = inside(b, x, y)
        if (ia && ib) inter++; if (ia || ib) uni++
    }
    return if (uni == 0) 0f else inter.toFloat() / uni
}

/** Mode: "old" = alur aplikasi lama, "new" = ScanCore.findDocument */
fun main(args: Array<String>) {
    val mode = args.getOrElse(0) { "old" }
    System.getenv("W")?.let { w -> w.split(",").forEachIndexed { i, v -> DocFind.W[i] = v.toFloat() } }
    val set2 = args.getOrElse(1) { "" } == "2"
    if (args.getOrElse(1) { "" } == "3") { runMask(mode); return }
    if (args.getOrElse(1) { "" } == "6") { runBig(mode, "cases6.json", "imgs6", "6"); return }
    if (args.getOrElse(1) { "" } == "7") { runBig(mode, "cases7.json", "imgs7", "7"); return }
    val dir = File("/home/claude/crop")
    val cases = Regex("\\{\"file\": \"([^\"]+)\", \"quad\": \\[([^\\]]+)\\], \"w\": (\\d+), \"h\": (\\d+)\\}").findAll(File(dir, if (set2) "cases2.json" else "cases.json").readText()).toList()
    var ok = 0; var sum = 0f
    val outLines = StringBuilder()
    for (m in cases) {
        val name = m.groupValues[1]
        val w = m.groupValues[3].toFloat(); val h = m.groupValues[4].toFloat()
        val gt = m.groupValues[2].split(",").map { it.trim().toFloat() }.toFloatArray().let { q -> FloatArray(8) { if (it % 2 == 0) q[it] / w else q[it] / h } }
        val (s, _, _) = loadSmall(File(dir, (if (set2) "imgs2/" else "imgs/") + name))
        val t0 = System.nanoTime()
        val q: FloatArray = if (mode == "old") {
            ScanCore.detectQuad(s) ?: ScanCore.detectCrop(s)?.let { Quad.ofRect(it[0], it[1], it[2], it[3]) } ?: Quad.full()
        } else {
            ScanCore.findDocument(s) ?: Quad.full()
        }
        val ms = (System.nanoTime() - t0) / 1e6
        val v = iou(Quad.order(q), Quad.order(gt))
        sum += v; if (v >= 0.85f) ok++
        println("%-34s IoU %.2f %s  %4.0fms".format(name, v, if (v >= 0.85f) "OK " else "GAGAL", ms))
        outLines.append(name).append(' ').append(q.joinToString(",")).append('\n')
    }
    File(dir, "result_$mode${if (set2) "2" else ""}.txt").writeText(outLines.toString())
    println("== $mode: berhasil $ok/${cases.size}, rata-rata IoU %.3f".format(sum / cases.size))
}

/** Set 3: ground truth = masker kertas 200x200. IoU + "bocor" (pecahan hasil yang berisi latar). */
fun runMask(mode: String) {
    val dir = File("/home/claude/crop")
    val cases = Regex("\\{\"file\": \"([^\"]+)\"").findAll(File(dir, "cases3.json").readText()).map { it.groupValues[1] }.toList()
    var ok = 0; var sumI = 0f; var sumL = 0f; var sumC = 0f
    val outLines = StringBuilder()
    for (name in cases) {
        val (s, _, _) = loadSmall(File(dir, "imgs3b/$name"))
        val m = ImageIO.read(File(dir, "imgs3b/" + name.replace(".jpg", "_mask.png")))
        val q = Quad.order(if (mode == "old") (ScanCore.detectQuad(s) ?: ScanCore.detectCrop(s)?.let { Quad.ofRect(it[0], it[1], it[2], it[3]) } ?: Quad.full()) else (ScanCore.findDocument(s) ?: Quad.full()))
        var inter = 0; var uni = 0; var pred = 0; var gt = 0
        for (yy in 0 until 200) for (xx in 0 until 200) {
            val a = inside(q, (xx + 0.5f) / 200, (yy + 0.5f) / 200)
            val b = (m.getRGB(xx, yy) and 0xff) > 127
            if (a) pred++; if (b) gt++
            if (a && b) inter++; if (a || b) uni++
        }
        val iou = inter.toFloat() / maxOf(1, uni); val leak = (pred - inter).toFloat() / maxOf(1, pred); val cut = (gt - inter).toFloat() / maxOf(1, gt)
        sumI += iou; sumL += leak; sumC += cut; if (iou >= 0.85f) ok++
        println("%-34s IoU %.2f bocor %4.1f%% terpotong %4.1f%% %s".format(name, iou, leak * 100, cut * 100, if (iou >= 0.85f) "OK" else "GAGAL"))
        outLines.append(name).append(' ').append(q.joinToString(",")).append('\n')
    }
    File(dir, "result_${mode}3.txt").writeText(outLines.toString())
    val n = cases.size
    println("== $mode set3: berhasil $ok/$n, IoU %.3f, bocor rata2 %.1f%%, terpotong rata2 %.1f%%".format(sumI / n, sumL / n * 100, sumC / n * 100))
}

/** Benchmark besar (gen6): IoU vs masker + rincian per kondisi. */
fun runBig(mode: String, cj: String, dirName: String, tag: String) {
    val dir = File("/home/claude/crop")
    val re = Regex("\\{\"file\": \"([^\"]+)\", \"bg\": \"([^\"]+)\", \"kind\": \"([^\"]+)\", \"tags\": \\[([^\\]]*)\\]\\}")
    val cases = re.findAll(File(dir, cj).readText()).toList()
    var ok = 0; var sumI = 0f; var sumL = 0f
    val groups = LinkedHashMap<String, FloatArray>()   // [n, ok, sumIoU]
    fun add(k: String, iou: Float) { val g = groups.getOrPut(k) { FloatArray(3) }; g[0]++; if (iou >= 0.85f) g[1]++; g[2] += iou }
    val outLines = StringBuilder()
    val verbose = System.getenv("V") != null
    for (m in cases) {
        val name = m.groupValues[1]
        val (s, _, _) = loadSmall(File(dir, "$dirName/$name"))
        val mk = ImageIO.read(File(dir, "$dirName/" + name.replace(".jpg", "_mask.png")))
        val q = Quad.order(if (mode == "old") (ScanCore.detectQuad(s) ?: ScanCore.detectCrop(s)?.let { Quad.ofRect(it[0], it[1], it[2], it[3]) } ?: Quad.full()) else (ScanCore.findDocument(s) ?: Quad.full()))
        var inter = 0; var uni = 0; var pred = 0
        for (yy in 0 until 200) for (xx in 0 until 200) {
            val a = inside(q, (xx + 0.5f) / 200, (yy + 0.5f) / 200)
            val b = (mk.getRGB(xx, yy) and 0xff) > 127
            if (a) pred++
            if (a && b) inter++; if (a || b) uni++
        }
        val iou = inter.toFloat() / maxOf(1, uni); val leak = (pred - inter).toFloat() / maxOf(1, pred)
        sumI += iou; sumL += leak; if (iou >= 0.85f) ok++
        add("latar:" + m.groupValues[2], iou); add("jenis:" + m.groupValues[3], iou)
        for (t in m.groupValues[4].split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }) add("kondisi:$t", iou)
        if (verbose && iou < 0.85f) println("%-44s IoU %.2f bocor %4.1f%%".format(name, iou, leak * 100))
        outLines.append(name).append(' ').append("%.3f".format(iou)).append(' ').append(q.joinToString(",")).append('\n')
    }
    File(dir, "result_${mode}$tag.txt").writeText(outLines.toString())
    if (System.getenv("G") != null) for ((k, g) in groups.toSortedMap()) println("  %-22s %3d/%3d  IoU %.2f".format(k, g[1].toInt(), g[0].toInt(), g[2] / g[0]))
    val n = cases.size
    println("== $mode set$tag: berhasil $ok/$n (%.0f%%), IoU %.3f, bocor %.1f%%".format(ok * 100f / n, sumI / n, sumL / n * 100))
}
