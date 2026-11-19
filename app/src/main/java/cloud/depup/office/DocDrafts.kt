package cloud.depup.office

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Draf pemulihan editor Word. Bila ada perubahan yang belum disimpan, editor menulis salinan lengkap dokumen
 * (.docx utuh) ke penyimpanan PRIVAT aplikasi setiap kali aplikasi masuk latar — saat itulah Android biasa mematikan
 * aplikasi untuk menghemat memori. Kembali ke aplikasi: editor langsung pulih dari draf; aplikasi sempat ditutup dari
 * daftar terbaru: layar utama menawarkan memulihkannya.
 *
 * Draf dihapus begitu dokumen disimpan atau pengguna memilih "Jangan Simpan". Dokumen terkunci (password) tidak
 * pernah dibuatkan draf. Draf berumur lebih dari [MAX_AGE_MS] dibuang otomatis.
 */
object DocDrafts {
    class Draft(val id: String, val file: File, val name: String, val savedPath: String?, val time: Long)

    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    /** Draf milik editor yang sedang hidup di proses ini — tidak ditawarkan ulang oleh layar utama. */
    private val live = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun dir(ctx: Context) = File(ctx.filesDir, "drafts").apply { mkdirs() }
    private fun docx(ctx: Context, id: String) = File(dir(ctx), "$id.docx")
    private fun meta(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    private fun safeId(id: String) = id.all { it.isLetterOrDigit() || it == '-' }

    fun markLive(id: String, on: Boolean) { if (on) live.add(id) else live.remove(id) }

    /**
     * Tulis draf [id]. [save] menulis .docx ke berkas yang diberikan (lewat .part + rename, jadi draf lama tetap utuh
     * bila penulisan gagal). Metadata ditulis SETELAH .docx lengkap.
     */
    fun write(ctx: Context, id: String, name: String, savedPath: String?, save: (File) -> Unit): Boolean {
        if (!safeId(id)) return false
        return try {
            save(docx(ctx, id))
            val m = JSONObject().put("name", name).put("saved", savedPath ?: "").put("time", System.currentTimeMillis())
            val tmp = File(dir(ctx), "$id.json.part")
            tmp.writeText(m.toString())
            if (!tmp.renameTo(meta(ctx, id))) { tmp.copyTo(meta(ctx, id), overwrite = true); tmp.delete() }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun get(ctx: Context, id: String): Draft? {
        if (!safeId(id)) return null
        val f = docx(ctx, id)
        val m = meta(ctx, id)
        if (!f.isFile || f.length() == 0L || !m.isFile) return null
        return try {
            val j = JSONObject(m.readText())
            Draft(id, f, j.optString("name").ifBlank { "Dokumen" }, j.optString("saved").ifBlank { null }, j.optLong("time"))
        } catch (_: Exception) {
            null
        }
    }

    fun clear(ctx: Context, id: String) {
        if (!safeId(id)) return
        docx(ctx, id).delete(); meta(ctx, id).delete()
        File(dir(ctx), "$id.docx.part").delete(); File(dir(ctx), "$id.json.part").delete()
    }

    /** Draf terbaru yang tidak sedang dipakai editor yang hidup; draf kedaluwarsa/rusak dibersihkan. */
    fun latestOrphan(ctx: Context): Draft? {
        val now = System.currentTimeMillis()
        val all = dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { get(ctx, it.name.removeSuffix(".json")) }
        all.filter { now - it.time > MAX_AGE_MS }.forEach { clear(ctx, it.id) }
        // .docx tanpa metadata (penulisan terputus) dibuang
        dir(ctx).listFiles { f -> f.name.endsWith(".docx") }.orEmpty()
            .filter { !meta(ctx, it.name.removeSuffix(".docx")).exists() && now - it.lastModified() > 60_000L }
            .forEach { it.delete() }
        return all.filter { now - it.time <= MAX_AGE_MS && it.id !in live }.maxByOrNull { it.time }
    }
}
