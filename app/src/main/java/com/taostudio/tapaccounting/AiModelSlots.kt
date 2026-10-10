package com.taostudio.tapaccounting

import android.content.Context

/**
 * Resolves which model each AI slot should use at request time.
 * Chat defaults to following the main text model unless the user picks otherwise.
 *
 * 各槽位的推荐值会与实时模型列表（[Prefs.getAiModelsCache]）比对：
 * 厂商换代后自动顶上更新的一代，用户手选的型号失效时回落到推荐值。
 */
object AiModelSlots {

    fun resolveTextModel(ctx: Context): String {
        val preset = AiProviderRegistry.resolvePreset(ctx)
        return resolveSlot(
            ctx = ctx,
            slot = AiModelAutoPicker.Slot.TEXT,
            curated = preset.defaultTextModel,
            saved = Prefs.getAiMultiModel(ctx).trim()
        )
    }

    fun resolveChatModel(ctx: Context): String {
        val main = resolveTextModel(ctx)
        val explicit = PrefsChatSupport.getAiChatModelRaw(ctx)
        if (explicit.isBlank()) return main
        val cache = liveModels(ctx)
        if (cache.isNotEmpty() && explicit !in cache) return main
        return explicit
    }

    fun resolveVisionModel(ctx: Context): String {
        val preset = AiProviderRegistry.resolvePreset(ctx)
        if (!preset.supportsVision) return ""
        val curated = preset.defaultVisionModel
        val picked = resolveSlot(
            ctx = ctx,
            slot = AiModelAutoPicker.Slot.VISION,
            curated = curated,
            saved = Prefs.getAiReceiptVisionModel(ctx).trim(),
            imageCapable = { model ->
                AiModelCatalog.supportsImageInput(
                    ctx = ctx,
                    providerId = preset.catalogProviderId,
                    modelId = model,
                    trustedModel = curated
                )
            }
        )
        if (picked.isBlank()) return ""
        return validateVisionCandidate(ctx, preset, picked)
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
        val liveModels = liveModels(ctx)
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
        return resolveSlot(
            ctx = ctx,
            slot = AiModelAutoPicker.Slot.SPEECH,
            curated = preset.defaultSpeechModel,
            saved = Prefs.getAiSpeechModel(ctx).trim()
        )
    }

    /**
     * 手动选择有效（仍在实时列表里）时尊重手选值；否则回落到预设推荐值，
     * 再交给 [AiModelAutoPicker] 与实时列表比对自动选择。
     */
    private fun resolveSlot(
        ctx: Context,
        slot: AiModelAutoPicker.Slot,
        curated: String,
        saved: String,
        imageCapable: ((String) -> Boolean?)? = null
    ): String {
        val live = liveModels(ctx)
        val preferred = when {
            !Prefs.isAiManualModelSelectionEnabled(ctx) -> curated
            saved.isBlank() -> curated
            live.isNotEmpty() && saved !in live -> curated
            else -> saved
        }
        if (preferred.isBlank()) return ""
        return AiModelAutoPicker.pick(slot, live, preferred, imageCapable)
    }

    private fun liveModels(ctx: Context): List<String> =
        Prefs.getAiModelsCache(ctx).map { it.trim() }.filter { it.isNotEmpty() }

    fun isChatFollowingMainText(ctx: Context): Boolean =
        Prefs.isAiChatModelFollowingMain(ctx)
}
