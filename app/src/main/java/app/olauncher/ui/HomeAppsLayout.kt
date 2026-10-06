package app.olauncher.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import app.olauncher.helper.dpToPx

/*
The home app list. Never draws over the clock area: anything above [clipTop] is hidden and
content reaching it fades out, as it does at the bottom edge. When an open folder makes the
list too tall to fit, the list shifts so the folder stays fully in view.
*/

class HomeAppsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    // Bottom of the clock area, in this view's coordinates
    var clipTop = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    // Rows that should stay visible, e.g. an open folder's name and its apps
    private var focusFirst: View? = null
    private var focusLast: View? = null

    private val fadeLength = 32.dpToPx().toFloat()
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var scrollAnimator: ObjectAnimator? = null

    fun keepInView(first: View?, last: View?) {
        focusFirst = first
        focusLast = last
        requestLayout()
    }

    fun resetScroll() {
        focusFirst = null
        focusLast = null
        scrollAnimator?.cancel()
        scrollY = 0
    }

    private val windowTop get() = maxOf(paddingTop, clipTop)
    private val windowBottom get() = height - paddingBottom

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Final positions are known here, before any layout transition animates the rows
        val first = focusFirst
        val last = focusLast
        val target = if (first == null || last == null || first.parent != this || last.parent != this) 0
        else {
            val lowest = last.bottom - windowBottom
            val highest = first.top - windowTop
            if (lowest <= highest) 0.coerceIn(lowest, highest) else highest
        }
        if (target == scrollY && scrollAnimator?.isRunning != true) return
        scrollAnimator?.cancel()
        scrollAnimator = ObjectAnimator.ofInt(this, "scrollY", target).setDuration(180L).apply { start() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        var contentTop = Int.MAX_VALUE
        var contentBottom = Int.MIN_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            contentTop = minOf(contentTop, child.top)
            contentBottom = maxOf(contentBottom, child.bottom)
        }
        val fadeTop = contentTop != Int.MAX_VALUE && contentTop - scrollY < windowTop + fadeLength
        val fadeBottom = contentBottom != Int.MIN_VALUE && contentBottom - scrollY > windowBottom
        if (!fadeTop && !fadeBottom) {
            super.dispatchDraw(canvas)
            return
        }

        // The canvas is already offset by scrollY, so the window moves with it
        val left = scrollX.toFloat()
        val right = left + width
        val top = (scrollY + windowTop).toFloat()
        val bottom = (scrollY + windowBottom).toFloat()
        val save = canvas.saveLayer(left, scrollY.toFloat(), right, (scrollY + height).toFloat(), null)
        canvas.clipRect(left, top, right, bottom)
        super.dispatchDraw(canvas)
        if (fadeTop) {
            fadePaint.shader = LinearGradient(0f, top, 0f, top + fadeLength, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(left, top, right, top + fadeLength, fadePaint)
        }
        if (fadeBottom) {
            fadePaint.shader = LinearGradient(0f, bottom - fadeLength, 0f, bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            canvas.drawRect(left, bottom - fadeLength, right, bottom, fadePaint)
        }
        canvas.restoreToCount(save)
    }
}
