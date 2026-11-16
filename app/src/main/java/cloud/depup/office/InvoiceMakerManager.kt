package cloud.depup.office

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
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
                        val q = fQty.text.toString().trim().toIntOrNull() ?: 0
                        val p = fPrc.text.toString().trim().toLongOrNull() ?: 0L
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
                        val q = fQty.text.toString().trim().toIntOrNull() ?: 0
                        val p = fPrc.text.toString().trim().toLongOrNull() ?: 0L
                        val tot = q * p
                        grandTotal += tot
                        if (n.isNotBlank()) grid.add(listOf(n, q.toString(), p.toString(), tot.toString()))
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
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            val c = page.canvas
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
            c.drawRect(40f, y, pw - 40f, y + 28f, bgHeader)
            val pTableHead = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 12f; isFakeBoldText = true }

            c.drawText("Nama Barang / Jasa", 50f, y + 18f, pTableHead)
            c.drawText("Qty", 320f, y + 18f, pTableHead)
            c.drawText("Harga Satuan", 380f, y + 18f, pTableHead)
            c.drawText("Total", 480f, y + 18f, pTableHead)
            y += 36f

            val fmt = NumberFormat.getCurrencyInstance(Locale("id", "ID"))
            var grandTotal = 0L

            items.forEach { item ->
                grandTotal += item.total
                c.drawText(item.name.take(30), 50f, y, pText)
                c.drawText(item.qty.toString(), 320f, y, pText)
                c.drawText(fmt.format(item.price), 380f, y, pText)
                c.drawText(fmt.format(item.total), 480f, y, pText)
                y += 22f
                c.drawLine(40f, y - 10f, pw - 40f, y - 10f, pLine)
            }

            y += 16f
            val pTotal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 16f; isFakeBoldText = true }
            c.drawText("GRAND TOTAL: ${fmt.format(grandTotal)}", pw - 280f, y, pTotal)

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
