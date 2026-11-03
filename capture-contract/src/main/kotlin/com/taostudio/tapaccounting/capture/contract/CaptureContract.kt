package com.taostudio.tapaccounting.capture.contract

/**
 * 插件（待记收件箱）与 App / hook 之间的共享契约。
 *
 * 只放常量：不依赖 Android、不依赖序列化库，保证 hook 进程可以直接引用。
 */
object CaptureContract {
    /** 契约版本；不兼容变更时 +1。 */
    const val VERSION: Int = 1

    /** 显式别名，方便契约文档/其他模块引用。 */
    const val CONTRACT_VERSION: Int = VERSION

    const val PLUGIN_PACKAGE: String = "com.taostudio.tapaccounting.capture"
    const val APP_PACKAGE: String = "com.taostudio.tapaccounting"

    // ---- hook -> 插件：CaptureIngestProvider（token 鉴权） ----
    /** 插件导出的 ingestion Provider authority。 */
    const val INGEST_AUTHORITY: String = "$PLUGIN_PACKAGE.ingest"
    const val INGEST_METHOD: String = "ingest"

    /** Provider 不可用时的显式广播兜底。 */
    const val ACTION_INGEST: String = "$PLUGIN_PACKAGE.action.INGEST"

    /** token 既可以放在 call() 的 arg，也可以放在 extras 里。 */
    const val EXTRA_TOKEN: String = "token"

    /** CapturePayload 的 JSON 字符串。 */
    const val EXTRA_PAYLOAD: String = "payload"

    /** ContentProvider.call() 返回 Bundle 里的 JSON ack。 */
    const val ACK_RESULT: String = "result"

    /** hook 调 Provider 的建议超时时间（不要阻塞宿主的 DB 线程）。 */
    const val INGEST_TIMEOUT_MS: Long = 2_000L

    // ---- 插件 -> App：CaptureBridgeProvider（signature 权限） ----
    const val BRIDGE_AUTHORITY: String = "$APP_PACKAGE.capturebridge"
    const val BRIDGE_PERMISSION: String = "$APP_PACKAGE.permission.CAPTURE_BRIDGE"

    const val METHOD_PING: String = "ping"
    const val METHOD_GET_ACCOUNTING_CONTEXT: String = "getAccountingContext"
    const val METHOD_GET_RECENT_BILLS: String = "getRecentBills"
    const val METHOD_OPEN_QUICK_RECORD: String = "openQuickRecord"
    const val METHOD_START_CLASSIFY: String = "startClassify"
    const val METHOD_GET_CLASSIFY_RESULT: String = "getClassifyResult"
    const val METHOD_IMPORT_BILLS: String = "importBills"
    const val METHOD_GET_IMPORT_STATUS: String = "getImportStatus"

    /** App -> 插件：查询待记数量（signature 权限）。 */
    const val METHOD_GET_PENDING_COUNT: String = "getPendingCount"

    const val EXTRA_REQUEST_ID: String = "requestId"
    const val EXTRA_CAPTURE_ID: String = "captureId"
    const val EXTRA_CAPTURE_IDS: String = "captureIds"
    const val EXTRA_PREFILL: String = "prefill"
    const val EXTRA_BILL_ID: String = "billId"

    // ---- App -> 插件：广播 ----
    const val ACTION_PENDING_COUNT_CHANGED: String = "$PLUGIN_PACKAGE.action.PENDING_COUNT_CHANGED"
    const val ACTION_SHOW_PENDING: String = "$PLUGIN_PACKAGE.action.SHOW_PENDING"
    const val ACTION_BILL_CREATED: String = "$APP_PACKAGE.action.BILL_CREATED"

    const val EXTRA_COUNT: String = "count"
    const val EXTRA_UPDATED_AT: String = "updatedAt"
    const val EXTRA_AMOUNT_CENTS: String = "amountCents"
    const val EXTRA_PAID_AT: String = "paidAt"
    const val EXTRA_ACCOUNT_ID: String = "accountId"
    const val EXTRA_ACCOUNT_NAME: String = "accountName"
}

/** 支付捕获来源。 */
object CaptureSource {
    const val WECHAT: String = "WECHAT"
    const val ALIPAY: String = "ALIPAY"

    val ALL: List<String> = listOf(WECHAT, ALIPAY)
}

/** 待记状态机（见 docs/pending-expense-capture.md 6.3）。 */
object CaptureStatus {
    const val PENDING: String = "PENDING"
    const val AI_DRAFT: String = "AI_DRAFT"
    const val IMPORTING: String = "IMPORTING"
    const val RECORDED: String = "RECORDED"
    const val IGNORED: String = "IGNORED"
    const val POSSIBLE_DUPLICATE: String = "POSSIBLE_DUPLICATE"
    const val DUPLICATE: String = "DUPLICATE"
    const val FAILED: String = "FAILED"

    /** 计入“N 笔待记”的状态。 */
    val PENDING_COUNT_STATUSES: Set<String> = setOf(
        PENDING,
        AI_DRAFT,
        IMPORTING,
        FAILED,
        POSSIBLE_DUPLICATE,
    )
}

/** `importBills` 单条结果状态。 */
object ImportItemStatus {
    const val CREATED: String = "created"
    const val DUPLICATE: String = "duplicate"
    const val ERROR: String = "error"
}
