package com.taostudio.tapaccounting.capture.contract

/**
 * App 通过 `getAccountingContext()` 返回的只读上下文。
 *
 * 插件只用它做展示/选择/映射，不写回 App 数据库。
 */
data class AccountingContext(
    val contractVersion: Int = CaptureContract.VERSION,
    val defaultBookName: String? = null,
    val assetFeatureEnabled: Boolean = true,
    val books: List<BookInfo> = emptyList(),
    val assets: List<AssetInfo> = emptyList(),
    val categories: List<CategoryInfo> = emptyList(),
)

data class BookInfo(
    val id: Long,
    val name: String,
)

data class AssetInfo(
    val id: Long,
    val name: String,
    val type: String = "",
    val assetCategory: String = "",
    val currency: String = "CNY",
)

data class CategoryInfo(
    val id: Long,
    val name: String,
    /** 0 支出 / 1 收入。 */
    val type: Int,
    val parentId: Long? = null,
)
