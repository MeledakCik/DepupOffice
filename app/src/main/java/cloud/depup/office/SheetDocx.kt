package cloud.depup.office

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Lembar kerja → dokumen Word (.docx) dengan tabel Word sungguhan, memakai tata letak yang sama dengan "Mode
 * Dokumen" & PDF ([SheetLayout]): kertas A4 tegak/mendatar per lembar, kolom yang terlalu lebar dibagi per bagian,
 * baris judul kolom diulang di tiap halaman, warna isi & teks, tebal/miring/garis bawah/coret, perataan, sel
 * gabungan (gridSpan/vMerge). Ditulis mengalir ke zip — ukuran hanya dibatasi ruang simpan.
 */
object SheetDocx {
    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val MARGIN_TW = 720   // 36 pt, sama dengan PDF

    fun write(out: File, title: String, grids: List<SheetLayout.Grid>, gridlines: Boolean) {
        val rd = SheetPrint.Renderer(title, grids, gridlines)
        val plan = rd.plan
        ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            put("[Content_Types].xml", XML +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                "</Types>")
            put("_rels/.rels", XML +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>")
            put("word/_rels/document.xml.rels", XML +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                "</Relationships>")
            put("word/styles.xml", XML +
                "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>" +
                "<w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/><w:lang w:val=\"id-ID\"/></w:rPr></w:rPrDefault>" +
                "<w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
                "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
                "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:spacing w:after=\"60\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/></w:rPr></w:style>" +
                "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"22\"/></w:rPr></w:style>" +
                "<w:style w:type=\"paragraph\" w:styleId=\"Sub\"><w:name w:val=\"Subtle\"/><w:basedOn w:val=\"Normal\"/>" +
                "<w:pPr><w:spacing w:after=\"160\"/></w:pPr><w:rPr><w:color w:val=\"6B6B6B\"/><w:sz w:val=\"17\"/></w:rPr></w:style>" +
                "</w:styles>")

            zip.putNextEntry(ZipEntry("word/document.xml"))
            val w = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8), 64 * 1024)
            w.append(XML).append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" ")
                .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>")
            para(w, "Title", title)
            val date = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("id", "ID")).format(java.util.Date())
            para(w, "Sub", date)
            grids.forEachIndexed { gi, g ->
                val pages = plan.pages.filter { it.grid == gi }
                val land = pages.firstOrNull()?.landscape == true
                val pageWtw = ((if (land) SheetLayout.A4_H else SheetLayout.A4_W) * 20).toInt()
                val textW = pageWtw - 2 * MARGIN_TW
                if (grids.size > 1) para(w, "Heading1", g.name)
                val bands = pages.map { it.band }.distinct()
                if (pages.all { it.rows.isEmpty() }) para(w, "Sub", "Lembar ini kosong.")
                else for (b in bands) {
                    val bp = pages.filter { it.band == b }
                    val cols = bp.first().cols
                    val rows = LinkedHashSet<Int>().apply { bp.forEach { addAll(it.rows) } }.toList()
                    if (bands.size > 1) {
                        val a = SheetLayout.colName(cols.first()); val z = SheetLayout.colName(cols.last())
                        para(w, "Sub", "Kolom ${if (a == z) a else "$a–$z"} · bagian ${b + 1} dari ${bands.size}")
                    }
                    table(w, g, plan, gi, cols, rows, textW, gridlines)
                }
                // satu bagian (section) per lembar: orientasi kertas mengikuti lembarnya
                val sect = sectPr(land)
                if (gi < grids.size - 1) w.append("<w:p><w:pPr>").append(sect).append("</w:pPr></w:p>")
                else w.append(sect)
            }
            if (grids.isEmpty()) w.append(sectPr(false))
            w.append("</w:body></w:document>")
            w.flush()
            zip.closeEntry()
        }
    }

    private fun sectPr(land: Boolean): String {
        val (pw, ph) = if (land) 16838 to 11906 else 11906 to 16838
        return "<w:sectPr><w:pgSz w:w=\"$pw\" w:h=\"$ph\"" + (if (land) " w:orient=\"landscape\"" else "") + "/>" +
            "<w:pgMar w:top=\"$MARGIN_TW\" w:right=\"$MARGIN_TW\" w:bottom=\"$MARGIN_TW\" w:left=\"$MARGIN_TW\" w:header=\"360\" w:footer=\"360\" w:gutter=\"0\"/></w:sectPr>"
    }

    private fun para(w: Appendable, style: String, text: String) {
        w.append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr><w:r>")
        runText(w, text)
        w.append("</w:r></w:p>")
    }

    private fun runText(w: Appendable, text: String) {
        text.replace("\r", "").split('\n').forEachIndexed { i, line ->
            if (i > 0) w.append("<w:br/>")
            w.append("<w:t xml:space=\"preserve\">").append(esc(line)).append("</w:t>")
        }
    }

    private fun hex(c: Int) = String.format("%06X", c and 0xFFFFFF)

    private fun table(w: Appendable, g: SheetLayout.Grid, plan: SheetLayout.Plan, gi: Int, cols: List<Int>, rows: List<Int>, textW: Int, gridlines: Boolean) {
        val colW = plan.colW[gi]
        val raw = cols.map { (colW[it] * 20).toInt().coerceAtLeast(200) }
        val sum = raw.sum().coerceAtLeast(1)
        val tw = if (sum > textW) raw.map { (it.toLong() * textW / sum).toInt().coerceAtLeast(150) } else raw
        val header = plan.header[gi]
        val bd = if (gridlines) "<w:%s w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BDBDBD\"/>" else "<w:%s w:val=\"nil\"/>"
        w.append("<w:tbl><w:tblPr><w:tblW w:w=\"").append(tw.sum().toString()).append("\" w:type=\"dxa\"/><w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) w.append(bd.format(side))
        w.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"80\" w:type=\"dxa\"/>")
            .append("<w:bottom w:w=\"40\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        for (x in tw) w.append("<w:gridCol w:w=\"").append(x.toString()).append("\"/>")
        w.append("</w:tblGrid>")
        val colPos = HashMap<Int, Int>().apply { cols.forEachIndexed { i, c -> put(c, i) } }
        val rowSet = rows.toHashSet()
        for (r in rows) {
            w.append("<w:tr><w:trPr><w:cantSplit/>")
            if (r == header) w.append("<w:tblHeader/>")
            w.append("</w:trPr>")
            var i = 0
            while (i < cols.size) {
                val c = cols[i]
                val mg = g.mergeAt(r, c)
                var span = 1
                var vm: String? = null
                var anchorR = r; var anchorC = c
                if (mg != null) {
                    // kolom gabungan yang ada di bagian ini (berurutan mulai dari kolom ini)
                    if (mg.left != c && colPos.containsKey(mg.left)) { i++; continue }   // sudah dicakup gridSpan
                    var k = i + 1
                    while (k < cols.size && cols[k] <= mg.right) k++
                    span = k - i
                    val firstRowInTable = (mg.top..mg.bottom).firstOrNull { it in rowSet }
                    vm = if (mg.bottom > mg.top) (if (r == firstRowInTable) "restart" else "continue") else null
                    anchorR = mg.top; anchorC = mg.left
                }
                val cw = (i until i + span).sumOf { tw[it] }
                val cell = if (vm == "continue") null else g.rows.getOrNull(anchorR)?.getOrNull(anchorC)
                val fillCell = g.rows.getOrNull(anchorR)?.getOrNull(anchorC)
                w.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(cw.toString()).append("\" w:type=\"dxa\"/>")
                if (span > 1) w.append("<w:gridSpan w:val=\"").append(span.toString()).append("\"/>")
                if (vm == "restart") w.append("<w:vMerge w:val=\"restart\"/>") else if (vm == "continue") w.append("<w:vMerge/>")
                if (fillCell != null && fillCell.fill != 0) w.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(hex(fillCell.fill)).append("\"/>")
                w.append("<w:vAlign w:val=\"center\"/></w:tcPr><w:p><w:pPr>")
                if (cell != null) {
                    val al = when (cell.align) { HAlign.LEFT, HAlign.CENTER, HAlign.RIGHT -> cell.align; else -> if (cell.num) HAlign.RIGHT else HAlign.LEFT }
                    when (al) { HAlign.CENTER -> w.append("<w:jc w:val=\"center\"/>"); HAlign.RIGHT -> w.append("<w:jc w:val=\"right\"/>") }
                }
                w.append("</w:pPr>")
                if (cell != null && cell.text.isNotEmpty()) {
                    w.append("<w:r><w:rPr>")
                    if (cell.bold) w.append("<w:b/>")
                    if (cell.italic) w.append("<w:i/>")
                    if (cell.strike) w.append("<w:strike/>")
                    if (cell.color != 0) w.append("<w:color w:val=\"").append(hex(cell.color)).append("\"/>")
                    if (cell.underline) w.append("<w:u w:val=\"single\"/>")
                    w.append("</w:rPr>")
                    runText(w, cell.text)
                    w.append("</w:r>")
                }
                w.append("</w:p></w:tc>")
                i += span
            }
            w.append("</w:tr>")
        }
        w.append("</w:tbl><w:p/>")
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c.code < 32 && c != '\t' -> sb.append(' ')
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
