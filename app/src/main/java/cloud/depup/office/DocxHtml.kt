package cloud.depup.office

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * DOCX → HTML untuk PEMBACA dokumen, sedekat mungkin dengan tampilan Word: halaman (ukuran & margin asli), gaya &
 * pewarisan (docDefaults → style basedOn → langsung), font tema, warna tema (tint/shade), penomoran & bullet,
 * tab (rata kiri/kanan/tengah + titik-titik), tabel (lebar kolom, gabung sel, garis tepi, arsiran, gaya tabel
 * bersyarat), gambar inline & mengambang, kotak teks, header/footer + nomor halaman, catatan kaki, field (hasil
 * tersimpan), pemisah halaman/bagian. Pemenggalan halaman dilakukan JavaScript di WebView.
 * Murni Kotlin — diuji di JVM.
 */
object DocxHtml {

    // ------------------------------------------------------------------ model properti
    class RP {
        var font: String? = null; var fontTheme: String? = null; var size: Int? = null
        var b: Boolean? = null; var i: Boolean? = null; var u: String? = null; var strike: Boolean? = null; var dstrike: Boolean? = null
        var color: Int? = null; var colorAuto: Boolean? = null; var highlight: Int? = null; var shd: Int? = null
        var vert: String? = null; var caps: Boolean? = null; var smallCaps: Boolean? = null; var vanish: Boolean? = null
        var spacing: Int? = null; var position: Int? = null; var rtl: Boolean? = null
        fun over(o: RP?): RP {
            if (o == null) return this
            val r = RP()
            r.font = o.font ?: font; r.fontTheme = if (o.font != null) o.fontTheme else (o.fontTheme ?: fontTheme)
            if (o.font != null && o.fontTheme == null) r.fontTheme = null
            r.size = o.size ?: size; r.b = o.b ?: b; r.i = o.i ?: i; r.u = o.u ?: u
            r.strike = o.strike ?: strike; r.dstrike = o.dstrike ?: dstrike; r.color = o.color ?: color
            r.colorAuto = o.colorAuto ?: colorAuto; if (o.color != null) r.colorAuto = false
            r.highlight = o.highlight ?: highlight; r.shd = o.shd ?: shd; r.vert = o.vert ?: vert
            r.caps = o.caps ?: caps; r.smallCaps = o.smallCaps ?: smallCaps; r.vanish = o.vanish ?: vanish
            r.spacing = o.spacing ?: spacing; r.position = o.position ?: position; r.rtl = o.rtl ?: rtl
            return r
        }
    }

    class Bdr(val style: String, val szEighths: Int, val color: Int?, val space: Int)

    class PP {
        var align: String? = null; var before: Int? = null; var after: Int? = null; var beforeAuto: Boolean? = null; var afterAuto: Boolean? = null
        var line: Int? = null; var lineRule: String? = null
        var left: Int? = null; var right: Int? = null; var first: Int? = null; var hanging: Int? = null
        var pageBreakBefore: Boolean? = null; var numId: Int? = null; var ilvl: Int? = null; var shd: Int? = null
        var bdr: HashMap<String, Bdr>? = null; var tabs: List<Triple<Int, String, String>>? = null
        var contextual: Boolean? = null; var keepNext: Boolean? = null; var styleId: String? = null
        fun over(o: PP?): PP {
            if (o == null) return this
            val r = PP()
            r.align = o.align ?: align; r.before = o.before ?: before; r.after = o.after ?: after
            r.beforeAuto = o.beforeAuto ?: beforeAuto; r.afterAuto = o.afterAuto ?: afterAuto
            r.line = o.line ?: line; r.lineRule = if (o.line != null) o.lineRule else lineRule
            r.left = o.left ?: left; r.right = o.right ?: right
            if (o.first != null || o.hanging != null) { r.first = o.first; r.hanging = o.hanging } else { r.first = first; r.hanging = hanging }
            r.pageBreakBefore = o.pageBreakBefore ?: pageBreakBefore; r.numId = o.numId ?: numId; r.ilvl = o.ilvl ?: ilvl
            r.shd = o.shd ?: shd
            r.bdr = if (o.bdr != null) HashMap(bdr ?: emptyMap<String, Bdr>()).also { it.putAll(o.bdr!!) } else bdr
            r.tabs = mergeTabs(tabs, o.tabs)
            r.contextual = o.contextual ?: contextual; r.keepNext = o.keepNext ?: keepNext; r.styleId = o.styleId ?: styleId
            return r
        }
        private fun mergeTabs(a: List<Triple<Int, String, String>>?, b: List<Triple<Int, String, String>>?): List<Triple<Int, String, String>>? {
            if (b == null) return a
            val m = LinkedHashMap<Int, Triple<Int, String, String>>()
            a?.forEach { m[it.first] = it }
            b.forEach { if (it.second == "clear") m.remove(it.first) else m[it.first] = it }
            return m.values.sortedBy { it.first }
        }
    }

    class Style(val id: String, val type: String, val basedOn: String?, val node: XNode)

    class Sect(
        val w: Int, val h: Int, val top: Int, val right: Int, val bottom: Int, val left: Int, val header: Int, val footer: Int,
        val titlePg: Boolean, val hdr: Map<String, String>, val ftr: Map<String, String>, val type: String, val cols: Int
    )

    private class Lvl(val start: Int, val fmt: String, val text: String, val jc: String, val pp: PP?, val rp: RP?, val suff: String, val restart: Int, val isLgl: Boolean)

    // ------------------------------------------------------------------ konteks
    private class Ctx(val pkg: OPackage, val main: String) {
        val res = HashMap<String, ByteArray>()
        val theme = HashMap<String, Int>()
        var majorFont = "Calibri Light"; var minorFont = "Calibri"
        val styles = HashMap<String, Style>()
        var defRP = RP(); var defPP = PP()
        var defParaStyle: String? = null; var defTableStyle: String? = null; var defCharStyle: String? = null
        val nums = HashMap<Int, Pair<String, Map<Int, Int>>>()          // numId → (abstractId, startOverride per level)
        val abstracts = HashMap<String, HashMap<Int, Lvl>>()
        val numOverrideLvls = HashMap<Int, HashMap<Int, Lvl>>()
        val counters = HashMap<String, IntArray>()
        val usedNum = HashSet<Int>()
        var defaultTab = 720
        val footnotes = HashMap<String, XNode>(); val endnotes = HashMap<String, XNode>()
        val noteOrder = ArrayList<Pair<String, Int>>()   // (kind:id, nomor)
        var noteNo = 0; var endNo = 0
        var rels: Map<String, OPackage.Rel> = emptyMap()
        var part = main
        // field
        val fieldStack = ArrayList<IntArray>()   // [fase 0=instr 1=hasil, jenis 0=biasa 1=PAGE 2=NUMPAGES]
        val instr = StringBuilder()
        /** Warna latar saat ini (arsiran sel/paragraf) — warna teks "auto" jadi putih di latar gelap. */
        var bg: Int? = null
        val pRPCache = HashMap<String, RP>(); val pPPCache = HashMap<String, PP>(); val cRPCache = HashMap<String, RP>()
    }

    // ------------------------------------------------------------------ API
    fun render(bytes: ByteArray, title: String): RenderedDoc {
        val pkg = OPackage(bytes)
        val main = pkg.mainPart() ?: "word/document.xml"
        val c = Ctx(pkg, main)
        // bagian pendukung yang rusak/aneh tidak boleh menggagalkan seluruh dokumen
        for (ld in listOf<(Ctx) -> Unit>(::loadTheme, ::loadStyles, ::loadNumbering, ::loadSettings, ::loadNotes)) try { ld(c) } catch (_: Throwable) {}
        val doc = pkg.xml(main) ?: throw IllegalArgumentException("document.xml tidak ada")
        val body = doc.first("body") ?: throw IllegalArgumentException("body tidak ada")
        c.rels = pkg.rels(main)

        // bagian (section): sectPr di dalam pPr menutup bagian; sectPr terakhir milik body
        val blocks = flatten(body.kids)
        val sects = ArrayList<Sect>()
        val sectOf = IntArray(blocks.size)
        var cur = 0
        for ((bi, b) in blocks.withIndex()) {
            sectOf[bi] = cur
            val sp = if (b.name == "p") b.path("pPr", "sectPr") else null
            if (sp != null) { sects.add(parseSect(sp, sects.lastOrNull())); cur++ }
        }
        sects.add(parseSect(body.child("sectPr"), sects.lastOrNull()))

        val sb = StringBuilder(64 * 1024)
        var lastSect = -1
        for ((bi, b) in blocks.withIndex()) {
            val s = sectOf[bi]
            if (s != lastSect) { sb.append("<div class=\"sect\" data-s=\"$s\"></div>"); lastSect = s }
            block(c, b, sb, s, null)
        }
        // catatan kaki & akhir
        if (c.noteOrder.isNotEmpty()) {
            sb.append("<div class=\"b notes\" data-s=\"$lastSect\"><hr>")
            for ((key, no) in c.noteOrder) {
                val node = if (key.startsWith("f:")) c.footnotes[key.substring(2)] else c.endnotes[key.substring(2)]
                if (node == null) continue
                sb.append("<div class=\"note\"><sup>$no</sup> ")
                for (p in flatten(node.kids)) if (p.name == "p") paragraph(c, p, sb, lastSect, null, inNote = true)
                sb.append("</div>")
            }
            sb.append("</div>")
        }

        // header & footer
        val hf = StringBuilder()
        val seen = HashSet<String>()
        for (sct in sects) for (id in sct.hdr.values + sct.ftr.values) if (seen.add(id)) {
            val rel = c.rels[id] ?: continue
            val x = pkg.xml(rel.target) ?: continue
            val saveRels = c.rels; val savePart = c.part
            c.rels = pkg.rels(rel.target); c.part = rel.target
            hf.append("<template id=\"hf-").append(id).append("\">")
            val rootEl = x.kids.firstOrNull { it.name == "hdr" || it.name == "ftr" } ?: x
            for (p in flatten(rootEl.kids)) block(c, p, hf, -1, null)
            hf.append("</template>")
            c.rels = saveRels; c.part = savePart
        }

        val sectJson = sects.joinToString(",", "[", "]") { s ->
            fun m(x: Map<String, String>) = x.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
            "{\"w\":${s.w / 20.0},\"h\":${s.h / 20.0},\"t\":${s.top / 20.0},\"r\":${s.right / 20.0},\"b\":${s.bottom / 20.0},\"l\":${s.left / 20.0}," +
                "\"hd\":${s.header / 20.0},\"ft\":${s.footer / 20.0},\"tp\":${s.titlePg},\"type\":\"${s.type}\",\"hdr\":${m(s.hdr)},\"ftr\":${m(s.ftr)}}"
        }
        val baseFont = fontOf(c, c.defRP)
        val baseSize = (c.defRP.size ?: 20) / 2.0
        val html = StringBuilder()
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=")
            .append(((sects.maxOf { it.w } / 20.0) * 4 / 3 + 24).roundToInt()).append("\"><title>").append(OxUtil.esc(title)).append("</title><style>")
            .append(OxUtil.fontFaces("https://doc.local/_fonts/"))
            .append(CSS)
            .append("@page{size:${sects[0].w / 20.0}pt ${sects[0].h / 20.0}pt;margin:0}")
            .append("#flow,.content{font-family:").append(OxUtil.fontStack(baseFont)).append(";font-size:${baseSize}pt}")
            .append("</style></head><body><div id=\"pages\"></div><div id=\"flow\">")
            .append(sb).append("</div>").append(hf)
            .append("<script>var SECTS=").append(sectJson).append(";var DEFTAB=").append(c.defaultTab / 20.0).append(";").append(JS).append("</script></body></html>")
        return RenderedDoc(html.toString(), c.res, title, "docx")
    }

    /** Mode cadangan bila render lengkap gagal: teks + tabel sederhana, tanpa tata letak halaman. */
    fun renderSimple(bytes: ByteArray, title: String): RenderedDoc {
        val pkg = OPackage(bytes)
        val doc = pkg.xml(pkg.mainPart() ?: "word/document.xml") ?: throw IllegalArgumentException("document.xml tidak ada")
        val body = doc.first("body") ?: throw IllegalArgumentException("body tidak ada")
        val sb = StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<style>body{margin:0;background:#e9eaee;font:11pt/1.35 Calibri,Arial,sans-serif;color:#000}")
        sb.append(".pg{background:#fff;max-width:794px;margin:12px auto;padding:48px 56px;box-sizing:border-box;box-shadow:0 1px 4px rgba(0,0,0,.2)}")
        sb.append(".p{margin:0 0 6pt;white-space:pre-wrap;word-wrap:break-word}table{border-collapse:collapse;margin:0 0 8pt}td .p{margin:0}</style></head><body data-ready=\"1\"><div class=\"pg\">")
        for (b in flatten(body.kids)) {
            if (b.name == "tbl") simpleTable(b, sb, -1)
            else sb.append("<div class=\"p\">").append(OxUtil.esc(paraText(b)).ifEmpty { "&#8203;" }).append("</div>")
        }
        sb.append("</div></body></html>")
        return RenderedDoc(sb.toString(), emptyMap(), title, "docx")
    }

    /** Render satu blok p/tbl; kalau gagal, tampilkan versi sederhana (tabel tetap tampil sebagai tabel). */
    private fun block(c: Ctx, b: XNode, out: StringBuilder, sect: Int, cellDef: Pair<PP?, RP?>?) {
        val mark = out.length
        try {
            when (b.name) { "p" -> paragraph(c, b, out, sect, cellDef); "tbl" -> table(c, b, out, sect) }
        } catch (e: Throwable) {
            out.setLength(mark)
            c.bg = null
            if (b.name == "tbl") simpleTable(b, out, sect)
            else out.append("<div class=\"p").append(if (sect >= 0) " b\" data-s=\"$sect\"" else "\"").append(">")
                .append(OxUtil.esc(paraText(b)).ifEmpty { "&#8203;" }).append("</div>")
        }
    }

    private fun paraText(p: XNode): String {
        val sb = StringBuilder()
        fun walk(n: XNode) { for (k in n.kids) when (k.name) { "t" -> sb.append(k.allText()); "tab" -> sb.append('\t'); "br", "cr" -> sb.append('\n'); "del", "delText", "instrText" -> {}; else -> walk(k) } }
        walk(p); return sb.toString()
    }

    /** Baris/sel bisa terbungkus sdt (kontrol isian), customXml, ins — ambil menembus pembungkus itu. */
    private fun through(n: XNode, name: String, out: MutableList<XNode> = ArrayList()): List<XNode> {
        for (k in n.kids) when (k.name) {
            name -> out.add(k)
            "sdt" -> k.child("sdtContent")?.let { through(it, name, out) }
            "customXml", "ins", "moveTo", "smartTag" -> through(k, name, out)
        }
        return out
    }

    /** Tabel cadangan: teks per sel dengan garis, dipakai bila rendering lengkap gagal. */
    private fun simpleTable(t: XNode, out: StringBuilder, sect: Int) {
        out.append("<table class=\"").append(if (sect >= 0) "b" else "").append("\"")
        if (sect >= 0) out.append(" data-s=\"$sect\"")
        out.append(" style=\"border-collapse:collapse;width:100%\">")
        for (tr in through(t, "tr")) {
            out.append("<tr>")
            for (tc in through(tr, "tc")) {
                if (tc.path("tcPr", "vMerge")?.let { it.v != "restart" } == true) continue
                val sp = tc.path("tcPr", "gridSpan")?.v?.toIntOrNull() ?: 1
                out.append("<td").append(if (sp > 1) " colspan=\"$sp\"" else "").append(" style=\"border:0.5pt solid #000;padding:0 5.4pt;vertical-align:top\">")
                val ps = flatten(tc.kids)
                if (ps.isEmpty()) out.append("<div class=\"p\">&#8203;</div>")
                for (k in ps) if (k.name == "tbl") simpleTable(k, out, -1) else out.append("<div class=\"p\">").append(OxUtil.esc(paraText(k)).replace("\n", "<br>").ifEmpty { "&#8203;" }).append("</div>")
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
    }

    /** Buka sdt / customXml / smartTag menjadi daftar blok p/tbl. */
    private fun flatten(kids: List<XNode>, out: MutableList<XNode> = ArrayList()): List<XNode> {
        for (k in kids) when (k.name) {
            "p", "tbl" -> out.add(k)
            "sdt" -> k.child("sdtContent")?.let { flatten(it.kids, out) }
            "customXml", "smartTag", "ins", "moveTo" -> flatten(k.kids, out)
        }
        return out
    }

    // ------------------------------------------------------------------ tema, gaya, penomoran
    private fun loadTheme(c: Ctx) {
        val rel = c.pkg.rels(c.main).values.firstOrNull { it.type.endsWith("/theme") } ?: return
        val t = c.pkg.xml(rel.target) ?: return
        t.first("clrScheme")?.kids?.forEach { k ->
            val v = k.child("srgbClr")?.v ?: k.child("sysClr")?.attr("lastClr")
            OxUtil.parseHex(v)?.let { c.theme[k.name] = it }
        }
        fun alias(a: String, b: String) { c.theme[b]?.let { c.theme[a] = it } }
        alias("text1", "dk1"); alias("background1", "lt1"); alias("text2", "dk2"); alias("background2", "lt2")
        alias("dark1", "dk1"); alias("light1", "lt1"); alias("dark2", "dk2"); alias("light2", "lt2")
        alias("hyperlink", "hlink"); alias("followedHyperlink", "folHlink")
        t.first("majorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() }?.let { c.majorFont = it }
        t.first("minorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() }?.let { c.minorFont = it }
    }

    private fun loadStyles(c: Ctx) {
        val rel = c.pkg.rels(c.main).values.firstOrNull { it.type.endsWith("/styles") }
        val x = rel?.let { c.pkg.xml(it.target) } ?: c.pkg.xml("word/styles.xml") ?: run { c.defRP.size = 20; return }
        x.child("styles")?.child("docDefaults")?.let { dd ->
            c.defRP = parseRP(c, dd.path("rPrDefault", "rPr"))
            c.defPP = parsePP(c, dd.path("pPrDefault", "pPr"))
        } ?: x.first("docDefaults")?.let { dd ->
            c.defRP = parseRP(c, dd.path("rPrDefault", "rPr")); c.defPP = parsePP(c, dd.path("pPrDefault", "pPr"))
        }
        if (c.defRP.size == null) c.defRP.size = 20
        for (s in x.all("style")) {
            val id = s.attr("styleId") ?: continue
            val type = s.attr("type") ?: "paragraph"
            c.styles[id] = Style(id, type, s.child("basedOn")?.v, s)
            if (s.attr("default") == "1" || s.attr("default") == "true") when (type) {
                "paragraph" -> c.defParaStyle = id; "table" -> c.defTableStyle = id; "character" -> c.defCharStyle = id
            }
        }
    }

    private fun loadNumbering(c: Ctx) {
        val rel = c.pkg.rels(c.main).values.firstOrNull { it.type.endsWith("/numbering") } ?: return
        val x0 = c.pkg.xml(rel.target) ?: return
        val x = x0.child("numbering") ?: x0
        fun lvls(n: XNode): HashMap<Int, Lvl> {
            val m = HashMap<Int, Lvl>()
            for (l in n.children("lvl")) {
                val il = l.attr("ilvl")?.toIntOrNull() ?: continue
                m[il] = Lvl(
                    l.child("start")?.v?.toIntOrNull() ?: 1, l.child("numFmt")?.v ?: "decimal", l.child("lvlText")?.v ?: "",
                    l.child("lvlJc")?.v ?: "left", l.child("pPr")?.let { parsePP(c, it) }, l.child("rPr")?.let { parseRP(c, it) },
                    l.child("suff")?.v ?: "tab", l.child("lvlRestart")?.v?.toIntOrNull() ?: -1, l.child("isLgl") != null
                )
            }
            return m
        }
        val styleLink = HashMap<String, String>()
        for (a in x.children("abstractNum")) {
            val id = a.attr("abstractNumId") ?: continue
            c.abstracts[id] = lvls(a)
            a.child("numStyleLink")?.v?.let { styleLink[id] = it }
        }
        for (n in x.children("num")) {
            val id = n.attr("numId")?.toIntOrNull() ?: continue
            val abs = n.child("abstractNumId")?.v ?: continue
            val starts = HashMap<Int, Int>()
            val ov = HashMap<Int, Lvl>()
            for (o in n.children("lvlOverride")) {
                val il = o.attr("ilvl")?.toIntOrNull() ?: continue
                o.child("startOverride")?.v?.toIntOrNull()?.let { starts[il] = it }
                o.child("lvl")?.let { l -> lvls(XNode("x").also { it.kids.add(l.also { it.attrs["ilvl"] = il.toString() }) })[il]?.let { ov[il] = it } }
            }
            c.nums[id] = abs to starts
            if (ov.isNotEmpty()) c.numOverrideLvls[id] = ov
        }
        // numStyleLink: abstractNum yang hanya menunjuk gaya penomoran lain
        for ((abs, styleId) in styleLink) {
            if (c.abstracts[abs]?.isNotEmpty() == true) continue
            val st = c.styles[styleId] ?: continue
            val nid = st.node.path("pPr", "numPr", "numId")?.v?.toIntOrNull() ?: continue
            val target = c.nums[nid]?.first ?: continue
            c.abstracts[target]?.let { c.abstracts[abs] = it }
        }
    }

    private fun loadSettings(c: Ctx) {
        val rel = c.pkg.rels(c.main).values.firstOrNull { it.type.endsWith("/settings") } ?: return
        val x = c.pkg.xml(rel.target) ?: return
        x.first("defaultTabStop")?.v?.toIntOrNull()?.let { if (it > 0) c.defaultTab = it }
    }

    private fun loadNotes(c: Ctx) {
        for (r in c.pkg.rels(c.main).values) {
            if (r.type.endsWith("/footnotes")) c.pkg.xml(r.target)?.all("footnote")?.forEach { n -> n.attr("id")?.let { c.footnotes[it] = n } }
            if (r.type.endsWith("/endnotes")) c.pkg.xml(r.target)?.all("endnote")?.forEach { n -> n.attr("id")?.let { c.endnotes[it] = n } }
        }
    }

    // ------------------------------------------------------------------ parsing properti
    private fun onOff(n: XNode?): Boolean? {
        if (n == null) return null
        val v = n.v ?: return true
        return !(v == "0" || v == "false" || v == "off" || v == "none")
    }

    private fun colorOf(c: Ctx, n: XNode?): Int? {
        if (n == null) return null
        val th = n.attr("themeColor")
        var rgb: Int? = if (th != null) c.theme[th] ?: OxUtil.parseHex(n.v) else OxUtil.parseHex(n.v)
        if (rgb != null) {
            n.attr("themeTint")?.toIntOrNull(16)?.let { t -> rgb = OxUtil.mix(rgb!!, 0xFFFFFF, 1f - t / 255f) }
            n.attr("themeShade")?.toIntOrNull(16)?.let { s -> rgb = OxUtil.mix(0, rgb!!, s / 255f) }
        }
        return rgb
    }

    private val HIGHLIGHT = mapOf(
        "yellow" to 0xFFFF00, "green" to 0x00FF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "blue" to 0x0000FF, "red" to 0xFF0000,
        "darkBlue" to 0x000080, "darkCyan" to 0x008080, "darkGreen" to 0x008000, "darkMagenta" to 0x800080, "darkRed" to 0x800000,
        "darkYellow" to 0x808000, "darkGray" to 0x808080, "lightGray" to 0xC0C0C0, "black" to 0x000000, "white" to 0xFFFFFF
    )

    private fun shdOf(c: Ctx, n: XNode?): Int? {
        if (n == null) return null
        val fill = n.attr("fill")
        val th = n.attr("themeFill")
        var base: Int? = if (th != null) c.theme[th] else if (fill != null && fill != "auto") OxUtil.parseHex(fill) else null
        if (base != null) {
            n.attr("themeFillTint")?.toIntOrNull(16)?.let { t -> base = OxUtil.mix(base!!, 0xFFFFFF, 1f - t / 255f) }
            n.attr("themeFillShade")?.toIntOrNull(16)?.let { s -> base = OxUtil.mix(0, base!!, s / 255f) }
        }
        // pola persentase (pct10 dll) di atas latar: campur warna pola
        val v = n.v
        if (v != null && v.startsWith("pct")) {
            val pct = v.substring(3).toIntOrNull() ?: 0
            val col = OxUtil.parseHex(n.attr("color")) ?: 0
            base = OxUtil.mix(base ?: 0xFFFFFF, col, pct / 100f)
        } else if (v == "solid") {
            base = OxUtil.parseHex(n.attr("color")) ?: 0
        }
        return base
    }

    private fun parseRP(c: Ctx, n: XNode?): RP {
        val r = RP()
        if (n == null) return r
        n.child("rFonts")?.let { f ->
            val th = f.attr("asciiTheme") ?: f.attr("hAnsiTheme")
            val nm = f.attr("ascii") ?: f.attr("hAnsi")
            if (nm != null) r.font = nm
            if (th != null) { r.fontTheme = th; if (nm == null) r.font = null }
        }
        n.child("sz")?.v?.toIntOrNull()?.let { r.size = it }
        r.b = onOff(n.child("b")); r.i = onOff(n.child("i"))
        n.child("u")?.let { r.u = it.v ?: "single" }
        r.strike = onOff(n.child("strike")); r.dstrike = onOff(n.child("dstrike"))
        n.child("color")?.let { col -> if (col.v == "auto" && col.attr("themeColor") == null) r.colorAuto = true else r.color = colorOf(c, col) }
        n.child("highlight")?.v?.let { h -> if (h != "none") r.highlight = HIGHLIGHT[h] }
        shdOf(c, n.child("shd"))?.let { r.shd = it }
        n.child("vertAlign")?.v?.let { r.vert = it }
        r.caps = onOff(n.child("caps")); r.smallCaps = onOff(n.child("smallCaps")); r.vanish = onOff(n.child("vanish"))
        n.child("spacing")?.v?.toIntOrNull()?.let { r.spacing = it }
        n.child("position")?.v?.toIntOrNull()?.let { r.position = it }
        return r
    }

    private fun bdrOf(n: XNode?): Bdr? {
        if (n == null) return null
        val v = n.v ?: return null
        return Bdr(v, n.attr("sz")?.toIntOrNull() ?: 4, OxUtil.parseHex(n.attr("color")?.takeIf { it != "auto" }), n.attr("space")?.toIntOrNull() ?: 0)
    }

    private fun parsePP(c: Ctx, n: XNode?): PP {
        val p = PP()
        if (n == null) return p
        n.child("pStyle")?.v?.let { p.styleId = it }
        n.child("jc")?.v?.let { p.align = it }
        n.child("spacing")?.let { s ->
            s.attr("before")?.toIntOrNull()?.let { p.before = it }; s.attr("after")?.toIntOrNull()?.let { p.after = it }
            s.attr("beforeAutospacing")?.let { p.beforeAuto = it == "1" || it == "true" }
            s.attr("afterAutospacing")?.let { p.afterAuto = it == "1" || it == "true" }
            s.attr("line")?.toIntOrNull()?.let { p.line = it; p.lineRule = s.attr("lineRule") ?: "auto" }
        }
        n.child("ind")?.let { i ->
            (i.attr("left") ?: i.attr("start"))?.toIntOrNull()?.let { p.left = it }
            (i.attr("right") ?: i.attr("end"))?.toIntOrNull()?.let { p.right = it }
            i.attr("firstLine")?.toIntOrNull()?.let { p.first = it; p.hanging = null }
            i.attr("hanging")?.toIntOrNull()?.let { p.hanging = it; p.first = null }
        }
        p.pageBreakBefore = onOff(n.child("pageBreakBefore"))
        n.child("numPr")?.let { np -> np.child("numId")?.v?.toIntOrNull()?.let { p.numId = it }; np.child("ilvl")?.v?.toIntOrNull()?.let { p.ilvl = it } }
        shdOf(c, n.child("shd"))?.let { p.shd = it }
        n.child("pBdr")?.let { b ->
            val m = HashMap<String, Bdr>()
            for (side in listOf("top", "bottom", "left", "right", "between")) bdrOf(b.child(side))?.let { m[side] = it }
            p.bdr = m
        }
        n.child("tabs")?.let { t ->
            p.tabs = t.children("tab").mapNotNull { tab ->
                val pos = tab.attr("pos")?.toIntOrNull() ?: return@mapNotNull null
                Triple(pos, tab.v ?: "left", tab.attr("leader") ?: "none")
            }.sortedBy { it.first }
        }
        p.contextual = onOff(n.child("contextualSpacing")); p.keepNext = onOff(n.child("keepNext"))
        return p
    }

    // ------------------------------------------------------------------ resolusi gaya
    private fun styleChain(c: Ctx, id: String?): List<Style> {
        val out = ArrayList<Style>()
        var cur = id; var guard = 0
        while (cur != null && guard++ < 20) { val s = c.styles[cur] ?: break; out.add(0, s); cur = s.basedOn }
        return out
    }

    private fun paraStyleRP(c: Ctx, id: String?): RP = c.pRPCache.getOrPut(id ?: "") {
        var r = c.defRP
        for (s in styleChain(c, id ?: c.defParaStyle)) r = r.over(parseRP(c, s.node.child("rPr")))
        r
    }

    private fun paraStylePP(c: Ctx, id: String?): PP = c.pPPCache.getOrPut(id ?: "") {
        var p = c.defPP
        for (s in styleChain(c, id ?: c.defParaStyle)) p = p.over(parsePP(c, s.node.child("pPr")))
        p
    }

    private fun charStyleRP(c: Ctx, id: String): RP = c.cRPCache.getOrPut(id) {
        var r = RP()
        for (s in styleChain(c, id)) r = r.over(parseRP(c, s.node.child("rPr")))
        r
    }

    private fun fontOf(c: Ctx, r: RP): String? {
        r.font?.let { return it }
        return when (r.fontTheme) {
            null -> null
            "majorHAnsi", "majorAscii", "majorBidi", "majorEastAsia" -> c.majorFont
            else -> c.minorFont
        }
    }

    // ------------------------------------------------------------------ CSS
    private fun dark(rgb: Int): Boolean {
        val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
        return (r * 76 + g * 151 + b * 29) / 256 <= 156
    }

    private fun runCss(c: Ctx, r: RP, sb: StringBuilder, fontStackCache: HashMap<String, String> = HashMap()) {
        val f = fontOf(c, r)
        if (f != null) sb.append("font-family:").append(fontStackCache.getOrPut(f) { OxUtil.fontStack(f) }).append(';')
        r.size?.let { sb.append("font-size:").append(it / 2.0).append("pt;") }
        if (r.b == true) sb.append("font-weight:bold;") else if (r.b == false) sb.append("font-weight:normal;")
        if (r.i == true) sb.append("font-style:italic;") else if (r.i == false) sb.append("font-style:normal;")
        val deco = ArrayList<String>()
        val u = r.u
        if (u != null && u != "none") deco.add("underline")
        if (r.strike == true || r.dstrike == true) deco.add("line-through")
        if (deco.isNotEmpty()) {
            sb.append("text-decoration:").append(deco.joinToString(" "))
            when (u) { "double" -> sb.append(" double"); "wave", "wavyHeavy", "wavyDouble" -> sb.append(" wavy"); "dotted", "dottedHeavy" -> sb.append(" dotted"); "dash", "dashLong", "dashedHeavy" -> sb.append(" dashed") }
            if (r.dstrike == true) sb.append(" double")
            sb.append(';')
        }
        val bgHere = r.highlight ?: r.shd ?: c.bg
        if (r.colorAuto != true && r.color != null) sb.append("color:").append(OxUtil.hex(r.color!!)).append(';')
        else if (bgHere != null && dark(bgHere)) sb.append("color:#ffffff;")
        (r.highlight ?: r.shd)?.let { sb.append("background:").append(OxUtil.hex(it)).append(';') }
        when (r.vert) { "superscript" -> sb.append("vertical-align:super;font-size:").append(((r.size ?: 22) / 2.0 * 0.65)).append("pt;")
            "subscript" -> sb.append("vertical-align:sub;font-size:").append(((r.size ?: 22) / 2.0 * 0.65)).append("pt;") }
        if (r.caps == true) sb.append("text-transform:uppercase;")
        if (r.smallCaps == true) sb.append("font-variant:small-caps;")
        r.spacing?.let { if (it != 0) sb.append("letter-spacing:").append(it / 20.0).append("pt;") }
        r.position?.let { if (it != 0) sb.append("position:relative;top:").append(-it / 2.0).append("pt;") }
    }

    private fun bdrCss(b: Bdr?): String {
        if (b == null || b.style == "nil" || b.style == "none") return "none"
        val w = max(0.5, b.szEighths / 8.0)
        val st = when (b.style) { "double", "triple", "thinThickSmallGap", "thickThinSmallGap", "thinThickMediumGap", "thickThinMediumGap" -> "double"
            "dotted", "dotDash", "dotDotDash" -> "dotted"; "dashed", "dashSmallGap", "dashDotStroked" -> "dashed"; "inset" -> "inset"; "outset" -> "outset"; else -> "solid" }
        val ww = if (st == "double") max(w, 2.25) else w
        return "${ww}pt $st ${OxUtil.hex(b.color ?: 0)}"
    }

    /** Faktor tinggi baris "single" per font (sesuai metrik font Office). */
    private fun lineFactor(font: String?): Double {
        val f = (font ?: "").lowercase()
        return when {
            f.contains("calibri") || f.contains("carlito") -> 1.22
            f.contains("aptos") -> 1.2
            f.contains("cambria") -> 1.172
            f.contains("times") -> 1.15
            f.contains("arial") || f.contains("helvetica") -> 1.15
            f.contains("courier") -> 1.133
            f.contains("georgia") -> 1.136
            f.contains("verdana") -> 1.215
            f.contains("tahoma") -> 1.207
            f.contains("segoe") -> 1.33
            else -> 1.17
        }
    }

    // ------------------------------------------------------------------ paragraf
    private class ParaState(val sb: StringBuilder, var openP: Boolean = false)

    private fun paragraph(c: Ctx, p: XNode, out: StringBuilder, sect: Int, cellBase: Pair<PP?, RP?>?, inNote: Boolean = false) {
        val pPr = p.child("pPr")
        val direct = parsePP(c, pPr)
        val styleId = direct.styleId
        var pp = paraStylePP(c, styleId)
        var baseRP = paraStyleRP(c, styleId)
        if (cellBase != null) {
            if (styleId == null || styleId == c.defParaStyle) {
                // gaya paragraf bawaan (Normal): gaya tabel menang (perilaku Word)
                cellBase.first?.let { pp = pp.over(it) }; cellBase.second?.let { baseRP = baseRP.over(it) }
            } else {
                // urutan resmi: default dokumen → gaya tabel → gaya paragraf
                var p2 = c.defPP; cellBase.first?.let { p2 = p2.over(it) }
                var r2 = c.defRP; cellBase.second?.let { r2 = r2.over(it) }
                for (st in styleChain(c, styleId)) { p2 = p2.over(parsePP(c, st.node.child("pPr"))); r2 = r2.over(parseRP(c, st.node.child("rPr"))) }
                pp = p2; baseRP = r2
            }
        }
        pp = pp.over(direct)
        // penomoran dari gaya / langsung
        var lvl: Lvl? = null; var label: String? = null
        val numId = pp.numId
        if (numId != null && numId > 0) {
            val il = (pp.ilvl ?: 0).coerceIn(0, 8)
            val nm = c.nums[numId]
            if (nm != null) {
                val absId = nm.first
                lvl = c.numOverrideLvls[numId]?.get(il) ?: c.abstracts[absId]?.get(il)
                if (lvl != null) {
                    val key = absId
                    val cnt = c.counters.getOrPut(key) { IntArray(9) { -1 } }
                    if (c.usedNum.add(numId)) for ((l, st) in nm.second) cnt[l] = st - 1
                    val absL = c.abstracts[absId]
                    if (cnt[il] < 0) cnt[il] = (lvl.start) - 1
                    cnt[il]++
                    for (d in il + 1 until 9) cnt[d] = -1
                    for (d in 0 until il) if (cnt[d] < 0) cnt[d] = (absL?.get(d)?.start ?: 1)
                    label = numLabel(lvl, il, cnt, absL)
                    lvl.pp?.let { lp ->
                        var base = paraStylePP(c, styleId)
                        if (cellBase?.first != null && (styleId == null || styleId == c.defParaStyle)) base = base.over(cellBase.first)
                        // penomoran langsung: indentasi level menimpa indentasi gaya paragraf
                        pp = base.over(lp).over(direct)
                    }
                }
            }
        }
        val markRP = baseRP.over(parseRP(c, pPr?.child("rPr")))
        val savedBg = c.bg
        if (pp.shd != null) c.bg = pp.shd
        val fcache = HashMap<String, String>()

        // CSS paragraf
        val css = StringBuilder()
        val al = when (pp.align) { "center" -> "center"; "right", "end" -> "right"; "both", "distribute", "thaiDistribute", "lowKashida", "mediumKashida", "highKashida" -> "justify"; else -> "left" }
        css.append("text-align:").append(al).append(';')
        val before = if (pp.beforeAuto == true) 280 else pp.before ?: 0
        val after = if (pp.afterAuto == true) 280 else pp.after ?: 0
        css.append("margin-top:").append(before / 20.0).append("pt;margin-bottom:").append(after / 20.0).append("pt;")
        val left = pp.left ?: 0; val right = pp.right ?: 0
        val firstLine = when { pp.hanging != null -> -pp.hanging!!; pp.first != null -> pp.first!!; else -> 0 }
        if (left != 0) css.append("margin-left:").append(left / 20.0).append("pt;")
        if (right != 0) css.append("margin-right:").append(right / 20.0).append("pt;")
        if (firstLine != 0) css.append("text-indent:").append(firstLine / 20.0).append("pt;")
        val font = fontOf(c, markRP)
        val line = pp.line
        when {
            line == null || pp.lineRule == null || pp.lineRule == "auto" -> css.append("line-height:").append("%.3f".format(java.util.Locale.US, (line ?: 240) / 240.0 * lineFactor(font))).append(';')
            pp.lineRule == "exact" -> css.append("line-height:").append(line / 20.0).append("pt;")
            else -> css.append("line-height:max(").append("%.3f".format(java.util.Locale.US, lineFactor(font))).append("em,").append(line / 20.0).append("pt);")
        }
        pp.shd?.let { css.append("background:").append(OxUtil.hex(it)).append(';') }
        pp.bdr?.let { m ->
            for (side in listOf("top", "bottom", "left", "right")) m[side]?.let { b ->
                css.append("border-").append(side).append(':').append(bdrCss(b)).append(';')
                css.append("padding-").append(side).append(':').append(b.space).append("pt;")
            }
        }
        // ukuran font paragraf (untuk paragraf kosong & label)
        val markCss = StringBuilder(); runCss(c, markRP, markCss, fcache)

        val tabsAttr = buildString {
            val stops = ArrayList<String>()
            pp.tabs?.forEach { (pos, a, lead) -> if (a != "clear") stops.add("${pos / 20.0}:${a}:${lead}") }
            if (pp.hanging != null && pp.hanging!! > 0) stops.add("${left / 20.0}:left:none")
            append(stops.joinToString(";"))
        }
        val openTag = StringBuilder()
        openTag.append("<div class=\"p").append(if (sect >= 0 && !inNote) " b" else "").append("\"")
        if (sect >= 0) openTag.append(" data-s=\"").append(sect).append('"')
        openTag.append(" data-il=\"").append(left / 20.0).append('"')
        if (tabsAttr.isNotEmpty()) openTag.append(" data-tabs=\"").append(tabsAttr).append('"')
        if (pp.contextual == true) openTag.append(" data-cs=\"").append(OxUtil.esc(styleId ?: "_")).append('"')
        if (pp.keepNext == true) openTag.append(" data-kn=\"1\"")
        val baseCss = StringBuilder(); runCss(c, baseRP, baseCss, fcache)
        openTag.append(" style=\"").append(css).append(baseCss).append("\">")

        if (pp.pageBreakBefore == true && sect >= 0) out.append("<div class=\"pb\" data-s=\"$sect\"></div>")
        out.append(openTag)
        val contentStart = out.length
        if (label != null && lvl != null) {
            val lrp = markRP.over(lvl.rp).also { it.u = null; it.highlight = null }
            val ls = StringBuilder(); runCss(c, lrp, ls, fcache)
            val font = fontOf(c, lrp)
            val txt = label.map { ch -> if (ch.code in 0xF000..0xF0FF || font?.lowercase()?.let { it.contains("symbol") || it.contains("wingding") } == true) OxUtil.symbolChar(ch, font) else ch.toString() }.joinToString("")
            val hang = pp.hanging ?: 0
            if (txt.isNotEmpty() && (lrp.font?.lowercase()?.contains("symbol") == true || lrp.font?.lowercase()?.contains("wingding") == true)) ls.append("font-family:sans-serif;")
            when (lvl.suff) {
                "tab" -> out.append("<span class=\"lbl\" style=\"").append(ls).append("min-width:").append(max(hang, 0) / 20.0).append("pt\">").append(OxUtil.esc(txt)).append("</span>")
                "space" -> out.append("<span style=\"").append(ls).append("\">").append(OxUtil.esc(txt)).append(" </span>")
                else -> out.append("<span style=\"").append(ls).append("\">").append(OxUtil.esc(txt)).append("</span>")
            }
        }
        val state = ParaState(out, true)
        inline(c, p.kids, state, baseRP, fcache, sect, openTag.toString(), hyper = null)
        // selalu ada "tanda paragraf" setinggi baris: paragraf yang hanya berisi objek mengambang tetap punya tinggi
        out.append("<span style=\"").append(markCss).append("\">&#8203;</span>")
        out.append("</div>")
        c.bg = savedBg
    }

    private fun numLabel(lvl: Lvl, il: Int, cnt: IntArray, abs: Map<Int, Lvl>?): String {
        if (lvl.fmt == "none") return ""
        if (lvl.fmt == "bullet") return lvl.text
        val sb = StringBuilder()
        var i = 0; val t = lvl.text
        while (i < t.length) {
            if (t[i] == '%' && i + 1 < t.length && t[i + 1].isDigit()) {
                val k = t[i + 1] - '1'
                val l = if (k == il) lvl else abs?.get(k)
                val v = if (k in 0..8) cnt[k].coerceAtLeast(l?.start ?: 1) else 1
                sb.append(fmtNum(v, if (lvl.isLgl && k != il) "decimal" else l?.fmt ?: "decimal"))
                i += 2
            } else { sb.append(t[i]); i++ }
        }
        return sb.toString()
    }

    fun fmtNum(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> letters(n).lowercase(); "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase(); "upperRoman" -> roman(n)
        "decimalZero" -> if (n < 10) "0$n" else "$n"
        "ordinal" -> "$n."; "none" -> ""
        "decimalEnclosedCircle" -> if (n in 1..20) (0x2460 + n - 1).toChar().toString() else "$n"
        else -> "$n"
    }
    private fun letters(n0: Int): String { if (n0 <= 0) return ""; val n = n0 - 1; val ch = ('A' + n % 26); return ch.toString().repeat(n / 26 + 1) }
    private fun roman(n0: Int): String {
        var n = n0; if (n <= 0) return n0.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder(); for (k in v.indices) while (n >= v[k]) { sb.append(s[k]); n -= v[k] }
        return sb.toString()
    }

    /** Isi inline paragraf (run, hyperlink, field, gambar, ...). */
    private fun inline(c: Ctx, kids: List<XNode>, st: ParaState, baseRP: RP, fc: HashMap<String, String>, sect: Int, reopen: String, hyper: String?) {
        val out = st.sb
        for (k in kids) when (k.name) {
            "r" -> run(c, k, st, baseRP, fc, sect, reopen)
            "hyperlink" -> {
                val href = k.attr("id")?.let { c.rels[it]?.target } ?: k.attr("anchor")?.let { "#$it" }
                if (href != null && !href.startsWith("#")) out.append("<a href=\"").append(OxUtil.esc(href)).append("\">") else out.append("<a>")
                inline(c, k.kids, st, baseRP, fc, sect, reopen, href)
                out.append("</a>")
            }
            "ins", "smartTag", "customXml", "moveTo", "fldSimple", "dir", "bdo" -> {
                if (k.name == "fldSimple") {
                    val ins = (k.attr("instr") ?: "").trim().uppercase()
                    if (ins.startsWith("PAGE")) { out.append("<span class=\"pg\">1</span>"); continue }
                    if (ins.startsWith("NUMPAGES") || ins.startsWith("SECTIONPAGES")) { out.append("<span class=\"np\">1</span>"); continue }
                }
                inline(c, k.kids, st, baseRP, fc, sect, reopen, hyper)
            }
            "sdt" -> k.child("sdtContent")?.let { inline(c, it.kids, st, baseRP, fc, sect, reopen, hyper) }
            "oMath", "oMathPara" -> {
                if (c.fieldStack.any { it[0] == 0 }) continue
                val t = k.all("t").joinToString("") { it.text }
                out.append("<span style=\"font-family:'Cambria Math','Times New Roman',serif;font-style:italic\">").append(OxUtil.esc(t)).append("</span>")
            }
        }
    }

    private fun run(c: Ctx, r: XNode, st: ParaState, baseRP: RP, fc: HashMap<String, String>, sect: Int, reopen: String) {
        val out = st.sb
        val rPr = r.child("rPr")
        var rp = baseRP
        rPr?.child("rStyle")?.v?.let { rp = rp.over(charStyleRP(c, it)) }
        rp = rp.over(parseRP(c, rPr))
        val hidden = rp.vanish == true
        val css = StringBuilder(); runCss(c, rp, css, fc)
        var open = false
        fun openSpan() { if (!open) { out.append("<span style=\"").append(css).append("\">"); open = true } }
        fun closeSpan() { if (open) { out.append("</span>"); open = false } }
        for (k in r.kids) {
            // field kompleks
            if (k.name == "fldChar") {
                when (k.attr("fldCharType")) {
                    "begin" -> { c.fieldStack.add(intArrayOf(0, 0)); c.instr.setLength(0) }
                    "separate" -> c.fieldStack.lastOrNull()?.let { f ->
                        f[0] = 1
                        val ins = c.instr.toString().trim().uppercase()
                        f[1] = when { ins.startsWith("PAGE") && !ins.startsWith("PAGEREF") -> 1; ins.startsWith("NUMPAGES") || ins.startsWith("SECTIONPAGES") -> 2; else -> 0 }
                        if (f[1] != 0) { closeSpan(); openSpan(); out.append(if (f[1] == 1) "<span class=\"pg\">1</span>" else "<span class=\"np\">1</span>"); closeSpan() }
                    }
                    "end" -> if (c.fieldStack.isNotEmpty()) {
                        val f = c.fieldStack.removeAt(c.fieldStack.size - 1)
                        if (f[0] == 0) { // field tanpa hasil tersimpan: PAGE dll tetap ditampilkan
                            val ins = c.instr.toString().trim().uppercase()
                            if (ins.startsWith("PAGE") && !ins.startsWith("PAGEREF")) { openSpan(); out.append("<span class=\"pg\">1</span>"); closeSpan() }
                            else if (ins.startsWith("NUMPAGES")) { openSpan(); out.append("<span class=\"np\">1</span>"); closeSpan() }
                        }
                    }
                }
                continue
            }
            if (k.name == "instrText") { if (c.fieldStack.isNotEmpty() && c.fieldStack.last()[0] == 0) c.instr.append(k.text); continue }
            val f = c.fieldStack.lastOrNull()
            if (f != null && (f[0] == 0 || f[1] != 0)) continue     // instruksi field / hasil PAGE yang sudah diganti
            if (hidden && k.name != "br") continue
            when (k.name) {
                "t", "delText" -> if (k.name == "t") {
                    val t = k.text
                    if (t.isNotEmpty()) {
                        openSpan()
                        val font = fontOf(c, rp)?.lowercase()
                        if (font != null && (font.contains("symbol") || font.contains("wingding"))) out.append(OxUtil.esc(t.map { OxUtil.symbolChar(it, font) }.joinToString("")))
                        else out.append(OxUtil.esc(t))
                    }
                }
                "tab" -> { openSpan(); out.append("<span class=\"tab\"></span>") }
                "ptab" -> { openSpan(); out.append("<span class=\"tab\" data-pa=\"").append(k.attr("alignment") ?: "left").append("\"></span>") }
                "br" -> {
                    when (k.attr("type")) {
                        "page" -> if (sect >= 0) {
                            closeSpan(); out.append("</div><div class=\"pb\" data-s=\"$sect\"></div>").append(reopen.replace("text-indent:", "x-ti:"))
                        } else { openSpan(); out.append("<br>") }
                        "column" -> { openSpan(); out.append("<br>") }
                        else -> { openSpan(); out.append("<br>") }
                    }
                }
                "cr" -> { openSpan(); out.append("<br>") }
                "noBreakHyphen" -> { openSpan(); out.append("&#8209;") }
                "softHyphen" -> { openSpan(); out.append("&shy;") }
                "sym" -> {
                    val ch = k.attr("char")?.toIntOrNull(16)?.toChar() ?: continue
                    openSpan(); out.append(OxUtil.esc(OxUtil.symbolChar(ch, k.attr("font"))))
                }
                "footnoteReference", "endnoteReference" -> {
                    val id = k.attr("id") ?: continue
                    val isF = k.name == "footnoteReference"
                    val no = if (isF) ++c.noteNo else ++c.endNo
                    c.noteOrder.add((if (isF) "f:" else "e:") + id to no)
                    openSpan(); out.append("<sup>").append(no).append("</sup>")
                }
                "footnoteRef", "endnoteRef" -> {}
                "drawing" -> { closeSpan(); drawing(c, k, out, sect) }
                "pict", "object" -> { closeSpan(); vml(c, k, out, sect) }
                "lastRenderedPageBreak", "rPr", "commentReference", "annotationRef" -> {}
            }
        }
        closeSpan()
    }

    // ------------------------------------------------------------------ gambar & bentuk
    private fun imgSrc(c: Ctx, rid: String?): String? {
        val rel = rid?.let { c.rels[it] } ?: return null
        if (rel.external) return null
        val bytes = c.pkg.files[rel.target] ?: return null
        c.res[rel.target] = bytes
        return "https://doc.local/" + rel.target
    }

    private fun emu(v: String?): Double = (v?.toLongOrNull() ?: 0L) / 12700.0

    private fun drawing(c: Ctx, d: XNode, out: StringBuilder, sect: Int) {
        val anchor = d.child("anchor")
        val box = anchor ?: d.child("inline") ?: return
        val ext = box.child("extent")
        val w = emu(ext?.attr("cx")); val h = emu(ext?.attr("cy"))
        val inner = StringBuilder()
        val gd = box.path("graphic", "graphicData")
        graphicContent(c, gd, w, h, inner)
        if (inner.isEmpty()) return
        if (anchor == null) {
            out.append("<span class=\"ig\" style=\"display:inline-block;vertical-align:bottom;width:${w}pt;height:${h}pt\">").append(inner).append("</span>")
            return
        }
        val behind = anchor.attr("behindDoc") == "1"
        val ph = anchor.child("positionH"); val pv = anchor.child("positionV")
        val hRel = ph?.attr("relativeFrom") ?: "column"; val vRel = pv?.attr("relativeFrom") ?: "paragraph"
        val hOff = ph?.child("posOffset")?.text?.let { emu(it) }; val hAl = ph?.child("align")?.text
        val vOff = pv?.child("posOffset")?.text?.let { emu(it) }; val vAl = pv?.child("align")?.text
        val wrap = when { anchor.child("wrapNone") != null -> "none"; anchor.child("wrapTopAndBottom") != null -> "tb"; anchor.child("wrapSquare") != null || anchor.child("wrapTight") != null || anchor.child("wrapThrough") != null -> "sq"; else -> "none" }
        val dl = emu(anchor.attr("distL")); val dr = emu(anchor.attr("distR")); val dt = emu(anchor.attr("distT")); val db = emu(anchor.attr("distB"))
        if (wrap == "tb") {
            out.append("<span class=\"anc tb\" style=\"display:block;width:${w}pt;height:${h}pt;margin:${dt}pt 0 ${db}pt ${if (hAl == null && hRel != "page") (hOff ?: 0.0) else 0.0}pt\"")
            if (hAl != null) out.append(" data-ha=\"$hAl\"")
            out.append(">").append(inner).append("</span>")
            return
        }
        if (wrap == "sq") {
            val right = hAl == "right" || hAl == "outside" || (hAl == null && (hOff ?: 0.0) > 200)
            out.append("<span class=\"anc sq\" style=\"float:${if (right) "right" else "left"};width:${w}pt;height:${h}pt;margin:${dt}pt ${dr}pt ${db}pt ${dl}pt;")
            if (!right && hAl == null && hRel != "page") out.append("margin-left:${(hOff ?: 0.0)}pt;")
            out.append("\">").append(inner).append("</span>")
            return
        }
        out.append("<span class=\"anc abs\" data-hr=\"$hRel\" data-vr=\"$vRel\"")
        if (hOff != null) out.append(" data-hx=\"$hOff\""); if (hAl != null) out.append(" data-ha=\"$hAl\"")
        if (vOff != null) out.append(" data-vy=\"$vOff\""); if (vAl != null) out.append(" data-va=\"$vAl\"")
        out.append(" data-w=\"$w\" data-h=\"$h\" style=\"position:absolute;width:${w}pt;height:${h}pt;z-index:${if (behind) 0 else 3}\">").append(inner).append("</span>")
    }

    private fun graphicContent(c: Ctx, gd: XNode?, w: Double, h: Double, out: StringBuilder) {
        if (gd == null) return
        gd.child("pic")?.let { pic -> picture(c, pic, w, h, out); return }
        gd.child("wsp")?.let { sp -> shapeBox(c, sp, w, h, out); return }
        gd.child("wgp")?.let { g -> group(c, g, w, h, out); return }
        if (gd.child("chart") != null || gd.first("chart") != null) {
            out.append("<span class=\"ph\" style=\"width:${w}pt;height:${h}pt\">Grafik</span>"); return
        }
        gd.first("blip")?.let { b -> imgSrc(c, b.attr("embed"))?.let { out.append("<img src=\"$it\" style=\"width:${w}pt;height:${h}pt\">") } }
    }

    private fun picture(c: Ctx, pic: XNode, w: Double, h: Double, out: StringBuilder) {
        val blip = pic.first("blip")
        val src = imgSrc(c, blip?.attr("embed"))
        val target = blip?.attr("embed")?.let { c.rels[it]?.target } ?: ""
        if (src == null || !OxUtil.isImage(target)) {
            out.append("<span class=\"ph\" style=\"width:${w}pt;height:${h}pt\"></span>"); return
        }
        val crop = pic.first("srcRect")
        val rot = pic.first("xfrm")?.attr("rot")?.toLongOrNull()?.let { it / 60000.0 } ?: 0.0
        val tr = if (rot != 0.0) "transform:rotate(${rot}deg);" else ""
        if (crop != null && crop.attrs.isNotEmpty()) {
            val l = (crop.attr("l")?.toDoubleOrNull() ?: 0.0) / 100000; val t = (crop.attr("t")?.toDoubleOrNull() ?: 0.0) / 100000
            val r = (crop.attr("r")?.toDoubleOrNull() ?: 0.0) / 100000; val b = (crop.attr("b")?.toDoubleOrNull() ?: 0.0) / 100000
            val fw = w / max(0.01, 1 - l - r); val fh = h / max(0.01, 1 - t - b)
            out.append("<span style=\"display:block;overflow:hidden;width:${w}pt;height:${h}pt;$tr\"><img src=\"$src\" style=\"width:${fw}pt;height:${fh}pt;margin-left:${-fw * l}pt;margin-top:${-fh * t}pt;max-width:none\"></span>")
        } else out.append("<img src=\"$src\" style=\"width:${w}pt;height:${h}pt;$tr\">")
    }

    /** Warna isian DrawingML (srgbClr/schemeClr + lumMod/lumOff/tint/shade). */
    private fun dmlColor(c: Ctx, n: XNode?): Int? {
        if (n == null) return null
        val cn = n.child("srgbClr") ?: n.child("schemeClr") ?: n.child("sysClr") ?: n.child("prstClr") ?: return null
        var rgb = when (cn.name) {
            "srgbClr" -> OxUtil.parseHex(cn.v)
            "sysClr" -> OxUtil.parseHex(cn.attr("lastClr"))
            "schemeClr" -> c.theme[when (cn.v) { "tx1" -> "dk1"; "bg1" -> "lt1"; "tx2" -> "dk2"; "bg2" -> "lt2"; else -> cn.v ?: "" }]
            else -> when (cn.v) { "black" -> 0; "white" -> 0xFFFFFF; "red" -> 0xFF0000; else -> 0x808080 }
        } ?: return null
        var mod = 1f; var off = 0f
        for (m in cn.kids) when (m.name) {
            "lumMod" -> mod = (m.v?.toFloatOrNull() ?: 100000f) / 100000f
            "lumOff" -> off = (m.v?.toFloatOrNull() ?: 0f) / 100000f
            "tint" -> rgb = OxUtil.mix(0xFFFFFF, rgb, (m.v?.toFloatOrNull() ?: 100000f) / 100000f)
            "shade" -> rgb = OxUtil.mix(0, rgb, (m.v?.toFloatOrNull() ?: 100000f) / 100000f)
        }
        if (mod != 1f || off != 0f) rgb = OxUtil.lum(rgb, mod, off)
        return rgb
    }

    private fun shapeBox(c: Ctx, sp: XNode, w: Double, h: Double, out: StringBuilder) {
        val spPr = sp.child("spPr")
        val css = StringBuilder("display:block;position:relative;width:${w}pt;height:${h}pt;box-sizing:border-box;overflow:hidden;")
        spPr?.child("xfrm")?.let { x ->
            val rot = (x.attr("rot")?.toLongOrNull() ?: 0L) / 60000.0
            val fh = x.attr("flipH") == "1"; val fv = x.attr("flipV") == "1"
            if (rot != 0.0 || fh || fv) css.append("transform:").append(if (rot != 0.0) "rotate(${rot}deg) " else "").append(if (fh) "scaleX(-1) " else "").append(if (fv) "scaleY(-1)" else "").append(';')
        }
        if (spPr?.child("noFill") == null) dmlColor(c, spPr?.child("solidFill"))?.let { css.append("background:").append(OxUtil.hex(it)).append(';') }
            ?: run { if (spPr?.child("solidFill") == null) sp.path("style", "fillRef")?.let { fr -> if (fr.attr("idx") != "0") dmlColor(c, fr)?.let { css.append("background:").append(OxUtil.hex(it)).append(';') } } }
        val ln = spPr?.child("ln")
        if (ln != null && ln.child("noFill") == null) {
            val lw = max(0.5, emu(ln.attr("w")).takeIf { it > 0 } ?: 0.75)
            val col = dmlColor(c, ln.child("solidFill")) ?: 0
            css.append("border:${lw}pt solid ${OxUtil.hex(col)};")
        } else if (ln == null) sp.path("style", "lnRef")?.let { lr -> if (lr.attr("idx") != "0") dmlColor(c, lr)?.let { css.append("border:0.75pt solid ${OxUtil.hex(it)};") } }
        val prst = spPr?.child("prstGeom")?.attr("prst") ?: "rect"
        if (prst != "rect" && prst != "roundRect" && prst != "ellipse") {
            // bentuk lain (segitiga, bintang, panah, garis …): gambar SVG; latar/garis dipindah ke path
            val cssS = css.toString()
            val bgM = Regex("background:([^;]+);").find(cssS)?.groupValues?.get(1)
            val bdM = Regex("border:([0-9.]+)pt solid ([^;]+);").find(cssS)
            val cleaned = css.toString().replace(Regex("background:[^;]+;"), "").replace(Regex("border:[^;]+;"), "")
            css.setLength(0); css.append(cleaned)
            val adj = spPr?.first("avLst")?.children("gd")?.associate { (it.attr("name") ?: "") to (it.attr("fmla")?.substringAfter("val ")?.trim()?.toDoubleOrNull() ?: 0.0) } ?: emptyMap()
            val isLine = prst == "line" || prst.contains("Connector")
            val d = PptxHtml.prstPath(prst, w, h, adj)
            val sw = bdM?.groupValues?.get(1) ?: "0.75"
            val svg = "<svg style=\"position:absolute;left:0;top:0;overflow:visible\" width=\"${w}pt\" height=\"${h}pt\" viewBox=\"0 0 $w $h\" preserveAspectRatio=\"none\"><path d=\"$d\" fill=\"${if (isLine) "none" else bgM ?: "none"}\" stroke=\"${bdM?.groupValues?.get(2) ?: if (isLine) "#000" else "none"}\" stroke-width=\"$sw\" vector-effect=\"non-scaling-stroke\"/></svg>"
            css.append("overflow:visible;")
            out.append("<span class=\"shp\" style=\"").append(css).append("\">").append(svg)
            sp.path("txbx", "txbxContent")?.let { tc ->
                out.append("<span style=\"display:block;position:relative\">")
                for (p in flatten(tc.kids)) block(c, p, out, -1, null)
                out.append("</span>")
            }
            out.append("</span>")
            return
        }
        when (prst) { "ellipse" -> css.append("border-radius:50%;"); "roundRect" -> css.append("border-radius:10%;") }
        val bp = sp.child("bodyPr")
        val li = bp?.attr("lIns")?.let { emu(it) } ?: 7.2; val ri = bp?.attr("rIns")?.let { emu(it) } ?: 7.2
        val ti = bp?.attr("tIns")?.let { emu(it) } ?: 3.6; val bi = bp?.attr("bIns")?.let { emu(it) } ?: 3.6
        css.append("padding:${ti}pt ${ri}pt ${bi}pt ${li}pt;")
        when (bp?.attr("anchor")) { "ctr" -> css.append("display:flex;flex-direction:column;justify-content:center;"); "b" -> css.append("display:flex;flex-direction:column;justify-content:flex-end;") }
        out.append("<span class=\"shp\" style=\"").append(css).append("\">")
        sp.path("txbx", "txbxContent")?.let { tc ->
            out.append("<span style=\"display:block\">")
            for (p in flatten(tc.kids)) block(c, p, out, -1, null)
            out.append("</span>")
        }
        out.append("</span>")
    }

    private fun group(c: Ctx, g: XNode, w: Double, h: Double, out: StringBuilder) {
        val xf = g.path("grpSpPr", "xfrm")
        val chOffX = emu(xf?.child("chOff")?.attr("x")); val chOffY = emu(xf?.child("chOff")?.attr("y"))
        val chW = emu(xf?.child("chExt")?.attr("cx")).takeIf { it > 0 } ?: w; val chH = emu(xf?.child("chExt")?.attr("cy")).takeIf { it > 0 } ?: h
        val sx = w / chW; val sy = h / chH
        out.append("<span style=\"display:block;position:relative;width:${w}pt;height:${h}pt\">")
        for (k in g.kids) {
            val kx = (k.child("spPr") ?: k.child("grpSpPr"))?.child("xfrm") ?: continue
            val x = (emu(kx.child("off")?.attr("x")) - chOffX) * sx; val y = (emu(kx.child("off")?.attr("y")) - chOffY) * sy
            val kw = emu(kx.child("ext")?.attr("cx")) * sx; val kh = emu(kx.child("ext")?.attr("cy")) * sy
            out.append("<span style=\"position:absolute;left:${x}pt;top:${y}pt;width:${kw}pt;height:${kh}pt\">")
            when (k.name) { "pic" -> picture(c, k, kw, kh, out); "wsp" -> shapeBox(c, k, kw, kh, out); "grpSp" -> group(c, k, kw, kh, out) }
            out.append("</span>")
        }
        out.append("</span>")
    }

    /** VML lama (w:pict / w:object): gambar & kotak teks. */
    private fun vml(c: Ctx, n: XNode, out: StringBuilder, sect: Int) {
        val shape = n.first("shape") ?: n.first("rect") ?: n.first("roundrect") ?: return
        val style = shape.attr("style") ?: ""
        fun dim(key: String): Double? {
            val m = Regex("(?:^|;)\\s*$key\\s*:\\s*([-0-9.]+)(pt|in|px|mm|cm)?").find(style) ?: return null
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            return when (m.groupValues[2]) { "in" -> v * 72; "px" -> v * 0.75; "mm" -> v * 72 / 25.4; "cm" -> v * 72 / 2.54; else -> v }
        }
        val w = dim("width") ?: 100.0; val h = dim("height") ?: 50.0
        val img = shape.first("imagedata")
        val inner = StringBuilder()
        if (img != null) {
            val src = imgSrc(c, img.attr("id"))
            val target = img.attr("id")?.let { c.rels[it]?.target } ?: ""
            if (src != null && OxUtil.isImage(target)) inner.append("<img src=\"$src\" style=\"width:${w}pt;height:${h}pt\">")
            else inner.append("<span class=\"ph\" style=\"width:${w}pt;height:${h}pt\"></span>")
        } else shape.first("txbxContent")?.let { tc ->
            val fill = OxUtil.parseHex(shape.attr("fillcolor")?.substringBefore(' '))
            inner.append("<span style=\"display:block;width:${w}pt;min-height:${h}pt;box-sizing:border-box;padding:3.6pt 7.2pt;")
            if (shape.attr("stroked") != "f") inner.append("border:0.75pt solid ${OxUtil.hex(OxUtil.parseHex(shape.attr("strokecolor")) ?: 0)};")
            fill?.let { inner.append("background:${OxUtil.hex(it)};") }
            inner.append("\">")
            for (p in flatten(tc.kids)) block(c, p, inner, -1, null)
            inner.append("</span>")
        }
        if (inner.isEmpty()) return
        if (style.contains("position:absolute")) {
            val ml = dim("margin-left") ?: dim("left") ?: 0.0; val mt = dim("margin-top") ?: dim("top") ?: 0.0
            val hr = Regex("mso-position-horizontal-relative:([a-z-]+)").find(style)?.groupValues?.get(1) ?: "column"
            val vr = Regex("mso-position-vertical-relative:([a-z-]+)").find(style)?.groupValues?.get(1) ?: "paragraph"
            out.append("<span class=\"anc abs\" data-hr=\"${if (hr == "page") "page" else if (hr == "margin") "margin" else "column"}\" data-vr=\"${if (vr == "page") "page" else if (vr == "margin") "margin" else "paragraph"}\" data-hx=\"$ml\" data-vy=\"$mt\" data-w=\"$w\" data-h=\"$h\" style=\"position:absolute;width:${w}pt;height:${h}pt;z-index:${if (style.contains("z-index:-")) 0 else 3}\">").append(inner).append("</span>")
        } else out.append("<span style=\"display:inline-block;vertical-align:bottom\">").append(inner).append("</span>")
    }

    // ------------------------------------------------------------------ tabel
    private class Cond(val pp: PP?, val rp: RP?, val shd: Int?, val bdr: Map<String, Bdr>?, val tcPr: XNode?)

    private fun table(c: Ctx, t: XNode, out: StringBuilder, sect: Int) {
        val tblPr = t.child("tblPr")
        val styleId = tblPr?.child("tblStyle")?.v ?: c.defTableStyle
        val chain = styleChain(c, styleId)
        // gaya tabel: properti keseluruhan + bersyarat
        var tsPP: PP? = null; var tsRP: RP? = null
        val tsBorders = HashMap<String, Bdr>(); var tsShd: Int? = null
        val conds = HashMap<String, Cond>()
        var cellMar = intArrayOf(0, 108, 0, 108)   // top, left(start), bottom, right
        for (s in chain) {
            s.node.child("pPr")?.let { tsPP = (tsPP ?: PP()).over(parsePP(c, it)) }
            s.node.child("rPr")?.let { tsRP = (tsRP ?: RP()).over(parseRP(c, it)) }
            s.node.child("tblPr")?.let { tp ->
                tp.child("tblBorders")?.kids?.forEach { b -> bdrOf(b)?.let { tsBorders[norm(b.name)] = it } }
                tp.child("tblCellMar")?.let { cellMar = mar(it, cellMar) }
            }
            s.node.child("tcPr")?.let { tc -> shdOf(c, tc.child("shd"))?.let { tsShd = it } }
            for (cp in s.node.children("tblStylePr")) {
                val type = cp.attr("type") ?: continue
                val prev = conds[type]
                val bd = HashMap<String, Bdr>(prev?.bdr ?: emptyMap())
                cp.path("tcPr", "tcBorders")?.kids?.forEach { b -> bdrOf(b)?.let { bd[norm(b.name)] = it } }
                conds[type] = Cond(
                    (prev?.pp ?: PP()).over(cp.child("pPr")?.let { parsePP(c, it) }),
                    (prev?.rp ?: RP()).over(cp.child("rPr")?.let { parseRP(c, it) }),
                    shdOf(c, cp.path("tcPr", "shd")) ?: prev?.shd, bd, cp.child("tcPr") ?: prev?.tcPr
                )
            }
        }
        // langsung
        val tBorders = HashMap(tsBorders)
        tblPr?.child("tblBorders")?.kids?.forEach { b -> bdrOf(b)?.let { tBorders[norm(b.name)] = it } }
        tblPr?.child("tblCellMar")?.let { cellMar = mar(it, cellMar) }
        val look = tblPr?.child("tblLook")
        fun lookFlag(attr: String, bit: Int, def: Boolean): Boolean {
            look?.attr(attr)?.let { return it == "1" || it == "true" }
            val v = look?.v?.toIntOrNull(16) ?: return def
            return v and bit != 0
        }
        val firstRow = lookFlag("firstRow", 0x20, true); val lastRow = lookFlag("lastRow", 0x40, false)
        val firstCol = lookFlag("firstColumn", 0x80, true); val lastCol = lookFlag("lastColumn", 0x100, false)
        val noHBand = lookFlag("noHBand", 0x200, false); val noVBand = lookFlag("noVBand", 0x400, true)

        val grid = t.child("tblGrid")?.children("gridCol")?.map { it.attr("w")?.toIntOrNull() ?: 0 } ?: emptyList()
        val rows = through(t, "tr")
        // susun sel → kolom grid untuk menghitung rowspan (vMerge)
        class Cell(val node: XNode, val col: Int, val span: Int, var rowspan: Int = 1, var skip: Boolean = false)
        val R = rows.map { tr ->
            var col = tr.path("trPr", "gridBefore")?.v?.toIntOrNull() ?: 0
            through(tr, "tc").map { tc ->
                val sp = tc.path("tcPr", "gridSpan")?.v?.toIntOrNull() ?: 1
                Cell(tc, col, sp).also { col += sp }
            }
        }
        for ((ri, row) in R.withIndex()) for (cell in row) {
            val vm = cell.node.path("tcPr", "vMerge") ?: continue
            if (vm.v == "restart") {
                var n = 1
                for (rj in ri + 1 until R.size) {
                    val below = R[rj].firstOrNull { it.col == cell.col } ?: break
                    val bvm = below.node.path("tcPr", "vMerge")
                    if (bvm != null && bvm.v != "restart") { below.skip = true; n++ } else break
                }
                cell.rowspan = n
            } else if (vm.v == null || vm.v == "continue") {
                // sel lanjutan yang belum tertandai (tak ada restart di atas): tampilkan apa adanya
            }
        }
        val totalGrid = grid.sum()
        val tblW = tblPr?.child("tblW")
        val widthCss = when (tblW?.attr("type")) {
            "pct" -> { val v = tblW.attr("w") ?: "0"; if (v.endsWith("%")) "width:$v;" else "width:${(v.toDoubleOrNull() ?: 0.0) / 50}%;" }
            "dxa" -> (tblW.attr("w")?.toIntOrNull() ?: 0).takeIf { it > 0 }?.let { "width:${it / 20.0}pt;" } ?: if (totalGrid > 0) "width:${totalGrid / 20.0}pt;" else ""
            else -> if (totalGrid > 0) "width:${totalGrid / 20.0}pt;" else ""
        }
        val jc = tblPr?.child("jc")?.v
        val ind = tblPr?.child("tblInd")?.attr("w")?.toIntOrNull() ?: 0
        out.append("<table class=\"").append(if (sect >= 0) "b" else "").append("\"")
        if (sect >= 0) out.append(" data-s=\"$sect\"")
        out.append(" style=\"").append(widthCss)
        when (jc) { "center" -> out.append("margin-left:auto;margin-right:auto;"); "right", "end" -> out.append("margin-left:auto;") ; else -> if (ind != 0) out.append("margin-left:${(ind - cellMar[1]) / 20.0}pt;") else out.append("margin-left:${-cellMar[1] / 20.0}pt;") }
        if (tblPr?.child("tblLayout")?.attr("type") == "fixed" || grid.isNotEmpty()) out.append("table-layout:fixed;")
        out.append("\">")
        if (grid.isNotEmpty()) { out.append("<colgroup>"); for (g in grid) out.append("<col style=\"width:${g / 20.0}pt\">"); out.append("</colgroup>") }
        val nCols = max(grid.size, R.maxOfOrNull { r -> r.lastOrNull()?.let { it.col + it.span } ?: 0 } ?: 0)
        val nRows = R.size
        var bandRow = 0
        for ((ri, row) in R.withIndex()) {
            val tr = rows[ri]
            val trPr = tr.child("trPr")
            val isHeader = trPr?.child("tblHeader") != null
            val hgt = trPr?.child("trHeight")
            out.append("<tr")
            if (isHeader) out.append(" class=\"th\"")
            if (hgt != null) {
                val hv = (hgt.v?.toIntOrNull() ?: 0) / 20.0
                out.append(" style=\"height:${hv}pt\"")
            }
            out.append(">")
            val isFirst = firstRow && ri == 0; val isLast = lastRow && ri == nRows - 1
            if (!isFirst && !isLast) bandRow++
            for (cell in row) {
                if (cell.skip) continue
                val tcPr = cell.node.child("tcPr")
                // syarat yang berlaku (urutan prioritas rendah → tinggi)
                val applied = ArrayList<Cond>()
                fun add(k: String) { conds[k]?.let { applied.add(it) } }
                if (!noHBand && !isFirst && !isLast) add(if ((bandRow - 1) % 2 == 0) "band1Horz" else "band2Horz")
                if (!noVBand) add(if (cell.col % 2 == 0) "band1Vert" else "band2Vert")
                if (firstCol && cell.col == 0) add("firstCol")
                if (lastCol && cell.col + cell.span == nCols) add("lastCol")
                if (isFirst) add("firstRow"); if (isLast) add("lastRow")
                if (isFirst && firstCol && cell.col == 0) add("nwCell"); if (isFirst && lastCol && cell.col + cell.span == nCols) add("neCell")
                if (isLast && firstCol && cell.col == 0) add("swCell"); if (isLast && lastCol && cell.col + cell.span == nCols) add("seCell")
                var cPP = tsPP; var cRP = tsRP; var shd = tsShd
                val bd = HashMap<String, Bdr?>()
                // garis tepi dasar dari tabel
                bd["top"] = if (ri == 0) tBorders["top"] else tBorders["insideH"]
                bd["bottom"] = if (ri + cell.rowspan >= nRows) tBorders["bottom"] else tBorders["insideH"]
                bd["left"] = if (cell.col == 0) tBorders["left"] else tBorders["insideV"]
                bd["right"] = if (cell.col + cell.span >= nCols) tBorders["right"] else tBorders["insideV"]
                for (a in applied) {
                    a.pp?.let { cPP = (cPP ?: PP()).over(it) }; a.rp?.let { cRP = (cRP ?: RP()).over(it) }; a.shd?.let { shd = it }
                    a.bdr?.forEach { (k, v) -> when (k) { "insideH" -> { if (ri > 0) bd["top"] = v; if (ri + cell.rowspan < nRows) bd["bottom"] = v }
                        "insideV" -> { if (cell.col > 0) bd["left"] = v; if (cell.col + cell.span < nCols) bd["right"] = v }
                        else -> bd[k] = v } }
                }
                tcPr?.child("tcBorders")?.kids?.forEach { b -> bd[norm(b.name)] = bdrOf(b) }
                shdOf(c, tcPr?.child("shd"))?.let { shd = it }
                var mt = cellMar[0]; var ml = cellMar[1]; var mb = cellMar[2]; var mr = cellMar[3]
                tcPr?.child("tcMar")?.let { m -> val a = mar(m, intArrayOf(mt, ml, mb, mr)); mt = a[0]; ml = a[1]; mb = a[2]; mr = a[3] }
                out.append("<td")
                if (cell.span > 1) out.append(" colspan=\"${cell.span}\"")
                if (cell.rowspan > 1) out.append(" rowspan=\"${cell.rowspan}\"")
                out.append(" style=\"padding:${mt / 20.0}pt ${mr / 20.0}pt ${mb / 20.0}pt ${ml / 20.0}pt;")
                for (side in listOf("top", "bottom", "left", "right")) { val b = bd[side]; if (b != null) out.append("border-$side:").append(bdrCss(b)).append(';') }
                shd?.let { out.append("background:${OxUtil.hex(it)};") }
                when (tcPr?.child("vAlign")?.v) { "center" -> out.append("vertical-align:middle;"); "bottom" -> out.append("vertical-align:bottom;") }
                if (tcPr?.child("noWrap") != null) out.append("white-space:nowrap;")
                out.append("\">")
                val kids = flatten(cell.node.kids)
                if (kids.isEmpty()) out.append("<div class=\"p\">&#8203;</div>")
                val savedBg = c.bg
                if (shd != null) c.bg = shd
                for (k in kids) block(c, k, out, -1, cPP to cRP)
                c.bg = savedBg
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
    }

    private fun norm(n: String) = when (n) { "start" -> "left"; "end" -> "right"; else -> n }

    private fun mar(n: XNode, base: IntArray): IntArray {
        val a = base.copyOf()
        n.child("top")?.attr("w")?.toIntOrNull()?.let { a[0] = it }
        (n.child("left") ?: n.child("start"))?.attr("w")?.toIntOrNull()?.let { a[1] = it }
        n.child("bottom")?.attr("w")?.toIntOrNull()?.let { a[2] = it }
        (n.child("right") ?: n.child("end"))?.attr("w")?.toIntOrNull()?.let { a[3] = it }
        return a
    }

    // ------------------------------------------------------------------ bagian
    private fun parseSect(sp: XNode?, prev: Sect?): Sect {
        val pg = sp?.child("pgSz"); val m = sp?.child("pgMar")
        var w = pg?.attr("w")?.toIntOrNull() ?: prev?.w ?: 11906; var h = pg?.attr("h")?.toIntOrNull() ?: prev?.h ?: 16838
        if (pg?.attr("orient") == "landscape" && w < h) { val t = w; w = h; h = t }
        fun mv(a: String, d: Int) = m?.attr(a)?.toIntOrNull() ?: d
        val hdr = HashMap(prev?.hdr ?: emptyMap()); val ftr = HashMap(prev?.ftr ?: emptyMap())
        sp?.children("headerReference")?.forEach { r -> val t = r.attr("type") ?: "default"; r.attr("id")?.let { hdr[t] = it } }
        sp?.children("footerReference")?.forEach { r -> val t = r.attr("type") ?: "default"; r.attr("id")?.let { ftr[t] = it } }
        return Sect(w, h, kotlin.math.abs(mv("top", prev?.top ?: 1440)), mv("right", prev?.right ?: 1440), kotlin.math.abs(mv("bottom", prev?.bottom ?: 1440)), mv("left", prev?.left ?: 1440),
            mv("header", prev?.header ?: 720), mv("footer", prev?.footer ?: 720), sp?.child("titlePg")?.let { it.v != "0" && it.v != "false" } ?: false,
            hdr, ftr, sp?.child("type")?.v ?: "nextPage", sp?.child("cols")?.attr("num")?.toIntOrNull() ?: 1)
    }

    // ------------------------------------------------------------------ CSS & JS halaman
    private const val CSS = """
html,body{margin:0;padding:0;background:#e9eaee}
#flow{position:absolute;left:-10000px;top:0;visibility:hidden}
#pages{padding:12px 0}
.page{position:relative;background:#fff;margin:0 auto 12px;box-shadow:0 1px 4px rgba(0,0,0,.25);overflow:hidden;color:#000}
.content{position:absolute;overflow:visible}
.hdr,.ftr{position:absolute;overflow:visible}
.p{margin:0;white-space:pre-wrap;overflow-wrap:break-word}
table{border-collapse:collapse;border-spacing:0}
td{vertical-align:top;overflow-wrap:break-word}
td>.p:first-child{margin-top:0}
img{display:inline-block;vertical-align:bottom;max-width:none}
a{color:inherit;text-decoration:inherit}
.tab{display:inline-block;white-space:pre}
.lbl{display:inline-block;text-indent:0;white-space:pre}
.ph{display:inline-block;background:#f1f3f6;border:1px dashed #b9bec8;box-sizing:border-box;color:#8a8f99;font:9pt sans-serif;text-align:center}
.notes{font-size:9pt}.notes hr{width:33%;margin:6pt 0 3pt 0;border:0;border-top:0.5pt solid #000}
.note .p{display:inline}
.pb,.sect{height:0}
@media print{html,body{background:#fff}#pages{padding:0}.page{margin:0;box-shadow:none;break-after:page;page-break-after:always}}
"""

    private const val JS = """
function __layout(){
var PT=4/3;
function px(pt){return pt*PT}
var flow=document.getElementById('flow'),pagesEl=document.getElementById('pages');
// spasi kontekstual: paragraf bergaya sama berurutan tanpa jarak
(function(){var ps=flow.querySelectorAll('.p[data-cs]');for(var i=0;i<ps.length;i++){var p=ps[i],n=p.nextElementSibling;if(n&&n.classList.contains('p')&&n.getAttribute('data-cs')==p.getAttribute('data-cs')){p.style.marginBottom='0';n.style.marginTop='0';}}})();
function solveTabs(root){
 var ps=root.querySelectorAll('.p');
 for(var i=0;i<ps.length;i++){
  var p=ps[i],tabs=p.querySelectorAll('.tab');if(!tabs.length)continue;
  var stops=[];var ds=p.getAttribute('data-tabs');
  if(ds)ds.split(';').forEach(function(s){var a=s.split(':');stops.push({pos:+a[0],al:a[1],ld:a[2]});});
  stops.sort(function(a,b){return a.pos-b.pos});
  var il=+(p.getAttribute('data-il')||0);
  for(var t=0;t<tabs.length;t++){
   var tb=tabs[t];tb.style.width='0px';
   var pr=p.getBoundingClientRect(),cs=getComputedStyle(p);
   var origin=pr.left+parseFloat(cs.borderLeftWidth||0)+parseFloat(cs.paddingLeft||0)-px(il);
   var x=(tb.getBoundingClientRect().left-origin)/PT;
   var st=null;for(var k=0;k<stops.length;k++){if(stops[k].pos>x+0.5){st=stops[k];break;}}
   var pa=tb.getAttribute('data-pa');
   if(pa){var cw=(pr.width-parseFloat(cs.paddingLeft||0)-parseFloat(cs.paddingRight||0))/PT+il;st={pos:pa=='right'?cw:pa=='center'?cw/2:0,al:pa,ld:'none'};}
   if(!st){var d=DEFTAB||36;st={pos:(Math.floor(x/d+1e-6)+1)*d,al:'left',ld:'none'};}
   var w=st.pos-x;
   if(st.al=='right'||st.al=='end'||st.al=='center'||st.al=='decimal'){
    // lebar teks sesudah tab sampai tab berikutnya / akhir baris
    var seg=0,n=tb,stop=false;
    var r=document.createRange();r.setStartAfter(tb);
    var nxt=tabs[t+1];if(nxt)r.setEndBefore(nxt);else r.setEnd(p,p.childNodes.length);
    var rects=r.getClientRects();var tr=tb.getBoundingClientRect();
    for(var q=0;q<rects.length;q++){var rc=rects[q];if(rc.top<=tr.bottom+1&&rc.bottom>=tr.top-1)seg+=rc.width;}
    seg/=PT;w-=st.al=='center'?seg/2:seg+0.6;
   }
   if(w<0.5)w=0.5;
   tb.style.width=w+'pt';
   if(st.ld&&st.ld!='none'){var ls=st.ld=='dot'?'dotted':st.ld=='hyphen'?'dashed':'solid';tb.style.borderBottom='1px '+ls+' currentColor';tb.style.height='0.9em';tb.style.verticalAlign='baseline';tb.style.boxSizing='border-box';tb.style.marginBottom='0.2em';}
  }
 }
}
var pages=[];
function hf(page,s,kind,idx,first){
 var map=kind=='hdr'?s.hdr:s.ftr;var id=(first&&s.tp&&map.first)?map.first:(idx%2==0&&map.even?map.even:map['default']);
 if(first&&s.tp&&!map.first)return;
 if(!id)return;var tpl=document.getElementById('hf-'+id);if(!tpl)return;
 var d=document.createElement('div');d.className=kind;d.style.left=px(s.l)+'px';d.style.width=px(s.w-s.l-s.r)+'px';
 if(kind=='hdr')d.style.top=px(s.hd)+'px';else d.style.bottom=px(s.ft)+'px';
 d.innerHTML=tpl.innerHTML;page.appendChild(d);solveTabs(d);
}
function newPage(si,firstOfSect){
 var s=SECTS[si];var pg=document.createElement('div');pg.className='page';
 pg.style.width=px(s.w)+'px';pg.style.height=px(s.h)+'px';
 var c=document.createElement('div');c.className='content';
 c.style.left=px(s.l)+'px';c.style.top=px(s.t)+'px';c.style.width=px(s.w-s.l-s.r)+'px';c.style.height=px(s.h-s.t-s.b)+'px';
 pg.appendChild(c);pagesEl.appendChild(pg);
 var rec={el:pg,c:c,s:si,first:firstOfSect,idx:pages.length};pages.push(rec);return rec;
}
function overflow(rec){var c=rec.c;var last=c.lastElementChild;if(!last)return false;
 var cb=c.getBoundingClientRect().bottom;var lb=last.getBoundingClientRect().bottom+parseFloat(getComputedStyle(last).marginBottom||0)*0;return lb>cb+1;}
var blocks=[];for(var i=0;i<flow.children.length;i++)blocks.push(flow.children[i]);
// lebar sementara supaya tab dihitung pada lebar kolom yang benar
var cur=null,curS=-1;
function splitTable(tbl,rec){
 // pindahkan baris dari akhir sampai muat; sisanya ke halaman berikut (baris judul diulang)
 var rows=Array.prototype.slice.call(tbl.rows);if(rows.length<2)return null;
 var head=rows.filter(function(r){return r.classList.contains('th')});
 var rest=tbl.cloneNode(false);var cg=tbl.querySelector('colgroup');if(cg)rest.appendChild(cg.cloneNode(true));
 var tb=document.createElement('tbody');rest.appendChild(tb);
 var moved=0;
 while(tbl.rows.length>head.length+1&&overflow(rec)){var r=tbl.rows[tbl.rows.length-1];if(r.querySelector('[rowspan]')&&moved==0){}tb.insertBefore(r,tb.firstChild);moved++;}
 if(moved==0)return null;
 head.forEach(function(h){tb.insertBefore(h.cloneNode(true),tb.firstChild)});
 return rest;
}
for(var b=0;b<blocks.length;b++){
 var el=blocks[b];var si=+(el.getAttribute('data-s')||0);
 if(el.classList.contains('sect')){if(cur==null||si!=curS){var cont=cur&&SECTS[si].type=='continuous'&&SECTS[si].w==SECTS[curS].w;if(!cont){cur=newPage(si,true);}curS=si;}continue;}
 if(cur==null){cur=newPage(si,true);curS=si;}
 if(el.classList.contains('pb')){cur=newPage(curS,false);continue;}
 try{
 cur.c.appendChild(el);
 if(el.classList.contains('p'))solveTabs({querySelectorAll:function(){return[el].concat(Array.prototype.slice.call(el.querySelectorAll('.p')))}});else solveTabs(el);
 var guard=0;
 while(overflow(cur)&&guard++<200){
  if(cur.c.children.length==1){
   if(el.tagName=='TABLE'){var rest=splitTable(el,cur);if(rest){cur=newPage(curS,false);cur.c.appendChild(rest);el=rest;continue;}}
   break;
  }
  if(el.tagName=='TABLE'){var rest2=splitTable(el,cur);if(rest2){cur=newPage(curS,false);cur.c.appendChild(rest2);el=rest2;continue;}}
  // keepNext: bawa paragraf judul sebelumnya ikut pindah
  var mv=[el];var prev=el.previousElementSibling;
  while(prev&&prev.getAttribute('data-kn')=='1'&&mv.length<4&&cur.c.children.length>mv.length+1){mv.unshift(prev);prev=prev.previousElementSibling;}
  cur=newPage(curS,false);mv.forEach(function(m){cur.c.appendChild(m)});
 }
 }catch(err){try{if(el&&el.parentNode!==cur.c)cur.c.appendChild(el);}catch(e2){}if(window.console)console.log('layout: '+err);}
}
if(!pages.length)newPage(0,true);
var N=pages.length;
pages.forEach(function(rec,i){try{var s=SECTS[rec.s];hf(rec.el,s,'hdr',i+1,rec.first);hf(rec.el,s,'ftr',i+1,rec.first);
 var pg=rec.el.querySelectorAll('.pg');for(var k=0;k<pg.length;k++)pg[k].textContent=String(i+1);
 var np=rec.el.querySelectorAll('.np');for(var k2=0;k2<np.length;k2++)np[k2].textContent=String(N);
 // objek mengambang: posisikan relatif halaman / margin / paragraf
 var an=rec.el.querySelectorAll('.anc.abs');
 for(var a=0;a<an.length;a++){var e=an[a];var hr=e.getAttribute('data-hr'),vr=e.getAttribute('data-vr');
  var w=+e.getAttribute('data-w'),h=+e.getAttribute('data-h');
  var pr=rec.el.getBoundingClientRect();var par=e.parentElement;while(par&&!(par.classList&&par.classList.contains('p'))&&par!==rec.el)par=par.parentElement;
  var ppr=par?par.getBoundingClientRect():pr;
  var refL,refW;if(hr=='page'){refL=0;refW=s.w;}else if(hr=='character'){refL=(e.getBoundingClientRect().left-pr.left)/PT;refW=0;}else{refL=s.l;refW=s.w-s.l-s.r;}
  if(hr=='leftMargin'){refL=0;refW=s.l;}if(hr=='rightMargin'){refL=s.w-s.r;refW=s.r;}
  var x;var ha=e.getAttribute('data-ha');if(ha){x=ha=='center'?refL+(refW-w)/2:(ha=='right'||ha=='outside')?refL+refW-w:refL;}else x=refL+(+(e.getAttribute('data-hx')||0));
  var refT;if(vr=='page')refT=0;else if(vr=='margin'||vr=='topMargin')refT=s.t;else refT=(ppr.top-pr.top)/PT;
  var y;var va=e.getAttribute('data-va');if(va){var refH=vr=='page'?s.h:vr=='margin'?(s.h-s.t-s.b):0;y=va=='center'?refT+(refH-h)/2:va=='bottom'?refT+refH-h:refT;}else y=refT+(+(e.getAttribute('data-vy')||0));
  rec.el.appendChild(e);e.style.left=px(x)+'px';e.style.top=px(y)+'px';
 }
 }catch(err){if(window.console)console.log('page: '+err);}
});
flow.parentNode.removeChild(flow);
document.body.setAttribute('data-ready','1');
}
// tunggu font pengganti termuat dulu: ukuran teks menentukan tab & pemenggalan halaman
(function(){var done=false;function go(){if(done)return;done=true;
 try{__layout();}catch(err){
  // tata letak gagal total: tampilkan isi apa adanya (tetap lengkap dengan tabel), jangan sampai kosong
  var f=document.getElementById('flow');if(f){f.style.cssText='position:static;visibility:visible;background:#fff;padding:24px;margin:8px auto;max-width:900px';}
  document.body.setAttribute('data-ready','1');if(window.console)console.log('layout gagal: '+err);}}
 if(document.fonts&&document.fonts.ready){document.fonts.ready.then(go);setTimeout(go,2500);}else go();})();
"""
}
