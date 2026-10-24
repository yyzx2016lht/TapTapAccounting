package com.taostudio.tapaccounting.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import java.util.WeakHashMap

/**
 * 多选进出时「勾选框 + 行内容」的联动动画，账单首页与资产详情页共用同一套。
 *
 * 勾选框在行内横向 LinearLayout 里占位，只补淡入淡出会让图标/文字/金额在同一帧
 * 整体跳移（内容先跳、勾选框后现），观感割裂；translationX 补偿则会在退出时把
 * 行内容推出左边界（图标被裁掉，真机实测可见）。这里用**宽度形变**：
 * - 进入：占位从 0 展开到自然宽度，同时勾选框淡入放大——内容随布局被渐进推开。
 * - 退出：勾选框淡出缩小、占位收回 0；动画自然结束后恢复原始布局参数并 GONE。
 * - 可打断：进出互切时从当前宽度/透明度继续反向；终态提交只在动画自然完成、
 *   且模式没有再次反转时进行。
 *
 * [spacer] 非空时（日期分组头）动画作用在占位 View 的宽度上，勾选框本身是覆盖层，
 * 完全不参与行高测量——否则 MaterialCheckBox 的按钮 drawable（约 28dp）会把
 * 分组头撑高，退出多选时整表变矮、列表上跳（真机逐帧实测的跳动来源）。
 */
object RowModeMotion {

    /** 宽度形变动画器（按勾选框跟踪，WeakHashMap 不持有引用）。 */
    private val animators = WeakHashMap<CheckBox, ValueAnimator>()

    /** 勾选框 XML 里的原始 marginEnd。形变期间会改写 lp，基准值必须提前单独缓存。 */
    private val baseMarginEnds = WeakHashMap<CheckBox, Int>()

    /**
     * 勾选框 XML 里的原始宽度（WRAP_CONTENT 以 [ViewGroup.LayoutParams.WRAP_CONTENT] 原值存）。
     * 表头勾选框是固定 32dp、账单行是 wrap_content，终态必须各自还原。
     */
    private val baseWidths = WeakHashMap<CheckBox, Int>()

    /**
     * @param isModeActive 当前是否处于多选模式（用于动画终态提交前的二次校验，
     *   避免快速连进连出时旧动画的结束回调把状态写错）。
     */
    fun apply(
        checkBox: CheckBox,
        show: Boolean,
        animate: Boolean,
        isModeActive: () -> Boolean,
        shownAlpha: Float = 1f,
        spacer: View? = null,
        afterShown: (() -> Unit)? = null
    ) {
        val cbLp = checkBox.layoutParams as? ViewGroup.MarginLayoutParams
        // 首次接触该勾选框时缓存 XML 原始 width/marginEnd（形变会改写它们，基准必须提前存）
        val baseMarginEnd = cbLp?.let { baseMarginEnds.getOrPut(checkBox) { it.marginEnd } } ?: 0
        val baseWidth = cbLp?.let {
            baseWidths.getOrPut(checkBox) {
                if (it.width > 0) it.width else ViewGroup.LayoutParams.WRAP_CONTENT
            }
        } ?: ViewGroup.LayoutParams.WRAP_CONTENT

        // 形变目标：有占位 View 时动占位宽度，否则直接动勾选框自身宽度
        val morphView: View = spacer ?: checkBox
        val morphLp = morphView.layoutParams as? ViewGroup.MarginLayoutParams

        // 勾选框自然宽度：固定宽直接取基准；wrap_content 用 UNSPECIFIED measure（不受当前 lp.width 影响）
        val naturalWidth = if (baseWidth != ViewGroup.LayoutParams.WRAP_CONTENT) {
            baseWidth
        } else {
            checkBox.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            checkBox.measuredWidth
        }
        // 展开态宽度：占位 = 勾选框宽 + marginEnd；直接动勾选框时即自然宽度
        val expandedWidth = if (spacer != null) naturalWidth + baseMarginEnd else naturalWidth

        animators.remove(checkBox)?.cancel()
        checkBox.animate().cancel()

        /** 布局参数与视觉属性统一落终态。 */
        fun snap(showState: Boolean) {
            if (morphLp != null) {
                val targetWidth = if (spacer != null) {
                    if (showState) expandedWidth else 0
                } else {
                    baseWidth
                }
                if (morphLp.width != targetWidth) {
                    morphLp.width = targetWidth
                    morphView.layoutParams = morphLp
                }
            }
            // 勾选框自身布局参数始终恢复原始
            if (cbLp != null && (cbLp.width != baseWidth || cbLp.marginEnd != baseMarginEnd)) {
                cbLp.width = baseWidth
                cbLp.marginEnd = baseMarginEnd
                checkBox.layoutParams = cbLp
            }
            checkBox.visibility = if (showState) View.VISIBLE else View.GONE
            checkBox.alpha = if (showState) shownAlpha else 1f
            checkBox.scaleX = 1f
            checkBox.scaleY = 1f
        }

        if (!animate || morphLp == null || naturalWidth <= 0) {
            if (show) afterShown?.invoke()
            snap(show)
            return
        }

        if (show) {
            // 已完全就位（多选期间的重复回调）不重播动画
            val alreadyShown = checkBox.visibility == View.VISIBLE &&
                checkBox.alpha == shownAlpha &&
                morphLp.width == (if (spacer != null) expandedWidth else baseWidth)
            if (alreadyShown) {
                afterShown?.invoke()
                return
            }
            if (checkBox.visibility != View.VISIBLE) {
                checkBox.visibility = View.VISIBLE
                checkBox.alpha = 0f
                checkBox.scaleX = 0.72f
                checkBox.scaleY = 0.72f
                if (spacer != null) {
                    morphLp.width = 0
                    morphView.layoutParams = morphLp
                } else {
                    morphLp.width = 0
                    morphLp.marginEnd = 0
                    morphView.layoutParams = morphLp
                }
            }
            afterShown?.invoke()
            val fromWidth = when {
                spacer != null -> morphLp.width
                morphLp.width == baseWidth -> naturalWidth
                else -> morphLp.width
            }
            val fromMargin = if (spacer != null) 0 else morphLp.marginEnd
            checkBox.animate()
                .alpha(shownAlpha)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(UiMotion.NORMAL)
                .setInterpolator(UiMotion.STANDARD_EASING)
                .start()
            startWidthAnimator(
                checkBox, morphView, morphLp,
                fromWidth = fromWidth, fromMargin = fromMargin,
                toWidth = expandedWidth,
                toMargin = if (spacer != null) 0 else baseMarginEnd,
                expectModeOnEnd = true,
                isModeActive = isModeActive
            ) { snap(true) }
        } else {
            if (checkBox.visibility != View.VISIBLE) {
                snap(false)
                return
            }
            val fromWidth = when {
                spacer != null -> morphLp.width
                morphLp.width == baseWidth -> naturalWidth
                else -> morphLp.width
            }
            val fromMargin = if (spacer != null) 0 else morphLp.marginEnd
            checkBox.animate()
                .alpha(0f)
                .scaleX(0.72f)
                .scaleY(0.72f)
                .setDuration(UiMotion.NORMAL)
                .setInterpolator(UiMotion.EXIT_EASING)
                .start()
            startWidthAnimator(
                checkBox, morphView, morphLp,
                fromWidth = fromWidth, fromMargin = fromMargin,
                toWidth = 0, toMargin = 0,
                expectModeOnEnd = false,
                isModeActive = isModeActive
            ) { snap(false) }
        }
    }

    /**
     * 宽度/marginEnd 的形变动画。终态提交（[commit]）只在动画自然完成、
     * 且 [expectModeOnEnd] 与当前模式一致时执行；被打断则什么都不做，
     * 由下一次 [apply] 从当前 lp 状态继续反向。
     */
    private fun startWidthAnimator(
        checkBox: CheckBox,
        morphView: View,
        lp: ViewGroup.MarginLayoutParams,
        fromWidth: Int,
        fromMargin: Int,
        toWidth: Int,
        toMargin: Int,
        expectModeOnEnd: Boolean,
        isModeActive: () -> Boolean,
        commit: () -> Unit
    ) {
        var canceled = false
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = UiMotion.NORMAL
            interpolator = UiMotion.STANDARD_EASING
            addUpdateListener {
                val f = it.animatedFraction
                lp.width = (fromWidth + (toWidth - fromWidth) * f).toInt()
                lp.marginEnd = (fromMargin + (toMargin - fromMargin) * f).toInt()
                morphView.layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    animators.remove(checkBox)
                    if (!canceled && isModeActive() == expectModeOnEnd) commit()
                }
            })
        }
        animators[checkBox] = animator
        animator.start()
    }

    /**
     * 行被回收时清干净所有动画残留（位移/透明度/缩放/勾选框布局参数与可见性），
     * 避免复用给其他位置时把半途状态带过去。
     */
    fun reset(row: View, checkBox: CheckBox, spacer: View? = null) {
        row.animate().cancel()
        row.translationY = 0f
        row.alpha = 1f
        row.scaleX = 1f
        row.scaleY = 1f
        animators.remove(checkBox)?.cancel()
        checkBox.animate().cancel()
        if (spacer != null) {
            val spLp = spacer.layoutParams as? ViewGroup.MarginLayoutParams
            if (spLp != null && spLp.width != 0) {
                spLp.width = 0
                spacer.layoutParams = spLp
            }
        }
        val lp = checkBox.layoutParams as? ViewGroup.MarginLayoutParams
        val baseWidth = baseWidths[checkBox]
        val baseMargin = baseMarginEnds[checkBox]
        if (lp != null && (baseWidth != null || baseMargin != null)) {
            baseWidth?.let { lp.width = it }
            baseMargin?.let { lp.marginEnd = it }
            checkBox.layoutParams = lp
        }
        checkBox.visibility = View.GONE
        checkBox.alpha = 1f
        checkBox.scaleX = 1f
        checkBox.scaleY = 1f
    }
}
