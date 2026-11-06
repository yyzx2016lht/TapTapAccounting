package com.taostudio.tapaccounting.capture

import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.data.local.AppDatabase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * 待记收件箱的进程内刷新信号。
 *
 * 悬浮窗是 WindowManager 浮层，盖在 [com.taostudio.tapaccounting.capture.ui.PendingListActivity]
 * 上方时不触发 Activity 的 onPause/onResume，所以“悬浮窗细改”入账成功后必须显式发信号，
 * 否则卡片会继续停在待记里，要等用户退出重进才消失。
 */
object CaptureInboxEvents {

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val suppressDepth = AtomicInteger(0)

    /** 待记状态/列表发生变化，收件箱应重读一次数据库。 */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun notifyChanged() {
        if (suppressDepth.get() == 0) {
            _changes.tryEmit(Unit)
        }
    }

    /**
     * 批量操作期间静默刷新信号：调用方会自己做局部 UI 更新，
     * 避免每写一条状态就触发一次整页重读。
     */
    suspend fun <T> suppressing(block: suspend () -> T): T {
        suppressDepth.incrementAndGet()
        return try {
            block()
        } finally {
            suppressDepth.decrementAndGet()
        }
    }

    /**
     * 账单已写入外部导入回执：把同一 captureId 的待记推进到 RECORDED，
     * 再通知收件箱重读（通知栏的待记计数也跟着走同一条路径刷新）。
     */
    suspend fun markRecorded(db: AppDatabase, captureId: String?) {
        if (captureId.isNullOrBlank()) return
        markRecorded(db, listOf(captureId))
    }

    suspend fun markRecorded(db: AppDatabase, captureIds: Collection<String>) {
        val ids = captureIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        runCatching {
            ids.forEach { id ->
                db.paymentCaptureDao().updateStatus(id, CaptureStatus.RECORDED, now)
            }
        }
        notifyChanged()
    }

    /**
     * 待记标记为不计：把同一 captureId 的待记推进到 IGNORED 并通知收件箱重读。
     */
    suspend fun markIgnored(db: AppDatabase, captureId: String?) {
        if (captureId.isNullOrBlank()) return
        markIgnored(db, listOf(captureId))
    }

    suspend fun markIgnored(db: AppDatabase, captureIds: Collection<String>) {
        val ids = captureIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        runCatching {
            ids.forEach { id ->
                db.paymentCaptureDao().updateStatus(id, CaptureStatus.IGNORED, now)
            }
        }
        notifyChanged()
    }
}
