package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.BuildConfig
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.CaptureSources
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
 * AutoAccounting 数据页的等价物：查看所有原始事件和规则命中情况。
 *
 * 支持：
 * 1. 已匹配事件：一键运行 QuickJS 规则引擎测试，核对解析字段；
 * 2. 未匹配事件：一键携带原始报文快速新建自定义规则并用该报文测试；
 * 3. 重新提交入库生成待记账单；
 * 4. 复制 / 查看原始 JSON 报文。
 */
class CaptureHistoryActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private lateinit var summary: TextView
    private lateinit var list: LinearLayout
    private lateinit var importButton: Button

    private val dataRuleViews by lazy {
        DataRuleViews(
            activity = this,
            scope = scope,
            onDataChanged = { load() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CaptureUi.applyLightStatusBar(this)
        setContentView(buildRoot())
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
            setPadding(dp(16), dp(18), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }
        root.addView(TextView(this).apply {
            text = "捕获历史"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#222222"))
        })
        summary = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#777777"))
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(summary)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        importButton = Button(this).apply {
            text = "导入历史"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { importHistory() }
        }
        val rematchButton = Button(this).apply {
            text = "🔄 重跑未匹配"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { rematchUnmatchedEvents() }
        }
        val refreshButton = Button(this).apply {
            text = "刷新"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { load() }
        }
        val backButton = Button(this).apply {
            text = "返回"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { finish() }
        }
        actions.addView(importButton, LinearLayout.LayoutParams(0, dp(44), 1.0f))
        actions.addView(rematchButton, LinearLayout.LayoutParams(0, dp(44), 1.3f).apply { marginStart = dp(4) })
        actions.addView(refreshButton, LinearLayout.LayoutParams(0, dp(44), 0.7f).apply { marginStart = dp(4) })
        actions.addView(backButton, LinearLayout.LayoutParams(0, dp(44), 0.7f).apply { marginStart = dp(4) })
        root.addView(actions)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setPadding(0, dp(10), 0, 0)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll)
        return root
    }

    private fun load() {
        scope.launch {
            val app = CaptureApp.from(this@CaptureHistoryActivity)
            val events = withContext(Dispatchers.IO) { app.eventRepository.listRecent(500) }
            val total = withContext(Dispatchers.IO) { app.eventRepository.count() }
            val matched = withContext(Dispatchers.IO) { app.eventRepository.countMatched() }
            summary.text = "共 $total 条原始事件 · 规则命中 $matched 条 · 最近显示 ${events.size} 条"
            render(events)
        }
    }

    private fun render(events: List<CaptureEvent>) {
        list.removeAllViews()
        if (events.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "还没有历史数据。\n可以从 AutoAccounting 导入，或等新的 hook 事件。"
                textSize = 14f
                setTextColor(Color.parseColor("#777777"))
                setPadding(0, dp(12), 0, 0)
            })
            return
        }
        events.forEach { event -> list.addView(renderEvent(event)) }
    }

    private fun renderEvent(event: CaptureEvent): ViewGroup {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = CaptureUi.roundedBg(Color.WHITE, dp(12), dp(1), CaptureUi.BORDER)
            setOnClickListener { showEventActionsDialog(event) }
        }
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }

        val statusText = if (event.matched) "✓ 已匹配" else "✕ 未匹配"
        val statusBg = if (event.matched) CaptureUi.SUCCESS_SOFT else CaptureUi.BG_SUBTLE
        val statusColor = if (event.matched) CaptureUi.SUCCESS else Color.parseColor("#888888")

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        headerRow.addView(TextView(this).apply {
            text = "${sourceLabel(event.source)} (${event.rawType})"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        headerRow.addView(TextView(this).apply {
            text = statusText
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(statusColor)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = CaptureUi.roundedBg(statusBg, dp(6))
        })
        row.addView(headerRow)

        val timeTv = TextView(this).apply {
            text = timeFormat.format(Date(event.receivedAt))
            textSize = 11.5f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(2), 0, dp(4))
        }
        row.addView(timeTv)

        val ruleName = event.ruleName?.takeIf { it.isNotBlank() }
        val details = buildString {
            append("类型：").append(typeLabel(event))
            event.amountCents?.let { append(" · ").append(formatAmount(it)) }
            event.merchant?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            append('\n').append("命中规则：").append(ruleName ?: "（无）")
        }
        row.addView(TextView(this).apply {
            text = details
            textSize = 13f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(2), 0, dp(8))
        })

        // 卡片底部快捷操作按钮栏
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        // 快捷按钮 0：AI 写规则（未匹配时优先作为主推荐按钮）
        if (!event.matched) {
            btnRow.addView(CaptureUi.button(
                activity = this,
                text = "🤖 AI写规则",
                textColor = Color.WHITE,
                bgColor = Color.parseColor("#1565C0"),
                strokeColor = Color.parseColor("#1565C0"),
                textSizeSp = 12f,
            ) {
                generateRuleForEventWithAi(event)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                    marginEnd = dp(6)
                }
            })
        }

        // 快捷按钮 1：测试规则
        btnRow.addView(CaptureUi.button(
            activity = this,
            text = "🧪 测试规则",
            textColor = CaptureUi.BRAND,
            bgColor = CaptureUi.BRAND_SOFT,
            strokeColor = CaptureUi.BRAND_BORDER,
            textSizeSp = 12f,
        ) {
            testEventRule(event)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        })

        // 快捷按钮 2：编写规则（未匹配或需要自定时）
        btnRow.addView(CaptureUi.button(
            activity = this,
            text = if (event.matched) "✍️ 改写规则" else "✍️ 手写规则",
            textColor = CaptureUi.TEXT_SECONDARY,
            bgColor = CaptureUi.BG_SUBTLE,
            strokeColor = CaptureUi.BORDER,
            textSizeSp = 12f,
        ) {
            writeRuleForEvent(event)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                marginStart = dp(6)
            }
        })

        row.addView(btnRow)
        return row
    }

    /**
     * 点击事件卡片弹出的完整操作菜单
     */
    private fun showEventActionsDialog(event: CaptureEvent) {
        val options = arrayOf(
            "🤖 让 AI 帮我写规则（自动生成正则与映射）",
            "🧪 运行规则引擎测试（实时验证）",
            "✍️ 为此事件手动编写规则",
            "⚡ 重新生成待记条目（推入待记箱）",
            "📋 复制原始报文（JSON）",
            "🔍 查看原始报文详情",
        )

        AlertDialog.Builder(this)
            .setTitle("${sourceLabel(event.source)} · 捕获事件操作")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> generateRuleForEventWithAi(event)
                    1 -> testEventRule(event)
                    2 -> writeRuleForEvent(event)
                    3 -> reIngestEvent(event)
                    4 -> copyToClipboard(event.rawJson)
                    5 -> showRaw(event)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 运行当前规则库测试该原始事件
     */
    private fun testEventRule(event: CaptureEvent) {
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

                    // 若该历史事件原本记录为未匹配，测试成功后自动更新数据库状态，刷新卡片为已匹配
                    if (!event.matched) {
                        withContext(Dispatchers.IO) {
                            val app = application as? CaptureApp
                            app?.eventRepository?.updateMatchResult(
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
                        append("· 商户名称：").append(r.merchant ?: "—").append("\n")
                        r.shopItem?.let { append("· 商品说明：").append(it).append("\n") }
                        r.accountNameFrom?.let { append("· 转出账户：").append(it).append("\n") }
                        r.accountNameTo?.let { append("· 转入账户：").append(it).append("\n") }
                        r.channel?.let { append("· 记账渠道：").append(it).append("\n") }
                        append("· 时间戳：").append(r.paidAt).append("\n")
                    }

                    AlertDialog.Builder(this@CaptureHistoryActivity)
                        .setTitle("✓ 规则测试成功")
                        .setMessage(content)
                        .setPositiveButton("知道了", null)
                        .setNeutralButton("⚡ 重新生成待记条目") { _, _ ->
                            reIngestEvent(event)
                        }
                        .show()
                }

                is AutoAccountingRuleEngine.ParseOutcome.NoMatch -> {
                    AlertDialog.Builder(this@CaptureHistoryActivity)
                        .setTitle("✕ 未命中任何规则")
                        .setMessage("当前规则库中暂无匹配此事件的规则，或规则在解析时返回 null / 异常。\n\n可以让 AI 自动分析生成规则，或手动编写。")
                        .setPositiveButton("🤖 让 AI 写规则") { _, _ ->
                            generateRuleForEventWithAi(event)
                        }
                        .setNeutralButton("✍️ 手写规则") { _, _ ->
                            writeRuleForEvent(event)
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }
    }

    /**
     * 调用大模型自动分析该原始事件报文，生成泛化正则与字段映射
     */
    private fun generateRuleForEventWithAi(event: CaptureEvent) {
        val appPkg = AutoAccountingRuleEngine.packageForSource(event.source) ?: event.source
        dataRuleViews.generateRuleWithAi(
            sampleJson = event.rawJson,
            targetApp = appPkg,
            targetType = event.rawType.ifBlank { "DATA" },
        )
    }

    /**
     * 为当前事件打开自定义规则表单，自动填充 app、type、name 并带入 sampleJson
     */
    private fun writeRuleForEvent(event: CaptureEvent) {
        val appPkg = AutoAccountingRuleEngine.packageForSource(event.source) ?: event.source
        val type = event.rawType.ifBlank { "DATA" }
        val titleCandidate = extractTitle(event.rawJson)
        val defaultName = titleCandidate ?: event.merchant?.takeIf { it.isNotBlank() }?.let { "${event.source}-$it" } ?: "${sourceLabel(event.source)}-自定义规则"

        dataRuleViews.showRuleFormForSample(
            sampleJson = event.rawJson,
            targetApp = appPkg,
            targetType = type,
            targetName = defaultName,
        )
    }

    /**
     * 从 rawJson 快速提取可作为规则名的标题
     */
    private fun extractTitle(rawJson: String): String? {
        return runCatching {
            val root = JSONObject(rawJson)
            val mMap = root.optJSONObject("mMap")
            mMap?.optString("title")?.takeIf { it.isNotBlank() }
                ?: root.optString("title").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 将该历史事件重新投递进待记入库流程（修复规则后直接重新入账）
     */
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

    /**
     * 一键用当前最新的规则库批量重新扫描所有未匹配历史记录
     */
    private fun rematchUnmatchedEvents() {
        val progress = AlertDialog.Builder(this)
            .setTitle("正在重跑规则")
            .setMessage("正在用最新规则库重新匹配历史未匹配事件…")
            .setCancelable(false)
            .create()
        progress.show()

        scope.launch {
            val app = application as? CaptureApp
            val unmatched = withContext(Dispatchers.IO) {
                app?.eventRepository?.listUnmatched(500).orEmpty()
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
                        app?.eventRepository?.updateMatchResult(
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
                    .setMessage("共扫描 ${unmatched.size} 条历史未匹配记录，成功匹配到 $newlyMatched 条！\n\n卡片已全部更新为「✓ 已匹配」。是否现在将这 $newlyMatched 条账单推送到待记列表中？")
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

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("原始事件", text))
        Toast.makeText(this, "原始报文已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun showRaw(event: CaptureEvent) {
        val raw = event.rawJson
        val scroll = ScrollView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val tv = TextView(this).apply {
            text = if (raw.length <= 8000) raw else raw.take(8000) + "\n…（已截断）"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setTextIsSelectable(true)
        }
        scroll.addView(tv)

        AlertDialog.Builder(this)
            .setTitle("原始报文 · ${sourceLabel(event.source)}")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制") { _, _ -> copyToClipboard(raw) }
            .show()
    }

    private fun importHistory() {
        importButton.isEnabled = false
        importButton.text = "导入中…"
        scope.launch {
            val result = AutoAccountingHistoryImporter.import(this@CaptureHistoryActivity)
            importButton.isEnabled = true
            importButton.text = "导入自动记账历史"
            val msg = if (result.error != null) {
                "导入失败：${result.error}"
            } else {
                "读取 ${result.fetched} 条，新增 ${result.inserted} 条"
            }
            Toast.makeText(this@CaptureHistoryActivity, msg, Toast.LENGTH_LONG).show()
            load()
        }
    }

    private fun sourceLabel(source: String): String = CaptureSources.displayName(source)

    private fun typeLabel(event: CaptureEvent): String = localTypeLabel(event.localType, event.localSubType)

    private fun localTypeLabel(type: Int?, subType: Int?): String = when {
        type == null -> "待分类"
        type == 0 -> "支出"
        type == 1 && subType == 2 -> "退款"
        type == 1 -> "收入"
        type == 2 && subType == 1 -> "还款"
        type == 2 -> "转账"
        else -> "未知"
    }

    private fun formatAmount(cents: Long): String =
        String.format(Locale.CHINA, "¥%.2f", cents / 100.0)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
