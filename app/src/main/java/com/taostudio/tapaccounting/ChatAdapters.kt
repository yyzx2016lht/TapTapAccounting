package com.taostudio.tapaccounting

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.ui.chat.ChatTypingDotsView
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import java.io.File
import com.taostudio.tapaccounting.ui.common.UiMotion
import com.taostudio.tapaccounting.ui.common.UiMotion.pressFeedback
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.ui.main.home.BillDetailSheetHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class ChatAdapter(
    private val context: ChatActivity,
    private val displayMessages: MutableList<ChatDisplayItem>,
    private val db: AppDatabase,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val isMessageSelected: (ChatDisplayItem) -> Boolean,
    private val pendingVoiceBubbleAnimations: MutableSet<String>,
    private val pendingTranscriptRevealAnimations: MutableSet<String>,
    private val visibleTranscriptPaths: MutableSet<String>,
    private val transcribingPaths: MutableSet<String>,
    private val isVoiceSelectionMode: () -> Boolean,
    private val currentPlayingPath: () -> String?,
    private val isMediaPlaying: () -> Boolean,
    private val onToggleVoiceSelection: (ChatDisplayItem) -> Unit,
    private val onPlayVoiceMessage: (ChatDisplayItem) -> Unit,
    private val onShowVoiceMessageMenu: (View, ChatDisplayItem) -> Unit,
    private val onShowTranscriptMenu: (View, ChatDisplayItem) -> Unit,
    private val onShowTextMessageMenu: (View, ChatDisplayItem) -> Unit,
    private val parseVoicePayload: (String) -> VoicePayload,
    private val copyToClipboard: (String, String, String) -> Unit,
    private val loadUserAvatar: (ImageView) -> Unit,
    private val loadAiAvatar: (ImageView) -> Unit,
    private val formatChatMessageTime: (Long) -> String,
    private val shouldShowTimestamp: (Int, Long) -> Boolean,
    private val formatTime: (Long) -> String,
    private val showSoftKeyboard: (View) -> Unit,
    private val hideSoftKeyboard: (View) -> Unit,
    private val getInlineAmountEditingBillId: () -> Long?,
    private val setInlineAmountEditingBillId: (Long?) -> Unit,
    private val onMaybeShowRuleDialogForChatBillCategoryEdit: (ChatDisplayItem, Bill, Bill) -> Unit,
    private val showCustomConfirmDialog: (String, String, String, Boolean, () -> Unit) -> Unit,
    private val onInteractiveBillAction: (ChatDisplayItem, Bill, Int) -> Unit,
    private val onOpenImagePreview: (ChatDisplayItem) -> Unit,
    private val onInterruptAiLoading: () -> Unit,
    private val isBillMessageExpanded: (ChatDisplayItem) -> Boolean,
    private val onToggleBillExpand: (ChatDisplayItem) -> Unit,
    private val onShowBillMessageMenu: (View, ChatDisplayItem) -> Unit,
    private val onBillsDeleted: (List<Long>, Long) -> Unit,
    private val onConfirmAllBills: (ChatDisplayItem) -> Unit,
    private val onSwitchConversationModeClick: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val imageThumbSizeCache = mutableMapOf<String, Pair<Int, Int>>()
    private val imageThumbSizeLoading = mutableSetOf<String>()

    companion object {
        const val PAYLOAD_LOADING_TEXT = "loading_text"

        /**
         * 已知的加载状态文案。只有这些（以及同样短小、以省略号结尾的状态式文本）
         * 才显示"打字点"小气泡；真实流式回复一律用与最终消息相同的气泡样式渲染，
         * 避免流式期间 13sp 灰字、完成后跳变为 15sp 深色 Markdown 的"缩小→变大"感。
         */
        private val LOADING_STATUS_TEXTS = setOf(
            "正在分析...",
            "正在思考...",
            "正在听语音...",
            "正在读懂这笔账...",
            "正在整理账单...",
            "正在看图识别交易...",
            "正在直接从图片生成账单...",
            "正在按确认内容整理账单...",
            "识别好了，等你核对草稿..."
        )
    }

    override fun getItemCount(): Int = displayMessages.size

    fun reconcileDeletedBillCards() {
        val activeBillIds = displayMessages
            .flatMap { item ->
                item.bills.mapNotNull { bill ->
                    bill.id.takeIf {
                        it > 0L &&
                            !item.isDeprecated &&
                            !item.deprecatedBillIds.contains(it)
                    }
                }
            }
            .distinct()
        if (activeBillIds.isEmpty()) return

        lifecycleScope.launch {
            val existingIds = withContext(Dispatchers.IO) {
                db.billDao().getBillsByIds(activeBillIds).map { it.id }.toSet()
            }
            val missingIds = activeBillIds.filterNot(existingIds::contains).toSet()
            if (missingIds.isEmpty()) return@launch

            displayMessages.toList().forEach { item ->
                val deletedForMessage = item.bills
                    .map { it.id }
                    .filter { it in missingIds }
                if (deletedForMessage.isNotEmpty()) {
                    markBillCardsDeleted(item.dbId, deletedForMessage)
                }
            }
        }
    }

    private suspend fun markBillCardsDeleted(messageDbId: Long, billIds: Collection<Long>) {
        val result = ChatBillMessageActions.markBillsDeletedFromMessage(
            db = db,
            displayMessages = displayMessages,
            messageDbId = messageDbId,
            deletedBillIds = billIds,
            formatTime = formatTime
        ) ?: return
        val msgIdx = displayMessages.indexOfFirst { it.dbId == messageDbId }
        if (msgIdx >= 0) notifyItemChanged(msgIdx)
        onBillsDeleted(result.deletedBillIds, messageDbId)
    }

    override fun getItemViewType(position: Int): Int = displayMessages[position].msgType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ChatActivity.MSG_TYPE_USER_TEXT, ChatActivity.MSG_TYPE_USER_IMAGE,
            ChatActivity.MSG_TYPE_USER_VOICE, ChatActivity.MSG_TYPE_USER_FILE ->
                UserVH(inflater.inflate(R.layout.item_chat_user, parent, false))

            ChatActivity.MSG_TYPE_AI_BILL ->
                AiBillVH(inflater.inflate(R.layout.item_chat_bill, parent, false))

            else ->
                AiTextVH(inflater.inflate(R.layout.item_chat_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = displayMessages[position]
        when (holder) {
            is UserVH -> holder.bind(item)
            is AiTextVH -> holder.bind(item)
            is AiBillVH -> holder.bind(item)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val item = displayMessages[position]
        if (holder is AiTextVH && item.isLoading && payloads.contains(PAYLOAD_LOADING_TEXT)) {
            holder.updateLoadingText(item.content)
            return
        }
        onBindViewHolder(holder, position)
    }

    private fun resolveUserBubbleMaxWidth(itemView: View, checkboxVisible: Boolean): Int {
        val res = itemView.resources
        val density = res.displayMetrics.density
        var reserved = res.getDimensionPixelSize(R.dimen.chat_message_side_inset) +
            res.getDimensionPixelSize(R.dimen.chat_msg_spacing_horizontal) +
            res.getDimensionPixelSize(R.dimen.chat_avatar_size) +
            res.getDimensionPixelSize(R.dimen.chat_avatar_margin_end)
        if (checkboxVisible) {
            reserved += (36f * density).roundToInt()
        }
        return (res.displayMetrics.widthPixels - reserved).coerceAtLeast((120f * density).roundToInt())
    }

    inner class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvText: TextView = v.findViewById(R.id.tv_user_text)
        private val tvTime: TextView = v.findViewById(R.id.tv_user_time)
        private val ivImage: ImageView = v.findViewById(R.id.iv_user_image)
        private val layoutFile: LinearLayout = v.findViewById(R.id.layout_user_file)
        private val ivFileIcon: ImageView = v.findViewById(R.id.iv_user_file_icon)
        private val tvFileName: TextView = v.findViewById(R.id.tv_user_file_name)
        private val tvFileType: TextView = v.findViewById(R.id.tv_user_file_type)
        private val layoutVoice: LinearLayout = v.findViewById(R.id.layout_user_voice)
        private val tvVoice: TextView = v.findViewById(R.id.tv_voice_text)
        private val ivVoicePlay: ImageView = v.findViewById(R.id.iv_voice_play)
        private val layoutVoiceWave: LinearLayout = v.findViewById(R.id.layout_voice_wave)
        private val layoutVoiceTranscript: LinearLayout = v.findViewById(R.id.layout_voice_transcript)
        private val tvVoiceTranscript: TextView = v.findViewById(R.id.tv_voice_transcript)
        private val ivVoiceTranscriptCopy: ImageView = v.findViewById(R.id.iv_voice_transcript_copy)
        private var waveBars: List<View> = emptyList()
        private val cbSelect: android.widget.CheckBox = v.findViewById(R.id.cb_user_select)
        private val ivUserAvatar: ImageView = v.findViewById(R.id.iv_user_avatar)

        fun bind(item: ChatDisplayItem) {
            tvTime.text = formatChatMessageTime(item.timestamp)
            tvTime.visibility = if (shouldShowTimestamp(adapterPosition, item.timestamp)) View.VISIBLE else View.GONE
            loadUserAvatar(ivUserAvatar)
            val supportsSelection = item.msgType == ChatActivity.MSG_TYPE_USER_TEXT ||
                item.msgType == ChatActivity.MSG_TYPE_USER_VOICE
            cbSelect.visibility = if (isVoiceSelectionMode() && supportsSelection) View.VISIBLE else View.GONE
            cbSelect.isChecked = isMessageSelected(item)
            layoutFile.visibility = View.GONE
            when (item.msgType) {
                ChatActivity.MSG_TYPE_USER_IMAGE -> {
                    tvText.visibility = View.GONE
                    layoutVoice.visibility = View.GONE
                    layoutVoiceTranscript.visibility = View.GONE
                    ivImage.visibility = View.VISIBLE
                    val imageUri = item.imageUri
                    ivImage.tag = imageUri
                    ivImage.setPadding(0, 0, 0, 0)
                    ivImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    imageThumbSizeCache[imageUri]?.let { (width, height) ->
                        applyImageThumbSize(intrinsicWidth = width, intrinsicHeight = height)
                    } ?: run {
                        applyImageThumbSize(intrinsicWidth = 4, intrinsicHeight = 3)
                        loadImageThumbSize(imageUri)
                    }
                    Glide.with(itemView.context)
                        .load(Uri.parse(imageUri))
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .apply(
                            RequestOptions().transform(
                                FitCenter(),
                                RoundedCorners((12f * itemView.resources.displayMetrics.density).roundToInt())
                            )
                        )
                        .into(ivImage)
                    ivImage.isClickable = true
                    ivImage.isFocusable = true
                    ivImage.contentDescription = context.getString(R.string.view_large_image)
                    ivImage.setOnClickListener {
                        onOpenImagePreview(item)
                    }
                }

                ChatActivity.MSG_TYPE_USER_FILE -> {
                    tvText.visibility = View.GONE
                    ivImage.visibility = View.GONE
                    layoutVoice.visibility = View.GONE
                    layoutVoiceTranscript.visibility = View.GONE
                    layoutFile.visibility = View.VISIBLE
                    val decoded = ChatAttachmentHelper.decodeFileMessageContent(item.content)
                    val fileName = decoded?.second?.ifBlank { context.getString(R.string.chat_attach_file) }
                        ?: item.content.ifBlank { context.getString(R.string.chat_attach_file) }
                    val mime = decoded?.first.orEmpty()
                    tvFileName.text = fileName
                    tvFileType.text = ChatAttachmentHelper.fileTypeLabel(context, mime, fileName)
                    ivFileIcon.setImageResource(R.drawable.ic_chat_file)
                    layoutFile.setOnClickListener {
                        openUserFile(item)
                    }
                    layoutFile.setOnLongClickListener {
                        onShowTextMessageMenu(tvFileName, item.copy(content = fileName))
                        true
                    }
                }

                ChatActivity.MSG_TYPE_USER_VOICE -> {
                    tvText.visibility = View.GONE
                    ivImage.visibility = View.GONE
                    layoutVoice.visibility = View.VISIBLE
                    val voice = item.voice ?: parseVoicePayload(item.content)
                    tvVoice.text = "${voice.durationSec}\""
                    val width = (44 + voice.durationSec.coerceAtMost(45) * 3.4f) * itemView.resources.displayMetrics.density
                    val waveWidth = width.toInt().coerceAtLeast(0)
                    layoutVoiceWave.layoutParams = layoutVoiceWave.layoutParams.apply {
                        this.width = waveWidth
                    }
                    updateWaveBarsForVoice(voice.durationSec, waveWidth)
                    val isPlaying = currentPlayingPath() == voice.audioPath && isMediaPlaying()
                    ivVoicePlay.setImageResource(
                        if (isPlaying) R.drawable.ic_voice_pause_telegram else R.drawable.ic_voice_play_telegram
                    )
                    val playTint = androidx.core.content.ContextCompat.getColor(
                        itemView.context,
                        R.color.chat_voice_bubble_text
                    )
                    ivVoicePlay.setColorFilter(playTint)
                    bindWaveBars(voice.audioPath, isPlaying)
                    bindVoiceTranscript(item, voice)
                    maybeAnimateFreshVoiceBubble(voice.audioPath)
                    maybeAnimateTranscriptReveal(voice.audioPath)
                    layoutVoice.alpha = if (isVoiceSelectionMode() && isMessageSelected(item)) 0.8f else 1f
                    layoutVoice.setOnClickListener {
                        if (isVoiceSelectionMode()) onToggleVoiceSelection(item) else onPlayVoiceMessage(item)
                    }
                    layoutVoice.setOnLongClickListener {
                        onShowVoiceMessageMenu(layoutVoice, item)
                        true
                    }
                }

                else -> {
                    tvText.visibility = View.VISIBLE
                    ivImage.visibility = View.GONE
                    layoutVoice.visibility = View.GONE
                    layoutVoiceTranscript.visibility = View.GONE
                    tvText.maxWidth = resolveUserBubbleMaxWidth(
                        itemView,
                        checkboxVisible = cbSelect.visibility == View.VISIBLE
                    )
                    ChatMarkdownFormatter.applyTo(tvText, item.content)
                    tvText.alpha = if (isVoiceSelectionMode() && isMessageSelected(item)) 0.8f else 1f
                    itemView.setOnClickListener {
                        if (isVoiceSelectionMode()) onToggleVoiceSelection(item)
                    }
                    itemView.setOnLongClickListener {
                        onShowTextMessageMenu(tvText, item)
                        true
                    }
                }
            }
            if (item.msgType != ChatActivity.MSG_TYPE_USER_VOICE) {
                layoutVoice.setOnClickListener(null)
                layoutVoice.setOnLongClickListener(null)
                layoutVoiceTranscript.setOnLongClickListener(null)
                tvVoiceTranscript.setOnLongClickListener(null)
                ivVoiceTranscriptCopy.setOnClickListener(null)
            }
            if (item.msgType != ChatActivity.MSG_TYPE_USER_IMAGE) {
                ivImage.setOnClickListener(null)
                ivImage.isClickable = false
            }
            if (item.msgType != ChatActivity.MSG_TYPE_USER_FILE) {
                layoutFile.setOnClickListener(null)
                layoutFile.setOnLongClickListener(null)
            }
            ivUserAvatar.setOnClickListener(null)
            itemView.setOnClickListener {
                if (isVoiceSelectionMode() && supportsSelection) onToggleVoiceSelection(item)
            }
        }

        private fun openUserFile(item: ChatDisplayItem) {
            val uriText = item.imageUri.trim()
            if (uriText.isBlank()) return
            val file = runCatching {
                val parsed = Uri.parse(uriText)
                if (parsed.scheme == "file") File(parsed.path ?: return) else File(uriText)
            }.getOrNull()
            if (file == null || !file.exists()) {
                Utils.toast(context, context.getString(R.string.chat_open_file_failed))
                return
            }
            val mime = ChatAttachmentHelper.decodeFileMessageContent(item.content)?.first ?: "*/*"
            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.chat_open_file)))
            }.onFailure {
                Utils.toast(context, context.getString(R.string.chat_open_file_failed))
            }
        }

        private fun loadImageThumbSize(imageUri: String) {
            if (imageUri.isBlank() || !imageThumbSizeLoading.add(imageUri)) return
            lifecycleScope.launch {
                val size = withContext(Dispatchers.IO) {
                    decodeImageBounds(imageUri)
                }
                imageThumbSizeLoading.remove(imageUri)
                if (size == null) return@launch
                imageThumbSizeCache[imageUri] = size
                if (ivImage.tag == imageUri) {
                    applyImageThumbSize(size.first, size.second)
                }
            }
        }

        private fun decodeImageBounds(imageUri: String): Pair<Int, Int>? {
            return runCatching {
                val uri = Uri.parse(imageUri)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth to options.outHeight
                } else {
                    null
                }
            }.getOrNull()
        }

        private fun applyImageThumbSize(intrinsicWidth: Int, intrinsicHeight: Int) {
            val density = itemView.resources.displayMetrics.density
            val maxWidth = minOf((184f * density).roundToInt(), (itemView.resources.displayMetrics.widthPixels * 0.52f).roundToInt())
            val minWidth = (92f * density).roundToInt()
            val maxHeight = (220f * density).roundToInt()
            val minHeight = (72f * density).roundToInt()
            val ratio = if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                intrinsicWidth.toFloat() / intrinsicHeight.toFloat()
            } else {
                4f / 3f
            }.coerceIn(0.42f, 2.4f)

            var width: Int
            var height: Int
            if (ratio >= 1f) {
                width = maxWidth
                height = (width / ratio).roundToInt()
                if (height < minHeight) {
                    height = minHeight
                    width = (height * ratio).roundToInt()
                }
            } else {
                height = maxHeight
                width = (height * ratio).roundToInt()
                if (width < minWidth) {
                    width = minWidth
                    height = (width / ratio).roundToInt()
                }
            }

            width = width.coerceIn(minWidth, maxWidth)
            height = height.coerceIn(minHeight, maxHeight)
            ivImage.layoutParams = ivImage.layoutParams.apply {
                this.width = width
                this.height = height
            }
        }

        private fun updateWaveBarsForVoice(durationSec: Int, waveWidthPx: Int) {
            val density = itemView.resources.displayMetrics.density
            val barCount = calculateWaveBarCount(durationSec, waveWidthPx, density)
            val barWidth = (2.4f * density).roundToInt().coerceAtLeast(2)
            val marginEnd = (1.9f * density).roundToInt().coerceAtLeast(1)
            if (layoutVoiceWave.childCount != barCount) {
                layoutVoiceWave.removeAllViews()
                repeat(barCount) {
                    val bar = View(itemView.context).apply {
                        setBackgroundResource(R.drawable.bg_chat_voice_wave_bar)
                    }
                    layoutVoiceWave.addView(bar)
                }
            }
            waveBars = (0 until layoutVoiceWave.childCount).map { layoutVoiceWave.getChildAt(it) }
            waveBars.forEachIndexed { index, bar ->
                val heightDp = 5 + ((index * 5 + durationSec * 3) % 9)
                val params = (bar.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(barWidth, (heightDp * density).toInt())
                params.width = barWidth
                params.height = (heightDp * density).roundToInt()
                params.marginEnd = if (index == waveBars.lastIndex) 0 else marginEnd
                bar.layoutParams = params
                bar.alpha = 0.6f + ((index + durationSec) % 5) * 0.07f
            }
        }

        private fun bindVoiceTranscript(item: ChatDisplayItem, voice: VoicePayload) {
            val transcript = voice.transcript.trim()
            val isVisible = visibleTranscriptPaths.contains(voice.audioPath)
            val isTranscribing = transcribingPaths.contains(voice.audioPath)

            if ((!isVisible || transcript.isBlank()) && !isTranscribing) {
                layoutVoiceTranscript.visibility = View.GONE
                layoutVoiceTranscript.alpha = 1f
                layoutVoiceTranscript.translationY = 0f
                ivVoiceTranscriptCopy.setOnClickListener(null)
                layoutVoiceTranscript.setOnLongClickListener(null)
                tvVoiceTranscript.setOnLongClickListener(null)
                return
            }

            layoutVoiceTranscript.visibility = View.VISIBLE
            tvVoiceTranscript.text = if (isTranscribing) "正在转换文字，请稍候..." else transcript
            ivVoiceTranscriptCopy.visibility = if (isTranscribing) View.GONE else View.VISIBLE

            if (isTranscribing) {
                ivVoiceTranscriptCopy.setOnClickListener(null)
                layoutVoiceTranscript.setOnLongClickListener(null)
                tvVoiceTranscript.setOnLongClickListener(null)
            } else {
                ivVoiceTranscriptCopy.setOnClickListener {
                    copyToClipboard("voice_transcript", transcript, "已复制转写文本")
                }
                val transcriptLongClick = View.OnLongClickListener {
                    if (isVoiceSelectionMode()) return@OnLongClickListener false
                    onShowTranscriptMenu(layoutVoiceTranscript, item)
                    true
                }
                layoutVoiceTranscript.setOnLongClickListener(transcriptLongClick)
                tvVoiceTranscript.setOnLongClickListener(transcriptLongClick)
            }
        }

        private fun calculateWaveBarCount(durationSec: Int, waveWidthPx: Int, density: Float): Int {
            val slotPx = (4.3f * density).coerceAtLeast(1f)
            val maxByWidth = (waveWidthPx / slotPx).toInt().coerceIn(8, 36)
            val fillRatio = when {
                durationSec <= 3 -> 0.58f
                durationSec <= 8 -> 0.72f
                durationSec <= 20 -> 0.86f
                else -> 0.92f
            }
            val byWidth = (maxByWidth * fillRatio).roundToInt()
            val byDuration = 6 + durationSec.coerceAtMost(60) / 2
            return maxOf(byWidth, byDuration).coerceIn(8, maxByWidth)
        }

        private fun bindWaveBars(audioPath: String, isPlaying: Boolean) {
            waveBars.forEachIndexed { index, bar ->
                bar.animate().cancel()
                if (isPlaying) {
                    val minScale = 0.45f + (index % 5) * 0.08f
                    bar.scaleY = minScale
                    bar.alpha = 0.55f + (index % 4) * 0.1f
                    animateWaveBar(bar, audioPath, index)
                } else {
                    bar.scaleY = 1f
                    if (bar.alpha < 0.62f) bar.alpha = 0.7f
                }
            }
        }

        private fun maybeAnimateFreshVoiceBubble(audioPath: String) {
            if (!pendingVoiceBubbleAnimations.remove(audioPath)) return
            layoutVoice.animate().cancel()
            layoutVoice.alpha = 0f
            layoutVoice.translationX = 20f
            layoutVoice.scaleX = 0.97f
            layoutVoice.scaleY = 0.97f
            layoutVoice.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(UiMotion.NORMAL)
                .setInterpolator(UiMotion.STANDARD_EASING)
                .start()
        }

        private fun maybeAnimateTranscriptReveal(audioPath: String) {
            if (layoutVoiceTranscript.visibility != View.VISIBLE) return
            if (!pendingTranscriptRevealAnimations.remove(audioPath)) return
            layoutVoiceTranscript.animate().cancel()
            layoutVoiceTranscript.alpha = 0f
            layoutVoiceTranscript.translationY = 10f
            layoutVoiceTranscript.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(UiMotion.FAST)
                .setInterpolator(UiMotion.STANDARD_EASING)
                .start()
        }

        private fun animateWaveBar(bar: View, audioPath: String, index: Int) {
            val targetScale = 1.4f - (index % 5) * 0.08f
            val duration = 170L + (index % 6) * 40L
            bar.animate()
                .scaleY(targetScale)
                .alpha(1f)
                .setDuration(duration)
                .withEndAction {
                    if (currentPlayingPath() == audioPath) {
                        bar.animate()
                            .scaleY(0.45f + index * 0.08f)
                            .alpha(0.55f + index * 0.08f)
                            .setDuration(duration)
                            .withEndAction {
                                if (currentPlayingPath() == audioPath) {
                                    animateWaveBar(bar, audioPath, index)
                                }
                            }
                            .start()
                    }
                }
                .start()
        }
    }

    inner class AiTextVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvText: TextView = v.findViewById(R.id.tv_ai_text)
        private val tvTime: TextView = v.findViewById(R.id.tv_ai_time)
        private val loadingRow: LinearLayout = v.findViewById(R.id.layout_ai_loading_row)
        private val loading: LinearLayout = v.findViewById(R.id.layout_ai_loading)
        private val typingDots: ChatTypingDotsView = v.findViewById(R.id.typing_dots)
        private val tvLoading: TextView = v.findViewById(R.id.tv_ai_loading_text)
        private val ivAvatar: ImageView = v.findViewById(R.id.iv_ai_avatar_msg)
        private val cbSelect: android.widget.CheckBox = v.findViewById(R.id.cb_ai_text_select)
        private val contentColumn: LinearLayout = v.findViewById(R.id.layout_ai_text_content)
        private val nudgeRow: LinearLayout = v.findViewById(R.id.layout_conversation_mode_nudge)
        private val btnSwitchConversationMode: TextView = v.findViewById(R.id.btn_switch_conversation_mode)

        fun bind(item: ChatDisplayItem) {
            val grouped = item.groupedWithBillReply
            val density = itemView.resources.displayMetrics.density
            val avatarSlot = (38 * density).toInt()
            val avatarGap = (10 * density).toInt()
            ivAvatar.visibility = if (grouped) View.GONE else View.VISIBLE
            (contentColumn.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
                if (grouped) avatarSlot + avatarGap else 0
            val verticalPad = context.resources.getDimensionPixelSize(R.dimen.chat_msg_spacing_vertical)
            val compactTop = (2 * density).toInt()
            val topPad = if (item.compactGroupedLayout) compactTop else verticalPad
            itemView.setPadding(itemView.paddingLeft, topPad, itemView.paddingRight, verticalPad)
            tvTime.text = formatChatMessageTime(item.timestamp)
            tvTime.visibility = if (!grouped && shouldShowTimestamp(adapterPosition, item.timestamp)) {
                View.VISIBLE
            } else {
                View.GONE
            }
            loadAiAvatar(ivAvatar)
            cbSelect.visibility = if (isVoiceSelectionMode() && !item.isLoading && item.content.isNotBlank()) View.VISIBLE else View.GONE
            cbSelect.isChecked = isMessageSelected(item)
            if (item.isLoading) {
                loadingRow.visibility = View.VISIBLE
                tvText.visibility = View.GONE
                nudgeRow.visibility = View.GONE
                updateLoadingText(item.content)
            } else {
                loadingRow.visibility = View.GONE
                tvText.visibility = View.VISIBLE
                ChatMarkdownFormatter.applyTo(tvText, item.content)
                if (item.showConversationModeNudge && item.content.isNotBlank()) {
                    nudgeRow.visibility = View.VISIBLE
                    btnSwitchConversationMode.setOnClickListener { onSwitchConversationModeClick() }
                    nudgeRow.setOnClickListener { onSwitchConversationModeClick() }
                } else {
                    nudgeRow.visibility = View.GONE
                    btnSwitchConversationMode.setOnClickListener(null)
                    nudgeRow.setOnClickListener(null)
                }
            }
            tvText.alpha = if (isVoiceSelectionMode() && isMessageSelected(item)) 0.8f else 1f
            itemView.setOnClickListener {
                if (isVoiceSelectionMode() && !item.isLoading && item.content.isNotBlank()) {
                    onToggleVoiceSelection(item)
                }
            }
            itemView.setOnLongClickListener {
                if (item.isLoading || item.content.isBlank()) return@setOnLongClickListener false
                onShowTextMessageMenu(tvText, item)
                true
            }
        }

        fun updateLoadingText(text: String) {
            val display = text.trim()
            if (isTransientStatusText(display)) {
                // 状态阶段：小气泡 = 打字点 + 状态文案（同一气泡内展示进度）
                loadingRow.visibility = View.VISIBLE
                tvText.visibility = View.GONE
                typingDots.visibility = View.VISIBLE
                tvLoading.visibility = if (display.isEmpty()) View.GONE else View.VISIBLE
                if (display.isNotEmpty()) tvLoading.text = display
                return
            }
            // 流式正文阶段：直接使用与最终消息完全相同的气泡（字号/颜色/边距/宽度一致），
            // 完成后仅追加 Markdown 样式，不再发生几何跳变。
            loadingRow.visibility = View.GONE
            tvText.visibility = View.VISIBLE
            typingDots.visibility = View.GONE
            tvLoading.visibility = View.GONE
            tvText.text = display
            tvText.linksClickable = false
            tvText.movementMethod = null
        }

        private fun isTransientStatusText(text: String): Boolean {
            if (text.isEmpty()) return true
            if (text in ChatAdapter.LOADING_STATUS_TEXTS) return true
            // 短且以省略号结尾、无换行的状态式文案（如"正在从3张图片生成账单..."）
            if (text.length <= 24 && !text.contains('\n') && text.endsWith("...")) {
                return text.startsWith("正在") ||
                    text.contains("思考") ||
                    text.contains("分析") ||
                    text.contains("识别") ||
                    text.contains("整理") ||
                    text.contains("听懂")
            }
            return false
        }
    }

    inner class AiBillVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvTime: TextView = v.findViewById(R.id.tv_ai_bill_time)
        private val container: LinearLayout = v.findViewById(R.id.container_bills)
        private val ivAvatar: ImageView = v.findViewById(R.id.iv_ai_avatar_bill)
        private val tvHint: TextView = v.findViewById(R.id.tv_ai_bill_hint)
        private val tvBillBatchSummary: TextView = v.findViewById(R.id.tv_bill_batch_summary)
        private val btnExpandBills: TextView = v.findViewById(R.id.btn_expand_bills)
        private val layoutBillBatchActions: LinearLayout = v.findViewById(R.id.layout_bill_batch_actions)
        private val btnConfirmAll: TextView = v.findViewById(R.id.btn_confirm_all_bills)
        private val btnDeleteAll: TextView = v.findViewById(R.id.btn_delete_all_bills)

        fun bind(item: ChatDisplayItem) {
            tvTime.text = formatChatMessageTime(item.timestamp)
            tvTime.visibility = if (shouldShowTimestamp(adapterPosition, item.timestamp)) View.VISIBLE else View.GONE
            loadAiAvatar(ivAvatar)
            tvHint.visibility = if (item.billHint.isBlank()) View.GONE else View.VISIBLE
            if (item.billHint.isBlank()) {
                tvHint.text = ""
            } else {
                ChatMarkdownFormatter.applyTo(tvHint, item.billHint)
            }

            val expanded = isBillMessageExpanded(item)
            val summary = ChatBillUiHelper.buildBatchSummaryText(item.bills, item.deprecatedBillIds)
            if (item.bills.size >= 2 && summary.isNotBlank()) {
                tvBillBatchSummary.visibility = View.VISIBLE
                tvBillBatchSummary.text = summary
            } else {
                tvBillBatchSummary.visibility = View.GONE
                tvBillBatchSummary.text = ""
            }

            val hiddenCount = ChatBillUiHelper.hiddenBillCount(item, expanded)
            when {
                hiddenCount > 0 -> {
                    btnExpandBills.visibility = View.VISIBLE
                    btnExpandBills.text = context.getString(R.string.chat_bill_expand_more_fmt, hiddenCount)
                    btnExpandBills.setOnClickListener { onToggleBillExpand(item) }
                }
                expanded && item.bills.size > ChatBillUiHelper.COLLAPSED_BILL_VISIBLE_COUNT -> {
                    btnExpandBills.visibility = View.VISIBLE
                    btnExpandBills.text = context.getString(R.string.chat_bill_collapse)
                    btnExpandBills.setOnClickListener { onToggleBillExpand(item) }
                }
                else -> {
                    btnExpandBills.visibility = View.GONE
                    btnExpandBills.setOnClickListener(null)
                }
            }

            container.removeAllViews()
            val displayBills = ChatBillUiHelper.billsForDisplay(item, expanded)

            displayBills.forEach { bill ->
                val index = item.bills.indexOf(bill)
                val deprecated = item.isDeprecated || item.deprecatedBillIds.contains(bill.id)
                val card = LayoutInflater.from(itemView.context)
                    .inflate(R.layout.item_chat_bill_card, container, false)
                val tvCat = card.findViewById<TextView>(R.id.tv_chat_bill_category)
                val tvDetail = card.findViewById<TextView>(R.id.tv_chat_bill_detail)
                val tvAmount = card.findViewById<TextView>(R.id.tv_chat_bill_amount)
                val etAmount = card.findViewById<android.widget.EditText>(R.id.et_chat_bill_amount)
                val tvBillTime = card.findViewById<TextView>(R.id.tv_chat_bill_time)
                val ivIcon = card.findViewById<ImageView>(R.id.iv_chat_bill_icon)
                val iconContainer = card.findViewById<View>(R.id.layout_chat_bill_icon_container)
                val btnEdit = card.findViewById<TextView>(R.id.btn_chat_bill_edit_category)
                val btnDelete = card.findViewById<TextView>(R.id.btn_chat_bill_delete)
                val tvEditedTag = card.findViewById<TextView>(R.id.tv_chat_bill_edited_tag)
                val isEdited = item.editedBillIds.contains(bill.id)
                val isTransfer = bill.type == Bill.TYPE_TRANSFER
                val showCategoryIcon = Prefs.isShowBillCategoryIcon(context)
                val showFullCategory = Prefs.isShowBillFullCategory(context)
                val remarkPriority = Prefs.isBillRemarkPriority(context)

                val categoryText = when (bill.type) {
                    Bill.TYPE_TRANSFER -> context.getString(R.string.transfer)
                    else -> BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, showFullCategory).ifBlank { context.getString(R.string.uncategorized) }
                }
                val (primaryText, secondaryText) = BillDisplayFormatter.resolvePrimarySecondaryText(
                    categoryText = categoryText,
                    remarkText = bill.remark,
                    suffixText = bill.accountName,
                    remarkPriority = remarkPriority
                )
                tvCat.text = primaryText
                tvDetail.text = secondaryText
                tvDetail.visibility = if (secondaryText.isBlank()) View.GONE else View.VISIBLE
                val sign = when (bill.type) {
                    Bill.TYPE_INCOME -> "+"
                    Bill.TYPE_TRANSFER -> ""
                    else -> "-"
                }
                val amountText = String.format(Locale.getDefault(), "%.2f", bill.amount)
                tvAmount.text = "$sign$amountText"
                val amountColor = when (bill.type) {
                    Bill.TYPE_INCOME -> Color.parseColor("#2E7D32")
                    Bill.TYPE_TRANSFER -> Color.parseColor("#7A8598")
                    else -> Color.parseColor("#D32F2F")
                }
                val amountBgRes = when (bill.type) {
                    Bill.TYPE_INCOME -> R.drawable.bg_chat_bill_amount_tag_income
                    Bill.TYPE_TRANSFER -> R.drawable.bg_chat_bill_amount_tag_transfer
                    else -> R.drawable.bg_chat_bill_amount_tag_expense
                }
                val amountBgActiveRes = when (bill.type) {
                    Bill.TYPE_INCOME -> R.drawable.bg_chat_bill_amount_tag_income_active
                    Bill.TYPE_TRANSFER -> R.drawable.bg_chat_bill_amount_tag_transfer_active
                    else -> R.drawable.bg_chat_bill_amount_tag_expense_active
                }
                tvAmount.setTextColor(amountColor)
                etAmount.setTextColor(amountColor)
                tvAmount.setBackgroundResource(amountBgRes)
                etAmount.setBackgroundResource(amountBgActiveRes)
                etAmount.setText(amountText)
                etAmount.setSelection(etAmount.text?.length ?: 0)
                tvAmount.visibility = View.VISIBLE
                etAmount.visibility = View.GONE
                tvBillTime.text = formatTime(bill.time)
                if (deprecated) {
                    val strike = android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    tvCat.paintFlags = tvCat.paintFlags or strike
                    tvDetail.paintFlags = tvDetail.paintFlags or strike
                    tvAmount.paintFlags = tvAmount.paintFlags or strike
                    tvBillTime.paintFlags = tvBillTime.paintFlags or strike
                    card.alpha = 0.55f
                    btnEdit.visibility = View.GONE
                    btnDelete.visibility = View.GONE
                    etAmount.isEnabled = false
                } else if (isEdited) {
                    // 已编辑状态：显示标签，保留正常卡片信息，但隐藏操作入口
                    tvEditedTag.visibility = View.VISIBLE
                    card.alpha = 1f
                    btnEdit.visibility = View.GONE
                    btnDelete.visibility = View.GONE
                    etAmount.isEnabled = true
                } else {
                    card.alpha = 1f
                    tvEditedTag.visibility = View.GONE
                    btnEdit.visibility = if (isTransfer) View.GONE else View.VISIBLE
                    btnDelete.visibility = View.VISIBLE
                    etAmount.isEnabled = true
                }
                val iconTint = when (bill.type) {
                    0 -> Color.parseColor("#D32F2F")
                    1 -> Color.parseColor("#2E7D32")
                    else -> Color.parseColor("#7A8598")
                }
                if (!showCategoryIcon) {
                    iconContainer.setBackgroundColor(Color.TRANSPARENT)
                    iconContainer.layoutParams = iconContainer.layoutParams.apply {
                        val widthPx = (iconContainer.resources.displayMetrics.density * 10).toInt()
                        val heightPx = (iconContainer.resources.displayMetrics.density * 38).toInt()
                        width = widthPx
                        height = heightPx
                    }
                    ivIcon.clearColorFilter()
                    ivIcon.layoutParams = ivIcon.layoutParams.apply {
                        val px = (ivIcon.resources.displayMetrics.density * 6).toInt()
                        width = px
                        height = px
                    }
                    val dotRes = when (bill.type) {
                        Bill.TYPE_EXPENSE -> R.drawable.bg_bill_dot_expense
                        Bill.TYPE_INCOME -> R.drawable.bg_bill_dot_income
                        else -> R.drawable.bg_bill_dot_neutral
                    }
                    ivIcon.setImageResource(dotRes)
                } else {
                    iconContainer.setBackgroundResource(R.drawable.bg_chat_bill_icon_container)
                    iconContainer.layoutParams = iconContainer.layoutParams.apply {
                        val widthPx = (iconContainer.resources.displayMetrics.density * 38).toInt()
                        val heightPx = (iconContainer.resources.displayMetrics.density * 38).toInt()
                        width = widthPx
                        height = heightPx
                    }
                    ivIcon.layoutParams = ivIcon.layoutParams.apply {
                        val px = (ivIcon.resources.displayMetrics.density * 26).toInt()
                        width = px
                        height = px
                    }
                    ivIcon.setImageResource(android.R.drawable.ic_menu_info_details)
                    ivIcon.setColorFilter(iconTint)
                    lifecycleScope.launch {
                        val iconUrl = withContext(Dispatchers.IO) {
                            CategoryIconHelper.findCategoryIcon(context, bill.categoryName, bill.type)
                        }
                        if (iconUrl.isNotBlank()) {
                            Glide.with(ivIcon.context)
                                .load(iconUrl)
                                .diskCacheStrategy(DiskCacheStrategy.DATA)
                                .into(ivIcon)
                        }
                    }
                }

                btnEdit.setOnClickListener {
                    if (deprecated) return@setOnClickListener
                    val pickerType = if (bill.type == Bill.TYPE_INCOME) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
                    val categoryDbType = if (bill.type == Bill.TYPE_INCOME) 1 else 0
                    OverlayDialogs.showGridCategoryPicker(context, bill.categoryName, pickerType) { selected ->
                        val originalBill = bill.copy()
                        lifecycleScope.launch {
                            val updated = withContext(Dispatchers.IO) {
                                val categoryEntity = CategoryRepository(db.categoryDao()).findCategoryByDisplayName(
                                    categoryDbType,
                                    selected
                                )
                                BillMutationService.replaceBill(
                                    db = db,
                                    oldBill = bill,
                                    newBill = bill.copy(categoryName = selected, categoryId = categoryEntity?.id)
                                )
                            }
                            val msgIdx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                            if (msgIdx >= 0) {
                                val rowIdx = displayMessages[msgIdx].bills.indexOfFirst { it.id == bill.id }
                                if (rowIdx >= 0) {
                                    displayMessages[msgIdx].bills[rowIdx] = updated
                                    this@ChatAdapter.notifyItemChanged(msgIdx)
                                }
                            }
                            onMaybeShowRuleDialogForChatBillCategoryEdit(item, originalBill, updated)
                        }
                    }
                }
                var savingAmount = false
                fun closeInlineAmountEdit(hideKeyboardNow: Boolean) {
                    if (hideKeyboardNow) hideSoftKeyboard(etAmount)
                    etAmount.clearFocus()
                    etAmount.visibility = View.GONE
                    tvAmount.visibility = View.VISIBLE
                    tvAmount.setBackgroundResource(amountBgRes)
                    if (getInlineAmountEditingBillId() == bill.id) {
                        setInlineAmountEditingBillId(null)
                    }
                }
                fun startInlineAmountEdit() {
                    if (deprecated || savingAmount) return
                    if (getInlineAmountEditingBillId() != null && getInlineAmountEditingBillId() != bill.id) {
                        Utils.toast(context, context.getString(R.string.toast_finish_amount_edit))
                        return
                    }
                    setInlineAmountEditingBillId(bill.id)
                    tvAmount.visibility = View.GONE
                    etAmount.visibility = View.VISIBLE
                    tvAmount.setBackgroundResource(amountBgRes)
                    etAmount.setBackgroundResource(amountBgActiveRes)
                    etAmount.setText(String.format(Locale.getDefault(), "%.2f", bill.amount))
                    etAmount.setSelection(etAmount.text?.length ?: 0)
                    etAmount.requestFocus()
                    showSoftKeyboard(etAmount)
                }
                fun commitInlineAmountEdit() {
                    if (deprecated || savingAmount || etAmount.visibility != View.VISIBLE) return
                    val value = etAmount.text?.toString().orEmpty().trim()
                    val editedAmount = value.toDoubleOrNull()
                    if (editedAmount == null || !editedAmount.isFinite() || editedAmount <= 0.0) {
                        Utils.toast(context, context.getString(R.string.toast_invalid_amount))
                        etAmount.requestFocus()
                        return
                    }
                    val changed = kotlin.math.abs(editedAmount - bill.amount) > 0.000001
                    if (!changed) {
                        closeInlineAmountEdit(hideKeyboardNow = true)
                        return
                    }
                    savingAmount = true
                    lifecycleScope.launch {
                        val updated = withContext(Dispatchers.IO) {
                            val updatedBill = when {
                                bill.subType == Bill.SUBTYPE_REFUND -> bill.copy(amount = editedAmount)
                                bill.type == Bill.TYPE_EXPENSE -> bill.copy(amount = editedAmount, originalAmount = editedAmount)
                                else -> bill.copy(amount = editedAmount)
                            }
                            BillMutationService.replaceBill(
                                db = db,
                                oldBill = bill,
                                newBill = updatedBill
                            )
                        }
                        savingAmount = false
                        closeInlineAmountEdit(hideKeyboardNow = true)
                        val msgIdx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                        if (msgIdx >= 0) {
                            val rowIdx = displayMessages[msgIdx].bills.indexOfFirst { it.id == bill.id }
                            if (rowIdx >= 0) {
                                displayMessages[msgIdx].bills[rowIdx] = updated
                                this@ChatAdapter.notifyItemChanged(msgIdx)
                            }
                        }
                    }
                }
                tvAmount.setOnClickListener { startInlineAmountEdit() }
                card.setOnClickListener {
                    if (deprecated || savingAmount || etAmount.visibility == View.VISIBLE) return@setOnClickListener
                    if (bill.id > 0L) {
                        BillDetailSheetHelper.showBillDetailSheet(
                            context = context,
                            lifecycleOwner = context,
                            bill = bill,
                            onBillChanged = {
                                lifecycleScope.launch {
                                    markBillCardsDeleted(item.dbId, listOf(bill.id))
                                }
                            }
                        )
                    }
                }
                etAmount.setOnEditorActionListener { _, actionId, event ->
                    val imeDone = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    val enterDown = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        event.action == android.view.KeyEvent.ACTION_DOWN
                    if (imeDone || enterDown) {
                        commitInlineAmountEdit()
                        true
                    } else {
                        false
                    }
                }
                etAmount.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && !savingAmount && etAmount.visibility == View.VISIBLE) {
                        closeInlineAmountEdit(hideKeyboardNow = false)
                    }
                }
                btnDelete.setOnClickListener {
                    if (deprecated) return@setOnClickListener
                    showCustomConfirmDialog(
                        context.getString(R.string.confirm_delete),
                        context.getString(R.string.chat_bill_delete_confirm),
                        context.getString(R.string.confirm_delete),
                        true
                    ) {
                        lifecycleScope.launch {
                            val result = ChatBillMessageActions.deleteBillsFromMessage(
                                db = db,
                                displayMessages = displayMessages,
                                messageDbId = item.dbId,
                                billsToDelete = listOf(bill),
                                formatTime = formatTime
                            )
                            val msgIdx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                            if (msgIdx >= 0) {
                                this@ChatAdapter.notifyItemChanged(msgIdx)
                            }
                            result?.deletedBillIds?.let { onBillsDeleted(it, item.dbId) }
                        }
                    }
                }

                container.addView(card)
            }
            container.alpha = if (item.isDeprecated) 0.55f else 1f

            val deletableBills = ChatBillUiHelper.deletableBills(item)
            val confirmableCount = ChatBillUiHelper.confirmableBills(item).size
            val showBatchActions = deletableBills.size >= 2 || confirmableCount > 0
            layoutBillBatchActions.visibility = if (showBatchActions) View.VISIBLE else View.GONE
            if (deletableBills.size >= 2) {
                btnDeleteAll.visibility = View.VISIBLE
                btnDeleteAll.text = context.getString(R.string.chat_bill_delete_all_fmt, deletableBills.size)
                btnDeleteAll.setOnClickListener {
                    val count = deletableBills.size
                    showCustomConfirmDialog(
                        context.getString(R.string.confirm_delete),
                        context.getString(R.string.chat_bill_delete_all_confirm, count),
                        context.getString(R.string.confirm_delete),
                        true
                    ) {
                        lifecycleScope.launch {
                            val result = ChatBillMessageActions.deleteBillsFromMessage(
                                db = db,
                                displayMessages = displayMessages,
                                messageDbId = item.dbId,
                                billsToDelete = deletableBills,
                                formatTime = formatTime
                            )
                            val msgIdx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                            if (msgIdx >= 0) {
                                this@ChatAdapter.notifyItemChanged(msgIdx)
                            }
                            result?.deletedBillIds?.let { onBillsDeleted(it, item.dbId) }
                        }
                    }
                }
            } else {
                btnDeleteAll.visibility = View.GONE
                btnDeleteAll.setOnClickListener(null)
            }
            if (confirmableCount > 0) {
                btnConfirmAll.visibility = View.VISIBLE
                btnConfirmAll.text = context.getString(R.string.chat_bill_confirm_all_fmt, confirmableCount)
                btnConfirmAll.setOnClickListener { onConfirmAllBills(item) }
            } else {
                btnConfirmAll.visibility = View.GONE
                btnConfirmAll.setOnClickListener(null)
            }

            itemView.setOnLongClickListener {
                onShowBillMessageMenu(container, item)
                true
            }
        }
    }

}

class ModelOptionAdapter(
    private val current: String,
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<ModelOptionAdapter.VH>() {
    private val list = mutableListOf<String>()

    fun submit(data: List<String>) {
        list.clear()
        list.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_option, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(list[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvName: TextView = v.findViewById(R.id.tv_model_name)
        private val tvTag: TextView = v.findViewById(R.id.tv_model_selected_tag)
        fun bind(model: String) {
            tvName.text = model
            tvTag.visibility = if (model == current) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onSelect(model) }
        }
    }
}

class SessionListAdapter(
    private val onClick: (ChatSessionRow) -> Unit,
    private val onRename: (ChatSessionRow, String) -> Unit,
    private val onDelete: (ChatSessionRow) -> Unit
) : RecyclerView.Adapter<SessionListAdapter.VH>() {
    private val list = mutableListOf<ChatSessionRow>()
    private var openedPosition: Int = RecyclerView.NO_POSITION
    private var editingPosition: Int = RecyclerView.NO_POSITION

    fun submit(data: List<ChatSessionRow>) {
        val openedKey = list.getOrNull(openedPosition)?.let { it.bookName to it.conversationId }
        val editingKey = list.getOrNull(editingPosition)?.let { it.bookName to it.conversationId }
        list.clear()
        list.addAll(data)
        openedPosition = list.indexOfFirst {
            (it.bookName to it.conversationId) == openedKey
        }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        editingPosition = list.indexOfFirst {
            (it.bookName to it.conversationId) == editingKey
        }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    fun closeSwipeActions() {
        if (openedPosition == RecyclerView.NO_POSITION) return
        val old = openedPosition
        openedPosition = RecyclerView.NO_POSITION
        notifyItemChanged(old)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_session_drawer, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = list.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(
            item = list[position],
            opened = position == openedPosition,
            editing = position == editingPosition
        )
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val foreground: View = v.findViewById(R.id.layout_session_foreground)
        private val actionLayer: View = v.findViewById(R.id.layout_session_actions)
        private val tvTitle: TextView = v.findViewById(R.id.tv_session_title)
        private val etTitle: EditText = v.findViewById(R.id.et_session_title)
        private val tvPreview: TextView = v.findViewById(R.id.tv_session_preview)
        private val tvTime: TextView = v.findViewById(R.id.tv_session_time)
        private val btnRename: ImageView = v.findViewById(R.id.btn_session_rename)
        private val btnDelete: ImageView = v.findViewById(R.id.btn_session_delete)
        private val slop = ViewConfiguration.get(v.context).scaledTouchSlop
        private val actionsWidthPx = 112f * v.resources.displayMetrics.density
        private var boundItem: ChatSessionRow? = null
        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var dragging = false

        init {
            foreground.setOnTouchListener { _, ev -> onForegroundTouch(ev) }
            foreground.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (editingPosition == pos) return@setOnClickListener
                val item = list.getOrNull(pos) ?: return@setOnClickListener
                if (openedPosition == pos) {
                    closeSwipeActions()
                    return@setOnClickListener
                }
                closeSwipeActions()
                onClick(item)
            }
            btnRename.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                closeSwipeActions()
                startInlineEdit(pos)
            }
            btnDelete.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = list.getOrNull(pos) ?: return@setOnClickListener
                closeSwipeActions()
                onDelete(item)
            }
            etTitle.setOnEditorActionListener { _, actionId, event ->
                val imeDone = actionId == EditorInfo.IME_ACTION_DONE
                val keyDone = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
                if (imeDone || keyDone) {
                    commitInlineRename()
                    true
                } else {
                    false
                }
            }
            etTitle.setOnFocusChangeListener { _, hasFocus ->
                val pos = adapterPosition
                if (!hasFocus && pos != RecyclerView.NO_POSITION && editingPosition == pos) {
                    commitInlineRename()
                }
            }
        }

        private fun onForegroundTouch(ev: MotionEvent): Boolean {
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION) return false
            if (editingPosition == pos) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startTx = foreground.translationX
                    dragging = false
                    if (openedPosition != RecyclerView.NO_POSITION && openedPosition != pos) {
                        val old = openedPosition
                        openedPosition = RecyclerView.NO_POSITION
                        notifyItemChanged(old)
                    }
                    itemView.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging) {
                        when {
                            abs(dx) > slop && abs(dx) > abs(dy) -> dragging = true
                            abs(dy) > slop -> {
                                itemView.parent?.requestDisallowInterceptTouchEvent(false)
                                return false
                            }
                        }
                    }
                    if (dragging) {
                        val tx = (startTx + dx).coerceIn(-actionsWidthPx, 0f)
                        foreground.translationX = tx
                        updateActionLayerVisibility(tx)
                        boundItem?.let { updateForegroundBackground(it, tx) }
                        return true
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    itemView.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragging) {
                        settleSwipe(pos)
                        dragging = false
                        return true
                    }
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        foreground.performClick()
                        return true
                    }
                }
            }
            return false
        }

        private fun settleSwipe(pos: Int) {
            val shouldOpen = foreground.translationX < -actionsWidthPx * 0.38f
            val target = if (shouldOpen) -actionsWidthPx else 0f
            if (shouldOpen) {
                val old = openedPosition
                openedPosition = pos
                if (old != RecyclerView.NO_POSITION && old != pos) notifyItemChanged(old)
            } else if (openedPosition == pos) {
                openedPosition = RecyclerView.NO_POSITION
            }
            foreground.animate().translationX(target).setDuration(UiMotion.FAST).setInterpolator(UiMotion.STANDARD_EASING)
                .withEndAction {
                    updateActionLayerVisibility(target)
                    boundItem?.let { updateForegroundBackground(it, target) }
                }
                .start()
        }

        private fun resolveSessionBgRes(item: ChatSessionRow, openedLike: Boolean): Int = when {
            item.isCurrent && openedLike -> R.drawable.bg_chat_session_item_selected_opened
            item.isCurrent -> R.drawable.bg_chat_session_item_selected
            openedLike -> R.drawable.bg_chat_session_item_opened
            else -> R.drawable.bg_chat_session_item
        }

        private fun updateForegroundBackground(item: ChatSessionRow, translationX: Float) {
            val openedLike = translationX < -actionsWidthPx * 0.08f
            val bgRes = resolveSessionBgRes(item, openedLike)
            if (foreground.tag != bgRes) {
                foreground.tag = bgRes
                foreground.setBackgroundResource(bgRes)
                foreground.clipToOutline = true
            }
        }

        private fun updateActionLayerVisibility(translationX: Float = foreground.translationX) {
            actionLayer.visibility =
                if (translationX < -slop * 0.5f) View.VISIBLE else View.GONE
        }

        private fun startInlineEdit(pos: Int) {
            val old = editingPosition
            editingPosition = pos
            if (old != RecyclerView.NO_POSITION && old != pos) notifyItemChanged(old)
            notifyItemChanged(pos)
        }

        private fun commitInlineRename() {
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val item = list.getOrNull(pos) ?: return
            val newTitle = etTitle.text?.toString().orEmpty().trim()
            editingPosition = RecyclerView.NO_POSITION
            hideKeyboard(etTitle)
            notifyItemChanged(pos)
            if (newTitle.isBlank() || newTitle == item.title) return
            onRename(item, newTitle)
        }

        private fun hideKeyboard(view: View) {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }

        fun bind(item: ChatSessionRow, opened: Boolean, editing: Boolean) {
            boundItem = item
            tvTitle.text = item.title
            tvPreview.text = item.preview
            tvTime.text = item.displayTime
            foreground.animate().cancel()
            foreground.translationX = if (opened) -actionsWidthPx else 0f
            updateActionLayerVisibility(foreground.translationX)
            updateForegroundBackground(item, foreground.translationX)
            if (editing) {
                tvTitle.visibility = View.GONE
                etTitle.visibility = View.VISIBLE
                if (etTitle.text?.toString() != item.title) etTitle.setText(item.title)
                etTitle.post {
                    if (adapterPosition != RecyclerView.NO_POSITION && adapterPosition == editingPosition) {
                        etTitle.requestFocus()
                        etTitle.setSelection(etTitle.text?.length ?: 0)
                        val imm = etTitle.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(etTitle, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            } else {
                tvTitle.visibility = View.VISIBLE
                etTitle.visibility = View.GONE
            }
        }
    }
}

class DrawerSearchResultAdapter(
    private val onClick: (ChatMessage) -> Unit,
    private val aiNameProvider: () -> String,
    private val parseVoicePayload: (String) -> VoicePayload,
    private val parseBillsFromMessageContent: (String) -> List<Bill>
) : RecyclerView.Adapter<DrawerSearchResultAdapter.VH>() {
    private val list = mutableListOf<ChatMessage>()

    fun submit(data: List<ChatMessage>) {
        list.clear()
        list.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_search_result, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = list.size
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(list[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvSender: TextView = v.findViewById(R.id.tv_search_sender)
        private val tvTime: TextView = v.findViewById(R.id.tv_search_time)
        private val tvContent: TextView = v.findViewById(R.id.tv_search_content)

        fun bind(msg: ChatMessage) {
            val isUser = msg.msgType in listOf(
                ChatActivity.MSG_TYPE_USER_TEXT,
                ChatActivity.MSG_TYPE_USER_IMAGE,
                ChatActivity.MSG_TYPE_USER_VOICE,
                ChatActivity.MSG_TYPE_USER_FILE
            )
            tvSender.text = if (isUser) "我" else aiNameProvider()
            tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
            tvContent.text = when (msg.msgType) {
                ChatActivity.MSG_TYPE_USER_IMAGE -> "[图片]"
                ChatActivity.MSG_TYPE_USER_FILE -> {
                    val fileName = ChatAttachmentHelper.decodeFileMessageContent(msg.content)?.second
                        ?: msg.content
                    "[文件] $fileName"
                }
                ChatActivity.MSG_TYPE_USER_VOICE -> {
                    val transcript = parseVoicePayload(msg.content).transcript
                    if (transcript.isNotBlank()) "语音：${transcript.replace(Regex("\\s+"), " ").trim().take(80)}" else "[语音]"
                }

                ChatActivity.MSG_TYPE_AI_BILL -> {
                    val bill = parseBillsFromMessageContent(msg.content).lastOrNull()
                    val remark = bill?.remark?.takeIf { it.isNotBlank() }
                        ?: bill?.categoryName
                        ?: "账单记录"
                    "账单：$remark"
                }

                else -> msg.content.trim().ifBlank { "(空内容)" }.take(100)
            }
            if (tvContent.text.contains("/") || tvContent.text.contains("\\") || tvContent.text.contains("base64", true)) {
                tvContent.text = itemView.context.getString(R.string.content_hidden)
            }
            itemView.setOnClickListener { onClick(msg) }
        }
    }
}
