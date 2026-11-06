package com.taostudio.tapaccounting.capture.data

import android.content.Context

object CapturePrefs {
    private const val PREF_NAME = "capture_plugin_prefs"
    private const val KEY_PROMPT_MODE = "prompt_mode"
    private const val KEY_BUBBLE_ENABLED = "alert_bubble_enabled"
    private const val KEY_NOTIFICATION_ENABLED = "alert_notification_enabled"
    private const val KEY_ONLY_EXPENSE = "only_expense_v2"
    private const val KEY_SEEDED_DEFAULTS = "seeded_defaults_v1"
    private const val KEY_LEGACY_IMPORTED = "legacy_imported_v1"
    private const val KEY_TRANSFER_INCOME_DEDUP = "transfer_income_dedup_enabled"
    private const val KEY_TRANSFER_INCOME_DEDUP_WINDOW = "transfer_income_dedup_window_seconds"
    private const val KEY_AI_CATEGORY_MODE = "ai_category_mode"
    private const val KEY_RULE_LEARN_MODE = "rule_learn_mode"
    private const val KEY_DATA_RULES_ENABLED = "data_rules_enabled"
    private const val KEY_EMPTY_CATEGORY_BACKFILLED = "empty_category_backfilled_v1"

    const val AI_CATEGORY_CONFIRM = "CONFIRM"
    const val AI_CATEGORY_DIRECT = "DIRECT"
    const val RULE_LEARN_AUTO = "AUTO"
    const val RULE_LEARN_ASK = "ASK"
    const val RULE_LEARN_OFF = "OFF"

    /** 转账到账后，收款方通知通常晚到几十秒；默认 5 分钟内视为同一笔。 */
    const val DEFAULT_TRANSFER_INCOME_DEDUP_WINDOW_SECONDS: Int = 300
    private const val MIN_TRANSFER_INCOME_DEDUP_WINDOW_SECONDS: Int = 30
    private const val MAX_TRANSFER_INCOME_DEDUP_WINDOW_SECONDS: Int = 1800

    /** 付款时弹出底部轻气泡 + 通知栏 */
    const val MODE_BUBBLE = "BUBBLE"
    /** 仅通知栏（支持通知栏快捷回复备注） */
    const val MODE_NOTIFICATION = "NOTIFICATION"
    /** 纯静默（不弹窗不发通知，仅攒进待记） */
    const val MODE_SILENT = "SILENT"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 气泡提醒开关（底部轻胶囊）。
     */
    fun isBubbleEnabled(context: Context): Boolean {
        val p = prefs(context)
        if (p.contains(KEY_BUBBLE_ENABLED)) {
            return p.getBoolean(KEY_BUBBLE_ENABLED, true)
        }
        val legacy = p.getString(KEY_PROMPT_MODE, null)
        return when (legacy) {
            MODE_SILENT, MODE_NOTIFICATION -> false
            else -> true
        }
    }

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()
        syncLegacyPromptMode(context)
    }

    /**
     * 通知栏待记开关。
     */
    fun isNotificationEnabled(context: Context): Boolean {
        val p = prefs(context)
        if (p.contains(KEY_NOTIFICATION_ENABLED)) {
            return p.getBoolean(KEY_NOTIFICATION_ENABLED, true)
        }
        val legacy = p.getString(KEY_PROMPT_MODE, null)
        return when (legacy) {
            MODE_SILENT -> false
            else -> true
        }
    }

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply()
        syncLegacyPromptMode(context)
    }

    /**
     * 是否完全静默（气泡与通知栏均关闭）。
     */
    fun isSilent(context: Context): Boolean =
        !isBubbleEnabled(context) && !isNotificationEnabled(context)

    fun setSilent(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_BUBBLE_ENABLED, false)
            .putBoolean(KEY_NOTIFICATION_ENABLED, false)
            .putString(KEY_PROMPT_MODE, MODE_SILENT)
            .apply()
    }

    /**
     * 兼容保留现有模式判断（如当两者皆关时等价于 MODE_SILENT；气泡开时为 MODE_BUBBLE；仅通知开时为 MODE_NOTIFICATION 等，确保其他组件读取正常）。
     */
    fun getPromptMode(context: Context): String {
        val bubble = isBubbleEnabled(context)
        val notif = isNotificationEnabled(context)
        return when {
            bubble -> MODE_BUBBLE
            notif -> MODE_NOTIFICATION
            else -> MODE_SILENT
        }
    }

    fun setPromptMode(context: Context, mode: String) {
        val (bubble, notif) = when (mode) {
            MODE_SILENT -> false to false
            MODE_NOTIFICATION -> false to true
            else -> true to true
        }
        prefs(context).edit()
            .putString(KEY_PROMPT_MODE, mode)
            .putBoolean(KEY_BUBBLE_ENABLED, bubble)
            .putBoolean(KEY_NOTIFICATION_ENABLED, notif)
            .apply()
    }

    private fun syncLegacyPromptMode(context: Context) {
        val mode = getPromptMode(context)
        prefs(context).edit().putString(KEY_PROMPT_MODE, mode).apply()
    }

    /** 是否只处理支出（忽略收入与转账）。默认关闭，保证微信转账收款、转账退款、收红包全部正常提醒与展示。 */
    fun isOnlyExpense(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONLY_EXPENSE, false)

    fun setOnlyExpense(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONLY_EXPENSE, enabled).apply()
    }

    fun isDefaultsSeeded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEEDED_DEFAULTS, false)

    fun setDefaultsSeeded(context: Context, seeded: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEEDED_DEFAULTS, seeded).apply()
    }

    /**
     * 转账到账自动判重：给某账户转账/提现后，收款方再推送的“入账/收款”通知默认归入「重复」。
     * 默认开启；关闭后所有到账通知仍会进待记。
     */
    fun isTransferIncomeDedupEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRANSFER_INCOME_DEDUP, true)

    fun setTransferIncomeDedupEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRANSFER_INCOME_DEDUP, enabled).apply()
    }

    fun getTransferIncomeDedupWindowSeconds(context: Context): Int =
        prefs(context).getInt(
            KEY_TRANSFER_INCOME_DEDUP_WINDOW,
            DEFAULT_TRANSFER_INCOME_DEDUP_WINDOW_SECONDS,
        ).coerceIn(MIN_TRANSFER_INCOME_DEDUP_WINDOW_SECONDS, MAX_TRANSFER_INCOME_DEDUP_WINDOW_SECONDS)

    fun getTransferIncomeDedupWindowMillis(context: Context): Long =
        getTransferIncomeDedupWindowSeconds(context) * 1000L

    fun setTransferIncomeDedupWindowSeconds(context: Context, seconds: Int) {
        val safe = seconds.coerceIn(MIN_TRANSFER_INCOME_DEDUP_WINDOW_SECONDS, MAX_TRANSFER_INCOME_DEDUP_WINDOW_SECONDS)
        prefs(context).edit().putInt(KEY_TRANSFER_INCOME_DEDUP_WINDOW, safe).apply()
    }

    /** 旧插件 capture.db 是否已经并入主应用主库。 */
    fun isLegacyImported(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEGACY_IMPORTED, false)

    fun setLegacyImported(context: Context, imported: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEGACY_IMPORTED, imported).apply()
    }

    fun getAiCategoryMode(context: Context): String =
        prefs(context).getString(KEY_AI_CATEGORY_MODE, AI_CATEGORY_CONFIRM) ?: AI_CATEGORY_CONFIRM

    fun setAiCategoryMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_AI_CATEGORY_MODE, mode).apply()
    }

    fun getRuleLearnMode(context: Context): String =
        prefs(context).getString(KEY_RULE_LEARN_MODE, RULE_LEARN_AUTO) ?: RULE_LEARN_AUTO

    fun setRuleLearnMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_RULE_LEARN_MODE, mode).apply()
    }

    /** 数据规则总开关：关闭后不再执行任何 AutoAccounting 数据规则，只保留本地备注/资产规则。 */
    fun isDataRulesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DATA_RULES_ENABLED, true)

    fun setDataRulesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DATA_RULES_ENABLED, enabled).apply()
    }

    /** 历史空分类是否已回填到「其他」，只跑一次。 */
    fun hasBackfilledEmptyCategories(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EMPTY_CATEGORY_BACKFILLED, false)

    fun setBackfilledEmptyCategories(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_EMPTY_CATEGORY_BACKFILLED, done).apply()
    }
}
