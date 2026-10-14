package com.taostudio.tapaccounting.ui.main.home

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.taostudio.tapaccounting.MainActivity
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.ui.common.MultiSelectBarSummary
import com.taostudio.tapaccounting.ui.common.StatusBarStyle
import com.taostudio.tapaccounting.ui.common.BillStatsMultiSelect
import com.taostudio.tapaccounting.ui.common.MultiSelectMotion
import com.taostudio.tapaccounting.ui.common.UiMotion.FabScrollBehavior

internal class HomeUiListController(
    private val fragment: Fragment,
    private val rvTransactions: RecyclerView,
    private val layoutEmptyView: View,
    private val layoutMultiSelectActions: View,
    private val tvMsSummary: TextView,
    private val btnMsDelete: View,
    private val btnMsExcludeStats: View,
    private val swipeRefreshLayout: SwipeRefreshLayout,
    private val cvChartContainer: View,
    private val layoutStickyTopBar: View,
    private val ivBookSwitcher: View,
    private val tvMonthSelector: View,
    private val ivCalendarView: View,
    private val ivSearchBill: View,
    private val layoutHeaderSummary: View,
    private val getHomeAppBar: () -> com.google.android.material.appbar.AppBarLayout?,
    private val getAppBarVerticalOffset: () -> Int,
    private val isBookDrawerOpen: () -> Boolean,
    private val getIsMultiSelectModeActive: () -> Boolean,
    private val setIsMultiSelectModeActive: (Boolean) -> Unit,
    private val getHomeAdapter: () -> HomeAdapter,
    private val setHomeAdapter: (HomeAdapter) -> Unit,
    private val homeViewModel: HomeViewModel,
    private val onShowBillDetailSheet: (com.taostudio.tapaccounting.data.local.entity.Bill) -> Unit,
    private val onRefreshAccountCurrencyCache: () -> Unit,
) {
    /**
     * FAB 的显隐交给共享策略（见 [FabScrollBehavior]），显示与否的最终判断仍在
     * [updateHomeFabVisibilityByDrawerState]：空态、账本抽屉、多选下它要继续隐藏。
     */
    private val fabScrollBehavior = FabScrollBehavior(
        hide = {
            (fragment.activity as? MainActivity)
                ?.findViewById<FloatingActionButton>(R.id.fab_add)?.hide()
        },
        show = { updateHomeFabVisibilityByDrawerState() },
    )

    fun setupRecyclerView() {
        val homeAdapter = homeViewModel.adapter
        setHomeAdapter(homeAdapter)

        val lm = LinearLayoutManager(fragment.context)
        lm.initialPrefetchItemCount = 12
        lm.recycleChildrenOnDetach = false
        rvTransactions.setHasFixedSize(false)
        rvTransactions.layoutManager = lm
        rvTransactions.adapter = homeAdapter
        rvTransactions.setItemViewCacheSize(36)
        (fragment.activity as? MainActivity)?.homeRecycledViewPool?.let {
            rvTransactions.setRecycledViewPool(it)
        }
        (rvTransactions.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        rvTransactions.itemAnimator = null

        homeAdapter.onBillItemClick = { bill ->
            onShowBillDetailSheet(bill)
        }

        homeAdapter.onSelectionChanged = { count ->
            if (homeAdapter.isMultiSelectMode) {
                setIsMultiSelectModeActive(true)
                MultiSelectMotion.animateIn(layoutMultiSelectActions)
                // 摘要行放在操作栏里（对齐资产详情页）：不依赖顶部 banner，滚动位置不影响展示
                tvMsSummary.text = MultiSelectBarSummary.build(
                    fragment.requireContext(),
                    homeAdapter.selectedBills.toList()
                )
                (btnMsDelete as TextView).text = fragment.getString(R.string.delete_label)
                BillStatsMultiSelect.refreshButton(
                    btnMsExcludeStats as TextView,
                    homeAdapter.selectedBills.toList()
                )
            } else {
                setIsMultiSelectModeActive(false)
                MultiSelectMotion.animateOut(layoutMultiSelectActions)
            }
            fabScrollBehavior.syncVisible()
        }

        rvTransactions.clearOnScrollListeners()
        rvTransactions.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!fragment.isAdded || !fragment.isVisible ||
                    layoutEmptyView.visibility == View.VISIBLE ||
                    homeAdapter.itemCount <= 1
                ) {
                    fabScrollBehavior.syncVisible()
                    return
                }
                fabScrollBehavior.onScroll(dy, atTop = !recyclerView.canScrollVertically(-1))
            }
        })
        homeAdapter.chartView = cvChartContainer
        homeAdapter.emptyView = layoutEmptyView
        onRefreshAccountCurrencyCache()

        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            val firstVisible = (rvTransactions.layoutManager as? LinearLayoutManager)
                ?.findFirstCompletelyVisibleItemPosition() ?: RecyclerView.NO_POSITION
            if (firstVisible != 0) return@setOnChildScrollUpCallback true
            if (getAppBarVerticalOffset() != 0) return@setOnChildScrollUpCallback true
            false
        }
    }

    fun setupTopBarDoubleTapToTop() {
        val detector = android.view.GestureDetector(fragment.requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean = true

            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                getHomeAppBar()?.setExpanded(true, true)
                (rvTransactions.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
                rvTransactions.post { rvTransactions.smoothScrollToPosition(0) }
                return true
            }
        })

        layoutStickyTopBar.setOnTouchListener { _, event ->
            if (isTouchInsideView(event, ivBookSwitcher) ||
                isTouchInsideView(event, tvMonthSelector) ||
                isTouchInsideView(event, ivCalendarView) ||
                isTouchInsideView(event, ivSearchBill)
            ) {
                return@setOnTouchListener false
            }
            detector.onTouchEvent(event)
        }
    }

    fun updateHomeFabVisibilityByDrawerState() {
        fabScrollBehavior.markVisible()
        val fab = (fragment.activity as? MainActivity)
            ?.findViewById<FloatingActionButton>(R.id.fab_add) ?: return
        if (!fragment.isAdded ||
            !fragment.isVisible ||
            layoutEmptyView.visibility == View.VISIBLE ||
            isBookDrawerOpen() ||
            getIsMultiSelectModeActive()
        ) {
            fab.hide()
            return
        }
        if (fab.visibility != View.VISIBLE) {
            fab.show()
        }
    }

    fun applyHomeFabDrawerProgress(slideOffset: Float) {
        val fab = (fragment.activity as? MainActivity)
            ?.findViewById<FloatingActionButton>(R.id.fab_add) ?: return
        if (!fragment.isAdded || !fragment.isVisible || getIsMultiSelectModeActive()) {
            fab.hide()
            return
        }
        val clamped = slideOffset.coerceIn(0f, 1f)
        if (clamped <= 0f) {
            if (layoutEmptyView.visibility != View.VISIBLE) {
                fab.alpha = 1f
                fab.scaleX = 1f
                fab.scaleY = 1f
            }
            return
        }
        fab.show()
        fab.alpha = 1f - clamped
        val scale = 1f - 0.18f * clamped
        fab.scaleX = scale
        fab.scaleY = scale
        if (clamped >= 0.999f) {
            fab.hide()
        }
    }

    fun applyStatusBarForHome() {
        if (!fragment.isAdded) return
        val window = fragment.requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val statusBar = getStatusBarHeight()
        val topLp = layoutStickyTopBar.layoutParams as? ViewGroup.MarginLayoutParams
        if (topLp != null) {
            val targetTop = statusBar
            if (topLp.topMargin != targetTop) {
                topLp.topMargin = targetTop
                layoutStickyTopBar.layoutParams = topLp
            }
        }

        val baseSummaryTop = (56f * fragment.resources.displayMetrics.density).toInt()
        if (layoutHeaderSummary.paddingTop != baseSummaryTop + statusBar) {
            layoutHeaderSummary.setPadding(
                layoutHeaderSummary.paddingLeft,
                baseSummaryTop + statusBar,
                layoutHeaderSummary.paddingRight,
                layoutHeaderSummary.paddingBottom
            )
        }
    }

    fun restoreDefaultStatusBarForOtherTabs() {
        if (!fragment.isAdded) return
        val window = fragment.requireActivity().window
        StatusBarStyle.applyByColor(
            window = window,
            statusBarColor = android.graphics.Color.WHITE,
            decorFitsSystemWindows = false
        )
    }

    fun applyHomeCollapseByScroll(offsetPx: Int) {
        // keep compatibility with old call sites
    }

    private fun getStatusBarHeight(): Int {
        val resId = fragment.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) fragment.resources.getDimensionPixelSize(resId) else 0
    }

    private fun isTouchInsideView(event: android.view.MotionEvent, target: View): Boolean {
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + target.width
        val bottom = top + target.height
        return event.rawX in left..right && event.rawY in top..bottom
    }
}
