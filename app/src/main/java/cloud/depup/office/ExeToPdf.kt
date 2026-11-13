package cloud.depup.office

/**
 * EXE → PDF: mengambil kembali PDF yang dibungkus jadi program Windows (.exe) oleh pengubah "PDF to EXE" (mis.
 * VaySoft). Program semacam itu tidak bisa dibuka di HP; PDF aslinya tersimpan di ujung berkas (overlay setelah
 * section PE terakhir), biasanya disamarkan dengan XOR berulang 12 byte.
 *
 * Urutan percobaan:
 *  1. PDF polos di overlay (tanpa penyamaran).
 *  2. Kunci XOR VaySoft yang sudah dikenal.
 *  3. Kunci tak dikenal: dipulihkan dari pola tabel xref PDF (baris 20 byte berisi spasi, spasi, CR, LF di posisi
 *     tetap → byte kunci di posisi itu terbaca), dilengkapi dari kata "xref" & kepala "%PDF-1.", sisanya dicoba.
 *
 * Murni Kotlin (tanpa Android) agar bisa diuji di JVM.
 */
object ExeToPdf {
    class Result(val pdf: ByteArray, val method: String, val keyHex: String?)

    private const val K = 12
    private val VAYSOFT = byteArrayOf(0x00, 0x0B, 0x0C, 0x04, 0x0D, 0x03, 0x04, 0x0B, 0x0C, 0x03, 0x06, 0x02)
    private val MAGIC = "%PDF-".toByteArray()

    /** Awal overlay (sesudah section PE terakhir); 0 bila bukan berkas PE. */
    fun overlayStart(d: ByteArray): Int {
        if (d.size < 0x40 || d[0] != 'M'.code.toByte() || d[1] != 'Z'.code.toByte()) return 0
        val pe = u32(d, 0x3C)
        if (pe < 0 || pe + 24 > d.size || d[pe] != 'P'.code.toByte() || d[pe + 1] != 'E'.code.toByte() || d[pe + 2] != 0.toByte() || d[pe + 3] != 0.toByte()) return 0
        val nSec = u16(d, pe + 6)
        val opt = u16(d, pe + 20)
        val tab = pe + 24 + opt
        var end = 0
        for (i in 0 until nSec) {
            val s = tab + i * 40
            if (s + 40 > d.size) break
            val size = u32(d, s + 16); val raw = u32(d, s + 20)
            if (size >= 0 && raw >= 0) end = maxOf(end, raw + size)
        }
        return end.coerceIn(0, d.size)
    }

    fun extract(data: ByteArray): Result {
        val start = overlayStart(data)
        val ov = if (start in 1 until data.size) data.copyOfRange(start, data.size) else data
        // 1) PDF polos
        indexOf(ov, MAGIC, 0).let { if (it >= 0) trim(ov.copyOfRange(it, ov.size))?.let { p -> return Result(p, "PDF tanpa penyamaran", null) } }
        // 2) kunci VaySoft
        val vs = xor(ov, VAYSOFT, 0)
        if (startsWith(vs, MAGIC)) trim(vs)?.let { return Result(it, "Kunci VaySoft", hex(VAYSOFT)) }
        // 3) pulihkan kunci dari tabel xref
        recover(ov)?.let { return it }
        throw IllegalStateException("PDF tidak ditemukan di berkas ini. Mungkin bukan hasil \"PDF to EXE\", atau isinya dikunci kata sandi.")
    }

    // ---------- pemulihan kunci ----------

    /** Posisi di baris xref ("0000000000 65535 f\r\n") yang isinya pasti: spasi, spasi, CR, LF. */
    private val KNOWN = intArrayOf(10, 16, 18, 19)
    /** Akhir baris xref bisa "\r\n" atau " \n" (keduanya 2 byte). */
    private val PLAINS = arrayOf(intArrayOf(0x20, 0x20, 0x0D, 0x0A), intArrayOf(0x20, 0x20, 0x20, 0x0A))

    private fun recover(d: ByteArray): Result? {
        val n = d.size
        val stride = 60          // KPK baris 20 byte & kunci 12 byte: posisi kunci sama tiap 3 baris
        val minRows = 8
        var o = 0
        var tried = 0
        while (o < n - minRows * stride - 20 && tried < 5000) {
            var ok = true
            loop@ for (p in KNOWN) {
                val ref = d[o + p]
                for (k in 1 until minRows) if (d[o + p + k * stride] != ref) { ok = false; break@loop }
            }
            if (ok) {
                tried++
                for (pl in PLAINS) tryTable(d, o, pl)?.let { return it }
            }
            o++
        }
        return null
    }

    private fun tryTable(d: ByteArray, o: Int, PLAIN: IntArray): Result? {
        val key = arrayOfNulls<Int>(K)
        // baris berurutan (20 byte) — tiap baris menggeser fase kunci, jadi 3 baris pertama sudah mengisi 12 posisi
        var rows = 0
        while (o + rows * 20 + 20 <= d.size && rows < 60) {
            val base = o + rows * 20
            var good = true
            for (i in KNOWN.indices) {
                val ki = Math.floorMod(base + KNOWN[i], K)
                val kb = (d[base + KNOWN[i]].toInt() and 0xFF) xor PLAIN[i]
                val prev = key[ki]
                if (prev != null && prev != kb) { good = false; break }
                key[ki] = kb
            }
            if (!good) { if (rows < 3) return null; break }
            rows++
        }
        // saring kebetulan (data biner berpola): dengan kunci sejauh ini, baris harus benar-benar berbentuk entri xref
        for (r in 0 until minOf(rows, 6)) {
            val base = o + r * 20
            for (pos in 0 until 18) {
                val k = key[Math.floorMod(base + pos, K)] ?: continue
                val c = (d[base + pos].toInt() and 0xFF) xor k
                val fine = when (pos) {
                    10, 16 -> c == 0x20
                    17 -> c == 'n'.code || c == 'f'.code
                    else -> c in 0x30..0x39
                }
                if (!fine) return null
            }
        }
        // "xref\n" / "xref\r\n" tepat sebelum baris pertama (setelah baris subjudul "0 N")
        fillFrom(d, o, key, "xref".toByteArray())
        val pdfAt = findPdfStart(d, key) ?: return null
        // kepala "%PDF-1." melengkapi posisi yang masih kosong
        val head = "%PDF-1.".toByteArray()
        for (j in head.indices) { val ki = Math.floorMod(pdfAt + j, K); if (key[ki] == null) key[ki] = (d[pdfAt + j].toInt() and 0xFF) xor head[j].toInt() }
        val missing = (0 until K).filter { key[it] == null }
        if (missing.size > 2) return null
        val total = 1 shl (8 * missing.size)
        for (combo in 0 until total) {
            val kb = ByteArray(K) { i -> (key[i] ?: 0).toByte() }
            missing.forEachIndexed { idx, ki -> kb[ki] = ((combo shr (8 * idx)) and 0xFF).toByte() }
            // cek murah dulu: 2 KB pertama harus berkepala PDF & memuat "obj"
            val headLen = minOf(2048, d.size - pdfAt)
            val head = ByteArray(headLen) { (d[pdfAt + it].toInt() xor kb[Math.floorMod(pdfAt + it, K)].toInt()).toByte() }
            if (!startsWith(head, MAGIC) || indexOf(head, "obj".toByteArray(), 0) < 0) continue
            val out = xorFrom(d, kb, pdfAt)
            val pdf = trim(out) ?: continue
            // kunci disejajarkan ke awal PDF untuk ditampilkan
            val aligned = ByteArray(K) { kb[Math.floorMod(pdfAt + it, K)] }
            return Result(pdf, "Kunci dipulihkan dari tabel xref", hex(aligned))
        }
        return null
    }

    /** Cari kata [word] (+ baris baru) sedikit sebelum tabel; cocokkan dengan kunci yang sudah ada lalu lengkapi. */
    private fun fillFrom(d: ByteArray, o: Int, key: Array<Int?>, word: ByteArray) {
        // baris "0 N\r\n" di antara "xref" dan entri: cari mundur hingga 24 byte
        for (back in 5..40) {
            val s = o - back
            if (s < 0) break
            var consistent = true
            val add = HashMap<Int, Int>()
            for (j in word.indices) {
                val ki = Math.floorMod(s + j, K)
                val kb = (d[s + j].toInt() and 0xFF) xor word[j].toInt()
                val have = key[ki] ?: add[ki]
                if (have != null && have != kb) { consistent = false; break }
                add[ki] = kb
            }
            if (consistent && add.keys.any { key[it] == null } && add.keys.count { key[it] != null } >= 2) {
                for ((k, v) in add) key[k] = v
                return
            }
        }
    }

    private fun findPdfStart(d: ByteArray, key: Array<Int?>): Int? {
        val n = d.size
        for (i in 0 until n - 8) {
            var ok = true; var known = 0
            for (j in MAGIC.indices) {
                val k = key[Math.floorMod(i + j, K)] ?: continue
                if (((d[i + j].toInt() and 0xFF) xor k) != MAGIC[j].toInt()) { ok = false; break }
                known++
            }
            if (ok && known >= 3) return i
        }
        return null
    }

    // ---------- utilitas ----------

    /** Potong tepat sesudah "%%EOF" terakhir (+ baris barunya). */
    private fun trim(p: ByteArray): ByteArray? {
        val eof = lastIndexOf(p, "%%EOF".toByteArray())
        if (eof < 64) return null
        var end = eof + 5
        while (end < p.size && (p[end] == 0x0A.toByte() || p[end] == 0x0D.toByte())) end++
        return p.copyOf(end)
    }

    private fun xor(d: ByteArray, k: ByteArray, phase: Int) = ByteArray(d.size) { (d[it].toInt() xor k[Math.floorMod(it + phase, K)].toInt()).toByte() }
    /** Dekripsi mulai [from] dengan kunci berindeks posisi mutlak. */
    private fun xorFrom(d: ByteArray, k: ByteArray, from: Int) = ByteArray(d.size - from) { (d[from + it].toInt() xor k[Math.floorMod(from + it, K)].toInt()).toByte() }
    private fun startsWith(a: ByteArray, p: ByteArray) = a.size >= p.size && p.indices.all { a[it] == p[it] }
    private fun indexOf(a: ByteArray, p: ByteArray, from: Int, limit: Int = a.size): Int {
        val end = minOf(a.size, limit) - p.size
        var i = from
        while (i <= end) { var j = 0; while (j < p.size && a[i + j] == p[j]) j++; if (j == p.size) return i; i++ }
        return -1
    }
    private fun lastIndexOf(a: ByteArray, p: ByteArray): Int {
        var i = a.size - p.size
        while (i >= 0) { var j = 0; while (j < p.size && a[i + j] == p[j]) j++; if (j == p.size) return i; i-- }
        return -1
    }
    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(d: ByteArray, o: Int): Int = if (o + 4 > d.size) -1 else
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or ((d[o + 2].toInt() and 0xFF) shl 16) or ((d[o + 3].toInt() and 0xFF) shl 24)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
