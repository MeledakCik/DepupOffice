package cloud.depup.office

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * XLSX → HTML untuk PEMBACA: tampilan seperti Excel — kepala kolom A, B, C & nomor baris, lebar kolom & tinggi baris
 * asli, sel gabungan, font/warna/isian/garis tepi/perataan/bungkus teks dari styles.xml, format angka (ribuan, desimal,
 * persen, mata uang, tanggal & jam, termasuk format kustom), teks meluber ke sel kosong di sebelahnya, gambar, dan
 * tab lembar kerja di bawah. Murni Kotlin — diuji di JVM.
 */
object XlsxHtml {
    private const val MAX_ROWS = 3000
    private const val MAX_COLS = 120
    /** Excel sendiri hanya menampilkan 1.024 karakter di dalam sel (sisanya di bilah rumus). Teks sel super panjang
     *  yang diulang di ribuan sel dulu menghasilkan HTML bergiga-giga → kehabisan memori & dokumen tidak tampil. */
    private const val CELL_CHARS = 1024
    private fun cap(t: String) = if (t.length > CELL_CHARS) t.substring(0, CELL_CHARS) + "…" else t

    private class Font(val name: String?, val size: Double, val b: Boolean, val i: Boolean, val u: Boolean, val strike: Boolean, val color: Int?)
    private class Border(val l: Pair<String, Int?>?, val r: Pair<String, Int?>?, val t: Pair<String, Int?>?, val b: Pair<String, Int?>?)
    private class Xf(val numFmt: Int, val font: Int, val fill: Int, val border: Int, val h: String?, val v: String?, val wrap: Boolean, val indent: Int, val rot: Int, val shrink: Boolean)

    private class Ctx(val pkg: OPackage) {
        val theme = ArrayList<Int>()
        val fonts = ArrayList<Font>(); val fills = ArrayList<Int?>(); val borders = ArrayList<Border>(); val xfs = ArrayList<Xf>()
        val numFmts = HashMap<Int, String>()
        val shared = ArrayList<String>(); val sharedRich = ArrayList<String?>()
        var date1904 = false
        val res = DocRes(pkg)
        /**
         * Gaya sel → kelas CSS bersama. Dulu tiap sel membawa gaya inline sendiri (±200 karakter): lembar besar jadi
         * HTML puluhan MB dan WebView menghitung gaya tiap sel satu per satu (gaya inline tidak bisa dibagi).
         */
        val cellCls = HashMap<String, String>()
        val clsCss = StringBuilder()
        fun cls(css: String): String? = cellCls[css] ?: if (cellCls.size >= 6000) null
            else ("k" + cellCls.size).also { cellCls[css] = it; clsCss.append("table.grid td.").append(it).append('{').append(css).append('}') }
        var indexed: IntArray = DEFAULT_INDEXED.copyOf()
        /** Gambar di dalam sel: WPS (=DISPIMG("ID_…")) nama → bagian; Excel "Tempatkan di Sel": vm → nilai kaya → bagian. */
        val wpsImg = HashMap<String, String>()
        val vmRv = HashMap<Int, Int>()
        val rvImg = HashMap<Int, String>()
    }

    fun render(bytes: ByteArray, title: String): RenderedDoc = render(OPackage(bytes), title)

    fun render(pkg: OPackage, title: String): RenderedDoc {
        val wbPath = pkg.mainPart() ?: "xl/workbook.xml"
        val c = Ctx(pkg)
        val wbRels = pkg.rels(wbPath)
        val wb = pkg.xml(wbPath) ?: throw IllegalArgumentException("workbook.xml tidak ada")
        c.date1904 = wb.first("workbookPr")?.attr("date1904").let { it == "1" || it == "true" }
        wbRels.values.firstOrNull { it.type.endsWith("/theme") }?.let { loadTheme(c, it.target) }
        wbRels.values.firstOrNull { it.type.endsWith("/styles") }?.let { loadStyles(c, it.target) }
        wbRels.values.firstOrNull { it.type.endsWith("/sharedStrings") }?.let { loadShared(c, it.target) }
        try { loadCellImages(c, wbRels) } catch (_: Throwable) {}

        val sheets = wb.all("sheet").mapNotNull { s ->
            val rid = s.attr("id") ?: s.attr("r:id") ?: return@mapNotNull null
            val rel = wbRels[rid] ?: return@mapNotNull null
            Triple(s.attr("name") ?: "Sheet", rel.target, s.attr("state") ?: "visible")
        }.filter { it.third == "visible" }
        val active = wb.first("workbookView")?.attr("activeTab")?.toIntOrNull() ?: 0

        val body = StringBuilder()
        val tabs = StringBuilder()
        for ((idx, sh) in sheets.withIndex()) {
            val (name, path, _) = sh
            body.append("<div class=\"sheet-wrap\" id=\"s$idx\" style=\"display:").append(if (idx == active.coerceIn(0, sheets.size - 1)) "block" else "none").append("\">")
            try {
                if (pkg.has(path) && pkg.xml(path)?.first("chartsheet") == null) sheet(c, path, body)
                else body.append("<div class=\"empty\">Lembar grafik tidak dapat ditampilkan.</div>")
            } catch (e: Throwable) { body.append("<div class=\"empty\">Lembar ini tidak dapat dibaca.</div>") }
            body.append("</div>")
            tabs.append("<button class=\"tab").append(if (idx == active) " on" else "").append("\" onclick=\"show($idx)\">").append(OxUtil.esc(name)).append("</button>")
        }
        val defFont = c.fonts.firstOrNull()
        val html = StringBuilder()
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
            .append(OxUtil.esc(title)).append("</title><style>").append(OxUtil.fontFaces("https://doc.local/_fonts/")).append(CSS).append(OxUtil.viewerCss("xlsx"))
            .append("table.grid{font-family:").append(OxUtil.fontStack(defFont?.name)).append(";font-size:${defFont?.size ?: 11.0}pt}")
            .append(c.clsCss)
            .append("</style></head><body><div id=\"main\">").append(body).append("</div><div id=\"tabs\">").append(tabs).append("</div><script>").append(JS).append("</script></body></html>")
        c.res.finish()
        return RenderedDoc(html.toString(), c.res, title, "xlsx")
    }

    private val DISPIMG = Regex("DISPIMG\\(\\s*\"([^\"]+)\"")

    /** Sumber gambar dalam sel (WPS Spreadsheet & Excel 365). */
    private fun loadCellImages(c: Ctx, wbRels: Map<String, OPackage.Rel>) {
        fun part(suffix: String, def: String): String? = wbRels.values.firstOrNull { it.type.endsWith(suffix) }?.target ?: def.takeIf { c.pkg.has(it) }
        // WPS: xl/cellimages.xml — <etc:cellImage><xdr:pic>… name="ID_…" … <a:blip r:embed>
        val ci = wbRels.values.firstOrNull { it.type.contains("cellimage", ignoreCase = true) }?.target ?: "xl/cellimages.xml".takeIf { c.pkg.has(it) }
        if (ci != null) c.pkg.xml(ci)?.let { x ->
            val rels = c.pkg.rels(ci)
            for (pic in x.all("pic")) {
                val name = pic.first("cNvPr")?.attr("name") ?: continue
                val rel = pic.first("blip")?.attr("embed")?.let { rels[it] } ?: continue
                if (!rel.external) c.wpsImg[name] = rel.target
            }
        }
        // Excel: sel ber-vm → metadata.xml (XLRICHVALUE) → rdrichvalue.xml → richValueRel.xml → gambar
        val md = part("/sheetMetadata", "xl/metadata.xml") ?: return
        val rvPath = part("/rdRichValue", "xl/richData/rdrichvalue.xml") ?: return
        val relPath = part("/richValueRel", "xl/richData/richValueRel.xml") ?: return
        val mdx = c.pkg.xml(md) ?: return
        val types = mdx.first("metadataTypes")?.children("metadataType")?.map { it.attr("name") } ?: return
        val fut = mdx.all("futureMetadata").firstOrNull { it.attr("name") == "XLRICHVALUE" }?.children("bk")?.map { it.first("rvb")?.attr("i")?.toIntOrNull() ?: -1 } ?: return
        mdx.first("valueMetadata")?.children("bk")?.forEachIndexed { i, bk ->
            val rc = bk.child("rc") ?: return@forEachIndexed
            val t = rc.attr("t")?.toIntOrNull() ?: return@forEachIndexed
            if (types.getOrNull(t - 1) != "XLRICHVALUE") return@forEachIndexed
            val rv = fut.getOrNull(rc.attr("v")?.toIntOrNull() ?: -1) ?: return@forEachIndexed
            if (rv >= 0) c.vmRv[i + 1] = rv
        }
        val structs = c.pkg.xml(part("/rdRichValueStructure", "xl/richData/rdrichvaluestructure.xml") ?: "")?.all("s") ?: emptyList()
        val relx = c.pkg.xml(relPath) ?: return
        val relRels = c.pkg.rels(relPath)
        val relIds = relx.all("rel").map { it.attr("r:id") ?: it.attr("id") }
        c.pkg.xml(rvPath)?.all("rv")?.forEachIndexed { k, rv ->
            val st = structs.getOrNull(rv.attr("s")?.toIntOrNull() ?: -1)
            if (st != null && st.attr("t") != "_localImage") return@forEachIndexed
            val keyIdx = st?.children("k")?.indexOfFirst { it.attr("n") == "_rvRel:LocalImageIdentifier" }?.takeIf { it >= 0 } ?: 0
            val relIdx = rv.children("v").getOrNull(keyIdx)?.text?.trim()?.toIntOrNull() ?: return@forEachIndexed
            val rel = relIds.getOrNull(relIdx)?.let { relRels[it] } ?: return@forEachIndexed
            if (!rel.external) c.rvImg[k] = rel.target
        }
    }

    // ------------------------------------------------------------------ tema & gaya
    private fun loadTheme(c: Ctx, path: String) {
        val t = c.pkg.xml(path) ?: return
        val cs = t.first("clrScheme") ?: return
        // urutan indeks warna tema Excel: lt1, dk1, lt2, dk2, accent1..6, hlink, folHlink
        val order = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        val m = HashMap<String, Int>()
        cs.kids.forEach { k -> (k.child("srgbClr")?.v ?: k.child("sysClr")?.attr("lastClr"))?.let { OxUtil.parseHex(it)?.let { v -> m[k.name] = v } } }
        for (o in order) c.theme.add(m[o] ?: 0)
    }

    private fun color(c: Ctx, n: XNode?): Int? {
        if (n == null) return null
        if (n.attr("auto") == "1" || n.attr("auto") == "true") return null
        var rgb: Int? = n.attr("rgb")?.let { OxUtil.parseHex(it) }
            ?: n.attr("theme")?.toIntOrNull()?.let { c.theme.getOrNull(it) }
            ?: n.attr("indexed")?.toIntOrNull()?.let { if (it == 64 || it == 65) null else c.indexed.getOrNull(it) }
        val t = n.attr("tint")?.toFloatOrNull()
        if (rgb != null && t != null && t != 0f) rgb = OxUtil.tint(rgb, t)
        return rgb
    }

    private fun loadStyles(c: Ctx, path: String) {
        val x = c.pkg.xml(path) ?: return
        x.first("indexedColors")?.children("rgbColor")?.mapNotNull { OxUtil.parseHex(it.attr("rgb")) }?.let { if (it.isNotEmpty()) c.indexed = it.toIntArray() + DEFAULT_INDEXED.drop(it.size).toIntArray() }
        x.first("numFmts")?.children("numFmt")?.forEach { n -> n.attr("numFmtId")?.toIntOrNull()?.let { id -> c.numFmts[id] = n.attr("formatCode") ?: "General" } }
        x.first("fonts")?.children("font")?.forEach { f ->
            c.fonts.add(Font(f.child("name")?.v, f.child("sz")?.v?.toDoubleOrNull() ?: 11.0, onOff(f.child("b")), onOff(f.child("i")),
                f.child("u")?.let { it.v != "none" } ?: false, onOff(f.child("strike")), color(c, f.child("color"))))
        }
        x.first("fills")?.children("fill")?.forEach { f ->
            val pf = f.child("patternFill")
            val gf = f.child("gradientFill")
            c.fills.add(when {
                pf != null && pf.attr("patternType") != null && pf.attr("patternType") != "none" ->
                    if (pf.attr("patternType") == "solid") color(c, pf.child("fgColor")) ?: color(c, pf.child("bgColor"))
                    else color(c, pf.child("fgColor"))?.let { OxUtil.mix(color(c, pf.child("bgColor")) ?: 0xFFFFFF, it, 0.35f) }
                gf != null -> color(c, gf.first("stop")?.child("color"))
                else -> null
            })
        }
        x.first("borders")?.children("border")?.forEach { b ->
            fun side(n: XNode?): Pair<String, Int?>? { val st = n?.attr("style") ?: return null; if (st == "none") return null; return st to color(c, n.child("color")) }
            c.borders.add(Border(side(b.child("left") ?: b.child("start")), side(b.child("right") ?: b.child("end")), side(b.child("top")), side(b.child("bottom"))))
        }
        x.first("cellXfs")?.children("xf")?.forEach { f ->
            val a = f.child("alignment")
            c.xfs.add(Xf(f.attr("numFmtId")?.toIntOrNull() ?: 0, f.attr("fontId")?.toIntOrNull() ?: 0, f.attr("fillId")?.toIntOrNull() ?: 0,
                f.attr("borderId")?.toIntOrNull() ?: 0, a?.attr("horizontal"), a?.attr("vertical"), a?.attr("wrapText").let { it == "1" || it == "true" },
                a?.attr("indent")?.toIntOrNull() ?: 0, a?.attr("textRotation")?.toIntOrNull() ?: 0, a?.attr("shrinkToFit") == "1"))
        }
    }

    private fun onOff(n: XNode?): Boolean { if (n == null) return false; val v = n.v ?: return true; return v != "0" && v != "false" }

    private fun loadShared(c: Ctx, path: String) {
        val x = c.pkg.xml(path) ?: return
        val sst = x.child("sst") ?: x
        for (si in sst.children("si")) {
            val runs = si.children("r")
            if (runs.isEmpty()) { c.shared.add(cap(si.child("t")?.text ?: si.all("t").joinToString("") { it.text })); c.sharedRich.add(null) }
            else {
                val plain = StringBuilder(); val rich = StringBuilder()
                for (r in runs) {
                    val t = r.child("t")?.text ?: ""
                    plain.append(t)
                    val rp = r.child("rPr")
                    if (rp == null) rich.append(OxUtil.esc(t)) else {
                        rich.append("<span style=\"")
                        rp.child("rFont")?.v?.let { rich.append("font-family:").append(OxUtil.fontStack(it)).append(';') }
                        rp.child("sz")?.v?.let { rich.append("font-size:").append(it).append("pt;") }
                        if (onOff(rp.child("b"))) rich.append("font-weight:bold;")
                        if (onOff(rp.child("i"))) rich.append("font-style:italic;")
                        if (rp.child("u") != null) rich.append("text-decoration:underline;")
                        if (onOff(rp.child("strike"))) rich.append("text-decoration:line-through;")
                        color(c, rp.child("color"))?.let { rich.append("color:").append(OxUtil.hex(it)).append(';') }
                        when (rp.child("vertAlign")?.v) { "superscript" -> rich.append("vertical-align:super;font-size:70%;"); "subscript" -> rich.append("vertical-align:sub;font-size:70%;") }
                        rich.append("\">").append(OxUtil.esc(t)).append("</span>")
                    }
                }
                // teks kaya yang terlalu panjang: tampilkan polos terpotong (format per-run tidak sebanding biayanya)
                if (plain.length > CELL_CHARS) { c.shared.add(cap(plain.toString())); c.sharedRich.add(null) }
                else { c.shared.add(plain.toString()); c.sharedRich.add(rich.toString()) }
            }
        }
    }

    // ------------------------------------------------------------------ lembar
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1) else break }
        return n - 1
    }
    private fun rowIndex(ref: String): Int = ref.dropWhile { it.isLetter() || it == '$' }.toIntOrNull()?.minus(1) ?: 0
    fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }; return sb.toString() }

    private class Cell(val v: String, val rich: String?, val xf: Int, val type: String, val num: Double?, val img: String? = null)

    private fun sheet(c: Ctx, path: String, out: StringBuilder) {
        val x = c.pkg.xml(path) ?: return
        val ws = x.child("worksheet") ?: x
        val rels = c.pkg.rels(path)
        val fmtPr = ws.child("sheetFormatPr")
        val maxDigit = 7.0   // lebar digit Calibri 11 dalam px
        val defColW = fmtPr?.attr("defaultColWidth")?.toDoubleOrNull()
            ?: ((fmtPr?.attr("baseColWidth")?.toDoubleOrNull() ?: 8.0) + 0.71)
        val defRowH = fmtPr?.attr("defaultRowHeight")?.toDoubleOrNull() ?: 15.0
        val showGrid = ws.first("sheetView")?.attr("showGridLines").let { it != "0" && it != "false" }
        // kolom
        val colW = HashMap<Int, Double>(); val colHidden = HashSet<Int>(); val colStyle = HashMap<Int, Int>()
        ws.child("cols")?.children("col")?.forEach { col ->
            val mn = (col.attr("min")?.toIntOrNull() ?: 1) - 1; val mx = min((col.attr("max")?.toIntOrNull() ?: 1) - 1, MAX_COLS + 50)
            for (i in mn..mx) {
                col.attr("width")?.toDoubleOrNull()?.let { colW[i] = it }
                if (col.attr("hidden") == "1" || col.attr("hidden") == "true") colHidden.add(i)
                col.attr("style")?.toIntOrNull()?.let { colStyle[i] = it }
            }
        }
        // sel
        val cells = HashMap<Long, Cell>()
        val rowH = HashMap<Int, Double>(); val rowHidden = HashSet<Int>()
        var maxR = -1; var maxC = -1
        ws.child("sheetData")?.children("row")?.forEach { row ->
            val r = (row.attr("r")?.toIntOrNull() ?: (maxR + 2)) - 1
            if (r >= MAX_ROWS) return@forEach
            row.attr("ht")?.toDoubleOrNull()?.let { if (row.attr("customHeight") == "1" || true) rowH[r] = it }
            if (row.attr("hidden") == "1" || row.attr("hidden") == "true") rowHidden.add(r)
            var cIdx = -1
            for (cel in row.children("c")) {
                cIdx = cel.attr("r")?.let { colIndex(it) } ?: (cIdx + 1)
                if (cIdx > MAX_COLS) continue
                val t = cel.attr("t") ?: "n"
                val s = cel.attr("s")?.toIntOrNull() ?: 0
                val vNode = cel.child("v")
                var rich: String? = null
                var num: Double? = null
                val text: String = when (t) {
                    "s" -> { val i = vNode?.text?.trim()?.toIntOrNull() ?: -1; rich = c.sharedRich.getOrNull(i); c.shared.getOrNull(i) ?: "" }
                    "inlineStr" -> cel.child("is")?.all("t")?.joinToString("") { it.text } ?: ""
                    "b" -> if (vNode?.text?.trim() == "1") "TRUE" else "FALSE"
                    "e" -> vNode?.text ?: "#ERR"
                    "str" -> vNode?.text ?: ""
                    else -> { num = vNode?.text?.trim()?.toDoubleOrNull(); vNode?.text ?: "" }
                }
                // gambar di dalam sel
                val img = cel.attr("vm")?.toIntOrNull()?.let { c.vmRv[it] }?.let { c.rvImg[it] }
                    ?: (if (c.wpsImg.isNotEmpty()) (cel.child("f")?.text ?: text).let { f -> if (f.contains("DISPIMG")) DISPIMG.find(f)?.groupValues?.get(1)?.let { c.wpsImg[it] } else null } else null)
                if (text.isEmpty() && s == 0 && img == null) continue
                cells[(r.toLong() shl 20) or cIdx.toLong()] = Cell(if (img != null) "" else text, if (img != null) null else rich, s, t, num, img)
                if (img != null) { maxR = max(maxR, r); maxC = max(maxC, cIdx) }
                if (text.isNotEmpty() || (c.xfs.getOrNull(s)?.let { it.fill != 0 || it.border != 0 } == true)) { maxR = max(maxR, r); maxC = max(maxC, cIdx) }
            }
            if (row.children("c").isEmpty() && rowH.containsKey(r)) { /* baris kosong bertinggi khusus */ }
        }
        // gabungan sel
        val mergeTop = HashMap<Long, IntArray>(); val covered = HashSet<Long>()
        ws.child("mergeCells")?.children("mergeCell")?.forEach { m ->
            val ref = m.attr("ref") ?: return@forEach
            val p = ref.split(':'); if (p.size != 2) return@forEach
            val r0 = rowIndex(p[0]); val c0 = colIndex(p[0]); val r1 = rowIndex(p[1]); val c1 = colIndex(p[1])
            if (r0 >= MAX_ROWS || c0 > MAX_COLS) return@forEach
            mergeTop[(r0.toLong() shl 20) or c0.toLong()] = intArrayOf(min(r1, MAX_ROWS - 1) - r0 + 1, min(c1, MAX_COLS) - c0 + 1)
            for (rr in r0..min(r1, MAX_ROWS - 1)) for (cc in c0..min(c1, MAX_COLS)) if (rr != r0 || cc != c0) covered.add((rr.toLong() shl 20) or cc.toLong())
            maxR = max(maxR, min(r1, MAX_ROWS - 1)); maxC = max(maxC, min(c1, MAX_COLS))
        }
        // Tabel Excel (Format as Table): gaya bawaan diperkirakan (kepala berwarna, baris belang)
        val tableFx = HashMap<Long, String>()
        ws.child("tableParts")?.children("tablePart")?.forEach { tp ->
            val tRel = tp.attr("id")?.let { rels[it] } ?: return@forEach
            val tx = c.pkg.xml(tRel.target)?.let { it.child("table") ?: it } ?: return@forEach
            val ref = tx.attr("ref") ?: return@forEach
            val pr = ref.split(':'); if (pr.size != 2) return@forEach
            val r0 = rowIndex(pr[0]); val c0 = colIndex(pr[0]); val r1 = min(rowIndex(pr[1]), MAX_ROWS - 1); val c1 = min(colIndex(pr[1]), MAX_COLS)
            val hdrRows = tx.attr("headerRowCount")?.toIntOrNull() ?: 1
            val totRows = tx.attr("totalsRowCount")?.toIntOrNull() ?: 0
            val si = tx.child("tableStyleInfo")
            val name = si?.attr("name") ?: "TableStyleMedium2"
            val stripes = si?.attr("showRowStripes").let { it == null || it == "1" || it == "true" }
            val m = Regex("TableStyle(Light|Medium|Dark)(\\d+)").find(name) ?: return@forEach
            val kind = m.groupValues[1]; val num = m.groupValues[2].toInt()
            val k = (num - 1) % 7
            val accent = if (k == 0) (c.theme.getOrNull(1) ?: 0) else (c.theme.getOrNull(3 + k) ?: 0x4472C4)
            val group = (num - 1) / 7
            fun tintc(t: Float) = OxUtil.tint(accent, t)
            val hdrCss: String; val bandCss: String?; val bodyCss: String
            when (kind) {
                "Light" -> when (group) {
                    0 -> { hdrCss = "font-weight:bold;border-bottom:1px solid ${OxUtil.hex(accent)};"; bandCss = "background:${OxUtil.hex(tintc(0.8f))};"; bodyCss = "" }
                    1 -> { hdrCss = "font-weight:bold;color:#fff;background:${OxUtil.hex(accent)};"; bandCss = null; bodyCss = "border-bottom:1px solid ${OxUtil.hex(tintc(0.4f))};" }
                    else -> { hdrCss = "font-weight:bold;border:1px solid ${OxUtil.hex(accent)};"; bandCss = "background:${OxUtil.hex(tintc(0.8f))};"; bodyCss = "border:1px solid ${OxUtil.hex(accent)};" }
                }
                "Medium" -> when (group) {
                    0 -> { hdrCss = "font-weight:bold;color:#fff;background:${OxUtil.hex(accent)};"; bandCss = "background:${OxUtil.hex(tintc(0.8f))};"; bodyCss = "border-bottom:1px solid ${OxUtil.hex(tintc(0.4f))};" }
                    1 -> { hdrCss = "font-weight:bold;color:#fff;background:#000;"; bandCss = "background:${OxUtil.hex(tintc(0.6f))};"; bodyCss = "background:${OxUtil.hex(tintc(0.8f))};" }
                    2 -> { hdrCss = "font-weight:bold;color:#fff;background:${OxUtil.hex(accent)};border:1px solid #fff;"; bandCss = "background:${OxUtil.hex(tintc(0.6f))};"; bodyCss = "background:${OxUtil.hex(tintc(0.8f))};border:1px solid #fff;" }
                    else -> { hdrCss = "font-weight:bold;"; bandCss = "background:${OxUtil.hex(tintc(0.6f))};"; bodyCss = "background:${OxUtil.hex(tintc(0.8f))};border:1px solid ${OxUtil.hex(tintc(0.4f))};" }
                }
                else -> when (group) {
                    0 -> { hdrCss = "font-weight:bold;color:#fff;background:#000;"; bandCss = "background:${OxUtil.hex(OxUtil.tint(accent, -0.25f))};color:#fff;"; bodyCss = "background:${OxUtil.hex(accent)};color:#fff;" }
                    else -> { hdrCss = "font-weight:bold;color:#fff;background:#000;"; bandCss = "background:${OxUtil.hex(tintc(0.4f))};"; bodyCss = "background:${OxUtil.hex(tintc(0.6f))};" }
                }
            }
            for (rr in r0..r1) for (cc in c0..c1) {
                val key = (rr.toLong() shl 20) or cc.toLong()
                val css = when {
                    rr < r0 + hdrRows -> hdrCss
                    totRows > 0 && rr > r1 - totRows -> "font-weight:bold;border-top:2px double ${OxUtil.hex(accent)};"
                    stripes && bandCss != null && (rr - r0 - hdrRows) % 2 == 0 -> bodyCss + bandCss
                    else -> bodyCss
                }
                tableFx[key] = css
            }
            maxR = max(maxR, r1); maxC = max(maxC, c1)
        }
        // gambar (drawing) memperluas area
        val drawingRel = ws.child("drawing")?.attr("id")?.let { rels[it] }
        val pics = ArrayList<DoubleArray>(); val picSrc = ArrayList<String>()
        val anchors = drawingRel?.let { d -> c.pkg.xml(d.target)?.let { dx -> anchorsOf(c, dx, d.target) } } ?: emptyList()
        for (a in anchors) { maxR = max(maxR, a.r1); maxC = max(maxC, a.c1) }
        if (maxR < 0 && anchors.isEmpty()) { out.append("<div class=\"empty\">Lembar kosong.</div>"); return }
        val nRows = min(max(maxR + 1, 1) + 3, MAX_ROWS); val nCols = min(max(maxC + 1, 1) + 1, MAX_COLS + 1)
        fun cw(i: Int): Double = if (i in colHidden) 0.0 else ((colW[i] ?: defColW) * maxDigit + 5).let { floor(it) }
        fun rh(r: Int): Double = if (r in rowHidden) 0.0 else (rowH[r] ?: defRowH) * 4 / 3

        var tableW = 40.0; for (i in 0 until nCols) tableW += cw(i)
        out.append("<div class=\"sheet\"><table class=\"grid").append(if (showGrid) " gl" else "").append("\" style=\"width:${tableW}px\"><colgroup><col style=\"width:40px\">")
        for (i in 0 until nCols) out.append("<col style=\"width:${cw(i)}px\">")
        out.append("</colgroup><thead><tr><th class=\"corner\"></th>")
        for (i in 0 until nCols) out.append("<th class=\"ch\"").append(if (i in colHidden) " style=\"display:none\"" else "").append(">").append(colName(i)).append("</th>")
        out.append("</tr></thead><tbody>")
        for (r in 0 until nRows) {
            if (r in rowHidden) continue
            out.append("<tr style=\"height:${rh(r)}px\"><th class=\"rh\">").append(r + 1).append("</th>")
            for (ci in 0 until nCols) {
                val key = (r.toLong() shl 20) or ci.toLong()
                if (key in covered) continue
                if (ci in colHidden) { out.append("<td style=\"display:none\"></td>"); continue }
                val cell = cells[key]
                val m = mergeTop[key]
                val xfIdx = cell?.xf ?: colStyle[ci] ?: 0
                val xf = c.xfs.getOrNull(xfIdx)
                out.append("<td")
                if (m != null) { if (m[0] > 1) out.append(" rowspan=\"${m[0]}\""); if (m[1] > 1) out.append(" colspan=\"${m[1]}\"") }
                val css = StringBuilder()
                tableFx[key]?.let { css.append(it) }
                var align: String? = null
                if (xf != null) {
                    val f = c.fonts.getOrNull(xf.font)
                    if (f != null && xf.font != 0) {
                        if (f.name != c.fonts.firstOrNull()?.name) css.append("font-family:").append(OxUtil.fontStack(f.name)).append(';')
                        if (f.size != c.fonts.firstOrNull()?.size) css.append("font-size:").append(f.size).append("pt;")
                    }
                    if (f != null) {
                        if (f.b) css.append("font-weight:bold;"); if (f.i) css.append("font-style:italic;")
                        if (f.u || f.strike) css.append("text-decoration:").append(if (f.u) "underline " else "").append(if (f.strike) "line-through" else "").append(';')
                        f.color?.let { css.append("color:").append(OxUtil.hex(it)).append(';') }
                    }
                    c.fills.getOrNull(xf.fill)?.let { css.append("background:").append(OxUtil.hex(it)).append(';') }
                    c.borders.getOrNull(xf.border)?.let { b ->
                        fun bs(side: String, p: Pair<String, Int?>?) { if (p != null) css.append("border-").append(side).append(':').append(bcss(p)).append(';') }
                        bs("left", b.l); bs("right", b.r); bs("top", b.t); bs("bottom", b.b)
                    }
                    align = xf.h
                    when (xf.v) { "top" -> css.append("vertical-align:top;"); "center" -> css.append("vertical-align:middle;"); else -> {} }
                    if (xf.wrap) css.append("white-space:pre-wrap;")
                    if (xf.indent > 0) css.append("padding-left:${xf.indent * 9 + 2}px;")
                }
                // nilai terformat
                var shown = ""; var colorOverride: Int? = null; var isNum = false
                if (cell != null) {
                    if (cell.num != null && (cell.type == "n")) {
                        val fmt = fmtCode(c, xf?.numFmt ?: 0)
                        val (s, col) = NumFmt.format(cell.num, fmt, c.date1904)
                        shown = s; colorOverride = col; isNum = true
                    } else shown = cap(cell.v)
                }
                colorOverride?.let { css.append("color:").append(OxUtil.hex(it)).append(';') }
                val hAlign = when (align) {
                    "center", "centerContinuous" -> "center"; "right" -> "right"; "left" -> "left"; "justify", "distributed" -> "justify"
                    "fill" -> "left"
                    else -> if (isNum || cell?.type == "b" || cell?.type == "e") "right" else "left"
                }
                if (hAlign != "left") css.append("text-align:").append(hAlign).append(';')
                if (xf?.rot != null && xf.rot != 0 && shown.isNotEmpty()) { /* rotasi teks: abaikan, tampil mendatar */ }
                // teks meluber hanya bila sel tetangga (arah luberan) kosong — seperti Excel
                val nextKey = if (hAlign == "right") (r.toLong() shl 20) or (ci - 1).toLong() else (r.toLong() shl 20) or (ci + 1).toLong()
                val overflow = shown.isNotEmpty() && xf?.wrap != true && m == null && !isNum && cells[nextKey]?.v.isNullOrEmpty() && nextKey !in covered
                val cs = if (css.isNotEmpty()) css.toString() else null
                val k = cs?.let { c.cls(it) }
                if (overflow || k != null) {
                    out.append(" class=\"")
                    if (overflow) out.append('o')
                    if (k != null) { if (overflow) out.append(' '); out.append(k) }
                    out.append('"')
                }
                if (cs != null && k == null) out.append(" style=\"").append(cs).append('"')
                out.append(">")
                cell?.img?.let { part ->
                    var iw = cw(ci); var ih = rh(r)
                    if (m != null) { iw = 0.0; for (k in 0 until m[1]) iw += cw(ci + k); ih = 0.0; for (k in 0 until m[0]) ih += rh(r + k) }
                    c.res.url(part, iw, ih)?.let { src -> out.append("<img class=\"ci\" src=\"").append(src).append("\" style=\"width:${max(1.0, iw - 3)}px;height:${max(1.0, ih - 3)}px\">") }
                }
                if (shown.isNotEmpty()) {
                    val content = cell?.rich?.takeIf { cell.type == "s" } ?: OxUtil.esc(shown)
                    if (overflow) out.append("<div class=\"ov ").append(hAlign).append("\">").append(content).append("</div>")
                    else if (isNum) out.append(OxUtil.esc(shown)) else out.append(content)
                }
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</tbody></table>")
        // gambar & grafik di atas grid
        if (anchors.isNotEmpty()) {
            val colX = DoubleArray(nCols + 2); for (i in 1..nCols + 1) colX[i] = colX[i - 1] + if (i - 1 < nCols) cw(i - 1) else defColW * maxDigit
            val rowY = DoubleArray(nRows + 2); for (i in 1..nRows + 1) rowY[i] = rowY[i - 1] + if (i - 1 < nRows) rh(i - 1) else defRowH * 4 / 3
            for (a in anchors) {
                val x0 = 40 + colX[a.c0.coerceIn(0, nCols)] + a.dx0; val y0 = 20 + rowY[a.r0.coerceIn(0, nRows)] + a.dy0
                val w = if (a.wPx > 0) a.wPx else (40 + colX[a.c1.coerceIn(0, nCols)] + a.dx1) - x0
                val h = if (a.hPx > 0) a.hPx else (20 + rowY[a.r1.coerceIn(0, nRows)] + a.dy1) - y0
                if (w <= 1 || h <= 1) continue
                out.append("<div class=\"pic\" style=\"left:${x0}px;top:${y0}px;width:${w}px;height:${h}px\">")
                val src = a.part?.let { c.res.url(it, w, h) }
                if (src != null) out.append("<img src=\"").append(src).append("\" style=\"width:100%;height:100%\">")
                else out.append("<div class=\"ph\">").append(OxUtil.esc(a.label.ifEmpty { "Gambar" })).append("</div>")
                out.append("</div>")
            }
        }
        out.append("</div>")
        if (maxR + 1 >= MAX_ROWS) out.append("<div class=\"note\">Menampilkan $MAX_ROWS baris pertama.</div>")
    }

    private class Anchor(val c0: Int, val r0: Int, val dx0: Double, val dy0: Double, val c1: Int, val r1: Int, val dx1: Double, val dy1: Double,
                         val wPx: Double, val hPx: Double, val part: String?, val label: String)

    private fun anchorsOf(c: Ctx, dx: XNode, path: String): List<Anchor> {
        val rels = c.pkg.rels(path)
        val out = ArrayList<Anchor>()
        val root = dx.kids.firstOrNull() ?: return out
        for (a in root.kids) {
            if (a.name != "twoCellAnchor" && a.name != "oneCellAnchor" && a.name != "absoluteAnchor") continue
            fun pos(n: XNode?): IntArray? = n?.let { intArrayOf(it.child("col")?.text?.trim()?.toIntOrNull() ?: 0, it.child("row")?.text?.trim()?.toIntOrNull() ?: 0) }
            fun off(n: XNode?, k: String): Double = (n?.child(k)?.text?.trim()?.toLongOrNull() ?: 0L) / 9525.0
            val f = a.child("from"); val t = a.child("to")
            val fp = pos(f) ?: intArrayOf(0, 0); val tp = pos(t)
            val ext = a.child("ext")
            val wPx = ext?.attr("cx")?.toLongOrNull()?.let { it / 9525.0 } ?: 0.0; val hPx = ext?.attr("cy")?.toLongOrNull()?.let { it / 9525.0 } ?: 0.0
            var src: String? = null; var label = ""
            val blip = a.first("blip")
            if (blip != null) {
                val rel = (blip.attr("embed") ?: blip.attr("link"))?.let { rels[it] }
                if (rel != null && !rel.external && c.pkg.has(rel.target)) src = rel.target
                if (src == null) label = "Gambar"
            } else if (a.first("chart") != null) label = "Grafik"
            else if (a.first("sp") != null) {
                label = a.first("txBody")?.all("t")?.joinToString(" ") { it.text } ?: ""
                if (label.isBlank()) continue
            } else continue
            out.add(Anchor(fp[0], fp[1], off(f, "colOff"), off(f, "rowOff"), tp?.get(0) ?: fp[0], tp?.get(1) ?: fp[1], off(t, "colOff"), off(t, "rowOff"),
                if (t == null) wPx else 0.0, if (t == null) hPx else 0.0, src, label))
        }
        return out
    }

    private fun bcss(p: Pair<String, Int?>): String {
        val col = OxUtil.hex(p.second ?: 0)
        return when (p.first) {
            "thin" -> "1px solid $col"; "hair" -> "1px dotted $col"; "dotted" -> "1px dotted $col"; "dashed", "dashDot", "dashDotDot" -> "1px dashed $col"
            "medium" -> "2px solid $col"; "mediumDashed", "mediumDashDot", "mediumDashDotDot", "slantDashDot" -> "2px dashed $col"
            "thick" -> "3px solid $col"; "double" -> "3px double $col"; else -> "1px solid $col"
        }
    }

    private fun fmtCode(c: Ctx, id: Int): String = c.numFmts[id] ?: BUILTIN[id] ?: "General"

    private val BUILTIN = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "dd/mm/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss",
        22 to "dd/mm/yyyy h:mm", 37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)",
        44 to "_(\"\$\"* #,##0.00_);_(\"\$\"* \\(#,##0.00\\);_(\"\$\"* \"-\"??_);_(@_)", 45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mmss.0", 48 to "##0.0E+0", 49 to "@"
    )

    private val DEFAULT_INDEXED = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00,
        0xFF00FF, 0x00FFFF, 0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080, 0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF,
        0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF, 0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF, 0x00CCFF, 0xCCFFFF,
        0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99, 0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333
    )

    private const val CSS = """
html,body{margin:0;padding:0;background:#fff;color:#000}
#main{padding-bottom:48px}
.sheet{position:relative;display:inline-block;min-width:100%}
table.grid{border-collapse:collapse;table-layout:fixed}
table.grid td{padding:0 3px;white-space:nowrap;overflow:hidden;vertical-align:bottom;line-height:1.15;box-sizing:border-box}
table.grid.gl td{border:1px solid #e1e3e6}
table.grid td.o{overflow:visible}
.ov{white-space:nowrap;position:relative;z-index:1}
.ov.right{direction:rtl;text-align:right}.ov.center{text-align:center}
th{background:#f3f4f6;color:#5f6368;font:11px sans-serif;border:1px solid #d5d8dc;padding:0 2px;position:sticky;z-index:2}
th.ch{top:0;height:20px;text-align:center}
th.rh{left:0;text-align:center;width:40px}
th.corner{top:0;left:0;z-index:3}
thead th{position:sticky;top:0}
.pic{position:absolute;z-index:4}
img.ci{display:block;object-fit:contain;max-width:none;margin:1px auto}
.pic .ph{width:100%;height:100%;box-sizing:border-box;border:1px dashed #9aa0a6;background:rgba(241,243,246,.85);display:flex;align-items:center;justify-content:center;font:12px sans-serif;color:#5f6368}
#tabs{position:fixed;left:0;right:0;bottom:0;background:#f3f4f6;border-top:1px solid #d5d8dc;white-space:nowrap;overflow-x:auto;padding:4px;z-index:10}
.tab{font:13px sans-serif;border:1px solid #d5d8dc;background:#fff;color:#3c4043;padding:6px 12px;margin-right:4px;border-radius:4px}
.tab.on{background:#e6f4ea;border-color:#188038;color:#188038;font-weight:bold}
.empty,.note{font:13px sans-serif;color:#5f6368;padding:16px}
@media print{#tabs{display:none}th{position:static}#main{padding:0}}
"""

    private const val JS = """
function show(i){var w=document.querySelectorAll('.sheet-wrap');for(var k=0;k<w.length;k++)w[k].style.display=k==i?'block':'none';
var t=document.querySelectorAll('.tab');for(var k2=0;k2<t.length;k2++)t[k2].className='tab'+(k2==i?' on':'');window.scrollTo(0,0);}
document.body.setAttribute('data-ready','1');
"""
}

/** Format angka Excel (bawaan & kustom) — dipisah supaya bisa diuji sendiri. */
object NumFmt {
    private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    // Pola dikompilasi SEKALI (dulu dikompilasi ulang untuk setiap sel — lembar ribuan baris jadi lambat dibuka).
    private val COLOR = Regex("\\[(Black|White|Red|Green|Blue|Yellow|Magenta|Cyan|Color\\s*\\d+)]", RegexOption.IGNORE_CASE)
    private val CONDITION = Regex("\\[[<>=][^]]*]")
    private val CURRENCY = Regex("\\[\\$([^-\\]]*)(-[^]]*)?]")
    private val DIGIT_SLOTS = Regex("[0#?]")
    private val EXP_ZEROS = Regex("0+E")
    private val FRACTION_SECONDS = Regex("s+\\.0")
    private val AM_PM = Regex("AM/PM|A/P", RegexOption.IGNORE_CASE)

    /** Satu bagian kode format yang sudah dibersihkan (warna, kondisi, mata uang) — sama untuk semua sel berformat ini. */
    private class Section(val sec: String, val color: Int?, val date: Boolean)
    private val sections = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val prepared = java.util.concurrent.ConcurrentHashMap<String, Section>()

    private fun sectionsOf(code: String): List<String> = sections[code] ?: splitSections(code).also {
        if (sections.size > 256) sections.clear()
        sections[code] = it
    }

    private fun prepare(sec0: String): Section = prepared[sec0] ?: run {
        var color: Int? = null
        COLOR.find(sec0)?.let { m ->
            color = when (m.groupValues[1].lowercase()) { "red" -> 0xFF0000; "blue" -> 0x0000FF; "green" -> 0x00B050; "yellow" -> 0xFFFF00; "magenta" -> 0xFF00FF; "cyan" -> 0x00FFFF; "white" -> 0xFFFFFF; else -> 0 }
        }
        var sec = COLOR.replace(sec0, "")
        sec = CONDITION.replace(sec, "")
        // mata uang [$Rp-421] → Rp
        sec = CURRENCY.replace(sec) { it.groupValues[1] }
        val p = Section(sec, color, isDate(sec))
        if (prepared.size > 512) prepared.clear()
        prepared[sec0] = p
        p
    }

    /** Mengembalikan teks + warna (dari [Red] dll) bila ada. */
    fun format(v: Double, code0: String, date1904: Boolean): Pair<String, Int?> {
        val code = code0.trim()
        if (code.equals("General", true) || code.isEmpty()) return general(v) to null
        if (code == "@") return general(v) to null
        val sections = sectionsOf(code)
        var sec: String; var value = v
        when {
            sections.size >= 2 && v < 0 -> { sec = sections[1]; value = -v }
            sections.size >= 3 && v == 0.0 -> sec = sections[2]
            else -> sec = sections[0]
        }
        // kondisi [>=100] diabaikan; warna & mata uang dibersihkan sekali per bagian format
        val p = prepare(sec)
        val color = p.color
        sec = p.sec
        val neg = v < 0 && sections.size < 2
        if (p.date) return formatDate(v, sec, date1904) to color
        val s = formatNumber(if (neg) -value else value, sec)
        return (if (neg && s.any { it.isDigit() && it != '0' }) "-$s" else if (neg) "-$s" else s) to color
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var esc = false
        for (ch in code) {
            if (esc) { sb.append(ch); esc = false; continue }
            when {
                ch == '\\' -> { sb.append(ch); esc = true }
                ch == '"' -> { q = !q; sb.append(ch) }
                ch == ';' && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }

    fun general(v: Double): String {
        if (v == 0.0) return "0"
        if (v == floor(v) && abs(v) < 1e11) return v.toLong().toString()
        val a = abs(v)
        if (a >= 1e11 || a < 1e-9) return String.format(java.util.Locale.US, "%.5E", v).replace(EXP_ZEROS, "E").replace(".E", "E")
        // maksimal 11 digit signifikan
        val digitsBefore = if (a >= 1) floor(kotlin.math.log10(a)).toInt() + 1 else 0
        val dec = (10 - digitsBefore).coerceIn(0, 10)
        var s = String.format(java.util.Locale.US, "%.${dec}f", v)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    private fun isDate(sec: String): Boolean {
        var q = false
        val stripped = StringBuilder()
        var i = 0
        while (i < sec.length) {
            val ch = sec[i]
            if (ch == '"') { q = !q; i++; continue }
            if (!q && ch == '\\') { i += 2; continue }
            if (!q && ch == '[' ) { val e = sec.indexOf(']', i); if (e > i) { val inner = sec.substring(i + 1, e).lowercase(); if (inner == "h" || inner == "hh" || inner == "m" || inner == "mm" || inner == "s" || inner == "ss") stripped.append('h'); i = e + 1; continue } }
            if (!q) stripped.append(ch)
            i++
        }
        val s = stripped.toString().lowercase()
        return s.any { it == 'y' || it == 'd' || it == 'h' || it == 's' } || (s.contains('m') && !s.contains('0') && !s.contains('#'))
    }

    private fun formatNumber(v: Double, sec0: String): String {
        var sec = sec0
        // literal: "teks", \x, _x (spasi selebar x), *x (isi ulang → abaikan)
        val lit = StringBuilder()
        // pisahkan bagian angka dari literal sekitarnya
        val tokens = ArrayList<Pair<Boolean, String>>()   // (isPattern, text)
        val cur = StringBuilder(); var i = 0
        fun flushLit(t: String) { if (t.isNotEmpty()) tokens.add(false to t) }
        val pat = StringBuilder()
        var inPat = false
        while (i < sec.length) {
            val ch = sec[i]
            when {
                ch == '"' -> { val e = sec.indexOf('"', i + 1).let { if (it < 0) sec.length else it }; if (inPat) { tokens.add(true to pat.toString()); pat.setLength(0); inPat = false }; flushLit(sec.substring(i + 1, e)); i = e + 1; continue }
                ch == '\\' && i + 1 < sec.length -> { if (inPat) { tokens.add(true to pat.toString()); pat.setLength(0); inPat = false }; flushLit(sec[i + 1].toString()); i += 2; continue }
                ch == '_' && i + 1 < sec.length -> { if (inPat) { tokens.add(true to pat.toString()); pat.setLength(0); inPat = false }; flushLit(" "); i += 2; continue }
                ch == '*' && i + 1 < sec.length -> { i += 2; continue }
                ch in "0#?.,%E+-" && (ch != 'E' || (i + 1 < sec.length && (sec[i + 1] == '+' || sec[i + 1] == '-'))) && (ch != '-' && ch != '+' || inPat && pat.endsWith("E")) -> { inPat = true; pat.append(ch) }
                ch == ',' -> { pat.append(ch) }
                else -> { if (inPat) { tokens.add(true to pat.toString()); pat.setLength(0); inPat = false }; flushLit(ch.toString()) }
            }
            i++
        }
        if (inPat) tokens.add(true to pat.toString())
        val pIdx = tokens.indexOfFirst { it.first && it.second.any { c -> c == '0' || c == '#' || c == '?' } }
        if (pIdx < 0) return tokens.joinToString("") { it.second }
        var p = tokens[pIdx].second
        var value = v
        // persen
        val pctCount = tokens.sumOf { t -> t.second.count { it == '%' } }
        repeat(pctCount) { value *= 100 }
        p = p.replace("%", "")
        // skala ribuan: koma di akhir
        while (p.endsWith(",")) { value /= 1000; p = p.dropLast(1) }
        val num = if (p.contains("E+") || p.contains("E-")) sci(value, p) else plain(value, p)
        val sb = StringBuilder()
        for ((k, t) in tokens.withIndex()) sb.append(if (k == pIdx) num else if (t.first) t.second.replace(DIGIT_SLOTS, "") else t.second)
        if (pctCount > 0 && !sb.contains('%')) sb.append('%')
        return sb.toString()
    }

    private fun plain(v: Double, p: String): String {
        val dot = p.indexOf('.')
        val intP = if (dot >= 0) p.substring(0, dot) else p
        val decP = if (dot >= 0) p.substring(dot + 1).filter { it == '0' || it == '#' || it == '?' } else ""
        val grouping = intP.contains(',')
        val minInt = intP.count { it == '0' }
        val decMin = decP.count { it == '0' }; val decMax = decP.length
        val rounded = java.math.BigDecimal(v).setScale(decMax, java.math.RoundingMode.HALF_UP)
        var s = rounded.abs().toPlainString()
        var ip = s.substringBefore('.'); var dp = if (s.contains('.')) s.substringAfter('.') else ""
        // desimal: buang nol di posisi '#'
        while (dp.length > decMin && dp.endsWith('0')) dp = dp.dropLast(1)
        if (ip == "0" && minInt == 0) ip = ""
        while (ip.length < minInt) ip = "0$ip"
        if (grouping && ip.length > 3) {
            val g = StringBuilder(); var cnt = 0
            for (k in ip.length - 1 downTo 0) { g.insert(0, ip[k]); cnt++; if (cnt % 3 == 0 && k > 0) g.insert(0, ',') }
            ip = g.toString()
        }
        return if (dp.isNotEmpty() || (decMax > 0 && decMin > 0)) "$ip.$dp" else if (dot >= 0 && decMax == 0) "$ip." .trimEnd('.') else ip
    }

    private fun sci(v: Double, p: String): String {
        val mant = p.substringBefore('E'); val expP = p.substringAfter('E').drop(1)
        val decs = if (mant.contains('.')) mant.substringAfter('.').length else 0
        if (v == 0.0) return plain(0.0, mant) + "E+" + "0".repeat(max(1, expP.length))
        var e = floor(kotlin.math.log10(abs(v))).toInt()
        val intDigits = mant.substringBefore('.').count { it == '0' || it == '#' }.coerceAtLeast(1)
        if (intDigits > 1) e = floor(e.toDouble() / intDigits).toInt() * intDigits
        val m = v / Math.pow(10.0, e.toDouble())
        val ms = String.format(java.util.Locale.US, "%.${decs}f", m)
        val es = abs(e).toString().padStart(max(1, expP.length), '0')
        return ms + "E" + (if (e < 0) "-" else "+") + es
    }

    private fun formatDate(v: Double, sec: String, date1904: Boolean): String {
        // serial Excel → tanggal (1900: bug tahun kabisat 1900 diikuti)
        var days = floor(v).toLong()
        val frac = v - floor(v)
        val epoch: java.time.LocalDate
        if (date1904) epoch = java.time.LocalDate.of(1904, 1, 1)
        else { epoch = java.time.LocalDate.of(1899, 12, 31); if (days >= 60) days -= 1 }
        val date = epoch.plusDays(if (date1904) days else days)
        val hasFrac = FRACTION_SECONDS.containsMatchIn(sec.lowercase())
        val totalMs = Math.round(frac * 86400000.0)
        var secs = if (hasFrac) totalMs / 1000 else (frac * 86400 + 0.5).toLong()
        val totalH = floor(v * 24).toLong()
        val hh = (secs / 3600).toInt(); val mi = ((secs % 3600) / 60).toInt(); val ss = (secs % 60).toInt()
        val ampm = AM_PM.containsMatchIn(sec)
        val out = StringBuilder()
        var i = 0; var lastWasHour = false
        val s = sec
        fun peekSec(from: Int): Boolean { var j = from; while (j < s.length && !s[j].isLetter()) j++; return j < s.length && (s[j] == 's' || s[j] == 'S') }
        while (i < s.length) {
            val ch = s[i]
            if (ch == '"') { val e = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }; out.append(s, i + 1, e); i = e + 1; continue }
            if (ch == '\\' && i + 1 < s.length) { out.append(s[i + 1]); i += 2; continue }
            if (ch == '_' && i + 1 < s.length) { out.append(' '); i += 2; continue }
            if (ch == '*' && i + 1 < s.length) { i += 2; continue }
            if (ch == '[') { val e = s.indexOf(']', i); val inner = if (e > i) s.substring(i + 1, e).lowercase() else ""
                if (inner.startsWith("h")) out.append(totalH.toString().padStart(inner.length, '0'))
                else if (inner.startsWith("m")) out.append((floor(v * 1440).toLong()).toString().padStart(inner.length, '0'))
                else if (inner.startsWith("s")) out.append((floor(v * 86400).toLong()).toString().padStart(inner.length, '0'))
                i = if (e > i) e + 1 else i + 1; continue }
            val lc = ch.lowercaseChar()
            var n = 1; while (i + n < s.length && s[i + n].lowercaseChar() == lc) n++
            when (lc) {
                'y' -> { out.append(if (n <= 2) (date.year % 100).toString().padStart(2, '0') else date.year.toString()); lastWasHour = false }
                'd' -> { out.append(when (n) { 1 -> date.dayOfMonth.toString(); 2 -> date.dayOfMonth.toString().padStart(2, '0'); 3 -> DAYS[date.dayOfWeek.value % 7].take(3); else -> DAYS[date.dayOfWeek.value % 7] }); lastWasHour = false }
                'm' -> {
                    if (lastWasHour || peekSec(i + n)) out.append(if (n >= 2) mi.toString().padStart(2, '0') else mi.toString())
                    else out.append(when (n) { 1 -> date.monthValue.toString(); 2 -> date.monthValue.toString().padStart(2, '0'); 3 -> MONTHS[date.monthValue - 1].take(3); 5 -> MONTHS[date.monthValue - 1].take(1); else -> MONTHS[date.monthValue - 1] })
                    lastWasHour = false
                }
                'h' -> { val h12 = if (ampm) ((hh + 11) % 12 + 1) else hh; out.append(if (n >= 2) h12.toString().padStart(2, '0') else h12.toString()); lastWasHour = true }
                's' -> { out.append(if (n >= 2) ss.toString().padStart(2, '0') else ss.toString()); lastWasHour = false }
                'a' -> {
                    if (s.regionMatches(i, "AM/PM", 0, 5, true)) { out.append(if (hh < 12) "AM" else "PM"); i += 5; continue }
                    if (s.regionMatches(i, "A/P", 0, 3, true)) { out.append(if (hh < 12) "A" else "P"); i += 3; continue }
                    out.append(ch)
                }
                'e' -> out.append(date.year)
                '0' -> { // pecahan detik (ss.000)
                    val ms = totalMs % 1000
                    out.append(ms.toString().padStart(3, '0').take(n)); i += n; continue }
                else -> { out.append(s, i, i + n) }
            }
            i += n
        }
        return out.toString()
    }
}
