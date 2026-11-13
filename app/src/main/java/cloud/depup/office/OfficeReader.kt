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
    /** Batas data yang dimuat untuk pratinjau/editor (UI hanya menampilkan ratusan baris pertama). */
    private const val CSV_MAX_CHARS = 4 * 1024 * 1024
    private const val SHEET_MAX_ROWS = 1000
    private const val SHEET_MAX_COLS = 256


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
    /**
     * Lembar pertama (urutan tab di workbook) — dibaca MENGALIR langsung dari zip & berhenti setelah [maxRows] baris;
     * sharedStrings hanya dibaca sampai indeks tertinggi yang dipakai baris-baris itu. Dulu seluruh lembar (dan semua
     * lembar lain) didekompresi ke memori, dan lembar > 12 MB malah dilewati sama sekali.
     */
    fun readXlsx(ctx: Context, uri: Uri, maxRows: Int = SHEET_MAX_ROWS): Sheet? = withZip(ctx, uri) { z ->
        fun entry(n: String) = z.getEntry(n) ?: z.entries().asSequence().firstOrNull { it.name.replace('\\', '/').trimStart('/').equals(n, true) }
        // workbook → lembar pertama & namanya
        var sheetName = "Sheet1"; var firstRid: String? = null
        entry("xl/workbook.xml")?.let { e -> z.getInputStream(e).use { ins -> pull(ins) { p ->
            if (p.eventType == XmlPullParser.START_TAG && localName(p) == "sheet" && firstRid == null) {
                p.getAttributeValue(null, "name")?.takeIf { it.isNotBlank() }?.let { sheetName = it }
                firstRid = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).endsWith("id") && p.getAttributeName(it).contains(':') }?.let { p.getAttributeValue(it) }
                false
            } else true
        } } }
        var sheetPath: String? = null
        var sstPath = "xl/sharedStrings.xml"
        entry("xl/_rels/workbook.xml.rels")?.let { e -> z.getInputStream(e).use { ins -> pull(ins) { p ->
            if (p.eventType == XmlPullParser.START_TAG && localName(p) == "relationship") {
                val id = p.getAttributeValue(null, "Id"); val t = p.getAttributeValue(null, "Target").orEmpty(); val type = p.getAttributeValue(null, "Type").orEmpty()
                val path = if (t.startsWith("/")) t.trimStart('/') else "xl/" + t.removePrefix("./")
                if (id != null && id == firstRid) sheetPath = path
                if (type.endsWith("/sharedStrings")) sstPath = path
            }
            true
        } } }
        val sheetEntry = sheetPath?.let { entry(it) }
            ?: z.entries().asSequence().filter { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
                .minByOrNull { it.name.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
            ?: return@withZip null
        val raw = HashMap<Int, HashMap<Int, String>>()      // isi sel; untuk t="s" berisi indeks
        val sIdx = HashMap<Int, HashMap<Int, Int>>()
        var maxRow = -1; var maxCol = -1; var maxS = -1
        z.getInputStream(sheetEntry).use { ins ->
            var row = -1; var col = -1; var type = ""; var inV = false; var inIs = false
            val v = StringBuilder()
            pull(ins) { p ->
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (localName(p)) {
                        "row" -> { row = p.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: (row + 1); col = -1; if (row >= maxRows) return@pull false }
                        "c" -> { type = p.getAttributeValue(null, "t").orEmpty(); col = p.getAttributeValue(null, "r")?.let { columnIndex(it) } ?: (col + 1); v.setLength(0) }
                        "v" -> inV = true
                        "is" -> inIs = true
                        "t" -> if (inIs) inV = true
                    }
                    XmlPullParser.TEXT -> if (inV) v.append(p.text.orEmpty())
                    XmlPullParser.END_TAG -> when (localName(p)) {
                        "v" -> inV = false
                        "t" -> if (inIs) inV = false
                        "is" -> inIs = false
                        "c" -> if (row in 0 until maxRows && col in 0 until SHEET_MAX_COLS && v.isNotEmpty()) {
                            if (type == "s") { val k = v.toString().trim().toIntOrNull(); if (k != null) { sIdx.getOrPut(row) { HashMap() }[col] = k; if (k > maxS) maxS = k } }
                            else raw.getOrPut(row) { HashMap() }[col] = v.toString()
                            if (row > maxRow) maxRow = row
                            if (col > maxCol) maxCol = col
                        }
                    }
                }
                true
            }
        }
        // sharedStrings: berhenti setelah indeks terbesar yang dibutuhkan
        val shared = ArrayList<String>()
        if (maxS >= 0) entry(sstPath)?.let { e -> z.getInputStream(e).use { ins ->
            var inSi = false; var inT = false; var inRph = false
            val sb = StringBuilder()
            pull(ins) { p ->
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (localName(p)) { "si" -> { inSi = true; sb.setLength(0) }; "t" -> if (inSi && !inRph) inT = true; "rph" -> inRph = true }
                    XmlPullParser.TEXT -> if (inT) sb.append(p.text.orEmpty())
                    XmlPullParser.END_TAG -> when (localName(p)) {
                        "t" -> inT = false; "rph" -> inRph = false
                        "si" -> { inSi = false; shared.add(sb.toString()); if (shared.size > maxS) return@pull false }
                    }
                }
                true
            }
        } }
        for ((r, m) in sIdx) for ((c, k) in m) shared.getOrNull(k)?.takeIf { it.isNotEmpty() }?.let { raw.getOrPut(r) { HashMap() }[c] = it }
        if (maxRow < 0 || maxCol < 0) return@withZip Sheet(sheetName, emptyList())
        val out = ArrayList<List<String>>(maxRow + 1)
        for (r in 0..maxRow) { val src = raw[r]; out.add(List(maxCol + 1) { c -> src?.get(c).orEmpty() }) }
        Sheet(sheetName, out, setOf(0))
    }

    /** Zip dengan akses acak: berkas lokal langsung; content:// disalin dulu ke cache (cepat, mengalir). */
    private fun <T> withZip(ctx: Context, uri: Uri, block: (java.util.zip.ZipFile) -> T?): T? {
        var tmp: java.io.File? = null
        return try {
            val f = if (uri.scheme == "file" && uri.path != null) java.io.File(uri.path!!) else {
                val t = java.io.File(ctx.cacheDir, "read-" + System.nanoTime() + ".zip"); tmp = t
                ctx.contentResolver.openInputStream(uri)?.use { ins -> t.outputStream().use { ins.copyTo(it, 64 * 1024) } } ?: return null
                t
            }
            java.util.zip.ZipFile(f).use { block(it) }
        } catch (_: Throwable) { null } finally { tmp?.delete() }
    }

    /** Jalankan pengurai tarik atas [ins]; [onEvent] mengembalikan false untuk berhenti. */
    private fun pull(ins: InputStream, onEvent: (XmlPullParser) -> Boolean) {
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(ins.buffered(64 * 1024), null)
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (!onEvent(p)) return
                p.next()
            }
        } catch (_: Exception) {}
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
            // Hanya ±4 MB pertama: pembaca & editor menampilkan maksimal ratusan baris, jadi CSV ratusan MB
            // tidak perlu (dan tidak boleh — bisa kehabisan memori) dimuat seluruhnya.
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val r = ins.bufferedReader()
                val buf = CharArray(CSV_MAX_CHARS)
                var n = 0
                while (n < buf.size) { val k = r.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                val full = n < buf.size || r.read() < 0
                val t = String(buf, 0, n)
                if (full) t else t.substring(0, (t.lastIndexOf('\n') + 1).takeIf { it > 0 } ?: t.length)
            }.orEmpty()
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
        while (i < text.length && rows.size <= 500) {
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
