package com.taostudio.tapaccounting.capture.notify

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.CaptureInboxEvents
import com.taostudio.tapaccounting.capture.bridge.CaptureBridgeClient
import com.taostudio.tapaccounting.capture.bridge.CaptureNoteClassifier
import com.taostudio.tapaccounting.capture.bridge.RuleLearningManager
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.CaptureSources
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.ImportBill
import com.taostudio.tapaccounting.capture.contract.ImportItemStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CategorySource
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import com.taostudio.tapaccounting.capture.ui.CaptureImagePickerActivity
import com.taostudio.tapaccounting.capture.ui.SplitConfirmSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 付款瞬间的底部微胶囊气泡。
 *
 * 三态：
 * - 摘要态：[智能记账] [细改] [不计] + ✕
 * - 输入态：[只存备注] [记这笔]
 * - 结果态：归类：水果 [记这笔] [改] / ✓ 已记
 *
 * 直接写账只走普通收支：转账/还款/退款、金额缺失、资产未映射、可能已记、
 * 桥接不可用或归类失败都会转交敲敲记账悬浮窗细改。
 */
object CaptureBubbleManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var dismissRunnable: Runnable? = null
    private var session: BubbleSession? = null

    /** 收起后贴在屏幕右侧的小圈（显示金额），最多停留 60 秒。 */
    private var peekView: View? = null
    private var peekParams: WindowManager.LayoutParams? = null
    private var peekDismissRunnable: Runnable? = null
    private var peekHintView: View? = null
    private var peekHintParams: WindowManager.LayoutParams? = null
    @Volatile
    private var isPeeking: Boolean = false

    /** OverlayService 用来在气泡消失后判断自己是否该停掉。 */
    @Volatile
    var onDismissed: (() -> Unit)? = null

    private enum class BubbleState { SUMMARY, INPUT, CLASSIFYING, SPLIT, RESULT, RECORDED }

    private class BubbleSession(
        val app: Context,
        val root: LinearLayout,
        val summaryText: TextView,
        val statusText: TextView,
        val noteRow: LinearLayout,
        val noteInput: EditText,
        var splitEnabled: Boolean,
        val summaryActions: LinearLayout,
        val inputActions: LinearLayout,
        val resultActions: LinearLayout,
        val inputRecordButton: TextView,
        val resultRecordButton: TextView,
        val resultAdjustButton: TextView,
        var capture: PaymentCapture,
        var pendingCount: Int,
        var busy: Boolean = false,
        var state: BubbleState = BubbleState.SUMMARY,
    ) {
        fun renderSummary() {
            val item = capture
            val refund = item.subType == LocalBillType.SUBTYPE_REFUND || item.needsRefundLink
            val income = item.billType == LocalBillType.TYPE_INCOME
            val transfer = BubbleRecordPolicy.isTransfer(item)
            val amountPrefix = if (income || refund) "+¥" else "¥"
            val amountStr = item.amountCents?.let {
                String.format(Locale.CHINA, "%s%.2f", amountPrefix, it / 100.0)
            } ?: "金额待补"
            // 转账要让人一眼看出钱从哪走到哪，只显示转出账户等于没信息。
            val assetStr = if (transfer) {
                listOfNotNull(item.assetName ?: item.paymentMethodRaw, item.toAssetName)
                    .filter { it.isNotBlank() }
                    .joinToString(" → ")
                    .takeIf { it.isNotEmpty() }
            } else {
                item.assetName ?: item.paymentMethodRaw
            }
            val assetLimit = if (transfer) 18 else 8
            summaryText.text = buildString {
                append(amountStr)
                item.merchant?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it.take(10)) }
                assetStr?.let { append(" (").append(it.take(assetLimit)).append(")") }
                append(" · 待记 ").append(pendingCount).append(" 笔")
            }
        }
    }

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** OverlayService 判断自己是否还被气泡占用。 */
    fun isShowing(): Boolean = currentView != null || peekView != null

    /**
     * 气泡已经在屏幕上时，后续同一笔支付的其它事件只更新“待记 N 笔”，
     * 不再 dismiss 重建成第二个气泡。
     */
    fun updatePendingCount(count: Int) {
        val current = session ?: return
        current.pendingCount = count
        mainHandler.post {
            if (session === current) {
                current.renderSummary()
                if (isPeeking) updatePeekBadge(current)
            }
        }
    }

    fun showBubble(context: Context, capture: PaymentCapture, pendingCount: Int) {
        val app = context.applicationContext
        if (!canDrawOverlays(app)) return
        mainHandler.post {
            showBubbleOnMain(app, capture, pendingCount)
        }
    }

    private fun showBubbleOnMain(app: Context, capture: PaymentCapture, pendingCount: Int) {
        dismissImmediate(app, notifyDismissed = false)
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val density = app.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FA1E293B"))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            elevation = dp(10).toFloat()
        }

        val topRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val isRefund = capture.subType == LocalBillType.SUBTYPE_REFUND || capture.needsRefundLink
        val isIncome = capture.billType == LocalBillType.TYPE_INCOME
        val typeSuffix = when {
            isRefund -> "·退款"
            isIncome -> "·收入"
            capture.billType == LocalBillType.TYPE_TRANSFER -> "·转账"
            else -> ""
        }
        val badgeColor = when (capture.source) {
            CaptureSource.WECHAT -> "#07C160"
            CaptureSource.ALIPAY -> "#1677FF"
            else -> "#3B82F6"
        }
        val badge = TextView(app).apply {
            text = CaptureSources.displayName(capture.source) + typeSuffix
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(badgeColor))
                cornerRadius = dp(10).toFloat()
            }
        }
        topRow.addView(badge)

        val tvSummary = TextView(app).apply {
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
            setPadding(dp(8), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(tvSummary)

        val btnClose = TextView(app).apply {
            text = " ✕ "
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(6), dp(4), dp(2), dp(4))
        }
        topRow.addView(btnClose)

        // 顶部小把手：按住向下滑动，卡片跟着下沉；松手收成胶囊。
        val grabber = FrameLayout(app)
        grabber.addView(
            View(app).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#40FFFFFF"))
                    cornerRadius = dp(2).toFloat()
                }
            },
            FrameLayout.LayoutParams(dp(32), dp(4), Gravity.CENTER),
        )
        root.addView(
            grabber,
            LinearLayout.LayoutParams(dp(44), dp(16)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(2)
            },
        )
        root.addView(topRow)

        val tvStatus = TextView(app).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#A7F3D0"))
            setPadding(dp(2), dp(8), dp(2), 0)
            maxLines = 4
            visibility = View.GONE
        }
        root.addView(tvStatus)

        val noteRow = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        val etNote = EditText(app).apply {
            hint = "补一句备注，方便以后查找"
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dp(12).toFloat()
            }
        }
        etNote.setText(capture.note.orEmpty())
        noteRow.addView(etNote)

        // 拆分/选图放在同一行：拆分为单选按钮式开关，图片只在拆分开启后出现，
        // 两者高度一致，窗口不会因为文案变化而抖。
        val splitRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val chipSplit = TextView(app).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val chipImage = TextView(app).apply {
            text = "📷 选图"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E3A8A"))
                cornerRadius = dp(10).toFloat()
            }
            visibility = View.GONE
        }
        splitRow.addView(chipSplit)
        splitRow.addView(
            chipImage,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) },
        )
        noteRow.addView(splitRow)
        root.addView(noteRow)

        fun actionRow(): LinearLayout = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }

        fun actionButton(
            text: String,
            textColor: String,
            bgColor: String,
        ): TextView = TextView(app).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textColor))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = dp(12).toFloat()
            }
        }

        val summaryActions = actionRow()
        // 转账/还款不需要分类：气泡只给「直接计入 / 不计」，不引导补备注、智能归类或拆分。
        val summaryTransfer = BubbleRecordPolicy.isTransfer(capture)
        val btnDirect = actionButton("直接计入", "#FFFFFF", "#3B82F6")
        val btnSmart = actionButton("智能记账", "#FFFFFF", "#3B82F6")
        val btnAdjust = actionButton("细改", "#93C5FD", "#1E3A8A")
        val btnIgnore = actionButton("不计", "#CBD5E1", "#334155")
        if (summaryTransfer) {
            summaryActions.addView(
                btnDirect,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply { marginEnd = dp(6) },
            )
            summaryActions.addView(
                btnIgnore,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f),
            )
        } else {
            summaryActions.addView(
                btnSmart,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply { marginEnd = dp(6) },
            )
            summaryActions.addView(
                btnAdjust,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).apply { marginEnd = dp(6) },
            )
            summaryActions.addView(
                btnIgnore,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f),
            )
        }
        root.addView(summaryActions)

        val inputActions = actionRow()
        val btnSaveOnly = actionButton("只存备注", "#93C5FD", "#1E3A8A")
        val btnRecordInput = actionButton("记这笔", "#FFFFFF", "#3B82F6")
        inputActions.addView(
            btnSaveOnly,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
        )
        inputActions.addView(
            btnRecordInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f),
        )
        root.addView(inputActions)

        val resultActions = actionRow()
        val btnRecordResult = actionButton("记这笔", "#FFFFFF", "#3B82F6")
        val btnAdjustResult = actionButton("改", "#93C5FD", "#1E3A8A")
        resultActions.addView(
            btnRecordResult,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = dp(8) },
        )
        resultActions.addView(
            btnAdjustResult,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(resultActions)

        val s = BubbleSession(
            app = app,
            root = root,
            summaryText = tvSummary,
            statusText = tvStatus,
            noteRow = noteRow,
            noteInput = etNote,
            splitEnabled = capture.splitRequested,
            summaryActions = summaryActions,
            inputActions = inputActions,
            resultActions = resultActions,
            inputRecordButton = btnRecordInput,
            resultRecordButton = btnRecordResult,
            resultAdjustButton = btnAdjustResult,
            capture = capture,
            pendingCount = pendingCount,
        )

        fun isActive(): Boolean = session === s

        fun setStatus(text: String, color: String = "#A7F3D0") {
            tvStatus.text = text
            tvStatus.setTextColor(Color.parseColor(color))
            tvStatus.visibility = View.VISIBLE
        }

        fun toast(message: String) {
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }

        fun styleSplitChip(active: Boolean) {
            chipSplit.text = if (active) "✓ 拆分" else "拆分"
            chipSplit.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#93C5FD"))
            chipSplit.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (active) "#3B82F6" else "#1E3A8A"))
                cornerRadius = dp(10).toFloat()
            }
            // 图片入口只在拆分开启后出现；和拆分按钮同一行，窗口高度不变。
            chipImage.visibility = if (active) View.VISIBLE else View.GONE
        }

        chipSplit.setOnClickListener {
            s.splitEnabled = !s.splitEnabled
            s.capture = s.capture.copy(splitRequested = s.splitEnabled)
            styleSplitChip(s.splitEnabled)
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        CaptureApp.from(app).repository
                            .updateSplitRequested(s.capture.id, s.splitEnabled)
                    }
                }
            }
        }
        styleSplitChip(s.splitEnabled)

        var imageSplitHandler: ((Uri) -> Unit)? = null
        chipImage.setOnClickListener {
            if (!s.splitEnabled) return@setOnClickListener
            val amountCents = s.capture.amountCents ?: 0L
            val billType = s.capture.billType ?: LocalBillType.TYPE_EXPENSE
            if (amountCents <= 0L) {
                toast("金额缺失，无法拆单")
                return@setOnClickListener
            }
            if (billType != LocalBillType.TYPE_EXPENSE && billType != LocalBillType.TYPE_INCOME) {
                toast("图片拆单只支持普通支出/收入")
                return@setOnClickListener
            }
            CaptureImagePickerActivity.pickImage(
                context = app,
                onPicked = { uri -> imageSplitHandler?.invoke(uri) },
                onCancelled = { toast("已取消选图，待记仍保留") },
            )
        }

        fun applyState(state: BubbleState) {
            val previous = s.state
            s.state = state
            s.noteRow.visibility = if (state == BubbleState.INPUT) View.VISIBLE else View.GONE
            s.inputActions.visibility = if (state == BubbleState.INPUT) View.VISIBLE else View.GONE
            s.summaryActions.visibility = if (state == BubbleState.SUMMARY) View.VISIBLE else View.GONE
            s.resultActions.visibility = if (state == BubbleState.RESULT) View.VISIBLE else View.GONE
            s.statusText.visibility = if (state == BubbleState.SUMMARY) View.GONE else View.VISIBLE
            s.inputRecordButton.isEnabled = !s.busy
            s.resultRecordButton.isEnabled = !s.busy
            s.resultAdjustButton.isEnabled = !s.busy

            if (state != BubbleState.INPUT && previous == BubbleState.INPUT) {
                hideInput(app, etNote)
            }
            when (state) {
                BubbleState.SUMMARY -> {
                    // 不自动收起：用户点右上角「⌄」或点窗口外才缩成小圈。
                    cancelAutoDismiss()
                }

                BubbleState.INPUT -> {
                    cancelAutoDismiss()
                    setStatus("写一句备注，自动归类", "#94A3B8")
                    if (previous != BubbleState.INPUT) enableWindowFocus(app, etNote)
                }

                BubbleState.CLASSIFYING -> {
                    cancelAutoDismiss()
                    setStatus("正在智能归类…", "#94A3B8")
                }

                BubbleState.SPLIT -> {
                    cancelAutoDismiss()
                }

                BubbleState.RESULT -> {
                    cancelAutoDismiss()
                }

                BubbleState.RECORDED -> {
                    cancelAutoDismiss()
                    scheduleAutoDismiss(app, 2_500L)
                }
            }
        }

        var grabberDownY = 0f
        var grabberBaseCenterY = 0
        grabber.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabberDownY = event.rawY
                    val loc = IntArray(2)
                    root.getLocationOnScreen(loc)
                    grabberBaseCenterY = loc[1] + root.height / 2
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - grabberDownY).coerceAtLeast(0f)
                    root.translationY = dy
                    root.alpha = (1f - dy / (root.height.coerceAtLeast(1) * 1.6f))
                        .coerceIn(0.35f, 1f)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = root.translationY
                    if (dy > dp(40)) {
                        if (s.state != BubbleState.SUMMARY) {
                            applyState(BubbleState.SUMMARY)
                        }
                        root.translationY = 0f
                        root.alpha = 1f
                        enterPeek(app, grabberBaseCenterY)
                    } else {
                        root.animate().translationY(0f).alpha(1f)
                            .setDuration(160L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    true
                }

                else -> false
            }
        }

        suspend fun persistNote(noteText: String?): PaymentCapture? {
            val repository = CaptureApp.from(app).repository
            return withContext(Dispatchers.IO) {
                val existing = repository.findById(s.capture.id) ?: return@withContext null
                val normalized = noteText?.trim().orEmpty()
                if (normalized == existing.note?.trim().orEmpty()) {
                    existing
                } else {
                    repository.updateNoteAndReclassify(app, existing.id, normalized)
                }
            }
        }

        fun showSummary() {
            s.renderSummary()
            applyState(BubbleState.SUMMARY)
        }

        suspend fun fineTune(noteText: String?, reason: String?) {
            if (!isActive()) return
            val repository = CaptureApp.from(app).repository
            val latest = persistNote(noteText) ?: s.capture
            s.capture = latest
            CaptureNotifier.refreshAll(app)
            val accepted = runCatching {
                CaptureBridgeClient(app).openQuickRecord(
                    buildPrefill(latest),
                    latest.id,
                    UUID.randomUUID().toString(),
                )
            }.getOrDefault(false)
            if (accepted) {
                toast("已交给悬浮窗${reason?.let { "（$it）" } ?: ""}")
                dismissImmediate(app)
            } else {
                toast("${reason ?: "无法直接入账"}；敲敲记账悬浮窗不可用，已保留待记")
                if (isActive()) showSummary()
            }
        }

        suspend fun importGroupAndToast(
            capture: PaymentCapture,
            items: List<CaptureBridgeClient.SplitItem>,
            totalCents: Long,
        ) {
            val result = CaptureBridgeClient(app).importBillGroup(
                capture = capture,
                items = items,
                requestId = UUID.randomUUID().toString(),
            )
            if (result == null) {
                toast("拆分入账失败：敲敲记账桥接不可用，已保留待记")
                return
            }
            if (result.ok || result.duplicate) {
                withContext(Dispatchers.IO) {
                    CaptureApp.from(app).repository
                        .updateStatus(capture.id, CaptureStatus.RECORDED)
                }
                CaptureNotifier.refreshAll(app)
                toast(
                    if (result.duplicate) {
                        "这笔已入账，未重复建单"
                    } else {
                        "已拆成 ${items.size} 笔；账单页显示明细，资产详情按 " +
                            "¥" + String.format(Locale.CHINA, "%.2f", totalCents / 100.0) +
                            " 合并显示"
                    }
                )
            } else {
                toast("拆分入账失败：${humanizeSplitError(result.error, result.message)}")
            }
        }

        fun showSplitConfirm(base: PaymentCapture, preview: CaptureBridgeClient.SplitPreviewResult) {
            if (!isActive()) return
            cancelAutoDismiss()
            s.state = BubbleState.SPLIT
            s.root.removeAllViews()
            s.root.setPadding(0, 0, 0, 0)
            s.root.background = null
            s.root.addView(
                SplitConfirmSheet.buildOverlayContent(
                    context = app,
                    capture = base,
                    preview = preview,
                    onCancel = { dismissImmediate(app) },
                    onEdit = {
                        dismissImmediate(app)
                        toast("已保留待记，可在待记列表改备注后重试")
                    },
                    onConfirm = { items ->
                        val total = base.amountCents ?: 0L
                        dismissImmediate(app)
                        scope.launch { importGroupAndToast(base, items, total) }
                    },
                )
            )
        }

        /**
         * 勾选拆分时：调用 App 只拆条目/金额，再弹确认页；不勾选不会走这里。
         * 取消确认页只关闭气泡，待记保持 PENDING。
         */
        suspend fun previewSplitThenConfirm(base: PaymentCapture) {
            val amountCents = base.amountCents ?: 0L
            val billType = base.billType ?: LocalBillType.TYPE_EXPENSE
            if (amountCents <= 0L) {
                fineTune(base.note, BubbleRecordPolicy.Block.AMOUNT_MISSING.message)
                return
            }
            if (isActive()) setStatus("正在按备注拆分…", "#94A3B8")
            val preview = CaptureBridgeClient(app).previewSplit(
                captureId = base.id,
                note = base.note,
                totalCents = amountCents,
                billType = billType,
            )
            if (!preview.ok) {
                if (isActive()) {
                    setStatus(
                        "拆分未完成：${humanizeSplitError(preview.error, preview.message)}",
                        "#FCA5A5",
                    )
                    applyState(BubbleState.INPUT)
                }
                return
            }
            showSplitConfirm(base, preview)
        }

        /** 图片拆单：把当前待记按小票识别成多条明细，再走同一个确认页。 */
        suspend fun previewImageSplitThenConfirm(base: PaymentCapture, imageUri: Uri) {
            val amountCents = base.amountCents ?: 0L
            val billType = base.billType ?: LocalBillType.TYPE_EXPENSE
            if (amountCents <= 0L) {
                fineTune(base.note, BubbleRecordPolicy.Block.AMOUNT_MISSING.message)
                return
            }
            if (billType != LocalBillType.TYPE_EXPENSE && billType != LocalBillType.TYPE_INCOME) {
                toast("图片拆单只支持普通支出/收入")
                return
            }
            if (isActive()) setStatus("正在识别图片并拆分…", "#94A3B8")
            val preview = CaptureBridgeClient(app).previewImageSplit(
                captureId = base.id,
                totalCents = amountCents,
                billType = billType,
                imageUri = imageUri,
            )
            if (!preview.ok) {
                if (isActive()) {
                    setStatus(
                        "拆分未完成：${humanizeSplitError(preview.error, preview.message)}",
                        "#FCA5A5",
                    )
                    applyState(BubbleState.INPUT)
                }
                return
            }
            showSplitConfirm(base, preview)
        }

        imageSplitHandler = { uri ->
            scope.launch { previewImageSplitThenConfirm(s.capture, uri) }
        }

        suspend fun classifyThenResult() {
            val noteText = etNote.text?.toString()?.trim().orEmpty()
            val repository = CaptureApp.from(app).repository
            val bridge = CaptureBridgeClient(app)

            val ping = bridge.pingDetail()
            if (!ping.ok) {
                fineTune(noteText, BubbleRecordPolicy.Block.BRIDGE_UNAVAILABLE.message)
                return
            }

            val base = persistNote(noteText) ?: s.capture
            s.capture = base
            val earlyBlock = BubbleRecordPolicy.preClassificationBlock(base, bridgeAvailable = true)
            if (earlyBlock != null) {
                fineTune(noteText, earlyBlock.message)
                return
            }

            if (s.splitEnabled) {
                previewSplitThenConfirm(base)
                return
            }

            var category = base.categoryName?.takeIf { it.isNotBlank() }
            if (category == null && !base.note.isNullOrBlank()) {
                val classified = withContext(Dispatchers.IO) {
                    CaptureNoteClassifier.classifyAndApply(
                        context = app,
                        client = bridge,
                        repository = repository,
                        captureId = base.id,
                    )
                }
                category = classified?.categoryName?.takeIf { it.isNotBlank() }
                s.capture = classified ?: base
            }
            if (category.isNullOrBlank()) {
                fineTune(noteText, BubbleRecordPolicy.Block.MISSING_CATEGORY.message)
                return
            }

            s.capture = withContext(Dispatchers.IO) { repository.findById(base.id) } ?: s.capture
            if (isActive()) {
                setStatus("归类：$category")
                applyState(BubbleState.RESULT)
            }
        }

        suspend fun importFromResult() {
            val repository = CaptureApp.from(app).repository
            val latest = withContext(Dispatchers.IO) { repository.findById(s.capture.id) } ?: s.capture
            s.capture = latest

            val bridge = CaptureBridgeClient(app)
            val bridgeOk = bridge.pingDetail().ok
            val block = BubbleRecordPolicy.importBlock(latest, bridgeAvailable = bridgeOk)
            if (block != null) {
                fineTune(latest.note, block.message)
                return
            }

            val result = bridge.importBills(listOf(latest.toImportBill()), UUID.randomUUID().toString())
            if (result == null) {
                if (isActive()) {
                    setStatus("入账失败：敲敲记账桥接不可用\n已保留在待记，可重试或点「改」", "#FCA5A5")
                    applyState(BubbleState.RESULT)
                }
                return
            }

            val first = result.optJSONArray("results")?.optJSONObject(0)
            when (first?.optString("status").orEmpty()) {
                ImportItemStatus.CREATED -> {
                    withContext(Dispatchers.IO) {
                        repository.updateStatus(latest.id, CaptureStatus.RECORDED)
                        RuleLearningManager.schedule(app, latest.id)
                    }
                    com.taostudio.tapaccounting.logic.RuleAskCoordinator.showIfNeeded(
                        app,
                        listOf(
                            RuleLearningManager.RuleLearnEntry(
                                note = latest.note.orEmpty(),
                                categoryName = latest.categoryName.orEmpty(),
                                billType = latest.billType ?: 0,
                            )
                        ),
                    )
                    CaptureNotifier.refreshAll(app)
                    if (isActive()) {
                        val cat = latest.categoryName?.takeIf { it.isNotBlank() }
                        setStatus("✓ 已记" + (cat?.let { " · 归类：$it" } ?: ""), "#6EE7B7")
                        applyState(BubbleState.RECORDED)
                    }
                }

                ImportItemStatus.DUPLICATE -> {
                    withContext(Dispatchers.IO) {
                        repository.updateStatus(latest.id, CaptureStatus.RECORDED)
                    }
                    CaptureNotifier.refreshAll(app)
                    if (isActive()) {
                        val cat = latest.categoryName?.takeIf { it.isNotBlank() }
                        setStatus("✓ 已记" + (cat?.let { " · 归类：$it" } ?: ""), "#6EE7B7")
                        applyState(BubbleState.RECORDED)
                    }
                }

                else -> {
                    val error = first?.optString("error").orEmpty()
                        .ifBlank { result.optString("error", "未知错误") }
                    if (isActive()) {
                        val cat = latest.categoryName?.takeIf { it.isNotBlank() }
                        setStatus(
                            "归类：${cat ?: "—"}\n入账未完成：${humanizeImportError(error)}",
                            "#FCA5A5",
                        )
                        applyState(BubbleState.RESULT)
                    }
                }
            }
        }

        suspend fun saveNoteOnly() {
            val noteText = etNote.text?.toString()?.trim().orEmpty()
            val repository = CaptureApp.from(app).repository
            val updated = withContext(Dispatchers.IO) {
                val existing = repository.findById(s.capture.id)
                if (existing != null && noteText == existing.note?.trim().orEmpty()) {
                    existing
                } else {
                    repository.updateNoteAndReclassify(app, s.capture.id, noteText)
                }
            }
            // 本地备注规则没命中、且有备注时，才做一次轻量 AI 归类；只存备注，不入账。
            val classified = if (updated != null && noteText.isNotEmpty() && updated.categoryName.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    CaptureNoteClassifier.classifyAndApply(
                        context = app,
                        client = CaptureBridgeClient(app),
                        repository = repository,
                        captureId = s.capture.id,
                    )
                }
            } else {
                updated
            }
            CaptureNotifier.refreshAll(app)
            val catHint = classified
                ?.takeIf { !it.categoryName.isNullOrBlank() }
                ?.let { item ->
                    when (item.categorySource) {
                        CategorySource.AI -> " · 智能归类：${item.categoryName}"
                        CategorySource.MANUAL -> " · 分类：${item.categoryName}"
                        else -> " · 自动归类：${item.categoryName}"
                    }
                }
                .orEmpty()
            toast(if (noteText.isNotEmpty()) "已留底备注：$noteText$catHint" else "已保存")
            dismissImmediate(app)
        }

        suspend fun ignoreCapture() {
            val repository = CaptureApp.from(app).repository
            val latest = withContext(Dispatchers.IO) { repository.findById(s.capture.id) }
            if (latest == null) {
                toast("这笔待记已不存在")
                dismissImmediate(app)
                return
            }
            if (latest.status == CaptureStatus.RECORDED) {
                toast("这笔已经入账，未标记不计")
                dismissImmediate(app)
                return
            }
            withContext(Dispatchers.IO) {
                repository.updateStatus(latest.id, CaptureStatus.IGNORED)
            }
            CaptureInboxEvents.notifyChanged()
            CaptureNotifier.refreshAll(app)
            toast("已标记不计，可在待记列表底部恢复")
            dismissImmediate(app)
        }

        fun runBusy(state: BubbleState? = null, block: suspend () -> Unit) {
            if (s.busy) return
            s.busy = true
            cancelAutoDismiss()
            if (state != null) applyState(state)
            s.inputRecordButton.alpha = 0.6f
            s.resultRecordButton.alpha = 0.6f
            s.resultAdjustButton.alpha = 0.6f
            scope.launch {
                try {
                    block()
                } catch (t: Throwable) {
                    if (isActive()) {
                        toast("操作失败：${t.message ?: t.javaClass.simpleName}")
                        showSummary()
                    }
                } finally {
                    s.busy = false
                    s.inputRecordButton.alpha = 1f
                    s.resultRecordButton.alpha = 1f
                    s.resultAdjustButton.alpha = 1f
                    if (isActive()) applyState(s.state)
                }
            }
        }

        fun openInput() {
            s.renderSummary()
            applyState(BubbleState.INPUT)
        }

        btnClose.setOnClickListener { dismissImmediate(app) }
        btnSmart.setOnClickListener {
            if (s.busy) return@setOnClickListener
            openInput()
        }
        btnDirect.setOnClickListener {
            if (s.busy) return@setOnClickListener
            runBusy { importFromResult() }
        }
        btnAdjust.setOnClickListener {
            if (s.busy) return@setOnClickListener
            runBusy { fineTune(etNote.text?.toString()?.trim(), null) }
        }
        btnIgnore.setOnClickListener {
            if (s.busy) return@setOnClickListener
            runBusy { ignoreCapture() }
        }
        btnSaveOnly.setOnClickListener {
            if (s.busy) return@setOnClickListener
            runBusy { saveNoteOnly() }
        }
        btnRecordInput.setOnClickListener {
            runBusy(state = BubbleState.CLASSIFYING) { classifyThenResult() }
        }
        btnRecordResult.setOnClickListener {
            runBusy { importFromResult() }
        }
        btnAdjustResult.setOnClickListener {
            if (s.busy) return@setOnClickListener
            runBusy { fineTune(etNote.text?.toString()?.trim(), null) }
        }
        etNote.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                if (!s.busy) runBusy(state = BubbleState.CLASSIFYING) { classifyThenResult() }
                true
            } else {
                false
            }
        }

        s.renderSummary()
        applyState(BubbleState.SUMMARY)

        val params = WindowManager.LayoutParams().apply {
            width = (app.resources.displayMetrics.widthPixels * 0.92f).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(68)
        }

        try {
            root.alpha = 0f
            root.translationY = dp(24).toFloat()
            wm.addView(root, params)
            currentView = root
            currentParams = params
            session = s
            (root.parent as? View)?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE && s.state == BubbleState.SUMMARY) {
                    enterPeek(app)
                    true
                } else {
                    false
                }
            }
            root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start()
        } catch (_: Throwable) {
            currentView = null
            currentParams = null
            session = null
        }
    }

    private fun buildPrefill(item: PaymentCapture): JSONObject {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return JSONObject().apply {
            put("type", item.billType ?: LocalBillType.TYPE_EXPENSE)
            put("subType", item.subType ?: LocalBillType.SUBTYPE_NORMAL)
            put("amount", (item.amountCents ?: 0L) / 100.0)
            put("currency", item.currency)
            (item.assetName ?: item.paymentMethodRaw)?.let { put("asset_name", it) }
            item.toAssetName?.let { put("to_asset_name", it) }
            item.categoryName?.let { put("category_name", it) }
            put("time", timeFormat.format(Date(item.paidAt)))
            put("remarks", item.note?.takeIf { it.isNotBlank() } ?: item.merchant.orEmpty())
            item.bookName?.let { put("book_name", it) }
            put("exclude_from_stats", item.excludeFromStats)
        }
    }

    private fun PaymentCapture.toImportBill(): ImportBill = ImportBill(
        captureId = id,
        type = billType ?: LocalBillType.TYPE_EXPENSE,
        subType = subType ?: LocalBillType.SUBTYPE_NORMAL,
        needsRefundLink = needsRefundLink,
        amountCents = amountCents ?: 0L,
        currency = currency,
        paidAt = paidAt,
        assetName = assetName ?: paymentMethodRaw,
        toAssetName = toAssetName,
        categoryName = categoryName,
        remarks = note?.takeIf { it.isNotBlank() } ?: merchant.orEmpty(),
        note = note,
        bookName = bookName,
        excludeFromStats = excludeFromStats,
    )

    private fun humanizeSplitError(rawError: String?, rawMessage: String?): String {
        val code = rawError?.trim().orEmpty()
        val message = rawMessage?.trim().orEmpty()
        return when {
            code == "split_empty" -> message.ifEmpty {
                "没从备注里拆出条目，请把备注写成「苹果30 雪梨40」"
            }
            code == "split_total_exceeds" -> message.ifEmpty { "明细合计超过付款，请改备注" }
            code == "split_amount_missing" -> message.ifEmpty { "有多项缺金额，请补金额或改备注" }
            code == "split_amount_invalid" -> message.ifEmpty { "有条目金额无效，请改备注" }
            code == "split_total_mismatch" -> message.ifEmpty { "拆分合计与付款不一致，请重新确认" }
            code == "split_unsupported_type" -> message.ifEmpty { "转账/还款/退款不支持文字拆分" }
            code == "ai_not_configured" -> message.ifEmpty { "请先在敲敲记账中配置 AI API Key" }
            code == "ai_timeout" -> message.ifEmpty { "AI 拆分超时，请重试或改备注" }
            code == "split_ai_failed" -> message.ifEmpty { "AI 拆分失败，请检查 AI 设置或改备注" }
            code == "bridge_unavailable" -> "敲敲记账桥接不可用"
            code == "missing_category" -> message.ifEmpty { "有条目缺少分类，请改备注或配置 AI/规则" }
            code == "category_not_found" || code.startsWith("category_not_found:") -> {
                val name = code.removePrefix("category_not_found:")
                    .ifBlank { message.removePrefix("category_not_found:") }
                if (name.isNotBlank()) {
                    "敲敲记账中不存在分类「$name」，请改备注或先建分类"
                } else {
                    "敲敲记账中没有对应分类，请改备注或先建分类"
                }
            }
            code == "asset_not_found" || code.startsWith("asset_not_found:") -> {
                val name = code.removePrefix("asset_not_found:")
                    .ifBlank { message.removePrefix("asset_not_found:") }
                if (name.isNotBlank()) {
                    "敲敲记账中不存在账户「$name」，请先绑定资产"
                } else {
                    "敲敲记账中没有对应账户，请先绑定资产"
                }
            }
            code == "missing_asset" -> "缺少账户，请先绑定资产"
            message.isNotEmpty() -> message
            code.isNotEmpty() -> code
            else -> "请检查分类、账户与备注"
        }
    }

    private fun humanizeImportError(rawError: String?): String {
        val err = rawError?.trim().orEmpty()
        return when {
            err == "missing_category" -> "请先选择分类"
            err.startsWith("category_not_found:") ->
                "敲敲记账中不存在分类「${err.removePrefix("category_not_found:")}」，请点「改」重选"
            err.startsWith("asset_not_found:") ->
                "敲敲记账中不存在账户「${err.removePrefix("asset_not_found:")}」，请点「改」绑定资产"
            err == "needs_refund_link" -> "退款账单需在悬浮窗关联原消费账单"
            err == "missing_asset" -> "缺少账户，请在悬浮窗选择"
            err == "missing_to_asset" -> "缺少转入账户，请在悬浮窗选择"
            err == "same_asset" -> "转出与转入账户不能相同"
            err == "asset_feature_disabled" -> "敲敲记账未开启资产功能"
            err.isNotEmpty() -> err
            else -> "请检查分类与账户映射"
        }
    }

    private fun enableWindowFocus(app: Context, targetEdit: EditText) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = currentView ?: return
        val params = currentParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        runCatching { wm.updateViewLayout(view, params) }
        targetEdit.requestFocus()
        mainHandler.postDelayed({
            val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(targetEdit, InputMethodManager.SHOW_IMPLICIT)
        }, 80L)
    }

    private fun hideInput(app: Context, targetEdit: EditText) {
        val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(targetEdit.windowToken, 0)
        targetEdit.clearFocus()
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = currentView ?: return
        val params = currentParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        runCatching { wm.updateViewLayout(view, params) }
    }

    // ---------------------------------------------------------------- 收起小圈

    private fun scheduleAutoPeek(app: Context, delayMs: Long) {
        cancelAutoDismiss()
        val runnable = Runnable { enterPeek(app) }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun schedulePeekDismiss(app: Context, delayMs: Long) {
        cancelPeekDismiss()
        val runnable = Runnable { fadePeekOut(app, notifyDismissed = true) }
        peekDismissRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPeekDismiss() {
        peekDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        peekDismissRunnable = null
    }

    /** 摘要态向下拖动把手：整张卡向右边缘缩成胶囊。 */
    private fun enterPeek(app: Context, centerYOverride: Int? = null) {
        val s = session ?: return
        val view = currentView ?: return
        if (s.state != BubbleState.SUMMARY || s.busy) return
        cancelAutoDismiss()
        val centerY = centerYOverride ?: run {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            loc[1] + view.height / 2
        }
        view.animate().cancel()
        view.pivotX = view.width.toFloat()
        view.pivotY = view.height / 2f
        view.animate()
            .scaleX(0.12f)
            .scaleY(0.12f)
            .alpha(0f)
            .setDuration(260L)
            .setInterpolator(AccelerateInterpolator())
            .withLayer()
            .withEndAction {
                if (session !== s || currentView !== view) return@withEndAction
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                runCatching { wm?.removeViewImmediate(view) }
                currentView = null
                view.scaleX = 1f
                view.scaleY = 1f
                view.alpha = 1f
                showPeek(app, s, centerY)
            }
            .start()
    }

    private fun showPeek(app: Context, s: BubbleSession, centerY: Int) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = app.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()
        val screenW = app.resources.displayMetrics.widthPixels
        val screenH = app.resources.displayMetrics.heightPixels
        val windowW = dp(104)
        val windowH = dp(50)
        val edgeMargin = dp(6)

        val bubble = FrameLayout(app).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        val sourceColor = when (s.capture.source) {
            CaptureSource.WECHAT -> "#07C160"
            CaptureSource.ALIPAY -> "#1677FF"
            else -> "#3B82F6"
        }
        val pill = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F172A"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#38FFFFFF"))
            }
            elevation = dp(8).toFloat()
        }
        val dot = View(app).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(sourceColor))
            }
        }
        pill.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })

        val label = TextView(app).apply {
            tag = "peek_label"
            text = "待记 ${s.pendingCount.coerceAtLeast(1)}"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
        pill.addView(label)
        bubble.addView(
            pill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36),
                Gravity.CENTER,
            ),
        )

        val params = WindowManager.LayoutParams(
            windowW,
            windowH,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - windowW - edgeMargin
            y = (centerY - windowH / 2).coerceIn(dp(40), screenH - windowH - dp(40))
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = dp(5)

        bubble.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        dragging = true
                        cancelPeekDismiss()
                        showDropHint(app)
                        bubble.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.92f)
                            .setDuration(120L).start()
                    }
                    if (dragging) {
                        params.x = (startX + dx).toInt().coerceIn(0, screenW - windowW)
                        params.y = (startY + dy).toInt().coerceIn(0, screenH - windowH)
                        runCatching { wm.updateViewLayout(bubble, params) }
                        val cx = params.x + windowW / 2f
                        val cy = params.y + windowH / 2f
                        val nearClose = Math.abs(cx - screenW / 2f) < screenW * 0.30f &&
                            cy > screenH - dp(170)
                        setHintHot(app, nearClose)
                        bubble.scaleX = if (nearClose) 0.88f else 0.96f
                        bubble.scaleY = bubble.scaleX
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    dragging = false
                    if (wasDragging) {
                        hideDropHint(app)
                        bubble.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(120L).start()
                        val cx = params.x + windowW / 2f
                        val cy = params.y + windowH / 2f
                        val nearClose = Math.abs(cx - screenW / 2f) < screenW * 0.30f &&
                            cy > screenH - dp(170)
                        if (nearClose) {
                            fadePeekOut(app, notifyDismissed = true)
                        } else {
                            snapPeekToEdge(app, wm, bubble, params, screenW, edgeMargin, windowW)
                            schedulePeekDismiss(app, 60_000L)
                        }
                    } else {
                        v.performClick()
                        exitPeek(app)
                    }
                    true
                }

                else -> false
            }
        }

        try {
            bubble.alpha = 0f
            bubble.scaleX = 0.75f
            bubble.scaleY = 0.75f
            wm.addView(bubble, params)
            peekView = bubble
            peekParams = params
            isPeeking = true
            bubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            schedulePeekDismiss(app, 60_000L)
        } catch (_: Throwable) {
            peekView = null
            peekParams = null
            isPeeking = false
        }
    }

    private fun snapPeekToEdge(
        app: Context,
        wm: WindowManager,
        view: View,
        params: WindowManager.LayoutParams,
        screenW: Int,
        margin: Int,
        windowSize: Int,
    ) {
        val target = if (params.x + windowSize / 2 < screenW / 2) margin else screenW - windowSize - margin
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(view, params) }
            }
            start()
        }
    }

    private fun showDropHint(app: Context) {
        if (peekHintView != null) return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = app.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val root = FrameLayout(app).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#730B1220"), Color.TRANSPARENT),
            )
        }
        val bar = View(app).apply {
            tag = "peek_hint_bar"
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#99FFFFFF"))
                cornerRadius = dp(3).toFloat()
            }
        }
        val text = TextView(app).apply {
            tag = "peek_hint_text"
            this.text = "松手关闭"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CCFFFFFF"))
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(dp(120), dp(6), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(34)
            },
        )
        root.addView(
            text,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(48) },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(130),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        try {
            root.alpha = 0f
            wm.addView(root, params)
            peekHintView = root
            peekHintParams = params
            root.animate().alpha(1f).setDuration(140L).start()
        } catch (_: Throwable) {
            peekHintView = null
            peekHintParams = null
        }
    }

    private fun setHintHot(app: Context, hot: Boolean) {
        val root = peekHintView ?: return
        val bar = root.findViewWithTag<View>("peek_hint_bar") ?: return
        val text = root.findViewWithTag<TextView>("peek_hint_text") ?: return
        bar.animate()
            .scaleX(if (hot) 1.25f else 1f)
            .scaleY(if (hot) 1.8f else 1f)
            .alpha(if (hot) 1f else 0.75f)
            .setDuration(120L)
            .start()
        text.animate().alpha(if (hot) 1f else 0.7f).setDuration(120L).start()
    }

    private fun hideDropHint(app: Context) {
        val hint = peekHintView ?: return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        peekHintView = null
        peekHintParams = null
        hint.animate().alpha(0f).setDuration(100L)
            .withEndAction { runCatching { wm?.removeViewImmediate(hint) } }
            .start()
    }

    private fun updatePeekBadge(s: BubbleSession) {
        val label = peekView?.findViewWithTag<TextView>("peek_label") ?: return
        label.text = "待记 ${s.pendingCount.coerceAtLeast(1)}"
    }

    private fun exitPeek(app: Context) {
        val bubble = peekView ?: return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val s = session ?: return
        val root = s.root
        val params = currentParams ?: return
        cancelPeekDismiss()
        peekView = null
        peekParams = null
        isPeeking = false
        runCatching { wm.removeViewImmediate(bubble) }
        if (currentView != null) return

        root.animate().cancel()
        root.alpha = 0f
        root.scaleX = 0.12f
        root.scaleY = 0.12f
        try {
            wm.addView(root, params)
            currentView = root
            root.post {
                if (currentView !== root) return@post
                root.pivotX = root.width.toFloat()
                root.pivotY = root.height / 2f
                root.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        } catch (_: Throwable) {
            currentView = null
        }
    }
    /** 敲击唤出记账悬浮窗时：小圈淡出，只留悬浮窗。 */
    fun fadePeekOut(context: Context, notifyDismissed: Boolean = false) {
        val bubble = peekView ?: return
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        cancelPeekDismiss()
        hideDropHint(context)
        peekView = null
        peekParams = null
        isPeeking = false
        bubble.animate().cancel()
        bubble.animate()
            .alpha(0f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(180L)
            .withEndAction {
                runCatching { wm?.removeViewImmediate(bubble) }
                if (notifyDismissed) {
                    session = null
                    onDismissed?.invoke()
                }
            }
            .start()
    }

    private fun scheduleAutoDismiss(app: Context, delayMs: Long) {
        cancelAutoDismiss()
        val runnable = Runnable { dismissImmediate(app) }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelAutoDismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
    }

    fun dismissImmediate(context: Context, notifyDismissed: Boolean = true) {
        cancelAutoDismiss()
        cancelPeekDismiss()
        hideDropHint(context)
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val hadPeek = peekView != null
        peekView?.let { runCatching { wm?.removeViewImmediate(it) } }
        peekView = null
        peekParams = null
        isPeeking = false
        val view = currentView ?: run {
            session = null
            if (notifyDismissed && hadPeek) onDismissed?.invoke()
            return
        }
        currentView = null
        currentParams = null
        session = null
        runCatching { wm?.removeViewImmediate(view) }
        if (notifyDismissed) {
            onDismissed?.invoke()
        }
    }
}
