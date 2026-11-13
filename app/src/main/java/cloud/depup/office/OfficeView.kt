package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore

/**
 * Pembaca DOCX / XLSX / PPTX berfidelitas tinggi: dokumen diubah ke HTML oleh [DocxHtml] / [XlsxHtml] / [PptxHtml]
 * (murni Kotlin) lalu ditampilkan di WebView. Gambar dari dalam berkas & font pengganti metrik-sama (turunan Carlito untuk Calibri,
 * Caladea untuk Cambria, Liberation untuk Arial/Times/Courier — lihat assets/fonts/README-fonts.txt) dilayani dari memori/aset lewat
 * https://doc.local/ — tidak ada akses jaringan sama sekali.
 */
object OfficeView {
    init { OxUtil.viewerCss = ::themeCss }

    private fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)

    /**
     * Tampilan pembaca yang SERAGAM untuk semua format: kanvas di sekitar halaman/slide = warna kanvas pembaca PDF &
     * editor (mengikuti tema terang/gelap). Dulu Word abu kebiruan terang, PowerPoint abu gelap, PDF abu hangat —
     * dan Word tetap terang di mode gelap. Lembar Excel tetap putih (warna isi asli); hanya bilah tab lembar yang
     * mengikuti gaya aplikasi (pil tinta untuk lembar aktif, sama dengan tab di editor).
     */
    private fun themeCss(kind: String): String {
        val canvas = hex(C.CANVAS); val muted = hex(C.MUTED)
        return when (kind) {
            "docx" -> "@media screen{html,body{background:$canvas}.page{box-shadow:0 1px 3px rgba(0,0,0,.22)}}"
            "pptx" -> "@media screen{html,body{background:$canvas}.sw{box-shadow:0 1px 3px rgba(0,0,0,.22)}.no,.err{color:$muted}}"
            "xlsx" -> "@media screen{#tabs{background:${hex(C.SURFACE)};border-top:1px solid ${hex(C.LINE)};padding:6px 8px}" +
                ".tab{background:${hex(C.SURFACE)};color:${hex(C.TEXT)};border:1px solid ${hex(C.LINE)};border-radius:100px;padding:6px 14px}" +
                ".tab.on{background:${hex(C.INK)};border-color:${hex(C.INK)};color:${hex(C.ON_INK)};font-weight:bold}}"
            else -> ""
        }
    }

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

    /**
     * Dijalankan di thread latar. Hasilnya MEMEGANG berkas zip yang terbuka (gambar dibaca saat diminta WebView) —
     * pemanggil wajib [RenderedDoc.close] saat pembaca ditutup.
     */
    fun render(ctx: Context, uri: Uri, name: String, kind: String): RenderedDoc {
        // Jalur cepat berkas lokal (salinan cache viewer): zip dibaca acak, bagian didekompresi seperlunya.
        // Tanpa ini berkas 50 MB berarti ±50 MB byte mentah + seluruh isi zip terekstrak di RAM sekaligus.
        val path = if (uri.scheme == "file") uri.path else null
        if (path != null) {
            val pkg = try { OPackage(java.io.File(path)) } catch (_: Exception) { null }
            if (pkg != null) {
                var ok = false
                try {
                    val doc = when (kind) {
                        "docx" -> try { DocxHtml.render(pkg, name) } catch (e: Throwable) { if (e is OutOfMemoryError) throw e; DocxHtml.renderSimple(pkg, name) }
                        "xlsx" -> XlsxHtml.render(pkg, name)
                        "pptx" -> PptxHtml.render(pkg, name)
                        else -> throw IllegalArgumentException("Format tidak didukung")
                    }
                    ok = true
                    return doc
                } finally { if (!ok) pkg.close() }
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

    // ------------------------------------------------------------------ gambar dokumen
    /**
     * Gambar siap kirim per dokumen (LRU berbatas byte). WebView kadang meminta gambar yang sama berulang (kepala
     * halaman di tiap halaman, gambar yang digulir lagi) — konversi/pengecilan tidak diulang.
     */
    private class ImageCache(private val budget: Int) {
        private val map = LinkedHashMap<Int, PreparedImage>(32, 0.75f, true)
        private var bytes = 0
        @Synchronized fun get(id: Int): PreparedImage? = map[id]
        @Synchronized fun put(id: Int, p: PreparedImage) {
            if (p.bytes.size > budget / 2) return
            map.put(id, p)?.let { bytes -= it.bytes.size }
            bytes += p.bytes.size
            val it = map.entries.iterator()
            while (bytes > budget && it.hasNext()) { val e = it.next(); if (e.key == id) continue; bytes -= e.value.bytes.size; it.remove() }
        }
    }

    /** Dekode bitmap besar dibatasi 2 sekaligus: puluhan foto 12 MP diminta bersamaan tidak menghabiskan memori. */
    private val decodeGate = Semaphore(2)

    /** Sisi terpanjang maksimum gambar yang dikirim ke WebView. */
    private fun capPx(): Int = if (Perf.low) 1600 else 2400

    /**
     * Ukuran cukup untuk [item]: ukuran tampil (px CSS) × 3 — tetap tajam di layar rapat & saat diperbesar — dibatasi
     * [capPx]. Foto kamera 4000 px yang tampil 8 cm di dokumen cukup ±1000 px: memori dekode WebView turun 16×.
     */
    private fun targetPx(item: DocRes.Item): Int {
        val css = maxOf(item.cssW, item.cssH)
        val cap = capPx()
        return if (css > 0) (css * 3).toInt().coerceIn(640, cap) else cap
    }

    private fun prepare(doc: RenderedDoc, item: DocRes.Item): PreparedImage {
        val raw = try { doc.res.read(item) } catch (_: Throwable) { null } ?: return PLACEHOLDER
        val target = targetPx(item)
        val p = (try { OfficeImage.prepare(raw, capPx()) } catch (_: Throwable) { null })
            ?: platformDecode(raw, target)                               // mis. HEIF
            ?: return PLACEHOLDER
        return if (p.raster) shrink(p, target) ?: p else p
    }

    private val PLACEHOLDER = PreparedImage("image/svg+xml", OfficeImage.PLACEHOLDER, false)

    /** Dekoder Android untuk format yang tidak dikenal WebView (HEIF/HEIC dari HP). */
    private fun platformDecode(raw: ByteArray, target: Int): PreparedImage? = try {
        decodeGate.acquire()
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) null else {
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
                bmp?.let { encode(it, false) }
            }
        } finally { decodeGate.release() }
    } catch (_: Throwable) { null }

    /**
     * Foto jauh lebih besar dari yang dibutuhkan → diperkecil (orientasi EXIF dipertahankan seperti tampilan WebView).
     * null = kirim apa adanya.
     */
    private fun shrink(p: PreparedImage, target: Int): PreparedImage? {
        if (p.mime == "image/gif" || p.mime == "image/svg+xml" || p.mime == "image/x-icon") return null
        val raw = p.bytes
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { BitmapFactory.decodeByteArray(raw, 0, raw.size, o) } catch (_: Throwable) { return null }
        val w = o.outWidth; val h = o.outHeight
        if (w <= 0 || h <= 0) return null
        val long = maxOf(w, h)
        // cukup kecil: biarkan (kecuali berkasnya sangat besar untuk ukurannya, mis. BMP mentah)
        if (long <= target * 1.25 && (raw.size < 3 * 1024 * 1024 || p.mime != "image/bmp")) return null
        return try {
            decodeGate.acquire()
            try {
                var sample = 1
                while (long / (sample * 2) >= target) sample *= 2
                var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
                val m = Matrix()
                val f = target.toFloat() / maxOf(bmp.width, bmp.height)
                if (f < 0.95f) m.postScale(f, f)
                if (p.mime == "image/jpeg") {
                    val orient = try { ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Throwable) { ExifInterface.ORIENTATION_NORMAL }
                    when (orient) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f); ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f); ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                    }
                }
                if (!m.isIdentity) { val nb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true); if (nb !== bmp) bmp.recycle(); bmp = nb }
                encode(bmp, p.mime == "image/jpeg")
            } finally { decodeGate.release() }
        } catch (_: Throwable) { null }
    }

    private fun encode(bmp: Bitmap, photo: Boolean): PreparedImage {
        val alpha = !photo && bmp.hasAlpha() && hasTransparency(bmp)
        val out = ByteArrayOutputStream(bmp.width * bmp.height / 4)
        bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (alpha) 100 else 90, out)
        bmp.recycle()
        return PreparedImage(if (alpha) "image/png" else "image/jpeg", out.toByteArray(), true)
    }

    /** Ada piksel tembus pandang? (sampel kisi — PNG RGBA yang sebenarnya buram cukup dikirim sebagai JPEG). */
    private fun hasTransparency(b: Bitmap): Boolean {
        val sx = maxOf(1, b.width / 48); val sy = maxOf(1, b.height / 48)
        var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { if ((b.getPixel(x, y) ushr 24) < 250) return true; x += sx }; y += sy }
        return false
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, ByteArrayInputStream(ByteArray(0)))

    fun webView(ctx: Context, doc: RenderedDoc, onReady: (() -> Unit)? = null): WebView = load(newWebView(ctx, doc.kind), doc, onReady)

    /**
     * WebView kosong yang sudah diatur untuk jenis dokumen [kind]. Dipisah dari [load] supaya bisa dibuat SELAGI dokumen
     * masih diurai di latar: membuat WebView pertama kali (memuat mesin Chromium) memakan ratusan milidetik, dan kini
     * berjalan bersamaan dengan penguraian — bukan sesudahnya.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun newWebView(ctx: Context, kind: String): WebView {
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true          // hanya skrip tata letak buatan kita sendiri (pemenggalan halaman, tab)
            allowFileAccess = false; allowContentAccess = false
            // JANGAN set blockNetworkLoads=false: aplikasi tanpa izin INTERNET akan kena SecurityException.
            // Tidak perlu juga — permintaan https://doc.local/ dilayani shouldInterceptRequest sebelum jaringan disentuh.
            builtInZoomControls = true; displayZoomControls = false; setSupportZoom(true)
            useWideViewPort = kind == "docx"; loadWithOverviewMode = kind == "docx"
            cacheMode = WebSettings.LOAD_NO_CACHE
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
        }
        // warna sebelum HTML tergambar = kanvas yang sama (tanpa kedip warna lain saat dokumen dibuka)
        wv.setBackgroundColor(if (kind == "xlsx") 0xFFFFFFFF.toInt() else C.CANVAS)
        return wv
    }

    /**
     * Tampilkan [doc] di [wv] (dari [newWebView] dengan jenis yang sama). [onCrash]: mesin tampilan WebView berhenti
     * (dokumen ekstrem / memori habis) — tanpa penanganan ini Android ikut menutup SELURUH aplikasi.
     */
    fun load(wv: WebView, doc: RenderedDoc, onReady: (() -> Unit)? = null, onCrash: (() -> Unit)? = null): WebView {
        val ctx = wv.context
        val images = ImageCache(if (Perf.low) 12 shl 20 else 28 shl 20)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url
                // dokumen utama dimuat sebagai data: / about: — biarkan WebView menanganinya sendiri
                if (req.isForMainFrame || (url.scheme != "http" && url.scheme != "https")) return null
                if (url.host != "doc.local") return notFound()
                val path = url.path?.trimStart('/').orEmpty()
                if (path.isEmpty()) return null
                if (path.startsWith("_fonts/")) {
                    return try {
                        WebResourceResponse("font/ttf", null, ctx.assets.open("fonts/" + path.removePrefix("_fonts/")))
                    } catch (_: Exception) { WebResourceResponse("font/ttf", null, 404, "Not Found", null, ByteArrayInputStream(ByteArray(0))) }
                }
                // gambar dokumen: dibaca dari zip SEKARANG (bukan saat penguraian), diubah bila perlu (EMF/WMF/TIFF),
                // diperkecil bila jauh lebih besar dari ukuran tampilnya; gagal → kotak pengganti, bukan ikon rusak
                if (path.startsWith("r/")) {
                    val item = path.substring(2).toIntOrNull()?.let { doc.res.item(it) } ?: return notFound()
                    val img = images.get(item.id) ?: synchronized(item) {
                        images.get(item.id) ?: prepare(doc, item).also { images.put(item.id, it) }
                    }
                    return WebResourceResponse(img.mime, if (img.mime == "image/svg+xml") "utf-8" else null, ByteArrayInputStream(img.bytes))
                }
                return notFound()
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                // WebView ini tidak bisa dipakai lagi: lepas & hancurkan, lalu biarkan layar menampilkan jalan keluar
                try { (view.parent as? android.view.ViewGroup)?.removeView(view) } catch (_: Throwable) {}
                try { view.destroy() } catch (_: Throwable) {}
                onCrash?.invoke()
                return true
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
        doc.dropHtml()          // HTML sudah disalin ke WebView: string besar ini tidak perlu ditahan
        return wv
    }

    /** Cetak / simpan PDF persis seperti yang tampil (halaman Word, slide, lembar kerja). */
    fun print(ctx: Context, wv: WebView, name: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val job = name.substringBeforeLast('.').ifBlank { "Dokumen" }
        pm.print(job, wv.createPrintDocumentAdapter(job), PrintAttributes.Builder().build())
    }
}
