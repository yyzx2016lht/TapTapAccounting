package com.taostudio.tapaccounting.ui.main.home

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.ColorDrawable
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.room.InvalidationTracker
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yalantis.ucrop.UCrop
import com.bumptech.glide.Glide
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.CategoryIconPreloader
import com.taostudio.tapaccounting.ChatActivity
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.repository.BillRepository
import com.taostudio.tapaccounting.logic.BudgetService
import com.taostudio.tapaccounting.data.sync.SharedSyncEngine
import com.taostudio.tapaccounting.data.sync.SharedSyncScheduler
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import com.taostudio.tapaccounting.ui.common.StatusBarStyle
import com.taostudio.tapaccounting.ui.common.UiMotion
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.MainActivity
import com.taostudio.tapaccounting.ui.main.YearMonthPickerDialog
import com.taostudio.tapaccounting.ui.main.SharedYearMonthSession
import com.taostudio.tapaccounting.viewscope.LedgerMemberScope
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min

@Suppress("UNCHECKED_CAST")
class HomeFragment : Fragment() {

    private lateinit var barChart: BarChart
    private var roundedBarChartRenderer: RoundedBarChartRenderer? = null
    private lateinit var cvChartContainer: View  // 动态 inflate 的图表卡片，作为 adapter header item
    private lateinit var rvTransactions: RecyclerView
    private lateinit var layoutEmptyView: View
    private lateinit var btnEmptyAddBill: View
    private lateinit var homeAdapter: HomeAdapter
    private lateinit var bookDrawerController: HomeBookDrawerController
    private lateinit var bannerController: HomeBannerController
    private lateinit var multiSelectController: HomeMultiSelectController
    private lateinit var chartController: HomeChartController
    private lateinit var uiListController: HomeUiListController
    private lateinit var refreshController: HomeRefreshController
    private lateinit var dataController: HomeDataController
    private var isMultiSelectModeActive = false

    // 顶部封面区域
    private lateinit var headerBannerLayout: FrameLayout
    private lateinit var ivHeaderBanner: ImageView
    private lateinit var layoutHeaderSummary: View
    private lateinit var layoutStickyTopBar: View
    private var homeAppBar: com.google.android.material.appbar.AppBarLayout? = null
    private var headerExpandedHeightPx: Int = 0
    private var headerCollapsedHeightPx: Int = 0
    private var chartExpandedHeightPx: Int = 0
    private var chartAllowedByState: Boolean = true
    private var currentScrollOffsetPx: Int = 0
    // AppBarLayout 当前垂直偏移量（0=完全展开，负值=已折叠），用于下拉刷新守卫
    private var appBarVerticalOffset: Int = 0

    // 图片选取/裁剪用的临时 Uri
    private val ucropResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val resultUri = UCrop.getOutput(result.data!!) ?: return@registerForActivityResult
                try {
                    val destFile = uriToBookBannerFile(selectedBookName)
                    // UCrop 输出的是 file:// URI，直接用 File 操作，避免 ContentResolver 在
                    // Android 7+ 上无法读取 file:// URI 导致写入失败。
                    val srcFile = resultUri.path?.let { File(it) }
                    if (srcFile != null && srcFile.exists() && srcFile.length() > 0) {
                        srcFile.copyTo(destFile, overwrite = true)
                        saveBannerAndRefresh(destFile)
                    } else {
                        Toast.makeText(requireContext(), "裁剪结果文件不存在，请重试", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "保存封面失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
            UCrop.RESULT_ERROR -> {
                val error = UCrop.getError(result.data!!)
                Toast.makeText(requireContext(), "裁剪失败，请重新选择图片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 从相册选图后，启动 UCrop 内置裁剪 */
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        startUCrop(uri)
    }

    private fun saveBannerAndRefresh(destFile: File) {
        BookAccountManager.setBookBannerPath(requireContext(), selectedBookName, destFile.absolutePath)
        updateHeaderBanner()
        Toast.makeText(requireContext(), "封面已更新", Toast.LENGTH_SHORT).show()
    }

    /** 申请读图权限，授权后直接启动图片选取器 */
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickImageLauncher.launch("image/*")
        else Toast.makeText(requireContext(), "需要相册权限才能选择图片", Toast.LENGTH_SHORT).show()
    }

    private lateinit var tvMonthExpense: TextView
    private lateinit var tvMonthExpenseLabel: TextView
    private lateinit var tvMonthIncomeLabel: TextView
    private lateinit var tvMonthIncome: TextView
    private lateinit var tvMonthBalanceLabel: TextView
    private lateinit var tvMonthBalance: TextView
    private lateinit var progressBudgetBar: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var tvMonthSelector: TextView
    private lateinit var tvChartTotal: TextView
    private lateinit var tvChartTitle: TextView
    private lateinit var vBannerTopScrim: View
    private lateinit var vBannerGradient: View
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var ivCalendarView: ImageView
    private lateinit var ivSearchBill: ImageView
    private lateinit var ivSyncStatus: ImageView
    private lateinit var layoutSyncError: View
    private lateinit var tvSyncError: TextView
    private lateinit var ivBookSwitcher: ImageView
    private lateinit var drawerBooks: DrawerLayout
    private lateinit var layoutBookDrawer: View
    private lateinit var rvBookAccounts: RecyclerView
    private var rvBookAccountsBasePaddingTop: Int = 0
    private var rvBookAccountsBasePaddingBottom: Int = 0
    private lateinit var btnAddBookAccount: View
    private lateinit var layoutAddBookInput: View
    private lateinit var etAddBookAccountName: EditText
    private lateinit var btnAddBookSetDefaultToggle: TextView
    private lateinit var btnConfirmAddBook: View
    private lateinit var btnCancelAddBook: View
    private var bookDrawerBasePaddingBottom: Int = 0
    private var selectedBookName: String = BookAccountManager.DEFAULT_BOOK
    private var availableBookNames: List<String> = BookAccountManager.withAllBookOption(listOf(BookAccountManager.DEFAULT_BOOK), BookAccountManager.DEFAULT_BOOK)
    // 抽屉关闭动画期间不做重刷新；等 onDrawerClosed 后再切账本
    private var pendingBookSwitchName: String? = null
    // 切账本后首次数据显示时做一次轻量淡入，缓解“突然出现”的突兀感
    private var animateNextBookDataReveal: Boolean = false
    // 首次加载数据后做一次 stagger 入场动画，只播放一次
    private var hasPlayedInitialStagger: Boolean = false

    private lateinit var layoutMultiSelectActions: View
    private lateinit var btnMsCancel: View
    private lateinit var btnMsSelectAll: View
    private lateinit var btnMsDelete: View
    private lateinit var btnMsMoveBook: View
    private var multiSelectActionsBaseBottomMargin: Int = 0

    // Settings state
    // range: 0=7d, 1=15d, 2=week
    private var currentTimeRange: Int = 0
    // type: 0=expense, 1=income, 2=both
    private var currentType: Int = 0
    private var isChartHidden: Boolean = false
    // fetchJob 已迁移到 HomeViewModel，Fragment 内不再持有
    // 防止 onViewCreated 之后 onResume 立即重复触发一次加载
    private var skipNextResume: Boolean = false
    private var summaryRenderGeneration: Long = 0L

    /** Activity 作用域 ViewModel，跨 Fragment 重建存活，StateFlow 缓存账单数据 */
    private val homeViewModel: HomeViewModel by activityViewModels()

    // Month picker state
    private var selectedYear: Int = SharedYearMonthSession.getYearMonth().first
    private var selectedMonth: Int = SharedYearMonthSession.getYearMonth().second

    // Optimizations: avoid repeating creation of simple date formats
    private val dfMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val dfChartKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dfWeekday = SimpleDateFormat("E", Locale.CHINESE)
    private val dfDay = SimpleDateFormat("d", Locale.getDefault())
    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailTimeShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val billSheetsController by lazy(LazyThreadSafetyMode.NONE) {
        HomeBillSheetsController(
            fragment = this,
            dfDetailTime = dfDetailTime,
            dfDetailTimeShort = dfDetailTimeShort,
            onDataChanged = { homeViewModel.reload() }
        )
    }
    private val accountCurrencyById = mutableMapOf<Long, String>()
    private val accountCurrencyByName = mutableMapOf<String, String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("HomePerf", "=== onViewCreated START ===")

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::drawerBooks.isInitialized && drawerBooks.isDrawerOpen(GravityCompat.START)) {
                    drawerBooks.closeDrawer(GravityCompat.START)
                    return
                }
                if (isMultiSelectModeActive) {
                    homeAdapter.clearSelection()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressed()
                    isEnabled = true
                }
            }
        })

        barChart = view.findViewById(R.id.barChart)
        // cvChartContainer 现在通过独立布局动态 inflate，不再通过 fragment_home.xml 的 findViewById
        cvChartContainer = layoutInflater.inflate(R.layout.item_home_chart, null, false)
        barChart = cvChartContainer.findViewById(R.id.barChart)
        tvChartTotal = cvChartContainer.findViewById(R.id.tvChartTotal)
        tvChartTitle = cvChartContainer.findViewById(R.id.tvChartTitle)
        cvChartContainer.findViewById<ImageView>(R.id.ivChartSettings)?.setOnClickListener {
            showChartSettingsDialog()
        }
        rvTransactions = view.findViewById(R.id.rvTransactions)
        layoutEmptyView = layoutInflater.inflate(R.layout.item_home_empty, null, false)
        btnEmptyAddBill = layoutEmptyView.findViewById(R.id.btnEmptyAddBill)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        drawerBooks = view.findViewById(R.id.drawerBooks)
        layoutBookDrawer = view.findViewById(R.id.layoutBookDrawer)
        applyBookDrawerAdaptiveWidth()
        bookDrawerBasePaddingBottom = layoutBookDrawer.paddingBottom

        tvMonthExpense = view.findViewById(R.id.tvMonthExpense)
        tvMonthExpenseLabel = view.findViewById(R.id.tvMonthExpenseLabel)
        tvMonthIncomeLabel = view.findViewById(R.id.tvMonthIncomeLabel)
        tvMonthIncome = view.findViewById(R.id.tvMonthIncome)
        tvMonthBalanceLabel = view.findViewById(R.id.tvMonthBalanceLabel)
        tvMonthBalance = view.findViewById(R.id.tvMonthBalance)
        progressBudgetBar = view.findViewById(R.id.progressBudgetBar)
        tvMonthSelector = view.findViewById(R.id.tvMonthSelector)
        ivCalendarView = view.findViewById(R.id.ivCalendarView)
        ivSearchBill = view.findViewById(R.id.ivSearchBill)
        ivSyncStatus = view.findViewById(R.id.ivSyncStatus)
        layoutSyncError = view.findViewById(R.id.layoutSyncError)
        tvSyncError = view.findViewById(R.id.tvSyncError)
        ivBookSwitcher = view.findViewById(R.id.ivBookSwitcher)
        // tvChartTotal / tvChartTitle 已在 cvChartContainer inflate 时绑定，此处不再重复
        vBannerTopScrim = view.findViewById(R.id.vBannerTopScrim)
        vBannerGradient = view.findViewById(R.id.vBannerGradient)
        rvBookAccounts = view.findViewById(R.id.rvBookAccounts)
        rvBookAccountsBasePaddingTop = rvBookAccounts.paddingTop
        rvBookAccountsBasePaddingBottom = rvBookAccounts.paddingBottom
        btnAddBookAccount = view.findViewById(R.id.btnAddBookAccount)
        view.findViewById<View>(R.id.btnJoinSharedLedger).setOnClickListener {
            drawerBooks.closeDrawer(GravityCompat.START)
            (activity as? com.taostudio.tapaccounting.MainActivity)?.showJoinSharedLedgerDialog()
        }
        layoutAddBookInput = view.findViewById(R.id.layoutAddBookInput)
        etAddBookAccountName = view.findViewById(R.id.etAddBookAccountName)
        btnAddBookSetDefaultToggle = view.findViewById(R.id.btnAddBookSetDefaultToggle)
        btnConfirmAddBook = view.findViewById(R.id.btnConfirmAddBook)
        btnCancelAddBook = view.findViewById(R.id.btnCancelAddBook)
        bookDrawerController = HomeBookDrawerController(
            fragment = this,
            homeViewModel = homeViewModel,
            drawerBooks = drawerBooks,
            layoutBookDrawer = layoutBookDrawer,
            rvBookAccounts = rvBookAccounts,
            btnViewScope = view.findViewById(R.id.btnViewScope),
            btnAddBookAccount = btnAddBookAccount,
            layoutAddBookInput = layoutAddBookInput,
            etAddBookAccountName = etAddBookAccountName,
            btnAddBookSetDefaultToggle = btnAddBookSetDefaultToggle,
            btnConfirmAddBook = btnConfirmAddBook,
            btnCancelAddBook = btnCancelAddBook,
            bookDrawerBasePaddingBottom = bookDrawerBasePaddingBottom,
            rvBookAccountsBasePaddingTop = rvBookAccountsBasePaddingTop,
            rvBookAccountsBasePaddingBottom = rvBookAccountsBasePaddingBottom,
            getSelectedBookName = { selectedBookName },
            setSelectedBookName = { selectedBookName = it },
            getAvailableBookNames = { availableBookNames },
            setAvailableBookNames = { availableBookNames = it },
            getPendingBookSwitchName = { pendingBookSwitchName },
            setPendingBookSwitchName = { pendingBookSwitchName = it },
            setAnimateNextBookDataReveal = { animateNextBookDataReveal = it },
            getSelectedYear = { selectedYear },
            getSelectedMonth = { selectedMonth },
            getCurrentTimeRange = { currentTimeRange },
            getCurrentType = { currentType },
            updateHeaderBanner = { updateHeaderBanner() },
            updateHomeFabVisibilityByDrawerState = { updateHomeFabVisibilityByDrawerState() },
            applyHomeFabDrawerProgress = { slideOffset -> applyHomeFabDrawerProgress(slideOffset) },
            dismissKeyboardForDialog = { dismissKeyboardForDialog() },
            configureDialogWindow = { dialog, width, dim -> configureDialogWindow(dialog, width, dim) },
        )

        layoutMultiSelectActions = view.findViewById(R.id.layout_multi_select_actions)
        btnMsCancel = view.findViewById(R.id.btn_ms_cancel)
        btnMsSelectAll = view.findViewById(R.id.btn_ms_select_all)
        btnMsDelete = view.findViewById(R.id.btn_ms_delete)
        btnMsMoveBook = view.findViewById(R.id.btn_ms_move_book)
        multiSelectActionsBaseBottomMargin =
            (layoutMultiSelectActions.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        multiSelectController = HomeMultiSelectController(
            fragment = this,
            layoutMultiSelectActions = layoutMultiSelectActions,
            btnMsCancel = btnMsCancel,
            btnMsSelectAll = btnMsSelectAll,
            btnMsDelete = btnMsDelete,
            btnMsMoveBook = btnMsMoveBook,
            multiSelectActionsBaseBottomMargin = multiSelectActionsBaseBottomMargin,
            getAvailableBookNames = { availableBookNames },
            getHomeAdapter = { homeAdapter },
            dismissKeyboardForDialog = { dismissKeyboardForDialog() },
            configureDialogWindow = { dialog, width, dim -> configureDialogWindow(dialog, width, dim) },
            onDataChanged = { homeViewModel.reload() },
        )

        headerBannerLayout = view.findViewById(R.id.headerBannerLayout)
        ivHeaderBanner = view.findViewById(R.id.ivHeaderBanner)
        layoutHeaderSummary = view.findViewById(R.id.layoutHeaderSummary)
        layoutStickyTopBar = view.findViewById(R.id.layoutStickyTopBar)
        bannerController = HomeBannerController(
            fragment = this,
            headerBannerLayout = headerBannerLayout,
            ivHeaderBanner = ivHeaderBanner,
            vBannerTopScrim = vBannerTopScrim,
            vBannerGradient = vBannerGradient,
            tvMonthSelector = tvMonthSelector,
            tvMonthExpense = tvMonthExpense,
            tvMonthExpenseLabel = tvMonthExpenseLabel,
            tvMonthIncomeLabel = tvMonthIncomeLabel,
            tvMonthIncome = tvMonthIncome,
            tvMonthBalanceLabel = tvMonthBalanceLabel,
            tvMonthBalance = tvMonthBalance,
            progressBudgetBar = progressBudgetBar,
            ivBookSwitcher = ivBookSwitcher,
            ivCalendarView = ivCalendarView,
            ivSearchBill = ivSearchBill,
            getSelectedBookName = { selectedBookName },
            requestPickBannerImage = { pickBannerImage() },
            dismissKeyboardForDialog = { dismissKeyboardForDialog() },
            configureDialogWindow = { dialog, width, dim -> configureDialogWindow(dialog, width, dim) },
        )

        // 监听 AppBarLayout 折叠偏移，用于：
        //   1. 保护下拉刷新（完全展开才允许触发）
        //   2. 月份统计区渐隐（layoutHeaderSummary alpha）
        //   3. 折叠后半段 banner 区渐变为纯白背景
    homeAppBar = view.findViewById(R.id.homeAppBar)
        homeAppBar?.addOnOffsetChangedListener(com.google.android.material.appbar.AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
            appBarVerticalOffset = verticalOffset
            val totalScrollRange = appBar.totalScrollRange
            if (totalScrollRange == 0) return@OnOffsetChangedListener
            // verticalOffset 在 [−totalScrollRange, 0] 之间，fraction: 0=完全展开, 1=完全折叠
            val fraction = (-verticalOffset).toFloat() / totalScrollRange.toFloat()

            // 月份统计摘要区：前 50% 折叠内渐隐完
            val summaryAlpha = (1f - fraction * 2f).coerceIn(0f, 1f)
            layoutHeaderSummary.alpha = summaryAlpha

            // Banner 区白色遮罩：后 50% 折叠进度内从透明渐变为纯白
            // 使 banner 图片/颜色在折叠完成时呈现为白底，和账单列表背景融合
            val whiteAlpha = ((fraction - 0.5f) * 2f).coerceIn(0f, 1f)
            val whiteOverlay = (whiteAlpha * 255).toInt()
            headerBannerLayout.foreground = android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(whiteOverlay, 255, 255, 255)
            )
            // 同步状态栏：根据当前顶部背景明暗统一更新图标颜色。
            syncHomeStatusBarByWhiteOverlay(whiteAlpha)
            // 同步固定顶栏图标/文字颜色，避免白底白字。
            syncBannerTopBarTextColorByWhiteOverlay(whiteAlpha)
        })
        uiListController = HomeUiListController(
            fragment = this,
            rvTransactions = rvTransactions,
            layoutEmptyView = layoutEmptyView,
            layoutMultiSelectActions = layoutMultiSelectActions,
            btnMsDelete = btnMsDelete,
            swipeRefreshLayout = swipeRefreshLayout,
            cvChartContainer = cvChartContainer,
            layoutStickyTopBar = layoutStickyTopBar,
            ivBookSwitcher = ivBookSwitcher,
            tvMonthSelector = tvMonthSelector,
            ivCalendarView = ivCalendarView,
            ivSearchBill = ivSearchBill,
            layoutHeaderSummary = layoutHeaderSummary,
            getHomeAppBar = { homeAppBar },
            getAppBarVerticalOffset = { appBarVerticalOffset },
            isBookDrawerOpen = { isBookDrawerOpen() },
            getIsMultiSelectModeActive = { isMultiSelectModeActive },
            setIsMultiSelectModeActive = { isMultiSelectModeActive = it },
            getHomeAdapter = { homeAdapter },
            setHomeAdapter = { homeAdapter = it },
            homeViewModel = homeViewModel,
            onShowBillDetailSheet = { bill -> showBillDetailSheet(bill) },
            onRefreshAccountCurrencyCache = { dataController.refreshAccountCurrencyCache() },
        )
        refreshController = HomeRefreshController(
            fragment = this,
            swipeRefreshLayout = swipeRefreshLayout,
            homeViewModel = homeViewModel,
            getSelectedBookName = { selectedBookName },
            getSelectedYear = { selectedYear },
            getSelectedMonth = { selectedMonth },
            getCurrentTimeRange = { currentTimeRange },
            getCurrentType = { currentType },
            onUpdateHomeFabVisibility = { updateHomeFabVisibilityByDrawerState() },
        )
        dataController = HomeDataController(
            fragment = this,
            accountCurrencyById = accountCurrencyById,
            accountCurrencyByName = accountCurrencyByName,
            getHomeAdapter = { homeAdapter }
        )

        setupTopBarDoubleTapToTop()

        setupRecyclerView()
        setupChart()
        setupMultiSelectActions()
        setupMultiSelectActionsBottomOffset()
        setupBookDrawer()
        setupBookDrawerImeInsets()
        setupBannerLongPress()

        // 空状态"记一笔"按钮：复用 FAB 的添加账单入口
        btnEmptyAddBill.setOnClickListener {
            (activity as? MainActivity)?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_add)?.performClick()
        }

        updateMonthSelectorText()
        tvMonthSelector.setOnClickListener {
            showMonthYearPicker()
        }

        ivBookSwitcher.setOnClickListener {
            drawerBooks.openDrawer(GravityCompat.START)
        }

        ivCalendarView.setOnClickListener {
            // open calendar with selected year/month
            val intent = Intent(requireContext(), CalendarActivity::class.java)
            intent.putExtra("YEAR", selectedYear)
            intent.putExtra("MONTH", selectedMonth)
            intent.putExtra("BOOK_NAME", selectedBookName)
            startActivity(intent)
        }
        ivSearchBill.setOnClickListener {
            val intent = Intent(requireContext(), BillSearchActivity::class.java).apply {
                putExtra(BillSearchActivity.EXTRA_SOURCE_BOOK, selectedBookName)
            }
            startActivity(intent)
        }

        ivSyncStatus.setOnClickListener {
            runSharedSync()
        }

        // ivChartSettings 的点击已在 cvChartContainer inflate 时设置，此处无需再设置

        refreshController.setupPullToRefresh()

        // 立即用 SharedPrefs 缓存同步渲染 Banner，消除首帧闪白（selectedBookName 已从 SharedPrefs 恢复）
        selectedBookName = BookAccountManager.getSelectedBook(requireContext())
        updateHeaderBanner()

        // 每次进入首页时，在后台预热所有分类图标缓存，确保历史账单图标在联网后能正确显示
        lifecycleScope.launch(Dispatchers.IO) {
            CategoryIconPreloader.preloadAll(requireContext().applicationContext)
        }

        // 告知 ViewModel 当前参数（若首次进入或参数有变化则触发查询，否则直接用缓存）
        Log.d("HomePerf", "calling syncAndLoad, current StateFlow bills=${homeViewModel.uiState.value.monthlyBills.size}")
        homeViewModel.syncAndLoad(
            bookName = selectedBookName,
            year = selectedYear,
            month = selectedMonth,
            timeRange = currentTimeRange,
            type = currentType,
            isChartHidden = !Prefs.isShowHomeTrendCard(requireContext())
        )
        // 收集 StateFlow：Fragment 重建后立刻收到上次缓存的账单数据，无需等待新的 DB 查询
        val collectStartMs = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.uiState.collect { state ->
                    val lag = System.currentTimeMillis() - collectStartMs
                    Log.d("HomePerf", "collect emission: bills=${state.monthlyBills.size}  isLoading=${state.isLoading}  [+${lag}ms since collect registered]")
                    // 同步本地 UI 状态字段（用于 UI 联动，如日历跳转等）
                    val prevBookName = selectedBookName
                    selectedBookName = state.selectedBookName
                    selectedYear = state.selectedYear
                    selectedMonth = state.selectedMonth
                    currentTimeRange = state.currentTimeRange
                    currentType = state.currentType
                    isChartHidden = state.isChartHidden
                    state.viewScope?.let { scope ->
                        homeAdapter.setViewContext(
                            contextsByBookName = scope.memberContextsByBookName,
                            showMembers = scope.scope.members == LedgerMemberScope.EVERYONE,
                            showBookNames = false
                        )
                        homeAdapter.detailSuffixProvider = if (scope.isAggregate) {
                            { bill -> "账本：${BookAccountManager.normalizeBookName(bill.bookName)}" }
                        } else {
                            null
                        }
                    }
                    refreshSharedUi()
                    // 切换账本时重置 stagger 标记，让新账本数据也做入场动画
                    if (prevBookName != selectedBookName) {
                        hasPlayedInitialStagger = false
                    }
                    updateMonthSelectorText()
                    updateChartTitleLabel()
                    syncTrendCardState()
                    dataController.refreshAccountCurrencyCache()

                    val monthlyBills = state.monthlyBills
                    val adapterT0 = System.currentTimeMillis()
                    homeAdapter.showEmptyState = !state.isLoading && monthlyBills.isEmpty()

                    if (state.isLoading && monthlyBills.isEmpty()) {
                        // 正在切换账本/加载中，且还没有新数据：直接清空列表，不走 DiffUtil
                        homeAdapter.submitList(emptyList())
                        rvTransactions.requestLayout()
                    } else {
                        val shouldAnimateReveal = animateNextBookDataReveal && !state.isLoading && monthlyBills.isNotEmpty()
                        if (shouldAnimateReveal) {
                            animateNextBookDataReveal = false
                            rvTransactions.alpha = 0.92f
                        }
                        // 直接调用 submitList，Adapter 内部已在子线程计算 DiffUtil 再切回主线程 dispatch，
                        // 不需要额外的 view.post{}，否则多个任务堆积在主线程消息队列会触发连续动画导致 5s+ 触摸冻结
                        homeAdapter.submitList(monthlyBills)
                        if (shouldAnimateReveal) {
                            rvTransactions.animate()
                                .alpha(1f)
                                .setDuration(140L)
                                .setStartDelay(16L)
                                .start()
                        }
                        Log.d("HomePerf", "submitList called: ${monthlyBills.size} bills  [${System.currentTimeMillis() - adapterT0}ms]")
                        rvTransactions.requestLayout()
                        crossfadeSummaryAmounts(
                            transactions = monthlyBills,
                            displayMode = state.displayMode,
                            bookName = state.selectedBookName,
                            year = state.selectedYear,
                            month = state.selectedMonth,
                            viewScope = state.viewScope
                        )

                        // 首次加载到数据时，对首屏可见的前几项做 stagger 入场动画
                        if (!hasPlayedInitialStagger && !state.isLoading && monthlyBills.isNotEmpty()) {
                            hasPlayedInitialStagger = true
                            UiMotion.staggerFirstLoadAnimation(rvTransactions, maxItems = 6, itemDelayMs = 40L, startDelayMs = 100L)
                        }

                        // 只有当加载完成且真的没有账单时，才复位空列表的滚动位置。
                        if (!state.isLoading && monthlyBills.isEmpty()) {
                            animateNextBookDataReveal = false
                            // 无账单时也可能保留趋势卡；主动展开 AppBar，保证趋势卡和空态从顶部开始展示。
                            homeAppBar?.setExpanded(true, false)
                            appBarVerticalOffset = 0
                            (rvTransactions.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(0, 0)
                        }
                    }

                    // 趋势卡和空态都是 RecyclerView 内容，空月份时两者可按顺序同时展示。
                    layoutEmptyView.visibility = if (homeAdapter.showEmptyState) View.VISIBLE else View.GONE
                    rvTransactions.visibility = View.VISIBLE

                    val filteredForChart = state.filteredByBook.filter {
                        it.time in state.chartStart..state.chartEnd
                    }
                    updateChart(filteredForChart)

                    refreshController.onStateCollected(monthlyBills, state.isLoading)

                    updateHomeFabVisibilityByDrawerState()
                }
            }
        }

        // 后台刷新账本列表 UI（异步，不阻塞账单加载）
        observeBillTableChanges()
        refreshBookAccounts(reloadTransactions = false)
        refreshSharedUi()
        SharedSyncScheduler.enqueueNow(requireContext())
        skipNextResume = true  // onViewCreated 已触发加载，紧随其后的 onResume 无需重复
        
        // 立刻关闭 loading 圈，显示上次缓存的静态数据，后台无声更新
        refreshController.resetRefreshState()
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarForHome()
        view?.post {
            if (isAdded && !isHidden) {
                syncHomeStatusBarByWhiteOverlay(getCurrentHomeWhiteOverlayAlpha())
            }
        }
        updateChartTitleLabel()
        refreshTrendCardVisibility(forceResubmit = true)
        syncDateFromSessionIfNeeded()
        selectedBookName = BookAccountManager.normalizeBookName(
            BookAccountManager.getSelectedBook(requireContext())
        )
        // 先同步渲染已缓存的 Banner，消除从其它 Tab 切回时的闪白
        updateHeaderBanner()
        if (skipNextResume) {
            skipNextResume = false
            return  // 跳过本次，避免与 onViewCreated 的加载重复
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val state = homeViewModel.uiState.value
            crossfadeSummaryAmounts(
                transactions = state.monthlyBills,
                displayMode = state.displayMode,
                bookName = state.selectedBookName,
                year = state.selectedYear,
                month = state.selectedMonth,
                viewScope = state.viewScope
            )
        }
        refreshBookAccounts(reloadTransactions = true)
    }

    override fun onPause() {
        super.onPause()
        if (isMultiSelectModeActive) {
            homeAdapter.clearSelection()
        }
        // 注意：不要在 onPause 里切换 decorFitsSystemWindows。
        // 打开设置/分类等新 Activity 时，onPause 也会触发，
        // 此处若切到 decorFits=true 会导致当前窗口根视图瞬时整体下移（用户可见“抖一下”）。
        // 状态栏恢复逻辑改为仅在 Tab 真正隐藏时执行（onHiddenChanged hidden=true）。
    }

    private fun refreshSharedUi() {
        if (!isAdded || !::ivSyncStatus.isInitialized) return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(requireContext().applicationContext)
                val sharedBookName = homeViewModel.uiState.value.viewScope?.singleBookName
                    ?: selectedBookName.takeIf { it != BookAccountManager.ALL_BOOK }
                val ledger = sharedBookName?.let { db.sharedLedgerDao().getByBookName(it) }
                if (ledger == null) null else Triple(
                    ledger,
                    db.sharedMemberDao().getByLedgerId(ledger.id).associate { it.memberId to it.resolvedName() },
                    db.syncStateDao().get(ledger.id) to db.syncQueueDao().count(ledger.id)
                )
            }
            ivSyncStatus.visibility = if (result == null) View.GONE else View.VISIBLE
            val state = result?.third?.first
            val pending = result?.third?.second ?: 0
            layoutSyncError.visibility = if (state?.lastError.isNullOrBlank()) View.GONE else View.VISIBLE
            tvSyncError.text = state?.lastError.orEmpty()
            ivSyncStatus.contentDescription = when {
                state?.isSyncing == true -> "同步中"
                !state?.lastError.isNullOrBlank() -> "同步失败：${state?.lastError}"
                pending > 0 -> "待上传 $pending 项"
                else -> "同步完成"
            }
        }
    }

    private fun runSharedSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ledgerId = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).sharedLedgerDao().getByBookName(selectedBookName)?.id
            } ?: return@launch
            runCatching { withContext(Dispatchers.IO) { SharedSyncEngine(requireContext().applicationContext, AppDatabase.getDatabase(requireContext())).syncLedger(ledgerId) } }
            refreshSharedUi()
            homeViewModel.forceReload(selectedBookName, selectedYear, selectedMonth, currentTimeRange, currentType, !Prefs.isShowHomeTrendCard(requireContext()))
        }
    }

    /**
     * MainActivity 使用 hide/show 管理 Tab，切换回账单页时不触发 onResume，
     * 在此处做统计→账单的月份反向同步：
     * - 统计页处于"月模式"时：将账单页日期同步为统计页当前年月
     * - 统计页处于"年模式"时：保留账单页切走时的年月，不修改
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            if (isMultiSelectModeActive) {
                homeAdapter.clearSelection()
            }
            // 切离账单 Tab：仅更新状态栏颜色/图标，不切换 decorFits，
            // 避免下一页（如设置页）首次进入首帧整体下移。
            restoreDefaultStatusBarForOtherTabs()
            return
        }
        if (!hidden) {
            // hide/show 切回账单页时不会触发 onResume，这里补一次 Home 状态栏样式。
            applyStatusBarForHome()
            view?.post {
                if (isAdded && !isHidden) {
                    syncHomeStatusBarByWhiteOverlay(getCurrentHomeWhiteOverlayAlpha())
                }
            }
            updateChartTitleLabel()
            refreshTrendCardVisibility(forceResubmit = true)
            syncDateFromSessionIfNeeded()
            selectedBookName = BookAccountManager.normalizeBookName(
                BookAccountManager.getSelectedBook(requireContext())
            )
            // 统计页可能已切换全局账本，这里补一次账本同步与数据刷新。
            refreshBookAccounts(reloadTransactions = true)
            return
        }
    }

    fun isBookDrawerOpen(): Boolean {
        return ::drawerBooks.isInitialized && drawerBooks.isDrawerOpen(GravityCompat.START)
    }

    fun openBookDrawerFromHost() {
        if (::drawerBooks.isInitialized && !drawerBooks.isDrawerOpen(GravityCompat.START)) {
            drawerBooks.openDrawer(GravityCompat.START)
        }
    }

    fun closeBookDrawerFromHost() {
        if (::drawerBooks.isInitialized && drawerBooks.isDrawerOpen(GravityCompat.START)) {
            drawerBooks.closeDrawer(GravityCompat.START)
        }
    }

    private fun applyBookDrawerAdaptiveWidth() {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val maxWidth = (312f * density).toInt()
        val sideGap = (48f * density).toInt()
        val minWidth = (272f * density).toInt()
        val targetWidth = min(maxWidth, screenWidth - sideGap).coerceAtLeast(min(minWidth, screenWidth))
        val lp = layoutBookDrawer.layoutParams ?: return
        if (lp.width != targetWidth) {
            lp.width = targetWidth
            layoutBookDrawer.layoutParams = lp
        }
    }

    fun shouldShowHomeFab(): Boolean {
        return ::layoutEmptyView.isInitialized &&
            layoutEmptyView.visibility != View.VISIBLE &&
            !isBookDrawerOpen() &&
            !isMultiSelectModeActive
    }

    private fun updateMonthSelectorText() {
        val mode = homeViewModel.uiState.value.displayMode
        tvMonthSelector.text = when (mode) {
            YearMonthPickerDialog.DisplayMode.MONTH ->
                "$selectedYear-${String.format(Locale.getDefault(), "%02d", selectedMonth)}"
            YearMonthPickerDialog.DisplayMode.YEAR ->
                "${selectedYear}年"
            YearMonthPickerDialog.DisplayMode.ALL ->
                "全部"
        }
    }

    private fun showMonthYearPicker() {
        val state = homeViewModel.uiState.value
        YearMonthPickerDialog.showModePicker(
            context = requireContext(),
            initialYear = selectedYear,
            initialMonth = selectedMonth,
            initialMode = state.displayMode,
            enabledModes = listOf(
                YearMonthPickerDialog.DisplayMode.MONTH,
                YearMonthPickerDialog.DisplayMode.YEAR,
                YearMonthPickerDialog.DisplayMode.ALL
            ),
            onPickMonth = { year, month ->
                selectedYear = year
                selectedMonth = month
                updateMonthSelectorText()
                homeViewModel.setMonth(selectedYear, selectedMonth)
            },
            onPickYear = { year ->
                selectedYear = year
                updateMonthSelectorText()
                homeViewModel.setYearMode(selectedYear)
            },
            onPickAll = {
                updateMonthSelectorText()
                homeViewModel.setAllBillsMode()
            }
        )
    }

    private fun setupBookDrawer() {
        bookDrawerController.setupBookDrawer()
    }

    private fun setupBookDrawerImeInsets() {
        bookDrawerController.setupBookDrawerImeInsets()
    }

    // ─── 封面图相关 ────────────────────────────────────────────────────────────

    /** 为顶部横幅区域设置长按事件，弹出操作菜单 */
    private fun setupBannerLongPress() {
        bannerController.setupBannerLongPress()
    }

    /** 检查权限，有权限则直接选图，否则先申请 */
    private fun pickBannerImage() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            pickImageLauncher.launch("image/*")
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    /**
     * 启动 UCrop 内置裁剪界面（16:9，1280×720 输出）。
     * 完全在 App 内运行，无需调用任何第三方 App，无权限兼容性问题。
     */
    private fun startUCrop(sourceUri: Uri) {
        val ctx = requireContext()
        val destFile = File(ctx.cacheDir, "banner_crop/ucrop_out_${System.currentTimeMillis()}.jpg")
            .also { it.parentFile?.mkdirs() }
        val destUri = Uri.fromFile(destFile)

        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(false)
            setToolbarTitle("裁剪封面图")
            setToolbarColor(
                BookAccountManager.getBookColor(ctx, selectedBookName)
            )
            setStatusBarColor(
                BookAccountManager.getBookColor(ctx, selectedBookName)
            )
            setToolbarWidgetColor(android.graphics.Color.WHITE)
        }

        UCrop.of(sourceUri, destUri)
            .withAspectRatio(16f, 9f)
            .withMaxResultSize(1280, 720)
            .withOptions(options)
            .getIntent(ctx)
            .also { ucropResultLauncher.launch(it) }
    }

    /**
     * 根据账本名返回对应封面图的持久化文件（filesDir/banners/）。
     * 文件名用账本名的 hashCode 保证唯一性。
     */
    private fun uriToBookBannerFile(bookName: String): File {
        val dir = File(requireContext().filesDir, "banners").also { it.mkdirs() }
        return File(dir, "banner_${bookName.hashCode()}.jpg")
    }

    /** 根据当前账本刷新顶部横幅：有图片则显示图片，否则用账本专属颜色 */
    fun updateHeaderBanner() {
        bannerController.updateHeaderBanner()
        // 防止在已折叠白底状态下被 updateHeaderBanner 重置成浅色文字。
        syncBannerTopBarTextColorByWhiteOverlay(getCurrentHomeWhiteOverlayAlpha())
    }

    /**
     * 统一设置 header 区域内所有文字和图标的颜色。
     * [useLightText] = true → 白色（适合深色/图片背景）
     * [useLightText] = false → 深灰（适合浅色主题色背景）
     */
    private fun applyBannerTextColor(useLightText: Boolean) {
        bannerController.applyBannerTextColor(useLightText)
    }

    private fun syncBannerTopBarTextColorByWhiteOverlay(whiteAlpha: Float) {
        // 使用略低阈值，减少临界值抖动；白底阶段强制深色文字和图标。
        if (whiteAlpha > 0.4f) {
            applyBannerTextColor(useLightText = false)
            return
        }
        val bannerPath = BookAccountManager.getBookBannerPath(requireContext(), selectedBookName)
        val useLightText = !bannerPath.isNullOrEmpty() || run {
            val c = BookAccountManager.getBookColor(requireContext(), selectedBookName)
            val lum = 0.299 * android.graphics.Color.red(c) +
                0.587 * android.graphics.Color.green(c) +
                0.114 * android.graphics.Color.blue(c)
            lum < 160
        }
        applyBannerTextColor(useLightText = useLightText)
    }

    // ──────────────────────────────────────────────────────────────────────────

    private fun updateHomeFabVisibilityByDrawerState() {
        uiListController.updateHomeFabVisibilityByDrawerState()
    }

    private fun applyHomeFabDrawerProgress(slideOffset: Float) {
        uiListController.applyHomeFabDrawerProgress(slideOffset)
    }

    fun shouldShowMainFab(): Boolean {
        if (!isAdded || !isVisible) return false
        return shouldShowHomeFab()
    }

    private fun syncDateFromSessionIfNeeded() {
        val (sessionYear, sessionMonth) = SharedYearMonthSession.getYearMonth()
        if (sessionYear != selectedYear || sessionMonth != selectedMonth) {
            selectedYear = sessionYear
            selectedMonth = sessionMonth
            homeViewModel.setMonth(sessionYear, sessionMonth)
        }
    }

    private fun refreshBookAccounts(reloadTransactions: Boolean) {
        bookDrawerController.refreshBookAccounts(reloadTransactions)
    }

    private fun dismissKeyboardForDialog() {
        val act = activity ?: return
        val focus = act.currentFocus ?: return
        focus.clearFocus()
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(focus.windowToken, 0)
    }

    private fun applyDialogMotion(dialog: Dialog) {
        fun applyWindowStyle() {
            dialog.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.decorView?.fitsSystemWindows = false
                window.setWindowAnimations(R.style.Animation_TapAccounting_DialogSoft)
            }
        }
        dialog.setOnShowListener { applyWindowStyle() }
        applyWindowStyle()
    }

    private fun configureDialogWindow(dialog: Dialog, width: Int, dimAmount: Float = 0.34f) {
        fun applyWindowStyle() {
            dialog.window?.apply {
                WindowCompat.setDecorFitsSystemWindows(this, false)
                decorView?.fitsSystemWindows = false
                setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                )
                setBackgroundDrawableResource(R.drawable.shape_dialog_bg)
                setGravity(Gravity.CENTER)
                setDimAmount(dimAmount)
                setWindowAnimations(R.style.Animation_TapAccounting_DialogSoft)
                attributes = attributes.apply {
                    this.width = width
                    this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
        }
        dialog.setOnShowListener { applyWindowStyle() }
        applyWindowStyle()
    }

    private fun setupMultiSelectActions() {
        multiSelectController.setupMultiSelectActions()
    }

    private fun setupMultiSelectActionsBottomOffset() {
        multiSelectController.setupMultiSelectActionsBottomOffset()
    }

    private fun setupRecyclerView() {
        uiListController.setupRecyclerView()
    }

    private fun setupTopBarDoubleTapToTop() {
        uiListController.setupTopBarDoubleTapToTop()
    }

    private fun applyHomeCollapseByScroll(offsetPx: Int) {
        uiListController.applyHomeCollapseByScroll(offsetPx)
    }

    private fun applyStatusBarForHome() {
        uiListController.applyStatusBarForHome()
        syncHomeStatusBarByWhiteOverlay(getCurrentHomeWhiteOverlayAlpha())
    }

    private fun restoreDefaultStatusBarForOtherTabs() {
        uiListController.restoreDefaultStatusBarForOtherTabs()
    }

    private fun getCurrentHomeWhiteOverlayAlpha(): Float {
        val overlayAlpha = (headerBannerLayout.foreground as? ColorDrawable)?.alpha
        if (overlayAlpha != null) {
            return (overlayAlpha / 255f).coerceIn(0f, 1f)
        }
        val appBar = homeAppBar ?: return 0f
        val totalScrollRange = appBar.totalScrollRange
        if (totalScrollRange <= 0) return 0f
        val fraction = (-appBarVerticalOffset).toFloat() / totalScrollRange.toFloat()
        return ((fraction - 0.5f) * 2f).coerceIn(0f, 1f)
    }

    private fun syncHomeStatusBarByWhiteOverlay(whiteAlpha: Float) {
        if (!isAdded) return
        val useDarkStatusBarContent = whiteAlpha > 0.5f
        val window = requireActivity().window
        StatusBarStyle.apply(
            window = window,
            statusBarColor = if (useDarkStatusBarContent) Color.WHITE else Color.TRANSPARENT,
            isLightBackground = useDarkStatusBarContent
        )
    }

    private fun ensureChartController(): HomeChartController {
        if (!::chartController.isInitialized) {
            chartController = HomeChartController(
                fragment = this,
                barChart = barChart,
                tvChartTotal = tvChartTotal,
                tvChartTitle = tvChartTitle,
                tvMonthExpense = tvMonthExpense,
                tvMonthIncome = tvMonthIncome,
                tvMonthBalance = tvMonthBalance,
                homeAdapter = homeAdapter,
                homeViewModel = homeViewModel,
                getCurrentType = { currentType },
                setCurrentType = { currentType = it },
                getCurrentTimeRange = { currentTimeRange },
                setCurrentTimeRange = { currentTimeRange = it },
                getIsChartHidden = { isChartHidden },
                setIsChartHidden = { isChartHidden = it },
                setChartAllowedByState = { chartAllowedByState = it },
                getSelectedYear = { selectedYear },
                getSelectedMonth = { selectedMonth },
                getRoundedBarChartRenderer = { roundedBarChartRenderer },
                setRoundedBarChartRenderer = { roundedBarChartRenderer = it },
                dfChartKey = dfChartKey,
                dfWeekday = dfWeekday,
                dfDay = dfDay,
            )
        }
        return chartController
    }

    private fun setupChart() {
        ensureChartController().setupChart()
    }

    private fun updateChartTitleLabel() {
        ensureChartController().updateChartTitleLabel()
    }

    private fun syncTrendCardState(): Boolean {
        return ensureChartController().syncTrendCardState()
    }

    private fun refreshTrendCardVisibility(forceResubmit: Boolean = false) {
        ensureChartController().refreshTrendCardVisibility(forceResubmit)
    }

    private fun showChartSettingsDialog() {
        ensureChartController().showChartSettingsDialog()
    }

    private fun updateSummary(
        transactions: List<Bill>,
        incomeOverride: Double? = null,
        balanceOverride: Double? = null
    ) {
        ensureChartController().updateSummary(transactions, incomeOverride, balanceOverride)
    }

    /**
     * 对头部摘要金额做 crossfade 过渡，避免切换月份/账本时数字直接闪变。
     * 在 updateSummary 之前记录旧文本，之后比较并应用淡出→更新→淡入。
     */
    private suspend fun crossfadeSummaryAmounts(
        transactions: List<Bill>,
        displayMode: YearMonthPickerDialog.DisplayMode,
        bookName: String,
        year: Int,
        month: Int,
        viewScope: ResolvedLedgerViewScope?
    ) {
        val generation = ++summaryRenderGeneration
        val useBudget = displayMode == YearMonthPickerDialog.DisplayMode.MONTH &&
            (viewScope?.supportsBudgetSummary != false) &&
            Prefs.isHomeBudgetSummaryEnabled(requireContext(), bookName)
        val budgetAmounts = if (useBudget) {
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(requireContext().applicationContext)
                val budgetBook = viewScope?.singleBookName
                    ?: if (viewScope?.isAllBooks == true || BookAccountManager.normalizeBookName(bookName) == BookAccountManager.ALL_BOOK) ""
                    else BookAccountManager.normalizeBookName(bookName)
                val yearMonth = String.format(Locale.US, "%04d-%02d", year, month)
                val budget = db.budgetDao().getTotalBudget(yearMonth, budgetBook)
                val used = BudgetService(db.budgetDao(), db.billDao(), db.categoryDao())
                    .getMonthSpend(budgetBook, null, yearMonth)
                val amount = budget?.amount ?: 0.0
                // 走 BudgetService 拿到 status/percent，进度条和 ⚠ 提示才有依据；
                // 该月压根没设预算时 amount=0，不画进度条（buildProgress 会把 percent 兜成 0）。
                val progress = if (amount > 0.0) {
                    BudgetService.buildProgress(
                        budgetAmount = amount,
                        usedAmount = used,
                        alertThreshold = budget?.alertThreshold ?: 0.8,
                        yearMonth = yearMonth,
                        nowMillis = System.currentTimeMillis()
                    )
                } else {
                    null
                }
                HomeBudgetSummary(amount, used, progress)
            }
        } else {
            null
        }
        if (generation != summaryRenderGeneration || !isAdded) return

        updateSummaryLabels(displayMode, useBudget)
        applyBudgetProgressBar(budgetAmounts?.progress)
        val oldExpense = tvMonthExpense.text?.toString()
        val oldIncome = tvMonthIncome.text?.toString()
        val oldBalance = tvMonthBalance.text?.toString()
        // updateSummary 会同步设置 TextView 文本
        updateSummary(
            transactions = transactions,
            incomeOverride = budgetAmounts?.amount,
            balanceOverride = budgetAmounts?.let { it.amount - it.used }
        )
        val newExpense = tvMonthExpense.text?.toString()
        val newIncome = tvMonthIncome.text?.toString()
        val newBalance = tvMonthBalance.text?.toString()
        if (oldExpense != newExpense) {
            tvMonthExpense.alpha = 0f
            tvMonthExpense.animate().alpha(1f).setDuration(180L)
                .setInterpolator(UiMotion.STANDARD_EASING).start()
        }
        if (oldIncome != newIncome) {
            tvMonthIncome.alpha = 0f
            tvMonthIncome.animate().alpha(1f).setDuration(180L)
                .setStartDelay(30L)
                .setInterpolator(UiMotion.STANDARD_EASING).start()
        }
        if (oldBalance != newBalance) {
            tvMonthBalance.alpha = 0f
            tvMonthBalance.animate().alpha(1f).setDuration(180L)
                .setStartDelay(60L)
                .setInterpolator(UiMotion.STANDARD_EASING).start()
        }
    }

    /** 预算汇总在 banner 上要用的三样东西；[progress] 为 null 表示该月没有预算。 */
    private data class HomeBudgetSummary(
        val amount: Double,
        val used: Double,
        val progress: BudgetService.BudgetProgress?
    )

    /**
     * banner 里的预算进度条 + ⚠ 提示。
     *
     * 只用长度和文字表达预警，**不用橙/红**：banner 底色是用户可变的 16 色账本色板，
     * 写死的预警色压到黄绿/橙色底上会糊成一片。
     * [progress] 为 null 有两种情况——没启用预算汇总，或该月压根没设预算，都不该画条。
     */
    private fun applyBudgetProgressBar(progress: BudgetService.BudgetProgress?) {
        if (!::progressBudgetBar.isInitialized) return
        if (progress == null) {
            progressBudgetBar.visibility = View.GONE
            return
        }
        progressBudgetBar.visibility = View.VISIBLE
        progressBudgetBar.setProgressCompat(
            progress.percent.coerceIn(0.0, 1.0).times(100).toInt(),
            false
        )
        val status = progress.status
        tvMonthBalanceLabel.text = when (status) {
            BudgetService.BudgetStatus.EXCEEDED -> getString(R.string.home_month_budget_exceeded)
            BudgetService.BudgetStatus.WARNING -> getString(R.string.home_month_budget_warning)
            BudgetService.BudgetStatus.NORMAL ->
                getString(R.string.home_month_budget_remaining)
        }
    }

    private fun updateSummaryLabels(
        displayMode: YearMonthPickerDialog.DisplayMode,
        useBudget: Boolean
    ) {
        when (displayMode) {
            YearMonthPickerDialog.DisplayMode.MONTH -> {
                tvMonthExpenseLabel.setText(R.string.home_month_expense)
                tvMonthIncomeLabel.setText(if (useBudget) R.string.home_month_budget else R.string.home_month_income)
                tvMonthBalanceLabel.setText(if (useBudget) R.string.home_month_budget_remaining else R.string.home_month_balance)
            }
            YearMonthPickerDialog.DisplayMode.YEAR -> {
                tvMonthExpenseLabel.setText(R.string.home_year_expense)
                tvMonthIncomeLabel.setText(R.string.home_year_income)
                tvMonthBalanceLabel.setText(R.string.home_year_balance)
            }
            YearMonthPickerDialog.DisplayMode.ALL -> {
                tvMonthExpenseLabel.setText(R.string.home_all_expense)
                tvMonthIncomeLabel.setText(R.string.home_all_income)
                tvMonthBalanceLabel.setText(R.string.home_all_balance)
            }
        }
    }

    private fun updateChart(transactions: List<Bill>) {
        ensureChartController().updateChart(transactions)
    }

    private fun showBillDetailSheet(bill: Bill) {
        billSheetsController.showBillDetailSheet(bill)
    }

    private fun showRefundSheet(originalBill: Bill, editingRefund: Bill? = null) {
        billSheetsController.showRefundSheet(originalBill, editingRefund)
    }

    override fun onDestroyView() {
        refreshController.onDestroyView()
        super.onDestroyView()
    }

    private fun observeBillTableChanges() {
        refreshController.observeBillTableChanges()
    }
}
