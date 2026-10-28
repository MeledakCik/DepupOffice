package cloud.depup.office

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val PDF = "application/pdf"
const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

object Files {
    fun nameOf(ctx: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun byName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun mimeOf(ctx: Context, uri: Uri): String {
        val t = ctx.contentResolver.getType(uri)
        if (!t.isNullOrBlank() && t != "application/octet-stream") return t
        return byName(nameOf(ctx, uri))
    }

    /**
     * Menyalin aliran berkas dari WPS Office, Microsoft Office, WhatsApp, atau penyedia berkas pihak ketiga
     * ke berkas lokal sementara di cacheDir agar dapat diakses penuh (seekable) oleh PdfRenderer & OfficeReader.
     */
    fun cacheCopy(ctx: Context, uri: Uri): Uri {
        if (uri.scheme == "file") return uri
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        return try {
            val name = nameOf(ctx, uri)
            val ext = name.substringAfterLast('.', "tmp")
            val cleanBase = baseName(name).replace(Regex("[^\\p{L}\\p{N}_.-]+"), "_")
            val tempFile = File(ctx.cacheDir, "open_${cleanBase}.$ext")
            
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                Uri.fromFile(tempFile)
            } else {
                uri
            }
        } catch (_: Exception) {
            uri
        }
    }

    fun outDir(ctx: Context): File {
        val sd = Environment.getExternalStorageDirectory()
        val depupDir = File(sd, "DepupOffice")
        if (!depupDir.exists()) depupDir.mkdirs()
        if (depupDir.exists() && depupDir.canWrite()) return depupDir
        val docs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DepupOffice")
        if (!docs.exists()) docs.mkdirs()
        return if (docs.exists()) docs else File(ctx.filesDir, "DepupOffice").apply { mkdirs() }
    }

    fun rename(ctx: Context, uri: Uri, newName: String): File? {
        val clean = newName.trim().replace(Regex("[\\/:*?\"<>|]+"), "_")
        if (clean.isBlank()) return null

        fun doRename(oldFile: File): File? {
            val ext = oldFile.extension
            val targetName = if (ext.isBlank() || clean.endsWith(".$ext", ignoreCase = true)) clean else "$clean.$ext"
            val newFile = File(oldFile.parentFile, targetName)
            if (oldFile.renameTo(newFile)) {
                val newUri = uriFor(ctx, newFile)
                Recent.remove(ctx, uri.toString())
                Recent.add(ctx, newUri, newFile.name, mimeOf(ctx, newUri))
                return newFile
            }
            return null
        }

        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            if (f.exists()) return doRename(f)
        } else if (uri.scheme == "content") {
            val f = outDir(ctx).listFiles()?.find { uriFor(ctx, it) == uri }
                ?: outDir(ctx).walkTopDown().find { uriFor(ctx, it) == uri }
                ?: ctx.filesDir.walkTopDown().find { uriFor(ctx, it) == uri }
            if (f != null && f.exists()) return doRename(f)
        }
        return null
    }

    fun newOut(ctx: Context, base: String, ext: String): File {
        val s = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(outDir(ctx), "${base}_$s.$ext")
    }

    /** Nama pilihan pengguna; kalau sudah ada, ditambah cap waktu supaya tidak menimpa. */
    fun namedOut(ctx: Context, name: String, ext: String): File {
        val safe = name.replace(Regex("[^\\p{L}\\p{N} _.-]+"), "_").trim().ifBlank { "dokumen" }
        val f = File(outDir(ctx), "$safe.$ext")
        return if (f.exists()) newOut(ctx, safe, ext) else f
    }

    fun uriFor(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)

    fun fmtSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
        else -> "%.1f MB".format(b / 1048576.0)
    }

    fun baseName(name: String): String =
        name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]+"), "_").ifBlank { "file" }

    /** PDF dibuka di pembaca bawaan; format lain diserahkan ke aplikasi office di perangkat. */
    fun open(ctx: Context, uri: Uri, mime: String) {
        ctx.startActivity(Intent(ctx, ViewerActivity::class.java).apply {
            data = uri
            putExtra("mime", mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    fun share(ctx: Context, uri: Uri, mime: String, whatsapp: Boolean) {
        try { Meta.logShare(ctx, nameOf(ctx, uri), if (whatsapp) "WhatsApp" else "Menu bagikan", sizeOf(ctx, uri)) } catch (_: Exception) {}
        val i = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (whatsapp) {
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                try {
                    ctx.startActivity(Intent(i).setPackage(pkg))
                    return
                } catch (_: Exception) {}
            }
            ctx.toast("WhatsApp tidak ditemukan, pilih aplikasi lain")
        }
        ctx.startActivity(Intent.createChooser(i, "Bagikan"))
    }

    /** Ukuran berkas (byte), -1 bila tidak diketahui. */
    fun sizeOf(ctx: Context, uri: Uri): Long {
        try {
            if (uri.scheme == "content") {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
                }
            } else if (uri.scheme == "file") {
                return File(uri.path ?: return -1L).length()
            }
        } catch (_: Exception) {}
        return -1L
    }

    /** "10 menit lalu", "Kemarin, 16:40", dst. */
    fun relTime(ms: Long): String {
        if (ms <= 0L) return ""
        val diff = System.currentTimeMillis() - ms
        val min = diff / 60000
        val hour = min / 60
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val year = cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = ms
        val sameYear = cal.get(java.util.Calendar.YEAR) == year
        val dayDiff = if (sameYear) today - cal.get(java.util.Calendar.DAY_OF_YEAR) else 99
        return when {
            min < 1 -> "Baru saja"
            min < 60 -> "$min menit lalu"
            hour < 24 && dayDiff == 0 -> "$hour jam lalu"
            dayDiff == 1 -> "Kemarin, " + SimpleDateFormat("HH:mm", Locale("id")).format(Date(ms))
            dayDiff in 2..6 -> "$dayDiff hari lalu"
            else -> SimpleDateFormat("d MMM yyyy", Locale("id")).format(Date(ms))
        }
    }

    fun fmtGb(b: Long): String = "%.1f GB".format(b / 1073741824.0)

    /** Ruang kosong & total penyimpanan internal perangkat. */
    fun deviceStorage(): Pair<Long, Long> {
        val s = StatFs(Environment.getDataDirectory().path)
        return s.availableBytes to s.totalBytes
    }

    /** Cetak PDF lewat layanan cetak Android (printer WiFi, Mopria, simpan sebagai PDF, dll). */
    fun print(ctx: Context, uri: Uri, name: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(name, object : PrintDocumentAdapter() {
            override fun onLayout(
                old: PrintAttributes?, new: PrintAttributes?, cs: CancellationSignal?,
                cb: LayoutResultCallback, extras: Bundle?
            ) {
                if (cs?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(
                    PrintDocumentInfo.Builder(name)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true
                )
            }
            override fun onWrite(
                pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cs: CancellationSignal?,
                cb: WriteResultCallback
            ) {
                try {
                    ctx.contentResolver.openInputStream(uri)!!.use { i ->
                        ParcelFileDescriptor.AutoCloseOutputStream(dest).use { o -> i.copyTo(o) }
                    }
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) { cb.onWriteFailed(e.message) }
            }
        }, null)
    }

    fun saveToDownloads(ctx: Context, src: Uri, name: String, mime: String) {
        val r = ctx.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Depup Office")
        }
        val dst = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("Gagal membuat file di Unduhan")
        r.openInputStream(src)!!.use { i -> r.openOutputStream(dst)!!.use { o -> i.copyTo(o) } }
    }
}

/** Riwayat file terakhir (disimpan ringan di SharedPreferences). Baris: uri, nama, mime, waktu. */
object Recent {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("depup", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<List<String>> {
        val raw = sp(ctx).getString("recent", "") ?: ""
        return raw.split("\n").filter { it.isNotBlank() }
            .map { it.split("\t") }.filter { it.size >= 3 && !it[0].startsWith("mock://") }
            .map { if (it.size >= 4) it else it + "0" }
    }

    fun add(ctx: Context, uri: Uri, name: String, mime: String) {
        val clean = name.replace("\t", " ").replace("\n", " ")
        val list = load(ctx).filter { it[0] != uri.toString() }.toMutableList()
        list.add(0, listOf(uri.toString(), clean, mime, System.currentTimeMillis().toString()))
        save(ctx, list.take(15))
    }

    fun remove(ctx: Context, uri: String) = save(ctx, load(ctx).filter { it[0] != uri })

    private fun save(ctx: Context, list: List<List<String>>) {
        sp(ctx).edit().putString("recent", list.joinToString("\n") { it.joinToString("\t") }).apply()
    }
}

/** Data ringan pendukung tampilan: penanda (bookmark), riwayat berbagi, dan proses terakhir. */
object Meta {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("depup", Context.MODE_PRIVATE)

    fun marks(ctx: Context): Set<String> = sp(ctx).getStringSet("marks", emptySet()) ?: emptySet()
    fun isMarked(ctx: Context, uri: String) = marks(ctx).contains(uri)

    /** Kembalikan status baru (true = sekarang ditandai). */
    fun toggleMark(ctx: Context, uri: String): Boolean {
        val s = HashSet(marks(ctx))
        val on = if (s.contains(uri)) { s.remove(uri); false } else { s.add(uri); true }
        sp(ctx).edit().putStringSet("marks", s).apply()
        return on
    }

    /** Baris: nama, tujuan, ukuran, waktu. */
    fun shares(ctx: Context): List<List<String>> =
        sp(ctx).getString("shares", "")!!.split("\n").filter { it.isNotBlank() }
            .map { it.split("\t") }.filter { it.size == 4 }

    fun logShare(ctx: Context, name: String, target: String, size: Long) {
        val n = name.replace("\t", " ").replace("\n", " ")
        val list = shares(ctx).toMutableList()
        list.add(0, listOf(n, target, size.toString(), System.currentTimeMillis().toString()))
        sp(ctx).edit().putString("shares", list.take(10).joinToString("\n") { it.joinToString("\t") }).apply()
    }

    fun setLastJob(ctx: Context, title: String, file: File) {
        sp(ctx).edit().putString("lastjob", listOf(title, file.name, file.absolutePath).joinToString("\t")).apply()
    }

    /** (judul proses, nama berkas, path) atau null bila belum ada / berkas sudah hilang. */
    fun lastJob(ctx: Context): Triple<String, String, String>? {
        val p = sp(ctx).getString("lastjob", null)?.split("\t") ?: return null
        if (p.size != 3 || !File(p[2]).exists()) return null
        return Triple(p[0], p[1], p[2])
    }
}

/** Pengaturan khusus format Dokumen Word (.docx). */
object DocSettings {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("doc_settings", Context.MODE_PRIVATE)

    fun fontFamily(ctx: Context): Int = sp(ctx).getInt("font_family", 0)
    fun setFontFamily(ctx: Context, v: Int) = sp(ctx).edit().putInt("font_family", v).apply()

    fun fontSize(ctx: Context): Float = sp(ctx).getFloat("font_size", 12f)
    fun setFontSize(ctx: Context, v: Float) = sp(ctx).edit().putFloat("font_size", v).apply()

    fun marginMode(ctx: Context): Int = sp(ctx).getInt("margin_mode", 0)
    fun setMarginMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("margin_mode", v).apply()

    fun lineSpacing(ctx: Context): Float = sp(ctx).getFloat("line_spacing", 1.15f)
    fun setLineSpacing(ctx: Context, v: Float) = sp(ctx).edit().putFloat("line_spacing", v).apply()

    fun pageSize(ctx: Context): String = sp(ctx).getString("page_size", "A4") ?: "A4"
    fun setPageSize(ctx: Context, v: String) = sp(ctx).edit().putString("page_size", v).apply()
}

/** Pengaturan khusus format Spreadsheet Excel (.xlsx / .csv). */
object SheetSettings {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("sheet_settings", Context.MODE_PRIVATE)

    fun showGridlines(ctx: Context): Boolean = sp(ctx).getBoolean("gridlines", true)
    fun setShowGridlines(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("gridlines", v).apply()

    fun currencySymbol(ctx: Context): String = sp(ctx).getString("currency", "Rp") ?: "Rp"
    fun setCurrencySymbol(ctx: Context, v: String) = sp(ctx).edit().putString("currency", v).apply()

    fun csvSeparator(ctx: Context): String = sp(ctx).getString("separator", ",") ?: ","
    fun setCsvSeparator(ctx: Context, v: String) = sp(ctx).edit().putString("separator", v).apply()

    fun autoSum(ctx: Context): Boolean = sp(ctx).getBoolean("auto_sum", true)
    fun setAutoSum(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_sum", v).apply()
}

/** Pengaturan khusus Reader PDF. */
object PdfSettings {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("pdf_settings", Context.MODE_PRIVATE)

    fun defaultZoom(ctx: Context): Float = sp(ctx).getFloat("zoom", 1.0f)
    fun setDefaultZoom(ctx: Context, v: Float) = sp(ctx).edit().putFloat("zoom", v).apply()

    fun readerTheme(ctx: Context): Int = sp(ctx).getInt("theme", 0)
    fun setReaderTheme(ctx: Context, v: Int) = sp(ctx).edit().putInt("theme", v).apply()

    fun smoothScroll(ctx: Context): Boolean = sp(ctx).getBoolean("smooth_scroll", true)
    fun setSmoothScroll(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("smooth_scroll", v).apply()
}
