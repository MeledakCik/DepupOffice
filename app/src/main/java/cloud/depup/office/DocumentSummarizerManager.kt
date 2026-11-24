package cloud.depup.office

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class SummaryResult(
    val title: String,
    val totalWords: Int,
    val keyPoints: List<String>
)

/**
 * Pembuat Ringkasan Dokumen & Catatan Penting (Document Summarizer & Excerpt).
 * Menganalisis isi berkas PDF / Word dan mengekstrak poin-poin utama menjadi ringkasan PDF atau Catatan.
 */
object DocumentSummarizerManager {

    fun showDialog(activity: AppCompatActivity, onPickFile: (cb: (Uri) -> Unit) -> Unit) {
        onPickFile { uri ->
            processDocument(activity, uri)
        }
    }

    private fun processDocument(activity: AppCompatActivity, uri: Uri) {
        val name = Files.nameOf(activity, uri)

        activity.work("Menganalisis & merangkum dokumen…", {
            // buang karakter tersembunyi (zero-width, bidi, Unicode TAG) yang dipakai menyelundupkan instruksi AI
            val text = AiText.clean(readDocumentText(activity, uri, name))
            if (text.isBlank()) throw IllegalStateException("Tidak ada teks yang dapat dibaca di berkas ini")

            val points = extractKeyPoints(text)
            val totalWords = text.split(Regex("\\s+")).size
            SummaryResult(name, totalWords, points)
        }) { res ->
            res.onSuccess { summary ->
                showSummarySheet(activity, uri, summary)
            }.onFailure { activity.fail(it) }
        }
    }

    private fun readDocumentText(ctx: Context, uri: Uri, name: String): String {
        val mime = Files.mimeOf(ctx, uri).lowercase()
        return if (mime == PDF || name.endsWith(".pdf", ignoreCase = true)) {
            val sb = StringBuilder()
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return ""
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val count = renderer.pageCount.coerceAtMost(20)
            for (i in 0 until count) {
                val rd = Pdf.render(renderer, i, 1.2f)
                try {
                    val ocr = Ocr.run(rd.bmp)
                    sb.append(ocr.text).append("\n")
                } finally {
                    rd.bmp.recycle()
                }
            }
            renderer.close()
            pfd.close()
            sb.toString()
        } else if (name.endsWith(".docx", ignoreCase = true)) {
            val input = ctx.contentResolver.openInputStream(uri) ?: return ""
            val zip = ZipInputStream(input.buffered())
            var entry = zip.nextEntry
            var xml = ""
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    xml = Files.readCapped(zip).toString(Charsets.UTF_8)
                    break
                }
                entry = zip.nextEntry
            }
            zip.close()
            // akhir paragraf Word (</w:p>) = baris baru; dulu semua tag jadi spasi → seluruh dokumen satu "paragraf"
            // dan ringkasannya berisi seluruh dokumen sebagai satu poin
            xml.replace(Regex("</w:p>|<w:br[^>]*/>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
                .replace(Regex("[ \\t]+"), " ")
        } else ""
    }

    /**
     * Algoritma ekstraksi kalimat kunci berdasarkan kata kunci & bobot posisi.
     */
    /** Baris PDF yang terpotong (tanpa tanda akhir kalimat) disambung menjadi satu paragraf — sama dengan iOS. */
    internal fun paragraphs(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) { if (current.isNotEmpty()) { out.add(current.toString()); current.clear() }; continue }
            // Judul pendek (≤ 4 kata, tanpa tanda baca akhir) berdiri sendiri, tidak digabung ke paragraf.
            if (current.isEmpty() && line.split(Regex("\\s+")).size <= 4 && line.last() !in ".!?:;,)\"”") { out.add(line); continue }
            if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            if (line.last() in ".!?:;)\"”") { out.add(current.toString()); current.clear() }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    internal fun extractKeyPoints(text: String): List<String> {
        val kept = ArrayList<Set<String>>()
        val paragraphs = paragraphs(text).filter { p ->
            if (p.length <= 25) return@filter false
            val words = p.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
            // Lewati paragraf yang (hampir) sama dengan yang sudah dipilih, mis. isi halaman berulang.
            val dup = kept.any { k -> words.intersect(k).size.toDouble() / (words.union(k).size).coerceAtLeast(1) >= 0.8 }
            if (!dup) kept.add(words)
            !dup
        }

        if (paragraphs.isEmpty()) {
            return listOf(text.take(200))
        }

        val keywords = listOf("tujuan", "kesimpulan", "penting", "total", "ringkasan", "rekomendasi", "hasil", "catatan", "pasal", "syarat")
        val scored = paragraphs.map { p ->
            var score = p.length.coerceAtMost(300)
            val lower = p.lowercase()
            keywords.forEach { kw ->
                if (lower.contains(kw)) score += 150
            }
            Pair(p, score)
        }

        // 5 paragraf terpenting, ditampilkan dalam URUTAN ASLI dokumen (bukan urutan skor)
        return scored.withIndex().sortedByDescending { it.value.second }
            .take(5)
            .sortedBy { it.index }
            .map { it.value.first }
    }

    private fun showSummarySheet(
        activity: AppCompatActivity,
        srcUri: Uri,
        summary: SummaryResult
    ) {
        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_note, C.ACCENT, C.BLUE_BG,
                "Ringkasan Dokumen", "${summary.totalWords} kata • ${summary.title}", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val contentBox = activity.vbox(10).apply {
                background = activity.shape(C.SURFACE2, 14)
                setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))

                add(activity.tv("Poin-Poin Utama / Ringkasan:", 13f, C.TEXT, true))
                summary.keyPoints.forEachIndexed { i, pt ->
                    add(activity.tv("${i + 1}. $pt", 12f, C.TEXT), top = 6)
                }
            }

            pad.add(ScrollView(activity).apply { addView(contentBox) }, h = activity.dp(260))

            val actionRow = activity.hbox().apply {
                add(activity.btn("Simpan PDF Ringkasan", true, C.PRIMARY, R.drawable.ic_pdf) {
                    dismiss()
                    generateSummaryPdf(activity, summary)
                }, w = 0, weight = 1f)

                add(activity.btn("Salin Teks", false, C.ACCENT, R.drawable.ic_share) {
                    val fullText = "RINGKASAN DOKUMEN: ${summary.title}\n\n" + summary.keyPoints.mapIndexed { i, p -> "${i + 1}. $p" }.joinToString("\n\n")
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Ringkasan Dokumen", fullText))
                    activity.toast("Ringkasan disalin ke papan klip")
                }, w = 0, weight = 1f, side = 8)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun generateSummaryPdf(activity: AppCompatActivity, summary: SummaryResult) {
        val outName = "Ringkasan_" + Files.baseName(summary.title)
        val out = Files.newOut(activity, outName, "pdf")

        activity.work("Membuat PDF Ringkasan…", {
            val doc = PdfDocument()
            val pw = 595; val ph = 842 // A4
            var pageNo = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
            var c = page.canvas
            c.drawColor(Color.WHITE)

            val pTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 20f; isFakeBoldText = true }
            val pSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF65625D.toInt(); textSize = 12f }
            val pText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); textSize = 13f }
            val pLine = Paint().apply { color = 0xFFE4E2DD.toInt(); strokeWidth = 1.5f }

            var y = 40f
            c.drawText("RINGKASAN DOKUMEN", 40f, y, pTitle)
            y += 20f
            c.drawText("Sumber: ${summary.title} • Total ${summary.totalWords} kata", 40f, y, pSub)
            c.drawLine(40f, y + 12f, pw - 40f, y + 12f, pLine)
            y += 36f

            /** Halaman baru bila sisa ruang < [need] (dulu ringkasan panjang tergambar di luar kertas). */
            fun room(need: Float) {
                if (y + need <= ph - 40f) return
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
                c = page.canvas
                c.drawColor(Color.WHITE)
                y = 50f
            }
            val pPoint = TextPaint(pTitle).apply { textSize = 14f }
            summary.keyPoints.forEachIndexed { idx, pt ->
                room(44f)
                c.drawText("${idx + 1}. Poin Penting:", 40f, y, pPoint)
                y += 20f
                val words = pt.split(" ")
                var line = ""
                for (w in words) {
                    if (line.isNotEmpty() && pText.measureText("$line $w") > pw - 80f) {
                        room(18f)
                        c.drawText(line, 40f, y, pText)
                        y += 18f
                        line = w
                    } else {
                        line = if (line.isEmpty()) w else "$line $w"
                    }
                }
                if (line.isNotEmpty()) {
                    room(18f)
                    c.drawText(TextUtils.ellipsize(line, pText, pw - 80f, TextUtils.TruncateAt.END).toString(), 40f, y, pText)
                    y += 24f
                }
            }

            doc.finishPage(page)
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
        }) { res ->
            res.onSuccess {
                activity.toast("PDF Ringkasan disimpan!")
                Files.open(activity, Files.uriFor(activity, out), PDF)
            }.onFailure { activity.fail(it) }
        }
    }
}
