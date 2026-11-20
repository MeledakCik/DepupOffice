package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * Cari ISI dokumen dari kolom "Cari" di tab Dokumen. Teks tiap berkas diambil SEKALI di latar lalu disimpan (memori +
 * disk, berkunci ukuran & waktu ubah — berkas berubah → dibaca ulang), jadi pencarian berikutnya instan:
 *  - PDF: lapisan teks asli lewat [PdfLayout] (gambar tidak diurai); PDF pindaian tanpa teks → OCR beberapa halaman awal
 *  - Word / Excel / PowerPoint (OOXML): teks dari XML di dalam zip
 *  - teks biasa (TXT/CSV/MD) dan foto dokumen (OCR)
 * Pencocokan mengabaikan huruf besar/kecil dan spasi (PDF sering tanpa spasi antar-kata). Berkas yang butuh OCR
 * dikerjakan paling akhir, jadi hasil dari berkas biasa tampil lebih dulu.
 */
object DocSearch {
    /** Berkas yang dicari; [file] diisi untuk berkas lokal aplikasi (lebih cepat daripada content resolver). */
    class Item(val uri: String, val name: String, val mime: String, val file: File? = null)

    /** Kecocokan: cuplikan teks di sekitarnya; [start]..[end] = letak kata yang dicari di dalam [snippet]. */
    class Hit(val item: Item, val snippet: String, val start: Int, val end: Int)

    class Job { @Volatile var cancelled = false; fun cancel() { cancelled = true } }

    private class Doc(val raw: String, val norm: String)

    private const val MAX_CHARS = 300_000
    private const val MAX_PAGES = 150
    private const val DISK_CAP = 24L shl 20
    private const val WORD = 0; private const val CELL = 1; private const val SLIDE = 2

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "cari-isi").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val mem = object : LruCache<String, Doc>(if (Perf.low) 1_500_000 else 5_000_000) {
        override fun sizeOf(key: String, value: Doc) = value.raw.length + value.norm.length + 32
    }
    /** PDF tanpa lapisan teks yang belum di-OCR (dikerjakan di putaran kedua). */
    private val needsOcr = HashSet<String>()

    /**
     * Cari [query] di isi [items]. [onUpdate] dipanggil di thread utama — beberapa kali selama berjalan (hasil
     * bertambah) lalu sekali lagi dengan done = true. Panggil [Job.cancel] bila kata kunci berganti.
     */
    fun search(ctx: Context, items: List<Item>, query: String, onUpdate: (hits: List<Hit>, done: Boolean) -> Unit): Job {
        val job = Job()
        val needle = DocText.norm(query)
        if (needle.length < 2 || items.isEmpty()) { main.post { if (!job.cancelled) onUpdate(emptyList(), true) }; return job }
        val app = ctx.applicationContext
        exec.execute {
            val hits = ArrayList<Hit>()
            var last = 0L
            fun post(done: Boolean) {
                val snap = ArrayList(hits)
                main.post { if (!job.cancelled) onUpdate(snap, done) }
            }
            fun check(item: Item, ocr: Boolean) {
                val d = try { doc(app, item, ocr) } catch (_: Throwable) { null } ?: return
                match(item, d, needle)?.let { h ->
                    hits.add(h)
                    val now = SystemClock.uptimeMillis()
                    if (now - last > 160) { last = now; post(false) }
                }
            }
            // putaran 1: teks yang sudah ada / bisa dibaca langsung; putaran 2: foto & pindaian (OCR, lambat)
            val later = ArrayList<Item>()
            for (item in items) {
                if (job.cancelled) return@execute
                if (isImage(item) && cachedDoc(app, item) == null) later.add(item) else {
                    check(item, ocr = false)
                    if (needsOcr.contains(item.uri)) later.add(item)
                }
            }
            if (later.isNotEmpty()) post(false)
            for (item in later) {
                if (job.cancelled) return@execute
                check(item, ocr = true)
            }
            post(true)
        }
        return job
    }

    private fun match(item: Item, d: Doc, needle: String): Hit? =
        DocText.snippet(d.raw, d.norm, needle)?.let { sn -> Hit(item, sn.text, sn.start, sn.end) }

    // ------------------------------------------------------------------ cache

    private fun dir(ctx: Context) = File(ctx.cacheDir, "cari-isi").apply { mkdirs() }

    private fun key(ctx: Context, item: Item): String? = fingerprint(ctx, item)?.let { fp -> item.uri + "|" + fp }

    private fun cachedDoc(ctx: Context, item: Item): Doc? {
        val k = key(ctx, item) ?: return null
        mem.get(k)?.let { return it }
        val f = File(dir(ctx), sha1(k))
        if (!f.exists()) return null
        return try {
            val raw = f.readText()
            Doc(raw, DocText.norm(raw)).also { d -> mem.put(k, d); f.setLastModified(System.currentTimeMillis()) }
        } catch (_: Throwable) { null }
    }

    /** Teks berkas: dari cache, atau dibaca sekarang. [ocr] = boleh OCR (putaran kedua); tanpanya PDF pindaian ditunda. */
    private fun doc(ctx: Context, item: Item, ocr: Boolean): Doc? {
        cachedDoc(ctx, item)?.let { return it }
        val k = key(ctx, item) ?: return null          // tidak bisa diakses lagi (izin dicabut / berkas hilang)
        val raw = try { extract(ctx, item, ocr) } catch (_: Throwable) { "" } ?: return null   // null = ditunda ke OCR
        needsOcr.remove(item.uri)
        val d = Doc(raw, DocText.norm(raw))
        mem.put(k, d)
        try {
            val f = File(dir(ctx), sha1(k))
            f.writeText(raw)
            prune(ctx)
        } catch (_: Throwable) {}
        return d
    }

    private fun prune(ctx: Context) {
        val files = dir(ctx).listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CAP) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CAP * 3 / 4) break
            total -= f.length(); f.delete()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Sidik sebentar disimpan: saat mengetik, tiap huruf tidak perlu menanyai penyedia berkas lagi. */
    private val fps = HashMap<String, Pair<Long, String?>>()

    /** Ukuran + waktu ubah; null = berkas tidak bisa dibuka lagi. */
    private fun fingerprint(ctx: Context, item: Item): String? {
        val now = SystemClock.uptimeMillis()
        fps[item.uri]?.let { (t, v) -> if (now - t < 20_000) return v }
        return fingerprintNow(ctx, item).also { v -> fps[item.uri] = now to v }
    }

    private fun fingerprintNow(ctx: Context, item: Item): String? {
        val u = Uri.parse(item.uri)
        val f = item.file ?: Files.localFileOf(ctx, u)
        if (f != null) return if (f.isFile) "${f.length()}-${f.lastModified()}" else null
        return try {
            ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                fun col(n: String): Long { val i = c.getColumnIndex(n); return if (i >= 0 && !c.isNull(i)) c.getLong(i) else -1L }
                "${col(OpenableColumns.SIZE)}-${max(col(DocumentsContract.Document.COLUMN_LAST_MODIFIED), col(MediaStore.MediaColumns.DATE_MODIFIED))}"
            }
        } catch (_: Throwable) { null }
    }

    // ------------------------------------------------------------------ ekstraksi

    private fun open(ctx: Context, item: Item): InputStream? =
        item.file?.inputStream() ?: ctx.contentResolver.openInputStream(Uri.parse(item.uri))

    private fun isImage(item: Item) = item.mime.startsWith("image/") ||
        item.name.lowercase().let { n -> n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") }

    /** null = PDF pindaian yang menunggu OCR (putaran kedua). */
    private fun extract(ctx: Context, item: Item, ocr: Boolean): String? {
        val n = item.name.lowercase(); val m = item.mime.lowercase()
        return when {
            m == PDF || n.endsWith(".pdf") -> pdf(ctx, item, ocr)
            n.endsWith(".docx") || n.endsWith(".docm") || n.endsWith(".dotx") || m.contains("wordprocessingml") -> ooxml(ctx, item, WORD)
            n.endsWith(".xlsx") || n.endsWith(".xlsm") || m.contains("spreadsheetml") -> ooxml(ctx, item, CELL)
            n.endsWith(".pptx") || m.contains("presentationml") -> ooxml(ctx, item, SLIDE)
            m.startsWith("text/") || n.endsWith(".txt") || n.endsWith(".csv") || n.endsWith(".md") -> plain(ctx, item)
            isImage(item) -> if (ocr) image(ctx, item) else null
            else -> ""
        }
    }

    private fun pdf(ctx: Context, item: Item, ocr: Boolean): String? {
        if (ocr && needsOcr.contains(item.uri)) return ocrPdf(ctx, item)
        val cap = Files.ramCap()
        val bytes = (item.file?.let { f -> if (f.length() > cap) null else f.readBytes() } ?: Files.readOrNull(ctx, Uri.parse(item.uri), cap)) ?: return ""
        val pdf = PdfFile.open(bytes)                 // terkunci password → dilempar → "" (tidak dicari)
        val sb = StringBuilder()
        for ((i, p) in pdf.pages().withIndex()) {
            if (i >= MAX_PAGES || sb.length > MAX_CHARS) break
            try { DocText.glyphText(PdfLayout.page(pdf, p, textOnly = true).glyphs, sb) } catch (_: OutOfMemoryError) { break } catch (_: Throwable) {}
            sb.append('\n')
        }
        if (sb.any { c -> !DocText.blank(c) }) return sb.toString()
        // pindaian tanpa lapisan teks: OCR (lambat) — di putaran kedua
        if (!ocr) { needsOcr.add(item.uri); return null }
        return ocrPdf(ctx, item)
    }

    private fun ocrPdf(ctx: Context, item: Item): String {
        val sb = StringBuilder()
        val u = item.file?.let { f -> Files.uriFor(ctx, f) } ?: Uri.parse(item.uri)
        Src(ctx, u).use { s ->
            val n = minOf(s.r.pageCount, if (Perf.low) 2 else 4)
            for (i in 0 until n) {
                val rd = Pdf.render(s.r, i, 2f, 0, if (Perf.low) 1800 else 2400)
                try { sb.append(Ocr.run(rd.bmp).text).append('\n') } finally { rd.bmp.recycle() }
            }
        }
        return sb.toString()
    }

    private fun image(ctx: Context, item: Item): String {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(ctx, item)?.use { s -> BitmapFactory.decodeStream(s, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return ""
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= (if (Perf.low) 1400 else 1800)) sample *= 2
        val b0 = open(ctx, item)?.use { s -> BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return ""
        val rot = try { open(ctx, item)?.use { s -> ExifInterface(s).rotationDegrees } ?: 0 } catch (_: Throwable) { 0 }
        val bmp = if (rot == 0) b0 else Bitmap.createBitmap(b0, 0, 0, b0.width, b0.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            .also { r -> if (r !== b0) b0.recycle() }
        try { return Ocr.run(bmp).text } finally { bmp.recycle() }
    }

    private fun plain(ctx: Context, item: Item): String = open(ctx, item)?.use { s ->
        val buf = ByteArray(2 shl 20)
        var n = 0
        while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        String(buf, 0, n, Charsets.UTF_8)
    } ?: ""

    private fun ooxml(ctx: Context, item: Item, kind: Int): String {
        val sb = StringBuilder()
        open(ctx, item)?.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                while (sb.length < MAX_CHARS) {
                    val e = z.nextEntry ?: break
                    val n = e.name
                    when (kind) {
                        WORD -> if (n == "word/document.xml" || n == "word/footnotes.xml" || n == "word/endnotes.xml" ||
                            ((n.startsWith("word/header") || n.startsWith("word/footer")) && n.endsWith(".xml")))
                            DocText.xmlText(read(z), sb)
                        CELL -> when {
                            n == "xl/sharedStrings.xml" -> DocText.xmlText(read(z), sb)
                            SHEET.matches(n) -> DocText.sheet(read(z), sb, MAX_CHARS)
                        }
                        else -> if (n.startsWith("ppt/slides/slide") && n.endsWith(".xml")) DocText.xmlText(read(z), sb)
                    }
                }
            }
        }
        return sb.toString()
    }

    private val SHEET = Regex("^xl/worksheets/sheet\\d+\\.xml$")

    private fun read(z: ZipInputStream) = String(Files.readCapped(z, 24L shl 20), Charsets.UTF_8)


}
