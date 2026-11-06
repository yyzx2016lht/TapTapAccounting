package com.taostudio.tapaccounting.ui.common

import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R

/**
 * 账单列表的「删除封口 / 撤销让位」动画。
 *
 * 为什么不用 RecyclerView 自带的 ItemAnimator：项目里所有账单列表都显式
 * `itemAnimator = null`（首页 AppBar/CoordinatorLayout 下 DiffUtil 动画历史上闪退），
 * 刷新也统一走 `notifyDataSetChanged`，RecyclerView 不知道哪一行没了、哪一行回来了。
 *
 * 做法是「刷新后 FLIP」：
 *  1. 操作前记下每行位置（按账单 id），再删库/恢复；
 *  2. 等列表真的刷新完（被删的行消失 / 恢复的行出现的那一次布局）；
 *  3. 新布局已经是最终位置：把每行按「旧位置 - 新位置」先摆回原位，再统一滑到 0。
 *
 * 这样动画永远在所有重建结束之后才跑，主线程是空的；位移量逐行实测，
 * 日期分组头被一起删掉也不会跳。不要在删库前先播动画——列表随时会被重建打断，
 * 会出现「行先消失又回来」的闪，并且和整表重建抢主线程。
 *
 * 用法：
 *  - 删除：`deleteThen(rv, ids) { 删库 + 刷新 }`
 *  - 撤销：`restoreThen(rv, ids) { 恢复 + 刷新 }`
 */
object BillRowAnimator {

    private const val FLIP_MS = 200L
    private const val APPEAR_MS = 180L
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
     * 删除账单：记录旧位置 → 执行 [delete]（删库 + 刷新）→ 刷新落地后播「下方内容上滑封口」。
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
        awaitContentSettled(recyclerView, deletedIds, expectPresent = false) {
            flipToOldPositions(recyclerView, oldTops, appearedIds = emptySet())
        }
        if (startDelayMs > 0L) {
            recyclerView.postDelayed(delete, startDelayMs)
        } else {
            delete()
        }
    }

    /**
     * 撤销恢复：记录旧位置 → 执行 [restore]（恢复 + 刷新）→ 刷新落地后让下方内容
     * 滑下去给恢复出来的行让位，恢复出来的行淡入。
     */
    fun restoreThen(
        recyclerView: RecyclerView?,
        billIds: Collection<Long>,
        restore: () -> Unit
    ) {
        if (recyclerView == null || billIds.isEmpty()) {
            restore()
            return
        }
        val restoredIds = billIds.toSet()
        val oldTops = snapshotTops(recyclerView)
        awaitContentSettled(recyclerView, restoredIds, expectPresent = true) {
            flipToOldPositions(recyclerView, oldTops, appearedIds = restoredIds)
        }
        restore()
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
     * 等列表进入终态：删除时等被删的行消失，撤销时等恢复的行出现。
     * 用全局布局监听而不是时间延迟：同步刷新、异步 Flow 刷新都能接上。
     */
    private fun awaitContentSettled(
        rv: RecyclerView,
        billIds: Set<Long>,
        expectPresent: Boolean,
        onReady: () -> Unit
    ) {
        if (containsAny(rv, billIds) == expectPresent) {
            // 已经是终态（例如 Flow 比我们先刷新了）：不用等布局。
            onReady()
            return
        }
        var consumed = false
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (consumed) return
                if (containsAny(rv, billIds) != expectPresent) return
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
     * 刷新后的布局已经是最终位置。把每一行按「旧位置 - 新位置」摆回去，再统一滑到 0：
     * 删除时是上方内容上滑封口，撤销时是下方内容下滑让位；位移量逐行实测。
     */
    private fun flipToOldPositions(
        rv: RecyclerView,
        oldTops: Map<Long, Int>,
        appearedIds: Set<Long>
    ) {
        val children = ArrayList<View>(rv.childCount)
        for (i in 0 until rv.childCount) {
            rv.getChildAt(i)?.let { children.add(it) }
        }
        if (children.isEmpty()) return

        val deltas = HashMap<Long, Int>(children.size)
        val appeared = ArrayList<View>()
        children.forEach { child ->
            val id = child.getTag(R.id.tag_bill_row_id) as? Long ?: return@forEach
            val oldTop = oldTops[id]
            if (oldTop == null) {
                if (id in appearedIds) appeared.add(child)
                return@forEach
            }
            val delta = oldTop - child.top
            if (delta != 0) deltas[id] = delta
        }

        appeared.forEach { child ->
            child.animate().cancel()
            child.alpha = 0f
            child.animate()
                .alpha(1f)
                .setDuration(APPEAR_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { child.alpha = 1f }
                .start()
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
                // 只有移动区域里的分组头才跟着动，上方的不能碰。
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
}
