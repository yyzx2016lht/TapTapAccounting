package com.taostudio.tapaccounting.ui.common

import android.view.View
import java.util.WeakHashMap

/**
 * 多选模式底部操作栏的进出场动画。
 *
 * 进入多选：操作栏从下边缘滑入并淡入；退出多选：滑出并淡出后隐藏。
 * 账单、资产详情、资产统计、日历、搜索、AI 规则等所有多选页面统一走这里，保证观感一致。
 *
 * 幂等性由本对象自己保证：多选期间每次勾选变化都会回调进来，但同一状态不会重播动画。
 *
 * 注意：`ViewPropertyAnimator.cancel()` 会触发上一个动画的 `withEndAction`。
 * 所以每次动画都带一个自增令牌，过期的结束回调一律跳过——否则快速连按/连续调用时，
 * 旧回调会把操作栏提前隐藏，表现为"没有退场动画"。
 */
object MultiSelectMotion {

    /** 每个操作栏当前动画的令牌，WeakHashMap 不持有视图。 */
    private val animToken = WeakHashMap<View, Int>()

    /** 进入多选：滑入 + 淡入。已就位时不重播；可打断退场动画平滑反向。 */
    fun animateIn(bar: View) {
        val token = nextToken(bar)
        val wasVisible = bar.visibility == View.VISIBLE
        bar.animate().cancel() // 旧动画的结束回调因令牌过期而跳过

        if (!wasVisible) {
            // 首次进入（或已隐藏后重新进入）才重置起始状态；打断退场时从当前位置继续
            bar.alpha = 0f
            bar.translationY = slideOffset(bar)
        }
        bar.visibility = View.VISIBLE
        // 已就位：多选期间勾选变化重复调用，直接跳过，避免重播闪烁
        if (wasVisible && bar.alpha == 1f && bar.translationY == 0f) return

        bar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(UiMotion.NORMAL)
            .setInterpolator(UiMotion.STANDARD_EASING)
            .start()
    }

    /** 退出多选：滑出 + 淡出，动画结束后隐藏。 */
    fun animateOut(bar: View) {
        if (bar.visibility != View.VISIBLE) {
            // 已经不可见：对齐到隐藏终态，清掉可能残留的位移/透明度
            bar.translationY = 0f
            bar.alpha = 1f
            return
        }
        val token = nextToken(bar)
        bar.animate().cancel() // 旧动画的结束回调因令牌过期而跳过
        bar.animate()
            .translationY(slideOffset(bar))
            .alpha(0f)
            .setDuration(UiMotion.NORMAL)
            .setInterpolator(UiMotion.STANDARD_EASING)
            .withEndAction {
                // 只有仍是本次退场动画（没有新动画接管）才允许隐藏
                if (animToken[bar] == token) {
                    bar.visibility = View.GONE
                    bar.translationY = 0f
                    bar.alpha = 1f
                }
            }
            .start()
    }

    private fun nextToken(bar: View): Int {
        val token = (animToken[bar] ?: 0) + 1
        animToken[bar] = token
        return token
    }

    private fun slideOffset(bar: View): Float =
        if (bar.height > 0) bar.height.toFloat()
        else 96f * bar.resources.displayMetrics.density
}
