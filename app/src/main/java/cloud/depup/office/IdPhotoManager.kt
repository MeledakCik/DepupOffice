package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pemindai Pasfoto & Ubah Warna Latar (ID Photo Maker & Background Changer).
 * Memotong pasfoto ukuran 2x3, 3x4, 4x6, mengubah latar belakang (Merah, Biru, Putih), dan menata ke lembar A4 siap cetak.
 */
object IdPhotoManager {

    val COLOR_RED = 0xFFD32F2F.toInt()     // Merah pasfoto resmi
    val COLOR_BLUE = 0xFF1976D2.toInt()    // Biru pasfoto resmi
    val COLOR_WHITE = 0xFFFFFFFF.toInt()   // Putih pasfoto/visa/paspor
    val COLOR_GREY = 0xFFE0E0E0.toInt()    // Abu netral

    enum class AspectRatio(val label: String, val w: Int, val h: Int) {
        SIZE_2X3("2 × 3 cm", 2, 3),
        SIZE_3X4("3 × 4 cm", 3, 4),
        SIZE_4X6("4 × 6 cm", 4, 6),
        SQUARE("1 × 1 (Persegi)", 1, 1)
    }

    /**
     * Potong gambar sesuai rasio aspek yang dipilih (berpusat di tengah).
     */
    fun cropRatio(src: Bitmap, aspect: AspectRatio): Bitmap {
        val targetRatio = aspect.w.toFloat() / aspect.h.toFloat()
        val srcRatio = src.width.toFloat() / src.height.toFloat()

        var cropW = src.width
        var cropH = src.height

        if (srcRatio > targetRatio) {
            cropW = (src.height * targetRatio).toInt()
        } else {
            cropH = (src.width / targetRatio).toInt()
        }

        val startX = ((src.width - cropW) / 2).coerceAtLeast(0)
        val startY = ((src.height - cropH) / 3).coerceAtLeast(0) // Sedikit lebih ke atas untuk posisi wajah

        return Bitmap.createBitmap(src, startX, startY, cropW.coerceAtMost(src.width - startX), cropH.coerceAtMost(src.height - startY))
    }

    /**
     * Ganti warna latar belakang foto (mendeteksi latar sekitar kepala/bahu dan menggantinya dengan [newColor]).
     */
    fun changeBackground(src: Bitmap, newColor: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Sampel warna latar dari pojok kiri & kanan atas
        val sample1 = pixels[0]
        val sample2 = pixels[w - 1]
        val sr1 = Color.red(sample1); val sg1 = Color.green(sample1); val sb1 = Color.blue(sample1)
        val sr2 = Color.red(sample2); val sg2 = Color.green(sample2); val sb2 = Color.blue(sample2)

        val outPixels = pixels.clone()

        fun isBgPixel(r: Int, g: Int, b: Int): Boolean {
            val d1 = abs(r - sr1) + abs(g - sg1) + abs(b - sb1)
            val d2 = abs(r - sr2) + abs(g - sg2) + abs(b - sb2)
            return d1 < 85 || d2 < 85
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val p = pixels[idx]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (isBgPixel(r, g, b)) {
                    outPixels[idx] = newColor
                }
            }
        }

        return Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Buat lembar A4 berisi deretan pasfoto siap cetak, UKURAN FISIK TEPAT: lembar = A4 penuh (21 × 29,7 cm) pada
     * [dpi] tertentu, pasfoto = ukuran cm-nya pada DPI yang sama. Dicetak "pas halaman" di A4, pasfoto 3×4 benar-benar
     * 3×4 cm. (Dulu lembar 150 DPI tetapi pasfoto digambar seolah ±250 DPI → hasil cetak ±1,7× terlalu besar.)
     */
    fun createA4PrintSheet(pasfoto: Bitmap, aspect: AspectRatio): Bitmap {
        val dpi = if (Perf.low) 200f else 300f
        fun cm(v: Float) = (v / 2.54f * dpi).roundToInt()
        val a4W = cm(21f)
        val a4H = cm(29.7f)
        val sheet = Bitmap.createBitmap(a4W, a4H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.LTGRAY
            strokeWidth = 1f
        }

        // ukuran cm sebenarnya; "1 × 1 (Persegi)" dicetak 3 × 3 cm
        val (wCm, hCm) = when (aspect) {
            AspectRatio.SIZE_2X3 -> 2f to 3f
            AspectRatio.SIZE_3X4 -> 3f to 4f
            AspectRatio.SIZE_4X6 -> 4f to 6f
            AspectRatio.SQUARE -> 3f to 3f
        }
        val pW = cm(wCm)
        val pH = cm(hCm)
        val gap = cm(0.3f)          // jarak potong antar-foto
        val margin = cm(1f)         // tepi aman printer
        val cols = ((a4W - 2 * margin + gap) / (pW + gap)).coerceAtLeast(1)
        val rows = ((a4H - 2 * margin + gap) / (pH + gap)).coerceAtLeast(1)

        val marginX = (a4W - (cols * pW + (cols - 1) * gap)) / 2
        val marginY = (a4H - (rows * pH + (rows - 1) * gap)) / 2

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = marginX + c * (pW + gap)
                val top = marginY + r * (pH + gap)
                val dstRect = Rect(left, top, left + pW, top + pH)
                canvas.drawBitmap(pasfoto, null, dstRect, paint)
                canvas.drawRect(dstRect, borderPaint)
            }
        }

        return sheet
    }


    /**
     * Tampilkan dialog pembuat pasfoto.
     */
    fun showDialog(activity: AppCompatActivity, onPickImage: (cb: (Uri) -> Unit) -> Unit) {
        onPickImage { uri ->
            try {
                val raw = Pdf.decode(activity, uri, 1600)
                if (raw == null) {
                    activity.toast("Gagal memuat foto")
                    return@onPickImage
                }
                showEditorSheet(activity, raw)
            } catch (_: Throwable) {
                activity.toast("Gagal membaca file foto")
            }
        }
    }

    private fun showEditorSheet(activity: AppCompatActivity, initialBmp: Bitmap) {
        var currentAspect = AspectRatio.SIZE_3X4
        var currentColor = COLOR_RED

        var processedBmp = changeBackground(cropRatio(initialBmp, currentAspect), currentColor)

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_user, C.ACCENT, C.BLUE_BG,
                "Pembuat Pasfoto Resmi", "Pilih ukuran 2x3, 3x4, 4x6 & warna latar", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val ivPreview = ZoomImageView(activity).apply {
                setImageBitmap(processedBmp)
                background = activity.shape(C.SURFACE2, 16)
                setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
            }
            pad.add(ivPreview, h = activity.dp(300))

            fun updatePreview() {
                processedBmp = changeBackground(cropRatio(initialBmp, currentAspect), currentColor)
                ivPreview.setImageBitmap(processedBmp)
            }

            // Pilihan Ukuran
            pad.add(activity.tv("Ukuran Pasfoto", 13f, C.TEXT, true), top = 12)
            val sizeRow = activity.hbox().apply {
                AspectRatio.values().forEach { aspect ->
                    val isSel = aspect == currentAspect
                    val btn = activity.tv(aspect.label, 12f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                        background = activity.shape(if (isSel) C.ACCENT else C.SURFACE2, 100)
                        setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
                        setOnClickListener {
                            currentAspect = aspect
                            updatePreview()
                        }
                    }
                    add(btn, w = WRAP, side = 3)
                }
            }
            pad.add(android.widget.HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(sizeRow)
            }, top = 6)

            // Pilihan Warna Latar
            pad.add(activity.tv("Warna Latar Belakang", 13f, C.TEXT, true), top = 12)
            val colorRow = activity.hbox().apply {
                val colors = listOf(
                    Pair("Merah", COLOR_RED),
                    Pair("Biru", COLOR_BLUE),
                    Pair("Putih", COLOR_WHITE),
                    Pair("Abu", COLOR_GREY)
                )
                colors.forEach { (label, col) ->
                    val isSel = col == currentColor
                    val btn = activity.tv(label, 12f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                        background = activity.shape(if (isSel) col else C.SURFACE2, 100)
                        setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
                        setOnClickListener {
                            currentColor = col
                            updatePreview()
                        }
                    }
                    add(btn, w = WRAP, side = 3)
                }
            }
            pad.add(colorRow, top = 6)

            // Tombol Simpan
            val actionRow = activity.hbox().apply {
                add(activity.btn("Simpan 1 Pasfoto", false, C.ACCENT) {
                    dismiss()
                    saveSingle(activity, processedBmp, currentAspect)
                }, w = 0, weight = 1f)

                add(activity.btn("Cetak Lembar A4", true, C.ACCENT, R.drawable.ic_print) {
                    dismiss()
                    saveA4Sheet(activity, processedBmp, currentAspect)
                }, w = 0, weight = 1f, side = 8)
            }
            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    @SuppressLint("ThreadConstraint")
    private fun saveSingle(activity: AppCompatActivity, bmp: Bitmap, aspect: AspectRatio) {
        val name = "Pasfoto_${aspect.label.replace(" ", "")}_${System.currentTimeMillis()}"
        val out = Files.namedOut(activity, name, "png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        activity.toast("Pasfoto disimpan ke Documents/DepUp Office/Gambar")
        activity.showImagePreviewDialog(bmp, "Pasfoto ${aspect.label}")
    }

    @SuppressLint("ThreadConstraint")
    private fun saveA4Sheet(activity: AppCompatActivity, bmp: Bitmap, aspect: AspectRatio) {
        val a4Bmp = createA4PrintSheet(bmp, aspect)
        val name = "Pasfoto_A4_${aspect.label.replace(" ", "")}_${System.currentTimeMillis()}"
        val out = Files.namedOut(activity, name, "png")
        FileOutputStream(out).use { a4Bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        activity.toast("Lembar A4 disimpan di Documents/DepUp Office/Gambar. Cetak di kertas A4 dengan \"pas halaman\" (tanpa skala tambahan) agar ukuran pasfoto tepat.")
        activity.showImagePreviewDialog(a4Bmp, "Lembar Cetak A4 ${aspect.label}")
    }
}
