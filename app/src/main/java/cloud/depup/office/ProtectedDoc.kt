package cloud.depup.office

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Penangan Dokumen Terlindungi DepUp Office.
 * - Halaman sampul: "Dokumen ini hanya bisa dibuka di DepUp Office".
 * - PDF asli dienkripsi AES-256-GCM dan disisipkan sebagai stream lampiran di dalam objek katalog PDF.
 * - Kunci baca via [KeyProvider] (per sesi).
 * - Kunci edit: PBKDF2-HMAC-SHA256 dari password guru.
 * - HMAC-SHA256 integritas file penuh.
 */
interface KeyProvider {
    fun getSessionReadEncryptionKey(docId: String): ByteArray?
}

/**
 * Kunci baca = HMAC-SHA256(rahasia aplikasi, docId). Sama di SEMUA HP yang memasang DepUp Office, tapi tidak bisa
 * dihitung dari isi berkas saja (dulu: SHA-256(docId + model HP) — docId tercetak di sampul, dan dokumen tidak bisa
 * dibuka di HP dengan model berbeda).
 */
object DefaultKeyProvider : KeyProvider {
    private val APP_SECRET = byteArrayOf(
        0x5A, 0x1F, 0x7C, 0x33, 0x0E, 0x6B, 0x52, 0x29, 0x71, 0x44, 0x18, 0x6D, 0x3B, 0x09, 0x67, 0x2E,
        0x4F, 0x15, 0x58, 0x7A, 0x23, 0x0C, 0x61, 0x36, 0x1D, 0x48, 0x75, 0x2A, 0x5E, 0x13, 0x69, 0x40
    )
    override fun getSessionReadEncryptionKey(docId: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(APP_SECRET, "HmacSHA256")) }
            .doFinal(("DepUpReadKey:v2:" + docId).toByteArray(Charsets.UTF_8))
}

/** Skema lama (v1, terikat model HP) — hanya untuk membuka dokumen yang sudah terlanjur dibuat. */
object LegacyKeyProvider : KeyProvider {
    override fun getSessionReadEncryptionKey(docId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(("DepUpSessionKey:" + docId + ":" + android.os.Build.MODEL).toByteArray(Charsets.UTF_8))
}

object ProtectedDoc {
    private const val MARKER = "DEPUP_SECURE_DOC_v1"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12

    class ProtectionMeta(
        val docId: String,
        val originalSha256: String,
        val salt: ByteArray,
        val nonce: ByteArray,
        val editPasswordHash: String
    )

    /** Cek apakah dokumen merupakan Dokumen Terlindungi DepUp via struktur PDF objek katalog. */
    fun isProtected(bytes: ByteArray): Boolean {
        return try {
            val f = PdfFile.open(bytes)
            f.catalog["DepupSecureData"] != null
        } catch (_: Exception) {
            false
        }
    }

    /** Buat password hash menggunakan PBKDF2-HMAC-SHA256 untuk password edit guru. */
    fun hashEditPassword(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 65536, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** Lindungi PDF asli: buat PDF sampul, enkripsi PDF asli dengan AES-256-GCM, bubuhkan HMAC, dan sematkan di katalog PDF. */
    fun protect(
        originalPdfBytes: ByteArray,
        editPassword: String,
        outputFile: File,
        keyProvider: KeyProvider = DefaultKeyProvider
    ): ProtectionMeta {
        val docId = java.util.UUID.randomUUID().toString()
        val originalSha256 = sha256Hex(originalPdfBytes)

        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_LENGTH).also { SecureRandom().nextBytes(it) }

        val readKeyBytes = keyProvider.getSessionReadEncryptionKey(docId) ?: throw IllegalStateException("Gagal mendapatkan kunci sesi baca")
        val readKey = SecretKeySpec(readKeyBytes, "AES")

        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, readKey, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
        val encryptedPayload = cipher.doFinal(originalPdfBytes)

        val editPasswordHash = hashEditPassword(editPassword, salt)
        val coverPdfBytes = generateCoverPdf(docId)

        val metaBytes = serializeMeta(docId, originalSha256, salt, nonce, editPasswordHash)
        val packageData = metaBytes + lenBytes(encryptedPayload.size) + encryptedPayload

        val hmacKey = SecretKeySpec(readKeyBytes, "HmacSHA256")
        val hmac = Mac.getInstance("HmacSHA256").apply { init(hmacKey) }.doFinal(packageData)
        val packageWithHmac = packageData + hmac

        val coverPdfFile = PdfFile.open(coverPdfBytes)
        FileOutputStream(outputFile).use { fos ->
            PdfSave.write(coverPdfFile, fos.buffered(1 shl 16), null) { c ->
                val dict = PDict().also { it["Subtype"] = PName("Application/DepupSecure") }
                c.catalog["DepupSecureData"] = c.add(PStream(dict, packageWithHmac))
            }
        }

        return ProtectionMeta(docId, originalSha256, salt, nonce, editPasswordHash)
    }

    /** Buka dan verifikasi Dokumen Terlindungi. Mengembalikan byte PDF asli jika valid. */
    fun openProtected(
        protectedBytes: ByteArray,
        keyProvider: KeyProvider = DefaultKeyProvider
    ): ByteArray {
        val f = PdfFile.open(protectedBytes)
        val streamRef = f.catalog["DepupSecureData"] as? PRef ?: throw SecurityException("Bukan Dokumen Terlindungi DepUp Office.")
        val stream = f.res(streamRef) as? PStream ?: throw SecurityException("Data lampiran terlindungi rusak.")
        val packageWithHmac = f.decode(stream, null)

        if (packageWithHmac.size < 32) throw SecurityException("Data paket terlindungi tidak lengkap.")

        val hmacSize = 32
        val packageData = packageWithHmac.copyOfRange(0, packageWithHmac.size - hmacSize)
        val storedHmac = packageWithHmac.copyOfRange(packageWithHmac.size - hmacSize, packageWithHmac.size)

        val meta = deserializeMeta(packageData)
        fun macOk(k: ByteArray) = MessageDigest.isEqual(
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(packageData), storedHmac)
        // kunci v2 dulu; dokumen lama (v1) dibuat dengan kunci terikat model HP
        val readKeyBytes = listOfNotNull(
            keyProvider.getSessionReadEncryptionKey(meta.docId),
            if (keyProvider === DefaultKeyProvider) LegacyKeyProvider.getSessionReadEncryptionKey(meta.docId) else null
        ).firstOrNull { macOk(it) }
            ?: throw SecurityException("Dokumen telah dimodifikasi di luar DepUp Office, atau dibuat dengan versi lama di HP model lain.")

        val payloadOffset = metaByteLength(packageData)
        val lenBytes = packageData.copyOfRange(payloadOffset, payloadOffset + 4)
        val payloadLen = bytesToInt(lenBytes)
        val encryptedPayload = packageData.copyOfRange(payloadOffset + 4, payloadOffset + 4 + payloadLen)

        val readKey = SecretKeySpec(readKeyBytes, "AES")
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, readKey, GCMParameterSpec(GCM_TAG_LENGTH, meta.nonce))
        val originalBytes = cipher.doFinal(encryptedPayload)

        val actualSha256 = sha256Hex(originalBytes)
        if (actualSha256 != meta.originalSha256) {
            throw SecurityException("Integritas dokumen gagal diverifikasi (SHA-256 tidak cocok).")
        }

        return originalBytes
    }

    /** Verifikasi password edit guru untuk ekspor PDF asli. */
    fun verifyEditPassword(protectedBytes: ByteArray, passwordInput: String): Boolean {
        try {
            val f = PdfFile.open(protectedBytes)
            val streamRef = f.catalog["DepupSecureData"] as? PRef ?: return false
            val stream = f.res(streamRef) as? PStream ?: return false
            val packageWithHmac = f.decode(stream, null)
            val packageData = packageWithHmac.copyOfRange(0, packageWithHmac.size - 32)
            val meta = deserializeMeta(packageData)
            val inputHash = hashEditPassword(passwordInput, meta.salt)
            return MessageDigest.isEqual(inputHash.toByteArray(Charsets.UTF_8), meta.editPasswordHash.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            return false
        }
    }

    private fun generateCoverPdf(docId: String): ByteArray {
        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        val page = pdfDoc.startPage(pageInfo)

        val canvas: Canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 22f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 13f
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("DEPUP OFFICE - DOKUMEN TERLINDUNGI", 595f / 2f, 350f, paint)
        canvas.drawText("Dokumen ini hanya bisa dibuka di DepUp Office", 595f / 2f, 390f, subPaint)
        canvas.drawText("ID Dokumen: $docId", 595f / 2f, 440f, subPaint.apply { textSize = 10f })

        pdfDoc.finishPage(page)
        val out = java.io.ByteArrayOutputStream()
        pdfDoc.writeTo(out)
        pdfDoc.close()
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun serializeMeta(
        docId: String,
        sha256: String,
        salt: ByteArray,
        nonce: ByteArray,
        editPassHash: String
    ): ByteArray {
        val header = MARKER.toByteArray(Charsets.ISO_8859_1)
        val docIdBytes = docId.toByteArray(Charsets.UTF_8)
        val shaBytes = sha256.toByteArray(Charsets.UTF_8)
        val passHashBytes = editPassHash.toByteArray(Charsets.UTF_8)

        val buf = java.io.ByteArrayOutputStream()
        buf.write(header)
        buf.write(lenBytes(docIdBytes.size)); buf.write(docIdBytes)
        buf.write(lenBytes(shaBytes.size)); buf.write(shaBytes)
        buf.write(lenBytes(salt.size)); buf.write(salt)
        buf.write(lenBytes(nonce.size)); buf.write(nonce)
        buf.write(lenBytes(passHashBytes.size)); buf.write(passHashBytes)
        buf.write(1) // editLocked = true
        return buf.toByteArray()
    }

    private fun deserializeMeta(data: ByteArray): ProtectionMeta {
        var offset = MARKER.length

        val docIdLen = readInt(data, offset); offset += 4
        val docId = String(data, offset, docIdLen, Charsets.UTF_8); offset += docIdLen

        val shaLen = readInt(data, offset); offset += 4
        val sha256 = String(data, offset, shaLen, Charsets.UTF_8); offset += shaLen

        val saltLen = readInt(data, offset); offset += 4
        val salt = data.copyOfRange(offset, offset + saltLen); offset += saltLen

        val nonceLen = readInt(data, offset); offset += 4
        val nonce = data.copyOfRange(offset, offset + nonceLen); offset += nonceLen

        val passHashLen = readInt(data, offset); offset += 4
        val editPassHash = String(data, offset, passHashLen, Charsets.UTF_8); offset += passHashLen

        return ProtectionMeta(docId, sha256, salt, nonce, editPassHash)
    }

    private fun metaByteLength(data: ByteArray): Int {
        var offset = MARKER.length
        val docIdLen = readInt(data, offset); offset += 4 + docIdLen
        val shaLen = readInt(data, offset); offset += 4 + shaLen
        val saltLen = readInt(data, offset); offset += 4 + saltLen
        val nonceLen = readInt(data, offset); offset += 4 + nonceLen
        val passHashLen = readInt(data, offset); offset += 4 + passHashLen
        offset += 1
        return offset
    }

    private fun lenBytes(v: Int): ByteArray = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
    )

    private fun readInt(arr: ByteArray, off: Int): Int {
        return ((arr[off].toInt() and 0xFF) shl 24) or
               ((arr[off + 1].toInt() and 0xFF) shl 16) or
               ((arr[off + 2].toInt() and 0xFF) shl 8) or
               (arr[off + 3].toInt() and 0xFF)
    }

    private fun bytesToInt(arr: ByteArray): Int = readInt(arr, 0)
}
