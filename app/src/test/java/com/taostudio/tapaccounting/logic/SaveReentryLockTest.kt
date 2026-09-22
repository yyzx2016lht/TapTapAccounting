package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-5：汇率确认路径防重入——入口与确认回调共用同一把锁。
 */
class SaveReentryLockTest {

    @Test
    fun secondAcquireWhileHeld_isRejected() {
        val lock = SaveReentryLock()
        assertTrue(lock.tryAcquire())
        assertFalse(lock.tryAcquire())
        assertTrue(lock.isLocked())
    }

    @Test
    fun release_allowsNextSave() {
        val lock = SaveReentryLock()
        assertTrue(lock.tryAcquire())
        lock.release()
        assertFalse(lock.isLocked())
        assertTrue(lock.tryAcquire())
    }

    @Test
    fun confirmCallbackContinuation_keepsLockUntilWriteReleases() {
        val lock = SaveReentryLock()
        // 入口抢锁
        assertTrue(lock.tryAcquire())
        // 汇率确认回调不得再抢锁成功（应 performSaveWithLock 直接继续）
        assertFalse(lock.tryAcquire())
        // 写路径 finally 释放后才能再次保存
        lock.release()
        assertTrue(lock.tryAcquire())
    }

    @Test
    fun dialogCancel_releasesSoUserCanRetry() {
        val lock = SaveReentryLock()
        assertTrue(lock.tryAcquire())
        // 取消弹窗
        lock.release()
        assertFalse(lock.isLocked())
        assertTrue(lock.tryAcquire())
    }
}
