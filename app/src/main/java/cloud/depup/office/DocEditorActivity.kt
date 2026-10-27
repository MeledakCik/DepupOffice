package cloud.depup.office

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class DocSection(
    var text: String = "",
    var isHeading: Boolean = false,
    var isNote: Boolean = false,
    var isBold: Boolean = false,
    var isItalic: Boolean = false,
    var align: Int = Gravity.START
)

class DocEditorActivity : AppCompatActivity() {

    private lateinit var docTitleEt: EditText
    private lateinit var docSubEt: EditText
    private lateinit var sectionsBox: LinearLayout
    private val sections = mutableListOf<DocSection>()

    private var currentFontFamily = 0
    private var currentMarginMode = 0
    private var currentLineSpacing = 1.15f
    private var currentPageSize = "A4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentFontFamily = DocSettings.fontFamily(this)
        currentMarginMode = DocSettings.marginMode(this)
        currentLineSpacing = DocSettings.lineSpacing(this)
        currentPageSize = DocSettings.pageSize(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF1F5F9.toInt())
        }

        root.add(topBar())
        root.add(formattingToolbar())

        val paperScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val paperPad = vbox(16).apply {
            setPadding(marginPx(), marginPx(), marginPx(), marginPx())
        }

        val paperCard = card(16, 16).apply {
            setBackgroundColor(Color.WHITE)
            elevation = dp(3).toFloat()
        }

        docTitleEt = field("Judul Dokumen Word", multi = false).apply {
            textSize = 20f
            typeface = Fonts.head(this@DocEditorActivity)
            setTextColor(C.TEXT)
            background = shape(0xFFF8FAFC.toInt(), 8)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        paperCard.add(docTitleEt)

        docSubEt = field("Sub-judul / Nomor Dokumen (opsional)", multi = false).apply {
            textSize = 13f
            typeface = Fonts.body(this@DocEditorActivity)
            setTextColor(C.MUTED)
            background = shape(0xFFF8FAFC.toInt(), 8)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        paperCard.add(docSubEt, top = 6)

        paperCard.add(View(this).apply { setBackgroundColor(C.LINE) }, top = 12, h = dp(1))

        sectionsBox = vbox()
        paperCard.add(sectionsBox, top = 12)

        // Seed default initial section if empty
        if (sections.isEmpty()) {
            sections.add(DocSection("Pasal 1 — Ketentuan Umum\nPIHAK PERTAMA dan PIHAK KEDUA dengan ini sepakat untuk menjalin kerjasama operasional sesuai dengan syarat dan ketentuan yang tercantum dalam dokumen ini.", isHeading = false))
            sections.add(DocSection("Catatan Tambahan: Mohon periksa kembali tanggal dan nomor dokumen sebelum ditandatangani secara digital.", isNote = true))
        }
        renderSections()

        val addBtn = btn("+ Tambah Paragraf Baru", false, C.ACCENT, R.drawable.ic_plus) {
            sections.add(DocSection("Ketik paragraf baru di sini..."))
            renderSections()
        }
        paperCard.add(addBtn, top = 16)

        paperPad.add(paperCard)
        paperScroll.addView(paperPad)

        root.add(paperScroll, w = MATCH, h = 0, weight = 1f)
        setContentView(root)
    }

    private fun marginPx(): Int = when (currentMarginMode) {
        1 -> dp(10) // Sempit
        2 -> dp(28) // Lebar
        else -> dp(16) // Normal
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

        val col = vbox()
        col.add(tv("Editor Dokumen Word", 16f, C.TEXT, true))
        col.add(tv("Format .docx • Ukuran $currentPageSize", 11f, C.MUTED))
        bar.add(col, w = 0, weight = 1f, side = 8)

        val settingsBtn = icon(R.drawable.ic_tune, C.ACCENT, 22).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showWordSettingsSheet() }
        }
        bar.add(settingsBtn, w = dp(40), h = dp(40))

        val saveBtn = tv("Simpan", 13f, Color.WHITE, true).apply {
            background = shape(C.ACCENT, 8)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { saveWordDocument() }
        }
        bar.add(saveBtn, w = WRAP, side = 6)

        return bar
    }

    private fun formattingToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE2)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        fun toolChip(label: String, iconRes: Int, onClick: () -> Unit) {
            val chip = LinearLayout(this@DocEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = shape(C.SURFACE, 8, C.LINE, 1)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                setOnClickListener { onClick() }
            }
            if (iconRes != 0) chip.add(icon(iconRes, C.ACCENT, 16), w = dp(16), h = dp(16))
            chip.add(tv(label, 12f, C.TEXT, true), w = WRAP, side = if (iconRes != 0) 4 else 0)
            row.add(chip, w = WRAP, side = 4)
        }

        toolChip("Judul Bab", R.drawable.ic_note) {
            sections.add(DocSection("Pasal / Judul Bagian Baru", isHeading = true))
            renderSections()
        }

        toolChip("Catatan / Quote", R.drawable.ic_bulb) {
            sections.add(DocSection("Catatan penting atau klausul pendukung.", isNote = true))
            renderSections()
        }

        toolChip("Atur Margin", R.drawable.ic_crop) {
            showWordSettingsSheet()
        }

        scroll.addView(row)
        bar.addView(scroll)
        return bar
    }

    private fun renderSections() {
        sectionsBox.removeAllViews()
        sections.forEachIndexed { idx, sec ->
            val secCard = vbox(10).apply {
                background = shape(if (sec.isNote) 0xFFFEF3C7.toInt() else (if (sec.isHeading) 0xFFEFF6FF.toInt() else 0xFFF8FAFC.toInt()), 10)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }

            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val tag = if (sec.isHeading) "JUDUL BAB" else (if (sec.isNote) "CATATAN" else "PARAGRAF ${idx + 1}")
            val tagCol = if (sec.isHeading) C.ACCENT else (if (sec.isNote) C.AMBER else C.MUTED)
            topRow.add(pill(tag, tagCol, Color.WHITE, 10f), w = WRAP)

            topRow.add(View(this), w = 0, h = dp(1), weight = 1f)

            val boldBtn = tv("B", 13f, if (sec.isBold) C.ACCENT else C.MUTED, true).apply {
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { sec.isBold = !sec.isBold; renderSections() }
            }
            topRow.add(boldBtn, w = WRAP)

            val delBtn = icon(R.drawable.ic_close, C.RED, 16).apply {
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    if (sections.size > 1) { sections.removeAt(idx); renderSections() }
                    else toast("Minimal harus ada 1 paragraf")
                }
            }
            topRow.add(delBtn, w = dp(24), h = dp(24), side = 4)

            secCard.add(topRow)

            val et = EditText(this).apply {
                setText(sec.text)
                textSize = if (sec.isHeading) 16f else 13f
                setTextColor(if (sec.isNote) 0xFF92400E.toInt() else C.TEXT)
                typeface = when {
                    sec.isHeading || sec.isBold -> Fonts.head(this@DocEditorActivity)
                    else -> Fonts.body(this@DocEditorActivity)
                }
                background = null
                isSingleLine = false
                minLines = if (sec.isHeading) 1 else 2
                setPadding(0, dp(4), 0, dp(4))
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { sec.text = s?.toString().orEmpty() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            secCard.add(et)
            sectionsBox.add(secCard, top = if (idx == 0) 0 else 8)
        }
    }

    private fun showWordSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_doc, C.ACCENT, C.BLUE_BG, "Pengaturan Format Word", "Sesuaikan ukuran, font, margin, dan spasi", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }

        pad.add(tv("Ukuran Kertas", 14f, C.TEXT, true))
        val sizes = listOf("A4", "Letter", "F4")
        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sizes.forEach { sz ->
            val isSel = sz == currentPageSize
            val btn = tv(sz, 12f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                background = shape(if (isSel) C.ACCENT else C.SURFACE2, 8)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentPageSize = sz
                    DocSettings.setPageSize(this@DocEditorActivity, sz)
                    dismiss()
                    recreate()
                }
            }
            sizeRow.add(btn, w = 0, weight = 1f, side = 4)
        }
        pad.add(sizeRow, top = 8)

        pad.add(tv("Batas Margin Halaman", 14f, C.TEXT, true), top = 16)
        val margins = listOf("Normal (2.5 cm)", "Sempit (1.2 cm)", "Lebar (3.8 cm)")
        val marginRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        margins.forEachIndexed { idx, mgName ->
            val isSel = idx == currentMarginMode
            val btn = tv(mgName, 11f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                background = shape(if (isSel) C.ACCENT else C.SURFACE2, 8)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentMarginMode = idx
                    DocSettings.setMarginMode(this@DocEditorActivity, idx)
                    dismiss()
                    recreate()
                }
            }
            marginRow.add(btn, w = 0, weight = 1f, side = 4)
        }
        pad.add(marginRow, top = 8)

        pad.add(tv("Spasi Baris Teks", 14f, C.TEXT, true), top = 16)
        val spacings = listOf("1.0x", "1.15x", "1.5x", "2.0x")
        val spacingVal = listOf(1.0f, 1.15f, 1.5f, 2.0f)
        val spacingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        spacings.forEachIndexed { idx, spName ->
            val isSel = kotlin.math.abs(spacingVal[idx] - currentLineSpacing) < 0.05f
            val btn = tv(spName, 12f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                background = shape(if (isSel) C.TEAL else C.SURFACE2, 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentLineSpacing = spacingVal[idx]
                    DocSettings.setLineSpacing(this@DocEditorActivity, spacingVal[idx])
                    dismiss()
                    recreate()
                }
            }
            spacingRow.add(btn, w = 0, weight = 1f, side = 4)
        }
        pad.add(spacingRow, top = 8)

        body.add(pad)
    }

    private fun saveWordDocument() {
        val title = docTitleEt.text.toString().trim().ifBlank { "Dokumen Word" }
        val sub = docSubEt.text.toString().trim()
        val defaultName = Files.baseName(title)

        saveSheet(
            "Simpan Dokumen Word", "Pilih nama dan format file yang diinginkan", defaultName, null, 0,
            "Simpan Berkas"
        ) { cleanName, _ ->
            val docxFile = Files.namedOut(this, cleanName, "docx")
            work("Membuat Dokumen Word (.docx)…", {
                val writer = DocxWriter(docxFile)
                writer.beginPage()
                writer.paragraph(title.uppercase())
                if (sub.isNotBlank()) writer.note(sub)
                writer.paragraph(" ")
                sections.forEach { sec ->
                    if (sec.isNote) writer.note(sec.text)
                    else writer.paragraph(sec.text)
                }
                writer.finish()
            }) { res ->
                res.onSuccess {
                    val pdfFile = Files.namedOut(this, cleanName, "pdf")
                    val fullText = "$title\n$sub\n\n" + sections.joinToString("\n\n") { it.text }
                    Pdf.text(fullText, pdfFile)
                    showResult("Dokumen Word Berhasil Dibuat", docxFile, DOCX)
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
