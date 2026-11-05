package com.taostudio.tapaccounting.data.local

/**
 * v41 -> v42：把插件侧的 5 张待记表并入主应用主库。
 *
 * 这里的 SQL 直接来自插件 `AppDatabase_Impl` 生成的 schema（插件 DB v11），
 * 与 [com.taostudio.tapaccounting.capture.data] 下的 Room Entity 一一对应。
 * 修改任何 Entity 字段/索引时，必须同步这里并更新
 * `app/src/test/.../CaptureSchemaMigrationTest`。
 *
 * 注意：不要用 `room_master_table` 的 identity hash 做手工判断；
 * Room 会在打开 v42 时按本文件建出的表结构做完整校验。
 */
internal val CAPTURE_MIGRATION_41_42_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `payment_captures` (" +
        "`id` TEXT NOT NULL, " +
        "`source` TEXT NOT NULL, " +
        "`sourceTxnId` TEXT, " +
        "`amountCents` INTEGER, " +
        "`currency` TEXT NOT NULL, " +
        "`merchant` TEXT, " +
        "`categoryName` TEXT, " +
        "`categorySource` TEXT, " +
        "`paymentMethodRaw` TEXT, " +
        "`assetId` INTEGER, " +
        "`assetName` TEXT, " +
        "`toAssetName` TEXT, " +
        "`paidAt` INTEGER NOT NULL, " +
        "`capturedAt` INTEGER NOT NULL, " +
        "`bookName` TEXT, " +
        "`note` TEXT, " +
        "`billType` INTEGER, " +
        "`subType` INTEGER, " +
        "`needsRefundLink` INTEGER NOT NULL DEFAULT 0, " +
        "`status` TEXT NOT NULL, " +
        "`splitRequested` INTEGER NOT NULL DEFAULT 0, " +
        "`excludeFromStats` INTEGER NOT NULL DEFAULT 0, " +
        "`needsAmount` INTEGER NOT NULL, " +
        "`rawJson` TEXT, " +
        "`ruleVersion` TEXT, " +
        "`sourceAppVersion` TEXT, " +
        "`groupId` TEXT, " +
        "`channel` TEXT, " +
        "`createdAt` INTEGER NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_payment_captures_status` " +
        "ON `payment_captures` (`status`)",
    "CREATE INDEX IF NOT EXISTS `index_payment_captures_paidAt` " +
        "ON `payment_captures` (`paidAt`)",
    "CREATE INDEX IF NOT EXISTS `index_payment_captures_source_sourceTxnId` " +
        "ON `payment_captures` (`source`, `sourceTxnId`)",
    "CREATE INDEX IF NOT EXISTS `index_payment_captures_groupId` " +
        "ON `payment_captures` (`groupId`)",
    "CREATE TABLE IF NOT EXISTS `capture_events` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`source` TEXT NOT NULL, " +
        "`rawType` TEXT NOT NULL, " +
        "`rawJson` TEXT NOT NULL, " +
        "`receivedAt` INTEGER NOT NULL, " +
        "`matched` INTEGER NOT NULL, " +
        "`ruleName` TEXT, " +
        "`autoType` TEXT, " +
        "`localType` INTEGER, " +
        "`localSubType` INTEGER, " +
        "`amountCents` INTEGER, " +
        "`merchant` TEXT, " +
        "`categoryName` TEXT, " +
        "`channel` TEXT, " +
        "`rawHash` TEXT NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_capture_events_rawHash` " +
        "ON `capture_events` (`rawHash`)",
    "CREATE INDEX IF NOT EXISTS `index_capture_events_receivedAt` " +
        "ON `capture_events` (`receivedAt`)",
    "CREATE TABLE IF NOT EXISTS `asset_mapping_rules` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`pattern` TEXT NOT NULL, " +
        "`targetAssetName` TEXT NOT NULL, " +
        "`isRegex` INTEGER NOT NULL, " +
        "`sort` INTEGER NOT NULL, " +
        "`enabled` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_asset_mapping_rules_pattern` " +
        "ON `asset_mapping_rules` (`pattern`)",
    "CREATE TABLE IF NOT EXISTS `category_rules` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`keyword` TEXT NOT NULL, " +
        "`matchField` TEXT NOT NULL, " +
        "`isRegex` INTEGER NOT NULL, " +
        "`targetCategoryName` TEXT NOT NULL, " +
        "`targetBookName` TEXT, " +
        "`sort` INTEGER NOT NULL, " +
        "`enabled` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, " +
        "`source` TEXT NOT NULL DEFAULT 'USER', " +
        "`learnedAt` INTEGER)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_category_rules_keyword_matchField` " +
        "ON `category_rules` (`keyword`, `matchField`)",
    "CREATE TABLE IF NOT EXISTS `rule_learning_tasks` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`captureId` TEXT NOT NULL, " +
        "`note` TEXT NOT NULL, " +
        "`categoryName` TEXT NOT NULL, " +
        "`billType` INTEGER NOT NULL, " +
        "`attempts` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_rule_learning_tasks_captureId` " +
        "ON `rule_learning_tasks` (`captureId`)",
)
