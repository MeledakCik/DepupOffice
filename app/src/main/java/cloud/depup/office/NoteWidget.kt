package cloud.depup.office

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * Warna widget. Android 12+: pasangan terang/gelap diserahkan ke peluncur ([RemoteViews.setColorInt]) sehingga widget
 * ikut berganti saat tema HP berganti, tanpa menunggu aplikasi. Android 10–11: dipilih dari tema HP saat dibangun.
 */
internal object WidgetPaint {
    const val SURFACE_L = 0xFFFFFFFF.toInt()
    const val SURFACE_D = 0xFF1B1B1A.toInt()
    const val ROW_L = 0xFFF1F0EC.toInt()
    const val ROW_D = 0xFF272725.toInt()

    private fun systemDark() =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun set(v: RemoteViews, id: Int, method: String, light: Int, dark: Int) {
        if (Build.VERSION.SDK_INT >= 31) v.setColorInt(id, method, light, dark)
        else v.setInt(id, method, if (systemDark()) dark else light)
    }

    fun text(v: RemoteViews, id: Int, muted: Boolean = false) =
        if (muted) set(v, id, "setTextColor", NoteColors.MUTED_L, NoteColors.MUTED_D)
        else set(v, id, "setTextColor", NoteColors.TEXT_L, NoteColors.TEXT_D)

    fun tint(v: RemoteViews, id: Int, light: Int, dark: Int) = set(v, id, "setColorFilter", light, dark)

    fun tone(v: RemoteViews, id: Int, t: NoteColors.Tone) = tint(v, id, t.light, t.dark)

    /** Isi daftar widget. Android 12+: baris dikirim langsung; Android 10–11: lewat [NoteWidgetService]. */
    @Suppress("DEPRECATION")
    fun list(ctx: Context, v: RemoteViews, widgetId: Int, kind: Int, rows: () -> List<RemoteViews>) {
        if (Build.VERSION.SDK_INT >= 31) {
            val b = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(2).setHasStableIds(false)
            rows().forEachIndexed { i, r -> b.addItem(i.toLong(), r) }
            v.setRemoteAdapter(R.id.list, b.build())
        } else {
            val i = Intent(ctx, NoteWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra(NoteWidgetService.KIND, kind)
                .setData(Uri.parse("depup://widget/isi/$kind/$widgetId"))
            v.setRemoteAdapter(R.id.list, i)
        }
    }

    /** Buka layar aplikasi dari widget. [key] membedakan PendingIntent (ekstra tidak ikut dibandingkan sistem). */
    fun activity(ctx: Context, i: Intent, key: String, mutable: Boolean = false): PendingIntent {
        i.setData(Uri.parse("depup://widget/buka/$key")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val f = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getActivity(ctx, 0, i, f)
    }
}

/**
 * Widget SATU catatan (seperti kertas tempel): judul + isi yang bisa digulir. Daftar centang bisa dicentang langsung
 * dari widget; ketuk judul/isi teks membuka catatan. Widget yang belum memilih catatan (atau catatannya dihapus)
 * meminta pengguna memilih lewat [NoteWidgetConfigActivity].
 */
class NoteWidget : AppWidgetProvider() {
    companion object {
        const val ACTION_TOGGLE = "cloud.depup.office.action.NOTE_TOGGLE"
        const val ACTION_PINNED = "cloud.depup.office.action.NOTE_PINNED"
        const val EXTRA_NOTE = "depup_note"
        const val EXTRA_INDEX = "depup_index"
        const val EXTRA_TEXT = "depup_text"
        const val EXTRA_TOK = "depup_tok"

        fun updateAll(ctx: Context) {
            val am = AppWidgetManager.getInstance(ctx) ?: return
            val ids = am.getAppWidgetIds(ComponentName(ctx, NoteWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { update(ctx, am, it) }
            if (Build.VERSION.SDK_INT < 31) am.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        fun update(ctx: Context, am: AppWidgetManager, id: Int) {
            try { am.updateAppWidget(id, build(ctx, id)) } catch (_: Throwable) {}
        }

        private fun noteOf(ctx: Context, widgetId: Int): Note? =
            NoteWidgets.noteFor(ctx, widgetId).let { if (it >= 0) Notes.get(ctx, it) else null }

        private fun build(ctx: Context, id: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_note)
            val nid = NoteWidgets.noteFor(ctx, id)
            val n = if (nid >= 0) Notes.get(ctx, nid) else null
            WidgetPaint.tone(v, R.id.bg, NoteColors.of(n?.color ?: 0))
            WidgetPaint.text(v, R.id.title)
            WidgetPaint.text(v, R.id.count, muted = true)
            WidgetPaint.text(v, R.id.msg, muted = true)
            if (n == null) {
                v.setViewVisibility(R.id.head, View.VISIBLE)
                v.setTextViewText(R.id.title, if (nid >= 0) "Catatan dihapus" else "Pilih catatan")
                v.setViewVisibility(R.id.count, View.GONE)
                v.setTextViewText(R.id.msg,
                    if (nid >= 0) "Ketuk untuk memilih catatan lain." else "Ketuk untuk memilih catatan Depup yang tampil di sini.")
                v.setViewVisibility(R.id.msg, View.VISIBLE)
                v.setViewVisibility(R.id.list, View.GONE)
                v.setOnClickPendingIntent(R.id.frame, configure(ctx, id))
                return v
            }
            val head = if (n.checklist) n.title.ifBlank { "Daftar centang" } else n.title
            v.setViewVisibility(R.id.head, if (head.isBlank()) View.GONE else View.VISIBLE)
            v.setTextViewText(R.id.title, head)
            val (done, total) = n.progress()
            v.setTextViewText(R.id.count, "$done/$total")
            v.setViewVisibility(R.id.count, if (n.checklist && total > 0) View.VISIBLE else View.GONE)
            val rs = rows(ctx, n)
            if (rs.isEmpty()) {
                v.setTextViewText(R.id.msg, if (n.checklist) "Belum ada item. Ketuk untuk menambah." else "Catatan kosong. Ketuk untuk menulis.")
                v.setViewVisibility(R.id.msg, View.VISIBLE)
                v.setViewVisibility(R.id.list, View.GONE)
            } else {
                v.setViewVisibility(R.id.msg, View.GONE)
                v.setViewVisibility(R.id.list, View.VISIBLE)
                WidgetPaint.list(ctx, v, id, NoteWidgetService.KIND_NOTE) { rs }
                // daftar centang: ketuk baris = centang; teks: ketuk paragraf = buka catatan
                v.setPendingIntentTemplate(R.id.list, if (n.checklist) toggleTemplate(ctx, id) else open(ctx, n.id, id, mutable = true))
            }
            val o = open(ctx, n.id, id)
            v.setOnClickPendingIntent(R.id.frame, o)
            v.setOnClickPendingIntent(R.id.head, o)
            return v
        }

        /** Baris isi widget [widgetId] (dipakai juga [NoteWidgetService] di Android 10–11). */
        fun rowsFor(ctx: Context, widgetId: Int): List<RemoteViews> = noteOf(ctx, widgetId)?.let { rows(ctx, it) } ?: emptyList()

        // Batas isi widget: paket tampilan dikirim ke peluncur lewat Binder (±1 MB untuk semua) — catatan sangat panjang
        // dipotong di widget (lengkap di aplikasi).
        private const val MAX_ROWS = 200
        private const val MAX_CHARS = 6000

        private fun rows(ctx: Context, n: Note): List<RemoteViews> {
            val out = ArrayList<RemoteViews>()
            var chars = 0
            if (!n.checklist) {
                for (line in n.body.trimEnd().lines()) {
                    if (out.size >= MAX_ROWS || chars >= MAX_CHARS) break
                    val t = line.take(MAX_CHARS - chars)
                    chars += t.length + 1
                    val r = RemoteViews(ctx.packageName, R.layout.widget_note_text)
                    r.setTextViewText(R.id.text, t)
                    WidgetPaint.text(r, R.id.text)
                    r.setOnClickFillInIntent(R.id.text, Intent())
                    out.add(r)
                }
                return out
            }
            for ((i, it) in n.items.withIndex()) {
                if (out.size >= MAX_ROWS || chars >= MAX_CHARS) break
                if (it.text.isBlank()) continue
                val t = it.text.trim().take(600)
                chars += t.length
                val r = RemoteViews(ctx.packageName, R.layout.widget_note_item)
                r.setImageViewResource(R.id.box, if (it.done) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox)
                if (it.done) {
                    WidgetPaint.tint(r, R.id.box, NoteColors.MUTED_L, NoteColors.MUTED_D)
                    r.setTextViewText(R.id.text, SpannableString(t).apply {
                        setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    })
                    WidgetPaint.text(r, R.id.text, muted = true)
                } else {
                    WidgetPaint.tint(r, R.id.box, NoteColors.TEXT_L, NoteColors.TEXT_D)
                    r.setTextViewText(R.id.text, t)
                    WidgetPaint.text(r, R.id.text)
                }
                r.setContentDescription(R.id.row, t + if (it.done) ", sudah dicentang. Ketuk untuk melepas centang" else ", belum dicentang. Ketuk untuk mencentang")
                r.setOnClickFillInIntent(R.id.row, Intent().putExtra(EXTRA_NOTE, n.id).putExtra(EXTRA_INDEX, i).putExtra(EXTRA_TEXT, it.text))
                out.add(r)
            }
            return out
        }

        private fun open(ctx: Context, noteId: Long, widgetId: Int, mutable: Boolean = false): PendingIntent =
            WidgetPaint.activity(ctx, Intent(ctx, NoteEditActivity::class.java).putExtra(NoteEditActivity.EXTRA_ID, noteId),
                "catatan/$noteId/$widgetId/${if (mutable) "isi" else "bingkai"}", mutable)

        private fun configure(ctx: Context, widgetId: Int): PendingIntent =
            WidgetPaint.activity(ctx, Intent(ctx, NoteWidgetConfigActivity::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId), "pilih/$widgetId")

        private fun toggleTemplate(ctx: Context, widgetId: Int): PendingIntent {
            val i = Intent(ctx, NoteWidget::class.java).setAction(ACTION_TOGGLE)
                .setData(Uri.parse("depup://widget/centang/$widgetId")).putExtra(EXTRA_TOK, NoteWidgets.token(ctx))
            return PendingIntent.getBroadcast(ctx, widgetId, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        }
    }

    override fun onUpdate(ctx: Context, am: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(ctx, am, it) }
        if (Build.VERSION.SDK_INT < 31 && ids.isNotEmpty()) am.notifyAppWidgetViewDataChanged(ids, R.id.list)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) = NoteWidgets.unbind(ctx, ids)

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                if (intent.getStringExtra(EXTRA_TOK) != NoteWidgets.token(ctx)) return
                val n = intent.getLongExtra(EXTRA_NOTE, -1L)
                val i = intent.getIntExtra(EXTRA_INDEX, -1)
                // gagal (catatan sudah berubah): gambar ulang supaya widget menampilkan isi terbaru
                if (n < 0 || i < 0 || !Notes.toggle(ctx, n, i, intent.getStringExtra(EXTRA_TEXT))) updateAll(ctx)
            }
            ACTION_PINNED -> {
                if (intent.getStringExtra(EXTRA_TOK) != NoteWidgets.token(ctx)) return
                val w = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val n = intent.getLongExtra(EXTRA_NOTE, -1L)
                if (w != AppWidgetManager.INVALID_APPWIDGET_ID && n >= 0) NoteWidgets.bind(ctx, w, n)
            }
            else -> super.onReceive(ctx, intent)
        }
    }
}

/**
 * Widget DAFTAR catatan: catatan terbaru (disematkan dulu) + tombol catatan baru & daftar centang baru. Ketuk catatan
 * membukanya; ketuk judul membuka semua catatan.
 */
class NotesListWidget : AppWidgetProvider() {
    companion object {
        fun updateAll(ctx: Context) {
            val am = AppWidgetManager.getInstance(ctx) ?: return
            val ids = am.getAppWidgetIds(ComponentName(ctx, NotesListWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { update(ctx, am, it) }
            if (Build.VERSION.SDK_INT < 31) am.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        fun update(ctx: Context, am: AppWidgetManager, id: Int) {
            try { am.updateAppWidget(id, build(ctx, id)) } catch (_: Throwable) {}
        }

        private fun build(ctx: Context, id: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_notes)
            WidgetPaint.tint(v, R.id.bg, WidgetPaint.SURFACE_L, WidgetPaint.SURFACE_D)
            WidgetPaint.text(v, R.id.title)
            WidgetPaint.text(v, R.id.count, muted = true)
            WidgetPaint.text(v, R.id.empty, muted = true)
            WidgetPaint.tint(v, R.id.logo, NoteColors.PRIMARY_L, NoteColors.PRIMARY_D)
            WidgetPaint.tint(v, R.id.add_bg, NoteColors.PRIMARY_L, NoteColors.PRIMARY_D)
            WidgetPaint.tint(v, R.id.add_ic, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
            WidgetPaint.tint(v, R.id.add_list_bg, WidgetPaint.ROW_L, WidgetPaint.ROW_D)
            WidgetPaint.tint(v, R.id.add_list_ic, NoteColors.TEXT_L, NoteColors.TEXT_D)
            val count = Notes.count(ctx)
            v.setTextViewText(R.id.count, if (count > 0) "$count" else "")
            v.setOnClickPendingIntent(R.id.open, WidgetPaint.activity(ctx, Intent(ctx, NotesActivity::class.java), "semua"))
            v.setOnClickPendingIntent(R.id.add, WidgetPaint.activity(ctx,
                Intent(ctx, NoteEditActivity::class.java).putExtra(NoteEditActivity.EXTRA_CHECKLIST, false), "baru/teks"))
            v.setOnClickPendingIntent(R.id.add_list, WidgetPaint.activity(ctx,
                Intent(ctx, NoteEditActivity::class.java).putExtra(NoteEditActivity.EXTRA_CHECKLIST, true), "baru/centang"))
            WidgetPaint.list(ctx, v, id, NoteWidgetService.KIND_LIST) { rows(ctx) }
            v.setPendingIntentTemplate(R.id.list, WidgetPaint.activity(ctx, Intent(ctx, NoteEditActivity::class.java), "daftar/$id", mutable = true))
            v.setEmptyView(R.id.list, R.id.empty)
            return v
        }

        /** Baris catatan (dipakai juga [NoteWidgetService] di Android 10–11). */
        fun rows(ctx: Context): List<RemoteViews> = Notes.all(ctx).take(40).map { n ->
            val r = RemoteViews(ctx.packageName, R.layout.widget_notes_row)
            if (n.color == 0) WidgetPaint.tint(r, R.id.row_bg, WidgetPaint.ROW_L, WidgetPaint.ROW_D)
            else WidgetPaint.tone(r, R.id.row_bg, NoteColors.of(n.color))
            val title = NoteViews.headline(n)
            val sub = NoteViews.summary(n, title)
            r.setTextViewText(R.id.title, title)
            r.setTextViewText(R.id.sub, sub)
            r.setViewVisibility(R.id.sub, if (sub.isBlank()) View.GONE else View.VISIBLE)
            r.setContentDescription(R.id.row, "$title. $sub")
            WidgetPaint.text(r, R.id.title)
            WidgetPaint.text(r, R.id.sub, muted = true)
            r.setOnClickFillInIntent(R.id.row, Intent().putExtra(NoteEditActivity.EXTRA_ID, n.id))
            r
        }
    }

    override fun onUpdate(ctx: Context, am: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(ctx, am, it) }
        if (Build.VERSION.SDK_INT < 31 && ids.isNotEmpty()) am.notifyAppWidgetViewDataChanged(ids, R.id.list)
    }
}

/** Android 10–11: penyedia baris daftar widget (Android 12+ mengirim baris langsung, lihat [WidgetPaint.list]). */
class NoteWidgetService : RemoteViewsService() {
    companion object {
        const val KIND = "depup_kind"
        const val KIND_NOTE = 0
        const val KIND_LIST = 1
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(
        applicationContext, intent.getIntExtra(KIND, KIND_NOTE),
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    )

    private class Factory(val ctx: Context, val kind: Int, val widgetId: Int) : RemoteViewsFactory {
        private var rows: List<RemoteViews> = emptyList()
        override fun onCreate() {}
        override fun onDataSetChanged() {
            rows = try { if (kind == KIND_LIST) NotesListWidget.rows(ctx) else NoteWidget.rowsFor(ctx, widgetId) } catch (_: Throwable) { emptyList() }
        }
        override fun onDestroy() { rows = emptyList() }
        override fun getCount() = rows.size
        override fun getViewAt(position: Int): RemoteViews = rows.getOrNull(position) ?: RemoteViews(ctx.packageName, R.layout.widget_note_text)
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
