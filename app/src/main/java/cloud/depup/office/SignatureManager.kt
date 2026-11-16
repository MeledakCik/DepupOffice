package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Pustaka Tanda Tangan & Stempel (Signature & Stamp Library).
 * Menyimpan, mengelola, dan menyediakan picker tanda tangan & stempel transparan yang siap dipakai.
 */
object SignatureManager {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "signatures").apply { mkdirs() }

    fun listFiles(ctx: Context): List<File> {
        val files = dir(ctx).listFiles()?.filter { it.extension.lowercase() == "png" } ?: emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    fun save(ctx: Context, name: String, bmp: Bitmap): File {
        val cleanName = name.trim().ifBlank { "TTD_${System.currentTimeMillis()}" }
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(dir(ctx), "$cleanName.png")
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    fun delete(file: File): Boolean {
        return try { file.delete() } catch (_: Throwable) { false }
    }

    fun load(file: File): Bitmap? {
        return try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Throwable) { null }
    }

    /**
     * Konversi foto stempel/kertas menjadi stempel transparan (menghapus latar putih/terang).
     */
    fun makeTransparentStamp(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            if (r > 215 && g > 215 && b > 215) {
                pixels[i] = Color.TRANSPARENT
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Tampilkan sheet Pustaka Tanda Tangan & Stempel.
     */
    fun showSheet(
        activity: AppCompatActivity,
        title: String = "Pustaka Tanda Tangan & Stempel",
        onPickImage: ((onImagePicked: (Uri) -> Unit) -> Unit)? = null,
        onSelect: (bmp: Bitmap, file: File?) -> Unit
    ) {
        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_sign, C.ACCENT, C.BLUE_BG,
                title, "Pilih dari pustaka tersimpan atau buat baru", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val container = activity.vbox(0)

            fun renderLibrary() {
                container.removeAllViews()
                val savedFiles = listFiles(activity)

                if (savedFiles.isNotEmpty()) {
                    container.add(activity.tv("Tersimpan (${savedFiles.size})", 13f, C.TEXT, true), top = 8)
                    val grid = activity.hbox().apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    val scroll = android.widget.HorizontalScrollView(activity).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(grid)
                    }

                    savedFiles.forEach { f ->
                        val bmp = load(f) ?: return@forEach
                        val card = FrameLayout(activity).apply {
                            background = activity.shape(C.SURFACE, 14, C.LINE, 1)
                            setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                dismiss()
                                onSelect(bmp, f)
                            }
                        }

                        val iv = ImageView(activity).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageBitmap(bmp)
                        }
                        card.addView(iv, FrameLayout.LayoutParams(activity.dp(120), activity.dp(80)))

                        val delBtn = ImageView(activity).apply {
                            setImageResource(R.drawable.ic_close)
                            setColorFilter(C.RED)
                            setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
                            background = activity.shape(C.SURFACE, 100)
                            setOnClickListener {
                                delete(f)
                                renderLibrary()
                            }
                        }
                        card.addView(
                            delBtn,
                            FrameLayout.LayoutParams(
                                activity.dp(24), activity.dp(24),
                                Gravity.TOP or Gravity.END
                            )
                        )

                        val itemBox = activity.vbox(0).apply {
                            add(card)
                            val displayName = f.nameWithoutExtension.take(14)
                            add(
                                activity.tv(displayName, 11f, C.MUTED).apply {
                                    gravity = Gravity.CENTER
                                }, top = 4, w = activity.dp(120)
                            )
                        }

                        grid.addView(
                            itemBox,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = activity.dp(12) }
                        )
                    }
                    container.add(scroll, top = 8)
                }

                container.add(activity.tv("Buat atau Impor Baru", 13f, C.TEXT, true), top = 16)

                val btnDraw = activity.btn("Gambar Tanda Tangan", true, C.ACCENT, R.drawable.ic_sign) {
                    showDrawDialog(activity) { newBmp, saveName ->
                        val file = if (!saveName.isNullOrBlank()) save(activity, saveName, newBmp) else null
                        dismiss()
                        onSelect(newBmp, file)
                    }
                }
                container.add(btnDraw, top = 8)

                if (onPickImage != null) {
                    val btnImport = activity.btn("Impor Foto / Stempel (Hapus Latar)", false, C.ACCENT, R.drawable.ic_image) {
                        onPickImage.invoke { uri ->
                            try {
                                val raw = Pdf.decode(activity, uri, 1200)
                                val stampBmp = makeTransparentStamp(raw)
                                showSaveStampDialog(activity, stampBmp) { newBmp, saveName ->
                                    val file = if (!saveName.isNullOrBlank()) save(activity, saveName, newBmp) else null
                                    dismiss()
                                    onSelect(newBmp, file)
                                }
                            } catch (_: Throwable) {
                                activity.toast("Gagal memuat foto stempel")
                            }
                        }
                    }
                    container.add(btnImport, top = 8)
                }
            }

            renderLibrary()
            pad.add(container)
            body.add(pad)
        }
    }

    private fun showDrawDialog(
        activity: AppCompatActivity,
        onDone: (bmp: Bitmap, saveName: String?) -> Unit
    ) {
        val sv = SignatureView(activity)
        val nameField = activity.field("Nama tanda tangan (mis: TTD Utama)")
        val chkSave = android.widget.CheckBox(activity).apply {
            text = "Simpan ke Pustaka untuk dipakai lagi"
            setTextColor(C.TEXT)
            isChecked = true
        }

        val box = activity.vbox(16).apply {
            setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16))
            add(activity.tv("Gambar Tanda Tangan Anda:", 14f, C.TEXT, true))
            add(sv, h = activity.dp(180))

            val colorRow = activity.hbox().apply {
                val c1 = View(activity).apply {
                    background = activity.shape(0xFF0B1F6B.toInt(), 100)
                    setOnClickListener { sv.color = 0xFF0B1F6B.toInt() }
                }
                val c2 = View(activity).apply {
                    background = activity.shape(Color.BLACK, 100)
                    setOnClickListener { sv.color = Color.BLACK }
                }
                val c3 = View(activity).apply {
                    background = activity.shape(Color.RED, 100)
                    setOnClickListener { sv.color = Color.RED }
                }
                add(c1, w = activity.dp(32), h = activity.dp(32))
                add(c2, w = activity.dp(32), h = activity.dp(32), side = 8)
                add(c3, w = activity.dp(32), h = activity.dp(32), side = 8)
                add(activity.btn("Ulangi", false, C.ACCENT) { sv.clear() }, side = 16)
            }
            add(colorRow)
            add(chkSave)
            add(nameField)
        }

        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Tanda Tangan Baru")
            .setView(box)
            .setPositiveButton("Gunakan") { _, _ ->
                val bmp = sv.toBitmap()
                if (bmp == null) {
                    activity.toast("Tanda tangan masih kosong")
                    return@setPositiveButton
                }
                val name = if (chkSave.isChecked) nameField.text.toString().trim().ifBlank { "TTD_${System.currentTimeMillis()}" } else null
                onDone(bmp, name)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSaveStampDialog(
        activity: AppCompatActivity,
        stampBmp: Bitmap,
        onDone: (bmp: Bitmap, saveName: String?) -> Unit
    ) {
        val iv = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(stampBmp)
            background = activity.shape(C.NAVY_PANEL, 12)
            setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
        }
        val nameField = activity.field("Nama stempel (mis: Stempel PT)")
        val chkSave = android.widget.CheckBox(activity).apply {
            text = "Simpan ke Pustaka untuk dipakai lagi"
            setTextColor(C.TEXT)
            isChecked = true
        }

        val box = activity.vbox(16).apply {
            setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16))
            add(activity.tv("Hasil Stempel Transparan:", 14f, C.TEXT, true))
            add(iv, h = activity.dp(180))
            add(chkSave)
            add(nameField)
        }

        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Konfirmasi Stempel Transparan")
            .setView(box)
            .setPositiveButton("Gunakan") { _, _ ->
                val name = if (chkSave.isChecked) nameField.text.toString().trim().ifBlank { "Stempel_${System.currentTimeMillis()}" } else null
                onDone(stampBmp, name)
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
