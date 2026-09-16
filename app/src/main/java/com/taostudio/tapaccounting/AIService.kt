package com.taostudio.tapaccounting

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.AiRule as DbAiRule
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.google.gson.JsonObject
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

// OCR 模式常量
const val OCR_MODE_LOCAL      = 0   // 本地 ML Kit OCR + 文本 AI
const val OCR_MODE_MULTIMODAL = 1   // 直接多模态 AI（发送图片）

/** 聊天入口的二分类结果。 */
internal fun normalizeChatIntent(intent: String): String = when (intent.trim().uppercase(Locale.ROOT)) {
    "BOOKKEEPING", "ACCOUNTING_CREATE" -> "BOOKKEEPING"
    "GENERAL_CHAT" -> "GENERAL_CHAT"
    else -> "BOOKKEEPING"
}

object AIService {
    private const val MAX_AUDIO_INLINE_BYTES = 8L * 1024L * 1024L
    private const val ACCOUNTING_MULTI_LOG_TAG = "AccountingMulti"
    private const val ACCOUNTING_AUDIO_MULTI_LOG_TAG = "AccountingAudioMulti"
    private const val RECEIPT_VISION_LOG_TAG = "ReceiptVision"
    private const val SCREEN_ACCOUNTING_LOG_TAG = "ScreenAccounting"
    private const val ACCOUNTING_ASSISTANT_LOG_TAG = "AccountingAssistant"
    private const val GENERAL_CHAT_LOG_TAG = "GeneralChat"
    private const val SIMPLE_CHAT_LOG_TAG = "SimpleChat"
    private const val AI_IO_LOG_TAG = "AI_IO"
    private const val AI_CACHE_LOG_TAG = "AICache"

    const val MULTI_BILL_PROMPT_DEFAULT = AIPrompts.MULTI_BILL_PROMPT_DEFAULT
    val RULE_EXTRACT_PROMPT_DEFAULT              get() = com.taostudio.tapaccounting.logic.RuleDialogHelper.DEFAULT_RULE_PROMPT
    const val RECEIPT_VISION_RETRY_PROMPT_DEFAULT= AIPrompts.RECEIPT_VISION_RETRY_PROMPT_DEFAULT
    const val IMAGE_ACCOUNTING_PROMPT   = AIPrompts.IMAGE_ACCOUNTING_PROMPT
    const val CHAT_ASSISTANT_PROMPT_DEFAULT      = AIPrompts.CHAT_ASSISTANT_PROMPT_DEFAULT
    private const val MAX_ACCOUNTING_INPUT_CHARS = 12000

    private const val MAX_ASSISTANT_INPUT_CHARS = 4000
    private const val MAX_ASSISTANT_SUMMARY_CHARS = 2500
    private const val API_CONNECT_TIMEOUT_SECONDS = 60L
    private const val API_READ_TIMEOUT_SECONDS = 90L
    private const val API_WRITE_TIMEOUT_SECONDS = 90L
    private const val SPEECH_CONNECT_TIMEOUT_SECONDS = 60L
    private const val SPEECH_READ_TIMEOUT_SECONDS = 180L
    private const val SPEECH_WRITE_TIMEOUT_SECONDS = 180L
    private const val DEFAULT_CUSTOM_REPLY_STYLE_GUIDE =
        "回复风格：按用户自定义要求回复。"
    private fun enableThinkingForAccounting(ctx: Context): Boolean = Prefs.isAiThinkingMultiBillEnabled(ctx)
    private fun enableThinkingForVision(ctx: Context): Boolean = Prefs.isAiThinkingVisionEnabled(ctx)

    fun getDefaultSingleBillPrompt(ctx: Context): String =
        getDefaultMultiBillPrompt(ctx)

    fun getDefaultMultiBillPrompt(ctx: Context): String =
        if (Prefs.isAssetFeatureEnabled(ctx)) {
            AIPrompts.MULTI_BILL_PROMPT_DEFAULT
        } else {
            AIPromptsWithoutAccount.MULTI_BILL_PROMPT_DEFAULT
        }

    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(API_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(API_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(API_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    private val speechClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(SPEECH_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SPEECH_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(SPEECH_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
            .build()
    }

    @Volatile private var cachedApi: Pair<String, SiliconFlowApi>? = null
    @Volatile private var cachedSpeechApi: Pair<String, SiliconFlowApi>? = null

    private fun getApi(ctx: Context): SiliconFlowApi {
        val baseUrl = normalizeBaseUrl(Prefs.getAiUrl(ctx))
        cachedApi?.let { (url, api) -> if (url == baseUrl) return api }
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(sharedClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SiliconFlowApi::class.java)
        cachedApi = baseUrl to api
        return api
    }

    private fun getSpeechApi(ctx: Context): SiliconFlowApi {
        val baseUrl = normalizeBaseUrl(Prefs.getAiUrl(ctx))
        cachedSpeechApi?.let { (url, api) -> if (url == baseUrl) return api }
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(speechClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SiliconFlowApi::class.java)
        cachedSpeechApi = baseUrl to api
        return api
    }

    suspend fun speechToText(ctx: Context, audioFile: File): String? {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) return null
        if (!audioFile.exists() || audioFile.length() <= 44L) return null

        val mimeType = detectSpeechAudioMimeType(audioFile)
        val modelName = AiModelSlots.resolveSpeechModel(ctx)
        if (modelName.isEmpty()) return null

        return try {
            val providerId = Prefs.getAiProvider(ctx)
            val parsed = when (providerId) {
                AiProviderRegistry.PROVIDER_QWEN,
                AiProviderRegistry.PROVIDER_MIMO -> speechToTextViaChatInputAudio(
                    ctx = ctx,
                    apiKey = apiKey,
                    audioFile = audioFile,
                    mimeType = mimeType,
                    modelName = modelName
                )

                else -> {
                    val requestFile = audioFile.asRequestBody(mimeType.toMediaTypeOrNull())
                    val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)
                    val modelPart = MultipartBody.Part.createFormData("model", modelName)
                    parseSpeechText(
                        getSpeechApi(ctx).transcribe("Bearer $apiKey", modelPart, filePart)
                    )
                }
            }
            if (!parsed.isNullOrBlank()) {
                Logger.d(ctx, "AIService", "Cloud ASR success. model=$modelName, mime=$mimeType, textLen=${parsed.length}")
            } else {
                Logger.d(ctx, "AIService", "Cloud ASR empty response. model=$modelName")
            }
            parsed
        } catch (e: Exception) {
            Logger.dPriv(
                ctx,
                "AIService",
                "Cloud ASR failed. model=$modelName, errType=${e.javaClass.simpleName}",
                "Cloud ASR failure detail=${detailedHttpError(e)}"
            )
            null
        }
    }

    private suspend fun speechToTextViaChatInputAudio(
        ctx: Context,
        apiKey: String,
        audioFile: File,
        mimeType: String,
        modelName: String
    ): String? {
        if (audioFile.length() > MAX_AUDIO_INLINE_BYTES) return null
        val audioBase64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        val requestJson = JsonObject().apply {
            addProperty("model", modelName)
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "input_audio")
                            add("input_audio", JsonObject().apply {
                                addProperty("data", "data:$mimeType;base64,$audioBase64")
                            })
                        })
                    })
                })
            })
        }
        if (Prefs.getAiProvider(ctx) == AiProviderRegistry.PROVIDER_MIMO) {
            requestJson.add("asr_options", JsonObject().apply {
                addProperty("language", "auto")
            })
        }
        val response = getApi(ctx).chatRaw(
            "Bearer $apiKey",
            adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
        )
        return response.choices.firstOrNull()?.message?.content?.trim()
    }

    private fun parseSpeechText(response: AudioResponse): String? =
        listOf(response.text, response.result, response.transcript)
            .firstOrNull { !it.isNullOrBlank() }?.trim()

    suspend fun analyzeAccounting(
        ctx: Context,
        userInput: String,
        onProgress: ((String) -> Unit)? = null,
        isFromChat: Boolean = false,
        chatTurns: List<ChatTurn> = emptyList()
    ): JSONObject? {
        val safeUserInput = shortenForModel(userInput, MAX_ACCOUNTING_INPUT_CHARS)
        Logger.d(ctx, AI_IO_LOG_TAG, "[记账] USER: ${safeUserInput.take(2000)}")
        Logger.d(ctx, "AIService", "Accounting analyze request: inputLen=${safeUserInput.length}, fromChat=$isFromChat")
        val apiKey = Prefs.getAiKey(ctx)
        val enableThinking = enableThinkingForAccounting(ctx)
        val model = if (isFromChat) {
            AiModelSlots.resolveChatModel(ctx).ifBlank { AiModelSlots.resolveTextModel(ctx) }
        } else {
            AiModelSlots.resolveTextModel(ctx)
        }
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        val promptContext = buildAccountingPromptContext(ctx)

        val promptRules = loadActivePromptRules(ctx)
        val matchedPromptRules = if (Prefs.isAiPromptCorrectionEnabled(ctx)) {
            findMatchedPromptRules(safeUserInput, promptRules)
        } else {
            emptyList()
        }

        val systemPrompt = buildAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isFromChat = isFromChat
        )
        val aiName = if (isFromChat) Prefs.getAiChatName(ctx).trim().ifBlank { "小记" } else ""
        val userPrompt = buildAccountingUserPrompt(
            userInput = safeUserInput,
            promptContext = promptContext,
            matchedPromptRules = matchedPromptRules,
            assetFeatureEnabled = promptContext.assetFeatureEnabled,
            isFromChat = isFromChat,
            aiName = aiName
        )

        return try {
            val requestJson = if (isFromChat && chatTurns.isNotEmpty()) {
                buildMultiTurnChatRequest(
                    model = model,
                    temperature = 0.3,
                    systemPrompt = systemPrompt,
                    historyTurns = chatTurns,
                    userText = userPrompt,
                    enableThinking = enableThinking
                )
            } else {
                buildTextChatRequest(
                    model = model,
                    temperature = 0.3,
                    systemPrompt = systemPrompt,
                    userText = userPrompt,
                    enableThinking = enableThinking
                )
            }
            onProgress?.invoke("正在拆分金额和备注...")
            val content = requestAccountingContentStreamed(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                onProgress = onProgress,
                emitTextDelta = true,
                logReasoning = enableThinking,
                reasoningLogTag = ACCOUNTING_MULTI_LOG_TAG
            )
            Logger.d(ctx, "AIService", "Accounting response received: len=${content.length}")
            Logger.d(ctx, AI_IO_LOG_TAG, "[记账] AI: ${content.take(3000)}")

            val result = parseAnalyzeResult(content)

            result?.let { root ->
                enforceExpenseForReceiptSummaries(root, safeUserInput)
                normalizeAccountingWithLocalRules(ctx, root, promptContext, safeUserInput)
                if (collapseSingleTotalSplitBills(root, safeUserInput)) {
                    Logger.d(ctx, "AIService", "Collapsed split bills into one total bill (userInput had a single number)")
                }
                Logger.d(ctx, AI_IO_LOG_TAG, "[记账] FINAL: ${root.toString().take(3000)}")
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "Accounting request failed: errType=${e.javaClass.simpleName}",
                "Accounting request failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    suspend fun analyzeAccountingFromAudio(
        ctx: Context,
        audioFile: File,
        audioFormat: String = "wav",
        onProgress: ((String) -> Unit)? = null,
        chatTurns: List<ChatTurn> = emptyList()
    ): JSONObject? {
        if (!audioFile.exists() || audioFile.length() <= 44L) {
            throw IllegalArgumentException("音频文件无效")
        }
        if (audioFile.length() > MAX_AUDIO_INLINE_BYTES) {
            throw IllegalArgumentException("音频文件过大（>${MAX_AUDIO_INLINE_BYTES / 1024 / 1024}MB）")
        }
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        val model = AiModelSlots.resolveChatModel(ctx).ifBlank { AiModelSlots.resolveTextModel(ctx) }
        val promptContext = buildAccountingPromptContext(ctx)
        val systemPrompt = buildAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isFromChat = true
        )
        val userPrompt = buildAccountingUserPrompt(
            userInput = "请直接听取随附语音，提取其中的记账信息；如果不是记账内容，按 no_bill + reply 输出。",
            promptContext = promptContext,
            matchedPromptRules = emptyList(),
            assetFeatureEnabled = promptContext.assetFeatureEnabled,
            isFromChat = true,
            aiName = Prefs.getAiChatName(ctx).trim().ifBlank { "小记" }
        )
        val audioBase64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        val dataUrl = "data:audio/$audioFormat;base64,$audioBase64"
        val requestJson = buildMultiTurnAudioChatRequest(
            model = model,
            temperature = 0.2,
            systemPrompt = systemPrompt,
            historyTurns = chatTurns,
            audioBase64 = dataUrl,
            audioFormat = audioFormat,
            userText = userPrompt,
            stream = true,
            enableThinking = enableThinkingForAccounting(ctx)
        )
        onProgress?.invoke("正在听语音...")
        val content = requestAccountingContentStreamed(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = requestJson,
            onProgress = onProgress,
            emitTextDelta = true,
            logReasoning = enableThinkingForAccounting(ctx),
            reasoningLogTag = ACCOUNTING_AUDIO_MULTI_LOG_TAG
        )
        Logger.d(ctx, AI_IO_LOG_TAG, "[语音记账] AI: ${content.take(3000)}")
        val result = parseAnalyzeResult(content)
        result?.let { root ->
            if (!root.optBoolean("no_bill", false)) {
                normalizeAccountingWithLocalRules(ctx, root, promptContext, "语音输入")
                // 语音路径拿不到本地转写文本，单数字总价护栏无法在此生效，依赖 prompt 侧规则
            }
        }
        return result
    }

    suspend fun analyzeReceiptByImage(
        ctx: Context,
        imageBase64: String,
        mimeType: String = "image/jpeg",
        supplementText: String = ""
    ): String {
        Logger.d(ctx, "AIService", "analyzeReceiptByImage: multimodal mode")
        Logger.d(ctx, AI_IO_LOG_TAG, "[票据图片] USER: [图片输入]")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = AiModelSlots.resolveVisionModel(ctx).ifBlank { AiModelSlots.resolveTextModel(ctx) }
        val promptContext = buildAccountingPromptContext(ctx)
        val systemPrompt = AIPrompts.RECEIPT_VISION_RETRY_PROMPT_DEFAULT +
            AIPrompts.buildReceiptVisionPaymentMethodRule(
                promptContext.assetFeatureEnabled,
                promptContext.assetNames
            ) +
            AIPrompts.buildSameItemMergeRule()
        val dataUrl = "data:$mimeType;base64,$imageBase64"
        val userText = buildString {
            append(AIPrompts.receiptVisionUserInstruction(1))
            val supplement = supplementText.trim()
            if (supplement.isNotBlank()) {
                append("\n\n用户补充说明（优先参考）：\n")
                append(supplement)
            }
        }

        val requestJson = buildVisionChatRequest(
            model = model,
            temperature = 0.1,
            systemPrompt = systemPrompt,
            dataUrl = dataUrl,
            userText = userText,
            enableThinking = enableThinkingForVision(ctx)
        )

        return try {
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = enableThinkingForVision(ctx),
                reasoningLogTag = RECEIPT_VISION_LOG_TAG
            )
            if (!streamed.completed) {
                throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("图片识别流式回复未完整结束")
            }
            val content = ReceiptImageInputHelper.normalizeVisionSummary(streamed.content)
            Logger.d(ctx, "AIService", "Receipt multimodal response received: contentLen=${content.length}")
            Logger.d(ctx, AI_IO_LOG_TAG, "[票据图片] AI: ${content.take(3000)}")
            content
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "analyzeReceiptByImage failed: errType=${e.javaClass.simpleName}",
                "Receipt image failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    suspend fun probeVisionInputSupport(ctx: Context, modelName: String? = null): Boolean {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) return false
        val model = modelName?.takeIf { it.isNotBlank() } ?: AiModelSlots.resolveVisionModel(ctx)
        if (model.isBlank()) return false
        return runCatching {
            Logger.d(ctx, "AIService", "Probing vision input support. model=$model")
            val requestJson = buildVisionChatRequest(
                model = model,
                temperature = 0.1,
                dataUrl = "data:image/png;base64,${buildProbeImageBase64(ctx)}",
                userText = "Reply with OK only.",
                enableThinking = enableThinkingForVision(ctx)
            )
            val response = getApi(ctx).chatRaw(
                "Bearer $apiKey",
                adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
            )
            Logger.d(ctx, "AIService", "Vision input support probe succeeded. model=$model")
            response.choices.firstOrNull()?.message?.content != null
        }.getOrElse {
            Logger.d(ctx, "AIService", "Vision input support probe failed. model=$model, errType=${it.javaClass.simpleName}")
            false
        }
    }

    suspend fun analyzeScreenAccountingByImage(
        ctx: Context,
        imageBase64: String,
        mimeType: String = "image/jpeg",
        sourceKind: String = "screen_capture",
        supplementText: String = "",
        onProgress: ((String) -> Unit)? = null,
        isFromChat: Boolean = false,
        chatTurns: List<ChatTurn> = emptyList(),
        quickScreenMode: Boolean = false
    ): JSONObject? {
        Logger.d(ctx, "AIService", "analyzeScreenAccountingByImage: multimodal accounting mode source=$sourceKind fromChat=$isFromChat quick=$quickScreenMode")
        Logger.d(ctx, AI_IO_LOG_TAG, "[截图记账] USER: [屏幕截图]")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = AiModelSlots.resolveVisionModel(ctx)
        if (model.isBlank()) throw IllegalArgumentException("请先在智能配置中选择视觉重试模型")

        val promptContext = buildAccountingPromptContext(ctx)
        val systemPrompt = buildScreenAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isFromChat = isFromChat,
            quickScreenMode = quickScreenMode
        )

        val taskInstruction = AIPrompts.buildScreenAccountingTaskInstruction(
            imageCount = 1,
            isFromChat = isFromChat,
            supplementText = supplementText,
            quickScreenMode = quickScreenMode
        )
        // 加载本地纠错规则（用 supplementText 匹配，无补充说明时跳过）
        val matchedPromptRules = if (supplementText.isNotBlank() && Prefs.isAiPromptCorrectionEnabled(ctx)) {
            val promptRules = loadActivePromptRules(ctx)
            findMatchedPromptRules(supplementText, promptRules)
        } else {
            emptyList()
        }
        val userText = buildScreenAccountingUserText(
            promptContext = promptContext,
            taskInstruction = taskInstruction,
            matchedPromptRules = matchedPromptRules
        )
        val attachment = MultimodalAttachmentPart(base64 = imageBase64, mime = mimeType)
        val requestJson = if (isFromChat && chatTurns.isNotEmpty()) {
            buildMultiTurnMultimodalChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = systemPrompt,
                historyTurns = chatTurns,
                attachments = listOf(attachment),
                userText = userText,
                enableThinking = enableThinkingForVision(ctx)
            )
        } else {
            buildMultimodalChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = systemPrompt,
                attachments = listOf(attachment),
                userText = userText,
                enableThinking = enableThinkingForVision(ctx)
            )
        }

        return try {
            onProgress?.invoke("正在识别图片中的交易...")
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = enableThinkingForVision(ctx),
                reasoningLogTag = SCREEN_ACCOUNTING_LOG_TAG,
                onContentDelta = if (onProgress == null) null else { delta ->
                    if (delta.isNotBlank()) onProgress("AI_STREAM_TEXT::$delta")
                },
                onProgressChars = null
            )
            if (!streamed.completed) {
                throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("截图记账流式回复未完整结束")
            }
            val content = streamed.content
            Logger.d(ctx, "AIService", "Screen accounting multimodal response: $content")
            Logger.d(ctx, AI_IO_LOG_TAG, "[截图记账] AI: ${content.take(3000)}")
            val result = parseAnalyzeResult(content)

            result?.let { root ->
                normalizeAccountingWithLocalRules(ctx, root, promptContext, supplementText)
                if (!isFromChat && !quickScreenMode) {
                    markVisualAccountingReviewDraft(
                        root = root,
                        sourceKind = sourceKind,
                        naturalSummary = supplementText.trim(),
                        includePaymentMethod = promptContext.assetFeatureEnabled
                    )
                }
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "analyzeScreenAccountingByImage failed: errType=${e.javaClass.simpleName}",
                "Screen accounting image failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    /**
     * 多图直出记账：一次性把所有图片发给多模态，直接返回 JSON 结果。
     */
    suspend fun analyzeScreenAccountingByImages(
        ctx: Context,
        images: List<Pair<String, String>>,
        sourceKind: String = "receipt_image",
        supplementText: String = "",
        onProgress: ((String) -> Unit)? = null,
        isFromChat: Boolean = false,
        chatTurns: List<ChatTurn> = emptyList(),
        quickScreenMode: Boolean = false
    ): JSONObject? {
        Logger.d(ctx, "AIService", "analyzeScreenAccountingByImages: multi-image multimodal accounting, count=${images.size} fromChat=$isFromChat quick=$quickScreenMode")
        if (images.isEmpty()) throw IllegalArgumentException("图片列表不能为空")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = AiModelSlots.resolveVisionModel(ctx)
        if (model.isBlank()) throw IllegalArgumentException("请先在智能配置中选择视觉重试模型")

        val promptContext = buildAccountingPromptContext(ctx)
        val systemPrompt = buildScreenAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isFromChat = isFromChat,
            quickScreenMode = quickScreenMode
        )

        val attachments = images.map { (base64, mime) ->
            MultimodalAttachmentPart(base64 = base64, mime = mime)
        }
        val taskInstruction = AIPrompts.buildScreenAccountingTaskInstruction(
            imageCount = images.size,
            isFromChat = isFromChat,
            supplementText = supplementText,
            quickScreenMode = quickScreenMode
        )
        val matchedPromptRules = if (supplementText.isNotBlank() && Prefs.isAiPromptCorrectionEnabled(ctx)) {
            val promptRules = loadActivePromptRules(ctx)
            findMatchedPromptRules(supplementText, promptRules)
        } else {
            emptyList()
        }
        val userText = buildScreenAccountingUserText(
            promptContext = promptContext,
            taskInstruction = taskInstruction,
            matchedPromptRules = matchedPromptRules
        )
        val requestJson = if (isFromChat && chatTurns.isNotEmpty()) {
            buildMultiTurnMultimodalChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = systemPrompt,
                historyTurns = chatTurns,
                attachments = attachments,
                userText = userText,
                enableThinking = enableThinkingForVision(ctx)
            )
        } else {
            buildMultimodalChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = systemPrompt,
                attachments = attachments,
                userText = userText,
                enableThinking = enableThinkingForVision(ctx)
            )
        }

        return try {
            onProgress?.invoke("正在识别图片中的交易...")
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = enableThinkingForVision(ctx),
                reasoningLogTag = SCREEN_ACCOUNTING_LOG_TAG,
                onContentDelta = if (onProgress == null) null else { delta ->
                    if (delta.isNotBlank()) onProgress("AI_STREAM_TEXT::$delta")
                },
                onProgressChars = null
            )
            if (!streamed.completed) {
                throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("多图记账流式回复未完整结束")
            }
            val content = streamed.content
            Logger.d(ctx, "AIService", "Multi-image accounting response: $content")
            val result = parseAnalyzeResult(content)

            result?.let { root ->
                normalizeAccountingWithLocalRules(ctx, root, promptContext, supplementText)
                if (!isFromChat && !quickScreenMode) {
                    markVisualAccountingReviewDraft(
                        root = root,
                        sourceKind = sourceKind,
                        naturalSummary = supplementText.trim(),
                        includePaymentMethod = promptContext.assetFeatureEnabled
                    )
                }
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "analyzeScreenAccountingByImages failed: errType=${e.javaClass.simpleName}",
                "Multi-image accounting failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    /**
     * 多图自然语言摘要：一次性把所有图片发给多模态，返回文字摘要。
     */
    suspend fun analyzeReceiptByImages(
        ctx: Context,
        images: List<Pair<String, String>>,
        supplementText: String = ""
    ): String {
        Logger.d(ctx, "AIService", "analyzeReceiptByImages: multi-image multimodal OCR, count=${images.size}")
        if (images.isEmpty()) throw IllegalArgumentException("图片列表不能为空")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = AiModelSlots.resolveVisionModel(ctx).ifBlank { AiModelSlots.resolveTextModel(ctx) }
        val promptContext = buildAccountingPromptContext(ctx)
        val systemPrompt = AIPrompts.RECEIPT_VISION_RETRY_PROMPT_DEFAULT +
            AIPrompts.buildReceiptVisionPaymentMethodRule(
                promptContext.assetFeatureEnabled,
                promptContext.assetNames
            ) +
            AIPrompts.buildSameItemMergeRule()
        val attachments = images.map { (base64, mime) ->
            MultimodalAttachmentPart(base64 = base64, mime = mime)
        }
        val userText = buildString {
            append(AIPrompts.receiptVisionUserInstruction(images.size))
            val supplement = supplementText.trim()
            if (supplement.isNotBlank()) {
                append("\n\n用户补充说明（优先参考）：\n")
                append(supplement)
            }
        }

        val requestJson = buildMultimodalChatRequest(
            model = model,
            temperature = 0.1,
            systemPrompt = systemPrompt,
            attachments = attachments,
            userText = userText,
            enableThinking = enableThinkingForVision(ctx)
        )

        return try {
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = enableThinkingForVision(ctx),
                reasoningLogTag = RECEIPT_VISION_LOG_TAG
            )
            if (!streamed.completed) {
                throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("多图识别流式回复未完整结束")
            }
            val content = ReceiptImageInputHelper.normalizeVisionSummary(streamed.content)
            Logger.d(ctx, "AIService", "Multi-image receipt response received: contentLen=${content.length}")
            content
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "analyzeReceiptByImages failed: errType=${e.javaClass.simpleName}",
                "Multi-image receipt failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    suspend fun fetchModels(ctx: Context, apiKey: String): List<String> =
        fetchModelsWithDetails(Prefs.getAiUrl(ctx), apiKey)

    suspend fun fetchModelsForProvider(
        preset: AiProviderPreset,
        apiKey: String
    ): List<String> {
        return runCatching {
            fetchModelsWithDetails(preset.baseUrl, apiKey)
                .takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("模型列表为空")
        }.getOrElse { modelListError ->
            val detail = modelListError.message.orEmpty()
            if (detail.contains("401") || detail.contains("403")) throw modelListError
            verifyProviderConnection(preset, apiKey)
            listOf(
                preset.defaultTextModel,
                preset.defaultVisionModel,
                preset.defaultSpeechModel
            ).filter { it.isNotBlank() }.distinct()
        }
    }

    private fun verifyProviderConnection(
        preset: AiProviderPreset,
        apiKey: String
    ) {
        val requestJson = buildTextChatRequest(
            model = preset.defaultTextModel,
            temperature = 0.1,
            userText = "Reply with OK only.",
            enableThinking = false
        ).let {
            adaptChatRequestForProvider(preset.id, it)
        }
        val request = Request.Builder()
            .url(normalizeBaseUrl(preset.baseUrl) + "v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                }
            }
    }

    suspend fun fetchModelsWithDetails(url: String, apiKey: String): List<String> {
        var baseUrl = url
        if (baseUrl.isEmpty()) baseUrl = "https://api.siliconflow.cn/"
        if (!baseUrl.endsWith("/")) baseUrl += "/"
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val rawRequest = Request.Builder()
            .url(baseUrl + "v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        val rawResponse = client.newCall(rawRequest).execute()
        if (!rawResponse.isSuccessful) {
            throw IllegalStateException("HTTP ${rawResponse.code}: ${rawResponse.message}")
        }

        val bodyText = rawResponse.body?.string().orEmpty()
        val parsed = runCatching {
            val root = JSONObject(bodyText)
            val data = root.optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isNotEmpty()) add(id)
                }
            }
        }.getOrElse { throw IllegalStateException("模型列表解析失败", it) }

        return parsed.sorted()
    }

    /**
     * 聊天入口二分类。模型只判断当前输入是否是新增记账，具体账单字段交给
     * analyzeAccounting，普通对话交给聊天模型。
     */
    suspend fun classifyIntent(
        ctx: Context,
        userText: String,
        images: List<Pair<String, String>> = emptyList()
    ): String {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) return "BOOKKEEPING"
        val model = AiModelSlots.resolveVisionModel(ctx).ifBlank { AiModelSlots.resolveTextModel(ctx) }
        if (model.isBlank()) return "BOOKKEEPING"

        val routerUserText = userText.trim().ifBlank {
            if (images.isNotEmpty()) "（用户未附带文字，请根据图片内容判断意图）" else ""
        }
        val requestJson = if (images.isEmpty()) {
            buildTextChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = AIPrompts.INTENT_ROUTER_PROMPT_DEFAULT,
                userText = routerUserText,
                jsonObjectResponse = true,
                enableThinking = false
            )
        } else {
            val attachments = images.map { (base64, mime) ->
                MultimodalAttachmentPart(base64 = base64, mime = mime)
            }
            buildMultimodalChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = AIPrompts.INTENT_ROUTER_PROMPT_DEFAULT,
                attachments = attachments,
                userText = routerUserText,
                jsonObjectResponse = true,
                enableThinking = false
            )
        }

        return try {
            val content = requestAccountingContentStreamed(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                onProgress = null,
                emitTextDelta = false,
                logReasoning = false,
                reasoningLogTag = if (images.isEmpty()) "IntentRouter" else "IntentRouterVision"
            )
            val cleaned = cleanJsonString(content)
            val jsonText = extractFirstJsonObjectText(cleaned)
            val json = runCatching { jsonText?.let { org.json.JSONObject(it) } }.getOrNull()
            val intent = json?.optString("intent", "BOOKKEEPING") ?: "BOOKKEEPING"
            Logger.d(
                ctx,
                "AIService",
                "classifyIntent: input=${routerUserText.take(50)} images=${images.size}, result=$intent"
            )
            normalizeChatIntent(intent)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "classifyIntent failed: ${e.message}, fallback to BOOKKEEPING")
            "BOOKKEEPING"
        }
    }

    suspend fun simpleChat(ctx: Context, prompt: String): String {
        val apiKey = Prefs.getAiKey(ctx)
        val model = AiModelSlots.resolveTextModel(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        val request = ChatRequest(
            model = model,
            messages = listOf(MessageUnion.Text(Message("user", prompt))),
            response_format = null
        )
        val streamed = requestChatContentStreamedWithReasoning(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = buildRawRequest(request),
            logReasoning = true,
            reasoningLogTag = SIMPLE_CHAT_LOG_TAG
        )
        if (!streamed.completed) {
            throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("简单聊天流式回复未完整结束")
        }
        return streamed.content
    }

    suspend fun generateAccountingAssistantReply(
        ctx: Context,
        userInput: String,
        billSummary: String = "",
        extractorReplyHint: String = "",
        chatTurns: List<ChatTurn> = emptyList()
    ): String {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        Logger.d(ctx, AI_IO_LOG_TAG, "[助手] USER: ${userInput.take(2000)}")

        val model = AiModelSlots.resolveChatModel(ctx)
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val safeBillSummary = shortenForModel(billSummary, MAX_ASSISTANT_SUMMARY_CHARS)
        val safeReplyHint = shortenForModel(extractorReplyHint, MAX_ASSISTANT_INPUT_CHARS, preserveTail = false)
        val systemPrompt = buildAssistantSystemPrompt(
            ctx = ctx,
            defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
        )
        val userPrompt = buildAccountingAssistantUserPrompt(
            userInput = safeUserInput,
            billSummary = safeBillSummary,
            extractorReplyHint = safeReplyHint
        )

        val requestJson = buildMultiTurnChatRequest(
            model = model,
            temperature = 0.7,
            systemPrompt = systemPrompt,
            historyTurns = chatTurns,
            userText = userPrompt,
            stream = true,
            enableThinking = false
        )
        val streamed = requestChatContentStreamedWithReasoning(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = requestJson,
            logReasoning = false,
            reasoningLogTag = ACCOUNTING_ASSISTANT_LOG_TAG
        )
        val reply = if (streamed.completed) streamed.content.trim() else ""
        if (reply.isNotBlank()) Logger.d(ctx, AI_IO_LOG_TAG, "[助手] AI: ${reply.take(3000)}")
        return reply
    }

    suspend fun generateGeneralChatReply(
        ctx: Context,
        userInput: String,
        chatTurns: List<ChatTurn> = emptyList(),
        replyGuideHint: String = "",
        accountingCasualMode: Boolean = false,
        openConversationMode: Boolean = false,
        onDelta: ((String) -> Unit)? = null
    ): StreamResult {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        Logger.d(ctx, AI_IO_LOG_TAG, "[聊天] USER: ${userInput.take(2000)}")

        val model = AiModelSlots.resolveChatModel(ctx)
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val systemPrompt = when {
            openConversationMode -> buildOpenConversationSystemPrompt(ctx)
            accountingCasualMode -> buildAccountingCasualChatSystemPrompt(
                ctx = ctx,
                defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
            )
            else -> buildAssistantSystemPrompt(
                ctx = ctx,
                defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
            )
        }
        val safeReplyGuideHint = shortenForModel(replyGuideHint, 400, preserveTail = false)
        val userPrompt = buildString {
            append(safeUserInput)
            if (safeReplyGuideHint.isNotBlank()) {
                append("\n\n补充参考（仅供你组织回复语气，不要逐字复述）：")
                append(safeReplyGuideHint)
            }
        }
        val requestJson = buildMultiTurnChatRequest(
            model = model,
            temperature = if (openConversationMode) 0.8 else 0.7,
            systemPrompt = systemPrompt,
            historyTurns = chatTurns,
            userText = userPrompt,
            stream = true,
            enableThinking = false
        )
        val streamed = requestChatContentStreamedWithReasoning(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = requestJson,
            logReasoning = false,
            reasoningLogTag = GENERAL_CHAT_LOG_TAG,
            onContentDelta = onDelta
        )
        val result = streamed.copy(content = streamed.content.trim())
        if (result.completed && result.content.isNotBlank()) {
            Logger.d(ctx, AI_IO_LOG_TAG, "[聊天] AI: ${result.content.take(3000)}")
        }
        return result
    }

    suspend fun generateGeneralChatReplyWithImages(
        ctx: Context,
        userInput: String,
        images: List<Pair<String, String>>,
        chatTurns: List<ChatTurn> = emptyList(),
        accountingCasualMode: Boolean = false,
        openConversationMode: Boolean = false,
        onDelta: ((String) -> Unit)? = null
    ): StreamResult {
        require(images.isNotEmpty()) { "images must not be empty" }
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        Logger.d(ctx, AI_IO_LOG_TAG, "[聊天附件] USER: ${userInput.take(2000)} attachments=${images.size}")

        val model = AiModelCapabilities.chatMultimodalModel(ctx)
        val hadPdfAttachment = userInput.contains(ChatAttachmentHelper.PDF_PAYLOAD_MARKER) ||
            images.any { ChatAttachmentHelper.isPdfMime(it.second) }
        val safeUserInput = userInput
            .replace(ChatAttachmentHelper.PDF_PAYLOAD_MARKER, "")
            .trim()
        val systemPrompt = when {
            openConversationMode -> buildOpenConversationSystemPrompt(ctx)
            accountingCasualMode -> buildAccountingCasualChatSystemPrompt(
                ctx = ctx,
                defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
            )
            else -> buildAssistantSystemPrompt(
                ctx = ctx,
                defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
            )
        }
        val cleanedUserInput = shortenForModel(safeUserInput, MAX_ASSISTANT_INPUT_CHARS)
        // Keep the proven compatibility path: PDF pages are rasterized before
        // sending, because several OpenAI-compatible providers reject file parts.
        val apiAttachments = expandPdfAttachmentsForVisionApi(ctx, images)
        val mimes = apiAttachments.map { it.second }
        val userPrompt = cleanedUserInput.ifBlank {
            when {
                hadPdfAttachment ->
                    ctx.getString(R.string.chat_pdf_default_prompt)
                mimes.all { it.startsWith("image/") } && mimes.size == 1 ->
                    "用户发来一张图片，请结合图片内容自然回复。"
                mimes.all { it.startsWith("image/") } ->
                    "用户发来${mimes.size}张图片，请结合图片内容自然回复。"
                else ->
                    "用户发来${ChatAttachmentHelper.attachmentSummaryLabel(mimes)}，请结合附件内容自然回复。"
            }
        }

        // 按 MIME 选择 image_url / video_url / input_audio / file
        val attachments = apiAttachments.map { (base64, mime) ->
            MultimodalAttachmentPart(base64 = base64, mime = mime)
        }
        val requestJson = buildMultiTurnMultimodalChatRequest(
            model = model,
            temperature = if (openConversationMode) 0.8 else 0.7,
            systemPrompt = systemPrompt,
            historyTurns = chatTurns,
            attachments = attachments,
            userText = userPrompt,
            stream = true,
            enableThinking = enableThinkingForVision(ctx)
        )
        val adaptedRequest = adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
        val streamed = requestChatContentStreamedWithReasoning(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = adaptedRequest,
            logReasoning = enableThinkingForVision(ctx),
            reasoningLogTag = GENERAL_CHAT_LOG_TAG,
            onContentDelta = onDelta
        )
        val result = streamed.copy(content = streamed.content.trim())
        if (result.completed && result.content.isNotBlank()) {
            Logger.d(ctx, AI_IO_LOG_TAG, "[聊天图片] AI: ${result.content.take(3000)}")
        }
        return result
    }

    /**
     * 聊天场景语音直发：把音频直接发给多模态模型，不再先 ASR 转文字。
     */
    suspend fun generateGeneralChatReplyWithAudio(
        ctx: Context,
        audioFile: File,
        audioFormat: String = "wav",
        chatTurns: List<ChatTurn> = emptyList(),
        openConversationMode: Boolean = false,
        onDelta: ((String) -> Unit)? = null
    ): StreamResult {
        if (!audioFile.exists() || audioFile.length() <= 44L) {
            throw IllegalArgumentException("音频文件无效")
        }
        if (audioFile.length() > MAX_AUDIO_INLINE_BYTES) {
            throw IllegalArgumentException("音频文件过大（>${MAX_AUDIO_INLINE_BYTES / 1024 / 1024}MB）")
        }
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        Logger.d(ctx, AI_IO_LOG_TAG, "[聊天语音] USER: [音频输入] file=${audioFile.name}, size=${audioFile.length()}")

        val model = AiModelSlots.resolveChatModel(ctx)
        val audioBase64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        val dataUrl = "data:audio/$audioFormat;base64,$audioBase64"
        val systemPrompt = if (openConversationMode) {
            buildOpenConversationSystemPrompt(ctx)
        } else {
            buildAssistantSystemPrompt(
                ctx = ctx,
                defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
            )
        }

        val requestJson = buildMultiTurnAudioChatRequest(
            model = model,
            temperature = if (openConversationMode) 0.8 else 0.7,
            systemPrompt = systemPrompt,
            historyTurns = chatTurns,
            audioBase64 = dataUrl,
            audioFormat = audioFormat,
            stream = true,
            enableThinking = false
        )
        val adaptedRequest = adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
        val streamed = requestChatContentStreamedWithReasoning(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = adaptedRequest,
            logReasoning = false,
            reasoningLogTag = GENERAL_CHAT_LOG_TAG,
            onContentDelta = onDelta
        )
        val result = streamed.copy(content = streamed.content.trim())
        if (result.completed && result.content.isNotBlank()) {
            Logger.d(ctx, AI_IO_LOG_TAG, "[聊天语音] AI: ${result.content.take(3000)}")
        }
        return result
    }

    /**
     * 聊天场景视频直发：把视频直接发给多模态模型。
     */
    suspend fun generateGeneralChatReplyWithVideo(
        ctx: Context,
        videoFile: File,
        videoMime: String = "video/mp4",
        chatTurns: List<ChatTurn> = emptyList(),
        openConversationMode: Boolean = false,
        onDelta: ((String) -> Unit)? = null
    ): StreamResult {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            throw IllegalArgumentException("视频文件无效")
        }
        val maxVideoBytes = 50L * 1024L * 1024L
        if (videoFile.length() > maxVideoBytes) {
            throw IllegalArgumentException("视频文件过大（>${maxVideoBytes / 1024 / 1024}MB）")
        }
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        Logger.d(ctx, AI_IO_LOG_TAG, "[聊天视频] USER: [视频输入] file=${videoFile.name}, size=${videoFile.length()}")

        val model = AiModelSlots.resolveVisionModel(ctx).ifBlank { AiModelSlots.resolveChatModel(ctx) }
        val videoBase64 = Base64.encodeToString(videoFile.readBytes(), Base64.NO_WRAP)
        val dataUrl = "data:$videoMime;base64,$videoBase64"
        val systemPrompt = if (openConversationMode) {
            buildOpenConversationSystemPrompt(ctx)
        } else {
            buildAssistantSystemPrompt(
                ctx = ctx,
                defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
            )
        }

        val requestJson = buildMultiTurnVideoChatRequest(
            model = model,
            temperature = if (openConversationMode) 0.8 else 0.7,
            systemPrompt = systemPrompt,
            historyTurns = chatTurns,
            videoDataUrl = dataUrl,
            userText = "用户发来一个视频，请结合视频内容自然回复。",
            stream = true,
            enableThinking = enableThinkingForVision(ctx)
        )
        val adaptedRequest = adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
        val streamed = requestChatContentStreamedWithReasoning(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = adaptedRequest,
            logReasoning = enableThinkingForVision(ctx),
            reasoningLogTag = GENERAL_CHAT_LOG_TAG,
            onContentDelta = onDelta
        )
        val result = streamed.copy(content = streamed.content.trim())
        if (result.completed && result.content.isNotBlank()) {
            Logger.d(ctx, AI_IO_LOG_TAG, "[聊天视频] AI: ${result.content.take(3000)}")
        }
        return result
    }

    suspend fun streamAccountingAssistantReply(
        ctx: Context,
        userInput: String,
        billSummary: String = "",
        extractorReplyHint: String = "",
        chatTurns: List<ChatTurn> = emptyList(),
        onDelta: (String) -> Unit
    ): Boolean {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        Logger.d(ctx, AI_IO_LOG_TAG, "[助手流] USER: ${userInput.take(2000)}")

        val model = AiModelSlots.resolveChatModel(ctx)
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val safeBillSummary = shortenForModel(billSummary, MAX_ASSISTANT_SUMMARY_CHARS)
        val safeReplyHint = shortenForModel(extractorReplyHint, MAX_ASSISTANT_INPUT_CHARS, preserveTail = false)
        val systemPrompt = buildAssistantSystemPrompt(
            ctx = ctx,
            defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
        )
        val userPrompt = buildAccountingAssistantUserPrompt(
            userInput = safeUserInput,
            billSummary = safeBillSummary,
            extractorReplyHint = safeReplyHint
        )

        val requestJson = buildMultiTurnChatRequest(
            model = model,
            temperature = 0.7,
            systemPrompt = systemPrompt,
            historyTurns = chatTurns,
            userText = userPrompt,
            stream = true,
            enableThinking = false
        )

        return try {
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = false,
                reasoningLogTag = ACCOUNTING_ASSISTANT_LOG_TAG,
                onContentDelta = onDelta
            )
            streamed.completed
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    suspend fun probeDirectAudioInputSupport(ctx: Context, modelName: String? = null): Boolean {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) return false
        val model = modelName?.takeIf { it.isNotBlank() }
            ?: AiModelSlots.resolveChatModel(ctx)
        return runCatching {
            val requestJson = buildAudioChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = null,
                leadText = "Reply with OK only.",
                audioBase64 = "data:audio/wav;base64,${buildProbeAudioBase64()}",
                audioFormat = "wav",
                enableThinking = false
            )
            val response = getApi(ctx).chatRaw(
                "Bearer $apiKey",
                adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
            )
            response.choices.firstOrNull()?.message?.content != null
        }.getOrElse { false }
    }

    fun extractAccountingAssistantReply(root: JSONObject): String {
        return root.optString("assistant_reply", "").trim()
            .ifBlank { root.optString("reply", "").trim() }
    }

    fun markVisualAccountingReviewDraft(
        root: JSONObject,
        sourceKind: String,
        naturalSummary: String = "",
        includePaymentMethod: Boolean = true
    ): JSONObject {
        if (root.optBoolean("no_bill", false)) return root
        root.put("source_kind", sourceKind)
        root.put("requires_review", true)
        val summary = naturalSummary.trim().ifBlank {
            root.optString("natural_summary", "").trim().ifBlank {
                buildVisualAccountingNaturalSummary(root, includePaymentMethod)
            }
        }
        if (summary.isNotBlank()) root.put("natural_summary", summary)

        val riskFlags = root.optJSONArray("risk_flags") ?: JSONArray()
        collectVisualAccountingRiskFlags(root, includePaymentMethod).forEach { flag ->
            if (!jsonArrayContains(riskFlags, flag)) riskFlags.put(flag)
        }
        root.put("risk_flags", riskFlags)

        if (root.has("bills")) {
            val bills = root.getJSONArray("bills")
            for (i in 0 until bills.length()) {
                val bill = bills.optJSONObject(i) ?: continue
                bill.put("source_kind", sourceKind)
                bill.put("requires_review", true)
                if (summary.isNotBlank()) bill.put("natural_summary", summary)
            }
        }
        return root
    }

    private fun buildVisualAccountingNaturalSummary(root: JSONObject, includePaymentMethod: Boolean): String {
        val bills = root.optJSONArray("bills") ?: JSONArray().put(root)
        if (bills.length() == 0) return ""
        val lines = mutableListOf<String>()
        for (i in 0 until bills.length()) {
            val bill = bills.optJSONObject(i) ?: continue
            val type = bill.optInt("type", 0)
            val subType = bill.optInt("subType", 0)
            val amount = bill.optDouble("amount", 0.0)
            val currency = bill.optString("currency", "CNY").ifBlank { "CNY" }
            val remark = bill.optString("remarks", bill.optString("remark", "")).ifBlank { "待确认" }
            val time = bill.optString("time", "").ifBlank { "待确认" }
            val asset = bill.optString("asset_name", "").ifBlank { "待确认" }
            val toAsset = bill.optString("to_asset_name", "")
            val category = bill.optString("category_name", "").ifBlank { "待确认" }
            val accountText = if (toAsset.isNotBlank()) "$asset -> $toAsset" else asset
            val paymentText = if (includePaymentMethod) "，账户 $accountText" else ""
            lines += "${i + 1}. ${visualTypeLabel(type, subType)} ${formatVisualAmount(amount, currency)}，$remark，时间 $time$paymentText，分类 $category"
        }
        return lines.joinToString("\n")
    }

    private fun collectVisualAccountingRiskFlags(root: JSONObject, includePaymentMethod: Boolean): Set<String> {
        val flags = linkedSetOf<String>()
        val bills = root.optJSONArray("bills") ?: JSONArray().put(root)
        for (i in 0 until bills.length()) {
            val bill = bills.optJSONObject(i) ?: continue
            if (bill.optDouble("amount", 0.0) <= 0.0) flags += "unclear_amount"
            if (includePaymentMethod && bill.optString("asset_name", "").isBlank()) flags += "missing_asset"
            if (bill.optString("remarks", bill.optString("remark", "")).isBlank()) flags += "unclear_item"
            if (bill.optString("category_name", "").isBlank()) flags += "unclear_category"
            if (bill.optString("time", "").isBlank()) flags += "missing_time"
        }
        return flags
    }

    private fun visualTypeLabel(type: Int, subType: Int): String = when {
        type == Bill.TYPE_INCOME -> "收入"
        type == Bill.TYPE_TRANSFER && subType == Bill.SUBTYPE_REPAYMENT -> "还款"
        type == Bill.TYPE_TRANSFER -> "转账"
        else -> "支出"
    }

    private fun formatVisualAmount(amount: Double, currency: String): String {
        val formatted = String.format(Locale.getDefault(), "%.2f", amount)
        val prefix = if (currency.equals("CNY", ignoreCase = true)) "¥" else "$currency "
        return "$prefix$formatted"
    }

    private fun jsonArrayContains(array: JSONArray, value: String): Boolean {
        for (i in 0 until array.length()) {
            if (array.optString(i) == value) return true
        }
        return false
    }

    /**
     * 规范化 AI 记账结果，并在开启本地规则覆盖时按关键词强制纠正。
     * 文字记账与图片直出共用，保证规则行为一致。
     */
    private suspend fun normalizeAccountingWithLocalRules(
        ctx: Context,
        root: JSONObject,
        promptContext: AIAccountingPromptContext,
        referenceText: String
    ) {
        normalizeAccountingResult(
            root = root,
            expenseCats = promptContext.expenseCats,
            incomeCats = promptContext.incomeCats,
            assetNames = promptContext.assetNames,
            assetFeatureEnabled = promptContext.assetFeatureEnabled,
            referenceText = referenceText,
            assetCurrencyMap = promptContext.assetCurrencyMap,
            availableBooks = promptContext.availableBooks
        )
        if (!Prefs.isLocalRuleOverrideEnabled(ctx)) return

        val promptRules = loadActivePromptRules(ctx)
        applyLocalRuleOverrideOnResult(root, referenceText.trim(), promptRules)
        normalizeAccountingResult(
            root = root,
            expenseCats = promptContext.expenseCats,
            incomeCats = promptContext.incomeCats,
            assetNames = promptContext.assetNames,
            assetFeatureEnabled = promptContext.assetFeatureEnabled,
            referenceText = referenceText,
            assetCurrencyMap = promptContext.assetCurrencyMap,
            availableBooks = promptContext.availableBooks
        )
    }

    /**
     * 本地规则强覆盖（最终裁决）。
     * 直接在已解析的 JSONObject 上原地修改，保证规则的 type / category_name /
     * asset_name / to_asset_name 不会再被后续步骤覆盖。
     */
    private fun applyLocalRuleOverrideOnResult(root: JSONObject, userInput: String, allRules: List<com.taostudio.tapaccounting.data.local.entity.AiRule>) {
        if (allRules.isEmpty()) return

        fun applyRulesToBill(bill: JSONObject) {
            // 优先取 remarks 作为该条账单的语义文本
            val remarks = bill.optString("remarks", "").trim()
                .ifEmpty { bill.optString("remark", "").trim() }
            val categoryName = bill.optString("category_name", "")

            // 多账单场景下：仅用 remarks+categoryName 匹配，避免 A 账单规则命中 B/C。
            // 单账单或 AI 未返回 remarks 时才兜底 userInput。
            val matchText = if (remarks.isNotBlank()) "$remarks $categoryName" else userInput

            allRules.filter { rule ->
                rule.keyword.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    .all { matchText.contains(it, ignoreCase = true) }
            }.forEach { rule ->
                rule.targetType?.takeIf { it in 0..3 }?.let { t ->
                    bill.put("type", if (t == 3) 2 else t)
                    if (t == 3) {
                        bill.put("subType", 1)
                        bill.put("category_name", "还款")
                    }
                }
                if (!rule.targetCategory.isNullOrEmpty()) bill.put("category_name", rule.targetCategory)
                if (!rule.targetAccount1.isNullOrEmpty()) bill.put("asset_name", rule.targetAccount1)
                if (!rule.targetAccount2.isNullOrEmpty()) bill.put("to_asset_name", rule.targetAccount2)
            }
        }

        try {
            if (root.has("bills")) {
                val billsArr = root.getJSONArray("bills")
                for (i in 0 until billsArr.length()) {
                    applyRulesToBill(billsArr.getJSONObject(i))
                }
            } else if (root.has("amount")) {
                applyRulesToBill(root)
            }
            android.util.Log.d("AIService", "Local rule override applied")
        } catch (e: Exception) {
            android.util.Log.d("AIService", "applyLocalRuleOverrideOnResult error: ${e.javaClass.simpleName}")
        }
    }

    private fun parseAnalyzeResult(finalContent: String): JSONObject? {
        val cleaned = cleanJsonString(finalContent)
        val json = try {
            if (cleaned.startsWith("[")) {
                val array = org.json.JSONArray(cleaned)
                JSONObject().apply { put("bills", array) }
            } else {
                JSONObject(cleaned)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("AI响应非JSON: ${cleaned.take(50)}...")
        }
        if (json.optBoolean("no_bill", false)) {
            return json
        }
        return when {
            json.has("bills")  -> json
            json.has("amount") -> JSONObject().apply { put("bills", JSONArray().put(json)) }
            else -> throw IllegalArgumentException("AI 返回的数据缺少关键字段 'bills' 或 'amount'")
        }
    }

    private fun enforceExpenseForReceiptSummaries(root: JSONObject, userInput: String) {
        if (!looksLikeReceiptExpenseSummary(userInput)) return
        if (root.has("bills")) {
            val billsArr = root.getJSONArray("bills")
            for (i in 0 until billsArr.length()) {
                val bill = billsArr.getJSONObject(i)
                if (bill.optInt("type", 0) in 0..1) bill.put("type", 0)
            }
        } else if (root.has("amount")) {
            if (root.optInt("type", 0) in 0..1) root.put("type", 0)
        }
    }

    private fun looksLikeReceiptExpenseSummary(text: String): Boolean {
        val normalized = text.lowercase()
        val expenseSignals = listOf("购买", "花了", "总计花费", "小票", "receipt", "discount", "visa",
            "mastercard", "刷卡", "支付", "超市", "商店", "biedronka", "pln", "eur")
        val incomeSignals = listOf("工资", "收入", "收款", "收到", "到账", "退款到账", "报销到账", "转入", "打款给我")
        return expenseSignals.count { normalized.contains(it) } >= 2 && incomeSignals.none { normalized.contains(it) }
    }

    private suspend fun loadActivePromptRules(ctx: Context): List<DbAiRule> = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(ctx).aiRuleDao().getEnabledRulesList()
    }

    private fun findMatchedPromptRules(text: String, allRules: List<DbAiRule>): List<DbAiRule> =
        allRules.filter { rule ->
            rule.isEnabled && rule.keyword.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                .all { text.contains(it, ignoreCase = true) }
        }

    data class StreamResult(
        val content: String,
        val reasoning: String,
        val completed: Boolean,
        val sawDone: Boolean,
        val parseError: Exception? = null,
        val transportError: Exception? = null
    )

    private suspend fun requestChatContentStreamedWithReasoning(
        ctx: Context,
        apiKey: String,
        requestJson: com.google.gson.JsonObject,
        logReasoning: Boolean = false,
        reasoningLogTag: String = "AIService",
        onContentDelta: ((String) -> Unit)? = null,
        onProgressChars: ((Int) -> Unit)? = null
    ): StreamResult {
        val streamReq = requestJson.deepCopy()
            .apply { addProperty("stream", true) }
            .let { adaptChatRequestForProvider(Prefs.getAiProvider(ctx), it) }
        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        var lastProgressLen = 0
        var sawDone = false
        var parseError: Exception? = null
        var transportError: Exception? = null
        try {
            val body = getApi(ctx).chatStreamRaw("Bearer $apiKey", streamReq)
            body.use { responseBody ->
                val source = responseBody.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        sawDone = true
                        break
                    }
                    try {
                        val root = JSONObject(payload)
                        root.optJSONObject("usage")?.let { usage ->
                            logPromptCacheUsage(ctx, reasoningLogTag, usage)
                        }
                        val deltaObj = root.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                        val contentDelta = jsonOptStringOrEmpty(deltaObj, "content")
                        val reasoningDelta = extractReasoningDelta(deltaObj)
                        if (reasoningDelta.isNotEmpty()) {
                            reasoningBuilder.append(reasoningDelta)
                            if (logReasoning) {
                                Logger.d(ctx, reasoningLogTag, "reasoning delta received: len=${reasoningDelta.length}")
                            }
                        }
                        if (contentDelta.isNotEmpty()) {
                            contentBuilder.append(contentDelta)
                            Logger.d(ctx, "AIService", "SSE delta: len=${contentDelta.length}, total=${contentBuilder.length}")
                            onContentDelta?.invoke(contentDelta)
                            val currentLen = contentBuilder.length
                            if (onProgressChars != null && currentLen - lastProgressLen >= 120) {
                                lastProgressLen = currentLen
                                onProgressChars.invoke(currentLen)
                            }
                        }
                    } catch (e: Exception) {
                        parseError = e
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            transportError = e
        }
        if (logReasoning && reasoningBuilder.isNotBlank()) {
            Logger.d(ctx, reasoningLogTag, "reasoning full received: len=${reasoningBuilder.length}")
        }
        return StreamResult(
            content = stripAccidentalNullPrefix(contentBuilder.toString()),
            reasoning = reasoningBuilder.toString(),
            completed = sawDone && parseError == null && transportError == null,
            sawDone = sawDone,
            parseError = parseError,
            transportError = transportError
        )
    }

    /**
     * Android JSONObject.optString returns the literal "null" when the JSON value is null.
     * DeepSeek reasoning streams often emit many content=null chunks before real text arrives.
     */
    private fun jsonOptStringOrEmpty(obj: JSONObject?, key: String): String {
        if (obj == null || obj.isNull(key)) return ""
        val value = obj.optString(key, "")
        return if (value.equals("null", ignoreCase = true)) "" else value
    }

    private fun stripAccidentalNullPrefix(raw: String): String {
        var text = raw
        while (text.startsWith("null", ignoreCase = true)) {
            text = text.substring(4)
        }
        return text
    }

    private fun logPromptCacheUsage(ctx: Context, requestTag: String, usage: JSONObject) {
        val hit = usage.optLong("prompt_cache_hit_tokens", -1)
        val miss = usage.optLong("prompt_cache_miss_tokens", -1)
        val promptTokens = usage.optLong("prompt_tokens", -1)
        val cachedTokens = usage.optLong("cached_tokens", -1).takeIf { it >= 0 }
            ?: usage.optJSONObject("prompt_tokens_details")
                ?.optLong("cached_tokens", -1)
            ?: -1
        if (hit >= 0 || miss >= 0 || cachedTokens >= 0) {
            Logger.d(
                ctx,
                AI_CACHE_LOG_TAG,
                "tag=$requestTag, prompt_cache_hit=$hit, prompt_cache_miss=$miss, prompt_tokens=$promptTokens, cached_tokens=$cachedTokens"
            )
        }
    }

    private fun extractReasoningDelta(deltaObj: JSONObject?): String {
        if (deltaObj == null) return ""
        val candidates = listOf(
            jsonOptStringOrEmpty(deltaObj, "reasoning_content"),
            jsonOptStringOrEmpty(deltaObj, "reasoning"),
            jsonOptStringOrEmpty(deltaObj, "thinking")
        )
        return candidates.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private suspend fun requestAccountingContentStreamed(
        ctx: Context,
        apiKey: String,
        requestJson: com.google.gson.JsonObject,
        onProgress: ((String) -> Unit)?,
        emitTextDelta: Boolean = false,
        logReasoning: Boolean = false,
        reasoningLogTag: String = "AIService"
    ): String {
        val streamed = requestChatContentStreamedWithReasoning(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = requestJson,
            logReasoning = logReasoning,
            reasoningLogTag = reasoningLogTag,
            onContentDelta = if (emitTextDelta) { delta -> onProgress?.invoke("AI_STREAM_TEXT::$delta") } else null,
            onProgressChars = if (emitTextDelta) null else { currentLen -> onProgress?.invoke("正在整理账单...（已接收 $currentLen 字）") }
        )
        if (streamed.completed) {
            return streamed.content
        }
        // 流式未完成但已收到部分内容，直接使用（不 fallback 到非流式）
        if (streamed.content.isNotBlank()) {
            Logger.d(ctx, "AIService", "Stream incomplete but has content (${streamed.content.length} chars), using partial result")
            return streamed.content
        }
        // 完全没有内容，才 fallback 到非流式
        Logger.d(ctx, "AIService", "Stream empty, fallback raw request, err=${streamed.transportError?.javaClass?.simpleName}")
        val response = getApi(ctx).chatRaw(
            "Bearer $apiKey",
            adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
        )
        return response.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("API returned empty choices in fallback path")
    }

    private fun buildRawRequest(request: ChatRequest): com.google.gson.JsonObject {
        val gson = com.google.gson.GsonBuilder()
            .registerTypeAdapter(MessageUnion::class.java, MessageUnionSerializer())
            .create()
        return gson.fromJson(gson.toJson(request), com.google.gson.JsonObject::class.java)
    }

    private fun expandPdfAttachmentsForVisionApi(
        ctx: Context,
        attachments: List<Pair<String, String>>,
        maxPdfPages: Int = ChatAttachmentHelper.MAX_PDF_PAGES
    ): List<Pair<String, String>> {
        val expanded = mutableListOf<Pair<String, String>>()
        for ((base64, mime) in attachments) {
            if (!mime.equals("application/pdf", ignoreCase = true)) {
                expanded.add(base64 to mime)
                continue
            }
            val temp = File.createTempFile("chat_pdf_", ".pdf", ctx.cacheDir)
            try {
                temp.writeBytes(Base64.decode(base64, Base64.NO_WRAP))
                val pages = ChatPdfRenderer.renderPagesToJpeg(temp, maxPdfPages)
                if (pages.isEmpty()) {
                    expanded.add(base64 to mime)
                } else {
                    pages.forEach { page ->
                        expanded.add(Base64.encodeToString(page, Base64.NO_WRAP) to "image/jpeg")
                    }
                }
            } catch (_: Exception) {
                expanded.add(base64 to mime)
            } finally {
                temp.delete()
            }
        }
        return expanded
    }
}
