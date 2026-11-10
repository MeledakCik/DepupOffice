package cloud.depup.office

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * PPTX → HTML untuk PEMBACA: tiap slide digambar seukuran aslinya (diskalakan ke lebar layar) dengan latar, bentuk
 * dari master & layout, pewarisan placeholder (posisi + gaya teks bertingkat), teks (ukuran/warna/font tema, perataan,
 * bullet & penomoran, spasi), gambar (termasuk crop), bentuk preset (SVG), garis, grup, tabel, dan SmartArt (gambar
 * siap pakai). Murni Kotlin — diuji di JVM.
 */
object PptxHtml {
    private const val EMU_PT = 12700.0

    private class Theme(val colors: Map<String, Int>, val major: String, val minor: String, val fills: List<XNode>, val bgFills: List<XNode>, val lines: List<XNode>)

    private class Ctx(val pkg: OPackage) {
        val res = HashMap<String, ByteArray>()
        var theme = Theme(emptyMap(), "Calibri Light", "Calibri", emptyList(), emptyList(), emptyList())
        var clrMap: Map<String, String> = DEFAULT_MAP
        var rels: Map<String, OPackage.Rel> = emptyMap()
        var defaultText: XNode? = null
        var slideNo = 1
    }

    private val DEFAULT_MAP = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2", "accent1" to "accent1", "accent2" to "accent2",
        "accent3" to "accent3", "accent4" to "accent4", "accent5" to "accent5", "accent6" to "accent6", "hlink" to "hlink", "folHlink" to "folHlink")

    fun render(bytes: ByteArray, title: String): RenderedDoc = render(OPackage(bytes), title)

    fun render(pkg: OPackage, title: String): RenderedDoc {
        val presPath = pkg.mainPart() ?: "ppt/presentation.xml"
        val pres = pkg.xml(presPath) ?: throw IllegalArgumentException("presentation.xml tidak ada")
        val c = Ctx(pkg)
        val sz = pres.first("sldSz")
        val sw = (sz?.attr("cx")?.toLongOrNull() ?: 9144000L) / EMU_PT
        val sh = (sz?.attr("cy")?.toLongOrNull() ?: 6858000L) / EMU_PT
        c.defaultText = pres.first("defaultTextStyle")
        val presRels = pkg.rels(presPath)
        val slides = pres.first("sldIdLst")?.children("sldId")?.mapNotNull { s -> (s.attr("r:id") ?: s.attr("id"))?.let { presRels[it]?.target } } ?: emptyList()
        val body = StringBuilder()
        for ((i, path) in slides.withIndex()) {
            c.slideNo = i + 1
            body.append("<div class=\"sw\"><div class=\"slide\" style=\"width:${sw}pt;height:${sh}pt\">")
            try { slide(c, path, sw, sh, body) } catch (e: Throwable) { body.append("<div class=\"err\">Slide ini tidak dapat ditampilkan.</div>") }
            body.append("</div></div><div class=\"no\">").append(i + 1).append(" / ").append(slides.size).append("</div>")
        }
        if (slides.isEmpty()) body.append("<div class=\"err\">Presentasi ini tidak memiliki slide.</div>")
        val html = StringBuilder()
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
            .append(OxUtil.esc(title)).append("</title><style>").append(OxUtil.fontFaces("https://doc.local/_fonts/")).append(CSS)
            .append("@page{size:${sw}pt ${sh}pt;margin:0}")
            .append("</style></head><body>").append(body).append("<script>var SW=${sw * 4 / 3};").append(JS).append("</script></body></html>")
        return RenderedDoc(html.toString(), c.res, title, "pptx")
    }

    // ------------------------------------------------------------------ slide
    private class Layer(val tree: XNode, val rels: Map<String, OPackage.Rel>)

    private fun slide(c: Ctx, path: String, sw: Double, sh: Double, out: StringBuilder) {
        val pkg = c.pkg
        val sx = pkg.xml(path) ?: return
        val sRels = pkg.rels(path)
        val layoutPath = sRels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target
        val lx = layoutPath?.let { pkg.xml(it) }
        val lRels = layoutPath?.let { pkg.rels(it) } ?: emptyMap()
        val masterPath = lRels.values.firstOrNull { it.type.endsWith("/slideMaster") }?.target
        val mx = masterPath?.let { pkg.xml(it) }
        val mRels = masterPath?.let { pkg.rels(it) } ?: emptyMap()
        mRels.values.firstOrNull { it.type.endsWith("/theme") }?.let { loadTheme(c, it.target) }
        // peta warna
        val map = HashMap(DEFAULT_MAP)
        mx?.first("clrMap")?.attrs?.forEach { (k, v) -> if (!k.contains(':')) map[k] = v }
        (sx.first("clrMapOvr") ?: lx?.first("clrMapOvr"))?.first("overrideClrMapping")?.attrs?.forEach { (k, v) -> if (!k.contains(':')) map[k] = v }
        c.clrMap = map

        val sTree = sx.first("cSld")?.child("spTree")
        val lTree = lx?.first("cSld")?.child("spTree")
        val mTree = mx?.first("cSld")?.child("spTree")
        // latar
        val bg = sx.first("cSld")?.child("bg") ?: lx?.first("cSld")?.child("bg") ?: mx?.first("cSld")?.child("bg")
        val bgRels = when { sx.first("cSld")?.child("bg") != null -> sRels; lx?.first("cSld")?.child("bg") != null -> lRels; else -> mRels }
        c.rels = bgRels
        out.append("<div class=\"bg\" style=\"").append(bgCss(c, bg)).append("\"></div>")

        val placeholders = PhIndex(lTree, mTree)
        val showMaster = (lx?.first("sld")?.attr("showMasterSp") ?: lx?.kids?.firstOrNull()?.attr("showMasterSp")) != "0" &&
            sx.kids.firstOrNull()?.attr("showMasterSp") != "0"
        val txStyles = mx?.first("txStyles")
        if (showMaster && mTree != null) { c.rels = mRels; tree(c, mTree, out, null, placeholders, txStyles, layer = 2, xf = Xf.ID) }
        if (lTree != null) { c.rels = lRels; tree(c, lTree, out, null, placeholders, txStyles, layer = 1, xf = Xf.ID) }
        if (sTree != null) { c.rels = sRels; tree(c, sTree, out, null, placeholders, txStyles, layer = 0, xf = Xf.ID) }
    }

    /** Placeholder layout & master untuk pewarisan posisi/gaya. */
    private class PhIndex(val layout: XNode?, val master: XNode?) {
        fun find(tree: XNode?, type: String?, idx: String?): XNode? {
            if (tree == null) return null
            val all = tree.all("sp")
            fun ph(s: XNode) = s.first("nvPr")?.child("ph")
            if (idx != null) all.firstOrNull { ph(it)?.attr("idx") == idx }?.let { return it }
            val t = norm(type)
            return all.firstOrNull { ph(it) != null && norm(ph(it)?.attr("type")) == t }
        }
        fun norm(t: String?) = when (t) { null, "obj" -> "body"; "ctrTitle" -> "title"; "subTitle" -> "body"; else -> t }
    }

    /** Transformasi koordinat grup: x' = ox + (x - cx) * sx. */
    private class Xf(val ox: Double, val oy: Double, val cx: Double, val cy: Double, val sx: Double, val sy: Double) {
        fun x(v: Double) = ox + (v - cx) * sx
        fun y(v: Double) = oy + (v - cy) * sy
        companion object { val ID = Xf(0.0, 0.0, 0.0, 0.0, 1.0, 1.0) }
    }

    private fun emu(s: String?): Double = (s?.toLongOrNull() ?: 0L) / EMU_PT

    private fun tree(c: Ctx, t: XNode, out: StringBuilder, parentPh: XNode?, ph: PhIndex, txStyles: XNode?, layer: Int, xf: Xf) {
        for (k in t.kids) when (k.name) {
            "sp" -> shape(c, k, out, ph, txStyles, layer, xf)
            "pic" -> picture(c, k, out, layer, xf, ph)
            "cxnSp" -> shape(c, k, out, ph, txStyles, layer, xf)
            "grpSp" -> {
                val gx = k.path("grpSpPr", "xfrm")
                if (gx == null) { tree(c, k, out, null, ph, txStyles, layer, xf); continue }
                val ox = emu(gx.child("off")?.attr("x")); val oy = emu(gx.child("off")?.attr("y"))
                val ew = emu(gx.child("ext")?.attr("cx")); val eh = emu(gx.child("ext")?.attr("cy"))
                val cx = emu(gx.child("chOff")?.attr("x")); val cy = emu(gx.child("chOff")?.attr("y"))
                val cw = emu(gx.child("chExt")?.attr("cx")).takeIf { it > 0 } ?: ew; val ch = emu(gx.child("chExt")?.attr("cy")).takeIf { it > 0 } ?: eh
                val inner = Xf(ox, oy, cx, cy, if (cw > 0) ew / cw else 1.0, if (ch > 0) eh / ch else 1.0)
                // gabungkan dengan transformasi induk
                val comb = Xf(xf.x(inner.ox), xf.y(inner.oy), inner.cx, inner.cy, inner.sx * xf.sx, inner.sy * xf.sy)
                tree(c, k, out, null, ph, txStyles, layer, comb)
            }
            "graphicFrame" -> frame(c, k, out, layer, xf, ph, txStyles)
            "AlternateContent" -> {}
        }
    }

    private fun isPh(sp: XNode) = sp.first("nvPr")?.child("ph") != null

    // ------------------------------------------------------------------ bentuk
    private class Box(val x: Double, val y: Double, val w: Double, val h: Double, val rot: Double, val flipH: Boolean, val flipV: Boolean)

    private fun boxOf(xfrm: XNode?, xf: Xf): Box? {
        if (xfrm == null) return null
        val off = xfrm.child("off") ?: return null; val ext = xfrm.child("ext") ?: return null
        val x = xf.x(emu(off.attr("x"))); val y = xf.y(emu(off.attr("y")))
        val w = emu(ext.attr("cx")) * xf.sx; val h = emu(ext.attr("cy")) * xf.sy
        return Box(x, y, w, h, (xfrm.attr("rot")?.toLongOrNull() ?: 0L) / 60000.0, xfrm.attr("flipH") == "1", xfrm.attr("flipV") == "1")
    }

    private fun shape(c: Ctx, sp: XNode, out: StringBuilder, ph: PhIndex, txStyles: XNode?, layer: Int, xf: Xf) {
        val phNode = sp.first("nvPr")?.child("ph")
        if (phNode != null && layer > 0) return            // placeholder master/layout = cetakan, tidak digambar
        val phType = phNode?.attr("type"); val phIdx = phNode?.attr("idx")
        if (phType == "dt" || phType == "ftr" || phType == "sldNum") { if (sp.first("txBody")?.allText().isNullOrBlank()) return }
        val lPh = if (phNode != null) ph.find(ph.layout, phType, phIdx) else null
        val mPh = if (phNode != null) ph.find(ph.master, phType ?: lPh?.first("nvPr")?.child("ph")?.attr("type"), null) else null
        val spPr = sp.child("spPr")
        val box = boxOf(spPr?.child("xfrm"), xf) ?: boxOf(lPh?.path("spPr", "xfrm"), Xf.ID) ?: boxOf(mPh?.path("spPr", "xfrm"), Xf.ID) ?: return
        val prst = spPr?.child("prstGeom")?.attr("prst") ?: if (spPr?.child("custGeom") != null) "cust" else if (sp.name == "cxnSp") "line" else "rect"
        // isian & garis
        val styleNode = sp.child("style")
        val fill = fillOf(c, spPr, styleNode?.child("fillRef"), lPh?.child("spPr") ?: mPh?.child("spPr"))
        val line = lineOf(c, spPr?.child("ln"), styleNode?.child("lnRef"), lPh?.path("spPr", "ln") ?: mPh?.path("spPr", "ln"), prst)
        out.append("<div class=\"sp\" style=\"left:${box.x}pt;top:${box.y}pt;width:${box.w}pt;height:${box.h}pt;").append(transform(box)).append("\">")
        drawGeom(c, prst, box, fill, line, spPr, out)
        // teks
        val txBody = sp.child("txBody")
        if (txBody != null && txBody.allText().isNotBlank()) {
            val lvlSources = ArrayList<XNode?>()
            // urutan prioritas rendah → tinggi
            lvlSources.add(c.defaultText)
            val styleKind = when (ph.norm(phType ?: lPh?.first("nvPr")?.child("ph")?.attr("type"))) {
                "title" -> "titleStyle"; "body" -> if (phNode != null) "bodyStyle" else "otherStyle"; else -> "otherStyle"
            }
            lvlSources.add(txStyles?.child(if (phNode == null) "otherStyle" else styleKind))
            lvlSources.add(mPh?.first("txBody")?.child("lstStyle")); lvlSources.add(lPh?.first("txBody")?.child("lstStyle"))
            lvlSources.add(txBody.child("lstStyle"))
            val bodyPrs = listOf(mPh?.first("txBody")?.child("bodyPr"), lPh?.first("txBody")?.child("bodyPr"), txBody.child("bodyPr"))
            text(c, txBody, lvlSources, bodyPrs, box, styleNode?.child("fontRef"), out)
        }
        out.append("</div>")
    }

    private fun transform(b: Box): String {
        if (b.rot == 0.0 && !b.flipH && !b.flipV) return ""
        val sb = StringBuilder("transform:")
        if (b.rot != 0.0) sb.append("rotate(${b.rot}deg) ")
        if (b.flipH) sb.append("scaleX(-1) "); if (b.flipV) sb.append("scaleY(-1) ")
        return sb.append(';').toString()
    }

    private class Fill(val css: String?, val svg: String?, val img: String? = null)
    private class Line(val w: Double, val color: String?, val dash: String?, val headEnd: Boolean = false, val tailEnd: Boolean = false)

    private fun fillOf(c: Ctx, spPr: XNode?, fillRef: XNode?, inherit: XNode?): Fill {
        fun from(n: XNode?): Fill? {
            if (n == null) return null
            n.child("noFill")?.let { return Fill(null, "none") }
            n.child("solidFill")?.let { f -> color(c, f)?.let { return Fill(it, it) } }
            n.child("gradFill")?.let { g ->
                val stops = g.first("gsLst")?.children("gs")?.mapNotNull { s -> color(c, s)?.let { col -> ((s.attr("pos")?.toDoubleOrNull() ?: 0.0) / 1000) to col } } ?: emptyList()
                if (stops.isNotEmpty()) {
                    val ang = (g.child("lin")?.attr("ang")?.toLongOrNull() ?: 0L) / 60000.0 + 90
                    val css = "linear-gradient(${ang}deg," + stops.joinToString(",") { "${it.second} ${it.first}%" } + ")"
                    return Fill(css, stops.first().second)
                }
            }
            n.child("blipFill")?.let { b -> imgSrc(c, b.first("blip")?.attr("embed"))?.let { return Fill(null, null, it) } }
            n.child("pattFill")?.let { p -> color(c, p.child("fgClr"))?.let { return Fill(it, it) } }
            return null
        }
        from(spPr)?.let { return it }
        if (fillRef != null && (fillRef.attr("idx")?.toIntOrNull() ?: 0) > 0) {
            val col = color(c, fillRef)
            val idx = fillRef.attr("idx")!!.toInt()
            val styleFill = if (idx >= 1001) c.theme.bgFills.getOrNull(idx - 1001) else c.theme.fills.getOrNull(idx - 1)
            if (styleFill?.name == "noFill") return Fill(null, "none")
            if (col != null) return Fill(col, col)
        }
        return from(inherit) ?: Fill(null, "none")
    }

    private fun lineOf(c: Ctx, ln: XNode?, lnRef: XNode?, inherit: XNode?, prst: String): Line? {
        val src = ln ?: inherit
        if (src?.child("noFill") != null) return null
        var w = src?.attr("w")?.toLongOrNull()?.let { it / EMU_PT }
        var col = src?.child("solidFill")?.let { color(c, it) } ?: src?.child("gradFill")?.first("gs")?.let { color(c, it) }
        if (col == null && lnRef != null && (lnRef.attr("idx")?.toIntOrNull() ?: 0) > 0) {
            col = color(c, lnRef)
            if (w == null) w = c.theme.lines.getOrNull(lnRef.attr("idx")!!.toInt() - 1)?.attr("w")?.toLongOrNull()?.let { it / EMU_PT }
        }
        if (col == null) return null
        val dash = when (src?.child("prstDash")?.v) { "dash", "lgDash", "sysDash", "dashDot", "lgDashDot" -> "6 4"; "dot", "sysDot" -> "1.5 3"; else -> null }
        return Line(w ?: 0.75, col, dash, src?.child("headEnd")?.attr("type")?.let { it != "none" } ?: false, src?.child("tailEnd")?.attr("type")?.let { it != "none" } ?: false)
    }

    private fun drawGeom(c: Ctx, prst: String, b: Box, fill: Fill, line: Line?, spPr: XNode?, out: StringBuilder) {
        if (fill.svg == "none" && fill.img == null && line == null) return
        if (prst == "rect" || prst == "cust" && spPr?.first("pathLst") == null) {
            out.append("<div class=\"fl\" style=\"")
            if (fill.img != null) out.append("background:url('${fill.img}') center/100% 100% no-repeat;")
            else if (fill.css != null) out.append("background:${fill.css};")
            if (line != null) out.append("box-shadow:inset 0 0 0 ${line.w}pt ${line.color};")
            out.append("\"></div>")
            return
        }
        val w = max(b.w, 0.01); val h = max(b.h, 0.01)
        val adj = spPr?.first("avLst")?.children("gd")?.associate { (it.attr("name") ?: "") to (it.attr("fmla")?.substringAfter("val ")?.trim()?.toDoubleOrNull() ?: 0.0) } ?: emptyMap()
        val path = if (prst == "cust") custPath(spPr!!, w, h) else prstPath(prst, w, h, adj)
        val lw = line?.w ?: 0.0
        out.append("<svg class=\"fl\" viewBox=\"${-lw} ${-lw} ${w + 2 * lw} ${h + 2 * lw}\" style=\"left:${-lw}pt;top:${-lw}pt;width:${w + 2 * lw}pt;height:${h + 2 * lw}pt\" preserveAspectRatio=\"none\">")
        if (line?.tailEnd == true || line?.headEnd == true) out.append("<defs><marker id=\"ah\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"5\" markerHeight=\"5\" orient=\"auto-start-reverse\"><path d=\"M0 0L10 5L0 10z\" fill=\"${line.color}\"/></marker></defs>")
        val isLine = prst == "line" || prst.contains("Connector")
        val fillAttr = when { isLine || fill.img != null -> "none"; fill.svg == null || fill.svg == "none" -> "none"; else -> fill.svg }
        if (fill.img != null) out.append("<clipPath id=\"cp\"><path d=\"$path\"/></clipPath><image href=\"${fill.img}\" x=\"0\" y=\"0\" width=\"$w\" height=\"$h\" preserveAspectRatio=\"none\" clip-path=\"url(#cp)\"/>")
        out.append("<path d=\"$path\" fill=\"$fillAttr\"")
        if (line != null) {
            out.append(" stroke=\"${line.color}\" stroke-width=\"${line.w}\" vector-effect=\"non-scaling-stroke\"")
            line.dash?.let { out.append(" stroke-dasharray=\"$it\"") }
            if (line.tailEnd) out.append(" marker-end=\"url(#ah)\""); if (line.headEnd) out.append(" marker-start=\"url(#ah)\"")
        }
        out.append("/></svg>")
    }

    private fun f(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    internal fun prstPath(p: String, w: Double, h: Double, adj: Map<String, Double>): String {
        val ss = min(w, h)
        fun a(n: String, d: Double) = (adj[n] ?: d) / 100000.0
        fun poly(vararg pts: Double): String { val sb = StringBuilder("M${f(pts[0])} ${f(pts[1])}"); var i = 2; while (i < pts.size) { sb.append("L${f(pts[i])} ${f(pts[i + 1])}"); i += 2 }; return sb.append("Z").toString() }
        return when (p) {
            "ellipse", "flowChartConnector" -> "M0 ${f(h / 2)}A${f(w / 2)} ${f(h / 2)} 0 1 0 ${f(w)} ${f(h / 2)}A${f(w / 2)} ${f(h / 2)} 0 1 0 0 ${f(h / 2)}Z"
            "roundRect", "flowChartAlternateProcess" -> { val r = ss * a("adj", 16667.0); "M${f(r)} 0H${f(w - r)}Q${f(w)} 0 ${f(w)} ${f(r)}V${f(h - r)}Q${f(w)} ${f(h)} ${f(w - r)} ${f(h)}H${f(r)}Q0 ${f(h)} 0 ${f(h - r)}V${f(r)}Q0 0 ${f(r)} 0Z" }
            "round2SameRect" -> { val r = ss * a("adj1", 16667.0); "M${f(r)} 0H${f(w - r)}Q${f(w)} 0 ${f(w)} ${f(r)}V${f(h)}H0V${f(r)}Q0 0 ${f(r)} 0Z" }
            "snip1Rect" -> { val r = ss * a("adj", 16667.0); poly(0.0, 0.0, w - r, 0.0, w, r, w, h, 0.0, h) }
            "triangle", "flowChartExtract" -> poly(w * a("adj", 50000.0), 0.0, w, h, 0.0, h)
            "rtTriangle" -> poly(0.0, 0.0, w, h, 0.0, h)
            "diamond", "flowChartDecision" -> poly(w / 2, 0.0, w, h / 2, w / 2, h, 0.0, h / 2)
            "parallelogram", "flowChartInputOutput" -> { val o = ss * a("adj", 25000.0); poly(o, 0.0, w, 0.0, w - o, h, 0.0, h) }
            "trapezoid" -> { val o = ss * a("adj", 25000.0); poly(o, 0.0, w - o, 0.0, w, h, 0.0, h) }
            "pentagon", "homePlate" -> { val o = ss * a("adj", 50000.0); poly(0.0, 0.0, w - o, 0.0, w, h / 2, w - o, h, 0.0, h) }
            "chevron" -> { val o = ss * a("adj", 50000.0); poly(0.0, 0.0, w - o, 0.0, w, h / 2, w - o, h, 0.0, h, o, h / 2) }
            "hexagon" -> { val o = ss * a("adj", 25000.0); poly(o, 0.0, w - o, 0.0, w, h / 2, w - o, h, o, h, 0.0, h / 2) }
            "octagon" -> { val o = ss * a("adj", 29289.0); poly(o, 0.0, w - o, 0.0, w, o, w, h - o, w - o, h, o, h, 0.0, h - o, 0.0, o) }
            "rightArrow" -> { val t = h * a("adj1", 50000.0); val hd = ss * a("adj2", 50000.0); poly(0.0, (h - t) / 2, w - hd, (h - t) / 2, w - hd, 0.0, w, h / 2, w - hd, h, w - hd, (h + t) / 2, 0.0, (h + t) / 2) }
            "leftArrow" -> { val t = h * a("adj1", 50000.0); val hd = ss * a("adj2", 50000.0); poly(w, (h - t) / 2, hd, (h - t) / 2, hd, 0.0, 0.0, h / 2, hd, h, hd, (h + t) / 2, w, (h + t) / 2) }
            "upArrow" -> { val t = w * a("adj1", 50000.0); val hd = ss * a("adj2", 50000.0); poly((w - t) / 2, h, (w - t) / 2, hd, 0.0, hd, w / 2, 0.0, w, hd, (w + t) / 2, hd, (w + t) / 2, h) }
            "downArrow" -> { val t = w * a("adj1", 50000.0); val hd = ss * a("adj2", 50000.0); poly((w - t) / 2, 0.0, (w + t) / 2, 0.0, (w + t) / 2, h - hd, w, h - hd, w / 2, h, 0.0, h - hd, (w - t) / 2, h - hd) }
            "leftRightArrow" -> { val t = h * a("adj1", 50000.0); val hd = ss * a("adj2", 50000.0); poly(0.0, h / 2, hd, 0.0, hd, (h - t) / 2, w - hd, (h - t) / 2, w - hd, 0.0, w, h / 2, w - hd, h, w - hd, (h + t) / 2, hd, (h + t) / 2, hd, h) }
            "star5", "star4", "star6", "star8", "star10", "star12", "star16", "star24", "star7" -> {
                val n = p.removePrefix("star").toIntOrNull() ?: 5
                val inner = if (n == 5) 0.382 else if (n == 4) 0.38 else 0.6
                val sb = StringBuilder()
                for (k in 0 until n * 2) {
                    val ang = -Math.PI / 2 + k * Math.PI / n
                    val r = if (k % 2 == 0) 1.0 else inner
                    val x = w / 2 + cos(ang) * w / 2 * r; val y = h / 2 + sin(ang) * h / 2 * r
                    sb.append(if (k == 0) "M" else "L").append(f(x)).append(' ').append(f(y))
                }
                sb.append("Z").toString()
            }
            "plus", "mathPlus" -> { val t = ss * a("adj", 25000.0); poly(t, 0.0, w - t, 0.0, w - t, t, w, t, w, h - t, w - t, h - t, w - t, h, t, h, t, h - t, 0.0, h - t, 0.0, t, t, t) }
            "line", "straightConnector1" -> "M0 0L${f(w)} ${f(h)}"
            "bentConnector2" -> "M0 0H${f(w)}V${f(h)}"
            "bentConnector3" -> { val x = w * a("adj1", 50000.0); "M0 0H${f(x)}V${f(h)}H${f(w)}" }
            "curvedConnector3" -> { val x = w * a("adj1", 50000.0); "M0 0C${f(x)} 0 ${f(x)} ${f(h)} ${f(w)} ${f(h)}" }
            "flowChartTerminator" -> { val r = h / 2; "M${f(r)} 0H${f(w - r)}A${f(r)} ${f(r)} 0 0 1 ${f(w - r)} ${f(h)}H${f(r)}A${f(r)} ${f(r)} 0 0 1 ${f(r)} 0Z" }
            "can" -> { val ry = ss * a("adj", 25000.0) / 2; "M0 ${f(ry)}A${f(w / 2)} ${f(ry)} 0 0 1 ${f(w)} ${f(ry)}V${f(h - ry)}A${f(w / 2)} ${f(ry)} 0 0 1 0 ${f(h - ry)}Z" }
            "wedgeRectCallout", "wedgeRoundRectCallout" -> poly(0.0, 0.0, w, 0.0, w, h * 0.8, w * 0.4, h * 0.8, w * 0.2, h, w * 0.25, h * 0.8, 0.0, h * 0.8)
            "wedgeEllipseCallout", "cloudCallout", "cloud" -> "M0 ${f(h / 2)}A${f(w / 2)} ${f(h / 2)} 0 1 0 ${f(w)} ${f(h / 2)}A${f(w / 2)} ${f(h / 2)} 0 1 0 0 ${f(h / 2)}Z"
            "donut" -> { val t = ss * a("adj", 25000.0); "M0 ${f(h / 2)}A${f(w / 2)} ${f(h / 2)} 0 1 0 ${f(w)} ${f(h / 2)}A${f(w / 2)} ${f(h / 2)} 0 1 0 0 ${f(h / 2)}ZM${f(t)} ${f(h / 2)}A${f(w / 2 - t)} ${f(h / 2 - t)} 0 1 1 ${f(w - t)} ${f(h / 2)}A${f(w / 2 - t)} ${f(h / 2 - t)} 0 1 1 ${f(t)} ${f(h / 2)}Z" }
            "heart" -> "M${f(w / 2)} ${f(h * 0.25)}C${f(w * 0.5)} 0 0 0 0 ${f(h * 0.3)}C0 ${f(h * 0.6)} ${f(w * 0.4)} ${f(h * 0.8)} ${f(w / 2)} ${f(h)}C${f(w * 0.6)} ${f(h * 0.8)} ${f(w)} ${f(h * 0.6)} ${f(w)} ${f(h * 0.3)}C${f(w)} 0 ${f(w * 0.5)} 0 ${f(w / 2)} ${f(h * 0.25)}Z"
            else -> "M0 0H${f(w)}V${f(h)}H0Z"
        }
    }

    /** Geometri kustom (a:custGeom) → path SVG. */
    private fun custPath(spPr: XNode, w: Double, h: Double): String {
        val sb = StringBuilder()
        for (p in spPr.first("pathLst")?.children("path") ?: emptyList()) {
            val pw = p.attr("w")?.toDoubleOrNull()?.takeIf { it > 0 } ?: (w * EMU_PT); val phh = p.attr("h")?.toDoubleOrNull()?.takeIf { it > 0 } ?: (h * EMU_PT)
            fun X(n: XNode) = f((n.attr("x")?.toDoubleOrNull() ?: 0.0) / pw * w)
            fun Y(n: XNode) = f((n.attr("y")?.toDoubleOrNull() ?: 0.0) / phh * h)
            for (cmd in p.kids) when (cmd.name) {
                "moveTo" -> cmd.child("pt")?.let { sb.append("M").append(X(it)).append(' ').append(Y(it)) }
                "lnTo" -> cmd.child("pt")?.let { sb.append("L").append(X(it)).append(' ').append(Y(it)) }
                "cubicBezTo" -> { val pts = cmd.children("pt"); if (pts.size == 3) sb.append("C").append(pts.joinToString(" ") { X(it) + " " + Y(it) }) }
                "quadBezTo" -> { val pts = cmd.children("pt"); if (pts.size == 2) sb.append("Q").append(pts.joinToString(" ") { X(it) + " " + Y(it) }) }
                "close" -> sb.append("Z")
            }
        }
        return if (sb.isEmpty()) "M0 0H${f(w)}V${f(h)}H0Z" else sb.toString()
    }

    // ------------------------------------------------------------------ teks
    private class TP {
        var size: Double? = null; var b: Boolean? = null; var i: Boolean? = null; var u: Boolean? = null; var strike: Boolean? = null
        var color: String? = null; var font: String? = null; var caps: Boolean? = null; var baseline: Int? = null
        fun over(o: TP?): TP { if (o == null) return this; val r = TP()
            r.size = o.size ?: size; r.b = o.b ?: b; r.i = o.i ?: i; r.u = o.u ?: u; r.strike = o.strike ?: strike
            r.color = o.color ?: color; r.font = o.font ?: font; r.caps = o.caps ?: caps; r.baseline = o.baseline ?: baseline; return r }
    }
    private class PPr { var algn: String? = null; var marL: Double? = null; var indent: Double? = null; var lnPct: Double? = null; var lnPts: Double? = null
        var bef: Double? = null; var befPct: Double? = null; var aft: Double? = null; var bu: String? = null; var buNone: Boolean? = null; var buAuto: String? = null
        var buFont: String? = null; var buColor: String? = null; var buSz: Double? = null; var buStart: Int? = null
        fun over(o: PPr?): PPr { if (o == null) return this; val r = PPr()
            r.algn = o.algn ?: algn; r.marL = o.marL ?: marL; r.indent = o.indent ?: indent; r.lnPct = if (o.lnPct != null || o.lnPts != null) o.lnPct else lnPct
            r.lnPts = if (o.lnPct != null || o.lnPts != null) o.lnPts else lnPts; r.bef = if (o.bef != null || o.befPct != null) o.bef else bef
            r.befPct = if (o.bef != null || o.befPct != null) o.befPct else befPct; r.aft = o.aft ?: aft
            if (o.bu != null || o.buNone != null || o.buAuto != null) { r.bu = o.bu; r.buNone = o.buNone; r.buAuto = o.buAuto } else { r.bu = bu; r.buNone = buNone; r.buAuto = buAuto }
            r.buFont = o.buFont ?: buFont; r.buColor = o.buColor ?: buColor; r.buSz = o.buSz ?: buSz; r.buStart = o.buStart ?: buStart; return r }
    }

    private fun parseTP(c: Ctx, n: XNode?): TP? {
        if (n == null) return null
        val t = TP()
        n.attr("sz")?.toDoubleOrNull()?.let { t.size = it / 100 }
        n.attr("b")?.let { t.b = it == "1" || it == "true" }; n.attr("i")?.let { t.i = it == "1" || it == "true" }
        n.attr("u")?.let { t.u = it != "none" }; n.attr("strike")?.let { t.strike = it != "noStrike" }
        n.attr("cap")?.let { t.caps = it == "all" }; n.attr("baseline")?.toIntOrNull()?.let { t.baseline = it }
        n.child("solidFill")?.let { t.color = color(c, it) }
        n.child("gradFill")?.first("gs")?.let { t.color = color(c, it) }
        n.child("latin")?.attr("typeface")?.let { t.font = themeFont(c, it) }
        return t
    }

    private fun themeFont(c: Ctx, f: String): String = when { f.startsWith("+mj") -> c.theme.major; f.startsWith("+mn") -> c.theme.minor; else -> f }

    private fun parsePPr(c: Ctx, n: XNode?): PPr? {
        if (n == null) return null
        val p = PPr()
        n.attr("algn")?.let { p.algn = it }
        n.attr("marL")?.toLongOrNull()?.let { p.marL = it / EMU_PT }; n.attr("indent")?.toLongOrNull()?.let { p.indent = it / EMU_PT }
        n.child("lnSpc")?.let { l -> l.child("spcPct")?.v?.toDoubleOrNull()?.let { p.lnPct = it / 100000 }; l.child("spcPts")?.v?.toDoubleOrNull()?.let { p.lnPts = it / 100 } }
        n.child("spcBef")?.let { l -> l.child("spcPts")?.v?.toDoubleOrNull()?.let { p.bef = it / 100 }; l.child("spcPct")?.v?.toDoubleOrNull()?.let { p.befPct = it / 100000 } }
        n.child("spcAft")?.let { l -> l.child("spcPts")?.v?.toDoubleOrNull()?.let { p.aft = it / 100 } }
        n.child("buNone")?.let { p.buNone = true }
        n.child("buChar")?.let { p.bu = it.attr("char") ?: "•"; p.buNone = false }
        n.child("buAutoNum")?.let { p.buAuto = it.attr("type") ?: "arabicPeriod"; p.buStart = it.attr("startAt")?.toIntOrNull(); p.buNone = false }
        n.child("buFont")?.attr("typeface")?.let { p.buFont = it }
        n.child("buClr")?.let { p.buColor = color(c, it) }
        n.child("buSzPct")?.v?.toDoubleOrNull()?.let { p.buSz = it / 100000 }
        return p
    }

    private fun text(c: Ctx, tb: XNode, lvlSources: List<XNode?>, bodyPrs: List<XNode?>, box: Box, fontRef: XNode?, out: StringBuilder) {
        // bodyPr gabungan
        var anchor = "t"; var l = 7.2; var r = 7.2; var t = 3.6; var b = 3.6; var wrap = true; var scale = 1.0; var lnRed = 0.0; var vert: String? = null
        for (bp in bodyPrs) if (bp != null) {
            bp.attr("anchor")?.let { anchor = it }
            bp.attr("lIns")?.let { l = emu(it) }; bp.attr("rIns")?.let { r = emu(it) }; bp.attr("tIns")?.let { t = emu(it) }; bp.attr("bIns")?.let { b = emu(it) }
            bp.attr("wrap")?.let { wrap = it != "none" }
            bp.attr("vert")?.let { vert = it }
            bp.child("normAutofit")?.let { a -> scale = (a.attr("fontScale")?.toDoubleOrNull() ?: 100000.0) / 100000; lnRed = (a.attr("lnSpcReduction")?.toDoubleOrNull() ?: 0.0) / 100000 }
        }
        val just = when (anchor) { "ctr" -> "center"; "b" -> "flex-end"; else -> "flex-start" }
        out.append("<div class=\"tx\" style=\"padding:${t}pt ${r}pt ${b}pt ${l}pt;justify-content:$just;")
        if (!wrap) out.append("white-space:nowrap;")
        if (vert == "vert" || vert == "eaVert") out.append("writing-mode:vertical-rl;")
        if (vert == "vert270") out.append("writing-mode:vertical-rl;transform:rotate(180deg);")
        fontRef?.let { fr -> color(c, fr)?.let { out.append("color:$it;") } }
        out.append("\"><div>")
        val autoNum = HashMap<Int, Int>()
        for (p in tb.children("p")) {
            val pPr = p.child("pPr")
            val lvl = pPr?.attr("lvl")?.toIntOrNull() ?: 0
            var pp = PPr(); var tp = TP()
            for (src in lvlSources) {
                val lv = src?.child("lvl${lvl + 1}pPr") ?: continue
                pp = pp.over(parsePPr(c, lv)); tp = tp.over(parseTP(c, lv.child("defRPr")))
            }
            pp = pp.over(parsePPr(c, pPr)); tp = tp.over(parseTP(c, pPr?.child("defRPr")))
            if (tp.font == null) tp.font = c.theme.minor
            val runs = p.kids.filter { it.name == "r" || it.name == "br" || it.name == "fld" }
            val endTp = tp.over(parseTP(c, p.child("endParaRPr")))
            val size0 = (tp.size ?: 18.0) * scale
            val css = StringBuilder()
            css.append("text-align:").append(when (pp.algn) { "ctr" -> "center"; "r" -> "right"; "just", "dist" -> "justify"; else -> "left" }).append(';')
            val marL = pp.marL ?: 0.0; val ind = pp.indent ?: 0.0
            if (marL != 0.0) css.append("margin-left:${marL}pt;")
            if (ind != 0.0) css.append("text-indent:${ind}pt;")
            val lh = when { pp.lnPts != null -> "${pp.lnPts}pt"; else -> String.format(java.util.Locale.US, "%.3f", (pp.lnPct ?: 1.0) * (1 - lnRed) * 1.2) }
            css.append("line-height:$lh;")
            pp.bef?.let { css.append("margin-top:${it}pt;") } ?: pp.befPct?.let { css.append("margin-top:${it * size0 * 1.2}pt;") }
            pp.aft?.let { css.append("margin-bottom:${it}pt;") }
            css.append("font-size:${String.format(java.util.Locale.US, "%.2f", size0)}pt;")
            out.append("<p style=\"").append(css).append("\">")
            val hasText = runs.any { it.name != "br" && it.allText().isNotEmpty() }
            // bullet
            if (hasText && pp.buNone != true && (pp.bu != null || pp.buAuto != null)) {
                val label = if (pp.buAuto != null) {
                    val n = (autoNum[lvl] ?: ((pp.buStart ?: 1) - 1)) + 1; autoNum[lvl] = n
                    for (k in autoNum.keys.toList()) if (k > lvl) autoNum.remove(k)
                    autoLabel(pp.buAuto!!, n)
                } else OxUtil.symbolChar(pp.bu!![0], pp.buFont) + pp.bu!!.drop(1)
                val bsz = size0 * (pp.buSz ?: 1.0)
                val bcol = pp.buColor ?: tp.color
                out.append("<span class=\"bu\" style=\"min-width:${max(-ind, 0.0)}pt;font-size:${bsz}pt;")
                if (-ind < bsz * 0.9) out.append("padding-right:0.35em;")
                if (bcol != null) out.append("color:$bcol;")
                if (pp.buFont != null && !pp.buFont!!.lowercase().let { it.contains("wingding") || it.contains("symbol") }) out.append("font-family:${OxUtil.fontStack(themeFont(c, pp.buFont!!))};")
                else out.append("font-family:${OxUtil.fontStack(tp.font)};")
                out.append("\">").append(OxUtil.esc(label)).append("</span>")
            } else if (!hasText) autoNum.remove(lvl)
            for (rn in runs) {
                if (rn.name == "br") { out.append("<br>"); continue }
                var rtp = tp.over(parseTP(c, rn.child("rPr")))
                var txt = rn.child("t")?.text ?: ""
                if (rn.name == "fld" && rn.attr("type") == "slidenum") txt = c.slideNo.toString()
                if (txt.isEmpty()) continue
                out.append("<span style=\"").append(runCss(rtp, scale)).append("\">")
                val href = rn.child("rPr")?.child("hlinkClick")?.attr("id")?.let { c.rels[it]?.target }
                if (href != null) out.append("<a href=\"").append(OxUtil.esc(href)).append("\">")
                out.append(OxUtil.esc(txt))
                if (href != null) out.append("</a>")
                out.append("</span>")
            }
            if (!hasText) out.append("<span style=\"font-size:${String.format(java.util.Locale.US, "%.2f", (endTp.size ?: tp.size ?: 18.0) * scale)}pt\">&#8203;</span>")
            out.append("</p>")
        }
        out.append("</div></div>")
    }

    private fun runCss(t: TP, scale: Double): String {
        val sb = StringBuilder()
        t.size?.let { sb.append("font-size:").append(String.format(java.util.Locale.US, "%.2f", it * scale)).append("pt;") }
        sb.append("font-family:").append(OxUtil.fontStack(t.font)).append(';')
        if (t.b == true) sb.append("font-weight:bold;")
        if (t.i == true) sb.append("font-style:italic;")
        val deco = listOfNotNull(if (t.u == true) "underline" else null, if (t.strike == true) "line-through" else null)
        if (deco.isNotEmpty()) sb.append("text-decoration:").append(deco.joinToString(" ")).append(';')
        t.color?.let { sb.append("color:").append(it).append(';') }
        if (t.caps == true) sb.append("text-transform:uppercase;")
        t.baseline?.let { if (it > 0) sb.append("vertical-align:super;font-size:70%;") else if (it < 0) sb.append("vertical-align:sub;font-size:70%;") }
        return sb.toString()
    }

    private fun autoLabel(type: String, n: Int): String {
        val num = when {
            type.startsWith("alphaLc") -> DocxHtml.fmtNum(n, "lowerLetter"); type.startsWith("alphaUc") -> DocxHtml.fmtNum(n, "upperLetter")
            type.startsWith("romanLc") -> DocxHtml.fmtNum(n, "lowerRoman"); type.startsWith("romanUc") -> DocxHtml.fmtNum(n, "upperRoman")
            else -> n.toString()
        }
        return when {
            type.endsWith("ParenBoth") -> "($num)"; type.endsWith("ParenR") -> "$num)"; type.endsWith("Period") -> "$num."; type.endsWith("Plain") -> num
            else -> "$num."
        }
    }

    // ------------------------------------------------------------------ gambar, tabel, grafik
    private fun imgSrc(c: Ctx, rid: String?): String? {
        val rel = rid?.let { c.rels[it] } ?: return null
        if (rel.external || !OxUtil.isImage(rel.target)) return null
        val bytes = c.pkg.files[rel.target] ?: return null
        c.res[rel.target] = bytes
        return "https://doc.local/" + rel.target
    }

    private fun picture(c: Ctx, pic: XNode, out: StringBuilder, layer: Int, xf: Xf, ph: PhIndex) {
        if (layer > 0 && isPh(pic)) return
        val box = boxOf(pic.path("spPr", "xfrm"), xf) ?: return
        val blip = pic.first("blip")
        val src = imgSrc(c, blip?.attr("embed"))
        out.append("<div class=\"sp\" style=\"left:${box.x}pt;top:${box.y}pt;width:${box.w}pt;height:${box.h}pt;overflow:hidden;").append(transform(box))
        when (pic.path("spPr", "prstGeom")?.attr("prst")) { "ellipse" -> out.append("border-radius:50%;"); "roundRect" -> out.append("border-radius:8%;") }
        out.append("\">")
        if (src == null) { out.append("<div class=\"ph\">Gambar</div></div>"); return }
        val crop = pic.first("srcRect")
        if (crop != null && crop.attrs.isNotEmpty()) {
            val l = (crop.attr("l")?.toDoubleOrNull() ?: 0.0) / 100000; val t = (crop.attr("t")?.toDoubleOrNull() ?: 0.0) / 100000
            val r = (crop.attr("r")?.toDoubleOrNull() ?: 0.0) / 100000; val b = (crop.attr("b")?.toDoubleOrNull() ?: 0.0) / 100000
            val fw = box.w / max(0.01, 1 - l - r); val fh = box.h / max(0.01, 1 - t - b)
            out.append("<img src=\"$src\" style=\"position:absolute;left:${-fw * l}pt;top:${-fh * t}pt;width:${fw}pt;height:${fh}pt\">")
        } else out.append("<img src=\"$src\" style=\"width:100%;height:100%\">")
        lineOf(c, pic.path("spPr", "ln"), pic.path("style", "lnRef"), null, "rect")?.let { out.append("<div class=\"fl\" style=\"box-shadow:inset 0 0 0 ${it.w}pt ${it.color}\"></div>") }
        out.append("</div>")
    }

    private fun frame(c: Ctx, gf: XNode, out: StringBuilder, layer: Int, xf: Xf, ph: PhIndex, txStyles: XNode?) {
        val box = boxOf(gf.child("xfrm"), xf) ?: return
        val gd = gf.path("graphic", "graphicData") ?: return
        val tbl = gd.child("tbl")
        if (tbl != null) { table(c, tbl, box, out); return }
        // SmartArt: pakai gambar siap pakai (diagramDrawing) bila ada
        val relIds = gd.first("relIds")
        if (relIds != null) {
            val dm = relIds.attr("dm")?.let { c.rels[it]?.target }
            val drawRel = c.rels.values.firstOrNull { it.type.endsWith("/diagramDrawing") && (dm == null || true) }
            val dx = drawRel?.let { c.pkg.xml(it.target) }
            val dTree = dx?.first("spTree")
            if (dTree != null) {
                val saveRels = c.rels; c.rels = c.pkg.rels(drawRel.target)
                out.append("<div class=\"sp\" style=\"left:${box.x}pt;top:${box.y}pt;width:${box.w}pt;height:${box.h}pt\">")
                tree(c, dTree, out, null, ph, null, 0, Xf.ID)
                out.append("</div>")
                c.rels = saveRels
                return
            }
        }
        val label = if (gd.first("chart") != null) "Grafik" else if (relIds != null) "Diagram" else "Objek"
        out.append("<div class=\"sp\" style=\"left:${box.x}pt;top:${box.y}pt;width:${box.w}pt;height:${box.h}pt\"><div class=\"ph\">$label</div></div>")
    }

    private fun table(c: Ctx, tbl: XNode, box: Box, out: StringBuilder) {
        val tp = tbl.child("tblPr")
        val firstRow = tp?.attr("firstRow") == "1"; val bandRow = tp?.attr("bandRow") == "1"
        val accent = color(c, XNode("x").also { it.kids.add(XNode("schemeClr").also { s -> s.attrs["val"] = "accent1" }) }) ?: "#4472c4"
        val accRgb = OxUtil.parseHex(accent) ?: 0x4472C4
        val hasStyle = tp?.child("tableStyleId") != null || tp?.attr("firstRow") != null
        val grid = tbl.child("tblGrid")?.children("gridCol")?.map { emu(it.attr("w")) } ?: emptyList()
        out.append("<div class=\"sp\" style=\"left:${box.x}pt;top:${box.y}pt;width:${box.w}pt\"><table class=\"pt\" style=\"width:${grid.sum().takeIf { it > 0 } ?: box.w}pt\"><colgroup>")
        for (g in grid) out.append("<col style=\"width:${g}pt\">")
        out.append("</colgroup>")
        for ((ri, tr) in tbl.children("tr").withIndex()) {
            out.append("<tr style=\"height:${emu(tr.attr("h"))}pt\">")
            for (tc in tr.children("tc")) {
                if (tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1") continue
                val tcPr = tc.child("tcPr")
                out.append("<td")
                tc.attr("gridSpan")?.let { out.append(" colspan=\"$it\"") }; tc.attr("rowSpan")?.let { out.append(" rowspan=\"$it\"") }
                out.append(" style=\"")
                val l = tcPr?.attr("marL")?.let { emu(it) } ?: 7.2; val r = tcPr?.attr("marR")?.let { emu(it) } ?: 7.2
                val t = tcPr?.attr("marT")?.let { emu(it) } ?: 3.6; val b = tcPr?.attr("marB")?.let { emu(it) } ?: 3.6
                out.append("padding:${t}pt ${r}pt ${b}pt ${l}pt;")
                var bgc: String? = tcPr?.child("solidFill")?.let { color(c, it) }
                var fg: String? = null
                if (bgc == null && tcPr?.child("noFill") == null && hasStyle) {
                    if (firstRow && ri == 0) { bgc = accent; fg = "#ffffff" }
                    else if (bandRow) bgc = OxUtil.hex(OxUtil.tint(accRgb, if ((ri - (if (firstRow) 1 else 0)) % 2 == 0) 0.6f else 0.8f))
                    else bgc = OxUtil.hex(OxUtil.tint(accRgb, 0.8f))
                }
                bgc?.let { out.append("background:$it;") }
                fg?.let { out.append("color:$it;font-weight:bold;") }
                for ((side, nm) in listOf("left" to "lnL", "right" to "lnR", "top" to "lnT", "bottom" to "lnB")) {
                    val ln = tcPr?.child(nm)
                    if (ln != null && ln.child("noFill") == null) { val col = ln.child("solidFill")?.let { color(c, it) } ?: "#000"; out.append("border-$side:${max(0.5, emu(ln.attr("w")))}pt solid $col;") }
                    else if (ln == null && hasStyle) out.append("border-$side:1pt solid #fff;")
                }
                when (tcPr?.attr("anchor")) { "ctr" -> out.append("vertical-align:middle;"); "b" -> out.append("vertical-align:bottom;") }
                out.append("\">")
                tc.child("txBody")?.let { txb ->
                    val sb = StringBuilder()
                    text(c, txb, listOf(c.defaultText, txb.child("lstStyle")), listOf(txb.child("bodyPr")), box, null, sb)
                    out.append(sb.toString().replace("class=\"tx\"", "class=\"tx tc\"").replace(Regex("padding:[^;]*;"), "padding:0;"))
                }
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table></div>")
    }

    // ------------------------------------------------------------------ warna, latar, tema
    private fun color(c: Ctx, holder: XNode?): String? {
        if (holder == null) return null
        val cn = holder.kids.firstOrNull { it.name in setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr") } ?: return null
        var rgb: Int = when (cn.name) {
            "srgbClr" -> OxUtil.parseHex(cn.v) ?: return null
            "sysClr" -> OxUtil.parseHex(cn.attr("lastClr")) ?: if (cn.v == "window") 0xFFFFFF else 0
            "schemeClr" -> { val v = cn.v ?: return null; if (v == "phClr") return null; c.theme.colors[c.clrMap[v] ?: v] ?: c.theme.colors[v] ?: return null }
            "scrgbClr" -> scrgb(cn)
            "prstClr" -> PRESET[cn.v] ?: 0
            else -> 0x808080
        }
        var alpha = 1.0; var mod = 1f; var off = 0f
        for (m in cn.kids) when (m.name) {
            "lumMod" -> mod = (m.v?.toFloatOrNull() ?: 100000f) / 100000f
            "lumOff" -> off = (m.v?.toFloatOrNull() ?: 0f) / 100000f
            "tint" -> rgb = OxUtil.mixLinear(0xFFFFFF, rgb, (m.v?.toFloatOrNull() ?: 100000f) / 100000f)
            "shade" -> rgb = OxUtil.mixLinear(0, rgb, (m.v?.toFloatOrNull() ?: 100000f) / 100000f)
            "alpha" -> alpha = (m.v?.toDoubleOrNull() ?: 100000.0) / 100000
        }
        if (mod != 1f || off != 0f) rgb = OxUtil.lum(rgb, mod, off)
        return if (alpha < 0.999) "rgba(${(rgb shr 16) and 255},${(rgb shr 8) and 255},${rgb and 255},${String.format(java.util.Locale.US, "%.3f", alpha)})" else OxUtil.hex(rgb)
    }

    private fun scrgb(cn: XNode): Int {
        fun ch(k: String): Int = ((cn.attr(k)?.toDoubleOrNull() ?: 0.0) / 100000 * 255).toInt().coerceIn(0, 255)
        return (ch("r") shl 16) or (ch("g") shl 8) or ch("b")
    }

    private val PRESET = mapOf("black" to 0, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF, "yellow" to 0xFFFF00, "gray" to 0x808080, "orange" to 0xFFA500)

    private fun bgCss(c: Ctx, bg: XNode?): String {
        if (bg == null) return "background:#fff"
        bg.child("bgPr")?.let { p ->
            val f = fillOf(c, p, null, null)
            if (f.img != null) return "background:url('${f.img}') center/cover no-repeat"
            if (f.css != null) return "background:${f.css}"
        }
        bg.child("bgRef")?.let { r ->
            val col = color(c, r)
            val idx = r.attr("idx")?.toIntOrNull() ?: 0
            val styleFill = if (idx >= 1001) c.theme.bgFills.getOrNull(idx - 1001) else null
            if (styleFill?.name == "gradFill" && col != null) {
                val rgb = OxUtil.parseHex(col) ?: 0xFFFFFF
                return "background:linear-gradient(180deg,${OxUtil.hex(OxUtil.lum(rgb, 1f, 0.1f))},${col})"
            }
            if (col != null) return "background:$col"
        }
        return "background:#fff"
    }

    private fun loadTheme(c: Ctx, path: String) {
        val t = c.pkg.xml(path) ?: return
        val cols = HashMap<String, Int>()
        t.first("clrScheme")?.kids?.forEach { k -> (k.child("srgbClr")?.v ?: k.child("sysClr")?.attr("lastClr"))?.let { OxUtil.parseHex(it)?.let { v -> cols[k.name] = v } } }
        val fs = t.first("fmtScheme")
        c.theme = Theme(cols, t.first("majorFont")?.child("latin")?.attr("typeface") ?: "Calibri Light", t.first("minorFont")?.child("latin")?.attr("typeface") ?: "Calibri",
            fs?.child("fillStyleLst")?.kids ?: emptyList(), fs?.child("bgFillStyleLst")?.kids ?: emptyList(), fs?.child("lnStyleLst")?.kids ?: emptyList())
    }

    private const val CSS = """
html,body{margin:0;padding:0;background:#2b2d33}
body{padding:10px 0}
.sw{margin:0 auto;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.5);background:#fff}
.slide{position:relative;overflow:hidden;transform-origin:0 0;color:#000}
.bg{position:absolute;left:0;top:0;right:0;bottom:0}
.sp{position:absolute;box-sizing:border-box}
.fl{position:absolute;left:0;top:0;width:100%;height:100%;overflow:visible}
svg.fl{width:auto;height:auto}
.tx{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;flex-direction:column;box-sizing:border-box;overflow:visible}
.tx.tc{position:static;display:block}
.tx p{margin:0;white-space:pre-wrap;overflow-wrap:break-word}
.bu{display:inline-block;text-indent:0;white-space:pre}
img{display:block;max-width:none}
a{color:inherit}
table.pt{border-collapse:collapse;table-layout:fixed}
table.pt td{vertical-align:top;overflow:hidden}
.ph{width:100%;height:100%;box-sizing:border-box;border:1px dashed #9aa0a6;background:rgba(241,243,246,.85);display:flex;align-items:center;justify-content:center;font:12px sans-serif;color:#5f6368}
.no{color:#c8cbd3;font:12px sans-serif;text-align:center;margin:6px 0 14px}
.err{color:#c8cbd3;font:14px sans-serif;padding:24px;text-align:center}
@media print{html,body{background:#fff;padding:0}.sw{box-shadow:none;margin:0;width:auto!important;height:auto!important;break-after:page;page-break-after:always}.slide{transform:none!important}.no{display:none}}
"""

    private const val JS = """
function fit(){var W=Math.min(window.innerWidth-16,SW*1.5);var s=W/SW;var ws=document.querySelectorAll('.sw');
for(var i=0;i<ws.length;i++){var sl=ws[i].firstElementChild;var w=sl.offsetWidth,h=sl.offsetHeight;sl.style.transform='scale('+s+')';ws[i].style.width=(w*s)+'px';ws[i].style.height=(h*s)+'px';}}
window.addEventListener('resize',fit);
(function(){var go=function(){fit();document.body.setAttribute('data-ready','1');};if(document.fonts&&document.fonts.ready){document.fonts.ready.then(go);setTimeout(go,2000);}else go();})();
"""
}
