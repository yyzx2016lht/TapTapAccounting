package com.taostudio.tapaccounting.logic

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.room.withTransaction
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.*
import org.json.JSONObject
import com.taostudio.tapaccounting.*
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.AiRule
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.ui.ExchangeRateActivity
import com.taostudio.tapaccounting.ui.common.UiMotion.applyFormRowPressFeedback
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.text.NumberFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resume

class AccountingFormController(
    private val ctx: Context,
    private val rootView: View,
    private val onCloseRequest: (isSaved: Boolean) -> Unit,
    /** 当容器高度锁定后回调（悬浮窗需要同步更新 WindowManager params 高度，防止跳动） */
    val onHeightLocked: ((lockedHeight: Int) -> Unit)? = null
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var editingBillId: Long? = null
    private var isSaving: Boolean = false
    private val etMoney: EditText = rootView.findViewById(R.id.et_amount)
    private val spType: Spinner = rootView.findViewById(R.id.spinner_type)
    private val layoutAccount: View = rootView.findViewById(R.id.layout_account)
    private val tvAccount: TextView = rootView.findViewById(R.id.tv_account)
    private val ivAccountIcon: ImageView? = rootView.findViewById(R.id.iv_account_icon)
    private val tvAccountIconEmoji: TextView? = rootView.findViewById(R.id.tv_account_icon_emoji)
    private val layoutAccount2: View = rootView.findViewById(R.id.layout_account_2)
    private val tvAccount2: TextView = rootView.findViewById(R.id.tv_account_2)
    private val ivAccount2Icon: ImageView? = rootView.findViewById(R.id.iv_account_2_icon)
    private val tvAccount2IconEmoji: TextView? = rootView.findViewById(R.id.tv_account_2_icon_emoji)
    private val tvAccountAmount: TextView? = rootView.findViewById(R.id.tv_account_amount)
    private val tvAccount2Amount: TextView? = rootView.findViewById(R.id.tv_account_2_amount)
    private val btnSwapAccounts: View? = rootView.findViewById(R.id.btn_swap_accounts)
    private val layoutCategory: View = rootView.findViewById(R.id.layout_category)
    private val tvCategory: TextView = rootView.findViewById(R.id.tv_category)
    private val ivCategoryIcon: ImageView? = rootView.findViewById(R.id.iv_category_icon)
    private val tvCategoryIconEmoji: TextView? = rootView.findViewById(R.id.tv_category_icon_emoji)
    private val layoutFee: View = rootView.findViewById(R.id.layout_fee)
    private val etFee: EditText = rootView.findViewById(R.id.et_fee)
    private val tvTime: TextView = rootView.findViewById(R.id.tv_time)
    private val etRemark: EditText = rootView.findViewById(R.id.et_remark)
    private val btnSave: Button = rootView.findViewById(R.id.btn_save)
    private val btnCancel: Button = rootView.findViewById(R.id.btn_cancel)
    private val tvTitle: TextView? = rootView.findViewById(R.id.tv_title)
    val btnVoice: ImageView = rootView.findViewById(R.id.btn_ai_voice)
    val layoutAiEntry: LinearLayout = rootView.findViewById(R.id.layout_ai_entry)
    val layoutAiTextEntry: LinearLayout = rootView.findViewById(R.id.layout_ai_text_entry)
    val btnAiIcon: ImageView = rootView.findViewById(R.id.btn_ai_magic)
    private val spCurrency: Spinner = rootView.findViewById(R.id.spinner_currency)
    private val layoutBook: View = rootView.findViewById(R.id.layout_book)
    private val tvBook: TextView = rootView.findViewById(R.id.tv_book)
    private var amountKeypadView: View? = null
    private var confirmKeyView: View? = null
    private var isAmountKeypadVisible: Boolean = false
    /** layout_form_body 首次布局后记录的高度，用于键盘显示时保持面板总高度不变 */
    private var formBodyMeasuredHeight: Int = 0

    // 退款来源账单相关（收入模式下可选退款来源）
    private val layoutRefundSource: View? = rootView.findViewById(R.id.layout_refund_source)
    private val tvRefundSourceBill: TextView? = rootView.findViewById(R.id.tv_refund_source_bill)
    /** 分类行右侧的"退款"切换标签（收入模式时显示，点击进入退款模式） */
    private val tvRefundToggle: TextView? = rootView.findViewById(R.id.tv_refund_toggle)
    /** 退款行右侧的"取消退款"标签 */
    private val tvRefundBadge: View? = rootView.findViewById(R.id.tv_refund_badge)
    /** 用户选中的退款来源支出账单，null 表示未选择（普通收入） */
    private var selectedRefundSourceBill: com.taostudio.tapaccounting.data.local.entity.Bill? = null
    /** 当前是否处于退款模式（分类行被退款来源行替换） */
    private var isRefundMode: Boolean = false

    /** 当前表单选定的账本（独立于全局选中账本，初始值同步全局） */
    private var selectedFormBook: String =
        BookAccountManager.resolveWritableBook(ctx, BookAccountManager.getSelectedBook(ctx))

    private val aiAssistant by lazy { AiAssistant(ctx) }
    private val isAssetFeatureEnabled: Boolean
        get() = Prefs.isAssetFeatureEnabled(ctx)

    private var customTransferRate: Double? = null
    private var customTargetAmount: Double? = null
    private var savedTransferRateForEdit: Double? = null
    private var isSpinnersInitialized = false
    /** 转账模式下用户是否已打开过汇率窗口并确认 */
    private var hasConfirmedExchangeRate: Boolean = false

    // 支出/收入跨币种汇率确认
    /** 当选择的币种与账户1币种不同时，用户是否已确认过汇率 */
    private var hasConfirmedCurrencyRate: Boolean = false
    /** 用户在汇率窗口中确认的实际扣减金额（账户币种） */
    private var customCurrencyTargetAmount: Double? = null
    /** 支出/收入场景中用户手动确认的汇率 */
    private var customCurrencyRate: Double? = null
    /** 程序自动调用 setCurrency() 时置 true，避免触发用户切换逻辑 */
    private var isProgrammaticCurrencyChange: Boolean = false
    private var pendingInvestmentSchedule: InvestmentInterestService.InvestmentSchedule? = null
    private var hasCheckedInvestmentSchedulePrompt: Boolean = false

    init {
        CurrencyManager.init(ctx)
        setupVisibility()
        setupSpinner()
        setupCurrencySpinner()
        setupListeners()
        setupAmountKeypad()
        setupDefaults()
        btnCancel.text = ctx.getString(R.string.cancel)
        btnSave.text = ctx.getString(R.string.save_and_record)
        setupModeToggle()
        applyAssetFeatureMode()
        refreshSelectionIcons()
    }

    private fun refreshSelectionIcons() {
        refreshAccountIconForName(tvAccount.text?.toString().orEmpty())
        refreshAccount2IconForName(tvAccount2.text?.toString().orEmpty())
        refreshCategoryIconForSelection(tvCategory.text?.toString().orEmpty())
    }

    private fun resetAccountIconToEmoji() {
        ivAccountIcon?.visibility = View.GONE
        tvAccountIconEmoji?.visibility = View.VISIBLE
        tvAccountAmount?.text = "--"
    }

    private fun resetAccount2IconToEmoji() {
        ivAccount2Icon?.visibility = View.GONE
        tvAccount2IconEmoji?.visibility = View.VISIBLE
        tvAccount2Amount?.text = "--"
    }

    private fun formatAssetBalance(asset: Asset): String {
        val symbol = CurrencyManager.getSymbol(asset.currency)
        val amount = String.format(Locale.getDefault(), "%.2f", asset.balance)
        return "$symbol$amount"
    }

    private fun resetCategoryIconToEmoji() {
        ivCategoryIcon?.visibility = View.GONE
        tvCategoryIconEmoji?.visibility = View.VISIBLE
        ivCategoryIcon?.clearColorFilter()
    }

    private fun refreshAccountIconForName(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank() || normalized.contains(ctx.getString(R.string.select_asset).take(2)) || normalized == ctx.getString(R.string.from_account) || normalized == ctx.getString(R.string.payment_account)) {
            resetAccountIconToEmoji()
            return
        }
        scope.launch {
            val asset = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(ctx).assetDao().getAssetByName(normalized)
            }
            if (asset == null) {
                resetAccountIconToEmoji()
                return@launch
            }
            tvAccountAmount?.text = formatAssetBalance(asset)
            if (asset.icon.isBlank()) {
                ivAccountIcon?.visibility = View.GONE
                tvAccountIconEmoji?.visibility = View.VISIBLE
                return@launch
            }
            tvAccountIconEmoji?.visibility = View.GONE
            ivAccountIcon?.visibility = View.VISIBLE
            ivAccountIcon?.let {
                Glide.with(ctx)
                    .load(asset.icon)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .into(it)
            }
        }
    }

    private fun refreshCategoryIconForSelection(selection: String) {
        val normalized = selection.trim()
        if (normalized.isBlank() || normalized.contains(ctx.getString(R.string.select_asset).take(2))) {
            resetCategoryIconToEmoji()
            return
        }
        val pathParts = normalized
            .replace(" - ", "/::/").replace(" > ", "/::/")
            .split("/::/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val leafName = pathParts.lastOrNull().orEmpty()
        if (leafName.isBlank()) {
            resetCategoryIconToEmoji()
            return
        }
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                val dbType = if (spType.selectedItemPosition == 1) 1 else 0
                val categories = AppDatabase.getDatabase(ctx).categoryDao().getCategoriesListByType(dbType)
                val parentName = pathParts.getOrNull(pathParts.size - 2)
                val parent = if (parentName.isNullOrBlank()) {
                    null
                } else {
                    categories.firstOrNull { it.parentId == null && it.name == parentName }
                }
                val category = when {
                    parent != null -> categories.firstOrNull { it.parentId == parent.id && it.name == leafName }
                    else -> categories.firstOrNull { it.parentId == null && it.name == leafName }
                        ?: categories.firstOrNull { it.name == leafName }
                }
                val icon = when {
                    category == null -> ""
                    category.iconId.isNotBlank() -> category.iconId
                    parent?.iconId?.isNotBlank() == true -> parent.iconId
                    else -> ""
                }
                category to icon
            }
            val category = resolved.first
            val icon = resolved.second
            if (category == null || icon.isBlank()) {
                resetCategoryIconToEmoji()
                return@launch
            }
            tvCategoryIconEmoji?.visibility = View.GONE
            ivCategoryIcon?.visibility = View.VISIBLE
            val tintColor = if (category.type == 1) Color.parseColor("#43A047") else Color.parseColor("#E53935")
            ivCategoryIcon?.let {
                Glide.with(ctx)
                    .load(icon)
                    .into(it)
                it.setColorFilter(tintColor)
            }
        }
    }

    private fun setupVisibility() {
        rootView.findViewById<LinearLayout>(R.id.layout_ai_entry)?.visibility = if (Prefs.isShowAiText(ctx)) View.VISIBLE else View.GONE
        btnVoice.visibility = if (Prefs.isShowAiVoice(ctx)) View.VISIBLE else View.GONE
        spCurrency.visibility = View.VISIBLE
        val isMultiCurrency = Prefs.isShowMultiCurrency(ctx)
        spCurrency.isEnabled = isMultiCurrency
        spCurrency.isClickable = isMultiCurrency
        if (!isMultiCurrency) {
            val cnyAdapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, listOf("CNY")) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.text = "\uD83C\uDDE8\uD83C\uDDF3 CNY"
                    v.textSize = 14f
                    v.setTextColor(Color.parseColor("#999999"))
                    v.gravity = Gravity.CENTER
                    return v
                }
            }
            spCurrency.adapter = cnyAdapter
        } else {
            setupCurrencySpinner()
        }
        layoutBook.visibility = if (Prefs.isShowBookEntry(ctx)) View.VISIBLE else View.GONE

        // 记账页面 AI 对话入口按钮
        val btnOpenAiChat = rootView.findViewById<android.view.View>(R.id.btn_open_ai_chat)
        btnOpenAiChat?.visibility = View.GONE
    }

    private fun setupModeToggle() {
        rootView.findViewById<View>(R.id.layout_bill_mode_switch)?.visibility = View.GONE
    }

    private fun isCurrentUiMultiMode(): Boolean = true

    private fun setupSpinner() {
        val types = if (isAssetFeatureEnabled) {
            ctx.resources.getStringArray(R.array.bill_types).toList()
        } else {
            ctx.resources.getStringArray(R.array.bill_types).take(2)
        }
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, types) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.textSize = 15f
                v.setTextColor(Color.parseColor("#333333"))
                v.gravity = Gravity.CENTER
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.textSize = 16f
                v.setPadding(32, 32, 32, 32)
                return v
            }
        }
        spType.adapter = adapter
    }

    private fun applyAssetFeatureMode() {
        if (!isAssetFeatureEnabled) {
            layoutAccount.visibility = View.GONE
            layoutAccount2.visibility = View.GONE
            rootView.findViewById<View?>(R.id.line_account_2)?.visibility = View.GONE
            refreshAccount2IconForName("")
            layoutFee.visibility = View.GONE
            rootView.findViewById<View?>(R.id.line_fee)?.visibility = View.GONE
            tvAccount.text = ""
            tvAccount2.text = ""
            refreshSelectionIcons()
        }
        onTypeChanged(spType.selectedItemPosition.coerceAtMost(if (isAssetFeatureEnabled) 3 else 1))
    }

    /** 统一处理 spinner position 变化：0=支出,1=收入,2=转账,3=还款 */
    private fun onTypeChanged(position: Int) {
        if (!isAssetFeatureEnabled && position > 1) {
            spType.setSelection(0)
            return
        }
        val isTransfer   = position == 2
        val isRepayment  = position == 3

        when (position) {
            0 -> etMoney.setTextColor(Color.parseColor("#FF5252"))
            1 -> etMoney.setTextColor(Color.parseColor("#4CAF50"))
            2 -> etMoney.setTextColor(Color.parseColor("#8A8A8E"))
            3 -> etMoney.setTextColor(Color.parseColor("#FF9800"))
        }

        // 转账/还款：显示转入账户
        if (isAssetFeatureEnabled) {
            layoutAccount.visibility = View.VISIBLE
            layoutAccount2.visibility = if (isTransfer || isRepayment) View.VISIBLE else View.GONE
            rootView.findViewById<View?>(R.id.line_account_2)?.visibility = layoutAccount2.visibility
            btnSwapAccounts?.visibility = if (isTransfer || isRepayment) View.VISIBLE else View.GONE
        } else {
            layoutAccount.visibility = View.GONE
            layoutAccount2.visibility = View.GONE
            rootView.findViewById<View?>(R.id.line_account_2)?.visibility = View.GONE
            btnSwapAccounts?.visibility = View.GONE
        }

        // 分类：仅支出/收入时显示
        layoutCategory.visibility = if (!isTransfer && !isRepayment) View.VISIBLE else View.GONE

        // 手续费：仅转账时显示
        layoutFee.visibility = if (isAssetFeatureEnabled && isTransfer) View.VISIBLE else View.GONE
        rootView.findViewById<View?>(R.id.line_fee)?.visibility = layoutFee.visibility

        // 退款模式控制：收入模式下分类行右侧显示"退款"切换标签，非收入模式强制退出退款模式
        if (position == 1) {
            // 收入模式：显示退款切换标签（保持当前退款模式状态不变）
            tvRefundToggle?.visibility = View.VISIBLE
        } else {
            // 非收入模式：退出退款模式，隐藏所有退款相关元素
            tvRefundToggle?.visibility = View.GONE
            if (isRefundMode) exitRefundMode()
        }

        // 账户标签文字
        val tvLabel = rootView.findViewById<TextView?>(R.id.tv_account_label)
        val tvLabel2 = rootView.findViewById<TextView?>(R.id.tv_account_2_label)
        if (!isAssetFeatureEnabled) {
            updateCurrencySpinnerMode(isTransferMode = false)
            return
        }
        when (position) {
            2 -> {
                tvLabel?.text  = ctx.getString(R.string.from_account)
                tvLabel2?.text = ctx.getString(R.string.to_account)
                if (tvAccount.text == ctx.getString(R.string.select_asset)) tvAccount.text = ctx.getString(R.string.from_account)
                if (tvAccount2.text.isEmpty() || tvAccount2.text == ctx.getString(R.string.select_asset) || tvAccount2.text == ctx.getString(R.string.select_credit_card) || tvAccount2.text == ctx.getString(R.string.select_to_account)) tvAccount2.text = ctx.getString(R.string.to_account)
                refreshAccountIconForName(tvAccount.text.toString())
                refreshAccount2IconForName(tvAccount2.text.toString())
                customTransferRate = null
                customTargetAmount = null
                hasConfirmedExchangeRate = false
                // 转账模式下，spCurrency 作为汇率入口，禁用下拉改为点击弹窗
                updateCurrencySpinnerMode(isTransferMode = true)
            }
            3 -> {
                tvLabel?.text  = ctx.getString(R.string.from_account)
                tvLabel2?.text = ctx.getString(R.string.to_account)
                if (tvAccount.text == ctx.getString(R.string.select_asset) || tvAccount.text == ctx.getString(R.string.payment_account)) tvAccount.text = ctx.getString(R.string.from_account)
                if (tvAccount2.text.isEmpty() || tvAccount2.text == ctx.getString(R.string.select_to_account) || tvAccount2.text == ctx.getString(R.string.select_credit_card)) tvAccount2.text = ctx.getString(R.string.to_account)
                refreshAccountIconForName(tvAccount.text.toString())
                refreshAccount2IconForName(tvAccount2.text.toString())
                updateCurrencySpinnerMode(isTransferMode = false)
            }
            else -> {
                tvLabel?.text  = ctx.getString(R.string.select_account_label)
                if (tvAccount.text == ctx.getString(R.string.from_account) || tvAccount.text == ctx.getString(R.string.payment_account)) tvAccount.text = ctx.getString(R.string.select_asset)
                refreshAccountIconForName(tvAccount.text.toString())
                refreshAccount2IconForName(tvAccount2.text.toString())
                updateCurrencySpinnerMode(isTransferMode = false)
            }
        }
    }

    private fun setupCurrencySpinner() {
        val enabledCodes = CurrencyManager.getEnabledCurrencies(ctx).toMutableList().apply {
            if (!contains("CNY")) add(0, "CNY")
        }
        
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, enabledCodes) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val code = enabledCodes[position]
                val info = CurrencyData.getInfo(code)
                v.text = info?.let { "${it.flagEmoji} ${it.code}" } ?: code
                v.textSize = 14f
                v.setTextColor(Color.parseColor("#333333"))
                v.gravity = Gravity.CENTER
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                val code = enabledCodes[position]
                val info = CurrencyData.getInfo(code)
                v.text = info?.let { "${it.flagEmoji} ${it.code} - ${it.nameZh}" } ?: code
                v.textSize = 16f
                v.setPadding(32, 32, 32, 32)
                return v
            }
        }
        spCurrency.adapter = adapter
        
        val defaultIdx = enabledCodes.indexOf("CNY")
        if (defaultIdx >= 0) spCurrency.setSelection(defaultIdx)
        // 初始状态不设置 onItemSelectedListener，由 updateCurrencySpinnerMode 控制行为
    }

    /**
     * 切换 spCurrency 的交互模式：
     * - isTransferMode=true：禁用下拉，点击直接弹汇率窗口（作为汇率设置入口）
     * - isTransferMode=false：恢复正常 Spinner 下拉选择
     */
    private fun updateCurrencySpinnerMode(isTransferMode: Boolean) {
        if (isTransferMode) {
            // 移除下拉监听，改为整体点击弹汇率（仅 ACTION_UP 触发一次，避免多次弹窗）
            spCurrency.onItemSelectedListener = null
            spCurrency.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    showExchangeDialog()
                }
                true  // 消费事件，阻止 Spinner 下拉
            }
        } else {
            spCurrency.setOnTouchListener(null)
            // 非转账模式：监听用户手动切换币种，重置跨币种汇率确认状态
            spCurrency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!isProgrammaticCurrencyChange) {
                        // 用户主动切换了币种，重置汇率确认状态，下次保存时重新弹汇率窗口
                        hasConfirmedCurrencyRate = false
                        customCurrencyTargetAmount = null
                        customCurrencyRate = null
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun setupDefaults() {
        tvTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        if (BookAccountManager.isBookCollapsed(ctx, selectedFormBook)) {
            selectedFormBook = BookAccountManager.getDefaultBook(ctx)
        }
        tvBook.text = selectedFormBook
    }

    private fun parseUiTimeToMillis(text: String): Long? {
        val value = text.trim()
        if (value.isEmpty()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(value)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun refreshAccount2IconForName(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank() || normalized.contains(ctx.getString(R.string.select_asset).take(2)) || normalized == ctx.getString(R.string.to_account)) {
            resetAccount2IconToEmoji()
            return
        }
        scope.launch {
            val asset = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(ctx).assetDao().getAssetByName(normalized)
            }
            if (asset == null) {
                resetAccount2IconToEmoji()
                return@launch
            }
            tvAccount2Amount?.text = formatAssetBalance(asset)
            if (asset.icon.isBlank()) {
                ivAccount2Icon?.visibility = View.GONE
                tvAccount2IconEmoji?.visibility = View.VISIBLE
                return@launch
            }
            tvAccount2IconEmoji?.visibility = View.GONE
            ivAccount2Icon?.visibility = View.VISIBLE
            ivAccount2Icon?.let {
                Glide.with(ctx)
                    .load(asset.icon)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .into(it)
            }
        }
    }

    private fun isAccountPlaceholder(name: String): Boolean {
        val text = name.trim()
        if (text.isEmpty()) return true
        return text.contains(ctx.getString(R.string.select_asset).take(2)) || text == ctx.getString(R.string.from_account) || text == ctx.getString(R.string.to_account) || text == ctx.getString(R.string.payment_account)
    }

    private fun adjustTypeByToAccount(toAccountName: String) {
        if (!isAssetFeatureEnabled || toAccountName.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val selected = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(toAccountName)
            withContext(Dispatchers.Main) {
                val currentPos = spType.selectedItemPosition
                if (selected?.assetCategory == Asset.CATEGORY_CREDIT_CARD && currentPos == 2) {
                    spType.setSelection(3)
                } else if (selected?.assetCategory != Asset.CATEGORY_CREDIT_CARD && currentPos == 3) {
                    spType.setSelection(2)
                }
            }
        }
    }

    private fun swapAccounts() {
        val currentType = spType.selectedItemPosition
        if (currentType != 2 && currentType != 3) return

        val fromRaw = tvAccount.text?.toString().orEmpty()
        val toRaw = tvAccount2.text?.toString().orEmpty()
        val fromName = fromRaw.takeUnless { isAccountPlaceholder(it) }.orEmpty()
        val toName = toRaw.takeUnless { isAccountPlaceholder(it) }.orEmpty()

        tvAccount.text = if (toName.isNotEmpty()) toName else ctx.getString(R.string.from_account)
        tvAccount2.text = if (fromName.isNotEmpty()) fromName else ctx.getString(R.string.to_account)
        refreshAccountIconForName(tvAccount.text?.toString().orEmpty())
        refreshAccount2IconForName(tvAccount2.text?.toString().orEmpty())

        customTransferRate = null
        customTargetAmount = null
        savedTransferRateForEdit = null
        hasConfirmedExchangeRate = false

        val newFromName = tvAccount.text?.toString().orEmpty().takeUnless { isAccountPlaceholder(it) }.orEmpty()
        if (Prefs.isShowMultiCurrency(ctx) && newFromName.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val selected = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(newFromName)
                withContext(Dispatchers.Main) {
                    val accountCurrency = selected?.currency ?: "CNY"
                    setCurrency(accountCurrency)
                }
            }
        }

    }

    private fun setupListeners() {
        etMoney.setOnClickListener { showAmountKeypad() }
        etMoney.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                showAmountKeypad()
            }
            false
        }
        etMoney.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showAmountKeypad()
        }

        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onTypeChanged(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val clickListener = View.OnClickListener { v ->
            hideAmountKeypad()
            when (v.id) {
                R.id.layout_account, R.id.tv_account -> {
                    scope.launch(Dispatchers.IO) {
                        val assets = AppDatabase.getDatabase(ctx).assetDao().getAllAssetsList()
                        withContext(Dispatchers.Main) {
                            if (!isActivityAlive()) return@withContext
                            val isRepaymentMode = spType.selectedItemPosition == 3
                            val title = if (spType.selectedItemPosition == 2 || isRepaymentMode) ctx.getString(R.string.select_from_account) else ctx.getString(R.string.select_asset)
                            val assetFilter: ((Asset) -> Boolean)? = if (isRepaymentMode) {
                                    { asset -> asset.assetCategory != Asset.CATEGORY_CREDIT_CARD }
                                } else {
                                    null
                                }
                            val selectableAssets = assetFilter?.let { filter -> assets.filter(filter) } ?: assets
                            if (selectableAssets.isNotEmpty()) {
                                OverlayDialogs.showGridAssetPicker(ctx, tvAccount.text.toString(), title, assetFilter) { selectedName ->
                                    tvAccount.text = selectedName
                                    refreshAccountIconForName(selectedName)
                                    customTransferRate = null
                                    customTargetAmount = null
                                    savedTransferRateForEdit = null
                                    hasConfirmedExchangeRate = false
                                    pendingInvestmentSchedule = null
                                    hasCheckedInvestmentSchedulePrompt = false
                                    // 自动将 spCurrency 同步为转出账户（账户1）的货币
                                    if (Prefs.isShowMultiCurrency(ctx)) {
                                        scope.launch(Dispatchers.IO) {
                                            val selected = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(selectedName)
                                            withContext(Dispatchers.Main) {
                                                val accountCurrency = selected?.currency ?: "CNY"
                                                setCurrency(accountCurrency)
                                            }
                                        }
                                    }
                                }
                            } else Utils.toast(ctx, ctx.getString(R.string.toast_add_asset_first))
                        }
                    }
                }
                R.id.layout_category, R.id.tv_category -> {
                    if (!isActivityAlive()) return@OnClickListener
                    val currentType = if (spType.selectedItemPosition == 1) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
                    OverlayDialogs.showGridCategoryPicker(ctx, tvCategory.text.toString(), currentType) {
                        tvCategory.text = it.replace(" > ", " - ")
                        refreshCategoryIconForSelection(it)
                    }
                }
                R.id.layout_account_2, R.id.tv_account_2 -> {
                    scope.launch(Dispatchers.IO) {
                        val assets = AppDatabase.getDatabase(ctx).assetDao().getAllAssetsList()
                        withContext(Dispatchers.Main) {
                            if (!isActivityAlive()) return@withContext
                            val isRepaymentMode = spType.selectedItemPosition == 3
                            val title = if (isRepaymentMode) ctx.getString(R.string.select_repayment_credit_card) else ctx.getString(R.string.select_to_account)
                            val assetFilter: ((Asset) -> Boolean)? = if (isRepaymentMode) {
                                    { asset -> asset.assetCategory == Asset.CATEGORY_CREDIT_CARD }
                                } else {
                                    null
                                }
                            val selectableAssets = assetFilter?.let { filter -> assets.filter(filter) } ?: assets
                            if (selectableAssets.isNotEmpty()) {
                                OverlayDialogs.showGridAssetPicker(ctx, tvAccount2.text.toString(), title, assetFilter) { selectedName ->
                                    tvAccount2.text = selectedName
                                    refreshAccount2IconForName(selectedName)
                                    customTransferRate = null
                                    customTargetAmount = null
                                    savedTransferRateForEdit = null
                                    hasConfirmedExchangeRate = false
                                    pendingInvestmentSchedule = null
                                    hasCheckedInvestmentSchedulePrompt = false
                                    adjustTypeByToAccount(selectedName)
                                }
                            } else Utils.toast(ctx, ctx.getString(R.string.toast_add_asset_first))
                        }
                    }
                }
            }
        }

        layoutAccount.setOnClickListener(clickListener)
        tvAccount.setOnClickListener(clickListener)
        layoutCategory.setOnClickListener(clickListener)
        tvCategory.setOnClickListener(clickListener)
        layoutAccount2.setOnClickListener(clickListener)
        tvAccount2.setOnClickListener(clickListener)

        // Apply consistent press feedback to all form rows
        layoutAccount.applyFormRowPressFeedback()
        layoutCategory.applyFormRowPressFeedback()
        layoutAccount2.applyFormRowPressFeedback()
        layoutBook.applyFormRowPressFeedback()

        btnSwapAccounts?.setOnClickListener {
            hideAmountKeypad()
            swapAccounts()
        }
        btnSwapAccounts?.applyFormRowPressFeedback()

        // 分类行右侧"退款"标签：点击进入退款模式
        tvRefundToggle?.setOnClickListener {
            if (!isActivityAlive()) return@setOnClickListener
            enterRefundMode()
        }
        tvRefundToggle?.applyFormRowPressFeedback()

        // 退款来源账单行：整行点击弹出账单选择
        layoutRefundSource?.setOnClickListener {
            if (!isActivityAlive()) return@setOnClickListener
            OverlayDialogs.showRefundBillPickerDialog(ctx) { chosenBill ->
                selectedRefundSourceBill = chosenBill
                val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                val symbol = com.taostudio.tapaccounting.logic.CurrencyManager.getSymbol(chosenBill.currency)
                val label = "${chosenBill.categoryName.ifEmpty { ctx.getString(R.string.uncategorized) }}  ${df.format(java.util.Date(chosenBill.time))}  ${symbol}${String.format(java.util.Locale.getDefault(), "%.2f", chosenBill.amount)}"
                tvRefundSourceBill?.text = label
                tvRefundSourceBill?.setTextColor(android.graphics.Color.parseColor("#E53935"))
                // 自动填充：账户、分类（金额仅在未填写时才填入）
                val currentAmount = parseAmountInput()
                if (currentAmount == null || currentAmount == 0.0) {
                    etMoney.setText(String.format(java.util.Locale.getDefault(), "%.2f", chosenBill.amount))
                }
                if (tvAccount.text.toString().contains(ctx.getString(R.string.select_asset).take(2)) || tvAccount.text.isBlank()) {
                    tvAccount.text = chosenBill.accountName
                    refreshAccountIconForName(chosenBill.accountName)
                }
                if (tvCategory.text.toString().contains(ctx.getString(R.string.select_asset).take(2)) || tvCategory.text.isBlank()) {
                    val baseCategory = if (chosenBill.categoryName.startsWith("退款：")) {
                        chosenBill.categoryName.removePrefix("退款：").trim()
                    } else if (chosenBill.categoryName.startsWith("退款·")) {
                        // 兼容旧数据格式
                        chosenBill.categoryName.removePrefix("退款·").trim()
                    } else chosenBill.categoryName
                    tvCategory.text = baseCategory
                    refreshCategoryIconForSelection(baseCategory)
                }
            }
        }

        // 退款行右侧"取消退款"标签：点击退出退款模式
        tvRefundBadge?.setOnClickListener {
            if (!isActivityAlive()) return@setOnClickListener
            exitRefundMode()
        }

        rootView.findViewById<View>(R.id.layout_time)?.setOnClickListener {
            hideAmountKeypad()
            val initialTimeMillis = parseUiTimeToMillis(tvTime.text?.toString().orEmpty())
            if (isActivityAlive()) {
                OverlayDialogs.showCustomTimePicker(ctx, initialTimeMillis = initialTimeMillis) { tvTime.text = it }
            }
        }
        rootView.findViewById<View>(R.id.layout_time)?.applyFormRowPressFeedback()
        layoutBook.setOnClickListener {
            hideAmountKeypad()
            showBookPickerDialog()
        }
        btnSave.setOnClickListener { v ->
            hideAmountKeypad()
            // Press feedback: quick scale-down then restore
            v.animate().cancel()
            v.animate()
                .scaleX(0.93f)
                .scaleY(0.93f)
                .setDuration(80L)
                .setInterpolator(com.taostudio.tapaccounting.ui.common.UiMotion.EXIT_EASING)
                .withEndAction {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(120L)
                        .setInterpolator(com.taostudio.tapaccounting.ui.common.UiMotion.STANDARD_EASING)
                        .withEndAction { handleSave() }
                        .start()
                }
                .start()
        }
        btnCancel.setOnClickListener {
            hideAmountKeypad()
            if (isProcessingPendingBillQueue) {
                if (pendingBills.isNotEmpty()) {
                    Utils.toast(ctx, ctx.getString(R.string.toast_bill_skipped_next))
                    processNextPendingBill()
                } else {
                    isProcessingPendingBillQueue = false
                    updateQueueActionUi()
                    Utils.toast(ctx, ctx.getString(R.string.toast_bill_skipped))
                    onCloseRequest(true)
                }
            } else {
                if (pendingBills.isNotEmpty()) {
                    pendingBills.clear()
                    Utils.toast(ctx, ctx.getString(R.string.toast_multi_bill_canceled))
                }
                onCloseRequest(false)
            }
        }
        layoutAiTextEntry.setOnClickListener {
            hideAmountKeypad()
            aiAssistant.showInputPanel(hideStreamText = true) { fillDataToUi(it) }
        }
        // btnVoice 的触摸逻辑由 VoiceInputHandler.setupVoiceButton 接管，此处不设置 OnClickListener

        // 备注输入框：回车键收起输入法，不换行
        etRemark.dismissKeyboardOnEnter()
    }

    private fun setupAmountKeypad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            etMoney.showSoftInputOnFocus = false
        }
        etMoney.isLongClickable = true
        etMoney.isCursorVisible = false

        val keypadView = rootView.findViewById<View>(R.id.layout_amount_keypad) ?: return
        amountKeypadView = keypadView

        // 等 layout_form_body 首次测量完成后记录其高度，键盘将使用完全相同的高度显示
        val formBody = rootView.findViewById<View>(R.id.layout_form_body)
        formBody?.post {
            if (formBodyMeasuredHeight == 0 && formBody.height > 0) {
                formBodyMeasuredHeight = formBody.height
            }
        }

        val digitIds = listOf(
            R.id.btn_key_0 to "0",
            R.id.btn_key_1 to "1",
            R.id.btn_key_2 to "2",
            R.id.btn_key_3 to "3",
            R.id.btn_key_4 to "4",
            R.id.btn_key_5 to "5",
            R.id.btn_key_6 to "6",
            R.id.btn_key_7 to "7",
            R.id.btn_key_8 to "8",
            R.id.btn_key_9 to "9",
            R.id.btn_key_dot to "."
        )
        digitIds.forEach { (id, value) ->
            keypadView.findViewById<View>(id)?.setOnClickListener { appendAmountInput(value) }
        }
        val operatorIds = listOf(
            R.id.btn_key_add to "+",
            R.id.btn_key_subtract to "-",
            R.id.btn_key_multiply to "×",
            R.id.btn_key_divide to "÷"
        )
        operatorIds.forEach { (id, value) ->
            keypadView.findViewById<View>(id)?.setOnClickListener { appendOperatorInput(value) }
        }
        keypadView.findViewById<View>(R.id.btn_key_delete)?.apply {
            setOnClickListener { deleteAmountInput() }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                clearAmountInput()
                true
            }
        }
        keypadView.findViewById<View>(R.id.btn_key_clear)?.setOnClickListener { clearAmountInput() }
        confirmKeyView = keypadView.findViewById<View>(R.id.btn_key_confirm)
        confirmKeyView?.setOnClickListener { confirmAmountInput() }

        etMoney.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateConfirmKeyState()
            }
        })
        updateConfirmKeyState()
    }

    private fun showAmountKeypad() {
        etMoney.isCursorVisible = true
        val keypad = amountKeypadView ?: return
        if (isAmountKeypadVisible) return
        val formBody = rootView.findViewById<View>(R.id.layout_form_body)
        // 若还未记录高度（极少情况），此刻补测
        if (formBodyMeasuredHeight == 0 && formBody != null && formBody.height > 0) {
            formBodyMeasuredHeight = formBody.height
        }
        // 将键盘高度精确设为表单体高度，保证面板总高度不变
        if (formBodyMeasuredHeight > 0) {
            val lp = keypad.layoutParams
            lp.height = formBodyMeasuredHeight
            keypad.layoutParams = lp
        }
        formBody?.visibility = View.GONE
        keypad.visibility = View.VISIBLE
        isAmountKeypadVisible = true
    }

    private fun hideAmountKeypad() {
        val keypad = amountKeypadView ?: return
        if (!isAmountKeypadVisible) return
        applyAmountInputResult()
        keypad.visibility = View.GONE
        rootView.findViewById<View>(R.id.layout_form_body)?.visibility = View.VISIBLE
        isAmountKeypadVisible = false
        etMoney.isCursorVisible = false
        etMoney.clearFocus()
    }

    fun handleBackPressed(): Boolean {
        return if (isAmountKeypadVisible) {
            confirmAmountInput()
            true
        } else {
            false
        }
    }

    private fun appendAmountInput(token: String) {
        val current = etMoney.text?.toString().orEmpty()
        applyAmountEdit(
            AmountInputEditor.insert(current, etMoney.selectionStart, etMoney.selectionEnd, token)
        )
    }

    private fun deleteAmountInput() {
        val current = etMoney.text?.toString().orEmpty()
        applyAmountEdit(
            AmountInputEditor.delete(current, etMoney.selectionStart, etMoney.selectionEnd)
        )
    }

    private fun clearAmountInput() {
        etMoney.setText("")
        etMoney.setSelection(0)
        updateConfirmKeyState()
    }

    private fun appendOperatorInput(operator: String) {
        val current = etMoney.text?.toString().orEmpty()
        applyAmountEdit(
            AmountInputEditor.insertOperator(current, etMoney.selectionStart, etMoney.selectionEnd, operator)
        )
    }

    private fun applyAmountEdit(edit: AmountInputEdit) {
        etMoney.setText(edit.text)
        etMoney.setSelection(edit.cursor.coerceIn(0, edit.text.length))
    }

    private fun confirmAmountInput() {
        // 输入为空时也应关闭键盘（不修改金额），不阻拦
        if (isConfirmInputValid()) {
            applyAmountInputResult()
        }
        val keypad = amountKeypadView ?: return
        keypad.visibility = View.GONE
        rootView.findViewById<View>(R.id.layout_form_body)?.visibility = View.VISIBLE
        isAmountKeypadVisible = false
        etMoney.isCursorVisible = false
        etMoney.clearFocus()
    }

    private fun isConfirmInputValid(): Boolean {
        val raw = etMoney.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return parseAmountInput() != null
    }

    private fun updateConfirmKeyState() {
        val enabled = isConfirmInputValid()
        confirmKeyView?.isEnabled = enabled
        confirmKeyView?.alpha = if (enabled) 1f else 0.45f
    }

    private fun applyAmountInputResult() {
        val evaluated = parseAmountInput()
        if (evaluated != null) {
            etMoney.setText(String.format(Locale.getDefault(), "%.2f", evaluated))
            etMoney.setSelection(etMoney.text?.length ?: 0)
        }
    }

    private fun parseAmountInput(): Double? {
        val raw = etMoney.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return evaluateAmountExpression(raw)
    }

    private fun evaluateAmountExpression(raw: String): Double? {
        val expr = raw.replace("×", "*").replace("÷", "/").replace("\\s+".toRegex(), "")
        if (expr.isEmpty()) return null
        val values = java.util.Stack<Double>()
        val ops = java.util.Stack<Char>()
        var i = 0
        while (i < expr.length) {
            val ch = expr[i]
            if (ch.isDigit() || ch == '.' || (ch == '-' && (i == 0 || expr[i - 1] in charArrayOf('+', '-', '*', '/')))) {
                val start = i
                i++
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                val token = expr.substring(start, i)
                values.push(token.toDoubleOrNull() ?: return null)
                continue
            }
            if (ch !in charArrayOf('+', '-', '*', '/')) return null
            while (ops.isNotEmpty() && precedence(ops.peek()) >= precedence(ch)) {
                applyTopOperator(values, ops) ?: return null
            }
            ops.push(ch)
            i++
        }
        while (ops.isNotEmpty()) {
            applyTopOperator(values, ops) ?: return null
        }
        return values.takeIf { it.size == 1 }?.peek()
    }

    private fun precedence(op: Char): Int = when (op) {
        '*', '/' -> 2
        '+', '-' -> 1
        else -> 0
    }

    private fun applyTopOperator(values: java.util.Stack<Double>, ops: java.util.Stack<Char>): Double? {
        if (values.size < 2 || ops.isEmpty()) return null
        val right = values.pop()
        val left = values.pop()
        val result = when (ops.pop()) {
            '+' -> left + right
            '-' -> left - right
            '*' -> left * right
            '/' -> if (right == 0.0) return null else left / right
            else -> return null
        }
        values.push(result)
        return result
    }

    /** 进入退款模式：隐藏分类行，显示退款来源账单行 */
    private fun enterRefundMode() {
        isRefundMode = true
        layoutCategory.visibility = View.GONE
        layoutRefundSource?.visibility = View.VISIBLE
        rootView.findViewById<View?>(R.id.line_refund_source)?.visibility = View.VISIBLE
    }

    /** 退出退款模式：显示分类行，隐藏退款来源账单行，清空退款选择 */
    private fun exitRefundMode() {
        isRefundMode = false
        layoutCategory.visibility = View.VISIBLE
        layoutRefundSource?.visibility = View.GONE
        rootView.findViewById<View?>(R.id.line_refund_source)?.visibility = View.GONE
        selectedRefundSourceBill = null
        tvRefundSourceBill?.text = ctx.getString(R.string.select_refund_bill)
        tvRefundSourceBill?.setTextColor(Color.parseColor("#888888"))
    }

    private fun showBookPickerDialog() {
        val books = BookAccountManager.getBookAccounts(ctx)
        if (books.isEmpty()) return
        OverlayDialogs.showBookPickerDialog(ctx, books, selectedFormBook) { chosen ->
            selectedFormBook = BookAccountManager.resolveWritableBook(ctx, chosen)
            tvBook.text = selectedFormBook
        }
    }

    /** 检查 ctx 对应的 Activity 是否仍然存活（防止 BadTokenException） */
    private fun isActivityAlive(): Boolean {
        if (ctx !is android.app.Activity) return true  // 非 Activity ctx（如悬浮窗）跳过检查
        return !ctx.isFinishing && !ctx.isDestroyed
    }

    private fun showExchangeDialog() {
        val money = parseAmountInput() ?: 0.0
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(ctx)
            val accountName1 = tvAccount.text.toString()
            val accountName2 = tvAccount2.text.toString()
            val asset1 = db.assetDao().getAssetByName(accountName1)
            val asset2 = if (accountName2 != ctx.getString(R.string.select_to_account) && accountName2 != ctx.getString(R.string.to_account) && accountName2.isNotEmpty())
                db.assetDao().getAssetByName(accountName2) else null
            
            val sourceCurrency = asset1?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
            val targetCurrency = asset2?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
            
            withContext(Dispatchers.Main) {
                val currentSpinnerCurrency = spCurrency.selectedItem as? String ?: "CNY"
                if (currentSpinnerCurrency != sourceCurrency) {
                    setCurrency(sourceCurrency)
                }
                // 如果转出账户和转入账户的币种相同，无需确认汇率，直接标记已确认
                if (sourceCurrency == targetCurrency) {
                    customTargetAmount = money
                    customTransferRate = 1.0
                    hasConfirmedExchangeRate = true
                } else {
                    // 币种不同，弹出汇率确认对话框
                    OverlayDialogs.showExchangeRateDialog(
                        ctx,
                        money,
                        sourceCurrency,
                        targetCurrency,
                        customTransferRate ?: savedTransferRateForEdit
                    ) { src, tgt, rate ->
                        etMoney.setText(String.format("%.2f", src))
                        customTargetAmount = tgt
                        customTransferRate = rate
                        hasConfirmedExchangeRate = true
                    }
                }
            }
        }
    }

    /**
     * 支出/收入跨币种汇率确认弹窗。
     * 上方：用户选择的记账币种（spCurrency）。
     * 下方：账户自身币种（asset1.currency）。
     * 用户确认后，实际从账户扣减 customCurrencyTargetAmount（账户币种金额）。
     * 若用户再次点击 spCurrency 切换，会重置状态并重新弹出此窗口。
     */
    private fun showCurrencyExchangeDialog(onConfirmed: () -> Unit) {
        val money = parseAmountInput() ?: 0.0
        val selectedCurrency = spCurrency.selectedItem as? String ?: "CNY"
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(ctx)
            val accountName1 = tvAccount.text.toString()
            val asset1 = db.assetDao().getAssetByName(accountName1)
            val assetCurrency = asset1?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
            withContext(Dispatchers.Main) {
                OverlayDialogs.showExchangeRateDialog(
                    ctx,
                    money,
                    selectedCurrency,
                    assetCurrency,
                    customCurrencyRate
                ) { src, tgt, rate ->
                    // tgt = 账户币种的实际扣减金额
                    customCurrencyTargetAmount = tgt
                    customCurrencyRate = if (src != 0.0) rate else customCurrencyRate
                    hasConfirmedCurrencyRate = true
                    onConfirmed()
                }
            }
        }
    }

    private fun showInvestmentScheduleDialog(
        transferTime: Long,
        targetName: String,
        annualInterestRate: Double,
        previousAnnualInterestRate: Double?,
        previousSettlementCycle: Int?,
        onConfirm: (InvestmentInterestService.InvestmentSchedule) -> Unit
    ) {
        val safeContext = ctx
        if (safeContext is Activity && safeContext.isFinishing) return

        val themeContext = ContextThemeWrapper(safeContext, R.style.Theme_TapAccounting)
        val content = LinearLayout(themeContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        content.addView(TextView(themeContext).apply {
            text = ctx.getString(R.string.investment_transfer_hint, targetName)
            setTextColor(Color.parseColor("#667085"))
            textSize = 14f
        })

        val rateRow = LinearLayout(themeContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(4))
        }
        rateRow.addView(TextView(themeContext).apply {
            text = ctx.getString(R.string.annual_interest_rate)
            textSize = 16f
            setTextColor(Color.parseColor("#1F2A38"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val etAnnualRate = EditText(themeContext).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            hint = ctx.getString(R.string.optional)
            textSize = 16f
            setTextColor(Color.parseColor("#1F2A38"))
            setHintTextColor(Color.parseColor("#8A9099"))
            background = null
            if (annualInterestRate != 0.0) setText(formatCompactDecimal(annualInterestRate))
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        var usePreviousRate = previousAnnualInterestRate != null
        previousAnnualInterestRate?.let { previousRate ->
            val rateModeGroup = RadioGroup(themeContext).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(0, dp(14), 0, dp(2))
            }
            val previousId = View.generateViewId()
            val newId = View.generateViewId()
            rateModeGroup.addView(RadioButton(themeContext).apply {
                id = previousId
                text = ctx.getString(R.string.investment_rate_use_previous, formatCompactDecimal(previousRate))
                textSize = 15f
                setTextColor(Color.parseColor("#1F2A38"))
                isChecked = true
            })
            rateModeGroup.addView(RadioButton(themeContext).apply {
                id = newId
                text = ctx.getString(R.string.investment_rate_use_new)
                textSize = 15f
                setTextColor(Color.parseColor("#1F2A38"))
            })
            rateModeGroup.setOnCheckedChangeListener { _, checkedId ->
                usePreviousRate = checkedId == previousId
                etAnnualRate.isEnabled = !usePreviousRate
                if (usePreviousRate) {
                    etAnnualRate.setText(formatCompactDecimal(previousRate))
                } else if (annualInterestRate != 0.0) {
                    etAnnualRate.setText(formatCompactDecimal(annualInterestRate))
                } else {
                    etAnnualRate.text = null
                }
            }
            content.addView(rateModeGroup)
            etAnnualRate.isEnabled = false
            etAnnualRate.setText(formatCompactDecimal(previousRate))
        }

        rateRow.addView(etAnnualRate)
        rateRow.addView(TextView(themeContext).apply {
            text = "%"
            textSize = 16f
            setTextColor(Color.parseColor("#667085"))
            setPadding(dp(4), 0, 0, 0)
        })
        content.addView(rateRow)
        content.addView(TextView(themeContext).apply {
            text = ctx.getString(R.string.interest_rate_hint)
            setTextColor(Color.parseColor("#8A9099"))
            textSize = 12f
            gravity = Gravity.END
        })

        val cycleOptions = InvestmentInterestService.cycleOptions()
        val cycleRow = LinearLayout(themeContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(4))
        }
        cycleRow.addView(TextView(themeContext).apply {
            text = ctx.getString(R.string.investment_settlement_cycle)
            textSize = 16f
            setTextColor(Color.parseColor("#1F2A38"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val cycleSpinner = Spinner(themeContext).apply {
            adapter = ArrayAdapter(
                themeContext,
                android.R.layout.simple_spinner_dropdown_item,
                cycleOptions.map { it.first }
            )
            val initialCycle = previousSettlementCycle ?: com.taostudio.tapaccounting.data.local.entity.InvestmentLot.CYCLE_DAILY
            setSelection(cycleOptions.indexOfFirst { it.second == initialCycle }.coerceAtLeast(0))
        }
        cycleRow.addView(cycleSpinner, LinearLayout.LayoutParams(dp(116), LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(cycleRow)

        var startEarningAt = InvestmentInterestService.plusDays(
            InvestmentInterestService.startOfDay(transferTime),
            1
        )

        lateinit var startRow: TextView
        lateinit var payoutRow: TextView

        fun bindRow(row: TextView, label: String, value: Long) {
            row.text = "$label    ${formatDateForSchedule(value)}"
        }
        fun refreshPayoutRow() {
            val cycle = cycleOptions[cycleSpinner.selectedItemPosition.coerceAtLeast(0)].second
            val firstPayoutAt = InvestmentInterestService.firstPayoutFor(startEarningAt, cycle)
            bindRow(payoutRow, ctx.getString(R.string.earning_first_payout), firstPayoutAt)
        }

        startRow = TextView(themeContext).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#1F2A38"))
            setPadding(0, dp(16), 0, dp(8))
            bindRow(this, ctx.getString(R.string.start_calculate_earning), startEarningAt)
            setOnClickListener {
                showOverlayFriendlyDatePicker(
                    initialTimeMillis = startEarningAt,
                    minTimeMillis = InvestmentInterestService.startOfDay(transferTime)
                ) { selected ->
                    startEarningAt = InvestmentInterestService.startOfDay(selected)
                    bindRow(startRow, ctx.getString(R.string.start_calculate_earning), startEarningAt)
                    refreshPayoutRow()
                }
            }
        }
        payoutRow = TextView(themeContext).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#667085"))
            setPadding(0, dp(16), 0, dp(8))
        }
        content.addView(startRow)
        content.addView(payoutRow)
        cycleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshPayoutRow()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refreshPayoutRow()
        content.addView(TextView(themeContext).apply {
            text = ctx.getString(R.string.investment_default_hint)
            setTextColor(Color.parseColor("#8A9099"))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })

        val dialog = AlertDialog.Builder(themeContext)
            .setTitle(ctx.getString(R.string.set_investment_time))
            .setView(content)
            .setNegativeButton(ctx.getString(R.string.cancel), null)
            .setPositiveButton(ctx.getString(R.string.confirm), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val annualRate = if (usePreviousRate) {
                    previousAnnualInterestRate ?: 0.0
                } else {
                    parseLocalizedAmount(etAnnualRate.text?.toString().orEmpty())
                }
                if (!InvestmentInterestService.isValidAnnualRate(annualRate)) {
                    etAnnualRate.error = "请输入大于 -100 且不超过 10000 的年利率"
                    return@setOnClickListener
                }
                hasCheckedInvestmentSchedulePrompt = true
                onConfirm(
                    InvestmentInterestService.InvestmentSchedule(
                        startEarningAt = startEarningAt,
                        firstPayoutAt = InvestmentInterestService.firstPayoutFor(
                            startEarningAt,
                            cycleOptions[cycleSpinner.selectedItemPosition.coerceAtLeast(0)].second
                        ),
                        annualInterestRate = annualRate,
                        settlementCycle = cycleOptions[cycleSpinner.selectedItemPosition.coerceAtLeast(0)].second
                    )
                )
                dialog.dismiss()
            }
        }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = safeContext,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun formatDateForSchedule(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd E", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun showOverlayFriendlyDatePicker(
        initialTimeMillis: Long,
        minTimeMillis: Long? = null,
        onDateSelected: (Long) -> Unit
    ) {
        val safeContext = ctx
        if (safeContext is Activity && safeContext.isFinishing) return

        val themeContext = ContextThemeWrapper(safeContext, R.style.Theme_TapAccounting)
        val minDay = minTimeMillis?.let { InvestmentInterestService.startOfDay(it) }
        var selectedDay = InvestmentInterestService.startOfDay(initialTimeMillis)
        if (minDay != null && selectedDay < minDay) selectedDay = minDay

        val visibleMonth = Calendar.getInstance().apply { timeInMillis = selectedDay }
        visibleMonth.set(Calendar.DAY_OF_MONTH, 1)

        val root = LinearLayout(themeContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(6))
        }
        val header = LinearLayout(themeContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnPrev = TextView(themeContext).apply {
            text = "<"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#324860"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val title = TextView(themeContext).apply {
            gravity = Gravity.CENTER
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#24374F"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnNext = TextView(themeContext).apply {
            text = ">"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#324860"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        header.addView(btnPrev)
        header.addView(title)
        header.addView(btnNext)
        root.addView(header)

        val weekRow = GridLayout(themeContext).apply {
            columnCount = 7
            setPadding(0, dp(8), 0, dp(4))
        }
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
            weekRow.addView(TextView(themeContext).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#98A2B3"))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(28)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            })
        }
        root.addView(weekRow)

        val daysGrid = GridLayout(themeContext).apply {
            columnCount = 7
            rowCount = 6
        }
        root.addView(daysGrid)

        fun refreshCalendar() {
            title.text = SimpleDateFormat("yyyy年MM月", Locale.CHINA).format(visibleMonth.time)
            daysGrid.removeAllViews()

            val monthStart = visibleMonth.clone() as Calendar
            val firstDayOfWeek = monthStart.get(Calendar.DAY_OF_WEEK)
            val leadingBlanks = (firstDayOfWeek + 5) % 7
            val daysInMonth = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
            val todayStart = InvestmentInterestService.startOfDay(System.currentTimeMillis())

            repeat(42) { index ->
                val dayNumber = index - leadingBlanks + 1
                val cell = TextView(themeContext).apply {
                    gravity = Gravity.CENTER
                    textSize = 15f
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(42)
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    }
                }
                if (dayNumber !in 1..daysInMonth) {
                    cell.text = ""
                    cell.isEnabled = false
                } else {
                    val cellDay = (visibleMonth.clone() as Calendar).apply {
                        set(Calendar.DAY_OF_MONTH, dayNumber)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    val enabled = minDay == null || cellDay >= minDay
                    cell.text = dayNumber.toString()
                    cell.isEnabled = enabled
                    cell.alpha = if (enabled) 1f else 0.35f
                    when {
                        cellDay == selectedDay -> {
                            cell.setTextColor(Color.WHITE)
                            cell.setBackgroundResource(R.drawable.bg_elegant_calendar_day_selected)
                        }
                        cellDay == todayStart -> {
                            cell.setTextColor(Color.parseColor("#5C6BC0"))
                            cell.setBackgroundResource(R.drawable.bg_elegant_calendar_day_today)
                        }
                        else -> {
                            cell.setTextColor(Color.parseColor("#24374F"))
                            cell.background = null
                        }
                    }
                    cell.setOnClickListener {
                        if (!enabled) return@setOnClickListener
                        selectedDay = cellDay
                        refreshCalendar()
                    }
                }
                daysGrid.addView(cell)
            }
        }

        btnPrev.setOnClickListener {
            visibleMonth.add(Calendar.MONTH, -1)
            refreshCalendar()
        }
        btnNext.setOnClickListener {
            visibleMonth.add(Calendar.MONTH, 1)
            refreshCalendar()
        }
        refreshCalendar()

        val dialog = AlertDialog.Builder(themeContext)
            .setTitle(ctx.getString(R.string.select_date))
            .setView(root)
            .setNegativeButton(ctx.getString(R.string.cancel), null)
            .setPositiveButton(ctx.getString(R.string.confirm)) { _, _ -> onDateSelected(selectedDay) }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = safeContext,
            widthRatio = 0.9f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun dp(value: Int): Int {
        return (value * rootView.resources.displayMetrics.density).toInt()
    }

    private fun parseLocalizedAmount(raw: String): Double {
        val value = raw.trim().removeSuffix("%").trim()
        if (value.isEmpty() || value == "-") return 0.0

        val localized = NumberFormat.getNumberInstance(Locale.getDefault())
        val parsePosition = ParsePosition(0)
        localized.parse(value, parsePosition)?.toDouble()
            ?.takeIf { parsePosition.index == value.length }
            ?.let { return it }

        val normalized = value
            .replace("\\s".toRegex(), "")
            .replace(',', '.')

        return normalized.toDoubleOrNull() ?: 0.0
    }

    private fun formatCompactDecimal(value: Double): String {
        return String.format(Locale.getDefault(), "%.4f", value)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun handleSave() {
        if (isSaving) return
        val money = parseAmountInput() ?: 0.0
        if (money <= 0) {
            Utils.toast(ctx, ctx.getString(R.string.toast_input_amount))
            return
        }
        val spinnerPos = spType.selectedItemPosition
        // 0=支出,1=收入,2=转账,3=还款（本质 type=2 subType=REPAYMENT）
        val isRepayment = isAssetFeatureEnabled && spinnerPos == 3
        var type = if (spinnerPos > 2) 2 else spinnerPos
        if (!isAssetFeatureEnabled && type !in 0..1) {
            Utils.toast(ctx, ctx.getString(R.string.toast_no_asset_mode))
            return
        }

        // 开启多币种且为转账时，检查是否需要确认汇率
        if (isAssetFeatureEnabled && type == 2 && !isRepayment && Prefs.isShowMultiCurrency(ctx) && !hasConfirmedExchangeRate) {
            // 先检查转出账户和转入账户的币种是否相同
            val accountName1 = tvAccount.text.toString()
            val accountName2 = tvAccount2.text.toString()
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(ctx)
                val asset1 = db.assetDao().getAssetByName(accountName1)
                val asset2 = if (accountName2 != ctx.getString(R.string.select_to_account) && accountName2 != ctx.getString(R.string.to_account) && accountName2.isNotEmpty())
                    db.assetDao().getAssetByName(accountName2) else null
                
                val sourceCurrency = asset1?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
                val targetCurrency = asset2?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
                
            withContext(Dispatchers.Main) {
                val currentSpinnerCurrency = spCurrency.selectedItem as? String ?: "CNY"
                if (currentSpinnerCurrency != sourceCurrency) {
                    setCurrency(sourceCurrency)
                }
                // 如果币种相同，无需弹窗，直接标记为已确认
                if (sourceCurrency == targetCurrency) {
                        customTargetAmount = money
                        customTransferRate = 1.0
                        hasConfirmedExchangeRate = true
                        // 继续保存流程，递归调用 handleSave，此时会跳过汇率检查
                        handleSave()
                    } else {
                        // 币种不同，弹出汇率对话框
                        showExchangeDialog()
                    }
                }
            }
            return
        }

        // 开启多币种且为支出/收入，且选择币种与账户币种不同时，先弹汇率确认
        if (isAssetFeatureEnabled && (type == 0 || type == 1) && Prefs.isShowMultiCurrency(ctx) && !hasConfirmedCurrencyRate) {
            val selectedCurrency = spCurrency.selectedItem as? String ?: "CNY"
            val accountName1 = tvAccount.text.toString()
            scope.launch(Dispatchers.IO) {
                val asset1 = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(accountName1)
                val assetCurrency = asset1?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
                withContext(Dispatchers.Main) {
                    if (selectedCurrency != assetCurrency) {
                        showCurrencyExchangeDialog { handleSave() }
                    } else {
                        // 币种一致，无需汇率确认，直接继续保存
                        customCurrencyRate = null
                        customCurrencyTargetAmount = null
                        hasConfirmedCurrencyRate = true
                        handleSave()
                    }
                }
            }
            return
        }

        val subType = if (isRepayment) Bill.SUBTYPE_REPAYMENT else Bill.SUBTYPE_NORMAL
        
        val accountName1 = if (isAssetFeatureEnabled) tvAccount.text.toString() else ""
        val accountName2 = if (isAssetFeatureEnabled) tvAccount2.text.toString() else ""

        if (editingBillId == null &&
            isAssetFeatureEnabled &&
            type == Bill.TYPE_TRANSFER &&
            !isRepayment &&
            !hasCheckedInvestmentSchedulePrompt &&
            pendingInvestmentSchedule == null
        ) {
            scope.launch(Dispatchers.IO) {
                val targetAsset = accountName2.takeIf { it.isNotBlank() }
                    ?.let { AppDatabase.getDatabase(ctx).assetDao().getAssetByName(it) }
                if (targetAsset?.assetCategory == Asset.CATEGORY_INVESTMENT) {
                    val previousLot = AppDatabase.getDatabase(ctx)
                        .investmentLotDao()
                        .getLatestOpenLotWithRateByAssetId(targetAsset.id)
                    val transferTime = parseUiTimeToMillis(tvTime.text?.toString().orEmpty()) ?: System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        showInvestmentScheduleDialog(
                            transferTime = transferTime,
                            targetName = targetAsset.name,
                            annualInterestRate = targetAsset.annualInterestRate,
                            previousAnnualInterestRate = previousLot?.annualInterestRate,
                            previousSettlementCycle = previousLot?.settlementCycle
                        ) { schedule ->
                            pendingInvestmentSchedule = schedule
                            handleSave()
                        }
                    }
                    return@launch
                }
                hasCheckedInvestmentSchedulePrompt = true
                withContext(Dispatchers.Main) { handleSave() }
            }
            return
        }

        isSaving = true
        scope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(ctx)
            val writableBook = BookAccountManager.resolveWritableBook(ctx, selectedFormBook)
            val timeStr = tvTime.text.toString()
            val parsedTimeLong = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(timeStr)?.time
            } catch (_: Exception) {
                null
            }
            val oldBill = editingBillId?.let { db.billDao().getBillById(it) }
            val timeLong = parsedTimeLong ?: oldBill?.time ?: System.currentTimeMillis()

            val asset1 = accountName1.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
            val asset2 = if (type == 2) accountName2.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) } else null

            if (isRepayment) {
                when {
                    asset1 == null || asset2 == null -> {
                        withContext(Dispatchers.Main) {
                            Utils.toast(ctx, ctx.getString(R.string.toast_repayment_require_both))
                        }
                        return@launch
                    }
                    asset1.assetCategory == Asset.CATEGORY_CREDIT_CARD -> {
                        withContext(Dispatchers.Main) {
                            Utils.toast(ctx, ctx.getString(R.string.toast_repayment_from_not_credit))
                        }
                        return@launch
                    }
                    asset2.assetCategory != Asset.CATEGORY_CREDIT_CARD -> {
                        withContext(Dispatchers.Main) {
                            Utils.toast(ctx, ctx.getString(R.string.toast_repayment_to_must_credit))
                        }
                        return@launch
                    }
                }
            }

            if (type == Bill.TYPE_TRANSFER && !isRepayment && (asset1 == null || asset2 == null)) {
                withContext(Dispatchers.Main) {
                    Utils.toast(ctx, ctx.getString(R.string.toast_transfer_require_both))
                }
                return@launch
            }

            val selectedCurrency = spCurrency.selectedItem as? String ?: "CNY"
            val effectiveCurrency = if (type == Bill.TYPE_TRANSFER) {
                asset1?.currency?.takeIf { it.isNotEmpty() } ?: selectedCurrency
            } else {
                selectedCurrency
            }
            val ratesReady = ensureRequiredRatesReady(
                type = type,
                isRepayment = isRepayment,
                selectedCurrency = effectiveCurrency,
                sourceAsset = asset1,
                targetAsset = asset2
            )
            if (!ratesReady) {
                return@launch
            }

            var finalCategory = tvCategory.text.toString()
            if (type == 2) {
                finalCategory = if (isRepayment) "还款" else "转账"
            }
            
            // 读取手续费（仅转账，非还款）
            val feeVal = if (type == 2 && !isRepayment) {
                etFee.text.toString().toDoubleOrNull() ?: 0.0
            } else 0.0

            // Calculate Rates and Target Amount
            var finalRate = 1.0
            var sourceDelta = money
            var targetDelta = money
            
            if (type == 2) {
                // Determine Source Delta (in Asset1 Currency)
                val rateSource = CurrencyManager.getRate(asset1?.currency ?: "CNY") ?: 1.0
                val rateTrans = CurrencyManager.getRate(selectedCurrency) ?: 1.0
                
                // Convert Transaction Amount -> Source Asset Currency
                // amount(Trans) * (Rate(CNY->Source) / Rate(CNY->Trans))? No.
                // Trans(USD). Source(CNY).
                // 100 USD = 100 * Rate(USD->CNY) CNY.
                // Rate(USD->CNY) = Rate(CNY->CNY) / Rate(CNY->USD) ?? No.
                // Rate in Manager is (1 Unit -> ? CNY) ??
                // Let's check CurrencyManager.kt read_file.
                // DEFAULT_RATES = mapOf("CNY" to 1.0, "USD" to 0.14). 
                // "USD" to 0.14 means 1 CNY = 0.14 USD? Or 1 USD = 0.14 CNY?
                // PREF_KEY_RATES... API_URL = .../latest/CNY.
                // Usually API returns: Base CNY. rates: {"USD": 0.14, ...}
                // Meaning 1 CNY = 0.14 USD.
                // So Rate(CNY->USD) = 0.14.
                // So to convert USD to CNY.
                // Amount(USD) / 0.14 = Amount(CNY).
                
                val sourceRateMapVal = CurrencyManager.getRate(asset1?.currency ?: "CNY") ?: 1.0
                if (sourceRateMapVal != 0.0) {
                     // Transaction(Selected) -> Source(Asset)
                     // Trans -> CNY -> Source
                     // Amt(Trans) / Rate(CNY->Trans) * Rate(CNY->Source) ?
                     // Amt(Trans) / Rate(CNY->Trans) = Amt(CNY).
                     // Amt(CNY) * Rate(CNY->Source) = Amt(Source).
                     val rateTransMapVal = CurrencyManager.getRate(effectiveCurrency) ?: 1.0
                     if (rateTransMapVal != 0.0) {
                         sourceDelta = (money / rateTransMapVal) * sourceRateMapVal
                     }
                }
                
                // Determine Target Delta
                // If customTargetAmount set, use it.
                // Else calc: Trans -> CNY -> Target
                if (customTransferRate != null) {
                    finalRate = customTransferRate!!
                    targetDelta = money * finalRate
                } else if (customTargetAmount != null) {
                    targetDelta = customTargetAmount!!
                    if (money != 0.0) finalRate = targetDelta / money
                } else {
                     val rateTargetMapVal = CurrencyManager.getRate(asset2?.currency ?: "CNY") ?: 1.0
                     val rateTransMapVal = CurrencyManager.getRate(effectiveCurrency) ?: 1.0
                     if (rateTransMapVal != 0.0) {
                         targetDelta = (money / rateTransMapVal) * rateTargetMapVal
                         if (money != 0.0) finalRate = targetDelta / money
                     }
                }
            } else {
                // 支出/收入：若用户已在汇率窗口确认实际扣减金额，直接使用；
                // 否则按汇率表自动换算（currency 与账户一致时 sourceDelta == money）。
                if (customCurrencyRate != null) {
                    sourceDelta = money * customCurrencyRate!!
                } else if (customCurrencyTargetAmount != null) {
                    sourceDelta = customCurrencyTargetAmount!!
                } else {
                    val rateTransMapVal = CurrencyManager.getRate(effectiveCurrency) ?: 1.0
                    val sourceRateMapVal = CurrencyManager.getRate(asset1?.currency ?: "CNY") ?: 1.0
                    if (rateTransMapVal != 0.0) {
                        sourceDelta = (money / rateTransMapVal) * sourceRateMapVal
                    }
                }
                // 将记账时的换算比率存入 exchangeRate（统一折算为 CNY 等值）；
                // 统计时用 amount * exchangeRate 得到 CNY 等值，避免因汇率波动导致历史数据失真。
                // 例：记了 10 PLN（账户也是 PLN）：rate=0.56，exchangeRate = 1/0.56 ≈ 1.785
                //     统计：10 * 1.785 ≈ 17.85 CNY ✅
                if (effectiveCurrency == "CNY") {
                    finalRate = 1.0
                } else {
                    // exchangeRate 始终保存"该币种 -> CNY"的换算率，
                    // 这样默认人民币统计和详情页"≈人民币"都能稳定成立。
                    finalRate = BillAssetImpactService.estimateExchangeRateToCny(effectiveCurrency)
                }
            }

            var rBill = Bill(
                id = editingBillId ?: 0,
                amount = money,
                type = type,
                subType = subType,
                accountName = accountName1,
                accountId = asset1?.id,
                toAccountId = asset2?.id,
                toAccountName = if (type == 2) accountName2 else "",
                categoryName = finalCategory,
                time = timeLong,
                remark = etRemark.text.toString(),
                currency = effectiveCurrency,
                exchangeRate = finalRate,
                fee = feeVal,
                bookName = writableBook
            )

            if (editingBillId != null && oldBill != null) {
                try {
                    rBill = BillMutationService.replaceBill(
                        db = db,
                        oldBill = oldBill,
                        newBill = rBill,
                        applyAssetImpact = true
                    )
                } catch (e: IllegalArgumentException) {
                    withContext(Dispatchers.Main) {
                        Utils.toast(ctx, ctx.getString(R.string.toast_save_failed))
                    }
                    return@launch
                }
            }

            // === 退款来源处理：若选择了退款来源账单，修改 rBill 为退款账单类型 ===
            val refundSourceBill = selectedRefundSourceBill
            var latestRefundSource: com.taostudio.tapaccounting.data.local.entity.Bill? = null
            if (refundSourceBill != null && type == 1 && editingBillId == null) {
                latestRefundSource = db.billDao().getBillById(refundSourceBill.id)
                if (latestRefundSource != null
                    && latestRefundSource.type == com.taostudio.tapaccounting.data.local.entity.Bill.TYPE_EXPENSE
                    && latestRefundSource.subType != com.taostudio.tapaccounting.data.local.entity.Bill.SUBTYPE_REFUND) {
                    val sourceCategory = if (latestRefundSource.categoryName.startsWith("退款："))
                        latestRefundSource.categoryName.removePrefix("退款：").trim()
                    else if (latestRefundSource.categoryName.startsWith("退款·"))
                        latestRefundSource.categoryName.removePrefix("退款·").trim()  // 兼容旧数据
                    else latestRefundSource.categoryName.trim()
                    rBill = rBill.copy(
                        subType = com.taostudio.tapaccounting.data.local.entity.Bill.SUBTYPE_REFUND,
                        relatedBillId = latestRefundSource.id,
                        categoryName = "退款：$sourceCategory",
                        originalAmount = money
                    )
                } else {
                    latestRefundSource = null // 来源账单无效，按普通收入处理
                }
            }

            val scheduleForInvestment = pendingInvestmentSchedule
            if (editingBillId == null) {
                if (latestRefundSource != null) {
                    try {
                        rBill = BillMutationService.saveRefundBill(
                            db = db,
                            originalBill = latestRefundSource,
                            refundBill = rBill
                        )
                    } catch (e: IllegalArgumentException) {
                        withContext(Dispatchers.Main) {
                            Utils.toast(ctx, ctx.getString(R.string.toast_refund_exceeds_balance))
                        }
                        return@launch
                    } catch (e: IllegalStateException) {
                        withContext(Dispatchers.Main) {
                            Utils.toast(ctx, ctx.getString(R.string.toast_original_bill_invalid))
                        }
                        return@launch
                    }
                } else {
                    rBill = if (scheduleForInvestment != null &&
                        rBill.type == Bill.TYPE_TRANSFER &&
                        asset2?.assetCategory == Asset.CATEGORY_INVESTMENT
                    ) {
                        InvestmentInterestService.insertTransferWithLot(
                            db = db,
                            bill = rBill,
                            targetAsset = asset2,
                            schedule = scheduleForInvestment
                        )
                    } else {
                        BillMutationService.upsertBillAndApplyImpact(
                            db = db,
                            bill = rBill,
                            applyAssetImpact = true
                        )
                    }
                }
            }

            withContext(Dispatchers.Main) {
                Utils.toast(ctx, ctx.getString(R.string.toast_accounting_success))
                if (Prefs.isSaveVibrateEnabled(ctx)) {
                    Utils.vibrate(ctx, 50)
                }
                // Reset custom transfer data
                customTransferRate = null
                customTargetAmount = null
                savedTransferRateForEdit = null
                hasConfirmedExchangeRate = false
                pendingInvestmentSchedule = null
                hasCheckedInvestmentSchedulePrompt = false
                // Reset custom currency rate data
                customCurrencyTargetAmount = null
                customCurrencyRate = null
                hasConfirmedCurrencyRate = false
                // 重置退款来源选择（如果保存后表单不关闭继续使用，退出退款模式）
                if (isRefundMode) exitRefundMode()
                tvRefundToggle?.visibility = if (spType.selectedItemPosition == 1) View.VISIBLE else View.GONE
                
                // ----------- AI 纠错拦截逻辑 -----------
                val originalText = lastAiSuggestOriginalText
                if (originalText != null && Prefs.isAiPromptCorrectionEnabled(ctx)) {
                    val changedType = if (lastAiSuggestType != null && lastAiSuggestType != type) type else null
                    
                    val normalizedSuggestCat = lastAiSuggestCategory?.replace(" ", "")?.replace("/::/", "-")?.replace("/:::/", "-") ?: ""
                    val normalizedFinalCat = finalCategory.replace(" ", "")
                    val changedCat = if (normalizedSuggestCat.isNotEmpty() && normalizedSuggestCat != normalizedFinalCat) finalCategory else null
                    
                    val normalizedSuggestAcc1 = lastAiSuggestAccount1?.replace(" ", "") ?: ""
                    val normalizedFinalAcc1 = accountName1.replace(" ", "")
                    val changedAcc1 = if (isAssetFeatureEnabled && normalizedSuggestAcc1.isNotEmpty() && normalizedSuggestAcc1 != normalizedFinalAcc1) accountName1 else null
                    
                    val normalizedSuggestAcc2 = lastAiSuggestAccount2?.replace(" ", "") ?: ""
                    val normalizedFinalAcc2 = accountName2.replace(" ", "")
                    val changedAcc2 = if (isAssetFeatureEnabled && normalizedSuggestAcc2.isNotEmpty() && normalizedSuggestAcc2 != normalizedFinalAcc2) accountName2 else null

                    var actualChangedCat = changedCat
                    if (type == 2) {
                        actualChangedCat = null
                    }
                    
                    var actualChangedAcc2 = changedAcc2
                    if (type == 0 || type == 1) {
                        actualChangedAcc2 = null
                    }

                    if (changedType != null || actualChangedCat != null) {
                        lastAiSuggestOriginalText = null
                        val acc1ToSuggest = if (changedType != null) (changedAcc1 ?: accountName1) else null

                        val suggestion = RuleCreateSuggestion(
                            originalText = originalText,
                            beforeType = lastAiSuggestType,
                            beforeCategory = lastAiSuggestCategory?.takeIf { it.isNotBlank() },
                            finalType = changedType ?: type,
                            finalCategory = actualChangedCat ?: finalCategory,
                            finalAcc1 = acc1ToSuggest,
                            finalAcc2 = actualChangedAcc2
                        )
                        handleRulePromptAfterBookkeeping(suggestion)
                        return@withContext
                    }
                }
                lastAiSuggestOriginalText = null

                if (pendingBills.isNotEmpty()) {
                    processNextPendingBill()
                } else {
                    onCloseRequest(true)
                }
            }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    isSaving = false
                }
            }
        }
    }

    private suspend fun ensureRequiredRatesReady(
        type: Int,
        isRepayment: Boolean,
        selectedCurrency: String,
        sourceAsset: Asset?,
        targetAsset: Asset?
    ): Boolean {
        if (!Prefs.isShowMultiCurrency(ctx)) return true

        val requiredCurrencies = linkedSetOf<String>()
        requiredCurrencies.add(selectedCurrency.trim().uppercase(Locale.ROOT))
        sourceAsset?.currency?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }?.let { requiredCurrencies.add(it) }

        if (type == Bill.TYPE_TRANSFER && !isRepayment) {
            targetAsset?.currency?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }?.let { requiredCurrencies.add(it) }
        }

        fun missingRates(currencies: Set<String>): Set<String> {
            return currencies
                .filter { code -> code != "CNY" && CurrencyManager.getRate(code) == null }
                .toSet()
        }

        val missingBefore = missingRates(requiredCurrencies)
        if (missingBefore.isEmpty()) return true

        withContext(Dispatchers.Main) {
            Utils.toast(ctx, ctx.getString(R.string.toast_refreshing_rates))
        }

        val refreshSuccess = suspendCancellableCoroutine<Boolean> { cont ->
            CurrencyManager.updateRates(ctx) { success ->
                if (cont.isActive) cont.resume(success)
            }
        }

        val missingAfter = missingRates(requiredCurrencies)
        if (missingAfter.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                showMissingRatesBlockingDialog(missingAfter, refreshSuccess)
            }
            return false
        }

        withContext(Dispatchers.Main) {
            Utils.toast(ctx, ctx.getString(R.string.toast_rates_updated))
        }
        return true
    }

    private var isMissingRateDialogShowing = false

    private fun showMissingRatesBlockingDialog(missingCurrencies: Set<String>, refreshSuccess: Boolean) {
        val missingText = missingCurrencies.joinToString("、")
        val suffix = if (refreshSuccess) "" else ctx.getString(R.string.pending_confirm)
        val message = ctx.getString(R.string.missing_rates_message, missingText + suffix)

        val safeContext = ctx
        if (safeContext !is Activity || safeContext.isFinishing) {
            Utils.toast(safeContext, message)
            return
        }

        if (isMissingRateDialogShowing) return
        isMissingRateDialogShowing = true

        val dialog = AlertDialog.Builder(ContextThemeWrapper(safeContext, R.style.Theme_TapAccounting))
            .setTitle(ctx.getString(R.string.missing_rates_title))
            .setMessage(message)
            .setCancelable(true)
            .setNegativeButton(ctx.getString(R.string.later)) { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton(ctx.getString(R.string.copy_missing_currencies)) { _, _ ->
                val clipboard = safeContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("missing_currencies", missingText))
                    Utils.toast(safeContext, safeContext.getString(R.string.toast_copied, missingText))
                } else {
                    Utils.toast(safeContext, safeContext.getString(R.string.toast_copy_failed))
                }
            }
            .setPositiveButton(ctx.getString(R.string.go_update_rates)) { dialog, _ ->
                dialog.dismiss()
                runCatching {
                    safeContext.startActivity(Intent(safeContext, ExchangeRateActivity::class.java))
                }.onFailure {
                    Utils.toast(safeContext, safeContext.getString(R.string.toast_cannot_open_rates))
                }
            }
            .setOnDismissListener {
                isMissingRateDialogShowing = false
            }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = safeContext,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private var hasFinishedSaveFlow = false

    private data class RuleCreateSuggestion(
        val originalText: String,
        val beforeType: Int?,
        val beforeCategory: String?,
        val finalType: Int,
        val finalCategory: String?,
        val finalAcc1: String?,
        val finalAcc2: String?
    )

    private fun handleRulePromptAfterBookkeeping(suggestion: RuleCreateSuggestion) {
        val safeContext = ctx
        val isOverlayContext = safeContext !is Activity
        if (safeContext is Activity && safeContext.isFinishing) {
            finishSaveFlow()
            return
        }

        RuleLearnPromptHelper.show(
            ctx = safeContext,
            model = RuleLearnPromptHelper.PromptModel(
                referenceText = suggestion.originalText,
                beforeType = suggestion.beforeType,
                afterType = suggestion.finalType,
                beforeCategory = suggestion.beforeCategory,
                afterCategory = suggestion.finalCategory
            ),
            isOverlay = isOverlayContext,
            onContinue = {
                showCreateRuleDialog(
                    suggestion.originalText,
                    suggestion.finalType,
                    suggestion.finalCategory,
                    suggestion.finalAcc1,
                    suggestion.finalAcc2,
                    finishOnDone = true
                )
            },
            onDismiss = { finishSaveFlow() }
        )
    }

    private fun showCreateRuleDialog(
        originalText: String,
        finalType: Int,
        finalCat: String?,
        finalAcc1: String?,
        finalAcc2: String?,
        finishOnDone: Boolean = true
    ) {
        val categoryToSave = if (finalType == 2) null else finalCat
        hasFinishedSaveFlow = false
        RuleDialogHelper.showDialog(
            ctx = ctx,
            rule = null,
            referenceText = originalText,
            defaultType = finalType,
            defaultCat = categoryToSave,
            defaultAcc1 = finalAcc1,
            defaultAcc2 = finalAcc2,
            isOverlay = ctx !is android.app.Activity,
            categoryOnlyLearnMode = true,
            onSave = { newRule ->
                scope.launch(Dispatchers.IO) {
                    val saveResult = saveRuleWithDedupDecision(newRule)
                    withContext(Dispatchers.Main) {
                        when (saveResult) {
                            RuleSaveResult.SAVED -> Utils.toast(ctx, ctx.getString(R.string.rule_saved))
                            RuleSaveResult.SKIPPED -> Utils.toast(ctx, ctx.getString(R.string.rule_save_canceled))
                        }

                        if (finishOnDone && !hasFinishedSaveFlow) {
                            hasFinishedSaveFlow = true
                            finishSaveFlow()
                        }
                    }
                }
            },
            onDelete = null,
            onCancel = { 
                if (finishOnDone && !hasFinishedSaveFlow) {
                    hasFinishedSaveFlow = true
                    finishSaveFlow()
                } else {
                    Utils.toast(ctx, ctx.getString(R.string.toast_rule_add_canceled))
                }
            }
        )
    }

    private enum class RuleSaveResult { SAVED, SKIPPED }

    private suspend fun saveRuleWithDedupDecision(newRule: AiRule): RuleSaveResult {
        val db = AppDatabase.getDatabase(ctx)
        val dao = db.aiRuleDao()
        val existingRules = dao.getRulesByKeyword(newRule.keyword.trim())

        if (existingRules.isEmpty()) {
            dao.insertRule(newRule)
            return RuleSaveResult.SAVED
        }

        val normalizedNewCategory = normalizeRuleCategory(newRule.targetCategory)
        val sameCategoryRule = existingRules.firstOrNull {
            normalizeRuleCategory(it.targetCategory) == normalizedNewCategory
        }
        if (sameCategoryRule != null) {
            dao.insertRule(newRule.copy(id = sameCategoryRule.id))
            return RuleSaveResult.SAVED
        }

        val chosen = withContext(Dispatchers.Main) {
            promptRuleConflictDecision(
                keyword = newRule.keyword,
                existingCategory = existingRules.firstOrNull()?.targetCategory,
                newCategory = newRule.targetCategory
            )
        }

        return when (chosen) {
            RuleConflictDecision.OVERWRITE -> {
                val target = existingRules.firstOrNull()
                if (target != null) {
                    dao.insertRule(newRule.copy(id = target.id))
                } else {
                    dao.insertRule(newRule)
                }
                RuleSaveResult.SAVED
            }
            RuleConflictDecision.CANCEL_RECORD,
            RuleConflictDecision.DISMISS -> RuleSaveResult.SKIPPED
        }
    }

    private enum class RuleConflictDecision { OVERWRITE, CANCEL_RECORD, DISMISS }

    private suspend fun promptRuleConflictDecision(
        keyword: String,
        existingCategory: String?,
        newCategory: String?
    ): RuleConflictDecision = suspendCancellableCoroutine { cont ->
        val safeContext = ctx
        if (safeContext !is Activity || safeContext.isFinishing) {
            cont.resume(RuleConflictDecision.DISMISS)
            return@suspendCancellableCoroutine
        }

        val existingLabel = existingCategory?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.category_not_set)
        val newLabel = newCategory?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.category_not_set)

        val part1 = ctx.getString(R.string.duplicate_rule_dialog_message, keyword, existingLabel)
        val part2 = ctx.getString(R.string.cancel_rule_save)
        val msg = part1 + "\n\n" + part2
        val dialog = AlertDialog.Builder(ContextThemeWrapper(safeContext, R.style.Theme_TapAccounting))
            .setTitle(ctx.getString(R.string.duplicate_keyword_title))
            .setMessage(msg)
            .setPositiveButton(ctx.getString(R.string.overwrite)) { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(RuleConflictDecision.OVERWRITE)
            }
            .setNegativeButton(ctx.getString(R.string.cancel_rule_save)) { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(RuleConflictDecision.CANCEL_RECORD)
            }
            .setOnCancelListener {
                if (cont.isActive) cont.resume(RuleConflictDecision.DISMISS)
            }
            .create()

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = safeContext,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun normalizeRuleCategory(category: String?): String {
        return category
            ?.replace(" ", "")
            ?.replace("/::/", "-")
            ?.replace("/:::/", "-")
            ?.replace(">", "-")
            ?.trim()
            .orEmpty()
    }

    private fun finishSaveFlow() {
        if (pendingBills.isNotEmpty()) {
            processNextPendingBill()
        } else {
            onCloseRequest(true)
        }
    }

    private val pendingBills = mutableListOf<JSONObject>()
    private var isProcessingPendingBillQueue: Boolean = false
    private var totalPendingBillCount: Int = 0

    private fun updateQueueActionUi() {
        if (isProcessingPendingBillQueue) {
            val currentIndex = (totalPendingBillCount - pendingBills.size).coerceAtLeast(1)
            tvTitle?.text = ctx.getString(R.string.accounting_progress, currentIndex, totalPendingBillCount)
            btnCancel.text = ctx.getString(R.string.skip_this)
            btnSave.text = ctx.getString(R.string.save_next)
        } else {
            tvTitle?.text = if (editingBillId != null) ctx.getString(R.string.edit_bill) else ctx.getString(R.string.record_one)
            btnCancel.text = ctx.getString(R.string.cancel)
            btnSave.text = ctx.getString(R.string.save_and_record)
        }
    }

    private fun processNextPendingBill() {
        if (pendingBills.isEmpty()) {
            isProcessingPendingBillQueue = false
            totalPendingBillCount = 0
            updateQueueActionUi()
            onCloseRequest(true)
            return
        }
        isProcessingPendingBillQueue = true
        updateQueueActionUi()
        val next = pendingBills.removeAt(0)
        fillDataToUi(next, showToast = true)
        setCurrency(next.optString("currency", "CNY"))
        
        if (pendingBills.isNotEmpty()) {
            Utils.toast(ctx, ctx.getString(R.string.toast_remaining_bills, pendingBills.size))
        } else {
            Utils.toast(ctx, ctx.getString(R.string.toast_last_bill))
        }
    }

    fun setCurrency(currencyCode: String) {
        val normalized = currencyCode.trim().uppercase(Locale.ROOT)
        if (normalized.isBlank()) return

        val enabledCodes = CurrencyManager.getEnabledCurrencies(ctx).toMutableList().apply {
            if (!contains("CNY")) add(0, "CNY")
        }
        if (!enabledCodes.contains(normalized)) {
            enabledCodes.add(normalized)
            CurrencyManager.setEnabledCurrencies(ctx, enabledCodes)
            setupCurrencySpinner()
        }

        val index = enabledCodes.indexOf(normalized)
        if (index >= 0) {
            isProgrammaticCurrencyChange = true
            spCurrency.setSelection(index)
            isProgrammaticCurrencyChange = false
        }
    }

    private fun convertAmountBetweenCurrencies(amount: Double, fromCurrency: String, toCurrency: String): Double {
        if (fromCurrency.equals(toCurrency, ignoreCase = true)) return amount
        val fromRate = CurrencyManager.getRate(fromCurrency) ?: 1.0
        val toRate = CurrencyManager.getRate(toCurrency) ?: 1.0
        if (fromRate == 0.0) return amount
        return (amount / fromRate) * toRate
    }

    private var lastAiSuggestType: Int? = null
    private var lastAiSuggestCategory: String? = null
    private var lastAiSuggestAccount1: String? = null
    private var lastAiSuggestAccount2: String? = null
    private var lastAiSuggestOriginalText: String? = null

    fun fillDataToUi(json: JSONObject, showToast: Boolean = true, forceMultiMode: Boolean = false) {
        val localRuleCorrected = json.optBoolean("_local_rule_corrected", false)
        val original = json.takeIf { it.has("original_text_from_user") }?.optString("original_text_from_user")
        val remarks = json.takeIf { it.has("remarks") }?.optString("remarks")
        val remark = json.takeIf { it.has("remark") }?.optString("remark")
        lastAiSuggestOriginalText = remarks ?: remark ?: original

        // 根据 AI 返回的 book_name 切换账本
        val books = BookAccountManager.getBookAccounts(ctx)
        val aiBookName = resolveAccountingBookSelection(
            bookId = "",
            bookName = json.optString("book_name", ""),
            availableBooks = books
        )
        if (aiBookName != null && aiBookName != selectedFormBook) {
            selectedFormBook = aiBookName
            tvBook.text = aiBookName
        }

        val isFromAi = json.has("original_text_from_user")
        if (isFromAi) {
            lastAiSuggestType = json.optInt("type", -1).takeIf { it != -1 }
            lastAiSuggestCategory = json.optString("category_name", json.optString("category", ""))
            lastAiSuggestAccount1 = json.optString("asset_name", json.optString("account", ""))
            lastAiSuggestAccount2 = json.optString("to_asset_name", json.optString("to_account", ""))
        } else {
            lastAiSuggestOriginalText = null
            lastAiSuggestType = null
            lastAiSuggestCategory = null
            lastAiSuggestAccount1 = null
            lastAiSuggestAccount2 = null
        }

        val isMulti = forceMultiMode || isCurrentUiMultiMode()
        
        if (isMulti && json.has("bills")) {
            val billsArray = json.getJSONArray("bills")
            if (billsArray.length() == 0) return

            val batchBookName = json.optString("book_name", "")
            val fallbackBookName = BookAccountManager.resolveWritableBook(ctx, selectedFormBook)
            for (i in 0 until billsArray.length()) {
                val billJson = billsArray.getJSONObject(i)
                val resolvedBookName = resolveAccountingBookForSave(
                    billBookName = billJson.optString("book_name", ""),
                    batchBookName = batchBookName,
                    availableBooks = books,
                    fallbackBookName = fallbackBookName
                )
                billJson.put("book_name", resolvedBookName)
            }
            
            val sourceKind = json.optString("source_kind", "")
            val isVisualReviewDraft = sourceKind == "screen_capture" || sourceKind == "receipt_image"
            val isNotSync = Prefs.isMultiBillNotSync(ctx) && !json.optBoolean("requires_review", false) && !isVisualReviewDraft
            if (isNotSync) {
                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(ctx)
                    for (i in 0 until billsArray.length()) {
                        val obj = billsArray.getJSONObject(i)
                        val resolvedBook = obj.optString("book_name", fallbackBookName)
                        
                        var typeIndex = obj.optInt("type", 0)
                        if (!isAssetFeatureEnabled) {
                            typeIndex = if (typeIndex == 1) 1 else 0
                        } else if (typeIndex > 2) {
                            typeIndex = 2
                        }
                        val amt = obj.optDouble("amount", 0.0)
                        val asset1 = if (isAssetFeatureEnabled) obj.optString("asset_name", "") else ""
                        val asset2Name = if (isAssetFeatureEnabled) obj.optString("to_asset_name", "") else ""
                        val a1 = asset1.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
                        val a2ByName = asset2Name.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
                        var cat = com.taostudio.tapaccounting.logic.CategoryNameNormalizer.normalizeForStorage(obj.optString("category_name", ""))
                        var toAssetId: Long? = null
                        var toAssetNm = ""
                        if (isAssetFeatureEnabled && typeIndex == 2) {
                            // 仅当转入/转出都为现有资产时，才允许落为转账
                            if (a1 != null && a2ByName != null) {
                                cat = "转账"
                                toAssetNm = asset2Name
                                toAssetId = a2ByName.id
                            } else {
                                typeIndex = Bill.TYPE_EXPENSE
                                if (cat.isBlank() || cat == "转账") cat = "其他"
                            }
                        }
                        
                        val timeStr = obj.optString("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                        val parsedTime = try { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(timeStr)?.time ?: System.currentTimeMillis() } catch(e:Exception){ System.currentTimeMillis() }
                        val remark = obj.optString("remarks", "")
                        val currency = obj.optString("currency", "CNY").ifBlank { "CNY" }
                        val fee = obj.optDouble("fee", 0.0).coerceAtLeast(0.0)
                        
                        val a2 = if (toAssetId != null) db.assetDao().getAssetById(toAssetId) else null
                        val exchangeRate = when {
                            typeIndex == Bill.TYPE_TRANSFER && a2 != null && amt > 0.0 ->
                                BillAssetImpactService.estimateExchangeRateToTarget(amt, currency, a2.currency)
                            currency == "CNY" -> 1.0
                            else -> {
                                val rateToCurrency = CurrencyManager.getRate(currency) ?: 1.0
                                if (rateToCurrency != 0.0) 1.0 / rateToCurrency else 1.0
                            }
                        }
                        
                        val bill = Bill(
                            amount = amt,
                            type = typeIndex,
                            currency = currency,
                            exchangeRate = exchangeRate,
                            fee = fee,
                            accountName = asset1,
                            accountId = a1?.id,
                            toAccountId = toAssetId,
                            toAccountName = toAssetNm,
                            categoryName = cat,
                            time = parsedTime,
                            remark = remark,
                            bookName = resolvedBook
                        )
                        BillMutationService.insertBillAndApplyImpact(db, bill)
                    }
                    withContext(Dispatchers.Main) {
                        Utils.toast(ctx, ctx.getString(R.string.toast_auto_saved_bills, billsArray.length()))
                        onCloseRequest(true)
                    }
                }
            } else {
                pendingBills.clear()
                totalPendingBillCount = 0
                for (i in 0 until billsArray.length()) {
                    pendingBills.add(billsArray.getJSONObject(i))
                }
                totalPendingBillCount = pendingBills.size
                processNextPendingBill()
            }
            return
        }

        val amount = json.optDouble("amount", 0.0)
        if (amount > 0) etMoney.setText(amount.toString())
        
        if (json.has("type")) {
            var t = json.optInt("type")
            val st = json.optInt("subType", 0)
            if (!isAssetFeatureEnabled) {
                t = if (t == 1) 1 else 0
            } else if (t > 2) {
                t = 2
            }
            // 还款：type=2 + subType=1 -> spinner position=3
            val spinnerPos = if (t == 2 && st == Bill.SUBTYPE_REPAYMENT) 3 else t
            val maxSpinnerPos = if (isAssetFeatureEnabled) 3 else 1
            if (spinnerPos in 0..maxSpinnerPos) spType.setSelection(spinnerPos)
        }
        
        val assetNameFromJson = json.optString("asset_name", "")
        if (isAssetFeatureEnabled && assetNameFromJson.isNotEmpty()) {
            tvAccount.text = assetNameFromJson
            refreshAccountIconForName(assetNameFromJson)
        }

        // 自动匹配资产对应的币种：
        // 优先级：① json 中 AI 已明确返回 currency -> 直接使用
        //         ② json 未返回 currency 或为 CNY（默认值）-> 从资产库查出该资产的 currency
        if (isAssetFeatureEnabled && Prefs.isShowMultiCurrency(ctx) && assetNameFromJson.isNotEmpty()) {
            val aiCurrency = json.optString("currency", "").trim()
            if (aiCurrency.isNotEmpty() && aiCurrency != "CNY") {
                // AI 已明确识别出非 CNY 币种，直接用
                setCurrency(aiCurrency)
            } else {
                // 从资产库自动查询币种
                scope.launch(Dispatchers.IO) {
                    val asset = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(assetNameFromJson)
                    val assetCurrency = asset?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
                    withContext(Dispatchers.Main) {
                        // 仅当资产币种不是 CNY，或 AI 未返回 currency 时才覆盖
                        if (assetCurrency != "CNY" || aiCurrency.isEmpty()) {
                            setCurrency(assetCurrency)
                        }
                    }
                }
            }
        } else if (json.has("currency")) {
            val aiCurrency = json.optString("currency", "").trim()
            if (aiCurrency.isNotEmpty()) setCurrency(aiCurrency)
        }

        if (isAssetFeatureEnabled && json.has("to_asset_name")) {
            val toA = json.optString("to_asset_name")
            if (toA.isNotEmpty()) {
                tvAccount2.text = toA
                refreshAccount2IconForName(toA)
                // 如果当前是转账且转入是信用卡，自动切换为还款
                if (spType.selectedItemPosition == 2) {
                    scope.launch(Dispatchers.IO) {
                        val asset = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(toA)
                        withContext(Dispatchers.Main) {
                            if (asset?.assetCategory == com.taostudio.tapaccounting.data.local.entity.Asset.CATEGORY_CREDIT_CARD) {
                                spType.setSelection(3)
                            }
                        }
                    }
                }
            }
        }
        
        val categoryText = json.optString("category_name", tvCategory.text.toString()).replace("/::/", " - ")
        tvCategory.text = categoryText
        refreshCategoryIconForSelection(categoryText)
        etRemark.setText(json.optString("remarks", json.optString("remark", "")))
        if (json.has("fee")) {
            val fee = json.optDouble("fee", 0.0).coerceAtLeast(0.0)
            etFee.setText(if (fee > 0.0) fee.toString() else "")
        }
        val time = json.optString("time", "")
        if (time.isNotEmpty()) tvTime.text = time

        // 编辑已有账单时恢复账本
        val bookFromJson = json.optString("bookName", "")
        if (bookFromJson.isNotEmpty()) {
            selectedFormBook = BookAccountManager.resolveWritableBook(ctx, bookFromJson)
            tvBook.text = selectedFormBook
        }

        if (json.has("recordTime")) {
            val idStr = json.optString("recordTime", "")
            if (idStr.isNotEmpty()) editingBillId = idStr.toLongOrNull()
        }

        enforceTransferAssetConstraintIfNeeded(json, showToast)
        if (showToast && localRuleCorrected) {
            Utils.toast(ctx, ctx.getString(R.string.toast_local_rule_applied))
        }

        if (editingBillId != null) {
            val savedExchangeRate = json.optDouble("exchange_rate", Double.NaN)
            if (!savedExchangeRate.isNaN()) {
                val currentType = spType.selectedItemPosition
                val isTransferEdit = isAssetFeatureEnabled && currentType == 2
                val isRepaymentEdit = isAssetFeatureEnabled && currentType == 3
                if (isTransferEdit && !isRepaymentEdit) {
                    savedTransferRateForEdit = savedExchangeRate
                    customTransferRate = savedExchangeRate
                    customTargetAmount = null
                    hasConfirmedExchangeRate = true
                } else if (currentType == 0 || currentType == 1) {
                    customCurrencyRate = savedExchangeRate
                    customCurrencyTargetAmount = null
                    hasConfirmedCurrencyRate = true
                }
            }
        }
    }

    private fun enforceTransferAssetConstraintIfNeeded(json: JSONObject, showToast: Boolean) {
        if (!isAssetFeatureEnabled) return
        if (json.optInt("type", -1) != Bill.TYPE_TRANSFER) return
        if (json.optInt("subType", 0) == Bill.SUBTYPE_REPAYMENT) return

        val fromName = json.optString("asset_name", json.optString("account", "")).trim()
        val toName = json.optString("to_asset_name", json.optString("to_account", "")).trim()

        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(ctx)
            val fromAsset = db.assetDao().getAssetByName(fromName)
            val toAsset = db.assetDao().getAssetByName(toName)
            val validTransfer = fromName.isNotEmpty() && toName.isNotEmpty() && fromAsset != null && toAsset != null
            if (validTransfer) return@launch

            withContext(Dispatchers.Main) {
                if (spType.selectedItemPosition == 2) {
                    spType.setSelection(0)
                }
                if (tvAccount2.text.toString() == toName) {
                    tvAccount2.text = ""
                    refreshAccount2IconForName("")
                }
                if (tvCategory.text.toString().trim() == ctx.getString(R.string.transfer)) {
                    tvCategory.text = ctx.getString(R.string.other_category)
                    refreshCategoryIconForSelection(ctx.getString(R.string.other_category))
                }
                if (showToast) {
                    Utils.toast(ctx, ctx.getString(R.string.toast_transfer_to_expense))
                }
            }
        }
    }

}
