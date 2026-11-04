package com.taostudio.tapaccounting.capture.data

import android.content.Context

object CapturePrefs {
    private const val PREF_NAME = "capture_plugin_prefs"
    private const val KEY_PROMPT_MODE = "prompt_mode"
    private const val KEY_ONLY_EXPENSE = "only_expense"
    private const val KEY_SEEDED_DEFAULTS = "seeded_defaults_v1"

    /** 付款时弹出底部轻气泡 + 通知栏 */
    const val MODE_BUBBLE = "BUBBLE"
    /** 仅通知栏（支持通知栏快捷回复备注） */
    const val MODE_NOTIFICATION = "NOTIFICATION"
    /** 纯静默（不弹窗不发通知，仅攒进待记） */
    const val MODE_SILENT = "SILENT"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getPromptMode(context: Context): String =
        prefs(context).getString(KEY_PROMPT_MODE, MODE_BUBBLE) ?: MODE_BUBBLE

    fun setPromptMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_PROMPT_MODE, mode).apply()
    }

    /** 是否默认只处理支出（忽略或折叠收入/转账/退款）。默认开启，让菜市场/日常消费收件箱极其干净。 */
    fun isOnlyExpense(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONLY_EXPENSE, true)

    fun setOnlyExpense(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONLY_EXPENSE, enabled).apply()
    }

    fun isDefaultsSeeded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEEDED_DEFAULTS, false)

    fun setDefaultsSeeded(context: Context, seeded: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEEDED_DEFAULTS, seeded).apply()
    }
}
