package cloud.depup.office

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.FileOutputStream

data class ScheduleTask(
    val timeOrDay: String,
    val taskName: String,
    val assignee: String,
    val status: String
)

/**
 * Pembuat Jadwal & Perencana Tugas (Schedule Planner to PDF/Excel).
 * Template jadwal kerja/kuliah/kegiatan bulanan dan daftar tugas yang dapat diekspor ke PDF / Excel.
 */
object SchedulePlannerManager {

    fun showDialog(activity: AppCompatActivity) {
        val fTitle = activity.field("Judul Perencana (mis: Jadwal Kerja Maret 2025)").apply { setText("Jadwal Kerja & Agenda") }
        val fOwner = activity.field("Nama / Tim").apply { setText("Tim Operasional") }

        val tasksContainer = activity.vbox(6)
        val taskRows = ArrayList<Triple<EditText, EditText, EditText>>()

        fun addTaskRow(defaultDay: String = "Senin", defaultTask: String = "Rapat Mingguan", defaultAssignee: String = "Tim A") {
            val r = activity.hbox().apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            val fDay = activity.field("Hari / Waktu").apply { setText(defaultDay) }
            val fTask = activity.field("Tugas / Kegiatan").apply { setText(defaultTask) }
            val fAssignee = activity.field("Catatan").apply { setText(defaultAssignee) }

            val delBtn = ImageView(activity).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(C.RED)
                setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
                background = activity.shape(C.SURFACE2, 100)
            }

            r.add(fDay, w = 0, weight = 1f)
            r.add(fTask, w = 0, weight = 2f, side = 4)
            r.add(fAssignee, w = 0, weight = 1.2f)
            r.add(delBtn, w = activity.dp(28), h = activity.dp(28), side = 4)

            val triple = Triple(fDay, fTask, fAssignee)
            taskRows.add(triple)

            delBtn.setOnClickListener {
                tasksContainer.removeView(r)
                taskRows.remove(triple)
            }

            tasksContainer.add(r)
        }

        // Default 3 baris tugas
        addTaskRow("Senin, 08:00", "Rapat Evaluasi", "Dir. Operasional")
        addTaskRow("Selasa, 10:00", "Review Dokumen & Laporan", "Tim Admin")
        addTaskRow("Rabu, 14:00", "Kunjungan Lapangan", "Tim Teknis")

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_note, C.ACCENT, C.BLUE_BG,
                "Pembuat Jadwal & Perencana Tugas", "Template agenda kerja & To-Do List siap cetak", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Judul Perencana", 12f, C.MUTED))
                add(fTitle)
                add(activity.tv("Nama / Tim", 12f, C.MUTED), top = 4)
                add(fOwner)

                add(activity.tv("Daftar Kegiatan & Agenda", 13f, C.TEXT, true), top = 12)
                add(tasksContainer, top = 4)

                val btnAddRow = activity.btn("+ Tambah Agenda", false, C.ACCENT, R.drawable.ic_plus) {
                    addTaskRow("Hari Baru", "Kegiatan Baru", "-")
                }
                add(btnAddRow, top = 6)
            }

            pad.add(ScrollView(activity).apply { addView(formBox) }, h = activity.dp(280))

            val actionRow = activity.hbox().apply {
                add(activity.btn("Buat PDF Jadwal", true, C.PRIMARY, R.drawable.ic_pdf) {
                    val title = fTitle.text.toString().trim().ifBlank { "Jadwal Kerja" }
                    val owner = fOwner.text.toString().trim().ifBlank { "Umum" }

                    val tasks = taskRows.mapNotNull { (fDay, fTask, fAssignee) ->
                        val d = fDay.text.toString().trim()
                        val t = fTask.text.toString().trim()
                        val a = fAssignee.text.toString().trim()
                        if (t.isBlank()) null else ScheduleTask(d.ifBlank { "-" }, t, a.ifBlank { "-" }, "Pending")
                    }

                    if (tasks.isEmpty()) {
                        activity.toast("Tambahkan minimal 1 agenda")
                        return@btn
                    }

                    dismiss()
                    generateSchedulePdf(activity, title, owner, tasks)
                }, w = 0, weight = 1f)

                add(activity.btn("Buka di Excel", false, C.TEAL, R.drawable.ic_sheet) {
                    val title = fTitle.text.toString().trim()
                    val owner = fOwner.text.toString().trim()

                    val grid = ArrayList<List<String>>()
                    grid.add(listOf("PERENCANA JADWAL & AGENDA", "", "", ""))
                    grid.add(listOf("Judul:", title, "Tim / Nama:", owner))
                    grid.add(listOf("", "", "", ""))
                    grid.add(listOf("No", "Hari / Waktu", "Tugas / Kegiatan", "Catatan"))

                    taskRows.forEachIndexed { idx, (fDay, fTask, fAssignee) ->
                        val d = fDay.text.toString().trim()
                        val t = fTask.text.toString().trim()
                        val a = fAssignee.text.toString().trim()
                        if (t.isNotBlank()) grid.add(listOf((idx + 1).toString(), d, t, a))
                    }

                    dismiss()
                    val outXlsx = Files.namedOut(activity, "Jadwal_" + title.replace(" ", "_"), "xlsx")
                    XlsxWriter.write(outXlsx, "Jadwal", grid)
                    val uri = Files.uriFor(activity, outXlsx)
                    Recent.add(activity, uri, outXlsx.name, XLSX)
                    activity.startActivity(Intent(activity, SheetEditorActivity::class.java).putExtra(SheetEditorActivity.EXTRA_URI, uri.toString()))
                }, w = 0, weight = 1f, side = 8)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun generateSchedulePdf(
        activity: AppCompatActivity,
        title: String,
        owner: String,
        tasks: List<ScheduleTask>
    ) {
        val outName = "Jadwal_" + title.replace(" ", "_")
        val out = Files.newOut(activity, outName, "pdf")

        activity.work("Membuat PDF Jadwal…", {
            val doc = PdfDocument()
            val pw = 595; val ph = 842 // A4
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            val c = page.canvas
            c.drawColor(Color.WHITE)

            val pTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 22f; isFakeBoldText = true }
            val pSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF65625D.toInt(); textSize = 12f }
            val pText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); textSize = 12f }
            val pLine = Paint().apply { color = 0xFFE4E2DD.toInt(); strokeWidth = 1.5f }

            var y = 40f
            c.drawText(title, 40f, y, pTitle)
            y += 20f
            c.drawText("Penanggung Jawab: $owner", 40f, y, pSub)
            c.drawLine(40f, y + 12f, pw - 40f, y + 12f, pLine)
            y += 32f

            // Tabel Header
            val bgHeader = Paint().apply { color = 0xFFE8EEFF.toInt() }
            c.drawRect(40f, y, pw - 40f, y + 28f, bgHeader)
            val pTableHead = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 12f; isFakeBoldText = true }

            c.drawText("Hari / Waktu", 50f, y + 18f, pTableHead)
            c.drawText("Tugas / Kegiatan", 200f, y + 18f, pTableHead)
            c.drawText("Catatan", 420f, y + 18f, pTableHead)
            y += 36f

            tasks.forEach { item ->
                c.drawText(item.timeOrDay.take(18), 50f, y, pText)
                c.drawText(item.taskName.take(30), 200f, y, pText)
                c.drawText(item.assignee.take(20), 420f, y, pText)
                y += 22f
                c.drawLine(40f, y - 10f, pw - 40f, y - 10f, pLine)
            }

            doc.finishPage(page)
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
        }) { res ->
            res.onSuccess {
                activity.toast("PDF Jadwal berhasil dibuat!")
                Files.open(activity, Files.uriFor(activity, out), PDF)
            }.onFailure { activity.fail(it) }
        }
    }
}
