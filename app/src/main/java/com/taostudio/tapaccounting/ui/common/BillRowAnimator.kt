package com.taostudio.tapaccounting.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R

/**
 * 账单行的「删除折叠 / 撤销展开」动画。
 *
 * 为什么不用 RecyclerView 自带的 ItemAnimator：项目里所有账单列表都显式
 * `itemAnimator = null`（首页 AppBar/CoordinatorLayout 下 DiffUtil 动画历史上闪过），
 * 刷新也统一走 `notifyDataSetChanged`，RecyclerView 根本不知道哪一行没了/哪一行回来了，
 * 所以删除和恢复都是硬切。这里退一步：只针对「被操作的那几行」做 View 级的
 * 高度 + 透明度动画，刷新链路一行不动——动画播不了（行不在屏幕内、视图还没布局）就
 * 静默跳过，绝不影响数据正确性。
 *
 * 用法：
 *  - 删除：`collapseThen(findRows(rv, ids)) { 真正删库 + 刷新 }`
 *  - 撤销：`刷新(); expandAfterRefresh(rv, ids)`
 */
object BillRowAnimator {

    private const val COLLAPSE_MS = 200L
    private const val EXPAND_MS = 240L
    private const val FADE_MS = 130L
    private const val STAGGER_MS = 20L
    /** 超过这个行数就不做高度动画（整表 requestLayout 太重），退化成整体淡出/淡入。 */
    private const val MAX_HEIGHT_ROWS = 3
    /** 撤销后列表是异步建行再 notify 的，等一小会儿再去找恢复出来的行。 */
    private const val REFRESH_SETTLE_MS = 90L

    /** 底部弹层收起大致要这么久：行折叠动画等它一下，否则两个动画叠在一起反而更乱。 */
    const val SHEET_DISMISS_DELAY_MS = 140L

    /** 从 [rv] 当前可见的行里挑出绑定了 [billIds] 的行，按屏幕从上到下排序。 */
    fun findRows(rv: RecyclerView?, billIds: Collection<Long>): List<View> {
        if (rv == null || billIds.isEmpty()) return emptyList()
        val out = ArrayList<View>(billIds.size)
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val id = child.getTag(R.id.tag_bill_row_id) as? Long ?: continue
            if (id in billIds) out.add(child)
        }
        if (out.size > 1) out.sortBy { it.top }
        return out
    }

    /**
     * 删除前播放折叠动画；播完（或压根没找到行）回调 [onEnd] 执行真正的删除。
     * [startDelayMs] 用于等底部弹层收起，否则两个动画叠在一起反而更乱。
     */
    fun collapseThen(rows: List<View>, startDelayMs: Long = 0L, onEnd: () -> Unit) {
        val targets = rows.filter { it.isAttachedToWindow && it.height > 0 }
        if (targets.isEmpty()) {
            onEnd()
            return
        }
        if (targets.size > MAX_HEIGHT_ROWS) {
            fade(targets, toAlpha = 0f, startDelayMs = startDelayMs, onEnd = onEnd)
            return
        }
        var pending = targets.size
        var fired = false
        targets.forEachIndexed { index, view ->
            animateRowHeight(
                view = view,
                fromHeight = view.height,
                toHeight = 0,
                fromAlpha = 1f,
                toAlpha = 0f,
                durationMs = COLLAPSE_MS,
                startDelayMs = startDelayMs + index * STAGGER_MS
            ) {
                if (!fired) {
                    pending--
                    if (pending == 0) {
                        fired = true
                        onEnd()
                    }
                }
            }
        }
    }

    /** 撤销恢复：列表刷新完成后把恢复出来的那几行展开。 */
    fun expandAfterRefresh(rv: RecyclerView?, billIds: Collection<Long>) {
        if (rv == null || billIds.isEmpty()) return
        rv.postDelayed({ expandNow(findRows(rv, billIds)) }, REFRESH_SETTLE_MS)
    }

    fun expandNow(rows: List<View>) {
        val targets = rows.filter { it.isAttachedToWindow && it.height > 0 }
        if (targets.isEmpty()) return
        if (targets.size > MAX_HEIGHT_ROWS) {
            fade(targets, toAlpha = 1f, fadeFrom = 0f, startDelayMs = 0L, onEnd = null)
            return
        }
        targets.forEachIndexed { index, view ->
            animateRowHeight(
                view = view,
                fromHeight = 0,
                toHeight = view.height,
                fromAlpha = 0f,
                toAlpha = 1f,
                durationMs = EXPAND_MS,
                startDelayMs = index * STAGGER_MS,
                onEnd = null
            )
        }
    }

    private fun animateRowHeight(
        view: View,
        fromHeight: Int,
        toHeight: Int,
        fromAlpha: Float,
        toAlpha: Float,
        durationMs: Long,
        startDelayMs: Long,
        onEnd: (() -> Unit)?
    ) {
        val lp = view.layoutParams
        if (lp == null) {
            onEnd?.invoke()
            return
        }
        // 同一行重复触发（连点删除）时先收掉上一个动画，避免高度被两个动画同时写。
        (view.getTag(R.id.tag_bill_row_anim) as? Animator)?.cancel()
        val originalHeight = lp.height
        if (view.height != fromHeight) {
            lp.height = fromHeight
            view.alpha = fromAlpha
            view.requestLayout()
        }

        val animator = ValueAnimator.ofInt(fromHeight, toHeight).apply {
            duration = durationMs
            startDelay = startDelayMs
            interpolator = if (toHeight == 0) AccelerateInterpolator() else DecelerateInterpolator()
            addUpdateListener { anim ->
                lp.height = anim.animatedValue as Int
                view.alpha = fromAlpha + (toAlpha - fromAlpha) * anim.animatedFraction
                view.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var settled = false
                override fun onAnimationEnd(animation: Animator) {
                    if (settled) return
                    settled = true
                    view.setTag(R.id.tag_bill_row_anim, null)
                    restore(view, originalHeight)
                    onEnd?.invoke()
                }

                override fun onAnimationCancel(animation: Animator) {
                    onAnimationEnd(animation)
                }
            })
        }
        view.setTag(R.id.tag_bill_row_anim, animator)
        animator.start()
    }

    private fun restore(view: View, originalHeight: Int) {
        val lp = view.layoutParams ?: return
        if (lp.height != originalHeight) {
            lp.height = originalHeight
        }
        if (view.alpha != 1f) view.alpha = 1f
        view.requestLayout()
    }

    /** 批量场景：不塌高度（整表 requestLayout 太重），只做整体淡出/淡入。 */
    private fun fade(
        views: List<View>,
        toAlpha: Float,
        startDelayMs: Long,
        onEnd: (() -> Unit)?,
        fadeFrom: Float = 1f
    ) {
        var pending = views.size
        var fired = false
        views.forEach { view ->
            if (fadeFrom != 1f) view.alpha = fadeFrom
            view.animate()
                .alpha(toAlpha)
                .setDuration(FADE_MS)
                .setStartDelay(startDelayMs)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.alpha = 1f
                        if (!fired) {
                            pending--
                            if (pending == 0) {
                                fired = true
                                onEnd?.invoke()
                            }
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        onAnimationEnd(animation)
                    }
                })
                .start()
        }
    }
}
