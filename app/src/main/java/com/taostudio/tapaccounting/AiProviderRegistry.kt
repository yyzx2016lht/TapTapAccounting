package com.taostudio.tapaccounting

import android.content.Context

/**
 * Built-in AI provider presets. Default models are tuned per provider and can be
 * updated as compatibility is verified.
 *
 * `default*Model` 是“预设首选值”：运行时/设置流程会经 [AiModelDefaults] 与
 * 实时模型列表比对，厂商换代后自动顶替（详见 [AiModelAutoPicker]）。
 */
data class AiProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val createKeyUrl: String,
    val supportsVision: Boolean,
    val supportsCloudSpeech: Boolean,
    val defaultTextModel: String,
    val defaultVisionModel: String,
    val defaultSpeechModel: String,
    val keyHint: String,
    val selectionNotice: String? = null,
    /** models.dev 目录中的提供商 ID，用于模型输入能力标注；null 表示不参与。 */
    val catalogProviderId: String? = null
)

data class AiProviderSetupResult(
    val preset: AiProviderPreset,
    val apiKey: String,
    val models: List<String>
)

object AiProviderRegistry {

    const val PROVIDER_SILICONFLOW = "硅基流动"
    const val PROVIDER_OPENCODE_GO = "OpenCode Go"
    const val PROVIDER_DEEPSEEK = "DeepSeek"
    const val PROVIDER_KIMI = "Kimi"
    const val PROVIDER_QWEN = "通义千问"
    const val PROVIDER_MIMO = "小米MiMo"

    private val presets = listOf(
        AiProviderPreset(
            id = PROVIDER_SILICONFLOW,
            displayName = PROVIDER_SILICONFLOW,
            baseUrl = "https://api.siliconflow.cn",
            createKeyUrl = "https://cloud.siliconflow.cn/account/ak",
            supportsVision = true,
            supportsCloudSpeech = true,
            defaultTextModel = "Qwen/Qwen3-14B",
            defaultVisionModel = "Qwen/Qwen3-VL-30B-A3B-Instruct",
            defaultSpeechModel = "FunAudioLLM/SenseVoiceSmall",
            keyHint = "请输入 API Key（硅基流动一般为 sk- 开头）",
            catalogProviderId = "siliconflow"
        ),
        AiProviderPreset(
            id = PROVIDER_OPENCODE_GO,
            displayName = PROVIDER_OPENCODE_GO,
            baseUrl = "https://opencode.ai/zen/go",
            createKeyUrl = "https://opencode.ai/console",
            supportsVision = true,
            supportsCloudSpeech = false,
            defaultTextModel = "deepseek-v4.1-flash",
            defaultVisionModel = "deepseek-v4.1-flash",
            defaultSpeechModel = "",
            keyHint = "请输入 OpenCode Go API Key（在 opencode.ai/console 创建）",
            selectionNotice = "OpenCode Go 为订阅制，图片记账会消耗 Go 月度额度；语音记账请使用本地离线模型。",
            catalogProviderId = "opencode-go"
        ),
        AiProviderPreset(
            id = PROVIDER_DEEPSEEK,
            displayName = PROVIDER_DEEPSEEK,
            baseUrl = "https://api.deepseek.com",
            createKeyUrl = "https://platform.deepseek.com/api-keys",
            supportsVision = true,
            supportsCloudSpeech = false,
            defaultTextModel = "deepseek-v4-flash",
            defaultVisionModel = "deepseek-v4-flash",
            defaultSpeechModel = "",
            keyHint = "请输入 DeepSeek API Key",
            catalogProviderId = "deepseek"
        ),
        AiProviderPreset(
            id = PROVIDER_KIMI,
            displayName = PROVIDER_KIMI,
            baseUrl = "https://api.moonshot.cn",
            createKeyUrl = "https://platform.moonshot.cn/console/api-keys",
            supportsVision = true,
            supportsCloudSpeech = false,
            defaultTextModel = "kimi-k2.5",
            defaultVisionModel = "kimi-k2.5",
            defaultSpeechModel = "",
            keyHint = "请输入 Kimi API Key",
            selectionNotice = "Kimi 支持文本与图片理解，但不提供当前流程可用的云端语音转写。语音记账请使用本地离线模型。",
            catalogProviderId = "moonshotai"
        ),
        AiProviderPreset(
            id = PROVIDER_QWEN,
            displayName = PROVIDER_QWEN,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode",
            createKeyUrl = "https://bailian.console.aliyun.com/cn-beijing/?tab=model#/api-key",
            supportsVision = true,
            supportsCloudSpeech = true,
            defaultTextModel = "qwen3.5-flash",
            defaultVisionModel = "qwen3.5-flash",
            defaultSpeechModel = "qwen3-asr-flash",
            keyHint = "请输入通义千问 API Key",
            catalogProviderId = "alibaba"
        ),
        AiProviderPreset(
            id = PROVIDER_MIMO,
            displayName = PROVIDER_MIMO,
            baseUrl = "https://api.xiaomimimo.com",
            createKeyUrl = "https://platform.xiaomimimo.com/#/console/api-keys",
            supportsVision = true,
            supportsCloudSpeech = true,
            // mimo-v2.5 / v2.5-pro 于 2026.10.21 10:00 下线（10.14 18:00 起自动转发到 V2.6）。
            // ASR 无后继型号，mimo-v2.5-asr 仍是官方现役语音识别模型，保持不动。
            defaultTextModel = "mimo-v2.6-flash",
            defaultVisionModel = "mimo-v2.6-flash",
            defaultSpeechModel = "mimo-v2.5-asr",
            keyHint = "请输入小米 MiMo API Key",
            catalogProviderId = "xiaomi"
        )
    )

    fun allPresets(): List<AiProviderPreset> = presets

    /** 参与 models.dev 能力目录裁剪的提供商 ID 集合。 */
    fun catalogProviderIds(): Set<String> = presets.mapNotNull { it.catalogProviderId }.toSet()

    fun presetFor(id: String): AiProviderPreset? =
        presets.firstOrNull { it.id == id.trim() }

    fun resolvePreset(ctx: Context): AiProviderPreset {
        val saved = Prefs.getAiProvider(ctx).trim()
        return presetFor(saved) ?: presets.first()
    }

    fun capabilitySummary(preset: AiProviderPreset): String = buildList {
        add("文本")
        if (preset.supportsVision) add("图片")
        if (preset.supportsCloudSpeech) add("云端语音")
    }.joinToString(" · ")

    fun applyDefaultModels(ctx: Context, preset: AiProviderPreset) {
        val resolved = AiModelDefaults.resolve(ctx, preset)
        Prefs.resetChatModelOnProviderChange(ctx)
        Prefs.setAiModel(ctx, resolved.text)
        Prefs.setAiMultiModel(ctx, resolved.text)
        Prefs.setAiModifyModel(ctx, resolved.text)
        Prefs.setAiCategoryRefineModel(ctx, resolved.text)
        Prefs.setAiRuleModel(ctx, resolved.text)
        Prefs.setAiReceiptModel(ctx, resolved.text)
        Prefs.setAiReceiptOcrRefineModel(ctx, resolved.text)
        Prefs.setAiRouterModel(ctx, resolved.text)

        Prefs.setAiReceiptVisionModel(ctx, resolved.vision)
        Prefs.setAiScreenModel(ctx, resolved.vision)

        Prefs.setAiSpeechModel(ctx, resolved.speech)
    }

    fun applyProvider(ctx: Context, preset: AiProviderPreset, apiKey: String, modelsCache: List<String>? = null) {
        // 用户输入 Key 后已拉到实时模型列表：默认值从列表里自动选，避免写死过时型号。
        val resolved = AiModelDefaults.resolve(ctx, preset, modelsCache ?: Prefs.getAiModelsCache(ctx))
        Prefs.applyAiProviderConfigSync(ctx, preset, apiKey.trim(), modelsCache, resolved)
        Prefs.resetChatModelOnProviderChange(ctx)
    }
}
