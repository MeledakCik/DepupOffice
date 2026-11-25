package cloud.depup.office

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Menerima yang DIBAGIKAN ke DepUp dari aplikasi lain:
 * - berkas (PDF/Word/Excel/PowerPoint) → langsung dibuka di pembaca, tanpa internet;
 * - teks berisi link (mis. "DDL_DML.pdf https://share.google/…", Google Drive/Docs, LMS) → setelah pengguna setuju,
 *   berkas diunduh SEKALI lewat https, disimpan ke Documents/DepUp Office, lalu dibuka. Selain itu DepUp tetap offline.
 */
class ShareReceiverActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val i = intent
        if (i?.action != Intent.ACTION_SEND) { finish(); return }

        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        if (stream != null && stream.scheme == "content") {
            Files.open(this, stream, i.type?.takeIf { it != "text/plain" } ?: Files.mimeOf(this, stream))
            finish(); return
        }

        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        val url = LinkFetch.extractUrl(text)
        if (url == null) {
            toast("Tidak ada link https di teks yang dibagikan.")
            finish(); return
        }
        val host = try { Uri.parse(url).host.orEmpty() } catch (_: Exception) { "" }
        val title = LinkFetch.titleOf(text, url)
        confirm(
            "Unduh dari link?",
            (if (title.isNotBlank()) "$title\n\n" else "") +
                "Dari: $host\n\nDepUp Office bekerja offline. Internet hanya dipakai sekarang untuk mengunduh berkas ini, " +
                "lalu berkas disimpan di Documents/DepUp Office.",
            "Unduh & buka",
            onCancel = { finish() }
        ) { fetch(url, title) }
    }

    private fun fetch(url: String, title: String) {
        work("Mengunduh berkas…", { LinkFetch.download(url, title) }) { r ->
            r.onSuccess { res ->
                try {
                    val base = res.name.substringBeforeLast('.')
                    val f = Files.namedOut(this, base, res.kind.ext)
                    f.writeBytes(res.bytes)
                    val u = Files.uriFor(this, f)
                    try { Recent.add(this, u, f.name, res.kind.mime) } catch (_: Exception) {}
                    Files.open(this, u, res.kind.mime)
                    finish()
                } catch (t: Throwable) { showError(t.message ?: "Gagal menyimpan berkas.") }
            }.onFailure { t ->
                showError(when (t) {
                    is LinkFetch.LinkException -> t.message ?: LinkFetch.NEED_LOGIN
                    is java.net.UnknownHostException -> "Tidak ada koneksi internet."
                    is java.net.SocketTimeoutException -> "Koneksi terlalu lambat. Coba lagi."
                    is javax.net.ssl.SSLException -> "Koneksi aman ke server gagal."
                    else -> t.message ?: "Gagal mengunduh."
                })
            }
        }
    }

    private fun showError(msg: String) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Tidak bisa dibuka").setMessage(msg)
            .setPositiveButton("OK", null).setOnDismissListener { finish() }.show()
    }
}
