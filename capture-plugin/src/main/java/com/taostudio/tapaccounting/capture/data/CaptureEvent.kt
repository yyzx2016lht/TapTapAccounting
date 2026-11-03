package com.taostudio.tapaccounting.capture.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AutoAccounting `AppDataModel` 的等价物：每条 hook 原始事件都留档。
 *
 * 和 `PaymentCapture` 的区别：
 * - `PaymentCapture` 是“待记账单”，只保存规则命中/显式类型的候选；
 * - `CaptureEvent` 是历史数据，匹配失败、丰巢提醒等也保留，供“历史”页查看和重跑规则。
 */
@Entity(
    tableName = "capture_events",
    indices = [
        Index(value = ["rawHash"], unique = true),
        Index(value = ["receivedAt"]),
    ],
)
data class CaptureEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val rawType: String,
    val rawJson: String,
    val receivedAt: Long,
    val matched: Boolean,
    val ruleName: String? = null,
    val autoType: String? = null,
    val localType: Int? = null,
    val localSubType: Int? = null,
    val amountCents: Long? = null,
    val merchant: String? = null,
    val categoryName: String? = null,
    val channel: String? = null,
    /** MD5(rawJson)，用于全局原始事件去重。 */
    val rawHash: String,
)
