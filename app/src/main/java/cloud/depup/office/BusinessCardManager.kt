package cloud.depup.office

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

data class BusinessCardInfo(
    var name: String = "",
    var phone: String = "",
    var email: String = "",
    var company: String = "",
    var title: String = "",
    var website: String = "",
    var address: String = ""
)

/**
 * Pemindai Kartu Nama ke Kontak HP (Business Card to Contact / VCF).
 * Membaca teks kartu nama dengan OCR, mengekstrak Nama, HP, Email, Perusahaan & Alamat, serta menyimpan ke Kontak HP atau VCF.
 */
object BusinessCardManager {

    fun showDialog(activity: AppCompatActivity, onPickImage: (cb: (Uri) -> Unit) -> Unit) {
        onPickImage { uri ->
            processCard(activity, uri)
        }
    }

    private fun processCard(activity: AppCompatActivity, uri: Uri) {
        activity.work("Membaca kartu nama (OCR)…", {
            val bmp = Pdf.decode(activity, uri, 1600) ?: throw IllegalStateException("Gagal memuat foto kartu nama")
            try {
                val ocr = Ocr.run(bmp)
                parseCardText(ocr.text)
            } finally {
                bmp.recycle()
            }
        }) { res ->
            res.onSuccess { info ->
                showEditSheet(activity, uri, info)
            }.onFailure { activity.fail(it) }
        }
    }

    /**
     * Ekstraksi pintar fields dari teks kartu nama.
     */
    fun parseCardText(rawText: String): BusinessCardInfo {
        val info = BusinessCardInfo()
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        val phoneRegex = Regex("(?:\\+62|08|021|\\(?0[1-9]\\)?)[0-9\\s-]{7,15}")
        val webRegex = Regex("(?:https?://|www\\.)[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

        for (line in lines) {
            // Match Email
            if (info.email.isEmpty() && emailRegex.containsMatchIn(line)) {
                info.email = emailRegex.find(line)?.value.orEmpty()
                continue
            }
            // Match Phone
            if (info.phone.isEmpty() && phoneRegex.containsMatchIn(line)) {
                info.phone = phoneRegex.find(line)?.value.orEmpty().replace(" ", "").replace("-", "")
                continue
            }
            // Match Website
            if (info.website.isEmpty() && webRegex.containsMatchIn(line)) {
                info.website = webRegex.find(line)?.value.orEmpty()
                continue
            }
            // Match Company (PT, CV, Inc, Ltd, Corp)
            if (info.company.isEmpty() && (line.contains("PT.", ignoreCase = true) || line.contains("CV.", ignoreCase = true) || line.contains("Inc", ignoreCase = true) || line.contains("Ltd", ignoreCase = true))) {
                info.company = line
                continue
            }
            // Match Address
            if (info.address.isEmpty() && (line.contains("Jl.", ignoreCase = true) || line.contains("Jalan", ignoreCase = true) || line.contains("Gedung", ignoreCase = true) || line.contains("Street", ignoreCase = true))) {
                info.address = line
                continue
            }
        }

        // Tentukan Nama & Jabatan dari baris teratas yang tersisa
        val remaining = lines.filter {
            it != info.email && it != info.phone && it != info.website && it != info.company && it != info.address
        }

        if (remaining.isNotEmpty()) {
            info.name = remaining[0]
        }
        if (remaining.size > 1) {
            info.title = remaining[1]
        }

        return info
    }

    private fun showEditSheet(activity: AppCompatActivity, srcUri: Uri, info: BusinessCardInfo) {
        val fName = activity.field("Nama Lengkap").apply { setText(info.name) }
        val fPhone = activity.field("Nomor HP / Telepon", number = true).apply { setText(info.phone) }
        val fEmail = activity.field("Email").apply { setText(info.email) }
        val fCompany = activity.field("Perusahaan / Bisnis").apply { setText(info.company) }
        val fTitle = activity.field("Jabatan").apply { setText(info.title) }
        val fAddress = activity.field("Alamat").apply { setText(info.address) }

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_user, C.ACCENT, C.BLUE_BG,
                "Kartu Nama Terdeteksi", "Periksa & simpan ke Kontak HP atau VCF", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Nama Lengkap", 12f, C.MUTED))
                add(fName)
                add(activity.tv("Nomor HP / Telepon", 12f, C.MUTED), top = 4)
                add(fPhone)
                add(activity.tv("Email", 12f, C.MUTED), top = 4)
                add(fEmail)
                add(activity.tv("Perusahaan", 12f, C.MUTED), top = 4)
                add(fCompany)
                add(activity.tv("Jabatan", 12f, C.MUTED), top = 4)
                add(fTitle)
                add(activity.tv("Alamat", 12f, C.MUTED), top = 4)
                add(fAddress)
            }

            pad.add(ScrollView(activity).apply { addView(formBox) }, h = activity.dp(280))

            val actionRow = activity.hbox().apply {
                add(activity.btn("Tambah ke Kontak", true, C.PRIMARY, R.drawable.ic_user) {
                    dismiss()
                    saveToContactsIntent(
                        activity,
                        fName.text.toString().trim(),
                        fPhone.text.toString().trim(),
                        fEmail.text.toString().trim(),
                        fCompany.text.toString().trim(),
                        fTitle.text.toString().trim()
                    )
                }, w = 0, weight = 1f)

                add(activity.btn("Ekspor VCF", false, C.ACCENT, R.drawable.ic_share) {
                    dismiss()
                    val vcfFile = exportVcf(
                        activity,
                        fName.text.toString().trim(),
                        fPhone.text.toString().trim(),
                        fEmail.text.toString().trim(),
                        fCompany.text.toString().trim(),
                        fTitle.text.toString().trim()
                    )
                    activity.toast("File VCF disimpan ke Documents/DepUp Office/Gambar")
                    Files.share(activity, Files.uriFor(activity, vcfFile), "text/x-vcard", false)
                }, w = 0, weight = 1f, side = 8)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun saveToContactsIntent(
        ctx: Context,
        name: String,
        phone: String,
        email: String,
        company: String,
        title: String
    ) {
        try {
            val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                putExtra(ContactsContract.Intents.Insert.NAME, name)
                putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                putExtra(ContactsContract.Intents.Insert.EMAIL, email)
                putExtra(ContactsContract.Intents.Insert.COMPANY, company)
                putExtra(ContactsContract.Intents.Insert.JOB_TITLE, title)
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
            ctx.toast("Gagal membuka Kontak HP")
        }
    }

    private fun exportVcf(
        ctx: Context,
        name: String,
        phone: String,
        email: String,
        company: String,
        title: String
    ): File {
        val cleanName = name.ifBlank { "Kontak" }
        val vcfContent = """
            BEGIN:VCARD
            VERSION:3.0
            N:;$cleanName;;;
            FN:$cleanName
            ORG:$company
            TITLE:$title
            TEL;TYPE=CELL:$phone
            EMAIL;TYPE=INTERNET:$email
            END:VCARD
        """.trimIndent()

        val file = Files.namedOut(ctx, "Kontak_${cleanName.replace(" ", "_")}", "vcf")
        FileOutputStream(file).use { out ->
            out.write(vcfContent.toByteArray(Charsets.UTF_8))
        }
        return file
    }
}
