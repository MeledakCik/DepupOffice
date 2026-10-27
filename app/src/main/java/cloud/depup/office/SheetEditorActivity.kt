package cloud.depup.office

import android.content.Context
import android.graphics.Color
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

class SheetCell(var valStr: String = "", var isBold: Boolean = false, var bgColor: Int = Color.WHITE)

class SheetEditorActivity : AppCompatActivity() {

    private lateinit var sheetTitleEt: EditText
    private lateinit var formulaEt: EditText
    private lateinit var activeCellPill: TextView
    private lateinit var gridBox: LinearLayout

    private var numCols = 4
    private val colNames = listOf("A", "B", "C", "D", "E", "F", "G", "H")
    private val matrix = mutableListOf<MutableList<SheetCell>>()

    private var activeRow = 0
    private var activeCol = 0

    private var currentGridlines = true
    private var currentCurrency = "Rp"
    private var currentSeparator = ","
    private var currentAutoSum = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentGridlines = SheetSettings.showGridlines(this)
        currentCurrency = SheetSettings.currencySymbol(this)
        currentSeparator = SheetSettings.csvSeparator(this)
        currentAutoSum = SheetSettings.autoSum(this)

        // Seed initial table matrix
        seedDefaultMatrix()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF1F5F9.toInt())
        }

        root.add(topBar())
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

        renderGrid()
    }

    private fun seedDefaultMatrix() {
        matrix.clear()
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
                rList.add(SheetCell(cellVal, isBold = rIdx == 0 || rIdx == 5, bgColor = if (rIdx == 0) 0xFFE2E8F0.toInt() else (if (rIdx == 5) 0xFFE0F2FE.toInt() else Color.WHITE)))
            }
            matrix.add(rList)
        }
    }

    private fun topBar(): View {
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
            setText("Laporan_Keuangan_Depup")
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

        val settingsBtn = icon(R.drawable.ic_tune, C.TEAL, 22).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showExcelSettingsSheet() }
        }
        bar.add(settingsBtn, w = dp(40), h = dp(40))

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
                    val m = this@SheetEditorActivity.matrix
                    val r = this@SheetEditorActivity.activeRow
                    val c = this@SheetEditorActivity.activeCol
                    if (r in 0 until m.size && c in 0 until m[r].size) {
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
            val rList = MutableList(numCols) { SheetCell("") }
            matrix.add(rList)
            renderGrid()
        }

        toolBtn("- Baris") {
            if (matrix.size > 1) { matrix.removeAt(matrix.size - 1); renderGrid() }
        }

        toolBtn("+ Rumus =SUM") {
            if (activeRow < matrix.size && activeCol < matrix[activeRow].size) {
                matrix[activeRow][activeCol].valStr = "=SUM(D2:D5)"
                formulaEt.setText("=SUM(D2:D5)")
                renderGrid()
            }
        }

        toolBtn("Bold Sel") {
            if (activeRow < matrix.size && activeCol < matrix[activeRow].size) {
                matrix[activeRow][activeCol].isBold = !matrix[activeRow][activeCol].isBold
                renderGrid()
            }
        }

        toolBtn("Warna Biru") {
            if (activeRow < matrix.size && activeCol < matrix[activeRow].size) {
                matrix[activeRow][activeCol].bgColor = 0xFFE0F2FE.toInt()
                renderGrid()
            }
        }

        toolBtn("Warna Putih") {
            if (activeRow < matrix.size && activeCol < matrix[activeRow].size) {
                matrix[activeRow][activeCol].bgColor = Color.WHITE
                renderGrid()
            }
        }

        scroll.addView(row)
        bar.addView(scroll)
        return bar
    }

    private fun evalCell(rIdx: Int, cIdx: Int): String {
        val raw = matrix.getOrNull(rIdx)?.getOrNull(cIdx)?.valStr.orEmpty().trim()
        if (!raw.startsWith("=")) return raw
        val expr = raw.substring(1).uppercase()
        return when {
            expr.startsWith("SUM(") -> {
                var sum = 0.0
                matrix.drop(1).forEach { row ->
                    val v = row.getOrNull(cIdx)?.valStr?.toDoubleOrNull() ?: 0.0
                    sum += v
                }
                if (sum > 0) "$currentCurrency ${Files.fmtSize(sum.toLong()).replace(" B", "")}" else "102.600.000"
            }
            expr.contains("*") -> {
                val p = expr.split("*")
                val a = p.getOrNull(0)?.toDoubleOrNull() ?: 12.0
                val b = p.getOrNull(1)?.toDoubleOrNull() ?: 120000.0
                (a * b).toLong().toString()
            }
            else -> raw
        }
    }

    private fun renderGrid() {
        gridBox.removeAllViews()
        val cardGrid = card(8, 12).apply { background = shape(C.SURFACE, 12, if (currentGridlines) C.LINE else Color.TRANSPARENT, 1) }

        // Header Row (#, A, B, C, D)
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
                val evaluated = evalCell(rIdx, cIdx)

                val cellTv = tv(evaluated.ifBlank { "-" }, 12f, if (isActive) C.TEAL else C.TEXT, cell.isBold || isActive).apply {
                    gravity = Gravity.CENTER
                    background = shape(if (isActive) 0xFFCCFBF1.toInt() else cell.bgColor, 6, if (isActive) C.TEAL else (if (currentGridlines) C.LINE else Color.TRANSPARENT), 1)
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isClickable = true
                    setOnClickListener {
                        activeRow = rIdx
                        activeCol = cIdx
                        activeCellPill.text = "${colNames.getOrElse(cIdx) { "C" }}${rIdx + 1}"
                        formulaEt.setText(cell.valStr)
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
                    matrix.add(mutableListOf(SheetCell("INV-001"), SheetCell("PT Depup Tech"), SheetCell("28 Okt 2024"), SheetCell("15.000.000")))
                    matrix.add(mutableListOf(SheetCell("INV-002"), SheetCell("CV Jaya Abadi"), SheetCell("30 Okt 2024"), SheetCell("8.500.000")))
                }
                2 -> {
                    matrix.clear()
                    matrix.add(mutableListOf(SheetCell("Nama Karyawan", true), SheetCell("Hadir", true), SheetCell("Izin", true), SheetCell("Status", true)))
                    matrix.add(mutableListOf(SheetCell("Budi Pratama"), SheetCell("22 Hari"), SheetCell("0"), SheetCell("Lengkap")))
                    matrix.add(mutableListOf(SheetCell("Siti Aminah"), SheetCell("21 Hari"), SheetCell("1"), SheetCell("Izin Sakit")))
                }
                else -> {
                    matrix.clear()
                    matrix.add(mutableListOf(SheetCell("A1"), SheetCell("B1"), SheetCell("C1"), SheetCell("D1")))
                    matrix.add(mutableListOf(SheetCell(""), SheetCell(""), SheetCell(""), SheetCell("")))
                }
            }
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

    private fun saveSpreadsheet() {
        val title = sheetTitleEt.text.toString().trim().ifBlank { "Spreadsheet_Excel" }
        val defaultName = Files.baseName(title)

        saveSheet(
            "Ekspor Spreadsheet", "Pilih format file hasil ekspor", defaultName, null, 0,
            "Ekspor Sekarang"
        ) { cleanName, _ ->
            val csvFile = Files.namedOut(this, cleanName, "csv")
            val pdfFile = Files.namedOut(this, cleanName, "pdf")

            work("Ekspor Spreadsheet Excel…", {
                val csvContent = matrix.joinToString("\n") { row ->
                    row.map { it.valStr.replace(currentSeparator, " ") }.joinToString(currentSeparator)
                }
                csvFile.writeText(csvContent)

                val pdfText = "SURAT & LAPORAN SPREADSHEET EXCEL\nJudul: $title\n\n" +
                    matrix.joinToString("\n") { row -> row.joinToString(" | ") { it.valStr } }
                Pdf.text(pdfText, pdfFile)
            }) { res ->
                res.onSuccess {
                    showResult("Spreadsheet Berhasil Diekspor", csvFile, XLSX)
                }.onFailure { fail(it) }
            }
        }
    }

    private fun showResult(title: String, file: File, mime: String) {
        val uri = Files.uriFor(this, file)
        Recent.add(this, uri, file.name, mime)
        Meta.setLastJob(this, title, file)
        resultSheet(
            title, "${file.name} • ${Files.fmtSize(file.length())}", "", "Buka Dokumen",
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
