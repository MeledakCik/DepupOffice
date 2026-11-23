package cloud.depup.office

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
