package cloud.depup.office

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Uji format Dokumen Terlindungi (murni JVM). Vektor v3 juga dihasilkan oleh referensi Python & diverifikasi iOS. */
class SecurePackageTest {
    private val docId = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val plain = "%PDF-1.4 contoh isi rahasia".toByteArray(Charsets.US_ASCII)

    private fun vector(): ByteArray = SecurePackage.buildV3(
        plain, "7kq2 m9xd-4rtw", "GuruRahasia!", docId, iterations = 100_000,
        salt = ByteArray(16) { it.toByte() }, nonce = ByteArray(12) { (100 + it).toByte() }
    )

    private fun open(pkg: ByteArray, code: String?): ByteArray {
        val (body, tag) = SecurePackage.split(pkg)
        return SecurePackage.openV3(body, tag, SecurePackage.parseMeta(body), code)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun v3MatchesCrossPlatformReferenceVector() {
        val pkg = vector()
        assertEquals(315, pkg.size)
        assertEquals("d3fd862f041dfa5d7473a70de3c59e5c87c9783a424f6e4c103f22ca41fda843", sha(pkg))
    }

    @Test
    fun v3RoundTripWithNormalizedCode() {
        val pkg = vector()
        assertArrayEquals(plain, open(pkg, "7KQ2M9XD4RTW"))
        assertArrayEquals(plain, open(pkg, "  7kq2-m9xd-4rtw "))
    }

    @Test
    fun v3WrongOrMissingCodeIsAccessCodeError() {
        val pkg = vector()
        val wrong = runCatching { open(pkg, "7KQ2M9XD4RTX") }.exceptionOrNull()
        assertTrue(wrong is SecurePackage.AccessCodeException && !wrong.missing)
        val missing = runCatching { open(pkg, null) }.exceptionOrNull()
        assertTrue(missing is SecurePackage.AccessCodeException && missing.missing)
    }

    @Test
    fun v3RejectsEveryTamperedByte() {
        val pkg = vector()
        val rng = SecureRandom()
        for (i in 0 until 40) {
            val t = pkg.copyOf()
            val at = rng.nextInt(t.size)
            t[at] = (t[at].toInt() xor (1 + rng.nextInt(255))).toByte()
            val r = runCatching { open(t, "7KQ2M9XD4RTW") }
            assertTrue("byte $at diterima setelah diubah", r.isFailure)
        }
    }

    @Test
    fun v3RejectsOutOfRangeIterations() {
        val pkg = vector()
        val (body, _) = SecurePackage.split(pkg)
        val meta = SecurePackage.parseMeta(body)
        val itOffset = meta.payloadOffset - 5
        val t = body.copyOf()
        SecurePackage.int32(Int.MAX_VALUE).copyInto(t, itOffset)
        assertTrue(runCatching { SecurePackage.parseMeta(t) }.isFailure)
    }

    @Test
    fun editPasswordVerifiesAgainstAuthenticatedMeta() {
        val (body, _) = SecurePackage.split(vector())
        val meta = SecurePackage.parseMeta(body)
        assertTrue(SecurePackage.editPasswordMatches(meta, "GuruRahasia!"))
        assertFalse(SecurePackage.editPasswordMatches(meta, "gururahasia!"))
        assertFalse(SecurePackage.editPasswordMatches(meta, ""))
    }

    @Test
    fun generatedCodesAreReadableAndValid() {
        repeat(50) {
            val c = SecurePackage.generateAccessCode()
            assertTrue(c, Regex("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$").matches(c))
            assertEquals(null, SecurePackage.validateNewCode(c))
        }
        assertNotNull(SecurePackage.validateNewCode("abc-12"))
    }

    @Test
    fun v1DocumentsFrom410StillOpen() {
        // Paket v1: kunci = HMAC(rahasia aplikasi, prefiks + docId), tanpa AAD, HMAC dengan kunci yang sama.
        val secret = Sx.appSecret()
        val key = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
            .doFinal((Sx.readKeyPrefix() + docId).toByteArray(Charsets.UTF_8))
        val salt = ByteArray(16) { 7 }; val nonce = ByteArray(12) { 9 }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ct = c.doFinal(plain)
        val out = java.io.ByteArrayOutputStream()
        fun f(b: ByteArray) { out.write(SecurePackage.int32(b.size)); out.write(b) }
        out.write(SecurePackage.MARKER_V1.toByteArray())
        f(docId.toByteArray()); f(SecurePackage.sha256Hex(plain).toByteArray()); f(salt); f(nonce)
        f(SecurePackage.hashEditPassword("guru123", salt).toByteArray()); out.write(1); f(ct)
        val body = out.toByteArray()
        val tag = SecurePackage.hmacSha256(key, body)
        val meta = SecurePackage.parseMeta(body)
        assertEquals(1, meta.version)
        assertArrayEquals(plain, SecurePackage.openWithKeys(body, tag, meta, key, key))
        val decoy = ByteArray(32) { 1 }
        assertEquals(null, SecurePackage.openWithKeys(body, tag, meta, decoy, decoy))
    }

    @Test
    fun hkdfMatchesRfc5869Sha256TestCaseOne() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
        assertArrayEquals(expected, SecurePackage.hkdfSha256(ikm, salt, info, expected.size))
    }

    @Test
    fun v2AadLayoutUnchanged() {
        // Dokumen v2 yang sudah ada harus tetap terbuka: AAD = marker | 2 | len,docId | flag
        val aad = SecurePackage.aad(SecurePackage.MARKER_V2, 2, docId, null, true)
        assertEquals(19 + 1 + 4 + 36 + 1, aad.size)
        assertEquals(1, aad.last().toInt())
    }

    @Test
    fun unknownMarkerRejected() {
        val bad = "DEPUP_SECURE_DOC_v9".toByteArray() + ByteArray(64)
        assertTrue(runCatching { SecurePackage.parseMeta(bad) }.isFailure)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { i ->
        value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
