package com.taostudio.tapaccounting

import android.content.Context

/**
 * 解析提供商各槽位的“推荐默认模型”：
 * 预设值（[AiProviderPreset]）作为首选，结合实时模型列表缓存做自动顶替。
 *
 * 设置流程写入的默认值、关闭手动选择 / 恢复默认模型时的回填都经过这里，
 * 保证用户输入 Key 拉到模型列表后，各槽位拿到的是列表里真实可用的模型。
 */
object AiModelDefaults {

    data class Resolved(
        val text: String,
        val vision: String,
        val speech: String
    )

    fun resolve(
        ctx: Context,
        preset: AiProviderPreset,
        liveModels: List<String> = Prefs.getAiModelsCache(ctx)
    ): Resolved {
        val text = AiModelAutoPicker.pick(
            slot = AiModelAutoPicker.Slot.TEXT,
            liveModels = liveModels,
            preferred = preset.defaultTextModel
        )

        val vision = if (preset.supportsVision && preset.defaultVisionModel.isNotBlank()) {
            AiModelAutoPicker.pick(
                slot = AiModelAutoPicker.Slot.VISION,
                liveModels = liveModels,
                preferred = preset.defaultVisionModel,
                imageCapable = { model ->
                    AiModelCatalog.supportsImageInput(
                        ctx = ctx,
                        providerId = preset.catalogProviderId,
                        modelId = model,
                        trustedModel = preset.defaultVisionModel
                    )
                }
            )
        } else {
            ""
        }

        val speech = if (preset.supportsCloudSpeech && preset.defaultSpeechModel.isNotBlank()) {
            AiModelAutoPicker.pick(
                slot = AiModelAutoPicker.Slot.SPEECH,
                liveModels = liveModels,
                preferred = preset.defaultSpeechModel
            )
        } else {
            ""
        }

        return Resolved(text = text, vision = vision, speech = speech)
    }
}
