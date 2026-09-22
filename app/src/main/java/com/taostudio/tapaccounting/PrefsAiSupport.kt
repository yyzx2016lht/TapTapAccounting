package com.taostudio.tapaccounting

import android.content.Context
import android.content.SharedPreferences
import com.taostudio.tapaccounting.data.crypto.KeystoreSecretBox
import com.taostudio.tapaccounting.data.crypto.SecretEnvelope
import org.json.JSONArray
import org.json.JSONObject

object PrefsAiSupport {
    private const val PREFS_NAME = "flip_prefs"
    private const val KEY_AI_KEY = "ai_api_key"
    private const val KEY_AI_KEY_ENC = "ai_api_key_enc"
    private const val KEY_AI_PROVIDER_KEYS = "ai_provider_keys_v1"
    private const val KEY_AI_PROVIDER_KEYS_ENC = "ai_provider_keys_enc_v1"
    private const val KEY_AI_PROVIDER_KEYS_MIGRATED = "ai_provider_keys_migrated_v1"
    private const val AI_SECRET_ALIAS = "flip_ai_secrets"
    // Legacy text-model key kept for backward compatibility.
    private const val KEY_AI_MODEL = "ai_model_id"
    private const val KEY_AI_MULTI_MODEL = "ai_multi_model_id"
    private const val KEY_AI_MODIFY_MODEL = "ai_modify_model_id"
    private const val KEY_AI_CATEGORY_REFINE_MODEL = "ai_category_refine_model_id"
    private const val KEY_AI_ROUTER_MODEL = "ai_router_model_id"
    private const val KEY_AI_LLM_ROUTER_ENABLED = "ai_llm_router_enabled"
    private const val KEY_AI_RULE_MODEL = "ai_rule_model_id"
    private const val KEY_AI_MODELS_CACHE = "ai_models_cache"
    private const val KEY_AI_PROVIDER = "ai_provider"
    private const val KEY_AI_URL = "ai_api_url"
    private const val KEY_AI_RECEIPT_MODEL = "ai_receipt_model_id"
    private const val KEY_AI_RECEIPT_VISION_MODEL = "ai_receipt_vision_model_id"
    private const val KEY_AI_SCREEN_MODEL = "ai_screen_model_id"
    private const val KEY_SCREEN_VISION_SUPPORTED_MODELS = "screen_vision_supported_models"
    private const val KEY_RECEIPT_OCR_REFINE_ENABLED = "receipt_ocr_refine_enabled"
    private const val KEY_RECEIPT_IMAGE_DRAFT_CONFIRM = "receipt_image_draft_confirm_enabled"
    private const val KEY_IMAGE_ACCOUNTING_NATURAL_LANGUAGE = "image_accounting_natural_language"
    private const val KEY_SCREEN_ACCOUNTING_USE_IMAGE_FLOW = "screen_accounting_use_image_flow"
    private const val KEY_AI_RECEIPT_OCR_REFINE_MODEL = "ai_receipt_ocr_refine_model_id"
    private const val KEY_AI_SPEECH_MODEL = "ai_speech_model_id"
    private const val KEY_AI_MANUAL_MODEL_SELECTION = "ai_manual_model_selection_v1"
    private const val KEY_AI_ENABLE_THINKING = "ai_enable_thinking"
    private const val KEY_AI_THINKING_MULTI_BILL = "ai_thinking_multi_bill"
    private const val KEY_AI_THINKING_MODIFY_BILL = "ai_thinking_modify_bill"
    private const val KEY_AI_THINKING_VISION = "ai_thinking_vision"
    private const val KEY_AI_THINKING_CATEGORY_REFINE = "ai_thinking_category_refine"
    private const val KEY_AI_RULES = "ai_rules_v1"
    private const val KEY_OCR_DEBUG_RECORDS = "ocr_debug_records_v1"
    private const val OCR_DEBUG_MAX_RECORDS = 20
    private const val OCR_DEBUG_MAX_TEXT_LEN = 12000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val DEFAULT_MAIN_MODEL = "Qwen/Qwen3-14B"
    private const val DEFAULT_ROUTER_MODEL = "Qwen/Qwen3-8B"
    private const val DEFAULT_VISION_MODEL = "Qwen/Qwen3-VL-30B-A3B-Instruct"
    private const val DEFAULT_SPEECH_MODEL = "FunAudioLLM/SenseVoiceSmall"

    fun getAiKey(ctx: Context): String {
        migrateLegacyProviderKeysIfNeeded(ctx)
        return readSecret(prefs(ctx), KEY_AI_KEY, KEY_AI_KEY_ENC).trim()
    }

    fun getAiProviderKey(ctx: Context, providerId: String): String {
        val id = providerId.trim()
        if (id.isBlank()) return ""
        return readProviderKeysMap(ctx)[id].orEmpty()
    }

    fun setAiKey(ctx: Context, key: String) {
        setAiProviderKey(ctx, getAiProvider(ctx), key)
    }

    fun exportAiProviderKeysJson(ctx: Context): String {
        migrateLegacyProviderKeysIfNeeded(ctx)
        return readSecret(prefs(ctx), KEY_AI_PROVIDER_KEYS, KEY_AI_PROVIDER_KEYS_ENC)
    }

    fun importAiProviderKeysFromBackup(ctx: Context, json: String) {
        val map = decodeProviderKeys(json)
        val editor = prefs(ctx).edit()
            .putBoolean(KEY_AI_PROVIDER_KEYS_MIGRATED, true)
        writeSecret(editor, KEY_AI_PROVIDER_KEYS, KEY_AI_PROVIDER_KEYS_ENC, encodeProviderKeys(map))
        val activeKey = map[getAiProvider(ctx)].orEmpty()
        writeSecret(editor, KEY_AI_KEY, KEY_AI_KEY_ENC, activeKey)
        editor.commit()
    }

    fun setAiProviderKey(ctx: Context, providerId: String, apiKey: String) {
        val id = providerId.trim()
        if (id.isBlank()) return
        val trimmed = apiKey.trim()
        val map = readProviderKeysMap(ctx).toMutableMap()
        if (trimmed.isEmpty()) {
            map.remove(id)
        } else {
            map[id] = trimmed
        }
        val editor = prefs(ctx).edit()
        writeSecret(editor, KEY_AI_PROVIDER_KEYS, KEY_AI_PROVIDER_KEYS_ENC, encodeProviderKeys(map))
        if (getAiProvider(ctx) == id) {
            writeSecret(editor, KEY_AI_KEY, KEY_AI_KEY_ENC, trimmed)
        }
        editor.commit()
    }

    /** Writes a secret under [plainKey]/[encKey]; empty clears both. Always encrypts at rest. */
    internal fun writeSecret(
        editor: SharedPreferences.Editor,
        plainKey: String,
        encKey: String,
        value: String
    ) {
        if (value.isEmpty()) {
            editor.remove(plainKey).remove(encKey)
            return
        }
        editor.putString(encKey, KeystoreSecretBox.encrypt(AI_SECRET_ALIAS, value)).remove(plainKey)
    }

    /**
     * Reads a secret, migrating leftover plaintext under [plainKey] to ciphertext.
     * Never returns the legacy slot after a successful encrypt.
     */
    internal fun readSecret(sp: SharedPreferences, plainKey: String, encKey: String): String {
        val encrypted = sp.getString(encKey, null)
        if (SecretEnvelope.isEncrypted(encrypted)) {
            val decrypted = KeystoreSecretBox.decrypt(AI_SECRET_ALIAS, encrypted!!)
            if (decrypted != null) return decrypted
        }
        val legacy = sp.getString(plainKey, null)
        if (legacy.isNullOrEmpty()) return ""
        runCatching {
            sp.edit().putString(encKey, KeystoreSecretBox.encrypt(AI_SECRET_ALIAS, legacy))
                .remove(plainKey).commit()
        }
        return legacy
    }

    private fun migrateLegacyProviderKeysIfNeeded(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_AI_PROVIDER_KEYS_MIGRATED, false)) {
            // Still migrate any leftover plaintext secrets.
            readSecret(p, KEY_AI_KEY, KEY_AI_KEY_ENC)
            readSecret(p, KEY_AI_PROVIDER_KEYS, KEY_AI_PROVIDER_KEYS_ENC)
            return
        }
        val map = readProviderKeysMapRaw(ctx).toMutableMap()
        val legacyKey = readSecret(p, KEY_AI_KEY, KEY_AI_KEY_ENC).trim()
        if (legacyKey.isNotEmpty()) {
            map.putIfAbsent(getAiProvider(ctx), legacyKey)
        }
        if (map.isNotEmpty()) {
            val editor = p.edit()
            writeSecret(editor, KEY_AI_PROVIDER_KEYS, KEY_AI_PROVIDER_KEYS_ENC, encodeProviderKeys(map))
            editor.apply()
        }
        p.edit().putBoolean(KEY_AI_PROVIDER_KEYS_MIGRATED, true).apply()
    }

    private fun readProviderKeysMap(ctx: Context): Map<String, String> {
        migrateLegacyProviderKeysIfNeeded(ctx)
        return readProviderKeysMapRaw(ctx)
    }

    private fun readProviderKeysMapRaw(ctx: Context): Map<String, String> =
        decodeProviderKeys(
            readSecret(prefs(ctx), KEY_AI_PROVIDER_KEYS, KEY_AI_PROVIDER_KEYS_ENC).ifEmpty { null }
        )

    private fun encodeProviderKeys(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (providerId, apiKey) ->
            if (providerId.isNotBlank() && apiKey.isNotBlank()) {
                obj.put(providerId, apiKey)
            }
        }
        return obj.toString()
    }

    private fun decodeProviderKeys(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val key = obj.optString(id, "").trim()
                    if (key.isNotEmpty()) put(id, key)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun getAiModel(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_MODEL, DEFAULT_MAIN_MODEL) ?: DEFAULT_MAIN_MODEL
    fun setAiModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_MODEL, value).apply()

    fun getAiMultiModel(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_MULTI_MODEL, "") ?: getAiModel(ctx)
    fun setAiMultiModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_MULTI_MODEL, value).apply()

    fun getAiModifyModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_MODIFY_MODEL, "") ?: "").ifBlank { getAiMultiModel(ctx) }
    fun setAiModifyModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_MODIFY_MODEL, value).apply()

    fun getAiCategoryRefineModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_CATEGORY_REFINE_MODEL, "") ?: "").ifBlank { getAiMultiModel(ctx) }
    fun setAiCategoryRefineModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_CATEGORY_REFINE_MODEL, value).apply()

    // Hidden legacy field. Current product flow no longer exposes router configuration.
    fun getAiRouterModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_ROUTER_MODEL, "") ?: "").ifBlank { getAiMultiModel(ctx) }
    fun setAiRouterModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_ROUTER_MODEL, value).apply()
    fun isAiLlmRouterEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_LLM_ROUTER_ENABLED, false)
    fun setAiLlmRouterEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_LLM_ROUTER_ENABLED, enabled).apply()

    fun getAiRuleModel(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_RULE_MODEL, "") ?: getAiModel(ctx)
    fun setAiRuleModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_RULE_MODEL, value).apply()

    fun getAiReceiptModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_RECEIPT_MODEL, "") ?: "").ifBlank { getAiModel(ctx) }
    fun setAiReceiptModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_RECEIPT_MODEL, value).apply()

    fun getAiReceiptVisionModel(ctx: Context): String {
        val saved = (prefs(ctx).getString(KEY_AI_RECEIPT_VISION_MODEL, "") ?: "").trim()
        if (saved.isNotEmpty()) return saved
        val preset = AiProviderRegistry.presetFor(getAiProvider(ctx))
        return if (preset?.supportsVision == true) {
            preset.defaultVisionModel.ifBlank { DEFAULT_VISION_MODEL }
        } else {
            ""
        }
    }
    fun setAiReceiptVisionModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_RECEIPT_VISION_MODEL, value).apply()

    // Legacy compatibility field. Current product flow follows the vision model.
    fun getAiScreenModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_SCREEN_MODEL, "") ?: "").ifBlank { getAiReceiptVisionModel(ctx) }
    fun setAiScreenModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_SCREEN_MODEL, value).apply()

    fun isScreenModelVisionSupported(ctx: Context, model: String): Boolean {
        val normalized = model.trim()
        if (normalized.isEmpty()) return false
        return prefs(ctx).getStringSet(KEY_SCREEN_VISION_SUPPORTED_MODELS, emptySet())
            ?.contains(normalized) == true
    }

    fun setScreenModelVisionSupported(ctx: Context, model: String, supported: Boolean) {
        val normalized = model.trim()
        if (normalized.isEmpty()) return
        val set = (prefs(ctx).getStringSet(KEY_SCREEN_VISION_SUPPORTED_MODELS, emptySet()) ?: emptySet())
            .toMutableSet()
        if (supported) set.add(normalized) else set.remove(normalized)
        prefs(ctx).edit().putStringSet(KEY_SCREEN_VISION_SUPPORTED_MODELS, set).apply()
    }

    fun getAiReceiptOcrRefineModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_RECEIPT_OCR_REFINE_MODEL, "") ?: "").ifBlank { getAiReceiptModel(ctx) }
    fun setAiReceiptOcrRefineModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_RECEIPT_OCR_REFINE_MODEL, value).apply()

    fun getAiSpeechModel(ctx: Context): String {
        val saved = (prefs(ctx).getString(KEY_AI_SPEECH_MODEL, "") ?: "").trim()
        if (saved.isNotEmpty()) return saved
        val preset = AiProviderRegistry.presetFor(getAiProvider(ctx))
        return if (preset?.supportsCloudSpeech == true) {
            preset.defaultSpeechModel.ifBlank { DEFAULT_SPEECH_MODEL }
        } else {
            ""
        }
    }
    fun setAiSpeechModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_SPEECH_MODEL, value).apply()

    fun getAiEnableThinking(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_ENABLE_THINKING, false)
    fun setAiEnableThinking(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_ENABLE_THINKING, enabled).apply()

    fun isAiThinkingMultiBillEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_THINKING_MULTI_BILL, false)
    fun setAiThinkingMultiBillEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_THINKING_MULTI_BILL, enabled).apply()

    fun isAiThinkingModifyBillEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_THINKING_MODIFY_BILL, false)
    fun setAiThinkingModifyBillEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_THINKING_MODIFY_BILL, enabled).apply()

    fun isAiThinkingVisionEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_THINKING_VISION, false)
    fun setAiThinkingVisionEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_THINKING_VISION, enabled).apply()

    fun isAiThinkingCategoryRefineEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_THINKING_CATEGORY_REFINE, false)
    fun setAiThinkingCategoryRefineEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_THINKING_CATEGORY_REFINE, enabled).apply()

    fun getAiModelsCache(ctx: Context): List<String> =
        (prefs(ctx).getStringSet(KEY_AI_MODELS_CACHE, null) ?: emptySet()).toList()
    fun setAiModelsCache(ctx: Context, models: List<String>) =
        prefs(ctx).edit().putStringSet(KEY_AI_MODELS_CACHE, models.toSet()).apply()

    /** Atomically persist provider switch so UI can read updated values immediately. */
    fun applyAiProviderConfigSync(
        ctx: Context,
        preset: AiProviderPreset,
        apiKey: String,
        modelsCache: List<String>? = null
    ) {
        val textModel = preset.defaultTextModel
        val visionModel = if (preset.supportsVision && preset.defaultVisionModel.isNotBlank()) {
            preset.defaultVisionModel
        } else {
            ""
        }
        val speechModel = if (preset.supportsCloudSpeech && preset.defaultSpeechModel.isNotBlank()) {
            preset.defaultSpeechModel
        } else {
            ""
        }
        val providerId = preset.id.trim().ifBlank { AiProviderRegistry.PROVIDER_SILICONFLOW }
        val providerKeys = readProviderKeysMap(ctx).toMutableMap()
        providerKeys[providerId] = apiKey.trim()
        val editor = prefs(ctx).edit()
            .putString(KEY_AI_PROVIDER, providerId)
            .putString(KEY_AI_URL, preset.baseUrl)
        writeSecret(editor, KEY_AI_KEY, KEY_AI_KEY_ENC, apiKey.trim())
        writeSecret(editor, KEY_AI_PROVIDER_KEYS, KEY_AI_PROVIDER_KEYS_ENC, encodeProviderKeys(providerKeys))
        editor
            .putString(KEY_AI_MODEL, textModel)
            .putString(KEY_AI_MULTI_MODEL, textModel)
            .putString(KEY_AI_MODIFY_MODEL, textModel)
            .putString(KEY_AI_CATEGORY_REFINE_MODEL, textModel)
            .putString(KEY_AI_RULE_MODEL, textModel)
            .putString(KEY_AI_RECEIPT_MODEL, textModel)
            .putString(KEY_AI_RECEIPT_OCR_REFINE_MODEL, textModel)
            .putString(KEY_AI_ROUTER_MODEL, textModel)
            .putString(KEY_AI_RECEIPT_VISION_MODEL, visionModel)
            .putString(KEY_AI_SCREEN_MODEL, visionModel)
            .putString(KEY_AI_SPEECH_MODEL, speechModel)
        editor.putStringSet(KEY_AI_MODELS_CACHE, modelsCache.orEmpty().toSet())
        editor.commit()
    }

    fun defaultSpeechModelForUrl(rawBaseUrl: String): String {
        val baseUrl = rawBaseUrl.lowercase()
        return when {
            "siliconflow" in baseUrl -> "FunAudioLLM/SenseVoiceSmall"
            else -> "whisper-1"
        }
    }

    fun getAiProvider(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_PROVIDER, AiProviderRegistry.PROVIDER_SILICONFLOW)
            ?: AiProviderRegistry.PROVIDER_SILICONFLOW

    fun setAiProvider(ctx: Context, value: String) {
        val normalized = value.trim().ifBlank { AiProviderRegistry.PROVIDER_SILICONFLOW }
        prefs(ctx).edit().putString(KEY_AI_PROVIDER, normalized).apply()
    }

    fun getAiUrl(ctx: Context): String {
        val saved = prefs(ctx).getString(KEY_AI_URL, "")?.trim().orEmpty()
        if (saved.isNotEmpty()) return saved
        return AiProviderRegistry.presetFor(getAiProvider(ctx))?.baseUrl
            ?: "https://api.siliconflow.cn"
    }

    fun setAiUrl(ctx: Context, url: String) {
        val normalized = url.trim().ifBlank {
            AiProviderRegistry.presetFor(getAiProvider(ctx))?.baseUrl.orEmpty()
        }
        if (normalized.isNotEmpty()) {
            prefs(ctx).edit().putString(KEY_AI_URL, normalized).apply()
        }
    }

    fun isAiConfigured(ctx: Context): Boolean = getAiKey(ctx).isNotBlank()

    fun isAiManualModelSelectionEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_MANUAL_MODEL_SELECTION, false)

    fun setAiManualModelSelectionEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_MANUAL_MODEL_SELECTION, enabled).apply()

    fun isReceiptOcrRefineEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RECEIPT_OCR_REFINE_ENABLED, false)
    fun setReceiptOcrRefineEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_RECEIPT_OCR_REFINE_ENABLED, enabled).apply()

    fun isReceiptImageDraftConfirmEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RECEIPT_IMAGE_DRAFT_CONFIRM, true)
    fun setReceiptImageDraftConfirmEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_RECEIPT_IMAGE_DRAFT_CONFIRM, enabled).apply()

    fun isImageAccountingNaturalLanguage(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_IMAGE_ACCOUNTING_NATURAL_LANGUAGE, false)
    fun setImageAccountingNaturalLanguage(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_IMAGE_ACCOUNTING_NATURAL_LANGUAGE, enabled).apply()

    fun isScreenAccountingUseImageFlow(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SCREEN_ACCOUNTING_USE_IMAGE_FLOW, false)
    fun setScreenAccountingUseImageFlow(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SCREEN_ACCOUNTING_USE_IMAGE_FLOW, enabled).apply()

    fun addOcrDebugRecord(ctx: Context, text: String, source: String = "local_ocr_before_ai") {
        if (text.isBlank()) return
        val trimmedText = if (text.length > OCR_DEBUG_MAX_TEXT_LEN) {
            text.take(OCR_DEBUG_MAX_TEXT_LEN) + "\n...[TRUNCATED]"
        } else {
            text
        }

        val records = getOcrDebugRecords(ctx).toMutableList()
        records.add(0, OcrDebugRecord(System.currentTimeMillis(), source, trimmedText))
        if (records.size > OCR_DEBUG_MAX_RECORDS) {
            records.subList(OCR_DEBUG_MAX_RECORDS, records.size).clear()
        }

        val arr = JSONArray()
        records.forEach { item ->
            val obj = JSONObject()
            obj.put("timestamp", item.timestamp)
            obj.put("source", item.source)
            obj.put("text", item.text)
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY_OCR_DEBUG_RECORDS, arr.toString()).apply()
    }

    fun getOcrDebugRecords(ctx: Context): List<OcrDebugRecord> {
        val raw = prefs(ctx).getString(KEY_OCR_DEBUG_RECORDS, null) ?: return emptyList()
        val list = mutableListOf<OcrDebugRecord>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    OcrDebugRecord(
                        timestamp = obj.optLong("timestamp", 0L),
                        source = obj.optString("source", "local_ocr_before_ai"),
                        text = obj.optString("text", "")
                    )
                )
            }
        }.onFailure { it.printStackTrace() }
        return list
    }

    fun clearOcrDebugRecords(ctx: Context) {
        prefs(ctx).edit().remove(KEY_OCR_DEBUG_RECORDS).apply()
    }

    fun getAiRules(ctx: Context): List<AiRule> {
        val jsonStr = prefs(ctx).getString(KEY_AI_RULES, null) ?: return emptyList()
        val list = mutableListOf<AiRule>()
        runCatching {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AiRule(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        keyword = obj.getString("keyword"),
                        targetType = if (obj.has("targetType") && !obj.isNull("targetType")) obj.getInt("targetType") else null,
                        targetCategory = if (obj.has("targetCategory") && !obj.isNull("targetCategory")) obj.getString("targetCategory") else null,
                        targetAccount1 = if (obj.has("targetAccount1") && !obj.isNull("targetAccount1")) obj.getString("targetAccount1") else null,
                        targetAccount2 = if (obj.has("targetAccount2") && !obj.isNull("targetAccount2")) obj.getString("targetAccount2") else null,
                        isEnabled = obj.optBoolean("isEnabled", true)
                    )
                )
            }
        }.onFailure { it.printStackTrace() }
        return list
    }

    fun saveAiRules(ctx: Context, rules: List<AiRule>) {
        val arr = JSONArray()
        rules.forEach { rule ->
            val obj = JSONObject()
            obj.put("id", rule.id)
            obj.put("keyword", rule.keyword)
            if (rule.targetType != null) obj.put("targetType", rule.targetType)
            if (rule.targetCategory != null) obj.put("targetCategory", rule.targetCategory)
            if (rule.targetAccount1 != null) obj.put("targetAccount1", rule.targetAccount1)
            if (rule.targetAccount2 != null) obj.put("targetAccount2", rule.targetAccount2)
            obj.put("isEnabled", rule.isEnabled)
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY_AI_RULES, arr.toString()).apply()
    }

    fun isAiPromptCorrectionEnabled(context: Context): Boolean {
        return prefs(context).getBoolean("enable_ai_prompt_correction", true)
    }

    fun setAiPromptCorrectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enable_ai_prompt_correction", enabled).apply()
    }

    fun isLocalRuleOverrideEnabled(context: Context): Boolean {
        return prefs(context).getBoolean("enable_local_rule_override", true)
    }

    fun setLocalRuleOverrideEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enable_local_rule_override", enabled).apply()
    }
}
