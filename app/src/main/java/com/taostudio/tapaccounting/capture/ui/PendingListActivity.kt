package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.ImagePickerActivity
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.CaptureInboxEvents
import com.taostudio.tapaccounting.capture.bridge.CaptureBridgeClient
import com.taostudio.tapaccounting.capture.bridge.CaptureNoteClassifier
import com.taostudio.tapaccounting.capture.bridge.CaptureReconciler
import com.taostudio.tapaccounting.capture.bridge.RuleLearningManager
import com.taostudio.tapaccounting.capture.contract.AccountingContext
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.CaptureSources
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.ImportBill
import com.taostudio.tapaccounting.capture.contract.ImportItemStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.AssetMappingRule
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.capture.data.CategorySource
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import com.taostudio.tapaccounting.capture.data.RuleBundleCodec
import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import com.taostudio.tapaccounting.BuildConfig
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.capture.data.RuleBundleStore
import com.taostudio.tapaccounting.capture.ingest.CaptureIngestCoordinator
import com.taostudio.tapaccounting.capture.notify.CaptureBubbleManager
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 敲敲待记插件工作台：
 * - Tab 0：待记收件箱（备注实时保存并秒级重分类、点选真实资产/分类并顺手记忆规则、自动统一归类、一键批量入账）
 * - Tab 1：资产映射（读取敲敲记账真实资产，预置自动记账映射规则）
 * - Tab 2：分类规则（读取敲敲记账真实分类树，预置自动记账关键字/正则规则）
 * - Tab 3：设置与调试（付款提醒方式切换、纯净支出过滤、模拟与自检工具）
 */
class PendingListActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge by lazy { CaptureBridgeClient(this) }
    private val ruleViews by lazy {
        RuleMappingViews(this, scope) { load() }.apply {
            onSyncDataRules = { dataRuleViews.syncFromLocal() }
        }
    }
    private val dataRuleViews by lazy {
        DataRuleViews(
            activity = this,
            scope = scope,
            onDataChanged = { load() },
            onExport = { exportRuleBundle() },
            onImport = { importRuleBundle() },
        )
    }
    private val rowTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val prefillTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private var currentTab = 0
    /** 规则页内分段：0 数据规则 / 1 分类规则 / 2 资产映射。 */
    private var rulesSection = 0
    private var latestSnapshot: Snapshot? = null
    private var isAiBusy = false
    private var ignoredExpanded = false
    /** null=全部；否则是 CaptureStatus.RECORDED / IGNORED / DUPLICATE。 */
    private var historyFilter: String? = null
    private var pendingExportJson: String? = null
    private val noteSaveJobs = mutableMapOf<String, Job>()
    private val splitDrafts = mutableMapOf<String, MutableList<CaptureBridgeClient.SplitItem>>()
    private var skipNextResumeLoad = false
    private var loadJob: Job? = null
    private var inboxEventsJob: Job? = null
    private var imeWasVisible = false
    private var savedInboxScrollY: Int = 0
    private var savedRulesScrollY: Int = 0
    private val pendingCardViews = mutableMapOf<String, View>()
    private val pendingCategoryChips = mutableMapOf<String, TextView>()
    private val pendingCategorySources = mutableMapOf<String, String?>()
    private val pendingAssetChips = mutableMapOf<String, TextView>()
    private val pendingBookChips = mutableMapOf<String, TextView>()
    private var inboxContainer: LinearLayout? = null
    private var summaryHeroCardView: View? = null
    private var summaryStatsTitleView: TextView? = null
    private var summaryStatsSubView: TextView? = null
    private var summaryAmountView: TextView? = null
    private var summaryAmountLabelView: TextView? = null
    private var summaryImportButton: TextView? = null
    private var emptyStateCardView: View? = null
    private var ignoredCardView: ViewGroup? = null
    private var ignoredTitleView: TextView? = null
    private val ignoredItemRowViews = mutableMapOf<String, View>()

    private lateinit var bridgeDot: View
    private lateinit var bridgeBadge: TextView
    private lateinit var tabViews: List<TextView>
    private lateinit var bodyFrame: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        CaptureUi.applyLightStatusBar(this)
        requestNotificationPermissionIfNeeded()
        installImeHideWatcher()
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResumeLoad) {
            skipNextResumeLoad = false
            return
        }
        load()
    }

    override fun onStart() {
        super.onStart()
        // 悬浮窗是浮层，不会让本页走 onResume；入账后靠这个信号即时刷新列表。
        if (inboxEventsJob == null) {
            inboxEventsJob = scope.launch {
                CaptureInboxEvents.changes
                    .conflate()
                    .collectLatest {
                        delay(120)
                        load()
                    }
            }
        }
    }

    override fun onStop() {
        inboxEventsJob?.cancel()
        inboxEventsJob = null
        super.onStop()
    }

    override fun onDestroy() {
        noteSaveJobs.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
    }

    /** 按返回键收起输入法时，输入框通常不会失焦；这里主动清焦点，触发 AI 归类。 */
    private fun installImeHideWatcher() {
        val root = findViewById<View>(android.R.id.content) ?: return
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = Rect()
            root.getWindowVisibleDisplayFrame(rect)
            val screenHeight = root.rootView.height
            val visible = screenHeight > 0 && screenHeight - rect.bottom > dp(120)
            if (imeWasVisible && !visible) {
                (currentFocus as? EditText)?.clearFocus()
            }
            imeWasVisible = visible
        }
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CaptureUi.BG_PAGE)
        }

        // 顶部白底导航头（带底部分割线）
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CaptureUi.BG_SURFACE)
            setPadding(dp(16), dp(14), dp(16), dp(12))
            elevation = dp(1).toFloat()
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setPadding(0, 0, dp(10), 0)
            setOnClickListener { finish() }
        })

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBlock.addView(TextView(this).apply {
            text = "待记收件箱"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        titleBlock.addView(TextView(this).apply {
            text = "补备注、归类、拆分与入账"
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(1), 0, 0)
        })
        titleRow.addView(titleBlock)

        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = CaptureUi.rippleBg(CaptureUi.BG_SUBTLE, dp(999), dp(1), CaptureUi.BORDER)
            setOnClickListener { load() }
        }
        bridgeDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
            background = CaptureUi.roundedBg(CaptureUi.TEXT_MUTED, dp(99))
        }
        bridgeBadge = TextView(this).apply {
            text = "连接中…"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_SECONDARY)
        }
        statusPill.addView(bridgeDot)
        statusPill.addView(bridgeBadge)
        titleRow.addView(statusPill)
        topBar.addView(titleRow)

        // 分段胶囊导航栏（Segmented Control）
        val segmentTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = CaptureUi.roundedBg(CaptureUi.BG_SEGMENT, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        val tabLabels = listOf("待记", "规则", "设置", "历史")
        tabViews = tabLabels.mapIndexed { index, label ->
            TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (currentTab != index) {
                        savedInboxScrollY = 0
                        savedRulesScrollY = 0
                        currentTab = index
                        updateTabSelection()
                        latestSnapshot?.let { renderCurrentTab(it) }
                    }
                }
            }.also { segmentTrack.addView(it) }
        }
        topBar.addView(segmentTrack)
        root.addView(topBar)

        root.addView(View(this).apply {
            setBackgroundColor(CaptureUi.BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        bodyFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(bodyFrame)

        updateTabSelection()
        return root
    }

    private fun updateTabSelection() {
        tabViews.forEachIndexed { index, tv ->
            val selected = index == currentTab
            tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tv.setTextColor(if (selected) CaptureUi.TEXT_PRIMARY else CaptureUi.TEXT_SECONDARY)
            tv.background = if (selected) {
                CaptureUi.roundedBg(Color.WHITE, dp(9), dp(1), CaptureUi.BORDER)
            } else {
                null
            }
            tv.elevation = if (selected) dp(1).toFloat() else 0f
        }
    }

    private fun findInnerScrollView(v: View?): ScrollView? {
        if (v == null) return null
        if (v is ScrollView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val found = findInnerScrollView(v.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun load() {
        val curScroll = (bodyFrame.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        if (curScroll > 0) savedInboxScrollY = curScroll
        val innerRulesScroll = findInnerScrollView(bodyFrame)?.scrollY ?: 0
        if (innerRulesScroll > 0) savedRulesScrollY = innerRulesScroll
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val app = CaptureApp.from(this@PendingListActivity)
                val repository = app.repository
                val db = app.database
                val snapshot = withContext(Dispatchers.IO) {
                    CaptureRuleMatcher.ensureSeeded(this@PendingListActivity)
                    val dedupChanged = repository.deduplicatePendingItems()
                    val allItems = repository.list(200)
                    val changed = CaptureReconciler.reconcile(repository, bridge, allItems)
                    val refreshed = if (changed > 0 || dedupChanged > 0) repository.list(200) else allItems
                    val onlyExpense = CapturePrefs.isOnlyExpense(this@PendingListActivity)
                    val pendingItems = refreshed.filter { item ->
                        item.status in CaptureStatus.PENDING_COUNT_STATUSES &&
                            (
                                !onlyExpense ||
                                    item.billType == null ||
                                    item.billType == LocalBillType.TYPE_EXPENSE ||
                                    item.subType == LocalBillType.SUBTYPE_REFUND ||
                                    item.needsRefundLink
                                )
                    }
                    val ping = bridge.pingDetail()
                    val accountingCtx = if (ping.ok) bridge.getAccountingContext() else null
                    Snapshot(
                        pendingItems = pendingItems,
                        ignoredItems = repository.listIgnored(50),
                        historyItems = repository.listHistory(200),
                        historyCount = repository.countHistory(),
                        eventCount = app.eventRepository.count(),
                        assetRules = db.assetMappingDao().listAll(),
                        accountingRules = db.accountingRuleDao().listAll(),
                        ruleBundle = RuleBundleStore.load(this@PendingListActivity),
                        accountingContext = accountingCtx,
                        bridgeAvailable = ping.ok,
                        aiEnabled = ping.aiEnabled,
                    )
                }
                latestSnapshot = snapshot
                updateBridgeStatus(snapshot.bridgeAvailable, snapshot.accountingContext)
                tabViews[0].text = if (snapshot.pendingItems.isNotEmpty()) {
                    "待记 ${snapshot.pendingItems.size}"
                } else {
                    "待记"
                }
                tabViews.getOrNull(3)?.text = if (snapshot.historyCount > 0) {
                    "历史 ${snapshot.historyCount}"
                } else {
                    "历史"
                }
                renderCurrentTab(snapshot)
                CaptureNotifier.refreshAll(this@PendingListActivity)
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                updateBridgeStatus(false, null)
                toast("读取失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun updateBridgeStatus(available: Boolean, ctx: AccountingContext?) {
        val assetCount = ctx?.assets?.size ?: 0
        val catCount = ctx?.categories?.size ?: 0
        bridgeDot.background = CaptureUi.roundedBg(
            if (available) CaptureUi.SUCCESS else CaptureUi.DANGER,
            dp(99),
        )
        bridgeBadge.text = if (available) {
            "已连敲敲记账 · ${assetCount}资产/${catCount}分类"
        } else {
            "未连接（点此重试）"
        }
        bridgeBadge.setTextColor(if (available) CaptureUi.TEXT_SECONDARY else CaptureUi.DANGER)
    }

    private fun renderCurrentTab(snapshot: Snapshot) {
        val currentScroll = (bodyFrame.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        val targetScroll = if (currentScroll > 0) currentScroll else savedInboxScrollY
        val innerRulesScroll = findInnerScrollView(bodyFrame)?.scrollY ?: 0
        val targetRulesScroll = if (innerRulesScroll > 0) innerRulesScroll else savedRulesScrollY
        bodyFrame.removeAllViews()
        val view = when (currentTab) {
            0 -> buildInboxTab(snapshot)
            1 -> buildRulesTab(snapshot)
            2 -> ruleViews.buildSettingsTab(
                onSimulateExpense = { simulateCapture() },
                onSimulateWcdb = { simulateWcdbSamples() },
                onTestRuleSamples = { parseSamples() },
                onCustomSimulateExpense = { showCustomSimulateDialog() },
            )
            else -> buildHistoryTab(snapshot)
        }
        bodyFrame.addView(view)
        if (targetScroll > 0 && currentTab == 0) {
            (view as? ScrollView)?.post {
                (view as? ScrollView)?.scrollTo(0, targetScroll)
            }
        } else if (targetRulesScroll > 0 && currentTab == 1) {
            view.post {
                findInnerScrollView(view)?.scrollTo(0, targetRulesScroll)
            }
        }
    }

    /** 规则页：顶部固定「数据规则 / 分类规则 / 资产映射」分段，复用现有分类/资产页面。 */
    private fun buildRulesTab(snapshot: Snapshot): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CaptureUi.BG_PAGE)
        }
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = CaptureUi.roundedBg(CaptureUi.BG_SEGMENT, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                topMargin = dp(10)
            }
        }
        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val sectionTabs = ArrayList<TextView>()

        fun updateSectionSelection() {
            sectionTabs.forEachIndexed { index, tv ->
                val selected = index == rulesSection
                tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                tv.setTextColor(if (selected) CaptureUi.TEXT_PRIMARY else CaptureUi.TEXT_SECONDARY)
                tv.background = if (selected) {
                    CaptureUi.roundedBg(Color.WHITE, dp(9), dp(1), CaptureUi.BORDER)
                } else {
                    null
                }
                tv.elevation = if (selected) dp(1).toFloat() else 0f
            }
        }

        fun renderSection() {
            content.removeAllViews()
            content.addView(
                when (rulesSection) {
                    1 -> ruleViews.buildCategoryRuleTab(snapshot.accountingRules, snapshot.accountingContext)
                    2 -> ruleViews.buildAssetMappingTab(snapshot.assetRules, snapshot.accountingContext)
                    else -> dataRuleViews.buildDataRuleTab(snapshot.ruleBundle)
                },
            )
        }

        listOf("数据规则", "分类规则", "资产映射").forEachIndexed { index, label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (rulesSection != index) {
                        rulesSection = index
                        updateSectionSelection()
                        renderSection()
                    }
                }
            }
            sectionTabs.add(tv)
            track.addView(tv)
        }
        root.addView(track)
        root.addView(content)
        updateSectionSelection()
        renderSection()
        return root
    }

    // ════════════════════════════════════════════════════════
    //  Tab 0: 待记收件箱
    // ════════════════════════════════════════════════════════

    private fun buildHistoryTab(snapshot: Snapshot): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        val recorded = snapshot.historyItems.count { it.status == CaptureStatus.RECORDED }
        val ignored = snapshot.historyItems.count { it.status == CaptureStatus.IGNORED }
        val duplicate = snapshot.historyItems.count { it.status == CaptureStatus.DUPLICATE }

        val header = CaptureUi.card(this)
        header.addView(TextView(this).apply {
            text = "历史记录"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        header.addView(TextView(this).apply {
            text = "已记 $recorded · 已不计 $ignored · 重复 $duplicate"
            textSize = 12.5f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setPadding(0, dp(6), 0, 0)
        })
        header.addView(TextView(this).apply {
            text = "原始报文 ${snapshot.eventCount} 条。待记和失败不会自动进历史；" +
                "历史默认保留最近一年，可在这里手动清理。"
            textSize = 11.5f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        val bulkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        bulkRow.addView(
            historyAction("清理 1 年前", CaptureUi.WARNING, CaptureUi.WARNING_SOFT) {
                confirmClearHistoryOlderThan()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) },
        )
        bulkRow.addView(
            historyAction("清空历史", CaptureUi.DANGER, CaptureUi.DANGER_SOFT) {
                confirmClearHistory()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) },
        )
        bulkRow.addView(
            historyAction("原始报文", CaptureUi.BRAND_DARK, CaptureUi.BRAND_SOFT) {
                startActivity(Intent(this@PendingListActivity, CaptureHistoryActivity::class.java))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) },
        )
        bulkRow.addView(
            historyAction("清理旧报文", CaptureUi.TEXT_SECONDARY, CaptureUi.BG_SUBTLE) {
                confirmTrimEvents()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        header.addView(bulkRow)
        root.addView(header)

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        listOf(
            null to "全部",
            CaptureStatus.RECORDED to "已记",
            CaptureStatus.IGNORED to "已不计",
            CaptureStatus.DUPLICATE to "重复",
        ).forEach { (status, label) ->
            val selected = historyFilter == status
            filterRow.addView(
                historyAction(
                    text = label,
                    textColor = if (selected) Color.WHITE else CaptureUi.TEXT_SECONDARY,
                    bgColor = if (selected) CaptureUi.BRAND else CaptureUi.BG_SEGMENT,
                ) {
                    historyFilter = status
                    latestSnapshot?.let { renderCurrentTab(it) }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) },
            )
        }
        root.addView(filterRow)

        val items = snapshot.historyItems.filter { item ->
            historyFilter == null || item.status == historyFilter
        }
        if (items.isEmpty()) {
            val empty = CaptureUi.card(this)
            empty.addView(TextView(this).apply {
                text = if (snapshot.historyCount == 0) {
                    "还没有历史记录。\n入账、标记不计或去重后的待记会出现在这里。"
                } else {
                    "当前筛选没有记录。"
                }
                textSize = 13f
                setTextColor(CaptureUi.TEXT_MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(18))
            })
            root.addView(empty)
        } else {
            items.forEach { item ->
                root.addView(buildHistoryCard(item))
            }
        }

        scroll.addView(root)
        return scroll
    }

    private fun buildHistoryCard(item: PaymentCapture): View {
        val card = CaptureUi.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            isClickable = true
            isFocusable = true
            background = CaptureUi.rippleBg(
                fillColor = CaptureUi.BG_SURFACE,
                radiusPx = dp(16),
                strokeWidthPx = dp(1),
                strokeColor = CaptureUi.BORDER,
            )
            setOnClickListener { openHistoryItem(item) }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val isAlipay = item.source == CaptureSource.ALIPAY
        top.addView(TextView(this).apply {
            text = sourceLabel(item.source)
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isAlipay) CaptureUi.ALIPAY else CaptureUi.WECHAT)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = CaptureUi.roundedBg(
                if (isAlipay) CaptureUi.ALIPAY_SOFT else CaptureUi.WECHAT_SOFT,
                dp(6),
            )
        })
        top.addView(TextView(this).apply {
            text = item.merchant?.takeIf { it.isNotBlank() } ?: "未知商户"
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            text = formatAmount(item.amountCents)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                if (item.billType == LocalBillType.TYPE_INCOME) CaptureUi.SUCCESS else CaptureUi.TEXT_PRIMARY
            )
        })
        card.addView(top)

        val detail = listOfNotNull(
            rowTimeFormat.format(Date(item.paidAt)),
            historyStatusLabel(item.status),
            item.categoryName?.takeIf { it.isNotBlank() }?.let { "分类 $it" },
            item.note?.takeIf { it.isNotBlank() }?.let { "备注 $it" },
            when (item.status) {
                CaptureStatus.RECORDED -> "点击查看账单"
                CaptureStatus.IGNORED, CaptureStatus.DUPLICATE -> "点击操作"
                else -> null
            },
        ).joinToString(" · ")
        card.addView(TextView(this).apply {
            text = detail
            textSize = 11.5f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(5), 0, 0)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        if (item.status == CaptureStatus.IGNORED || item.status == CaptureStatus.DUPLICATE) {
            actions.addView(
                historyAction("恢复待记", CaptureUi.BRAND_DARK, CaptureUi.BRAND_SOFT) {
                    mutate { repo -> repo.updateStatus(item.id, CaptureStatus.PENDING) }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) },
            )
        }
        actions.addView(
            historyAction("删除", CaptureUi.DANGER, CaptureUi.DANGER_SOFT) {
                confirmDeleteHistory(item)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(actions)
        return card
    }

    private fun historyAction(
        text: String,
        textColor: Int,
        bgColor: Int,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(textColor)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = CaptureUi.rippleBg(bgColor, dp(9))
        setOnClickListener { onClick() }
    }

    /**
     * 历史卡片点击的主操作：
     * - 已记：直接打开关联账单详情（没有关联账单时才降级到操作菜单）；
     * - 已不计/重复：弹出恢复/删除/原始报文操作。
     */
    private fun openHistoryItem(item: PaymentCapture) {
        if (item.status != CaptureStatus.RECORDED) {
            showHistoryActions(item)
            return
        }
        scope.launch {
            val app = CaptureApp.from(this@PendingListActivity)
            val billId = withContext(Dispatchers.IO) {
                val link = app.database.externalImportDao().getByCaptureId(item.id) ?: return@withContext null
                if (app.database.billDao().getBillById(link.billId) != null) link.billId else null
            }
            if (billId != null) {
                com.taostudio.tapaccounting.ui.activity.BillDetailActivity.start(
                    this@PendingListActivity,
                    billId,
                )
            } else {
                showHistoryActions(item)
            }
        }
    }

    private fun showHistoryActions(item: PaymentCapture) {
        val actions = mutableListOf<String>()
        if (item.status == CaptureStatus.IGNORED || item.status == CaptureStatus.DUPLICATE) {
            actions.add("恢复为待记")
        }
        if (!item.rawJson.isNullOrBlank()) {
            actions.add("查看原始报文")
        }
        actions.add("删除这条历史")

        AlertDialog.Builder(this)
            .setTitle("${item.merchant?.takeIf { it.isNotBlank() } ?: "历史记录"} · ${formatAmount(item.amountCents)}")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "恢复为待记" -> mutate { repo ->
                        repo.updateStatus(item.id, CaptureStatus.PENDING)
                    }

                    "查看原始报文" -> showRawPayload(item)

                    "删除这条历史" -> confirmDeleteHistory(item)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showRawPayload(item: PaymentCapture) {
        val text = TextView(this).apply {
            this.text = item.rawJson.orEmpty()
            textSize = 11f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle("原始报文")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun historyStatusLabel(status: String): String = when (status) {
        CaptureStatus.RECORDED -> "已记"
        CaptureStatus.IGNORED -> "已不计"
        CaptureStatus.DUPLICATE -> "重复"
        else -> status
    }

    private fun confirmDeleteHistory(item: PaymentCapture) {
        AlertDialog.Builder(this)
            .setTitle("删除历史记录")
            .setMessage("只删除这条待记历史，不影响已经写入的账单。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                mutate { repo -> repo.deleteById(item.id) }
            }
            .show()
    }

    private fun confirmClearHistoryOlderThan() {
        val cutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
        AlertDialog.Builder(this)
            .setTitle("清理历史")
            .setMessage("删除 1 年前已记、已不计和重复的待记历史；账单本身不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                scope.launch {
                    val count = withContext(Dispatchers.IO) {
                        CaptureApp.from(this@PendingListActivity).repository
                            .deleteHistoryOlderThan(cutoff)
                    }
                    toast("已清理 $count 条历史")
                    load()
                }
            }
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空历史")
            .setMessage("删除全部已记、已不计和重复的待记历史；账单本身不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                scope.launch {
                    val count = withContext(Dispatchers.IO) {
                        CaptureApp.from(this@PendingListActivity).repository.clearHistory()
                    }
                    toast("已清空 $count 条历史")
                    load()
                }
            }
            .show()
    }

    private fun confirmTrimEvents() {
        AlertDialog.Builder(this)
            .setTitle("清理旧报文")
            .setMessage("原始报文只用于规则调试/历史排查，保留最近 2000 条，删除更早的记录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                scope.launch {
                    val count = withContext(Dispatchers.IO) {
                        CaptureApp.from(this@PendingListActivity).eventRepository.trimToLatest(2000)
                    }
                    toast("已清理 $count 条旧报文")
                    load()
                }
            }
            .show()
    }

    private fun buildInboxTab(snapshot: Snapshot): View {
        pendingCardViews.clear()
        pendingCategoryChips.clear()
        pendingCategorySources.clear()
        pendingAssetChips.clear()
        pendingBookChips.clear()
        ignoredItemRowViews.clear()
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(32))
        }
        inboxContainer = container
        scroll.addView(container)

        val items = snapshot.pendingItems
        if (items.isEmpty()) {
            val empty = buildEmptyStateCard()
            emptyStateCardView = empty
            summaryHeroCardView = null
            container.addView(empty)
        } else {
            emptyStateCardView = null
            val hero = buildSummaryHeroCard(items)
            summaryHeroCardView = hero
            container.addView(hero)
            items.forEach { item ->
                val card = buildCaptureCard(item, snapshot.accountingContext)
                pendingCardViews[item.id] = card
                container.addView(card)
            }
        }
        if (snapshot.ignoredItems.isNotEmpty()) {
            val ignored = buildIgnoredSection(snapshot.ignoredItems)
            ignoredCardView = ignored as? ViewGroup
            container.addView(ignored)
        } else {
            ignoredCardView = null
            ignoredTitleView = null
        }
        return scroll
    }

    private fun stylePendingCategoryChip(tv: TextView, cat: String?, source: String?) {
        val ok = !cat.isNullOrBlank()
        val prefix = when (source) {
            CategorySource.AI -> "AI · "
            CategorySource.RULE -> "规则 · "
            CategorySource.MANUAL -> "手动 · "
            else -> ""
        }
        tv.text = if (ok) "分类 · $prefix$cat" else "待选分类"
        tv.setTextColor(if (ok) CaptureUi.SUCCESS else CaptureUi.BRAND_DARK)
        tv.background = CaptureUi.rippleBg(
            fillColor = if (ok) CaptureUi.SUCCESS_SOFT else CaptureUi.BRAND_SOFT,
            radiusPx = dp(8),
            strokeWidthPx = dp(1),
            strokeColor = if (ok) CaptureUi.SUCCESS_BORDER else CaptureUi.BRAND_BORDER,
        )
        CaptureUi.applyTrailingIcon(
            tv,
            this,
            R.drawable.ic_chevron_down,
            if (ok) CaptureUi.SUCCESS else CaptureUi.BRAND_DARK,
            10,
        )
    }

    /** 只更新单张卡片的分类胶囊，不重建整页。 */
    private fun updatePendingCategory(itemId: String, categoryName: String?, source: String?) {
        val chip = pendingCategoryChips[itemId] ?: return
        val snapshot = latestSnapshot ?: return
        val newPending = snapshot.pendingItems.map { item ->
            if (item.id == itemId) {
                item.copy(categoryName = categoryName, categorySource = source)
            } else {
                item
            }
        }
        latestSnapshot = snapshot.copy(pendingItems = newPending)
        pendingCategorySources[itemId] = source
        stylePendingCategoryChip(chip, categoryName, source)
        updateSummaryStatsOnly()
    }

    private fun applyPendingCategoryUpdates(updates: Map<String, Pair<String?, String?>>) {
        if (updates.isEmpty()) return
        val snapshot = latestSnapshot ?: return
        val newPending = snapshot.pendingItems.map { item ->
            val update = updates[item.id]
            if (update != null) {
                item.copy(categoryName = update.first, categorySource = update.second)
            } else {
                item
            }
        }
        latestSnapshot = snapshot.copy(pendingItems = newPending)
        updates.forEach { (id, update) ->
            pendingCategorySources[id] = update.second
            pendingCategoryChips[id]?.let { chip ->
                stylePendingCategoryChip(chip, update.first, update.second)
            }
        }
        updateSummaryStatsOnly()
    }

    private fun updateSummaryImportButton() {
        val button = summaryImportButton ?: return
        val items = latestSnapshot?.pendingItems.orEmpty().filterNot { it.splitRequested }
        val readyCount = items.count {
            !it.categoryName.isNullOrBlank() && (it.amountCents ?: 0L) > 0L
        }
        button.text = if (isAiBusy) "自动归类中…" else "一键入账 ($readyCount)"
        button.isEnabled = !isAiBusy
    }

    /** 只刷新统计文字和按钮，不重建 hero、不改变滚动位置。 */
    private fun updateSummaryStatsOnly() {
        val items = latestSnapshot?.pendingItems ?: return
        if (items.isEmpty()) return
        val expenseCents = items
            .filter { it.billType == null || it.billType == LocalBillType.TYPE_EXPENSE }
            .mapNotNull { it.amountCents }
            .sum()
        val incomeCents = items
            .filter { it.billType == LocalBillType.TYPE_INCOME || it.subType == LocalBillType.SUBTYPE_REFUND }
            .mapNotNull { it.amountCents }
            .sum()
        val batchItems = items.filterNot { it.splitRequested }
        val readyCount = batchItems.count {
            !it.categoryName.isNullOrBlank() && (it.amountCents ?: 0L) > 0L
        }
        val missingCount = batchItems.count { it.categoryName.isNullOrBlank() }
        summaryStatsTitleView?.text = "待处理 ${items.size} 笔"
        summaryStatsSubView?.apply {
            text = when {
                batchItems.isEmpty() -> "已勾选拆分 ${items.size} 笔，请逐笔点“拆分入账”"
                missingCount == 0 -> "全部已匹配分类，可直接一键入账"
                else -> "已就绪 $readyCount 笔 · 待分类 $missingCount 笔"
            }
            setTextColor(if (missingCount == 0) CaptureUi.SUCCESS else CaptureUi.TEXT_SECONDARY)
        }
        val showIncomePrimary = expenseCents == 0L && incomeCents > 0L
        summaryAmountView?.apply {
            text = if (showIncomePrimary) "+${formatAmount(incomeCents)}" else "-${formatAmount(expenseCents)}"
            setTextColor(if (showIncomePrimary) CaptureUi.SUCCESS else CaptureUi.TEXT_PRIMARY)
        }
        summaryAmountLabelView?.text = when {
            showIncomePrimary -> "合计收入/退款"
            incomeCents > 0L -> "支出 · 另有收入/退款 +${formatAmount(incomeCents)}"
            else -> "合计支出"
        }
        updateSummaryImportButton()
    }

    private fun updatePendingAsset(itemId: String, assetName: String?, rawChannel: String?) {
        val chip = pendingAssetChips[itemId] ?: return
        val hasAsset = !assetName.isNullOrBlank()
        chip.text = when {
            hasAsset -> "账户 · $assetName"
            !rawChannel.isNullOrBlank() -> "绑定账户 · $rawChannel"
            else -> "选择账户"
        }
        chip.setTextColor(if (hasAsset) CaptureUi.TEXT_SECONDARY else CaptureUi.WARNING)
        chip.background = CaptureUi.rippleBg(
            fillColor = if (hasAsset) CaptureUi.BG_SUBTLE else CaptureUi.WARNING_SOFT,
            radiusPx = dp(8),
            strokeWidthPx = dp(1),
            strokeColor = if (hasAsset) CaptureUi.BORDER else CaptureUi.WARNING_BORDER,
        )
        CaptureUi.applyTrailingIcon(
            chip,
            this,
            R.drawable.ic_chevron_down,
            if (hasAsset) CaptureUi.TEXT_SECONDARY else CaptureUi.WARNING,
            10,
        )
    }

    private fun updatePendingBook(itemId: String, book: String) {
        val chip = pendingBookChips[itemId] ?: return
        chip.text = "账本 · $book"
    }

    private fun updateInboxTabCount(pending: List<PaymentCapture> = latestSnapshot?.pendingItems.orEmpty()) {
        tabViews[0].text = if (pending.isNotEmpty()) "待记 ${pending.size}" else "待记"
    }

    private fun refreshSummaryHeroCard() {
        val snapshot = latestSnapshot ?: return
        val container = inboxContainer ?: return
        if (currentTab != 0) return
        val items = snapshot.pendingItems
        if (items.isEmpty()) {
            val old = summaryHeroCardView
            if (old != null) {
                (old.parent as? ViewGroup)?.removeView(old)
                summaryHeroCardView = null
            }
            if (emptyStateCardView == null) {
                val empty = buildEmptyStateCard()
                emptyStateCardView = empty
                container.addView(empty, 0)
            }
            return
        }
        emptyStateCardView?.let { empty ->
            (empty.parent as? ViewGroup)?.removeView(empty)
            emptyStateCardView = null
        }
        val old = summaryHeroCardView
        val index = old?.let { container.indexOfChild(it) }?.takeIf { it >= 0 } ?: 0
        if (old != null) container.removeView(old)
        val newHero = buildSummaryHeroCard(items)
        summaryHeroCardView = newHero
        container.addView(newHero, index.coerceAtMost(container.childCount))
    }

    /** 入账成功后只移除对应卡片，更新统计和空状态，不触发整页重读。 */
    private suspend fun removePendingCards(ids: Set<String>) {
        if (ids.isEmpty()) return
        val snapshot = latestSnapshot ?: return
        val container = inboxContainer ?: return
        val scroll = (bodyFrame.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        var removedAbove = 0
        ids.forEach { id ->
            val card = pendingCardViews.remove(id) ?: return@forEach
            if (card.top < scroll) removedAbove += card.height
            (card.parent as? ViewGroup)?.removeView(card)
            pendingCategoryChips.remove(id)
            pendingCategorySources.remove(id)
            pendingAssetChips.remove(id)
            pendingBookChips.remove(id)
        }
        val newPending = snapshot.pendingItems.filterNot { it.id in ids }
        latestSnapshot = snapshot.copy(pendingItems = newPending)
        updateInboxTabCount(newPending)
        refreshSummaryHeroCard()
        if (removedAbove > 0) {
            (bodyFrame.getChildAt(0) as? ScrollView)?.scrollTo(0, (scroll - removedAbove).coerceAtLeast(0))
        }
        CaptureNotifier.refreshAll(this)
    }

    /** 待记列表底部的“已不计”折叠区，只做恢复，不改变列表主体结构。 */
    private fun buildIgnoredSection(items: List<PaymentCapture>): View {
        ignoredItemRowViews.clear()
        val card = CaptureUi.card(
            activity = this,
            paddingH = 14,
            paddingV = 12,
            radiusDp = 14,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                toggleIgnoredSection()
            }
        }
        val titleTv = TextView(this).apply {
            text = "已不计 (${items.size})"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ignoredTitleView = titleTv
        header.addView(titleTv)
        header.addView(TextView(this).apply {
            text = if (ignoredExpanded) "收起" else "展开"
            textSize = 12f
            setTextColor(CaptureUi.BRAND_DARK)
            setPadding(dp(8), dp(4), 0, dp(4))
            CaptureUi.applyTrailingIcon(
                this,
                this@PendingListActivity,
                if (ignoredExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                CaptureUi.BRAND_DARK,
                10,
            )
        })
        card.addView(header)
        if (!ignoredExpanded) return card

        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, 0)
            }
            ignoredItemRowViews[item.id] = row
            val amountPrefix = when {
                item.billType == LocalBillType.TYPE_INCOME ||
                    item.subType == LocalBillType.SUBTYPE_REFUND ||
                    item.needsRefundLink -> "+"
                item.billType == LocalBillType.TYPE_TRANSFER -> ""
                else -> "-"
            }
            row.addView(TextView(this).apply {
                text = buildString {
                    append(amountPrefix).append(formatAmount(item.amountCents))
                    item.merchant?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it.take(12)) }
                    append(" · ").append(rowTimeFormat.format(Date(item.paidAt)))
                }
                textSize = 12.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(CaptureUi.TEXT_MUTED)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            })
            row.addView(
                CaptureUi.button(
                    activity = this,
                    text = "恢复",
                    textColor = CaptureUi.BRAND_DARK,
                    bgColor = CaptureUi.BRAND_SOFT,
                    strokeColor = CaptureUi.BRAND_BORDER,
                    radiusDp = 9,
                    paddingH = 12,
                    paddingV = 6,
                    textSizeSp = 12f,
                ) { restoreIgnored(item) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            card.addView(row)
        }
        return card
    }

    private fun toggleIgnoredSection() {
        ignoredExpanded = !ignoredExpanded
        rebuildIgnoredSection()
    }

    private fun rebuildIgnoredSection() {
        val snapshot = latestSnapshot ?: return
        val container = inboxContainer ?: return
        val old = ignoredCardView
        val index = old?.let { container.indexOfChild(it) }?.takeIf { it >= 0 } ?: container.childCount
        if (old != null) container.removeView(old)
        if (snapshot.ignoredItems.isEmpty()) {
            ignoredCardView = null
            ignoredTitleView = null
            return
        }
        val newSection = buildIgnoredSection(snapshot.ignoredItems)
        ignoredCardView = newSection as? ViewGroup
        container.addView(newSection, index.coerceAtMost(container.childCount))
    }

    private fun restoreIgnored(item: PaymentCapture) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CaptureApp.from(this@PendingListActivity).repository
                        .updateStatus(item.id, CaptureStatus.PENDING)
                }

                // 局部更新 UI，坚决不全量 load()，彻底杜绝闪烁
                val snapshot = latestSnapshot
                val restoredItem = item.copy(status = CaptureStatus.PENDING)
                val newPending = if (snapshot != null) snapshot.pendingItems + restoredItem else listOf(restoredItem)
                val newIgnored = snapshot?.ignoredItems?.filterNot { it.id == item.id } ?: emptyList()
                if (snapshot != null) {
                    latestSnapshot = snapshot.copy(
                        pendingItems = newPending,
                        ignoredItems = newIgnored,
                    )
                }

                // 1. 动态更新顶部 Tab 待记数量
                tabViews[0].text = if (newPending.isNotEmpty()) "待记 ${newPending.size}" else "待记"

                // 2. 动态更新已不计折叠区（标题计数 -1，移除条目 View）
                ignoredTitleView?.text = "已不计 (${newIgnored.size})"
                val rowView = ignoredItemRowViews.remove(item.id)
                if (rowView != null) {
                    rowView.animate()
                        .alpha(0f)
                        .setDuration(180)
                        .withEndAction {
                            (rowView.parent as? ViewGroup)?.removeView(rowView)
                            if (newIgnored.isEmpty()) {
                                ignoredCardView?.let { ic ->
                                    (ic.parent as? ViewGroup)?.removeView(ic)
                                    ignoredCardView = null
                                    ignoredTitleView = null
                                }
                            }
                        }
                        .start()
                } else if (newIgnored.isEmpty()) {
                    ignoredCardView?.let { ic ->
                        (ic.parent as? ViewGroup)?.removeView(ic)
                        ignoredCardView = null
                        ignoredTitleView = null
                    }
                }

                // 3. 动态更新上方待记列表与 Summary 统计卡片
                val container = inboxContainer
                if (container != null && currentTab == 0) {
                    val emptyView = emptyStateCardView
                    if (emptyView != null && emptyView.parent == container) {
                        // 若原本为空待记提示视图，则替换为待记列表与 Summary
                        val emptyIdx = container.indexOfChild(emptyView)
                        container.removeView(emptyView)
                        emptyStateCardView = null

                        val hero = buildSummaryHeroCard(newPending)
                        summaryHeroCardView = hero
                        container.addView(hero, emptyIdx)

                        val newCard = buildCaptureCard(restoredItem, latestSnapshot?.accountingContext)
                        pendingCardViews[restoredItem.id] = newCard
                        container.addView(newCard, emptyIdx + 1)
                    } else {
                        // 替换 Summary 卡片以更新金额、笔数和各状态统计
                        val oldSummary = summaryHeroCardView
                        if (oldSummary != null && oldSummary.parent == container) {
                            val summaryIdx = container.indexOfChild(oldSummary)
                            container.removeViewAt(summaryIdx)
                            val newSummary = buildSummaryHeroCard(newPending)
                            summaryHeroCardView = newSummary
                            container.addView(newSummary, summaryIdx)
                        }

                        // 将新恢复的 item View 追加/插入到待记列表末尾（即已不计卡片之前）
                        val newCard = buildCaptureCard(restoredItem, latestSnapshot?.accountingContext)
                        pendingCardViews[restoredItem.id] = newCard
                        val insertIdx = if (ignoredCardView != null && ignoredCardView?.parent == container) {
                            container.indexOfChild(ignoredCardView)
                        } else {
                            container.childCount
                        }
                        newCard.alpha = 0f
                        container.addView(newCard, insertIdx)
                        newCard.animate().alpha(1f).setDuration(200).start()
                    }
                }

                toast("已恢复到待记")
                CaptureNotifier.refreshAll(this@PendingListActivity)
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                toast("恢复失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun buildSummaryHeroCard(items: List<PaymentCapture>): View {
        val expenseCents = items
            .filter { it.billType == null || it.billType == LocalBillType.TYPE_EXPENSE }
            .mapNotNull { it.amountCents }
            .sum()
        val incomeCents = items
            .filter { it.billType == LocalBillType.TYPE_INCOME || it.subType == LocalBillType.SUBTYPE_REFUND }
            .mapNotNull { it.amountCents }
            .sum()
        val batchItems = items.filterNot { it.splitRequested }
        val readyItems = batchItems.filter { !it.categoryName.isNullOrBlank() && (it.amountCents ?: 0L) > 0L }
        val missingCategoryItems = batchItems.filter { it.needsCategory() }
        val unmappedCount = batchItems.count { it.categoryName.isNullOrBlank() }

        val card = CaptureUi.card(
            activity = this,
            paddingH = 16,
            paddingV = 15,
            radiusDp = 16,
            fillColor = CaptureUi.BG_SURFACE,
            strokeColor = CaptureUi.BRAND_BORDER,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

        // 指标行：左侧笔数与状态，右侧大号合计金额
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val statsTitle = TextView(this).apply {
            text = "待处理 ${items.size} 笔"
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        }
        summaryStatsTitleView = statsTitle
        leftCol.addView(statsTitle)
        val statsSub = TextView(this).apply {
            text = when {
                batchItems.isEmpty() && items.isNotEmpty() ->
                    "已勾选拆分 ${items.size} 笔，请逐笔点“拆分入账”"
                unmappedCount == 0 -> "全部已匹配分类，可直接一键入账"
                else -> "已就绪 ${readyItems.size} 笔 · 待分类 $unmappedCount 笔"
            }
            textSize = 12f
            setTextColor(if (unmappedCount == 0) CaptureUi.SUCCESS else CaptureUi.TEXT_SECONDARY)
            setPadding(0, dp(2), 0, 0)
        }
        summaryStatsSubView = statsSub
        leftCol.addView(statsSub)
        statsRow.addView(leftCol)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val showIncomePrimary = expenseCents == 0L && incomeCents > 0L
        val amountView = TextView(this).apply {
            text = if (showIncomePrimary) "+${formatAmount(incomeCents)}" else "-${formatAmount(expenseCents)}"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (showIncomePrimary) CaptureUi.SUCCESS else CaptureUi.TEXT_PRIMARY)
        }
        summaryAmountView = amountView
        rightCol.addView(amountView)
        val amountLabel = TextView(this).apply {
            text = when {
                showIncomePrimary -> "合计收入/退款"
                incomeCents > 0L -> "支出 · 另有收入/退款 +${formatAmount(incomeCents)}"
                else -> "合计支出"
            }
            textSize = 11f
            setTextColor(CaptureUi.TEXT_MUTED)
        }
        summaryAmountLabelView = amountLabel
        rightCol.addView(amountLabel)
        statsRow.addView(rightCol)
        card.addView(statsRow)

        // 批量动作行：只保留一键入账；缺分类在入账流程里自动兜底。
        val batchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }

        val canImportAll = readyItems.isNotEmpty() || missingCategoryItems.isNotEmpty()
        val importAllBtn = CaptureUi.button(
            activity = this,
            text = if (isAiBusy) "自动归类中…" else "一键入账 (${readyItems.size})",
            textColor = if (canImportAll) Color.WHITE else CaptureUi.TEXT_MUTED,
            bgColor = if (canImportAll) CaptureUi.BRAND else CaptureUi.BG_SEGMENT,
            onClick = {
                if (isAiBusy) return@button
                when {
                    canImportAll -> confirmAndBatchImport(readyItems, missingCategoryItems)
                    items.any { it.splitRequested } ->
                        toast("勾选拆分的账单请逐笔点“拆分入账”")
                    else -> toast("请先为账单选择分类")
                }
            },
        )
        summaryImportButton = importAllBtn
        batchRow.addView(
            importAllBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(batchRow)

        return card
    }

    private fun buildEmptyStateCard(): View {
        val emptyCard = CaptureUi.card(
            activity = this,
            paddingH = 24,
            paddingV = 36,
            radiusDp = 16,
        ).apply {
            gravity = Gravity.CENTER
        }
        val badge = FrameLayout(this).apply {
            background = CaptureUi.roundedBg(
                CaptureUi.SUCCESS_SOFT,
                dp(999),
                dp(1),
                CaptureUi.SUCCESS_BORDER,
            )
        }
        emptyCard.addView(
            badge,
            LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            },
        )
        badge.addView(
            CaptureUi.icon(this, R.drawable.ic_check, 24, CaptureUi.SUCCESS),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
        )
        emptyCard.addView(TextView(this).apply {
            text = "待记账单已全部清空"
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        emptyCard.addView(TextView(this).apply {
            text = "${CaptureSources.all.joinToString("、") { it.displayName }}付款时会自动留底到这里；敲击手机背面即可在记账悬浮窗里顺手分类入账。"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(8), dp(6), dp(8), dp(18))
        })
        emptyCard.addView(
            CaptureUi.button(
                activity = this,
                text = "+ 模拟一笔示例支出",
                textColor = CaptureUi.BRAND_DARK,
                bgColor = CaptureUi.BRAND_SOFT,
                strokeColor = CaptureUi.BRAND_BORDER,
                paddingH = 18,
                paddingV = 10,
            ) { simulateCapture() }.apply {
                setOnLongClickListener {
                    showCustomSimulateDialog()
                    true
                }
            }
        )
        return emptyCard
    }

    private fun buildCaptureCard(item: PaymentCapture, ctx: AccountingContext?): View {
        val card = CaptureUi.card(
            activity = this,
            paddingH = 15,
            paddingV = 13,
            radiusDp = 15,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        // 第一行：来源徽章 + 商户名 + 时间 + 金额
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val isWechat = item.source == CaptureSource.WECHAT
        topRow.addView(TextView(this).apply {
            text = sourceLabel(item.source)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isWechat) CaptureUi.WECHAT else CaptureUi.ALIPAY)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = CaptureUi.roundedBg(
                fillColor = if (isWechat) CaptureUi.WECHAT_SOFT else CaptureUi.ALIPAY_SOFT,
                radiusPx = dp(6),
            )
        })

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }
        titleCol.addView(TextView(this).apply {
            text = item.merchant?.takeIf { it.isNotBlank() } ?: "未知商户"
            textSize = 14.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        titleCol.addView(TextView(this).apply {
            text = buildString {
                append(rowTimeFormat.format(Date(item.paidAt)))
                item.paymentMethodRaw?.takeIf { it.isNotBlank() }?.let { raw ->
                    append(" · ").append(raw)
                }
            }
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_MUTED)
        })
        topRow.addView(titleCol)

        val isRefund = item.subType == LocalBillType.SUBTYPE_REFUND || item.needsRefundLink
        val isIncome = item.billType == LocalBillType.TYPE_INCOME
        val isTransfer = item.billType == LocalBillType.TYPE_TRANSFER
        when {
            isRefund -> topRow.addView(TextView(this).apply {
                text = "退款"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.SUCCESS)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = CaptureUi.roundedBg(CaptureUi.SUCCESS_SOFT, dp(6), dp(1), CaptureUi.SUCCESS_BORDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            })
            isIncome -> topRow.addView(TextView(this).apply {
                text = "收入"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.SUCCESS)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = CaptureUi.roundedBg(CaptureUi.SUCCESS_SOFT, dp(6), dp(1), CaptureUi.SUCCESS_BORDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            })
            isTransfer -> topRow.addView(TextView(this).apply {
                text = "转账"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.BRAND_DARK)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = CaptureUi.roundedBg(CaptureUi.BRAND_SOFT, dp(6), dp(1), CaptureUi.BRAND_BORDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            })
        }

        if (item.status == CaptureStatus.POSSIBLE_DUPLICATE) {
            topRow.addView(TextView(this).apply {
                text = "可能已记"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.WARNING)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = CaptureUi.roundedBg(CaptureUi.WARNING_SOFT, dp(6), dp(1), CaptureUi.WARNING_BORDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            })
        }

        if (item.excludeFromStats) {
            topRow.addView(TextView(this).apply {
                text = "不计统计"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#B45309"))
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = CaptureUi.roundedBg(Color.parseColor("#FEF3C7"), dp(6), dp(1), Color.parseColor("#FDE68A"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            })
        }

        val isMerged = item.channel?.contains("+") == true
        if (isMerged) {
            topRow.addView(TextView(this).apply {
                text = "多渠道合并"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.BRAND)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = CaptureUi.roundedBg(CaptureUi.BRAND_SOFT, dp(6), dp(1), CaptureUi.BRAND_BORDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    showMergedDetailsDialog(item)
                }
            })
        }

        val amountPrefix = when {
            isIncome || isRefund -> "+"
            isTransfer -> ""
            else -> "-"
        }
        topRow.addView(TextView(this).apply {
            text = "$amountPrefix${formatAmount(item.amountCents)}"
            textSize = 17.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isIncome || isRefund) CaptureUi.SUCCESS else CaptureUi.TEXT_PRIMARY)
        })
        card.addView(topRow)

        // 第二行：【账户 Chip】+【分类 Chip】
        val chipRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }

        val mappedAsset = item.assetName
        val rawChannel = item.paymentMethodRaw
        val hasAsset = !mappedAsset.isNullOrBlank()
        val assetText = when {
            hasAsset -> "账户 · $mappedAsset"
            !rawChannel.isNullOrBlank() -> "绑定账户 · $rawChannel"
            else -> "选择账户"
        }
        val assetChip = CaptureUi.chip(
            activity = this,
            text = assetText,
            textColor = if (hasAsset) CaptureUi.TEXT_SECONDARY else CaptureUi.WARNING,
            bgColor = if (hasAsset) CaptureUi.BG_SUBTLE else CaptureUi.WARNING_SOFT,
            strokeColor = if (hasAsset) CaptureUi.BORDER else CaptureUi.WARNING_BORDER,
            trailingIconRes = R.drawable.ic_chevron_down,
            trailingIconTint = if (hasAsset) CaptureUi.TEXT_SECONDARY else CaptureUi.WARNING,
        ) {
            showAssetPicker(item, ctx)
        }
        pendingAssetChips[item.id] = assetChip
        chipRow1.addView(
            assetChip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            },
        )

        var currentCategory = item.categoryName
        var currentCategorySource = item.categorySource
        fun styleCategoryChip(tv: TextView, cat: String?, source: String? = currentCategorySource) {
            stylePendingCategoryChip(tv, cat, source)
        }
        val categoryChip = CaptureUi.chip(
            activity = this,
            text = "",
            textColor = CaptureUi.SUCCESS,
            bgColor = CaptureUi.SUCCESS_SOFT,
            trailingIconRes = R.drawable.ic_chevron_down,
            trailingIconTint = CaptureUi.SUCCESS,
        ) {
            showCategoryPicker(item, ctx)
        }
        styleCategoryChip(categoryChip, currentCategory, currentCategorySource)
        categoryChip.visibility = if (item.splitRequested) View.GONE else View.VISIBLE
        pendingCategoryChips[item.id] = categoryChip
        pendingCategorySources[item.id] = currentCategorySource
        chipRow1.addView(categoryChip)
        card.addView(chipRow1)

        // 第三行：【账本 Chip】+【不计入统计 Chip】
        val chipRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }

        var currentBook = item.bookName?.takeIf { it.isNotBlank() }
            ?: ctx?.currentBookName?.takeIf { it.isNotBlank() }
            ?: ctx?.defaultBookName?.takeIf { it.isNotBlank() }
            ?: "日常账本"
        fun styleBookChip(tv: TextView, book: String) {
            tv.text = "账本 · $book"
            tv.setTextColor(CaptureUi.TEXT_SECONDARY)
            tv.background = CaptureUi.rippleBg(
                fillColor = CaptureUi.BG_SUBTLE,
                radiusPx = dp(8),
                strokeWidthPx = dp(1),
                strokeColor = CaptureUi.BORDER,
            )
            CaptureUi.applyTrailingIcon(
                tv,
                this@PendingListActivity,
                R.drawable.ic_chevron_down,
                CaptureUi.TEXT_SECONDARY,
                10,
            )
        }
        lateinit var bookChip: TextView
        bookChip = CaptureUi.chip(
            activity = this,
            text = "",
            textColor = CaptureUi.TEXT_SECONDARY,
            bgColor = CaptureUi.BG_SUBTLE,
        ) {
            showBookPicker(item, ctx) { selectedBook ->
                currentBook = selectedBook
                styleBookChip(bookChip, selectedBook)
            }
        }
        styleBookChip(bookChip, currentBook)
        pendingBookChips[item.id] = bookChip
        chipRow2.addView(
            bookChip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            },
        )

        var currentExcludeStats = item.excludeFromStats
        val excludeStatsChip = TextView(this).apply {
            textSize = 12f
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        fun styleExcludeStatsChip(tv: TextView, excluded: Boolean) {
            if (excluded) {
                tv.text = "🚫 不计统计"
                tv.setTextColor(Color.parseColor("#B45309"))
                tv.background = CaptureUi.rippleBg(
                    fillColor = Color.parseColor("#FEF3C7"),
                    radiusPx = dp(8),
                    strokeWidthPx = dp(1),
                    strokeColor = Color.parseColor("#FDE68A"),
                )
            } else {
                tv.text = "不计统计"
                tv.setTextColor(CaptureUi.TEXT_MUTED)
                tv.background = CaptureUi.rippleBg(
                    fillColor = CaptureUi.BG_SUBTLE,
                    radiusPx = dp(8),
                    strokeWidthPx = dp(1),
                    strokeColor = CaptureUi.BORDER,
                )
            }
        }
        styleExcludeStatsChip(excludeStatsChip, currentExcludeStats)
        excludeStatsChip.setOnClickListener {
            currentExcludeStats = !currentExcludeStats
            styleExcludeStatsChip(excludeStatsChip, currentExcludeStats)
            scope.launch {
                withContext(Dispatchers.IO) {
                    CaptureApp.from(this@PendingListActivity).repository
                        .updateExcludeFromStats(item.id, currentExcludeStats)
                }
            }
            if (currentExcludeStats) {
                toast("已设为「不计入统计」")
            } else {
                toast("已恢复为计入统计")
            }
        }
        chipRow2.addView(
            excludeStatsChip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        card.addView(chipRow2)

        if (!item.ruleName.isNullOrBlank()) {
            card.addView(TextView(this).apply {
                text = "命中规则：${item.ruleName}"
                textSize = 10.5f
                setTextColor(CaptureUi.TEXT_MUTED)
                setPadding(0, dp(2), 0, dp(6))
            })
        }

        // 第三行：无感自动保存备注框（打字 280ms 只落库并匹配本地规则；AI 只在失焦/完成时调一次）
        var aiRequestedFor: String? = null
        val noteInput = EditText(this).apply {
            setText(item.note.orEmpty())
            hint = "写备注，命中关键词会自动归类"
            textSize = 13f
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setHintTextColor(CaptureUi.TEXT_MUTED)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(9), dp(1), CaptureUi.BORDER)
        }
        noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val newNote = s?.toString()?.trim().orEmpty()
                aiRequestedFor = null
                noteSaveJobs[item.id]?.cancel()
                noteSaveJobs[item.id] = scope.launch {
                    delay(280L)
                    val updated = withContext(Dispatchers.IO) {
                        CaptureApp.from(this@PendingListActivity)
                            .repository
                            .updateNoteAndReclassify(this@PendingListActivity, item.id, newNote)
                    }
                    if (updated != null &&
                        (updated.categoryName != currentCategory ||
                            updated.categorySource != currentCategorySource)
                    ) {
                        currentCategory = updated.categoryName
                        currentCategorySource = updated.categorySource
                        pendingCategorySources[item.id] = currentCategorySource
                        styleCategoryChip(categoryChip, currentCategory, currentCategorySource)
                        updateSummaryStatsOnly()
                    }
                }
            }
        })

        fun triggerAiClassify() {
            val note = noteInput.text?.toString()?.trim().orEmpty()
            if (note.isEmpty()) return
            if (!currentCategory.isNullOrBlank()) {
                scope.launch {
                    val matched = withContext(Dispatchers.IO) {
                        CaptureRuleMatcher.resolveNoteCategory(this@PendingListActivity, note)
                    }
                    if (matched != null && matched != currentCategory) {
                        AlertDialog.Builder(this@PendingListActivity)
                            .setTitle("发现匹配分类规则")
                            .setMessage("备注「$note」命中分类规则「$matched」，是否替换当前分类「$currentCategory」？")
                            .setPositiveButton("替换为 $matched") { _, _ ->
                                scope.launch {
                                    val updated = withContext(Dispatchers.IO) {
                                        CaptureApp.from(this@PendingListActivity).repository.updateDraft(
                                            context = this@PendingListActivity,
                                            id = item.id,
                                            note = note,
                                            assetName = item.assetName,
                                            categoryName = matched,
                                            categorySource = CategorySource.RULE,
                                        )
                                    }
                                    if (updated != null) {
                                        currentCategory = matched
                                        currentCategorySource = CategorySource.RULE
                                        pendingCategorySources[item.id] = currentCategorySource
                                        styleCategoryChip(categoryChip, currentCategory, currentCategorySource)
                                        updateSummaryStatsOnly()
                                    }
                                }
                            }
                            .setNegativeButton("保留原分类", null)
                            .show()
                    }
                }
                return
            }
            if (aiRequestedFor == note) return
            aiRequestedFor = note
            scope.launch {
                // 先等 280ms 防抖落库，确保 AI 看到的是这条备注，且本地规则已先跑过。
                noteSaveJobs[item.id]?.join()
                val updated = withContext(Dispatchers.IO) {
                    val repository = CaptureApp.from(this@PendingListActivity).repository
                    CaptureNoteClassifier.classifyAndApply(
                        context = this@PendingListActivity,
                        client = bridge,
                        repository = repository,
                        captureId = item.id,
                    )
                }
                if (updated != null &&
                    (updated.categoryName != currentCategory ||
                        updated.categorySource != currentCategorySource)
                ) {
                    currentCategory = updated.categoryName
                    currentCategorySource = updated.categorySource
                    pendingCategorySources[item.id] = currentCategorySource
                    styleCategoryChip(categoryChip, currentCategory, currentCategorySource)
                    updateSummaryStatsOnly()
                }
            }
        }

        noteInput.setOnFocusChangeListener { _, hasFocus ->
            noteInput.background = CaptureUi.roundedBg(
                if (hasFocus) Color.WHITE else CaptureUi.BG_SUBTLE,
                dp(9),
                dp(1),
                if (hasFocus) CaptureUi.BORDER_FOCUS else CaptureUi.BORDER,
            )
            if (!hasFocus) triggerAiClassify()
        }
        noteInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                triggerAiClassify()
                noteInput.clearFocus()
                true
            } else {
                false
            }
        }
        card.addView(noteInput)

        // 备注框下的“拆分记账”工作区
        val splitSubItems = splitDrafts.getOrPut(item.id) { mutableListOf() }
        var splitRequested = item.splitRequested || splitSubItems.isNotEmpty()
        val splitBox = CheckBox(this).apply {
            text = "拆分记账（按多条明细分别归类入账）"
            textSize = 12f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            isChecked = splitRequested
            setPadding(dp(2), dp(6), 0, 0)
        }
        card.addView(splitBox)

        val splitWorkArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (splitRequested) View.VISIBLE else View.GONE
            setPadding(0, dp(6), 0, dp(4))
        }

        val splitActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        splitWorkArea.addView(splitActionsRow)

        val subItemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        splitWorkArea.addView(subItemsContainer)

        val splitSummaryText = TextView(this).apply {
            textSize = 12f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(4))
        }
        splitWorkArea.addView(splitSummaryText)

        var primaryButton: TextView? = null
        var btnAddAdjustment: View? = null
        var btnQuickRecord: View? = null

        fun renderSubItems() {
            subItemsContainer.removeAllViews()
            val total = item.amountCents ?: 0L
            val currentSum = splitSubItems.sumOf { it.amountCents }
            val diff = total - currentSum

            splitSubItems.forEachIndexed { index, subItem ->
                val row = LinearLayout(this@PendingListActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                val etName = EditText(this@PendingListActivity).apply {
                    setText(subItem.name)
                    hint = "明细名称"
                    textSize = 12.5f
                    setTextColor(CaptureUi.TEXT_PRIMARY)
                    setHintTextColor(CaptureUi.TEXT_MUTED)
                    isSingleLine = true
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(6), dp(1), CaptureUi.BORDER)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            val newName = s?.toString()?.trim().orEmpty()
                            if (index < splitSubItems.size) {
                                splitSubItems[index] = splitSubItems[index].copy(name = newName)
                            }
                        }
                    })
                }
                row.addView(etName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { marginEnd = dp(6) })

                val etAmount = EditText(this@PendingListActivity).apply {
                    setText(String.format(Locale.CHINA, "%.2f", subItem.amountCents / 100.0))
                    hint = "金额"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    textSize = 12.5f
                    setTextColor(CaptureUi.TEXT_PRIMARY)
                    setHintTextColor(CaptureUi.TEXT_MUTED)
                    isSingleLine = true
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(6), dp(1), CaptureUi.BORDER)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            val cents = (s?.toString()?.toDoubleOrNull()?.times(100.0))?.toLong() ?: 0L
                            if (index < splitSubItems.size) {
                                splitSubItems[index] = splitSubItems[index].copy(amountCents = cents)
                                val nowSum = splitSubItems.sumOf { it.amountCents }
                                val nowDiff = total - nowSum
                                splitSummaryText.text = "已拆合计 ¥${String.format(Locale.CHINA, "%.2f", nowSum / 100.0)} / 应付 ¥${String.format(Locale.CHINA, "%.2f", total / 100.0)}${if (nowDiff != 0L) " (差额: ¥${String.format(Locale.CHINA, "%.2f", nowDiff / 100.0)})" else " (已配平)"}"
                                btnAddAdjustment?.visibility = if (nowDiff > 0L) View.VISIBLE else View.GONE
                            }
                        }
                    })
                }
                row.addView(etAmount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })

                val subCatOk = !subItem.categoryName.isNullOrBlank()
                val subCatChip = CaptureUi.chip(
                    activity = this@PendingListActivity,
                    text = subItem.categoryName?.takeIf { it.isNotBlank() } ?: "选分类",
                    textColor = if (!subItem.categoryName.isNullOrBlank()) CaptureUi.SUCCESS else CaptureUi.WARNING,
                    bgColor = if (!subItem.categoryName.isNullOrBlank()) CaptureUi.SUCCESS_SOFT else CaptureUi.WARNING_SOFT,
                    trailingIconRes = R.drawable.ic_chevron_down,
                    trailingIconTint = if (subCatOk) CaptureUi.SUCCESS else CaptureUi.WARNING,
                ) {
                    showCategoryPicker(
                        item = item.copy(categoryName = subItem.categoryName),
                        ctx = ctx,
                        title = "选择「${subItem.name}」的分类",
                    ) { chosenCat ->
                        if (!chosenCat.isNullOrBlank() && index < splitSubItems.size) {
                            splitSubItems[index] = splitSubItems[index].copy(categoryName = chosenCat)
                            renderSubItems()
                        }
                    }
                }
                row.addView(subCatChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })

                val btnDel = CaptureUi.iconButton(
                    context = this@PendingListActivity,
                    res = R.drawable.ic_close,
                    tint = CaptureUi.TEXT_MUTED,
                    sizeDp = 30,
                    iconDp = 14,
                    contentDescription = "删除这条明细",
                ) {
                    if (index < splitSubItems.size) {
                        splitSubItems.removeAt(index)
                        renderSubItems()
                    }
                }
                row.addView(btnDel)
                subItemsContainer.addView(row)
            }

            splitSummaryText.text = if (splitSubItems.isEmpty()) {
                "尚未拆分明细，点上方「📝 按备注 AI 拆单」或「+ 手动加一行」"
            } else {
                "已拆合计 ¥${String.format(Locale.CHINA, "%.2f", currentSum / 100.0)} / 应付 ¥${String.format(Locale.CHINA, "%.2f", total / 100.0)}${if (diff != 0L) " (差额: ¥${String.format(Locale.CHINA, "%.2f", diff / 100.0)})" else " (已配平)"}"
            }
            btnAddAdjustment?.visibility = if (diff > 0L && splitSubItems.isNotEmpty()) View.VISIBLE else View.GONE
            primaryButton?.let { btn ->
                if (splitRequested) {
                    if (splitSubItems.isEmpty()) {
                        btn.text = "拆分入账 (0)"
                        btn.isEnabled = false
                        btn.setTextColor(CaptureUi.TEXT_MUTED)
                        btn.background = CaptureUi.rippleBg(
                            fillColor = CaptureUi.BG_SUBTLE,
                            radiusPx = dp(10),
                            strokeWidthPx = dp(1),
                            strokeColor = CaptureUi.BORDER,
                        )
                    } else {
                        btn.isEnabled = true
                        btn.text = "确认拆分入账 (${splitSubItems.size}笔)"
                        btn.setTextColor(Color.WHITE)
                        btn.background = CaptureUi.rippleBg(
                            fillColor = CaptureUi.BRAND,
                            radiusPx = dp(10),
                            strokeWidthPx = 0,
                        )
                    }
                } else {
                    btn.isEnabled = true
                    btn.text = "直接入账"
                    btn.setTextColor(Color.WHITE)
                    btn.background = CaptureUi.rippleBg(
                        fillColor = CaptureUi.BRAND,
                        radiusPx = dp(10),
                        strokeWidthPx = 0,
                    )
                }
            }
        }

        btnAddAdjustment = CaptureUi.button(
            activity = this,
            text = "+ 补差额调整项",
            textColor = CaptureUi.BRAND_DARK,
            bgColor = CaptureUi.BRAND_SOFT,
            strokeColor = CaptureUi.BRAND_BORDER,
            paddingV = 6,
            textSizeSp = 12f,
        ) {
            val total = item.amountCents ?: 0L
            val currentSum = splitSubItems.sumOf { it.amountCents }
            val diff = total - currentSum
            if (diff > 0L) {
                splitSubItems.add(
                    CaptureBridgeClient.SplitItem(
                        name = "差额调整",
                        amountCents = diff,
                        categoryName = "其他",
                        inferred = true,
                    )
                )
                renderSubItems()
            }
        }.apply { visibility = View.GONE }
        splitWorkArea.addView(btnAddAdjustment)
        card.addView(splitWorkArea)

        lateinit var btnAiSplit: TextView

        fun runAiSplit() {
            val draftNote = noteInput.text?.toString()?.trim().orEmpty()
            if (draftNote.isBlank()) {
                toast("请先在备注框输入如「苹果30 雪梨40」")
                noteInput.requestFocus()
                return
            }
            if (!splitBox.isChecked) {
                splitBox.isChecked = true
            }
            splitRequested = true
            categoryChip.visibility = View.GONE
            btnQuickRecord?.visibility = View.GONE
            splitWorkArea.visibility = View.VISIBLE

            scope.launch {
                btnAiSplit.isEnabled = false
                btnAiSplit.text = "⏳ 拆分中…"
                splitSummaryText.text = "AI 正在分析备注并拆分条目，请稍候…"
                try {
                    val preview = bridge.previewSplit(
                        captureId = item.id,
                        note = draftNote,
                        totalCents = item.amountCents ?: 0L,
                        billType = item.billType ?: LocalBillType.TYPE_EXPENSE,
                    )
                    if (!splitRequested) return@launch
                    if (preview.ok) {
                        splitSubItems.clear()
                        val newItems = preview.items
                        if (newItems.isEmpty()) {
                            toast("未能从备注中识别出明细条目")
                            renderSubItems()
                        } else {
                            for (sub in newItems) {
                                splitSubItems.add(sub)
                                renderSubItems()
                                delay(120L)
                            }
                            toast("已拆分为 ${newItems.size} 笔明细")
                        }
                    } else {
                        showSplitError(preview.error, preview.message)
                        renderSubItems()
                    }
                } finally {
                    btnAiSplit.isEnabled = true
                    btnAiSplit.text = "📝 按备注 AI 拆单"
                }
            }
        }

        btnAiSplit = CaptureUi.chip(
            activity = this,
            text = "📝 按备注 AI 拆单",
            textColor = CaptureUi.BRAND_DARK,
            bgColor = CaptureUi.BRAND_SOFT,
            strokeColor = CaptureUi.BRAND_BORDER,
        ) { runAiSplit() }
        splitActionsRow.addView(
            btnAiSplit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) },
        )

        lateinit var btnImageSplit: TextView
        btnImageSplit = CaptureUi.chip(
            activity = this,
            text = "📷 识图拆单",
            textColor = CaptureUi.BRAND_DARK,
            bgColor = CaptureUi.BRAND_SOFT,
            strokeColor = CaptureUi.BRAND_BORDER,
        ) {
            val amountCents = item.amountCents ?: 0L
            val billType = item.billType ?: LocalBillType.TYPE_EXPENSE
            if (amountCents <= 0L) {
                toast("金额缺失，无法拆单")
                return@chip
            }
            if (billType != LocalBillType.TYPE_EXPENSE && billType != LocalBillType.TYPE_INCOME) {
                toast("图片拆单只支持普通支出/收入")
                return@chip
            }

            if (!splitBox.isChecked) {
                splitBox.isChecked = true
            }
            splitRequested = true
            categoryChip.visibility = View.GONE
            btnQuickRecord?.visibility = View.GONE
            splitWorkArea.visibility = View.VISIBLE

            skipNextResumeLoad = true
            ImagePickerActivity.launch(
                context = this@PendingListActivity,
                maxItems = 5,
                onPicked = { uris ->
                    scope.launch {
                        btnImageSplit.isEnabled = false
                        btnImageSplit.text = "⏳ 识图中…"
                        splitSummaryText.text = "AI 正在识别小票并拆分条目，请稍候…"
                        try {
                            val preview = bridge.previewImageSplitMulti(
                                captureId = item.id,
                                totalCents = amountCents,
                                billType = billType,
                                imageUris = uris,
                            )
                            if (preview.ok) {
                                splitSubItems.clear()
                                val newItems = preview.items
                                if (newItems.isEmpty()) {
                                    toast("未能从图片识别出明细条目")
                                    renderSubItems()
                                } else {
                                    for (sub in newItems) {
                                        splitSubItems.add(sub)
                                        renderSubItems()
                                        delay(120L)
                                    }
                                    toast("已拆分为 ${newItems.size} 笔明细")
                                }
                            } else {
                                showSplitError(preview.error, preview.message)
                                renderSubItems()
                            }
                        } finally {
                            btnImageSplit.isEnabled = true
                            btnImageSplit.text = "📷 识图拆单"
                        }
                    }
                },
                onCancelled = {
                    toast("已取消选图，待记仍保留")
                },
            )
        }
        splitActionsRow.addView(
            btnImageSplit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) },
        )
        fun addEmptySplitRow() {
            val currentSum = splitSubItems.sumOf { it.amountCents }
            val total = item.amountCents ?: 0L
            val rem = (total - currentSum).coerceAtLeast(0L)
            splitSubItems.add(
                CaptureBridgeClient.SplitItem(
                    name = "明细 ${splitSubItems.size + 1}",
                    amountCents = if (rem > 0L) rem else 100L,
                    categoryName = item.categoryName,
                )
            )
            renderSubItems()
        }

        splitActionsRow.addView(
            CaptureUi.chip(
                activity = this,
                text = "+ 加一行",
                textColor = CaptureUi.TEXT_SECONDARY,
                bgColor = CaptureUi.BG_SUBTLE,
                strokeColor = CaptureUi.BORDER,
            ) { addEmptySplitRow() },
        )

        // 第四行：操作栏（直接入账 / 拆分入账 / 悬浮窗改 / 不计）
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val isInitialEmptySplit = splitRequested && splitSubItems.isEmpty()
        val btnPrimary = CaptureUi.button(
            activity = this,
            text = if (splitRequested) {
                if (splitSubItems.isEmpty()) "拆分入账 (0)" else "确认拆分入账 (${splitSubItems.size}笔)"
            } else {
                "直接入账"
            },
            textColor = if (isInitialEmptySplit) CaptureUi.TEXT_MUTED else Color.WHITE,
            bgColor = if (isInitialEmptySplit) CaptureUi.BG_SUBTLE else CaptureUi.BRAND,
            strokeColor = if (isInitialEmptySplit) CaptureUi.BORDER else Color.TRANSPARENT,
            radiusDp = 10,
            paddingV = 8,
            textSizeSp = 13f,
        ) {
            val draftNote = noteInput.text?.toString()?.trim().orEmpty()
            val draft = item.copy(
                note = draftNote.ifBlank { item.note },
                categoryName = currentCategory ?: item.categoryName,
                splitRequested = splitRequested,
                // 账本 chip 是本地选择，可能还没等到 load() 刷新 Snapshot；入账必须用当前选择。
                bookName = currentBook,
            )
            if (splitRequested) {
                if (splitSubItems.isEmpty()) {
                    toast("尚未拆分明细，请先点上方「📝 按备注 AI 拆单」或「+」添加明细")
                    if (draftNote.isBlank()) {
                        noteInput.requestFocus()
                    }
                    return@button
                } else {
                    val total = item.amountCents ?: 0L
                    val currentSum = splitSubItems.sumOf { it.amountCents }
                    val diff = total - currentSum
                    if (diff < 0L) {
                        toast("拆分明细合计超出付款金额 ¥${String.format(Locale.CHINA, "%.2f", -diff / 100.0)}，请检查")
                        return@button
                    }
                    if (diff > 0L) {
                        AlertDialog.Builder(this@PendingListActivity)
                            .setTitle("拆分金额未配平")
                            .setMessage("明细合计还差 ¥${String.format(Locale.CHINA, "%.2f", diff / 100.0)}，是否自动补齐一条差额调整项后入账？")
                            .setPositiveButton("补齐并入账") { _, _ ->
                                splitSubItems.add(
                                    CaptureBridgeClient.SplitItem(
                                        name = "差额调整",
                                        amountCents = diff,
                                        categoryName = "其他",
                                        inferred = true,
                                    )
                                )
                                scope.launch { performGroupImport(draft, splitSubItems) }
                            }
                            .setNegativeButton("继续修改", null)
                            .show()
                        return@button
                    }
                    scope.launch { performGroupImport(draft, splitSubItems) }
                }
            } else {
                directImport(draft)
            }
        }
        primaryButton = btnPrimary
        btnPrimary.isEnabled = !isInitialEmptySplit

        btnQuickRecord = CaptureUi.button(
            activity = this,
            text = "悬浮窗细改",
            textColor = CaptureUi.TEXT_SECONDARY,
            bgColor = CaptureUi.BG_SUBTLE,
            strokeColor = CaptureUi.BORDER,
            radiusDp = 10,
            paddingV = 8,
            textSizeSp = 13f,
        ) {
            val draftNote = noteInput.text?.toString()?.trim().orEmpty()
            openQuickRecord(
                item.copy(
                    note = draftNote.ifBlank { item.note },
                    categoryName = currentCategory ?: item.categoryName,
                    bookName = currentBook,
                )
            )
        }.apply {
            visibility = if (splitRequested) View.GONE else View.VISIBLE
        }

        splitBox.setOnCheckedChangeListener { _, checked ->
            splitRequested = checked
            if (!checked) {
                splitDrafts.remove(item.id)
                splitSubItems.clear()
            }
            categoryChip.visibility = if (checked) View.GONE else View.VISIBLE
            btnQuickRecord?.visibility = if (checked) View.GONE else View.VISIBLE
            splitWorkArea.visibility = if (checked) View.VISIBLE else View.GONE
            renderSubItems()
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        CaptureApp.from(this@PendingListActivity).repository
                            .updateSplitRequested(item.id, checked)
                    }
                }
            }
        }
        if (splitRequested) {
            renderSubItems()
        }
        actionRow.addView(
            btnPrimary,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = dp(8) },
        )
        actionRow.addView(
            btnQuickRecord,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
        )
        actionRow.addView(
            CaptureUi.button(
                activity = this,
                text = "不计",
                textColor = CaptureUi.TEXT_MUTED,
                bgColor = CaptureUi.BG_SUBTLE,
                radiusDp = 10,
                paddingH = 12,
                paddingV = 8,
                textSizeSp = 12.5f,
                bold = false,
            ) { ignore(item) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        card.addView(actionRow)

        return card
    }

    private fun showMergedDetailsDialog(item: PaymentCapture) {
        val app = CaptureApp.from(this)
        scope.launch {
            val children = withContext(Dispatchers.IO) {
                app.repository.listMergedChildren(item.id)
            }
            val container = LinearLayout(this@PendingListActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }

            val descTv = TextView(this@PendingListActivity).apply {
                text = "此账单由多个渠道的记录自动合并而成：\n金额：¥${formatAmount(item.amountCents)}\n合并渠道：${item.channel ?: "多渠道"}"
                textSize = 13.5f
                setTextColor(CaptureUi.TEXT_SECONDARY)
            }
            container.addView(descTv)

            val divider = View(this@PendingListActivity).apply {
                setBackgroundColor(CaptureUi.BORDER)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(12)
                }
            }
            container.addView(divider)

            val mainHeader = TextView(this@PendingListActivity).apply {
                text = "【主账单记录】"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
            }
            container.addView(mainHeader)

            val mainDetail = TextView(this@PendingListActivity).apply {
                text = "来源: ${sourceLabel(item.source)} · 商户: ${item.merchant ?: "未知"} · 资产: ${item.assetName ?: item.paymentMethodRaw ?: "未分配"}\n时间: ${rowTimeFormat.format(Date(item.paidAt))}"
                textSize = 12.5f
                setTextColor(CaptureUi.TEXT_MUTED)
                setPadding(0, dp(4), 0, dp(12))
            }
            container.addView(mainDetail)

            if (children.isEmpty()) {
                val emptyTv = TextView(this@PendingListActivity).apply {
                    text = "（未查到子记录明细，可能由同渠道并发重发合并）"
                    textSize = 12.5f
                    setTextColor(CaptureUi.TEXT_MUTED)
                }
                container.addView(emptyTv)
            } else {
                val subHeader = TextView(this@PendingListActivity).apply {
                    text = "【已合并的子记录 (${children.size}笔)】"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(CaptureUi.TEXT_PRIMARY)
                }
                container.addView(subHeader)

                for (child in children) {
                    val childRow = LinearLayout(this@PendingListActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(8))
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(6) }
                    }

                    val infoCol = LinearLayout(this@PendingListActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val childChan = child.channel ?: sourceLabel(child.source)
                    infoCol.addView(TextView(this@PendingListActivity).apply {
                        text = "渠道: $childChan · ${child.merchant ?: "无商户"}"
                        textSize = 13f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(CaptureUi.TEXT_PRIMARY)
                    })
                    infoCol.addView(TextView(this@PendingListActivity).apply {
                        text = "时间: ${rowTimeFormat.format(Date(child.paidAt))}"
                        textSize = 11.5f
                        setTextColor(CaptureUi.TEXT_MUTED)
                    })
                    childRow.addView(infoCol)

                    val unmergeBtn = CaptureUi.chip(
                        activity = this@PendingListActivity,
                        text = "解除合并",
                        textColor = Color.parseColor("#DC2626"),
                        bgColor = Color.parseColor("#FEF2F2"),
                        strokeColor = Color.parseColor("#FECACA"),
                    ) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                app.repository.unmerge(child.id)
                            }
                            if (ok) {
                                toast("已解除合并，该记录已恢复到待记")
                                load()
                            }
                        }
                    }
                    childRow.addView(unmergeBtn)

                    container.addView(childRow)
                }
            }

            val scroll = ScrollView(this@PendingListActivity).apply {
                addView(container)
            }

            AlertDialog.Builder(this@PendingListActivity)
                .setTitle("多渠道合并详情")
                .setView(scroll)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun showBookPicker(
        item: PaymentCapture,
        ctx: AccountingContext?,
        onResult: ((String) -> Unit)? = null,
    ) {
        val bookNames = ctx?.books?.map { it.name }?.filter { it.isNotBlank() }.orEmpty()
        val availableBooks = if (bookNames.isNotEmpty()) bookNames else listOf("日常账本")
        val currentBook = item.bookName?.takeIf { it.isNotBlank() }
            ?: ctx?.currentBookName?.takeIf { it.isNotBlank() }
            ?: ctx?.defaultBookName?.takeIf { it.isNotBlank() }
            ?: "日常账本"

        OverlayDialogs.showBookPickerDialog(
            ctx = this,
            books = availableBooks,
            currentBook = currentBook,
        ) { chosenBook ->
            onResult?.invoke(chosenBook)
            updatePendingBook(item.id, chosenBook)
            scope.launch {
                withContext(Dispatchers.IO) {
                    CaptureApp.from(this@PendingListActivity).repository
                        .updateBookName(item.id, chosenBook)
                }
            }
        }
    }

    private fun showAssetPicker(item: PaymentCapture, ctx: AccountingContext? = null) {
        val rawChannel = item.paymentMethodRaw?.trim().orEmpty()
        OverlayDialogs.showGridAssetPicker(
            ctx = this,
            currentSelectionText = item.assetName.orEmpty(),
            title = "选择对应账户资产",
            allowClear = true,
        ) { chosenAsset ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    CaptureApp.from(this@PendingListActivity).repository.updateDraft(
                        context = this@PendingListActivity,
                        id = item.id,
                        note = item.note,
                        assetName = chosenAsset,
                        categoryName = item.categoryName,
                        rememberAssetMapping = rawChannel.isNotEmpty() && chosenAsset.isNotBlank(),
                    )
                }
                updatePendingAsset(item.id, chosenAsset, rawChannel)
            }
        }
    }

    private fun showCategoryPicker(
        item: PaymentCapture,
        ctx: AccountingContext? = null,
        title: String = "选择账单分类",
        onResult: ((String?) -> Unit)? = null,
    ) {
        val isIncome = item.billType == LocalBillType.TYPE_INCOME
        val isRefund = item.subType == LocalBillType.SUBTYPE_REFUND || item.needsRefundLink
        val pickerType = if (isIncome || isRefund) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
        val allowClear = item.status != CaptureStatus.RECORDED && onResult == null

        OverlayDialogs.showGridCategoryPicker(
            ctx = this,
            currentSelectionText = item.categoryName.orEmpty(),
            type = pickerType,
            allowClear = allowClear,
            title = title,
            onCancel = { onResult?.invoke(null) },
        ) { chosenCat ->
            if (onResult == null) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        CaptureApp.from(this@PendingListActivity).repository.updateDraft(
                            context = this@PendingListActivity,
                            id = item.id,
                            note = item.note,
                            assetName = item.assetName,
                            categoryName = chosenCat,
                            categorySource = CategorySource.MANUAL,
                        )
                    }
                    updatePendingCategory(item.id, chosenCat, CategorySource.MANUAL)
                }
            } else {
                scope.launch {
                    val updated = runCatching {
                        withContext(Dispatchers.IO) {
                            CaptureApp.from(this@PendingListActivity).repository.updateDraft(
                                context = this@PendingListActivity,
                                id = item.id,
                                note = item.note,
                                assetName = item.assetName,
                                categoryName = chosenCat,
                                categorySource = CategorySource.MANUAL,
                            )
                        }
                    }.getOrNull()
                    if (updated == null) {
                        toast("保存分类失败")
                    } else {
                        updatePendingCategory(item.id, chosenCat, CategorySource.MANUAL)
                    }
                    onResult.invoke(if (updated != null) chosenCat else null)
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════
    //  批量一键入账
    // ════════════════════════════════════════════════════════

    /**
     * 批量入账前防护：扫描待入账列表，若存在未指定资产的条目，弹出友好的确认对话框。
     */
    private fun confirmAndBatchImport(
        readyItems: List<PaymentCapture>,
        missingItems: List<PaymentCapture>,
    ) {
        val unmappedAssetItems = readyItems.filter { it.assetName.isNullOrBlank() }
        if (unmappedAssetItems.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("部分待记未绑定账户")
                .setMessage("待入账记录中有 ${unmappedAssetItems.size} 笔尚未指定账户资产。\n建议绑定对应账户后再入账，也可直接入账。")
                .setPositiveButton("去绑定") { _, _ ->
                    val first = unmappedAssetItems.first()
                    val targetCard = pendingCardViews[first.id]
                    val scroll = bodyFrame.getChildAt(0) as? ScrollView
                    if (targetCard != null && scroll != null) {
                        scroll.post {
                            scroll.smoothScrollTo(0, targetCard.top)
                        }
                    }
                    showAssetPicker(first)
                }
                .setNegativeButton("仍然入账") { _, _ ->
                    batchImportWithMissing(readyItems, missingItems)
                }
                .setNeutralButton("取消", null)
                .show()
        } else {
            batchImportWithMissing(readyItems, missingItems)
        }
    }

    /**
     * 一键入账：
     * 1. 有备注、缺分类的先自动跑一次 AI；
     * 2. 没备注、缺分类的不弹窗，留在待记并用 Toast 汇总；
     * 3. 有备注但 AI 仍分不出的，才弹“逐笔补分类”。
     */
    private fun batchImportWithMissing(
        readyItems: List<PaymentCapture>,
        missingItems: List<PaymentCapture>,
    ) {
        if (isAiBusy) return
        isAiBusy = true
        updateSummaryImportButton()
        scope.launch {
            var noNoteSkipped: List<PaymentCapture> = emptyList()
            var noteMissingForPrompt: List<PaymentCapture> = emptyList()
            try {
                noteSaveJobs.values.forEach { it.join() }
                val repo = CaptureApp.from(this@PendingListActivity).repository
                val requestedIds = (readyItems + missingItems).map { it.id }.distinct()
                var freshItems = withContext(Dispatchers.IO) {
                    requestedIds.mapNotNull { repo.findById(it) }
                }

                // 一键入账前兜底：只给“有备注、还没分类”的条目补一次 AI。
                val aiTargets = freshItems.filter {
                    !it.splitRequested &&
                        !it.note.isNullOrBlank() &&
                        it.categoryName.isNullOrBlank() &&
                        it.billType != LocalBillType.TYPE_TRANSFER
                }
                if (aiTargets.isNotEmpty() && Prefs.getAiKey(this@PendingListActivity).isNotBlank()) {
                    val outcome = withContext(Dispatchers.IO) {
                        bridge.classifyNotes(
                            aiTargets.map { item ->
                                CaptureBridgeClient.NoteClassifyInput(
                                    captureId = item.id,
                                    note = item.note,
                                    billType = item.billType,
                                )
                            }
                        )
                    }
                    if (outcome.ok) {
                        val updates = mutableMapOf<String, Pair<String?, String?>>()
                        withContext(Dispatchers.IO) {
                            for (item in aiTargets) {
                                val category = outcome.categories[item.id]
                                    ?.takeIf { it.isNotBlank() } ?: continue
                                val latest = repo.findById(item.id) ?: continue
                                if (latest.note?.trim() != item.note?.trim()) continue
                                if (!latest.categoryName.isNullOrBlank()) continue
                                val updated = repo.updateDraft(
                                    context = this@PendingListActivity,
                                    id = latest.id,
                                    note = latest.note,
                                    assetName = latest.assetName,
                                    categoryName = category,
                                    categorySource = CategorySource.AI,
                                ) ?: continue
                                updates[latest.id] = updated.categoryName to CategorySource.AI
                            }
                        }
                        if (updates.isNotEmpty()) {
                            applyPendingCategoryUpdates(updates)
                            freshItems = withContext(Dispatchers.IO) {
                                requestedIds.mapNotNull { repo.findById(it) }
                            }
                        }
                    }
                }

                val freshReady = freshItems.filter {
                    !it.splitRequested &&
                        !it.categoryName.isNullOrBlank() &&
                        (it.amountCents ?: 0L) > 0L
                }
                val allMissing = freshItems.filter { !it.splitRequested && it.needsCategory() }
                noNoteSkipped = allMissing.filter { it.note.isNullOrBlank() }
                noteMissingForPrompt = allMissing.filter { !it.note.isNullOrBlank() }
                val splitSkipped = freshItems.count { it.splitRequested }

                if (freshReady.isNotEmpty()) {
                    val bills = freshReady.map { item ->
                        ImportBill(
                            captureId = item.id,
                            type = item.billType ?: LocalBillType.TYPE_EXPENSE,
                            subType = item.subType ?: LocalBillType.SUBTYPE_NORMAL,
                            needsRefundLink = item.needsRefundLink,
                            amountCents = item.amountCents ?: 0L,
                            currency = item.currency,
                            paidAt = item.paidAt,
                            assetName = item.assetName ?: item.paymentMethodRaw,
                            toAssetName = item.toAssetName,
                            categoryName = item.categoryName,
                            remarks = item.note?.takeIf { it.isNotBlank() } ?: item.merchant.orEmpty(),
                            note = item.note,
                            bookName = item.bookName?.takeIf { it.isNotBlank() }
                                ?: latestSnapshot?.accountingContext?.currentBookName
                                ?: latestSnapshot?.accountingContext?.defaultBookName,
                            excludeFromStats = item.excludeFromStats,
                        )
                    }
                    val result = CaptureInboxEvents.suppressing {
                        bridge.importBills(bills, UUID.randomUUID().toString())
                    }
                    if (result == null) {
                        toast("敲敲记账桥接不可用，未入账")
                    } else {
                        val resultsArr = result.optJSONArray("results")
                        var successCount = 0
                        var firstError: String? = null
                        val successfulIds = mutableSetOf<String>()
                        if (resultsArr != null) {
                            for (i in 0 until resultsArr.length()) {
                                val r = resultsArr.optJSONObject(i) ?: continue
                                val cid = r.optString("captureId")
                                val st = r.optString("status")
                                if (st == ImportItemStatus.CREATED || st == ImportItemStatus.DUPLICATE) {
                                    successfulIds.add(cid)
                                    // 首次成功才学习；duplicate 不重复学习。
                                    if (st == ImportItemStatus.CREATED) {
                                        RuleLearningManager.schedule(this@PendingListActivity, cid)
                                    }
                                    successCount++
                                } else if (firstError == null) {
                                    firstError = r.optString("error").takeIf { it.isNotBlank() }
                                }
                            }
                        }
                        val importedItems = freshReady.filter { it.id in successfulIds }
                        if (importedItems.isNotEmpty()) {
                            // 状态已由 importBills 统一推进；这里只做局部 UI 更新。
                            latestSnapshot?.let { snap ->
                                val recorded = importedItems.map {
                                    it.copy(status = CaptureStatus.RECORDED)
                                }
                                val newHistory = (recorded + snap.historyItems).take(200)
                                latestSnapshot = snap.copy(
                                    historyItems = newHistory,
                                    historyCount = snap.historyCount + recorded.size,
                                )
                                val newHistoryCount = latestSnapshot?.historyCount ?: 0
                                tabViews.getOrNull(3)?.text = if (newHistoryCount > 0) {
                                    "历史 $newHistoryCount"
                                } else {
                                    "历史"
                                }
                            }
                            removePendingCards(successfulIds)
                            val askEntries = importedItems.map {
                                RuleLearningManager.RuleLearnEntry(
                                    note = it.note.orEmpty(),
                                    categoryName = it.categoryName.orEmpty(),
                                    billType = it.billType ?: 0,
                                )
                            }
                            val asked = com.taostudio.tapaccounting.logic.RuleAskCoordinator
                                .showIfNeeded(this@PendingListActivity, askEntries)
                            val failCount = bills.size - successCount
                            if (failCount > 0) {
                                toast("入账 $successCount 笔，失败 $failCount 笔（${humanizeImportError(firstError)}）")
                            }
                            if (!asked) {
                                showBatchResultCard(importedItems) {
                                    if (noteMissingForPrompt.isNotEmpty()) {
                                        promptMissingCategories(noteMissingForPrompt)
                                    }
                                }
                            } else if (noteMissingForPrompt.isNotEmpty()) {
                                promptMissingCategories(noteMissingForPrompt)
                            }
                        } else {
                            val failCount = bills.size
                            toast("入账失败 $failCount 笔（${humanizeImportError(firstError)}）")
                            if (noteMissingForPrompt.isNotEmpty()) {
                                promptMissingCategories(noteMissingForPrompt)
                            }
                        }
                    }
                } else {
                    if (noteMissingForPrompt.isNotEmpty()) {
                        promptMissingCategories(noteMissingForPrompt)
                    } else if (noNoteSkipped.isEmpty()) {
                        toast("没有可入账的账单")
                    }
                }

                if (noNoteSkipped.isNotEmpty()) {
                    toastNoNoteSkipped(noNoteSkipped)
                }
                if (splitSkipped > 0) {
                    toast("已跳过 $splitSkipped 笔勾选拆分的账单，请逐笔点“拆分入账”")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                toast("入账失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                isAiBusy = false
                updateSummaryImportButton()
                CaptureNotifier.refreshAll(this@PendingListActivity)
            }
        }
    }

    private fun toastNoNoteSkipped(items: List<PaymentCapture>) {
        if (items.isEmpty()) return
        val preview = items.take(3).joinToString("、") { item ->
            buildString {
                append(formatAmount(item.amountCents))
                item.merchant?.takeIf { it.isNotBlank() }?.let { append(" ").append(it.take(8)) }
            }
        }
        val suffix = if (items.size > 3) " 等 ${items.size} 笔" else ""
        toast("${items.size} 笔没备注，未入账：$preview$suffix")
    }

    private fun showBatchResultCard(
        items: List<PaymentCapture>,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (items.isEmpty()) {
            onDismiss?.invoke()
            return
        }
        val message = items.take(10).joinToString("\n") { item ->
            val category = item.categoryName?.takeIf { it.isNotBlank() } ?: "未分类"
            "· ${formatAmount(item.amountCents)}  $category"
        } + if (items.size > 10) "\n… 还有 ${items.size - 10} 笔" else ""
        AlertDialog.Builder(this)
            .setTitle("本次入账 ${items.size} 笔")
            .setMessage(message)
            .setPositiveButton("知道了") { _, _ -> onDismiss?.invoke() }
            .setOnCancelListener { onDismiss?.invoke() }
            .show()
    }

    private fun promptMissingCategories(items: List<PaymentCapture>) {
        // 没备注的已经用 Toast 汇总，不再弹窗打扰。
        val targets = items.filter { !it.note.isNullOrBlank() }
        if (targets.isEmpty()) return
        val preview = buildString {
            targets.take(8).forEach { item ->
                append("· ").append(formatAmount(item.amountCents))
                item.merchant?.takeIf { it.isNotBlank() }?.let { append(" ").append(it.take(14)) }
                item.note?.takeIf { it.isNotBlank() }?.let {
                    append("（").append(it.take(14)).append("）")
                }
                append('\n')
            }
            if (targets.size > 8) append("… 还有 ${targets.size - 8} 笔")
        }
        AlertDialog.Builder(this)
            .setTitle("${targets.size} 笔有备注但暂未分出分类")
            .setMessage("以下待记仍保留在列表里，需逐笔补分类：\n\n$preview")
            .setPositiveButton("逐笔补分类") { _, _ ->
                promptMissingCategoryAt(targets, 0, 0)
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun promptMissingCategoryAt(
        items: List<PaymentCapture>,
        index: Int,
        addedCount: Int,
    ) {
        if (index >= items.size) {
            if (addedCount > 0) {
                toast("已补 $addedCount 笔分类，可再次点“一键入账”")
            } else {
                toast("未补分类，缺分类账单仍保留在待记")
            }
            refreshSummaryHeroCard()
            return
        }
        val item = items[index]
        showCategoryPicker(
            item = item,
            ctx = latestSnapshot?.accountingContext,
            title = "补分类 ${index + 1}/${items.size}",
            onResult = { selected ->
                promptMissingCategoryAt(
                    items = items,
                    index = index + 1,
                    addedCount = addedCount + if (selected != null) 1 else 0,
                )
            },
        )
    }

    // ════════════════════════════════════════════════════════
    //  单笔操作与模拟调试
    // ════════════════════════════════════════════════════════

    private fun ignore(item: PaymentCapture) {
        splitDrafts.remove(item.id)
        scope.launch {
            withContext(Dispatchers.IO) {
                CaptureApp.from(this@PendingListActivity).repository
                    .updateStatus(item.id, CaptureStatus.IGNORED)
            }
            val snapshot = latestSnapshot ?: return@launch
            val newPending = snapshot.pendingItems.filterNot { it.id == item.id }
            val newIgnored = snapshot.ignoredItems + item.copy(status = CaptureStatus.IGNORED)
            latestSnapshot = snapshot.copy(pendingItems = newPending, ignoredItems = newIgnored)
            pendingCardViews.remove(item.id)?.let { card ->
                (card.parent as? ViewGroup)?.removeView(card)
            }
            pendingCategoryChips.remove(item.id)
            pendingCategorySources.remove(item.id)
            pendingAssetChips.remove(item.id)
            pendingBookChips.remove(item.id)
            updateInboxTabCount(newPending)
            refreshSummaryHeroCard()
            rebuildIgnoredSection()
            CaptureNotifier.refreshAll(this@PendingListActivity)
            toast("已移到「已不计」")
        }
    }

    private fun mutate(block: suspend (CaptureRepository) -> Unit) {
        val curScroll = (bodyFrame.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        if (curScroll > 0) savedInboxScrollY = curScroll
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    block(CaptureApp.from(this@PendingListActivity).repository)
                }
                load()
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                toast("操作失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun showSplitError(error: String?, message: String?) {
        AlertDialog.Builder(this)
            .setTitle("拆分未完成")
            .setMessage(humanizeSplitError(error, message))
            .setPositiveButton("知道了", null)
            .show()
    }

    private suspend fun performGroupImport(
        item: PaymentCapture,
        items: List<CaptureBridgeClient.SplitItem>,
    ) {
        val effectiveBookName = item.bookName?.takeIf { it.isNotBlank() }
            ?: latestSnapshot?.accountingContext?.currentBookName
            ?: latestSnapshot?.accountingContext?.defaultBookName
        val captureWithBook = item.copy(bookName = effectiveBookName)
        val result = bridge.importBillGroup(captureWithBook, items, UUID.randomUUID().toString())
        if (result == null) {
            toast("拆分入账失败：敲敲记账桥接不可用，已保留待记")
            return
        }
        if (result.ok || result.duplicate) {
            splitDrafts.remove(item.id)
            withContext(Dispatchers.IO) {
                CaptureApp.from(this@PendingListActivity).repository
                    .updateStatus(item.id, CaptureStatus.RECORDED)
            }
            if (result.duplicate) {
                toast("这笔已入账，未重复建单")
            } else {
                toast(splitSuccessToast(items.size, item.amountCents ?: 0L))
            }
            load()
        } else {
            toast("拆分入账失败：${humanizeSplitError(result.error, result.message)}")
        }
    }

    private fun splitSuccessToast(count: Int, totalCents: Long): String =
        "已拆成 $count 笔；账单页显示明细，资产详情按 ${formatAmount(totalCents)} 合并显示"

    private fun humanizeSplitError(error: String?, message: String?): String {
        val code = error?.trim().orEmpty()
        val fallback = message?.trim().orEmpty()
        return when {
            code == "split_empty" -> fallback.ifEmpty {
                "没从备注里拆出条目，请把备注写成「苹果30 雪梨40」"
            }
            code == "split_total_exceeds" -> fallback.ifEmpty { "明细合计超过付款，请改备注" }
            code == "split_amount_missing" -> fallback.ifEmpty { "有多项缺金额，请补金额或改备注" }
            code == "split_amount_invalid" -> fallback.ifEmpty { "有条目金额无效，请改备注" }
            code == "split_total_mismatch" -> fallback.ifEmpty { "拆分合计与付款不一致，请重新确认" }
            code == "split_unsupported_type" -> fallback.ifEmpty { "转账/还款/退款不支持文字拆分" }
            code == "ai_not_configured" -> fallback.ifEmpty { "请先在敲敲记账中配置 AI API Key" }
            code == "ai_timeout" -> fallback.ifEmpty { "AI 拆分超时，请重试或改备注" }
            code == "split_ai_failed" -> fallback.ifEmpty { "AI 拆分失败，请检查 AI 设置或改备注" }
            code == "bridge_unavailable" -> "敲敲记账桥接不可用"
            code == "missing_category" -> fallback.ifEmpty { "有条目缺少分类，请改备注或配置 AI/规则" }
            code == "category_not_found" || code.startsWith("category_not_found:") -> {
                val name = code.removePrefix("category_not_found:")
                    .ifBlank { fallback.removePrefix("category_not_found:") }
                if (name.isNotBlank()) {
                    "敲敲记账中不存在分类「$name」，请改备注或先建分类"
                } else {
                    "敲敲记账中没有对应分类，请改备注或先建分类"
                }
            }
            code == "asset_not_found" || code.startsWith("asset_not_found:") -> {
                val name = code.removePrefix("asset_not_found:")
                    .ifBlank { fallback.removePrefix("asset_not_found:") }
                if (name.isNotBlank()) {
                    "敲敲记账中不存在账户「$name」，请先绑定资产"
                } else {
                    "敲敲记账中没有对应账户，请先绑定资产"
                }
            }
            code == "missing_asset" -> "缺少账户，请先绑定资产"
            fallback.isNotEmpty() -> fallback
            code.isNotEmpty() -> code
            else -> "请检查分类、账户与备注"
        }
    }

    private fun directImport(item: PaymentCapture) {
        val type = item.billType ?: LocalBillType.TYPE_EXPENSE
        if ((item.amountCents ?: 0L) <= 0L) {
            toast("金额缺失，不能直接入账")
            return
        }
        if (type in setOf(LocalBillType.TYPE_EXPENSE, LocalBillType.TYPE_INCOME) &&
            item.categoryName.isNullOrBlank()
        ) {
            toast("请先为该笔账单选择分类")
            showCategoryPicker(item, latestSnapshot?.accountingContext)
            return
        }
        scope.launch {
            // 先把用户刚改的备注持久化到待记表，防止入账失败时草稿丢失
            withContext(Dispatchers.IO) {
                CaptureApp.from(this@PendingListActivity).repository.updateDraft(
                    context = this@PendingListActivity,
                    id = item.id,
                    note = item.note,
                    assetName = item.assetName,
                    categoryName = item.categoryName,
                )
            }
            val bill = ImportBill(
                captureId = item.id,
                type = type,
                subType = item.subType ?: LocalBillType.SUBTYPE_NORMAL,
                needsRefundLink = item.needsRefundLink,
                amountCents = item.amountCents ?: 0L,
                currency = item.currency,
                paidAt = item.paidAt,
                assetName = item.assetName ?: item.paymentMethodRaw,
                toAssetName = item.toAssetName,
                categoryName = item.categoryName,
                remarks = item.note?.takeIf { it.isNotBlank() } ?: item.merchant.orEmpty(),
                note = item.note,
                bookName = item.bookName?.takeIf { it.isNotBlank() }
                    ?: latestSnapshot?.accountingContext?.currentBookName
                    ?: latestSnapshot?.accountingContext?.defaultBookName,
                excludeFromStats = item.excludeFromStats,
            )
            performDirectImport(item, bill)
        }
    }

    private suspend fun performDirectImport(item: PaymentCapture, bill: ImportBill) {
        val requestId = UUID.randomUUID().toString()
        val result = bridge.importBills(listOf(bill), requestId)
        if (result == null) {
            toast("App 桥接不可用，未入账")
            return
        }
        val first = result.optJSONArray("results")?.optJSONObject(0)
        when (first?.optString("status")) {
            ImportItemStatus.CREATED -> {
                withContext(Dispatchers.IO) {
                    CaptureApp.from(this@PendingListActivity).repository
                        .updateStatus(item.id, CaptureStatus.RECORDED)
                    RuleLearningManager.schedule(this@PendingListActivity, item.id)
                }
                com.taostudio.tapaccounting.logic.RuleAskCoordinator.showIfNeeded(
                    this@PendingListActivity,
                    listOf(
                        RuleLearningManager.RuleLearnEntry(
                            note = item.note.orEmpty(),
                            categoryName = item.categoryName.orEmpty(),
                            billType = item.billType ?: 0,
                        )
                    ),
                )
                toast("✓ 已入账")
                load()
            }
            ImportItemStatus.DUPLICATE -> {
                withContext(Dispatchers.IO) {
                    CaptureApp.from(this@PendingListActivity).repository
                        .updateStatus(item.id, CaptureStatus.RECORDED)
                }
                toast("✓ 已入账")
                load()
            }
            else -> {
                val error = first?.optString("error").orEmpty()
                    .ifBlank { result.optString("error", "未知错误") }
                toast("入账未完成：${humanizeImportError(error)}")
            }
        }
    }

    private fun humanizeImportError(rawError: String?): String {
        val err = rawError?.trim().orEmpty()
        return when {
            err == "missing_category" -> "请先选择分类"
            err.startsWith("category_not_found:") ->
                "敲敲记账中不存在分类「${err.removePrefix("category_not_found:")}」，请点分类标签重选"
            err.startsWith("asset_not_found:") ->
                "敲敲记账中不存在账户「${err.removePrefix("asset_not_found:")}」，请点账户标签绑定真实资产"
            err == "needs_refund_link" -> "退款账单需在悬浮窗关联原消费账单"
            err.isNotEmpty() -> err
            else -> "请检查分类与账户映射"
        }
    }

    private fun openQuickRecord(item: PaymentCapture) {
        val effectiveBook = item.bookName?.takeIf { it.isNotBlank() }
            ?: latestSnapshot?.accountingContext?.currentBookName
            ?: latestSnapshot?.accountingContext?.defaultBookName
        val prefill = JSONObject().apply {
            put("type", item.billType ?: LocalBillType.TYPE_EXPENSE)
            put("subType", item.subType ?: LocalBillType.SUBTYPE_NORMAL)
            put("amount", (item.amountCents ?: 0L) / 100.0)
            put("currency", item.currency)
            (item.assetName ?: item.paymentMethodRaw)?.let { put("asset_name", it) }
            item.toAssetName?.let { put("to_asset_name", it) }
            item.categoryName?.let { put("category_name", it) }
            put("time", prefillTimeFormat.format(Date(item.paidAt)))
            put("remarks", item.note?.takeIf { it.isNotBlank() } ?: item.merchant.orEmpty())
            effectiveBook?.let { put("book_name", it) }
            put("exclude_from_stats", item.excludeFromStats)
        }
        val requestId = UUID.randomUUID().toString()
        scope.launch {
            withContext(Dispatchers.IO) {
                CaptureApp.from(this@PendingListActivity).repository.updateDraft(
                    context = this@PendingListActivity,
                    id = item.id,
                    note = item.note,
                    assetName = item.assetName,
                    categoryName = item.categoryName,
                )
            }
            val accepted = bridge.openQuickRecord(prefill, item.id, requestId)
            if (accepted) {
                toast("已拉起敲敲记账悬浮窗")
            } else {
                toast("敲敲记账未安装、未授权悬浮窗或桥接不可用")
            }
        }
    }

    private fun showCustomSimulateDialog() {
        ruleViews.showCustomSimulateDialog { source, amountCents, merchant, note ->
            simulateCapture(amountCents, merchant, note, source)
        }
    }

    private fun simulateCapture(
        amountCents: Long = 300L,
        merchant: String = "示例支出",
        note: String? = null,
        source: String = CaptureSource.WECHAT,
    ) {
        val now = System.currentTimeMillis()
        val scopeCtx = applicationContext
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val rawMethod = if (source == CaptureSource.ALIPAY) "支付宝余额" else "零钱"
                    val mappedAsset = CaptureRuleMatcher.resolveAssetName(scopeCtx, rawMethod)
                    val payload = CapturePayload(
                        captureId = UUID.randomUUID().toString(),
                        source = source,
                        amountCents = amountCents,
                        currency = "CNY",
                        merchant = merchant,
                        paymentMethodRaw = rawMethod,
                        assetName = mappedAsset,
                        categoryName = null,
                        paidAt = now,
                        capturedAt = now,
                        note = note,
                        billType = LocalBillType.TYPE_EXPENSE,
                        subType = LocalBillType.SUBTYPE_NORMAL,
                        rawJson = JSONObject().apply {
                            put("simulated", true)
                            put("amountCents", amountCents)
                            put("merchant", merchant)
                            if (note != null) put("description", note)
                        }.toString(),
                        ruleVersion = "simulated",
                    )
                    val repo = CaptureApp.from(this@PendingListActivity).repository
                    repo.insert(
                        payload,
                        transferIncomeDedupEnabled = CapturePrefs.isTransferIncomeDedupEnabled(scopeCtx),
                        transferIncomeDedupWindowMillis = CapturePrefs.getTransferIncomeDedupWindowMillis(scopeCtx),
                    )
                    val pendingList = repo.listPending(10, true)
                    Pair(payload, pendingList)
                }
                val saved = outcome.second.firstOrNull { it.id == outcome.first.captureId }
                if (saved != null && CapturePrefs.getPromptMode(this@PendingListActivity) == CapturePrefs.MODE_BUBBLE) {
                    CaptureBubbleManager.showBubble(this@PendingListActivity, saved, outcome.second.size)
                }
                currentTab = 0
                updateTabSelection()
                load()
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                toast("模拟失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun simulateWcdbSamples() {
        val now = System.currentTimeMillis()
        val rawJson = JSONObject().put(
            "mMap",
            JSONObject()
                .put("description", "使用招商银行储蓄卡(4852)支付￥9.00")
                .put("title", "已支付¥9.00")
                .put("display_name", "同程旅行")
                .put("source", "微信支付")
                .put("t", now),
        ).toString()
        val payload = JSONObject()
            .put("captureId", UUID.randomUUID().toString())
            .put("source", CaptureSource.WECHAT)
            .put("rawType", "DATA")
            .put("paidAt", now)
            .put("capturedAt", now)
            .put("rawJson", rawJson)
            .put("ruleVersion", "simulate-wcdb")
            .toString()
        scope.launch {
            withContext(Dispatchers.IO) {
                CaptureIngestCoordinator.ingest(
                    applicationContext,
                    BuildConfig.CAPTURE_INGEST_TOKEN,
                    payload,
                )
            }
            delay(800)
            currentTab = 0
            updateTabSelection()
            load()
        }
    }

    private fun parseSamples() {
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                SAMPLES.joinToString("\n\n") { (label, raw) -> describeSample(label, raw) }
            }
            AlertDialog.Builder(this@PendingListActivity)
                .setTitle("规则解析样本")
                .setMessage(report)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    private fun describeSample(label: String, rawJson: String): String = try {
        when (val outcome = AutoAccountingRuleEngine.parse(CaptureSource.WECHAT, "DATA", rawJson)) {
            is AutoAccountingRuleEngine.ParseOutcome.NoMatch -> "$label\n→ 未命中任何启用规则"
            is AutoAccountingRuleEngine.ParseOutcome.Matched -> {
                val parsed = outcome.rule
                buildString {
                    append(label).append('\n')
                    append("规则：").append(parsed.ruleName).append('\n')
                    append("金额：").append(formatAmount(parsed.amountCents)).append('\n')
                    append("商户：").append(parsed.merchant ?: "—").append('\n')
                    append("支付方式：").append(parsed.accountNameFrom ?: "—")
                }
            }
        }
    } catch (t: Throwable) {
        "$label\n→ 失败：${t.javaClass.simpleName}: ${t.message}"
    }

    // ════════════════════════════════════════════════════════
    //  UI 工具与数据模型
    // ════════════════════════════════════════════════════════

    private data class Snapshot(
        val pendingItems: List<PaymentCapture>,
        val ignoredItems: List<PaymentCapture> = emptyList(),
        val historyItems: List<PaymentCapture> = emptyList(),
        val historyCount: Int = 0,
        val eventCount: Int = 0,
        val assetRules: List<AssetMappingRule>,
        val accountingRules: List<AccountingRule>,
        val ruleBundle: RuleBundleCodec.Bundle,
        val accountingContext: AccountingContext?,
        val bridgeAvailable: Boolean,
        val aiEnabled: Boolean,
    )

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    // ════════════════════════════════════════════════════════
    //  规则 bundle 导入 / 导出（SAF，不申请存储权限）
    // ════════════════════════════════════════════════════════

    private fun exportRuleBundle() {
        scope.launch {
            val exported = withContext(Dispatchers.IO) {
                runCatching { RuleBundleStore.exportJson(this@PendingListActivity) }
            }
            val json = exported.getOrElse { error ->
                toast("导出失败：${error.message ?: error.javaClass.simpleName}")
                return@launch
            }
            pendingExportJson = json
            val fileName = "tap-capture-rule-bundle-" +
                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            runCatching { startActivityForResult(intent, REQUEST_EXPORT_RULES) }
                .onFailure {
                    pendingExportJson = null
                    toast("系统没有可用的文件导出器")
                }
        }
    }

    private fun importRuleBundle() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "application/octet-stream"),
            )
        }
        runCatching { startActivityForResult(intent, REQUEST_IMPORT_RULES) }
            .onFailure { toast("系统没有可用的文件选择器") }
    }

    @Deprecated("Activity#onActivityResult 在 Android 13 起被标记，但本插件保持零额外依赖，继续沿用。")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT_RULES) {
            val json = pendingExportJson
            pendingExportJson = null
            if (resultCode != RESULT_OK || json == null) return
            val uri = data?.data ?: return
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("无法打开输出文件")
                    }.isSuccess
                }
                toast(if (ok) "已导出当前规则 JSON" else "导出失败，请换一个保存位置")
            }
            return
        }
        if (requestCode != REQUEST_IMPORT_RULES || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                toast("读取规则文件失败")
                return@launch
            }
            val imported = withContext(Dispatchers.IO) {
                runCatching { RuleBundleStore.importJson(this@PendingListActivity, text) }
            }
            imported.onSuccess { bundle ->
                AlertDialog.Builder(this@PendingListActivity)
                    .setTitle("导入成功")
                    .setMessage(
                        "已导入 ${bundle.rules.size} 条规则（我的 ${bundle.userCount} 条）。\n" +
                            "原有规则已被这份文件替换；规则已立即生效。",
                    )
                    .setPositiveButton("好的", null)
                    .show()
                rulesSection = 0
                load()
            }.onFailure { error ->
                AlertDialog.Builder(this@PendingListActivity)
                    .setTitle("导入失败")
                    .setMessage(
                        "规则文件未通过校验，现有规则没有被修改。\n\n" +
                            (error.message ?: error.javaClass.simpleName),
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun sourceLabel(source: String): String = CaptureSources.displayName(source)

    private fun formatAmount(cents: Long?): String {
        if (cents == null) return "¥0.00"
        return "¥" + String.format(Locale.CHINA, "%.2f", cents / 100.0)
    }

    /** 转入/还款不需要消费分类；其余空分类的待记在一键入账时要弹窗补分类。 */
    private fun PaymentCapture.needsCategory(): Boolean =
        categoryName.isNullOrBlank() && billType != LocalBillType.TYPE_TRANSFER

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1001
        const val REQUEST_EXPORT_RULES = 1002
        const val REQUEST_IMPORT_RULES = 1003

        const val SAMPLE_WECHAT_PAY =
            """{"mMap":{"description":"使用招商银行储蓄卡(4852)支付￥9.00","source":"微信支付","title":"已支付¥9.00","display_name":"同程旅行","cachedPayTools":"","cachedPayMoney":"","cachedPayShop":"","t":1770696436886}}"""

        val SAMPLES: List<Pair<String, String>> = listOf(
            "样本 1：${CaptureSources.displayName(CaptureSource.WECHAT)}支付 mMap ¥9.00 同程旅行" to SAMPLE_WECHAT_PAY,
        )
    }
}
