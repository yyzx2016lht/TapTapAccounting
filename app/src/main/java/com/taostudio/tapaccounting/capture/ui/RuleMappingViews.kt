package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.AccountingContext
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.CaptureSources
import com.taostudio.tapaccounting.capture.data.AssetMappingRule
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.data.CategoryRule
import com.taostudio.tapaccounting.capture.history.AutoAccountingRuleImporter
import com.taostudio.tapaccounting.capture.notify.CaptureBubbleManager
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginEnd = dp(6) },
            )
            btnRow.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "恢复通用",
                    textColor = CaptureUi.TEXT_SECONDARY,
                    bgColor = Color.parseColor("#F1F5F9"),
                    strokeColor = Color.parseColor("#CBD5E1"),
                    paddingH = 10,
                ) {
                    AlertDialog.Builder(activity)
                        .setTitle("恢复通用通道预置")
                        .setMessage("将补齐常用支付渠道与钱包基础映射（不含任何具体个人银行卡）。不会删除你已自定义的规则。")
                        .setPositiveButton("恢复") { _, _ ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    CaptureRuleMatcher.ensureSeeded(activity, force = true)
                                }
                                toast("已恢复通用基础映射")
                                onDataChanged()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
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
            setPadding(dp(2), dp(9), dp(2), dp(9))
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showEditAssetMappingDialog(rule, appAssets) }
        }
        textCol.addView(TextView(activity).apply {
            text = rule.pattern
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (rule.enabled) CaptureUi.TEXT_PRIMARY else CaptureUi.TEXT_MUTED)
        })
        textCol.addView(TextView(activity).apply {
            text = if (rule.isRegex) "正则匹配 · 点击编辑" else "包含/精确匹配 · 点击编辑"
            textSize = 11f
            setTextColor(CaptureUi.TEXT_MUTED)
        })
        row.addView(textCol)

        row.addView(Switch(activity).apply {
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, checked ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        CaptureApp.from(activity).database.assetMappingDao()
                            .update(rule.copy(enabled = checked))
                    }
                    onDataChanged()
                }
            }
        })

        row.addView(TextView(activity).apply {
            text = "删除"
            textSize = 12f
            setTextColor(CaptureUi.DANGER)
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        CaptureApp.from(activity).database.assetMappingDao().deleteById(rule.id)
                    }
                    onDataChanged()
                }
            }
        })
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

        if (appAssets.isNotEmpty()) {
            val pickBtn = CaptureUi.button(
                activity = activity,
                text = "从敲敲记账账户列表中点选 ▾",
                textColor = CaptureUi.BRAND_DARK,
                bgColor = CaptureUi.BRAND_SOFT,
                strokeColor = CaptureUi.BRAND_BORDER,
                paddingV = 9,
            ) {
                AlertDialog.Builder(activity)
                    .setTitle("选择敲敲记账账户")
                    .setItems(appAssets.toTypedArray()) { _, which ->
                        etTarget.setText(appAssets[which])
                    }
                    .show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            }
            layout.addView(pickBtn)
        }

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
                    withContext(Dispatchers.IO) {
                        CaptureApp.from(activity).database.assetMappingDao().upsert(
                            AssetMappingRule(
                                id = existing?.id ?: 0L,
                                pattern = p,
                                targetAssetName = t,
                                isRegex = cbRegex.isChecked,
                                sort = existing?.sort ?: 0,
                                enabled = existing?.enabled ?: true,
                            )
                        )
                    }
                    onDataChanged()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ════════════════════════════════════════════════════════
    //  Tab 2: 分类规则页（按目标分类聚合关键词胶囊 + 搜索过滤）
    // ════════════════════════════════════════════════════════

    fun buildCategoryRuleTab(
        rules: List<CategoryRule>,
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
        val infoCard = CaptureUi.card(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }

            addView(TextView(activity).apply {
                text = "本地秒级分类规则（${rules.size} 条）"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
            })
            addView(TextView(activity).apply {
                text = "只按用户备注匹配分类；商户规则保留但不再自动命中。付款时可在备注里写“咖啡”“打车”“房租”等关键词。AI 确认入账后也会自动学习备注关键词，标记为「AI 学习」。"
                textSize = 12.5f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(4), 0, dp(12))
            })
            val catBtnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            catBtnRow.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "+ 新增分类规则",
                    textColor = Color.WHITE,
                    bgColor = CaptureUi.BRAND,
                ) { showEditCategoryRuleDialog(null, categoryPaths) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(catBtnRow)

            val merchantCount = rules.count { it.matchField == CategoryRule.MATCH_MERCHANT }
            if (merchantCount > 0) {
                val convertRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                }
                convertRow.addView(
                    CaptureUi.button(
                        activity = activity,
                        text = "⚡ 一键将 $merchantCount 条商户规则转为自动匹配（支持备注）",
                        textColor = CaptureUi.BRAND_DARK,
                        bgColor = CaptureUi.BRAND_SOFT,
                        strokeColor = CaptureUi.BRAND_BORDER,
                        paddingV = 8,
                        textSizeSp = 12f,
                    ) {
                        scope.launch {
                            val count = withContext(Dispatchers.IO) {
                                CaptureApp.from(activity).database.categoryRuleDao()
                                    .convertAllMerchantRules(CategoryRule.MATCH_ANY)
                            }
                            toast("已将 $count 条规则转为自动匹配")
                            onDataChanged()
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                addView(convertRow)
            }
        }
        container.addView(infoCard)

        val searchBox = buildSearchBox("搜索关键词或分类（如：咖啡、美宜佳、交通）")
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
                    it.keyword.contains(q, ignoreCase = true) ||
                        it.targetCategoryName.contains(q, ignoreCase = true)
                }
            }
            if (filtered.isEmpty()) {
                listHost.addView(buildEmptyHint("没有匹配的分类规则"))
                return
            }

            // 按目标分类聚合：每个分类一张卡片，下面展示紧凑关键词标签矩阵
            val grouped = filtered.groupBy { it.targetCategoryName.ifBlank { "未分类" } }
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
                    setPadding(0, 0, 0, dp(8))
                }
                header.addView(TextView(activity).apply {
                    text = "🏷️ $targetCat"
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(CaptureUi.TEXT_PRIMARY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                header.addView(
                    CaptureUi.chip(
                        activity = activity,
                        text = "+ 加词",
                        textColor = CaptureUi.BRAND_DARK,
                        bgColor = CaptureUi.BRAND_SOFT,
                    ) {
                        showEditCategoryRuleDialog(
                            existing = CategoryRule(
                                id = 0L,
                                keyword = "",
                                matchField = CategoryRule.MATCH_ANY,
                                isRegex = false,
                                targetCategoryName = targetCat,
                            ),
                            categoryPaths = categoryPaths,
                        )
                    }
                )
                groupCard.addView(header)

                // 每行最多排 3 个关键词胶囊，整洁紧凑
                val chunks = catRules.chunked(3)
                chunks.forEach { rowRules ->
                    val chipRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(3), 0, dp(3))
                    }
                    rowRules.forEach { rule ->
                        val sourceTag = CategoryRule.sourceLabel(rule.source)
                        val scopeTag = if (rule.matchField == CategoryRule.MATCH_MERCHANT) "不自动匹配: " else ""
                        val isAi = rule.source == CategoryRule.SOURCE_AI
                        val pill = CaptureUi.chip(
                            activity = activity,
                            text = "$sourceTag · $scopeTag${rule.keyword}",
                            textColor = when {
                                isAi -> CaptureUi.BRAND_DARK
                                rule.enabled -> CaptureUi.TEXT_SECONDARY
                                else -> CaptureUi.TEXT_MUTED
                            },
                            bgColor = if (isAi) CaptureUi.BRAND_SOFT else CaptureUi.BG_SUBTLE,
                            strokeColor = if (isAi) CaptureUi.BRAND_BORDER else CaptureUi.BORDER,
                        ) {
                            showEditCategoryRuleDialog(rule, categoryPaths)
                        }
                        chipRow.addView(
                            pill,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { marginEnd = dp(6) },
                        )
                    }
                    groupCard.addView(chipRow)
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

    private fun showEditCategoryRuleDialog(existing: CategoryRule?, categoryPaths: List<String>) {
        val isNew = existing == null || existing.id == 0L
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        val etKeyword = buildDialogInput(
            hint = "匹配关键词（如：咖啡、打车、房租）",
            initial = existing?.keyword.orEmpty(),
        )
        val etCategory = buildDialogInput(
            hint = "目标分类（如：餐饮 - 咖啡）",
            initial = existing?.targetCategoryName.orEmpty(),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        layout.addView(etKeyword)
        layout.addView(etCategory)

        if (existing != null && existing.id != 0L) {
            val learnedAt = existing.learnedAt
            val sourceText = buildString {
                append("来源：").append(CategoryRule.sourceLabel(existing.source))
                if (learnedAt != null && learnedAt > 0L) {
                    append(" · 学习于 ")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(learnedAt)))
                }
            }
            layout.addView(TextView(activity).apply {
                text = sourceText
                textSize = 11.5f
                setTextColor(CaptureUi.TEXT_MUTED)
                setPadding(0, dp(6), 0, 0)
            })
        }

        if (categoryPaths.isNotEmpty()) {
            val pickBtn = CaptureUi.button(
                activity = activity,
                text = "从敲敲记账分类树中点选 ▾",
                textColor = CaptureUi.SUCCESS,
                bgColor = CaptureUi.SUCCESS_SOFT,
                strokeColor = CaptureUi.SUCCESS_BORDER,
                paddingV = 9,
            ) {
                AlertDialog.Builder(activity)
                    .setTitle("选择敲敲记账分类")
                    .setItems(categoryPaths.toTypedArray()) { _, which ->
                        etCategory.setText(categoryPaths[which])
                    }
                    .show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            }
            layout.addView(pickBtn)
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (isNew) "新增分类规则" else "编辑分类规则")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val kw = etKeyword.text?.toString()?.trim().orEmpty()
                val cat = etCategory.text?.toString()?.trim().orEmpty()
                if (kw.isEmpty() || cat.isEmpty()) {
                    toast("关键词与目标分类不能为空")
                    return@setPositiveButton
                }
                scope.launch {
                    withContext(Dispatchers.IO) {
                        CaptureApp.from(activity).database.categoryRuleDao().upsert(
                            CategoryRule(
                                id = existing?.id ?: 0L,
                                keyword = kw,
                                matchField = if (existing?.matchField == CategoryRule.MATCH_MERCHANT) {
                                    CategoryRule.MATCH_ANY
                                } else {
                                    existing?.matchField ?: CategoryRule.MATCH_ANY
                                },
                                isRegex = existing?.isRegex ?: false,
                                targetCategoryName = cat,
                                sort = existing?.sort ?: 0,
                                enabled = existing?.enabled ?: true,
                                // 用户在规则页手动保存后，这条规则归“我的”，AI 以后不得覆盖。
                                source = CategoryRule.SOURCE_USER,
                                learnedAt = null,
                            )
                        )
                    }
                    onDataChanged()
                }
            }
            .setNegativeButton("取消", null)

        if (existing != null && existing.id != 0L) {
            builder.setNeutralButton("删除此词") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        CaptureApp.from(activity).database.categoryRuleDao().deleteById(existing.id)
                    }
                    onDataChanged()
                }
            }
        }
        builder.show()
    }

    // ════════════════════════════════════════════════════════
    //  Tab 3: 设置与调试页
    // ════════════════════════════════════════════════════════

    fun buildSettingsTab(
        onSimulateExpense: () -> Unit,
        onSimulateWcdb: () -> Unit,
        onTestRuleSamples: () -> Unit,
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

        val currentMode = CapturePrefs.getPromptMode(activity)
        val hasOverlayPerm = CaptureBubbleManager.canDrawOverlays(activity)

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
                text = "选择付款成功那一刻插件如何提示你（无论选哪种，敲击手机背面都能在记账悬浮窗里看到待记）："
                textSize = 12.5f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(4), 0, dp(12))
            })

            val modes = listOf(
                Triple(
                    CapturePrefs.MODE_BUBBLE,
                    "底部轻胶囊气泡",
                    "付款后在屏幕底部浮出 4 秒轻提示，可点开顺手补一句备注",
                ),
                Triple(
                    CapturePrefs.MODE_NOTIFICATION,
                    "仅通知栏静默汇总",
                    "不弹悬浮气泡；下拉通知栏可直接打字补最新一笔备注",
                ),
                Triple(
                    CapturePrefs.MODE_SILENT,
                    "完全静默留底",
                    "付款时不打扰，全部攒进待记，等敲击手机背面记账时统一处理",
                ),
            )
            modes.forEach { (modeKey, title, desc) ->
                val selected = currentMode == modeKey
                val optionCard = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(13), dp(11), dp(13), dp(11))
                    background = CaptureUi.rippleBg(
                        fillColor = if (selected) CaptureUi.BRAND_SOFT else CaptureUi.BG_SUBTLE,
                        radiusPx = dp(12),
                        strokeWidthPx = dp(1),
                        strokeColor = if (selected) CaptureUi.BRAND else CaptureUi.BORDER,
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8) }
                    setOnClickListener {
                        CapturePrefs.setPromptMode(activity, modeKey)
                        scope.launch { CaptureNotifier.refreshAll(activity) }
                        onDataChanged()
                    }
                }
                val radioDot = TextView(activity).apply {
                    text = if (selected) "●" else "○"
                    textSize = 15f
                    setTextColor(if (selected) CaptureUi.BRAND else CaptureUi.TEXT_MUTED)
                    setPadding(0, 0, dp(10), 0)
                }
                optionCard.addView(radioDot)

                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(activity).apply {
                    text = title
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (selected) CaptureUi.BRAND_DARK else CaptureUi.TEXT_PRIMARY)
                })
                textCol.addView(TextView(activity).apply {
                    text = desc
                    textSize = 11.5f
                    setTextColor(CaptureUi.TEXT_SECONDARY)
                    setPadding(0, dp(2), 0, 0)
                })
                optionCard.addView(textCol)
                addView(optionCard)
            }

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

        val filterCard = CaptureUi.card(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(activity).apply {
                text = "仅提醒支出与退款（忽略普通收入）"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
            })
            textCol.addView(TextView(activity).apply {
                text = "默认关闭（同时捕获支出、${CaptureSources.displayName(CaptureSource.WECHAT)}转账收款、转账退款与红包）；开启后忽略普通收款"
                textSize = 12f
                setTextColor(CaptureUi.TEXT_SECONDARY)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(textCol)
            row.addView(Switch(activity).apply {
                isChecked = CapturePrefs.isOnlyExpense(activity)
                setOnCheckedChangeListener { _, checked ->
                    CapturePrefs.setOnlyExpense(activity, checked)
                    scope.launch { CaptureNotifier.refreshAll(activity) }
                    onDataChanged()
                }
            })
            addView(row)
        }
        container.addView(filterCard)

        val transferDedupCard = CaptureUi.card(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }

            val headerRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
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
            headerRow.addView(Switch(activity).apply {
                isChecked = CapturePrefs.isTransferIncomeDedupEnabled(activity)
                setOnCheckedChangeListener { _, checked ->
                    CapturePrefs.setTransferIncomeDedupEnabled(activity, checked)
                    onDataChanged()
                }
            })
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
            val windowOptions = listOf(
                60 to "1分钟",
                180 to "3分钟",
                300 to "5分钟",
                600 to "10分钟",
            )
            windowOptions.forEachIndexed { index, (seconds, label) ->
                val selected = currentWindow == seconds
                windowRow.addView(TextView(activity).apply {
                    text = label
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (selected) CaptureUi.BRAND_DARK else CaptureUi.TEXT_SECONDARY)
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                    background = CaptureUi.rippleBg(
                        fillColor = if (selected) CaptureUi.BRAND_SOFT else CaptureUi.BG_SUBTLE,
                        radiusPx = dp(999),
                        strokeWidthPx = dp(1),
                        strokeColor = if (selected) CaptureUi.BRAND else CaptureUi.BORDER,
                    )
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(6)
                    }
                    setOnClickListener {
                        CapturePrefs.setTransferIncomeDedupWindowSeconds(activity, seconds)
                        onDataChanged()
                    }
                })
            }
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

            val row1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "模拟支出 (长按自定义)",
                    textColor = CaptureUi.SUCCESS,
                    bgColor = CaptureUi.SUCCESS_SOFT,
                    strokeColor = CaptureUi.SUCCESS_BORDER,
                ) { onSimulateExpense() }.apply {
                    if (onCustomSimulateExpense != null) {
                        setOnLongClickListener {
                            onCustomSimulateExpense()
                            true
                        }
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
            )
            row1.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "模拟 WCDB 真实包",
                    textColor = CaptureUi.BRAND_DARK,
                    bgColor = CaptureUi.BRAND_SOFT,
                    strokeColor = CaptureUi.BRAND_BORDER,
                ) { onSimulateWcdb() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(row1)

            val row2 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            row2.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "原始捕获历史",
                    textColor = CaptureUi.TEXT_SECONDARY,
                    bgColor = CaptureUi.BG_SUBTLE,
                    strokeColor = CaptureUi.BORDER,
                ) {
                    activity.startActivity(Intent(activity, CaptureHistoryActivity::class.java))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
            )
            row2.addView(
                CaptureUi.button(
                    activity = activity,
                    text = "规则引擎自检",
                    textColor = CaptureUi.TEXT_SECONDARY,
                    bgColor = CaptureUi.BG_SUBTLE,
                    strokeColor = CaptureUi.BORDER,
                ) { onTestRuleSamples() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(row2)
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

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
