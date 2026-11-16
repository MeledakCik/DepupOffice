package cloud.depup.office

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tulis ulang PDF dari [PdfFile] TANPA merender ulang halaman: teks, font, vektor, dan foto tetap asli (beda dengan
 * PdfTools.rebuild yang mengubah halaman jadi gambar). Dipakai untuk:
 *  - kunci password AES-256 (Standard Security Handler V5/R6, dibuka Adobe Reader, Chrome, Depup, dll) + izin
 *    cetak/salin/ubah,
 * Object stream & xref stream dibuka jadi objek biasa + tabel xref klasik (paling luas didukung).
 */
object PdfSave {

    /** Izin dokumen terkunci (berlaku di pembaca yang menghormati izin PDF, mis. Adobe Reader). */
    class Perms(val print: Boolean = true, val copy: Boolean = true, val modify: Boolean = true, val annotate: Boolean = true) {
        /** Nilai /P (ISO 32000-2 tabel 22): bit cadangan 7-8 & 13-32 = 1. */
        fun p(): Int {
            var v = 0xFFFFF0C0.toInt()
            if (print) v = v or (1 shl 2) or (1 shl 11)
            if (modify) v = v or (1 shl 3) or (1 shl 10)
            if (copy) v = v or (1 shl 4) or (1 shl 9)
            if (annotate) v = v or (1 shl 5) or (1 shl 8)
            if (!copy) v = v or (1 shl 9)    // aksesibilitas (pembaca layar) tetap diizinkan
            return v
        }
    }

    class Lock(val userPw: String, val ownerPw: String, val perms: Perms)

    /**
     * Tulis [f] ke [out]. [edit] boleh mengubah objek (Catalog, Info) sebelum ditulis; kembalikan Info baru (atau null =
     * tanpa Info). [lock] != null → dienkripsi AES-256.
     */
    fun write(f: PdfFile, out: OutputStream, lock: Lock? = null, edit: ((Ctx) -> Unit)? = null) {
        val ctx = Ctx(f)
        edit?.invoke(ctx)
        // AES-256 R6 = ekstensi Adobe tingkat 8 di atas PDF 1.7 (Acrobat/Reader X ke atas)
        if (lock != null) ctx.catalog["Extensions"] = PDict().also { e ->
            e["ADBE"] = PDict().also { it["BaseVersion"] = PName("1.7"); it["ExtensionLevel"] = 8L }
        }
        val rnd = SecureRandom()
        val id = (f.trailer["ID"] as? List<*>)?.mapNotNull { it as? PStr }?.takeIf { it.size == 2 }
            ?: ByteArray(16).also { rnd.nextBytes(it) }.let { listOf(PStr(it, true), PStr(it.copyOf(), true)) }
        val enc = lock?.let { Aes256(it, rnd) }
        val skip = HashSet<Int>()
        f.encryptRef?.let { skip.add(it.num) }
        val nums = ArrayList<Int>()
        for (n in f.objectNumbers()) {
            if (n in skip) continue
            val o = ctx.objs[n] ?: f.obj(n)
            val t = (o as? PStream)?.dict?.name("Type")
            if (t == "XRef" || t == "ObjStm") continue
            if (o == null) continue
            nums.add(n)
        }
        for (n in ctx.objs.keys) if (n !in nums) nums.add(n)
        nums.sort()
        var maxN = nums.maxOrNull() ?: 0
        val encN = if (enc != null) ++maxN else -1

        val w = Counting(out)
        val ver = if (enc != null) "1.7" else maxOf(f.version, "1.4")
        w.ascii("%PDF-$ver\n%âãÏÓ\n")
        val offs = HashMap<Int, Long>()
        val ser = Ser(enc)
        for (n in nums) {
            val o = ctx.objs[n] ?: f.obj(n)
            val g = if (ctx.objs.containsKey(n)) 0 else f.genOf(n)
            offs[n] = w.n
            w.ascii("$n $g obj\n")
            ser.objNum = n; ser.gen = g
            if (o is PStream) {
                val body = enc?.encrypt(o.raw) ?: o.raw
                val d = PDict(LinkedHashMap(o.dict.m))
                d["Length"] = body.size.toLong()
                ser.write(w, d)
                w.ascii("\nstream\n"); w.write(body); w.ascii("\nendstream")
            } else ser.write(w, o)
            w.ascii("\nendobj\n")
        }
        if (enc != null) {
            offs[encN] = w.n
            w.ascii("$encN 0 obj\n")
            Ser(null).write(w, enc.dict())
            w.ascii("\nendobj\n")
        }
        val xref = w.n
        val size = maxN + 1
        w.ascii("xref\n0 $size\n")
        val sb = StringBuilder()
        sb.append("0000000000 65535 f\r\n")
        for (n in 1 until size) {
            val o = offs[n]
            if (o == null) sb.append("0000000000 00001 f\r\n")
            else sb.append(String.format("%010d %05d n\r\n", o, if (ctx.objs.containsKey(n) || n == encN) 0 else f.genOf(n)))
            if (sb.length > 64000) { w.ascii(sb.toString()); sb.setLength(0) }
        }
        w.ascii(sb.toString())
        val tr = PDict()
        tr["Size"] = size.toLong()
        tr["Root"] = f.trailer["Root"]
        ctx.infoRef?.let { tr["Info"] = it } ?: f.trailer["Info"]?.let { if (!ctx.dropInfo) tr["Info"] = it }
        if (enc != null) tr["Encrypt"] = PRef(encN, 0)
        tr["ID"] = id
        w.ascii("trailer\n")
        Ser(null).write(w, tr)
        w.ascii("\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }

    /** Wadah perubahan sebelum ditulis: objek baru/diganti (nomor → objek). */
    class Ctx(val f: PdfFile) {
        val objs = LinkedHashMap<Int, Any?>()
        var infoRef: PRef? = null
        var dropInfo = false
        private var next = (f.objectNumbers().maxOrNull() ?: 0) + 1
        fun add(o: Any?): PRef { val n = next++; objs[n] = o; return PRef(n, 0) }
        fun put(r: PRef, o: Any?) { objs[r.num] = o }
        /** Catalog yang bisa diubah (perubahan ikut ditulis). */
        val catalog: PDict by lazy {
            val r = f.trailer["Root"] as? PRef
            val c = f.catalog
            if (r != null) objs[r.num] = c
            c
        }
    }

    // ------------------------------------------------------------------ serialisasi

    private class Counting(val o: OutputStream) {
        var n = 0L
        fun write(b: ByteArray) { o.write(b); n += b.size }
        fun ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
    }

    private class Ser(val enc: Aes256?) {
        var objNum = 0; var gen = 0
        fun write(w: Counting, o: Any?) { val b = StringBuilder(); val bytes = ByteArrayOutputStream(); emit(o, bytes); w.write(bytes.toByteArray()); b.clear() }

        private fun emit(o: Any?, out: ByteArrayOutputStream) {
            fun a(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
            when (o) {
                null, PNull -> a("null")
                is Boolean -> a(if (o) "true" else "false")
                is Long, is Int -> a(o.toString())
                is Number -> a(fmt(o.toDouble()))
                is PName -> { a("/"); a(nameEsc(o.n)) }
                is PStr -> {
                    val b = enc?.encrypt(o.b) ?: o.b
                    a("<"); val sb = StringBuilder(b.size * 2); for (x in b) sb.append(HEX[(x.toInt() shr 4) and 15]).append(HEX[x.toInt() and 15]); a(sb.toString()); a(">")
                }
                is PRef -> a("${o.num} ${o.gen} R")
                is List<*> -> { a("["); var first = true; for (x in o) { if (!first) a(" "); first = false; emit(x, out) }; a("]") }
                is PDict -> {
                    a("<<")
                    for ((k, v) in o.m) { if (v == null) continue; a("/"); a(nameEsc(k)); a(" "); emit(v, out); a("\n") }
                    a(">>")
                }
                is PStream -> emit(o.dict, out)
                else -> a("null")
            }
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun fmt(d: Double): String {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        var s = String.format(java.util.Locale.US, "%.6f", d).trimEnd('0')
        if (s.endsWith(".")) s = s.dropLast(1)
        return if (s == "-0") "0" else s
    }

    private fun nameEsc(n: String): String {
        val sb = StringBuilder()
        for (b in n.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 0x21 || c > 0x7E || c == '#'.code || PdfLexer.delim(c)) sb.append('#').append(HEX[c shr 4]).append(HEX[c and 15])
            else sb.append(c.toChar())
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ AES-256 (V5 R6)

    private class Aes256(val lock: Lock, val rnd: SecureRandom) {
        val fk = ByteArray(32).also { rnd.nextBytes(it) }
        private val u: ByteArray; private val ue: ByteArray; private val o: ByteArray; private val oe: ByteArray; private val perms: ByteArray
        val p = lock.perms.p()

        init {
            fun pw(s: String) = s.toByteArray(Charsets.UTF_8).let { if (it.size > 127) it.copyOf(127) else it }
            val up = pw(lock.userPw); val op = pw(lock.ownerPw.ifEmpty { lock.userPw })
            val uvs = salt(); val uks = salt()
            u = PdfDecrypt.hashR6(up, uvs, null, 6) + uvs + uks
            ue = aesNoIv(PdfDecrypt.hashR6(up, uks, null, 6), fk)
            val ovs = salt(); val oks = salt()
            o = PdfDecrypt.hashR6(op, ovs, u, 6) + ovs + oks
            oe = aesNoIv(PdfDecrypt.hashR6(op, oks, u, 6), fk)
            val pb = ByteArray(16)
            for (i in 0 until 4) pb[i] = (p shr (8 * i)).toByte()
            for (i in 4 until 8) pb[i] = 0xFF.toByte()
            pb[8] = 'T'.code.toByte(); pb[9] = 'a'.code.toByte(); pb[10] = 'd'.code.toByte(); pb[11] = 'b'.code.toByte()
            val r4 = salt(); for (i in 12 until 16) pb[i] = r4[i - 12]
            perms = Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(fk, "AES")); doFinal(pb) }
        }

        private fun salt() = ByteArray(8).also { rnd.nextBytes(it) }
        private fun aesNoIv(k: ByteArray, d: ByteArray): ByteArray =
            Cipher.getInstance("AES/CBC/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(ByteArray(16))); doFinal(d) }

        fun encrypt(d: ByteArray): ByteArray {
            val iv = ByteArray(16).also { rnd.nextBytes(it) }
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fk, "AES"), IvParameterSpec(iv))
            return iv + c.doFinal(d)
        }

        fun dict(): PDict {
            val cf = PDict(); cf["StdCF"] = PDict().also { it["AuthEvent"] = PName("DocOpen"); it["CFM"] = PName("AESV3"); it["Length"] = 32L }
            val d = PDict()
            d["Filter"] = PName("Standard"); d["V"] = 5L; d["R"] = 6L; d["Length"] = 256L
            d["CF"] = cf; d["StmF"] = PName("StdCF"); d["StrF"] = PName("StdCF")
            d["O"] = PStr(o, true); d["U"] = PStr(u, true); d["OE"] = PStr(oe, true); d["UE"] = PStr(ue, true)
            d["Perms"] = PStr(perms, true); d["P"] = p.toLong()
            return d
        }
    }

    /** Sidik SHA-256 pendek (untuk uji). */
    fun digest(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).take(6).joinToString("") { "%02x".format(it) }
}
