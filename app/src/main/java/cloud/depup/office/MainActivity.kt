package cloud.depup.office

import android.annotation.SuppressLint
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
import android.view.ViewGroup
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CameraX sudah disiapkan di proses ini (lihat [MainActivity.warmCamera]). */
private var cameraWarm = false

class MainActivity : AppCompatActivity(), ThemeFade.Host {

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
        else if (r.resultCode == RESULT_OK && jpg > 0) toast("$jpg gambar disimpan di Documents/DepUp Office/Gambar")
    }
    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val files = r.data?.getStringArrayListExtra("files")
        if (r.resultCode == RESULT_OK && files != null && files.isNotEmpty()) {
            scanEdit.launch(Intent(this, ScanEditActivity::class.java)
                .putStringArrayListExtra("files", files)
                .putExtra("mode", r.data?.getIntExtra("mode", 0) ?: 0))
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
    private lateinit var tabBar: TabBar
    /** Layar pembuka sedang tampil: isi pertama belum dianimasikan (menunggu [intro]). */
    private var splashing = false
    private var tab = 0
    private var sv: ScrollView? = null
    private var renderedTab = -1
    private var query = ""
    private var glass: GlassScaffold? = null
    private var docsList: LinearLayout? = null
    private var docsMode = 0          // 0 terkini, 1 ditandai, 2 hasil olahan
    private var docsCat = "Semua"
    private var focusSearch = false
    private val tabNames = arrayOf("Beranda", "Dokumen", "Alat", "Saya")

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putInt("docsMode", docsMode)
        sv?.let { outState.putInt("scrollY", it.scrollY) }   // dibangun ulang (tema/efek kaca) → posisi gulir tetap
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: 0
        docsMode = savedInstanceState?.getInt("docsMode") ?: 0
        // Tombol Kembali di tab selain Beranda: pulang ke Beranda dulu; baru di Beranda tombol Kembali keluar aplikasi.
        onBackPressedDispatcher.addCallback(this, backToHome)
        prefetchSizes()
        restoreY = savedInstanceState?.getInt("scrollY", 0) ?: 0
        val root = buildUi()
        splashing = savedInstanceState == null && Motion.on()
        select(tab)
        freshlyBuilt = true

        if (savedInstanceState == null) {
            val splash = buildSplash()
            root.addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
            Theme.apply(this, if (C.dark) C.BG else C.PRIMARY)
            var gone = false
            val finishSplash = {
                if (!gone) {
                    gone = true
                    intro()   // pengaman bila animasi pembuka terlewat
                    root.removeView(splash)
                    Theme.apply(this)
                    // Latar jendela bawaan layar pembuka berwarna gelap; ganti ke latar aplikasi supaya area bilah
                    // status ikut terang dan tidak pernah terlihat hitam bila isi sedang digambar ulang.
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.BG))
                }
            }
            // layar pembuka memudar sementara isi beranda & navigasi naik bersama ke tempatnya (satu gerakan)
            splash.postDelayed({ intro(); splash.animate().alpha(0f).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).withEndAction { finishSplash() }.start() }, 550L)
            splash.postDelayed({ finishSplash() }, 1200L)   // pengaman bila animasi tidak selesai
        } else {
            Theme.apply(this)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.BG))
        }
    }

    /** Kerangka layar: bilah atas, wadah isi, navigasi bawah, dibungkus kaca. Dipakai onCreate & ganti tema. */
    private fun buildUi(): FrameLayout {
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
        val screen = Glass.scaffold(mainView, floatBottom = true)
        glass = screen as? GlassScaffold
        // Navigasi = kapsul liquid glass ala iOS 26 (terang di tema terang, gelap di tema gelap); panel tinta
        // kontras tinggi dipakai di isi halaman (kartu Scan & ubin angka).
        glass?.bottomBar?.apply { tintColor = { C.SURFACE }; tintAlpha = 232 }
        glass?.topBar?.fade = true   // bilah atas: blur progresif (makin atas makin buram), tanpa garis & lensa
        glass?.topBar?.fadeTail = 0  // bilah Beranda kosong: menipis sepanjang tinggi bilah (ekornya padding bilah)
        glass?.watchContent = true   // isi beranda tidak pernah kosong polos → ikut diawasi penjaga kaca
        root.addView(screen, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        return root
    }

    /**
     * Tema berganti (lihat [ThemeFade]): seluruh tampilan dibangun ulang DI TEMPAT dengan token warna baru, di balik
     * potret layar lama yang lalu memudar. Tanpa recreate: jendela, siklus hidup & state activity tetap — jauh lebih
     * cepat, dan tab serta posisi gulir tidak berubah.
     */
    override fun rebuildForTheme() {
        restoreY = sv?.scrollY ?: 0
        if (::content.isInitialized) content.removeCallbacks(prebuild)
        swapGen++; swapping = false; titleAnim = false; splashing = false
        for (i in pages.indices) { pages[i] = null; pageKey[i] = null }
        orphans.clear()
        sv = null; renderedTab = -1; docsList = null; docsSearch = null; continueKey = null; scrollRun = 0
        contentJob?.cancel(); contentJob = null
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.BG))
        buildUi()
        select(tab)
    }

    /**
     * Layar pembuka mulai memudar: isi beranda, kapsul navigasi & "Lanjutkan" naik ke tempatnya dalam SATU gerakan —
     * mulai bersamaan, berhenti bersamaan, satu kurva. Tidak ada yang bergelombang, memantul, atau menyusul.
     */
    private fun intro() {
        if (!splashing) return
        splashing = false
        if (!Motion.on()) return
        val dur = Motion.SLOW + 100L
        hold(dur + 600L)   // halaman tab lain disiapkan SETELAH animasi pembuka selesai
        (sv?.getChildAt(0))?.shiftIn(dyDp = 16, duration = dur)   // isi sudah utuh di balik layar pembuka, hanya bergeser naik
        listOfNotNull(glass?.bottomBar, glass?.miniBar?.takeIf { it.visibility == View.VISIBLE }).forEach { b ->
            b.animate().cancel()
            b.translationY = dp(24).toFloat()
            // kaca digambar ulang tiap frame selama naik: isi buram di dalamnya tetap selaras dengan layar
            b.animate().translationY(0f).setStartDelay(0).setDuration(dur).setInterpolator(Motion.STANDARD)
                .setUpdateListener { b.invalidate() }.start()
        }
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
        // ubin logo = ikon aplikasi (biru Depup + logo putih), jadi transisi dari ikon peluncur terasa menyambung
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_logo_tile); alpha = 0f; scaleX = 0.92f; scaleY = 0.92f }
        center.add(logo, w = dp(88), h = dp(88))
        // nama dengan gaya logo: huruf kecil, "office" abu (sama dengan logo pada paket logo)
        val wordmark = android.text.SpannableString("depup office").apply {
            setSpan(android.text.style.ForegroundColorSpan(if (C.dark) C.MUTED else 0xFFA9A6A0.toInt()), 6, 12, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val name = tv("", 28f, fg, true).apply { text = wordmark; contentDescription = "Depup Office"; gravity = Gravity.CENTER; alpha = 0f }
        center.add(name, top = 18, w = WRAP)
        val tag = tv("Kantor saku · 100% offline", 15f, fg).apply { gravity = Gravity.CENTER; alpha = 0f }
        center.add(tag, top = 4, w = WRAP)
        splash.addView(center, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        // satu irama: logo membesar sedikit sambil menebal, nama & tagline menebal bersama sesaat kemudian
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Motion.STANDARD).start()
        name.animate().alpha(1f).setStartDelay(80).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).start()
        tag.animate().alpha(0.9f).setStartDelay(80).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).start()
        return splash
    }

    /** Layar baru saja dibangun di onCreate -> onResume pertama tidak perlu membangun ulang semuanya. */
    private var freshlyBuilt = false

    override fun onPause() {
        super.onPause()
        contentJob?.cancel()   // pencarian isi berhenti saat layar lain (mis. penampil) dibuka; dilanjutkan saat kembali
    }

    override fun onResume() {
        super.onResume()
        window.decorView.removeCallbacks(draftCheck)
        window.decorView.postDelayed(draftCheck, 900L)   // setelah layar tenang & editor yang ditutup selesai
        if (freshlyBuilt) { freshlyBuilt = false; return }
        if (!::content.isInitialized) return
        prefetchSizes()   // berkas bisa berubah selama di layar lain (mis. disunting): ukurannya diperbarui di latar
        // Data bisa berubah selama di layar lain (berkas terbaru, tanda, hasil). Diperbarui SETELAH animasi kembali
        // selesai & layar diam, lalu diganti tanpa kedip — frame transisi tidak terbebani pembangunan halaman.
        for (t in 0..3) if (t != tab) pageKey[t] = null
        content.removeCallbacks(resumeRefresh)
        content.postDelayed(resumeRefresh, 420L)
    }

    /** Tawaran pemulihan sudah ditampilkan di layar ini ("Nanti" = tanya lagi saat aplikasi dibuka berikutnya). */
    private var draftAsked = false

    /**
     * Editor Word sempat tertutup (Android mematikan aplikasi / aplikasi diusap dari daftar terbaru) dengan perubahan
     * yang belum disimpan: tawarkan memulihkannya dari draf ([DocDrafts]).
     */
    private val draftCheck = Runnable {
        if (draftAsked || isFinishing || isDestroyed) return@Runnable
        // draf terbaru dari editor Word atau Excel
        val w = DocDrafts.latestOrphan(this)
        val x = SheetDrafts.latestOrphan(this)
        val sheet = x != null && (w == null || x.time > w.time)
        val d = (if (sheet) x else w) ?: return@Runnable
        draftAsked = true
        val whenTxt = SimpleDateFormat("d MMM, HH:mm", Locale("id")).format(Date(d.time))
        AlertDialog.Builder(this)
            .setTitle(if (sheet) "Pulihkan spreadsheet?" else "Pulihkan dokumen?")
            .setMessage("“${d.name}” punya perubahan yang belum disimpan (terakhir $whenTxt). Pulihkan dan lanjutkan mengedit?")
            .setPositiveButton("Pulihkan") { _, _ ->
                startActivity(
                    if (sheet) Intent(this, SheetEditorActivity::class.java).putExtra(SheetEditorActivity.EXTRA_DRAFT, d.id)
                    else Intent(this, DocEditorActivity::class.java).putExtra(DocEditorActivity.EXTRA_DRAFT, d.id)
                )
            }
            .setNegativeButton("Buang") { _, _ -> if (sheet) SheetDrafts.clear(this, d.id) else DocDrafts.clear(this, d.id) }
            .setNeutralButton("Nanti", null)
            .show()
    }

    /** Ukuran berkas di riwayat dibaca di thread latar, jadi daftar Dokumen dibangun tanpa menunggu penyedia berkas. */
    private fun prefetchSizes() = Files.prefetchSizes(this, Recent.load(this).map { it[0] })

    private val resumeRefresh: Runnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (busy()) { content.postDelayed(this, 300L); return }
            render()
        }
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BG)
            setPadding(dp(16), dp(6), dp(6), dp(6))
            minimumHeight = dp(60)
        }
        setBarCompact(bar)
        // Judul kecil di tengah: baru terlihat setelah judul besar halaman tergulir ke balik kaca.
        barTitle = tv("Beranda", 17f, C.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1; alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        // Judul kecil tidak ditampilkan: nama tab sudah ada di navigasi bawah. Tampilannya tetap ada (tersembunyi)
        // agar logika gulir & pergantian tab tidak berubah; bilah atas hanya berisi blur progresif.
        barTitle.visibility = View.GONE
        bar.add(barTitle, w = 0, weight = 1f, side = 0, h = dp(44))
        return bar
    }

    /** Teks angka di dalam ubin statistik (anak pertama baris atas). */
    private fun statValue(tile: View): TextView? =
        ((tile as? android.view.ViewGroup)?.getChildAt(0) as? android.view.ViewGroup)?.getChildAt(0) as? TextView

    private fun setBarCompact(bar: LinearLayout) {
        // ekor bawah 20dp: ruang bagi blur progresif untuk menipis perlahan (bukan berhenti mendadak di dasar judul)
        bar.setPadding(dp(16), dp(2), dp(16), dp(20))
        bar.minimumHeight = dp(48)
    }

    /** Kapsul "Lanjutkan": dokumen terakhir dibuka, melayang di atas navigasi. Ketuk untuk membukanya lagi. */
    private var continueKey: String? = null

    private fun refreshContinueBar() {
        val g = glass ?: return
        val e = Recent.load(this).firstOrNull()
        val key = e?.joinToString("\u0001")
        val shown = g.miniBar.visibility == View.VISIBLE
        if (key == continueKey && (e != null) == shown) return   // tidak berubah → tidak dibangun ulang
        continueKey = key
        if (e == null) { g.setAccessory(null); return }
        val (label, fg, _) = typeOf(e[2], e[1])
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(6), dp(6))
            minimumHeight = dp(56)
        }
        fun openIt() { try { Files.open(this, Uri.parse(e[0]), e[2]) } catch (_: Exception) { toast("File tidak lagi tersedia") } }
        row.tappable("Lanjutkan ${e[1]}, ${readableType(label)}") { openIt() }
        row.add(cover(fg, 0, null, 10).apply {
            addView(tv(label, 11f, fg, true).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(MATCH, MATCH))
        }, w = dp(44), h = dp(44))
        val col = vbox().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        col.add(tv(e[1], 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        val t = Files.relTime(e[3].toLongOrNull() ?: 0L)
        col.add(tv(if (t.isBlank()) "Lanjutkan" else "Lanjutkan • $t", 13f, C.MUTED).apply { maxLines = 1 }, top = 1)
        row.add(col, w = 0, weight = 1f, side = 10)
        if (e[2].contains("word") || e[2].contains("sheet") || e[1].endsWith(".docx", true) || e[1].endsWith(".xlsx", true))
            row.add(iconBtn(R.drawable.ic_note, "Edit ${e[1]}", C.TEXT, 22) { editDoc(e[0], e[1], e[2]) }, w = dp(44), h = dp(44))
        row.add(iconBtn(R.drawable.ic_next, "Buka ${e[1]}", C.TEXT, 22) { openIt() }, w = dp(44), h = dp(44))
        if (shown) g.miniBar.box.swapContent { g.setAccessory(row) } else g.setAccessory(row)
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
                val text = "Dukung pengembangan Depup Office\n\nDonasi via Saweria (GoPay, DANA, OVO, QRIS, dll):\n$saweriaUrl\n\nAplikasi office & scanner PDF 100% offline & bebas iklan. Terima kasih!"
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
                toast("QR disimpan ke Documents/DepUp Office/Gambar")
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
            }
            val dst = Files.insertGallery(contentResolver, cv) ?: throw java.io.IOException("Gagal menyimpan QR")
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
    /**
     * Navigasi bawah minimalis: kapsul putih melayang berisi ikon garis. Tab aktif = pil tinta (hitam) yang
     * memuat ikon + nama; tab lain hanya ikon abu (nama tetap dibacakan TalkBack).
     */
    private fun bottomNav(): View {
        // Pil tinta meluncur cair antar-tab; nama tab membuka/menutup halus; Scan = lingkaran tinta (aksi, bukan tab).
        val nav = TabBar(this).apply {
            setBackgroundColor(C.SURFACE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        tabBar = nav
        val items = listOf(
            Triple(0, R.drawable.ic_home, "Beranda"), Triple(1, R.drawable.ic_folder, "Dokumen"),
            Triple(-1, R.drawable.ic_scan, "Scan"), Triple(2, R.drawable.ic_tools, "Alat"), Triple(3, R.drawable.ic_user, "Saya"))
        items.forEachIndexed { k, (idx, res, label) ->
            if (k > 0) nav.gap()
            if (idx < 0) nav.addAction(res, "Scan dokumen dengan kamera") { startScan() }
            else nav.addTab(idx, res, label) {
                if (idx == tab) { sv?.smoothScrollTo(0, 0); nav.beat() }   // tab yang sedang dibuka: kembali ke atas
                else select(idx)
            }
        }
        nav.onTabSelected = { idx ->
            if (idx != tab) select(idx)
        }
        return nav
    }

    /** Aktif hanya saat tab bukan Beranda (lihat [select]); di Beranda tombol Kembali ditangani sistem (keluar). */
    private val backToHome = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { select(0) }
    }

    private fun select(i: Int) {
        backToHome.isEnabled = i != 0
        val changed = i != tab
        if (changed) glass?.setMinimized(false)   // pindah tab: navigasi kembali penuh
        if (changed && ::content.isInitialized) content.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        tab = i
        tabBar.select(i, changed)
        switchTo(i, changed)
        setBarTitle(tabNames[i], sv?.let { barAlpha(it.scrollY) } ?: 0f, changed)
    }

    private var titleAnim = false

    /** Jarak gulir searah yang terkumpul (px; + ke bawah, − ke atas) untuk navigasi diperkecil ala iOS 26. */
    private var scrollRun = 0

    /**
     * Gulir ke bawah ±28 dp → navigasi diperkecil (tab aktif di lingkaran kiri, "Lanjutkan" turun ke sebelahnya);
     * gulir ke atas ±28 dp atau kembali dekat puncak → penuh lagi. Arah dihitung terkumpul agar getaran jari kecil
     * tidak membuatnya berkedip.
     */
    private fun minimizeOnScroll(y: Int, oy: Int) {
        val g = glass ?: return
        val dy = y - oy
        if (dy == 0) return
        if ((dy > 0) != (scrollRun > 0)) scrollRun = 0
        scrollRun += dy
        when {
            y < dp(48) -> g.setMinimized(false)
            scrollRun > dp(28) -> g.setMinimized(true)
            scrollRun < -dp(28) -> g.setMinimized(false)
        }
    }

    /**
     * Judul kecil di bilah atas: berganti tanpa lompatan. Bila sedang tampak, judul lama memudar dulu lalu judul baru
     * muncul ke tingkat yang sesuai posisi gulir halaman tujuan.
     */
    private fun setBarTitle(text: String, target: Float, animate: Boolean) {
        barTitle.animate().cancel()
        if (!animate || !Motion.on()) { barTitle.text = text; barTitle.alpha = target; titleAnim = false; return }
        barTitle.noLayer()
        titleAnim = true
        val done = Runnable { titleAnim = false }
        // total selalu = durasi larut halaman (MED): judul lama pergi 100 ms, judul baru datang 150 ms
        if (barTitle.text != text && barTitle.alpha > 0.01f) {
            barTitle.animate().alpha(0f).setStartDelay(0).setDuration(100L).setInterpolator(Motion.STANDARD).withEndAction {
                barTitle.text = text
                barTitle.animate().alpha(target).setStartDelay(0).setDuration(Motion.FAST).setInterpolator(Motion.STANDARD).withEndAction(done).start()
            }.start()
        } else {
            barTitle.text = text
            barTitle.animate().alpha(target).setStartDelay(0).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).withEndAction(done).start()
        }
    }

    // ================= HALAMAN TAB (disimpan & dipakai ulang) =================
    /*
     * Tiap tab punya halaman sendiri yang dibangun SEKALI lalu disimpan (tersembunyi) di wadah isi. Pindah tab hanya
     * menampilkan halaman yang sudah siap — tidak ada ratusan tampilan dibangun di tengah animasi, jadi geraknya
     * tetap mulus. Halaman yang datanya mungkin berubah ditandai basi dan dibangun ulang diam-diam saat aplikasi
     * menganggur (tidak sedang disentuh, digulir, atau beranimasi).
     */
    private val pages = arrayOfNulls<ScrollView>(4)
    private val pageKey = arrayOfNulls<String>(4)
    private val orphans = ArrayList<ScrollView>()   // versi lama halaman yang sedang memudar keluar
    private var swapGen = 0
    private var swapping = false
    private var lastTouch = 0L
    private var lastScroll = 0L
    /** Sampai kapan animasi besar sedang berjalan (pekerjaan berat ditunda agar tidak menyendat). */
    private var holdUntil = 0L
    private var restoreY = 0
    private var docsSearch: EditText? = null

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        lastTouch = android.os.SystemClock.uptimeMillis()
        LaunchFrom.record(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Layar DepUp yang dibuka dari ketukan tumbuh dari elemen yang diketuk ([LaunchFrom]). */
    override fun startActivity(intent: Intent, options: Bundle?) {
        super.startActivity(intent, options ?: LaunchFrom.options(this, intent))
    }

    /** Pengguna sedang menyentuh/menggulir atau transisi berjalan → tunda pekerjaan berat. */
    private fun busy(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        return swapping || now < holdUntil || now - maxOf(lastTouch, lastScroll) < 600L
    }

    private fun hold(ms: Long) { holdUntil = maxOf(holdUntil, android.os.SystemClock.uptimeMillis() + ms) }

    private fun keyFor(t: Int) = if (t == 1) "$docsMode/$docsCat" else ""
    private fun fresh(t: Int) = pages[t] != null && pageKey[t] == keyFor(t)
    private fun barAlpha(y: Int) = ((y - dp(26)) / dp(22).toFloat()).coerceIn(0f, 1f)

    /** Bangun halaman tab [t] (tanpa animasi di dalamnya). */
    private fun buildPage(t: Int): ScrollView {
        val quiet = Motion.quiet
        Motion.quiet = true
        try {
            val page = vbox().apply { setPadding(dp(20), dp(4), dp(20), dp(40)); clipChildren = false; clipToPadding = false }
            // Judul besar ala pustaka (Beranda diawali tanggal). Dibungkus satu wadah: wadahnya ikut gerak halaman,
            // isinya dipakai efek menyusut saat digulir — keduanya tidak bentrok.
            val head = vbox()
            // Beranda: logo Depup sebaris dengan salam di paling atas, lalu "mau kerjakan apa?", lalu tanggal
            val big: View = if (t == 0) vbox().apply {
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.add(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_logo_tile); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    w = dp(34), h = dp(34))
                row.add(largeTitle("${greeting()},").apply { maxLines = 1 }, w = 0, weight = 1f, side = 12)
                add(row)
                add(largeTitle("mau kerjakan apa?").apply { setTextColor(C.MUTED) }, top = 2)
            } else largeTitle(tabNames[t])
            head.add(big)
            val date: View? = if (t == 0) tv(java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale("id")).format(java.util.Date()), 14f, C.MUTED)
                .also { head.add(it, top = 6) } else null
            page.add(head, top = 4)
            when (t) {
                0 -> buildHome(page)
                1 -> buildDocs(page)
                2 -> buildTools(page)
                else -> buildMe(page)
            }
            val scroller = ScrollView(this).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setBackgroundColor(C.BG)   // buram: saat memudar masuk, halaman baru menutupi halaman lama (tanpa layar kosong)
                outlineProvider = null     // tanpa bayangan ketika dinaikkan di atas halaman lain
                addView(page)
            }
            // Saat digulir: judul besar menyusut & memudar ke arah bilah, judul kecil di bilah kaca menggantikannya.
            big.noLayer(); date?.noLayer()
            val shrink = dp(72).toFloat()
            scroller.setOnScrollChangeListener { _, _, y, _, oy ->
                lastScroll = android.os.SystemClock.uptimeMillis()
                if (sv === scroller && !swapping) { barTitle.alpha = barAlpha(y); minimizeOnScroll(y, oy) }
                if (y != oy) SwipeRow.closeAll()
                val f = (y / shrink).coerceIn(0f, 1f)
                big.pivotX = 0f; big.pivotY = big.height.toFloat()
                big.alpha = 1f - 0.8f * f
                big.scaleX = 1f - 0.07f * f; big.scaleY = big.scaleX
                date?.alpha = 1f - f
            }
            pageKey[t] = keyFor(t)
            return scroller
        } finally { Motion.quiet = quiet }
    }

    /** Simpan halaman [s] untuk tab [t] di lapisan paling bawah wadah isi (tidak pernah menghalangi sentuhan). */
    private fun install(t: Int, s: ScrollView, shown: Boolean) {
        val prev = pages[t]
        pages[t] = s
        s.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        content.addView(s, 0)
        glass?.pin(s)
        if (prev != null && prev !== sv) drop(prev)
    }

    private fun drop(p: ScrollView) {
        p.animate().cancel()
        glass?.unpin(p)
        content.removeView(p)
    }

    /**
     * Tampilkan halaman tab [i]. Transisi "larut": halaman tujuan (berlatar buram) menebal di atas halaman asal
     * sementara seluruh isinya bergeser sedikit dari arah tab tujuan sebagai SATU blok — searah & seirama dengan pil
     * navigasi (kurva & durasi sama). Halaman asal diam di bawahnya lalu disembunyikan. Tidak pernah ada frame yang
     * kosong/gelap, tidak ada yang bergelombang, dan tidak ada yang dibangun di tengah animasi.
     */
    private fun switchTo(i: Int, animate: Boolean) {
        val from = sv
        if (from != null && from === pages[i]) {            // tab yang sama
            if (!fresh(i)) render()
            afterShow(i)
            return
        }
        if (!fresh(i)) install(i, buildPage(i), shown = false)
        val to = pages[i] ?: return
        if (from == null) {
            if (restoreY > 0) { val y = restoreY; restoreY = 0; to.beforeDraw { to.scrollTo(0, y) } }
            refreshContinueBar()
        }
        sv = to
        val dir = if (i > renderedTab) 1 else -1
        renderedTab = i
        val gen = ++swapGen
        if (animate && from != null && Motion.on() && !splashing) {
            swapping = true
            hold(Motion.SPRING_SOFT_MS + Motion.CASCADE_MAX * Motion.CASCADE_STEP + 250L)
            if (to.visibility == View.VISIBLE && from.alpha < 1f) {
                // berbalik di tengah transisi: halaman yang sedang menebal memudar kembali, tujuan sudah ada di bawahnya
                from.animate().alpha(0f).setStartDelay(0).setDuration(Motion.FAST).setInterpolator(Motion.STANDARD).setUpdateListener(null)
                    .withEndAction { if (gen == swapGen) settlePages() }.start()
                (to.getChildAt(0) as? ViewGroup)?.let { pg -> pg.animate().cancel(); pg.animate().translationX(0f).setStartDelay(0)
                    .setDuration(Motion.MED).setInterpolator(Motion.STANDARD).setUpdateListener(null).start() }
            } else {
                for (p in pages) if (p != null) p.translationZ = 0f
                from.translationZ = 1f; to.translationZ = 2f   // tujuan di atas, asal tepat di bawahnya (diam)
                to.noLayer()
                to.animate().cancel()
                to.alpha = 0f
                to.visibility = View.VISIBLE
                to.animate().alpha(1f).setStartDelay(0).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).setUpdateListener(null)
                    .withEndAction { if (gen == swapGen) settlePages() }.start()
                // bagian-bagian halaman tujuan masuk BERGELOMBANG dari arah tab tujuan (pegas lembut), seirama lensa tab
                (to.getChildAt(0) as? ViewGroup)?.let { pg ->
                    pg.animate().cancel()
                    pg.translationX = 0f
                    pg.cascadeIn(dyDp = 4, dxDp = dir * (Motion.SHIFT_DP + 8))
                }
            }
        } else settlePages()
        afterShow(i)
    }

    /** Akhir transisi: hanya halaman aktif yang tampil; lapisan, transparansi & posisi kembali normal. */
    private fun settlePages() {
        swapping = false
        val cur = sv
        for (p in pages) {
            if (p == null) continue
            p.animate().cancel()
            p.translationZ = 0f
            p.alpha = 1f
            if (p !== cur) {
                (p.getChildAt(0) as? ViewGroup)?.let { pg -> pg.animate().cancel(); pg.translationX = 0f }
                p.visibility = View.INVISIBLE
            } else p.visibility = View.VISIBLE
        }
        for (o in orphans) drop(o)
        orphans.clear()
        if (!titleAnim) cur?.let { barTitle.alpha = barAlpha(it.scrollY) }
    }

    private fun afterShow(i: Int) {
        if (focusSearch && i == 1) {
            focusSearch = false
            docsSearch?.let { s -> s.post {
                s.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.showSoftInput(s, 0)
            } }
        }
        glass?.refresh()
        GlassGuard.poke()
        schedulePrebuild()
    }

    /**
     * Data halaman yang sedang tampil berubah: bangun ulang & gantikan tanpa kedip (versi baru menebal di atas versi
     * lama, posisi gulir — termasuk korsel — dipertahankan). Halaman tab lain ditandai basi.
     */
    private fun render() {
        if (!::content.isInitialized) return
        val t = tab
        val old = pages[t]
        val s = buildPage(t)
        pages[t] = s
        content.addView(s)          // di atas versi lama: sentuhan langsung ke versi baru
        glass?.pin(s)
        s.visibility = View.VISIBLE
        old?.let { o ->
            val y = o.scrollY
            if (y > 0) s.beforeDraw { s.scrollTo(0, y) }
            keepCarousels(o, s)
        }
        sv = s; renderedTab = t
        if (old != null && old.parent === content && old.visibility == View.VISIBLE && Motion.on() && !splashing) {
            hold(450L)
            run {
                orphans.add(old)
                val gen = swapGen
                s.noLayer(); s.alpha = 0f
                s.animate().alpha(1f).setStartDelay(0).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).setUpdateListener(null)
                    .withEndAction { if (gen == swapGen) { orphans.remove(old); drop(old) } }.start()
            }
        } else old?.let { drop(it) }
        for (k in 0..3) if (k != t) pageKey[k] = null
        refreshContinueBar()
        glass?.refresh()
        schedulePrebuild()
    }

    /** Posisi gulir korsel mendatar ikut dipindah ke versi baru halaman (tidak melompat ke awal). */
    private fun keepCarousels(from: View, to: View) {
        fun collect(v: View, out: MutableList<android.widget.HorizontalScrollView>) {
            if (v is android.widget.HorizontalScrollView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
        }
        val a = ArrayList<android.widget.HorizontalScrollView>(); collect(from, a)
        val b = ArrayList<android.widget.HorizontalScrollView>(); collect(to, b)
        if (a.size != b.size) return
        for (k in a.indices) { val x = a[k].scrollX; if (x > 0) b[k].let { h -> h.beforeDraw { h.scrollTo(x, 0) } } }
    }

    private val prebuild = Runnable { prebuildNext() }

    private fun schedulePrebuild(delay: Long = 700L) {
        if (!::content.isInitialized) return
        content.removeCallbacks(prebuild)
        content.postDelayed(prebuild, delay)
    }

    /** Saat menganggur: siapkan satu halaman tab lain yang belum ada/basi (satu per giliran — tidak menyendat). */
    private fun prebuildNext() {
        if (isFinishing || isDestroyed) return
        if (splashing || busy()) { schedulePrebuild(450L); return }
        val t = (0..3).firstOrNull { it != tab && !fresh(it) } ?: run { warmCamera(); return }
        val s = buildPage(t)
        install(t, s, shown = true)
        // tampil transparan sekejap agar tampilannya sudah "terekam" — saat pertama ditampilkan tidak menyendat
        s.alpha = 0f
        s.postOnAnimation { s.postOnAnimation { if (sv !== s && pages[t] === s && !swapping) { s.visibility = View.INVISIBLE; s.alpha = 1f } } }
        schedulePrebuild(240L)
    }

    /**
     * Saat beranda menganggur (semua tab sudah disiapkan), CameraX diinisialisasi di latar SEKALI per proses: layar
     * pindai — fitur utama — terbuka lebih cepat. Kamera TIDAK dibuka (tanpa indikator kamera), hanya daftar kameranya.
     */
    private fun warmCamera() {
        if (cameraWarm) return
        cameraWarm = true
        try { androidx.camera.lifecycle.ProcessCameraProvider.getInstance(applicationContext) } catch (_: Throwable) {}
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
        // sel berkelompok ala iOS: satu kartu bersudut kontinu, pemisah tipis mulai sejajar teks (bukan ikon)
        background = squircle(C.SURFACE, 22)
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        rows.forEachIndexed { i, r ->
            // baris geser: jarak dalam untuk isinya — tombol aksinya tetap selebar kartu
            val pr = (r as? SwipeRow)?.content ?: r
            pr.setPadding(dp(14), pr.paddingTop, pr.paddingRight.coerceAtMost(dp(8)), pr.paddingBottom)
            if (i > 0) add(View(context).apply { setBackgroundColor(C.LINE) }, h = 1, side = 0).also {
                (getChildAt(childCount - 1).layoutParams as LinearLayout.LayoutParams).marginStart = dp(66)
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
        val size = Files.sizeCached(this, Uri.parse(e[0]))   // ukuran sudah disiapkan di latar (lihat onCreate/onResume)
        return listOf(if (size >= 0) Files.fmtSize(size) else "", Files.relTime(e[3].toLongOrNull() ?: 0L))
            .filter { it.isNotBlank() }.joinToString(" • ")
    }

    private fun showRenameFileDialog(uriStr: String, currentName: String) =
        renameFlow(Uri.parse(uriStr), currentName) { render() }

    /** Lencana jenis berkas (PDF/DOCX/XLSX/IMG): huruf tebal di wadah warna kategori. */
    private fun typeBadge(label: String, fg: Int, bg: Int): View =
        tv(label, 11f, fg, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
            background = squircle(C.SOFT, 12)
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
        val sw = SwipeRow(this, r)
        fun openIt() {
            noteSearch()
            try { Files.open(this, Uri.parse(uriStr), e[2]) } catch (_: Exception) { toast("File tidak lagi tersedia") }
        }
        fun share() = try { Files.share(this, Uri.parse(uriStr), e[2], false) } catch (_: Exception) { toast("Tidak bisa dibagikan") }
        fun forget() = sw.collapseOut { Recent.remove(this, uriStr); toast("Dihapus dari riwayat"); render() }
        val meta = metaLine(e)
        r.tappable("${e[1]}, ${readableType(label)}${if (meta.isBlank()) "" else ", $meta"}. Ketuk untuk membuka") { openIt() }
        r.add(typeBadge(label, fg, bg), w = dp(40), h = dp(40))
        val col = vbox().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        col.add(tv(e[1], 15f, C.TEXT, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        val (metaRow, pinIc) = metaWithPin(if (meta.isBlank()) readableType(label) else meta, Meta.isPinned(this, uriStr))
        col.add(metaRow, top = 2)
        r.add(col, w = 0, weight = 1f, side = 12)
        val marked = Meta.isMarked(this, uriStr)
        lateinit var bm: ImageView
        bm = iconBtn(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off,
            if (marked) "Hapus tanda ${e[1]}" else "Tandai ${e[1]}", if (marked) C.PRIMARY else C.MUTED, 22) {
            val on = Meta.toggleMark(this, uriStr)
            haptic(bm)
            // tanda: membesar sedikit lalu kembali mulus (tanpa pantulan)
            if (Motion.on()) bm.animate().scaleX(1.15f).scaleY(1.15f).setStartDelay(0).setDuration(Motion.PRESS_DOWN)
                .setInterpolator(Motion.STANDARD).withEndAction {
                    bm.animate().scaleX(1f).scaleY(1f).setDuration(Motion.PRESS_UP).setInterpolator(Motion.STANDARD).start()
                }.start()
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
            pm.menu.add(0, 7, 4, if (Meta.isPinned(this, uriStr)) "Lepas dari Beranda" else "Sematkan di Beranda")
            pm.menu.add(0, 6, 5, "Temukan di File Manager")
            pm.menu.add(0, 5, 6, "Hapus dari riwayat")
            pm.setOnMenuItemClickListener { m ->
                when (m.itemId) {
                    1 -> openIt()
                    2 -> editDoc(uriStr, e[1], e[2])
                    3 -> showRenameFileDialog(uriStr, e[1])
                    4 -> share()
                    7 -> togglePin(uriStr, e[1], e[2], pinIc, sw)
                    6 -> Files.showInFileManager(this, Uri.parse(uriStr))
                    else -> forget()
                }
                true
            }
            pm.show()
        }, w = dp(48), h = dp(48))
        // geser ala iOS: ke kanan = sematkan, ke kiri = bagikan / ubah nama / hapus dari riwayat
        sw.leading = listOf(pinAction(uriStr, e[1], e[2], pinIc, sw))
        sw.trailing = listOf(
            SwipeRow.Action("Bagikan", R.drawable.ic_share, SwipeRow.BLUE) { share() },
            SwipeRow.Action("Ubah nama", R.drawable.ic_edit, SwipeRow.GRAY) { showRenameFileDialog(uriStr, e[1]) },
            SwipeRow.Action("Hapus", R.drawable.ic_trash, SwipeRow.RED, "Hapus dari riwayat", removes = true) { forget() })
        return sw
    }

    /** Kartu ringkas di deret "Disematkan": ketuk = buka, tahan = opsi (bagikan / lepas). */
    private fun pinCard(e: List<String>): View {
        val (label, fg, bg) = typeOf(e[2], e[1])
        fun openIt() = try { Files.open(this, Uri.parse(e[0]), e[2]) } catch (_: Exception) { toast("File tidak lagi tersedia") }
        fun unpin() { Meta.unpin(this, e[0]); toast("Dilepas dari Beranda"); pinsChanged() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), squircle(C.SURFACE, 18), squircle(android.graphics.Color.WHITE, 18))
            setPadding(dp(10), dp(10), dp(2), dp(10))
            minimumHeight = dp(64)
            isClickable = true; isFocusable = true
            contentDescription = "${e[1]}, ${readableType(label)}, disematkan. Ketuk untuk membuka, tahan untuk opsi lain"
            setOnClickListener { haptic(this); openIt() }
            setOnLongClickListener { v ->
                haptic(v)
                val pm = android.widget.PopupMenu(this@MainActivity, v)
                pm.menu.add(0, 1, 0, "Buka")
                pm.menu.add(0, 2, 1, "Bagikan")
                pm.menu.add(0, 3, 2, "Lepas dari Beranda")
                pm.setOnMenuItemClickListener { m ->
                    when (m.itemId) {
                        1 -> openIt()
                        2 -> try { Files.share(this@MainActivity, Uri.parse(e[0]), e[2], false) } catch (_: Exception) { toast("Tidak bisa dibagikan") }
                        else -> unpin()
                    }
                    true
                }
                pm.show()
                true
            }
            add(typeBadge(label, fg, bg), w = dp(36), h = dp(36))
            add(tv(e[1], 14f, C.TEXT, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, w = dp(116), side = 10)
            springPress(0.97f)
        }
        androidx.core.view.ViewCompat.addAccessibilityAction(card, "Lepas dari Beranda") { _, _ -> unpin(); true }
        return card
    }

    /** Warna pin (penanda "disematkan") — oranye yang tetap kontras di tema terang & gelap. */
    private val pinColor get() = if (C.dark) 0xFFFF9F0A.toInt() else SwipeRow.ORANGE

    /** Keterangan baris dengan ikon pin kecil di depannya (tampak bila berkas disematkan di Beranda). */
    private fun metaWithPin(text: String, pinned: Boolean): Pair<View, View> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ic = icon(R.drawable.ic_pin, pinColor, 13).apply { visibility = if (pinned) View.VISIBLE else View.GONE }
        row.add(ic, w = dp(13), h = dp(13), side = 0)
        (ic.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4)
        row.add(tv(text, 13f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, w = 0, weight = 1f, side = 0)
        return row to ic
    }

    private fun pinAction(uri: String, name: String, mime: String, pinIc: View?, sw: SwipeRow): SwipeRow.Action {
        val on = Meta.isPinned(this, uri)
        return SwipeRow.Action(if (on) "Lepas" else "Sematkan", R.drawable.ic_pin, SwipeRow.ORANGE,
            if (on) "Lepas dari Beranda" else "Sematkan di Beranda") { togglePin(uri, name, mime, pinIc, sw) }
    }

    /** Sematkan / lepas berkas dari deret "Disematkan" di Beranda; baris di daftar diperbarui di tempat. */
    private fun togglePin(uri: String, name: String, mime: String, pinIc: View?, sw: SwipeRow?) {
        val on = Meta.togglePin(this, uri, name, mime)
        toast(if (on) "Disematkan di Beranda" else "Dilepas dari Beranda")
        pinIc?.let { ic ->
            if (on) { ic.visibility = View.VISIBLE; ic.scaleX = 0.4f; ic.scaleY = 0.4f; ic.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).start() }
            else ic.visibility = View.GONE
        }
        // label tombol geser berganti setelah barisnya selesai menutup
        sw?.let { s -> s.postDelayed({ s.leading = listOf(pinAction(uri, name, mime, pinIc, s)) }, Motion.MED + 90L) }
        pinsChanged()
    }

    /** Sematan berubah: deret "Disematkan" di Beranda ikut diperbarui. */
    private fun pinsChanged() {
        if (tab == 0) render() else { pageKey[0] = null; schedulePrebuild() }
    }

    /** Berkas dibuka dari hasil pencarian → kata kuncinya masuk "Pencarian terakhir". */
    private fun noteSearch() { if (tab == 1 && query.isNotBlank()) Meta.addSearch(this, query) }

    private fun hideIme(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun readableType(label: String) = when (label) {
        "PDF" -> "Dokumen PDF"; "DOCX" -> "Dokumen Word"; "XLSX" -> "Spreadsheet Excel"; "IMG" -> "Gambar"; else -> "Berkas $label"
    }

    /** Saring jenis daftar Dokumen (chip → kode [typeOf]); satu pilihan [docsCat] untuk ketiga segmen. */
    private val cats = listOf("Semua" to "", "PDF" to "PDF", "Word" to "DOCX", "Excel" to "XLSX", "Gambar" to "IMG")
    private fun catCode() = cats.firstOrNull { it.first == docsCat }?.second.orEmpty()

    /** Chip jenis di atas daftar; [codes] = jenis tiap berkas (untuk hitungannya). */
    private fun catChips(box: LinearLayout, codes: List<String>, keepX: Int) {
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cats.forEach { (name, code) ->
            val n = if (code.isEmpty()) codes.size else codes.count { it == code }
            chips.add(filterChip("$name ($n)", docsCat == name) {
                if (docsCat != name) { docsCat = name; pageKey[1] = keyFor(1); box.swapContent { fillDocs(box) } }
            }, w = WRAP, side = 0)
                .also { (chips.getChildAt(chips.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        }
        val chipScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        box.add(chipScroll)
        if (keepX > 0) chipScroll.beforeDraw { chipScroll.scrollTo(keepX, 0) }
    }

    /** Isi daftar tab Dokumen sesuai segmen, jenis & kata kunci — lalu cari juga di ISI dokumen bila sedang mencari. */
    private fun fillDocs(box: LinearLayout) {
        contentJob?.cancel(); contentJob = null
        val keepX = (box.getChildAt(0) as? android.widget.HorizontalScrollView)?.scrollX ?: 0
        val named = when (docsMode) {
            0 -> fillRecent(box, 60, query, keepX = keepX)
            1 -> fillRecent(box, 60, query, markedOnly = true, keepX = keepX)
            else -> fillOutputs(box, keepX)
        }
        contentSearch(box, named)
    }

    /**
     * Jumlah berkas yang NAMANYA cocok; -1 = keadaan kosong sudah ditampilkan. 0 = tidak ada yang cocok namanya —
     * keadaan kosongnya diputuskan pencarian isi ([contentSearch]).
     */
    private fun fillRecent(box: LinearLayout, max: Int, filter: String = "", markedOnly: Boolean = false, keepX: Int = 0): Int {
        box.removeAllViews()
        val all = Recent.load(this)
        val marks = Meta.marks(this)
        val q = filter.trim()
        val scope = all.filter { !markedOnly || marks.contains(it[0]) }
        if (scope.isNotEmpty()) catChips(box, scope.map { typeOf(it[2], it[1]).first }, keepX)
        val code = catCode()
        val list = scope.filter { code.isEmpty() || typeOf(it[2], it[1]).first == code }
            .filter { q.isEmpty() || it[1].contains(q, ignoreCase = true) }.take(max)
        val top = if (scope.isNotEmpty()) 12 else 0
        if (list.isNotEmpty()) { box.add(listCard(list.map { fileRow(it) }), top = top); return list.size }
        if (q.length >= 2 && scope.isNotEmpty()) return 0
        box.add(when {
            markedOnly && scope.isEmpty() -> emptyState(R.drawable.ic_bookmark_off, "Belum ada yang ditandai",
                "Ketuk ikon penanda di samping berkas agar mudah ditemukan lagi.")
            all.isEmpty() -> emptyState(R.drawable.ic_folder, "Belum ada berkas",
                "Berkas PDF, Word, dan Excel yang Anda buka akan muncul di sini.", "Buka Berkas") { openAny() }
            q.isEmpty() -> emptyState(R.drawable.ic_search, "Belum ada berkas $docsCat", "Tidak ada berkas $docsCat di daftar ini.")
            else -> emptyState(R.drawable.ic_search, "Tidak ditemukan", "Tidak ada berkas yang cocok dengan “$q”.")
        }, top = top)
        return -1
    }

    private var contentJob: DocSearch.Job? = null

    /**
     * Cari juga di ISI dokumen (PDF, Word, Excel, foto & pindaian) pada segmen yang tampil. Hasil muncul bertahap di
     * bawah hasil nama (berkas yang namanya sudah cocok tidak diulang), lengkap dengan cuplikan kalimatnya.
     */
    private fun contentSearch(box: LinearLayout, named: Int) {
        val q = query.trim()
        if (named < 0 || q.length < 2) return
        val code = catCode()
        val items = if (docsMode == 2) {
            outFiles().filter { (code.isEmpty() || catOf(it) == code) && !it.name.contains(q, ignoreCase = true) }
                .map { DocSearch.Item(Files.uriFor(this, it).toString(), it.name, Files.byName(it.name), it) }
        } else {
            val marks = Meta.marks(this)
            Recent.load(this).filter { (docsMode == 0 || marks.contains(it[0])) && (code.isEmpty() || typeOf(it[2], it[1]).first == code) &&
                !it[1].contains(q, ignoreCase = true) }.map { DocSearch.Item(it[0], it[1], it[2]) }
        }
        fun notFound() = emptyState(R.drawable.ic_search, "Tidak ditemukan", "Tidak ada berkas yang nama atau isinya cocok dengan “$q”.")
        if (items.isEmpty()) { if (named == 0) box.add(notFound(), top = 12); return }
        val sec = vbox()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.add(tv("Di dalam dokumen", 13f, C.MUTED, true), w = 0, weight = 1f, side = 0)
        val spin = android.widget.ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(C.MUTED) }
        head.add(spin, w = dp(16), h = dp(16))
        sec.add(head)
        val rows = vbox()
        sec.add(rows, top = 8)
        rows.add(tv("Mencari di isi dokumen…", 14f, C.MUTED))
        box.add(sec, top = if (named > 0) 22 else 14)
        var shown = false
        contentJob = DocSearch.search(this, items, q) { hits, done ->
            if (done) spin.visibility = View.GONE
            when {
                hits.isNotEmpty() -> {
                    rows.removeAllViews()
                    val card = listCard(hits.map { hitRow(it) })
                    rows.add(card)
                    if (!shown) { shown = true; if (Motion.on()) { card.alpha = 0f; card.animate().alpha(1f).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).start() } }
                }
                !done -> {}
                named == 0 -> { sec.removeAllViews(); sec.add(notFound()) }
                else -> sec.showIf(false)
            }
        }
    }

    /** Hasil dari isi dokumen: nama + cuplikan kalimat dengan kata yang dicari ditebalkan & disorot. */
    private fun hitRow(h: DocSearch.Hit): View {
        val item = h.item
        val (label, fg, bg) = typeOf(item.mime, item.name)
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            minimumHeight = dp(68)
        }
        r.tappable("${item.name}, ${readableType(label)}. Berisi: ${h.snippet}. Ketuk untuk membuka") {
            noteSearch()
            try { Files.open(this, Uri.parse(item.uri), item.mime) } catch (_: Exception) { toast("File tidak lagi tersedia") }
        }
        r.add(typeBadge(label, fg, bg), w = dp(40), h = dp(40))
        val col = vbox().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        col.add(tv(item.name, 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        val sn = android.text.SpannableString(h.snippet).apply {
            val f = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), h.start, h.end, f)
            setSpan(android.text.style.ForegroundColorSpan(C.TEXT), h.start, h.end, f)
            setSpan(android.text.style.BackgroundColorSpan(C.WARN_C), h.start, h.end, f)
        }
        col.add(tv("", 13f, C.MUTED).apply { text = sn; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, top = 2)
        r.add(col, w = 0, weight = 1f, side = 12)
        return r
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
        fun bleed(v: View) { (v.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = -dp(20); rightMargin = -dp(20) } }

        // Kolom cari (membuka tab Dokumen) — ukuran sentuh penuh 52dp.
        val search = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), shape(C.SURFACE, 100, C.LINE, 1), null)
            setPadding(dp(18), 0, dp(18), 0)
            minimumHeight = dp(52)
            isClickable = true; isFocusable = true
            contentDescription = "Cari dokumen"
            setOnClickListener { openSearch() }
            add(icon(R.drawable.ic_search, C.MUTED, 20), w = dp(20), h = dp(20))
            add(tv("Cari dokumen…", 15f, C.MUTED), w = 0, weight = 1f, side = 12)
        }
        p.add(search, top = 12)

        // Disematkan: berkas pilihan pengguna selalu di atas (di tab Dokumen: geser baris ke kanan / menu ⋮ → Sematkan)
        val pins = Meta.pins(this)
        if (pins.isNotEmpty()) {
            p.add(sectionTitle("Disematkan"), top = 18)
            val c = carousel(pins.map { pinCard(it) })
            p.add(c, top = 6); bleed(c)
        }

        // Baru dibuka: korsel sampul besar, seperti deretan "baru diputar"
        val recents = Recent.load(this)
        if (recents.isNotEmpty()) {
            p.add(sectionTitle("Baru dibuka", if (recents.size > 3) "Semua" else null) { docsMode = 0; select(1) }, top = 18)
            val cards = recents.take(8).map { e ->
                val (label, fg, _) = typeOf(e[2], e[1])
                val ic = when (label) { "PDF" -> R.drawable.ic_pdf; "DOCX" -> R.drawable.ic_doc; "XLSX" -> R.drawable.ic_sheet; "IMG" -> R.drawable.ic_image; else -> R.drawable.ic_folder }
                val meta = Files.relTime(e[3].toLongOrNull() ?: 0L)
                coverCard(cover(fg, ic, label), e[1], if (meta.isBlank()) readableType(label) else meta, 156,
                    "${e[1]}, ${readableType(label)}. Ketuk untuk membuka") {
                    try { Files.open(this, Uri.parse(e[0]), e[2]) } catch (_: Exception) { toast("File tidak lagi tersedia") }
                }
            }
            val c = carousel(cards)
            p.add(c, top = 6); bleed(c)
        }

        // Kartu Scan: aksi utama
        val h = hero()
        val hr = hbox()
        val hl = vbox()
        hl.add(headingTv("Scan dokumen", 22f, C.ON_PANEL))
        hl.add(tv("Foto kertas jadi PDF rapi. Sudut otomatis diluruskan.", 14f, C.ON_PANEL_MUTED), top = 4)
        hl.add(heroBtn("Mulai Scan", R.drawable.ic_camera) { startScan() }, top = 14, w = WRAP)
        hr.add(hl, w = 0, weight = 1f)
        hr.add(ScanFrameView(this), w = dp(92), h = dp(112), side = 6)
        h.add(hr)
        p.add(h, top = 16)

        // Bento: dua ubin angka — abu lembut + tinta
        val nRecent = Recent.load(this).size; val nOut = outFiles().size
        val t1 = statTile("$nRecent", "Berkas terbaru", false, R.drawable.ic_folder) { docsMode = 0; select(1) }
        val t2 = statTile("$nOut", "Hasil dibuat", true, R.drawable.ic_download) { docsMode = 2; select(1) }
        p.add(twoCols(t1, t2), top = 12)
        statValue(t1)?.countUp(nRecent, 260); statValue(t2)?.countUp(nOut, 300)

        // Buka berkas: 4 pintasan sejajar
        p.add(sectionTitle("Buka berkas"), top = 22)
        fun quick(res: Int, fg: Int, bg: Int, label: String, desc: String, f: () -> Unit): View = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            tappable(desc) { f() }
            background = rippleBg(14)
            add(iconBadge(res, fg, C.SURFACE, 16, 24), w = dp(56), h = dp(56))
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
            createRow(R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG, "Nota & Kuitansi Toko", "Buat kuitansi, hitung total & tempel stempel") {
                InvoiceMakerManager.showDialog(this) { cb -> pickFile(arrayOf("image/*")) { cb(it) } }
            },
            createRow(R.drawable.ic_doc, C.DOC_FG, C.DOC_BG, "Katalog Produk & Portofolio", "Buat katalog jualan / portofolio PDF") {
                CatalogMakerManager.showDialog(this) { cb -> pickFile(arrayOf("image/*")) { cb(it) } }
            },
            createRow(R.drawable.ic_note, C.PRIMARY, C.PRIMARY_C, "Jadwal & Perencana Tugas", "Template agenda kerja / To-Do List ke PDF/Excel") {
                SchedulePlannerManager.showDialog(this)
            },
            createRow(R.drawable.ic_note, C.PRIMARY, C.PRIMARY_C, "Ringkas Dokumen PDF / Word", "Ekstrak poin-poin utama & buat PDF ringkasan") {
                DocumentSummarizerManager.showDialog(this) { cb -> pickFile(arrayOf(PDF, DOCX, "*/*")) { cb(it) } }
            },
            createRow(R.drawable.ic_sign, C.SUCCESS, C.SUCCESS_C, "Tanda tangan PDF", "Gambar tanda tangan, tempel ke PDF") {
                pickFile(arrayOf(PDF)) { signFlow(it) }
            }
        )), top = 8)

        // Template siap pakai (gulir mendatar)
        p.add(sectionTitle("Template siap pakai"), top = 18)
        val tplCards = ArrayList<View>()
        fun tpl(res: Int, fg: Int, @Suppress("UNUSED_PARAMETER") bg: Int, title: String, sub: String, f: () -> Unit) {
            tplCards.add(coverCard(cover(fg, res), title, sub, 132, "Template $title. $sub") { f() })
        }
        tpl(R.drawable.ic_doc, C.DOC_FG, C.DOC_BG, "Surat Lamaran", "Format resmi HRD") {
            startActivity(Intent(this, DocEditorActivity::class.java).putExtra(DocEditorActivity.EXTRA_TEMPLATE, DocEditorActivity.TEMPLATE_SURAT))
        }
        tpl(R.drawable.ic_user, C.DOC_FG, C.DOC_BG, "CV / Resume", "Riwayat hidup rapi") {
            startActivity(Intent(this, DocEditorActivity::class.java).putExtra(DocEditorActivity.EXTRA_TEMPLATE, DocEditorActivity.TEMPLATE_CV))
        }
        DocTemplates.all.forEach { t ->
            val icon = when (t.id) { "hadir" -> R.drawable.ic_grid; "undangan" -> R.drawable.ic_mail; "notulen" -> R.drawable.ic_note; else -> R.drawable.ic_doc }
            tpl(icon, C.DOC_FG, C.DOC_BG, t.title, t.sub) {
                startActivity(Intent(this, DocEditorActivity::class.java).putExtra(DocEditorActivity.EXTRA_TEMPLATE, t.id))
            }
        }
        tpl(R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG, "Kuitansi / Invoice", "Nota & pembayaran") {
            startActivity(Intent(this, SheetEditorActivity::class.java).putExtra(SheetEditorActivity.EXTRA_TEMPLATE, SheetEditorActivity.TEMPLATE_INVOICE))
        }
        tpl(R.drawable.ic_sign, C.IMG_FG, C.IMG_BG, "TTD Transparan", "Foto TTD jadi PNG") { extractTransparentSignatureFlow() }
        val tc = carousel(tplCards)
        p.add(tc, top = 6); bleed(tc)
    }

    /** Baris setelan bersakelar: ikon · judul · keterangan · sakelar. Seluruh baris bisa diketuk (sekali). */
    private fun switchRow(res: Int, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = checked
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(st, intArrayOf((C.PRIMARY and 0x00FFFFFF) or 0x66000000, C.LINE_STRONG))
            thumbTintList = ColorStateList(st, intArrayOf(C.ACCENT, C.BG))
            isClickable = false; isFocusable = false   // ketukan ditangani baris
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(8), dp(12))
            minimumHeight = dp(64)
            fun state() = "$title, ${if (sw.isChecked) "aktif" else "nonaktif"}. $sub"
            val badge = iconBadge(res, C.PRIMARY, C.PRIMARY_C, 10, 22)
            tappable(state()) {
                if (!isEnabled) return@tappable
                isEnabled = false
                haptic(this)
                sw.toggle()
                badge.boing(0.78f)   // ikon baris berdenyut mengikuti sakelar
                contentDescription = state()
                onChange(sw.isChecked)
            }
            add(badge, w = dp(40), h = dp(40))
            val c = vbox().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            c.add(tv(title, 15f, C.TEXT, true))
            c.add(tv(sub, 13f, C.MUTED), top = 1)
            add(c, w = 0, weight = 1f, side = 12)
            add(sw, w = WRAP, side = 0)
        }
    }

    /**
     * Tampilan kaca yang bisa diatur sesuka hati, seperti penggeser Liquid Glass di iOS 27: transparansi (bening ↔
     * pekat, bawaan di tengah) dan kekuatan blur. Pratinjau memakai kaca sungguhan, dan bilah atas & navigasi di layar
     * ini ikut berubah selagi penggeser ditarik.
     */
    private fun glassLookBlock(): View {
        val box = vbox()
        box.add(tv("Tampilan kaca", 15f, C.TEXT, true))
        box.add(tv("Atur seberapa bening kaca dan seberapa kuat blurnya. Berlaku untuk bilah, navigasi & notifikasi.", 13f, C.MUTED), top = 2)
        box.add(GlassPreview(this, listOf(R.drawable.ic_home, R.drawable.ic_doc, R.drawable.ic_folder), "Kaca"), h = dp(136), top = 12)
        val tone = lookSlider(box, "Transparansi", "Bening", "Pekat", Glass.tone) { v, save -> Glass.setLook(this, tone = v, save = save) }
        val blur = lookSlider(box, "Blur", "Tajam", "Buram", Glass.blurAmt) { v, save -> Glass.setLook(this, blur = v, save = save) }
        box.add(tv("Kembalikan bawaan", 14f, C.ACCENT, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setPadding(0, 0, dp(12), 0)
            tappable("Kembalikan tampilan kaca bawaan") {
                haptic(this)
                tone.progress = 50; blur.progress = 50
                Glass.setLook(this@MainActivity, Glass.DEFAULT, Glass.DEFAULT, save = true)
            }
        }, w = WRAP, top = 4)
        return box
    }

    /** Penggeser 0–100 dengan magnet di titik bawaan (50). [onChange]: nilai 0..1, save = simpan ke setelan. */
    private fun lookSlider(parent: LinearLayout, title: String, left: String, right: String, value: Float,
                           onChange: (Float, Boolean) -> Unit): android.widget.SeekBar {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.add(tv(title, 14f, C.TEXT, true), w = 0, weight = 1f)
        val valTv = tv("", 13f, C.MUTED)
        head.add(valTv, w = WRAP)
        parent.add(head, top = 14)
        fun label(p: Int) = if (p == 50) "Bawaan" else "$p%"
        val sb = android.widget.SeekBar(this).apply {
            max = 100
            progress = Math.round(value * 100f).coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(C.ACCENT)
            thumbTintList = ColorStateList.valueOf(C.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(C.LINE_STRONG)
            contentDescription = "$title kaca, dari $left sampai $right"
            minimumHeight = dp(40)
        }
        valTv.text = label(sb.progress)
        var tracking = false
        var snapping = false
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (snapping) return
                var q = p
                // magnet ke titik bawaan: mudah kembali ke tampilan semula, dengan ketukan getar kecil
                if (fromUser && q != 50 && kotlin.math.abs(q - 50) <= 2) {
                    snapping = true; s.progress = 50; snapping = false; q = 50
                    s.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                valTv.text = label(q)
                if (android.os.Build.VERSION.SDK_INT >= 30) s.stateDescription = label(q)
                // pembaca layar / keyboard mengubah nilai tanpa menyentuh: langsung disimpan
                if (fromUser) onChange(q / 100f, !tracking)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) { tracking = true }
            override fun onStopTrackingTouch(s: android.widget.SeekBar) { tracking = false; onChange(s.progress / 100f, true) }
        })
        parent.add(sb, top = 2)
        val ends = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        ends.add(tv(left, 12f, C.MUTED), w = 0, weight = 1f, side = 4)
        ends.add(tv(right, 12f, C.MUTED).apply { gravity = Gravity.END }, w = 0, weight = 1f, side = 4)
        parent.add(ends)
        return sb
    }

    private fun createRow(res: Int, fg: Int, bg: Int, title: String, sub: String, f: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            minimumHeight = dp(64)
            tappable("$title. $sub") { f() }
            add(iconBadge(res, fg, bg, 10, 22), w = dp(40), h = dp(40))
            val c = vbox().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            c.add(tv(title, 15f, C.TEXT, true))
            c.add(tv(sub, 13f, C.MUTED), top = 1)
            add(c, w = 0, weight = 1f, side = 12)
            add(icon(R.drawable.ic_chev, C.MUTED, 20), w = dp(20), h = dp(20))
        }

    // ================= DOKUMEN =================
    private fun buildDocs(p: LinearLayout) {
        val search = field("Cari nama atau isi berkas…").apply {
            isSingleLine = true
            setText(query)
            setSelection(text.length)
            contentDescription = "Cari dokumen"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelative(getDrawable(R.drawable.ic_search)!!.mutate().also { it.setTint(C.MUTED); it.setBounds(0, 0, dp(20), dp(20)) }, null, null, null)
        }
        p.add(search, top = 4)
        docsSearch = search
        // Pencarian terakhir: tampil saat kolom aktif & masih kosong (seperti iOS); ketuk = cari lagi
        val hist = vbox().apply { visibility = View.GONE }
        p.add(hist, top = 10)
        val listBox = vbox()
        fun refresh() = fillDocs(listBox)
        fun fillHist() {
            hist.removeAllViews()
            val items = Meta.searches(this)
            if (items.isEmpty()) return
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.add(tv("Pencarian terakhir", 13f, C.MUTED, true), w = 0, weight = 1f, side = 0)
            head.add(tv("Hapus", 14f, C.PRIMARY, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                tappable("Hapus riwayat pencarian") { Meta.clearSearches(this@MainActivity); hist.showIf(false) }
            }, w = WRAP, side = 0)
            hist.add(head)
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEach { s ->
                chips.add(filterChip(s, false, R.drawable.ic_clock) {
                    Meta.addSearch(this, s)
                    search.setText(s); search.setSelection(s.length)
                    hideIme(search)
                }, w = WRAP, side = 0)
                    .also { (chips.getChildAt(chips.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
            }
            hist.add(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        }
        fun updateHist() {
            val show = search.hasFocus() && query.isBlank() && Meta.searches(this).isNotEmpty()
            if (show && hist.visibility != View.VISIBLE) fillHist()
            hist.showIf(show)
        }
        val focusPrev = search.onFocusChangeListener
        search.setOnFocusChangeListener { v, has -> focusPrev?.onFocusChange(v, has); updateHist() }
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                if (query.isNotBlank()) Meta.addSearch(this, query)
                hideIme(v)
                true
            } else false
        }
        search.addTextChangedListener(object : TextWatcher {
            // Tunda sebentar agar daftar tidak dibangun ulang di setiap ketukan huruf (berat di HP kelas bawah).
            // Saat mengetik cukup larut singkat di tempat (tanpa geser) — daftar tidak "melompat" tiap huruf.
            private val run = Runnable { listBox.swapContent(0, 0) { refresh() } }
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                updateHist()
                listBox.removeCallbacks(run)
                listBox.postDelayed(run, if (Perf.low) 280L else 180L)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val outNote = tv("Berkas yang dibuat aplikasi ini · folder Documents/DepUp Office", 13f, C.MUTED).apply {
            visibility = if (docsMode == 2) View.VISIBLE else View.GONE
        }
        p.add(segmented(listOf("Terkini", "Ditandai", "Hasil olahan"), docsMode) {
            if (it != docsMode) {
                // daftar diganti di tempat tanpa kedip: kontrol segmen tetap hidup (ibu jarinya meluncur), daftar lama
                // memudar di atas daftar baru yang hanyut halus ke tempatnya
                docsMode = it
                pageKey[1] = keyFor(1)
                outNote.showIf(it == 2)
                listBox.swapContent { refresh() }
            }
        }, top = 12)
        p.add(outNote, top = 10)
        p.add(listBox, top = 12)
        docsList = listBox
        refresh()
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
            r1.add(tv("Simpan salinan ke DepUp Office", 14f, C.PRIMARY, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(6), 0)
                tappable {
                    val f = File(path)
                    if (f.exists()) work("Menyimpan…", { Files.saveToDownloads(this@MainActivity, Files.uriFor(this@MainActivity, f), name, Files.byName(name)) }) { r ->
                        r.onSuccess { toast("Tersimpan di Documents/DepUp Office") }.onFailure { fail(it) }
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
            tile(R.drawable.ic_crop, C.PDF_FG, C.PDF_BG, "Nomor Halaman PDF", "Tambahkan Hal X dari Y & Header") {
                PdfPageNumberingManager.showDialog(this) { cb -> pickFile(arrayOf(PDF)) { cb(it) } }
            },
            tile(R.drawable.ic_crop, C.PDF_FG, C.PDF_BG, "Hapus hal. kosong", "Deteksi & buang halaman putih polos") { pickFile(arrayOf(PDF)) { removeBlankPagesFlow(it) } },
            tile(R.drawable.ic_compress, C.PDF_FG, C.PDF_BG, "Kompres PDF Presisi", "Target < 200 KB / 500 KB / 1 MB", "CPNS", C.PDF_FG, C.PDF_BG) {
                PdfTargetCompressorManager.showDialog(this) { cb -> pickFile(arrayOf(PDF)) { cb(it) } }
            },
            tile(R.drawable.ic_image, C.PDF_FG, C.PDF_BG, "PDF ke JPG", "Tiap halaman jadi gambar") { pickFile(arrayOf(PDF)) { pdfToJpgFlow(it) } },
            tile(R.drawable.ic_img_add, C.PDF_FG, C.PDF_BG, "Ambil gambar", "Simpan foto di dalam PDF") { pickFile(arrayOf(PDF)) { extractImagesFlow(it) } },
            tile(R.drawable.ic_bright, C.PDF_FG, C.PDF_BG, "Coret-coret PDF", "Stabilo, pena, catatan, TTD", "Baru", C.PDF_FG, C.PDF_BG) {
                pickFile(arrayOf(PDF)) { annotateFlow(it) }
            },
            tile(R.drawable.ic_wand, C.PDF_FG, C.PDF_BG, "Watermark PDF", "Teks diagonal di tiap halaman") { pickFile(arrayOf(PDF)) { watermarkFlow(it) } }
        )
        section("Konversi",
            tile(R.drawable.ic_doc, C.DOC_FG, C.DOC_BG, "PDF ke Word", "Persis aslinya: teks, tabel, foto, bentuk", "Baru", C.DOC_FG, C.DOC_BG) { pickFile(arrayOf(PDF)) { pdfToWordFlow(it) } },
            tile(R.drawable.ic_search, C.DOC_FG, C.DOC_BG, "PDF bisa dicari", "Tanam teks OCR, bisa di-copy") { pickFile(arrayOf(PDF)) { rebuildPdfFlow(it, lock = false) } },
            tile(R.drawable.ic_pdf, C.DOC_FG, C.DOC_BG, "Word/Excel ke PDF", "Tampilan asli, simpan PDF") {
                pickFile(arrayOf(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/msword", "application/vnd.ms-excel"
                )) { officeToPdfFlow(it) }
            },
            tile(R.drawable.ic_image, C.DOC_FG, C.DOC_BG, "Gambar ke PDF", "Gabung foto jadi satu PDF") { jpgToPdfFlow() },
            tile(R.drawable.ic_pdf, C.DOC_FG, C.DOC_BG, "EXE ke PDF", "Ambil PDF dari berkas .exe (PDF to EXE)", "Baru", C.DOC_FG, C.DOC_BG) {
                pickFile(arrayOf("application/x-msdownload", "application/vnd.microsoft.portable-executable", "application/x-dosexec", "application/octet-stream", "*/*")) { exeToPdfFlow(it) }
            }
        )
        section("Gambar & foto",
            tile(R.drawable.ic_wand, C.IMG_FG, C.IMG_BG, "Watermark foto massal", "Tempel teks/stempel pada banyak foto produk") {
                BatchWatermarkManager.showDialog(this) { cb -> pickFiles(arrayOf("image/*")) { cb(it) } }
            },
            tile(R.drawable.ic_wand, C.IMG_FG, C.IMG_BG, "Pembersih bayangan foto", "Hapus bayangan tangan/HP & putihkan kertas") {
                DocShadowRemoverManager.showDialog(this) { cb -> pickFile(arrayOf("image/*")) { cb(it) } }
            },
            tile(R.drawable.ic_user, C.IMG_FG, C.IMG_BG, "Pindai kartu nama", "Ekstrak Kontak, HP & Email ke Kontak/VCF") {
                BusinessCardManager.showDialog(this) { cb -> pickFile(arrayOf("image/*")) { cb(it) } }
            },
            tile(R.drawable.ic_user, C.IMG_FG, C.IMG_BG, "Pembuat Pasfoto", "Potong 2x3 3x4 4x6, latar Merah/Biru, cetak A4") {
                IdPhotoManager.showDialog(this) { cb -> pickFile(arrayOf("image/*")) { cb(it) } }
            },
            tile(R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG, "Pindai tabel ke Excel", "Foto tabel/kuitansi jadi file .xlsx") {
                TableToExcelManager.showDialog(this) { cb -> pickFile(arrayOf("image/*")) { cb(it) } }
            },
            tile(R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG, "Grafik & Chart Excel", "Buat grafik Batang, Lingkaran & Garis dari .xlsx") {
                ExcelChartGeneratorManager.showDialog(this) { cb -> pickFile(arrayOf(XLSX, "text/csv", "*/*")) { cb(it) } }
            },
            tile(R.drawable.ic_pdf, C.IMG_FG, C.IMG_BG, "Foto ke PDF", "Gabung foto jadi satu PDF") { jpgToPdfFlow() },
            tile(R.drawable.ic_grid, C.IMG_FG, C.IMG_BG, "Konversi massal", "Banyak foto jadi PDF terpisah") { batchConvertFlow() },
            tile(R.drawable.ic_image, C.IMG_FG, C.IMG_BG, "Kompres foto", "Kecilkan sebelum kirim WA/email") { compressGalleryFlow() },
            tile(R.drawable.ic_sign, C.IMG_FG, C.IMG_BG, "TTD transparan", "Foto tanda tangan jadi PNG") { extractTransparentSignatureFlow() }
        )
        section("Tanda tangan & keamanan",
            tile(R.drawable.ic_sign, C.SUCCESS, C.SUCCESS_C, "Pustaka TTD & Stempel", "Kelola & simpan tanda tangan & stempel") {
                SignatureManager.showSheet(this, "Pustaka Tanda Tangan & Stempel", onPickImage = { cb -> pickFile(arrayOf("image/*")) { cb(it) } }) { _, _ ->
                    toast("Tanda tangan / stempel tersimpan dan siap digunakan")
                }
            },
            tile(R.drawable.ic_sign, C.SUCCESS, C.SUCCESS_C, "Tanda tangan PDF", "Tempel bebas: geser, ukuran, putar, warna") { pickFile(arrayOf(PDF)) { signFlow(it) } },
            tile(R.drawable.ic_shield, C.PRIMARY, C.PRIMARY_C, "Dokumen Terlindungi", "Password pembuka, anti-kopi, anti-cetak & sampul DepUp", "Utama", C.PRIMARY, C.PRIMARY_C) {
                ProtectedDocumentManager.showDialog(this) { cb -> pickFile(arrayOf(PDF)) { cb(it) } }
            },
            tile(R.drawable.ic_lock, C.PRIMARY, C.PRIMARY_C, "Kunci berkas", "Enkripsi ke Brankas AES-256") { pickFile(arrayOf("*/*")) { lockPasswordFlow(it) } },
            tile(R.drawable.ic_search, C.PRIMARY, C.PRIMARY_C, "Cari teks dalam file", "Cari kata di dalam isi PDF, Word, Excel & pindaian") { openSearch() },
            tile(R.drawable.ic_qr, C.PRIMARY, C.PRIMARY_C, "Label Harga & Barcode", "Buat label harga toko & cetak lebel A4") { BarcodePriceTagManager.showDialog(this) },
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
            tile(R.drawable.ic_download, C.PRIMARY, C.PRIMARY_C, "Simpan ke HP", "Salin ke Documents/DepUp Office") { exportFlow() },
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
        row.addView(segmented(labels, selectedIndex, onPick = onPick), LinearLayout.LayoutParams(MATCH, WRAP))
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

        // dibangun sekali: kontrol segmen menyimpan pilihannya sendiri, ibu jari meluncur utuh sampai tujuan
        fun render() {
            settingsChips(sizeRow, sizes, sizes.indexOf(size), C.ACCENT) {
                size = sizes[it]; DocSettings.setPageSize(this, size)
            }
            settingsChips(marginRow, margins, margin, C.ACCENT) {
                margin = it; DocSettings.setMarginMode(this, margin)
            }
            settingsChips(spacingRow, spacings.map { "${it}x" }, spacings.indexOf(spacing), C.TEAL) {
                spacing = spacings[it]; DocSettings.setLineSpacing(this, spacing)
            }
            settingsChips(fontRow, fonts, font.coerceIn(0, fonts.lastIndex), C.INDIGO) {
                font = it; DocSettings.setFontFamily(this, font)
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

        // dibangun sekali: kontrol segmen menyimpan pilihannya sendiri, ibu jari meluncur utuh sampai tujuan
        fun render() {
            settingsChips(gridRow, gridOptions, if (grid) 0 else 1, C.TEAL) {
                grid = it == 0; SheetSettings.setShowGridlines(this, grid)
            }
            settingsChips(currencyRow, currencies, currencies.indexOf(currency).coerceAtLeast(0), C.ACCENT) {
                currency = currencies[it]; SheetSettings.setCurrencySymbol(this, currency)
            }
            settingsChips(autoRow, autoOptions, if (autoSum) 0 else 1, C.INDIGO) {
                autoSum = it == 0; SheetSettings.setAutoSum(this, autoSum)
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

    /** Hasil olahan, terbaru dulu. Waktu ubah dibaca SEKALI per berkas (bukan berulang di setiap perbandingan urut). */
    private fun outFiles(): List<File> {
        val fs = Files.outFiles(this)
        return fs.map { it to it.lastModified() }.sortedByDescending { it.second }.map { it.first }
    }

    private fun catOf(f: File) = typeOf(Files.byName(f.name), f.name).first

    /** Satu baris hasil olahan (buka dengan ketuk; menu ⋮ untuk edit, ubah nama, hapus, bagikan). */
    private fun outRow(f: File, onChanged: () -> Unit = { render() }): View {
        val mime = Files.byName(f.name)
        val (label, fg, bg) = typeOf(mime, f.name)
        val uri = Files.uriFor(this, f)
        val us = uri.toString()
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(4), dp(10))
            minimumHeight = dp(68)
        }
        val sw = SwipeRow(this, r)
        fun openIt() { noteSearch(); Files.open(this, uri, mime) }
        fun share() = try { Files.share(this, uri, mime, false) } catch (_: Exception) { toast("Tidak bisa dibagikan") }
        fun delete() = confirm("Hapus berkas?", "“${f.name}” akan dihapus dari HP dan tidak bisa dikembalikan.", "Hapus", onCancel = { sw.close() }) {
            sw.collapseOut {
                f.delete()
                if (Meta.isPinned(this, us)) { Meta.unpin(this, us); pinsChanged() }
                toast("Berkas dihapus"); onChanged()
            }
        }
        val meta = "${Files.fmtSize(f.length())} • ${Files.relTime(f.lastModified())}"
        r.tappable("${f.name}, ${readableType(label)}, $meta. Ketuk untuk membuka") { openIt() }
        r.add(typeBadge(label, fg, bg), w = dp(40), h = dp(40))
        val col = vbox().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        col.add(tv(f.name, 15f, C.TEXT, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        val (metaRow, pinIc) = metaWithPin(meta, Meta.isPinned(this, us))
        col.add(metaRow, top = 2)
        r.add(col, w = 0, weight = 1f, side = 12)
        r.add(iconBtn(R.drawable.ic_more, "Opsi lain untuk ${f.name}", C.MUTED, 22) { v ->
            val pm = android.widget.PopupMenu(this, v)
            pm.menu.add(0, 1, 0, "Buka")
            pm.menu.add(0, 2, 1, "Edit dokumen")
            pm.menu.add(0, 3, 2, "Ubah nama")
            pm.menu.add(0, 5, 3, "Bagikan")
            pm.menu.add(0, 7, 4, if (Meta.isPinned(this, us)) "Lepas dari Beranda" else "Sematkan di Beranda")
            pm.menu.add(0, 6, 5, "Temukan di File Manager")
            pm.menu.add(0, 4, 6, "Hapus berkas")
            pm.setOnMenuItemClickListener { m ->
                when (m.itemId) {
                    1 -> openIt()
                    2 -> editDoc(us, f.name, mime)
                    3 -> showRenameFileDialog(us, f.name)
                    5 -> share()
                    7 -> togglePin(us, f.name, mime, pinIc, sw)
                    6 -> Files.showInFileManager(this, uri)
                    4 -> delete()
                    else -> {}
                }
                true
            }
            pm.show()
        }, w = dp(48), h = dp(48))
        sw.leading = listOf(pinAction(us, f.name, mime, pinIc, sw))
        sw.trailing = listOf(
            SwipeRow.Action("Bagikan", R.drawable.ic_share, SwipeRow.BLUE) { share() },
            SwipeRow.Action("Ubah nama", R.drawable.ic_edit, SwipeRow.GRAY) { showRenameFileDialog(us, f.name) },
            SwipeRow.Action("Hapus", R.drawable.ic_trash, SwipeRow.RED, "Hapus berkas", removes = true) { delete() })
        return sw
    }

    /** Daftar hasil olahan + chip kategori (dipakai tab Dokumen). */
    /** Daftar hasil olahan; kembalian sama seperti [fillRecent]. */
    private fun fillOutputs(box: LinearLayout, keepX: Int = 0): Int {
        box.removeAllViews()
        val all = outFiles()
        if (all.isNotEmpty()) catChips(box, all.map { catOf(it) }, keepX)
        val code = catCode()
        val q = query.trim()
        val list = all.filter { code.isEmpty() || catOf(it) == code }
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
        val top = if (all.isNotEmpty()) 12 else 0
        if (list.isNotEmpty()) {
            box.add(listCard(list.take(80).map { f -> outRow(f) { box.swapContent { fillDocs(box) } } }), top = top)
            return list.size
        }
        if (q.length >= 2 && all.isNotEmpty()) return 0
        box.add(when {
            all.isEmpty() -> emptyState(R.drawable.ic_box, "Belum ada hasil", "Hasil scan, konversi, dan dokumen yang Anda simpan akan muncul di sini.")
            q.isEmpty() -> emptyState(R.drawable.ic_box, "Belum ada hasil $docsCat", "Tidak ada hasil berjenis $docsCat.")
            else -> emptyState(R.drawable.ic_search, "Tidak ditemukan", "Tidak ada hasil yang cocok dengan “$q”.")
        }, top = top)
        return -1
    }

    private fun showOutputs(@Suppress("UNUSED_PARAMETER") list: List<File>) { docsMode = 2; select(1) }

    // ================= SAYA =================
    private fun buildMe(p: LinearLayout) {
        // Kepala
        val head = hbox()
        // sama dengan ikon aplikasi: ubin biru Depup + logo putih
        head.add(ImageView(this).apply { setImageResource(R.drawable.ic_logo_tile); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            w = dp(56), h = dp(56))
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
        val outSize = Files.outFiles(this).sumOf { it.length() }
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
        // halaman dibangun ulang setelah ibu jari tiba → kontrol baru muncul tepat di posisi akhir (tanpa ibu jari ganda)
        tc.add(segmented(listOf("Ikuti HP", "Terang", "Gelap"), Theme.mode(this), waitThumb = true) { m ->
            val willDark = when (m) { Theme.LIGHT -> false; Theme.DARK -> true; else -> Theme.systemDark() }
            if (willDark == C.dark) {
                Theme.setMode(this, m); DepupApp.applyNightMode(m); render()
            } else {
                // terang ↔ gelap: layar lama dipotret, tampilan dibangun ulang di tempat, lalu memudar (ala iOS)
                ThemeFade.change(this) { Theme.setMode(this, m); DepupApp.applyNightMode(m) }
            }
        }, top = 12)
        tc.add(View(this).apply { setBackgroundColor(C.LINE) }, top = 14, h = 1)
        tc.add(switchRow(R.drawable.ic_spark, "Efek kaca (blur)",
            "Bilah & navigasi buram transparan. Matikan bila layar terasa berat atau berkedip.", Glass.BLUR) { on ->
            Glass.setEnabled(this, on)
            content.postDelayed({ if (!isFinishing) recreate() }, 240L)   // sakelar selesai bergeser dulu
        }.apply { setPadding(0, dp(10), 0, 0) })
        if (LiquidGlass.supported) {
            // Android 13+: lensa kaca waktu-nyata (shader) di atas blur
            tc.add(switchRow(R.drawable.ic_spark, "Kaca cair (lensa & kilau)",
                "Tepi kaca membiaskan isi di belakangnya, kilau mengikuti kemiringan HP, menyala saat disentuh. " +
                    "Butuh efek kaca menyala.", LiquidGlass.ON) { on ->
                LiquidGlass.setEnabled(this, on)
                content.postDelayed({ if (!isFinishing) recreate() }, 240L)
            }.apply { setPadding(0, dp(10), 0, 0) })
        }
        // berlaku untuk lensa tab bawah (semua Android) dan tepi kaca cair (Android 13+)
        tc.add(switchRow(R.drawable.ic_spark, "Dispersi warna",
            "Semburat pelangi tipis di tepi kaca dan lensa tab saat bergerak. Matikan untuk tepi kaca yang bening.",
            LiquidGlass.DISPERSION) { on ->
            LiquidGlass.setDispersion(this, on)
            content.postDelayed({ if (!isFinishing) recreate() }, 240L)
        }.apply { setPadding(0, dp(10), 0, 0) })
        // transparansi & blur kaca yang bisa diatur bebas (hanya bermakna saat efek kaca menyala; tanpa blur kaca pekat)
        if (Glass.adjustable) {
            tc.add(View(this).apply { setBackgroundColor(C.LINE) }, top = 4, h = 1)
            tc.add(glassLookBlock(), top = 14)
        }
        tc.add(View(this).apply { setBackgroundColor(C.LINE) }, top = 4, h = 1)
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
    private fun mergeFlow() = pickFiles(arrayOf(PDF, "image/*")) { picked -> orderDialog(picked.toMutableList()) }

    /** Tombol kecil ber-ikon garis (naik/turun/putar/hapus) — pengganti simbol teks ↑ ↓ ↻ ✕. */
    private fun miniBtn(res: Int, desc: String, onClick: () -> Unit) = FrameLayout(this).apply {
        background = raised(C.SURFACE, 10, 2f)
        contentDescription = desc
        tooltipText = desc
        addView(icon(res, C.ACCENT_SOFT, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    /** Atur urutan manual: naik/turun/hapus, hasil digabung dari atas ke bawah. */
    private fun orderDialog(list: MutableList<Uri>) {
        val box = vbox(16)
        box.add(tv("Atur urutan dengan tombol naik/turun. Campuran file (PDF & Foto) digabung dari atas ke bawah.", 12f, C.MUTED))
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
                r.add(miniBtn(R.drawable.ic_up, "Naikkan") { if (i > 0) { list.add(i - 1, list.removeAt(i)); rebuild() } }, w = dp(36), h = dp(36), side = 2)
                r.add(miniBtn(R.drawable.ic_down, "Turunkan") { if (i < list.size - 1) { list.add(i + 1, list.removeAt(i)); rebuild() } }, w = dp(36), h = dp(36), side = 2)
                r.add(miniBtn(R.drawable.ic_close, "Hapus") { r.collapseOut { list.removeAt(i); rebuild() } }, w = dp(36), h = dp(36), side = 2)
                rows.add(r, top = 6)
            }
        }
        rebuild()
        val d = AlertDialog.Builder(this).setTitle("Urutan penggabungan file")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Gabung", null).setNegativeButton("Batal", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (list.size < 2) { toast("Pilih minimal 2 file untuk digabung"); return@setOnClickListener }
            d.dismiss()
            val files = list.toList()
            val out = Files.newOut(this, "gabung", "pdf")
            work("Menggabungkan campuran file ke PDF…", { UniversalMergeManager.merge(this, files, out) }) { r ->
                r.onSuccess { skipped ->
                    if (skipped.isEmpty()) showResult("File berhasil digabung ke PDF", out, PDF)
                    else showResult("Sebagian file digabung", out, PDF,
                        "Tidak ikut (terkunci/rusak): " + skipped.joinToString(", ").take(200))
                }.onFailure { fail(it) }
            }
        }
    }

    /**
     * Sampul halaman pertama PDF untuk dialog alat. Dirender di thread latar: dialog langsung tampil tanpa menunggu
     * berkas dibuka & dirender (dulu menahan layar, terasa saat berkas besar / dari penyimpanan awan).
     */
    private fun pdfCover(uri: Uri): ImageView {
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            background = paperThumbBg(8)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val app = applicationContext
        Perf.io.execute {
            val bmp = Pdf.thumbnail(app, uri, 0, 360)
            iv.post {
                if (isFinishing || isDestroyed) return@post
                if (bmp != null) iv.setImageBitmap(bmp) else iv.visibility = View.GONE
            }
        }
        return iv
    }

    private fun splitFlow(uri: Uri) {
        val pages = try { Pdf.pageCount(this, uri) } catch (t: Throwable) { fail(t); return }
        val name = Files.nameOf(this, uri)
        var splitMode = 0

        val box = vbox(16).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.add(pdfCover(uri), w = dp(80), h = dp(100))
        val infoCol = vbox()
        infoCol.add(tv(name, 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        infoCol.add(tv("Total Halaman: $pages hal", 13f, C.ACCENT, true), top = 4)
        infoCol.add(tv("Pilih metode pemisahan halaman:", 12f, C.MUTED), top = 4)
        topRow.add(infoCol, w = 0, weight = 1f, side = 10)
        box.add(topRow)

        val modeHolder = vbox()
        val rangeBox = vbox(8)
        val chunkBox = vbox(8)

        val a = field("Dari halaman", number = true).apply { setText("1") }
        val b = field("Sampai halaman", number = true).apply { setText(pages.toString()) }
        rangeBox.add(a)
        rangeBox.add(b, top = 4)

        val fChunk = field("Pecah per berapa halaman? (misal: 5)", number = true).apply { setText("5") }
        chunkBox.add(fChunk)
        chunkBox.visibility = View.GONE

        modeHolder.add(segmented(listOf("Rentang Halaman", "Pecah per N Hal"), splitMode) { mode ->
            splitMode = mode
            rangeBox.showIf(mode == 0)
            chunkBox.showIf(mode == 1)
        })

        box.add(modeHolder, top = 10)
        box.add(rangeBox, top = 8)
        box.add(chunkBox, top = 8)

        AlertDialog.Builder(this).setTitle("Pratinjau & Pisah PDF").setView(box)
            .setPositiveButton("Pisahkan") { _, _ ->
                if (splitMode == 0) {
                    val f = a.text.toString().toIntOrNull()
                    val t = b.text.toString().toIntOrNull()
                    if (f == null || t == null || f < 1 || t > pages || f > t) {
                        toast("Rentang halaman tidak valid"); return@setPositiveButton
                    }
                    val out = Files.newOut(this, "pisah_${f}-$t", "pdf")
                    work("Memisahkan halaman…", { Pdf.split(this, uri, f, t, out) }) { r ->
                        r.onSuccess { showResult("PDF dipisah", out, PDF) }.onFailure { fail(it) }
                    }
                } else {
                    val chunkSize = fChunk.text.toString().toIntOrNull() ?: 5
                    if (chunkSize < 1) {
                        toast("Jumlah halaman per bagian minimal 1"); return@setPositiveButton
                    }
                    work("Memecah PDF per $chunkSize halaman…", {
                        Pdf.splitChunk(this, uri, chunkSize, Files.baseName(name))
                    }) { r ->
                        r.onSuccess { files ->
                            toast("${files.size} berkas PDF berhasil dibuat!")
                            if (files.isNotEmpty()) showResult("PDF dipecah per $chunkSize hal", files.first(), PDF)
                        }.onFailure { fail(it) }
                    }
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun compressFlow(uri: Uri) {
        val name = Files.nameOf(this, uri)
        val before = try { contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize } } catch (_: Exception) { 0L }

        val box = vbox(16).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.add(pdfCover(uri), w = dp(80), h = dp(100))
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

    private fun removeBlankPagesFlow(uri: Uri) {
        val out = Files.newOut(this, Files.baseName(Files.nameOf(this, uri)) + "_bersih", "pdf")
        work("Mendeteksi & menghapus halaman kosong…", {
            Pdf.removeBlankPages(this, uri, out)
        }) { res ->
            res.onSuccess { count ->
                if (count > 0) showResult("Halaman Kosong Dibuang ($count hal)", out, PDF)
                else toast("Tidak ada halaman kosong yang terdeteksi")
            }.onFailure { fail(it) }
        }
    }

    private fun pdfToJpgFlow(uri: Uri) {
        val name = Files.nameOf(this, uri)
        val pages = try { Pdf.pageCount(this, uri) } catch (t: Throwable) { 1 }

        val box = vbox(16).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.add(pdfCover(uri), w = dp(80), h = dp(100))
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
                            .setMessage("$n gambar disimpan di Documents/DepUp Office/Gambar")
                            .setPositiveButton("OK", null).show()
                    }.onFailure { fail(it) }
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun organizePdfFlow(uri: Uri) {
        PdfTools.showOrganizeSheet(this, uri) { out ->
            showResult("PDF dikelola & disusun", out, PDF)
        }
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
                    .setMessage("Berhasil menyedot $count gambar dari PDF ke Documents/DepUp Office/Gambar!")
                    .setPositiveButton("Mengerti", null)
                    .show()
            }.onFailure { fail(it) }
        }
    }

    /** Berkas .exe hasil pengubah "PDF to EXE" (tidak bisa dibuka di HP) → PDF aslinya. Lihat [ExeToPdf]. */
    private fun exeToPdfFlow(uri: Uri) {
        val name = Files.nameOf(this, uri)
        val base = Files.baseName(name).ifBlank { "dokumen" }
        val out = Files.namedOut(this, base, "pdf")
        work("Mengambil PDF dari $name…", {
            val bytes = Files.readOrNull(this@MainActivity, uri) ?: throw IllegalStateException("Berkas tidak bisa dibaca")
            val r = ExeToPdf.extract(bytes)
            out.writeBytes(r.pdf)
            r
        }) { res ->
            res.onSuccess { showResult("PDF berhasil diambil", out, PDF) }.onFailure { out.delete(); fail(it) }
        }
    }

    private fun batchConvertFlow() = pickFiles(arrayOf("image/*")) { uris ->
        work("Konversi Massal ${uris.size} foto jadi PDF…", {
            Pdf.batchImagesToIndividualPdf(this, uris)
        }) { r ->
            r.onSuccess { n ->
                AlertDialog.Builder(this)
                    .setTitle("Konversi Massal Selesai")
                    .setMessage("Berhasil mengonversi $n foto menjadi $n file PDF terpisah di folder Documents/DepUp Office!")
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
                            .setMessage("Berhasil mengompres $count foto ke Documents/DepUp Office/Gambar!\n" + if (saved > 0) "Hemat ruang penyimpanan: $savedMb" else "")
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
            }
            val dst = Files.insertGallery(contentResolver, cv) ?: throw java.io.IOException("Gagal menyimpan PNG")
            contentResolver.openOutputStream(dst)!!.use { o -> transparentBmp.compress(Bitmap.CompressFormat.PNG, 100, o) }
            transparentBmp.recycle()
        }) { r ->
            r.onSuccess {
                AlertDialog.Builder(this)
                    .setTitle("Ekstrak TTD Selesai")
                    .setMessage("Tanda Tangan / Stempel Transparan berhasil dibuat dan disimpan ke Documents/DepUp Office/Gambar!\nSiap ditempelkan ke dokumen mana pun.")
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

    @SuppressLint("ThreadConstraint")
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
                toast("Kode QR disimpan di Documents/DepUp Office/Gambar")
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

    /** Tanda tangan PDF: editor bebas (geser, ukuran, putar, warna, banyak TTD, semua halaman). */
    private fun signFlow(uri: Uri) {
        if (!guardUnlocked(uri)) return
        startActivity(Intent(this, PdfAnnotateActivity::class.java)
            .putExtra(PdfAnnotateActivity.EXTRA_URI, uri.toString())
            .putExtra(PdfAnnotateActivity.EXTRA_SIGN, true))
    }


    // ================= ALAT PDF TAMBAHAN =================

    /** PDF terkunci password tidak bisa dirender PdfRenderer — beri tahu dengan jelas daripada gagal samar. */
    private fun guardUnlocked(uri: Uri): Boolean {
        if (Pdf.isLocked(this, uri)) { toast("PDF ini sudah dikunci password. Buka kuncinya dulu."); return false }
        return true
    }

    private fun annotateFlow(uri: Uri) {
        if (!guardUnlocked(uri)) return
        startActivity(Intent(this, PdfAnnotateActivity::class.java).putExtra(PdfAnnotateActivity.EXTRA_URI, uri.toString()))
    }

    private fun watermarkFlow(uri: Uri) {
        if (!guardUnlocked(uri)) return
        val dateStamp = "Dipindai: " + java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("id")).format(java.util.Date())
        val text = field("Teks watermark").apply { setText("RAHASIA") }
        var style = 0
        var colorIdx = 0
        var opacity = 1
        val colors = intArrayOf(0xFFDC2626.toInt(), 0xFF6B7280.toInt(), 0xFF2563EB.toInt())
        val alphas = intArrayOf(45, 80, 130)
        val box = vbox(20)
        box.add(tv("Pilih preset atau ketik teks kustom (misal: RAHASIA, DRAFT, LUNAS):", 13f, C.MUTED))
        box.add(text, top = 8)
        val quick = hbox()
        listOf("RAHASIA", "DRAFT", "CONFIDENTIAL", "LUNAS", dateStamp).forEach { q ->
            quick.add(filterChip(if (q.startsWith("Dipindai")) "Tanggal Hari Ini" else q, false) {
                text.setText(q)
                text.setSelection(q.length)
                if (q.startsWith("Dipindai")) style = 2
            }, w = WRAP, side = 3)
        }
        box.add(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(quick) }, top = 8)
        val styleHolder = vbox(); val colorHolder = vbox(); val opHolder = vbox()
        styleHolder.add(segmented(listOf("Satu besar", "Berulang", "Stempel Pojok"), style) { style = it })
        colorHolder.add(segmented(listOf("Merah", "Abu-abu", "Biru"), colorIdx) { colorIdx = it })
        opHolder.add(segmented(listOf("Tipis", "Sedang", "Tebal"), opacity) { opacity = it })
        box.add(tv("Gaya Penempatan", 13f, C.MUTED), top = 14); box.add(styleHolder, top = 6)
        box.add(tv("Warna", 13f, C.MUTED), top = 12); box.add(colorHolder, top = 6)
        box.add(tv("Ketebalan / Transparansi", 13f, C.MUTED), top = 12); box.add(opHolder, top = 6)
        val d = AlertDialog.Builder(this).setTitle("Watermark PDF")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Terapkan", null).setNegativeButton("Batal", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val t = text.text.toString().trim()
            if (t.isEmpty()) { toast("Teks watermark masih kosong"); return@setOnClickListener }
            d.dismiss()
            val out = Files.newOut(this, Files.baseName(Files.nameOf(this, uri)) + "_watermark", "pdf")
            work("Menambahkan watermark…", { PdfTools.watermark(this, uri, t, style, colors[colorIdx], alphas[opacity], out) }) { r ->
                r.onSuccess { showResult("Watermark ditambahkan", out, PDF) }.onFailure { fail(it) }
            }
        }
    }

    /**
     * lock = true: kunci PDF dengan password (opsional tanam teks OCR).
     * lock = false: jadikan PDF hasil scan bisa dicari (OCR), opsional sekalian dikunci.
     */
    private fun rebuildPdfFlow(uri: Uri, lock: Boolean) {
        if (!guardUnlocked(uri)) return
        val pages = try { Pdf.pageCount(this, uri) } catch (t: Throwable) { fail(t); return }
        val box = vbox(20)
        val pw = field("Password", pin = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pw2 = field("Ulangi password").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val useLock = android.widget.CheckBox(this).apply { text = "Kunci dengan password"; isChecked = lock; setTextColor(C.TEXT) }
        val useOcr = android.widget.CheckBox(this).apply { text = "Tanam teks OCR (untuk PDF hasil scan)"; isChecked = !lock; setTextColor(C.TEXT) }
        fun permBox(t: String) = android.widget.CheckBox(this).apply { text = t; isChecked = true; setTextColor(C.TEXT) }
        val canPrint = permBox("Izinkan cetak"); val canCopy = permBox("Izinkan salin teks"); val canEdit = permBox("Izinkan ubah & beri komentar")
        val pwBox = vbox().apply {
            add(pw); add(pw2, top = 8)
            add(tv("Izin setelah dibuka (dihormati Adobe Reader & pembaca resmi):", 12f, C.MUTED), top = 10)
            add(canPrint, top = 2); add(canCopy); add(canEdit)
        }
        fun sync() { pwBox.showIf(useLock.isChecked) }
        useLock.setOnCheckedChangeListener { _, _ -> sync() }
        box.add(tv(if (lock) "$pages halaman. Dikunci AES-256 TANPA mengubah isi: teks tetap asli & bisa dicari, ukuran tetap kecil. " +
                "Bisa dibuka di Adobe Reader, Chrome, Depup Office, dan pembaca PDF lain dengan password."
            else "$pages halaman. Halaman disimpan ulang sebagai gambar berkualitas tinggi; teks hasil OCR ditaruh tak terlihat di bawahnya.", 13f, C.MUTED))
        box.add(useLock, top = 10)
        box.add(pwBox, top = 4)
        box.add(useOcr, top = 8)
        if (pages > 20) box.add(tv("OCR untuk $pages halaman bisa memakan beberapa menit.", 12f, C.MUTED), top = 4)
        sync()
        val d = AlertDialog.Builder(this).setTitle(if (lock) "Password PDF" else "PDF bisa dicari")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Proses", null).setNegativeButton("Batal", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = if (useLock.isChecked) {
                val a = pw.text.toString()
                if (a.length < 4) { toast("Password minimal 4 karakter"); return@setOnClickListener }
                if (a != pw2.text.toString()) { toast("Password tidak sama"); return@setOnClickListener }
                a
            } else null
            val ocr = useOcr.isChecked
            if (password == null && !ocr) { toast("Pilih minimal satu: password atau OCR"); return@setOnClickListener }
            d.dismiss()
            val suffix = if (password != null) "_terkunci" else "_ocr"
            val out = Files.newOut(this, Files.baseName(Files.nameOf(this, uri)) + suffix, "pdf")
            val msg = if (ocr) "Membaca teks tiap halaman…" else "Mengunci PDF…"
            val perms = PdfSave.Perms(print = canPrint.isChecked, copy = canCopy.isChecked, modify = canEdit.isChecked, annotate = canEdit.isChecked)
            // izin dibatasi → password pemilik acak (izin tidak bisa dicabut balik dengan password pembuka)
            val owner = if (canPrint.isChecked && canCopy.isChecked && canEdit.isChecked) password.orEmpty() else java.util.UUID.randomUUID().toString()
            work(msg, {
                if (password == null) PdfTools.rebuild(this, uri, out, null, ocr)
                else if (!ocr) PdfTools.lockLossless(this, uri, out, password, owner, perms)
                else {
                    // OCR dulu (halaman jadi gambar + teks tersembunyi), lalu kunci AES-256
                    val tmp = File(cacheDir, "ocr_${System.nanoTime()}.pdf")
                    try { PdfTools.rebuild(this, uri, tmp, null, true); PdfTools.lockLossless(this, Uri.fromFile(tmp), out, password, owner, perms) } finally { tmp.delete() }
                }
            }) { r ->
                r.onSuccess {
                    showResult(if (password != null) "PDF dikunci" else "PDF bisa dicari", out, PDF,
                        if (password != null) "Simpan password Anda baik-baik — PDF tidak bisa dibuka tanpanya." else "")
                }.onFailure { fail(it) }
            }
        }
    }

    private fun pdfToWordFlow(uri: Uri, password: String = "", confirmed: Boolean = false) {
        fun run() {
            val out = Files.newOut(this, Files.baseName(Files.nameOf(this, uri)), "docx")
            work("Mengubah PDF ke Word…", { PdfTools.toWord(this, uri, out, password) }) { r ->
                r.onSuccess { locked ->
                    showResult(if (locked) "PDF diubah ke Word (terkunci)" else "PDF diubah ke Word", out, Files.byName(out.name),
                        if (locked) "Dokumen Word bisa dibaca siapa saja, tapi untuk MENGEDIT harus memasukkan password yang sama dengan PDF-nya. " +
                            "Di Depup: diminta saat membuka editor. Di Microsoft Word: Tinjau → Batasi Pengeditan → Hentikan Perlindungan." else "")
                }.onFailure { e ->
                    if (e is PdfPasswordException) askPdfPassword(password.isNotEmpty(), e.message?.takeIf { it.startsWith("PDF ini dibatasi") }) { pdfToWordFlow(uri, it, true) }
                    else fail(e)
                }
            }
        }
        if (confirmed) { run(); return }
        confirm("PDF ke Word", "Teks asli (font, ukuran, warna, tebal/miring), tabel lengkap dengan garis & sel gabungan, foto, " +
            "bentuk, dan tata letak diambil langsung dari PDF supaya hasilnya semirip mungkin dengan aslinya. " +
            "Halaman hasil scan dibaca dengan OCR.\n\nPDF terkunci: hasil Word hanya bisa diedit dengan password yang sama.", "Konversi") { run() }
    }

    /** Minta password PDF terkunci. [wrong] = percobaan sebelumnya salah; [msg] = penjelasan khusus. */
    private fun askPdfPassword(wrong: Boolean, msg: String? = null, then: (String) -> Unit) {
        val pw = field("Password PDF").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val box = vbox(20)
        box.add(tv(when { msg != null -> msg; wrong -> "Password salah. Coba lagi."; else -> "PDF ini dikunci. Masukkan password untuk membukanya." }, 13f, if (wrong && msg == null) C.RED else C.MUTED))
        box.add(pw, top = 8)
        AlertDialog.Builder(this).setTitle("PDF terkunci").setView(box)
            .setPositiveButton("Buka") { _, _ -> then(pw.text.toString()) }.setNegativeButton("Batal", null).show()
    }

    /** Buat Dokumen Terlindungi (sampul + enkripsi AES-256-GCM + password edit guru + HMAC). */
    private fun protectDocFlow(uri: Uri) {
        val pwField = field("Password Edit Guru", pin = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = vbox(16).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.add(tv("Buat Dokumen Terlindungi", 16f, C.TEXT, true))
        box.add(tv("Dokumen akan dibungkus dengan halaman sampul DepUp dan dienkripsi AES-256-GCM + HMAC integritas penuh. Guru dapat mengekspor PDF asli nanti dengan password edit.", 13f, C.MUTED), top = 6)
        box.add(pwField, top = 12)
        AlertDialog.Builder(this)
            .setTitle("Proteksi Dokumen")
            .setView(box)
            .setPositiveButton("Lindungi") { _, _ ->
                val editPw = pwField.text.toString().trim()
                if (editPw.length < 6 || editPw == "123456" || editPw.toSet().size == 1) {
                    toast("Password edit minimal 6 karakter dan tidak mudah ditebak")
                    return@setPositiveButton
                }
                val base = Files.baseName(Files.nameOf(this, uri))
                val out = Files.newOut(this, base + "_terlindungi", "pdf")
                work("Melindungi dokumen…", {
                    val originalBytes = Files.readOrNull(this@MainActivity, uri) ?: throw IllegalStateException("Tidak bisa membaca file")
                    ProtectedDoc.protect(originalBytes, editPw, out)
                }) { res ->
                    res.onSuccess {
                        showResult("Dokumen Terlindungi Dibuat", out, PDF, "Sampul, enkripsi AES-256-GCM, integritas HMAC, dan password edit guru berhasil diterapkan.")
                    }.onFailure { fail(it) }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Word/Excel/PowerPoint -> PDF lewat pembaca (tampilan asli), langsung jadi tanpa dialog cetak sistem. */
    private fun officeToPdfFlow(uri: Uri) {
        val name = Files.nameOf(this, uri).lowercase()
        if (name.endsWith(".doc") || name.endsWith(".xls") || name.endsWith(".ppt")) {
            toast("Format lama (.doc/.xls/.ppt) belum didukung. Simpan ulang sebagai .docx/.xlsx/.pptx.")
            return
        }
        // PDF dibuat langsung oleh pembaca (tanpa dialog cetak sistem) lalu tampil di lembar "PDF siap"
        startActivity(Integrity.internal(Intent(this, ViewerActivity::class.java)).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(ViewerActivity.EXTRA_AUTO_PRINT, true))
    }

    // ================= SIMPAN =================
    private fun exportFlow() = pickFile(arrayOf("*/*")) { u ->
        val name = Files.nameOf(this, u)
        val mime = Files.mimeOf(this, u)
        work("Menyimpan…", { Files.saveToDownloads(this, u, name, mime) }) { r ->
            r.onSuccess { toast("Tersimpan di Documents/DepUp Office") }.onFailure { fail(it) }
        }
    }

    private fun showResult(title: String, file: File, mime: String, extra: String = "") {
        val uri = Files.uriFor(this, file)
        Recent.add(this, uri, file.name, mime)
        Meta.setLastJob(this, title, file)
        resultSheet(
            title, "${file.name} · ${Files.fmtSize(file.length())}", extra, "Buka Dokumen", { Files.open(this, uri, mime) },
            listOf(
                SheetAction(R.drawable.ic_download, "Simpan salinan ke DepUp Office") {
                    work("Menyimpan…", { Files.saveToDownloads(this, uri, file.name, mime) }) { r ->
                        r.onSuccess { toast("Tersimpan di Documents/DepUp Office") }.onFailure { fail(it) }
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
