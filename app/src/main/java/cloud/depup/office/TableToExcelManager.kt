package cloud.depup.office

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Pindai Tabel Jadi File Excel (Table-to-Excel OCR).
 * Mengenali struktur tabel & teks dari foto, lalu mengonversi menjadi berkas .xlsx yang bisa diedit di Excel.
 */
object TableToExcelManager {

    /**
     * Tampilkan dialog pindai tabel dari foto.
     */
    fun showDialog(activity: AppCompatActivity, onPickImage: (cb: (Uri) -> Unit) -> Unit) {
        onPickImage { uri ->
            processImage(activity, uri)
        }
    }

    private fun processImage(activity: AppCompatActivity, uri: Uri) {
        val name = Files.baseName(Files.nameOf(activity, uri))
        val outXlsx = Files.namedOut(activity, "Tabel_$name", "xlsx")

        activity.work("Membaca struktur tabel & teks (OCR)…", {
            val bmp = Pdf.decode(activity, uri, 1800)
            try {
                val ocrPage = Ocr.run(bmp)
                val gridRows = extractRowsFromOcr(ocrPage)
                if (gridRows.isEmpty()) {
                    throw IllegalStateException("Tabel atau teks tidak terdeteksi pada foto ini")
                }
                XlsxWriter.write(outXlsx, "Hasil Scan", gridRows)
                Pair(outXlsx, gridRows)
            } finally {
                bmp.recycle()
            }
        }) { result ->
            result.onSuccess { (file, rows) ->
                showResultSheet(activity, file, rows)
            }.onFailure { activity.fail(it) }
        }
    }

    /**
     * Ekstrak baris dan kolom dari OcrPage.
     */
    private fun extractRowsFromOcr(ocrPage: OcrPage): List<List<String>> {
        val grid = ArrayList<List<String>>()

        val tableBlock = ocrPage.blocks.filterIsInstance<Table>().firstOrNull()
        if (tableBlock != null && tableBlock.cells.isNotEmpty()) {
            val maxRow = tableBlock.cells.maxOfOrNull { it.r } ?: 0
            val maxCol = tableBlock.cells.maxOfOrNull { it.c } ?: 0
            val matrix = Array(maxRow + 1) { Array(maxCol + 1) { "" } }
            for (cell in tableBlock.cells) {
                val text = cell.paras.flatten().let { OcrLayout.plain(it) }.trim()
                if (cell.r in matrix.indices && cell.c in matrix[cell.r].indices) {
                    matrix[cell.r][cell.c] = text
                }
            }
            for (r in matrix) {
                grid.add(r.toList())
            }
            if (grid.isNotEmpty()) return grid
        }

        // Fallback: baca teks berpisah TAB (\t) dan newline (\n)
        val lines = ocrPage.text.lines()
        for (line in lines) {
            if (line.isBlank()) continue
            val cols = line.split('\t').map { it.trim() }
            if (cols.any { it.isNotEmpty() }) {
                grid.add(cols)
            }
        }

        return grid
    }

    private fun showResultSheet(
        activity: AppCompatActivity,
        file: File,
        rows: List<List<String>>
    ) {
        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG,
                "Tabel Berhasil Dipindai", "${rows.size} baris • ${file.name}", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val previewBox = activity.vbox(10).apply {
                background = activity.shape(C.SURFACE2, 14)
                setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
            }

            val maxShow = rows.take(12)
            maxShow.forEach { row ->
                val lineText = row.joinToString("  |  ")
                previewBox.add(
                    activity.tv(lineText, 12f, C.TEXT).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )
            }
            if (rows.size > 12) {
                previewBox.add(activity.tv("... dan ${rows.size - 12} baris lainnya", 11f, C.MUTED), top = 4)
            }

            pad.add(ScrollView(activity).apply { addView(previewBox) }, h = activity.dp(220))

            val fileUri = Files.uriFor(activity, file)
            Recent.add(activity, fileUri, file.name, XLSX)

            val actionRow = activity.hbox().apply {
                add(activity.btn("Edit di Excel", true, C.TEAL, R.drawable.ic_edit) {
                    dismiss()
                    activity.startActivity(
                        Intent(activity, SheetEditorActivity::class.java)
                            .putExtra(SheetEditorActivity.EXTRA_URI, fileUri.toString())
                    )
                }, w = 0, weight = 1f)

                add(activity.btn("Salin Teks", false, C.ACCENT, R.drawable.ic_share) {
                    val tabText = rows.joinToString("\n") { it.joinToString("\t") }
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Tabel OCR", tabText))
                    activity.toast("Tabel disalin ke papan klip")
                }, w = 0, weight = 1f, side = 8)
            }
            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }
}
