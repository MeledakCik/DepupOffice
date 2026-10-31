package cloud.depup.office

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

class OcrLine(val text: String, val box: Rect)

/**
 * Hasil OCR satu halaman.
 * text   : teks yang sudah "mirip aslinya" (pangkat jadi ², indeks jadi ₂, tabel dipisah TAB sehingga
 *          saat ditempel ke Word/Excel langsung menjadi tabel).
 * paras  : paragraf polos (kompatibilitas lama).
 * lines  : baris + kotak (untuk PDF yang bisa dicari).
 * blocks : paragraf + tabel terstruktur (untuk Word).
 * html   : versi HTML (sup/sub/tabel) untuk clipboard, supaya Word/Docs menempel dengan format.
 */
class OcrPage(
    val text: String, val paras: List<String>, val lines: List<OcrLine>, val w: Int, val h: Int,
    val blocks: List<Block> = emptyList(), val html: String = "",
    /** Rata-rata keyakinan ML Kit (0..1). Rendah = biasanya tulisan tangan / foto buram. */
    val conf: Float = 1f
)

/** OCR offline memakai ML Kit (model Latin sudah ikut di dalam APK, tanpa internet). Panggil dari thread latar. */
object Ocr {
    const val LOW_CONF = 0.6f

    /**
     * OCR pintar: baca apa adanya dulu. Kalau keyakinan rendah (biasanya tulisan tangan / kertas bergaris),
     * ulangi pada salinan yang garis bukunya sudah dibuang & tintanya dipekatkan, lalu pakai yang lebih meyakinkan.
     * Semua pemanggil lama (Export, ScanEditActivity, ViewerActivity) otomatis ikut lebih pintar.
     */
    fun run(bmp: Bitmap): OcrPage {
        val first = runOnce(bmp, bmp)
        if (first.conf >= LOW_CONF) return first
        val cleaned = OcrPre.handwriting(bmp) ?: return first
        try {
            val second = try { runOnce(cleaned, bmp) } catch (_: Throwable) { return first }
            val enough = second.text.length * 2 >= first.text.length      // jangan pilih hasil yang hampir kosong
            return if (enough && second.conf > first.conf + 0.03f) second else first
        } finally { cleaned.recycle() }
    }

    /** [img] = gambar yang dibaca ML Kit; [gridSrc] = gambar asli untuk mencari garis tabel (garis tidak boleh dibuang di sini). */
    private fun runOnce(img: Bitmap, gridSrc: Bitmap): OcrPage {
        val bmp = img
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val res = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 90, TimeUnit.SECONDS)
            val lines = ArrayList<OcrLine>()
            val words = ArrayList<LWord>()
            var confSum = 0f; var confN = 0
            res.textBlocks.forEachIndexed { bi, b ->
                b.lines.forEachIndexed { li, l ->
                    val box = l.boundingBox
                    if (box != null && l.text.isNotBlank()) lines.add(OcrLine(l.text.trim(), box))
                    for (e in l.elements) {
                        val eb = e.boundingBox ?: continue
                        if (e.text.isBlank()) continue
                        confSum += e.confidence * e.text.length; confN += e.text.length
                        if (isGarbage(e.text, e.confidence)) continue     // sampah OCR (mis. "8e8|po", ">8TL") tidak dimasukkan
                        val syms = e.symbols.mapNotNull { s ->
                            val sb = s.boundingBox ?: return@mapNotNull null
                            LSym(s.text, LBox(sb.left, sb.top, sb.right, sb.bottom))
                        }
                        words.add(LWord(fixNumeric(e.text), LBox(eb.left, eb.top, eb.right, eb.bottom), syms, bi, li))
                    }
                }
            }
            val blocks = try {
                OcrLayout.build(words, detectGrids(gridSrc), bmp.width)
            } catch (_: Throwable) {
                // Jaga-jaga: kalau tata letak gagal, tetap kembalikan paragraf polos dari ML Kit.
                res.textBlocks.mapNotNull { b ->
                    val p = b.lines.joinToString(" ") { it.text.trim() }.trim()
                    if (p.isEmpty()) null else Para(listOf(Run(p)), b.boundingBox?.top ?: 0)
                }
            }
            val rich = OcrLayout.toText(blocks).ifBlank { res.text.trim() }
            val paras = blocks.filterIsInstance<Para>().map { OcrLayout.plain(it.runs) }
            return OcrPage(rich, paras, lines, bmp.width, bmp.height, blocks, OcrLayout.toHtml(blocks), if (confN > 0) confSum / confN else 1f)
        } finally {
            rec.close()
        }
    }

    /** Kata berkeyakinan sangat rendah yang tidak mirip kata/angka. Konservatif: kata cukup yakin selalu lolos. */
    private fun isGarbage(t: String, conf: Float): Boolean {
        val alnum = t.count { it.isLetterOrDigit() }
        if (alnum == 0) return t.length > 1 && conf < 0.5f              // deretan simbol saja
        if (conf >= 0.5f) return false
        if (conf < 0.25f) return true
        // keyakinan 0.25..0.5: buang bila campuran simbol/huruf/angka yang tidak wajar atau tanpa vokal (kecuali angka)
        val symbols = t.length - alnum
        val hasDigit = t.any { it.isDigit() }
        val hasLetter = t.any { it.isLetter() }
        if (symbols * 2 > t.length) return true
        if (hasDigit && hasLetter && t.length <= 6) return true
        if (hasLetter && !hasDigit && t.length >= 4 && t.none { it.lowercaseChar() in "aiueo" }) return true
        return false
    }

    private val DIGIT_LOOKALIKE = mapOf('O' to '0', 'o' to '0', 'l' to '1', 'I' to '1', '|' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')

    /** Token yang jelas angka (mis. "1O5", "l2") diperbaiki huruf-mirip-angkanya; kata biasa tidak disentuh. */
    private fun fixNumeric(t: String): String {
        if (t.none { it.isDigit() }) return t
        if (t.any { !it.isDigit() && it !in DIGIT_LOOKALIKE && it.isLetter() }) return t
        val letters = t.count { it in DIGIT_LOOKALIKE }
        if (letters * 10 > t.length * 4) return t
        return buildString { for (c in t) append(DIGIT_LOOKALIKE[c] ?: c) }
    }

    /** Cari garis tabel di gambar (skala diperkecil supaya cepat), kembalikan kisi dalam koordinat gambar asli. */
    private fun detectGrids(bmp: Bitmap): List<Grid> {
        val sc = if (bmp.width > 1000) 1000f / bmp.width else 1f
        val w = (bmp.width * sc).toInt().coerceAtLeast(1)
        val h = (bmp.height * sc).toInt().coerceAtLeast(1)
        val small = if (sc < 1f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        try {
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            val lum = IntArray(w * h) {
                val c = px[it]
                (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
            }
            return OcrLayout.detectGrids(lum, w, h, 1f / sc)
        } finally {
            if (small !== bmp) small.recycle()
        }
    }
}
