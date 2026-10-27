package cloud.depup.office

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

class OcrLine(val text: String, val box: Rect)

/** Hasil OCR satu halaman: teks utuh, paragraf (untuk Word), dan baris + kotak (untuk PDF yang bisa dicari). */
class OcrPage(val text: String, val paras: List<String>, val lines: List<OcrLine>, val w: Int, val h: Int)

/** OCR offline memakai ML Kit (model Latin sudah ikut di dalam APK, tanpa internet). Panggil dari thread latar. */
object Ocr {
    fun run(bmp: Bitmap): OcrPage {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val res = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 90, TimeUnit.SECONDS)
            val paras = ArrayList<String>()
            val lines = ArrayList<OcrLine>()
            for (b in res.textBlocks) {
                val p = b.lines.joinToString(" ") { it.text.trim() }.trim()
                if (p.isNotEmpty()) paras.add(p)
                for (l in b.lines) {
                    val box = l.boundingBox ?: continue
                    if (l.text.isNotBlank()) lines.add(OcrLine(l.text.trim(), box))
                }
            }
            return OcrPage(res.text.trim(), paras, lines, bmp.width, bmp.height)
        } finally {
            rec.close()
        }
    }
}
