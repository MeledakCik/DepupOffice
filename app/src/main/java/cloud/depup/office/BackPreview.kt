package cloud.depup.office

import android.app.Activity
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback

/**
 * Tombol/gestur Kembali dengan PRATINJAU prediktif (Android 14+), seirama kaca cair:
 * selama ibu jari menahan gestur kembali, seluruh isi layar mengecil (hingga 90%), bergeser menjauhi tepi gestur,
 * dan sudutnya membulat — di atas latar gelap, seperti layar yang "diangkat". Dibatalkan → memantul kembali
 * dengan pegas. Dilepas → [onBack] dijalankan (tutup layar, tanya simpan, dst.); bila layar ternyata tidak ditutup
 * (mis. muncul dialog "Simpan perubahan?"), isi layar memantul kembali ke ukuran penuh.
 *
 * Di Android < 14 (tanpa progres gestur) perilakunya sama dengan callback biasa: [onBack] langsung dijalankan.
 */
open class PreviewBack(private val act: Activity, enabled: Boolean = true, private val onBack: () -> Unit) :
    OnBackPressedCallback(enabled) {

    private var oldBg: Drawable? = null
    private var previewing = false
    private var radius = 0f

    private fun target(): View? = act.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)

    private val outline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, radius) }
    }

    private fun begin(v: View) {
        if (previewing) return
        previewing = true
        v.animate().cancel()
        // latar di balik layar yang mengecil: gelap, agar tepi & sudut membulatnya terlihat
        oldBg = act.window.decorView.background
        act.window.setBackgroundDrawable(ColorDrawable(if (C.dark) 0xFF000000.toInt() else 0xFF1C1C1F.toInt()))
        v.outlineProvider = outline
        v.clipToOutline = true
    }

    private fun end(v: View) {
        previewing = false
        radius = 0f
        v.clipToOutline = false
        v.outlineProvider = ViewOutlineProvider.BACKGROUND
        oldBg?.let { act.window.setBackgroundDrawable(it) }
        oldBg = null
    }

    private fun springBack(v: View) {
        if (!Motion.on()) { v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f; end(v); return }
        val r0 = radius
        v.animate().scaleX(1f).scaleY(1f).translationX(0f).setStartDelay(0).setDuration(Motion.SPRING_MS)
            .setInterpolator(Motion.SPRING)
            .setUpdateListener { a -> radius = r0 * (1f - a.animatedFraction); v.invalidateOutline() }
            .withEndAction { end(v) }.start()
    }

    override fun handleOnBackStarted(backEvent: BackEventCompat) {
        target()?.let { begin(it) }
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) {
        val v = target() ?: return
        if (!Motion.on()) return
        begin(v)
        val p = backEvent.progress
        val e = 1f - (1f - p) * (1f - p)               // melambat menjelang ujung (terasa berat seperti kaca)
        val fromLeft = backEvent.swipeEdge == BackEventCompat.EDGE_LEFT
        v.pivotX = if (fromLeft) v.width * 0.8f else v.width * 0.2f
        v.pivotY = v.height / 2f
        val s = 1f - 0.10f * e
        v.scaleX = s; v.scaleY = s
        v.translationX = (if (fromLeft) 1f else -1f) * v.context.dp(14) * e
        radius = v.context.dp(28) * e
        v.invalidateOutline()
    }

    override fun handleOnBackCancelled() {
        target()?.let { springBack(it) }
    }

    override fun handleOnBackPressed() {
        onBack()
        // layar tidak ditutup (dialog konfirmasi, pindah tab, ...): kembali utuh dengan pegas
        val v = target() ?: return
        if (!act.isFinishing && previewing) springBack(v)
    }
}
