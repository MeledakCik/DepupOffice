package cloud.depup.office

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Paket biner Dokumen Terlindungi (murni JVM, tanpa API Android — diuji di unit test dan identik dengan
 * `SecurePackage` di iOS).
 *
 * Tiga versi bisa DIBACA; hanya v3 yang DIBUAT:
 *  - v1 (DepUp 4.1.0): kunci = HMAC(rahasia aplikasi, prefiks + docId). Kunci tersimpan (tersamar) di aplikasi.
 *  - v2 (build pengembangan "khusus perangkat ini"): kunci dari Android Keystore perangkat pembuat.
 *  - v3: kunci diturunkan dari KODE AKSES yang dibagikan guru (PBKDF2-HMAC-SHA256 → HKDF-SHA256).
 *    Tidak ada kunci di dalam aplikasi, jadi membongkar APK/IPA tidak membuka dokumen.
 *
 * Tata letak body (semua panjang = int32 big-endian):
 *   marker(19 ASCII) | len,docId(36) | len,sha256Hex(64) | len,salt(16) | len,nonce(12) | len,editHashHex
 *   | [v3: iterations int32] | editLocked(1) | len,ciphertext+tag
 * Paket = body | HMAC-SHA256(macKey, body).
 *
 * v3: ikm = PBKDF2(normalize(kode), salt || "DepUp-v3-Access", iterations, 32)
 *     encKey = HKDF(ikm, salt, "DepUp-v3-AES-GCM-Key"), macKey = HKDF(ikm, salt, "DepUp-v3-Package-HMAC-Key")
 *     AAD AES-GCM = "DEPUP_SECURE_DOC_v3" | 0x03 | len,docId | iterations | editLocked
 */
internal object SecurePackage {
    const val MARKER_V1 = "DEPUP_SECURE_DOC_v1"
    const val MARKER_V2 = "DEPUP_SECURE_DOC_v2"
    const val MARKER_V3 = "DEPUP_SECURE_DOC_v3"
    private const val MARKER_LEN = 19
    const val HMAC_LEN = 32
    const val SALT_LEN = 16
    const val NONCE_LEN = 12
    const val V3_ITERATIONS = 600_000
    const val V3_MIN_ITERATIONS = 100_000
    const val V3_MAX_ITERATIONS = 5_000_000
    const val EDIT_ITERATIONS = 65_536
    const val MIN_CODE_LENGTH = 8
    const val MAX_CODE_LENGTH = 64
    private val V3_PBKDF2_DOMAIN = "DepUp-v3-Access".toByteArray(Charsets.US_ASCII)
    val V2_ENC_INFO = "DepUp-v2-AES-GCM-Key".toByteArray(Charsets.US_ASCII)
    val V2_MAC_INFO = "DepUp-v2-Package-HMAC-Key".toByteArray(Charsets.US_ASCII)
    val V3_ENC_INFO = "DepUp-v3-AES-GCM-Key".toByteArray(Charsets.US_ASCII)
    val V3_MAC_INFO = "DepUp-v3-Package-HMAC-Key".toByteArray(Charsets.US_ASCII)
    /** Alfabet Crockford Base32 tanpa I, L, O, U — tidak tertukar saat diketik dari kertas. */
    private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    class Meta(
        val version: Int,
        val docId: String,
        val originalSha256: String,
        val salt: ByteArray,
        val nonce: ByteArray,
        val editPasswordHash: String,
        val iterations: Int,
        val editLocked: Boolean,
        /** Offset field panjang ciphertext di dalam body. */
        val payloadOffset: Int
    ) {
        fun wipe() { salt.fill(0); nonce.fill(0) }
    }

    /** Kode akses salah / tidak ada: UI boleh meminta ulang, bukan "dokumen rusak". */
    class AccessCodeException(message: String, val missing: Boolean) : SecurityException(message)

    // ---------------------------------------------------------------- kode akses

    /** 12 karakter acak (60 bit) dalam 3 kelompok, mis. "7KQ2-M9XD-4RTW". */
    fun generateAccessCode(rng: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder(14)
        for (i in 0 until 12) {
            if (i > 0 && i % 4 == 0) sb.append('-')
            sb.append(CODE_ALPHABET[rng.nextInt(CODE_ALPHABET.length)])
        }
        return sb.toString()
    }

    /** Bentuk kanonis: NFKC, huruf besar, spasi & tanda hubung dibuang. Sama persis dengan iOS. */
    fun normalizeCode(code: String): String {
        val n = Normalizer.normalize(code.trim(), Normalizer.Form.NFKC).uppercase(java.util.Locale.ROOT)
        val sb = StringBuilder(n.length)
        for (ch in n) if (ch != ' ' && ch != '-' && ch != ' ' && ch != '\t') sb.append(ch)
        return sb.toString()
    }

    fun validateNewCode(code: String): String? {
        val n = normalizeCode(code)
        return when {
            n.length < MIN_CODE_LENGTH -> "Kode akses minimal $MIN_CODE_LENGTH karakter."
            n.length > MAX_CODE_LENGTH -> "Kode akses maksimal $MAX_CODE_LENGTH karakter."
            else -> null
        }
    }

    // ---------------------------------------------------------------- pembuatan v3

    fun buildV3(
        plain: ByteArray,
        accessCode: String,
        editPassword: String,
        docId: String,
        iterations: Int = V3_ITERATIONS,
        rng: SecureRandom = SecureRandom(),
        salt: ByteArray = ByteArray(SALT_LEN).also { rng.nextBytes(it) },
        nonce: ByteArray = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
    ): ByteArray {
        require(UUID_RE.matches(docId)) { "ID dokumen tidak valid." }
        require(salt.size == SALT_LEN && nonce.size == NONCE_LEN)
        require(iterations in V3_MIN_ITERATIONS..V3_MAX_ITERATIONS)
        validateNewCode(accessCode)?.let { throw IllegalArgumentException(it) }
        val sha = sha256Hex(plain)
        val editHash = hashEditPassword(editPassword, salt)
        val ikm = v3Ikm(accessCode, salt, iterations)
        val encKey = hkdfSha256(ikm, salt, V3_ENC_INFO)
        val macKey = hkdfSha256(ikm, salt, V3_MAC_INFO)
        var cipherText: ByteArray? = null
        var body: ByteArray? = null
        try {
            val aad = aad(MARKER_V3, 3, docId, iterations, true)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(128, nonce))
            c.updateAAD(aad)
            cipherText = c.doFinal(plain)
            val out = java.io.ByteArrayOutputStream(cipherText.size + 256)
            out.write(MARKER_V3.toByteArray(Charsets.US_ASCII))
            field(out, docId.toByteArray(Charsets.US_ASCII))
            field(out, sha.toByteArray(Charsets.US_ASCII))
            field(out, salt)
            field(out, nonce)
            field(out, editHash.toByteArray(Charsets.US_ASCII))
            out.write(int32(iterations))
            out.write(1)
            field(out, cipherText)
            body = out.toByteArray()
            val tag = hmacSha256(macKey, body)
            return body + tag
        } finally {
            ikm.fill(0); encKey.fill(0); macKey.fill(0); cipherText?.fill(0); body?.fill(0)
        }
    }

    internal fun v3Ikm(accessCode: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = normalizeCode(accessCode).toCharArray()
        val spec = PBEKeySpec(chars, salt + V3_PBKDF2_DOMAIN, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); chars.fill('\u0000') }
    }

    // ---------------------------------------------------------------- pembacaan

    /** Pisahkan body & tag HMAC. Body adalah SALINAN (aman dihapus pemanggil). */
    fun split(pkg: ByteArray): Pair<ByteArray, ByteArray> {
        if (pkg.size < MARKER_LEN + HMAC_LEN) throw SecurityException("Data paket terlindungi tidak lengkap.")
        return pkg.copyOfRange(0, pkg.size - HMAC_LEN) to pkg.copyOfRange(pkg.size - HMAC_LEN, pkg.size)
    }

    fun version(body: ByteArray): Int {
        if (body.size < MARKER_LEN) throw SecurityException("Data paket terlindungi tidak lengkap.")
        return when (String(body, 0, MARKER_LEN, Charsets.ISO_8859_1)) {
            MARKER_V1 -> 1
            MARKER_V2 -> 2
            MARKER_V3 -> 3
            else -> throw SecurityException("Penanda Dokumen Terlindungi tidak valid.")
        }
    }

    /** Validasi ketat: data berasal dari berkas pihak luar. */
    fun parseMeta(body: ByteArray): Meta {
        val v = version(body)
        var off = MARKER_LEN
        fun next(expected: IntRange): ByteArray {
            val n = readInt(body, off); off += 4
            if (n !in expected || n > body.size - off) throw SecurityException("Data paket terlindungi rusak.")
            return body.copyOfRange(off, off + n).also { off += n }
        }
        val docId = String(next(36..36), Charsets.US_ASCII)
        val sha = String(next(64..64), Charsets.US_ASCII)
        val salt = next(SALT_LEN..SALT_LEN)
        val nonce = next(NONCE_LEN..NONCE_LEN)
        val edit = String(next(if (v == 1) 0..128 else 64..64), Charsets.US_ASCII)
        var iterations = 0
        if (v == 3) {
            iterations = readInt(body, off); off += 4
            if (iterations !in V3_MIN_ITERATIONS..V3_MAX_ITERATIONS) throw SecurityException("Parameter kunci dokumen tidak valid.")
        }
        if (off >= body.size) throw SecurityException("Data paket terlindungi rusak.")
        val flag = body[off].toInt() and 0xFF; off += 1
        if (flag > 1) throw SecurityException("Flag dokumen tidak valid.")
        val payloadOffset = off
        val len = readInt(body, off)
        if (len < 16 || off.toLong() + 4L + len != body.size.toLong()) throw SecurityException("Data paket terlindungi rusak.")
        if (!UUID_RE.matches(docId) || !HEX64.matches(sha) || (v != 1 && !HEX64.matches(edit)))
            throw SecurityException("Metadata Dokumen Terlindungi tidak valid.")
        return Meta(v, docId, sha, salt, nonce, edit, iterations, flag == 1, payloadOffset)
    }

    /**
     * Verifikasi HMAC (konstan-waktu), dekripsi, cek SHA-256. [macKey] dan [encKey] milik pemanggil.
     * Mengembalikan null bila HMAC tidak cocok (kunci/kode salah atau berkas diubah).
     */
    fun openWithKeys(body: ByteArray, tag: ByteArray, meta: Meta, encKey: ByteArray, macKey: ByteArray): ByteArray? {
        val expected = hmacSha256(macKey, body)
        val ok = MessageDigest.isEqual(expected, tag)
        expected.fill(0)
        if (!ok) return null
        val len = readInt(body, meta.payloadOffset)
        val ct = body.copyOfRange(meta.payloadOffset + 4, meta.payloadOffset + 4 + len)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(128, meta.nonce))
            when (meta.version) {
                2 -> c.updateAAD(aad(MARKER_V2, 2, meta.docId, null, meta.editLocked))
                3 -> c.updateAAD(aad(MARKER_V3, 3, meta.docId, meta.iterations, meta.editLocked))
            }
            val plain = try { c.doFinal(ct) } catch (e: Exception) { throw SecurityException("Dekripsi dokumen gagal.") }
            if (sha256Hex(plain) != meta.originalSha256) {
                plain.fill(0)
                throw SecurityException("Integritas dokumen gagal diverifikasi (SHA-256 tidak cocok).")
            }
            return plain
        } finally { ct.fill(0) }
    }

    /** Buka paket v3 dengan kode akses. Melempar [AccessCodeException] bila kode salah. */
    fun openV3(body: ByteArray, tag: ByteArray, meta: Meta, accessCode: String?): ByteArray {
        if (accessCode.isNullOrBlank()) throw AccessCodeException("Masukkan kode akses dari pembuat dokumen.", missing = true)
        val ikm = v3Ikm(accessCode, meta.salt, meta.iterations)
        val enc = hkdfSha256(ikm, meta.salt, V3_ENC_INFO)
        val mac = hkdfSha256(ikm, meta.salt, V3_MAC_INFO)
        try {
            return openWithKeys(body, tag, meta, enc, mac)
                ?: throw AccessCodeException("Kode akses salah, atau dokumen telah diubah di luar DepUp Office.", missing = false)
        } finally { ikm.fill(0); enc.fill(0); mac.fill(0) }
    }

    /** Cocokkan password edit guru dengan hash tersimpan (konstan-waktu). Hash v1 memakai skema yang sama. */
    fun editPasswordMatches(meta: Meta, password: String): Boolean {
        if (password.isEmpty() || meta.editPasswordHash.isEmpty()) return false
        val a = hashEditPassword(password, meta.salt).toByteArray(Charsets.US_ASCII)
        val b = meta.editPasswordHash.toByteArray(Charsets.US_ASCII)
        return try { MessageDigest.isEqual(a, b) } finally { a.fill(0); b.fill(0) }
    }

    // ---------------------------------------------------------------- primitif

    fun hashEditPassword(password: String, salt: ByteArray): String {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, EDIT_ITERATIONS, 256)
        val hash = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); chars.fill('\u0000') }
        return try { hex(hash) } finally { hash.fill(0) }
    }

    /** AAD AES-GCM untuk v2/v3 (v2 tanpa iterations — identik dengan format v2 lama). */
    fun aad(marker: String, version: Int, docId: String, iterations: Int?, editLocked: Boolean): ByteArray {
        val id = docId.toByteArray(Charsets.UTF_8)
        return java.io.ByteArrayOutputStream().apply {
            write(marker.toByteArray(Charsets.US_ASCII))
            write(version)
            write(int32(id.size)); write(id)
            if (iterations != null) write(int32(iterations))
            write(if (editLocked) 1 else 0)
        }.toByteArray().also { id.fill(0) }
    }

    /** RFC 5869 HKDF-SHA256. Hasil milik pemanggil dan harus dihapus. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        require(length in 1..(255 * 32))
        val prk = hmacSha256(salt, ikm)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        try {
            var counter = 1
            while (offset < length) {
                val input = previous + info + byteArrayOf(counter.toByte())
                val next = hmacSha256(prk, input)
                input.fill(0); previous.fill(0)
                previous = next
                val count = minOf(next.size, length - offset)
                next.copyInto(output, offset, 0, count)
                offset += count
                counter++
            }
            return output
        } catch (t: Throwable) {
            output.fill(0); throw t
        } finally { prk.fill(0); previous.fill(0) }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return try { hex(d) } finally { d.fill(0) }
    }

    private fun hex(b: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append(digits[v ushr 4]).append(digits[v and 15]) }
        return sb.toString()
    }

    private fun field(out: java.io.ByteArrayOutputStream, b: ByteArray) { out.write(int32(b.size)); out.write(b) }

    fun int32(v: Int): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    fun readInt(arr: ByteArray, off: Int): Int {
        if (off < 0 || off.toLong() + 4L > arr.size) throw SecurityException("Data paket terlindungi rusak.")
        return ((arr[off].toInt() and 0xFF) shl 24) or ((arr[off + 1].toInt() and 0xFF) shl 16) or
            ((arr[off + 2].toInt() and 0xFF) shl 8) or (arr[off + 3].toInt() and 0xFF)
    }
}
