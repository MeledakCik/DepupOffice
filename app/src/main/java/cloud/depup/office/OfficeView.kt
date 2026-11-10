package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Pembaca DOCX / XLSX / PPTX berfidelitas tinggi: dokumen diubah ke HTML oleh [DocxHtml] / [XlsxHtml] / [PptxHtml]
 * (murni Kotlin) lalu ditampilkan di WebView. Gambar dari dalam berkas & font pengganti metrik-sama (turunan Carlito untuk Calibri,
 * Caladea untuk Cambria, Liberation untuk Arial/Times/Courier — lihat assets/fonts/README-fonts.txt) dilayani dari memori/aset lewat
 * https://doc.local/ — tidak ada akses jaringan sama sekali.
 */
object OfficeView {
    /** "docx" / "xlsx" / "pptx" untuk format yang didukung, "legacy" untuk .doc/.xls/.ppt lama, null selain itu. */
    fun kindOf(name: String, mime: String): String? {
        val n = name.lowercase()
        return when {
            n.endsWith(".docx") || n.endsWith(".docm") || n.endsWith(".dotx") || mime.contains("wordprocessingml") -> "docx"
            n.endsWith(".xlsx") || n.endsWith(".xlsm") || n.endsWith(".xltx") || mime.contains("spreadsheetml") -> "xlsx"
            n.endsWith(".pptx") || n.endsWith(".ppsx") || n.endsWith(".potx") || mime.contains("presentationml") -> "pptx"
            n.endsWith(".doc") || n.endsWith(".ppt") || mime == "application/msword" || mime == "application/vnd.ms-powerpoint" -> "legacy"
            else -> null
        }
    }

    /** Kenali jenis dari isi berkas bila nama/mime tidak jelas (mis. berkas dari WhatsApp/Drive tanpa ekstensi). */
    fun sniff(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val z = java.util.zip.ZipInputStream(ins)
            var kind: String? = null; var n = 0
            while (kind == null && n++ < 40) {
                val e = z.nextEntry ?: break
                kind = when {
                    e.name.startsWith("word/") -> "docx"; e.name.startsWith("xl/") -> "xlsx"; e.name.startsWith("ppt/") -> "pptx"; else -> null
                }
            }
            kind
        }
    } catch (_: Exception) { null }

    /** Dijalankan di thread latar. */
    fun render(ctx: Context, uri: Uri, name: String, kind: String): RenderedDoc {
        // Jalur cepat berkas lokal (salinan cache viewer): zip dibaca acak, bagian didekompresi seperlunya.
        // Tanpa ini berkas 50 MB berarti ±50 MB byte mentah + seluruh isi zip terekstrak di RAM sekaligus.
        val path = if (uri.scheme == "file") uri.path else null
        if (path != null) {
            val pkg = try { OPackage(java.io.File(path)) } catch (_: Exception) { null }
            if (pkg != null) pkg.use { p ->
                return when (kind) {
                    "docx" -> try { DocxHtml.render(p, name) } catch (e: Throwable) { if (e is OutOfMemoryError) throw e; DocxHtml.renderSimple(p, name) }
                    "xlsx" -> XlsxHtml.render(p, name)
                    "pptx" -> PptxHtml.render(p, name)
                    else -> throw IllegalArgumentException("Format tidak didukung")
                }
            }
        }
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Berkas tidak bisa dibaca")
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte())
            throw IllegalArgumentException("Bukan berkas Office modern (mungkin format lama atau terenkripsi)")
        return when (kind) {
            "docx" -> try { DocxHtml.render(bytes, name) } catch (e: Throwable) { if (e is OutOfMemoryError) throw e; DocxHtml.renderSimple(bytes, name) }
            "xlsx" -> XlsxHtml.render(bytes, name)
            "pptx" -> PptxHtml.render(bytes, name)
            else -> throw IllegalArgumentException("Format tidak didukung")
        }
    }

    private fun mimeFor(path: String): String {
        val p = path.lowercase()
        return when {
            p.endsWith(".png") -> "image/png"; p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"; p.endsWith(".gif") -> "image/gif"
            p.endsWith(".bmp") -> "image/bmp"; p.endsWith(".webp") -> "image/webp"; p.endsWith(".svg") -> "image/svg+xml"
            p.endsWith(".ttf") -> "font/ttf"; else -> "application/octet-stream"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(ctx: Context, doc: RenderedDoc, onReady: (() -> Unit)? = null): WebView {
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true          // hanya skrip tata letak buatan kita sendiri (pemenggalan halaman, tab)
            allowFileAccess = false; allowContentAccess = false
            // JANGAN set blockNetworkLoads=false: aplikasi tanpa izin INTERNET akan kena SecurityException.
            // Tidak perlu juga — permintaan https://doc.local/ dilayani shouldInterceptRequest sebelum jaringan disentuh.
            builtInZoomControls = true; displayZoomControls = false; setSupportZoom(true)
            useWideViewPort = doc.kind == "docx"; loadWithOverviewMode = doc.kind == "docx"
            cacheMode = WebSettings.LOAD_NO_CACHE
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
        }
        wv.setBackgroundColor(if (doc.kind == "pptx") 0xFF2B2D33.toInt() else if (doc.kind == "docx") 0xFFE9EAEE.toInt() else 0xFFFFFFFF.toInt())
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url
                // dokumen utama dimuat sebagai data: / about: — biarkan WebView menanganinya sendiri
                if (req.isForMainFrame || (url.scheme != "http" && url.scheme != "https")) return null
                if (url.host != "doc.local") return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, ByteArrayInputStream(ByteArray(0)))
                val path = url.path?.trimStart('/').orEmpty()
                if (path.isEmpty()) return null
                if (path.startsWith("_fonts/")) {
                    return try {
                        WebResourceResponse("font/ttf", null, ctx.assets.open("fonts/" + path.removePrefix("_fonts/")))
                    } catch (_: Exception) { WebResourceResponse("font/ttf", null, 404, "Not Found", null, ByteArrayInputStream(ByteArray(0))) }
                }
                val data = doc.res[Uri.decode(path)] ?: doc.res[path] ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, ByteArrayInputStream(ByteArray(0)))
                return WebResourceResponse(mimeFor(path), null, ByteArrayInputStream(data))
            }

            private var readyFired = false
            override fun onPageFinished(view: WebView, url: String?) {
                if (readyFired || onReady == null) return
                readyFired = true
                // Beri waktu skrip pemenggalan halaman & font selesai sebelum dicetak.
                view.postDelayed({ onReady() }, 900L)
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == "doc.local") return true
                // tautan di dokumen dibuka di aplikasi lain (browser / email), bukan di dalam pembaca
                if (u.scheme == "http" || u.scheme == "https" || u.scheme == "mailto" || u.scheme == "tel") {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
                return true
            }
        }
        wv.loadDataWithBaseURL("https://doc.local/", doc.html, "text/html", "utf-8", null)
        return wv
    }

    /** Cetak / simpan PDF persis seperti yang tampil (halaman Word, slide, lembar kerja). */
    fun print(ctx: Context, wv: WebView, name: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val job = name.substringBeforeLast('.').ifBlank { "Dokumen" }
        pm.print(job, wv.createPrintDocumentAdapter(job), PrintAttributes.Builder().build())
    }
}
