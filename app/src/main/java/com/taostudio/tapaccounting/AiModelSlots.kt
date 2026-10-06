package com.taostudio.tapaccounting

import android.content.Context

/**
 * Resolves which model each AI slot should use at request time.
 * Chat defaults to following the main text model unless the user picks otherwise.
 */
object AiModelSlots {

    fun resolveTextModel(ctx: Context): String {
        val preset = AiProviderRegistry.resolvePreset(ctx)
        if (!Prefs.isAiManualModelSelectionEnabled(ctx)) {
            return preset.defaultTextModel
        }
        val saved = Prefs.getAiMultiModel(ctx).trim()
        if (saved.isNotEmpty()) return saved
        return preset.defaultTextModel
    }

    fun resolveChatModel(ctx: Context): String {
        val main = resolveTextModel(ctx)
        val explicit = PrefsChatSupport.getAiChatModelRaw(ctx)
        if (explicit.isBlank()) return main
        val cache = Prefs.getAiModelsCache(ctx).map { it.trim() }.filter { it.isNotEmpty() }
        if (cache.isNotEmpty() && explicit !in cache) return main
        return explicit
    }

    fun resolveVisionModel(ctx: Context): String {
        val preset = AiProviderRegistry.resolvePreset(ctx)
        if (!preset.supportsVision) return ""
        val candidate = if (!Prefs.isAiManualModelSelectionEnabled(ctx)) {
            preset.defaultVisionModel
        } else {
            Prefs.getAiReceiptVisionModel(ctx).trim().ifBlank { preset.defaultVisionModel }
        }
        if (candidate.isBlank()) return ""
        return validateVisionCandidate(ctx, preset, candidate)
    }

    /**
     * 能力目录可用时，确保选中/默认的视觉模型确实支持图片输入；
     * 不合法则回落到预设默认或列表里第一个支持图片的模型。
     */
    private fun validateVisionCandidate(
        ctx: Context,
        preset: AiProviderPreset,
        candidate: String
    ): String {
        val providerId = preset.catalogProviderId ?: return candidate
        val liveModels = Prefs.getAiModelsCache(ctx).map { it.trim() }.filter { it.isNotEmpty() }
        if (!AiModelCatalog.isProviderCatalogUsable(ctx, providerId, liveModels)) return candidate
        if (candidate == preset.defaultVisionModel) return candidate
        if (AiModelCatalog.supportsImageInput(ctx, providerId, candidate) == true) return candidate
        val fallback = liveModels.firstOrNull { model ->
            model != candidate && (
                model == preset.defaultVisionModel ||
                    AiModelCatalog.supportsImageInput(ctx, providerId, model) == true
                )
        }
        if (fallback != null) return fallback
        return preset.defaultVisionModel.ifBlank { candidate }
    }

    fun resolveSpeechModel(ctx: Context): String {
        val preset = AiProviderRegistry.resolvePreset(ctx)
        if (!preset.supportsCloudSpeech) return ""
        if (!Prefs.isAiManualModelSelectionEnabled(ctx)) {
            return preset.defaultSpeechModel
        }
        return Prefs.getAiSpeechModel(ctx).trim().ifBlank { preset.defaultSpeechModel }
    }

    fun isChatFollowingMainText(ctx: Context): Boolean =
        Prefs.isAiChatModelFollowingMain(ctx)
}
