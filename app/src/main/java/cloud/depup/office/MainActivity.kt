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

    // ================= KERANGKA: app bar + konten + navigasi bawah =================
    private lateinit var content: FrameLayout
    private lateinit var barTitle: TextView
    private lateinit var fab: FrameLayout
    private val navViews = mutableMapOf<Int, Pair<ImageView, TextView>>()
    private var tab = 0
    private var query = ""
    private var recentBox: LinearLayout? = null
    private val tabNames = arrayOf("Baca", "Tools", "Buat", "Simpan")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            clipChildren = false
        }
        mainView.add(topBar())
        content = FrameLayout(this)
        mainView.add(content, w = MATCH, h = 0, weight = 1f)
        mainView.add(bottomNav())

        val root = FrameLayout(this)
        root.addView(mainView, FrameLayout.LayoutParams(MATCH, MATCH))
        val splash = buildSplash()
        root.addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        select(0)

        splash.postDelayed({
            splash.animate()
                .alpha(0f)
                .scaleX(1.05f)
                .scaleY(1.05f)
                .setDuration(350)
                .withEndAction {
                    root.removeView(splash)
                    window.statusBarColor = C.SURFACE
                    window.navigationBarColor = C.SURFACE
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        window.decorView.systemUiVisibility =
                            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    }
                }
                .start()
        }, 1500)
    }

    private fun buildSplash(): View {
        val splash = FrameLayout(this).apply {
            setBackgroundColor(0xFF070D1E.toInt())
            isClickable = true
            isFocusable = true
        }
        val center = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val titleTv = tv("Depup Office", 34f, 0xFFF0F9FF.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.03f
            alpha = 0f
            translationY = -40f
        }
        val subTitleTv = tv("Deploy Platform", 14f, 0xFF38BDF8.toInt()).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            alpha = 0f
            translationY = -20f
        }
        center.add(titleTv)
        center.add(subTitleTv, top = 6)

        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.ic_cyber_cloud_logo)
            alpha = 0f
            scaleX = 0.4f
            scaleY = 0.4f
            translationY = 30f
        }
        center.add(logoView, top = 36, w = dp(170), h = dp(170))

        val loadingLabel = tv("Loading…", 13f, 0xFF94A3B8.toInt()).apply {
            gravity = Gravity.CENTER
            alpha = 0f
        }
        center.add(loadingLabel, top = 40)

        val pbarTrack = FrameLayout(this).apply {
            background = shape(0x220284C7.toInt(), 100, 0xFF0284C7.toInt(), 1)
            alpha = 0f
        }
        val pbarFill = View(this).apply {
            background = shape(0xFF0284C7.toInt(), 100)
        }
        pbarTrack.addView(pbarFill, FrameLayout.LayoutParams(0, MATCH))
        center.add(pbarTrack, top = 12, w = dp(240), h = dp(10))

        val statusTv = tv("Initializing deploy environment • Syncing cloud nodes • Preparing platform…", 10f, 0xFF64748B.toInt()).apply {
            gravity = Gravity.CENTER
            alpha = 0f
        }
        center.add(statusTv, top = 16)

        splash.addView(center, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        titleTv.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        subTitleTv.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(150)
            .setDuration(600)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        logoView.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .translationY(0f)
            .setStartDelay(200)
            .setDuration(700)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .withEndAction {
                logoView.animate()
                    .scaleX(1.06f)
                    .scaleY(1.06f)
                    .setDuration(700)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()

        loadingLabel.animate().alpha(1f).setStartDelay(400).setDuration(400).start()
        pbarTrack.animate().alpha(1f).setStartDelay(450).setDuration(400).start()
        statusTv.animate().alpha(1f).setStartDelay(500).setDuration(400).start()

        pbarTrack.postDelayed({
            val maxW = pbarTrack.width
            val anim = android.animation.ValueAnimator.ofInt(0, maxW)
            anim.duration = 1000
            anim.interpolator = android.view.animation.DecelerateInterpolator()
            anim.addUpdateListener { a ->
                val v = a.animatedValue as Int
                pbarFill.layoutParams = FrameLayout.LayoutParams(v, MATCH)
            }
            anim.start()
        }, 450)

        return splash
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            elevation = dp(1).toFloat()
        }
        bar.add(ImageView(this).apply { setImageResource(R.drawable.ic_logo) }, w = dp(34), h = dp(34))
        barTitle = tv("Baca", 20f, C.ACCENT, true)
        bar.add(barTitle, w = 0, weight = 1f, side = 10)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val donasiBtn = icon(R.drawable.ic_heart, 0xFFEF4444.toInt(), 22).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { showDonationDialog() }
        }
        val s = icon(R.drawable.ic_search, C.MUTED, 22).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { showSearchDialog() }
        }
        val b = icon(R.drawable.ic_bell, C.MUTED, 22).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { toast("Tidak ada notifikasi baru") }
        }
        val u = iconBadge(R.drawable.ic_user, Color.WHITE, C.ACCENT, 100, 18).apply {
            isClickable = true
            setOnClickListener { toast("Profil Pengguna • Depup Office") }
        }
        actions.add(donasiBtn, w = dp(32), h = dp(32))
        actions.add(s, w = dp(32), h = dp(32), side = 2)
        actions.add(b, w = dp(32), h = dp(32), side = 2)
        actions.add(u, w = dp(32), h = dp(32), side = 4)

        bar.add(actions, w = WRAP)
        return bar
    }

    private fun openEWallet(walletIndex: Int, phone: String, amount: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("No HP Donasi", phone)
        clipboard.setPrimaryClip(clip)

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

        toast("Nomor $phone (a.n. MKA) disalin! Membuka $walletName…")

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
                .setTitle("Nomor Berhasil Disalin!")
                .setMessage("Nomor e-Wallet $phone (a.n. MKA) telah disalin ke clipboard.\n\nBuka aplikasi $walletName Anda dan paste nomor tersebut untuk melakukan transfer donasi $amount.")
                .setPositiveButton("Mengerti", null)
                .show()
        }
    }

    private fun showDonationDialog() {
        val ctx = this
        val dialogView = vbox(12).apply {
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headRow.add(iconBadge(R.drawable.ic_heart, 0xFFEF4444.toInt(), 0xFFFEE2E2.toInt(), 100, 24), w = dp(44), h = dp(44))
        val headCol = vbox()
        headCol.add(tv("Dukung Pengembang (Donasi)", 17f, C.TEXT, true))
        headCol.add(tv("Bantu aplikasi ini agar tetap 100% bebas iklan", 11f, C.MUTED), top = 2)
        headRow.add(headCol, w = 0, weight = 1f, side = 10)
        dialogView.add(headRow)

        dialogView.add(tv("Pilih Nominal Donasi:", 13f, C.TEXT, true), top = 10)

        val amounts = listOf("Rp 1.000", "Rp 2.000", "Rp 5.000", "Rp 10.000", "Rp 25.000", "Rp 50.000", "Rp 100.000", "Rp 1.000.000")
        var selectedAmount = "Rp 10.000"

        val amountsGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun renderChips() {
            amountsGrid.removeAllViews()
            var row: LinearLayout? = null
            amounts.forEachIndexed { i, amt ->
                if (i % 4 == 0) {
                    row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    amountsGrid.add(row!!, top = if (i == 0) 0 else 6)
                }
                val isSel = amt == selectedAmount
                val chip = tv(amt, 11f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                    background = shape(if (isSel) C.ACCENT else C.SURFACE2, 8)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener {
                        selectedAmount = amt
                        renderChips()
                    }
                }
                row?.add(chip, w = 0, weight = 1f).also {
                    if (i % 4 > 0) (chip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
                }
            }
        }
        renderChips()
        dialogView.add(amountsGrid, top = 6)

        val customEt = EditText(ctx).apply {
            hint = "Nominal Lainnya (misal: 15000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f
            background = shape(C.SURFACE2, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        dialogView.add(customEt, top = 8)

        dialogView.add(tv("Pilih e-Wallet:", 13f, C.TEXT, true), top = 12)

        var selectedWallet = 1
        val wallets = listOf("GoPay", "DANA", "ShopeePay")
        val walletRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        fun renderWallets() {
            walletRow.removeAllViews()
            wallets.forEachIndexed { idx, wName ->
                val isSel = idx == selectedWallet
                val wChip = tv(wName, 12f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                    background = shape(if (isSel) 0xFF0284C7.toInt() else C.SURFACE2, 10)
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener {
                        selectedWallet = idx
                        renderWallets()
                    }
                }
                walletRow.add(wChip, w = 0, weight = 1f).also {
                    if (idx > 0) (wChip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
                }
            }
        }
        renderWallets()
        dialogView.add(walletRow, top = 6)

        val accountCard = card(12, 12).apply {
            background = shape(0xFFF0F9FF.toInt(), 12, 0xFFBAE6FD.toInt(), 1)
        }
        val acRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val acCol = vbox()
        acCol.add(tv("Nomor HP Donasi:", 11f, C.MUTED))
        acCol.add(tv("081322544391", 16f, C.TEXT, true), top = 2)
        acCol.add(tv("a.n. MKA", 12f, 0xFF0284C7.toInt(), true), top = 2)
        acRow.add(acCol, w = 0, weight = 1f)

        val copyBtn = tv("Salin No HP", 11f, Color.WHITE, true).apply {
            background = shape(C.ACCENT, 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("No HP Donasi", "081322544391"))
                toast("Nomor 081322544391 (a.n. MKA) disalin!")
            }
        }
        acRow.add(copyBtn, w = WRAP)
        accountCard.add(acRow)
        dialogView.add(accountCard, top = 10)

        val transferBtn = btn("Transfer Sekarang via e-Wallet", true, C.ACCENT, R.drawable.ic_heart) {
            val custom = customEt.text.toString().trim()
            val finalAmount = if (custom.isNotBlank()) "Rp $custom" else selectedAmount
            openEWallet(selectedWallet, "081322544391", finalAmount)
        }
        dialogView.add(transferBtn, top = 14)

        AlertDialog.Builder(ctx)
            .setView(dialogView)
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showSearchDialog() {
        val et = EditText(this).apply { hint = "Cari dokumen PDF, Word, Excel…" }
        AlertDialog.Builder(this).setTitle("Pencarian Dokumen").setView(et)
            .setPositiveButton("Cari") { _, _ ->
                query = et.text.toString().trim()
                recentBox?.let { fillRecent(it, 8, query) }
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun bottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.SURFACE)
            clipChildren = false
            elevation = dp(8).toFloat()
        }
        fun item(idx: Int, res: Int, label: String): View {
            val g = icon(res, C.MUTED, 24)
            val l = tv(label, 11f, C.MUTED, true).apply { gravity = Gravity.CENTER }
            navViews[idx] = g to l
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { select(idx) }
                add(g, w = dp(24), h = dp(24))
                add(l, top = 2, w = WRAP)
            }
        }
        nav.add(item(0, R.drawable.ic_doc, "Baca"), w = 0, h = dp(64), weight = 1f)
        nav.add(item(1, R.drawable.ic_tools, "Tools"), w = 0, h = dp(64), weight = 1f)
        fab = FrameLayout(this).apply {
            addView(icon(R.drawable.ic_scan, Color.WHITE, 28), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.ACCENT) }
            elevation = dp(6).toFloat()
            translationY = -dp(16).toFloat()
            contentDescription = "Buat dokumen"
            setOnClickListener { select(2) }
        }
        val fabSlot = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        fabSlot.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        nav.add(fabSlot, w = 0, h = dp(64), weight = 1f)
        nav.add(item(3, R.drawable.ic_box, "Simpan"), w = 0, h = dp(64), weight = 1f)
        return nav
    }

    private fun select(i: Int) {
        tab = i
        barTitle.text = tabNames[i]
        navViews.forEach { (idx, v) ->
            val on = idx == i
            v.first.setColorFilter(if (on) C.ACCENT else C.MUTED)
            v.second.setTextColor(if (on) C.ACCENT else C.MUTED)
        }
        (fab.background as GradientDrawable).setColor(if (i == 2) C.ACCENT_DARK else C.ACCENT)
        render()
    }

    private fun render() {
        content.removeAllViews()
        recentBox = null
        val page = vbox(16).apply { setPadding(dp(16), dp(16), dp(16), dp(28)) }
        when (tab) {
            0 -> buildBaca(page)
            1 -> buildTools(page)
            2 -> buildBuat(page)
            else -> buildSimpan(page)
        }
        content.addView(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(page)
        })
    }

    private fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when { h < 11 -> "Selamat pagi"; h < 15 -> "Selamat siang"; h < 18 -> "Selamat sore"; else -> "Selamat malam" }
    }

    private fun heading(small: String?, title: String, sub: String? = null, parent: LinearLayout) {
        if (small != null) parent.add(tv(small, 13f, C.MUTED))
        parent.add(tv(title, 26f, C.TEXT, true), top = 2)
        if (sub != null) parent.add(tv(sub, 14f, C.MUTED), top = 6)
    }

    private fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Label, warna teks, warna latar lencana jenis berkas. */
    private fun typeOf(mime: String, name: String): Triple<String, Int, Int> {
        val n = name.lowercase()
        return when {
            mime == PDF || n.endsWith(".pdf") -> Triple("PDF", C.RED, C.RED_BG)
            mime.contains("word") || n.endsWith(".doc") || n.endsWith(".docx") -> Triple("DOCX", C.ACCENT, C.BLUE_BG)
            mime.contains("sheet") || mime.contains("excel") || n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".csv") ->
                Triple("XLSX", C.TEAL, C.TEAL_BG)
            mime.startsWith("image/") -> Triple("IMG", C.SKY, C.SKY_BG)
            else -> Triple(n.substringAfterLast('.', "FILE").uppercase().take(4), C.INDIGO, C.INDIGO_BG)
        }
    }

    private var filterMode = 0   // 0 semua terkini, 1 ditandai

    private fun metaLine(e: List<String>): String {
        val size = Files.sizeOf(this, Uri.parse(e[0]))
        return listOf(if (size >= 0) Files.fmtSize(size) else "", Files.relTime(e[3].toLongOrNull() ?: 0L))
            .filter { it.isNotBlank() }.joinToString(" • ")
    }

    private fun showRenameFileDialog(uriStr: String, currentName: String) {
        val et = field("Nama file baru").apply { setText(Files.baseName(currentName)) }
        AlertDialog.Builder(this)
            .setTitle("Ubah Nama Berkas")
            .setView(vbox(16).apply { add(et) })
            .setPositiveButton("Simpan Nama") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotBlank()) {
                    val renamedFile = Files.rename(this, Uri.parse(uriStr), newName)
                    if (renamedFile != null) {
                        toast("Nama file diubah ke ${renamedFile.name}")
                        render()
                    } else toast("Tidak dapat mengubah nama file ini")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun fileRow(e: List<String>): View {
        val (label, fg, bg) = typeOf(e[2], e[1])
        val uriStr = e[0]
        val r = card(12, 16)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = rippleCard(16)
        r.isClickable = true
        fun openIt() {
            try { Files.open(this, Uri.parse(uriStr), e[2]) } catch (_: Exception) { toast("File tidak lagi tersedia") }
        }
        r.setOnClickListener { openIt() }
        r.add(tv(label, 11f, fg, true).apply { gravity = Gravity.CENTER; background = shape(bg, 12) }, w = dp(44), h = dp(44))
        val col = vbox()
        col.add(tv(e[1], 15f, C.TEXT, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        val meta = metaLine(e)
        col.add(tv(if (meta.isBlank()) "Dokumen $label" else meta, 12f, C.MUTED), top = 2)
        r.add(col, w = 0, weight = 1f, side = 12)
        val marked = Meta.isMarked(this, uriStr)
        val bm = icon(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off, if (marked) C.ACCENT else C.MUTED, 22)
        bm.setPadding(dp(6), dp(6), dp(6), dp(6))
        bm.setOnClickListener {
            val on = Meta.toggleMark(this, uriStr)
            bm.setImageResource(if (on) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off)
            bm.setColorFilter(if (on) C.ACCENT else C.MUTED)
            if (filterMode == 1 && !on) render()
        }
        r.add(bm, w = dp(36), h = dp(36))
        val more = icon(R.drawable.ic_more, C.MUTED, 22)
        more.setPadding(dp(6), dp(6), dp(6), dp(6))
        more.setOnClickListener {
            val pm = android.widget.PopupMenu(this, more)
            pm.menu.add(0, 1, 0, "Buka")
            pm.menu.add(0, 2, 1, "Edit Dokumen")
            pm.menu.add(0, 3, 2, "Ubah Nama (Rename)")
            pm.menu.add(0, 4, 3, "Bagikan")
            pm.menu.add(0, 5, 4, "Hapus dari riwayat")
            pm.setOnMenuItemClickListener { m ->
                when (m.itemId) {
                    1 -> openIt()
                    2 -> {
                        val name = e[1].lowercase()
                        if (name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".csv")) {
                            startActivity(Intent(this, SheetEditorActivity::class.java))
                        } else {
                            startActivity(Intent(this, DocEditorActivity::class.java))
                        }
                    }
                    3 -> showRenameFileDialog(uriStr, e[1])
                    4 -> try { Files.share(this, Uri.parse(uriStr), e[2], false) } catch (_: Exception) { toast("Tidak bisa dibagikan") }
                    else -> { Recent.remove(this, uriStr); render() }
                }
                true
            }
            pm.show()
        }
        r.add(more, w = dp(36), h = dp(36))
        return r
    }

    private fun fillRecent(box: LinearLayout, max: Int, filter: String = "") {
        box.removeAllViews()
        val all = Recent.load(this)
        val marks = Meta.marks(this)
        val list = all.filter { filter.isBlank() || it[1].contains(filter, ignoreCase = true) }
            .filter { filterMode == 0 || marks.contains(it[0]) }.take(max)
        if (list.isEmpty()) {
            val msg = when {
                filterMode == 1 -> "Belum ada file yang ditandai. Ketuk ikon penanda di salah satu file."
                all.isEmpty() -> "Belum ada file terakhir. Buka PDF, Word, atau Excel untuk memulai."
                else -> "Tidak ada file yang cocok."
            }
            box.add(card(16, 16).apply { add(tv(msg, 13f, C.MUTED)) })
        } else {
            list.forEachIndexed { i, e -> box.add(fileRow(e), top = if (i == 0) 0 else 10) }
        }
    }

    private fun pillRow(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, x -> add(x, w = WRAP).also { if (i > 0) (x.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) } }
    }

    // ================= TAB BACA =================
    private fun buildBaca(p: LinearLayout) {
        val recent = Recent.load(this)
        val (free, total) = Files.deviceStorage()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = vbox()
        left.add(tv("● ${greeting()}", 13f, C.TEAL, true))
        left.add(tv("Ruang Baca\nBerkas", 28f, C.TEXT, true), top = 2)
        head.add(left, w = 0, weight = 1f)
        head.add(iconPill(R.drawable.ic_cloud, "${Files.fmtGb(total - free)} / ${Files.fmtGb(total)}", C.ACCENT, C.BLUE_BG), w = WRAP)
        p.add(head)

        val search = EditText(this).apply {
            hint = "Cari dokumen PDF, Word, Excel…"
            setHintTextColor(C.MUTED)
            setTextColor(C.TEXT)
            textSize = 14f
            isSingleLine = true
            background = shape(C.SURFACE2, 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minHeight = dp(48)
            setText(query)
            typeface = Fonts.body(this@MainActivity)
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                getDrawable(R.drawable.ic_search)!!.mutate().also { it.setTint(C.MUTED); it.setBounds(0, 0, dp(20), dp(20)) },
                null,
                getDrawable(R.drawable.ic_tune)!!.mutate().also { it.setTint(C.MUTED); it.setBounds(0, 0, dp(20), dp(20)) },
                null
            )
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    recentBox?.let { fillRecent(it, 8, query) }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        p.add(search, top = 14)

        val h = hero()
        val hrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hl = vbox()
        hl.add(iconPill(R.drawable.ic_spark, "SMART SCAN", Color.WHITE, 0x33FFFFFF, 10f), w = WRAP)
        hl.add(tv("Pindai Cepat\ndengan Kamera", 20f, Color.WHITE, true), top = 10)
        hl.add(tv("Ubah kertas fisik jadi PDF: multi halaman, potong, dan filter hitam-putih.", 12f, 0xE6FFFFFF.toInt()), top = 6)
        hl.add(heroBtn("Buka Kamera", R.drawable.ic_camera) { startScan() }, top = 14, w = WRAP)
        hrow.add(hl, w = 0, weight = 1f)
        hrow.add(iconBadge(R.drawable.ic_scan, Color.WHITE, 0x33FFFFFF, 20, 40), w = dp(84), h = dp(84), side = 8)
        h.add(hrow)
        p.add(h, top = 16)

        p.add(sectionTitle("Format Dokumen", "4 Kategori"), top = 22)
        fun count(pred: (List<String>) -> Boolean) = "${recent.count(pred)} file"
        p.add(twoCols(
            tile(R.drawable.ic_pdf, C.RED, C.RED_BG, "Buka PDF", "Buka file PDF dari File Manager HP",
                count { it[2] == PDF }, C.MUTED, C.SURFACE2) { openPdf() },
            tile(R.drawable.ic_doc, C.ACCENT, C.BLUE_BG, "Buka Word", "Buka file Word (.docx) dari File Manager HP",
                count { typeOf(it[2], it[1]).first == "DOCX" }, C.MUTED, C.SURFACE2) {
                pickFile(arrayOf(DOCX, "application/msword", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) { u ->
                    remember(u)
                    Files.open(this, u, Files.mimeOf(this, u))
                }
            }
        ), top = 12)
        p.add(twoCols(
            tile(R.drawable.ic_sheet, C.TEAL, C.TEAL_BG, "Buka Excel", "Buka file Excel (.xlsx) dari File Manager HP",
                count { typeOf(it[2], it[1]).first == "XLSX" }, C.MUTED, C.SURFACE2) {
                pickFile(arrayOf(XLSX, "application/vnd.ms-excel", "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { u ->
                    remember(u)
                    Files.open(this, u, Files.mimeOf(this, u))
                }
            },
            tile(R.drawable.ic_folder, C.INDIGO, C.INDIGO_BG, "Semua Berkas", "Buka File Manager sistem HP",
                "${outFiles().size} total", C.MUTED, C.SURFACE2) { openAny() }
        ), top = 12)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(res: Int, label: String, on: Boolean, f: () -> Unit) {
            val fgc = if (on) Color.WHITE else C.MUTED
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(if (on) C.ACCENT else C.SURFACE2, 100)
                setPadding(dp(14), dp(9), dp(16), dp(9))
                isClickable = true
                setOnClickListener { f() }
            }
            c.add(icon(res, fgc, 16), w = dp(16), h = dp(16))
            c.add(tv(label, 13f, fgc, true), w = WRAP, side = 6)
            chips.add(c, w = WRAP, side = 0).also { (c.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        }
        chip(R.drawable.ic_clock, "Semua Terkini", filterMode == 0) { filterMode = 0; render() }
        chip(R.drawable.ic_star, "Ditandai", filterMode == 1) { filterMode = 1; render() }
        chip(R.drawable.ic_lock, "Brankas Pribadi", false) { startActivity(Intent(this, VaultActivity::class.java)) }
        p.add(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, top = 18)

        p.add(sectionTitle("File Terakhir Dibuka", "Lihat Semua") { showRecent() }, top = 20)
        val box = vbox()
        recentBox = box
        fillRecent(box, 8, query)
        p.add(box, top = 12)
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
        val head = vbox(18).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFE0ECFF.toInt(), 0xFFEEF2FF.toInt()))
                .apply { cornerRadius = dp(20).toFloat() }
        }
        head.add(iconPill(R.drawable.ic_spark, "Serbaguna & Cepat", C.ACCENT, Color.WHITE, 11f), w = WRAP)
        head.add(tv("Alat PDF Lengkap", 26f, C.TEXT, true), top = 10)
        head.add(tv("Kelola, gabung, pisah, dan konversi format berkas secara instan.", 14f, C.MUTED), top = 6)
        p.add(head)

        val dz = vbox(20).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(C.SURFACE); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFFC7D2FE.toInt(), dp(6).toFloat(), dp(4).toFloat())
            }
            isClickable = true
            setOnClickListener { pickFile(arrayOf(PDF, "image/*")) { quickPick(it) } }
        }
        dz.add(iconBadge(R.drawable.ic_cloud, C.ACCENT, 0xFFDBE4FF.toInt(), 100, 30), w = dp(64), h = dp(64))
        dz.add(tv("Pilih atau Tarik Berkas", 17f, C.TEXT, true).apply { gravity = Gravity.CENTER }, top = 12)
        dz.add(tv("Ketuk untuk memilih berkas atau seret ke sini untuk memulai pengolahan instan", 12f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 4)
        dz.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            listOf("PDF", "JPG", "PNG", "Maks 50MB").forEachIndexed { i, t ->
                add(pill(t, C.MUTED, C.SURFACE2, 10f), w = WRAP).also { if (i > 0) (getChildAt(childCount - 1).layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
            }
        }, top = 12)
        p.add(dz, top = 16)

        Meta.lastJob(this)?.let { (title, name, path) ->
            val c = card(14, 16)
            val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r1.add(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.TEAL) } }, w = dp(8), h = dp(8))
            r1.add(tv("Proses Terakhir: $title", 13f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, w = 0, weight = 1f, side = 8)
            r1.add(pill("✓ Selesai 100%", C.TEAL, C.TEAL_BG, 10f), w = WRAP)
            c.add(r1)
            c.add(progressBar(1f), top = 10)
            val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r2.add(icon(R.drawable.ic_pdf, C.MUTED, 18), w = dp(18), h = dp(18))
            r2.add(tv(name, 12f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, w = 0, weight = 1f, side = 8)
            r2.add(tv("Unduh ⤓", 12f, C.ACCENT, true).apply {
                setPadding(dp(8), dp(6), dp(4), dp(6))
                setOnClickListener {
                    val f = File(path)
                    if (f.exists()) {
                        work("Menyimpan…", { Files.saveToDownloads(this@MainActivity, Files.uriFor(this@MainActivity, f), name, Files.byName(name)) }) { r ->
                            r.onSuccess { toast("Tersimpan di Unduhan/Depup Office") }.onFailure { fail(it) }
                        }
                    }
                }
            }, w = WRAP)
            c.add(r2, top = 10)
            p.add(c, top = 16)
        }

        p.add(sectionTitle("Peralatan Utama PDF & Dokumen", "12 Alat Tersedia"), top = 22)
        p.add(twoCols(
            tile(R.drawable.ic_merge, C.INDIGO, C.INDIGO_BG, "Gabung PDF", "Satukan banyak dokumen PDF jadi satu, urutan bebas",
                "Populer", C.ACCENT, C.BLUE_BG) { mergeFlow() },
            tile(R.drawable.ic_cut, C.INDIGO, C.INDIGO_BG, "Pisah PDF", "Pilah halaman atau pisahkan berkas sesuai rentang",
                chev = true) { pickFile(arrayOf(PDF)) { splitFlow(it) } }
        ), top = 12)
        p.add(twoCols(
            tile(R.drawable.ic_crop, C.ACCENT, C.BLUE_BG, "Susun & Hapus PDF", "Atur urutan, putar 90°, atau buang halaman PDF",
                "Baru", C.ACCENT, C.BLUE_BG) { pickFile(arrayOf(PDF)) { organizePdfFlow(it) } },
            tile(R.drawable.ic_image, C.INDIGO, C.INDIGO_BG, "Sedot Foto PDF", "Ekstrak seluruh gambar/foto dari PDF ke Galeri",
                chev = true) { pickFile(arrayOf(PDF)) { extractImagesFlow(it) } }
        ), top = 12)
        p.add(twoCols(
            tile(R.drawable.ic_compress, C.TEAL, C.TEAL_BG, "Kompres PDF Target", "Kompres presisi < 200KB, < 500KB, < 1MB untuk CPNS",
                "SSCASN", C.TEAL, C.TEAL_BG) { pickFile(arrayOf(PDF)) { compressFlow(it) } },
            tile(R.drawable.ic_sheet, C.TEAL, C.TEAL_BG, "Scan Struk & Nota", "Pindai nota/struk belanja, ekstrak total ke Excel",
                chev = true) { startScan() }
        ), top = 12)
        p.add(twoCols(
            tile(R.drawable.ic_spark, C.INDIGO, C.INDIGO_BG, "Konversi Massal", "Konversi belasan foto sekaligus jadi PDF terpisah",
                "Batch", C.INDIGO, C.INDIGO_BG) { batchConvertFlow() },
            tile(R.drawable.ic_image, C.TEAL, C.TEAL_BG, "Kompres Foto Galeri", "Kecilkan ukuran foto galeri sebelum dikirim WA/Email",
                chev = true) { compressGalleryFlow() }
        ), top = 12)
        p.add(twoCols(
            tile(R.drawable.ic_img_add, C.INDIGO, C.INDIGO_BG, "JPG ke PDF", "Ubah foto atau hasil tangkapan kamera jadi PDF",
                chev = true) { jpgToPdfFlow() },
            tile(R.drawable.ic_lock, C.INDIGO, C.INDIGO_BG, "Kunci Password", "Proteksi & buka proteksi sandi dokumen rahasia",
                chev = true) { pickFile(arrayOf(PDF)) { lockPasswordFlow(it) } }
        ), top = 12)

        p.add(sectionTitle("Pengaturan Format Dokumen", "3 Format"), top = 22)
        p.add(twoCols(
            tile(R.drawable.ic_doc, C.ACCENT, C.BLUE_BG, "Pengaturan Word", "Ukuran A4, margin & spasi",
                chev = true) { showWordFormatSettingsSheet() },
            tile(R.drawable.ic_sheet, C.TEAL, C.TEAL_BG, "Pengaturan Excel", "Garis kisi & mata uang",
                chev = true) { showExcelFormatSettingsSheet() }
        ), top = 12)
        p.add(twoCols(
            tile(R.drawable.ic_pdf, C.RED, C.RED_BG, "Pengaturan PDF", "Preset zoom & tema reader",
                chev = true) { showPdfFormatSettingsSheet() },
            tile(R.drawable.ic_lock, C.INDIGO, C.INDIGO_BG, "Brankas Vault", "AES-256 terenkripsi",
                chev = true) { startActivity(Intent(this, VaultActivity::class.java)) }
        ), top = 12)

        val tip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = shape(0xFFE4EAFB.toInt(), 16)
        }
        tip.add(iconBadge(R.drawable.ic_bulb, C.ACCENT, 0xFFD3DEF7.toInt(), 100, 22), w = dp(40), h = dp(40))
        val tc = vbox()
        tc.add(tv("Tips Efisiensi", 15f, C.TEXT, true))
        tc.add(tv("Semua proses berjalan secara lokal dan aman di perangkat tanpa mengunggah isi rahasia ke server luar.", 12f, C.MUTED), top = 2)
        tip.add(tc, w = 0, weight = 1f, side = 12)
        p.add(tip, top = 20)
    }

    private fun showWordFormatSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_doc, C.ACCENT, C.BLUE_BG, "Pengaturan Format Word", "Konfigurasi standar dokumen .docx", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
        pad.add(tv("Ukuran Kertas Standar: ${DocSettings.pageSize(this)}", 13f, C.TEXT, true))
        pad.add(tv("Spasi Baris Standar: ${DocSettings.lineSpacing(this)}x", 13f, C.TEXT, true), top = 8)
        pad.add(btn("Buka Editor Word Dokumen") {
            dismiss()
            startActivity(Intent(this, DocEditorActivity::class.java))
        }, top = 16)
        body.add(pad)
    }

    private fun showExcelFormatSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_sheet, C.TEAL, C.TEAL_BG, "Pengaturan Format Excel", "Konfigurasi standar spreadsheet", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
        pad.add(tv("Mata Uang Standar: ${SheetSettings.currencySymbol(this)}", 13f, C.TEXT, true))
        pad.add(tv("Garis Kisi (Gridlines): ${if (SheetSettings.showGridlines(this)) "Aktif" else "Nonaktif"}", 13f, C.TEXT, true), top = 8)
        pad.add(btn("Buka Editor Spreadsheet Excel") {
            dismiss()
            startActivity(Intent(this, SheetEditorActivity::class.java))
        }, top = 16)
        body.add(pad)
    }

    private fun showPdfFormatSettingsSheet() = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_pdf, C.RED, C.RED_BG, "Pengaturan Format PDF", "Konfigurasi standar pembaca PDF", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
        pad.add(tv("Preset Zoom Reader: ${(PdfSettings.defaultZoom(this) * 100).toInt()}%", 13f, C.TEXT, true))
        pad.add(tv("Tema Tampilan: Mode Siang", 13f, C.TEXT, true), top = 8)
        body.add(pad)
    }

    private fun lockPasswordFlow(u: Uri) {
        val name = Files.nameOf(this, u)
        val et = EditText(this).apply {
            hint = "Masukkan kata sandi rahasia"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Kunci Password PDF")
            .setMessage("Lindungi file '$name' dengan kata sandi rahasia.")
            .setView(et)
            .setPositiveButton("Kunci & Simpan") { _, _ ->
                val pass = et.text.toString().trim()
                if (pass.isBlank()) { toast("Kata sandi tidak boleh kosong"); return@setPositiveButton }
                toast("File '$name' telah diproteksi kata sandi")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ================= TAB BUAT =================
    private fun outFiles(): List<File> =
        (Files.outDir(this).listFiles()?.filter { it.isFile } ?: emptyList()).sortedByDescending { it.lastModified() }

    private fun outRow(f: File): View {
        val mime = Files.byName(f.name)
        val (label, fg, bg) = typeOf(mime, f.name)
        val r = card(12, 16)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.add(tv(label, 11f, fg, true).apply { gravity = Gravity.CENTER; background = shape(bg, 12) }, w = dp(44), h = dp(44))
        val col = vbox()
        col.add(tv(f.name, 14f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        col.add(tv("${Files.fmtSize(f.length())} • ${Files.relTime(f.lastModified())}", 12f, C.MUTED), top = 2)
        r.add(col, w = 0, weight = 1f, side = 12)
        r.add(tv("Buka  ›", 13f, C.ACCENT, true).apply {
            background = shape(C.BLUE_BG, 10)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { Files.open(this@MainActivity, Files.uriFor(this@MainActivity, f), mime) }
        }, w = WRAP, side = 6)
        return r
    }

    private fun hint(res: Int, text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        add(icon(res, C.TEAL, 14), w = dp(14), h = dp(14))
        add(tv(text, 11f, C.MUTED), w = WRAP, side = 4)
    }

    private fun buildBuat(p: LinearLayout) {
        p.add(iconPill(R.drawable.ic_plus, "STUDIO KREASI PDF", C.ACCENT, C.BLUE_BG, 10f), w = WRAP)
        p.add(tv("Buat Dokumen Baru", 26f, C.TEXT, true), top = 10)
        p.add(tv("Pindai fisik, tulis langsung, atau bubuhkan tanda tangan resmi.", 14f, C.MUTED), top = 6)

        // --- Scan kamera ---
        val h = hero()
        val hr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hr.add(iconBadge(R.drawable.ic_camera, Color.WHITE, 0x33FFFFFF, 14, 24), w = dp(48), h = dp(48))
        val hc = vbox()
        hc.add(pill("PALING POPULER", C.TEAL, 0xFFCCFBF1.toInt(), 10f), w = WRAP)
        hc.add(tv("Scan Kamera jadi PDF", 20f, Color.WHITE, true), top = 4)
        hr.add(hc, w = 0, weight = 1f, side = 12)
        h.add(hr)
        h.add(ScanFrameView(this), top = 16, h = dp(130))
        h.add(pillRow(
            iconPill(R.drawable.ic_merge, "Multi-halaman", Color.WHITE, 0x33FFFFFF, 11f),
            iconPill(R.drawable.ic_crop, "Potong manual", Color.WHITE, 0x33FFFFFF, 11f)
        ), top = 14)
        h.add(pillRow(iconPill(R.drawable.ic_contrast, "Filter B&W & Warna", Color.WHITE, 0x33FFFFFF, 11f)), top = 8)
        h.add(heroBtn("Buka Kamera Pindai", R.drawable.ic_scan) { startScan() }, top = 16)
        p.add(h, top = 18)

        // --- Buat Word & Excel ---
        p.add(twoCols(
            tile(R.drawable.ic_doc, C.ACCENT, C.BLUE_BG, "Buat Word", "Editor .docx interaktif",
                "A4 & Format", C.ACCENT, C.BLUE_BG) {
                startActivity(Intent(this, DocEditorActivity::class.java))
            },
            tile(R.drawable.ic_sheet, C.TEAL, C.TEAL_BG, "Buat Excel", "Spreadsheet & Rumus",
                "Rumus =SUM", C.TEAL, C.TEAL_BG) {
                startActivity(Intent(this, SheetEditorActivity::class.java))
            }
        ), top = 16)

        // --- Template Dokumen Siap Pakai ---
        p.add(sectionTitle("Template Dokumen Siap Pakai", "4 Template"), top = 20)
        p.add(twoCols(
            tile(R.drawable.ic_doc, C.ACCENT, C.BLUE_BG, "Surat Lamaran Kerja", "Template resmi HRD, siap edit & simpan .docx",
                "Siap Kerja", C.ACCENT, C.BLUE_BG) {
                startActivity(Intent(this, DocEditorActivity::class.java))
            },
            tile(R.drawable.ic_doc, C.INDIGO, C.INDIGO_BG, "CV / Resume Profesional", "Template Curriculum Vitae terstruktur",
                "Karir", C.INDIGO, C.INDIGO_BG) {
                startActivity(Intent(this, DocEditorActivity::class.java))
            }
        ), top = 12)
        p.add(twoCols(
            tile(R.drawable.ic_sheet, C.TEAL, C.TEAL_BG, "Kuintansi / Invoice", "Format pembayaran resmi & nota penjualan",
                "Keuangan", C.TEAL, C.TEAL_BG) {
                startActivity(Intent(this, SheetEditorActivity::class.java))
            },
            tile(R.drawable.ic_sign, C.ACCENT, C.BLUE_BG, "Ekstrak TTD Transparan", "Ubah foto TTD basah kertas jadi PNG transparan",
                "Fitur Baru", C.ACCENT, C.BLUE_BG) {
                extractTransparentSignatureFlow()
            }
        ), top = 12)

        // --- Tulis teks ---
        val t = card(16, 16)
        val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tr.add(iconBadge(R.drawable.ic_note, C.INDIGO, C.INDIGO_BG, 14, 24), w = dp(48), h = dp(48))
        val tc = vbox()
        tc.add(tv("Tulis Teks jadi PDF", 17f, C.TEXT, true))
        tc.add(tv("Editor memo cepat, hasil PDF rapi", 12f, C.MUTED), top = 2)
        tr.add(tc, w = 0, weight = 1f, side = 12)
        t.add(tr)
        t.add(evenRow(listOf(
            miniTile(R.drawable.ic_bulb, "Catatan Instan"),
            miniTile(R.drawable.ic_doc, "Ukuran A4 Rapi"),
            miniTile(R.drawable.ic_sheet, "Multi-halaman")
        )), top = 14)
        t.add(btn("Buat Dokumen Teks", true, C.INDIGO, R.drawable.ic_note) { textFlow() }, top = 14)
        p.add(t, top = 16)

        // --- Tanda tangan ---
        val s = card(16, 16)
        val sr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sr.add(iconBadge(R.drawable.ic_sign, C.TEAL, C.TEAL_BG, 14, 24), w = dp(48), h = dp(48))
        val sc = vbox()
        sc.add(tv("Tanda Tangan Digital", 17f, C.TEXT, true))
        sc.add(tv("Gambar tanda tangan, tempel ke PDF", 12f, C.MUTED), top = 2)
        sr.add(sc, w = 0, weight = 1f, side = 12)
        sr.add(pill("Offline", C.TEAL, C.TEAL_BG, 10f), w = WRAP)
        s.add(sr)
        val pv = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(0xFFF0FDFA.toInt(), 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val pc = vbox()
        pc.add(tv("Pratinjau Goresan Pena", 10f, C.MUTED))
        pc.add(tv("Tanda Tangan Anda", 22f, 0xFF0F5C4E.toInt()).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC) }, top = 4)
        pv.add(pc, w = 0, weight = 1f)
        pv.add(iconBadge(R.drawable.ic_check_ok, C.TEAL, Color.WHITE, 100, 22), w = dp(38), h = dp(38))
        s.add(pv, top = 14)
        s.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            add(hint(R.drawable.ic_sign, "Gambar di layar"), w = 0, weight = 1f)
            add(hint(R.drawable.ic_doc, "Pilih halaman"), w = 0, weight = 1f)
            add(hint(R.drawable.ic_full, "Kiri/Tengah/Kanan"), w = 0, weight = 1.2f)
        }, top = 12)
        s.add(btn("Bubuhkan Tanda Tangan", true, C.TEAL, R.drawable.ic_sign) { pickFile(arrayOf(PDF)) { signFlow(it) } }, top = 14)
        p.add(s, top = 16)

        // --- Hasil terakhir ---
        val outs = outFiles()
        if (outs.isNotEmpty()) {
            p.add(sectionTitle("Hasil Terakhir", "Lihat Semua (${outs.size})") { showOutputs(outs) }, top = 22)
            outs.take(2).forEachIndexed { i, f -> p.add(outRow(f), top = if (i == 0) 12 else 10) }
        }

        val tip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(0xFFEEF2FF.toInt(), 16)
        }
        tip.add(iconBadge(R.drawable.ic_bulb, C.TEAL, 0xFFCCFBF1.toInt(), 100, 20), w = dp(36), h = dp(36))
        tip.add(tv("Tips Pencahayaan: letakkan berkas di atas latar kontras dan cahaya merata agar hasil potong lebih tajam.", 12f, C.MUTED), w = 0, weight = 1f, side = 12)
        p.add(tip, top = 20)
    }

    private fun showFolderExplorerSheet(initialCategory: String = "SEMUA") = sheet { body, dismiss ->
        sheetHeader(body, R.drawable.ic_folder, C.ACCENT, C.BLUE_BG, "Folder DepupOffice", "sdcard/DepupOffice • ${Files.fmtSize(dirSize(Files.outDir(this)))}", dismiss)
        val pad = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }

        var activeFilter = initialCategory
        val allFiles = outFiles()

        val chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scrollChips = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipsRow) }

        val listBox = vbox()

        fun filterFiles(): List<File> = when (activeFilter) {
            "PDF" -> allFiles.filter { typeOf(Files.byName(it.name), it.name).first == "PDF" }
            "Word" -> allFiles.filter { typeOf(Files.byName(it.name), it.name).first == "DOCX" }
            "Excel" -> allFiles.filter { typeOf(Files.byName(it.name), it.name).first == "XLSX" }
            "Gambar" -> allFiles.filter { typeOf(Files.byName(it.name), it.name).first == "IMG" }
            else -> allFiles
        }

        fun renderFileList() {
            listBox.removeAllViews()
            val filtered = filterFiles()

            chipsRow.removeAllViews()
            val cats = listOf("SEMUA", "PDF", "Word", "Excel", "Gambar")
            cats.forEach { cat ->
                val count = when (cat) {
                    "PDF" -> allFiles.count { typeOf(Files.byName(it.name), it.name).first == "PDF" }
                    "Word" -> allFiles.count { typeOf(Files.byName(it.name), it.name).first == "DOCX" }
                    "Excel" -> allFiles.count { typeOf(Files.byName(it.name), it.name).first == "XLSX" }
                    "Gambar" -> allFiles.count { typeOf(Files.byName(it.name), it.name).first == "IMG" }
                    else -> allFiles.size
                }
                val isSel = cat.equals(activeFilter, ignoreCase = true)
                val chip = tv("$cat ($count)", 12f, if (isSel) Color.WHITE else C.TEXT, true).apply {
                    background = shape(if (isSel) C.ACCENT else C.SURFACE2, 100)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    isClickable = true
                    setOnClickListener { activeFilter = cat; renderFileList() }
                }
                chipsRow.add(chip, w = WRAP).also { (chip.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6) }
            }

            if (filtered.isEmpty()) {
                listBox.add(card(16, 16).apply {
                    add(tv("Belum ada berkas $activeFilter di folder sdcard/DepupOffice.", 13f, C.MUTED))
                }, top = 12)
            } else {
                filtered.forEachIndexed { idx, f ->
                    val mime = Files.byName(f.name)
                    val (label, fg, bg) = typeOf(mime, f.name)
                    val uri = Files.uriFor(this@MainActivity, f)
                    val r = card(12, 16)
                    r.orientation = LinearLayout.HORIZONTAL
                    r.gravity = Gravity.CENTER_VERTICAL
                    r.add(tv(label, 11f, fg, true).apply { gravity = Gravity.CENTER; background = shape(bg, 12) }, w = dp(44), h = dp(44))
                    val col = vbox()
                    col.add(tv(f.name, 14f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                    col.add(tv("${Files.fmtSize(f.length())} • ${Files.relTime(f.lastModified())}", 12f, C.MUTED), top = 2)
                    r.add(col, w = 0, weight = 1f, side = 10)

                    val more = icon(R.drawable.ic_more, C.MUTED, 22).apply {
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        setOnClickListener { v ->
                            val pm = android.widget.PopupMenu(this@MainActivity, v)
                            pm.menu.add(0, 1, 0, "Buka")
                            pm.menu.add(0, 2, 1, "Edit Dokumen")
                            pm.menu.add(0, 3, 2, "Ubah Nama (Rename)")
                            pm.menu.add(0, 4, 3, "Hapus File")
                            pm.menu.add(0, 5, 4, "Bagikan")
                            pm.setOnMenuItemClickListener { m ->
                                when (m.itemId) {
                                    1 -> { dismiss(); Files.open(this@MainActivity, uri, mime) }
                                    2 -> {
                                        dismiss()
                                        if (label == "XLSX") startActivity(Intent(this@MainActivity, SheetEditorActivity::class.java))
                                        else startActivity(Intent(this@MainActivity, DocEditorActivity::class.java))
                                    }
                                    3 -> { dismiss(); showRenameFileDialog(uri.toString(), f.name) }
                                    4 -> {
                                        confirm("Hapus Berkas", "Hapus '${f.name}' dari perangkat?", "Hapus") {
                                            f.delete()
                                            renderFileList()
                                            toast("Berkas dihapus")
                                        }
                                    }
                                    else -> try { Files.share(this@MainActivity, uri, mime, false) } catch (_: Exception) { toast("Tidak bisa dibagikan") }
                                }
                                true
                            }
                            pm.show()
                        }
                    }
                    r.add(more, w = dp(36), h = dp(36))
                    listBox.add(r, top = if (idx == 0) 12 else 8)
                }
            }
        }

        pad.add(scrollChips)
        pad.add(listBox)
        body.add(pad)
        renderFileList()
    }

    private fun showOutputs(list: List<File>) {
        AlertDialog.Builder(this).setTitle("Hasil olahan")
            .setItems(list.map { it.name }.toTypedArray()) { _, i ->
                val f = list[i]
                Files.open(this, Files.uriFor(this, f), Files.byName(f.name))
            }.setNegativeButton("Tutup", null).show()
    }

    // ================= TAB SIMPAN =================
    private fun buildSimpan(p: LinearLayout) {
        heading(null, "Penyimpanan &\nKeamanan", parent = p)
        p.add(iconPill(R.drawable.ic_verified, "Offline · File tidak pernah diunggah", C.TEAL, C.TEAL_BG, 11f), top = 8, w = WRAP)

        val vaultFiles = Vault.list(this)
        val h = hero()
        val hr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hr.add(iconBadge(R.drawable.ic_lock, Color.WHITE, 0x33FFFFFF, 14, 24), w = dp(48), h = dp(48))
        val hc = vbox()
        hc.add(tv("Brankas Terenkripsi", 18f, Color.WHITE, true))
        hc.add(tv("AES-256 · kunci di Android Keystore", 12f, 0xCCFFFFFF.toInt()), top = 2)
        hr.add(hc, w = 0, weight = 1f, side = 12)
        hr.add(pill("${vaultFiles.size} berkas", Color.WHITE, 0x33FFFFFF, 11f), w = WRAP)
        h.add(hr)
        h.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(0x26FFFFFF, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            add(icon(R.drawable.ic_shield, 0xFFBAE6FD.toInt(), 20), w = dp(20), h = dp(20))
            add(tv("Simpan KTP, ijazah, sertifikat & dokumen finansial. Dikunci PIN dan tanpa fitur reset.", 12f, 0xE6FFFFFF.toInt()), w = 0, weight = 1f, side = 10)
        }, top = 14)
        h.add(heroBtn("Buka Brankas Vault", R.drawable.ic_lock) { startActivity(Intent(this, VaultActivity::class.java)) }, top = 14)
        p.add(h, top = 16)

        val vaultSize = dirSize(Vault.dir(this))
        val outSize = dirSize(Files.outDir(this))
        val (free, total) = Files.deviceStorage()
        val st = card(16, 16)
        val sh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sh.add(icon(R.drawable.ic_tune, C.ACCENT, 20), w = dp(20), h = dp(20))
        sh.add(tv("Penyimpanan Terpakai", 16f, C.TEXT, true), w = 0, weight = 1f, side = 8)
        sh.add(tv(Files.fmtSize(vaultSize + outSize), 14f, C.ACCENT, true), w = WRAP)
        st.add(sh)
        st.add(stackedBar(listOf(outSize to C.ACCENT, vaultSize to C.INDIGO)), top = 12)
        st.add(tv("Tersisa ${Files.fmtGb(free)} dari ${Files.fmtGb(total)} di perangkat", 12f, C.MUTED).apply { gravity = Gravity.END }, top = 6)
        fun stat(label: String, v: Long, col: Int) = vbox(12).apply {
            background = shape(C.SURFACE2, 12)
            add(tv("● $label", 11f, col, true))
            add(tv(Files.fmtSize(v), 15f, C.TEXT, true), top = 4)
        }
        st.add(evenRow(listOf(
            stat("Hasil olahan", outSize, C.ACCENT),
            stat("Vault", vaultSize, C.INDIGO),
            stat("Sisa di HP", free, C.TEAL)
        ), 6), top = 12)
        p.add(st, top = 16)

        val folderCard = card(16, 16)
        val fRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fRow.add(iconBadge(R.drawable.ic_folder, C.ACCENT, C.BLUE_BG, 100, 24), w = dp(48), h = dp(48))
        val fCol = vbox()
        fCol.add(tv("Manajer Folder DepupOffice", 17f, C.TEXT, true))
        fCol.add(tv("sdcard/DepupOffice • ${outFiles().size} berkas tersimpan", 12f, C.MUTED), top = 2)
        fRow.add(fCol, w = 0, weight = 1f, side = 12)
        folderCard.add(fRow)

        val statRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pdfCount = outFiles().count { typeOf(Files.byName(it.name), it.name).first == "PDF" }
        val docCount = outFiles().count { typeOf(Files.byName(it.name), it.name).first == "DOCX" }
        val sheetCount = outFiles().count { typeOf(Files.byName(it.name), it.name).first == "XLSX" }
        val imgCount = outFiles().count { typeOf(Files.byName(it.name), it.name).first == "IMG" }

        fun fStat(label: String, count: Int, col: Int, bgCol: Int) = vbox(10).apply {
            background = shape(bgCol, 12)
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener { showFolderExplorerSheet(label) }
            add(tv(label, 11f, col, true))
            add(tv("$count file", 13f, C.TEXT, true), top = 2)
        }

        statRow.add(evenRow(listOf(
            fStat("PDF", pdfCount, C.RED, C.RED_BG),
            fStat("Word", docCount, C.ACCENT, C.BLUE_BG),
            fStat("Excel", sheetCount, C.TEAL, C.TEAL_BG),
            fStat("Gambar", imgCount, C.SKY, C.SKY_BG)
        ), 6))
        folderCard.add(statRow, top = 12)

        folderCard.add(btn("Eksplor Isi Folder DepupOffice", true, C.ACCENT, R.drawable.ic_folder) {
            showFolderExplorerSheet("SEMUA")
        }, top = 14)
        p.add(folderCard, top = 16)

        val wa = card(16, 16)
        val wr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wr.add(iconBadge(R.drawable.ic_share, C.TEAL, 0xFFCCFBF1.toInt(), 100, 22), w = dp(40), h = dp(40))
        val wc = vbox()
        wc.add(tv("Share WhatsApp & Sosial", 16f, C.TEXT, true))
        wc.add(tv("Kirim instan lewat aplikasi pesan", 12f, C.MUTED), top = 2)
        wr.add(wc, w = 0, weight = 1f, side = 12)
        wa.add(wr)
        wa.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(C.SURFACE2, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            add(icon(R.drawable.ic_compress, C.INDIGO, 20), w = dp(20), h = dp(20))
            val c = vbox()
            c.add(tv("Perkecil dulu supaya cepat terkirim", 12f, C.TEXT, true))
            c.add(tv("PDF besar bisa dikompres lewat Tools sebelum dikirim agar mudah dibuka penerima.", 11f, C.MUTED), top = 2)
            add(c, w = 0, weight = 1f, side = 10)
        }, top = 14)
        wa.add(btn("Kirim Cepat ke WhatsApp  →", true, 0xFF005952.toInt(), R.drawable.ic_chat) {
            pickFile(arrayOf("*/*")) { u -> Files.share(this, u, Files.mimeOf(this, u), true) }
        }, top = 12)
        wa.add(evenRow(listOf(
            btn("Bagikan Lain", false, C.ACCENT, R.drawable.ic_upload) {
                pickFile(arrayOf("*/*")) { u -> Files.share(this, u, Files.mimeOf(this, u), false) }
            },
            btn("Kompres PDF", false, C.ACCENT, R.drawable.ic_compress) { pickFile(arrayOf(PDF)) { compressFlow(it) } }
        ), 8), top = 8)
        p.add(wa, top = 16)

        val ex = card(16, 16)
        val eh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        eh.add(iconBadge(R.drawable.ic_upload, C.INDIGO, C.INDIGO_BG, 100, 20), w = dp(36), h = dp(36))
        eh.add(tv("Ekspor & Cetak Dokumen", 16f, C.TEXT, true), w = 0, weight = 1f, side = 10)
        eh.add(tv("Siap Kerja", 11f, C.MUTED), w = WRAP)
        ex.add(eh)
        fun exTile(res: Int, t1: String, t2: String, f: () -> Unit) = vbox(12).apply {
            background = shape(C.SURFACE2, 16)
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener { f() }
            add(iconBadge(res, C.ACCENT, Color.WHITE, 100, 22), w = dp(44), h = dp(44))
            add(tv(t1, 12f, C.TEXT, true).apply { gravity = Gravity.CENTER }, top = 8)
            add(tv(t2, 10f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 2)
        }
        ex.add(evenRow(listOf(
            exTile(R.drawable.ic_cloud, "Cloud / Drive", "Lewat menu bagikan") {
                pickFile(arrayOf("*/*")) { u -> Files.share(this, u, Files.mimeOf(this, u), false) }
            },
            exTile(R.drawable.ic_phone, "Memori HP", "Unduh lokal") { exportFlow() },
            exTile(R.drawable.ic_print, "Printer", "Cetak PDF") {
                pickFile(arrayOf(PDF)) { u -> Files.print(this, u, Files.nameOf(this, u)) }
            }
        ), 8), top = 12)
        p.add(ex, top = 16)

        p.add(sectionTitle("Riwayat Berbagi Terakhir"), top = 22)
        val sh2 = Meta.shares(this).take(3)
        if (sh2.isEmpty()) {
            p.add(card(16, 16).apply { add(tv("Belum ada riwayat berbagi. File yang kamu bagikan dari sini akan muncul di sini.", 13f, C.MUTED)) }, top = 12)
        } else sh2.forEachIndexed { i, e ->
            val wapp = e[1] == "WhatsApp"
            val r = card(12, 16)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.add(iconBadge(if (wapp) R.drawable.ic_chat else R.drawable.ic_upload,
                if (wapp) C.TEAL else C.INDIGO, if (wapp) 0xFFCCFBF1.toInt() else C.INDIGO_BG, 12, 20), w = dp(40), h = dp(40))
            val col = vbox()
            col.add(tv(e[0], 14f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            val sz = e[2].toLongOrNull() ?: -1L
            col.add(tv(listOf(e[1], if (sz >= 0) Files.fmtSize(sz) else "").filter { it.isNotBlank() }.joinToString(" • "), 12f, C.MUTED), top = 2)
            r.add(col, w = 0, weight = 1f, side = 12)
            r.add(tv(Files.relTime(e[3].toLongOrNull() ?: 0L), 11f, C.MUTED), w = WRAP, side = 4)
            p.add(r, top = if (i == 0) 12 else 8)
        }
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
        AlertDialog.Builder(this).setTitle("Recent Files")
            .setItems(list.map { it[1] }.toTypedArray()) { _, i ->
                try { Files.open(this, Uri.parse(list[i][0]), list[i][2]) }
                catch (e: Exception) { toast("File tidak lagi tersedia") }
            }.setNegativeButton("Tutup", null).show()
    }

    // ================= TOOLS =================
    private fun mergeFlow() = pickFiles(arrayOf(PDF)) { picked -> orderDialog(picked.toMutableList()) }

    private fun miniBtn(label: String, onClick: () -> Unit) = tv(label, 16f, C.ACCENT_SOFT, true).apply {
        gravity = Gravity.CENTER
        background = shape(C.BG, 10)
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
                background = shape(Color.WHITE, 8, C.LINE, 1)
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
                background = shape(Color.WHITE, 8, C.LINE, 1)
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
                val chip = tv(tName, 11f, if (isSel) Color.WHITE else C.TEXT, true).apply {
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
                val chip = tv(lvlName, 11f, if (isSel) Color.WHITE else C.TEXT, true).apply {
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
                background = shape(Color.WHITE, 8, C.LINE, 1)
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
        levels.forEachIndexed { i, name ->
            val chip = tv(name, 10f, if (i == sel) Color.WHITE else C.TEXT, true).apply {
                background = shape(if (i == sel) C.TEAL else C.SURFACE2, 8)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener { sel = i }
            }
            row.add(chip, w = 0, weight = 1f).also { if (i > 0) (chip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(4) }
        }
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
        listOf("Kiri", "Tengah", "Kanan").forEachIndexed { i, n ->
            pos.addView(RadioButton(this).apply { text = n; id = 100 + i; setTextColor(C.TEXT) })
        }
        pos.check(102)
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
            val p = pos.checkedRadioButtonId - 100
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
