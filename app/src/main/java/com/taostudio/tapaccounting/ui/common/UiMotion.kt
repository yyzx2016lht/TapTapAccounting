package com.taostudio.tapaccounting.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Centralised animation duration & interpolator constants for the app.
 *
 * Usage example:
 * ```
 * view.animate()
 *     .alpha(0f)
 *     .setDuration(UiMotion.FAST)
 *     .setInterpolator(UiMotion.EXIT_EASING)
 *     .withEndAction { view.visibility = View.GONE }
 *     .start()
 * ```
 */
object UiMotion {

    /** Micro-interaction – ripple, icon morph, button press feedback. */
    const val FAST = 150L

    /** Standard transition – page element enter/exit, snackbar. */
    const val NORMAL = 220L

    /** Emphasised transition – page change, shared element. */
    const val SLOW = 300L

    // ── Easing curves ──────────────────────────────────────────────

    /** Standard easing (decelerate-in). Use for most enter animations. */
    val STANDARD_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** Exit easing (accelerate-out). Use for dismiss / exit animations. */
    val EXIT_EASING = PathInterpolator(0.4f, 0f, 1f, 1f)

    /** Emphasised easing (same curve as standard, kept separate for clarity). */
    val EMPHASISED_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** Overshoot easing for drawer page transitions – slight bounce at end. */
    val OVERSHOOT_EASING = PathInterpolator(0.34f, 1.2f, 0.64f, 1f)

    // ── View extension helpers ──────────────────────────────────────

    /**
     * Cancel any running animation on [this] view safely (no crash if none).
     */
    fun View.safeCancelAnimation() {
        animate().cancel()
    }

    /**
     * Apply a quick press-feedback scale effect and restore.
     * Useful for `onTouch` / `OnClickListener` callbacks.
     */
    fun View.pressFeedback() {
        animate().cancel()
        animate()
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(FAST)
            .setInterpolator(EXIT_EASING)
            .withEndAction {
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(FAST)
                    .setInterpolator(STANDARD_EASING)
                    .start()
            }
            .start()
    }

    /**
     * Fade in [this] view over [duration] ms using [STANDARD_EASING].
     */
    fun View.fadeIn(duration: Long = NORMAL, onEnd: (() -> Unit)? = null) {
        alpha = 0f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(STANDARD_EASING)
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    /**
     * Fade out [this] view over [duration] ms, then set [View.GONE].
     */
    fun View.fadeOut(duration: Long = NORMAL, onEnd: (() -> Unit)? = null) {
        animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(EXIT_EASING)
            .withEndAction {
                visibility = View.GONE
                onEnd?.invoke()
            }
            .start()
    }

    // ── FAB helpers ──────────────────────────────────────────────────

    /**
     * Animate FAB to visible state with scale + alpha transition.
     * Uses [FloatingActionButton.show] for proper internal state management,
     * but wraps it with a crossfade for smoother appearance.
     */
    fun FloatingActionButton.showAnimated() {
        if (isOrWillBeShown) return
        safeCancelAnimation()
        alpha = 0f
        scaleX = 0.5f
        scaleY = 0.5f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(NORMAL)
            .setInterpolator(STANDARD_EASING)
            .withLayer()
            .withEndAction { show() }
            .start()
    }

    /**
     * Animate FAB to hidden state with scale + alpha transition.
     * Uses [FloatingActionButton.hide] for proper internal state management.
     */
    fun FloatingActionButton.hideAnimated() {
        if (isOrWillBeHidden) return
        safeCancelAnimation()
        animate()
            .alpha(0f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(NORMAL)
            .setInterpolator(EXIT_EASING)
            .withLayer()
            .withEndAction {
                hide()
                // Reset visual state so next show starts clean
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
            }
            .start()
    }

    // ── FAB scroll behaviour ────────────────────────────────────────

    /** 累计向下位移超过该值才收起 FAB。 */
    const val FAB_HIDE_THRESHOLD_PX = 8

    /** 累计向上位移超过该值才唤回 FAB。 */
    const val FAB_SHOW_THRESHOLD_PX = 8

    /**
     * 滚动感知型 FAB 的显隐策略，三个页面共用一份规则。
     *
     * 之前账单首页 / 资产页 / 资产详情各写各的（+8/-8、+8/-2、+20/-8），只有资产详情
     * 做了位移累加，阈值也因此漂移。这里收敛成一条：
     *  - **累加 dy**：慢慢拖动时每次只走几像素，单次比较永远达不到阈值，FAB 会一直赖着
     *    不走；累加之后同样的位移能累积到阈值，"一上一下就能控制"的手感不受影响。
     *  - **方向一反转就清零**（关键）：只按"翻转后清零"是不够的——一次长距离下滑会把
     *    累加值攒成几百像素的"债"，用户往回滑时得先补回这段距离 FAB 才肯回来，表现为
     *    "往上滑半天没反应，非得滑到顶才行"。按方向清零后，不管刚才滑多远，往回滑 8px
     *    就唤回。
     *  - **[atTop]** 列表已在顶部时强制显示，不管当前处于什么状态。
     */
    class FabScrollBehavior(
        private val hide: () -> Unit,
        private val show: () -> Unit,
    ) {
        private var accumulator = 0
        private var hidden = false

        /** 喂入每帧的滚动位移。[atTop] 表示列表已无法继续向上滚。 */
        fun onScroll(dy: Int, atTop: Boolean) {
            if (atTop) {
                syncVisible()
                return
            }
            if (dy == 0) return
            if (accumulator != 0 && (accumulator > 0) != (dy > 0)) accumulator = 0
            accumulator += dy
            when {
                !hidden && accumulator > FAB_HIDE_THRESHOLD_PX -> {
                    hidden = true
                    accumulator = 0
                    hide()
                }
                hidden && accumulator < -FAB_SHOW_THRESHOLD_PX -> {
                    hidden = false
                    accumulator = 0
                    show()
                }
            }
        }

        /**
         * 非滚动原因导致的显示（抽屉关闭、退出多选、空态退出……）。
         * 显隐逻辑仍由 [show] 决定——它可能因为空态/抽屉而选择继续隐藏。
         */
        fun syncVisible() {
            markVisible()
            show()
        }

        /**
         * 外部直接操作了 FAB 时用它对齐内部状态，否则下一次滚动会用过期状态做判断
         * （典型后果：FAB 明明可见，却要等一次上滑才肯再响应）。
         */
        fun markVisible() {
            hidden = false
            accumulator = 0
        }

        /** 外部直接隐藏了 FAB 时用它对齐内部状态（切 Tab、弹层遮挡等）。 */
        fun markHidden() {
            hidden = true
            accumulator = 0
        }
    }

    // ── Form / overlay helpers ──────────────────────────────────────

    /**
     * Gentle press feedback for accounting form rows: scale to 0.98 + alpha to 0.92 on press,
     * restore on release/cancel. Does not interfere with click/long-click listeners.
     */
    fun View.applyFormRowPressFeedback() {
        var isPressed = false
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.985f)
                        .scaleY(0.985f)
                        .alpha(0.88f)
                        .setDuration(FAST)
                        .setInterpolator(EXIT_EASING)
                        .withLayer()
                        .start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPressed) {
                        isPressed = false
                        v.animate().cancel()
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(FAST)
                            .setInterpolator(STANDARD_EASING)
                            .withLayer()
                            .start()
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    false
                }
                else -> false
            }
        }
    }

    /**
     * Crossfade from [from] view to [to] view over [duration] ms.
     * Fades out [from], then fades in [to]. Calls [onMidpoint] between the two phases.
     */
    fun crossfadeViews(
        from: View,
        to: View,
        duration: Long = NORMAL,
        onMidpoint: (() -> Unit)? = null
    ) {
        from.animate().cancel()
        from.animate()
            .alpha(0f)
            .setDuration(duration / 2)
            .setInterpolator(EXIT_EASING)
            .withLayer()
            .withEndAction {
                from.visibility = View.GONE
                onMidpoint?.invoke()
                to.alpha = 0f
                to.visibility = View.VISIBLE
                to.animate()
                    .alpha(1f)
                    .setDuration(duration / 2)
                    .setInterpolator(STANDARD_EASING)
                    .withLayer()
                    .start()
            }
            .start()
    }

    // ── Home page helpers ─────────────────────────────────────────

    /**
     * Crossfade text on a [TextView]: fade out → set new text → fade in.
     * Uses [FAST] duration to feel instant but smooth.
     * Cancels any running animation on the same view before starting.
     */
    fun TextView.crossfadeText(newText: String) {
        if (text == newText) return
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(60L)
            .setInterpolator(EXIT_EASING)
            .withEndAction {
                text = newText
                animate()
                    .alpha(1f)
                    .setDuration(80L)
                    .setInterpolator(STANDARD_EASING)
                    .start()
            }
            .start()
    }

    /**
     * Gentle press feedback for list items: scale to 0.98 + alpha to 0.94 on press,
     * restore on release/cancel. Uses [OnTouchListener] so it does not interfere
     * with click/long-click listeners.
     *
     * Call this from [RecyclerView.Adapter.onViewAttachedToWindow] or ViewHolder init,
     * NOT from bind, to avoid re-setting on every rebind.
     *
     * [translationY] 必须一起收/放，不能只管 scale 和 alpha：按下时的
     * `animate().cancel()` 会连带杀掉同一个 view 上正在跑的动画——包括
     * [staggerFirstLoadAnimation] 的入场位移。一旦被打断，那个 `translationY = 24f`
     * 就没人复位了（RecyclerView 复用时也不会重置），账单行会永久停在 +24px，
     * 表现为「日期表头和下面那条账单之间裂开一条缝」。所以恢复分支必须显式写回 0f。
     */
    fun View.applyItemPressFeedback() {
        var isPressed = false
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .alpha(0.92f)
                        .translationY(0f)
                        .setDuration(FAST)
                        .setInterpolator(EXIT_EASING)
                        .withLayer()
                        .start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPressed) {
                        isPressed = false
                        v.animate().cancel()
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(FAST)
                            .setInterpolator(STANDARD_EASING)
                            .withLayer()
                            .start()
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    false
                }
                else -> false
            }
        }
    }

    /**
     * Stagger fade-in animation for RecyclerView children.
     * Animates only the first [maxItems] visible children with a stagger delay.
     * Safe for performance: only targets currently attached views.
     */
    fun staggerFirstLoadAnimation(
        recyclerView: RecyclerView,
        maxItems: Int = 6,
        itemDelayMs: Long = 40L,
        startDelayMs: Long = 80L
    ) {
        recyclerView.post {
            val lm = recyclerView.layoutManager ?: return@post
            val count = minOf(maxItems, lm.childCount)
            for (i in 0 until count) {
                val child = lm.getChildAt(i) ?: continue
                child.alpha = 0f
                child.translationY = 24f
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(NORMAL)
                    .setInterpolator(STANDARD_EASING)
                    .setStartDelay(startDelayMs + i * itemDelayMs)
                    .withLayer()
                    .start()
            }
        }
    }
}

