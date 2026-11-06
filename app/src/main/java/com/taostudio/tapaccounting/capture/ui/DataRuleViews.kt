package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.capture.bridge.CaptureBridgeClient
import com.taostudio.tapaccounting.capture.data.RuleBundleCodec
import com.taostudio.tapaccounting.capture.data.RuleBundleStore
import com.taostudio.tapaccounting.capture.history.AutoAccountingRuleSync
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import com.taostudio.tapaccounting.capture.parse.RuleJsBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * 规则页 -> 数据规则分段。
 *
 * 系统规则只读（可测试、可复制、可启停）；用户规则可编辑/删除。
 * 新建/编辑弹窗按字段生成 AutoAccounting 规则 JS，也可直接改原始 JS。
 */
internal class DataRuleViews(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val onDataChanged: () -> Unit,
    private val onExport: () -> Unit = {},
    private val onImport: () -> Unit = {},
) {
    private val density = activity.resources.displayMetrics.density
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private var visibleLimit = 50

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    enum class RuleCategory(val id: String, val title: String, val emoji: String) {
        ALL("all", "全部", "📋"),
        USER("user", "我的规则", "⭐"),
        WECHAT("wechat", "微信", "🟢"),
        ALIPAY("alipay", "支付宝", "🔵"),
        SMS("sms", "短信动账", "💬"),
        BANK("bank", "手机银行", "🏦"),
        COMMERCE("commerce", "电商生活", "🛍️"),
        UNIONPAY("unionpay", "云闪付/数币", "💳"),
        OTHER("other", "其他", "📦");

        fun matches(rule: RuleBundleCodec.Rule): Boolean {
            if (this == ALL) return true
            if (this == USER) return rule.isUser
            if (rule.isUser) return false
            val app = rule.app.lowercase(Locale.US)
            return when (this) {
                WECHAT -> app == "com.tencent.mm"
                ALIPAY -> app == "com.eg.android.alipaygphone"
                SMS -> app == "com.android.phone"
                UNIONPAY -> app == "com.unionpay" || app == "cn.gov.pbc.dcep" || app.contains("wallet") || app.contains("tsmclient")
                BANK -> app.contains("bank") || app.contains("cmb") || app.contains("icbc") || app.contains("chinamworld") || app.contains("spdb") || app.contains("yitong") || app.contains("cib")
                COMMERCE -> app.contains("meituan") || app.contains("jd") || app.contains("jingdong") || app.contains("pinduoduo") || app.contains("didi") || app.contains("aweme") || app.contains("lifeservices")
                OTHER -> !WECHAT.matches(rule) && !ALIPAY.matches(rule) && !SMS.matches(rule) && !UNIONPAY.matches(rule) && !BANK.matches(rule) && !COMMERCE.matches(rule)
                else -> false
            }
        }
    }

    fun buildDataRuleTab(bundle: RuleBundleCodec.Bundle): View {
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(28))
        }
        scroll.addView(container)

        container.addView(buildHeaderCard(bundle))

        val searchBox = EditText(activity).apply {
            hint = "🔍 搜索规则名、应用包名、来源（内置/我的/导入）"
            textSize = 13f
            isSingleLine = true
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setHintTextColor(CaptureUi.TEXT_MUTED)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = CaptureUi.roundedBg(Color.WHITE, dp(12), dp(1), CaptureUi.BORDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        container.addView(searchBox)

        var selectedCategory = RuleCategory.ALL

        val chipScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        val chipContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        chipScroll.addView(chipContainer)
        container.addView(chipScroll)

        val listHost = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listHost)

        lateinit var updateChips: () -> Unit

        fun renderList(query: String) {
            listHost.removeAllViews()
            val q = query.trim()
            val filtered = RuleBundleCodec.orderedForEngine(
                bundle.rules.filter { rule ->
                    selectedCategory.matches(rule) && (
                        q.isEmpty() ||
                            rule.name.contains(q, ignoreCase = true) ||
                            rule.app.contains(q, ignoreCase = true) ||
                            rule.systemRuleName.contains(q, ignoreCase = true) ||
                            rule.typeLabel.contains(q, ignoreCase = true) ||
                            rule.sourceLabel.contains(q, ignoreCase = true)
                    )
                },
            )
            if (filtered.isEmpty()) {
                listHost.addView(buildEmptyHint("没有匹配的数据规则"))
                return
            }
            val shown = filtered.take(visibleLimit)
            val userRules = shown.filter { it.isUser }
            val systemRules = shown.filterNot { it.isUser }
            if (userRules.isNotEmpty()) {
                val userCount = if (selectedCategory == RuleCategory.USER) filtered.size else userRules.size
                listHost.addView(sectionTitle("我的规则 ($userCount)", "优先执行 · 可编辑/删除"))
                userRules.forEach { listHost.addView(buildRuleCard(it, bundle)) }
            }
            if (systemRules.isNotEmpty()) {
                val catLabel = if (selectedCategory == RuleCategory.ALL) "系统规则" else "${selectedCategory.emoji} ${selectedCategory.title}规则"
                listHost.addView(sectionTitle("$catLabel (${filtered.size - userRules.size})", "只读 · 可测试/复制/启停"))
                systemRules.forEach { listHost.addView(buildRuleCard(it, bundle)) }
            }
            if (filtered.size > shown.size) {
                listHost.addView(
                    CaptureUi.button(
                        activity = activity,
                        text = "显示更多（还有 ${filtered.size - shown.size} 条）",
                        textColor = CaptureUi.TEXT_SECONDARY,
                        bgColor = CaptureUi.BG_SUBTLE,
                        strokeColor = CaptureUi.BORDER,
                        textSizeSp = 12.5f,
                    ) {
                        visibleLimit += 100
                        renderList(searchBox.text?.toString().orEmpty())
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(4) }
                    },
                )
            }
        }

        updateChips = {
            chipContainer.removeAllViews()
            RuleCategory.values().forEach { cat ->
                val count = when (cat) {
                    RuleCategory.ALL -> bundle.rules.size
                    RuleCategory.USER -> bundle.rules.count { it.isUser }
                    else -> bundle.rules.count { cat.matches(it) }
                }
                if (count == 0 && cat != RuleCategory.ALL && cat != RuleCategory.USER) return@forEach

                val isSelected = cat == selectedCategory
                val chip = TextView(activity).apply {
                    text = "${cat.emoji} ${cat.title} ($count)"
                    textSize = 12f
                    setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isSelected) Color.WHITE else CaptureUi.TEXT_PRIMARY)
                    val bgColor = if (isSelected) CaptureUi.BRAND else Color.parseColor("#EFEFEF")
                    background = CaptureUi.roundedBg(bgColor, dp(14), 0, 0)
                    setPadding(dp(11), dp(5), dp(11), dp(5))
                    setOnClickListener {
                        selectedCategory = cat
                        updateChips()
                        visibleLimit = 50
                        renderList(searchBox.text?.toString().orEmpty())
                    }
                }
                chipContainer.addView(chip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) })
            }
        }
        updateChips()

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                visibleLimit = 50
                renderList(s?.toString().orEmpty())
            }
        })
        renderList("")
        return scroll
    }

    // ════════════════════════════════════════════════════════
    //  页头与操作
    // ════════════════════════════════════════════════════════

    private fun buildHeaderCard(bundle: RuleBundleCodec.Bundle): View {
        val card = CaptureUi.card(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        card.addView(TextView(activity).apply {
            text = "数据规则（${bundle.rules.size} 条）"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        card.addView(TextView(activity).apply {
            text = "我的 ${bundle.userCount} · 系统 ${bundle.systemCount} · 启用 ${bundle.enabledCount}。" +
                "付款解析先跑「我的」规则，再跑系统/导入规则。"
            textSize = 12.5f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(4), 0, dp(2))
        })
        card.addView(TextView(activity).apply {
            text = bundleSourceLine(bundle)
            textSize = 11.5f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, 0, 0, dp(12))
        })

        val row1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(
            CaptureUi.button(
                activity = activity,
                text = "+ 新建规则",
                textColor = Color.WHITE,
                bgColor = CaptureUi.BRAND,
            ) { showRuleForm(null, bundle) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        card.addView(row1)

        val row2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row2.addView(
            CaptureUi.button(
                activity = activity,
                text = "导入 JSON",
                textColor = CaptureUi.TEXT_SECONDARY,
                bgColor = CaptureUi.BG_SUBTLE,
                strokeColor = CaptureUi.BORDER,
            ) { onImport() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
        )
        row2.addView(
            CaptureUi.button(
                activity = activity,
                text = "导出当前规则",
                textColor = CaptureUi.TEXT_SECONDARY,
                bgColor = CaptureUi.BG_SUBTLE,
                strokeColor = CaptureUi.BORDER,
            ) { onExport() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(row2)
        return card
    }

    private fun bundleSourceLine(bundle: RuleBundleCodec.Bundle): String {
        val source = when (bundle.source) {
            RuleBundleCodec.SOURCE_BUILTIN -> "APK 内置规则"
            RuleBundleCodec.SOURCE_LOCAL -> "本机规则（同步/自定义）"
            RuleBundleCodec.SOURCE_IMPORT -> "导入的规则文件"
            else -> bundle.sourceLabel
        }
        return if (bundle.date > 0L) {
            "来源：$source · 更新于 ${dateFormat.format(Date(bundle.date))}"
        } else {
            "来源：$source · 尚未生成本机规则文件，同步/新建后自动生成"
        }
    }

    private fun sectionTitle(title: String, subtitle: String): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(6))
            addView(TextView(activity).apply {
                text = title
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_SECONDARY)
            })
            addView(TextView(activity).apply {
                text = "  $subtitle"
                textSize = 11f
                setTextColor(CaptureUi.TEXT_MUTED)
            })
        }

    // ════════════════════════════════════════════════════════
    //  规则卡片
    // ════════════════════════════════════════════════════════

    private fun buildRuleCard(rule: RuleBundleCodec.Rule, bundle: RuleBundleCodec.Bundle): View {
        val card = CaptureUi.card(activity, paddingH = 14, paddingV = 12, radiusDp = 14).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(9) }
        }

        var currentRule = rule
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = CaptureUi.rippleBg(Color.TRANSPARENT, dp(8))
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(activity).apply {
            text = currentRule.name
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (currentRule.enabled) CaptureUi.TEXT_PRIMARY else CaptureUi.TEXT_MUTED)
        }
        titleCol.addView(titleTv)
        titleCol.addView(TextView(activity).apply {
            text = "${currentRule.app} · ${currentRule.typeLabel} · ${currentRule.systemRuleName}"
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        topRow.addView(titleCol)

        lateinit var statusChip: TextView
        val sw = Switch(activity).apply {
            isChecked = currentRule.enabled
        }
        topRow.addView(sw)
        topRow.setOnClickListener { sw.toggle() }
        card.addView(topRow)

        val chipRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(2))
        }
        chipRow.addView(
            CaptureUi.chip(
                activity = activity,
                text = currentRule.sourceLabel,
                textColor = sourceTextColor(currentRule.source),
                bgColor = sourceBgColor(currentRule.source),
                strokeColor = sourceBorderColor(currentRule.source),
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            },
        )
        fun styleStatusChip(tv: TextView, enabled: Boolean) {
            tv.text = if (enabled) "已启用" else "已停用"
            tv.setTextColor(if (enabled) CaptureUi.SUCCESS else CaptureUi.TEXT_MUTED)
            tv.background = CaptureUi.roundedBg(
                fillColor = if (enabled) CaptureUi.SUCCESS_SOFT else CaptureUi.BG_SUBTLE,
                radiusPx = dp(8),
                strokeWidthPx = dp(1),
                strokeColor = if (enabled) CaptureUi.SUCCESS_BORDER else CaptureUi.BORDER,
            )
        }
        statusChip = CaptureUi.chip(
            activity = activity,
            text = if (currentRule.enabled) "已启用" else "已停用",
            textColor = if (currentRule.enabled) CaptureUi.SUCCESS else CaptureUi.TEXT_MUTED,
            bgColor = if (currentRule.enabled) CaptureUi.SUCCESS_SOFT else CaptureUi.BG_SUBTLE,
            strokeColor = if (currentRule.enabled) CaptureUi.SUCCESS_BORDER else CaptureUi.BORDER,
        )
        chipRow.addView(statusChip)
        card.addView(chipRow)

        sw.setOnCheckedChangeListener { _, checked ->
            if (checked != currentRule.enabled) {
                val old = currentRule.enabled
                currentRule = currentRule.copy(enabled = checked)
                titleTv.setTextColor(if (checked) CaptureUi.TEXT_PRIMARY else CaptureUi.TEXT_MUTED)
                styleStatusChip(statusChip, checked)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            RuleBundleStore.setEnabled(activity, currentRule, checked)
                        }
                        toast(if (checked) "已启用「${currentRule.name}」" else "已停用「${currentRule.name}」")
                    } catch (t: Throwable) {
                        currentRule = currentRule.copy(enabled = old)
                        sw.isChecked = old
                        titleTv.setTextColor(if (old) CaptureUi.TEXT_PRIMARY else CaptureUi.TEXT_MUTED)
                        styleStatusChip(statusChip, old)
                        toast("保存启停失败：${t.message ?: t.javaClass.simpleName}")
                    }
                }
            }
        }

        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
        }
        if (rule.isUser) {
            if (rule.meta != null) {
                addAction(actionRow, "编辑", CaptureUi.BRAND_DARK, CaptureUi.BRAND_SOFT, CaptureUi.BRAND_BORDER) {
                    showRuleForm(rule, bundle)
                }
            }
            addAction(actionRow, "原始 JS", CaptureUi.TEXT_SECONDARY, CaptureUi.BG_SUBTLE, CaptureUi.BORDER) {
                showRawJsEditor(rule, bundle)
            }
        }
        addAction(actionRow, "测试", CaptureUi.TEXT_SECONDARY, CaptureUi.BG_SUBTLE, CaptureUi.BORDER) {
            showRuleTestDialog(rule, bundle.common)
        }
        if (!rule.isUser) {
            addAction(actionRow, "复制为我的", CaptureUi.SUCCESS, CaptureUi.SUCCESS_SOFT, CaptureUi.SUCCESS_BORDER) {
                copyAsMine(rule, bundle)
            }
        } else {
            addAction(actionRow, "删除", CaptureUi.DANGER, CaptureUi.DANGER_SOFT, CaptureUi.DANGER_BORDER) {
                confirmDelete(rule)
            }
        }
        card.addView(actionRow)
        return card
    }

    private fun addAction(
        row: LinearLayout,
        text: String,
        textColor: Int,
        bgColor: Int,
        strokeColor: Int,
        onClick: () -> Unit,
    ) {
        row.addView(
            CaptureUi.chip(
                activity = activity,
                text = text,
                textColor = textColor,
                bgColor = bgColor,
                strokeColor = strokeColor,
                onClick = onClick,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            },
        )
    }

    private fun sourceTextColor(source: String): Int = when (source) {
        RuleBundleCodec.SOURCE_USER -> CaptureUi.BRAND_DARK
        RuleBundleCodec.SOURCE_AI -> CaptureUi.WARNING
        RuleBundleCodec.SOURCE_IMPORT -> CaptureUi.SUCCESS
        else -> CaptureUi.TEXT_SECONDARY
    }

    private fun sourceBgColor(source: String): Int = when (source) {
        RuleBundleCodec.SOURCE_USER -> CaptureUi.BRAND_SOFT
        RuleBundleCodec.SOURCE_AI -> CaptureUi.WARNING_SOFT
        RuleBundleCodec.SOURCE_IMPORT -> CaptureUi.SUCCESS_SOFT
        else -> CaptureUi.BG_SUBTLE
    }

    private fun sourceBorderColor(source: String): Int = when (source) {
        RuleBundleCodec.SOURCE_USER -> CaptureUi.BRAND_BORDER
        RuleBundleCodec.SOURCE_AI -> CaptureUi.WARNING_BORDER
        RuleBundleCodec.SOURCE_IMPORT -> CaptureUi.SUCCESS_BORDER
        else -> CaptureUi.BORDER
    }

    // ════════════════════════════════════════════════════════
    //  同步 / 复制 / 删除
    // ════════════════════════════════════════════════════════

    internal fun syncFromLocal() {
        val progress = AlertDialog.Builder(activity)
            .setTitle("正在同步本机规则")
            .setMessage("正在读取自动记账 127.0.0.1:52045 的数据规则与 common 脚本…")
            .setCancelable(false)
            .create()
        progress.show()

        scope.launch {
            val result = try {
                AutoAccountingRuleSync.sync(activity)
            } catch (t: Throwable) {
                AutoAccountingRuleSync.Result(
                    success = false,
                    error = "同步失败：${t.message ?: t.javaClass.simpleName}\n\n请先打开自动记账。",
                )
            } finally {
                runCatching { progress.dismiss() }
            }
            if (result.success) {
                AlertDialog.Builder(activity)
                    .setTitle("同步成功")
                    .setMessage(
                        "已同步 ${result.ruleCount} 条系统规则。\n" +
                            "保留我的自定义规则 ${result.userCount} 条。\n" +
                            "common 脚本 ${result.commonChars} 字符，分类脚本 ${result.categoryChars} 字符。\n\n" +
                            "规则已立即生效，不需要重启 App。",
                    )
                    .setPositiveButton("好的", null)
                    .show()
                onDataChanged()
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("同步失败")
                    .setMessage(result.error ?: "请先打开自动记账，再重试。")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun copyAsMine(rule: RuleBundleCodec.Rule, bundle: RuleBundleCodec.Bundle) {
        val newName = nextRuleName(bundle, RuleJsBuilder.newRuleName())
        val copy = rule.copy(
            systemRuleName = newName,
            js = RuleJsBuilder.renameRuleVariable(rule.js, rule.systemRuleName, newName),
            name = "${rule.name}（副本）",
            source = RuleBundleCodec.SOURCE_USER,
            creator = RuleBundleCodec.CREATOR_USER,
            enabled = true,
            id = 0L,
            updateAt = System.currentTimeMillis(),
            meta = rule.meta?.let { copyJson(it) },
        )
        persistRule(copy, "已复制为我的规则，可点「编辑」或「原始 JS」修改")
    }

    private fun confirmDelete(rule: RuleBundleCodec.Rule) {
        AlertDialog.Builder(activity)
            .setTitle("删除我的规则")
            .setMessage("确定删除「${rule.name}」？删除后付款解析不再使用这条规则。")
            .setPositiveButton("删除") { _, _ -> deleteRule(rule) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteRule(rule: RuleBundleCodec.Rule) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RuleBundleStore.delete(activity, rule)
                }
                toast("已删除「${rule.name}」")
                onDataChanged()
            } catch (t: Throwable) {
                toast("删除失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    // ════════════════════════════════════════════════════════
    //  自定义规则表单
    // ════════════════════════════════════════════════════════

    internal fun showRuleFormForSample(
        sampleJson: String,
        targetApp: String? = null,
        targetType: String? = null,
        targetName: String? = null,
        presetForm: RuleJsBuilder.RuleForm? = null,
        aiNotice: String? = null,
        bundle: RuleBundleCodec.Bundle = RuleBundleStore.load(activity),
    ) {
        val initial = presetForm ?: RuleJsBuilder.RuleForm(
            app = targetApp.orEmpty(),
            type = if (targetType?.equals(RuleBundleCodec.TYPE_NOTICE, ignoreCase = true) == true) {
                RuleBundleCodec.TYPE_NOTICE
            } else {
                RuleBundleCodec.TYPE_DATA
            },
            name = targetName.orEmpty(),
            enabled = true,
        )
        showRuleForm(existing = null, bundle = bundle, presetForm = initial, sampleJson = sampleJson, aiNotice = aiNotice)
    }

    private fun showRuleForm(
        existing: RuleBundleCodec.Rule?,
        bundle: RuleBundleCodec.Bundle,
        presetForm: RuleJsBuilder.RuleForm? = null,
        sampleJson: String? = null,
        aiNotice: String? = null,
    ) {
        val initial = presetForm ?: (existing?.let { rule ->
            RuleJsBuilder.RuleForm.fromMeta(rule.meta)?.copy(
                app = rule.app,
                type = rule.type,
                name = rule.name,
                enabled = rule.enabled,
            )
        } ?: RuleJsBuilder.RuleForm(
            app = "",
            type = RuleBundleCodec.TYPE_DATA,
            name = "",
            enabled = true,
        ))

        var selectedType = RuleJsBuilder.normalizeType(initial.type)
        var selectedBillType = RuleJsBuilder.normalizeBillType(initial.billType)

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(activity).apply { addView(layout) }

        if (!aiNotice.isNullOrBlank()) {
            val isSuccess = aiNotice.startsWith("✓")
            layout.addView(TextView(activity).apply {
                text = aiNotice
                textSize = 12.5f
                setTextColor(if (isSuccess) CaptureUi.SUCCESS else Color.parseColor("#E65100"))
                background = CaptureUi.roundedBg(
                    if (isSuccess) CaptureUi.SUCCESS_SOFT else Color.parseColor("#FFF3E0"),
                    dp(8),
                    dp(1),
                    if (isSuccess) CaptureUi.SUCCESS_BORDER else Color.parseColor("#FFE0B2"),
                )
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
            })
        }

        val etApp = addField(layout, "应用包名", "com.tencent.mm / com.eg.android.AlipayGphone", initial.app)
        val etName = addField(layout, "规则名称", "例如：微信零钱付款", initial.name)

        layout.addView(fieldLabel("类型（数据 / 通知）"))
        val typeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val dataChip = choiceChip("数据（DATA）") {}
        val noticeChip = choiceChip("通知（NOTICE）") {}
        typeRow.addView(dataChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        typeRow.addView(noticeChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(typeRow)

        layout.addView(fieldLabel("账单类型"))
        val billRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val expendChip = choiceChip("支出") {}
        val incomeChip = choiceChip("收入") {}
        val transferChip = choiceChip("转账") {}
        billRow.addView(expendChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        billRow.addView(incomeChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        billRow.addView(transferChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(billRow)

        val etPattern = addField(
            layout,
            "正则表达式",
            "例如：支付￥(\\d+\\.\\d{2}).*?商户：(.*?)，",
            initial.pattern,
        )
        val etAmount = addField(layout, "金额（捕获组，如 \$1）", "\$1 表示第 1 个括号；也可填固定金额", initial.amountField)
        val etMerchant = addField(layout, "商户（捕获组或固定值）", "如 \$2", initial.merchantField)
        val etItem = addField(layout, "商品（捕获组或固定值）", "如 \$3", initial.itemField)
        val etFrom = addField(layout, "转出账户", "如 \$4 或 微信零钱", initial.accountFromField)
        val etTo = addField(layout, "转入账户", "转账必填；支出/收入可留空", initial.accountToField)
        val etFee = addField(layout, "手续费", "如 \$5；留空为 0", initial.feeField)
        val etCurrency = addField(layout, "币种", "如 \$6；留空默认 CNY", initial.currencyField)
        val etTime = addField(layout, "时间", "如 \$7（支持时间戳或日期文本）；留空用当前时间", initial.timeField)

        val enabledBox = CheckBox(activity).apply {
            text = "保存后立即启用"
            textSize = 13f
            isChecked = initial.enabled
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setPadding(0, dp(8), 0, 0)
        }
        layout.addView(enabledBox)

        val formHint = TextView(activity).apply {
            text = "保存后生成 var rule_x = {...}，付款时优先跑「我的」规则。"
            textSize = 11.5f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(4), 0, dp(8))
        }
        layout.addView(formHint)

        var formDialog: AlertDialog? = null
        val testRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        if (!sampleJson.isNullOrBlank()) {
            testRow.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "🤖 AI 解析",
                    textColor = Color.parseColor("#1565C0"),
                    bgColor = Color.parseColor("#E3F2FD"),
                    strokeColor = Color.parseColor("#90CAF9"),
                    textSizeSp = 12f,
                ) {
                    formDialog?.dismiss()
                    generateRuleWithAi(
                        sampleJson = sampleJson,
                        targetApp = etApp.text?.toString(),
                        targetType = selectedType,
                        bundle = bundle,
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply { marginEnd = dp(6) },
            )
        }

        testRow.addView(
            CaptureUi.button(
                activity = activity,
                text = "测试字段",
                textColor = CaptureUi.SUCCESS,
                bgColor = CaptureUi.SUCCESS_SOFT,
                strokeColor = CaptureUi.SUCCESS_BORDER,
                textSizeSp = 12f,
            ) {
                val draft = buildRuleFromForm(
                    existing = existing,
                    bundle = bundle,
                    form = readForm(
                        etApp, etName, selectedType, selectedBillType, etPattern, etAmount,
                        etMerchant, etItem, etFrom, etTo, etFee, etCurrency, etTime,
                        enabledBox.isChecked, initial.ignoreCase,
                    ),
                )
                if (draft != null) showRuleTestDialog(draft, bundle.common, sampleJson = sampleJson)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
        )
        testRow.addView(
            CaptureUi.button(
                activity = activity,
                text = "改原始 JS",
                textColor = CaptureUi.TEXT_SECONDARY,
                bgColor = CaptureUi.BG_SUBTLE,
                strokeColor = CaptureUi.BORDER,
                textSizeSp = 12f,
            ) {
                val draft = buildRuleFromForm(
                    existing = existing,
                    bundle = bundle,
                    form = readForm(
                        etApp, etName, selectedType, selectedBillType, etPattern, etAmount,
                        etMerchant, etItem, etFrom, etTo, etFee, etCurrency, etTime,
                        enabledBox.isChecked, initial.ignoreCase,
                    ),
                )
                if (draft != null) {
                    // 关掉表单，避免用户改完原始 JS 后又点表单“保存”把修改覆盖掉。
                    formDialog?.dismiss()
                    showRawJsEditor(draft, bundle)
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        layout.addView(testRow)

        fun refreshChoices() {
            styleChoice(dataChip, selectedType == RuleBundleCodec.TYPE_DATA)
            styleChoice(noticeChip, selectedType == RuleBundleCodec.TYPE_NOTICE)
            styleChoice(expendChip, selectedBillType == RuleJsBuilder.BILL_EXPEND)
            styleChoice(incomeChip, selectedBillType == RuleJsBuilder.BILL_INCOME)
            styleChoice(transferChip, selectedBillType == RuleJsBuilder.BILL_TRANSFER)
        }
        dataChip.setOnClickListener {
            selectedType = RuleBundleCodec.TYPE_DATA
            refreshChoices()
        }
        noticeChip.setOnClickListener {
            selectedType = RuleBundleCodec.TYPE_NOTICE
            refreshChoices()
        }
        expendChip.setOnClickListener {
            selectedBillType = RuleJsBuilder.BILL_EXPEND
            refreshChoices()
        }
        incomeChip.setOnClickListener {
            selectedBillType = RuleJsBuilder.BILL_INCOME
            refreshChoices()
        }
        transferChip.setOnClickListener {
            selectedBillType = RuleJsBuilder.BILL_TRANSFER
            refreshChoices()
        }
        refreshChoices()

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "新建数据规则" else "编辑数据规则")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        formDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val form = readForm(
                    etApp, etName, selectedType, selectedBillType, etPattern, etAmount,
                    etMerchant, etItem, etFrom, etTo, etFee, etCurrency, etTime,
                    enabledBox.isChecked, initial.ignoreCase,
                )
                val error = validateForm(form)
                if (error != null) {
                    toast(error)
                    return@setOnClickListener
                }
                val rule = buildRuleFromForm(existing, bundle, form)
                if (rule == null) {
                    toast("生成规则失败，请检查字段")
                    return@setOnClickListener
                }
                dialog.dismiss()
                persistRule(rule, "已保存「${rule.name}」，付款时立即生效")
            }
        }
        dialog.show()
    }

    internal fun generateRuleWithAi(
        sampleJson: String,
        targetApp: String? = null,
        targetType: String? = null,
        bundle: RuleBundleCodec.Bundle = RuleBundleStore.load(activity),
    ) {
        val progress = AlertDialog.Builder(activity)
            .setTitle("🤖 AI 正在分析报文")
            .setMessage("正在调用大模型分析原始报文，生成泛化正则与字段映射…")
            .setCancelable(false)
            .create()
        progress.show()

        scope.launch {
            val bridge = CaptureBridgeClient(activity)
            val appPkg = targetApp?.takeIf { it.isNotBlank() } ?: "com.tencent.mm"
            val type = targetType?.takeIf { it.isNotBlank() } ?: RuleBundleCodec.TYPE_DATA
            val result = withContext(Dispatchers.IO) {
                bridge.generateRule(app = appPkg, type = type, rawJson = sampleJson)
            }
            progress.dismiss()

            if (!result.ok || result.rule == null) {
                AlertDialog.Builder(activity)
                    .setTitle("AI 生成规则失败")
                    .setMessage(result.error ?: "AI 未能生成有效规则，请检查网络或 AI 配置。\n\n是否改用手动编写规则？")
                    .setPositiveButton("手动编写") { _, _ ->
                        showRuleFormForSample(sampleJson, targetApp, targetType, null, bundle = bundle)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val r = result.rule
            val form = RuleJsBuilder.RuleForm(
                app = appPkg,
                type = type,
                name = r.optString("name", "AI 生成规则"),
                enabled = true,
                pattern = r.optString("pattern"),
                billType = RuleJsBuilder.normalizeBillType(r.optString("billType", RuleJsBuilder.BILL_EXPEND)),
                amountField = r.optString("amountField", "$1"),
                merchantField = r.optString("merchantField"),
                itemField = r.optString("itemField"),
                accountFromField = r.optString("accountFromField"),
                accountToField = r.optString("accountToField"),
                feeField = r.optString("feeField", "0"),
                currencyField = r.optString("currencyField", "CNY"),
                timeField = r.optString("timeField"),
                ignoreCase = true,
            )

            // 本地 QuickJS 验证
            val draftJs = RuleJsBuilder.buildJs(form, "rule_test_ai")
            val testResult = AutoAccountingRuleEngine.testRule(
                common = bundle.common,
                js = draftJs,
                systemRuleName = "rule_test_ai",
                rawJson = sampleJson,
            )

            val notice = if (testResult.matched) {
                val fieldSummary = testResult.fields.take(4).joinToString("，") { "${it.first}: ${it.second}" }
                "✓ AI 规则已在当前样本上验证通过！${if (fieldSummary.isNotEmpty()) fieldSummary else "已成功提取字段"}\n请核对字段后保存。"
            } else {
                val err = testResult.error?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                "⚠️ AI 生成了规则骨架，但在当前样本上尚未成功匹配$err，请检查并微调正则表达式。"
            }

            showRuleFormForSample(
                sampleJson = sampleJson,
                targetApp = appPkg,
                targetType = type,
                targetName = form.name,
                presetForm = form,
                aiNotice = notice,
                bundle = bundle,
            )
        }
    }

    private fun readForm(
        etApp: EditText,
        etName: EditText,
        type: String,
        billType: String,
        etPattern: EditText,
        etAmount: EditText,
        etMerchant: EditText,
        etItem: EditText,
        etFrom: EditText,
        etTo: EditText,
        etFee: EditText,
        etCurrency: EditText,
        etTime: EditText,
        enabled: Boolean,
        ignoreCase: Boolean,
    ): RuleJsBuilder.RuleForm = RuleJsBuilder.RuleForm(
        app = etApp.text?.toString()?.trim().orEmpty(),
        type = type,
        name = etName.text?.toString()?.trim().orEmpty(),
        enabled = enabled,
        pattern = etPattern.text?.toString()?.trim().orEmpty(),
        billType = billType,
        amountField = etAmount.text?.toString()?.trim().orEmpty(),
        merchantField = etMerchant.text?.toString()?.trim().orEmpty(),
        itemField = etItem.text?.toString()?.trim().orEmpty(),
        accountFromField = etFrom.text?.toString()?.trim().orEmpty(),
        accountToField = etTo.text?.toString()?.trim().orEmpty(),
        feeField = etFee.text?.toString()?.trim().orEmpty(),
        currencyField = etCurrency.text?.toString()?.trim().orEmpty(),
        timeField = etTime.text?.toString()?.trim().orEmpty(),
        ignoreCase = ignoreCase,
    )

    private fun validateForm(form: RuleJsBuilder.RuleForm): String? {
        if (form.app.isBlank() || !form.app.contains('.')) return "应用包名不能为空，且应包含包名点号"
        if (form.name.isBlank()) return "规则名称不能为空"
        if (form.pattern.isBlank()) return "正则表达式不能为空"
        if (form.amountField.isBlank()) return "金额捕获组不能为空（如 \$1）"
        return try {
            Pattern.compile(form.pattern)
            null
        } catch (t: Throwable) {
            "正则表达式无效：${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun buildRuleFromForm(
        existing: RuleBundleCodec.Rule?,
        bundle: RuleBundleCodec.Bundle,
        form: RuleJsBuilder.RuleForm,
    ): RuleBundleCodec.Rule? {
        val ruleName = existing?.systemRuleName?.takeIf {
            RuleBundleCodec.Rule.isValidRuleName(it)
        } ?: nextRuleName(bundle, RuleJsBuilder.newRuleName())
        val normalized = form.copy(
            app = form.app.trim().lowercase(Locale.US),
            name = form.name.trim(),
            type = RuleJsBuilder.normalizeType(form.type),
            billType = RuleJsBuilder.normalizeBillType(form.billType),
        )
        return try {
            val js = RuleJsBuilder.buildJs(normalized, ruleName)
            RuleBundleCodec.Rule(
                app = normalized.app,
                type = normalized.type,
                name = normalized.name,
                enabled = normalized.enabled,
                js = js,
                systemRuleName = ruleName,
                source = RuleBundleCodec.SOURCE_USER,
                creator = RuleBundleCodec.CREATOR_USER,
                id = existing?.id ?: 0L,
                updateAt = System.currentTimeMillis(),
                meta = normalized.toMeta(),
            )
        } catch (t: Throwable) {
            null
        }
    }

    // ════════════════════════════════════════════════════════
    //  原始 JS 编辑与样本测试
    // ════════════════════════════════════════════════════════

    private fun showRawJsEditor(rule: RuleBundleCodec.Rule, bundle: RuleBundleCodec.Bundle) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        layout.addView(TextView(activity).apply {
            text = "变量名 ${rule.systemRuleName}。保存后引擎会执行 JSON.stringify(${rule.systemRuleName}.get(data))；请保留 .get(data) 返回对象。"
            textSize = 11.5f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, 0, 0, dp(6))
        })
        val editor = EditText(activity).apply {
            setText(rule.js)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 12
            maxLines = 18
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(10), dp(1), CaptureUi.BORDER)
        }
        layout.addView(editor)
        val testButton = CaptureUi.button(
            activity = activity,
            text = "用样本测试当前 JS",
            textColor = CaptureUi.SUCCESS,
            bgColor = CaptureUi.SUCCESS_SOFT,
            strokeColor = CaptureUi.SUCCESS_BORDER,
            textSizeSp = 12.5f,
        ) {
            val draft = rule.copy(js = editor.text?.toString().orEmpty())
            showRuleTestDialog(draft, bundle.common)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        layout.addView(testButton)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("原始 JS · ${rule.name}")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val js = editor.text?.toString()?.trim().orEmpty()
                if (js.isBlank()) {
                    toast("JS 不能为空")
                    return@setOnClickListener
                }
                val updated = rule.copy(
                    js = js,
                    source = RuleBundleCodec.SOURCE_USER,
                    creator = RuleBundleCodec.CREATOR_USER,
                    meta = null,
                    updateAt = System.currentTimeMillis(),
                )
                dialog.dismiss()
                persistRule(updated, "已保存「${updated.name}」的原始 JS")
            }
        }
        dialog.show()
    }

    private fun showRuleTestDialog(rule: RuleBundleCodec.Rule, common: String, sampleJson: String? = null) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        layout.addView(TextView(activity).apply {
            text = "粘贴一笔真实 raw JSON（hook 投递的 data）。测试只读，不影响现有规则。"
            textSize = 11.5f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, 0, 0, dp(6))
        })
        val sampleInput = EditText(activity).apply {
            setText(sampleJson?.takeIf { it.isNotBlank() } ?: defaultSample(rule))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 8
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(10), dp(1), CaptureUi.BORDER)
        }
        layout.addView(sampleInput)
        val resultView = TextView(activity).apply {
            text = "点下方按钮运行 QuickJS 测试。"
            textSize = 12f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(10), 0, dp(4))
        }
        layout.addView(
            CaptureUi.button(
                activity = activity,
                text = "运行样本测试",
                textColor = Color.WHITE,
                bgColor = CaptureUi.BRAND,
                textSizeSp = 12.5f,
            ) {
                val sample = sampleInput.text?.toString().orEmpty()
                if (sample.isBlank()) {
                    toast("请先粘贴样本 JSON")
                } else {
                    resultView.text = "运行中…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            AutoAccountingRuleEngine.testRule(
                                common = common,
                                js = rule.js,
                                systemRuleName = rule.systemRuleName,
                                rawJson = sample,
                            )
                        }
                        resultView.text = formatTestResult(result)
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            },
        )
        layout.addView(resultView)

        AlertDialog.Builder(activity)
            .setTitle("样本测试 · ${rule.name}")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun formatTestResult(result: AutoAccountingRuleEngine.RuleTestResult): String {
        if (result.error != null) return "✗ ${result.error}"
        if (!result.matched) return "未命中：规则返回 null/undefined"
        return buildString {
            append("✓ 命中，解析字段：\n")
            result.fields.forEach { (label, value) ->
                append("· ").append(label).append("：").append(value).append('\n')
            }
            result.output?.let { output ->
                append("\n原始输出：\n").append(output.take(500))
            }
        }
    }

    private fun defaultSample(rule: RuleBundleCodec.Rule): String {
        val name = rule.name
        val js = rule.js

        // 1. 通知规则（NOTICE）：提取 expectedTitle 与 regex 动态生成精准匹配报文
        if (rule.type == RuleBundleCodec.TYPE_NOTICE) {
            val titleMatch = Regex("""expectedTitle:\s*(?:\["([^"]+)"|"([^"]+)")""").find(js)
            val title = titleMatch?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                ?: name.substringBefore(" ").substringBefore("消费").substringBefore("通知")
            val sampleText = when {
                name.contains("还款") -> "您尾号1234卡于本月还款成功人民币12.34元"
                name.contains("退款") -> "您的退款人民币12.34元已到账"
                name.contains("转账") -> "向张三转账人民币12.34元成功"
                name.contains("红包") -> "收到红包12.34元"
                name.contains("定存") || name.contains("理财") -> "买入理财产品12.34元成功"
                name.contains("扣费") || js.contains("扣费") -> "已扣费¥12.34"
                else -> "已支付¥12.34，向测试商户付款成功"
            }
            return """{"title":"$title","text":"$sampleText","tickerText":"$sampleText","postTime":${System.currentTimeMillis()}}"""
        }

        // 2. 微信数据规则
        if (rule.app == "com.tencent.mm") {
            return when {
                name.contains("转账") ->
                    """{"type":"transfer","isSend":1,"hookerUser":"好友","cachedPayTools":"零钱","content":"{\"msg\":{\"appmsg\":{\"wcpayinfo\":{\"feedesc\":\"￥12.34\",\"pay_memo\":\"测试转账\",\"paysubtype\":1}}}}","t":${System.currentTimeMillis()}}"""
                name.contains("红包") ->
                    """{"type":"red_package","isSend":1,"hookerUser":"群友","content":"{\"msg\":{\"appmsg\":{\"wcpayinfo\":{\"feedesc\":\"￥6.66\",\"paysubtype\":1}}}}","t":${System.currentTimeMillis()}}"""
                name.contains("群收款") ->
                    """{"type":"aa","isSend":1,"hookerUser":"组织者","content":"{\"msg\":{\"appmsg\":{\"wcpayinfo\":{\"feedesc\":\"￥25.00\",\"paysubtype\":1}}}}","t":${System.currentTimeMillis()}}"""
                name.contains("公交") ->
                    """{"type":"bus","fee":"12.34","channel":"微信公交","display_name":"乘车码扣费","t":${System.currentTimeMillis()}}"""
                name.contains("退款") ->
                    """{"type":"refund","source":"微信支付","description":"退款金额￥12.34\n商户名称：美团","title":"退款成功","t":${System.currentTimeMillis()}}"""
                name.contains("公众号") ->
                    """{"title":"${name.removePrefix("微信公众号")}","digest":"您于近期消费人民币12.34元","content":"您于近期消费人民币12.34元","t":${System.currentTimeMillis()}}"""
                else ->
                    """{"mMap":{"description":"使用零钱支付¥12.34","source":"微信支付","title":"已支付¥12.34","display_name":"测试商户","t":${System.currentTimeMillis()}}}"""
            }
        }

        // 3. 支付宝数据规则
        if (rule.app.contains("alipay", ignoreCase = true)) {
            return when {
                name.contains("账单列表") ->
                    """[{"money":"-12.34","shopName":"测试商户","consumeTitle":"测试商品","time":"2026-04-12 12:00:00"}]"""
                name.contains("付款码") ->
                    """[{"pl":"{\"sdkBizData\":\"{\\\"fee\\\":\\\"12.34\\\",\\\"shopName\\\":\\\"便利店\\\",\\\"payTools\\\":\\\"花呗\\\"}\"}"}]"""
                name.contains("退款") ->
                    """{"content":"{\"content\":\"退款成功￥12.34\",\"title\":\"退款通知\",\"shopName\":\"淘宝商户\",\"payTools\":\"花呗\"}"}"""
                name.contains("收款") || name.contains("收钱码") ->
                    """{"content":"{\"content\":\"二维码收款到账12.34元\",\"title\":\"收钱码到账通知\",\"money\":\"12.34\",\"payTools\":\"余额\"}"}"""
                name.contains("红包") ->
                    """{"content":"{\"content\":\"收到现金红包6.66元\",\"title\":\"红包通知\",\"money\":\"6.66\",\"payTools\":\"余额\"}"}"""
                else ->
                    """{"content":"{\"content\":\"付款成功￥12.34\",\"money\":\"12.34\",\"shopName\":\"便利店\",\"payTools\":\"余额宝\",\"title\":\"付款成功\"}"}"""
            }
        }

        // 4. 银行/短信/其他通知
        return """{"title":"交易提醒","text":"您尾号1234卡于近期消费人民币12.34元【测试银行】","sender":"95555"}"""
    }

    // ════════════════════════════════════════════════════════
    //  保存与 UI 工具
    // ════════════════════════════════════════════════════════

    private fun persistRule(rule: RuleBundleCodec.Rule, successMessage: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RuleBundleStore.upsert(activity, rule)
                }
                toast(successMessage)
                onDataChanged()
            } catch (t: Throwable) {
                toast("保存失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun nextRuleName(bundle: RuleBundleCodec.Bundle, base: String): String {
        val used = bundle.rules.mapTo(HashSet()) { it.systemRuleName }
        if (base !in used) return base
        var index = 1
        while ("${base}_$index" in used) index++
        return "${base}_$index"
    }

    private fun addField(
        container: LinearLayout,
        label: String,
        hint: String,
        initial: String,
    ): EditText {
        container.addView(fieldLabel(label))
        return EditText(activity).apply {
            this.hint = hint
            setText(initial)
            textSize = 13f
            isSingleLine = true
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setHintTextColor(CaptureUi.TEXT_MUTED)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(9), dp(1), CaptureUi.BORDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }.also { container.addView(it) }
    }

    private fun fieldLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(CaptureUi.TEXT_SECONDARY)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun choiceChip(text: String, onClick: () -> Unit): TextView =
        CaptureUi.chip(
            activity = activity,
            text = text,
            textColor = CaptureUi.TEXT_SECONDARY,
            bgColor = CaptureUi.BG_SUBTLE,
            strokeColor = CaptureUi.BORDER,
            onClick = onClick,
        )

    private fun styleChoice(tv: TextView, selected: Boolean) {
        tv.setTextColor(if (selected) CaptureUi.BRAND_DARK else CaptureUi.TEXT_SECONDARY)
        tv.background = CaptureUi.rippleBg(
            fillColor = if (selected) CaptureUi.BRAND_SOFT else CaptureUi.BG_SUBTLE,
            radiusPx = dp(8),
            strokeWidthPx = dp(1),
            strokeColor = if (selected) CaptureUi.BRAND_BORDER else CaptureUi.BORDER,
        )
    }

    private fun buildEmptyHint(text: String): View = TextView(activity).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(CaptureUi.TEXT_MUTED)
        setPadding(dp(16), dp(24), dp(16), dp(24))
    }

    /** JSONObject 没有公开的复制构造，用序列化做一次隔离，避免副本与源共享 meta。 */
    private fun copyJson(source: org.json.JSONObject): org.json.JSONObject =
        org.json.JSONObject(source.toString())

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
