package cloud.depup.office

import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var onOne: ((Uri) -> Unit)? = null
    private var onMany: ((List<Uri>) -> Unit)? = null
    private var onPic: ((Boolean) -> Unit)? = null
    private val scanned = mutableListOf<File>()

    private val pickOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { keep(u); onOne?.invoke(u) }
    }
    private val pickMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l ->
        if (l.isNotEmpty()) { l.forEach { keep(it) }; onMany?.invoke(l) }
    }
    private val takePic = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> onPic?.invoke(ok) }
    private val scanEdit = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra("out")
        val jpg = r.data?.getIntExtra("jpg", 0) ?: 0
        if (r.resultCode == RESULT_OK && path != null) {
            val f = File(path)
            val mime = if (f.name.endsWith(".docx")) DOCX else PDF
            showResult(if (mime == DOCX) "Dokumen Word dibuat" else "Scan selesai", f, mime)
        }
        else if (r.resultCode == RESULT_OK && jpg > 0) toast("$jpg gambar disimpan di Galeri › Pictures/Depup Office")
    }
    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val files = r.data?.getStringArrayListExtra("files")
        if (r.resultCode == RESULT_OK && files != null && files.isNotEmpty()) {
            scanEdit.launch(Intent(this, ScanEditActivity::class.java)
                .putStringArrayListExtra("files", files)
                .putExtra("mode", r.data?.getIntExtra("mode", 3) ?: 3))
        }
    }

    private fun keep(u: Uri) {
        try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
    }

    private fun pickFile(types: Array<String>, cb: (Uri) -> Unit) { onOne = cb; pickOne.launch(types) }
    private fun pickFiles(types: Array<String>, cb: (List<Uri>) -> Unit) { onMany = cb; pickMany.launch(types) }

    // ================= KERANGKA: bilah atas + konten + navigasi bawah =================
    private lateinit var content: FrameLayout
    private lateinit var barTitle: TextView
    private lateinit var barLogo: View
    private val navItems = mutableMapOf<Int, Triple<View, ImageView, TextView>>()
    private var tab = 0
    private var sv: ScrollView? = null
    private var renderedTab = -1
    private val scrollMemo = mutableMapOf<Int, Int>()
    private var query = ""
    private var recentBox: LinearLayout? = null
    private var docsMode = 0          // 0 terkini, 1 ditandai, 2 hasil olahan
    private var docsCat = "Semua"
    private var focusSearch = false
    private val tabNames = arrayOf("Beranda", "Dokumen", "Alat", "Saya")

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putInt("docsMode", docsMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: 0
        docsMode = savedInstanceState?.getInt("docsMode") ?: 0
        val mainView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        mainView.add(topBar())
        content = FrameLayout(this)
        mainView.add(content, w = MATCH, h = 0, weight = 1f)
        mainView.add(View(this).apply { setBackgroundColor(C.LINE) }, h = 1)
        mainView.add(bottomNav())

        val root = FrameLayout(this)
        root.addView(mainView, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        select(tab)
        freshlyBuilt = true

        if (savedInstanceState == null) {
            val splash = buildSplash()
            root.addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
            Theme.apply(this, if (C.dark) C.BG else C.PRIMARY)
            splash.postDelayed({
                splash.animate().alpha(0f).setDuration(220).withEndAction {
                    root.removeView(splash)
                    Theme.apply(this)
                }.start()
            }, 550L)
        } else Theme.apply(this)
    }

    /** Layar pembuka singkat (±0,5 detik). Animasi otomatis nonaktif bila "Hapus animasi" aktif di HP. */
    private fun buildSplash(): View {
        val splash = FrameLayout(this).apply {
            setBackgroundColor(if (C.dark) C.BG else C.PRIMARY)
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val fg = if (C.dark) C.TEXT else C.ON_PRIMARY
        val center = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_logo); alpha = 0f; scaleX = 0.85f; scaleY = 0.85f }
        center.add(logo, w = dp(88), h = dp(88))
        val name = tv("Depup Office", 28f, fg, true).apply { gravity = Gravity.CENTER; alpha = 0f }
        center.add(name, top = 18, w = WRAP)
        val tag = tv("Kantor saku · 100% offline", 15f, fg).apply { gravity = Gravity.CENTER; alpha = 0f }
        center.add(tag, top = 4, w = WRAP)
        splash.addView(center, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start()
        name.animate().alpha(1f).setStartDelay(80).setDuration(300).start()
        tag.animate().alpha(0.9f).setStartDelay(140).setDuration(300).start()
        return splash
    }

    /** Layar baru saja dibangun di onCreate -> onResume pertama tidak perlu membangun ulang semuanya. */
    private var freshlyBuilt = false

    override fun onResume() {
        super.onResume()
        if (freshlyBuilt) { freshlyBuilt = false; return }
        if (::content.isInitialized) render()
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BG)
            setPadding(dp(16), dp(6), dp(6), dp(6))
            minimumHeight = dp(60)
        }
        barLogo = ImageView(this).apply { setImageResource(R.drawable.ic_logo); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        bar.add(barLogo, w = dp(32), h = dp(32), side = 0)
        barTitle = headingTv("Depup Office", 22f)
        bar.add(barTitle, w = 0, weight = 1f, side = 10)
        bar.add(iconBtn(R.drawable.ic_search, "Cari dokumen", C.TEXT) { openSearch() }, w = dp(48), h = dp(48))
        return bar
    }

    private fun rupiah(value: Long): String =
        "Rp " + NumberFormat.getIntegerInstance(Locale("id", "ID")).format(value)

    private fun shortRupiah(value: Long): String = when {
        value >= 1_000_000 -> "${value / 1_000_000} jt"
        value >= 1_000 -> "${value / 1_000} rb"
        else -> value.toString()
    }

    private fun openEWallet(walletIndex: Int, phone: String, amountText: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("No HP Donasi", phone))

        val walletName = when (walletIndex) {
            0 -> "GoPay / Gojek"
            1 -> "DANA"
            else -> "ShopeePay"
        }
        val pkgs = when (walletIndex) {
            0 -> listOf("com.gojek.app")
            1 -> listOf("id.dana")
            else -> listOf("com.shopee.id")
        }

        toast("Nomor $phone disalin. Membuka $walletName…")

        var launched = false
        for (pkg in pkgs) {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                try {
                    startActivity(i)
                    launched = true
                    break
                } catch (_: Exception) {}
            }
        }
        if (!launched) {
            AlertDialog.Builder(this)
                .setTitle("Nomor Donasi Sudah Disalin")
                .setMessage(
                    "Nomor e-Wallet:\n$phone (a.n. MKA)\n\n" +
                        "Nominal donasi: $amountText\n\n" +
                        "Aplikasi $walletName belum terpasang. Pasang aplikasinya, lalu tempel nomor yang sudah disalin untuk mengirim donasi. Terima kasih!"
                )
                .setPositiveButton("Buka Play Store") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${pkgs.first()}")))
                    } catch (_: Exception) {
                        toast("Play Store tidak tersedia di perangkat ini")
                    }
                }
                .setNegativeButton("Tutup", null)
                .show()
        }
    }

    private fun showDonationDialog() {
        val saweriaUrl = "https://saweria.co/depupdonate"

        sheet { body, dismiss ->
            sheetHeader(
                body, R.drawable.ic_heart, C.NAVY, C.INDIGO_BG,
                "Dukung Depup Office", "Donasi sukarela via Saweria • Bebas iklan & 100% offline", dismiss
            )
            val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(20)) }

            // Kartu Hero Saweria
            val heroCard = vbox(16).apply {
                background = shape(C.WARN_C, 16)
            }
            val heroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            heroRow.add(iconBadge(R.drawable.ic_heart, C.ERROR, C.SURFACE, 100, 24), w = dp(48), h = dp(48))
            val heroCol = vbox()
            heroCol.add(tv("Donasi via Saweria", 13f, C.WARN_T))
            heroCol.add(tv("saweria.co/depupdonate", 19f, C.TEXT, true), top = 2)
            heroCol.add(tv("GoPay • DANA • OVO • QRIS • LinkAja • ShopeePay", 13f, C.WARN_T), top = 2)
            heroRow.add(heroCol, w = 0, weight = 1f, side = 12)
            heroCard.add(heroRow)
            heroCard.add(tv("Dukungan Anda membantu pengembangan fitur baru Depup Office tanpa iklan, tanpa langganan, dan tetap 100% offline di perangkat Anda.", 14f, C.TEXT), top = 10)
            pad.add(heroCard)

            // Tombol Utama Buka Saweria
            val saweriaBtn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = shape(C.ACCENT, 14)
                minimumHeight = dp(52)
                contentDescription = "Buka halaman Saweria"
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                isFocusable = true
                add(icon(R.drawable.ic_heart, C.ON_PRIMARY, 20), w = dp(20), h = dp(20))
                add(tv("Buka Saweria", 15f, C.ON_PRIMARY, true), w = WRAP, side = 6)
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(saweriaUrl)))
                    } catch (_: Exception) {
                        toast("Tidak dapat membuka browser")
                    }
                }
            }
            pad.add(saweriaBtn, top = 16)

            val secRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val copyBtn = btn("Salin Link Saweria", false, C.ACCENT, R.drawable.ic_spark) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Link Donasi Saweria", saweriaUrl))
                toast("Link $saweriaUrl berhasil disalin!")
            }
            val shareBtn = btn("Bagikan Link Donasi", false, C.ACCENT, R.drawable.ic_share) {
                val text = "Dukung pengembangan Depup Office 💙\n\nDonasi via Saweria (GoPay, DANA, OVO, QRIS, dll):\n$saweriaUrl\n\nAplikasi office & scanner PDF 100% offline & bebas iklan. Terima kasih!"
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Bagikan link donasi"))
            }
            secRow.add(copyBtn, w = 0, weight = 1f)
            secRow.add(shareBtn, w = 0, weight = 1f).also {
                (shareBtn.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            }
            pad.add(secRow, top = 10)

            pad.add(
                tv(
                    "Donasi bersifat sukarela dan tidak membatasi fitur aplikasi. Seluruh fitur Depup Office tetap dapat dipakai gratis 100% offline.",
                    11f, C.MUTED
                ).apply {
                    background = shape(C.SURFACE2, 10)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }, top = 12
            )

            body.add(pad)
        }
    }

    private fun showDonationQr(phone: String, walletName: String, amount: Long) {
        val content = "DONASI DEPUP OFFICE\n\n" +
            "Metode: $walletName\n" +
            "Nomor: $phone\n" +
            "Atas nama: MKA\n" +
            "Nominal: ${rupiah(amount)}\n\n" +
            "Terima kasih telah mendukung aplikasi offline ini!"
        val bmp = try {
            QrManager.generateQr(content, 640, 640)
        } catch (t: Throwable) {
            fail(t)
            return
        }

        sheet { body, dismiss ->
            sheetHeader(
                body, R.drawable.ic_spark, C.SKY, C.SKY_BG,
                "QR Info Donasi", "Pindai untuk menyalin detail donasi", dismiss
            )
            val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(20)) }

            val qrCard = FrameLayout(this).apply {
                background = raised(Color.WHITE, 16, 2.5f)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            qrCard.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(MATCH, dp(260)))
            pad.add(qrCard)

            pad.add(tv("$walletName • $phone • a.n. MKA", 13f, C.TEXT, true).apply { gravity = Gravity.CENTER }, top = 12)
            pad.add(tv("Nominal: ${rupiah(amount)}", 12f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 2)
            pad.add(
                tv("QR ini berisi informasi donasi, bukan QRIS pembayaran. Tunjukkan QR ke perangkat lain untuk menyalin detail donasi.", 11f, C.MUTED)
                    .apply {
                        gravity = Gravity.CENTER
                        background = shape(C.SURFACE2, 10)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    }, top = 12
            )

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.add(btn("Simpan ke Galeri", true, C.ACCENT, R.drawable.ic_download) {
                saveQrBitmap(bmp, "Donasi_Depup")
                toast("QR disimpan ke Galeri › Pictures/Depup Office")
            }, w = 0, weight = 1f)
            val shareQrBtn = btn("Bagikan", false, C.ACCENT, R.drawable.ic_share) {
                shareQrBitmap(bmp, content)
            }
            row.add(shareQrBtn, w = 0, weight = 1f)
            (shareQrBtn.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            pad.add(row, top = 14)

            body.add(pad)
        }
    }

    private fun saveQrBitmap(bmp: Bitmap, base: String) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_${System.currentTimeMillis()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Depup Office")
            }
            val dst = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("Gagal menyimpan QR")
            contentResolver.openOutputStream(dst)!!.use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun shareQrBitmap(bmp: Bitmap, caption: String) {
        try {
            val f = File(cacheDir, "qr_donasi.png")
            java.io.FileOutputStream(f).use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) }
            val uri = Files.uriFor(this, f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Bagikan QR donasi"))
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun openSearch() {
        focusSearch = true
        docsMode = if (docsMode == 2) 2 else 0
        select(1)
    }

    /** Navigasi bawah: 4 tujuan + tombol Scan di tengah. Label selalu tampil; status terpilih diumumkan TalkBack. */
    private fun bottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(4), dp(6), dp(4), dp(8))
        }
        fun item(idx: Int, res: Int, label: String): View {
            val ind = FrameLayout(this)
            val ic = icon(res, C.MUTED, 24)
            ind.addView(ic, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            val l = tv(label, 12f, C.MUTED, true).apply { gravity = Gravity.CENTER; maxLines = 1; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            navItems[idx] = Triple(ind, ic, l)
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = label
                background = rippleBg(16)
                minimumHeight = dp(56)
                setOnClickListener { select(idx) }
                add(ind, w = dp(60), h = dp(32))
                add(l, top = 4, w = WRAP)
            }
        }
        nav.add(item(0, R.drawable.ic_home, "Beranda"), w = 0, weight = 1f)
        nav.add(item(1, R.drawable.ic_folder, "Dokumen"), w = 0, weight = 1f)
        // Tombol Scan: aksi paling sering, menonjol di tengah.
        val scan = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Scan dokumen dengan kamera"
            background = rippleBg(16)
            minimumHeight = dp(56)
            setOnClickListener { startScan() }
            val fabBox = FrameLayout(this@MainActivity).apply { background = shape(C.PRIMARY, 16) }
            fabBox.addView(icon(R.drawable.ic_scan, C.ON_PRIMARY, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            add(fabBox, w = dp(56), h = dp(40))
            add(tv("Scan", 12f, C.PRIMARY, true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, top = 2, w = WRAP)
        }
        nav.add(scan, w = 0, weight = 1f)
        nav.add(item(2, R.drawable.ic_tools, "Alat"), w = 0, weight = 1f)
        nav.add(item(3, R.drawable.ic_user, "Saya"), w = 0, weight = 1f)
        return nav
    }

    private fun select(i: Int) {
        tab = i
        barTitle.text = if (i == 0) "Depup Office" else tabNames[i]
        barLogo.visibility = if (i == 0) View.VISIBLE else View.GONE
        navItems.forEach { (idx, v) ->
            val on = idx == i
            v.first.background = if (on) shape(C.PRIMARY_C, 100) else null
            v.second.setColorFilter(if (on) C.ON_PRIMARY_C else C.MUTED)
            v.third.setTextColor(if (on) C.TEXT else C.MUTED)
            v.third.typeface = if (on) Fonts.head(this) else Fonts.label(this)
            (v.first.parent as? View)?.isSelected = on
        }
        render()
    }

    private fun render() {
        sv?.let { scrollMemo[renderedTab] = it.scrollY }
        content.removeAllViews()
        recentBox = null
        val page = vbox().apply { setPadding(dp(16), dp(8), dp(16), dp(32)) }
        when (tab) {
            0 -> buildHome(page)
            1 -> buildDocs(page)
            2 -> buildTools(page)
            else -> buildMe(page)
        }
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(page)
        }
        sv = scroller
        renderedTab = tab
        content.addView(scroller)
        val y = scrollMemo[tab] ?: 0
        if (y > 0) scroller.post { scroller.scrollTo(0, y) }
    }

    private fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when { h < 11 -> "Selamat pagi"; h < 15 -> "Selamat siang"; h < 18 -> "Selamat sore"; else -> "Selamat malam" }
    }

    private fun heading(small: String?, title: String, sub: String? = null, parent: LinearLayout) {
        if (small != null) parent.add(tv(small, 14f, C.MUTED))
        parent.add(headingTv(title, 24f), top = 2)
        if (sub != null) parent.add(tv(sub, 15f, C.MUTED), top = 6)
    }

    /** Kumpulan baris dalam satu kartu dengan garis pemisah — lebih rapi daripada kartu bertumpuk. */
    private fun listCard(rows: List<View>): LinearLayout = vbox().apply {
        background = raised(C.SURFACE, 16, 1f)
        rows.forEachIndexed { i, r ->
            if (i > 0) add(View(context).apply { setBackgroundColor(C.LINE) }, h = 1, side = 0).also {
                (getChildAt(childCount - 1).layoutParams as LinearLayout.LayoutParams).marginStart = dp(72)
            }
            add(r)
        }
    }

    private fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Label, warna teks, warna latar lencana jenis berkas. */
    private fun typeOf(mime: String, name: String): Triple<String, Int, Int> {
        val n = name.lowercase()
        return when {
            mime == PDF || n.endsWith(".pdf") -> Triple("PDF", C.PDF_FG, C.PDF_BG)
            mime.contains("word") || n.endsWith(".doc") || n.endsWith(".docx") -> Triple("DOCX", C.DOC_FG, C.DOC_BG)
            mime.contains("sheet") || mime.contains("excel") || n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".csv") ->
                Triple("XLSX", C.XLS_FG, C.XLS_BG)
            mime.startsWith("image/") -> Triple("IMG", C.IMG_FG, C.IMG_BG)
            else -> Triple(n.substringAfterLast('.', "FILE").uppercase().take(4), C.NAVY, C.TINT20)
        }
    }


    private fun metaLine(e: List<String>): String {
        val size = Files.sizeOf(this, Uri.parse(e[0]))
        return listOf(if (size >= 0) Files.fmtSize(size) else "", Files.relTime(e[3].toLongOrNull() ?: 0L))
            .filter { it.isNotBlank() }.joinToString(" • ")
    }

    private fun showRenameFileDialog(uriStr: String, currentName: String) =
        renameFlow(Uri.parse(uriStr), currentName) { render() }

    /** Lencana jenis berkas (PDF/DOCX/XLSX/IMG): huruf tebal di wadah warna kategori. */
    private fun typeBadge(label: String, fg: Int, bg: Int): View =
        tv(label, 12f, fg, true).apply {
            gravity = Gravity.CENTER
            background = shape(bg, 12)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun fileRow(e: List<String>): View {
        val (label, fg, bg) = typeOf(e[2], e[1])
        val uriStr = e[0]
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(4), dp(10))
            minimumHeight = dp(68)
        }
        fun openIt() {
            try { Files.open(this, Uri.parse(uriStr), e[2]) } catch (_: Exception) { toast("File tidak lagi tersedia") }
        }
        val meta = metaLine(e)
        r.tappable("${e[1]}, ${readableType(label)}${if (meta.isBlank()) "" else ", $meta"}. Ketuk untuk membuka") { openIt() }
        r.add(typeBadge(label, fg, bg), w = dp(44), h = dp(44))
        val col = vbox().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        col.add(tv(e[1], 15f, C.TEXT, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        col.add(tv(if (meta.isBlank()) readableType(label) else meta, 13f, C.MUTED), top = 2)
        r.add(col, w = 0, weight = 1f, side = 12)
        val marked = Meta.isMarked(this, uriStr)
        lateinit var bm: ImageView
        bm = iconBtn(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off,
            if (marked) "Hapus tanda ${e[1]}" else "Tandai ${e[1]}", if (marked) C.PRIMARY else C.MUTED, 22) {
            val on = Meta.toggleMark(this, uriStr)
            bm.setImageResource(if (on) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off)
            bm.setColorFilter(if (on) C.PRIMARY else C.MUTED)
            bm.contentDescription = if (on) "Hapus tanda ${e[1]}" else "Tandai ${e[1]}"
            toast(if (on) "Ditandai" else "Tanda dihapus")
            if (tab == 1 && docsMode == 1 && !on) render()
        }
        r.add(bm, w = dp(48), h = dp(48))
        r.add(iconBtn(R.drawable.ic_more, "Opsi lain untuk ${e[1]}", C.MUTED, 22) { v ->
            val pm = android.widget.PopupMenu(this, v)
            pm.menu.add(0, 1, 0, "Buka")
            pm.menu.add(0, 2, 1, "Edit dokumen")
            pm.menu.add(0, 3, 2, "Ubah nama")
            pm.menu.add(0, 4, 3, "Bagikan")
            pm.menu.add(0, 5, 4, "Hapus dari riwayat")
            pm.setOnMenuItemClickListener { m ->
                when (m.itemId) {
                    1 -> openIt()
                    2 -> editDoc(uriStr, e[1], e[2])
                    3 -> showRenameFileDialog(uriStr, e[1])
                    4 -> try { Files.share(this, Uri.parse(uriStr), e[2], false) } catch (_: Exception) { toast("Tidak bisa dibagikan") }
                    else -> { Recent.remove(this, uriStr); render() }
                }
                true
            }
            pm.show()
        }, w = dp(48), h = dp(48))
        return r
    }

    private fun readableType(label: String) = when (label) {
        "PDF" -> "Dokumen PDF"; "DOCX" -> "Dokumen Word"; "XLSX" -> "Spreadsheet Excel"; "IMG" -> "Gambar"; else -> "Berkas $label"
    }

    private fun fillRecent(box: LinearLayout, max: Int, filter: String = "", markedOnly: Boolean = false) {
        box.removeAllViews()
        val all = Recent.load(this)
        val marks = Meta.marks(this)
        val list = all.filter { filter.isBlank() || it[1].contains(filter, ignoreCase = true) }
            .filter { !markedOnly || marks.contains(it[0]) }.take(max)
        if (list.isEmpty()) {
            box.add(when {
                markedOnly && filter.isBlank() -> emptyState(R.drawable.ic_bookmark_off, "Belum ada yang ditandai",
                    "Ketuk ikon penanda di samping berkas agar mudah ditemukan lagi.")
                all.isEmpty() -> emptyState(R.drawable.ic_folder, "Belum ada berkas",
                    "Berkas PDF, Word, dan Excel yang Anda buka akan muncul di sini.", "Buka Berkas") { openAny() }
                else -> emptyState(R.drawable.ic_search, "Tidak ditemukan", "Tidak ada berkas yang cocok dengan “$filter”.")
            })
        } else box.add(listCard(list.map { fileRow(it) }))
    }

    /** Buka editor yang sesuai. PDF/gambar/format lain dibuka di pratinjau, bukan editor kosong. */
    private fun editDoc(uriStr: String, name: String, mime: String) {
        val n = name.lowercase()
        when {
            n.endsWith(".docx") || n.endsWith(".doc") ->
                startActivity(Intent(this, DocEditorActivity::class.java).putExtra(DocEditorActivity.EXTRA_URI, uriStr))
            n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".csv") ->
                startActivity(Intent(this, SheetEditorActivity::class.java).putExtra(SheetEditorActivity.EXTRA_URI, uriStr))
            else -> {
                if (n.endsWith(".pdf") || mime.startsWith("image/") || mime.startsWith("text/")) {
                    Files.open(this, Uri.parse(uriStr), mime)
                } else {
                    toast("Format ini belum bisa diedit. Membuka pratinjau…")
                    Files.open(this, Uri.parse(uriStr), mime)
                }
            }
        }
    }

    private fun pillRow(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, x -> add(x, w = WRAP).also { if (i > 0) (x.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) } }
    }

    // ================= BERANDA =================
    private fun buildHome(p: LinearLayout) {
        p.add(tv(greeting(), 15f, C.MUTED), top = 4)
        p.add(headingTv("Mau kerjakan apa hari ini?", 24f), top = 2)

        // Kolom cari (membuka tab Dokumen) — ukuran sentuh penuh 52dp.
        val search = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), shape(C.SURFACE, 14, C.LINE, 1), null)
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(52)
            isClickable = true; isFocusable = true
            contentDescription = "Cari dokumen"
            setOnClickListener { openSearch() }
            add(icon(R.drawable.ic_search, C.MUTED, 20), w = dp(20), h = dp(20))
            add(tv("Cari dokumen…", 15f, C.MUTED), w = 0, weight = 1f, side = 12)
        }
        p.add(search, top = 16)

        // Kartu Scan: aksi utama
        val h = hero()
        val hr = hbox()
        val hl = vbox()
        hl.add(headingTv("Scan dokumen", 20f, C.ON_PRIMARY_C))
        hl.add(tv("Foto kertas jadi PDF rapi. Sudut otomatis diluruskan.", 14f, C.ON_PRIMARY_C), top = 4)
        hl.add(heroBtn("Mulai Scan", R.drawable.ic_camera) { startScan() }, top = 14, w = WRAP)
        hr.add(hl, w = 0, weight = 1f)
        hr.add(ScanFrameView(this), w = dp(92), h = dp(112), side = 6)
        h.add(hr)
        p.add(h, top = 16)

        // Buka berkas: 4 pintasan sejajar
        p.add(sectionTitle("Buka berkas"), top = 22)
        fun quick(res: Int, fg: Int, bg: Int, label: String, desc: String, f: () -> Unit): View = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            tappable(desc) { f() }
            background = rippleBg(14)
            add(iconBadge(res, fg, bg, 16, 24), w = dp(56), h = dp(56))
            add(tv(label, 13f, C.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1 }, top = 6, w = WRAP)
        }
        p.add(evenRow(listOf(
            quick(R.drawable.ic_pdf, C.PDF_FG, C.PDF_BG, "PDF", "Buka berkas PDF") { openPdf() },
            quick(R.drawable.ic_doc, C.DOC_FG, C.DOC_BG, "Word", "Buka dokumen Word") {
                pickFile(arrayOf(DOCX, "application/msword", "text/plain")) { u -> remember(u); Files.open(this, u, Files.mimeOf(this, u)) }
            },
            quick(R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG, "Excel", "Buka spreadsheet Excel") {
                pickFile(arrayOf(XLSX, "application/vnd.ms-excel", "text/csv")) { u -> remember(u); Files.open(this, u, Files.mimeOf(this, u)) }
            },
            quick(R.drawable.ic_folder, C.PRIMARY, C.PRIMARY_C, "Lainnya", "Buka berkas apa pun") { openAny() }
        ), 4), top = 8)

        // Buat baru
        p.add(sectionTitle("Buat baru"), top = 18)
        p.add(listCard(listOf(
            createRow(R.drawable.ic_doc, C.DOC_FG, C.DOC_BG, "Dokumen Word", "Halaman kosong, editor ala Word") {
                startActivity(Intent(this, DocEditorActivity::class.java).putExtra(DocEditorActivity.EXTRA_TEMPLATE, DocEditorActivity.TEMPLATE_BLANK))
            },
            createRow(R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG, "Spreadsheet", "Tabel & rumus seperti Excel") {
                startActivity(Intent(this, SheetEditorActivity::class.java).putExtra(SheetEditorActivity.EXTRA_TEMPLATE, SheetEditorActivity.TEMPLATE_BLANK))
            },
            createRow(R.drawable.ic_note, C.PRIMARY, C.PRIMARY_C, "Catatan jadi PDF", "Tulis teks, simpan sebagai PDF") { textFlow() },
            createRow(R.drawable.ic_sign, C.SUCCESS, C.SUCCESS_C, "Tanda tangan PDF", "Gambar tanda tangan, tempel ke PDF") {
                pickFile(arrayOf(PDF)) { signFlow(it) }
            }
        )), top = 8)

        // Template siap pakai (gulir mendatar)
        p.add(sectionTitle("Template siap pakai"), top = 18)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), dp(4), dp(6)) }
        fun tpl(res: Int, fg: Int, bg: Int, title: String, sub: String, f: () -> Unit) {
            val c = vbox(14).apply {
                background = rippleCard(16)
                tappable("Template $title. $sub") { f() }
            }
            c.add(iconBadge(res, fg, bg, 12, 22), w = dp(40), h = dp(40))
            c.add(tv(title, 15f, C.TEXT, true).apply { maxLines = 2 }, top = 10)
            c.add(tv(sub, 13f, C.MUTED).apply { maxLines = 2 }, top = 2)
            row.add(c, w = dp(170), side = 0).also { (c.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10) }
        }
        tpl(R.drawable.ic_doc, C.DOC_FG, C.DOC_BG, "Surat Lamaran", "Format resmi HRD") {
            startActivity(Intent(this, DocEditorActivity::class.java).putExtra(DocEditorActivity.EXTRA_TEMPLATE, DocEditorActivity.TEMPLATE_SURAT))
        }
        tpl(R.drawable.ic_user, C.DOC_FG, C.DOC_BG, "CV / Resume", "Riwayat hidup rapi") {
            startActivity(Intent(this, DocEditorActivity::class.java).putExtra(DocEditorActivity.EXTRA_TEMPLATE, DocEditorActivity.TEMPLATE_CV))
        }
        tpl(R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG, "Kuitansi / Invoice", "Nota & pembayaran") {
            startActivity(Intent(this, SheetEditorActivity::class.java).putExtra(SheetEditorActivity.EXTRA_TEMPLATE, SheetEditorActivity.TEMPLATE_INVOICE))
        }
        tpl(R.drawable.ic_sign, C.IMG_FG, C.IMG_BG, "TTD Transparan", "Foto TTD jadi PNG") { extractTransparentSignatureFlow() }
        p.add(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(row) }, top = 8)

        // Terakhir dibuka
        p.add(sectionTitle("Terakhir dibuka", if (Recent.load(this).size > 3) "Lihat semua" else null) { docsMode = 0; select(1) }, top = 14)
        val box = vbox()
        recentBox = box
        fillRecent(box, 3)
        p.add(box, top = 8)
    }

    private fun createRow(res: Int, fg: Int, bg: Int, title: String, sub: String, f: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            minimumHeight = dp(64)
            tappable("$title. $sub") { f() }
            add(iconBadge(res, fg, bg, 12, 22), w = dp(44), h = dp(44))
            val c = vbox().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            c.add(tv(title, 15f, C.TEXT, true))
            c.add(tv(sub, 13f, C.MUTED), top = 1)
            add(c, w = 0, weight = 1f, side = 12)
            add(icon(R.drawable.ic_chev, C.MUTED, 20), w = dp(20), h = dp(20))
        }

    // ================= DOKUMEN =================
    private fun buildDocs(p: LinearLayout) {
        val search = field("Cari nama berkas…").apply {
            isSingleLine = true
            setText(query)
            setSelection(text.length)
            contentDescription = "Cari dokumen"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelative(getDrawable(R.drawable.ic_search)!!.mutate().also { it.setTint(C.MUTED); it.setBounds(0, 0, dp(20), dp(20)) }, null, null, null)
        }
        p.add(search, top = 4)
        val listBox = vbox()
        fun refresh() {
            when (docsMode) {
                0 -> fillRecent(listBox, 60, query)
                1 -> fillRecent(listBox, 60, query, markedOnly = true)
                else -> fillOutputs(listBox)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            // Tunda sebentar agar daftar tidak dibangun ulang di setiap ketukan huruf (berat di HP kelas bawah).
            private val run = Runnable { refresh() }
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                listBox.removeCallbacks(run)
                listBox.postDelayed(run, if (Perf.low) 280L else 180L)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        p.add(segmented(listOf("Terkini", "Ditandai", "Hasil olahan"), docsMode) { docsMode = it; render() }, top = 12)
        if (docsMode == 2) {
            p.add(tv("Berkas yang dibuat aplikasi ini · folder sdcard/DepupOffice", 13f, C.MUTED), top = 10)
        }
        p.add(listBox, top = 12)
        refresh()
        if (focusSearch) {
            focusSearch = false
            search.post {
                search.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.showSoftInput(search, 0)
            }
        }
    }

    private fun openAny() = pickFile(arrayOf("*/*")) { u -> remember(u); Files.open(this, u, Files.mimeOf(this, u)) }

    // ================= TAB TOOLS =================
    private fun quickPick(u: Uri) {
        val mime = Files.mimeOf(this, u)
        if (mime.startsWith("image/")) {
            val out = Files.newOut(this, "gambar", "pdf")
            work("Membuat PDF…", { Pdf.imagesToPdf(this, listOf(u), out) }) { r ->
                r.onSuccess { showResult("PDF dibuat dari gambar", out, PDF) }.onFailure { fail(it) }
            }
            return
        }
        if (mime != PDF) { toast("Pilih berkas PDF atau gambar (JPG/PNG)"); return }
        val items = arrayOf("Pisah PDF", "Kompres PDF", "PDF ke JPG", "Tanda tangan")
        AlertDialog.Builder(this).setTitle("Olah berkas ini dengan…").setItems(items) { _, i ->
            when (i) { 0 -> splitFlow(u); 1 -> compressFlow(u); 2 -> pdfToJpgFlow(u); else -> signFlow(u) }
        }.setNegativeButton("Batal", null).show()
    }

    private fun buildTools(p: LinearLayout) {
        p.add(tv("Pilih alat, lalu pilih berkasnya. Semua diproses di HP Anda.", 15f, C.MUTED), top = 4)

        // Jalan pintas: pilih berkas dulu, alat menyesuaikan
        p.add(actionRow(R.drawable.ic_upload, "Pilih berkas dulu", "PDF atau foto — lalu pilih tindakannya", C.PRIMARY, C.PRIMARY_C, shadow = false) {
            pickFile(arrayOf(PDF, "image/*")) { quickPick(it) }
        }, top = 14)

        Meta.lastJob(this)?.let { (title, name, path) ->
            val c = card(14, 16, shadow = false)
            val r1 = hbox()
            r1.add(iconBadge(R.drawable.ic_check_ok, C.SUCCESS, C.SUCCESS_C, 100, 18), w = dp(32), h = dp(32))
            val col = vbox()
            col.add(tv("Terakhir: $title", 14f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            col.add(tv(name, 13f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, top = 1)
            r1.add(col, w = 0, weight = 1f, side = 10)
            r1.add(tv("Simpan ke Unduhan", 14f, C.PRIMARY, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(6), 0)
                tappable {
                    val f = File(path)
                    if (f.exists()) work("Menyimpan…", { Files.saveToDownloads(this@MainActivity, Files.uriFor(this@MainActivity, f), name, Files.byName(name)) }) { r ->
                        r.onSuccess { toast("Tersimpan di Unduhan/Depup Office") }.onFailure { fail(it) }
                    } else toast("Berkas sudah tidak ada")
                }
            }, w = WRAP)
            c.add(r1)
            p.add(c, top = 12)
        }

        fun section(title: String, vararg tiles: View) {
            p.add(sectionTitle(title), top = 22)
            tiles.toList().chunked(2).forEachIndexed { i, pair ->
                p.add(if (pair.size == 2) twoCols(pair[0], pair[1]) else twoCols(pair[0], View(this)), top = if (i == 0) 8 else 12)
            }
        }
        section("Kelola PDF",
            tile(R.drawable.ic_merge, C.PDF_FG, C.PDF_BG, "Gabung PDF", "Satukan beberapa PDF, urutan bebas") { mergeFlow() },
            tile(R.drawable.ic_cut, C.PDF_FG, C.PDF_BG, "Pisah PDF", "Ambil halaman tertentu") { pickFile(arrayOf(PDF)) { splitFlow(it) } },
            tile(R.drawable.ic_crop, C.PDF_FG, C.PDF_BG, "Susun halaman", "Urutkan, putar, atau hapus halaman") { pickFile(arrayOf(PDF)) { organizePdfFlow(it) } },
            tile(R.drawable.ic_compress, C.PDF_FG, C.PDF_BG, "Kompres PDF", "Target < 200 KB / 500 KB / 1 MB", "CPNS", C.PDF_FG, C.PDF_BG) { pickFile(arrayOf(PDF)) { compressFlow(it) } },
            tile(R.drawable.ic_image, C.PDF_FG, C.PDF_BG, "PDF ke JPG", "Tiap halaman jadi gambar") { pickFile(arrayOf(PDF)) { pdfToJpgFlow(it) } },
            tile(R.drawable.ic_img_add, C.PDF_FG, C.PDF_BG, "Ambil gambar", "Simpan foto di dalam PDF") { pickFile(arrayOf(PDF)) { extractImagesFlow(it) } }
        )
        section("Gambar & foto",
            tile(R.drawable.ic_pdf, C.IMG_FG, C.IMG_BG, "Foto ke PDF", "Gabung foto jadi satu PDF") { jpgToPdfFlow() },
            tile(R.drawable.ic_grid, C.IMG_FG, C.IMG_BG, "Konversi massal", "Banyak foto jadi PDF terpisah") { batchConvertFlow() },
            tile(R.drawable.ic_image, C.IMG_FG, C.IMG_BG, "Kompres foto", "Kecilkan sebelum kirim WA/email") { compressGalleryFlow() },
            tile(R.drawable.ic_sign, C.IMG_FG, C.IMG_BG, "TTD transparan", "Foto tanda tangan jadi PNG") { extractTransparentSignatureFlow() }
        )
        section("Tanda tangan & keamanan",
            tile(R.drawable.ic_sign, C.SUCCESS, C.SUCCESS_C, "Tanda tangan PDF", "Gambar lalu tempel ke halaman") { pickFile(arrayOf(PDF)) { signFlow(it) } },
            tile(R.drawable.ic_lock, C.PRIMARY, C.PRIMARY_C, "Kunci berkas", "Enkripsi ke Brankas AES-256") { pickFile(arrayOf("*/*")) { lockPasswordFlow(it) } },
            tile(R.drawable.ic_qr, C.PRIMARY, C.PRIMARY_C, "QR Code", "Pindai atau buat QR") { showQrDialog() },
            tile(R.drawable.ic_scan, C.XLS_FG, C.XLS_BG, "Scan struk", "Total belanja ke Excel") { startScan() }
        )
        section("Bagikan & ekspor",
            tile(R.drawable.ic_chat, C.SUCCESS, C.SUCCESS_C, "Kirim ke WhatsApp", "Pilih berkas lalu kirim") {
                pickFile(arrayOf("*/*")) { u -> Files.share(this, u, Files.mimeOf(this, u), true) }
            },
            tile(R.drawable.ic_share, C.PRIMARY, C.PRIMARY_C, "Bagikan", "Drive, email, aplikasi lain") {
                pickFile(arrayOf("*/*")) { u -> Files.share(this, u, Files.mimeOf(this, u), false) }
            },
            tile(R.drawable.ic_download, C.PRIMARY, C.PRIMARY_C, "Simpan ke HP", "Salin ke folder Unduhan") { exportFlow() },
            tile(R.drawable.ic_print, C.PRIMARY, C.PRIMARY_C, "Cetak", "Printer atau simpan PDF") {
                pickFile(arrayOf(PDF)) { u -> Files.print(this, u, Files.nameOf(this, u)) }
            }
        )
    }

    /** Baris pilihan pengaturan: tiap opsi 48dp, status terpilih diumumkan pembaca layar. */
    private fun settingsChips(
        row: LinearLayout,
        labels: List<String>,
        selectedIndex: Int,
        @Suppress("UNUSED_PARAMETER") color: Int,
        onPick: (Int) -> Unit
    ) {
        row.removeAllViews()
        row.addView(segmented(labels, selectedIndex, onPick), LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun showWordFormatSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_doc, C.DOC_FG, C.DOC_BG, "Pengaturan Format Word", "Konfigurasi standar dokumen .docx", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
        var size = DocSettings.pageSize(this)
        var margin = DocSettings.marginMode(this)
        var spacing = DocSettings.lineSpacing(this)
        var font = DocSettings.fontFamily(this)

        val sizes = listOf("A4", "Letter", "F4")
        val margins = listOf("Normal", "Sempit", "Lebar")
        val spacings = listOf(1.0f, 1.15f, 1.5f, 2.0f)
        val fonts = listOf("Sans", "Times")

        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val marginRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val spacingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun render() {
            settingsChips(sizeRow, sizes, sizes.indexOf(size), C.ACCENT) {
                size = sizes[it]; DocSettings.setPageSize(this, size); render()
            }
            settingsChips(marginRow, margins, margin, C.ACCENT) {
                margin = it; DocSettings.setMarginMode(this, margin); render()
            }
            settingsChips(spacingRow, spacings.map { "${it}x" }, spacings.indexOf(spacing), C.TEAL) {
                spacing = spacings[it]; DocSettings.setLineSpacing(this, spacing); render()
            }
            settingsChips(fontRow, fonts, font.coerceIn(0, fonts.lastIndex), C.INDIGO) {
                font = it; DocSettings.setFontFamily(this, font); render()
            }
        }

        pad.add(tv("Ukuran Kertas", 13f, C.TEXT, true))
        pad.add(sizeRow, top = 8)
        pad.add(tv("Batas Margin", 13f, C.TEXT, true), top = 14)
        pad.add(marginRow, top = 8)
        pad.add(tv("Spasi Baris", 13f, C.TEXT, true), top = 14)
        pad.add(spacingRow, top = 8)
        pad.add(tv("Jenis Huruf", 13f, C.TEXT, true), top = 14)
        pad.add(fontRow, top = 8)
        pad.add(btn("Buka Editor Word Dokumen") {
            dismiss()
            startActivity(Intent(this, DocEditorActivity::class.java)
                .putExtra(DocEditorActivity.EXTRA_TEMPLATE, DocEditorActivity.TEMPLATE_BLANK))
        }, top = 16)
        body.add(pad)
        render()
    }

    private fun showExcelFormatSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG, "Pengaturan Format Excel", "Konfigurasi standar spreadsheet", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
        var grid = SheetSettings.showGridlines(this)
        var currency = SheetSettings.currencySymbol(this)
        var autoSum = SheetSettings.autoSum(this)

        val gridOptions = listOf("Aktif", "Nonaktif")
        val currencies = listOf("Rp", "\$", "€")
        val autoOptions = listOf("Aktif", "Nonaktif")
        val gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val currencyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val autoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun render() {
            settingsChips(gridRow, gridOptions, if (grid) 0 else 1, C.TEAL) {
                grid = it == 0; SheetSettings.setShowGridlines(this, grid); render()
            }
            settingsChips(currencyRow, currencies, currencies.indexOf(currency).coerceAtLeast(0), C.ACCENT) {
                currency = currencies[it]; SheetSettings.setCurrencySymbol(this, currency); render()
            }
            settingsChips(autoRow, autoOptions, if (autoSum) 0 else 1, C.INDIGO) {
                autoSum = it == 0; SheetSettings.setAutoSum(this, autoSum); render()
            }
        }

        pad.add(tv("Garis Kisi (Gridlines)", 13f, C.TEXT, true))
        pad.add(gridRow, top = 8)
        pad.add(tv("Simbol Mata Uang", 13f, C.TEXT, true), top = 14)
        pad.add(currencyRow, top = 8)
        pad.add(tv("Jumlah Otomatis (AutoSum)", 13f, C.TEXT, true), top = 14)
        pad.add(autoRow, top = 8)
        pad.add(btn("Buka Editor Spreadsheet Excel") {
            dismiss()
            startActivity(Intent(this, SheetEditorActivity::class.java)
                .putExtra(SheetEditorActivity.EXTRA_TEMPLATE, SheetEditorActivity.TEMPLATE_BLANK))
        }, top = 16)
        body.add(pad)
        render()
    }

    private fun showPdfFormatSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_pdf, C.PDF_FG, C.PDF_BG, "Pengaturan Format PDF", "Konfigurasi standar pembaca PDF", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
        var zoom = PdfSettings.defaultZoom(this).coerceIn(1f, 2.5f)
        var theme = PdfSettings.readerTheme(this)
        var smooth = PdfSettings.smoothScroll(this)

        val summary = tv("", 12f, C.MUTED)
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val smoothRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun option(label: String, selected: Boolean, color: Int, onClick: () -> Unit): TextView =
            tv(label, 12f, if (selected) C.ON_PRIMARY else C.TEXT, true).apply {
                gravity = Gravity.CENTER
                background = shape(if (selected) color else C.SURFACE2, 10)
                setPadding(dp(6), dp(10), dp(6), dp(10))
                isClickable = true
                setOnClickListener { onClick() }
            }

        fun render() {
            val zooms = listOf(1.0f, 1.25f, 1.5f, 2.0f)
            zoomRow.removeAllViews()
            zooms.forEachIndexed { i, z ->
                val v = option("${(z * 100).toInt()}%", zoom == z, C.ACCENT) {
                    zoom = z
                    PdfSettings.setDefaultZoom(this, z)
                    render()
                }
                zoomRow.add(v, w = 0, h = dp(40), weight = 1f).also {
                    if (i > 0) (v.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
                }
            }

            val themes = listOf("Siang", "Malam", "Sepia", "Eye-Care")
            themeRow.removeAllViews()
            themes.forEachIndexed { i, label ->
                val v = option(label, theme == i, C.INDIGO) {
                    theme = i
                    PdfSettings.setReaderTheme(this, i)
                    render()
                }
                themeRow.add(v, w = 0, h = dp(40), weight = 1f).also {
                    if (i > 0) (v.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
                }
            }

            smoothRow.removeAllViews()
            listOf(true to "Halus", false to "Langsung").forEachIndexed { i, (value, label) ->
                val v = option(label, smooth == value, C.TEAL) {
                    smooth = value
                    PdfSettings.setSmoothScroll(this, value)
                    render()
                }
                smoothRow.add(v, w = 0, h = dp(40), weight = 1f).also {
                    if (i > 0) (v.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
                }
            }

            summary.text = "Zoom ${(zoom * 100).toInt()}% • Tema ${themes[theme.coerceIn(0, themes.lastIndex)]} • Gulir ${if (smooth) "Halus" else "Langsung"}"
        }

        pad.add(tv("Preset Zoom Reader", 13f, C.TEXT, true))
        pad.add(zoomRow, top = 8)
        pad.add(tv("Tema Tampilan", 13f, C.TEXT, true), top = 14)
        pad.add(themeRow, top = 8)
        pad.add(tv("Efek Gulir", 13f, C.TEXT, true), top = 14)
        pad.add(smoothRow, top = 8)
        pad.add(summary, top = 14)
        pad.add(btn("Buka Pembaca PDF") {
            dismiss()
            pickFile(arrayOf(PDF)) { u -> remember(u); Files.open(this, u, PDF) }
        }, top = 16)
        body.add(pad)
        render()
    }

    private fun lockPasswordFlow(u: Uri) {
        val name = Files.nameOf(this, u)
        if (!Vault.hasPin(this)) {
            AlertDialog.Builder(this)
                .setTitle("Brankas belum aktif")
                .setMessage("Buat PIN Brankas dulu. Berkas akan dienkripsi AES-256 dan hanya bisa dibuka dengan PIN atau sidik jari.")
                .setPositiveButton("Buat PIN") { _, _ -> startActivity(Intent(this, VaultActivity::class.java)) }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Kunci & Enkripsi Berkas")
            .setMessage("'$name' akan dienkripsi AES-256 dan disimpan di Brankas. Berkas asli tetap aman di tempatnya. Buka lewat tab Simpan → Brankas Vault.")
            .setPositiveButton("Enkripsi Sekarang") { _, _ ->
                work("Mengenkripsi…", {
                    contentResolver.openInputStream(u)?.use { Vault.encrypt(this, it, name) }
                        ?: throw IllegalStateException("Berkas tidak bisa dibaca")
                }) { r ->
                    r.onSuccess { toast("Berkas terkunci di Brankas") }.onFailure { fail(it) }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun outFiles(): List<File> =
        (Files.outDir(this).listFiles()?.filter { it.isFile } ?: emptyList()).sortedByDescending { it.lastModified() }

    private fun catOf(f: File) = typeOf(Files.byName(f.name), f.name).first

    /** Satu baris hasil olahan (buka dengan ketuk; menu ⋮ untuk edit, ubah nama, hapus, bagikan). */
    private fun outRow(f: File, onChanged: () -> Unit = { render() }): View {
        val mime = Files.byName(f.name)
        val (label, fg, bg) = typeOf(mime, f.name)
        val uri = Files.uriFor(this, f)
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(4), dp(10))
            minimumHeight = dp(68)
        }
        val meta = "${Files.fmtSize(f.length())} • ${Files.relTime(f.lastModified())}"
        r.tappable("${f.name}, ${readableType(label)}, $meta. Ketuk untuk membuka") { Files.open(this, uri, mime) }
        r.add(typeBadge(label, fg, bg), w = dp(44), h = dp(44))
        val col = vbox().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        col.add(tv(f.name, 15f, C.TEXT, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        col.add(tv(meta, 13f, C.MUTED), top = 2)
        r.add(col, w = 0, weight = 1f, side = 12)
        r.add(iconBtn(R.drawable.ic_more, "Opsi lain untuk ${f.name}", C.MUTED, 22) { v ->
            val pm = android.widget.PopupMenu(this, v)
            pm.menu.add(0, 1, 0, "Buka")
            pm.menu.add(0, 2, 1, "Edit dokumen")
            pm.menu.add(0, 3, 2, "Ubah nama")
            pm.menu.add(0, 5, 3, "Bagikan")
            pm.menu.add(0, 4, 4, "Hapus berkas")
            pm.setOnMenuItemClickListener { m ->
                when (m.itemId) {
                    1 -> Files.open(this, uri, mime)
                    2 -> editDoc(uri.toString(), f.name, mime)
                    3 -> showRenameFileDialog(uri.toString(), f.name)
                    4 -> confirm("Hapus berkas?", "“${f.name}” akan dihapus dari HP dan tidak bisa dikembalikan.", "Hapus") {
                        f.delete(); toast("Berkas dihapus"); onChanged()
                    }
                    else -> try { Files.share(this, uri, mime, false) } catch (_: Exception) { toast("Tidak bisa dibagikan") }
                }
                true
            }
            pm.show()
        }, w = dp(48), h = dp(48))
        return r
    }

    /** Daftar hasil olahan + chip kategori (dipakai tab Dokumen). */
    private fun fillOutputs(box: LinearLayout) {
        box.removeAllViews()
        val all = outFiles()
        val cats = listOf("Semua" to "", "PDF" to "PDF", "Word" to "DOCX", "Excel" to "XLSX", "Gambar" to "IMG")
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cats.forEach { (name, code) ->
            val n = if (code.isEmpty()) all.size else all.count { catOf(it) == code }
            chips.add(filterChip("$name ($n)", docsCat == name) { docsCat = name; fillOutputs(box) }, w = WRAP, side = 0)
                .also { (chips.getChildAt(chips.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        }
        box.add(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val code = cats.firstOrNull { it.first == docsCat }?.second.orEmpty()
        val list = all.filter { code.isEmpty() || catOf(it) == code }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        if (list.isEmpty()) box.add(emptyState(R.drawable.ic_box, "Belum ada hasil", "Hasil scan, konversi, dan dokumen yang Anda simpan akan muncul di sini."), top = 12)
        else box.add(listCard(list.take(80).map { f -> outRow(f) { fillOutputs(box) } }), top = 12)
    }

    private fun showOutputs(@Suppress("UNUSED_PARAMETER") list: List<File>) { docsMode = 2; select(1) }

    // ================= SAYA =================
    private fun buildMe(p: LinearLayout) {
        // Kepala
        val head = hbox()
        head.add(iconBadge(R.drawable.ic_logo, C.PRIMARY, C.PRIMARY_C, 16, 30).apply {
            (getChildAt(0) as ImageView).clearColorFilter()
        }, w = dp(56), h = dp(56))
        val hc = vbox()
        hc.add(headingTv("Depup Office", 20f))
        hc.add(iconPill(R.drawable.ic_verified, "100% offline · berkas tidak diunggah", C.SUCCESS, C.SUCCESS_C), top = 4, w = WRAP)
        head.add(hc, w = 0, weight = 1f, side = 12)
        p.add(head, top = 4)

        // Brankas
        val vaultFiles = Vault.list(this)
        p.add(sectionTitle("Keamanan"), top = 22)
        p.add(listCard(listOf(
            createRow(R.drawable.ic_lock, C.PRIMARY, C.PRIMARY_C, "Brankas pribadi",
                "${vaultFiles.size} berkas · terkunci PIN/sidik jari, AES-256") { startActivity(Intent(this, VaultActivity::class.java)) },
            createRow(R.drawable.ic_shield, C.SUCCESS, C.SUCCESS_C, "Privasi & tentang aplikasi", "Bagaimana data Anda dijaga") {
                startActivity(Intent(this, PrivacyPolicyActivity::class.java))
            }
        )), top = 8)

        // Penyimpanan
        val vaultSize = dirSize(Vault.dir(this))
        val outSize = dirSize(Files.outDir(this))
        val (free, total) = Files.deviceStorage()
        p.add(sectionTitle("Penyimpanan"), top = 22)
        val st = card(16, 16, shadow = false)
        val sh = hbox()
        sh.add(tv("Dipakai Depup Office", 15f, C.TEXT, true), w = 0, weight = 1f)
        sh.add(tv(Files.fmtSize(vaultSize + outSize), 15f, C.PRIMARY, true), w = WRAP)
        st.add(sh)
        st.add(stackedBar(listOf(outSize to C.PRIMARY, vaultSize to C.IMG_FG)), top = 12)
        fun legend(color: Int, label: String, v: String) = hbox().apply {
            add(View(context).apply { background = shape(color, 100) }, w = dp(10), h = dp(10))
            add(tv(label, 14f, C.TEXT), w = 0, weight = 1f, side = 8)
            add(tv(v, 14f, C.MUTED), w = WRAP)
            minimumHeight = dp(32)
        }
        st.add(legend(C.PRIMARY, "Hasil olahan (${outFiles().size} berkas)", Files.fmtSize(outSize)), top = 10)
        st.add(legend(C.IMG_FG, "Brankas", Files.fmtSize(vaultSize)))
        st.add(legend(C.LINE_STRONG, "Sisa ruang HP", "${Files.fmtGb(free)} dari ${Files.fmtGb(total)}"))
        st.add(btn("Lihat hasil olahan", false, C.ACCENT, R.drawable.ic_folder) { docsMode = 2; docsCat = "Semua"; select(1) }, top = 10)
        p.add(st, top = 8)

        // Tampilan & aksesibilitas
        p.add(sectionTitle("Tampilan"), top = 22)
        val tc = card(16, 16, shadow = false)
        tc.add(tv("Tema", 15f, C.TEXT, true))
        tc.add(tv("Mode gelap lebih nyaman di malam hari dan menghemat baterai.", 13f, C.MUTED), top = 2)
        tc.add(segmented(listOf("Ikuti HP", "Terang", "Gelap"), Theme.mode(this)) { m ->
            Theme.setMode(this, m)
            val wasDark = C.dark
            DepupApp.applyNightMode(m)
            if (Theme.isDark(this) == wasDark) render() else recreate()
        }, top = 12)
        tc.add(View(this).apply { setBackgroundColor(C.LINE) }, top = 14, h = 1)
        tc.add(createRow(R.drawable.ic_zoom_in, C.PRIMARY, C.PRIMARY_C, "Ukuran huruf & tampilan",
            "Aplikasi mengikuti ukuran huruf di pengaturan HP") {
            try { startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)) } catch (_: Exception) { toast("Pengaturan tidak tersedia") }
        }.apply { setPadding(0, dp(10), 0, 0) })
        p.add(tc, top = 8)

        // Format bawaan
        p.add(sectionTitle("Format bawaan"), top = 22)
        p.add(listCard(listOf(
            createRow(R.drawable.ic_doc, C.DOC_FG, C.DOC_BG, "Dokumen Word", "Ukuran kertas, margin, spasi, huruf") { showWordFormatSettingsSheet() },
            createRow(R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG, "Spreadsheet Excel", "Garis kisi, mata uang, jumlah otomatis") { showExcelFormatSettingsSheet() },
            createRow(R.drawable.ic_pdf, C.PDF_FG, C.PDF_BG, "Pembaca PDF", "Zoom awal, tema baca, gaya gulir") { showPdfFormatSettingsSheet() }
        )), top = 8)

        // Dukungan
        p.add(sectionTitle("Dukungan"), top = 22)
        p.add(listCard(listOf(
            createRow(R.drawable.ic_heart, C.ERROR, C.ERROR_C, "Dukung developer", "Donasi sukarela — semua fitur tetap gratis") { showDonationDialog() },
            createRow(R.drawable.ic_mail, C.PRIMARY, C.PRIMARY_C, "Kirim masukan", "depupdev@gmail.com") {
                try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:depupdev@gmail.com"))) }
                catch (_: Exception) { toast("Aplikasi email tidak tersedia") }
            }
        )), top = 8)

        // Riwayat berbagi
        val sh2 = Meta.shares(this).take(3)
        if (sh2.isNotEmpty()) {
            p.add(sectionTitle("Baru dibagikan"), top = 22)
            p.add(listCard(sh2.map { e ->
                val wapp = e[1] == "WhatsApp"
                hbox().apply {
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    minimumHeight = dp(60)
                    add(iconBadge(if (wapp) R.drawable.ic_chat else R.drawable.ic_upload,
                        if (wapp) C.SUCCESS else C.PRIMARY, if (wapp) C.SUCCESS_C else C.PRIMARY_C, 12, 20), w = dp(44), h = dp(44))
                    val col = vbox()
                    col.add(tv(e[0], 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                    val sz = e[2].toLongOrNull() ?: -1L
                    col.add(tv(listOf(e[1], if (sz >= 0) Files.fmtSize(sz) else "", Files.relTime(e[3].toLongOrNull() ?: 0L))
                        .filter { it.isNotBlank() }.joinToString(" • "), 13f, C.MUTED), top = 1)
                    add(col, w = 0, weight = 1f, side = 12)
                    isFocusable = true
                }
            }), top = 8)
        }

        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null }
        p.add(tv("Depup Office${ver?.let { " versi $it" } ?: ""}", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 24)
    }

    // ================= BACA =================
    private fun remember(u: Uri) {
        val n = Files.nameOf(this, u)
        Recent.add(this, u, n, Files.mimeOf(this, u))
    }

    private fun openPdf() = pickFile(arrayOf(PDF)) { u -> remember(u); Files.open(this, u, PDF) }

    private fun openOther(types: Array<String>, fallback: String) = pickFile(types) { u ->
        remember(u)
        val m = Files.mimeOf(this, u)
        Files.open(this, u, if (m == "application/octet-stream") fallback else m)
    }

    private fun showRecent() {
        val list = Recent.load(this)
        if (list.isEmpty()) { toast("Belum ada file terakhir"); return }
        AlertDialog.Builder(this).setTitle("Berkas Terakhir Dibuka")
            .setItems(list.map { it[1] }.toTypedArray()) { _, i ->
                try { Files.open(this, Uri.parse(list[i][0]), list[i][2]) }
                catch (e: Exception) { toast("File tidak lagi tersedia") }
            }.setNegativeButton("Tutup", null).show()
    }

    // ================= TOOLS =================
    private fun mergeFlow() = pickFiles(arrayOf(PDF)) { picked -> orderDialog(picked.toMutableList()) }

    private fun miniBtn(label: String, onClick: () -> Unit) = tv(label, 16f, C.ACCENT_SOFT, true).apply {
        gravity = Gravity.CENTER
        background = raised(C.SURFACE, 10, 2f)
        setOnClickListener { onClick() }
    }

    /** Atur urutan manual: naik/turun/hapus, hasil digabung dari atas ke bawah. */
    private fun orderDialog(list: MutableList<Uri>) {
        val box = vbox(16)
        box.add(tv("Atur urutan dengan ↑ ↓. PDF digabung dari atas ke bawah.", 12f, C.MUTED))
        val rows = vbox()
        box.add(rows, top = 8)
        fun rebuild() {
            rows.removeAllViews()
            list.forEachIndexed { i, u ->
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(6), dp(8))
                    background = shape(C.SURFACE2, 12)
                }
                r.add(tv("${i + 1}. ${Files.nameOf(this, u)}", 13f).apply { maxLines = 2 }, w = 0, weight = 1f)
                r.add(miniBtn("↑") { if (i > 0) { list.add(i - 1, list.removeAt(i)); rebuild() } }, w = dp(36), h = dp(36), side = 2)
                r.add(miniBtn("↓") { if (i < list.size - 1) { list.add(i + 1, list.removeAt(i)); rebuild() } }, w = dp(36), h = dp(36), side = 2)
                r.add(miniBtn("✕") { list.removeAt(i); rebuild() }, w = dp(36), h = dp(36), side = 2)
                rows.add(r, top = 6)
            }
        }
        rebuild()
        val d = AlertDialog.Builder(this).setTitle("Urutan penggabungan")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Gabung", null).setNegativeButton("Batal", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (list.size < 2) { toast("Minimal 2 PDF"); return@setOnClickListener }
            d.dismiss()
            val files = list.toList()
            val out = Files.newOut(this, "gabung", "pdf")
            work("Menggabungkan…", { Pdf.merge(this, files, out) }) { r ->
                r.onSuccess { showResult("PDF digabung", out, PDF) }.onFailure { fail(it) }
            }
        }
    }

    private fun splitFlow(uri: Uri) {
        val pages = try { Pdf.pageCount(this, uri) } catch (t: Throwable) { fail(t); return }
        val name = Files.nameOf(this, uri)
        val thumb = Pdf.thumbnail(this, uri, 0, 360)

        val box = vbox(16).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (thumb != null) {
            val iv = ImageView(this).apply {
                setImageBitmap(thumb)
                adjustViewBounds = true
                background = paperThumbBg(8)
            }
            topRow.add(iv, w = dp(80), h = dp(100))
        }
        val infoCol = vbox()
        infoCol.add(tv(name, 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        infoCol.add(tv("Total Halaman: $pages hal", 13f, C.ACCENT, true), top = 4)
        infoCol.add(tv("Pilih rentang halaman yang ingin dipisah:", 12f, C.MUTED), top = 4)
        topRow.add(infoCol, w = 0, weight = 1f, side = 10)
        box.add(topRow)

        val a = field("Dari halaman", number = true).apply { setText("1") }
        val b = field("Sampai halaman", number = true).apply { setText(pages.toString()) }
        box.add(a, top = 12)
        box.add(b, top = 8)

        AlertDialog.Builder(this).setTitle("Pratinjau & Pisah PDF").setView(box)
            .setPositiveButton("Pisahkan Halaman") { _, _ ->
                val f = a.text.toString().toIntOrNull()
                val t = b.text.toString().toIntOrNull()
                if (f == null || t == null || f < 1 || t > pages || f > t) {
                    toast("Rentang halaman tidak valid"); return@setPositiveButton
                }
                val out = Files.newOut(this, "pisah_${f}-$t", "pdf")
                work("Memisahkan halaman…", { Pdf.split(this, uri, f, t, out) }) { r ->
                    r.onSuccess { showResult("PDF dipisah", out, PDF) }.onFailure { fail(it) }
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun compressFlow(uri: Uri) {
        val name = Files.nameOf(this, uri)
        val before = try { contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize } } catch (_: Exception) { 0L }
        val thumb = Pdf.thumbnail(this, uri, 0, 360)

        val box = vbox(16).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (thumb != null) {
            val iv = ImageView(this).apply {
                setImageBitmap(thumb)
                adjustViewBounds = true
                background = paperThumbBg(8)
            }
            topRow.add(iv, w = dp(80), h = dp(100))
        }
        val infoCol = vbox()
        infoCol.add(tv(name, 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        if (before > 0) infoCol.add(tv("Ukuran Saat Ini: ${Files.fmtSize(before)}", 13f, C.ACCENT, true), top = 4)
        infoCol.add(tv("Pilih tingkat kompresi:", 12f, C.MUTED), top = 4)
        topRow.add(infoCol, w = 0, weight = 1f, side = 10)
        box.add(topRow)

        var selectedMode = 0 // 0 = Target Size, 1 = Level
        var targetKb = 500   // 200, 500, 1000, 2000
        var selectedLevel = 1

        val targetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val targets = listOf("< 200 KB", "< 500 KB", "< 1 MB", "< 2 MB")
        val targetVals = listOf(200, 500, 1000, 2000)

        val levelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val levels = listOf("Ringan (~15%)", "Sedang (~35%)", "Tinggi (~60%)")

        fun renderUi() {
            targetRow.removeAllViews()
            targets.forEachIndexed { idx, tName ->
                val isSel = selectedMode == 0 && targetKb == targetVals[idx]
                val chip = tv(tName, 11f, if (isSel) C.ON_PRIMARY else C.TEXT, true).apply {
                    background = shape(if (isSel) C.ACCENT else C.SURFACE2, 8)
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener {
                        selectedMode = 0
                        targetKb = targetVals[idx]
                        renderUi()
                    }
                }
                targetRow.add(chip, w = 0, weight = 1f).also {
                    if (idx > 0) (chip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
                }
            }

            levelRow.removeAllViews()
            levels.forEachIndexed { idx, lvlName ->
                val isSel = selectedMode == 1 && idx == selectedLevel
                val chip = tv(lvlName, 11f, if (isSel) C.ON_PRIMARY else C.TEXT, true).apply {
                    background = shape(if (isSel) C.TEAL else C.SURFACE2, 8)
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener {
                        selectedMode = 1
                        selectedLevel = idx
                        renderUi()
                    }
                }
                levelRow.add(chip, w = 0, weight = 1f).also {
                    if (idx > 0) (chip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
                }
            }
        }

        box.add(tv("1. Target Ukuran Presisi (SSCASN / CPNS / BUMN):", 13f, C.TEXT, true), top = 12)
        box.add(targetRow, top = 6)
        box.add(tv("2. Atau Pilih Tingkat Kompresi Persentase:", 13f, C.TEXT, true), top = 12)
        box.add(levelRow, top = 6)
        renderUi()

        AlertDialog.Builder(this).setTitle("Pratinjau & Kompres PDF").setView(box)
            .setPositiveButton("Kompres Sekarang") { _, _ ->
                val out = Files.newOut(this, "kompres", "pdf")
                val action = {
                    if (selectedMode == 0) Pdf.compressToTarget(this, uri, targetKb, out)
                    else Pdf.compress(this, uri, selectedLevel, out)
                }
                work("Mengompres PDF…", action) { r ->
                    r.onSuccess {
                        var extra = ""
                        if (before > 0) {
                            extra = "\nSebelum: ${Files.fmtSize(before)} → Sesudah: ${Files.fmtSize(out.length())}"
                            if (out.length() >= before) extra += "\nHasil tidak lebih kecil. PDF ini sudah efisien."
                        }
                        showResult("PDF dikompres", out, PDF, extra)
                    }.onFailure { fail(it) }
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun pdfToJpgFlow(uri: Uri) {
        val name = Files.nameOf(this, uri)
        val pages = try { Pdf.pageCount(this, uri) } catch (t: Throwable) { 1 }
        val thumb = Pdf.thumbnail(this, uri, 0, 360)

        val box = vbox(16).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (thumb != null) {
            val iv = ImageView(this).apply {
                setImageBitmap(thumb)
                adjustViewBounds = true
                background = paperThumbBg(8)
            }
            topRow.add(iv, w = dp(80), h = dp(100))
        }
        val infoCol = vbox()
        infoCol.add(tv(name, 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        infoCol.add(tv("Total: $pages Halaman Siap Ekstrak", 13f, C.ACCENT, true), top = 4)
        infoCol.add(tv("Setiap halaman akan diekstrak menjadi gambar JPG ke Galeri HP.", 12f, C.MUTED), top = 4)
        topRow.add(infoCol, w = 0, weight = 1f, side = 10)
        box.add(topRow)

        AlertDialog.Builder(this).setTitle("Pratinjau & Ekstrak JPG").setView(box)
            .setPositiveButton("Ekstrak ke Galeri") { _, _ ->
                val base = Files.baseName(name)
                work("Mengubah ke JPG…", { Pdf.toJpg(this, uri, base) }) { r ->
                    r.onSuccess { n ->
                        AlertDialog.Builder(this).setTitle("Selesai")
                            .setMessage("$n gambar disimpan di Galeri › Pictures/Depup Office")
                            .setPositiveButton("OK", null).show()
                    }.onFailure { fail(it) }
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun organizePdfFlow(uri: Uri) {
        val total = try { Pdf.pageCount(this, uri) } catch (t: Throwable) { fail(t); return }
        val name = Files.nameOf(this, uri)
        val pagesOrder = (0 until total).toMutableList()
        val rotations = mutableMapOf<Int, Int>()

        val box = vbox(12).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.add(tv("Kelola & Susun Halaman: $name ($total hal)", 14f, C.TEXT, true))
        box.add(tv("Atur urutan dengan ↑ ↓, putar 90° dengan ↻, atau hapus halaman dengan ✕.", 12f, C.MUTED))

        val listContainer = vbox(8)
        box.add(ScrollView(this).apply { addView(listContainer) }, h = dp(220))

        fun renderList() {
            listContainer.removeAllViews()
            pagesOrder.forEachIndexed { idx, origIdx ->
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = shape(C.SURFACE2, 10)
                    setPadding(dp(10), dp(8), dp(6), dp(8))
                }
                val rot = rotations[origIdx] ?: 0
                r.add(tv("${idx + 1}. Halaman ${origIdx + 1}" + if (rot > 0) " ($rot°)" else "", 13f, C.TEXT, true), w = 0, weight = 1f)
                r.add(miniBtn("↑") { if (idx > 0) { pagesOrder.add(idx - 1, pagesOrder.removeAt(idx)); renderList() } }, w = dp(32), h = dp(32), side = 2)
                r.add(miniBtn("↓") { if (idx < pagesOrder.size - 1) { pagesOrder.add(idx + 1, pagesOrder.removeAt(idx)); renderList() } }, w = dp(32), h = dp(32), side = 2)
                r.add(miniBtn("↻") { rotations[origIdx] = ((rotations[origIdx] ?: 0) + 90) % 360; renderList() }, w = dp(32), h = dp(32), side = 2)
                r.add(miniBtn("✕") { pagesOrder.removeAt(idx); renderList() }, w = dp(32), h = dp(32), side = 2)
                listContainer.add(r, top = 4)
            }
        }
        renderList()

        AlertDialog.Builder(this)
            .setTitle("Kelola & Susun Ulang PDF")
            .setView(box)
            .setPositiveButton("Simpan PDF Baru") { _, _ ->
                if (pagesOrder.isEmpty()) { toast("Semua halaman telah dihapus"); return@setPositiveButton }
                val out = Files.newOut(this, "susun_" + Files.baseName(name), "pdf")
                work("Menyusun PDF baru…", {
                    Pdf.organizePdf(this, uri, pagesOrder, rotations, out)
                }) { r ->
                    r.onSuccess { showResult("PDF dikelola & disusun", out, PDF) }.onFailure { fail(it) }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun extractImagesFlow(uri: Uri) {
        val name = Files.nameOf(this, uri)
        val base = Files.baseName(name)
        work("Sedot & ekstrak semua foto dari PDF…", {
            Pdf.extractImagesFromPdf(this, uri, base)
        }) { r ->
            r.onSuccess { count ->
                AlertDialog.Builder(this)
                    .setTitle("Ekstrak Foto Selesai")
                    .setMessage("✓ Berhasil menyedot $count gambar dari PDF ke Galeri › Pictures/Depup Office!")
                    .setPositiveButton("Mengerti", null)
                    .show()
            }.onFailure { fail(it) }
        }
    }

    private fun batchConvertFlow() = pickFiles(arrayOf("image/*")) { uris ->
        work("Konversi Massal ${uris.size} foto jadi PDF…", {
            Pdf.batchImagesToIndividualPdf(this, uris)
        }) { r ->
            r.onSuccess { n ->
                AlertDialog.Builder(this)
                    .setTitle("Konversi Massal Selesai")
                    .setMessage("✓ Berhasil mengonversi $n foto menjadi $n file PDF terpisah di folder sdcard/DepupOffice!")
                    .setPositiveButton("Mengerti", null)
                    .show()
            }.onFailure { fail(it) }
        }
    }

    private fun compressGalleryFlow() = pickFiles(arrayOf("image/*")) { uris ->
        val levels = listOf("Sedang (1920px · 80%)", "Tinggi (1280px · 70%)", "Maksimal (1080px · 55%)")
        var sel = 0

        val box = vbox(12).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.add(tv("Kompres ${uris.size} Foto dari Galeri HP", 14f, C.TEXT, true))
        box.add(tv("Kecilkan ukuran file foto galeri agar cepat dikirim via WhatsApp / Email.", 12f, C.MUTED))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = mutableListOf<TextView>()
        fun paintLevels() {
            chips.forEachIndexed { i, chip ->
                val on = i == sel
                chip.setTextColor(if (on) C.ON_PRIMARY else C.TEXT)
                chip.background = shape(if (on) C.TEAL else C.SURFACE2, 8)
            }
        }
        levels.forEachIndexed { i, name ->
            val chip = tv(name, 10f, C.TEXT, true).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { sel = i; paintLevels() }
            }
            chips.add(chip)
            row.add(chip, w = 0, weight = 1f).also { if (i > 0) (chip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(4) }
        }
        paintLevels()
        box.add(row, top = 8)

        AlertDialog.Builder(this)
            .setTitle("Kompres Foto Galeri")
            .setView(box)
            .setPositiveButton("Kompres Sekarang") { _, _ ->
                val dim = listOf(1920, 1280, 1080)[sel]
                val q = listOf(80, 70, 55)[sel]
                work("Mengompres ${uris.size} foto galeri…", {
                    Pdf.compressGalleryImages(this, uris, dim, q)
                }) { r ->
                    r.onSuccess { (count, saved) ->
                        val savedMb = Files.fmtSize(saved)
                        AlertDialog.Builder(this)
                            .setTitle("Kompres Foto Selesai")
                            .setMessage("✓ Berhasil mengompres $count foto ke Galeri › Pictures/Depup Office!\n" + if (saved > 0) "Hemat ruang penyimpanan: $savedMb" else "")
                            .setPositiveButton("Mengerti", null)
                            .show()
                    }.onFailure { fail(it) }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun extractTransparentSignatureFlow() = pickFile(arrayOf("image/*")) { uri ->
        work("Menghapus latar kertas & membuat PNG transparan…", {
            val raw = Pdf.decode(this, uri, 1200)
            val transparentBmp = Pdf.extractTransparentSignature(raw)
            raw.recycle()

            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "TTD_Transparan_${System.currentTimeMillis()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Depup Office")
            }
            val dst = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("Gagal menyimpan PNG")
            contentResolver.openOutputStream(dst)!!.use { o -> transparentBmp.compress(Bitmap.CompressFormat.PNG, 100, o) }
            transparentBmp.recycle()
        }) { r ->
            r.onSuccess {
                AlertDialog.Builder(this)
                    .setTitle("Ekstrak TTD Selesai")
                    .setMessage("✓ Tanda Tangan / Stempel Transparan berhasil dibuat dan disimpan ke Galeri › Pictures/Depup Office!\nSiap ditempelkan ke dokumen mana pun.")
                    .setPositiveButton("Mengerti", null)
                    .show()
            }.onFailure { fail(it) }
        }
    }

    private fun showQrDialog() {
        sheet { body, dismiss ->
            sheetHeader(
                body, R.drawable.ic_spark, C.TEAL, C.TEAL_BG,
                "Kode QR & Barcode", "Pindai dari foto atau buat kode QR asli", dismiss
            )
            val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(18)) }

            val scanCard = actionRow(
                R.drawable.ic_search, "Pindai dari Foto / Galeri",
                "Membaca QR, barcode produk, tiket, dan kode verifikasi",
                C.TEAL, C.TEAL_BG
            ) {
                dismiss()
                pickFile(arrayOf("image/*")) { uri ->
                    work("Membaca Kode QR / Barcode…", {
                        val bmp = Pdf.decode(this, uri, 1600)
                        val results = QrManager.scanAll(bmp)
                        bmp.recycle()
                        results
                    }) { r ->
                        r.onSuccess { results -> showQrScanResult(results) }.onFailure { fail(it) }
                    }
                }
            }
            pad.add(scanCard)

            val makeCard = actionRow(
                R.drawable.ic_spark, "Buat Kode QR Baru",
                "Link, teks, nomor HP, atau informasi donasi",
                C.ACCENT, C.BLUE_BG
            ) {
                dismiss()
                showQrGenerator()
            }
            pad.add(makeCard, top = 10)

            pad.add(
                tv(
                    "Kode QR dibuat dengan standar ISO/IEC 18004 dan koreksi error tinggi, sehingga tetap terbaca walau sedikit tergores atau buram.",
                    11f, C.MUTED
                ).apply {
                    background = shape(C.SURFACE2, 10)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }, top = 14
            )
            body.add(pad)
        }
    }

    private fun showQrScanResult(results: List<QrResult>) {
        if (results.isEmpty()) {
            toast("Kode QR / barcode tidak terdeteksi. Coba foto yang lebih tajam dan terang.")
            return
        }
        val main = results.first()
        val allText = results.joinToString("\n\n") { "${it.format}:\n${it.text}" }

        val box = vbox(14).apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        results.forEach { result ->
            val c = card(12, 12)
            c.add(pill(result.format, C.TEAL, C.TEAL_BG, 10f), w = WRAP)
            c.add(TextView(this).apply {
                text = result.text
                textSize = 14f
                setTextColor(C.TEXT)
                typeface = Fonts.body(this@MainActivity)
                setTextIsSelectable(true)
            }, top = 8)
            box.add(c, top = if (results.indexOf(result) == 0) 6 else 8)
        }

        val canOpen = main.text.startsWith("http://") || main.text.startsWith("https://") ||
            main.text.startsWith("tel:") || main.text.startsWith("mailto:")
        if (canOpen) {
            box.add(btn("Buka Link", true, C.ACCENT, R.drawable.ic_cloud) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(main.text)))
                } catch (_: Exception) {
                    toast("Link tidak dapat dibuka")
                }
            }, top = 12)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Hasil Pindai (${results.size} kode)")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Salin Semua") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("QR", allText))
                toast("Teks QR disalin")
            }
            .setNeutralButton("Bagikan") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, allText)
                }, "Bagikan hasil pindai"))
            }
            .setNegativeButton("Tutup", null)
            .create()
        dialog.show()
    }

    private fun showQrGenerator() {
        sheet { body, dismiss ->
            sheetHeader(
                body, R.drawable.ic_spark, C.ACCENT, C.BLUE_BG,
                "Pembuat Kode QR", "Pratinjau langsung • simpan PNG atau bagikan", dismiss
            )
            val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(18)) }

            val previewCard = FrameLayout(this).apply {
                background = raised(Color.WHITE, 16, 2.5f)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(R.drawable.ic_spark)
                setColorFilter(C.LINE_STRONG)
            }
            previewCard.addView(preview, FrameLayout.LayoutParams(MATCH, dp(250)))
            pad.add(previewCard)

            val status = tv("Ketik sesuatu untuk melihat pratinjau kode QR.", 11f, C.MUTED).apply {
                gravity = Gravity.CENTER
            }
            pad.add(status, top = 8)

            val et = field("Masukkan link, teks, nomor HP, atau info donasi")
            pad.add(et, top = 12)

            var currentBitmap: Bitmap? = null

            fun updatePreview() {
                val text = et.text.toString().trim()
                if (text.isEmpty()) {
                    currentBitmap = null
                    preview.setImageResource(R.drawable.ic_spark)
                    preview.setColorFilter(C.LINE_STRONG)
                    status.setTextColor(C.MUTED)
                    status.text = "Ketik sesuatu untuk melihat pratinjau kode QR."
                    return
                }
                if (QrManager.isTextTooLong(text)) {
                    currentBitmap = null
                    preview.setImageResource(R.drawable.ic_spark)
                    preview.setColorFilter(C.RED)
                    status.setTextColor(C.RED)
                    status.text = "Teks terlalu panjang. Maksimal ±1.200 karakter untuk QR koreksi tinggi."
                    return
                }
                try {
                    val bmp = QrManager.generateQr(text, 640, 640)
                    currentBitmap = bmp
                    preview.clearColorFilter()
                    preview.setImageBitmap(bmp)
                    status.setTextColor(C.TEAL)
                    status.text = "Kode QR siap dipindai • ${text.toByteArray(Charsets.UTF_8).size} byte"
                } catch (_: Throwable) {
                    currentBitmap = null
                    preview.setImageResource(R.drawable.ic_spark)
                    preview.setColorFilter(C.RED)
                    status.setTextColor(C.RED)
                    status.text = "Teks ini tidak dapat dijadikan kode QR."
                }
            }

            et.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = updatePreview()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })

            pad.add(
                tv(
                    "Tips: gunakan link lengkap (https://…) agar bisa langsung dibuka saat dipindai. Untuk teks panjang, kode QR akan lebih padat.",
                    11f, C.MUTED
                ).apply {
                    background = shape(C.SURFACE2, 10)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }, top = 10
            )

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.add(btn("Simpan PNG", true, C.ACCENT, R.drawable.ic_download) {
                val bmp = currentBitmap
                if (bmp == null) {
                    toast("Teks QR masih kosong atau terlalu panjang")
                    return@btn
                }
                saveQrBitmap(bmp, "QR_Code")
                toast("Kode QR disimpan di Galeri › Pictures/Depup Office")
            }, w = 0, weight = 1f)
            val shareBtn = btn("Bagikan", false, C.ACCENT, R.drawable.ic_share) {
                val bmp = currentBitmap
                val text = et.text.toString().trim()
                if (bmp == null) {
                    toast("Teks QR masih kosong atau terlalu panjang")
                    return@btn
                }
                shareQrBitmap(bmp, text)
            }
            row.add(shareBtn, w = 0, weight = 1f)
            (shareBtn.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            pad.add(row, top = 12)

            body.add(pad)
            et.requestFocus()
        }
    }

    private fun jpgToPdfFlow() = pickFiles(arrayOf("image/*")) { uris ->
        val out = Files.newOut(this, "gambar", "pdf")
        work("Membuat PDF…", { Pdf.imagesToPdf(this, uris, out) }) { r ->
            r.onSuccess { showResult("PDF dibuat dari ${uris.size} gambar", out, PDF) }.onFailure { fail(it) }
        }
    }

    // ================= KAMERA =================
    private fun startScan() { camera.launch(Intent(this, CameraActivity::class.java)) }

    private fun textFlow() {
        val title = field("Judul (opsional)")
        val body = field("Ketik teks di sini…", multi = true)
        val box = vbox(20)
        box.add(title)
        box.add(body, top = 10)
        AlertDialog.Builder(this).setTitle("Tulis Teks jadi PDF")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Buat PDF") { _, _ ->
                val t = body.text.toString()
                if (t.isBlank()) { toast("Teks masih kosong"); return@setPositiveButton }
                val ttl = title.text.toString().trim()
                val content = if (ttl.isNotEmpty()) "$ttl\n\n$t" else t
                val out = Files.newOut(this, Files.baseName(ttl.ifBlank { "teks" }), "pdf")
                work("Membuat PDF…", { Pdf.text(content, out) }) { r ->
                    r.onSuccess { showResult("PDF dibuat", out, PDF) }.onFailure { fail(it) }
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun signFlow(uri: Uri) {
        val pages = try { Pdf.pageCount(this, uri) } catch (t: Throwable) { fail(t); return }
        val pad = SignatureView(this)
        val page = field("Halaman (1-$pages)", number = true).apply { setText(pages.toString()) }
        val pos = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val posIds = IntArray(3) { View.generateViewId() }
        listOf("Kiri", "Tengah", "Kanan").forEachIndexed { i, n ->
            pos.addView(RadioButton(this).apply { text = n; id = posIds[i]; setTextColor(C.TEXT) })
        }
        pos.check(posIds[2])
        val box = vbox(20)
        box.add(tv("Gambar tanda tangan di kotak putih", 13f, C.MUTED))
        box.add(pad, top = 8, h = dp(170))
        box.add(btn("Hapus", false) { pad.clear() }, top = 8)
        box.add(tv("Tempel di halaman", 13f, C.MUTED), top = 12)
        box.add(page, top = 6)
        box.add(pos, top = 8)
        val d = AlertDialog.Builder(this).setTitle("Tanda Tangan")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Tempel", null).setNegativeButton("Batal", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = page.text.toString().toIntOrNull()
            if (n == null || n < 1 || n > pages) { toast("Nomor halaman tidak valid"); return@setOnClickListener }
            val sig = pad.toBitmap() ?: run { toast("Tanda tangan masih kosong"); return@setOnClickListener }
            val p = posIds.indexOf(pos.checkedRadioButtonId).coerceAtLeast(0)
            d.dismiss()
            val out = Files.newOut(this, "ttd", "pdf")
            work("Menempel tanda tangan…", { Pdf.sign(this, uri, sig, n, p, out) }) { r ->
                r.onSuccess { showResult("Tanda tangan ditempel", out, PDF) }.onFailure { fail(it) }
            }
        }
    }

    // ================= SIMPAN =================
    private fun exportFlow() = pickFile(arrayOf("*/*")) { u ->
        val name = Files.nameOf(this, u)
        val mime = Files.mimeOf(this, u)
        work("Menyimpan…", { Files.saveToDownloads(this, u, name, mime) }) { r ->
            r.onSuccess { toast("Tersimpan di Unduhan/Depup Office") }.onFailure { fail(it) }
        }
    }

    private fun showResult(title: String, file: File, mime: String, extra: String = "") {
        val uri = Files.uriFor(this, file)
        Recent.add(this, uri, file.name, mime)
        Meta.setLastJob(this, title, file)
        resultSheet(
            title, "${file.name} · ${Files.fmtSize(file.length())}", extra, "Buka Dokumen", { Files.open(this, uri, mime) },
            listOf(
                SheetAction(R.drawable.ic_download, "Simpan ke Unduhan") {
                    work("Menyimpan…", { Files.saveToDownloads(this, uri, file.name, mime) }) { r ->
                        r.onSuccess { toast("Tersimpan di Unduhan/Depup Office") }.onFailure { fail(it) }
                    }
                },
                SheetAction(R.drawable.ic_lock, "Masukkan ke Brankas", C.AMBER, C.AMBER_BG) {
                    if (!Vault.hasPin(this)) {
                        toast("Buat PIN Brankas dulu")
                        startActivity(Intent(this, VaultActivity::class.java))
                    } else {
                        work("Mengenkripsi…", { contentResolver.openInputStream(uri)!!.use { Vault.encrypt(this, it, file.name) } }) { r ->
                            r.onSuccess { toast("Masuk ke Brankas") }.onFailure { fail(it) }
                        }
                    }
                },
                SheetAction(R.drawable.ic_chat, "Kirim via WhatsApp", C.TEAL, C.TEAL_BG) { Files.share(this, uri, mime, true) },
                SheetAction(R.drawable.ic_upload, "Bagikan ke aplikasi lain", C.INDIGO, C.INDIGO_BG) { Files.share(this, uri, mime, false) }
            )
        )
    }
}
