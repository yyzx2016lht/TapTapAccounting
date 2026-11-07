package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 插件导入/“自己记”的回执：`captureId -> billId`。
 *
 * 只由 [com.taostudio.tapaccounting.logic.BillMutationService] 在写账单的同一个事务里写入，
 * 用于幂等（同一个 captureId 不重复建账单）和插件侧对账（getImportStatus）。
 *
 * [requestId] 只做批次追踪/日志，不是幂等键。
 */
@Entity(
    tableName = "external_imports",
    indices = [Index(value = ["billId"])]
)
data class ExternalImport(
    @PrimaryKey val captureId: String,
    val billId: Long,
    val requestId: String? = null,
    /** 拆分入账时 N 条账单共用的支付组；普通单笔回执为 null。 */
    val paymentGroupId: String? = null,
    val createdAt: Long
)
