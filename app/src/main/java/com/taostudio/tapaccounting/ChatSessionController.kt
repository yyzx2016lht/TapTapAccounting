package com.taostudio.tapaccounting

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.GravityCompat
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatSessionController(
    private val context: ChatActivity,
    private val db: AppDatabase,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val drawerSessions: DrawerLayout,
    private val drawerContainer: View,
    private val etSessionSearch: android.widget.EditText,
    private val btnNewSession: TextView,
    private val btnReplyStyle: TextView,
    private val btnChangeChatBg: TextView,
    private val btnClearCurrentSession: TextView,
    private val rvSessionList: RecyclerView,
    private val sessionAdapter: SessionListAdapter,
    private val searchResultAdapter: DrawerSearchResultAdapter,
    private val allSessionRows: MutableList<ChatSessionRow>,
    private val displayMessages: MutableList<ChatDisplayItem>,
    private val adapterProvider: () -> RecyclerView.Adapter<*>,
    private val getCurrentBookName: () -> String,
    private val setCurrentBookName: (String) -> Unit,
    private val getCurrentConversationId: () -> String,
    private val setCurrentConversationId: (String) -> Unit,
    private val newConversationId: () -> String,
    private val loadHistoryMessages: () -> Unit,
    private val parseVoicePayload: (String) -> VoicePayload,
    private val parseBillIds: (String) -> List<Long>,
    private val parseDeprecatedBillIdsFromContent: (String) -> Set<Long>,
    private val parseBillsFromMessageContent: (String) -> List<Bill>,
    private val isDeprecatedBillMessage: (String) -> Boolean,
    private val showPageCenterDialog: (AlertDialog, Float) -> Unit,
    private val showCustomConfirmDialog: (String, String, String, Boolean, () -> Unit) -> Unit,
    private val onPickBgImage: () -> Unit,
    private val onShowReplyStyleDialog: () -> Unit,
    private val onConversationSubtitleChanged: () -> Unit,
    private val cancelCurrentRequest: () -> Unit
) {
    private val sessionScanLimit = 4000
    private var drawerSearchJob: Job? = null

    fun setupSessionDrawer() {
        drawerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
            width = (context.resources.displayMetrics.widthPixels * 0.76f).toInt()
        }
        drawerSessions.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                sessionAdapter.closeSwipeActions()
            }
        })
        rvSessionList.layoutManager = LinearLayoutManager(context)
        rvSessionList.adapter = sessionAdapter
        rvSessionList.itemAnimator = null

        etSessionSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                drawerSearchJob?.cancel()
                val keyword = s?.toString().orEmpty().trim()
                if (keyword.isBlank()) {
                    lifecycleScope.launch {
                        refreshSessionRows()
                        rvSessionList.adapter = sessionAdapter
                        sessionAdapter.submit(allSessionRows.toList())
                    }
                    return
                }
                drawerSearchJob = lifecycleScope.launch {
                    delay(250)
                    val results = withContext(Dispatchers.IO) {
                        db.chatMessageDao().searchByBook(getCurrentBookName(), "%$keyword%")
                    }
                    rvSessionList.adapter = searchResultAdapter
                    searchResultAdapter.submit(results)
                }
            }
        })

        btnNewSession.setOnClickListener {
            startNewConversation()
            drawerSessions.closeDrawer(GravityCompat.END)
        }
        btnReplyStyle.setOnClickListener { onShowReplyStyleDialog() }
        btnChangeChatBg.setOnClickListener {
            drawerSessions.closeDrawer(GravityCompat.END)
            onPickBgImage()
        }
        btnClearCurrentSession.setOnClickListener {
            drawerSessions.closeDrawer(GravityCompat.END)
            confirmClearHistory()
        }
    }

    fun startNewConversation() {
        cancelCurrentRequest()
        setCurrentConversationId(newConversationId())
        displayMessages.clear()
        adapterProvider().notifyDataSetChanged()
        onConversationSubtitleChanged()
        lifecycleScope.launch { refreshSessionRows() }
        Utils.toast(context, context.getString(R.string.toast_new_session))
    }

    fun showSessionPanel() {
        lifecycleScope.launch {
            refreshSessionRows()
            rvSessionList.adapter = sessionAdapter
            sessionAdapter.submit(allSessionRows.toList())
            if (!drawerSessions.isDrawerOpen(GravityCompat.END)) {
                drawerSessions.openDrawer(GravityCompat.END)
            }
        }
    }

    fun showDeleteSessionDialog(row: ChatSessionRow) {
        sessionAdapter.closeSwipeActions()
        lifecycleScope.launch {
            val sessionBills = withContext(Dispatchers.IO) {
                loadSessionActiveBills(row.bookName, row.conversationId)
            }
            val panel = LayoutInflater.from(context)
                .inflate(R.layout.dialog_book_delete_options, null)
            panel.findViewById<TextView>(R.id.tv_delete_book_title).text = context.getString(R.string.delete_session)
            panel.findViewById<TextView>(R.id.tv_delete_book_desc).text =
                "将删除该会话聊天记录（关联账单 ${sessionBills.size} 条）"
            val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)

            fun addOption(
                title: String,
                desc: String,
                showRisk: Boolean = false,
                onClick: () -> Unit
            ) {
                val item = LayoutInflater.from(context)
                    .inflate(R.layout.item_book_delete_option, optionsContainer, false)
                item.findViewById<TextView>(R.id.tv_delete_option_title).text = title
                item.findViewById<TextView>(R.id.tv_delete_option_desc).text = desc
                item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility =
                    if (showRisk) View.VISIBLE else View.GONE
                item.setOnClickListener { onClick() }
                optionsContainer.addView(item)
            }

            val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
                .setView(panel)
                .create()

            addOption(
                title = "仅删除会话",
                desc = "保留关联账单，只删除本会话聊天记录",
                onClick = {
                    dialog.dismiss()
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            db.chatMessageDao().deleteByBookAndConversation(row.bookName, row.conversationId)
                        }
                        onSessionDeleted(row)
                    }
                }
            )
            addOption(
                title = "删除会话并删除账单",
                desc = "进入账单选择后删除，影响账本数据",
                showRisk = true,
                onClick = {
                    dialog.dismiss()
                    showDeleteSessionBillsConfirmDialog(row, sessionBills)
                }
            )

            panel.findViewById<View>(R.id.btn_delete_book_cancel).setOnClickListener {
                dialog.dismiss()
            }
            showPageCenterDialog(dialog, 0.9f)
        }
    }

    fun showRenameSessionDialog(row: ChatSessionRow) {
        val input = android.widget.EditText(context).apply {
            setText(row.title)
            setSelection(text?.length ?: 0)
            hint = "输入会话名称"
            setPadding(40, 28, 40, 28)
        }
        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
            .setTitle(context.getString(R.string.rename_session))
            .setView(input)
            .setPositiveButton(context.getString(R.string.save)) { _, _ ->
                Prefs.setAiChatSessionTitle(context, row.bookName, row.conversationId, input.text?.toString().orEmpty())
                lifecycleScope.launch { refreshRowsAndBindWhenSearchEmpty() }
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .create()
        showPageCenterDialog(dialog, 0.9f)
    }

    fun renameSessionInline(row: ChatSessionRow, newTitle: String) {
        val value = newTitle.trim()
        if (value.isBlank() || value == row.title) return
        Prefs.setAiChatSessionTitle(context, row.bookName, row.conversationId, value)
        lifecycleScope.launch { refreshRowsAndBindWhenSearchEmpty() }
    }

    fun confirmClearHistory() {
        showCustomConfirmDialog(
            "清空聊天记录",
            "该操作只会清空当前对话的聊天内容，不会删除对应账单。如果需要删除账单，请使用历史会话里的“删除会话”。",
            "清空",
            true
        ) {
            cancelCurrentRequest()
            lifecycleScope.launch {
                val voiceFiles = displayMessages
                    .mapNotNull { it.voice?.audioPath?.takeIf { path -> path.isNotBlank() } }
                    .distinct()
                withContext(Dispatchers.IO) {
                    db.chatMessageDao().deleteByBookAndConversation(getCurrentBookName(), getCurrentConversationId())
                    voiceFiles.forEach { path -> runCatching { File(path).delete() } }
                }
                displayMessages.clear()
                adapterProvider().notifyDataSetChanged()
                refreshSessionRows()
            }
        }
    }

    suspend fun refreshSessionRows() {
        val msgs = withContext(Dispatchers.IO) {
            // 防止极端大历史在会话面板刷新时一次性全量加载导致内存峰值过高。
            db.chatMessageDao().getAllByBookLimited(getCurrentBookName(), sessionScanLimit)
        }
        // Filter: only show accounting conversations (exclude agent_ prefixed ones).
        val grouped = msgs
            .groupBy { (it.bookName.ifBlank { BookAccountManager.getDefaultBook(context) }) to it.conversationId }
            .filterKeys { key ->
                val convId = key.second
                if (convId.isBlank()) return@filterKeys false
                !convId.startsWith("agent_")
            }
            .toMutableMap()
        val orderByFirstSeen = mutableMapOf<Pair<String, String>, Int>()
        grouped.entries
            .groupBy { it.key.first }
            .forEach { (_, entriesInBook) ->
                entriesInBook
                    .sortedBy { entry -> entry.value.minOfOrNull { it.timestamp } ?: Long.MAX_VALUE }
                    .forEachIndexed { index, entry ->
                        orderByFirstSeen[entry.key] = index + 1
                    }
            }

        allSessionRows.clear()
        val rows = mutableListOf<ChatSessionRow>()
        grouped.forEach { (key, list) ->
            val rowBookName = key.first
            val convId = key.second
            val latest = list.maxByOrNull { it.timestamp }
            val latestBillMsg = list
                .filter { it.msgType == ChatActivity.MSG_TYPE_AI_BILL && !isDeprecatedBillMessage(it.billIds) }
                .maxByOrNull { it.timestamp }
            val preview = runCatching {
                buildSessionPreview(latestBillMsg, latest)
            }.getOrElse {
                buildSessionPreviewFallback(latestBillMsg, latest)
            }
            val bookLabel = BookAccountManager.normalizeBookName(rowBookName).ifBlank {
                BookAccountManager.getDefaultBook(context)
            }
            val defaultTitle = "$bookLabel · 会话 ${orderByFirstSeen[key] ?: 1}"
            val savedTitle = Prefs.getAiChatSessionTitle(context, rowBookName, convId).trim()
            val oldAutoTitle = buildSessionAutoTitle(list, preview)
            val finalTitle = if (
                savedTitle.isBlank() ||
                isLegacySessionAutoTitle(savedTitle) ||
                savedTitle == oldAutoTitle
            ) {
                defaultTitle
            } else {
                savedTitle
            }
            rows += ChatSessionRow(
                bookName = rowBookName,
                conversationId = convId,
                title = finalTitle,
                preview = preview,
                displayTime = latest?.timestamp?.let {
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                } ?: "刚刚创建",
                timestamp = latest?.timestamp ?: System.currentTimeMillis(),
                isCurrent = rowBookName == getCurrentBookName() && convId == getCurrentConversationId()
            )
        }
        allSessionRows.addAll(rows.sortedByDescending { it.timestamp })
    }

    suspend fun onSessionDeleted(row: ChatSessionRow) {
        sessionAdapter.closeSwipeActions()
        if (getCurrentBookName() == row.bookName && getCurrentConversationId() == row.conversationId) {
            switchToLatestConversationOrNew(row.bookName)
            loadHistoryMessages()
        } else {
            refreshSessionRows()
            rvSessionList.adapter = sessionAdapter
            sessionAdapter.submit(allSessionRows.toList())
        }
    }

    private suspend fun refreshRowsAndBindWhenSearchEmpty() {
        refreshSessionRows()
        if (etSessionSearch.text?.toString().orEmpty().isBlank()) {
            rvSessionList.adapter = sessionAdapter
            sessionAdapter.submit(allSessionRows.toList())
        }
    }

    private suspend fun switchToLatestConversationOrNew(bookName: String) {
        setCurrentBookName(bookName)
        val latest = withContext(Dispatchers.IO) {
            db.chatMessageDao().getLatestAccountingConversationIdByBook(bookName).orEmpty()
        }
        setCurrentConversationId(if (latest.isNotBlank()) latest else newConversationId())
    }

    private suspend fun loadSessionActiveBills(bookName: String, conversationId: String): List<Bill> {
        val messages = db.chatMessageDao().getAllByBookAndConversation(bookName, conversationId)
        val ids = linkedSetOf<Long>()
        messages.filter { it.msgType == ChatActivity.MSG_TYPE_AI_BILL }.forEach { msg ->
            parseBillIds(msg.billIds).filterTo(ids) { it > 0L }
            parseDeprecatedBillIdsFromContent(msg.content).forEach { if (it > 0L) ids.add(it) }
            parseBillsFromMessageContent(msg.content).mapTo(ids) { it.id }.filter { it > 0L }
        }
        return ids.mapNotNull { db.billDao().getBillById(it) }
    }

    private suspend fun buildSessionPreview(latestBillMsg: ChatMessage?, latestMsg: ChatMessage?): String {
        if (latestBillMsg == null) return "最后一笔账单：（暂无）"
        val latestBill = withContext(Dispatchers.IO) {
            parseBillIds(latestBillMsg.billIds)
                .mapNotNull { db.billDao().getBillById(it) }
                .lastOrNull()
        } ?: parseBillsFromMessageContent(latestBillMsg.content).lastOrNull()
        val remark = latestBill?.remark?.trim().orEmpty()
        val displayRemark = when {
            remark.isNotBlank() -> remark
            latestBill != null && latestBill.categoryName.isNotBlank() -> latestBill.categoryName
            else -> "暂无"
        }
        return "最后一笔账单：（$displayRemark）"
    }

    private fun buildSessionPreviewFallback(latestBillMsg: ChatMessage?, latestMsg: ChatMessage?): String {
        val latestBill = latestBillMsg?.let { parseBillsFromMessageContent(it.content).lastOrNull() }
        val remark = latestBill?.remark?.trim().orEmpty()
        if (remark.isNotBlank()) return "最后一笔账单：（$remark）"
        val fallbackText = sanitizePreviewText(latestMsg?.content.orEmpty())
        return if (fallbackText.isNotBlank()) {
            "最近消息：${fallbackText.take(18)}"
        } else {
            "最后一笔账单：（暂无）"
        }
    }

    private fun buildSessionAutoTitle(messages: List<ChatMessage>, preview: String): String {
        val latestUserText = messages
            .asReversed()
            .firstOrNull { it.msgType == ChatActivity.MSG_TYPE_USER_TEXT }
            ?.content
            .orEmpty()
            .trim()
        if (latestUserText.isNotBlank()) {
            val clean = sanitizePreviewText(latestUserText)
                .replace(Regex("\\s+"), " ")
                .replace("。", "")
                .replace("，", " ")
                .replace(",", " ")
                .trim()
            return clean.take(16).ifBlank { "新的会话" }
        }

        val latestVoiceText = messages
            .asReversed()
            .firstOrNull { it.msgType == ChatActivity.MSG_TYPE_USER_VOICE }
            ?.let { sanitizePreviewText(parseVoicePayload(it.content).transcript.trim()) }
            .orEmpty()
        if (latestVoiceText.isNotBlank()) {
            return latestVoiceText.take(16)
        }

        if (preview.isNotBlank()) {
            return preview
                .removePrefix("最后一笔账单：（")
                .removePrefix("最近消息：")
                .removeSuffix("）")
                .trim()
                .take(16)
                .ifBlank { "新的会话" }
        }
        return "新的会话"
    }

    private fun isLegacySessionAutoTitle(title: String): Boolean {
        val t = title.trim()
        if (t == "AI对话") return true
        if (t == "新的会话") return true
        if (Regex("^记账会话\\s*\\d+$").matches(t)) return true
        return Regex("^AI对话\\d+$").matches(t)
    }

    private fun sanitizePreviewText(raw: String): String {
        if (raw.isBlank()) return ""
        val compact = raw.replace(Regex("\\s+"), " ").trim()
        if (compact.contains("base64", ignoreCase = true)) return ""
        if (compact.startsWith("data:image", ignoreCase = true)) return ""
        if (compact.startsWith("/") || compact.contains(":/")) return ""
        if (compact.contains("\\") || compact.contains("/storage/", ignoreCase = true)) return ""
        return compact
    }

    private fun showDeleteSessionBillsConfirmDialog(row: ChatSessionRow, bills: List<Bill>) {
        lifecycleScope.launch {
            val sortedBills = bills.sortedByDescending { it.time }
            val iconUrls = withContext(Dispatchers.IO) {
                sortedBills.map { bill ->
                    runCatching {
                        CategoryIconHelper.findCategoryIcon(context, bill.categoryName, bill.type)
                    }.getOrDefault("")
                }
            }
            val selectedIndexes = sortedBills.indices.toMutableSet()
            val previewView = buildDeleteBillsPreviewView(sortedBills, iconUrls, selectedIndexes)
            val density = context.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_overlay_accounting_panel)
                setPadding(dp(18), dp(16), dp(18), dp(14))
            }
            val titleView = TextView(context).apply {
                text = "选择要删除的账单"
                textSize = 18f
                setTextColor(Color.parseColor("#1F2937"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val tipView = TextView(context).apply {
                text = "仅删除你勾选的账单，并同步删除该会话聊天记录"
                textSize = 12f
                setTextColor(Color.parseColor("#7B8798"))
                setPadding(0, dp(6), 0, dp(10))
            }
            panel.addView(titleView)
            panel.addView(tipView)
            panel.addView(previewView)

            val actionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
            val btnBack = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
                gravity = android.view.Gravity.CENTER
                text = context.getString(R.string.back)
                textSize = 14f
                setTextColor(Color.parseColor("#5A677C"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_delete_dialog_cancel_btn)
            }
            val btnDelete = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) }
                gravity = android.view.Gravity.CENTER
                text = context.getString(R.string.confirm_delete)
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_delete_followup_danger_btn)
            }
            actionRow.addView(btnBack)
            actionRow.addView(btnDelete)
            panel.addView(actionRow)

            val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
                .setView(panel)
                .create()

            btnBack.setOnClickListener { dialog.dismiss() }
            btnDelete.setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val selectedBills = sortedBills.filterIndexed { index, _ -> selectedIndexes.contains(index) }
                        selectedBills.forEach { bill ->
                            com.taostudio.tapaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
                        }
                        db.chatMessageDao().deleteByBookAndConversation(row.bookName, row.conversationId)
                    }
                    onSessionDeleted(row)
                }
            }

            showPageCenterDialog(dialog, 0.92f)
        }
    }

    private fun buildDeleteBillsPreviewView(
        bills: List<Bill>,
        iconUrls: List<String>,
        selectedIndexes: MutableSet<Int>
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        val topHint = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 12f
            setTextColor(Color.parseColor("#5D6E84"))
        }
        val refreshTopHint: () -> Unit = {
            topHint.text = if (bills.isEmpty()) {
                "暂无可删除账单"
            } else {
                "已选 ${selectedIndexes.size}/${bills.size}"
            }
        }
        val btnSelectAll = TextView(context).apply {
            text = context.getString(R.string.select_all)
            textSize = 12f
            setTextColor(Color.parseColor("#4D79C7"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                selectedIndexes.clear()
                selectedIndexes.addAll(bills.indices)
                refreshDeleteBillsPreviewRows(container, bills, iconUrls, selectedIndexes, refreshTopHint)
            }
        }
        val btnClearAll = TextView(context).apply {
            text = "清空"
            textSize = 12f
            setTextColor(Color.parseColor("#7B8798"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                selectedIndexes.clear()
                refreshDeleteBillsPreviewRows(container, bills, iconUrls, selectedIndexes, refreshTopHint)
            }
        }
        topBar.addView(topHint)
        if (bills.isNotEmpty()) {
            topBar.addView(btnSelectAll)
            topBar.addView(btnClearAll)
        }
        refreshTopHint()
        container.addView(topBar)
        refreshDeleteBillsPreviewRows(container, bills, iconUrls, selectedIndexes, refreshTopHint)

        return android.widget.ScrollView(context).apply {
            isFillViewport = true
            addView(container)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320))
        }
    }

    private fun refreshDeleteBillsPreviewRows(
        container: LinearLayout,
        bills: List<Bill>,
        iconUrls: List<String>,
        selectedIndexes: MutableSet<Int>,
        onChanged: (() -> Unit)? = null
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        while (container.childCount > 1) container.removeViewAt(1)
        if (bills.isEmpty()) return
        bills.forEachIndexed { index, bill ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = context.getDrawable(R.drawable.bg_book_item_normal)
            }
            val checkbox = android.widget.CheckBox(context).apply {
                isChecked = selectedIndexes.contains(index)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIndexes.add(index) else selectedIndexes.remove(index)
                    onChanged?.invoke()
                }
            }
            val iconWrap = android.widget.FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                background = context.getDrawable(R.drawable.bg_circle_soft)
            }
            val icon = ImageView(context).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(16), dp(16), android.view.Gravity.CENTER)
                val typeColor = when (bill.type) {
                    Bill.TYPE_INCOME -> Color.parseColor("#2E7D32")
                    Bill.TYPE_TRANSFER -> Color.parseColor("#7A8598")
                    else -> BillColors.expense
                }
                setImageResource(android.R.drawable.ic_menu_info_details)
                setColorFilter(typeColor)
                val iconUrl = iconUrls.getOrNull(index).orEmpty()
                if (iconUrl.isNotBlank()) {
                    Glide.with(context).load(iconUrl).into(this)
                } else {
                    when (bill.type) {
                        Bill.TYPE_TRANSFER -> setImageResource(R.drawable.ic_transfer)
                        Bill.TYPE_INCOME -> setImageResource(R.drawable.ic_trend_up)
                        else -> setImageResource(R.drawable.ic_trend_down)
                    }
                }
            }
            iconWrap.addView(icon)
            val mid = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                }
            }
            val title = TextView(context).apply {
                text = bill.remark.ifBlank { bill.categoryName.ifBlank { context.getString(R.string.uncategorized) } }
                textSize = 13f
                setTextColor(Color.parseColor("#24364D"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val date = TextView(context).apply {
                text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(bill.time))
                textSize = 11f
                setTextColor(Color.parseColor("#8A97AB"))
            }
            val amount = TextView(context).apply {
                val prefix = when (bill.type) {
                    Bill.TYPE_INCOME -> "+"
                    Bill.TYPE_TRANSFER -> ""
                    else -> "-"
                }
                text = "$prefix${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
                textSize = 13f
                setTextColor(
                    when (bill.type) {
                        Bill.TYPE_INCOME -> Color.parseColor("#2E7D32")
                        Bill.TYPE_TRANSFER -> Color.parseColor("#7A8598")
                        else -> BillColors.expense
                    }
                )
            }
            mid.addView(title)
            mid.addView(date)
            row.addView(checkbox)
            row.addView(iconWrap)
            row.addView(mid)
            row.addView(amount)
            row.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
            container.addView(row)
            container.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
            })
        }
        onChanged?.invoke()
    }
}

