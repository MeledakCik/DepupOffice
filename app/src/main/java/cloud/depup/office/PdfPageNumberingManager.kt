package cloud.depup.office

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Penomoran Halaman & Header/Footer PDF (PDF Page Numbering & Header/Footer).
 * Menambahkan nomor halaman otomatis ("Hal X dari Y") & header/footer pada seluruh halaman PDF.
 */
object PdfPageNumberingManager {

    fun showDialog(activity: AppCompatActivity, onPickFile: (cb: (Uri) -> Unit) -> Unit) {
        onPickFile { uri ->
            showNumberingSheet(activity, uri)
        }
    }

    private fun showNumberingSheet(activity: AppCompatActivity, uri: Uri) {
        val total = try { Pdf.pageCount(activity, uri) } catch (_: Throwable) { 1 }
        val name = Files.nameOf(activity, uri)

        val fHeader = activity.field("Teks Header Opsional (mis: Laporan Resmi)")
        var position = 0 // 0 = Bottom Right, 1 = Bottom Center, 2 = Bottom Left
        var formatType = 0 // 0 = "Hal X dari Y", 1 = "Halaman X", 2 = "X"
        var skipCover = true

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_crop, C.ACCENT, C.BLUE_BG,
                "Penomoran Halaman PDF", "$name • $total halaman", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Format Penomoran", 12f, C.MUTED))
                val fmtHolder = activity.vbox()
                fmtHolder.add(activity.segmented(listOf("Hal X dari Y", "Halaman X", "Hanya Angka (X)"), formatType) {
                    formatType = it
                })
                add(fmtHolder, top = 4)

                add(activity.tv("Posisi Nomor Halaman", 12f, C.MUTED), top = 8)
                val posHolder = activity.vbox()
                posHolder.add(activity.segmented(listOf("Tengah Bawah", "Kanan Bawah", "Kiri Bawah"), position) {
                    position = it
                })
                add(posHolder, top = 4)

                add(activity.tv("Header Teks Opsional (Atas)", 12f, C.MUTED), top = 8)
                add(fHeader)

                val chkCover = android.widget.CheckBox(activity).apply {
                    text = "Lewati Halaman Sampul Pertama (Mulai Hal 2)"
                    setTextColor(C.TEXT)
                    isChecked = skipCover
                    setOnCheckedChangeListener { _, isChecked -> skipCover = isChecked }
                }
                add(chkCover, top = 8)
            }

            pad.add(formBox)

            val actionRow = activity.hbox().apply {
                add(activity.btn("Terapkan Nomor Halaman", true, C.PRIMARY, R.drawable.ic_crop) {
                    dismiss()
                    val headerText = fHeader.text.toString().trim()
                    applyNumbering(activity, uri, name, formatType, position, headerText, skipCover)
                }, w = 0, weight = 1f)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun applyNumbering(
        activity: AppCompatActivity,
        uri: Uri,
        name: String,
        formatType: Int,
        position: Int,
        headerText: String,
        skipCover: Boolean
    ) {
        val outName = Files.baseName(name) + "_nomor"
        val out = Files.newOut(activity, outName, "pdf")

        activity.work("Menambahkan nomor halaman PDF…", {
            // Jalur utama: LOSSLESS — teks, font & vektor asli tetap (bisa dicari/disalin), ukuran hampir tidak berubah.
            // Dulu setiap halaman dirender jadi gambar: teks hilang, hasil buram & berkas membengkak berkali lipat.
            if (numberLossless(activity, uri, out, formatType, position, headerText, skipCover)) return@work out
            // cadangan (PDF terkunci / berotasi / tidak terbaca pengurai): render ulang seperti sebelumnya
            val doc = PdfDocument()
            val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val pNum = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt(); textSize = 11f }
            val pHead = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF888888.toInt(); textSize = 10f; isFakeBoldText = true }

            val pfd = activity.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Gagal membaca file PDF")
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val total = renderer.pageCount

            for (i in 0 until total) {
                val pg = renderer.openPage(i)
                val w = pg.width
                val h = pg.height

                val bmp = Bitmap.createBitmap((w * 1.5f).toInt(), (h * 1.5f).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pg.close()

                val pdfPg = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                val c = pdfPg.canvas
                c.drawBitmap(bmp, null, android.graphics.Rect(0, 0, w, h), bmpPaint)

                val pageNum = i + 1
                val shouldNumber = !skipCover || pageNum > 1

                if (shouldNumber) {
                    val numStr = when (formatType) {
                        0 -> "Hal $pageNum dari $total"
                        1 -> "Halaman $pageNum"
                        else -> "$pageNum"
                    }

                    val margin = w * 0.05f
                    val y = h - (h * 0.03f)

                    when (position) {
                        0 -> { pNum.textAlign = Paint.Align.CENTER; c.drawText(numStr, w / 2f, y, pNum) }
                        1 -> { pNum.textAlign = Paint.Align.RIGHT; c.drawText(numStr, w - margin, y, pNum) }
                        else -> { pNum.textAlign = Paint.Align.LEFT; c.drawText(numStr, margin, y, pNum) }
                    }

                    if (headerText.isNotBlank()) {
                        pHead.textAlign = Paint.Align.LEFT
                        c.drawText(headerText.take(40), margin, h * 0.04f, pHead)
                        c.drawLine(margin, h * 0.048f, w - margin, h * 0.048f, Paint().apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 1f })
                    }
                }

                doc.finishPage(pdfPg)
                bmp.recycle()
            }

            renderer.close()
            pfd.close()

            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
            out
        }) { res ->
            res.onSuccess {
                activity.toast("Nomor halaman berhasil ditambahkan!")
                Files.open(activity, Files.uriFor(activity, out), PDF)
            }.onFailure { activity.fail(it) }
        }
    }

    private fun label(formatType: Int, n: Int, total: Int) = when (formatType) {
        0 -> "Hal $n dari $total"
        1 -> "Halaman $n"
        else -> "$n"
    }

    /** Teks → string literal PDF (WinAnsi/Latin-1; karakter di luarnya menjadi "?"). */
    private fun pdfStr(t: String): String {
        val sb = StringBuilder("(")
        for (ch in t) {
            when {
                ch == '(' || ch == ')' || ch == '\\' -> sb.append('\\').append(ch)
                ch.code in 32..126 || ch.code in 160..255 -> sb.append(ch)
                else -> sb.append('?')
            }
        }
        return sb.append(')').toString()
    }

    /**
     * Tambah nomor halaman & header TANPA merender ulang: tiap halaman diberi stream isi tambahan (teks Helvetica)
     * — isi asli dibungkus q/Q supaya keadaan grafisnya tidak memengaruhi teks kita. false = tidak bisa (terkunci,
     * berotasi, rusak) → pemanggil memakai jalur render.
     */
    private fun numberLossless(
        activity: AppCompatActivity, uri: Uri, out: File,
        formatType: Int, position: Int, headerText: String, skipCover: Boolean
    ): Boolean {
        val f = try { PdfFile.open(Files.readOrNull(activity, uri) ?: return false) } catch (_: Throwable) { return false }
        if (f.encrypted) return false   // jangan sampai salinan bernomor melepas password/izin dokumen asli
        val pages = try { f.pages() } catch (_: Throwable) { return false }
        if (pages.isEmpty() || pages.any { it.ref == null || it.rotate != 0 }) return false
        val total = pages.size
        val measure = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = android.graphics.Typeface.SANS_SERIF }
        val tmp = File(out.parentFile, out.name + ".part")
        return try {
            FileOutputStream(tmp).buffered().use { os ->
                PdfSave.write(f, os) { ctx ->
                    val font = ctx.add(PDict().also {
                        it["Type"] = PName("Font"); it["Subtype"] = PName("Type1")
                        it["BaseFont"] = PName("Helvetica"); it["Encoding"] = PName("WinAnsiEncoding")
                    })
                    val fontB = ctx.add(PDict().also {
                        it["Type"] = PName("Font"); it["Subtype"] = PName("Type1")
                        it["BaseFont"] = PName("Helvetica-Bold"); it["Encoding"] = PName("WinAnsiEncoding")
                    })
                    pages.forEachIndexed { i, p ->
                        val n = i + 1
                        if (skipCover && n == 1) return@forEachIndexed
                        val x0 = p.box[0]; val y0 = p.box[1]; val w = p.box[2] - p.box[0]; val h = p.box[3] - p.box[1]
                        val margin = w * 0.05f
                        val ops = StringBuilder("Q\n")
                        val num = label(formatType, n, total)
                        val tw = measure.measureText(num)
                        val nx = x0 + when (position) { 0 -> (w - tw) / 2f; 1 -> w - margin - tw; else -> margin }
                        val ny = y0 + h * 0.03f
                        ops.append("BT /DpNum 11 Tf 0.333 g ${PdfSave.fmt(nx.toDouble())} ${PdfSave.fmt(ny.toDouble())} Td ${pdfStr(num)} Tj ET\n")
                        if (headerText.isNotBlank()) {
                            val head = headerText.take(40)
                            val hy = y0 + h - h * 0.04f
                            ops.append("BT /DpNumB 10 Tf 0.533 g ${PdfSave.fmt((x0 + margin).toDouble())} ${PdfSave.fmt(hy.toDouble())} Td ${pdfStr(head)} Tj ET\n")
                            val ly = y0 + h - h * 0.048f
                            ops.append("0.8 G 1 w ${PdfSave.fmt((x0 + margin).toDouble())} ${PdfSave.fmt(ly.toDouble())} m " +
                                "${PdfSave.fmt((x0 + w - margin).toDouble())} ${PdfSave.fmt(ly.toDouble())} l S\n")
                        }
                        val pre = ctx.add(PStream(PDict(), "q\n".toByteArray(Charsets.ISO_8859_1)))
                        val post = ctx.add(PStream(PDict(), ops.toString().toByteArray(Charsets.ISO_8859_1)))
                        val nd = PDict(LinkedHashMap(p.dict.m))
                        val old = f.res(p.dict["Contents"])
                        val oldList: List<Any?> = when {
                            old is List<*> -> old
                            p.dict["Contents"] != null -> listOf(p.dict["Contents"])
                            else -> emptyList()
                        }
                        nd["Contents"] = ArrayList<Any?>().apply { add(pre); addAll(oldList); add(post) }
                        // sumber daya efektif (termasuk warisan dari induk) disalin + font kita
                        val r = PDict(LinkedHashMap(p.res.m))
                        val fonts = PDict(LinkedHashMap((f.res(r["Font"]) as? PDict)?.m ?: LinkedHashMap()))
                        fonts["DpNum"] = font; fonts["DpNumB"] = fontB
                        r["Font"] = fonts
                        nd["Resources"] = r
                        ctx.put(p.ref!!, nd)
                    }
                }
            }
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            true
        } catch (_: Throwable) {
            tmp.delete(); false
        }
    }
}
