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
    }

    private lateinit var sheetTitleEt: EditText
    private lateinit var formulaEt: EditText
    private lateinit var activeCellPill: android.widget.TextView
    private lateinit var gridBox: LinearLayout
    private lateinit var topBarV: View
    private lateinit var formulaBarV: View
    private lateinit var gridToolbarV: View
    /** Keyboard sedang tampil (diperkirakan dari tinggi area tampak jendela). */
    private var imeShown = false

    private var numCols = 4
    private val colNames = listOf("A", "B", "C", "D", "E", "F", "G", "H")
    private val matrix = mutableListOf<MutableList<SheetCell>>()

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
                val title = if (sheet != null && sheet.rows.isNotEmpty()) {
                    fillMatrix(sheet.rows, sheet.boldRows)
                    sheet.name.ifBlank { sourceName ?: "Spreadsheet" }
                } else {
                    toast("Isi spreadsheet tidak terbaca. Mulai dari tabel kosong.")
                    loadTemplateMatrix()
                }
                buildUi(title)
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
        setContentView(Glass.scaffold(root))

        syncFormulaBar()
        renderGrid()
        applyDensity()
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(imeWatch)
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

        bar.add(navCircle(R.drawable.ic_back, "Kembali") { finish() }, w = dp(40), h = dp(40))

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
            pm.menu.add(0, 1, 0, "🔊 Bacakan Isi Tabel (Voice Reader)")
            pm.menu.add(0, 2, 1, "🖨️ Cetak Spreadsheet")
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

    private fun formulaBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        activeCellPill = pill("A1", C.TEAL, C.TEAL_BG, 11f)
        bar.add(activeCellPill, w = WRAP)

        bar.add(tv("fx", 13f, C.MUTED, true), w = WRAP, side = 8)

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
            // kapsul putih bergaris tipis, senada dengan pita editor Word (dulu kotak berbayangan kecil)
            val tvBtn = tv(label, 13f, C.TEXT, true).apply {
                gravity = Gravity.CENTER
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(C.RIPPLE), shape(C.SURFACE, 100, C.LINE, 1), null)
                minHeight = dp(36)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = true
                setOnClickListener { onClick() }
            }
            row.add(tvBtn, w = WRAP, side = 3)
        }

        toolBtn("+ Baris") {
            matrix.add(MutableList(numCols) { SheetCell("") })
            activeRow = matrix.size - 1
            activeCol = activeCol.coerceIn(0, numCols - 1)
            syncFormulaBar()
            renderGrid()
        }

        toolBtn("- Baris") {
            if (matrix.size > 1) {
                matrix.removeAt(matrix.size - 1)
                activeRow = activeRow.coerceIn(0, matrix.size - 1)
                syncFormulaBar()
                renderGrid()
            } else toast("Minimal 1 baris")
        }

        toolBtn("+ Kolom") {
            if (numCols < colNames.size) {
                numCols++
                matrix.forEachIndexed { r, cells ->
                    while (cells.size < numCols) cells.add(SheetCell("", r == 0))
                }
                renderGrid()
            } else toast("Maksimal ${colNames.size} kolom")
        }

        toolBtn("- Kolom") {
            if (numCols > 2) {
                numCols--
                matrix.forEach { if (it.size > numCols) it.subList(numCols, it.size).clear() }
                activeCol = activeCol.coerceIn(0, numCols - 1)
                syncFormulaBar()
                renderGrid()
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
            } else toast("Tambahkan baris angka terlebih dahulu")
        }

        toolBtn("Bold Sel") {
            if (activeRow in matrix.indices) {
                ensureCell(activeRow, activeCol)
                matrix[activeRow][activeCol].isBold = !matrix[activeRow][activeCol].isBold
                refreshCells()
            }
        }

        toolBtn("Warna Biru") {
            if (activeRow in matrix.indices) {
                ensureCell(activeRow, activeCol)
                matrix[activeRow][activeCol].bgColor = C.BLUE_BG
                refreshCells()
            }
        }

        toolBtn("Warna Putih") {
            if (activeRow in matrix.indices) {
                ensureCell(activeRow, activeCol)
                matrix[activeRow][activeCol].bgColor = 0
                refreshCells()
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
        activeCellPill.text = "${colNames.getOrElse(activeCol) { "C${activeCol + 1}" }}${activeRow + 1}"
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
            // dengan penyuntingan di thread lain
            val exportRows = evaluating {
                matrix.indices.map { r -> (0 until numCols).map { c -> displayValue(r, c) } }
            }
            val boldRows = matrix.indices
                .filter { r -> matrix[r].any { it.isBold } }
                .toSet()
            val cols = numCols
            work("Mengekspor Spreadsheet Excel…", {
                XlsxWriter.write(xlsxFile, title, exportRows, boldRows, cols)

                val pdfText = "LAPORAN SPREADSHEET EXCEL\nJudul: $title\n\n" +
                    exportRows.joinToString("\n") { row -> row.joinToString(" | ") }
                Pdf.text(pdfText, pdfFile)
            }) { res ->
                res.onSuccess {
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
                SheetAction(R.drawable.ic_download, "Simpan ke Unduhan") {
                    work("Menyimpan…", { Files.saveToDownloads(this, uri, file.name, mime) }) { r ->
                        r.onSuccess { toast("Tersimpan di Unduhan/Depup Office") }.onFailure { fail(it) }
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
