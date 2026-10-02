package com.taostudio.tapaccounting.ui.main.assets

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.NestedScrollView
import androidx.cardview.widget.CardView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.CornerFamily
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.taostudio.tapaccounting.AddAssetActivity
import com.taostudio.tapaccounting.AssetIconDefaults
import com.taostudio.tapaccounting.CurrencyData
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.logic.CurrencyUtils
import com.taostudio.tapaccounting.logic.InvestmentInterestService
import com.taostudio.tapaccounting.ui.common.UiMotion
import com.taostudio.tapaccounting.ui.common.UiMotion.FabScrollBehavior
import com.taostudio.tapaccounting.ui.common.UiMotion.applyItemPressFeedback
import com.taostudio.tapaccounting.ui.common.UiMotion.crossfadeText

class AssetsFragment : Fragment() {
    private companion object {
        const val DRAWER_WIDTH_MAX_DP = 312f
        const val DRAWER_SIDE_GAP_DP = 48f
        const val DRAWER_WIDTH_MIN_DP = 272f

        // 极简商务风：低对比、少描边、选中用浅底 + 品牌色字
        const val DRAWER_SHELL_BG = "#FAFBFC"
        const val DRAWER_PANEL_BG = "#FFFFFF"
        const val DRAWER_MUTED_BG = "#F3F5F8"
        const val DRAWER_DIVIDER = "#E8ECF2"
        const val DRAWER_STROKE = "#E4E9F0"
        const val DRAWER_TEXT_PRIMARY = "#1F2937"
        const val DRAWER_TEXT_SECONDARY = "#6B7280"
        const val DRAWER_TEXT_HINT = "#9CA3AF"
        const val DRAWER_ACCENT = "#5C6BC0"
        const val DRAWER_ACCENT_SOFT = "#EEF0F8"
        const val DRAWER_CHIP_STROKE = "#E5E9F0"
    }

    private lateinit var tvNetAsset: TextView
    private lateinit var tvAssetAmountMode: TextView
    private lateinit var btnAssetDisplayFilter: ImageView
    private lateinit var layoutAssetsSummaryRow: View
    private lateinit var tvRateStatus: TextView
    private lateinit var tvTotalAsset: TextView
    private lateinit var tvTotalDebt: TextView
    private lateinit var tvAssetEmptyTitle: TextView
    private lateinit var tvAssetEmptySubtitle: TextView
    private lateinit var containerCategoryCards: LinearLayout
    private lateinit var nsvAssets: NestedScrollView
    private lateinit var fabAddAsset: FloatingActionButton
    private lateinit var layoutEmptyState: View
    private lateinit var drawerAssets: DrawerLayout
    private lateinit var layoutAssetDrawer: View
    private lateinit var assetDrawerContentContainer: ViewGroup

    private val db by lazy { AppDatabase.getDatabase(requireContext()) }
    private var hasTriggeredInitialRateRefresh = false
    /** FAB 显隐规则与账单首页、资产详情共用同一份（见 [FabScrollBehavior]）。 */
    private val fabScrollBehavior = FabScrollBehavior(
        hide = { hideAssetFab() },
        show = { showAssetFab() },
    )
    private var dragAutoScrollActive = false
    private var dragAutoScrollDirection = 0
    private var dragAutoScrollSpeedPx = 0
    private var currentAssets: List<Asset> = emptyList()
    private var amountDisplayMode: AssetAmountDisplayMode = AssetAmountDisplayMode(null, "CNY")
    private var sourceSearchQuery: String = ""
    private var targetSearchQuery: String = ""
    private var sourceSearchExpanded: Boolean = false
    private var targetSearchExpanded: Boolean = false
    private var savedWindowSoftInputMode: Int? = null
    private var assetDrawerBasePaddingTop = 0
    private var assetDrawerBasePaddingBottom = 0
    private val collapsedCategories = mutableSetOf<String>()
    private val expandedArchivedAssetCategories = mutableSetOf<String>()
    private var isFirstLoad = true
    private val dragAutoScrollRunner = object : Runnable {
        override fun run() {
            if (!dragAutoScrollActive || !isAdded || !::nsvAssets.isInitialized) return
            if (dragAutoScrollDirection != 0 && dragAutoScrollSpeedPx > 0) {
                nsvAssets.scrollBy(0, dragAutoScrollDirection * dragAutoScrollSpeedPx)
            }
            nsvAssets.postOnAnimation(this)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_assets, container, false)
        initViews(view)
        observeData()
        return view
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            InvestmentInterestService.settleDueInterest(db)
        }
    }

    override fun onDestroyView() {
        savedWindowSoftInputMode?.let { mode ->
            activity?.window?.setSoftInputMode(mode)
        }
        savedWindowSoftInputMode = null
        super.onDestroyView()
    }

    private fun initViews(view: View) {
        drawerAssets = view.findViewById(R.id.drawerAssets)
        layoutAssetDrawer = view.findViewById(R.id.layoutAssetDrawer)
        assetDrawerContentContainer = view.findViewById(R.id.assetDrawerContentContainer)
        applyAssetDrawerAdaptiveWidth()
        setupAssetDrawerInsets()
        setupAssetDrawer()

        tvNetAsset = view.findViewById(R.id.tv_net_asset)
        tvAssetAmountMode = view.findViewById(R.id.tv_asset_amount_mode)
        btnAssetDisplayFilter = view.findViewById(R.id.btn_asset_display_filter)
        layoutAssetsSummaryRow = view.findViewById(R.id.layout_assets_summary_row)
        tvRateStatus = view.findViewById(R.id.tv_rate_status)
        tvTotalAsset = view.findViewById(R.id.tv_total_asset)
        tvTotalDebt = view.findViewById(R.id.tv_total_debt)
        containerCategoryCards = view.findViewById(R.id.container_category_cards)
        nsvAssets = view.findViewById(R.id.nsv_assets)
        layoutEmptyState = view.findViewById(R.id.layout_empty_state)
        tvAssetEmptyTitle = view.findViewById(R.id.tv_asset_empty_title)
        tvAssetEmptySubtitle = view.findViewById(R.id.tv_asset_empty_subtitle)

        amountDisplayMode = readValidatedDisplayMode()
        updateAmountModeChip()
        btnAssetDisplayFilter.setOnClickListener {
            showAssetDisplayDrawer()
        }

        fabAddAsset = view.findViewById(R.id.fab_add_asset)
        fabAddAsset.setOnClickListener {
            startActivity(Intent(requireContext(), AddAssetActivity::class.java))
        }
        fabAddAsset.post { showAssetFab() }

        nsvAssets.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            applyFabScrollBehavior(dy, scrollY)
        }
    }

    private fun setupAssetDrawer() {
        refreshAssetDrawerContent()
        drawerAssets.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (slideOffset > 0f) {
                    drawerAssets.getChildAt(0)?.let { content ->
                        val cancel = android.view.MotionEvent.obtain(
                            0, 0, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0
                        )
                        content.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                fabAddAsset.animate()
                    .alpha(1f - slideOffset)
                    .setDuration(0L)
                    .start()
            }

            override fun onDrawerOpened(drawerView: View) {
                applyDrawerImePolicy(drawerOpen = true)
                btnAssetDisplayFilter.animate()
                    .rotation(90f)
                    .setDuration(180L)
                    .setInterpolator(UiMotion.STANDARD_EASING)
                    .start()
            }

            override fun onDrawerClosed(drawerView: View) {
                applyDrawerImePolicy(drawerOpen = false)
                btnAssetDisplayFilter.animate()
                    .rotation(0f)
                    .setDuration(160L)
                    .setInterpolator(UiMotion.STANDARD_EASING)
                    .start()
                fabAddAsset.alpha = 1f
            }
        })
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (drawerAssets.isDrawerOpen(GravityCompat.END)) {
                        drawerAssets.closeDrawer(GravityCompat.END)
                        return
                    }
                    isEnabled = false
                    requireActivity().onBackPressed()
                    isEnabled = true
                }
            }
        )
    }

    private fun setupAssetDrawerInsets() {
        assetDrawerBasePaddingTop = layoutAssetDrawer.paddingTop
        assetDrawerBasePaddingBottom = layoutAssetDrawer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(layoutAssetDrawer) { view, insets ->
            // 仅处理系统栏，忽略 IME，避免键盘顶起/收起时抽屉跳动
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(
                view.paddingLeft,
                assetDrawerBasePaddingTop + statusTop,
                view.paddingRight,
                assetDrawerBasePaddingBottom + navBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(layoutAssetDrawer)
    }

    private fun applyDrawerImePolicy(drawerOpen: Boolean) {
        val window = activity?.window ?: return
        if (drawerOpen) {
            if (savedWindowSoftInputMode == null) {
                savedWindowSoftInputMode = window.attributes.softInputMode
            }
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            return
        }

        hideAssetDrawerKeyboard()
        savedWindowSoftInputMode?.let { window.setSoftInputMode(it) }
        savedWindowSoftInputMode = null

        val hadSearchState = sourceSearchExpanded || targetSearchExpanded ||
            sourceSearchQuery.isNotBlank() || targetSearchQuery.isNotBlank()
        sourceSearchExpanded = false
        targetSearchExpanded = false
        sourceSearchQuery = ""
        targetSearchQuery = ""
        if (hadSearchState) {
            refreshAssetDrawerContent()
        }
    }

    private fun hideAssetDrawerKeyboard() {
        val focused = activity?.currentFocus
        focused?.clearFocus()
        val token = focused?.windowToken ?: layoutAssetDrawer.windowToken
        val imm = requireContext().getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun applyAssetDrawerAdaptiveWidth() {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val maxWidth = (DRAWER_WIDTH_MAX_DP * density).toInt()
        val sideGap = (DRAWER_SIDE_GAP_DP * density).toInt()
        val minWidth = (DRAWER_WIDTH_MIN_DP * density).toInt()
        val targetWidth =
            minOf(maxWidth, screenWidth - sideGap).coerceAtLeast(minOf(minWidth, screenWidth))
        val lp = layoutAssetDrawer.layoutParams ?: return
        if (lp.width != targetWidth) {
            lp.width = targetWidth
            layoutAssetDrawer.layoutParams = lp
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!::fabAddAsset.isInitialized) return
        if (hidden) {
            hideAssetFab()
        } else {
            fabAddAsset.post { showAssetFab() }
        }
    }

    fun showAssetFab() {
        if (!isAdded || !::fabAddAsset.isInitialized) return
        fabScrollBehavior.markVisible()
        fabAddAsset.show()
    }

    fun hideAssetFab() {
        if (!::fabAddAsset.isInitialized) return
        fabScrollBehavior.markHidden()
        fabAddAsset.hide()
    }

    fun isAssetDrawerOpen(): Boolean {
        return ::drawerAssets.isInitialized && drawerAssets.isDrawerOpen(GravityCompat.END)
    }

    fun openAssetDrawerFromHost() {
        if (::drawerAssets.isInitialized && !drawerAssets.isDrawerOpen(GravityCompat.END)) {
            drawerAssets.openDrawer(GravityCompat.END)
        }
    }

    fun closeAssetDrawerFromHost() {
        if (::drawerAssets.isInitialized && drawerAssets.isDrawerOpen(GravityCompat.END)) {
            drawerAssets.closeDrawer(GravityCompat.END)
        }
    }

    private fun applyFabScrollBehavior(dy: Int, scrollY: Int) {
        fabScrollBehavior.onScroll(dy, atTop = scrollY <= 0)
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            db.assetDao().getAllAssets().collectLatest { assets ->
                currentAssets = assets
                val validatedMode = readValidatedDisplayMode()
                if (validatedMode != amountDisplayMode) {
                    amountDisplayMode = validatedMode
                    Prefs.setAssetAmountDisplayMode(requireContext(), validatedMode.prefValue)
                }
                updateAmountModeChip()
                updateHeader(assets)
                buildCategoryCards(assets)
            }
        }
    }

    private data class AssetAmountDisplayMode(
        val sourceCurrency: String?,
        val targetCurrency: String
    ) {
        val prefValue: String
            get() = "source:${sourceCurrency?.uppercase() ?: "ALL"};target:${targetCurrency.uppercase()}"

        companion object {
            fun parse(raw: String): AssetAmountDisplayMode {
                val segments = raw.split(";")
                    .mapNotNull {
                        val parts = it.split(":", limit = 2)
                        if (parts.size == 2) parts[0] to parts[1] else null
                    }
                    .toMap()
                if (segments.containsKey("target")) {
                    val source = segments["source"]
                        ?.trim()
                        ?.uppercase()
                        ?.takeIf { it != "ALL" && CurrencyData.isSelectableCurrencyCode(it) }
                    val target = segments["target"]
                        ?.trim()
                        ?.uppercase()
                        ?.takeIf { CurrencyData.isSelectableCurrencyCode(it) }
                        ?: "CNY"
                    return AssetAmountDisplayMode(source, target)
                }

                val legacyParts = raw.split(":", limit = 2)
                val legacyMode = legacyParts.getOrNull(0).orEmpty()
                val legacyCurrency = legacyParts.getOrNull(1)
                    ?.trim()
                    ?.uppercase()
                    ?.takeIf { CurrencyData.isSelectableCurrencyCode(it) }
                    ?: "CNY"
                return when (legacyMode) {
                    "native" -> AssetAmountDisplayMode(sourceCurrency = legacyCurrency, targetCurrency = legacyCurrency)
                    else -> AssetAmountDisplayMode(sourceCurrency = null, targetCurrency = legacyCurrency)
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // 金额显示模式
    // ──────────────────────────────────────────────
    private fun readValidatedDisplayMode(): AssetAmountDisplayMode {
        val mode = AssetAmountDisplayMode.parse(Prefs.getAssetAmountDisplayMode(requireContext()))
        val enabledTargets = CurrencyManager.getEnabledCurrencies(requireContext())
            .map { it.uppercase() }
            .toSet() + "CNY"
        var target = mode.targetCurrency.uppercase().takeIf { enabledTargets.contains(it) } ?: "CNY"
        val source = mode.sourceCurrency
            ?.uppercase()
            ?.takeIf { CurrencyData.isSelectableCurrencyCode(it) }
        return AssetAmountDisplayMode(sourceCurrency = source, targetCurrency = target)
    }

    private fun updateAmountModeChip() {
        if (!::tvAssetAmountMode.isInitialized) return
        tvAssetAmountMode.text = "当前：${displayModeLabel(amountDisplayMode)}"
    }

    private fun displayModeLabel(mode: AssetAmountDisplayMode): String {
        val source = mode.sourceCurrency?.uppercase() ?: "全部"
        val target = mode.targetCurrency.uppercase()
        return "范围 $source · 折算 $target"
    }

    private fun currencyDisplayName(currency: String): String {
        val normalized = currency.uppercase()
        return CurrencyData.getInfo(normalized)?.nameZh
            ?.takeIf { it.isNotBlank() && it != normalized }
            ?: normalized
    }

    private fun filterAssetsForMode(assets: List<Asset>): List<Asset> {
        val sourceCurrency = amountDisplayMode.sourceCurrency ?: return assets
        return assets.filter { it.currency.equals(sourceCurrency, ignoreCase = true) }
    }

    private fun displayCurrency(): String = amountDisplayMode.targetCurrency.uppercase()

    private fun displayAmount(asset: Asset): Double {
        return CurrencyManager.convert(asset.balance, asset.currency, amountDisplayMode.targetCurrency)
    }

    private fun requiresRates(assets: List<Asset>): Boolean {
        return assets.any { !it.currency.equals(amountDisplayMode.targetCurrency, ignoreCase = true) }
    }

    private fun hasMissingRatesForDisplay(assets: List<Asset>): Boolean {
        return assets.any { !CurrencyManager.hasConversionRate(it.currency, amountDisplayMode.targetCurrency) }
    }

    private fun refreshCurrentAssetDisplay() {
        updateAmountModeChip()
        updateHeader(currentAssets)
        buildCategoryCards(currentAssets)
    }

    private fun showAssetDisplayDrawer() {
        if (::drawerAssets.isInitialized && !drawerAssets.isDrawerOpen(GravityCompat.END)) {
            // Source/target options depend on live assets and enabled currencies;
            // rebuild before opening to avoid stale drawer content.
            refreshAssetDrawerContent()
            drawerAssets.openDrawer(GravityCompat.END)
        }
    }

    private fun closeAssetDisplayDrawer() {
        if (::drawerAssets.isInitialized && drawerAssets.isDrawerOpen(GravityCompat.END)) {
            drawerAssets.closeDrawer(GravityCompat.END)
        }
    }

    private fun refreshAssetDrawerContent() {
        if (!::assetDrawerContentContainer.isInitialized) return
        assetDrawerContentContainer.removeAllViews()
        assetDrawerContentContainer.addView(
            buildAssetDisplayDrawer(),
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    // ── Build drawer content: always-visible, scrollable sections ──
    private fun buildAssetDisplayDrawer(): View {
        val ctx = requireContext()

        val contentRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val contentScroll = NestedScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isNestedScrollingEnabled = true
            addView(
                contentRoot,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val shell = MaterialCardView(ctx).apply {
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor(DRAWER_SHELL_BG))
            strokeWidth = 0
            shapeAppearanceModel = shapeAppearanceModel
                .toBuilder()
                .setTopLeftCorner(CornerFamily.ROUNDED, dp(20).toFloat())
                .setBottomLeftCorner(CornerFamily.ROUNDED, dp(20).toFloat())
                .setTopRightCorner(CornerFamily.ROUNDED, 0f)
                .setBottomRightCorner(CornerFamily.ROUNDED, 0f)
                .build()
            addView(
                contentScroll,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        contentRoot.addView(drawerHeader("资产显示", "筛选范围与折算币种", onClose = { closeAssetDisplayDrawer() }))
        contentRoot.addView(selectedScopeSummary(topMargin = dp(16)))

        val filteredSourceItems =
            sourceCurrencyCandidates().filter { currencyMatches(it, sourceSearchQuery) }
        val sourcePickerItems =
            if (sourceSearchQuery.isNotBlank() && filteredSourceItems.isEmpty()) {
                emptyList()
            } else {
                listOf("ALL") + filteredSourceItems
            }

        contentRoot.addView(cardSection(topMargin = dp(16)) {
            addCurrencyPickerBlock(
                title = "资产范围",
                searchExpanded = sourceSearchExpanded,
                searchQuery = sourceSearchQuery,
                onToggleSearch = {
                    sourceSearchExpanded = !sourceSearchExpanded
                    if (!sourceSearchExpanded) sourceSearchQuery = ""
                    refreshAssetDrawerContent()
                },
                onSearchQueryChanged = { query ->
                    if (query != sourceSearchQuery) {
                        sourceSearchQuery = query
                        refreshAssetDrawerContent()
                    }
                },
                items = sourcePickerItems,
                selectedCode = amountDisplayMode.sourceCurrency?.uppercase() ?: "ALL",
                codeLabel = { code -> if (code == "ALL") "全部" else code.uppercase() },
                onSelect = { code ->
                    val newSource = if (code == "ALL") null else code.uppercase()
                    // 切换范围时自动调整折算币种
                    val newTarget = if (newSource != null) {
                        // 选了具体币种 → 折算币种默认选中相同币种（无需换算，但能正常显示金额）
                        newSource
                    } else {
                        // 选了全部 → 保持当前折算币种
                        amountDisplayMode.targetCurrency
                    }
                    applyDisplayMode(amountDisplayMode.copy(sourceCurrency = newSource, targetCurrency = newTarget))
                    refreshAssetDrawerContent()
                },
                showEmptyHint = sourcePickerItems.isEmpty()
            )

            addView(drawerDivider())

            addCurrencyPickerBlock(
                title = "折算币种",
                searchExpanded = targetSearchExpanded,
                searchQuery = targetSearchQuery,
                onToggleSearch = {
                    targetSearchExpanded = !targetSearchExpanded
                    if (!targetSearchExpanded) targetSearchQuery = ""
                    refreshAssetDrawerContent()
                },
                onSearchQueryChanged = { query ->
                    if (query != targetSearchQuery) {
                        targetSearchQuery = query
                        refreshAssetDrawerContent()
                    }
                },
                items = targetCurrencyCandidates().filter { currencyMatches(it, targetSearchQuery) },
                selectedCode = displayCurrency(),
                codeLabel = { it.uppercase() },
                onSelect = { code ->
                    applyDisplayMode(amountDisplayMode.copy(targetCurrency = code.uppercase()))
                    refreshAssetDrawerContent()
                },
                showEmptyHint = targetCurrencyCandidates().none { currencyMatches(it, targetSearchQuery) },
                topPadding = dp(14)
            )
        })

        contentRoot.addView(TextView(ctx).apply {
            text = "范围决定统计哪些资产，折算币种决定金额单位。"
            setTextColor(Color.parseColor(DRAWER_TEXT_HINT))
            textSize = 11f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(14), 0, 0)
        })

        return shell
    }

    private fun LinearLayout.addCurrencyPickerBlock(
        title: String,
        searchExpanded: Boolean,
        searchQuery: String,
        onToggleSearch: () -> Unit,
        onSearchQueryChanged: (String) -> Unit,
        items: List<String>,
        selectedCode: String,
        codeLabel: (String) -> String,
        onSelect: (String) -> Unit,
        showEmptyHint: Boolean,
        topPadding: Int = 0
    ) {
        val ctx = context
        if (topPadding > 0) {
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    topPadding
                )
            })
        }
        addView(sectionHeader(title, searchExpanded, onToggleSearch))
        if (searchExpanded) {
            addView(searchInputRow(searchQuery, onSearchQueryChanged))
        }
        if (items.isEmpty() && showEmptyHint) {
            addView(emptySearchHint("未找到匹配币种"))
        } else if (items.isNotEmpty()) {
            addView(
                buildCodeTagGroup(
                    items = items,
                    selectedCode = selectedCode,
                    codeLabel = codeLabel,
                    onClick = onSelect
                )
            )
        }
    }

    private fun selectedScopeSummary(topMargin: Int = 0): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.parseColor(DRAWER_MUTED_BG), dp(10))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (topMargin > 0) this.topMargin = topMargin
            }
            addView(TextView(ctx).apply {
                text = "已选"
                setTextColor(Color.parseColor(DRAWER_TEXT_HINT))
                textSize = 11f
                letterSpacing = 0.02f
            })
            addView(TextView(ctx).apply {
                text = "${sourceSelectorSubtitle()}  →  ${targetSelectorSubtitle()}"
                setTextColor(Color.parseColor(DRAWER_TEXT_PRIMARY))
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun drawerDivider(): View {
        val ctx = requireContext()
        return View(ctx).apply {
            setBackgroundColor(Color.parseColor(DRAWER_DIVIDER))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(2)
            }
        }
    }

    private fun sourceSelectorSubtitle(): String {
        val source = amountDisplayMode.sourceCurrency
        val code = source?.uppercase() ?: return "全部资产"
        return "$code · ${currencyDisplayName(code)}"
    }

    private fun targetSelectorSubtitle(): String {
        val code = displayCurrency()
        return "${code.uppercase()} · ${currencyDisplayName(code)}"
    }

    private fun emptySearchHint(message: String): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = message
            setTextColor(Color.parseColor(DRAWER_TEXT_HINT))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }
    }

    private fun buildCodeTagGroup(
        items: List<String>,
        selectedCode: String,
        codeLabel: (String) -> String,
        onClick: (String) -> Unit
    ): View {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(2).forEachIndexed { rowIndex, rowItems ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (rowIndex > 0) topMargin = dp(6) }
            }
            rowItems.forEachIndexed { index, code ->
                row.addView(
                    codeTagChip(
                        text = codeLabel(code),
                        selected = code.equals(selectedCode, ignoreCase = true),
                        onClick = { onClick(code) }
                    ),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(6)
                    }
                )
            }
            repeat(2 - rowItems.size) { idx ->
                row.addView(
                    View(ctx),
                    LinearLayout.LayoutParams(0, 1, 1f).apply {
                        if (rowItems.isNotEmpty() || idx > 0) marginStart = dp(6)
                    }
                )
            }
            container.addView(row)
        }
        return container
    }

    private fun codeTagChip(
        text: String,
        selected: Boolean,
        onClick: () -> Unit
    ): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(
                color = if (selected) Color.parseColor(DRAWER_ACCENT_SOFT) else Color.parseColor(DRAWER_PANEL_BG),
                radius = dp(8),
                strokeColor = if (selected) Color.parseColor(DRAWER_ACCENT) else Color.parseColor(DRAWER_CHIP_STROKE),
                strokeWidth = if (selected) dp(1) else dp(1)
            )
            minHeight = dp(40)
            setPadding(dp(8), dp(9), dp(8), dp(9))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            this.text = text
            setTextColor(
                if (selected) Color.parseColor(DRAWER_ACCENT)
                else Color.parseColor(DRAWER_TEXT_PRIMARY)
            )
            textSize = 13f
            setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun cardSection(topMargin: Int = 0, content: LinearLayout.() -> Unit = {}): View {
        val ctx = requireContext()
        return MaterialCardView(ctx).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor(DRAWER_PANEL_BG))
            strokeColor = Color.parseColor(DRAWER_STROKE)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (topMargin > 0) this.topMargin = topMargin
            }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                content()
            })
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int,
        leftCornersOnly: Boolean = false,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            if (strokeColor != null && strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
            if (leftCornersOnly) {
                cornerRadii = floatArrayOf(
                    radius.toFloat(), radius.toFloat(),
                    0f, 0f,
                    0f, 0f,
                    radius.toFloat(), radius.toFloat()
                )
            } else {
                cornerRadius = radius.toFloat()
            }
        }
    }

    private fun drawerHeader(title: String, subtitle: String, onClose: () -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = title
                    setTextColor(Color.parseColor(DRAWER_TEXT_PRIMARY))
                    textSize = 17f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(TextView(ctx).apply {
                    text = subtitle
                    setTextColor(Color.parseColor(DRAWER_TEXT_SECONDARY))
                    textSize = 12f
                    setPadding(0, dp(2), 0, 0)
                })
            })

            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(DRAWER_TEXT_HINT)
                )
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClose() }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
        }
    }

    private fun sectionHeader(
        title: String,
        showSearch: Boolean,
        onToggleSearch: () -> Unit
    ): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            addView(TextView(ctx).apply {
                text = title
                setTextColor(Color.parseColor(DRAWER_TEXT_PRIMARY))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_search_outline)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(if (showSearch) DRAWER_ACCENT else DRAWER_TEXT_HINT)
                )
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { onToggleSearch() }
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
        }
    }

    private fun searchInputRow(
        query: String,
        onQueryChanged: (String) -> Unit
    ): View {
        val ctx = requireContext()
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(EditText(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                hint = "搜索"
                setHintTextColor(Color.parseColor(DRAWER_TEXT_HINT))
                setTextColor(Color.parseColor(DRAWER_TEXT_PRIMARY))
                textSize = 13f
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                isSingleLine = true
                setText(query)
                setSelection(text.length)
                addTextChangedListener { editable ->
                    onQueryChanged(editable?.toString().orEmpty())
                }
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            if (query.isNotBlank()) {
                addView(TextView(ctx).apply {
                    text = "清空"
                    setTextColor(Color.parseColor(DRAWER_ACCENT))
                    textSize = 12f
                    setPadding(dp(8), 0, 0, 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onQueryChanged("") }
                })
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(inputRow)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor(DRAWER_DIVIDER))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
            })
            setPadding(0, 0, 0, dp(10))
        }
    }

    private fun applyDisplayMode(mode: AssetAmountDisplayMode) {
        amountDisplayMode = mode
        Prefs.setAssetAmountDisplayMode(requireContext(), mode.prefValue)
        refreshCurrentAssetDisplay()
    }

    private fun targetCurrencyCandidates(): List<String> {
        val base = linkedSetOf<String>()
        base.add("CNY")
        base.addAll(CurrencyManager.getEnabledCurrencies(requireContext()).map { it.uppercase() })
        return base.toList()
    }

    private fun sourceCurrencyCandidates(): List<String> {
        return currentAssets
            .map { it.currency.uppercase() }
            .filter { CurrencyData.isSelectableCurrencyCode(it) }
            .distinct()
            .sorted()
    }

    private fun currencyMatches(code: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return CurrencyData.getInfo(code)?.matches(q) == true || code.contains(q, ignoreCase = true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ──────────────────────────────────────────────
    // 顶部统计
    // ──────────────────────────────────────────────
    private fun updateHeader(assets: List<Asset>) {
        val displayAssets = filterAssetsForMode(assets)
        val includedAssets = displayAssets.filter { it.includeInNetAsset && !it.isArchived }

        val needsRates = requiresRates(includedAssets)
        val hasMissingIncludedRates = hasMissingRatesForDisplay(includedAssets)
        val hasSyncedRates = CurrencyManager.getLastUpdateTime(requireContext()) > 0L

        if (needsRates && !hasSyncedRates) {
            if (!hasTriggeredInitialRateRefresh) {
                hasTriggeredInitialRateRefresh = true
                CurrencyManager.updateRates(requireContext()) { success ->
                    if (!isAdded) return@updateRates
                    if (success) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val latestAssets = db.assetDao().getAllAssetsList()
                            updateHeader(latestAssets)
                        }
                    }
                }
            }
        }

        if (hasMissingIncludedRates) {
            tvNetAsset.text = "需要网络更新"
            tvTotalAsset.text = "需要网络更新"
            tvTotalDebt.text = "需要网络更新"
            updateRateStatus(needsRates, hasMissingIncludedRates, hasSyncedRates)
            return
        }

        var netAsset = 0.0
        var totalAsset = 0.0
        var creditCardDebt = 0.0
        var hasNaN = false

        displayAssets.forEach { asset ->
            if (asset.includeInNetAsset && !asset.isArchived) {
                val balance = displayAmount(asset)
                if (balance.isNaN()) {
                    hasNaN = true
                } else {
                    netAsset += balance
                    if (balance >= 0) totalAsset += balance
                }
            }
            if (asset.assetCategory == Asset.CATEGORY_CREDIT_CARD && asset.includeInNetAsset && !asset.isArchived) {
                val balance = displayAmount(asset)
                if (!balance.isNaN() && balance < 0) creditCardDebt += balance
            }
        }

        if (hasNaN) {
            tvNetAsset.text = "需要网络更新"
            tvTotalAsset.text = "需要网络更新"
            tvTotalDebt.text = "需要网络更新"
            updateRateStatus(needsRates, hasMissingRates = true, hasSyncedRates)
            return
        }

        val currency = displayCurrency()
        val netText = CurrencyUtils.formatAmount(netAsset, currency)
        val totalText = CurrencyUtils.formatAmount(totalAsset, currency)
        val debtDisplay = if (creditCardDebt == 0.0) "暂无"
        else CurrencyUtils.formatAmount(creditCardDebt, currency)

        tvNetAsset.crossfadeText(netText)
        tvTotalAsset.crossfadeText(totalText)
        tvTotalDebt.crossfadeText(debtDisplay)

        updateRateStatus(needsRates, hasMissingIncludedRates, hasSyncedRates)
    }

    private fun updateRateStatus(needsRates: Boolean, hasMissingRates: Boolean, hasSyncedRates: Boolean) {
        if (needsRates) {
            tvRateStatus.text = when {
                hasMissingRates -> "需要网络更新汇率"
                hasSyncedRates -> "汇率已同步"
                else -> "汇率同步中..."
            }
            tvRateStatus.visibility = View.VISIBLE
        } else {
            tvRateStatus.visibility = View.GONE
        }
    }

    // ──────────────────────────────────────────────
    // 动态生成各类别卡片
    // ──────────────────────────────────────────────
    private fun buildCategoryCards(assets: List<Asset>) {
        containerCategoryCards.removeAllViews()
        val displayAssets = filterAssetsForMode(assets)

        // Empty state
        if (displayAssets.isEmpty()) {
            if (assets.isEmpty()) {
                tvAssetEmptyTitle.text = "暂无资产"
                tvAssetEmptySubtitle.text = "点击右下角按钮添加你的第一个资产"
            } else {
                val currency = displayCurrency()
                tvAssetEmptyTitle.text = getString(R.string.asset_amount_mode_empty_title, currency)
                tvAssetEmptySubtitle.text = getString(R.string.asset_amount_mode_empty_hint, currency)
            }
            layoutEmptyState.visibility = View.VISIBLE
            containerCategoryCards.visibility = View.GONE
            return
        } else {
            layoutEmptyState.visibility = View.GONE
            containerCategoryCards.visibility = View.VISIBLE
        }

        val categoryViews = mutableListOf<View>()

        Asset.CATEGORY_ORDER.forEach { category ->
            val group = displayAssets.filter { it.assetCategory == category }
            if (group.isEmpty()) return@forEach

            val cardView = buildCategoryCard(category, group)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.space_16)
            }
            containerCategoryCards.addView(cardView, lp)
            categoryViews.add(cardView)
        }

        // Stagger first-load animation
        if (isFirstLoad && categoryViews.isNotEmpty()) {
            isFirstLoad = false
            categoryViews.forEachIndexed { index, view ->
                view.alpha = 0f
                view.translationY = 20f
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(UiMotion.NORMAL)
                    .setInterpolator(UiMotion.STANDARD_EASING)
                    .setStartDelay(index * 40L)
                    .withLayer()
                    .start()
            }
        }
    }

    /**
     * 构建单个类别卡片
     */
    private fun buildCategoryCard(
        category: String,
        group: List<Asset>
    ): CardView {
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        // ── 汇率检测 ──
        val hasMissingRate = hasMissingRatesForDisplay(group)
        val currency = displayCurrency()

        val total = if (hasMissingRate) 0.0 else group
            .filter { it.includeInNetAsset && !it.isArchived }
            .map { displayAmount(it) }
            .filter { !it.isNaN() }
            .sum()
        val excludedTotal = if (hasMissingRate) 0.0 else group
            .filterNot { it.includeInNetAsset && !it.isArchived }
            .map { displayAmount(it) }
            .filter { !it.isNaN() }
            .sum()

        val card = CardView(ctx).apply {
            radius = resources.getDimension(R.dimen.asset_category_card_radius)
            cardElevation = 0f
            setCardBackgroundColor(ctx.getColor(R.color.surface_card))
        }

        val cardContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── 标题行（with ripple press feedback） ──
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(
                resources.getDimensionPixelSize(R.dimen.card_padding),
                0,
                resources.getDimensionPixelSize(R.dimen.card_padding),
                0
            )
            minimumHeight = resources.getDimensionPixelSize(R.dimen.asset_category_header_height)
            background = ctx.getDrawable(R.drawable.bg_asset_category_header)
            isClickable = true
            isFocusable = true
        }

        val tvTitle = TextView(ctx).apply {
            text = Asset.categoryLabel(category)
            setTextColor(ctx.getColor(R.color.text_primary))
            textSize = resources.getDimension(R.dimen.asset_category_title_size) / density
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTotal = TextView(ctx).apply {
            text = when {
                hasMissingRate -> "需要网络更新"
                else -> CurrencyUtils.formatAmount(total, currency)
            }
            setTextColor(if (hasMissingRate) android.graphics.Color.parseColor("#E65100") else ctx.getColor(R.color.asset_category_header_total))
            textSize = resources.getDimension(R.dimen.asset_category_total_size) / density
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val ivExpand = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ctx.getColor(R.color.text_tertiary)
            )
            rotation = 90f
            val iconSize = resources.getDimensionPixelSize(R.dimen.icon_size_16)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_4)
            }
        }

        headerRow.addView(tvTitle)
        headerRow.addView(tvTotal)
        headerRow.addView(ivExpand)

        // ── 分割线 ──
        val divider = View(ctx).apply {
            setBackgroundColor(ctx.getColor(R.color.asset_divider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }

        // ── 资产列表（支持长按拖拽） ──
        val activeAssets = group.filterNot { it.isArchived }
        val archivedAssets = group.filter { it.isArchived }
        val assetList = activeAssets.toMutableList()
        val adapter = AssetRowAdapter(assetList) { asset ->
            val intent = Intent(requireContext(), AssetDetailActivity::class.java)
            intent.putExtra("ASSET_ID", asset.id)
            startActivity(intent)
        }

        val assetsRecycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
        }

        var hasMoved = false
        val touchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun isLongPressDragEnabled(): Boolean = true

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = viewHolder.adapterPosition
                    val to = target.adapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                        return false
                    }

                    val moved = assetList.removeAt(from)
                    assetList.add(to, moved)
                    adapter.notifyItemMoved(from, to)
                    hasMoved = true
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.apply {
                            alpha = 0.85f
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    } else {
                        stopDragAutoScroll()
                    }
                }

                override fun onChildDraw(
                    c: android.graphics.Canvas,
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    dX: Float,
                    dY: Float,
                    actionState: Int,
                    isCurrentlyActive: Boolean
                ) {
                    val itemTop = viewHolder.itemView.top
                    val itemBottom = viewHolder.itemView.bottom
                    val minDy = (recyclerView.paddingTop - itemTop).toFloat()
                    val maxDy = (recyclerView.height - recyclerView.paddingBottom - itemBottom).toFloat()
                    val clampedDy = dY.coerceIn(minDy, maxDy)

                    super.onChildDraw(c, recyclerView, viewHolder, dX, clampedDy, actionState, isCurrentlyActive)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                        updateDragAutoScroll(recyclerView, viewHolder, clampedDy)
                    } else {
                        stopDragAutoScroll()
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    stopDragAutoScroll()
                    viewHolder.itemView.apply {
                        alpha = 1f
                        scaleX = 1f
                        scaleY = 1f
                    }
                    if (hasMoved) {
                        hasMoved = false
                        persistCategoryOrder(category, assetList)
                    }
                }
            }
        )
        touchHelper.attachToRecyclerView(assetsRecycler)

        cardContent.addView(headerRow)
        cardContent.addView(divider)
        cardContent.addView(assetsRecycler)
        val archivedToggleRow = TextView(ctx).apply {
            text = "▸ 收纳资产  ${archivedAssets.size}"
            setTextColor(ctx.getColor(R.color.text_tertiary))
            textSize = resources.getDimension(R.dimen.text_size_13) / density
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(
                resources.getDimensionPixelSize(R.dimen.card_padding),
                resources.getDimensionPixelSize(R.dimen.space_8),
                resources.getDimensionPixelSize(R.dimen.card_padding),
                resources.getDimensionPixelSize(R.dimen.space_8)
            )
            visibility = if (archivedAssets.isEmpty()) View.GONE else View.VISIBLE
            isClickable = true
            isFocusable = true
            background = ctx.getDrawable(R.drawable.bg_asset_category_header)
        }
        val archivedRecycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = AssetRowAdapter(archivedAssets) { asset ->
                val intent = Intent(requireContext(), AssetDetailActivity::class.java)
                intent.putExtra("ASSET_ID", asset.id)
                startActivity(intent)
            }
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            visibility = View.GONE
        }
        if (archivedAssets.isNotEmpty()) {
            cardContent.addView(archivedToggleRow)
            cardContent.addView(archivedRecycler)
        }
        val tvExcludedSummary = TextView(ctx).apply {
            text = when {
                hasMissingRate -> "不计入总资产：汇率未加载"
                else -> "不计入总资产：${CurrencyUtils.formatAmount(excludedTotal, currency)}"
            }
            setTextColor(ctx.getColor(R.color.asset_excluded_text))
            textSize = resources.getDimension(R.dimen.text_size_12) / density
            setPadding(
                resources.getDimensionPixelSize(R.dimen.card_padding),
                resources.getDimensionPixelSize(R.dimen.space_8),
                resources.getDimensionPixelSize(R.dimen.card_padding),
                resources.getDimensionPixelSize(R.dimen.space_12)
            )
        }
        cardContent.addView(tvExcludedSummary)

        fun applyCollapsedState(collapsed: Boolean, withAnimation: Boolean) {
            val targetVisibility = if (collapsed) View.GONE else View.VISIBLE
            val duration = if (withAnimation) UiMotion.FAST else 0L
            ivExpand.animate()
                .rotation(if (collapsed) 0f else 90f)
                .setDuration(duration)
                .setInterpolator(if (withAnimation) UiMotion.STANDARD_EASING else null)
                .start()
            if (withAnimation && !collapsed) {
                divider.alpha = 0f
                divider.visibility = View.VISIBLE
                divider.animate().alpha(1f).setDuration(duration).start()
                assetsRecycler.alpha = 0f
                assetsRecycler.translationY = 12f
                assetsRecycler.visibility = View.VISIBLE
                assetsRecycler.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(UiMotion.NORMAL)
                    .setInterpolator(UiMotion.STANDARD_EASING)
                    .start()
                tvExcludedSummary.alpha = 0f
                tvExcludedSummary.visibility = View.VISIBLE
                tvExcludedSummary.animate().alpha(1f).setDuration(duration).start()
                archivedToggleRow.visibility = if (archivedAssets.isEmpty()) View.GONE else View.VISIBLE
                archivedRecycler.visibility =
                    if (expandedArchivedAssetCategories.contains(category) && archivedAssets.isNotEmpty()) View.VISIBLE else View.GONE
            } else if (withAnimation && collapsed) {
                divider.animate().alpha(0f).setDuration(duration).withEndAction {
                    divider.visibility = View.GONE
                }.start()
                assetsRecycler.animate()
                    .alpha(0f)
                    .translationY(8f)
                    .setDuration(duration)
                    .setInterpolator(UiMotion.EXIT_EASING)
                    .withEndAction {
                        assetsRecycler.visibility = View.GONE
                        assetsRecycler.translationY = 0f
                    }.start()
                tvExcludedSummary.animate().alpha(0f).setDuration(duration).withEndAction {
                    tvExcludedSummary.visibility = View.GONE
                }.start()
                archivedToggleRow.visibility = View.GONE
                archivedRecycler.visibility = View.GONE
            } else {
                divider.visibility = targetVisibility
                assetsRecycler.visibility = targetVisibility
                tvExcludedSummary.visibility = targetVisibility
                archivedToggleRow.visibility =
                    if (!collapsed && archivedAssets.isNotEmpty()) View.VISIBLE else View.GONE
                archivedRecycler.visibility =
                    if (!collapsed && expandedArchivedAssetCategories.contains(category) && archivedAssets.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        val initiallyCollapsed = collapsedCategories.contains(category)
        applyCollapsedState(initiallyCollapsed, withAnimation = false)
        headerRow.setOnClickListener {
            val collapsed = !collapsedCategories.contains(category)
            if (collapsed) collapsedCategories.add(category) else collapsedCategories.remove(category)
            applyCollapsedState(collapsed, withAnimation = true)
        }
        archivedToggleRow.setOnClickListener {
            val expanded = !expandedArchivedAssetCategories.contains(category)
            if (expanded) expandedArchivedAssetCategories.add(category) else expandedArchivedAssetCategories.remove(category)
            archivedToggleRow.text = "${if (expanded) "▾" else "▸"} 收纳资产  ${archivedAssets.size}"
            if (expanded) {
                archivedRecycler.alpha = 0f
                archivedRecycler.translationY = 8f
                archivedRecycler.visibility = View.VISIBLE
                archivedRecycler.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(UiMotion.NORMAL)
                    .setInterpolator(UiMotion.STANDARD_EASING)
                    .start()
            } else {
                archivedRecycler.animate()
                    .alpha(0f)
                    .translationY(8f)
                    .setDuration(UiMotion.FAST)
                    .setInterpolator(UiMotion.EXIT_EASING)
                    .withEndAction {
                        archivedRecycler.visibility = View.GONE
                        archivedRecycler.translationY = 0f
                    }
                    .start()
            }
        }
        archivedToggleRow.text =
            "${if (expandedArchivedAssetCategories.contains(category)) "▾" else "▸"} 收纳资产  ${archivedAssets.size}"

        card.addView(cardContent)
        return card
    }

    private fun updateDragAutoScroll(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dY: Float
    ) {
        if (!::nsvAssets.isInitialized) return
        val rvLoc = IntArray(2)
        val svLoc = IntArray(2)
        recyclerView.getLocationOnScreen(rvLoc)
        nsvAssets.getLocationOnScreen(svLoc)

        val dragCenterYOnScreen = rvLoc[1] + viewHolder.itemView.top + dY + viewHolder.itemView.height / 2f
        val visibleTop = svLoc[1].toFloat()
        val visibleBottom = svLoc[1] + nsvAssets.height.toFloat()
        val edgeThreshold = resources.displayMetrics.density * 84f
        val maxSpeed = (resources.displayMetrics.density * 24f).toInt().coerceAtLeast(8)

        val newDirection: Int
        val newSpeed: Int
        if (dragCenterYOnScreen > visibleBottom - edgeThreshold) {
            val ratio = ((dragCenterYOnScreen - (visibleBottom - edgeThreshold)) / edgeThreshold).coerceIn(0f, 1f)
            newDirection = 1
            newSpeed = (maxSpeed * ratio).toInt().coerceAtLeast(2)
        } else if (dragCenterYOnScreen < visibleTop + edgeThreshold) {
            val ratio = (((visibleTop + edgeThreshold) - dragCenterYOnScreen) / edgeThreshold).coerceIn(0f, 1f)
            newDirection = -1
            newSpeed = (maxSpeed * ratio).toInt().coerceAtLeast(2)
        } else {
            newDirection = 0
            newSpeed = 0
        }

        dragAutoScrollDirection = newDirection
        dragAutoScrollSpeedPx = newSpeed
        if (newDirection != 0) {
            startDragAutoScroll()
        } else {
            stopDragAutoScroll()
        }
    }

    private fun startDragAutoScroll() {
        if (dragAutoScrollActive) return
        dragAutoScrollActive = true
        nsvAssets.removeCallbacks(dragAutoScrollRunner)
        nsvAssets.postOnAnimation(dragAutoScrollRunner)
    }

    private fun stopDragAutoScroll() {
        dragAutoScrollActive = false
        dragAutoScrollDirection = 0
        dragAutoScrollSpeedPx = 0
        if (::nsvAssets.isInitialized) {
            nsvAssets.removeCallbacks(dragAutoScrollRunner)
        }
    }

    private fun persistCategoryOrder(changedCategory: String, reorderedList: List<Asset>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val allAssets = db.assetDao().getAllAssetsList()
            val otherAssets = allAssets.filter { it.assetCategory != changedCategory }

            val globalList = mutableListOf<Asset>()
            Asset.CATEGORY_ORDER.forEach { cat ->
                if (cat == changedCategory) {
                    globalList.addAll(reorderedList)
                } else {
                    globalList.addAll(otherAssets.filter { it.assetCategory == cat }
                        .sortedBy { it.sortOrder })
                }
            }

            val orders = globalList.mapIndexed { idx, asset ->
                asset.id to (idx + 1) * 10
            }.toMap()
            db.assetDao().reorderAssets(orders)
        }
    }

    private class AssetRowAdapter(
        private val assets: List<Asset>,
        private val onClick: (Asset) -> Unit
    ) : RecyclerView.Adapter<AssetRowAdapter.AssetVH>() {

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = assets[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_asset_row, parent, false)
            return AssetVH(view)
        }

        override fun onViewAttachedToWindow(holder: AssetVH) {
            super.onViewAttachedToWindow(holder)
            holder.itemView.applyItemPressFeedback()
        }

        override fun onBindViewHolder(holder: AssetVH, position: Int) {
            val asset = assets[position]
            holder.tvName.text = asset.name
            holder.tvBalance.text = CurrencyUtils.formatAmount(asset.balance, asset.currency)

            val remarkTexts = mutableListOf<String>()
            if (asset.isArchived) remarkTexts.add("已收纳")
            if (!asset.includeInNetAsset) remarkTexts.add("不计入")
            if (asset.remark.isNotBlank()) remarkTexts.add(asset.remark.trim())
            if (remarkTexts.isNotEmpty()) {
                holder.tvRemark.visibility = View.VISIBLE
                holder.tvRemark.text = remarkTexts.joinToString(" | ")
            } else {
                holder.tvRemark.visibility = View.GONE
            }

            Glide.with(holder.itemView)
                .load(AssetIconDefaults.withDefault(asset.icon))
                .transform(CircleCrop())
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .into(holder.ivIcon)

            holder.itemView.setOnClickListener { onClick(asset) }
        }

        override fun getItemCount(): Int = assets.size

        class AssetVH(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.iv_asset_icon)
            val tvName: TextView = v.findViewById(R.id.tv_asset_name)
            val tvRemark: TextView = v.findViewById(R.id.tv_asset_remark)
            val tvBalance: TextView = v.findViewById(R.id.tv_asset_balance)
        }
    }
}

