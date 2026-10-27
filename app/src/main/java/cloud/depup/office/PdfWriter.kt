package cloud.depup.office

import android.graphics.Paint
import android.graphics.Typeface
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Penulis PDF mini: tiap halaman = satu gambar JPEG (ukuran kecil, bukan bitmap mentah) + lapisan teks
 * tak terlihat dari OCR (supaya PDF bisa dicari/disalin) + enkripsi AES-128 (password) opsional.
 * Ditulis halaman demi halaman, jadi memori tetap kecil.
 */
class PdfWriter(private val out: File, private val pageCount: Int, password: String?) {
    private val os = CountingOut(BufferedOutputStream(FileOutputStream(out), 64 * 1024))
    private val offsets = HashMap<Int, Long>()
    private val rnd = SecureRandom()
    private val fileId = ByteArray(16).also { rnd.nextBytes(it) }
    private var key: ByteArray? = null
    private var oEntry = ByteArray(0)
    private var uEntry = ByteArray(0)
    private var added = 0
    private val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF; textSize = 100f }

    private class CountingOut(private val o: java.io.OutputStream) : java.io.OutputStream() {
        var pos = 0L
        override fun write(b: Int) { o.write(b); pos++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); pos += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }

    // Nomor objek tetap: 1 katalog, 2 daftar halaman, 3 font, 4 enkripsi; halaman ke-i: 5+3i (halaman), +1 (isi), +2 (gambar)
    private fun pageObj(i: Int) = 5 + 3 * i

    private fun w(b: ByteArray) = os.write(b)
    private fun w(s: String) = os.write(s.toByteArray(Charsets.ISO_8859_1))
    private fun md5(vararg p: ByteArray): ByteArray {
        val m = MessageDigest.getInstance("MD5")
        p.forEach { m.update(it) }
        return m.digest()
    }
    private fun rc4(k: ByteArray, d: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) { j = (j + s[i] + (k[i % k.size].toInt() and 255)) and 255; val t = s[i]; s[i] = s[j]; s[j] = t }
        val o = ByteArray(d.size)
        var a = 0; var b = 0
        for (n in d.indices) {
            a = (a + 1) and 255; b = (b + s[a]) and 255
            val t = s[a]; s[a] = s[b]; s[b] = t
            o[n] = (d[n].toInt() xor s[(s[a] + s[b]) and 255]).toByte()
        }
        return o
    }

    private val PAD = intArrayOf(
        0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A
    ).map { it.toByte() }.toByteArray()

    private fun padPw(pw: ByteArray): ByteArray = (pw + PAD).copyOf(32)

    /** PDF 1.6, filter Standard, V4/R4, AESV2 (AES-128), password pengguna = password pemilik. */
    private fun setupEncryption(password: String) {
        val cs = if (password.all { it.code < 256 }) Charsets.ISO_8859_1 else Charsets.UTF_8
        val pw = padPw(password.toByteArray(cs))
        val p = -4
        val pBytes = byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte())
        // O (algoritma 3)
        var h = md5(pw)
        repeat(50) { h = md5(h) }
        val oKey = h.copyOf(16)
        var o = rc4(oKey, pw)
        for (i in 1..19) o = rc4(ByteArray(16) { (oKey[it].toInt() xor i).toByte() }, o)
        // Kunci enkripsi (algoritma 2)
        var k = md5(pw, o, pBytes, fileId)
        repeat(50) { k = md5(k.copyOf(16)) }
        val fileKey = k.copyOf(16)
        // U (algoritma 5)
        var u = rc4(fileKey, md5(PAD, fileId))
        for (i in 1..19) u = rc4(ByteArray(16) { (fileKey[it].toInt() xor i).toByte() }, u)
        key = fileKey
        oEntry = o
        uEntry = u + ByteArray(16)
    }

    private fun encrypt(objNum: Int, data: ByteArray): ByteArray {
        val k = key ?: return data
        val ok = md5(
            k, byteArrayOf(objNum.toByte(), (objNum shr 8).toByte(), (objNum shr 16).toByte(), 0, 0),
            "sAlT".toByteArray(Charsets.ISO_8859_1)
        ).copyOf(16)
        val iv = ByteArray(16).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ok, "AES"), IvParameterSpec(iv))
        return iv + c.doFinal(data)
    }

    private fun begin(n: Int) { offsets[n] = os.pos; w("$n 0 obj\n") }
    private fun end() = w("\nendobj\n")

    private fun stream(n: Int, dict: String, data: ByteArray) {
        val d = encrypt(n, data)
        begin(n)
        w("<< $dict /Length ${d.size} >>\nstream\n")
        w(d)
        w("\nendstream")
        end()
    }

    private fun hex(b: ByteArray) = "<" + b.joinToString("") { "%02x".format(it) } + ">"

    private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.2f", v)

    /** Teks tak terlihat (render mode 3) dalam WinAnsi; karakter di luar Latin-1 diganti '?'. */
    private fun winAnsi(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val ch = if (c.code in 32..255) c else if (c == '\t') ' ' else '?'
            when (ch) {
                '\\', '(', ')' -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Tambah satu halaman. [jpeg] sudah final (px jpegW x jpegH), digambar di [imgX, imgY, imgW, imgH] (titik PDF, asal kiri-atas).
     * [ocr] (opsional) memakai koordinat piksel gambar yang sama.
     */
    fun addPage(
        jpeg: ByteArray, jpegW: Int, jpegH: Int, pageW: Float, pageH: Float,
        imgX: Float, imgY: Float, imgW: Float, imgH: Float, ocr: OcrPage?
    ) {
        val i = added++
        val pg = pageObj(i); val ct = pg + 1; val im = pg + 2

        stream(im, "/Type /XObject /Subtype /Image /Width $jpegW /Height $jpegH /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpeg)

        val sb = StringBuilder()
        sb.append("q ${fmt(imgW)} 0 0 ${fmt(imgH)} ${fmt(imgX)} ${fmt(pageH - imgY - imgH)} cm /Im0 Do Q\n")
        if (ocr != null && ocr.lines.isNotEmpty() && jpegW > 0 && jpegH > 0) {
            val sx = imgW / jpegW; val sy = imgH / jpegH
            sb.append("BT 3 Tr\n")
            for (ln in ocr.lines) {
                val fs = (ln.box.height() * sy * 0.85f).coerceIn(4f, 72f)
                val natural = measure.measureText(ln.text) / 100f * fs
                val boxW = ln.box.width() * sx
                val tz = if (natural > 0f) (boxW / natural * 100f).coerceIn(20f, 400f) else 100f
                val x = imgX + ln.box.left * sx
                val y = pageH - (imgY + ln.box.bottom * sy) + fs * 0.2f
                sb.append("/F1 ${fmt(fs)} Tf ${fmt(tz)} Tz 1 0 0 1 ${fmt(x)} ${fmt(y)} Tm (${winAnsi(ln.text)}) Tj\n")
            }
            sb.append("ET\n")
        }
        stream(ct, "", sb.toString().toByteArray(Charsets.ISO_8859_1))

        begin(pg)
        w("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pageW)} ${fmt(pageH)}] " +
            "/Resources << /XObject << /Im0 $im 0 R >> /Font << /F1 3 0 R >> >> /Contents $ct 0 R >>")
        end()
    }

    fun finish() {
        check(added == pageCount) { "Jumlah halaman tidak cocok" }
        begin(1); w("<< /Type /Catalog /Pages 2 0 R >>"); end()
        begin(2)
        w("<< /Type /Pages /Count $pageCount /Kids [" + (0 until pageCount).joinToString(" ") { "${pageObj(it)} 0 R" } + "] >>")
        end()
        begin(3); w("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"); end()
        val enc = key != null
        if (enc) {
            begin(4)
            w("<< /Filter /Standard /V 4 /R 4 /Length 128 /CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV2 /Length 16 >> >> " +
                "/StmF /StdCF /StrF /StdCF /O ${hex(oEntry)} /U ${hex(uEntry)} /P -4 >>")
            end()
        } else {
            begin(4); w("<< >>"); end()
        }
        val total = 5 + 3 * pageCount
        val xref = os.pos
        w("xref\n0 $total\n0000000000 65535 f \n")
        for (n in 1 until total) w(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets[n] ?: 0L))
        w("trailer\n<< /Size $total /Root 1 0 R " + (if (enc) "/Encrypt 4 0 R " else "") +
            "/ID [${hex(fileId)}${hex(fileId)}] >>\nstartxref\n$xref\n%%EOF\n")
        os.flush(); os.close()
    }

    fun abort() { try { os.close() } catch (_: Exception) {}; out.delete() }

    // Harus paling bawah: semua properti (PAD, key, dst.) sudah terinisialisasi saat blok ini jalan.
    init {
        if (!password.isNullOrEmpty()) setupEncryption(password)
        w("%PDF-1.6\n%\u00E2\u00E3\u00CF\u00D3\n".toByteArray(Charsets.ISO_8859_1))
    }
}
