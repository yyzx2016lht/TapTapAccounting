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

    // ---- hook -> 插件：显式广播（token 鉴权） ----
    /**
     * 插件导出的 ingestion Provider authority。
     *
     * 注意：Android 11+ 的包可见性会过滤未在宿主 manifest 中声明的 authority；
     * 微信/支付宝 hook 进程应优先用 [ACTION_INGEST] 显式广播。
     */
    const val INGEST_AUTHORITY: String = "$PLUGIN_PACKAGE.ingest"
    const val INGEST_METHOD: String = "ingest"

    /** hook -> 插件的显式广播；不受包可见性过滤。 */
    const val ACTION_INGEST: String = "$PLUGIN_PACKAGE.action.INGEST"

    /** 接收 [ACTION_INGEST] 的 Receiver 类名。 */
    const val INGEST_RECEIVER_CLASS: String = "$PLUGIN_PACKAGE.ingest.CaptureIngestReceiver"

    // ---- hook/relay -> 主应用：P1 起的第二个入库目标（双写过渡） ----
    /**
     * 主应用导出的 ingestion Provider authority。
     *
     * 注意不能与插件 authority 相同：插件是 `$PLUGIN_PACKAGE.ingest`，
     * 主应用使用 `$APP_PACKAGE.ingest`。
     */
    const val APP_INGEST_AUTHORITY: String = "$APP_PACKAGE.ingest"
    const val APP_INGEST_ACTION: String = "$APP_PACKAGE.action.CAPTURE_INGEST"
    const val APP_INGEST_RECEIVER_CLASS: String = "$APP_PACKAGE.capture.ingest.CaptureIngestReceiver"
    const val APP_INGEST_PROVIDER_CLASS: String = "$APP_PACKAGE.capture.ingest.CaptureIngestProvider"
    const val APP_INGEST_METHOD: String = "ingest"

    /** 插件旧规则包导出：Provider.openFile 的 path；call() 只返回元信息。 */
    const val LEGACY_RULE_BUNDLE_PATH: String = "rule_bundle"
    const val METHOD_EXPORT_LEGACY_RULE_BUNDLE: String = "exportLegacyRuleBundle"

    // ---- 常驻 relay & 插件直连：hook 进程 -> localhost TCP -> 插件 ----
    /** relay 监听端口（仅 127.0.0.1）。 */
    const val RELAY_PORT: Int = 52046

    /** 插件进程内直连监听端口（仅 127.0.0.1），毫秒级零延迟投递，绕过 ColorOS 后台广播/FGS 拦截。 */
    const val PLUGIN_DIRECT_PORT: Int = 52047

    /** relay 拉起插件前台服务时使用的 action。 */
    const val ACTION_SERVICE_INGEST: String = "$PLUGIN_PACKAGE.action.SERVICE_INGEST"

    /** relay（com.android.phone UID 1001）拉起透明唤醒 Activity 时使用的 action。 */
    const val ACTION_WAKE_INGEST: String = "$PLUGIN_PACKAGE.action.WAKE_INGEST"

    /** 插件前台服务类名（跨进程启动时用 ComponentName）。 */
    const val INGEST_SERVICE_CLASS: String = "$PLUGIN_PACKAGE.service.CaptureKeepAliveService"

    /** 插件 1px 透明唤醒 Activity 类名（等价于 AutoAccounting 的 FloatingWindowTriggerActivity）。 */
    const val WAKE_ACTIVITY_CLASS: String = "$PLUGIN_PACKAGE.service.CaptureWakeActivity"

    /** relay 队列 id；插件入库完成后用它回 ack。 */
    const val EXTRA_RELAY_ID: String = "relayId"

    /** TCP ack 的 JSON 字段名。 */
    const val RELAY_ACK_FIELD: String = "ack"

    /** relay 单条消息上限，防止异常大包拖垮宿主。 */
    const val RELAY_MAX_BYTES: Int = 2 * 1024 * 1024

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
    /** AI 规则学习：只把备注、已确认分类、账单类型发给 App，取回 1-8 字可复用关键词。 */
    const val METHOD_SUGGEST_RULE: String = "suggestRuleKeyword"
    /** 文字拆分记账：只让 AI 按备注拆条目与金额，是否拆分由用户勾选决定。 */
    const val METHOD_PREVIEW_SPLIT: String = "previewSplit"
    /** 拆分入账：N 条账单共用 paymentGroupId，一条 captureId 回执。 */
    const val METHOD_IMPORT_BILL_GROUP: String = "importBillGroup"
    /** 图片拆单预览：带图片与待记上下文由 App 后台解析明细并分类，返回拆分项 JSON。 */
    const val METHOD_PREVIEW_IMAGE_SPLIT: String = "previewImageSplit"
    /** 图片记账（旧入口兼容）：带 capture 上下文打开系统图片选择器，识别后按支付组合并入账。 */
    const val METHOD_OPEN_IMAGE_ACCOUNTING: String = "openImageAccounting"
    /** AI 生成自动记账规则：基于样本原始报文提取通用正则与字段映射。 */
    const val METHOD_GENERATE_RULE: String = "generateRule"

    /** App -> 插件：查询待记数量与待记列表（signature 权限或同签名调用）。 */
    const val METHOD_GET_PENDING_COUNT: String = "getPendingCount"
    const val METHOD_LIST_PENDING_CAPTURES: String = "listPendingCaptures"
    const val METHOD_UPDATE_CAPTURE_DRAFT: String = "updateCaptureDraft"
    const val METHOD_IGNORE_CAPTURE: String = "ignoreCapture"
    const val METHOD_MARK_CAPTURES_RECORDED: String = "markCapturesRecorded"
    const val METHOD_CLASSIFY_CAPTURES: String = "classifyCaptures"
    /** 轻量智能归类：只把备注与分类候选发给 AI，不传金额/时间/资产/商户。 */
    const val METHOD_CLASSIFY_NOTES: String = "classifyNotes"

    const val EXTRA_REQUEST_ID: String = "requestId"
    const val EXTRA_CAPTURE_ID: String = "captureId"
    const val EXTRA_CAPTURE_IDS: String = "captureIds"
    const val EXTRA_PREFILL: String = "prefill"
    const val EXTRA_BILL_ID: String = "billId"
    /** `classifyNotes` 的请求数组（每项 captureId/note/billType）。 */
    const val EXTRA_NOTES: String = "notes"
    /** `suggestRuleKeyword` 的 JSON：{note, categoryName, billType}。 */
    const val EXTRA_RULE_PAYLOAD: String = "rulePayload"
    /** `previewSplit` 的 JSON：{captureId, note, totalCents, billType}。 */
    const val EXTRA_SPLIT_PAYLOAD: String = "splitPayload"
    /** `importBillGroup` 的 JSON：capture 字段 + items。 */
    const val EXTRA_BILL_GROUP_PAYLOAD: String = "billGroupPayload"
    /** `openImageAccounting` 的 JSON：amountCents/assetId/assetName/paidAt/bookName/type/subType/currency 等。 */
    const val EXTRA_CAPTURE_PAYLOAD: String = "capturePayload"
    /** `previewImageSplit` 传递图片的 ParcelFileDescriptor。 */
    const val EXTRA_IMAGE_PFD: String = "imagePfd"
    /** `previewImageSplit` 图片的 MIME 类型。 */
    const val EXTRA_IMAGE_MIME: String = "imageMime"
    /** `generateRule` 的请求 JSON：{app, type, rawJson}。 */
    const val EXTRA_GENERATE_RULE_PAYLOAD: String = "generateRulePayload"

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
