package cloud.depup.office

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Watermark Foto Massal / Stempel Toko (Batch Image Watermark).
 * Menempelkan teks watermark atau stempel transparan pada banyak foto produk sekaligus.
 */
object BatchWatermarkManager {

    fun showDialog(activity: AppCompatActivity, onPickImages: (cb: (List<Uri>) -> Unit) -> Unit) {
        onPickImages { uris ->
            if (uris.isEmpty()) return@onPickImages
            showWatermarkSheet(activity, uris)
        }
    }

    private fun showWatermarkSheet(activity: AppCompatActivity, uris: List<Uri>) {
        val fText = activity.field("Teks Watermark (mis: Toko Olshop)").apply { setText("TOKO OLSHOP") }
        var selectedStampBmp: Bitmap? = null
        var position = 0 // 0 = Bottom Right, 1 = Center, 2 = Top Right, 3 = Bottom Left
        var opacity = 180 // 0..255

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_wand, C.ACCENT, C.BLUE_BG,
                "Watermark Foto Massal", "${uris.size} foto dipilih • Tempel teks/stempel", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Teks Watermark", 12f, C.MUTED))
                add(fText)

                // Stempel Toko
                add(activity.tv("Atau Gunakan Stempel Toko", 12f, C.MUTED), top = 6)
                val stampRow = activity.hbox().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val stampIv = ImageView(activity).apply {
                        setImageResource(R.drawable.ic_sign)
                        setColorFilter(C.MUTED)
                        background = activity.shape(C.SURFACE2, 10)
                        setPadding(activity.dp(6), activity.dp(6), activity.dp(6), activity.dp(6))
                    }
                    val stampTv = activity.tv("Stempel / Logo Toko: Belum Dipilih", 12f, C.MUTED)
                    add(stampIv, w = activity.dp(36), h = activity.dp(36))
                    add(stampTv, w = 0, weight = 1f, side = 8)
                    add(activity.btn("Pilih Stempel", false, C.ACCENT) {
                        SignatureManager.showSheet(activity, "Pilih Stempel / Logo Toko") { bmp, _ ->
                            selectedStampBmp = bmp
                            stampIv.setImageBitmap(bmp)
                            stampIv.clearColorFilter()
                            stampTv.text = "Stempel Toko Siap Ditempel"
                        }
                    }, w = WRAP)
                }
                add(stampRow, top = 4)

                // Posisi
                add(activity.tv("Posisi Watermark", 12f, C.MUTED), top = 6)
                val posHolder = activity.vbox()
                posHolder.add(activity.segmented(listOf("Kanan Bawah", "Tengah", "Kanan Atas", "Kiri Bawah"), position) {
                    position = it
                })
                add(posHolder, top = 4)
            }

            pad.add(formBox)

            val actionRow = activity.hbox().apply {
                add(activity.btn("Terapkan ke ${uris.size} Foto", true, C.PRIMARY, R.drawable.ic_wand) {
                    val wText = fText.text.toString().trim()
                    dismiss()
                    processBatch(activity, uris, wText, selectedStampBmp, position, opacity)
                }, w = 0, weight = 1f)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    @SuppressLint("ThreadConstraint")
    private fun processBatch(
        activity: AppCompatActivity,
        uris: List<Uri>,
        text: String,
        stampBmp: Bitmap?,
        position: Int,
        opacity: Int
    ) {
        activity.work("Memproses ${uris.size} foto…", {
            val processedFiles = ArrayList<File>()

            uris.forEachIndexed { idx, uri ->
                val rawBmp = Pdf.decode(activity, uri, 1800) ?: return@forEachIndexed
                val mutableBmp = rawBmp.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutableBmp)
                val w = mutableBmp.width.toFloat()
                val h = mutableBmp.height.toFloat()

                if (stampBmp != null) {
                    val stampW = w * 0.25f
                    val stampH = stampW * (stampBmp.height.toFloat() / stampBmp.width.toFloat())
                    val margin = w * 0.04f

                    val rect = when (position) {
                        0 -> RectF(w - stampW - margin, h - stampH - margin, w - margin, h - margin)
                        1 -> RectF((w - stampW) / 2f, (h - stampH) / 2f, (w + stampW) / 2f, (h + stampH) / 2f)
                        2 -> RectF(w - stampW - margin, margin, w - margin, margin + stampH)
                        else -> RectF(margin, h - stampH - margin, margin + stampW, h - margin)
                    }

                    val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = opacity }
                    canvas.drawBitmap(stampBmp, null, rect, p)
                } else if (text.isNotBlank()) {
                    val pText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        alpha = opacity
                        textSize = Math.min(w, h) * 0.045f
                        isFakeBoldText = true
                        setShadowLayer(4f, 2f, 2f, 0x99000000.toInt())
                    }

                    val margin = w * 0.05f
                    when (position) {
                        0 -> { pText.textAlign = Paint.Align.RIGHT; canvas.drawText(text, w - margin, h - margin, pText) }
                        1 -> { pText.textAlign = Paint.Align.CENTER; canvas.drawText(text, w / 2f, h / 2f, pText) }
                        2 -> { pText.textAlign = Paint.Align.RIGHT; canvas.drawText(text, w - margin, margin + pText.textSize, pText) }
                        else -> { pText.textAlign = Paint.Align.LEFT; canvas.drawText(text, margin, h - margin, pText) }
                    }
                }

                val out = Files.namedOut(activity, "Watermark_${idx + 1}_${System.currentTimeMillis()}", "png")
                FileOutputStream(out).use { mutableBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                processedFiles.add(out)
                mutableBmp.recycle()
                if (rawBmp !== mutableBmp) rawBmp.recycle()
            }

            processedFiles
        }) { res ->
            res.onSuccess { files ->
                activity.toast("${files.size} foto berhasil diberi watermark & disimpan ke Documents/DepUp Office/Gambar!")
                if (files.isNotEmpty()) {
                    val firstBmp = Pdf.decode(activity, Files.uriFor(activity, files.first()), 1000)
                    if (firstBmp != null) activity.showImagePreviewDialog(firstBmp, "Hasil Watermark Foto")
                }
            }.onFailure { activity.fail(it) }
        }
    }
}
