package cloud.depup.office

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/** Bingkai panduan dokumen/KTP + grid 3x3 di atas pratinjau kamera. */
class GuideView(ctx: Context) : View(ctx) {
    var ratio = 0.707f      // lebar / tinggi bingkai
    var grid = false
    private val dim = Paint().apply { color = 0x66000000 }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22D3EE.toInt(); style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1).toFloat()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        var fw = w * 0.86f
        var fh = fw / ratio
        if (fh > h * 0.92f) { fh = h * 0.92f; fw = fh * ratio }
        val l = (w - fw) / 2f; val t = (h - fh) / 2f; val r = l + fw; val b = t + fh
        c.drawRect(0f, 0f, w, t, dim); c.drawRect(0f, b, w, h, dim)
        c.drawRect(0f, t, l, b, dim); c.drawRect(r, t, w, b, dim)
        if (grid) for (i in 1..2) {
            c.drawLine(l + fw * i / 3f, t, l + fw * i / 3f, b, thin)
            c.drawLine(l, t + fh * i / 3f, r, t + fh * i / 3f, thin)
        }
        val L = context.dp(26).toFloat()
        c.drawLines(floatArrayOf(
            l, t + L, l, t, l, t, l + L, t,
            r - L, t, r, t, r, t, r, t + L,
            l, b - L, l, b, l, b, l + L, b,
            r - L, b, r, b, r, b, r, b - L
        ), edge)
    }
}

/** Viewfinder sendiri (CameraX): mode multi-halaman / KTP / dokumen, flash, grid, filter bawaan. */
class CameraActivity : AppCompatActivity() {
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

    private lateinit var flashLabel: TextView
    private lateinit var thumb: ImageView
    private lateinit var badge: TextView
    private lateinit var thumbLabel: TextView
    private lateinit var filterLabel: TextView
    private val tabs = mutableListOf<TextView>()
    private lateinit var hintPill: TextView
    private lateinit var shutter: View
    private lateinit var autoPill: TextView

    // ---- Pindai otomatis saat stabil ----
    private var autoScan = true
    private val analyzer: ExecutorService = Executors.newSingleThreadExecutor()
    private var prevLuma: IntArray? = null
    @Volatile private var stableSince = 0L
    @Volatile private var armed = true            // false setelah jepret: tunggu kamera digerakkan dulu
    @Volatile private var busyShot = false
    private var lastHint = 0L

    private val askCam = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { toast("Izin kamera diperlukan untuk memindai"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); fitsSystemWindows = true }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        guide = GuideView(this)
        root.addView(guide, FrameLayout.LayoutParams(MATCH, MATCH))

        // ----- atas: tutup, flash, grid -----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        fun round(res: Int, onClick: () -> Unit) = FrameLayout(this).apply {
            background = shape(0x66000000, 100)
            addView(icon(res, Color.WHITE, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { onClick() }
        }
        top.add(round(R.drawable.ic_close) { cancel() }, w = dp(44), h = dp(44))
        top.add(View(this), w = 0, weight = 1f)
        val fp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(0x66000000, 100)
            setPadding(dp(14), dp(8), dp(6), dp(8))
            setOnClickListener { cycleFlash() }
        }
        fp.add(icon(R.drawable.ic_flash, 0xFFFACC15.toInt(), 18), w = dp(18), h = dp(18))
        flashLabel = tv("Otomatis", 13f, Color.WHITE, true).apply {
            background = shape(C.ACCENT, 100)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        fp.add(flashLabel, w = WRAP, side = 8)
        top.add(fp, w = WRAP)
        top.add(View(this), w = 0, weight = 1f)
        top.add(round(R.drawable.ic_grid) { guide.grid = !guide.grid; guide.invalidate() }, w = dp(44), h = dp(44))
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        hintPill = tv("Letakkan dokumen di dalam bingkai & permukaan kontras", 12f, Color.WHITE).apply {
            background = shape(0x99000000.toInt(), 100)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            gravity = Gravity.CENTER
        }
        root.addView(hintPill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(72) })

        autoPill = tv("", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { autoScan = !autoScan; stableSince = 0L; armed = true; paintAuto() }
        }
        root.addView(autoPill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(112) })
        paintAuto()

        // ----- bawah -----
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE60B1220.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("Multi-Halaman", "KTP / ID Card", "Dokumen (PDF)").forEachIndexed { i, n ->
            val t = tv(n, 13f, 0xFFCBD5E1.toInt(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnClickListener { setMode(i) }
            }
            tabs.add(t)
            tabRow.add(t, w = WRAP)
        }
        bottom.add(tabRow)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // kiri: thumbnail
        val left = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val tf = FrameLayout(this)
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(0x33FFFFFF, 14, Color.WHITE, 2)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setOnClickListener { finishWithShots() }
        }
        tf.addView(thumb, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        badge = tv("0", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.ACCENT) }
            visibility = View.GONE
        }
        tf.addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END))
        left.add(tf, w = dp(64), h = dp(64))
        thumbLabel = tv("0 Halaman", 11f, Color.WHITE)
        left.add(thumbLabel, top = 2, w = WRAP)
        actions.add(left, w = 0, weight = 1f)

        // tengah: shutter
        val outer = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(4), Color.WHITE) }
        }
        shutter = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.ACCENT) }
            addView(icon(R.drawable.ic_camera, Color.WHITE, 28), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            setOnClickListener { snap() }
        }
        outer.addView(shutter, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        actions.add(outer, w = dp(84), h = dp(84))

        // kanan: filter bawaan
        val right = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        right.add(FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            addView(icon(R.drawable.ic_wand, Color.WHITE, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { filterIdx = (filterIdx + 1) % filters.size; filterLabel.text = filterNames[filterIdx] }
        }, w = dp(56), h = dp(56))
        filterLabel = tv(filterNames[0], 11f, Color.WHITE)
        right.add(filterLabel, top = 10, w = WRAP)
        actions.add(right, w = 0, weight = 1f)
        bottom.add(actions, top = 12)
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        setContentView(root)
        setMode(2)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancel() }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCam.launch(Manifest.permission.CAMERA)
    }

    private fun paintAuto() {
        autoPill.text = if (autoScan) "● Pindai otomatis: AKTIF" else "○ Pindai otomatis: MATI"
        autoPill.background = shape(if (autoScan) 0xE60D9488.toInt() else 0x99000000.toInt(), 100)
    }

    private fun hint(t: String) {
        runOnUiThread { hintPill.text = t }
    }

    /**
     * Berjalan di thread analisis: bandingkan kecerahan 24x32 titik antar-frame. Bila gambar nyaris tidak berubah
     * ±1,2 detik dan cukup berkontras (ada dokumen, bukan permukaan kosong), jepret otomatis.
     */
    private fun analyze(img: ImageProxy) {
        try {
            if (!autoScan || busyShot) { prevLuma = null; stableSince = 0L; return }
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
                if (stableSince == 0L) stableSince = now
                val held = now - stableSince
                if (held >= 1200L) {
                    stableSince = 0L
                    runOnUiThread { snap() }
                } else if (now - lastHint > 300L) {
                    lastHint = now
                    hint("Tahan stabil… ${held * 100L / 1200L}%")
                }
            } else {
                stableSince = 0L
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
        modeIdx = i
        tabs.forEachIndexed { k, t ->
            val on = k == i
            t.setTextColor(if (on) Color.WHITE else 0xFFCBD5E1.toInt())
            t.background = if (on) shape(C.ACCENT, 100) else null
        }
        guide.ratio = if (i == 1) 1.586f else 0.707f
        guide.invalidate()
        hintPill.text = when (i) {
            0 -> "Ambil beberapa halaman, lalu ketuk gambar kecil untuk lanjut"
            1 -> "Posisikan KTP / kartu di dalam bingkai"
            else -> "Letakkan dokumen di dalam bingkai & permukaan kontras"
        }
    }

    private fun flashConst() = when (flash) {
        1 -> ImageCapture.FLASH_MODE_ON
        2 -> ImageCapture.FLASH_MODE_OFF
        else -> ImageCapture.FLASH_MODE_AUTO
    }

    private fun cycleFlash() {
        flash = (flash + 1) % 3
        flashLabel.text = arrayOf("Otomatis", "Nyala", "Mati")[flash]
        capture?.flashMode = flashConst()
    }

    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            try {
                val provider = fut.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashConst()).build()
                capture = cap
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzer) { img -> analyze(img) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap, analysis)
            } catch (e: Exception) {
                toast("Kamera tidak bisa dibuka: ${e.message}")
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun snap() {
        val cap = capture ?: return
        if (busyShot) return
        busyShot = true
        armed = false
        stableSince = 0L
        shutter.isEnabled = false
        val dir = File(cacheDir, "scan").apply { mkdirs() }
        val f = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    shutter.isEnabled = true
                    busyShot = false
                    shots.add(f)
                    updateThumb(f)
                    if (modeIdx == 1) { // KTP Mode
                        if (shots.size == 1) {
                            toast("✓ Foto Sisi DEPAN KTP disimpan. Sekarang foto Sisi BELAKANG!")
                            hint("Step 2: Posisikan KTP Sisi BELAKANG di dalam bingkai")
                        } else {
                            finishWithShots()
                        }
                    } else if (modeIdx != 0) {
                        finishWithShots()
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    shutter.isEnabled = true
                    busyShot = false
                    toast("Gagal mengambil foto: ${e.message}")
                }
            }
        )
    }

    private fun updateThumb(f: File) {
        try { thumb.setImageBitmap(Pdf.decode(this, Uri.fromFile(f), 220)) } catch (_: Throwable) {}
        badge.visibility = View.VISIBLE
        badge.text = shots.size.toString()
        thumbLabel.text = "${shots.size} Halaman"
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
