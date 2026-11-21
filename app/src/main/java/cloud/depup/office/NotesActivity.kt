package cloud.depup.office

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Semua catatan: kisi dua kolom seperti kertas tempel (disematkan di atas), cari, buat catatan / daftar centang baru,
 * dan pasang widget di layar utama. Tekan lama kartu = opsi (warna, sematkan, widget, bagikan, hapus).
 */
class NotesActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var sub: TextView
    private lateinit var search: EditText
    private lateinit var promo: View
    private var query = ""
    private val refill = Runnable { fill() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = savedInstanceState?.getString(STATE_QUERY) ?: ""
        onBackPressedDispatcher.addCallback(this, PreviewBack(this) { finish() })
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_QUERY, query)
    }

    // Isi dibangun ulang tiap kembali ke layar ini: catatan bisa berubah di editor atau dari widget.
    override fun onResume() {
        super.onResume()
        fill()
    }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        // bilah atas: kapsul kaca melayang (Glass.scaffold floatTop)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        bar.add(icon(R.drawable.ic_back, C.TEXT, 24).asButton("Kembali").apply { setOnClickListener { finish() } }, w = dp(48), h = dp(48))
        val tc = vbox()
        tc.add(tv("Catatan", 18f, C.TEXT, true).apply { markHeading() })
        sub = tv("", 12f, C.MUTED).apply { maxLines = 1 }
        tc.add(sub)
        bar.add(tc, w = 0, weight = 1f, side = 8)
        bar.add(icon(R.drawable.ic_checklist, C.TEXT, 24).asButton("Daftar centang baru").apply {
            setOnClickListener { openNote(-1L, checklist = true) }
        }, w = dp(48), h = dp(48))
        bar.addView(navPrimary("Baru") { openNote(-1L) }.apply {
            leadingIcon(R.drawable.ic_plus, C.ON_PRIMARY, 16)
            contentDescription = "Catatan baru"
        }, LinearLayout.LayoutParams(WRAP, dp(48)).apply { marginStart = dp(4) })
        root.add(bar)

        val page = vbox().apply { setPadding(dp(16), dp(6), dp(16), dp(28)) }
        search = EditText(this).apply {
            hint = "Cari catatan"
            setHintTextColor(C.MUTED)
            setTextColor(C.TEXT)
            textSize = 15f
            typeface = Fonts.body(this@NotesActivity)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = shape(C.SURFACE, 100, C.LINE, 1)
            setPadding(dp(16), 0, dp(16), 0)
            minHeight = dp(48)
            leadingIcon(R.drawable.ic_search, C.MUTED, 18)
            compoundDrawablePadding = dp(10)
            contentDescription = "Cari catatan"
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    list.removeCallbacks(refill)
                    list.postDelayed(refill, 160L)
                }
            })
        }
        page.add(search, top = 4)
        promo = actionRow(R.drawable.ic_widget, "Pasang di layar utama", "Catatan atau daftar centang langsung di layar utama HP",
            C.AMBER, C.AMBER_BG, shadow = false) { noteWidgetSheet() }
        page.add(promo, top = 12)
        list = vbox()
        page.add(list)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        root.add(scroll, h = 0, weight = 1f)
        setContentView(Glass.scaffold(root, floatTop = true))
    }

    private fun fill() {
        list.removeCallbacks(refill)
        val all = Notes.all(this)
        sub.text = if (all.isEmpty()) "Tersimpan di HP ini" else "${all.size} catatan"
        search.visibility = if (all.isEmpty() && query.isEmpty()) View.GONE else View.VISIBLE
        promo.visibility = if (NoteWidgets.placed(this)) View.GONE else View.VISIBLE
        list.removeAllViews()
        if (all.isEmpty()) {
            list.add(emptyState(R.drawable.ic_note, "Belum ada catatan",
                "Tulis ide, daftar belanja, atau tugas. Semua tersimpan offline di HP ini dan bisa dipasang di layar utama.",
                "Tulis catatan") { openNote(-1L) }, top = 16)
            return
        }
        val shown = all.filter { NoteViews.matches(it, query) }
        if (shown.isEmpty()) {
            list.add(tv("Tidak ada catatan yang cocok dengan “${query.trim()}”.", 14f, C.MUTED).apply {
                gravity = Gravity.CENTER
            }, top = 28)
            return
        }
        val pinned = shown.filter { it.pinned }
        val others = shown.filter { !it.pinned }
        if (pinned.isNotEmpty()) {
            list.add(sectionTitle("Disematkan"), top = 14)
            list.add(grid(pinned), top = 4)
        }
        if (others.isNotEmpty()) {
            if (pinned.isNotEmpty()) list.add(sectionTitle("Lainnya"), top = 18)
            list.add(grid(others), top = if (pinned.isEmpty()) 16 else 4)
        }
    }

    /** Kisi dua kolom bergaya "masonry": tiap kartu masuk ke kolom yang (perkiraannya) lebih pendek. */
    private fun grid(ns: List<Note>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val cols = arrayOf(vbox(), vbox())
        row.add(cols[0], w = 0, weight = 1f)
        row.add(cols[1], w = 0, weight = 1f)
        (cols[1].layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        val h = intArrayOf(0, 0)
        ns.forEach { n ->
            val c = if (h[0] <= h[1]) 0 else 1
            val card = noteCard(n, onOpen = { openNote(n.id) }, onLong = { noteActions(n.id, { fill() }, { fill() }) })
            cols[c].add(card, top = if (cols[c].childCount == 0) 0 else 10)
            h[c] += estimate(n)
        }
        return row
    }

    private fun estimate(n: Note): Int {
        val title = if (n.title.isBlank()) 0 else 2
        val content = if (n.checklist) minOf(n.items.count { it.text.isNotBlank() }, 5) * 2
        else minOf(n.body.length / 22 + n.body.count { it == '\n' } + 1, 10)
        return 3 + title + content
    }

    companion object {
        private const val STATE_QUERY = "depup_notes_query"
    }
}
