package cloud.depup.office

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipFile

/**
 * "Batasi Pengeditan" Word (w:documentProtection, hanya-baca + password) — murni Kotlin, diuji di JVM.
 *
 * Dipakai saat PDF terkunci diubah ke Word: dokumennya bisa dibaca, tapi untuk MENGEDIT harus memasukkan password yang
 * sama dengan PDF-nya (di Word: Tinjau → Batasi Pengeditan → Hentikan Perlindungan; di Depup: diminta saat membuka
 * editor). Hash mengikuti cara Word/ECMA-376: kunci XOR lama (Word 97–2003) dari password → teks heksadesimal →
 * SHA-512 bersalt, diulang 100.000 kali.
 */
object DocxProtect {
    private const val SPIN = 100_000

    private val INITIAL_CODE = intArrayOf(0xE1F0, 0x1D0F, 0xCC9C, 0x84C0, 0x110C, 0x0E10, 0xF1CE, 0x313E, 0x1872, 0xE139, 0xD40F, 0x84F9, 0x280C, 0xA96A, 0x4EC3)
    private val MATRIX = arrayOf(
        intArrayOf(0xAEFC, 0x4DD9, 0x9BB2, 0x2745, 0x4E8A, 0x9D14, 0x2A09),
        intArrayOf(0x7B61, 0xF6C2, 0xFDA5, 0xEB6B, 0xC6F7, 0x9DCF, 0x2BBF),
        intArrayOf(0x4563, 0x8AC6, 0x05AD, 0x0B5A, 0x16B4, 0x2D68, 0x5AD0),
        intArrayOf(0x0375, 0x06EA, 0x0DD4, 0x1BA8, 0x3750, 0x6EA0, 0xDD40),
        intArrayOf(0xD849, 0xA0B3, 0x5147, 0xA28E, 0x553D, 0xAA7A, 0x44D5),
        intArrayOf(0x6F45, 0xDE8A, 0xAD35, 0x4A4B, 0x9496, 0x390D, 0x721A),
        intArrayOf(0xEB23, 0xC667, 0x9CEF, 0x29FF, 0x53FE, 0xA7FC, 0x5FD9),
        intArrayOf(0x47D3, 0x8FA6, 0x0F6D, 0x1EDA, 0x3DB4, 0x7B68, 0xF6D0),
        intArrayOf(0xB861, 0x60E3, 0xC1C6, 0x93AD, 0x377B, 0x6EF6, 0xDDEC),
        intArrayOf(0x45A0, 0x8B40, 0x06A1, 0x0D42, 0x1A84, 0x3508, 0x6A10),
        intArrayOf(0xAA51, 0x4483, 0x8906, 0x022D, 0x045A, 0x08B4, 0x1168),
        intArrayOf(0x76B4, 0xED68, 0xCAF1, 0x85C3, 0x1BA7, 0x374E, 0x6E9C),
        intArrayOf(0x3730, 0x6E60, 0xDCC0, 0xA9A1, 0x4363, 0x86C6, 0x1DAD),
        intArrayOf(0x3331, 0x6662, 0xCCC4, 0x89A9, 0x0373, 0x06E6, 0x0DCC),
        intArrayOf(0x1021, 0x2042, 0x4084, 0x8108, 0x1231, 0x2462, 0x48C4)
    )

    /** Byte "ANSI" password ala Word: byte rendah tiap karakter (byte tinggi bila rendahnya 0), maks. 15. */
    private fun ansi(pw: String): ByteArray {
        val s = pw.take(15)
        return ByteArray(s.length) { i -> val c = s[i].code; (if (c and 0xFF != 0) c and 0xFF else (c shr 8) and 0xFF).toByte() }
    }

    /** Kunci XOR lama Word (32-bit) sebagai 8 digit heksa dengan urutan byte dibalik. */
    private fun legacyHex(pw: String): String {
        val b = ansi(pw)
        if (b.isEmpty()) return "00000000"
        var high = INITIAL_CODE[b.size - 1]
        for (i in b.indices) {
            val line = 15 - b.size + i
            for (bit in 0 until 7) if ((b[i].toInt() and (1 shl bit)) != 0) high = high xor MATRIX[line][bit]
        }
        var low = 0
        fun rot(x: Int) = ((x shr 14) and 1) or ((x shl 1) and 0x7FFF)
        for (i in b.indices.reversed()) { low = rot(low); low = low xor (b[i].toInt() and 0xFF) }
        low = rot(low); low = low xor b.size; low = low xor 0xCE4B
        val key = (high shl 16) or (low and 0xFFFF)
        return String.format("%02X%02X%02X%02X", key and 0xFF, (key ushr 8) and 0xFF, (key ushr 16) and 0xFF, (key ushr 24) and 0xFF)
    }

    private fun algo(sid: Int) = when (sid) { 4 -> "SHA-1"; 12 -> "SHA-256"; 13 -> "SHA-384"; else -> "SHA-512" }

    private fun hash(pw: String, salt: ByteArray, spin: Int, alg: String, legacy: Boolean): ByteArray {
        val md = MessageDigest.getInstance(alg)
        val src = (if (legacy) legacyHex(pw) else pw).toByteArray(Charsets.UTF_16LE)
        md.update(salt); md.update(src)
        var h = md.digest()
        val it = ByteArray(4)
        for (i in 0 until spin) {
            it[0] = i.toByte(); it[1] = (i ushr 8).toByte(); it[2] = (i ushr 16).toByte(); it[3] = (i ushr 24).toByte()
            md.update(h); md.update(it); h = md.digest()
        }
        return h
    }

    /** Elemen settings.xml: hanya-baca, wajib, password = [pw]. */
    fun protectionXml(pw: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val h = hash(pw, salt, SPIN, "SHA-512", legacy = true)
        val b64 = Base64.getEncoder()
        return "<w:documentProtection w:edit=\"readOnly\" w:enforcement=\"1\" w:cryptProviderType=\"rsaAES\" w:cryptAlgorithmClass=\"hash\" " +
            "w:cryptAlgorithmType=\"typeAny\" w:cryptAlgorithmSid=\"14\" w:cryptSpinCount=\"$SPIN\" w:hash=\"${b64.encodeToString(h)}\" w:salt=\"${b64.encodeToString(salt)}\"/>"
    }

    /** Proteksi aktif di settings.xml (null = tidak ada / tidak ditegakkan). */
    class Prot(private val attrs: Map<String, String>) {
        val hasPassword get() = attrs.containsKey("hash") || attrs.containsKey("hashValue")

        /** Cocokkan password (bentuk lama cryptAlgorithmSid & bentuk baru algorithmName). Tanpa hash = tanpa password. */
        /**
         * Jumlah putaran hash dari berkas, dibatasi 0..10 juta (batas spesifikasi ECMA-376). Tanpa batas, berkas jahat
         * bisa menulis miliaran putaran — pemeriksaan password tidak pernah selesai.
         */
        private fun spins(v: String?): Int = (v?.toIntOrNull() ?: SPIN).coerceIn(0, 10_000_000)

        fun check(pw: String): Boolean = try {
            val d = Base64.getDecoder()
            when {
                attrs.containsKey("hash") -> {
                    val salt = d.decode(attrs["salt"] ?: "")
                    MessageDigest.isEqual(hash(pw, salt, spins(attrs["cryptSpinCount"]), algo(attrs["cryptAlgorithmSid"]?.toIntOrNull() ?: 4), true), d.decode(attrs["hash"]))
                }
                attrs.containsKey("hashValue") -> {
                    val salt = d.decode(attrs["saltValue"] ?: "")
                    val alg = (attrs["algorithmName"] ?: "SHA-512").uppercase().let { if (it.startsWith("SHA") && !it.contains("-")) "SHA-" + it.removePrefix("SHA") else it }
                    val spin = spins(attrs["spinCount"])
                    val want = d.decode(attrs["hashValue"])
                    MessageDigest.isEqual(hash(pw, salt, spin, alg, true), want) || MessageDigest.isEqual(hash(pw, salt, spin, alg, false), want)
                }
                else -> true
            }
        } catch (_: Exception) { false }
    }

    /** Baca proteksi hanya-baca yang DITEGAKKAN dari isi settings.xml. */
    fun parse(settingsXml: String): Prot? {
        val m = Regex("<w:documentProtection\\b([^>]*)/?>").find(settingsXml) ?: return null
        val attrs = Regex("w:([A-Za-z]+)=\"([^\"]*)\"").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        val enf = attrs["enforcement"]?.lowercase()
        if (enf != "1" && enf != "true" && enf != "on") return null
        if ((attrs["edit"] ?: "none") == "none") return null
        return Prot(attrs)
    }

    /** Proteksi berkas .docx (null bila tidak dilindungi / bukan docx). */
    fun of(f: File): Prot? = try {
        ZipFile(f).use { z ->
            val e = z.getEntry("word/settings.xml") ?: return null
            parse(z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) })
        }
    } catch (_: Exception) { null }
}
