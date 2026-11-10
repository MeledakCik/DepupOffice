package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Coretan pada satu halaman PDF. Semua koordinat dalam pecahan 0..1 terhadap lebar/tinggi halaman,
 * jadi sama persis di layar (berapa pun zoom-nya) maupun saat ditulis ke PDF.
 */
class PageMarks {
    /** Goresan pena / stabilo. [width] = pecahan lebar halaman. */
    class Stroke(val color: Int, val width: Float, val highlight: Boolean, val pts: MutableList<Float> = ArrayList())
    /** Catatan teks di posisi (x, y) = pojok kiri atas kotak catatan. */
    class Note(val text: String, val x: Float, val y: Float)
    /** Tanda tangan (PNG transparan) di kotak [box]. */
    class Stamp(val bmp: Bitmap, val box: RectF)

    val items = ArrayList<Any>()          // urutan gambar = urutan dibuat (untuk Urungkan)
    fun isEmpty() = items.isEmpty()
}

/** Alat PDF tambahan: watermark, kunci password, OCR (bisa dicari), PDF ke Word, dan simpan coretan. */
object PdfTools {
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Skala render halaman (poin PDF -> piksel). Lebih kecil di HP lemah supaya hemat memori. */
    private fun scale() = if (Perf.low) 1.6f else 2f

    private fun jpeg(b: Bitmap, q: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    // ------------------------------------------------------------------ Watermark

    /**
     * Watermark teks di setiap halaman. [style] 0 = satu diagonal besar di tengah, 1 = diulang (pola ubin).
     * [alpha] 0..255.
     */
    fun watermark(ctx: Context, uri: Uri, text: String, style: Int, color: Int, alpha: Int, out: File, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val doc = PdfDocument()
        try {
            Src(ctx, uri).use { s ->
                val n = s.r.pageCount
                for (i in 0 until n) {
                    progress(i + 1, n)
                    val rd = Pdf.render(s.r, i, scale())
                    try {
                        val pg = doc.startPage(PdfDocument.PageInfo.Builder(rd.pw, rd.ph, i + 1).create())
                        val c = pg.canvas
                        c.drawBitmap(rd.bmp, null, Rect(0, 0, rd.pw, rd.ph), bmpPaint)
                        drawWatermark(c, rd.pw.toFloat(), rd.ph.toFloat(), text, style, color, alpha)
                        doc.finishPage(pg)
                    } finally { rd.bmp.recycle() }
                }
            }
            FileOutputStream(out).use { doc.writeTo(it) }
        } catch (t: Throwable) { out.delete(); throw t } finally { doc.close() }
    }

    fun drawWatermark(c: Canvas, w: Float, h: Float, text: String, style: Int, color: Int, alpha: Int) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = alpha.coerceIn(10, 255)
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        c.save()
        c.rotate(-35f, w / 2f, h / 2f)
        if (style == 0) {
            // Sebesar mungkin tapi tetap muat di diagonal halaman.
            p.textSize = min(w, h) * 0.14f
            val diag = hypot(w, h) * 0.8f
            val tw = p.measureText(text)
            if (tw > diag) p.textSize *= diag / tw
            c.drawText(text, w / 2f, h / 2f - (p.descent() + p.ascent()) / 2f, p)
        } else {
            p.textSize = min(w, h) * 0.045f
            val stepX = p.measureText(text) + min(w, h) * 0.12f
            val stepY = p.textSize * 4.5f
            val span = hypot(w, h)
            var row = 0
            var y = h / 2f - span
            while (y < h / 2f + span) {
                var x = w / 2f - span + (if (row % 2 == 1) stepX / 2f else 0f)
                while (x < w / 2f + span) { c.drawText(text, x, y, p); x += stepX }
                y += stepY; row++
            }
        }
        c.restore()
    }

    // ------------------------------------------------------------------ Kunci password + OCR

    /**
     * Tulis ulang PDF: tiap halaman jadi gambar JPEG. [password] != null -> dikunci (AES-128).
     * [ocr] = true -> teks hasil OCR ditanam tak terlihat di bawah gambar, jadi PDF bisa dicari & disalin.
     */
    fun rebuild(ctx: Context, uri: Uri, out: File, password: String?, ocr: Boolean, progress: (Int, Int) -> Unit = { _, _ -> }) {
        Src(ctx, uri).use { s ->
            val n = s.r.pageCount
            val w = PdfWriter(out, n, password)
            try {
                for (i in 0 until n) {
                    progress(i + 1, n)
                    // OCR butuh resolusi cukup (±1600 px sisi panjang); tanpa OCR cukup skala biasa.
                    val rd = if (ocr) Pdf.render(s.r, i, ocrScale(s, i), 0, Perf.ocrCap()) else Pdf.render(s.r, i, scale())
                    try {
                        val res = if (ocr) Ocr.run(rd.bmp) else null
                        w.addPage(jpeg(rd.bmp, 85), rd.bmp.width, rd.bmp.height, rd.pw.toFloat(), rd.ph.toFloat(),
                            0f, 0f, rd.pw.toFloat(), rd.ph.toFloat(), res)
                    } finally { rd.bmp.recycle() }
                }
                w.finish()
            } catch (t: Throwable) { w.abort(); throw t }
        }
    }

    // ------------------------------------------------------------------ PDF ke Word

    /** PDF -> .docx yang bisa diedit (OCR per halaman; teks digital pun dibaca lewat OCR karena PdfRenderer tidak memberi teks). */
    fun toWord(ctx: Context, uri: Uri, out: File, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val d = DocxWriter(out)
        try {
            Src(ctx, uri).use { s ->
                val n = s.r.pageCount
                for (i in 0 until n) {
                    progress(i + 1, n)
                    val rd = Pdf.render(s.r, i, ocrScale(s, i), 0, Perf.ocrCap())
                    try {
                        d.beginPage()
                        val res = Ocr.run(rd.bmp)
                        if (res.conf < Ocr.LOW_CONF || res.blocks.isEmpty()) {
                            // Isi tidak terbaca yakin (foto/tulisan tangan): sertakan gambar halaman agar tidak hilang.
                            val small = scaled(rd.bmp, Export.WORD_IMG_DIM)
                            try { d.image(jpeg(small, Export.WORD_IMG_Q), small.width, small.height) } finally { if (small !== rd.bmp) small.recycle() }
                        }
                        for (b in res.blocks) when (b) {
                            is Para -> d.runsParagraph(b.runs)
                            is Table -> d.table(b)
                        }
                    } finally { rd.bmp.recycle() }
                }
            }
            d.finish()
        } catch (t: Throwable) { d.abort(out); throw t }
    }

    /** Skala agar sisi panjang halaman ±[Export.OCR_MIN_DIM] px — cukup tajam untuk OCR. */
    private fun ocrScale(s: Src, i: Int): Float {
        val p = s.r.openPage(i)
        val longSide = try { max(p.width, p.height).toFloat() } finally { p.close() }
        return (Export.OCR_MIN_DIM / longSide).coerceAtLeast(scale())
    }

    private fun scaled(b: Bitmap, maxDim: Int): Bitmap {
        val f = maxDim.toFloat() / max(b.width, b.height)
        return if (f >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    // ------------------------------------------------------------------ Coretan (stabilo, pena, catatan, TTD)

    /** Gambar semua coretan [m] ke kanvas berukuran [w] x [h] (piksel layar atau poin PDF). */
    fun drawMarks(c: Canvas, w: Float, h: Float, m: PageMarks) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        for (it in m.items) when (it) {
            is PageMarks.Stroke -> {
                if (it.pts.size < 2) continue
                p.color = it.color
                p.strokeWidth = it.width * w
                p.strokeCap = if (it.highlight) Paint.Cap.SQUARE else Paint.Cap.ROUND
                // Stabilo: warna dikalikan dengan isi halaman, jadi teks di bawahnya tetap terbaca.
                p.xfermode = if (it.highlight) PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) else null
                val path = Path()
                path.moveTo(it.pts[0] * w, it.pts[1] * h)
                var k = 2
                while (k + 1 < it.pts.size) { path.lineTo(it.pts[k] * w, it.pts[k + 1] * h); k += 2 }
                if (it.pts.size == 2) path.lineTo(it.pts[0] * w + 0.5f, it.pts[1] * h)
                c.drawPath(path, p)
                p.xfermode = null
            }
            is PageMarks.Note -> drawNote(c, w, h, it)
            is PageMarks.Stamp -> c.drawBitmap(it.bmp, null,
                RectF(it.box.left * w, it.box.top * h, it.box.right * w, it.box.bottom * h), bmpPaint)
        }
    }

    /** Catatan = kotak kuning lembut berisi teks (gaya "sticky note"). */
    fun noteRect(w: Float, h: Float, n: PageMarks.Note): RectF {
        val lay = noteLayout(w, n.text)
        val pad = w * 0.012f
        val x = n.x * w; val y = n.y * h
        return RectF(x, y, x + lay.width + pad * 2, y + lay.height + pad * 2)
    }

    private fun noteLayout(w: Float, text: String): StaticLayout {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 32, 0); textSize = w * 0.024f }
        val maxW = (w * 0.45f).toInt()
        val lineW = text.lines().maxOfOrNull { tp.measureText(it) }?.toInt()?.plus(2) ?: maxW
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, min(maxW, max(lineW, 10)))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }

    private fun drawNote(c: Canvas, w: Float, h: Float, n: PageMarks.Note) {
        val r = noteRect(w, h, n)
        val pad = w * 0.012f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 241, 150) }
        val bd = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(214, 170, 0); strokeWidth = w * 0.002f }
        c.drawRoundRect(r, pad, pad, bg)
        c.drawRoundRect(r, pad, pad, bd)
        c.save()
        c.translate(r.left + pad, r.top + pad)
        noteLayout(w, n.text).draw(c)
        c.restore()
    }

    /** Tulis PDF baru: halaman asli + coretan. Halaman tanpa coretan tetap dirender ulang (keterbatasan PdfRenderer). */
    fun saveAnnotated(ctx: Context, uri: Uri, marks: Map<Int, PageMarks>, out: File, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val doc = PdfDocument()
        try {
            Src(ctx, uri).use { s ->
                val n = s.r.pageCount
                for (i in 0 until n) {
                    progress(i + 1, n)
                    val rd = Pdf.render(s.r, i, scale())
                    try {
                        val pg = doc.startPage(PdfDocument.PageInfo.Builder(rd.pw, rd.ph, i + 1).create())
                        val c = pg.canvas
                        // Isi halaman digambar ke bitmap kerja dulu supaya mode MULTIPLY stabilo bekerja di atasnya.
                        val m = marks[i]
                        if (m != null && !m.isEmpty()) {
                            val work = rd.bmp.copy(Bitmap.Config.ARGB_8888, true)
                            try {
                                val wc = Canvas(work)
                                drawMarks(wc, work.width.toFloat(), work.height.toFloat(), m)
                                c.drawBitmap(work, null, Rect(0, 0, rd.pw, rd.ph), bmpPaint)
                            } finally { work.recycle() }
                        } else c.drawBitmap(rd.bmp, null, Rect(0, 0, rd.pw, rd.ph), bmpPaint)
                        doc.finishPage(pg)
                    } finally { rd.bmp.recycle() }
                }
            }
            FileOutputStream(out).use { doc.writeTo(it) }
        } catch (t: Throwable) { out.delete(); throw t } finally { doc.close() }
    }
}
