package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.AlertDialog
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
import com.taostudio.tapaccounting.capture.bridge.CaptureReconciler
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
import com.taostudio.tapaccounting.capture.data.PaymentCapture
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
    private val rowTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val prefillTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private var currentTab = 0
    private var latestSnapshot: Snapshot? = null
    private var isAiBusy = false
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
            text = "${CaptureSources.all.joinToString(" · ") { it.displayName }}收件箱"
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
        val tabLabels = listOf("待记", "资产映射", "分类规则", "设置")
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
                        assetRules = db.assetMappingDao().listAll(),
                        categoryRules = db.categoryRuleDao().listAll(),
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
            1 -> ruleViews.buildAssetMappingTab(snapshot.assetRules, snapshot.accountingContext)
            2 -> ruleViews.buildCategoryRuleTab(snapshot.categoryRules, snapshot.accountingContext)
            else -> ruleViews.buildSettingsTab(
                onSimulateExpense = { simulateCapture() },
                onSimulateWcdb = { simulateWcdbSamples() },
                onTestRuleSamples = { parseSamples() },
            )
        }
        bodyFrame.addView(view)
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
            return scroll
        }

        container.addView(buildSummaryHeroCard(items))
        items.forEach { item ->
            container.addView(buildCaptureCard(item, snapshot.accountingContext))
        }
        return scroll
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
        val readyItems = items.filter { !it.categoryName.isNullOrBlank() && (it.amountCents ?: 0L) > 0L }
        val unmappedCount = items.count { it.categoryName.isNullOrBlank() }

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
            text = if (unmappedCount == 0) {
                "全部已匹配分类，可直接一键入账"
            } else {
                "已就绪 ${readyItems.size} 笔 · 待分类 $unmappedCount 笔"
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
            text = if (isAiBusy) "归类中…" else "自动归类 (${items.size})",
            textColor = CaptureUi.BRAND_DARK,
            bgColor = CaptureUi.BRAND_SOFT,
            strokeColor = CaptureUi.BRAND_BORDER,
            onClick = { if (!isAiBusy) runBatchAiClassify(items) },
        )
        batchRow.addView(
            aiBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
        )

        val canImportAll = readyItems.isNotEmpty()
        val importAllBtn = CaptureUi.button(
            activity = this,
            text = "一键入账 (${readyItems.size})",
            textColor = if (canImportAll) Color.WHITE else CaptureUi.TEXT_MUTED,
            bgColor = if (canImportAll) CaptureUi.BRAND else CaptureUi.BG_SEGMENT,
            onClick = {
                if (readyItems.isEmpty()) {
                    toast("请先点左侧“自动归类”或为账单选择分类")
                } else {
                    batchImportReadyItems(readyItems)
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

        // 第三行：无感自动保存备注框（打字 300ms 自动落库并匹配本地分类规则，无需手动点保存）
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
            setOnFocusChangeListener { _, hasFocus ->
                background = CaptureUi.roundedBg(
                    if (hasFocus) Color.WHITE else CaptureUi.BG_SUBTLE,
                    dp(9),
                    dp(1),
                    if (hasFocus) CaptureUi.BORDER_FOCUS else CaptureUi.BORDER,
                )
            }
        }
        noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val newNote = s?.toString()?.trim().orEmpty()
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
        card.addView(noteInput)

        // 第四行：操作栏（直接入账 / 悬浮窗改 / 不计）
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        actionRow.addView(
            CaptureUi.button(
                activity = this,
                text = "直接入账",
                textColor = Color.WHITE,
                bgColor = CaptureUi.BRAND,
                radiusDp = 10,
                paddingV = 8,
                textSizeSp = 13f,
            ) {
                val draftNote = noteInput.text?.toString()?.trim().orEmpty()
                directImport(
                    item.copy(
                        note = draftNote.ifBlank { item.note },
                        categoryName = currentCategory ?: item.categoryName,
                    )
                )
            },
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

    private fun showCategoryPickerDialog(item: PaymentCapture, ctx: AccountingContext?) {
        val isRefund = item.subType == LocalBillType.SUBTYPE_REFUND || item.needsRefundLink
        val targetType = if (isRefund) null else item.billType?.takeIf { it in 0..1 }
        val categories = ruleViews.buildCategoryDisplayList(ctx, targetType)
        if (categories.isEmpty()) {
            toast("尚未从敲敲记账读取到分类列表，请确认敲敲记账已安装")
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

        AlertDialog.Builder(this)
            .setTitle("选择账单分类")
            .setView(layout)
            .setItems(categories.toTypedArray()) { _, which ->
                val chosenCat = categories[which]
                mutate { repo ->
                    repo.updateDraft(
                        context = this@PendingListActivity,
                        id = item.id,
                        note = item.note,
                        assetName = item.assetName,
                        categoryName = chosenCat,
                        rememberCategoryKeyword = if (rememberBox.isChecked) candidateKeyword else null,
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ════════════════════════════════════════════════════════
    //  批量自动归类 & 批量一键入账
    // ════════════════════════════════════════════════════════

    private fun runBatchAiClassify(items: List<PaymentCapture>) {
        isAiBusy = true
        latestSnapshot?.let { renderCurrentTab(it) }
        scope.launch {
            try {
                // 先等待所有正在防抖输入的备注落库，确保发给 AI 的是最新的备注
                noteSaveJobs.values.forEach { it.join() }
                val freshItems = withContext(Dispatchers.IO) {
                    val repo = CaptureApp.from(this@PendingListActivity).repository
                    items.mapNotNull { repo.findById(it.id) }
                }
                toast("正在调用敲敲记账归类 ${freshItems.size} 笔账单…")
                val outcome = bridge.classifyCaptures(freshItems)
                if (!outcome.ok) {
                    toast("自动归类未完成：${outcome.error ?: "请检查敲敲记账 AI 设置"}")
                    return@launch
                }
                var updatedCount = 0
                if (outcome.drafts.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        val repo = CaptureApp.from(this@PendingListActivity).repository
                        for (d in outcome.drafts) {
                            val cid = d.captureId
                            val cat = d.categoryName?.takeIf { it.isNotBlank() } ?: continue
                            val existing = freshItems.firstOrNull { it.id == cid } ?: continue
                            repo.updateDraft(
                                context = this@PendingListActivity,
                                id = cid,
                                note = existing.note ?: d.remarks,
                                assetName = existing.assetName ?: d.assetName,
                                categoryName = cat,
                            )
                            updatedCount++
                        }
                    }
                }
                toast("已自动归类 $updatedCount 笔，确认后可点“一键入账”")
            } finally {
                isAiBusy = false
                load()
            }
        }
    }

    private fun batchImportReadyItems(readyItems: List<PaymentCapture>) {
        scope.launch {
            noteSaveJobs.values.forEach { it.join() }
            val bills = readyItems.map { item ->
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
                    bookName = item.bookName,
                )
            }
            val result = bridge.importBills(bills, UUID.randomUUID().toString())
            if (result == null) {
                toast("敲敲记账桥接不可用，未入账")
                return@launch
            }
            val resultsArr = result.optJSONArray("results")
            var successCount = 0
            var firstError: String? = null
            if (resultsArr != null) {
                withContext(Dispatchers.IO) {
                    val repo = CaptureApp.from(this@PendingListActivity).repository
                    for (i in 0 until resultsArr.length()) {
                        val r = resultsArr.optJSONObject(i) ?: continue
                        val cid = r.optString("captureId")
                        val st = r.optString("status")
                        if (st == ImportItemStatus.CREATED || st == ImportItemStatus.DUPLICATE) {
                            repo.updateStatus(cid, CaptureStatus.RECORDED)
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
            load()
        }
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

    private fun directImport(item: PaymentCapture) {
        val type = item.billType ?: LocalBillType.TYPE_EXPENSE
        if ((item.amountCents ?: 0L) <= 0L) {
            toast("金额缺失，不能直接入账")
            return
        }
        if (type in setOf(LocalBillType.TYPE_EXPENSE, LocalBillType.TYPE_INCOME) &&
            item.categoryName.isNullOrBlank()
        ) {
            toast("请先为该笔账单选择分类，或点顶部“自动归类”")
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
            ImportItemStatus.CREATED, ImportItemStatus.DUPLICATE -> {
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
                    val matchedCategory = CaptureRuleMatcher.resolveCategoryName(
                        context = scopeCtx,
                        merchant = "示例支出",
                        shopItem = null,
                        note = null,
                        fallbackCategory = null,
                    )
                    val payload = CapturePayload(
                        captureId = UUID.randomUUID().toString(),
                        source = CaptureSource.WECHAT,
                        amountCents = 300L,
                        currency = "CNY",
                        merchant = "示例支出",
                        paymentMethodRaw = "零钱",
                        assetName = mappedAsset,
                        categoryName = matchedCategory,
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
        val assetRules: List<AssetMappingRule>,
        val categoryRules: List<CategoryRule>,
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

    private fun sourceLabel(source: String): String = CaptureSources.displayName(source)

    private fun formatAmount(cents: Long?): String {
        if (cents == null) return "¥0.00"
        return "¥" + String.format(Locale.CHINA, "%.2f", cents / 100.0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1001

        const val SAMPLE_WECHAT_PAY =
            """{"mMap":{"description":"使用招商银行储蓄卡(4852)支付￥9.00","source":"微信支付","title":"已支付¥9.00","display_name":"同程旅行","cachedPayTools":"","cachedPayMoney":"","cachedPayShop":"","t":1770696436886}}"""

        val SAMPLES: List<Pair<String, String>> = listOf(
            "样本 1：${CaptureSources.displayName(CaptureSource.WECHAT)}支付 mMap ¥9.00 同程旅行" to SAMPLE_WECHAT_PAY,
        )
    }
}
