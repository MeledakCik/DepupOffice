package cloud.depup.office

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

/**
 * Pilih catatan untuk widget satu catatan. Dibuka peluncur (pasang / atur ulang widget) atau dengan mengetuk widget
 * yang belum memilih catatan. Hanya menerima id widget milik [NoteWidget].
 */
class NoteWidgetConfigActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    /** Baru saja terikat ke catatan (dipasang dari aplikasi): tidak perlu memilih lagi. */
    private fun justBound() = System.currentTimeMillis() - NoteWidgets.boundAt(this, widgetId) < 10_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // dibatalkan = widget baru tidak jadi dipasang (atur ulang: widget tetap seperti semula)
        setResult(RESULT_CANCELED, result())
        val info = try { AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId) } catch (_: Throwable) { null }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider != ComponentName(this, NoteWidget::class.java)) {
            finish(); return
        }
        if (justBound()) { done(); return }
        onBackPressedDispatcher.addCallback(this, PreviewBack(this) { finish() })
        build()
        // pemasangan dari aplikasi: id widget bisa tiba sesaat SESUDAH layar ini dibuka peluncur
        window.decorView.postDelayed({ if (!isFinishing && !isDestroyed && justBound()) done() }, 900L)
    }

    private fun done() {
        setResult(RESULT_OK, result())
        finish()
    }

    private fun bindTo(noteId: Long) {
        NoteWidgets.bind(this, widgetId, noteId)
        done()
    }

    private fun createNew(checklist: Boolean) {
        val n = Note(Notes.newId(this), checklist = checklist)
        if (checklist) n.items.add(NoteItem(""))
        Notes.save(this, n)
        NoteWidgets.bind(this, widgetId, n.id)
        setResult(RESULT_OK, result())
        // editor di tugas Depup sendiri (layar ini bisa berjalan di tugas peluncur)
        startActivity(Intent(this, NoteEditActivity::class.java).putExtra(NoteEditActivity.EXTRA_ID, n.id)
            .putExtra(NoteEditActivity.EXTRA_FOCUS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        bar.add(icon(R.drawable.ic_close, C.TEXT, 24).asButton("Batal").apply { setOnClickListener { finish() } }, w = dp(48), h = dp(48))
        val tc = vbox()
        tc.add(tv("Pilih catatan", 18f, C.TEXT, true).apply { markHeading() })
        tc.add(tv("Untuk widget di layar utama", 12f, C.MUTED))
        bar.add(tc, w = 0, weight = 1f, side = 8)
        root.add(bar)

        val col = vbox().apply { setPadding(dp(20), dp(8), dp(20), dp(28)) }
        col.add(twoCols(
            btn("Catatan baru", true, C.PRIMARY, R.drawable.ic_plus) { createNew(false) },
            btn("Daftar centang", false, C.PRIMARY, R.drawable.ic_checklist) { createNew(true) }
        ), top = 4)
        val all = Notes.all(this)
        if (all.isEmpty()) {
            col.add(tv("Belum ada catatan. Buat catatan atau daftar centang baru — langsung tampil di widget.", 14f, C.MUTED), top = 18)
        } else {
            col.add(sectionTitle("Catatan Anda"), top = 18)
            val current = NoteWidgets.noteFor(this, widgetId)
            all.forEachIndexed { i, n ->
                val row = noteRow(n) { bindTo(n.id) }
                if (n.id == current) {
                    row.contentDescription = "${row.contentDescription}. Sedang tampil di widget ini"
                    (row as? LinearLayout)?.add(icon(R.drawable.ic_check, C.PRIMARY, 20), w = dp(20), h = dp(20))
                }
                col.add(row, top = if (i == 0) 4 else 8)
            }
        }
        val scroll = ScrollView(this).apply { addView(col) }
        root.add(scroll, h = 0, weight = 1f)
        setContentView(Glass.scaffold(root, floatTop = true))
    }
}
