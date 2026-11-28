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

    /** Satu lembar kerja untuk [writeBook]. */
    class Sheet(
        val name: String,
        val rows: List<List<String>>,
        val boldRows: Set<Int> = setOf(0),
        val columnCount: Int = rows.maxOfOrNull { it.size } ?: 0,
        /**
         * Rumus sel (r, c) → (rumus tanpa "=", nilai hasil: Double / String / Boolean / null). Ditulis sebagai rumus
         * Excel asli, bukan angka mati.
         */
        val formulas: (Int, Int) -> Pair<String, Any?>? = { _, _ -> null },
        /** Format sel (editor Sheet); null = pakai [boldRows]. */
        val styleOf: ((Int, Int) -> CellStyle?)? = null,
        /** Lebar kolom (satuan karakter Excel; 0 = disembunyikan). Tanpa isi = lebar mengikuti isi. */
        val colWidths: Map<Int, Float>? = null,
        /** Tinggi baris (pt; 0 = disembunyikan). */
        val rowHeights: Map<Int, Float> = emptyMap(),
        val merges: List<CellRange> = emptyList(),
        /** Simbol mata uang untuk format "Mata uang". */
        val currency: String = "Rp"
    )

    /** Daftar gaya buku kerja: font, isi, format angka, dan xf dibuat sesuai kebutuhan saat lembar ditulis. */
    private class Styles {
        val fonts = ArrayList<String>()
        val fills = ArrayList<String>()
        val numFmts = LinkedHashMap<String, Int>()
        val xfs = ArrayList<String>()
        private val fontIdx = HashMap<String, Int>()
        private val fillIdx = HashMap<String, Int>()
        private val xfIdx = HashMap<String, Int>()
        private val styleIdx = HashMap<Pair<CellStyle, String>, Int>()

        init {
            font("<sz val=\"11\"/><color theme=\"1\"/><name val=\"Calibri\"/><family val=\"2\"/>")
            font("<b/><sz val=\"11\"/><color theme=\"1\"/><name val=\"Calibri\"/><family val=\"2\"/>")
            fill("<patternFill patternType=\"none\"/>")
            fill("<patternFill patternType=\"gray125\"/>")
            xf("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
            xf("<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>")
        }

        private fun font(x: String) = fontIdx.getOrPut(x) { fonts.add(x); fonts.size - 1 }
        private fun fill(x: String) = fillIdx.getOrPut(x) { fills.add(x); fills.size - 1 }
        private fun xf(x: String) = xfIdx.getOrPut(x) { xfs.add(x); xfs.size - 1 }
        private fun argb(c: Int) = String.format("FF%06X", c and 0xFFFFFF)

        private fun numFmt(f: Int, currency: String): Int = when (f) {
            CellNumFmt.NUMBER -> 4
            CellNumFmt.THOUSANDS -> 3
            CellNumFmt.PERCENT -> 10
            CellNumFmt.CURRENCY -> custom("\"${currency.replace("\"", "")}\" #,##0")
            CellNumFmt.DATE -> custom("dd/mm/yyyy")
            else -> 0
        }

        private fun custom(code: String) = numFmts.getOrPut(code) { 164 + numFmts.size }

        /** Indeks xf untuk gaya editor. */
        fun of(st: CellStyle, currency: String): Int = styleIdx.getOrPut(st to currency) {
            val size = if (st.size > 0f) st.size else 11f
            val f = buildString {
                if (st.bold) append("<b/>")
                if (st.italic) append("<i/>")
                if (st.strike) append("<strike/>")
                if (st.underline) append("<u/>")
                append("<sz val=\"").append(trimNumber(size.toDouble())).append("\"/>")
                if (st.color != 0) append("<color rgb=\"").append(argb(st.color)).append("\"/>") else append("<color theme=\"1\"/>")
                append("<name val=\"Calibri\"/><family val=\"2\"/>")
            }
            val fontId = font(f)
            val fillId = if (st.fill != 0) fill("<patternFill patternType=\"solid\"><fgColor rgb=\"${argb(st.fill)}\"/><bgColor indexed=\"64\"/></patternFill>") else 0
            val nf = numFmt(st.numFmt, currency)
            val h = when (st.align) { HAlign.LEFT -> "left"; HAlign.CENTER -> "center"; HAlign.RIGHT -> "right"; else -> null }
            val align = if (h != null || st.wrap) "<alignment" + (if (h != null) " horizontal=\"$h\"" else "") +
                (if (st.wrap) " wrapText=\"1\"" else "") + " vertical=\"center\"/>" else ""
            xf(
                "<xf numFmtId=\"$nf\" fontId=\"$fontId\" fillId=\"$fillId\" borderId=\"0\" xfId=\"0\"" +
                    (if (nf != 0) " applyNumberFormat=\"1\"" else "") + (if (fontId != 0) " applyFont=\"1\"" else "") +
                    (if (fillId != 0) " applyFill=\"1\"" else "") + (if (align.isNotEmpty()) " applyAlignment=\"1\">$align</xf>" else "/>")
            )
        }

        fun xml(): String = XML +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            (if (numFmts.isNotEmpty()) "<numFmts count=\"${numFmts.size}\">" +
                numFmts.entries.joinToString("") { "<numFmt numFmtId=\"${it.value}\" formatCode=\"${esc(it.key)}\"/>" } + "</numFmts>" else "") +
            "<fonts count=\"${fonts.size}\">" + fonts.joinToString("") { "<font>$it</font>" } + "</fonts>" +
            "<fills count=\"${fills.size}\">" + fills.joinToString("") { "<fill>$it</fill>" } + "</fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"${xfs.size}\">" + xfs.joinToString("") + "</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"
    }

    fun write(
        out: File,
        sheetName: String,
        rows: List<List<String>>,
        boldRows: Set<Int> = setOf(0),
        columnCount: Int = rows.maxOfOrNull { it.size } ?: 0,
        /** Rumus sel (r, c) → (rumus tanpa "=", nilai hasil). Ditulis sebagai rumus Excel asli, bukan angka mati. */
        formulas: (Int, Int) -> Pair<String, Any?>? = { _, _ -> null }
    ) = writeBook(out, listOf(Sheet(sheetName, rows, boldRows, columnCount, formulas)))

    /** Buku kerja dengan beberapa lembar (urutan & nama sesuai [sheets]). */
    fun writeBook(out: File, sheets: List<Sheet>) {
        require(sheets.isNotEmpty()) { "Tidak ada sheet untuk disimpan" }
        // nama sheet Excel: maks 31 karakter, tanpa \ / * ? [ ] :, unik (tanpa membedakan huruf besar/kecil)
        val used = HashSet<String>()
        val names = sheets.mapIndexed { i, sh ->
            val base = sh.name.ifBlank { "Sheet${i + 1}" }.replace(Regex("[\\\\/*?\\[\\]:]"), "_").take(31)
            var n = base; var k = 2
            while (!used.add(n.lowercase())) { val suf = " ($k)"; n = base.take(31 - suf.length) + suf; k++ }
            n
        }

        ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            put("[Content_Types].xml", contentTypes(sheets.size))
            put("_rels/.rels", rootRels())
            put("xl/workbook.xml", workbook(names))
            put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            val st = Styles()
            sheets.forEachIndexed { i, sh ->
                // lembar DITULIS MENGALIR ke zip (bukan satu String raksasa di memori): ukuran lembar hanya dibatasi
                // ruang simpan, bukan memori HP
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
                val w = java.io.BufferedWriter(java.io.OutputStreamWriter(zip, Charsets.UTF_8), 64 * 1024)
                worksheet(w, sh, sh.columnCount.coerceAtLeast(1), st)
                w.flush()   // jangan close(): itu menutup zip
                zip.closeEntry()
            }
            // gaya ditulis terakhir: baru lengkap setelah semua lembar ditulis
            put("xl/styles.xml", st.xml())
        }
    }

    private fun contentTypes(n: Int) = XML +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        (1..n).joinToString("") { "<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private fun rootRels() = XML +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private fun workbook(names: List<String>) = XML +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets>" + names.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets>" +
        "</workbook>"

    // lembar = rId1..rIdN, gaya = rId(N+1)
    private fun workbookRels(n: Int) = XML +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        (1..n).joinToString("") { "<Relationship Id=\"rId$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$it.xml\"/>" } +
        "<Relationship Id=\"rId${n + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    private fun worksheet(sb: Appendable, sh: Sheet, cols: Int, styles: Styles) {
        val rows = sh.rows; val boldRows = sh.boldRows; val formulas = sh.formulas
        sb.append(XML)
            .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (rows.isNotEmpty()) {
            sb.append("<dimension ref=\"A1:${columnName(cols - 1)}${rows.size}\"/>")
        }

        sb.append("<cols>")
        val widths = sh.colWidths
        if (widths != null) {
            // lebar dari editor (kolom yang tidak diubah = lebar bawaan)
            for (c in 0 until cols) {
                val w = widths[c] ?: (SheetData.DEFAULT_COL_W / SheetData.DP_PER_CHAR)
                if (w <= 0f) sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"0\" hidden=\"1\" customWidth=\"1\"/>")
                else sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${trimNumber(Math.round(w * 100) / 100.0)}\" customWidth=\"1\"/>")
            }
        } else {
            // lebar mengikuti isi terpanjang (maks 40 karakter) — satu lintasan atas semua sel
            val longest = IntArray(cols)
            for (row in rows) for (c in 0 until minOf(cols, row.size)) { val n = row[c].length; if (n > longest[c]) longest[c] = n }
            for (c in 0 until cols) {
                val width = if (rows.isEmpty()) 14 else longest[c].coerceIn(10, 40)
                sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$width\" customWidth=\"1\"/>")
            }
        }
        sb.append("</cols><sheetData>")

        rows.forEachIndexed { r, row ->
            val ht = sh.rowHeights[r]
            sb.append("<row r=\"${r + 1}\"")
            if (ht != null) { if (ht <= 0f) sb.append(" ht=\"0\" hidden=\"1\" customHeight=\"1\"") else sb.append(" ht=\"${trimNumber(Math.round(ht * 100) / 100.0)}\" customHeight=\"1\"") }
            sb.append(">")
            val bold = r in boldRows
            for (c in 0 until cols) {
                val ref = "${columnName(c)}${r + 1}"
                val xf = sh.styleOf?.let { f -> f(r, c)?.let { if (it.isDefault) 0 else styles.of(it, sh.currency) } ?: 0 } ?: (if (bold) 1 else 0)
                val style = if (xf != 0) " s=\"$xf\"" else ""
                val fx = formulas(r, c)
                if (fx != null) {
                    // rumus tetap rumus di Excel/Sheets; nilai tersimpan = hasil terakhir (presisi penuh)
                    val v = fx.second
                    val t = when (v) { is String -> " t=\"str\""; is Boolean -> " t=\"b\""; else -> "" }
                    sb.append("<c r=\"$ref\"$style$t><f>").append(esc(excelFormula(fx.first))).append("</f>")
                    when (v) {
                        is Double -> if (!v.isNaN() && !v.isInfinite()) sb.append("<v>").append(trimNumber(v)).append("</v>")
                        is String -> sb.append("<v>").append(esc(v)).append("</v>")
                        is Boolean -> sb.append("<v>").append(if (v) "1" else "0").append("</v>")
                    }
                    sb.append("</c>")
                    continue
                }
                val raw = row.getOrNull(c).orEmpty().trim()
                if (raw.isEmpty()) { if (xf != 0) sb.append("<c r=\"$ref\"$style/>"); continue }
                val numeric = parseNumber(raw)
                if (numeric != null) {
                    sb.append("<c r=\"$ref\"$style><v>${trimNumber(numeric)}</v></c>")
                } else {
                    sb.append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(raw)}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (sh.merges.isNotEmpty()) {
            sb.append("<mergeCells count=\"${sh.merges.size}\">")
            for (m in sh.merges) sb.append("<mergeCell ref=\"${m.ref()}\"/>")
            sb.append("</mergeCells>")
        }
        sb.append("</worksheet>")
    }

    /** Angka dari teks bergaya Indonesia/internasional ("Rp 15.000", "1.234,5", "1,234.5"); null bila bukan angka. */
    internal fun parseNumber(raw: String): Double? {
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

    /**
     * Rumus editor → sintaks berkas Excel: tanpa "=", nama fungsi huruf besar, AVG → AVERAGE (AVG bukan fungsi Excel).
     * Pemisah argumen editor sudah koma (= format berkas).
     */
    private fun excelFormula(f: String): String {
        val e = f.trim().removePrefix("=").trim()
        if (e.contains('"')) return e
        return e.uppercase().replace(Regex("\\bAVG\\("), "AVERAGE(")
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
