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

    fun run(bmp: Bitmap): OcrPage {
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
                        val syms = e.symbols.mapNotNull { s ->
                            val sb = s.boundingBox ?: return@mapNotNull null
                            LSym(s.text, LBox(sb.left, sb.top, sb.right, sb.bottom))
                        }
                        words.add(LWord(e.text, LBox(eb.left, eb.top, eb.right, eb.bottom), syms, bi, li))
                    }
                }
            }
            val blocks = try {
                OcrLayout.build(words, detectGrids(bmp), bmp.width)
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
