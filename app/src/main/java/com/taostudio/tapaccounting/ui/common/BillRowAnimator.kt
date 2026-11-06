package com.taostudio.tapaccounting.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R

/**
 * 账单行的「删除 + 撤销恢复」动画。
 *
 * 为什么不用 RecyclerView 自带的 ItemAnimator：项目里所有账单列表都显式
 * `itemAnimator = null`（首页 AppBar/CoordinatorLayout 下 DiffUtil 动画历史上闪退），
 * 刷新也统一走 `notifyDataSetChanged`，RecyclerView 根本不知道哪一行没了、哪一行回来了。
 *
 * 删除采用「刷新后 FLIP」：
 *  1. 点击删除时先记下每行刷新前的位置（按账单 id），然后立刻删库；
 *  2. 等列表真的刷新完（被删的行从可见行里消失的那一次布局）；
 *  3. 此时新布局已经是删除后的位置：把每个仍然存在的行按「旧位置 - 新位置」先摆回原位，
 *     再整体滑到 0。视觉上就是下方内容平滑上滑、把空位封上。
 *
 * 为什么不在删库前先播动画（上一版的问题）：
 *  - 动画期间数据库还没删，列表随时可能被重建打断，出现「行先消失又回来」的闪；
 *  - 删库后的整表重建会和动画抢主线程，明显掉帧。
 * FLIP 的动画在所有重建都结束之后才跑，主线程是空的，而且位移量是每一行实测出来的
 * （日期分组头被一起删掉也不会跳）。
 *
 * 用法：
 *  - 删除：`deleteThen(rv, ids) { 删库 + 刷新 }`
 *  - 撤销：`刷新(); expandAfterRefresh(rv, ids)`
 */
object BillRowAnimator {

    private const val FLIP_MS = 200L
    private const val EXPAND_MS = 240L
    private const val FADE_MS = 130L
    private const val STAGGER_MS = 20L
    /** 超过这个行数就不做高度动画（整表 requestLayout 太重），退化成整体淡入。 */
    private const val MAX_HEIGHT_ROWS = 3
    /** 撤销后列表是异步建行的，等一小会儿再去找恢复出来的行。 */
    private const val REFRESH_SETTLE_MS = 90L
    /** 刷新迟迟不来时放弃动画，避免监听泄漏（删除失败 / 页面退出）。 */
    private const val REFRESH_TIMEOUT_MS = 1500L

    /** 底部弹层收起大致要这么久：删库等它一下，避免列表在弹层底下先动。 */
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
     * 删除账单：记录旧位置 → 执行 [delete]（删库 + 刷新）→ 刷新落地后播「上滑封口」。
     *
     * [startDelayMs] 用于等底部弹层收起。
     */
    fun deleteThen(
        recyclerView: RecyclerView?,
        billIds: Collection<Long>,
        startDelayMs: Long = 0L,
        delete: () -> Unit
    ) {
        if (recyclerView == null || billIds.isEmpty()) {
            delete()
            return
        }
        val deletedIds = billIds.toSet()
        val oldTops = snapshotTops(recyclerView)
        if (oldTops.isEmpty()) {
            delete()
            return
        }

        awaitContentRefresh(recyclerView, deletedIds) {
            closeGap(recyclerView, oldTops)
        }
        if (startDelayMs > 0L) {
            recyclerView.postDelayed(delete, startDelayMs)
        } else {
            delete()
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

    // ---------------------------------------------------------------- 内部实现

    /** 刷新前的可见行位置：账单 id → top。没有 tag 的分组头不记录，后面按相邻账单推算。 */
    private fun snapshotTops(rv: RecyclerView): Map<Long, Int> {
        val tops = HashMap<Long, Int>(rv.childCount)
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val id = child.getTag(R.id.tag_bill_row_id) as? Long ?: continue
            tops[id] = child.top
        }
        return tops
    }

    /**
     * 等「被删的行真的从列表里消失」的那一次布局。
     * 用全局布局监听而不是时间延迟：同步刷新、异步 Flow 刷新都能接上。
     */
    private fun awaitContentRefresh(rv: RecyclerView, deletedIds: Set<Long>, onReady: () -> Unit) {
        var consumed = false
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (consumed) return
                // 还没刷新（被删的行仍在列表里）：继续等，别提前播动画。
                if (containsAny(rv, deletedIds)) return
                consumed = true
                rv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                onReady()
            }
        }
        rv.viewTreeObserver.addOnGlobalLayoutListener(listener)
        rv.postDelayed({
            if (!consumed) {
                consumed = true
                rv.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }, REFRESH_TIMEOUT_MS)
    }

    private fun containsAny(rv: RecyclerView, billIds: Set<Long>): Boolean {
        for (i in 0 until rv.childCount) {
            val id = rv.getChildAt(i)?.getTag(R.id.tag_bill_row_id) as? Long ?: continue
            if (id in billIds) return true
        }
        return false
    }

    /**
     * 刷新后的布局已经是删除后的最终位置。把每一行按「旧位置 - 新位置」摆回去，
     * 再统一滑到 0 —— 所有行同时上滑，位移量逐行实测，日期分组变化也不会跳。
     */
    private fun closeGap(rv: RecyclerView, oldTops: Map<Long, Int>) {
        val children = ArrayList<View>(rv.childCount)
        for (i in 0 until rv.childCount) {
            rv.getChildAt(i)?.let { children.add(it) }
        }
        if (children.isEmpty()) return

        val deltas = HashMap<Long, Int>(children.size)
        children.forEach { child ->
            val id = child.getTag(R.id.tag_bill_row_id) as? Long ?: return@forEach
            val oldTop = oldTops[id] ?: return@forEach
            val delta = oldTop - child.top
            if (delta != 0) deltas[id] = delta
        }
        if (deltas.isEmpty()) return

        // 没有 id 的分组头/空态：跟随离它最近的账单行的位移。
        val taggedDeltas = children.mapNotNull { child ->
            val id = child.getTag(R.id.tag_bill_row_id) as? Long ?: return@mapNotNull null
            deltas[id]?.let { child to it }
        }
        val firstMoverTop = taggedDeltas.minOfOrNull { (view, _) -> view.top } ?: return

        children.forEach { child ->
            val id = child.getTag(R.id.tag_bill_row_id) as? Long
            val delta = if (id != null) {
                deltas[id] ?: return@forEach
            } else {
                // 只有删除位置下方的分组头才跟着动，上方的不能碰。
                if (child.top < firstMoverTop) return@forEach
                nearestDelta(taggedDeltas, child.top) ?: return@forEach
            }
            child.animate().cancel()
            child.translationY = delta.toFloat()
            child.animate()
                .translationY(0f)
                .setDuration(FLIP_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { child.translationY = 0f }
                .start()
        }
    }

    private fun nearestDelta(tagged: List<Pair<View, Int>>, top: Int): Int? =
        tagged.minByOrNull { (view, _) -> kotlin.math.abs(view.top - top) }?.second

    // ---------------------------------------------------------------- 撤销动效

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
        // 同一行重复触发时先收掉上一个动画，避免高度被两个动画同时写。
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

    /** 批量场景：不做高度动画（整表 requestLayout 太重），只做整体淡入。 */
    private fun fade(
        views: List<View>,
        toAlpha: Float,
        startDelayMs: Long,
        onEnd: (() -> Unit)?,
        fadeFrom: Float = 1f
    ) {
        var pending = views.size
        var finished = false
        views.forEach { view ->
            if (fadeFrom != 1f) view.alpha = fadeFrom
            view.animate()
                .alpha(toAlpha)
                .setDuration(FADE_MS)
                .setStartDelay(startDelayMs)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.alpha = 1f
                        if (!finished) {
                            pending--
                            if (pending == 0) {
                                finished = true
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
