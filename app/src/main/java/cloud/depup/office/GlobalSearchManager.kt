package cloud.depup.office

import android.content.Context
import android.net.Uri
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.zip.ZipInputStream

data class GlobalSearchResult(
    val fileName: String,
    val uri: Uri,
    val snippet: String,
    val pageInfo: String,
    val mime: String
)

/**
 * Pencarian Teks Massal Seluruh File (Global Content Search).
 * Mencari kata kunci di dalam isi berkas PDF & Word tanpa membuka file satu per satu.
 */
object GlobalSearchManager {

    fun showSearchDialog(activity: AppCompatActivity) {
        val queryField = activity.field("Ketik kata kunci (misal: Invoice, KTP, Surat)").apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }

        val box = activity.vbox(16).apply { setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16)) }
        box.add(activity.tv("Pencarian Teks di Dalam Berkas", 16f, C.TEXT, true))
        box.add(activity.tv("Mencari kata kunci langsung di dalam isi berkas PDF & Word di HP Anda.", 13f, C.MUTED), top = 4)
        box.add(queryField, top = 10)

        val resultsBox = activity.vbox(8)
        box.add(ScrollView(activity).apply { addView(resultsBox) }, h = activity.dp(260))

        fun performSearch() {
            val q = queryField.text.toString().trim()
            if (q.length < 2) {
                activity.toast("Ketik minimal 2 karakter")
                return
            }

            resultsBox.removeAllViews()
            resultsBox.add(activity.tv("Mencari \"$q\" di dalam berkas…", 13f, C.MUTED), top = 10)

            activity.work("Mencari teks di dalam berkas…", {
                val results = ArrayList<GlobalSearchResult>()
                val recentList = Recent.load(activity)

                val fileList = ArrayList<Pair<Uri, String>>()
                recentList.forEach { item ->
                    try {
                        val uri = Uri.parse(item[0])
                        val name = item[1]
                        fileList.add(Pair(uri, name))
                    } catch (_: Throwable) {}
                }

                val appDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "DepUp Office")
                if (appDir.exists()) {
                    appDir.listFiles()?.forEach { f ->
                        if (f.isFile && (f.name.endsWith(".pdf") || f.name.endsWith(".docx"))) {
                            fileList.add(Pair(Uri.fromFile(f), f.name))
                        }
                    }
                }

                fileList.distinctBy { it.first.toString() }.take(20).forEach { (uri, name) ->
                    val mime = Files.mimeOf(activity, uri).lowercase()
                    if (mime == PDF || name.endsWith(".pdf", ignoreCase = true)) {
                        searchInPdf(activity, uri, name, q, results)
                    } else if (name.endsWith(".docx", ignoreCase = true)) {
                        searchInDocx(activity, uri, name, q, results)
                    }
                }

                results
            }) { res ->
                res.onSuccess { matches ->
                    resultsBox.removeAllViews()
                    if (matches.isEmpty()) {
                        resultsBox.add(activity.tv("Tidak ditemukan teks \"$q\" di dalam berkas", 13f, C.MUTED), top = 16)
                    } else {
                        resultsBox.add(activity.tv("Ditemukan ${matches.size} hasil cocok:", 13f, C.TEXT, true), top = 6)
                        matches.forEach { match ->
                            val card = activity.vbox(8).apply {
                                background = activity.shape(C.SURFACE2, 12)
                                setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
                                isClickable = true
                                isFocusable = true
                                setOnClickListener {
                                    Files.open(activity, match.uri, match.mime)
                                }
                            }
                            card.add(activity.tv("${match.fileName} • ${match.pageInfo}", 13f, C.PRIMARY, true))
                            card.add(activity.tv("… ${match.snippet} …", 12f, C.TEXT))
                            resultsBox.add(card, top = 6)
                        }
                    }
                }.onFailure { activity.fail(it) }
            }
        }

        queryField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Cari Teks dalam Berkas")
            .setView(box)
            .setPositiveButton("Cari") { _, _ -> performSearch() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun searchInPdf(ctx: Context, uri: Uri, name: String, q: String, results: MutableList<GlobalSearchResult>) {
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val count = renderer.pageCount.coerceAtMost(15)

            for (i in 0 until count) {
                val rd = Pdf.render(renderer, i, 1.2f)
                try {
                    val ocr = Ocr.run(rd.bmp)
                    val text = ocr.text
                    if (text.contains(q, ignoreCase = true)) {
                        val idx = text.indexOf(q, ignoreCase = true)
                        val start = (idx - 30).coerceAtLeast(0)
                        val end = (idx + q.length + 30).coerceAtMost(text.length)
                        val snippet = text.substring(start, end).replace('\n', ' ')
                        results.add(
                            GlobalSearchResult(
                                fileName = name,
                                uri = uri,
                                snippet = snippet,
                                pageInfo = "Halaman ${i + 1}",
                                mime = PDF
                            )
                        )
                        if (results.size >= 12) break
                    }
                } finally {
                    rd.bmp.recycle()
                }
            }
            renderer.close()
            pfd.close()
        } catch (_: Throwable) {}
    }

    private fun searchInDocx(ctx: Context, uri: Uri, name: String, q: String, results: MutableList<GlobalSearchResult>) {
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val zip = ZipInputStream(bytes.inputStream())
            var entry = zip.nextEntry
            var docxXml = ""
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    docxXml = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
                entry = zip.nextEntry
            }
            zip.close()

            if (docxXml.isNotEmpty()) {
                val cleanText = docxXml.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
                if (cleanText.contains(q, ignoreCase = true)) {
                    val idx = cleanText.indexOf(q, ignoreCase = true)
                    val start = (idx - 30).coerceAtLeast(0)
                    val end = (idx + q.length + 30).coerceAtMost(cleanText.length)
                    val snippet = cleanText.substring(start, end)
                    results.add(
                        GlobalSearchResult(
                            fileName = name,
                            uri = uri,
                            snippet = snippet,
                            pageInfo = "Dokumen Word",
                            mime = DOCX
                        )
                    )
                }
            }
        } catch (_: Throwable) {}
    }
}
