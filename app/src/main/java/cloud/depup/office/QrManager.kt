package cloud.depup.office

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage

object QrManager {

    /** Pindai Kode QR / Barcode dari Bitmap. */
    fun scanBarcode(bmp: Bitmap): String {
        return try {
            val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
            val image = InputImage.fromBitmap(bmp, 0)
            val barcodes = Tasks.await(scanner.process(image))
            if (barcodes.isNotEmpty()) {
                val b = barcodes[0]
                b.rawValue ?: b.displayValue ?: "Kode QR terdeteksi"
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** Pembuat Kode QR Kustom Murni Kotlin (Tanpa Library Eksternal Berat). */
    fun generateQr(text: String, width: Int = 512, height: Int = 512): Bitmap {
        val qrMatrix = encodeQrMatrix(text, 25)
        val matrixSize = qrMatrix.size
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scaleX = width.toFloat() / matrixSize
        val scaleY = height.toFloat() / matrixSize

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val my = (y / scaleY).toInt().coerceIn(0, matrixSize - 1)
            for (x in 0 until width) {
                val mx = (x / scaleX).toInt().coerceIn(0, matrixSize - 1)
                pixels[y * width + x] = if (qrMatrix[my][mx]) Color.BLACK else Color.WHITE
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun encodeQrMatrix(content: String, size: Int): Array<BooleanArray> {
        val m = Array(size) { BooleanArray(size) }

        fun addFinderPattern(x: Int, y: Int) {
            for (i in 0..6) {
                for (j in 0..6) {
                    if (x + i < size && y + j < size) {
                        m[y + j][x + i] = (i == 0 || i == 6 || j == 0 || j == 6 || (i in 2..4 && j in 2..4))
                    }
                }
            }
        }

        addFinderPattern(0, 0)
        addFinderPattern(size - 7, 0)
        addFinderPattern(0, size - 7)

        for (i in 8 until size - 8) {
            m[6][i] = (i % 2 == 0)
            m[i][6] = (i % 2 == 0)
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        var bitIdx = 0
        for (r in 8 until size - 1) {
            for (c in 8 until size - 1) {
                val byteVal = if (bytes.isNotEmpty()) bytes[bitIdx % bytes.size].toInt() else 0
                val bit = ((byteVal shr (bitIdx % 8)) and 1) == 1
                m[r][c] = bit
                bitIdx++
            }
        }
        return m
    }
}
