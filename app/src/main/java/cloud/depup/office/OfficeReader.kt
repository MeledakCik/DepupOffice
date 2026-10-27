package cloud.depup.office

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pembaca ringan untuk dokumen Office (.docx / .xlsx) dan CSV.
 * Tanpa library pihak ketiga: hanya membongkar ZIP OOXML lalu membaca XML-nya.
 * Dipakai untuk pratinjau di Viewer dan untuk membuka berkas di editor.
 */
object OfficeReader {

    class Paragraph(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val align: Int = 0 // 0 kiri, 1 tengah, 2 kanan, 3 rata kiri-kanan
    )

    class Sheet(
        val name: String,
        val rows: List<List<String>>,
        val boldRows: Set<Int> = emptySet()
    )

    // ================= DOCX =================

    fun readDocx(ctx: Context, uri: Uri): List<Paragraph> {
        val entries = readZip(ctx, uri) { name ->
            name == "word/document.xml"
        }
        val xml = entries["word/document.xml"] ?: return emptyList()
        return parseDocx(xml)
    }

    fun readDocxText(ctx: Context, uri: Uri): String =
        readDocx(ctx, uri).joinToString("\n\n") { it.text }

    private fun parseDocx(data: ByteArray): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(ByteArrayInputStream(data), "UTF-8")

            var inParagraph = false
            var inText = false
            var bold = false
            var italic = false
            var align = 0
            val text = StringBuilder()

            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (localName(p)) {
                            "p" -> {
                                inParagraph = true
                                bold = false
                                italic = false
                                align = 0
                                text.setLength(0)
                            }
                            "b" -> if (inParagraph) bold = !isOff(p.getAttributeValue(null, "w:val") ?: p.getAttributeValue(null, "val"))
                            "i" -> if (inParagraph) italic = !isOff(p.getAttributeValue(null, "w:val") ?: p.getAttributeValue(null, "val"))
                            "jc" -> {
                                if (inParagraph) {
                                    align = when ((p.getAttributeValue(null, "w:val") ?: p.getAttributeValue(null, "val")).orEmpty()) {
                                        "center" -> 1
                                        "right", "end" -> 2
                                        "both", "justify" -> 3
                                        else -> 0
                                    }
                                }
                            }
                            "t" -> inText = true
                            "br", "cr" -> if (inParagraph) text.append('\n')
                            "tab" -> if (inParagraph) text.append('\t')
                        }
                    }
                    XmlPullParser.TEXT -> if (inParagraph && inText) text.append(p.text.orEmpty())
                    XmlPullParser.END_TAG -> {
                        when (localName(p)) {
                            "t" -> inText = false
                            "p" -> {
                                if (inParagraph) {
                                    val value = text.toString().trim()
                                    if (value.isNotEmpty()) {
                                        out.add(Paragraph(value, bold, italic, align))
                                    }
                                    if (out.size >= 800) return out
                                }
                                inParagraph = false
                                inText = false
                            }
                        }
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
            // Biarkan pemanggil menampilkan pesan bila hasil kosong.
        }
        return out
    }

    private fun isOff(value: String?): Boolean =
        value == "0" || value.equals("false", ignoreCase = true) || value.equals("off", ignoreCase = true)

    // ================= XLSX =================

    /** Membaca sheet pertama. Baris/kolom kosong di ujung dipangkas. */
    fun readXlsx(ctx: Context, uri: Uri): Sheet? {
        val entries = readZip(ctx, uri) { name ->
            name == "xl/workbook.xml" ||
                name == "xl/sharedStrings.xml" ||
                (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
        }
        val sheetEntry = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .minByOrNull { it }
            ?: return null

        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetName = entries["xl/workbook.xml"]?.let { parseSheetName(it) } ?: "Sheet1"
        val rows = parseWorksheet(entries.getValue(sheetEntry), shared)
        if (rows.isEmpty()) return Sheet(sheetName, emptyList())
        return Sheet(sheetName, rows, setOf(0))
    }

    private fun parseSharedStrings(data: ByteArray): List<String> {
        val out = ArrayList<String>()
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(ByteArrayInputStream(data), "UTF-8")
            var inSi = false
            var inText = false
            val sb = StringBuilder()
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (localName(p)) {
                        "si" -> { inSi = true; sb.setLength(0) }
                        "t" -> if (inSi) inText = true
                    }
                    XmlPullParser.TEXT -> if (inSi && inText) sb.append(p.text.orEmpty())
                    XmlPullParser.END_TAG -> when (localName(p)) {
                        "t" -> inText = false
                        "si" -> {
                            inSi = false
                            out.add(sb.toString())
                        }
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun parseSheetName(data: ByteArray): String? {
        return try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(ByteArrayInputStream(data), "UTF-8")
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && localName(p) == "sheet") {
                    val name = p.getAttributeValue(null, "name")
                    if (!name.isNullOrBlank()) return name
                }
                event = p.next()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseWorksheet(data: ByteArray, shared: List<String>): List<List<String>> {
        val cells = HashMap<Int, HashMap<Int, String>>()
        var maxRow = -1
        var maxCol = -1
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(ByteArrayInputStream(data), "UTF-8")

            var row = -1
            var col = -1
            var cellType = ""
            var inValue = false
            var inInline = false
            var value = StringBuilder()

            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (localName(p)) {
                        "row" -> {
                            row = p.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: (row + 1)
                            col = -1
                        }
                        "c" -> {
                            cellType = p.getAttributeValue(null, "t").orEmpty()
                            val ref = p.getAttributeValue(null, "r")
                            col = ref?.let { columnIndex(it) } ?: (col + 1)
                            value = StringBuilder()
                        }
                        "v" -> inValue = true
                        "is" -> inInline = true
                        "t" -> if (inInline) inValue = true
                    }
                    XmlPullParser.TEXT -> if (inValue) value.append(p.text.orEmpty())
                    XmlPullParser.END_TAG -> when (localName(p)) {
                        "v" -> inValue = false
                        "t" -> if (inInline) inValue = false
                        "is" -> inInline = false
                        "c" -> {
                            if (row >= 0 && col >= 0) {
                                var text = value.toString()
                                if (cellType == "s") {
                                    text = shared.getOrNull(text.trim().toIntOrNull() ?: -1).orEmpty()
                                }
                                if (text.isNotEmpty()) {
                                    cells.getOrPut(row) { HashMap() }[col] = text
                                    if (row > maxRow) maxRow = row
                                    if (col > maxCol) maxCol = col
                                }
                            }
                        }
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
        }

        if (maxRow < 0 || maxCol < 0) return emptyList()
        val out = ArrayList<List<String>>(maxRow + 1)
        for (r in 0..maxRow) {
            val src = cells[r]
            val row = ArrayList<String>(maxCol + 1)
            for (c in 0..maxCol) row.add(src?.get(c).orEmpty())
            out.add(row)
        }
        return out
    }

    /** "AB12" -> indeks kolom 27 (0-based). */
    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
            else break
        }
        return (n - 1).coerceAtLeast(0)
    }

    // ================= CSV =================

    fun readCsv(ctx: Context, uri: Uri): Sheet {
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val sep = when {
            text.lineSequence().take(3).any { it.count { c -> c == ';' } > it.count { c -> c == ',' } } -> ';'
            text.lineSequence().take(3).any { it.contains('\t') } -> '\t'
            else -> ','
        }
        val rows = parseCsv(text, sep)
            .filter { row -> row.any { it.isNotBlank() } }
        return Sheet("Sheet1", rows, setOf(0))
    }

    private fun parseCsv(text: String, sep: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row.add(cell.toString()); cell.setLength(0) }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.setLength(0)
                    rows.add(ArrayList(row)); row.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(ArrayList(row))
        }
        if (rows.size > 500) return rows.take(500)
        return rows
    }

    // ================= ZIP =================

    private fun readZip(ctx: Context, uri: Uri, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input: InputStream ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    var total = 0L
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && wanted(name) && entry.size < 12L * 1024 * 1024) {
                            val data = zip.readBytes()
                            total += data.size
                            if (total <= 24L * 1024 * 1024) out[name] = data
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun localName(p: XmlPullParser): String =
        (p.name ?: "").substringAfter(':').lowercase()
}
