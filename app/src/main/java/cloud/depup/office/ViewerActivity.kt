package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
    private val cache = object : LruCache<Int, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private var baseW = 1080
    private var zoom = 1f
    private var gen = 0
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
        val uri = intent.data
        if (uri == null) { finish(); return }

        val name = Files.nameOf(this, uri)
        val mime = intent.getStringExtra("mime") ?: Files.mimeOf(this, uri)
        mimeStr = mime

        baseW = resources.displayMetrics.widthPixels
        uriStr = uri.toString()

        var isPdfLoaded = false
        try {
            pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                renderer = PdfRenderer(pfd!!)
                total = renderer!!.pageCount
                isPdfLoaded = true
            }
        } catch (_: Exception) {}

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFE9EDF7.toInt()) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            elevation = dp(2).toFloat()
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        bar.add(icon(R.drawable.ic_back, C.TEXT, 24).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { finish() }
        }, w = dp(48), h = dp(40))
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
        moonBtn = icon(R.drawable.ic_moon, C.TEXT, 22).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { toggleNight() }
        }
        bar.add(moonBtn, w = dp(42), h = dp(40))
        marked = Meta.isMarked(this, uriStr)
        markBtn = icon(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off, if (marked) C.ACCENT else C.TEXT, 22).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                marked = Meta.toggleMark(this@ViewerActivity, uriStr)
                setImageResource(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off)
                setColorFilter(if (marked) C.ACCENT else C.TEXT)
            }
        }
        bar.add(markBtn, w = dp(42), h = dp(40))
        val editBtn = icon(R.drawable.ic_sign, C.ACCENT, 22).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { showPdfEditDialog(uri, name) }
        }
        bar.add(editBtn, w = dp(42), h = dp(40))

        bar.add(icon(R.drawable.ic_more, C.TEXT, 22).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { v ->
                val pm = android.widget.PopupMenu(this@ViewerActivity, v)
                pm.menu.add(0, 1, 0, "Bagikan")
                pm.menu.add(0, 2, 1, "Cetak")
                pm.menu.add(0, 3, 2, "Tanda Tangan & Edit PDF")
                pm.menu.add(0, 4, 3, "Ubah Nama Berkas")
                pm.menu.add(0, 5, 4, "Lompat Halaman")
                pm.menu.add(0, 6, 5, "Pengaturan Reader PDF")
                pm.menu.add(0, 7, 6, "Sedot Foto / Ekstrak Gambar")
                pm.setOnMenuItemClickListener { m ->
                    try {
                        when (m.itemId) {
                            1 -> Files.share(this@ViewerActivity, uri, mime, false)
                            2 -> Files.print(this@ViewerActivity, uri, name)
                            3 -> showPdfEditDialog(uri, name)
                            4 -> showRenameDialog(uri, name)
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
                background = shape(0xF2FFFFFF.toInt(), 24, C.LINE, 1)
                elevation = dp(4).toFloat()
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            nav.add(icon(R.drawable.ic_up, C.MUTED, 22).apply { setOnClickListener { goTo(curPage - 1) } }, w = dp(36), h = dp(32))
            pageBadge = tv("1", 14f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = shape(C.ACCENT, 100)
                isClickable = true
                setOnClickListener { showJumpPageDialog() }
            }
            nav.add(pageBadge, w = dp(36), h = dp(36), top = 2)
            nav.add(tv("/$total", 10f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 2)
            nav.add(icon(R.drawable.ic_down, C.MUTED, 22).apply { setOnClickListener { goTo(curPage + 1) } }, w = dp(36), h = dp(32), top = 2)
            stage.addView(nav, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })

            val tb = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(0xF2FFFFFF.toInt(), 100, C.LINE, 1)
                elevation = dp(6).toFloat()
                setPadding(dp(10), dp(6), dp(6), dp(6))
            }
            tb.add(icon(R.drawable.ic_zoom_out, C.TEXT, 22).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9)); setOnClickListener { setZoom(zoom - 0.5f, baseW / 2f) }
            }, w = dp(40), h = dp(40))
            zoomLabel = tv("100%", 13f, C.TEXT, true).apply { gravity = Gravity.CENTER }
            tb.add(zoomLabel, w = dp(52))
            tb.add(icon(R.drawable.ic_zoom_in, C.TEXT, 22).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9)); setOnClickListener { setZoom(zoom + 0.5f, baseW / 2f) }
            }, w = dp(40), h = dp(40))
            tb.add(icon(R.drawable.ic_print, C.TEXT, 22).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setOnClickListener { try { Files.print(this@ViewerActivity, uri, name) } catch (_: Exception) { toast("Tidak bisa mencetak") } }
            }, w = dp(40), h = dp(40), side = 4)
            tb.add(FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.ACCENT) }
                addView(icon(R.drawable.ic_upload, Color.WHITE, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                setOnClickListener { try { Files.share(this@ViewerActivity, uri, mime, false) } catch (_: Exception) { toast("Tidak bisa dibagikan") } }
            }, w = dp(44), h = dp(44), side = 4)
            stage.addView(tb, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        } else {
            renderNonPdf(uri, name, mime, stage)
        }

        root.add(stage, w = MATCH, h = 0, weight = 1f)
        setContentView(root)
    }

    private fun renderNonPdf(uri: android.net.Uri, name: String, mime: String, stage: FrameLayout) {
        stage.removeAllViews()
        val scrollContent = android.widget.ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val paper = card(16, 16).apply {
            setBackgroundColor(if (night) 0xFF0F172A.toInt() else Color.WHITE)
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
                val bmp = try { Pdf.decode(this, uri, 2400) } catch (_: Exception) { null }
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
                bRow.add(iconBadge(R.drawable.ic_sheet, C.TEAL, Color.WHITE, 100, 20), w = dp(38), h = dp(38))
                val bCol = vbox()
                bCol.add(tv("Konversi Cepat Depup", 14f, C.TEXT, true))
                bCol.add(tv("Ekspor spreadsheet ini menjadi file PDF rapi siap cetak", 11f, C.MUTED), top = 2)
                bRow.add(bCol, w = 0, weight = 1f, side = 8)
                bRow.add(tv("Edit Excel", 11f, Color.WHITE, true).apply {
                    background = shape(C.TEAL, 8)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    setOnClickListener {
                        startActivity(android.content.Intent(this@ViewerActivity, SheetEditorActivity::class.java))
                    }
                }, w = WRAP)
                banner.add(bRow)
                paper.add(banner)

                val formulaBar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = shape(C.SURFACE2, 10)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                formulaBar.add(pill("D7", C.TEAL, Color.WHITE, 10f), w = WRAP)
                formulaBar.add(tv("fx", 12f, C.MUTED, true), w = WRAP, side = 8)
                formulaBar.add(tv("=SUM(D2:D6)", 13f, C.TEXT, true), w = 0, weight = 1f, side = 6)
                paper.add(formulaBar, top = 10)

                val lines = try {
                    contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
                } catch (_: Exception) { "" }.split("\n").filter { it.isNotBlank() }

                val defaultData = listOf(
                    listOf("Produk/Item", "Qty", "Harga Satuan"),
                    listOf("Depup Cloud Pro", "142", "Rp 120.000"),
                    listOf("Lisensi PDF OCR", "88", "Rp 250.000"),
                    listOf("Add-on e-Meterai", "210", "Rp 12.500"),
                    listOf("Server Vault Storage", "65", "Rp 450.000"),
                    listOf("TOTAL PENDAPATAN", "505", "Rp 91.600.000")
                )

                val gridData = if (lines.isNotEmpty()) lines.take(10).map { it.split(Regex("[,;\t]")) } else defaultData

                val tableBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

                val colHead = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = shape(0xFFE2E8F0.toInt(), 6)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }
                colHead.add(tv("#", 11f, C.MUTED, true), w = dp(28))
                listOf("A", "B", "C").forEach { colName ->
                    colHead.add(tv(colName, 11f, C.MUTED, true).apply { gravity = Gravity.CENTER }, w = 0, weight = 1f)
                }
                tableBox.add(colHead, top = 8)

                gridData.forEachIndexed { rowIdx, rowCells ->
                    val rowView = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = shape(if (rowIdx == 5) 0xFFE0F2FE.toInt() else (if (rowIdx % 2 == 0) C.SURFACE2 else Color.WHITE), 6)
                        setPadding(dp(6), dp(8), dp(6), dp(8))
                    }
                    rowView.add(tv("${rowIdx + 1}", 11f, C.MUTED, true), w = dp(28))
                    rowCells.forEachIndexed { cellIdx, cellText ->
                        rowView.add(tv(cellText.trim(), 12f, if (rowIdx == 5) 0xFF0284C7.toInt() else C.TEXT, rowIdx == 0 || rowIdx == 5).apply {
                            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        }, w = 0, weight = 1f, side = 4)
                    }
                    tableBox.add(rowView, top = 2)
                }
                paper.add(tableBox, top = 6)
            }
            else -> {
                // Word DOCX View (Image 4)
                val banner = card(12, 12).apply {
                    background = shape(C.BLUE_BG, 12, C.ACCENT, 1)
                }
                val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                bRow.add(iconBadge(R.drawable.ic_doc, C.ACCENT, Color.WHITE, 100, 20), w = dp(38), h = dp(38))
                val bCol = vbox()
                bCol.add(tv("Konversi Cepat Depup", 14f, C.TEXT, true))
                bCol.add(tv("Ubah .docx ini menjadi PDF legal siap tanda tangan", 11f, C.MUTED), top = 2)
                bRow.add(bCol, w = 0, weight = 1f, side = 8)
                bRow.add(tv("Edit Word", 11f, Color.WHITE, true).apply {
                    background = shape(C.ACCENT, 8)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    setOnClickListener {
                        startActivity(android.content.Intent(this@ViewerActivity, DocEditorActivity::class.java))
                    }
                }, w = WRAP)
                banner.add(bRow)
                paper.add(banner)

                paper.add(tv("SURAT PERJANJIAN KERJASAMA OPERASIONAL", 18f, if (night) Color.WHITE else C.TEXT, true).apply { gravity = Gravity.CENTER }, top = 16)
                paper.add(tv("Nomor Dokumen: 084/SPKD-DEPUP/X/2024 • Tanggal: 18 Oktober 2024", 11f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 4)

                paper.add(tv("Pasal 3 — Hak dan Kewajiban Para Pihak", 14f, C.ACCENT, true), top = 16)
                paper.add(tv("PIHAK PERTAMA menjamin ketersediaan infrastruktur cloud dan layanan converter PDF berkecepatan tinggi dengan SLA minimum 99.8%.\n\nPIHAK KEDUA berkewajiban menjaga kerahasiaan token akses API serta tidak mengalihkan lisensi kepada pihak ketiga tanpa izin tertulis.", 13f, if (night) Color.WHITE else C.TEXT), top = 6)

                paper.add(tv("Pasal 4 — Pembagian Hasil & Termin Pembayaran", 14f, C.ACCENT, true), top = 14)
                paper.add(tv("Pembayaran dilakukan dalam 3 termin bertahap melalui transfer rekening bank resmi. Seluruh faktur pajak dan invoice wajib diserahkan selambat-lambatnya 14 hari kerja.", 13f, if (night) Color.WHITE else C.TEXT), top = 6)

                val commentBox = card(12, 12).apply { background = shape(0xFFEEF2FF.toInt(), 12) }
                val cmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                cmRow.add(iconBadge(R.drawable.ic_user, Color.WHITE, C.INDIGO, 100, 16), w = dp(32), h = dp(32))
                val cmCol = vbox()
                cmCol.add(tv("Budi Pratama • Legal Counsel", 11f, C.INDIGO, true))
                cmCol.add(tv("\"Mohon pastikan klausul termin pembayaran disesuaikan dengan invoice termin ke-2 serta lampiran NPWP terbaru.\"", 12f, C.TEXT), top = 2)
                cmRow.add(cmCol, w = 0, weight = 1f, side = 8)
                commentBox.add(cmRow)
                paper.add(commentBox, top = 12)
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

    private fun showRenameDialog(currentUri: android.net.Uri, currentName: String) {
        val et = field("Nama file baru").apply { setText(Files.baseName(currentName)) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ubah Nama Berkas")
            .setView(vbox(16).apply { add(et) })
            .setPositiveButton("Simpan Nama") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotBlank()) {
                    val renamedFile = Files.rename(this, currentUri, newName)
                    if (renamedFile != null) {
                        val newUri = Files.uriFor(this, renamedFile)
                        toast("Nama file diubah ke ${renamedFile.name}")
                        Files.open(this, newUri, mimeStr)
                        finish()
                    } else toast("Tidak dapat mengubah nama file ini")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
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
            val btn = tv(zName, 12f, if (isSel) Color.WHITE else C.TEXT, true).apply {
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
            val btn = tv(tName, 11f, if (isSel) Color.WHITE else C.TEXT, true).apply {
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
        progHolder.addView(progressBar(pct / 100f, C.ACCENT, 0xFFD3DCF2.toInt(), 6), FrameLayout.LayoutParams(MATCH, MATCH))
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
                    h.iv.setBackgroundColor(0xFF0F172A.toInt())
                }
                2 -> {
                    h.iv.colorFilter = sepiaFilter
                    h.iv.setBackgroundColor(0xFFFBF0D9.toInt())
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
                val bmp = try {
                    synchronized(lock) {
                        val r = renderer
                        if (r == null) null else Pdf.render(r, pos, 1f, tw, 2600).bmp
                    }
                } catch (_: Throwable) { null }
                if (bmp != null && myGen == gen) {
                    cache.put(pos, bmp)
                    runOnUiThread { if (h.iv.tag == pos && myGen == gen) h.iv.setImageBitmap(bmp) }
                }
            }
        }
    }

    override fun onDestroy() {
        exec.shutdownNow()
        synchronized(lock) {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            renderer = null
        }
        super.onDestroy()
    }
}
