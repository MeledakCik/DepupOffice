package cloud.depup.office

import android.net.Uri
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Pengelola Dokumen Terlindungi (Protected Document Manager).
 * Menggabungkan Password Pembuka PDF, Proteksi Anti-Kopi, Anti-Cetak, dan Sampul DepUp + HMAC dalam 1 dialog checkbox.
 */
object ProtectedDocumentManager {

    fun showDialog(activity: AppCompatActivity, onPickFile: (cb: (Uri) -> Unit) -> Unit) {
        onPickFile { uri ->
            showProtectionSheet(activity, uri)
        }
    }

    private fun showProtectionSheet(activity: AppCompatActivity, uri: Uri) {
        val name = Files.nameOf(activity, uri)
        val baseName = Files.baseName(name)

        var userPwEnabled = false

        val fUserPw = activity.field("Password Pembuka PDF", pin = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fUserPwConfirm = activity.field("Konfirmasi Password Pembuka", pin = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val fTeacherPw = activity.field("Password Edit Guru / Pemilik", pin = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fTeacherPwConfirm = activity.field("Konfirmasi Password Guru", pin = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_shield, C.PRIMARY, C.BLUE_BG,
                "Dokumen Terlindungi", "$name • Pengaturan Keamanan", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val chkCoverHmac = CheckBox(activity).apply {
                text = "Bungkus Sampul DepUp & Integritas HMAC"
                setTextColor(C.TEXT)
                isChecked = true
            }

            val chkUserPw = CheckBox(activity).apply {
                text = "Kunci dengan Password Pembuka (User Password)"
                setTextColor(C.TEXT)
                isChecked = false
            }

            val userPwContainer = activity.vbox(6).apply {
                add(activity.tv("Masukkan Password Pembuka:", 12f, C.MUTED))
                add(fUserPw)
                add(fUserPwConfirm, top = 4)
                visibility = View.GONE
            }

            chkUserPw.setOnCheckedChangeListener { _, isChecked ->
                userPwEnabled = isChecked
                userPwContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            }

            val chkAntiCopy = CheckBox(activity).apply {
                text = "Proteksi Anti-Kopi Teks & Gambar (Read-Only)"
                setTextColor(C.TEXT)
                isChecked = true
            }

            val chkAntiPrint = CheckBox(activity).apply {
                text = "Proteksi Anti-Cetak (Print Restriction)"
                setTextColor(C.TEXT)
                isChecked = true
            }

            val chkAntiEdit = CheckBox(activity).apply {
                text = "Proteksi Anti-Edit / Ubah Dokumen"
                setTextColor(C.TEXT)
                isChecked = true
            }

            // Password guru WAJIB: dipakai untuk membuka batasan PDF dan untuk SEMUA fitur bagikan/ekspor/cetak
            // dokumen terlindungi. Dulu bisa kosong → diam-diam memakai "1234" yang bisa ditebak siapa saja.
            val chkTeacherPw = CheckBox(activity).apply {
                text = "Password Edit Guru / Pemilik (wajib — diminta untuk bagikan, ekspor, cetak & sedot foto)"
                setTextColor(C.TEXT)
                isChecked = true
                isEnabled = false
            }

            val teacherPwContainer = activity.vbox(6).apply {
                add(activity.tv("Masukkan Password Edit Guru:", 12f, C.MUTED))
                add(fTeacherPw)
                add(fTeacherPwConfirm, top = 4)
            }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Pilih Tingkat Keamanan & Akses Dokumen:", 13f, C.TEXT, true))
                add(chkCoverHmac, top = 4)
                add(chkUserPw, top = 6)
                add(userPwContainer, top = 4)
                add(chkAntiCopy, top = 6)
                add(chkAntiPrint, top = 4)
                add(chkAntiEdit, top = 4)
                add(chkTeacherPw, top = 6)
                add(teacherPwContainer, top = 4)
            }

            pad.add(ScrollView(activity).apply { addView(formBox) }, h = activity.dp(300))

            val actionRow = activity.hbox().apply {
                add(activity.btn("Terapkan Proteksi Dokumen", true, C.PRIMARY, R.drawable.ic_shield) {
                    val userPw = if (userPwEnabled) fUserPw.text.toString().trim() else ""
                    val confirmPw = if (userPwEnabled) fUserPwConfirm.text.toString().trim() else ""

                    if (userPwEnabled) {
                        if (userPw.length < 4) {
                            activity.toast("Password pembuka minimal 4 karakter")
                            return@btn
                        }
                        if (userPw != confirmPw) {
                            activity.toast("Konfirmasi password pembuka tidak sama")
                            return@btn
                        }
                    }

                    val teacherPw = fTeacherPw.text.toString().trim()
                    if (teacherPw.length < 6) {
                        activity.toast("Password edit guru minimal 6 karakter")
                        return@btn
                    }
                    if (teacherPw != fTeacherPwConfirm.text.toString().trim()) {
                        activity.toast("Konfirmasi password guru tidak sama")
                        return@btn
                    }
                    if (teacherPw == "1234" || teacherPw == "123456" || teacherPw.toSet().size == 1) {
                        activity.toast("Password guru terlalu mudah ditebak")
                        return@btn
                    }
                    if (userPwEnabled && teacherPw == userPw) {
                        activity.toast("Password guru harus berbeda dari password pembuka")
                        return@btn
                    }

                    dismiss()
                    applyProtection(
                        activity,
                        uri,
                        baseName,
                        chkCoverHmac.isChecked,
                        userPw,
                        !chkAntiCopy.isChecked,
                        !chkAntiPrint.isChecked,
                        !chkAntiEdit.isChecked,
                        teacherPw
                    )
                }, w = 0, weight = 1f)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun applyProtection(
        activity: AppCompatActivity,
        uri: Uri,
        baseName: String,
        useCoverHmac: Boolean,
        userPassword: String,
        canCopy: Boolean,
        canPrint: Boolean,
        canModify: Boolean,
        teacherPassword: String
    ) {
        val outName = baseName + "_terlindungi"
        val out = Files.newOut(activity, outName, "pdf")

        activity.work("Menerapkan proteksi dokumen…", {
            // (dulu seluruh PDF dibaca ke RAM di sini tanpa dipakai — lockLossless membaca sendiri)
            require(teacherPassword.isNotBlank()) { "Password guru wajib diisi" }
            val ownerPw = teacherPassword
            val perms = PdfSave.Perms(print = canPrint, copy = canCopy, modify = canModify, annotate = canModify)

            // Step 1: Kunci Izin & Password Pembuka (AES-256)
            val tempFile = File(activity.cacheDir, "temp_prot_${System.currentTimeMillis()}.pdf")
            try {
                PdfTools.lockLossless(activity, uri, tempFile, userPassword, ownerPw, perms)
                val lockedBytes = tempFile.readBytes()

                // Step 2: Sampul DepUp & Integritas HMAC jika diaktifkan
                if (useCoverHmac) {
                    ProtectedDoc.protect(lockedBytes, ownerPw, out)
                } else {
                    tempFile.copyTo(out, overwrite = true)
                }
            } finally {
                tempFile.delete()
            }
            out
        }) { res ->
            res.onSuccess {
                activity.toast("Dokumen Terlindungi Berhasil Dibuat!")
                Files.open(activity, Files.uriFor(activity, out), PDF)
            }.onFailure { activity.fail(it) }
        }
    }
}
