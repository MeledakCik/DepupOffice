package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.InputType
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Editor dokumen Word ala Microsoft Word, tetap sederhana:
 *  - Pita menu berlabel: Beranda · Sisipkan · Tata Letak · Tinjau (+ Tabel saat kursor di tabel).
 *  - Format karakter (font, ukuran, tebal, miring, garis bawah, coret, superskrip/subskrip, warna, stabilo),
 *    format paragraf (gaya judul, rata, daftar poin/nomor, inden, spasi), tabel, gambar, tanda tangan,
 *    pemisah halaman, simbol, tanggal, ukuran kertas/orientasi/margin, cari & ganti, jumlah kata,
 *    bacakan, cetak/PDF, urungkan/ulangi.
 *  - Semua perubahan ditulis ke berkas .docx asli lewat [DocxDocument] tanpa merusak bagian lain.
 */
class DocEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TEMPLATE = "template"
        const val TEMPLATE_SURAT = "surat"
        const val TEMPLATE_CV = "cv"
        const val TEMPLATE_BLANK = "blank"

        private val TEXT_COLORS = listOf(
            0xFF000000, 0xFF404040, 0xFF7F7F7F, 0xFFC00000, 0xFFFF0000, 0xFFFFC000, 0xFFFFFF00,
            0xFF92D050, 0xFF00B050, 0xFF00B0F0, 0xFF0070C0, 0xFF002060, 0xFF7030A0, 0xFF833C0B
        ).map { it.toInt() }
        private val HIGHLIGHTS = listOf("yellow", "green", "cyan", "magenta", "red", "blue", "lightGray", "darkYellow")
        private val FONTS = listOf(
            "Calibri", "Arial", "Times New Roman", "Cambria", "Georgia", "Verdana", "Tahoma",
            "Segoe UI", "Garamond", "Book Antiqua", "Century Gothic", "Courier New"
        )
        private val SIZES = listOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72)
        private val SYMBOLS = listOf(
            "©", "®", "™", "°", "±", "×", "÷", "≈", "≠", "≤", "≥", "√", "∞", "π", "Σ", "Ω",
            "€", "£", "¥", "Rp", "•", "…", "—", "–", "«", "»", "“", "”", "¼", "½", "¾", "²", "³",
            "→", "←", "↑", "↓", "✓", "✗", "★", "☎", "✉", "§", "¶", "†", "‰"
        )
        private class Paper(val label: String, val w: Int, val h: Int)
        private val PAPERS = listOf(
            Paper("A4", 11906, 16838), Paper("F4 / Folio", 12189, 18709), Paper("Letter", 12240, 15840),
            Paper("Legal", 12240, 20160), Paper("A5", 8391, 11906)
        )
        private class Margins(val label: String, val desc: String, val t: Int, val r: Int, val b: Int, val l: Int)
        private val MARGINS = listOf(
            Margins("Normal", "2,54 cm semua sisi", 1440, 1440, 1440, 1440),
            Margins("Sempit", "1,27 cm semua sisi", 720, 720, 720, 720),
            Margins("Sedang", "atas/bawah 2,54 · kiri/kanan 1,91 cm", 1440, 1080, 1440, 1080),
            Margins("Lebar", "atas/bawah 2,54 · kiri/kanan 5,08 cm", 1440, 2880, 1440, 2880),
            Margins("Skripsi / Surat Resmi", "kiri 4 · atas 4 · kanan 3 · bawah 3 cm", 2268, 1701, 1701, 2268)
        )
    }

    class Pending(val editor: ParaEdit, val pos: Int, val fmt: CharFmt)
    private class Owner(val list: MutableList<EBlock>, val box: LinearLayout, val cell: ECell?, val row: ERow?, val table: ETable?)
    private class Snap(val body: List<EBlock>, val page: PageSetup?, val focusIdx: Int, val focusPos: Int)
    private class ParaRow(val root: LinearLayout, val label: TextView, val editor: ParaEdit, val images: LinearLayout)

    private lateinit var doc: DocxDocument
    private var docName = "Dokumen"
    private var savedFile: File? = null
    private var dirty = false

    private lateinit var scroll: ScrollView
    private lateinit var pageCard: LinearLayout
    private lateinit var pageBox: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var ribbonScroll: HorizontalScrollView
    private lateinit var ribbon: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var zoomTv: TextView
    private lateinit var undoBtn: View
    private lateinit var redoBtn: View
    private var findBar: View? = null

    private val rows = HashMap<EPara, ParaRow>()
    private val owners = HashMap<EPara, Owner>()
    private var current: ParaEdit? = null
    private var tab = 0
    private var tableTabShown = false
    private var zoom = 1f

    var pending: Pending? = null
    var softBreak = false

    private val undo = ArrayDeque<Snap>()
    private val redo = ArrayDeque<Snap>()
    private var typing = false
    private var inCommand = false
    private val handler = Handler(Looper.getMainLooper())
    private val endTyping = Runnable { typing = false }
    private val statusTick = Runnable { updateStatus() }

    private val toolStates = ArrayList<() -> Unit>()
    private val imgCache = HashMap<String, Bitmap?>()
    private var printView: WebView? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { insertImageFrom(it) } }

    // =========================================================================================
    // Siklus hidup
    // =========================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.apply(this, C.ACCENT)
        // Membaca & mengurai .docx bisa makan waktu (dokumen puluhan halaman) — dikerjakan di latar,
        // layar memuat tampil seketika alih-alih aplikasi terlihat membeku.
        val loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(C.SURFACE2)
        }
        loading.add(android.widget.ProgressBar(this), w = dp(40), h = dp(40))
        loading.add(tv("Membuka dokumen…", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 10)
        setContentView(loading)
        Thread(null, {
            val opened = try { openDocument() } catch (_: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (opened == null) { toast("Dokumen tidak dapat dibuka."); finish(); return@runOnUiThread }
                if (openedFlat) toast("Format dokumen tidak dikenali penuh; hanya teks yang dimuat.")
                doc = opened
                buildUi()
            }
        }, "doc-open", 16L * 1024 * 1024).start()
    }

    private var openedFlat = false

    private fun buildUi() {

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.SURFACE2) }
        root.add(topBar())
        root.add(tabsBar())
        ribbonScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(C.SURFACE)
        }
        ribbon = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        ribbonScroll.addView(ribbon)
        root.add(ribbonScroll)
        root.add(View(this).apply { setBackgroundColor(C.LINE) }, h = dp(1))

        val docArea = FrameLayout(this)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(C.CANVAS)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(80)) }
        pageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(2).toFloat(); setStroke(1, 0xFFD5D8E0.toInt()) }
            elevation = dp(2).toFloat()
        }
        pageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageCard.add(pageBox)
        pad.add(pageCard)
        scroll.addView(pad)
        // Ketuk area kosong di bawah halaman -> kursor ke akhir dokumen
        pad.setOnClickListener { focusEnd() }
        docArea.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        root.add(docArea, h = 0, weight = 1f)
        root.add(statusBar())
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { askClose() }
        })

        applyPageLook()
        renderAll()
        showTab(0)
        updateUndoButtons()
        pageBox.post { focusFirst() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun openDocument(): DocxDocument? {
        val rawUri = intent.getStringExtra(EXTRA_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (rawUri != null) {
            docName = Files.baseName(Files.nameOf(this, rawUri)).removePrefix("open_").ifBlank { "Dokumen" }
            val f = copyToCache(rawUri) ?: return null
            DocxDocument.open(f)?.let { return it }
            // Bukan .docx standar: muat teksnya ke dokumen baru agar tetap bisa diedit.
            val flat = try { OfficeReader.readDocx(this, Uri.fromFile(f)) } catch (_: Exception) { emptyList() }
            openedFlat = true
            return buildDoc { w ->
                if (flat.isEmpty()) w.paragraph("")
                flat.forEach { p -> w.paragraph(p.text, p.bold, p.italic, p.align) }
            }
        }
        return when (intent.getStringExtra(EXTRA_TEMPLATE)) {
            TEMPLATE_SURAT -> { docName = "Surat Lamaran Kerja"; buildDoc(::templateSurat) }
            TEMPLATE_CV -> { docName = "Curriculum Vitae"; buildDoc(::templateCv) }
            else -> { docName = "Dokumen Baru"; buildDoc { it.paragraph("") } }
        }
    }

    private fun copyToCache(uri: Uri): File? = try {
        val f = File(cacheDir, "edit_${System.currentTimeMillis()}.docx")
        val ok = contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) }; true } ?: false
        if (ok && f.length() > 0) f else null
    } catch (_: Exception) { null }

    private fun buildDoc(fill: (DocxWriter) -> Unit): DocxDocument? {
        val f = File(cacheDir, "new_${System.currentTimeMillis()}.docx")
        val w = DocxWriter(f, DocSettings.pageSize(this), DocSettings.marginMode(this), DocSettings.lineSpacing(this), DocSettings.fontFamily(this))
        try { w.beginPage(); fill(w); w.finish() } catch (t: Throwable) { w.abort(f); return null }
        return DocxDocument.open(f)
    }

    private fun templateSurat(w: DocxWriter) {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        w.paragraph("(Kota), ${SimpleDateFormat("d MMMM yyyy", Locale("id", "ID")).format(Date())}", align = 2)
        w.paragraph("Perihal: Lamaran Pekerjaan\nNomor: 001/LAMARAN/$year")
        w.paragraph("Kepada Yth.\nHRD Manager\nPT Nama Perusahaan\ndi Tempat")
        w.paragraph("Dengan hormat,")
        w.paragraph("Berdasarkan informasi lowongan pekerjaan yang saya peroleh, saya bermaksud mengajukan lamaran kerja untuk posisi yang tersedia di perusahaan Bapak/Ibu. Berikut data diri saya:", align = 3)
        w.paragraph("Nama\t: (Nama Lengkap)\nTempat/Tgl Lahir\t: (Kota, Tanggal)\nPendidikan\t: (Jenjang / Jurusan)\nAlamat\t: (Alamat Lengkap)\nNo. HP\t: (Nomor Aktif)\nEmail\t: (Alamat Email)")
        w.paragraph("Sebagai bahan pertimbangan, saya lampirkan Curriculum Vitae, fotokopi ijazah, transkrip nilai, dan dokumen pendukung lainnya. Besar harapan saya untuk dapat diberikan kesempatan wawancara.", align = 3)
        w.paragraph("Demikian surat lamaran ini saya buat dengan sebenar-benarnya. Atas perhatian Bapak/Ibu, saya ucapkan terima kasih.", align = 3)
        w.paragraph("Hormat saya,\n\n\n\n(Nama Lengkap)", align = 2)
    }

    private fun templateCv(w: DocxWriter) {
        w.title("Curriculum Vitae")
        w.subtitle("(Nama Lengkap) • (No. HP) • (Email)")
        w.heading("Data Pribadi")
        w.paragraph("Nama Lengkap\t: (Nama)\nTempat/Tgl Lahir\t: (Kota, Tanggal)\nJenis Kelamin\t: (L/P)\nAlamat\t: (Alamat)")
        w.heading("Ringkasan Profil")
        w.paragraph("Profesional yang berorientasi pada hasil dengan pengalaman di bidang (bidang). Terbiasa bekerja mandiri maupun dalam tim, teliti, dan cepat beradaptasi.", align = 3)
        w.heading("Pengalaman Kerja")
        w.paragraph("(Tahun – Sekarang) — (Nama Perusahaan)", bold = true)
        w.paragraph("Posisi: (Jabatan)\nTanggung jawab: (Uraikan pekerjaan dan pencapaian utama)")
        w.heading("Pendidikan")
        w.paragraph("(Tahun – Tahun) — (Nama Institusi)", bold = true)
        w.paragraph("Jurusan: (S1 / Teknik Informatika) • IPK: (Nilai)")
        w.heading("Keahlian")
        w.paragraph("• (Keahlian 1)\n• (Keahlian 2)\n• (Keahlian 3)")
    }

    // =========================================================================================
    // Bilah atas, tab, pita, status
    // =========================================================================================

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.ACCENT)
            setPadding(dp(6), dp(6), dp(10), dp(6))
        }
        bar.add(icon(R.drawable.ic_back, C.ON_PRIMARY, 22).apply {
            contentDescription = "Kembali"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; background = rippleBg()
            setPadding(dp(9), dp(9), dp(9), dp(9)); setOnClickListener { askClose() }
        }, w = dp(40), h = dp(40))
        val col = vbox().apply { setOnClickListener { renameDoc() } }
        titleTv = tv(docName, 15f, C.ON_PRIMARY, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        subTv = tv("Belum disimpan", 12f, onBarMuted)
        col.add(titleTv); col.add(subTv)
        bar.add(col, w = 0, weight = 1f, side = 6)
        undoBtn = topText("↶", "Urungkan") { doUndo() }
        redoBtn = topText("↷", "Ulangi") { doRedo() }
        bar.add(undoBtn, w = dp(40), h = dp(40))
        bar.add(redoBtn, w = dp(40), h = dp(40))
        bar.add(tv("Simpan", 13f, C.ACCENT, true).apply {
            background = shape(C.ON_PRIMARY, 10)
            minHeight = dp(40)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { save(false) }
        }, w = WRAP, side = 4)
        return bar
    }

    private fun topText(glyph: String, desc: String, onClick: () -> Unit): TextView =
        tv(glyph, 22f, C.ON_PRIMARY, true).apply {
            gravity = Gravity.CENTER; contentDescription = desc
            setOnClickListener { onClick() }
            setOnLongClickListener { toast(desc); true }
        }

    private val tabNames = listOf("Beranda", "Sisipkan", "Tata Letak", "Tinjau", "Tabel")
    /** Teks sekunder di atas bilah warna utama (kontras tetap >= 4.5:1 di kedua tema). */
    private val onBarMuted: Int get() = (C.ON_PRIMARY and 0x00FFFFFF) or 0xE6000000.toInt()
    private val tabViews = ArrayList<TextView>()

    private fun tabsBar(): View {
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.ACCENT)
            setPadding(dp(4), 0, dp(4), 0)
        }
        tabNames.forEachIndexed { i, n ->
            val t = tv(n, 14f, onBarMuted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(9))
                setOnClickListener { showTab(i) }
            }
            tabViews.add(t)
            tabRow.add(t, w = WRAP)
        }
        tabViews[4].visibility = View.GONE
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }
    }

    private fun showTab(i: Int) {
        tab = i
        tabViews.forEachIndexed { k, t ->
            val sel = k == i
            t.setTextColor(if (sel) C.ACCENT else onBarMuted)
            t.isSelected = sel
            t.background = if (sel) GradientDrawable().apply {
                setColor(C.SURFACE); val r = dp(10).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            } else null
        }
        ribbon.removeAllViews()
        toolStates.clear()
        when (i) {
            0 -> buildHome()
            1 -> buildInsert()
            2 -> buildLayout()
            3 -> buildReview()
            4 -> buildTableTab()
        }
        ribbonScroll.scrollTo(0, 0)
        refreshTools()
    }

    private fun statusBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(14), dp(4), dp(8), dp(4))
        }
        statusTv = tv("", 11.5f, C.MUTED)
        bar.add(statusTv, w = 0, weight = 1f)
        bar.add(tv("−", 20f, C.TEXT, true).apply { gravity = Gravity.CENTER; setOnClickListener { setZoom(zoom - 0.15f) } }, w = dp(36), h = dp(32))
        zoomTv = tv("100%", 12f, C.TEXT, true).apply { gravity = Gravity.CENTER; setOnClickListener { setZoom(1f) } }
        bar.add(zoomTv, w = dp(48))
        bar.add(tv("+", 20f, C.TEXT, true).apply { gravity = Gravity.CENTER; setOnClickListener { setZoom(zoom + 0.15f) } }, w = dp(36), h = dp(32))
        return bar
    }

    private fun updateStatus() {
        if (!::statusTv.isInitialized) return
        var words = 0
        for (ed in allEditors()) {
            val c = wordCache[ed]
            words += if (c != null && c[0] == ed.version) c[1]
            else countWords(ed.text?.toString().orEmpty()).also { wordCache[ed] = intArrayOf(ed.version, it) }
        }
        if (wordCache.size > rows.size * 2 + 64) wordCache.keys.retainAll(allEditors().toSet())
        val pg = doc.page
        val paper = pg?.let { p -> PAPERS.firstOrNull { (it.w == p.w && it.h == p.h) || (it.w == p.h && it.h == p.w) }?.label ?: "Kustom" } ?: "A4"
        statusTv.text = "$paper · ${if (pg?.landscape == true) "Lanskap" else "Potret"} · $words kata"
    }

    private val wordCache = HashMap<ParaEdit, IntArray>()
    private val wordRe = Regex("[\\p{L}\\p{N}]+(?:[-'’.][\\p{L}\\p{N}]+)*")
    private fun countWords(s: String) = if (s.isEmpty()) 0 else wordRe.findAll(s).count()

    // =========================================================================================
    // Alat pita
    // =========================================================================================

    private fun toolBox(label: String, glyph: View, glyphW: Int = 26, onClick: () -> Unit, isActive: (() -> Boolean)? = null): View {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(7), dp(5), dp(7), dp(4))
            minimumWidth = dp(52)
            minimumHeight = dp(52)
            isClickable = true
            isFocusable = true
            contentDescription = label
            if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = label
            background = rippleBg(10)
            setOnClickListener { onClick(); refreshTools() }
        }
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        b.add(glyph, w = dp(glyphW), h = dp(24))
        b.add(tv(label, 10f, C.MUTED).apply { gravity = Gravity.CENTER; maxLines = 1 }, w = WRAP, top = 2)
        var last: Boolean? = null
        if (isActive != null) toolStates.add {
            val on = isActive()
            if (on == last) return@add
            last = on
            b.background = if (on) RippleDrawable(ColorStateList.valueOf(C.RIPPLE), shape(C.BLUE_BG, 10), null) else rippleBg(10)
            b.isSelected = on
            b.contentDescription = label + if (on) ", aktif" else ""
        }
        ribbon.add(b, w = WRAP, h = WRAP)
        return b
    }

    private fun glyphText(text: CharSequence, sp: Float = 17f, style: Int = Typeface.BOLD, color: Int = C.TEXT, serif: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text; textSize = sp; setTextColor(color); gravity = Gravity.CENTER
            typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, style)
            includeFontPadding = false
        }

    private fun drop(label: String, value: () -> String, widthDp: Int, onClick: () -> Unit) {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(4))
            isClickable = true
            isFocusable = true
            background = rippleBg(8)
            setOnClickListener { onClick() }
        }
        val v = tv("", 13f, C.TEXT, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            background = shape(C.SURFACE, 6, C.LINE_STRONG, 1)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        b.add(v, w = dp(widthDp), h = dp(26))
        b.add(tv(label, 10f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 2)
        var last: String? = null
        toolStates.add {
            val now = value()
            if (now != last) { last = now; v.text = "$now  ▾"; b.contentDescription = "$label: $now. Ketuk untuk mengganti" }
        }
        ribbon.add(b, w = WRAP)
    }

    private fun sep() = ribbon.add(View(this).apply { setBackgroundColor(C.LINE) }, w = dp(1), h = dp(38), side = 4)

    private var fmtMemo: CharFmt? = null
    private var refreshQueued = false
    private val refreshTick = Runnable { refreshQueued = false; refreshToolsNow() }

    /** Segarkan status pita. Saat mengetik dipanggil berkali-kali per huruf → digabung jadi satu per ~80 ms. */
    private fun refreshTools() {
        if (refreshQueued) return
        refreshQueued = true
        handler.postDelayed(refreshTick, 80)
    }

    private fun refreshToolsNow() {
        handler.removeCallbacks(refreshTick); refreshQueued = false
        fmtMemo = null
        toolStatesRunning = true
        try { toolStates.forEach { it() } } finally { fmtMemo = null; toolStatesRunning = false }
    }

    private fun buildHome() {
        drop("Gaya", { doc.styles.name(cur()?.para?.pf?.style).let(::styleLabel) }, 96) { pickStyle() }
        drop("Font", { curFmt().font ?: "Calibri" }, 104) { pickFont() }
        drop("Ukuran", { fmtSize(curFmt().sz ?: 22) }, 48) { pickSize() }
        toolBox("Perbesar", glyphText("A⁺", 16f), onClick = { growFont(+1) })
        toolBox("Perkecil", glyphText("A⁻", 14f), onClick = { growFont(-1) })
        sep()
        toolBox("Tebal", glyphText("B"), onClick = { toggle { f, on -> f.copy(b = on) } }, isActive = { curFmt().b == true })
        toolBox("Miring", glyphText("I", 18f, Typeface.BOLD_ITALIC, serif = true), onClick = { toggle { f, on -> f.copy(i = on) } }, isActive = { curFmt().i == true })
        toolBox("Garis Bawah", glyphText(SpannableString("U").apply { setSpan(UnderlineSpan(), 0, 1, 0) }), onClick = { toggle { f, on -> f.copy(u = on) } }, isActive = { curFmt().u == true })
        toolBox("Coret", glyphText(SpannableString("ab").apply { setSpan(StrikethroughSpan(), 0, 2, 0) }, 15f), onClick = { toggle { f, on -> f.copy(s = on) } }, isActive = { curFmt().s == true })
        toolBox("Pangkat", glyphText("x²", 15f), onClick = { toggleVa(1) }, isActive = { curFmt().va == 1 })
        toolBox("Indeks", glyphText("x₂", 15f), onClick = { toggleVa(2) }, isActive = { curFmt().va == 2 })
        sep()
        val colorBar = View(this)
        val colorG = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        colorG.add(glyphText("A", 16f), w = dp(26), h = dp(18)); colorG.add(colorBar, w = dp(18), h = dp(4))
        var lastColor: Int? = null
        toolStates.add { val c = curFmt().color.let { if (it == null || it == CharFmt.AUTO) Color.BLACK else it }; if (c != lastColor) { lastColor = c; colorBar.setBackgroundColor(c) } }
        toolBox("Warna Teks", colorG, onClick = { pickColor() })
        val hlBar = View(this)
        val hlG = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        hlG.add(glyphText("ab", 14f, Typeface.NORMAL), w = dp(26), h = dp(18)); hlG.add(hlBar, w = dp(18), h = dp(4))
        var lastHl: Int? = null
        toolStates.add { val c = curFmt().bg.let { if (it == null || it == CharFmt.NONE) 0xFFFFFF00.toInt() else it }; if (c != lastHl) { lastHl = c; hlBar.setBackgroundColor(c) } }
        toolBox("Stabilo", hlG, onClick = { pickHighlight() })
        toolBox("Hapus Format", glyphText("T✕", 14f, color = C.RED), onClick = { clearFormat() })
        sep()
        toolBox("Kiri", GlyphIcon(this, GlyphIcon.ALIGN_L), onClick = { setAlign(0) }, isActive = { curAlign() == 0 })
        toolBox("Tengah", GlyphIcon(this, GlyphIcon.ALIGN_C), onClick = { setAlign(1) }, isActive = { curAlign() == 1 })
        toolBox("Kanan", GlyphIcon(this, GlyphIcon.ALIGN_R), onClick = { setAlign(2) }, isActive = { curAlign() == 2 })
        toolBox("Rata Penuh", GlyphIcon(this, GlyphIcon.ALIGN_J), onClick = { setAlign(3) }, isActive = { curAlign() == 3 })
        sep()
        toolBox("Poin", GlyphIcon(this, GlyphIcon.BULLET), onClick = { toggleList(true) }, isActive = { curList() == 1 })
        toolBox("Nomor", GlyphIcon(this, GlyphIcon.NUMBER), onClick = { toggleList(false) }, isActive = { curList() == 2 })
        toolBox("Kurangi Inden", GlyphIcon(this, GlyphIcon.INDENT_OUT), onClick = { indent(-1) })
        toolBox("Tambah Inden", GlyphIcon(this, GlyphIcon.INDENT_IN), onClick = { indent(+1) })
        toolBox("Spasi Baris", GlyphIcon(this, GlyphIcon.SPACING), onClick = { pickLineSpacing() })
    }

    private fun buildInsert() {
        toolBox("Tabel", GlyphIcon(this, GlyphIcon.TABLE), onClick = { pickTable() })
        toolBox("Gambar", icon(R.drawable.ic_image, C.TEXT, 24), onClick = { pickImage.launch("image/*") })
        toolBox("Tanda Tangan", icon(R.drawable.ic_sign, C.TEXT, 24), onClick = { drawSignature() })
        sep()
        toolBox("Pemisah Halaman", GlyphIcon(this, GlyphIcon.BREAK), onClick = { insertPageBreak() })
        toolBox("Baris Baru", glyphText("↵", 18f), onClick = { insertSoftBreak() })
        toolBox("Paragraf Baru", glyphText("¶", 18f), onClick = { insertParagraphAfter() })
        sep()
        toolBox("Simbol", glyphText("Ω", 18f), onClick = { pickSymbol() })
        toolBox("Tanggal & Waktu", icon(R.drawable.ic_clock, C.TEXT, 24), onClick = { pickDate() })
    }

    private fun buildLayout() {
        toolBox("Ukuran Kertas", GlyphIcon(this, GlyphIcon.PAGE), onClick = { pickPaper() })
        toolBox("Orientasi", GlyphIcon(this, GlyphIcon.ORIENT), onClick = { pickOrientation() })
        toolBox("Margin", GlyphIcon(this, GlyphIcon.MARGIN), onClick = { pickMargins() })
        sep()
        toolBox("Spasi Baris", GlyphIcon(this, GlyphIcon.SPACING), onClick = { pickLineSpacing() })
        toolBox("Spasi Paragraf", GlyphIcon(this, GlyphIcon.PARA_SPACE), onClick = { pickParaSpacing() })
        toolBox("Inden", GlyphIcon(this, GlyphIcon.INDENT_IN), onClick = { pickIndent() })
    }

    private fun buildReview() {
        toolBox("Cari & Ganti", icon(R.drawable.ic_search, C.TEXT, 24), onClick = { toggleFindBar() })
        toolBox("Jumlah Kata", glyphText("123", 14f), onClick = { showWordCount() })
        toolBox("Bacakan", glyphText("🔊", 16f, Typeface.NORMAL), onClick = { readAloud() })
        sep()
        toolBox("Cetak / PDF", icon(R.drawable.ic_print, C.TEXT, 24), onClick = { printDoc() })
        toolBox("Simpan Sebagai", icon(R.drawable.ic_download, C.TEXT, 24), onClick = { save(true) })
        toolBox("Panduan", glyphText("?", 18f), onClick = { showHelp() })
    }

    private fun buildTableTab() {
        toolBox("Baris di Bawah", GlyphIcon(this, GlyphIcon.ROW_ADD), onClick = { tableOp("rowAdd") })
        toolBox("Hapus Baris", GlyphIcon(this, GlyphIcon.ROW_DEL), onClick = { tableOp("rowDel") })
        toolBox("Kolom di Kanan", GlyphIcon(this, GlyphIcon.COL_ADD), onClick = { tableOp("colAdd") })
        toolBox("Hapus Kolom", GlyphIcon(this, GlyphIcon.COL_DEL), onClick = { tableOp("colDel") })
        sep()
        toolBox("Hapus Tabel", icon(R.drawable.ic_trash, C.RED, 24), onClick = { tableOp("delete") })
    }

    private fun styleLabel(name: String): String =
        StdStyle.entries.firstOrNull { it.wordName.equals(name, true) }?.label ?: name

    private fun fmtSize(halfPts: Int): String = if (halfPts % 2 == 0) "${halfPts / 2}" else "${halfPts / 2},5"

    // =========================================================================================
    // Render dokumen
    // =========================================================================================

    private fun ptPx(): Float = 1.25f * zoom * resources.displayMetrics.density
    private fun twPx(tw: Int?): Int = (((tw ?: 0) / 20f) * ptPx()).roundToInt()
    private fun innerWidth(): Int = resources.displayMetrics.widthPixels - dp(16) - pageCard.paddingLeft - pageCard.paddingRight

    private fun applyPageLook() {
        val pg = doc.page
        fun m(tw: Int?) = (twPx(tw) * 0.35f).roundToInt().coerceIn(dp(12), dp(36))
        pageCard.setPadding(m(pg?.left), m(pg?.top), m(pg?.right), m(pg?.bottom))
    }

    private var renderGen = 0

    /**
     * Bangun ulang tampilan. Dokumen panjang dibangun bertahap: blok sekitar fokus langsung, sisanya per potongan
     * tiap frame — layar tidak membeku saat membuka, mengurungkan atau mengubah zoom.
     */
    private fun renderAll(focus: EPara? = null, focusPos: Int = -1) {
        val gen = ++renderGen
        flushRender = null
        pageBox.removeAllViews()
        rows.clear(); owners.clear()
        current = null
        val blocks = ArrayList(doc.body)
        val owner = Owner(doc.body, pageBox, null, null, null)
        // blok yang memuat paragraf fokus harus ada di potongan pertama
        val focusIdx = if (focus == null) -1 else blocks.indexOfFirst { b -> b === focus || (b is ETable && b.rows.any { r -> r.cells.any { c -> c.paras.any { it === focus } } }) }
        val first = max(40, focusIdx + 1)
        var i = 0
        fun done() {
            refreshLists()
            updateStatus()
            updateTableTab()
            if (focus != null) rows[focus]?.editor?.let { e -> e.post { focusEditor(e, if (focusPos < 0) e.length() else focusPos) } }
        }
        while (i < blocks.size && i < first) pageBox.addView(blockView(blocks[i++], owner))
        if (i >= blocks.size) { done(); return }
        refreshLists()
        val step = object : Runnable {
            override fun run() {
                if (gen != renderGen || isFinishing) return
                val until = min(blocks.size, i + 30)
                while (i < until) pageBox.addView(blockView(blocks[i++], owner))
                if (i < blocks.size) pageBox.postOnAnimation(this) else { flushRender = null; done() }
            }
        }
        flushRender = {
            flushRender = null
            renderGen++ // hentikan potongan terjadwal
            while (i < blocks.size) pageBox.addView(blockView(blocks[i++], owner))
            done()
        }
        pageBox.postOnAnimation(step)
    }

    /** Selesaikan pembangunan bertahap sekarang juga — sebelum operasi yang mengubah struktur dokumen. */
    private var flushRender: (() -> Unit)? = null
    private fun ensureRendered() { flushRender?.invoke() }

    private fun blockView(b: EBlock, owner: Owner): View = when (b) {
        is EPara -> paraRow(b, owner).root
        is ETable -> tableView(b)
        is EImage -> newImageView(b, owner)
        is EBreak -> breakView(b, owner)
    }

    private fun paraRow(p: EPara, owner: Owner): ParaRow {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (p.pageBreak) root.add(breakLabel())
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val label = TextView(this).apply { includeFontPadding = true; visibility = View.GONE }
        val ed = ParaEdit(this, this)
        line.add(label, w = WRAP)
        line.add(ed, w = 0, weight = 1f)
        root.add(line)
        val imgs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.add(imgs)
        if (p.hasObject) root.add(tv("▣ Kotak teks / objek (tetap dipertahankan)", 10.5f, C.MUTED).apply {
            gravity = Gravity.CENTER; background = shape(C.SURFACE2, 6); setPadding(dp(6), dp(5), dp(6), dp(5))
        }, top = 2)
        val pr = ParaRow(root, label, ed, imgs)
        rows[p] = pr
        owners[p] = owner
        applyParaLook(p, relayout = false)
        ed.bind(p)
        renderParaImages(p)
        return pr
    }

    private fun breakLabel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        val dash = { View(context).apply { background = shape(C.LINE_STRONG, 1) } }
        add(dash(), w = 0, h = dp(1), weight = 1f)
        add(tv("Pemisah Halaman", 10f, C.MUTED), w = WRAP, side = 8)
        add(dash(), w = 0, h = dp(1), weight = 1f)
    }

    private fun breakView(b: EBreak, owner: Owner): View = breakLabel().apply {
        setOnClickListener {
            sheetActions("Pemisah Halaman", listOf("Hapus pemisah halaman" to { command { removeBlock(b, owner) } }))
        }
    }

    /** Terapkan gaya paragraf (gaya, rata, inden, spasi, daftar) ke tampilan. */
    private fun applyParaLook(p: EPara, relayout: Boolean = true) {
        val r = rows[p] ?: return
        val ed = r.editor
        val e = doc.effPara(p.pf)
        val base = doc.runBase(p.pf)
        ed.ptPx = ptPx()
        ed.base = base
        val cellFill = owners[p]?.cell?.fill
        ed.autoColor = if (cellFill != null && Color.luminance(cellFill) < 0.35f) Color.WHITE else Color.BLACK
        ed.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ed.ptPx * (base.sz ?: 22) / 2f)
        ed.typeface = DocFonts.get(base.font, base.b == true, base.i == true)
        ed.setTextColor(base.color.let { if (it == null || it == CharFmt.AUTO) ed.autoColor else it })
        ed.gravity = Gravity.TOP or when (e.align) { 1 -> Gravity.CENTER_HORIZONTAL; 2 -> Gravity.END; else -> Gravity.START }
        ed.justificationMode = if (e.align == 3) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE
        val line = e.line ?: 240
        val mult = if (e.lineRule == null || e.lineRule == "auto") line / 240f
        else ((line / 20f) * ed.ptPx) / (ed.ptPx * (base.sz ?: 22) / 2f * 1.17f)
        ed.setLineSpacing(0f, mult.coerceIn(0.8f, 3.2f))

        val maxIndent = (innerWidth() * 0.45f).toInt()
        val left = twPx(e.left).coerceIn(0, maxIndent)
        val first = twPx(e.firstLine).coerceIn(-left, maxIndent)
        val right = twPx(e.right).coerceIn(0, maxIndent)
        val isList = (e.numId ?: 0) > 0
        if (isList) {
            val hang = if (first < 0) -first else dp(18)
            r.label.minWidth = hang
            r.root.setPadding(max(0, left - hang), 0, right, 0)
            ed.updateFirstLine(0)
        } else {
            r.root.setPadding(max(0, left + min(0, first)), 0, right, 0)
            ed.updateFirstLine(max(0, first))
        }
        r.label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ed.textSize)
        r.label.typeface = DocFonts.get(base.font, base.b == true, false)
        r.label.setTextColor(ed.currentTextColor)
        r.label.setLineSpacing(0f, ed.lineSpacingMultiplier)
        val lp = r.root.layoutParams as? LinearLayout.LayoutParams
        val top = twPx(e.before).coerceAtMost(dp(40))
        val bottom = twPx(e.after).coerceAtMost(dp(40))
        if (lp != null) { lp.topMargin = top; lp.bottomMargin = bottom; r.root.layoutParams = lp }
        else r.root.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = top; bottomMargin = bottom }
        if (relayout) ed.relayout()
    }

    private fun refreshLists() {
        val labels = doc.listLabels()
        for ((p, r) in rows) {
            val l = labels[p]
            if (l == null) r.label.visibility = View.GONE
            else { r.label.visibility = View.VISIBLE; r.label.text = l }
        }
    }

    private fun renderParaImages(p: EPara) {
        val r = rows[p] ?: return
        r.images.removeAllViews()
        for (im in p.images) {
            val iv = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFFF2F3F6.toInt())
                setOnClickListener {
                    sheetActions("Gambar", listOf("Hapus gambar ini" to { command { p.images.remove(im); renderParaImages(p) } }))
                }
            }
            val wPx = if (im.cx > 0) min(innerWidth(), ((im.cx / 12700f) * ptPx()).toInt()) else innerWidth() / 2
            val hPx = if (im.cx > 0 && im.cy > 0) (wPx * im.cy.toDouble() / im.cx).toInt() else wPx * 2 / 3
            val gravity = when (doc.effPara(p.pf).align) { 1 -> Gravity.CENTER_HORIZONTAL; 2 -> Gravity.END; else -> Gravity.START }
            r.images.gravity = gravity
            r.images.add(iv, w = wPx, h = hPx, top = 2)
            loadImage(im.rId, wPx) { bmp -> if (bmp != null) { iv.setImageBitmap(bmp); iv.background = null } }
        }
    }

    private fun loadImage(rId: String, targetW: Int, done: (Bitmap?) -> Unit) {
        if (imgCache.containsKey(rId)) { done(imgCache[rId]); return }
        // Pool bersama (2–3 thread), bukan satu thread baru per gambar: dokumen bergambar banyak tidak membanjiri CPU/RAM.
        Perf.io.execute {
            val bmp = doc.imageBytes(rId)?.let { decodeSampled(it, targetW) }
            runOnUiThread { imgCache[rId] = bmp; if (!isFinishing) done(bmp) }
        }
    }

    private fun decodeSampled(b: ByteArray, targetW: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= targetW.coerceAtLeast(64)) s *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Throwable) { null }

    private fun newImageView(img: EImage, owner: Owner): View {
        val box = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(decodeSampled(img.bytes, innerWidth()))
            setOnClickListener {
                sheetActions("Gambar", listOf(
                    "Ukuran kecil (30%)" to { command { img.widthFrac = 0.3f; renderAll() } },
                    "Ukuran sedang (50%)" to { command { img.widthFrac = 0.5f; renderAll() } },
                    "Ukuran besar (75%)" to { command { img.widthFrac = 0.75f; renderAll() } },
                    "Selebar halaman (100%)" to { command { img.widthFrac = 1f; renderAll() } },
                    "Hapus gambar" to { command { removeBlock(img, owner) } }
                ))
            }
        }
        val w = (innerWidth() * img.widthFrac).toInt()
        box.add(iv, w = w, h = (w * img.hPx.toDouble() / img.wPx.coerceAtLeast(1)).toInt())
        return box
    }

    private fun tableView(t: ETable): View {
        val avail = innerWidth()
        val total = t.grid.sum().coerceAtLeast(1)
        val scale = avail.toFloat() / total
        val minCol = dp(56)
        val colPx = t.grid.map { max((it * scale).toInt(), minCol) }
        val table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (row in t.rows) {
            val rowBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            var col = 0
            for (cell in row.cells) {
                val wpx = (col until min(colPx.size, col + cell.span)).sumOf { colPx[it] }.takeIf { it > 0 } ?: dp(100)
                col += cell.span
                val cb = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(cell.fill ?: Color.WHITE)
                        setStroke(max(1, dp(1) / 2), if (t.bordered) 0xFF6B6F7A.toInt() else 0xFFD0D3DA.toInt())
                    }
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                }
                val owner = Owner(asBlocks(cell.paras), cb, cell, row, t)
                if (cell.paras.isEmpty()) cell.paras.add(EPara.blank(-1, ParaFmt()))
                cell.paras.forEach { p -> cb.addView(paraRow(p, owner).root) }
                rowBox.add(cb, w = wpx, h = MATCH)
            }
            table.add(rowBox, w = WRAP)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            setPadding(0, dp(4), 0, dp(4))
            addView(table)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asBlocks(l: MutableList<EPara>): MutableList<EBlock> = l as MutableList<EBlock>

    private fun allEditors(): List<ParaEdit> = rows.values.map { it.editor }

    // =========================================================================================
    // Fokus & seleksi
    // =========================================================================================

    private fun cur(): ParaEdit? = current?.takeIf { rows[it.para]?.editor === it }

    private fun focusEditor(e: ParaEdit, pos: Int) {
        e.requestFocus()
        runCatching { e.setSelection(pos.coerceIn(0, e.length())) }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(e, 0)
    }

    private fun focusFirst() {
        val p = doc.allParas().firstOrNull() ?: return
        rows[p]?.editor?.let { current = it; refreshTools() }
    }

    private fun focusEnd() {
        ensureRendered()
        val last = doc.body.lastOrNull()
        if (last !is EPara) {
            command { val np = EPara.blank(-1, ParaFmt()); doc.body.add(np); pageBox.addView(paraRow(np, Owner(doc.body, pageBox, null, null, null)).root) }
            rows[doc.body.last() as EPara]?.editor?.let { focusEditor(it, 0) }
        } else rows[last]?.editor?.let { focusEditor(it, it.length()) }
    }

    fun onSel(e: ParaEdit) {
        if (current !== e) pending = null
        current = e
        pending?.let { if (it.editor !== e || it.pos != e.selectionStart || e.selectionEnd != e.selectionStart) pending = null }
        updateTableTab()
        refreshTools()
    }

    private fun updateTableTab() {
        val inTable = cur()?.let { owners[it.para]?.table } != null
        if (inTable != tableTabShown) {
            tableTabShown = inTable
            tabViews[4].visibility = if (inTable) View.VISIBLE else View.GONE
            if (!inTable && tab == 4) showTab(0)
        }
    }

    /** Format karakter efektif (gaya + langsung) di posisi kursor/seleksi. */
    private fun curFmt(): CharFmt = fmtMemo ?: curFmtCompute().also { if (refreshing()) fmtMemo = it }
    private fun refreshing() = !refreshQueued && toolStatesRunning
    private var toolStatesRunning = false

    private fun curFmtCompute(): CharFmt {
        val e = cur() ?: return doc.runBase(ParaFmt())
        pending?.let { if (it.editor === e) return e.base.over(it.fmt) }
        val f = e.readFmts()
        val s = e.selectionStart.coerceAtLeast(0)
        val idx = if (e.selectionEnd > s) s else s - 1
        val direct = f.getOrNull(idx.coerceAtLeast(0)) ?: if (f.isEmpty()) CharFmt.EMPTY else f[0]
        return e.base.over(direct)
    }

    private fun curAlign(): Int = cur()?.let { doc.effPara(it.para.pf).align ?: 0 } ?: 0

    private fun curList(): Int {
        val e = cur() ?: return 0
        val id = doc.effPara(e.para.pf).numId ?: return 0
        if (id <= 0) return 0
        return if (doc.isBulletList(id)) 1 else 2
    }

    // =========================================================================================
    // Riwayat (urungkan / ulangi)
    // =========================================================================================

    /** Salin isi editor ke model — hanya editor yang benar-benar berubah (dokumen ratusan paragraf tetap ringan). */
    private fun syncAll() { for (r in rows.values) if (r.editor.dirty) r.editor.sync() }

    private fun snapshot(): Snap {
        syncAll()
        val all = doc.allParas()
        val c = cur()
        return Snap(doc.body.map { it.deepCopy() }, doc.page, c?.let { all.indexOf(it.para) } ?: -1, c?.selectionStart ?: 0)
    }

    private fun pushUndo() {
        undo.addLast(snapshot())
        while (undo.size > 60) undo.removeFirst()
        redo.clear()
        markDirty()
        updateUndoButtons()
    }

    /** Jalankan perubahan sebagai satu langkah yang bisa diurungkan. */
    private fun command(block: () -> Unit) {
        ensureRendered()
        typing = false
        handler.removeCallbacks(endTyping)
        pushUndo()
        inCommand = true
        try { block() } finally { inCommand = false }
        refreshLists()
        refreshTools()
        scheduleStatus()
    }

    fun beforeEdit(e: ParaEdit) {
        if (inCommand) return
        if (!typing) { pushUndo(); typing = true }
        handler.removeCallbacks(endTyping)
        handler.postDelayed(endTyping, 1200)
    }

    fun afterEdit(e: ParaEdit) {
        markDirty()
        scheduleStatus()
        refreshTools()
    }

    private fun scheduleStatus() { handler.removeCallbacks(statusTick); handler.postDelayed(statusTick, 500) }

    private fun restore(s: Snap) {
        doc.body.clear(); doc.body.addAll(s.body.map { it.deepCopy() }); doc.page = s.page
        applyPageLook()
        val focus = doc.allParas().getOrNull(s.focusIdx)
        renderAll(focus, s.focusPos)
    }

    private fun doUndo() {
        val s = undo.removeLastOrNull() ?: run { toast("Tidak ada yang bisa diurungkan"); return }
        typing = false
        redo.addLast(snapshot())
        restore(s)
        markDirty(); updateUndoButtons()
    }

    private fun doRedo() {
        val s = redo.removeLastOrNull() ?: run { toast("Tidak ada yang bisa diulangi"); return }
        typing = false
        undo.addLast(snapshot())
        restore(s)
        markDirty(); updateUndoButtons()
    }

    private fun updateUndoButtons() {
        if (!::undoBtn.isInitialized) return
        undoBtn.alpha = if (undo.isEmpty()) 0.4f else 1f
        redoBtn.alpha = if (redo.isEmpty()) 0.4f else 1f
    }

    private fun markDirty() {
        if (!dirty) { dirty = true; subTv.text = "Ada perubahan belum disimpan" }
        updateUndoButtons()
    }

    // =========================================================================================
    // Format karakter
    // =========================================================================================

    /** Rentang yang dikenai format: seleksi, atau kata di posisi kursor; null = format tertunda. */
    private fun targetRange(e: ParaEdit): IntRange? {
        val s = e.selectionStart; val en = e.selectionEnd
        if (s < 0) return null
        if (en > s) return s until en
        val t = e.text?.toString().orEmpty()
        fun isW(i: Int) = i in t.indices && (t[i].isLetterOrDigit() || t[i] == '\'' || t[i] == '-')
        if (!(isW(s - 1) && isW(s))) return null
        var a = s; while (isW(a - 1)) a--
        var b = s; while (isW(b)) b++
        return a until b
    }

    private fun applyChar(transform: (CharFmt) -> CharFmt) {
        val e = cur() ?: run { toast("Ketuk dulu teks yang ingin diubah"); return }
        val r = targetRange(e)
        if (r == null) {
            val base = pending?.takeIf { it.editor === e }?.fmt ?: e.readFmts().let { f ->
                val i = e.selectionStart - 1
                if (i >= 0 && i < f.size) f[i] else f.firstOrNull() ?: CharFmt.EMPTY
            }
            pending = Pending(e, e.selectionStart, clean(e, transform(base)))
            return
        }
        command {
            val f = e.readFmts()
            for (k in r) if (k < f.size) f[k] = clean(e, transform(f[k]))
            e.setFmts(f)
            e.relayout()
        }
    }

    /** Nilai yang sama dengan gaya paragraf dijadikan "ikut gaya" (null) agar berkas tetap bersih. */
    private fun clean(e: ParaEdit, f: CharFmt): CharFmt {
        val b = e.base
        return CharFmt(
            f.b.takeIf { it != b.b }, f.i.takeIf { it != b.i }, f.u.takeIf { it != b.u }, f.s.takeIf { it != b.s },
            f.color.takeIf { it != b.color }, f.bg.takeIf { it != b.bg }, f.sz.takeIf { it != b.sz },
            f.font.takeIf { it != b.font }, f.va.takeIf { it != b.va }
        )
    }

    private fun toggle(set: (CharFmt, Boolean) -> CharFmt) {
        val e = cur() ?: return
        val now = curFmt()
        val probe = set(CharFmt.EMPTY, true)
        val on = when {
            probe.b != null -> now.b != true
            probe.i != null -> now.i != true
            probe.u != null -> now.u != true
            else -> now.s != true
        }
        applyChar { set(e.base.over(it), on) }
    }

    private fun toggleVa(v: Int) {
        val e = cur() ?: return
        val now = curFmt().va ?: 0
        applyChar { e.base.over(it).copy(va = if (now == v) 0 else v) }
    }

    private fun growFont(dir: Int) {
        val e = cur() ?: return
        applyChar { f ->
            val sz = e.base.over(f).sz ?: 22
            val pts = SIZES.map { it * 2 }
            val next = if (dir > 0) pts.firstOrNull { it > sz } ?: (sz + 4) else pts.lastOrNull { it < sz } ?: max(2, sz - 2)
            e.base.over(f).copy(sz = next)
        }
    }

    private fun clearFormat() {
        val e = cur() ?: return
        val r = targetRange(e) ?: run { pending = Pending(e, e.selectionStart, CharFmt.EMPTY); return }
        command {
            val f = e.readFmts()
            for (k in r) if (k < f.size) f[k] = CharFmt.EMPTY
            e.setFmts(f); e.relayout()
        }
    }

    private fun pickFont() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_note, C.ACCENT, C.BLUE_BG, "Pilih Font", "Berlaku untuk teks terpilih atau kata di kursor", dismiss)
        val list = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
        val docFonts = doc.allParas().flatMap { p -> p.fmts.mapNotNull { it.font } }.toSet()
        val now = curFmt().font
        (listOfNotNull(now) + FONTS + docFonts).distinct().forEach { name ->
            list.add(tv(name, 17f, C.TEXT).apply {
                typeface = DocFonts.get(name, false, false)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = if (name == now) shape(C.BLUE_BG, 10) else null
                setOnClickListener { dismiss(); val e = cur(); applyChar { (e?.base ?: CharFmt()).over(it).copy(font = name) }; refreshTools() }
            })
        }
        body.add(list)
    }

    private fun pickSize() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_note, C.ACCENT, C.BLUE_BG, "Ukuran Huruf", "Dalam poin (pt), seperti di Word", dismiss)
        val now = curFmt().sz ?: 22
        body.add(chipGrid(SIZES.map { "$it" }, SIZES.indexOfFirst { it * 2 == now }, 5) { i ->
            dismiss(); val e = cur(); applyChar { (e?.base ?: CharFmt()).over(it).copy(sz = SIZES[i] * 2) }; refreshTools()
        })
    }

    private fun pickColor() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_spark, C.ACCENT, C.BLUE_BG, "Warna Teks", "Pilih warna untuk teks terpilih", dismiss)
        body.add(swatches(listOf<Int?>(null) + TEXT_COLORS, "Otomatis") { c ->
            dismiss(); val e = cur(); applyChar { (e?.base ?: CharFmt()).over(it).copy(color = c ?: CharFmt.AUTO) }; refreshTools()
        })
    }

    private fun pickHighlight() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_spark, C.ACCENT, C.BLUE_BG, "Warna Stabilo", "Tandai teks seperti spidol stabilo", dismiss)
        body.add(swatches(listOf<Int?>(null) + HIGHLIGHTS.map { Fmt.HIGHLIGHT.getValue(it) }, "Tanpa stabilo") { c ->
            dismiss(); val e = cur(); applyChar { (e?.base ?: CharFmt()).over(it).copy(bg = c ?: CharFmt.NONE) }; refreshTools()
        })
    }

    private fun swatches(colors: List<Int?>, nullLabel: String, onPick: (Int?) -> Unit): View {
        val grid = vbox().apply { setPadding(dp(18), dp(4), dp(18), dp(24)) }
        var row: LinearLayout? = null
        colors.forEachIndexed { i, c ->
            if (i % 5 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; grid.add(row!!, top = 8) }
            val v = if (c == null) tv(nullLabel, 11f, C.TEXT, true).apply {
                gravity = Gravity.CENTER; background = shape(C.SURFACE, 10, C.LINE_STRONG, 1)
            } else View(this).apply { background = shape(c, 10, 0x22000000, 1) }
            v.setOnClickListener { onPick(c) }
            row!!.add(v, w = 0, h = dp(44), weight = 1f, side = 4)
        }
        val rest = (5 - colors.size % 5) % 5
        repeat(rest) { row?.add(View(this), w = 0, h = dp(44), weight = 1f, side = 4) }
        return grid
    }

    private fun chipGrid(items: List<String>, selected: Int, perRow: Int, onPick: (Int) -> Unit): View {
        val grid = vbox().apply { setPadding(dp(16), dp(4), dp(16), dp(22)) }
        var row: LinearLayout? = null
        items.forEachIndexed { i, s ->
            if (i % perRow == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; grid.add(row!!, top = 8) }
            row!!.add(tv(s, 14f, if (i == selected) C.ON_PRIMARY else C.TEXT, true).apply {
                gravity = Gravity.CENTER
                background = shape(if (i == selected) C.ACCENT else C.SURFACE2, 10)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { onPick(i) }
            }, w = 0, weight = 1f, side = 3)
        }
        val rest = (perRow - items.size % perRow) % perRow
        repeat(rest) { row?.add(View(this), w = 0, weight = 1f, side = 3) }
        return grid
    }

    // =========================================================================================
    // Format paragraf
    // =========================================================================================

    private fun updatePara(e: ParaEdit, change: (ParaFmt) -> ParaFmt) {
        command {
            e.sync()
            e.para.pf = change(e.para.pf)
            applyParaLook(e.para)
            renderParaImages(e.para)
        }
    }

    private fun setAlign(a: Int) {
        val e = cur() ?: return
        updatePara(e) { it.copy(align = if (doc.styles.paraBase(it.style).align == a) null else a) }
    }

    private fun toggleList(bullet: Boolean) {
        val e = cur() ?: return
        val curKind = curList()
        updatePara(e) { pf ->
            val styleNum = doc.styles.paraBase(pf.style).numId
            if ((bullet && curKind == 1) || (!bullet && curKind == 2)) {
                pf.copy(numId = if (styleNum != null && styleNum > 0) 0 else null, ilvl = null)
            } else pf.copy(numId = doc.numIdFor(bullet), ilvl = pf.ilvl ?: 0, left = null, firstLine = null)
        }
    }

    private fun indent(dir: Int) {
        val e = cur() ?: return
        val eff = doc.effPara(e.para.pf)
        if ((eff.numId ?: 0) > 0) {
            updatePara(e) { it.copy(numId = eff.numId, ilvl = ((eff.ilvl ?: 0) + dir).coerceIn(0, 8)) }
            return
        }
        updatePara(e) { it.copy(left = ((eff.left ?: 0) + dir * 720).coerceIn(0, 720 * 8)) }
    }

    private fun pickStyle() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_note, C.ACCENT, C.BLUE_BG, "Gaya Paragraf", "Judul otomatis masuk ke navigasi & daftar isi Word", dismiss)
        val list = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
        val curName = doc.styles.name(cur()?.para?.pf?.style)
        for (s in StdStyle.entries) {
            val id = doc.styles.byName(s.wordName)
            val f = if (id != null) doc.styles.runBase(id) else when (s) {
                StdStyle.TITLE -> CharFmt(sz = 52, color = 0xFF17365D.toInt())
                StdStyle.SUBTITLE -> CharFmt(i = true, sz = 26, color = 0xFF5A5A5A.toInt())
                StdStyle.H1 -> CharFmt(b = true, sz = 32, color = 0xFF2F5496.toInt())
                StdStyle.H2 -> CharFmt(b = true, sz = 26, color = 0xFF2F5496.toInt())
                StdStyle.H3 -> CharFmt(b = true, sz = 24, color = 0xFF1F3763.toInt())
                StdStyle.QUOTE -> CharFmt(i = true, color = 0xFF404040.toInt())
                StdStyle.NORMAL -> doc.runBase(ParaFmt())
            }
            val sel = curName.equals(s.wordName, true)
            list.add(tv(s.label, ((f.sz ?: 22) / 2f * 1.1f).coerceIn(13f, 26f), (f.color ?: Color.BLACK).let { if (it == CharFmt.AUTO) Color.BLACK else it }).apply {
                typeface = DocFonts.get(f.font, f.b == true, f.i == true)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = if (sel) shape(Color.WHITE, 10, C.PRIMARY, 2) else shape(Color.WHITE, 10, C.LINE, 1) // "kertas" putih: warna asli gaya tetap terbaca di tema gelap
                setOnClickListener {
                    dismiss()
                    val e = cur() ?: return@setOnClickListener
                    val sid = doc.styleId(s)
                    updatePara(e) { it.copy(style = sid) }
                }
            }, top = 6)
        }
        body.add(list)
    }

    private fun applyToParas(all: Boolean, change: (ParaFmt) -> ParaFmt) {
        val e = cur()
        command {
            syncAll()
            val targets = if (all || e == null) doc.allParas() else listOf(e.para)
            targets.forEach { p -> p.pf = change(p.pf); applyParaLook(p) }
        }
    }

    private fun pickLineSpacing() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_tune, C.ACCENT, C.BLUE_BG, "Spasi Baris", "Jarak antarbaris dalam paragraf", dismiss)
        val vals = listOf(1.0f, 1.15f, 1.5f, 2.0f, 2.5f, 3.0f)
        val now = cur()?.let { doc.effPara(it.para.pf) }?.let { if (it.lineRule == null || it.lineRule == "auto") (it.line ?: 240) / 240f else -1f } ?: 1f
        val all = CheckBox(this).apply { text = "Terapkan ke seluruh dokumen"; setTextColor(C.TEXT) }
        body.add(chipGrid(vals.map { "%.2f".format(Locale("id"), it).trimEnd('0').trimEnd(',') }, vals.indexOfFirst { kotlin.math.abs(it - now) < 0.02f }, 3) { i ->
            dismiss(); applyToParas(all.isChecked) { it.copy(line = (vals[i] * 240).roundToInt(), lineRule = "auto") }
        })
        body.add(all, side = 18)
        body.add(View(this), h = dp(16))
    }

    private fun pickParaSpacing() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_tune, C.ACCENT, C.BLUE_BG, "Spasi Paragraf", "Jarak sebelum & sesudah paragraf", dismiss)
        val opts = listOf(0, 6, 8, 12, 18, 24)
        val all = CheckBox(this).apply { text = "Terapkan ke seluruh dokumen"; setTextColor(C.TEXT) }
        val eff = cur()?.let { doc.effPara(it.para.pf) }
        body.add(tv("Sebelum paragraf (pt)", 13f, C.TEXT, true), side = 18)
        body.add(chipGrid(opts.map { "$it" }, opts.indexOf((eff?.before ?: 0) / 20), 6) { i ->
            dismiss(); applyToParas(all.isChecked) { it.copy(before = opts[i] * 20) }
        })
        body.add(tv("Sesudah paragraf (pt)", 13f, C.TEXT, true), side = 18)
        body.add(chipGrid(opts.map { "$it" }, opts.indexOf((eff?.after ?: 0) / 20), 6) { i ->
            dismiss(); applyToParas(all.isChecked) { it.copy(after = opts[i] * 20) }
        })
        body.add(all, side = 18)
        body.add(View(this), h = dp(16))
    }

    private fun pickIndent() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_tune, C.ACCENT, C.BLUE_BG, "Inden Paragraf", "Jarak teks dari margin kiri", dismiss)
        val e = cur()
        val eff = e?.let { doc.effPara(it.para.pf) }
        val leftOpts = listOf(0, 1, 2, 3, 4)
        body.add(tv("Inden kiri (cm)", 13f, C.TEXT, true), side = 18)
        body.add(chipGrid(leftOpts.map { "$it" }, leftOpts.indexOf(((eff?.left ?: 0) / 567f).roundToInt()), 5) { i ->
            dismiss(); e?.let { ed -> updatePara(ed) { it.copy(left = leftOpts[i] * 567) } }
        })
        body.add(tv("Baris pertama", 13f, C.TEXT, true), side = 18)
        val firstOpts = listOf("Tidak ada" to 0, "Menjorok 1,25 cm" to 709, "Gantung 1,25 cm" to -709)
        body.add(chipGrid(firstOpts.map { it.first }, firstOpts.indexOfFirst { it.second == (eff?.firstLine ?: 0) }, 3) { i ->
            dismiss(); e?.let { ed -> updatePara(ed) { it.copy(firstLine = firstOpts[i].second) } }
        })
        body.add(View(this), h = dp(16))
    }

    // =========================================================================================
    // Struktur: Enter, Backspace, sisip blok
    // =========================================================================================

    /** Paragraf baru setelah [p] meniru formatnya (judul -> Normal, seperti Word). */
    private fun nextPf(p: EPara, atEnd: Boolean): ParaFmt =
        if (atEnd && doc.styles.headingLevel(p.pf.style) > 0) ParaFmt() else p.pf

    fun splitAtNewlines(e: ParaEdit, start: Int, count: Int) {
        ensureRendered()
        val p = e.para
        val owner = owners[p] ?: return
        if (owner.cell?.locked == true) return // sel bertabel bersarang: biarkan sebagai baris baru
        e.sync()
        val t = p.text
        val nl = (start until min(t.length, start + count)).filter { t[it] == '\n' }
        if (nl.isEmpty()) return
        // Enter pada butir daftar kosong -> keluar dari daftar
        if (nl.size == 1 && t.length == 1 && (doc.effPara(p.pf).numId ?: 0) > 0) {
            e.suppress = true; e.text?.clear(); e.suppress = false
            p.text = ""; p.fmts = emptyArray()
            val styleNum = doc.styles.paraBase(p.pf.style).numId
            p.pf = p.pf.copy(numId = if (styleNum != null && styleNum > 0) 0 else null, ilvl = null)
            applyParaLook(p); refreshLists(); refreshTools()
            return
        }
        val pieces = ArrayList<Pair<String, Array<CharFmt>>>()
        var from = 0
        for (k in nl + t.length) {
            pieces.add(t.substring(from, k) to p.fmts.copyOfRange(from, k))
            from = k + 1
        }
        val (firstText, firstFmts) = pieces[0]
        p.text = firstText; p.fmts = firstFmts
        applyParaLook(p, relayout = false)
        e.bind(p)
        var idx = owner.list.indexOf(p)
        var last: EPara = p
        val tpl = if (p.origin >= 0) p.origin else p.tpl
        for (k in 1 until pieces.size) {
            val (pt, pfm) = pieces[k]
            val np = EPara(-1, pt, pfm, nextPf(p, k == pieces.size - 1 && pt.isEmpty()), tpl)
            idx++
            owner.list.add(idx, np)
            owner.box.addView(paraRow(np, owner).root, idx)
            last = np
        }
        refreshLists()
        rows[last]?.editor?.let { ed ->
            // Format karakter berlanjut ke paragraf baru
            if (last.text.isEmpty()) {
                val keep = firstFmts.lastOrNull() ?: CharFmt.EMPTY
                ed.setFmts(emptyArray(), keep)
            }
            focusEditor(ed, 0)
        }
        scheduleStatus()
    }

    fun backspaceAtStart(e: ParaEdit) {
        ensureRendered()
        val p = e.para
        val owner = owners[p] ?: return
        val eff = doc.effPara(p.pf)
        if ((eff.numId ?: 0) > 0) {
            command {
                e.sync()
                val styleNum = doc.styles.paraBase(p.pf.style).numId
                p.pf = p.pf.copy(numId = if (styleNum != null && styleNum > 0) 0 else null, ilvl = null)
                applyParaLook(p)
            }
            return
        }
        if ((p.pf.left ?: 0) > 0 || (p.pf.firstLine ?: 0) > 0) {
            command { e.sync(); p.pf = p.pf.copy(left = null, firstLine = null); applyParaLook(p) }
            return
        }
        val i = owner.list.indexOf(p)
        if (i <= 0) return
        when (val prev = owner.list[i - 1]) {
            is EPara -> {
                if (owner.cell?.locked == true) return
                if (p.locked || p.images.isNotEmpty() || p.hasObject) {
                    rows[prev]?.editor?.let { focusEditor(it, it.length()) }; return
                }
                command {
                    e.sync(); rows[prev]?.editor?.sync()
                    val join = prev.text.length
                    prev.text += p.text
                    prev.fmts = prev.fmts + p.fmts
                    owner.list.removeAt(i)
                    rows.remove(p)?.let { owner.box.removeView(it.root) }
                    owners.remove(p)
                    rows[prev]?.editor?.let { ed -> applyParaLook(prev, relayout = false); ed.bind(prev); focusEditor(ed, join) }
                }
            }
            is EBreak, is EImage -> command { removeBlock(prev, owner) }
            is ETable -> {
                val lastCellPara = prev.rows.lastOrNull()?.cells?.lastOrNull()?.paras?.lastOrNull()
                lastCellPara?.let { lp -> rows[lp]?.editor?.let { focusEditor(it, it.length()) } }
            }
        }
    }

    private fun removeBlock(b: EBlock, owner: Owner) {
        val i = owner.list.indexOf(b)
        if (i < 0) return
        owner.list.removeAt(i)
        owner.box.removeViewAt(i)
        if (owner.list.isEmpty() && owner.cell == null) {
            val np = EPara.blank(-1, ParaFmt()); owner.list.add(np); owner.box.addView(paraRow(np, owner).root)
        }
        refreshLists()
    }

    /** Sisipkan blok setelah paragraf saat ini (bila di tabel: setelah tabel). */
    private fun insertBlocksAfterCurrent(blocks: List<EBlock>) {
        val e = cur()
        var idx = doc.body.size - 1
        if (e != null) {
            val o = owners[e.para]
            idx = if (o?.table != null) doc.body.indexOf(o.table) else doc.body.indexOf(e.para)
        }
        val root = Owner(doc.body, pageBox, null, null, null)
        var at = idx + 1
        for (b in blocks) {
            doc.body.add(at, b)
            pageBox.addView(blockView(b, root), at)
            at++
        }
        // Pastikan selalu ada paragraf sesudah tabel/gambar/pemisah di akhir dokumen
        if (doc.body.last() !is EPara) {
            val np = EPara.blank(-1, ParaFmt())
            doc.body.add(np); pageBox.addView(paraRow(np, root).root)
        }
        refreshLists()
        updateStatus()
    }

    private fun insertSoftBreak() {
        val e = cur() ?: return
        softBreak = true
        val s = e.selectionStart.coerceAtLeast(0); val en = e.selectionEnd.coerceAtLeast(s)
        e.text?.replace(s, en, "\n")
        softBreak = false
    }

    private fun insertText(s: String) {
        val e = cur() ?: run { toast("Ketuk dulu posisi di dokumen"); return }
        val a = e.selectionStart.coerceAtLeast(0); val b = e.selectionEnd.coerceAtLeast(a)
        e.text?.replace(a, b, s)
    }

    private fun insertParagraphAfter() {
        val e = cur() ?: return
        command {
            e.sync()
            val owner = owners[e.para] ?: return@command
            val np = EPara(-1, "", emptyArray(), nextPf(e.para, true), if (e.para.origin >= 0) e.para.origin else e.para.tpl)
            val i = owner.list.indexOf(e.para) + 1
            owner.list.add(i, np)
            owner.box.addView(paraRow(np, owner).root, i)
            rows[np]?.editor?.let { focusEditor(it, 0) }
        }
    }

    private fun insertPageBreak() = command {
        syncAll()
        insertBlocksAfterCurrent(listOf(EBreak()))
    }

    private fun pickSymbol() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_note, C.ACCENT, C.BLUE_BG, "Sisipkan Simbol", "Ketuk simbol untuk menyisipkan di posisi kursor", dismiss)
        body.add(chipGrid(SYMBOLS, -1, 6) { i -> dismiss(); insertText(SYMBOLS[i]) })
    }

    private fun pickDate() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_clock, C.ACCENT, C.BLUE_BG, "Tanggal & Waktu", "Pilih format", dismiss)
        val id = Locale("id", "ID")
        val now = Date()
        val opts = listOf(
            SimpleDateFormat("d MMMM yyyy", id).format(now),
            SimpleDateFormat("EEEE, d MMMM yyyy", id).format(now),
            SimpleDateFormat("dd/MM/yyyy", id).format(now),
            SimpleDateFormat("dd-MM-yyyy HH:mm", id).format(now),
            SimpleDateFormat("HH:mm", id).format(now)
        )
        val list = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
        opts.forEach { s ->
            list.add(tv(s, 15f, C.TEXT).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12)); background = shape(C.SURFACE2, 10)
                setOnClickListener { dismiss(); insertText(s) }
            }, top = 6)
        }
        body.add(list)
    }

    // ------------------------------- tabel -----------------------------------------------

    private fun pickTable() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_grid, C.ACCENT, C.BLUE_BG, "Sisipkan Tabel", "Atur jumlah baris & kolom", dismiss)
        var r = 3; var c = 3
        val pad = vbox().apply { setPadding(dp(20), dp(4), dp(20), dp(20)) }
        val preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = CheckBox(this).apply { text = "Baris pertama sebagai judul (tebal & berwarna)"; isChecked = true; setTextColor(C.TEXT) }
        fun drawPreview() {
            preview.removeAllViews()
            for (i in 0 until r) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (j in 0 until c) row.add(View(this).apply {
                    background = shape(if (i == 0 && header.isChecked) C.BLUE_BG else Color.WHITE, 2, 0xFF6B6F7A.toInt(), 1)
                }, w = 0, h = dp(18), weight = 1f)
                preview.add(row)
            }
        }
        fun stepper(label: String, get: () -> Int, set: (Int) -> Unit): View {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val v = tv("${get()}", 18f, C.TEXT, true).apply { gravity = Gravity.CENTER }
            row.add(tv(label, 14f, C.TEXT, true), w = 0, weight = 1f)
            row.add(tv("−", 22f, C.ACCENT, true).apply { gravity = Gravity.CENTER; background = shape(C.SURFACE2, 10)
                setOnClickListener { set((get() - 1).coerceAtLeast(1)); v.text = "${get()}"; drawPreview() } }, w = dp(44), h = dp(40))
            row.add(v, w = dp(48))
            row.add(tv("+", 22f, C.ACCENT, true).apply { gravity = Gravity.CENTER; background = shape(C.SURFACE2, 10)
                setOnClickListener { set((get() + 1).coerceAtMost(30)); v.text = "${get()}"; drawPreview() } }, w = dp(44), h = dp(40))
            return row
        }
        pad.add(stepper("Baris", { r }, { r = it }))
        pad.add(stepper("Kolom", { c }, { c = min(it, 10) }), top = 8)
        pad.add(header, top = 8)
        header.setOnCheckedChangeListener { _, _ -> drawPreview() }
        pad.add(preview, top = 10)
        pad.add(btn("Sisipkan Tabel", true, C.ACCENT, R.drawable.ic_plus) {
            dismiss(); insertTable(r, c, header.isChecked)
        }, top = 16)
        drawPreview()
        body.add(pad)
    }

    private fun insertTable(r: Int, c: Int, header: Boolean) {
        val tw = doc.page?.textWidth ?: 9026
        val colW = tw / c
        val t = ETable(-1, MutableList(r) { ri ->
            ERow(-1, MutableList(c) {
                ECell(-1, arrayListOf(EPara.blank(-1, ParaFmt(after = 0), if (header && ri == 0) CharFmt(b = true) else CharFmt.EMPTY)),
                    fill = if (header && ri == 0) 0xFFD9E2F3.toInt() else null)
            })
        }, MutableList(c) { colW }, true)
        command {
            syncAll()
            insertBlocksAfterCurrent(listOf(t, EPara.blank(-1, ParaFmt())))
            t.rows[0].cells[0].paras[0].let { p -> rows[p]?.editor?.let { focusEditor(it, 0) } }
        }
    }

    private fun tableOp(op: String) {
        val e = cur() ?: return
        val o = owners[e.para] ?: return
        val t = o.table ?: return
        val row = o.row ?: return
        val cell = o.cell ?: return
        val ri = t.rows.indexOf(row)
        val ci = row.cells.indexOf(cell)
        if (op != "delete" && op != "rowDel" && !t.simple) {
            toast("Tabel ini memiliki sel gabungan; ubah jumlah kolom/baris di Word desktop."); return
        }
        if (op == "rowDel" && !t.simple && t.rows.size > 1) { toast("Tabel ini memiliki sel gabungan; baris tidak bisa dihapus di sini."); return }
        command {
            syncAll()
            when (op) {
                "rowAdd" -> {
                    val nr = ERow(-1, row.cells.mapTo(ArrayList()) { c ->
                        val tp = c.paras.firstOrNull()
                        ECell(-1, arrayListOf(EPara.blank(tp?.let { if (it.origin >= 0) it.origin else it.tpl } ?: -1, tp?.pf ?: ParaFmt(),
                            tp?.fmts?.firstOrNull() ?: CharFmt.EMPTY)), c.span, false, if (ri == 0 && t.rows.size > 1) t.rows[1].cells.getOrNull(row.cells.indexOf(c))?.fill else c.fill,
                            if (c.origin >= 0) c.origin else c.tplCell)
                    }, if (row.origin >= 0) row.origin else row.tplRow)
                    t.rows.add(ri + 1, nr)
                }
                "rowDel" -> if (t.rows.size > 1) t.rows.removeAt(ri) else doc.body.remove(t)
                "colAdd" -> {
                    val w = t.grid[ci]
                    t.grid[ci] = w - w / 2; t.grid.add(ci + 1, w / 2)
                    for (rw in t.rows) {
                        val src = rw.cells.getOrNull(ci) ?: continue
                        val tp = src.paras.firstOrNull()
                        rw.cells.add(ci + 1, ECell(-1, arrayListOf(EPara.blank(tp?.let { if (it.origin >= 0) it.origin else it.tpl } ?: -1, tp?.pf ?: ParaFmt(),
                            tp?.fmts?.firstOrNull() ?: CharFmt.EMPTY)), 1, false, src.fill, if (src.origin >= 0) src.origin else src.tplCell))
                    }
                }
                "colDel" -> {
                    if (t.grid.size <= 1) doc.body.remove(t)
                    else {
                        val w = t.grid.removeAt(ci)
                        val nb = if (ci < t.grid.size) ci else ci - 1
                        t.grid[nb] = t.grid[nb] + w
                        for (rw in t.rows) if (ci < rw.cells.size) rw.cells.removeAt(ci)
                    }
                }
                "delete" -> doc.body.remove(t)
            }
            renderAll()
            if (op == "rowAdd") t.rows.getOrNull(ri + 1)?.cells?.getOrNull(ci)?.paras?.firstOrNull()?.let { p -> rows[p]?.editor?.let { focusEditor(it, 0) } }
        }
    }

    // ------------------------------- gambar & tanda tangan -------------------------------

    private fun insertImageFrom(uri: Uri) {
        work("Menyiapkan gambar…", {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Gambar tidak dapat dibaca")
            prepareImage(bytes)
        }) { res ->
            res.onSuccess { img -> command { syncAll(); insertBlocksAfterCurrent(listOf(img)) } }.onFailure { fail(it) }
        }
    }

    /** Perkecil gambar besar agar dokumen tetap ringan; PNG transparan dipertahankan. */
    private fun prepareImage(bytes: ByteArray): EImage {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) error("Format gambar tidak didukung")
        val isPng = o.outMimeType == "image/png"
        if (o.outWidth <= 1600 && (o.outMimeType == "image/jpeg" || isPng) && bytes.size < 1_500_000)
            return EImage(bytes, if (isPng) "png" else "jpeg", o.outWidth, o.outHeight, 0.6f)
        var s = 1
        while (o.outWidth / (s * 2) >= 1600) s *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: error("Gambar rusak")
        if (bmp.width > 1600) bmp = Bitmap.createScaledBitmap(bmp, 1600, (bmp.height * 1600.0 / bmp.width).toInt(), true)
        val out = ByteArrayOutputStream()
        if (isPng) bmp.compress(Bitmap.CompressFormat.PNG, 100, out) else bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return EImage(out.toByteArray(), if (isPng) "png" else "jpeg", bmp.width, bmp.height, 0.6f)
    }

    private fun drawSignature() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_sign, C.ACCENT, C.BLUE_BG, "Tanda Tangan", "Gambar tanda tangan dengan jari", dismiss)
        val pad = vbox().apply { setPadding(dp(18), dp(4), dp(18), dp(20)) }
        val sv = SignatureView(this)
        pad.add(sv, h = dp(200))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.add(btn("Ulangi", false, C.ACCENT) { sv.clear() }, w = 0, weight = 1f, side = 4)
        row.add(btn("Sisipkan", true, C.ACCENT, R.drawable.ic_check) {
            val bmp = sv.toBitmap() ?: run { toast("Tanda tangan masih kosong"); return@btn }
            val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            dismiss()
            command { syncAll(); insertBlocksAfterCurrent(listOf(EImage(out.toByteArray(), "png", bmp.width, bmp.height, 0.3f))) }
        }, w = 0, weight = 1f, side = 4)
        pad.add(row, top = 12)
        body.add(pad)
    }

    // =========================================================================================
    // Tata letak halaman
    // =========================================================================================

    private fun setPage(p: PageSetup) = command {
        syncAll()
        doc.page = p
        applyPageLook()
        renderAll(cur()?.para)
    }

    private fun pickPaper() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_doc, C.ACCENT, C.BLUE_BG, "Ukuran Kertas", "Ukuran halaman saat dicetak", dismiss)
        val pg = doc.page ?: PageSetup(11906, 16838, false, 1440, 1440, 1440, 1440)
        val list = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
        PAPERS.forEach { pp ->
            val sel = (pp.w == pg.w && pp.h == pg.h) || (pp.w == pg.h && pp.h == pg.w)
            list.add(tv("${pp.label}   ·   ${"%.1f".format(pp.w / 567f)} × ${"%.1f".format(pp.h / 567f)} cm", 15f, if (sel) C.ACCENT else C.TEXT, sel).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = if (sel) shape(C.BLUE_BG, 10, C.ACCENT, 1) else shape(C.SURFACE2, 10)
                setOnClickListener {
                    dismiss()
                    setPage(if (pg.landscape) pg.copy(w = pp.h, h = pp.w) else pg.copy(w = pp.w, h = pp.h))
                }
            }, top = 6)
        }
        body.add(list)
    }

    private fun pickOrientation() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_rotate, C.ACCENT, C.BLUE_BG, "Orientasi", "Arah halaman", dismiss)
        val pg = doc.page ?: PageSetup(11906, 16838, false, 1440, 1440, 1440, 1440)
        body.add(chipGrid(listOf("Potret (tegak)", "Lanskap (mendatar)"), if (pg.landscape) 1 else 0, 2) { i ->
            dismiss()
            val land = i == 1
            if (land != pg.landscape) setPage(pg.copy(w = pg.h, h = pg.w, landscape = land))
        })
    }

    private fun pickMargins() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_crop, C.ACCENT, C.BLUE_BG, "Margin", "Jarak teks dari tepi kertas", dismiss)
        val pg = doc.page ?: PageSetup(11906, 16838, false, 1440, 1440, 1440, 1440)
        val list = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
        fun cm(tw: Int) = "%.2f".format(Locale("id"), tw / 567f).trimEnd('0').trimEnd(',')
        list.add(tv("Saat ini: atas ${cm(pg.top)} · bawah ${cm(pg.bottom)} · kiri ${cm(pg.left)} · kanan ${cm(pg.right)} cm", 12f, C.MUTED), top = 2)
        MARGINS.forEach { m ->
            val sel = m.t == pg.top && m.r == pg.right && m.b == pg.bottom && m.l == pg.left
            val item = vbox().apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = if (sel) shape(C.BLUE_BG, 10, C.ACCENT, 1) else shape(C.SURFACE2, 10)
                setOnClickListener { dismiss(); setPage(pg.copy(top = m.t, right = m.r, bottom = m.b, left = m.l)) }
            }
            item.add(tv(m.label, 15f, C.TEXT, true)); item.add(tv(m.desc, 12f, C.MUTED))
            list.add(item, top = 6)
        }
        body.add(list)
    }

    // =========================================================================================
    // Tinjau: cari & ganti, jumlah kata, bacakan, cetak, panduan
    // =========================================================================================

    private fun toggleFindBar() {
        findBar?.let { (it.parent as? ViewGroup)?.removeView(it); findBar = null; return }
        val bar = vbox().apply { setBackgroundColor(C.SURFACE); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val find = EditText(this).apply { hint = "Cari kata…"; isSingleLine = true; textSize = 14f; background = shape(C.SURFACE2, 8, C.LINE_STRONG, 1); setTextColor(C.TEXT); setHintTextColor(C.MUTED); minHeight = dp(44); setPadding(dp(10), dp(6), dp(10), dp(6)) }
        val repl = EditText(this).apply { hint = "Ganti dengan…"; isSingleLine = true; textSize = 14f; background = shape(C.SURFACE2, 8, C.LINE_STRONG, 1); setTextColor(C.TEXT); setHintTextColor(C.MUTED); minHeight = dp(44); setPadding(dp(10), dp(6), dp(10), dp(6)) }
        val caseBox = CheckBox(this).apply { text = "Huruf besar/kecil sama persis"; textSize = 12f; setTextColor(C.MUTED) }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r1.add(find, w = 0, weight = 1f)
        r1.add(smallBtn("Berikutnya") { findNext(find.text.toString(), caseBox.isChecked) }, w = WRAP, side = 4)
        r1.add(tv("✕", 18f, C.MUTED, true).apply { setPadding(dp(8), dp(4), dp(4), dp(4)); setOnClickListener { toggleFindBar() } }, w = WRAP)
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r2.add(repl, w = 0, weight = 1f)
        r2.add(smallBtn("Ganti") { replaceOne(find.text.toString(), repl.text.toString(), caseBox.isChecked) }, w = WRAP, side = 4)
        r2.add(smallBtn("Semua") { replaceAll(find.text.toString(), repl.text.toString(), caseBox.isChecked) }, w = WRAP)
        bar.add(r1); bar.add(r2, top = 6); bar.add(caseBox)
        val root = (scroll.parent as ViewGroup).parent as LinearLayout
        root.addView(bar, root.indexOfChild(scroll.parent as View), LinearLayout.LayoutParams(MATCH, WRAP))
        findBar = bar
        find.requestFocus()
    }

    private fun smallBtn(label: String, onClick: () -> Unit) = tv(label, 13f, C.ON_PRIMARY, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(44)
        background = shape(C.ACCENT, 8); setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { onClick() }
    }

    private fun findNext(q: String, matchCase: Boolean): Boolean {
        ensureRendered()
        if (q.isEmpty()) return false
        val ps = doc.allParas().filter { rows.containsKey(it) }
        if (ps.isEmpty()) return false
        val c = cur()
        val startIdx = c?.let { ps.indexOf(it.para) }?.coerceAtLeast(0) ?: 0
        var from = c?.selectionEnd?.coerceAtLeast(0) ?: 0
        for (step in 0..ps.size) {
            val p = ps[(startIdx + step) % ps.size]
            val ed = rows[p]!!.editor
            val t = ed.text?.toString().orEmpty()
            val i = t.indexOf(q, if (step == 0) from else 0, ignoreCase = !matchCase)
            if (i >= 0) {
                ed.requestFocus()
                ed.setSelection(i, i + q.length)
                scrollToView(ed)
                return true
            }
            from = 0
        }
        toast("“$q” tidak ditemukan")
        return false
    }

    private fun scrollToView(v: View) {
        scroll.post {
            var y = 0; var cur: View? = v
            while (cur != null && cur !== scroll) { y += cur.top; cur = cur.parent as? View }
            scroll.smoothScrollTo(0, max(0, y - dp(120)))
        }
    }

    private fun replaceOne(q: String, r: String, matchCase: Boolean) {
        val e = cur()
        if (e != null && q.isNotEmpty()) {
            val s = e.selectionStart; val en = e.selectionEnd
            val sel = e.text?.substring(max(0, s), max(0, en)).orEmpty()
            if (sel.equals(q, ignoreCase = !matchCase) && en > s) {
                command { e.text?.replace(s, en, r); e.setSelection(s + r.length) }
            }
        }
        findNext(q, matchCase)
    }

    private fun replaceAll(q: String, r: String, matchCase: Boolean) {
        ensureRendered()
        if (q.isEmpty()) return
        var n = 0
        command {
            for (ed in allEditors()) {
                val t = ed.text ?: continue
                var i = t.toString().lastIndexOf(q, ignoreCase = !matchCase)
                while (i >= 0) {
                    t.replace(i, i + q.length, r); n++
                    i = if (i == 0) -1 else t.toString().lastIndexOf(q, i - 1, ignoreCase = !matchCase)
                }
            }
        }
        toast(if (n == 0) "“$q” tidak ditemukan" else "$n bagian diganti")
    }

    private fun showWordCount() {
        syncAll()
        val all = doc.allParas()
        val text = all.joinToString("\n") { it.text }
        val words = countWords(text)
        val chars = text.count { it != '\n' }
        val charsNoSpace = text.count { !it.isWhitespace() }
        val paras = all.count { it.text.isNotBlank() }
        val pages = max(1, (words / 300.0).let { kotlin.math.ceil(it).toInt() })
        AlertDialog.Builder(this).setTitle("Jumlah Kata")
            .setMessage("Kata: $words\nKarakter (dengan spasi): $chars\nKarakter (tanpa spasi): $charsNoSpace\nParagraf: $paras\nPerkiraan halaman: ±$pages")
            .setPositiveButton("OK", null).show()
    }

    private fun plainText(): String {
        syncAll()
        return doc.allParas().joinToString("\n") { it.text }.trim()
    }

    private fun readAloud() = VoiceReaderManager.showVoiceReaderDialog(this, docName, plainText())

    private fun printDoc() {
        syncAll()
        val html = doc.toHtml(docName) { b ->
            val mime = when {
                b.size > 3 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
                b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
                b.size > 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "image/gif"
                else -> return@toHtml null
            }
            "data:$mime;base64," + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        }
        val wv = WebView(this)
        printView = wv
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val pg = doc.page
                val base = when (pg?.let { min(it.w, it.h) to max(it.w, it.h) }) {
                    12240 to 15840 -> PrintAttributes.MediaSize.NA_LETTER
                    12240 to 20160 -> PrintAttributes.MediaSize.NA_LEGAL
                    8391 to 11906 -> PrintAttributes.MediaSize.ISO_A5
                    12189 to 18709 -> PrintAttributes.MediaSize("F4", "F4 / Folio", 8465, 12992)
                    else -> PrintAttributes.MediaSize.ISO_A4
                }
                val media = if (pg?.landscape == true) base.asLandscape() else base
                pm.print(docName, view.createPrintDocumentAdapter(docName), PrintAttributes.Builder().setMediaSize(media).build())
            }
        }
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        toast("Pilih printer atau “Simpan sebagai PDF”")
    }

    private fun showHelp() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_bulb, C.ACCENT, C.BLUE_BG, "Panduan Singkat", "Cara cepat memakai editor", dismiss)
        val tips = listOf(
            "Ketuk teks lalu ketik seperti biasa. Tekan Enter untuk paragraf baru.",
            "Pilih teks (tekan lama lalu geser), lalu pakai tombol di tab Beranda untuk tebal, warna, ukuran, dll.",
            "Tanpa memilih teks, format berlaku untuk kata di posisi kursor — atau untuk teks yang akan diketik.",
            "Tab Sisipkan: tabel, gambar, tanda tangan, pemisah halaman, simbol, dan tanggal.",
            "Saat kursor berada di tabel, muncul tab “Tabel” untuk menambah/menghapus baris & kolom.",
            "Tab Tata Letak: ukuran kertas (A4/F4), orientasi, margin (termasuk 4-4-3-3 untuk skripsi), dan spasi.",
            "Ketuk gambar untuk mengubah ukuran atau menghapusnya.",
            "↶ / ↷ di atas untuk urungkan/ulangi. Tombol − / + di bawah untuk memperbesar tampilan.",
            "Format asli dokumen (header, footer, gaya, gambar) selalu dipertahankan saat disimpan."
        )
        val pad = vbox().apply { setPadding(dp(20), 0, dp(20), dp(24)) }
        tips.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.add(tv("${i + 1}", 12f, C.ON_PRIMARY, true).apply { gravity = Gravity.CENTER; background = shape(C.ACCENT, 100) }, w = dp(22), h = dp(22))
            row.add(tv(s, 13.5f, C.TEXT), w = 0, weight = 1f, side = 10)
            pad.add(row, top = 10)
        }
        body.add(pad)
    }

    private fun sheetActions(title: String, actions: List<Pair<String, () -> Unit>>) = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_tune, C.ACCENT, C.BLUE_BG, title, "Pilih tindakan", dismiss)
        val list = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
        actions.forEach { (label, run) ->
            val danger = label.startsWith("Hapus")
            list.add(tv(label, 15f, if (danger) C.RED else C.TEXT, true).apply {
                setPadding(dp(14), dp(13), dp(14), dp(13)); background = shape(C.SURFACE2, 10)
                setOnClickListener { dismiss(); run() }
            }, top = 6)
        }
        body.add(list)
    }

    private fun setZoom(z: Float) {
        val nz = z.coerceIn(0.7f, 2.2f)
        if (kotlin.math.abs(nz - zoom) < 0.01f) return
        syncAll()
        val focus = cur()?.para; val pos = cur()?.selectionStart ?: -1
        zoom = nz
        zoomTv.text = "${(zoom * 100).roundToInt()}%"
        applyPageLook()
        renderAll(focus, pos)
    }

    // =========================================================================================
    // Simpan, ganti nama, keluar
    // =========================================================================================

    private fun renameDoc() {
        val et = EditText(this).apply { setText(docName); setSelection(text.length); isSingleLine = true }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this).setTitle("Nama dokumen").setView(box)
            .setPositiveButton("OK") { _, _ ->
                docName = et.text.toString().trim().ifBlank { docName }
                titleTv.text = docName; savedFile = null
            }.setNegativeButton("Batal", null).show()
    }

    private fun save(asNew: Boolean, after: (() -> Unit)? = null) {
        syncAll()
        val target = savedFile
        if (!asNew && target != null) {
            work("Menyimpan…", { doc.save(target) }) { res ->
                res.onSuccess { onSaved(target, false); after?.invoke() }.onFailure { fail(it) }
            }
            return
        }
        saveSheet("Simpan Dokumen Word", "Format asli dokumen tetap dipertahankan", docName, null, 0, "Simpan", ext = "docx") { name, _ ->
            val out = Files.namedOut(this, name, "docx")
            work("Menyimpan dokumen…", { doc.save(out) }) { res ->
                res.onSuccess {
                    docName = name; titleTv.text = name
                    onSaved(out, after == null)
                    after?.invoke()
                }.onFailure { fail(it) }
            }
        }
    }

    private fun onSaved(f: File, showSheet: Boolean) {
        savedFile = f
        dirty = false
        subTv.text = "Tersimpan · ${SimpleDateFormat("HH:mm", Locale("id")).format(Date())}"
        val uri = Files.uriFor(this, f)
        Recent.add(this, uri, f.name, DOCX)
        Meta.setLastJob(this, "Dokumen Word Tersimpan", f)
        if (!showSheet) { toast("Tersimpan: ${f.name}"); return }
        resultSheet(
            "Dokumen Tersimpan", "${f.name} • ${Files.fmtSize(f.length())}", "Klik Simpan lagi untuk memperbarui berkas yang sama.", "Buka di Aplikasi Lain",
            { Files.open(this, uri, DOCX) },
            listOf(
                SheetAction(R.drawable.ic_download, "Simpan ke Unduhan") {
                    work("Menyimpan…", { Files.saveToDownloads(this, uri, f.name, DOCX) }) { r ->
                        r.onSuccess { toast("Tersimpan di Unduhan/Depup Office") }.onFailure { fail(it) }
                    }
                },
                SheetAction(R.drawable.ic_chat, "Kirim via WhatsApp", C.TEAL, C.TEAL_BG) { Files.share(this, uri, DOCX, true) },
                SheetAction(R.drawable.ic_upload, "Bagikan ke Aplikasi Lain", C.INDIGO, C.INDIGO_BG) { Files.share(this, uri, DOCX, false) }
            )
        )
    }

    private fun askClose() {
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this).setTitle("Simpan perubahan?")
            .setMessage("Ada perubahan yang belum disimpan pada “$docName”.")
            .setPositiveButton("Simpan") { _, _ -> save(false) { finish() } }
            .setNegativeButton("Jangan Simpan") { _, _ -> finish() }
            .setNeutralButton("Batal", null).show()
    }
}
