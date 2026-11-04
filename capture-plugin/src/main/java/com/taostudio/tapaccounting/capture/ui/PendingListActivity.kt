package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.bridge.CaptureBridgeClient
import com.taostudio.tapaccounting.capture.bridge.CaptureNoteClassifier
import com.taostudio.tapaccounting.capture.bridge.CaptureReconciler
import com.taostudio.tapaccounting.capture.bridge.RuleLearningManager
import com.taostudio.tapaccounting.capture.contract.AccountingContext
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
import com.taostudio.tapaccounting.capture.data.CategoryRule
import com.taostudio.tapaccounting.capture.data.CategorySource
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import com.taostudio.tapaccounting.capture.data.RuleBundleCodec
import com.taostudio.tapaccounting.capture.data.RuleBundleStore
import com.taostudio.tapaccounting.capture.hook.CaptureForwarder
import com.taostudio.tapaccounting.capture.hook.WechatEnvelope
import com.taostudio.tapaccounting.capture.notify.CaptureBubbleManager
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher
import com.taostudio.tapaccounting.capture.service.CaptureKeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private val ruleViews by lazy { RuleMappingViews(this, scope) { load() } }
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
    private var pendingExportJson: String? = null
    private val noteSaveJobs = mutableMapOf<String, Job>()

    private lateinit var bridgeDot: View
    private lateinit var bridgeBadge: TextView
    private lateinit var tabViews: List<TextView>
    private lateinit var bodyFrame: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        CaptureUi.applyLightStatusBar(this)
        requestNotificationPermissionIfNeeded()
        CaptureKeepAliveService.start(this)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        noteSaveJobs.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
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

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBlock.addView(TextView(this).apply {
            text = "敲敲待记"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        titleBlock.addView(TextView(this).apply {
            text = "${CaptureSources.all.joinToString(" · ") { it.displayName }}收件箱与规则工作台"
            textSize = 11.5f
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
        val tabLabels = listOf("待记", "规则", "设置")
        tabViews = tabLabels.mapIndexed { index, label ->
            TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (currentTab != index) {
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

    private fun load() {
        scope.launch {
            try {
                val app = CaptureApp.from(this@PendingListActivity)
                val repository = app.repository
                val db = app.database
                val snapshot = withContext(Dispatchers.IO) {
                    CaptureRuleMatcher.ensureSeeded(this@PendingListActivity)
                    val allItems = repository.list(200)
                    val changed = CaptureReconciler.reconcile(repository, bridge, allItems)
                    val refreshed = if (changed > 0) repository.list(200) else allItems
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
                        assetRules = db.assetMappingDao().listAll(),
                        categoryRules = db.categoryRuleDao().listAll(),
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
                renderCurrentTab(snapshot)
                CaptureNotifier.refreshAll(this@PendingListActivity)
            } catch (t: Throwable) {
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
        bodyFrame.removeAllViews()
        val view = when (currentTab) {
            0 -> buildInboxTab(snapshot)
            1 -> buildRulesTab(snapshot)
            else -> ruleViews.buildSettingsTab(
                onSimulateExpense = { simulateCapture() },
                onSimulateWcdb = { simulateWcdbSamples() },
                onTestRuleSamples = { parseSamples() },
            )
        }
        bodyFrame.addView(view)
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
                    1 -> ruleViews.buildCategoryRuleTab(snapshot.categoryRules, snapshot.accountingContext)
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

    private fun buildInboxTab(snapshot: Snapshot): View {
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
        scroll.addView(container)

        val items = snapshot.pendingItems
        if (items.isEmpty()) {
            container.addView(buildEmptyStateCard())
        } else {
            container.addView(buildSummaryHeroCard(items))
            items.forEach { item ->
                container.addView(buildCaptureCard(item, snapshot.accountingContext))
            }
        }
        if (snapshot.ignoredItems.isNotEmpty()) {
            container.addView(buildIgnoredSection(snapshot.ignoredItems))
        }
        return scroll
    }

    /** 待记列表底部的“已不计”折叠区，只做恢复，不改变列表主体结构。 */
    private fun buildIgnoredSection(items: List<PaymentCapture>): View {
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
                ignoredExpanded = !ignoredExpanded
                latestSnapshot?.let { renderCurrentTab(it) }
            }
        }
        header.addView(TextView(this).apply {
            text = "已不计 (${items.size})"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = if (ignoredExpanded) "收起 ▴" else "展开 ▾"
            textSize = 12f
            setTextColor(CaptureUi.BRAND_DARK)
            setPadding(dp(8), dp(4), 0, dp(4))
        })
        card.addView(header)
        if (!ignoredExpanded) return card

        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, 0)
            }
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

    private fun restoreIgnored(item: PaymentCapture) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CaptureApp.from(this@PendingListActivity).repository
                        .updateStatus(item.id, CaptureStatus.PENDING)
                }
                toast("已恢复到待记")
                load()
            } catch (t: Throwable) {
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
        leftCol.addView(TextView(this).apply {
            text = "待处理 ${items.size} 笔"
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        leftCol.addView(TextView(this).apply {
            text = when {
                batchItems.isEmpty() && items.isNotEmpty() ->
                    "已勾选拆分 ${items.size} 笔，请逐笔点“拆分入账”"
                unmappedCount == 0 -> "全部已匹配分类，可直接一键入账"
                else -> "已就绪 ${readyItems.size} 笔 · 待分类 $unmappedCount 笔"
            }
            textSize = 12f
            setTextColor(if (unmappedCount == 0) CaptureUi.SUCCESS else CaptureUi.TEXT_SECONDARY)
            setPadding(0, dp(2), 0, 0)
        })
        statsRow.addView(leftCol)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val showIncomePrimary = expenseCents == 0L && incomeCents > 0L
        rightCol.addView(TextView(this).apply {
            text = if (showIncomePrimary) "+${formatAmount(incomeCents)}" else "-${formatAmount(expenseCents)}"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (showIncomePrimary) CaptureUi.SUCCESS else CaptureUi.TEXT_PRIMARY)
        })
        rightCol.addView(TextView(this).apply {
            text = when {
                showIncomePrimary -> "合计收入/退款"
                incomeCents > 0L -> "支出 · 另有收入/退款 +${formatAmount(incomeCents)}"
                else -> "合计支出"
            }
            textSize = 11f
            setTextColor(CaptureUi.TEXT_MUTED)
        })
        statsRow.addView(rightCol)
        card.addView(statsRow)

        // 批量动作行
        val batchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val aiBtn = CaptureUi.button(
            activity = this,
            text = if (isAiBusy) "归类中…" else "智能归类 (${items.size})",
            textColor = CaptureUi.BRAND_DARK,
            bgColor = CaptureUi.BRAND_SOFT,
            strokeColor = CaptureUi.BRAND_BORDER,
            onClick = { if (!isAiBusy) runBatchAiClassify(items) },
        )
        batchRow.addView(
            aiBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
        )

        val canImportAll = readyItems.isNotEmpty() || missingCategoryItems.isNotEmpty()
        val importAllBtn = CaptureUi.button(
            activity = this,
            text = "一键入账 (${readyItems.size})",
            textColor = if (canImportAll) Color.WHITE else CaptureUi.TEXT_MUTED,
            bgColor = if (canImportAll) CaptureUi.BRAND else CaptureUi.BG_SEGMENT,
            onClick = {
                when {
                    canImportAll -> batchImportWithMissing(readyItems, missingCategoryItems)
                    items.any { it.splitRequested } ->
                        toast("勾选拆分的账单请逐笔点“拆分入账”")
                    else -> toast("请先点左侧“智能归类”或为账单选择分类")
                }
            },
        )
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
        emptyCard.addView(TextView(this).apply {
            text = "✓"
            textSize = 24f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.SUCCESS)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(12) }
            background = CaptureUi.roundedBg(CaptureUi.SUCCESS_SOFT, dp(999), dp(1), CaptureUi.SUCCESS_BORDER)
        })
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
                text = "+ 模拟一笔示例支出 (¥3.00)",
                textColor = CaptureUi.BRAND_DARK,
                bgColor = CaptureUi.BRAND_SOFT,
                strokeColor = CaptureUi.BRAND_BORDER,
                paddingH = 18,
                paddingV = 10,
            ) { simulateCapture() }
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

        // 第二行：可点击【资产 Chip】+【分类 Chip】（实时更新 UI 引用）
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
        }
        val mappedAsset = item.assetName
        val rawChannel = item.paymentMethodRaw
        val hasAsset = !mappedAsset.isNullOrBlank()
        val assetText = when {
            hasAsset -> "账户 · $mappedAsset ▾"
            !rawChannel.isNullOrBlank() -> "绑定账户 · $rawChannel ▾"
            else -> "选择账户 ▾"
        }
        val assetChip = CaptureUi.chip(
            activity = this,
            text = assetText,
            textColor = if (hasAsset) CaptureUi.TEXT_SECONDARY else CaptureUi.WARNING,
            bgColor = if (hasAsset) CaptureUi.BG_SUBTLE else CaptureUi.WARNING_SOFT,
            strokeColor = if (hasAsset) CaptureUi.BORDER else CaptureUi.WARNING_BORDER,
        ) {
            showAssetPickerDialog(item, ctx)
        }
        chipRow.addView(
            assetChip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            },
        )

        var currentCategory = item.categoryName
        fun styleCategoryChip(tv: TextView, cat: String?) {
            val ok = !cat.isNullOrBlank()
            tv.text = if (ok) "分类 · $cat ▾" else "待选分类 ▾"
            tv.setTextColor(if (ok) CaptureUi.SUCCESS else CaptureUi.BRAND_DARK)
            tv.background = CaptureUi.rippleBg(
                fillColor = if (ok) CaptureUi.SUCCESS_SOFT else CaptureUi.BRAND_SOFT,
                radiusPx = dp(8),
                strokeWidthPx = dp(1),
                strokeColor = if (ok) CaptureUi.SUCCESS_BORDER else CaptureUi.BRAND_BORDER,
            )
        }
        val categoryChip = CaptureUi.chip(
            activity = this,
            text = "",
            textColor = CaptureUi.SUCCESS,
            bgColor = CaptureUi.SUCCESS_SOFT,
        ) {
            showCategoryPickerDialog(item, ctx)
        }
        styleCategoryChip(categoryChip, currentCategory)
        chipRow.addView(categoryChip)
        card.addView(chipRow)

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
                    if (updated != null && updated.categoryName != currentCategory) {
                        currentCategory = updated.categoryName
                        styleCategoryChip(categoryChip, currentCategory)
                    }
                }
            }
        })

        fun triggerAiClassify() {
            val note = noteInput.text?.toString()?.trim().orEmpty()
            if (note.isEmpty() || !currentCategory.isNullOrBlank()) return
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
                if (updated != null && updated.categoryName != currentCategory) {
                    currentCategory = updated.categoryName
                    styleCategoryChip(categoryChip, currentCategory)
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

        // 备注框下的“拆分记账”：只有勾选时才走 previewSplit；不勾选永远一笔。
        var splitRequested = item.splitRequested
        val splitBox = CheckBox(this).apply {
            text = "拆分记账（按备注拆成多条明细）"
            textSize = 12f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            isChecked = item.splitRequested
            setPadding(dp(2), dp(6), 0, 0)
        }
        card.addView(splitBox)

        // 第四行：操作栏（直接入账 / 拆分入账 / 悬浮窗改 / 不计）
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val primaryButton = CaptureUi.button(
            activity = this,
            text = if (splitRequested) "拆分入账" else "直接入账",
            textColor = Color.WHITE,
            bgColor = CaptureUi.BRAND,
            radiusDp = 10,
            paddingV = 8,
            textSizeSp = 13f,
        ) {
            val draftNote = noteInput.text?.toString()?.trim().orEmpty()
            val draft = item.copy(
                note = draftNote.ifBlank { item.note },
                categoryName = currentCategory ?: item.categoryName,
                splitRequested = splitRequested,
            )
            if (splitRequested) {
                startSplitImport(draft, noteInput)
            } else {
                directImport(draft)
            }
        }
        splitBox.setOnCheckedChangeListener { _, checked ->
            splitRequested = checked
            primaryButton.text = if (checked) "拆分入账" else "直接入账"
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        CaptureApp.from(this@PendingListActivity).repository
                            .updateSplitRequested(item.id, checked)
                    }
                }
            }
        }
        actionRow.addView(
            primaryButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = dp(8) },
        )
        actionRow.addView(
            CaptureUi.button(
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
                    )
                )
            },
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

    private fun showAssetPickerDialog(item: PaymentCapture, ctx: AccountingContext?) {
        val assetNames = ctx?.assets?.map { it.name }.orEmpty()
        if (assetNames.isEmpty()) {
            toast("尚未从敲敲记账读取到资产列表，请确认敲敲记账已安装")
            return
        }
        val rawChannel = item.paymentMethodRaw?.trim().orEmpty()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val rememberBox = CheckBox(this).apply {
            text = if (rawChannel.isNotEmpty()) {
                "记住「$rawChannel = 所选资产」，以后自动填入"
            } else {
                "记住该渠道资产映射"
            }
            isChecked = rawChannel.isNotEmpty()
            visibility = if (rawChannel.isNotEmpty()) View.VISIBLE else View.GONE
        }
        layout.addView(rememberBox)

        AlertDialog.Builder(this)
            .setTitle("选择对应账户资产")
            .setView(layout)
            .setItems(assetNames.toTypedArray()) { _, which ->
                val chosenAsset = assetNames[which]
                mutate { repo ->
                    repo.updateDraft(
                        context = this@PendingListActivity,
                        id = item.id,
                        note = item.note,
                        assetName = chosenAsset,
                        categoryName = item.categoryName,
                        rememberAssetMapping = rememberBox.isChecked && rawChannel.isNotEmpty(),
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCategoryPickerDialog(
        item: PaymentCapture,
        ctx: AccountingContext?,
        title: String = "选择账单分类",
        onResult: ((String?) -> Unit)? = null,
    ) {
        val isRefund = item.subType == LocalBillType.SUBTYPE_REFUND || item.needsRefundLink
        val targetType = if (isRefund) null else item.billType?.takeIf { it in 0..1 }
        val categories = ruleViews.buildCategoryDisplayList(ctx, targetType)
        if (categories.isEmpty()) {
            toast("尚未从敲敲记账读取到分类列表，请确认敲敲记账已安装")
            onResult?.invoke(null)
            return
        }
        val candidateKeyword = item.note?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.merchant?.trim()?.takeIf { it.isNotEmpty() }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val rememberBox = CheckBox(this).apply {
            text = if (!candidateKeyword.isNullOrBlank()) {
                "记住包含「$candidateKeyword」自动归入所选分类"
            } else {
                "记住分类规则"
            }
            isChecked = false
            visibility = if (!candidateKeyword.isNullOrBlank()) View.VISIBLE else View.GONE
        }
        layout.addView(rememberBox)

        var finished = false
        fun finish(result: String?) {
            if (finished) return
            finished = true
            onResult?.invoke(result)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setItems(categories.toTypedArray()) { _, which ->
                val chosenCat = categories[which]
                if (onResult == null) {
                    mutate { repo ->
                        repo.updateDraft(
                            context = this@PendingListActivity,
                            id = item.id,
                            note = item.note,
                            assetName = item.assetName,
                            categoryName = chosenCat,
                            categorySource = CategorySource.MANUAL,
                            rememberCategoryKeyword = if (rememberBox.isChecked) candidateKeyword else null,
                        )
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
                                    rememberCategoryKeyword = if (rememberBox.isChecked) candidateKeyword else null,
                                )
                            }
                        }.getOrNull()
                        if (updated == null) toast("保存分类失败")
                        finish(if (updated != null) chosenCat else null)
                    }
                }
            }
            .setNegativeButton("取消") { _, _ -> finish(null) }
            .setOnCancelListener { _ -> finish(null) }
            .show()
    }

    // ════════════════════════════════════════════════════════
    //  批量智能归类 & 批量一键入账
    // ════════════════════════════════════════════════════════

    private fun runBatchAiClassify(items: List<PaymentCapture>) {
        isAiBusy = true
        latestSnapshot?.let { renderCurrentTab(it) }
        scope.launch {
            try {
                // 先等待所有正在防抖输入的备注落库，确保发给 AI 的是最新的备注。
                noteSaveJobs.values.forEach { it.join() }
                val freshItems = withContext(Dispatchers.IO) {
                    val repo = CaptureApp.from(this@PendingListActivity).repository
                    items.mapNotNull { repo.findById(it.id) }
                }
                // 没备注不调 AI；已有本地规则/手动分类的也不重复调。
                val targets = freshItems.filter {
                    !it.splitRequested &&
                        !it.note.isNullOrBlank() &&
                        it.categoryName.isNullOrBlank() &&
                        it.billType != LocalBillType.TYPE_TRANSFER
                }
                if (targets.isEmpty()) {
                    toast("没有可智能归类的备注；没备注的账单保持待归类")
                    return@launch
                }
                toast("正在智能归类 ${targets.size} 笔…")
                val outcome = bridge.classifyNotes(
                    targets.map { item ->
                        CaptureBridgeClient.NoteClassifyInput(
                            captureId = item.id,
                            note = item.note,
                            billType = item.billType,
                        )
                    }
                )
                if (!outcome.ok) {
                    toast("智能归类未完成：${outcome.error ?: "请检查敲敲记账 AI 设置"}")
                    return@launch
                }
                var updatedCount = 0
                withContext(Dispatchers.IO) {
                    val repo = CaptureApp.from(this@PendingListActivity).repository
                    for (item in targets) {
                        val category = outcome.categories[item.id]?.takeIf { it.isNotBlank() }
                            ?: continue
                        // AI 返回时用户可能又改了备注或已手动分类：以数据库最新状态为准。
                        val latest = repo.findById(item.id) ?: continue
                        if (latest.note?.trim() != item.note?.trim()) continue
                        if (!latest.categoryName.isNullOrBlank()) continue
                        repo.updateDraft(
                            context = this@PendingListActivity,
                            id = latest.id,
                            note = latest.note,
                            assetName = latest.assetName,
                            categoryName = category,
                            categorySource = CategorySource.AI,
                        )
                        updatedCount++
                    }
                }
                val unresolved = targets.size - updatedCount
                toast(
                    if (unresolved == 0) {
                        "已智能归类 $updatedCount 笔，确认后可点“一键入账”"
                    } else {
                        "已智能归类 $updatedCount 笔，$unresolved 笔暂无法判断（保持待归类）"
                    }
                )
            } finally {
                isAiBusy = false
                load()
            }
        }
    }

    /**
     * 一键入账：只入账已有分类的条目；缺分类的不静默丢弃，
     * 入账完成后弹窗列出并逐笔让用户补分类，取消/未选则保留 PENDING。
     */
    private fun batchImportWithMissing(
        readyItems: List<PaymentCapture>,
        missingItems: List<PaymentCapture>,
    ) {
        scope.launch {
            noteSaveJobs.values.forEach { it.join() }
            val repo = CaptureApp.from(this@PendingListActivity).repository
            val requestedIds = (readyItems + missingItems).map { it.id }.distinct()
            val freshItems = withContext(Dispatchers.IO) {
                requestedIds.mapNotNull { repo.findById(it) }
            }
            val freshReady = freshItems.filter {
                !it.splitRequested &&
                    !it.categoryName.isNullOrBlank() &&
                    (it.amountCents ?: 0L) > 0L
            }
            val freshMissing = freshItems.filter { !it.splitRequested && it.needsCategory() }
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
                        bookName = item.bookName,
                    )
                }
                val result = bridge.importBills(bills, UUID.randomUUID().toString())
                if (result == null) {
                    toast("敲敲记账桥接不可用，未入账")
                } else {
                    val resultsArr = result.optJSONArray("results")
                    var successCount = 0
                    var firstError: String? = null
                    if (resultsArr != null) {
                        withContext(Dispatchers.IO) {
                            for (i in 0 until resultsArr.length()) {
                                val r = resultsArr.optJSONObject(i) ?: continue
                                val cid = r.optString("captureId")
                                val st = r.optString("status")
                                if (st == ImportItemStatus.CREATED || st == ImportItemStatus.DUPLICATE) {
                                    repo.updateStatus(cid, CaptureStatus.RECORDED)
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
                    }
                    val failCount = bills.size - successCount
                    if (failCount == 0) {
                        toast("✓ 已成功入账 $successCount 笔")
                    } else {
                        toast("入账 $successCount 笔，失败 $failCount 笔（${humanizeImportError(firstError)}）")
                    }
                }
            } else if (freshMissing.isEmpty()) {
                toast("没有可入账的账单")
            }
            if (splitSkipped > 0) {
                toast("已跳过 $splitSkipped 笔勾选拆分的账单，请逐笔点“拆分入账”")
            }

            load()
            if (freshMissing.isNotEmpty()) {
                promptMissingCategories(freshMissing)
            }
        }
    }

    private fun promptMissingCategories(items: List<PaymentCapture>) {
        if (items.isEmpty()) return
        val preview = buildString {
            items.take(8).forEach { item ->
                append("· ").append(formatAmount(item.amountCents))
                item.merchant?.takeIf { it.isNotBlank() }?.let { append(" ").append(it.take(14)) }
                item.note?.takeIf { it.isNotBlank() }?.let {
                    append("（").append(it.take(14)).append("）")
                }
                append('\n')
            }
            if (items.size > 8) append("… 还有 ${items.size - 8} 笔")
        }
        AlertDialog.Builder(this)
            .setTitle("${items.size} 笔缺少分类，已跳过入账")
            .setMessage("以下待记仍保留在列表里，需逐笔补分类：\n\n$preview")
            .setPositiveButton("逐笔补分类") { _, _ ->
                promptMissingCategoryAt(items, 0, 0)
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
            load()
            return
        }
        val item = items[index]
        showCategoryPickerDialog(
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
        mutate { repository ->
            repository.updateStatus(item.id, CaptureStatus.IGNORED)
        }
    }

    private fun mutate(block: suspend (CaptureRepository) -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    block(CaptureApp.from(this@PendingListActivity).repository)
                }
                load()
            } catch (t: Throwable) {
                toast("操作失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * 勾选“拆分记账”后的入口：先持久化备注，再 previewSplit，最后弹确认页。
     * 取消/关闭确认页不会把待记改成已记。
     */
    private fun startSplitImport(item: PaymentCapture, noteInput: EditText) {
        val type = item.billType ?: LocalBillType.TYPE_EXPENSE
        val amountCents = item.amountCents ?: 0L
        if (amountCents <= 0L) {
            toast("金额缺失，不能拆分入账")
            return
        }
        if (type !in setOf(LocalBillType.TYPE_EXPENSE, LocalBillType.TYPE_INCOME) ||
            item.subType == LocalBillType.SUBTYPE_REFUND ||
            item.needsRefundLink
        ) {
            toast("转账/还款/退款不支持文字拆分")
            return
        }
        if (item.note.isNullOrBlank()) {
            toast("请先在备注里写「苹果30 雪梨40」再拆分")
            noteInput.requestFocus()
            return
        }

        scope.launch {
            noteSaveJobs[item.id]?.join()
            withContext(Dispatchers.IO) {
                runCatching {
                    CaptureApp.from(this@PendingListActivity).repository.updateDraft(
                        context = this@PendingListActivity,
                        id = item.id,
                        note = item.note,
                        assetName = item.assetName,
                        categoryName = item.categoryName,
                    )
                }
            }
            toast("正在按备注拆分…")
            val preview = bridge.previewSplit(
                captureId = item.id,
                note = item.note,
                totalCents = amountCents,
                billType = type,
            )
            if (!preview.ok) {
                showSplitError(preview.error, preview.message)
                return@launch
            }
            SplitConfirmSheet.show(
                activity = this@PendingListActivity,
                capture = item,
                preview = preview,
                onEdit = {
                    noteInput.requestFocus()
                    toast("已保留待记，改完备注后再点“拆分入账”")
                },
                onConfirm = { items ->
                    scope.launch { performGroupImport(item, items) }
                },
            )
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
        val result = bridge.importBillGroup(item, items, UUID.randomUUID().toString())
        if (result == null) {
            toast("拆分入账失败：敲敲记账桥接不可用，已保留待记")
            return
        }
        if (result.ok || result.duplicate) {
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
            toast("请先为该笔账单选择分类，或点顶部“智能归类”")
            showCategoryPickerDialog(item, latestSnapshot?.accountingContext)
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
                bookName = item.bookName,
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
            item.bookName?.let { put("book_name", it) }
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

    private fun simulateCapture() {
        val now = System.currentTimeMillis()
        val scopeCtx = applicationContext
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val mappedAsset = CaptureRuleMatcher.resolveAssetName(scopeCtx, "零钱")
                    val payload = CapturePayload(
                        captureId = UUID.randomUUID().toString(),
                        source = CaptureSource.WECHAT,
                        amountCents = 300L,
                        currency = "CNY",
                        merchant = "示例支出",
                        paymentMethodRaw = "零钱",
                        assetName = mappedAsset,
                        categoryName = null,
                        paidAt = now,
                        capturedAt = now,
                        note = null,
                        billType = LocalBillType.TYPE_EXPENSE,
                        subType = LocalBillType.SUBTYPE_NORMAL,
                        rawJson = "{\"simulated\":true}",
                        ruleVersion = "simulated",
                    )
                    val repo = CaptureApp.from(this@PendingListActivity).repository
                    repo.insert(payload)
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
                toast("已模拟：${CaptureSources.displayName(CaptureSource.WECHAT)} ¥3.00 示例支出（自动映射账户：${outcome.first.assetName ?: CaptureSources.displayName(CaptureSource.WECHAT)}）")
            } catch (t: Throwable) {
                toast("模拟失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun simulateWcdbSamples() {
        val now = System.currentTimeMillis()
        val mmapXml = """
            <msg>
              <appmsg>
                <title>已支付¥9.00</title>
                <des>使用招商银行储蓄卡(4852)支付￥9.00</des>
                <mmreader><template_header><display_name>同程旅行</display_name></template_header></mmreader>
                <appinfo><appname>微信支付</appname></appinfo>
              </appmsg>
            </msg>
        """.trimIndent()

        CaptureForwarder.forward(
            source = CaptureSource.WECHAT,
            rawJson = WechatEnvelope.mmapFromXml(mmapXml, now, null),
            dataType = "DATA",
            contextOverride = applicationContext,
        )
        toast("已投递真实 WCDB XML 事件，稍后自动刷新")
        scope.launch {
            delay(1_200)
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
        val assetRules: List<AssetMappingRule>,
        val categoryRules: List<CategoryRule>,
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
