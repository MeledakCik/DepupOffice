package cloud.depup.office

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * Notifikasi kapsul "kaca buram": latar di belakang kapsul diambil dengan PixelCopy (GPU, ikut menangkap pratinjau
 * kamera), diperkecil 1/8 lalu di-blur — murah bahkan di HP kelas bawah — dan dilapisi warna transparan + garis tipis.
 * Android 13+: latar buram itu dilewatkan lensa kaca cair ([LiquidGlass.lens]): refraksi tepi, dispersi, kilau tepi
 * & tint adaptif — digambar sebagai cat dari bitmap (bukan efek GPU), jadi selalu utuh. Kapsul selalu diletakkan di
 * atas kaca bawah yang melayang — tidak ada kaca di atas kaca.
 *
 * Toast sistem tidak bisa diberi tampilan khusus sejak Android 11, jadi kapsul ini digambar di atas Activity.
 * Bila Activity sedang ditutup (pola `toast(...); finish()`), otomatis kembali ke Toast sistem agar pesan tidak hilang.
 */
object GlassToast {
    private val main = Handler(Looper.getMainLooper())
    private var current: WeakReference<View>? = null
    private const val DOWNSCALE = 8

    fun show(act: Activity, msg: String, long: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { show(act, msg, long) }; return }
        // Ditunda satu giliran: kalau pemanggil langsung finish(), pakai Toast sistem.
        main.post {
            if (act.isFinishing || act.isDestroyed) {
                Toast.makeText(act.applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            } else try { attach(act, msg, long) } catch (_: Throwable) {
                Toast.makeText(act.applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun attach(act: Activity, msg: String, long: Boolean) {
        val decor = act.window.decorView as? ViewGroup ?: throw IllegalStateException()
        current?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val ctx = act
        val dark = C.dark
        val radius = ctx.dp(100).toFloat()

        val capsule = FrameLayout(ctx).apply {
            alpha = 0f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.height / 2f) }
            }
            clipToOutline = true
            elevation = ctx.dp(6).toFloat()
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val glass = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
        capsule.addView(glass, FrameLayout.LayoutParams(0, 0))   // ukuran diisi setelah teks diukur
        // Kaca notifikasi = tangkapan layar statis yang di-blur (PixelCopy), bukan efek GPU hidup: notifikasi
        // berumur pendek, dan cara ini paling aman di semua HP.
        // Lapisan warna kaca: cukup tembus pandang agar blur terlihat, cukup pekat agar teks tetap terbaca.
        val tint = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                val ink = C.PANEL
                // alfa bawaan 224, digeser setelan transparansi kaca (Saya → Tampilan)
                setColor(Color.argb(Glass.toneAlpha(224), Color.red(ink), Color.green(ink), Color.blue(ink)))
                setStroke(ctx.dp(1), if (dark) Color.argb(40, 0, 0, 0) else Color.argb(36, 255, 255, 255))
            }
        }
        capsule.addView(tint, FrameLayout.LayoutParams(0, 0))
        val text = TextView(ctx).apply {
            this.text = msg
            textSize = 14f
            setTextColor(C.ON_PANEL)
            gravity = Gravity.CENTER
            maxLines = 4
            setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
            try { typeface = Fonts.label(ctx) } catch (_: Throwable) {}
        }
        capsule.addView(text, FrameLayout.LayoutParams(WRAP, WRAP))

        val insets = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        // tidak ada kaca di atas kaca: kapsul notifikasi selalu duduk di atas navigasi/kapsul kaca yang melayang
        val scaffold = GlassContent.find(decor)?.parent as? GlassScaffold
        val glassTop = scaffold?.floatingTop()
        val bottom = max((insets?.bottom ?: 0) + ctx.dp(84), if (glassTop != null && decor.height > 0) decor.height - glassTop + ctx.dp(12) else 0)
        val side = ctx.dp(24)
        val maxW = min(decor.width - side * 2, ctx.dp(440)).takeIf { it > 0 } ?: WRAP
        decor.addView(capsule, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = bottom
        })
        text.maxWidth = maxW
        current = WeakReference(capsule)

        capsule.setOnClickListener { dismiss(capsule) }
        capsule.post {
            if (!capsule.isAttachedToWindow) return@post
            // Lapisan kaca & warna mengikuti ukuran teks (kalau MATCH_PARENT, kapsul WRAP ikut memenuhi layar).
            tint.layoutParams = FrameLayout.LayoutParams(capsule.width, capsule.height)
            capture(act, capsule) { bmp ->
                // Ukuran gambar kaca dikunci ke ukuran kapsul — tanpa ini bitmap kecil ikut "mengembangkan" kapsul.
                if (bmp != null) {
                    glass.layoutParams = FrameLayout.LayoutParams(capsule.width, capsule.height)
                    // Android 13+: latar buram dilewatkan lensa kaca cair (refraksi tepi, dispersi, kilau, tint
                    // adaptif yang menjaga teks terang tetap terbaca) — lapisan warna pekat tidak diperlukan lagi
                    val lens = if (android.os.Build.VERSION.SDK_INT >= 33) LiquidGlass.lens(ctx, bmp, true, C.PANEL, Glass.toneTint(0.5f)) else null
                    if (lens != null) { glass.setImageDrawable(lens); tint.background = null }
                    else glass.setImageBitmap(bmp)
                }
                else tint.background = GradientDrawable().apply {   // tanpa tangkapan: kaca lebih pekat
                    cornerRadius = radius
                    setColor(C.PANEL)
                }
                // naik sedikit sambil menebal, mendarat lembut — tanpa pantulan
                capsule.translationY = ctx.dp(12).toFloat(); capsule.scaleX = 0.96f; capsule.scaleY = 0.96f
                scaffold?.holdWhole(Motion.SLOW + 200L)   // bayangan kapsul yang bergerak bisa menyentuh kaca di bawahnya
                capsule.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(Motion.SLOW)
                    .setInterpolator(Motion.ENTER).start()
                capsule.announceForAccessibility(msg)
            }
            main.postDelayed({ dismiss(capsule) }, if (long) 3200L else 2000L)
        }
    }

    private fun dismiss(v: View) {
        if (!v.isAttachedToWindow) return
        ((v.parent as? ViewGroup)?.let { GlassContent.find(it) }?.parent as? GlassScaffold)?.holdWhole(Motion.FAST + 200L)
        v.animate().cancel()
        v.animate().alpha(0f).translationY(v.dp(8).toFloat()).setDuration(Motion.FAST).setInterpolator(Motion.EXIT).withEndAction {
            (v.parent as? ViewGroup)?.removeView(v)
            ((v as? ViewGroup)?.getChildAt(0) as? ImageView)?.setImageDrawable(null)
        }.start()
    }

    /** Salin area layar di belakang kapsul (kapsul masih transparan), perkecil, lalu blur. */
    private fun capture(act: Activity, v: View, done: (Bitmap?) -> Unit) {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val src = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        if (src.width() <= 0 || src.height() <= 0) { done(null); return }
        val bmp = Bitmap.createBitmap(max(1, src.width() / DOWNSCALE), max(1, src.height() / DOWNSCALE), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(act.window, src, bmp, { res ->
                if (res == PixelCopy.SUCCESS) {
                    val r = Glass.softRadius()   // kekuatan blur dari setelan (0 = tajam)
                    if (r > 0) { boxBlur(bmp, r); boxBlur(bmp, r) }
                    done(bmp)
                } else { bmp.recycle(); done(null) }
            }, main)
        } catch (_: Throwable) { bmp.recycle(); done(null) }
    }

    /** Blur kotak sederhana (horizontal + vertikal) pada bitmap kecil — puluhan piksel, jadi instan. */
    private fun boxBlur(b: Bitmap, r: Int) {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        fun pass(inp: IntArray, out: IntArray, horiz: Boolean) {
            val n = if (horiz) w else h; val lines = if (horiz) h else w
            for (l in 0 until lines) for (i in 0 until n) {
                var rs = 0; var gs = 0; var bs = 0; var c = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, n - 1)
                    val p = if (horiz) inp[l * w + j] else inp[j * w + l]
                    rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255; c++
                }
                out[if (horiz) l * w + i else i * w + l] = (0xFF shl 24) or ((rs / c) shl 16) or ((gs / c) shl 8) or (bs / c)
            }
        }
        pass(px, tmp, true); pass(tmp, px, false)
        b.setPixels(px, 0, w, 0, 0, w, h)
    }
}
