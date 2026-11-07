package com.taostudio.tapaccounting.ui.main.home

import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.room.InvalidationTracker
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.sync.SharedSyncEngine

internal class HomeRefreshController(
    private val fragment: Fragment,
    private val swipeRefreshLayout: SwipeRefreshLayout,
    private val homeViewModel: HomeViewModel,
    private val getSelectedBookName: () -> String,
    private val getSelectedYear: () -> Int,
    private val getSelectedMonth: () -> Int,
    private val getCurrentTimeRange: () -> Int,
    private val getCurrentType: () -> Int,
    private val onUpdateHomeFabVisibility: () -> Unit,
) {
    private data class RefreshSnapshot(
        val count: Int,
        val signature: Long
    )

    private var refreshTimeoutJob: Job? = null
    private var syncJob: Job? = null
    private var isPullRefreshing = false
    private var pullRefreshSharedSync = false
    private var pullRefreshSyncFailed = false
    private var pullRefreshBeforeSnapshot: RefreshSnapshot? = null
    private var billsInvalidationObserver: InvalidationTracker.Observer? = null
    private var billsInvalidationDebounceJob: Job? = null

    fun setupPullToRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            swipeRefreshLayout.isRefreshing = true
            isPullRefreshing = true
            pullRefreshSharedSync = false
            pullRefreshSyncFailed = false
            pullRefreshBeforeSnapshot = buildRefreshSnapshot(homeViewModel.uiState.value.monthlyBills)
            syncJob?.cancel()
            syncJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
                val bookName = getSelectedBookName()
                val (isShared, syncError) = withContext(Dispatchers.IO) {
                    val context = fragment.requireContext().applicationContext
                    val db = AppDatabase.getDatabase(context)
                    val ledgerId = db.sharedLedgerDao().getByBookName(bookName)?.id
                    ledgerId?.let { id -> true to runCatching {
                        SharedSyncEngine(context, db).syncLedger(id)
                    }.exceptionOrNull() } ?: (false to null)
                }
                if (!fragment.isAdded) return@launch
                pullRefreshSharedSync = isShared
                pullRefreshSyncFailed = syncError != null
                if (syncError != null) {
                    Toast.makeText(fragment.requireContext(), "同步失败：${syncError.message ?: "请稍后重试"}", Toast.LENGTH_LONG).show()
                }
                homeViewModel.forceReload(
                    bookName = bookName,
                    year = getSelectedYear(),
                    month = getSelectedMonth(),
                    timeRange = getCurrentTimeRange(),
                    type = getCurrentType(),
                    isChartHidden = !Prefs.isShowHomeTrendCard(fragment.requireContext())
                )
            }

            refreshTimeoutJob?.cancel()
            refreshTimeoutJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
                delay(30_000)
                if (fragment.isAdded && swipeRefreshLayout.isRefreshing) {
                    swipeRefreshLayout.isRefreshing = false
                    Log.d("HomePerf", "pull refresh timeout fallback: stop spinner")
                }
            }
            onUpdateHomeFabVisibility()
        }
    }

    fun onStateCollected(monthlyBills: List<Bill>, isLoading: Boolean) {
        if (syncJob?.isActive == true) return
        swipeRefreshLayout.isRefreshing = false
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        if (isPullRefreshing && !isLoading) {
            showPullRefreshFeedback(monthlyBills)
        }
    }

    fun resetRefreshState() {
        swipeRefreshLayout.isRefreshing = false
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        isPullRefreshing = false
        pullRefreshSharedSync = false
        pullRefreshSyncFailed = false
        pullRefreshBeforeSnapshot = null
    }

    fun observeBillTableChanges() {
        val appContext = fragment.requireContext().applicationContext
        val db = AppDatabase.getDatabase(appContext)
        billsInvalidationObserver?.let { db.invalidationTracker.removeObserver(it) }
        val observer = object : InvalidationTracker.Observer("bills") {
            override fun onInvalidated(tables: Set<String>) {
                // 兜底刷新：Room Flow 正常情况下会自动推送，这里只做“漏网之鱼”的补偿。
                // 对于批量写入/连续更新，做 debounce 合并，避免频繁重启 flow。
                fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (!fragment.isAdded) return@launch
                    billsInvalidationDebounceJob?.cancel()
                    billsInvalidationDebounceJob = fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        delay(260)
                        if (!fragment.isAdded) return@launch
                        // Room Flow 刚刚已经推过一次（删除/编辑账单时几乎总会这样）：
                        // 再强制重载只会让列表重建两次，看起来就是「删完闪一下 / 刷新一下」。
                        val age = homeViewModel.billEmissionAgeMs()
                        if (age < 700L) {
                            Log.d("HomePerf", "skip fallback reload: bills flow emitted ${age}ms ago")
                            return@launch
                        }
                        homeViewModel.forceReload(
                            bookName = getSelectedBookName(),
                            year = getSelectedYear(),
                            month = getSelectedMonth(),
                            timeRange = getCurrentTimeRange(),
                            type = getCurrentType(),
                            isChartHidden = !Prefs.isShowHomeTrendCard(appContext)
                        )
                    }
                }
            }
        }
        billsInvalidationObserver = observer
        db.invalidationTracker.addObserver(observer)
    }

    fun onDestroyView() {
        syncJob?.cancel()
        syncJob = null
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        billsInvalidationDebounceJob?.cancel()
        billsInvalidationDebounceJob = null
        val appContext = fragment.context?.applicationContext
        if (appContext != null) {
            val db = AppDatabase.getDatabase(appContext)
            billsInvalidationObserver?.let { observer ->
                db.invalidationTracker.removeObserver(observer)
            }
        }
        billsInvalidationObserver = null
    }

    private fun buildRefreshSnapshot(bills: List<Bill>): RefreshSnapshot {
        var signature = 1125899906842597L
        bills.forEach { bill ->
            signature = signature * 31 + bill.id
            signature = signature * 31 + bill.time
            signature = signature * 31 + bill.type.toLong()
            signature = signature * 31 + bill.subType.toLong()
            signature = signature * 31 + java.lang.Double.doubleToLongBits(bill.amount)
            signature = signature * 31 + java.lang.Double.doubleToLongBits(bill.exchangeRate)
            signature = signature * 31 + bill.categoryName.hashCode().toLong()
            signature = signature * 31 + bill.accountName.hashCode().toLong()
            signature = signature * 31 + bill.remark.hashCode().toLong()
        }
        return RefreshSnapshot(
            count = bills.size,
            signature = signature
        )
    }

    private fun showPullRefreshFeedback(latestBills: List<Bill>) {
        val before = pullRefreshBeforeSnapshot
        val after = buildRefreshSnapshot(latestBills)
        val changed = before == null || before != after
        if (!pullRefreshSyncFailed) {
            val message = when {
                pullRefreshSharedSync -> "同步成功"
                changed -> "已刷新最新账单"
                else -> "已经是最新了"
            }
            Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_SHORT).show()
        }
        isPullRefreshing = false
        pullRefreshSharedSync = false
        pullRefreshSyncFailed = false
        pullRefreshBeforeSnapshot = null
    }
}
