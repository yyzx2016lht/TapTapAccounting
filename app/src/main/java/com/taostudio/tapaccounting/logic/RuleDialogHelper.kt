package com.taostudio.tapaccounting.logic

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.AIService
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.AiRule
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

object RuleDialogHelper {

    const val DEFAULT_RULE_PROMPT = """你是关键词提取助手。从记账备注中提取用于自动匹配的关键词。

备注：{{REMARK}}
用户归类：{{CATEGORY}}

规则：
1. 提取能代表这笔交易核心内容的名词：商品名、服务名、商家名、事项名
2. 不要提取：支付方式（微信、支付宝、银行卡等）、金额、时间、数量词
3. 如果有多个独立商品，全部提取，逗号分隔。如"买了苹果和牛奶"→苹果,牛奶
4. 如果备注中有商家名+商品名，优先提取商品名；商家名知名度高时也可一起提取。如"星巴克拿铁"→拿铁,星巴克
5. 备注很短也要尽量提取。如"理发"→理发；"午饭"→午饭
6. 备注太模糊无法提取时（如"消费""支出"），返回原文本身
7. 转账/还款场景提取对象名。如"还花呗"→花呗；"转给张三"→张三
8. 只返回关键词，不要解释，不要标点"""

    fun showDialog(
        ctx: Context,
        rule: AiRule?,
        referenceText: String?,
        defaultType: Int? = null,
        defaultCat: String? = null,
        defaultAcc1: String? = null,
        defaultAcc2: String? = null,
        isOverlay: Boolean = false,
        categoryOnlyLearnMode: Boolean = false,
        onSave: (AiRule) -> Unit,
        onDelete: ((AiRule) -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        val themeCtx = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeCtx).inflate(R.layout.dialog_edit_ai_rule, null)
        
        val tvReferenceText = view.findViewById<TextView>(R.id.tv_reference_text)
        val btnAiExtract = view.findViewById<View>(R.id.btn_ai_extract_rule)
        val btnDeleteRule = view.findViewById<View>(R.id.btn_delete_rule)
        val btnCancelRule = view.findViewById<View>(R.id.btn_cancel_rule)
        val btnSaveRule = view.findViewById<View>(R.id.btn_save_rule)
        val etKeyword = view.findViewById<TextInputEditText>(R.id.et_keyword)
        val spType = view.findViewById<Spinner>(R.id.sp_type)
        val tvCategory = view.findViewById<TextView>(R.id.tv_category)
        val spAccount1 = view.findViewById<Spinner>(R.id.sp_account1)
        val spAccount2 = view.findViewById<Spinner>(R.id.sp_account2)
        val layoutAccount2 = view.findViewById<View>(R.id.layout_account2)
        val tvAccount1Label = view.findViewById<TextView>(R.id.tv_account1_label)
        val switchEnabled = view.findViewById<Switch>(R.id.switch_enabled)

        if (categoryOnlyLearnMode) {
            tvAccount1Label.visibility = View.GONE
            spAccount1.visibility = View.GONE
            layoutAccount2.visibility = View.GONE
        }

        if (!referenceText.isNullOrEmpty()) {
            view.findViewById<View>(R.id.layout_reference_card).visibility = View.VISIBLE
            tvReferenceText.text = referenceText
        } else {
            view.findViewById<View>(R.id.layout_reference_card).visibility = View.GONE
        }

        btnAiExtract.setOnClickListener {
            Toast.makeText(ctx, ctx.getString(R.string.extracting_rules), Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                val remark = referenceText ?: ""
                val prompt = DEFAULT_RULE_PROMPT
                    .replace("{{REMARK}}", remark)
                    .replace("{{CATEGORY}}", tvCategory.text.toString())
                try {
                    val result = AIService.simpleChat(ctx, prompt)
                    withContext(Dispatchers.Main) {
                        etKeyword.setText(result.trim())
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, ctx.getString(R.string.extract_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        // type
        val types = ctx.resources.getStringArray(R.array.bill_types)
        spType.adapter = ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, types)
        (spType.adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (categoryOnlyLearnMode) {
                    layoutAccount2.visibility = View.GONE
                    return
                }
                if (position == 2 || position == 3) {
                    layoutAccount2.visibility = View.VISIBLE
                } else {
                    layoutAccount2.visibility = View.GONE
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // Accounts
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(ctx)
            val accounts = db.assetDao().getAllAssetsList()
                .filterNot { it.isArchived }
                .map { it.name }
            withContext(Dispatchers.Main) {
                val accAdapter = ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, (listOf(ctx.getString(R.string.none)) + accounts).toTypedArray()).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                spAccount1.adapter = accAdapter
                spAccount2.adapter = accAdapter

                var initType = rule?.targetType ?: defaultType ?: 0
                if (initType !in 0..5) initType = 0
                spType.setSelection(initType)

                val initCat = rule?.targetCategory ?: defaultCat ?: ""
                if (initCat.isNotEmpty()) tvCategory.text = initCat

                val initAcc1 = rule?.targetAccount1 ?: defaultAcc1 ?: ""
                var pos1 = accounts.indexOf(initAcc1) + 1
                if (pos1 > 0) spAccount1.setSelection(pos1)

                val initAcc2 = rule?.targetAccount2 ?: defaultAcc2 ?: ""
                var pos2 = accounts.indexOf(initAcc2) + 1
                if (pos2 > 0) spAccount2.setSelection(pos2)
            }
        }

        tvCategory.setOnClickListener {
            val currentType = if (spType.selectedItemPosition == 1) 1 else 0
            OverlayDialogs.showGridCategoryPicker(ctx, tvCategory.text.toString(), currentType) { selected ->
                tvCategory.text = selected.ifBlank { ctx.getString(R.string.tap_select_category) }
            }
        }

        switchEnabled.isChecked = rule?.isEnabled ?: true
        if (rule != null) {
            etKeyword.setText(rule.keyword)
        }

        view.findViewById<TextView>(R.id.tv_dialog_title).text = if (rule == null) ctx.getString(R.string.add_rule_title) else ctx.getString(R.string.edit_rule_title)
        view.findViewById<TextView>(R.id.tv_dialog_subtitle).text = when {
            rule != null -> ctx.getString(R.string.edit_rule_subtitle)
            categoryOnlyLearnMode -> ctx.getString(R.string.add_rule_subtitle_learn)
            else -> ctx.getString(R.string.add_rule_subtitle)
        }

        val dialog = AlertDialog.Builder(themeCtx)
            .setView(view)
            .create()

        btnCancelRule.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }

        btnDeleteRule.visibility = if (rule != null && onDelete != null) View.VISIBLE else View.GONE
        btnDeleteRule.setOnClickListener {
            if (rule != null && onDelete != null) {
                onDelete(rule)
                dialog.dismiss()
            }
        }

        btnSaveRule.setOnClickListener {
            val keyword = etKeyword.text.toString().trim()
            if (keyword.isEmpty()) {
                Toast.makeText(ctx, ctx.getString(R.string.keyword_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val finalType = spType.selectedItemPosition
            val finalCat = tvCategory.text.toString().takeIf { it != ctx.getString(R.string.tap_select_category) && it.isNotBlank() }
            val finalAcc1Str = spAccount1.selectedItem?.toString()
            val finalAcc1 = if (categoryOnlyLearnMode) {
                null
            } else if (finalAcc1Str == ctx.getString(R.string.none) || finalAcc1Str == null) {
                null
            } else {
                finalAcc1Str
            }
            val finalAcc2Str = spAccount2.selectedItem?.toString()
            val finalAcc2 = if (categoryOnlyLearnMode) {
                null
            } else if (finalAcc2Str == ctx.getString(R.string.none) || finalAcc2Str == null || (finalType != 2 && finalType != 3)) {
                null
            } else {
                finalAcc2Str
            }

            val keywords = keyword.split("，", ",").map { it.trim() }.filter { it.isNotEmpty() }
            for (kw in keywords) {
                val newRule = AiRule(
                    id = if (keywords.size == 1) (rule?.id ?: 0) else 0,
                    keyword = kw,
                    targetType = finalType,
                    targetCategory = finalCat,
                    targetAccount1 = finalAcc1,
                    targetAccount2 = finalAcc2,
                    isEnabled = switchEnabled.isChecked
                )
                onSave(newRule)
            }
            dialog.dismiss()
        }

        dialog.setOnCancelListener { onCancel?.invoke() }
        if (isOverlay) {
            OverlayDialogs.showOverlayCenterDialog(
                dialog = dialog,
                ctx = ctx,
                widthRatio = 0.92f,
                cancelOnTouchOutside = true,
                useSolidPanelBackground = true
            )
        } else {
            OverlayDialogs.showPageCenterDialog(
                dialog = dialog,
                ctx = ctx,
                widthRatio = 0.92f,
                cancelOnTouchOutside = true,
                useSolidPanelBackground = true
            )
        }
    }
}

