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
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
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
import com.taostudio.tapaccounting.ImagePickerActivity
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
import com.taostudio.tapaccounting.capture.ui.SplitConfirmSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
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
    /** 「重新拉起卡片」的入场动画；下一次收起前必须取消，避免和收起动画打架。 */
    private var peekEntranceRunnable: Runnable? = null
    /** 极端情况下 removeView 失败留下的窗口，下次切换时补删。 */
    private val staleWindows = mutableSetOf<View>()
    /** 收起动画进行中：防止自动收起和手势收起同时跑两遍。 */
    private var collapsing = false
    /** 胶囊第一次出现就定死的 60 秒硬截止时间（elapsedRealtime）。 */
    private var peekDeadlineAt = 0L
    /** 胶囊被临时隐藏的原因（敲击页面 / 图片选择），全部解除才恢复。 */
    private val peekSuppressors = mutableSetOf<String>()
    private var peekFlagsBeforeSuppress = 0
    private var cardHiddenForPicker = false
    private var cardFlagsBeforePicker = 0
    private var inboxWatcherJob: Job? = null
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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 移除悬浮窗。removeViewImmediate 在部分 ROM 上会抛异常；
     * 这里不吞掉结果：确认视图真的脱离窗口才算成功，失败的留着下次补删。
     */
    private fun removeWindow(app: Context, view: View): Boolean {
        if (!view.isAttachedToWindow) {
            staleWindows.remove(view)
            return true
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return false
        runCatching { wm.removeViewImmediate(view) }
        if (!view.isAttachedToWindow) {
            staleWindows.remove(view)
            return true
        }
        runCatching { wm.removeView(view) }
        if (!view.isAttachedToWindow) {
            staleWindows.remove(view)
            return true
        }
        staleWindows.add(view)
        return false
    }

    private fun sweepStaleWindows(app: Context) {
        if (staleWindows.isEmpty()) return
        staleWindows.toList().forEach { removeWindow(app, it) }
    }

    private fun cancelPeekEntrance(): Boolean {
        val pending = peekEntranceRunnable != null
        peekEntranceRunnable?.let { mainHandler.removeCallbacks(it) }
        peekEntranceRunnable = null
        return pending
    }

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

    /**
     * 任何地方（收件箱 / 悬浮窗 / 通知）把这笔记下或不计，都要立刻收掉胶囊；
     * 这也是「一分钟内只有记下或不计能结束小球」的另一半。
     */
    private fun ensureInboxWatcher(app: Context) {
        if (inboxWatcherJob?.isActive == true) return
        inboxWatcherJob = scope.launch {
            CaptureInboxEvents.changes.collect {
                val s = session ?: return@collect
                val repo = CaptureApp.from(s.app).repository
                val latest = withContext(Dispatchers.IO) {
                    repo.findById(s.capture.id)
                } ?: return@collect
                if (latest.status != CaptureStatus.PENDING) {
                    dismissImmediate(s.app, notifyDismissed = true)
                } else {
                    val count = withContext(Dispatchers.IO) { repo.countPending() }
                    updatePendingCount(count.coerceAtLeast(1))
                }
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
        sweepStaleWindows(app)
        ensureInboxWatcher(app)
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val density = app.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        // 任何一次触摸都取消「2 秒自动收起」，否则用户手指刚落到按钮上，卡片就自己缩了。
        val root = object : LinearLayout(app) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) cancelAutoDismiss()
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
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
            LinearLayout.LayoutParams(dp(56), dp(20)).apply {
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
        etNote.setOnClickListener {
            val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etNote, InputMethodManager.SHOW_IMPLICIT)
        }
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

        var imageSplitHandler: ((List<Uri>) -> Unit)? = null
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
            suspendForPicker(app)
            ImagePickerActivity.launch(
                context = app,
                maxItems = 5,
                onPicked = { uris ->
                    resumeFromPicker(app)
                    imageSplitHandler?.invoke(uris)
                },
                onCancelled = {
                    resumeFromPicker(app)
                    toast("已取消选图，待记仍保留")
                },
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
                    // 2 秒无操作自动缩成胶囊；用户也可以手动下滑或点窗口外提前收。
                    scheduleAutoPeek(app, 2_000L)
                }

                BubbleState.INPUT -> {
                    cancelAutoDismiss()
                    setStatus("写一句备注，自动归类", "#94A3B8")
                    if (previous != BubbleState.INPUT) enableWindowFocus(app)
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

        // 收起手势：只认「拖够距离」或「向下甩」。
        // 原来的 40dp 死阈值太容易误触，手指抖一下卡片就没了。
        var grabberDownY = 0f
        var grabberBaseCenterY = 0
        var grabberActive = false
        var grabberArmed = false
        var grabberVelocity: VelocityTracker? = null
        val touchSlop = ViewConfiguration.get(app).scaledTouchSlop.toFloat()

        // 卡片高度不同，阈值跟着高度走：夹在 48~72dp 之间。
        fun collapseThreshold(): Float {
            val h = root.height.coerceAtLeast(dp(160))
            return (h * 0.26f).coerceIn(dp(48).toFloat(), dp(72).toFloat())
        }

        grabber.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabberDownY = event.rawY
                    val loc = IntArray(2)
                    root.getLocationOnScreen(loc)
                    grabberBaseCenterY = loc[1] - root.translationY.toInt() + root.height / 2
                    grabberActive = false
                    grabberArmed = false
                    // 手势级 tracker：用一次就回收，绝不跨「收起 → 重新拉起」复用。
                    grabberVelocity?.recycle()
                    grabberVelocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    root.animate().cancel()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    grabberVelocity?.addMovement(event)
                    val rawDy = event.rawY - grabberDownY
                    if (rawDy < 0f) {
                        // 把手只负责「收起」，往上拉只给 20% 阻尼，回弹时更有边界感。
                        root.translationY = rawDy * 0.2f
                        root.alpha = 1f
                        root.scaleX = 1f
                        root.scaleY = 1f
                        grabberArmed = false
                        return@setOnTouchListener true
                    }
                    if (!grabberActive && rawDy > touchSlop) {
                        grabberActive = true
                        root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    val threshold = collapseThreshold()
                    // 过了阈值再拉只剩 35% 位移，像拉皮筋：拉不动了，但还跟着手。
                    val overshoot = (rawDy - threshold).coerceAtLeast(0f)
                    val shown = rawDy - overshoot * 0.65f
                    root.translationY = shown
                    val squish = (shown / root.height.coerceAtLeast(dp(160)))
                        .coerceIn(0f, 1f)
                    root.alpha = 1f - squish * 0.45f
                    root.scaleX = 1f - squish * 0.04f
                    root.scaleY = 1f - squish * 0.04f
                    val armed = rawDy >= threshold
                    if (armed != grabberArmed) {
                        grabberArmed = armed
                        root.performHapticFeedback(
                            if (armed) HapticFeedbackConstants.LONG_PRESS
                            else HapticFeedbackConstants.CONTEXT_CLICK,
                        )
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tracker = grabberVelocity
                    grabberVelocity = null
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    val travel = event.rawY - grabberDownY
                    // 快甩也要先过 slop，否则一次点击就会被当成收起。
                    val flung = vy > dp(1100) && travel > touchSlop * 2
                    val canCollapse = (
                        s.state == BubbleState.SUMMARY ||
                            s.state == BubbleState.INPUT ||
                            s.state == BubbleState.RESULT
                        ) && !s.busy
                    if (canCollapse && grabberActive && (grabberArmed || flung)) {
                        enterPeek(app, grabberBaseCenterY)
                    } else {
                        root.animate().translationY(0f).alpha(1f)
                            .scaleX(1f).scaleY(1f)
                            .setDuration(180L)
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
        suspend fun previewImageSplitThenConfirm(base: PaymentCapture, imageUris: List<Uri>) {
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
            val preview = CaptureBridgeClient(app).previewImageSplitMulti(
                captureId = base.id,
                totalCents = amountCents,
                billType = billType,
                imageUris = imageUris,
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

        imageSplitHandler = { uris ->
            scope.launch { previewImageSplitThenConfirm(s.capture, uris) }
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
            put("remarks", item.note?.takeIf { it.isNotBlank() } ?: "")
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

    private fun enableWindowFocus(app: Context) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = currentView ?: return
        val params = currentParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        // 只把窗口变成可聚焦，不主动弹键盘：点「智能记账」时整张卡被键盘顶高太突兀，
        // 让用户点输入框再弹，动作发生在预期里。
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        runCatching { wm.updateViewLayout(view, params) }
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

    private fun peekRemainingMs(): Long =
        (peekDeadlineAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /**
     * 胶囊的 60 秒是硬计时：只在第一次出现时定 deadline，
     * 拖动、吸附、展开再收起都不续期。
     */
    private fun schedulePeekDismiss(app: Context) {
        cancelPeekDismiss()
        if (peekSuppressors.isNotEmpty() || peekView == null) return
        if (peekDeadlineAt <= 0L) return
        val remain = peekRemainingMs()
        if (remain <= 0L) {
            fadePeekOut(app, notifyDismissed = true)
            return
        }
        val runnable = Runnable { fadePeekOut(app, notifyDismissed = true) }
        peekDismissRunnable = runnable
        mainHandler.postDelayed(runnable, remain)
    }

    private fun cancelPeekDismiss() {
        peekDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        peekDismissRunnable = null
    }

    // ------------------------------------------------------- 临时隐藏（敲击页面 / 选图）

    private fun suspendPeek(app: Context, reason: String) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        if (!peekSuppressors.add(reason)) return
        cancelPeekDismiss()
        if (peekSuppressors.size != 1) return
        val view = peekView ?: return
        view.animate().cancel()
        val params = peekParams ?: return
        peekFlagsBeforeSuppress = params.flags
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(view, params) }
        view.visibility = View.INVISIBLE
    }

    private fun resumePeek(app: Context, reason: String) {
        if (!peekSuppressors.remove(reason)) return
        if (peekSuppressors.isNotEmpty()) return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = peekView ?: return
        // 已经过了 60 秒：直接收掉，别先闪一下再淡出。
        if (peekDeadlineAt > 0L && peekRemainingMs() <= 0L) {
            peekView = null
            peekParams = null
            isPeeking = false
            peekDeadlineAt = 0L
            removeWindow(app, view)
            session = null
            onDismissed?.invoke()
            return
        }
        peekParams?.let { params ->
            if (peekFlagsBeforeSuppress != 0) {
                params.flags = peekFlagsBeforeSuppress
                runCatching { wm.updateViewLayout(view, params) }
            }
        }
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.visibility = View.VISIBLE
        schedulePeekDismiss(app)
    }

    /** 敲击记账页面打开：胶囊先隐藏，关页面再恢复，60 秒计时照走。 */
    fun suspendPeekForOverlay(context: Context) = suspendPeek(context.applicationContext, "overlay")

    fun resumePeekAfterOverlay(context: Context) = resumePeek(context.applicationContext, "overlay")

    /** 系统选图期间隐藏，避免悬浮窗盖住选择器。 */
    fun suspendPeekForPicker(context: Context) = suspendPeek(context.applicationContext, "picker")

    fun resumePeekFromPicker(context: Context) = resumePeek(context.applicationContext, "picker")

    /** 选图期间连卡片一起藏起来并放掉触摸，回来再恢复。 */
    fun suspendForPicker(context: Context) {
        val app = context.applicationContext
        suspendPeekForPicker(app)
        val view = currentView ?: return
        val params = currentParams ?: return
        if (cardHiddenForPicker) return
        cardHiddenForPicker = true
        cardFlagsBeforePicker = params.flags
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { wm?.updateViewLayout(view, params) }
        view.visibility = View.INVISIBLE
    }

    fun resumeFromPicker(context: Context) {
        val app = context.applicationContext
        if (cardHiddenForPicker) {
            cardHiddenForPicker = false
            val view = currentView
            val params = currentParams
            if (view != null && params != null) {
                params.flags = cardFlagsBeforePicker
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                runCatching { wm?.updateViewLayout(view, params) }
                view.visibility = View.VISIBLE
            }
        }
        resumePeekFromPicker(app)
    }

    /** 摘要 / 输入 / 结果态都可以向下拖动把手：整张卡滑向右边缘、缩成胶囊。 */
    private fun enterPeek(app: Context, baseCenterYOverride: Int? = null) {
        val s = session ?: return
        val view = currentView ?: return
        val collapsible = s.state == BubbleState.SUMMARY ||
            s.state == BubbleState.INPUT ||
            s.state == BubbleState.RESULT
        if (!collapsible || s.busy) return
        if (collapsing) return
        // 已经过了 60 秒：这一轮不再生成胶囊，卡片回弹即可。
        if (peekDeadlineAt > 0L && peekRemainingMs() <= 0L) {
            view.animate().translationY(0f).alpha(1f)
                .scaleX(1f).scaleY(1f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            return
        }
        collapsing = true
        cancelAutoDismiss()
        if (cancelPeekEntrance()) {
            // 入场动画还没跑就被收起：先把卡片摆正，拖动过程才看得见。
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
        }
        if (s.state == BubbleState.INPUT) {
            // 收起时把键盘一起收掉，回来再点输入框继续写。
            hideInput(app, s.noteInput)
        }
        val screenH = app.resources.displayMetrics.heightPixels

        // 卡片被拖动时 translationY 已经偏离原位：用「未位移」的基准算，
        // 再把终点夹进屏幕，胶囊就会落在手指松开的位置，而不是跳回原位。
        val baseCenterY = baseCenterYOverride ?: run {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            loc[1] - view.translationY.toInt() + view.height / 2
        }
        val currentCenterY = baseCenterY + view.translationY.toInt()
        val peekHalf = dp(app, 25)
        val targetCenterY = currentCenterY.coerceIn(
            dp(app, 40) + peekHalf,
            screenH - dp(app, 40) - peekHalf,
        )
        // 缩到 12% 后，卡片视觉中心会被右侧 pivot 拉走；
        // 补一个平移，让它正好落到胶囊出现的位置，看起来像「卡片变成了胶囊」。
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val capsuleCenterX = app.resources.displayMetrics.widthPixels -
            dp(app, 6) - dp(app, 104) / 2f
        val targetTranslationX =
            capsuleCenterX - (loc[0] - view.translationX) - view.width + view.width * 0.06f

        view.animate().cancel()
        view.pivotX = view.width.toFloat()
        view.pivotY = view.height / 2f
        view.animate()
            .translationX(targetTranslationX)
            .translationY((targetCenterY - baseCenterY).toFloat())
            .scaleX(0.12f)
            .scaleY(0.12f)
            .alpha(0f)
            .setDuration(210L)
            .setInterpolator(AccelerateInterpolator())
            .withLayer()
            .withEndAction {
                collapsing = false
                if (session !== s || currentView !== view) {
                    // 被新会话/新窗口顶掉：如果卡片还挂着，先还原，别留一张半透明的卡挡点击。
                    if (view.isAttachedToWindow) {
                        view.animate().translationX(0f).translationY(0f)
                            .scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(180L)
                            .start()
                    }
                    return@withEndAction
                }
                // 只有确认卡片真的离开窗口，才显示胶囊；否则两张窗会叠在一起。
                if (!removeWindow(app, view)) {
                    view.animate().translationX(0f).translationY(0f)
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(180L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    return@withEndAction
                }
                currentView = null
                view.scaleX = 1f
                view.scaleY = 1f
                view.alpha = 1f
                view.translationX = 0f
                view.translationY = 0f
                showPeek(app, s, targetCenterY)
            }
            .start()
    }

    private fun showPeek(app: Context, s: BubbleSession, centerY: Int) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        // 任何残留的胶囊/目标先清掉，避免叠出第二颗。
        sweepStaleWindows(app)
        peekView?.let { removeWindow(app, it) }
        peekView = null
        peekParams = null
        isPeeking = false
        if (peekHintView != null) hideDropHint(app)
        val density = app.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()
        val screenW = app.resources.displayMetrics.widthPixels
        val screenH = app.resources.displayMetrics.heightPixels
        val windowW = dp(104)
        val windowH = dp(50)
        val edgeMargin = dp(6)
        val safeTop = dp(24)
        val safeBottom = dp(24)
        // 60 秒硬计时从胶囊第一次出现开始，之后展开/收起都不续期。
        if (peekDeadlineAt <= 0L) {
            peekDeadlineAt = SystemClock.elapsedRealtime() + 60_000L
        }

        val bubble = FrameLayout(app).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        val sourceColor = when (s.capture.source) {
            CaptureSource.WECHAT -> "#07C160"
            CaptureSource.ALIPAY -> "#1677FF"
            else -> "#3B82F6"
        }
        val pill = LinearLayout(app).apply {
            tag = "peek_pill"
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
        bubble.contentDescription = "待记 ${s.pendingCount.coerceAtLeast(1)} 笔，点按展开，向下拖动可关闭提醒"

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
            y = (centerY - windowH / 2).coerceIn(safeTop, screenH - windowH - safeBottom)
        }

        // 删除目标改成底部居中的小胶囊 ✕：只有把胶囊真的拖到它上面松手才关闭。
        // 原来判定的是「屏幕下 170dp × 中间 60% 宽」一整块隐形区域，
        // 胶囊本来就停在屏幕下方，随手一拖就会误关，这才是「删除很怪」的根源。
        val dropCenterX = screenW / 2f
        val dropCenterY = screenH - dp(84).toFloat()
        // 命中区和 ✕ 的视觉大小对齐：横向宽、纵向紧，松手范围好判断。
        val dropHalfW = dp(88)
        val dropHalfH = dp(34)
        val dropHalfWOut = dp(98)
        val dropHalfHOut = dp(46)
        var dropShown = false
        var dropArmed = false

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var velocity: VelocityTracker? = null
        val touchSlop = ViewConfiguration.get(app).scaledTouchSlop.toFloat()

        fun insideDrop(halfW: Int, halfH: Int): Boolean {
            val cx = params.x + windowW / 2f
            val cy = params.y + windowH / 2f
            return Math.abs(cx - dropCenterX) <= halfW &&
                Math.abs(cy - dropCenterY) <= halfH
        }

        fun refreshDropTarget() {
            // 先向下拖一点，或已经贴着目标，才把 ✕ 露出来；
            // 只是横向把胶囊挪到另一边时，不打扰用户。
            val near = params.y - startY > dp(16) ||
                insideDrop(dropHalfWOut + dp(6), dropHalfHOut + dp(6))
            if (near != dropShown) {
                dropShown = near
                if (near) showDropHint(app) else hideDropHint(app)
            }
            if (!near) {
                if (dropArmed) {
                    dropArmed = false
                    setHintHot(app, false)
                }
                bubble.scaleX = 1f
                bubble.scaleY = 1f
                return
            }
            // 外圈进、更外圈才算退出：留迟滞，手抖不会把 ✕ 反复点亮。
            val armed = if (dropArmed) {
                insideDrop(dropHalfWOut, dropHalfHOut)
            } else {
                insideDrop(dropHalfW, dropHalfH)
            }
            if (armed != dropArmed) {
                dropArmed = armed
                setHintHot(app, armed)
                bubble.performHapticFeedback(
                    if (armed) HapticFeedbackConstants.LONG_PRESS
                    else HapticFeedbackConstants.CONTEXT_CLICK,
                )
            }
            bubble.scaleX = if (dropArmed) 0.84f else 0.94f
            bubble.scaleY = bubble.scaleX
        }

        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    dropArmed = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    cancelPeekDismiss()
                    // 按下先轻微压缩：既是按压反馈，也提示「这颗胶囊可以拖」。
                    bubble.animate().cancel()
                    bubble.animate().scaleX(0.94f).scaleY(0.94f)
                        .setDuration(90L).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(event)
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging &&
                        Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop
                    ) {
                        dragging = true
                        bubble.animate().cancel()
                        bubble.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                            .coerceIn(edgeMargin, screenW - windowW - edgeMargin)
                        params.y = (startY + dy).toInt()
                            .coerceIn(safeTop, screenH - windowH - safeBottom)
                        runCatching { wm.updateViewLayout(bubble, params) }
                        refreshDropTarget()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 先把 tracker 收干净：后面的分支可能立刻移除视图。
                    val tracker = velocity
                    velocity = null
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    tracker?.recycle()
                    val wasDragging = dragging
                    // 以松手时的实际位置判定，不依赖最后一帧 MOVE 是否到达。
                    val releasedArmed = wasDragging && dropShown &&
                        insideDrop(dropHalfWOut, dropHalfHOut)
                    dragging = false
                    dropArmed = false
                    dropShown = false
                    hideDropHint(app)

                    if (wasDragging && releasedArmed &&
                        event.actionMasked == MotionEvent.ACTION_UP
                    ) {
                        // 关掉前先把胶囊吸进 ✕，动作有落点，而不是原地淡出。
                        cancelPeekDismiss()
                        bubble.setOnTouchListener { _, _ -> true }
                        val tx = dropCenterX - (params.x + windowW / 2f)
                        val ty = dropCenterY - (params.y + windowH / 2f)
                        bubble.animate().cancel()
                        bubble.animate()
                            .translationX(tx)
                            .translationY(ty)
                            .scaleX(0.18f)
                            .scaleY(0.18f)
                            .alpha(0f)
                            .setDuration(170L)
                            .setInterpolator(AccelerateInterpolator())
                            .withEndAction {
                                finishPeekDismiss(app, bubble, notifyDismissed = true)
                            }
                            .start()
                    } else if (wasDragging) {
                        snapPeekToEdge(
                            app, wm, bubble, params, screenW, edgeMargin, windowW,
                            vx,
                        )
                        bubble.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(140L).start()
                        schedulePeekDismiss(app)
                    } else {
                        exitPeek(app)
                    }
                    true
                }

                else -> false
            }
        }

        try {
            bubble.alpha = 0f
            bubble.scaleX = 0.86f
            bubble.scaleY = 0.86f
            wm.addView(bubble, params)
            peekView = bubble
            peekParams = params
            isPeeking = true
            bubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            schedulePeekDismiss(app)
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
        velocityX: Float,
    ) {
        val fling = dp(app, 420).toFloat()
        val target = when {
            velocityX < -fling -> margin
            velocityX > fling -> screenW - windowSize - margin
            params.x + windowSize / 2 < screenW / 2 -> margin
            else -> screenW - windowSize - margin
        }
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

    /** 关闭目标：底部居中的小胶囊 ✕。只有把胶囊拖到它上面松手才会关闭。 */
    private fun showDropHint(app: Context) {
        if (peekHintView != null) return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val target = TextView(app).apply {
            tag = "peek_drop_target"
            text = "✕ 拖到此处关闭"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D90F172A"))
                cornerRadius = dp(app, 20).toFloat()
                setStroke(dp(app, 1), Color.parseColor("#52FFFFFF"))
            }
            elevation = dp(app, 8).toFloat()
        }

        val params = WindowManager.LayoutParams(
            dp(app, 156),
            dp(app, 40),
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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(app, 64)
        }
        try {
            target.alpha = 0f
            target.translationY = dp(app, 10).toFloat()
            wm.addView(target, params)
            peekHintView = target
            peekHintParams = params
            target.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(150L)
                .start()
        } catch (_: Throwable) {
            peekHintView = null
            peekHintParams = null
        }
    }

    /** 只在「进 / 出目标」这一刻调用，避免拖动时每一帧都重建 drawable。 */
    private fun setHintHot(app: Context, hot: Boolean) {
        val target = peekHintView as? TextView ?: return
        target.text = if (hot) "松手关闭" else "✕ 拖到此处关闭"
        target.setTextColor(Color.parseColor(if (hot) "#FFFFFF" else "#E2E8F0"))
        target.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (hot) "#F2EF4444" else "#D90F172A"))
            cornerRadius = dp(app, 20).toFloat()
            setStroke(
                dp(app, 1),
                Color.parseColor(if (hot) "#FFC9C9" else "#52FFFFFF"),
            )
        }
        target.animate()
            .scaleX(if (hot) 1.06f else 1f)
            .scaleY(if (hot) 1.06f else 1f)
            .setDuration(120L)
            .start()
        // 胶囊边框同步变红，明确“松手就关”。
        val pill = peekView?.findViewWithTag<View>("peek_pill")
        if (pill != null) {
            pill.background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F172A"))
                cornerRadius = dp(app, 18).toFloat()
                setStroke(
                    dp(app, 1),
                    Color.parseColor(if (hot) "#EF4444" else "#38FFFFFF"),
                )
            }
        }
    }

    private fun hideDropHint(app: Context) {
        val hint = peekHintView ?: return
        peekHintView = null
        peekHintParams = null
        hint.animate().alpha(0f).setDuration(100L)
            .withEndAction { removeWindow(app, hint) }
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
        bubble.animate().cancel()
        bubble.setOnTouchListener(null)
        // 先确认胶囊真的能移除，再去加卡片；删不掉就保持胶囊原样，
        // 绝不让「卡片 + 胶囊」叠在一起，把下面的按钮点不到。
        if (!removeWindow(app, bubble)) {
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(120L).start()
            schedulePeekDismiss(app)
            return
        }
        peekView = null
        peekParams = null
        isPeeking = false
        collapsing = false
        if (currentView != null) return

        cancelPeekEntrance()
        root.animate().cancel()
        root.alpha = 0f
        root.scaleX = 0.12f
        root.scaleY = 0.12f
        root.translationX = 0f
        root.translationY = 0f
        try {
            wm.addView(root, params)
            currentView = root
            val runnable = Runnable {
                peekEntranceRunnable = null
                if (currentView !== root || !root.isAttachedToWindow) return@Runnable
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
            peekEntranceRunnable = runnable
            root.post(runnable)
        } catch (_: Throwable) {
            currentView = null
        }
    }
    /** 到期/主动关闭时：胶囊淡出，只留悬浮窗。 */
    fun fadePeekOut(context: Context, notifyDismissed: Boolean = false) {
        val app = context.applicationContext
        val bubble = peekView ?: return
        cancelPeekDismiss()
        hideDropHint(app)
        isPeeking = false
        // 淡出期间不再响应点击，避免又弹回卡片。
        bubble.setOnTouchListener { _, _ -> true }
        bubble.animate().cancel()
        bubble.animate()
            .alpha(0f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(180L)
            .withEndAction { finishPeekDismiss(app, bubble, notifyDismissed) }
            .start()
    }

    /** 胶囊离场（淡出或吸进 ✕）后的统一收尾。 */
    private fun finishPeekDismiss(app: Context, bubble: View, notifyDismissed: Boolean) {
        // 动画期间可能已经有新的气泡顶上来：只有「这颗还是当前胶囊」才清状态，
        // 否则会把新会话一起清掉。
        val wasCurrent = peekView === bubble
        if (wasCurrent) {
            cancelPeekDismiss()
            hideDropHint(app)
        }
        val removed = removeWindow(app, bubble)
        if (removed && wasCurrent) {
            peekView = null
            peekParams = null
            isPeeking = false
        } else if (!removed) {
            // 极端 ROM 上第一次删不掉，隔一帧再补一刀。
            mainHandler.postDelayed({ removeWindow(app, bubble) }, 300L)
        }
        if (removed && notifyDismissed && wasCurrent) {
            session = null
            peekDeadlineAt = 0L
            onDismissed?.invoke()
        }
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
        val app = context.applicationContext
        collapsing = false
        cancelAutoDismiss()
        cancelPeekDismiss()
        cancelPeekEntrance()
        hideDropHint(app)
        val hadPeek = peekView != null
        peekView?.let { removeWindow(app, it) }
        peekView = null
        peekParams = null
        isPeeking = false
        peekDeadlineAt = 0L
        peekSuppressors.clear()
        cardHiddenForPicker = false
        val view = currentView ?: run {
            sweepStaleWindows(app)
            session = null
            if (notifyDismissed && hadPeek) onDismissed?.invoke()
            return
        }
        currentView = null
        currentParams = null
        session = null
        removeWindow(app, view)
        sweepStaleWindows(app)
        if (notifyDismissed) {
            onDismissed?.invoke()
        }
    }
}
