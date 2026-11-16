package cloud.depup.office

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Pembersih Bayangan & Penjernih Foto Dokumen (Doc Shadow Remover & Clarifier).
 * Menghapus bayangan tangan/HP, meratakan penerangan kertas, dan mempertajam teks foto dokumen.
 */
object DocShadowRemoverManager {

    fun showDialog(activity: AppCompatActivity, onPickImage: (cb: (Uri) -> Unit) -> Unit) {
        onPickImage { uri ->
            processImage(activity, uri)
        }
    }

    private fun processImage(activity: AppCompatActivity, uri: Uri) {
        activity.work("Penjernihan & Pembersihan Bayangan Dokumen…", {
            val originalBmp = Pdf.decode(activity, uri, 1800) ?: throw IllegalStateException("Gagal memuat foto")
            val cleanedBmp = originalBmp.copy(Bitmap.Config.ARGB_8888, true)
            val w = cleanedBmp.width
            val h = cleanedBmp.height
            val pixels = IntArray(w * h)
            cleanedBmp.getPixels(pixels, 0, w, 0, 0, w, h)

            // Terapkan Magic Color + Shadow Removal
            DocFilter.magic(pixels, w, h)
            cleanedBmp.setPixels(pixels, 0, w, 0, 0, w, h)

            Pair(originalBmp, cleanedBmp)
        }) { res ->
            res.onSuccess { (originalBmp, cleanedBmp) ->
                showCompareSheet(activity, uri, originalBmp, cleanedBmp)
            }.onFailure { activity.fail(it) }
        }
    }

    private fun showCompareSheet(
        activity: AppCompatActivity,
        srcUri: Uri,
        originalBmp: Bitmap,
        cleanedBmp: Bitmap
    ) {
        var showingCleaned = true

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_wand, C.ACCENT, C.BLUE_BG,
                "Pembersih Bayangan & Penjernih Foto", "Bayangan dihapus • Teks dipertajam", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val ivPreview = ZoomImageView(activity).apply {
                setImageBitmap(cleanedBmp)
                background = activity.shape(C.SURFACE2, 16)
                setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
            }
            pad.add(ivPreview, h = activity.dp(320))

            val statusTv = activity.tv("Tampilan: Kertas Jernih (Tanpa Bayangan)", 12f, C.MUTED).apply {
                gravity = Gravity.CENTER
            }
            pad.add(statusTv, top = 6)

            val toggleBtn = activity.btn("Tahan untuk Lihat Foto Asli", false, C.TEXT) {
                // Toggle mode
                showingCleaned = !showingCleaned
                if (showingCleaned) {
                    ivPreview.setImageBitmap(cleanedBmp)
                    statusTv.text = "Tampilan: Kertas Jernih (Tanpa Bayangan)"
                } else {
                    ivPreview.setImageBitmap(originalBmp)
                    statusTv.text = "Tampilan: Foto Asli (Dengan Bayangan)"
                }
            }
            pad.add(toggleBtn, top = 8)

            val actionRow = activity.hbox().apply {
                add(activity.btn("Simpan PNG Jernih", false, C.ACCENT) {
                    dismiss()
                    saveCleaned(activity, srcUri, cleanedBmp)
                }, w = 0, weight = 1f)

                add(activity.btn("Simpan ke PDF", true, C.ACCENT, R.drawable.ic_pdf) {
                    dismiss()
                    saveCleanedPdf(activity, srcUri, cleanedBmp)
                }, w = 0, weight = 1f, side = 8)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    @SuppressLint("ThreadConstraint")
    private fun saveCleaned(activity: AppCompatActivity, srcUri: Uri, bmp: Bitmap) {
        val name = "Jernih_" + Files.baseName(Files.nameOf(activity, srcUri))
        val out = Files.namedOut(activity, name, "png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        activity.toast("Foto jernih disimpan ke Documents/DepUp Office/Gambar")
        activity.showImagePreviewDialog(bmp, "Foto Dokumen Jernih")
    }

    private fun saveCleanedPdf(activity: AppCompatActivity, srcUri: Uri, bmp: Bitmap) {
        val name = "Jernih_" + Files.baseName(Files.nameOf(activity, srcUri))
        val out = Files.newOut(activity, name, "pdf")
        activity.work("Membuat PDF Jernih…", {
            val uriTemp = Files.uriFor(activity, Files.namedOut(activity, name, "png").apply {
                FileOutputStream(this).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            })
            Pdf.imagesToPdf(activity, listOf(uriTemp), out)
        }) { res ->
            res.onSuccess {
                activity.toast("PDF Jernih disimpan")
                Files.open(activity, Files.uriFor(activity, out), PDF)
            }.onFailure { activity.fail(it) }
        }
    }
}
