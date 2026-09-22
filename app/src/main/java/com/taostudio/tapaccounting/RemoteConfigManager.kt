package com.taostudio.tapaccounting

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程配置（已停用）。
 *
 * P0-6：原先从固定 GitHub Gist 拉取 JSON 并静默写入 AI API Key / URL，无签名、无用户确认。
 * 该能力已不需要，**整体关闭**——不拉取、不写入本地凭据或模型配置。
 * 若将来恢复，必须改为签名校验 + 用户逐项确认，且禁止静默覆盖 Key。
 */
object RemoteConfigManager {

    /** 已停用：留空则 [isConfigUrlConfigured] 为 false，UI 入口自动隐藏。 */
    private const val CONFIG_URL = ""

    data class RemoteConfig(
        @SerializedName("apiKey") val apiKey: String = "",
        @SerializedName("apiUrl") val apiUrl: String = "https://api.siliconflow.cn",
        @SerializedName("provider") val provider: String = "硅基流动",
        @SerializedName("textModelId") val textModelId: String = "",
        @SerializedName("visionModelId") val visionModelId: String = "",
        @SerializedName("onlineSpeechModelId") val onlineSpeechModelId: String = "",
        @SerializedName("modelId") val modelId: String = "Qwen/Qwen3-14B",
        @SerializedName("singleModelId") val singleModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("multiModelId") val multiModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("modifyModelId") val modifyModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("categoryRefineModelId") val categoryRefineModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("routerModelId") val routerModelId: String = "Qwen/Qwen3-8B",
        @SerializedName("queryModelId") val queryModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("ruleModelId") val ruleModelId: String = "Qwen/Qwen3-8B",
        @SerializedName("receiptModelId") val receiptModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("receiptVisionModelId") val receiptVisionModelId: String = "Qwen/Qwen3-VL-30B-A3B-Instruct",
        @SerializedName("ocrRefineModelId") val ocrRefineModelId: String = "Qwen/Qwen3-8B",
        @SerializedName("speechModelId") val speechModelId: String = "FunAudioLLM/SenseVoiceSmall",
        @SerializedName("chatModelId") val chatModelId: String = "Qwen/Qwen3-14B",
        // Legacy hidden-feature flags kept only for backward-compatible parsing.
        @SerializedName("llmRouterEnabled") val llmRouterEnabled: Boolean = false,
        @SerializedName("queryEnabled") val queryEnabled: Boolean = true,
        @SerializedName("thinkingEnabled") val thinkingEnabled: Boolean = true,
        @SerializedName("ocrRefineEnabled") val ocrRefineEnabled: Boolean = true
    )

    fun isConfigUrlConfigured(): Boolean = CONFIG_URL.isNotBlank()

    /** 已停用：不再拉取或应用远程配置。 */
    suspend fun syncIfConfigured(context: Context): Boolean = false

    /** 已停用：不发起网络请求。 */
    suspend fun fetchConfig(): RemoteConfig? = null

    /**
     * 已停用：禁止任何远程来源写入本地 AI Key / URL / 模型配置。
     * 即使误传入 [config] 也绝不写 Prefs（P0-6）。
     */
    fun applyConfig(context: Context, config: RemoteConfig) {
        // intentionally no-op
    }
}
