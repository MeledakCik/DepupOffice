package cloud.depup.office

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Garis pindai seperti mesin pemindai: bergerak naik-turun di dalam sebuah area (melambat di ujung) dengan cahaya
 * biru DepUp dan jejak cahaya di belakang arah geraknya; kedua ujung garis memudar. Dipasang pada View pemilik:
 * panggil [draw] dari onDraw-nya dan [start] / [stop] untuk menyalakan / mematikan (memudar masuk & keluar).
 * Tidak tampil bila animasi dimatikan di pengaturan HP (pemanggil tetap menampilkan teks status).
 */
class ScanSweep(private val host: View, private val passMs: Long = 1100L) {
    /** 0..1 selama garis tampil (untuk meredupkan gambar di bawahnya seiring masuk / keluarnya garis). */
    var level = 0f
        private set
    private var on = false
    private var pos = 0f                  // 0 = atas, 1 = bawah
    private var down = true               // arah gerak: jejak digambar di belakang garis
    private var sweep: ValueAnimator? = null
    private var fade: ValueAnimator? = null
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadeW = -1f
    private var shadeH = -1f

    fun start() {
        if (on) return
        on = true
        if (!Motion.on()) return
        if (sweep?.isRunning != true) {
            pos = 0f; down = true
            sweep = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = passMs
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    val v = it.animatedValue as Float
                    if (v != pos) down = v > pos
                    pos = v
                    host.invalidate()
                }
                start()
            }
        }
        fadeTo(1f, 260L)
    }

    fun stop() {
        if (!on) return
        on = false
        if (!Motion.on()) { cancel(); return }
        fadeTo(0f, 240L)
    }

    /** Hentikan seketika (mis. view dilepas dari layar). */
    fun cancel() {
        fade?.removeAllListeners(); fade?.cancel(); fade = null
        sweep?.cancel(); sweep = null
        on = false; level = 0f
        host.invalidate()
    }

    private fun fadeTo(to: Float, ms: Long) {
        fade?.removeAllListeners()      // pudar sebelumnya dibatalkan tanpa ikut menghentikan gerak garis
        fade?.cancel()
        fade = ValueAnimator.ofFloat(level, to).apply {
            duration = ms
            interpolator = Motion.STANDARD
            addUpdateListener { level = it.animatedValue as Float; host.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (!on) { sweep?.cancel(); sweep = null } }
            })
            start()
        }
    }

    /** Gambar garis di dalam persegi l,t,r,b (koordinat kanvas pemilik). */
    fun draw(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        if (level <= 0.01f) return
        val d = host.resources.displayMetrics.density
        val inset = 6f * d
        val x0 = l + inset; val x1 = r - inset
        val top = t + inset; val bot = b - inset
        val w = x1 - x0
        if (w <= 0f || bot <= top) return
        val trailH = minOf((bot - top) * 0.26f, 150f * d)
        val glowH = 7f * d
        if (w != shadeW || trailH != shadeH) shades(w, trailH, glowH)
        val y = top + (bot - top) * pos
        val a = 255f * level
        c.save()
        c.clipRect(x0, top, x1, bot)
        c.translate(x0, y)
        // jejak cahaya di belakang garis: di atas saat turun, di bawah saat naik
        c.save()
        if (down) c.scale(1f, -1f)
        trail.alpha = (a * 0.85f).toInt()
        c.drawRect(0f, 0f, w, trailH, trail)
        c.restore()
        glow.alpha = (a * 0.85f).toInt()
        c.drawRect(0f, -glowH, w, glowH, glow)
        val lh = 1f * d
        line.alpha = a.toInt()
        c.drawRoundRect(0f, -lh, w, lh, lh, lh, line)
        c.restore()
    }

    /** Gradasi garis, cahaya & jejak — dibuat ulang hanya saat ukuran area berubah. Kedua ujungnya memudar. */
    private fun shades(w: Float, trailH: Float, glowH: Float) {
        shadeW = w; shadeH = trailH
        val stops = floatArrayOf(0f, 0.14f, 0.86f, 1f)
        val fade = intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000)
        fun mask() = LinearGradient(0f, 0f, w, 0f, fade, stops, Shader.TileMode.CLAMP)
        val blue = BLUE and 0x00FFFFFF
        trail.shader = ComposeShader(
            LinearGradient(0f, 0f, 0f, trailH, intArrayOf(blue or 0x66000000, blue or 0x1A000000, blue),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP),
            mask(), PorterDuff.Mode.DST_IN)
        glow.shader = ComposeShader(
            LinearGradient(0f, -glowH, 0f, glowH, intArrayOf(blue, blue or 0xB3000000.toInt(), blue), null, Shader.TileMode.CLAMP),
            mask(), PorterDuff.Mode.DST_IN)
        line.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(CORE and 0x00FFFFFF, CORE, CORE, CORE and 0x00FFFFFF),
            stops, Shader.TileMode.CLAMP)
    }

    private companion object {
        /** Biru merek DepUp yang dicerahkan agar terbaca di atas foto & latar gelap. */
        const val BLUE = 0xFF4D7BFF.toInt()
        const val CORE = 0xFFE8EEFF.toInt()
    }
}
