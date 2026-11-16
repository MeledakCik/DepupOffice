package cloud.depup.office

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
import android.view.Gravity
import android.widget.ImageView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.FileOutputStream

data class CatalogItem(
    val title: String,
    val priceOrSub: String,
    val description: String,
    val imageBmp: Bitmap?
)

/**
 * Pembuat Katalog Produk & Portofolio PDF (Catalog & Portfolio Maker).
 * Membuat katalog jualan atau portofolio pekerjaan berdesain modern dalam format PDF.
 */
object CatalogMakerManager {

    fun showDialog(activity: AppCompatActivity, onPickImage: ((cb: (Uri) -> Unit) -> Unit)? = null) {
        val fTitle = activity.field("Judul Katalog (mis: Katalog Produk Olshop 2025)").apply { setText("Katalog Produk Olshop") }
        val fContact = activity.field("Nama Toko & Kontak WA").apply { setText("Toko Usaha • WA: 08123456789") }

        val itemsContainer = activity.vbox(8)
        val catalogItems = ArrayList<CatalogItem>()

        fun renderItemsList() {
            itemsContainer.removeAllViews()
            catalogItems.forEachIndexed { idx, item ->
                val card = activity.hbox().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = activity.shape(C.SURFACE2, 12)
                    setPadding(activity.dp(10), activity.dp(8), activity.dp(8), activity.dp(8))
                }

                val iv = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (item.imageBmp != null) setImageBitmap(item.imageBmp)
                    else {
                        setImageResource(R.drawable.ic_image)
                        setColorFilter(C.MUTED)
                        setBackgroundColor(C.SURFACE)
                    }
                }
                card.add(iv, w = activity.dp(48), h = activity.dp(48))

                val col = activity.vbox(0)
                col.add(activity.tv("${idx + 1}. ${item.title}", 13f, C.TEXT, true))
                col.add(activity.tv("${item.priceOrSub} • ${item.description.take(20)}", 11f, C.MUTED))
                card.add(col, w = 0, weight = 1f, side = 8)

                val delBtn = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(C.RED)
                    setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
                    setOnClickListener {
                        catalogItems.removeAt(idx)
                        renderItemsList()
                    }
                }
                card.add(delBtn, w = activity.dp(24), h = activity.dp(24))

                itemsContainer.add(card)
            }
        }

        fun showAddItemDialog() {
            var selectedBmp: Bitmap? = null

            val fItemTitle = activity.field("Nama Produk / Judul")
            val fPrice = activity.field("Harga / Subjudul (mis: Rp 150.000)")
            val fDesc = activity.field("Deskripsi Singkat (mis: Bahan Katun, Size M/L/XL)")

            val box = activity.vbox(12).apply {
                setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16))
                add(activity.tv("Tambah Produk / Item Katalog", 15f, C.TEXT, true))

                val imgPreview = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.ic_image)
                    setColorFilter(C.MUTED)
                    background = activity.shape(C.SURFACE2, 12)
                    setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
                }

                val btnPickImg = activity.btn("Pilih Foto Produk", false, C.ACCENT, R.drawable.ic_image) {
                    if (onPickImage != null) {
                        onPickImage.invoke { uri ->
                            try {
                                val bmp = Pdf.decode(activity, uri, 1000)
                                if (bmp != null) {
                                    selectedBmp = bmp
                                    imgPreview.setImageBitmap(bmp)
                                    imgPreview.clearColorFilter()
                                }
                            } catch (_: Throwable) {
                                activity.toast("Gagal memuat foto")
                            }
                        }
                    } else {
                        activity.toast("Fitur galeri tidak tersedia")
                    }
                }

                val imgRow = activity.hbox().apply {
                    add(imgPreview, w = activity.dp(64), h = activity.dp(64))
                    add(btnPickImg, w = 0, weight = 1f, side = 10)
                }
                add(imgRow)
                add(fItemTitle)
                add(fPrice)
                add(fDesc)
            }

            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Tambah Item")
                .setView(box)
                .setPositiveButton("Tambahkan") { _, _ ->
                    val t = fItemTitle.text.toString().trim()
                    if (t.isBlank()) {
                        activity.toast("Nama produk wajib diisi")
                        return@setPositiveButton
                    }
                    val p = fPrice.text.toString().trim().ifBlank { "Rp -" }
                    val d = fDesc.text.toString().trim().ifBlank { "-" }
                    catalogItems.add(CatalogItem(t, p, d, selectedBmp))
                    renderItemsList()
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_doc, C.ACCENT, C.BLUE_BG,
                "Pembuat Katalog Produk / Portofolio", "Desain katalog PDF profesional untuk olshop/usaha", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Judul Katalog", 12f, C.MUTED))
                add(fTitle)
                add(activity.tv("Nama Toko & Kontak", 12f, C.MUTED), top = 4)
                add(fContact)

                add(activity.tv("Daftar Produk / Portofolio", 13f, C.TEXT, true), top = 12)
                add(itemsContainer, top = 4)

                val btnAddItem = activity.btn("+ Tambah Produk / Foto", false, C.ACCENT, R.drawable.ic_plus) {
                    showAddItemDialog()
                }
                add(btnAddItem, top = 6)
            }

            pad.add(ScrollView(activity).apply { addView(formBox) }, h = activity.dp(280))

            val actionRow = activity.hbox().apply {
                add(activity.btn("Buat PDF Katalog", true, C.PRIMARY, R.drawable.ic_pdf) {
                    val title = fTitle.text.toString().trim().ifBlank { "Katalog Produk" }
                    val contact = fContact.text.toString().trim().ifBlank { "Toko Usaha" }

                    if (catalogItems.isEmpty()) {
                        activity.toast("Tambahkan minimal 1 produk")
                        return@btn
                    }

                    dismiss()
                    generateCatalogPdf(activity, title, contact, catalogItems)
                }, w = 0, weight = 1f)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    @SuppressLint("ThreadConstraint")
    private fun generateCatalogPdf(
        activity: AppCompatActivity,
        title: String,
        contact: String,
        items: List<CatalogItem>
    ) {
        val outName = "Katalog_" + title.replace(" ", "_")
        val out = Files.newOut(activity, outName, "pdf")

        activity.work("Membuat PDF Katalog Produk…", {
            val doc = PdfDocument()
            val pw = 595; val ph = 842 // A4
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            val c = page.canvas
            c.drawColor(Color.WHITE)

            val pTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 22f; isFakeBoldText = true }
            val pSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF65625D.toInt(); textSize = 12f }
            val pItemTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); textSize = 13f; isFakeBoldText = true }
            val pItemPrice = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 12f; isFakeBoldText = true }
            val pItemDesc = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF65625D.toInt(); textSize = 10f }
            val pLine = Paint().apply { color = 0xFFE4E2DD.toInt(); strokeWidth = 1.5f }
            val pCardBg = Paint().apply { color = 0xFFF3F2EE.toInt() }

            var y = 40f
            c.drawText(title, 40f, y, pTitle)
            y += 20f
            c.drawText(contact, 40f, y, pSub)
            c.drawLine(40f, y + 12f, pw - 40f, y + 12f, pLine)
            y += 32f

            // Grid 2 Kolom
            val colW = (pw - 100f) / 2f
            val cardH = 140f

            items.take(8).forEachIndexed { idx, item ->
                val col = idx % 2
                val row = idx / 2
                val left = 40f + col * (colW + 20f)
                val top = y + row * (cardH + 16f)

                // Draw Card
                c.drawRoundRect(RectF(left, top, left + colW, top + cardH), 12f, 12f, pCardBg)

                // Draw Image
                if (item.imageBmp != null) {
                    val imgDst = RectF(left + 8f, top + 8f, left + 90f, top + cardH - 8f)
                    c.drawBitmap(item.imageBmp, null, imgDst, Paint(Paint.FILTER_BITMAP_FLAG))
                }

                val textLeft = if (item.imageBmp != null) left + 100f else left + 12f
                c.drawText(item.title.take(20), textLeft, top + 28f, pItemTitle)
                c.drawText(item.priceOrSub, textLeft, top + 48f, pItemPrice)

                val desc = item.description.take(45)
                c.drawText(desc, textLeft, top + 68f, pItemDesc)
            }

            doc.finishPage(page)
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
        }) { res ->
            res.onSuccess {
                activity.toast("PDF Katalog berhasil dibuat!")
                Files.open(activity, Files.uriFor(activity, out), PDF)
            }.onFailure { activity.fail(it) }
        }
    }
}
