package cloud.depup.office

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.QRCodeWriter

/** Hasil pembacaan satu kode. */
class QrResult(val text: String, val format: String)

object QrManager {

    /** Pindai Kode QR / Barcode dari Bitmap. Mengembalikan teks pertama yang ditemukan. */
    fun scanBarcode(bmp: Bitmap): String = scanAll(bmp).firstOrNull()?.text.orEmpty()

    /**
     * Pindai semua kode pada gambar. ML Kit dipakai lebih dulu (akurat untuk foto miring/HP),
     * lalu ZXing sebagai cadangan bila ML Kit tidak menemukan apa pun.
     */
    fun scanAll(bmp: Bitmap): List<QrResult> {
        val found = LinkedHashMap<String, QrResult>()

        try {
            val scanner = BarcodeScanning.getClient()
            try {
                val image = InputImage.fromBitmap(bmp, 0)
                Tasks.await(scanner.process(image)).forEach { b ->
                    val text = (b.rawValue ?: b.displayValue).orEmpty().trim()
                    if (text.isNotEmpty()) found[text] = QrResult(text, formatName(b.format))
                }
            } finally {
                try { scanner.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            // Abaikan, lanjut ke decoder cadangan.
        }

        if (found.isEmpty()) {
            try {
                val w = bmp.width
                val h = bmp.height
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                val source = RGBLuminanceSource(w, h, pixels)
                val bitmap = BinaryBitmap(HybridBinarizer(source))
                val result = MultiFormatReader().apply { setHints(buildDecodeHints()) }.decodeWithState(bitmap)
                val text = result.text?.trim().orEmpty()
                if (text.isNotEmpty()) found[text] = QrResult(text, result.barcodeFormat.name)
            } catch (_: Exception) {
                // Tidak ada kode yang terbaca.
            }
        }

        return found.values.toList()
    }

    private fun buildDecodeHints() = mapOf(
        com.google.zxing.DecodeHintType.TRY_HARDER to true,
        com.google.zxing.DecodeHintType.CHARACTER_SET to "UTF-8"
    )

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR Code"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_CODABAR -> "Codabar"
        else -> "Barcode"
    }

    /**
     * Pembuat Kode QR asli (standar ISO/IEC 18004) memakai encoder ZXing.
     * [margin] adalah quiet zone dalam modul; minimal 4 modul agar mudah dipindai.
     */
    fun generateQr(
        text: String,
        width: Int = 512,
        height: Int = 512,
        margin: Int = 4,
        highCorrection: Boolean = true,
    ): Bitmap {
        require(text.isNotBlank()) { "Teks QR tidak boleh kosong" }
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to if (highCorrection) ErrorCorrectionLevel.H else ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin.coerceAtLeast(0)
        )
        val side = maxOf(width, height)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(matrix.width * matrix.height)
        var i = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[i++] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        bmp.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        return if (bmp.width == width && bmp.height == height) bmp
        else Bitmap.createScaledBitmap(bmp, width, height, false).also { if (it !== bmp) bmp.recycle() }
    }

    /** Kapasitas aman QR versi 40 dengan koreksi error H sekitar 1.2 KB (UTF-8). */
    fun isTextTooLong(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size > 1200
}
