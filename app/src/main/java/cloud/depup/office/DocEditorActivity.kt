package cloud.depup.office

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TEMPLATE = "template"
        const val TEMPLATE_SURAT = "surat"
        const val TEMPLATE_CV = "cv"
        const val TEMPLATE_BLANK = "blank"
    }

    private lateinit var docTitleEt: EditText
    private lateinit var docSubEt: EditText
    private lateinit var sectionsBox: LinearLayout
    private lateinit var paperPad: LinearLayout
    private lateinit var formatInfo: TextView
    private val sections = mutableListOf<DocSection>()
    private val sectionEditors = mutableListOf<EditText>()

    private var currentFontFamily = 0
    private var currentMarginMode = 0
    private var currentLineSpacing = 1.15f
    private var currentPageSize = "A4"
    private var sourceUri: Uri? = null
    private var sourceName: String? = null

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

        paperPad = vbox(16)
        applyPagePadding()

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

        loadInitialContent()

        val addBtn = btn("+ Tambah Paragraf Baru", false, C.ACCENT, R.drawable.ic_plus) {
            sections.add(DocSection("Ketik paragraf baru di sini..."))
            renderSections()
        }
        paperCard.add(addBtn, top = 16)

        paperPad.add(paperCard)
        paperScroll.addView(paperPad)

        root.add(paperScroll, w = MATCH, h = 0, weight = 1f)
        setContentView(root)

        updateFormatInfo()
        renderSections()
    }

    private fun loadInitialContent() {
        val rawUri = intent.getStringExtra(EXTRA_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val uri = rawUri?.let { Files.cacheCopy(this, it) }
        val template = intent.getStringExtra(EXTRA_TEMPLATE)

        if (uri != null) {
            sourceUri = uri
            sourceName = Files.baseName(Files.nameOf(this, uri))
            val paragraphs = try {
                OfficeReader.readDocx(this, uri)
            } catch (_: Exception) {
                emptyList<OfficeReader.Paragraph>()
            }
            if (paragraphs.isEmpty()) {
                toast("Teks dokumen tidak ditemukan. Mulai menulis dari halaman kosong.")
                seedDefault()
                return
            }
            val first = paragraphs.first()
            val useFirstAsTitle = first.text.length <= 100 && paragraphs.size > 1
            docTitleEt.setText(if (useFirstAsTitle) first.text else (sourceName ?: "Dokumen Word"))
            paragraphs.drop(if (useFirstAsTitle) 1 else 0).forEach { p ->
                sections.add(
                    DocSection(
                        p.text,
                        isBold = p.bold,
                        isItalic = p.italic,
                        align = when (p.align) {
                            1 -> Gravity.CENTER
                            2 -> Gravity.END
                            3 -> Gravity.START
                            else -> Gravity.START
                        }
                    )
                )
            }
        } else if (template != null) {
            applyTemplate(template)
        } else {
            seedDefault()
        }
    }

    private fun seedDefault() {
        docTitleEt.setText("")
        sections.add(DocSection("Pasal 1 — Ketentuan Umum\nPIHAK PERTAMA dan PIHAK KEDUA dengan ini sepakat untuk menjalin kerjasama operasional sesuai dengan syarat dan ketentuan yang tercantum dalam dokumen ini."))
        sections.add(DocSection("Catatan Tambahan: Mohon periksa kembali tanggal dan nomor dokumen sebelum ditandatangani secara digital.", isNote = true))
    }

    private fun applyTemplate(key: String) {
        when (key) {
            TEMPLATE_SURAT -> {
                docTitleEt.setText("Surat Lamaran Kerja")
                docSubEt.setText("Nomor: 001/LAMARAN/${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}")
                sections.add(DocSection("Kepada Yth.\nHRD Manager\nPT Nama Perusahaan\nDi Tempat", isHeading = true))
                sections.add(DocSection("Dengan hormat,\n\nBerdasarkan informasi lowongan pekerjaan yang saya peroleh, saya bermaksud mengajukan lamaran kerja untuk posisi yang tersedia di perusahaan Bapak/Ibu."))
                sections.add(DocSection("Data Diri\nNama: (Nama Lengkap)\nTempat/Tanggal Lahir: (Kota, Tanggal)\nPendidikan Terakhir: (Jenjang / Jurusan)\nAlamat: (Alamat Lengkap)\nNo. HP: (Nomor Aktif)\nEmail: (Alamat Email)"))
                sections.add(DocSection("Sebagai bahan pertimbangan, saya lampirkan Curriculum Vitae, fotokopi ijazah, transkrip nilai, dan dokumen pendukung lainnya. Besar harapan saya untuk dapat diberikan kesempatan wawancara."))
                sections.add(DocSection("Demikian surat lamaran ini saya buat dengan sebenar-benarnya. Atas perhatian dan kesempatan yang diberikan, saya ucapkan terima kasih.", isNote = true))
                sections.add(DocSection("Hormat saya,\n\n\n(Nama Lengkap)", align = Gravity.END))
            }
            TEMPLATE_CV -> {
                docTitleEt.setText("Curriculum Vitae")
                docSubEt.setText("Data Pribadi & Riwayat Profesional")
                sections.add(DocSection("DATA PRIBADI", isHeading = true))
                sections.add(DocSection("Nama Lengkap: (Nama)\nTempat/Tanggal Lahir: (Kota, Tanggal)\nJenis Kelamin: (L/P)\nAlamat: (Alamat)\nNo. HP: (Nomor)\nEmail: (Email)"))
                sections.add(DocSection("RINGKASAN PROFIL", isHeading = true))
                sections.add(DocSection("Profesional yang berorientasi pada hasil dengan pengalaman di bidang (bidang). Terbiasa bekerja secara mandiri maupun dalam tim, teliti, dan mampu beradaptasi dengan cepat."))
                sections.add(DocSection("PENGALAMAN KERJA", isHeading = true))
                sections.add(DocSection("(Tahun – Sekarang) — (Nama Perusahaan)\nPosisi: (Jabatan)\nTanggung jawab: (Uraikan pekerjaan dan pencapaian utama)"))
                sections.add(DocSection("PENDIDIKAN", isHeading = true))
                sections.add(DocSection("(Tahun – Tahun) — (Nama Institusi)\nJenjang / Jurusan: (S1 / Teknik Informatika)\nIPK: (Nilai)"))
                sections.add(DocSection("KEAHLIAN", isHeading = true))
                sections.add(DocSection("• (Keahlian 1)\n• (Keahlian 2)\n• (Keahlian 3)"))
            }
            TEMPLATE_BLANK -> {
                docTitleEt.setText("")
                docSubEt.setText("")
                sections.add(DocSection(""))
            }
            else -> seedDefault()
        }
    }

    private fun updateFormatInfo() {
        formatInfo.text = "Format .docx • $currentPageSize • Spasi ${currentLineSpacing}x"
    }

    private fun applyPagePadding() {
        val margin = when (currentMarginMode) {
            1 -> 10
            2 -> 28
            else -> 16
        }
        paperPad.setPadding(dp(margin), dp(margin), dp(margin), dp(margin))
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
        formatInfo = tv("Format .docx • Ukuran $currentPageSize", 11f, C.MUTED)
        col.add(formatInfo)
        bar.add(col, w = 0, weight = 1f, side = 8)

        bar.add(icon(R.drawable.ic_tune, C.ACCENT, 22).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showWordSettingsSheet() }
        }, w = dp(40), h = dp(40))

        bar.add(icon(R.drawable.ic_more, C.TEXT, 22).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { v ->
                val pm = android.widget.PopupMenu(this@DocEditorActivity, v)
                pm.menu.add(0, 1, 0, "🔊 Bacakan Dokumen (Voice Reader)")
                pm.menu.add(0, 2, 1, "🖨️ Cetak Dokumen Word")
                pm.setOnMenuItemClickListener { m ->
                    val title = docTitleEt.text.toString().ifBlank { "Dokumen Word" }
                    val fullText = (listOf(title, docSubEt.text.toString()) + sections.map { it.text }).filter { it.isNotBlank() }.joinToString("\n\n")
                    when (m.itemId) {
                        1 -> VoiceReaderManager.showVoiceReaderDialog(this@DocEditorActivity, title, fullText)
                        2 -> PrintHelper.printText(this@DocEditorActivity, title, fullText)
                    }
                    true
                }
                pm.show()
            }
        }, w = dp(40), h = dp(40))

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

        toolChip("Atur Halaman", R.drawable.ic_crop) {
            showWordSettingsSheet()
        }

        scroll.addView(row)
        bar.addView(scroll)
        return bar
    }

    private fun renderSections() {
        sectionsBox.removeAllViews()
        sectionEditors.clear()
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

            val italicBtn = tv("I", 13f, if (sec.isItalic) C.ACCENT else C.MUTED, true).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { sec.isItalic = !sec.isItalic; renderSections() }
            }
            topRow.add(italicBtn, w = WRAP, side = 2)

            val alignBtn = tv(
                when (sec.align) {
                    Gravity.CENTER -> "≡"
                    Gravity.END -> "⇥"
                    else -> "⇤"
                },
                13f, C.MUTED, true
            ).apply {
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener {
                    sec.align = when (sec.align) {
                        Gravity.START -> Gravity.CENTER
                        Gravity.CENTER -> Gravity.END
                        else -> Gravity.START
                    }
                    renderSections()
                }
            }
            topRow.add(alignBtn, w = WRAP, side = 2)

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
                    sec.isHeading -> Fonts.head(this@DocEditorActivity)
                    sec.isItalic -> Typeface.create(Fonts.body(this@DocEditorActivity), Typeface.ITALIC)
                    sec.isBold -> Fonts.headSemi(this@DocEditorActivity)
                    else -> Fonts.body(this@DocEditorActivity)
                }
                gravity = sec.align or Gravity.TOP
                setLineSpacing(0f, currentLineSpacing)
                background = null
                isSingleLine = false
                minLines = if (sec.isHeading) 1 else 2
                setPadding(0, dp(4), 0, dp(4))
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val newText = s?.toString().orEmpty()
                        if (sec.text != newText) sec.text = newText
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            sectionEditors.add(et)
            secCard.add(et)
            sectionsBox.add(secCard, top = if (idx == 0) 0 else 8)
        }
    }

    private fun showWordSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_doc, C.ACCENT, C.BLUE_BG, "Pengaturan Format Word", "Sesuaikan ukuran, margin, dan spasi tanpa kehilangan isi", dismiss)
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
                    updateFormatInfo()
                    renderSections()
                    dismiss()
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
                    applyPagePadding()
                    dismiss()
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
                    sectionEditors.forEach { it.setLineSpacing(0f, currentLineSpacing) }
                    updateFormatInfo()
                    dismiss()
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
        val defaultName = sourceName ?: Files.baseName(title)

        saveSheet(
            "Simpan Dokumen Word", "Pilih nama file .docx yang diinginkan", defaultName, null, 0,
            "Simpan Berkas", ext = "docx"
        ) { cleanName, _ ->
            val docxFile = Files.namedOut(this, cleanName, "docx")
            val pdfFile = Files.namedOut(this, cleanName, "pdf")
            val snapshot = sections.map {
                DocSection(it.text, it.isHeading, it.isNote, it.isBold, it.isItalic, it.align)
            }
            work("Membuat Dokumen Word (.docx)…", {
                val writer = DocxWriter(
                    docxFile,
                    pageSize = currentPageSize,
                    marginMode = currentMarginMode,
                    lineSpacing = currentLineSpacing,
                    fontFamily = currentFontFamily
                )
                writer.beginPage()
                writer.paragraph(title.uppercase(), bold = true, align = 1)
                if (sub.isNotBlank()) writer.note(sub)
                writer.paragraph(" ")
                snapshot.forEach { sec ->
                    if (sec.isNote) writer.note(sec.text)
                    else writer.paragraph(
                        sec.text,
                        bold = sec.isBold,
                        italic = sec.isItalic,
                        align = when (sec.align) {
                            Gravity.CENTER -> 1
                            Gravity.END -> 2
                            else -> 0
                        }
                    )
                }
                writer.finish()

                val fullText = "$title\n$sub\n\n" + snapshot.joinToString("\n\n") { it.text }
                Pdf.text(fullText, pdfFile)
            }) { res ->
                res.onSuccess {
                    showResult("Dokumen Word Berhasil Dibuat", docxFile, DOCX, "PDF juga dibuat: ${pdfFile.name}")
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
