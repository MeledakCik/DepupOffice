package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.abs

/** Pembungkus scroll horizontal + pinch-zoom + ketuk dua kali. Zoom memperbesar lebar halaman (tajam, bukan sekadar membesarkan gambar). */
class PinchScroll(ctx: Context) : HorizontalScrollView(ctx) {
    var target: View? = null
    var onCommit: ((factor: Float, focusX: Float) -> Unit)? = null
    var onDouble: ((focusX: Float) -> Unit)? = null
    private var live = 1f
    private var scaling = false

    private val sd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { live = 1f; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            live = (live * d.scaleFactor).coerceIn(0.5f, 3f)
            target?.let {
                it.pivotX = scrollX + d.focusX
                it.pivotY = d.focusY
                it.scaleX = live
                it.scaleY = live
            }
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            target?.let { it.scaleX = 1f; it.scaleY = 1f }
            val f = live
            live = 1f
            onCommit?.invoke(f, d.focusX)
        }
    })

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { onDouble?.invoke(e.x); return true }
    })

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) scaling = false
        gd.onTouchEvent(e)
        sd.onTouchEvent(e)
        if (sd.isInProgress) {
            if (!scaling) {
                scaling = true
                val c = MotionEvent.obtain(e)
                c.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(c)
                c.recycle()
            }
            return true
        }
        if (scaling) return true
        return super.dispatchTouchEvent(e)
    }
}

/** Pembaca PDF ringan: halaman dirender satu per satu saat di-scroll. Pinch / ketuk dua kali untuk zoom. */
class ViewerActivity : AppCompatActivity() {
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val lock = Any()
    private val exec = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<Int, Bitmap>(Perf.bitmapCacheBytes()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private var baseW = 1080
    private var zoom = 1f
    @Volatile private var gen = 0
    private val trimHook: () -> Unit = { runOnUiThread { cache.evictAll() } }
    private lateinit var rv: RecyclerView
    private lateinit var scroll: PinchScroll
    private val pageAdapter = PageAdapter()
    private var total = 0
    private var curPage = 0
    private var night = false
    private var marked = false
    private var uriStr = ""
    private var mimeStr = PDF
    private lateinit var counter: TextView
    private lateinit var moonBtn: ImageView
    private lateinit var markBtn: ImageView
    private lateinit var modeLabel: TextView
    private lateinit var progLabel: TextView
    private lateinit var progHolder: FrameLayout
    private lateinit var pageBadge: TextView
    private lateinit var zoomLabel: TextView
    private val invert = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rawUri = intent.data
        if (rawUri == null) { finish(); return }
        Perf.onTrim(trimHook)
        // Penyalinan ke cache bisa lama untuk berkas besar (mis. PDF 100 MB dari Drive/WhatsApp): jalankan di latar
        // dengan layar memuat, jangan di main thread (dulu bisa memicu "Aplikasi tidak merespons").
        setContentView(openingView(Files.nameOf(this, rawUri)))
        Thread {
            val uri = Files.cacheCopy(this, rawUri)
            runOnUiThread { if (!isFinishing && !isDestroyed) build(rawUri, uri) }
        }.start()
    }

    private fun openingView(name: String): View {
        val box = vbox().apply { gravity = Gravity.CENTER; setBackgroundColor(C.CANVAS); setPadding(dp(24), dp(24), dp(24), dp(24)) }
        box.add(android.widget.ProgressBar(this), w = dp(40), h = dp(40))
        box.add(tv("Membuka $name…", 14f, C.MUTED).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, top = 12)
        return box
    }

    private fun build(rawUri: android.net.Uri, uri: android.net.Uri) {

        val name = Files.nameOf(this, rawUri)   // nama berkas ASLI (bukan "open_xxx" dari salinan cache)
        val mime = intent.getStringExtra("mime") ?: Files.mimeOf(this, uri)
        mimeStr = mime
        paperTheme = PdfSettings.readerTheme(this)
        night = paperTheme == 1
        zoom = PdfSettings.defaultZoom(this).coerceIn(1f, 2.5f)

        baseW = resources.displayMetrics.widthPixels
        uriStr = uri.toString()

        var isPdfLoaded = false
        var isPasswordProtected = false
        try {
            pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                renderer = PdfRenderer(pfd!!)
                total = renderer!!.pageCount
                isPdfLoaded = true
            }
        } catch (e: SecurityException) {
            isPasswordProtected = true
            toast("Dokumen ini dilindungi kata sandi (Password Protected)")
        } catch (_: Exception) {}

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.CANVAS) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        bar.add(icon(R.drawable.ic_back, C.TEXT, 24).asButton("Kembali").apply {
            setOnClickListener { finish() }
        }, w = dp(48), h = dp(48))
        val tcol = vbox()
        val trow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        trow.add(tv(name, 16f, C.TEXT, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, w = WRAP)
        counter = pill(if (isPdfLoaded) "Hal 1 / $total" else "Dokumen", C.ACCENT, C.BLUE_BG, 10f)
        trow.add(counter, w = WRAP, side = 6)
        tcol.add(trow)
        tcol.add(tv("Depup Office Reader", 12f, C.MUTED))
        bar.add(tcol, w = 0, weight = 1f)
        moonBtn = icon(R.drawable.ic_moon, C.TEXT, 22).asButton("Mode baca malam").apply {
            setOnClickListener { toggleNight() }
        }
        bar.add(moonBtn, w = dp(48), h = dp(48))
        marked = Meta.isMarked(this, uriStr)
        markBtn = icon(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off, if (marked) C.ACCENT else C.TEXT, 22).asButton(if (marked) "Hapus tanda" else "Tandai dokumen").apply {
            setOnClickListener {
                contentDescription = if (!marked) "Hapus tanda" else "Tandai dokumen"
                marked = Meta.toggleMark(this@ViewerActivity, uriStr)
                setImageResource(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off)
                setColorFilter(if (marked) C.ACCENT else C.TEXT)
            }
        }
        bar.add(markBtn, w = dp(48), h = dp(48))
        val editBtn = icon(R.drawable.ic_sign, C.ACCENT, 22).asButton("Tanda tangan & edit PDF").apply {
            setOnClickListener { showPdfEditDialog(uri, name) }
        }
        bar.add(editBtn, w = dp(48), h = dp(48))

        bar.add(icon(R.drawable.ic_more, C.TEXT, 22).asButton("Opsi lain").apply {
            setOnClickListener { v ->
                val pm = android.widget.PopupMenu(this@ViewerActivity, v)
                pm.menu.add(0, 1, 0, "Bagikan")
                pm.menu.add(0, 2, 1, "🖨️ Cetak Dokumen")
                pm.menu.add(0, 8, 2, "🔊 Bacakan Suara (Voice Reader)")
                pm.menu.add(0, 3, 3, "Tanda Tangan & Edit PDF")
                pm.menu.add(0, 4, 4, "Ubah Nama Berkas")
                pm.menu.add(0, 5, 5, "Lompat Halaman")
                pm.menu.add(0, 6, 6, "Pengaturan Reader PDF")
                pm.menu.add(0, 7, 7, "Sedot Foto / Ekstrak Gambar")
                pm.setOnMenuItemClickListener { m ->
                    try {
                        when (m.itemId) {
                            1 -> Files.share(this@ViewerActivity, uri, mime, false)
                            2 -> PrintHelper.printPdf(this@ViewerActivity, uri, name)
                            8 -> startVoiceReaderForPdf(uri, name)
                            3 -> showPdfEditDialog(uri, name)
                            4 -> showRenameDialog(rawUri, uri, name)
                            5 -> showJumpPageDialog()
                            6 -> showPdfSettingsSheet()
                            else -> {
                                val base = Files.baseName(name)
                                work("Menyedot semua gambar dari PDF…", {
                                    Pdf.extractImagesFromPdf(this@ViewerActivity, uri, base)
                                }) { r ->
                                    r.onSuccess { count ->
                                        toast("✓ $count gambar diekstrak ke Pictures/Depup Office!")
                                    }.onFailure { fail(it) }
                                }
                            }
                        }
                    } catch (_: Exception) { toast("Tidak bisa dijalankan") }
                    true
                }
                pm.show()
            }
        }, w = dp(42), h = dp(40))
        root.add(bar)

        val stage = FrameLayout(this)

        if (isPdfLoaded) {
            val st = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            modeLabel = tv("● Mode Siang • 100%", 11f, C.TEXT, true).apply {
                background = shape(C.BLUE_BG, 100); setPadding(dp(12), dp(5), dp(12), dp(5))
            }
            st.add(modeLabel, w = WRAP)
            st.add(View(this), w = 0, h = dp(1), weight = 1f)
            progLabel = tv("0% selesai", 11f, C.MUTED, true)
            st.add(progLabel, w = WRAP)
            progHolder = FrameLayout(this)
            st.add(progHolder, w = dp(56), h = dp(6), side = 8)
            root.add(st)
            updateProgress(0)

            val lm = LinearLayoutManager(this)
            rv = RecyclerView(this).apply {
                layoutManager = lm
                adapter = pageAdapter
                setItemViewCacheSize(2)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                        val p = lm.findFirstVisibleItemPosition()
                        if (p >= 0) { curPage = p; counter.text = "Hal ${p + 1} / $total"; pageBadge.text = "${p + 1}"; updateProgress(p) }
                    }
                })
            }
            scroll = PinchScroll(this).apply {
                isHorizontalScrollBarEnabled = false
                target = rv
                onCommit = { f, fx -> setZoom(zoom * f, fx) }
                onDouble = { fx -> setZoom(if (zoom > 1.05f) 1f else 2f, fx) }
                addView(rv, FrameLayout.LayoutParams(baseW, MATCH))
            }
            stage.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

            val nav = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = raised(C.SURFACE, 24, 3f)
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            nav.add(icon(R.drawable.ic_up, C.MUTED, 22).asButton("Halaman sebelumnya").apply { setOnClickListener { goTo(curPage - 1) } }, w = dp(48), h = dp(48))
            pageBadge = tv("1", 14f, C.ON_PRIMARY, true).apply {
                gravity = Gravity.CENTER
                background = shape(C.ACCENT, 100)
                isClickable = true
                contentDescription = "Lompat ke halaman"
                setOnClickListener { showJumpPageDialog() }
            }
            nav.add(pageBadge, w = dp(44), h = dp(44), top = 2)
            nav.add(tv("/$total", 10f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 2)
            nav.add(icon(R.drawable.ic_down, C.MUTED, 22).asButton("Halaman berikutnya").apply { setOnClickListener { goTo(curPage + 1) } }, w = dp(48), h = dp(48), top = 2)
            stage.addView(nav, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })

            val tb = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = raised(C.SURFACE, 100, 3f)
                setPadding(dp(10), dp(6), dp(6), dp(6))
            }
            tb.add(icon(R.drawable.ic_zoom_out, C.TEXT, 22).asButton("Perkecil").apply {
                setOnClickListener { setZoom(zoom - 0.5f, baseW / 2f) }
            }, w = dp(48), h = dp(48))
            zoomLabel = tv("100%", 13f, C.TEXT, true).apply { gravity = Gravity.CENTER }
            tb.add(zoomLabel, w = dp(52))
            tb.add(icon(R.drawable.ic_zoom_in, C.TEXT, 22).asButton("Perbesar").apply {
                setOnClickListener { setZoom(zoom + 0.5f, baseW / 2f) }
            }, w = dp(48), h = dp(48))
            tb.add(icon(R.drawable.ic_print, C.TEXT, 22).asButton("Cetak").apply {
                setOnClickListener { try { Files.print(this@ViewerActivity, uri, name) } catch (_: Exception) { toast("Tidak bisa mencetak") } }
            }, w = dp(48), h = dp(48), side = 4)
            tb.add(FrameLayout(this).apply {
                contentDescription = "Bagikan"; isFocusable = true
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.ACCENT) }
                addView(icon(R.drawable.ic_upload, C.ON_PRIMARY, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                setOnClickListener { try { Files.share(this@ViewerActivity, uri, mime, false) } catch (_: Exception) { toast("Tidak bisa dibagikan") } }
            }, w = dp(44), h = dp(44), side = 4)
            stage.addView(tb, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        } else {
            renderNonPdf(uri, name, mime, stage)
        }

        root.add(stage, w = MATCH, h = 0, weight = 1f)
        setContentView(root)
    }

    private var officeWeb: android.webkit.WebView? = null

    /**
     * DOCX / XLSX / PPTX ditampilkan semirip mungkin dengan aslinya (halaman, font, tabel, gambar, slide) lewat
     * [OfficeView]. Bila gagal (berkas rusak / tidak lazim), kembali ke pratinjau teks sederhana.
     */
    private fun renderOffice(uri: android.net.Uri, name: String, mime: String, kind: String, stage: FrameLayout) {
        stage.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val strip = hbox().apply { setPadding(dp(8), dp(6), dp(8), dp(6)); setBackgroundColor(C.SURFACE) }
        val label = when (kind) { "docx" -> "Dokumen Word"; "xlsx" -> "Lembar kerja Excel"; else -> "Presentasi PowerPoint" }
        strip.add(tv(label, 12f, C.MUTED), w = 0, weight = 1f, side = 6)
        if (kind == "docx" || kind == "xlsx") {
            strip.add(btn(if (kind == "docx") "Edit Word" else "Edit Excel", false, C.ACCENT) {
                val i = if (kind == "docx") android.content.Intent(this, DocEditorActivity::class.java).putExtra(DocEditorActivity.EXTRA_URI, uri.toString())
                else android.content.Intent(this, SheetEditorActivity::class.java).putExtra(SheetEditorActivity.EXTRA_URI, uri.toString())
                startActivity(i); finish()
            }, w = WRAP)
        }
        val printBtn = btn("Cetak / PDF", true, C.ACCENT, R.drawable.ic_print) {
            officeWeb?.let { OfficeView.print(this, it, name) } ?: toast("Dokumen masih disiapkan")
        }
        strip.add(printBtn, w = WRAP, side = 4)
        col.add(strip)
        val holder = FrameLayout(this).apply { setBackgroundColor(C.CANVAS) }
        val loading = vbox().apply { gravity = Gravity.CENTER }
        loading.add(android.widget.ProgressBar(this), w = dp(40), h = dp(40))
        loading.add(tv("Menyiapkan tampilan dokumen…", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 10)
        holder.addView(loading, FrameLayout.LayoutParams(MATCH, MATCH))
        col.add(holder, h = 0, weight = 1f)
        stage.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        // tumpukan besar: dokumen bertingkat dalam (tabel di dalam tabel, grup shape) aman dari StackOverflow
        Thread(null, {
            val r = try { Result.success(OfficeView.render(this, uri, name, kind)) } catch (e: Throwable) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { doc ->
                    val wv = try { Result.success(OfficeView.webView(this, doc)) } catch (e: Throwable) { Result.failure(e) }
                    wv.onSuccess {
                        officeWeb = it
                        holder.removeAllViews()
                        holder.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
                    }.onFailure { e -> officeError(holder, uri, name, mime, stage, "Tampilan dokumen gagal dimuat", e) }
                }.onFailure { e -> officeError(holder, uri, name, mime, stage, "Berkas tidak bisa ditampilkan", e) }
            }
        }, "office-render", 64L * 1024 * 1024).start()
    }

    /** Kegagalan ditampilkan jelas (dengan nama error) — bukan diam-diam jatuh ke pratinjau teks yang tanpa tabel. */
    private fun officeError(holder: FrameLayout, uri: android.net.Uri, name: String, mime: String, stage: FrameLayout, title: String, e: Throwable) {
        holder.removeAllViews()
        val box = vbox().apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        box.add(tv(title, 16f, C.TEXT, true).apply { gravity = Gravity.CENTER })
        box.add(tv("${e.javaClass.simpleName}: ${e.message?.take(160) ?: "-"}", 12f, C.MUTED).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) }, top = 8)
        box.add(btn("Tampilkan teks saja", false, C.ACCENT) { renderNonPdf(uri, name, mime, stage, allowWeb = false) }, w = WRAP, top = 16)
        holder.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun renderNonPdf(uri: android.net.Uri, name: String, mime: String, stage: FrameLayout, allowWeb: Boolean = true) {
        val kind = OfficeView.kindOf(name, mime) ?: if (allowWeb && !mime.startsWith("image/") && mime != PDF) OfficeView.sniff(this, uri) else null
        if (allowWeb && (kind == "docx" || kind == "xlsx" || kind == "pptx")) { renderOffice(uri, name, mime, kind, stage); return }
        stage.removeAllViews()
        val scrollContent = android.widget.ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val paper = card(16, 16).apply {
            setBackgroundColor(if (night) 0xFF14161D.toInt() else C.SURFACE)
        }

        when {
            mime == PDF || name.endsWith(".pdf") -> {
                val banner = card(16, 16).apply {
                    background = shape(C.RED_BG, 16, C.RED, 1)
                }
                banner.add(tv("Gagal Membuka PDF", 18f, C.RED, true))
                banner.add(tv("File PDF ini dilindungi kata sandi atau mengalami kerusakan sehingga tidak dapat dibuka oleh pembaca.", 13f, C.TEXT), top = 6)
                banner.add(btn("Tutup Pembaca", true, C.RED) { finish() }, top = 16)
                paper.add(banner)
            }
            mime.startsWith("image/") || name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") -> {
                val bmp = try { Pdf.decode(this, uri, if (Perf.low) 1600 else 2400) } catch (_: Exception) { null }
                if (bmp != null) {
                    val iv = ImageView(this).apply {
                        adjustViewBounds = true
                        setImageBitmap(bmp)
                    }
                    paper.add(iv, w = MATCH, h = WRAP)
                } else {
                    paper.add(tv("Tidak dapat memuat gambar", 14f, C.MUTED))
                }
            }
            mime.contains("sheet") || name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".csv") -> {
                // Image 5 Spreadsheet View
                val banner = card(12, 12).apply {
                    background = shape(C.BLUE_BG, 12, C.ACCENT, 1)
                }
                val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                bRow.add(iconBadge(R.drawable.ic_sheet, C.TEAL, C.SURFACE, 100, 20), w = dp(38), h = dp(38))
                val bCol = vbox()
                bCol.add(tv("Konversi Cepat Depup", 14f, C.TEXT, true))
                bCol.add(tv("Ekspor spreadsheet ini menjadi file PDF rapi siap cetak", 11f, C.MUTED), top = 2)
                bRow.add(bCol, w = 0, weight = 1f, side = 8)
                bRow.add(tv("Edit Excel", 11f, C.ON_PRIMARY, true).apply {
                    background = shape(C.TEAL, 8)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    setOnClickListener {
                        startActivity(android.content.Intent(this@ViewerActivity, SheetEditorActivity::class.java)
                            .putExtra(SheetEditorActivity.EXTRA_URI, uri.toString()))
                        finish()
                    }
                }, w = WRAP)
                banner.add(bRow)
                paper.add(banner)

                val sheet = try {
                    if (name.endsWith(".csv", ignoreCase = true) || mime == "text/csv") OfficeReader.readCsv(this, uri)
                    else OfficeReader.readXlsx(this, uri)
                } catch (_: Exception) {
                    null
                }
                val gridData = (sheet?.rows ?: emptyList()).take(60)
                val cols = (gridData.maxOfOrNull { it.size } ?: 0).coerceIn(1, 8)
                val boldRows = sheet?.boldRows ?: setOf(0)

                if (gridData.isEmpty()) {
                    paper.add(tv("Isi spreadsheet tidak dapat dibaca atau masih kosong.", 13f, C.MUTED), top = 16)
                } else {
                    val sheetLabel = sheet?.name?.takeIf { it.isNotBlank() } ?: "Sheet1"
                    paper.add(tv("Sheet: $sheetLabel • ${gridData.size} baris", 12f, C.MUTED), top = 10)

                    val tableBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

                    val colHead = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        background = shape(C.LINE, 6)
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                    }
                    colHead.add(tv("#", 11f, C.MUTED, true), w = dp(28))
                    for (c in 0 until cols) {
                        colHead.add(tv(XlsxWriter.columnName(c), 11f, C.MUTED, true).apply { gravity = Gravity.CENTER }, w = 0, weight = 1f)
                    }
                    tableBox.add(colHead, top = 8)

                    gridData.forEachIndexed { rowIdx, rowCells ->
                        val header = rowIdx in boldRows
                        val rowView = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = shape(
                                if (header) C.BLUE_BG else (if (rowIdx % 2 == 0) C.SURFACE2 else C.SURFACE),
                                6
                            )
                            setPadding(dp(6), dp(8), dp(6), dp(8))
                        }
                        rowView.add(tv("${rowIdx + 1}", 11f, C.MUTED, true), w = dp(28))
                        for (c in 0 until cols) {
                            rowView.add(tv(rowCells.getOrNull(c).orEmpty().trim(), 12f, if (header) C.ACCENT else C.TEXT, header).apply {
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            }, w = 0, weight = 1f, side = 4)
                        }
                        tableBox.add(rowView, top = 2)
                    }
                    paper.add(tableBox, top = 6)
                }
            }
            kind == "legacy" || kind == "pptx" -> {
                val banner = card(16, 16).apply { background = shape(C.SURFACE2, 16, C.LINE, 1) }
                banner.add(tv(if (kind == "legacy") "Format lama belum didukung" else "Presentasi tidak dapat dibuka", 18f, C.TEXT, true))
                banner.add(tv(if (kind == "legacy") "Berkas .doc / .ppt (format Office 97–2003) belum bisa ditampilkan di pembaca ini. Buka di Word/PowerPoint lalu simpan ulang sebagai .docx / .pptx."
                    else "Berkas ini rusak atau dilindungi kata sandi.", 13f, C.MUTED), top = 6)
                banner.add(btn("Bagikan ke aplikasi lain", true, C.ACCENT) { try { Files.share(this, uri, mime, false) } catch (_: Exception) {} }, top = 16)
                paper.add(banner)
            }
            mime.startsWith("text/") || name.endsWith(".txt", ignoreCase = true) || name.endsWith(".md", ignoreCase = true) -> {
                // Satu TextView dengan teks berukuran MB membuat tata letak membeku; tampilkan bagian awal saja.
                val limit = if (Perf.low) 150_000 else 400_000
                var cut = false
                val content = try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                        val buf = CharArray(limit)
                        var n = 0
                        while (n < limit) { val k = r.read(buf, n, limit - n); if (k < 0) break; n += k }
                        cut = n == limit && r.read() >= 0
                        String(buf, 0, n)
                    }.orEmpty()
                } catch (_: Exception) {
                    ""
                }
                val banner = card(12, 12).apply {
                    background = shape(C.SURFACE2, 12, C.LINE, 1)
                }
                val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                bRow.add(iconBadge(R.drawable.ic_note, C.NAVY, C.INDIGO_BG, 100, 20), w = dp(38), h = dp(38))
                val bCol = vbox()
                bCol.add(tv("Pratinjau Teks", 14f, C.TEXT, true))
                bCol.add(tv(if (cut) "Menampilkan ${content.length} karakter pertama (berkas sangat besar)" else "${content.length} karakter • teks bisa disalin", 11f, C.MUTED), top = 2)
                bRow.add(bCol, w = 0, weight = 1f, side = 8)
                banner.add(bRow)
                paper.add(banner)
                if (content.isBlank()) {
                    paper.add(tv("File teks ini kosong atau tidak bisa dibaca.", 13f, C.MUTED), top = 16)
                } else {
                    paper.add(TextView(this).apply {
                        text = content.take(200_000)
                        textSize = 13f
                        setTextColor(if (night) C.ON_PRIMARY else C.TEXT)
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                        setLineSpacing(dp(3).toFloat(), 1.15f)
                    }, top = 16)
                }
            }
            else -> {
                // Pratinjau isi .docx asli (bukan contoh).
                val paragraphs = try {
                    OfficeReader.readDocx(this, uri)
                } catch (_: Exception) {
                    emptyList<OfficeReader.Paragraph>()
                }
                val banner = card(12, 12).apply {
                    background = shape(C.BLUE_BG, 12, C.ACCENT, 1)
                }
                val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                bRow.add(iconBadge(R.drawable.ic_doc, C.ACCENT, C.SURFACE, 100, 20), w = dp(38), h = dp(38))
                val bCol = vbox()
                bCol.add(tv("Pratinjau Isi Dokumen Word", 14f, C.TEXT, true))
                bCol.add(tv("${paragraphs.size} paragraf terbaca • ketuk Edit Word untuk mengubah teks", 11f, C.MUTED), top = 2)
                bRow.add(bCol, w = 0, weight = 1f, side = 8)
                bRow.add(tv("Edit Word", 11f, C.ON_PRIMARY, true).apply {
                    background = shape(C.ACCENT, 8)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    setOnClickListener {
                        startActivity(android.content.Intent(this@ViewerActivity, DocEditorActivity::class.java)
                            .putExtra(DocEditorActivity.EXTRA_URI, uri.toString()))
                        finish()
                    }
                }, w = WRAP)
                banner.add(bRow)
                paper.add(banner)

                if (paragraphs.isEmpty()) {
                    paper.add(tv("Dokumen ini tidak memiliki teks yang bisa ditampilkan (mungkin hanya berisi gambar).", 13f, C.MUTED), top = 16)
                } else {
                    paragraphs.take(300).forEachIndexed { i, p ->
                        val tv = TextView(this).apply {
                            text = p.text
                            textSize = if (i == 0) 17f else 13f
                            setTextColor(if (night) C.ON_PRIMARY else C.TEXT)
                            typeface = when {
                                p.bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                                p.italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                                i == 0 -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                                else -> Typeface.DEFAULT
                            }
                            gravity = when (p.align) {
                                1 -> Gravity.CENTER_HORIZONTAL
                                2 -> Gravity.END
                                else -> Gravity.START
                            }
                            setLineSpacing(dp(3).toFloat(), 1.15f)
                        }
                        paper.add(tv, top = if (i == 0) 16 else 10)
                    }
                }

                paper.add(btn("Jadikan PDF", true, C.ACCENT, R.drawable.ic_pdf) {
                    if (paragraphs.isEmpty()) {
                        toast("Tidak ada teks untuk dikonversi")
                    } else {
                        val base = name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _.-]+"), "_")
                        val out = Files.namedOut(this, base, "pdf")
                        val text = paragraphs.joinToString("\n\n") { it.text }
                        work("Mengonversi Word ke PDF…", { Pdf.text(text, out) }) { r ->
                            r.onSuccess {
                                val newUri = Files.uriFor(this, out)
                                Recent.add(this, newUri, out.name, PDF)
                                toast("PDF dibuat: ${out.name}")
                                Files.open(this, newUri, PDF)
                            }.onFailure { fail(it) }
                        }
                    }
                }, top = 18)
            }
        }

        scrollContent.addView(paper)
        stage.addView(scrollContent, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun goTo(p: Int) {
        val n = p.coerceIn(0, total - 1)
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(n, 0)
    }

    private fun showJumpPageDialog() {
        val et = field("Nomor halaman (1-$total)", number = true).apply { setText("${curPage + 1}") }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Lompat ke Halaman")
            .setView(vbox(16).apply { add(et) })
            .setPositiveButton("Lompat") { _, _ ->
                val p = et.text.toString().toIntOrNull()
                if (p != null && p in 1..total) {
                    goTo(p - 1)
                } else toast("Nomor halaman tidak valid")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showRenameDialog(origin: android.net.Uri, copySource: android.net.Uri, currentName: String) =
        renameFlow(origin, currentName, copySource) { r ->
            Files.open(this, r.uri, mimeStr)
            finish()
        }

    private fun showPdfEditDialog(currentUri: android.net.Uri, currentName: String) {
        val pad = SignatureView(this)
        val box = vbox(16).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.add(tv("Gambar Tanda Tangan pada Halaman ${curPage + 1}", 14f, C.TEXT, true))
        box.add(pad, top = 8, h = dp(160))
        box.add(btn("Bersihkan Goresan", false) { pad.clear() }, top = 8)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Tanda Tangan Digital PDF")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Tempel ke PDF") { _, _ ->
                val sigBmp = pad.toBitmap()
                if (sigBmp == null) { toast("Tanda tangan masih kosong"); return@setPositiveButton }
                val out = Files.namedOut(this, "TTD_" + Files.baseName(currentName), "pdf")
                work("Menempel Tanda Tangan ke PDF…", {
                    Pdf.sign(this, currentUri, sigBmp, curPage + 1, 1, out)
                }) { res ->
                    res.onSuccess {
                        val newUri = Files.uriFor(this, out)
                        Recent.add(this, newUri, out.name, PDF)
                        toast("Tanda tangan ditempel di sdcard/DepupOffice!")
                        Files.open(this, newUri, PDF)
                        finish()
                    }.onFailure { fail(it) }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showPdfSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_pdf, C.ACCENT, C.BLUE_BG, "Pengaturan Reader PDF", "Preset zoom, tema, dan preferensi tampilan", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }

        pad.add(tv("Preset Zoom Standar", 14f, C.TEXT, true))
        val zooms = listOf("100%", "125%", "150%", "200%")
        val zoomVal = listOf(1.0f, 1.25f, 1.5f, 2.0f)
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zooms.forEachIndexed { idx, zName ->
            val isSel = kotlin.math.abs(zoomVal[idx] - zoom) < 0.05f
            val btn = tv(zName, 12f, if (isSel) C.ON_PRIMARY else C.TEXT, true).apply {
                background = shape(if (isSel) C.ACCENT else C.SURFACE2, 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    setZoom(zoomVal[idx], baseW / 2f)
                    PdfSettings.setDefaultZoom(this@ViewerActivity, zoomVal[idx])
                    dismiss()
                }
            }
            zoomRow.add(btn, w = 0, weight = 1f, side = 4)
        }
        pad.add(zoomRow, top = 8)

        pad.add(tv("Tema Kertas & Pelindung Mata (Eye-Care)", 14f, C.TEXT, true), top = 16)
        val themes = listOf("Siang", "Malam", "Sepia", "Eye-Care")
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        themes.forEachIndexed { idx, tName ->
            val isSel = paperTheme == idx
            val btn = tv(tName, 11f, if (isSel) C.ON_PRIMARY else C.TEXT, true).apply {
                background = shape(if (isSel) C.ACCENT else C.SURFACE2, 8)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    paperTheme = idx
                    night = (idx == 1)
                    moonBtn.setColorFilter(if (night) C.ACCENT else C.TEXT)
                    refreshMode()
                    pageAdapter.notifyDataSetChanged()
                    dismiss()
                }
            }
            themeRow.add(btn, w = 0, weight = 1f, side = 4)
        }
        pad.add(themeRow, top = 8)

        body.add(pad)
    }

    private fun updateProgress(p: Int) {
        val pct = if (total <= 0) 0 else ((p + 1) * 100 / total)
        progLabel.text = "$pct% selesai"
        progHolder.removeAllViews()
        progHolder.addView(progressBar(pct / 100f, C.ACCENT, C.TRACK, 6), FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun toggleNight() {
        night = !night
        moonBtn.setColorFilter(if (night) C.ACCENT else C.TEXT)
        refreshMode()
        pageAdapter.notifyDataSetChanged()
    }

    private fun refreshMode() {
        modeLabel.text = "● Mode ${if (night) "Malam" else "Siang"} • ${(zoom * 100).toInt()}%"
        zoomLabel.text = "${(zoom * 100).toInt()}%"
    }

    private fun setZoom(z: Float, focusX: Float) {
        val nz = z.coerceIn(1f, 2.5f)
        if (abs(nz - zoom) < 0.05f) return
        val ratio = nz / zoom
        val old = scroll.scrollX
        zoom = nz
        refreshMode()
        gen++
        cache.evictAll()
        rv.layoutParams = FrameLayout.LayoutParams((baseW * zoom).toInt(), MATCH)
        pageAdapter.notifyDataSetChanged()
        scroll.post { scroll.scrollTo(((old + focusX) * ratio - focusX).toInt().coerceAtLeast(0), 0) }
    }

    private var paperTheme = 0 // 0 = Siang, 1 = Malam, 2 = Sepia, 3 = Hijau Eye-Care

    private val sepiaFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        0.90f, 0f, 0f, 0f, 25f,
        0f, 0.82f, 0f, 0f, 15f,
        0f, 0f, 0.65f, 0f, -10f,
        0f, 0f, 0f, 1f, 0f
    )))

    private val greenFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        0.82f, 0f, 0f, 0f, -10f,
        0f, 0.95f, 0f, 0f, 15f,
        0f, 0f, 0.82f, 0f, -10f,
        0f, 0f, 0f, 1f, 0f
    )))

    private class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)

    private inner class PageAdapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = total

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                minimumHeight = dp(320)
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) }
            }
            return VH(iv)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            h.iv.tag = pos
            when (paperTheme) {
                1 -> {
                    h.iv.colorFilter = invert
                    h.iv.setBackgroundColor(0xFF14161D.toInt())
                }
                2 -> {
                    h.iv.colorFilter = sepiaFilter
                    h.iv.setBackgroundColor(0xFFF4ECD8.toInt())
                }
                3 -> {
                    h.iv.colorFilter = greenFilter
                    h.iv.setBackgroundColor(0xFFE2F0D9.toInt())
                }
                else -> {
                    h.iv.colorFilter = null
                    h.iv.setBackgroundColor(Color.WHITE)
                }
            }
            val tw = (baseW * zoom).toInt() - dp(16)
            (h.iv.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.width != tw) {
                    lp.width = tw
                    h.iv.layoutParams = lp
                }
            }
            val c = cache.get(pos)
            if (c != null) { h.iv.setImageBitmap(c); return }
            h.iv.setImageDrawable(null)
            val myGen = gen
            exec.execute {
                // Saat di-scroll cepat, halaman yang sudah lewat tidak perlu dirender lagi.
                if (myGen != gen || h.iv.tag != pos) return@execute
                cache.get(pos)?.let { c -> runOnUiThread { if (h.iv.tag == pos && myGen == gen) h.iv.setImageBitmap(c) }; return@execute }
                val bmp = try {
                    synchronized(lock) {
                        val r = renderer
                        if (r == null) null else Pdf.render(r, pos, 1f, tw, Perf.pageMaxPx()).bmp
                    }
                } catch (_: OutOfMemoryError) {
                    cache.evictAll(); null
                } catch (_: Throwable) { null }
                if (bmp != null && myGen == gen) {
                    cache.put(pos, bmp)
                    runOnUiThread { if (h.iv.tag == pos && myGen == gen) h.iv.setImageBitmap(bmp) }
                }
            }
        }
    }

    override fun onDestroy() {
        Perf.removeTrim(trimHook)
        try { officeWeb?.destroy() } catch (_: Exception) {}
        officeWeb = null
        exec.shutdownNow()
        synchronized(lock) {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            renderer = null
        }
        super.onDestroy()
    }

    private fun startVoiceReaderForPdf(docUri: android.net.Uri, docName: String) {
        val r = renderer
        if (r == null) {
            val txt = try {
                if (mimeStr.startsWith("text/") || docName.endsWith(".txt", ignoreCase = true)) {
                    contentResolver.openInputStream(docUri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                } else {
                    OfficeReader.readDocx(this, docUri).joinToString("\n") { it.text }
                }
            } catch (_: Exception) { "" }
            if (txt.isNotBlank()) {
                VoiceReaderManager.showVoiceReaderDialog(this, docName, txt)
            } else {
                toast("Tidak ada teks yang dapat dibacakan")
            }
            return
        }
        work("Membaca teks dari dokumen…", {
            val sb = StringBuilder()
            val maxPages = Math.min(total, 10)
            for (p in 0 until maxPages) {
                try {
                    val page = r.openPage(p)
                    if (page != null) {
                        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        val ocrRes = try { Ocr.run(bmp) } finally { bmp.recycle() }
                        if (ocrRes.text.isNotBlank()) {
                            sb.append("Halaman ").append(p + 1).append(":\n")
                            sb.append(ocrRes.text).append("\n\n")
                        }
                    }
                } catch (_: Exception) {}
            }
            sb.toString().trim()
        }) { res ->
            res.onSuccess { txt ->
                if (txt.isNotBlank()) {
                    VoiceReaderManager.showVoiceReaderDialog(this, docName, txt)
                } else {
                    toast("Tidak ditemukan teks untuk dibacakan")
                }
            }.onFailure {
                toast("Gagal memproses teks dokumen")
            }
        }
    }
}
