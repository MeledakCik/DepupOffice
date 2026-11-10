package cloud.depup.office

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * DOM XML mini + pembaca paket OOXML (zip) — murni Kotlin (tanpa Android) supaya pembaca dokumen bisa diuji di JVM.
 * Nama elemen/atribut disimpan TANPA awalan namespace ("w:p" → "p"), cukup untuk DOCX/XLSX/PPTX.
 */
class XNode(val name: String) {
    val attrs = HashMap<String, String>(4)
    val kids = ArrayList<XNode>(2)
    /** Teks langsung (gabungan potongan teks di antara anak). */
    var text: String = ""

    fun attr(n: String): String? = attrs[n]
    fun child(n: String): XNode? { for (k in kids) if (k.name == n) return k; return null }
    fun children(n: String): List<XNode> = kids.filter { it.name == n }
    /** Jalur anak bertingkat: path("rPr","b"). */
    fun path(vararg ns: String): XNode? { var c: XNode? = this; for (n in ns) c = c?.child(n) ?: return null; return c }
    /** Semua keturunan bernama [n] (urutan dokumen). */
    fun all(n: String, out: MutableList<XNode> = ArrayList()): MutableList<XNode> {
        for (k in kids) { if (k.name == n) out.add(k); k.all(n, out) }
        return out
    }
    fun first(n: String): XNode? { for (k in kids) { if (k.name == n) return k; k.first(n)?.let { return it } }; return null }
    fun allText(): String { val sb = StringBuilder(); fun go(x: XNode) { sb.append(x.text); x.kids.forEach { go(it) } }; go(this); return sb.toString() }
    /** Nilai atribut "val" (w:val) yang paling sering dipakai. */
    val v: String? get() = attrs["val"]
}

object XmlDom {
    private fun local(n: String): String { val i = n.indexOf(':'); return if (i >= 0) n.substring(i + 1) else n }

    fun parse(bytes: ByteArray): XNode = parse(decode(bytes))

    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val s = String(b, Charsets.UTF_8)
        return if (s.isNotEmpty() && s[0] == '﻿') s.substring(1) else s
    }

    fun parse(s: String): XNode {
        val root = XNode("#root")
        val stack = ArrayList<XNode>(); stack.add(root)
        val texts = ArrayList<StringBuilder?>(); texts.add(null)
        var i = 0; val n = s.length
        fun appendText(t: String) {
            if (t.isEmpty()) return
            val top = stack.size - 1
            val sb = texts[top] ?: StringBuilder().also { texts[top] = it }
            sb.append(t)
        }
        fun close() {
            val top = stack.size - 1
            texts[top]?.let { stack[top].text = it.toString() }
            stack.removeAt(top); texts.removeAt(top)
        }
        while (i < n) {
            val c = s[i]
            if (c == '<') {
                if (s.startsWith("<!--", i)) { val e = s.indexOf("-->", i + 4); i = if (e < 0) n else e + 3; continue }
                if (s.startsWith("<![CDATA[", i)) { val e = s.indexOf("]]>", i + 9); appendText(s.substring(i + 9, if (e < 0) n else e)); i = if (e < 0) n else e + 3; continue }
                if (i + 1 < n && (s[i + 1] == '?' || s[i + 1] == '!')) { val e = s.indexOf('>', i); i = if (e < 0) n else e + 1; continue }
                if (i + 1 < n && s[i + 1] == '/') {
                    val e = s.indexOf('>', i); i = if (e < 0) n else e + 1
                    if (stack.size > 1) close()
                    continue
                }
                // tag pembuka
                var j = i + 1
                while (j < n && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
                val node = XNode(local(s.substring(i + 1, j)))
                // atribut
                var selfClose = false
                while (j < n) {
                    while (j < n && s[j].isWhitespace()) j++
                    if (j >= n) break
                    if (s[j] == '>') { j++; break }
                    if (s[j] == '/') { selfClose = true; j++; continue }
                    var k = j
                    while (k < n && s[k] != '=' && !s[k].isWhitespace() && s[k] != '>' && s[k] != '/') k++
                    val an = s.substring(j, k)
                    while (k < n && s[k].isWhitespace()) k++
                    if (k < n && s[k] == '=') {
                        k++
                        while (k < n && s[k].isWhitespace()) k++
                        val q = if (k < n) s[k] else '"'
                        if (q == '"' || q == '\'') {
                            val e = s.indexOf(q, k + 1).let { if (it < 0) n else it }
                            val av = unescape(s.substring(k + 1, e))
                            if (!an.startsWith("xmlns")) { node.attrs[local(an)] = av; if (an.contains(':')) node.attrs[an] = av }
                            j = e + 1
                        } else j = k
                    } else j = k
                }
                stack[stack.size - 1].kids.add(node)
                if (!selfClose) { stack.add(node); texts.add(null) }
                i = j
            } else {
                val e = s.indexOf('<', i).let { if (it < 0) n else it }
                val raw = s.substring(i, e)
                if (stack.size > 1) appendText(unescape(raw))
                i = e
            }
        }
        while (stack.size > 1) close()
        return resolveAlternate(root)
    }

    /** mc:AlternateContent → pakai mc:Choice (format modern); Fallback dibuang. */
    private fun resolveAlternate(n: XNode): XNode {
        var idx = 0
        while (idx < n.kids.size) {
            val k = n.kids[idx]
            if (k.name == "AlternateContent") {
                val pick = k.child("Choice") ?: k.child("Fallback")
                n.kids.removeAt(idx)
                if (pick != null) { n.kids.addAll(idx, pick.kids); continue }
                continue
            }
            resolveAlternate(k); idx++
        }
        return n
    }

    fun unescape(t: String): String {
        if (t.indexOf('&') < 0) return t
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '&') {
                val e = t.indexOf(';', i)
                if (e > i && e - i < 12) {
                    val ent = t.substring(i + 1, e)
                    val r: String? = when {
                        ent == "amp" -> "&"; ent == "lt" -> "<"; ent == "gt" -> ">"; ent == "quot" -> "\""; ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (r != null) { sb.append(r); i = e + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}

/** Paket OOXML: isi zip + hubungan (rels) antar-bagian. */
class OPackage(bytes: ByteArray) {
    val files = HashMap<String, ByteArray>()
    init {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) files[e.name.trimStart('/')] = z.readBytes()
                e = z.nextEntry
            }
        }
    }
    private val domCache = HashMap<String, XNode?>()
    fun has(p: String) = files.containsKey(p)
    fun xml(p: String): XNode? = domCache.getOrPut(p) { files[p]?.let { try { XmlDom.parse(it) } catch (_: Throwable) { null } } }

    class Rel(val id: String, val type: String, val target: String, val external: Boolean)

    /** Hubungan bagian [part]: id → Rel, target sudah berupa jalur penuh di dalam zip. */
    fun rels(part: String): Map<String, Rel> {
        val dir = part.substringBeforeLast('/', "")
        val relPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val x = xml(relPath) ?: return emptyMap()
        val out = HashMap<String, Rel>()
        for (r in x.all("Relationship")) {
            val id = r.attr("Id") ?: continue
            val t = r.attr("Target") ?: continue
            val ext = r.attr("TargetMode") == "External"
            out[id] = Rel(id, r.attr("Type") ?: "", if (ext) t else resolve(dir, t), ext)
        }
        return out
    }

    fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val parts = ArrayList<String>()
        if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (seg in target.split('/')) when (seg) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts.add(seg) }
        return parts.joinToString("/")
    }

    /** Bagian utama menurut _rels/.rels (officeDocument). */
    fun mainPart(): String? = rels("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target
}

/** Hasil render: HTML + berkas pendukung (gambar) yang dilayani WebView di https://doc.local/<kunci>. */
class RenderedDoc(val html: String, val res: Map<String, ByteArray>, val title: String, val kind: String)

/** Utilitas warna & teks yang dipakai ketiga renderer. */
object OxUtil {
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) { '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '&' -> sb.append("&amp;"); '"' -> sb.append("&quot;"); else -> sb.append(c) }
        return sb.toString()
    }
    fun hex(rgb: Int) = "#%06x".format(rgb and 0xFFFFFF)
    fun parseHex(s: String?): Int? {
        if (s == null) return null
        val t = s.trim().removePrefix("#")
        return when (t.length) { 6 -> t.toIntOrNull(16); 8 -> t.substring(2).toIntOrNull(16); else -> null }
    }
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 255
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        return (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
    /** Campuran di ruang RGB linear (DrawingML tint/shade, seperti PowerPoint). [t] = bobot warna [b]. */
    fun mixLinear(a: Int, b: Int, t: Float): Int {
        fun lin(v: Int): Double { val c = v / 255.0; return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
        fun srgb(l: Double): Int { val c = if (l <= 0.0031308) l * 12.92 else 1.055 * Math.pow(l, 1 / 2.4) - 0.055; return (c * 255 + 0.5).toInt().coerceIn(0, 255) }
        fun ch(x: Int, sh: Int) = (x shr sh) and 255
        fun m(sh: Int) = srgb(lin(ch(a, sh)) + (lin(ch(b, sh)) - lin(ch(a, sh))) * t)
        return (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** HSL luminance modulation (DrawingML lumMod/lumOff, Excel tint). */
    fun lum(rgb: Int, mod: Float, off: Float): Int {
        val r = ((rgb shr 16) and 255) / 255f; val g = ((rgb shr 8) and 255) / 255f; val b = (rgb and 255) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        var h = 0f; var s = 0f; var l = (mx + mn) / 2
        if (mx != mn) {
            val d = mx - mn
            s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
            h = when (mx) { r -> (g - b) / d + (if (g < b) 6 else 0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 } / 6f
        }
        l = (l * mod + off).coerceIn(0f, 1f)
        fun hue(p: Float, q: Float, t0: Float): Float { var t = t0; if (t < 0) t += 1; if (t > 1) t -= 1
            return when { t < 1f / 6 -> p + (q - p) * 6 * t; t < 0.5f -> q; t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6; else -> p } }
        val rr: Float; val gg: Float; val bb: Float
        if (s == 0f) { rr = l; gg = l; bb = l } else {
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s; val p = 2 * l - q
            rr = hue(p, q, h + 1f / 3); gg = hue(p, q, h); bb = hue(p, q, h - 1f / 3)
        }
        return ((rr * 255 + 0.5f).toInt() shl 16) or ((gg * 255 + 0.5f).toInt() shl 8) or (bb * 255 + 0.5f).toInt()
    }
    /** Excel tint: -1..1 (negatif = lebih gelap). */
    fun tint(rgb: Int, t: Float): Int = if (t < 0) lum(rgb, 1 + t, 0f) else lum(rgb, 1 - t, t)

    /** Nama font Office → keluarga CSS (font pengganti metrik-sama dibundel di aset). */
    fun fontStack(name: String?, serifHint: Boolean = false): String {
        val n = (name ?: "").trim()
        val low = n.lowercase()
        val fallback = when {
            low.isEmpty() -> if (serifHint) "'Times New Roman',serif" else "Calibri,sans-serif"
            listOf("times", "cambria", "georgia", "garamond", "book antiqua", "bookman", "palatino", "serif", "minion", "century", "constantia", "baskerville")
                .any { low.contains(it) } && !low.contains("sans") -> "'Times New Roman',serif"
            listOf("courier", "consolas", "mono", "lucida console", "menlo").any { low.contains(it) } -> "'Courier New',monospace"
            listOf("arial", "helvetica", "verdana", "tahoma", "segoe", "trebuchet", "roboto", "open sans", "liberation sans", "noto sans", "gill", "aptos").any { low.contains(it) } -> "Arial,sans-serif"
            else -> "Calibri,sans-serif"
        }
        return if (n.isEmpty()) fallback else "'" + n.replace("'", "") + "'," + fallback
    }

    /** CSS @font-face: nama font Office dipetakan ke font pengganti yang ukurannya (metrik) sama. */
    fun fontFaces(base: String): String {
        val sb = StringBuilder()
        fun face(fam: String, file: String) {
            for ((suf, w, st) in listOf(Triple("Regular", 400, "normal"), Triple("Bold", 700, "normal"), Triple("Italic", 400, "italic"), Triple("BoldItalic", 700, "italic")))
                sb.append("@font-face{font-family:'$fam';src:local('$fam'),url('${base}$file-$suf.ttf');font-weight:$w;font-style:$st}")
        }
        for (f in listOf("Calibri", "Calibri Light", "Carlito")) face(f, "DpMetricA")
        for (f in listOf("Cambria", "Caladea")) face(f, "DpMetricB")
        for (f in listOf("Arial", "Helvetica", "Liberation Sans", "Arial MT", "Aptos", "Aptos Display", "Aptos Narrow")) face(f, "DpMetricSans")
        for (f in listOf("Times New Roman", "Times", "Liberation Serif")) face(f, "DpMetricSerif")
        for (f in listOf("Courier New", "Courier", "Liberation Mono")) face(f, "DpMetricMono")
        return sb.toString()
    }

    /** Karakter Symbol/Wingdings (area pribadi Unicode) → karakter Unicode biasa. */
    fun symbolChar(c: Char, font: String?): String {
        val code = c.code
        val f = (font ?: "").lowercase()
        val k = if (code in 0xF000..0xF0FF) code - 0xF000 else code
        if (f.contains("wingdings")) return when (k) {
            0xA7 -> "▪"; 0xA8 -> "◻"; 0x6C -> "●"; 0x6E -> "■"; 0x71 -> "❑"; 0x76 -> "❖"; 0xD8 -> "➢"; 0xFC -> "✓"; 0xFB -> "✗"
            0x9F -> "•"; 0x77 -> "◆"; 0xA1 -> "○"; 0xE0 -> "→"; 0xE8 -> "➔"; 0xF0 -> "⇨"; 0x4A -> "☺"; 0x43 -> "👍"; else -> "•" }
        if (f.contains("symbol")) return when (k) { 0xB7 -> "•"; 0x2D -> "−"; 0xAE -> "→"; 0xD8 -> "¬"; 0xA7 -> "♣"; 0xB0 -> "°"; 0xB4 -> "×"; else -> if (k in 0x20..0x7E) k.toChar().toString() else "•" }
        if (code in 0xF000..0xF0FF) return "•"
        return c.toString()
    }

    fun isImage(path: String): Boolean {
        val p = path.lowercase()
        return p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".gif") || p.endsWith(".bmp") || p.endsWith(".webp") || p.endsWith(".svg")
    }
}
