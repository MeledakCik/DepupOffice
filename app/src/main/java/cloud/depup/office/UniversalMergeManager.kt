package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Penggabung Campuran File ke PDF (Universal File Merger).
 * Menggabungkan campuran file foto (JPG/PNG/WebP) dan PDF menjadi 1 berkas PDF utuh.
 */
object UniversalMergeManager {

    fun merge(ctx: Context, uris: List<Uri>, out: File, progress: (Int, Int) -> Unit = { _, _ -> }) {
        if (uris.isEmpty()) throw IllegalArgumentException("Tidak ada file yang dipilih")

        val doc = PdfDocument()
        var pageCount = 0
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        try {
            uris.forEachIndexed { idx, uri ->
                progress(idx + 1, uris.size)
                val mime = Files.mimeOf(ctx, uri).lowercase()
                val name = Files.nameOf(ctx, uri).lowercase()

                if (mime == PDF || name.endsWith(".pdf")) {
                    // Gabungkan PDF
                    try {
                        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) {
                            val renderer = android.graphics.pdf.PdfRenderer(pfd)
                            val n = renderer.pageCount
                            for (pIdx in 0 until n) {
                                pageCount++
                                val pg = renderer.openPage(pIdx)
                                val w = pg.width
                                val h = pg.height
                                val bmp = Bitmap.createBitmap((w * 1.5f).toInt(), (h * 1.5f).toInt(), Bitmap.Config.ARGB_8888)
                                pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                pg.close()

                                val pdfPg = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageCount).create())
                                val c = pdfPg.canvas
                                c.drawBitmap(bmp, null, Rect(0, 0, w, h), bmpPaint)
                                doc.finishPage(pdfPg)
                                bmp.recycle()
                            }
                            renderer.close()
                            pfd.close()
                        }
                    } catch (_: Throwable) {}
                } else {
                    // Gabungkan Foto / Gambar
                    try {
                        val bmp = Pdf.decode(ctx, uri, 1800)
                        if (bmp != null) {
                            pageCount++
                            val land = bmp.width > bmp.height
                            val pw = if (land) 842 else 595 // A4 standard point size
                            val ph = if (land) 595 else 842

                            val pdfPg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageCount).create())
                            val c = pdfPg.canvas
                            c.drawColor(Color.WHITE)

                            // Scaled Rect
                            val scale = Math.min(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
                            val dw = (bmp.width * scale).toInt()
                            val dh = (bmp.height * scale).toInt()
                            val dx = (pw - dw) / 2
                            val dy = (ph - dh) / 2

                            c.drawBitmap(bmp, null, Rect(dx, dy, dx + dw, dy + dh), bmpPaint)
                            doc.finishPage(pdfPg)
                            bmp.recycle()
                        }
                    } catch (_: Throwable) {}
                }
            }

            if (pageCount == 0) throw IllegalStateException("Tidak ada halaman yang berhasil digabung")
            FileOutputStream(out).use { doc.writeTo(it) }
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            doc.close()
        }
    }
}
