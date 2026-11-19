package cloud.depup.office

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.transition.TransitionValues
import android.transition.Visibility
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Transisi menu pop-up (PopupMenu & semua PopupWindow bergaya [R.style.PopupMenu]): menu TUMBUH dari tombol yang
 * diketuk dengan pantulan pegas ([Motion.SPRING]) sambil menebal cepat; ditutup menyusut ke arah yang sama sambil
 * memudar. Titik tumbuh = pusat tombol pemicu (epicenter dari PopupWindow), menempel ke tepi atas/bawah menu.
 *
 * Dipasang lewat tema (res/transition/menu_pop.xml), jadi semua menu ikut tanpa mengubah kode pemanggilnya.
 * Durasi diatur di sini — JANGAN beri android:duration di XML (Transition akan menimpa durasi tiap animator).
 */
class MenuPop(ctx: Context, attrs: AttributeSet?) : Visibility(ctx, attrs) {

    private fun pivot(v: View) {
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        val e = epicenter
        if (e != null && w > 0f && h > 0f) {
            v.pivotX = (e.exactCenterX() - v.left).coerceIn(0f, w)
            v.pivotY = if (e.exactCenterY() - v.top < h / 2f) 0f else h
        } else {
            v.pivotX = w; v.pivotY = 0f   // cadangan: dari pojok kanan atas (letak tombol ⋮ yang lazim)
        }
    }

    override fun onAppear(sceneRoot: ViewGroup, view: View, startValues: TransitionValues?, endValues: TransitionValues?): Animator {
        pivot(view)
        val scale = AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, 0.55f, 1f), ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.55f, 1f))
            duration = Motion.SPRING_MS
            interpolator = Motion.SPRING
        }
        val fade = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply { duration = Motion.FAST; interpolator = Motion.STANDARD }
        return AnimatorSet().apply {
            playTogether(scale, fade)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { view.scaleX = 1f; view.scaleY = 1f; view.alpha = 1f }
            })
        }
    }

    override fun onDisappear(sceneRoot: ViewGroup, view: View, startValues: TransitionValues?, endValues: TransitionValues?): Animator {
        pivot(view)
        return AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.86f), ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.86f),
                ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f))
            duration = Motion.FAST
            interpolator = Motion.EXIT
        }
    }
}
