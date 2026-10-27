package cloud.depup.office

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Penulis .docx mini (tanpa library): paragraf teks hasil OCR dan/atau gambar halaman, ukuran A4. */
class DocxWriter(out: File) {
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

    fun paragraph(text: String) {
        body.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr><w:r><w:rPr><w:sz w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">")
            .append(esc(text)).append("</w:t></w:r></w:p>")
    }

    fun note(text: String) {
        body.append("<w:p><w:r><w:rPr><w:i/><w:color w:val=\"64748B\"/><w:sz w:val=\"20\"/></w:rPr><w:t xml:space=\"preserve\">")
            .append(esc(text)).append("</w:t></w:r></w:p>")
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
        val doc = xml +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">" +
            "<w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"
        val docRels = xml +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>"
        put("word/document.xml", doc.toByteArray())
        put("word/_rels/document.xml.rels", docRels.toByteArray())
        zip.finish(); zip.close()
    }

    fun abort(f: File) { try { zip.close() } catch (_: Exception) {}; f.delete() }
}
