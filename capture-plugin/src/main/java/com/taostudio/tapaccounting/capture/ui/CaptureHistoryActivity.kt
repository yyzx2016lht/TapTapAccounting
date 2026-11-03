package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.data.CaptureEvent
import com.taostudio.tapaccounting.capture.history.AutoAccountingHistoryImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AutoAccounting 数据页的最小等价物：查看所有原始事件和规则命中情况。
 *
 * 这里不做编辑/入账；待记仍在 PendingListActivity。
 */
class CaptureHistoryActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private lateinit var summary: TextView
    private lateinit var list: LinearLayout
    private lateinit var importButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            text = "导入自动记账历史"
            isAllCaps = false
            textSize = 13f
            setOnClickListener { importHistory() }
        }
        actions.addView(importButton, LinearLayout.LayoutParams(0, dp(44), 1.4f))
        actions.addView(Button(this).apply {
            text = "刷新"
            isAllCaps = false
            textSize = 13f
            setOnClickListener { load() }
        }, LinearLayout.LayoutParams(0, dp(44), 0.8f).apply { marginStart = dp(6) })
        actions.addView(Button(this).apply {
            text = "返回"
            isAllCaps = false
            textSize = 13f
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, dp(44), 0.8f).apply { marginStart = dp(6) })
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
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
            setOnClickListener { showRaw(event) }
        }
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }

        val status = if (event.matched) "匹配" else "未匹配"
        val rule = event.ruleName?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        row.addView(TextView(this).apply {
            text = "${sourceLabel(event.source)} · $status · " +
                timeFormat.format(Date(event.receivedAt))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (event.matched) Color.parseColor("#2E7D32") else Color.parseColor("#757575"))
        })
        val details = buildString {
            append("类型：").append(typeLabel(event))
            event.amountCents?.let { append(" · ").append(formatAmount(it)) }
            event.merchant?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            append('\n').append("规则：").append(rule.removePrefix(" · ").ifBlank { "—" })
        }
        row.addView(TextView(this).apply {
            text = details
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(3), 0, 0)
        })
        return row
    }

    private fun showRaw(event: CaptureEvent) {
        val raw = event.rawJson
        AlertDialog.Builder(this)
            .setTitle("原始事件")
            .setMessage(if (raw.length <= 6000) raw else raw.take(6000) + "\n…（已截断）")
            .setPositiveButton("关闭", null)
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

    private fun sourceLabel(source: String): String = when (source.uppercase(Locale.US)) {
        "WECHAT" -> "微信"
        "ALIPAY" -> "支付宝"
        else -> source
    }

    private fun typeLabel(event: CaptureEvent): String = when {
        event.localType == null -> "待分类"
        event.localType == 0 -> "支出"
        event.localType == 1 && event.localSubType == 2 -> "退款"
        event.localType == 1 -> "收入"
        event.localType == 2 && event.localSubType == 1 -> "还款"
        event.localType == 2 -> "转账"
        else -> "未知"
    }

    private fun formatAmount(cents: Long): String =
        String.format(Locale.CHINA, "¥%.2f", cents / 100.0)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
