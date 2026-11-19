package cloud.depup.office

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.view.Gravity
import android.widget.ImageView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.FileOutputStream

/**
 * Pembuat Barcode Produk & Label Harga (Price Tag & Barcode Generator).
 * Generator Kode Barcode (Code 128 / EAN-13 / QR) dan penata label harga toko di kertas A4.
 */
object BarcodePriceTagManager {

    /**
     * Generate Barcode 1D (Code 128 / EAN-13) atau QR Code.
     */
    fun generateBarcode(text: String, format: BarcodeFormat = BarcodeFormat.CODE_128, w: Int = 400, h: Int = 160): Bitmap {
        require(text.isNotBlank()) { "Kode barcode tidak boleh kosong" }
        val matrix = MultiFormatWriter().encode(text, format, w, h, null)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(matrix.width * matrix.height)
        var i = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[i++] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        bmp.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        return bmp
    }

    /**
     * Gambar 1 Label Harga Toko (Product Name, Price, Barcode Image, SKU).
     */
    fun drawPriceTag(productName: String, price: String, codeText: String, barcodeBmp: Bitmap): Bitmap {
        val tagW = 360
        val tagH = 220
        val bmp = Bitmap.createBitmap(tagW, tagH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val pBorder = Paint().apply { style = Paint.Style.STROKE; color = Color.LTGRAY; strokeWidth = 3f }
        c.drawRect(0f, 0f, tagW.toFloat(), tagH.toFloat(), pBorder)

        val pTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); textSize = 20f; isFakeBoldText = true }
        val pPrice = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 26f; isFakeBoldText = true }
        val pCode = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF65625D.toInt(); textSize = 14f }

        c.drawText(productName.take(22), 16f, 32f, pTitle)
        c.drawText(price, 16f, 70f, pPrice)

        val dstBarcode = Rect(16, 85, tagW - 16, tagH - 40)
        c.drawBitmap(barcodeBmp, null, dstBarcode, Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawText(codeText, 16f, tagH - 12f, pCode)

        return bmp
    }

    /**
     * Buat lembar A4 berisi grid label harga siap cetak (3x7 grid = 21 label).
     */
    fun createA4LabelSheet(priceTag: Bitmap): Bitmap {
        val a4W = 1240
        val a4H = 1754
        val sheet = Bitmap.createBitmap(a4W, a4H, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.WHITE)

        val cols = 3
        val rows = 7
        val tagW = 360
        val tagH = 220

        val marginX = (a4W - cols * tagW) / 4
        val marginY = (a4H - rows * tagH) / 8

        val pPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        for (r in 0 until rows) {
            for (col in 0 until cols) {
                val left = marginX + col * (tagW + marginX)
                val top = marginY + r * (tagH + marginY)
                val dst = Rect(left, top, left + tagW, top + tagH)
                c.drawBitmap(priceTag, null, dst, pPaint)
            }
        }

        return sheet
    }

    fun showDialog(activity: AppCompatActivity) {
        // 12 angka (899 = awalan GS1 Indonesia + 9 angka): sah untuk EAN-13 (digit cek ditambah otomatis) & Code 128.
        // Dulu 11 angka — memilih EAN-13 tanpa mengubah kode selalu gagal.
        val autoCode = "899" + (100000000..999999999).random()

        val fProduct = activity.field("Nama Produk").apply { setText("Kopi Susu Gula Aren") }
        val fCode = activity.field("Kode Barcode / SKU").apply { setText(autoCode) }
        val fPrice = activity.field("Harga Produk").apply { setText("Rp 18.000") }

        var currentFormat = BarcodeFormat.CODE_128
        var previewTagBmp: Bitmap? = null

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_qr, C.ACCENT, C.BLUE_BG,
                "Pembuat Barcode & Label Harga", "Buat label harga toko & cetak lebel A4", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val ivPreview = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = activity.shape(C.SURFACE2, 16)
                setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
            }
            pad.add(ivPreview, h = activity.dp(160))

            fun updatePreview() {
                try {
                    val code = fCode.text.toString().trim().ifBlank { autoCode }
                    val name = fProduct.text.toString().trim().ifBlank { "Produk Toko" }
                    val price = fPrice.text.toString().trim().ifBlank { "Rp -" }

                    val barcodeImg = generateBarcode(code, currentFormat)
                    val tagImg = drawPriceTag(name, price, code, barcodeImg)
                    previewTagBmp = tagImg
                    ivPreview.setImageBitmap(tagImg)
                } catch (_: Throwable) {
                    // jelaskan penyebabnya (dulu hanya "Gagal membuat barcode")
                    val code = fCode.text.toString().trim().ifBlank { autoCode }
                    activity.toast(when {
                        currentFormat == BarcodeFormat.EAN_13 && (code.length !in 12..13 || !code.all { it.isDigit() }) ->
                            "EAN-13 butuh 12 atau 13 angka (tanpa huruf/spasi)"
                        currentFormat == BarcodeFormat.EAN_13 -> "Digit terakhir EAN-13 tidak cocok. Isi 12 angka saja, digit cek dihitung otomatis."
                        currentFormat == BarcodeFormat.CODE_128 && code.any { it.code > 126 } -> "Code 128 hanya untuk huruf/angka biasa (tanpa emoji/aksen)"
                        else -> "Gagal membuat barcode"
                    })
                }
            }

            updatePreview()

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Nama Produk", 12f, C.MUTED))
                add(fProduct)
                add(activity.tv("Kode Barcode", 12f, C.MUTED), top = 4)
                add(fCode)
                add(activity.tv("Harga Produk", 12f, C.MUTED), top = 4)
                add(fPrice)

                add(activity.tv("Format Barcode", 12f, C.MUTED), top = 6)
                val formatRow = activity.hbox().apply {
                    val formats = listOf(
                        Pair("Code 128", BarcodeFormat.CODE_128),
                        Pair("EAN-13", BarcodeFormat.EAN_13),
                        Pair("QR Code", BarcodeFormat.QR_CODE)
                    )
                    formats.forEach { (label, fmt) ->
                        val isSel = fmt == currentFormat
                        val btn = activity.tv(label, 12f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                            background = activity.shape(if (isSel) C.ACCENT else C.SURFACE2, 100)
                            setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
                            setOnClickListener {
                                currentFormat = fmt
                                updatePreview()
                            }
                        }
                        add(btn, w = WRAP, side = 3)
                    }
                }
                add(formatRow, top = 4)
            }

            pad.add(ScrollView(activity).apply { addView(formBox) }, h = activity.dp(220))

            val actionRow = activity.hbox().apply {
                @SuppressLint("ThreadConstraint")
                add(activity.btn("Simpan 1 Label", false, C.ACCENT) {
                    val tag = previewTagBmp ?: return@btn
                    dismiss()
                    val out = Files.namedOut(activity, "Label_${fProduct.text}", "png")
                    FileOutputStream(out).use { tag.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    activity.toast("Label harga disimpan")
                    activity.showImagePreviewDialog(tag, "Label Harga")
                }, w = 0, weight = 1f)

                @SuppressLint("ThreadConstraint")
                add(activity.btn("Cetak Lembar A4", true, C.PRIMARY, R.drawable.ic_print) {
                    val tag = previewTagBmp ?: return@btn
                    dismiss()
                    val sheetA4 = createA4LabelSheet(tag)
                    val out = Files.namedOut(activity, "Label_A4_${fProduct.text}", "png")
                    FileOutputStream(out).use { sheetA4.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    activity.toast("Lembar label A4 disimpan")
                    activity.showImagePreviewDialog(sheetA4, "Lembar Label A4")
                }, w = 0, weight = 1f, side = 8)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }
}
