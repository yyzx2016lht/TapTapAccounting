package com.taostudio.tapaccounting

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.BaseAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.room.withTransaction
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillAssetImpactService
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.logic.InvestmentInterestService
import com.taostudio.tapaccounting.logic.InvestmentLotDraft
import com.taostudio.tapaccounting.logic.InvestmentLotDraftStorage
import com.taostudio.tapaccounting.logic.InvestmentLotEntryHelper
import com.taostudio.tapaccounting.ui.dialog.InvestmentLotSplitDialog
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import kotlin.math.abs

class AddAssetActivity : AppCompatActivity() {
    private lateinit var etName: EditText
    private lateinit var etBalance: EditText
    private lateinit var etAnnualInterestRate: EditText
    private lateinit var etRemark: EditText
    private lateinit var tvTypeName: TextView
    private lateinit var ivTypeIcon: ImageView
    private lateinit var tvCurrency: TextView
    private lateinit var swIncludeNet: androidx.appcompat.widget.SwitchCompat
    private lateinit var layoutTypePicker: View
    private lateinit var etSearchType: EditText
    private lateinit var tvAssetCategory: TextView
    private lateinit var layoutAnnualInterestRate: View
    private lateinit var layoutCreditLimit: View
    private lateinit var etCreditLimit: EditText
    private lateinit var layoutCreditCycle: View
    private lateinit var tvStatementDay: TextView
    private lateinit var tvDueDay: TextView
    private var selectedStatementDay: Int = 0
    private var selectedDueDay: Int = 0

    private var selectedType: String = ""
    private var selectedIcon: String = ""
    private var selectedCurrency: String = "CNY"
    private var selectedAssetCategory: String = Asset.CATEGORY_FUND
    private var assetId: Long = -1L
    private var originalSortOrder: Int = 0
    private var originalPickerSortOrder: Int = 0
    private var allIcons: List<BuiltInCategory> = emptyList()
    private var suppressBalanceWatcher = false
    private var balanceEditedByUser = false
    private var pendingAssetSave: PendingAssetSave? = null
    private var forceInvestmentLotSplitHandled = false
    private val assetUiPrefs by lazy { getSharedPreferences(PREFS_ASSET_UI, MODE_PRIVATE) }

    /** 本页统一取色：值/可点选值/选中态用品牌色，标签用主文本色，占位与说明用次文本色。 */
    private val brandColor by lazy { ContextCompat.getColor(this, R.color.brand_primary) }
    private val textPrimaryColor by lazy { ContextCompat.getColor(this, R.color.text_primary) }

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val balanceAdjustmentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingAssetSave ?: return@registerForActivityResult
        pendingAssetSave = null
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        val data = result.data
        val mode = data?.getStringExtra(BalanceAdjustmentActivity.RESULT_MODE)
            ?: BalanceAdjustmentActivity.MODE_SAVE_ONLY

        lifecycleScope.launch {
            persistAssetChange(
                pending = pending,
                adjustment = if (mode == BalanceAdjustmentActivity.MODE_SAVE_WITH_RECORD && data != null) {
                    AdjustmentDraft(
                        categoryName = data.getStringExtra(BalanceAdjustmentActivity.RESULT_CATEGORY_NAME).orEmpty(),
                        includeInStats = data.getBooleanExtra(BalanceAdjustmentActivity.RESULT_INCLUDE_IN_STATS, true),
                        remark = data.getStringExtra(BalanceAdjustmentActivity.RESULT_REMARK).orEmpty()
                    )
                } else {
                    null
                }
            )
            finish()
        }
    }

    private data class PendingAssetSave(
        val asset: Asset,
        val oldName: String,
        val oldBalance: Double,
        val oldCurrency: String,
        val isNew: Boolean,
        val investmentLots: List<InvestmentLotDraft> = emptyList(),
        val investmentDraftLots: List<InvestmentLotDraft> = emptyList()
    )

    private data class AdjustmentDraft(
        val categoryName: String,
        val includeInStats: Boolean,
        val remark: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_asset)

        assetId = intent.getLongExtra("ASSET_ID", -1L)

        initViews()
        setupTypePicker()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBackAction()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (assetId != -1L) {
            loadAssetData()
        }
    }

    private fun initViews() {
        etName = findViewById(R.id.et_name)
        etBalance = findViewById(R.id.et_balance)
        etAnnualInterestRate = findViewById(R.id.et_annual_interest_rate)
        etRemark = findViewById(R.id.et_remark)
        tvTypeName = findViewById(R.id.tv_type_name)
        ivTypeIcon = findViewById(R.id.iv_type_icon)
        tvCurrency = findViewById(R.id.tv_currency)
        swIncludeNet = findViewById(R.id.sw_include_net)
        layoutTypePicker = findViewById(R.id.layout_type_picker)
        etSearchType = findViewById(R.id.et_search_type)
        tvAssetCategory = findViewById(R.id.tv_asset_category)
        layoutAnnualInterestRate = findViewById(R.id.layout_annual_interest_rate)

        // P1-5: 信用卡字段
        layoutCreditLimit = findViewById(R.id.layout_credit_limit)
        etCreditLimit = findViewById(R.id.et_credit_limit)
        layoutCreditCycle = findViewById(R.id.layout_credit_cycle)
        tvStatementDay = findViewById(R.id.tv_statement_day)
        tvDueDay = findViewById(R.id.tv_due_day)

        fun refreshDayLabels() {
            tvStatementDay.text = com.taostudio.tapaccounting.ui.common.DayPickerDialog.formatDay(selectedStatementDay)
            tvDueDay.text = com.taostudio.tapaccounting.ui.common.DayPickerDialog.formatDay(selectedDueDay)
        }
        refreshDayLabels()
        findViewById<View>(R.id.layout_statement_day).setOnClickListener {
            com.taostudio.tapaccounting.ui.common.DayPickerDialog.show(
                this,
                getString(R.string.credit_statement_day),
                selectedStatementDay
            ) { day ->
                selectedStatementDay = day
                refreshDayLabels()
            }
        }
        findViewById<View>(R.id.layout_due_day).setOnClickListener {
            com.taostudio.tapaccounting.ui.common.DayPickerDialog.show(
                this,
                getString(R.string.credit_due_day),
                selectedDueDay
            ) { day ->
                selectedDueDay = day
                refreshDayLabels()
            }
        }

        refreshCurrencyDisplay()

        findViewById<View>(R.id.btn_back).setOnClickListener {
            if (!handleBackAction()) finish()
        }
        findViewById<View>(R.id.btn_save).setOnClickListener { saveAsset() }

        etRemark.dismissKeyboardOnEnter()

        findViewById<View>(R.id.layout_select_type).setOnClickListener {
            layoutTypePicker.visibility =
                if (layoutTypePicker.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<View>(R.id.layout_select_currency).setOnClickListener {
            showCurrencyDialog()
        }

        findViewById<View>(R.id.layout_select_asset_category).setOnClickListener {
            showAssetCategoryDialog()
        }

        etSearchType.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterIcons(s.toString())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        etBalance.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!suppressBalanceWatcher) {
                    balanceEditedByUser = true
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        swIncludeNet.setOnCheckedChangeListener { buttonView, isChecked ->
            // Programmatic state updates (e.g. loading existing asset) should not trigger this one-time tip.
            if (!buttonView.isPressed) return@setOnCheckedChangeListener
            if (isChecked) return@setOnCheckedChangeListener
            if (assetUiPrefs.getBoolean(KEY_SKIP_NET_ASSET_TIP_SHOWN, false)) return@setOnCheckedChangeListener

            Toast.makeText(
                this,
                getString(R.string.net_asset_tip),
                Toast.LENGTH_LONG
            ).show()
            assetUiPrefs.edit().putBoolean(KEY_SKIP_NET_ASSET_TIP_SHOWN, true).apply()
        }
    }

    private fun handleBackAction(): Boolean {
        if (layoutTypePicker.visibility == View.VISIBLE) {
            layoutTypePicker.visibility = View.GONE
            etSearchType.setText("")
            return true
        }
        return false
    }

    private fun setupTypePicker() {
        allIcons = loadAssetsJson()
        val rvIcons = findViewById<RecyclerView>(R.id.rv_icon_list)
        rvIcons.layoutManager = GridLayoutManager(this, 4)
        rvIcons.adapter = IconPickerAdapter(allIcons) { item ->
            val oldTypeName = selectedType
            selectedType = item.name
            selectedIcon = item.icon
            selectedAssetCategory = when (item.type.uppercase(Locale.ROOT)) {
                "CREDIT" -> Asset.CATEGORY_CREDIT_CARD
                else -> selectedAssetCategory
            }
            tvTypeName.text = item.name
            tvTypeName.setTextColor(brandColor)
            Glide.with(this)
                .load(AssetIconDefaults.withDefault(item.icon))
                .transform(CircleCrop())
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .into(ivTypeIcon)

            val currentName = etName.text.toString()
            if (currentName.isEmpty() || currentName == oldTypeName) {
                etName.setText(item.name)
            }
            updateAssetCategoryUI()
            layoutTypePicker.visibility = View.GONE
        }
    }

    private fun filterIcons(query: String) {
        val filtered = if (query.isEmpty()) {
            allIcons
        } else {
            allIcons.filter { it.name.contains(query, ignoreCase = true) }
        }
        (findViewById<RecyclerView>(R.id.rv_icon_list).adapter as? IconPickerAdapter)?.updateData(filtered)
    }

    private fun loadAssetData() {
        findViewById<TextView>(R.id.tv_title).text = getString(R.string.edit_asset_title)
        lifecycleScope.launch {
            val asset = db.assetDao().getAssetById(assetId)
            asset?.let {
                etName.setText(it.name)

                val balanceFormatted = String.format(Locale.getDefault(), "%.2f", it.balance)
                    .trimEnd('0')
                    .trimEnd('.')
                    .ifEmpty { "0" }

                suppressBalanceWatcher = true
                etBalance.setText(balanceFormatted)
                suppressBalanceWatcher = false
                balanceEditedByUser = false

                etBalance.post {
                    etBalance.selectAll()
                    etBalance.requestFocus()
                }

                etRemark.setText(it.remark)
                etAnnualInterestRate.setText(
                    if (it.annualInterestRate == 0.0) "" else formatCompactDecimal(it.annualInterestRate)
                )
                tvTypeName.text = it.type
                tvTypeName.setTextColor(brandColor)
                selectedType = it.type
                selectedIcon = AssetIconDefaults.withDefault(it.icon)
                selectedCurrency = it.currency
                refreshCurrencyDisplay()
                swIncludeNet.isChecked = if (it.isArchived) it.includeInNetBeforeArchive else it.includeInNetAsset
                selectedAssetCategory = it.assetCategory
                originalSortOrder = it.sortOrder
                originalPickerSortOrder = it.pickerSortOrder
                updateAssetCategoryUI()

                // P1-5: 加载信用卡字段
                if (it.creditLimit > 0) etCreditLimit.setText(formatCompactDecimal(it.creditLimit))
                selectedStatementDay = if (it.statementDay > 0) it.statementDay else 0
                selectedDueDay = when {
                    it.dueDay > 0 -> it.dueDay
                    it.billingDay > 0 -> it.billingDay
                    else -> 0
                }
                tvStatementDay.text = com.taostudio.tapaccounting.ui.common.DayPickerDialog.formatDay(selectedStatementDay)
                tvDueDay.text = com.taostudio.tapaccounting.ui.common.DayPickerDialog.formatDay(selectedDueDay)

                Glide.with(this@AddAssetActivity)
                    .load(AssetIconDefaults.withDefault(it.icon))
                    .transform(CircleCrop())
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_placeholder)
                    .error(R.drawable.ic_placeholder)
                    .into(ivTypeIcon)

                maybeOpenForcedInvestmentLotSplit(it)
            }
        }
    }

    private fun maybeOpenForcedInvestmentLotSplit(asset: Asset) {
        if (forceInvestmentLotSplitHandled) return
        if (!intent.getBooleanExtra(EXTRA_FORCE_INVESTMENT_LOT_SPLIT, false)) return
        if (asset.assetCategory != Asset.CATEGORY_INVESTMENT || asset.balance <= 0.0) return
        forceInvestmentLotSplitHandled = true

        InvestmentLotSplitDialog.show(
            activity = this,
            title = getString(R.string.investment_lot_prompt_title),
            message = getString(R.string.investment_principal_hint),
            totalAmount = asset.balance,
            currency = asset.currency,
            annualInterestRate = asset.annualInterestRate,
            initialDrafts = InvestmentLotDraftStorage.load(this, asset.id),
            onLater = { drafts ->
                InvestmentLotEntryHelper.saveDrafts(this, asset.id, drafts)
            },
            onConfirm = { lots ->
                lifecycleScope.launch(Dispatchers.IO) {
                    InvestmentLotEntryHelper.persistConfirmedLots(this@AddAssetActivity, db, asset, lots)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@AddAssetActivity,
                            getString(R.string.investment_lot_saved),
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            }
        )
    }

    private fun saveAsset(
        skipCurrencyConfirm: Boolean = false,
        investmentLots: List<InvestmentLotDraft>? = null,
        investmentDraftLots: List<InvestmentLotDraft> = emptyList()
    ) {
        val name = etName.text.toString().trim()
        val balance = BillAssetImpactService.roundMoney(parseLocalizedAmount(etBalance.text.toString()))
        val annualInterestRate = if (selectedAssetCategory == Asset.CATEGORY_INVESTMENT) {
            parseLocalizedAmount(etAnnualInterestRate.text.toString())
        } else {
            0.0
        }

        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.input_account_name), Toast.LENGTH_SHORT).show()
            return
        }
        if (!InvestmentInterestService.isValidAnnualRate(annualInterestRate)) {
            etAnnualInterestRate.error = "请输入大于 -100 且不超过 10000 的年利率"
            etAnnualInterestRate.requestFocus()
            return
        }

        lifecycleScope.launch {
            val existing = db.assetDao().getAssetByName(name)
            val isDuplicate = when {
                assetId == -1L -> existing != null
                else -> existing != null && existing.id != assetId
            }
            if (isDuplicate) {
                withContext(Dispatchers.Main) {
                    etName.error = getString(R.string.name_already_used)
                    etName.requestFocus()
                }
                return@launch
            }

            val existingAsset = if (assetId != -1L) db.assetDao().getAssetById(assetId) else null
            val oldBalance = existingAsset?.balance ?: 0.0
            val oldName = existingAsset?.name.orEmpty()
            val oldCurrency = existingAsset?.currency ?: selectedCurrency
            val currencyChanged = assetId != -1L && !oldCurrency.equals(selectedCurrency, ignoreCase = true)

            val isZeroBalance = abs(balance) <= 0.000001
            if (currencyChanged && !balanceEditedByUser && !isZeroBalance) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AddAssetActivity,
                        getString(R.string.currency_changed_confirm_balance),
                        Toast.LENGTH_SHORT
                    ).show()
                    etBalance.requestFocus()
                    etBalance.selectAll()
                }
                return@launch
            }

            if (currencyChanged && !skipCurrencyConfirm) {
                withContext(Dispatchers.Main) {
                    showCurrencyChangeConfirmDialog(oldCurrency, selectedCurrency) {
                        saveAsset(skipCurrencyConfirm = true)
                    }
                }
                return@launch
            }

            if (selectedAssetCategory == Asset.CATEGORY_INVESTMENT) {
                InvestmentInterestService.ensureInvestmentCategories(db)
            }

            val currentBalance = BillAssetImpactService.roundMoney(balance)
            val previousBalance = BillAssetImpactService.roundMoney(oldBalance)
            val shouldCreateOpeningLot = selectedAssetCategory == Asset.CATEGORY_INVESTMENT &&
                currentBalance > 0.000001 &&
                investmentLots == null &&
                (
                    assetId == -1L ||
                        existingAsset?.assetCategory != Asset.CATEGORY_INVESTMENT ||
                        intent.getBooleanExtra(EXTRA_FORCE_INVESTMENT_LOT_SPLIT, false)
                )
            if (shouldCreateOpeningLot) {
                withContext(Dispatchers.Main) {
                    InvestmentLotSplitDialog.show(
                        activity = this@AddAssetActivity,
                        title = if (assetId == -1L) getString(R.string.set_initial_principal_time) else getString(R.string.set_convert_to_investment_time),
                        message = getString(R.string.investment_principal_hint),
                        totalAmount = currentBalance,
                        currency = selectedCurrency,
                        annualInterestRate = annualInterestRate,
                        initialDrafts = if (assetId != -1L) InvestmentLotDraftStorage.load(this@AddAssetActivity, assetId) else emptyList(),
                        onLater = { drafts ->
                            saveAsset(
                                skipCurrencyConfirm = skipCurrencyConfirm,
                                investmentLots = emptyList(),
                                investmentDraftLots = drafts
                            )
                        },
                        onConfirm = { lots ->
                            saveAsset(
                                skipCurrencyConfirm = skipCurrencyConfirm,
                                investmentLots = lots
                            )
                        }
                    )
                }
                return@launch
            }

            val existingLastSettledAt = existingAsset?.interestLastSettledAt ?: System.currentTimeMillis()
            val interestLastSettledAt = when {
                selectedAssetCategory != Asset.CATEGORY_INVESTMENT -> System.currentTimeMillis()
                existingAsset?.assetCategory == Asset.CATEGORY_INVESTMENT -> existingLastSettledAt
                else -> System.currentTimeMillis()
            }

            val createTime = existingAsset?.createTime ?: System.currentTimeMillis()
            val balanceFromTime = when {
                existingAsset != null && existingAsset.billBalanceFromTime > 0L -> existingAsset.billBalanceFromTime
                else -> createTime
            }
            val isArchived = existingAsset?.isArchived ?: false
            val includeNetPreference = swIncludeNet.isChecked
            val asset = Asset(
                id = if (assetId == -1L) 0 else assetId,
                name = name,
                type = if (selectedType.isEmpty()) "其它" else selectedType,
                balance = currentBalance,
                initialBalance = if (assetId == -1L) currentBalance else (existingAsset?.initialBalance ?: 0.0),
                currency = selectedCurrency,
                icon = AssetIconDefaults.withDefault(selectedIcon),
                remark = etRemark.text.toString(),
                includeInNetAsset = if (isArchived) false else includeNetPreference,
                createTime = createTime,
                showBillBalanceAfter = existingAsset?.showBillBalanceAfter ?: true,
                billBalanceFromTime = balanceFromTime,
                assetCategory = selectedAssetCategory,
                annualInterestRate = annualInterestRate,
                interestLastSettledAt = interestLastSettledAt,
                sortOrder = if (assetId == -1L) 0 else originalSortOrder,
                pickerSortOrder = if (assetId == -1L) 0 else originalPickerSortOrder,
                isArchived = isArchived,
                includeInNetBeforeArchive = includeNetPreference,
                // P1-5: 信用卡字段
                creditLimit = if (selectedAssetCategory == Asset.CATEGORY_CREDIT_CARD) {
                    parseLocalizedAmount(etCreditLimit.text.toString())
                } else {
                    0.0
                },
                statementDay = if (selectedAssetCategory == Asset.CATEGORY_CREDIT_CARD) selectedStatementDay else 0,
                dueDay = if (selectedAssetCategory == Asset.CATEGORY_CREDIT_CARD) selectedDueDay else 0
            )

            if (assetId == -1L) {
                persistAssetChange(
                    pending = PendingAssetSave(
                        asset = asset,
                        oldName = "",
                        oldBalance = 0.0,
                        oldCurrency = selectedCurrency,
                        isNew = true,
                        investmentLots = investmentLots.orEmpty(),
                        investmentDraftLots = investmentDraftLots
                    ),
                    adjustment = null
                )
                finish()
            } else {
                if (currencyChanged || abs(currentBalance - previousBalance) > 0.000001) {
                    pendingAssetSave = PendingAssetSave(
                        asset = asset,
                        oldName = oldName,
                        oldBalance = previousBalance,
                        oldCurrency = oldCurrency,
                        isNew = false,
                        investmentLots = investmentLots.orEmpty(),
                        investmentDraftLots = investmentDraftLots
                    )
                    val intent = Intent(this@AddAssetActivity, BalanceAdjustmentActivity::class.java)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_ASSET_ID, assetId)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_OLD_BALANCE, previousBalance)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_NEW_BALANCE, currentBalance)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_ASSET_NAME, name)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_OLD_CURRENCY, oldCurrency)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_CURRENCY, selectedCurrency)
                    balanceAdjustmentLauncher.launch(intent)
                } else {
                    persistAssetChange(
                        pending = PendingAssetSave(
                            asset = asset,
                            oldName = oldName,
                            oldBalance = previousBalance,
                            oldCurrency = oldCurrency,
                            isNew = false,
                            investmentLots = investmentLots.orEmpty(),
                            investmentDraftLots = investmentDraftLots
                        ),
                        adjustment = null
                    )
                    finish()
                }
            }
        }
    }

    private suspend fun persistAssetChange(
        pending: PendingAssetSave,
        adjustment: AdjustmentDraft?
    ) {
        db.withTransaction {
            val hasAdjustment = adjustment != null
            val currencyChanged = hasAdjustment && !pending.oldCurrency.equals(pending.asset.currency, ignoreCase = true)

            // 根据场景决定基线余额：
            // - 无平账记录：直接保存目标余额
            // - 普通余额调整（同币种）：基线余额 = oldBalance，账单负责调整差额
            // - 换币平账：基线余额 = 0.0，账单负责将余额增加/减少到目标值
            val baselineBalance = when {
                !hasAdjustment -> pending.asset.balance
                currencyChanged -> 0.0
                else -> pending.oldBalance
            }

            val savedAssetId = if (pending.isNew) {
                val maxOrder = db.assetDao().getMaxSortOrderInCategory(pending.asset.assetCategory) ?: 0
                val maxPickerOrder = db.assetDao().getMaxPickerSortOrder() ?: 0
                db.assetDao().insertAsset(
                    pending.asset.copy(
                        balance = baselineBalance,
                        sortOrder = maxOrder + 10,
                        pickerSortOrder = maxPickerOrder + 10
                    )
                )
            } else {
                if (hasAdjustment) {
                    // 有平账记录时：更新非余额字段 + 基线余额
                    db.assetDao().updateAssetInfo(
                        id = pending.asset.id,
                        name = pending.asset.name,
                        type = pending.asset.type,
                        initialBalance = pending.asset.initialBalance,
                        currency = pending.asset.currency,
                        icon = pending.asset.icon,
                        remark = pending.asset.remark,
                        includeInNetAsset = pending.asset.includeInNetAsset,
                        sortOrder = pending.asset.sortOrder,
                        pickerSortOrder = pending.asset.pickerSortOrder,
                        createTime = pending.asset.createTime,
                        assetCategory = pending.asset.assetCategory,
                        creditLimit = pending.asset.creditLimit,
                        billingDay = pending.asset.billingDay,
                        statementDay = pending.asset.statementDay,
                        dueDay = pending.asset.dueDay,
                        annualInterestRate = pending.asset.annualInterestRate,
                        interestLastSettledAt = pending.asset.interestLastSettledAt,
                        isArchived = pending.asset.isArchived,
                        includeInNetBeforeArchive = pending.asset.includeInNetBeforeArchive
                    )
                    // 更新基线余额
                    db.assetDao().updateBalance(pending.asset.id, baselineBalance)
                } else {
                    db.assetDao().updateAsset(pending.asset)
                }
                if (pending.oldName.isNotEmpty() && pending.oldName != pending.asset.name) {
                    db.billDao().bindAccountIdByLegacyName(pending.asset.id, pending.oldName)
                    db.billDao().bindToAccountIdByLegacyName(pending.asset.id, pending.oldName)
                    db.billDao().syncAccountNameByAssetId(pending.asset.id, pending.asset.name)
                    db.billDao().syncToAccountNameByAssetId(pending.asset.id, pending.asset.name)
                }
                pending.asset.id
            }

            // 投资资产的初始份额按用户保存后的目标余额创建；有平账记录时，账单随后会把资产余额调整到同一数值。
            pending.investmentLots.forEach { lot ->
                val assetForLot = pending.asset.copy(id = savedAssetId, balance = lot.amount)
                InvestmentInterestService.createLotForAssetBalance(
                    db = db,
                    asset = assetForLot,
                    schedule = lot.schedule
                )
            }
            when {
                pending.investmentLots.isNotEmpty() -> InvestmentLotDraftStorage.clear(this@AddAssetActivity, savedAssetId)
                pending.investmentDraftLots.isNotEmpty() -> InvestmentLotEntryHelper.saveDrafts(
                    this@AddAssetActivity,
                    savedAssetId,
                    pending.investmentDraftLots
                )
            }

            if (adjustment != null) {
                val diff = BillAssetImpactService.roundMoney(pending.asset.balance - baselineBalance)
                val excludeFromStats = currencyChanged || !adjustment.includeInStats

                // 计算账单金额和类型：
                // - 换币平账：基线为 0，金额为目标余额绝对值，类型按目标余额正负决定
                // - 普通调整：金额为差额绝对值，类型按差额正负决定
                val billAmount: Double
                val billType: Int
                if (currencyChanged) {
                    billAmount = BillAssetImpactService.roundMoney(abs(pending.asset.balance))
                    billType = if (pending.asset.balance >= 0) Bill.TYPE_INCOME else Bill.TYPE_EXPENSE
                } else {
                    billAmount = BillAssetImpactService.roundMoney(abs(diff))
                    billType = if (diff >= 0) Bill.TYPE_INCOME else Bill.TYPE_EXPENSE
                }

                val bill = Bill(
                    type = billType,
                    subType = Bill.SUBTYPE_NORMAL,
                    amount = billAmount,
                    currency = pending.asset.currency,
                    accountId = savedAssetId,
                    accountName = pending.asset.name,
                    time = System.currentTimeMillis(),
                    remark = adjustment.remark,
                    categoryName = adjustment.categoryName.ifBlank { "其它" },
                    bookName = BookAccountManager.getSelectedBook(this@AddAssetActivity),
                    excludeFromStats = excludeFromStats,
                )
                BillMutationService.insertBillAndApplyImpact(
                    db = db,
                    bill = bill,
                    applyAssetImpact = true
                )
            } else if (pending.asset.assetCategory == Asset.CATEGORY_INVESTMENT &&
                pending.investmentDraftLots.isEmpty()
            ) {
                db.assetDao().getAssetById(savedAssetId)?.let { latestAsset ->
                    InvestmentInterestService.reconcileAssetLotsToBalance(
                        db = db,
                        asset = latestAsset
                    )
                }
            }

            // “稍后再写”必须真的保持未配置，不能被平账或保存后的自动对账偷偷生成默认批次。
            if (pending.investmentDraftLots.isNotEmpty() && pending.investmentLots.isEmpty()) {
                db.investmentLotDao().deleteByAssetId(savedAssetId)
            }

            if (pending.asset.assetCategory != Asset.CATEGORY_INVESTMENT) {
                db.investmentLotDao().deleteByAssetId(savedAssetId)
            }
        }
    }

    private fun showCurrencyDialog() {
        val currencies = CurrencyManager.getEnabledCurrencies(this)
        if (currencies.isEmpty()) return

        val selected = selectedCurrency.trim().uppercase(Locale.ROOT)
        val selectedIndex = currencies.indexOfFirst { it.trim().uppercase(Locale.ROOT) == selected }
            .takeIf { it >= 0 } ?: 0

        val options = currencies.map { code ->
            val normalized = code.trim().uppercase(Locale.ROOT)
            val info = CurrencyData.getInfo(normalized)
            if (info != null) {
                DialogOption(
                    title = "${info.symbol} ${info.code}",
                    subtitle = "${info.nameZh} (${info.countryZh})"
                )
            } else {
                DialogOption(title = normalized)
            }
        }

        showOptionPickerDialog(
            title = getString(R.string.select_currency),
            options = options,
            selectedIndex = selectedIndex,
            widthRatio = 0.9f
        ) { which ->
            selectedCurrency = currencies[which]
            refreshCurrencyDisplay()
        }
    }

    private fun refreshCurrencyDisplay() {
        tvCurrency.text = formatCurrencyDisplay(selectedCurrency)
    }

    private fun formatCurrencyDisplay(code: String): String {
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (normalized.isBlank()) return code

        val info = CurrencyData.getInfo(normalized)
        val symbol = info?.symbol?.takeIf { it.isNotBlank() } ?: normalized
        return "$symbol $normalized"
    }

    private fun showAssetCategoryDialog() {
        val categories = arrayOf(
            Asset.CATEGORY_FUND,
            Asset.CATEGORY_CREDIT_CARD,
            Asset.CATEGORY_RECHARGE,
            Asset.CATEGORY_INVESTMENT
        )
        val options = listOf(
            DialogOption(title = getString(R.string.asset_fund_title), subtitle = getString(R.string.asset_fund_subtitle)),
            DialogOption(title = getString(R.string.asset_credit_card), subtitle = getString(R.string.asset_credit_subtitle)),
            DialogOption(title = getString(R.string.asset_recharge_title), subtitle = getString(R.string.asset_recharge_subtitle)),
            DialogOption(title = getString(R.string.asset_investment_title), subtitle = getString(R.string.asset_investment_subtitle))
        )
        val currentIndex = categories.indexOf(selectedAssetCategory).takeIf { it >= 0 } ?: 0
        showOptionPickerDialog(
            title = getString(R.string.select_asset_category),
            options = options,
            selectedIndex = currentIndex,
            widthRatio = 0.9f
        ) { which ->
            selectedAssetCategory = categories[which]
            updateAssetCategoryUI()
        }
    }

    private fun updateAssetCategoryUI() {
        tvAssetCategory.text = Asset.categoryLabel(selectedAssetCategory)
        layoutAnnualInterestRate.visibility =
            if (selectedAssetCategory == Asset.CATEGORY_INVESTMENT) View.VISIBLE else View.GONE

        // P1-5: 信用卡字段可见性
        val isCreditCard = selectedAssetCategory == Asset.CATEGORY_CREDIT_CARD
        layoutCreditLimit.visibility = if (isCreditCard) View.VISIBLE else View.GONE
        layoutCreditCycle.visibility = if (isCreditCard) View.VISIBLE else View.GONE
        tvAssetCategory.setTextColor(brandColor)
    }

    private fun loadAssetsJson(): List<BuiltInCategory> {
        val list = mutableListOf<BuiltInCategory>()
        try {
            val inputStream = resources.openRawResource(R.raw.assets)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonStr = reader.use { it.readText() }
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BuiltInCategory(
                        name = obj.getString("name"),
                        icon = obj.getString("icon"),
                        type = obj.optString("type")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
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

    private fun showCurrencyChangeConfirmDialog(
        oldCurrency: String,
        newCurrency: String,
        onConfirm: () -> Unit
    ) {
        val themeContext = ContextThemeWrapper(this, R.style.Theme_TapAccounting)
        val dialog = AlertDialog.Builder(themeContext)
            .setTitle(R.string.confirm_change_currency_title)
            .setMessage(getString(R.string.confirm_change_currency_message, oldCurrency, newCurrency))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ -> onConfirm() }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class DialogOption(
        val title: String,
        val subtitle: String = ""
    )

    private fun showOptionPickerDialog(
        title: String,
        options: List<DialogOption>,
        selectedIndex: Int,
        widthRatio: Float,
        onSelected: (Int) -> Unit
    ) {
        val themeContext = ContextThemeWrapper(this, R.style.Theme_TapAccounting)
        val contentView = LayoutInflater.from(themeContext).inflate(R.layout.dialog_option_picker, null)
        contentView.findViewById<TextView>(R.id.tv_option_picker_title).text = title
        contentView.findViewById<TextView>(R.id.tv_option_picker_desc).visibility = View.GONE
        contentView.findViewById<TextView>(R.id.btn_option_picker_cancel).visibility = View.GONE

        val listView = contentView.findViewById<ListView>(R.id.lv_option_picker)
        val adapter = OptionPickerAdapter(options, selectedIndex)
        listView.adapter = adapter
        listView.divider = ColorDrawable(Color.parseColor("#12000000"))
        listView.dividerHeight = 1
        adjustOptionListHeight(listView, options.size)

        val dialog = AlertDialog.Builder(themeContext)
            .setView(contentView)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in options.indices) {
                adapter.selectedIndex = position
                adapter.notifyDataSetChanged()
                onSelected(position)
                dialog.dismiss()
            }
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = widthRatio,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private fun adjustOptionListHeight(listView: ListView, itemCount: Int) {
        val density = resources.displayMetrics.density
        val itemHeightPx = (64f * density).toInt()
        val maxHeightPx = (320f * density).toInt()
        val minHeightPx = (136f * density).toInt()
        val targetHeight = (itemHeightPx * itemCount).coerceAtMost(maxHeightPx).coerceAtLeast(minHeightPx)
        listView.layoutParams = listView.layoutParams.apply { height = targetHeight }
    }

    private inner class OptionPickerAdapter(
        private val options: List<DialogOption>,
        var selectedIndex: Int
    ) : BaseAdapter() {
        override fun getCount(): Int = options.size

        override fun getItem(position: Int): DialogOption = options[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dialog_option_picker, parent, false)
            val titleView = view.findViewById<TextView>(R.id.tv_option_title)
            val subtitleView = view.findViewById<TextView>(R.id.tv_option_subtitle)
            val checkView = view.findViewById<TextView>(R.id.tv_option_check)
            val riskView = view.findViewById<TextView>(R.id.tv_option_risk)
            val arrowView = view.findViewById<ImageView>(R.id.iv_option_arrow)

            val option = getItem(position)
            val isSelected = position == selectedIndex

            titleView.text = option.title
            titleView.setTextColor(if (isSelected) brandColor else textPrimaryColor)
            subtitleView.text = option.subtitle
            subtitleView.visibility = if (option.subtitle.isBlank()) View.GONE else View.VISIBLE
            checkView.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            riskView.visibility = View.GONE
            arrowView.visibility = View.GONE
            view.setBackgroundColor(
                if (isSelected) ColorUtils.setAlphaComponent(brandColor, 15) else Color.TRANSPARENT
            )
            return view
        }
    }

    inner class IconPickerAdapter(
        private var icons: List<BuiltInCategory>,
        private val onSelect: (BuiltInCategory) -> Unit
    ) : RecyclerView.Adapter<IconPickerAdapter.VH>() {

        fun updateData(newData: List<BuiltInCategory>) {
            icons = newData
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.iv_asset_icon)
            val tv: TextView = v.findViewById(R.id.tv_asset_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_asset_grid, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = icons[position]
            holder.tv.text = item.name
            Glide.with(holder.itemView)
                .load(AssetIconDefaults.withDefault(item.icon))
                .transform(CircleCrop())
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .into(holder.iv)
            holder.itemView.setOnClickListener { onSelect(item) }
        }

        override fun getItemCount(): Int = icons.size
    }

    companion object {
        const val EXTRA_FORCE_INVESTMENT_LOT_SPLIT = "FORCE_INVESTMENT_LOT_SPLIT"
        private const val PREFS_ASSET_UI = "asset_ui_prefs"
        private const val KEY_SKIP_NET_ASSET_TIP_SHOWN = "skip_net_asset_tip_shown"
    }
}
