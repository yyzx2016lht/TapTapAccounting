package com.taostudio.tapaccounting.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.logic.BillDeleteHelper
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.ui.dialog.AmountKeypadDialog
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class BillDetailActivity : AppCompatActivity() {

    private lateinit var tvCategory: TextView
    private lateinit var tvCategoryInitial: TextView
    private lateinit var ivCategoryIcon: ImageView
    private lateinit var layoutCategoryIcon: View
    private lateinit var tvAmount: TextView
    private lateinit var tvAmountLabel: TextView
    private lateinit var tvAmountFormula: TextView
    private lateinit var dividerFeeTop: View
    private lateinit var layoutFee: LinearLayout
    private lateinit var tvFee: TextView
    private lateinit var dividerIncomingTop: View
    private lateinit var layoutIncoming: LinearLayout
    private lateinit var tvIncomingAmount: TextView
    private lateinit var tvBillDate: TextView
    private lateinit var tvAsset: TextView
    private lateinit var tvBook: TextView
    private lateinit var tvRecordTime: TextView
    private lateinit var etRemark: EditText
    private lateinit var switchExcludeStats: SwitchCompat
    private lateinit var btnSave: TextView
    private lateinit var btnTypePrimary: TextView
    private lateinit var btnTypeSecondary: TextView
    private lateinit var btnRefund: TextView
    private lateinit var layoutAsset: LinearLayout
    private lateinit var dividerAssetTop: View
    private lateinit var dividerBookTop: View
    private lateinit var layoutRefundRecordsSection: LinearLayout
    private lateinit var layoutRefundRecordsContainer: LinearLayout
    private lateinit var layoutOriginalBillSection: LinearLayout
    private lateinit var layoutOriginalBillContainer: LinearLayout

    private var billId: Long = -1L
    private var bill: Bill? = null

    private var currentUiType: Int = Bill.TYPE_EXPENSE
    private var currentCategoryName: String = ""
    private var currentCategoryIcon: String = ""
    private var currentAmountText: String = "0.00"
    private var currentAssetName: String = ""
    private var currentBookName: String = ""
    private var currentExcludeFromStats: Boolean = false
    private var currentBillTimeMillis: Long = System.currentTimeMillis()
    private var originalRecordedTimeMillis: Long = System.currentTimeMillis()

    private var iconLoadJob: Job? = null

    private val detailPickerFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val billDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val recordTimeFormat = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_detail)

        billId = intent.getLongExtra(BILL_ID, -1L)
        if (billId == -1L) {
            Toast.makeText(this, getString(R.string.bill_not_exist), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        loadBillData()
    }

    private fun initViews() {
        tvCategory = findViewById(R.id.tv_category)
        tvCategoryInitial = findViewById(R.id.tv_category_initial)
        ivCategoryIcon = findViewById(R.id.iv_category_icon)
        layoutCategoryIcon = findViewById(R.id.layout_category_icon)
        tvAmount = findViewById(R.id.tv_amount)
        tvAmountLabel = findViewById(R.id.tv_amount_label)
        tvAmountFormula = findViewById(R.id.tv_amount_formula)
        dividerFeeTop = findViewById(R.id.divider_fee_top)
        layoutFee = findViewById(R.id.layout_fee)
        tvFee = findViewById(R.id.tv_fee)
        dividerIncomingTop = findViewById(R.id.divider_incoming_top)
        layoutIncoming = findViewById(R.id.layout_incoming)
        tvIncomingAmount = findViewById(R.id.tv_incoming_amount)
        tvBillDate = findViewById(R.id.tv_bill_date)
        tvAsset = findViewById(R.id.tv_asset)
        tvBook = findViewById(R.id.tv_book)
        tvRecordTime = findViewById(R.id.tv_record_time)
        etRemark = findViewById(R.id.et_remark)
        switchExcludeStats = findViewById(R.id.switch_exclude_stats)
        btnSave = findViewById(R.id.btn_save)
        btnTypePrimary = findViewById(R.id.btn_type_primary)
        btnTypeSecondary = findViewById(R.id.btn_type_secondary)
        btnRefund = findViewById(R.id.btn_refund)
        layoutAsset = findViewById(R.id.layout_asset)
        dividerAssetTop = findViewById(R.id.divider_asset_top)
        dividerBookTop = findViewById(R.id.divider_book_top)
        layoutRefundRecordsSection = findViewById(R.id.layout_refund_records_section)
        layoutRefundRecordsContainer = findViewById(R.id.layout_refund_records_container)
        layoutOriginalBillSection = findViewById(R.id.layout_original_bill_section)
        layoutOriginalBillContainer = findViewById(R.id.layout_original_bill_container)

        val assetEnabled = Prefs.isAssetFeatureEnabled(this)
        layoutAsset.visibility = if (assetEnabled) View.VISIBLE else View.GONE
        dividerAssetTop.visibility = if (assetEnabled) View.VISIBLE else View.GONE
        dividerBookTop.visibility = View.VISIBLE
    }

    private fun setupListeners() {
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        btnSave.setOnClickListener { saveBill() }

        findViewById<View>(R.id.layout_category).setOnClickListener { showCategoryPicker() }
        findViewById<View>(R.id.layout_amount).setOnClickListener { showAmountKeypad() }
        findViewById<View>(R.id.layout_bill_date).setOnClickListener { showBillDatePicker() }
        findViewById<View>(R.id.layout_book).setOnClickListener { showBookPicker() }
        layoutAsset.setOnClickListener { showAssetPicker() }
        findViewById<View>(R.id.btn_delete).setOnClickListener { showDeleteConfirmation() }
        btnRefund.setOnClickListener { openRefundPage() }

        switchExcludeStats.setOnCheckedChangeListener { _, isChecked ->
            currentExcludeFromStats = isChecked
        }

        btnTypePrimary.setOnClickListener { onTypeChipClicked(isPrimary = true) }
        btnTypeSecondary.setOnClickListener { onTypeChipClicked(isPrimary = false) }
    }

    private fun loadBillData() {
        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val loadedBill = app.billRepository.getBillById(billId)
            withContext(Dispatchers.Main) {
                if (loadedBill == null) {
                    Toast.makeText(this@BillDetailActivity, getString(R.string.bill_not_exist), Toast.LENGTH_SHORT).show()
                    finish()
                    return@withContext
                }
                bindBill(loadedBill)
            }
        }
    }

    private fun bindBill(loadedBill: Bill) {
        bill = loadedBill
        currentUiType = resolveUiType(loadedBill)
        currentAmountText = formatEditableAmount(loadedBill.amount)
        currentBillTimeMillis = loadedBill.time
        originalRecordedTimeMillis = loadedBill.time
        currentAssetName = loadedBill.accountName
        currentBookName = loadedBill.bookName.ifBlank { getString(R.string.default_book) }
        currentExcludeFromStats = loadedBill.excludeFromStats
        currentCategoryName = resolveDisplayedCategory(loadedBill)
        currentCategoryIcon = ""

        etRemark.setText(loadedBill.remark)
        switchExcludeStats.isChecked = loadedBill.excludeFromStats

        renderBillState()
        loadCategoryIcon()
    }

    private fun renderBillState() {
        tvCategory.text = currentCategoryName.ifBlank { if (isTransferFamily(currentUiType)) getTypeDisplayName(currentUiType) else getString(R.string.select_category) }
        tvCategoryInitial.text = buildCategoryInitial(tvCategory.text.toString())
        tvAmount.text = formatAmountDisplay(currentAmountText.toDoubleOrNull() ?: 0.0, currentUiType)
        tvAmount.setTextColor(resolveAmountColor(currentUiType))
        tvBillDate.text = billDateFormat.format(Date(currentBillTimeMillis))
        tvRecordTime.text = recordTimeFormat.format(Date(originalRecordedTimeMillis))
        tvAsset.text = currentAssetName.ifBlank { getString(R.string.not_selected) }
        tvBook.text = currentBookName.ifBlank { getString(R.string.default_book) }
        btnRefund.visibility = if (shouldShowRefundButton()) View.VISIBLE else View.GONE
        renderTypeSelector()
        renderCategoryIcon()
        renderAmountDetails()
        renderRefundRecords()
        renderOriginalBill()
    }

    private fun renderAmountDetails() {
        val currentBill = bill
        if (currentBill == null) {
            tvAmountLabel.text = getString(R.string.bill_amount)
            tvAmountFormula.visibility = View.GONE
            hideFeeRow()
            hideIncomingRow()
            return
        }

        val isRefund = currentBill.subType == Bill.SUBTYPE_REFUND

        if (isTransferFamily(currentUiType)) {
            renderTransferDetails(currentBill)
        } else {
            tvAmountLabel.text = getString(R.string.bill_amount)
            hideFeeRow()
            hideIncomingRow()

            if (!isRefund && currentBill.type == Bill.TYPE_EXPENSE) {
                val refundedAmount = refundedAmountInBillCurrency(currentBill)
                if (refundedAmount > 0.0) {
                    tvAmountFormula.visibility = View.VISIBLE
                    val symbol = CurrencyManager.getSymbol(currentBill.currency)
                    tvAmountFormula.text = getString(R.string.refund_deduct_formula, symbol, String.format(Locale.getDefault(), "%.2f", refundedAmount), String.format(Locale.getDefault(), "%.2f", currentBill.amount))
                } else {
                    showCrossCurrencyFormula(currentBill)
                }
            } else {
                showCrossCurrencyFormula(currentBill)
            }
        }
    }

    private fun renderTransferDetails(currentBill: Bill) {
        val isRepayment = currentUiType == Bill.TYPE_REPAYMENT

        if (currentBill.fee > 0.0 && !isRepayment) {
            showFeeRow(currentBill)
        } else {
            hideFeeRow()
        }

        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val toAsset = currentBill.toAccountId?.let { app.database.assetDao().getAssetById(it) }
            val toAssetCurrency = toAsset?.currency ?: "CNY"
            withContext(Dispatchers.Main) {
                val sourceCurrency = currentBill.currency
                val isCrossCurrency = !isRepayment && sourceCurrency != toAssetCurrency && currentBill.exchangeRate != 1.0
                val symbol = CurrencyManager.getSymbol(sourceCurrency)

                if (isCrossCurrency) {
                    tvAmountLabel.text = getString(R.string.from_amount)
                    val targetAmount = currentBill.amount * currentBill.exchangeRate
                    val toSymbol = CurrencyManager.getSymbol(toAssetCurrency)
                    showIncomingRow(toSymbol, targetAmount)
                    tvAmountFormula.visibility = View.GONE
                } else {
                    tvAmountLabel.text = when {
                        isRepayment -> getString(R.string.repayment_amount)
                        else -> getString(R.string.transfer_amount_label)
                    }
                    hideIncomingRow()
                    showCrossCurrencyFormula(currentBill)
                }
            }
        }
    }

    private fun showCrossCurrencyFormula(currentBill: Bill) {
        val formula = BillDisplayFormatter.buildCrossCurrencyDetailFormula(currentBill, "CNY")
        if (!formula.isNullOrBlank()) {
            tvAmountFormula.visibility = View.VISIBLE
            tvAmountFormula.text = formula
        } else {
            tvAmountFormula.visibility = View.GONE
        }
    }

    private fun showFeeRow(currentBill: Bill) {
        dividerFeeTop.visibility = View.VISIBLE
        layoutFee.visibility = View.VISIBLE
        val feeSymbol = CurrencyManager.getSymbol(currentBill.currency)
        tvFee.text = "-$feeSymbol${String.format(Locale.getDefault(), "%.2f", currentBill.fee)}"
    }

    private fun hideFeeRow() {
        dividerFeeTop.visibility = View.GONE
        layoutFee.visibility = View.GONE
    }

    private fun showIncomingRow(symbol: String, amount: Double) {
        dividerIncomingTop.visibility = View.VISIBLE
        layoutIncoming.visibility = View.VISIBLE
        tvIncomingAmount.text = "$symbol${String.format(Locale.getDefault(), "%.2f", amount)}"
    }

    private fun hideIncomingRow() {
        dividerIncomingTop.visibility = View.GONE
        layoutIncoming.visibility = View.GONE
    }

    private fun refundedAmountInBillCurrency(currentBill: Bill): Double {
        if (currentBill.type != Bill.TYPE_EXPENSE || currentBill.subType == Bill.SUBTYPE_REFUND) return 0.0
        val originalAmount = if (currentBill.originalAmount > 0.0) {
            maxOf(currentBill.originalAmount, currentBill.amount)
        } else {
            currentBill.amount
        }
        return (originalAmount - currentBill.amount).coerceAtLeast(0.0)
    }

    private fun renderRefundRecords() {
        val currentBill = bill
        layoutRefundRecordsSection.visibility = View.GONE
        layoutRefundRecordsContainer.removeAllViews()

        if (currentBill == null || currentBill.type != Bill.TYPE_EXPENSE || currentBill.subType == Bill.SUBTYPE_REFUND) return
        if (refundedAmountInBillCurrency(currentBill) <= 0.0) return

        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val refunds = app.database.billDao().getRefundBillsBySourceId(currentBill.id)
            withContext(Dispatchers.Main) {
                if (refunds.isEmpty()) {
                    layoutRefundRecordsSection.visibility = View.GONE
                    return@withContext
                }
                layoutRefundRecordsSection.visibility = View.VISIBLE
                refunds.forEach { refundBill ->
                    addRefundBillRow(layoutRefundRecordsContainer, refundBill)
                }
            }
        }
    }

    private fun renderOriginalBill() {
        val currentBill = bill
        layoutOriginalBillSection.visibility = View.GONE
        layoutOriginalBillContainer.removeAllViews()

        if (currentBill == null || currentBill.subType != Bill.SUBTYPE_REFUND) return

        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val original = BillMutationService.resolveRefundSourceBill(app.database, currentBill)
            withContext(Dispatchers.Main) {
                if (original != null) {
                    layoutOriginalBillSection.visibility = View.VISIBLE
                    addLinkedBillRow(layoutOriginalBillContainer, original)
                }
            }
        }
    }

    private fun addRefundBillRow(container: LinearLayout, refundBill: Bill) {
        val row = layoutInflater.inflate(R.layout.item_home_transaction, container, false)
        fillBillRow(row, refundBill, forceGrayStyle = true)
        row.findViewById<View?>(R.id.cb_bill_select)?.visibility = View.GONE
        container.addView(row)
    }

    private fun addLinkedBillRow(container: LinearLayout, linkedBill: Bill) {
        val row = layoutInflater.inflate(R.layout.item_home_transaction, container, false)
        fillBillRow(row, linkedBill, forceGrayStyle = false)
        row.findViewById<View?>(R.id.cb_bill_select)?.visibility = View.GONE
        container.addView(row)
    }

    private fun fillBillRow(row: View, bill: Bill, forceGrayStyle: Boolean) {
        val tvCategory = row.findViewById<TextView>(R.id.tv_bill_category)
        val tvDetail = row.findViewById<TextView>(R.id.tv_bill_detail)
        val tvAmountRow = row.findViewById<TextView>(R.id.tv_bill_amount)
        val tvAsset = row.findViewById<TextView>(R.id.tv_bill_asset)
        val tvTime = row.findViewById<TextView>(R.id.tv_bill_time)
        val ivIcon = row.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val iconContainer = row.findViewById<View?>(R.id.layout_icon_container)

        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        val symbol = CurrencyManager.getSymbol(bill.currency)
        val baseCategory = BillDisplayFormatter.stripRefundPrefix(bill.categoryName)

        row.setBackgroundResource(R.drawable.bg_bill_group_single)
        iconContainer?.setBackgroundResource(R.drawable.bg_circle_soft)

        val categoryText = BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, true).ifEmpty { getString(R.string.uncategorized) }

        val sign = when {
            forceGrayStyle || isRefund -> ""
            bill.type == Bill.TYPE_EXPENSE -> "-"
            bill.type == Bill.TYPE_INCOME -> "+"
            else -> ""
        }
        tvAmountRow.text = "$sign$symbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"

        if (forceGrayStyle || isRefund) {
            tvAmountRow.setTextColor(Color.parseColor("#9AA1AA"))
            tvCategory.setTextColor(Color.parseColor("#8E98A3"))
            tvDetail?.setTextColor(Color.parseColor("#A1A8AF"))
            tvTime?.setTextColor(Color.parseColor("#A1A8AF"))
            tvAsset?.setTextColor(Color.parseColor("#A1A8AF"))
        } else {
            tvCategory.setTextColor(Color.parseColor("#1A1A1A"))
            tvDetail?.setTextColor(Color.parseColor("#8A8A8E"))
            tvTime?.setTextColor(Color.parseColor("#8A8A8E"))
            tvAsset?.setTextColor(Color.parseColor("#8A8A8E"))
            when (bill.type) {
                Bill.TYPE_EXPENSE -> tvAmountRow.setTextColor(Color.parseColor("#FF3B30"))
                Bill.TYPE_INCOME -> tvAmountRow.setTextColor(Color.parseColor("#4CAF50"))
                else -> tvAmountRow.setTextColor(Color.parseColor("#5F6772"))
            }
        }

        tvCategory.text = categoryText
        tvDetail?.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(bill.time))
        tvDetail?.visibility = View.VISIBLE
        tvTime?.visibility = View.GONE
        tvAsset?.text = bill.accountName

        val iconLookupName = if (isRefund) baseCategory else bill.categoryName
        val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
        val iconTint = when {
            forceGrayStyle || isRefund -> Color.parseColor("#8E98A3")
            bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF3B30")
            bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
            else -> Color.parseColor("#9E9E9E")
        }
        ivIcon?.layoutParams = ivIcon?.layoutParams?.apply {
            val px = (ivIcon.resources.displayMetrics.density * 21).toInt()
            width = px
            height = px
        }
        ivIcon?.setImageResource(R.mipmap.ic_launcher)
        ivIcon?.setColorFilter(iconTint)
        lifecycleScope.launch(Dispatchers.IO) {
            val iconUrl = CategoryIconHelper.findCategoryIcon(this@BillDetailActivity, iconLookupName, iconLookupType)
            withContext(Dispatchers.Main) {
                if (iconUrl.isNotEmpty()) {
                    Glide.with(row)
                        .load(iconUrl)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                        .into(ivIcon!!)
                }
            }
        }
    }

    private fun renderTypeSelector() {
        val primaryType = if (isTransferFamily(currentUiType)) Bill.TYPE_TRANSFER else Bill.TYPE_EXPENSE
        val secondaryType = if (isTransferFamily(currentUiType)) Bill.TYPE_REPAYMENT else Bill.TYPE_INCOME

        btnTypePrimary.text = getTypeDisplayName(primaryType)
        btnTypeSecondary.text = getTypeDisplayName(secondaryType)

        val primarySelected = currentUiType == primaryType
        applyTypeChipStyle(btnTypePrimary, primarySelected)
        applyTypeChipStyle(btnTypeSecondary, !primarySelected)
    }

    private fun applyTypeChipStyle(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_bill_detail_type_selected else android.R.color.transparent)
        view.setTextColor(if (selected) Color.WHITE else Color.parseColor("#6E737D"))
    }

    private fun onTypeChipClicked(isPrimary: Boolean) {
        val targetType = if (isTransferFamily(currentUiType)) {
            if (isPrimary) Bill.TYPE_TRANSFER else Bill.TYPE_REPAYMENT
        } else {
            if (isPrimary) Bill.TYPE_EXPENSE else Bill.TYPE_INCOME
        }
        if (currentUiType == targetType) return

        currentUiType = targetType
        if (isTransferFamily(currentUiType)) {
            currentCategoryName = getTypeDisplayName(currentUiType)
        }
        renderBillState()
        loadCategoryIcon()
    }

    private fun showCategoryPicker() {
        if (isTransferFamily(currentUiType)) {
            Toast.makeText(this, getString(R.string.transfer_follow_bill), Toast.LENGTH_SHORT).show()
            return
        }
        val pickerType = if (currentUiType == Bill.TYPE_INCOME) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
        OverlayDialogs.showGridCategoryPicker(this, currentCategoryName, pickerType) { selectedCategory ->
            currentCategoryName = selectedCategory
            tvCategory.text = selectedCategory.ifBlank { getString(R.string.select_category) }
            tvCategoryInitial.text = buildCategoryInitial(tvCategory.text.toString())
            loadCategoryIcon()
        }
    }

    private fun showAmountKeypad() {
        AmountKeypadDialog.show(this, currentAmountText) { result ->
            currentAmountText = result
            tvAmount.text = formatAmountDisplay(result.toDoubleOrNull() ?: 0.0, currentUiType)
            tvAmount.setTextColor(resolveAmountColor(currentUiType))
            renderAmountDetails()
        }
    }

    private fun showBillDatePicker() {
        OverlayDialogs.showCustomTimePicker(this, currentBillTimeMillis) { timeString ->
            val parsed = runCatching { detailPickerFormat.parse(timeString)?.time }.getOrNull()
            if (parsed != null) {
                currentBillTimeMillis = parsed
                tvBillDate.text = billDateFormat.format(Date(currentBillTimeMillis))
            }
        }
    }

    private fun showAssetPicker() {
        if (!Prefs.isAssetFeatureEnabled(this)) return
        OverlayDialogs.showGridAssetPicker(this, currentAssetName, getString(R.string.select_asset)) { selectedAsset ->
            currentAssetName = selectedAsset
            tvAsset.text = selectedAsset.ifBlank { getString(R.string.not_selected) }
        }
    }

    private fun showBookPicker() {
        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val dbBooks = app.database.billDao().getAllBookNames()
            val books = BookAccountManager.getBookAccounts(this@BillDetailActivity, dbBooks)
            withContext(Dispatchers.Main) {
                OverlayDialogs.showBookPickerDialog(
                    this@BillDetailActivity,
                    books = books,
                    currentBook = currentBookName
                ) { selectedBook ->
                    currentBookName = selectedBook
                    tvBook.text = selectedBook
                }
            }
        }
    }

    private fun showDeleteConfirmation() {
        showCustomConfirmDialog(
            title = getString(R.string.confirm_delete),
            message = getString(R.string.confirm_delete_message),
            confirmText = getString(R.string.confirm_delete),
            isDanger = true,
            onConfirm = { deleteBill() }
        )
    }

    private fun deleteBill() {
        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val currentBill = bill ?: return@launch
            runCatching { BillDeleteHelper.deleteBillAndRevertBalance(app.database, currentBill) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BillDetailActivity, getString(R.string.bill_moved_to_trash), Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BillDetailActivity, getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun saveBill() {
        val originalBill = bill ?: return
        val amount = currentAmountText.toDoubleOrNull() ?: 0.0
        if (amount <= 0.0) {
            Toast.makeText(this, getString(R.string.invalid_amount), Toast.LENGTH_SHORT).show()
            return
        }
        // 分类允许为空（未分类是合法状态）；清空后可直接保存

        val remark = etRemark.text.toString().trim()
        val app = application as TapApplication

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val assetEnabled = Prefs.isAssetFeatureEnabled(this@BillDetailActivity)
                val persistedAssetName = if (assetEnabled) currentAssetName else originalBill.accountName
                val persistedAccountId = if (!assetEnabled) {
                    originalBill.accountId
                } else if (persistedAssetName.isNotBlank()) {
                    app.database.assetDao().getAssetByName(persistedAssetName)?.id
                } else {
                    null
                }

                val persistedCategory = persistedCategoryName(currentUiType, currentCategoryName)
                val persistedCategoryId = if (isTransferFamily(currentUiType)) {
                    originalBill.categoryId
                } else if (persistedCategory.isNotBlank()) {
                    val categoryDbType = if (currentUiType == Bill.TYPE_INCOME) 1 else 0
                    CategoryRepository(app.database.categoryDao())
                        .findCategoryByDisplayName(categoryDbType, persistedCategory)?.id
                } else {
                    null
                }

                val updatedBill = originalBill.copy(
                    type = persistedType(currentUiType),
                    subType = persistedSubType(originalBill, currentUiType),
                    amount = amount,
                    categoryName = persistedCategory,
                    categoryId = persistedCategoryId,
                    accountName = persistedAssetName,
                    accountId = persistedAccountId,
                    bookName = currentBookName.ifBlank { originalBill.bookName },
                    remark = remark,
                    excludeFromStats = currentExcludeFromStats,
                    time = currentBillTimeMillis
                )

                BillMutationService.replaceBill(
                    db = app.database,
                    oldBill = originalBill,
                    newBill = updatedBill
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillDetailActivity, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillDetailActivity, getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadCategoryIcon() {
        iconLoadJob?.cancel()
        currentCategoryIcon = ""
        renderCategoryIcon()
        iconLoadJob = lifecycleScope.launch(Dispatchers.IO) {
            val lookupName = currentCategoryName.ifBlank { getTypeDisplayName(currentUiType) }
            val lookupType = if (currentUiType == Bill.TYPE_INCOME) Bill.TYPE_INCOME else Bill.TYPE_EXPENSE
            val icon = when {
                currentUiType == Bill.TYPE_TRANSFER || currentUiType == Bill.TYPE_REPAYMENT ->
                    "android.resource://$packageName/${R.drawable.ic_transfer}"
                lookupName.isBlank() -> ""
                else -> CategoryIconHelper.findCategoryIcon(this@BillDetailActivity, lookupName, lookupType)
            }
            withContext(Dispatchers.Main) {
                currentCategoryIcon = icon
                renderCategoryIcon()
            }
        }
    }

    private fun renderCategoryIcon() {
        layoutCategoryIcon.setBackgroundResource(
            when (currentUiType) {
                Bill.TYPE_EXPENSE -> R.drawable.bg_circle_expense_soft
                Bill.TYPE_INCOME -> R.drawable.bg_circle_income_soft
                else -> R.drawable.bg_circle_soft
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ivCategoryIcon.imageTintList = null
        }
        ivCategoryIcon.setColorFilter(resolveIconTintColor(currentUiType))
        tvCategoryInitial.setTextColor(
            when (currentUiType) {
                Bill.TYPE_EXPENSE -> Color.parseColor("#D32F2F")
                Bill.TYPE_INCOME -> Color.parseColor("#2E7D32")
                else -> Color.parseColor("#6F7A8A")
            }
        )
        if (currentCategoryIcon.isBlank()) {
            ivCategoryIcon.setImageDrawable(null)
            tvCategoryInitial.visibility = View.VISIBLE
            return
        }
        tvCategoryInitial.visibility = View.INVISIBLE
        Glide.with(this)
            .load(currentCategoryIcon)
            .into(ivCategoryIcon)
    }

    private fun resolveUiType(bill: Bill): Int {
        return when {
            bill.type == Bill.TYPE_TRANSFER && bill.subType == Bill.SUBTYPE_REPAYMENT -> Bill.TYPE_REPAYMENT
            bill.type == Bill.TYPE_REPAYMENT -> Bill.TYPE_REPAYMENT
            else -> bill.type
        }
    }

    private fun resolveDisplayedCategory(bill: Bill): String {
        return if (isTransferFamily(resolveUiType(bill))) {
            getTypeDisplayName(resolveUiType(bill))
        } else {
            bill.categoryName
        }
    }

    private fun persistedType(uiType: Int): Int {
        return if (uiType == Bill.TYPE_REPAYMENT) Bill.TYPE_TRANSFER else uiType
    }

    private fun persistedSubType(originalBill: Bill, uiType: Int): Int {
        return when (uiType) {
            Bill.TYPE_REPAYMENT -> Bill.SUBTYPE_REPAYMENT
            Bill.TYPE_TRANSFER -> if (originalBill.subType == Bill.SUBTYPE_REPAYMENT) Bill.SUBTYPE_NORMAL else originalBill.subType
            else -> if (originalBill.subType == Bill.SUBTYPE_REPAYMENT) Bill.SUBTYPE_NORMAL else originalBill.subType
        }
    }

    private fun persistedCategoryName(uiType: Int, currentCategory: String): String {
        return when (uiType) {
            Bill.TYPE_TRANSFER -> getString(R.string.transfer_label)
            Bill.TYPE_REPAYMENT -> getString(R.string.repayment_label)
            // 空分类表示用户主动清空为「未分类」
            else -> currentCategory
        }
    }

    private fun isTransferFamily(type: Int): Boolean {
        return type == Bill.TYPE_TRANSFER || type == Bill.TYPE_REPAYMENT
    }

    private fun getTypeDisplayName(type: Int): String {
        return when (type) {
            Bill.TYPE_EXPENSE -> getString(R.string.expense_label)
            Bill.TYPE_INCOME -> getString(R.string.income_label)
            Bill.TYPE_TRANSFER -> getString(R.string.transfer_label)
            Bill.TYPE_REPAYMENT -> getString(R.string.repayment_label)
            else -> getString(R.string.expense_label)
        }
    }

    private fun formatAmountDisplay(amount: Double, uiType: Int): String {
        val symbol = CurrencyManager.getSymbol(bill?.currency ?: "CNY")
        val prefix = when (uiType) {
            Bill.TYPE_EXPENSE -> "-$symbol"
            Bill.TYPE_INCOME -> "+$symbol"
            else -> symbol
        }
        return prefix + String.format(Locale.getDefault(), "%.2f", amount)
    }

    private fun formatEditableAmount(amount: Double): String {
        return String.format(Locale.getDefault(), "%.2f", amount)
    }

    private fun resolveAmountColor(uiType: Int): Int {
        return when (uiType) {
            Bill.TYPE_EXPENSE -> Color.parseColor("#D32F2F")
            Bill.TYPE_INCOME -> Color.parseColor("#1A9B5F")
            else -> Color.parseColor("#6F7A8A")
        }
    }

    private fun resolveIconTintColor(uiType: Int): Int {
        return when (uiType) {
            Bill.TYPE_EXPENSE -> Color.parseColor("#C62828")
            Bill.TYPE_INCOME -> Color.parseColor("#2E7D32")
            else -> Color.parseColor("#7A8598")
        }
    }

    private fun buildCategoryInitial(text: String): String {
        return text.trim().firstOrNull()?.toString() ?: "分"
    }

    private fun shouldShowRefundButton(): Boolean {
        val currentBill = bill ?: return false
        return currentUiType == Bill.TYPE_EXPENSE && currentBill.subType != Bill.SUBTYPE_REFUND
    }

    private fun openRefundPage() {
        val currentBill = bill ?: return
        if (!shouldShowRefundButton()) return
        val intent = Intent(this, RefundActivity::class.java)
        intent.putExtra(RefundActivity.BILL_ID, currentBill.id)
        startActivity(intent)
    }

    private fun showCustomConfirmDialog(
        title: String,
        message: String,
        confirmText: String = getString(R.string.confirm),
        isDanger: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_delete_followup_confirm, null, false)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = title
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = message

        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting))
            .setView(panel)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = confirmText
            setBackgroundResource(
                if (isDanger) R.drawable.bg_delete_followup_danger_btn
                else R.drawable.bg_delete_followup_primary_btn
            )
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    companion object {
        const val BILL_ID = "BILL_ID"

        fun createIntent(context: Context, billId: Long): Intent {
            return Intent(context, BillDetailActivity::class.java).apply {
                putExtra(BILL_ID, billId)
            }
        }

        fun start(context: Context, billId: Long) {
            context.startActivity(createIntent(context, billId))
        }
    }
}
