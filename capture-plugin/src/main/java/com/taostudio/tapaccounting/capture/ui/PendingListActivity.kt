package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.R
import com.taostudio.tapaccounting.capture.bridge.CaptureBridgeClient
import com.taostudio.tapaccounting.capture.bridge.CaptureReconciler
import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import com.taostudio.tapaccounting.capture.hook.CaptureForwarder
import com.taostudio.tapaccounting.capture.hook.WechatEnvelope
import com.taostudio.tapaccounting.capture.hook.WechatRichCache
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * P0 待记列表：纯 android.app.Activity + ScrollView/LinearLayout 动态行。
 *
 * 刻意不用 RecyclerView / Compose / AppCompat，保持插件骨架最小、可独立构建。
 */
class PendingListActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge by lazy { CaptureBridgeClient(this) }
    private val rowTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val prefillTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private lateinit var countView: TextView
    private lateinit var bridgeView: TextView
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "待记"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
        }
        countView = TextView(this).apply {
            text = "共 0 笔 · 待记 0 笔"
            textSize = 14f
            setTextColor(Color.parseColor("#616161"))
            setPadding(0, dp(4), 0, dp(10))
        }
        bridgeView = TextView(this).apply {
            text = "桥接：检测中…"
            textSize = 13f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 0, 0, dp(10))
        }
        val simulate = Button(this).apply {
            text = "模拟一笔"
            isAllCaps = false
            textSize = 14f
            minHeight = 0
            minimumHeight = dp(40)
            setOnClickListener { simulateCapture() }
        }
        val parseSample = Button(this).apply {
            text = "解析样本"
            isAllCaps = false
            textSize = 14f
            minHeight = 0
            minimumHeight = dp(40)
            setOnClickListener { parseSamples() }
        }
        val simulateWcdb = Button(this).apply {
            text = "模拟WCDB"
            isAllCaps = false
            textSize = 13f
            minHeight = 0
            minimumHeight = dp(40)
            setOnClickListener { simulateWcdbSamples() }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(
            simulate,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) },
        )
        actions.addView(
            parseSample,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) },
        )
        actions.addView(
            simulateWcdb,
            LinearLayout.LayoutParams(0, dp(40), 1f),
        )
        header.addView(title)
        header.addView(countView)
        header.addView(bridgeView)
        header.addView(actions)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dp(10), 0, 0)
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)

        val refresh = Button(this).apply {
            text = "刷新"
            isAllCaps = false
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(6) }
            setOnClickListener { load() }
        }

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(scroll)
        root.addView(refresh)
        return root
    }

    private fun buildRow(item: PaymentCapture): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = roundedBackground(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }

        row.addView(TextView(this).apply {
            text = "${sourceLabel(item.source)} · ${billTypeLabel(item)} · " +
                "${rowTimeFormat.format(Date(item.paidAt))}    ${formatAmount(item.amountCents)}"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
        })

        val merchantLine = buildString {
            append("商户：").append(item.merchant ?: "—")
            item.paymentMethodRaw?.let { append("　支付方式：").append(it) }
        }
        row.addView(secondaryText(merchantLine))
        row.addView(secondaryText("备注：${item.note ?: "—"}"))

        val statusLine = buildString {
            append("状态：").append(statusLabel(item.status))
            if (item.needsAmount) append("　· 待补金额")
            if (item.billType == null) append("　· 待手动选择类型")
            if (isRefund(item)) append("　· 待关联原支出")
            item.assetName?.let { append("　· 资产：").append(it) }
        }
        row.addView(secondaryText(statusLine))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val actionable = item.status != CaptureStatus.RECORDED &&
            item.status != CaptureStatus.IGNORED &&
            item.status != CaptureStatus.DUPLICATE
        actions.addView(actionButton("自己记", actionable) { openQuickRecord(item) })
        actions.addView(actionButton("备注", true) { editNote(item) })
        actions.addView(actionButton("忽略", actionable) { ignore(item) })
        row.addView(actions)

        return row
    }

    private fun secondaryText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#616161"))
        setPadding(0, dp(4), 0, 0)
    }

    private fun actionButton(text: String, enabled: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            minHeight = 0
            minimumHeight = dp(38)
            isEnabled = enabled
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onClick() }
        }

    // ---------------- 数据 ----------------

    private fun load() {
        scope.launch {
            try {
                val repository = CaptureApp.from(this@PendingListActivity).repository
                val snapshot = withContext(Dispatchers.IO) {
                    // 先对账再渲染：App 已写入回执的条目不必再显示为待记。
                    val items = repository.list(200)
                    val changed = CaptureReconciler.reconcile(repository, bridge, items)
                    val rendered = if (changed > 0) repository.list(200) else items
                    Snapshot(
                        items = rendered,
                        pendingCount = repository.countPending(),
                        bridgeAvailable = bridge.ping(),
                    )
                }
                render(snapshot.items, snapshot.pendingCount)
                updateBridgeStatus(snapshot.bridgeAvailable)
                CaptureNotifier.refresh(this@PendingListActivity, snapshot.pendingCount)
            } catch (t: Throwable) {
                updateBridgeStatus(false)
                toast("读取待记失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun updateBridgeStatus(available: Boolean) {
        bridgeView.text =
            if (available) "桥接：已连接" else "桥接：不可用（列表仍可离线查看）"
        bridgeView.setTextColor(Color.parseColor(if (available) "#2E7D32" else "#C62828"))
    }

    private fun render(items: List<PaymentCapture>, pendingCount: Int) {
        val totalCents = items
            .filter { it.status in CaptureStatus.PENDING_COUNT_STATUSES }
            .mapNotNull { it.amountCents }
            .sum()
        countView.text = "共 ${items.size} 笔 · 待记 $pendingCount 笔 · 合计 ${formatAmount(totalCents)}"

        listContainer.removeAllViews()
        if (items.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "还没有待记。点“模拟一笔”验证链路，或等微信/支付宝 hook 投递。"
                textSize = 14f
                setTextColor(Color.parseColor("#757575"))
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
            return
        }
        items.forEach { listContainer.addView(buildRow(it)) }
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
            is AutoAccountingRuleEngine.ParseOutcome.NoMatch ->
                "$label\n→ 未命中任何启用规则"

            is AutoAccountingRuleEngine.ParseOutcome.Matched -> {
                val parsed = outcome.rule
                buildString {
                    append(label).append('\n')
                    append("规则：").append(parsed.ruleName).append('\n')
                    append("AutoAccounting：").append(parsed.type).append(" → 本地：")
                        .append(localTypeLabel(parsed.localType, parsed.localSubType))
                    if (parsed.needsRefundLink) append("（需关联原支出）")
                    append('\n')
                    append("金额：").append(formatAmount(parsed.amountCents))
                        .append("（").append(parsed.amountCents).append(" 分）").append('\n')
                    append("商户：").append(parsed.merchant ?: "—").append('\n')
                    append("支付方式：").append(parsed.accountNameFrom ?: "—").append('\n')
                    append("时间：").append(prefillTimeFormat.format(Date(parsed.paidAt))).append('\n')
                    append("渠道：").append(parsed.channel ?: "—")
                }
            }
        }
    } catch (t: Throwable) {
        "$label\n→ 失败：${t.javaClass.simpleName}: ${t.message}"
    }

    private fun simulateCapture() {
        val now = System.currentTimeMillis()
        val payload = CapturePayload(
            captureId = UUID.randomUUID().toString(),
            source = CaptureSource.WECHAT,
            amountCents = 300L,
            currency = "CNY",
            merchant = "菜摊",
            paymentMethodRaw = "零钱",
            paidAt = now,
            capturedAt = now,
            note = null,
            billType = LocalBillType.TYPE_EXPENSE,
            subType = LocalBillType.SUBTYPE_NORMAL,
            rawJson = "{\"simulated\":true}",
            ruleVersion = "simulated",
        )
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CaptureApp.from(this@PendingListActivity).repository.insert(payload)
                }
                toast("已插入模拟待记：微信 ¥3.00 菜摊")
                load()
            } catch (t: Throwable) {
                toast("插入失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * 模拟两条与 AutoAccounting 规则完全同形的微信 WCDB 事件：
     * - mMap 9.00 同程旅行；
     * - transfer 1.00 吴希城6.4。
     * 走真实 CaptureForwarder -> IngestProvider -> QuickJS 规则引擎，
     * 用来在备用机上验证“hook envelope + 规则 + 入库”整条链路。
     */
    private fun simulateWcdbSamples() {
        val now = System.currentTimeMillis()

        // 用真实 WCDB XML 形状，走 WechatEnvelope -> 规则引擎，而不是直接塞 mMap JSON。
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

        val transferXml = """
            <msg>
              <appmsg>
                <wcpayinfo>
                  <feedesc>￥1.00</feedesc>
                  <pay_memo></pay_memo>
                  <paysubtype>1</paysubtype>
                  <transferid>sim_${now}</transferid>
                </wcpayinfo>
              </appmsg>
            </msg>
        """.trimIndent()

        val transferCache = WechatRichCache.Snapshot(
            payMoney = "￥1.00",
            payTools = "招商银行储蓄卡",
            payShop = "向吴希城6.4(**城)转账",
            at = now,
        )

        CaptureForwarder.forward(
            source = CaptureSource.WECHAT,
            rawJson = WechatEnvelope.mmapFromXml(mmapXml, now, null),
            dataType = "DATA",
            contextOverride = applicationContext,
        )
        CaptureForwarder.forward(
            source = CaptureSource.WECHAT,
            rawJson = WechatEnvelope.transferFromXml(
                transferXml,
                isSend = 1,
                cache = transferCache,
                talker = "吴希城6.4",
                now = now + 1,
            ),
            dataType = "DATA",
            contextOverride = applicationContext,
        )
        toast("已投递两条模拟 WCDB XML 事件，稍后自动刷新")
        scope.launch {
            delay(2_000)
            load()
        }
    }

    private fun editNote(item: PaymentCapture) {
        val input = EditText(this).apply {
            setText(item.note ?: "")
            hint = "例如：青菜"
            isSingleLine = false
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle("备注")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                applyNote(item.id, input.text?.toString().orEmpty())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyNote(id: String, note: String) {
        mutate { repository ->
            repository.updateNote(id, note)
        }
    }

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

    // ---------------- 自己记 ----------------

    private fun openQuickRecord(item: PaymentCapture) {
        // 退款不能按普通收入入账；阶段一还没有“关联原支出”的入口，
        // 宁可留待记，也不从悬浮窗生成一条无关联的普通收入。
        if (isRefund(item)) {
            AlertDialog.Builder(this)
                .setTitle("退款待关联")
                .setMessage(
                    "这笔是退款，必须先关联原支出账单。当前阶段还不支持从待记直接关联，" +
                        "请先在 App 里手动记这笔退款并选择原账单。",
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val prefill = JSONObject().apply {
            // 类型未知时不写 type，避免把待分类强行打开成支出；由用户在悬浮窗选择。
            item.billType?.let { type ->
                put("type", type)
                item.subType?.let { put("subType", it) }
            }
            put("amount", (item.amountCents ?: 0L) / 100.0)
            put("currency", item.currency)
            item.assetName?.let { put("asset_name", it) }
            put("time", prefillTimeFormat.format(Date(item.paidAt)))
            put("remarks", item.note ?: "")
            item.bookName?.let { put("book_name", it) }
        }
        val requestId = UUID.randomUUID().toString()

        scope.launch {
            // CaptureBridgeClient 内部切 IO；App 未安装/无权限时返回 false，不会崩。
            val accepted = bridge.openQuickRecord(prefill, item.id, requestId)
            if (accepted) {
                updateBridgeStatus(true)
                when {
                    item.billType == null ->
                        toast("已发送到敲敲记账；类型待确认，请在悬浮窗手动选择")
                    item.billType == LocalBillType.TYPE_TRANSFER ->
                        toast("已发送到敲敲记账；请在悬浮窗确认转账/还款类型与两端资产")
                    else -> toast("已发送到敲敲记账，请在悬浮窗确认")
                }
            } else {
                updateBridgeStatus(false)
                toast("敲敲记账未安装、未授权悬浮窗或桥接不可用")
            }
        }
    }

    // ---------------- 小工具 ----------------

    private data class Snapshot(
        val items: List<PaymentCapture>,
        val pendingCount: Int,
        val bridgeAvailable: Boolean,
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

    private fun sourceLabel(source: String): String = when (source) {
        CaptureSource.WECHAT -> "微信"
        CaptureSource.ALIPAY -> "支付宝"
        else -> source
    }

    private fun statusLabel(status: String): String = when (status) {
        CaptureStatus.PENDING -> "待记"
        CaptureStatus.AI_DRAFT -> "AI 草稿"
        CaptureStatus.IMPORTING -> "导入中"
        CaptureStatus.RECORDED -> "已记"
        CaptureStatus.IGNORED -> "已忽略"
        CaptureStatus.POSSIBLE_DUPLICATE -> "可能已记"
        CaptureStatus.DUPLICATE -> "重复"
        CaptureStatus.FAILED -> "失败"
        else -> status
    }

    private fun billTypeLabel(item: PaymentCapture): String =
        localTypeLabel(item.billType, item.subType)

    private fun isRefund(item: PaymentCapture): Boolean =
        item.needsRefundLink ||
            (item.billType == LocalBillType.TYPE_INCOME &&
                item.subType == LocalBillType.SUBTYPE_REFUND)

    private fun localTypeLabel(type: Int?, subType: Int?): String = when {
        type == null -> "待分类"
        type == LocalBillType.TYPE_EXPENSE -> "支出"
        type == LocalBillType.TYPE_INCOME && subType == LocalBillType.SUBTYPE_REFUND -> "退款待关联"
        type == LocalBillType.TYPE_INCOME -> "收入"
        type == LocalBillType.TYPE_TRANSFER && subType == LocalBillType.SUBTYPE_REPAYMENT -> "还款"
        type == LocalBillType.TYPE_TRANSFER -> "转账"
        type == LocalBillType.TYPE_REPAYMENT -> "还款（旧）"
        else -> "待分类"
    }

    private fun formatAmount(cents: Long?): String {
        if (cents == null) return "金额待补"
        return "¥" + String.format(Locale.CHINA, "%.2f", cents / 100.0)
    }

    private fun roundedBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1001

        /** 真机样本：微信支付 mMap（同程旅行 ¥9.00）。 */
        const val SAMPLE_WECHAT_PAY =
            """{"mMap":{"description":"使用招商银行储蓄卡(4852)支付￥9.00","source":"微信支付","title":"已支付¥9.00","display_name":"同程旅行","cachedPayTools":"","cachedPayMoney":"","cachedPayShop":"","t":1770696436886}}"""

        /** 真机样本：微信转账（吴希城6.4 ¥1.00）。 */
        const val SAMPLE_WECHAT_TRANSFER =
            """{"type":"transfer","isSend":1,"content":"{\"msg\":{\"appmsg\":{\"wcpayinfo\":{\"feedesc\":\"￥1.00\",\"paysubtype\":\"1\",\"pay_memo\":\"\"}}}}","hookerUser":"吴希城6.4","cachedPayTools":"招商银行储蓄卡","cachedPayMoney":"￥1.00","cachedPayShop":"向吴希城6.4(吴城)转账","t":1770711541951}"""

        val SAMPLES: List<Pair<String, String>> = listOf(
            "样本 1：微信支付 mMap ¥9.00 同程旅行" to SAMPLE_WECHAT_PAY,
            "样本 2：微信转账 ¥1.00 吴希城6.4" to SAMPLE_WECHAT_TRANSFER,
        )
    }
}
