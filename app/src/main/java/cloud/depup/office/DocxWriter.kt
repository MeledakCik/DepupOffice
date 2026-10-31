package cloud.depup.office

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Penulis .docx mini (tanpa library): paragraf teks hasil OCR dan/atau gambar halaman, ukuran A4. */
class DocxWriter(
    out: File,
    private val pageSize: String = "A4",
    private val marginMode: Int = 0,
    private val lineSpacing: Float = 1.15f,
    private val fontFamily: Int = 0
) {
    private companion object { const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" }
    private val zip = ZipOutputStream(FileOutputStream(out).buffered())
    private val body = StringBuilder()
    private val rels = StringBuilder()
    private var imgCount = 0
    private var first = true

    init {
        val types = XML +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"
        val root = XML +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
        put("[Content_Types].xml", types.toByteArray())
        put("_rels/.rels", root.toByteArray())
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c.code < 32 && c != '\t' -> sb.append(' ')
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun put(name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun pageBreakIfNeeded() {
        if (!first) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        first = false
    }

    fun beginPage() = pageBreakIfNeeded()

    /** Tulis teks ke dalam satu run, ubah baris baru menjadi <w:br/> agar tidak menyatu. */
    private fun appendRunText(target: StringBuilder, text: String) {
        text.split('\n').forEachIndexed { i, line ->
            if (i > 0) target.append("<w:br/>")
            target.append("<w:t xml:space=\"preserve\">").append(esc(line)).append("</w:t>")
        }
    }

    fun paragraph(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        align: Int = 0
    ) {
        val jc = when (align) {
            1 -> "<w:jc w:val=\"center\"/>"
            2 -> "<w:jc w:val=\"right\"/>"
            3 -> "<w:jc w:val=\"both\"/>"
            else -> ""
        }
        val rPr = buildString {
            if (bold) append("<w:b/>")
            if (italic) append("<w:i/>")
            append("<w:sz w:val=\"22\"/>")
            if (fontFamily == 1) append("<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\"/>")
        }
        body.append("<w:p><w:pPr>")
            .append("<w:spacing w:after=\"120\" w:line=\"${(lineSpacing * 240).toInt()}\" w:lineRule=\"auto\"/>")
            .append(jc)
            .append("</w:pPr><w:r><w:rPr>").append(rPr).append("</w:rPr>")
        appendRunText(body, text)
        body.append("</w:r></w:p>")
    }


    private fun rPrXml(bold: Boolean, italic: Boolean, sup: Boolean, sub: Boolean): String = buildString {
        if (fontFamily == 1) append("<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\"/>")
        if (bold) append("<w:b/>")
        if (italic) append("<w:i/>")
        append("<w:sz w:val=\"22\"/>")
        if (sup) append("<w:vertAlign w:val=\"superscript\"/>")
        if (sub) append("<w:vertAlign w:val=\"subscript\"/>")
    }

    private fun runsXml(runs: List<Run>): String {
        val sb = StringBuilder()
        for (r in runs) {
            sb.append("<w:r><w:rPr>").append(rPrXml(false, false, r.sup, r.sub)).append("</w:rPr>")
            appendRunText(sb, r.text)
            sb.append("</w:r>")
        }
        return sb.toString()
    }

    /** Paragraf dengan pangkat (superscript) dan indeks (subscript) asli Word. */
    fun runsParagraph(runs: List<Run>, align: Int = 0) {
        val jc = when (align) { 1 -> "<w:jc w:val=\"center\"/>"; 2 -> "<w:jc w:val=\"right\"/>"; 3 -> "<w:jc w:val=\"both\"/>"; else -> "" }
        body.append("<w:p><w:pPr><w:spacing w:after=\"120\" w:line=\"${(lineSpacing * 240).toInt()}\" w:lineRule=\"auto\"/>")
            .append(jc).append("</w:pPr>").append(runsXml(runs)).append("</w:p>")
    }

    private fun textWidthDxa(): Int {
        val pgW = when (pageSize) { "Letter" -> 12240; "F4" -> 11907; else -> 11906 }
        val margin = when (marginMode) { 1 -> 680; 2 -> 2154; else -> 1417 }
        return pgW - 2 * margin
    }

    /** Tabel Word sungguhan (gridSpan / vMerge untuk sel gabungan), lebar kolom mengikuti foto. */
    fun table(t: Table) {
        val total = (textWidthDxa() * t.widthFrac).toInt()
        val sumW = t.colW.sum().coerceAtLeast(1)
        val w = t.colW.map { (total.toLong() * it / sumW).toInt().coerceAtLeast(300) }
        val bd = if (t.bordered) "<w:%s w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>" else "<w:%s w:val=\"nil\"/>"
        val borders = listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { bd.format(it) }
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"${w.sum()}\" w:type=\"dxa\"/><w:tblBorders>").append(borders).append("</w:tblBorders>")
            .append("<w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"80\" w:type=\"dxa\"/>")
            .append("<w:bottom w:w=\"40\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        w.forEach { body.append("<w:gridCol w:w=\"$it\"/>") }
        body.append("</w:tblGrid>")
        val grid = Array(t.rows) { arrayOfNulls<TCell>(t.cols) }
        for (c in t.cells) for (r in c.r until minOf(t.rows, c.r + c.rs)) for (cc in c.c until minOf(t.cols, c.c + c.cs)) grid[r][cc] = c
        for (r in 0 until t.rows) {
            body.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
            var c = 0
            while (c < t.cols) {
                val cell = grid[r][c]
                if (cell == null) { // kisi tidak lengkap: sel kosong
                    body.append("<w:tc><w:tcPr><w:tcW w:w=\"${w[c]}\" w:type=\"dxa\"/></w:tcPr><w:p/></w:tc>"); c++; continue
                }
                val span = minOf(cell.cs, t.cols - cell.c)
                val cw = (cell.c until cell.c + span).sumOf { w[it] }
                body.append("<w:tc><w:tcPr><w:tcW w:w=\"$cw\" w:type=\"dxa\"/>")
                if (span > 1) body.append("<w:gridSpan w:val=\"$span\"/>")
                if (cell.rs > 1) body.append(if (cell.r == r) "<w:vMerge w:val=\"restart\"/>" else "<w:vMerge/>")
                body.append("</w:tcPr>")
                if (cell.r == r && cell.paras.isNotEmpty()) {
                    for (p in cell.paras) body.append("<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr>").append(runsXml(p)).append("</w:p>")
                } else body.append("<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr></w:p>")
                body.append("</w:tc>")
                c += span
            }
            body.append("</w:tr>")
        }
        body.append("</w:tbl><w:p><w:pPr><w:spacing w:after=\"60\"/></w:pPr></w:p>")
    }

    fun note(text: String) {
        body.append("<w:p><w:pPr><w:spacing w:after=\"120\" w:line=\"${(lineSpacing * 240).toInt()}\" w:lineRule=\"auto\"/></w:pPr>")
            .append("<w:r><w:rPr><w:i/><w:color w:val=\"64748B\"/><w:sz w:val=\"20\"/></w:rPr>")
        appendRunText(body, text)
        body.append("</w:r></w:p>")
    }

    /** Gambar JPEG satu halaman, diskalakan agar muat dalam area tulis A4 (17 x 25,7 cm). */
    fun image(jpeg: ByteArray, wPx: Int, hPx: Int) {
        imgCount++
        val n = imgCount
        put("word/media/image$n.jpeg", jpeg)
        rels.append("<Relationship Id=\"rIdImg$n\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image$n.jpeg\"/>")
        val maxW = 6120000.0; val maxH = 9252000.0
        val s = minOf(maxW / wPx, maxH / hPx)
        val cx = (wPx * s).toLong(); val cy = (hPx * s).toLong()
        body.append(
            "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:docPr id=\"$n\" name=\"Halaman $n\"/>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
            "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"image$n.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"rIdImg$n\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        )
    }

    fun finish() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        val (pgW, pgH) = when (pageSize) {
            "Letter" -> 12240 to 15840
            "F4" -> 11907 to 18720
            else -> 11906 to 16838
        }
        val margin = when (marginMode) {
            1 -> 680   // Sempit ±1,2 cm
            2 -> 2154  // Lebar ±3,8 cm
            else -> 1417 // Normal ±2,5 cm
        }
        val doc = xml +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">" +
            "<w:body>$body<w:sectPr><w:pgSz w:w=\"$pgW\" w:h=\"$pgH\"/>" +
            "<w:pgMar w:top=\"$margin\" w:right=\"$margin\" w:bottom=\"$margin\" w:left=\"$margin\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"
        val docRels = xml +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>"
        put("word/document.xml", doc.toByteArray())
        put("word/_rels/document.xml.rels", docRels.toByteArray())
        zip.finish(); zip.close()
    }

    fun abort(f: File) { try { zip.close() } catch (_: Exception) {}; f.delete() }
}
