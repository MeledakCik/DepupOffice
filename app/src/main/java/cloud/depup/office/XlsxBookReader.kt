package cloud.depup.office

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Pembaca .xlsx lengkap untuk editor Sheet: SEMUA lembar, isi, rumus (yang didukung mesin rumus — rumus lain
 * dimuat sebagai nilainya), format sel (huruf, warna teks & isi termasuk warna tema + tint, perataan, bungkus teks,
 * format angka), lebar kolom, tinggi baris, kolom/baris tersembunyi, dan sel gabungan. Tanpa batas baris/kolom —
 * hanya memori HP (OutOfMemoryError diteruskan ke pemanggil).
 */
object XlsxBookReader {

    class Book(val sheets: List<SheetData>)

    fun read(ctx: Context, uri: Uri): Book? {
        var tmp: File? = null
        try {
            val f = if (uri.scheme == "file" && uri.path != null) File(uri.path!!) else {
                val t = File(ctx.cacheDir, "book-" + System.nanoTime() + ".xlsx"); tmp = t
                ctx.contentResolver.openInputStream(uri)?.use { ins -> t.outputStream().use { ins.copyTo(it, 64 * 1024) } } ?: return null
                t
            }
            return ZipFile(f).use { readZip(it) }
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (_: Exception) {
            return null
        } finally { tmp?.delete() }
    }

    private fun entry(z: ZipFile, n: String) = z.getEntry(n)
        ?: z.entries().asSequence().firstOrNull { it.name.replace('\\', '/').trimStart('/').equals(n, true) }

    private fun pull(ins: InputStream, onEvent: (XmlPullParser) -> Boolean) {
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(ins.buffered(64 * 1024), null)
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (!onEvent(p)) return
                p.next()
            }
        } catch (_: org.xmlpull.v1.XmlPullParserException) {
        } catch (_: java.io.IOException) {}
    }

    private fun local(p: XmlPullParser) = (p.name ?: "").substringAfter(':')
    private fun attr(p: XmlPullParser, n: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i).substringAfter(':') == n) return p.getAttributeValue(i)
        return null
    }

    private fun resolve(base: String, target: String): String =
        if (target.startsWith("/")) target.trimStart('/') else {
            val parts = (base.substringBeforeLast('/', "") + "/" + target.removePrefix("./")).split('/').toMutableList()
            val out = ArrayList<String>()
            for (s in parts) when (s) { "", "." -> {}; ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1); else -> out.add(s) }
            out.joinToString("/")
        }

    private fun readZip(z: ZipFile): Book? {
        // lembar & relasi
        val sheetsMeta = ArrayList<Pair<String, String>>()   // nama, rId
        entry(z, "xl/workbook.xml")?.let { e -> z.getInputStream(e).use { ins -> pull(ins) { p ->
            if (p.eventType == XmlPullParser.START_TAG && local(p) == "sheet") {
                val name = attr(p, "name") ?: "Sheet${sheetsMeta.size + 1}"
                val rid = attr(p, "id") ?: ""
                if (attr(p, "state") != "veryHidden") sheetsMeta.add(name to rid)
            }
            true
        } } }
        val rels = HashMap<String, String>()
        var sstPath = "xl/sharedStrings.xml"; var stylesPath = "xl/styles.xml"; var themePath = "xl/theme/theme1.xml"
        entry(z, "xl/_rels/workbook.xml.rels")?.let { e -> z.getInputStream(e).use { ins -> pull(ins) { p ->
            if (p.eventType == XmlPullParser.START_TAG && local(p) == "Relationship") {
                val id = attr(p, "Id"); val target = attr(p, "Target").orEmpty(); val type = attr(p, "Type").orEmpty()
                val path = resolve("xl/workbook.xml", target)
                if (id != null) rels[id] = path
                when {
                    type.endsWith("/sharedStrings") -> sstPath = path
                    type.endsWith("/styles") -> stylesPath = path
                    type.endsWith("/theme") -> themePath = path
                }
            }
            true
        } } }

        val theme = readTheme(z, themePath)
        val styles = readStyles(z, stylesPath, theme)
        val shared = readShared(z, sstPath)

        val out = ArrayList<SheetData>()
        for ((name, rid) in sheetsMeta) {
            val path = rels[rid] ?: continue
            val e = entry(z, path) ?: continue
            z.getInputStream(e).use { out.add(readSheet(it, name, styles, shared)) }
        }
        if (out.isEmpty()) return null
        return Book(out)
    }

    // ---------- tema & warna ----------

    /** Warna tema berurutan seperti indeks Excel: lt1, dk1, lt2, dk2, accent1…6, hlink, folHlink. */
    private fun readTheme(z: ZipFile, path: String): IntArray {
        val scheme = LinkedHashMap<String, Int>()
        var cur: String? = null
        entry(z, path)?.let { e -> z.getInputStream(e).use { ins -> pull(ins) { p ->
            if (p.eventType == XmlPullParser.START_TAG) {
                val n = local(p)
                when {
                    n in setOf("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink") -> cur = n
                    n == "srgbClr" && cur != null -> { attr(p, "val")?.toLongOrNull(16)?.let { scheme[cur!!] = (0xFF000000 or it).toInt() }; cur = null }
                    n == "sysClr" && cur != null -> { attr(p, "lastClr")?.toLongOrNull(16)?.let { scheme[cur!!] = (0xFF000000 or it).toInt() }; cur = null }
                }
            } else if (p.eventType == XmlPullParser.END_TAG && local(p) == "clrScheme") return@pull false
            true
        } } }
        val order = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        val def = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE7E6E6.toInt(), 0xFF44546A.toInt(), 0xFF4472C4.toInt(), 0xFFED7D31.toInt(),
            0xFFA5A5A5.toInt(), 0xFFFFC000.toInt(), 0xFF5B9BD5.toInt(), 0xFF70AD47.toInt(), 0xFF0563C1.toInt(), 0xFF954F72.toInt())
        return IntArray(order.size) { scheme[order[it]] ?: def[it] }
    }

    /** Palet indeks lama Excel (0–63). */
    private val INDEXED = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00,
        0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF, 0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF,
        0x800080, 0x800000, 0x008080, 0x0000FF, 0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696, 0x003366, 0x339966, 0x003300, 0x333300,
        0x993300, 0x993366, 0x333399, 0x333333
    )

    private fun applyTint(c: Int, tint: Double): Int {
        if (tint == 0.0) return c
        fun ch(v: Int) = (if (tint < 0) v * (1 + tint) else v + (255 - v) * tint).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
    }

    /** Warna dari atribut elemen &lt;color&gt;/&lt;fgColor&gt;; null = otomatis. */
    private fun color(p: XmlPullParser, theme: IntArray): Int? {
        val tint = attr(p, "tint")?.toDoubleOrNull() ?: 0.0
        attr(p, "rgb")?.let { v ->
            val x = v.toLongOrNull(16) ?: return null
            val rgb = (x and 0xFFFFFF).toInt()
            return applyTint((0xFF shl 24) or rgb, tint)
        }
        attr(p, "theme")?.toIntOrNull()?.let { t -> return theme.getOrNull(t)?.let { applyTint(it, tint) } }
        attr(p, "indexed")?.toIntOrNull()?.let { i -> return INDEXED.getOrNull(i)?.let { applyTint((0xFF shl 24) or it, tint) } }
        return null
    }

    // ---------- gaya ----------

    private class Font(var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false,
                       var strike: Boolean = false, var size: Float = 0f, var color: Int = 0)

    private fun readStyles(z: ZipFile, path: String, theme: IntArray): List<CellStyle> {
        val numFmts = HashMap<Int, String>()
        val fonts = ArrayList<Font>()
        val fills = ArrayList<Int>()
        val xfs = ArrayList<CellStyle>()
        var section = ""
        var font: Font? = null
        var fillSolid = false; var fillColor = 0; var inFill = false
        var xfOpen: IntArray? = null   // numFmtId, fontId, fillId
        var xfAlign = HAlign.AUTO; var xfWrap = false
        fun finishXf() {
            val x = xfOpen ?: return
            val f = fonts.getOrNull(x[1]) ?: Font()
            val code = numFmts[x[0]]
            val nf = when {
                x[0] == 0 || x[0] == 49 -> CellNumFmt.GENERAL
                x[0] in 1..4 || x[0] in 37..40 -> if (x[0] == 1 || x[0] == 3 || x[0] == 37 || x[0] == 38) CellNumFmt.THOUSANDS else CellNumFmt.NUMBER
                x[0] == 9 || x[0] == 10 -> CellNumFmt.PERCENT
                x[0] in 14..22 || x[0] in 27..36 || x[0] in 45..47 -> CellNumFmt.DATE
                code != null -> numFmtOf(code)
                else -> CellNumFmt.GENERAL
            }
            xfs.add(CellStyle.intern(CellStyle(
                bold = f.bold, italic = f.italic, underline = f.underline, strike = f.strike, color = f.color,
                fill = fills.getOrNull(x[2]) ?: 0, align = xfAlign,
                size = if (f.size > 0f && kotlin.math.abs(f.size - 11f) > 0.01f) f.size else 0f, numFmt = nf, wrap = xfWrap
            )))
            xfOpen = null
        }
        entry(z, path)?.let { e -> z.getInputStream(e).use { ins -> pull(ins) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (val n = local(p)) {
                    "numFmt" -> { val id = attr(p, "numFmtId")?.toIntOrNull(); val c = attr(p, "formatCode"); if (id != null && c != null) numFmts[id] = c }
                    "fonts", "fills", "cellXfs", "cellStyleXfs", "borders", "dxfs" -> section = n
                    "font" -> if (section == "fonts") font = Font()
                    "b" -> font?.let { it.bold = attr(p, "val") != "0" && attr(p, "val") != "false" }
                    "i" -> font?.let { it.italic = attr(p, "val") != "0" && attr(p, "val") != "false" }
                    "u" -> font?.let { it.underline = attr(p, "val") != "none" }
                    "strike" -> font?.let { it.strike = attr(p, "val") != "0" && attr(p, "val") != "false" }
                    "sz" -> font?.let { it.size = attr(p, "val")?.toFloatOrNull() ?: 0f }
                    "color" -> font?.let { f ->
                        // hitam / warna teks tema = otomatis (tetap terbaca di tema gelap)
                        val c = color(p, theme)
                        f.color = if (c == null || attr(p, "theme") == "1" || (c and 0xFFFFFF) == 0) 0 else c
                    }
                    "fill" -> if (section == "fills") { inFill = true; fillSolid = false; fillColor = 0 }
                    "patternFill" -> if (inFill) fillSolid = attr(p, "patternType").let { it != null && it != "none" && it != "gray125" }
                    "fgColor" -> if (inFill) fillColor = color(p, theme) ?: 0
                    "xf" -> if (section == "cellXfs") {
                        xfOpen = intArrayOf(attr(p, "numFmtId")?.toIntOrNull() ?: 0, attr(p, "fontId")?.toIntOrNull() ?: 0, attr(p, "fillId")?.toIntOrNull() ?: 0)
                        xfAlign = HAlign.AUTO; xfWrap = false
                    }
                    "alignment" -> if (xfOpen != null) {
                        xfAlign = when (attr(p, "horizontal")) { "left" -> HAlign.LEFT; "center", "centerContinuous" -> HAlign.CENTER; "right" -> HAlign.RIGHT; else -> HAlign.AUTO }
                        xfWrap = attr(p, "wrapText") == "1" || attr(p, "wrapText") == "true"
                    }
                }
                XmlPullParser.END_TAG -> when (local(p)) {
                    "font" -> { font?.let { if (section == "fonts") fonts.add(it) }; font = null }
                    "fill" -> if (inFill) { fills.add(if (fillSolid) fillColor else 0); inFill = false }
                    "xf" -> if (section == "cellXfs") finishXf()
                    "cellXfs" -> return@pull false
                }
            }
            true
        } } }
        return xfs
    }

    private fun numFmtOf(code: String): Int {
        val c = code.lowercase().replace(Regex("\"[^\"]*\"|\\[[^\\]]*\\]"), { m -> if (m.value.contains("rp") || m.value.contains("$") || m.value.contains("€")) "¤" else "" })
        return when {
            c.contains('%') -> CellNumFmt.PERCENT
            c.contains('¤') || code.contains("Rp") || code.contains('$') || code.contains('€') -> CellNumFmt.CURRENCY
            Regex("[dmy]").containsMatchIn(c.replace("general", "")) && !c.contains('#') && !c.contains('0') -> CellNumFmt.DATE
            c.contains("0.00") || c.contains("0,00") -> CellNumFmt.NUMBER
            c.contains("#,##0") || c.contains("#.##0") -> CellNumFmt.THOUSANDS
            else -> CellNumFmt.GENERAL
        }
    }

    private fun readShared(z: ZipFile, path: String): ArrayList<String> {
        val out = ArrayList<String>()
        val e = entry(z, path) ?: return out
        var inSi = false; var inT = false; var inRph = false
        val sb = StringBuilder()
        z.getInputStream(e).use { ins -> pull(ins) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p)) { "si" -> { inSi = true; sb.setLength(0) }; "t" -> if (inSi && !inRph) inT = true; "rPh" -> inRph = true }
                XmlPullParser.TEXT -> if (inT) sb.append(p.text.orEmpty())
                XmlPullParser.END_TAG -> when (local(p)) { "t" -> inT = false; "rPh" -> inRph = false; "si" -> { inSi = false; out.add(sb.toString()) } }
            }
            true
        } }
        return out
    }

    // ---------- lembar ----------

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (!ch.isLetter()) break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    private fun rowIndex(ref: String): Int = ref.dropWhile { it.isLetter() || it == '$' }.toIntOrNull()?.minus(1) ?: -1

    /** Angka dari berkas (titik desimal) → teks yang dibaca editor tanpa salah tafsir ribuan/desimal. */
    private fun numberText(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        var s = java.math.BigDecimal(v.trim()).let { if (it.scale() > 0) it.stripTrailingZeros() else it }.toPlainString()
        if (s.contains('E') || s.contains('e')) s = java.math.BigDecimal(d).toPlainString()
        // "1.234" dibaca editor sebagai seribu dua ratus tiga puluh empat (gaya Indonesia) → "1.2340" tidak ambigu
        if (s.contains('.') && s.substringAfter('.').length == 3) s += "0"
        return s
    }

    private class SharedF(val r: Int, val c: Int, val text: String)

    private fun readSheet(ins: InputStream, name: String, styles: List<CellStyle>, shared: List<String>): SheetData {
        val sheet = SheetData(name, mutableListOf(), 1)
        val rows = sheet.rows
        val colW = HashMap<Int, Float>()
        val rowH = HashMap<Int, Float>()
        val sharedF = HashMap<String, SharedF>()
        var maxCol = -1
        var defRowHt = 15f

        var row = -1; var col = -1; var type = ""; var styleIdx = 0
        var inV = false; var inF = false; var inIs = false; var inT = false
        val v = StringBuilder(); val f = StringBuilder()
        var fShared: String? = null; var fRef: String? = null; var fType = ""

        fun put(r: Int, c: Int, text: String, st: CellStyle) {
            if (text.isEmpty() && st.isDefault) return
            while (rows.size <= r) rows.add(ArrayList())
            val list = rows[r]
            while (list.size < c) list.add(SheetCell(""))
            if (list.size == c) list.add(SheetCell(text, st)) else { list[c].valStr = text; list[c].style = st }
            if (c > maxCol) maxCol = c
        }

        pull(ins) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p)) {
                    "sheetFormatPr" -> attr(p, "defaultRowHeight")?.toFloatOrNull()?.let { defRowHt = it }
                    "col" -> {
                        val mn = (attr(p, "min")?.toIntOrNull() ?: 1) - 1
                        val mx = ((attr(p, "max")?.toIntOrNull() ?: (mn + 1)) - 1).coerceAtMost(mn + 4096)
                        val hidden = attr(p, "hidden") == "1" || attr(p, "hidden") == "true"
                        val w = attr(p, "width")?.toFloatOrNull()
                        val custom = attr(p, "customWidth") == "1" || attr(p, "customWidth") == "true"
                        for (c in mn..mx) {
                            if (hidden) colW[c] = 0f
                            else if (w != null && (custom || kotlin.math.abs(w - 8.43f) > 0.6f)) colW[c] = w * SheetData.DP_PER_CHAR
                        }
                    }
                    "row" -> {
                        row = attr(p, "r")?.toIntOrNull()?.minus(1) ?: (row + 1); col = -1
                        val hidden = attr(p, "hidden") == "1" || attr(p, "hidden") == "true"
                        val ht = attr(p, "ht")?.toFloatOrNull()
                        if (hidden) rowH[row] = 0f
                        else if (ht != null && (attr(p, "customHeight") == "1" || kotlin.math.abs(ht - defRowHt) > 0.6f)) rowH[row] = ht * SheetData.DP_PER_PT
                        while (rows.size <= row) rows.add(ArrayList())
                    }
                    "c" -> {
                        type = attr(p, "t").orEmpty()
                        val ref = attr(p, "r")
                        col = ref?.let { colIndex(it) } ?: (col + 1)
                        styleIdx = attr(p, "s")?.toIntOrNull() ?: 0
                        v.setLength(0); f.setLength(0); fShared = null; fRef = null; fType = ""
                    }
                    "v" -> inV = true
                    "f" -> { inF = true; fType = attr(p, "t").orEmpty(); fShared = attr(p, "si"); fRef = attr(p, "ref") }
                    "is" -> inIs = true
                    "t" -> if (inIs) inT = true
                    "mergeCell" -> attr(p, "ref")?.let { ref ->
                        val parts = ref.split(':')
                        if (parts.size == 2) {
                            val m = CellRange(rowIndex(parts[0]), colIndex(parts[0]), rowIndex(parts[1]), colIndex(parts[1])).norm()
                            if (m.top >= 0 && m.left >= 0 && !m.isSingle) sheet.merges.add(m)
                        }
                    }
                }
                XmlPullParser.TEXT -> when {
                    inF -> f.append(p.text.orEmpty())
                    inV || inT -> v.append(p.text.orEmpty())
                }
                XmlPullParser.END_TAG -> when (local(p)) {
                    "v" -> inV = false
                    "f" -> inF = false
                    "t" -> inT = false
                    "is" -> inIs = false
                    "c" -> if (row >= 0 && col >= 0) {
                        val st = styles.getOrNull(styleIdx) ?: CellStyle.DEFAULT
                        val value = when (type) {
                            "s" -> shared.getOrNull(v.toString().trim().toIntOrNull() ?: -1).orEmpty()
                            "inlineStr", "str", "e" -> v.toString()
                            "b" -> if (v.toString().trim() == "1") "TRUE" else "FALSE"
                            else -> if (v.isNotEmpty()) numberText(v.toString()) else ""
                        }
                        // rumus: teks rumus sendiri, atau rumus bersama (digeser dari sel induknya)
                        var formula: String? = null
                        if (f.isNotEmpty()) {
                            formula = "=" + f.toString()
                            if (fType == "shared" && fShared != null) sharedF[fShared!!] = SharedF(row, col, formula)
                        } else if (fType == "shared" && fShared != null) {
                            sharedF[fShared!!]?.let { m -> formula = FormulaRefs.shift(m.text, row - m.r, col - m.c) }
                        }
                        val text = formula?.takeIf { fType != "array" && FormulaEngine.supported(it) } ?: value
                        put(row, col, text, st)
                    }
                }
            }
            true
        }
        sheet.cols = (maxCol + 1).coerceAtLeast(2)
        for (m in sheet.merges) if (m.right + 1 > sheet.cols) sheet.cols = m.right + 1
        if (rows.isEmpty()) rows.add(ArrayList())
        // baris kosong di ujung bawah dibuang
        while (rows.size > 1 && rows.last().isEmpty()) rows.removeAt(rows.size - 1)
        for ((c, w) in colW) if (c < sheet.cols + 64) sheet.colW.set(c, w)
        for ((r, h) in rowH) if (r < rows.size) sheet.rowH.set(r, h)
        sheet.merges.removeAll { it.top >= rows.size }
        return sheet
    }
}
