package cloud.depup.office

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pembaca struktur PDF (murni Kotlin/JVM, tanpa Android — diuji di JVM).
 *
 * PdfRenderer bawaan Android hanya bisa MENGGAMBAR halaman; untuk PDF ke Word yang lengkap (teks asli, foto, garis,
 * tabel) dan kunci AES-256 tanpa merusak teks, isi PDF perlu dibaca langsung. Mendukung: tabel
 * xref klasik & xref stream, object stream, filter Flate (+ prediktor PNG), ASCIIHex, ASCII85, LZW, RunLength, berkas
 * rusak (xref dibangun ulang dengan memindai "n g obj"), dan dekripsi Standard Security Handler (RC4 40/128,
 * AES-128, AES-256) untuk password kosong / password yang diberikan.
 */

// ------------------------------------------------------------------ objek PDF

class PName(val n: String) {
    override fun equals(other: Any?) = other is PName && other.n == n
    override fun hashCode() = n.hashCode()
    override fun toString() = "/$n"
}
class PStr(var b: ByteArray, val hex: Boolean = false) {
    /** Teks: UTF-16BE (BOM FE FF), UTF-8 (BOM EF BB BF), selain itu PDFDocEncoding (≈ Latin-1). */
    fun text(): String = when {
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
        else -> String(b, Charsets.ISO_8859_1)
    }
    override fun toString() = "(" + text() + ")"
    companion object {
        /** String teks PDF: Latin-1 bila cukup, selain itu UTF-16BE ber-BOM. */
        fun of(s: String): PStr = if (s.all { it.code < 128 }) PStr(s.toByteArray(Charsets.ISO_8859_1))
            else PStr(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE))
    }
}
class PRef(val num: Int, val gen: Int) {
    override fun equals(other: Any?) = other is PRef && other.num == num && other.gen == gen
    override fun hashCode() = num * 31 + gen
    override fun toString() = "$num $gen R"
}
class PDict(val m: LinkedHashMap<String, Any?> = LinkedHashMap()) {
    operator fun get(k: String): Any? = m[k]
    operator fun set(k: String, v: Any?) { if (v == null) m.remove(k) else m[k] = v }
    fun name(k: String): String? = (m[k] as? PName)?.n
    override fun toString() = m.entries.joinToString(" ", "<<", ">>") { "/${it.key} ${it.value}" }
}
class PStream(val dict: PDict, var raw: ByteArray) {
    override fun toString() = "stream$dict[${raw.size}]"
}
/** Penanda "objek tidak ada / null". */
object PNull { override fun toString() = "null" }

class PdfException(msg: String) : Exception(msg)
class PdfPasswordException(msg: String) : Exception(msg)

// ------------------------------------------------------------------ pemindai (lexer + parser objek)

class PdfLexer(val d: ByteArray, var p: Int = 0, val end: Int = d.size) {
    companion object {
        /** Kedalaman array/kamus maksimum (PDF wajar < 30). */
        const val MAX_NEST = 512
        fun ws(c: Int) = c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00
        fun delim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code || c == ']'.code ||
            c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
    }

    fun at(i: Int): Int = if (i < end) d[i].toInt() and 0xFF else -1

    fun skipWs() {
        while (p < end) {
            val c = at(p)
            if (ws(c)) p++
            else if (c == '%'.code) { while (p < end && at(p) != 0x0A && at(p) != 0x0D) p++ }
            else break
        }
    }

    /** Kata kunci / angka mentah sampai pembatas berikutnya. */
    fun token(): String {
        skipWs()
        val s = p
        while (p < end && !ws(at(p)) && !delim(at(p))) p++
        return String(d, s, p - s, Charsets.ISO_8859_1)
    }

    /**
     * Kedalaman array/kamus yang sedang dibaca. PDF jahat bisa bersarang ratusan ribu tingkat ("[[[[…") → rekursi
     * meluap (StackOverflowError — Error, lolos dari `catch (Exception)` & menutup aplikasi). Dibatasi [MAX_NEST].
     */
    private var nest = 0

    private inline fun <T> nested(block: () -> T): T {
        if (++nest > MAX_NEST) { nest--; throw IllegalStateException("Struktur PDF terlalu dalam") }
        try { return block() } finally { nest-- }
    }

    /** Baca satu objek. "n g R" dikenali bila [refs]. Mengembalikan kata kunci (mis. "obj", "stream") sebagai [Op]. */
    fun obj(refs: Boolean = true): Any? {
        skipWs()
        if (p >= end) return null
        val c = at(p)
        when {
            c == '/'.code -> { p++; return PName(nameBody()) }
            c == '('.code -> { p++; return PStr(literal()) }
            c == '<'.code && at(p + 1) == '<'.code -> { p += 2; return nested { dict() } }
            c == '<'.code -> { p++; return PStr(hexStr(), true) }
            c == '['.code -> {
                p++
                return nested {
                    val a = ArrayList<Any?>()
                    while (true) {
                        skipWs()
                        if (p >= end) break
                        if (at(p) == ']'.code) { p++; break }
                        val o = obj(refs)
                        if (o is Op) continue
                        a.add(o)
                    }
                    a
                }
            }
            c == ']'.code || c == '>'.code || c == ')'.code || c == '}'.code || c == '{'.code -> { p++; return Op(String(charArrayOf(c.toChar()))) }
        }
        val t = token()
        if (t.isEmpty()) { p++; return Op("") }
        val n = num(t)
        if (n != null) {
            if (refs && n is Long && n >= 0) {
                // "n g R" ?
                val save = p
                val t2 = token()
                val g = t2.toLongOrNull()
                if (g != null && g >= 0) {
                    val t3 = token()
                    if (t3 == "R") return PRef(n.toInt(), g.toInt())
                }
                p = save
            }
            return n
        }
        return when (t) {
            "true" -> true; "false" -> false; "null" -> PNull
            else -> Op(t)
        }
    }

    class Op(val k: String) { override fun toString() = k }

    private fun num(t: String): Number? {
        if (t.isEmpty()) return null
        val c = t[0]
        if (!(c.isDigit() || c == '-' || c == '+' || c == '.')) return null
        t.toLongOrNull()?.let { return it }
        // angka rusak umum: "--5", "1.2.3", "5-"
        val clean = t.replace(Regex("^[-+]{2,}"), "-").let { s -> val i = s.indexOf('.', s.indexOf('.') + 1); if (i > 0) s.substring(0, i) else s }.trimEnd('-')
        return clean.toDoubleOrNull() ?: if (t.all { it == '-' || it == '+' || it == '.' }) 0L else null
    }

    private fun nameBody(): String {
        val sb = ByteArrayOutputStream()
        while (p < end && !ws(at(p)) && !delim(at(p))) {
            val c = at(p)
            if (c == '#'.code && p + 2 < end && hex(at(p + 1)) >= 0 && hex(at(p + 2)) >= 0) { sb.write(hex(at(p + 1)) * 16 + hex(at(p + 2))); p += 3 }
            else { sb.write(c); p++ }
        }
        return String(sb.toByteArray(), Charsets.UTF_8)
    }

    private fun literal(): ByteArray {
        val o = ByteArrayOutputStream()
        var depth = 1
        while (p < end) {
            val c = at(p++)
            when (c) {
                '\\'.code -> {
                    if (p >= end) break
                    val e = at(p++)
                    when (e) {
                        'n'.code -> o.write(10); 'r'.code -> o.write(13); 't'.code -> o.write(9)
                        'b'.code -> o.write(8); 'f'.code -> o.write(12)
                        0x0D -> { if (at(p) == 0x0A) p++ }
                        0x0A -> {}
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            for (k in 0 until 2) { val q = at(p); if (q in '0'.code..'7'.code) { v = v * 8 + (q - '0'.code); p++ } else break }
                            o.write(v and 0xFF)
                        }
                        else -> o.write(e)
                    }
                }
                '('.code -> { depth++; o.write(c) }
                ')'.code -> { depth--; if (depth == 0) return o.toByteArray(); o.write(c) }
                else -> o.write(c)
            }
        }
        return o.toByteArray()
    }

    private fun hex(c: Int) = when (c) { in 48..57 -> c - 48; in 65..70 -> c - 55; in 97..102 -> c - 87; else -> -1 }

    private fun hexStr(): ByteArray {
        val o = ByteArrayOutputStream()
        var hi = -1
        while (p < end) {
            val c = at(p++)
            if (c == '>'.code) break
            val v = hex(c)
            if (v < 0) continue
            if (hi < 0) hi = v else { o.write(hi * 16 + v); hi = -1 }
        }
        if (hi >= 0) o.write(hi * 16)
        return o.toByteArray()
    }

    private fun dict(): PDict {
        val dd = PDict()
        while (true) {
            skipWs()
            if (p >= end) break
            if (at(p) == '>'.code && at(p + 1) == '>'.code) { p += 2; break }
            val k = obj(false)
            if (k !is PName) { if (k is Op && k.k == ">") continue; if (k == null) break; continue }
            skipWs()
            if (at(p) == '>'.code && at(p + 1) == '>'.code) { dd[k.n] = PNull; continue }
            val v = obj(true)
            if (v is Op) continue
            dd[k.n] = v
        }
        return dd
    }
}

// ------------------------------------------------------------------ dokumen

class PdfFile private constructor(val data: ByteArray) {
    /** objNum -> (offset di berkas) atau (nomor object stream, indeks) */
    private val xOff = HashMap<Int, Int>()
    private val xGen = HashMap<Int, Int>()
    private val xStm = HashMap<Int, IntArray>()
    private val cache = HashMap<Int, Any?>()
    var trailer = PDict(); private set
    /** Dekripsi aktif bila berkas terenkripsi dan password cocok. */
    private var crypt: PdfDecrypt? = null
    var encrypted = false; private set
    /** Objek /Encrypt (jangan ikut ditulis ulang). */
    var encryptRef: PRef? = null; private set
    /** Izin asli (/P) bila terenkripsi. */
    var permissions: Int = -1; private set
    /** Dibuka dengan password pemilik (atau tidak terenkripsi): batasan izin tidak berlaku. */
    var ownerAuth = true; private set
    /** Isi boleh disalin / diubah menurut izin PDF (bit 5 & 4 /P) atau karena dibuka pemilik. */
    val canExtract get() = ownerAuth || !encrypted || ((permissions and (1 shl 4)) != 0 && (permissions and (1 shl 3)) != 0)
    val version: String = Regex("%PDF-(\\d\\.\\d)").find(String(data, 0, minOf(1024, data.size), Charsets.ISO_8859_1))?.groupValues?.get(1) ?: "1.4"

    companion object {
        fun open(data: ByteArray, password: String = ""): PdfFile {
            val f = PdfFile(data)
            f.loadXref()
            f.setupCrypt(password)
            return f
        }
        fun dict(o: Any?): PDict? = o as? PDict ?: (o as? PStream)?.dict
        fun num(o: Any?, def: Double = 0.0): Double = (o as? Number)?.toDouble() ?: def
        fun int(o: Any?, def: Int = 0): Int = (o as? Number)?.toInt() ?: def
    }

    // ---------------- xref

    private fun loadXref() {
        val tail = String(data, maxOf(0, data.size - 2048), minOf(2048, data.size), Charsets.ISO_8859_1)
        val sx = tail.lastIndexOf("startxref")
        var ok = false
        if (sx >= 0) {
            val off = Regex("startxref\\s+(\\d+)").find(tail, sx)?.groupValues?.get(1)?.toIntOrNull()
            if (off != null && off in 0 until data.size) ok = try { readXrefChain(off); xOff.isNotEmpty() || xStm.isNotEmpty() } catch (_: Exception) { false }
        }
        // xref rusak / offset salah: bangun ulang dengan memindai seluruh berkas
        if (!ok || !sane()) rebuildXref()
        if (trailer["Root"] == null) findRoot()
    }

    /** Cek cepat: offset objek Root benar-benar menunjuk "n g obj". */
    private fun sane(): Boolean {
        val r = trailer["Root"] as? PRef ?: return false
        val off = xOff[r.num] ?: return xStm.containsKey(r.num)
        return off in 0 until data.size && Regex("^\\s*${r.num}\\s+\\d+\\s+obj").containsMatchIn(String(data, off, minOf(40, data.size - off), Charsets.ISO_8859_1))
    }

    private fun readXrefChain(first: Int) {
        val seen = HashSet<Int>()
        var off: Int? = first
        var firstTrailer: PDict? = null
        while (off != null && off in 0 until data.size && seen.add(off)) {
            val lx = PdfLexer(data, off)
            lx.skipWs()
            val tr: PDict
            if (String(data, lx.p, minOf(4, data.size - lx.p), Charsets.ISO_8859_1) == "xref") {
                lx.p += 4
                tr = readXrefTable(lx)
                // berkas hibrida: /XRefStm berisi objek di object stream
                (tr["XRefStm"] as? Number)?.toInt()?.let { xs -> try { readXrefStream(xs) } catch (_: Exception) {} }
            } else {
                tr = readXrefStream(off) ?: break
            }
            if (firstTrailer == null) firstTrailer = tr
            else for ((k, v) in tr.m) if (!firstTrailer.m.containsKey(k)) firstTrailer.m[k] = v
            off = (tr["Prev"] as? Number)?.toInt()
        }
        trailer = firstTrailer ?: PDict()
    }

    private fun readXrefTable(lx: PdfLexer): PDict {
        while (true) {
            lx.skipWs()
            val save = lx.p
            val t = lx.token()
            if (t == "trailer") {
                val d = lx.obj() as? PDict ?: PDict()
                return d
            }
            val start = t.toIntOrNull() ?: run { lx.p = save; return PDict() }
            val count = lx.token().toIntOrNull() ?: return PDict()
            for (i in 0 until count) {
                val o = lx.token().toLongOrNull() ?: 0L
                val g = lx.token().toIntOrNull() ?: 0
                val ty = lx.token()
                val n = start + i
                // rantai /Prev dibaca dari yang terbaru: entri yang sudah ada jangan ditimpa
                if (xOff.containsKey(n) || xStm.containsKey(n)) continue
                if (ty == "n" && o > 0) { xOff[n] = o.toInt(); xGen[n] = g }
                else if (ty == "f") xOff[n] = -1
            }
        }
    }

    private fun readXrefStream(off: Int): PDict? {
        val (o, _) = readIndirectAt(off) ?: return null
        val s = o as? PStream ?: return null
        if (s.dict.name("Type") != "XRef") return null
        val w = (s.dict["W"] as? List<*>)?.map { int(it) } ?: return null
        val size = int(s.dict["Size"])
        val idx = (s.dict["Index"] as? List<*>)?.map { int(it) } ?: listOf(0, size)
        val b = decode(s, null)
        val rec = w.sum()
        var p = 0
        var k = 0
        while (k + 1 < idx.size) {
            val start = idx[k]; val cnt = idx[k + 1]; k += 2
            for (i in 0 until cnt) {
                if (p + rec > b.size) break
                fun f(n: Int): Long { var v = 0L; for (j in 0 until w[n]) v = (v shl 8) or (b[p++].toLong() and 0xFF); return v }
                val t = if (w[0] == 0) 1L else f(0)
                val a = f(1); val c = f(2)
                val n = start + i
                if (xOff.containsKey(n) || xStm.containsKey(n)) continue
                when (t) {
                    0L -> xOff[n] = -1
                    1L -> { xOff[n] = a.toInt(); xGen[n] = c.toInt() }
                    2L -> xStm[n] = intArrayOf(a.toInt(), c.toInt())
                }
            }
        }
        return s.dict
    }

    private fun rebuildXref() {
        xOff.clear(); xStm.clear(); xGen.clear(); cache.clear()
        val re = Regex("(?<![0-9])(\\d{1,10})\\s+(\\d{1,5})\\s+obj\\b")
        val s = String(data, Charsets.ISO_8859_1)
        for (m in re.findAll(s)) {
            val n = m.groupValues[1].toInt()
            xOff[n] = m.range.first; xGen[n] = m.groupValues[2].toInt()   // yang terakhir menang (pembaruan bertahap)
        }
        // object stream: daftarkan isinya
        for (n in xOff.keys.toList()) {
            val o = try { obj(n) } catch (_: Exception) { null }
            val st = o as? PStream ?: continue
            if (st.dict.name("Type") == "ObjStm") {
                val cnt = int(st.dict["N"])
                val lx = PdfLexer(try { decode(st, null) } catch (_: Exception) { continue })
                for (i in 0 until cnt) { val on = lx.token().toIntOrNull() ?: break; lx.token(); if (!xOff.containsKey(on)) xStm[on] = intArrayOf(n, i) }
            }
        }
        cache.clear()
        var tr = PDict()
        var i = s.lastIndexOf("trailer")
        while (i >= 0) {
            val d = PdfLexer(data, i + 7).obj() as? PDict
            if (d != null && d["Root"] != null) { tr = d; break }
            i = s.lastIndexOf("trailer", i - 1)
        }
        trailer = tr
    }

    private fun findRoot() {
        for (n in (xOff.keys + xStm.keys).sorted()) {
            val d = dict(try { obj(n) } catch (_: Exception) { null }) ?: continue
            if (d.name("Type") == "Catalog") { trailer["Root"] = PRef(n, xGen[n] ?: 0); return }
        }
        throw PdfException("Struktur PDF tidak dikenali (Catalog tidak ditemukan)")
    }

    /** Semua nomor objek yang ada. */
    fun objectNumbers(): List<Int> = (xOff.filterValues { it > 0 }.keys + xStm.keys).toSortedSet().toList()
    fun genOf(n: Int): Int = xGen[n] ?: 0

    // ---------------- objek

    private fun readIndirectAt(off: Int): Pair<Any?, Int>? {
        val lx = PdfLexer(data, off)
        val n = lx.token().toIntOrNull() ?: return null
        lx.token()
        if (lx.token() != "obj") return null
        val o = lx.obj()
        lx.skipWs()
        if (o is PDict && data.size - lx.p >= 6 && String(data, lx.p, 6, Charsets.ISO_8859_1) == "stream") {
            var p = lx.p + 6
            if (p < data.size && data[p] == 0x0D.toByte()) p++
            if (p < data.size && data[p] == 0x0A.toByte()) p++
            var len = when (val l = o["Length"]) {
                is Number -> l.toInt()
                is PRef -> if (l.num == n) -1 else int(try { obj(l.num) } catch (_: Exception) { null }, -1)
                else -> -1
            }
            // panjang salah/tidak diketahui: cari "endstream"
            if (len < 0 || p + len > data.size || !endsAt(p + len)) {
                val e = indexOf("endstream".toByteArray(), p)
                len = if (e < 0) data.size - p else {
                    var q = e
                    if (q > p && data[q - 1] == 0x0A.toByte()) q--
                    if (q > p && data[q - 1] == 0x0D.toByte()) q--
                    q - p
                }
            }
            return PStream(o, data.copyOfRange(p, p + len)) to n
        }
        return o to n
    }

    private fun endsAt(p: Int): Boolean {
        var q = p
        while (q < data.size && PdfLexer.ws(data[q].toInt() and 0xFF)) q++
        return data.size - q >= 9 && String(data, q, 9, Charsets.ISO_8859_1) == "endstream"
    }

    private fun indexOf(pat: ByteArray, from: Int): Int {
        var i = from
        val end = data.size - pat.size
        while (i <= end) { var j = 0; while (j < pat.size && data[i + j] == pat[j]) j++; if (j == pat.size) return i; i++ }
        return -1
    }

    /** Objek nomor [n] (sudah didekripsi bila perlu). */
    fun obj(n: Int): Any? {
        if (cache.containsKey(n)) return cache[n]
        cache[n] = PNull          // jaga dari rujukan berputar
        val r: Any? = try {
            val off = xOff[n]
            if (off != null && off > 0) {
                val (o, _) = readIndirectAt(off) ?: (null to 0)
                val c = crypt
                if (c != null && n != encryptRef?.num) c.decryptObj(o, n, genOf(n)) else o
            } else xStm[n]?.let { (sn, idx) -> fromObjStm(sn, idx, n) }
        } catch (e: PdfPasswordException) { throw e } catch (_: Exception) { null }
        cache[n] = r
        return r
    }

    private val stmCache = HashMap<Int, Pair<IntArray, ByteArray>>()

    private fun fromObjStm(sn: Int, idx: Int, want: Int): Any? {
        val (offs, body) = stmCache.getOrPut(sn) {
            val st = obj(sn) as? PStream ?: return null
            val b = decode(st, null)
            val cnt = int(st.dict["N"]); val first = int(st.dict["First"])
            val lx = PdfLexer(b)
            val o = IntArray(cnt * 2)
            for (i in 0 until cnt) { o[2 * i] = lx.token().toIntOrNull() ?: -1; o[2 * i + 1] = first + (lx.token().toIntOrNull() ?: 0) }
            o to b
        }
        var i = idx
        if (i * 2 >= offs.size || offs[2 * i] != want) i = (0 until offs.size / 2).firstOrNull { offs[2 * it] == want } ?: return null
        return PdfLexer(body, offs[2 * i + 1]).obj()
    }

    /** Ikuti rujukan sampai objek langsung. */
    fun res(o: Any?): Any? {
        var x = o
        var guard = 0
        while (x is PRef && guard++ < 32) x = obj(x.num)
        return if (x === PNull) null else x
    }
    fun rdict(o: Any?): PDict? = dict(res(o))

    val catalog: PDict get() = rdict(trailer["Root"]) ?: throw PdfException("Catalog PDF tidak ada")
    val info: PDict? get() = rdict(trailer["Info"])

    // ---------------- halaman

    class Page(val ref: PRef?, val dict: PDict, val res: PDict, val box: FloatArray, val rotate: Int)

    fun pages(): List<Page> {
        val out = ArrayList<Page>()
        fun walk(nodeRef: Any?, inh: PDict, depth: Int) {
            if (depth > 64) return
            val node = rdict(nodeRef) ?: return
            val h = PDict(LinkedHashMap(inh.m))
            for (k in listOf("Resources", "MediaBox", "CropBox", "Rotate")) node[k]?.let { h[k] = it }
            if (node.name("Type") == "Pages" || node["Kids"] != null) {
                for (kid in (res(node["Kids"]) as? List<*>).orEmpty()) walk(kid, h, depth + 1)
            } else {
                val mb = (res(h["CropBox"]) as? List<*> ?: res(h["MediaBox"]) as? List<*>)?.map { num(res(it)).toFloat() }
                val box = if (mb != null && mb.size >= 4) floatArrayOf(minOf(mb[0], mb[2]), minOf(mb[1], mb[3]), maxOf(mb[0], mb[2]), maxOf(mb[1], mb[3]))
                    else floatArrayOf(0f, 0f, 612f, 792f)
                out.add(Page(nodeRef as? PRef, node, rdict(h["Resources"]) ?: PDict(), box, ((int(res(h["Rotate"])) % 360) + 360) % 360))
            }
        }
        walk(catalog["Pages"], PDict(), 0)
        return out
    }

    /** Isi halaman (gabungan semua stream /Contents), sudah di-dekode. */
    fun contents(p: Page): ByteArray {
        val o = ByteArrayOutputStream()
        val c = res(p.dict["Contents"])
        val list = if (c is List<*>) c else listOf(p.dict["Contents"])
        for (x in list) { val s = res(x) as? PStream ?: continue; try { o.write(decode(s, null)); o.write(10) } catch (_: Exception) {} }
        return o.toByteArray()
    }

    // ---------------- filter

    /**
     * Dekode stream. [stopAt] = nama filter gambar yang dibiarkan (mis. DCTDecode/JPXDecode): data dikembalikan
     * dalam bentuk terkompresi itu. Lempar bila filter tidak didukung.
     */
    fun decode(s: PStream, stopAt: Set<String>?): ByteArray {
        val f = res(s.dict["Filter"])
        val fl = when (f) { is PName -> listOf(f.n); is List<*> -> f.mapNotNull { (res(it) as? PName)?.n }; else -> emptyList() }
        val pr = res(s.dict["DecodeParms"])
        val pl: List<PDict?> = when (pr) { is PDict -> listOf(pr); is List<*> -> pr.map { rdict(it) }; else -> emptyList() }
        var b = s.raw
        for ((i, name) in fl.withIndex()) {
            if (stopAt != null && name in stopAt) return b
            val parm = pl.getOrNull(i)
            b = when (name) {
                "FlateDecode", "Fl" -> predictor(inflate(b), parm)
                "LZWDecode", "LZW" -> predictor(lzw(b, int(parm?.get("EarlyChange"), 1)), parm)
                "ASCIIHexDecode", "AHx" -> ahx(b)
                "ASCII85Decode", "A85" -> a85(b)
                "RunLengthDecode", "RL" -> rl(b)
                "Crypt" -> b
                else -> throw PdfException("Filter $name belum didukung")
            }
        }
        return b
    }

    fun filters(s: PStream): List<String> = when (val f = res(s.dict["Filter"])) { is PName -> listOf(f.n); is List<*> -> f.mapNotNull { (res(it) as? PName)?.n }; else -> emptyList() }

    private fun inflate(b: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(b)
        val o = ByteArrayOutputStream(minOf(b.size.toLong() * 3 + 64, 64L shl 20).toInt())
        val buf = ByteArray(16384)
        // hasil dekompresi dibatasi (flate bomb: stream kecil yang mengembang jadi gigabita)
        val cap = Files.ramCap()
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break }
                if (o.size().toLong() + n > cap) throw Files.TooLargeException(cap)
                o.write(buf, 0, n)
            }
        } catch (e: Files.TooLargeException) {
            throw e
        } catch (_: Exception) {
            // data rusak: kembalikan yang sempat terbaca (banyak pembaca juga begini); coba tanpa header zlib
            if (o.size() == 0) {
                val raw = Inflater(true)
                try { raw.setInput(b, 2, maxOf(0, b.size - 2)); while (!raw.finished()) { val n = raw.inflate(buf); if (n == 0) break; if (o.size().toLong() + n > cap) break; o.write(buf, 0, n) } } catch (_: Exception) {} finally { raw.end() }
            }
        } finally { inf.end() }
        return o.toByteArray()
    }

    private fun predictor(b: ByteArray, parm: PDict?): ByteArray {
        val pred = int(parm?.get("Predictor"), 1)
        if (pred < 10) return b
        val colors = int(parm?.get("Colors"), 1); val bpc = int(parm?.get("BitsPerComponent"), 8); val cols = int(parm?.get("Columns"), 1)
        val bpp = maxOf(1, (colors * bpc + 7) / 8)
        val row = (colors * bpc * cols + 7) / 8
        val o = ByteArrayOutputStream()
        var prev = ByteArray(row)
        var p = 0
        while (p + 1 + row <= b.size + 0 && p < b.size) {
            val t = b[p].toInt() and 0xFF; p++
            val cur = ByteArray(row)
            val n = minOf(row, b.size - p)
            System.arraycopy(b, p, cur, 0, n); p += n
            for (i in 0 until row) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val x = cur[i].toInt() and 0xFF
                cur[i] = when (t) {
                    1 -> x + a; 2 -> x + up; 3 -> x + (a + up) / 2
                    4 -> { val pp = a + up - c; val pa = Math.abs(pp - a); val pb = Math.abs(pp - up); val pc = Math.abs(pp - c); x + (if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c) }
                    else -> x
                }.toByte()
            }
            o.write(cur); prev = cur
        }
        return o.toByteArray()
    }

    private fun ahx(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); var hi = -1
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c == '>'.code) break
            val v = when (c) { in 48..57 -> c - 48; in 65..70 -> c - 55; in 97..102 -> c - 87; else -> -1 }
            if (v < 0) continue
            if (hi < 0) hi = v else { o.write(hi * 16 + v); hi = -1 }
        }
        if (hi >= 0) o.write(hi * 16)
        return o.toByteArray()
    }

    private fun a85(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); val g = IntArray(5); var n = 0
        var i = 0
        if (b.size >= 2 && b[0] == '<'.code.toByte() && b[1] == '~'.code.toByte()) i = 2
        while (i < b.size) {
            val c = b[i++].toInt() and 0xFF
            if (c == '~'.code) break
            if (PdfLexer.ws(c)) continue
            if (c == 'z'.code && n == 0) { repeat(4) { o.write(0) }; continue }
            if (c < 33 || c > 117) continue
            g[n++] = c - 33
            if (n == 5) {
                var v = 0L; for (k in 0 until 5) v = v * 85 + g[k]
                for (k in 3 downTo 0) o.write(((v shr (8 * k)) and 0xFF).toInt()); n = 0
            }
        }
        if (n > 1) {
            for (k in n until 5) g[k] = 84
            var v = 0L; for (k in 0 until 5) v = v * 85 + g[k]
            for (k in 3 downTo 4 - (n - 1)) o.write(((v shr (8 * k)) and 0xFF).toInt())
        }
        return o.toByteArray()
    }

    private fun rl(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); var i = 0
        while (i < b.size) {
            val l = b[i++].toInt() and 0xFF
            if (l == 128) break
            if (l < 128) { val n = minOf(l + 1, b.size - i); o.write(b, i, n); i += n }
            else { if (i >= b.size) break; val v = b[i++].toInt(); repeat(257 - l) { o.write(v) } }
        }
        return o.toByteArray()
    }

    private fun lzw(b: ByteArray, early: Int): ByteArray {
        val o = ByteArrayOutputStream()
        val tab = ArrayList<ByteArray>(4096)
        fun reset() { tab.clear(); for (i in 0 until 256) tab.add(byteArrayOf(i.toByte())); tab.add(ByteArray(0)); tab.add(ByteArray(0)) }
        reset()
        var bits = 9; var buf = 0L; var nb = 0; var prev: ByteArray? = null
        var i = 0
        while (true) {
            while (nb < bits && i < b.size) { buf = (buf shl 8) or (b[i++].toLong() and 0xFF); nb += 8 }
            if (nb < bits) break
            val code = ((buf shr (nb - bits)) and ((1L shl bits) - 1)).toInt(); nb -= bits
            if (code == 256) { reset(); bits = 9; prev = null; continue }
            if (code == 257) break
            val e = when {
                code < tab.size -> tab[code]
                prev != null -> prev + prev[0]
                else -> break
            }
            o.write(e)
            if (prev != null) tab.add(prev + e[0])
            prev = e
            val lim = tab.size + early
            bits = when { lim >= 2048 -> 12; lim >= 1024 -> 11; lim >= 512 -> 10; else -> 9 }
        }
        return o.toByteArray()
    }

    // ---------------- dekripsi

    private fun setupCrypt(password: String) {
        val er = trailer["Encrypt"] ?: return
        encryptRef = er as? PRef
        val ed = rdict(er) ?: return
        encrypted = true
        permissions = int(ed["P"], -1)
        val id0 = ((res(trailer["ID"]) as? List<*>)?.firstOrNull() as? PStr)?.b ?: ByteArray(0)
        val c = PdfDecrypt.open(ed, id0, password) ?: throw PdfPasswordException("Password salah")
        crypt = c
        ownerAuth = c.owner
        cache.clear(); stmCache.clear()
    }
}

/** Standard Security Handler: RC4 (V1/V2), AES-128 (V4 AESV2), AES-256 (V5, R5/R6). */
class PdfDecrypt private constructor(private val key: ByteArray, private val aes: Boolean, private val v5: Boolean,
                                     private val strF: String, private val stmF: String, private val encMeta: Boolean) {
    /** true bila dibuka dengan password PEMILIK (boleh mengabaikan batasan izin). */
    var owner = false; private set
    companion object {
        private val PAD = intArrayOf(0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A).map { it.toByte() }.toByteArray()

        fun open(ed: PDict, id0: ByteArray, password: String): PdfDecrypt? {
            if (ed.name("Filter") != "Standard") throw PdfPasswordException("Jenis enkripsi ${ed.name("Filter")} belum didukung")
            val v = PdfFile.int(ed["V"]); val r = PdfFile.int(ed["R"])
            val o = (ed["O"] as? PStr)?.b ?: return null
            val u = (ed["U"] as? PStr)?.b ?: return null
            val p = PdfFile.int(ed["P"])
            val encMeta = ed["EncryptMetadata"] != false
            var aes = false; var strF = "StdCF"; var stmF = "StdCF"
            if (v >= 4) {
                val cf = ed["CF"] as? PDict
                strF = ed.name("StrF") ?: "Identity"; stmF = ed.name("StmF") ?: "Identity"
                val std = cf?.get(stmF) as? PDict ?: cf?.get(strF) as? PDict
                val cfm = std?.name("CFM")
                aes = cfm == "AESV2" || cfm == "AESV3"
            }
            if (r >= 5) {
                val pw = password.toByteArray(Charsets.UTF_8).let { if (it.size > 127) it.copyOf(127) else it }
                val ue = (ed["UE"] as? PStr)?.b; val oe = (ed["OE"] as? PStr)?.b
                // coba sebagai password pengguna lalu pemilik
                if (u.size >= 48 && ue != null && hashR6(pw, u.copyOfRange(32, 40), null, r).contentEquals(u.copyOf(32))) {
                    val k = hashR6(pw, u.copyOfRange(40, 48), null, r)
                    return PdfDecrypt(aesEcbCbcNoIv(k, ue), true, true, strF, stmF, encMeta)
                }
                if (o.size >= 48 && oe != null && hashR6(pw, o.copyOfRange(32, 40), u.copyOf(48), r).contentEquals(o.copyOf(32))) {
                    val k = hashR6(pw, o.copyOfRange(40, 48), u.copyOf(48), r)
                    return PdfDecrypt(aesEcbCbcNoIv(k, oe), true, true, strF, stmF, encMeta).also { it.owner = true }
                }
                return null
            }
            val len = if (v == 1) 5 else (PdfFile.int(ed["Length"], 40) / 8).coerceIn(5, 16)
            fun keyFor(pw: ByteArray): ByteArray {
                val md = MessageDigest.getInstance("MD5")
                md.update(pad(pw)); md.update(o)
                md.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte()))
                md.update(id0)
                if (r >= 4 && !encMeta) md.update(byteArrayOf(-1, -1, -1, -1))
                var k = md.digest().copyOf(len)
                if (r >= 3) repeat(50) { k = MessageDigest.getInstance("MD5").digest(k).copyOf(len) }
                return k
            }
            fun userOk(k: ByteArray): Boolean = if (r == 2) rc4(k, PAD).contentEquals(u.copyOf(32)) else {
                val md = MessageDigest.getInstance("MD5"); md.update(PAD); md.update(id0)
                var x = rc4(k, md.digest())
                for (i in 1..19) x = rc4(ByteArray(k.size) { (k[it].toInt() xor i).toByte() }, x)
                x.contentEquals(u.copyOf(16))
            }
            val pwb = password.toByteArray(Charsets.ISO_8859_1)
            keyFor(pwb).let { if (userOk(it)) return PdfDecrypt(it, aes, false, strF, stmF, encMeta) }
            // password pemilik → pulihkan password pengguna
            run {
                var ok = MessageDigest.getInstance("MD5").digest(pad(pwb))
                if (r >= 3) repeat(50) { ok = MessageDigest.getInstance("MD5").digest(ok) }
                val ok2 = ok.copyOf(len)
                var up = o.copyOf(32)
                if (r == 2) up = rc4(ok2, up) else for (i in 19 downTo 0) up = rc4(ByteArray(ok2.size) { (ok2[it].toInt() xor i).toByte() }, up)
                val k = keyFor(up)
                if (userOk(k)) return PdfDecrypt(k, aes, false, strF, stmF, encMeta).also { it.owner = true }
            }
            return null
        }

        fun pad(pw: ByteArray): ByteArray = ByteArray(32) { if (it < pw.size) pw[it] else PAD[it - pw.size] }

        fun rc4(k: ByteArray, d: ByteArray): ByteArray {
            val s = IntArray(256) { it }; var j = 0
            for (i in 0 until 256) { j = (j + s[i] + (k[i % k.size].toInt() and 0xFF)) and 255; val t = s[i]; s[i] = s[j]; s[j] = t }
            var i = 0; j = 0
            return ByteArray(d.size) { n ->
                i = (i + 1) and 255; j = (j + s[i]) and 255; val t = s[i]; s[i] = s[j]; s[j] = t
                (d[n].toInt() xor s[(s[i] + s[j]) and 255]).toByte()
            }
        }

        /** ISO 32000-2 Algoritma 2.B (R6); R5 = SHA-256 sekali saja. */
        fun hashR6(pw: ByteArray, salt: ByteArray, u: ByteArray?, r: Int): ByteArray {
            var k = MessageDigest.getInstance("SHA-256").run { update(pw); update(salt); if (u != null) update(u); digest() }
            if (r == 5) return k
            var round = 0
            while (true) {
                val unit = pw + k + (u ?: ByteArray(0))
                val k1 = ByteArray(unit.size * 64)
                for (i in 0 until 64) System.arraycopy(unit, 0, k1, i * unit.size, unit.size)
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
                val e = c.doFinal(k1)
                var sum = 0
                for (i in 0 until 16) sum += e[i].toInt() and 0xFF
                k = MessageDigest.getInstance(when (sum % 3) { 0 -> "SHA-256"; 1 -> "SHA-384"; else -> "SHA-512" }).digest(e)
                round++
                if (round >= 64 && (e[e.size - 1].toInt() and 0xFF) <= round - 32) break
            }
            return k.copyOf(32)
        }

        private fun aesEcbCbcNoIv(k: ByteArray, d: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(ByteArray(16)))
            return c.doFinal(d.copyOf(32))
        }
    }

    private fun objKey(n: Int, g: Int): ByteArray {
        if (v5) return key
        val md = MessageDigest.getInstance("MD5")
        md.update(key)
        md.update(byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), g.toByte(), (g shr 8).toByte()))
        if (aes) md.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))
        return md.digest().copyOf(minOf(16, key.size + 5))
    }

    private fun dec(k: ByteArray, d: ByteArray, useAes: Boolean): ByteArray {
        if (!useAes) return rc4(k, d)
        if (d.size < 32 || d.size % 16 != 0) return if (d.size < 16) ByteArray(0) else try {
            val c = Cipher.getInstance("AES/CBC/NoPadding"); c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(d.copyOf(16)))
            c.doFinal(d, 16, (d.size - 16) / 16 * 16)
        } catch (_: Exception) { d }
        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding"); c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(d.copyOf(16)))
            c.doFinal(d, 16, d.size - 16)
        } catch (_: Exception) {
            val c = Cipher.getInstance("AES/CBC/NoPadding"); c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(d.copyOf(16)))
            c.doFinal(d, 16, d.size - 16)
        }
    }

    fun decryptObj(o: Any?, n: Int, g: Int): Any? {
        val k by lazy { objKey(n, g) }
        fun walk(x: Any?): Any? {
            when (x) {
                is PStr -> if (strF != "Identity") x.b = dec(k, x.b, aes)
                is List<*> -> x.forEach { walk(it) }
                is PDict -> x.m.values.forEach { walk(it) }
                is PStream -> {
                    walk(x.dict)
                    val t = x.dict.name("Type")
                    val skip = t == "XRef" || (t == "Metadata" && !encMeta) || stmF == "Identity" ||
                        (x.dict["Filter"] as? List<*>)?.any { (it as? PName)?.n == "Crypt" } == true || x.dict.name("Filter") == "Crypt"
                    if (!skip) x.raw = dec(k, x.raw, aes)
                }
            }
            return x
        }
        return walk(o)
    }
}
