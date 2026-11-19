package cloud.depup.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Mode Slideshow & Peragaan Presentasi (PPTX & PDF Presenter Mode).
 * Mode peragaan slide layar penuh dengan transisi halus, laser pointer virtual, timer, & auto-play.
 */
object PresenterSlideshowManager {

    fun startSlideshow(activity: AppCompatActivity, uri: Uri, title: String) {
        val total = try { Pdf.pageCount(activity, uri) } catch (_: Throwable) {
            activity.toast("Gagal memuat slide presentasi")
            return
        }

        var currentSlide = 0
        var isAutoPlay = false
        val handler = Handler(Looper.getMainLooper())
        var timerSeconds = 0

        activity.sheet { body, dismiss ->
            body.setBackgroundColor(Color.BLACK)
            val pad = activity.vbox().apply { setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12)) }

            // Header Slide & Timer
            val topRow = activity.hbox().apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleTv = activity.tv(title, 14f, Color.WHITE, true)
            val timerTv = activity.tv("00:00", 12f, Color.LTGRAY).apply {
                // jam dari paket ikon, bukan emoji ⏱
                val ck = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_clock)?.mutate()?.apply {
                    setTint(Color.LTGRAY); setBounds(0, 0, activity.dp(14), activity.dp(14))
                }
                setCompoundDrawablesRelative(ck, null, null, null)
                compoundDrawablePadding = activity.dp(5)
            }
            topRow.add(titleTv, w = 0, weight = 1f)
            topRow.add(timerTv, w = WRAP)
            pad.add(topRow)

            // Canvas Slide + Laser Pointer Container
            val slideFrame = FrameLayout(activity).apply {
                setBackgroundColor(Color.BLACK)
            }
            val slideIv = ZoomImageView(activity)
            val laserDot = View(activity).apply {
                background = activity.shape(Color.RED, 100)
                visibility = View.GONE
            }

            slideFrame.addView(slideIv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            slideFrame.addView(laserDot, FrameLayout.LayoutParams(activity.dp(16), activity.dp(16)))

            // Touch Laser Pointer
            slideFrame.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        laserDot.visibility = View.VISIBLE
                        laserDot.x = event.x - activity.dp(8)
                        laserDot.y = event.y - activity.dp(8)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        laserDot.visibility = View.GONE
                    }
                }
                false
            }

            pad.add(slideFrame, h = activity.dp(340), top = 8)

            // Status Bar
            val statusTv = activity.tv("Slide 1 / $total", 13f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
            }
            pad.add(statusTv, top = 6)

            fun renderSlide() {
                val pfd = activity.contentResolver.openFileDescriptor(uri, "r") ?: return
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                if (currentSlide in 0 until total) {
                    val pg = renderer.openPage(currentSlide)
                    val bmp = Bitmap.createBitmap(pg.width * 2, pg.height * 2, Bitmap.Config.ARGB_8888)
                    pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pg.close()
                    slideIv.setImageBitmap(bmp)
                    statusTv.text = "Slide ${currentSlide + 1} / $total"
                }
                renderer.close()
                pfd.close()
            }

            renderSlide()

            // Timer Loop
            val timerRunnable = object : Runnable {
                override fun run() {
                    timerSeconds++
                    val mins = timerSeconds / 60
                    val secs = timerSeconds % 60
                    timerTv.text = String.format("%02d:%02d", mins, secs)
                    handler.postDelayed(this, 1000)
                }
            }
            handler.post(timerRunnable)

            // AutoPlay Loop
            val autoPlayRunnable = object : Runnable {
                override fun run() {
                    if (isAutoPlay && currentSlide < total - 1) {
                        currentSlide++
                        renderSlide()
                        handler.postDelayed(this, 4000)
                    } else if (currentSlide >= total - 1) {
                        isAutoPlay = false
                    }
                }
            }

            // Controls
            val ctrlRow = activity.hbox().apply {
                gravity = Gravity.CENTER

                val btnPrev = activity.btn("Sblm", false, C.ACCENT, R.drawable.ic_back) {
                    if (currentSlide > 0) {
                        currentSlide--
                        renderSlide()
                    }
                }

                val btnAuto = activity.btn("AutoPlay", false, C.ACCENT, R.drawable.ic_play) {
                    isAutoPlay = !isAutoPlay
                    if (isAutoPlay) {
                        activity.toast("AutoPlay aktif (4 detik/slide)")
                        handler.post(autoPlayRunnable)
                    } else {
                        handler.removeCallbacks(autoPlayRunnable)
                    }
                }

                val btnNext = activity.btn("Brkt", true, C.PRIMARY, R.drawable.ic_next, iconEnd = true) {
                    if (currentSlide < total - 1) {
                        currentSlide++
                        renderSlide()
                    }
                }

                add(btnPrev, w = 0, weight = 1f)
                add(btnAuto, w = 0, weight = 1f, side = 6)
                add(btnNext, w = 0, weight = 1f, side = 6)
            }

            pad.add(ctrlRow, top = 12)

            // Exit
            val exitBtn = activity.btn("Keluar Slideshow", false, C.RED) {
                handler.removeCallbacks(timerRunnable)
                handler.removeCallbacks(autoPlayRunnable)
                dismiss()
            }
            pad.add(exitBtn, top = 8)

            body.add(pad)
        }
    }
}
