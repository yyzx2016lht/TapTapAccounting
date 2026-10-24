package com.taostudio.tapaccounting.ui.main.home

import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.ui.common.BillRowAnimator
import com.taostudio.tapaccounting.ui.common.BillUndoController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDeleteUndo
import com.taostudio.tapaccounting.logic.BillMoveTargetResolver
import com.taostudio.tapaccounting.ui.dialog.BillMoveDialog
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

internal class HomeMultiSelectController(
    private val fragment: Fragment,
    private val layoutMultiSelectActions: View,
    private val btnMsCancel: View,
    private val btnMsSelectAll: View,
    private val btnMsDelete: View,
    private val btnMsMoveBook: View,
    private val btnMsExcludeStats: View,
    private val multiSelectActionsBaseBottomMargin: Int,
    private val getAvailableBookNames: () -> List<String>,
    private val getHomeAdapter: () -> HomeAdapter,
    private val dismissKeyboardForDialog: () -> Unit,
    private val configureDialogWindow: (Dialog, Int, Float) -> Unit,
    private val onDataChanged: () -> Unit = {},
    /** 账单列表：批量删除时用它定位被删的行播行动画。 */
    private val getRecyclerView: () -> RecyclerView? = { null }
) {
    /** 操作栏 XML 原始底部内边距，用于叠加系统导航条 inset（首次 setup 时捕获）。 */
    private var multiSelectActionsBasePaddingBottom = -1

    fun setupMultiSelectActions() {
        btnMsCancel.setOnClickListener {
            getHomeAdapter().clearSelection()
        }
        btnMsSelectAll.setOnClickListener {
            val adapter = getHomeAdapter()
            val allCount = adapter.items.count { it is HomeAdapter.ListItem.Item }
            if (adapter.selectedBills.size >= allCount && allCount > 0) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }
        btnMsMoveBook.setOnClickListener {
            val billsToMove = getHomeAdapter().selectedBills.toList()
            if (billsToMove.isEmpty()) return@setOnClickListener
            showMoveToBookDialog(billsToMove)
        }
        btnMsExcludeStats.setOnClickListener {
            val adapter = getHomeAdapter()
            val bills = adapter.selectedBills.toList()
            if (bills.isEmpty()) return@setOnClickListener
            com.taostudio.tapaccounting.ui.common.BillStatsMultiSelect.apply(
                context = fragment.requireContext(),
                scope = fragment.lifecycleScope,
                bills = bills
            ) {
                // 不退出多选，只清空勾选，方便连续处理多批。
                adapter.deselectAll()
                onDataChanged()
            }
        }
        btnMsDelete.setOnClickListener {
            val billsToDelete = getHomeAdapter().selectedBills.toList()
            if (billsToDelete.isEmpty()) return@setOnClickListener

            val recyclerView = getRecyclerView()
            val deletedIds = billsToDelete.map { it.id }
            BillRowAnimator.collapseThen(BillRowAnimator.findRows(recyclerView, deletedIds)) {
                val db = AppDatabase.getDatabase(fragment.requireContext())
                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    val rows = BillDeleteUndo.delete(db, billsToDelete) ?: return@launch
                    withContext(Dispatchers.Main) {
                        getHomeAdapter().clearSelection()
                        onDataChanged()
                        BillUndoController.showNow(
                            fragment.requireActivity().findViewById(android.R.id.content),
                            fragment.getString(R.string.deleted_bills_fmt, billsToDelete.size),
                            rows
                        ) {
                            onDataChanged()
                            BillRowAnimator.expandAfterRefresh(recyclerView, rows.map { it.originalBillId })
                        }
                    }
                }
            }
        }
    }

    fun setupMultiSelectActionsBottomOffset() {
        // Home 页面容器已经通过 activity_main.xml 为底栏预留了固定高度，
        // 这里不应再叠加 BottomNavigation 的额外偏移，否则会出现“悬空”间隙。
        val lp = layoutMultiSelectActions.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null && lp.bottomMargin != multiSelectActionsBaseBottomMargin) {
            lp.bottomMargin = multiSelectActionsBaseBottomMargin
            layoutMultiSelectActions.layoutParams = lp
        }

        // 手势条避让（关键）：首页是 edge-to-edge（decorFitsSystemWindows = false），
        // 进入多选后容器铺满到屏幕最底边，操作栏背景贴底——但内容必须再让出一个
        // 系统导航条（手势条）的高度，否则最下面一排按钮会被横条压住、显示不全。
        // 资产详情页是普通窗口，内容天然布局在系统栏之上，所以那边没有这个问题。
        // 背景仍铺到屏幕最底，只是把 inset 加进 paddingBottom，观感与 BottomNavigation 一致。
        if (multiSelectActionsBasePaddingBottom < 0) {
            multiSelectActionsBasePaddingBottom = layoutMultiSelectActions.paddingBottom
        }
        ViewCompat.setOnApplyWindowInsetsListener(layoutMultiSelectActions) { view, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val target = multiSelectActionsBasePaddingBottom + navBottom
            if (view.paddingBottom != target) {
                view.updatePadding(bottom = target)
            }
            insets
        }
        ViewCompat.requestApplyInsets(layoutMultiSelectActions)
    }

    private fun showMoveToBookDialog(bills: List<Bill>) {
        dismissKeyboardForDialog()
        val activity = fragment.requireActivity()
        val appContext = activity.applicationContext
        val targets = BillMoveTargetResolver.resolve(
            availableBookNames = getAvailableBookNames(),
            selectedBillBookNames = bills.map { it.bookName }
        )
        BillMoveDialog.show(
            activity = activity,
            bills = bills,
            targets = targets
        ) { targetBook ->
            fragment.lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(appContext)
                val error = runCatching {
                    com.taostudio.tapaccounting.data.sync.SharedMutationHooks.moveBills(db, bills, targetBook)
                }.exceptionOrNull()
                withContext(Dispatchers.Main) {
                    if (error != null) {
                        Toast.makeText(activity, error.message ?: "移动失败", Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                    getHomeAdapter().clearSelection()
                    onDataChanged()
                    Toast.makeText(
                        activity,
                        "已将 ${bills.size} 条账单移动到「$targetBook」",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}

