package cloud.depup.office

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/*
 * Mesin dokumen Word (.docx) untuk editor ala Word.
 *
 * Prinsip utama: berkas asli TIDAK ditulis ulang dari nol. Dokumen dibaca sebagai token XML yang
 * menyimpan teks mentahnya; saat menyimpan, hanya bagian yang benar-benar diubah pengguna yang
 * disunting (teks, format karakter, format paragraf, paragraf/tabel/gambar baru atau yang dihapus,
 * pengaturan halaman). Semua yang lain (tema, header/footer, kotak teks, field, bookmark, komentar,
 * font tertanam, dsb.) keluar persis seperti aslinya.
 *
 * Berkas ini sengaja tanpa dependensi Android supaya bisa diuji di JVM biasa.
 */

// =============================================================================================
// Format
// =============================================================================================

/** Format karakter langsung (null = ikut gaya). Ukuran dalam setengah-poin seperti di Word. */
data class CharFmt(
    val b: Boolean? = null,
    val i: Boolean? = null,
    val u: Boolean? = null,
    val s: Boolean? = null,
    val color: Int? = null,  // ARGB; AUTO = otomatis
    val bg: Int? = null,     // ARGB stabilo/latar; NONE = tanpa stabilo (eksplisit)
    val sz: Int? = null,
    val font: String? = null,
    val va: Int? = null      // 0 normal, 1 superskrip, 2 subskrip
) {
    fun over(o: CharFmt?): CharFmt = if (o == null) this else CharFmt(
        o.b ?: b, o.i ?: i, o.u ?: u, o.s ?: s, o.color ?: color, o.bg ?: bg, o.sz ?: sz, o.font ?: font, o.va ?: va
    )

    companion object {
        const val AUTO = 0x00000001
        const val NONE = 0x00000000
        val EMPTY = CharFmt()
    }
}

/** Format paragraf langsung (null = ikut gaya). Satuan twip (1/20 pt). firstLine negatif = gantung. */
data class ParaFmt(
    val style: String? = null,
    val align: Int? = null,      // 0 kiri, 1 tengah, 2 kanan, 3 rata kiri-kanan
    val line: Int? = null,       // 240 = 1,0 baris (lineRule auto)
    val lineRule: String? = null,
    val before: Int? = null,
    val after: Int? = null,
    val left: Int? = null,
    val right: Int? = null,
    val firstLine: Int? = null,
    val numId: Int? = null,      // 0 = tanpa daftar (eksplisit)
    val ilvl: Int? = null
) {
    fun over(o: ParaFmt): ParaFmt = ParaFmt(
        o.style ?: style, o.align ?: align, o.line ?: line, if (o.line != null) o.lineRule else lineRule,
        o.before ?: before, o.after ?: after, o.left ?: left, o.right ?: right, o.firstLine ?: firstLine,
        o.numId ?: numId, if (o.numId != null) o.ilvl else ilvl
    )
}

/** Ukuran halaman & margin dalam twip. */
data class PageSetup(val w: Int, val h: Int, val landscape: Boolean, val top: Int, val right: Int, val bottom: Int, val left: Int) {
    val textWidth: Int get() = (w - left - right).coerceAtLeast(1440)
}

// =============================================================================================
// Model yang dipakai editor
// =============================================================================================

sealed class EBlock {
    abstract fun deepCopy(): EBlock
}

class ImgRef(val rId: String, val runStart: Int, val runEnd: Int, val cx: Long, val cy: Long)

class EPara(
    /** id paragraf asli; -1 = paragraf baru. */
    val origin: Int,
    var text: String,
    var fmts: Array<CharFmt>,
    var pf: ParaFmt,
    /** Paragraf asli yang menjadi acuan format untuk paragraf baru (mis. hasil Enter). */
    var tpl: Int = origin
) : EBlock() {
    var images: MutableList<ImgRef> = ArrayList()
    var hasObject = false
    var pageBreak = false
    /** Paragraf pembawa pemisah bagian (section) tidak boleh dihapus. */
    var locked = false

    override fun deepCopy(): EPara = EPara(origin, text, fmts.copyOf(), pf, tpl).also {
        it.images = ArrayList(images); it.hasObject = hasObject; it.pageBreak = pageBreak; it.locked = locked
    }

    companion object {
        fun blank(tpl: Int, pf: ParaFmt, fmt: CharFmt = CharFmt.EMPTY, text: String = "") =
            EPara(-1, text, Array(text.length) { fmt }, pf, tpl)
    }
}

class ECell(
    val origin: Int,
    val paras: MutableList<EPara>,
    val span: Int = 1,
    val vCont: Boolean = false,
    val fill: Int? = null,
    /** Sel asli acuan properti (tcPr) untuk sel baru. */
    val tplCell: Int = origin,
    /** Sel berisi tabel bersarang: hanya teks/format yang bisa diubah, bukan jumlah paragraf. */
    val locked: Boolean = false
) {
    fun deepCopy() = ECell(origin, paras.mapTo(ArrayList()) { it.deepCopy() }, span, vCont, fill, tplCell, locked)
}

class ERow(val origin: Int, val cells: MutableList<ECell>, val tplRow: Int = origin) {
    fun deepCopy() = ERow(origin, cells.mapTo(ArrayList()) { it.deepCopy() }, tplRow)
}

class ETable(
    val origin: Int,
    val rows: MutableList<ERow>,
    var grid: MutableList<Int>,
    /** Tanpa sel gabungan: baris/kolom boleh ditambah/dihapus. */
    val simple: Boolean,
    val bordered: Boolean = true
) : EBlock() {
    override fun deepCopy() = ETable(origin, rows.mapTo(ArrayList()) { it.deepCopy() }, ArrayList(grid), simple, bordered)
}

/** Gambar baru yang disisipkan pengguna (satu paragraf rata tengah). */
class EImage(val bytes: ByteArray, val ext: String, val wPx: Int, val hPx: Int, var widthFrac: Float = 0.6f) : EBlock() {
    override fun deepCopy() = EImage(bytes, ext, wPx, hPx, widthFrac)
}

/** Pemisah halaman baru. */
class EBreak : EBlock() {
    override fun deepCopy() = EBreak()
}

/** Gaya bawaan yang bisa dipilih di galeri gaya. */
enum class StdStyle(val label: String, val wordName: String) {
    NORMAL("Normal", "Normal"),
    TITLE("Judul Dokumen", "Title"),
    SUBTITLE("Subjudul", "Subtitle"),
    H1("Judul 1", "heading 1"),
    H2("Judul 2", "heading 2"),
    H3("Judul 3", "heading 3"),
    QUOTE("Kutipan", "Quote")
}

// =============================================================================================
// Dokumen
// =============================================================================================

class DocxDocument private constructor(
    private val src: File,
    private val mainPart: String,
    private val docXml: String,
    private val docCharset: java.nio.charset.Charset
) {
    val body: MutableList<EBlock> = ArrayList()
    lateinit var styles: Styles; private set
    lateinit var numbering: Numbering; private set
    var page: PageSetup? = null
    private var origPage: PageSetup? = null
    private val rels = HashMap<String, String>() // rId -> target (relatif terhadap folder part utama)
    private var stylesPart: String? = null
    private var numberingPart: String? = null
    private val mainDir get() = mainPart.substringBeforeLast('/', "")

    companion object {
        const val REL_STYLES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
        const val REL_NUMBERING = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering"
        const val REL_IMAGE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"

        /** null bila bukan .docx yang valid. */
        fun open(src: File): DocxDocument? = try {
            ZipFile(src).use { z ->
                val main = findMainPart(z)
                val bytes = z.getEntry(main)?.let { e -> z.getInputStream(e).use { it.readBytes() } } ?: return null
                val cs = charsetOf(bytes)
                val d = DocxDocument(src, main, String(bytes, cs), cs)
                d.load(z)
                d
            }
        } catch (_: Exception) { null }

        private fun findMainPart(z: ZipFile): String {
            val r = z.getEntry("_rels/.rels")?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            if (r != null) {
                for (t in XmlLite.tokenize(r)) if ((t.kind == XTok.EMPTY || t.kind == XTok.START) && XmlLite.local(t.name) == "Relationship") {
                    val type = XmlLite.attr(t.raw, "Type").orEmpty()
                    if (type.endsWith("/officeDocument")) {
                        val target = XmlLite.attr(t.raw, "Target")?.removePrefix("/") ?: continue
                        if (z.getEntry(target) != null) return target
                    }
                }
            }
            return "word/document.xml"
        }

        internal fun charsetOf(b: ByteArray): java.nio.charset.Charset = when {
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }

        internal fun resolve(dir: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = ArrayList<String>()
            if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
            for (seg in target.split('/')) when (seg) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                ".", "" -> {}
                else -> parts.add(seg)
            }
            return parts.joinToString("/")
        }
    }

    private fun readPart(z: ZipFile, name: String?): String? =
        name?.let { z.getEntry(it) }?.let { e -> z.getInputStream(e).use { val b = it.readBytes(); String(b, charsetOf(b)) } }

    private fun relsPath() = (if (mainDir.isEmpty()) "" else "$mainDir/") + "_rels/" + mainPart.substringAfterLast('/') + ".rels"

    private fun load(z: ZipFile) {
        readPart(z, relsPath())?.let { r ->
            for (t in XmlLite.tokenize(r)) if ((t.kind == XTok.EMPTY || t.kind == XTok.START) && XmlLite.local(t.name) == "Relationship") {
                val id = XmlLite.attr(t.raw, "Id") ?: continue
                val target = XmlLite.attr(t.raw, "Target") ?: continue
                val type = XmlLite.attr(t.raw, "Type").orEmpty()
                if (XmlLite.attr(t.raw, "TargetMode") == "External") continue
                rels[id] = target
                if (type == REL_STYLES) stylesPart = resolve(mainDir, target)
                if (type == REL_NUMBERING) numberingPart = resolve(mainDir, target)
            }
        }
        styles = Styles.parse(readPart(z, stylesPart))
        numbering = Numbering.parse(readPart(z, numberingPart))
        val p = Parsed(docXml)
        page = p.pageSetup()
        origPage = page
        body.addAll(p.buildModel(styles))
    }

    // ---------------------------------------------------------------------------------------
    // Layanan untuk editor
    // ---------------------------------------------------------------------------------------

    /** Byte gambar asli berdasarkan id relasi (r:embed). */
    fun imageBytes(rId: String): ByteArray? {
        val target = rels[rId] ?: return null
        val name = resolve(mainDir, target)
        return try { ZipFile(src).use { z -> z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } } } } catch (_: Exception) { null }
    }

    /** id gaya untuk gaya bawaan; bila belum ada di dokumen, definisinya akan ditambahkan saat simpan. */
    fun styleId(s: StdStyle): String? {
        if (s == StdStyle.NORMAL) return styles.defaultPara ?: styles.byName("Normal")
        return styles.byName(s.wordName) ?: styles.addStd(s)
    }

    /** Gaya paragraf efektif (gaya + format langsung). */
    fun effPara(pf: ParaFmt): ParaFmt {
        val base = styles.paraBase(pf.style)
        var e = base.over(pf)
        val nid = e.numId
        if (nid != null && nid > 0 && pf.left == null && pf.firstLine == null) {
            numbering.level(nid, e.ilvl ?: 0)?.let { l ->
                if (base.left == null) e = e.copy(left = l.left ?: e.left, firstLine = l.firstLine ?: e.firstLine)
            }
        }
        return e
    }

    /** Format karakter dasar sebuah paragraf (dari gaya), semua nilai terisi. */
    fun runBase(pf: ParaFmt): CharFmt = styles.runBase(pf.style)

    fun numIdFor(bullet: Boolean): Int = numbering.ensureOwn(bullet)

    fun isBulletList(numId: Int?): Boolean = numId != null && numId > 0 && numbering.isBullet(numId)

    /** Semua paragraf (termasuk di dalam sel tabel) dalam urutan dokumen. */
    fun allParas(): List<EPara> {
        val out = ArrayList<EPara>()
        for (b in body) when (b) {
            is EPara -> out.add(b)
            is ETable -> b.rows.forEach { r -> r.cells.forEach { c -> out.addAll(c.paras) } }
            else -> {}
        }
        return out
    }

    /** Label nomor/poin daftar untuk tiap paragraf (null bila bukan daftar). */
    fun listLabels(): Map<EPara, String> {
        val ps = allParas()
        val labels = numbering.labels(ps.map { effPara(it.pf) })
        val out = HashMap<EPara, String>()
        ps.forEachIndexed { i, p -> labels[i]?.let { out[p] = it } }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Simpan
    // ---------------------------------------------------------------------------------------

    fun save(out: File) {
        val parts = PartWriter()
        val p = Parsed(docXml)
        val pt = Patcher(p, this, parts)
        pt.apply()
        val newDoc = p.render()

        // Gaya & penomoran baru
        val stylesAdd = styles.pendingXml()
        val numAdd = numbering.pendingXml()

        val tmp = File(out.parentFile, out.name + ".part")
        try {
            ZipFile(src).use { z ->
                val existing = z.entries().toList().map { it.name }.toSet()
                val ct = readPart(z, "[Content_Types].xml") ?: ""
                var ctNew = ct
                var relsNew = readPart(z, relsPath()) ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""

                val replace = HashMap<String, ByteArray>()
                val added = LinkedHashMap<String, ByteArray>()
                replace[mainPart] = newDoc.toByteArray(docCharset)

                if (stylesAdd != null) {
                    val sp = stylesPart
                    if (sp != null && sp in existing) replace[sp] = Styles.inject(readPart(z, sp)!!, stylesAdd).toByteArray(Charsets.UTF_8)
                    else {
                        val name = (if (mainDir.isEmpty()) "" else "$mainDir/") + "styles.xml"
                        added[name] = Styles.fresh(stylesAdd).toByteArray(Charsets.UTF_8)
                        ctNew = PartWriter.addOverride(ctNew, "/$name", "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml")
                        relsNew = PartWriter.addRel(relsNew, PartWriter.freeId(relsNew, "rIdDpStyles"), REL_STYLES, "styles.xml")
                    }
                }
                if (numAdd != null) {
                    val np = numberingPart
                    if (np != null && np in existing) replace[np] = Numbering.inject(readPart(z, np)!!, numAdd).toByteArray(Charsets.UTF_8)
                    else {
                        val name = (if (mainDir.isEmpty()) "" else "$mainDir/") + "numbering.xml"
                        added[name] = Numbering.fresh(numAdd).toByteArray(Charsets.UTF_8)
                        ctNew = PartWriter.addOverride(ctNew, "/$name", "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml")
                        relsNew = PartWriter.addRel(relsNew, PartWriter.freeId(relsNew, "rIdDpNumbering"), REL_NUMBERING, "numbering.xml")
                    }
                }
                for (m in parts.media) {
                    val name = (if (mainDir.isEmpty()) "" else "$mainDir/") + "media/" + m.file
                    added[name] = m.bytes
                    relsNew = PartWriter.addRel(relsNew, m.rId, REL_IMAGE, "media/" + m.file)
                    ctNew = PartWriter.addDefault(ctNew, m.ext, if (m.ext == "png") "image/png" else "image/jpeg")
                }
                if (ctNew != ct) replace["[Content_Types].xml"] = ctNew.toByteArray(Charsets.UTF_8)
                val rp = relsPath()
                val relsOld = readPart(z, rp)
                if (relsOld == null) { if (parts.media.isNotEmpty() || stylesAdd != null || numAdd != null) added[rp] = relsNew.toByteArray(Charsets.UTF_8) }
                else if (relsNew != relsOld) replace[rp] = relsNew.toByteArray(Charsets.UTF_8)

                ZipOutputStream(FileOutputStream(tmp).buffered()).use { zo ->
                    val seen = HashSet<String>()
                    for (e in z.entries()) {
                        if (!seen.add(e.name)) continue
                        val ne = ZipEntry(e.name)
                        if (e.time > 0) ne.time = e.time
                        zo.putNextEntry(ne)
                        val rep = replace[e.name]
                        if (rep != null) zo.write(rep) else if (!e.isDirectory) z.getInputStream(e).use { it.copyTo(zo) }
                        zo.closeEntry()
                    }
                    for ((n, b) in added) if (seen.add(n)) {
                        zo.putNextEntry(ZipEntry(n)); zo.write(b); zo.closeEntry()
                    }
                }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        } catch (t: Throwable) {
            tmp.delete(); throw t
        }
    }

    internal fun pageChanged() = page != null && page != origPage

    // ---------------------------------------------------------------------------------------
    // Ekspor HTML (untuk cetak / simpan PDF)
    // ---------------------------------------------------------------------------------------

    fun toHtml(title: String, imageData: (ByteArray) -> String?): String {
        val pg = page ?: PageSetup(11906, 16838, false, 1440, 1440, 1440, 1440)
        fun mm(tw: Int) = "%.1fmm".format(java.util.Locale.US, tw / 1440.0 * 25.4)
        val labels = listLabels()
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(XmlLite.esc(title)).append("</title><style>")
            .append("@page{size:${mm(pg.w)} ${mm(pg.h)};margin:${mm(pg.top)} ${mm(pg.right)} ${mm(pg.bottom)} ${mm(pg.left)}}")
            .append("body{margin:0;color:#000}p{margin:0;white-space:pre-wrap;word-wrap:break-word}")
            .append("table{border-collapse:collapse;margin:2pt 0}td{vertical-align:top;padding:1pt 4pt}")
            .append(".pb{page-break-after:always;height:0}img{max-width:100%}</style></head><body>")
        fun para(p: EPara) {
            val e = effPara(p.pf)
            val base = runBase(p.pf)
            val css = StringBuilder()
            css.append("text-align:").append(when (e.align) { 1 -> "center"; 2 -> "right"; 3 -> "justify"; else -> "left" }).append(';')
            css.append("margin-top:${(e.before ?: 0) / 20.0}pt;margin-bottom:${(e.after ?: 0) / 20.0}pt;")
            css.append("margin-left:${(e.left ?: 0) / 20.0}pt;margin-right:${(e.right ?: 0) / 20.0}pt;text-indent:${(e.firstLine ?: 0) / 20.0}pt;")
            val line = e.line ?: 240
            if (e.lineRule == null || e.lineRule == "auto") css.append("line-height:${"%.2f".format(java.util.Locale.US, line / 240.0 * 1.17)};")
            else css.append("line-height:${line / 20.0}pt;")
            if (p.pageBreak) sb.append("<div class=\"pb\"></div>")
            sb.append("<p style=\"").append(css).append("\">")
            labels[p]?.let { sb.append("<span style=\"").append(runCss(base, base)).append("\">").append(XmlLite.esc(it)).append("&#160;</span>") }
            var k = 0
            while (k < p.text.length) {
                var j = k
                while (j < p.text.length && p.fmts[j] == p.fmts[k]) j++
                sb.append("<span style=\"").append(runCss(base, base.over(p.fmts[k]))).append("\">")
                    .append(XmlLite.esc(p.text.substring(k, j)).replace("\n", "<br>").replace("\t", "&emsp;")).append("</span>")
                k = j
            }
            if (p.text.isEmpty()) sb.append("<span style=\"").append(runCss(base, base)).append("\">&#8203;</span>")
            for (im in p.images) imageBytes(im.rId)?.let { b -> imageData(b)?.let { d ->
                sb.append("<img src=\"").append(d).append("\" style=\"width:${im.cx / 12700.0}pt\">") } }
            sb.append("</p>")
        }
        for (b in body) when (b) {
            is EPara -> para(b)
            is ETable -> {
                val total = b.grid.sum().coerceAtLeast(1)
                sb.append("<table style=\"width:${total / 20.0}pt\">")
                for (r in b.rows) {
                    sb.append("<tr>")
                    for (c in r.cells) {
                        if (c.vCont) { sb.append("<td style=\"").append(if (b.bordered) "border:0.5pt solid #000;" else "").append("\"></td>"); continue }
                        sb.append("<td colspan=\"${c.span}\" style=\"").append(if (b.bordered) "border:0.5pt solid #000;" else "")
                        c.fill?.let { sb.append("background:").append(cssColor(it)).append(';') }
                        sb.append("\">")
                        c.paras.forEach { para(it) }
                        sb.append("</td>")
                    }
                    sb.append("</tr>")
                }
                sb.append("</table>")
            }
            is EImage -> imageData(b.bytes)?.let {
                sb.append("<p style=\"text-align:center\"><img src=\"").append(it).append("\" style=\"width:${(b.widthFrac * 100).toInt()}%\"></p>")
            }
            is EBreak -> sb.append("<div class=\"pb\"></div>")
        }
        return sb.append("</body></html>").toString()
    }

    private fun cssColor(c: Int) = "#%06X".format(c and 0xFFFFFF)

    private fun runCss(base: CharFmt, f: CharFmt): String {
        val sb = StringBuilder()
        sb.append("font-size:${(f.sz ?: 22) / 2.0}pt;")
        f.font?.let { sb.append("font-family:'").append(it.replace("'", "")).append("',").append(Fonts2.generic(it)).append(';') }
        if (f.b == true) sb.append("font-weight:bold;")
        if (f.i == true) sb.append("font-style:italic;")
        val deco = listOfNotNull(if (f.u == true) "underline" else null, if (f.s == true) "line-through" else null)
        if (deco.isNotEmpty()) sb.append("text-decoration:").append(deco.joinToString(" ")).append(';')
        f.color?.let { if (it != CharFmt.AUTO) sb.append("color:").append(cssColor(it)).append(';') }
        f.bg?.let { if (it != CharFmt.NONE) sb.append("background:").append(cssColor(it)).append(';') }
        when (f.va) { 1 -> sb.append("vertical-align:super;font-size:smaller;"); 2 -> sb.append("vertical-align:sub;font-size:smaller;") }
        return sb.toString()
    }
}

/** Pemetaan nama font Word ke keluarga umum. */
object Fonts2 {
    fun generic(name: String?): String {
        val f = name?.lowercase().orEmpty()
        return when {
            listOf("courier", "consolas", "mono", "lucida console").any { f.contains(it) } -> "monospace"
            f.contains("sans") -> "sans-serif"
            listOf("times", "cambria", "georgia", "garamond", "book", "serif", "palatino", "century").any { f.contains(it) } -> "serif"
            else -> "sans-serif"
        }
    }
}

// =============================================================================================
// Tokenizer XML ringan
// =============================================================================================

internal class XTok(val kind: Int, var raw: String, val name: String) {
    companion object { const val START = 0; const val END = 1; const val EMPTY = 2; const val TEXT = 3; const val OTHER = 4 }
}

internal object XmlLite {
    fun tokenize(s: String): ArrayList<XTok> {
        val out = ArrayList<XTok>(s.length / 12 + 16)
        var i = 0
        val n = s.length
        while (i < n) {
            if (s[i] != '<') {
                val j = s.indexOf('<', i).let { if (it < 0) n else it }
                out.add(XTok(XTok.TEXT, s.substring(i, j), ""))
                i = j
                continue
            }
            val end: Int
            when {
                s.startsWith("<!--", i) -> { end = idx(s, "-->", i, n) + 3; out.add(XTok(XTok.OTHER, s.substring(i, minOf(end, n)), "")) }
                s.startsWith("<![CDATA[", i) -> { end = idx(s, "]]>", i, n) + 3; out.add(XTok(XTok.TEXT, s.substring(i, minOf(end, n)), "")) }
                s.startsWith("<?", i) -> { end = idx(s, "?>", i, n) + 2; out.add(XTok(XTok.OTHER, s.substring(i, minOf(end, n)), "")) }
                s.startsWith("<!", i) -> { end = idx(s, ">", i, n) + 1; out.add(XTok(XTok.OTHER, s.substring(i, minOf(end, n)), "")) }
                else -> {
                    var j = i + 1
                    var q = 0.toChar()
                    while (j < n) {
                        val c = s[j]
                        if (q.code != 0) { if (c == q) q = 0.toChar() }
                        else if (c == '"' || c == '\'') q = c
                        else if (c == '>') break
                        j++
                    }
                    end = minOf(j + 1, n)
                    val raw = s.substring(i, end)
                    if (raw.startsWith("</")) out.add(XTok(XTok.END, raw, raw.substring(2, raw.length - 1).trim()))
                    else {
                        var k = 1
                        while (k < raw.length && !raw[k].isWhitespace() && raw[k] != '/' && raw[k] != '>') k++
                        out.add(XTok(if (raw.endsWith("/>")) XTok.EMPTY else XTok.START, raw, raw.substring(1, k)))
                    }
                }
            }
            i = minOf(end, n)
        }
        return out
    }

    private fun idx(s: String, what: String, from: Int, n: Int): Int = s.indexOf(what, from).let { if (it < 0) n - what.length else it }

    /** Atribut tag sebagai peta berurutan (nilai masih ter-escape). */
    fun attrs(raw: String): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        val re = Regex("([A-Za-z_][\\w:.-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        val body = raw.removePrefix("<").removeSuffix(">").removeSuffix("/")
        val sp = body.indexOfFirst { it.isWhitespace() }
        if (sp < 0) return m
        for (mt in re.findAll(body.substring(sp))) m[mt.groupValues[1]] = mt.groups[3]?.value ?: mt.groups[4]?.value ?: ""
        return m
    }

    fun tag(name: String, attrs: Map<String, String>, empty: Boolean): String {
        val sb = StringBuilder("<").append(name)
        for ((k, v) in attrs) sb.append(' ').append(k).append("=\"").append(v).append('"')
        return sb.append(if (empty) "/>" else ">").toString()
    }

    fun attr(raw: String, qname: String): String? {
        var from = 0
        while (true) {
            val i = raw.indexOf(qname, from)
            if (i < 0) return null
            from = i + qname.length
            if (i == 0 || !raw[i - 1].isWhitespace()) continue
            var j = from
            while (j < raw.length && raw[j].isWhitespace()) j++
            if (j >= raw.length || raw[j] != '=') continue
            j++
            while (j < raw.length && raw[j].isWhitespace()) j++
            if (j >= raw.length) return null
            val q = raw[j]
            if (q != '"' && q != '\'') continue
            val e = raw.indexOf(q, j + 1)
            if (e < 0) return null
            return decode(raw.substring(j + 1, e))
        }
    }

    /** Atribut dengan nama lokal tertentu, apa pun prefiksnya (mis. r:embed). */
    fun attrLocal(raw: String, local: String): String? {
        val m = Regex("\\s([\\w.-]+:)?" + Regex.escape(local) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").find(raw) ?: return null
        return decode(m.groups[3]?.value ?: m.groups[4]?.value ?: "")
    }

    fun decode(s: String): String {
        if (s.startsWith("<![CDATA[")) return s.removePrefix("<![CDATA[").removeSuffix("]]>")
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi > i) {
                    val ent = s.substring(i + 1, semi)
                    val rep: String? = when {
                        ent == "lt" -> "<"; ent == "gt" -> ">"; ent == "amp" -> "&"
                        ent == "quot" -> "\""; ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> {}
            c == '\uFFFE' || c == '\uFFFF' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun local(name: String): String = name.substringAfter(':')
    fun prefix(name: String): String = if (name.indexOf(':') >= 0) name.substringBefore(':') else ""

    /** Anak langsung sebuah elemen (sebagai potongan XML mentah). */
    class Elem(val open: String, val close: String, val children: MutableList<Pair<String, String>>, val name: String)

    fun parseElem(raw: String): Elem? {
        if (raw.isBlank()) return null
        val toks = tokenize(raw)
        val first = toks.firstOrNull { it.kind == XTok.START || it.kind == XTok.EMPTY } ?: return null
        if (first.kind == XTok.EMPTY) return Elem(first.raw.removeSuffix("/>").trimEnd() + ">", "</${first.name}>", ArrayList(), first.name)
        val kids = ArrayList<Pair<String, String>>()
        var depth = 0
        var cur: StringBuilder? = null
        var curName = ""
        var close = "</${first.name}>"
        for (k in toks.indices) {
            val t = toks[k]
            if (t === first) { depth = 1; continue }
            if (depth == 0) continue
            when (t.kind) {
                XTok.START -> {
                    if (depth == 1) { cur = StringBuilder(); curName = t.name }
                    cur?.append(t.raw); depth++
                }
                XTok.EMPTY -> if (depth == 1) kids.add(t.name to t.raw) else cur?.append(t.raw)
                XTok.END -> {
                    depth--
                    if (depth == 0) { close = t.raw; continue }
                    cur?.append(t.raw)
                    if (depth == 1 && cur != null) { kids.add(curName to cur.toString()); cur = null }
                }
                else -> if (depth > 1) cur?.append(t.raw)
            }
        }
        return Elem(first.raw, close, kids, first.name)
    }
}

// =============================================================================================
// Format <-> XML
// =============================================================================================

internal object Fmt {
    fun isOff(v: String?) = v == "0" || v.equals("false", true) || v.equals("off", true) || v == "none"
    fun q(w: String, l: String) = if (w.isEmpty()) l else "$w:$l"
    fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
    fun parseHex(v: String?): Int? {
        if (v == null || v.length != 6 || v.equals("auto", true)) return null
        return v.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
    }

    val HIGHLIGHT = linkedMapOf(
        "yellow" to 0xFFFFFF00.toInt(), "green" to 0xFF00FF00.toInt(), "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(), "blue" to 0xFF0000FF.toInt(), "red" to 0xFFFF0000.toInt(),
        "darkBlue" to 0xFF000080.toInt(), "darkCyan" to 0xFF008080.toInt(), "darkGreen" to 0xFF008000.toInt(),
        "darkMagenta" to 0xFF800080.toInt(), "darkRed" to 0xFF800000.toInt(), "darkYellow" to 0xFF808000.toInt(),
        "darkGray" to 0xFF808080.toInt(), "lightGray" to 0xFFC0C0C0.toInt(), "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt()
    )

    fun jcOf(v: String?): Int? = when (v) {
        "center" -> 1; "right", "end" -> 2; "both", "distribute", "justify" -> 3; "left", "start" -> 0; else -> null
    }

    private fun num(v: String?): Int? = v?.toDoubleOrNull()?.toInt()

    /** Format langsung dari isi <w:rPr> (hanya anak langsung). */
    fun parseRPr(toks: List<XTok>, range: IntRange?, w: String): Pair<CharFmt, String?> {
        if (range == null) return CharFmt.EMPTY to null
        var f = CharFmt.EMPTY
        var rStyle: String? = null
        var depth = 0
        for (k in range) {
            val t = toks[k]
            when (t.kind) {
                XTok.START, XTok.EMPTY -> {
                    if (depth == 1 && XmlLite.prefix(t.name) == w) {
                        fun v() = XmlLite.attr(t.raw, q(w, "val"))
                        when (XmlLite.local(t.name)) {
                            "rStyle" -> rStyle = v()
                            "b" -> f = f.copy(b = !isOff(v()))
                            "i" -> f = f.copy(i = !isOff(v()))
                            "strike" -> f = f.copy(s = !isOff(v()))
                            "u" -> f = f.copy(u = v()?.let { it != "none" } ?: true)
                            "color" -> { val vv = v(); f = f.copy(color = parseHex(vv) ?: if (vv == "auto") CharFmt.AUTO else f.color) }
                            "sz" -> num(v())?.let { f = f.copy(sz = it) }
                            "rFonts" -> (XmlLite.attr(t.raw, q(w, "ascii")) ?: XmlLite.attr(t.raw, q(w, "hAnsi")))?.let { f = f.copy(font = it) }
                            "highlight" -> { val vv = v(); f = f.copy(bg = if (vv == "none") CharFmt.NONE else HIGHLIGHT[vv] ?: f.bg) }
                            "shd" -> if (f.bg == null) parseHex(XmlLite.attr(t.raw, q(w, "fill")))?.let { f = f.copy(bg = it) }
                            "vertAlign" -> f = f.copy(va = when (v()) { "superscript" -> 1; "subscript" -> 2; else -> 0 })
                        }
                    }
                    if (t.kind == XTok.START) depth++
                }
                XTok.END -> depth--
            }
        }
        return f to rStyle
    }

    /** Format paragraf langsung dari <w:pPr>. */
    fun parsePPr(toks: List<XTok>, range: IntRange?, w: String): Pair<ParaFmt, Boolean> {
        if (range == null) return ParaFmt() to false
        var f = ParaFmt()
        var sect = false
        var depth = 0
        var inNum = false
        for (k in range) {
            val t = toks[k]
            when (t.kind) {
                XTok.START, XTok.EMPTY -> {
                    val l = XmlLite.local(t.name)
                    val isW = XmlLite.prefix(t.name) == w
                    if (isW && depth == 1) {
                        fun a(n: String) = XmlLite.attr(t.raw, q(w, n))
                        when (l) {
                            "pStyle" -> f = f.copy(style = a("val"))
                            "jc" -> f = f.copy(align = jcOf(a("val")))
                            "spacing" -> f = f.copy(
                                before = num(a("before")) ?: f.before, after = num(a("after")) ?: f.after,
                                line = num(a("line")) ?: f.line, lineRule = if (a("line") != null) (a("lineRule") ?: "auto") else f.lineRule
                            )
                            "ind" -> {
                                val left = num(a("left") ?: a("start"))
                                val right = num(a("right") ?: a("end"))
                                val first = num(a("firstLine"))
                                val hang = num(a("hanging"))
                                f = f.copy(left = left ?: f.left, right = right ?: f.right, firstLine = hang?.let { -it } ?: first ?: f.firstLine)
                            }
                            "sectPr" -> sect = true
                            "numPr" -> { inNum = t.kind == XTok.START; if (!inNum) f = f.copy(numId = null) }
                        }
                    } else if (isW && depth == 2 && inNum) {
                        when (l) {
                            "numId" -> f = f.copy(numId = num(XmlLite.attr(t.raw, q(w, "val"))) ?: 0)
                            "ilvl" -> f = f.copy(ilvl = num(XmlLite.attr(t.raw, q(w, "val"))))
                        }
                    }
                    if (t.kind == XTok.START) depth++
                }
                XTok.END -> { depth--; if (depth == 1) inNum = false }
            }
        }
        if (f.numId != null && f.ilvl == null) f = f.copy(ilvl = 0)
        return f to sect
    }

    private val RPR_ORDER = listOf(
        "rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline", "shadow",
        "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern",
        "position", "sz", "szCs", "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign", "rtl", "cs",
        "em", "lang", "eastAsianLayout", "specVanish", "oMath"
    )
    private val PPR_ORDER = listOf(
        "pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr",
        "suppressLineNumbers", "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct",
        "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind",
        "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc", "textDirection", "textAlignment",
        "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr"
    )

    private fun order(list: List<String>, w: String, name: String): Int {
        val l = XmlLite.local(name)
        if (XmlLite.prefix(name) != w) return 900
        if (l == "rPrChange" || l == "pPrChange") return 1000
        val i = list.indexOf(l)
        return if (i < 0) 500 else i
    }

    private fun emit(e: XmlLite.Elem, list: List<String>, w: String): String {
        if (e.children.isEmpty()) return ""
        val sorted = e.children.withIndex().sortedWith(compareBy({ order(list, w, it.value.first) }, { it.index })).map { it.value }
        val sb = StringBuilder(e.open)
        sorted.forEach { sb.append(it.second) }
        return sb.append(e.close).toString()
    }

    private fun set(e: XmlLite.Elem, w: String, l: String, xml: String?) {
        e.children.removeAll { it.first == q(w, l) }
        if (xml != null) e.children.add(q(w, l) to xml)
    }

    private fun getRaw(e: XmlLite.Elem, w: String, l: String) = e.children.firstOrNull { it.first == q(w, l) }?.second

    /** Ubah properti yang dikelola dari [from] menjadi [to]; properti lain dibiarkan apa adanya. */
    fun rprWith(raw: String, from: CharFmt, to: CharFmt, w: String): String {
        if (from == to) return raw
        val e = XmlLite.parseElem(raw) ?: XmlLite.Elem("<${q(w, "rPr")}>", "</${q(w, "rPr")}>", ArrayList(), q(w, "rPr"))
        fun tg(l: String, v: String?) = if (v == null) "<${q(w, l)}/>" else "<${q(w, l)} ${q(w, "val")}=\"$v\"/>"
        if (from.b != to.b) { set(e, w, "b", to.b?.let { if (it) tg("b", null) else tg("b", "0") }); set(e, w, "bCs", to.b?.let { if (it) tg("bCs", null) else tg("bCs", "0") }) }
        if (from.i != to.i) { set(e, w, "i", to.i?.let { if (it) tg("i", null) else tg("i", "0") }); set(e, w, "iCs", to.i?.let { if (it) tg("iCs", null) else tg("iCs", "0") }) }
        if (from.u != to.u) set(e, w, "u", to.u?.let { tg("u", if (it) "single" else "none") })
        if (from.s != to.s) set(e, w, "strike", to.s?.let { if (it) tg("strike", null) else tg("strike", "0") })
        if (from.color != to.color) set(e, w, "color", to.color?.let { tg("color", if (it == CharFmt.AUTO) "auto" else hex(it)) })
        if (from.sz != to.sz) { set(e, w, "sz", to.sz?.let { tg("sz", "$it") }); set(e, w, "szCs", to.sz?.let { tg("szCs", "$it") }) }
        if (from.va != to.va) set(e, w, "vertAlign", to.va?.let { tg("vertAlign", when (it) { 1 -> "superscript"; 2 -> "subscript"; else -> "baseline" }) })
        if (from.bg != to.bg) {
            set(e, w, "highlight", null); set(e, w, "shd", null)
            to.bg?.let { c ->
                val name = if (c == CharFmt.NONE) "none" else HIGHLIGHT.entries.firstOrNull { it.value == c }?.key
                if (name != null) set(e, w, "highlight", tg("highlight", name))
                else set(e, w, "shd", "<${q(w, "shd")} ${q(w, "val")}=\"clear\" ${q(w, "color")}=\"auto\" ${q(w, "fill")}=\"${hex(c)}\"/>")
            }
        }
        if (from.font != to.font) {
            val old = getRaw(e, w, "rFonts")
            val at = old?.let { XmlLite.attrs(it) } ?: LinkedHashMap()
            listOf("asciiTheme", "hAnsiTheme", "cstheme").forEach { at.remove(q(w, it)) }
            if (to.font == null) listOf("ascii", "hAnsi", "cs").forEach { at.remove(q(w, it)) }
            else {
                val v = XmlLite.esc(to.font)
                at[q(w, "ascii")] = v; at[q(w, "hAnsi")] = v; at[q(w, "cs")] = v
            }
            set(e, w, "rFonts", if (at.isEmpty()) null else XmlLite.tag(q(w, "rFonts"), at, true))
        }
        return emit(e, RPR_ORDER, w)
    }

    fun pprWith(raw: String, from: ParaFmt, to: ParaFmt, w: String): String {
        if (from == to) return raw
        val e = XmlLite.parseElem(raw) ?: XmlLite.Elem("<${q(w, "pPr")}>", "</${q(w, "pPr")}>", ArrayList(), q(w, "pPr"))
        if (from.style != to.style) set(e, w, "pStyle", to.style?.let { "<${q(w, "pStyle")} ${q(w, "val")}=\"${XmlLite.esc(it)}\"/>" })
        if (from.numId != to.numId || from.ilvl != to.ilvl) set(e, w, "numPr", to.numId?.let {
            "<${q(w, "numPr")}><${q(w, "ilvl")} ${q(w, "val")}=\"${to.ilvl ?: 0}\"/><${q(w, "numId")} ${q(w, "val")}=\"$it\"/></${q(w, "numPr")}>"
        })
        if (from.align != to.align) set(e, w, "jc", to.align?.let {
            "<${q(w, "jc")} ${q(w, "val")}=\"${when (it) { 1 -> "center"; 2 -> "right"; 3 -> "both"; else -> "left" }}\"/>"
        })
        if (from.before != to.before || from.after != to.after || from.line != to.line || from.lineRule != to.lineRule) {
            val at = getRaw(e, w, "spacing")?.let { XmlLite.attrs(it) } ?: LinkedHashMap()
            fun put(k: String, v: Any?) { if (v == null) at.remove(q(w, k)) else at[q(w, k)] = v.toString() }
            if (from.before != to.before) { put("before", to.before); at.remove(q(w, "beforeAutospacing")); at.remove(q(w, "beforeLines")) }
            if (from.after != to.after) { put("after", to.after); at.remove(q(w, "afterAutospacing")); at.remove(q(w, "afterLines")) }
            if (from.line != to.line || from.lineRule != to.lineRule) { put("line", to.line); put("lineRule", if (to.line == null) null else (to.lineRule ?: "auto")) }
            set(e, w, "spacing", if (at.isEmpty()) null else XmlLite.tag(q(w, "spacing"), at, true))
        }
        if (from.left != to.left || from.right != to.right || from.firstLine != to.firstLine) {
            val at = getRaw(e, w, "ind")?.let { XmlLite.attrs(it) } ?: LinkedHashMap()
            val lk = if (at.containsKey(q(w, "start"))) "start" else "left"
            val rk = if (at.containsKey(q(w, "end"))) "end" else "right"
            if (from.left != to.left) { if (to.left == null) at.remove(q(w, lk)) else at[q(w, lk)] = "${to.left}"; at.remove(q(w, "leftChars")); at.remove(q(w, "startChars")) }
            if (from.right != to.right) { if (to.right == null) at.remove(q(w, rk)) else at[q(w, rk)] = "${to.right}"; at.remove(q(w, "rightChars")); at.remove(q(w, "endChars")) }
            if (from.firstLine != to.firstLine) {
                listOf("firstLine", "hanging", "firstLineChars", "hangingChars").forEach { at.remove(q(w, it)) }
                to.firstLine?.let { if (it < 0) at[q(w, "hanging")] = "${-it}" else at[q(w, "firstLine")] = "$it" }
            }
            set(e, w, "ind", if (at.isEmpty()) null else XmlLite.tag(q(w, "ind"), at, true))
        }
        return emit(e, PPR_ORDER, w)
    }
}

// =============================================================================================
// Gaya (styles.xml)
// =============================================================================================

class Styles private constructor(private val w: String) {
    internal class Def(val id: String, val name: String, val type: String, val basedOn: String?, val rp: CharFmt, val pf: ParaFmt)

    private val defs = LinkedHashMap<String, Def>()
    private var defaults = CharFmt.EMPTY
    private var defaultsPf = ParaFmt()
    var defaultPara: String? = null; private set
    private val pending = LinkedHashMap<String, String>() // id -> XML

    companion object {
        private val HARD = CharFmt(false, false, false, false, CharFmt.AUTO, CharFmt.NONE, 20, null, 0)

        fun parse(xml: String?): Styles {
            if (xml == null) return Styles("w")
            val toks = XmlLite.tokenize(xml)
            val w = mainPrefix(toks)
            val st = Styles(w)
            val stack = ArrayList<Int>()
            var curStart = -1
            for (i in toks.indices) {
                val t = toks[i]
                when (t.kind) {
                    XTok.START -> {
                        stack.add(i)
                        if (XmlLite.local(t.name) == "style") curStart = i
                    }
                    XTok.END -> {
                        val s = stack.removeLastOrNull() ?: continue
                        val l = XmlLite.local(t.name)
                        val par = stack.lastOrNull()?.let { XmlLite.local(toks[it].name) }
                        if (l == "rPr" && par == "rPrDefault") st.defaults = Fmt.parseRPr(toks, s..i, w).first
                        if (l == "pPr" && par == "pPrDefault") st.defaultsPf = Fmt.parsePPr(toks, s..i, w).first
                        if (l == "style" && curStart >= 0) { st.readStyle(toks, curStart..i); curStart = -1 }
                    }
                    XTok.EMPTY -> if (XmlLite.local(t.name) == "style") st.readStyle(toks, i..i)
                }
            }
            return st
        }

        /** Sisipkan definisi gaya baru sebelum </w:styles>. */
        fun inject(xml: String, add: String): String {
            val toks = XmlLite.tokenize(xml)
            val w = mainPrefix(toks)
            val end = toks.indexOfLast { it.kind == XTok.END && XmlLite.local(it.name) == "styles" }
            if (end < 0) return xml
            val sb = StringBuilder()
            toks.forEachIndexed { i, t -> if (i == end) sb.append(add.replace("w:", if (w.isEmpty()) "" else "$w:")); sb.append(t.raw) }
            return sb.toString()
        }

        fun fresh(add: String): String =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault>" +
                "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
                "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
                add + "</w:styles>"
    }

    private fun readStyle(toks: List<XTok>, r: IntRange) {
        val open = toks[r.first]
        val type = XmlLite.attr(open.raw, Fmt.q(w, "type")) ?: "paragraph"
        val id = XmlLite.attr(open.raw, Fmt.q(w, "styleId")) ?: return
        val isDefault = XmlLite.attr(open.raw, Fmt.q(w, "default")).let { it == "1" || it == "true" }
        var name = id; var based: String? = null
        var rp = CharFmt.EMPTY; var pf = ParaFmt()
        var depth = 0
        var sub = -1
        for (k in r) {
            val t = toks[k]
            when (t.kind) {
                XTok.START, XTok.EMPTY -> {
                    if (depth == 1) {
                        when (XmlLite.local(t.name)) {
                            "name" -> name = XmlLite.attr(t.raw, Fmt.q(w, "val")) ?: name
                            "basedOn" -> based = XmlLite.attr(t.raw, Fmt.q(w, "val"))
                        }
                        if (t.kind == XTok.START) sub = k
                    }
                    if (t.kind == XTok.START) depth++
                }
                XTok.END -> {
                    depth--
                    if (depth == 1 && sub >= 0) {
                        when (XmlLite.local(t.name)) {
                            "rPr" -> rp = Fmt.parseRPr(toks, sub..k, w).first
                            "pPr" -> pf = Fmt.parsePPr(toks, sub..k, w).first
                        }
                        sub = -1
                    }
                }
            }
        }
        defs[id] = Def(id, name, type, based, rp, pf)
        if (isDefault && type == "paragraph") defaultPara = id
    }

    fun byName(name: String): String? =
        defs.values.firstOrNull { it.type == "paragraph" && it.name.equals(name, true) }?.id
            ?: defs.values.firstOrNull { it.type == "paragraph" && it.id.equals(name.replace(" ", ""), true) }?.id

    fun name(id: String?): String = defs[id ?: defaultPara ?: ""]?.name ?: id ?: "Normal"

    private fun chain(id: String?): List<Def> {
        val out = ArrayList<Def>()
        var cur = id ?: defaultPara
        var guard = 0
        while (cur != null && guard++ < 20) {
            val d = defs[cur] ?: break
            out.add(0, d)
            cur = d.basedOn
        }
        return out
    }

    /** Format karakter dasar untuk paragraf bergaya [styleId] (semua nilai terisi). */
    fun runBase(styleId: String?): CharFmt {
        var f = HARD.over(defaults)
        for (d in chain(styleId)) f = f.over(d.rp)
        return f
    }

    fun paraBase(styleId: String?): ParaFmt {
        var f = ParaFmt(align = 0, line = 240, lineRule = "auto", before = 0, after = 0).over(defaultsPf)
        for (d in chain(styleId)) f = f.over(d.pf)
        return f.copy(style = styleId)
    }

    /** Format dari gaya karakter (rStyle). */
    fun charStyle(id: String?): CharFmt {
        if (id == null) return CharFmt.EMPTY
        var f = CharFmt.EMPTY
        for (d in chain(id)) f = f.over(d.rp)
        return f
    }

    fun headingLevel(id: String?): Int {
        for (d in chain(id).asReversed()) {
            val n = d.name.lowercase()
            if (n == "title") return 1
            if (n.startsWith("heading")) return n.removePrefix("heading").trim().toIntOrNull() ?: 1
        }
        return 0
    }

    internal fun addStd(s: StdStyle): String {
        val id = when (s) {
            StdStyle.TITLE -> "Title"; StdStyle.SUBTITLE -> "Subtitle"; StdStyle.H1 -> "Heading1"
            StdStyle.H2 -> "Heading2"; StdStyle.H3 -> "Heading3"; StdStyle.QUOTE -> "Quote"; StdStyle.NORMAL -> "Normal"
        }.let { base -> var c = base; var n = 1; while (defs.containsKey(c) && defs[c]?.name?.equals(s.wordName, true) != true) c = base + "Dp" + n++; c }
        if (defs.containsKey(id)) return id
        val basedOn = defaultPara
        val (rp, pf, ppr, rpr) = when (s) {
            StdStyle.TITLE -> Quad(CharFmt(sz = 52, color = 0xFF17365D.toInt()), ParaFmt(after = 300),
                "<w:spacing w:after=\"300\"/><w:contextualSpacing/>", "<w:color w:val=\"17365D\"/><w:kern w:val=\"28\"/><w:sz w:val=\"52\"/><w:szCs w:val=\"52\"/>")
            StdStyle.SUBTITLE -> Quad(CharFmt(i = true, sz = 26, color = 0xFF5A5A5A.toInt()), ParaFmt(after = 160),
                "<w:spacing w:after=\"160\"/>", "<w:i/><w:color w:val=\"5A5A5A\"/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/>")
            StdStyle.H1 -> Quad(CharFmt(b = true, sz = 32, color = 0xFF2F5496.toInt()), ParaFmt(before = 240, after = 80),
                "<w:keepNext/><w:keepLines/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"0\"/>", "<w:b/><w:bCs/><w:color w:val=\"2F5496\"/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/>")
            StdStyle.H2 -> Quad(CharFmt(b = true, sz = 26, color = 0xFF2F5496.toInt()), ParaFmt(before = 160, after = 60),
                "<w:keepNext/><w:keepLines/><w:spacing w:before=\"160\" w:after=\"60\"/><w:outlineLvl w:val=\"1\"/>", "<w:b/><w:bCs/><w:color w:val=\"2F5496\"/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/>")
            StdStyle.H3 -> Quad(CharFmt(b = true, sz = 24, color = 0xFF1F3763.toInt()), ParaFmt(before = 120, after = 40),
                "<w:keepNext/><w:keepLines/><w:spacing w:before=\"120\" w:after=\"40\"/><w:outlineLvl w:val=\"2\"/>", "<w:b/><w:bCs/><w:color w:val=\"1F3763\"/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/>")
            StdStyle.QUOTE -> Quad(CharFmt(i = true, color = 0xFF404040.toInt()), ParaFmt(before = 120, after = 120, left = 864, right = 864, align = 1),
                "<w:spacing w:before=\"120\" w:after=\"120\"/><w:ind w:left=\"864\" w:right=\"864\"/><w:jc w:val=\"center\"/>", "<w:i/><w:iCs/><w:color w:val=\"404040\"/>")
            StdStyle.NORMAL -> Quad(CharFmt.EMPTY, ParaFmt(), "", "")
        }
        defs[id] = Def(id, s.wordName, "paragraph", basedOn, rp, pf)
        pending[id] = "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"${s.wordName}\"/>" +
            (if (basedOn != null) "<w:basedOn w:val=\"${XmlLite.esc(basedOn)}\"/><w:next w:val=\"${XmlLite.esc(basedOn)}\"/>" else "") +
            "<w:uiPriority w:val=\"9\"/><w:qFormat/><w:pPr>$ppr</w:pPr><w:rPr>$rpr</w:rPr></w:style>"
        return id
    }

    private data class Quad(val a: CharFmt, val b: ParaFmt, val c: String, val d: String)

    /** XML gaya yang perlu ditambahkan, hanya untuk gaya yang benar-benar dipakai. */
    internal var used: Set<String> = emptySet()
    internal fun pendingXml(): String? {
        val x = pending.filterKeys { it in used }.values.joinToString("")
        return x.ifEmpty { null }
    }
}

// =============================================================================================
// Penomoran / daftar (numbering.xml)
// =============================================================================================

class Numbering private constructor() {
    class Lvl(val fmt: String, val text: String, val start: Int, val left: Int?, val firstLine: Int?)

    private val abstracts = HashMap<Int, HashMap<Int, Lvl>>()
    private val nums = HashMap<Int, Int>()
    private var maxAbs = 0
    private var maxNum = 0
    private var ownBullet = -1
    private var ownDecimal = -1
    internal var used: Set<Int> = emptySet()

    companion object {
        fun parse(xml: String?): Numbering {
            val n = Numbering()
            if (xml == null) return n
            val toks = XmlLite.tokenize(xml)
            val w = mainPrefix(toks)
            var curAbs = -1; var curNum = -1; var lvl = -1
            var fmt = "decimal"; var text = "%1."; var start = 1; var left: Int? = null; var first: Int? = null
            for (t in toks) {
                if (t.kind != XTok.START && t.kind != XTok.EMPTY && t.kind != XTok.END) continue
                val l = XmlLite.local(t.name)
                fun v(a: String = "val") = XmlLite.attr(t.raw, Fmt.q(w, a))
                if (t.kind == XTok.END) {
                    if (l == "lvl" && curAbs >= 0 && lvl >= 0) n.abstracts.getOrPut(curAbs) { HashMap() }[lvl] = Lvl(fmt, text, start, left, first)
                    if (l == "abstractNum") curAbs = -1
                    if (l == "num") curNum = -1
                    continue
                }
                when (l) {
                    "abstractNum" -> { curAbs = v("abstractNumId")?.toIntOrNull() ?: -1; n.maxAbs = maxOf(n.maxAbs, curAbs) }
                    "lvl" -> { lvl = v("ilvl")?.toIntOrNull() ?: 0; fmt = "decimal"; text = "%${lvl + 1}."; start = 1; left = null; first = null }
                    "numFmt" -> fmt = v() ?: fmt
                    "lvlText" -> text = v() ?: ""
                    "start" -> start = v()?.toIntOrNull() ?: 1
                    "ind" -> {
                        left = (v("left") ?: v("start"))?.toDoubleOrNull()?.toInt()
                        first = v("hanging")?.toDoubleOrNull()?.toInt()?.let { -it } ?: v("firstLine")?.toDoubleOrNull()?.toInt()
                    }
                    "num" -> { curNum = v("numId")?.toIntOrNull() ?: -1; n.maxNum = maxOf(n.maxNum, curNum) }
                    "abstractNumId" -> if (curNum >= 0) v()?.toIntOrNull()?.let { n.nums[curNum] = it }
                }
            }
            return n
        }

        fun inject(xml: String, add: Pair<String, String>): String {
            val toks = XmlLite.tokenize(xml)
            val w = mainPrefix(toks)
            fun fix(s: String) = s.replace("w:", if (w.isEmpty()) "" else "$w:")
            val firstNum = toks.indexOfFirst { (it.kind == XTok.START || it.kind == XTok.EMPTY) && XmlLite.local(it.name) == "num" && XmlLite.prefix(it.name) == w }
            val end = toks.indexOfLast { it.kind == XTok.END && XmlLite.local(it.name) == "numbering" }
            if (end < 0) return xml
            val absAt = if (firstNum >= 0) firstNum else end
            val sb = StringBuilder()
            toks.forEachIndexed { i, t ->
                if (i == absAt) sb.append(fix(add.first))
                if (i == end) sb.append(fix(add.second))
                sb.append(t.raw)
            }
            return sb.toString()
        }

        fun fresh(add: Pair<String, String>) =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                add.first + add.second + "</w:numbering>"

        private val BULLETS = listOf("•", "◦", "▪")
        private val DEC = listOf("decimal" to "%1.", "lowerLetter" to "%2.", "lowerRoman" to "%3.")
    }

    fun level(numId: Int, ilvl: Int): Lvl? = nums[numId]?.let { abstracts[it]?.get(ilvl) }

    fun isBullet(numId: Int): Boolean = level(numId, 0)?.fmt == "bullet"

    internal fun ensureOwn(bullet: Boolean): Int {
        if (bullet && ownBullet > 0) return ownBullet
        if (!bullet && ownDecimal > 0) return ownDecimal
        val abs = ++maxAbs + 900
        val num = ++maxNum
        maxAbs = abs
        val m = HashMap<Int, Lvl>()
        for (l in 0..8) m[l] = if (bullet) Lvl("bullet", BULLETS[l % 3], 1, 720 * (l + 1), -360)
        else Lvl(DEC[l % 3].first, "%${l + 1}.", 1, 720 * (l + 1), -360)
        abstracts[abs] = m
        nums[num] = abs
        if (bullet) ownBullet = num else ownDecimal = num
        ownAbs[num] = abs
        return num
    }

    private val ownAbs = HashMap<Int, Int>()

    internal fun pendingXml(): Pair<String, String>? {
        val ids = ownAbs.keys.filter { it in used }
        if (ids.isEmpty()) return null
        val a = StringBuilder(); val n = StringBuilder()
        for (num in ids) {
            val abs = ownAbs.getValue(num)
            a.append("<w:abstractNum w:abstractNumId=\"$abs\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
            for ((l, lv) in abstracts.getValue(abs).toSortedMap()) {
                a.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"${lv.fmt}\"/><w:lvlText w:val=\"${XmlLite.esc(lv.text)}\"/>")
                    .append("<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${lv.left}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
            }
            a.append("</w:abstractNum>")
            n.append("<w:num w:numId=\"$num\"><w:abstractNumId w:val=\"$abs\"/></w:num>")
        }
        return a.toString() to n.toString()
    }

    /** Label tiap paragraf (null = bukan daftar). Penghitung per numId, direset untuk level lebih dalam. */
    fun labels(eff: List<ParaFmt>): List<String?> {
        val counters = HashMap<Int, IntArray>()
        return eff.map { pf ->
            val id = pf.numId ?: return@map null
            if (id <= 0) return@map null
            val lv = (pf.ilvl ?: 0).coerceIn(0, 8)
            val l = level(id, lv) ?: return@map null
            val abs = nums[id] ?: return@map null
            val c = counters.getOrPut(abs) { IntArray(9) { -1 } }
            for (k in 0 until lv) if (c[k] < 0) c[k] = (level(id, k)?.start ?: 1)
            c[lv] = if (c[lv] < 0) l.start else c[lv] + 1
            for (k in lv + 1 until 9) c[k] = -1
            if (l.fmt == "bullet") return@map bulletChar(l.text)
            if (l.fmt == "none") return@map ""
            var s = l.text
            for (k in 0..lv) {
                val lk = level(id, k) ?: continue
                s = s.replace("%${k + 1}", fmtNum(c[k].coerceAtLeast(lk.start), lk.fmt))
            }
            s
        }
    }

    private fun bulletChar(t: String): String {
        val c = t.firstOrNull() ?: return "•"
        return when {
            c.code in 0xF000..0xF0FF -> if (c.code == 0xF0A7) "▪" else "•"
            c == 'o' -> "◦"
            c == '§' -> "▪"
            else -> t
        }
    }

    private fun fmtNum(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> letters(n).lowercase()
        "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "decimalZero" -> if (n < 10) "0$n" else "$n"
        else -> "$n"
    }

    private fun letters(n: Int): String { if (n <= 0) return "$n"; val ch = 'A' + (n - 1) % 26; return ch.toString().repeat((n - 1) / 26 + 1) }

    private fun roman(n: Int): String {
        if (n <= 0 || n > 3999) return "$n"
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n; val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }
}

internal fun mainPrefix(toks: List<XTok>): String {
    val root = toks.firstOrNull { it.kind == XTok.START || it.kind == XTok.EMPTY } ?: return "w"
    val ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    val m = Regex("xmlns(?::([A-Za-z0-9_.-]+))?\\s*=\\s*[\"']" + Regex.escape(ns) + "[\"']").find(root.raw)
    return m?.groupValues?.get(1) ?: XmlLite.prefix(root.name).ifEmpty { "w" }
}

// =============================================================================================
// Bagian-bagian paket tambahan (gambar baru, relasi, content types)
// =============================================================================================

internal class PartWriter {
    class Media(val rId: String, val file: String, val ext: String, val bytes: ByteArray)
    val media = ArrayList<Media>()

    companion object {
        fun freeId(rels: String, base: String): String {
            var id = base; var n = 1
            while (rels.contains("Id=\"$id\"")) id = base + n++
            return id
        }

        fun addRel(rels: String, id: String, type: String, target: String): String {
            val end = rels.lastIndexOf("</Relationships>")
            val r = "<Relationship Id=\"$id\" Type=\"$type\" Target=\"$target\"/>"
            if (end < 0) {
                val selfClose = Regex("<Relationships([^>]*)/>").find(rels)
                return if (selfClose != null) rels.replaceRange(selfClose.range, "<Relationships${selfClose.groupValues[1]}>$r</Relationships>") else rels
            }
            return rels.substring(0, end) + r + rels.substring(end)
        }

        fun addOverride(ct: String, part: String, type: String): String {
            if (ct.contains("PartName=\"$part\"")) return ct
            val end = ct.lastIndexOf("</Types>")
            return if (end < 0) ct else ct.substring(0, end) + "<Override PartName=\"$part\" ContentType=\"$type\"/>" + ct.substring(end)
        }

        fun addDefault(ct: String, ext: String, type: String): String {
            if (Regex("Extension=\"(?i:$ext)\"").containsMatchIn(ct)) return ct
            val end = ct.lastIndexOf("</Types>")
            return if (end < 0) ct else ct.substring(0, end) + "<Default Extension=\"$ext\" ContentType=\"$type\"/>" + ct.substring(end)
        }
    }
}

// =============================================================================================
// Parsing struktur document.xml
// =============================================================================================

internal class Atom(val tok: Int, val off: Int, val run: Int)

internal class RInfo(val start: Int) {
    var end = start
    var rPr: IntRange? = null
    var direct = CharFmt.EMPTY
    var rStyle: String? = null
    var merged = CharFmt.EMPTY
    var hasDrawing = false
    val blips = ArrayList<String>()
    var cx = 0L; var cy = 0L
}

internal class PInfo(val id: Int, val start: Int, val emptyTag: Boolean) {
    var end = start
    var editable = true
    var bodyLevel = false
    val atoms = ArrayList<Atom>()
    val text = StringBuilder()
    var pPr: IntRange? = null
    var direct = ParaFmt()
    var hasSect = false
    var markRPr: IntRange? = null
    var firstRun = -1
    var anyRun = -1
    var hasObject = false
    var pageBreak = false
    val images = ArrayList<ImgRef>()
    var cell = -1
}

internal class CInfo(val id: Int, val start: Int) {
    var end = start
    var tcPr: IntRange? = null
    var tcW = -1
    var span = 1
    var vCont = false
    var hasVMerge = false
    var fill: Int? = null
    var nested = false
    val paras = ArrayList<Int>()
}

internal class RowInfo(val id: Int, val start: Int) {
    var end = start
    var trPr: IntRange? = null
    val cells = ArrayList<Int>()
}

internal class TInfo(val id: Int, val start: Int) {
    var end = start
    val gridToks = ArrayList<Int>()
    val grid = ArrayList<Int>()
    var gridEnd = -1
    val rows = ArrayList<Int>()
    var bordered = false
}

internal class SectInfo { var pgSz = -1; var pgMar = -1 }

internal class Parsed(xml: String) {
    val toks = XmlLite.tokenize(xml)
    val w = mainPrefix(toks)
    val paras = ArrayList<PInfo>()
    val runs = ArrayList<RInfo>()
    val cells = ArrayList<CInfo>()
    val rowsI = ArrayList<RowInfo>()
    val tables = ArrayList<TInfo>()
    val sects = ArrayList<SectInfo>()
    val order = ArrayList<Any>() // PInfo / TInfo tingkat body
    var bodySectPr = -1
    var bodyEnd = -1
    val tOwner = HashMap<Int, Int>() // TEXT -> token <w:t>
    val tRun = HashMap<Int, Int>()   // TEXT -> run

    init { walk() }

    private fun walk() {
        val stack = ArrayList<String>()
        val pStack = ArrayList<PInfo>()
        val runStack = ArrayList<Int>()
        var skip = 0
        var tblDepth = 0
        var table: TInfo? = null
        var row: RowInfo? = null
        var cell: CInfo? = null
        var curT = -1
        val rPrStarts = ArrayList<Int>()
        var pPrStart = -1
        var tcPrStart = -1
        var trPrStart = -1
        var sect: SectInfo? = null

        fun parentL(n: Int = 1): String? = stack.getOrNull(stack.size - n)?.let { if (XmlLite.prefix(it) == w) XmlLite.local(it) else "~" + XmlLite.local(it) }

        fun newP(i: Int, emptyTag: Boolean): PInfo {
            val p = PInfo(paras.size, i, emptyTag)
            p.editable = skip == 0
            paras.add(p)
            if (p.editable) {
                val c = cell
                if (tblDepth >= 1 && c != null) {
                    c.paras.add(p.id); p.cell = c.id
                    if (tblDepth >= 2) c.nested = true
                } else if (tblDepth == 0 && pStack.isEmpty()) { p.bodyLevel = true; order.add(p) }
            }
            return p
        }

        fun addAtom(p: PInfo, a: Atom, ch: Char) {
            if (p.firstRun < 0) p.firstRun = a.run
            p.atoms.add(a); p.text.append(ch)
        }

        for (i in toks.indices) {
            val t = toks[i]
            when (t.kind) {
                XTok.START, XTok.EMPTY -> {
                    val isWn = XmlLite.prefix(t.name) == w
                    val l = XmlLite.local(t.name)
                    val par = parentL(1)
                    val p = pStack.lastOrNull()
                    val start = t.kind == XTok.START
                    if (isWn) when (l) {
                        "p" -> { val np = newP(i, !start); if (start) pStack.add(np) }
                        "r" -> if (start) { runs.add(RInfo(i)); runStack.add(runs.lastIndex) }
                        "rPr" -> if (start) rPrStarts.add(i) else {
                            if (par == "r") runStack.lastOrNull()?.let { runs[it].rPr = i..i }
                            if (par == "pPr" && p != null) p.markRPr = i..i
                        }
                        "pPr" -> if (par == "p" && p != null) { if (start) pPrStart = i else p.pPr = i..i }
                        "t" -> if (par == "r" && start) curT = i
                        "tab" -> if (par == "r" && p != null) runStack.lastOrNull()?.let { addAtom(p, Atom(i, -1, it), '\t') }
                        "cr" -> if (par == "r" && p != null) runStack.lastOrNull()?.let { addAtom(p, Atom(i, -1, it), '\n') }
                        "noBreakHyphen" -> if (par == "r" && p != null) runStack.lastOrNull()?.let { addAtom(p, Atom(i, -1, it), '-') }
                        "br" -> if (par == "r" && p != null) {
                            val type = XmlLite.attr(t.raw, Fmt.q(w, "type"))
                            if (type == "page" || type == "column") p.pageBreak = true
                            else runStack.lastOrNull()?.let { addAtom(p, Atom(i, -1, it), '\n') }
                        }
                        "drawing", "pict", "object" -> runStack.lastOrNull()?.let { runs[it].hasDrawing = true }
                        "txbxContent" -> if (start) { skip++; p?.hasObject = true }
                        "tbl" -> if (start) {
                            tblDepth++
                            if (tblDepth == 1 && skip == 0 && pStack.isEmpty()) {
                                table = TInfo(tables.size, i).also { tables.add(it) }
                                if (cell == null) order.add(table!!)
                            }
                        }
                        "tblStyle" -> if (tblDepth == 1) table?.bordered = true
                        "gridCol" -> if (tblDepth == 1 && par == "tblGrid") table?.let { tb ->
                            tb.gridToks.add(i); tb.grid.add(XmlLite.attr(t.raw, Fmt.q(w, "w"))?.toDoubleOrNull()?.toInt() ?: 0)
                        }
                        "tr" -> if (tblDepth == 1 && start) table?.let { tb -> row = RowInfo(rowsI.size, i).also { rowsI.add(it); tb.rows.add(it.id) } }
                        "trPr" -> if (tblDepth == 1 && par == "tr") { if (start) trPrStart = i else row?.trPr = i..i }
                        "tc" -> if (tblDepth == 1 && start) row?.let { r -> cell = CInfo(cells.size, i).also { cells.add(it); r.cells.add(it.id) } }
                        "tcPr" -> if (tblDepth == 1 && par == "tc") { if (start) tcPrStart = i else cell?.tcPr = i..i }
                        "sectPr" -> { if (par == "body") bodySectPr = i; sect = SectInfo().also { sects.add(it) }; if (!start) sect = null; if (par == "pPr") p?.hasSect = true }
                        "pgSz" -> if (par == "sectPr") sect?.pgSz = i
                        "pgMar" -> if (par == "sectPr") sect?.pgMar = i
                        "pageBreakBefore" -> if (par == "pPr" && p != null && !Fmt.isOff(XmlLite.attr(t.raw, Fmt.q(w, "val")))) p.pageBreak = true
                    }
                    // Gambar (a:blip r:embed / v:imagedata r:id)
                    if (skip == 0 && (l == "blip" || l == "imagedata")) runStack.lastOrNull()?.let { ri ->
                        (XmlLite.attrLocal(t.raw, "embed") ?: XmlLite.attrLocal(t.raw, "id"))?.let { runs[ri].blips.add(it) }
                    }
                    if (l == "extent" && skip == 0) runStack.lastOrNull()?.let { ri ->
                        runs[ri].cx = XmlLite.attr(t.raw, "cx")?.toLongOrNull() ?: 0; runs[ri].cy = XmlLite.attr(t.raw, "cy")?.toLongOrNull() ?: 0
                    }
                    if (l == "Fallback" && start) skip++
                    if (isWn) {
                        if (par == "tcPr" && parentL(2) == "tc" && tblDepth == 1) cell?.let { c ->
                            when (l) {
                                "gridSpan" -> c.span = XmlLite.attr(t.raw, Fmt.q(w, "val"))?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                "vMerge" -> { c.hasVMerge = true; c.vCont = XmlLite.attr(t.raw, Fmt.q(w, "val")) != "restart" }
                                "shd" -> c.fill = Fmt.parseHex(XmlLite.attr(t.raw, Fmt.q(w, "fill")))
                                "tcW" -> c.tcW = i
                            }
                        }
                        if (par == "tblBorders" && tblDepth == 1) {
                            val v = XmlLite.attr(t.raw, Fmt.q(w, "val"))
                            if (v != null && v != "nil" && v != "none") table?.bordered = true
                        }
                    }
                    if (start) stack.add(t.name)
                }
                XTok.END -> {
                    val isWn = XmlLite.prefix(t.name) == w
                    val l = XmlLite.local(t.name)
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    val par = parentL(1)
                    if (isWn) when (l) {
                        "p" -> pStack.removeLastOrNull()?.let { it.end = i }
                        "r" -> {
                            val ri = runStack.removeLastOrNull()
                            val p = pStack.lastOrNull()
                            if (ri != null) {
                                val r = runs[ri]; r.end = i
                                if (p != null) {
                                    if (p.anyRun < 0 && r.rPr != null) p.anyRun = ri
                                    if (r.blips.isNotEmpty()) r.blips.forEach { p.images.add(ImgRef(it, r.start, i, r.cx, r.cy)) }
                                    else if (r.hasDrawing) p.hasObject = true
                                }
                            }
                        }
                        "rPr" -> {
                            val s = rPrStarts.removeLastOrNull() ?: -1
                            if (s >= 0) {
                                if (par == "r") runStack.lastOrNull()?.let { runs[it].rPr = s..i }
                                if (par == "pPr") pStack.lastOrNull()?.markRPr = s..i
                            }
                        }
                        "pPr" -> if (par == "p" && pPrStart >= 0) { pStack.lastOrNull()?.pPr = pPrStart..i; pPrStart = -1 }
                        "t" -> curT = -1
                        "txbxContent" -> skip--
                        "tbl" -> { if (tblDepth == 1) { table?.end = i; table = null; row = null; cell = null }; tblDepth-- }
                        "tblGrid" -> if (tblDepth == 1) table?.gridEnd = i
                        "tr" -> if (tblDepth == 1) { row?.end = i; row = null }
                        "trPr" -> if (tblDepth == 1 && trPrStart >= 0) { row?.trPr = trPrStart..i; trPrStart = -1 }
                        "tc" -> if (tblDepth == 1) { cell?.end = i; cell = null }
                        "tcPr" -> if (tblDepth == 1 && tcPrStart >= 0) { cell?.tcPr = tcPrStart..i; tcPrStart = -1 }
                        "sectPr" -> sect = null
                        "body" -> bodyEnd = i
                    }
                    if (l == "Fallback") skip--
                }
                XTok.TEXT -> {
                    val p = pStack.lastOrNull()
                    val ri = runStack.lastOrNull()
                    if (curT >= 0 && p != null && ri != null) {
                        tOwner[i] = curT; tRun[i] = ri
                        val s = XmlLite.decode(t.raw)
                        for (k in s.indices) addAtom(p, Atom(i, k, ri), s[k])
                    }
                }
            }
        }
        for (p in paras) {
            val (f, sect) = Fmt.parsePPr(toks, p.pPr, w)
            p.direct = f; if (sect) p.hasSect = true
        }
    }

    fun pageSetup(): PageSetup? {
        val s = sects.lastOrNull { it.pgSz >= 0 } ?: return null
        val sz = toks[s.pgSz].raw
        fun a(raw: String, n: String) = XmlLite.attr(raw, Fmt.q(w, n))?.toDoubleOrNull()?.toInt()
        val pw = a(sz, "w") ?: 11906; val ph = a(sz, "h") ?: 16838
        val land = XmlLite.attr(sz, Fmt.q(w, "orient")) == "landscape"
        val m = if (s.pgMar >= 0) toks[s.pgMar].raw else ""
        return PageSetup(pw, ph, land, a(m, "top") ?: 1440, a(m, "right") ?: 1440, a(m, "bottom") ?: 1440, a(m, "left") ?: 1440)
    }

    fun buildModel(styles: Styles): List<EBlock> {
        for (r in runs) {
            val (d, rs) = Fmt.parseRPr(toks, r.rPr, w)
            r.direct = d; r.rStyle = rs
            r.merged = styles.charStyle(rs).over(d)
        }
        fun para(p: PInfo): EPara {
            val e = EPara(p.id, p.text.toString(), Array(p.atoms.size) { runs[p.atoms[it].run].merged }, p.direct)
            e.images = ArrayList(p.images); e.hasObject = p.hasObject; e.pageBreak = p.pageBreak; e.locked = p.hasSect
            return e
        }
        return order.map { o ->
            when (o) {
                is PInfo -> para(o)
                is TInfo -> {
                    val rws = o.rows.map { rowsI[it] }
                    val simple = o.grid.isNotEmpty() && rws.all { r -> r.cells.size == o.grid.size && r.cells.all { cells[it].span == 1 && !cells[it].hasVMerge } }
                    ETable(o.id, rws.mapTo(ArrayList()) { r ->
                        ERow(r.id, r.cells.mapTo(ArrayList()) { ci ->
                            val c = cells[ci]
                            ECell(c.id, c.paras.mapTo(ArrayList()) { para(paras[it]) }, c.span, c.vCont, c.fill, c.id, c.nested)
                        })
                    }, ArrayList(o.grid), simple, o.bordered)
                }
                else -> error("?")
            }
        }
    }

    // --------------------------------------------------------------------------------------
    // Status patch
    // --------------------------------------------------------------------------------------

    val before = HashMap<Int, StringBuilder>()
    val after = HashMap<Int, StringBuilder>()
    val del = BooleanArray(toks.size)
    val repl = HashMap<Int, String>()
    /** Token atom (tab/br) yang dihapus: isinya tidak ditulis, tetapi sisipan di sekitarnya tetap. */
    val blank = HashSet<Int>()
    /** Per token TEXT: hapus per karakter, override rPr per karakter, sisipan per offset. */
    val tDel = HashMap<Int, BooleanArray>()
    val tOvr = HashMap<Int, Array<String?>>()
    val tIns = HashMap<Int, HashMap<Int, MutableList<Pair<Char, String?>>>>()

    fun before(i: Int): StringBuilder = before.getOrPut(i) { StringBuilder() }
    fun after(i: Int): StringBuilder = after.getOrPut(i) { StringBuilder() }
    fun delRange(r: IntRange) { for (k in r) if (k in del.indices) del[k] = true }
    fun replRange(r: IntRange, xml: String) { delRange(r); repl[r.first] = xml }

    fun wq(l: String) = if (w.isEmpty()) l else "$w:$l"
    fun tOpen() = "<${wq("t")} xml:space=\"preserve\">"
    fun tClose() = "</${wq("t")}>"

    fun charXml(c: Char): String = when (c) {
        '\n' -> tClose() + "<${wq("br")}/>" + tOpen()
        '\t' -> tClose() + "<${wq("tab")}/>" + tOpen()
        '\r' -> ""
        else -> XmlLite.esc(c.toString())
    }

    /** Isi di dalam <w:t> yang terbuka pada run berformat [orig]; override memecah run. */
    fun emitInRun(seq: List<Pair<Char, String?>>, orig: String): String {
        val sb = StringBuilder()
        var cur: String? = null
        for ((c, ov) in seq) {
            if (ov != cur) {
                sb.append(tClose()).append("</${wq("r")}><${wq("r")}>").append(ov ?: orig).append(tOpen())
                cur = ov
            }
            sb.append(charXml(c))
        }
        if (cur != null) sb.append(tClose()).append("</${wq("r")}><${wq("r")}>").append(orig).append(tOpen())
        return sb.toString()
    }

    fun rangeRaw(r: IntRange?): String {
        if (r == null) return ""
        val sb = StringBuilder()
        for (k in r) sb.append(toks[k].raw)
        return sb.toString()
    }

    private val STRIP = setOf("ins", "del", "moveFrom", "moveTo", "rPrChange", "pPrChange", "sectPr", "pageBreakBefore")

    /** Salin rentang token tanpa sub-pohon yang tidak boleh diduplikasi. */
    fun copyRange(r: IntRange?, strip: Set<String> = STRIP): String {
        if (r == null) return ""
        val sb = StringBuilder()
        var skipDepth = 0
        for (i in r) {
            val t = toks[i]
            val drop = XmlLite.prefix(t.name) == w && XmlLite.local(t.name) in strip
            when {
                skipDepth > 0 -> { if (t.kind == XTok.START) skipDepth++ else if (t.kind == XTok.END) skipDepth-- }
                drop && t.kind == XTok.START -> skipDepth = 1
                drop && t.kind == XTok.EMPTY -> {}
                else -> sb.append(t.raw)
            }
        }
        return sb.toString()
    }

    fun render(): String {
        val sb = StringBuilder()
        val preserve = HashSet<Int>()
        for (i in tDel.keys + tOvr.keys + tIns.keys) tOwner[i]?.let { preserve.add(it) }
        for (i in toks.indices) {
            repl[i]?.let { sb.append(it) }
            if (del[i]) continue
            val t = toks[i]
            before[i]?.let { sb.append(it) }
            when {
                i in blank -> {}
                i in preserve -> sb.append("<${t.name} xml:space=\"preserve\">")
                t.kind == XTok.TEXT && (tDel.containsKey(i) || tOvr.containsKey(i) || tIns.containsKey(i)) -> {
                    val s = XmlLite.decode(t.raw)
                    val d = tDel[i]; val o = tOvr[i]; val ins = tIns[i]
                    val seq = ArrayList<Pair<Char, String?>>(s.length + 8)
                    for (k in 0..s.length) {
                        ins?.get(k)?.let { seq.addAll(it) }
                        if (k < s.length && (d == null || !d[k])) seq.add(s[k] to o?.get(k))
                    }
                    val run = tRun[i]
                    sb.append(emitInRun(seq, run?.let { rangeRaw(runs[it].rPr) } ?: ""))
                }
                else -> sb.append(t.raw)
            }
            after[i]?.let { sb.append(it) }
        }
        return sb.toString()
    }
}

// =============================================================================================
// Patcher: menerapkan model editor ke token XML asli
// =============================================================================================

internal class Patcher(private val p: Parsed, private val doc: DocxDocument, private val parts: PartWriter) {
    private val w = p.w
    private var imgN = 0
    private val usedStyles = HashSet<String>()
    private val usedNums = HashSet<Int>()

    fun apply() {
        p.buildModel(doc.styles) // isi format gabungan per run

        val presentP = HashSet<Int>(); val presentT = HashSet<Int>(); val presentR = HashSet<Int>(); val presentC = HashSet<Int>()
        fun markPara(e: EPara) { if (e.origin >= 0) presentP.add(e.origin); e.pf.style?.let { usedStyles.add(it) }; e.pf.numId?.let { usedNums.add(it) } }
        for (b in doc.body) when (b) {
            is EPara -> markPara(b)
            is ETable -> {
                if (b.origin >= 0) presentT.add(b.origin)
                b.rows.forEach { r -> if (r.origin >= 0) presentR.add(r.origin); r.cells.forEach { c -> if (c.origin >= 0) presentC.add(c.origin); c.paras.forEach(::markPara) } }
            }
            else -> {}
        }
        doc.styles.used = usedStyles
        doc.numbering.used = usedNums

        // Penghapusan
        for (t in p.tables) if (t.id !in presentT && p.order.contains(t)) p.delRange(t.start..t.end)
        for (r in p.rowsI) if (r.id !in presentR) p.delRange(r.start..r.end)
        for (c in p.cells) if (c.id !in presentC) p.delRange(c.start..c.end)
        for (pi in p.paras) {
            if (!pi.editable || pi.id in presentP) continue
            if (!pi.bodyLevel && pi.cell < 0) continue
            if (pi.hasSect) { editPara(pi, EPara(pi.id, "", emptyArray(), pi.direct)); continue }
            p.delRange(pi.start..pi.end)
        }
        // Gambar asli yang dihapus dari paragraf
        for (b in doc.allParas()) if (b.origin >= 0) {
            val orig = p.paras[b.origin].images
            for (im in orig) if (b.images.none { it.runStart == im.runStart }) p.delRange(im.runStart..im.runEnd)
        }

        // Body
        flush(doc.body, { b ->
            when (b) {
                is EPara -> if (b.origin >= 0) p.paras[b.origin].let { it.start..it.end } else null
                is ETable -> if (b.origin >= 0) p.tables[b.origin].let { it.start..it.end } else null
                else -> null
            }
        }, ::blockXml, if (p.bodySectPr >= 0) p.bodySectPr else p.bodyEnd)

        for (b in doc.body) when (b) {
            is EPara -> if (b.origin >= 0) editPara(p.paras[b.origin], b)
            is ETable -> if (b.origin >= 0) editTable(p.tables[b.origin], b)
            else -> {}
        }

        // Halaman
        if (doc.pageChanged()) doc.page?.let { pg ->
            for (s in p.sects) {
                if (s.pgSz >= 0) {
                    val t = p.toks[s.pgSz]; val a = XmlLite.attrs(t.raw)
                    a[Fmt.q(w, "w")] = "${pg.w}"; a[Fmt.q(w, "h")] = "${pg.h}"
                    if (pg.landscape) a[Fmt.q(w, "orient")] = "landscape" else a.remove(Fmt.q(w, "orient"))
                    t.raw = XmlLite.tag(t.name, a, true)
                }
                if (s.pgMar >= 0) {
                    val t = p.toks[s.pgMar]; val a = XmlLite.attrs(t.raw)
                    a[Fmt.q(w, "top")] = "${pg.top}"; a[Fmt.q(w, "right")] = "${pg.right}"
                    a[Fmt.q(w, "bottom")] = "${pg.bottom}"; a[Fmt.q(w, "left")] = "${pg.left}"
                    t.raw = XmlLite.tag(t.name, a, true)
                }
            }
        }
    }

    private fun <T> flush(items: List<T>, range: (T) -> IntRange?, xml: (T) -> String, fallback: Int) {
        var anchor = -1
        val pending = StringBuilder()
        for (it in items) {
            val r = range(it)
            if (r == null) { pending.append(xml(it)); continue }
            if (pending.isNotEmpty()) {
                if (anchor >= 0) p.after(anchor).append(pending) else p.before(r.first).append(pending)
                pending.setLength(0)
            }
            anchor = r.last
        }
        if (pending.isNotEmpty()) {
            if (anchor >= 0) p.after(anchor).append(pending) else if (fallback >= 0) p.before(fallback).append(pending)
        }
    }

    private fun blockXml(b: EBlock): String = when (b) {
        is EPara -> newParaXml(b)
        is ETable -> newTableXml(b)
        is EImage -> imageXml(b)
        is EBreak -> "<${p.wq("p")}><${p.wq("r")}><${p.wq("br")} ${Fmt.q(w, "type")}=\"page\"/></${p.wq("r")}></${p.wq("p")}>"
    }

    // ------------------------------- paragraf --------------------------------------------

    private fun runRaw(ri: Int?): String = ri?.let { p.copyRange(p.runs[it].rPr) } ?: ""
    private fun runFmt(ri: Int?): CharFmt = ri?.let { p.runs[it].merged } ?: CharFmt.EMPTY

    /** Run-run baru untuk teks [text] dengan format per karakter, relatif terhadap run acuan. */
    private fun runsXml(text: String, fmts: Array<CharFmt>, tplRaw: String, tplFmt: CharFmt): String {
        val sb = StringBuilder()
        var k = 0
        while (k < text.length) {
            var j = k
            while (j < text.length && fmts.getOrElse(j) { CharFmt.EMPTY } == fmts.getOrElse(k) { CharFmt.EMPTY }) j++
            sb.append("<${p.wq("r")}>").append(Fmt.rprWith(tplRaw, tplFmt, fmts.getOrElse(k) { CharFmt.EMPTY }, w))
                .append(p.tOpen())
            for (c in text.substring(k, j)) sb.append(p.charXml(c))
            sb.append(p.tClose()).append("</${p.wq("r")}>")
            k = j
        }
        return sb.toString()
    }

    private fun newParaXml(e: EPara): String {
        val tpl = p.paras.getOrNull(e.tpl)
        val tplPf = tpl?.direct ?: ParaFmt()
        val pPr = Fmt.pprWith(p.copyRange(tpl?.pPr), tplPf, e.pf, w)
        val ri = tpl?.let { if (it.firstRun >= 0) it.firstRun else if (it.anyRun >= 0) it.anyRun else null }
        val (rRaw, rFmt) = if (ri != null) runRaw(ri) to runFmt(ri)
        else (tpl?.markRPr?.let { p.copyRange(it) } ?: "") to (tpl?.markRPr?.let { Fmt.parseRPr(p.toks, it, w).first } ?: CharFmt.EMPTY)
        val br = if (e.pageBreak && e.origin < 0) "<${p.wq("r")}><${p.wq("br")} ${Fmt.q(w, "type")}=\"page\"/></${p.wq("r")}>" else ""
        return "<${p.wq("p")}>$pPr$br${runsXml(e.text, e.fmts, rRaw, rFmt)}</${p.wq("p")}>"
    }

    private fun editPara(pi: PInfo, e: EPara) {
        val origText = pi.text.toString()
        val origFmts = Array(pi.atoms.size) { p.runs[pi.atoms[it].run].merged }
        val pfChanged = e.pf != pi.direct
        val textChanged = origText != e.text || !origFmts.contentEquals(e.fmts)
        if (!pfChanged && !textChanged) return

        if (pi.emptyTag) {
            val t = p.toks[pi.start]
            val pPr = Fmt.pprWith("", ParaFmt(), e.pf, w)
            t.raw = t.raw.removeSuffix("/>").trimEnd() + ">" + pPr + runsXml(e.text, e.fmts, "", CharFmt.EMPTY) + "</${t.name}>"
            return
        }
        if (pfChanged) {
            val newPPr = Fmt.pprWith(p.rangeRaw(pi.pPr), pi.direct, e.pf, w)
            val r = pi.pPr
            if (r != null) p.replRange(r, newPPr) else if (newPPr.isNotEmpty()) p.after(pi.start).append(newPPr)
        }
        if (!textChanged) return

        if (pi.atoms.isEmpty()) {
            if (e.text.isEmpty()) return
            val ri = if (pi.anyRun >= 0) pi.anyRun else null
            val (raw, f) = if (ri != null) runRaw(ri) to runFmt(ri)
            else (pi.markRPr?.let { p.copyRange(it) } ?: "") to (pi.markRPr?.let { Fmt.parseRPr(p.toks, it, w).first } ?: CharFmt.EMPTY)
            p.before(pi.end).append(runsXml(e.text, e.fmts, raw, f))
            return
        }

        val d = TextDiff.align(origText, e.text)
        val n = origText.length
        // Hapus
        for (k in 0 until n) if (d.oldToNew[k] < 0) {
            val a = pi.atoms[k]
            if (a.off >= 0) p.tDel.getOrPut(a.tok) { BooleanArray(XmlLite.decode(p.toks[a.tok].raw).length) }[a.off] = true
            else p.blank.add(a.tok)
        }
        // Format karakter yang dipertahankan
        for (k in 0 until n) {
            val j = d.oldToNew[k]
            if (j < 0) continue
            val a = pi.atoms[k]
            if (a.off < 0) continue
            val want = e.fmts.getOrElse(j) { origFmts[k] }
            if (want != origFmts[k]) {
                p.tOvr.getOrPut(a.tok) { arrayOfNulls(XmlLite.decode(p.toks[a.tok].raw).length) }[a.off] =
                    Fmt.rprWith(runRaw(a.run), origFmts[k], want, w)
            }
        }
        // Sisipan
        for (ins in d.inserts) {
            val k = ins.at
            val (ai, afterAtom) = when {
                k > 0 && d.oldToNew[k - 1] < 0 -> (k - 1) to true
                k < n && d.oldToNew[k] < 0 -> k to false
                k > 0 -> (k - 1) to true
                else -> 0 to false
            }
            val a = pi.atoms[ai]
            val host = origFmts[ai]
            val raw = runRaw(a.run)
            val seq = (ins.from until ins.to).map { j ->
                val f = e.fmts.getOrElse(j) { host }
                e.text[j] to (if (f == host) null else Fmt.rprWith(raw, host, f, w))
            }
            if (a.off >= 0) {
                val pos = if (afterAtom) a.off + 1 else a.off
                p.tIns.getOrPut(a.tok) { HashMap() }.getOrPut(pos) { ArrayList() }.addAll(seq)
            } else {
                val xml = p.tOpen() + p.emitInRun(seq, raw) + p.tClose()
                (if (afterAtom) p.after(a.tok) else p.before(a.tok)).append(xml)
            }
        }
    }

    // ------------------------------- tabel -----------------------------------------------

    private fun tcPrXml(tplCell: Int, width: Int, fill: Int?): String {
        val c = p.cells.getOrNull(tplCell)
        val raw = c?.tcPr?.let { p.copyRange(it, setOf("vMerge", "tcPrChange", "gridSpan")) }
        val e = raw?.let { XmlLite.parseElem(it) } ?: XmlLite.Elem("<${p.wq("tcPr")}>", "</${p.wq("tcPr")}>", ArrayList(), p.wq("tcPr"))
        e.children.removeAll { XmlLite.local(it.first) == "tcW" }
        e.children.add(0, p.wq("tcW") to "<${p.wq("tcW")} ${Fmt.q(w, "w")}=\"$width\" ${Fmt.q(w, "type")}=\"dxa\"/>")
        if (fill != null && e.children.none { XmlLite.local(it.first) == "shd" })
            e.children.add(p.wq("shd") to "<${p.wq("shd")} ${Fmt.q(w, "val")}=\"clear\" ${Fmt.q(w, "color")}=\"auto\" ${Fmt.q(w, "fill")}=\"${Fmt.hex(fill)}\"/>")
        val sb = StringBuilder(e.open); e.children.forEach { sb.append(it.second) }; return sb.append(e.close).toString()
    }

    private fun cellParasXml(c: ECell): String =
        if (c.paras.isEmpty()) "<${p.wq("p")}/>" else c.paras.joinToString("") { newParaXml(it) }

    private fun newCellXml(c: ECell, width: Int): String =
        "<${p.wq("tc")}>${tcPrXml(c.tplCell, width, c.fill)}${cellParasXml(c)}</${p.wq("tc")}>"

    private fun newRowXml(r: ERow, grid: List<Int>): String {
        val tplRow = p.rowsI.getOrNull(r.tplRow)
        val trPr = tplRow?.trPr?.let { p.copyRange(it, setOf("ins", "del", "trPrChange", "tblHeader")) } ?: ""
        val sb = StringBuilder("<${p.wq("tr")}>").append(trPr)
        r.cells.forEachIndexed { i, c -> sb.append(newCellXml(c, grid.getOrElse(i) { grid.lastOrNull() ?: 2000 })) }
        return sb.append("</${p.wq("tr")}>").toString()
    }

    private fun newTableXml(t: ETable): String {
        val W = { l: String -> p.wq(l) }
        val a = { l: String -> Fmt.q(w, l) }
        val bd = listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") {
            "<${W(it)} ${a("val")}=\"single\" ${a("sz")}=\"4\" ${a("space")}=\"0\" ${a("color")}=\"000000\"/>"
        }
        val sb = StringBuilder("<${W("tbl")}><${W("tblPr")}><${W("tblW")} ${a("w")}=\"${t.grid.sum()}\" ${a("type")}=\"dxa\"/>")
            .append("<${W("tblBorders")}>$bd</${W("tblBorders")}><${W("tblLayout")} ${a("type")}=\"fixed\"/>")
            .append("<${W("tblCellMar")}><${W("left")} ${a("w")}=\"108\" ${a("type")}=\"dxa\"/><${W("right")} ${a("w")}=\"108\" ${a("type")}=\"dxa\"/></${W("tblCellMar")}>")
            .append("</${W("tblPr")}><${W("tblGrid")}>")
        t.grid.forEach { sb.append("<${W("gridCol")} ${a("w")}=\"$it\"/>") }
        sb.append("</${W("tblGrid")}>")
        for (r in t.rows) {
            sb.append("<${W("tr")}>")
            r.cells.forEachIndexed { i, c ->
                sb.append("<${W("tc")}><${W("tcPr")}><${W("tcW")} ${a("w")}=\"${t.grid.getOrElse(i) { 2000 }}\" ${a("type")}=\"dxa\"/>")
                c.fill?.let { sb.append("<${W("shd")} ${a("val")}=\"clear\" ${a("color")}=\"auto\" ${a("fill")}=\"${Fmt.hex(it)}\"/>") }
                sb.append("</${W("tcPr")}>").append(cellParasXml(c)).append("</${W("tc")}>")
            }
            sb.append("</${W("tr")}>")
        }
        return sb.append("</${W("tbl")}>").toString()
    }

    private fun editTable(ti: TInfo, t: ETable) {
        // Lebar kolom berubah (tambah/hapus kolom) -> tulis ulang tblGrid & tcW
        val gridChanged = t.grid != ti.grid
        if (gridChanged && ti.gridEnd >= 0) {
            ti.gridToks.forEach { p.delRange(it..it) }
            p.before(ti.gridEnd).append(t.grid.joinToString("") { "<${p.wq("gridCol")} ${Fmt.q(w, "w")}=\"$it\"/>" })
        }
        flush(t.rows, { r -> if (r.origin >= 0) p.rowsI[r.origin].let { it.start..it.end } else null }, { newRowXml(it, t.grid) }, ti.end)
        for (r in t.rows) if (r.origin >= 0) {
            flush(r.cells, { c -> if (c.origin >= 0) p.cells[c.origin].let { it.start..it.end } else null }, { c ->
                newCellXml(c, t.grid.getOrElse(r.cells.indexOf(c)) { 2000 })
            }, p.rowsI[r.origin].end)
            r.cells.forEachIndexed { ci, c ->
                if (c.origin < 0) return@forEachIndexed
                val info = p.cells[c.origin]
                if (gridChanged && t.simple && info.tcW >= 0) {
                    val tok = p.toks[info.tcW]; val at = XmlLite.attrs(tok.raw)
                    at[Fmt.q(w, "w")] = "${t.grid.getOrElse(ci) { 2000 }}"; at[Fmt.q(w, "type")] = "dxa"
                    tok.raw = XmlLite.tag(tok.name, at, true)
                }
                flush(c.paras, { e -> if (e.origin >= 0) p.paras[e.origin].let { it.start..it.end } else null }, ::newParaXml, info.end)
                c.paras.forEach { if (it.origin >= 0) editPara(p.paras[it.origin], it) }
            }
        }
    }

    // ------------------------------- gambar ----------------------------------------------

    private fun imageXml(img: EImage): String {
        imgN++
        val ext = if (img.ext.equals("png", true)) "png" else "jpeg"
        val rId = "rIdDpImg${System.nanoTime() % 100000}$imgN"
        val file = "depup_img_${System.currentTimeMillis()}_$imgN.$ext"
        parts.media.add(PartWriter.Media(rId, file, ext, img.bytes))
        val textW = (doc.page?.textWidth ?: 9026).toLong() * 635 // twip -> EMU
        val cx = (textW * img.widthFrac.coerceIn(0.1f, 1f)).toLong()
        val cy = (cx * img.hPx.toDouble() / img.wPx.coerceAtLeast(1)).toLong()
        val id = 30000 + imgN
        return "<${p.wq("p")}><${p.wq("pPr")}><${p.wq("jc")} ${Fmt.q(w, "val")}=\"center\"/></${p.wq("pPr")}><${p.wq("r")}><${p.wq("drawing")}>" +
            "<wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Gambar $id\"/>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"$file\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:embed=\"$rId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:inline></${p.wq("drawing")}></${p.wq("r")}></${p.wq("p")}>"
    }
}

// =============================================================================================
// Diff karakter (Myers) dengan pemetaan posisi lama -> baru
// =============================================================================================

internal object TextDiff {
    private const val MAX_D = 1500

    class Ins(val at: Int, val from: Int, val to: Int)
    class Result(val oldToNew: IntArray, val inserts: List<Ins>)

    fun align(a: String, b: String): Result {
        val map = IntArray(a.length) { -1 }
        val ins = ArrayList<Ins>()
        var pre = 0
        while (pre < a.length && pre < b.length && a[pre] == b[pre]) pre++
        if (pre > 0 && pre < a.length && a[pre - 1].isHighSurrogate()) pre--
        var suf = 0
        while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf] == b[b.length - 1 - suf]) suf++
        if (suf > 0 && a[a.length - suf].isLowSurrogate()) suf--
        for (k in 0 until pre) map[k] = k
        for (k in 0 until suf) map[a.length - suf + k] = b.length - suf + k
        val aa = a.substring(pre, a.length - suf)
        val bb = b.substring(pre, b.length - suf)
        if (aa.isEmpty() && bb.isEmpty()) return Result(map, ins)
        if (aa.isEmpty()) { ins.add(Ins(pre, pre, pre + bb.length)); return Result(map, ins) }
        if (bb.isEmpty()) return Result(map, ins)

        val ops = myers(aa, bb)
        if (ops == null) { ins.add(Ins(pre, pre, pre + bb.length)); return Result(map, ins) }
        var curAt = -1; var curFrom = -1; var curTo = -1
        for (op in ops) {
            when (op[0]) {
                0 -> map[pre + op[1]] = pre + op[2]
                2 -> {
                    val at = pre + op[1]; val y = pre + op[2]
                    if (at == curAt && y == curTo) curTo++
                    else { if (curAt >= 0) ins.add(Ins(curAt, curFrom, curTo)); curAt = at; curFrom = y; curTo = y + 1 }
                }
            }
        }
        if (curAt >= 0) ins.add(Ins(curAt, curFrom, curTo))
        return Result(map, ins)
    }

    /** Operasi maju: [0,x,y] tetap, [1,x,-] hapus, [2,x,y] sisip b[y] sebelum a[x]. */
    private fun myers(a: String, b: String): List<IntArray>? {
        val n = a.length; val m = b.length
        val max = n + m
        val off = max + 1
        val v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        loop@ for (d in 0..max) {
            if (d > MAX_D) return null
            trace.add(v.copyOfRange(off - d - 1, off + d + 2))
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[off + k] = x
                if (x >= n && y >= m) { found = d; break@loop }
                k += 2
            }
        }
        if (found < 0) return null
        val ops = ArrayList<IntArray>()
        var x = n; var y = m
        for (d in found downTo 1) {
            val snap = trace[d]
            fun vv(k: Int) = snap[k + d + 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && vv(k - 1) < vv(k + 1))) k + 1 else k - 1
            val prevX = vv(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { x--; y--; ops.add(intArrayOf(0, x, y)) }
            if (x == prevX) ops.add(intArrayOf(2, x, prevY)) else ops.add(intArrayOf(1, prevX, -1))
            x = prevX; y = prevY
        }
        while (x > 0 && y > 0) { x--; y--; ops.add(intArrayOf(0, x, y)) }
        ops.reverse()
        return ops
    }
}
