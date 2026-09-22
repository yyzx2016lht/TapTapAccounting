package com.taostudio.tapaccounting.logic

/**
 * 保存流程重入锁（P0-5）。
 *
 * 所有 [AccountingFormController] 保存入口（含汇率确认旁路、动画回调）共用同一把锁：
 * 进入保存管线先 [tryAcquire]，写路径 finally / 对话框取消 [release]，
 * 确认回调走已持锁的 `performSaveWithLock` 而不是重新抢锁。
 */
class SaveReentryLock {
    private var locked = false

    @Synchronized
    fun tryAcquire(): Boolean {
        if (locked) return false
        locked = true
        return true
    }

    @Synchronized
    fun release() {
        locked = false
    }

    @Synchronized
    fun isLocked(): Boolean = locked
}
