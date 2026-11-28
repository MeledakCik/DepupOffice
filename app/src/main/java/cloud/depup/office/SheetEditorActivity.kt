package cloud.depup.office

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/**
 * Editor spreadsheet gaya Excel.
 *
 * - Tanpa batas baris/kolom dari DepUp — hanya memori HP ([SheetGridView] menggambar sel yang tampak saja).
 * - Seleksi: ketuk sel; TEKAN LAMA lalu geser untuk memilih rentang; tarik bulatan di sudut seleksi; ketuk judul
 *   kolom/baris untuk memilih kolom/baris utuh; sudut kiri-atas = pilih semua. Seleksi selesai → menu aksi.
 * - Ukuran: tarik tepi judul kolom/baris; ketuk dua kali tepinya = sesuaikan otomatis.
 * - Format (pita "Beranda"): tebal/miring/garis bawah/coret, warna isi & teks BEBAS (palet + pemilih warna khusus),
 *   perataan, ukuran huruf, bungkus teks, gabung sel, format angka.
 * - Sisip/hapus baris & kolom (rumus ikut menyesuaikan seperti Excel), urutkan, isi ke bawah/kanan, salin/potong/
 *   tempel (rumus ikut bergeser), cari & ganti, sembunyikan/tampilkan, Urungkan/Ulangi.
 * - Rumus gaya Excel ([FormulaEngine]): SUM, AVERAGE, IF, VLOOKUP, SUMIF, COUNTIF, CONCAT, ROUND, DATE, dll.
 *   Saat mengetik rumus, ketuk/seret sel untuk menyisipkan rujukannya.
 * - "Mode Dokumen": pratinjau halaman A4 persis seperti hasil Word/PDF ([SheetDocView]).
 * - Ekspor: Excel (.xlsx, lengkap dengan format, lebar, gabungan, rumus), Word (.docx), atau PDF.
 */
class SheetEditorActivity : AppCompatActivity() {

    companion object {
        private val NUMERIC = Regex("^[+\\-0-9.,]+$")
        const val EXTRA_URI = "uri"
        const val EXTRA_TEMPLATE = "template"
        const val TEMPLATE_INVOICE = "invoice"
        const val TEMPLATE_PRESENSI = "presensi"
        const val TEMPLATE_BLANK = "blank"
        /** Buka dari draf pemulihan [SheetDrafts] (id draf). */
        const val EXTRA_DRAFT = "draft"
        private const val STATE_DRAFT = "depup.sheetDraftId"

        /** Warna bawaan templat (tetap, tidak ikut tema — sama di layar, Excel, Word, PDF). */
        private const val HEAD_FILL = 0xFFE7E6E6.toInt()
        private const val TOTAL_FILL = 0xFFDDEBF7.toInt()
        private val FONT_SIZES = floatArrayOf(8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 36f, 48f)
    }

    // ================= DATA =================

    private val sheets = mutableListOf<SheetData>()
    private var cur = 0
    private val sh: SheetData get() = sheets[cur]

    /** Sel aktif = jangkar seleksi; [selR]/[selC] = ujung seleksi. */
    private var activeRow = 0
    private var activeCol = 0
    private var selR = 0
    private var selC = 0

    private val undo = UndoStack()
    private val engines = HashMap<SheetData, FormulaEngine>()
    private val shown = HashMap<Long, String>()

    private var currentGridlines = true
    private var currentCurrency = "Rp"
    private var currentSeparator = ","
    private var currentAutoSum = true
    private var sourceName: String? = null
    private var docMode = false

    /** Id draf pemulihan sesi editor ini ([SheetDrafts]). */
    private var draftId = java.util.UUID.randomUUID().toString()
    /** Sidik isi saat terakhir dibuka/diekspor; null = belum pernah bersih (dipulihkan dari draf). */
    private var cleanPrint: Int? = 0
    private var restoredDraft = false
    private var uiBuilt = false

    private val nfGeneral = NumberFormat.getNumberInstance(Locale("id", "ID")).apply { maximumFractionDigits = 6 }
    private val nf2 = NumberFormat.getNumberInstance(Locale("id", "ID")).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
    private val nf0 = NumberFormat.getNumberInstance(Locale("id", "ID")).apply { maximumFractionDigits = 0 }
    private val nfPct = NumberFormat.getNumberInstance(Locale("id", "ID")).apply { maximumFractionDigits = 2 }

    // ================= TAMPILAN =================

    private lateinit var sheetTitleEt: EditText
    private lateinit var formulaEt: EditText
    private lateinit var activeCellPill: TextView
    private lateinit var gridBox: LinearLayout
    private lateinit var grid: SheetGridView
    private lateinit var docView: SheetDocView
    private lateinit var docLoading: TextView
    private lateinit var contentFrame: FrameLayout
    private lateinit var topBarV: View
    private lateinit var formulaBarV: View
    private lateinit var ribbonV: LinearLayout
    private lateinit var toolsRow: LinearLayout
    private lateinit var toolsScroll: View
    private lateinit var modeSheetBtn: TextView
    private lateinit var modeDocBtn: TextView
    private lateinit var statusTv: TextView
    private lateinit var sheetBarContainer: LinearLayout
    private var ribbonTab = 0
    /** Keyboard sedang tampil (diperkirakan dari tinggi area tampak jendela). */
    private var imeShown = false
    private var updatingFormula = false

    // ================= SIDIK, DRAF, PULIHKAN =================

    private fun fingerprint(): Int {
        var h = 17
        for (s in sheets) {
            h = h * 31 + s.name.hashCode()
            h = h * 31 + s.cols
            for (row in s.rows) {
                h = h * 31 + row.size
                for (c in row) { h = h * 31 + c.valStr.hashCode(); h = h * 31 + c.style.hashCode() }
            }
            h = h * 31 + s.colW.sizes.hashCode()
            h = h * 31 + s.rowH.sizes.hashCode()
            h = h * 31 + s.merges.hashCode()
        }
        return h
    }

    private fun isDirty() = uiBuilt && cleanPrint != fingerprint()
    private fun markClean() { cleanPrint = fingerprint() }

    private fun styleJson(st: CellStyle) = org.json.JSONArray()
        .put(if (st.bold) 1 else 0).put(if (st.italic) 1 else 0).put(if (st.underline) 1 else 0).put(if (st.strike) 1 else 0)
        .put(st.color).put(st.fill).put(st.align).put(st.size.toDouble()).put(st.numFmt).put(if (st.wrap) 1 else 0)

    private fun styleFrom(a: org.json.JSONArray) = CellStyle(
        bold = a.optInt(0) == 1, italic = a.optInt(1) == 1, underline = a.optInt(2) == 1, strike = a.optInt(3) == 1,
        color = a.optInt(4), fill = a.optInt(5), align = a.optInt(6), size = a.optDouble(7, 0.0).toFloat(),
        numFmt = a.optInt(8), wrap = a.optInt(9) == 1
    )

    /** Semua sheet → JSON draf (rumus disimpan mentah, gaya, ukuran, gabungan). */
    private fun draftJson(): String {
        val arr = org.json.JSONArray()
        for (s in sheets) {
            val styleIdx = HashMap<CellStyle, Int>()
            val styles = org.json.JSONArray()
            val rows = org.json.JSONArray()
            for (row in s.rows) {
                val r = org.json.JSONArray()
                for (c in row) {
                    val si = styleIdx.getOrPut(c.style) { styles.put(styleJson(c.style)); styles.length() - 1 }
                    r.put(org.json.JSONArray().put(c.valStr).put(si))
                }
                rows.put(r)
            }
            val cw = org.json.JSONObject(); for ((k, v) in s.colW.sizes) cw.put(k.toString(), v.toDouble())
            val rh = org.json.JSONObject(); for ((k, v) in s.rowH.sizes) rh.put(k.toString(), v.toDouble())
            val mg = org.json.JSONArray(); for (m in s.merges) mg.put(org.json.JSONArray().put(m.top).put(m.left).put(m.bottom).put(m.right))
            arr.put(org.json.JSONObject().put("name", s.name).put("cols", s.cols).put("styles", styles).put("rows", rows)
                .put("colW", cw).put("rowH", rh).put("merges", mg))
        }
        return org.json.JSONObject().put("v", 2)
            .put("title", if (::sheetTitleEt.isInitialized) sheetTitleEt.text.toString() else "")
            .put("source", sourceName ?: "")
            .put("cur", cur)
            .put("sheets", arr).toString()
    }

    /** Pulihkan dari draf (format baru & lama); false bila draf rusak. */
    private fun restoreFrom(d: DraftStore.Draft): Boolean = try {
        val j = org.json.JSONObject(d.file.readText())
        val v2 = j.optInt("v") >= 2
        val arr = j.getJSONArray("sheets")
        val list = ArrayList<SheetData>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = SheetData(o.optString("name").ifBlank { "Sheet${i + 1}" })
            val styles = o.optJSONArray("styles")
            val rows = o.getJSONArray("rows")
            for (r in 0 until rows.length()) {
                val ra = rows.getJSONArray(r)
                val row = ArrayList<SheetCell>(ra.length())
                for (c in 0 until ra.length()) {
                    val ca = ra.getJSONArray(c)
                    row.add(if (v2) SheetCell(ca.optString(0), styles?.optJSONArray(ca.optInt(1))?.let { styleFrom(it) } ?: CellStyle.DEFAULT)
                    else SheetCell(ca.optString(0), ca.optInt(1) == 1, ca.optInt(2)))
                }
                s.rows.add(row)
            }
            if (s.rows.isEmpty()) s.rows.add(MutableList(4) { SheetCell("") })
            s.cols = o.optInt("cols", s.rows.maxOf { it.size }).coerceAtLeast(1)
            o.optJSONObject("colW")?.let { m -> m.keys().forEach { k -> k.toIntOrNull()?.let { s.colW.set(it, m.optDouble(k).toFloat()) } } }
            o.optJSONObject("rowH")?.let { m -> m.keys().forEach { k -> k.toIntOrNull()?.let { s.rowH.set(it, m.optDouble(k).toFloat()) } } }
            o.optJSONArray("merges")?.let { m -> for (k in 0 until m.length()) m.getJSONArray(k).let { a -> s.merges.add(CellRange(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))) } }
            list.add(s)
        }
        require(list.isNotEmpty())
        sheets.clear(); sheets.addAll(list)
        cur = j.optInt("cur").coerceIn(0, list.size - 1)
        sourceName = j.optString("source").ifBlank { null }
        restoredDraft = true
        buildUi(j.optString("title").ifBlank { d.name })
        toast("Perubahan yang belum diekspor telah dipulihkan.")
        true
    } catch (_: Throwable) {
        sheets.clear(); restoredDraft = false
        false
    }

    /** Keluar: tanya dulu bila ada perubahan yang belum diekspor. */
    private fun askClose() {
        commitTyping()
        if (!isDirty()) { finish(); return }
        AlertDialog.Builder(this).setTitle("Simpan perubahan?")
            .setMessage("Ada perubahan yang belum diekspor pada spreadsheet ini.")
            .setPositiveButton("Ekspor") { _, _ -> showExportChooser() }
            .setNegativeButton("Jangan Simpan") { _, _ -> SheetDrafts.clear(this, draftId); finish() }
            .setNeutralButton("Batal", null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DRAFT, draftId)
    }

    /** Ke latar dengan perubahan belum diekspor → draf pemulihan; ditutup → draf dibuang. */
    override fun onStop() {
        super.onStop()
        if (isFinishing) { SheetDrafts.clear(this, draftId); return }
        commitTyping()
        if (!isDirty()) return
        val json = try { draftJson() } catch (_: Throwable) { return }
        val title = if (::sheetTitleEt.isInitialized) sheetTitleEt.text.toString().ifBlank { "Spreadsheet" } else "Spreadsheet"
        SheetDrafts.write(this, draftId, title, null) { f ->
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }

    // ================= BUKA =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentGridlines = SheetSettings.showGridlines(this)
        currentCurrency = SheetSettings.currencySymbol(this)
        currentSeparator = SheetSettings.csvSeparator(this)
        currentAutoSum = SheetSettings.autoSum(this)

        onBackPressedDispatcher.addCallback(this, PreviewBack(this) { if (docMode) setDocMode(false) else askClose() })
        val sid = savedInstanceState?.getString(STATE_DRAFT)
        val want = sid ?: intent.getStringExtra(EXTRA_DRAFT)
        if (sid != null) draftId = sid
        val draft = want?.let { SheetDrafts.get(this, it) }
        if (draft != null) draftId = draft.id
        SheetDrafts.markLive(draftId, true)
        if (draft != null && restoreFrom(draft)) return

        val rawUri = intent.getStringExtra(EXTRA_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (rawUri == null) { buildUi(loadTemplate(intent.getStringExtra(EXTRA_TEMPLATE))); return }
        // Berkas diurai di LATAR (workbook besar tidak membekukan layar). Tanpa batas baris/kolom dari DepUp:
        // seluruh workbook dimuat sejauh memori HP sanggup; bila tidak cukup, pengguna diberi tahu.
        val loading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(C.SURFACE2) }
        loading.add(android.widget.ProgressBar(this), w = dp(40), h = dp(40))
        loading.add(tv("Membuka spreadsheet…", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 10)
        setContentView(loading)
        Thread(null, {
            val name = try { Files.nameOf(this, rawUri) } catch (_: Throwable) { "Spreadsheet" }
            var tooBig = false
            var book: List<SheetData>? = null
            try {
                val low = name.lowercase()
                book = if (low.endsWith(".csv") || low.endsWith(".txt")) {
                    OfficeReader.readCsv(this, rawUri, full = true).takeIf { it.rows.isNotEmpty() }?.let { listOf(fromRows(it.name, it.rows, it.boldRows)) }
                } else {
                    XlsxBookReader.read(this, rawUri)?.sheets
                        ?: OfficeReader.readXlsx(this, rawUri, Int.MAX_VALUE, Int.MAX_VALUE, dense = false)
                            ?.takeIf { it.rows.isNotEmpty() }?.let { listOf(fromRows(it.name, it.rows, it.boldRows)) }
                }
            } catch (_: OutOfMemoryError) {
                tooBig = true; book = null
            } catch (_: Throwable) { book = null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sourceName = Files.baseName(name)
                val got = book
                val title = if (!got.isNullOrEmpty()) {
                    sheets.clear(); sheets.addAll(got)
                    sourceName ?: got[0].name
                } else {
                    if (!tooBig) toast("Isi spreadsheet tidak terbaca. Mulai dari tabel kosong.")
                    loadTemplate(TEMPLATE_BLANK)
                }
                buildUi(title)
                if (tooBig) {
                    AlertDialog.Builder(this)
                        .setTitle("Memori HP tidak cukup")
                        .setMessage("Spreadsheet ini terlalu besar untuk dimuat seluruhnya di memori HP ini, jadi editor " +
                            "dibuka dengan tabel kosong.\n\nBerkas asli tidak diubah. Tutup aplikasi lain lalu coba lagi, " +
                            "atau buka berkas ini di pembaca.")
                        .setPositiveButton("Mengerti", null)
                        .show()
                }
            }
        }, "sheet-open", 4L * 1024 * 1024).start()
    }

    /** Baris teks (CSV / pembaca lama) → lembar. Baris judul ditebalkan. */
    private fun fromRows(name: String, rows: List<List<String>>, boldRows: Set<Int>): SheetData {
        val s = SheetData(name.ifBlank { "Sheet1" })
        var maxCols = 0
        rows.forEachIndexed { r, row ->
            var len = row.size
            while (len > 0 && row[len - 1].isEmpty()) len--
            val st = if (r in boldRows) CellStyle(bold = true, fill = HEAD_FILL) else CellStyle.DEFAULT
            val list = ArrayList<SheetCell>(len)
            for (c in 0 until len) list.add(SheetCell(row[c], st))
            if (len > maxCols) maxCols = len
            s.rows.add(list)
        }
        s.cols = maxCols.coerceAtLeast(2)
        if (s.rows.isEmpty()) s.rows.add(MutableList(s.cols) { SheetCell("") })
        return s
    }

    private fun sheetOf(name: String, rows: List<List<String>>, head: Set<Int>, total: Set<Int> = emptySet()): SheetData {
        val s = SheetData(name)
        rows.forEachIndexed { r, row ->
            val st = when (r) { in head -> CellStyle(bold = true, fill = HEAD_FILL); in total -> CellStyle(bold = true, fill = TOTAL_FILL); else -> CellStyle.DEFAULT }
            s.rows.add(row.map { SheetCell(it, st) }.toMutableList())
        }
        s.cols = rows.maxOf { it.size }
        return s
    }

    /** Lembar templat; mengembalikan judul. */
    private fun loadTemplate(template: String?): String {
        val (title, s) = templateSheet(template)
        sheets.clear(); sheets.add(s); cur = 0
        return title
    }

    private fun templateSheet(template: String?): Pair<String, SheetData> = when (template) {
        TEMPLATE_INVOICE -> "Invoice / Kuitansi" to sheetOf("Sheet1", listOf(
            listOf("No Invoice", "Klien", "Jatuh Tempo", "Total Tagihan"),
            listOf("INV-001", "PT Depup Tech", "28 Okt 2024", "15000000"),
            listOf("INV-002", "CV Jaya Abadi", "30 Okt 2024", "8500000"),
            listOf("TOTAL", "", "", "=SUM(D2:D3)")
        ), setOf(0), setOf(3)).also { s ->
            s.colW.set(1, 140f); s.colW.set(3, 130f)
            s.rows.drop(1).forEach { r -> r[3].style = r[3].style.copy(numFmt = CellNumFmt.CURRENCY) }
        }
        TEMPLATE_PRESENSI -> "Presensi & Daftar Hadir" to sheetOf("Sheet1", listOf(
            listOf("Nama Karyawan", "Hadir", "Izin", "Status"),
            listOf("Budi Pratama", "22", "0", "=IF(C2=0,\"Lengkap\",\"Ada izin\")"),
            listOf("Siti Aminah", "21", "1", "=IF(C3=0,\"Lengkap\",\"Ada izin\")")
        ), setOf(0)).also { it.colW.set(0, 140f) }
        TEMPLATE_BLANK -> "Spreadsheet Baru" to SheetData("Sheet1", MutableList(30) { mutableListOf<SheetCell>() }, 8)
        else -> "Laporan_Keuangan_Depup" to sheetOf("Sheet1", listOf(
            listOf("Produk/Item", "Qty", "Harga Satuan", "Total (Rp)"),
            listOf("Depup Cloud Pro", "12", "120000", "=B2*C2"),
            listOf("Lisensi PDF OCR", "8", "250000", "=B3*C3"),
            listOf("Add-on e-Meterai", "15", "12500", "=B4*C4"),
            listOf("Server Vault Storage", "4", "450000", "=B5*C5"),
            listOf("TOTAL PENDAPATAN", "=SUM(B2:B5)", "", "=SUM(D2:D5)")
        ), setOf(0), setOf(5)).also { s ->
            s.colW.set(0, 160f); s.colW.set(2, 110f); s.colW.set(3, 120f)
            for (r in 1..5) for (c in 2..3) s.rows[r].getOrNull(c)?.let { cell -> cell.style = cell.style.copy(numFmt = CellNumFmt.THOUSANDS) }
        }
    }

    // ================= KERANGKA UI =================

    private fun buildUi(initialTitle: String) {
        if (sheets.isEmpty()) loadTemplate(TEMPLATE_BLANK)
        cur = cur.coerceIn(0, sheets.size - 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.SURFACE2) }
        root.add(topBar(initialTitle).also { topBarV = it })
        root.add(formulaBar().also { formulaBarV = it })
        root.add(ribbon().also { ribbonV = it })

        contentFrame = FrameLayout(this)
        val pad = vbox().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        gridBox = vbox()
        val cardGrid = FrameLayout(this).apply {
            background = raised(C.SURFACE, 12, 2f)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        grid = SheetGridView(this, gridSource)
        @Suppress("ClickableViewAccessibility")
        grid.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) dismissEditMenu(); false }
        cardGrid.addView(grid, FrameLayout.LayoutParams(MATCH, WRAP))
        gridBox.add(cardGrid, w = MATCH, h = WRAP)
        pad.add(gridBox, w = MATCH, h = WRAP)
        contentFrame.addView(pad, FrameLayout.LayoutParams(MATCH, MATCH))
        docView = SheetDocView(this).apply { visibility = View.GONE }
        contentFrame.addView(docView, FrameLayout.LayoutParams(MATCH, MATCH))
        docLoading = tv("Menyiapkan halaman…", 13f, C.TEXT, true).apply {
            gravity = Gravity.CENTER; visibility = View.GONE
            background = shape(C.SURFACE, 100, C.LINE, 1); elevation = dp(4).toFloat()
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        contentFrame.addView(docLoading, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.add(contentFrame, w = MATCH, h = 0, weight = 1f)

        // ringkasan seleksi (Jumlah · Rata-rata · Hitung): kapsul kecil melayang di bawah grid, seperti Numbers
        statusTv = tv("", 12f, C.TEXT, true).apply {
            background = shape(C.SURFACE, 100, C.LINE, 1)
            elevation = dp(4).toFloat()
            setPadding(dp(14), dp(7), dp(14), dp(7))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        contentFrame.addView(statusTv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(14); leftMargin = dp(16); rightMargin = dp(16)
        })
        // bilah bawah: tab sheet (bisa digeser) + Sheet/Dokumen
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(12), dp(4))
        }
        sheetBarContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottomRow.add(sheetBarContainer, w = 0, weight = 1f)
        bottomRow.add(modeToggle(), w = WRAP, side = 4)
        root.add(bottomRow, w = MATCH, h = WRAP)

        setContentView(Glass.scaffold(root))
        uiBuilt = true
        updateSheetBar()
        selectCell(0, 0, false)
        renderGrid()
        applyDensity()
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(imeWatch)
        cleanPrint = if (restoredDraft) null else fingerprint()
    }

    private val imeWatch = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        val r = android.graphics.Rect()
        window.decorView.getWindowVisibleDisplayFrame(r)
        val h = window.decorView.rootView.height
        val shownNow = h > 0 && h - r.bottom > h * 0.15f
        if (shownNow != imeShown) { imeShown = shownNow; applyDensity() }
    }

    /** HP dimiringkan: bilah lebih rapat; pita alat disembunyikan selama keyboard tampil (lanskap). */
    private fun applyDensity() {
        if (!::topBarV.isInitialized) return
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        topBarV.setPadding(dp(12), if (land) dp(3) else dp(6), dp(12), if (land) dp(3) else dp(6))
        formulaBarV.setPadding(dp(12), if (land) dp(2) else dp(4), dp(12), if (land) dp(3) else dp(4))
        formulaEt.minHeight = if (land) dp(34) else dp(38)
        if (folds.isEmpty()) {
            formulaBarV.visibility = if (docMode) View.GONE else View.VISIBLE
            ribbonV.visibility = if (docMode) View.GONE else View.VISIBLE
        }
        toolsScroll.visibility = if (land && imeShown) View.GONE else View.VISIBLE
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDensity()
    }

    override fun onDestroy() {
        SheetDrafts.markLive(draftId, false)
        if (isFinishing) SheetDrafts.clear(this, draftId)
        if (::topBarV.isInitialized) try { window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(imeWatch) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Bilah atas: kembali, judul, templat/pengaturan/opsi, Ekspor. */
    private fun topBar(initialTitle: String): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        bar.add(navCircle(R.drawable.ic_back, "Kembali") { if (docMode) setDocMode(false) else askClose() }, w = dp(40), h = dp(40))
        sheetTitleEt = field("Nama Spreadsheet Excel", multi = false).apply {
            setText(initialTitle)
            textSize = 16f
            typeface = Fonts.head(this@SheetEditorActivity)
            setTextColor(C.TEXT)
            background = null
            minHeight = dp(40)
            setPadding(dp(10), dp(4), dp(8), dp(4))
        }
        bar.add(sheetTitleEt, w = 0, weight = 1f, side = 4)
        val caps = navCapsule()
        caps.add(capsuleBtn(R.drawable.ic_sheet, "Templat") { showTemplatePicker() }, w = dp(40), h = dp(40))
        caps.add(capsuleBtn(R.drawable.ic_tune, "Pengaturan spreadsheet") { showExcelSettingsSheet() }, w = dp(40), h = dp(40))
        caps.add(capsuleBtn(R.drawable.ic_more, "Opsi lain") { v ->
            val pm = android.widget.PopupMenu(this@SheetEditorActivity, v)
            pm.menu.add(0, 1, 0, "Bacakan Isi Tabel (Voice Reader)")
            pm.menu.add(0, 2, 1, "Cetak Spreadsheet")
            pm.menu.add(0, 3, 2, "Pergi ke Sel…")
            pm.setOnMenuItemClickListener { m ->
                val title = sheetTitleEt.text.toString().ifBlank { "Spreadsheet Excel" }
                when (m.itemId) {
                    1 -> VoiceReaderManager.showVoiceReaderDialog(this@SheetEditorActivity, title, getReadableGridText())
                    2 -> {
                        commitTyping()
                        val g = printGrid(sh, if (sheets.size > 1) sh.name else title)
                        val out = PrintKit.newFile(this@SheetEditorActivity, "tabel")
                        val lines = currentGridlines
                        work("Menyiapkan tabel…", { SheetPrint.pdf(title, listOf(g), out, lines) }) { r ->
                            if (r.isSuccess) printSheet(out, title) else { out.delete(); toast("Tabel tidak bisa disiapkan untuk dicetak") }
                        }
                    }
                    3 -> showGoTo()
                }
                true
            }
            pm.show()
        }, w = dp(40), h = dp(40))
        bar.addView(caps, LinearLayout.LayoutParams(WRAP, dp(42)))
        bar.addView(navPrimary("Ekspor") { showExportChooser() }, LinearLayout.LayoutParams(WRAP, dp(42)).apply { marginStart = dp(8) })
        return bar
    }

    /** Bilah rumus: nama sel/rentang, fx, isian. Enter = turun satu baris (seperti Excel). */
    private fun formulaBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        // satu kapsul utuh: nama sel · fx · isian (tanpa kotak bersudut)
        val cap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(C.SURFACE2, 100)
            setPadding(dp(5), dp(4), dp(5), dp(4))
        }
        bar.add(cap, w = 0, weight = 1f)
        activeCellPill = pill("A1", C.TEAL, C.TEAL_BG, 11f).apply {
            isClickable = true
            contentDescription = "Pergi ke sel"
            setOnClickListener { showGoTo() }
        }
        cap.add(activeCellPill, w = WRAP)
        val fxBtn = tv("fx", 14f, C.ACCENT, true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = shape(C.BLUE_BG, 100)
            isClickable = true
            isFocusable = true
            contentDescription = "Asisten rumus"
            setOnClickListener { showFormulaAssistantSheet() }
        }
        cap.add(fxBtn, w = WRAP, side = 4)
        formulaEt = field("Ketik teks, angka, atau rumus (=SUM…)", multi = false).apply {
            textSize = 13f
            typeface = Fonts.body(this@SheetEditorActivity)
            setTextColor(C.TEXT)
            background = null
            setPadding(dp(6), dp(4), dp(10), dp(4))
            // fokus: kapsul pembungkusnya yang diberi cincin warna (bukan kotak kedua di dalam kapsul)
            onFocusChangeListener = View.OnFocusChangeListener { _, has ->
                background = null
                (parent as? View)?.background = if (has) shape(C.SURFACE2, 100, C.TEAL, 2) else shape(C.SURFACE2, 100)
            }
            isSingleLine = true
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, action, ev ->
                val enterKey = ev != null && ev.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_NEXT || action == EditorInfo.IME_ACTION_DONE || enterKey) {
                    commitTyping()
                    val r = selRange().bottom + 1
                    while (sh.rows.size <= r) sh.rows.add(mutableListOf())
                    grid.structureChanged()
                    selectCell(r, activeCol, true)
                    true
                } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (updatingFormula) return
                    val r = activeRow; val c = activeCol
                    if (r < 0 || c < 0) return
                    beginTyping()
                    while (sh.rows.size <= r) sh.rows.add(mutableListOf())
                    sh.ensure(r, c).valStr = s?.toString().orEmpty()
                    if (c >= sh.cols) sh.cols = c + 1
                    refreshCells()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { if (!pickingNow) pickLen = -1 }
            })
        }
        cap.add(formulaEt, w = 0, weight = 1f, side = 2)
        return bar
    }

    // ================= PITA ALAT (RIBBON) =================
    // Senada dengan editor Word: tab = kontrol segmen kapsul dengan pil yang meluncur; tab Beranda = tombol ikon di
    // dalam kapsul berkelompok (seperti Pages), tab lain = kapsul ikon + label. Tidak ada sudut tajam.

    private val toggles = HashMap<String, TextView>()
    private val grouped = HashSet<View>()
    private var fillSwatch: View? = null
    private var colorSwatch: View? = null
    private var toolGroup: LinearLayout? = null
    private lateinit var tabRow: PillRow
    private val tabViews = ArrayList<TextView>()
    private lateinit var modeRow: PillRow
    private var tabShown = false

    private fun ribbon(): LinearLayout {
        val box = vbox().apply { setBackgroundColor(C.SURFACE2) }
        tabRow = PillRow(this).apply {
            background = shape(C.SURFACE, 100, C.LINE, 1)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            pillColor = C.TEAL
            textOn = C.ON_TEAL
        }
        tabRow.pillEdge = dp(3).toFloat()
        listOf("Beranda", "Sisipkan", "Data", "Tampilan").forEachIndexed { i, n ->
            val t = tv(n, 13f, C.MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rippleBg(100)
                isClickable = true
                setOnClickListener { if (i != ribbonTab) haptic(this); showTab(i) }
            }
            t.squish(0.95f) { tabRow.invalidate() }
            tabViews.add(t)
            tabRow.add(t, w = WRAP, weight = 1f)
        }
        box.add(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(6))
            addView(tabRow)
        })
        val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        scroll.addView(toolsRow)
        toolsScroll = scroll
        box.add(scroll)
        showTab(ribbonTab)
        return box
    }

    /** Pindah tab pita: pil meluncur, alat lama memudar & alat baru bergeser masuk dari arah tab yang dipilih. */
    private fun showTab(i: Int) {
        val prev = ribbonTab
        ribbonTab = i
        tabViews.forEachIndexed { k, t -> t.isSelected = k == i }
        val animate = tabShown && prev != i && Motion.on()
        tabRow.mark(tabViews[i], animate)
        tabShown = true
        (toolsScroll as? android.widget.HorizontalScrollView)?.scrollTo(0, 0)
        if (animate) toolsRow.swapContent(if (i > prev) Motion.SHIFT_DP else -Motion.SHIFT_DP, 0) { buildTools() } else buildTools()
    }

    /** Sheet / Dokumen: kontrol segmen kecil di bilah bawah, pilnya meluncur. */
    private fun modeToggle(): View {
        modeRow = PillRow(this).apply {
            background = shape(C.SURFACE2, 100)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            pillColor = C.TEAL
            textOn = C.ON_TEAL
        }
        modeRow.pillEdge = dp(3).toFloat()
        modeSheetBtn = tv("Sheet", 12f, C.MUTED, true).apply { contentDescription = "Mode sheet"; setOnClickListener { setDocMode(false) } }
        modeDocBtn = tv("Dokumen", 12f, C.MUTED, true).apply { contentDescription = "Mode dokumen (pratinjau Word)"; setOnClickListener { setDocMode(true) } }
        for (b in listOf(modeSheetBtn, modeDocBtn)) {
            b.gravity = Gravity.CENTER
            b.setPadding(dp(12), dp(5), dp(12), dp(5))
            b.background = rippleBg(100)
            b.isClickable = true
            b.squish(0.95f) { modeRow.invalidate() }
            modeRow.add(b, w = WRAP)
        }
        modeRow.post { paintMode(false) }
        return modeRow
    }

    private fun paintMode(animate: Boolean) {
        if (!::modeRow.isInitialized) return
        modeSheetBtn.isSelected = !docMode; modeDocBtn.isSelected = docMode
        modeRow.mark(if (docMode) modeDocBtn else modeSheetBtn, animate && Motion.on())
    }

    /** Kapsul kelompok alat yang sedang diisi; [sep] menutupnya. */
    private fun group(): LinearLayout = toolGroup ?: LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(C.SURFACE, 100, C.LINE, 1)
        setPadding(dp(3), 0, dp(3), 0)
        toolsRow.addView(this, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
        toolGroup = this
    }

    private fun toolBg(on: Boolean, inGroup: Boolean): android.graphics.drawable.Drawable =
        if (inGroup) { if (on) android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(C.RIPPLE), shape(C.TEAL_SOFT, 100), null) else rippleBg(100) }
        else android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(C.RIPPLE),
            if (on) shape(C.TEAL_SOFT, 100, C.TEAL, 1) else shape(C.SURFACE, 100, C.LINE, 1), null
        )

    /**
     * Alat pita. Di tab Beranda, tombol ikon / satu huruf masuk kapsul berkelompok; tombol berlabel berdiri sendiri
     * sebagai kapsul. Nama alat selalu ada untuk pembaca layar & tooltip (tekan lama).
     */
    private fun tool(label: String, icon: Int = 0, desc: String = label, key: String? = null, onClick: () -> Unit): TextView {
        val inGroup = ribbonTab == 0 && label.length <= 1
        val t = tv("", 13f, C.TEXT, true).apply {
            text = if (icon != 0) withIcon(if (label.isEmpty()) "" else " $label", icon, C.TEXT, 18, end = false) else label
            gravity = Gravity.CENTER
            background = toolBg(false, inGroup)
            includeFontPadding = false
            isClickable = true
            contentDescription = desc
            tooltipText = desc
            setOnClickListener { onClick(); if (key != null && toggles[key] === this) boing(0.8f) }
            springPress(if (inGroup) 0.86f else 0.94f)
        }
        if (key != null) toggles[key] = t
        if (inGroup) {
            grouped.add(t)
            t.minWidth = dp(38)
            t.setPadding(dp(6), 0, dp(6), 0)
            group().addView(t, LinearLayout.LayoutParams(WRAP, dp(34)))
        } else {
            toolGroup = null
            t.setPadding(dp(12), 0, dp(14), 0)
            toolsRow.addView(t, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(6) })
        }
        return t
    }

    /** Pemisah kelompok: kapsul berikutnya dimulai baru (jarak antar-kapsul sudah memisahkan). */
    private fun sep() {
        if (toolGroup == null && toolsRow.childCount > 0) (toolsRow.getChildAt(toolsRow.childCount - 1).layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(10)
        toolGroup = null
    }

    /** Tombol warna: huruf + garis warna membulat di bawahnya (seperti Excel), di dalam kapsul kelompok. */
    private fun swatchTool(label: String, desc: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rippleBg(100)
            setPadding(dp(8), 0, dp(8), 0)
            minimumWidth = dp(38)
            isClickable = true
            contentDescription = desc
            tooltipText = desc
            setOnClickListener { onClick() }
            springPress(0.86f)
        }
        box.add(tv(label, 13f, C.TEXT, true).apply { gravity = Gravity.CENTER; includeFontPadding = false }, w = WRAP)
        val bar = View(this)
        box.add(bar, w = dp(18), h = dp(4), top = 2)
        group().addView(box, LinearLayout.LayoutParams(WRAP, dp(34)))
        return bar
    }

    private fun pickFill() = ColorPicker.show(this, "Warna Isi Sel", activeStyle().fill, "Tanpa isi") { c -> applyStyle("Warna isi") { it.copy(fill = c) } }
    private fun pickTextColor() = ColorPicker.show(this, "Warna Teks", activeStyle().color, "Otomatis") { c -> applyStyle("Warna teks") { it.copy(color = c) } }

    /** Isi pita sesuai tab. */
    private fun buildTools() {
        if (!::toolsRow.isInitialized) return
        toolsRow.removeAllViews(); toggles.clear(); grouped.clear(); fillSwatch = null; colorSwatch = null; toolGroup = null
        when (ribbonTab) {
            0 -> {
                tool("", R.drawable.ic_undo, "Urungkan") { doUndo() }
                tool("", R.drawable.ic_redo, "Ulangi") { doRedo() }
                sep()
                tool("", R.drawable.ic_paste, "Tempel") { paste() }
                tool("", R.drawable.ic_copy, "Salin") { copy(false) }
                tool("", R.drawable.ic_cut, "Potong") { copy(true) }
                sep()
                tool("B", desc = "Tebal", key = "b") { toggleStyle("Tebal", { it.bold }) { s, v -> s.copy(bold = v) } }
                    .apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
                tool("I", desc = "Miring", key = "i") { toggleStyle("Miring", { it.italic }) { s, v -> s.copy(italic = v) } }
                    .apply { setTypeface(typeface, android.graphics.Typeface.ITALIC) }
                tool("U", desc = "Garis bawah", key = "u") { toggleStyle("Garis bawah", { it.underline }) { s, v -> s.copy(underline = v) } }
                    .apply { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG }
                tool("S", desc = "Coret", key = "s") { toggleStyle("Coret", { it.strike }) { s, v -> s.copy(strike = v) } }
                    .apply { paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG }
                sep()
                fillSwatch = swatchTool("Isi", "Warna isi sel") { pickFill() }
                colorSwatch = swatchTool("A", "Warna teks") { pickTextColor() }
                sep()
                tool("⇤", desc = "Rata kiri", key = "al") { setAlign(HAlign.LEFT) }
                tool("↔", desc = "Rata tengah", key = "ac") { setAlign(HAlign.CENTER) }
                tool("⇥", desc = "Rata kanan", key = "ar") { setAlign(HAlign.RIGHT) }
                tool("Bungkus", R.drawable.ic_line_break, "Bungkus teks", key = "wrap") {
                    val v = !activeStyle().wrap
                    applyStyle(if (v) "Bungkus teks" else "Lepas bungkus") { it.copy(wrap = v) }
                    fitRowsOfSelection()
                }
                sep()
                tool(sizeLabel(), desc = "Ukuran huruf", key = "size") { pickFontSize() }
                tool("Gabung", R.drawable.ic_merge, "Gabung & tengahkan", key = "merge") { toggleMerge() }
                tool("Format angka", desc = "Format angka") { pickNumFmt() }
                sep()
                tool("", R.drawable.ic_format_clear, "Hapus format") { applyStyle("Hapus format") { CellStyle.DEFAULT } }
                tool("Hapus isi", R.drawable.ic_trash, "Hapus isi sel") { clearContents() }
            }
            1 -> {
                tool("Baris di atas", R.drawable.ic_plus) { insertRows(selRange().top, selRange().rows) }
                tool("Baris di bawah", R.drawable.ic_plus) { insertRows(selRange().bottom + 1, selRange().rows) }
                tool("Kolom di kiri", R.drawable.ic_plus) { insertCols(selRange().left, selRange().cols) }
                tool("Kolom di kanan", R.drawable.ic_plus) { insertCols(selRange().right + 1, selRange().cols) }
                sep()
                tool("Hapus baris", R.drawable.ic_minus) { deleteRows() }
                tool("Hapus kolom", R.drawable.ic_minus) { deleteCols() }
                sep()
                tool("Σ AutoSum") { autoSum() }
                tool("fx Rumus") { showFormulaAssistantSheet() }
            }
            2 -> {
                tool("Urutkan A→Z") { sortSelection(true) }
                tool("Urutkan Z→A") { sortSelection(false) }
                sep()
                tool("Isi ke bawah", R.drawable.ic_down) { fill(true) }
                tool("Isi ke kanan", R.drawable.ic_next) { fill(false) }
                sep()
                tool("Cari & Ganti", R.drawable.ic_search) { showFindReplaceSheet() }
                tool("Pergi ke sel") { showGoTo() }
            }
            else -> {
                tool("Lebar kolom…") { askSize(true) }
                tool("Tinggi baris…") { askSize(false) }
                tool("Pas otomatis") { autoFitSelection() }
                sep()
                tool("Sembunyikan kolom") { hide(true) }
                tool("Sembunyikan baris") { hide(false) }
                tool("Tampilkan semua") { unhide() }
                sep()
                tool(if (currentGridlines) "Garis kisi: Ya" else "Garis kisi: Tidak", R.drawable.ic_grid) {
                    currentGridlines = !currentGridlines
                    SheetSettings.setShowGridlines(this, currentGridlines)
                    buildTools(); refreshCells()
                }
                tool("Mode Dokumen", R.drawable.ic_doc) { setDocMode(true) }
            }
        }
        refreshRibbon()
    }

    private fun uiReady() = uiBuilt && sheets.isNotEmpty()

    private fun sizeLabel(): String {
        val s = if (uiReady()) activeStyle().size else 0f
        val v = if (s > 0f) s else CellStyle.BASE_SIZE
        return (if (v % 1f == 0f) v.toInt().toString() else v.toString()) + " pt"
    }

    /** Tombol pita mengikuti gaya sel aktif. */
    private fun refreshRibbon() {
        if (!uiReady()) return
        val st = activeStyle()
        fun mark(k: String, on: Boolean) {
            val t = toggles[k] ?: return
            t.setTextColor(if (on) C.TEAL else C.TEXT)
            t.background = toolBg(on, t in grouped)
            t.isSelected = on
        }
        mark("b", st.bold); mark("i", st.italic); mark("u", st.underline); mark("s", st.strike)
        mark("al", st.align == HAlign.LEFT); mark("ac", st.align == HAlign.CENTER); mark("ar", st.align == HAlign.RIGHT)
        mark("wrap", st.wrap)
        mark("merge", sh.mergeAt(activeRow, activeCol) != null)
        toggles["size"]?.text = sizeLabel()
        fillSwatch?.background = shape(if (st.fill != 0) st.fill else C.LINE, 100)
        colorSwatch?.background = shape(if (st.color != 0) st.color else C.TEXT, 100)
    }

    // ================= MODE DOKUMEN =================

    /** Pratinjau terakhir + kunci isinya: pindah bolak-balik tanpa perubahan langsung memakai halaman yang sama. */
    private var docCache: Pair<Int, SheetPrint.Renderer>? = null
    private var docJob = 0

    /**
     * Sheet ⇄ Dokumen tanpa kedip: grid tetap tampil sampai halaman siap, lalu keduanya bersilang-pudar sementara
     * bilah rumus & pita alat melipat/membuka dengan halus (tidak melompat). Tidak ada dialog peredup layar;
     * penanda "Menyiapkan halaman…" kecil hanya muncul bila penyusunan halaman memang lama.
     */
    private fun setDocMode(on: Boolean) {
        if (on == docMode || !uiReady()) return
        commitTyping()
        dismissEditMenu(false)
        docMode = on
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(formulaEt.windowToken, 0)
        formulaEt.clearFocus()
        paintMode(true)
        val sheetPane = gridBox.parent as View
        val job = ++docJob
        if (!on) {
            docLoading.animate().cancel(); docLoading.visibility = View.GONE
            foldBars(true)
            crossfade(docView, sheetPane)
            updateStatus()
            return
        }
        showStatus(null)
        val title = sheetTitleEt.text.toString().ifBlank { "Spreadsheet" }
        val lines = currentGridlines
        val key = (fingerprint() * 31 + title.hashCode()) * 31 + (if (lines) 1 else 0)
        val ready = docCache?.takeIf { it.first == key }?.second
        if (ready != null) { showDoc(ready, sheetPane); return }
        val grids = sheets.map { printGrid(it, if (sheets.size == 1) title else it.name) }
        // penanda kecil hanya bila lama (>250 ms) — penyusunan cepat tidak memunculkan apa-apa
        val slow = Runnable { if (docMode && job == docJob) { docLoading.alpha = 0f; docLoading.visibility = View.VISIBLE; docLoading.animate().alpha(1f).setDuration(Motion.FAST).start() } }
        contentFrame.postDelayed(slow, 250)
        Thread {
            val r = runCatching { SheetPrint.Renderer(title, grids, lines) }
            runOnUiThread {
                contentFrame.removeCallbacks(slow)
                if (isFinishing || isDestroyed || job != docJob || !docMode) return@runOnUiThread
                r.onSuccess { rd -> docCache = key to rd; showDoc(rd, sheetPane) }
                    .onFailure { docLoading.visibility = View.GONE; setDocMode(false); fail(it) }
            }
        }.start()
    }

    private fun showDoc(rd: SheetPrint.Renderer, sheetPane: View) {
        if (docView.renderer !== rd) docView.renderer = rd
        if (docLoading.visibility == View.VISIBLE)
            docLoading.animate().alpha(0f).setDuration(Motion.FAST).withEndAction { docLoading.visibility = View.GONE }.start()
        foldBars(false)
        crossfade(sheetPane, docView)
    }

    /** [from] memudar & sedikit mengecil, [to] muncul dari sedikit lebih kecil — bersamaan, satu gerakan. */
    private fun crossfade(from: View, to: View) {
        from.animate().cancel(); to.animate().cancel()
        if (!Motion.on()) {
            from.visibility = View.GONE; from.alpha = 1f
            to.visibility = View.VISIBLE; to.alpha = 1f; to.scaleX = 1f; to.scaleY = 1f
            return
        }
        if (to.visibility != View.VISIBLE) { to.alpha = 0f; to.scaleX = 0.97f; to.scaleY = 0.97f }
        to.visibility = View.VISIBLE
        to.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).start()
        if (from.visibility == View.VISIBLE)
            from.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(Motion.FAST).setInterpolator(Motion.STANDARD)
                .withEndAction { from.visibility = View.GONE; from.alpha = 1f; from.scaleX = 1f; from.scaleY = 1f }.start()
    }

    private val folds = HashMap<View, android.animation.ValueAnimator>()
    /** Tinggi asli bilah sebelum dilipat: dipakai lagi saat dibuka, jadi tidak ada lompatan kecil di akhir animasi. */
    private val naturalH = HashMap<View, Int>()

    /** Bilah rumus & pita alat melipat ([show] = false) / membuka dengan tinggi yang mengalir, bukan hilang sekejap. */
    private fun foldBars(show: Boolean) {
        for (v in listOf(formulaBarV, ribbonV)) fold(v, show)
    }

    private fun fold(v: View, show: Boolean) {
        folds.remove(v)?.cancel()
        val lp = v.layoutParams ?: return
        if (!Motion.on() || !v.isAttachedToWindow) {
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT; v.layoutParams = lp
            v.alpha = 1f; v.visibility = if (show) View.VISIBLE else View.GONE
            return
        }
        val from = if (v.visibility == View.VISIBLE) v.height else 0
        if (!show && lp.height == android.view.ViewGroup.LayoutParams.WRAP_CONTENT && from > 0) naturalH[v] = from
        val to = if (show) naturalH[v] ?: run {
            val parentW = (v.parent as? View)?.width ?: v.width
            v.measure(View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            v.measuredHeight
        } else 0
        v.visibility = View.VISIBLE
        lp.height = from; v.layoutParams = lp
        val anim = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = Motion.MED
            interpolator = Motion.STANDARD
            addUpdateListener {
                lp.height = it.animatedValue as Int; v.layoutParams = lp
                v.alpha = if (to > 0) it.animatedFraction else 1f - it.animatedFraction
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (cancelled) return
                    folds.remove(v)
                    lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT; v.layoutParams = lp
                    v.alpha = 1f
                    v.visibility = if (show) View.VISIBLE else View.GONE
                }
            })
        }
        folds[v] = anim
        anim.start()
    }

    // ================= NILAI & TAMPILAN SEL =================

    private fun engineOf(s: SheetData): FormulaEngine = engines.getOrPut(s) {
        FormulaEngine(object : FormulaEngine.Source {
            override fun raw(r: Int, c: Int) = s.cell(r, c)?.valStr
            override fun rowCount() = s.rows.size
            override fun colCount() = s.cols
            override fun number(text: String) = parseNumber(text)
        })
    }

    /** Isi berubah: semua nilai terhitung dibuang. */
    private fun invalidateValues() { for (e in engines.values) e.clear(); shown.clear() }

    private fun key(r: Int, c: Int): Long = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)

    private fun valueOf(s: SheetData, r: Int, c: Int): Any? = engineOf(s).value(r, c)

    /** Teks tampilan sel (rumus dihitung, format angka diterapkan). */
    private fun display(s: SheetData, r: Int, c: Int): String {
        val cell = s.cell(r, c) ?: return ""
        val raw = cell.valStr
        if (raw.isEmpty()) return ""
        val isF = raw.startsWith("=") && raw.length > 1
        val fmt = cell.style.numFmt
        if (!isF && fmt == CellNumFmt.GENERAL) return raw
        return when (val v = valueOf(s, r, c)) {
            null -> ""
            is Double -> fmtNum(v, fmt)
            is Boolean -> if (v) "TRUE" else "FALSE"
            else -> v.toString()
        }
    }

    private fun fmtNum(d: Double, fmt: Int): String = when (fmt) {
        CellNumFmt.NUMBER -> nf2.format(d)
        CellNumFmt.THOUSANDS -> nf0.format(d)
        CellNumFmt.CURRENCY -> (if (d < 0) "-" else "") + currentCurrency + " " + (if (d % 1.0 != 0.0) nf2 else nf0).format(kotlin.math.abs(d))
        CellNumFmt.PERCENT -> nfPct.format(d * 100) + "%"
        CellNumFmt.DATE -> FormulaEngine.dateParts(d).let { String.format(Locale.ROOT, "%02d/%02d/%04d", it[2], it[1], it[0]) }
        else -> nfGeneral.format(d)
    }

    private fun parseNumber(raw: String): Double? {
        var s = raw.trim()
            .replace(currentCurrency, "", ignoreCase = true)
            .replace("Rp", "", ignoreCase = true)
            .replace("$", "")
            .replace("€", "")
            .replace(" ", "")
        if (s.isEmpty()) return null
        if (s.none { it.isDigit() }) return null
        if (!s.matches(NUMERIC)) return null
        // Nomor HP / kode berawalan nol bukan angka agar nol tidak hilang dan tidak ikut di-SUM.
        val unsigned = s.trimStart('-', '+')
        if (unsigned.length > 1 && unsigned.startsWith("0") && !unsigned.startsWith("0.") && !unsigned.startsWith("0,")) return null
        val hasDot = s.contains('.')
        val hasComma = s.contains(',')
        if (hasDot && hasComma) {
            s = if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
        } else if (hasComma) {
            val decimals = s.substringAfterLast(',').length
            s = if (decimals in 1..2 && s.count { it == ',' } == 1) s.replace(',', '.') else s.replace(",", "")
        } else if (hasDot) {
            val decimals = s.substringAfterLast('.').length
            val groups = s.count { it == '.' }
            s = if (groups == 1 && decimals != 3) s else s.replace(".", "")
        }
        return s.toDoubleOrNull()
    }

    private val gridSource = object : SheetGridView.Source {
        override fun sheet() = sh
        override fun text(r: Int, c: Int): String {
            val k = key(r, c)
            shown[k]?.let { return it }
            val t = display(sh, r, c)
            shown[k] = t
            return t
        }
        override fun align(r: Int, c: Int): Int {
            val a = sh.cell(r, c)?.style?.align ?: HAlign.AUTO
            if (a != HAlign.AUTO) return a
            if (sh.cell(r, c)?.valStr.isNullOrEmpty()) return HAlign.LEFT
            return when (valueOf(sh, r, c)) { is Double -> HAlign.RIGHT; is Boolean, is FormulaEngine.FErr -> HAlign.CENTER; else -> HAlign.LEFT }
        }
        override fun numeric(r: Int, c: Int) = sh.cell(r, c)?.valStr?.isNotEmpty() == true && valueOf(sh, r, c) is Double
        override fun selection() = selRange()
        override fun activeRow() = activeRow
        override fun activeCol() = activeCol
        override fun gridlines() = currentGridlines
        override fun select(kind: Int, ar: Int, ac: Int, r: Int, c: Int, phase: Int, menu: Boolean) = onGridSelect(kind, ar, ac, r, c, phase, menu)
        override fun doubleTap(r: Int, c: Int) { selectCell(r, c, false); startEditing() }
        override fun resized(col: Boolean, index: Int, from: Float, sizeDp: Float, done: Boolean) = onResized(col, index, from, sizeDp, done)
    }

    // ================= SELEKSI =================

    /** Rentang terpilih (diperluas mencakup sel gabungan yang tersentuh, seperti Excel). */
    private fun selRange(): CellRange {
        var r = CellRange(activeRow, activeCol, selR, selC).norm()
        if (sheets.isEmpty() || sh.merges.isEmpty()) return r
        var changed = true
        var guard = 0
        while (changed && guard++ < 32) {
            changed = false
            for (m in sh.merges) if (m.intersects(r) && !(r.top <= m.top && r.bottom >= m.bottom && r.left <= m.left && r.right >= m.right)) {
                r = CellRange(minOf(r.top, m.top), minOf(r.left, m.left), maxOf(r.bottom, m.bottom), maxOf(r.right, m.right)); changed = true
            }
        }
        return r
    }

    /** Seleksi dibatasi ke baris & kolom yang ada. */
    private fun selClamped(): CellRange? {
        val s = selRange()
        if (sh.rows.isEmpty()) return null
        return CellRange(s.top.coerceIn(0, sh.rows.size - 1), s.left.coerceIn(0, sh.cols - 1), s.bottom.coerceIn(0, sh.rows.size - 1), s.right.coerceIn(0, sh.cols - 1))
    }

    private fun activeStyle(): CellStyle = sh.cell(activeRow, activeCol)?.style ?: CellStyle.DEFAULT

    private fun selectCell(r: Int, c: Int, scroll: Boolean = true) {
        val pr = activeRow; val pc = activeCol
        if (pr != r || pc != c) commitTyping()
        activeRow = r.coerceAtLeast(0); activeCol = c.coerceAtLeast(0)
        selR = activeRow; selC = activeCol
        syncFormulaBar()
        if (!::grid.isInitialized) return
        grid.invalidate()
        if (scroll) grid.ensureVisible(activeRow, activeCol)
        if (pr != r || pc != c) grid.pulseActive(0.92f)
        refreshRibbon(); updateStatus()
    }

    // --- menyisipkan rujukan saat mengetik rumus ---
    private var pickStart = -1
    private var pickLen = -1
    private var pickingNow = false

    private fun picking(): Boolean {
        if (!formulaEt.hasFocus()) return false
        val t = formulaEt.text
        if (!t.startsWith("=")) return false
        if (pickLen >= 0) return true
        val at = formulaEt.selectionStart
        if (at <= 0) return false
        return t[at - 1] in "=(+-*/^,;:&<>"
    }

    private fun onGridSelect(kind: Int, ar: Int, ac: Int, r: Int, c: Int, phase: Int, menu: Boolean) {
        if (kind == SheetGridView.KIND_CELLS && picking()) {
            // mengetik rumus: ketuk / seret sel = sisipkan rujukannya (A1 atau A1:B5)
            val ref = CellRange(ar, ac, r, c).norm().ref()
            val t = formulaEt.text
            if (pickLen < 0) { pickStart = formulaEt.selectionStart.coerceIn(0, t.length); pickLen = 0 }
            val end = (pickStart + pickLen).coerceAtMost(t.length)
            pickingNow = true
            t.replace(pickStart, end, ref)
            pickingNow = false
            pickLen = ref.length
            formulaEt.setSelection((pickStart + pickLen).coerceAtMost(formulaEt.text.length))
            if (phase == SheetGridView.PHASE_END) pickLen = -1
            return
        }
        val wasSingleSame = selRange().isSingle && activeRow == ar && activeCol == ac && ar == r && ac == c
        when (kind) {
            SheetGridView.KIND_COLS -> { setActive(0, ac); selR = (sh.rows.size - 1).coerceAtLeast(0); selC = c }
            SheetGridView.KIND_ROWS -> { setActive(ar, 0); selR = r; selC = (sh.cols - 1).coerceAtLeast(0) }
            SheetGridView.KIND_ALL -> { setActive(0, 0); selR = (sh.rows.size - 1).coerceAtLeast(0); selC = (sh.cols - 1).coerceAtLeast(0) }
            else -> { setActive(ar, ac); selR = r; selC = c }
        }
        syncFormulaBar()
        grid.invalidate()
        refreshRibbon(); updateStatus()
        if (phase == SheetGridView.PHASE_END) {
            if (kind == SheetGridView.KIND_CELLS && ar == r && ac == c && !menu) grid.pulseActive(0.92f)
            // menu aksi: seleksi dengan tekan lama, kolom/baris utuh, atau ketuk lagi sel yang sudah aktif
            if (menu || kind != SheetGridView.KIND_CELLS || wasSingleSame) showSelectionMenu()
        }
    }

    private fun setActive(r: Int, c: Int) {
        if (activeRow != r || activeCol != c) commitTyping()
        activeRow = r; activeCol = c
    }

    private fun syncFormulaBar() {
        if (!::formulaEt.isInitialized) return
        val raw = sh.cell(activeRow, activeCol)?.valStr.orEmpty()
        updatingFormula = true
        formulaEt.setText(raw)
        formulaEt.setSelection(formulaEt.text.length)
        updatingFormula = false
        pickLen = -1
        val sel = selRange()
        val oneCell = sel.isSingle || sh.mergeAt(activeRow, activeCol)?.let { it.top == sel.top && it.left == sel.left && it.bottom == sel.bottom && it.right == sel.right } == true
        activeCellPill.morphText(if (oneCell) CellRange.a1(activeRow, activeCol) else "${sel.rows}B × ${sel.cols}K")
    }

    private fun startEditing() {
        formulaEt.requestFocus()
        formulaEt.setSelection(formulaEt.text.length)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(formulaEt, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Bilah status ala Excel: jumlah, rata-rata, hitung untuk seleksi lebih dari satu sel. */
    private fun updateStatus() {
        if (!::statusTv.isInitialized) return
        val s = selClamped()
        val oneMerged = s != null && sh.mergeAt(s.top, s.left)?.let { it.top == s.top && it.left == s.left && it.bottom == s.bottom && it.right == s.right } == true
        if (docMode || s == null || s.isSingle || oneMerged) { showStatus(null); return }
        val cells = s.rows.toLong() * s.cols
        if (cells > 400_000) { showStatus("${s.ref()} · ${nf0.format(cells)} sel"); return }
        var sum = 0.0; var n = 0; var filled = 0
        for (r in s.top..s.bottom) {
            val row = sh.rows.getOrNull(r) ?: continue
            for (c in s.left..minOf(s.right, row.size - 1)) {
                if (row[c].valStr.isEmpty()) continue
                filled++
                val v = valueOf(sh, r, c)
                if (v is Double) { sum += v; n++ }
            }
        }
        showStatus(if (n > 0) "Jumlah ${nfGeneral.format(sum)}  ·  Rata-rata ${nfGeneral.format(sum / n)}  ·  Hitung $filled"
            else "Hitung $filled")
    }

    /** Kapsul ringkasan muncul memantul dari bawah, teksnya bergulir saat berganti, dan tenggelam saat hilang. */
    private fun showStatus(text: String?) {
        val v = statusTv
        if (text == null) {
            if (v.visibility != View.VISIBLE || v.tag == "hiding") return
            if (!Motion.on()) { v.visibility = View.GONE; return }
            v.tag = "hiding"
            v.animate().alpha(0f).translationY(dp(10).toFloat()).scaleX(0.94f).scaleY(0.94f).setDuration(Motion.FAST)
                .setInterpolator(Motion.STANDARD).withEndAction { if (v.tag == "hiding") { v.visibility = View.GONE; v.tag = null } }.start()
            return
        }
        val wasShown = v.visibility == View.VISIBLE && v.tag != "hiding"
        v.tag = null
        if (wasShown) { if (v.text.toString() != text) v.morphText(text); return }
        v.text = text
        v.visibility = View.VISIBLE
        if (!Motion.on()) { v.alpha = 1f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; return }
        v.alpha = 0f; v.translationY = dp(12).toFloat(); v.scaleX = 0.92f; v.scaleY = 0.92f
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).start()
    }

    // ================= PENYEGARAN =================

    /** Struktur berubah (baris/kolom/templat/sheet): ukur ulang grid & pastikan sel aktif tampak. */
    private fun renderGrid() {
        invalidateValues()
        if (!::grid.isInitialized) return
        activeRow = activeRow.coerceIn(0, (sh.rows.size - 1).coerceAtLeast(0))
        activeCol = activeCol.coerceIn(0, (sh.cols - 1).coerceAtLeast(0))
        selR = selR.coerceIn(0, (sh.rows.size - 1).coerceAtLeast(0))
        selC = selC.coerceIn(0, (sh.cols - 1).coerceAtLeast(0))
        grid.structureChanged()
        grid.ensureVisible(activeRow, activeCol, animate = false)
        syncFormulaBar(); refreshRibbon(); updateStatus()
    }

    /** Isi atau gaya sel berubah: hitung ulang nilai & gambar ulang. */
    private fun refreshCells() {
        invalidateValues()
        if (::grid.isInitialized) grid.invalidate()
        updateStatus()
    }

    // ================= URUNGKAN / ULANGI =================

    private var typing: SheetData? = null
    private var typingR = 0
    private var typingC = 0
    private var typingBefore: SheetCell? = null

    private fun beginTyping() {
        if (typing != null && (typing !== sh || typingR != activeRow || typingC != activeCol)) commitTyping()
        if (typing == null) { typing = sh; typingR = activeRow; typingC = activeCol; typingBefore = sh.cell(activeRow, activeCol)?.copy() }
    }

    /** Ketikan pada satu sel = satu langkah Urungkan. */
    private fun commitTyping() {
        val s = typing ?: return
        typing = null
        val r = typingR; val c = typingC
        val before = typingBefore
        val after = s.cell(r, c)?.copy()
        if ((before?.valStr ?: "") == (after?.valStr ?: "")) return
        undo.push(UndoStack.Edit("Ketik", { putCell(s, r, c, before); afterEdit(s) }, { putCell(s, r, c, after); afterEdit(s) }))
    }

    private fun putCell(s: SheetData, r: Int, c: Int, v: SheetCell?) {
        if (v == null) { s.cell(r, c)?.let { it.valStr = ""; it.style = CellStyle.DEFAULT }; return }
        while (s.rows.size <= r) s.rows.add(mutableListOf())
        val cell = s.ensure(r, c); cell.valStr = v.valStr; cell.style = v.style
    }

    /** Setelah Urungkan/Ulangi: tampilkan sheet yang berubah. */
    private fun afterEdit(s: SheetData) {
        val i = sheets.indexOf(s)
        if (i >= 0 && i != cur) { cur = i; updateSheetBar() }
        renderGrid()
    }

    private fun doUndo() {
        commitTyping()
        val e = undo.undo()
        toast(if (e == null) "Tidak ada yang bisa diurungkan" else "Diurungkan: ${e.label}")
    }

    private fun doRedo() {
        commitTyping()
        val e = undo.redo()
        toast(if (e == null) "Tidak ada yang bisa diulangi" else "Diulangi: ${e.label}")
    }

    private class Snap(val top: Int, val cells: Array<Array<SheetCell?>>, val rowCount: Int, val cols: Int)

    private fun snapshot(s: SheetData, rg: CellRange): Snap {
        val bottom = minOf(rg.bottom, s.rows.size - 1)
        val h = (bottom - rg.top + 1).coerceAtLeast(0)
        val arr = Array(h) { i ->
            val row = s.rows[rg.top + i]
            Array(rg.cols) { j -> row.getOrNull(rg.left + j)?.copy() }
        }
        return Snap(rg.top, arr, s.rows.size, s.cols)
    }

    private fun restore(s: SheetData, snap: Snap, rg: CellRange) {
        // baris yang ditambahkan operasi ini dibuang; yang ada dikembalikan
        while (s.rows.size > snap.rowCount) s.rows.removeAt(s.rows.size - 1)
        while (s.rows.size < snap.rowCount) s.rows.add(mutableListOf())
        s.cols = snap.cols
        for (r in rg.top..minOf(rg.bottom, s.rows.size - 1)) {
            val i = r - snap.top
            for (j in 0 until rg.cols) putCell(s, r, rg.left + j, if (i in snap.cells.indices) snap.cells[i][j] else null)
        }
    }

    /** Ubah isi/gaya sel dalam [rg] dengan satu langkah Urungkan. */
    private fun editRegion(label: String, rg: CellRange, s: SheetData = sh, block: () -> Unit) {
        commitTyping()
        val before = snapshot(s, rg)
        block()
        val after = snapshot(s, rg)
        undo.push(UndoStack.Edit(label, { restore(s, before, rg); afterEdit(s) }, { restore(s, after, rg); afterEdit(s) }))
        renderGrid()
    }

    /** Keadaan struktur lembar (untuk sisip/hapus baris & kolom, ukuran, gabungan). */
    private class StructState(s: SheetData, private val deep: Boolean) {
        private val rows: List<MutableList<SheetCell>> = if (deep) s.rows.map { ArrayList(it) } else ArrayList(s.rows)
        private val cols = s.cols
        private val colW = java.util.TreeMap(s.colW.sizes)
        private val rowH = java.util.TreeMap(s.rowH.sizes)
        private val merges = ArrayList(s.merges)
        fun apply(s: SheetData) {
            s.rows.clear()
            if (deep) rows.forEach { s.rows.add(ArrayList(it)) } else s.rows.addAll(rows)
            s.cols = cols
            s.colW.sizes.clear(); s.colW.sizes.putAll(colW); s.colW.touch()
            s.rowH.sizes.clear(); s.rowH.sizes.putAll(rowH); s.rowH.touch()
            s.merges.clear(); s.merges.addAll(merges)
        }
    }

    private class FormulaChange(val cell: SheetCell, val old: String, val new: String)

    /** Perubahan struktur dengan satu langkah Urungkan. [block] mengembalikan rumus yang ditulis ulang. */
    private fun structural(label: String, deep: Boolean, s: SheetData = sh, block: () -> List<FormulaChange>) {
        commitTyping()
        val before = StructState(s, deep)
        val fx = block()
        val after = StructState(s, deep)
        undo.push(UndoStack.Edit(label, {
            before.apply(s); fx.forEach { it.cell.valStr = it.old }; afterEdit(s)
        }, {
            after.apply(s); fx.forEach { it.cell.valStr = it.new }; afterEdit(s)
        }))
        renderGrid()
    }

    /** Tulis ulang rujukan semua rumus di lembar. */
    private fun rewriteFormulas(s: SheetData, f: (String) -> String): List<FormulaChange> {
        val out = ArrayList<FormulaChange>()
        for (row in s.rows) for (cell in row) {
            val v = cell.valStr
            if (v.length > 1 && v.startsWith("=")) {
                val n = f(v)
                if (n != v) { out.add(FormulaChange(cell, v, n)); cell.valStr = n }
            }
        }
        return out
    }

    // ================= FORMAT =================

    /** Terapkan perubahan gaya ke seluruh seleksi. */
    private fun applyStyle(label: String, f: (CellStyle) -> CellStyle) {
        val rg = selClamped() ?: return
        editRegion(label, rg) {
            for (r in rg.top..rg.bottom) for (c in rg.left..rg.right) {
                val cell = sh.ensure(r, c)
                cell.style = f(cell.style)
            }
        }
        grid.pulseActive(0.9f)
    }

    private fun toggleStyle(label: String, get: (CellStyle) -> Boolean, set: (CellStyle, Boolean) -> CellStyle) {
        val v = !get(activeStyle())
        applyStyle(label) { set(it, v) }
    }

    private fun setAlign(a: Int) {
        val v = if (activeStyle().align == a) HAlign.AUTO else a
        applyStyle("Perataan") { it.copy(align = v) }
    }

    private fun pickFontSize() {
        val labels = FONT_SIZES.map { (if (it % 1f == 0f) it.toInt().toString() else it.toString()) + " pt" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Ukuran Huruf").setItems(labels) { _, i ->
            val v = FONT_SIZES[i]
            applyStyle("Ukuran huruf") { it.copy(size = if (v == CellStyle.BASE_SIZE) 0f else v) }
            fitRowsOfSelection()
        }.setNegativeButton("Batal", null).show()
    }

    /** Baris yang isinya membesar (huruf besar / dibungkus) dipaskan tingginya — seperti Excel. */
    private fun fitRowsOfSelection() {
        val rg = selClamped() ?: return
        if (rg.rows > 500) return
        for (r in rg.top..rg.bottom) {
            val fit = grid.fitRow(r)
            if (fit > sh.rowH.size(r) || (sh.rowH.sizes.containsKey(r) && fit < sh.rowH.size(r))) sh.rowH.set(r, fit)
        }
        grid.structureChanged()
    }

    private fun pickNumFmt() {
        val labels = CellNumFmt.LABELS.mapIndexed { i, l -> if (i == CellNumFmt.CURRENCY) "$l ($currentCurrency)" else l }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Format Angka").setSingleChoiceItems(labels, activeStyle().numFmt) { d, i ->
            d.dismiss()
            applyStyle("Format angka") { it.copy(numFmt = i) }
        }.setNegativeButton("Batal", null).show()
    }

    private fun clearContents() {
        val rg = selClamped() ?: return
        editRegion("Hapus isi", rg) {
            for (r in rg.top..rg.bottom) { val row = sh.rows[r]; for (c in rg.left..minOf(rg.right, row.size - 1)) row[c].valStr = "" }
        }
    }

    /** Gabung & tengahkan / pisahkan. */
    private fun toggleMerge() {
        val rg = selClamped() ?: return
        val existing = sh.merges.filter { it.intersects(rg) }
        if (existing.isNotEmpty()) {
            structural("Pisahkan sel", false) { sh.merges.removeAll(existing.toSet()); emptyList() }
            toast("Sel dipisahkan")
            return
        }
        if (rg.isSingle) { toast("Pilih beberapa sel untuk digabung (tekan lama lalu geser)"); return }
        val others = (rg.top..rg.bottom).sumOf { r -> (rg.left..rg.right).count { c -> !(r == rg.top && c == rg.left) && sh.cell(r, c)?.valStr?.isNotEmpty() == true } }
        val go = {
            commitTyping()
            val s = sh
            val beforeCells = snapshot(s, rg)
            val beforeMerges = ArrayList(s.merges)
            for (r in rg.top..rg.bottom) for (c in rg.left..rg.right) if (!(r == rg.top && c == rg.left)) s.cell(r, c)?.valStr = ""
            s.ensure(rg.top, rg.left).let { it.style = it.style.copy(align = HAlign.CENTER) }
            s.merges.add(rg)
            val afterCells = snapshot(s, rg)
            val afterMerges = ArrayList(s.merges)
            undo.push(UndoStack.Edit("Gabung sel", {
                restore(s, beforeCells, rg); s.merges.clear(); s.merges.addAll(beforeMerges); afterEdit(s)
            }, {
                restore(s, afterCells, rg); s.merges.clear(); s.merges.addAll(afterMerges); afterEdit(s)
            }))
            selectCell(rg.top, rg.left, false)
            renderGrid()
        }
        if (others > 0) AlertDialog.Builder(this).setTitle("Gabungkan sel?")
            .setMessage("Hanya isi sel kiri-atas (${CellRange.a1(rg.top, rg.left)}) yang dipertahankan; isi $others sel lain akan dihapus.")
            .setPositiveButton("Gabungkan") { _, _ -> go() }.setNegativeButton("Batal", null).show()
        else go()
    }

    // ================= BARIS & KOLOM =================

    private fun insertRows(at: Int, n: Int) {
        val count = n.coerceIn(1, 10_000)
        val pos = at.coerceIn(0, sh.rows.size)
        structural(if (count == 1) "Sisipkan baris" else "Sisipkan $count baris", false) {
            val s = sh
            // baris baru mewarisi format baris di atasnya (seperti Excel)
            val above = s.rows.getOrNull(pos - 1)
            repeat(count) { s.rows.add(pos, above?.mapTo(ArrayList()) { SheetCell("", it.style) } ?: ArrayList()) }
            s.rowH.shift(pos, count)
            val ms = s.merges.map { m -> when { m.top >= pos -> CellRange(m.top + count, m.left, m.bottom + count, m.right); m.bottom >= pos -> CellRange(m.top, m.left, m.bottom + count, m.right); else -> m } }
            s.merges.clear(); s.merges.addAll(ms)
            rewriteFormulas(s) { FormulaRefs.structural(it, true, pos, count) }
        }
        selectCell(pos, activeCol)
    }

    private fun deleteRows() {
        val rg = selClamped() ?: return
        if (rg.rows >= sh.rows.size) { toast("Minimal harus ada 1 baris"); return }
        structural(if (rg.rows == 1) "Hapus baris" else "Hapus ${rg.rows} baris", false) {
            val s = sh
            s.rows.subList(rg.top, rg.bottom + 1).clear()
            s.rowH.shift(rg.top, -rg.rows)
            val ms = s.merges.mapNotNull { m -> shrink(m.top, m.bottom, rg.top, rg.rows)?.let { (t, b) -> CellRange(t, m.left, b, m.right) } }.filter { !it.isSingle }
            s.merges.clear(); s.merges.addAll(ms)
            rewriteFormulas(s) { FormulaRefs.structural(it, true, rg.top, -rg.rows) }
        }
        selectCell(rg.top.coerceAtMost(sh.rows.size - 1), activeCol)
    }

    private fun insertCols(at: Int, n: Int) {
        val count = n.coerceIn(1, 1000)
        val pos = at.coerceIn(0, sh.cols)
        structural(if (count == 1) "Sisipkan kolom" else "Sisipkan $count kolom", true) {
            val s = sh
            for (row in s.rows) if (row.size > pos) {
                val left = row.getOrNull(pos - 1)?.style ?: CellStyle.DEFAULT
                repeat(count) { row.add(pos, SheetCell("", left)) }
            }
            s.cols += count
            s.colW.shift(pos, count)
            val ms = s.merges.map { m -> when { m.left >= pos -> CellRange(m.top, m.left + count, m.bottom, m.right + count); m.right >= pos -> CellRange(m.top, m.left, m.bottom, m.right + count); else -> m } }
            s.merges.clear(); s.merges.addAll(ms)
            rewriteFormulas(s) { FormulaRefs.structural(it, false, pos, count) }
        }
        selectCell(activeRow, pos)
    }

    private fun deleteCols() {
        val rg = selClamped() ?: return
        if (rg.cols >= sh.cols) { toast("Minimal harus ada 1 kolom"); return }
        structural(if (rg.cols == 1) "Hapus kolom" else "Hapus ${rg.cols} kolom", true) {
            val s = sh
            for (row in s.rows) if (row.size > rg.left) row.subList(rg.left, minOf(row.size, rg.right + 1)).clear()
            s.cols = (s.cols - rg.cols).coerceAtLeast(1)
            s.colW.shift(rg.left, -rg.cols)
            val ms = s.merges.mapNotNull { m -> shrink(m.left, m.right, rg.left, rg.cols)?.let { (l, r) -> CellRange(m.top, l, m.bottom, r) } }.filter { !it.isSingle }
            s.merges.clear(); s.merges.addAll(ms)
            rewriteFormulas(s) { FormulaRefs.structural(it, false, rg.left, -rg.cols) }
        }
        selectCell(activeRow, rg.left.coerceAtMost(sh.cols - 1))
    }

    /** Rentang [a, b] setelah [n] indeks mulai [at] dihapus; null bila habis. */
    private fun shrink(a: Int, b: Int, at: Int, n: Int): Pair<Int, Int>? {
        val end = at + n - 1
        if (a >= at && b <= end) return null
        val na = if (a > end) a - n else if (a >= at) at else a
        val nb = if (b > end) b - n else if (b >= at) at - 1 else b
        return if (nb < na) null else na to nb
    }

    private fun onResized(col: Boolean, index: Int, from: Float, size: Float, done: Boolean) {
        if (!done) return
        commitTyping()
        val s = sh
        val sel = selRange()
        // kolom/baris utuh terpilih & yang diubah termasuk di dalamnya → semua ikut (seperti Excel)
        val full = if (col) sel.top == 0 && sel.bottom >= s.rows.size - 1 else sel.left == 0 && sel.right >= s.cols - 1
        val targets = if (full && (if (col) index in sel.left..sel.right else index in sel.top..sel.bottom))
            (if (col) sel.left..sel.right else sel.top..sel.bottom).toList() else listOf(index)
        val axis = if (col) s.colW else s.rowH
        val before = java.util.TreeMap(axis.sizes).apply { if (from == axis.def) remove(index) else put(index, from) }
        for (t in targets) axis.set(t, size)
        val after = java.util.TreeMap(axis.sizes)
        undo.push(UndoStack.Edit(if (col) "Lebar kolom" else "Tinggi baris", {
            axis.sizes.clear(); axis.sizes.putAll(before); axis.touch(); afterEdit(s)
        }, {
            axis.sizes.clear(); axis.sizes.putAll(after); axis.touch(); afterEdit(s)
        }))
        grid.structureChanged()
    }

    private fun askSize(col: Boolean) {
        val rg = selClamped() ?: return
        val axis = if (col) sh.colW else sh.rowH
        val unit = if (col) SheetData.DP_PER_CHAR else SheetData.DP_PER_PT
        val curV = axis.size(if (col) rg.left else rg.top) / unit
        val et = field(if (col) "Lebar (karakter)" else "Tinggi (pt)", number = true).apply { setText(String.format(Locale.ROOT, "%.1f", curV)) }
        val box = vbox(12).apply { setPadding(dp(20), dp(8), dp(20), dp(8)); add(et) }
        AlertDialog.Builder(this).setTitle(if (col) "Lebar Kolom" else "Tinggi Baris")
            .setMessage(if (col) "Satuan Excel: jumlah karakter (bawaan 8,43)." else "Satuan Excel: poin (bawaan 15).")
            .setView(box)
            .setPositiveButton("Terapkan") { _, _ ->
                val v = et.text.toString().replace(',', '.').toFloatOrNull() ?: return@setPositiveButton
                val dpV = (v * unit).coerceIn(0f, if (col) 1200f else 600f)
                structural(if (col) "Lebar kolom" else "Tinggi baris", false) {
                    for (i in (if (col) rg.left..rg.right else rg.top..rg.bottom)) axis.set(i, dpV)
                    emptyList()
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun autoFitSelection() {
        val rg = selClamped() ?: return
        structural("Pas otomatis", false) {
            if (rg.cols <= 200) for (c in rg.left..rg.right) sh.colW.set(c, grid.fitColumn(c))
            if (rg.rows <= 500) for (r in rg.top..rg.bottom) sh.rowH.set(r, grid.fitRow(r))
            emptyList()
        }
    }

    private fun hide(col: Boolean) {
        val rg = selClamped() ?: return
        if (col && rg.cols >= sh.cols || !col && rg.rows >= sh.rows.size) { toast("Tidak bisa menyembunyikan semuanya"); return }
        structural(if (col) "Sembunyikan kolom" else "Sembunyikan baris", false) {
            for (i in (if (col) rg.left..rg.right else rg.top..rg.bottom)) (if (col) sh.colW else sh.rowH).set(i, 0f)
            emptyList()
        }
        toast(if (col) "Kolom disembunyikan. Tampilkan lagi lewat Tampilan → Tampilkan semua." else "Baris disembunyikan. Tampilkan lagi lewat Tampilan → Tampilkan semua.")
    }

    private fun unhide() {
        val hidden = sh.colW.sizes.count { it.value <= 0f } + sh.rowH.sizes.count { it.value <= 0f }
        if (hidden == 0) { toast("Tidak ada kolom/baris tersembunyi"); return }
        structural("Tampilkan semua", false) {
            sh.colW.sizes.entries.removeAll { it.value <= 0f }; sh.colW.touch()
            sh.rowH.sizes.entries.removeAll { it.value <= 0f }; sh.rowH.touch()
            emptyList()
        }
    }

    // ================= DATA =================

    /** Urutkan baris dalam seleksi (atau seluruh tabel bila satu sel/kolom dipilih) menurut kolom sel aktif. */
    private fun sortSelection(asc: Boolean) {
        val sel = selClamped() ?: return
        val whole = sel.cols == 1
        var top = if (whole) 0 else sel.top
        val bottom = if (whole) sh.rows.size - 1 else sel.bottom
        val left = if (whole) 0 else sel.left
        val right = if (whole) sh.cols - 1 else sel.right
        // baris judul (semua sel berisi tebal) tidak ikut diurutkan
        val first = sh.rows.getOrNull(top)
        if (first != null && first.any { it.valStr.isNotEmpty() } && first.filter { it.valStr.isNotEmpty() }.all { it.isBold } && bottom > top) top++
        if (bottom <= top) { toast("Tidak ada data untuk diurutkan"); return }
        val col = activeCol.coerceIn(left, right)
        val rg = CellRange(top, left, bottom, right)
        val keys = (top..bottom).map { r -> r to valueOf(sh, r, col) }
        val cmp = Comparator<Pair<Int, Any?>> { a, b ->
            val x = a.second; val y = b.second
            // kosong selalu di akhir
            if (x == null || x == "") return@Comparator if (y == null || y == "") 0 else 1
            if (y == null || y == "") return@Comparator -1
            val rank = { v: Any -> when (v) { is Double -> 0; is String -> 1; is Boolean -> 2; else -> 3 } }
            val ra = rank(x); val rb = rank(y)
            val c = if (ra != rb) ra.compareTo(rb) else when (x) {
                is Double -> x.compareTo(y as Double)
                is String -> x.lowercase(Locale.ROOT).compareTo((y as String).lowercase(Locale.ROOT))
                is Boolean -> x.compareTo(y as Boolean)
                else -> 0
            }
            if (asc) c else -c
        }
        val order = keys.sortedWith(cmp).map { it.first }
        editRegion(if (asc) "Urutkan A→Z" else "Urutkan Z→A", rg) {
            val copies = order.map { r -> (left..right).map { c -> sh.cell(r, c)?.copy() } }
            copies.forEachIndexed { i, cells ->
                val dst = top + i; val src = order[i]
                cells.forEachIndexed { j, cell ->
                    val c = left + j
                    if (cell == null) sh.cell(dst, c)?.let { it.valStr = ""; it.style = CellStyle.DEFAULT }
                    else {
                        val t = sh.ensure(dst, c)
                        t.valStr = if (cell.valStr.startsWith("=")) FormulaRefs.shift(cell.valStr, dst - src, 0) else cell.valStr
                        t.style = cell.style
                    }
                }
            }
        }
        toast((if (asc) "Diurutkan A→Z" else "Diurutkan Z→A") + " menurut kolom ${CellRange.colName(col)}")
    }

    /** Isi ke bawah / kanan (Ctrl+D / Ctrl+R): baris/kolom pertama seleksi disalin, rumus ikut bergeser. */
    private fun fill(down: Boolean) {
        val rg = selClamped() ?: return
        if (if (down) rg.rows < 2 else rg.cols < 2) {
            toast(if (down) "Pilih beberapa baris dulu (tekan lama lalu geser ke bawah)" else "Pilih beberapa kolom dulu (tekan lama lalu geser ke kanan)"); return
        }
        editRegion(if (down) "Isi ke bawah" else "Isi ke kanan", rg) {
            for (r in rg.top..rg.bottom) for (c in rg.left..rg.right) {
                val sr = if (down) rg.top else r; val sc = if (down) c else rg.left
                if (r == sr && c == sc) continue
                val src = sh.cell(sr, sc)
                val t = sh.ensure(r, c)
                val v = src?.valStr.orEmpty()
                t.valStr = if (v.startsWith("=")) FormulaRefs.shift(v, r - sr, c - sc) else v
                t.style = src?.style ?: CellStyle.DEFAULT
            }
        }
    }

    private fun autoSum() {
        val sel = selClamped() ?: return
        if (!sel.isSingle && sel.rows >= 2) {
            // rentang terpilih: jumlah tiap kolom di bawahnya
            val target = sel.bottom + 1
            editRegion("AutoSum", CellRange(target, sel.left, target, sel.right)) {
                while (sh.rows.size <= target) sh.rows.add(mutableListOf())
                for (c in sel.left..sel.right) {
                    val name = CellRange.colName(c)
                    sh.ensure(target, c).let { it.valStr = "=SUM($name${sel.top + 1}:$name${sel.bottom + 1})"; it.style = it.style.copy(bold = true) }
                }
            }
            selectCell(target, sel.left)
            return
        }
        // satu sel: jumlahkan angka berurutan di atasnya
        var top = activeRow - 1
        while (top >= 0 && valueOf(sh, top, activeCol) is Double) top--
        if (top + 1 > activeRow - 1) { toast("Tidak ada angka di atas sel ini"); return }
        val name = CellRange.colName(activeCol)
        editRegion("AutoSum", CellRange(activeRow, activeCol, activeRow, activeCol)) {
            sh.ensure(activeRow, activeCol).valStr = "=SUM($name${top + 2}:$name$activeRow)"
        }
        syncFormulaBar()
    }

    // ================= PAPAN KLIP =================

    private class Clip(val cells: List<List<SheetCell?>>, val top: Int, val left: Int, val text: String)
    private var clip: Clip? = null

    private fun copy(cut: Boolean) {
        val rg = selClamped() ?: return
        commitTyping()
        if (rg.rows.toLong() * rg.cols > 2_000_000) { toast("Seleksi terlalu besar untuk disalin"); return }
        val cells = (rg.top..rg.bottom).map { r -> (rg.left..rg.right).map { c -> sh.cell(r, c)?.copy() } }
        val text = (rg.top..rg.bottom).joinToString("\n") { r -> (rg.left..rg.right).joinToString("\t") { c -> display(sh, r, c) } }
        clip = Clip(cells, rg.top, rg.left, text)
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Sel spreadsheet", text))
        } catch (_: Exception) {}
        if (cut) {
            editRegion("Potong", rg) {
                for (r in rg.top..rg.bottom) for (c in rg.left..rg.right) sh.cell(r, c)?.let { it.valStr = ""; it.style = CellStyle.DEFAULT }
            }
            toast("${rg.rows * rg.cols} sel dipotong — pilih sel tujuan lalu Tempel")
        } else toast("${rg.rows * rg.cols} sel disalin")
    }

    private fun paste() {
        val sys = try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null }
        val c = clip
        val r0 = activeRow; val c0 = activeCol
        if (c != null && (sys == null || sys == c.text)) {
            // dari editor ini: isi + format, rumus ikut bergeser
            val h = c.cells.size; val w = c.cells.maxOf { it.size }
            val rg = CellRange(r0, c0, r0 + h - 1, c0 + w - 1)
            editRegion("Tempel", rg) {
                while (sh.rows.size <= rg.bottom) sh.rows.add(mutableListOf())
                if (rg.right >= sh.cols) sh.cols = rg.right + 1
                c.cells.forEachIndexed { i, row -> row.forEachIndexed { j, cell ->
                    val t = sh.ensure(r0 + i, c0 + j)
                    val v = cell?.valStr.orEmpty()
                    t.valStr = if (v.startsWith("=")) FormulaRefs.shift(v, r0 - c.top, c0 - c.left) else v
                    t.style = cell?.style ?: CellStyle.DEFAULT
                } }
            }
            activeRow = r0; activeCol = c0; selR = rg.bottom; selC = rg.right
            syncFormulaBar(); grid.invalidate(); updateStatus()
            return
        }
        if (sys.isNullOrEmpty()) { toast("Papan klip kosong"); return }
        // teks dari aplikasi lain: baris = baris baru, tab = kolom (tabel dari web/Excel/Sheets)
        val data = sys.replace("\r\n", "\n").trimEnd('\n').split('\n').map { it.split('\t') }
        val w = data.maxOf { it.size }
        val rg = CellRange(r0, c0, r0 + data.size - 1, c0 + w - 1)
        editRegion("Tempel", rg) {
            while (sh.rows.size <= rg.bottom) sh.rows.add(mutableListOf())
            if (rg.right >= sh.cols) sh.cols = rg.right + 1
            data.forEachIndexed { i, row -> row.forEachIndexed { j, v -> sh.ensure(r0 + i, c0 + j).valStr = v } }
        }
        activeRow = r0; activeCol = c0; selR = rg.bottom; selC = rg.right
        syncFormulaBar(); grid.invalidate(); updateStatus()
    }

    // ================= MENU SELEKSI =================

    private var editMenu: View? = null

    /**
     * Menu aksi seleksi ala iOS: kapsul kaca mendatar di atas seleksi (Potong · Salin · Tempel · …), muncul memantul.
     * Ketuk "›" untuk alat lainnya (kapsulnya berganti isi dengan geser), "‹" kembali. Hilang saat grid disentuh.
     */
    private fun showSelectionMenu() {
        dismissEditMenu(false)
        val rg = selRange()
        val merged = sh.merges.any { it.intersects(rg) }
        val main = listOf<Pair<String, () -> Unit>>(
            "Potong" to { copy(true) }, "Salin" to { copy(false) }, "Tempel" to { paste() },
            "Hapus" to { clearContents() }, "Sunting" to { startEditing() }
        )
        val more = listOf<Pair<String, () -> Unit>>(
            "Warna isi" to { pickFill() }, "Warna teks" to { pickTextColor() },
            (if (merged) "Pisahkan" else "Gabung") to { toggleMerge() },
            "Sisip baris" to { insertRows(rg.top, rg.rows) }, "Sisip kolom" to { insertCols(rg.left, rg.cols) },
            "Hapus baris" to { deleteRows() }, "Hapus kolom" to { deleteCols() },
            "A→Z" to { sortSelection(true) }, "Z→A" to { sortSelection(false) },
            "Isi ke bawah" to { fill(true) }, "Pas otomatis" to { autoFitSelection() }
        )
        val card = FrameLayout(this).apply {
            background = shape(if (C.dark) 0xFF3A3A3C.toInt() else 0xF2222326.toInt(), 100)
            elevation = dp(10).toFloat()
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        scroll.addView(row)
        card.addView(scroll, FrameLayout.LayoutParams(WRAP, dp(44)))
        fun item(label: String, desc: String = label, act: () -> Unit) {
            if (row.childCount > 0) row.add(View(this).apply { background = shape(0x33FFFFFF, 100) }, w = dp(1), h = dp(18))
            row.add(tv(label, 14f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(13), 0, dp(13), 0)
                background = rippleBg(100)
                isClickable = true
                contentDescription = desc
                setOnClickListener { act() }
                springPress(0.92f)
            }, w = WRAP, h = dp(38))
        }
        fun fillRow(page: Int) {
            row.removeAllViews()
            if (page == 0) {
                for ((l, f) in main) item(l) { dismissEditMenu(true); f() }
                item("›", "Aksi lainnya") { row.swapContent(Motion.SHIFT_DP, 0) { fillRow(1) }; scroll.scrollTo(0, 0) }
            } else {
                item("‹", "Kembali") { row.swapContent(-Motion.SHIFT_DP, 0) { fillRow(0) }; scroll.scrollTo(0, 0) }
                for ((l, f) in more) item(l) { dismissEditMenu(true); f() }
            }
        }
        fillRow(0)
        contentFrame.addView(card, FrameLayout.LayoutParams(WRAP, WRAP))
        editMenu = card
        card.visibility = View.INVISIBLE
        card.post {
            // di atas seleksi bila muat; bila tidak, di bawahnya — selalu di dalam layar
            val (x1, y1) = grid.rangeCorner(minOf(rg.top, sh.rows.size - 1) - 1, minOf(rg.left, sh.cols - 1) - 1)
            val (x2, y2) = grid.rangeCorner(minOf(rg.bottom, sh.rows.size - 1), minOf(rg.right, sh.cols - 1))
            val gl = IntArray(2); val fl = IntArray(2)
            grid.getLocationInWindow(gl); contentFrame.getLocationInWindow(fl)
            val ox = gl[0] - fl[0]; val oy = gl[1] - fl[1]
            val w = card.width.coerceAtMost(contentFrame.width - dp(24))
            if (card.width > w) { card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply { width = w } }
            val cx = ox + (x1 + x2) / 2f
            card.translationX = (cx - w / 2f).coerceIn(dp(12).toFloat(), (contentFrame.width - w - dp(12)).toFloat().coerceAtLeast(dp(12).toFloat()))
            val above = oy + y1 - card.height - dp(10)
            val below = oy + y2 + dp(14)
            card.translationY = (if (above >= dp(8)) above else below).coerceIn(dp(8).toFloat(), (contentFrame.height - card.height - dp(8)).toFloat().coerceAtLeast(0f))
            card.pivotX = (cx - card.translationX).coerceIn(0f, w.toFloat()); card.pivotY = if (above >= dp(8)) card.height.toFloat() else 0f
            card.visibility = View.VISIBLE
            if (Motion.on()) {
                card.alpha = 0f; card.scaleX = 0.86f; card.scaleY = 0.86f
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).start()
            }
        }
    }

    private fun dismissEditMenu(animate: Boolean = true) {
        val v = editMenu ?: return
        editMenu = null
        if (!animate || !Motion.on()) { contentFrame.removeView(v); return }
        v.animate().cancel()
        v.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(Motion.FAST).setInterpolator(Motion.STANDARD)
            .withEndAction { contentFrame.removeView(v) }.start()
    }

    // ================= SHEET (TAB BAWAH) =================

    private fun switchSheet(idx: Int, dir: Int) {
        commitTyping()
        cur = idx
        activeRow = 0; activeCol = 0; selR = 0; selC = 0
        grid.scrollToOrigin()
        renderGrid()
        gridBox.cascadeIn(dyDp = if (dir == 0) 14 else 0, dxDp = 22 * dir)
        updateSheetBar(true)
    }

    private fun updateSheetBar(bounce: Boolean = false) {
        if (!::sheetBarContainer.isInitialized) return
        sheetBarContainer.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(2)) }
        sheets.forEachIndexed { idx, s ->
            val isSel = idx == cur
            row.add(tv(s.name, 12f, if (isSel) C.ON_TEAL else C.TEXT, true).apply {
                background = if (isSel) shape(C.TEAL, 100) else shape(C.SURFACE, 100, C.LINE, 1)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true
                springPress(0.92f)
                if (isSel && bounce) boing(0.85f)
                setOnClickListener { if (cur != idx) switchSheet(idx, if (idx > cur) 1 else -1) }
                setOnLongClickListener { showRenameSheetDialog(idx); true }
            }, w = WRAP, side = 3)
        }
        row.add(tv("+", 16f, C.TEAL, true).apply {
            gravity = Gravity.CENTER
            background = shape(C.TEAL_SOFT, 100)
            includeFontPadding = false
            contentDescription = "Tambah sheet"
            setPadding(dp(13), dp(5), dp(13), dp(7))
            isClickable = true
            springPress(0.9f)
            setOnClickListener {
                commitTyping()
                var n = sheets.size + 1
                while (sheets.any { it.name.equals("Sheet$n", true) }) n++
                val s = SheetData("Sheet$n", MutableList(30) { mutableListOf<SheetCell>() }, 8)
                sheets.add(s)
                undo.push(UndoStack.Edit("Tambah sheet", {
                    sheets.remove(s); cur = cur.coerceIn(0, sheets.size - 1); updateSheetBar(); renderGrid()
                }, {
                    sheets.add(s); cur = sheets.indexOf(s); updateSheetBar(); renderGrid()
                }))
                switchSheet(sheets.size - 1, 0)
                toast("${s.name} ditambahkan")
            }
        }, w = WRAP, side = 6)
        sheetBarContainer.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) },
            LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun showRenameSheetDialog(sheetIdx: Int) {
        val s = sheets.getOrNull(sheetIdx) ?: return
        val et = field("Nama Sheet").apply { setText(s.name) }
        val box = vbox(12).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.add(tv("Kelola Sheet: ${s.name}", 15f, C.TEXT, true))
        box.add(et, top = 8)
        val b = AlertDialog.Builder(this).setTitle("Ubah Nama / Hapus Sheet").setView(box)
            .setPositiveButton("Simpan Nama") { _, _ ->
                val old = s.name
                val n = et.text.toString().trim().ifBlank { old }
                if (n != old) {
                    s.name = n
                    undo.push(UndoStack.Edit("Ganti nama sheet", { s.name = old; updateSheetBar() }, { s.name = n; updateSheetBar() }))
                    updateSheetBar()
                }
            }
        if (sheets.size > 1) b.setNeutralButton("Hapus Sheet") { _, _ ->
            AlertDialog.Builder(this).setTitle("Hapus ${s.name}?").setMessage("Seluruh isi sheet ini akan dihapus. Bisa diurungkan.")
                .setPositiveButton("Hapus") { _, _ ->
                    commitTyping()
                    val at = sheets.indexOf(s)
                    sheets.removeAt(at)
                    undo.push(UndoStack.Edit("Hapus sheet", {
                        sheets.add(at.coerceAtMost(sheets.size), s); cur = sheets.indexOf(s); updateSheetBar(); renderGrid()
                    }, {
                        sheets.remove(s); cur = cur.coerceIn(0, sheets.size - 1); updateSheetBar(); renderGrid()
                    }))
                    if (at < cur) cur--
                    cur = cur.coerceIn(0, sheets.size - 1)
                    activeRow = 0; activeCol = 0; selR = 0; selC = 0
                    renderGrid(); updateSheetBar()
                    toast("Sheet dihapus")
                }.setNegativeButton("Batal", null).show()
        }
        b.setNegativeButton("Batal", null).show()
    }

    // ================= DIALOG LAIN =================

    private fun showGoTo() {
        val et = field("Contoh: B12 atau A1:D20", multi = false)
        val box = vbox(12).apply { setPadding(dp(20), dp(8), dp(20), dp(8)); add(et) }
        AlertDialog.Builder(this).setTitle("Pergi ke Sel").setView(box)
            .setPositiveButton("Pergi") { _, _ ->
                val t = et.text.toString().trim().uppercase().replace("$", "")
                val m = Regex("^([A-Z]{1,3})([0-9]{1,7})(?::([A-Z]{1,3})([0-9]{1,7}))?$").matchEntire(t)
                if (m == null) { toast("Alamat sel tidak dikenal"); return@setPositiveButton }
                fun col(s: String): Int { var n = 0; for (ch in s) n = n * 26 + (ch - 'A' + 1); return n - 1 }
                val r1 = m.groupValues[2].toInt() - 1; val c1 = col(m.groupValues[1])
                val r2 = m.groupValues[4].toIntOrNull()?.minus(1) ?: r1
                val c2 = if (m.groupValues[3].isNotEmpty()) col(m.groupValues[3]) else c1
                if (r1 < 0 || r2 < 0) { toast("Alamat sel tidak dikenal"); return@setPositiveButton }
                // pergi ke luar tabel = tabel bertambah (seperti Excel yang tak berujung)
                while (sh.rows.size <= maxOf(r1, r2)) sh.rows.add(mutableListOf())
                if (maxOf(c1, c2) >= sh.cols) sh.cols = maxOf(c1, c2) + 1
                grid.structureChanged()
                selectCell(r1, c1)
                selR = r2; selC = c2
                syncFormulaBar(); grid.invalidate(); updateStatus()
            }.setNegativeButton("Batal", null).show()
    }

    private fun showFormulaAssistantSheet() {
        val sel = selRange()
        val colName = CellRange.colName(activeCol)
        val cellName = CellRange.a1(activeRow, activeCol)
        val range = if (!sel.isSingle) sel.ref() else if (activeRow > 0) "${colName}1:${colName}$activeRow" else "${colName}1"
        val formulas = listOf(
            Triple("Jumlah (SUM)", "=SUM($range)", "Jumlahkan angka di $range"),
            Triple("Rata-rata (AVERAGE)", "=AVERAGE($range)", "Rata-rata angka di $range"),
            Triple("Hitung angka (COUNT)", "=COUNT($range)", "Banyaknya sel berisi angka"),
            Triple("Hitung terisi (COUNTA)", "=COUNTA($range)", "Banyaknya sel yang tidak kosong"),
            Triple("Maksimum (MAX)", "=MAX($range)", "Nilai tertinggi"),
            Triple("Minimum (MIN)", "=MIN($range)", "Nilai terendah"),
            Triple("Jika (IF)", "=IF(${colName}1>100,\"Lulus\",\"Tidak\")", "Pilih hasil menurut syarat"),
            Triple("Jumlah bersyarat (SUMIF)", "=SUMIF(A1:A10,\"Apel\",B1:B10)", "Jumlahkan B bila A = \"Apel\""),
            Triple("Hitung bersyarat (COUNTIF)", "=COUNTIF($range,\">0\")", "Banyaknya sel yang memenuhi syarat"),
            Triple("Cari data (VLOOKUP)", "=VLOOKUP(${colName}1,A1:B10,2,FALSE)", "Cari di kolom pertama, ambil kolom ke-2"),
            Triple("Gabung teks (CONCAT)", "=CONCAT(A1,\" \",B1)", "Gabungkan teks beberapa sel"),
            Triple("Bulatkan (ROUND)", "=ROUND($cellName,2)", "Bulatkan ke 2 desimal"),
            Triple("Tanggal hari ini (TODAY)", "=TODAY()", "Pakai format angka Tanggal"),
            Triple("Abaikan galat (IFERROR)", "=IFERROR(A1/B1,0)", "Tampilkan 0 bila ada galat")
        )
        sheet { body, dismiss ->
            sheetHeader(body, R.drawable.ic_sheet, C.ACCENT, C.BLUE_BG, "Asisten Rumus", "Sel aktif: $cellName • ketuk untuk menyisipkan", dismiss)
            val pad = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
            val list = vbox(8)
            formulas.forEach { (name, f, desc) ->
                list.add(vbox(4).apply {
                    background = shape(C.SURFACE, 12, C.LINE, 1)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        dismiss()
                        formulaEt.setText(f)
                        formulaEt.setSelection(f.length)
                        commitTyping()
                        toast("Rumus diterapkan pada $cellName")
                    }
                    add(tv(name, 14f, C.ACCENT, true)); add(tv(f, 13f, C.TEXT, true)); add(tv(desc, 11f, C.MUTED))
                })
            }
            pad.add(ScrollView(this@SheetEditorActivity).apply { addView(list) }, h = dp(380))
            body.add(pad)
        }
    }

    private fun showFindReplaceSheet() {
        val fFind = field("Cari kata / teks…")
        val fReplace = field("Ganti dengan…")
        sheet { body, dismiss ->
            sheetHeader(body, R.drawable.ic_search, C.ACCENT, C.BLUE_BG, "Cari & Ganti", "Di seluruh sel sheet ini (bisa diurungkan)", dismiss)
            val pad = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
            pad.add(vbox(10).apply {
                add(tv("Kata yang Dicari", 12f, C.MUTED)); add(fFind)
                add(tv("Ganti Dengan", 12f, C.MUTED), top = 4); add(fReplace)
            })
            val actions = hbox()
            actions.add(btn("Cari", false, C.ACCENT, R.drawable.ic_search) {
                val q = fFind.text.toString().trim()
                if (q.isEmpty()) { toast("Ketik kata yang ingin dicari"); return@btn }
                // sel berikutnya setelah sel aktif yang memuat teks itu
                val cols = sh.cols.coerceAtLeast(1)
                val total = sh.rows.size.toLong() * cols
                val start = activeRow.toLong() * cols + activeCol + 1
                var k = 0L
                while (k < total) {
                    val idx = (start + k) % total
                    val r = (idx / cols).toInt(); val c = (idx % cols).toInt()
                    if (sh.cell(r, c)?.valStr?.contains(q, true) == true || display(sh, r, c).contains(q, true)) {
                        dismiss(); selectCell(r, c); return@btn
                    }
                    k++
                }
                toast("\"$q\" tidak ditemukan")
            }, w = 0, weight = 1f)
            actions.add(btn("Ganti Semua", true, C.PRIMARY) {
                val q = fFind.text.toString().trim()
                val rText = fReplace.text.toString()
                if (q.isEmpty()) { toast("Ketik kata yang ingin dicari"); return@btn }
                val count = sh.rows.sumOf { row -> row.count { it.valStr.contains(q, ignoreCase = true) } }
                if (count == 0) { toast("Teks \"$q\" tidak ditemukan"); return@btn }
                val rg = CellRange(0, 0, (sh.rows.size - 1).coerceAtLeast(0), (sh.cols - 1).coerceAtLeast(0))
                editRegion("Ganti semua", rg) {
                    for (row in sh.rows) for (cell in row) if (cell.valStr.contains(q, ignoreCase = true)) cell.valStr = cell.valStr.replace(q, rText, ignoreCase = true)
                }
                syncFormulaBar(); toast("Berhasil mengganti di $count sel"); dismiss()
            }, w = 0, weight = 1f, side = 4)
            pad.add(actions, top = 16)
            body.add(pad)
        }
    }

    private fun showTemplatePicker() {
        val names = arrayOf("Laporan Keuangan & Sales", "Invoice / Tagihan", "Presensi & Daftar Hadir", "Kosongkan Tabel")
        val keys = arrayOf<String?>(null, TEMPLATE_INVOICE, TEMPLATE_PRESENSI, TEMPLATE_BLANK)
        AlertDialog.Builder(this).setTitle("Pilih Templat").setItems(names) { _, idx ->
            commitTyping()
            val t = templateSheet(keys[idx]).second
            t.name = sh.name
            val at = cur
            val old = sheets[at]
            sheets[at] = t
            undo.push(UndoStack.Edit("Templat", { sheets[at] = old; cur = at; updateSheetBar(); renderGrid() }, { sheets[at] = t; cur = at; updateSheetBar(); renderGrid() }))
            activeRow = 0; activeCol = 0; selR = 0; selC = 0
            grid.scrollToOrigin()
            renderGrid()
        }.setNegativeButton("Batal", null).show()
    }

    private fun showExcelSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_sheet, C.TEAL, C.TEAL_BG, "Pengaturan Spreadsheet", "Mata uang & garis kisi", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
        pad.add(tv("Simbol Mata Uang", 14f, C.TEXT, true))
        val currencies = listOf("Rp (Rupiah)", "$ (Dollar)", "€ (Euro)")
        val currVal = listOf("Rp", "$", "€")
        val currRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        currencies.forEachIndexed { idx, cName ->
            val isSel = currVal[idx] == currentCurrency
            currRow.add(tv(cName, 12f, if (isSel) C.ON_PRIMARY else C.TEXT, true).apply {
                background = shape(if (isSel) C.TEAL else C.SURFACE2, 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentCurrency = currVal[idx]
                    SheetSettings.setCurrencySymbol(this@SheetEditorActivity, currVal[idx])
                    dismiss(); refreshCells()
                }
            }, w = 0, weight = 1f, side = 4)
        }
        pad.add(currRow, top = 8)
        pad.add(tv("Garis Kisi (Gridlines)", 14f, C.TEXT, true), top = 16)
        val gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Tampilkan Garis", "Sembunyikan Garis").forEachIndexed { idx, gName ->
            val isSel = (idx == 0) == currentGridlines
            gridRow.add(tv(gName, 12f, if (isSel) C.ON_PRIMARY else C.TEXT, true).apply {
                background = shape(if (isSel) C.TEAL else C.SURFACE2, 8)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentGridlines = idx == 0
                    SheetSettings.setShowGridlines(this@SheetEditorActivity, currentGridlines)
                    dismiss(); buildTools(); refreshCells()
                }
            }, w = 0, weight = 1f, side = 4)
        }
        pad.add(gridRow, top = 8)
        body.add(pad)
    }

    // ================= EKSPOR =================

    /** Isi lembar untuk Word / PDF / Mode Dokumen: nilai tampilan, gaya, lebar kolom, gabungan (thread UI). */
    private fun printGrid(s: SheetData, name: String): SheetLayout.Grid {
        val rows = s.rows.indices.map { r ->
            val row = s.rows[r]
            (0 until minOf(s.cols, row.size)).map { c ->
                val cell = row[c]
                val v = if (cell.valStr.isNotEmpty()) valueOf(s, r, c) else null
                val st = cell.style
                SheetLayout.Cell(display(s, r, c), st.bold, v is Double, st.fill, st.italic, st.underline, st.strike, st.color, st.align)
            }
        }
        // skala lebar = skala huruf (sel 12 sp di layar → 9 pt di halaman): isi yang muat di layar juga muat dicetak
        val widths = FloatArray(s.cols) { c -> s.colW.size(c) * 0.75f }
        val hidden = s.rowH.sizes.filter { it.value <= 0f }.keys
        return SheetLayout.Grid(name, rows, widths, hidden, ArrayList(s.merges))
    }

    private fun showExportChooser() {
        commitTyping()
        sheet { body, dismiss ->
            sheetHeader(body, R.drawable.ic_download, C.ACCENT, C.BLUE_BG, "Ekspor Spreadsheet", "Pilih format berkas", dismiss)
            val pad = vbox(8).apply { setPadding(dp(16), dp(4), dp(16), dp(20)) }
            fun opt(icon: Int, title: String, desc: String, go: () -> Unit) {
                pad.add(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = shape(C.SURFACE, 14, C.LINE, 1)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    isClickable = true
                    springPress(0.98f)
                    setOnClickListener { dismiss(); go() }
                    add(iconBadge(icon, C.ACCENT, C.BLUE_BG, 12, 20), w = dp(40), h = dp(40))
                    add(vbox().apply { add(tv(title, 15f, C.TEXT, true)); add(tv(desc, 12f, C.MUTED), top = 2) }, w = 0, weight = 1f, side = 12)
                })
            }
            opt(R.drawable.ic_sheet, "Excel (.xlsx)", "Semua sheet, rumus, warna, lebar kolom, sel gabungan + salinan PDF") { exportAs("xlsx") }
            opt(R.drawable.ic_doc, "Word (.docx)", "Tabel Word rapi seperti di Mode Dokumen") { exportAs("docx") }
            opt(R.drawable.ic_pdf, "PDF", "Halaman A4 siap cetak") { exportAs("pdf") }
            body.add(pad)
        }
    }

    private fun exportAs(ext: String) {
        val title = sheetTitleEt.text.toString().trim().ifBlank { "Spreadsheet_Excel" }
        val defaultName = sourceName ?: Files.baseName(title)
        val (head, sub) = when (ext) {
            "docx" -> "Ekspor ke Word" to "Dokumen Word dengan tabel lembar kerja"
            "pdf" -> "Ekspor ke PDF" to "Halaman A4 siap cetak"
            else -> "Ekspor ke Excel" to "File Excel (.xlsx) asli + salinan PDF siap cetak"
        }
        saveSheet(head, sub, defaultName, null, 0, "Ekspor Sekarang", ext = ext) { cleanName, _ ->
            val lines = currentGridlines
            // nilai sel dihitung di thread UI (mesin rumus tidak dipakai bersamaan dari thread lain)
            val grids = sheets.map { printGrid(it, if (sheets.size == 1) title else it.name) }
            when (ext) {
                "docx" -> {
                    val out = Files.namedOut(this, cleanName, "docx")
                    work("Mengekspor ke Word…", { SheetDocx.write(out, title, grids, lines) }) { r ->
                        r.onSuccess { markClean(); SheetDrafts.clear(this, draftId); showResult("Dokumen Word Berhasil Dibuat", out, DOCX) }.onFailure { fail(it) }
                    }
                }
                "pdf" -> {
                    val out = Files.namedOut(this, cleanName, "pdf")
                    work("Mengekspor ke PDF…", { SheetPrint.pdf(title, grids, out, lines) }) { r ->
                        r.onSuccess { markClean(); SheetDrafts.clear(this, draftId); showResult("PDF Berhasil Dibuat", out, PDF) }.onFailure { fail(it) }
                    }
                }
                else -> {
                    val xlsxFile = Files.namedOut(this, cleanName, "xlsx")
                    val pdfFile = Files.namedOut(this, cleanName, "pdf")
                    val book = sheets.map { s -> xlsxSheet(s, if (sheets.size == 1) title else s.name) }
                    work("Mengekspor Spreadsheet Excel…", {
                        XlsxWriter.writeBook(xlsxFile, book)
                        SheetPrint.pdf(title, grids, pdfFile, lines)
                    }) { res ->
                        res.onSuccess {
                            markClean(); SheetDrafts.clear(this, draftId)
                            showResult("Spreadsheet Berhasil Diekspor", xlsxFile, XLSX, "PDF juga dibuat: ${pdfFile.name}")
                        }.onFailure { fail(it) }
                    }
                }
            }
        }
    }

    /** Satu lembar → XlsxWriter (rumus asli + hasil, gaya, lebar, tinggi, gabungan). Thread UI. */
    private fun xlsxSheet(s: SheetData, name: String): XlsxWriter.Sheet {
        val rows = s.rows.map { row -> row.map { if (it.valStr.startsWith("=") && it.valStr.length > 1) "" else it.valStr } }
        val fx = HashMap<Long, Pair<String, Any?>>()
        s.rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, cell ->
                val raw = cell.valStr.trim()
                if (raw.length > 1 && raw.startsWith("=")) fx[key(r, c)] = raw to valueOf(s, r, c).takeUnless { it is FormulaEngine.FErr }
            }
        }
        val styles = HashMap<Long, CellStyle>()
        s.rows.forEachIndexed { r, row -> row.forEachIndexed { c, cell -> if (!cell.style.isDefault) styles[key(r, c)] = cell.style } }
        val widths = HashMap<Int, Float>()
        for ((c, w) in s.colW.sizes) widths[c] = w / SheetData.DP_PER_CHAR
        val heights = HashMap<Int, Float>()
        for ((r, h) in s.rowH.sizes) heights[r] = h / SheetData.DP_PER_PT
        return XlsxWriter.Sheet(
            name, rows, emptySet(), s.cols,
            formulas = { r, c -> fx[key(r, c)] },
            styleOf = { r, c -> styles[key(r, c)] },
            colWidths = widths, rowHeights = heights, merges = ArrayList(s.merges), currency = currentCurrency
        )
    }

    private fun showResult(title: String, file: File, mime: String, extra: String = "") {
        val uri = Files.uriFor(this, file)
        Recent.add(this, uri, file.name, mime)
        Meta.setLastJob(this, title, file)
        resultSheet(
            title, "${file.name} • ${Files.fmtSize(file.length())}", extra, "Buka Dokumen",
            { Files.open(this, uri, mime) },
            listOf(
                SheetAction(R.drawable.ic_download, "Simpan salinan ke DepUp Office") {
                    work("Menyimpan…", { Files.saveToDownloads(this, uri, file.name, mime) }) { r ->
                        r.onSuccess { toast("Tersimpan di Documents/DepUp Office") }.onFailure { fail(it) }
                    }
                },
                SheetAction(R.drawable.ic_chat, "Kirim via WhatsApp", C.TEAL, C.TEAL_BG) { Files.share(this, uri, mime, true) },
                SheetAction(R.drawable.ic_upload, "Bagikan ke Aplikasi Lain", C.INDIGO, C.INDIGO_BG) { Files.share(this, uri, mime, false) }
            )
        )
    }

    private fun getReadableGridText(): String {
        val sb = StringBuilder()
        sh.rows.forEachIndexed { r, row ->
            val parts = ArrayList<String>()
            row.forEachIndexed { c, cell -> if (cell.valStr.isNotBlank()) parts.add("Kolom ${CellRange.colName(c)}: ${display(sh, r, c)}") }
            if (parts.isNotEmpty()) sb.append("Baris ").append(r + 1).append(": ").append(parts.joinToString(", ")).append("\n")
        }
        return sb.toString().trim()
    }
}
