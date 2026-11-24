package cloud.depup.office

/**
 * Penyimpan rahasia ter-obfuskasi (XOR split). Rahasia asli TIDAK pernah tersimpan utuh di DEX:
 * setiap byte = A[i] xor B[i] xor mask(salt, i). Pembongkar (jadx/apktool) hanya melihat dua deret acak.
 * Rahasia yang sengaja "mudah ditemukan" ada di [MasterKeys] — itu umpan (honeypot), bukan kunci asli.
 *
 * Murni Kotlin (tanpa API Android) agar bisa diuji di JVM.
 */
internal object Sx {
    private val KA = intArrayOf(0x23, 0xA4, 0x0E, 0x39, 0x64, 0xB2, 0x8E, 0x1B, 0xEC, 0x68, 0x31, 0x14, 0x3F, 0x7F, 0x9D, 0xB8, 0xB8, 0x8D, 0x78, 0x8A, 0x19, 0x8F, 0x50, 0x20, 0xB3, 0xD2, 0x82, 0x2C, 0xE2, 0x78, 0x30, 0x45)
    private val KB = intArrayOf(0x44, 0xE7, 0x09, 0x90, 0xAA, 0x06, 0x22, 0x2F, 0xDE, 0x4E, 0xA8, 0xD9, 0xC2, 0x93, 0xFE, 0xB5, 0xBE, 0xF0, 0xA7, 0x56, 0xF6, 0x68, 0x3B, 0x3F, 0xE1, 0xF4, 0x7A, 0xAA, 0x6E, 0x9A, 0x49, 0x2A)
    private val CA = intArrayOf(0x4F, 0x79, 0x99, 0xC6, 0xAC, 0x45, 0x7E, 0x64, 0xD2, 0xA9, 0x0F, 0x33, 0xFF, 0x05, 0x06, 0xA9, 0x1C, 0x67, 0x1D, 0xCE, 0xF0, 0x76, 0x72, 0xDE, 0x19, 0x0B, 0x7C, 0x20, 0x7B, 0x07, 0xFF, 0x1A)
    private val CB = intArrayOf(0xC9, 0xB8, 0x90, 0x67, 0xB5, 0xC3, 0x6A, 0x3B, 0xFB, 0xF5, 0x4E, 0xA4, 0x36, 0x43, 0xAA, 0xBE, 0xF2, 0x0D, 0x71, 0x16, 0x2F, 0x99, 0x09, 0x29, 0x62, 0xE6, 0x55, 0x64, 0x3E, 0xF4, 0xA4, 0x14)
    private val PA = intArrayOf(0xA8, 0x8A, 0x16, 0x13, 0xEF, 0x4F, 0x9F, 0x63, 0xB4, 0x59, 0xE0, 0xC1, 0xE1, 0xE0, 0xE0, 0xFF)
    private val PB = intArrayOf(0xBD, 0x9F, 0xE9, 0xE8, 0x4B, 0xEE, 0xE8, 0x33, 0x87, 0x64, 0x10, 0x0C, 0x01, 0x6F, 0xCA, 0xF2)

    private fun mask(salt: Int, i: Int) = (salt + i * 31 + (i shr 2) * 7) and 0xFF

    private fun open(a: IntArray, b: IntArray, salt: Int): ByteArray =
        ByteArray(a.size) { i -> (a[i] xor b[i] xor mask(salt, i)).toByte() }

    /** Rahasia aplikasi untuk kunci baca Dokumen Terlindungi (32 byte). Salinan baru tiap panggilan — hapus setelah dipakai. */
    fun appSecret(): ByteArray = open(KA, KB, 0x3D)

    /** SHA-256 sertifikat penandatangan rilis (upload key) yang sah. */
    fun releaseCert(): ByteArray = open(CA, CB, 0x6B)

    /** Prefiks domain-separation derivasi kunci baca. */
    fun readKeyPrefix(): String = String(open(PA, PB, 0x51), Charsets.UTF_8)

    /** Hapus isi array rahasia dari memori setelah dipakai. */
    fun wipe(b: ByteArray?) { b?.fill(0) }
}

/**
 * HONEYPOT. Nama & isi sengaja tampak seperti kunci produksi dan DIPERTAHANKAN apa adanya oleh R8
 * (lihat proguard-rules.pro). Kunci ini tidak pernah dipakai untuk mengenkripsi dokumen asli.
 * Dokumen yang ternyata ditandatangani/dienkripsi dengan kunci ini = dipalsukan oleh pihak yang membongkar APK,
 * dan langsung ditolak oleh [ProtectedDoc] (lihat [Honeypot]).
 */
object MasterKeys {
    @JvmField val AES_MASTER_KEY: ByteArray = byteArrayOf(
        0x89.toByte(), 0xC2.toByte(), 0x0F, 0x40, 0xB8.toByte(), 0xC0.toByte(), 0x63, 0x6A, 0xA2.toByte(), 0x53, 0x99.toByte(), 0x92.toByte(), 0x96.toByte(), 0xD4.toByte(), 0xE4.toByte(), 0x23,
        0xCA.toByte(), 0x4D, 0xD4.toByte(), 0x2E, 0x13, 0x5B, 0xF7.toByte(), 0x82.toByte(), 0x2F, 0x38, 0xF7.toByte(), 0x6D, 0xD2.toByte(), 0x6B, 0xED.toByte(), 0xB4.toByte()
    )
    @JvmField val READ_KEY_SALT: String = "DepUpMasterKey:v3:"
    @JvmField val LICENSE_API_KEY: String = "dpk_live_7Q2xN9fV4mK8sT1wR6yB3hJ5"
}

/** Pembersih teks dari vektor prompt-injection AI yang tersembunyi (murni Kotlin). */
object AiText {
    /** Karakter tak terlihat yang lazim dipakai menyelundupkan instruksi ke model AI. */
    private fun invisible(cp: Int): Boolean =
        cp in 0x200B..0x200F ||          // zero-width space/joiner, LRM/RLM
        cp in 0x202A..0x202E ||          // bidi embedding/override
        cp in 0x2060..0x2064 ||          // word joiner, invisible operators
        cp in 0x2066..0x2069 ||          // bidi isolates
        cp == 0xFEFF || cp == 0x00AD ||  // BOM, soft hyphen
        cp in 0xE0000..0xE007F ||        // Unicode TAG ("ASCII smuggling")
        cp in 0xFE00..0xFE0F ||          // variation selectors (steganografi)
        cp in 0xE0100..0xE01EF

    /** Hapus karakter tersembunyi; kembalikan teks bersih + jumlah karakter yang dibuang. */
    fun sanitize(s: String): Pair<String, Int> {
        val sb = StringBuilder(s.length)
        var removed = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (invisible(cp) || (Character.getType(cp) == Character.CONTROL.toInt() && cp != '\n'.code && cp != '\t'.code && cp != '\r'.code)) removed++
            else sb.appendCodePoint(cp)
            i += n
        }
        return sb.toString() to removed
    }

    private val INJECTION = Regex(
        "(?i)(ignore (all |any )?(previous|prior|above) (instructions|prompts)|abaikan (semua )?(instruksi|perintah) (sebelumnya|di atas)|" +
        "you are now|system prompt|developer mode|jailbreak|<\\|im_start\\|>|\\[INST]|BEGIN SYSTEM)"
    )

    /** Deteksi pola instruksi yang ditujukan ke AI di dalam isi dokumen. */
    fun looksLikeInjection(s: String): Boolean = INJECTION.containsMatchIn(s)

    fun clean(s: String): String = sanitize(s).first
}
