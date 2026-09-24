package com.taostudio.tapaccounting

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import java.util.ArrayDeque
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.coroutines.coroutineContext

data class ChatRequestContext(
    val requestId: String,
    val bookName: String,
    val conversationId: String,
    val startedAt: Long,
    val loadingUiKey: String? = null
)

class ChatMessagePipeline(
    private val context: ChatActivity,
    private val aiWorkScope: CoroutineScope,
    private val getInputText: () -> String,
    private val clearInput: () -> Unit,
    private val updateInputActionUi: () -> Unit,
    private val appendUserMessage: (String, Int) -> Unit,
    private val consumePendingHabitSuggestionReply: (String) -> Boolean,
    private val appendAiTextMessage: (String, Boolean, String?, String?, Boolean) -> String,
    private val removeLoadingMessage: (String) -> Unit,
    private val updateLoadingMessage: (String, String) -> Unit,
    private val finalizeLoadingMessage: (String, String, String, String, Boolean) -> Boolean,
    private val buildAnalysisInput: suspend (String) -> String,
    private val processBillResult: suspend (JSONObject, String, String, String) -> List<Bill>,
    private val confirmVisualAccountingDraft: suspend (String, String, String) -> String?,
    private val buildBillSummary: (List<Bill>) -> String,
    private val transcribeVoiceToTextWithFallback: suspend (File) -> String,
    private val persistAiTextMessage: suspend (String, String, String) -> Unit,
    private val db: com.taostudio.tapaccounting.data.local.AppDatabase,
    private val getCurrentBookName: () -> String,
    private val getCurrentConversationId: () -> String,
    private val onMessagesChanged: () -> Unit = {},
    private val isConversationMode: () -> Boolean = { false }
) {
    private fun appendAi(
        text: String,
        isLoading: Boolean,
        bookName: String? = null,
        conversationId: String? = null,
        showConversationModeNudge: Boolean = false
    ): String = appendAiTextMessage(text, isLoading, bookName, conversationId, showConversationModeNudge)

    /**
     * 原地把加载气泡定稿为最终回复：不销毁重建消息条目，避免 remove+insert 带来的
     * 二次闪跳。若加载气泡已不存在（已被移除等），回退为追加新消息，保证内容不丢。
     */
    private fun finalizeAi(
        uiKey: String,
        text: String,
        bookName: String,
        conversationId: String,
        showConversationModeNudge: Boolean = false
    ) {
        val finalized = finalizeLoadingMessage(uiKey, text, bookName, conversationId, showConversationModeNudge)
        if (!finalized) {
            appendAi(text, false, bookName, conversationId, showConversationModeNudge)
        }
    }

    companion object {
        private const val CHAT_ROUTE_LOG_TAG = "AiChatRoute"
        private const val REPEAT_REPLY_STATS_LOG_TAG = "RepeatReplyStats"
        private const val REPEAT_REPLY_WINDOW_SIZE = 3
        private const val REPEAT_REPLY_MAX_KEYS = 24
        private const val HIGH_SIMILARITY_THRESHOLD = 0.82
        const val CHAT_HISTORY_FETCH_LIMIT = 80
        const val CHAT_HISTORY_MAX_TURNS = 40
        const val CHAT_HISTORY_MAX_TOTAL_CHARS = 48_000
        const val MAX_CHAT_HISTORY_TURN_CHARS = 6000
        const val MAX_CHAT_HISTORY_VOICE_CHARS = 400

        /**
         * Truncate history messages from newest to oldest, then reverse to chronological order.
         * Ensures the first message is a user message (not an orphaned assistant reply).
         * A single oversized message is truncated rather than dropped.
         * This is a pure function — testable without Android dependencies.
         */
        fun truncateHistory(
            messages: List<Pair<Int, String>>,
            maxTotalChars: Int = CHAT_HISTORY_MAX_TOTAL_CHARS
        ): List<Pair<Int, String>> {
            if (messages.isEmpty()) return emptyList()
            // Step 1: Truncate from newest to oldest, preserving recent context.
            val selected = mutableListOf<Pair<Int, String>>()
            var totalChars = 0
            for (msg in messages.reversed()) {
                val (msgType, content) = msg
                if (content.isBlank()) continue
                if (totalChars + content.length > maxTotalChars) {
                    // If this is the newest message and nothing selected yet:
                    // - User messages: truncate text to fit (user intent is always important)
                    // - Assistant messages: drop entirely (truncated reply is misleading)
                    if (selected.isEmpty() && msgType in 0..2 && content.length > maxTotalChars) {
                        val truncated = content.take(maxTotalChars - 1).trimEnd() + "…"
                        selected.add(msgType to truncated)
                    }
                    break
                }
                totalChars += content.length
                selected.add(msg)
            }
            // Step 2: Reverse to chronological order
            selected.reverse()
            // Step 3: Ensure first message is a user message (msgType 0-2)
            val firstUserIndex = selected.indexOfFirst { it.first in 0..2 }
            return if (firstUserIndex > 0) selected.subList(firstUserIndex, selected.size) else selected
        }
    }

    private var isUserTextDispatching: Boolean = false
    private var activeRequestJob: Job? = null
    private var activeRequestContext: ChatRequestContext? = null
    private val repeatedInputReplyWindow = LinkedHashMap<String, ArrayDeque<String>>()
    private var repeatInputTurns: Int = 0
    private var repeatInputExactMatches: Int = 0
    private var repeatInputHighSimilarityMatches: Int = 0

    private class StreamingTextUiBuffer(
        private val minIntervalMs: Long = 120L
    ) {
        private val text = StringBuilder()
        private var lastFlushMs = 0L

        fun append(delta: String): String? {
            if (delta.isBlank()) return null
            text.append(delta)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFlushMs < minIntervalMs) return null
            lastFlushMs = now
            return text.toString()
        }

        fun value(): String = text.toString()
    }

    private fun pushStreamText(
        loadingKey: String,
        requestContext: ChatRequestContext,
        buffer: StreamingTextUiBuffer,
        delta: String
    ) {
        val next = buffer.append(delta) ?: return
        runOnUiIfAlive {
            if (canWriteForRequest(requestContext)) {
                updateLoadingMessage(loadingKey, next)
            }
        }
    }

    private fun newRequestContext(loadingUiKey: String? = null): ChatRequestContext {
        return ChatRequestContext(
            requestId = UUID.randomUUID().toString(),
            bookName = getCurrentBookName(),
            conversationId = getCurrentConversationId(),
            startedAt = System.currentTimeMillis(),
            loadingUiKey = loadingUiKey
        )
    }

    private fun registerActiveJob(job: Job, ctx: ChatRequestContext) {
        activeRequestJob?.cancel()
        activeRequestJob = job
        activeRequestContext = ctx
    }

    private fun isRequestStillActive(ctx: ChatRequestContext): Boolean {
        return activeRequestContext?.requestId == ctx.requestId &&
            activeRequestJob?.isActive == true &&
            isUiAlive()
    }

    private fun isRequestStillInCurrentConversation(ctx: ChatRequestContext): Boolean {
        return ctx.bookName == getCurrentBookName() && ctx.conversationId == getCurrentConversationId()
    }

    private fun canWriteForRequest(ctx: ChatRequestContext): Boolean {
        return isRequestStillActive(ctx) && isRequestStillInCurrentConversation(ctx)
    }

    private fun clearActiveRequestIfMatch(ctx: ChatRequestContext) {
        if (activeRequestContext?.requestId == ctx.requestId) {
            activeRequestJob = null
            activeRequestContext = null
        }
    }

    fun cancelCurrentRequest(showInterruptedMessage: Boolean = true) {
        val ctx = activeRequestContext
        activeRequestJob?.cancel()
        activeRequestJob = null
        activeRequestContext = null
        isUserTextDispatching = false
        ctx?.loadingUiKey?.let { key ->
            runOnUiIfAlive { removeLoadingMessage(key) }
        }
        if (showInterruptedMessage && ctx != null && isRequestStillInCurrentConversation(ctx)) {
            appendAi("已中断本次请求。", false, ctx.bookName, ctx.conversationId)
        }
    }

    private fun isUiAlive(): Boolean = !(context.isDestroyed || context.isFinishing)

    private fun runOnUiIfAlive(block: () -> Unit) {
        if (!isUiAlive()) return
        context.runOnUiThread {
            if (!isUiAlive()) return@runOnUiThread
            block()
        }
    }

    fun sendText() {
        val text = getInputText().trim()
        if (text.isEmpty()) {
            Utils.toast(context, context.getString(R.string.toast_empty_content))
            return
        }
        isUserTextDispatching = true
        clearInput()
        updateInputActionUi()

        if (consumePendingHabitSuggestionReply(text)) {
            // Still save the user message for habit suggestion replies
            appendUserMessage(text, ChatActivity.MSG_TYPE_USER_TEXT)
            isUserTextDispatching = false
            return
        }

        appendUserMessage(text, ChatActivity.MSG_TYPE_USER_TEXT)
        callAiAccounting(text, appendUserBubble = false)
        isUserTextDispatching = false
        onMessagesChanged()
    }

    private suspend fun buildChatHistoryTurns(
        userText: String,
        requestContext: ChatRequestContext? = null
    ): List<ChatTurn> {
        return withContext(Dispatchers.IO) {
            val book = requestContext?.bookName ?: getCurrentBookName()
            val convId = requestContext?.conversationId ?: getCurrentConversationId()
            val recent = db.chatMessageDao().getRecentMessages(book, convId, CHAT_HISTORY_FETCH_LIMIT).toMutableList()
            if (recent.isEmpty()) return@withContext emptyList()

            val filteredRecent = filterRepeatedInputTurnsFromHistory(recent, userText)

            val turns = filteredRecent
                .takeLast(CHAT_HISTORY_MAX_TURNS)
                .mapNotNull { msg ->
                    val summary = summarizeHistoryMessage(msg, book)
                    if (summary.isBlank()) return@mapNotNull null
                    val role = if (msg.msgType in 0..2) "user" else "assistant"
                    ChatTurn(role, summary)
                }
            trimHistoryToCharBudget(turns, CHAT_HISTORY_MAX_TOTAL_CHARS)
        }
    }

    private fun trimHistoryToCharBudget(turns: List<ChatTurn>, maxTotalChars: Int): List<ChatTurn> {
        if (turns.isEmpty()) return turns
        var total = turns.sumOf { it.content.length }
        if (total <= maxTotalChars) return turns
        val trimmed = turns.toMutableList()
        while (trimmed.isNotEmpty() && total > maxTotalChars) {
            total -= trimmed.removeAt(0).content.length
        }
        return trimmed
    }

    private fun filterRepeatedInputTurnsFromHistory(
        recent: List<ChatMessage>,
        userText: String
    ): List<ChatMessage> {
        val currentUserNormalized = normalizeForRepeatComparison(userText)
        if (currentUserNormalized.isBlank()) return recent

        val currentMessageId = recent.asReversed()
            .firstOrNull { msg ->
                msg.msgType == ChatActivity.MSG_TYPE_USER_TEXT &&
                    normalizeForRepeatComparison(msg.content) == currentUserNormalized
            }
            ?.id
            ?: return recent

        return recent.filterNot { it.id == currentMessageId }
    }

    private suspend fun summarizeHistoryMessage(msg: ChatMessage, bookName: String): String {
        val raw = msg.content.trim()
        if (raw.isBlank() && msg.msgType != ChatActivity.MSG_TYPE_AI_BILL) return ""
        return when (msg.msgType) {
            ChatActivity.MSG_TYPE_USER_TEXT -> truncateHistoryText(raw, MAX_CHAT_HISTORY_TURN_CHARS)
            ChatActivity.MSG_TYPE_USER_IMAGE -> "[图片消息]"
            ChatActivity.MSG_TYPE_USER_VOICE -> {
                val transcript = runCatching {
                    JSONObject(raw).optString("transcript").trim()
                }.getOrDefault("")
                if (transcript.isNotBlank()) {
                    "语音：${compactHistoryText(transcript, MAX_CHAT_HISTORY_VOICE_CHARS)}"
                } else {
                    "[语音消息]"
                }
            }
            ChatActivity.MSG_TYPE_AI_BILL -> summarizeBillHistoryMessage(msg, bookName)
            ChatActivity.MSG_TYPE_AI_TEXT -> truncateHistoryText(raw, MAX_CHAT_HISTORY_TURN_CHARS)
            else -> compactHistoryText(raw)
        }
    }

    private suspend fun summarizeBillHistoryMessage(msg: ChatMessage, bookName: String): String {
        val billIds = ChatBillMessageParser.parseBillIds(msg.billIds)
        val liveBills = billIds.mapNotNull { id -> db.billDao().getBillById(id) }
        if (liveBills.isNotEmpty()) {
            return truncateHistoryText(
                "过去已入账（仅上下文，不是当前任务结果）：${buildBillSummary(liveBills)}",
                MAX_CHAT_HISTORY_TURN_CHARS
            )
        }
        // Never dump raw bill JSON into model history — that makes the model echo
        // "已记账：{...}" as plain text and skip real booking on the next turn.
        val snapshotBills = ChatBillMessageParser.parseBillsFromMessageContent(
            content = msg.content,
            currentBookName = bookName,
            parseTimeToMillis = { System.currentTimeMillis() }
        )
        if (snapshotBills.isNotEmpty()) {
            return truncateHistoryText(
                "过去已入账（仅上下文，不是当前任务结果）：${buildBillSummary(snapshotBills)}",
                MAX_CHAT_HISTORY_TURN_CHARS
            )
        }
        return "[过去账单结果]"
    }

    private fun compactHistoryText(text: String, maxLen: Int = 180): String {
        val normalized = text
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maxLen) return normalized
        return normalized.take(maxLen).trimEnd() + "…"
    }

    private fun truncateHistoryText(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        return text.take(maxLen).trimEnd() + "…"
    }

    private fun normalizeForRepeatComparison(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Punct}]+"), "")
            .trim()
    }

    private fun logRepeatReplyStats(routeTag: String, userText: String, finalReply: String) {
        val normalizedInput = normalizeForRepeatComparison(userText)
        val normalizedReply = normalizeForRepeatComparison(finalReply)
        if (normalizedInput.isBlank() || normalizedReply.isBlank()) return

        val previousReplies = repeatedInputReplyWindow[normalizedInput]
        if (previousReplies != null && previousReplies.isNotEmpty()) {
            repeatInputTurns += 1
            val exactMatched = previousReplies.any { it == normalizedReply }
            val highSimilarityMatched = previousReplies.any {
                similarityScore(it, normalizedReply) >= HIGH_SIMILARITY_THRESHOLD
            }
            if (exactMatched) repeatInputExactMatches += 1
            if (highSimilarityMatched) repeatInputHighSimilarityMatches += 1
            val exactRate = if (repeatInputTurns <= 0) 0.0 else repeatInputExactMatches.toDouble() / repeatInputTurns.toDouble()
            val highRate = if (repeatInputTurns <= 0) 0.0 else repeatInputHighSimilarityMatches.toDouble() / repeatInputTurns.toDouble()
            Logger.d(
                context,
                REPEAT_REPLY_STATS_LOG_TAG,
                "route=$routeTag, repeatedTurns=$repeatInputTurns, exactRate=${String.format(Locale.US, "%.3f", exactRate)}, highSimRate=${String.format(Locale.US, "%.3f", highRate)}, inputHash=${abs(normalizedInput.hashCode())}, priorCount=${previousReplies.size}"
            )
        }

        val window = repeatedInputReplyWindow.getOrPut(normalizedInput) { ArrayDeque<String>(REPEAT_REPLY_WINDOW_SIZE) }
        if (window.size >= REPEAT_REPLY_WINDOW_SIZE) {
            window.removeFirst()
        }
        window.addLast(normalizedReply)
        while (repeatedInputReplyWindow.size > REPEAT_REPLY_MAX_KEYS) {
            val firstKey = repeatedInputReplyWindow.keys.firstOrNull() ?: break
            repeatedInputReplyWindow.remove(firstKey)
        }
    }

    private fun similarityScore(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val setA = a.chunked(2).filter { it.isNotBlank() }.toSet()
        val setB = b.chunked(2).filter { it.isNotBlank() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val intersection = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        if (union <= 0.0) return 0.0
        return (intersection / union).coerceIn(0.0, 1.0)
    }

    fun callAiAccounting(
        userText: String,
        appendUserBubble: Boolean = true,
        forceTextReply: Boolean = false,
        loadingIdxOverride: String? = null,
        loadingBootstrapText: String = "",
        loadingInitialText: String = ""
    ) {
        if (appendUserBubble) appendUserMessage(userText, ChatActivity.MSG_TYPE_USER_TEXT)
        val loadingKey = loadingIdxOverride ?: appendAi(
            loadingInitialText.ifBlank { "正在分析..." },
            true,
            getCurrentBookName(),
            getCurrentConversationId()
        )
        val requestContext = newRequestContext(loadingKey)
        var loadingStage = 1
        val streamedRaw = StringBuilder()
        var lastDisplayedPreview = ""
        var streamStarted = false
        var lastPreviewUpdateMs = 0L
        fun pushLoadingStatus(raw: String) {
            if (!canWriteForRequest(requestContext)) return
            if (raw.startsWith("AI_STREAM_TEXT::")) {
                val delta = raw.removePrefix("AI_STREAM_TEXT::")
                if (delta.isBlank()) return
                streamStarted = true
                streamedRaw.append(delta)
                val candidate = StreamingBillPreview.formatChatPreview(streamedRaw.toString(), lastDisplayedPreview)
                if (!StreamingBillPreview.shouldUpdateUi(lastDisplayedPreview, candidate, lastPreviewUpdateMs)) return
                lastDisplayedPreview = candidate
                lastPreviewUpdateMs = android.os.SystemClock.elapsedRealtime()
                updateLoadingMessage(loadingKey, candidate)
                return
            }
            if (!StreamingBillPreview.shouldApplyNonStreamProgress(streamStarted)) return
            val (stage, text) = mapProgressToNaturalStatus(raw)
            val nextStage = maxOf(loadingStage, stage)
            loadingStage = nextStage
            val stableText = when (nextStage) {
                1 -> "正在读懂这笔账..."
                2 -> "正在整理账单..."
                else -> text
            }
            if (stableText == lastDisplayedPreview) return
            lastDisplayedPreview = stableText
            lastPreviewUpdateMs = android.os.SystemClock.elapsedRealtime()
            updateLoadingMessage(loadingKey, stableText)
        }
        if (loadingIdxOverride != null && loadingBootstrapText.isNotBlank()) {
            updateLoadingMessage(loadingKey, loadingBootstrapText)
        }
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val isSingleImagePayload = userText.startsWith(ReceiptImageInputHelper.MULTIMODAL_PREFIX) ||
                    userText.startsWith(ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX)
                val isMultiImagePayload = ChatImageComposer.isMultiImagePayload(userText)
                val isImagePayload = isSingleImagePayload || isMultiImagePayload
                val extractedImages = if (isImagePayload) ChatImageComposer.extractPayloadImages(userText) else null
                val historyInputText = if (isImagePayload) {
                    extractedImages?.supplement?.takeIf { it.isNotBlank() }
                        ?: ChatAttachmentHelper.historyPlaceholder(
                            extractedImages?.images?.map { it.mime }.orEmpty()
                        )
                } else {
                    userText
                }
                val chatHistoryTurns = buildChatHistoryTurns(historyInputText, requestContext)
                val analysisInput = buildAnalysisInput(userText)
                var accountingSourceText = userText
                val result = try {
                    if (isImagePayload && extractedImages != null) {
                        val supplementText = extractedImages.supplement
                        val imagePairs = extractedImages.images.map { it.base64 to it.mime }
                        // 先做二分类
                        val intent = withContext(Dispatchers.IO) {
                            AIService.classifyIntent(context, supplementText, imagePairs)
                        }
                        if (!canWriteForRequest(requestContext)) return@launch

                        if (isConversationMode()) {
                            if (intent == "BOOKKEEPING") {
                                // 对话模式下检测到记账请求，提示用户切换模式
                                finalizeAi(
                                    loadingKey,
                                    "检测到记账内容，你可以点击右上角切换到记账模式来记录这笔账",
                                    requestContext.bookName,
                                    requestContext.conversationId
                                )
                                return@launch
                            }
                            // 对话模式下的闲聊
                            streamConversationWithImages(
                                loadingKey = loadingKey,
                                userInput = supplementText,
                                images = imagePairs,
                                chatHistoryTurns = chatHistoryTurns,
                                requestContext = requestContext
                            )
                            return@launch
                        }

                        // 记账模式下的闲聊
                        if (intent == "GENERAL_CHAT") {
                            streamAccountingCasualWithImages(
                                loadingKey = loadingKey,
                                userInput = supplementText,
                                images = imagePairs,
                                chatHistoryTurns = chatHistoryTurns,
                                requestContext = requestContext
                            )
                            return@launch
                        }
                    } else if (!isImagePayload) {
                        // 先做二分类
                        val intent = withContext(Dispatchers.IO) {
                            AIService.classifyIntent(context, userText)
                        }
                        if (!canWriteForRequest(requestContext)) return@launch

                        if (isConversationMode()) {
                            if (intent == "BOOKKEEPING") {
                                // 对话模式下检测到记账请求，提示用户切换模式
                                finalizeAi(
                                    loadingKey,
                                    "检测到记账内容，你可以点击右上角切换到记账模式来记录这笔账",
                                    requestContext.bookName,
                                    requestContext.conversationId
                                )
                                return@launch
                            }
                            // 对话模式下的闲聊
                            streamConversationWithText(
                                loadingKey = loadingKey,
                                userText = userText,
                                chatHistoryTurns = chatHistoryTurns,
                                requestContext = requestContext
                            )
                            return@launch
                        }

                        // 记账模式下的闲聊
                        if (intent == "GENERAL_CHAT") {
                            streamAccountingCasualWithText(
                                loadingKey = loadingKey,
                                userText = userText,
                                chatHistoryTurns = chatHistoryTurns,
                                requestContext = requestContext
                            )
                            return@launch
                        }
                    }

                    // 记账流程
                    if (isImagePayload) {
                        if (isMultiImagePayload) {
                            // Multi-image path: send all images to multimodal at once
                            val multiPayload = ChatImageComposer.decodeMultiImagePayload(userText)
                                ?: throw IllegalArgumentException("多图数据无效")
                            accountingSourceText = multiPayload.supplement.ifBlank { "图片记账" }
                            val imagePairs = multiPayload.images.map { it.base64 to it.mime }
                            val isDirectImageAccounting = !Prefs.isImageAccountingNaturalLanguage(context)

                            if (isDirectImageAccounting) {
                                // Direct path: multimodal returns JSON directly
                                updateLoadingMessage(loadingKey, "正在从${multiPayload.images.size}张图片生成账单...")
                                withContext(Dispatchers.IO) {
                                    AIService.analyzeScreenAccountingByImages(
                                        ctx = context,
                                        images = imagePairs,
                                        sourceKind = "receipt_image",
                                        supplementText = multiPayload.supplement,
                                        isFromChat = true,
                                        chatTurns = chatHistoryTurns,
                                        onProgress = { status ->
                                            runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                                        }
                                    )
                                }
                            } else {
                                // Draft-confirm path: multimodal returns text summary, user confirms, then accounting
                                updateLoadingMessage(loadingKey, "正在识别${multiPayload.images.size}张图片...")
                                val visionResult = withContext(Dispatchers.IO) {
                                    AIService.analyzeReceiptByImages(
                                        ctx = context,
                                        images = imagePairs,
                                        supplementText = multiPayload.supplement
                                    )
                                }
                                if (!canWriteForRequest(requestContext)) return@launch
                                val draftForConfirm = ReceiptImageInputHelper.mergeSupplementWithSummary(
                                    visionResult, multiPayload.supplement
                                )
                                updateLoadingMessage(loadingKey, "识别好了，等你核对草稿...")
                                val confirmedDraft = confirmVisualAccountingDraft(
                                    draftForConfirm,
                                    requestContext.bookName,
                                    requestContext.conversationId
                                )?.trim()
                                if (!canWriteForRequest(requestContext)) return@launch
                                if (confirmedDraft.isNullOrBlank()) {
                                    finalizeAi(
                                        loadingKey,
                                        "已取消本次图片记账。",
                                        requestContext.bookName,
                                        requestContext.conversationId
                                    )
                                    return@launch
                                }
                                val accountingInput = ReceiptImageInputHelper.buildAccountingInputFromImageDraft(
                                    confirmedDraft, multiPayload.supplement
                                )
                                accountingSourceText = accountingInput
                                updateLoadingMessage(loadingKey, "正在按确认内容整理账单...")
                                withContext(Dispatchers.IO) {
                                    AIService.analyzeAccounting(
                                        ctx = context,
                                        userInput = accountingInput,
                                        isFromChat = true,
                                        chatTurns = chatHistoryTurns,
                                        onProgress = { status ->
                                            runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                                        }
                                    )?.also { root ->
                                        AIService.markVisualAccountingReviewDraft(
                                            root = root,
                                            sourceKind = "chat_image",
                                            naturalSummary = accountingInput,
                                            includePaymentMethod = Prefs.isAssetFeatureEnabled(context)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Single-image legacy path (unchanged)
                            val imagePayload = ReceiptImageInputHelper.decodePayload(userText)
                                ?: throw IllegalArgumentException("图片数据无效")
                            val isDirectImageAccounting = ReceiptImageInputHelper.isDirectPayload(userText) ||
                                !Prefs.isImageAccountingNaturalLanguage(context)
                            accountingSourceText = imagePayload.supplement.ifBlank { "图片记账" }

                            if (isDirectImageAccounting) {
                                updateLoadingMessage(loadingKey, "正在直接从图片生成账单...")
                                withContext(Dispatchers.IO) {
                                    AIService.analyzeScreenAccountingByImage(
                                        ctx = context,
                                        imageBase64 = imagePayload.base64,
                                        mimeType = imagePayload.mime,
                                        sourceKind = "receipt_image",
                                        supplementText = imagePayload.supplement,
                                        isFromChat = true,
                                        chatTurns = chatHistoryTurns,
                                        onProgress = { status ->
                                            runOnUiIfAlive {
                                                if (canWriteForRequest(requestContext)) pushLoadingStatus(status)
                                            }
                                        }
                                    )
                                }
                            } else {
                                updateLoadingMessage(loadingKey, "正在看图识别交易...")
                                val visionResult = withContext(Dispatchers.IO) {
                                    AIService.analyzeReceiptByImage(
                                        ctx = context,
                                        imageBase64 = imagePayload.base64,
                                        mimeType = imagePayload.mime,
                                        supplementText = imagePayload.supplement
                                    )
                                }
                                if (!canWriteForRequest(requestContext)) return@launch

                                updateLoadingMessage(loadingKey, "识别好了，等你核对草稿...")
                                val draftForConfirm = ReceiptImageInputHelper.mergeSupplementWithSummary(
                                    visionResult,
                                    imagePayload.supplement
                                )
                                val confirmedDraft = confirmVisualAccountingDraft(
                                    draftForConfirm,
                                    requestContext.bookName,
                                    requestContext.conversationId
                                )?.trim()
                                if (!canWriteForRequest(requestContext)) return@launch
                                if (confirmedDraft.isNullOrBlank()) {
                                    finalizeAi(
                                        loadingKey,
                                        "已取消本次图片记账。",
                                        requestContext.bookName,
                                        requestContext.conversationId
                                    )
                                    return@launch
                                }
                                val accountingInput = ReceiptImageInputHelper.buildAccountingInputFromImageDraft(
                                    confirmedDraft,
                                    imagePayload.supplement
                                )
                                accountingSourceText = accountingInput

                                updateLoadingMessage(loadingKey, "正在按确认内容整理账单...")
                                withContext(Dispatchers.IO) {
                                    AIService.analyzeAccounting(
                                        ctx = context,
                                    userInput = accountingInput,
                                    isFromChat = true,
                                    chatTurns = chatHistoryTurns,
                                    onProgress = { status ->
                                        runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                                    }
                                )?.also { root ->
                                    AIService.markVisualAccountingReviewDraft(
                                        root = root,
                                        sourceKind = "chat_image",
                                        naturalSummary = accountingInput,
                                        includePaymentMethod = Prefs.isAssetFeatureEnabled(context)
                                    )
                                }
                            }
                        }
                        }
                    } else {
                        updateLoadingMessage(loadingKey, "正在读懂这笔账...")
                        withContext(Dispatchers.IO) {
                            AIService.analyzeAccounting(
                                ctx = context,
                                userInput = analysisInput,
                                isFromChat = true,
                                chatTurns = chatHistoryTurns,
                                onProgress = { status ->
                                    runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (!shouldFallbackToAssistant(e)) throw e
                    null
                }

                if (!canWriteForRequest(requestContext)) return@launch
                finalizeChatAccountingResult(
                    loadingKey = loadingKey,
                    result = result,
                    sourceText = accountingSourceText,
                    requestContext = requestContext,
                    forceTextReply = forceTextReply,
                    parseFailureHint = if (forceTextReply) {
                        "我这次没能正确解析，但已经收到你的语音转写文本。你可以再说得更具体一点，我继续帮你记账。"
                    } else {
                        "我这次没能正确解析，你可以说得更具体一点，我继续帮你记账。"
                    }
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                removeLoadingMessage(loadingKey)
            } catch (e: Exception) {
                if (!canWriteForRequest(requestContext)) return@launch
                val msg = mapAiErrorToUserMessage(e)
                finalizeAi(loadingKey, msg, requestContext.bookName, requestContext.conversationId)
            } finally {
                clearActiveRequestIfMatch(requestContext)
            }
        }
        registerActiveJob(job, requestContext)
        job.start()
    }

    fun callAiAccountingWithVoice(audioFile: File) {
        val loadingKey = appendAi("正在听语音...", true, getCurrentBookName(), getCurrentConversationId())
        val requestContext = newRequestContext(loadingKey)
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val audioFormat = audioFile.extension.lowercase().ifBlank { "wav" }
                val directAudio = AiModelCapabilities.supportsDirectAudioInput(context)

                if (directAudio) {
                    val chatHistoryTurns = buildChatHistoryTurns("[语音消息]", requestContext)
                    if (isConversationMode()) {
                        val streamedText = StreamingTextUiBuffer()
                        val chatReply = withContext(Dispatchers.IO) {
                            AIService.generateGeneralChatReplyWithAudio(
                                ctx = context,
                                audioFile = audioFile,
                                audioFormat = audioFormat,
                                chatTurns = chatHistoryTurns,
                                openConversationMode = true,
                                onDelta = { delta ->
                                    pushStreamText(loadingKey, requestContext, streamedText, delta)
                                }
                            )
                        }
                        if (!canWriteForRequest(requestContext)) return@launch
                        if (chatReply.completed && chatReply.content.isNotBlank()) {
                            finalizeAi(
                                loadingKey,
                                chatReply.content,
                                requestContext.bookName,
                                requestContext.conversationId
                            )
                        } else {
                            finalizeAi(loadingKey, "语音回复生成失败，请重试。", requestContext.bookName, requestContext.conversationId)
                        }
                        return@launch
                    }

                    var loadingStage = 1
                    val streamedRaw = StringBuilder()
                    var lastDisplayedPreview = ""
                    var streamStarted = false
                    var lastPreviewUpdateMs = 0L
                    fun pushAudioAccountingStatus(raw: String) {
                        if (!canWriteForRequest(requestContext)) return
                        if (raw.startsWith("AI_STREAM_TEXT::")) {
                            val delta = raw.removePrefix("AI_STREAM_TEXT::")
                            if (delta.isBlank()) return
                            streamStarted = true
                            streamedRaw.append(delta)
                            val candidate = StreamingBillPreview.formatChatPreview(streamedRaw.toString(), lastDisplayedPreview)
                            if (!StreamingBillPreview.shouldUpdateUi(lastDisplayedPreview, candidate, lastPreviewUpdateMs)) return
                            lastDisplayedPreview = candidate
                            lastPreviewUpdateMs = android.os.SystemClock.elapsedRealtime()
                            updateLoadingMessage(loadingKey, candidate)
                            return
                        }
                        if (!StreamingBillPreview.shouldApplyNonStreamProgress(streamStarted)) return
                        val (stage, text) = mapProgressToNaturalStatus(raw)
                        loadingStage = maxOf(loadingStage, stage)
                        val stableText = when (loadingStage) {
                            1 -> "正在听语音..."
                            2 -> "正在整理账单..."
                            else -> text
                        }
                        if (stableText == lastDisplayedPreview) return
                        lastDisplayedPreview = stableText
                        lastPreviewUpdateMs = android.os.SystemClock.elapsedRealtime()
                        updateLoadingMessage(loadingKey, stableText)
                    }

                    val result = withContext(Dispatchers.IO) {
                        AIService.analyzeAccountingFromAudio(
                            ctx = context,
                            audioFile = audioFile,
                            audioFormat = audioFormat,
                            onProgress = { status ->
                                runOnUiIfAlive {
                                    if (canWriteForRequest(requestContext)) pushAudioAccountingStatus(status)
                                }
                            },
                            chatTurns = chatHistoryTurns
                        )
                    }
                    if (!canWriteForRequest(requestContext)) return@launch
                    finalizeChatAccountingResult(
                        loadingKey = loadingKey,
                        result = result,
                        sourceText = "[语音输入]",
                        requestContext = requestContext,
                        parseFailureHint = "我收到这段语音了，但这次没能正确解析。你可以再说得更具体一点。"
                    )
                    return@launch
                }

                val transcript = withContext(Dispatchers.IO) { transcribeVoiceToTextWithFallback(audioFile) }
                if (!canWriteForRequest(requestContext)) return@launch

                if (transcript.isBlank()) {
                    finalizeAi(loadingKey, "我没听清语音内容，你可以再说一次或直接打字。", requestContext.bookName, requestContext.conversationId)
                    return@launch
                }

                // 先做二分类
                val intent = withContext(Dispatchers.IO) {
                    AIService.classifyIntent(context, transcript)
                }
                if (!canWriteForRequest(requestContext)) return@launch

                if (isConversationMode()) {
                    if (intent == "BOOKKEEPING") {
                        // 对话模式下检测到记账请求，提示用户切换模式
                        finalizeAi(
                            loadingKey,
                            "检测到记账内容，你可以点击右上角切换到记账模式来记录这笔账",
                            requestContext.bookName,
                            requestContext.conversationId
                        )
                        return@launch
                    }
                    // 对话模式下的闲聊
                    streamConversationWithText(
                        loadingKey = loadingKey,
                        userText = transcript,
                        chatHistoryTurns = buildChatHistoryTurns(transcript, requestContext),
                        requestContext = requestContext
                    )
                    return@launch
                }

                // 记账模式下的闲聊
                if (intent == "GENERAL_CHAT") {
                    streamAccountingCasualWithText(
                        loadingKey = loadingKey,
                        userText = transcript,
                        chatHistoryTurns = buildChatHistoryTurns(transcript, requestContext),
                        requestContext = requestContext
                    )
                    return@launch
                }

                updateLoadingMessage(loadingKey, "正在整理账单...")
                val result = withContext(Dispatchers.IO) {
                    AIService.analyzeAccounting(
                        ctx = context,
                        userInput = transcript,
                        onProgress = { status ->
                            runOnUiIfAlive {
                                if (canWriteForRequest(requestContext)) {
                                    updateLoadingMessage(loadingKey, mapProgressToNaturalStatus(status).second)
                                }
                            }
                        },
                        isFromChat = true,
                        chatTurns = buildChatHistoryTurns(transcript, requestContext)
                    )
                }

                if (!canWriteForRequest(requestContext)) return@launch
                finalizeChatAccountingResult(
                    loadingKey = loadingKey,
                    result = result,
                    sourceText = transcript,
                    requestContext = requestContext,
                    parseFailureHint = "我收到这段语音了，但这次没能正确解析。你可以再说得更具体一点。"
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                removeLoadingMessage(loadingKey)
            } catch (e: Exception) {
                if (!canWriteForRequest(requestContext)) return@launch
                val msg = mapAiErrorToUserMessage(e)
                finalizeAi(loadingKey, msg, requestContext.bookName, requestContext.conversationId)
            } finally {
                clearActiveRequestIfMatch(requestContext)
            }
        }
        registerActiveJob(job, requestContext)
        job.start()
    }

    private suspend fun finalizeChatAccountingResult(
        loadingKey: String,
        result: JSONObject?,
        sourceText: String,
        requestContext: ChatRequestContext,
        forceTextReply: Boolean = true,
        parseFailureHint: String = "我这次没能正确解析，你可以说得更具体一点，我继续帮你记账。"
    ) {
        if (result == null) {
            // P1-26: forceTextReply=false 时也要给用户反馈，不能静默移除加载气泡
            finalizeAi(
                loadingKey,
                parseFailureHint,
                requestContext.bookName,
                requestContext.conversationId
            )
            return
        }
        if (result.optBoolean("no_bill", false)) {
            val reply = sanitizeAssistantReply(AIService.extractAccountingAssistantReply(result))
            val safeReply = if (replyLooksLikeBookedSuccess(reply)) "" else reply
            val text = if (forceTextReply || safeReply.isBlank()) {
                "这次没有真正入账。你可以补充金额、消费内容或账户，我再帮你记。"
            } else {
                safeReply
            }
            finalizeAi(
                loadingKey,
                text,
                requestContext.bookName,
                requestContext.conversationId
            )
            return
        }
        // 账单路径：原加载气泡换成账单卡片（不同消息类型），移除即可
        removeLoadingMessage(loadingKey)
        val savedBills = processBillResult(
            result,
            sourceText,
            requestContext.bookName,
            requestContext.conversationId
        )
        if (!canWriteForRequest(requestContext)) return
        if (savedBills.isNotEmpty()) {
            appendAccountingInlineReply(result, requestContext)
        }
    }

    private suspend fun appendAccountingInlineReply(
        result: JSONObject,
        requestContext: ChatRequestContext
    ) {
        if (Prefs.getAiChatReplyStyle(context) == "off") return
        if (!canWriteForRequest(requestContext)) return
        val reply = sanitizeAssistantReply(AIService.extractAccountingAssistantReply(result))
        if (reply.isBlank()) return
        appendAi(reply, false, requestContext.bookName, requestContext.conversationId)
    }

    private fun sanitizeAssistantReply(reply: String): String {
        var text = reply.trim()
        if (text.equals("BILL_SAVED", ignoreCase = true) || text.equals("NO_BILL", ignoreCase = true)) {
            return ""
        }
        text = text.replace(Regex("^\\s*(BILL_SAVED|NO_BILL|SCENE)\\s*[:：-]?\\s*", RegexOption.IGNORE_CASE), "")
        // Drop leaked bill JSON dumps that models sometimes put into reply.
        if (text.contains("\"bills\"") && (text.contains('{') || text.contains('['))) {
            val withoutJson = text
                .replace(Regex("已记账\\s*[:：]?\\s*\\{.*\\}", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("过去已入账[^\\n]*"), "")
                .replace(Regex("\\{\\s*\"bills\"\\s*:.*\\}", RegexOption.DOT_MATCHES_ALL), "")
                .trim()
            text = withoutJson
        }
        return text.trim()
    }

    private fun replyLooksLikeBookedSuccess(reply: String): Boolean {
        val normalized = reply.replace("\\s+".toRegex(), "")
        if (normalized.isBlank()) return false
        return listOf(
            "已记账", "已经记账", "记账成功", "入账成功", "已经记好",
            "记好了", "已经帮你记", "帮你记下", "记下了"
        ).any { normalized.contains(it) }
    }

    private suspend fun streamAccountingCasualWithText(
        loadingKey: String,
        userText: String,
        chatHistoryTurns: List<ChatTurn>,
        requestContext: ChatRequestContext
    ) {
        updateLoadingMessage(loadingKey, "正在思考...")
        val streamedText = StreamingTextUiBuffer()
        val chatReply = withContext(Dispatchers.IO) {
            AIService.generateGeneralChatReply(
                ctx = context,
                userInput = userText,
                chatTurns = chatHistoryTurns,
                accountingCasualMode = true,
                onDelta = { delta ->
                    pushStreamText(loadingKey, requestContext, streamedText, delta)
                }
            )
        }
        if (!canWriteForRequest(requestContext)) return
        if (chatReply.completed && chatReply.content.isNotBlank()) {
            finalizeAi(
                loadingKey,
                chatReply.content,
                requestContext.bookName,
                requestContext.conversationId,
                showConversationModeNudge = true
            )
        } else {
            finalizeAi(
                loadingKey,
                context.getString(R.string.chat_reply_failed),
                requestContext.bookName,
                requestContext.conversationId
            )
        }
    }

    private suspend fun streamAccountingCasualWithImages(
        loadingKey: String,
        userInput: String,
        images: List<Pair<String, String>>,
        chatHistoryTurns: List<ChatTurn>,
        requestContext: ChatRequestContext
    ) {
        updateLoadingMessage(loadingKey, "正在思考...")
        val streamedText = StreamingTextUiBuffer()
        val chatReply = withContext(Dispatchers.IO) {
            AIService.generateGeneralChatReplyWithImages(
                ctx = context,
                userInput = userInput,
                images = images,
                chatTurns = chatHistoryTurns,
                accountingCasualMode = true,
                onDelta = { delta ->
                    pushStreamText(loadingKey, requestContext, streamedText, delta)
                }
            )
        }
        if (!canWriteForRequest(requestContext)) return
        if (chatReply.completed && chatReply.content.isNotBlank()) {
            finalizeAi(
                loadingKey,
                chatReply.content,
                requestContext.bookName,
                requestContext.conversationId,
                showConversationModeNudge = true
            )
        } else {
            finalizeAi(
                loadingKey,
                context.getString(R.string.chat_reply_failed),
                requestContext.bookName,
                requestContext.conversationId
            )
        }
    }

    private suspend fun streamConversationWithText(
        loadingKey: String,
        userText: String,
        chatHistoryTurns: List<ChatTurn>,
        requestContext: ChatRequestContext
    ) {
        updateLoadingMessage(loadingKey, "正在思考...")
        val streamedText = StreamingTextUiBuffer()
        val chatReply = withContext(Dispatchers.IO) {
            AIService.generateGeneralChatReply(
                ctx = context,
                userInput = userText,
                chatTurns = chatHistoryTurns,
                openConversationMode = true,
                onDelta = { delta ->
                    pushStreamText(loadingKey, requestContext, streamedText, delta)
                }
            )
        }
        if (!canWriteForRequest(requestContext)) return
        if (chatReply.completed && chatReply.content.isNotBlank()) {
            finalizeAi(
                loadingKey,
                chatReply.content,
                requestContext.bookName,
                requestContext.conversationId
            )
        } else {
            finalizeAi(
                loadingKey,
                context.getString(R.string.chat_reply_failed),
                requestContext.bookName,
                requestContext.conversationId
            )
        }
    }

    private suspend fun streamConversationWithImages(
        loadingKey: String,
        userInput: String,
        images: List<Pair<String, String>>,
        chatHistoryTurns: List<ChatTurn>,
        requestContext: ChatRequestContext
    ) {
        updateLoadingMessage(loadingKey, "正在思考...")
        val streamedText = StreamingTextUiBuffer()
        val chatReply = withContext(Dispatchers.IO) {
            AIService.generateGeneralChatReplyWithImages(
                ctx = context,
                userInput = userInput,
                images = images,
                chatTurns = chatHistoryTurns,
                openConversationMode = true,
                onDelta = { delta ->
                    pushStreamText(loadingKey, requestContext, streamedText, delta)
                }
            )
        }
        if (!canWriteForRequest(requestContext)) return
        if (chatReply.completed && chatReply.content.isNotBlank()) {
            finalizeAi(
                loadingKey,
                chatReply.content,
                requestContext.bookName,
                requestContext.conversationId
            )
        } else {
            finalizeAi(
                loadingKey,
                context.getString(R.string.chat_reply_failed),
                requestContext.bookName,
                requestContext.conversationId
            )
        }
    }

    private fun mapAiErrorToUserMessage(error: Exception): String {
        val raw = error.message.orEmpty()
        val normalized = raw.lowercase(Locale.getDefault())
        return when {
            normalized.contains("http 500") || normalized.contains("500 internal") -> "网络不佳，请重试"
            normalized.contains("timeout") || normalized.contains("timed out") -> "网络不佳，请重试"
            normalized.contains("unable to resolve host") || normalized.contains("failed to connect") -> "网络不佳，请重试"
            raw.isBlank() -> "分析失败，请稍后重试"
            else -> "分析失败，请稍后重试"
        }
    }

    private fun shouldFallbackToAssistant(error: Exception): Boolean {
        val msg = error.message.orEmpty()
        if (msg.contains("API Key")) return false
        if (msg.contains("配置")) return false
        return error is IllegalArgumentException || msg.contains("JSON", ignoreCase = true)
    }

    private fun mapProgressToNaturalStatus(raw: String): Pair<Int, String> {
        val text = raw.trim()
        if (text.isBlank()) return 1 to "正在读懂这笔账..."
        val lower = text.lowercase(Locale.getDefault())

        if (text.contains("智能分类中") ||
            text.contains("智能分析中") ||
            text.contains("匹配中") ||
            text.contains("核对分类") ||
            text.contains("确认分类")
        ) {
            return 3 to text
        }

        return when {
            lower.contains("reply") ||
                lower.contains("respond") ||
                lower.contains("output") ||
                lower.contains("generate") ||
                lower.contains("生成") ||
                lower.contains("回复") ||
                lower.contains("整理") -> 2 to "正在整理账单..."

            lower.contains("upload") ||
                lower.contains("audio") ||
                lower.contains("image") ||
                lower.contains("ocr") ||
                lower.contains("parse") ||
                lower.contains("extract") ||
                lower.contains("analy") ||
                lower.contains("thinking") ||
                lower.contains("理解") ||
                lower.contains("分析") -> 1 to "正在读懂这笔账..."

            else -> 1 to "正在读懂这笔账..."
        }
    }
}
