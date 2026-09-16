package com.taostudio.tapaccounting

import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import java.io.File

class ChatMessagePersistenceController(
    private val context: ChatActivity,
    private val db: AppDatabase,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val aiWorkScope: CoroutineScope,
    private val displayMessages: MutableList<ChatDisplayItem>,
    private val pendingVoiceBubbleAnimations: MutableSet<String>,
    private val adapterProvider: () -> RecyclerView.Adapter<*>,
    private val rvSessionListProvider: () -> RecyclerView,
    private val drawerSessionsProvider: () -> DrawerLayout,
    private val etSessionSearchProvider: () -> android.widget.EditText,
    private val sessionAdapterProvider: () -> SessionListAdapter,
    private val allSessionRowsProvider: () -> List<ChatSessionRow>,
    private val getCurrentBookName: () -> String,
    private val getCurrentConversationId: () -> String,
    private val buildVoicePayload: (String, Int, String) -> String,
    private val scrollToBottom: (Boolean) -> Unit,
    private val followStreamUpdates: () -> Boolean,
    private val refreshSessionRows: suspend () -> Unit
) {
    private fun isUiAlive(): Boolean = !(context.isDestroyed || context.isFinishing)
    private fun previewForLog(text: String, maxLen: Int = 36): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return "<empty>"
        return if (normalized.length <= maxLen) normalized else normalized.take(maxLen) + "…"
    }

    fun appendUserMessage(text: String, type: Int, imageUri: String = "") {
        val bookSnapshot = getCurrentBookName()
        val conversationSnapshot = getCurrentConversationId()
        val timestampSnapshot = System.currentTimeMillis()
        val item = ChatDisplayItem(
            msgType = type,
            content = text,
            imageUri = imageUri,
            timestamp = timestampSnapshot
        )
        val uiKey = item.uiKey
        displayMessages.add(item)
        adapterProvider().notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom(true)
        Logger.d(
            context,
            "ChatRecord",
            "user message queued: type=$type, len=${text.length}, preview=${previewForLog(text)}"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val id = db.chatMessageDao().insert(
                ChatMessage(
                    msgType = type,
                    content = text,
                    imageUri = imageUri,
                    timestamp = timestampSnapshot,
                    bookName = bookSnapshot,
                    conversationId = conversationSnapshot
                )
            )
            withContext(Dispatchers.Main) {
                if (getCurrentBookName() == bookSnapshot && getCurrentConversationId() == conversationSnapshot) {
                    val idx = displayMessages.indexOfFirst { it.uiKey == uiKey }
                    if (idx >= 0) displayMessages[idx] = displayMessages[idx].copy(dbId = id)
                }
                lifecycleScope.launch {
                    refreshSessionRows()
                    if (drawerSessionsProvider().isDrawerOpen(androidx.core.view.GravityCompat.END) &&
                        etSessionSearchProvider().text?.toString().orEmpty().isBlank()
                    ) {
                        rvSessionListProvider().adapter = sessionAdapterProvider()
                        sessionAdapterProvider().submit(allSessionRowsProvider().toList())
                    }
                }
            }
        }
    }

    fun appendUserVoiceMessage(audioFile: File, durationSec: Int, transcript: String): ChatDisplayItem {
        val bookSnapshot = getCurrentBookName()
        val conversationSnapshot = getCurrentConversationId()
        val timestampSnapshot = System.currentTimeMillis()
        val payload = buildVoicePayload(audioFile.absolutePath, durationSec, transcript)
        val item = ChatDisplayItem(
            msgType = ChatActivity.MSG_TYPE_USER_VOICE,
            content = payload,
            timestamp = timestampSnapshot,
            voice = VoicePayload(
                audioPath = audioFile.absolutePath,
                durationSec = durationSec,
                transcript = transcript
            )
        )
        val uiKey = item.uiKey
        pendingVoiceBubbleAnimations += audioFile.absolutePath
        displayMessages.add(item)
        adapterProvider().notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom(true)
        Logger.d(
            context,
            "ChatRecord",
            "user voice queued: durationSec=$durationSec, transcriptLen=${transcript.length}, preview=${previewForLog(transcript)}"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val id = db.chatMessageDao().insert(
                ChatMessage(
                    msgType = ChatActivity.MSG_TYPE_USER_VOICE,
                    content = payload,
                    timestamp = timestampSnapshot,
                    bookName = bookSnapshot,
                    conversationId = conversationSnapshot
                )
            )
            withContext(Dispatchers.Main) {
                val idx = if (getCurrentBookName() == bookSnapshot && getCurrentConversationId() == conversationSnapshot) {
                    displayMessages.indexOfFirst { it.uiKey == uiKey }
                } else {
                    -1
                }
                if (idx >= 0) {
                    displayMessages[idx] = displayMessages[idx].copy(dbId = id)
                    val latestPayload = displayMessages[idx].content
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.chatMessageDao().getById(id)?.let { stored ->
                            if (stored.content != latestPayload) {
                                db.chatMessageDao().update(stored.copy(content = latestPayload))
                            }
                        }
                    }
                }
                refreshSessionRows()
            }
        }
        return item
    }

    fun appendAiTextMessage(
        text: String,
        isLoading: Boolean,
        bookName: String? = null,
        conversationId: String? = null,
        showConversationModeNudge: Boolean = false
    ): String {
        val targetBookName = bookName ?: getCurrentBookName()
        val targetConversationId = conversationId ?: getCurrentConversationId()
        val timestampSnapshot = System.currentTimeMillis()
        val item = ChatDisplayItem(
            msgType = ChatActivity.MSG_TYPE_AI_TEXT,
            content = text,
            timestamp = timestampSnapshot,
            isLoading = isLoading,
            showConversationModeNudge = showConversationModeNudge
        )
        val uiKey = item.uiKey
        if (isUiAlive()) {
            displayMessages.add(item)
            val insertedIndex = displayMessages.lastIndex
            adapterProvider().notifyItemInserted(insertedIndex)
            if (followStreamUpdates()) scrollToBottom(false)
        }

        if (!isLoading && text.isNotBlank()) {
            Logger.d(
                context,
                "ChatRecord",
                "ai message queued: len=${text.length}, preview=${previewForLog(text)}"
            )
            aiWorkScope.launch(Dispatchers.IO) {
                val id = db.chatMessageDao().insert(
                    ChatMessage(
                        msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                        content = text,
                        modelName = Prefs.getAiChatModel(context),
                        bookName = targetBookName,
                        conversationId = targetConversationId,
                        timestamp = timestampSnapshot
                    )
                )
                withContext(Dispatchers.Main) {
                    if (!isUiAlive()) return@withContext
                    if (getCurrentBookName() == targetBookName && getCurrentConversationId() == targetConversationId) {
                        val idx = displayMessages.indexOfFirst { it.uiKey == uiKey }
                        if (idx >= 0) {
                            // 仅回填 dbId，不重复通知（视觉上无任何变化，复通知会重渲染 Markdown 引起完成后的二次闪动）
                            displayMessages[idx] = displayMessages[idx].copy(dbId = id)
                        }
                    }
                    refreshSessionRows()
                    if (drawerSessionsProvider().isDrawerOpen(androidx.core.view.GravityCompat.END) &&
                        etSessionSearchProvider().text?.toString().orEmpty().isBlank()
                    ) {
                        rvSessionListProvider().adapter = sessionAdapterProvider()
                        sessionAdapterProvider().submit(allSessionRowsProvider().toList())
                    }
                }
            }
        }
        return item.uiKey
    }

    suspend fun persistAiTextMessage(text: String, bookName: String, conversationId: String) {
        withContext(Dispatchers.IO) {
            db.chatMessageDao().insert(
                ChatMessage(
                    msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                    content = text,
                    modelName = Prefs.getAiChatModel(context),
                    bookName = bookName,
                    conversationId = conversationId,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun removeLoadingMessage(uiKey: String) {
        if (!isUiAlive()) return
        val idx = displayMessages.indexOfFirst { it.uiKey == uiKey && it.isLoading }
        if (idx >= 0) {
            displayMessages.removeAt(idx)
            adapterProvider().notifyItemRemoved(idx)
            if (followStreamUpdates()) scrollToBottom(false)
        }
    }

    fun updateLoadingMessage(uiKey: String, text: String) {
        if (!isUiAlive()) return
        val idx = displayMessages.indexOfFirst { it.uiKey == uiKey && it.isLoading }
        if (idx < 0) return
        val current = displayMessages[idx]
        if (current.content == text) return
        displayMessages[idx] = current.copy(content = text)
        adapterProvider().notifyItemChanged(idx, ChatAdapter.PAYLOAD_LOADING_TEXT)
        // 用户停留在底部时，让流式增长始终露出最新一行（不在底部则不打扰阅读）
        if (followStreamUpdates()) scrollToBottom(false)
    }

    fun finalizeLoadingMessage(
        uiKey: String,
        text: String,
        bookName: String,
        conversationId: String,
        showConversationModeNudge: Boolean = false
    ): Boolean {
        if (!isUiAlive()) return false
        val idx = displayMessages.indexOfFirst { it.uiKey == uiKey && it.isLoading }
        if (idx < 0) return false
        val current = displayMessages[idx]
        displayMessages[idx] = current.copy(
            content = text,
            isLoading = false,
            showConversationModeNudge = showConversationModeNudge
        )
        adapterProvider().notifyItemChanged(idx)
        if (followStreamUpdates()) scrollToBottom(false)
        if (text.isNotBlank()) {
            aiWorkScope.launch(Dispatchers.IO) {
                val id = db.chatMessageDao().insert(
                    ChatMessage(
                        msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                        content = text,
                        modelName = Prefs.getAiChatModel(context),
                        bookName = bookName,
                        conversationId = conversationId,
                        timestamp = current.timestamp
                    )
                )
                withContext(Dispatchers.Main) {
                    if (!isUiAlive()) return@withContext
                    if (getCurrentBookName() == bookName && getCurrentConversationId() == conversationId) {
                        val currentIdx = displayMessages.indexOfFirst { it.uiKey == uiKey }
                        if (currentIdx >= 0) {
                            // 仅回填 dbId，避免完成后再触发一次全量 rebind（重新加载头像 / 重渲 Markdown）
                            displayMessages[currentIdx] = displayMessages[currentIdx].copy(dbId = id)
                        }
                    }
                    refreshSessionRows()
                }
            }
        }
        return true
    }

    @Deprecated("Use uiKey-based appendAiTextMessage overload.")
    fun appendAiTextMessageLegacy(text: String, isLoading: Boolean): Int {
        val uiKey = appendAiTextMessage(text, isLoading)
        return displayMessages.indexOfFirst { it.uiKey == uiKey }
    }

    @Deprecated("Use explicit book/conversation persistence.")
    suspend fun persistAiTextMessage(text: String) {
        persistAiTextMessage(text, getCurrentBookName(), getCurrentConversationId())
    }

    @Deprecated("Use uiKey-based removeLoadingMessage.")
    fun removeLoadingMessage(idx: Int) {
        val uiKey = displayMessages.getOrNull(idx)?.uiKey ?: return
        removeLoadingMessage(uiKey)
    }

    @Deprecated("Use uiKey-based updateLoadingMessage.")
    fun updateLoadingMessage(idx: Int, text: String) {
        val uiKey = displayMessages.getOrNull(idx)?.uiKey ?: return
        updateLoadingMessage(uiKey, text)
    }

    @Deprecated("Use uiKey-based finalizeLoadingMessage with explicit context.")
    fun finalizeLoadingMessage(idx: Int, text: String) {
        val uiKey = displayMessages.getOrNull(idx)?.uiKey ?: return
        finalizeLoadingMessage(uiKey, text, getCurrentBookName(), getCurrentConversationId())
    }
}

