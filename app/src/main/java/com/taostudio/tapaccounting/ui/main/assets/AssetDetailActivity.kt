package com.taostudio.tapaccounting.ui.main.assets

import com.taostudio.tapaccounting.BillColors
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.bumptech.glide.Glide
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.logic.AccountingFormController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.AddAssetActivity
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.repository.AssetRepository
import com.taostudio.tapaccounting.logic.BillAssetImpactService
import com.taostudio.tapaccounting.logic.AssetBillBalanceHistory
import com.taostudio.tapaccounting.logic.AssetBillBalanceDisplay
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.ui.dialog.ElegantDatePickerSheet
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.logic.BillDeleteHelper
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.logic.PaymentGroups
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.logic.CurrencyUtils
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import com.taostudio.tapaccounting.ui.common.AddBillEntrySheetLauncher
import com.taostudio.tapaccounting.ui.common.BillStatsMultiSelect
import com.taostudio.tapaccounting.ui.common.MultiSelectMoreTip
import com.taostudio.tapaccounting.ui.common.MultiSelectMotion
import com.taostudio.tapaccounting.ui.common.UiMotion.FabScrollBehavior
import com.taostudio.tapaccounting.logic.InvestmentLotDraftStorage
import com.taostudio.tapaccounting.logic.InvestmentLotEntryHelper
import com.taostudio.tapaccounting.logic.InvestmentInterestService
import com.taostudio.tapaccounting.ui.dialog.InvestmentLotPromptDialog
import com.taostudio.tapaccounting.ui.dialog.InvestmentLotSplitDialog
import com.taostudio.tapaccounting.ui.dialog.InvestmentLotManagerDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssetDetailActivity : AppCompatActivity() {
    companion object {
        private data class AssetDetailCache(
            val asset: Asset?,
            val bills: List<Bill>,
            val updatedAtMs: Long
        )

        private val detailCacheByAssetId = mutableMapOf<Long, AssetDetailCache>()
    }

    private lateinit var tvToolbarAssetName: TextView
    private lateinit var rvTransactions: RecyclerView
    private lateinit var tvBtnSearch: TextView
    private lateinit var layoutSearchBar: View
    private lateinit var etBillSearch: EditText
    private lateinit var adapter: TransactionAdapter
    private lateinit var fabAddBill: FloatingActionButton
    private lateinit var layoutMultiSelectActions: View
    private lateinit var btnMsCancel: TextView
    private lateinit var btnMsSelectAll: TextView
    private lateinit var btnMsMove: TextView
    private lateinit var btnMsDelete: TextView
    private lateinit var btnMsMore: TextView
    private lateinit var viewMsMoreDot: View
    private lateinit var tvMsSummary: TextView
    private var moreTipPopup: PopupWindow? = null
    private var moreMenuPopup: PopupWindow? = null
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var layoutBalancePanelTap: View
    private lateinit var tvAssetBalance: TextView
    private lateinit var layoutArchivedStatus: View
    private lateinit var tvExcludeHint: TextView
    private lateinit var toolbarDoubleTapDetector: GestureDetector
    /** FAB 显隐规则与账单首页、资产页共用同一份（见 [FabScrollBehavior]）。 */
    private val fabScrollBehavior = FabScrollBehavior(
        hide = { hideAssetDetailFab() },
        show = { showAssetDetailFab() },
    )

    private var assetId: Long = -1
    private var currentAsset: Asset? = null
    private var allAssetBills: List<Bill> = emptyList()
    private var assetDetailRemarkText: String = ""
    private var creditCycleSummaryText: String? = null
    private var investmentSummaryText: String? = null
    private var hasShownInvestmentLotPrompt = false
    /** Per-bill balance after tx, derived backward from current asset balance (not stored snapshots). */
    private var balanceAfterByBillId: Map<Long, Double> = emptyMap()
    private var searchQuery: String = ""
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val assetRepository by lazy { AssetRepository(db.assetDao(), db.billDao(), db) }
    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailTimeShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val billDetailSheetController by lazy(LazyThreadSafetyMode.NONE) {
        AssetBillDetailSheetController(
            activity = this,
            db = db,
            scope = lifecycleScope,
            getCurrentAssetCurrency = { currentAsset?.currency },
            getDefaultAssetId = { assetId },
            amountForAssetRow = ::amountForAssetRow,
            detailOwnerAssetId = ::detailOwnerAssetId,
            refundedAmountInBillCurrency = ::refundedAmountInBillCurrency,
            baseOriginalAmount = ::baseOriginalAmount,
            buildAssetDetailFormula = ::buildAssetDetailFormula
        )
    }

    data class MonthHeaderRow(
        val monthLabel: String,
        val inflow: Double,
        val outflow: Double
    )

    object DetailActionHeaderRow

    data class BillRow(val bill: Bill, val inGroup: Boolean = false)

    data class GroupRow(val groupId: String, val bills: List<Bill>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_detail)

        assetId = intent.getLongExtra("ASSET_ID", -1)
        if (assetId == -1L) {
            finish()
            return
        }

        initViews()
        setupBackPressForMultiSelect()
        observeData()
    }

    private fun initViews() {
        tvToolbarAssetName = findViewById(R.id.tv_toolbar_asset_name)
        rvTransactions = findViewById(R.id.rv_transactions)
        tvBtnSearch = findViewById(R.id.tv_btn_search)
        layoutSearchBar = findViewById(R.id.layout_asset_search_bar)
        etBillSearch = findViewById(R.id.et_asset_bill_search)
        layoutMultiSelectActions = findViewById(R.id.layout_multi_select_actions)
        btnMsCancel = findViewById(R.id.btn_ms_cancel)
        btnMsSelectAll = findViewById(R.id.btn_ms_select_all)
        btnMsMove = findViewById(R.id.btn_ms_move)
        btnMsDelete = findViewById(R.id.btn_ms_delete)
        btnMsMore = findViewById(R.id.btn_ms_more)
        viewMsMoreDot = findViewById(R.id.view_ms_more_dot)
        tvMsSummary = findViewById(R.id.tv_ms_summary)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = ""
        setupToolbarDoubleTapToHome()
        updateSearchButtonState(false)
        tvBtnSearch.setOnClickListener { toggleSearchPanel() }
        findViewById<View>(R.id.tv_btn_stats).setOnClickListener {
            startActivity(Intent(this, AssetStatsActivity::class.java).putExtra("ASSET_ID", assetId))
        }
        etBillSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                applyBillSearch()
            }
        })

        findViewById<View>(R.id.tv_btn_edit).setOnClickListener {
            val intent = Intent(this, AddAssetActivity::class.java)
            intent.putExtra("ASSET_ID", assetId)
            startActivity(intent)
        }

        findViewById<View>(R.id.tv_btn_manage).setOnClickListener {
            showAssetManageDialog()
        }

        layoutBalancePanelTap = findViewById(R.id.layout_balance_panel_tap)
        tvAssetBalance = findViewById(R.id.tv_asset_balance)
        layoutBalancePanelTap.setOnClickListener { showBillBalanceDisplaySheet() }

        layoutArchivedStatus = findViewById(R.id.layout_archived_status)
        tvExcludeHint = findViewById(R.id.tv_exclude_hint)
        findViewById<View>(R.id.tv_action_unarchive).setOnClickListener { setCurrentAssetArchived(false) }

        rvTransactions.layoutManager = LinearLayoutManager(this)
        adapter = TransactionAdapter().apply {
            onBillItemClick = { bill -> billDetailSheetController.showBillDetailSheet(bill, detailOwnerAssetId(bill)) }
            onSelectionChanged = { count -> updateDetailMultiSelectUi(count) }
        }
        rvTransactions.adapter = adapter
        rvTransactions.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                applyFabScrollBehavior(dy)
            }
        })

        fabAddBill = findViewById(R.id.fab_add_bill)
        fabAddBill.setOnClickListener {
            showAddBillForAsset()
        }
        fabAddBill.post { showAssetDetailFab() }
        setupMultiSelectActions()
    }

    private fun setupBackPressForMultiSelect() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isMultiSelectMode) {
                    adapter.clearSelection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupToolbarDoubleTapToHome() {
        toolbarDoubleTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                rvTransactions.post { rvTransactions.smoothScrollToPosition(0) }
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::toolbarDoubleTapDetector.isInitialized && isEventInsideView(ev, toolbar)) {
            val localX = ev.rawX - toolbar.screenX()
            val localY = ev.rawY - toolbar.screenY()
            if (!toolbar.isTouchInsideChild(localX, localY)) {
                toolbarDoubleTapDetector.onTouchEvent(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isEventInsideView(ev: MotionEvent, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = ev.rawX
        val y = ev.rawY
        return x >= loc[0] && x <= loc[0] + view.width && y >= loc[1] && y <= loc[1] + view.height
    }

    private fun View.screenX(): Int {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return loc[0]
    }

    private fun View.screenY(): Int {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return loc[1]
    }

    private fun View.isTouchInsideChild(x: Float, y: Float): Boolean {
        val group = this as? ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                return true
            }
        }
        return false
    }

    private fun setupMultiSelectActions() {
        btnMsCancel.setOnClickListener { adapter.clearSelection() }
        btnMsSelectAll.setOnClickListener {
            val allCount = adapter.getSelectableBills().size
            if (allCount > 0 && adapter.selectedBills.size >= allCount) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }
        btnMsDelete.setOnClickListener {
            val targets = adapter.getSelectedBills()
            if (targets.isEmpty()) return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                BillDeleteHelper.deleteBillsAndRevertBalance(db, targets)
                withContext(Dispatchers.Main) {
                    adapter.clearSelection()
                    Toast.makeText(
                        this@AssetDetailActivity,
                        "已删除 ${targets.size} 条账单",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        btnMsMove.setOnClickListener {
            val sourceAsset = currentAsset ?: return@setOnClickListener
            val targets = adapter.getSelectedBills()
            if (targets.isEmpty()) return@setOnClickListener
            OverlayDialogs.showGridAssetPicker(
                this,
                sourceAsset.name,
                "选择目标资产",
                allowClear = false
            ) { selectedName ->
                if (selectedName == sourceAsset.name) {
                    Toast.makeText(this, "已在当前资产中", Toast.LENGTH_SHORT).show()
                    return@showGridAssetPicker
                }
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val targetAsset = db.assetDao().getAssetByName(selectedName) ?: return@withContext 0
                        var moved = 0
                        targets.forEach { bill ->
                            val movedBill = moveBillToTargetAsset(bill, sourceAsset, targetAsset)
                            if (movedBill != null) {
                                db.billDao().updateBill(movedBill)
                                moved++
                            }
                        }
                        moved
                    }
                    adapter.clearSelection()
                    if (result > 0) {
                        applyBillSearch()
                    }
                    Toast.makeText(this@AssetDetailActivity, "已移动 $result 条账单", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnMsMore.setOnClickListener { onMoreClicked() }
        updateDetailMultiSelectUi(0)
    }

    override fun onStop() {
        hideMoreTipPopup()
        dismissMoreMenu()
        super.onStop()
    }

    private fun onMoreClicked() {
        dismissMoreTipPermanently()
        showMoreMenu()
    }

    private fun applyPaymentGroupAction() {
        val targets = adapter.getSelectedBills()
        val action = PaymentGroups.resolveAction(targets)
        if (action == PaymentGroups.Action.MERGE) {
            val block = PaymentGroups.mergeBlock(targets, assetId)
            if (block != null) {
                Toast.makeText(this, mergeBlockReason(block), Toast.LENGTH_SHORT).show()
                return
            }
        }
        val groupId = if (action == PaymentGroups.Action.MERGE) PaymentGroups.newGroupId() else null
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    BillMutationService.setPaymentGroupBatch(db, targets, groupId)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            if (!saved) {
                Toast.makeText(this@AssetDetailActivity, R.string.payment_group_not_owner, Toast.LENGTH_SHORT).show()
                return@launch
            }
            adapter.clearSelection()
            val message = if (groupId != null) R.string.toast_payment_merged_fmt else R.string.toast_payment_split_fmt
            Toast.makeText(this@AssetDetailActivity, getString(message, targets.size), Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildSelectionSummary(selected: List<Bill>): String {
        val currency = currentAsset?.currency ?: selected.firstOrNull()?.currency ?: "CNY"
        var inflow = 0.0
        var outflow = 0.0
        selected.forEach { bill ->
            val (billInflow, billOutflow) = assetFlow(bill)
            inflow += billInflow
            outflow += billOutflow
        }
        return getString(
            R.string.asset_selection_summary_fmt,
            selected.size,
            formatMoney(outflow, currency),
            formatMoney(inflow, currency)
        )
    }

    private fun updateDetailMultiSelectUi(selectedCount: Int) {
        val active = adapter.isMultiSelectMode
        val becameActive = active && layoutMultiSelectActions.visibility != View.VISIBLE
        if (active) MultiSelectMotion.animateIn(layoutMultiSelectActions)
        else MultiSelectMotion.animateOut(layoutMultiSelectActions)
        val hasSelection = selectedCount > 0
        btnMsDelete.text = getString(R.string.delete_label)
        btnMsDelete.isEnabled = hasSelection
        btnMsMove.isEnabled = hasSelection
        btnMsDelete.alpha = if (hasSelection) 1f else 0.45f
        btnMsMove.alpha = if (hasSelection) 1f else 0.45f
        tvMsSummary.text = buildSelectionSummary(adapter.getSelectedBills())
        if (active) {
            hideAssetDetailFab()
            if (becameActive) {
                showMoreTipIfNeeded()
            } else {
                viewMsMoreDot.visibility =
                    if (MultiSelectMoreTip.shouldShow(this)) View.VISIBLE else View.GONE
            }
        } else {
            showAssetDetailFab()
            hideMoreTipPopup()
            dismissMoreMenu()
            viewMsMoreDot.visibility = View.GONE
        }
    }

    private fun mergeBlockReason(block: PaymentGroups.MergeBlock): Int = when (block) {
        PaymentGroups.MergeBlock.TOO_FEW -> R.string.payment_merge_too_few
        PaymentGroups.MergeBlock.NOT_INCOME_EXPENSE -> R.string.payment_merge_not_income_expense
        PaymentGroups.MergeBlock.OTHER_ASSET -> R.string.payment_merge_other_asset
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showMoreTipIfNeeded() {
        val show = MultiSelectMoreTip.shouldShow(this)
        viewMsMoreDot.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            hideMoreTipPopup()
            return
        }
        MultiSelectMoreTip.onEnteredMultiSelect(this)
        btnMsMore.post {
            if (!adapter.isMultiSelectMode || isFinishing || isDestroyed) return@post
            showMoreTipPopup()
        }
    }

    private fun showMoreTipPopup() {
        hideMoreTipPopup()
        val tip = TextView(this).apply {
            text = getString(R.string.multi_select_more_tip)
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundResource(R.drawable.bg_ms_more_tip)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        tip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popup = PopupWindow(tip, tip.measuredWidth, tip.measuredHeight, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = false
            isTouchable = false
            elevation = dp(6).toFloat()
        }
        moreTipPopup = popup
        val xOff = (btnMsMore.width - tip.measuredWidth) / 2
        val yOff = -(btnMsMore.height + tip.measuredHeight + dp(6))
        try {
            if (btnMsMore.isAttachedToWindow) {
                popup.showAsDropDown(btnMsMore, xOff, yOff)
            }
        } catch (_: android.view.WindowManager.BadTokenException) {
            hideMoreTipPopup()
        } catch (_: IllegalStateException) {
            hideMoreTipPopup()
        }
    }

    private fun hideMoreTipPopup() {
        moreTipPopup?.dismiss()
        moreTipPopup = null
    }

    private fun dismissMoreTipPermanently() {
        MultiSelectMoreTip.dismiss(this)
        viewMsMoreDot.visibility = View.GONE
        hideMoreTipPopup()
    }

    private fun dismissMoreMenu() {
        moreMenuPopup?.dismiss()
        moreMenuPopup = null
    }

    private fun showMoreMenu() {
        dismissMoreMenu()
        hideMoreTipPopup()
        val selected = adapter.getSelectedBills()
        val themeContext = ContextThemeWrapper(this, R.style.Theme_TapAccounting)
        val content = LayoutInflater.from(themeContext).inflate(R.layout.popup_asset_ms_more, null, false)

        val groupAction = PaymentGroups.resolveAction(selected)
        val mergeBlock = if (groupAction == PaymentGroups.Action.SPLIT) {
            null
        } else {
            PaymentGroups.mergeBlock(selected, assetId)
        }
        val mergeEnabled = selected.isNotEmpty() &&
            (groupAction == PaymentGroups.Action.SPLIT || mergeBlock == null)
        val tvMergeTitle = content.findViewById<TextView>(R.id.tv_ms_merge_title)
        val tvMergeHint = content.findViewById<TextView>(R.id.tv_ms_merge_hint)
        tvMergeTitle.setText(
            if (groupAction == PaymentGroups.Action.SPLIT) R.string.payment_split else R.string.payment_merge
        )
        tvMergeHint.setText(
            when {
                groupAction == PaymentGroups.Action.SPLIT -> R.string.payment_split_hint
                mergeBlock != null -> mergeBlockReason(mergeBlock)
                else -> R.string.payment_merge_hint
            }
        )
        tvMergeTitle.alpha = if (mergeEnabled) 1f else 0.45f
        tvMergeHint.alpha = if (mergeEnabled) 1f else 0.45f
        content.findViewById<View>(R.id.item_ms_merge).setOnClickListener {
            dismissMoreMenu()
            applyPaymentGroupAction()
        }

        val excludeInclude = selected.isNotEmpty() &&
            BillStatsMultiSelect.resolveAction(selected) == BillStatsMultiSelect.Action.INCLUDE
        val tvExcludeTitle = content.findViewById<TextView>(R.id.tv_ms_exclude_title)
        val tvExcludeHint = content.findViewById<TextView>(R.id.tv_ms_exclude_hint)
        tvExcludeTitle.setText(if (excludeInclude) R.string.include else R.string.exclude)
        tvExcludeHint.setText(
            when {
                selected.isEmpty() -> R.string.multi_select_pick_bills_first
                excludeInclude -> R.string.include_stats_hint
                else -> R.string.exclude_stats_hint
            }
        )
        tvExcludeTitle.alpha = if (selected.isNotEmpty()) 1f else 0.45f
        tvExcludeHint.alpha = if (selected.isNotEmpty()) 1f else 0.45f
        content.findViewById<View>(R.id.item_ms_exclude).setOnClickListener {
            dismissMoreMenu()
            if (selected.isEmpty()) {
                Toast.makeText(this, R.string.multi_select_pick_bills_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            BillStatsMultiSelect.apply(
                context = this,
                scope = lifecycleScope,
                bills = selected
            ) {
                adapter.deselectAll()
                applyBillSearch()
            }
        }

        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popup = PopupWindow(content, content.measuredWidth, content.measuredHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setOnDismissListener { moreMenuPopup = null }
        }
        moreMenuPopup = popup
        val xOff = (btnMsMore.width - content.measuredWidth) / 2
        val yOff = -(btnMsMore.height + content.measuredHeight + dp(8))
        try {
            if (btnMsMore.isAttachedToWindow) {
                popup.showAsDropDown(btnMsMore, xOff, yOff)
            }
        } catch (_: android.view.WindowManager.BadTokenException) {
            dismissMoreMenu()
        } catch (_: IllegalStateException) {
            dismissMoreMenu()
        }
    }

    private fun moveBillToTargetAsset(
        bill: Bill,
        sourceAsset: Asset,
        targetAsset: Asset
    ): Bill? {
        val matchSourceAccount = bill.accountId == sourceAsset.id ||
            (bill.accountId == null && bill.accountName == sourceAsset.name)
        val matchSourceToAccount = bill.toAccountId == sourceAsset.id ||
            (bill.toAccountId == null && bill.toAccountName == sourceAsset.name)

        val updated = if (bill.type == Bill.TYPE_TRANSFER) {
            when {
                matchSourceAccount && matchSourceToAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name,
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                matchSourceAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name
                )
                matchSourceToAccount -> bill.copy(
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                else -> null
            }
        } else {
            when {
                matchSourceAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name
                )
                matchSourceToAccount -> bill.copy(
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                else -> null
            }
        }
        return if (updated != null && updated != bill) updated else null
    }

    private fun observeData() {
        detailCacheByAssetId[assetId]?.let { cached ->
            currentAsset = cached.asset ?: currentAsset
            if (cached.asset != null) {
                updateAssetUI(cached.asset)
            }
            allAssetBills = cached.bills
            recomputeBalanceAfterMap()
            applyBillSearch()
        }

        lifecycleScope.launch {
            db.assetDao().observeAssetById(assetId).filterNotNull().collectLatest { asset ->
                currentAsset = asset
                updateAssetUI(asset)
                detailCacheByAssetId[assetId] = AssetDetailCache(
                    asset = asset,
                    bills = allAssetBills,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.billDao().backfillAssetLinksByName()
            }
            val assetName = currentAsset?.name.orEmpty()
            db.billDao().getBillsByAssetIdOrName(assetId, assetName).collectLatest { bills ->
                allAssetBills = bills
                recomputeBalanceAfterMap()
                applyBillSearch()
                detailCacheByAssetId[assetId] = AssetDetailCache(
                    asset = currentAsset,
                    bills = bills,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    private fun toggleSearchPanel() {
        val showing = layoutSearchBar.visibility == View.VISIBLE
        if (!showing) {
            layoutSearchBar.visibility = View.VISIBLE
            updateSearchButtonState(true)
            etBillSearch.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etBillSearch, InputMethodManager.SHOW_IMPLICIT)
            return
        }

        layoutSearchBar.visibility = View.GONE
        updateSearchButtonState(false)
        etBillSearch.setText("")
        searchQuery = ""
        applyBillSearch()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etBillSearch.windowToken, 0)
    }

    private fun updateSearchButtonState(active: Boolean) {
        tvBtnSearch.setTextColor(Color.parseColor(if (active) "#4080FF" else "#333333"))
    }

    private fun applyBillSearch() {
        val keyword = searchQuery.trim()
        if (keyword.isEmpty()) {
            adapter.submitList(allAssetBills)
            return
        }
        adapter.submitList(allAssetBills.filter { billMatchesQuery(it, keyword) })
    }

    private fun billMatchesQuery(bill: Bill, keyword: String): Boolean {
        val query = keyword.lowercase(Locale.ROOT)
        val amountText = String.format(Locale.getDefault(), "%.2f", bill.amount).lowercase(Locale.ROOT)
        val category = bill.categoryName.lowercase(Locale.ROOT)
        val remark = bill.remark.lowercase(Locale.ROOT)
        val account = bill.accountName.lowercase(Locale.ROOT)
        val toAccount = bill.toAccountName.lowercase(Locale.ROOT)
        val bookName = bill.bookName.lowercase(Locale.ROOT)
        val currency = bill.currency.lowercase(Locale.ROOT)
        return amountText.contains(query) ||
            category.contains(query) ||
            remark.contains(query) ||
            account.contains(query) ||
            toAccount.contains(query) ||
            bookName.contains(query) ||
            currency.contains(query)
    }

    private fun recomputeBalanceAfterMap() {
        val asset = currentAsset ?: return
        balanceAfterByBillId = AssetBillBalanceHistory.computeBalanceAfterByBillId(
            bills = allAssetBills,
            assetId = assetId,
            assetName = asset.name,
            assetCurrency = asset.currency,
            currentBalance = asset.balance
        )
        if (::adapter.isInitialized) {
            adapter.setBalanceAfterByBillId(balanceAfterByBillId)
        }
    }

    private fun updateAssetUI(asset: Asset) {
        tvToolbarAssetName.text = asset.name
        recomputeBalanceAfterMap()
        val balanceText = CurrencyUtils.formatAmount(asset.balance, asset.currency)
        val noteParts = mutableListOf<String>()
        if (asset.assetCategory == Asset.CATEGORY_INVESTMENT && asset.annualInterestRate != 0.0) {
            noteParts += "新批次默认年利率 ${AmountFormatHelper.formatRate(asset.annualInterestRate)}%"
        }
        if (asset.remark.isNotBlank()) noteParts += asset.remark.trim()
        creditCycleSummaryText = null

        // 收纳会临时把 includeInNetAsset 置 false；取消收纳时从 includeInNetBeforeArchive 恢复
        layoutArchivedStatus.visibility = if (asset.isArchived) View.VISIBLE else View.GONE
        tvExcludeHint.visibility = if (!asset.isArchived && !asset.includeInNetAsset) View.VISIBLE else View.GONE

        // 理财资产自动弹窗补录本金批次
        checkAndPromptInvestmentLotDraft(asset)
        loadInvestmentSummary(asset)

        // P1-6: 信用卡周期快照
        if (asset.assetCategory == Asset.CATEGORY_CREDIT_CARD) {
            val cycleService = com.taostudio.tapaccounting.logic.CreditCardCycleService()
            lifecycleScope.launch {
                val bills = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    db.billDao().getBillsByAssetIdOrNameList(asset.id, asset.name)
                }
                val snapshot = cycleService.calculateSnapshot(asset, bills)
                val stmt = asset.statementDay.takeIf { it > 0 } ?: cycleService.getStatementDay(asset).takeIf { it > 0 }
                val due = asset.dueDay.takeIf { it > 0 } ?: cycleService.getDueDay(asset).takeIf { it > 0 }
                if (stmt != null || due != null || snapshot != null) {
                    val summaryParts = mutableListOf<String>()
                    if (stmt != null) summaryParts += getString(R.string.credit_statement_day_value, "${stmt}号")
                    if (due != null) summaryParts += getString(R.string.credit_due_day_value, "${due}号")
                    if (snapshot != null) {
                        summaryParts += getString(R.string.credit_due_amount_fmt, snapshot.amountDue)
                        snapshot.daysToDue?.let { summaryParts += getString(R.string.credit_days_to_due_fmt, it) }
                    }
                    creditCycleSummaryText = summaryParts.joinToString(" · ")
                } else {
                    creditCycleSummaryText = getString(R.string.credit_cycle_not_configured)
                }
                if (snapshot != null) {
                    if (snapshot.unbilledSpend > 0) {
                        noteParts += "未出账 ¥${String.format("%.0f", snapshot.unbilledSpend)}"
                    }
                    if (snapshot.availableLimit != null) {
                        noteParts += "剩余额度 ¥${String.format("%.0f", snapshot.availableLimit)}"
                    }
                }
                val remarkText = noteParts.joinToString(" · ")
                tvAssetBalance.text = balanceText
                assetDetailRemarkText = remarkText
                if (::adapter.isInitialized) adapter.notifyDetailHeaderChanged()
                return@launch
            }
            return // 异步处理，提前返回
        }

        val remarkText = noteParts.joinToString(" · ")
        tvAssetBalance.text = balanceText
        assetDetailRemarkText = remarkText
        if (::adapter.isInitialized) adapter.notifyDetailHeaderChanged()
    }

    private fun checkAndPromptInvestmentLotDraft(asset: Asset) {
        if (hasShownInvestmentLotPrompt) return
        if (asset.assetCategory != Asset.CATEGORY_INVESTMENT || asset.balance <= 0.0) return
        hasShownInvestmentLotPrompt = true
        lifecycleScope.launch {
            val needPrompt = withContext(Dispatchers.IO) {
                val hasDraft = InvestmentLotDraftStorage.hasDraft(this@AssetDetailActivity, asset.id)
                val hasOpenLots = db.investmentLotDao().getOpenLotsByAssetId(asset.id).isNotEmpty()
                hasDraft || !hasOpenLots
            }
            if (!needPrompt) return@launch
            val hasDraft = InvestmentLotDraftStorage.hasDraft(this@AssetDetailActivity, asset.id)
            InvestmentLotPromptDialog.show(
                activity = this@AssetDetailActivity,
                hasDraft = hasDraft,
                onGo = { openInvestmentLotEntryDialog(asset, hasDraft) }
            )
        }
    }

    private fun openInvestmentLotEntryDialog(asset: Asset, hasDraft: Boolean) {
        val initialDrafts = if (hasDraft) {
            InvestmentLotDraftStorage.load(this, asset.id)
        } else {
            emptyList()
        }
        InvestmentLotSplitDialog.show(
            activity = this,
            title = getString(R.string.investment_lot_prompt_title),
            message = getString(R.string.investment_principal_hint),
            totalAmount = asset.balance,
            currency = asset.currency,
            annualInterestRate = asset.annualInterestRate,
            initialDrafts = initialDrafts,
            onLater = { drafts ->
                InvestmentLotEntryHelper.saveDrafts(this, asset.id, drafts)
            },
            onConfirm = { lots ->
                lifecycleScope.launch(Dispatchers.IO) {
                    InvestmentLotEntryHelper.persistConfirmedLots(this@AssetDetailActivity, db, asset, lots)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@AssetDetailActivity,
                            getString(R.string.investment_lot_saved),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun loadInvestmentSummary(asset: Asset) {
        if (asset.assetCategory != Asset.CATEGORY_INVESTMENT) {
            investmentSummaryText = null
            if (::adapter.isInitialized) adapter.notifyDetailHeaderChanged()
            return
        }
        lifecycleScope.launch {
            val lots = withContext(Dispatchers.IO) {
                db.investmentLotDao().getOpenLotsByAssetId(asset.id)
            }
            val tracked = lots.sumOf { it.remainingPrincipal }
            val accruing = lots.count {
                it.status == com.taostudio.tapaccounting.data.local.entity.InvestmentLot.STATUS_ACTIVE &&
                    it.annualInterestRate != 0.0
            }
            val paused = lots.count {
                it.status == com.taostudio.tapaccounting.data.local.entity.InvestmentLot.STATUS_PAUSED
            }
            investmentSummaryText = if (lots.isEmpty()) {
                getString(R.string.investment_manage_empty)
            } else {
                buildString {
                    append("已跟踪 ${CurrencyUtils.formatAmount(tracked, asset.currency)} · ${lots.size} 笔批次")
                    if (accruing > 0) append(" · $accruing 笔自动结息")
                    if (paused > 0) append(" · $paused 笔暂停")
                }
            }
            if (::adapter.isInitialized) adapter.notifyDetailHeaderChanged()
        }
    }

    private fun openInvestmentLotManager() {
        val asset = currentAsset ?: return
        if (asset.assetCategory != Asset.CATEGORY_INVESTMENT) return
        lifecycleScope.launch {
            val lots = withContext(Dispatchers.IO) {
                db.investmentLotDao().getLotsByAssetId(asset.id)
            }
            InvestmentLotManagerDialog.show(
                activity = this@AssetDetailActivity,
                asset = asset,
                lots = lots,
                onEdit = { lot, rate, cycle, active ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        InvestmentInterestService.updateLotSchedule(
                            db = db,
                            lotId = lot.id,
                            annualInterestRate = rate,
                            settlementCycle = cycle,
                            active = active
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@AssetDetailActivity,
                                "收益设置已更新",
                                Toast.LENGTH_SHORT
                            ).show()
                            currentAsset?.let(::loadInvestmentSummary)
                        }
                    }
                },
                onAddMissing = {
                    lifecycleScope.launch {
                        val latestLots = withContext(Dispatchers.IO) {
                            db.investmentLotDao().getOpenLotsByAssetId(asset.id)
                        }
                        val missing = BillAssetImpactService.roundMoneyForCurrency(
                            (asset.balance - latestLots.sumOf { it.remainingPrincipal }).coerceAtLeast(0.0),
                            asset.currency
                        )
                        if (missing <= 0.0) return@launch
                        InvestmentLotSplitDialog.show(
                            activity = this@AssetDetailActivity,
                            title = getString(R.string.investment_lot_prompt_title),
                            message = "为尚未跟踪的余额补录本金批次。",
                            totalAmount = missing,
                            currency = asset.currency,
                            annualInterestRate = asset.annualInterestRate,
                            onLater = { },
                            onConfirm = { drafts ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    InvestmentLotEntryHelper.persistConfirmedLots(
                                        this@AssetDetailActivity,
                                        db,
                                        asset,
                                        drafts
                                    )
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@AssetDetailActivity,
                                            getString(R.string.investment_lot_saved),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        loadInvestmentSummary(asset)
                                    }
                                }
                            }
                        )
                    }
                }
            )
        }
    }

    private fun setCurrentAssetArchived(archived: Boolean, offerUndo: Boolean = true) {
        val asset = currentAsset ?: return
        if (asset.isArchived == archived) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.assetDao().updateArchived(asset.id, archived) }
            val message = getString(
                if (archived) R.string.asset_archived_toast else R.string.asset_unarchived_toast,
                asset.name
            )
            val snackbar = Snackbar.make(rvTransactions, message, Snackbar.LENGTH_LONG)
            if (offerUndo) {
                snackbar.setAction(R.string.undo) { setCurrentAssetArchived(!archived, offerUndo = false) }
            }
            snackbar.show()
        }
    }

    private fun showAssetManageDialog() {
        val asset = currentAsset ?: return
        data class ManageOption(
            val title: String,
            val desc: String,
            val highRisk: Boolean = false,
            val onClick: () -> Unit
        )

        val options = listOf(
            if (asset.isArchived) {
                ManageOption(
                    title = getString(R.string.asset_action_unarchive),
                    desc = getString(R.string.asset_action_unarchive_desc),
                    onClick = { setCurrentAssetArchived(false) }
                )
            } else {
                ManageOption(
                    title = getString(R.string.asset_action_archive),
                    desc = getString(R.string.asset_action_archive_desc),
                    onClick = { setCurrentAssetArchived(true) }
                )
            },
            ManageOption(
                title = getString(R.string.asset_action_delete),
                desc = getString(R.string.asset_action_delete_desc),
                highRisk = true,
                onClick = { showDeleteConfirmDialog() }
            )
        )

        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_book_delete_options, null, false)
        panel.findViewById<TextView>(R.id.tv_delete_book_title).text =
            getString(R.string.asset_manage_title, asset.name)
        panel.findViewById<TextView>(R.id.tv_delete_book_desc).text = getString(
            if (asset.isArchived) R.string.asset_manage_desc_archived else R.string.asset_manage_desc
        )
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)
        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting))
            .setView(panel)
            .create()
        options.forEach { opt ->
            val item = LayoutInflater.from(this).inflate(R.layout.item_book_delete_option, optionsContainer, false)
            item.findViewById<TextView>(R.id.tv_delete_option_title).text = opt.title
            item.findViewById<TextView>(R.id.tv_delete_option_desc).text = opt.desc
            item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility =
                if (opt.highRisk) View.VISIBLE else View.GONE
            item.setOnClickListener {
                dialog.dismiss()
                opt.onClick()
            }
            optionsContainer.addView(item)
        }
        panel.findViewById<TextView>(R.id.btn_delete_book_cancel).setOnClickListener { dialog.dismiss() }

        OverlayDialogs.showPageCenterDialog(
            dialog,
            this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun applyBillBalanceDisplayLocally(show: Boolean, fromTimeMillis: Long) {
        val asset = currentAsset ?: return
        currentAsset = asset.copy(
            showBillBalanceAfter = show,
            billBalanceFromTime = fromTimeMillis
        )
        if (::adapter.isInitialized) {
            adapter.notifyBillBalanceDisplayChanged()
        }
    }

    private fun showBillBalanceDisplaySheet() {
        val asset = currentAsset ?: return
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_asset_balance_display, null)
        dialog.setContentView(view)

        val switchShow = view.findViewById<SwitchMaterial>(R.id.switch_show_bill_balance)
        val layoutFromDate = view.findViewById<View>(R.id.layout_balance_from_date)
        val tvFromDate = view.findViewById<TextView>(R.id.tv_balance_from_date)
        val tvResetCreate = view.findViewById<TextView>(R.id.tv_reset_balance_from_create)

        var showBalance = asset.showBillBalanceAfter
        var fromTime = asset.billBalanceFromTime.takeIf { it > 0L }
            ?: AssetBillBalanceDisplay.assetCreationDayStart(asset, allAssetBills, assetId, asset.name)

        fun commitDisplaySettings() {
            applyBillBalanceDisplayLocally(showBalance, fromTime)
            persistBillBalanceDisplay(showBalance, fromTime)
        }

        fun refreshUi() {
            switchShow.setOnCheckedChangeListener(null)
            switchShow.isChecked = showBalance
            switchShow.setOnCheckedChangeListener { _, checked ->
                showBalance = checked
                refreshUi()
                commitDisplaySettings()
            }
            layoutFromDate.alpha = if (showBalance) 1f else 0.45f
            layoutFromDate.isEnabled = showBalance
            tvResetCreate.isEnabled = showBalance
            tvFromDate.text = AssetBillBalanceDisplay.formatFromDateLabel(fromTime)
        }

        layoutFromDate.setOnClickListener {
            if (!showBalance) return@setOnClickListener
            ElegantDatePickerSheet.show(
                context = this,
                initialTimeMillis = fromTime,
                maxTimeMillis = System.currentTimeMillis()
            ) { selected ->
                fromTime = selected
                refreshUi()
                commitDisplaySettings()
            }
        }

        tvResetCreate.setOnClickListener {
            if (!showBalance) return@setOnClickListener
            fromTime = AssetBillBalanceDisplay.assetCreationDayStart(asset, allAssetBills, assetId, asset.name)
            refreshUi()
            commitDisplaySettings()
        }

        refreshUi()
        dialog.dismissWithAnimation = true
        dialog.setOnShowListener { shown ->
            val sheet = (shown as? BottomSheetDialog)
                ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            BottomSheetBehavior.from(sheet).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    private fun persistBillBalanceDisplay(show: Boolean, fromTimeMillis: Long) {
        val asset = currentAsset ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            db.assetDao().updateBillBalanceDisplay(
                assetId = asset.id,
                showBillBalanceAfter = show,
                billBalanceFromTime = fromTimeMillis
            )
        }
    }

    private fun showAddBillForAsset() {
        val asset = currentAsset ?: return
        val prefill = JSONObject().apply {
            put("asset_name", asset.name)
        }
        AddBillEntrySheetLauncher.show(
            activity = this,
            prefillData = prefill,
            onShow = { hideAssetDetailFab() },
            onDismiss = { showAssetDetailFab() }
        )
    }

    private fun showAssetDetailFab() {
        fabScrollBehavior.markVisible()
        fabAddBill.show()
    }

    private fun hideAssetDetailFab() {
        fabScrollBehavior.markHidden()
        fabAddBill.hide()
    }

    private fun applyFabScrollBehavior(dy: Int) {
        fabScrollBehavior.onScroll(dy, atTop = !rvTransactions.canScrollVertically(-1))
    }

    private fun showDeleteConfirmDialog() {
        val themeContext = ContextThemeWrapper(this, R.style.Theme_TapAccounting)
        val dialog = AlertDialog.Builder(themeContext)
            .setTitle("\u5220\u9664\u8D26\u6237")
            .setMessage("\u786E\u5B9A\u5220\u9664\u8BE5\u8D26\u6237\u5417\uFF1F\u76F8\u5173\u7684\u8D26\u5355\u5C06\u5931\u53BB\u8D26\u6237\u5173\u8054\u3002")
            .setPositiveButton("\u5220\u9664") { _, _ ->
                lifecycleScope.launch {
                    currentAsset?.let { assetRepository.deleteAssetWithCleanup(it) }
                    finish()
                }
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog,
            this,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    inner class TransactionAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val PAYLOAD_MODE_CHANGE = "PAYLOAD_MODE_CHANGE"
        private val PAYLOAD_SELECTION_CHANGE = "PAYLOAD_SELECTION_CHANGE"
        private val PAYLOAD_BALANCE_DISPLAY_CHANGE = "PAYLOAD_BALANCE_DISPLAY_CHANGE"
        private val PAYLOAD_HEADER_SELECTION_CHANGE = "PAYLOAD_HEADER_SELECTION_CHANGE"
        private val PAYLOAD_DETAIL_HEADER_CHANGE = "PAYLOAD_DETAIL_HEADER_CHANGE"
        private val typeDetailActionHeader = 0
        private val typeMonthHeader = 1
        private val typeBillItem = 2
        private val typeGroupItem = 3
        private val expandedGroupIds = mutableSetOf<String>()
        private var lastSubmittedBills: List<Bill> = emptyList()

        private val rows = mutableListOf<Any>()
        private val monthKeyFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        private val monthLabelFormat = SimpleDateFormat("yyyy.MM\u6708", Locale.getDefault())
        private val currentYearMonthLabelFormat = SimpleDateFormat("MM\u6708", Locale.getDefault())
        private val yearFormat = SimpleDateFormat("yyyy", Locale.getDefault())
        private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
        var isMultiSelectMode: Boolean = false
        val selectedBills = mutableSetOf<Bill>()
        var onBillItemClick: ((Bill) -> Unit)? = null
        var onSelectionChanged: ((Int) -> Unit)? = null
        private var balanceAfterByBillId: Map<Long, Double> = emptyMap()

        fun setBalanceAfterByBillId(map: Map<Long, Double>) {
            if (balanceAfterByBillId == map) return
            balanceAfterByBillId = map
            notifyBillBalanceDisplayChanged()
        }

        fun notifyBillBalanceDisplayChanged() {
            if (rows.isEmpty()) return
            notifyItemRangeChanged(0, itemCount, PAYLOAD_BALANCE_DISPLAY_CHANGE)
        }

        fun notifyDetailHeaderChanged() {
            val position = rows.indexOfFirst { it is DetailActionHeaderRow }
            if (position >= 0) notifyItemChanged(position, PAYLOAD_DETAIL_HEADER_CHANGE)
        }

        fun submitList(newList: List<Bill>) {
            rows.clear()
            rows.add(DetailActionHeaderRow)
            if (newList.isNotEmpty()) {
                val sorted = newList.sortedWith(compareByDescending<Bill> { it.time }.thenByDescending { it.id })
                val grouped = sorted.groupBy { monthKeyFormat.format(Date(it.time)) }

                grouped.forEach { (_, monthBills) ->
                    var monthlyInflow = 0.0
                    var monthlyOutflow = 0.0

                    monthBills.forEach { bill ->
                        val (inflow, outflow) = assetFlow(bill)
                        monthlyInflow += inflow
                        monthlyOutflow += outflow
                    }

                    val monthLabel = formatMonthHeaderLabel(Date(monthBills.first().time))
                    rows.add(MonthHeaderRow(monthLabel, monthlyInflow, monthlyOutflow))
                    PaymentGroups.arrange(monthBills).forEach { item ->
                        when (item) {
                            is PaymentGroups.Item.Single -> rows.add(BillRow(item.bill))
                            is PaymentGroups.Item.Group -> {
                                rows.add(GroupRow(item.groupId, item.bills))
                                if (item.groupId in expandedGroupIds) {
                                    item.bills.forEach { rows.add(BillRow(it, inGroup = true)) }
                                }
                            }
                        }
                    }
                }
            }
            lastSubmittedBills = newList
            val availableIds = getSelectableBills().map { it.id }.toSet()
            selectedBills.removeAll { it.id !in availableIds }
            if (getSelectableBills().isEmpty()) {
                isMultiSelectMode = false
            }
            onSelectionChanged?.invoke(selectedBills.size)
            notifyDataSetChanged()
        }

        private fun formatMonthHeaderLabel(monthDate: Date): String {
            return if (yearFormat.format(monthDate) == yearFormat.format(Date())) {
                currentYearMonthLabelFormat.format(monthDate)
            } else {
                monthLabelFormat.format(monthDate)
            }
        }

        fun getSelectableBills(): List<Bill> = billsInRows(rows)

        private fun billsInRows(source: Iterable<Any>): List<Bill> = source.flatMap { row ->
            when (row) {
                is BillRow -> listOf(row.bill)
                is GroupRow -> row.bills
                else -> emptyList()
            }
        }.distinctBy { it.id }

        private fun toggleGroupExpanded(groupId: String) {
            if (!expandedGroupIds.add(groupId)) expandedGroupIds.remove(groupId)
            submitList(lastSubmittedBills)
        }

        private fun notifySelectionChanged() {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION_CHANGE)
            onSelectionChanged?.invoke(selectedBills.size)
        }

        private fun isListItem(row: Any?): Boolean = row is BillRow || row is GroupRow

        private fun bindRowBackground(itemView: View, position: Int) {
            val hasMonthHeaderAbove = rows.getOrNull(position - 1) is MonthHeaderRow
            val isGroupStart = !isListItem(rows.getOrNull(position - 1))
            val isGroupEnd = !isListItem(rows.getOrNull(position + 1))
            itemView.setBackgroundResource(
                when {
                    isGroupStart && isGroupEnd ->
                        if (hasMonthHeaderAbove) R.drawable.bg_bill_group_bottom else R.drawable.bg_bill_group_single
                    isGroupStart ->
                        if (hasMonthHeaderAbove) R.drawable.bg_bill_group_middle else R.drawable.bg_bill_group_top
                    isGroupEnd -> R.drawable.bg_bill_group_bottom
                    else -> R.drawable.bg_bill_group_middle
                }
            )
        }

        fun getSelectedBills(): List<Bill> = selectedBills.toList()

        fun clearSelection() {
            selectedBills.clear()
            isMultiSelectMode = false
            onSelectionChanged?.invoke(0)
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
        }

        fun deselectAll() {
            isMultiSelectMode = true
            selectedBills.clear()
            onSelectionChanged?.invoke(0)
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
        }

        fun selectAll() {
            isMultiSelectMode = true
            selectedBills.clear()
            selectedBills.addAll(getSelectableBills())
            onSelectionChanged?.invoke(selectedBills.size)
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
        }

        private fun selectableBillsForSection(headerPosition: Int): List<Bill> {
            return billsInRows(
                rows.asSequence()
                    .drop(headerPosition + 1)
                    .takeWhile { it !is MonthHeaderRow }
                    .asIterable()
            )
        }

        private fun sectionSelectionState(headerPosition: Int): Pair<Boolean, Boolean> {
            val bills = selectableBillsForSection(headerPosition)
            if (bills.isEmpty()) return false to false
            val selectedCount = bills.count { selectedBills.contains(it) }
            return (selectedCount == bills.size) to (selectedCount in 1 until bills.size)
        }

        private fun setSectionSelection(headerPosition: Int, checked: Boolean) {
            val bills = selectableBillsForSection(headerPosition)
            if (bills.isEmpty()) return
            if (checked) {
                selectedBills.addAll(bills)
            } else {
                selectedBills.removeAll(bills.toSet())
            }
            isMultiSelectMode = true
            onSelectionChanged?.invoke(selectedBills.size)
            val nextHeader = ((headerPosition + 1) until rows.size)
                .firstOrNull { rows[it] is MonthHeaderRow }
                ?: rows.size
            notifyItemRangeChanged(headerPosition, nextHeader - headerPosition, PAYLOAD_SELECTION_CHANGE)
        }

        override fun getItemViewType(position: Int): Int {
            return when (rows[position]) {
                is DetailActionHeaderRow -> typeDetailActionHeader
                is MonthHeaderRow -> typeMonthHeader
                is BillRow -> typeBillItem
                is GroupRow -> typeGroupItem
                else -> typeBillItem
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                typeDetailActionHeader -> DetailActionHeaderViewHolder(
                    inflater.inflate(R.layout.item_asset_detail_actions, parent, false)
                )
                typeMonthHeader -> MonthHeaderViewHolder(
                    inflater.inflate(R.layout.item_asset_month_header, parent, false)
                )
                typeGroupItem -> GroupViewHolder(inflater.inflate(R.layout.item_home_transaction, parent, false))
                else -> BillViewHolder(inflater.inflate(R.layout.item_home_transaction, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is DetailActionHeaderRow -> (holder as DetailActionHeaderViewHolder).bind()
                is MonthHeaderRow -> (holder as MonthHeaderViewHolder).bind(row, position)
                is BillRow -> (holder as BillViewHolder).bind(row, position)
                is GroupRow -> (holder as GroupViewHolder).bind(row, position)
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.isNotEmpty() && holder is BillViewHolder) {
                val row = rows.getOrNull(position) as? BillRow
                if (row != null) {
                    if (payloads.contains(PAYLOAD_MODE_CHANGE)) {
                        holder.updateMode(row.bill)
                        return
                    }
                    if (payloads.contains(PAYLOAD_SELECTION_CHANGE)) {
                        holder.updateSelection(row.bill)
                        return
                    }
                    if (payloads.contains(PAYLOAD_BALANCE_DISPLAY_CHANGE)) {
                        holder.updateBalanceAfter(row.bill)
                        return
                    }
                }
            }
            if (payloads.isNotEmpty() && holder is GroupViewHolder) {
                val row = rows.getOrNull(position) as? GroupRow
                if (row != null) {
                    if (payloads.contains(PAYLOAD_MODE_CHANGE) || payloads.contains(PAYLOAD_SELECTION_CHANGE)) {
                        holder.updateMode(row)
                        return
                    }
                    if (payloads.contains(PAYLOAD_BALANCE_DISPLAY_CHANGE)) {
                        holder.updateBalanceAfter(row)
                        return
                    }
                }
            }
            if (payloads.isNotEmpty() && holder is MonthHeaderViewHolder) {
                if (payloads.contains(PAYLOAD_MODE_CHANGE)) {
                    holder.updateMode(position)
                    return
                }
                if (payloads.contains(PAYLOAD_SELECTION_CHANGE) || payloads.contains(PAYLOAD_HEADER_SELECTION_CHANGE)) {
                    holder.updateSelection(position)
                    return
                }
            }
            if (payloads.isNotEmpty() && holder is DetailActionHeaderViewHolder) {
                if (payloads.contains(PAYLOAD_DETAIL_HEADER_CHANGE) || payloads.contains(PAYLOAD_MODE_CHANGE)) {
                    holder.bind()
                    return
                }
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun getItemCount(): Int = rows.size

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is BillViewHolder) {
                holder.cancelIconLoad()
            }
            super.onViewRecycled(holder)
        }

        inner class DetailActionHeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val tvCreditCycleSummary = v.findViewById<TextView>(R.id.tv_credit_cycle_summary)
            private val tvAssetRemark = v.findViewById<TextView>(R.id.tv_asset_remark)
            private val tvInvestmentSummary = v.findViewById<TextView>(R.id.tv_investment_summary)
            private val tvInvestmentManage = v.findViewById<TextView>(R.id.tv_investment_manage)

            fun bind() {
                tvCreditCycleSummary.text = creditCycleSummaryText.orEmpty()
                tvCreditCycleSummary.visibility =
                    if (creditCycleSummaryText.isNullOrBlank()) View.GONE else View.VISIBLE

                tvAssetRemark.text = assetDetailRemarkText
                tvAssetRemark.visibility = if (assetDetailRemarkText.isBlank()) View.GONE else View.VISIBLE

                val isInvestment = currentAsset?.assetCategory == Asset.CATEGORY_INVESTMENT
                tvInvestmentSummary.text = investmentSummaryText.orEmpty()
                tvInvestmentSummary.visibility =
                    if (isInvestment && !investmentSummaryText.isNullOrBlank()) View.VISIBLE else View.GONE
                tvInvestmentManage.visibility = if (isInvestment) View.VISIBLE else View.GONE
                tvInvestmentManage.setOnClickListener { openInvestmentLotManager() }

                val hasContent = !creditCycleSummaryText.isNullOrBlank() ||
                    assetDetailRemarkText.isNotBlank() || isInvestment
                val lp = itemView.layoutParams as RecyclerView.LayoutParams
                if (hasContent) {
                    itemView.visibility = View.VISIBLE
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    lp.topMargin = itemView.resources.getDimensionPixelSize(R.dimen.space_8)
                } else {
                    itemView.visibility = View.GONE
                    lp.height = 0
                    lp.topMargin = 0
                }
                itemView.layoutParams = lp
            }
        }

        inner class MonthHeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val cbSelect = v.findViewById<CheckBox>(R.id.cb_select_section)
            private val tvMonth = v.findViewById<TextView>(R.id.tv_month_title)
            private val tvSummary = v.findViewById<TextView>(R.id.tv_month_summary)

            init {
                itemView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION || !isMultiSelectMode) return@setOnClickListener
                    val (allSelected, _) = sectionSelectionState(pos)
                    setSectionSelection(pos, checked = !allSelected)
                }
            }

            fun bind(header: MonthHeaderRow, position: Int) {
                val symbol = CurrencyManager.getSymbol(currentAsset?.currency ?: "CNY")
                tvMonth.text = header.monthLabel
                tvSummary.text = "\u6D41\u5165:${symbol}${String.format(Locale.getDefault(), "%.2f", header.inflow)}\n\u6D41\u51FA:${symbol}${String.format(Locale.getDefault(), "%.2f", header.outflow)}"
                updateMode(position)
            }

            fun updateMode(position: Int) {
                cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
                updateSelection(position)
            }

            fun updateSelection(position: Int) {
                val (allSelected, partiallySelected) = sectionSelectionState(position)
                cbSelect.isChecked = allSelected
                cbSelect.alpha = if (partiallySelected) 0.55f else 1f
            }
        }

        inner class BillViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val tvCategory = v.findViewById<TextView>(R.id.tv_bill_category)
            private val tvDetail = v.findViewById<TextView>(R.id.tv_bill_detail)
            private val tvAmount = v.findViewById<TextView>(R.id.tv_bill_amount)
            private val tvAsset = v.findViewById<TextView>(R.id.tv_bill_asset)
            private val tvTime = v.findViewById<TextView>(R.id.tv_bill_time)
            private val ivIcon = v.findViewById<ImageView>(R.id.iv_bill_category_icon)
            private val iconContainer = v.findViewById<View>(R.id.layout_icon_container)
            private val cbSelect = v.findViewById<android.widget.CheckBox>(R.id.cb_bill_select)
            private var iconLoadJob: Job? = null
            private var boundBillId: Long = -1L

            fun cancelIconLoad() {
                iconLoadJob?.cancel()
                iconLoadJob = null
                if (!isFinishing && !isDestroyed) {
                    Glide.with(this@AssetDetailActivity).clear(ivIcon)
                }
            }

            fun updateMode(bill: Bill) {
                cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
                cbSelect.isChecked = selectedBills.contains(bill)
            }

            fun updateSelection(bill: Bill) {
                cbSelect.isChecked = selectedBills.contains(bill)
            }

            fun updateBalanceAfter(bill: Bill) {
                val asset = currentAsset
                val refundStatus = refundStatusText(bill)
                val balanceLabel = if (
                    asset != null && AssetBillBalanceDisplay.shouldShowBalanceForBill(
                        asset,
                        bill.time,
                        allAssetBills,
                        assetId,
                        asset.name
                    )
                ) {
                    balanceAfterByBillId[bill.id]?.let { balanceAfter ->
                        AssetBillBalanceHistory.formatBalanceAfterLabel(balanceAfter, asset.currency)
                    }
                } else {
                    null
                }
                val labels = listOfNotNull(refundStatus, balanceLabel)
                if (labels.isEmpty()) {
                    tvAsset.visibility = View.GONE
                    return
                }
                tvAsset.text = labels.joinToString(" · ")
                tvAsset.visibility = View.VISIBLE
            }

            fun bind(row: BillRow, position: Int) {
                val bill = row.bill
                boundBillId = bill.id
                cancelIconLoad()
                val isTransfer = bill.type == Bill.TYPE_TRANSFER
                val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
                val isRefund = bill.subType == Bill.SUBTYPE_REFUND
                val showCategoryIcon = Prefs.isShowBillCategoryIcon(itemView.context)
                val showFullCategory = Prefs.isShowBillFullCategory(itemView.context)
                val remarkPriority = Prefs.isBillRemarkPriority(itemView.context)
                val baseCategory = stripRefundPrefix(bill.categoryName)
                val displayCurrency = currentAsset?.currency ?: bill.currency
                val symbol = CurrencyManager.getSymbol(displayCurrency)
                val displayAmount = amountForAssetRow(bill, assetId)

                bindRowBackground(itemView, position)
                val density = itemView.resources.displayMetrics.density
                itemView.setPaddingRelative(
                    ((if (row.inGroup) 38 else 14) * density).toInt(),
                    itemView.paddingTop,
                    itemView.paddingEnd,
                    itemView.paddingBottom
                )
                iconContainer.setBackgroundResource(
                    when {
                        !isRefund && bill.type == Bill.TYPE_EXPENSE -> R.drawable.bg_circle_expense_soft
                        !isRefund && bill.type == Bill.TYPE_INCOME -> R.drawable.bg_circle_income_soft
                        else -> R.drawable.bg_circle_soft
                    }
                )
                updateMode(bill)

                val categoryText = when {
                    isRepayment -> "\u8FD8\u6B3E"
                    isTransfer -> "\u8F6C\u8D26"
                    else -> BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, showFullCategory).ifEmpty { "\u672A\u5206\u7C7B" }
                }
                val (primaryText, secondaryText) = BillDisplayFormatter.resolvePrimarySecondaryText(
                    categoryText = categoryText,
                    remarkText = bill.remark,
                    suffixText = dateFormat.format(Date(bill.time)),
                    remarkPriority = remarkPriority
                )
                tvCategory.text = primaryText
                tvCategory.setTextColor(if (isRefund) Color.parseColor("#8E98A3") else Color.parseColor("#333333"))

                val refundAmount = refundedAmountInBillCurrency(bill)
                tvAmount.text = if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmount > 0.0) {
                    "-${BillDisplayFormatter.formatMoney(AssetBillBalanceHistory.amountAtTransactionTime(bill), bill.currency)}"
                } else {
                    val amountPrefix = when {
                        isRefund -> ""
                        bill.type == Bill.TYPE_EXPENSE -> "-"
                        bill.type == Bill.TYPE_INCOME -> "+"
                        else -> ""
                    }
                    "$amountPrefix$symbol${String.format(Locale.getDefault(), "%.2f", displayAmount)}"
                }
                tvAmount.setTextColor(
                    when {
                        bill.excludeFromStats -> Color.parseColor("#9AA1AA")
                        isRefund -> Color.parseColor("#9AA1AA")
                        bill.type == Bill.TYPE_EXPENSE -> BillColors.expense
                        bill.type == Bill.TYPE_INCOME -> BillColors.income
                        else -> Color.parseColor("#757575")
                    }
                )

                tvTime.visibility = View.GONE
                tvDetail.text = secondaryText.ifBlank { dateFormat.format(Date(bill.time)) }
                tvDetail.visibility = View.VISIBLE
                tvDetail.setTextColor(if (isRefund) Color.parseColor("#A1A8AF") else Color.parseColor("#999999"))
                tvAsset.setTextColor(if (isRefund) Color.parseColor("#A1A8AF") else Color.parseColor("#999999"))

                updateBalanceAfter(bill)

                if (!showCategoryIcon) {
                    iconContainer.setBackgroundColor(Color.TRANSPARENT)
                    iconContainer.layoutParams = iconContainer.layoutParams.apply {
                        val widthPx = (itemView.resources.displayMetrics.density * 10).toInt()
                        val heightPx = (itemView.resources.displayMetrics.density * 44).toInt()
                        width = widthPx
                        height = heightPx
                    }
                    ivIcon.clearColorFilter()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ivIcon.imageTintList = null
                    }
                    ivIcon.layoutParams = ivIcon.layoutParams.apply {
                        val px = (ivIcon.resources.displayMetrics.density * 6).toInt()
                        width = px
                        height = px
                    }
                    ivIcon.setImageResource(
                        when (bill.type) {
                            Bill.TYPE_EXPENSE -> R.drawable.bg_bill_dot_expense
                            Bill.TYPE_INCOME -> R.drawable.bg_bill_dot_income
                            else -> R.drawable.bg_bill_dot_neutral
                        }
                    )
                } else {
                    iconContainer.layoutParams = iconContainer.layoutParams.apply {
                        val widthPx = (itemView.resources.displayMetrics.density * 44).toInt()
                        val heightPx = (itemView.resources.displayMetrics.density * 44).toInt()
                        width = widthPx
                        height = heightPx
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ivIcon.imageTintList = null
                    }
                    val iconTint = when {
                        isRefund -> Color.parseColor("#8E98A3")
                        bill.type == Bill.TYPE_EXPENSE -> BillColors.expense
                        bill.type == Bill.TYPE_INCOME -> BillColors.income
                        else -> Color.parseColor("#9E9E9E")
                    }
                    ivIcon.setColorFilter(iconTint)
                    ivIcon.layoutParams = ivIcon.layoutParams.apply {
                        val px = (ivIcon.resources.displayMetrics.density * 21).toInt()
                        width = px
                        height = px
                    }

                    val iconName = if (isRefund) baseCategory else bill.categoryName
                    val iconType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
                    ivIcon.setImageResource(R.mipmap.ic_launcher)
                    iconLoadJob = lifecycleScope.launch {
                        val iconUrl = withContext(Dispatchers.IO) {
                            CategoryIconHelper.findCategoryIcon(applicationContext, iconName, iconType)
                        }
                        if (
                            iconUrl.isNotEmpty() &&
                            boundBillId == bill.id &&
                            bindingAdapterPosition != RecyclerView.NO_POSITION &&
                            !isFinishing &&
                            !isDestroyed
                        ) {
                            Glide.with(this@AssetDetailActivity)
                                .load(iconUrl)
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                .into(ivIcon)
                        }
                    }
                }

                itemView.setOnClickListener {
                    if (isMultiSelectMode) {
                        if (selectedBills.contains(bill)) {
                            selectedBills.remove(bill)
                        } else {
                            selectedBills.add(bill)
                        }
                        notifySelectionChanged()
                    } else {
                        onBillItemClick?.invoke(bill)
                    }
                }
                itemView.setOnLongClickListener {
                    enterOrExtendSelection(listOf(bill))
                    true
                }
            }
        }

        private fun enterOrExtendSelection(bills: List<Bill>) {
            if (!isMultiSelectMode) {
                isMultiSelectMode = true
                selectedBills.clear()
                selectedBills.addAll(bills)
                notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
                onSelectionChanged?.invoke(selectedBills.size)
            } else {
                selectedBills.addAll(bills)
                notifySelectionChanged()
            }
        }

        inner class GroupViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val tvCategory = v.findViewById<TextView>(R.id.tv_bill_category)
            private val tvDetail = v.findViewById<TextView>(R.id.tv_bill_detail)
            private val tvAmount = v.findViewById<TextView>(R.id.tv_bill_amount)
            private val tvAsset = v.findViewById<TextView>(R.id.tv_bill_asset)
            private val ivIcon = v.findViewById<ImageView>(R.id.iv_bill_category_icon)
            private val iconContainer = v.findViewById<View>(R.id.layout_icon_container)
            private val cbSelect = v.findViewById<CheckBox>(R.id.cb_bill_select)

            fun updateMode(row: GroupRow) {
                cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
                cbSelect.isChecked = row.bills.all { selectedBills.contains(it) }
            }

            /** 对账看的是这一笔扣款后的余额，即组内最晚入账那一条之后的余额。 */
            fun updateBalanceAfter(row: GroupRow) {
                val asset = currentAsset
                val latest = row.bills.first()
                val label = if (
                    asset != null && AssetBillBalanceDisplay.shouldShowBalanceForBill(
                        asset,
                        latest.time,
                        allAssetBills,
                        assetId,
                        asset.name
                    )
                ) {
                    balanceAfterByBillId[latest.id]?.let {
                        AssetBillBalanceHistory.formatBalanceAfterLabel(it, asset.currency)
                    }
                } else {
                    null
                }
                tvAsset.text = label.orEmpty()
                tvAsset.visibility = if (label == null) View.GONE else View.VISIBLE
            }

            fun bind(row: GroupRow, position: Int) {
                bindRowBackground(itemView, position)
                val density = itemView.resources.displayMetrics.density
                itemView.setPaddingRelative(
                    (14 * density).toInt(),
                    itemView.paddingTop,
                    itemView.paddingEnd,
                    itemView.paddingBottom
                )
                var inflow = 0.0
                var outflow = 0.0
                row.bills.forEach { bill ->
                    val (billInflow, billOutflow) = assetFlow(bill)
                    inflow += billInflow
                    outflow += billOutflow
                }
                val net = inflow - outflow
                val isOutflow = net < 0
                val currency = currentAsset?.currency ?: row.bills.first().currency
                tvAmount.text = (if (isOutflow) "-" else "+") +
                    CurrencyManager.getSymbol(currency) +
                    String.format(Locale.getDefault(), "%.2f", kotlin.math.abs(net))
                tvAmount.setTextColor(if (isOutflow) BillColors.expense else BillColors.income)

                val categories = row.bills
                    .map { BillDisplayFormatter.formatCategoryByPreference(it.categoryName, false) }
                    .filter { it.isNotBlank() }
                    .distinct()
                tvCategory.text = categories.take(3).joinToString("、") + if (categories.size > 3) "等" else ""
                tvCategory.setTextColor(Color.parseColor("#333333"))

                val expanded = row.groupId in expandedGroupIds
                tvDetail.text = getString(
                    R.string.payment_group_detail_fmt,
                    row.bills.size,
                    dateFormat.format(Date(row.bills.first().time))
                ) + if (expanded) " ▴" else " ▾"
                tvDetail.visibility = View.VISIBLE

                iconContainer.setBackgroundResource(
                    if (isOutflow) R.drawable.bg_circle_expense_soft else R.drawable.bg_circle_income_soft
                )
                iconContainer.layoutParams = iconContainer.layoutParams.apply {
                    width = (44 * density).toInt()
                    height = (44 * density).toInt()
                }
                ivIcon.layoutParams = ivIcon.layoutParams.apply {
                    width = (21 * density).toInt()
                    height = (21 * density).toInt()
                }
                ivIcon.setImageResource(R.drawable.ic_insight_receipt)
                ivIcon.setColorFilter(if (isOutflow) BillColors.expense else BillColors.income)

                updateMode(row)
                updateBalanceAfter(row)

                itemView.setOnClickListener {
                    if (isMultiSelectMode) {
                        if (row.bills.all { selectedBills.contains(it) }) {
                            selectedBills.removeAll(row.bills.toSet())
                        } else {
                            selectedBills.addAll(row.bills)
                        }
                        notifySelectionChanged()
                    } else {
                        toggleGroupExpanded(row.groupId)
                    }
                }
                itemView.setOnLongClickListener {
                    enterOrExtendSelection(row.bills)
                    true
                }
            }
        }

        private fun stripRefundPrefix(categoryName: String): String {
            return categoryName
                .removePrefix("\u9000\u6B3E\u00B7")
                .removePrefix("\u9000\u6B3E\uFF1A")
                .trim()
        }
    }

    private fun formatMoney(amount: Double, currency: String): String =
        BillDisplayFormatter.formatMoney(amount, currency)

    private fun amountInAssetCurrency(bill: Bill, ownerAssetId: Long, isInflow: Boolean): Double {
        val assetCurrency = currentAsset?.currency ?: bill.currency
        return when {
            bill.type == Bill.TYPE_EXPENSE && bill.accountId == ownerAssetId -> {
                val baseExpenseAmount = baseOriginalAmount(bill)
                BillAssetImpactService.convertAmountBetweenCurrencies(baseExpenseAmount, bill.currency, assetCurrency)
            }

            bill.type == Bill.TYPE_TRANSFER && isInflow && bill.toAccountId == ownerAssetId -> {
                bill.amount * bill.exchangeRate
            }

            bill.type == Bill.TYPE_TRANSFER && !isInflow && bill.accountId == ownerAssetId -> {
                val sourceAmount = BillAssetImpactService.convertAmountBetweenCurrencies(bill.amount, bill.currency, assetCurrency)
                val feeInSource = if (bill.fee > 0.0) {
                    BillAssetImpactService.convertAmountBetweenCurrencies(bill.fee, bill.currency, assetCurrency)
                } else {
                    0.0
                }
                sourceAmount + feeInSource
            }

            else -> BillAssetImpactService.convertAmountBetweenCurrencies(bill.amount, bill.currency, assetCurrency)
        }
    }

    private fun amountForAssetRow(bill: Bill, ownerAssetId: Long): Double {
        val isInflow = when {
            bill.subType == Bill.SUBTYPE_REFUND -> true
            bill.type == Bill.TYPE_INCOME -> true
            bill.type == Bill.TYPE_TRANSFER -> bill.toAccountId == ownerAssetId && bill.accountId != ownerAssetId
            else -> false
        }
        return amountInAssetCurrency(bill, ownerAssetId, isInflow)
    }

    /** 一条账单对本资产造成的（流入, 流出），资产币种；月度小计、多选合计、扣款组合计共用。 */
    private fun assetFlow(bill: Bill): Pair<Double, Double> = when {
        bill.subType == Bill.SUBTYPE_REFUND -> amountInAssetCurrency(bill, assetId, isInflow = true) to 0.0
        bill.type == Bill.TYPE_EXPENSE -> 0.0 to amountInAssetCurrency(bill, assetId, isInflow = false)
        bill.type == Bill.TYPE_INCOME -> amountInAssetCurrency(bill, assetId, isInflow = true) to 0.0
        bill.type == Bill.TYPE_TRANSFER -> {
            val isTransferOut = bill.accountId == assetId && bill.toAccountId != assetId
            val isTransferIn = bill.toAccountId == assetId && bill.accountId != assetId
            val inflow = if (isTransferIn) amountInAssetCurrency(bill, assetId, isInflow = true) else 0.0
            val outflow = if (isTransferOut) amountInAssetCurrency(bill, assetId, isInflow = false) else 0.0
            inflow to outflow
        }
        else -> 0.0 to 0.0
    }

    private fun baseOriginalAmount(bill: Bill): Double {
        return if (bill.originalAmount > 0.0) {
            kotlin.math.max(bill.originalAmount, bill.amount)
        } else {
            bill.amount
        }
    }

    private fun detailOwnerAssetId(bill: Bill): Long {
        return when {
            bill.subType == Bill.SUBTYPE_REFUND -> bill.accountId ?: assetId
            bill.type == Bill.TYPE_TRANSFER -> when {
                bill.accountId == assetId || bill.toAccountId == assetId -> assetId
                bill.accountId != null -> bill.accountId
                else -> bill.toAccountId ?: assetId
            }
            else -> bill.accountId ?: assetId
        }
    }

    private fun refundedAmountInBillCurrency(bill: Bill): Double {
        if (bill.type != Bill.TYPE_EXPENSE || bill.subType == Bill.SUBTYPE_REFUND) return 0.0
        return (baseOriginalAmount(bill) - bill.amount).coerceAtLeast(0.0)
    }

    private fun refundStatusText(bill: Bill): String? {
        val refundedAmount = refundedAmountInBillCurrency(bill)
        if (refundedAmount <= 0.0) return null
        return if (refundedAmount >= baseOriginalAmount(bill) - 1e-9) {
            "已全额退款"
        } else {
            "已退款${formatMoney(refundedAmount, bill.currency)}"
        }
    }

    private fun buildAssetDetailFormula(bill: Bill, ownerAssetId: Long): String? {
        return when {
            bill.type == Bill.TYPE_EXPENSE && bill.accountId == ownerAssetId -> {
                val refundedAmount = refundedAmountInBillCurrency(bill)
                if (refundedAmount > 0.0) {
                    "退款${formatMoney(refundedAmount, bill.currency)}，实际支出${formatMoney(bill.amount, bill.currency)}"
                } else {
                    buildCrossCurrencyDetailFormula(bill, "CNY")
                }
            }

            else -> buildCrossCurrencyDetailFormula(bill, "CNY")
        }
    }

    private fun buildCrossCurrencyAmountFormula(bill: Bill, accountCurrency: String): String? =
        BillDisplayFormatter.buildCrossCurrencyAmountFormula(bill, accountCurrency)

    private fun buildCrossCurrencyDetailFormula(bill: Bill, targetCurrency: String = "CNY"): String? =
        BillDisplayFormatter.buildCrossCurrencyDetailFormula(bill, targetCurrency)
}
