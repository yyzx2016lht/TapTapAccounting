package com.taostudio.tapaccounting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Backup/restore serialization helpers for SharedPreferences.
 *
 * These methods used to live directly inside [Prefs]. They are kept here so
 * the public Prefs API can stay stable while the implementation becomes easier
 * to read and maintain.
 */
object PrefsBackupSupport {
    private const val PREFS_NAME = "flip_prefs"
    private const val CLOUD_PREFS_NAME = "tap_cloud_backup_prefs"
    private const val KEY_ASSETS = "assets_v1"
    private const val KEY_CAT_EXPENSE = "cat_expense_v1"
    private const val KEY_CAT_INCOME = "cat_income_v1"
    private const val KEY_BILLS = "bills_list"
    private const val KEY_WHITE_LIST = "app_white_list"
    private const val KEY_ACTIVE_CURRENCIES = "active_currencies_v1"
    private const val KEY_EXCHANGE_REFRESH_INTERVAL = "exchange_refresh_interval_v1"
    private const val KEY_RECURRING_AUTO_DETECT_ENABLED = "recurring_auto_detect_enabled_v1"
    private const val KEY_RECURRING_DETECT_AMOUNT_TOLERANCE = "recurring_detect_amount_tolerance_v1"
    private const val KEY_QUICK_GESTURE_ENABLED = "quick_gesture_enabled"
    private const val KEY_HIDE_RECENTS = "hide_recents_card"
    private const val KEY_SHOW_AI_TEXT = "show_ai_text"
    private const val KEY_SHOW_AI_VOICE = "show_ai_voice"
    private const val KEY_SHOW_AI_IMAGE = "show_ai_image"
    private const val KEY_SHOW_SCREEN_ACCOUNTING = "show_screen_accounting"
    private const val KEY_SHOW_MULTI_CURRENCY = "show_multi_currency"
    private const val KEY_SHOW_HOME_TREND_CARD = "show_home_trend_card"
    private const val KEY_SHOW_BOOK_ENTRY = "show_book_entry"
    private const val KEY_SHOW_AI_CHAT_ENTRY = "show_ai_chat_entry"
    private const val KEY_SAVE_OCR_DEBUG = "save_ocr_debug_before_ai"
    private const val KEY_AI_KEY = "ai_api_key"
    private const val KEY_AI_PROVIDER_KEYS = "ai_provider_keys_v1"
    private const val KEY_AI_PROVIDER_KEYS_MIGRATED = "ai_provider_keys_migrated_v1"
    private const val KEY_AI_URL = "ai_api_url"
    private const val KEY_AI_MODEL = "ai_model_id"
    private const val KEY_AI_PROMPT = "ai_system_prompt"
    private const val KEY_AI_MULTI_MODEL = "ai_multi_model_id"
    private const val KEY_AI_MODIFY_MODEL = "ai_modify_model_id"
    private const val KEY_AI_CATEGORY_REFINE_MODEL = "ai_category_refine_model_id"
    private const val KEY_AI_ROUTER_MODEL = "ai_router_model_id"
    private const val KEY_AI_RULE_MODEL = "ai_rule_model_id"
    private const val KEY_AI_LLM_ROUTER_ENABLED = "ai_llm_router_enabled"
    private const val KEY_AI_RECEIPT_MODEL = "ai_receipt_model_id"
    private const val KEY_AI_RECEIPT_VISION_MODEL = "ai_receipt_vision_model_id"
    private const val KEY_AI_SCREEN_MODEL = "ai_screen_model_id"
    private const val KEY_AI_RECEIPT_OCR_REFINE_MODEL = "ai_receipt_ocr_refine_model_id"
    private const val KEY_AI_SPEECH_MODEL = "ai_speech_model_id"
    private const val KEY_AI_PROVIDER = "ai_provider"
    private const val KEY_AI_MODELS_CACHE = "ai_models_cache"
    private const val KEY_AI_ENTRY_MODE = "ai_entry_mode"
    private const val KEY_AI_CHAT_NAME = "ai_chat_name"
    private const val KEY_AI_CHAT_IDENTITY = "ai_chat_identity"
    private const val KEY_USER_CHAT_NAME = "user_chat_name"
    private const val KEY_USER_PROFILE_DESC = "user_profile_desc"
    private const val KEY_AI_CHAT_AVATAR_PATH = "ai_chat_avatar_path"
    private const val KEY_USER_CHAT_AVATAR_PATH = "user_chat_avatar_path"
    private const val KEY_AI_CHAT_BG_PATH = "ai_chat_bg_path"
    private const val KEY_AI_CHAT_MODEL = "ai_chat_model"
    private const val KEY_AI_CHAT_REPLY_STYLE = "ai_chat_reply_style"
    private const val KEY_AI_CHAT_REPLY_STYLE_CUSTOM = "ai_chat_reply_style_custom"
    private const val KEY_AI_CHAT_MODEL_AUDIO_SUPPORT = "ai_chat_model_audio_support"
    private const val KEY_MULTI_BILL_ENABLED = "multi_bill_enabled"
    private const val KEY_MULTI_BILL_NOT_SYNC = "multi_bill_not_sync"
    private const val KEY_ASR_MODE = "asr_engine_mode"
    private const val KEY_OCR_MODE = "ocr_engine_mode"
    private const val KEY_RECEIPT_OCR_REFINE_ENABLED = "receipt_ocr_refine_enabled"
    private const val KEY_RECEIPT_LANG_MODE = "receipt_lang_mode"
    private const val KEY_SHIZUKU_PERSISTENCE = "advanced_shizuku_persistence"
    private const val KEY_VIBRATE_FEEDBACK = "vibrate_feedback"
    private const val KEY_SAVE_VIBRATE = "save_vibrate_feedback"
    private const val KEY_APP_USAGE_MODE = "app_usage_mode"
    private const val KEY_ASR_DOWNLOAD_SOURCE = "asr_download_source_v1"
    private const val KEY_ASSET_FEATURE_ENABLED = "asset_feature_enabled_v1"
    private const val KEY_LOGGING_ENABLED = "logging_enabled"
    private const val KEY_AI_THINKING_MODIFY_BILL = "ai_thinking_modify_bill"
    private const val KEY_AI_THINKING_CATEGORY_REFINE = "ai_thinking_category_refine"
    private const val KEY_BILL_SHOW_CATEGORY_ICON = "bill_show_category_icon_v1"
    private const val KEY_BILL_SHOW_FULL_CATEGORY = "bill_show_full_category_v1"
    private const val KEY_BILL_REMARK_PRIORITY = "bill_remark_priority_v1"
    private const val KEY_SCREEN_VISION_SUPPORTED_MODELS = "screen_vision_supported_models"
    private const val KEY_CLOUD_WEBDAV_URL = "webdav_url"
    private const val KEY_CLOUD_WEBDAV_USER = "webdav_user"
    private const val KEY_CLOUD_WEBDAV_PASS = "webdav_pass"
    private const val KEY_CLOUD_WEBDAV_DIR = "webdav_dir"
    private const val KEY_CLOUD_DEVICE_NAME = "webdav_device_name"
    private const val KEY_HOME_BUDGET_SUMMARY_PREFIX = "home_budget_summary_v1_"
    private const val KEY_STATS_BUDGET_MODE_PREFIX = "stats_budget_mode_v1_"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun cloudPrefs(ctx: Context) = ctx.getSharedPreferences(CLOUD_PREFS_NAME, Context.MODE_PRIVATE)
    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    fun importAll(ctx: Context, root: JSONObject) {
        val edit = prefs(ctx).edit()
        val currencyEdit = ctx.getSharedPreferences("flip_currency_prefs", Context.MODE_PRIVATE).edit()
        val cloudEdit = cloudPrefs(ctx).edit()

        if (root.has("assets_v1")) {
            edit.putString(KEY_ASSETS, root.get("assets_v1").toString())
        }
        if (root.has("cat_expense_v1")) {
            edit.putString(KEY_CAT_EXPENSE, root.get("cat_expense_v1").toString())
        }
        if (root.has("cat_income_v1")) {
            edit.putString(KEY_CAT_INCOME, root.get("cat_income_v1").toString())
        }

        val billsJson = when {
            root.has("bills_v1") -> root.get("bills_v1").toString()
            root.has(KEY_BILLS) -> root.get(KEY_BILLS).toString()
            else -> null
        }
        if (billsJson != null) edit.putString(KEY_BILLS, billsJson)

        if (root.has("app_white_list_v1")) {
            runCatching {
                val arr = JSONArray(root.getString("app_white_list_v1"))
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                edit.putStringSet(KEY_WHITE_LIST, set)
            }
        } else if (root.has(KEY_WHITE_LIST)) {
            runCatching {
                val obj = root.get(KEY_WHITE_LIST)
                if (obj is JSONArray) {
                    val set = mutableSetOf<String>()
                    for (i in 0 until obj.length()) set.add(obj.getString(i))
                    edit.putStringSet(KEY_WHITE_LIST, set)
                }
            }
        }

        if (root.has("active_currencies_v1")) {
            val raw = root.getString("active_currencies_v1")
            val currencies = raw.split(",").filter { it.isNotBlank() }.toSet()
            if (currencies.isNotEmpty()) edit.putStringSet(KEY_ACTIVE_CURRENCIES, currencies)
        }
        if (root.has("exchange_refresh_interval_v1")) {
            edit.putLong(KEY_EXCHANGE_REFRESH_INTERVAL, root.getLong("exchange_refresh_interval_v1"))
        }
        if (root.has("recurring_auto_detect_enabled_v1")) {
            edit.putBoolean(KEY_RECURRING_AUTO_DETECT_ENABLED, root.getBoolean("recurring_auto_detect_enabled_v1"))
        }
        if (root.has("recurring_detect_amount_tolerance_v1")) {
            edit.putFloat(
                KEY_RECURRING_DETECT_AMOUNT_TOLERANCE,
                root.getDouble("recurring_detect_amount_tolerance_v1").coerceAtLeast(0.0).toFloat()
            )
        }
        val cmCurrenciesRaw = when {
            root.has("cm_enabled_currencies_v1") -> root.getString("cm_enabled_currencies_v1")
            root.has("active_currencies_v1") -> root.getString("active_currencies_v1")
            else -> null
        }
        if (cmCurrenciesRaw != null) {
            val cmList = cmCurrenciesRaw.split(",").filter { it.isNotBlank() }
            if (cmList.isNotEmpty()) {
                com.taostudio.tapaccounting.logic.CurrencyManager.setEnabledCurrencies(ctx, cmList)
            }
        }

        if (root.has("quick_gesture_enabled_v1")) edit.putBoolean(KEY_QUICK_GESTURE_ENABLED, root.getBoolean("quick_gesture_enabled_v1"))

        // 隐藏后台卡片已废弃：老备份里的 hide_recents_v1 一律忽略，
        // 避免恢复出"卡片被摘掉 → 进程被反复清理"的已知劣化配置。
        edit.putBoolean(KEY_HIDE_RECENTS, false)

        if (root.has("double_tap_enabled_v1")) edit.putBoolean("double_tap_enabled", root.getBoolean("double_tap_enabled_v1"))
        if (root.has("tap_model_v1")) edit.putString("tap_model", root.getString("tap_model_v1"))
        if (root.has("tap_sensitivity_level_v1")) edit.putInt("tap_sensitivity_level", root.getInt("tap_sensitivity_level_v1"))
        if (root.has("tap_he_sensitivity_v1")) edit.putInt("tap_he_sensitivity", root.getInt("tap_he_sensitivity_v1"))
        if (root.has("tap_he_test_mode_v1")) edit.putBoolean("tap_he_test_mode", root.getBoolean("tap_he_test_mode_v1"))
        if (root.has("tap_nnapi_low_power_v1")) edit.putBoolean("tap_nnapi_low_power", root.getBoolean("tap_nnapi_low_power_v1"))
        if (root.has("tap_power_saving_v1")) edit.putBoolean("tap_power_saving", root.getBoolean("tap_power_saving_v1"))
        if (root.has("tap_force_full_ml_v1")) edit.putBoolean("tap_force_full_ml_migrated_v1", root.getBoolean("tap_force_full_ml_v1"))
        if (root.has("tap_triple_enabled_v1")) edit.putBoolean("tap_triple_enabled", root.getBoolean("tap_triple_enabled_v1"))
        if (root.has("tap_action_double_v1")) edit.putString("tap_action_double", root.getString("tap_action_double_v1"))
        if (root.has("tap_action_triple_v1")) edit.putString("tap_action_triple", root.getString("tap_action_triple_v1"))
        if (root.has("flip_enabled_v1")) edit.putBoolean("flip_enabled", root.getBoolean("flip_enabled_v1"))
        if (root.has("flip_sensitivity_v1")) edit.putInt("flip_sensitivity_level", root.getInt("flip_sensitivity_v1"))
        if (root.has("flip_action_v1")) edit.putString("flip_action", root.getString("flip_action_v1"))
        if (root.has("flip_disable_landscape_v1")) edit.putBoolean("flip_disable_landscape", root.getBoolean("flip_disable_landscape_v1"))

        if (root.has("show_ai_text_v1")) edit.putBoolean(KEY_SHOW_AI_TEXT, root.getBoolean("show_ai_text_v1"))
        if (root.has("show_ai_voice_v1")) edit.putBoolean(KEY_SHOW_AI_VOICE, root.getBoolean("show_ai_voice_v1"))
        if (root.has("show_multi_cur_v1")) edit.putBoolean(KEY_SHOW_MULTI_CURRENCY, root.getBoolean("show_multi_cur_v1"))
        if (root.has("show_ai_image_v1")) edit.putBoolean(KEY_SHOW_AI_IMAGE, root.getBoolean("show_ai_image_v1"))
        if (root.has("show_screen_accounting_v1")) edit.putBoolean(KEY_SHOW_SCREEN_ACCOUNTING, root.getBoolean("show_screen_accounting_v1"))
        if (root.has("show_home_trend_card_v1")) edit.putBoolean(KEY_SHOW_HOME_TREND_CARD, root.getBoolean("show_home_trend_card_v1"))
        if (root.has("show_book_entry_v1")) edit.putBoolean(KEY_SHOW_BOOK_ENTRY, root.getBoolean("show_book_entry_v1"))
        if (root.has("show_ai_chat_entry_v1")) edit.putBoolean(KEY_SHOW_AI_CHAT_ENTRY, root.getBoolean("show_ai_chat_entry_v1"))
        if (root.has("save_ocr_debug_v1")) edit.putBoolean(KEY_SAVE_OCR_DEBUG, root.getBoolean("save_ocr_debug_v1"))
        if (root.has("amount_grouping_v1")) edit.putBoolean("amount_grouping_enabled", root.getBoolean("amount_grouping_v1"))
        if (root.has("bill_show_category_icon_v1")) edit.putBoolean(KEY_BILL_SHOW_CATEGORY_ICON, root.getBoolean("bill_show_category_icon_v1"))
        if (root.has("bill_show_full_category_v1")) edit.putBoolean(KEY_BILL_SHOW_FULL_CATEGORY, root.getBoolean("bill_show_full_category_v1"))
        if (root.has("bill_remark_priority_v1")) edit.putBoolean(KEY_BILL_REMARK_PRIORITY, root.getBoolean("bill_remark_priority_v1"))
        if (root.has("insight_cards_enabled_v1")) edit.putBoolean("insight_cards_enabled", root.getBoolean("insight_cards_enabled_v1"))
        if (root.has("independent_detail_enabled_v1")) edit.putBoolean("independent_detail_enabled_v1", root.getBoolean("independent_detail_enabled_v1"))
        if (root.has("chat_page_mode_v1")) edit.putInt("chat_page_mode", root.getInt("chat_page_mode_v1"))
        if (root.has("asset_amount_display_mode_v1")) edit.putString("asset_amount_display_mode_v1", root.getString("asset_amount_display_mode_v1"))
        if (root.has("ai_thinking_modify_bill_v1")) edit.putBoolean(KEY_AI_THINKING_MODIFY_BILL, root.getBoolean("ai_thinking_modify_bill_v1"))
        if (root.has("ai_thinking_category_refine_v1")) edit.putBoolean(KEY_AI_THINKING_CATEGORY_REFINE, root.getBoolean("ai_thinking_category_refine_v1"))
        if (root.has("ai_enable_thinking_v1")) edit.putBoolean("ai_enable_thinking", root.getBoolean("ai_enable_thinking_v1"))
        if (root.has("ai_thinking_multi_bill_v1")) edit.putBoolean("ai_thinking_multi_bill", root.getBoolean("ai_thinking_multi_bill_v1"))
        if (root.has("ai_thinking_vision_v1")) edit.putBoolean("ai_thinking_vision", root.getBoolean("ai_thinking_vision_v1"))
        if (root.has("ai_manual_model_selection_v1")) edit.putBoolean("ai_manual_model_selection_v1", root.getBoolean("ai_manual_model_selection_v1"))
        if (root.has("receipt_image_draft_confirm_v1")) edit.putBoolean("receipt_image_draft_confirm_enabled", root.getBoolean("receipt_image_draft_confirm_v1"))
        if (root.has("image_accounting_natural_language_v1")) edit.putBoolean("image_accounting_natural_language", root.getBoolean("image_accounting_natural_language_v1"))
        if (root.has("screen_accounting_use_image_flow_v1")) edit.putBoolean("screen_accounting_use_image_flow", root.getBoolean("screen_accounting_use_image_flow_v1"))

        if (root.has("ai_entry_mode_v1")) edit.putInt(KEY_AI_ENTRY_MODE, root.getInt("ai_entry_mode_v1"))
        if (root.has("ai_chat_name_v1")) edit.putString(KEY_AI_CHAT_NAME, root.getString("ai_chat_name_v1"))
        if (root.has("user_chat_name_v1")) edit.putString(KEY_USER_CHAT_NAME, root.getString("user_chat_name_v1"))
        if (root.has("user_profile_desc_v1")) edit.putString(KEY_USER_PROFILE_DESC, root.getString("user_profile_desc_v1"))
        if (root.has("ai_chat_avatar_path_v1")) edit.putString(KEY_AI_CHAT_AVATAR_PATH, root.getString("ai_chat_avatar_path_v1"))
        if (root.has("user_chat_avatar_path_v1")) edit.putString(KEY_USER_CHAT_AVATAR_PATH, root.getString("user_chat_avatar_path_v1"))
        if (root.has("ai_chat_bg_path_v1")) edit.putString(KEY_AI_CHAT_BG_PATH, root.getString("ai_chat_bg_path_v1"))
        if (root.has("ai_chat_model_v1")) edit.putString(KEY_AI_CHAT_MODEL, root.getString("ai_chat_model_v1"))
        if (root.has("ai_chat_reply_style_v1")) edit.putString(KEY_AI_CHAT_REPLY_STYLE, root.getString("ai_chat_reply_style_v1"))
        if (root.has("ai_chat_reply_style_custom_v1")) edit.putString(KEY_AI_CHAT_REPLY_STYLE_CUSTOM, root.getString("ai_chat_reply_style_custom_v1"))
        if (root.has("ai_chat_model_audio_support_v1")) edit.putString(KEY_AI_CHAT_MODEL_AUDIO_SUPPORT, root.getString("ai_chat_model_audio_support_v1"))

        if (root.has("ai_api_key_v1")) edit.putString(KEY_AI_KEY, root.getString("ai_api_key_v1"))
        if (root.has("ai_api_url_v1")) edit.putString(KEY_AI_URL, root.getString("ai_api_url_v1"))
        if (root.has("ai_provider_v1")) edit.putString(KEY_AI_PROVIDER, root.getString("ai_provider_v1"))
        if (root.has("ai_provider_keys_v1")) {
            edit.putString(KEY_AI_PROVIDER_KEYS, root.getString("ai_provider_keys_v1"))
            edit.putBoolean(KEY_AI_PROVIDER_KEYS_MIGRATED, true)
        }
        val importedTextModel = firstNonBlank(
            root.optString("ai_text_model_v1"),
            root.optString("ai_multi_model_v1"),
            root.optString("ai_model_id_v1"),
            root.optString("ai_modify_model_v1"),
            root.optString("ai_category_refine_model_v1"),
            root.optString("ai_rule_model_v1"),
            root.optString("ai_receipt_model_v1"),
            root.optString("ai_receipt_ocr_refine_model_v1")
        )
        if (importedTextModel.isNotBlank()) {
            edit.putString(KEY_AI_MODEL, importedTextModel)
            edit.putString(KEY_AI_MULTI_MODEL, importedTextModel)
            edit.putString(KEY_AI_MODIFY_MODEL, importedTextModel)
            edit.putString(KEY_AI_CATEGORY_REFINE_MODEL, importedTextModel)
            edit.putString(KEY_AI_RULE_MODEL, importedTextModel)
            edit.putString(KEY_AI_RECEIPT_MODEL, importedTextModel)
            edit.putString(KEY_AI_RECEIPT_OCR_REFINE_MODEL, importedTextModel)
        } else if (root.has("ai_model_id_v1")) {
            edit.putString(KEY_AI_MODEL, root.getString("ai_model_id_v1"))
        }
        if (root.has("ai_router_model_v1")) edit.putString(KEY_AI_ROUTER_MODEL, root.getString("ai_router_model_v1"))
        if (root.has("ai_model_id_v1")) edit.putString(KEY_AI_MODEL, root.getString("ai_model_id_v1"))
        if (root.has("ai_multi_model_v1")) edit.putString(KEY_AI_MULTI_MODEL, root.getString("ai_multi_model_v1"))
        if (root.has("ai_modify_model_v1")) edit.putString(KEY_AI_MODIFY_MODEL, root.getString("ai_modify_model_v1"))
        if (root.has("ai_category_refine_model_v1")) edit.putString(KEY_AI_CATEGORY_REFINE_MODEL, root.getString("ai_category_refine_model_v1"))
        if (root.has("ai_rule_model_v1")) edit.putString(KEY_AI_RULE_MODEL, root.getString("ai_rule_model_v1"))
        if (root.has("ai_receipt_model_v1")) edit.putString(KEY_AI_RECEIPT_MODEL, root.getString("ai_receipt_model_v1"))
        if (root.has("ai_receipt_vision_model_v1")) edit.putString(KEY_AI_RECEIPT_VISION_MODEL, root.getString("ai_receipt_vision_model_v1"))
        if (root.has("ai_screen_model_v1")) edit.putString(KEY_AI_SCREEN_MODEL, root.getString("ai_screen_model_v1"))
        if (root.has("ai_receipt_ocr_refine_model_v1")) edit.putString(KEY_AI_RECEIPT_OCR_REFINE_MODEL, root.getString("ai_receipt_ocr_refine_model_v1"))
        if (root.has("ai_speech_model_v1")) edit.putString(KEY_AI_SPEECH_MODEL, root.getString("ai_speech_model_v1"))
        if (root.has("ai_llm_router_enabled_v1")) edit.putBoolean(KEY_AI_LLM_ROUTER_ENABLED, root.getBoolean("ai_llm_router_enabled_v1"))
        val importedVisionModel = firstNonBlank(
            root.optString("ai_vision_model_v1"),
            root.optString("ai_receipt_vision_model_v1"),
            root.optString("ai_screen_model_v1")
        )
        if (importedVisionModel.isNotBlank()) {
            edit.putString(KEY_AI_RECEIPT_VISION_MODEL, importedVisionModel)
            edit.putString(KEY_AI_SCREEN_MODEL, importedVisionModel)
        }
        val importedSpeechModel = firstNonBlank(
            root.optString("ai_online_speech_model_v1"),
            root.optString("ai_speech_model_v1")
        )
        if (importedSpeechModel.isNotBlank()) {
            edit.putString(KEY_AI_SPEECH_MODEL, importedSpeechModel)
        }
        if (root.has("ai_chat_identity_v1")) edit.putString(KEY_AI_CHAT_IDENTITY, root.getString("ai_chat_identity_v1"))
        if (root.has("multi_bill_enabled_v1")) edit.putBoolean(KEY_MULTI_BILL_ENABLED, root.getBoolean("multi_bill_enabled_v1"))
        if (root.has("multi_bill_not_sync_v1")) edit.putBoolean(KEY_MULTI_BILL_NOT_SYNC, root.getBoolean("multi_bill_not_sync_v1"))

        if (root.has("asr_mode_v1")) edit.putInt(KEY_ASR_MODE, root.getInt("asr_mode_v1"))
        if (root.has("asr_download_source_v1")) edit.putString(KEY_ASR_DOWNLOAD_SOURCE, root.getString("asr_download_source_v1"))
        if (root.has("asset_feature_enabled_v1")) edit.putBoolean(KEY_ASSET_FEATURE_ENABLED, root.getBoolean("asset_feature_enabled_v1"))
        if (root.has("ocr_mode_v1")) edit.putInt(KEY_OCR_MODE, root.getInt("ocr_mode_v1"))
        if (root.has("receipt_ocr_refine_enabled_v1")) edit.putBoolean(KEY_RECEIPT_OCR_REFINE_ENABLED, root.getBoolean("receipt_ocr_refine_enabled_v1"))
        if (root.has("receipt_lang_mode_v1")) edit.putInt(KEY_RECEIPT_LANG_MODE, root.getInt("receipt_lang_mode_v1"))
        if (root.has("ai_models_cache_v1")) {
            edit.putStringSet(
                KEY_AI_MODELS_CACHE,
                root.getString("ai_models_cache_v1").replace("\\n", "\n")
                    .lineSequence().filter { it.isNotBlank() }.toSet()
            )
        }
        if (root.has("screen_vision_supported_models_v1")) {
            edit.putStringSet(
                KEY_SCREEN_VISION_SUPPORTED_MODELS,
                root.getString("screen_vision_supported_models_v1").replace("\\n", "\n")
                    .lineSequence().filter { it.isNotBlank() }.toSet()
            )
        }

        if (root.has("shizuku_persistence_v1")) edit.putBoolean(KEY_SHIZUKU_PERSISTENCE, root.getBoolean("shizuku_persistence_v1"))
        if (root.has("shizuku_mode_v1")) edit.putBoolean("advanced_shizuku_mode", root.getBoolean("shizuku_mode_v1"))
        if (root.has("vibrate_feedback_v1")) edit.putBoolean(KEY_VIBRATE_FEEDBACK, root.getBoolean("vibrate_feedback_v1"))
        if (root.has("save_vibrate_v1")) edit.putBoolean(KEY_SAVE_VIBRATE, root.getBoolean("save_vibrate_v1"))
        if (root.has("app_usage_mode_v1")) edit.putInt(KEY_APP_USAGE_MODE, root.getInt("app_usage_mode_v1"))
        if (root.has("first_day_of_week_v1")) edit.putInt("first_day_of_week", root.getInt("first_day_of_week_v1"))
        if (root.has("ai_prompt_correction_v1")) edit.putBoolean("enable_ai_prompt_correction", root.getBoolean("ai_prompt_correction_v1"))
        if (root.has("local_rule_override_v1")) edit.putBoolean("enable_local_rule_override", root.getBoolean("local_rule_override_v1"))
        if (root.has("logging_enabled_v1")) edit.putBoolean(KEY_LOGGING_ENABLED, root.getBoolean("logging_enabled_v1"))
        if (root.has("book_accounts_v1")) edit.putString("book_accounts_v1", root.getString("book_accounts_v1"))
        if (root.has("collapsed_book_accounts_v1")) edit.putString("collapsed_book_accounts_v1", root.getString("collapsed_book_accounts_v1"))
        if (root.has("selected_book_v1")) edit.putString("selected_book_name_v1", root.getString("selected_book_v1"))
        if (root.has("default_book_v1")) edit.putString("default_book_name_v1", root.getString("default_book_v1"))

        importBooleanPreferenceMap(root, "home_budget_summary_v1", KEY_HOME_BUDGET_SUMMARY_PREFIX, edit)
        importBooleanPreferenceMap(root, "stats_budget_mode_v1", KEY_STATS_BUDGET_MODE_PREFIX, edit)

        check(edit.commit()) { "无法恢复应用设置" }

        if (root.has("ai_provider_keys_v1")) {
            Prefs.importAiProviderKeysFromBackup(ctx, root.getString("ai_provider_keys_v1"))
        }

        if (root.has("book_colors_v1")) {
            runCatching {
                val colorsObj = JSONObject(root.getString("book_colors_v1"))
                val colorEdit = prefs(ctx).edit()
                val keys = colorsObj.keys()
                while (keys.hasNext()) {
                    val bookName = keys.next()
                    colorEdit.putInt("book_color_$bookName", colorsObj.getInt(bookName))
                }
                check(colorEdit.commit()) { "无法恢复账本颜色" }
            }
        }

        if (root.has("book_banners_v1")) {
            runCatching {
                val bannersObj = JSONObject(root.getString("book_banners_v1"))
                val bannerEdit = prefs(ctx).edit()
                val keys = bannersObj.keys()
                while (keys.hasNext()) {
                    val bookName = keys.next()
                    bannerEdit.putString("book_banner_$bookName", bannersObj.getString(bookName))
                }
                check(bannerEdit.commit()) { "无法恢复账本封面设置" }
            }
        }

        if (root.has("cm_rates_json_v1")) currencyEdit.putString("currency_rates_json", root.getString("cm_rates_json_v1"))
        if (root.has("cm_rates_update_time_v1")) currencyEdit.putLong("currency_rates_update_time", root.getLong("cm_rates_update_time_v1"))
        if (root.has("cm_refresh_interval_min_v1")) currencyEdit.putInt("currency_refresh_interval_min", root.getInt("cm_refresh_interval_min_v1"))
        check(currencyEdit.commit()) { "无法恢复汇率设置" }

        if (root.has("cloud_webdav_url_v1")) cloudEdit.putString(KEY_CLOUD_WEBDAV_URL, root.getString("cloud_webdav_url_v1"))
        if (root.has("cloud_webdav_user_v1")) cloudEdit.putString(KEY_CLOUD_WEBDAV_USER, root.getString("cloud_webdav_user_v1"))
        if (root.has("cloud_webdav_pass_v1")) cloudEdit.putString(KEY_CLOUD_WEBDAV_PASS, root.getString("cloud_webdav_pass_v1"))
        if (root.has("cloud_webdav_dir_v1")) cloudEdit.putString(KEY_CLOUD_WEBDAV_DIR, root.getString("cloud_webdav_dir_v1"))
        if (root.has("cloud_device_name_v1")) cloudEdit.putString(KEY_CLOUD_DEVICE_NAME, root.getString("cloud_device_name_v1"))
        check(cloudEdit.commit()) { "无法恢复云备份设置" }

        if (root.has("ai_chat_session_titles_v1")) {
            runCatching {
                val titleObj = JSONObject(root.getString("ai_chat_session_titles_v1"))
                val titleEdit = prefs(ctx).edit()
                val keys = titleObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    titleEdit.putString(key, titleObj.getString(key))
                }
                check(titleEdit.commit()) { "无法恢复聊天会话标题" }
            }
        }
    }

    fun serializeSettings(ctx: Context): String {
        val currencyPrefs = ctx.getSharedPreferences("flip_currency_prefs", Context.MODE_PRIVATE)
        val cloudPrefs = cloudPrefs(ctx)
        return JSONObject().apply {
            put("quick_gesture_enabled_v1", Prefs.isQuickGestureEnabled(ctx))
            put("hide_recents_v1", Prefs.isHideRecents(ctx))
            put("double_tap_enabled_v1", Prefs.isDoubleTapEnabled(ctx))
            put("tap_model_v1", Prefs.getTapModel(ctx))
            put("tap_sensitivity_level_v1", Prefs.getTapSensitivityLevel(ctx))
            put("tap_he_sensitivity_v1", Prefs.getTapHeSensitivityLevel(ctx))
            put("tap_he_test_mode_v1", Prefs.isTapHeTestModeEnabled(ctx))
            put("tap_nnapi_low_power_v1", Prefs.isTapNnapiLowPower(ctx))
            put("tap_power_saving_v1", Prefs.isTapPowerSavingEnabled(ctx))
            put("tap_force_full_ml_v1", Prefs.isTapForceFullMl(ctx))
            put("tap_triple_enabled_v1", Prefs.isTapTripleEnabled(ctx))
            put("tap_action_double_v1", Prefs.getTapActionDouble(ctx))
            put("tap_action_triple_v1", Prefs.getTapActionTriple(ctx))
            put("flip_enabled_v1", Prefs.isFlipEnabled(ctx))
            put("flip_sensitivity_v1", Prefs.getFlipSensitivity(ctx))
            put("flip_action_v1", Prefs.getFlipAction(ctx))
            put("flip_disable_landscape_v1", Prefs.isDisableLandscape(ctx))
            put("vibrate_feedback_v1", Prefs.isVibrateFeedbackEnabled(ctx))
            put("save_vibrate_v1", Prefs.isSaveVibrateEnabled(ctx))
            put("shizuku_persistence_v1", Prefs.isShizukuPersistenceEnabled(ctx))
            put("shizuku_mode_v1", Prefs.isShizukuModeEnabled(ctx))
            put("app_usage_mode_v1", Prefs.getAppUsageMode(ctx))
            put("first_day_of_week_v1", prefs(ctx).getInt("first_day_of_week", Calendar.MONDAY))
            put("asset_feature_enabled_v1", Prefs.isAssetFeatureEnabled(ctx))

            put("app_white_list_v1", Prefs.serializeWhiteList(Prefs.getAppWhiteList(ctx)))
            put("active_currencies_v1", Prefs.getActiveCurrencies(ctx).joinToString(","))
            put("exchange_refresh_interval_v1", Prefs.getExchangeRefreshInterval(ctx))
            put("recurring_auto_detect_enabled_v1", Prefs.isRecurringAutoDetectEnabled(ctx))
            put("recurring_detect_amount_tolerance_v1", Prefs.getRecurringDetectAmountTolerance(ctx))
            put("cm_enabled_currencies_v1", com.taostudio.tapaccounting.logic.CurrencyManager.getEnabledCurrencies(ctx).joinToString(","))
            put("cm_rates_json_v1", currencyPrefs.getString("currency_rates_json", "") ?: "")
            put("cm_rates_update_time_v1", currencyPrefs.getLong("currency_rates_update_time", 0L))
            put("cm_refresh_interval_min_v1", currencyPrefs.getInt("currency_refresh_interval_min", 60))

            put("show_ai_text_v1", Prefs.isShowAiText(ctx))
            put("show_ai_voice_v1", Prefs.isShowAiVoice(ctx))
            put("show_ai_image_v1", Prefs.isShowAiImage(ctx))
            put("show_screen_accounting_v1", Prefs.isShowScreenAccounting(ctx))
            put("show_multi_cur_v1", Prefs.isShowMultiCurrency(ctx))
            put("show_home_trend_card_v1", Prefs.isShowHomeTrendCard(ctx))
            put("show_book_entry_v1", Prefs.isShowBookEntry(ctx))
            put("show_ai_chat_entry_v1", Prefs.isShowAiChatEntry(ctx))
            put("amount_grouping_v1", Prefs.isAmountGroupingEnabled(ctx))
            put("bill_show_category_icon_v1", Prefs.isShowBillCategoryIcon(ctx))
            put("bill_show_full_category_v1", Prefs.isShowBillFullCategory(ctx))
            put("bill_remark_priority_v1", Prefs.isBillRemarkPriority(ctx))
            put("insight_cards_enabled_v1", Prefs.isInsightCardsEnabled(ctx))
            put("independent_detail_enabled_v1", Prefs.isIndependentDetailEnabled(ctx))
            put("chat_page_mode_v1", Prefs.getChatPageMode(ctx))
            put("asset_amount_display_mode_v1", Prefs.getAssetAmountDisplayMode(ctx))

            put("multi_bill_enabled_v1", Prefs.isMultiBillEnabled(ctx))
            put("multi_bill_not_sync_v1", Prefs.isMultiBillNotSync(ctx))

            put("asr_mode_v1", Prefs.getAsrMode(ctx))
            put("asr_download_source_v1", Prefs.getAsrDownloadSource(ctx))
            put("ocr_mode_v1", Prefs.getOcrMode(ctx))
            put("receipt_lang_mode_v1", Prefs.getReceiptLangMode(ctx))
            put("receipt_ocr_refine_enabled_v1", Prefs.isReceiptOcrRefineEnabled(ctx))

            put("ai_api_key_v1", Prefs.getAiKey(ctx))
            put("ai_api_url_v1", Prefs.getAiUrl(ctx))
            put("ai_provider_v1", Prefs.getAiProvider(ctx))
            put("ai_provider_keys_v1", Prefs.exportAiProviderKeysJson(ctx))
            val textModel = Prefs.getAiMultiModel(ctx)
            val visionModel = Prefs.getAiReceiptVisionModel(ctx)
            val speechModel = Prefs.getAiSpeechModel(ctx)
            put("ai_text_model_v1", textModel)
            put("ai_model_id_v1", Prefs.getAiModel(ctx))
            put("ai_multi_model_v1", Prefs.getAiMultiModel(ctx))
            put("ai_modify_model_v1", Prefs.getAiModifyModel(ctx))
            put("ai_category_refine_model_v1", Prefs.getAiCategoryRefineModel(ctx))
            put("ai_router_model_v1", Prefs.getAiRouterModel(ctx))
            put("ai_rule_model_v1", Prefs.getAiRuleModel(ctx))
            put("ai_receipt_model_v1", Prefs.getAiReceiptModel(ctx))
            put("ai_receipt_vision_model_v1", Prefs.getAiReceiptVisionModel(ctx))
            put("ai_screen_model_v1", Prefs.getAiScreenModel(ctx))
            put("ai_receipt_ocr_refine_model_v1", Prefs.getAiReceiptOcrRefineModel(ctx))
            put("ai_speech_model_v1", Prefs.getAiSpeechModel(ctx))
            put("ai_vision_model_v1", visionModel)
            put("ai_online_speech_model_v1", speechModel)
            put("ai_llm_router_enabled_v1", Prefs.isAiLlmRouterEnabled(ctx))
            put("screen_vision_supported_models_v1", (prefs(ctx).getStringSet(KEY_SCREEN_VISION_SUPPORTED_MODELS, emptySet()) ?: emptySet()).joinToString("\\n"))
            put("ai_models_cache_v1", Prefs.getAiModelsCache(ctx).joinToString("\\n"))

            put("ai_prompt_correction_v1", Prefs.isAiPromptCorrectionEnabled(ctx))
            put("local_rule_override_v1", Prefs.isLocalRuleOverrideEnabled(ctx))
            put("logging_enabled_v1", Prefs.isLoggingEnabled(ctx))
            put("ai_thinking_modify_bill_v1", Prefs.isAiThinkingModifyBillEnabled(ctx))
            put("ai_thinking_category_refine_v1", Prefs.isAiThinkingCategoryRefineEnabled(ctx))
            put("ai_enable_thinking_v1", Prefs.isAiThinkingEnabled(ctx))
            put("ai_thinking_multi_bill_v1", Prefs.isAiThinkingMultiBillEnabled(ctx))
            put("ai_thinking_vision_v1", Prefs.isAiThinkingVisionEnabled(ctx))
            put("ai_manual_model_selection_v1", Prefs.isAiManualModelSelectionEnabled(ctx))
            put("receipt_image_draft_confirm_v1", Prefs.isReceiptImageDraftConfirmEnabled(ctx))
            put("image_accounting_natural_language_v1", Prefs.isImageAccountingNaturalLanguage(ctx))
            put("screen_accounting_use_image_flow_v1", Prefs.isScreenAccountingUseImageFlow(ctx))

            val bookAccounts = BookAccountManager.getBookAccounts(ctx)
            put("book_accounts_v1", BookAccountManager.serializeBookAccounts(ctx))
            put("collapsed_book_accounts_v1", BookAccountManager.serializeCollapsedBookAccounts(ctx))
            put("selected_book_v1", BookAccountManager.getSelectedBook(ctx))
            put("default_book_v1", BookAccountManager.getDefaultBook(ctx))

            val bookColorsObj = JSONObject()
            val bookBannersObj = JSONObject()
            for (book in bookAccounts) {
                val norm = BookAccountManager.normalizeBookName(book)
                val colorVal = prefs(ctx).getInt("book_color_$norm", Int.MIN_VALUE)
                if (colorVal != Int.MIN_VALUE) bookColorsObj.put(norm, colorVal)
                val bannerPath = BookAccountManager.getBookBannerPath(ctx, norm)
                if (!bannerPath.isNullOrEmpty()) bookBannersObj.put(norm, bannerPath)
            }
            put("book_colors_v1", bookColorsObj.toString())
            put("book_banners_v1", bookBannersObj.toString())
            put("home_budget_summary_v1", serializeBooleanPrefsWithPrefix(ctx, KEY_HOME_BUDGET_SUMMARY_PREFIX))
            put("stats_budget_mode_v1", serializeBooleanPrefsWithPrefix(ctx, KEY_STATS_BUDGET_MODE_PREFIX))
            put("ai_entry_mode_v1", Prefs.getAiEntryMode(ctx))
            put("ai_chat_name_v1", Prefs.getAiChatName(ctx))
            put("ai_chat_identity_v1", Prefs.getAiChatIdentity(ctx))
            put("user_chat_name_v1", Prefs.getUserChatName(ctx))
            put("user_profile_desc_v1", Prefs.getUserProfileDesc(ctx))
            put("ai_chat_avatar_path_v1", Prefs.getAiChatAvatarPath(ctx))
            put("user_chat_avatar_path_v1", Prefs.getUserChatAvatarPath(ctx))
            put("ai_chat_bg_path_v1", Prefs.getAiChatBgPath(ctx))
            // Preserve an empty value: it means "follow the main model".
            put("ai_chat_model_v1", prefs(ctx).getString(KEY_AI_CHAT_MODEL, "") ?: "")
            put("ai_chat_reply_style_v1", Prefs.getAiChatReplyStyle(ctx))
            put("ai_chat_reply_style_custom_v1", Prefs.getAiChatReplyStyleCustomPrompt(ctx))
            put("ai_chat_model_audio_support_v1", prefs(ctx).getString(KEY_AI_CHAT_MODEL_AUDIO_SUPPORT, "") ?: "")
            put("ai_chat_session_titles_v1", serializeChatSessionTitles(ctx).toString())
            put("cloud_webdav_url_v1", cloudPrefs.getString(KEY_CLOUD_WEBDAV_URL, "") ?: "")
            put("cloud_webdav_user_v1", cloudPrefs.getString(KEY_CLOUD_WEBDAV_USER, "") ?: "")
            put("cloud_webdav_pass_v1", cloudPrefs.getString(KEY_CLOUD_WEBDAV_PASS, "") ?: "")
            put("cloud_webdav_dir_v1", cloudPrefs.getString(KEY_CLOUD_WEBDAV_DIR, "") ?: "")
            put("cloud_device_name_v1", cloudPrefs.getString(KEY_CLOUD_DEVICE_NAME, "") ?: "")
        }.toString()
    }

    fun serializeSettingsModules(ctx: Context): Map<String, String> {
        val full = JSONObject(serializeSettings(ctx))
        return linkedMapOf(
            "settings_general_basic" to filterSettingsModule(full, "quick_gesture_enabled_v1", "hide_recents_v1", "app_usage_mode_v1", "first_day_of_week_v1", "app_white_list_v1", "double_tap_enabled_v1", "tap_model_v1", "tap_sensitivity_level_v1", "tap_he_sensitivity_v1", "tap_he_test_mode_v1", "tap_nnapi_low_power_v1", "tap_power_saving_v1", "tap_force_full_ml_v1", "tap_triple_enabled_v1", "tap_action_double_v1", "tap_action_triple_v1", "flip_enabled_v1", "flip_sensitivity_v1", "flip_action_v1", "flip_disable_landscape_v1", "recurring_auto_detect_enabled_v1", "recurring_detect_amount_tolerance_v1"),
            "settings_general_assets" to filterSettingsModule(full, "asset_feature_enabled_v1", "active_currencies_v1", "exchange_refresh_interval_v1", "cm_enabled_currencies_v1", "cm_rates_json_v1", "cm_rates_update_time_v1", "cm_refresh_interval_min_v1"),
            // These modules enter only the whole-archive encrypted backup format.
            "settings_general_cloud" to filterSettingsModule(full, "cloud_webdav_url_v1", "cloud_webdav_user_v1", "cloud_webdav_pass_v1", "cloud_webdav_dir_v1", "cloud_device_name_v1"),
            "settings_display_entries" to filterSettingsModule(full, "show_ai_text_v1", "show_ai_voice_v1", "show_ai_image_v1", "show_screen_accounting_v1", "show_multi_cur_v1", "show_home_trend_card_v1", "show_book_entry_v1", "show_ai_chat_entry_v1", "insight_cards_enabled_v1", "independent_detail_enabled_v1", "chat_page_mode_v1", "asset_amount_display_mode_v1", "home_budget_summary_v1", "stats_budget_mode_v1"),
            "settings_display_bills" to filterSettingsModule(full, "amount_grouping_v1", "bill_show_category_icon_v1", "bill_show_full_category_v1", "bill_remark_priority_v1"),
            "settings_display_multibill" to filterSettingsModule(full, "multi_bill_enabled_v1", "multi_bill_not_sync_v1"),
        "settings_ai_core" to filterSettingsModule(full, "ai_api_key_v1", "ai_provider_keys_v1", "ai_api_url_v1", "ai_provider_v1", "ai_text_model_v1", "ai_model_id_v1", "ai_multi_model_v1", "ai_modify_model_v1", "ai_category_refine_model_v1", "ai_router_model_v1", "ai_rule_model_v1", "ai_receipt_model_v1", "ai_receipt_vision_model_v1", "ai_screen_model_v1", "ai_receipt_ocr_refine_model_v1", "ai_speech_model_v1", "ai_vision_model_v1", "ai_online_speech_model_v1", "ai_llm_router_enabled_v1", "screen_vision_supported_models_v1", "ai_models_cache_v1", "asr_mode_v1", "asr_download_source_v1", "ocr_mode_v1", "receipt_lang_mode_v1", "receipt_ocr_refine_enabled_v1", "ai_prompt_correction_v1", "local_rule_override_v1", "ai_thinking_modify_bill_v1", "ai_thinking_category_refine_v1", "ai_enable_thinking_v1", "ai_thinking_multi_bill_v1", "ai_thinking_vision_v1", "ai_manual_model_selection_v1", "receipt_image_draft_confirm_v1", "image_accounting_natural_language_v1", "screen_accounting_use_image_flow_v1"),
            "settings_ai_chat" to filterSettingsModule(full, "ai_entry_mode_v1", "ai_chat_name_v1", "ai_chat_identity_v1", "user_chat_name_v1", "user_profile_desc_v1", "ai_chat_avatar_path_v1", "user_chat_avatar_path_v1", "ai_chat_bg_path_v1", "ai_chat_model_v1", "ai_chat_reply_style_v1", "ai_chat_reply_style_custom_v1", "ai_chat_model_audio_support_v1", "ai_chat_session_titles_v1"),
            "settings_books" to filterSettingsModule(full, "book_accounts_v1", "collapsed_book_accounts_v1", "selected_book_v1", "default_book_v1", "book_colors_v1", "book_banners_v1"),
            "settings_advanced_runtime" to filterSettingsModule(full, "vibrate_feedback_v1", "save_vibrate_v1", "shizuku_persistence_v1", "shizuku_mode_v1", "logging_enabled_v1")
        )
    }

    private fun filterSettingsModule(root: JSONObject, vararg keys: String): String {
        val result = JSONObject()
        keys.forEach { key -> if (root.has(key)) result.put(key, root.get(key)) }
        return result.toString()
    }

    private fun serializeBooleanPrefsWithPrefix(ctx: Context, prefix: String): JSONObject {
        val result = JSONObject()
        prefs(ctx).all.forEach { (key, value) ->
            if (key.startsWith(prefix) && value is Boolean) {
                result.put(key.removePrefix(prefix), value)
            }
        }
        return result
    }

    private fun importBooleanPreferenceMap(
        root: JSONObject,
        backupKey: String,
        preferencePrefix: String,
        editor: android.content.SharedPreferences.Editor
    ) {
        val values = root.optJSONObject(backupKey) ?: return
        val keys = values.keys()
        while (keys.hasNext()) {
            val suffix = keys.next()
            editor.putBoolean(preferencePrefix + suffix, values.optBoolean(suffix))
        }
    }

    private fun serializeChatSessionTitles(ctx: Context): JSONObject {
        val all = prefs(ctx).all
        val result = JSONObject()
        all.forEach { (key, value) ->
            if (key.startsWith("ai_chat_session_title_") && value is String) {
                result.put(key, value)
            }
        }
        return result
    }
}
