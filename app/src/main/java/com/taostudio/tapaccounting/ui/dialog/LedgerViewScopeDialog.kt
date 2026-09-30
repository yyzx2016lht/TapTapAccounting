package com.taostudio.tapaccounting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager.BadTokenException
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.viewscope.LedgerBookSelection
import com.taostudio.tapaccounting.viewscope.LedgerMemberScope
import com.taostudio.tapaccounting.viewscope.LedgerViewScope
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import com.taostudio.tapaccounting.viewscope.ViewBookOption

/**
 * 查看范围。普通对话框，不用 BottomSheet。
 * 窗口贴在屏幕底部，高度接近统计筛选面板，不超过可用区域的大约七成。
 * 重置 / 应用范围固定在窗口底部，账本列表在中间滚动。
 */
object LedgerViewScopeDialog {

    fun show(
        context: Context,
        current: ResolvedLedgerViewScope,
        onConfirm: (LedgerViewScope) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_ledger_view_scope, null, false)
        ScopeController(context, view, current) { scope ->
            onConfirm(scope)
            dialog.dismiss()
        }.bind(onClose = { dialog.dismiss() })
        dialog.setContentView(
            view,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setGravity(Gravity.BOTTOM)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, windowHeight(context))
        }
        try {
            dialog.show()
        } catch (_: BadTokenException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun windowHeight(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val status = systemBar(context, "status_bar_height")
        val nav = systemBar(context, "navigation_bar_height")
        val available = (metrics.heightPixels - status - nav).coerceAtLeast((metrics.heightPixels * 0.72f).toInt())
        return (available * 0.72f).toInt()
    }

    private fun systemBar(context: Context, name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}

private class ScopeController(
    private val context: Context,
    private val view: View,
    private val current: ResolvedLedgerViewScope,
    private val onConfirm: (LedgerViewScope) -> Unit
) {
    private val activeBooks = current.availableBooks.filterNot { it.isCollapsed }
    private val collapsedBooks = current.availableBooks.filter { it.isCollapsed }
    private val selectedIds = current.selectedBooks.mapTo(linkedSetOf()) { it.id }
    private var memberScope = current.scope.members
    private var collapsedExpanded = false

    fun bind(onClose: () -> Unit) {
        val rvBooks = view.findViewById<RecyclerView>(R.id.rv_scope_books)
        val summary = view.findViewById<TextView>(R.id.tv_scope_selection_summary)
        val btnMine = view.findViewById<MaterialButton>(R.id.btn_scope_mine)
        val btnPersonal = view.findViewById<MaterialButton>(R.id.btn_scope_personal)
        val btnAll = view.findViewById<MaterialButton>(R.id.btn_scope_all)
        val btnEveryone = view.findViewById<TextView>(R.id.btn_scope_everyone)
        val btnOnlyMine = view.findViewById<TextView>(R.id.btn_scope_only_mine)

        lateinit var adapter: BookAdapter

        fun consideredBooks(): List<ViewBookOption> =
            if (collapsedExpanded) current.availableBooks else activeBooks

        fun effectiveSelectedIds(): Set<Long> {
            val visible = consideredBooks().mapTo(hashSetOf()) { it.id }
            return selectedIds.filterTo(linkedSetOf()) { it in visible }
        }

        fun rows(): List<BookRow> = buildList {
            activeBooks.forEach { add(BookRow.Book(it, it.id in selectedIds)) }
            if (collapsedBooks.isNotEmpty()) {
                add(BookRow.CollapsedToggle(collapsedBooks.size, collapsedExpanded))
                if (collapsedExpanded) collapsedBooks.forEach { add(BookRow.Book(it, it.id in selectedIds)) }
            }
        }

        fun paintMemberToggle() {
            val everyone = memberScope == LedgerMemberScope.EVERYONE
            btnEveryone.setBackgroundResource(
                if (everyone) R.drawable.bg_stats_segmented_selected
                else R.drawable.bg_stats_segmented_unselected
            )
            btnOnlyMine.setBackgroundResource(
                if (everyone) R.drawable.bg_stats_segmented_unselected
                else R.drawable.bg_stats_segmented_selected
            )
            btnEveryone.setTextColor(context.getColor(
                if (everyone) R.color.stats_segmented_text_active
                else R.color.stats_segmented_text_inactive
            ))
            btnOnlyMine.setTextColor(context.getColor(
                if (everyone) R.color.stats_segmented_text_inactive
                else R.color.stats_segmented_text_active
            ))
        }

        fun paint() {
            val considered = consideredBooks()
            val effective = effectiveSelectedIds()
            val allIds = considered.mapTo(linkedSetOf()) { it.id }
            val personalIds = considered.filterNot { it.isShared }.mapTo(linkedSetOf()) { it.id }
            val allSelected = allIds.isNotEmpty() && effective == allIds
            btnMine.isChecked = allSelected && memberScope == LedgerMemberScope.MINE
            btnAll.isChecked = allSelected && memberScope == LedgerMemberScope.EVERYONE
            btnPersonal.isChecked = !btnMine.isChecked && personalIds.isNotEmpty() &&
                effective == personalIds && memberScope == LedgerMemberScope.MINE
            val memberLabel = if (memberScope == LedgerMemberScope.MINE) "仅我" else "全部成员"
            summary.text = "已选 ${effective.size} 个 · $memberLabel"
            paintMemberToggle()
            adapter.submit(rows())
        }

        fun select(bookIds: Set<Long>, members: LedgerMemberScope) {
            selectedIds.clear()
            selectedIds.addAll(bookIds)
            memberScope = members
            paint()
        }

        adapter = BookAdapter(
            onToggleCollapsed = {
                collapsedExpanded = !collapsedExpanded
                paint()
            },
            onToggleBook = { book ->
                if (!selectedIds.add(book.id)) selectedIds.remove(book.id)
                paint()
            }
        )
        rvBooks.layoutManager = LinearLayoutManager(context)
        rvBooks.adapter = adapter
        rvBooks.itemAnimator = null

        btnMine.setOnClickListener {
            select(consideredBooks().mapTo(linkedSetOf()) { it.id }, LedgerMemberScope.MINE)
        }
        btnPersonal.setOnClickListener {
            val personalIds = consideredBooks().filterNot { it.isShared }.mapTo(linkedSetOf()) { it.id }
            if (personalIds.isEmpty()) {
                Toast.makeText(context, "当前没有个人账本", Toast.LENGTH_SHORT).show()
            } else {
                select(personalIds, LedgerMemberScope.MINE)
            }
        }
        btnAll.setOnClickListener {
            select(consideredBooks().mapTo(linkedSetOf()) { it.id }, LedgerMemberScope.EVERYONE)
        }
        btnEveryone.setOnClickListener {
            memberScope = LedgerMemberScope.EVERYONE
            paint()
        }
        btnOnlyMine.setOnClickListener {
            memberScope = LedgerMemberScope.MINE
            paint()
        }
        view.findViewById<View>(R.id.btn_scope_close).setOnClickListener { onClose() }
        view.findViewById<View>(R.id.btn_scope_reset).setOnClickListener {
            collapsedExpanded = false
            select(emptySet(), LedgerMemberScope.EVERYONE)
        }
        view.findViewById<View>(R.id.btn_scope_confirm).setOnClickListener {
            val chosen = effectiveSelectedIds()
            if (chosen.isEmpty()) {
                Toast.makeText(context, "请至少选择一个账本", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val defaultIds = activeBooks.mapTo(hashSetOf()) { it.id }
            val books = if (chosen == defaultIds) {
                LedgerBookSelection.All
            } else {
                LedgerBookSelection.Selected(chosen)
            }
            onConfirm(LedgerViewScope(books, memberScope))
        }

        paint()
    }
}

private sealed interface BookRow {
    data class Book(val book: ViewBookOption, val selected: Boolean) : BookRow
    data class CollapsedToggle(val count: Int, val expanded: Boolean) : BookRow
}

private class BookAdapter(
    private val onToggleCollapsed: () -> Unit,
    private val onToggleBook: (ViewBookOption) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<BookRow> = emptyList()

    fun submit(next: List<BookRow>) {
        rows = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val item = LayoutInflater.from(parent.context).inflate(R.layout.item_book_picker, parent, false)
        return object : RecyclerView.ViewHolder(item) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val itemView = holder.itemView
        val name = itemView.findViewById<TextView>(R.id.tv_book_name)
        val icon = itemView.findViewById<ImageView>(R.id.iv_book_icon)
        val mark = itemView.findViewById<ImageView>(R.id.iv_book_selected)
        val context = itemView.context
        when (val row = rows[position]) {
            is BookRow.CollapsedToggle -> {
                name.text = "已收纳账本（${row.count}）"
                name.setTextColor(context.getColor(R.color.text_secondary))
                icon.setImageResource(R.drawable.ic_backup_folder_action)
                icon.alpha = 0.72f
                mark.setImageResource(R.drawable.ic_chevron_down_small)
                mark.rotation = if (row.expanded) 180f else 0f
                mark.visibility = View.VISIBLE
                itemView.setBackgroundResource(R.drawable.bg_book_item_normal)
                itemView.contentDescription = if (row.expanded) "收起已收纳账本" else "展开已收纳账本"
                itemView.setOnClickListener { onToggleCollapsed() }
            }
            is BookRow.Book -> {
                name.text = if (row.book.isShared) "${row.book.name}  · 共享" else row.book.name
                icon.setImageResource(R.drawable.ic_switch_book)
                mark.setImageResource(R.drawable.ic_book_selected_check)
                mark.rotation = 0f
                bindSelected(itemView, name, icon, mark, row.selected, row.book)
                itemView.setOnClickListener { onToggleBook(row.book) }
            }
        }
    }

    private fun bindSelected(
        itemView: View,
        name: TextView,
        icon: ImageView,
        mark: ImageView,
        selected: Boolean,
        book: ViewBookOption
    ) {
        val context = itemView.context
        name.setTextColor(context.getColor(
            if (selected) R.color.brand_primary else R.color.text_primary
        ))
        icon.alpha = if (selected) 1f else 0.75f
        mark.visibility = if (selected) View.VISIBLE else View.GONE
        itemView.setBackgroundResource(
            if (selected) R.drawable.bg_book_item_selected else R.drawable.bg_book_item_normal
        )
        itemView.contentDescription = "${book.name}，${if (selected) "已选择" else "未选择"}"
    }
}
