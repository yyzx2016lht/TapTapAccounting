package com.taostudio.tapaccounting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Centralized SharedPreferences access.
 *
 * Existing call sites still use `Prefs.xxx(...)`, so this object keeps the
 * public API stable while we gradually split large helper logic out.
 */
object Prefs {
    const val AI_ENTRY_MODE_TRADITIONAL = 0
    const val AI_ENTRY_MODE_CHAT = 1

    const val CHAT_PAGE_MODE_ACCOUNTING = 0
    const val CHAT_PAGE_MODE_CONVERSATION = 1

    const val ASR_MODE_API = 0
    const val ASR_MODE_WHISPER = 1
    const val OCR_MODE_LOCAL = 0
    const val OCR_MODE_MULTIMODAL = 1
    const val RECEIPT_LANG_AUTO = 0
    const val RECEIPT_LANG_CN = 1
    const val RECEIPT_LANG_FOREIGN = 2

    const val TYPE_EXPENSE = 1
    const val TYPE_INCOME = 2

    // --- General settings ---

    fun isVibrateFeedbackEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isVibrateFeedbackEnabled(ctx)
    fun setVibrateFeedbackEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setVibrateFeedbackEnabled(ctx, enabled)

    fun isSaveVibrateEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isSaveVibrateEnabled(ctx)
    fun setSaveVibrateEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setSaveVibrateEnabled(ctx, enabled)

    fun isDisableLandscape(ctx: Context): Boolean =
        PrefsGeneralSupport.isDisableLandscape(ctx)
    fun setDisableLandscape(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setDisableLandscape(ctx, enabled)

    fun isHideRecents(ctx: Context): Boolean = PrefsGeneralSupport.isHideRecents(ctx)
    fun setHideRecents(ctx: Context, hide: Boolean) = PrefsGeneralSupport.setHideRecents(ctx, hide)

    fun isFlipEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isFlipEnabled(ctx)
    fun setFlipEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setFlipEnabled(ctx, enabled)
    fun getFlipSensitivity(ctx: Context): Int = PrefsGeneralSupport.getFlipSensitivity(ctx)
    fun setFlipSensitivity(ctx: Context, level: Int) =
        PrefsGeneralSupport.setFlipSensitivity(ctx, level)
    fun getFlipAction(ctx: Context): String = PrefsGeneralSupport.getFlipAction(ctx)
    fun setFlipAction(ctx: Context, actionId: String) =
        PrefsGeneralSupport.setFlipAction(ctx, actionId)
    fun hasSeenFlipGuide(ctx: Context): Boolean = PrefsGeneralSupport.hasSeenFlipGuide(ctx)
    fun setFlipGuideSeen(ctx: Context) = PrefsGeneralSupport.setFlipGuideSeen(ctx)

    fun isShizukuPersistenceEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isShizukuPersistenceEnabled(ctx)
    fun setShizukuPersistenceEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setShizukuPersistenceEnabled(ctx, enabled)
    fun isShizukuModeEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isShizukuModeEnabled(ctx)
    fun setShizukuModeEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setShizukuModeEnabled(ctx, enabled)

    fun isLoggingEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isLoggingEnabled(ctx)
    fun setLoggingEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setLoggingEnabled(ctx, enabled)
    fun enablePrivacyDebugLoggingForMinutes(ctx: Context, minutes: Int = 30) =
        PrefsGeneralSupport.enablePrivacyDebugLoggingForMinutes(ctx, minutes)
    fun disablePrivacyDebugLogging(ctx: Context) =
        PrefsGeneralSupport.disablePrivacyDebugLogging(ctx)
    fun isPrivacyDebugLoggingEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isPrivacyDebugLoggingEnabled(ctx)
    fun isDeveloperFullLoggingEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isDeveloperFullLoggingEnabled(ctx)
    fun setDeveloperFullLoggingEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setDeveloperFullLoggingEnabled(ctx, enabled)

    fun getAppUsageMode(ctx: Context): Int = PrefsGeneralSupport.getAppUsageMode(ctx)
    fun setAppUsageMode(ctx: Context, mode: Int) = PrefsGeneralSupport.setAppUsageMode(ctx, mode)

    fun getAsrMode(ctx: Context): Int = PrefsGeneralSupport.getAsrMode(ctx)
    fun setAsrMode(ctx: Context, mode: Int) = PrefsGeneralSupport.setAsrMode(ctx, mode)
    fun getAsrDownloadSource(ctx: Context): String = PrefsGeneralSupport.getAsrDownloadSource(ctx)
    fun setAsrDownloadSource(ctx: Context, source: String) =
        PrefsGeneralSupport.setAsrDownloadSource(ctx, source)
    fun isAssetFeatureEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isAssetFeatureEnabled(ctx)
    fun setAssetFeatureEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setAssetFeatureEnabled(ctx, enabled)
    fun isRecurringAutoDetectEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isRecurringAutoDetectEnabled(ctx)
    fun setRecurringAutoDetectEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setRecurringAutoDetectEnabled(ctx, enabled)
    fun getRecurringDetectAmountTolerance(ctx: Context): Double =
        PrefsGeneralSupport.getRecurringDetectAmountTolerance(ctx)
    fun setRecurringDetectAmountTolerance(ctx: Context, tolerance: Double) =
        PrefsGeneralSupport.setRecurringDetectAmountTolerance(ctx, tolerance)
    fun hasSeenRecurringAutoDetectGuide(ctx: Context): Boolean =
        PrefsGeneralSupport.hasSeenRecurringAutoDetectGuide(ctx)
    fun setRecurringAutoDetectGuideSeen(ctx: Context) =
        PrefsGeneralSupport.setRecurringAutoDetectGuideSeen(ctx)

    // --- AI settings ---
    fun getAiKey(ctx: Context): String = PrefsAiSupport.getAiKey(ctx)
    fun setAiKey(ctx: Context, key: String) = PrefsAiSupport.setAiKey(ctx, key)
    fun getAiProviderKey(ctx: Context, providerId: String): String =
        PrefsAiSupport.getAiProviderKey(ctx, providerId)
    fun setAiProviderKey(ctx: Context, providerId: String, apiKey: String) =
        PrefsAiSupport.setAiProviderKey(ctx, providerId, apiKey)
    fun exportAiProviderKeysJson(ctx: Context): String =
        PrefsAiSupport.exportAiProviderKeysJson(ctx)
    fun importAiProviderKeysFromBackup(ctx: Context, json: String) =
        PrefsAiSupport.importAiProviderKeysFromBackup(ctx, json)

    // Legacy compatibility field. New code should prefer getAiMultiModel().
    fun getAiModel(ctx: Context): String = PrefsAiSupport.getAiModel(ctx)
    // Legacy compatibility field. New code should prefer setAiMultiModel().
    fun setAiModel(ctx: Context, value: String) = PrefsAiSupport.setAiModel(ctx, value)

    fun getAiMultiModel(ctx: Context): String = PrefsAiSupport.getAiMultiModel(ctx)
    fun setAiMultiModel(ctx: Context, value: String) = PrefsAiSupport.setAiMultiModel(ctx, value)

    fun getAiModifyModel(ctx: Context): String = PrefsAiSupport.getAiModifyModel(ctx)
    fun setAiModifyModel(ctx: Context, value: String) = PrefsAiSupport.setAiModifyModel(ctx, value)

    // Legacy compatibility field. Current product flow follows the text model.
    fun getAiCategoryRefineModel(ctx: Context): String = PrefsAiSupport.getAiCategoryRefineModel(ctx)
    fun setAiCategoryRefineModel(ctx: Context, value: String) =
        PrefsAiSupport.setAiCategoryRefineModel(ctx, value)

    // Hidden legacy field. Current product flow does not expose router configuration.
    fun getAiRouterModel(ctx: Context): String = PrefsAiSupport.getAiRouterModel(ctx)
    fun setAiRouterModel(ctx: Context, value: String) = PrefsAiSupport.setAiRouterModel(ctx, value)
    // Hidden legacy flag kept for backward-compatible restore only.
    fun isAiLlmRouterEnabled(ctx: Context): Boolean = PrefsAiSupport.isAiLlmRouterEnabled(ctx)
    fun setAiLlmRouterEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiLlmRouterEnabled(ctx, enabled)
    fun getAiRuleModel(ctx: Context): String = PrefsAiSupport.getAiRuleModel(ctx)
    fun setAiRuleModel(ctx: Context, value: String) = PrefsAiSupport.setAiRuleModel(ctx, value)
    fun getAiReceiptModel(ctx: Context): String = PrefsAiSupport.getAiReceiptModel(ctx)
    fun setAiReceiptModel(ctx: Context, value: String) = PrefsAiSupport.setAiReceiptModel(ctx, value)
    fun getAiReceiptVisionModel(ctx: Context): String = PrefsAiSupport.getAiReceiptVisionModel(ctx)
    fun setAiReceiptVisionModel(ctx: Context, value: String) =
        PrefsAiSupport.setAiReceiptVisionModel(ctx, value)
    // Legacy compatibility field. Current product flow follows the vision model.
    fun getAiScreenModel(ctx: Context): String = PrefsAiSupport.getAiScreenModel(ctx)
    fun setAiScreenModel(ctx: Context, value: String) =
        PrefsAiSupport.setAiScreenModel(ctx, value)
    fun isScreenModelVisionSupported(ctx: Context, model: String): Boolean =
        PrefsAiSupport.isScreenModelVisionSupported(ctx, model)
    fun setScreenModelVisionSupported(ctx: Context, model: String, supported: Boolean) =
        PrefsAiSupport.setScreenModelVisionSupported(ctx, model, supported)
    fun getAiReceiptOcrRefineModel(ctx: Context): String = PrefsAiSupport.getAiReceiptOcrRefineModel(ctx)
    fun setAiReceiptOcrRefineModel(ctx: Context, value: String) =
        PrefsAiSupport.setAiReceiptOcrRefineModel(ctx, value)
    fun getAiSpeechModel(ctx: Context): String = PrefsAiSupport.getAiSpeechModel(ctx)
    fun setAiSpeechModel(ctx: Context, value: String) = PrefsAiSupport.setAiSpeechModel(ctx, value)
    fun isAiThinkingEnabled(ctx: Context): Boolean = PrefsAiSupport.getAiEnableThinking(ctx)
    fun setAiThinkingEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiEnableThinking(ctx, enabled)
    fun isAiThinkingMultiBillEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isAiThinkingMultiBillEnabled(ctx)
    fun setAiThinkingMultiBillEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiThinkingMultiBillEnabled(ctx, enabled)
    fun isAiThinkingModifyBillEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isAiThinkingModifyBillEnabled(ctx)
    fun setAiThinkingModifyBillEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiThinkingModifyBillEnabled(ctx, enabled)
    fun isAiThinkingVisionEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isAiThinkingVisionEnabled(ctx)
    fun setAiThinkingVisionEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiThinkingVisionEnabled(ctx, enabled)
    fun isAiThinkingCategoryRefineEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isAiThinkingCategoryRefineEnabled(ctx)
    fun setAiThinkingCategoryRefineEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiThinkingCategoryRefineEnabled(ctx, enabled)
    fun getAiModelsCache(ctx: Context): List<String> = PrefsAiSupport.getAiModelsCache(ctx)
    fun setAiModelsCache(ctx: Context, models: List<String>) =
        PrefsAiSupport.setAiModelsCache(ctx, models)

    fun applyAiProviderConfigSync(
        ctx: Context,
        preset: AiProviderPreset,
        apiKey: String,
        modelsCache: List<String>? = null
    ) = PrefsAiSupport.applyAiProviderConfigSync(ctx, preset, apiKey, modelsCache)

    fun getAiProvider(ctx: Context): String = PrefsAiSupport.getAiProvider(ctx)
    fun setAiProvider(ctx: Context, value: String) = PrefsAiSupport.setAiProvider(ctx, value)

    fun getAiUrl(ctx: Context): String = PrefsAiSupport.getAiUrl(ctx)
    fun setAiUrl(ctx: Context, url: String) = PrefsAiSupport.setAiUrl(ctx, url)

    fun isAiConfigured(ctx: Context): Boolean = PrefsAiSupport.isAiConfigured(ctx)

    fun isAiManualModelSelectionEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isAiManualModelSelectionEnabled(ctx)

    fun setAiManualModelSelectionEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiManualModelSelectionEnabled(ctx, enabled)

    fun isReceiptOcrRefineEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isReceiptOcrRefineEnabled(ctx)
    fun setReceiptOcrRefineEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setReceiptOcrRefineEnabled(ctx, enabled)
    fun isReceiptImageDraftConfirmEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isReceiptImageDraftConfirmEnabled(ctx)
    fun setReceiptImageDraftConfirmEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setReceiptImageDraftConfirmEnabled(ctx, enabled)
    fun isImageAccountingNaturalLanguage(ctx: Context): Boolean =
        PrefsAiSupport.isImageAccountingNaturalLanguage(ctx)
    fun setImageAccountingNaturalLanguage(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setImageAccountingNaturalLanguage(ctx, enabled)
    fun isScreenAccountingUseImageFlow(ctx: Context): Boolean =
        PrefsAiSupport.isScreenAccountingUseImageFlow(ctx)
    fun setScreenAccountingUseImageFlow(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setScreenAccountingUseImageFlow(ctx, enabled)
    // --- Bill cache management ---
    fun addBill(ctx: Context, bill: Bill) = PrefsDataSupport.addBill(ctx, bill)

    fun deleteBill(ctx: Context, bill: Bill) {
        deleteBills(ctx, setOf(bill))
    }

    fun deleteBills(ctx: Context, billsToDelete: Set<Bill>) =
        PrefsDataSupport.deleteBills(ctx, billsToDelete)

    fun getBills(ctx: Context): List<Bill> = PrefsDataSupport.getBills(ctx)

    // --- White list ---
    fun getAppWhiteList(ctx: Context): Set<String> = PrefsGeneralSupport.getAppWhiteList(ctx)
    fun setAppWhiteList(ctx: Context, list: Set<String>) = PrefsGeneralSupport.setAppWhiteList(ctx, list)

    // --- Currency and exchange rates ---
    fun getActiveCurrencies(ctx: Context): Set<String> = PrefsGeneralSupport.getActiveCurrencies(ctx)
    fun setActiveCurrencies(ctx: Context, currencies: Set<String>) =
        PrefsGeneralSupport.setActiveCurrencies(ctx, currencies)

    fun getExchangeRefreshInterval(ctx: Context): Long =
        PrefsGeneralSupport.getExchangeRefreshInterval(ctx)
    fun setExchangeRefreshInterval(ctx: Context, interval: Long) =
        PrefsGeneralSupport.setExchangeRefreshInterval(ctx, interval)

    fun getAssetAmountDisplayMode(ctx: Context): String =
        PrefsGeneralSupport.getAssetAmountDisplayMode(ctx)
    fun setAssetAmountDisplayMode(ctx: Context, mode: String) =
        PrefsGeneralSupport.setAssetAmountDisplayMode(ctx, mode)

    // --- Display settings ---
    fun isShowAiText(ctx: Context): Boolean = PrefsDisplaySupport.isShowAiText(ctx)
    fun setShowAiText(ctx: Context, show: Boolean) = PrefsDisplaySupport.setShowAiText(ctx, show)

    fun isShowAiVoice(ctx: Context): Boolean = PrefsDisplaySupport.isShowAiVoice(ctx)
    fun setShowAiVoice(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowAiVoice(ctx, show)
    fun isShowAiImage(ctx: Context): Boolean = PrefsDisplaySupport.isShowAiImage(ctx)
    fun setShowAiImage(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowAiImage(ctx, show)
    fun isShowScreenAccounting(ctx: Context): Boolean =
        PrefsDisplaySupport.isShowScreenAccounting(ctx)
    fun setShowScreenAccounting(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowScreenAccounting(ctx, show)

    fun isShowMultiCurrency(ctx: Context): Boolean =
        PrefsDisplaySupport.isShowMultiCurrency(ctx)
    fun setShowMultiCurrency(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowMultiCurrency(ctx, show)

    fun isShowHomeTrendCard(ctx: Context): Boolean =
        PrefsDisplaySupport.isShowHomeTrendCard(ctx)

    fun isInsightCardsEnabled(ctx: Context): Boolean =
        PrefsDisplaySupport.isInsightCardsEnabled(ctx)
    fun setInsightCardsEnabled(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setInsightCardsEnabled(ctx, enabled)

    fun isImportOnboardingSeen(ctx: Context): Boolean =
        PrefsDisplaySupport.isImportOnboardingSeen(ctx)
    fun setImportOnboardingSeen(ctx: Context, seen: Boolean) =
        PrefsDisplaySupport.setImportOnboardingSeen(ctx, seen)

    fun isImportReviewCompleted(ctx: Context): Boolean =
        PrefsDisplaySupport.isImportReviewCompleted(ctx)
    fun setImportReviewCompleted(ctx: Context, completed: Boolean) =
        PrefsDisplaySupport.setImportReviewCompleted(ctx, completed)
    fun setShowHomeTrendCard(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowHomeTrendCard(ctx, show)

    fun isHomeBudgetSummaryEnabled(ctx: Context, bookName: String): Boolean =
        PrefsDisplaySupport.isHomeBudgetSummaryEnabled(ctx, bookName)

    fun setHomeBudgetSummaryEnabled(ctx: Context, bookName: String, enabled: Boolean) =
        PrefsDisplaySupport.setHomeBudgetSummaryEnabled(ctx, bookName, enabled)

    fun hasHomeBudgetSummaryPreference(ctx: Context, bookName: String): Boolean =
        PrefsDisplaySupport.hasHomeBudgetSummaryPreference(ctx, bookName)

    fun isStatsBudgetModeEnabled(ctx: Context, bookName: String): Boolean =
        PrefsDisplaySupport.isStatsBudgetModeEnabled(ctx, bookName)

    fun setStatsBudgetModeEnabled(ctx: Context, bookName: String, enabled: Boolean) =
        PrefsDisplaySupport.setStatsBudgetModeEnabled(ctx, bookName, enabled)

    fun hasStatsBudgetModePreference(ctx: Context, bookName: String): Boolean =
        PrefsDisplaySupport.hasStatsBudgetModePreference(ctx, bookName)

    fun enableSharedBudgetDisplayDefaultsIfUnset(ctx: Context, bookName: String) =
        PrefsDisplaySupport.enableSharedBudgetDisplayDefaultsIfUnset(ctx, bookName)

    fun isMultiBillEnabled(ctx: Context): Boolean = PrefsDisplaySupport.isMultiBillEnabled(ctx)
    fun setMultiBillEnabled(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setMultiBillEnabled(ctx, enabled)

    fun isMultiBillNotSync(ctx: Context): Boolean = PrefsDisplaySupport.isMultiBillNotSync(ctx)
    fun setMultiBillNotSync(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setMultiBillNotSync(ctx, enabled)

    fun isShowBookEntry(ctx: Context): Boolean = PrefsDisplaySupport.isShowBookEntry(ctx)
    fun setShowBookEntry(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowBookEntry(ctx, show)

    fun getOcrMode(ctx: Context): Int = PrefsDisplaySupport.getOcrMode(ctx)
    fun setOcrMode(ctx: Context, mode: Int) = PrefsDisplaySupport.setOcrMode(ctx, mode)
    fun getReceiptLangMode(ctx: Context): Int = PrefsDisplaySupport.getReceiptLangMode(ctx)
    fun setReceiptLangMode(ctx: Context, mode: Int) =
        PrefsDisplaySupport.setReceiptLangMode(ctx, mode)
    fun isSaveOcrDebugEnabled(ctx: Context): Boolean =
        PrefsDisplaySupport.isSaveOcrDebugEnabled(ctx)
    fun setSaveOcrDebugEnabled(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setSaveOcrDebugEnabled(ctx, enabled)
    fun isAmountGroupingEnabled(ctx: Context): Boolean =
        PrefsDisplaySupport.isAmountGroupingEnabled(ctx)
    fun setAmountGroupingEnabled(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setAmountGroupingEnabled(ctx, enabled)
    fun isShowBillCategoryIcon(ctx: Context): Boolean =
        PrefsDisplaySupport.isShowBillCategoryIcon(ctx)
    fun setShowBillCategoryIcon(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowBillCategoryIcon(ctx, show)
    fun isShowBillFullCategory(ctx: Context): Boolean =
        PrefsDisplaySupport.isShowBillFullCategory(ctx)
    fun setShowBillFullCategory(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowBillFullCategory(ctx, show)
    fun isBillRemarkPriority(ctx: Context): Boolean =
        PrefsDisplaySupport.isBillRemarkPriority(ctx)
    fun setBillRemarkPriority(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setBillRemarkPriority(ctx, enabled)

    fun isIndependentDetailEnabled(ctx: Context): Boolean =
        PrefsDisplaySupport.isIndependentDetailEnabled(ctx)
    fun setIndependentDetailEnabled(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setIndependentDetailEnabled(ctx, enabled)

    fun addOcrDebugRecord(ctx: Context, text: String, source: String = "local_ocr_before_ai") =
        PrefsAiSupport.addOcrDebugRecord(ctx, text, source)

    fun getOcrDebugRecords(ctx: Context): List<OcrDebugRecord> =
        PrefsAiSupport.getOcrDebugRecords(ctx)

    fun clearOcrDebugRecords(ctx: Context) = PrefsAiSupport.clearOcrDebugRecords(ctx)

    fun getAiRules(ctx: Context): List<AiRule> = PrefsAiSupport.getAiRules(ctx)

    fun saveAiRules(ctx: Context, rules: List<AiRule>) = PrefsAiSupport.saveAiRules(ctx, rules)

    // --- Asset management ---
    fun getAssets(ctx: Context): List<Asset> = PrefsDataSupport.getAssets(ctx)

    fun saveAssets(ctx: Context, assets: List<Asset>) = PrefsDataSupport.saveAssets(ctx, assets)

    // --- Category management ---
    fun getCategories(ctx: Context, type: Int): MutableList<CategoryNode> =
        PrefsDataSupport.getCategories(ctx, type)

    fun loadDefaultFromRaw(ctx: Context, type: Int): MutableList<CategoryNode> =
        PrefsDataSupport.loadDefaultFromRaw(ctx, type)

    fun loadAssetsFromRaw(ctx: Context): List<BuiltInCategory> =
        PrefsDataSupport.loadAssetsFromRaw(ctx)

    fun saveCategories(ctx: Context, type: Int, list: List<CategoryNode>) =
        PrefsDataSupport.saveCategories(ctx, type, list)

    fun deleteCategory(ctx: Context, type: Int, name: String) =
        PrefsDataSupport.deleteCategory(ctx, type, name)

    // --- Serialization helpers ---
    fun serializeAssetList(assets: List<Asset>): JSONArray =
        PrefsDataSupport.serializeAssetList(assets)

    fun serializeCategoryList(list: List<CategoryNode>): JSONArray =
        PrefsDataSupport.serializeCategoryList(list)

    fun importAll(ctx: Context, root: JSONObject) = PrefsBackupSupport.importAll(ctx, root)

    // --- Quick gesture master switch ---
    fun isQuickGestureEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isQuickGestureEnabled(ctx)
    fun setQuickGestureEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setQuickGestureEnabled(ctx, enabled)

    fun isApiConfigUnlocked(ctx: Context): Boolean = PrefsGeneralSupport.isApiConfigUnlocked(ctx)
    fun setApiConfigUnlocked(ctx: Context, unlocked: Boolean) =
        PrefsGeneralSupport.setApiConfigUnlocked(ctx, unlocked)

    fun isAiDetailConfigUnlocked(ctx: Context): Boolean = PrefsGeneralSupport.isAiDetailConfigUnlocked(ctx)
    fun setAiDetailConfigUnlocked(ctx: Context, unlocked: Boolean) =
        PrefsGeneralSupport.setAiDetailConfigUnlocked(ctx, unlocked)

    fun isShizukuUnlocked(ctx: Context): Boolean = PrefsGeneralSupport.isShizukuUnlocked(ctx)
    fun setShizukuUnlocked(ctx: Context, unlocked: Boolean) =
        PrefsGeneralSupport.setShizukuUnlocked(ctx, unlocked)

    fun isAggressiveKeepAliveEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isAggressiveKeepAliveEnabled(ctx)
    fun setAggressiveKeepAliveEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setAggressiveKeepAliveEnabled(ctx, enabled)

    // --- Double tap detection ---
    fun isDoubleTapEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isDoubleTapEnabled(ctx)
    fun setDoubleTapEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setDoubleTapEnabled(ctx, enabled)
    fun hasSeenDoubleTapGuide(ctx: Context): Boolean = PrefsGeneralSupport.hasSeenDoubleTapGuide(ctx)
    fun setDoubleTapGuideSeen(ctx: Context) = PrefsGeneralSupport.setDoubleTapGuideSeen(ctx)
    fun hasSeenQuickGestureSetupGuide(ctx: Context): Boolean =
        PrefsGeneralSupport.hasSeenQuickGestureSetupGuide(ctx)
    fun setQuickGestureSetupGuideSeen(ctx: Context) =
        PrefsGeneralSupport.setQuickGestureSetupGuideSeen(ctx)
    fun hasSeenSensitivityOnboarding(ctx: Context): Boolean =
        PrefsGeneralSupport.hasSeenSensitivityOnboarding(ctx)
    fun setSensitivityOnboardingSeen(ctx: Context) =
        PrefsGeneralSupport.setSensitivityOnboardingSeen(ctx)
    fun hasSeenHomeOnboarding(ctx: Context): Boolean =
        PrefsGeneralSupport.hasSeenHomeOnboarding(ctx)
    fun setHomeOnboardingSeen(ctx: Context) =
        PrefsGeneralSupport.setHomeOnboardingSeen(ctx)
    fun isSettingsGuideDismissed(ctx: Context): Boolean =
        PrefsGeneralSupport.isSettingsGuideDismissed(ctx)
    fun setSettingsGuideDismissed(ctx: Context, dismissed: Boolean) =
        PrefsGeneralSupport.setSettingsGuideDismissed(ctx, dismissed)
    fun getGesturePermissionPromptDeferUntilMs(ctx: Context): Long =
        PrefsGeneralSupport.getGesturePermissionPromptDeferUntilMs(ctx)
    fun setGesturePermissionPromptDeferUntilMs(ctx: Context, untilMs: Long) =
        PrefsGeneralSupport.setGesturePermissionPromptDeferUntilMs(ctx, untilMs)
    fun shouldDeferGesturePermissionPrompt(ctx: Context): Boolean =
        PrefsGeneralSupport.shouldDeferGesturePermissionPrompt(ctx)

    // --- Tap back settings ---
    fun getTapModel(ctx: Context): String = PrefsGeneralSupport.getTapModel(ctx)
    fun setTapModel(ctx: Context, model: String) = PrefsGeneralSupport.setTapModel(ctx, model)
    fun getTapSensitivityLevel(ctx: Context): Int = PrefsGeneralSupport.getTapSensitivityLevel(ctx)
    fun setTapSensitivityLevel(ctx: Context, level: Int) = PrefsGeneralSupport.setTapSensitivityLevel(ctx, level)

    /** 省电档（启发式待机）专用灵敏度：数字越大越灵敏。 */
    fun getTapHeSensitivityLevel(ctx: Context): Int = PrefsGeneralSupport.getTapHeSensitivityLevel(ctx)
    fun setTapHeSensitivityLevel(ctx: Context, level: Int) = PrefsGeneralSupport.setTapHeSensitivityLevel(ctx, level)

    /** 省电档测试模式：锁定启发式、只提示不记账，用于试灵敏度。 */
    fun isTapHeTestModeEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isTapHeTestModeEnabled(ctx)
    fun setTapHeTestModeEnabled(ctx: Context, enabled: Boolean) = PrefsGeneralSupport.setTapHeTestModeEnabled(ctx, enabled)
    fun isTapNnapiLowPower(ctx: Context): Boolean = PrefsGeneralSupport.isTapNnapiLowPower(ctx)
    fun setTapNnapiLowPower(ctx: Context, enabled: Boolean) = PrefsGeneralSupport.setTapNnapiLowPower(ctx, enabled)
    fun isTapPowerSavingEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isTapPowerSavingEnabled(ctx)
    fun setTapPowerSavingEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setTapPowerSavingEnabled(ctx, enabled)
    fun isTapForceFullMl(ctx: Context): Boolean = PrefsGeneralSupport.isTapForceFullMl(ctx)
    fun setTapForceFullMl(ctx: Context, enabled: Boolean) = PrefsGeneralSupport.setTapForceFullMl(ctx, enabled)
    fun isTapTripleEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isTapTripleEnabled(ctx)
    fun setTapTripleEnabled(ctx: Context, enabled: Boolean) = PrefsGeneralSupport.setTapTripleEnabled(ctx, enabled)
    fun getTapActionDouble(ctx: Context): String = PrefsGeneralSupport.getTapActionDouble(ctx)
    fun setTapActionDouble(ctx: Context, actionId: String) = PrefsGeneralSupport.setTapActionDouble(ctx, actionId)
    fun getTapActionTriple(ctx: Context): String = PrefsGeneralSupport.getTapActionTriple(ctx)
    fun setTapActionTriple(ctx: Context, actionId: String) = PrefsGeneralSupport.setTapActionTriple(ctx, actionId)

    // --- White list serialization helpers ---
    fun serializeWhiteList(set: Set<String>): String = PrefsGeneralSupport.serializeWhiteList(set)

    fun importWhiteList(ctx: Context, jsonStr: String) =
        PrefsGeneralSupport.importWhiteList(ctx, jsonStr)

    fun serializeSettings(ctx: Context): String = PrefsBackupSupport.serializeSettings(ctx)

    // --- AI correction settings ---
    fun isAiPromptCorrectionEnabled(context: Context): Boolean =
        PrefsAiSupport.isAiPromptCorrectionEnabled(context)

    fun setAiPromptCorrectionEnabled(context: Context, enabled: Boolean) =
        PrefsAiSupport.setAiPromptCorrectionEnabled(context, enabled)

    fun isLocalRuleOverrideEnabled(context: Context): Boolean =
        PrefsAiSupport.isLocalRuleOverrideEnabled(context)

    fun setLocalRuleOverrideEnabled(context: Context, enabled: Boolean) =
        PrefsAiSupport.setLocalRuleOverrideEnabled(context, enabled)

    // --- AI chat settings ---
    fun isShowAiChatEntry(ctx: Context): Boolean = PrefsChatSupport.isShowAiChatEntry(ctx)
    fun setShowAiChatEntry(ctx: Context, show: Boolean) =
        PrefsChatSupport.setShowAiChatEntry(ctx, show)

    /** Accounting entry mode: traditional input or AI chat. */
    fun getAiEntryMode(ctx: Context): Int = PrefsChatSupport.getAiEntryMode(ctx)
    fun setAiEntryMode(ctx: Context, mode: Int) = PrefsChatSupport.setAiEntryMode(ctx, mode)

    fun getChatPageMode(ctx: Context): Int = PrefsChatSupport.getChatPageMode(ctx)
    fun setChatPageMode(ctx: Context, mode: Int) = PrefsChatSupport.setChatPageMode(ctx, mode)

    fun getAiChatName(ctx: Context): String = PrefsChatSupport.getAiChatName(ctx)
    fun setAiChatName(ctx: Context, name: String) = PrefsChatSupport.setAiChatName(ctx, name)

    fun getAiChatIdentity(ctx: Context): String = PrefsChatSupport.getAiChatIdentity(ctx)
    fun setAiChatIdentity(ctx: Context, identity: String) =
        PrefsChatSupport.setAiChatIdentity(ctx, identity)

    fun getUserChatName(ctx: Context): String = PrefsChatSupport.getUserChatName(ctx)
    fun setUserChatName(ctx: Context, name: String) = PrefsChatSupport.setUserChatName(ctx, name)
    fun getUserProfileDesc(ctx: Context): String = PrefsChatSupport.getUserProfileDesc(ctx)
    fun setUserProfileDesc(ctx: Context, text: String) = PrefsChatSupport.setUserProfileDesc(ctx, text)

    fun getAiChatAvatarPath(ctx: Context): String = PrefsChatSupport.getAiChatAvatarPath(ctx)
    fun setAiChatAvatarPath(ctx: Context, path: String) =
        PrefsChatSupport.setAiChatAvatarPath(ctx, path)

    fun getUserChatAvatarPath(ctx: Context): String = PrefsChatSupport.getUserChatAvatarPath(ctx)
    fun setUserChatAvatarPath(ctx: Context, path: String) =
        PrefsChatSupport.setUserChatAvatarPath(ctx, path)

    fun getAiChatBgPath(ctx: Context): String = PrefsChatSupport.getAiChatBgPath(ctx)
    fun setAiChatBgPath(ctx: Context, path: String) = PrefsChatSupport.setAiChatBgPath(ctx, path)

    fun getAiChatModel(ctx: Context): String = PrefsChatSupport.getAiChatModel(ctx)
    fun isAiChatModelFollowingMain(ctx: Context): Boolean =
        PrefsChatSupport.isAiChatModelFollowingMain(ctx)
    fun setAiChatModel(ctx: Context, value: String) = PrefsChatSupport.setAiChatModel(ctx, value)
    fun resetChatModelOnProviderChange(ctx: Context) =
        PrefsChatSupport.resetChatModelOnProviderChange(ctx)

    fun getAiChatReplyStyle(ctx: Context): String = PrefsChatSupport.getAiChatReplyStyle(ctx)
    fun setAiChatReplyStyle(ctx: Context, value: String) =
        PrefsChatSupport.setAiChatReplyStyle(ctx, value)
    fun getAiChatReplyStyleCustomPrompt(ctx: Context): String =
        PrefsChatSupport.getAiChatReplyStyleCustomPrompt(ctx)
    fun setAiChatReplyStyleCustomPrompt(ctx: Context, value: String) =
        PrefsChatSupport.setAiChatReplyStyleCustomPrompt(ctx, value)
    fun getAiChatModelAudioSupport(ctx: Context, model: String): Boolean? =
        PrefsChatSupport.getAiChatModelAudioSupport(ctx, model)
    fun setAiChatModelAudioSupport(ctx: Context, model: String, supported: Boolean) =
        PrefsChatSupport.setAiChatModelAudioSupport(ctx, model, supported)

    fun getAiChatSessionTitle(ctx: Context, bookName: String, conversationId: String): String =
        PrefsChatSupport.getAiChatSessionTitle(ctx, bookName, conversationId)

    fun setAiChatSessionTitle(ctx: Context, bookName: String, conversationId: String, title: String) =
        PrefsChatSupport.setAiChatSessionTitle(ctx, bookName, conversationId, title)

    fun serializeSettingsModules(ctx: Context): Map<String, String> =
        PrefsBackupSupport.serializeSettingsModules(ctx)

    // --- Backup initialization ---
    fun isBackupInitialized(ctx: Context): Boolean =
        PrefsGeneralSupport.isBackupInitialized(ctx)
    fun markBackupInitialized(ctx: Context) =
        PrefsGeneralSupport.markBackupInitialized(ctx)

}
