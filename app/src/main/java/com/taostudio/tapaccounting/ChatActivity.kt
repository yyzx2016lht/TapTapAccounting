package com.taostudio.tapaccounting

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Selection
import android.util.Log
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.ui.common.StatusBarStyle
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import com.taostudio.tapaccounting.data.local.entity.AiRule
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.google.android.material.snackbar.Snackbar
import com.taostudio.tapaccounting.logic.BillRestoreHelper
import com.taostudio.tapaccounting.ui.main.home.CalendarActivity
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.logic.RuleDialogHelper
import com.taostudio.tapaccounting.logic.RuleLearnPromptHelper
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

class ChatActivity : AppCompatActivity() {
    private val maxVoiceRecordBytes = 8L * 1024L * 1024L
    private val maxVoiceRecordDurationSec = 180
    private enum class RuleSaveOutcome { SAVED, OVERWRITTEN, CANCELED }
    companion object {
        const val MSG_TYPE_USER_TEXT = 0
        const val MSG_TYPE_USER_IMAGE = 1
        const val MSG_TYPE_USER_VOICE = 2
        const val MSG_TYPE_USER_FILE = 7
        const val MSG_TYPE_AI_TEXT = 3
        const val MSG_TYPE_AI_BILL = 4
        const val BILL_INTERACTION_NONE = 0
        const val BILL_INTERACTIVE_ACTION_PRIMARY = 1
        const val BILL_INTERACTIVE_ACTION_SECONDARY = 2

        const val EXTRA_SOURCE_BOOK = "extra_source_book"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_MODE = "extra_chat_mode"
        const val MODE_ACCOUNTING = Prefs.CHAT_PAGE_MODE_ACCOUNTING
        const val MODE_CONVERSATION = Prefs.CHAT_PAGE_MODE_CONVERSATION
        private const val EXTRA_SCROLL_TO_MSG_ID = "scroll_to_msg_id"

        private const val REQ_PICK_IMAGE = 101
        private const val REQ_PICK_BG = 102
        private const val REQ_PICK_AI_AVATAR = 103
        private const val REQ_PICK_USER_AVATAR = 104
        private const val REQ_CROP_AI_AVATAR = 105
        private const val REQ_CROP_USER_AVATAR = 106
        private const val REQ_CROP_BG = 107
        private const val REQ_TAKE_PHOTO = 109
        private const val REQ_PICK_FILE = 110
        private const val REQ_IMAGE_PERMISSION = 1002
        private const val REQ_CAMERA_PERMISSION = 1003
    }

    private lateinit var rvMessages: RecyclerView
    private var chatLayoutManager: LinearLayoutManager? = null
    private lateinit var etInput: android.widget.EditText
    private lateinit var btnSend: ImageView
    private lateinit var btnStop: ImageView
    private lateinit var btnMore: ImageView
    private lateinit var btnChatMode: TextView
    private lateinit var btnAttach: ImageView
    private lateinit var btnVoiceToggle: ImageView
    private lateinit var btnVoiceHold: com.google.android.material.button.MaterialButton
    private lateinit var btnScrollToLatest: ImageView
    private lateinit var tvAiName: TextView
    private lateinit var tvAiModel: TextView
    private lateinit var ivAiAvatar: ImageView
    private lateinit var ivChatBg: ImageView
    private lateinit var chatRoot: View
    private lateinit var bottomBar: View
    private lateinit var drawerSessions: DrawerLayout
    private lateinit var drawerContainer: View
    private lateinit var etSessionSearch: android.widget.EditText
    private lateinit var btnNewSession: TextView
    private lateinit var btnReplyStyle: TextView
    private lateinit var btnChangeChatBg: TextView
    private lateinit var btnClearCurrentSession: TextView
    private lateinit var rvSessionList: RecyclerView
    private lateinit var tvVoiceModelHint: TextView
    private lateinit var layoutVoiceRecordOverlay: View
    private lateinit var viewVoiceRecordDot: View
    private lateinit var ivVoiceRecordState: ImageView
    private lateinit var tvVoiceRecordTitle: TextView
    private lateinit var tvVoiceRecordSubtitle: TextView
    private lateinit var tvVoiceRecordTimer: TextView
    private lateinit var layoutVoiceSelectionBar: LinearLayout
    private lateinit var tvVoiceSelectionCount: TextView
    private lateinit var btnVoiceSelectionCancel: TextView
    private lateinit var btnVoiceSelectionDelete: TextView
    private lateinit var layoutChatInputRow: View
    private lateinit var layoutPendingImages: View
    private lateinit var containerPendingImages: LinearLayout
    private lateinit var tvPendingImageCount: TextView
    private lateinit var btnClearPendingImages: TextView

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val aiScopeJob = SupervisorJob()
    private val aiWorkScope = CoroutineScope(aiScopeJob + Dispatchers.Main.immediate)
    private val messagePipeline by lazy {
        ChatMessagePipeline(
            context = this,
            aiWorkScope = aiWorkScope,
            getInputText = { etInput.text?.toString().orEmpty() },
            clearInput = { etInput.setText("") },
            updateInputActionUi = ::updateInputActionUi,
            appendUserMessage = { text, type -> appendUserMessage(text, type) },
            consumePendingHabitSuggestionReply = ::consumePendingHabitSuggestionReply,
            appendAiTextMessage = { text, loading, bookName, conversationId, showNudge ->
                appendAiTextMessage(text, loading, bookName, conversationId, showNudge)
            },
            removeLoadingMessage = ::removeLoadingMessage,
            updateLoadingMessage = ::updateLoadingMessage,
            finalizeLoadingMessage = { uiKey, text, bookName, conversationId, showNudge ->
                finalizeLoadingMessage(uiKey, text, bookName, conversationId, showNudge)
            },
            buildAnalysisInput = ::buildAnalysisInput,
            processBillResult = ::processBillResult,
            confirmVisualAccountingDraft = ::confirmVisualAccountingDraftInChat,
            buildBillSummary = ::buildBillSummary,
            transcribeVoiceToTextWithFallback = ::transcribeVoiceToTextWithFallback,
            persistAiTextMessage = ::persistAiTextMessage,
            db = db,
            getCurrentBookName = { currentBookName },
            getCurrentConversationId = { currentConversationId },
            isConversationMode = { chatMode == MODE_CONVERSATION }
        )
    }
    private val billCorrectionService by lazy {
        ChatBillCorrectionService(
            context = this,
            db = db,
            displayMessages = displayMessages,
            adapterProvider = { adapter },
            appendAiTextMessage = { text, loading, bookName, conversationId ->
                appendAiTextMessage(text, loading, bookName, conversationId)
            },
            scrollToBottom = ::scrollToBottom,
            refreshSessionRows = ::refreshSessionRows,
            getCurrentBookName = { currentBookName },
            setCurrentBookName = { currentBookName = it },
            getCurrentConversationId = { currentConversationId },
            parseTimeToMillis = ::parseTimeToMillis,
            buildBillMessageContent = ::buildBillMessageContent,
            onBillMessagesUpdated = ::syncBillReplyGrouping
        )
    }
    private val voiceController: ChatVoiceController by lazy {
        ChatVoiceController(
            context = this,
            db = db,
            lifecycleScope = lifecycleScope,
            displayMessages = displayMessages,
            adapterProvider = { adapter },
            layoutVoiceSelectionBarProvider = { layoutVoiceSelectionBar },
            tvVoiceSelectionCountProvider = { tvVoiceSelectionCount },
            pendingTranscriptRevealAnimations = pendingTranscriptRevealAnimations,
            visibleTranscriptPaths = visibleTranscriptPaths,
            transcribingPaths = transcribingPaths,
            transcribeVoiceToTextWithFallback = ::transcribeVoiceToTextWithFallback,
            scrollToBottom = ::scrollToBottom,
            showCustomConfirmDialog = { title, message, confirmText, isDanger, onConfirm ->
                uiHelperController.showCustomConfirmDialog(title, message, confirmText, isDanger, onConfirm)
            },
            findDependentAssistantMessageIds = ::findDependentAssistantMessageIds,
            refreshSessionRows = { refreshSessionRows() }
        )
    }
    private val adapter: ChatAdapter by lazy {
        ChatAdapter(
            context = this,
            displayMessages = displayMessages,
            db = db,
            lifecycleScope = lifecycleScope,
            isMessageSelected = { voiceController.isItemSelected(it) },
            pendingVoiceBubbleAnimations = pendingVoiceBubbleAnimations,
            pendingTranscriptRevealAnimations = pendingTranscriptRevealAnimations,
            visibleTranscriptPaths = visibleTranscriptPaths,
            transcribingPaths = transcribingPaths,
            isVoiceSelectionMode = { voiceController.isVoiceSelectionMode() },
            currentPlayingPath = { voiceController.currentPlayingPath() },
            isMediaPlaying = { voiceController.isMediaPlaying() },
            onToggleVoiceSelection = ::toggleVoiceSelection,
            onPlayVoiceMessage = ::playVoiceMessage,
            onShowVoiceMessageMenu = ::showVoiceMessageMenu,
            onShowTranscriptMenu = ::showTranscriptMenu,
            onShowTextMessageMenu = ::showTextMessageMenu,
            parseVoicePayload = ::parseVoicePayload,
            copyToClipboard = { label, text, toast -> uiHelperController.copyToClipboard(label, text, toast) },
            loadUserAvatar = { iv -> uiHelperController.loadUserAvatar(iv) },
            loadAiAvatar = { iv -> uiHelperController.loadAiAvatar(iv) },
            formatChatMessageTime = { ms -> uiHelperController.formatChatMessageTime(ms) },
            shouldShowTimestamp = { position, timestamp -> uiHelperController.shouldShowTimestamp(position, timestamp) },
            formatTime = { ms -> uiHelperController.formatTime(ms) },
            showSoftKeyboard = { view -> uiHelperController.showSoftKeyboard(view) },
            hideSoftKeyboard = { view -> uiHelperController.hideSoftKeyboard(view) },
            getInlineAmountEditingBillId = { inlineAmountEditingBillId },
            setInlineAmountEditingBillId = { inlineAmountEditingBillId = it },
            onMaybeShowRuleDialogForChatBillCategoryEdit = ::maybeShowRuleDialogForChatBillCategoryEdit,
            showCustomConfirmDialog = { title, message, confirmText, isDanger, onConfirm ->
                uiHelperController.showCustomConfirmDialog(title, message, confirmText, isDanger, onConfirm)
            },
            onInteractiveBillAction = ::onInteractiveBillAction,
            onOpenImagePreview = ::openImagePreview,
            onInterruptAiLoading = ::interruptAiResponse,
            isBillMessageExpanded = ::isBillMessageExpanded,
            onToggleBillExpand = ::toggleBillMessageExpand,
            onShowBillMessageMenu = ::showBillMessageMenu,
            onBillsDeleted = ::showBillDeleteUndo,
            onConfirmAllBills = ::confirmAllBillsInMessage,
            onSwitchConversationModeClick = ::showSwitchConversationModeDialog
        )
    }
    private val sessionAdapter by lazy {
        SessionListAdapter(
            onClick = { row ->
                messagePipeline.cancelCurrentRequest(showInterruptedMessage = false)
                currentBookName = row.bookName
                currentConversationId = row.conversationId
                drawerSessions.closeDrawer(GravityCompat.END)
                loadHistoryMessages()
            },
            onRename = { row, newTitle -> renameSessionInline(row, newTitle) },
            onDelete = { row -> showDeleteSessionDialog(row) }
        )
    }
    private val searchResultAdapter by lazy {
        DrawerSearchResultAdapter(
            onClick = { msg ->
                messagePipeline.cancelCurrentRequest(showInterruptedMessage = false)
                pendingScrollToMessageId = msg.id
                currentBookName = msg.bookName.ifBlank { currentBookName }
                currentConversationId = msg.conversationId.ifBlank { currentConversationId }
                drawerSessions.closeDrawer(GravityCompat.END)
                loadHistoryMessages()
            },
            aiNameProvider = { Prefs.getAiChatName(this) },
            parseVoicePayload = ::parseVoicePayload,
            parseBillsFromMessageContent = ::parseBillsFromMessageContent
        )
    }
    private val sessionController: ChatSessionController by lazy {
        ChatSessionController(
            context = this,
            db = db,
            lifecycleScope = lifecycleScope,
            drawerSessions = drawerSessions,
            drawerContainer = drawerContainer,
            etSessionSearch = etSessionSearch,
            btnNewSession = btnNewSession,
            btnReplyStyle = btnReplyStyle,
            btnChangeChatBg = btnChangeChatBg,
            btnClearCurrentSession = btnClearCurrentSession,
            rvSessionList = rvSessionList,
            sessionAdapter = sessionAdapter,
            searchResultAdapter = searchResultAdapter,
            allSessionRows = allSessionRows,
            displayMessages = displayMessages,
            adapterProvider = { adapter },
            getCurrentBookName = { currentBookName },
            setCurrentBookName = { currentBookName = it },
            getCurrentConversationId = { currentConversationId },
            setCurrentConversationId = { currentConversationId = it },
            newConversationId = ::newConversationId,
            loadHistoryMessages = ::loadHistoryMessages,
            parseVoicePayload = ::parseVoicePayload,
            parseBillIds = ::parseBillIds,
            parseDeprecatedBillIdsFromContent = ::parseDeprecatedBillIdsFromContent,
            parseBillsFromMessageContent = ::parseBillsFromMessageContent,
            isDeprecatedBillMessage = ::isDeprecatedBillMessage,
            showPageCenterDialog = { dialog, widthRatio -> uiHelperController.showPageCenterDialog(dialog, widthRatio) },
            showCustomConfirmDialog = { title, message, confirmText, isDanger, onConfirm ->
                uiHelperController.showCustomConfirmDialog(title, message, confirmText, isDanger, onConfirm)
            },
            onPickBgImage = { mediaController.pickBgImage() },
            onShowReplyStyleDialog = { panelController.showReplyStyleDialog() },
            onConversationSubtitleChanged = ::updateConversationSubtitle,
            cancelCurrentRequest = { messagePipeline.cancelCurrentRequest(showInterruptedMessage = false) }
        )
    }
    private val mediaController: ChatMediaController by lazy {
        ChatMediaController(
            context = this,
            lifecycleScope = lifecycleScope,
            tvAiNameProvider = { tvAiName },
            ivAiAvatarProvider = { ivAiAvatar },
            ivChatBgProvider = { ivChatBg },
            adapterProvider = { adapter },
            ensureAiImageFeatureEnabled = ::ensureAiImageFeatureEnabled,
            showPageCenterDialog = { dialog, widthRatio -> uiHelperController.showPageCenterDialog(dialog, widthRatio) },
            updateConversationSubtitle = ::updateConversationSubtitle,
            appendUserMessage = ::appendUserMessage,
            onAttachmentReady = ::onAttachmentReady,
            pendingAttachmentCount = { pendingImages.size },
            appendAiTextMessage = { text, loading -> appendAiTextMessage(text, loading) },
            showPageBottomDialog = { dialog -> uiHelperController.showPageBottomDialog(dialog) },
            requestGalleryPermission = ::requestGalleryPermission,
            requestCameraPermission = ::requestCameraPermission,
            reqPickImage = REQ_PICK_IMAGE,
            reqTakePhoto = REQ_TAKE_PHOTO,
            reqPickFile = REQ_PICK_FILE,
            reqPickBg = REQ_PICK_BG,
            reqCropBg = REQ_CROP_BG,
            reqPickAiAvatar = REQ_PICK_AI_AVATAR,
            reqPickUserAvatar = REQ_PICK_USER_AVATAR,
            reqCropAiAvatar = REQ_CROP_AI_AVATAR,
            reqCropUserAvatar = REQ_CROP_USER_AVATAR,
            msgTypeUserImage = MSG_TYPE_USER_IMAGE
        )
    }
    private val panelController: ChatPanelController by lazy {
        ChatPanelController(
            context = this,
            onConversationSubtitleChanged = ::updateConversationSubtitle,
            refreshVoiceSupportHint = ::refreshVoiceSupportHint,
            showPageBottomDialog = { dialog -> uiHelperController.showPageBottomDialog(dialog) }
        )
    }
    private val messageMenuController: ChatMessageMenuController by lazy {
        ChatMessageMenuController(
            context = this,
            parseVoicePayload = ::parseVoicePayload,
            hideVoiceTranscript = ::hideVoiceTranscript,
            transcribeVoiceMessage = ::transcribeVoiceMessage,
            isVoiceTranscriptVisible = ::isVoiceTranscriptVisible,
            copyToClipboard = { label, text, toast -> uiHelperController.copyToClipboard(label, text, toast) },
            enterVoiceSelectionMode = ::enterVoiceSelectionMode,
            requestDeleteFromLongPressMenu = ::requestDeleteFromLongPressMenu,
            isVoiceMode = { isVoiceMode },
            setVoiceMode = { isVoiceMode = it },
            updateVoiceModeUi = ::updateVoiceModeUi,
            etInputProvider = { etInput },
            showSoftKeyboard = { view -> uiHelperController.showSoftKeyboard(view) },
            updateInputActionUi = ::updateInputActionUi,
            deleteBillsFromMenu = ::deleteBillsFromMenu,
            openBillCalendar = ::openBillCalendarForItem,
            selectAllInTextView = ::startSelectAllInTextView,
            sharePlainText = ::sharePlainText,
            readAloud = ::readAloudText
        )
    }
    private val uiHelperController: ChatUiHelperController by lazy {
        ChatUiHelperController(
            context = this,
            displayMessagesProvider = { displayMessages }
        )
    }
    private val audioRecordController: ChatAudioRecordController by lazy {
        ChatAudioRecordController(
            context = this,
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            audioFormat = audioFormat,
            audioBufferSizeProvider = { audioBufferSize },
            btnVoiceHoldProvider = { btnVoiceHold },
            onVoiceHoldRecording = voiceInputController::setVoiceHoldRecordingAppearance,
            getAudioRecord = { audioRecord },
            setAudioRecord = { audioRecord = it },
            getAudioFile = { audioFile },
            setAudioFile = { audioFile = it },
            getRecordingThread = { recordingThread },
            setRecordingThread = { recordingThread = it },
            isRecording = { isRecording },
            setIsRecording = { isRecording = it },
            getRecordingStartAt = { recordingStartAt },
            setRecordingStartAt = { recordingStartAt = it },
            startRecordingButtonPulse = ::startRecordingButtonPulse,
            stopRecordingButtonPulse = ::stopRecordingButtonPulse,
            showVoiceRecordOverlay = ::showVoiceRecordOverlay,
            hideVoiceRecordOverlay = ::hideVoiceRecordOverlay,
            clearPendingLongPress = ::clearPendingLongPress
        )
    }
    private val historyController: ChatHistoryController by lazy {
        ChatHistoryController(
            context = this,
            db = db,
            lifecycleScope = lifecycleScope,
            displayMessages = displayMessages,
            adapterProvider = { adapter },
            rvMessagesProvider = { rvMessages },
            drawerSessionsProvider = { drawerSessions },
            etSessionSearchProvider = { etSessionSearch },
            rvSessionListProvider = { rvSessionList },
            sessionAdapterProvider = { sessionAdapter },
            allSessionRowsProvider = { allSessionRows },
            getCurrentBookName = { currentBookName },
            getCurrentConversationId = { currentConversationId },
            getPendingScrollToMessageId = { pendingScrollToMessageId },
            setPendingScrollToMessageId = { pendingScrollToMessageId = it },
            parseVoicePayload = ::parseVoicePayload,
            parseVoicePayloadStrict = ::parseVoicePayloadStrict,
            parseBillIds = ::parseBillIds,
            isDeprecatedBillMessage = ::isDeprecatedBillMessage,
            parseBillsFromMessageContent = ::parseBillsFromMessageContent,
            parseDeprecatedBillIdsFromContent = ::parseDeprecatedBillIdsFromContent,
            parseEditedBillIdsFromContent = ::parseEditedBillIdsFromContent,
            parseSnapshotOnlyFromContent = ::parseSnapshotOnlyFromContent,
            mergeChatBillSnapshots = ::mergeChatBillSnapshots,
            markBillIdsAsDeprecated = ::markBillIdsAsDeprecated,
            updateConversationSubtitle = ::updateConversationSubtitle,
            scrollToBottom = ::scrollToBottom,
            refreshSessionRows = ::refreshSessionRows,
            onHistoryLoaded = ::syncBillReplyGrouping
        )
    }
    private val messagePersistenceController: ChatMessagePersistenceController by lazy {
        ChatMessagePersistenceController(
            context = this,
            db = db,
            lifecycleScope = lifecycleScope,
            aiWorkScope = aiWorkScope,
            displayMessages = displayMessages,
            pendingVoiceBubbleAnimations = pendingVoiceBubbleAnimations,
            adapterProvider = { adapter },
            rvSessionListProvider = { rvSessionList },
            drawerSessionsProvider = { drawerSessions },
            etSessionSearchProvider = { etSessionSearch },
            sessionAdapterProvider = { sessionAdapter },
            allSessionRowsProvider = { allSessionRows },
            getCurrentBookName = { currentBookName },
            getCurrentConversationId = { currentConversationId },
            buildVoicePayload = ::buildVoicePayload,
            scrollToBottom = { force -> scrollToBottom(force) },
            followStreamUpdates = ::isUserPinnedToBottom,
            refreshSessionRows = ::refreshSessionRows
        )
    }
    private val voiceInputController: ChatVoiceInputController by lazy {
        ChatVoiceInputController(
            context = this,
            etInputProvider = { etInput },
            btnSendProvider = { btnSend },
            btnStopProvider = { btnStop },
            btnAttachProvider = { btnAttach },
            isAiGenerating = ::isAiGenerating,
            btnVoiceToggleProvider = { btnVoiceToggle },
            btnVoiceHoldProvider = { btnVoiceHold },
            layoutVoiceRecordOverlayProvider = { layoutVoiceRecordOverlay },
            viewVoiceRecordDotProvider = { viewVoiceRecordDot },
            ivVoiceRecordStateProvider = { ivVoiceRecordState },
            tvVoiceRecordTitleProvider = { tvVoiceRecordTitle },
            tvVoiceRecordSubtitleProvider = { tvVoiceRecordSubtitle },
            tvVoiceRecordTimerProvider = { tvVoiceRecordTimer },
            isVoiceMode = { isVoiceMode },
            setVoiceMode = { isVoiceMode = it },
            isRecording = { isRecording },
            setIsRecording = { isRecording = it },
            isWannaCancel = { isWannaCancel },
            setIsWannaCancel = { isWannaCancel = it },
            setIsFingerDown = { isFingerDown = it },
            setLongPressTriggered = { longPressTriggered = it },
            getRecordingStartAt = { recordingStartAt },
            ensureAiVoiceFeatureEnabled = ::ensureAiVoiceFeatureEnabled,
            ensureRecordPermission = ::ensureRecordPermission,
            clearPendingLongPress = ::clearPendingLongPress,
            startVoiceRecording = ::startVoiceRecording,
            stopVoiceRecording = ::stopVoiceRecording,
            onVoiceRecorded = ::onVoiceRecorded,
            isInlineAmountEditing = ::isInlineAmountEditing,
            ensureLastMessageVisible = { ensureLastMessageVisible(force = true) },
            refreshVoiceSupportHint = ::refreshVoiceSupportHint
        )
    }
    private val displayMessages = mutableListOf<ChatDisplayItem>()
    private val expandedBillMessageKeys = mutableSetOf<String>()
    private val allSessionRows = mutableListOf<ChatSessionRow>()
    private val pendingImages = mutableListOf<PendingImage>()

    private var currentBookName: String = BookAccountManager.DEFAULT_BOOK
    private var currentConversationId: String = ""
    private var chatMode: Int = MODE_ACCOUNTING
    private var pendingScrollToMessageId: Long = -1L
    private val deprecatedBillMessageIds = mutableSetOf<Long>()
    private var pendingHabitSuggestion: HabitRuleSuggestion? = null
    private var isVoiceMode = false
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private var isWannaCancel = false
    private var isFingerDown = false
    private var longPressTriggered = false
    private var pendingLongPressRunnable: Runnable? = null
    private val voiceHandler = Handler(Looper.getMainLooper())
    private var recordingStartAt = 0L
    private var audioSupportProbeJob: Job? = null
    private val pendingVoiceBubbleAnimations = mutableSetOf<String>()
    private val pendingTranscriptRevealAnimations = mutableSetOf<String>()
    private val visibleTranscriptPaths = mutableSetOf<String>()
    private val transcribingPaths = mutableSetOf<String>()
    private var inlineAmountEditingBillId: Long? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioBufferSize by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(sampleRate * 2)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        StatusBarStyle.applyByColor(window, getColor(R.color.chat_toolbar_bg))

        currentBookName = resolveEntryBookName(intent)
        chatMode = Prefs.getChatPageMode(this)
        pendingScrollToMessageId = intent?.getLongExtra(EXTRA_SCROLL_TO_MSG_ID, -1L) ?: -1L

        bindViews()
        setupToolbar()
        setupRecyclerView()
        setupSessionDrawer()
        setupInput()
        setupKeyboardInsets()
        setupFallbackVoiceUi()
        mediaController.refreshAiProfile()
        applyChatMode()
        mediaController.applyBackground()

        lifecycleScope.launch {
            bootstrapConversationState()
            loadHistoryMessages()
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.reconcileDeletedBillCards()
        if (::tvAiModel.isInitialized) {
            updateConversationSubtitle()
            ensureModelAudioSupportProbed()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            clearInlineAmountFocusIfTouchOutside(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun clearInlineAmountFocusIfTouchOutside(ev: MotionEvent) {
        inlineAmountEditingBillId ?: return
        val focusedAmountInput = currentFocus as? EditText ?: return
        if (focusedAmountInput.id != R.id.et_chat_bill_amount) return

        val inputBounds = Rect()
        if (focusedAmountInput.getGlobalVisibleRect(inputBounds) &&
            inputBounds.contains(ev.rawX.toInt(), ev.rawY.toInt())
        ) {
            return
        }

        uiHelperController.hideSoftKeyboard(focusedAmountInput)
        focusedAmountInput.clearFocus()
    }

    private fun bindViews() {
        rvMessages = findViewById(R.id.rv_chat_messages)
        btnScrollToLatest = findViewById(R.id.btn_chat_scroll_to_latest)
        etInput = findViewById(R.id.et_chat_input)
        btnSend = findViewById(R.id.btn_chat_send)
        btnStop = findViewById(R.id.btn_chat_stop)
        btnMore = findViewById(R.id.btn_chat_more)
        btnChatMode = findViewById(R.id.btn_chat_mode)
        btnAttach = findViewById(R.id.btn_chat_attach)
        btnVoiceToggle = findViewById(R.id.btn_voice_toggle)
        btnVoiceHold = findViewById(R.id.btn_voice_hold)
        tvAiName = findViewById(R.id.tv_ai_name)
        tvAiModel = findViewById(R.id.tv_ai_model)
        ivAiAvatar = findViewById(R.id.iv_ai_avatar)
        ivChatBg = findViewById(R.id.iv_chat_bg)
        chatRoot = findViewById(R.id.chat_root)
        bottomBar = findViewById(R.id.layout_chat_bottom)
        drawerSessions = findViewById(R.id.drawer_chat_sessions)
        drawerContainer = findViewById(R.id.layout_session_drawer_container)
        applySessionDrawerAdaptiveWidth()
        etSessionSearch = findViewById(R.id.et_session_search)
        btnNewSession = findViewById(R.id.btn_new_session)
        btnReplyStyle = findViewById(R.id.btn_reply_style)
        btnChangeChatBg = findViewById(R.id.btn_change_chat_bg)
        btnClearCurrentSession = findViewById(R.id.btn_clear_current_session)
        rvSessionList = findViewById(R.id.rv_session_list)
        tvVoiceModelHint = findViewById(R.id.tv_voice_model_hint)
        layoutChatInputRow = findViewById(R.id.layout_chat_input_row)
        layoutPendingImages = findViewById(R.id.layout_pending_images)
        containerPendingImages = findViewById(R.id.container_pending_images)
        tvPendingImageCount = findViewById(R.id.tv_pending_image_count)
        btnClearPendingImages = findViewById(R.id.btn_clear_pending_images)
        layoutVoiceRecordOverlay = findViewById(R.id.layout_voice_record_overlay)
        viewVoiceRecordDot = findViewById(R.id.view_voice_record_dot)
        ivVoiceRecordState = findViewById(R.id.iv_voice_record_state)
        tvVoiceRecordTitle = findViewById(R.id.tv_voice_record_title)
        tvVoiceRecordSubtitle = findViewById(R.id.tv_voice_record_subtitle)
        tvVoiceRecordTimer = findViewById(R.id.tv_voice_record_timer)
        layoutVoiceSelectionBar = findViewById(R.id.layout_voice_selection_bar)
        tvVoiceSelectionCount = findViewById(R.id.tv_voice_selection_count)
        btnVoiceSelectionCancel = findViewById(R.id.btn_voice_selection_cancel)
        btnVoiceSelectionDelete = findViewById(R.id.btn_voice_selection_delete)
    }

    private fun applySessionDrawerAdaptiveWidth() {
        val density = resources.displayMetrics.density
        val maxWidth = (328f * density).roundToInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val sideGap = (48f * density).roundToInt()
        val targetWidth = minOf(maxWidth, screenWidth - sideGap).coerceAtLeast((272f * density).roundToInt())
        drawerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
            width = targetWidth.coerceAtMost(screenWidth)
        }
    }

    private fun setupToolbar() {
        findViewById<ImageView>(R.id.btn_chat_back).setOnClickListener { finish() }
        btnMore.setOnClickListener { showSessionPanel() }
        btnChatMode.setOnClickListener { toggleChatPageMode() }
        ivAiAvatar.setOnClickListener { mediaController.showEditAiProfileDialog() }
        findViewById<View>(R.id.layout_ai_name_click).setOnClickListener { mediaController.showEditAiProfileDialog() }
    }

    private fun toggleChatPageMode() {
        chatMode = if (chatMode == MODE_CONVERSATION) MODE_ACCOUNTING else MODE_CONVERSATION
        Prefs.setChatPageMode(this, chatMode)
        applyChatMode()
        Utils.toast(
            this,
            getString(
                if (chatMode == MODE_CONVERSATION) {
                    R.string.chat_mode_switched_conversation
                } else {
                    R.string.chat_mode_switched_accounting
                }
            )
        )
    }

    private fun showSwitchConversationModeDialog() {
        if (chatMode == MODE_CONVERSATION) return
        val panel = layoutInflater.inflate(R.layout.dialog_book_delete_options, null)
        panel.findViewById<TextView>(R.id.tv_delete_book_title).text =
            getString(R.string.chat_switch_mode_dialog_title)
        panel.findViewById<TextView>(R.id.tv_delete_book_desc).visibility = View.GONE
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)
        optionsContainer.removeAllViews()

        val dialog = androidx.appcompat.app.AlertDialog.Builder(
            android.view.ContextThemeWrapper(this, R.style.Theme_TapAccounting)
        )
            .setView(panel)
            .create()

        fun addOption(title: String, desc: String, onClick: () -> Unit) {
            val item = layoutInflater.inflate(R.layout.item_book_delete_option, optionsContainer, false)
            item.findViewById<TextView>(R.id.tv_delete_option_title).text = title
            item.findViewById<TextView>(R.id.tv_delete_option_desc).text = desc
            item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility = View.GONE
            item.setOnClickListener {
                dialog.dismiss()
                onClick()
            }
            optionsContainer.addView(item)
        }

        panel.findViewById<View>(R.id.btn_delete_book_cancel).setOnClickListener { dialog.dismiss() }

        addOption(
            getString(R.string.chat_switch_mode_continue),
            getString(R.string.chat_switch_mode_continue_desc)
        ) {
            if (chatMode != MODE_CONVERSATION) toggleChatPageMode()
        }
        addOption(
            getString(R.string.chat_switch_mode_new_session),
            getString(R.string.chat_switch_mode_new_session_desc)
        ) {
            if (chatMode != MODE_CONVERSATION) toggleChatPageMode()
            startNewConversation()
        }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.9f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private fun applyChatMode() {
        btnAttach.visibility = View.VISIBLE
        btnChatMode.text = getString(
            if (chatMode == MODE_CONVERSATION) R.string.chat_mode_conversation else R.string.chat_mode_accounting
        )
        etInput.hint = getString(
            if (chatMode == MODE_CONVERSATION) {
                R.string.conversation_chat_input_hint
            } else {
                R.string.accounting_chat_input_hint
            }
        )
    }

    private fun setupSessionDrawer() {
        sessionController.setupSessionDrawer()
    }

    private fun setupRecyclerView() {
        chatLayoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvMessages.layoutManager = chatLayoutManager
        rvMessages.adapter = adapter
        rvMessages.itemAnimator = DefaultItemAnimator().apply {
            supportsChangeAnimations = false
            addDuration = 180L
            removeDuration = 160L
        }
        rvMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && !isInlineAmountEditing() && !voiceController.isVoiceSelectionMode()) {
                scrollToBottom(force = true)
            }
        }
        // 用户停留在底部时，流式增长应始终露出最新一行；上翻阅读历史时不硬拽。
        rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                userPinnedToBottom = computeUserPinnedToBottom()
                updateScrollToLatestButton()
            }
        })
        btnScrollToLatest.setOnClickListener {
            // 强制滚到底（用户主动操作，忽略 pinned 守卫）
            scrollToBottom(force = true)
        }
    }

    private var userPinnedToBottom: Boolean = true

    private fun isUserPinnedToBottom(): Boolean = userPinnedToBottom

    private fun computeUserPinnedToBottom(): Boolean {
        val lm = chatLayoutManager ?: return true
        if (displayMessages.isEmpty()) return true
        if (lm.findLastCompletelyVisibleItemPosition() == displayMessages.lastIndex) return true
        return !rvMessages.canScrollVertically(1)
    }

    private fun updateScrollToLatestButton() {
        if (!::btnScrollToLatest.isInitialized) return
        val shouldShow = !isUserPinnedToBottom() && displayMessages.isNotEmpty()
        if (btnScrollToLatest.visibility == View.VISIBLE != shouldShow) {
            btnScrollToLatest.visibility = if (shouldShow) View.VISIBLE else View.GONE
        }
    }

    private fun setupInput() {
        btnSend.setOnClickListener { sendText() }
        btnStop.setOnClickListener { interruptAiResponse() }
        btnAttach.setOnClickListener { mediaController.showAttachmentMenu(pendingImages.size) }
        btnClearPendingImages.setOnClickListener {
            pendingImages.clear()
            updatePendingImagePreview()
        }
        etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) scrollToBottom(force = true)
        }
        etInput.setOnClickListener { scrollToBottom(force = true) }
        etInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                updateInputActionUi()
            }
        })
        updateInputActionUi()
        btnVoiceSelectionCancel.setOnClickListener { exitVoiceSelectionMode() }
        btnVoiceSelectionDelete.setOnClickListener { deleteSelectedVoiceMessages() }
    }

    private fun requestGalleryPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mediaController.pickImagesFromSystem(pendingImages.size)
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mediaController.pickImagesFromSystem(pendingImages.size)
        } else {
            requestPermissions(arrayOf(permission), REQ_IMAGE_PERMISSION)
        }
    }

    private fun requestCameraPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mediaController.onCameraPermissionGranted()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mediaController.onCameraPermissionGranted()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_IMAGE_PERMISSION) {
            if (grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Utils.toast(this, getString(R.string.toast_album_permission))
            }
            mediaController.pickImagesFromSystem(pendingImages.size)
        } else if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                mediaController.onCameraPermissionGranted()
            } else {
                mediaController.onCameraPermissionDenied()
            }
        }
    }

    private fun setupFallbackVoiceUi() {
        btnVoiceToggle.setOnClickListener { toggleVoiceMode() }
        btnVoiceHold.setOnTouchListener { _, event -> handleVoiceButtonTouch(event) }
        updateVoiceModeUi()
        refreshVoiceSupportHint()
    }

    private fun toggleVoiceMode() {
        voiceInputController.toggleVoiceMode()
    }

    private fun updateVoiceModeUi() {
        voiceInputController.updateVoiceModeUi()
    }

    private fun updateInputActionUi() {
        voiceInputController.updateInputActionUi()
    }

    private fun startRecordingButtonPulse() {
        voiceInputController.startRecordingButtonPulse()
    }

    private fun stopRecordingButtonPulse() {
        voiceInputController.stopRecordingButtonPulse()
    }

    private fun showVoiceRecordOverlay(isCancelState: Boolean) {
        voiceInputController.showVoiceRecordOverlay(isCancelState)
    }

    private fun hideVoiceRecordOverlay() {
        voiceInputController.hideVoiceRecordOverlay()
    }

    private fun handleVoiceButtonTouch(event: MotionEvent): Boolean {
        return voiceInputController.handleVoiceButtonTouch(event)
    }

    private fun ensureRecordPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
            Utils.toast(this, getString(R.string.mic_permission_required))
        }
        return granted
    }

    private fun ensureAiVoiceFeatureEnabled(): Boolean {
        if (Prefs.isShowAiVoice(this)) return true
        uiHelperController.showCustomConfirmDialog(
            title = getString(R.string.chat_voice_dialog_title),
            message = getString(R.string.chat_voice_dialog_message),
            confirmText = getString(R.string.chat_goto_settings),
            onConfirm = {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_TAB_INDEX, 3)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        )
        return false
    }

    private fun ensureAiImageFeatureEnabled(): Boolean {
        if (Prefs.isShowAiImage(this)) return true
        uiHelperController.showCustomConfirmDialog(
            title = getString(R.string.chat_image_dialog_title),
            message = getString(R.string.chat_image_dialog_message),
            confirmText = getString(R.string.chat_goto_settings),
            onConfirm = {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_TAB_INDEX, 3)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        )
        return false
    }

    private fun clearPendingLongPress() {
        pendingLongPressRunnable?.let { voiceHandler.removeCallbacks(it) }
        pendingLongPressRunnable = null
    }

    private fun setupKeyboardInsets() {
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        val initialBottomPadding = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(chatRoot) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = ime.bottom > 0
            val extra = if (imeVisible) 0 else nav.bottom
            bottomBar.setPadding(
                bottomBar.paddingLeft,
                bottomBar.paddingTop,
                bottomBar.paddingRight,
                initialBottomPadding + extra
            )
            if (imeVisible && !isInlineAmountEditing()) ensureLastMessageVisible()
            insets
        }
    }

    private suspend fun bootstrapConversationState() {
        currentBookName = BookAccountManager.normalizeBookName(currentBookName)
        db.chatMessageDao().migrateLegacyBookAndConversation(currentBookName, "legacy")

        val fromIntentConversation = intent?.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty().trim()
        if (fromIntentConversation.isNotEmpty()) {
            // Reject agent-prefixed conversation IDs — create a new accounting conversation.
            if (fromIntentConversation.startsWith("agent_")) {
                currentConversationId = newConversationId()
                return
            }
            currentConversationId = fromIntentConversation
            return
        }

        if (pendingScrollToMessageId > 0L) {
            val msg = db.chatMessageDao().getById(pendingScrollToMessageId)
            if (msg != null) {
                if (msg.bookName.isNotBlank()) currentBookName = msg.bookName
                if (msg.conversationId.isNotBlank()) {
                    // Reject agent-prefixed conversation IDs
                    if (msg.conversationId.startsWith("agent_")) {
                        currentConversationId = newConversationId()
                        return
                    }
                    currentConversationId = msg.conversationId
                    return
                }
            }
        }

        val latest = db.chatMessageDao().getLatestAccountingConversationIdByBook(currentBookName).orEmpty()
        currentConversationId = if (latest.isNotBlank()) latest else newConversationId()
    }

    private fun newConversationId(): String {
        return "conv_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    }

    private fun startNewConversation() {
        sessionController.startNewConversation()
    }

    private fun updateConversationSubtitle() {
        val identity = Prefs.getAiChatIdentity(this).trim()
        if (identity.isNotBlank()) {
            tvAiModel.text = identity
            tvAiModel.visibility = View.VISIBLE
        } else {
            tvAiModel.visibility = View.GONE
        }
    }

    private fun showSessionPanel() {
        sessionController.showSessionPanel()
    }

    private fun showDeleteSessionDialog(row: ChatSessionRow) {
        sessionController.showDeleteSessionDialog(row)
    }

    private suspend fun onSessionDeleted(row: ChatSessionRow) {
        sessionController.onSessionDeleted(row)
    }

    private fun confirmClearHistory() {
        sessionController.confirmClearHistory()
    }

    private suspend fun refreshSessionRows() {
        sessionController.refreshSessionRows()
    }

    private fun showRenameSessionDialog(row: ChatSessionRow) {
        sessionController.showRenameSessionDialog(row)
    }

    private fun renameSessionInline(row: ChatSessionRow, newTitle: String) {
        sessionController.renameSessionInline(row, newTitle)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (mediaController.handleActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startVoiceRecording(): Boolean {
        return audioRecordController.startVoiceRecording()
    }

    private fun stopVoiceRecording(onFileReady: (File?, Int) -> Unit) {
        audioRecordController.stopVoiceRecording(onFileReady)
    }

    private fun onVoiceRecorded(tempFile: File, durationSec: Int) {
        lifecycleScope.launch {
            val copiedFile = withContext(Dispatchers.IO) { copyVoiceFileToStorage(tempFile) }
            if (durationSec > maxVoiceRecordDurationSec || copiedFile.length() > maxVoiceRecordBytes) {
                runCatching { copiedFile.delete() }
                appendAiTextMessage(getString(R.string.voice_too_long), isLoading = false)
                return@launch
            }

            appendUserVoiceMessage(copiedFile, durationSec, "")
            val audioPath = copiedFile.absolutePath
            val directAudioInput = currentChatModelSupportsDirectAudioInput()
            Logger.d(
                this@ChatActivity,
                "ChatVoiceRoute",
                "directAudio=$directAudioInput, provider=${Prefs.getAiProvider(this@ChatActivity)}, model=${AiModelSlots.resolveChatModel(this@ChatActivity)}"
            )
            if (directAudioInput) {
                callAiAccountingWithVoice(copiedFile)
                return@launch
            }

            transcribingPaths.add(audioPath)
            refreshVoiceMessageUi(audioPath)

            val transcript = withContext(Dispatchers.IO) {
                transcribeVoiceToTextWithFallback(copiedFile)
            }.trim()

            transcribingPaths.remove(audioPath)
            refreshVoiceMessageUi(audioPath)

            when {
                transcript == "API_KEY_NOT_SETUP" -> {
                    appendAiTextMessage(getString(R.string.api_key_required_cloud_asr), isLoading = false)
                    return@launch
                }
                transcript == "MODEL_DOWNLOADING" -> {
                    appendAiTextMessage(getString(R.string.asr_model_downloading), isLoading = false)
                    return@launch
                }
                transcript == "WHISPER_NOT_SETUP" -> {
                    appendAiTextMessage(getString(R.string.asr_model_needed_for_local), isLoading = false)
                    return@launch
                }
                transcript.isBlank() -> {
                    appendAiTextMessage(getString(R.string.voice_not_clear), isLoading = false)
                    return@launch
                }
            }

            updateVoiceTranscriptByPath(audioPath, transcript, revealTranscript = false)
            callAiAccounting(
                userText = transcript,
                appendUserBubble = false,
                forceTextReply = true
            )
        }
    }

    private fun refreshVoiceMessageUi(audioPath: String) {
        val idx = displayMessages.indexOfFirst { it.voice?.audioPath == audioPath }
        if (idx >= 0) {
            adapter.notifyItemChanged(idx)
            scrollToBottom()
        }
    }

    private fun currentChatModelSupportsDirectAudioInput(): Boolean {
        return AiModelCapabilities.supportsDirectAudioInput(this)
    }

    private suspend fun transcribeVoiceToTextWithFallback(audioFile: File): String {
        fun normalize(raw: String?): String {
            val text = raw.orEmpty().trim()
            return if (
                text.isBlank() ||
                text == "WHISPER_NOT_SETUP" ||
                text == "MODEL_DOWNLOADING"
            ) "" else text
        }
        val asrMode = Prefs.getAsrMode(this)
        return if (asrMode == Prefs.ASR_MODE_WHISPER) {
            normalize(LocalAsrService.speechToText(this@ChatActivity, audioFile))
        } else {
            normalize(AIService.speechToText(this@ChatActivity, audioFile))
        }
    }

    private fun copyVoiceFileToStorage(tempFile: File): File {
        return audioRecordController.copyVoiceFileToStorage(tempFile)
    }

    private fun buildVoicePayload(audioPath: String, durationSec: Int, transcript: String): String {
        return voiceController.buildVoicePayload(audioPath, durationSec, transcript)
    }

    private fun parseVoicePayload(content: String): VoicePayload {
        return voiceController.parseVoicePayload(content)
    }

    private fun parseVoicePayloadStrict(content: String): VoicePayload? {
        return voiceController.parseVoicePayloadStrict(content)
    }

    private fun playVoiceMessage(item: ChatDisplayItem) {
        voiceController.playVoiceMessage(item)
    }

    private fun stopVoicePlayback() {
        voiceController.stopVoicePlayback()
    }

    private fun showVoiceMessageMenu(anchor: View, item: ChatDisplayItem) {
        messageMenuController.showVoiceMessageMenu(anchor, item)
    }

    private fun showTranscriptMenu(anchor: View, item: ChatDisplayItem) {
        messageMenuController.showTranscriptMenu(anchor, item)
    }

    private fun hideVoiceTranscript(item: ChatDisplayItem) {
        voiceController.hideVoiceTranscript(item)
    }

    private fun isVoiceTranscriptVisible(item: ChatDisplayItem): Boolean {
        return voiceController.isTranscriptVisible(item)
    }

    private fun showTextMessageMenu(anchor: View, item: ChatDisplayItem) {
        messageMenuController.showTextMessageMenu(anchor, item)
    }

    private fun showBillMessageMenu(anchor: View, item: ChatDisplayItem) {
        messageMenuController.showBillMessageMenu(anchor, item)
    }

    private fun transcribeVoiceMessage(item: ChatDisplayItem, showResult: Boolean, force: Boolean = false) {
        voiceController.transcribeVoiceMessage(item, showResult, force)
    }

    private fun updateVoiceTranscriptByPath(audioPath: String, transcript: String, revealTranscript: Boolean) {
        voiceController.updateVoiceTranscriptByPath(audioPath, transcript, revealTranscript)
    }

    private fun enterVoiceSelectionMode(firstItem: ChatDisplayItem? = null) {
        voiceController.enterVoiceSelectionMode(firstItem)
    }

    private fun exitVoiceSelectionMode() {
        voiceController.exitVoiceSelectionMode()
    }

    private fun toggleVoiceSelection(item: ChatDisplayItem) {
        voiceController.toggleVoiceSelection(item)
    }

    private fun deleteSelectedVoiceMessages() {
        voiceController.deleteSelectedVoiceMessages()
    }

    private fun deleteVoiceMessages(items: List<ChatDisplayItem>) {
        voiceController.deleteVoiceMessages(items)
    }

    private fun requestDeleteFromLongPressMenu(item: ChatDisplayItem) {
        uiHelperController.showCustomConfirmDialog(
            getString(R.string.confirm_delete),
            "删除后可在回收站恢复，是否继续？\n删除聊天记录不会删除附带账单。",
            getString(R.string.delete),
            true
        ) {
            deleteVoiceMessages(listOf(item))
        }
    }

    private suspend fun findDependentAssistantMessageIds(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<Long>()
        val userTypes = listOf(MSG_TYPE_USER_TEXT, MSG_TYPE_USER_IMAGE, MSG_TYPE_USER_VOICE)
        val assistantTypes = listOf(MSG_TYPE_AI_TEXT, MSG_TYPE_AI_BILL)
        for (id in ids.distinct()) {
            val start = db.chatMessageDao().getById(id) ?: continue
            if (!isUserMessageType(start.msgType)) continue
            val nextUser = db.chatMessageDao().findNextUserMessage(
                bookName = start.bookName,
                conversationId = start.conversationId,
                timestamp = start.timestamp,
                id = start.id,
                userTypes = userTypes
            )
            result += db.chatMessageDao().findAssistantMessageIdsBetween(
                bookName = start.bookName,
                conversationId = start.conversationId,
                startTimestamp = start.timestamp,
                startId = start.id,
                endTimestamp = nextUser?.timestamp ?: -1L,
                endId = nextUser?.id ?: -1L,
                assistantTypes = assistantTypes
            )
        }
        return result.distinct()
    }

    private fun isUserMessageType(msgType: Int): Boolean =
        msgType == MSG_TYPE_USER_TEXT || msgType == MSG_TYPE_USER_IMAGE || msgType == MSG_TYPE_USER_VOICE

    private fun ensureModelAudioSupportProbed() {
        val model = AiModelSlots.resolveChatModel(this)
        if (AiModelCapabilities.supportsDirectAudioInput(this, model) ||
            Prefs.getAiChatModelAudioSupport(this, model) != null ||
            audioSupportProbeJob?.isActive == true
        ) {
            refreshVoiceSupportHint()
            return
        }
        audioSupportProbeJob = lifecycleScope.launch {
            val support = withContext(Dispatchers.IO) {
                AIService.probeDirectAudioInputSupport(this@ChatActivity)
            }
            Prefs.setAiChatModelAudioSupport(this@ChatActivity, model, support)
            refreshVoiceSupportHint()
        }
    }

    private fun refreshVoiceSupportHint() {
        tvVoiceModelHint.visibility = View.GONE
        val lp = layoutChatInputRow.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin != 0) {
            lp.topMargin = 0
            layoutChatInputRow.layoutParams = lp
        }
    }

    private fun sendText() {
        val text = etInput.text?.toString().orEmpty().trim()
        val images = pendingImages.toList()

        if (text.isEmpty() && images.isEmpty()) {
            messagePipeline.sendText()  // Pipeline handles the empty-toast
            return
        }

        if (images.isEmpty()) {
            messagePipeline.sendText()
            return
        }

        etInput.setText("")
        pendingImages.clear()
        updatePendingImagePreview()
        dispatchToAccounting(text, images)
    }

    /**
     * Route [text] + [images] to the accounting pipeline.
     */
    private fun dispatchToAccounting(text: String, attachments: List<PendingImage>) {
        val mergedText = text.trim()
        val binaryAttachments = attachments.filter { it.hasApiPayload }
        val (imageAttachments, fileAttachments) = ChatAttachmentHelper.groupAttachmentsForDisplay(binaryAttachments)

        if (binaryAttachments.isEmpty()) {
            if (mergedText.isNotBlank()) {
                messagePipeline.callAiAccounting(mergedText)
            }
            return
        }

        val useNaturalLanguage = Prefs.isImageAccountingNaturalLanguage(this)
        val apiAttachments = ChatAttachmentHelper.flattenForApiPayload(binaryAttachments)
        val apiSupplement = buildString {
            if (fileAttachments.isNotEmpty()) {
                append(ChatAttachmentHelper.PDF_PAYLOAD_MARKER)
                append('\n')
            }
            append(mergedText)
        }.trim()
        val payload = ChatImageComposer.encodeMultiImagePayload(apiAttachments, apiSupplement, useNaturalLanguage)
        imageAttachments.forEach { attachment ->
            appendUserMessage(
                "",
                MSG_TYPE_USER_IMAGE,
                attachment.uri?.toString().orEmpty()
            )
        }
        fileAttachments.forEach { attachment ->
            appendUserMessage(
                ChatAttachmentHelper.encodeFileMessageContent(
                    attachment.mime,
                    attachment.fileName.ifBlank { getString(R.string.chat_attach_pdf) }
                ),
                MSG_TYPE_USER_FILE,
                attachment.uri?.toString().orEmpty()
            )
        }
        if (mergedText.isNotBlank()) {
            appendUserMessage(mergedText, MSG_TYPE_USER_TEXT)
        }
        val loadingText = when {
            fileAttachments.isNotEmpty() && imageAttachments.isEmpty() ->
                getString(R.string.chat_analyzing_pdf)
            binaryAttachments.size > 1 -> getString(R.string.chat_analyzing_attachments_fmt, binaryAttachments.size)
            else -> ""
        }
        messagePipeline.callAiAccounting(
            userText = payload,
            appendUserBubble = false,
            loadingInitialText = loadingText
        )
    }

    private fun onAttachmentReady(attachment: PendingImage) {
        if (ChatImageComposer.isAtLimit(pendingImages.size)) {
            Utils.toast(this, getString(R.string.toast_max_attachments, ChatImageComposer.MAX_PENDING_IMAGES))
            return
        }
        pendingImages.add(attachment)
        updatePendingImagePreview()
        updateInputActionUi()
    }

    private fun updatePendingImagePreview() {
        if (!::containerPendingImages.isInitialized || !::layoutPendingImages.isInitialized) return

        containerPendingImages.removeAllViews()

        if (pendingImages.isEmpty()) {
            layoutPendingImages.visibility = View.GONE
            return
        }

        layoutPendingImages.visibility = View.VISIBLE
        tvPendingImageCount.text = getString(
            R.string.selected_attachment_count_fmt,
            pendingImages.size,
            ChatImageComposer.MAX_PENDING_IMAGES
        )

        val density = resources.displayMetrics.density
        val size = (68 * density).toInt()
        val margin = (4 * density).toInt()
        val removeBtnSize = (22 * density).toInt()

        pendingImages.forEachIndexed { index, attachment ->
            when {
                attachment.showsAsImageThumbnail ->
                    addImagePreviewChip(index, attachment, size, margin, removeBtnSize)
                attachment.showsAsFileCard ->
                    addFilePreviewChip(index, attachment, density, margin, removeBtnSize)
            }
        }
    }

    private fun addImagePreviewChip(
        index: Int,
        attachment: PendingImage,
        size: Int,
        margin: Int,
        removeBtnSize: Int
    ) {
        val frameLayout = FrameLayout(this).apply {
            background = androidx.core.content.ContextCompat.getDrawable(
                this@ChatActivity,
                R.drawable.bg_chat_image_thumb
            )
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, 0, margin, 0)
            }
        }
        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        Glide.with(this)
            .load(attachment.uri)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .centerCrop()
            .into(imageView)
        frameLayout.addView(imageView)
        frameLayout.addView(buildAttachmentRemoveButton(index, removeBtnSize))
        containerPendingImages.addView(frameLayout)
    }

    private fun addFilePreviewChip(
        index: Int,
        attachment: PendingImage,
        density: Float,
        margin: Int,
        removeBtnSize: Int
    ) {
        val chipWidth = (176 * density).toInt()
        val chipHeight = (56 * density).toInt()
        val frameLayout = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(chipWidth, chipHeight).apply {
                setMargins(margin, 0, margin, 0)
            }
        }
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(
                this@ChatActivity,
                R.drawable.bg_chat_file_chip
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
        }
        val iconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt())
            setImageResource(R.drawable.ic_chat_file)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
        }
        val displayName = attachment.fileName.ifBlank { getString(R.string.chat_attach_pdf) }
        textColumn.addView(TextView(this).apply {
            text = displayName
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(Color.parseColor("#1F2937"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        textColumn.addView(TextView(this).apply {
            text = ChatAttachmentHelper.fileTypeLabel(this@ChatActivity, attachment.mime, displayName)
            textSize = 10f
            maxLines = 2
            setTextColor(Color.parseColor("#7B8798"))
        })
        chip.addView(iconView)
        chip.addView(textColumn)
        frameLayout.addView(chip)
        frameLayout.addView(buildAttachmentRemoveButton(index, removeBtnSize))
        containerPendingImages.addView(frameLayout)
    }

    private fun buildAttachmentRemoveButton(index: Int, removeBtnSize: Int): TextView {
        return TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(removeBtnSize, removeBtnSize).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
            }
            text = "×"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_chat_attachment_remove)
            contentDescription = getString(R.string.remove_attachment_cd, index + 1)
            setOnClickListener { removePendingImage(index) }
        }
    }

    private fun removePendingImage(index: Int) {
        if (index in pendingImages.indices) {
            pendingImages.removeAt(index)
            updatePendingImagePreview()
            updateInputActionUi()
        }
    }

    private fun callAiAccounting(
        userText: String,
        appendUserBubble: Boolean = true,
        forceTextReply: Boolean = false,
        loadingIdxOverride: String? = null,
        loadingBootstrapText: String = "",
        loadingInitialText: String = ""
    ) {
        messagePipeline.callAiAccounting(
            userText = userText,
            appendUserBubble = appendUserBubble,
            forceTextReply = forceTextReply,
            loadingIdxOverride = loadingIdxOverride,
            loadingBootstrapText = loadingBootstrapText,
            loadingInitialText = loadingInitialText
        )
    }

    private fun callAiAccountingWithVoice(audioFile: File) {
        messagePipeline.callAiAccountingWithVoice(audioFile)
    }

    private fun interruptAiResponse() {
        messagePipeline.cancelCurrentRequest()
        updateComposerGenerationState()
    }

    private fun isAiGenerating(): Boolean =
        displayMessages.any { it.isLoading && it.msgType == MSG_TYPE_AI_TEXT }

    private fun updateComposerGenerationState() {
        if (!::btnStop.isInitialized) return
        updateInputActionUi()
    }

    private fun ensureLastMessageVisible(force: Boolean = false) {
        scrollToBottom(force)
    }

    private fun consumePendingHabitSuggestionReply(text: String): Boolean {
        val pending = pendingHabitSuggestion ?: return false
        val normalized = text.trim().lowercase(Locale.getDefault())
        val acceptWords = listOf("是", "好", "好的", "行", "可以", "记住", "加入", "添加", "那就记入记账界面")
        val rejectWords = listOf("不", "不用", "不要", "算了", "否", "不需要")

        return when {
            acceptWords.any { normalized.contains(it) } -> {
                lifecycleScope.launch {
                    val outcome = saveRuleWithKeywordConflictPrompt(
                        AiRule(
                            keyword = pending.keyword,
                            targetType = pending.targetType,
                            targetCategory = pending.targetCategory,
                            targetAccount1 = pending.targetAccount1,
                            targetAccount2 = pending.targetAccount2,
                            isEnabled = true
                        )
                    )
                    when (outcome) {
                        RuleSaveOutcome.SAVED ->
                            appendAiTextMessage("好呀，已经帮你记成一条记账习惯啦 ${pending.summaryText}", isLoading = false)
                        RuleSaveOutcome.OVERWRITTEN ->
                            appendAiTextMessage("好呀，检测到同关键词规则，我已用这次内容覆盖旧规则 ${pending.summaryText}", isLoading = false)
                        RuleSaveOutcome.CANCELED ->
                            appendAiTextMessage("检测到同关键词旧规则，你取消了覆盖，这次就先不保存啦~", isLoading = false)
                    }
                }
                pendingHabitSuggestion = null
                true
            }
            rejectWords.any { normalized.contains(it) } -> {
                pendingHabitSuggestion = null
                appendAiTextMessage("好哒，那这次我先不记进习惯里~", isLoading = false)
                true
            }
            else -> false
        }
    }

    private suspend fun buildAnalysisInput(userText: String): String {
        return userText.removePrefix("[图片OCR文本]: ").trim()
    }

    private fun buildBillSummary(bills: List<Bill>): String {
        return billCorrectionService.buildBillSummary(bills)
    }

    private suspend fun processBillResult(
        result: JSONObject,
        userText: String,
        bookName: String,
        conversationId: String
    ): List<Bill> {
        return billCorrectionService.processBillResult(result, userText, bookName, conversationId)
    }

    private suspend fun confirmVisualAccountingDraftInChat(
        summary: String,
        bookName: String,
        conversationId: String
    ): String? {
        val initialDraft = summary.trim()
        if (initialDraft.isBlank()) return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                if (isFinishing || isDestroyed) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                val themeContext = ContextThemeWrapper(this@ChatActivity, R.style.Theme_TapAccounting)
                val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_visual_accounting_draft, null)
                val etDraft = view.findViewById<EditText>(R.id.et_visual_draft)
                val btnCancel = view.findViewById<TextView>(R.id.btn_visual_draft_cancel)
                val btnConfirm = view.findViewById<TextView>(R.id.btn_visual_draft_confirm)

                etDraft.setText(initialDraft)
                etDraft.setSelection(initialDraft.length)

                val dialog = AlertDialog.Builder(themeContext)
                    .setView(view)
                    .create()

                var completed = false
                fun finish(value: String?) {
                    if (completed) return
                    completed = true
                    dialog.setOnDismissListener(null)
                    if (cont.isActive) cont.resume(value)
                    if (dialog.isShowing) dialog.dismiss()
                }

                btnCancel.setOnClickListener { finish(null) }

                btnConfirm.setOnClickListener {
                    val edited = etDraft.text?.toString().orEmpty().trim()
                    if (edited.isBlank()) {
                        Utils.toast(this@ChatActivity, getString(R.string.keep_recognizable_bill_content))
                        return@setOnClickListener
                    }
                    finish(edited)
                }
                dialog.setOnCancelListener { finish(null) }
                dialog.setOnDismissListener { finish(null) }
                cont.invokeOnCancellation {
                    runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                    }
                }

                OverlayDialogs.showPageCenterDialog(
                    dialog = dialog,
                    ctx = this@ChatActivity,
                    widthRatio = 0.92f,
                    cancelOnTouchOutside = false,
                    useSolidPanelBackground = false
                )
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                if (!dialog.isShowing) finish(null)
            }
        }
    }

    private fun formatBillBrief(bill: Bill): String {
        val typeLabel = when (bill.type) {
            Bill.TYPE_INCOME -> getString(R.string.income)
            Bill.TYPE_TRANSFER -> if (bill.subType == Bill.SUBTYPE_REPAYMENT) getString(R.string.repayment) else getString(R.string.transfer)
            else -> getString(R.string.expense)
        }
        val amountText = String.format(Locale.getDefault(), "%.2f", bill.amount)
        val category = bill.categoryName.ifBlank { getString(R.string.uncategorized) }
        val main = bill.remark.ifBlank { category }
        return "$typeLabel $amountText ${bill.currency} · $main"
    }

    /**
     * 从单笔账单 JSON 构建摘要文本，用于草稿 naturalSummary 显示。
     */
    fun buildSingleBillSummary(billJson: org.json.JSONObject): String {
        val category = billJson.optString("category_name", billJson.optString("categoryName", ""))
        val remark = billJson.optString("remarks", billJson.optString("remark", ""))
        val amount = billJson.optDouble("amount", 0.0)
        val currency = billJson.optString("currency", "CNY")
        val account = billJson.optString("asset_name", billJson.optString("accountName", ""))
        val parts = mutableListOf<String>()
        if (remark.isNotBlank()) parts.add(remark)
        if (category.isNotBlank()) parts.add(category)
        if (amount > 0) parts.add("${String.format("%.2f", amount)} $currency")
        if (account.isNotBlank()) parts.add(account)
        return parts.joinToString(" · ").take(120)
    }

    private fun onInteractiveBillAction(item: ChatDisplayItem, bill: Bill, action: Int) {
        // No interactive bill actions remain after agent mode removal.
    }

    private fun parseTimeToMillis(timeStr: String): Long {
        if (timeStr.isBlank()) return System.currentTimeMillis()
        val locale = Locale.getDefault()
        val fullFormats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
        for (pattern in fullFormats) {
            val parsed = runCatching { SimpleDateFormat(pattern, locale).parse(timeStr)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        val partialFormats = listOf("MM-dd HH:mm", "MM-dd HH:mm:ss")
        for (pattern in partialFormats) {
            val parsedDate = runCatching { SimpleDateFormat(pattern, locale).parse(timeStr) }.getOrNull() ?: continue
            return Calendar.getInstance(locale).apply {
                val currentYear = get(Calendar.YEAR)
                time = parsedDate
                set(Calendar.YEAR, currentYear)
            }.timeInMillis
        }
        return System.currentTimeMillis()
    }

    private fun maybeShowRuleDialogForChatBillCategoryEdit(
        item: ChatDisplayItem,
        originalBill: Bill,
        updatedBill: Bill
    ) {
        if (originalBill.categoryName == updatedBill.categoryName) return
        val referenceText = updatedBill.remark.ifBlank { originalBill.remark }.trim()
        if (referenceText.isBlank()) return

        RuleLearnPromptHelper.show(
            ctx = this,
            model = RuleLearnPromptHelper.PromptModel(
                referenceText = referenceText,
                beforeType = originalBill.type,
                afterType = updatedBill.type,
                beforeCategory = originalBill.categoryName,
                afterCategory = updatedBill.categoryName
            ),
            isOverlay = false,
            onContinue = { openChatRuleEditor(referenceText, updatedBill) },
            onDismiss = {}
        )
    }

    private fun openChatRuleEditor(referenceText: String, updatedBill: Bill) {
        RuleDialogHelper.showDialog(
            ctx = this,
            rule = null,
            referenceText = referenceText,
            defaultType = updatedBill.type,
            defaultCat = updatedBill.categoryName,
            defaultAcc1 = null,
            defaultAcc2 = null,
            isOverlay = false,
            categoryOnlyLearnMode = true,
            onSave = { newRule ->
                lifecycleScope.launch {
                    when (saveRuleWithKeywordConflictPrompt(newRule)) {
                        RuleSaveOutcome.SAVED -> Utils.toast(this@ChatActivity, getString(R.string.rule_saved))
                        RuleSaveOutcome.OVERWRITTEN -> Utils.toast(this@ChatActivity, getString(R.string.rule_overwritten))
                        RuleSaveOutcome.CANCELED -> Utils.toast(this@ChatActivity, getString(R.string.rule_save_canceled))
                    }
                }
            },
            onDelete = null
        )
    }

    private suspend fun saveRuleWithKeywordConflictPrompt(newRule: AiRule): RuleSaveOutcome =
        withContext(Dispatchers.IO) {
            val dao = db.aiRuleDao()
            val keyword = newRule.keyword.trim()
            val currentEditId = newRule.id.takeIf { it > 0 }
            val conflicts = dao.getRulesByKeyword(keyword)
                .filter { existing -> currentEditId == null || existing.id != currentEditId }

            if (conflicts.isEmpty()) {
                dao.insertRule(newRule.copy(keyword = keyword))
                return@withContext RuleSaveOutcome.SAVED
            }

            val shouldOverwrite = withContext(Dispatchers.Main) {
                promptKeywordOverwrite(keyword = keyword, existingCount = conflicts.size)
            }
            if (!shouldOverwrite) {
                return@withContext RuleSaveOutcome.CANCELED
            }

            val target = conflicts.first()
            dao.insertRule(newRule.copy(id = target.id, keyword = keyword))
            currentEditId?.takeIf { it != target.id }?.let { dao.deleteRuleById(it) }
            conflicts.drop(1).forEach { duplicate ->
                if (duplicate.id != target.id) dao.deleteRuleById(duplicate.id)
            }
            RuleSaveOutcome.OVERWRITTEN
        }

    private suspend fun promptKeywordOverwrite(
        keyword: String,
        existingCount: Int
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        if (isFinishing || isDestroyed) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting))
            .setTitle(getString(R.string.duplicate_rule_dialog_title))
            .setMessage(getString(R.string.duplicate_rule_dialog_message, keyword, existingCount.toString()))
            .setPositiveButton(getString(R.string.continue_and_overwrite)) { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(true)
            }
            .setNegativeButton(getString(R.string.cancel_save)) { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(false)
            }
            .setOnCancelListener {
                if (cont.isActive) cont.resume(false)
            }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun loadHistoryMessages() {
        historyController.loadHistoryMessages()
    }

    private fun openImagePreview(item: ChatDisplayItem) {
        val imageUris = displayMessages
            .asSequence()
            .filter { it.msgType == MSG_TYPE_USER_IMAGE && it.imageUri.isNotBlank() }
            .map { it.imageUri }
            .toList()
        if (imageUris.isEmpty()) return
        val index = imageUris.indexOf(item.imageUri).coerceAtLeast(0)
        val intent = android.content.Intent(this, ChatImagePreviewActivity::class.java).apply {
            putStringArrayListExtra(ChatImagePreviewActivity.EXTRA_IMAGE_URIS, ArrayList(imageUris))
            putExtra(ChatImagePreviewActivity.EXTRA_INDEX, index)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, 0)
    }

    private fun scrollToPendingMessageIfNeeded() {
        historyController.scrollToPendingMessageIfNeeded()
    }

    private fun parseBillIds(json: String): List<Long> {
        return ChatBillMessageParser.parseBillIds(json)
    }

    private fun isDeprecatedBillMessage(billIds: String): Boolean =
        ChatBillMessageParser.isDeprecatedBillMessage(billIds)

    private fun markBillIdsAsDeprecated(billIds: String): String {
        return ChatBillMessageParser.markBillIdsAsDeprecated(billIds)
    }

    private fun parseBillsFromMessageContent(content: String): List<Bill> {
        return ChatBillMessageParser.parseBillsFromMessageContent(
            content = content,
            currentBookName = currentBookName,
            parseTimeToMillis = ::parseTimeToMillis
        )
    }

    private fun parseDeprecatedBillIdsFromContent(content: String): Set<Long> {
        return ChatBillMessageParser.parseDeprecatedBillIdsFromContent(content)
    }

    private fun parseEditedBillIdsFromContent(content: String): Set<Long> {
        return ChatBillMessageParser.parseEditedBillIdsFromContent(content)
    }

    private fun parseSnapshotOnlyFromContent(content: String): Boolean {
        return ChatBillMessageParser.parseSnapshotOnlyFromContent(content)
    }

    private fun mergeChatBillSnapshots(liveBills: List<Bill>, snapshots: List<Bill>): List<Bill> {
        return ChatBillMessageParser.mergeChatBillSnapshots(liveBills, snapshots)
    }

    private fun buildBillMessageContent(
        bills: List<Bill>,
        deprecatedBillIds: Set<Long> = emptySet(),
        editedBillIds: Set<Long> = emptySet(),
        snapshotOnly: Boolean = false
    ): String {
        return ChatBillMessageParser.buildBillMessageContent(
            bills = bills,
            formatTime = { ms -> uiHelperController.formatTime(ms) },
            deprecatedBillIds = deprecatedBillIds,
            editedBillIds = editedBillIds,
            snapshotOnly = snapshotOnly
        )
    }

    private fun appendUserMessage(text: String, type: Int, imageUri: String = "") {
        messagePersistenceController.appendUserMessage(text, type, imageUri)
    }

    private fun appendUserVoiceMessage(audioFile: File, durationSec: Int, transcript: String): ChatDisplayItem {
        return messagePersistenceController.appendUserVoiceMessage(audioFile, durationSec, transcript)
    }

    private fun appendAiTextMessage(
        text: String,
        isLoading: Boolean,
        bookName: String? = null,
        conversationId: String? = null,
        showConversationModeNudge: Boolean = false
    ): String {
        val uiKey = messagePersistenceController.appendAiTextMessage(
            text,
            isLoading,
            bookName,
            conversationId,
            showConversationModeNudge
        )
        if (!isLoading) {
            syncBillReplyGrouping()
        }
        updateComposerGenerationState()
        return uiKey
    }

    private suspend fun persistAiTextMessage(text: String, bookName: String, conversationId: String) {
        messagePersistenceController.persistAiTextMessage(text, bookName, conversationId)
    }

    private fun removeLoadingMessage(uiKey: String) {
        messagePersistenceController.removeLoadingMessage(uiKey)
        updateComposerGenerationState()
    }

    private fun updateLoadingMessage(uiKey: String, text: String) {
        messagePersistenceController.updateLoadingMessage(uiKey, text)
    }

    private fun finalizeLoadingMessage(
        uiKey: String,
        text: String,
        bookName: String,
        conversationId: String,
        showConversationModeNudge: Boolean = false
    ): Boolean {
        val converted = messagePersistenceController.finalizeLoadingMessage(
            uiKey,
            text,
            bookName,
            conversationId,
            showConversationModeNudge
        )
        if (converted) {
            syncBillReplyGrouping()
        }
        updateComposerGenerationState()
        return converted
    }

    private fun billExpandKey(item: ChatDisplayItem): String =
        if (item.dbId > 0L) "db:${item.dbId}" else item.uiKey

    private fun isBillMessageExpanded(item: ChatDisplayItem): Boolean =
        expandedBillMessageKeys.contains(billExpandKey(item))

    private fun toggleBillMessageExpand(item: ChatDisplayItem) {
        val key = billExpandKey(item)
        if (key in expandedBillMessageKeys) {
            expandedBillMessageKeys.remove(key)
        } else {
            expandedBillMessageKeys.add(key)
        }
        val idx = displayMessages.indexOfFirst { billExpandKey(it) == key }
        if (idx >= 0) adapter.notifyItemChanged(idx)
    }

    private fun syncBillReplyGrouping() {
        val previous = displayMessages.associate { item ->
            item.uiKey to (item.groupedWithBillReply to item.compactGroupedLayout)
        }
        ChatDisplayLinkHelper.applyBillReplyGrouping(displayMessages)
        displayMessages.forEachIndexed { index, item ->
            val now = item.groupedWithBillReply to item.compactGroupedLayout
            if (previous[item.uiKey] != now) {
                adapter.notifyItemChanged(index)
            }
        }
    }

    private fun showBillDeleteUndo(deletedBillIds: List<Long>, messageDbId: Long) {
        if (deletedBillIds.isEmpty()) return
        Snackbar.make(chatRoot, getString(R.string.chat_bill_deleted_undo_fmt, deletedBillIds.size), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                lifecycleScope.launch {
                    val records = withContext(Dispatchers.IO) {
                        db.deletedBillDao().getByOriginalBillIds(deletedBillIds)
                    }
                    if (records.isEmpty()) return@launch
                    withContext(Dispatchers.IO) {
                        BillRestoreHelper.restoreBills(db, records)
                    }
                    refreshBillMessageItem(messageDbId)
                    syncBillReplyGrouping()
                }
            }
            .show()
    }

    private suspend fun refreshBillMessageItem(messageDbId: Long) {
        val idx = displayMessages.indexOfFirst { it.dbId == messageDbId }
        if (idx < 0) return
        val msg = withContext(Dispatchers.IO) { db.chatMessageDao().getById(messageDbId) } ?: return
        val billIds = parseBillIds(msg.billIds)
        val bills = withContext(Dispatchers.IO) { billIds.mapNotNull { db.billDao().getBillById(it) } }
        val billSnapshots = parseBillsFromMessageContent(msg.content)
        val deprecatedBillIds = parseDeprecatedBillIdsFromContent(msg.content)
        val editedBillIds = parseEditedBillIdsFromContent(msg.content)
        val snapshotOnly = parseSnapshotOnlyFromContent(msg.content)
        val displayBills = if (bills.isNotEmpty()) {
            mergeChatBillSnapshots(bills, billSnapshots)
        } else {
            billSnapshots
        }
        displayMessages[idx] = displayMessages[idx].copy(
            content = msg.content,
            bills = displayBills.toMutableList(),
            isDeprecated = isDeprecatedBillMessage(msg.billIds) || (bills.isEmpty() && !snapshotOnly),
            deprecatedBillIds = deprecatedBillIds.toMutableSet(),
            editedBillIds = editedBillIds.toMutableSet()
        )
        adapter.notifyItemChanged(idx)
    }

    private fun confirmAllBillsInMessage(item: ChatDisplayItem) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                ChatBillMessageActions.confirmBillsInMessage(
                    db = db,
                    displayMessages = displayMessages,
                    messageDbId = item.dbId,
                    formatTime = { ms -> uiHelperController.formatTime(ms) }
                )
            }
            if (count > 0) {
                val idx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                if (idx >= 0) adapter.notifyItemChanged(idx)
                Utils.toast(this@ChatActivity, getString(R.string.toast_bill_confirmed_fmt, count))
            }
        }
    }

    private fun deleteBillsFromMenu(item: ChatDisplayItem) {
        val deletableBills = ChatBillUiHelper.deletableBills(item)
        if (deletableBills.isEmpty()) return
        val message = if (deletableBills.size >= 2) {
            getString(R.string.chat_bill_delete_all_confirm, deletableBills.size)
        } else {
            getString(R.string.chat_bill_delete_confirm)
        }
        uiHelperController.showCustomConfirmDialog(
            getString(R.string.confirm_delete),
            message,
            getString(R.string.confirm_delete),
            true
        ) {
            lifecycleScope.launch {
                val result = ChatBillMessageActions.deleteBillsFromMessage(
                    db = db,
                    displayMessages = displayMessages,
                    messageDbId = item.dbId,
                    billsToDelete = deletableBills,
                    formatTime = { ms -> uiHelperController.formatTime(ms) }
                )
                val idx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                if (idx >= 0) adapter.notifyItemChanged(idx)
                result?.deletedBillIds?.let { showBillDeleteUndo(it, item.dbId) }
            }
        }
    }

    private fun openBillCalendarForItem(item: ChatDisplayItem) {
        startActivity(Intent(this, CalendarActivity::class.java))
    }

    private fun scrollToBottom(force: Boolean = false) {
        if (!force && isInlineAmountEditing()) return
        if (displayMessages.isEmpty()) return
        val last = displayMessages.lastIndex
        rvMessages.post {
            val lm = chatLayoutManager ?: return@post
            lm.scrollToPosition(last)
            rvMessages.post {
                val lastView = lm.findViewByPosition(last) ?: return@post
                val offset = rvMessages.height - rvMessages.paddingBottom - rvMessages.paddingTop - lastView.height
                if (offset < 0) {
                    lm.scrollToPositionWithOffset(last, offset)
                }
                updateScrollToLatestButton()
            }
        }
    }

    // --- 长按菜单新增动作：全选 / 分享 / 朗读 ---

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ttsInitListener = TextToSpeech.OnInitListener { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.SIMPLIFIED_CHINESE
        }
    }

    private fun ensureTts(): Boolean {
        if (tts == null) {
            tts = TextToSpeech(applicationContext, ttsInitListener)
        }
        return ttsReady
    }

    private fun releaseTts() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    /**
     * 长按菜单"全选"：让目标 TextView 进入系统选区模式并全选内容。
     * 用 Selection.setSelection(Spannable) 拉起选区工具栏；并设置 customSelectionActionModeCallback
     * 保证部分 ROM/版本上能稳定出现浮动 ActionMode 工具栏（含 Copy / Share 等）。
     */
    private fun startSelectAllInTextView(tv: TextView) {
        val text = tv.text
        if (text.isNullOrEmpty()) return
        tv.isFocusable = true
        tv.isFocusableInTouchMode = true
        tv.requestFocus()
        if (text is android.text.Spannable) {
            Selection.setSelection(text, 0, text.length)
        }
        val existing = tv.customSelectionActionModeCallback
        if (existing == null) {
            tv.setCustomSelectionActionModeCallback(object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = true
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode) {}
            })
        }
        tv.startActionMode(tv.customSelectionActionModeCallback, ActionMode.TYPE_FLOATING)
    }

    private fun sharePlainText(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)))
        } catch (e: Exception) {
            Log.w("Chat", "share failed: ${e.message}")
        }
    }

    private fun readAloudText(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (ensureTts()) {
            tts?.speak(body, TextToSpeech.QUEUE_FLUSH, null, "chat_tts")
        } else {
            // 引擎还在异步初始化，提示用户
            Utils.toast(this, getString(R.string.tts_not_ready))
        }
    }

    private fun isInlineAmountEditing(): Boolean = inlineAmountEditingBillId != null

    private fun resolveEntryBookName(intent: Intent?): String {
        val fromIntent = intent?.getStringExtra(EXTRA_SOURCE_BOOK).orEmpty().trim()
        if (fromIntent.isNotEmpty()) return BookAccountManager.normalizeBookName(fromIntent)
        return BookAccountManager.getSelectedBook(this)
    }

    override fun onDestroy() {
        messagePipeline.cancelCurrentRequest(showInterruptedMessage = false)
        aiScopeJob.cancel()
        super.onDestroy()
        clearPendingLongPress()
        stopVoicePlayback()
        releaseTts()
        if (isRecording) {
            stopVoiceRecording { _, _ -> }
        }
    }

    override fun onBackPressed() {
        if (voiceController.isVoiceSelectionMode()) {
            exitVoiceSelectionMode()
            return
        }
        if (::drawerSessions.isInitialized && drawerSessions.isDrawerOpen(GravityCompat.END)) {
            drawerSessions.closeDrawer(GravityCompat.END)
            return
        }
        super.onBackPressed()
    }
}

data class ChatDisplayItem(
    val dbId: Long = 0,
    val uiKey: String = UUID.randomUUID().toString(),
    val msgType: Int,
    val content: String = "",
    val imageUri: String = "",
    val voice: VoicePayload? = null,
    val bills: MutableList<Bill> = mutableListOf(),
    val timestamp: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val isDeprecated: Boolean = false,
    val deprecatedBillIds: MutableSet<Long> = mutableSetOf(),
    val editedBillIds: MutableSet<Long> = mutableSetOf(),
    val billHint: String = "",
    val groupedWithBillReply: Boolean = false,
    val compactGroupedLayout: Boolean = false,
    val billInteractionMode: Int = ChatActivity.BILL_INTERACTION_NONE,
    val billInteractionToken: String = "",
    val showConversationModeNudge: Boolean = false
)

data class VoicePayload(
    val audioPath: String = "",
    val durationSec: Int = 1,
    val transcript: String = ""
)

data class ChatSessionRow(
    val bookName: String,
    val conversationId: String,
    val title: String,
    val preview: String,
    val displayTime: String,
    val timestamp: Long,
    val isCurrent: Boolean
)

data class HabitRuleSuggestion(
    val keyword: String,
    val targetType: Int?,
    val targetCategory: String?,
    val targetAccount1: String?,
    val targetAccount2: String?
) {
    val summaryText: String
        get() = buildString {
            append("关键词：$keyword")
            targetCategory?.takeIf { it.isNotBlank() }?.let { append("，分类：$it") }
            targetAccount1?.takeIf { it.isNotBlank() }?.let { append("，账户：$it") }
            targetAccount2?.takeIf { it.isNotBlank() }?.let { append("，目标账户：$it") }
        }
}
