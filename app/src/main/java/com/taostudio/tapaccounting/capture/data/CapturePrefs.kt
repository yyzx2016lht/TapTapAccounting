package com.taostudio.tapaccounting.capture.data

import android.content.Context

object CapturePrefs {
    private const val PREF_NAME = "capture_plugin_prefs"
    private const val KEY_PROMPT_MODE = "prompt_mode"
    private const val KEY_ONLY_EXPENSE = "only_expense_v2"
    private const val KEY_SEEDED_DEFAULTS = "seeded_defaults_v1"
    private const val KEY_LEGACY_IMPORTED = "legacy_imported_v1"

    /** 付款时弹出底部轻气泡 + 通知栏 */
    const val MODE_BUBBLE = "BUBBLE"
    /** 仅通知栏（支持通知栏快捷回复备注） */
    const val MODE_NOTIFICATION = "NOTIFICATION"
    /** 纯静默（不弹窗不发通知，仅攒进待记） */
    const val MODE_SILENT = "SILENT"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * P3 起主应用接管气泡：默认付款瞬间弹底部胶囊；用户可在主应用设置页改成通知/静默。
     */
    fun getPromptMode(context: Context): String =
        prefs(context).getString(KEY_PROMPT_MODE, MODE_BUBBLE) ?: MODE_BUBBLE

    fun setPromptMode(context: Context, mode: String) {
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

    /** 旧插件 capture.db 是否已经并入主应用主库。 */
    fun isLegacyImported(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEGACY_IMPORTED, false)

    fun setLegacyImported(context: Context, imported: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEGACY_IMPORTED, imported).apply()
    }
}
