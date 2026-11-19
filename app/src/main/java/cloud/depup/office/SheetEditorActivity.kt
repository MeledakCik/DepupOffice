package cloud.depup.office

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class SheetCell(var valStr: String = "", var isBold: Boolean = false, var bgColor: Int = 0)

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
    }

    /** Id draf pemulihan sesi editor ini ([SheetDrafts]). */
    private var draftId = java.util.UUID.randomUUID().toString()
    /**
     * Sidik isi saat terakhir dibuka/diekspor; null = belum pernah bersih (dipulihkan dari draf). Penanda perubahan
     * dihitung dari ISI (semua sheet), jadi tidak ada jenis suntingan yang terlewat.
     */
    private var cleanPrint: Int? = 0
    private var restoredDraft = false

    private fun fingerprint(): Int {
        var h = 17
        for ((n, m) in sheetList) {
            h = h * 31 + n.hashCode()
            for (row in m) {
                h = h * 31 + row.size
                for (c in row) { h = h * 31 + c.valStr.hashCode(); h = h * 31 + (if (c.isBold) 1 else 0); h = h * 31 + c.bgColor }
            }
        }
        return h
    }

    private fun isDirty() = ::gridBox.isInitialized && cleanPrint != fingerprint()
    private fun markClean() { cleanPrint = fingerprint() }

    /** Isi semua sheet → JSON draf (rumus disimpan mentah, bukan hasil). */
    private fun draftJson(): String {
        val sheets = org.json.JSONArray()
        for ((n, m) in sheetList) {
            val rows = org.json.JSONArray()
            for (row in m) {
                val r = org.json.JSONArray()
                for (c in row) r.put(org.json.JSONArray().put(c.valStr).put(if (c.isBold) 1 else 0).put(c.bgColor))
                rows.put(r)
            }
            val cols = if (m === matrix) numCols else (m.maxOfOrNull { it.size } ?: 1)
            sheets.put(org.json.JSONObject().put("name", n).put("cols", cols).put("rows", rows))
        }
        return org.json.JSONObject()
            .put("title", if (::sheetTitleEt.isInitialized) sheetTitleEt.text.toString() else "")
            .put("source", sourceName ?: "")
            .put("cur", currentSheetIdx)
            .put("sheets", sheets).toString()
    }

    /** Pulihkan dari draf; false bila draf rusak (layar dibuka seperti biasa). */
    private fun restoreFrom(d: DraftStore.Draft): Boolean = try {
        val j = org.json.JSONObject(d.file.readText())
        val arr = j.getJSONArray("sheets")
        val list = ArrayList<Pair<String, MutableList<MutableList<SheetCell>>>>()
        val colsOf = ArrayList<Int>()
        for (i in 0 until arr.length()) {
            val sh = arr.getJSONObject(i)
            val rows = sh.getJSONArray("rows")
            val m = mutableListOf<MutableList<SheetCell>>()
            for (r in 0 until rows.length()) {
                val ra = rows.getJSONArray(r)
                val row = mutableListOf<SheetCell>()
                for (c in 0 until ra.length()) {
                    val ca = ra.getJSONArray(c)
                    row.add(SheetCell(ca.optString(0), ca.optInt(1) == 1, ca.optInt(2)))
                }
                m.add(row)
            }
            if (m.isEmpty()) m.add(MutableList(4) { SheetCell("") })
            list.add(sh.optString("name").ifBlank { "Sheet${i + 1}" } to m)
            colsOf.add(sh.optInt("cols", m.maxOf { it.size }).coerceIn(1, 8))
        }
        require(list.isNotEmpty())
        sheetList.clear(); sheetList.addAll(list)
        matrix = list[0].second
        numCols = colsOf[0]
        sourceName = j.optString("source").ifBlank { null }
        restoredDraft = true
        buildUi(j.optString("title").ifBlank { d.name })
        val cur = j.optInt("cur").coerceIn(0, list.size - 1)
        if (cur != 0) {
            currentSheetIdx = cur; matrix = list[cur].second; numCols = colsOf[cur]
            activeRow = 0; activeCol = 0
            syncFormulaBar(); renderGrid(); updateSheetBar()
        }
        toast("Perubahan yang belum diekspor telah dipulihkan.")
        true
    } catch (_: Throwable) {
        sheetList.clear(); matrix = mutableListOf(); restoredDraft = false
        false
    }

    /** Keluar: tanya dulu bila ada perubahan yang belum diekspor (dulu langsung tertutup & semua isian hilang). */
    private fun askClose() {
        if (!isDirty()) { finish(); return }
        AlertDialog.Builder(this).setTitle("Simpan perubahan?")
            .setMessage("Ada perubahan yang belum diekspor pada spreadsheet ini.")
            .setPositiveButton("Ekspor") { _, _ -> saveSpreadsheet() }
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
        if (!isDirty()) return
        val json = try { draftJson() } catch (_: Throwable) { return }
        val title = if (::sheetTitleEt.isInitialized) sheetTitleEt.text.toString().ifBlank { "Spreadsheet" } else "Spreadsheet"
        SheetDrafts.write(this, draftId, title, null) { f ->
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }

    private lateinit var sheetTitleEt: EditText
    private lateinit var formulaEt: EditText
    private lateinit var activeCellPill: android.widget.TextView
    private lateinit var gridBox: LinearLayout
    private lateinit var topBarV: View
    private lateinit var formulaBarV: View
    private lateinit var gridToolbarV: View
    private lateinit var sheetBarContainer: LinearLayout
    private val sheetList = mutableListOf<Pair<String, MutableList<MutableList<SheetCell>>>>()
    private var currentSheetIdx = 0
    /** Keyboard sedang tampil (diperkirakan dari tinggi area tampak jendela). */
    private var imeShown = false

    private var numCols = 4
    private val colNames = listOf("A", "B", "C", "D", "E", "F", "G", "H")
    private var matrix = mutableListOf<MutableList<SheetCell>>()

    private var activeRow = 0
    private var activeCol = 0
    private var updatingFormula = false

    private var currentGridlines = true
    private var currentCurrency = "Rp"
    private var currentSeparator = ","
    private var currentAutoSum = true

    private var sourceName: String? = null
    private val visitingCells = HashSet<Int>()
    /** Cache nilai sel selama satu penyegaran grid — hanya di thread UI, dikosongkan setiap penyegaran. */
    private val memo = HashMap<Int, Double>()
    private val memoNull = HashSet<Int>()
    private var memoOn = false
    private var cycleHits = 0
    private val numberFormat = NumberFormat.getNumberInstance(Locale("id", "ID")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentGridlines = SheetSettings.showGridlines(this)
        currentCurrency = SheetSettings.currencySymbol(this)
        currentSeparator = SheetSettings.csvSeparator(this)
        currentAutoSum = SheetSettings.autoSum(this)

        onBackPressedDispatcher.addCallback(this, PreviewBack(this) { askClose() })
        // Pulihkan: proses dimatikan di latar (draf sesi ini) atau dari layar utama (EXTRA_DRAFT)
        val sid = savedInstanceState?.getString(STATE_DRAFT)
        val want = sid ?: intent.getStringExtra(EXTRA_DRAFT)
        if (sid != null) draftId = sid
        val draft = want?.let { SheetDrafts.get(this, it) }
        if (draft != null) draftId = draft.id
        SheetDrafts.markLive(draftId, true)
        if (draft != null && restoreFrom(draft)) return

        val rawUri = intent.getStringExtra(EXTRA_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (rawUri == null) { buildUi(loadInitialMatrix()); return }
        // Berkas diurai di LATAR: workbook besar dulu diurai di thread utama → layar membeku (bahkan "aplikasi tidak
        // merespons") sebelum editor tampil.
        val loading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(C.SURFACE2) }
        loading.add(android.widget.ProgressBar(this), w = dp(40), h = dp(40))
        loading.add(tv("Membuka spreadsheet…", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 10)
        setContentView(loading)
        Thread(null, {
            val name = try { Files.nameOf(this, rawUri) } catch (_: Throwable) { "Spreadsheet" }
            val sheet = try {
                val low = name.lowercase()
                if (low.endsWith(".csv") || low.endsWith(".txt")) OfficeReader.readCsv(this, rawUri) else OfficeReader.readXlsx(this, rawUri, 220)
            } catch (_: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sourceName = Files.baseName(name)
                // editor memuat paling banyak 200 baris × 8 kolom (sheet pertama). Dulu sisanya dibuang DIAM-DIAM —
                // ekspor lalu menghasilkan berkas yang kehilangan data tanpa pengguna tahu.
                val cut = sheet != null && (sheet.rows.size > 200 || sheet.rows.any { it.size > 8 })
                val title = if (sheet != null && sheet.rows.isNotEmpty()) {
                    fillMatrix(sheet.rows, sheet.boldRows)
                    sheet.name.ifBlank { sourceName ?: "Spreadsheet" }
                } else {
                    toast("Isi spreadsheet tidak terbaca. Mulai dari tabel kosong.")
                    loadTemplateMatrix()
                }
                buildUi(title)
                if (cut) {
                    AlertDialog.Builder(this)
                        .setTitle("Sebagian isi tidak dimuat")
                        .setMessage("Spreadsheet ini lebih besar dari batas editor (200 baris × 8 kolom, sheet pertama). " +
                            "Hanya bagian itu yang bisa diedit, dan berkas hasil ekspor hanya berisi bagian tersebut.\n\n" +
                            "Berkas asli tidak diubah. Untuk melihat seluruh isi, buka berkas ini di pembaca.")
                        .setPositiveButton("Mengerti", null)
                        .show()
                }
            }
        }, "sheet-open", 4L * 1024 * 1024).start()
    }

    private fun buildUi(initialTitle: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.SURFACE2)
        }

        root.add(topBar(initialTitle).also { topBarV = it })
        root.add(formulaBar().also { formulaBarV = it })
        root.add(gridToolbar().also { gridToolbarV = it })

        val gridScroll = GlassScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val pad = vbox(12).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        gridBox = vbox()
        pad.add(gridBox)
        gridScroll.addView(pad)

        root.add(gridScroll, w = MATCH, h = 0, weight = 1f)

        if (sheetList.isEmpty()) {
            sheetList.add(Pair("Sheet1", matrix))
        } else {
            sheetList[0] = Pair(sheetList[0].first, matrix)   // nama sheet pertama dipertahankan (draf yang dipulihkan)
        }
        sheetBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        root.add(sheetBarContainer, w = MATCH, h = WRAP)

        setContentView(Glass.scaffold(root))

        updateSheetBar()
        syncFormulaBar()
        renderGrid()
        applyDensity()
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(imeWatch)
        cleanPrint = if (restoredDraft) null else fingerprint()
    }

    /** Sel aktif memantul kecil (dipilih / diberi tebal / diwarnai). */
    private fun bounceActive(from: Float = 0.9f) {
        cellViews.getOrNull(activeRow)?.getOrNull(activeCol)?.boing(from)
    }

    /** Isi lembar berganti: kartu grid meluncur masuk dari arah lembar yang dituju (pegas lembut). */
    private fun slideGrid(dir: Int) = gridBox.cascadeIn(dyDp = if (dir == 0) 14 else 0, dxDp = 22 * dir)

    private fun updateSheetBar(bounce: Boolean = false) {
        if (!::sheetBarContainer.isInitialized) return
        sheetBarContainer.removeAllViews()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        sheetList.forEachIndexed { idx, (sName, _) ->
            val isSel = idx == currentSheetIdx
            val tabBtn = tv(sName, 12f, if (isSel) C.ON_PRIMARY else C.TEXT, true).apply {
                background = shape(if (isSel) C.TEAL else C.SURFACE, 100, C.LINE, 1)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isClickable = true
                springPress(0.92f)
                if (isSel && bounce) boing(0.85f)
                setOnClickListener {
                    if (currentSheetIdx != idx) {
                        val dir = if (idx > currentSheetIdx) 1 else -1
                        currentSheetIdx = idx
                        val targetM = sheetList[idx].second
                        // PINDAH penunjuk ke list milik sheet itu. Dulu isinya disalin ke `matrix` — padahal `matrix`
                        // adalah list milik Sheet1 sendiri, jadi menambah/berpindah sheet mengosongkan Sheet1.
                        this@SheetEditorActivity.matrix = targetM
                        numCols = targetM.maxOfOrNull { it.size } ?: 4
                        activeRow = 0
                        activeCol = 0
                        syncFormulaBar()
                        renderGrid()
                        slideGrid(dir)
                        updateSheetBar(true)
                    }
                }
                setOnLongClickListener {
                    showRenameSheetDialog(idx)
                    true
                }
            }
            row.add(tabBtn, w = WRAP, side = 3)
        }

        val addBtn = tv("+ Sheet", 12f, C.ACCENT, true).apply {
            background = shape(C.BLUE_BG, 100)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            springPress(0.9f)
            setOnClickListener {
                val newIndex = sheetList.size + 1
                val newMatrix = mutableListOf<MutableList<SheetCell>>()
                newMatrix.add(MutableList(4) { SheetCell("") })
                sheetList.add(Pair("Sheet$newIndex", newMatrix))
                currentSheetIdx = sheetList.size - 1
                this@SheetEditorActivity.matrix = newMatrix
                numCols = 4
                activeRow = 0
                activeCol = 0
                syncFormulaBar()
                renderGrid()
                slideGrid(0)
                updateSheetBar(true)
                toast("Sheet$newIndex ditambahkan")
            }
        }
        row.add(addBtn, w = WRAP, side = 6)

        sheetBarContainer.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun showRenameSheetDialog(sheetIdx: Int) {
        val currentName = sheetList.getOrNull(sheetIdx)?.first ?: "Sheet"
        val et = field("Nama Sheet").apply { setText(currentName) }

        val box = vbox(12).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.add(tv("Kelola Sheet: $currentName", 15f, C.TEXT, true))
        box.add(et, top = 8)

        val builder = AlertDialog.Builder(this)
            .setTitle("Ubah Nama / Hapus Sheet")
            .setView(box)
            .setPositiveButton("Simpan Nama") { _, _ ->
                val newName = et.text.toString().trim().ifBlank { currentName }
                if (sheetIdx in sheetList.indices) {
                    val m = sheetList[sheetIdx].second
                    sheetList[sheetIdx] = Pair(newName, m)
                    updateSheetBar()
                }
            }

        if (sheetList.size > 1) {
            builder.setNeutralButton("Hapus Sheet") { _, _ ->
                sheetList.removeAt(sheetIdx)
                // sheet sebelum sheet aktif dihapus → nomor sheet aktif bergeser satu
                if (sheetIdx < currentSheetIdx) currentSheetIdx--
                currentSheetIdx = currentSheetIdx.coerceIn(0, sheetList.size - 1)
                val targetM = sheetList[currentSheetIdx].second
                this@SheetEditorActivity.matrix = targetM
                numCols = targetM.maxOfOrNull { it.size } ?: 4
                activeRow = 0
                activeCol = 0
                syncFormulaBar()
                renderGrid()
                updateSheetBar()
                toast("Sheet dihapus")
            }
        }

        builder.setNegativeButton("Batal", null).show()
    }

    private val imeWatch = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        val r = android.graphics.Rect()
        window.decorView.getWindowVisibleDisplayFrame(r)
        val h = window.decorView.rootView.height
        val shown = h > 0 && h - r.bottom > h * 0.15f
        if (shown != imeShown) { imeShown = shown; applyDensity() }
    }

    /**
     * HP dimiringkan: tiga bilah (judul, rumus, alat) dulu ±165 dp — dengan keyboard terbuka, tabel nyaris tak terlihat.
     * Lanskap kini lebih rapat, dan baris alat disembunyikan selama keyboard tampil.
     */
    private fun applyDensity() {
        if (!::topBarV.isInitialized) return
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        topBarV.setPadding(dp(12), if (land) dp(3) else dp(6), dp(12), if (land) dp(3) else dp(6))
        formulaBarV.setPadding(dp(12), if (land) dp(2) else dp(6), dp(12), if (land) dp(3) else dp(6))
        formulaEt.minHeight = if (land) dp(40) else dp(48)
        gridToolbarV.setPadding(dp(12), if (land) dp(3) else dp(6), dp(12), if (land) dp(3) else dp(6))
        gridToolbarV.visibility = if (land && imeShown) View.GONE else View.VISIBLE
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

    // ================= MUAT DATA =================

    private fun loadInitialMatrix(): String = loadTemplateMatrix()

    private fun loadTemplateMatrix(): String {
        val template = intent.getStringExtra(EXTRA_TEMPLATE)

        when (template) {
            TEMPLATE_INVOICE -> {
                matrix.clear()
                matrix.add(mutableListOf(
                    SheetCell("No Invoice", true), SheetCell("Klien", true),
                    SheetCell("Jatuh Tempo", true), SheetCell("Total Tagihan", true)
                ))
                matrix.add(mutableListOf(SheetCell("INV-001"), SheetCell("PT Depup Tech"), SheetCell("28 Okt 2024"), SheetCell("15000000")))
                matrix.add(mutableListOf(SheetCell("INV-002"), SheetCell("CV Jaya Abadi"), SheetCell("30 Okt 2024"), SheetCell("8500000")))
                matrix.add(mutableListOf(SheetCell("TOTAL", true), SheetCell(""), SheetCell(""), SheetCell("=SUM(D2:D3)", true)))
                numCols = 4
                return "Invoice / Kuitansi"
            }
            TEMPLATE_PRESENSI -> {
                matrix.clear()
                matrix.add(mutableListOf(
                    SheetCell("Nama Karyawan", true), SheetCell("Hadir", true),
                    SheetCell("Izin", true), SheetCell("Status", true)
                ))
                matrix.add(mutableListOf(SheetCell("Budi Pratama"), SheetCell("22 Hari"), SheetCell("0"), SheetCell("Lengkap")))
                matrix.add(mutableListOf(SheetCell("Siti Aminah"), SheetCell("21 Hari"), SheetCell("1"), SheetCell("Izin Sakit")))
                numCols = 4
                return "Presensi & Daftar Hadir"
            }
            TEMPLATE_BLANK -> {
                matrix.clear()
                matrix.add(mutableListOf(SheetCell(""), SheetCell(""), SheetCell(""), SheetCell("")))
                numCols = 4
                return "Spreadsheet Baru"
            }
        }

        seedDefaultMatrix()
        return "Laporan_Keuangan_Depup"
    }

    private fun fillMatrix(rows: List<List<String>>, boldRows: Set<Int>) {
        matrix.clear()
        val maxCols = rows.maxOfOrNull { it.size } ?: 0
        numCols = maxCols.coerceIn(2, 8)
        rows.take(200).forEachIndexed { r, row ->
            val list = MutableList(numCols) { c ->
                SheetCell(
                    row.getOrNull(c).orEmpty(),
                    isBold = r in boldRows,
                    bgColor = if (r in boldRows) C.LINE else 0
                )
            }
            matrix.add(list)
        }
        if (matrix.isEmpty()) {
            matrix.add(MutableList(numCols) { SheetCell("") })
        }
    }

    private fun seedDefaultMatrix() {
        matrix.clear()
        numCols = 4
        val defaultRows = listOf(
            listOf("Produk/Item", "Qty", "Harga Satuan", "Total (Rp)"),
            listOf("Depup Cloud Pro", "12", "120000", "=B2*C2"),
            listOf("Lisensi PDF OCR", "8", "250000", "=B3*C3"),
            listOf("Add-on e-Meterai", "15", "12500", "=B4*C4"),
            listOf("Server Vault Storage", "4", "450000", "=B5*C5"),
            listOf("TOTAL PENDAPATAN", "39", "-", "=SUM(D2:D5)")
        )

        defaultRows.forEachIndexed { rIdx, row ->
            val rList = mutableListOf<SheetCell>()
            row.forEach { cellVal ->
                rList.add(
                    SheetCell(
                        cellVal,
                        isBold = rIdx == 0 || rIdx == 5,
                        bgColor = when (rIdx) {
                            0 -> C.LINE
                            5 -> C.BLUE_BG
                            else -> 0
                        }
                    )
                )
            }
            matrix.add(rList)
        }
    }

    // ================= UI =================

    /** Bilah atas bergaya kaca yang sama dengan editor Word & pembaca (tombol bulat, kapsul ikon, aksi kapsul tinta). */
    private fun topBar(initialTitle: String): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        bar.add(navCircle(R.drawable.ic_back, "Kembali") { askClose() }, w = dp(40), h = dp(40))

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
            pm.setOnMenuItemClickListener { m ->
                val title = sheetTitleEt.text.toString().ifBlank { "Spreadsheet Excel" }
                when (m.itemId) {
                    1 -> VoiceReaderManager.showVoiceReaderDialog(this@SheetEditorActivity, title, getReadableGridText())
                    2 -> PrintHelper.printSpreadsheet(this@SheetEditorActivity, title, getGridData())
                }
                true
            }
            pm.show()
        }, w = dp(40), h = dp(40))
        bar.addView(caps, LinearLayout.LayoutParams(WRAP, dp(42)))
        bar.addView(navPrimary("Ekspor") { saveSpreadsheet() }, LinearLayout.LayoutParams(WRAP, dp(42)).apply { marginStart = dp(8) })

        return bar
    }

    private fun showFormulaAssistantSheet() {
        val colName = colNames.getOrNull(activeCol) ?: "A"
        val activeCellName = "$colName${activeRow + 1}"
        val smartRange = if (activeRow > 0) "${colName}1:${colName}${activeRow}" else "${colName}1"

        val formulas = listOf(
            Triple("AutoSum (=SUM)", "=SUM($smartRange)", "Jumlahkan seluruh angka di kolom $colName"),
            Triple("Rata-Rata (=AVERAGE)", "=AVERAGE($smartRange)", "Hitung rata-rata nilai di sel $smartRange"),
            Triple("Hitung Data (=COUNT)", "=COUNT($smartRange)", "Hitung jumlah sel ber-angka di $smartRange"),
            Triple("Nilai Maksimum (=MAX)", "=MAX($smartRange)", "Cari nilai tertinggi di $smartRange"),
            Triple("Nilai Minimum (=MIN)", "=MIN($smartRange)", "Cari nilai terendah di $smartRange"),
            Triple("Pengkondisian (=IF)", "=IF(${colName}1>100, \"Lulus\", \"Tidak\")", "Cek kondisi nilai sel"),
            Triple("Pencarian Data (=VLOOKUP)", "=VLOOKUP(${colName}1, A1:B10, 2, FALSE)", "Cari data dalam rentang tabel"),
            Triple("Gabung Teks (=CONCAT)", "=CONCAT(A1, \" \", B1)", "Gabungkan teks dari dua sel")
        )

        sheet { body, dismiss ->
            sheetHeader(body, R.drawable.ic_sheet, C.ACCENT, C.BLUE_BG, "Asisten Rumus Excel", "Sel aktif: $activeCellName • Pilih rumus cepat", dismiss)
            val pad = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }

            val list = vbox(8)
            formulas.forEach { (name, formulaTpl, desc) ->
                val card = vbox(4).apply {
                    background = shape(C.SURFACE, 12, C.LINE, 1)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dismiss()
                        formulaEt.setText(formulaTpl)
                        formulaEt.setSelection(formulaTpl.length)
                        toast("Rumus $name diterapkan pada $activeCellName")
                    }
                }
                card.add(tv(name, 14f, C.ACCENT, true))
                card.add(tv(formulaTpl, 13f, C.TEXT, true))
                card.add(tv(desc, 11f, C.MUTED))
                list.add(card)
            }

            pad.add(ScrollView(this@SheetEditorActivity).apply { addView(list) }, h = dp(320))
            body.add(pad)
        }
    }

    private fun formulaBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        activeCellPill = pill("A1", C.TEAL, C.TEAL_BG, 11f)
        bar.add(activeCellPill, w = WRAP)

        val fxBtn = tv("fx", 14f, C.ACCENT, true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = shape(C.BLUE_BG, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener { showFormulaAssistantSheet() }
        }
        bar.add(fxBtn, w = WRAP, side = 8)

        formulaEt = field("Masukkan teks, angka, atau rumus (=SUM...)", multi = false).apply {
            textSize = 13f
            typeface = Fonts.body(this@SheetEditorActivity)
            setTextColor(C.TEXT)
            background = shape(C.SURFACE2, 8)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (updatingFormula) return
                    val m = this@SheetEditorActivity.matrix
                    val r = activeRow
                    val c = activeCol
                    if (r in m.indices && c in 0 until numCols) {
                        ensureCell(r, c)
                        m[r][c].valStr = s?.toString().orEmpty()
                        refreshCells()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        bar.add(formulaEt, w = 0, weight = 1f, side = 6)

        return bar
    }

    private fun showFindReplaceSheet() {
        val fFind = field("Cari kata / teks…")
        val fReplace = field("Ganti dengan…")

        sheet { body, dismiss ->
            sheetHeader(body, R.drawable.ic_search, C.ACCENT, C.BLUE_BG, "Cari & Ganti Kata Massal", "Cari dan ganti teks pada seluruh sel tabel", dismiss)
            val pad = vbox().apply { setPadding(dp(16), 0, dp(16), dp(20)) }

            val formBox = vbox(10).apply {
                add(tv("Kata yang Dicari", 12f, C.MUTED))
                add(fFind)
                add(tv("Ganti Dengan", 12f, C.MUTED), top = 4)
                add(fReplace)
            }

            pad.add(formBox)

            val actionRow = hbox().apply {
                add(btn("Ganti Semua", true, C.PRIMARY, R.drawable.ic_search) {
                    val q = fFind.text.toString().trim()
                    val rText = fReplace.text.toString()
                    if (q.isEmpty()) {
                        toast("Ketik kata yang ingin dicari")
                        return@btn
                    }

                    var count = 0
                    val m = this@SheetEditorActivity.matrix
                    for (r in 0 until m.size) {
                        for (c in 0 until numCols) {
                            val cell = m.getOrNull(r)?.getOrNull(c) ?: continue
                            if (cell.valStr.contains(q, ignoreCase = true)) {
                                cell.valStr = cell.valStr.replace(q, rText, ignoreCase = true)
                                count++
                            }
                        }
                    }

                    if (count > 0) {
                        refreshCells()
                        syncFormulaBar()
                        toast("Berhasil mengganti $count kata di tabel")
                        dismiss()
                    } else {
                        toast("Teks \"$q\" tidak ditemukan")
                    }
                }, w = 0, weight = 1f)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun gridToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE2)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        fun toolBtn(label: String, onClick: () -> Unit) {
            val tvBtn = tv(label, 13f, C.TEXT, true).apply {
                gravity = Gravity.CENTER
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(C.RIPPLE), shape(C.SURFACE, 100, C.LINE, 1), null)
                minHeight = dp(36)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = true
                setOnClickListener { onClick() }
                springPress(0.93f)
            }
            row.add(tvBtn, w = WRAP, side = 3)
        }

        toolBtn("Σ AutoSum") { showFormulaAssistantSheet() }
        toolBtn("Cari & Ganti") { showFindReplaceSheet() }

        toolBtn("+ Baris") {
            matrix.add(MutableList(numCols) { SheetCell("") })
            activeRow = matrix.size - 1
            activeCol = activeCol.coerceIn(0, numCols - 1)
            syncFormulaBar()
            renderGrid()
            lastRowView()?.pop(0.9f)      // baris baru muncul memantul
        }

        toolBtn("- Baris") {
            if (rowLeaving) return@toolBtn     // baris sebelumnya masih meluncur keluar
            if (matrix.size > 1) {
                val drop = {
                    rowLeaving = false
                    matrix.removeAt(matrix.size - 1)
                    activeRow = activeRow.coerceIn(0, matrix.size - 1)
                    syncFormulaBar()
                    renderGrid()
                }
                // baris terakhir meluncur keluar & celahnya menutup, baru dihapus dari data
                val rv = lastRowView()
                if (rv != null && Motion.on()) { rowLeaving = true; rv.collapseOut { drop() } } else drop()
            } else toast("Minimal 1 baris")
        }

        toolBtn("+ Kolom") {
            if (numCols < colNames.size) {
                numCols++
                matrix.forEachIndexed { r, cells ->
                    while (cells.size < numCols) cells.add(SheetCell("", r == 0))
                }
                renderGrid()
                slideGrid(1)
            } else toast("Maksimal ${colNames.size} kolom")
        }

        toolBtn("- Kolom") {
            if (numCols > 2) {
                numCols--
                matrix.forEach { if (it.size > numCols) it.subList(numCols, it.size).clear() }
                activeCol = activeCol.coerceIn(0, numCols - 1)
                syncFormulaBar()
                renderGrid()
                slideGrid(-1)
            } else toast("Minimal 2 kolom")
        }

        toolBtn("=SUM") {
            val colName = colNames[activeCol]
            val start = (activeRow + 1).coerceAtLeast(1)
            val end = matrix.size
            if (end > start) {
                val formula = "=SUM($colName$start:$colName$end)"
                ensureCell(activeRow, activeCol)
                matrix[activeRow][activeCol].valStr = formula
                syncFormulaBar()
                refreshCells()
                bounceActive(0.85f)
            } else toast("Tambahkan baris angka terlebih dahulu")
        }

        toolBtn("Bold Sel") {
            if (activeRow in matrix.indices) {
                ensureCell(activeRow, activeCol)
                matrix[activeRow][activeCol].isBold = !matrix[activeRow][activeCol].isBold
                refreshCells()
                bounceActive(0.85f)
            }
        }

        toolBtn("Warna Biru") {
            if (activeRow in matrix.indices) {
                ensureCell(activeRow, activeCol)
                matrix[activeRow][activeCol].bgColor = C.BLUE_BG
                refreshCells()
                bounceActive(0.88f)
            }
        }

        toolBtn("Warna Putih") {
            if (activeRow in matrix.indices) {
                ensureCell(activeRow, activeCol)
                matrix[activeRow][activeCol].bgColor = 0
                refreshCells()
                bounceActive(0.88f)
            }
        }

        scroll.addView(row)
        bar.addView(scroll)
        return bar
    }

    private fun ensureCell(r: Int, c: Int) {
        if (r !in matrix.indices) return
        while (matrix[r].size <= c) matrix[r].add(SheetCell(""))
    }

    private fun syncFormulaBar() {
        val raw = matrix.getOrNull(activeRow)?.getOrNull(activeCol)?.valStr.orEmpty()
        updatingFormula = true
        formulaEt.setText(raw)
        formulaEt.setSelection(formulaEt.text.length)
        updatingFormula = false
        activeCellPill.morphText("${colNames.getOrElse(activeCol) { "C${activeCol + 1}" }}${activeRow + 1}")
    }

    // ================= RUMUS =================

    /**
     * Nilai numerik mentah sebuah sel (rumus dievaluasi rekursif, aman dari siklus). Selama satu penyegaran grid
     * ([evaluating]) hasilnya disimpan: rumus yang dirujuk berkali-kali (rentang SUM, total berjalan) dihitung sekali,
     * bukan berulang kali secara berantai.
     */
    private fun cellNumber(r: Int, c: Int): Double? {
        val raw = matrix.getOrNull(r)?.getOrNull(c)?.valStr?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val key = r * 64 + c   // kolom < 64 (maks. 8 kolom berisi)
        if (memoOn) {
            memo[key]?.let { return it }
            if (key in memoNull) return null
        }
        if (!raw.startsWith("=")) {
            val v = parseNumber(raw)
            if (memoOn) { if (v == null) memoNull.add(key) else memo[key] = v }
            return v
        }

        if (!visitingCells.add(key)) { cycleHits++; return 0.0 }
        val hitsBefore = cycleHits
        val result = try {
            val value = FormulaParser(raw.substring(1), r, c).parse()
            if (value.isNaN() || value.isInfinite()) null else value
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null   // rantai rumus sangat panjang: sel ini "#ERROR!", bukan aplikasi tertutup
        } finally {
            visitingCells.remove(key)
        }
        // nilai yang tersentuh rujukan melingkar tidak disimpan (dihitung ulang seperti sebelumnya)
        if (memoOn && cycleHits == hitsBefore) { if (result == null) memoNull.add(key) else memo[key] = result }
        return result
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
        if (s.length > 1 && s.startsWith("0") && !s.startsWith("0.") && !s.startsWith("0,")) return null
        val hasDot = s.contains('.')
        val hasComma = s.contains(',')
        if (hasDot && hasComma) {
            if (s.lastIndexOf(',') > s.lastIndexOf('.')) s = s.replace(".", "").replace(',', '.')
            else s = s.replace(",", "")
        } else if (hasComma) {
            val decimals = s.substringAfterLast(',').length
            s = if (decimals in 1..2 && s.count { it == ',' } == 1) s.replace(',', '.')
            else s.replace(",", "")
        } else if (hasDot) {
            val decimals = s.substringAfterLast('.').length
            val groups = s.count { it == '.' }
            s = if (groups == 1 && decimals != 3) s else s.replace(".", "")
        }
        return s.toDoubleOrNull()
    }

    private fun displayValue(r: Int, c: Int): String {
        val raw = matrix.getOrNull(r)?.getOrNull(c)?.valStr.orEmpty()
        if (!raw.trim().startsWith("=")) return raw
        val value = cellNumber(r, c) ?: return "#ERROR!"
        return numberFormat.format(value)
    }

    private inner class FormulaParser(
        private val src: String,
        private val baseRow: Int,
        private val baseCol: Int
    ) {
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            skipSpace()
            if (pos < src.length) throw IllegalArgumentException("Sisa ekspresi: ${src.substring(pos)}")
            return scalar(v)
        }

        private fun expr(): Any {
            var value = term()
            while (true) {
                skipSpace()
                if (pos >= src.length) break
                when (src[pos]) {
                    '+' -> { pos++; value = scalar(value) + scalar(term()) }
                    '-' -> { pos++; value = scalar(value) - scalar(term()) }
                    else -> return value
                }
            }
            return value
        }

        private fun term(): Any {
            var value = power()
            while (true) {
                skipSpace()
                if (pos >= src.length) break
                when (src[pos]) {
                    '*' -> { pos++; value = scalar(value) * scalar(power()) }
                    '/' -> {
                        pos++
                        val divisor = scalar(power())
                        value = if (divisor == 0.0) Double.NaN else scalar(value) / divisor
                    }
                    else -> return value
                }
            }
            return value
        }

        private fun power(): Any {
            var value = unary()
            skipSpace()
            if (pos < src.length && src[pos] == '^') {
                pos++
                value = Math.pow(scalar(value), scalar(power()))
            }
            return value
        }

        private fun unary(): Any {
            skipSpace()
            if (pos < src.length) {
                when (src[pos]) {
                    '-' -> { pos++; return -scalar(unary()) }
                    '+' -> { pos++; return unary() }
                }
            }
            return primary()
        }

        private fun primary(): Any {
            skipSpace()
            if (pos >= src.length) throw IllegalArgumentException("Ekspresi kosong")
            val ch = src[pos]
            if (ch == '(') {
                pos++
                val v = expr()
                skipSpace()
                if (pos < src.length && src[pos] == ')') pos++
                return v
            }
            if (ch.isDigit() || ch == '.') return readNumber()
            if (ch == '$') { pos++; return primary() }
            if (ch.isLetter()) return readNameOrCell()
            throw IllegalArgumentException("Karakter tak dikenal: $ch")
        }

        private fun readNumber(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            return src.substring(start, pos).toDoubleOrNull()
                ?: throw IllegalArgumentException("Angka tidak valid")
        }

        private fun readNameOrCell(): Any {
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val name = src.substring(start, pos).uppercase()

            // Referensi sel: huruf + angka.
            val digitsStart = pos
            while (pos < src.length && src[pos].isDigit()) pos++
            if (pos > digitsStart) {
                val rowIdx = (src.substring(digitsStart, pos).toIntOrNull() ?: 1) - 1
                val colIdx = columnIndex(name)
                skipSpace()
                if (pos < src.length && src[pos] == ':') {
                    pos++
                    val end = readCellReference() ?: return 0.0
                    return rangeValues(rowIdx, colIdx, end.first, end.second)
                }
                return cellNumber(rowIdx, colIdx) ?: 0.0
            }

            skipSpace()
            if (pos >= src.length || src[pos] != '(') return 0.0
            pos++
            val args = ArrayList<Any>()
            skipSpace()
            if (pos < src.length && src[pos] == ')') {
                pos++
            } else {
                while (true) {
                    args.add(expr())
                    skipSpace()
                    if (pos < src.length && src[pos] == ',') { pos++; continue }
                    if (pos < src.length && src[pos] == ')') { pos++; break }
                    throw IllegalArgumentException("Fungsi $name tidak valid")
                }
            }
            return applyFunction(name, args)
        }

        private fun readCellReference(): Pair<Int, Int>? {
            skipSpace()
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val letters = src.substring(start, pos)
            val digitsStart = pos
            while (pos < src.length && src[pos].isDigit()) pos++
            if (letters.isEmpty() || pos == digitsStart) return null
            val row = (src.substring(digitsStart, pos).toIntOrNull() ?: 1) - 1
            return row to columnIndex(letters)
        }

        private fun columnIndex(letters: String): Int {
            var n = 0
            for (ch in letters) n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
            return (n - 1).coerceAtLeast(0)
        }

        private fun rangeValues(r1: Int, c1: Int, r2: Int, c2: Int): List<Double> {
            val top = minOf(r1, r2)
            val bottom = maxOf(r1, r2)
            val left = minOf(c1, c2)
            val right = maxOf(c1, c2)
            val out = ArrayList<Double>()
            for (r in top..bottom) {
                for (c in left..right) {
                    cellNumber(r, c)?.let { out.add(it) }
                }
            }
            return out
        }

        private fun applyFunction(name: String, args: List<Any>): Double {
            val values = ArrayList<Double>()
            args.forEach { arg ->
                if (arg is List<*>) arg.forEach { item -> if (item is Double) values.add(item) }
                else if (arg is Double) values.add(arg)
            }
            return when (name) {
                "SUM" -> values.sum()
                "AVERAGE", "AVG" -> if (values.isEmpty()) 0.0 else values.sum() / values.size
                "MIN" -> values.minOrNull() ?: 0.0
                "MAX" -> values.maxOrNull() ?: 0.0
                "COUNT" -> values.size.toDouble()
                "ABS" -> kotlin.math.abs(values.firstOrNull() ?: 0.0)
                "ROUND" -> {
                    val v = values.getOrNull(0) ?: 0.0
                    val digits = (values.getOrNull(1) ?: 0.0).toInt()
                    val factor = Math.pow(10.0, digits.toDouble())
                    Math.round(v * factor) / factor
                }
                "INT" -> kotlin.math.floor(values.firstOrNull() ?: 0.0)
                else -> throw IllegalArgumentException("Fungsi $name belum didukung")
            }
        }

        private fun scalar(v: Any): Double = when (v) {
            is Double -> v
            is List<*> -> (v.firstOrNull() as? Double) ?: 0.0
            else -> 0.0
        }

        private fun skipSpace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }

    // ================= TAMPILAN GRID =================
    // Grid dibangun SEKALI per perubahan struktur (baris/kolom/templat). Mengetik, memilih sel, menebalkan, mewarnai,
    // atau mengganti setelan hanya memperbarui sel yang berubah. Dulu SETIAP ketukan huruf membangun ulang seluruh grid
    // (hingga 1.600 sel) dan menghitung ulang semua rumus tanpa cache — mengetik terasa berat di tabel besar.

    private var cellViews: Array<Array<TextView?>> = emptyArray()
    private var rowLeaving = false
    private var builtCols = -1

    /** Jalankan [block] dengan cache nilai sel aktif (thread UI). */
    private inline fun <T> evaluating(block: () -> T): T {
        memo.clear(); memoNull.clear(); memoOn = true
        try { return block() } finally { memoOn = false; memo.clear(); memoNull.clear() }
    }

    private fun renderGrid() {
        gridBox.removeAllViews()
        val cardGrid = card(8, 12).apply {
            background = raised(C.SURFACE, 12, 2f)
        }

        val colHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(C.LINE, 6)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        colHead.add(tv("#", 11f, C.MUTED, true).apply { gravity = Gravity.CENTER }, w = dp(28))
        for (c in 0 until numCols) {
            val cName = colNames.getOrElse(c) { "C${c + 1}" }
            colHead.add(tv(cName, 11f, C.MUTED, true).apply { gravity = Gravity.CENTER }, w = 0, weight = 1f)
        }
        cardGrid.add(colHead)

        val views = Array(matrix.size) { arrayOfNulls<TextView>(numCols) }
        evaluating {
            for (rIdx in matrix.indices) {
                val rowView = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                }
                rowView.add(tv("${rIdx + 1}", 11f, C.MUTED, true).apply { gravity = Gravity.CENTER }, w = dp(28))

                for (cIdx in 0 until numCols) {
                    val cellTv = tv("", 12f, C.TEXT).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        isClickable = true
                        setOnClickListener { selectCell(rIdx, cIdx) }
                    }
                    styleCell(cellTv, rIdx, cIdx)
                    views[rIdx][cIdx] = cellTv
                    rowView.add(cellTv, w = 0, weight = 1f, side = 2)
                }
                cardGrid.add(rowView, top = 2)
            }
        }
        cellViews = views
        builtCols = numCols
        gridBox.add(cardGrid)
    }

    private fun lastRowView(): View? = (gridBox.getChildAt(0) as? android.view.ViewGroup)?.let { g -> g.getChildAt(g.childCount - 1).takeIf { g.childCount > 1 } }

    /** Perbarui isi & gaya semua sel di tempatnya (tanpa membangun ulang tampilan). */
    private fun refreshCells() {
        if (cellViews.size != matrix.size || builtCols != numCols) { renderGrid(); return }
        evaluating {
            for (r in cellViews.indices) {
                val row = cellViews[r]
                for (c in 0 until numCols) row[c]?.let { styleCell(it, r, c) }
            }
        }
    }

    /** Pindah sel aktif: hanya sel lama & sel baru yang digambar ulang. */
    private fun selectCell(r: Int, c: Int) {
        val pr = activeRow; val pc = activeCol
        activeRow = r; activeCol = c
        syncFormulaBar()
        if (cellViews.size != matrix.size || builtCols != numCols) { renderGrid(); return }
        evaluating {
            cellViews.getOrNull(pr)?.getOrNull(pc)?.let { styleCell(it, pr, pc) }
            cellViews.getOrNull(r)?.getOrNull(c)?.let { styleCell(it, r, c) }
        }
        if (pr != r || pc != c) bounceActive(0.92f)   // cincin sel aktif "mendarat" di sel baru
    }

    /**
     * Isi & gaya satu sel. Teks, huruf, dan latar hanya diganti bila memang berubah — tidak memicu tata letak ulang.
     * shape() sengaja tidak menggambar stroke, jadi gridline & cincin sel aktif digambar manual.
     */
    private fun styleCell(v: TextView, r: Int, c: Int) {
        val cell = matrix.getOrNull(r)?.getOrNull(c)
        val isActive = r == activeRow && c == activeCol
        val text = displayValue(r, c).ifBlank { "-" }
        if (!android.text.TextUtils.equals(v.text, text)) v.text = text
        v.setTextColor(if (isActive) C.TEAL else C.TEXT)
        val tf = if (cell?.isBold == true || isActive) Fonts.head(this) else Fonts.body(this)
        if (v.typeface !== tf) v.typeface = tf
        val fill = if (isActive) C.TEAL_SOFT else if (cell == null || cell.bgColor == 0) C.SURFACE else cell.bgColor
        val ring = if (isActive) 2 else if (currentGridlines) 1 else 0
        val key = (fill.toLong() shl 4) or ring.toLong()
        if (v.tag != key) {
            v.tag = key
            v.background = shape(fill, 6).apply {
                if (ring == 2) setStroke(dp(2), C.TEAL) else if (ring == 1) setStroke(dp(1), C.LINE)
            }
        }
    }

    private fun showTemplatePicker() {
        val tmpls = arrayOf("Laporan Keuangan & Sales", "Invoice / Tagihan", "Presensi & Daftar Hadir", "Kosongkan Tabel")
        AlertDialog.Builder(this).setTitle("Pilih Templat Excel").setItems(tmpls) { _, idx ->
            when (idx) {
                0 -> seedDefaultMatrix()
                1 -> {
                    matrix.clear()
                    matrix.add(mutableListOf(SheetCell("No Invoice", true), SheetCell("Klien", true), SheetCell("Jatuh Tempo", true), SheetCell("Total Tagihan", true)))
                    matrix.add(mutableListOf(SheetCell("INV-001"), SheetCell("PT Depup Tech"), SheetCell("28 Okt 2024"), SheetCell("15000000")))
                    matrix.add(mutableListOf(SheetCell("INV-002"), SheetCell("CV Jaya Abadi"), SheetCell("30 Okt 2024"), SheetCell("8500000")))
                    matrix.add(mutableListOf(SheetCell("TOTAL", true), SheetCell(""), SheetCell(""), SheetCell("=SUM(D2:D3)", true)))
                    numCols = 4
                }
                2 -> {
                    matrix.clear()
                    matrix.add(mutableListOf(SheetCell("Nama Karyawan", true), SheetCell("Hadir", true), SheetCell("Izin", true), SheetCell("Status", true)))
                    matrix.add(mutableListOf(SheetCell("Budi Pratama"), SheetCell("22 Hari"), SheetCell("0"), SheetCell("Lengkap")))
                    matrix.add(mutableListOf(SheetCell("Siti Aminah"), SheetCell("21 Hari"), SheetCell("1"), SheetCell("Izin Sakit")))
                    numCols = 4
                }
                else -> {
                    matrix.clear()
                    matrix.add(mutableListOf(SheetCell(""), SheetCell(""), SheetCell(""), SheetCell("")))
                    numCols = 4
                }
            }
            activeRow = 0
            activeCol = 0
            syncFormulaBar()
            renderGrid()
        }.setNegativeButton("Batal", null).show()
    }

    private fun showExcelSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_sheet, C.TEAL, C.TEAL_BG, "Pengaturan Excel & CSV", "Atur garis kisi, mata uang, dan pemisah", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }

        pad.add(tv("Simbol Mata Uang Standard", 14f, C.TEXT, true))
        val currencies = listOf("Rp (Rupiah)", "$ (Dollar)", "€ (Euro)")
        val currVal = listOf("Rp", "$", "€")
        val currRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        currencies.forEachIndexed { idx, cName ->
            val isSel = currVal[idx] == currentCurrency
            val btn = tv(cName, 12f, if (isSel) C.ON_PRIMARY else C.TEXT, true).apply {
                background = shape(if (isSel) C.TEAL else C.SURFACE2, 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentCurrency = currVal[idx]
                    SheetSettings.setCurrencySymbol(this@SheetEditorActivity, currVal[idx])
                    dismiss()
                    refreshCells()
                }
            }
            currRow.add(btn, w = 0, weight = 1f, side = 4)
        }
        pad.add(currRow, top = 8)

        pad.add(tv("Garis Kisi Tabel (Gridlines)", 14f, C.TEXT, true), top = 16)
        val gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Tampilkan Garis", "Sembunyikan Garis").forEachIndexed { idx, gName ->
            val isSel = (idx == 0) == currentGridlines
            val btn = tv(gName, 12f, if (isSel) C.ON_PRIMARY else C.TEXT, true).apply {
                background = shape(if (isSel) C.TEAL else C.SURFACE2, 8)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentGridlines = idx == 0
                    SheetSettings.setShowGridlines(this@SheetEditorActivity, currentGridlines)
                    dismiss()
                    refreshCells()
                }
            }
            gridRow.add(btn, w = 0, weight = 1f, side = 4)
        }
        pad.add(gridRow, top = 8)

        body.add(pad)
    }

    // ================= EKSPOR =================

    private fun saveSpreadsheet() {
        val title = sheetTitleEt.text.toString().trim().ifBlank { "Spreadsheet_Excel" }
        val defaultName = sourceName ?: Files.baseName(title)

        saveSheet(
            "Ekspor Spreadsheet", "File Excel (.xlsx) asli + salinan PDF siap cetak", defaultName, null, 0,
            "Ekspor Sekarang", ext = "xlsx"
        ) { cleanName, _ ->
            val xlsxFile = Files.namedOut(this, cleanName, "xlsx")
            val pdfFile = Files.namedOut(this, cleanName, "pdf")

            // nilai sel dihitung di thread UI (cepat, dengan cache) — evaluasi rumus tidak boleh berjalan bersamaan
            // dengan penyuntingan di thread lain. SEMUA sheet diekspor (dulu hanya sheet yang sedang tampil — sheet
            // lain hilang dari berkas tanpa peringatan).
            val book = ArrayList<XlsxWriter.Sheet>()
            val pdfText = StringBuilder("LAPORAN SPREADSHEET EXCEL\nJudul: $title\n")
            val keepM = matrix
            val keepCols = numCols
            try {
                for ((sheetName, m) in sheetList.toList()) {
                    matrix = m   // rumus dihitung terhadap sheet-nya sendiri
                    numCols = if (m === keepM) keepCols else (m.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
                    val cols = numCols
                    val rows = evaluating { m.indices.map { r -> (0 until cols).map { c -> displayValue(r, c) } } }
                    val boldRows = m.indices.filter { r -> m[r].any { it.isBold } }.toSet()
                    // rumus ikut disimpan sebagai rumus Excel (dulu hanya hasil tampilan berformat & dibulatkan 2 desimal)
                    val formulas = evaluating {
                        val fx = HashMap<Long, Pair<String, Double?>>()
                        m.forEachIndexed { r, row ->
                            row.forEachIndexed { c, cell ->
                                val raw = cell.valStr.trim()
                                if (raw.length > 1 && raw.startsWith("=")) fx[r.toLong() * 100_000 + c] = raw to cellNumber(r, c)
                            }
                        }
                        fx
                    }
                    val name = if (sheetList.size == 1) title else sheetName
                    book.add(XlsxWriter.Sheet(name, rows, boldRows, cols) { r, c -> formulas[r.toLong() * 100_000 + c] })
                    if (sheetList.size > 1) pdfText.append("\n== ").append(sheetName).append(" ==\n") else pdfText.append('\n')
                    pdfText.append(rows.joinToString("\n") { row -> row.joinToString(" | ") }).append('\n')
                }
            } finally {
                matrix = keepM; numCols = keepCols
            }
            work("Mengekspor Spreadsheet Excel…", {
                XlsxWriter.writeBook(xlsxFile, book)
                Pdf.text(pdfText.toString(), pdfFile)
            }) { res ->
                res.onSuccess {
                    markClean(); SheetDrafts.clear(this, draftId)   // diekspor: draf tidak diperlukan lagi
                    showResult("Spreadsheet Berhasil Diekspor", xlsxFile, XLSX, "PDF juga dibuat: ${pdfFile.name}")
                }.onFailure { fail(it) }
            }
        }
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

    private fun getGridData(): List<List<String>> {
        val list = ArrayList<List<String>>()
        matrix.forEach { row ->
            val rList = ArrayList<String>()
            row.forEach { cell -> rList.add(cell.valStr) }
            list.add(rList)
        }
        return list
    }

    private fun getReadableGridText(): String {
        val sb = StringBuilder()
        matrix.forEachIndexed { r, row ->
            val rowParts = ArrayList<String>()
            row.forEachIndexed { c, cell ->
                if (cell.valStr.isNotBlank()) {
                    val colName = colNames.getOrNull(c) ?: "$c"
                    rowParts.add("Kolom $colName: ${cell.valStr}")
                }
            }
            if (rowParts.isNotEmpty()) {
                sb.append("Baris ").append(r + 1).append(": ").append(rowParts.joinToString(", ")).append("\n")
            }
        }
        return sb.toString().trim()
    }
}
