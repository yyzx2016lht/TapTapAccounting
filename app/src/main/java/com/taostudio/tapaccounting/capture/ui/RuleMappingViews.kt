package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.AccountingContext
import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.CaptureSources
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.AssetMappingRule
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.CaptureInboxEvents
import com.taostudio.tapaccounting.capture.history.AutoAccountingRuleImporter
import com.taostudio.tapaccounting.capture.notify.CaptureBubbleManager
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher
import com.taostudio.tapaccounting.capture.rules.AccountingRuleEngine
import com.taostudio.tapaccounting.capture.rules.DefaultAccountingRuleEngine
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 插件工作台 Tab 1（资产映射）、Tab 2（分类规则）、Tab 3（设置与调试）视图构建器。
 */
internal class RuleMappingViews(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val onDataChanged: () -> Unit,
) {
    var onSyncDataRules: (() -> Unit)? = null
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    // ════════════════════════════════════════════════════════
    //  Tab 1: 资产映射页（按目标账户分组折叠 + 搜索过滤）
    // ════════════════════════════════════════════════════════

    fun buildAssetMappingTab(
        rules: List<AssetMappingRule>,
        accountingContext: AccountingContext?,
    ): View {
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

        val appAssets = accountingContext?.assets?.map { it.name }.orEmpty()
        val infoCard = CaptureUi.card(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }

            addView(TextView(activity).apply {
                text = "支付渠道 → 记账账户映射"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
            })
            addView(TextView(activity).apply {
                text = if (appAssets.isNotEmpty()) {
                    "已同步敲敲记账 ${appAssets.size} 个账户（${appAssets.take(5).joinToString("、")}" +
                        (if (appAssets.size > 5) " 等" else "") + "），当前配置 ${rules.size} 条映射规则。"
                } else {
                    "暂未连上敲敲记账资产列表，仍可手动配置 ${rules.size} 条映射规则。"
                }
                textSize = 12.5f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(4), 0, dp(12))
            })

            val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            btnRow.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "+ 新增映射",
                    textColor = Color.WHITE,
                    bgColor = CaptureUi.BRAND,
                ) { showEditAssetMappingDialog(null, appAssets) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(btnRow)
        }
        container.addView(infoCard)

        val searchBox = buildSearchBox("搜索渠道名或目标账户（如：招商、零钱、花呗）")
        container.addView(searchBox)

        val listHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(listHost)

        fun renderFiltered(query: String) {
            listHost.removeAllViews()
            val q = query.trim()
            val filtered = if (q.isEmpty()) {
                rules
            } else {
                rules.filter {
                    it.pattern.contains(q, ignoreCase = true) ||
                        it.targetAssetName.contains(q, ignoreCase = true)
                }
            }
            if (filtered.isEmpty()) {
                listHost.addView(buildEmptyHint("没有匹配的资产映射规则"))
                return
            }
            // 按目标资产分组展示，一目了然
            val grouped = filtered.groupBy { it.targetAssetName.ifBlank { "未命名账户" } }
            grouped.forEach { (targetAsset, groupRules) ->
                val existsInApp = appAssets.isEmpty() || appAssets.any { it.equals(targetAsset, ignoreCase = true) }
                val groupCard = CaptureUi.card(activity, paddingH = 14, paddingV = 12, radiusDp = 14).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) }
                }

                val header = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(8))
                }
                header.addView(TextView(activity).apply {
                    text = targetAsset
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(CaptureUi.TEXT_PRIMARY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (!existsInApp) {
                    header.addView(
                        CaptureUi.chip(
                            activity = activity,
                            text = "App中未建此账户",
                            textColor = CaptureUi.WARNING,
                            bgColor = CaptureUi.WARNING_SOFT,
                            strokeColor = CaptureUi.WARNING_BORDER,
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { marginEnd = dp(6) }
                        }
                    )
                }
                header.addView(
                    CaptureUi.chip(
                        activity = activity,
                        text = "${groupRules.size} 个渠道",
                        textColor = CaptureUi.BRAND_DARK,
                        bgColor = CaptureUi.BRAND_SOFT,
                    )
                )
                groupCard.addView(header)

                groupRules.forEachIndexed { idx, rule ->
                    if (idx > 0) {
                        groupCard.addView(View(activity).apply {
                            setBackgroundColor(CaptureUi.DIVIDER)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                        })
                    }
                    groupCard.addView(buildAssetRuleRow(rule, appAssets))
                }
                listHost.addView(groupCard)
            }
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderFiltered(s?.toString().orEmpty())
            }
        })
        renderFiltered("")

        return scroll
    }

    private fun buildAssetRuleRow(rule: AssetMappingRule, appAssets: List<String>): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            isClickable = true
            background = CaptureUi.rippleBg(activity, CaptureUi.BG_SURFACE)
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvPattern = TextView(activity).apply {
            text = rule.pattern
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (rule.enabled) CaptureUi.TEXT_PRIMARY else CaptureUi.TEXT_MUTED)
        }
        textCol.addView(tvPattern)
        textCol.addView(TextView(activity).apply {
            text = if (rule.isRegex) "正则匹配" else "包含/精确匹配"
            textSize = 11f
            setTextColor(CaptureUi.TEXT_MUTED)
        })
        row.addView(textCol)

        val sw = Switch(activity).apply {
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, checked ->
                tvPattern.setTextColor(if (checked) CaptureUi.TEXT_PRIMARY else CaptureUi.TEXT_MUTED)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        CaptureApp.from(activity).database.assetMappingDao()
                            .update(rule.copy(enabled = checked))
                    }
                }
            }
        }
        row.addView(sw)

        row.addView(TextView(activity).apply {
            text = "编辑"
            textSize = 12f
            setTextColor(CaptureUi.BRAND_DARK)
            setPadding(dp(8), dp(6), dp(6), dp(6))
            setOnClickListener { showEditAssetMappingDialog(rule, appAssets) }
        })

        row.addView(TextView(activity).apply {
            text = "删除"
            textSize = 12f
            setTextColor(CaptureUi.DANGER)
            setPadding(dp(6), dp(6), dp(4), dp(6))
            setOnClickListener {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        CaptureApp.from(activity).database.assetMappingDao().deleteById(rule.id)
                    }
                    onDataChanged()
                }
            }
        })

        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun showEditAssetMappingDialog(existing: AssetMappingRule?, appAssets: List<String>) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        val etPattern = buildDialogInput(
            hint = "支付渠道原始名（如：招商银行储蓄卡(4852)）",
            initial = existing?.pattern.orEmpty(),
        )
        val etTarget = buildDialogInput(
            hint = "对应敲敲记账账户名（如：招商银行）",
            initial = existing?.targetAssetName.orEmpty(),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        layout.addView(etPattern)
        layout.addView(etTarget)

        val pickBtn = CaptureUi.button(
            activity = activity,
            text = "从敲敲记账账户列表中点选",
            textColor = CaptureUi.BRAND_DARK,
            bgColor = CaptureUi.BRAND_SOFT,
            strokeColor = CaptureUi.BRAND_BORDER,
            paddingV = 9,
        ) {
            OverlayDialogs.showGridAssetPicker(
                ctx = activity,
                currentSelectionText = etTarget.text?.toString().orEmpty(),
                title = "选择敲敲记账账户",
                allowClear = false,
            ) { selected ->
                etTarget.setText(selected)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            CaptureUi.applyTrailingIcon(
                this,
                activity,
                R.drawable.ic_chevron_down,
                CaptureUi.BRAND_DARK,
                11,
            )
        }
        layout.addView(pickBtn)

        val cbRegex = CheckBox(activity).apply {
            text = "使用正则表达式匹配"
            isChecked = existing?.isRegex ?: false
            setPadding(0, dp(8), 0, 0)
        }
        layout.addView(cbRegex)

        AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "新增资产映射" else "编辑资产映射")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val p = etPattern.text?.toString()?.trim().orEmpty()
                val t = etTarget.text?.toString()?.trim().orEmpty()
                if (p.isEmpty() || t.isEmpty()) {
                    toast("支付渠道名与目标账户名不能为空")
                    return@setPositiveButton
                }
                scope.launch {
                    val updatedCount = withContext(Dispatchers.IO) {
                        val rule = AssetMappingRule(
                            id = existing?.id ?: 0L,
                            pattern = p,
                            targetAssetName = t,
                            isRegex = cbRegex.isChecked,
                            sort = existing?.sort ?: 0,
                            enabled = existing?.enabled ?: true,
                        )
                        CaptureApp.from(activity).database.assetMappingDao().upsert(rule)
                        CaptureApp.from(activity).repository.reapplyAssetMappingForRule(rule)
                    }
                    toast("已更新 ${updatedCount} 笔待记资产")
                    onDataChanged()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ════════════════════════════════════════════════════════
    //  Tab 2: 分类规则页（按目标分类聚合胶囊矩阵 + 就地即时启停 + 零跳顶）
    // ════════════════════════════════════════════════════════

    fun buildCategoryRuleTab(
        rules: List<AccountingRule>,
        accountingContext: AccountingContext?,
    ): View {
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

        val categoryPaths = buildCategoryDisplayList(accountingContext)
        val activeRules = rules.toMutableList()
        val conflictKeywords = activeRules
            .groupBy { it.keyword.trim().lowercase() }
            .filterValues { list ->
                list.map { it.targetCategory?.trim().orEmpty() }.distinct().size > 1
            }
            .keys

        // 来源筛选："ALL"、"USER"、"SEED"、"AI"
        var currentSourceFilter = "ALL"
        var currentQuery = ""

        val totalCount = activeRules.size
        val userCount = activeRules.count { it.source == AccountingRule.Source.USER }
        val seedCount = activeRules.count { it.source == AccountingRule.Source.SEED || it.source == AccountingRule.Source.IMPORT }
        val aiCount = activeRules.count { it.source == AccountingRule.Source.AI }

        val infoCard = CaptureUi.card(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }

            val titleRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(activity).apply {
                text = "本地分类规则（${totalCount} 条）"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(titleRow)

            addView(TextView(activity).apply {
                text = "按目标分类聚合展示。短按关键词胶囊就地启用/停用（停用后变暗置灰）；长按关键词胶囊可编辑关键词与分类。"
                textSize = 12f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(4), 0, dp(12))
            })

            val actionRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actionRow.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "+ 新增分类规则",
                    textColor = Color.WHITE,
                    bgColor = CaptureUi.BRAND,
                ) {
                    showEditCategoryRuleDialog(null, categoryPaths)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(actionRow)
        }
        container.addView(infoCard)

        // 规则测试框：输入备注，看实际命中哪条（同关键词冲突时也能看到优先级）
        val testInput = EditText(activity).apply {
            hint = "输入一条备注，例如：酸奶 / 星巴克 / 停车费"
            textSize = 13f
            isSingleLine = true
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setHintTextColor(CaptureUi.TEXT_MUTED)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = CaptureUi.roundedBg(Color.WHITE, dp(10), dp(1), CaptureUi.BORDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val testCard = CaptureUi.card(activity, paddingH = 14, paddingV = 12, radiusDp = 14).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            addView(TextView(activity).apply {
                text = "测试一条备注"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
            })
            addView(TextView(activity).apply {
                text = "看看这条备注实际会命中哪条规则、被谁覆盖。"
                textSize = 12f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setPadding(0, dp(2), 0, dp(8))
            })
            addView(testInput)
            addView(
                CaptureUi.button(
                    activity = activity,
                    text = "测试命中",
                    textColor = Color.WHITE,
                    bgColor = CaptureUi.BRAND,
                ) {
                    val note = testInput.text?.toString()?.trim().orEmpty()
                    if (note.isEmpty()) {
                        toast("先输入一条备注")
                        return@button
                    }
                    val matches = DefaultAccountingRuleEngine.matchAllNoteWithRules(activeRules, note)
                    val msg = if (matches.isEmpty()) {
                        "没有分类规则命中「$note」，会走 AI 分类或保持待分类。"
                    } else {
                        buildString {
                            append("「$note」命中 ${matches.size} 条：\n\n")
                            matches.take(8).forEachIndexed { index, match ->
                                append("${index + 1}. ${match.keyword} → ")
                                append(match.categoryName?.takeIf { it.isNotBlank() } ?: "未分类")
                                append("（${match.source}）\n")
                            }
                            if (matches.size > 8) append("… 还有 ${matches.size - 8} 条")
                        }
                    }
                    val dialog = AlertDialog.Builder(activity)
                        .setTitle("规则测试")
                        .setMessage(msg)
                        .setPositiveButton("知道了", null)
                        .create()
                    dialog.show()
                },
            )
        }
        container.addView(testCard)

        // 最近学习：AI 学到的规则，支持一键撤销
        val recentRules = activeRules
            .filter { it.source == AccountingRule.Source.AI && (it.learnedAt ?: 0L) > 0L }
            .sortedByDescending { it.learnedAt }
            .take(5)
        if (recentRules.isNotEmpty()) {
            val recentCard = CaptureUi.card(activity, paddingH = 14, paddingV = 12, radiusDp = 14).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(12) }
                addView(TextView(activity).apply {
                    text = "最近学习"
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(CaptureUi.TEXT_PRIMARY)
                })
                addView(TextView(activity).apply {
                    text = "AI 刚学到的规则；点「撤销」删除。"
                    textSize = 12f
                    setTextColor(CaptureUi.TEXT_SECONDARY)
                    setPadding(0, dp(2), 0, dp(8))
                })
                recentRules.forEach { rule ->
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    row.addView(TextView(activity).apply {
                        val leaf = rule.targetCategory?.substringAfterLast(" - ")
                            ?.substringAfterLast(" > ")?.trim()
                            ?: rule.targetCategory?.trim().orEmpty()
                        text = "${rule.keyword} → ${leaf.ifBlank { "未分类" }}"
                        textSize = 12.5f
                        setTextColor(CaptureUi.TEXT_SECONDARY)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(
                        CaptureUi.chip(
                            activity = activity,
                            text = "撤销",
                            textColor = CaptureUi.DANGER,
                            bgColor = CaptureUi.DANGER_SOFT,
                        ) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        com.taostudio.tapaccounting.capture.CaptureApp.from(activity)
                                            .database.accountingRuleDao()
                                            .deleteById(rule.id)
                                    }
                                }
                                onDataChanged()
                            }
                        },
                    )
                    addView(row)
                }
            }
            container.addView(recentCard)
        }

        // 来源分段筛选胶囊栏
        val segmentRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        data class FilterOption(val id: String, val title: String, val count: Int)
        val filterOptions = listOf(
            FilterOption("ALL", "全部", totalCount),
            FilterOption("USER", "手动", userCount),
            FilterOption("SEED", "预置", seedCount),
            FilterOption("AI", "AI 学习", aiCount),
        )

        val filterViews = ArrayList<TextView>()

        val searchBox = buildSearchBox("搜索规则关键词或分类（如：咖啡、餐饮）")
        container.addView(segmentRow)
        container.addView(searchBox)

        val listHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(listHost)

        fun renderList() {
            listHost.removeAllViews()
            val q = currentQuery.trim()

            // 1. 过滤来源
            val sourceFiltered = when (currentSourceFilter) {
                "USER" -> activeRules.filter { it.source == AccountingRule.Source.USER }
                "SEED" -> activeRules.filter { it.source == AccountingRule.Source.SEED || it.source == AccountingRule.Source.IMPORT }
                "AI" -> activeRules.filter { it.source == AccountingRule.Source.AI }
                else -> activeRules
            }

            // 2. 过滤关键词 / 分类
            val queryFiltered = if (q.isEmpty()) {
                sourceFiltered
            } else {
                sourceFiltered.filter {
                    it.keyword.contains(q, ignoreCase = true) ||
                        (it.targetCategory?.contains(q, ignoreCase = true) == true)
                }
            }

            if (queryFiltered.isEmpty()) {
                val hint = if (q.isNotEmpty()) "没有匹配「$q」的分类规则" else "暂无该来源的规则"
                listHost.addView(buildEmptyHint(hint))
                return
            }

            // 3. 按目标分类聚合
            val grouped = queryFiltered.groupBy { it.targetCategory?.ifBlank { "未分类" } ?: "未分类" }

            grouped.forEach { (targetCat, catRules) ->
                val groupCard = CaptureUi.card(activity, paddingH = 14, paddingV = 12, radiusDp = 14).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) }
                }

                val header = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(10))
                }
                header.addView(TextView(activity).apply {
                    text = "🏷️ $targetCat"
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(CaptureUi.TEXT_PRIMARY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (catRules.any { it.keyword.trim().lowercase() in conflictKeywords }) {
                    header.addView(
                        CaptureUi.chip(
                            activity = activity,
                            text = "冲突",
                            textColor = CaptureUi.DANGER,
                            bgColor = CaptureUi.DANGER_SOFT,
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { marginEnd = dp(6) }
                        },
                    )
                }
                header.addView(
                    CaptureUi.chip(
                        activity = activity,
                        text = "+ 加词",
                        textColor = CaptureUi.BRAND_DARK,
                        bgColor = CaptureUi.BRAND_SOFT,
                    ) {
                        showEditCategoryRuleDialog(
                            existing = AccountingRule(
                                keyword = "",
                                targetCategory = targetCat,
                            ),
                            categoryPaths = categoryPaths,
                        )
                    }
                )
                groupCard.addView(header)

                val flow = ChipsFlowLayout(activity, horizontalSpacing = dp(8), verticalSpacing = dp(8)).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }

                // 规则排序：优先级 USER > SEED/IMPORT > AI，同级按 id
                val sortedCatRules = catRules.sortedWith(DefaultAccountingRuleEngine.ruleComparator)

                sortedCatRules.forEach { rule ->
                    flow.addView(buildRuleCapsule(rule, categoryPaths, activeRules))
                }

                groupCard.addView(flow)
                listHost.addView(groupCard)
            }
        }

        fun updateSegmentUI() {
            filterOptions.forEachIndexed { index, opt ->
                val tv = filterViews.getOrNull(index) ?: return@forEachIndexed
                val selected = opt.id == currentSourceFilter
                tv.text = "${opt.title} ${opt.count}"
                if (selected) {
                    tv.setTextColor(CaptureUi.BRAND_DARK)
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.background = CaptureUi.roundedBg(CaptureUi.BRAND_SOFT, dp(8), dp(1), CaptureUi.BRAND_BORDER)
                } else {
                    tv.setTextColor(CaptureUi.TEXT_SECONDARY)
                    tv.setTypeface(null, Typeface.NORMAL)
                    tv.background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(8), dp(1), CaptureUi.BORDER)
                }
            }
        }

        filterOptions.forEach { opt ->
            val tv = TextView(activity).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(7), dp(10), dp(7))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    if (currentSourceFilter != opt.id) {
                        currentSourceFilter = opt.id
                        updateSegmentUI()
                        renderList()
                    }
                }
            }
            filterViews.add(tv)
            segmentRow.addView(tv)
        }

        updateSegmentUI()

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString().orEmpty()
                renderList()
            }
        })

        renderList()

        return scroll
    }

    private fun buildRuleCapsule(
        initialRule: AccountingRule,
        categoryPaths: List<String>,
        activeRules: MutableList<AccountingRule>,
    ): View {
        var currentRule = initialRule
        val tv = TextView(activity).apply {
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(6), dp(11), dp(6))
            isClickable = true
            isFocusable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        fun updateVisual(rule: AccountingRule) {
            val sourceLabel = when (rule.source) {
                AccountingRule.Source.AI -> "AI · "
                AccountingRule.Source.USER -> "手动 · "
                AccountingRule.Source.SEED, AccountingRule.Source.IMPORT -> "预置 · "
                else -> ""
            }
            tv.text = "$sourceLabel${rule.keyword}"

            if (rule.enabled) {
                tv.alpha = 1.0f
                when (rule.source) {
                    AccountingRule.Source.AI -> {
                        tv.setTextColor(Color.parseColor("#7C3AED"))
                        tv.background = CaptureUi.rippleBg(
                            fillColor = Color.parseColor("#F5F3FF"),
                            radiusPx = dp(8),
                            strokeWidthPx = dp(1),
                            strokeColor = Color.parseColor("#DDD6FE"),
                        )
                    }
                    AccountingRule.Source.USER -> {
                        tv.setTextColor(CaptureUi.BRAND_DARK)
                        tv.background = CaptureUi.rippleBg(
                            fillColor = CaptureUi.BRAND_SOFT,
                            radiusPx = dp(8),
                            strokeWidthPx = dp(1),
                            strokeColor = CaptureUi.BRAND_BORDER,
                        )
                    }
                    else -> {
                        tv.setTextColor(CaptureUi.TEXT_PRIMARY)
                        tv.background = CaptureUi.rippleBg(
                            fillColor = CaptureUi.BG_SUBTLE,
                            radiusPx = dp(8),
                            strokeWidthPx = dp(1),
                            strokeColor = CaptureUi.BORDER,
                        )
                    }
                }
            } else {
                tv.alpha = 0.45f
                tv.setTextColor(CaptureUi.TEXT_MUTED)
                tv.background = CaptureUi.rippleBg(
                    fillColor = CaptureUi.BG_PAGE,
                    radiusPx = dp(8),
                    strokeWidthPx = dp(1),
                    strokeColor = CaptureUi.BORDER,
                )
            }
        }

        updateVisual(currentRule)

        // 短按关键词胶囊：直接就地切换「启用 / 停用」，立刻变暗置灰，零跳顶保证
        tv.setOnClickListener {
            val nextEnabled = !currentRule.enabled
            currentRule = currentRule.copy(
                enabled = nextEnabled,
                updatedAt = System.currentTimeMillis(),
            )
            val index = activeRules.indexOfFirst { it.id == currentRule.id }
            if (index >= 0) {
                activeRules[index] = currentRule
            }
            updateVisual(currentRule)

            val actionText = if (nextEnabled) "已启用「${currentRule.keyword}」规则" else "已停用「${currentRule.keyword}」规则"
            toast(actionText)

            scope.launch {
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(activity).accountingRuleDao().updateEnabled(currentRule.id, nextEnabled)
                    AccountingRuleEngine.invalidateCache()
                }
            }
        }

        // 长按关键词胶囊：唤起规则编辑对话框
        tv.setOnLongClickListener {
            showEditCategoryRuleDialog(currentRule, categoryPaths)
            true
        }

        return tv
    }

    private fun showEditCategoryRuleDialog(existing: AccountingRule?, categoryPaths: List<String>) {
        val isNew = existing == null || existing.id == 0L
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

        // 关键词输入框
        layout.addView(TextView(activity).apply {
            text = "匹配关键词"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setPadding(0, 0, 0, dp(4))
        })
        val etKeyword = buildDialogInput(
            hint = "如：咖啡、星巴克、美宜佳、打车",
            initial = existing?.keyword.orEmpty(),
        )
        layout.addView(etKeyword)

        // 匹配模式单选行
        layout.addView(TextView(activity).apply {
            text = "匹配模式"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setPadding(0, dp(10), 0, dp(4))
        })

        var selectedMode = when {
            existing?.isRegex == true -> "REGEX"
            existing?.matchMode == AccountingRule.MatchMode.ALL_TOKENS -> "ALL_TOKENS"
            else -> "SUBSTRING"
        }

        val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val modeOptions = listOf(
            Triple("SUBSTRING", "包含匹配", "子串匹配"),
            Triple("ALL_TOKENS", "全词匹配", "空格分词全部包含"),
            Triple("REGEX", "正则表达式", "高级正则匹配"),
        )
        val modeViews = ArrayList<TextView>()

        fun updateModeUI() {
            modeOptions.forEachIndexed { index, triple ->
                val tv = modeViews[index]
                val selected = triple.first == selectedMode
                if (selected) {
                    tv.setTextColor(CaptureUi.BRAND_DARK)
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.background = CaptureUi.roundedBg(CaptureUi.BRAND_SOFT, dp(8), dp(1), CaptureUi.BRAND_BORDER)
                } else {
                    tv.setTextColor(CaptureUi.TEXT_SECONDARY)
                    tv.setTypeface(null, Typeface.NORMAL)
                    tv.background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(8), dp(1), CaptureUi.BORDER)
                }
            }
        }

        modeOptions.forEach { triple ->
            val tv = TextView(activity).apply {
                text = triple.second
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(7), dp(6), dp(7))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    selectedMode = triple.first
                    updateModeUI()
                }
            }
            modeViews.add(tv)
            modeRow.addView(tv)
        }
        layout.addView(modeRow)
        updateModeUI()

        // 目标分类输入框与悬浮窗大宫格选择按钮
        layout.addView(TextView(activity).apply {
            text = "目标分类"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
            setPadding(0, dp(10), 0, dp(4))
        })
        val etCategory = buildDialogInput(
            hint = "目标分类（如：餐饮 - 咖啡）",
            initial = existing?.targetCategory.orEmpty(),
        )
        layout.addView(etCategory)

        val pickBtn = CaptureUi.button(
            activity = activity,
            text = "🏷️ 点选敲敲记账分类（悬浮窗大宫格）",
            textColor = CaptureUi.SUCCESS,
            bgColor = CaptureUi.SUCCESS_SOFT,
            strokeColor = CaptureUi.SUCCESS_BORDER,
            paddingV = 9,
        ) {
            OverlayDialogs.showGridCategoryPicker(
                ctx = activity,
                currentSelectionText = etCategory.text?.toString()?.trim().orEmpty(),
                type = existing?.targetType ?: Prefs.TYPE_EXPENSE,
                allowClear = false,
                title = "选择目标分类",
            ) { selectedCategory ->
                etCategory.setText(selectedCategory)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            CaptureUi.applyTrailingIcon(
                this,
                activity,
                R.drawable.ic_chevron_down,
                CaptureUi.SUCCESS,
                11,
            )
        }
        layout.addView(pickBtn)

        // 来源与详细信息提示
        if (existing != null && existing.id != 0L) {
            val sourceLabel = when (existing.source) {
                AccountingRule.Source.USER -> "手动规则（最高优先级 P1）"
                AccountingRule.Source.SEED, AccountingRule.Source.IMPORT -> "系统预置规则（优先级 P2）"
                AccountingRule.Source.AI -> "AI 学习规则（优先级 P3）"
                else -> existing.source
            }
            layout.addView(TextView(activity).apply {
                text = "来源：$sourceLabel"
                textSize = 11.5f
                setTextColor(CaptureUi.TEXT_MUTED)
                setPadding(0, dp(8), 0, 0)
            })
            if (existing.source == AccountingRule.Source.SEED || existing.source == AccountingRule.Source.IMPORT) {
                layout.addView(TextView(activity).apply {
                    text = "提示：系统预置规则不可删除；微调后保存将自动升级为你的手动规则。"
                    textSize = 11f
                    setTextColor(CaptureUi.WARNING)
                    setPadding(0, dp(2), 0, 0)
                })
            }
        }

        val dialogTitle = when {
            isNew && !existing?.targetCategory.isNullOrBlank() -> "为「${existing?.targetCategory}」加词"
            isNew -> "新增分类规则"
            else -> "编辑分类规则"
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(dialogTitle)
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val kw = etKeyword.text?.toString()?.trim().orEmpty()
                val cat = etCategory.text?.toString()?.trim().orEmpty()
                if (kw.isEmpty() || cat.isEmpty()) {
                    toast("关键词与目标分类不能为空")
                    return@setPositiveButton
                }

                val isRegex = selectedMode == "REGEX"
                val matchMode = if (selectedMode == "ALL_TOKENS") {
                    AccountingRule.MatchMode.ALL_TOKENS
                } else {
                    AccountingRule.MatchMode.SUBSTRING
                }

                val ruleToSave = AccountingRule(
                    id = existing?.id ?: 0L,
                    keyword = kw,
                    isRegex = isRegex,
                    matchField = existing?.matchField ?: AccountingRule.MatchField.NOTE,
                    matchMode = matchMode,
                    targetCategory = cat,
                    targetType = existing?.targetType,
                    targetBook = existing?.targetBook,
                    targetAccount1 = existing?.targetAccount1,
                    targetAccount2 = existing?.targetAccount2,
                    sort = existing?.sort ?: 0,
                    enabled = existing?.enabled ?: true,
                    source = AccountingRule.Source.USER,
                    learnedAt = existing?.learnedAt,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )

                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(activity).accountingRuleDao().upsert(ruleToSave)
                        AccountingRuleEngine.invalidateCache()
                    }
                    toast("已保存「$kw」规则")
                    onDataChanged()
                }
            }
            .setNegativeButton("取消", null)

        if (existing != null && existing.id != 0L) {
            builder.setNeutralButton("删除规则") { _, _ ->
                if (existing.source == AccountingRule.Source.SEED || existing.source == AccountingRule.Source.IMPORT) {
                    AlertDialog.Builder(activity)
                        .setTitle("预置规则不可删除")
                        .setMessage("这是系统预置的基础分类规则，不能彻底删除。你可以直接在卡片上点击停用该规则。")
                        .setPositiveButton("我知道了", null)
                        .setNegativeButton("一键停用") { _, _ ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AppDatabase.getDatabase(activity).accountingRuleDao().update(
                                        existing.copy(enabled = false, updatedAt = System.currentTimeMillis())
                                    )
                                    AccountingRuleEngine.invalidateCache()
                                }
                                toast("已停用「${existing.keyword}」规则")
                                onDataChanged()
                            }
                        }
                        .show()
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle("确认删除")
                        .setMessage("确定要删除「${existing.keyword}」规则吗？")
                        .setPositiveButton("删除") { _, _ ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AppDatabase.getDatabase(activity).accountingRuleDao().deleteById(existing.id)
                                    AccountingRuleEngine.invalidateCache()
                                }
                                toast("已删除「${existing.keyword}」规则")
                                onDataChanged()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }

        builder.show()
    }

    // ════════════════════════════════════════════════════════
    //  Tab 3: 设置与调试页
    // ════════════════════════════════════════════════════════

    private data class ModeChoice(
        val key: String,
        val row: LinearLayout,
        val title: TextView,
        val desc: TextView,
    )

    private fun buildRuleModeCard(): View {
        val card = CaptureUi.card(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            addView(TextView(activity).apply {
                text = "分类与规则"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
            })
            addView(TextView(activity).apply {
                text = "下面两个开关互相独立：上面只管“分类要不要先确认”，下面只管“保存后要不要写规则”。"
                textSize = 12.5f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(4), 0, dp(10))
            })
        }

        fun paint(choices: List<ModeChoice>, selectedKey: String) {
            choices.forEach { choice ->
                val selected = choice.key == selectedKey
                choice.row.background = CaptureUi.rippleBg(
                    fillColor = if (selected) CaptureUi.BRAND_SOFT else CaptureUi.BG_SUBTLE,
                    radiusPx = dp(12),
                    strokeWidthPx = dp(1),
                    strokeColor = if (selected) CaptureUi.BRAND else CaptureUi.BORDER,
                )
                choice.title.setTextColor(if (selected) CaptureUi.BRAND_DARK else CaptureUi.TEXT_PRIMARY)
            }
        }

        fun makeChoice(
            key: String,
            title: String,
            desc: String,
            onClick: () -> Unit,
        ): ModeChoice {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(10), dp(13), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                isClickable = true
                setOnClickListener { onClick() }
            }
            val titleView = TextView(activity).apply {
                text = title
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
            }
            val descView = TextView(activity).apply {
                text = desc
                textSize = 11.5f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setPadding(0, dp(2), 0, 0)
            }
            row.addView(titleView)
            row.addView(descView)
            return ModeChoice(key, row, titleView, descView)
        }

        fun sectionLabel(text: String): TextView = TextView(activity).apply {
            this.text = text
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(6), 0, dp(6))
        }

        var aiMode = CapturePrefs.getAiCategoryMode(activity)
        val aiSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        aiSection.addView(sectionLabel("AI 分类后"))
        val aiChoices = mutableListOf<ModeChoice>()
        fun addAi(key: String, title: String, desc: String) {
            val choice = makeChoice(key, title, desc) {
                aiMode = key
                CapturePrefs.setAiCategoryMode(activity, key)
                paint(aiChoices, aiMode)
            }
            aiChoices += choice
            aiSection.addView(choice.row)
        }
        addAi(CapturePrefs.AI_CATEGORY_CONFIRM, "先确认", "只控制分类：AI 分类后先显示建议，点确认才入账")
        addAi(CapturePrefs.AI_CATEGORY_DIRECT, "直接入账再提示", "只控制分类：AI 分类后直接记账，结果卡可改")
        card.addView(aiSection)
        paint(aiChoices, aiMode)

        var learnMode = CapturePrefs.getRuleLearnMode(activity)
        val learnSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        learnSection.addView(sectionLabel("规则学习方式"))
        val learnChoices = mutableListOf<ModeChoice>()
        fun addLearn(key: String, title: String, desc: String) {
            val choice = makeChoice(key, title, desc) {
                learnMode = key
                CapturePrefs.setRuleLearnMode(activity, key)
                paint(learnChoices, learnMode)
            }
            learnChoices += choice
            learnSection.addView(choice.row)
        }
        addLearn(CapturePrefs.RULE_LEARN_AUTO, "直接学", "保存成功后静默写入规则，并提示已记住（受上面“先确认”影响：确认分类后仍会直接学）")
        addLearn(CapturePrefs.RULE_LEARN_ASK, "问一次", "入账后出结果卡问一次，同意才学习；和上面的分类确认独立")
        addLearn(CapturePrefs.RULE_LEARN_OFF, "不学", "不自动新建规则；已有规则修正提示仍然保留")
        card.addView(learnSection)
        paint(learnChoices, learnMode)

        return card
    }

    fun buildSettingsTab(
        onSimulateExpense: () -> Unit,
        onSimulateWcdb: () -> Unit,
        onTestRuleSamples: (() -> Unit)? = null,
        onCustomSimulateExpense: (() -> Unit)? = null,
    ): View {
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

        val hasOverlayPerm = CaptureBubbleManager.canDrawOverlays(activity)
        var bubbleEnabled = CapturePrefs.isBubbleEnabled(activity)
        var notifEnabled = CapturePrefs.isNotificationEnabled(activity)

        val promptCard = CaptureUi.card(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }

            addView(TextView(activity).apply {
                text = "付款瞬间提醒方式"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
            })
            addView(TextView(activity).apply {
                text = "选择付款成功那一刻插件如何提示你（多选组合，或选完全静默；无论选哪种，敲击手机背面都能在记账悬浮窗里看到待记）："
                textSize = 12.5f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(4), 0, dp(12))
            })

            data class OptionItem(
                val key: String,
                val title: String,
                val desc: String,
            )
            val optionItems = listOf(
                OptionItem(
                    "BUBBLE",
                    "底部轻胶囊气泡",
                    "付款后在屏幕底部浮出轻胶囊，可点开顺手补一句备注",
                ),
                OptionItem(
                    "NOTIFICATION",
                    "下拉通知栏待记",
                    "通知栏展示分组待记卡片，支持每笔快捷打字补备注与不计",
                ),
                OptionItem(
                    "SILENT",
                    "完全静默留底",
                    "付款时不打扰，全部攒进待记，等敲击手机背面记账时统一处理",
                ),
            )

            class OptionViewHolder(
                val card: LinearLayout,
                val iconView: ImageView,
                val titleView: TextView,
                val descView: TextView,
            )

            val viewHolders = mutableMapOf<String, OptionViewHolder>()

            fun updateViewsState() {
                val silent = !bubbleEnabled && !notifEnabled
                optionItems.forEach { item ->
                    val isChecked = when (item.key) {
                        "BUBBLE" -> bubbleEnabled
                        "NOTIFICATION" -> notifEnabled
                        "SILENT" -> silent
                        else -> false
                    }
                    val holder = viewHolders[item.key] ?: return@forEach
                    holder.card.background = CaptureUi.rippleBg(
                        fillColor = if (isChecked) CaptureUi.BRAND_SOFT else CaptureUi.BG_SUBTLE,
                        radiusPx = dp(12),
                        strokeWidthPx = dp(1),
                        strokeColor = if (isChecked) CaptureUi.BRAND else CaptureUi.BORDER,
                    )
                    holder.iconView.setImageDrawable(
                        CaptureUi.tintedDrawable(
                            activity,
                            if (isChecked) R.drawable.ic_book_selected_check else R.drawable.ic_book_unselected_box,
                            if (isChecked) CaptureUi.BRAND else CaptureUi.TEXT_MUTED,
                        )
                    )
                    holder.titleView.setTextColor(if (isChecked) CaptureUi.BRAND_DARK else CaptureUi.TEXT_PRIMARY)
                }
            }

            optionItems.forEach { item ->
                val optionCard = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(13), dp(11), dp(13), dp(11))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8) }
                }
                val checkIcon = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                        marginEnd = dp(10)
                    }
                }
                optionCard.addView(checkIcon)

                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val titleTv = TextView(activity).apply {
                    text = item.title
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                }
                val descTv = TextView(activity).apply {
                    text = item.desc
                    textSize = 11.5f
                    setTextColor(CaptureUi.TEXT_SECONDARY)
                    setPadding(0, dp(2), 0, 0)
                }
                textCol.addView(titleTv)
                textCol.addView(descTv)
                optionCard.addView(textCol)

                viewHolders[item.key] = OptionViewHolder(optionCard, checkIcon, titleTv, descTv)

                optionCard.setOnClickListener {
                    when (item.key) {
                        "BUBBLE" -> {
                            val newBubble = !bubbleEnabled
                            bubbleEnabled = newBubble
                            CapturePrefs.setBubbleEnabled(activity, newBubble)
                        }
                        "NOTIFICATION" -> {
                            val newNotif = !notifEnabled
                            notifEnabled = newNotif
                            CapturePrefs.setNotificationEnabled(activity, newNotif)
                        }
                        "SILENT" -> {
                            bubbleEnabled = false
                            notifEnabled = false
                            CapturePrefs.setSilent(activity)
                        }
                    }
                    updateViewsState()
                    scope.launch { CaptureNotifier.refreshAll(activity) }
                }

                addView(optionCard)
            }

            updateViewsState()

            if (!hasOverlayPerm) {
                addView(
                    CaptureUi.button(
                        activity = activity,
                        text = "授权插件悬浮窗权限（用于底部轻气泡）",
                        textColor = CaptureUi.WARNING,
                        bgColor = CaptureUi.WARNING_SOFT,
                        strokeColor = CaptureUi.WARNING_BORDER,
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${activity.packageName}"),
                            )
                            runCatching { activity.startActivity(intent) }
                        }
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(4) }
                    }
                )
            }
        }
        container.addView(promptCard)
        container.addView(buildRuleModeCard())

        val transferDedupCard = CaptureUi.card(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }

            val headerRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = CaptureUi.rippleBg(activity, CaptureUi.BG_SURFACE)
            }
            val headerText = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            headerText.addView(TextView(activity).apply {
                text = "转账到账自动判重"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
            })
            headerText.addView(TextView(activity).apply {
                text = "给账户转账/提现后，收款方再推送的“入账/收款”通知默认归入「重复」，不再多记一笔收入。"
                textSize = 12f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(2), dp(8), 0)
            })
            headerRow.addView(headerText)
            val swDedup = Switch(activity).apply {
                isChecked = CapturePrefs.isTransferIncomeDedupEnabled(activity)
                setOnCheckedChangeListener { _, checked ->
                    CapturePrefs.setTransferIncomeDedupEnabled(activity, checked)
                }
            }
            headerRow.addView(swDedup)
            headerRow.setOnClickListener { swDedup.toggle() }
            addView(headerRow)

            addView(TextView(activity).apply {
                text = "判重时间窗（转账与到账通知的最大间隔）"
                textSize = 12f
                setTextColor(CaptureUi.TEXT_MUTED)
                setPadding(0, dp(12), 0, 0)
            })

            val windowRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            val currentWindow = CapturePrefs.getTransferIncomeDedupWindowSeconds(activity)
            val presetOptions = listOf(
                60 to "1分钟",
                180 to "3分钟",
                300 to "5分钟",
                600 to "10分钟",
            )
            val presetSeconds = presetOptions.map { it.first }.toSet()
            val optionViews = mutableListOf<TextView>()
            lateinit var customTv: TextView

            fun styleCapsule(tv: TextView, isSel: Boolean) {
                tv.setTypeface(tv.typeface, if (isSel) Typeface.BOLD else Typeface.NORMAL)
                tv.setTextColor(if (isSel) CaptureUi.BRAND_DARK else CaptureUi.TEXT_SECONDARY)
                tv.background = CaptureUi.rippleBg(
                    fillColor = if (isSel) CaptureUi.BRAND_SOFT else CaptureUi.BG_SUBTLE,
                    radiusPx = dp(999),
                    strokeWidthPx = dp(1),
                    strokeColor = if (isSel) CaptureUi.BRAND else CaptureUi.BORDER,
                )
            }

            fun updateWindowSelection(chosenSeconds: Int) {
                CapturePrefs.setTransferIncomeDedupWindowSeconds(activity, chosenSeconds)
                presetOptions.forEachIndexed { idx, (sec, _) ->
                    val isSel = sec == chosenSeconds
                    val tv = optionViews.getOrNull(idx) ?: return@forEachIndexed
                    styleCapsule(tv, isSel)
                }
                val isCustom = chosenSeconds !in presetSeconds
                customTv.text = if (isCustom) "${chosenSeconds}秒" else "自定义"
                styleCapsule(customTv, isCustom)
            }

            presetOptions.forEachIndexed { index, (seconds, label) ->
                val selected = currentWindow == seconds
                val tv = TextView(activity).apply {
                    text = label
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    styleCapsule(this, selected)
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(6)
                    }
                    setOnClickListener { updateWindowSelection(seconds) }
                }
                optionViews.add(tv)
                windowRow.addView(tv)
            }

            val isCustomInitial = currentWindow !in presetSeconds
            customTv = TextView(activity).apply {
                text = if (isCustomInitial) "${currentWindow}秒" else "自定义"
                textSize = 12.5f
                gravity = Gravity.CENTER
                styleCapsule(this, isCustomInitial)
                setPadding(dp(8), dp(7), dp(8), dp(7))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                }
                setOnClickListener {
                    val input = EditText(activity).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        hint = "输入秒数 (10~3600)"
                        val cur = CapturePrefs.getTransferIncomeDedupWindowSeconds(activity)
                        if (cur !in presetSeconds) setText(cur.toString())
                    }
                    val container = FrameLayout(activity).apply {
                        setPadding(dp(20), dp(10), dp(20), dp(4))
                        addView(input)
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("自定义判重时间窗")
                        .setMessage("请输入转账与到账通知的最大时间间隔（秒）：")
                        .setView(container)
                        .setPositiveButton("确定") { _, _ ->
                            val sec = input.text?.toString()?.toIntOrNull()
                            if (sec == null || sec < 10 || sec > 3600) {
                                toast("请输入 10~3600 之间的秒数")
                                return@setPositiveButton
                            }
                            updateWindowSelection(sec)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            windowRow.addView(customTv)
            addView(windowRow)
        }
        container.addView(transferDedupCard)

        val debugCard = CaptureUi.card(activity).apply {
            addView(TextView(activity).apply {
                text = "调试与验证工具箱"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
                setPadding(0, 0, 0, dp(10))
            })

            val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            btnRow.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "⚡ 模拟支出",
                    textColor = CaptureUi.SUCCESS,
                    bgColor = CaptureUi.SUCCESS_SOFT,
                    strokeColor = CaptureUi.SUCCESS_BORDER,
                ) { onSimulateExpense() }.apply {
                    setOnLongClickListener {
                        if (onCustomSimulateExpense != null) {
                            onCustomSimulateExpense()
                        } else {
                            showCustomSimulateDialog()
                        }
                        true
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
            )
            btnRow.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "📦 真实报文",
                    textColor = CaptureUi.BRAND_DARK,
                    bgColor = CaptureUi.BRAND_SOFT,
                    strokeColor = CaptureUi.BRAND_BORDER,
                ) { onSimulateWcdb() }.apply {
                    setOnLongClickListener {
                        if (onCustomSimulateExpense != null) {
                            onCustomSimulateExpense()
                        } else {
                            showCustomSimulateDialog()
                        }
                        true
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(btnRow)

            val clearLink = TextView(activity).apply {
                text = "清空待记历史"
                textSize = 12f
                setTextColor(CaptureUi.TEXT_MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("清空历史")
                        .setMessage("确定清空全部待记历史吗？账单本身不受影响。")
                        .setPositiveButton("清空") { _, _ ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    CaptureApp.from(activity).repository.clearHistory()
                                }
                                toast("已清空待记历史")
                                onDataChanged()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            addView(clearLink)
        }
        container.addView(debugCard)

        val migrationCard = CaptureUi.card(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }

            addView(TextView(activity).apply {
                text = "第三方数据迁移（可选）"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
                setPadding(0, 0, 0, dp(6))
            })
            addView(TextView(activity).apply {
                text = "若曾使用过「自动记账 (52045)」，可在此按需导入历史分类/资产映射与数据规则。"
                textSize = 12f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setLineSpacing(0f, 1.2f)
                setPadding(0, 0, 0, dp(10))
            })

            val mRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            mRow.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "从自动记账导入分类/资产",
                    textColor = CaptureUi.BRAND_DARK,
                    bgColor = CaptureUi.BRAND_SOFT,
                    strokeColor = CaptureUi.BRAND_BORDER,
                    textSizeSp = 12f,
                ) { triggerAutoAccountingImport() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = dp(8) },
            )
            mRow.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "同步本机数据规则",
                    textColor = CaptureUi.TEXT_SECONDARY,
                    bgColor = CaptureUi.BG_SUBTLE,
                    strokeColor = CaptureUi.BORDER,
                    textSizeSp = 12f,
                ) { onSyncDataRules?.invoke() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(mRow)
        }
        container.addView(migrationCard)

        return scroll
    }

    fun buildCategoryDisplayList(ctx: AccountingContext?, billType: Int? = null): List<String> {
        val allCats = ctx?.categories.orEmpty()
        if (allCats.isEmpty()) return emptyList()
        val byId = allCats.associateBy { it.id }
        val filtered = if (billType != null) {
            allCats.filter { it.type == billType }.ifEmpty { allCats }
        } else {
            allCats
        }
        return filtered.map { cat ->
            val parent = cat.parentId?.let { byId[it] }
            if (parent != null) "${parent.name} - ${cat.name}" else cat.name
        }.distinct().sorted()
    }

    private fun buildSearchBox(hintText: String): EditText = EditText(activity).apply {
        hint = "🔍 $hintText"
        textSize = 13f
        isSingleLine = true
        setTextColor(CaptureUi.TEXT_PRIMARY)
        setHintTextColor(CaptureUi.TEXT_MUTED)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = CaptureUi.roundedBg(Color.WHITE, dp(12), dp(1), CaptureUi.BORDER)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    private fun buildDialogInput(hint: String, initial: String): EditText = EditText(activity).apply {
        this.hint = hint
        setText(initial)
        isSingleLine = true
        textSize = 14f
        setTextColor(CaptureUi.TEXT_PRIMARY)
        setHintTextColor(CaptureUi.TEXT_MUTED)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(10), dp(1), CaptureUi.BORDER)
    }

    private fun buildEmptyHint(text: String): View = TextView(activity).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(CaptureUi.TEXT_MUTED)
        setPadding(dp(16), dp(24), dp(16), dp(24))
    }

    private fun triggerAutoAccountingImport() {
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在从自动记账导入")
            .setMessage("正在连接本地 127.0.0.1:52045 拉取资产映射与分类规则...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        scope.launch {
            val result = AutoAccountingRuleImporter.importFromLocalServer(activity)
            progressDialog.dismiss()
            if (result.isSuccess) {
                AlertDialog.Builder(activity)
                    .setTitle("导入成功")
                    .setMessage("成功同步导入：\n• 资产映射：${result.assetCount} 条\n• 分类规则：${result.categoryCount} 条")
                    .setPositiveButton("好的", null)
                    .show()
                onDataChanged()
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("连接自动记账服务失败")
                    .setMessage("${result.error}\n\n排查建议：\n1. 确保同机已安装并运行「自动记账」\n2. 确保自动记账的本地核心服务已启动（端口 52045）\n3. 检查是否有权限或省电策略拦截了本机环回通信")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    fun showCustomSimulateDialog(
        onSubmit: ((source: String, amountCents: Long, merchant: String, note: String?) -> Unit)? = null,
    ) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        container.addView(TextView(activity).apply {
            text = "金额（元）"
            textSize = 12f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(4))
        })
        val etAmount = EditText(activity).apply {
            hint = "如 88.50"
            setText("88.50")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(8), dp(1), CaptureUi.BORDER)
        }
        container.addView(etAmount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        container.addView(TextView(activity).apply {
            text = "商户名称"
            textSize = 12f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(4))
        })
        val etMerchant = EditText(activity).apply {
            hint = "如 自定义星巴克"
            setText("自定义星巴克")
            isSingleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(8), dp(1), CaptureUi.BORDER)
        }
        container.addView(etMerchant, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        container.addView(TextView(activity).apply {
            text = "商品说明"
            textSize = 12f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(4))
        })
        val etDesc = EditText(activity).apply {
            hint = "如 拿铁咖啡（可留空）"
            setText("拿铁咖啡")
            isSingleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(8), dp(1), CaptureUi.BORDER)
        }
        container.addView(etDesc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        container.addView(TextView(activity).apply {
            text = "支付渠道"
            textSize = 12f
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(4))
        })
        var selectedSource = CaptureSource.WECHAT
        val channelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnWechat = TextView(activity).apply {
            text = "微信支付"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val btnAlipay = TextView(activity).apply {
            text = "支付宝"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        fun updateChannelUi() {
            val isWechat = selectedSource == CaptureSource.WECHAT
            btnWechat.setTextColor(if (isWechat) CaptureUi.WECHAT else CaptureUi.TEXT_SECONDARY)
            btnWechat.background = CaptureUi.rippleBg(
                fillColor = if (isWechat) CaptureUi.WECHAT_SOFT else CaptureUi.BG_SUBTLE,
                radiusPx = dp(8),
                strokeWidthPx = dp(1),
                strokeColor = if (isWechat) CaptureUi.WECHAT else CaptureUi.BORDER,
            )
            btnAlipay.setTextColor(if (!isWechat) CaptureUi.ALIPAY else CaptureUi.TEXT_SECONDARY)
            btnAlipay.background = CaptureUi.rippleBg(
                fillColor = if (!isWechat) CaptureUi.ALIPAY_SOFT else CaptureUi.BG_SUBTLE,
                radiusPx = dp(8),
                strokeWidthPx = dp(1),
                strokeColor = if (!isWechat) CaptureUi.ALIPAY else CaptureUi.BORDER,
            )
        }
        btnWechat.setOnClickListener {
            selectedSource = CaptureSource.WECHAT
            updateChannelUi()
        }
        btnAlipay.setOnClickListener {
            selectedSource = CaptureSource.ALIPAY
            updateChannelUi()
        }
        updateChannelUi()
        channelRow.addView(btnWechat, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        channelRow.addView(btnAlipay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(channelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

        AlertDialog.Builder(activity)
            .setTitle("自定义模拟待记")
            .setView(container)
            .setPositiveButton("生成待记") { _, _ ->
                val amountDouble = etAmount.text?.toString()?.toDoubleOrNull() ?: 88.50
                val amountCents = (amountDouble * 100.0).toLong().coerceAtLeast(1L)
                val merchant = etMerchant.text?.toString()?.trim().takeIf { !it.isNullOrBlank() } ?: "自定义星巴克"
                val note = etDesc.text?.toString()?.trim().takeIf { !it.isNullOrBlank() }
                if (onSubmit != null) {
                    onSubmit(selectedSource, amountCents, merchant, note)
                } else {
                    simulateCustomCapture(selectedSource, amountCents, merchant, note)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun simulateCustomCapture(
        source: String,
        amountCents: Long,
        merchant: String,
        note: String?,
    ) {
        val now = System.currentTimeMillis()
        val appContext = activity.applicationContext
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val rawMethod = if (source == CaptureSource.ALIPAY) "支付宝余额" else "零钱"
                    val mappedAsset = CaptureRuleMatcher.resolveAssetName(appContext, rawMethod)
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
                        ruleVersion = "simulated-custom",
                    )
                    val repo = CaptureApp.from(activity).repository
                    repo.insert(
                        payload,
                        transferIncomeDedupEnabled = CapturePrefs.isTransferIncomeDedupEnabled(appContext),
                        transferIncomeDedupWindowMillis = CapturePrefs.getTransferIncomeDedupWindowMillis(appContext),
                    )
                    val pendingList = repo.listPending(10, true)
                    Pair(payload, pendingList)
                }
                val saved = outcome.second.firstOrNull { it.id == outcome.first.captureId }
                if (saved != null && CapturePrefs.getPromptMode(activity) == CapturePrefs.MODE_BUBBLE) {
                    CaptureBubbleManager.showBubble(activity, saved, outcome.second.size)
                }
                toast("已生成自定义待记")
                onDataChanged()
            } catch (t: Throwable) {
                toast("生成失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}

/**
 * 矩阵流式胶囊布局容器：自动折行，保证多关键词胶囊整齐自然排列
 */
private class ChipsFlowLayout(
    context: Context,
    private val horizontalSpacing: Int = 0,
    private val verticalSpacing: Int = 0,
) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val maxWidth = widthSize - paddingLeft - paddingRight

        var currentLineWidth = 0
        var currentLineHeight = 0
        var totalHeight = 0
        var maxLineWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childW = child.measuredWidth
            val childH = child.measuredHeight

            if (currentLineWidth + childW > maxWidth && currentLineWidth > 0) {
                maxLineWidth = maxOf(maxLineWidth, currentLineWidth - horizontalSpacing)
                totalHeight += currentLineHeight + verticalSpacing
                currentLineWidth = childW + horizontalSpacing
                currentLineHeight = childH
            } else {
                currentLineWidth += childW + horizontalSpacing
                currentLineHeight = maxOf(currentLineHeight, childH)
            }
        }

        maxLineWidth = maxOf(maxLineWidth, if (currentLineWidth > 0) currentLineWidth - horizontalSpacing else 0)
        totalHeight += currentLineHeight

        val finalWidth = resolveSize(maxLineWidth + paddingLeft + paddingRight, widthMeasureSpec)
        val finalHeight = resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxRight = (r - l) - paddingRight
        var curX = paddingLeft
        var curY = paddingTop
        var currentLineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            val childW = child.measuredWidth
            val childH = child.measuredHeight

            if (curX + childW > maxRight && curX > paddingLeft) {
                curX = paddingLeft
                curY += currentLineHeight + verticalSpacing
                currentLineHeight = childH
            } else {
                currentLineHeight = maxOf(currentLineHeight, childH)
            }

            child.layout(curX, curY, curX + childW, curY + childH)
            curX += childW + horizontalSpacing
        }
    }
}

