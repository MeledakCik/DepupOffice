package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfGenerator {

    /**
     * Menggabungkan daftar Bitmap hasil scan (yang sudah di-crop & filter)
     * menjadi 1 file PDF berstandar A4 dan menguncinya di folder /Documents/DepupOffice/.
     */
    fun generatePdf(context: Context, bitmaps: List<Bitmap>, baseName: String = "CamScanner"): File {
        if (bitmaps.isEmpty()) throw IllegalArgumentException("Daftar gambar tidak boleh kosong")

        val pdfDocument = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val pageW = 595 // Standard A4 Width in points
        val pageH = 842 // Standard A4 Height in points

        bitmaps.forEachIndexed { index, bitmap ->
            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            // Skalakan bitmap agar pas di tengah halaman A4 dengan margin 20pt
            val margin = 20f
            val availW = pageW - (margin * 2)
            val availH = pageH - (margin * 2)

            val scale = Math.min(availW / bitmap.width, availH / bitmap.height)
            val drawW = bitmap.width * scale
            val drawH = bitmap.height * scale

            val dx = margin + (availW - drawW) / 2f
            val dy = margin + (availH - drawH) / 2f

            canvas.save()
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            canvas.restore()

            pdfDocument.finishPage(page)
        }

        // Tentukan lokasi direktori simpan: /Documents/DepupOffice/
        val documentsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "DepupOffice"
        )
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pdfFile = File(documentsDir, "${baseName}_$timeStamp.pdf")

        FileOutputStream(pdfFile).use { outputStream ->
            pdfDocument.writeTo(outputStream)
        }
        pdfDocument.close()

        return pdfFile
    }
}
