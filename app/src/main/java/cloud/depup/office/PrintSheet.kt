package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cetak khas DepUp. Layar cetak Android (Print Spooler) milik sistem — aplikasi tidak bisa mengubah tampilannya — dan
 * Depup sengaja tanpa izin jaringan (offline), jadi tidak mengirim sendiri ke printer Wi-Fi. Maka semua pengaturan
 * dibuat di lembar DepUp ([printSheet]): pratinjau, halaman, salinan, ukuran kertas, warna, bolak-balik. Layar Android
 * tinggal untuk memilih printer lalu menekan Cetak: kertas, warna & bolak-balik sudah terisi, dan PDF yang diserahkan
 * hanya berisi halaman & salinan pilihan ([PrintPages.build] — tanpa render ulang, jadi teks tetap tajam).
 */
object PrintKit {
    class Paper(val label: String, val size: String, val media: PrintAttributes.MediaSize, val wPt: Float, val hPt: Float)

    /** Kertas yang ditawarkan. F4 = 8,5 × 13 in: ukuran "F4 / Folio" yang dikenal printer di Indonesia. */
    val PAPERS = listOf(
        Paper("A4", "210 × 297 mm", PrintAttributes.MediaSize.ISO_A4, 595.3f, 841.9f),
        Paper("F4", "215 × 330 mm", PrintAttributes.MediaSize("na_foolscap_8.5x13in", "F4 / Folio", 8500, 13000), 612f, 936f),
        Paper("Letter", "216 × 279 mm", PrintAttributes.MediaSize.NA_LETTER, 612f, 792f),
        Paper("Legal", "216 × 356 mm", PrintAttributes.MediaSize.NA_LEGAL, 612f, 1008f),
        Paper("A5", "148 × 210 mm", PrintAttributes.MediaSize.ISO_A5, 419.5f, 595.3f)
    )

    /** Kertas yang ukurannya cocok dengan halaman [wPt] × [hPt] (pt, arah bebas), atau -1. */
    fun matchPaper(wPt: Float, hPt: Float): Int {
        val s = min(wPt, hPt); val l = max(wPt, hPt)
        return PAPERS.indexOfFirst { abs(it.wPt - s) <= 12f && abs(it.hPt - l) <= 12f }
    }

    /** Folder sementara bahan & tugas cetak (cache aplikasi; sisa lebih dari 6 jam dibuang). */
    fun dir(ctx: Context): File = File(ctx.cacheDir, "cetak").apply {
        mkdirs()
        val old = System.currentTimeMillis() - 6 * 3600_000L
        listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
    }

    fun newFile(ctx: Context, tag: String): File = File(dir(ctx), "${tag}_${System.nanoTime()}.pdf")

    /** Hapus [f] hanya bila berkas sementara milik folder cetak (berkas pengguna tidak pernah disentuh). */
    fun dropTemp(ctx: Context, f: File) {
        if (f.parentFile?.canonicalPath == File(ctx.cacheDir, "cetak").canonicalPath) f.delete()
    }

    fun attrs(paper: Paper, landscape: Boolean, color: Boolean, duplex: Boolean): PrintAttributes =
        PrintAttributes.Builder()
            .setMediaSize(if (landscape) paper.media.asLandscape() else paper.media.asPortrait())
            .setColorMode(if (color) PrintAttributes.COLOR_MODE_COLOR else PrintAttributes.COLOR_MODE_MONOCHROME)
            .setDuplexMode(when {
                !duplex -> PrintAttributes.DUPLEX_MODE_NONE
                landscape -> PrintAttributes.DUPLEX_MODE_SHORT_EDGE   // dibalik ke atas, seperti kalender meja
                else -> PrintAttributes.DUPLEX_MODE_LONG_EDGE         // dibalik ke samping, seperti buku
            })
            .build()

    /**
     * Menyerahkan PDF jadi [file] apa adanya ke layanan cetak. Layout tidak bergantung pengaturan layar printer, jadi
     * isi hanya ditulis sekali. [done] dipanggil setelah layar printer ditutup (membuang berkas sementara).
     */
    class FileAdapter(private val file: File, private val name: String, private val count: Int, private val done: () -> Unit) : PrintDocumentAdapter() {
        private var laidOut = false

        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cs: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cs?.isCanceled == true) { cb.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(if (count > 0) count else PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            cb.onLayoutFinished(info, !laidOut)
            laidOut = true
        }

        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cs: CancellationSignal?, cb: WriteResultCallback) {
            // dipanggil di thread utama: salin di latar supaya PDF besar tidak membekukan layar
            Thread {
                try {
                    FileInputStream(file).use { i -> ParcelFileDescriptor.AutoCloseOutputStream(dest).use { o -> i.copyTo(o, 1 shl 16) } }
                    if (cs?.isCanceled == true) cb.onWriteCancelled() else cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Throwable) { cb.onWriteFailed(e.message) }
            }.start()
        }

        override fun onFinish() = done()
    }

    /** Ringkasan PDF untuk lembar cetak: jumlah halaman & ukuran halaman pertama (pt). */
    class Info(val count: Int, val wPt: Float, val hPt: Float) {
        val landscape get() = wPt > hPt * 1.02f
    }

    fun info(file: File): Info? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount <= 0) null
                else r.openPage(0).use { p -> Info(r.pageCount, p.width.toFloat(), p.height.toFloat()) }
            }
        }
    } catch (_: Throwable) {
        // PdfRenderer menolak (mis. rusak ringan): coba pembaca PDF sendiri
        try {
            val pages = PdfFile.open(file.readBytes()).pages()
            if (pages.isEmpty()) null else {
                val b = pages[0].box
                val w = b[2] - b[0]; val h = b[3] - b[1]
                if (pages[0].rotate % 180 != 0) Info(pages.size, h, w) else Info(pages.size, w, h)
            }
        } catch (_: Throwable) { null }
    }
}

/**
 * Perender gambar mini halaman di SATU thread (PdfRenderer tidak boleh dipakai bersamaan). [onReady] dipanggil di
 * thread perender.
 */
private class ThumbLoader(private val file: File, private val maxW: Int, private val maxH: Int, private val onReady: (Int, Bitmap) -> Unit) {
    private val exec = Executors.newSingleThreadExecutor()
    private var pfd: ParcelFileDescriptor? = null
    private var r: PdfRenderer? = null
    private val asked = HashSet<Int>()
    @Volatile private var closed = false

    fun want(i: Int) {
        if (closed || !asked.add(i)) return
        exec.execute {
            if (closed) return@execute
            try {
                val rr = r ?: PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { pfd = it }).also { r = it }
                if (i >= rr.pageCount) return@execute
                rr.openPage(i).use { p ->
                    val s = min(maxW.toFloat() / p.width, maxH.toFloat() / p.height)
                    val b = Bitmap.createBitmap(max(1, (p.width * s).roundToInt()), max(1, (p.height * s).roundToInt()), Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)   // halaman PDF bisa transparan
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if (!closed) onReady(i, b) else b.recycle()
                }
            } catch (_: Throwable) {}
        }
    }

    fun close() {
        closed = true
        exec.execute {
            try { r?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
        exec.shutdown()
    }
}

/** PDF dari penyedia mana pun: disalin dulu ke cache (latar) supaya bisa dipratinjau & disusun, lalu [printSheet]. */
fun AppCompatActivity.printPdfUri(uri: Uri, name: String) {
    if (uri.scheme == "file") uri.path?.let { printSheet(File(it), name); return }
    work("Membuka dokumen…", {
        val out = PrintKit.newFile(this, "sumber")
        contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(out).use { o -> i.copyTo(o, 1 shl 16) } }
        out
    }) { r ->
        r.onSuccess { printSheet(it, name) }.onFailure { toast("Dokumen tidak bisa dibuka untuk dicetak") }
    }
}

/**
 * Dokumen yang tampil di WebView (Word, Excel, PowerPoint, editor): ditulis jadi PDF dulu tanpa dialog sistem
 * ([PdfExport]), lalu [printSheet]. Gagal → [fallback] (layar cetak Android langsung).
 */
fun AppCompatActivity.printWebView(wv: WebView, name: String, media: PrintAttributes.MediaSize = PdfExport.mediaOf(wv), fallback: () -> Unit) {
    val d = busy("Menyiapkan cetak…")
    PdfExport.write(this, wv, name, media, to = PrintKit.newFile(this, "dok")) { f ->
        try { d.dismiss() } catch (_: Exception) {}
        if (f != null) printSheet(f, name) else fallback()
    }
}

/** Lembar "Cetak" DepUp untuk PDF lokal [file]. */
fun AppCompatActivity.printSheet(file: File, name: String) {
    Thread {
        val info = PrintKit.info(file)
        runOnUiThread { if (!isFinishing && !isDestroyed) showPrintSheet(file, name, info) }
    }.start()
}

private const val MAX_THUMBS = 60

private fun AppCompatActivity.showPrintSheet(file: File, name: String, info: PrintKit.Info?) {
    val n = info?.count ?: 0
    val landscape = info?.landscape ?: false
    val docPaper = info?.let { PrintKit.matchPaper(it.wPt, it.hPt) } ?: -1
    var allPages = true
    val sel = sortedSetOf<Int>().apply { for (i in 0 until n) add(i) }
    var rangesOk = true
    var copies = 1
    var paper = if (docPaper >= 0) docPaper else 0
    var color = true
    var duplex = false
    var started = false
    var loader: ThumbLoader? = null

    val dlg = sheet { body, dismiss ->
        val sub = listOf(name, if (n > 0) "$n halaman" else "").filter { it.isNotBlank() }.joinToString(" · ")
        sheetHeader(body, R.drawable.ic_print, C.ACCENT, C.BLUE_BG, "Cetak", sub, dismiss)

        // ---------- pratinjau halaman
        val ratio = if (info != null && info.hPt > 0f) info.wPt / info.hPt else 0.707f
        val th = dp(if (ratio > 1f) 104 else 136)
        val tw = (th * ratio).roundToInt().coerceIn(dp(64), dp(220))
        val gray = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val shown = min(n, MAX_THUMBS)
        val thumbs = ArrayList<ImageView>(); val marks = ArrayList<ImageView>(); val cells = ArrayList<View>()
        lateinit var paintSel: () -> Unit
        lateinit var onThumbTap: (Int) -> Unit
        val stripScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        if (shown > 0) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(20), dp(2), dp(20), dp(6))
                clipChildren = false; clipToPadding = false
            }
            for (i in 0 until shown) {
                val cell = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
                val frame = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = shape(Color.WHITE, 8, C.LINE, 1)
                    elevation = dp(2).toFloat()
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                frame.addView(iv, FrameLayout.LayoutParams(tw, th))
                val mk = icon(R.drawable.ic_check_ok, C.ACCENT, 22).apply {
                    background = shape(C.SURFACE, 100)
                    elevation = dp(3).toFloat()
                    visibility = View.GONE
                }
                frame.addView(mk, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
                cell.add(frame, w = tw, h = th)
                cell.add(tv("${i + 1}", 12f, C.MUTED, true).apply { gravity = Gravity.CENTER }, top = 6, w = WRAP)
                if (n > 1) cell.tappable("Halaman ${i + 1}") { onThumbTap(i) } else cell.contentDescription = "Halaman ${i + 1}"
                thumbs.add(iv); marks.add(mk); cells.add(cell)
                strip.add(cell, w = WRAP, side = 0)
                if (i > 0) (cell.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            }
            if (n > shown) {
                val more = vbox().apply {
                    gravity = Gravity.CENTER
                    background = shape(C.SURFACE2, 8)
                    contentDescription = "${n - shown} halaman lagi — pilih lewat nomor halaman"
                    add(tv("+${n - shown}", 16f, C.TEXT, true).apply { gravity = Gravity.CENTER }, w = WRAP)
                    add(tv("halaman", 12f, C.MUTED).apply { gravity = Gravity.CENTER }, w = WRAP)
                }
                strip.add(more, w = tw, h = th)
                (more.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            }
            stripScroll.addView(strip)
            body.add(stripScroll)
            // gambar mini dimuat saat terlihat (dokumen panjang tidak dirender sekaligus)
            val pxW = min(tw, dp(150)); val pxH = (pxW / ratio).roundToInt().coerceAtLeast(1)
            val ld = ThumbLoader(file, pxW, pxH) { i, b -> runOnUiThread { thumbs.getOrNull(i)?.setImageBitmap(b) ?: b.recycle() } }
            loader = ld
            fun loadVisible() {
                val step = tw + dp(10)
                val first = ((stripScroll.scrollX - dp(20)) / step).coerceAtLeast(0)
                val last = min(shown - 1, (stripScroll.scrollX + stripScroll.width) / step + 2)
                for (i in first..last) ld.want(i)
            }
            stripScroll.setOnScrollChangeListener { _, _, _, _, _ -> loadVisible() }
            stripScroll.post { loadVisible() }
        }

        val pad = vbox().apply { setPadding(dp(20), dp(10), dp(20), dp(4)) }
        lateinit var summary: TextView
        lateinit var go: View
        fun refresh() {
            val count = if (allPages || n == 0) n else sel.size
            val parts = ArrayList<String>()
            parts.add(if (n == 0) "Semua halaman" else "$count halaman")
            if (copies > 1) parts.add("$copies salinan")
            parts.add(PrintKit.PAPERS[paper].label)
            parts.add(if (color) "Berwarna" else "Hitam-putih")
            if (duplex) parts.add("Bolak-balik")
            summary.text = parts.joinToString(" · ")
            val ok = allPages || n == 0 || (rangesOk && sel.isNotEmpty())
            go.isEnabled = ok; go.alpha = if (ok) 1f else 0.45f
        }

        // ---------- halaman
        if (n > 1) {
            pad.add(tv("Halaman", 14f, C.TEXT, true))
            val rangeBox = vbox().apply { visibility = View.GONE }
            val rangeField = field("Contoh: 1-3, 5").apply {
                // hanya angka, koma, tanda hubung & spasi; papan tombol telepon (angka besar + "-" & ",")
                keyListener = DigitsKeyListener.getInstance("0123456789,- ")
                setRawInputType(InputType.TYPE_CLASS_PHONE)
                contentDescription = "Nomor halaman yang dicetak"
            }
            val err = tv("Tulis nomor halaman 1–$n, mis. 1-3, 5", 12f, C.ERROR).apply { visibility = View.GONE }
            var syncing = false
            paintSel = {
                for (i in thumbs.indices) {
                    val on = allPages || i in sel
                    thumbs[i].alpha = if (on) 1f else 0.35f
                    marks[i].visibility = if (!allPages && on) View.VISIBLE else View.GONE
                    cells[i].contentDescription = "Halaman ${i + 1}" + if (!allPages) (if (on) ", dipilih" else ", tidak dipilih") else ""
                }
            }
            fun setSel(pages: Collection<Int>) {
                sel.clear(); sel.addAll(pages)
                syncing = true
                rangeField.setText(PrintPages.text(sel)); rangeField.setSelection(rangeField.text.length)
                syncing = false
                rangesOk = sel.isNotEmpty(); err.visibility = View.GONE
                paintSel(); refresh()
            }
            rangeField.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (syncing) return
                    val p = PrintPages.parse(s?.toString() ?: "", n)
                    rangesOk = p != null
                    err.visibility = if (p == null && !s.isNullOrBlank()) View.VISIBLE else View.GONE
                    if (p != null) { sel.clear(); sel.addAll(p) }
                    paintSel(); refresh()
                }
            })
            lateinit var seg: LinearLayout
            seg = segmented(listOf("Semua ($n)", "Pilih halaman"), 0) { i ->
                allPages = i == 0
                rangeBox.visibility = if (allPages) View.GONE else View.VISIBLE
                // masuk "Pilih halaman" pertama kali (atau isian masih salah): mulai dari semua halaman terpilih
                if (!allPages && (rangeField.text.isNullOrBlank() || !rangesOk)) setSel((0 until n).toList())
                paintSel(); refresh()
            }
            onThumbTap = { i ->
                if (allPages) {
                    // ketuk satu halaman saat "Semua" = cetak halaman itu saja
                    (seg as? SegmentedBar)?.select(1)
                    setSel(listOf(i))
                } else setSel(if (i in sel) sel - i else sel + i)
                thumbs.getOrNull(i)?.boing(0.92f)
            }
            pad.add(seg, top = 8)
            rangeBox.add(rangeField, top = 10)
            rangeBox.add(err, top = 4)
            rangeBox.add(tv("Ketuk gambar halaman untuk memilih atau membatalkan.", 12f, C.MUTED), top = 6)
            pad.add(rangeBox)
        } else {
            paintSel = {}
            onThumbTap = {}
        }

        // ---------- salinan
        val copyRow = hbox()
        copyRow.add(vbox().apply {
            add(tv("Salinan", 14f, C.TEXT, true))
            add(tv("Tersusun 1-2-3, 1-2-3", 12f, C.MUTED), top = 2)
        }, w = 0, weight = 1f)
        val count = tv("1", 17f, C.TEXT, true).apply { gravity = Gravity.CENTER; minWidth = dp(40) }
        lateinit var minus: ImageView
        lateinit var plus: ImageView
        fun paintCopies() {
            count.text = "$copies"
            count.contentDescription = "$copies salinan"
            minus.isEnabled = copies > 1; minus.alpha = if (copies > 1) 1f else 0.35f
            plus.isEnabled = copies < 99; plus.alpha = if (copies < 99) 1f else 0.35f
        }
        minus = iconBtn(R.drawable.ic_minus, "Kurangi salinan", C.TEXT, 20) { if (copies > 1) { copies--; paintCopies(); refresh(); count.boing(0.85f) } }
            .apply { background = rippleBg(100, C.SURFACE2) }
        plus = iconBtn(R.drawable.ic_plus, "Tambah salinan", C.TEXT, 20) { if (copies < 99) { copies++; paintCopies(); refresh(); count.boing(0.85f) } }
            .apply { background = rippleBg(100, C.SURFACE2) }
        paintCopies()
        copyRow.add(minus, w = dp(44), h = dp(44))
        copyRow.add(count, w = WRAP, side = 6)
        copyRow.add(plus, w = dp(44), h = dp(44))
        pad.add(copyRow, top = if (n > 1) 20 else 4)

        // ---------- ukuran kertas
        pad.add(tv("Ukuran kertas", 14f, C.TEXT, true), top = 20)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val paperNote = tv("", 12f, C.MUTED)
        fun paintPaper() {
            chips.removeAllViews()
            PrintKit.PAPERS.forEachIndexed { i, p ->
                chips.add(filterChip(p.label, i == paper) {
                    if (paper != i) { paper = i; paintPaper(); refresh() }
                }, w = WRAP, side = 0)
                if (i > 0) (chips.getChildAt(i).layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            }
            val p = PrintKit.PAPERS[paper]
            paperNote.text = p.size + when {
                paper == docPaper -> " · sesuai ukuran dokumen"
                info != null && docPaper < 0 -> " · halaman diperkecil/diperbesar agar pas"
                else -> ""
            } + if (landscape) " · mendatar" else ""
        }
        paintPaper()
        pad.add(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false
            addView(chips)
        }, top = 8)
        pad.add(paperNote, top = 6)

        // ---------- warna
        pad.add(tv("Warna", 14f, C.TEXT, true), top = 20)
        pad.add(segmented(listOf("Berwarna", "Hitam-putih"), 0) { i ->
            color = i == 0
            thumbs.forEach { it.colorFilter = if (color) null else gray }
            refresh()
        }, top = 8)

        // ---------- sisi kertas
        pad.add(tv("Sisi kertas", 14f, C.TEXT, true), top = 20)
        pad.add(segmented(listOf("Satu sisi", "Bolak-balik"), 0) { i -> duplex = i == 1; refresh() }, top = 8)
        pad.add(tv("Printer yang tidak bisa bolak-balik otomatis akan mencetak satu sisi.", 12f, C.MUTED), top = 6)

        // ---------- langkah berikutnya
        val next = hbox().apply {
            background = shape(C.SURFACE2, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        next.add(icon(R.drawable.ic_print, C.MUTED, 18), w = dp(18), h = dp(18))
        next.add(tv("Berikutnya pilih printer di layar Android, lalu tekan Cetak. Pengaturan di atas sudah terisi.", 13f, C.MUTED), w = 0, weight = 1f, side = 10)
        pad.add(next, top = 20)
        summary = tv("", 13f, C.TEXT, true).apply { gravity = Gravity.CENTER }
        pad.add(summary, top = 14)
        body.add(pad)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(10), dp(20), dp(16))
        }
        bar.add(btn("Batal", false) { dismiss() }, w = WRAP)
        go = btn("Pilih printer", true, C.ACCENT, R.drawable.ic_print) {
            val pages = if (allPages || n == 0) null else sel.toList()
            if (pages != null && (pages.isEmpty() || !rangesOk)) return@btn
            started = true
            dismiss()
            startPrint(file, name, n, pages, copies, PrintKit.PAPERS[paper], landscape, color, duplex)
        }
        bar.add(go, w = 0, weight = 1f, side = 6)
        body.add(bar)
        paintSel()
        refresh()
    }
    dlg.setOnDismissListener {
        loader?.close()
        if (!started) PrintKit.dropTemp(this, file)
    }
}

/** Siapkan PDF tugas (halaman & salinan pilihan) lalu buka layar printer Android dengan pengaturan sudah terisi. */
private fun AppCompatActivity.startPrint(
    src: File, name: String, n: Int, pages: List<Int>?, copies: Int, paper: PrintKit.Paper,
    landscape: Boolean, color: Boolean, duplex: Boolean
) {
    val attrs = PrintKit.attrs(paper, landscape, color, duplex)
    val subset = pages != null && pages.size < n
    if (!subset && copies == 1) { handOff(src, src, name, n, attrs); return }
    val list = pages ?: (0 until n).toList()
    val out = PrintKit.newFile(this, "tugas")
    work("Menyiapkan dokumen cetak…", { PrintPages.build(src, list, copies, duplex, out) }) { r ->
        if (r.getOrDefault(false)) handOff(out, src, name, PrintPages.jobPages(list.size, copies, duplex), attrs)
        else {
            toast("Halaman & salinan untuk berkas ini diatur di layar printer")
            handOff(src, src, name, n, attrs)
        }
    }
}

private fun AppCompatActivity.handOff(job: File, src: File, name: String, count: Int, attrs: PrintAttributes) {
    val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
    val title = name.substringBeforeLast('.').ifBlank { "Dokumen" }
    val app = applicationContext
    val pj = try {
        pm.print(title, PrintKit.FileAdapter(job, title, count) {
            PrintKit.dropTemp(app, job)
            if (src != job) PrintKit.dropTemp(app, src)
        }, attrs)
    } catch (_: Throwable) {
        toast("Layanan cetak tidak tersedia di HP ini")
        PrintKit.dropTemp(this, job); if (src != job) PrintKit.dropTemp(this, src)
        return
    }
    // kembali dari layar printer: beri tahu bila tugas benar-benar dikirim (dibatalkan → diam)
    var left = false
    lifecycle.addObserver(object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_PAUSE -> left = true
                Lifecycle.Event.ON_RESUME -> if (left) {
                    lifecycle.removeObserver(this)
                    val msg = try {
                        when {
                            pj.isQueued || pj.isStarted -> "Dikirim ke printer"
                            pj.isCompleted -> "Tugas cetak selesai"
                            pj.isFailed -> "Printer gagal mencetak. Periksa printer lalu coba lagi."
                            else -> null
                        }
                    } catch (_: Throwable) { null }
                    if (msg != null) toast(msg)
                }
                Lifecycle.Event.ON_DESTROY -> lifecycle.removeObserver(this)
                else -> {}
            }
        }
    })
}
