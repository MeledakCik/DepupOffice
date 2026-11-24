package cloud.depup.office

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Dialog kode akses Dokumen Terlindungi v3 (dipakai MainActivity, ProtectedDocumentManager, ViewerActivity). */
object AccessCodeUi {
    private fun px(a: Activity, dp: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), a.resources.displayMetrics).toInt()

    private fun column(a: Activity) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(a, 24), px(a, 12), px(a, 24), 0)
    }

    private fun text(a: Activity, s: String, sizeSp: Float = 14f) = TextView(a).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    /** Kolom kode akses yang sudah terisi kode acak; guru boleh menggantinya. */
    fun codeField(a: Activity, initial: String): EditText = EditText(a).apply {
        setText(initial)
        hint = "Kode akses (min. ${SecurePackage.MIN_CODE_LENGTH} karakter)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(SecurePackage.MAX_CODE_LENGTH + 16))
        typeface = android.graphics.Typeface.MONOSPACE
        isSingleLine = true
    }

    /** Salin ke papan klip sebagai data SENSITIF (tidak tampil di pratinjau papan klip Android 13+). */
    fun copySensitive(a: Activity, label: String, value: String) {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        } else {
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        cm.setPrimaryClip(clip)
        Toast.makeText(a, "Kode akses disalin", Toast.LENGTH_SHORT).show()
    }

    /** Tampilkan kode akses setelah dokumen dibuat. Kode tidak disimpan di dokumen; guru harus mencatatnya. */
    fun showCreatedCode(a: Activity, code: String, onDone: () -> Unit) {
        if (a.isFinishing || a.isDestroyed) return
        val box = column(a)
        box.addView(text(a, "Bagikan kode ini kepada pembaca (murid) lewat jalur terpisah dari berkasnya. " +
            "Kode tidak tersimpan di dalam dokumen dan tidak bisa dipulihkan bila hilang."))
        box.addView(TextView(a).apply {
            text = code
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setPadding(0, px(a, 16), 0, px(a, 8))
        })
        box.addView(text(a, "Dokumen bisa dibuka di DepUp Office Android maupun iPhone dengan kode ini.", 12f))
        AlertDialog.Builder(a)
            .setTitle("Kode Akses Dokumen")
            .setView(box)
            .setCancelable(false)
            .setNeutralButton("Salin") { _, _ -> copySensitive(a, "Kode akses DepUp", code); onDone() }
            .setPositiveButton("Selesai") { _, _ -> onDone() }
            .show()
    }

    /**
     * Minta kode akses saat membuka dokumen v3. [onCode] menerima kode & pilihan "ingat di perangkat ini";
     * [onCancel] bila pengguna membatalkan.
     */
    fun ask(a: Activity, wrong: Boolean, onCode: (String, Boolean) -> Unit, onCancel: () -> Unit) {
        if (a.isFinishing || a.isDestroyed) { onCancel(); return }
        val box = column(a)
        box.addView(text(a, if (wrong) "Kode akses salah. Periksa kembali kode dari pembuat dokumen."
            else "Dokumen ini dilindungi. Masukkan kode akses dari guru/pembuat dokumen."))
        val field = codeField(a, "").apply { hint = "Kode akses" }
        box.addView(field)
        val remember = CheckBox(a).apply { text = "Ingat kode di perangkat ini"; isChecked = true }
        box.addView(remember)
        AlertDialog.Builder(a)
            .setTitle("Dokumen Terlindungi")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Buka") { _, _ -> onCode(field.text.toString(), remember.isChecked) }
            .setNegativeButton("Batal") { _, _ -> onCancel() }
            .show()
    }
}
