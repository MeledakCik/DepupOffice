package cloud.depup.office

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Penomoran Halaman & Header/Footer PDF (PDF Page Numbering & Header/Footer).
 * Menambahkan nomor halaman otomatis ("Hal X dari Y") & header/footer pada seluruh halaman PDF.
 */
object PdfPageNumberingManager {

    fun showDialog(activity: AppCompatActivity, onPickFile: (cb: (Uri) -> Unit) -> Unit) {
        onPickFile { uri ->
            showNumberingSheet(activity, uri)
        }
    }

    private fun showNumberingSheet(activity: AppCompatActivity, uri: Uri) {
        val total = try { Pdf.pageCount(activity, uri) } catch (_: Throwable) { 1 }
        val name = Files.nameOf(activity, uri)

        val fHeader = activity.field("Teks Header Opsional (mis: Laporan Resmi)")
        var position = 0 // 0 = Bottom Right, 1 = Bottom Center, 2 = Bottom Left
        var formatType = 0 // 0 = "Hal X dari Y", 1 = "Halaman X", 2 = "X"
        var skipCover = true

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_crop, C.ACCENT, C.BLUE_BG,
                "Penomoran Halaman PDF", "$name • $total halaman", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Format Penomoran", 12f, C.MUTED))
                val fmtHolder = activity.vbox()
                fmtHolder.add(activity.segmented(listOf("Hal X dari Y", "Halaman X", "Hanya Angka (X)"), formatType) {
                    formatType = it
                })
                add(fmtHolder, top = 4)

                add(activity.tv("Posisi Nomor Halaman", 12f, C.MUTED), top = 8)
                val posHolder = activity.vbox()
                posHolder.add(activity.segmented(listOf("Tengah Bawah", "Kanan Bawah", "Kiri Bawah"), position) {
                    position = it
                })
                add(posHolder, top = 4)

                add(activity.tv("Header Teks Opsional (Atas)", 12f, C.MUTED), top = 8)
                add(fHeader)

                val chkCover = android.widget.CheckBox(activity).apply {
                    text = "Lewati Halaman Sampul Pertama (Mulai Hal 2)"
                    setTextColor(C.TEXT)
                    isChecked = skipCover
                    setOnCheckedChangeListener { _, isChecked -> skipCover = isChecked }
                }
                add(chkCover, top = 8)
            }

            pad.add(formBox)

            val actionRow = activity.hbox().apply {
                add(activity.btn("Terapkan Nomor Halaman", true, C.PRIMARY, R.drawable.ic_crop) {
                    dismiss()
                    val headerText = fHeader.text.toString().trim()
                    applyNumbering(activity, uri, name, formatType, position, headerText, skipCover)
                }, w = 0, weight = 1f)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun applyNumbering(
        activity: AppCompatActivity,
        uri: Uri,
        name: String,
        formatType: Int,
        position: Int,
        headerText: String,
        skipCover: Boolean
    ) {
        val outName = Files.baseName(name) + "_nomor"
        val out = Files.newOut(activity, outName, "pdf")

        activity.work("Menambahkan nomor halaman PDF…", {
            val doc = PdfDocument()
            val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val pNum = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt(); textSize = 11f }
            val pHead = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF888888.toInt(); textSize = 10f; isFakeBoldText = true }

            val pfd = activity.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Gagal membaca file PDF")
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val total = renderer.pageCount

            for (i in 0 until total) {
                val pg = renderer.openPage(i)
                val w = pg.width
                val h = pg.height

                val bmp = Bitmap.createBitmap((w * 1.5f).toInt(), (h * 1.5f).toInt(), Bitmap.Config.ARGB_8888)
                pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pg.close()

                val pdfPg = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                val c = pdfPg.canvas
                c.drawBitmap(bmp, null, android.graphics.Rect(0, 0, w, h), bmpPaint)

                val pageNum = i + 1
                val shouldNumber = !skipCover || pageNum > 1

                if (shouldNumber) {
                    val numStr = when (formatType) {
                        0 -> "Hal $pageNum dari $total"
                        1 -> "Halaman $pageNum"
                        else -> "$pageNum"
                    }

                    val margin = w * 0.05f
                    val y = h - (h * 0.03f)

                    when (position) {
                        0 -> { pNum.textAlign = Paint.Align.CENTER; c.drawText(numStr, w / 2f, y, pNum) }
                        1 -> { pNum.textAlign = Paint.Align.RIGHT; c.drawText(numStr, w - margin, y, pNum) }
                        else -> { pNum.textAlign = Paint.Align.LEFT; c.drawText(numStr, margin, y, pNum) }
                    }

                    if (headerText.isNotBlank()) {
                        pHead.textAlign = Paint.Align.LEFT
                        c.drawText(headerText.take(40), margin, h * 0.04f, pHead)
                        c.drawLine(margin, h * 0.048f, w - margin, h * 0.048f, Paint().apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 1f })
                    }
                }

                doc.finishPage(pdfPg)
                bmp.recycle()
            }

            renderer.close()
            pfd.close()

            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
            out
        }) { res ->
            res.onSuccess {
                activity.toast("Nomor halaman berhasil ditambahkan!")
                Files.open(activity, Files.uriFor(activity, out), PDF)
            }.onFailure { activity.fail(it) }
        }
    }
}
