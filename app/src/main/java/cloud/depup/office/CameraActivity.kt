package cloud.depup.office

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.annotation.SuppressLint
import android.hardware.camera2.CaptureRequest
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Bingkai panduan dokumen/KTP + grid 3x3 di atas pratinjau kamera. Sudutnya melengkung (bukan siku tajam) dan
 * berubah ke biru DepUp seiring [progress] pindai otomatis. Bingkai selalu muat di antara kontrol atas & bawah
 * ([areaTop]..[areaBottom]) supaya tidak tertutup tombol.
 */
class GuideView(ctx: Context) : View(ctx) {
    var ratio = 0.707f      // lebar / tinggi bingkai
    var grid = false
    /** Batas atas & bawah area bebas kontrol (px, koordinat view ini); 0 = seluruh tinggi. */
    var areaTop = 0
    var areaBottom = 0
    /** 0..1: kemajuan "tahan stabil" pindai otomatis. */
    var progress = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (n != field) { field = n; invalidate() } }
    private var progAnim: ValueAnimator? = null
    private val dim = Paint().apply { color = 0x73000000 }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x47FFFFFF; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1).toFloat()
    }
    private val corners = android.graphics.Path()

    /** Kemajuan meluncur ke [to] dalam [ms] (linier — terasa seperti hitung mundur). */
    fun runTo(to: Float, ms: Long) {
        progAnim?.cancel()
        if (!Motion.on() || ms <= 0L) { progress = to; return }
        progAnim = ValueAnimator.ofFloat(progress, to).apply {
            duration = ms; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    /** Bingkai saat ini (koordinat view ini) — juga titik awal foto yang "terbang" ke gambar kecil. */
    fun frame(): android.graphics.RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val top = if (areaBottom > areaTop + context.dp(120)) areaTop.toFloat() else 0f
        val bottom = if (areaBottom > areaTop + context.dp(120)) areaBottom.toFloat() else h
        val ah = bottom - top
        var fw = w * 0.86f
        var fh = fw / ratio
        if (fh > ah * 0.96f) { fh = ah * 0.96f; fw = fh * ratio }
        val l = (w - fw) / 2f; val t = top + (ah - fh) / 2f
        return android.graphics.RectF(l, t, l + fw, t + fh)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val fr = frame()
        val fw = fr.width(); val fh = fr.height()
        val l = fr.left; val t = fr.top; val r = fr.right; val b = fr.bottom
        c.drawRect(0f, 0f, w, t, dim); c.drawRect(0f, b, w, h, dim)
        c.drawRect(0f, t, l, b, dim); c.drawRect(r, t, w, b, dim)
        if (grid) for (i in 1..2) {
            c.drawLine(l + fw * i / 3f, t, l + fw * i / 3f, b, thin)
            c.drawLine(l, t + fh * i / 3f, r, t + fh * i / 3f, thin)
        }
        // sudut melengkung: garis pendek yang membelok mulus, putih → biru DepUp saat menunggu stabil
        val L = context.dp(28).toFloat(); val R = context.dp(12).toFloat()
        corners.reset()
        corners.moveTo(l, t + L); corners.lineTo(l, t + R); corners.quadTo(l, t, l + R, t); corners.lineTo(l + L, t)
        corners.moveTo(r - L, t); corners.lineTo(r - R, t); corners.quadTo(r, t, r, t + R); corners.lineTo(r, t + L)
        corners.moveTo(r, b - L); corners.lineTo(r, b - R); corners.quadTo(r, b, r - R, b); corners.lineTo(r - L, b)
        corners.moveTo(l + L, b); corners.lineTo(l + R, b); corners.quadTo(l, b, l, b - R); corners.lineTo(l, b - L)
        edge.color = android.graphics.Color.rgb(
            (255 + (Color.red(CAM_BRAND_LIGHT) - 255) * progress).toInt(),
            (255 + (Color.green(CAM_BRAND_LIGHT) - 255) * progress).toInt(),
            (255 + (Color.blue(CAM_BRAND_LIGHT) - 255) * progress).toInt())
        edge.strokeWidth = context.dp(3).toFloat() * (1f + 0.35f * progress)
        c.drawPath(corners, edge)
    }
}

/** Cincin rana: lingkar putih; saat pindai otomatis menunggu stabil, busur biru DepUp mengisi searah jarum jam. */
class ShutterRing(ctx: Context) : FrameLayout(ctx) {
    var progress = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (n != field) { field = n; invalidate() } }
    private var anim: ValueAnimator? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = ctx.dp(4).toFloat() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = CAM_BRAND_LIGHT; strokeWidth = ctx.dp(4).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val oval = android.graphics.RectF()

    init { setWillNotDraw(false) }

    fun runTo(to: Float, ms: Long) {
        anim?.cancel()
        if (!Motion.on() || ms <= 0L) { progress = to; return }
        anim = ValueAnimator.ofFloat(progress, to).apply {
            duration = ms; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val sw = ring.strokeWidth / 2f
        oval.set(sw, sw, width - sw, height - sw)
        c.drawOval(oval, ring)
        if (progress > 0.001f) c.drawArc(oval, -90f, 360f * progress, false, arc)
    }
}

/** Cincin fokus: muncul di titik ketuk, mengerut berpegas, berdenyut saat fokus terkunci, lalu memudar. */
class FocusRing(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    init { alpha = 0f; isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        val r = width / 2f - ring.strokeWidth
        c.drawCircle(width / 2f, height / 2f, r, ring)
        c.drawCircle(width / 2f, height / 2f, context.dp(3).toFloat(), dot)
    }
    fun show(x: Float, y: Float) {
        translationX = x - width / 2f; translationY = y - height / 2f
        removeCallbacks(hideRun)
        if (!Motion.on()) { alpha = 1f; postDelayed(hideRun, 1100); return }
        animate().cancel()
        scaleX = 1.45f; scaleY = 1.45f; alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING)
            .setUpdateListener(null).withEndAction(null).start()
        postDelayed(hideRun, 1100)
    }
    /** Fokus terkunci: denyut kecil. */
    fun locked() { if (alpha > 0f) boing(0.88f) }
    private val hideRun = Runnable {
        if (!Motion.on()) { alpha = 0f; return@Runnable }
        animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setStartDelay(0).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).start()
    }
}

/**
 * UI kamera selalu gelap (di atas pratinjau foto), jadi warnanya tidak ikut tema terang/gelap: kapsul kaca gelap
 * bertepi tipis seperti kamera iOS, putih untuk pilihan aktif, biru DepUp untuk pindai otomatis.
 */
private val CAM_ACCENT = 0xFFFFFFFF.toInt()
private val CAM_ON_ACCENT = 0xFF111111.toInt()
/** Biru merek DepUp yang dicerahkan agar terbaca di atas gelap. */
private val CAM_BRAND_LIGHT = 0xFF4D7BFF.toInt()
private const val CAM_GLASS = 0x59000000       // isi kapsul kaca gelap (±35%)
private const val CAM_RIM = 0x33FFFFFF         // garis tepi tipis kapsul

private fun Context.camGlass(radiusDp: Int = 100) = shape(CAM_GLASS, radiusDp, CAM_RIM, 1)

/** Viewfinder sendiri (CameraX): mode multi-halaman / KTP / dokumen, flash, grid, filter bawaan. */
class CameraActivity : AppCompatActivity(), FullBleed {
    private lateinit var previewView: PreviewView
    private lateinit var guide: GuideView
    private var capture: ImageCapture? = null
    private var camera: Camera? = null
    private val shots = ArrayList<File>()

    private var flash = 0                 // 0 otomatis, 1 nyala, 2 mati
    private var modeIdx = 2               // 0 multi, 1 KTP, 2 dokumen
    private var filterIdx = 0
    private val filters = intArrayOf(3, 2, 1, 0)
    private val filterNames = arrayOf("Magic Color", "B&W", "Grayscale", "Warna Asli")

    private lateinit var flashBtn: FrameLayout
    private lateinit var flashIcon: ImageView
    private lateinit var flashSlash: View
    private lateinit var flashBadge: TextView
    private lateinit var thumb: ImageView
    private lateinit var badge: TextView
    private lateinit var thumbLabel: TextView
    private lateinit var filterLabel: TextView
    private val tabs = mutableListOf<TextView>()
    private lateinit var hintPill: TextView
    private lateinit var shutter: View
    private lateinit var shutterRing: ShutterRing
    private lateinit var modePill: View
    private lateinit var tabRow: LinearLayout
    private lateinit var focusRing: FocusRing
    private lateinit var zoomPill: TextView
    private lateinit var bottomBar: View
    private lateinit var wandIcon: View
    private var pillAnim: ValueAnimator? = null
    private var guideAnim: ValueAnimator? = null
    private lateinit var autoPill: TextView

    // ---- Pindai otomatis saat stabil ----
    private var autoScan = true
    private val analyzer: ExecutorService = Executors.newSingleThreadExecutor()
    private var prevLuma: IntArray? = null
    @Volatile private var stableSince = 0L
    @Volatile private var armed = true            // false setelah jepret: tunggu kamera digerakkan dulu
    @Volatile private var busyShot = false
    private var lastHint = 0L
    // Analisis cukup ±8x per detik; tiap frame kamera (30 fps) hanya membebani CPU HP kelas bawah.
    private var lastAnalyze = 0L
    private val analyzeEveryMs = if (Perf.low) 160L else 110L

    private val askCam = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { toast("Izin kamera diperlukan untuk memindai"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hitam penuh sampai tepi layar: pratinjau di belakang bilah status, ikon bilah putih. (Dulu area bilah status
        // memperlihatkan latar jendela yang terang — pita krem di atas kamera.)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        guide = GuideView(this)
        root.addView(guide, FrameLayout.LayoutParams(MATCH, MATCH))
        focusRing = FocusRing(this)
        root.addView(focusRing, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP or Gravity.START))

        // bayang lembut di atas: kontrol putih tetap terbaca walau kertas putih memenuhi pratinjau
        val scrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x8C000000.toInt(), 0x00000000))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, dp(150), Gravity.TOP))

        // ----- atas: tutup · pindai otomatis (tengah) · lampu kilat & garis bantu -----
        fun glassBtn(res: Int, desc: String, onClick: () -> Unit) = FrameLayout(this).apply {
            background = camGlass()
            contentDescription = desc; isFocusable = true; isClickable = true
            addView(icon(res, Color.WHITE, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { onClick() }
            springPress(0.86f)
        }
        // FrameLayout (bukan baris berpengisi): View pengisi polos ber-tinggi WRAP mengambil SELURUH tinggi layar,
        // sehingga tombol-tombol ini dulu jatuh ke tengah layar, menutupi bingkai.
        val top = FrameLayout(this).apply { setPadding(dp(14), 0, dp(14), 0) }
        top.addView(glassBtn(R.drawable.ic_close, "Tutup kamera") { cancel() },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        autoPill = tv("", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(16), 0)
            compoundDrawablePadding = dp(8)
            isFocusable = true
            setOnClickListener {
                autoScan = !autoScan
                if (stableSince != 0L) { stableSince = 0L; holdCancel() }
                armed = true
                paintAuto(true)
            }
            springPress(0.94f)
        }
        top.addView(autoPill, FrameLayout.LayoutParams(WRAP, dp(36), Gravity.CENTER))
        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        flashBtn = FrameLayout(this).apply {
            background = camGlass()
            isClickable = true; isFocusable = true
            setOnClickListener { cycleFlash() }
            springPress(0.86f)
        }
        flashIcon = icon(R.drawable.ic_flash, Color.WHITE, 20)
        flashBtn.addView(flashIcon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        flashSlash = View(this).apply { setBackgroundColor(Color.WHITE); rotation = -45f; visibility = View.GONE }
        flashBtn.addView(flashSlash, FrameLayout.LayoutParams(dp(2), dp(26), Gravity.CENTER))
        flashBadge = tv("A", 9f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        flashBtn.addView(flashBadge, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(7), dp(7)) })
        right.add(flashBtn, w = dp(44), h = dp(44))
        val gridBtn = glassBtn(R.drawable.ic_grid, "Tampilkan atau sembunyikan garis bantu") { toggleGrid() }
        right.add(gridBtn, w = dp(44), h = dp(44))
        (gridBtn.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        top.addView(right, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(top, FrameLayout.LayoutParams(MATCH, dp(48), Gravity.TOP))
        paintFlash(false)

        hintPill = tv("Letakkan dokumen di dalam bingkai & permukaan kontras", 13f, Color.WHITE).apply {
            background = camGlass()
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER
            maxWidth = resources.displayMetrics.widthPixels - dp(48)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(hintPill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        paintAuto()

        // ----- bawah: mode · gambar kecil · rana · filter -----
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0xF5000000.toInt()))
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        // label ringkas: ketiganya muat di layar 360 dp (dulu "KTP / ID Card" & "Dokumen (PDF)" bisa terpotong)
        listOf("Multi-Halaman", "KTP / Kartu", "Dokumen").forEachIndexed { i, n ->
            val t = tv(n, 13f, 0xCCFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minHeight = dp(44)
                setOnClickListener { setMode(i) }
            }
            tabs.add(t)
            tabRow.add(t, w = WRAP)
        }
        // kapsul putih yang MELUNCUR (dan sedikit melar) di antara mode — bukan latar yang berpindah tiba-tiba
        val tabWrap = FrameLayout(this).apply { background = camGlass(); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        modePill = View(this).apply { background = shape(CAM_ACCENT, 100); alpha = 0f }
        tabWrap.addView(modePill, FrameLayout.LayoutParams(0, 0))
        tabWrap.addView(tabRow, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
        tabWrap.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (pillAnim?.isRunning != true) placePill(false) }
        val tabCenter = FrameLayout(this)
        tabCenter.addView(tabWrap, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
        bottom.add(tabCenter)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // kiri: gambar kecil hasil jepretan
        val left = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val tf = FrameLayout(this)
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(0x26FFFFFF, 14, 0xD9FFFFFF.toInt(), 2)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            contentDescription = "Selesai memotret, lanjut ke edit"
            setOnClickListener { finishWithShots() }
        }
        tf.addView(thumb, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        badge = tv("0", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CAM_BRAND_LIGHT) }
            visibility = View.GONE
        }
        tf.addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END))
        left.add(tf, w = dp(64), h = dp(64))
        thumbLabel = tv("0 Halaman", 11f, 0xD9FFFFFF.toInt())
        left.add(thumbLabel, top = 2, w = WRAP)
        actions.add(left, w = 0, weight = 1f)

        // tengah: rana — cincinnya terisi biru saat pindai otomatis menghitung mundur
        val outer = ShutterRing(this)
        shutterRing = outer
        shutter = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            contentDescription = "Ambil foto"
            setOnClickListener { snap() }
            springPress(0.86f)      // tekan: rana mengecil; lepas: memantul kembali seperti jeli
        }
        outer.addView(shutter, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        actions.add(outer, w = dp(82), h = dp(82))

        // kanan: filter bawaan
        val rightCol = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        rightCol.add(FrameLayout(this).apply {
            background = camGlass()
            wandIcon = icon(R.drawable.ic_wand, Color.WHITE, 24)
            addView(wandIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            contentDescription = "Ganti filter warna"
            isClickable = true; isFocusable = true
            setOnClickListener { nextFilter() }
            springPress(0.88f)
        }, w = dp(56), h = dp(56))
        filterLabel = tv(filterNames[0], 11f, 0xD9FFFFFF.toInt())
        rightCol.add(filterLabel, top = 10, w = WRAP)
        actions.add(rightCol, w = 0, weight = 1f)
        bottom.add(actions, top = 12)
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        bottomBar = bottom
        thumb.springPress(0.92f)

        // jarak aman dari bilah status / takik kamera / navigasi gestur
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (top.layoutParams as FrameLayout.LayoutParams).topMargin = i.top + dp(6)
            top.setPadding(dp(14) + i.left, 0, dp(14) + i.right, 0)
            (hintPill.layoutParams as FrameLayout.LayoutParams).topMargin = i.top + dp(64)
            scrim.layoutParams.height = i.top + dp(150)
            bottom.setPadding(dp(16) + i.left, dp(10), dp(16) + i.right, dp(14) + i.bottom)
            top.requestLayout(); hintPill.requestLayout(); scrim.requestLayout()
            WindowInsetsCompat.CONSUMED
        }
        // bingkai panduan muat di antara kontrol atas (termasuk petunjuk) & bilah bawah
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val at = top.bottom + dp(58); val ab = bottom.top - dp(14)
            if (guide.areaTop != at || guide.areaBottom != ab) { guide.areaTop = at; guide.areaBottom = ab; guide.invalidate() }
        }

        // tingkat zoom saat mencubit: kapsul kecil di atas bilah bawah, muncul & hilang sendiri
        zoomPill = tv("1.0×", 13f, Color.WHITE, true).apply {
            background = camGlass()
            setPadding(dp(12), dp(6), dp(12), dp(6))
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(zoomPill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        setContentView(root)
        setMode(2)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancel() }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCam.launch(Manifest.permission.CAMERA)
    }

    /** Kapsul pindai otomatis: biru DepUp + titik putih saat aktif, kaca gelap saat manual. */
    private fun paintAuto(animate: Boolean = false) {
        val t = if (autoScan) "Otomatis" else "Manual"
        autoPill.contentDescription = if (autoScan) "Pindai otomatis aktif. Ketuk untuk memotret manual"
            else "Pindai manual. Ketuk untuk menyalakan pindai otomatis"
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (autoScan) Color.WHITE else 0x8CFFFFFF.toInt())
            setBounds(0, 0, dp(8), dp(8))
        }
        autoPill.setCompoundDrawables(dot, null, null, null)
        val to = if (autoScan) 0xE62F5BFF.toInt() else CAM_GLASS
        val bg = (autoPill.background as? GradientDrawable) ?: shape(to, 100, CAM_RIM, 1).also { autoPill.background = it }
        if (!animate || !Motion.on()) { autoPill.text = t; bg.setColor(to); autoPill.tag = to; return }
        // warna kapsul mengalir ke warna baru, teks bergulir, kapsul memantul kecil
        val from = (autoPill.tag as? Int) ?: to
        ValueAnimator.ofArgb(from, to).apply {
            duration = Motion.MED; interpolator = Motion.STANDARD
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
        autoPill.tag = to
        autoPill.morphText(t)
        autoPill.boing(0.94f)
        if (!autoScan) { guide.runTo(0f, 160L); shutterRing.runTo(0f, 160L) }
    }

    /** Pindai otomatis mulai menghitung mundur: cincin rana & sudut bingkai terisi biru selama ±1,2 detik. */
    private fun holdStart() {
        if (busyShot || !autoScan) return
        shutterRing.runTo(1f, 1200L)
        guide.runTo(1f, 1200L)
        hintPill.morphText("Tahan stabil…")
    }

    /** Ponsel bergerak sebelum selesai: hitungan dibatalkan, isian surut cepat. */
    private fun holdCancel() {
        shutterRing.runTo(0f, 180L)
        guide.runTo(0f, 180L)
    }

    private fun hint(t: String) {
        runOnUiThread { hintPill.morphText(t) }
    }

    private fun toggleGrid() {
        guide.grid = !guide.grid
        guide.invalidate()
        if (Motion.on()) { guide.alpha = 0.6f; guide.animate().alpha(1f).setStartDelay(0).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).start() }
    }

    private fun nextFilter() {
        filterIdx = (filterIdx + 1) % filters.size
        filterLabel.morphText(filterNames[filterIdx])
        // tongkat sihir berayun lalu mengendap
        if (Motion.on()) {
            wandIcon.animate().cancel()
            wandIcon.rotation = -28f
            wandIcon.animate().rotation(0f).setStartDelay(0).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).start()
        }
    }

    /** Letakkan kapsul mode di bawah tab aktif; [animate] = meluncur berpegas & sedikit melar di tengah jalan. */
    private fun placePill(animate: Boolean) {
        val t = tabs.getOrNull(modeIdx) ?: return
        if (t.width == 0) return
        // kapsul tinggal di dalam wadah ber-padding: posisi tab dikurangi padding wadah (tidak terhitung dua kali)
        val wrap = modePill.parent as? View
        val x1 = (tabRow.left + t.left - (wrap?.paddingLeft ?: 0)).toFloat(); val w1 = t.width.toFloat()
        val top = tabRow.top + t.top - (wrap?.paddingTop ?: 0); val h = t.height
        val lp = modePill.layoutParams as FrameLayout.LayoutParams
        fun apply(x: Float, w: Float) {
            modePill.translationX = x
            val wi = w.toInt()
            // tata letak ulang HANYA bila ukurannya berubah (pendengar tata letak memanggil ini lagi → jangan berputar)
            if (lp.width != wi || lp.height != h || lp.topMargin != top || lp.leftMargin != 0) {
                lp.width = wi; lp.height = h; lp.topMargin = top; lp.leftMargin = 0
                modePill.layoutParams = lp
            }
        }
        pillAnim?.cancel()
        if (!animate || !Motion.on() || modePill.alpha == 0f) { apply(x1, w1); modePill.alpha = 1f; return }
        val x0 = modePill.translationX; val w0 = lp.width.toFloat()
        val dist = abs(x1 - x0)
        pillAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.SPRING_MS; interpolator = Motion.SPRING
            addUpdateListener {
                val f = it.animatedValue as Float
                val stretch = dist * 0.22f * kotlin.math.sin(Math.PI * f.coerceIn(0f, 1f)).toFloat()
                val w = w0 + (w1 - w0) * f + stretch
                apply(x0 + (x1 - x0) * f - stretch / 2f, w)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { apply(x1, w1) }
            })
            start()
        }
    }

    /**
     * Berjalan di thread analisis: bandingkan kecerahan 24x32 titik antar-frame. Bila gambar nyaris tidak berubah
     * ±1,2 detik dan cukup berkontras (ada dokumen, bukan permukaan kosong), jepret otomatis.
     */
    private fun analyze(img: ImageProxy) {
        try {
            if (!autoScan || busyShot) { prevLuma = null; stableSince = 0L; return }
            val t0 = System.currentTimeMillis()
            if (t0 - lastAnalyze < analyzeEveryMs) return
            lastAnalyze = t0
            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride; val ps = plane.pixelStride
            val w = img.width; val h = img.height
            val gx = 24; val gy = 32
            val cur = IntArray(gx * gy)
            for (j in 0 until gy) {
                val y = ((j + 0.5f) / gy * h).toInt().coerceIn(0, h - 1)
                for (i in 0 until gx) {
                    val x = ((i + 0.5f) / gx * w).toInt().coerceIn(0, w - 1)
                    val idx = y * rs + x * ps
                    cur[j * gx + i] = if (idx < buf.limit()) buf.get(idx).toInt() and 255 else 0
                }
            }
            var mean = 0.0
            for (v in cur) mean += v
            mean /= cur.size
            var varSum = 0.0
            for (v in cur) varSum += (v - mean) * (v - mean)
            val std = sqrt(varSum / cur.size)

            val prev = prevLuma
            prevLuma = cur
            if (prev == null) return
            var diff = 0.0
            for (k in cur.indices) diff += abs(cur[k] - prev[k])
            diff /= cur.size

            val now = System.currentTimeMillis()
            if (!armed) {
                // Setelah jepret, tunggu sampai kamera digerakkan (ganti halaman) sebelum jepret lagi.
                if (diff > 9.0) armed = true
                return
            }
            val steady = diff < 2.2
            val hasDoc = std > 16.0 && mean > 25.0
            if (steady && hasDoc) {
                if (stableSince == 0L) { stableSince = now; lastHint = now; runOnUiThread { holdStart() } }
                val held = now - stableSince
                if (held >= 1200L) {
                    stableSince = 0L
                    runOnUiThread { snap() }
                }
            } else {
                if (stableSince != 0L) { stableSince = 0L; runOnUiThread { holdCancel() } }
                if (now - lastHint > 600L) { lastHint = now; hint(if (!hasDoc) "Arahkan ke dokumen yang kontras" else "Tahan ponsel agar stabil") }
            }
        } catch (_: Throwable) {
        } finally {
            img.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer.shutdown()
    }

    private fun setMode(i: Int) {
        val changed = i != modeIdx
        modeIdx = i
        tabs.forEachIndexed { k, t ->
            val on = k == i
            t.setTextColor(if (on) CAM_ON_ACCENT else 0xCCFFFFFF.toInt())
            t.background = null
            t.isSelected = on
        }
        placePill(changed)
        // bingkai panduan berubah bentuk (dokumen ↔ KTP) dengan pegas lembut, bukan melompat
        val ratio = if (i == 1) 1.586f else 0.707f
        guideAnim?.cancel()
        if (!changed || !Motion.on() || guide.ratio == ratio) { guide.ratio = ratio; guide.invalidate() }
        else {
            // rasio diinterpolasi dalam skala log supaya lebar & tinggi berubah sama mulusnya
            val a = kotlin.math.ln(guide.ratio); val b = kotlin.math.ln(ratio)
            guideAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motion.SPRING_SOFT_MS; interpolator = Motion.SPRING_SOFT
                addUpdateListener { guide.ratio = kotlin.math.exp(a + (b - a) * (it.animatedValue as Float)); guide.invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(x: android.animation.Animator) { guide.ratio = ratio; guide.invalidate() }
                })
                start()
            }
        }
        hintPill.morphText(when (i) {
            0 -> "Ambil beberapa halaman, lalu ketuk gambar kecil untuk lanjut"
            1 -> "Posisikan KTP / kartu di dalam bingkai"
            else -> "Letakkan dokumen di dalam bingkai & permukaan kontras"
        })
    }

    private fun flashConst() = when (flash) {
        1 -> ImageCapture.FLASH_MODE_ON
        2 -> ImageCapture.FLASH_MODE_OFF
        else -> ImageCapture.FLASH_MODE_AUTO
    }

    private fun cycleFlash() {
        flash = (flash + 1) % 3
        paintFlash(true)
        capture?.flashMode = flashConst()
    }

    /** Lampu kilat seperti kamera iOS: ikon saja — "A" kecil = otomatis, kuning = nyala, dicoret = mati. */
    private fun paintFlash(animate: Boolean) {
        val name = arrayOf("Otomatis", "Nyala", "Mati")[flash]
        flashBtn.contentDescription = "Lampu kilat: $name. Ketuk untuk mengganti"
        flashIcon.setColorFilter(if (flash == 1) 0xFFFFD60A.toInt() else Color.WHITE)
        flashIcon.alpha = if (flash == 2) 0.55f else 1f
        flashBadge.visibility = if (flash == 0) View.VISIBLE else View.GONE
        flashSlash.visibility = if (flash == 2) View.VISIBLE else View.GONE
        if (animate) { flashIcon.boing(0.78f); hint("Lampu kilat: $name") }
    }

    /**
     * Konfigurasi kamera "natural++": resolusi ±12 MP rasio 4:3, JPEG kualitas 100, fokus otomatis terus-menerus,
     * pengurangan noise & ketajaman (edge) kualitas tinggi, tanpa efek/filter warna dari kamera,
     * ketuk untuk fokus, cubit untuk zoom.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            try {
                val provider = fut.get()
                val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val hiRes = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(ResolutionStrategy(
                        // ±12 MP di HP kuat; ±8 MP di HP kelas bawah (masih tajam untuk OCR, jauh lebih ringan diproses).
                        if (Perf.low) Size(3264, 2448) else Size(4000, 3000),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val ratioOnly = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()

                val pb = Preview.Builder().setResolutionSelector(ratioOnly)
                val cb = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(hiRes)
                    .setJpegQuality(if (Perf.low) 95 else 100)
                    .setFlashMode(flashConst())
                for (ext in listOf(Camera2Interop.Extender(pb), Camera2Interop.Extender(cb))) {
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                }
                val ext = Camera2Interop.Extender(cb)
                if (Perf.low) {
                    // ISP HP kelas bawah lambat pada mode HIGH_QUALITY -> jepretan tertunda lama. FAST tetap layak untuk dokumen.
                    ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                    ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                } else {
                    ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    ext.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                }

                val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val cap = cb.build()
                capture = cap
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ratioOnly)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzer) { img -> analyze(img) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap, analysis)
                setupFocusAndZoom()
            } catch (e: Exception) {
                toast("Kamera tidak bisa dibuka: ${e.message}")
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFocusAndZoom() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val z = cam.cameraInfo.zoomState.value ?: return true
                val target = (z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, minOf(z.maxZoomRatio, 6f))
                cam.cameraControl.setZoomRatio(target)
                showZoom(target)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
        })
        previewView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) tap.onTouchEvent(ev)
            true
        }
    }

    private val hideZoom = Runnable {
        if (Motion.on()) zoomPill.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).start()
        else zoomPill.alpha = 0f
    }

    private fun showZoom(z: Float) {
        zoomPill.text = String.format(java.util.Locale.US, "%.1f×", z)
        zoomPill.translationY = -(bottomBar.height + dp(14)).toFloat()
        zoomPill.removeCallbacks(hideZoom)
        if (zoomPill.alpha < 1f && Motion.on()) {
            zoomPill.animate().cancel()
            if (zoomPill.alpha == 0f) { zoomPill.scaleX = 0.85f; zoomPill.scaleY = 0.85f }
            zoomPill.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).start()
        } else zoomPill.alpha = 1f
        zoomPill.postDelayed(hideZoom, 800)
    }

    /** Ketuk layar: fokus + ukur cahaya di titik itu (berguna untuk tulisan kecil / tabel). */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val pt = previewView.meteringPointFactory.createPoint(x, y)
        val act = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS).build()
        focusRing.show(previewView.left + x, previewView.top + y)
        val fut = cam.cameraControl.startFocusAndMetering(act)
        fut.addListener({
            try { if (fut.get().isFocusSuccessful) focusRing.locked() } catch (_: Throwable) {}
        }, ContextCompat.getMainExecutor(this))
    }

    /** Kilatan putih tipis di atas pratinjau saat memotret (tanpa mengganggu kamera). */
    private fun shutterFlash() {
        if (!Motion.on()) return
        val root = window.decorView as? FrameLayout ?: return
        val f = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(f, FrameLayout.LayoutParams(MATCH, MATCH))
        f.animate().alpha(0.55f).setDuration(60).withEndAction {
            f.animate().alpha(0f).setDuration(Motion.MED).setInterpolator(Motion.STANDARD).withEndAction { root.removeView(f) }.start()
        }.start()
    }

    private fun snap() {
        val cap = capture ?: return
        if (busyShot) return
        busyShot = true
        armed = false
        stableSince = 0L
        shutter.isEnabled = false
        if (Motion.on()) shutterRing.animate().alpha(0.45f).scaleX(0.94f).scaleY(0.94f).setStartDelay(0).setDuration(Motion.FAST).setInterpolator(Motion.STANDARD).start()
        shutterFlash()
        shutter.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        val dir = File(cacheDir, "scan").apply { mkdirs() }
        val f = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    ringBack()
                    shutter.isEnabled = true
                    busyShot = false
                    shots.add(f)
                    // terbang ke gambar kecil hanya bila kamera tetap terbuka (multi-halaman / KTP sisi depan)
                    updateThumb(f, fly = modeIdx == 0 || (modeIdx == 1 && shots.size == 1))
                    if (modeIdx == 1) { // KTP Mode
                        if (shots.size == 1) {
                            toast("Foto Sisi DEPAN KTP disimpan. Sekarang foto Sisi BELAKANG!")
                            hint("Step 2: Posisikan KTP Sisi BELAKANG di dalam bingkai")
                        } else {
                            finishWithShots()
                        }
                    } else if (modeIdx != 0) {
                        finishWithShots()
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    ringBack()
                    shutter.isEnabled = true
                    busyShot = false
                    toast("Gagal mengambil foto: ${e.message}")
                }
            }
        )
    }

    private fun ringBack() {
        shutterRing.runTo(0f, 220L)
        guide.runTo(0f, 220L)
        if (!Motion.on()) { shutterRing.alpha = 1f; return }
        shutterRing.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).start()
    }

    private fun updateThumb(f: File, fly: Boolean = true) {
        val bm = try { Pdf.decode(this, Uri.fromFile(f), 480) } catch (_: Throwable) { null }
        val land = {
            if (bm != null) thumb.setImageBitmap(bm)
            badge.visibility = View.VISIBLE
            badge.text = shots.size.toString()
            // gambar kecil "menerima" foto: memantul, lencana jumlah ikut memantul
            thumb.boing(0.86f)
            badge.boing(0.5f, 40L)
            thumbLabel.morphText("${shots.size} Halaman")
        }
        if (bm == null || !fly || !Motion.on() || !flyToThumb(bm, land)) land()
    }

    /**
     * Foto yang baru diambil "terbang" dari bingkai panduan ke gambar kecil di kiri bawah, mengecil & membulat
     * dengan pegas lembut — supaya jelas ke mana hasil jepretan pergi. Kembalikan false bila tidak bisa.
     */
    private fun flyToThumb(bm: android.graphics.Bitmap, land: () -> Unit): Boolean {
        val root = guide.parent as? FrameLayout ?: return false
        if (guide.width == 0 || thumb.width == 0) return false
        val from = guide.frame()
        val rl = IntArray(2); root.getLocationInWindow(rl)
        val gl = IntArray(2); guide.getLocationInWindow(gl)
        val tl = IntArray(2); thumb.getLocationInWindow(tl)
        val sx = gl[0] - rl[0] + from.left; val sy = gl[1] - rl[1] + from.top
        val sw = from.width(); val sh = from.height()
        val ex = (tl[0] - rl[0]).toFloat(); val ey = (tl[1] - rl[1]).toFloat()
        val ew = thumb.width.toFloat(); val eh = thumb.height.toFloat()
        val card = object : View(this) {
            val src = android.graphics.Rect(0, 0, bm.width, bm.height)
            val dst = android.graphics.RectF()
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            var radius = 0f
            val path = android.graphics.Path()
            override fun onDraw(c: Canvas) {
                // isi seperti CENTER_CROP di ukuran view saat ini (ukuran view diubah tiap frame, bukan skala → tidak gepeng)
                val vw = width.toFloat(); val vh = height.toFloat()
                val s = maxOf(vw / bm.width, vh / bm.height)
                val dw = bm.width * s; val dh = bm.height * s
                dst.set((vw - dw) / 2f, (vh - dh) / 2f, (vw + dw) / 2f, (vh + dh) / 2f)
                path.reset(); path.addRoundRect(0f, 0f, vw, vh, radius, radius, android.graphics.Path.Direction.CW)
                c.save(); c.clipPath(path); c.drawBitmap(bm, src, dst, p); c.restore()
            }
        }.apply { isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; elevation = dp(6).toFloat() }
        val lp = FrameLayout.LayoutParams(sw.toInt(), sh.toInt(), Gravity.TOP or Gravity.START)
        root.addView(card, lp)
        val r1 = dp(14).toFloat()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = Motion.SPRING_SOFT_MS
        a.interpolator = Motion.SPRING_SOFT
        a.startDelay = 70      // sesudah kilatan rana
        a.addUpdateListener {
            val t = it.animatedValue as Float
            val tc = t.coerceIn(0f, 1.04f)
            val w = sw + (ew - sw) * tc; val h = sh + (eh - sh) * tc
            card.translationX = sx + (ex - sx) * t
            card.translationY = sy + (ey - sy) * t
            card.radius = r1 * t.coerceIn(0f, 1f)
            if (lp.width != w.toInt() || lp.height != h.toInt()) { lp.width = maxOf(1, w.toInt()); lp.height = maxOf(1, h.toInt()); card.layoutParams = lp }
            card.invalidate()
        }
        var done = false
        val finish = { if (!done) { done = true; land(); root.removeView(card) } }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(x: android.animation.Animator) { finish() }
        })
        a.start()
        root.postDelayed({ finish() }, 70 + Motion.SPRING_SOFT_MS + 200L)
        return true
    }

    private fun finishWithShots() {
        if (shots.isEmpty()) return
        setResult(RESULT_OK, Intent().putStringArrayListExtra("files", ArrayList(shots.map { it.absolutePath }))
            .putExtra("mode", filters[filterIdx])
            .putExtra("isKtp", modeIdx == 1))
        finish()
    }

    private fun cancel() {
        shots.forEach { it.delete() }
        setResult(RESULT_CANCELED)
        finish()
    }
}
