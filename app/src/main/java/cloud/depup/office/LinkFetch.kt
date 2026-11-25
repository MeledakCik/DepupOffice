package cloud.depup.office

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/**
 * Unduh berkas dari LINK yang dibagikan ke DepUp (mis. "DDL_DML.pdf https://share.google/…", link Google Drive,
 * Google Docs, LMS). DepUp tetap offline: internet hanya dipakai saat pengguna sendiri membagikan link ke DepUp dan
 * menyetujui unduhannya. Tidak ada data pengguna yang dikirim selain permintaan HTTPS biasa ke alamat tersebut.
 */
object LinkFetch {
    const val MAX_BYTES = 100L shl 20          // 100 MB
    private const val MAX_HOPS = 8

    class LinkException(msg: String) : IOException(msg)

    enum class Kind(val ext: String, val mime: String) {
        PDF("pdf", "application/pdf"),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    }

    class Result(val bytes: ByteArray, val kind: Kind, val name: String)

    /** Link https pertama di teks bagikan ("Judul https://…"); null bila tidak ada. */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = Regex("""https://[^\s<>"'`]+""").find(text) ?: return null
        return m.value.trimEnd('.', ',', ')', ']', '}', ';', '!', '?')
    }

    /** Judul sebelum link (nama berkas yang disertakan aplikasi pembagi), mis. "DDL_DML.pdf". */
    fun titleOf(text: String?, url: String): String =
        text.orEmpty().replace(url, " ").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("http") }.orEmpty()

    private val driveFile = Regex("""^https://drive\.google\.com/(?:file/d/|open\?id=|uc\?(?:.*&)?id=)([A-Za-z0-9_-]{10,})""")
    private val docsFile = Regex("""^https://docs\.google\.com/(document|spreadsheets|presentation)/d/([A-Za-z0-9_-]{10,})""")

    /**
     * Alamat halaman pratinjau → alamat unduhan langsung:
     * - Google Drive berkas → drive.usercontent.google.com/download (tanpa halaman "pindai virus");
     * - Google Docs/Sheets/Slides → ekspor PDF.
     * Selain itu dikembalikan apa adanya.
     */
    fun directUrl(url: String): String {
        driveFile.find(url)?.let { return "https://drive.usercontent.google.com/download?id=${it.groupValues[1]}&export=download&confirm=t" }
        docsFile.find(url)?.let {
            val id = it.groupValues[2]
            return when (it.groupValues[1]) {
                "presentation" -> "https://docs.google.com/presentation/d/$id/export/pdf"
                else -> "https://docs.google.com/${it.groupValues[1]}/d/$id/export?format=pdf"
            }
        }
        return url
    }

    /** Jenis berkas dari isinya (bukan dari header server yang bisa salah). */
    fun sniff(b: ByteArray, nameHint: String): Kind? {
        if (b.size >= 5 && String(b, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return Kind.PDF
        // Sebagian server menambah byte/BOM sebelum header PDF: cari di 1 KB pertama.
        if (String(b, 0, minOf(b.size, 1024), Charsets.ISO_8859_1).contains("%PDF-")) return Kind.PDF
        if (b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4) {
            val head = String(b, 0, minOf(b.size, 64 * 1024), Charsets.ISO_8859_1)
            return when {
                head.contains("word/") -> Kind.DOCX
                head.contains("xl/") -> Kind.XLSX
                head.contains("ppt/") -> Kind.PPTX
                else -> when (nameHint.substringAfterLast('.', "").lowercase()) {
                    "docx" -> Kind.DOCX; "xlsx" -> Kind.XLSX; "pptx" -> Kind.PPTX; else -> null
                }
            }
        }
        return null
    }

    /** Nama berkas dari Content-Disposition, lalu dari alamat; tanpa karakter jalur. */
    fun fileName(disposition: String?, url: String, title: String): String {
        val fromHeader = disposition?.let { d ->
            Regex("""filename\*=(?:UTF-8'')?([^;]+)""", RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)
                ?.let { try { URLDecoder.decode(it.trim('"', ' '), "UTF-8") } catch (_: Exception) { null } }
                ?: Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)
        }
        val fromUrl = try { URI(url).path?.substringAfterLast('/')?.let { URLDecoder.decode(it, "UTF-8") } } catch (_: Exception) { null }
        val raw = listOf(fromHeader, title.takeIf { it.contains('.') }, fromUrl, title)
            .firstOrNull { !it.isNullOrBlank() && it != "download" && it != "export" && it != "pdf" && it != "view" } ?: "dokumen"
        return raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), "_").trim().take(120).ifBlank { "dokumen" }
    }

    /** Unduh [link] (ikuti pengalihan https saja, maks. [MAX_BYTES]). Jalankan di luar thread utama. */
    fun download(link: String, title: String = ""): Result {
        var url = directUrl(link)
        if (!url.startsWith("https://")) throw LinkException("Hanya link https yang bisa dibuka.")
        repeat(MAX_HOPS) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DepUpOffice")
                setRequestProperty("Accept", "application/pdf,application/octet-stream,*/*;q=0.8")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw LinkException("Pengalihan tanpa alamat tujuan.")
                    val next = URL(URL(url), loc).toString()
                    if (!next.startsWith("https://")) throw LinkException("Link mengarah ke alamat tidak aman (bukan https).")
                    url = directUrl(next)
                    return@repeat
                }
                if (code == 401 || code == 403) throw LinkException(NEED_LOGIN)
                if (code !in 200..299) throw LinkException("Server menjawab $code. Coba buka link di browser.")
                if (c.contentLengthLong > MAX_BYTES) throw LinkException("Berkas lebih dari ${MAX_BYTES shr 20} MB.")
                val bytes = try { c.inputStream.use { Files.readCapped(it, MAX_BYTES) } }
                    catch (_: Files.TooLargeException) { throw LinkException("Berkas lebih dari ${MAX_BYTES shr 20} MB.") }
                val name = fileName(c.getHeaderField("Content-Disposition"), url, title)
                val kind = sniff(bytes, name) ?: throw LinkException(NEED_LOGIN)
                val base = name.substringBeforeLast('.').ifBlank { "dokumen" }
                return Result(bytes, kind, "$base.${kind.ext}")
            } finally { c.disconnect() }
        }
        throw LinkException("Terlalu banyak pengalihan.")
    }

    const val NEED_LOGIN = "Link ini tidak langsung ke berkas PDF/Office (mungkin perlu login, mis. LMS). " +
        "Buka link di browser, unduh berkasnya, lalu bagikan berkas itu ke DepUp Office."
}
