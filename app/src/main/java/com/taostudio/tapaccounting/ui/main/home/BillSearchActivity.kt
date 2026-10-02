package com.taostudio.tapaccounting.ui.main.home

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.BillMoveTargetResolver
import com.taostudio.tapaccounting.ui.dialog.BillMoveDialog
import com.taostudio.tapaccounting.viewscope.LedgerMemberScope
import com.taostudio.tapaccounting.viewscope.LedgerViewScopeStore
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import java.util.Locale

class BillSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_BOOK = "extra_source_book"
    }

    private lateinit var etSearch: EditText
    private lateinit var rvResults: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvScope: TextView
    private lateinit var layoutMultiSelectActions: View
    private lateinit var btnMsDelete: TextView
    private lateinit var btnMsExcludeStats: TextView

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val adapter = HomeAdapter().apply { showChart = false }
    private var allBills: List<Bill> = emptyList()
    private var searchJob: Job? = null
    private var hasResumedOnce = false
    private var viewScope: ResolvedLedgerViewScope? = null

    private val sourceBookName by lazy {
        BookAccountManager.normalizeBookName(
            intent.getStringExtra(EXTRA_SOURCE_BOOK).orEmpty().ifBlank { BookAccountManager.ALL_BOOK }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_search)

        etSearch = findViewById(R.id.et_bill_search)
        rvResults = findViewById(R.id.rv_bill_search_results)
        tvEmpty = findViewById(R.id.tv_bill_search_empty)
        tvScope = findViewById(R.id.tv_bill_search_scope)
        layoutMultiSelectActions = findViewById(R.id.layout_multi_select_actions)
        btnMsDelete = findViewById(R.id.btn_ms_delete)
        btnMsExcludeStats = findViewById(R.id.btn_ms_exclude_stats)

        findViewById<View>(R.id.btn_bill_search_back).setOnClickListener { handleBackPressed() }
        onBackPressedDispatcher.addCallback(this) {
            handleBackPressed()
        }

        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter

        setupMultiSelectActions()

        adapter.onBillItemClick = { bill ->
            if (!isFinishing) {
                BillDetailSheetHelper.showBillDetailSheet(
                    context = this,
                    lifecycleOwner = this,
                    bill = bill,
                    onBillChanged = { loadAllBills() }
                )
            }
        }
        tvScope.text = "范围：加载中"

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty().trim()
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(120)
                    applyFilter(keyword)
                }
            }
        })

        loadAllBills()

        etSearch.post {
            etSearch.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasResumedOnce) {
            // 独立详情页编辑/删除后返回时，重新读取当前搜索结果。
            loadAllBills()
        } else {
            hasResumedOnce = true
        }
    }

    private fun handleBackPressed() {
        if (adapter.isMultiSelectMode) {
            adapter.clearSelection()
        } else {
            finish()
        }
    }

    private fun setupMultiSelectActions() {
        adapter.onSelectionChanged = { count ->
            layoutMultiSelectActions.visibility = if (adapter.isMultiSelectMode) View.VISIBLE else View.GONE
            btnMsDelete.text = if (count > 0) "删除($count)" else "删除"
            com.taostudio.tapaccounting.ui.common.BillStatsMultiSelect.refreshButton(
                btnMsExcludeStats,
                adapter.selectedBills.toList()
            )
        }

        findViewById<View>(R.id.btn_ms_cancel).setOnClickListener {
            adapter.clearSelection()
        }
        findViewById<View>(R.id.btn_ms_select_all).setOnClickListener {
            val allCount = adapter.items.count {
                it is HomeAdapter.ListItem.Item &&
                    !it.displayBill.isDeprecated &&
                    adapter.isOwnedByLocal(it.displayBill.bill)
            }
            if (allCount > 0 && adapter.selectedBills.size >= allCount) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }
        findViewById<View>(R.id.btn_ms_move_book).setOnClickListener {
            val billsToMove = adapter.getSelectedBills()
            if (billsToMove.isEmpty()) return@setOnClickListener
            showMoveToBookDialog(billsToMove)
        }
        btnMsExcludeStats.setOnClickListener {
            val bills = adapter.getSelectedBills()
            if (bills.isEmpty()) return@setOnClickListener
            com.taostudio.tapaccounting.ui.common.BillStatsMultiSelect.apply(
                context = this,
                scope = lifecycleScope,
                bills = bills
            ) { exclude ->
                val ids = bills.map { it.id }.toSet()
                allBills = allBills.map { if (it.id in ids) it.copy(excludeFromStats = exclude) else it }
                // 保留多选模式，只清空勾选，方便连续处理多批。
                adapter.deselectAll()
                applyFilter(etSearch.text?.toString().orEmpty().trim())
            }
        }
        btnMsDelete.setOnClickListener {
            val billsToDelete = adapter.getSelectedBills()
            if (billsToDelete.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                com.taostudio.tapaccounting.logic.BillDeleteHelper.deleteBillsAndRevertBalance(db, billsToDelete)
                val deletedIds = billsToDelete.map { it.id }.toSet()
                allBills = allBills.filterNot { it.id in deletedIds }
                adapter.clearSelection()
                applyFilter(etSearch.text?.toString().orEmpty().trim())
                Toast.makeText(
                    this@BillSearchActivity,
                    "已删除 ${billsToDelete.size} 条账单",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showMoveToBookDialog(bills: List<Bill>) {
        val scope = viewScope
        if (scope == null) {
            Toast.makeText(this, "账本列表尚未加载，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        val targets = BillMoveTargetResolver.resolve(
            viewScope = scope,
            selectedBillBookNames = bills.map { it.bookName }
        )
        BillMoveDialog.show(
            activity = this,
            bills = bills,
            targets = targets
        ) { targetBook ->
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    com.taostudio.tapaccounting.data.sync.SharedMutationHooks.moveBills(db, bills, targetBook)
                }.onFailure { error ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BillSearchActivity, error.message ?: "移动失败", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    adapter.clearSelection()
                    loadAllBills()
                    Toast.makeText(
                        this@BillSearchActivity,
                        "已将 ${bills.size} 条账单移动到「$targetBook」",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun loadAllBills() {
        lifecycleScope.launch {
            val bills = withContext(Dispatchers.IO) {
                val scope = LedgerViewScopeStore.resolve(applicationContext, db, sourceBookName)
                scope to db.billDao().getAllBillsList()
            }
            viewScope = bills.first
            val scope = bills.first
            tvScope.text = "范围：${scope.displayLabel}"
            adapter.setViewContext(
                contextsByBookName = scope.memberContextsByBookName,
                showMembers = scope.scope.members == LedgerMemberScope.EVERYONE,
                showBookNames = false
            )
            adapter.detailSuffixProvider = if (scope.isAggregate) {
                { bill -> "账本：${BookAccountManager.normalizeBookName(bill.bookName)}" }
            } else null
            allBills = bills.second
                .asSequence()
                .filter(scope::includes)
                .sortedWith(compareByDescending<Bill> { it.time }.thenByDescending { it.id })
                .toList()
            applyFilter(etSearch.text?.toString().orEmpty().trim())
        }
    }

    private fun applyFilter(keyword: String) {
        val filtered = filterBillsByKeyword(allBills, keyword)
        adapter.submitList(filtered)
        val empty = filtered.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rvResults.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun filterBillsByKeyword(source: List<Bill>, keywordRaw: String): List<Bill> {
        val keyword = keywordRaw.trim().lowercase(Locale.getDefault())
        if (keyword.isBlank()) return source
        val normalizedKeyword = keyword.replace(',', '.')

        return source.filter { bill ->
            val textMatched = listOf(
                bill.remark,
                bill.categoryName,
                BillDisplayFormatter.stripRefundPrefix(bill.categoryName),
                bill.accountName,
                bill.toAccountName,
                BookAccountManager.normalizeBookName(bill.bookName)
            ).any { value ->
                value.isNotBlank() && value.lowercase(Locale.getDefault()).contains(keyword)
            }
            if (textMatched) return@filter true

            val numericCandidates = listOf(
                String.format(Locale.US, "%.2f", bill.amount),
                bill.amount.toString(),
                String.format(Locale.US, "%.2f", bill.originalAmount),
                bill.originalAmount.toString(),
                String.format(Locale.US, "%.2f", bill.amount * bill.exchangeRate)
            )
            numericCandidates.any { candidate ->
                val value = candidate.lowercase(Locale.getDefault())
                value.contains(keyword) || value.contains(normalizedKeyword)
            }
        }
    }
}
