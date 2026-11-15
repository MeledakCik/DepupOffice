import cloud.depup.office.*
import java.io.File
fun main(args: Array<String>) {
    val f = PdfFile.open(File(args[0]).readBytes(), args.getOrElse(1) { "" })
    for ((i, p) in f.pages().withIndex()) {
        val o = PdfLayout.page(f, p)
        println("== hal ${i + 1} ${o.w}x${o.h} glif=${o.glyphs.size} foto=${o.images.size} bentuk=${o.shapes.size}")
        // baris kasar
        val lines = o.glyphs.groupBy { Math.round(it.y) }.toSortedMap()
        for ((y, g) in lines) {
            val s = g.sortedBy { it.x }
            val sb = StringBuilder(); var lastX = -1f
            for (c in s) { if (lastX >= 0 && c.x - lastX > c.size * 0.2f) sb.append(' '); sb.append(c.text); lastX = c.x + c.w }
            val f0 = s[0].font
            println("  y=%4d x=%5.1f sz=%4.1f %s%s %s #%06X | %s".format(y, s[0].x, s[0].size, f0.family, (if (f0.bold) " B" else "") + (if (f0.italic) " I" else ""), "", s[0].color and 0xFFFFFF, sb))
        }
        for (im in o.images) println("  FOTO %.0f,%.0f-%.0f,%.0f jpeg=%s png=%s %dx%d".format(im.x0, im.y0, im.x1, im.y1, im.jpeg?.size, im.png?.size, im.pxW, im.pxH))
        for (sh in o.shapes.take(40)) println("  BENTUK rect=%s bbox=%s fill=%08X stroke=%08X lw=%.1f subs=%d".format(sh.isRect, sh.bbox.joinToString(",") { "%.0f".format(it) }, sh.fill, sh.stroke, sh.lineW, sh.subs.size))
    }
}
