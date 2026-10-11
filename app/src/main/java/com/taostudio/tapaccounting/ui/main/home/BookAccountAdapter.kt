package com.taostudio.tapaccounting.ui.main.home

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewParent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import kotlin.math.abs

class BookAccountAdapter(
    private val onItemClick: (String) -> Unit,
    private val onRenameClick: (oldName: String, newName: String) -> Unit,
    private val onManageClick: (name: String) -> Unit,
    private val onOrderChanged: (newOrder: List<String>) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onEditingChanged: (Boolean) -> Unit = {}
) : RecyclerView.Adapter<BookAccountAdapter.BookViewHolder>() {

    private val items = mutableListOf<String>()
    private var selectedBook: String = ""
    private var defaultBook: String = BookAccountManager.DEFAULT_BOOK
    private val collapsedBooks = mutableSetOf<String>()
    private var summaries: Map<String, String> = emptyMap()
    private var openedPosition: Int = RecyclerView.NO_POSITION
    private var editingPosition: Int = RecyclerView.NO_POSITION
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (this.recyclerView == recyclerView) {
            this.recyclerView = null
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun submitList(
        books: List<String>,
        selected: String,
        defaultBookName: String,
        collapsedBookNames: List<String> = emptyList()
    ) {
        val openedName = items.getOrNull(openedPosition)
        val editingName = items.getOrNull(editingPosition)

        defaultBook = BookAccountManager.normalizeBookName(defaultBookName)
        collapsedBooks.clear()
        collapsedBookNames.map { BookAccountManager.normalizeBookName(it) }
            .filter { it.isNotBlank() }
            .forEach { collapsedBooks.add(it) }
        items.clear()
        books.map { BookAccountManager.normalizeBookName(it) }
            .filter { it.isNotBlank() }
            .forEach {
                if (!items.contains(it)) items.add(it)
            }
        if (!items.contains(defaultBook)) {
            items.add(0, defaultBook)
        }
        selectedBook = selected

        openedPosition = items.indexOf(openedName).takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        editingPosition = items.indexOf(editingName).takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    /** 账本名（规范化后）到摘要行文字，例如「本月支出 ¥1,280 · 23 笔」。 */
    fun submitSummaries(next: Map<String, String>) {
        if (summaries == next) return
        summaries = next
        notifyItemRangeChanged(0, items.size)
    }

    fun onItemMove(fromPos: Int, toPos: Int): Boolean {
        if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
        if (fromPos !in items.indices || toPos !in items.indices) return false
        if (!isDraggablePosition(fromPos) || !isDraggablePosition(toPos)) return false
        val item = items.removeAt(fromPos)
        items.add(toPos, item)
        notifyItemMoved(fromPos, toPos)
        return true
    }

    fun isDraggablePosition(position: Int): Boolean {
        if (position !in items.indices) return false
        val normalized = BookAccountManager.normalizeBookName(items[position])
        return normalized != BookAccountManager.ALL_BOOK && normalized != defaultBook
            && normalized != BookAccountManager.COLLAPSED_BOOK_GROUP
    }

    private fun isManageablePosition(position: Int): Boolean {
        if (position !in items.indices) return false
        val normalized = BookAccountManager.normalizeBookName(items[position])
        return normalized != BookAccountManager.ALL_BOOK &&
            normalized != BookAccountManager.COLLAPSED_BOOK_GROUP
    }

    fun onDragEnd() {
        onOrderChanged(items.toList())
    }

    fun closeSwipeActions() {
        if (openedPosition != RecyclerView.NO_POSITION) {
            val old = openedPosition
            openedPosition = RecyclerView.NO_POSITION
            notifyItemChanged(old)
        }
    }

    fun startInlineRename(bookName: String) {
        val normalized = BookAccountManager.normalizeBookName(bookName)
        val pos = items.indexOfFirst { BookAccountManager.normalizeBookName(it) == normalized }
        if (pos == RecyclerView.NO_POSITION || !isManageablePosition(pos)) return

        val oldOpened = openedPosition
        val oldEditing = editingPosition
        openedPosition = RecyclerView.NO_POSITION
        editingPosition = pos
        onEditingChanged(true)
        if (oldOpened != RecyclerView.NO_POSITION && oldOpened != pos) notifyItemChanged(oldOpened)
        if (oldEditing != RecyclerView.NO_POSITION && oldEditing != pos) notifyItemChanged(oldEditing)
        notifyItemChanged(pos)
        recyclerView?.post {
            recyclerView?.smoothScrollToPosition(pos)
        }
    }

    fun commitActiveRename(): Boolean {
        val pos = editingPosition
        if (pos == RecyclerView.NO_POSITION) return false

        val holder = recyclerView?.findViewHolderForAdapterPosition(pos) as? BookViewHolder
        if (holder != null) {
            holder.commitInlineRename()
            return true
        }

        editingPosition = RecyclerView.NO_POSITION
        notifyItemChanged(pos)
        onEditingChanged(false)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_account, parent, false)
        return BookViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(
            name = items[position],
            selected = items[position] == selectedBook,
            opened = position == openedPosition,
            editing = position == editingPosition
        )
    }

    override fun getItemCount(): Int = items.size

    inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val foreground: View = itemView.findViewById(R.id.layout_foreground)
        private val layoutText: View = itemView.findViewById(R.id.layout_book_text)
        private val tvName: TextView = itemView.findViewById(R.id.tv_book_name)
        private val tvSummary: TextView = itemView.findViewById(R.id.tv_book_summary)
        private val etName: EditText = itemView.findViewById(R.id.et_book_name)
        private val tvDefaultTag: TextView = itemView.findViewById(R.id.tv_book_default_tag)
        private val ivSelected: TextView = itemView.findViewById(R.id.iv_book_selected)
        private val btnSetDefaultInline: TextView = itemView.findViewById(R.id.btn_book_set_default_inline)
        private val btnManage: ImageView = itemView.findViewById(R.id.btn_book_manage)

        private val slop = ViewConfiguration.get(itemView.context).scaledTouchSlop
        private val actionsWidthPx = 60f * itemView.resources.displayMetrics.density

        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var dragging = false
        private var movedBeyondTapSlop = false
        private var longPressRunnable: Runnable? = null
        private var dragStartedByLongPress = false

        init {
            foreground.setOnTouchListener { _, ev -> onForegroundTouch(ev) }
            foreground.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (editingPosition == pos) return@setOnClickListener

                if (openedPosition == pos) {
                    closeSwipeActions()
                    return@setOnClickListener
                }

                closeSwipeActions()
                onItemClick(items[pos])
            }

            btnManage.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                closeSwipeActions()
                onManageClick(items[pos])
            }

            etName.setOnEditorActionListener { _, actionId, event ->
                val imeDone = actionId == EditorInfo.IME_ACTION_DONE
                val keyDone = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
                if (imeDone || keyDone) {
                    commitInlineRename()
                    true
                } else {
                    false
                }
            }

            etName.setOnTouchListener { _, event ->
                requestAncestorsDisallowIntercept(true)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    itemView.post { requestAncestorsDisallowIntercept(true) }
                }
                false
            }

            etName.setOnFocusChangeListener { _, hasFocus ->
                val pos = adapterPosition
                if (!hasFocus && pos != RecyclerView.NO_POSITION && editingPosition == pos) {
                    commitInlineRename()
                }
            }
        }

        private fun onForegroundTouch(ev: MotionEvent): Boolean {
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION || editingPosition == pos) return false

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startTx = foreground.translationX
                    dragging = false
                    movedBeyondTapSlop = false
                    dragStartedByLongPress = false
                    if (openedPosition != RecyclerView.NO_POSITION && openedPosition != pos) {
                        val old = openedPosition
                        openedPosition = RecyclerView.NO_POSITION
                        notifyItemChanged(old)
                    }
                    longPressRunnable?.let { foreground.removeCallbacks(it) }
                    if (isDraggablePosition(pos)) {
                        val runnable = Runnable {
                            val currentPos = adapterPosition
                            if (currentPos == RecyclerView.NO_POSITION || !isDraggablePosition(currentPos)) return@Runnable
                            if (editingPosition == currentPos || movedBeyondTapSlop || dragging) return@Runnable
                            dragStartedByLongPress = true
                            closeSwipeActions()
                            foreground.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            itemView.parent?.requestDisallowInterceptTouchEvent(false)
                            onStartDrag(this)
                        }
                        longPressRunnable = runnable
                        foreground.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    // 提前告知父容器不要拦截，确保 MOVE 能持续送达
                    requestAncestorsDisallowIntercept(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        movedBeyondTapSlop = true
                        longPressRunnable?.let {
                            foreground.removeCallbacks(it)
                            longPressRunnable = null
                        }
                    }
                    if (dragStartedByLongPress) {
                        // 长按进入排序后，事件交还给 RecyclerView + ItemTouchHelper 处理拖拽
                        requestAncestorsDisallowIntercept(false)
                        return false
                    }
                    if (!dragging) {
                        when {
                            abs(dx) > slop && abs(dx) > abs(dy) -> {
                                if (!isManageablePosition(pos)) {
                                    requestAncestorsDisallowIntercept(false)
                                    return false
                                }
                                // 确认横向拖动
                                dragging = true
                            }
                            abs(dy) > slop -> {
                                // 确认纵向滚动，释放拦截权，让 RecyclerView/NestedScrollView 接管
                                dragging = false
                                requestAncestorsDisallowIntercept(false)
                                return false
                            }
                        }
                    }
                    if (dragging) {
                        // 向左滑（dx < 0）露出右侧管理按钮，translationX 范围 [-actionsWidthPx, 0]
                        val tx = (startTx + dx).coerceIn(-actionsWidthPx, 0f)
                        foreground.translationX = tx
                        return true
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let {
                        foreground.removeCallbacks(it)
                        longPressRunnable = null
                    }
                    requestAncestorsDisallowIntercept(false)
                    if (dragStartedByLongPress) {
                        dragStartedByLongPress = false
                        return false
                    }
                    if (dragging) {
                        settleSwipe(pos)
                        dragging = false
                        return true
                    }
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val isTap = !movedBeyondTapSlop && abs(dx) <= slop && abs(dy) <= slop
                        if (isTap) {
                            foreground.performClick()
                        }
                        return true
                    }
                }
            }
            return false
        }

        private fun requestAncestorsDisallowIntercept(disallow: Boolean) {
            var parent: ViewParent? = itemView.parent
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(disallow)
                parent = (parent as? View)?.parent
            }
        }

        private fun settleSwipe(pos: Int) {
            if (!isManageablePosition(pos)) {
                foreground.animate().translationX(0f).setDuration(180L).start()
                return
            }
            // 向左滑超过40%则弹出，否则回弹；向右滑时关闭
            val shouldOpen = foreground.translationX < -actionsWidthPx * 0.4f
            val target = if (shouldOpen) -actionsWidthPx else 0f
            if (shouldOpen) {
                val old = openedPosition
                openedPosition = pos
                if (old != RecyclerView.NO_POSITION && old != pos) {
                    notifyItemChanged(old)
                }
            } else if (openedPosition == pos) {
                openedPosition = RecyclerView.NO_POSITION
            }
            foreground.animate().translationX(target).setDuration(180L).start()
        }

        fun commitInlineRename() {
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val oldName = items.getOrNull(pos) ?: return
            val newName = etName.text?.toString()?.trim().orEmpty()
            editingPosition = RecyclerView.NO_POSITION
            onEditingChanged(false)
            hideKeyboard(etName)
            // Focus changes may happen while RecyclerView is in layout/scroll.
            // Post notify to next frame to avoid "Cannot call this method while RecyclerView is computing a layout".
            itemView.post {
                if (pos in 0 until itemCount) {
                    notifyItemChanged(pos)
                }
            }

            if (newName.isBlank() || newName == oldName) return
            onRenameClick(oldName, newName)
        }

        private fun hideKeyboard(view: View) {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }

        fun bind(name: String, selected: Boolean, opened: Boolean, editing: Boolean) {
            val normalized = BookAccountManager.normalizeBookName(name)
            val isAllBook = normalized == BookAccountManager.ALL_BOOK
            val isCollapsedGroup = normalized == BookAccountManager.COLLAPSED_BOOK_GROUP
            val isDefaultBook = normalized == defaultBook
            val isCollapsedBook = normalized in collapsedBooks
            val collapsedGroupExpanded = items.any { BookAccountManager.normalizeBookName(it) in collapsedBooks }
            val density = itemView.resources.displayMetrics.density
            val normalStart = (16f * density).toInt()
            val nestedStart = (30f * density).toInt()

            foreground.animate().cancel()
            tvName.text = when {
                isAllBook -> BookAccountManager.ALL_BOOK
                isCollapsedGroup -> "${if (collapsedGroupExpanded) "▾" else "▸"} ${BookAccountManager.COLLAPSED_BOOK_GROUP}  ${collapsedBooks.size}"
                isDefaultBook -> defaultBook
                else -> name
            }
            foreground.setPadding(
                if (isCollapsedBook) nestedStart else normalStart,
                foreground.paddingTop,
                foreground.paddingRight,
                foreground.paddingBottom
            )
            foreground.translationX = if (opened) -actionsWidthPx else 0f
            foreground.setBackgroundResource(if (selected) R.drawable.bg_book_item_selected else R.drawable.bg_book_item_normal)
            ivSelected.visibility = if (selected) View.VISIBLE else View.GONE

            if (isAllBook || isCollapsedGroup) {
                tvName.setTextColor(android.graphics.Color.parseColor("#8E9CAF"))
                btnManage.isEnabled = false
                btnManage.alpha = 0.35f
            } else {
                tvName.setTextColor(android.graphics.Color.parseColor(if (isCollapsedBook) "#6E7D94" else "#333333"))
                btnManage.isEnabled = true
                btnManage.alpha = 1f
            }
            btnManage.contentDescription = "manage"

            val summary = summaries[normalized]
            tvSummary.text = summary
            tvSummary.visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
            tvDefaultTag.visibility = if (isDefaultBook && !editing) View.VISIBLE else View.GONE

            if (editing) {
                layoutText.visibility = View.GONE
                etName.visibility = View.VISIBLE
                if (!isAllBook && !isCollapsedGroup) {
                    btnSetDefaultInline.visibility = View.VISIBLE
                    btnSetDefaultInline.isEnabled = !isDefaultBook
                    btnSetDefaultInline.alpha = if (isDefaultBook) 0.6f else 1f
                    btnSetDefaultInline.text = if (isDefaultBook) "已是默认" else "设为默认"
                } else {
                    btnSetDefaultInline.visibility = View.GONE
                }
                if (etName.text?.toString() != name) {
                    etName.setText(name)
                }
                etName.post {
                    if (adapterPosition != RecyclerView.NO_POSITION && adapterPosition == editingPosition) {
                        etName.requestFocus()
                        etName.setSelection(etName.text?.length ?: 0)
                        val imm = etName.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(etName, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            } else {
                layoutText.visibility = View.VISIBLE
                etName.visibility = View.GONE
                btnSetDefaultInline.visibility = View.GONE
            }
        }
    }
}

