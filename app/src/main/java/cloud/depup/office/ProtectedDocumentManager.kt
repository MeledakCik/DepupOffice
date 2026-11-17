package cloud.depup.office

import android.net.Uri
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.UUID

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
        var teacherPwEnabled = false

        val fUserPw = activity.field("Password Pembuka PDF", pin = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fUserPwConfirm = activity.field("Konfirmasi Password Pembuka", pin = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val fTeacherPw = activity.field("Password Edit Guru / Pemilik", pin = false).apply {
            setText("1234")
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

            val chkTeacherPw = CheckBox(activity).apply {
                text = "Set Password Edit Guru / Pemilik (Master Password)"
                setTextColor(C.TEXT)
                isChecked = true
            }

            val teacherPwContainer = activity.vbox(6).apply {
                add(activity.tv("Masukkan Password Edit Guru:", 12f, C.MUTED))
                add(fTeacherPw)
            }

            chkTeacherPw.setOnCheckedChangeListener { _, isChecked ->
                teacherPwEnabled = isChecked
                teacherPwContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
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

                    val teacherPw = if (teacherPwEnabled) fTeacherPw.text.toString().trim() else "1234"
                    if (teacherPwEnabled && teacherPw.length < 4) {
                        activity.toast("Password edit guru minimal 4 karakter")
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
            val originalBytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Tidak dapat membaca file PDF")

            val ownerPw = if (teacherPassword.isNotBlank()) teacherPassword else UUID.randomUUID().toString()
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
