package cloud.depup.office

/**
 * Penanda "dokumen ini tidak boleh dipakai / diproses AI" di METADATA PDF (tidak mengubah tampilan halaman).
 * Memakai standar yang dibaca perayap & alat AI:
 *  - IPTC/PLUS Data Mining: `plus:DataMining = DMI-PROHIBITED` (XMP),
 *  - W3C TDM Reservation Protocol: `tdm:reserve = 1` (XMP) — hak penambangan teks & data dicadangkan,
 *  - kata kunci `noai, noimageai` (Info /Keywords) + pernyataan hak (dc:rights, Info /Rights).
 * [remove] membuang penanda tersebut — baik yang dipasang Depup maupun aplikasi lain — tanpa menyentuh isi halaman.
 */
object PdfAiMark {
    private const val NS_PLUS = "http://ns.useplus.org/ldf/xmp/1.0/"
    private const val NS_TDM = "http://www.w3.org/ns/tdmrep/"
    private const val NS_DEPUP = "https://depup.cloud/ns/aimark/1.0/"
    private const val DMI = "http://ns.useplus.org/ldf/vocab/DMI-PROHIBITED"
    private val KEYWORDS = listOf("noai", "noimageai", "DMI-PROHIBITED")
    const val NOTICE = "Dilarang menggunakan dokumen ini untuk pelatihan, penambangan data, atau pemrosesan AI tanpa izin tertulis pemilik."

    /** true bila dokumen membawa salah satu penanda anti-AI. */
    fun isMarked(f: PdfFile): Boolean {
        val kw = (f.info?.get("Keywords") as? PStr)?.text().orEmpty()
        if (KEYWORDS.any { k -> kw.split(',', ';', ' ').any { it.trim().equals(k, true) } }) return true
        val x = xmp(f) ?: return false
        return x.contains("DMI-PROHIBITED") || Regex("tdm:reserve\\s*(=\\s*\"1\"|>\\s*1\\s*<)").containsMatchIn(x) || x.contains(NS_DEPUP)
    }

    private fun xmp(f: PdfFile): String? {
        val s = f.res(f.catalog["Metadata"]) as? PStream ?: return null
        return try { String(f.decode(s, null), Charsets.UTF_8) } catch (_: Exception) { null }
    }

    /** Pasang penanda. [owner] = nama pemilik hak (opsional, ditulis di pernyataan hak). */
    fun apply(c: PdfSave.Ctx, owner: String) {
        val f = c.f
        val notice = if (owner.isBlank()) NOTICE else "$NOTICE Pemilik: $owner."
        // Info
        val info = PDict(LinkedHashMap(f.info?.m ?: LinkedHashMap()))
        val kw = (info["Keywords"] as? PStr)?.text().orEmpty()
        val have = kw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        info["Keywords"] = PStr.of((have + KEYWORDS.filter { k -> have.none { it.equals(k, true) } }).joinToString(", "))
        info["Rights"] = PStr.of(notice)
        info["AIUsage"] = PStr.of("prohibited")
        info["TDMReservation"] = PStr.of("1")
        info["ModDate"] = PStr.of(pdfDate())
        c.infoRef = c.add(info)
        // XMP
        val old = xmp(f)?.let { strip(it) }
        val desc = """<rdf:Description rdf:about="" xmlns:plus="$NS_PLUS" xmlns:tdm="$NS_TDM" xmlns:depup="$NS_DEPUP" xmlns:xmpRights="http://ns.adobe.com/xap/1.0/rights/" xmlns:dc="http://purl.org/dc/elements/1.1/">
<plus:DataMining>$DMI</plus:DataMining>
<tdm:reserve>1</tdm:reserve>
<depup:AiMark>prohibited</depup:AiMark>
<xmpRights:Marked>True</xmpRights:Marked>
<xmpRights:UsageTerms><rdf:Alt><rdf:li xml:lang="x-default">${esc(notice)}</rdf:li></rdf:Alt></xmpRights:UsageTerms>
<dc:rights><rdf:Alt><rdf:li xml:lang="x-default">${esc(notice)}</rdf:li></rdf:Alt></dc:rights>
</rdf:Description>"""
        val x = if (old != null && old.contains("</rdf:RDF>")) old.replaceFirst("</rdf:RDF>", "$desc\n</rdf:RDF>")
            else "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n$desc\n</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>"
        setXmp(c, x)
    }

    /** Hapus penanda anti-AI (Depup & standar umum). Isi halaman tidak diubah. */
    fun remove(c: PdfSave.Ctx) {
        val f = c.f
        val src = f.info
        if (src != null) {
            val info = PDict(LinkedHashMap(src.m))
            (info["Keywords"] as? PStr)?.text()?.let { kw ->
                val rest = kw.split(',', ';').map { it.trim() }.filter { k -> k.isNotEmpty() && KEYWORDS.none { it.equals(k, true) } }
                info["Keywords"] = if (rest.isEmpty()) null else PStr.of(rest.joinToString(", "))
            }
            for (k in listOf("AIUsage", "TDMReservation", "TDMPolicy")) info[k] = null
            if ((info["Rights"] as? PStr)?.text()?.startsWith(NOTICE.take(30)) == true) info["Rights"] = null
            info["ModDate"] = PStr.of(pdfDate())
            c.infoRef = c.add(info)
        }
        val x = xmp(f) ?: return
        setXmp(c, strip(x))
    }

    /** Buang blok Depup + properti DataMining/TDM dari XMP (bentuk elemen maupun atribut). */
    fun strip(x0: String): String {
        var x = x0
        x = Regex("<rdf:Description[^>]*xmlns:depup=\"[^\"]*\"[^>]*>.*?</rdf:Description>\\s*", RegexOption.DOT_MATCHES_ALL).replace(x, "")
        x = Regex("<plus:DataMining\\b[^>]*?(/>|>.*?</plus:DataMining>)\\s*", RegexOption.DOT_MATCHES_ALL).replace(x, "")
        x = Regex("<tdm:(reserve|policy)\\b[^>]*?(/>|>.*?</tdm:\\1>)\\s*", RegexOption.DOT_MATCHES_ALL).replace(x, "")
        x = Regex("\\s(plus:DataMining|tdm:reserve|tdm:policy)=\"[^\"]*\"").replace(x, "")
        return x
    }

    private fun setXmp(c: PdfSave.Ctx, x: String) {
        val d = PDict(); d["Type"] = PName("Metadata"); d["Subtype"] = PName("XML")
        c.catalog["Metadata"] = c.add(PStream(d, x.toByteArray(Charsets.UTF_8)))
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun pdfDate(): String {
        val f = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return "D:" + f.format(java.util.Date()) + "Z"
    }
}
