package cloud.depup.office

import kotlin.math.abs
import kotlin.math.max

/**
 * Bagian murni (tanpa Android) dari pencarian isi [DocSearch]: menormalkan teks, mencari & memotong cuplikan, serta
 * mengambil teks dari XML Office (Word / Excel / PowerPoint) dan dari glif PDF. Diuji di JVM (DocTextTest).
 */
object DocText {
    /** Cuplikan teks; [start]..[end] = letak kata yang dicari di dalam [text]. */
    class Snip(val text: String, val start: Int, val end: Int)

    /** Spasi & karakter tak tampak yang diabaikan saat mencocokkan (PDF sering tanpa spasi antar-kata). */
    fun blank(ch: Char) = Character.isWhitespace(ch) || ch == '\u00A0' || ch == '\u00AD' || ch == '\u200B' || ch == '\uFEFF'

    /** Huruf kecil tanpa spasi — panjangnya = jumlah karakter bukan-[blank] teks asli (dipakai [snippet]). */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) if (!blank(ch)) sb.append(Character.toLowerCase(ch))
        return sb.toString()
    }

    /**
     * Cuplikan di sekitar kecocokan pertama [needle] (sudah [norm]) di [raw]; [n] = norm(raw).
     * Null = tidak ada. Awal cuplikan tidak memotong kata; spasi beruntun dirapatkan.
     */
    fun snippet(raw: String, n: String, needle: String): Snip? {
        if (needle.isEmpty()) return null
        val k = n.indexOf(needle)
        if (k < 0) return null
        // kembalikan ke posisi teks asli: hitung karakter bukan-spasi
        var seen = 0; var start = -1; var end = -1
        for (i in raw.indices) {
            if (blank(raw[i])) continue
            if (seen == k) start = i
            seen++
            if (seen == k + needle.length) { end = i + 1; break }
        }
        if (start < 0 || end < 0) return null
        var a = max(0, start - 36)
        while (a > 0 && a > start - 56 && !blank(raw[a - 1])) a--
        val b = minOf(raw.length, end + 90)
        val pre = (if (a > 0) "…" else "") + clean(raw, a, start).trimStart()
        val mid = clean(raw, start, end)
        val post = clean(raw, end, b).trimEnd() + if (b < raw.length) "…" else ""
        return Snip(pre + mid + post, pre.length, pre.length + mid.length)
    }

    /** Spasi beruntun → satu spasi; tanda pemenggal & spasi lebar-nol dibuang. */
    private fun clean(s: String, from: Int, to: Int): String {
        val sb = StringBuilder(to - from)
        var space = false
        for (i in from until to) {
            val ch = s[i]
            if (ch == '\u00AD' || ch == '\u200B' || ch == '\uFEFF') continue
            if (blank(ch)) { if (!space) sb.append(' '); space = true } else { sb.append(ch); space = false }
        }
        return sb.toString()
    }

    /** Glif PDF → teks berurutan: spasi bila ada jarak, baris baru bila pindah baris. */
    fun glyphText(gs: List<PdfLayout.Glyph>, sb: StringBuilder) {
        var px = Float.NaN; var py = 0f; var ps = 0f
        for (g in gs) {
            if (g.text.isEmpty()) continue
            if (!px.isNaN()) {
                val sz = max(max(g.size, ps), 1f)
                when {
                    abs(g.y - py) > sz * 0.6f -> sb.append('\n')
                    g.x - px > sz * 0.18f || g.x < px - sz * 2f -> sb.append(' ')
                }
            }
            sb.append(g.text)
            px = g.x + g.w; py = g.y; ps = g.size
        }
    }

    /** Teks di antara tag XML Office; akhir paragraf / baris → baris baru, tab & akhir sel → spasi. Kode field dilewati. */
    fun xmlText(x: String, sb: StringBuilder) {
        var i = 0
        var skip = false
        var tabStops = false     // <w:tabs> di sifat paragraf berisi <w:tab> juga — itu posisi tab, bukan tab
        val n = x.length
        while (i < n) {
            if (x[i] == '<') {
                val e = x.indexOf('>', i)
                if (e < 0) break
                var j = i + 1
                if (j < e && x[j] == '/') j++
                while (j < e && x[j] != ' ' && x[j] != '/' && x[j] != '>') j++
                when (x.substring(i + 1, j)) {
                    "/w:p", "/a:p", "/si", "w:br", "/w:tr", "a:br" -> sb.append('\n')
                    "w:tab" -> if (!tabStops) sb.append(' ')
                    "/w:tc" -> sb.append(' ')
                    "w:tabs" -> tabStops = x[e - 1] != '/'
                    "/w:tabs" -> tabStops = false
                    "w:instrText", "w:delText" -> skip = x[e - 1] != '/'
                    "/w:instrText", "/w:delText" -> skip = false
                }
                i = e + 1
            } else {
                val e = x.indexOf('<', i).let { if (it < 0) n else it }
                if (!skip) unescape(x, i, e, sb)
                i = e
            }
        }
    }

    private val CELLS = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val CELL_T = Regex("\\bt=\"(\\w+)\"")
    private val CELL_V = Regex("<v>([^<]*)</v>")

    /** Lembar Excel: teks sebaris & angka. Sel teks bersama (t="s") dilewati — teksnya sudah dari sharedStrings. */
    fun sheet(x: String, sb: StringBuilder, maxChars: Int = Int.MAX_VALUE) {
        for (m in CELLS.findAll(x)) {
            if (sb.length > maxChars) return
            val body = m.groupValues[2]
            if (body.isEmpty()) continue
            when (CELL_T.find(m.groupValues[1])?.groupValues?.get(1)) {
                "s" -> {}
                "inlineStr" -> { xmlText(body, sb); sb.append(' ') }
                else -> CELL_V.find(body)?.let { v -> unescape(v.groupValues[1], 0, v.groupValues[1].length, sb); sb.append(' ') }
            }
        }
    }

    /** Salin [x] dari..ke ke [sb] sambil menerjemahkan entitas XML (&amp; &lt; &#233; &#x20AC; …). */
    fun unescape(x: String, from: Int, to: Int, sb: StringBuilder) {
        var i = from
        while (i < to) {
            val c = x[i]
            if (c == '&') {
                val e = x.indexOf(';', i)
                if (e in (i + 2)..minOf(to - 1, i + 10)) {
                    val ent = x.substring(i + 1, e)
                    val cp: Int? = when {
                        ent == "lt" -> '<'.code
                        ent == "gt" -> '>'.code
                        ent == "amp" -> '&'.code
                        ent == "quot" -> '"'.code
                        ent == "apos" -> '\''.code
                        ent.startsWith("#x") -> ent.substring(2).toIntOrNull(16)
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()
                        else -> null
                    }
                    if (cp != null && Character.isValidCodePoint(cp)) { sb.appendCodePoint(cp); i = e + 1; continue }
                }
            }
            sb.append(c); i++
        }
    }
}
