package app.olauncher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/*
Hosts the home screen and the app drawer. The drawer follows the finger:
drag up on home to pull it open, drag down from the top of the list to push it closed.
*/

class DrawerHostLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    lateinit var content: View
    lateinit var drawer: View

    var canDrawerScrollUp: () -> Boolean = { false }
    var onDragStart: (wasOpen: Boolean) -> Unit = {}
    var onOpened: () -> Unit = {}
    var onClosed: () -> Unit = {}

    // 0 = closed, 1 = fully open
    private var progress = 0f
    val isOpen get() = progress > 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 8
    private var velocityTracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var dragStartProgress = 0f
    private var dragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyProgress(progress)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                trackVelocity(ev, reset = true)
                // Catch the drawer mid-animation
                if (animator?.isRunning == true) {
                    startDrag()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                trackVelocity(ev)
                if (shouldStartDrag(ev)) {
                    startDrag()
                    downY = ev.y
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycleVelocity()
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            // Reached when no child takes the touch, e.g. empty space in the drawer
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                trackVelocity(ev, reset = true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                trackVelocity(ev)
                if (!dragging && shouldStartDrag(ev)) {
                    startDrag()
                    downY = ev.y
                }
                if (dragging && height > 0)
                    applyProgress((dragStartProgress - (ev.y - downY) / height).coerceIn(0f, 1f))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    trackVelocity(ev)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    dragging = false
                    settle(
                        open = when {
                            velocityY < -flingVelocity -> true
                            velocityY > flingVelocity -> false
                            else -> progress > 0.5f
                        }
                    )
                }
                recycleVelocity()
            }
        }
        return true
    }

    fun open() = settle(open = true)

    fun close(animate: Boolean = true) {
        if (!isOpen && animator?.isRunning != true) return
        if (animate) settle(open = false)
        else {
            animator?.cancel()
            applyProgress(0f)
            onClosed()
        }
    }

    private fun shouldStartDrag(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) < touchSlop || abs(dy) < abs(dx)) return false
        return if (progress < 1f) dy < 0 // closed: only an upward drag opens
        else dy > 0 && !canDrawerScrollUp() // open: downward drag once the list is at the top
    }

    private fun startDrag() {
        animator?.cancel()
        dragging = true
        dragStartProgress = progress
        onDragStart(progress >= 1f)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun settle(open: Boolean) {
        animator?.cancel()
        val target = if (open) 1f else 0f
        val distance = abs(target - progress)
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = (300 * distance).toLong().coerceAtLeast(120)
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    if (open) onOpened() else onClosed()
                }
            })
            start()
        }
    }

    private fun applyProgress(value: Float) {
        progress = value
        if (!::drawer.isInitialized) return
        drawer.translationY = (1f - value) * height
        drawer.visibility = if (value > 0f) View.VISIBLE else View.INVISIBLE
        content.alpha = 1f - value
    }

    private fun trackVelocity(ev: MotionEvent, reset: Boolean = false) {
        if (reset) recycleVelocity()
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        // Track in screen coordinates so the drawer moving under the finger doesn't skew velocity
        val event = MotionEvent.obtain(ev)
        event.offsetLocation(ev.rawX - ev.x, ev.rawY - ev.y)
        tracker.addMovement(event)
        event.recycle()
    }

    private fun recycleVelocity() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
