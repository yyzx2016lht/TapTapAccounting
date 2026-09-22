package com.taostudio.tapaccounting.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P0-4：降级备份失败/不完整时必须阻断 destructive 清库。
 */
class DatabaseDowngradeHelperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun isBackupComplete_trueWhenSameLength() {
        val source = tmp.newFile("src.db").apply { writeBytes(ByteArray(128) { 1 }) }
        val backup = tmp.newFile("bak.db").apply { writeBytes(ByteArray(128) { 2 }) }
        assertTrue(DatabaseDowngradeHelper.isBackupComplete(source, backup))
    }

    @Test
    fun isBackupComplete_falseWhenLengthMismatch() {
        val source = tmp.newFile("src.db").apply { writeBytes(ByteArray(128) { 1 }) }
        val backup = tmp.newFile("bak.db").apply { writeBytes(ByteArray(64) { 2 }) }
        assertFalse(DatabaseDowngradeHelper.isBackupComplete(source, backup))
    }

    @Test
    fun isBackupComplete_falseWhenBackupMissingOrEmptySource() {
        val source = tmp.newFile("src.db").apply { writeBytes(ByteArray(0)) }
        val backup = tmp.newFile("bak.db").apply { writeBytes(ByteArray(8)) }
        assertFalse(DatabaseDowngradeHelper.isBackupComplete(source, backup))
        assertFalse(DatabaseDowngradeHelper.isBackupComplete(source, tmp.root.resolve("nope.db")))
    }
}
