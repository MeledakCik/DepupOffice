package cloud.depup.office

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class SheetCell(var valStr: String = "", var isBold: Boolean = false, var bgColor: Int = Color.WHITE)

class SheetEditorActivity : AppCompatActivity() {

    companion object {
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
    private val visitingCells = HashSet<String>()
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

        val initialTitle = loadInitialMatrix()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF1F5F9.toInt())
        }

        root.add(topBar(initialTitle))
        root.add(formulaBar())
        root.add(gridToolbar())

        val gridScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val pad = vbox(12).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        gridBox = vbox()
        pad.add(gridBox)
        gridScroll.addView(pad)

        root.add(gridScroll, w = MATCH, h = 0, weight = 1f)
        setContentView(root)

        syncFormulaBar()
        renderGrid()
    }

    // ================= MUAT DATA =================

    private fun loadInitialMatrix(): String {
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val template = intent.getStringExtra(EXTRA_TEMPLATE)

        if (uriStr != null) {
            val uri = runCatching { Uri.parse(uriStr) }.getOrNull()
            if (uri != null) {
                sourceName = Files.baseName(Files.nameOf(this, uri))
                val name = Files.nameOf(this, uri).lowercase()
                val sheet = try {
                    if (name.endsWith(".csv") || name.endsWith(".txt")) OfficeReader.readCsv(this, uri)
                    else OfficeReader.readXlsx(this, uri)
                } catch (_: Exception) {
                    null
                }
                if (sheet != null && sheet.rows.isNotEmpty()) {
                    fillMatrix(sheet.rows, sheet.boldRows)
                    return sheet.name.ifBlank { sourceName ?: "Spreadsheet" }
                }
                toast("Isi spreadsheet tidak terbaca. Mulai dari tabel kosong.")
            }
        }

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
                return "Invoice / Kuintansi"
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
                    bgColor = if (r in boldRows) 0xFFE2E8F0.toInt() else Color.WHITE
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
                            0 -> 0xFFE2E8F0.toInt()
                            5 -> 0xFFE0F2FE.toInt()
                            else -> Color.WHITE
                        }
                    )
                )
            }
            matrix.add(rList)
        }
    }

    // ================= UI =================

    private fun topBar(initialTitle: String): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            elevation = dp(2).toFloat()
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        bar.add(icon(R.drawable.ic_back, C.TEXT, 24).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finish() }
        }, w = dp(40), h = dp(40))

        sheetTitleEt = field("Nama Spreadsheet Excel", multi = false).apply {
            setText(initialTitle)
            textSize = 15f
            typeface = Fonts.head(this@SheetEditorActivity)
            setTextColor(C.TEXT)
            background = null
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        bar.add(sheetTitleEt, w = 0, weight = 1f)

        val tmplBtn = iconPill(R.drawable.ic_sheet, "Templat", C.TEAL, C.TEAL_BG, 11f).apply {
            setOnClickListener { showTemplatePicker() }
        }
        bar.add(tmplBtn, w = WRAP, side = 4)

        bar.add(icon(R.drawable.ic_tune, C.TEAL, 22).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showExcelSettingsSheet() }
        }, w = dp(40), h = dp(40))

        val saveBtn = tv("Ekspor", 13f, Color.WHITE, true).apply {
            background = shape(C.TEAL, 8)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { saveSpreadsheet() }
        }
        bar.add(saveBtn, w = WRAP, side = 4)

        return bar
    }

    private fun formulaBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            elevation = dp(1).toFloat()
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
                        renderGrid()
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
            val tvBtn = tv(label, 11f, C.TEXT, true).apply {
                background = shape(C.SURFACE, 8, C.LINE, 1)
                setPadding(dp(10), dp(6), dp(10), dp(6))
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
                renderGrid()
            } else toast("Tambahkan baris angka terlebih dahulu")
        }

        toolBtn("Bold Sel") {
            if (activeRow in matrix.indices) {
                ensureCell(activeRow, activeCol)
                matrix[activeRow][activeCol].isBold = !matrix[activeRow][activeCol].isBold
                renderGrid()
            }
        }

        toolBtn("Warna Biru") {
            if (activeRow in matrix.indices) {
                ensureCell(activeRow, activeCol)
                matrix[activeRow][activeCol].bgColor = 0xFFE0F2FE.toInt()
                renderGrid()
            }
        }

        toolBtn("Warna Putih") {
            if (activeRow in matrix.indices) {
                ensureCell(activeRow, activeCol)
                matrix[activeRow][activeCol].bgColor = Color.WHITE
                renderGrid()
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

    /** Nilai numerik mentah sebuah sel (rumus dievaluasi rekursif, aman dari siklus). */
    private fun cellNumber(r: Int, c: Int): Double? {
        val raw = matrix.getOrNull(r)?.getOrNull(c)?.valStr?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.startsWith("=")) return parseNumber(raw)

        val key = "$r:$c"
        if (!visitingCells.add(key)) return 0.0
        return try {
            val value = FormulaParser(raw.substring(1), r, c).parse()
            if (value.isNaN() || value.isInfinite()) null else value
        } catch (_: Exception) {
            null
        } finally {
            visitingCells.remove(key)
        }
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
        if (!s.matches(Regex("^[+\\-0-9.,]+$"))) return null
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

    private fun renderGrid() {
        gridBox.removeAllViews()
        val cardGrid = card(8, 12).apply {
            background = shape(C.SURFACE, 12, if (currentGridlines) C.LINE else Color.TRANSPARENT, 1)
        }

        val colHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(0xFFE2E8F0.toInt(), 6)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        colHead.add(tv("#", 11f, C.MUTED, true).apply { gravity = Gravity.CENTER }, w = dp(28))
        for (c in 0 until numCols) {
            val cName = colNames.getOrElse(c) { "C${c + 1}" }
            colHead.add(tv(cName, 11f, C.MUTED, true).apply { gravity = Gravity.CENTER }, w = 0, weight = 1f)
        }
        cardGrid.add(colHead)

        matrix.forEachIndexed { rIdx, row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            rowView.add(tv("${rIdx + 1}", 11f, C.MUTED, true).apply { gravity = Gravity.CENTER }, w = dp(28))

            for (cIdx in 0 until numCols) {
                val cell = row.getOrElse(cIdx) { SheetCell("") }
                val isActive = rIdx == activeRow && cIdx == activeCol
                val evaluated = displayValue(rIdx, cIdx)

                val cellTv = tv(evaluated.ifBlank { "-" }, 12f, if (isActive) C.TEAL else C.TEXT, cell.isBold || isActive).apply {
                    gravity = Gravity.CENTER
                    background = shape(
                        if (isActive) 0xFFCCFBF1.toInt() else cell.bgColor,
                        6,
                        if (isActive) C.TEAL else (if (currentGridlines) C.LINE else Color.TRANSPARENT),
                        1
                    )
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isClickable = true
                    setOnClickListener {
                        activeRow = rIdx
                        activeCol = cIdx
                        syncFormulaBar()
                        renderGrid()
                    }
                }
                rowView.add(cellTv, w = 0, weight = 1f, side = 2)
            }
            cardGrid.add(rowView, top = 2)
        }

        gridBox.add(cardGrid)
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
            val btn = tv(cName, 12f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                background = shape(if (isSel) C.TEAL else C.SURFACE2, 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentCurrency = currVal[idx]
                    SheetSettings.setCurrencySymbol(this@SheetEditorActivity, currVal[idx])
                    dismiss()
                    renderGrid()
                }
            }
            currRow.add(btn, w = 0, weight = 1f, side = 4)
        }
        pad.add(currRow, top = 8)

        pad.add(tv("Garis Kisi Tabel (Gridlines)", 14f, C.TEXT, true), top = 16)
        val gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Tampilkan Garis", "Sembunyikan Garis").forEachIndexed { idx, gName ->
            val isSel = (idx == 0) == currentGridlines
            val btn = tv(gName, 12f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                background = shape(if (isSel) C.TEAL else C.SURFACE2, 8)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentGridlines = idx == 0
                    SheetSettings.setShowGridlines(this@SheetEditorActivity, currentGridlines)
                    dismiss()
                    renderGrid()
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

            work("Mengekspor Spreadsheet Excel…", {
                val exportRows = matrix.mapIndexed { r, _ ->
                    (0 until numCols).map { c -> displayValue(r, c) }
                }
                val boldRows = matrix.indices
                    .filter { r -> matrix[r].any { it.isBold } }
                    .toSet()
                XlsxWriter.write(xlsxFile, title, exportRows, boldRows, numCols)

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
}
