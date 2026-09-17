package com.taostudio.tapaccounting

import android.content.Context
import org.json.JSONArray

object PrefsGeneralSupport {
    private const val PREFS_NAME = "flip_prefs"
    private const val KEY_DISABLE_LANDSCAPE = "flip_disable_landscape"
    private const val KEY_WHITE_LIST = "app_white_list"
    private const val KEY_HIDE_RECENTS = "hide_recents_card"
    private const val KEY_LOGGING_ENABLED = "logging_enabled"
    private const val KEY_ACTIVE_CURRENCIES = "active_currencies_v1"
    private const val KEY_EXCHANGE_REFRESH_INTERVAL = "exchange_refresh_interval_v1"
    private const val KEY_ASSET_AMOUNT_DISPLAY_MODE = "asset_amount_display_mode_v1"
    private const val KEY_SHIZUKU_PERSISTENCE = "advanced_shizuku_persistence"
    private const val KEY_SHIZUKU_MODE = "advanced_shizuku_mode"
    private const val KEY_VIBRATE_FEEDBACK = "vibrate_feedback"
    private const val KEY_SAVE_VIBRATE = "save_vibrate_feedback"
    private const val KEY_APP_USAGE_MODE = "app_usage_mode"
    private const val KEY_ASR_MODE = "asr_engine_mode"
    private const val KEY_ASR_DOWNLOAD_SOURCE = "asr_download_source_v1"
    private const val KEY_ASSET_FEATURE_ENABLED = "asset_feature_enabled_v1"
    private const val KEY_PRIVACY_DEBUG_UNTIL_MS = "privacy_debug_until_ms_v1"
    private const val KEY_DEVELOPER_FULL_LOGGING = "developer_full_logging_v1"
    private const val KEY_QUICK_GESTURE_ENABLED = "quick_gesture_enabled"
    private const val KEY_FLIP_ENABLED = "flip_enabled"
    private const val KEY_FLIP_SENSITIVITY = "flip_sensitivity_level"
    private const val KEY_DOUBLE_TAP_ENABLED = "double_tap_enabled"
    private const val KEY_DOUBLE_TAP_GUIDE_SEEN = "double_tap_guide_seen"
    private const val KEY_FLIP_GUIDE_SEEN = "flip_guide_seen_v1"
    private const val KEY_HOME_ONBOARDING_SEEN = "home_onboarding_seen_v1"
    private const val KEY_SETTINGS_GUIDE_DISMISSED = "settings_guide_dismissed_v1"
    private const val KEY_QUICK_GESTURE_SETUP_GUIDE_SEEN = "quick_gesture_setup_guide_seen_v1"
    private const val KEY_SENSITIVITY_ONBOARDING_SEEN = "sensitivity_onboarding_seen_v2"
    private const val KEY_GESTURE_PERMISSION_PROMPT_DEFER_UNTIL_MS = "gesture_permission_prompt_defer_until_ms_v1"
    private const val KEY_TAP_MODEL = "tap_model"
    private const val KEY_TAP_SENSITIVITY_LEVEL = "tap_sensitivity_level"
    private const val KEY_TAP_HE_TEST_MODE = "tap_he_test_mode"
    private const val KEY_TAP_NNAPI_LOW_POWER = "tap_nnapi_low_power"
    private const val KEY_TAP_TRIPLE_ENABLED = "tap_triple_enabled"
    private const val KEY_TAP_LOW_POWER = "tap_low_power"
    private const val KEY_TAP_FORCE_FULL_ML_MIGRATED = "tap_force_full_ml_migrated_v1"
    private const val KEY_TAP_POWER_SAVING = "tap_power_saving"
    private const val KEY_TAP_POWER_SAVING_MIGRATED = "tap_power_saving_migrated_v1"
    private const val KEY_FLIP_ACTION = "flip_action"
    private const val KEY_TAP_ACTION_DOUBLE = "tap_action_double"
    private const val KEY_TAP_ACTION_TRIPLE = "tap_action_triple"
    private const val KEY_API_CONFIG_UNLOCKED = "api_config_unlocked_v1"
    private const val KEY_AI_DETAIL_CONFIG_UNLOCKED = "ai_detail_config_unlocked_v1"
    private const val KEY_SHIZUKU_UNLOCKED = "shizuku_unlocked_v1"
    private const val KEY_AGGRESSIVE_KEEP_ALIVE = "aggressive_keep_alive"
    private const val KEY_BACKUP_INITIALIZED = "backup_initialized_v1"
    private const val KEY_RECURRING_AUTO_DETECT_ENABLED = "recurring_auto_detect_enabled_v1"
    private const val KEY_RECURRING_DETECT_AMOUNT_TOLERANCE = "recurring_detect_amount_tolerance_v1"
    private const val KEY_RECURRING_AUTO_DETECT_GUIDE_SEEN = "recurring_auto_detect_guide_seen_v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isVibrateFeedbackEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VIBRATE_FEEDBACK, true)
    fun setVibrateFeedbackEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_VIBRATE_FEEDBACK, enabled).apply()

    fun isSaveVibrateEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SAVE_VIBRATE, true)
    fun setSaveVibrateEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SAVE_VIBRATE, enabled).apply()

    fun isDisableLandscape(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DISABLE_LANDSCAPE, false)
    fun setDisableLandscape(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_DISABLE_LANDSCAPE, enabled).apply()

    /**
     * 隐藏后台卡片已被废弃，永远返回 false，也不再允许打开。
     *
     * 这个开关原本的假设是"卡片消失能少被系统清理"，实测结论相反：
     * 卡片是 ColorOS/Osense 判定"用户还在用"的输入之一，卡片被摘掉之后
     * 进程会进入"无任务"状态并反复被杀。相关证据见 keepalive 排查记录。
     *
     * 保留 key 与 setter 是为了兼容旧备份的恢复（老备份里可能有 hide_recents_v1，
     * 直接删 key 会让恢复逻辑找不到字段）。
     */
    fun isHideRecents(ctx: Context): Boolean = false

    @Deprecated("隐藏后台卡片已被证伪，调用不再生效", ReplaceWith(""))
    fun setHideRecents(ctx: Context, hide: Boolean) {
        // 故意不落盘：即使旧备份把 hide_recents_v1 恢复成 true，也不会再隐藏卡片
    }

    fun isShizukuPersistenceEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHIZUKU_PERSISTENCE, false)
    fun setShizukuPersistenceEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHIZUKU_PERSISTENCE, enabled).apply()

    fun isShizukuModeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHIZUKU_MODE, false)
    fun setShizukuModeEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHIZUKU_MODE, enabled).apply()

    fun isLoggingEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOGGING_ENABLED, false)
    fun setLoggingEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()

    fun getAppUsageMode(ctx: Context): Int = prefs(ctx).getInt(KEY_APP_USAGE_MODE, 0)
    fun setAppUsageMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_APP_USAGE_MODE, mode).apply()

    fun getAsrMode(ctx: Context): Int = prefs(ctx).getInt(KEY_ASR_MODE, Prefs.ASR_MODE_API)
    fun setAsrMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_ASR_MODE, mode).apply()

    fun getAsrDownloadSource(ctx: Context): String =
        prefs(ctx).getString(KEY_ASR_DOWNLOAD_SOURCE, "github") ?: "github"
    fun setAsrDownloadSource(ctx: Context, source: String) =
        prefs(ctx).edit().putString(KEY_ASR_DOWNLOAD_SOURCE, source).apply()

    fun isAssetFeatureEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ASSET_FEATURE_ENABLED, true)
    fun setAssetFeatureEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_ASSET_FEATURE_ENABLED, enabled).apply()

    fun isRecurringAutoDetectEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RECURRING_AUTO_DETECT_ENABLED, false)

    fun setRecurringAutoDetectEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_RECURRING_AUTO_DETECT_ENABLED, enabled).apply()

    fun hasSeenRecurringAutoDetectGuide(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RECURRING_AUTO_DETECT_GUIDE_SEEN, false)

    fun setRecurringAutoDetectGuideSeen(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_RECURRING_AUTO_DETECT_GUIDE_SEEN, true).apply()

    fun getRecurringDetectAmountTolerance(ctx: Context): Double =
        prefs(ctx).getFloat(KEY_RECURRING_DETECT_AMOUNT_TOLERANCE, 1.0f).toDouble()

    fun setRecurringDetectAmountTolerance(ctx: Context, tolerance: Double) =
        prefs(ctx).edit()
            .putFloat(KEY_RECURRING_DETECT_AMOUNT_TOLERANCE, tolerance.coerceAtLeast(0.0).toFloat())
            .apply()

    fun enablePrivacyDebugLoggingForMinutes(ctx: Context, minutes: Int) {
        val durationMs = minutes.coerceIn(1, 240) * 60_000L
        val untilMs = System.currentTimeMillis() + durationMs
        prefs(ctx).edit().putLong(KEY_PRIVACY_DEBUG_UNTIL_MS, untilMs).apply()
    }

    fun disablePrivacyDebugLogging(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PRIVACY_DEBUG_UNTIL_MS).apply()
    }

    fun isPrivacyDebugLoggingEnabled(ctx: Context): Boolean {
        if (isDeveloperFullLoggingEnabled(ctx)) return true
        val untilMs = prefs(ctx).getLong(KEY_PRIVACY_DEBUG_UNTIL_MS, 0L)
        if (untilMs <= 0L) return false
        val enabled = System.currentTimeMillis() < untilMs
        if (!enabled) {
            disablePrivacyDebugLogging(ctx)
        }
        return enabled
    }

    fun isDeveloperFullLoggingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DEVELOPER_FULL_LOGGING, false)

    fun setDeveloperFullLoggingEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_DEVELOPER_FULL_LOGGING, enabled).apply()

    fun getAppWhiteList(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_WHITE_LIST, emptySet()) ?: emptySet()
    fun setAppWhiteList(ctx: Context, list: Set<String>) =
        prefs(ctx).edit().putStringSet(KEY_WHITE_LIST, list).apply()

    fun getActiveCurrencies(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_ACTIVE_CURRENCIES, setOf("CNY")) ?: setOf("CNY")
    fun setActiveCurrencies(ctx: Context, currencies: Set<String>) =
        prefs(ctx).edit().putStringSet(KEY_ACTIVE_CURRENCIES, currencies).apply()

    fun getExchangeRefreshInterval(ctx: Context): Long =
        prefs(ctx).getLong(KEY_EXCHANGE_REFRESH_INTERVAL, 12 * 3600 * 1000L)
    fun setExchangeRefreshInterval(ctx: Context, interval: Long) =
        prefs(ctx).edit().putLong(KEY_EXCHANGE_REFRESH_INTERVAL, interval).apply()

    fun getAssetAmountDisplayMode(ctx: Context): String =
        prefs(ctx).getString(KEY_ASSET_AMOUNT_DISPLAY_MODE, "source:ALL;target:CNY") ?: "source:ALL;target:CNY"
    fun setAssetAmountDisplayMode(ctx: Context, mode: String) =
        prefs(ctx).edit().putString(KEY_ASSET_AMOUNT_DISPLAY_MODE, mode).apply()

    fun isQuickGestureEnabled(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.contains(KEY_QUICK_GESTURE_ENABLED)) {
            return p.getBoolean(KEY_QUICK_GESTURE_ENABLED, false) || isDoubleTapEnabled(ctx) || isFlipEnabled(ctx)
        }
        val migrated = isDoubleTapEnabled(ctx) || isFlipEnabled(ctx)
        setQuickGestureEnabled(ctx, migrated)
        return migrated
    }
    fun setQuickGestureEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit()
            .putBoolean(KEY_QUICK_GESTURE_ENABLED, enabled)
            .putBoolean(KEY_DOUBLE_TAP_ENABLED, enabled)
            .apply()

    fun isFlipEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FLIP_ENABLED, false)
    fun setFlipEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit()
            .putBoolean(KEY_FLIP_ENABLED, enabled)
            .putBoolean(KEY_QUICK_GESTURE_ENABLED, enabled || isDoubleTapEnabled(ctx))
            .apply()

    fun getFlipSensitivity(ctx: Context): Int =
        prefs(ctx).getInt(KEY_FLIP_SENSITIVITY, 50)
    fun setFlipSensitivity(ctx: Context, level: Int) =
        prefs(ctx).edit().putInt(KEY_FLIP_SENSITIVITY, level.coerceIn(0, 100)).apply()

    fun getFlipAction(ctx: Context): String =
        prefs(ctx).getString(KEY_FLIP_ACTION, "show_overlay") ?: "show_overlay"
    fun setFlipAction(ctx: Context, actionId: String) =
        prefs(ctx).edit().putString(KEY_FLIP_ACTION, actionId).apply()

    fun hasSeenFlipGuide(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FLIP_GUIDE_SEEN, false)
    fun setFlipGuideSeen(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_FLIP_GUIDE_SEEN, true).apply()

    fun isDoubleTapEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DOUBLE_TAP_ENABLED, false)
    fun setDoubleTapEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit()
            .putBoolean(KEY_DOUBLE_TAP_ENABLED, enabled)
            .putBoolean(KEY_QUICK_GESTURE_ENABLED, enabled || isFlipEnabled(ctx))
            .apply()

    fun hasSeenDoubleTapGuide(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DOUBLE_TAP_GUIDE_SEEN, false)
    fun setDoubleTapGuideSeen(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_DOUBLE_TAP_GUIDE_SEEN, true).apply()

    fun hasSeenQuickGestureSetupGuide(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_QUICK_GESTURE_SETUP_GUIDE_SEEN, false)
    fun setQuickGestureSetupGuideSeen(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_QUICK_GESTURE_SETUP_GUIDE_SEEN, true).apply()

    fun hasSeenSensitivityOnboarding(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SENSITIVITY_ONBOARDING_SEEN, false)
    fun setSensitivityOnboardingSeen(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_SENSITIVITY_ONBOARDING_SEEN, true).apply()

    fun hasSeenHomeOnboarding(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_HOME_ONBOARDING_SEEN, false)
    fun setHomeOnboardingSeen(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_HOME_ONBOARDING_SEEN, true).apply()

    fun isSettingsGuideDismissed(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SETTINGS_GUIDE_DISMISSED, false)
    fun setSettingsGuideDismissed(ctx: Context, dismissed: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SETTINGS_GUIDE_DISMISSED, dismissed).apply()

    fun getGesturePermissionPromptDeferUntilMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_GESTURE_PERMISSION_PROMPT_DEFER_UNTIL_MS, 0L)

    fun setGesturePermissionPromptDeferUntilMs(ctx: Context, untilMs: Long) {
        val editor = prefs(ctx).edit()
        if (untilMs <= 0L) {
            editor.remove(KEY_GESTURE_PERMISSION_PROMPT_DEFER_UNTIL_MS)
        } else {
            editor.putLong(KEY_GESTURE_PERMISSION_PROMPT_DEFER_UNTIL_MS, untilMs)
        }
        editor.apply()
    }

    fun shouldDeferGesturePermissionPrompt(ctx: Context): Boolean {
        val until = getGesturePermissionPromptDeferUntilMs(ctx)
        if (until <= 0L) return false
        val deferred = System.currentTimeMillis() < until
        if (!deferred) {
            setGesturePermissionPromptDeferUntilMs(ctx, 0L)
        }
        return deferred
    }

    // --- Tap back settings ---
    fun getTapModel(ctx: Context): String =
        prefs(ctx).getString(KEY_TAP_MODEL, "") ?: ""
    fun setTapModel(ctx: Context, model: String) =
        prefs(ctx).edit().putString(KEY_TAP_MODEL, model).apply()

    fun getTapSensitivityLevel(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TAP_SENSITIVITY_LEVEL, 5)
    fun setTapSensitivityLevel(ctx: Context, level: Int) =
        prefs(ctx).edit().putInt(KEY_TAP_SENSITIVITY_LEVEL, level).apply()

    /**
     * 省电敲击测试模式：全程启发式，敲中只给震动+提示、不执行动作。
     * 用来快速试出灵敏度该调多少。
     *
     * 只在省电敲击开启时生效。
     */
    fun isTapHeTestModeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TAP_HE_TEST_MODE, false)
    fun setTapHeTestModeEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TAP_HE_TEST_MODE, enabled).apply()

    fun isTapNnapiLowPower(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TAP_NNAPI_LOW_POWER, false)
    fun setTapNnapiLowPower(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TAP_NNAPI_LOW_POWER, enabled).apply()

    /**
     * 敲击检测省电：开启后长时间无敲击会切到启发式待机；默认关闭（全程 ML）。
     */
    fun isTapPowerSavingEnabled(ctx: Context): Boolean {
        val prefs = prefs(ctx)
        if (!prefs.getBoolean(KEY_TAP_POWER_SAVING_MIGRATED, false)) {
            prefs.edit()
                .putBoolean(KEY_TAP_POWER_SAVING, false)
                .putBoolean(KEY_TAP_POWER_SAVING_MIGRATED, true)
                .putBoolean(KEY_TAP_FORCE_FULL_ML_MIGRATED, true)
                .apply()
        }
        return prefs.getBoolean(KEY_TAP_POWER_SAVING, false)
    }

    fun setTapPowerSavingEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit()
            .putBoolean(KEY_TAP_POWER_SAVING, enabled)
            .putBoolean(KEY_TAP_POWER_SAVING_MIGRATED, true)
            .apply()

    /** @deprecated 语义已反转，请用 [isTapPowerSavingEnabled] */
    fun isTapForceFullMl(ctx: Context): Boolean = !isTapPowerSavingEnabled(ctx)

    /** @deprecated 语义已反转，请用 [setTapPowerSavingEnabled] */
    fun setTapForceFullMl(ctx: Context, enabled: Boolean) =
        setTapPowerSavingEnabled(ctx, !enabled)

    fun isTapTripleEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TAP_TRIPLE_ENABLED, false)
    fun setTapTripleEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TAP_TRIPLE_ENABLED, enabled).apply()

    fun getTapActionDouble(ctx: Context): String =
        prefs(ctx).getString(KEY_TAP_ACTION_DOUBLE, "") ?: ""
    fun setTapActionDouble(ctx: Context, actionId: String) =
        prefs(ctx).edit().putString(KEY_TAP_ACTION_DOUBLE, actionId).apply()

    fun getTapActionTriple(ctx: Context): String =
        prefs(ctx).getString(KEY_TAP_ACTION_TRIPLE, "") ?: ""
    fun setTapActionTriple(ctx: Context, actionId: String) =
        prefs(ctx).edit().putString(KEY_TAP_ACTION_TRIPLE, actionId).apply()

    fun serializeWhiteList(set: Set<String>): String = JSONArray(set).toString()

    fun importWhiteList(ctx: Context, jsonStr: String) {
        runCatching {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            setAppWhiteList(ctx, list.toSet())
        }.onFailure { it.printStackTrace() }
    }

    fun isApiConfigUnlocked(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_API_CONFIG_UNLOCKED, false)
    fun setApiConfigUnlocked(ctx: Context, unlocked: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_API_CONFIG_UNLOCKED, unlocked).apply()

    fun isAiDetailConfigUnlocked(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_DETAIL_CONFIG_UNLOCKED, false)
    fun setAiDetailConfigUnlocked(ctx: Context, unlocked: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_DETAIL_CONFIG_UNLOCKED, unlocked).apply()

    fun isShizukuUnlocked(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHIZUKU_UNLOCKED, false)
    fun setShizukuUnlocked(ctx: Context, unlocked: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHIZUKU_UNLOCKED, unlocked).apply()

    fun isAggressiveKeepAliveEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AGGRESSIVE_KEEP_ALIVE, false)
    fun setAggressiveKeepAliveEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AGGRESSIVE_KEEP_ALIVE, enabled).apply()

    // --- Backup initialization ---
    fun isBackupInitialized(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BACKUP_INITIALIZED, false)
    fun markBackupInitialized(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_BACKUP_INITIALIZED, true).apply()
}
