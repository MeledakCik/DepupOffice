package cloud.depup.office

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Membuka PDF lewat PdfRenderer bawaan Android (tanpa library tambahan). */
class Src(ctx: Context, uri: Uri) : Closeable {
    private val pfd: ParcelFileDescriptor =
        ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("File tidak bisa dibuka")
    val r: PdfRenderer = try { PdfRenderer(pfd) } catch (e: Throwable) { pfd.close(); throw e }
    override fun close() { try { r.close() } finally { pfd.close() } }
}

class Rendered(val bmp: Bitmap, val pw: Int, val ph: Int)

/**
 * Semua fitur PDF memakai API bawaan Android (PdfRenderer + PdfDocument) supaya APK kecil.
 * Catatan: Gabung/Pisah/Kompres/Tanda tangan merender ulang halaman menjadi gambar,
 * jadi teks di hasilnya tidak bisa diseleksi.
 */
object Pdf {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** True bila PDF dikunci password (PdfRenderer bawaan tidak bisa membukanya). */
    fun isLocked(ctx: Context, uri: Uri): Boolean = try {
        Src(ctx, uri).use { false }
    } catch (_: SecurityException) { true } catch (_: Throwable) { false }

    fun pageCount(ctx: Context, uri: Uri): Int = Src(ctx, uri).use { it.r.pageCount }

    fun thumbnail(ctx: Context, uri: Uri, pageIdx: Int = 0, targetW: Int = 300): Bitmap? = try {
        Src(ctx, uri).use { s ->
            if (pageIdx < s.r.pageCount) render(s.r, pageIdx, 1f, targetW).bmp else null
        }
    } catch (_: Throwable) { null }

    fun render(r: PdfRenderer, i: Int, scale: Float, targetW: Int = 0, maxPx: Int = 3000): Rendered {
        val p = r.openPage(i)
        try {
            var s = if (targetW > 0) targetW.toFloat() / p.width else scale
            val big = max(p.width, p.height) * s
            if (big > maxPx) s *= maxPx / big
            val bmp = Bitmap.createBitmap(max(1, (p.width * s).toInt()), max(1, (p.height * s).toInt()), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return Rendered(bmp, p.width, p.height)
        } finally {
            p.close()
        }
    }

    private fun addPage(doc: PdfDocument, n: Int, rd: Rendered, extra: ((Canvas) -> Unit)? = null) {
        val pg = doc.startPage(PdfDocument.PageInfo.Builder(rd.pw, rd.ph, n).create())
        pg.canvas.drawBitmap(rd.bmp, null, Rect(0, 0, rd.pw, rd.ph), paint)
        extra?.invoke(pg.canvas)
        doc.finishPage(pg)
    }

    private fun write(doc: PdfDocument, out: File) {
        FileOutputStream(out).use { doc.writeTo(it) }
    }

    fun merge(ctx: Context, uris: List<Uri>, out: File) {
        val doc = PdfDocument()
        try {
            var n = 0
            for (u in uris) Src(ctx, u).use { s ->
                for (i in 0 until s.r.pageCount) {
                    val rd = render(s.r, i, 2f)
                    addPage(doc, ++n, rd)
                    rd.bmp.recycle()
                }
            }
            write(doc, out)
        } finally { doc.close() }
    }

    /** from..to memakai nomor halaman mulai dari 1 (inklusif). */
    fun split(ctx: Context, uri: Uri, from: Int, to: Int, out: File) {
        val doc = PdfDocument()
        try {
            Src(ctx, uri).use { s ->
                var n = 0
                for (i in (from - 1) until to) {
                    val rd = render(s.r, i, 2f)
                    addPage(doc, ++n, rd)
                    rd.bmp.recycle()
                }
            }
            write(doc, out)
        } finally { doc.close() }
    }

    /** level 0 = ringan, 1 = sedang, 2 = tinggi (ukuran paling kecil). */
    fun compress(ctx: Context, uri: Uri, level: Int, out: File) {
        val scale = floatArrayOf(1.5f, 1.0f, 0.7f)[level]
        val q = intArrayOf(80, 65, 50)[level]
        val doc = PdfDocument()
        try {
            Src(ctx, uri).use { s ->
                for (i in 0 until s.r.pageCount) {
                    val rd = render(s.r, i, scale)
                    val bos = ByteArrayOutputStream()
                    rd.bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
                    rd.bmp.recycle()
                    val bytes = bos.toByteArray()
                    val low = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    addPage(doc, i + 1, Rendered(low, rd.pw, rd.ph))
                    low.recycle()
                }
            }
            write(doc, out)
        } finally { doc.close() }
    }

    /** Kompres PDF dengan target ukuran spesifik (kb). Iterasi presisi untuk SSCASN / CPNS / BUMN. */
    fun compressToTarget(ctx: Context, uri: Uri, targetKb: Int, out: File) {
        val targetBytes = targetKb * 1024L
        val scales = floatArrayOf(1.6f, 1.3f, 1.0f, 0.8f, 0.6f, 0.45f)
        val qualities = intArrayOf(85, 75, 65, 50, 35, 20)

        for (idx in scales.indices) {
            val scale = scales[idx]
            val q = qualities[idx]
            val doc = PdfDocument()
            try {
                Src(ctx, uri).use { s ->
                    for (i in 0 until s.r.pageCount) {
                        val rd = render(s.r, i, scale)
                        val bos = ByteArrayOutputStream()
                        rd.bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
                        rd.bmp.recycle()
                        val bytes = bos.toByteArray()
                        val low = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        addPage(doc, i + 1, Rendered(low, rd.pw, rd.ph))
                        low.recycle()
                    }
                }
                FileOutputStream(out).use { doc.writeTo(it) }
            } finally { doc.close() }

            if (out.length() <= targetBytes || idx == scales.lastIndex) {
                break
            }
        }
    }

    /** Simpan tiap halaman sebagai JPG ke Galeri (Pictures/Depup Office). Mengembalikan jumlah gambar. */
    fun toJpg(ctx: Context, uri: Uri, base: String): Int {
        var n = 0
        val r = ctx.contentResolver
        Src(ctx, uri).use { s ->
            for (i in 0 until s.r.pageCount) {
                val rd = render(s.r, i, 2f)
                try {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_${i + 1}.jpg")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Depup Office")
                    }
                    val u = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("Gagal menyimpan gambar")
                    r.openOutputStream(u)!!.use { rd.bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    n++
                } finally { rd.bmp.recycle() }
            }
        }
        return n
    }

    /** Kelola PDF: susun ulang urutan halaman, putar 90°, atau hapus halaman tertentu. */
    fun organizePdf(
        ctx: Context,
        uri: Uri,
        pageOrder: List<Int>,
        rotations: Map<Int, Int>,
        out: File
    ) {
        val doc = PdfDocument()
        try {
            Src(ctx, uri).use { s ->
                pageOrder.forEachIndexed { newIdx, origPageIdx ->
                    if (origPageIdx in 0 until s.r.pageCount) {
                        val extraRot = rotations[origPageIdx] ?: 0
                        val rd = render(s.r, origPageIdx, 2f)
                        val bmp = if (extraRot % 360 != 0) orient(rd.bmp, extraRot, false) else rd.bmp
                        try {
                            val land = bmp.width > bmp.height
                            val pw = if (land) max(rd.pw, rd.ph) else min(rd.pw, rd.ph)
                            val ph = if (land) min(rd.pw, rd.ph) else max(rd.pw, rd.ph)
                            addPage(doc, newIdx + 1, Rendered(bmp, pw, ph))
                        } finally {
                            if (bmp !== rd.bmp) bmp.recycle()
                            rd.bmp.recycle()
                        }
                    }
                }
            }
            write(doc, out)
        } finally {
            doc.close()
        }
    }

    /** Ekstrak seluruh gambar/foto dari PDF ke Galeri (Pictures/Depup Office). */
    fun extractImagesFromPdf(ctx: Context, uri: Uri, base: String): Int = toJpg(ctx, uri, "${base}_foto")

    /** Kompres foto dari Galeri HP tanpa merusak kualitas visual. Mengembalikan Pair(jumlah_foto, total_byte_hemat). */
    fun compressGalleryImages(ctx: Context, uris: List<Uri>, targetMaxDim: Int, quality: Int): Pair<Int, Long> {
        var count = 0
        var savedBytes = 0L
        val r = ctx.contentResolver

        uris.forEachIndexed { idx, u ->
            val origSize = Files.sizeOf(ctx, u)
            val bmp = decode(ctx, u, targetMaxDim)
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Kompres_${System.currentTimeMillis()}_${idx + 1}.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Depup Office")
                }
                val dst = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("Gagal menyimpan foto")
                r.openOutputStream(dst)!!.use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, quality, o) }
                val newSize = Files.sizeOf(ctx, dst)
                if (origSize > newSize && origSize > 0) savedBytes += (origSize - newSize)
                count++
            } finally {
                bmp.recycle()
            }
        }
        return count to savedBytes
    }

    /** Konversi Massal (Batch): Mengonversi banyak foto sekaligus menjadi PDF terpisah secara cepat. */
    fun batchImagesToIndividualPdf(ctx: Context, uris: List<Uri>): Int {
        var count = 0
        uris.forEachIndexed { idx, u ->
            val name = Files.nameOf(ctx, u)
            val base = Files.baseName(name)
            val out = Files.namedOut(ctx, "Batch_$base", "pdf")
            imagesToPdf(ctx, listOf(u), out, mode = 0)
            count++
        }
        return count
    }

    /** Parser Struk / Nota Keuangan dari teks OCR. */
    class ReceiptInfo(val merchant: String, val date: String, val total: String, val items: List<String>)

    fun parseReceipt(ocrText: String): ReceiptInfo {
        val lines = ocrText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        var merchant = if (lines.isNotEmpty()) lines[0] else "Toko / Merchant"
        if (merchant.length < 3 && lines.size > 1) merchant = lines[1]

        var date = ""
        val dateRegex = Regex("(?i)(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{1,2}\\s+(jan|feb|mar|apr|mei|jun|jul|agu|sep|okt|nov|des)\\w*\\s+\\d{2,4})")
        for (l in lines) {
            val match = dateRegex.find(l)
            if (match != null) {
                date = match.value
                break
            }
        }
        if (date.isBlank()) date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("id")).format(java.util.Date())

        var total = ""
        val totalRegex = Regex("(?i)(total|grand total|bayar|jumlah|rp)\\s*[:=]?\\s*(rp)?\\s*([\\d.,]{3,15})")
        for (l in lines.reversed()) {
            val match = totalRegex.find(l)
            if (match != null) {
                total = match.groupValues.lastOrNull().orEmpty().ifBlank { match.value }
                break
            }
        }
        if (total.isBlank()) {
            val numberRegex = Regex("([\\d.,]{4,12})")
            for (l in lines.reversed()) {
                if (l.contains("rp", ignoreCase = true) || l.contains("total", ignoreCase = true)) {
                    val m = numberRegex.find(l)
                    if (m != null) { total = m.value; break }
                }
            }
        }
        if (total.isBlank()) total = "Rp 0"

        val items = lines.filter { !it.contains(merchant, ignoreCase = true) && !it.contains("total", ignoreCase = true) }.take(8)
        return ReceiptInfo(merchant, date, total, items)
    }

    /** Penerjemah Teks Dokumen sederhana (Inggris <-> Indonesia). */
    fun translateDocText(text: String, toIndonesian: Boolean): String {
        if (text.isBlank()) return text
        if (toIndonesian) {
            val enToId = mapOf(
                "agreement" to "perjanjian", "contract" to "kontrak", "article" to "pasal", "section" to "bagian",
                "invoice" to "faktur", "receipt" to "struk / nota", "total" to "jumlah total",
                "date" to "tanggal", "name" to "nama", "signature" to "tanda tangan", "approved" to "disetujui",
                "confidential" to "rahasia", "document" to "dokumen", "payment" to "pembayaran",
                "party" to "pihak", "first party" to "pihak pertama", "second party" to "pihak kedua",
                "subject" to "perihal", "description" to "keterangan", "amount" to "nominal", "address" to "alamat"
            )
            var res = text
            for ((en, id) in enToId) {
                res = res.replace(Regex("(?i)\\b$en\\b"), id.uppercase())
            }
            return res
        } else {
            val idToEn = mapOf(
                "perjanjian" to "agreement", "kontrak" to "contract", "pasal" to "article", "bagian" to "section",
                "faktur" to "invoice", "nota" to "receipt", "total" to "total", "tanggal" to "date",
                "nama" to "name", "tanda tangan" to "signature", "disetujui" to "approved", "rahasia" to "confidential",
                "dokumen" to "document", "pembayaran" to "payment", "alamat" to "address"
            )
            var res = text
            for ((id, en) in idToEn) {
                res = res.replace(Regex("(?i)\\b$id\\b"), en.uppercase())
            }
            return res
        }
    }

    /** Ekstrak Tanda Tangan / Stempel menjadi PNG Transparan (menghapus latar kertas putih). */
    fun extractTransparentSignature(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            if (luma > 195) {
                px[i] = 0x00FFFFFF
            } else {
                val alpha = ((255 - luma) * 1.3f).toInt().coerceIn(0, 255)
                px[i] = (alpha shl 24) or (c and 0x00FFFFFF)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    class DocSummary(val numbers: List<String>, val dates: List<String>, val amounts: List<String>, val keyPoints: List<String>)

    fun extractDocSummary(text: String): DocSummary {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val dates = mutableListOf<String>()
        val amounts = mutableListOf<String>()
        val numbers = mutableListOf<String>()

        val dateRegex = Regex("(?i)(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{1,2}\\s+(jan|feb|mar|apr|mei|jun|jul|agu|sep|okt|nov|des)\\w*\\s+\\d{2,4})")
        val amountRegex = Regex("(?i)(rp|idr)?\\s*([\\d.,]{4,15})")
        val numberRegex = Regex("(?i)(pasal|nomor|no\\.|no)\\s*[:=]?\\s*([\\w./-]+)")

        for (l in lines) {
            dateRegex.find(l)?.let { if (!dates.contains(it.value)) dates.add(it.value) }
            numberRegex.find(l)?.let { if (!numbers.contains(it.value)) numbers.add(it.value) }
            if (l.contains("rp", ignoreCase = true) || l.contains("total", ignoreCase = true) || l.contains("biaya", ignoreCase = true)) {
                amountRegex.find(l)?.let { if (!amounts.contains(it.value)) amounts.add(it.value) }
            }
        }
        val keyPoints = lines.take(5)
        return DocSummary(numbers.take(4), dates.take(4), amounts.take(4), keyPoints)
    }

    fun decode(ctx: Context, uri: Uri, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Gambar tidak terbaca")
        val orient = try {
            ctx.contentResolver.openInputStream(uri)!!.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        val f = min(1f, maxDim.toFloat() / max(raw.width, raw.height))
        if (f < 1f) m.postScale(f, f)
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    /** Mode dokumen: ambang adaptif lokal (integral image) -> hitam-putih tajam, ukuran PDF kecil. */
    fun toDocument(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            g[i] = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
        }
        val iw = w + 1
        val integ = IntArray(iw * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += g[y * w + x]
                integ[(y + 1) * iw + x + 1] = integ[y * iw + x + 1] + row
            }
        }
        val r = max(8, max(w, h) / 32)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w, x + r + 1)
                val sum = integ[y1 * iw + x1] - integ[y0 * iw + x1] - integ[y1 * iw + x0] + integ[y0 * iw + x0]
                val mean = sum / ((y1 - y0) * (x1 - x0))
                px[y * w + x] = if (g[y * w + x] * 100 < mean * 92) black else white
            }
        }
        val o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        o.setPixels(px, 0, w, 0, 0, w, h)
        return o
    }

    /** mode: 0 berwarna, 1 abu-abu, 2 dokumen hitam-putih. */
    fun imagesToPdf(ctx: Context, uris: List<Uri>, out: File, mode: Int = 0) {
        val doc = PdfDocument()
        try {
            uris.forEachIndexed { idx, u ->
                val raw = decode(ctx, u, 1600)
                val bmp = if (mode == 2) toDocument(raw).also { raw.recycle() } else raw
                val drawPaint = when (mode) {
                    1 -> Paint(paint).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
                    3 -> Paint(paint).apply { colorFilter = ColorMatrixColorFilter(magicMatrix()) }
                    else -> paint
                }
                val land = bmp.width > bmp.height
                val pw = if (land) 842 else 595
                val ph = if (land) 595 else 842
                val m = 16f
                val s = min((pw - 2 * m) / bmp.width, (ph - 2 * m) / bmp.height)
                val dw = bmp.width * s
                val dh = bmp.height * s
                val l = (pw - dw) / 2f
                val t = (ph - dh) / 2f
                val pg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, idx + 1).create())
                pg.canvas.drawBitmap(bmp, null, RectF(l, t, l + dw, t + dh), drawPaint)
                doc.finishPage(pg)
                bmp.recycle()
            }
            write(doc, out)
        } finally { doc.close() }
    }

    /** Matriks kecerahan/kontras. Nilai -50..+50 (persen). */
    fun adjustMatrix(bright: Int, contrast: Int): ColorMatrix {
        val c = 1f + contrast / 100f
        val t = 128f * (1f - c) + bright / 100f * 120f
        return ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    /** Magic Color: kontras & saturasi sedikit dinaikkan agar kertas lebih bersih dan warna hidup. */
    fun magicMatrix(): ColorMatrix = ColorMatrix(floatArrayOf(
        1.25f, 0f, 0f, 0f, -25f,
        0f, 1.25f, 0f, 0f, -25f,
        0f, 0f, 1.25f, 0f, -25f,
        0f, 0f, 0f, 1f, 0f
    )).also { it.postConcat(ColorMatrix().apply { setSaturation(1.15f) }) }

    /** Terapkan filter mode (0 asli, 1 abu-abu, 2 hitam-putih dokumen, 3 magic) ke bitmap hasil potong. */
    fun applyMode(raw: Bitmap, mode: Int): Bitmap {
        if (mode == 2) return toDocument(raw).also { raw.recycle() }
        if (mode == 0) return raw
        val o = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
        val cm = if (mode == 1) ColorMatrix().apply { setSaturation(0f) } else magicMatrix()
        Canvas(o).drawBitmap(raw, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        raw.recycle()
        return o
    }

    /** Halaman scan -> JPG di Galeri (Pictures/Depup Office). Mengembalikan jumlah gambar. */
    fun scansToJpg(ctx: Context, pages: List<ScanPage>, base: String, mode: Int, maxDim: Int, bright: Int, contrast: Int): Int {
        var n = 0
        val r = ctx.contentResolver
        pages.forEachIndexed { idx, pg ->
            val bmp = applyMode(renderScan(ctx, pg, maxDim, bright, contrast), mode)
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, if (pages.size == 1) "$base.jpg" else "${base}_${idx + 1}.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Depup Office")
                }
                val u = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("Gagal menyimpan gambar")
                r.openOutputStream(u)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                n++
            } finally { bmp.recycle() }
        }
        return n
    }

    /** Putar kelipatan 90 derajat + balik horizontal. Mengembalikan [src] bila tidak ada perubahan. */
    fun orient(src: Bitmap, rot: Int, flip: Boolean): Bitmap {
        if (rot % 360 == 0 && !flip) return src
        val m = Matrix().apply { postRotate(rot.toFloat()); if (flip) postScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Luruskan kemiringan halus [deg] derajat (searah jarum jam bila positif). Ukuran tetap; sudut diisi putih. */
    fun deskew(src: Bitmap, deg: Float): Bitmap {
        if (kotlin.math.abs(deg) < 0.05f) return src
        val o = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        o.eraseColor(Color.WHITE)
        val c = Canvas(o)
        c.rotate(deg, src.width / 2f, src.height / 2f)
        c.drawBitmap(src, 0f, 0f, paint)
        return o
    }

    /** Terapkan putar + luruskan + potong + kecerahan/kontras + sensur hitam + watermark pada satu halaman scan. */
    fun renderScan(ctx: Context, pg: ScanPage, maxDim: Int, bright: Int, contrast: Int): Bitmap {
        val raw = decode(ctx, Uri.fromFile(pg.file), maxDim)
        val oriented = orient(raw, pg.rot, pg.flip)
        if (oriented !== raw) raw.recycle()
        val rotated = deskew(oriented, pg.skew)
        if (rotated !== oriented) oriented.recycle()
        val w = rotated.width
        val h = rotated.height
        val x = (pg.crop.left * w).toInt().coerceIn(0, w - 2)
        val y = (pg.crop.top * h).toInt().coerceIn(0, h - 2)
        val cw = ((pg.crop.right - pg.crop.left) * w).toInt().coerceIn(2, w - x)
        val ch = ((pg.crop.bottom - pg.crop.top) * h).toInt().coerceIn(2, h - y)
        val cropped = Bitmap.createBitmap(rotated, x, y, cw, ch)
        if (cropped !== rotated) rotated.recycle()

        val baseBmp = if (bright != 0 || contrast != 0) {
            val o = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
            val pt = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(adjustMatrix(bright, contrast)) }
            Canvas(o).drawBitmap(cropped, 0f, 0f, pt)
            cropped.recycle()
            o
        } else cropped

        // Lukis sensur hitam (blackout) jika ada
        if (pg.redactions.isNotEmpty()) {
            val canvas = Canvas(baseBmp)
            val bPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
            for (r in pg.redactions) {
                val rx1 = r.left * baseBmp.width
                val ry1 = r.top * baseBmp.height
                val rx2 = r.right * baseBmp.width
                val ry2 = r.bottom * baseBmp.height
                canvas.drawRect(rx1, ry1, rx2, ry2, bPaint)
            }
        }

        // Lukis watermark diagonal jika ada
        if (!pg.watermark.isNullOrBlank()) {
            val canvas = Canvas(baseBmp)
            canvas.save()
            canvas.rotate(-32f, baseBmp.width / 2f, baseBmp.height / 2f)
            val wmPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x55DC2626.toInt() // Semi-transparent red
                textSize = min(baseBmp.width, baseBmp.height) * 0.08f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(pg.watermark!!, baseBmp.width / 2f, baseBmp.height / 2f, wmPaint)
            canvas.restore()
        }

        return baseBmp
    }

    /** Halaman scan yang sudah diedit -> PDF. mode: 0 warna, 1 abu-abu, 2 dokumen hitam-putih. */
    fun scansToPdf(ctx: Context, pages: List<ScanPage>, out: File, mode: Int, maxDim: Int, bright: Int, contrast: Int) {
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { idx, pg ->
                val raw = renderScan(ctx, pg, maxDim, bright, contrast)
                val bmp = if (mode == 2) toDocument(raw).also { raw.recycle() } else raw
                val drawPaint = when (mode) {
                    1 -> Paint(paint).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
                    3 -> Paint(paint).apply { colorFilter = ColorMatrixColorFilter(magicMatrix()) }
                    else -> paint
                }
                val land = bmp.width > bmp.height
                val pw = if (land) 842 else 595
                val ph = if (land) 595 else 842
                val mg = 16f
                val sc = min((pw - 2 * mg) / bmp.width, (ph - 2 * mg) / bmp.height)
                val dw = bmp.width * sc
                val dh = bmp.height * sc
                val l = (pw - dw) / 2f
                val t = (ph - dh) / 2f
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, idx + 1).create())
                page.canvas.drawBitmap(bmp, null, RectF(l, t, l + dw, t + dh), drawPaint)
                doc.finishPage(page)
                bmp.recycle()
            }
            write(doc, out)
        } finally { doc.close() }
    }

    fun text(content: String, out: File) {
        val doc = PdfDocument()
        try {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f }
            val margin = 48
            val w = 595 - margin * 2
            val availH = 842 - margin * 2
            val layout = StaticLayout.Builder.obtain(content, 0, content.length, tp, w)
                .setLineSpacing(2f, 1.1f).build()
            var line = 0
            var no = 1
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= availH) end++
                if (end == line) end = line + 1
                val pg = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, no).create())
                val c = pg.canvas
                c.save()
                c.translate(margin.toFloat(), margin.toFloat() - top)
                c.clipRect(0f, top.toFloat(), w.toFloat(), layout.getLineBottom(end - 1).toFloat())
                layout.draw(c)
                c.restore()
                doc.finishPage(pg)
                line = end
                no++
            }
            write(doc, out)
        } finally { doc.close() }
    }

    /** pageNo mulai dari 1; pos: 0 kiri, 1 tengah, 2 kanan. */
    fun sign(ctx: Context, uri: Uri, sig: Bitmap, pageNo: Int, pos: Int, out: File) {
        val doc = PdfDocument()
        try {
            Src(ctx, uri).use { s ->
                for (i in 0 until s.r.pageCount) {
                    val rd = render(s.r, i, 2f)
                    addPage(doc, i + 1, rd) { c ->
                        if (i + 1 == pageNo) {
                            var sw = rd.pw * 0.28f
                            var sh = sw * sig.height / sig.width
                            if (sh > rd.ph * 0.2f) { sh = rd.ph * 0.2f; sw = sh * sig.width / sig.height }
                            val x = when (pos) { 0 -> 36f; 1 -> (rd.pw - sw) / 2f; else -> rd.pw - sw - 36f }
                            val y = rd.ph - sh - 48f
                            c.drawBitmap(sig, null, RectF(x, y, x + sw, y + sh), paint)
                        }
                    }
                    rd.bmp.recycle()
                }
            }
            write(doc, out)
        } finally { doc.close() }
    }
}
