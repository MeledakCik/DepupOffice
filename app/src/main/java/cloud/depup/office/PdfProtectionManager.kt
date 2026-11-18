package cloud.depup.office

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.UUID

/**
 * Proteksi Anti-Kopi & Anti-Cetak PDF (Read-Only Restrictions).
 * Mengunci izin Salin Teks & Cetak PDF tanpa memerlukan password pembuka saat dibaca.
 */
object PdfProtectionManager {

    fun showDialog(activity: AppCompatActivity, onPickFile: (cb: (Uri) -> Unit) -> Unit) {
        onPickFile { uri ->
            showRestrictionsSheet(activity, uri)
        }
    }

    private fun showRestrictionsSheet(activity: AppCompatActivity, uri: Uri) {
        val name = Files.nameOf(activity, uri)
        var canCopy = false
        var canPrint = false
        var canModify = false

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_shield, C.ACCENT, C.BLUE_BG,
                "Proteksi Anti-Kopi & Anti-Cetak PDF", name, dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("Dokumen dapat dibuka langsung tanpa password, tetapi hak akses dikunci:", 12f, C.MUTED))

                val chkCopy = android.widget.CheckBox(activity).apply {
                    text = "Izinkan Salin Teks / Gambar (Copy-Paste)"
                    setTextColor(C.TEXT)
                    isChecked = canCopy
                    setOnCheckedChangeListener { _, c -> canCopy = c }
                }

                val chkPrint = android.widget.CheckBox(activity).apply {
                    text = "Izinkan Cetak / Print Dokumen"
                    setTextColor(C.TEXT)
                    isChecked = canPrint
                    setOnCheckedChangeListener { _, c -> canPrint = c }
                }

                val chkModify = android.widget.CheckBox(activity).apply {
                    text = "Izinkan Ubah / Edit Dokumen"
                    setTextColor(C.TEXT)
                    isChecked = canModify
                    setOnCheckedChangeListener { _, c -> canModify = c }
                }

                add(chkCopy, top = 8)
                add(chkPrint, top = 4)
                add(chkModify, top = 4)
            }

            pad.add(formBox)

            val actionRow = activity.hbox().apply {
                add(activity.btn("Terapkan Proteksi Read-Only", true, C.PRIMARY, R.drawable.ic_shield) {
                    dismiss()
                    applyRestrictions(activity, uri, name, canCopy, canPrint, canModify)
                }, w = 0, weight = 1f)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun applyRestrictions(
        activity: AppCompatActivity,
        uri: Uri,
        name: String,
        canCopy: Boolean,
        canPrint: Boolean,
        canModify: Boolean
    ) {
        val outName = Files.baseName(name) + "_readonly"
        val out = Files.newOut(activity, outName, "pdf")

        activity.work("Menerapkan proteksi read-only PDF…", {
            val ownerPw = UUID.randomUUID().toString()
            val perms = PdfSave.Perms(print = canPrint, copy = canCopy, modify = canModify, annotate = canModify)
            PdfTools.lockLossless(activity, uri, out, "", ownerPw, perms)
            out
        }) { res ->
            res.onSuccess {
                // sebutkan HANYA batasan yang benar-benar dipilih (dulu selalu "Anti-Kopi & Anti-Cetak")
                val locked = listOfNotNull(if (!canCopy) "salin teks" else null, if (!canPrint) "cetak" else null,
                    if (!canModify) "ubah" else null)
                activity.toast(if (locked.isEmpty()) "PDF disalin tanpa batasan (semua izin dibiarkan)."
                    else "Terkunci: ${locked.joinToString(", ")}. Berkas asli tidak diubah.")
                Files.open(activity, Files.uriFor(activity, out), PDF)
            }.onFailure { activity.fail(it) }
        }
    }
}
