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
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
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
    /**
     * Tanda tangan (PNG transparan) di kotak [box] (pecahan halaman, sebelum diputar), diputar [rot] derajat searah
     * jarum jam terhadap pusat kotak. [bmp] bisa diganti (ganti warna tinta).
     */
    class Stamp(var bmp: Bitmap, val box: RectF, var rot: Float = 0f) {
        fun copy(dx: Float = 0f, dy: Float = 0f) = Stamp(bmp, RectF(box).apply { offset(dx, dy) }, rot)
    }

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
        if (style == 2) {
            // Stempel tanggal / teks di pojok kanan bawah halaman
            p.textSize = min(w, h) * 0.032f
            p.textAlign = Paint.Align.RIGHT
            val margin = min(w, h) * 0.04f
            c.drawText(text, w - margin, h - margin, p)
            return
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

    /** Isi berkas PDF (maks. [Files.ramCap] — seperempat RAM aplikasi; lebih besar → pesan jelas, bukan crash). */
    private fun readPdf(ctx: Context, uri: Uri): ByteArray {
        return Files.readOrNull(ctx, uri) ?: throw IllegalStateException("Berkas tidak bisa dibaca")
    }

    /**
     * PDF → Word semirip mungkin: teks asli + font/ukuran/warna, tabel (garis, sel gabungan, arsiran), foto, bentuk,
     * dan tata letak dibaca langsung dari isi PDF ([PdfToDocx]). Halaman hasil pindaian (tanpa teks) dibaca dengan OCR.
     * Bila struktur PDF tidak bisa dibaca sama sekali, jatuh ke jalur OCR lama ([toWordOcr]).
     */
    /**
     * Kembalikan true bila hasil Word DIKUNCI: PDF terkunci yang dibuka dengan password → Word hanya-baca, mengedit
     * butuh password yang sama ([DocxProtect]). PDF tanpa password pembuka tapi dibatasi pemiliknya (tidak boleh
     * disalin/diubah) butuh password pemilik.
     */
    fun toWord(ctx: Context, uri: Uri, out: File, password: String = "", progress: (Int, Int) -> Unit = { _, _ -> }): Boolean {
        val pdf = try { PdfFile.open(readPdf(ctx, uri), password) } catch (e: PdfPasswordException) { throw e } catch (e: OutOfMemoryError) { throw e } catch (_: Throwable) { null }
        if (pdf == null) { toWordOcr(ctx, uri, out, progress); return false }
        if (pdf.encrypted && password.isEmpty() && !pdf.canExtract)
            throw PdfPasswordException("PDF ini dibatasi pemiliknya (tidak boleh disalin/diubah). Masukkan password pemilik untuk mengubahnya ke Word.")
        val protectPw = if (pdf.encrypted && password.isNotEmpty()) password else null
        PdfLayout.jpegWithAlpha = { jpg, a, w, h -> jpegAlpha(jpg, a, w, h) }
        // PdfRenderer (untuk OCR halaman pindaian) tidak bisa membuka PDF terkunci → tanpa OCR
        val src = if (pdf.encrypted) null else try { Src(ctx, uri) } catch (_: Throwable) { null }
        try {
            val scan = src?.let { s ->
                object : PdfToDocx.Scan {
                    override fun ocr(pageIndex: Int): List<Block>? {
                        val rd = Pdf.render(s.r, pageIndex, ocrScale(s, pageIndex), 0, Perf.ocrCap())
                        try { val res = Ocr.run(rd.bmp); return if (res.conf < Ocr.LOW_CONF) null else res.blocks } finally { rd.bmp.recycle() }
                    }
                    override fun pageImage(pageIndex: Int): Triple<ByteArray, Int, Int>? {
                        val rd = Pdf.render(s.r, pageIndex, scale())
                        try { val sm = scaled(rd.bmp, Export.WORD_IMG_DIM); try { return Triple(jpeg(sm, Export.WORD_IMG_Q), sm.width, sm.height) } finally { if (sm !== rd.bmp) sm.recycle() } } finally { rd.bmp.recycle() }
                    }
                }
            }
            try { PdfToDocx(out, protectPw).convert(pdf, scan, progress) } catch (e: OutOfMemoryError) { out.delete(); throw e } catch (t: Throwable) {
                out.delete()
                // jalur OCR cadangan tidak bisa mengunci → hanya untuk PDF tak terkunci
                if (src != null && protectPw == null) toWordOcr(ctx, uri, out, progress) else throw t
            }
        } finally { try { src?.close() } catch (_: Throwable) {} }
        return protectPw != null
    }

    /** JPEG + kanal alfa (SMask) → PNG transparan. */
    private fun jpegAlpha(jpg: ByteArray, a: ByteArray, w: Int, h: Int): ByteArray? = try {
        val b = android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: null
        if (b == null) null else {
            val m = if (b.width == w && b.height == h) b.copy(Bitmap.Config.ARGB_8888, true) else Bitmap.createScaledBitmap(b, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            if (m !== b) b.recycle()
            val px = IntArray(w * h); m.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) px[i] = (px[i] and 0xFFFFFF) or ((a[i].toInt() and 0xFF) shl 24)
            m.setPixels(px, 0, w, 0, 0, w, h)
            ByteArrayOutputStream().also { m.compress(Bitmap.CompressFormat.PNG, 100, it); m.recycle() }.toByteArray()
        }
    } catch (_: Throwable) { null }

    // ------------------------------------------------------------------ Kunci AES-256 (tanpa render ulang)

    /** Kunci PDF dengan AES-256 TANPA mengubah halaman jadi gambar: teks tetap bisa dicari, ukuran tetap kecil. */
    fun lockLossless(ctx: Context, uri: Uri, out: File, userPw: String, ownerPw: String, perms: PdfSave.Perms) {
        val pdf = PdfFile.open(readPdf(ctx, uri))
        FileOutputStream(out).use { PdfSave.write(pdf, it.buffered(1 shl 16), PdfSave.Lock(userPw, ownerPw, perms)) }
    }

    /** PDF -> .docx lewat OCR per halaman (cadangan bila isi PDF tidak bisa dibaca langsung). */
    fun toWordOcr(ctx: Context, uri: Uri, out: File, progress: (Int, Int) -> Unit = { _, _ -> }) {
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
            is PageMarks.Stamp -> {
                val r = RectF(it.box.left * w, it.box.top * h, it.box.right * w, it.box.bottom * h)
                c.save()
                if (it.rot != 0f) c.rotate(it.rot, r.centerX(), r.centerY())
                c.drawBitmap(it.bmp, null, r, bmpPaint)
                c.restore()
            }
        }
    }

    /** Warnai ulang tanda tangan: bentuk (kanal alfa) tetap, warna tinta diganti [color]. */
    fun recolor(src: Bitmap, color: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val rgb = color and 0xFFFFFF
        for (i in px.indices) px[i] = (px[i] and 0xFF000000.toInt()) or rgb
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
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

    /**
     * Dialog interaktif visual untuk menyusun, memutar 90°, dan menghapus halaman PDF dengan thumbnail pratinjau.
     */
    fun showOrganizeSheet(
        activity: AppCompatActivity,
        uri: Uri,
        onDone: (File) -> Unit
    ) {
        val total = try { Pdf.pageCount(activity, uri) } catch (_: Throwable) {
            activity.toast("Gagal membaca dokumen PDF")
            return
        }
        val name = Files.nameOf(activity, uri)
        val pagesOrder = (0 until total).toMutableList()
        val rotations = mutableMapOf<Int, Int>()

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_crop, C.ACCENT, C.BLUE_BG,
                "Susun & Putar Halaman PDF", "$name • $total halaman", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val container = activity.vbox(8)
            val thumbCache = mutableMapOf<Int, Bitmap>()

            fun getThumb(origIdx: Int, rot: Int): Bitmap? {
                val key = origIdx * 1000 + rot
                thumbCache[key]?.let { return it }
                return try {
                    val pfd = activity.contentResolver.openFileDescriptor(uri, "r") ?: return null
                    val renderer = android.graphics.pdf.PdfRenderer(pfd)
                    val pg = renderer.openPage(origIdx)
                    val w = (pg.width * 0.32f).toInt().coerceAtLeast(100)
                    val h = (pg.height * 0.32f).toInt().coerceAtLeast(100)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pg.close()
                    renderer.close()
                    pfd.close()
                    val result = if (rot % 360 != 0) Pdf.orient(bmp, rot, false) else bmp
                    thumbCache[key] = result
                    result
                } catch (_: Throwable) { null }
            }

            fun renderList() {
                container.removeAllViews()

                if (pagesOrder.isEmpty()) {
                    container.add(activity.tv("Semua halaman telah dihapus", 14f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 20)
                    return
                }

                val grid = activity.hbox().apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val scroll = android.widget.HorizontalScrollView(activity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(grid)
                }

                pagesOrder.forEachIndexed { idx, origIdx ->
                    val rot = rotations[origIdx] ?: 0
                    val thumb = getThumb(origIdx, rot)

                    val card = FrameLayout(activity).apply {
                        background = activity.shape(C.SURFACE, 14, C.LINE, 1)
                        setPadding(activity.dp(6), activity.dp(6), activity.dp(6), activity.dp(6))
                    }

                    val iv = ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        if (thumb != null) setImageBitmap(thumb)
                        else setBackgroundColor(C.SURFACE2)
                    }
                    card.addView(iv, FrameLayout.LayoutParams(activity.dp(110), activity.dp(150)))

                    val badge = activity.tv("${idx + 1}", 11f, Color.WHITE, true).apply {
                        background = activity.shape(C.ACCENT, 100)
                        setPadding(activity.dp(6), activity.dp(2), activity.dp(6), activity.dp(2))
                    }
                    card.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))

                    val ctrlRow = activity.hbox().apply {
                        gravity = Gravity.CENTER
                    }

                    fun miniBtn(iconRes: Int, desc: String, onClick: () -> Unit): View = FrameLayout(activity).apply {
                        background = activity.shape(C.SURFACE2, 100)
                        isClickable = true
                        isFocusable = true
                        contentDescription = desc
                        addView(activity.icon(iconRes, C.TEXT, 14), FrameLayout.LayoutParams(activity.dp(14), activity.dp(14), Gravity.CENTER))
                        setOnClickListener { onClick() }
                    }

                    if (idx > 0) {
                        ctrlRow.add(miniBtn(R.drawable.ic_back, "Geser Kiri") {
                            pagesOrder.add(idx - 1, pagesOrder.removeAt(idx))
                            renderList()
                        }, w = activity.dp(28), h = activity.dp(28))
                    }

                    ctrlRow.add(miniBtn(R.drawable.ic_rotate, "Putar 90°") {
                        rotations[origIdx] = ((rotations[origIdx] ?: 0) + 90) % 360
                        renderList()
                    }, w = activity.dp(28), h = activity.dp(28), side = 2)

                    ctrlRow.add(miniBtn(R.drawable.ic_close, "Hapus") {
                        pagesOrder.removeAt(idx)
                        renderList()
                    }, w = activity.dp(28), h = activity.dp(28), side = 2)

                    if (idx < pagesOrder.size - 1) {
                        ctrlRow.add(miniBtn(R.drawable.ic_next, "Geser Kanan") {
                            pagesOrder.add(idx + 1, pagesOrder.removeAt(idx))
                            renderList()
                        }, w = activity.dp(28), h = activity.dp(28))
                    }

                    val itemBox = activity.vbox(0).apply {
                        add(card)
                        add(ctrlRow, top = 6)
                    }

                    grid.addView(itemBox, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        if (idx > 0) marginStart = activity.dp(10)
                    })
                }

                container.add(scroll, top = 8)
            }

            renderList()
            pad.add(container)

            val actionRow = activity.hbox().apply {
                add(activity.btn("Putar Semua 90°", false, C.ACCENT) {
                    pagesOrder.forEach { origIdx ->
                        rotations[origIdx] = ((rotations[origIdx] ?: 0) + 90) % 360
                    }
                    renderList()
                }, w = 0, weight = 1f)

                add(activity.btn("Simpan PDF Baru", true, C.ACCENT) {
                    if (pagesOrder.isEmpty()) {
                        activity.toast("Semua halaman telah dihapus")
                        return@btn
                    }
                    dismiss()
                    val out = Files.newOut(activity, "susun_" + Files.baseName(name), "pdf")
                    activity.work("Menyusun PDF baru…", {
                        Pdf.organizePdf(activity, uri, pagesOrder, rotations, out)
                    }) { r ->
                        r.onSuccess { onDone(out) }.onFailure { activity.fail(it) }
                    }
                }, w = 0, weight = 1f, side = 8)
            }
            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }
}
