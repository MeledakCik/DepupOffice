package cloud.depup.office

import android.graphics.Bitmap
import android.graphics.Matrix
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

enum class FilterType {
    ORIGINAL,
    MAGIC_COLOR,
    BLACK_AND_WHITE
}

data class ScannerPoint(val x: Float, val y: Float)

object DocumentScanner {

    init {
        try {
            if (!OpenCVLoader.initDebug()) {
                // OpenCV Debug load fallback
            }
        } catch (_: Exception) {
        }
    }

    /** Memutar bitmap berdasarkan derajat (-90, 90, 180, dll) */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Mendeteksi 4 sudut kertas HVS / dokumen dari Bitmap.
     * Mengembalikan koordinat 4 titik yang sudah ternormalisasi (0.0 .. 1.0) dengan urutan:
     * [0] Top-Left, [1] Top-Right, [2] Bottom-Right, [3] Bottom-Left.
     * Bila kontur tidak ditemukan, fallback ke kotak inset default.
     */
    fun detectDocumentContour(bitmap: Bitmap): List<ScannerPoint> {
        val src = Mat()
        try {
            Utils.bitmapToMat(bitmap, src)

            val width = src.width()
            val height = src.height()
            if (width <= 0 || height <= 0) return defaultCorners()

            // Scale down untuk akselerasi deteksi tepi
            val scale = 600f / max(width, height)
            val resizedW = (width * scale).toInt().coerceAtLeast(1)
            val resizedH = (height * scale).toInt().coerceAtLeast(1)

            val resized = Mat()
            Imgproc.resize(src, resized, Size(resizedW.toDouble(), resizedH.toDouble()))

            val gray = Mat()
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)

            val blur = Mat()
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

            val canny = Mat()
            Imgproc.Canny(blur, canny, 30.0, 120.0)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            val dilated = Mat()
            Imgproc.dilate(canny, dilated, kernel)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            // Cari kontur terbesar berbentuk segi empat konveks
            var bestContour: MatOfPoint2f? = null
            var maxArea = 0.0
            val minArea = (resizedW * resizedH) * 0.08 // Minimal 8% luas gambar

            for (c in contours) {
                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)

                val points = approx.toArray()
                val area = Imgproc.contourArea(approx)

                if (points.size == 4 && area > minArea && area > maxArea) {
                    if (Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                        maxArea = area
                        bestContour = approx
                    }
                }
            }

            // Fallback: Gunakan minAreaRect jika approxPolyDP tidak menghasilkan 4 titik eksak
            if (bestContour == null) {
                for (c in contours) {
                    val c2f = MatOfPoint2f(*c.toArray())
                    val area = Imgproc.contourArea(c2f)
                    if (area > minArea && area > maxArea) {
                        maxArea = area
                        val rect = Imgproc.minAreaRect(c2f)
                        val rectMat = MatOfPoint2f()
                        Imgproc.boxPoints(rect, rectMat)
                        bestContour = rectMat
                    }
                }
            }

            if (bestContour != null) {
                val pts = bestContour.toArray()
                val normalizedPts = pts.map { p ->
                    ScannerPoint(
                        (p.x / resizedW).toFloat().coerceIn(0f, 1f),
                        (p.y / resizedH).toFloat().coerceIn(0f, 1f)
                    )
                }
                return sortCorners(normalizedPts)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            src.release()
        }

        return defaultCorners()
    }

    fun defaultCorners(): List<ScannerPoint> {
        return listOf(
            ScannerPoint(0.05f, 0.05f), // Top-Left
            ScannerPoint(0.95f, 0.05f), // Top-Right
            ScannerPoint(0.95f, 0.95f), // Bottom-Right
            ScannerPoint(0.05f, 0.95f)  // Bottom-Left
        )
    }

    /** Urutkan 4 titik ke standar: [TL, TR, BR, BL] */
    fun sortCorners(points: List<ScannerPoint>): List<ScannerPoint> {
        if (points.size != 4) return defaultCorners()

        val sumList = points.map { it.x + it.y }
        val diffList = points.map { it.x - it.y }

        val tl = points[sumList.indexOfMin()]
        val br = points[sumList.indexOfMax()]
        val tr = points[diffList.indexOfMax()]
        val bl = points[diffList.indexOfMin()]

        return listOf(tl, tr, br, bl)
    }

    private fun List<Float>.indexOfMin(): Int {
        var minIdx = 0
        for (i in indices) {
            if (this[i] < this[minIdx]) minIdx = i
        }
        return minIdx
    }

    private fun List<Float>.indexOfMax(): Int {
        var maxIdx = 0
        for (i in indices) {
            if (this[i] > this[maxIdx]) maxIdx = i
        }
        return maxIdx
    }

    /**
     * Melakukan Perspective Correction (Warp Perspective) berdasarkan 4 titik sudut.
     * Mengubah dokumen miring menjadi tegak lurus sempurna.
     */
    fun warpPerspective(bitmap: Bitmap, corners: List<ScannerPoint>): Bitmap {
        if (corners.size != 4) return bitmap

        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val imgW = src.width().toDouble()
        val imgH = src.height().toDouble()

        val sorted = sortCorners(corners)
        val tl = sorted[0]
        val tr = sorted[1]
        val br = sorted[2]
        val bl = sorted[3]

        val pTL = Point(tl.x * imgW, tl.y * imgH)
        val pTR = Point(tr.x * imgW, tr.y * imgH)
        val pBR = Point(br.x * imgW, br.y * imgH)
        val pBL = Point(bl.x * imgW, bl.y * imgH)

        val widthA = hypot(pBR.x - pBL.x, pBR.y - pBL.y)
        val widthB = hypot(pTR.x - pTL.x, pTR.y - pTL.y)
        val targetWidth = max(widthA, widthB).toInt().coerceAtLeast(200)

        val heightA = hypot(pTR.x - pBR.x, pTR.y - pBR.y)
        val heightB = hypot(pTL.x - pBL.x, pTL.y - pBL.y)
        val targetHeight = max(heightA, heightB).toInt().coerceAtLeast(200)

        val srcMat = MatOfPoint2f(pTL, pTR, pBR, pBL)
        val dstMat = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(targetWidth.toDouble(), 0.0),
            Point(targetWidth.toDouble(), targetHeight.toDouble()),
            Point(0.0, targetHeight.toDouble())
        )

        val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
        val warpedMat = Mat()
        Imgproc.warpPerspective(src, warpedMat, transform, Size(targetWidth.toDouble(), targetHeight.toDouble()))

        val resultBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warpedMat, resultBitmap)

        src.release()
        warpedMat.release()
        transform.release()

        return resultBitmap
    }

    /**
     * Menerapkan filter dokumen:
     * - ORIGINAL: Foto asli hasil crop
     * - MAGIC_COLOR: Menaikkan kontras & kecerahan kertas
     * - BLACK_AND_WHITE: Efek fotokopi tajam menggunakan adaptiveThreshold
     */
    fun applyFilter(bitmap: Bitmap, filterType: FilterType): Bitmap {
        if (filterType == FilterType.ORIGINAL) return bitmap

        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val dst = Mat()

        when (filterType) {
            FilterType.ORIGINAL -> return bitmap

            FilterType.MAGIC_COLOR -> {
                // Magic Color: Tingkatkan kontras dan pemutihan warna kertas
                src.convertTo(dst, -1, 1.35, 18.0)
            }

            FilterType.BLACK_AND_WHITE -> {
                // Adaptive Threshold B&W untuk efek fotokopi kertas bersih
                val gray = Mat()
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.adaptiveThreshold(
                    gray,
                    dst,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY,
                    19,
                    15.0
                )
                gray.release()
            }
        }

        val resultBitmap = Bitmap.createBitmap(dst.width(), dst.height(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, resultBitmap)

        src.release()
        dst.release()

        return resultBitmap
    }
}
