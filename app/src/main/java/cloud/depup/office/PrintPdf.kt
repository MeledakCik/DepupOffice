package cloud.depup.office

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Simpan sebagai PDF" TANPA dialog cetak sistem: dokumen yang tampil di WebView (Word, Excel, PowerPoint, editor
 * dokumen) ditulis langsung ke PDF lewat adaptor cetaknya ([android.print.DepupPdfWriter]) dengan ukuran halaman
 * dokumen itu sendiri — slide tetap lanskap penuh, bukan menempel di atas kertas A4 tegak. Hasilnya tampil di lembar
 * DepUp ([pdfReadySheet]). Dialog cetak sistem hanya dipakai untuk mencetak ke printer, atau bila cara ini gagal.
 */
object PdfExport {
    private val main = Handler(Looper.getMainLooper())

    /** Ukuran kertas baku: dipakai bila ukuran dokumen cocok (printer mengenali namanya). */
    private val KNOWN = listOf(
        PrintAttributes.MediaSize.ISO_A4, PrintAttributes.MediaSize.ISO_A5, PrintAttributes.MediaSize.ISO_A3,
        PrintAttributes.MediaSize.NA_LETTER, PrintAttributes.MediaSize.NA_LEGAL
    )

    /** Ukuran halaman dari @page dokumen (pt), disimpan [OfficeView.load] di tag WebView; tanpa @page → A4 tegak. */
    fun mediaOf(wv: WebView): PrintAttributes.MediaSize {
        val pt = wv.getTag(R.id.page_pt) as? FloatArray ?: return PrintAttributes.MediaSize.ISO_A4
        return mediaFromPt(pt[0], pt[1])
    }

    fun mediaFromPt(wPt: Float, hPt: Float): PrintAttributes.MediaSize {
        if (wPt <= 1f || hPt <= 1f) return PrintAttributes.MediaSize.ISO_A4
        val wm = (wPt / 72f * 1000f).roundToInt(); val hm = (hPt / 72f * 1000f).roundToInt()
        val land = wm > hm
        val short = min(wm, hm); val long = max(wm, hm)
        KNOWN.firstOrNull { abs(it.widthMils - short) <= 40 && abs(it.heightMils - long) <= 40 }
            ?.let { return if (land) it.asLandscape() else it.asPortrait() }
        // ukuran persis dokumen (mis. slide 16:9)
        return PrintAttributes.MediaSize("depup_${wm}x$hm", if (land) "Ukuran slide" else "Ukuran dokumen", wm, hm)
    }

    fun attrs(media: PrintAttributes.MediaSize): PrintAttributes = PrintAttributes.Builder()
        .setMediaSize(media)
        .setResolution(PrintAttributes.Resolution("depup_pdf", "PDF", 600, 600))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .build()

    /**
     * Tulis isi [wv] ke PDF baru di folder hasil (Documents/DepUp Office). [done] di thread utama: berkas PDF, atau
     * null bila perangkat menolak / gagal — pemanggil lalu memakai dialog cetak sistem.
     */
    fun write(a: AppCompatActivity, wv: WebView, name: String, media: PrintAttributes.MediaSize = mediaOf(wv), done: (File?) -> Unit) {
        val base = name.substringBeforeLast('.').ifBlank { "Dokumen" }
        val out = Files.newOut(a, base, "pdf")
        var fired = false
        fun finish(ok: Boolean) {
            if (fired) return
            fired = true
            val good = ok && out.length() > 0
            if (!good) out.delete()
            if (!a.isFinishing && !a.isDestroyed) done(if (good) out else null)
        }
        try {
            val adapter = wv.createPrintDocumentAdapter(base)
            val cancel = android.print.DepupPdfWriter.write(adapter, attrs(media), out) { ok, _ -> main.post { finish(ok) } }
            // jaga-jaga: mesin cetak tidak pernah menjawab → batalkan & pakai dialog sistem
            main.postDelayed({ if (!fired) { try { cancel.cancel() } catch (_: Throwable) {}; finish(false) } }, 60_000L)
        } catch (_: Throwable) {
            finish(false)
        }
    }
}

/**
 * Lembar "Cetak & PDF" DepUp: pilih Simpan sebagai PDF (langsung jadi, tanpa dialog sistem) atau Cetak ke printer.
 */
fun AppCompatActivity.printChoiceSheet(name: String, onPdf: () -> Unit, onPrint: () -> Unit) = sheet { body, dismiss ->
    sheetHeader(body, R.drawable.ic_print, C.ACCENT, C.BLUE_BG, "Cetak & PDF", name, dismiss)
    val pad = vbox().apply { setPadding(dp(20), dp(4), dp(20), dp(8)) }
    pad.add(actionRow(R.drawable.ic_pdf, "Simpan sebagai PDF", "Ukuran halaman sesuai dokumen · langsung jadi", C.PDF_FG, C.PDF_BG) {
        dismiss(); onPdf()
    })
    pad.add(actionRow(R.drawable.ic_print, "Cetak ke printer", "Printer Wi-Fi / Mopria — pilih printer di langkah berikutnya", C.ACCENT, C.BLUE_BG) {
        dismiss(); onPrint()
    }, top = 8)
    body.add(pad)
    body.add(View(this), h = dp(12))
}

/**
 * Lembar "PDF siap": pratinjau halaman (dirender di latar), lalu Buka · Bagikan · WhatsApp · Cetak.
 * Berkas sudah tersimpan di folder hasil & masuk daftar Terkini.
 */
fun AppCompatActivity.pdfReadySheet(file: File, title: String = "PDF siap") {
    val uri = Files.uriFor(this, file)
    Recent.add(this, uri, file.name, PDF)
    Meta.setLastJob(this, title, file)
    val pages = try { Src(this, uri).use { it.r.pageCount } } catch (_: Throwable) { 0 }
    sheet { body, dismiss ->
        val sub = listOf(file.name, if (pages > 0) "$pages halaman" else "", Files.fmtSize(file.length())).filter { it.isNotBlank() }.joinToString(" · ")
        sheetHeader(body, R.drawable.ic_check_ok, C.TEAL, C.TEAL_BG, title, sub, dismiss)

        // pratinjau: deret halaman seperti lembaran kertas, dimuat bertahap
        val n = min(pages, if (Perf.low) 5 else 8)
        if (n > 0) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(20), dp(4), dp(20), dp(10))
            }
            val ratio = try {
                Src(this, uri).use { s -> s.r.openPage(0).use { p -> p.width.toFloat() / p.height.coerceAtLeast(1) } }
            } catch (_: Throwable) { 0.707f }
            val th = dp(if (ratio > 1f) 118 else 168)
            val tw = (th * ratio).roundToInt().coerceIn(dp(70), dp(260))
            val thumbs = (0 until n).map { i ->
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = shape(Color.WHITE, 8, C.LINE, 1)
                    elevation = dp(2).toFloat()
                    contentDescription = "Halaman ${i + 1}"
                }
            }
            thumbs.forEachIndexed { i, v ->
                strip.add(v, w = tw, h = th, side = 0)
                if (i > 0) (v.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            }
            body.add(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(strip)
            }, top = 2)
            val app = applicationContext
            Perf.io.execute {
                try {
                    Src(app, uri).use { s ->
                        for (i in 0 until n) {
                            if (isFinishing || isDestroyed) break
                            val rd = Pdf.render(s.r, i, 1f, tw)
                            runOnUiThread {
                                val v = thumbs[i]
                                v.setImageBitmap(rd.bmp)
                                if (Motion.on()) { v.alpha = 0f; v.animate().alpha(1f).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).start() }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        val pad = vbox().apply { setPadding(dp(20), dp(6), dp(20), dp(8)) }
        pad.add(btn("Buka PDF", true, C.ACCENT, R.drawable.ic_pdf) { dismiss(); Files.open(this, uri, PDF) })
        pad.add(actionRow(R.drawable.ic_upload, "Bagikan", "Kirim ke aplikasi lain", C.INDIGO, C.INDIGO_BG) {
            dismiss(); Files.share(this, uri, PDF, false)
        }, top = 14)
        pad.add(actionRow(R.drawable.ic_chat, "Kirim via WhatsApp", "", C.TEAL, C.TEAL_BG) {
            dismiss(); Files.share(this, uri, PDF, true)
        }, top = 8)
        pad.add(actionRow(R.drawable.ic_print, "Cetak ke printer", "Pilih printer di langkah berikutnya", C.ACCENT, C.BLUE_BG) {
            dismiss(); PrintHelper.printPdf(this, uri, file.name)
        }, top = 8)
        pad.add(tv("Tersimpan di Documents/DepUp Office · ada di Dokumen → Hasil olahan", 12f, C.MUTED).apply {
            gravity = Gravity.CENTER
        }, top = 14)
        body.add(pad)
        body.add(View(this), h = dp(12))
    }
}
