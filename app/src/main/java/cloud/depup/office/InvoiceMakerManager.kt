package cloud.depup.office

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InvoiceItem(
    val name: String,
    val qty: Int,
    val price: Long
) {
    val total: Long get() = qty * price
}

/**
 * Pembuat Nota / Kuitansi Toko Otomatis (Invoice & Receipt Maker).
 * Formulir kuitansi/nota dengan nomor otomatis, hitung total, dan penempelan stempel toko dari Pustaka.
 */
object InvoiceMakerManager {

    /**
     * Angka rupiah/qty dari isian bebas: "15.000", "Rp 15.000", "15,000" → 15000. Dulu toLongOrNull() menolak titik
     * ribuan sehingga harga yang ditulis wajar menjadi Rp0 di nota.
     */
    internal fun digits(s: String): Long? = s.filter { it.isDigit() }.take(15).toLongOrNull()

    fun showDialog(activity: AppCompatActivity, onPickImage: ((cb: (Uri) -> Unit) -> Unit)? = null) {
        val todayStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val invoiceNo = "INV-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())

        val fShop = activity.field("Nama Toko / Usaha (mis: Toko Maju Jaya)").apply { setText("Toko Usaha Mandiri") }
        val fInvNo = activity.field("Nomor Nota / Invoice").apply { setText(invoiceNo) }
        val fDate = activity.field("Tanggal").apply { setText(todayStr) }
        val fCustomer = activity.field("Nama Pembeli / Pelanggan").apply { setText("Bpk. Pelanggan") }

        val itemsContainer = activity.vbox(6)
        val itemRows = ArrayList<Triple<EditText, EditText, EditText>>()

        fun addItemRow(defaultName: String = "", defaultQty: String = "1", defaultPrice: String = "50000") {
            val r = activity.hbox().apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            val fItemName = activity.field("Nama Barang").apply { setText(defaultName) }
            val fQty = activity.field("Qty", number = true).apply { setText(defaultQty) }
            val fPrice = activity.field("Harga Satuan", number = true).apply { setText(defaultPrice) }

            val delBtn = ImageView(activity).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(C.RED)
                setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
                background = activity.shape(C.SURFACE2, 100)
            }

            r.add(fItemName, w = 0, weight = 2f)
            r.add(fQty, w = activity.dp(50), side = 4)
            r.add(fPrice, w = 0, weight = 1.5f)
            r.add(delBtn, w = activity.dp(28), h = activity.dp(28), side = 4)

            val triple = Triple(fItemName, fQty, fPrice)
            itemRows.add(triple)

            delBtn.setOnClickListener {
                itemsContainer.removeView(r)
                itemRows.remove(triple)
            }

            itemsContainer.add(r)
        }

        // Tambah 2 item default
        addItemRow("Barang A", "2", "25000")
        addItemRow("Jasa Service", "1", "100000")

        var selectedStampBmp: Bitmap? = null

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_sheet, C.ACCENT, C.BLUE_BG,
                "Pembuat Kuitansi / Nota Toko", "Kuitansi resmi • Hitung total otomatis", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Nama Toko / Usaha", 12f, C.MUTED))
                add(fShop)
                val rowMeta = activity.hbox().apply {
                    add(activity.vbox(0).apply {
                        add(activity.tv("Nomor Nota", 12f, C.MUTED))
                        add(fInvNo)
                    }, w = 0, weight = 1.2f)
                    add(activity.vbox(0).apply {
                        add(activity.tv("Tanggal", 12f, C.MUTED))
                        add(fDate)
                    }, w = 0, weight = 1f, side = 8)
                }
                add(rowMeta, top = 4)
                add(activity.tv("Nama Pembeli", 12f, C.MUTED), top = 4)
                add(fCustomer)

                add(activity.tv("Daftar Barang / Jasa", 13f, C.TEXT, true), top = 12)
                add(itemsContainer, top = 4)

                val btnAddRow = activity.btn("+ Tambah Barang", false, C.ACCENT) {
                    addItemRow("", "1", "0")
                }
                add(btnAddRow, top = 6)

                // Stempel Toko
                val stampRow = activity.hbox().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val stampIv = ImageView(activity).apply {
                        setImageResource(R.drawable.ic_sign)
                        setColorFilter(C.MUTED)
                        background = activity.shape(C.SURFACE2, 10)
                        setPadding(activity.dp(6), activity.dp(6), activity.dp(6), activity.dp(6))
                    }
                    val stampTv = activity.tv("Stempel / TTD Toko: Belum Dipilih", 12f, C.MUTED)
                    add(stampIv, w = activity.dp(36), h = activity.dp(36))
                    add(stampTv, w = 0, weight = 1f, side = 8)
                    add(activity.btn("Pilih Stempel", false, C.ACCENT) {
                        SignatureManager.showSheet(activity, "Pilih Stempel / TTD Toko", onPickImage = onPickImage) { bmp, _ ->
                            selectedStampBmp = bmp
                            stampIv.setImageBitmap(bmp)
                            stampIv.clearColorFilter()
                            stampTv.text = "Stempel Toko Siap Ditempel"
                        }
                    }, w = WRAP)
                }
                add(stampRow, top = 12)
            }

            pad.add(ScrollView(activity).apply { addView(formBox) }, h = activity.dp(300))

            val actionRow = activity.hbox().apply {
                add(activity.btn("Buat PDF Nota", true, C.PRIMARY, R.drawable.ic_pdf) {
                    val shop = fShop.text.toString().trim().ifBlank { "Toko Usaha" }
                    val inv = fInvNo.text.toString().trim().ifBlank { "INV-001" }
                    val date = fDate.text.toString().trim()
                    val cust = fCustomer.text.toString().trim()

                    val items = itemRows.mapNotNull { (fName, fQty, fPrc) ->
                        val n = fName.text.toString().trim()
                        val q = digits(fQty.text.toString())?.toInt() ?: 0
                        val p = digits(fPrc.text.toString()) ?: 0L
                        if (n.isBlank() || q <= 0) null else InvoiceItem(n, q, p)
                    }

                    if (items.isEmpty()) {
                        activity.toast("Tambahkan minimal 1 barang")
                        return@btn
                    }

                    dismiss()
                    generatePdf(activity, shop, inv, date, cust, items, selectedStampBmp)
                }, w = 0, weight = 1f)

                add(activity.btn("Buka di Excel", false, C.TEAL, R.drawable.ic_sheet) {
                    val shop = fShop.text.toString().trim()
                    val inv = fInvNo.text.toString().trim()
                    val date = fDate.text.toString().trim()
                    val cust = fCustomer.text.toString().trim()

                    val grid = ArrayList<List<String>>()
                    grid.add(listOf("KUITANSI / NOTA PEMBAYARAN", "", "", ""))
                    grid.add(listOf("Toko:", shop, "Nomor:", inv))
                    grid.add(listOf("Tanggal:", date, "Pelanggan:", cust))
                    grid.add(listOf("", "", "", ""))
                    grid.add(listOf("Nama Barang", "Qty", "Harga Satuan", "Total"))

                    var grandTotal = 0L
                    itemRows.forEach { (fName, fQty, fPrc) ->
                        val n = fName.text.toString().trim()
                        val q = digits(fQty.text.toString())?.toInt() ?: 0
                        val p = digits(fPrc.text.toString()) ?: 0L
                        // baris tanpa nama / qty 0 dilewati — SAMA dengan versi PDF (dulu tetap ikut grand total)
                        if (n.isNotBlank() && q > 0) {
                            val tot = q * p
                            grandTotal += tot
                            grid.add(listOf(n, q.toString(), p.toString(), tot.toString()))
                        }
                    }
                    grid.add(listOf("", "", "GRAND TOTAL:", grandTotal.toString()))

                    dismiss()
                    val outXlsx = Files.namedOut(activity, "Nota_$inv", "xlsx")
                    XlsxWriter.write(outXlsx, "Nota", grid)
                    val uri = Files.uriFor(activity, outXlsx)
                    Recent.add(activity, uri, outXlsx.name, XLSX)
                    activity.startActivity(Intent(activity, SheetEditorActivity::class.java).putExtra(SheetEditorActivity.EXTRA_URI, uri.toString()))
                }, w = 0, weight = 1f, side = 8)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun generatePdf(
        activity: AppCompatActivity,
        shop: String,
        invNo: String,
        date: String,
        cust: String,
        items: List<InvoiceItem>,
        stampBmp: Bitmap?
    ) {
        val out = Files.newOut(activity, "Nota_$invNo", "pdf")
        activity.work("Membuat PDF Kuitansi…", {
            val doc = PdfDocument()
            val pw = 595; val ph = 842 // A4
            var pageNo = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
            var c = page.canvas
            c.drawColor(Color.WHITE)

            val pText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); textSize = 14f }
            val pHead = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 22f; isFakeBoldText = true }
            val pSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF65625D.toInt(); textSize = 12f }
            val pLine = Paint().apply { color = 0xFFE4E2DD.toInt(); strokeWidth = 1.5f }

            var y = 40f
            c.drawText(shop, 40f, y, pHead)
            y += 24f
            c.drawText("KUITANSI / NOTA PEMBAYARAN", 40f, y, pSub)

            c.drawLine(40f, y + 12f, pw - 40f, y + 12f, pLine)
            y += 32f

            c.drawText("Nomor: $invNo", 40f, y, pText)
            c.drawText("Tanggal: $date", pw - 200f, y, pText)
            y += 20f
            c.drawText("Kepada: $cust", 40f, y, pText)
            y += 30f

            val bgHeader = Paint().apply { color = 0xFFE8EEFF.toInt() }
            val pTableHead = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 12f; isFakeBoldText = true }

            // nominal rata kanan di kolomnya: angka jutaan tidak lagi melewati tepi kanan kertas
            val pMoney = TextPaint(pText).apply { textAlign = Paint.Align.RIGHT }
            val pHeadR = TextPaint(pTableHead).apply { textAlign = Paint.Align.RIGHT }
            val xPrice = 455f
            val xTotal = pw - 48f
            fun tableHeader() {
                c.drawRect(40f, y, pw - 40f, y + 28f, bgHeader)
                c.drawText("Nama Barang / Jasa", 50f, y + 18f, pTableHead)
                c.drawText("Qty", 300f, y + 18f, pTableHead)
                c.drawText("Harga Satuan", xPrice, y + 18f, pHeadR)
                c.drawText("Total", xTotal, y + 18f, pHeadR)
                y += 36f
            }
            /** Halaman baru bila sisa ruang kurang dari [need] (dulu barang ke-25 dst. tergambar di luar kertas). */
            fun ensure(need: Float, header: Boolean) {
                if (y + need <= ph - 40f) return
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
                c = page.canvas
                c.drawColor(Color.WHITE)
                y = 50f
                c.drawText("$shop · $invNo (lanjutan)", 40f, y, pSub)
                y += 20f
                if (header) tableHeader()
            }
            tableHeader()

            val fmt = NumberFormat.getCurrencyInstance(Locale("id", "ID"))
            var grandTotal = 0L

            items.forEach { item ->
                ensure(22f, true)
                grandTotal += item.total
                c.drawText(TextUtils.ellipsize(item.name, pText, 240f, TextUtils.TruncateAt.END).toString(), 50f, y, pText)
                c.drawText(item.qty.toString(), 300f, y, pText)
                c.drawText(fmt.format(item.price), xPrice, y, pMoney)
                c.drawText(fmt.format(item.total), xTotal, y, pMoney)
                y += 22f
                c.drawLine(40f, y - 10f, pw - 40f, y - 10f, pLine)
            }

            // total + tanda tangan/stempel selalu utuh di satu halaman
            ensure(170f, false)
            y += 16f
            val pTotal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 16f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
            c.drawText("GRAND TOTAL: ${fmt.format(grandTotal)}", xTotal, y, pTotal)

            y += 60f
            c.drawText("Hormat Kami,", pw - 180f, y, pSub)
            if (stampBmp != null) {
                val dst = RectF(pw - 200f, y + 10f, pw - 80f, y + 90f)
                c.drawBitmap(stampBmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            }

            doc.finishPage(page)
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
        }) { res ->
            res.onSuccess {
                activity.toast("PDF Nota berhasil dibuat!")
                Files.open(activity, Files.uriFor(activity, out), PDF)
            }.onFailure { activity.fail(it) }
        }
    }
}
