package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

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
            var quality = 85
            var scaleFactor = 1.6f

            // Iterasi kompresi hingga file <= targetBytes
            var attempts = 0
            while (attempts < 5) {
                attempts++
                compressPdfIteration(activity, uri, out, scaleFactor, quality)
                val currentSize = out.length()

                if (currentSize <= targetBytes || (quality <= 35 && scaleFactor <= 0.8f)) {
                    break
                }

                // Turunkan kualitas/resolusi secara adaptif
                quality = (quality - 15).coerceAtLeast(30)
                scaleFactor = (scaleFactor * 0.85f).coerceAtLeast(0.7f)
            }

            out
        }) { res ->
            res.onSuccess { compressedFile ->
                val finalSize = compressedFile.length()
                val finalSizeStr = Files.fmtSize(finalSize)
                val origSizeStr = Files.fmtSize(originalSize)
                val savedPct = if (originalSize > 0) ((originalSize - finalSize) * 100 / originalSize) else 0

                activity.toast("PDF berhasil dikompres: $origSizeStr ➔ $finalSizeStr (Hemat $savedPct%)")
                Files.open(activity, Files.uriFor(activity, compressedFile), PDF)
            }.onFailure { activity.fail(it) }
        }
    }

    private fun compressPdfIteration(
        ctx: Context,
        uri: Uri,
        out: File,
        scaleFactor: Float,
        quality: Int
    ) {
        val doc = PdfDocument()
        val bmpPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)

        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val n = renderer.pageCount

            for (i in 0 until n) {
                val pg = renderer.openPage(i)
                val w = pg.width
                val h = pg.height
                val renderW = (w * scaleFactor).toInt().coerceAtLeast(100)
                val renderH = (h * scaleFactor).toInt().coerceAtLeast(100)

                val bmp = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pg.close()

                // Compress bitmap
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                val compressedJpeg = baos.toByteArray()
                val compressedBmp = android.graphics.BitmapFactory.decodeByteArray(compressedJpeg, 0, compressedJpeg.size)

                val pdfPg = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                val c = pdfPg.canvas
                c.drawBitmap(compressedBmp, null, android.graphics.Rect(0, 0, w, h), bmpPaint)
                doc.finishPage(pdfPg)

                bmp.recycle()
                compressedBmp.recycle()
            }

            renderer.close()
            pfd.close()

            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }
}
