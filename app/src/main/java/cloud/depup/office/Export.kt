package cloud.depup.office

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Satu pilihan rasio aspek. kind: 0 = otomatis (A4 + margin), 1 = asli (ikuti gambar), 2 = rasio tetap. */
class AspectOpt(val name: String, val kind: Int, val ratio: Float = 0f, val followOrientation: Boolean = false)

/** Halaman yang sudah ditata: bitmap final + ukuran halaman PDF (titik) + posisi gambar. */
class Laid(val bmp: Bitmap, val orig: Bitmap, val pageW: Float, val pageH: Float, val x: Float, val y: Float, val w: Float, val h: Float) {
    fun recycle() { if (bmp !== orig) bmp.recycle(); orig.recycle() }
}

object Export {
    val ASPECTS = listOf(
        AspectOpt("Otomatis", 0),
        AspectOpt("Asli", 1),
        AspectOpt("A4", 2, 210f / 297f, true),
        AspectOpt("Letter", 2, 8.5f / 11f, true),
        AspectOpt("F4", 2, 215f / 330f, true),
        AspectOpt("1:1", 2, 1f),
        AspectOpt("4:3", 2, 4f / 3f),
        AspectOpt("16:9", 2, 16f / 9f)
    )

    // Indeks format di modal simpan
    const val F_PDF = 0
    const val F_WORD = 1
    const val F_JPG = 2

    val PDF_DIM = intArrayOf(2000, 1600, 1000)
    val PDF_Q = intArrayOf(88, 80, 65)
    val JPG_DIM = intArrayOf(1920, 2560, 3840)
    const val JPG_Q = 92
    const val WORD_IMG_DIM = 1600
    const val WORD_IMG_Q = 80
    /** OCR butuh resolusi cukup; di bawah ini hasil baca teks turun drastis. */
    const val OCR_MIN_DIM = 1600

    /** Skala + margin rasio tetap: konten diletakkan di tengah kanvas putih dengan margin 2,5%. */
    private fun fitRatio(src: Bitmap, ratio: Float): Bitmap {
        val inner = 0.95f
        val cw = max(src.width / inner, src.height / inner * ratio)
        val ch = cw / ratio
        val w = cw.toInt().coerceAtLeast(src.width)
        val h = ch.toInt().coerceAtLeast(src.height)
        val o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        o.eraseColor(Color.WHITE)
        Canvas(o).drawBitmap(src, (w - src.width) / 2f, (h - src.height) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        return o
    }

    fun layout(src: Bitmap, a: AspectOpt): Laid {
        when (a.kind) {
            0 -> {
                val land = src.width > src.height
                val pw = if (land) 842f else 595f
                val ph = if (land) 595f else 842f
                val mg = 16f
                val s = min((pw - 2 * mg) / src.width, (ph - 2 * mg) / src.height)
                val dw = src.width * s; val dh = src.height * s
                return Laid(src, src, pw, ph, (pw - dw) / 2f, (ph - dh) / 2f, dw, dh)
            }
            1 -> {
                val s = 842f / max(src.width, src.height)
                val pw = src.width * s; val ph = src.height * s
                return Laid(src, src, pw, ph, 0f, 0f, pw, ph)
            }
            else -> {
                var r = a.ratio
                if (a.followOrientation && src.width > src.height) r = 1f / r
                val fitted = fitRatio(src, r)
                val pw = if (r <= 1f) 595f else 842f
                val ph = pw / r
                return Laid(fitted, src, pw, ph, 0f, 0f, pw, ph)
            }
        }
    }

    private fun jpeg(b: Bitmap, q: Int): ByteArray {
        val bos = ByteArrayOutputStream(256 * 1024)
        b.compress(Bitmap.CompressFormat.JPEG, q, bos)
        return bos.toByteArray()
    }

    /**
     * Dimensi dekode untuk OCR. Foto didekode dulu baru dipotong, jadi kalau halaman hanya sebagian kecil foto,
     * resolusinya jatuh. Di sini dimensi dinaikkan supaya area potongan tetap ±2400 px (maks 3600 demi memori).
     */
    fun ocrDim(pg: ScanPage, base: Int = 2400, cap: Int = 3600): Int {
        val f = max(pg.crop.right - pg.crop.left, pg.crop.bottom - pg.crop.top).coerceIn(0.2f, 1f)
        return min(cap, (base / f).toInt()).coerceAtLeast(base)
    }

    /** OCR dengan resolusi tinggi; kalau memori tidak cukup, ulangi dengan resolusi biasa. */
    private fun ocrRender(ctx: Context, pg: ScanPage, mode: Int, bright: Int, contrast: Int): Bitmap =
        try { process(ctx, pg, mode, ocrDim(pg), bright, contrast) }
        catch (_: OutOfMemoryError) { process(ctx, pg, mode, 2000, bright, contrast) }

    /** Putar + straighten + potong + kecerahan + filter untuk satu halaman. */
    fun process(ctx: Context, pg: ScanPage, mode: Int, maxDim: Int, bright: Int, contrast: Int): Bitmap =
        Pdf.applyMode(Pdf.renderScan(ctx, pg, maxDim, bright, contrast), mode)

    /** Perkiraan byte untuk SATU halaman (halaman pertama dipakai sebagai contoh). */
    fun estimatePage(
        ctx: Context, pg: ScanPage, fmt: Int, opt: Int, mode: Int, bright: Int, contrast: Int, a: AspectOpt, ocr: Boolean
    ): Long {
        val (dim0, q) = when (fmt) {
            F_PDF -> PDF_DIM[opt] to PDF_Q[opt]
            F_WORD -> WORD_IMG_DIM to WORD_IMG_Q
            else -> JPG_DIM[opt] to JPG_Q
        }
        val dim = if (fmt == F_PDF && ocr) max(dim0, OCR_MIN_DIM) else dim0
        val lay = layout(process(ctx, pg, mode, dim, bright, contrast), a)
        try { return jpeg(lay.bmp, q).size.toLong() } finally { lay.recycle() }
    }

    fun pdf(
        ctx: Context, pages: List<ScanPage>, out: File, mode: Int, bright: Int, contrast: Int,
        opt: Int, a: AspectOpt, ocr: Boolean, password: String?
    ) {
        val dim = if (ocr) max(PDF_DIM[opt], OCR_MIN_DIM) else PDF_DIM[opt]
        val w = PdfWriter(out, pages.size, password)
        try {
            for (pg in pages) {
                val lay = layout(process(ctx, pg, mode, dim, bright, contrast), a)
                try {
                    val res = if (ocr) Ocr.run(lay.bmp) else null
                    val jp = jpeg(lay.bmp, PDF_Q[opt])
                    w.addPage(jp, lay.bmp.width, lay.bmp.height, lay.pageW, lay.pageH, lay.x, lay.y, lay.w, lay.h, res)
                } finally { lay.recycle() }
            }
            w.finish()
        } catch (t: Throwable) { w.abort(); throw t }
    }

    /** asImage = false -> teks hasil OCR (bisa diedit); true -> tiap halaman jadi gambar. */
    fun docx(
        ctx: Context, pages: List<ScanPage>, out: File, mode: Int, bright: Int, contrast: Int,
        asImage: Boolean, a: AspectOpt
    ) {
        val d = DocxWriter(out)
        try {
            pages.forEachIndexed { i, pg ->
                d.beginPage()
                if (asImage) {
                    val lay = layout(process(ctx, pg, mode, WORD_IMG_DIM, bright, contrast), a)
                    try { d.image(jpeg(lay.bmp, WORD_IMG_Q), lay.bmp.width, lay.bmp.height) } finally { lay.recycle() }
                } else {
                    val bmp = ocrRender(ctx, pg, mode, bright, contrast)
                    val res = try { Ocr.run(bmp) } finally { bmp.recycle() }
                    if (res.conf < Ocr.LOW_CONF) {
                        d.note("⚠ OCR kurang yakin di halaman ${i + 1} (kemungkinan tulisan tangan atau foto buram). Foto halaman disertakan agar isinya tidak hilang.")
                        val lay = layout(process(ctx, pg, mode, WORD_IMG_DIM, bright, contrast), a)
                        try { d.image(jpeg(lay.bmp, WORD_IMG_Q), lay.bmp.width, lay.bmp.height) } finally { lay.recycle() }
                    }
                    if (res.blocks.isEmpty()) d.note("(Tidak ada teks terdeteksi di halaman ${i + 1})")
                    else for (b in res.blocks) when (b) {
                        is Para -> d.runsParagraph(b.runs)
                        is Table -> d.table(b)
                    }
                }
            }
            d.finish()
        } catch (t: Throwable) { d.abort(out); throw t }
    }

    fun jpg(
        ctx: Context, pages: List<ScanPage>, base: String, mode: Int, bright: Int, contrast: Int, opt: Int, a: AspectOpt
    ): Int {
        var n = 0
        val r = ctx.contentResolver
        pages.forEachIndexed { idx, pg ->
            val lay = layout(process(ctx, pg, mode, JPG_DIM[opt], bright, contrast), a)
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, if (pages.size == 1) "$base.jpg" else "${base}_${idx + 1}.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Depup Office")
                }
                val u = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("Gagal menyimpan gambar")
                r.openOutputStream(u)!!.use { lay.bmp.compress(Bitmap.CompressFormat.JPEG, JPG_Q, it) }
                n++
            } finally { lay.recycle() }
        }
        return n
    }

    /** OCR satu halaman untuk tombol "Teks" di editor. */
    fun ocrPage(ctx: Context, pg: ScanPage, mode: Int, bright: Int, contrast: Int): OcrPage {
        val bmp = ocrRender(ctx, pg, mode, bright, contrast)
        try { return Ocr.run(bmp) } finally { bmp.recycle() }
    }
}
