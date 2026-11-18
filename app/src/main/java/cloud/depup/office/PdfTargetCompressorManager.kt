package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Kompresi PDF Target Ukuran Presisi (Precision Target PDF Compressor).
 * Mengompres PDF hingga berukuran di bawah target presisi (<200 KB, <500 KB, <1 MB) untuk CPNS/Portal Kerja.
 */
object PdfTargetCompressorManager {

    val TARGET_200_KB = 200L * 1024L
    val TARGET_500_KB = 500L * 1024L
    val TARGET_1_MB = 1024L * 1024L

    fun showDialog(activity: AppCompatActivity, onPickFile: (cb: (Uri) -> Unit) -> Unit) {
        onPickFile { uri ->
            showCompressSheet(activity, uri)
        }
    }

    private fun showCompressSheet(activity: AppCompatActivity, uri: Uri) {
        val originalSize = Files.sizeOf(activity, uri)
        val originalSizeStr = Files.fmtSize(originalSize)
        val name = Files.nameOf(activity, uri)

        var selectedTargetBytes = TARGET_200_KB
        var targetIndex = 0

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_compress, C.ACCENT, C.BLUE_BG,
                "Kompresi PDF Presisi", "$name • $originalSizeStr", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Ukuran File Awal: $originalSizeStr", 13f, C.TEXT, true))
                add(activity.tv("Pilih target batas ukuran file maksimal untuk CPNS/Pendaftaran:", 12f, C.MUTED), top = 2)

                val targetHolder = activity.vbox()
                targetHolder.add(activity.segmented(listOf("< 200 KB (CPNS)", "< 500 KB", "< 1 MB"), targetIndex) { idx ->
                    targetIndex = idx
                    selectedTargetBytes = when (idx) {
                        0 -> TARGET_200_KB
                        1 -> TARGET_500_KB
                        else -> TARGET_1_MB
                    }
                })
                add(targetHolder, top = 8)
            }

            pad.add(formBox)

            val actionRow = activity.hbox().apply {
                add(activity.btn("Kompres ke Target", true, C.PRIMARY, R.drawable.ic_compress) {
                    dismiss()
                    compressToTarget(activity, uri, name, originalSize, selectedTargetBytes)
                }, w = 0, weight = 1f)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun compressToTarget(
        activity: AppCompatActivity,
        uri: Uri,
        name: String,
        originalSize: Long,
        targetBytes: Long
    ) {
        val outName = Files.baseName(name) + "_kompres"
        val out = Files.newOut(activity, outName, "pdf")

        activity.work("Mengompres PDF ke target presisi…", {
            // Tangga (skala render, kualitas JPEG) dari paling tajam ke paling kecil. Pencarian biner: hasil = langkah
            // PALING TAJAM yang masih muat di target (±4 kali tulis, bukan mencoba satu per satu).
            var best = -1
            var written = -1
            var lo = 0
            var hi = STEPS.lastIndex
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                writeAt(activity, uri, out, STEPS[mid].first, STEPS[mid].second)
                written = mid
                if (out.length() <= targetBytes) { best = mid; hi = mid - 1 } else lo = mid + 1
            }
            // berkas di disk = langkah terpilih; target tak tercapai → hasil terkecil yang mungkin
            val pick = if (best >= 0) best else STEPS.lastIndex
            if (written != pick) writeAt(activity, uri, out, STEPS[pick].first, STEPS[pick].second)
            out to (best >= 0)
        }) { res ->
            res.onSuccess { (compressedFile, reached) ->
                val finalSize = compressedFile.length()
                val finalSizeStr = Files.fmtSize(finalSize)
                val origSizeStr = Files.fmtSize(originalSize)
                if (reached) {
                    val savedPct = if (originalSize > finalSize && originalSize > 0) ((originalSize - finalSize) * 100 / originalSize) else 0
                    activity.toast("PDF berhasil dikompres: $origSizeStr ➔ $finalSizeStr (Hemat $savedPct%)")
                } else {
                    // jujur: jangan menyebut "berhasil" bila batas tidak tercapai (mis. terlalu banyak halaman)
                    activity.toast("Target ${Files.fmtSize(targetBytes)} tidak tercapai. Hasil terkecil: $finalSizeStr. " +
                        "Coba kurangi jumlah halaman (Pisah PDF) lalu kompres lagi.")
                }
                Files.open(activity, Files.uriFor(activity, compressedFile), PDF)
            }.onFailure { activity.fail(it) }
        }
    }

    /** (skala render terhadap ukuran halaman PDF, kualitas JPEG) — makin ke belakang makin kecil. */
    private val STEPS = listOf(
        1.6f to 80, 1.4f to 72, 1.25f to 64, 1.1f to 58, 1.0f to 52,
        0.9f to 46, 0.8f to 40, 0.7f to 35, 0.6f to 30, 0.5f to 26
    )

    /**
     * Tulis ulang PDF: tiap halaman dirender (latar putih) lalu disimpan sebagai JPEG ASLI di dalam PDF (DCTDecode,
     * lewat [PdfWriter]). Dulu gambar JPEG di-decode lagi lalu digambar ke PdfDocument Android — yang menyimpan
     * bitmap TANPA kompresi JPEG, sehingga hasilnya sering lebih besar dari aslinya; halaman transparan juga jadi hitam.
     */
    private fun writeAt(ctx: Context, uri: Uri, out: File, scale: Float, quality: Int) {
        Src(ctx, uri).use { s ->
            val n = s.r.pageCount
            val w = PdfWriter(out, n, null)
            try {
                for (i in 0 until n) {
                    val rd = Pdf.render(s.r, i, scale)
                    try {
                        val jpg = ByteArrayOutputStream().also { rd.bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                        w.addPage(jpg, rd.bmp.width, rd.bmp.height, rd.pw.toFloat(), rd.ph.toFloat(),
                            0f, 0f, rd.pw.toFloat(), rd.ph.toFloat(), null)
                    } finally { rd.bmp.recycle() }
                }
                w.finish()
            } catch (t: Throwable) { w.abort(); throw t }
        }
    }
}
