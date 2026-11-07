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

/**
 * v42 -> v43：待记与 AI 记账规则合并为 accounting_rules，新增 bill_rule_provenance，
 * payment_captures 新增 categoryRuleId。
 */
internal val CAPTURE_MIGRATION_42_43_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `accounting_rules` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`keyword` TEXT NOT NULL, " +
        "`isRegex` INTEGER NOT NULL, " +
        "`matchField` TEXT NOT NULL, " +
        "`matchMode` TEXT NOT NULL, " +
        "`targetType` INTEGER, " +
        "`targetCategory` TEXT, " +
        "`targetBook` TEXT, " +
        "`targetAccount1` TEXT, " +
        "`targetAccount2` TEXT, " +
        "`sort` INTEGER NOT NULL, " +
        "`enabled` INTEGER NOT NULL, " +
        "`source` TEXT NOT NULL, " +
        "`learnedAt` INTEGER, " +
        "`createdAt` INTEGER NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_accounting_rules_keyword` " +
        "ON `accounting_rules` (`keyword`)",
    "CREATE INDEX IF NOT EXISTS `index_accounting_rules_matchField` " +
        "ON `accounting_rules` (`matchField`)",
    "CREATE INDEX IF NOT EXISTS `index_accounting_rules_enabled` " +
        "ON `accounting_rules` (`enabled`)",
    "INSERT INTO `accounting_rules` (" +
        "keyword, isRegex, matchField, matchMode, targetType, targetCategory, " +
        "targetBook, targetAccount1, targetAccount2, sort, enabled, source, learnedAt, createdAt, updatedAt) " +
        "SELECT keyword, isRegex, matchField, 'SUBSTRING', NULL, targetCategoryName, " +
        "targetBookName, NULL, NULL, sort, enabled, source, learnedAt, createdAt, createdAt " +
        "FROM `category_rules`",
    "INSERT INTO `accounting_rules` (" +
        "keyword, isRegex, matchField, matchMode, targetType, targetCategory, " +
        "targetBook, targetAccount1, targetAccount2, sort, enabled, source, learnedAt, createdAt, updatedAt) " +
        "SELECT keyword, 0, 'REMARK', 'ALL_TOKENS', targetType, targetCategory, " +
        "NULL, targetAccount1, targetAccount2, 0, isEnabled, 'USER', NULL, " +
        "CAST((strftime('%s','now')) AS INTEGER) * 1000, " +
        "CAST((strftime('%s','now')) AS INTEGER) * 1000 " +
        "FROM `ai_rule`",
    "DELETE FROM `accounting_rules` WHERE id NOT IN (" +
        "SELECT MIN(id) FROM `accounting_rules` " +
        "GROUP BY keyword, matchField, targetCategory, source)",
    "CREATE TABLE IF NOT EXISTS `bill_rule_provenance` (" +
        "`billId` INTEGER PRIMARY KEY NOT NULL, " +
        "`captureId` TEXT, " +
        "`source` TEXT NOT NULL, " +
        "`ruleId` INTEGER, " +
        "`keyword` TEXT, " +
        "`createdAt` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_bill_rule_provenance_billId` " +
        "ON `bill_rule_provenance` (`billId`)",
    "CREATE INDEX IF NOT EXISTS `index_bill_rule_provenance_captureId` " +
        "ON `bill_rule_provenance` (`captureId`)",
    "ALTER TABLE `payment_captures` ADD COLUMN `categoryRuleId` INTEGER",
)

/**
 * v43 -> v44：待记卡片展示命中规则名。
 */
internal val CAPTURE_MIGRATION_43_44_SQL: List<String> = listOf(
    "ALTER TABLE `payment_captures` ADD COLUMN `ruleName` TEXT",
)
