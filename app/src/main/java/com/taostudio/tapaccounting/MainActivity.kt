package com.taostudio.tapaccounting

import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.content.ClipboardManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.sync.InviteCodec
import com.taostudio.tapaccounting.data.sync.SharedInvite
import com.taostudio.tapaccounting.data.sync.SharedLedgerService
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.taostudio.tapaccounting.ui.main.SharedYearMonthSession
import com.taostudio.tapaccounting.ui.common.AddBillEntrySheetLauncher
import com.taostudio.tapaccounting.ui.common.UiMotion
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.ui.main.home.HomeFragment
import com.taostudio.tapaccounting.ui.main.stats.StatsFragment
import com.taostudio.tapaccounting.ui.main.assets.AssetsFragment
import com.taostudio.tapaccounting.ui.main.profile.ProfileFragment
import com.taostudio.tapaccounting.ui.recurring.RecurringDuePromptController
import com.taostudio.tapaccounting.ui.SensitivityActivity
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.abs

// 支持水平滑动接管的 FrameLayout（用于页面切换手势）
/**
 * 在 onInterceptTouchEvent 中识别水平滑动意图。
 * 确认开始后接管事件，并通过回调把 dx 传给 Activity 做 translationX。
 * 不会影响普通点击与垂直滚动。
 */
class SwipeFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /**
     * 手势意图确认时回调参数含义：
     *   dir      1=向左（下一页） / -1=向右（上一页）
     *   rawX/rawY 按下时的屏幕坐标（用于判断是否可接管）
     * 返回 true 才会接管后续事件；返回 false 表示放弃并交给子 View（如 Drawer）。
     */
    var onSwipeStart: ((dir: Int, rawX: Float, rawY: Float) -> Boolean)? = null
    var onHorizontalDrag: ((dx: Float) -> Unit)? = null
    var onHorizontalSettle: ((dx: Float, vx: Float) -> Unit)? = null

    private var vt: VelocityTracker? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    private var rejected = false   // 已判定为拒绝，后续手势不再接管
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxVel = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                dragging = false
                rejected = false
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt!!.addMovement(ev)
                return false          // DOWN 不拦截，保留子 View 点击能力
            }
            MotionEvent.ACTION_MOVE -> {
                if (rejected) return false
                vt?.addMovement(ev)
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragging) {
                    when {
                        abs(dx) > slop && abs(dx) > abs(dy) -> {
                            val dir = if (dx < 0f) 1 else -1
                            // 使用屏幕坐标，作为回调入参
                            val loc = IntArray(2).also { getLocationOnScreen(it) }
                            val localDownX = downRawX - loc[0]
                            val shouldStart = onSwipeStart?.invoke(dir, downRawX, downRawY) ?: true
                            if (!shouldStart) {
                                rejected = true
                                vt?.recycle(); vt = null
                                parent?.requestDisallowInterceptTouchEvent(false)
                                return false
                            }

                            // 水平意图确认，开始接管事件
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        abs(dy) > slop -> {
                            // 纵向意图明显，不接管
                            rejected = true
                            vt?.recycle(); vt = null
                            return false
                        }
                        else -> return false
                    }
                }
                if (dragging) {
                    onHorizontalDrag?.invoke(dx)
                    return true   // 接管后的 MOVE
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.apply {
                        addMovement(ev)
                        computeCurrentVelocity(1000, maxVel.toFloat())
                    }
                    val vx = vt?.xVelocity ?: 0f
                    val dx = ev.rawX - downRawX
                    vt?.recycle(); vt = null
                    dragging = false
                    rejected = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        onHorizontalSettle?.invoke(dx, vx)
                    } else {
                        onHorizontalSettle?.invoke(0f, 0f)
                    }
                    return true
                }
                vt?.recycle(); vt = null
                dragging = false
                rejected = false
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // only called when dragging=true; onInterceptTouchEvent already consumed MOVE
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                onHorizontalDrag?.invoke(ev.rawX - downRawX)
            }
            MotionEvent.ACTION_UP -> {
                vt?.apply {
                    addMovement(ev)
                    computeCurrentVelocity(1000, maxVel.toFloat())
                }
                val vx = vt?.xVelocity ?: 0f
                val dx = ev.rawX - downRawX
                vt?.recycle(); vt = null
                dragging = false
                rejected = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onHorizontalSettle?.invoke(dx, vx)
            }
            MotionEvent.ACTION_CANCEL -> {
                vt?.recycle(); vt = null
                dragging = false
                rejected = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onHorizontalSettle?.invoke(0f, 0f)
            }
        }
        return true
    }
}

// MainActivity：主页容器与底部导航切页控制
class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OPEN_TAB_INDEX = "open_tab_index"
    }

    private var fabApp: FloatingActionButton? = null
    private var bottomNavigationView: BottomNavigationView? = null
    private lateinit var swipeContainer: SwipeFrameLayout
    private var bottomNavBasePaddingBottom = 0
    private var fabBaseBottomMargin = 0
    private var navBarBottomInset = 0

    /**
     * 跨 HomeFragment 重建共享的 RecycledViewPool。
     * Fragment 每次被 replace() 重建时，ViewHolder 不会被丢弃，下次直接复用，
     * 彻底跳过 inflate，消除首帧布局卡顿。
     * TYPE_HEADER=0, TYPE_ITEM=1；
     * 为应对“少账本 <-> 多账本”频繁切换，提升缓存上限，减少 2->164 这种场景的重新 inflate。
     */
    val homeRecycledViewPool = androidx.recyclerview.widget.RecyclerView.RecycledViewPool().also {
        it.setMaxRecycledViews(0, 80)    // TYPE_HEADER：按天分组头，适度提高
        it.setMaxRecycledViews(1, 260)   // TYPE_ITEM：重点提高，尽量覆盖大账本回切
    }

    // Tab 顺序
    private val tabIds = listOf(R.id.nav_home, R.id.nav_stats, R.id.nav_assets, R.id.nav_profile)
    private var currentTabIndex = 0

    // 4个 Tab 的 Fragment 实例，一次性创建，永不销毁
    private val tabFragments = arrayOfNulls<Fragment>(4)

    // 最小判定滑动速度
    private var minFlingVelocity = 0

    // 当前运行中的回弹/切换动画
    private var settleAnimator: ValueAnimator? = null

    // 滑动手势：预加载的下一个 Fragment（add 但未 replace）
    private var peekFragment: Fragment? = null
    // 滑动方向：+1 右→左（下一页）/-1 左→右（上一页）
    private var swipeDir = 0
    // 预加载的目标 tab 索引
    private var peekIndex = -1

    // 防止快速连续切换 Tab 导致状态错乱
    private var isSwitching = false
    private var homeOnboardingShownThisSession = false
    private var pendingInviteDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        RecentTasksHelper.ensureTaskVisible(this)
        KeepAliveDiagnostics.logSnapshot(this, "main-onCreate")

        bottomNavigationView = findViewById(R.id.bottom_navigation)
        fabApp = findViewById(R.id.fab_add)
        swipeContainer = findViewById(R.id.fragment_container)
        bottomNavBasePaddingBottom = bottomNavigationView?.paddingBottom ?: 0
        fabBaseBottomMargin =
            (fabApp?.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        setupMainWindowInsets()
        refreshBottomNavigationTabs(ensureValidSelection = false)

        minFlingVelocity = ViewConfiguration.get(this).scaledMinimumFlingVelocity

        if (savedInstanceState == null) {
            currentTabIndex = resolveRequestedTabIndex(intent) ?: 0
            // 一次性 add 全部 4 个 Fragment，hide 非当前的，永不 replace
            val tx = supportFragmentManager.beginTransaction()
            for (i in tabIds.indices) {
                val f = makeFragment(i)
                tabFragments[i] = f
                tx.add(R.id.fragment_container, f, "tab_$i")
                if (i != currentTabIndex) tx.hide(f)
            }
            tx.commitNow()
            bottomNavigationView?.selectedItemId = tabIds[currentTabIndex]
            updateFabVisibility()

            // 预加载首页数据：直接获取 ViewModel 并启动数据查询（比 Fragment.onViewCreated 早很多）
            val homeViewModel = androidx.lifecycle.ViewModelProvider(this)
                .get(com.taostudio.tapaccounting.ui.main.home.HomeViewModel::class.java)
            val (sessionYear, sessionMonth) = SharedYearMonthSession.getYearMonth()
            homeViewModel.syncAndLoad(
                bookName = com.taostudio.tapaccounting.BookAccountManager.getSelectedBook(this),
                year = sessionYear,
                month = sessionMonth,
                timeRange = 0,
                type = 0,
                isChartHidden = false
            )
            Log.d("HomePerf", "MainActivity.onCreate: preload started")
        } else {
            val restoredTabIndex = savedInstanceState.getInt("tab_index", 0)
            currentTabIndex = restoredTabIndex
            val shouldFallbackToHome = !isTabVisible(currentTabIndex)
            if (shouldFallbackToHome) {
                currentTabIndex = 0
            }
            // 恢复时从 FragmentManager 找回已有实例
            for (i in tabIds.indices) {
                tabFragments[i] = supportFragmentManager.findFragmentByTag("tab_$i")
            }
            if (shouldFallbackToHome) {
                val tx = supportFragmentManager.beginTransaction()
                tabFragments.forEachIndexed { index, fragment ->
                    if (fragment != null) {
                        if (index == currentTabIndex) tx.show(fragment) else tx.hide(fragment)
                    }
                }
                tx.commitNowAllowingStateLoss()
            }
            bottomNavigationView?.selectedItemId = tabIds.getOrElse(currentTabIndex) { R.id.nav_home }
            fabApp?.post { updateFabVisibility() }
        }

    // BottomNav 点击切换：统一走 switchTab，保持与滑动动画一致
        bottomNavigationView?.setOnItemSelectedListener { item ->
            val newIndex = tabIds.indexOf(item.itemId)
            if (newIndex < 0 || newIndex == currentTabIndex) return@setOnItemSelectedListener true
            val dir = if (newIndex > currentTabIndex) 1 else -1
            switchTab(newIndex, dir, fromSwipe = false)
            true
        }

    // 滑动开始：预加载下一页（show 但不 replace），与当前页并排摆放
        swipeContainer.onSwipeStart = { dir, rawX, rawY ->
            if (isSwitching) {
                false
            } else {
            val home = curFragment() as? HomeFragment
            val assets = curFragment() as? AssetsFragment
            val nextIdx = findAdjacentVisibleTabIndex(dir)
            when {
                // If on Stats page and touch is on PieChart, let PieChart handle the gesture
                currentTabIndex == 1 && isSwipeTouchOnPieChart(rawX, rawY) -> false
                home?.isBookDrawerOpen() == true -> false
                assets?.isAssetDrawerOpen() == true -> false
                currentTabIndex == 0 && dir < 0 -> {
                    val loc = IntArray(2).also { swipeContainer.getLocationOnScreen(it) }
                    val localDownX = rawX - loc[0]
                    if (localDownX < swipeContainer.width / 3f) {
                        home?.openBookDrawerFromHost()
                    }
                    false
                }
                nextIdx == null -> false
                else -> {
                    if (peekFragment == null) {
                        swipeDir = dir
                        peekIndex = nextIdx
                        val frag = tabFragments[nextIdx] ?: makeFragment(nextIdx).also { tabFragments[nextIdx] = it }
                        peekFragment = frag
                        val w = swipeContainer.width.toFloat().coerceAtLeast(1f)
                        hideAssetFabForTransition(frag)
                        supportFragmentManager.beginTransaction()
                            .show(frag)
                            .commitNow()
                        // 目标页初始贴在当前页对侧
                        frag.view?.apply {
                            translationX = if (dir > 0) w else -w
                            alpha = 0.78f
                            scaleX = 0.985f
                            scaleY = 0.985f
                        }
                    }
                    true
                }
            }
            }
        }

    // 拖动中：当前页与目标页同步平移/缩放/透明度
        swipeContainer.onHorizontalDrag = { dx ->
            if (peekFragment != null && swipeDir != 0 && peekIndex in tabIds.indices) {
                val w = swipeContainer.width.toFloat().coerceAtLeast(1f)
                val clampedDx = dx.coerceIn(-w, w)
                // 找到当前页与预加载页的 View
                val allFrags = supportFragmentManager.fragments
                val curFrag = tabFragments.getOrNull(currentTabIndex)
                val curView = curFrag?.view
                val peekView = peekFragment?.view

                // 当前页跟随手势移动
                curView?.translationX = clampedDx
                val progress = (abs(clampedDx) / w).coerceIn(0f, 1f)
                curView?.alpha = 1f - 0.18f * progress
                val curScale = 1f - 0.035f * progress
                curView?.scaleX = curScale
                curView?.scaleY = curScale
                peekView?.translationX = if (swipeDir > 0) clampedDx + w else clampedDx - w
                peekView?.alpha = 0.78f + 0.22f * progress
                val peekScale = 0.985f + 0.015f * progress
                peekView?.scaleX = peekScale
                peekView?.scaleY = peekScale

                // 目标页始终贴在当前页对侧，形成并排跟手
                peekView?.translationX = if (swipeDir > 0) clampedDx + w else clampedDx - w
            }
        }

        // 松手后：决定切换还是回弹
        swipeContainer.onHorizontalSettle = { dx, vx ->
            if (peekFragment != null && swipeDir != 0 && peekIndex in tabIds.indices) {
                val w = swipeContainer.width.toFloat().coerceAtLeast(1f)
                val distPass = abs(dx) > w * 0.25f
                val velPass = abs(vx) >= minFlingVelocity
                // 方向需与预览方向一致，避免反向手势误切换
                val dirMatch = (dx < 0f && swipeDir > 0) || (dx > 0f && swipeDir < 0)

                if ((distPass || velPass) && dirMatch && peekIndex != currentTabIndex) {
                    commitSwipe()
                } else {
                    snapBack()
                }
            }
        }

        fabApp?.setOnClickListener {
            if (Prefs.getAiEntryMode(this) == Prefs.AI_ENTRY_MODE_CHAT) {
                startActivity(
                    Intent(this, ChatActivity::class.java)
                        .putExtra(
                            ChatActivity.EXTRA_SOURCE_BOOK,
                            BookAccountManager.getSelectedBook(this)
                        )
                        .putExtra(
                            ChatActivity.EXTRA_MODE,
                            ChatActivity.MODE_ACCOUNTING
                        )
                )
            } else {
                showAddBillBottomSheet()
            }
        }

    // 若已开启敲击，则在启动时拉起悬浮服务
        val serviceIntent = Intent(this, OverlayService::class.java)
        val needsService = Prefs.isFlipEnabled(this) || Prefs.isDoubleTapEnabled(this)
        if (needsService) {
            OverlayService.startCompat(this, serviceIntent)
        }

        if (savedInstanceState == null) {
            swipeContainer.post { showHomeOnboardingIfNeeded() }
        }
        swipeContainer.post { checkSharedInviteIntent(intent) }
    }

    private fun checkSharedInviteIntent(source: Intent?) {
        if (source?.action != Intent.ACTION_SEND || source.type != "text/plain") return
        val text = source.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        source.action = null
        val invite = InviteCodec.decode(text) ?: return
        pendingInviteDialog = true
        showInviteConfirmDialog("收到共享账本邀请", invite) {
            showJoinOptionsDialog(invite, "handled_invite_${invite.ledgerId}_${invite.memberId}")
        }
    }

    private fun checkSharedInviteClipboard() {
        if (pendingInviteDialog) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val invite = InviteCodec.decode(text) ?: return
        val key = "handled_invite_${invite.ledgerId}_${invite.memberId}"
        if (getSharedPreferences("shared_invites", MODE_PRIVATE).getBoolean(key, false)) return
        pendingInviteDialog = true
        showInviteConfirmDialog("发现共享账本邀请", invite) { showJoinOptionsDialog(invite, key) }
    }

    private fun showInviteConfirmDialog(title: String, invite: SharedInvite, onJoin: () -> Unit) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_delete_followup_confirm, null, false)
        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting)).setView(panel).create()
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = title
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = "是否加入“${invite.ledgerName}”？"
        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            pendingInviteDialog = false
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = "加入"
            setOnClickListener { dialog.dismiss(); onJoin() }
        }
        dialog.setOnCancelListener { pendingInviteDialog = false }
        OverlayDialogs.showPageCenterDialog(dialog, this, widthRatio = 0.86f)
    }

    /** 剪贴板隔离或系统禁止后台读取时的可靠加入入口。 */
    fun showJoinSharedLedgerDialog() {
        showJoinInputDialog(
            title = "加入共享账本",
            subtitle = "请粘贴对方发送的完整邀请文本",
            hint = "邀请文本",
            buttonText = "下一步",
            multiline = true
        ) { input, dialog, button ->
            val invite = InviteCodec.decode(input.text.toString())
            if (invite == null) {
                input.error = "邀请文本无效或不完整"
                return@showJoinInputDialog
            }
            dialog.dismiss()
            pendingInviteDialog = true
            showJoinOptionsDialog(invite, "handled_invite_${invite.ledgerId}_${invite.memberId}")
        }
    }

    private fun showJoinInputDialog(
        title: String,
        subtitle: String,
        hint: String,
        buttonText: String,
        multiline: Boolean = false,
        password: Boolean = false,
        initialValue: String? = null,
        onConfirm: (EditText, AlertDialog, TextView) -> Unit
    ) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_shared_join_input, null, false)
        val input = panel.findViewById<EditText>(R.id.et_shared_join_input).apply {
            this.hint = hint
            if (multiline) {
                minLines = 4
                maxLines = 8
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else if (password) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                panel.findViewById<TextInputLayout>(R.id.layout_shared_join_input).endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            if (!initialValue.isNullOrBlank()) {
                setText(initialValue)
                setSelection(text?.length ?: 0)
            }
        }
        panel.findViewById<TextView>(R.id.tv_shared_join_title).text = title
        panel.findViewById<TextView>(R.id.tv_shared_join_subtitle).text = subtitle
        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting)).setView(panel).create()
        panel.findViewById<TextView>(R.id.btn_shared_join_cancel).setOnClickListener {
            pendingInviteDialog = false
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_shared_join_confirm).apply {
            text = buttonText
            setOnClickListener { onConfirm(input, dialog, this) }
        }
        dialog.setOnCancelListener { pendingInviteDialog = false }
        OverlayDialogs.showPageCenterDialog(dialog, this, widthRatio = 0.88f)
    }

    private fun showJoinOptionsDialog(invite: SharedInvite, handledKey: String) {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(applicationContext)
                val names = linkedSetOf<String>().apply {
                    addAll(BookAccountManager.getBookAccounts(this@MainActivity))
                    addAll(db.billDao().getAllBookNames())
                    addAll(db.bookDao().getAll().map { it.name })
                }
                names.filter { name ->
                    name.isNotBlank() && name != BookAccountManager.ALL_BOOK &&
                        db.bookDao().getByName(name)?.let { db.sharedLedgerDao().getByBookId(it.id) == null } != false
                }
            }
            if (isFinishing) return@launch
            val panel = LayoutInflater.from(this@MainActivity).inflate(R.layout.dialog_book_delete_options, null, false)
            panel.findViewById<TextView>(R.id.tv_delete_book_title).text = "选择加入方式"
            panel.findViewById<TextView>(R.id.tv_delete_book_desc).text = "可创建新账本，也可将本机已有账本合并进来"
            val container = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)
            val dialog = AlertDialog.Builder(ContextThemeWrapper(this@MainActivity, R.style.Theme_TapAccounting)).setView(panel).create()
            val options = listOf(null) + books
            options.forEach { book ->
                val item = LayoutInflater.from(this@MainActivity).inflate(R.layout.item_book_delete_option, container, false)
                item.findViewById<TextView>(R.id.tv_delete_option_title).text =
                    book?.let { "合并到已有账本“$it”" } ?: "创建新账本“${invite.ledgerName}”"
                item.findViewById<TextView>(R.id.tv_delete_option_desc).text =
                    if (book == null) "使用共享账本名称创建一个新账本" else "上传其中的普通收支、退款和预算"
                item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility = View.GONE
                item.setOnClickListener {
                    dialog.dismiss()
                    showJoinMemberNameDialog(invite, handledKey, book)
                }
                container.addView(item)
            }
            panel.findViewById<TextView>(R.id.btn_delete_book_cancel).setOnClickListener {
                pendingInviteDialog = false
                dialog.dismiss()
            }
            dialog.setOnCancelListener { pendingInviteDialog = false }
            OverlayDialogs.showPageCenterDialog(dialog, this@MainActivity, widthRatio = 0.88f)
        }
    }

    private fun showJoinMemberNameDialog(invite: SharedInvite, handledKey: String, existingBookName: String?) {
        showJoinInputDialog(
            title = "设置我的成员名称",
            subtitle = "名称由你自己填写，加入后会显示给共享账本中的其他成员",
            hint = "我的成员名称",
            buttonText = "下一步",
            initialValue = Prefs.getUserChatName(this).trim().takeUnless { it == "我" }
        ) { input, dialog, _ ->
            val memberName = input.text.toString().trim()
            if (memberName.isBlank()) {
                input.error = "请输入你的成员名称"
                return@showJoinInputDialog
            }
            dialog.dismiss()
            showJoinPasswordDialog(invite, handledKey, existingBookName, memberName)
        }
    }

    private fun showJoinPasswordDialog(
        invite: SharedInvite,
        handledKey: String,
        existingBookName: String?,
        memberName: String
    ) {
        val title = existingBookName?.let { "合并“$it”并加入" } ?: "加入 ${invite.ledgerName}"
        showJoinInputDialog(title, "请输入创建者同一坚果云账号的应用密码", "坚果云应用密码", "加入", password = true) { input, dialog, button ->
            val password = input.text.toString()
            if (password.isBlank()) {
                input.error = "请输入应用密码"
                return@showJoinInputDialog
            }
            button.isEnabled = false
            lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { SharedLedgerService(applicationContext, AppDatabase.getDatabase(applicationContext)).join(invite, password, memberName, existingBookName) } }
                        .onSuccess { ledgerId ->
                            val db = AppDatabase.getDatabase(applicationContext)
                            val ledger = withContext(Dispatchers.IO) { db.sharedLedgerDao().getById(ledgerId) }
                            val initialSyncError = withContext(Dispatchers.IO) {
                                db.syncStateDao().get(ledgerId)?.lastError
                            }
                            val bookName = ledger?.let { withContext(Dispatchers.IO) { db.bookDao().getById(it.bookId)?.name } }
                            if (!bookName.isNullOrBlank()) {
                                BookAccountManager.addBookAccount(this@MainActivity, bookName)
                                BookAccountManager.setSelectedBook(this@MainActivity, bookName)
                            }
                            getSharedPreferences("shared_invites", MODE_PRIVATE).edit().putBoolean(handledKey, true).apply()
                            dialog.dismiss(); pendingInviteDialog = false
                            val message = if (initialSyncError.isNullOrBlank()) {
                                "已加入共享账本"
                            } else {
                                "已加入，但首次同步失败：$initialSyncError。稍后会自动重试"
                            }
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            recreate()
                        }.onFailure {
                            button.isEnabled = true
                            Toast.makeText(this@MainActivity, it.message ?: "加入失败", Toast.LENGTH_LONG).show()
                        }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab_index", currentTabIndex)
    }

    override fun onResume() {
        super.onResume()
        checkSharedInviteClipboard()
        com.taostudio.tapaccounting.data.sync.SharedSyncScheduler.enqueueNow(this)
        RecentTasksHelper.ensureTaskVisible(this)
        refreshBottomNavigationTabs()
        swipeContainer.post { RecurringDuePromptController.maybeShow(this) }
    }

    override fun onPause() {
        super.onPause()
        // 离开 App 时立即刷新；账单写路径另有防抖刷新，这里保证回桌面即可见最新数据。
        com.taostudio.tapaccounting.widget.ExpenseWidgetUpdater.refreshAll(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        swipeContainer.post { checkSharedInviteIntent(intent) }
        resolveRequestedTabIndex(intent)?.let { requestedIndex ->
            if (requestedIndex in tabIds.indices && isTabVisible(requestedIndex)) {
                val previousIndex = currentTabIndex
                if (requestedIndex != previousIndex) {
                    switchTab(requestedIndex, if (requestedIndex > previousIndex) 1 else -1, fromSwipe = false)
                } else {
                    refreshBottomNavigationTabs()
                }
            }
        }
    }

    override fun onDestroy() {
        settleAnimator?.cancel()
        settleAnimator = null
        super.onDestroy()
    }

    private fun updateFabVisibility() {
        val fab = fabApp ?: return
        if (currentTabIndex == 0) {
            val homeVisible = (tabFragments.getOrNull(0) as? HomeFragment)?.shouldShowMainFab() ?: true
            if (homeVisible) {
                fab.show()
            } else {
                fab.hide()
            }
        } else {
            fab.hide()
        }
    }

    private fun setupMainWindowInsets() {
        val bottomNav = bottomNavigationView ?: return
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            navBarBottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = bottomNavBasePaddingBottom + navBarBottomInset)
            updateMainBottomOffsets()
            insets
        }
        bottomNav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMainBottomOffsets()
        }
        ViewCompat.requestApplyInsets(bottomNav)
    }

    private fun updateMainBottomOffsets() {
        val bottomNav = bottomNavigationView ?: return
        val bottomNavHeight = bottomNav.height.takeIf { it > 0 } ?: return
        val containerLp = swipeContainer.layoutParams as? ViewGroup.MarginLayoutParams
        if (containerLp != null && containerLp.bottomMargin != bottomNavHeight) {
            containerLp.bottomMargin = bottomNavHeight
            swipeContainer.layoutParams = containerLp
        }
        val fab = fabApp ?: return
        val fabLp = fab.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val spacingAboveNav = (24f * resources.displayMetrics.density).toInt()
        val desiredMargin = bottomNavHeight + spacingAboveNav
        if (fabLp.bottomMargin != desiredMargin) {
            fabLp.bottomMargin = desiredMargin.coerceAtLeast(fabBaseBottomMargin + navBarBottomInset)
            fab.layoutParams = fabLp
        }
    }

    private fun isTabVisible(index: Int): Boolean {
        if (index !in tabIds.indices) return false
        return tabIds[index] != R.id.nav_assets || Prefs.isAssetFeatureEnabled(this)
    }

    private fun resolveRequestedTabIndex(intent: Intent?): Int? {
        if (intent == null || !intent.hasExtra(EXTRA_OPEN_TAB_INDEX)) return null
        return intent.getIntExtra(EXTRA_OPEN_TAB_INDEX, 0)
    }

    private fun findAdjacentVisibleTabIndex(direction: Int): Int? {
        var index = currentTabIndex + direction
        while (index in tabIds.indices) {
            if (isTabVisible(index)) return index
            index += direction
        }
        return null
    }

    fun refreshBottomNavigationTabs(ensureValidSelection: Boolean = true) {
        bottomNavigationView?.menu?.findItem(R.id.nav_assets)?.isVisible = Prefs.isAssetFeatureEnabled(this)
        if (ensureValidSelection && !isTabVisible(currentTabIndex)) {
            currentTabIndex = 0
            val tx = supportFragmentManager.beginTransaction()
            tabFragments.forEachIndexed { index, fragment ->
                if (fragment != null) {
                    if (index == currentTabIndex) tx.show(fragment) else tx.hide(fragment)
                }
            }
            tx.commitNowAllowingStateLoss()
        }
        bottomNavigationView?.selectedItemId = tabIds[currentTabIndex]
        updateFabVisibility()
    }

    // Tab 切换动画相关

    /** 返回当前显示的 Fragment */
    private fun curFragment(): Fragment? = tabFragments.getOrNull(currentTabIndex)

    /**
     * 确认切换：让当前页与目标页在同一时间轴内平移并完成过渡。
     */
    private fun commitSwipe() {
        settleAnimator?.cancel()
        isSwitching = true
        val w = swipeContainer.width.toFloat().coerceAtLeast(1f)

        val peekFrag = peekFragment
        val curFrag = curFragment()
        val curView = curFrag?.view
        val peekView = peekFrag?.view

        hideAssetFabForTransition(curFrag)
        hideAssetFabForTransition(peekFrag)

    // 当前页起始偏移
        val fromOffset = curView?.translationX ?: 0f
    // 目标：当前页移出屏幕，目标页归位到 0
        val toOffset = if (swipeDir > 0) -w else w

        val newIndex = peekIndex
        val savedDir = swipeDir
        closeHomeDrawerIfLeaving(newIndex)

        settleAnimator = ValueAnimator.ofFloat(fromOffset, toOffset).apply {
            duration = UiMotion.NORMAL
            interpolator = DecelerateInterpolator(2.0f)
            addUpdateListener { va ->
                val offset = va.animatedValue as Float
                val progress = (abs(offset) / w).coerceIn(0f, 1f)
                curView?.translationX = offset
                curView?.alpha = 1f - 0.18f * progress
                val curScale = 1f - 0.035f * progress
                curView?.scaleX = curScale
                curView?.scaleY = curScale
                peekView?.translationX = if (savedDir > 0) offset + w else offset - w
                peekView?.alpha = 0.78f + 0.22f * progress
                val peekScale = 0.985f + 0.015f * progress
                peekView?.scaleX = peekScale
                peekView?.scaleY = peekScale
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // hide 当前，show 目标，保留 View 树，零重建
                    val cur = curFrag
                    val target = peekFrag ?: tabFragments[newIndex] ?: makeFragment(newIndex)
                    val tx = supportFragmentManager.beginTransaction()
                    if (cur != null) tx.hide(cur)
                    tx.show(target)
                    tx.commitNowAllowingStateLoss()
                    resetTabViewState(cur?.view)
                    resetTabViewState(target.view)
                    currentTabIndex = newIndex
                    peekFragment = null
                    peekIndex = -1
                    swipeDir = 0
                    bottomNavigationView?.setOnItemSelectedListener(null)
                    bottomNavigationView?.selectedItemId = tabIds[newIndex]
                    rebindBottomNav()
                    updateFabVisibility()
                    showAssetFabForActiveTab(target)
                    isSwitching = false
                }
            })
            start()
        }
    }

    /**
     * 回弹：把当前页恢复到 offset=0，并隐藏预加载页。
     */
    private fun snapBack() {
        settleAnimator?.cancel()
        val w = swipeContainer.width.toFloat().coerceAtLeast(1f)

        val peekFrag = peekFragment
        val curFrag = curFragment()
        val curView = curFrag?.view
        val peekView = peekFrag?.view

        val fromOffset = curView?.translationX ?: 0f
        val savedDir = swipeDir

        settleAnimator = ValueAnimator.ofFloat(fromOffset, 0f).apply {
            duration = UiMotion.SLOW
            interpolator = DecelerateInterpolator(2.5f)
            addUpdateListener { va ->
                val offset = va.animatedValue as Float
                val progress = (abs(offset) / w).coerceIn(0f, 1f)
                curView?.translationX = offset
                curView?.alpha = 1f - 0.18f * progress
                val curScale = 1f - 0.035f * progress
                curView?.scaleX = curScale
                curView?.scaleY = curScale
                peekView?.translationX = if (savedDir > 0) offset + w else offset - w
                peekView?.alpha = 0.78f + 0.22f * progress
                val peekScale = 0.985f + 0.015f * progress
                peekView?.scaleX = peekScale
                peekView?.scaleY = peekScale
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    resetTabViewState(curView)
                    if (peekFrag != null) {
                        supportFragmentManager.beginTransaction()
                            .hide(peekFrag)
                            .commitNowAllowingStateLoss()
                    }
                    resetTabViewState(peekView)
                    peekFragment = null
                    peekIndex = -1
                    swipeDir = 0
                    isSwitching = false
                }
            })
            start()
        }
    }

    /**
     * BottomNav 点击切换入口；必要时带入动画参数。
     */
    private fun switchTab(newIndex: Int, dir: Int, fromSwipe: Boolean, currentDx: Float = 0f) {
        if (isSwitching) return
        isSwitching = true
        closeHomeDrawerIfLeaving(newIndex)
        // 清理临时 peek 状态
        settleAnimator?.cancel()
        if (peekFragment != null) {
            try {
                supportFragmentManager.beginTransaction()
                    .hide(peekFragment!!)
                    .commitNow()
            } catch (_: Exception) {}
            peekFragment = null; peekIndex = -1; swipeDir = 0
        }

        val w = swipeContainer.width.toFloat().coerceAtLeast(1f)
        val curFrag = curFragment()
        val newFrag = tabFragments[newIndex] ?: makeFragment(newIndex).also { tabFragments[newIndex] = it }
        val tx = supportFragmentManager.beginTransaction()
        tx.show(newFrag)
        tx.commitNow()

        hideAssetFabForTransition(curFrag)
        hideAssetFabForTransition(newFrag)

        val curView = curFrag?.view
        val newView = newFrag.view
        val useSlideMotion = fromSwipe
        val enterFrom = if (useSlideMotion) {
            if (dir > 0) w * 0.45f else -w * 0.45f
        } else {
            0f
        }
        newView?.translationX = enterFrom
        newView?.alpha = if (useSlideMotion) {
            if (fromSwipe) 0.78f else 0.92f
        } else {
            0f
        }
        newView?.scaleX = if (useSlideMotion) {
            if (fromSwipe) 0.985f else 0.998f
        } else {
            0.992f
        }
        newView?.scaleY = if (useSlideMotion) {
            if (fromSwipe) 0.985f else 0.998f
        } else {
            0.992f
        }
        curView?.translationX = 0f
        curView?.alpha = 1f
        curView?.scaleX = 1f
        curView?.scaleY = 1f

        currentTabIndex = newIndex

        var finishedAnimations = 0
        val expectedAnimations = (if (curView != null && curFrag != newFrag) 1 else 0) + (if (newView != null) 1 else 0)
        val finishSwitch = {
            finishedAnimations += 1
            if (finishedAnimations >= expectedAnimations) {
                if (curFrag != null && curFrag != newFrag) {
                    supportFragmentManager.beginTransaction()
                        .hide(curFrag)
                        .commitNowAllowingStateLoss()
                    resetTabViewState(curView)
                }
                resetTabViewState(newView)
                showAssetFabForActiveTab(newFrag)
                isSwitching = false
            }
        }

        if (curView != null && curFrag != newFrag) {
            curView.animate()
                .translationX(if (useSlideMotion) {
                    if (dir > 0) {
                        if (fromSwipe) -w * 0.12f else -w * 0.015f
                    } else {
                        if (fromSwipe) w * 0.12f else w * 0.015f
                    }
                } else {
                    0f
                })
                .alpha(if (useSlideMotion) {
                    if (fromSwipe) 0.82f else 0.96f
                } else {
                    0f
                })
                .scaleX(if (useSlideMotion) {
                    if (fromSwipe) 0.975f else 0.998f
                } else {
                    0.992f
                })
                .scaleY(if (useSlideMotion) {
                    if (fromSwipe) 0.975f else 0.998f
                } else {
                    0.992f
                })
                .setDuration(when {
                    !useSlideMotion -> UiMotion.FAST
                    fromSwipe -> UiMotion.NORMAL
                    else -> UiMotion.FAST
                })
                .setInterpolator(when {
                    fromSwipe -> AccelerateInterpolator(1.45f)
                    useSlideMotion -> DecelerateInterpolator(1.6f)
                    else -> DecelerateInterpolator(1.5f)
                })
                .withLayer()
                .withEndAction(finishSwitch)
                .start()
        }

        if (newView != null) {
            newView.animate().cancel()
            newView.animate()
                .translationX(0f)
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(when {
                    !useSlideMotion -> UiMotion.FAST
                    fromSwipe -> UiMotion.NORMAL
                    else -> UiMotion.FAST
                })
                .setInterpolator(when {
                    fromSwipe -> DecelerateInterpolator(2.0f)
                    useSlideMotion -> DecelerateInterpolator(1.8f)
                    else -> DecelerateInterpolator(1.5f)
                })
                .withLayer()
                .withEndAction(finishSwitch)
                .start()
        } else if (expectedAnimations == 0) {
            isSwitching = false
            showAssetFabForActiveTab(newFrag)
        }

        bottomNavigationView?.setOnItemSelectedListener(null)
        bottomNavigationView?.selectedItemId = tabIds[newIndex]
        rebindBottomNav()
        updateFabVisibility()
    }

    private fun animateTo(
        target: android.view.View?,
        toX: Float, toAlpha: Float,
        durationMs: Long,
        interp: android.view.animation.Interpolator,
        onEnd: (() -> Unit)? = null
    ) {
        target ?: return
        settleAnimator?.cancel()
        val fromX = target.translationX
        val fromA = target.alpha
        settleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = interp
            addUpdateListener {
                val t = it.animatedFraction
                target.translationX = fromX + (toX - fromX) * t
                target.alpha = fromA + (toAlpha - fromA) * t
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    target.translationX = toX
                    target.alpha = toAlpha
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun hideAssetFabForTransition(fragment: Fragment?) {
        (fragment as? AssetsFragment)?.hideAssetFab()
    }

    private fun showAssetFabForActiveTab(fragment: Fragment?) {
        (fragment as? AssetsFragment)?.view?.post {
            (fragment as? AssetsFragment)?.showAssetFab()
        }
    }

    private fun resetTabViewState(view: android.view.View?) {
        view ?: return
        view.translationX = 0f
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun rebindBottomNav() {
        bottomNavigationView?.setOnItemSelectedListener { item ->
            val newIndex = tabIds.indexOf(item.itemId)
            if (newIndex < 0 || newIndex == currentTabIndex) return@setOnItemSelectedListener true
            val dir = if (newIndex > currentTabIndex) 1 else -1
            switchTab(newIndex, dir, fromSwipe = false)
            true
        }
    }

    private fun closeHomeDrawerIfLeaving(targetTabIndex: Int) {
        if (currentTabIndex == 0 && targetTabIndex != 0) {
            (tabFragments.getOrNull(0) as? HomeFragment)?.closeBookDrawerFromHost()
        }
        if (currentTabIndex == 2 && targetTabIndex != 2) {
            (tabFragments.getOrNull(2) as? AssetsFragment)?.closeAssetDrawerFromHost()
        }
    }

    private fun makeFragment(index: Int): Fragment = when (tabIds[index]) {
        R.id.nav_home -> HomeFragment()
        R.id.nav_stats -> StatsFragment()
        R.id.nav_assets -> AssetsFragment()
        R.id.nav_profile -> ProfileFragment()
        else -> HomeFragment()
    }

    private fun commitFragment(fragment: Fragment, animate: Boolean) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun showHomeOnboardingIfNeeded() {
        if (homeOnboardingShownThisSession) return
        if (Prefs.hasSeenHomeOnboarding(this)) return
        // 已经开启任一快捷手势时不再提示首次引导
        if (Prefs.isFlipEnabled(this) || Prefs.isDoubleTapEnabled(this)) {
            Prefs.setHomeOnboardingSeen(this)
            return
        }
        // 仅在首页展示首次提示，避免跨页打断
        if (currentTabIndex != 0) return
        val root = findViewById<View>(android.R.id.content) ?: return
        homeOnboardingShownThisSession = true
        root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (currentTabIndex != 0) return@postDelayed
            Snackbar.make(
                root,
                getString(R.string.onboarding_welcome_snackbar_message),
                Snackbar.LENGTH_LONG
            )
                .setAction(getString(R.string.onboarding_welcome_go_settings)) {
                    startActivity(Intent(this, SensitivityActivity::class.java))
                }
                .addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        Prefs.setHomeOnboardingSeen(this@MainActivity)
                    }
                })
                .show()
        }, 900L)
    }

    private fun showAddBillBottomSheet() {
        AddBillEntrySheetLauncher.show(
            activity = this,
            onShow = { fabApp?.hide() },
            onDismiss = { updateFabVisibility() }
        )
    }

    /**
     * Check if touch point (screen absolute coordinates) is on the PieChart in Stats page.
     * Stats page (index 1) PieChart should handle gestures independently,
     * not intercepted by page swipe.
     */
    private fun isSwipeTouchOnPieChart(rawX: Float, rawY: Float): Boolean {
        val statsFrag = tabFragments.getOrNull(1)
        val statsView = statsFrag?.view ?: return false

        val pieChart = statsView.findViewById<com.github.mikephil.charting.charts.PieChart?>(R.id.pie_chart)
            ?: return false

        // Use screen absolute coordinates to compare with PieChart's screen position
        val location = IntArray(2)
        pieChart.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + pieChart.width
        val bottom = top + pieChart.height

        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }
}
