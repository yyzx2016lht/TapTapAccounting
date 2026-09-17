package com.taostudio.tapaccounting.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.taostudio.tapaccounting.AppListActivity
import com.taostudio.tapaccounting.KeepAliveAccessibilityService
import com.taostudio.tapaccounting.KeepAliveDiagnostics
import com.taostudio.tapaccounting.GesturePermissionGuideActivity
import com.taostudio.tapaccounting.OverlayService
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.RecentTasksHelper
import com.taostudio.tapaccounting.ShizukuSafe
import com.taostudio.tapaccounting.tap.TapActionRegistry
import com.google.android.material.snackbar.Snackbar
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.tap.TapModel
import java.util.Locale

class SensitivityActivity : AppCompatActivity() {
    private companion object {
        private const val TAP_RESTART_DEBOUNCE_MS = 1_000L
        private const val REQUEST_POST_NOTIFICATIONS = 7101
        private const val SCREEN_CAPTURE_ACTION_ID = "screen_capture"
        private const val PERMISSION_PROMPT_DEFER_DAYS = 7L
    }

    private lateinit var cardFlipSensitivity: View
    private lateinit var cardTapSensitivity: View
    private lateinit var cardTapOptions: View
    private lateinit var cardKeepAlive: View
    private lateinit var rowLandscapeDisable: View
    private lateinit var rowHideRecents: View
    private lateinit var rowHideRecentsBottom: View
    private lateinit var rowVibrationFeedback: View
    private lateinit var rowSaveVibrate: View
    private lateinit var btnTapAdvancedToggle: View
    private lateinit var btnTapModel: View
    private lateinit var layoutTapNnapi: View
    private lateinit var layoutTapLowPower: View
    private lateinit var groupLandscapeDisable: View
    private lateinit var groupHideRecents: View
    private lateinit var groupVibrationFeedback: View
    private lateinit var groupSaveVibrate: View
    private lateinit var groupTapAdvancedToggle: View
    private lateinit var groupTapModel: View
    private lateinit var groupTapNnapi: View
    private lateinit var groupTapLowPower: View
    private lateinit var groupNotificationPermission: View
    private lateinit var tvTapAdvancedSummary: TextView
    private lateinit var ivTapAdvancedChevron: ImageView

    private lateinit var seekBarFlip: SeekBar
    private lateinit var tvFlipCurrentValue: TextView
    private lateinit var tvFlipActionName: TextView
    private lateinit var seekBarTap: SeekBar
    private lateinit var tvTapCurrentValue: TextView
    private lateinit var groupTapHeOptions: View
    private lateinit var switchTapHeTest: SwitchMaterial
    private lateinit var layoutTapHeTest: View
    private lateinit var tvTapModelName: TextView
    private lateinit var tvTapActionDoubleName: TextView
    private lateinit var tvTapActionTripleName: TextView
    private lateinit var switchTapNnapi: SwitchMaterial
    private lateinit var switchTapLowPower: SwitchMaterial
    private lateinit var switchTapTriple: SwitchMaterial
    private lateinit var groupTapActionTriple: View
    private var isUpdatingUi = false

    private lateinit var switchFlipEnable: SwitchMaterial
    private lateinit var switchTapEnable: SwitchMaterial
    private lateinit var switchLandscapeDisable: SwitchMaterial
    private lateinit var switchHideRecents: SwitchMaterial
    private lateinit var switchVibrationFeedback: SwitchMaterial
    private lateinit var switchSaveVibrate: SwitchMaterial
    private lateinit var switchNotificationPermission: SwitchMaterial
    private lateinit var switchHideRecentsBottom: SwitchMaterial
    private lateinit var groupWhitelist: View
    private lateinit var switchWhitelistMode: SwitchMaterial
    private lateinit var btnManageWhitelist: View
    private var ignoreWhitelistToggle = false
    private var tapAdvancedExpanded = false
    private var isUpdatingHideRecentsSwitch = false
    private val tapRestartHandler = Handler(Looper.getMainLooper())
    private var tapRestartPending = false
    private var isUpdatingNotificationSwitch = false
    private var permissionGuidePromptShowing = false
    private var permissionGuidePromptShownOnce = false
    private val tapRestartRunnable = Runnable {
        tapRestartPending = false
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_RESTART_DOUBLE_TAP
        }
        startServiceCompat(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensitivity)

        initViews()
        loadData()
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.toolbar).setOnClickListener { finish() }

        cardFlipSensitivity = findViewById(R.id.card_flip_sensitivity)
        cardTapSensitivity = findViewById(R.id.card_tap_sensitivity)
        cardTapOptions = findViewById(R.id.card_tap_options)
        cardKeepAlive = findViewById(R.id.card_keep_alive)
        rowLandscapeDisable = findViewById(R.id.row_landscape_disable)
        rowHideRecents = findViewById(R.id.row_hide_recents)
        rowHideRecentsBottom = findViewById(R.id.row_hide_recents_bottom)
        rowVibrationFeedback = findViewById(R.id.row_vibration_feedback)
        rowSaveVibrate = findViewById(R.id.row_save_vibrate)
        btnTapAdvancedToggle = findViewById(R.id.btn_tap_advanced_toggle)
        btnTapModel = findViewById(R.id.btn_tap_model)
        layoutTapNnapi = findViewById(R.id.layout_tap_nnapi)
        layoutTapLowPower = findViewById(R.id.layout_tap_low_power)
        groupTapHeOptions = findViewById(R.id.group_tap_he_options)
        groupLandscapeDisable = findViewById(R.id.group_landscape_disable)
        groupHideRecents = findViewById(R.id.group_hide_recents)
        groupVibrationFeedback = findViewById(R.id.group_vibration_feedback)
        groupSaveVibrate = findViewById(R.id.group_save_vibrate)
        groupTapAdvancedToggle = findViewById(R.id.group_tap_advanced_toggle)
        groupTapModel = findViewById(R.id.group_tap_model)
        groupTapNnapi = findViewById(R.id.group_tap_nnapi)
        groupTapLowPower = findViewById(R.id.group_tap_low_power)
        groupNotificationPermission = findViewById(R.id.group_notification_permission)
        tvTapAdvancedSummary = findViewById(R.id.tv_tap_advanced_summary)
        ivTapAdvancedChevron = findViewById(R.id.iv_tap_advanced_chevron)
        groupWhitelist = findViewById(R.id.group_whitelist)
        switchWhitelistMode = findViewById(R.id.switch_whitelist_mode)
        btnManageWhitelist = findViewById(R.id.btn_manage_whitelist)

        initGestureSwitches()
        initFlipViews()
        initTapViews()
        initTapOptions()
        initWhitelistSettings()
        initKeepAliveSettings()

        // 依赖 initTapOptions() 里绑定的 groupTapHeSensitivity / switchTapTriple 等视图，
        // 所以必须排在它后面。
        updateLowPowerDependencies()

        // 让包含开关的整行都可点击
        makeSwitchRowsClickable()

        switchFlipEnable.post { showInitialOnboardingIfNeeded() }
    }

    private fun initGestureSwitches() {
        switchFlipEnable = findViewById(R.id.switch_flip_enable)
        switchTapEnable = findViewById(R.id.switch_tap_enable)
        switchLandscapeDisable = findViewById(R.id.switch_landscape_disable)
        switchHideRecents = findViewById(R.id.switch_hide_recents)
        switchHideRecentsBottom = findViewById(R.id.switch_hide_recents_bottom)
        switchVibrationFeedback = findViewById(R.id.switch_vibration_feedback)
        switchSaveVibrate = findViewById(R.id.switch_save_vibrate)

        switchFlipEnable.isChecked = Prefs.isFlipEnabled(this)
        updateGestureDependentSections()
        switchFlipEnable.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setFlipEnabled(this, isChecked)
            updateGestureDependentSections()
            val intent = Intent(this, OverlayService::class.java).apply {
                action = if (isChecked) OverlayService.ACTION_START_FLIP else OverlayService.ACTION_STOP_FLIP
            }
            startServiceCompat(intent)
            if (isChecked) {
                handleGestureEnabled(getString(R.string.flip_phone))
                if (!Prefs.hasSeenFlipGuide(this@SensitivityActivity)) {
                    Prefs.setFlipGuideSeen(this@SensitivityActivity)
                    cardFlipSensitivity.post { showFlipSecondaryGuide() }
                }
            }
        }

        switchTapEnable.isChecked = Prefs.isDoubleTapEnabled(this)
        updateGestureDependentSections()
        switchTapEnable.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setDoubleTapEnabled(this, isChecked)
            updateGestureDependentSections()
            val intent = Intent(this, OverlayService::class.java).apply {
                action = if (isChecked) OverlayService.ACTION_START_DOUBLE_TAP else OverlayService.ACTION_STOP_DOUBLE_TAP
            }
            startServiceCompat(intent)
            if (isChecked) {
                if (Prefs.getTapModel(this).isEmpty()) {
                    val recommended = TapModel.recommend(this)
                    Prefs.setTapModel(this, recommended.path)
                }
                if (Prefs.getTapActionDouble(this).isEmpty()) {
                    Prefs.setTapActionDouble(this, "show_overlay")
                    if (::tvTapActionDoubleName.isInitialized) {
                        tvTapActionDoubleName.text = getVisibleTapActionName("show_overlay")
                    }
                }
                handleGestureEnabled(getString(R.string.tap_back))
                if (!Prefs.hasSeenDoubleTapGuide(this@SensitivityActivity)) {
                    Prefs.setDoubleTapGuideSeen(this@SensitivityActivity)
                    cardTapSensitivity.post { showTapSecondaryGuide() }
                }
            }
        }

        switchLandscapeDisable.isChecked = Prefs.isDisableLandscape(this)
        switchLandscapeDisable.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setDisableLandscape(this, isChecked)
            restartTapDetection()
        }

        // 隐藏后台卡片已废弃：实测证明卡片消失会导致进程被反复清理，
        // 因此开关一律隐藏并强制关闭，同时无条件保证卡片存在。
        switchHideRecents.isChecked = false
        switchHideRecentsBottom.isChecked = false
        switchHideRecents.setOnCheckedChangeListener(null)
        switchHideRecentsBottom.setOnCheckedChangeListener(null)
        RecentTasksHelper.ensureTaskVisible(this)

        switchVibrationFeedback.isChecked = Prefs.isVibrateFeedbackEnabled(this)
        switchVibrationFeedback.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setVibrateFeedbackEnabled(this, isChecked)
        }

        switchSaveVibrate.isChecked = Prefs.isSaveVibrateEnabled(this)
        switchSaveVibrate.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setSaveVibrateEnabled(this, isChecked)
        }
    }

    private fun updateGestureDependentSections() {
        val flipEnabled = Prefs.isFlipEnabled(this)
        val tapEnabled = Prefs.isDoubleTapEnabled(this)
        val anyGestureEnabled = flipEnabled || tapEnabled

        cardFlipSensitivity.visibility = if (flipEnabled) View.VISIBLE else View.GONE
        cardTapSensitivity.visibility = if (tapEnabled) View.VISIBLE else View.GONE
        cardTapOptions.visibility = if (tapEnabled) View.VISIBLE else View.GONE
        cardKeepAlive.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE

        groupLandscapeDisable.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE
        // 隐藏后台卡片入口整体下线（group 与其内的 divider 一起隐藏）
        groupHideRecents.visibility = View.GONE
        rowHideRecentsBottom.visibility = View.GONE
        groupNotificationPermission.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE
        groupVibrationFeedback.visibility = if (tapEnabled) View.VISIBLE else View.GONE
        groupSaveVibrate.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE
        updateWhitelistVisibility()
    }

    private fun updateWhitelistVisibility() {
        val anyGestureEnabled = Prefs.isFlipEnabled(this) || Prefs.isDoubleTapEnabled(this)
        val shizukuReady = Prefs.isShizukuModeEnabled(this)
        groupWhitelist.visibility = if (anyGestureEnabled && shizukuReady) View.VISIBLE else View.GONE
    }

    private fun makeSwitchRowsClickable() {
        val toggleIds = intArrayOf(
            R.id.switch_flip_enable,
            R.id.switch_tap_enable,
            R.id.switch_landscape_disable,
            R.id.switch_vibration_feedback,
            R.id.switch_save_vibrate,
            R.id.switch_notification_permission,
            R.id.switch_whitelist_mode
        )
        for (tid in toggleIds) {
            val sw = findViewById<CompoundButton>(tid) ?: continue
            val parent = sw.parent as? View
            if (parent != null) {
                parent.isClickable = true
                parent.isFocusable = true
                parent.setOnClickListener { sw.performClick() }
            }
        }
    }

    private fun startServiceCompat(intent: Intent) {
        OverlayService.startCompat(this, intent)
    }

    private fun initFlipViews() {
        seekBarFlip = findViewById(R.id.seekBarFlipSensitivity)
        tvFlipCurrentValue = findViewById(R.id.tvFlipCurrentValue)
        tvFlipActionName = findViewById(R.id.tv_flip_action_name)
        tvFlipActionName.text = getVisibleTapActionName(Prefs.getFlipAction(this))

        findViewById<View>(R.id.btn_flip_action).setOnClickListener {
            showTapActionDialog(
                title = getString(R.string.flip_action_title),
                currentActionId = Prefs.getFlipAction(this),
                targetView = tvFlipActionName,
                onSelected = { Prefs.setFlipAction(this, it) }
            )
        }

        seekBarFlip.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateFlipUI(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { level ->
                    Prefs.setFlipSensitivity(this@SensitivityActivity, level)
                }
            }
        })
    }

    private fun initTapViews() {
        seekBarTap = findViewById(R.id.seekBarTapSensitivity)
        tvTapCurrentValue = findViewById(R.id.tvTapCurrentValue)

        seekBarTap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTapUI(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { level ->
                    Prefs.setTapSensitivityLevel(this@SensitivityActivity, level)
                    restartTapDetection()
                }
            }
        })

        // 省电敲击测试模式：敲中只震动+提示，不执行动作。
        // 这样调灵敏度可以立刻连续验证，不用真的把记账动作触发出来。
        switchTapHeTest = findViewById(R.id.switch_tap_he_test)
        layoutTapHeTest = findViewById(R.id.layout_tap_he_test)
        layoutTapHeTest.setOnClickListener { switchTapHeTest.performClick() }
        switchTapHeTest.isChecked = Prefs.isTapHeTestModeEnabled(this)
        switchTapHeTest.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setTapHeTestModeEnabled(this@SensitivityActivity, isChecked)
            restartTapDetection()
        }
    }

    private fun initTapOptions() {
        tvTapModelName = findViewById(R.id.tv_tap_model_name)
        tvTapActionDoubleName = findViewById(R.id.tv_tap_action_double_name)
        tvTapActionTripleName = findViewById(R.id.tv_tap_action_triple_name)
        switchTapNnapi = findViewById(R.id.switch_tap_nnapi)
        switchTapLowPower = findViewById(R.id.switch_tap_low_power)
        switchTapTriple = findViewById(R.id.switch_tap_triple)
        groupTapActionTriple = findViewById(R.id.group_tap_action_triple)
        val layoutTapTriple = findViewById<View>(R.id.layout_tap_triple)

        layoutTapNnapi.setOnClickListener { if (switchTapNnapi.isEnabled) switchTapNnapi.performClick() }
        layoutTapTriple.setOnClickListener { if (switchTapTriple.isEnabled) switchTapTriple.performClick() }
        layoutTapLowPower.setOnClickListener { switchTapLowPower.performClick() }
        btnTapAdvancedToggle.setOnClickListener {
            tapAdvancedExpanded = !tapAdvancedExpanded
            updateTapAdvancedVisibility()
        }

        // 模型选择
        tvTapModelName.text = TapModel.resolve(this).displayName
        btnTapModel.setOnClickListener {
            val models = TapModel.values()
            val currentIdx = models.indexOf(TapModel.resolve(this)).coerceAtLeast(0)
            showSelectionDialog(
                title = getString(R.string.model_selection_title),
                items = models.map { SelectionItem(it.displayName, getString(R.string.model_inches_fmt, it.screenInches)) },
                selectedIndex = currentIdx
            ) { which ->
                Prefs.setTapModel(this, models[which].path)
                tvTapModelName.text = models[which].displayName
                restartTapDetection()
            }
        }

        // NNAPI 低功耗
        switchTapNnapi.isChecked = Prefs.isTapNnapiLowPower(this)
        switchTapNnapi.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setTapNnapiLowPower(this, isChecked)
            restartTapDetection()
        }

        // 敲击检测省电：开关 ON = 省电（静止约 3 分钟后切启发式待机），OFF = 全程 ML。
        // 注意这个 switch 的 id 是 switch_tap_low_power，绑定的是 tap_power_saving，
        // 不要再把它当成"全程 ML 开关"来写标签（历史上正因为文案相反误导过一次）。
        switchTapLowPower.isChecked = Prefs.isTapPowerSavingEnabled(this)
        switchTapLowPower.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setTapPowerSavingEnabled(this, isChecked)
            updateLowPowerDependencies()
            restartTapDetection()
        }

        // 三击模式
        switchTapTriple.isChecked = Prefs.isTapTripleEnabled(this)
        groupTapActionTriple.visibility = if (Prefs.isTapTripleEnabled(this)) View.VISIBLE else View.GONE
        switchTapTriple.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            Prefs.setTapTripleEnabled(this, isChecked)
            groupTapActionTriple.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked && Prefs.getTapActionTriple(this).isEmpty()) {
                Prefs.setTapActionTriple(this, "show_overlay")
                tvTapActionTripleName.text = getVisibleTapActionName("show_overlay")
            }
            restartTapDetection()
        }

        // 初始同步
        updateLowPowerDependencies()
        updateTapAdvancedVisibility()

        // 双击动作
        val doubleActionId = Prefs.getTapActionDouble(this)
        tvTapActionDoubleName.text = getVisibleTapActionName(doubleActionId)
        findViewById<View>(R.id.btn_tap_action_double).setOnClickListener {
            showTapActionDialog(
                title = getString(R.string.double_tap_action_title),
                currentActionId = Prefs.getTapActionDouble(this),
                targetView = tvTapActionDoubleName,
                onSelected = { Prefs.setTapActionDouble(this, it) }
            )
        }

        // 三击动作
        val tripleActionId = Prefs.getTapActionTriple(this)
        tvTapActionTripleName.text = getVisibleTapActionName(tripleActionId)
        findViewById<View>(R.id.btn_tap_action_triple).setOnClickListener {
            showTapActionDialog(
                title = getString(R.string.triple_tap_action_title),
                currentActionId = Prefs.getTapActionTriple(this),
                targetView = tvTapActionTripleName,
                onSelected = { Prefs.setTapActionTriple(this, it) }
            )
        }
    }

    private data class SelectionItem(val title: String, val subtitle: String = "")

    private fun getVisibleTapActions() =
        TapActionRegistry.getAll().filter { action ->
            action.id != SCREEN_CAPTURE_ACTION_ID || isScreenAccountingAvailableForTapAction()
        }

    private fun getVisibleTapActionName(actionId: String): String {
        val action = TapActionRegistry.findById(actionId) ?: return getString(R.string.unset)
        if (action.id == SCREEN_CAPTURE_ACTION_ID && !isScreenAccountingAvailableForTapAction()) {
            return getString(R.string.unset)
        }
        return action.displayName
    }

    private fun isScreenAccountingAvailableForTapAction(): Boolean {
        val hasAccessibility = KeepAliveAccessibilityService.isServiceEnabled() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val hasShizuku = Prefs.isShizukuModeEnabled(this) && ShizukuSafe.isReady(this)
        return Prefs.isShowScreenAccounting(this) && (hasAccessibility || hasShizuku)
    }

    private fun showTapActionDialog(
        title: String,
        currentActionId: String,
        targetView: TextView,
        onSelected: (String) -> Unit
    ) {
        val actions = getVisibleTapActions()
        val ids = listOf("") + actions.map { it.id }
        val items = listOf(SelectionItem(getString(R.string.action_none), getString(R.string.action_none_desc))) +
            actions.map { SelectionItem(it.displayName, it.description) }
        val currentIdx = ids.indexOf(currentActionId).coerceAtLeast(0)
        showSelectionDialog(
            title = title,
            items = items,
            selectedIndex = currentIdx
        ) { which ->
            val selectedId = ids[which]
            onSelected(selectedId)
            targetView.text = items[which].title
        }
    }

    private fun showSelectionDialog(
        title: String,
        items: List<SelectionItem>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_option_picker, null, false)
        panel.findViewById<TextView>(R.id.tv_option_picker_title).text = title
        panel.findViewById<TextView>(R.id.tv_option_picker_desc).visibility = View.GONE

        val listView = panel.findViewById<ListView>(R.id.lv_option_picker)
        val adapter = SelectionAdapter(items, selectedIndex)
        listView.adapter = adapter
        listView.divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#12000000"))
        listView.dividerHeight = 1

        val maxHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        val estimatedItemHeight = (64 * resources.displayMetrics.density).toInt()
        val estimatedContentHeight = (items.size * estimatedItemHeight).coerceAtLeast(dp(1))
        listView.layoutParams = listView.layoutParams.apply {
            height = maxHeight.coerceAtMost(estimatedContentHeight)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in items.indices) {
                dialog.dismiss()
                onSelected(position)
            }
        }
        panel.findViewById<TextView>(R.id.btn_option_picker_cancel).setOnClickListener { dialog.dismiss() }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.9f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private inner class SelectionAdapter(
        private val items: List<SelectionItem>,
        private val selectedIndex: Int
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): SelectionItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@SensitivityActivity)
                .inflate(R.layout.item_dialog_option_picker, parent, false)
            val titleView = view.findViewById<TextView>(R.id.tv_option_title)
            val subtitleView = view.findViewById<TextView>(R.id.tv_option_subtitle)
            val checkView = view.findViewById<TextView>(R.id.tv_option_check)
            val riskView = view.findViewById<TextView>(R.id.tv_option_risk)

            val item = getItem(position)
            titleView.text = item.title
            subtitleView.text = item.subtitle
            subtitleView.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
            checkView.visibility = if (position == selectedIndex) View.VISIBLE else View.GONE
            riskView.visibility = View.GONE
            return view
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadData() {
        val flipLevel = Prefs.getFlipSensitivity(this)
        seekBarFlip.progress = flipLevel
        updateFlipUI(flipLevel)

        if (Prefs.isDoubleTapEnabled(this)) {
            val tapLevel = Prefs.getTapSensitivityLevel(this)
            seekBarTap.progress = tapLevel
            updateTapUI(tapLevel)
        }
    }

    private fun initWhitelistSettings() {
        val layoutWhitelistManage = findViewById<View>(R.id.layout_whitelist_manage)

        switchWhitelistMode.isChecked = false
        switchWhitelistMode.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreWhitelistToggle) return@setOnCheckedChangeListener
            if (!Prefs.isShizukuModeEnabled(this)) {
                ignoreWhitelistToggle = true
                switchWhitelistMode.post {
                    switchWhitelistMode.isChecked = false
                    ignoreWhitelistToggle = false
                }
                layoutWhitelistManage.visibility = View.GONE
                Toast.makeText(this, getString(R.string.enable_shizuku_first), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (isChecked && !ShizukuSafe.isReady(this)) {
                ignoreWhitelistToggle = true
                switchWhitelistMode.post {
                    switchWhitelistMode.isChecked = false
                    ignoreWhitelistToggle = false
                }
                layoutWhitelistManage.visibility = View.GONE
                Toast.makeText(this, getString(R.string.whitelist_need_shizuku), Toast.LENGTH_SHORT).show()
                OverlayDialogs.showShizukuPrompt(this)
                return@setOnCheckedChangeListener
            }
            layoutWhitelistManage.visibility = if (isChecked) View.VISIBLE else View.GONE
            restartTapDetection()
            Toast.makeText(this, if (isChecked) getString(R.string.whitelist_enabled) else getString(R.string.whitelist_disabled), Toast.LENGTH_SHORT).show()
        }

        btnManageWhitelist.setOnClickListener {
            if (!ShizukuSafe.isBinderAlive()) {
                Toast.makeText(this, getString(R.string.shizuku_auth_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ShizukuSafe.hasPermission(this)) {
                ShizukuSafe.requestPermission(this, 101)
            } else {
                startActivity(Intent(this, AppListActivity::class.java))
            }
        }

        updateWhitelistVisibility()
    }

    private fun initKeepAliveSettings() {
        findViewById<View>(R.id.btn_gesture_permission_guide)?.setOnClickListener {
            startActivity(Intent(this, GesturePermissionGuideActivity::class.java))
        }

        // 电池优化豁免
        findViewById<View>(R.id.btn_battery_optimization)?.setOnClickListener {
            startActivity(Intent(this, GesturePermissionGuideActivity::class.java))
        }

        // 通知权限
        switchNotificationPermission = findViewById(R.id.switch_notification_permission)
        switchNotificationPermission.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingNotificationSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                requestNotificationPermissionOrOpenSettings()
            } else {
                Toast.makeText(this, getString(R.string.disable_notification), Toast.LENGTH_SHORT).show()
                openNotificationSettings()
            }
        }

        // 无障碍服务
        val btnAccessibility = findViewById<View>(R.id.btn_accessibility_service)
        val tvAccessibilityStatus = findViewById<TextView>(R.id.tv_accessibility_status)

        fun refreshAccessibilityStatus() {
            val isEnabled = KeepAliveAccessibilityService.isServiceEnabled()
            tvAccessibilityStatus?.text = if (isEnabled) getString(R.string.accessibility_status_on) else getString(R.string.accessibility_desc)
            (btnAccessibility as? MaterialButton)?.text = if (isEnabled) getString(R.string.accessibility_status_on) else getString(R.string.go_enable)
        }

        btnAccessibility?.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.accessibility_failed), Toast.LENGTH_SHORT).show()
            }
        }

        refreshAccessibilityStatus()
        refreshNotificationStatus()
    }

    override fun onResume() {
        super.onResume()
        KeepAliveDiagnostics.logSnapshot(this, "sensitivity-onResume")
        // 刷新无障碍服务和通知状态
        val tvAccessibilityStatus = findViewById<TextView>(R.id.tv_accessibility_status)
        val btnAccessibility = findViewById<View>(R.id.btn_accessibility_service)
        val isEnabled = KeepAliveAccessibilityService.isServiceEnabled()
        tvAccessibilityStatus?.text = if (isEnabled) getString(R.string.accessibility_status_on) else getString(R.string.accessibility_desc)
        (btnAccessibility as? MaterialButton)?.text = if (isEnabled) getString(R.string.accessibility_status_on) else getString(R.string.go_enable)

        refreshNotificationStatus()
        refreshWhitelistUi()
    }

    private fun refreshWhitelistUi() {
        if (!::groupWhitelist.isInitialized) return
        val shizukuEnabled = Prefs.isShizukuModeEnabled(this)
        val whitelistVisible = shizukuEnabled
        groupWhitelist.visibility = if (whitelistVisible) View.VISIBLE else View.GONE
        btnManageWhitelist.visibility =
            if (whitelistVisible && switchWhitelistMode.isChecked) View.VISIBLE else View.GONE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            refreshNotificationStatus()
        }
    }

    private fun refreshNotificationStatus() {
        val notificationReady = isNotificationPermissionReady()
        findViewById<TextView>(R.id.tv_notification_status)?.text =
            if (notificationReady) getString(R.string.notification_status_on) else getString(R.string.notification_status_off)
        if (::switchNotificationPermission.isInitialized) {
            isUpdatingNotificationSwitch = true
            switchNotificationPermission.isChecked = notificationReady
            isUpdatingNotificationSwitch = false
        }
    }

    private fun requestNotificationPermissionOrOpenSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
            return
        }

        if (!isNotificationPermissionReady()) {
            openNotificationSettings()
        } else {
            refreshNotificationStatus()
        }
    }

    private fun openNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.notification_settings_failed), Toast.LENGTH_SHORT).show()
            refreshNotificationStatus()
        }
    }

    private fun isNotificationPermissionReady(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val appNotificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.getNotificationChannel(OverlayService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
        return permissionGranted && appNotificationsEnabled && channelEnabled
    }

    private fun updateFlipUI(level: Int) {
        val label = when (level) {
            in 0..19 -> getString(R.string.level_very_stable)
            in 20..39 -> getString(R.string.level_stable)
            in 40..60 -> getString(R.string.level_medium)
            in 61..80 -> getString(R.string.level_sensitive)
            else -> getString(R.string.level_very_sensitive)
        }
        tvFlipCurrentValue.text = getString(R.string.current_flip_level_fmt, label, level)
    }

    private fun updateTapUI(level: Int) {
        tvTapCurrentValue.text = getString(R.string.current_tap_level_fmt, sensitivityLabel(level))
    }

    private fun sensitivityLabel(level: Int): String = when (level) {
        in 0..1 -> getString(R.string.level_very_low)
        in 2..3 -> getString(R.string.level_low)
        in 4..5 -> getString(R.string.level_medium)
        in 6..7 -> getString(R.string.level_high)
        in 8..10 -> getString(R.string.level_very_high)
        else -> getString(R.string.level_medium)
    }

    private fun updateLowPowerDependencies() {
        switchTapNnapi.isEnabled = true
        switchTapTriple.isEnabled = true
        groupTapActionTriple.visibility = if (Prefs.isTapTripleEnabled(this)) View.VISIBLE else View.GONE
        // 省电敲击测试模式只在省电敲击开启时才有意义
        groupTapHeOptions.visibility =
            if (Prefs.isTapPowerSavingEnabled(this)) View.VISIBLE else View.GONE
    }

    private fun updateTapAdvancedVisibility() {
        val visibility = if (tapAdvancedExpanded) View.VISIBLE else View.GONE
        groupTapModel.visibility = visibility
        groupTapNnapi.visibility = visibility
        groupTapLowPower.visibility = visibility
        tvTapAdvancedSummary.text = if (tapAdvancedExpanded) {
            getString(R.string.advanced_expanded)
        } else {
            getString(R.string.advanced_collapsed)
        }
        ivTapAdvancedChevron.rotation = if (tapAdvancedExpanded) 90f else 0f
    }

    private fun restartTapDetection() {
        tapRestartPending = true
        tapRestartHandler.removeCallbacks(tapRestartRunnable)
        tapRestartHandler.postDelayed(tapRestartRunnable, TAP_RESTART_DEBOUNCE_MS)
    }

    private var tipCard: View? = null

    private fun showInitialOnboardingIfNeeded() {
        if (Prefs.hasSeenSensitivityOnboarding(this)) return
        if (Prefs.isFlipEnabled(this) || Prefs.isDoubleTapEnabled(this)) {
            Prefs.setSensitivityOnboardingSeen(this)
            return
        }
        Prefs.setSensitivityOnboardingSeen(this)
        val gestureCard = findViewById<View>(R.id.card_gesture_switches) ?: return
        val parent = gestureCard.parent as? android.widget.LinearLayout ?: return

        val dp = resources.displayMetrics.density
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = 16 * dp
            cardElevation = 0f
            setCardBackgroundColor(android.graphics.Color.parseColor("#EEF2FF"))
            useCompatPadding = false
        }
        val inner = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        }
        inner.addView(android.widget.TextView(this).apply {
            text = getString(R.string.onboarding_gesture_tip_title)
            setTextColor(android.graphics.Color.parseColor("#3949AB"))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        inner.addView(android.widget.TextView(this).apply {
            text = getString(R.string.onboarding_gesture_tip_desc)
            setTextColor(android.graphics.Color.parseColor("#5C6BC0"))
            textSize = 13f
            setLineSpacing(3 * dp, 1f)
            setPadding(0, (6 * dp).toInt(), 0, (8 * dp).toInt())
        })
        inner.addView(android.widget.TextView(this).apply {
            text = getString(R.string.onboarding_gesture_tip_footer)
            setTextColor(android.graphics.Color.parseColor("#3949AB"))
            textSize = 12f
            setPadding(0, 0, 0, (10 * dp).toInt())
        })
        val dismissBtn = android.widget.TextView(this).apply {
            text = getString(R.string.onboarding_welcome_dismiss)
            setTextColor(android.graphics.Color.parseColor("#3949AB"))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#DDE3F9"))
                cornerRadius = 8 * dp
            }
        }
        dismissBtn.setOnClickListener {
            parent.removeView(card)
            tipCard = null
        }
        inner.addView(dismissBtn)
        card.addView(inner)

        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * dp).toInt() }

        val idx = parent.indexOfChild(gestureCard)
        parent.addView(card, idx, lp)
        tipCard = card
    }

    private fun showFlipSecondaryGuide() {
        val root = findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(root,
            getString(R.string.onboarding_flip_enabled_tip),
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showTapSecondaryGuide() {
        val root = findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(root,
            getString(R.string.onboarding_tap_enabled_tip),
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun handleGestureEnabled(name: String) {
        Toast.makeText(this, getString(R.string.onboarding_gesture_enabled_toast, name), Toast.LENGTH_SHORT).show()
        tipCard?.let { card ->
            (card.parent as? android.view.ViewGroup)?.removeView(card)
            tipCard = null
        }
        if (hasCoreGesturePermissionGap()) {
            promptGesturePermissionGuide()
            return
        }
        val root = findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(root, getString(R.string.onboarding_gesture_ready_tip), Snackbar.LENGTH_LONG).show()
    }

    private fun promptGesturePermissionGuide() {
        if (permissionGuidePromptShowing || permissionGuidePromptShownOnce) return
        if (Prefs.shouldDeferGesturePermissionPrompt(this)) return
        permissionGuidePromptShowing = true
        permissionGuidePromptShownOnce = true
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.onboarding_permission_prompt_title))
            .setMessage(getString(R.string.onboarding_permission_prompt_message))
            .setPositiveButton(getString(R.string.onboarding_permission_prompt_go)) { _, _ ->
                Prefs.setGesturePermissionPromptDeferUntilMs(this, 0L)
                startActivity(Intent(this, GesturePermissionGuideActivity::class.java))
            }
            .setNegativeButton(getString(R.string.onboarding_permission_prompt_later)) { _, _ ->
                val deferUntil = System.currentTimeMillis() + PERMISSION_PROMPT_DEFER_DAYS * 24L * 60L * 60L * 1000L
                Prefs.setGesturePermissionPromptDeferUntilMs(this, deferUntil)
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.onboarding_permission_prompt_deferred_tip),
                    Snackbar.LENGTH_LONG
                ).show()
            }
            .setOnDismissListener {
                permissionGuidePromptShowing = false
            }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun hasCoreGesturePermissionGap(): Boolean {
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val batteryReady = runCatching {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            powerManager.isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
        return !hasOverlay || !batteryReady
    }

    override fun onDestroy() {
        tapRestartHandler.removeCallbacks(tapRestartRunnable)
        if (tapRestartPending) {
            tapRestartRunnable.run()
        }
        super.onDestroy()
    }
}

