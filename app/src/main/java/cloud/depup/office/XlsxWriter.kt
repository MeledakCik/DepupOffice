package cloud.depup.office

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Penulis .xlsx mini tanpa library: satu sheet, teks + angka, header tebal.
 * Cukup untuk hasil ekspor spreadsheet Depup Office dan tetap bisa dibuka Excel/Sheets.
 */
object XlsxWriter {

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    fun write(
        out: File,
        sheetName: String,
        rows: List<List<String>>,
        boldRows: Set<Int> = setOf(0),
        columnCount: Int = rows.maxOfOrNull { it.size } ?: 0
    ) {
        val safeName = sheetName.ifBlank { "Sheet1" }.take(31)
            .replace(Regex("[\\\\/*?\\[\\]:]"), "_")
        val cols = columnCount.coerceAtLeast(1)

        ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            put("[Content_Types].xml", contentTypes())
            put("_rels/.rels", rootRels())
            put("xl/workbook.xml", workbook(safeName))
            put("xl/_rels/workbook.xml.rels", workbookRels())
            put("xl/styles.xml", styles())
            put("xl/worksheets/sheet1.xml", worksheet(rows, boldRows, cols))
        }
    }

    private fun contentTypes() = XML +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private fun rootRels() = XML +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private fun workbook(sheetName: String) = XML +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"${esc(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
        "</workbook>"

    private fun workbookRels() = XML +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    private fun styles() = XML +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<fonts count=\"2\">" +
        "<font><sz val=\"11\"/><color theme=\"1\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
        "<font><b/><sz val=\"11\"/><color theme=\"1\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
        "</fonts>" +
        "<fills count=\"2\">" +
        "<fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill>" +
        "</fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"

    private fun worksheet(rows: List<List<String>>, boldRows: Set<Int>, cols: Int): String {
        val sb = StringBuilder()
        sb.append(XML)
            .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (rows.isNotEmpty()) {
            sb.append("<dimension ref=\"A1:${columnName(cols - 1)}${rows.size}\"/>")
        }

        // Lebar kolom mengikuti isi terpanjang (maks 40 karakter).
        sb.append("<cols>")
        for (c in 0 until cols) {
            val width = rows.maxOfOrNull { row -> (row.getOrNull(c) ?: "").length }
                ?.coerceIn(10, 40) ?: 14
            sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$width\" customWidth=\"1\"/>")
        }
        sb.append("</cols><sheetData>")

        rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\">")
            val bold = r in boldRows
            for (c in 0 until cols) {
                val raw = row.getOrNull(c).orEmpty().trim()
                if (raw.isEmpty()) continue
                val ref = "${columnName(c)}${r + 1}"
                val style = if (bold) " s=\"1\"" else ""
                val numeric = parseNumber(raw)
                if (numeric != null) {
                    sb.append("<c r=\"$ref\"$style><v>${trimNumber(numeric)}</v></c>")
                } else {
                    sb.append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(raw)}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun parseNumber(raw: String): Double? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        // Hanya buang simbol mata uang, JANGAN buang huruf lain (mis. "INV-001" harus tetap teks).
        s = s.replace(Regex("(?i)\\bRp\\b|\\bIDR\\b"), "")
            .replace("\$", "")
            .replace("€", "")
            .trim()
        if (s.isEmpty() || s.none { it.isDigit() }) return null
        // Hanya angka, tanda +/- , pemisah desimal/ribuan, dan spasi.
        if (!s.matches(Regex("^[+\\-0-9.,\\s]+$"))) return null
        val compact = s.replace(Regex("\\s+"), "")
        // Nomor HP / kode berawalan nol tetap teks agar angka nol tidak hilang.
        if (compact.length > 1 && compact.startsWith("0") &&
            !compact.startsWith("0.") && !compact.startsWith("0,")
        ) return null
        return when {
            compact.contains('.') && compact.contains(',') ->
                compact.replace(".", "").replace(',', '.').toDoubleOrNull()
            compact.count { it == ',' } == 1 && compact.substringAfter(',').length in 1..2 ->
                compact.replace(',', '.').toDoubleOrNull()
            compact.count { it == '.' } == 1 && compact.substringAfter('.').length in 1..2 ->
                compact.toDoubleOrNull()
            else -> compact.replace(".", "").replace(",", "").toDoubleOrNull()
        }
    }

    private fun trimNumber(v: Double): String =
        if (kotlin.math.abs(v - kotlin.math.round(v)) < 0.0000001) v.toLong().toString() else v.toString()

    fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c.code < 32 && c != '\t' && c != '\n' && c != '\r' -> sb.append(' ')
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
