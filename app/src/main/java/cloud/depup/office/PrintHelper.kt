package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream

object PrintHelper {

    /** Cetak berkas PDF secara langsung lewat layanan PrintManager Android (printer WiFi, Bluetooth, atau Simpan PDF). */
    fun printPdf(context: Context, uri: Uri, documentName: String) {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val jobName = "DepupOffice_$documentName"
            printManager.print(jobName, object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder(documentName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                        .build()
                    callback.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback
                ) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destination.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Cetak gambar/foto (misal hasil scan KTP 2 sisi, stempel, atau nota) langsung ke printer. */
    fun printBitmap(context: Context, bitmap: Bitmap, jobName: String) {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print(jobName, object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder(jobName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build()
                    callback.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback
                ) {
                    try {
                        val pdfDocument = PdfDocument()
                        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size in points
                        val page = pdfDocument.startPage(pageInfo)
                        val canvas = page.canvas

                        // Fit bitmap nicely on A4 page
                        val scale = Math.min(
                            (canvas.width - 40).toFloat() / bitmap.width,
                            (canvas.height - 40).toFloat() / bitmap.height
                        )
                        val dx = (canvas.width - bitmap.width * scale) / 2f
                        val dy = (canvas.height - bitmap.height * scale) / 2f

                        canvas.save()
                        canvas.translate(dx, dy)
                        canvas.scale(scale, scale)
                        canvas.drawBitmap(bitmap, 0f, 0f, null)
                        canvas.restore()

                        pdfDocument.finishPage(page)

                        FileOutputStream(destination.fileDescriptor).use { output ->
                            pdfDocument.writeTo(output)
                        }
                        pdfDocument.close()
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Cetak teks dokumen Word/Catatan langsung ke printer dengan format A4 rapi. */
    fun printText(context: Context, title: String, text: String) {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print("DepupOffice_$title", object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder(title)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build()
                    callback.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback
                ) {
                    try {
                        val pdfDocument = PdfDocument()
                        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
                        val page = pdfDocument.startPage(pageInfo)
                        val canvas = page.canvas

                        val titlePaint = Paint().apply {
                            color = Color.BLACK
                            textSize = 18f
                            isFakeBoldText = true
                        }
                        val textPaint = Paint().apply {
                            color = Color.DKGRAY
                            textSize = 12f
                        }

                        canvas.drawText(title, 40f, 50f, titlePaint)

                        var y = 80f
                        val lines = text.split("\n")
                        for (line in lines) {
                            if (y > 800f) break
                            canvas.drawText(line, 40f, y, textPaint)
                            y += 18f
                        }

                        pdfDocument.finishPage(page)
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            pdfDocument.writeTo(output)
                        }
                        pdfDocument.close()
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Cetak tabel Spreadsheet Excel secara langsung ke printer. */
    fun printSpreadsheet(context: Context, title: String, grid: List<List<String>>) {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print("DepupOffice_$title", object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder(title)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build()
                    callback.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback
                ) {
                    try {
                        val pdfDocument = PdfDocument()
                        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
                        val page = pdfDocument.startPage(pageInfo)
                        val canvas = page.canvas

                        val titlePaint = Paint().apply {
                            color = Color.BLACK
                            textSize = 18f
                            isFakeBoldText = true
                        }
                        val linePaint = Paint().apply {
                            color = Color.LTGRAY
                            strokeWidth = 1f
                        }
                        val textPaint = Paint().apply {
                            color = Color.BLACK
                            textSize = 11f
                        }

                        canvas.drawText(title, 40f, 45f, titlePaint)

                        var startY = 70f
                        val cellWidth = 115f
                        val cellHeight = 25f

                        grid.take(28).forEachIndexed { r, row ->
                            row.take(4).forEachIndexed { c, cellVal ->
                                val left = 40f + (c * cellWidth)
                                val top = startY + (r * cellHeight)
                                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, linePaint)
                                val truncated = if (cellVal.length > 15) cellVal.take(13) + ".." else cellVal
                                canvas.drawText(truncated, left + 8f, top + 17f, textPaint)
                            }
                        }

                        pdfDocument.finishPage(page)
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            pdfDocument.writeTo(output)
                        }
                        pdfDocument.close()
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
