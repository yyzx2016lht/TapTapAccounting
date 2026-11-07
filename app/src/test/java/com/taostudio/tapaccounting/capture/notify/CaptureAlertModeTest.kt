package com.taostudio.tapaccounting.capture.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 验证待记通知组常量与 Child Notification ID 避免与 Summary ID 冲突。
 */
class CaptureAlertModeTest {

    @Test
    fun `group constants and child id mapping`() {
        assertEquals("capture_pending_group", CaptureNotifier.GROUP_KEY_PENDING)
        assertEquals(1001, CaptureNotifier.NOTIFICATION_ID_SUMMARY)

        val id1 = "capture_record_abc"
        val child1 = CaptureNotifier.childNotificationId(id1)
        assertNotEquals(CaptureNotifier.NOTIFICATION_ID_SUMMARY, child1)
        assertEquals(id1.hashCode(), child1)

        // 模拟 hashCode 恰好等于 1001 的边缘情况
        val mockObj = object {
            override fun hashCode(): Int = 1001
            override fun toString(): String = "collision_item"
        }
        val childCollision = CaptureNotifier.childNotificationId(mockObj.toString())
        assertNotEquals(CaptureNotifier.NOTIFICATION_ID_SUMMARY, childCollision)
    }
}
