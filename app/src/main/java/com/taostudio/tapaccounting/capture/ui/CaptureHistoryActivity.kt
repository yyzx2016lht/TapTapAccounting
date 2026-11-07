package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.BuildConfig
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.CaptureSources
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CaptureEvent
import com.taostudio.tapaccounting.capture.history.AutoAccountingHistoryImporter
import com.taostudio.tapaccounting.capture.ingest.CaptureIngestCoordinator
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 历史数据页：查看所有原始事件、规则命中情况，支持规则编写、测试与重跑。
 */
class CaptureHistoryActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayLabelFormat = SimpleDateFormat("MM-dd EEE", Locale.getDefault())

    private lateinit var summaryTotal: TextView
    private lateinit var summaryMatched: TextView
    private lateinit var summaryToday: TextView
    private lateinit var summaryCaption: TextView
    private lateinit var list: LinearLayout
    private lateinit var importButton: TextView

    private val dataRuleViews by lazy {
        DataRuleViews(
            activity = this,
            scope = scope,
            onDataChanged = { load() },
        )
    }

    private var filterTabs: List<TextView> = emptyList()
    private var filter = FILTER_ALL
    private var loadedEvents: List<CaptureEvent> = emptyList()
    private var totalCount = 0
    private var matchedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        CaptureUi.applyLightStatusBar(this)
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildRoot(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CaptureUi.BG_PAGE)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CaptureUi.BG_SURFACE)
            setPadding(dp(4), dp(6), dp(6), dp(6))
            elevation = dp(2).toFloat()
            minimumHeight = dp(56)
        }
        topBar.addView(
            CaptureUi.iconButton(
                context = this,
                res = R.drawable.ic_chevron_left,
                tint = CaptureUi.TEXT_PRIMARY,
                contentDescription = "返回",
            ) { finish() },
        )

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(2), 0, dp(8), 0)
        }
        titleBlock.addView(TextView(this).apply {
            text = "历史数据"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        titleBlock.addView(TextView(this).apply {
            text = "原始捕获事件 · 规则匹配与重跑"
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(1), 0, 0)
        })
        topBar.addView(titleBlock)

        val rematchButton = CaptureUi.button(
            activity = this,
            text = "重跑未匹配",
            textColor = CaptureUi.BRAND_DARK,
            bgColor = CaptureUi.BRAND_SOFT,
            strokeColor = CaptureUi.BRAND_BORDER,
            radiusDp = 10,
            paddingH = 10,
            paddingV = 8,
            textSizeSp = 12f,
        ) { rematchUnmatchedEvents() }.apply {
            minimumHeight = dp(40)
        }
        topBar.addView(
            rematchButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(4) },
        )

        importButton = CaptureUi.button(
            activity = this,
            text = "导入",
            textColor = CaptureUi.TEXT_PRIMARY,
            bgColor = CaptureUi.BG_SUBTLE,
            strokeColor = CaptureUi.BORDER,
            radiusDp = 10,
            paddingH = 12,
            paddingV = 8,
            textSizeSp = 12.5f,
        ) { importHistory() }.apply {
            minimumHeight = dp(40)
        }
        topBar.addView(
            importButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(2) },
        )

        topBar.addView(
            CaptureUi.iconButton(
                context = this,
                res = R.drawable.ic_refresh,
                tint = CaptureUi.TEXT_SECONDARY,
                contentDescription = "刷新",
            ) { load() },
        )
        root.addView(topBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(32))
        }
        content.addView(buildSummaryCard())
        content.addView(buildFilterRow())

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun buildSummaryCard(): View {
        val card = CaptureUi.card(
            activity = this,
            paddingH = 16,
            paddingV = 14,
            radiusDp = 16,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addMetric(label: String, valueColor: Int): TextView {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(this).apply {
                text = "—"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(valueColor)
            }
            column.addView(value)
            column.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(CaptureUi.TEXT_MUTED)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(column)
            return value
        }

        summaryTotal = addMetric("原始事件", CaptureUi.TEXT_PRIMARY)
        summaryMatched = addMetric("规则命中", CaptureUi.SUCCESS)
        summaryToday = addMetric("今天新增", CaptureUi.BRAND_DARK)
        card.addView(row)

        summaryCaption = TextView(this).apply {
            text = "最近显示 0 条 · 点击事件查看原始 JSON"
            textSize = 11f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(summaryCaption)
        return card
    }

    private fun buildFilterRow(): View {
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = CaptureUi.roundedBg(CaptureUi.BG_SEGMENT, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        filterTabs = listOf(
            "全部" to FILTER_ALL,
            "已命中" to FILTER_MATCHED,
            "未命中" to FILTER_UNMATCHED,
        ).map { (label, value) ->
            TextView(this).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                setOnClickListener {
                    if (filter != value) {
                        filter = value
                        updateFilterTabs()
                        renderFiltered()
                    }
                }
            }.also { track.addView(it) }
        }
        updateFilterTabs()
        return track
    }

    private fun updateFilterTabs() {
        filterTabs.forEachIndexed { index, tab ->
            val selected = index == filter
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tab.setTextColor(if (selected) CaptureUi.TEXT_PRIMARY else CaptureUi.TEXT_SECONDARY)
            tab.background = if (selected) {
                CaptureUi.roundedBg(Color.WHITE, dp(9), dp(1), CaptureUi.BORDER)
            } else {
                null
            }
            tab.elevation = if (selected) dp(1).toFloat() else 0f
        }
    }

    private fun load() {
        scope.launch {
            val app = CaptureApp.from(this@CaptureHistoryActivity)
            val events = withContext(Dispatchers.IO) { app.eventRepository.listRecent(500) }
            val total = withContext(Dispatchers.IO) { app.eventRepository.count() }
            val matched = withContext(Dispatchers.IO) { app.eventRepository.countMatched() }
            totalCount = total
            matchedCount = matched
            render(events)
        }
    }

    private fun render(events: List<CaptureEvent>) {
        loadedEvents = events
        val todayCount = events.count { isToday(it.receivedAt) }

        summaryTotal.text = totalCount.toString()
        summaryMatched.text = if (totalCount > 0) {
            "${matchedCount * 100 / totalCount}%"
        } else {
            "—"
        }
        summaryToday.text = todayCount.toString()
        summaryCaption.text = if (events.isEmpty()) {
            "还没有历史事件"
        } else {
            "最近显示 ${events.size} 条 · 点击事件查看原始 JSON"
        }
        renderFiltered()
    }

    private fun renderFiltered() {
        list.removeAllViews()
        val filtered = when (filter) {
            FILTER_MATCHED -> loadedEvents.filter { it.matched }
            FILTER_UNMATCHED -> loadedEvents.filterNot { it.matched }
            else -> loadedEvents
        }

        if (filtered.isEmpty()) {
            val noEvents = loadedEvents.isEmpty()
            list.addView(
                buildEmptyCard(
                    title = if (noEvents) "还没有历史事件" else "没有符合筛选的事件",
                    desc = if (noEvents) {
                        "微信/支付宝付款后会自动留底到这里；\n也可以点右上角「导入」从自动记账导入。"
                    } else {
                        "切换到「全部」查看最近捕获的事件。"
                    },
                    actionText = if (noEvents) "导入自动记账历史" else "查看全部",
                    action = if (noEvents) {
                        { importHistory() }
                    } else {
                        {
                            filter = FILTER_ALL
                            updateFilterTabs()
                            renderFiltered()
                        }
                    },
                ),
            )
            return
        }

        val todayKey = dayKeyFormat.format(Date())
        val yesterdayKey = dayKeyFormat.format(Date(System.currentTimeMillis() - DAY_MILLIS))
        filtered.groupBy { dayKeyFormat.format(Date(it.receivedAt)) }
            .forEach { (key, dayEvents) ->
                val label = when (key) {
                    todayKey -> "今天 · ${key.substring(5)}"
                    yesterdayKey -> "昨天 · ${key.substring(5)}"
                    else -> dayLabelFormat.format(Date(dayEvents.first().receivedAt))
                }
                list.addView(dayHeader(label, dayEvents.size))
                dayEvents.forEach { event -> list.addView(renderEvent(event)) }
            }
    }

    private fun dayHeader(label: String, count: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(8), dp(2), dp(6))
        addView(TextView(this@CaptureHistoryActivity).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@CaptureHistoryActivity).apply {
            text = "$count 条"
            textSize = 11f
            setTextColor(CaptureUi.TEXT_MUTED)
        })
    }

    private fun renderEvent(event: CaptureEvent): View {
        val card = CaptureUi.card(
            activity = this,
            paddingH = 13,
            paddingV = 11,
            radiusDp = 14,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            background = CaptureUi.rippleBg(
                fillColor = CaptureUi.BG_SURFACE,
                radiusPx = dp(14),
                strokeWidthPx = dp(1),
                strokeColor = CaptureUi.BORDER,
            )
            setOnClickListener { showRaw(event) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val isWechat = event.source == CaptureSource.WECHAT
        val isAlipay = event.source == CaptureSource.ALIPAY
        topRow.addView(TextView(this).apply {
            text = sourceLabel(event.source)
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                when {
                    isWechat -> CaptureUi.WECHAT
                    isAlipay -> CaptureUi.ALIPAY
                    else -> CaptureUi.TEXT_SECONDARY
                }
            )
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = CaptureUi.roundedBg(
                when {
                    isWechat -> CaptureUi.WECHAT_SOFT
                    isAlipay -> CaptureUi.ALIPAY_SOFT
                    else -> CaptureUi.BG_SUBTLE
                },
                dp(6),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(7) }
        })

        topRow.addView(TextView(this).apply {
            text = event.merchant?.takeIf { it.isNotBlank() } ?: typeLabel(event)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        })

        val amount = event.amountCents
        val isRefund = event.localSubType == LocalBillType.SUBTYPE_REFUND
        val isIncome = event.localType == LocalBillType.TYPE_INCOME
        val isTransfer = event.localType == LocalBillType.TYPE_TRANSFER
        val prefix = when {
            isIncome || isRefund -> "+"
            isTransfer || event.localType == null -> ""
            else -> "-"
        }
        topRow.addView(TextView(this).apply {
            text = amount?.let { "$prefix${formatAmount(it)}" } ?: "—"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                when {
                    isIncome || isRefund -> CaptureUi.SUCCESS
                    amount == null -> CaptureUi.TEXT_MUTED
                    else -> CaptureUi.TEXT_PRIMARY
                }
            )
        })
        card.addView(topRow)

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val meta = buildString {
            append(timeFormat.format(Date(event.receivedAt)))
            append(" · ").append(typeLabel(event))
            event.channel?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        metaRow.addView(TextView(this).apply {
            text = meta
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        })
        metaRow.addView(TextView(this).apply {
            text = if (event.matched) "已命中" else "未命中"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (event.matched) CaptureUi.SUCCESS else CaptureUi.TEXT_SECONDARY)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = if (event.matched) {
                CaptureUi.roundedBg(CaptureUi.SUCCESS_SOFT, dp(6), dp(1), CaptureUi.SUCCESS_BORDER)
            } else {
                CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(6), dp(1), CaptureUi.BORDER)
            }
        })
        card.addView(metaRow)

        val ruleLine = buildString {
            append("规则：").append(event.ruleName?.takeIf { it.isNotBlank() } ?: "—")
            event.categoryName?.takeIf { it.isNotBlank() }?.let {
                append(" · 分类：").append(it)
            }
        }
        card.addView(TextView(this).apply {
            text = ruleLine
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(5), 0, 0)
        })
        return card
    }

    private fun buildEmptyCard(
        title: String,
        desc: String,
        actionText: String?,
        action: (() -> Unit)?,
    ): View {
        val card = CaptureUi.card(
            activity = this,
            paddingH = 22,
            paddingV = 30,
            radiusDp = 16,
        ).apply {
            gravity = Gravity.CENTER
        }

        val badge = FrameLayout(this).apply {
            background = CaptureUi.roundedBg(
                CaptureUi.BG_SUBTLE,
                dp(999),
                dp(1),
                CaptureUi.BORDER,
            )
        }
        card.addView(
            badge,
            LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            },
        )
        badge.addView(
            CaptureUi.icon(this, R.drawable.ic_history, 24, CaptureUi.TEXT_SECONDARY),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
        )

        card.addView(TextView(this).apply {
            text = title
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        card.addView(TextView(this).apply {
            text = desc
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(6), dp(6), dp(6), if (actionText != null) dp(16) else 0)
        })
        if (actionText != null && action != null) {
            card.addView(
                CaptureUi.button(
                    activity = this,
                    text = actionText,
                    textColor = CaptureUi.BRAND_DARK,
                    bgColor = CaptureUi.BRAND_SOFT,
                    strokeColor = CaptureUi.BRAND_BORDER,
                    paddingH = 18,
                    paddingV = 10,
                ) { action() },
            )
        }
        return card
    }

    private fun showRaw(event: CaptureEvent) {
        var dialog: Dialog? = null

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(16))
            background = CaptureUi.roundedBg(Color.WHITE, dp(20), dp(1), CaptureUi.BORDER)
        }
        content.addView(View(this).apply {
            setBackgroundColor(CaptureUi.BORDER)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
                bottomMargin = dp(12)
            }
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBlock.addView(TextView(this).apply {
            text = "原始事件"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        titleBlock.addView(TextView(this).apply {
            text = buildString {
                append(sourceLabel(event.source))
                append(" · ").append(timeFormat.format(Date(event.receivedAt)))
                if (event.matched) append(" · 已命中")
            }
            textSize = 11.5f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titleBlock)
        header.addView(
            CaptureUi.iconButton(
                context = this,
                res = R.drawable.ic_close,
                tint = CaptureUi.TEXT_MUTED,
                sizeDp = 36,
                iconDp = 18,
                contentDescription = "关闭",
            ) { dialog?.dismiss() },
        )
        content.addView(header)

        val raw = event.rawJson
        val shown = if (raw.length <= MAX_RAW_CHARS) {
            raw
        } else {
            raw.take(MAX_RAW_CHARS) + "\n…（已截断，复制会得到完整 JSON）"
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.35f).toInt(),
            )
            isFillViewport = false
            setPadding(0, dp(8), 0, 0)
        }
        scroll.addView(TextView(this).apply {
            text = shown
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(2), 0, dp(2), 0)
        })
        content.addView(scroll)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        val primaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        if (!event.matched) {
            primaryRow.addView(
                CaptureUi.button(
                    activity = this,
                    text = "🤖 AI 写规则",
                    textColor = Color.WHITE,
                    bgColor = CaptureUi.BRAND,
                    strokeColor = Color.TRANSPARENT,
                    radiusDp = 10,
                    paddingV = 9,
                    textSizeSp = 13f,
                ) {
                    generateRuleForEventWithAi(event, dialog)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                },
            )
            primaryRow.addView(
                CaptureUi.button(
                    activity = this,
                    text = "✍️ 手写规则",
                    textColor = CaptureUi.TEXT_PRIMARY,
                    bgColor = CaptureUi.BG_SUBTLE,
                    strokeColor = CaptureUi.BORDER,
                    radiusDp = 10,
                    paddingV = 9,
                    textSizeSp = 13f,
                ) {
                    writeRuleForEvent(event, dialog)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        } else {
            primaryRow.addView(
                CaptureUi.button(
                    activity = this,
                    text = "🧪 规则测试",
                    textColor = CaptureUi.BRAND_DARK,
                    bgColor = CaptureUi.BRAND_SOFT,
                    strokeColor = CaptureUi.BRAND_BORDER,
                    radiusDp = 10,
                    paddingV = 9,
                    textSizeSp = 13f,
                ) {
                    testEventRule(event, dialog)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                },
            )
            primaryRow.addView(
                CaptureUi.button(
                    activity = this,
                    text = "⚡ 重新生成待记",
                    textColor = Color.WHITE,
                    bgColor = CaptureUi.BRAND,
                    strokeColor = Color.TRANSPARENT,
                    radiusDp = 10,
                    paddingV = 9,
                    textSizeSp = 13f,
                ) {
                    dialog?.dismiss()
                    reIngestEvent(event)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        actions.addView(primaryRow)

        val secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        secondaryRow.addView(
            CaptureUi.button(
                activity = this,
                text = "复制 JSON",
                textColor = CaptureUi.TEXT_PRIMARY,
                bgColor = CaptureUi.BG_SUBTLE,
                strokeColor = CaptureUi.BORDER,
                radiusDp = 10,
                paddingV = 9,
                textSizeSp = 12.5f,
            ) { copyJson(raw) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            },
        )
        secondaryRow.addView(
            CaptureUi.button(
                activity = this,
                text = "关闭",
                textColor = CaptureUi.TEXT_SECONDARY,
                bgColor = CaptureUi.BG_SUBTLE,
                strokeColor = CaptureUi.BORDER,
                radiusDp = 10,
                paddingV = 9,
                textSizeSp = 12.5f,
            ) { dialog?.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(secondaryRow)

        content.addView(actions)

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dialog?.dismiss() }
        }
        container.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(10)
            },
        )
        content.setOnClickListener { }

        val sheet = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(container)
            setCanceledOnTouchOutside(true)
        }
        dialog = sheet
        sheet.show()
        sheet.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.42f)
        }
    }

    private fun generateRuleForEventWithAi(event: CaptureEvent, dialog: Dialog? = null) {
        dialog?.dismiss()
        val appPkg = AutoAccountingRuleEngine.packageForSource(event.source) ?: event.source
        dataRuleViews.generateRuleWithAi(
            sampleJson = event.rawJson,
            targetApp = appPkg,
            targetType = event.rawType.ifBlank { "DATA" },
        )
    }

    private fun writeRuleForEvent(event: CaptureEvent, dialog: Dialog? = null) {
        dialog?.dismiss()
        val appPkg = AutoAccountingRuleEngine.packageForSource(event.source) ?: event.source
        val type = event.rawType.ifBlank { "DATA" }
        val titleCandidate = extractTitle(event.rawJson)
        val defaultName = titleCandidate ?: event.merchant?.takeIf { it.isNotBlank() }?.let { "${event.source}-$it" }
            ?: "${sourceLabel(event.source)}-自定义规则"

        dataRuleViews.showRuleFormForSample(
            sampleJson = event.rawJson,
            targetApp = appPkg,
            targetType = type,
            targetName = defaultName,
        )
    }

    private fun extractTitle(rawJson: String): String? {
        return runCatching {
            val root = JSONObject(rawJson)
            val mMap = root.optJSONObject("mMap")
            mMap?.optString("title")?.takeIf { it.isNotBlank() }
                ?: root.optString("title").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun testEventRule(event: CaptureEvent, dialog: Dialog? = null) {
        dialog?.dismiss()
        val progress = AlertDialog.Builder(this)
            .setTitle("正在测试规则")
            .setMessage("正在用最新规则库运行 QuickJS 解析…")
            .setCancelable(false)
            .create()
        progress.show()

        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                AutoAccountingRuleEngine.parse(event.source, event.rawType, event.rawJson)
            }
            progress.dismiss()

            when (outcome) {
                is AutoAccountingRuleEngine.ParseOutcome.Matched -> {
                    val r = outcome.rule

                    if (!event.matched) {
                        withContext(Dispatchers.IO) {
                            val app = CaptureApp.from(this@CaptureHistoryActivity)
                            app.eventRepository.updateMatchResult(
                                id = event.id,
                                matched = true,
                                ruleName = r.ruleName,
                                autoType = r.type,
                                localType = r.localType,
                                localSubType = r.localSubType,
                                amountCents = r.amountCents,
                                merchant = r.merchant,
                                channel = r.channel,
                            )
                        }
                        load()
                    }

                    val content = buildString {
                        append("【✓ 命中规则】：").append(r.ruleName).append("\n\n")
                        append("· 账单类型：").append(r.type)
                        if (r.localType != null) {
                            append(" (本地映射: ").append(localTypeLabel(r.localType, r.localSubType)).append(")")
                        }
                        append("\n")
                        append("· 金额：").append(formatAmount(r.amountCents)).append("\n")
                        append("· 对方户名/商户：").append(r.merchant ?: "—").append("\n")
                        r.shopItem?.let { append("· 商品说明：").append(it).append("\n") }
                        r.accountNameFrom?.let { append("· 转出账户：").append(it).append("\n") }
                        r.accountNameTo?.let { append("· 转入账户：").append(it).append("\n") }
                        r.channel?.let { append("· 支付方式/渠道：").append(it).append("\n") }
                        append("· 时间戳：").append(r.paidAt)
                    }

                    AlertDialog.Builder(this@CaptureHistoryActivity)
                        .setTitle("🧪 规则测试成功")
                        .setMessage(content)
                        .setPositiveButton("知道了", null)
                        .setNeutralButton("⚡ 重新生成待记") { _, _ ->
                            reIngestEvent(event)
                        }
                        .show()
                }

                is AutoAccountingRuleEngine.ParseOutcome.NoMatch -> {
                    AlertDialog.Builder(this@CaptureHistoryActivity)
                        .setTitle("⚠️ 未命中任何规则")
                        .setMessage("当前规则库中暂无匹配此事件的规则，或规则在解析时返回 null / 异常。\n\n可以让 AI 自动分析生成规则，或手动编写。")
                        .setPositiveButton("🤖 AI 写规则") { _, _ ->
                            generateRuleForEventWithAi(event)
                        }
                        .setNeutralButton("✍️ 手写规则") { _, _ ->
                            writeRuleForEvent(event)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    private fun reIngestEvent(event: CaptureEvent) {
        scope.launch {
            val payload = JSONObject().apply {
                put("captureId", UUID.randomUUID().toString())
                put("source", event.source)
                put("rawType", event.rawType)
                put("rawJson", event.rawJson)
                put("capturedAt", event.receivedAt)
                put("paidAt", event.receivedAt)
            }
            val resultAck = withContext(Dispatchers.IO) {
                CaptureIngestCoordinator.ingest(
                    this@CaptureHistoryActivity,
                    BuildConfig.CAPTURE_INGEST_TOKEN,
                    payload.toString(),
                )
            }
            val ok = runCatching { JSONObject(resultAck).optBoolean("ok") }.getOrDefault(false)
            if (ok) {
                Toast.makeText(this@CaptureHistoryActivity, "已重新提交待记条目，请在待记列表查看", Toast.LENGTH_LONG).show()
                load()
            } else {
                Toast.makeText(this@CaptureHistoryActivity, "入库返回：$resultAck", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun rematchUnmatchedEvents() {
        val progress = AlertDialog.Builder(this)
            .setTitle("正在重跑规则")
            .setMessage("正在用最新规则库重新匹配历史未匹配事件…")
            .setCancelable(false)
            .create()
        progress.show()

        scope.launch {
            val app = CaptureApp.from(this@CaptureHistoryActivity)
            val unmatched = withContext(Dispatchers.IO) {
                app.eventRepository.listUnmatched(500)
            }
            if (unmatched.isEmpty()) {
                progress.dismiss()
                Toast.makeText(this@CaptureHistoryActivity, "没有未匹配的事件需要重跑", Toast.LENGTH_SHORT).show()
                return@launch
            }

            var newlyMatched = 0
            val matchedEvents = mutableListOf<CaptureEvent>()
            withContext(Dispatchers.IO) {
                AutoAccountingRuleEngine.init(this@CaptureHistoryActivity)
                for (event in unmatched) {
                    val outcome = AutoAccountingRuleEngine.parse(event.source, event.rawType, event.rawJson)
                    if (outcome is AutoAccountingRuleEngine.ParseOutcome.Matched) {
                        val r = outcome.rule
                        app.eventRepository.updateMatchResult(
                            id = event.id,
                            matched = true,
                            ruleName = r.ruleName,
                            autoType = r.type,
                            localType = r.localType,
                            localSubType = r.localSubType,
                            amountCents = r.amountCents,
                            merchant = r.merchant,
                            channel = r.channel,
                        )
                        newlyMatched++
                        matchedEvents.add(event)
                    }
                }
            }
            progress.dismiss()
            load()

            if (newlyMatched > 0) {
                AlertDialog.Builder(this@CaptureHistoryActivity)
                    .setTitle("重跑完成")
                    .setMessage("共扫描 ${unmatched.size} 条历史未匹配记录，成功匹配到 $newlyMatched 条！\n\n卡片已全部更新为「已命中」。是否现在将这 $newlyMatched 条账单推送到待记列表中？")
                    .setPositiveButton("全部推送待记") { _, _ ->
                        batchReIngest(matchedEvents)
                    }
                    .setNegativeButton("仅更新历史状态", null)
                    .show()
            } else {
                Toast.makeText(this@CaptureHistoryActivity, "扫描了 ${unmatched.size} 条记录，暂无新匹配", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun batchReIngest(events: List<CaptureEvent>) {
        val progress = AlertDialog.Builder(this)
            .setTitle("正在生成待记账单")
            .setMessage("正在推送…")
            .setCancelable(false)
            .create()
        progress.show()

        scope.launch {
            var successCount = 0
            withContext(Dispatchers.IO) {
                for (event in events) {
                    val payload = JSONObject().apply {
                        put("captureId", UUID.randomUUID().toString())
                        put("source", event.source)
                        put("rawType", event.rawType)
                        put("rawJson", event.rawJson)
                        put("capturedAt", event.receivedAt)
                        put("paidAt", event.receivedAt)
                    }
                    val ack = CaptureIngestCoordinator.ingest(
                        this@CaptureHistoryActivity,
                        BuildConfig.CAPTURE_INGEST_TOKEN,
                        payload.toString(),
                    )
                    if (runCatching { JSONObject(ack).optBoolean("ok") }.getOrDefault(false)) {
                        successCount++
                    }
                }
            }
            progress.dismiss()
            Toast.makeText(
                this@CaptureHistoryActivity,
                "已将 $successCount 条账单推送到待记列表",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun copyJson(raw: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("capture_event", raw))
        Toast.makeText(this, "已复制原始 JSON", Toast.LENGTH_SHORT).show()
    }

    private fun importHistory() {
        importButton.isEnabled = false
        importButton.text = "导入中…"
        scope.launch {
            val result = AutoAccountingHistoryImporter.import(this@CaptureHistoryActivity)
            importButton.isEnabled = true
            importButton.text = "导入"
            val msg = if (result.error != null) {
                "导入失败：${result.error}"
            } else {
                "读取 ${result.fetched} 条，新增 ${result.inserted} 条"
            }
            Toast.makeText(this@CaptureHistoryActivity, msg, Toast.LENGTH_LONG).show()
            load()
        }
    }

    private fun isToday(timestamp: Long): Boolean =
        dayKeyFormat.format(Date(timestamp)) == dayKeyFormat.format(Date())

    private fun sourceLabel(source: String): String = CaptureSources.displayName(source)

    private fun typeLabel(event: CaptureEvent): String = localTypeLabel(event.localType, event.localSubType)

    private fun localTypeLabel(localType: Int?, localSubType: Int?): String = when {
        localType == null -> "待分类"
        localType == 0 -> "支出"
        localType == 1 && localSubType == 2 -> "退款"
        localType == 1 -> "收入"
        localType == 2 && localSubType == 1 -> "还款"
        localType == 2 -> "转账"
        else -> "未知"
    }

    private fun formatAmount(cents: Long): String =
        String.format(Locale.CHINA, "¥%.2f", cents / 100.0)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val FILTER_ALL = 0
        const val FILTER_MATCHED = 1
        const val FILTER_UNMATCHED = 2
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        const val MAX_RAW_CHARS = 6000
    }
}
