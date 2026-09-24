package com.taostudio.tapaccounting.ui.main.profile

import android.app.Activity
import android.os.Bundle
import android.net.Uri
import android.app.ActivityManager
import com.taostudio.tapaccounting.*
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import android.content.Context
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.ui.CurrencyManagerActivity
import com.google.android.material.button.MaterialButton
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.ui.SensitivityActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import com.taostudio.tapaccounting.ui.common.StatusBarStyle
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private var rootRef: View? = null
    // P1-29: 视图级协程作用域，onDestroyView 时取消
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var suppressHomeTrendCardSwitchCallback = false
    private data class ToggleTracker(var count: Int = 0, var firstToggleAtMs: Long = 0L)
    private val toggleTimers = mutableMapOf<String, ToggleTracker>()
    private var pendingEditUserAvatarView: ImageView? = null
    private val pickUserAvatarLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && isAdded) startUserAvatarCrop(uri)
    }
    private val userAvatarCropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isAdded) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.let { UCrop.getOutput(it) } ?: return@registerForActivityResult
            saveUserAvatar(uri)
        } else {
            val error = result.data?.let { UCrop.getError(it) }
            if (error != null) Utils.toast(requireContext(), getString(R.string.avatar_crop_failed))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootRef = view

        setupMainSettings(view)
        setupProfileInsets(view)
    }

    private fun setupProfileInsets(root: View) {
        val statusSpacer = root.findViewById<View>(R.id.view_profile_status_spacer)
        val scroll = findProfileScrollContainer(root)
        val baseScrollBottom = scroll?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            statusSpacer?.let { spacer ->
                spacer.layoutParams = spacer.layoutParams.apply {
                    height = statusTop.coerceAtLeast((24f * resources.displayMetrics.density).toInt())
                }
            }
            scroll?.updatePadding(bottom = baseScrollBottom + navBottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        applyProfileStatusBarStyle()
        rootRef?.let { refreshOverlayReminder(it, Prefs.isQuickGestureEnabled(requireContext())) }
        rootRef?.let { updateShowBookEntrySettingVisibility(it) }
        rootRef?.let { refreshUserAvatarCard(it) }
        rootRef?.let { refreshHomeTrendCardSwitch(it) }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            applyProfileStatusBarStyle()
            rootRef?.let { refreshHomeTrendCardSwitch(it) }
        }
    }

    private fun findProfileScrollContainer(root: View): NestedScrollView? {
        if (root is NestedScrollView) return root
        if (root !is ViewGroup) return null
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current is NestedScrollView) return current
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) {
                    queue.add(current.getChildAt(i))
                }
            }
        }
        return null
    }

    override fun onDestroyView() {
        // P1-29: 取消本视图协程
        viewScope.cancel()
        rootRef = null
        super.onDestroyView()
    }

    private fun applyProfileStatusBarStyle() {
        if (!isAdded) return
        StatusBarStyle.applyByColor(
            window = requireActivity().window,
            statusBarColor = Color.WHITE,
            decorFitsSystemWindows = false
        )
    }


    private fun setupMainSettings(view: View) {
        val btnRequestOverlay = view.findViewById<MaterialButton>(R.id.btnRequestOverlay)
        val btnShowOverlay = view.findViewById<MaterialButton>(R.id.btnShowOverlay)
        view.findViewById<View>(R.id.layout_user_profile_entry)?.setOnClickListener {
            showEditUserProfileDialog()
        }
        refreshUserAvatarCard(view)

        // --- 悬浮窗及权限 ---
        btnRequestOverlay?.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), GesturePermissionGuideActivity::class.java))
        }
        btnShowOverlay?.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(requireContext(), OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SHOW_OVERLAY
                }
                OverlayService.startCompat(requireContext(), intent)
                Utils.toast(requireContext(), getString(R.string.overlay_enabled))
            } else {
                Utils.toast(requireContext(), getString(R.string.overlay_permission_needed))
            }
        }

        // --- 基础菜单跳转 ---
        view.findViewById<View>(R.id.btn_manage_categories).setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.btn_bill_display_settings).setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), BillDisplaySettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.btn_manage_currencies).setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), CurrencyManagerActivity::class.java))
        }
        view.findViewById<View>(R.id.btn_sensitivity).setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), SensitivityActivity::class.java))
        }
        view.findViewById<View>(R.id.layout_widget_settings_entry).setOnClickListener {
            requireActivity().startActivity(
                Intent(requireContext(), com.taostudio.tapaccounting.widget.WidgetSettingsActivity::class.java)
            )
        }
        view.findViewById<View>(R.id.btn_backup_restore).setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), BackupActivity::class.java))
        }
        btnSyncRemoteConfig = view.findViewById(R.id.btn_sync_remote_config)
        groupSyncRemoteConfig = view.findViewById(R.id.group_sync_remote_config)
        refreshSyncRemoteConfigVisibility()
        btnSyncRemoteConfig?.setOnClickListener {
            viewScope.launch {
                val ok = RemoteConfigManager.syncIfConfigured(requireContext())
                withContext(Dispatchers.Main) {
                    Utils.toast(requireContext(), if (ok) getString(R.string.remote_config_synced) else getString(R.string.remote_config_failed))
                }
            }
        }
        groupShizukuMode = view.findViewById(R.id.group_shizuku_mode)
        groupShizukuPersistence = view.findViewById(R.id.group_shizuku_persistence)
        refreshShizukuVisibility()
        val layoutAiFeatureEntry = view.findViewById<View>(R.id.layout_ai_feature_entry)
        view.findViewById<View>(R.id.layout_ai_feature_entry)?.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), AiFeatureSettingsActivity::class.java))
        }
        val layoutStorageCleanupEntry = view.findViewById<View>(R.id.layout_storage_cleanup_entry)
        view.findViewById<View>(R.id.layout_storage_cleanup_entry)?.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), StorageCleanupActivity::class.java))
        }

        // --- 白名单 ---
        val switchShowMultiCur = view.findViewById<CompoundButton>(R.id.switch_show_multi_cur)
        val btnManageCurrencies = view.findViewById<View>(R.id.btn_manage_currencies)
        val btnSensitivity = view.findViewById<View>(R.id.btn_sensitivity)
        val groupBillDisplay = view.findViewById<View>(R.id.group_bill_display)
        val groupManageCurrencies = view.findViewById<View>(R.id.group_manage_currencies)
        val switchShowBookEntry = view.findViewById<CompoundButton>(R.id.switch_show_book_entry)
        val layoutShowBookEntryContainer = view.findViewById<View>(R.id.layout_show_book_entry_container)
        val switchShizukuMode = view.findViewById<CompoundButton>(R.id.switch_shizuku_mode)
        fun updateDataEntriesUi(multiCurrencyEnabled: Boolean) {
            groupManageCurrencies.visibility = if (multiCurrencyEnabled) View.VISIBLE else View.GONE
        }
        switchShowMultiCur.isChecked = Prefs.isShowMultiCurrency(requireContext())
        // 初始化多币种入口的可见状态
        updateDataEntriesUi(Prefs.isShowMultiCurrency(requireContext()))
        switchShowMultiCur.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setShowMultiCurrency(requireContext(), isChecked)
            updateDataEntriesUi(isChecked)
            refreshOverlayReminder(view, Prefs.isQuickGestureEnabled(requireContext()))
        }
        switchShowBookEntry.apply {
            isChecked = Prefs.isShowBookEntry(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShowBookEntry(requireContext(), isChecked)
            }
        }
        val showBookEntrySetting = shouldShowBookEntrySetting(requireContext())
        layoutShowBookEntryContainer.visibility = if (showBookEntrySetting) View.VISIBLE else View.GONE
        switchShizukuMode.apply {
            isChecked = Prefs.isShizukuModeEnabled(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShizukuModeEnabled(requireContext(), isChecked)
                if (!isChecked) {
                    view.findViewById<CompoundButton>(R.id.switch_shizuku_persistence)?.isChecked = false
                    ShizukuRecoveryService.stop(requireContext())
                } else if (!ShizukuSafe.isReady(requireContext())) {
                    Utils.toast(requireContext(), getString(R.string.shizuku_advanced_on))
                }
                updateShizukuPersistenceVisibility(view, isChecked)
                updateDoubleTapService(Prefs.isDoubleTapEnabled(requireContext()))
            }
        }
        // 初始化 Shizuku 持久化行的可见状态
        updateShizukuPersistenceVisibility(view, Prefs.isShizukuModeEnabled(requireContext()))
        view.findViewById<CompoundButton>(R.id.switch_asset_feature).apply {
            isChecked = Prefs.isAssetFeatureEnabled(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setAssetFeatureEnabled(requireContext(), isChecked)
                (activity as? MainActivity)?.refreshBottomNavigationTabs()
            }
        }

        view.findViewById<CompoundButton>(R.id.switch_show_home_trend_card).apply {
            isChecked = Prefs.isShowHomeTrendCard(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                if (suppressHomeTrendCardSwitchCallback) return@setOnCheckedChangeListener
                Prefs.setShowHomeTrendCard(requireContext(), isChecked)
            }
        }

        // --- 高级留存设置 ---

        val btnShareLogs = view.findViewById<View>(R.id.btn_share_logs)
        view.findViewById<CompoundButton>(R.id.switch_logging).apply {
            isChecked = Prefs.isLoggingEnabled(requireContext())
            btnShareLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setLoggingEnabled(requireContext(), isChecked)
                btnShareLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
                handleEasterEgg()
            }
        }

        btnShareLogs.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), LogViewerActivity::class.java))
        }

        view.findViewById<CompoundButton>(R.id.switch_shizuku_persistence).apply {
            isChecked = Prefs.isShizukuPersistenceEnabled(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && !switchShizukuMode.isChecked) {
                    post { this.isChecked = false }
                    Utils.toast(requireContext(), getString(R.string.shizuku_first_enable))
                    return@setOnCheckedChangeListener
                }
                if (isChecked && !ShizukuSafe.isReady(requireContext())) {
                    post { this.isChecked = false }
                    Utils.toast(requireContext(), getString(R.string.shizuku_auth_needed))
                    com.taostudio.tapaccounting.ui.dialog.OverlayDialogs.showShizukuPrompt(requireContext())
                    return@setOnCheckedChangeListener
                }
                Prefs.setShizukuPersistenceEnabled(requireContext(), isChecked)
                if (isChecked) {
                    val started = ShizukuRecoveryService.ensureStarted(requireContext())
                    Utils.toast(
                        requireContext(),
                        if (started) getString(R.string.shizuku_auto_restore_on) else getString(R.string.shizuku_auto_restore_failed)
                    )
                } else {
                    ShizukuRecoveryService.stop(requireContext())
                    Utils.toast(requireContext(), getString(R.string.shizuku_auto_restore_off))
                }
            }
        }

        // 让包含开关的整行都可点击：点击行会触发对应的开关（不会破坏已有的 setOnCheckedChangeListener）
        try {
            val toggleIds = intArrayOf(
                R.id.switch_show_book_entry,
                R.id.switch_shizuku_mode,
                R.id.switch_asset_feature,
                R.id.switch_screen_accounting,
                R.id.switch_show_home_trend_card,
                R.id.switch_show_multi_cur,
                R.id.switch_logging,
                R.id.switch_shizuku_persistence
            )

            for (tid in toggleIds) {
                val sw = view.findViewById<CompoundButton>(tid) ?: continue
                val parent = sw.parent as? View
                if (parent != null) {
                    parent.isClickable = true
                    parent.isFocusable = true
                    parent.setOnClickListener { sw.performClick() }
                }
            }
        } catch (e: Exception) {
            // 防御性：如果运行时出错，不影响主流程
            e.printStackTrace()
        }
    }

    private fun refreshHomeTrendCardSwitch(root: View) {
        val switch = root.findViewById<CompoundButton>(R.id.switch_show_home_trend_card) ?: return
        val targetChecked = Prefs.isShowHomeTrendCard(requireContext())
        suppressHomeTrendCardSwitchCallback = true
        try {
            if (switch.isChecked != targetChecked) {
                switch.isChecked = targetChecked
            }
        } finally {
            suppressHomeTrendCardSwitchCallback = false
        }
    }

    private fun updateShowBookEntrySettingVisibility(root: View) {
        val show = shouldShowBookEntrySetting(requireContext())
        root.findViewById<View>(R.id.layout_show_book_entry_container)?.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun shouldShowBookEntrySetting(context: Context): Boolean {
        val hasMultipleBooks = BookAccountManager.getBookAccounts(context).size > 1
        if (!hasMultipleBooks) return false
        val usingTraditionalEntry = Prefs.getAiEntryMode(context) == Prefs.AI_ENTRY_MODE_TRADITIONAL
        val quickOverlayEnabled = Prefs.isQuickGestureEnabled(context)
        return usingTraditionalEntry || quickOverlayEnabled
    }

    private fun refreshOverlayReminder(view: View, quickGestureEnabled: Boolean) {
        val card = view.findViewById<View>(R.id.card_overlay_reminder) ?: return
        val tvTitle = view.findViewById<TextView>(R.id.tv_overlay_reminder_title)
        val tvDesc = view.findViewById<TextView>(R.id.tv_overlay_reminder_desc)
        val btnRequestOverlay = view.findViewById<MaterialButton>(R.id.btnRequestOverlay)
        val btnShowOverlay = view.findViewById<MaterialButton>(R.id.btnShowOverlay)

        val hasOverlayPermission = Settings.canDrawOverlays(requireContext())
        val batteryReady = isIgnoringBatteryOptimizations()

        // 仅在「快捷手势已开启 且核心权限未完成」时显示提醒
        if (!quickGestureEnabled || (hasOverlayPermission && batteryReady)) {
            card.visibility = View.GONE
            return
        }

        card.visibility = View.VISIBLE
        tvTitle?.text = getString(R.string.shortcut_one_step)

        tvDesc?.text = when {
            !hasOverlayPermission -> getString(R.string.overlay_hint)
            !batteryReady -> getString(R.string.battery_hint)
            else -> getString(R.string.permission_hint)
        }
        btnRequestOverlay?.isEnabled = true
        btnRequestOverlay?.alpha = 1f
        btnRequestOverlay?.text = getString(R.string.view_prepare)
        btnShowOverlay?.visibility = View.GONE
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return runCatching {
            val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
        }.getOrDefault(false)
    }

    private fun refreshUserAvatarCard(root: View) {
        val ivAvatar = root.findViewById<ImageView>(R.id.iv_profile_user_avatar) ?: return
        val tvName = root.findViewById<TextView>(R.id.tv_profile_user_name)
        val tvDesc = root.findViewById<TextView>(R.id.tv_profile_user_avatar_desc)
        tvName?.text = Prefs.getUserChatName(requireContext())
        tvDesc?.text = Prefs.getUserProfileDesc(requireContext())
        val path = Prefs.getUserChatAvatarPath(requireContext())
        val file = if (path.isNotBlank()) File(path) else null
        if (file != null && file.exists()) {
            GlideLocalFiles.load(
                target = ivAvatar,
                file = file,
                placeholderRes = R.drawable.ic_user_avatar_default,
                circleCrop = true,
                overrideSize = 128
            )
        } else {
            ivAvatar.setImageResource(R.drawable.ic_user_avatar_default)
        }
    }

    private var btnSyncRemoteConfig: View? = null
    private var groupSyncRemoteConfig: View? = null
    private var groupShizukuMode: View? = null
    private var groupShizukuPersistence: View? = null

    private fun processProfilePassword(rawDesc: String): String {
        val ctx = requireContext()
        var clean = rawDesc
        var changed = false

        if (rawDesc.contains("5201314")) {
            clean = clean.replace("5201314", "")
            Prefs.setApiConfigUnlocked(ctx, true)
            changed = true
            refreshSyncRemoteConfigVisibility()
            showApiKeyDialog()
            Utils.toast(ctx, getString(R.string.api_unlocked))
        }
        if (rawDesc.contains("shizuku")) {
            clean = clean.replace("shizuku", "")
            Prefs.setShizukuUnlocked(ctx, true)
            changed = true
            refreshShizukuVisibility()
            Utils.toast(ctx, getString(R.string.shizuku_unlocked))
        }

        return if (changed && clean.isBlank()) "" else clean.trim()
    }

    private fun refreshSyncRemoteConfigVisibility() {
        val show = Prefs.isApiConfigUnlocked(requireContext()) && RemoteConfigManager.isConfigUrlConfigured()
        groupSyncRemoteConfig?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun refreshShizukuVisibility() {
        val show = Prefs.isShizukuUnlocked(requireContext())
        groupShizukuMode?.visibility = if (show) View.VISIBLE else View.GONE
        groupShizukuPersistence?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun handleEasterEgg() {
        val key = "logging"
        val now = SystemClock.elapsedRealtime()
        val tracker = toggleTimers.getOrPut(key) { ToggleTracker() }
        if (now - tracker.firstToggleAtMs > 60_000) {
            tracker.count = 0
            tracker.firstToggleAtMs = now
        }
        tracker.count++
        if (tracker.count >= 10) {
            tracker.count = 0
            val ctx = requireContext()
            val newState = !Prefs.isApiConfigUnlocked(ctx)
            Prefs.setApiConfigUnlocked(ctx, newState)
            Prefs.setAiDetailConfigUnlocked(ctx, newState)
            Prefs.setShizukuUnlocked(ctx, newState)
            refreshSyncRemoteConfigVisibility()
            refreshShizukuVisibility()
            Utils.toast(ctx, if (newState) getString(R.string.all_features_unlocked) else getString(R.string.all_features_locked))
        }
    }

    private fun showApiKeyDialog() {
        val ctx = requireContext()
        val builder = AlertDialog.Builder(ctx)
        builder.setTitle(getString(R.string.profile_config_api_key))
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val tvHint = TextView(ctx).apply {
            text = getString(R.string.profile_api_key_hint)
            textSize = 12f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(0, 0, 0, 16)
        }
        val etUrl = EditText(ctx).apply {
            hint = getString(R.string.profile_api_url_hint)
            setText(Prefs.getAiUrl(ctx).ifBlank { "https://api.siliconflow.cn" })
        }
        val etKey = EditText(ctx).apply {
            hint = getString(R.string.api_key)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.getAiKey(ctx))
        }
        layout.addView(tvHint)
        layout.addView(etUrl)
        layout.addView(etKey)
        builder.setView(layout)
        builder.setPositiveButton(getString(R.string.save_btn)) { _, _ ->
            Prefs.setAiUrl(ctx, etUrl.text.toString().trim())
            Prefs.setAiKey(ctx, etKey.text.toString().trim())
            Utils.toast(ctx, getString(R.string.profile_api_key_saved))
            refreshSyncRemoteConfigVisibility()
        }
        builder.setNeutralButton(getString(R.string.test_connection_btn)) { _, _ ->
            val url = etUrl.text.toString().trim()
            val key = etKey.text.toString().trim()
            if (key.isBlank()) {
                Utils.toast(ctx, getString(R.string.please_input_api_key))
                return@setNeutralButton
            }
            viewScope.launch {
                try {
                    val models = AIService.fetchModelsWithDetails(url, key)
                    withContext(Dispatchers.Main) {
                        if (models.isNotEmpty()) {
                            Utils.toast(ctx, getString(R.string.connection_success_models_fmt, models.size))
                        } else {
                            Utils.toast(ctx, getString(R.string.connection_success_no_models))
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Utils.toast(ctx, getString(R.string.connection_failed))
                    }
                }
            }
        }
        builder.setNegativeButton(getString(R.string.cancel_btn), null)
        val dialog = builder.create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.85f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun showEditUserProfileDialog() {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_edit_user_profile, null)
        val ivAvatar = view.findViewById<ImageView>(R.id.iv_user_profile_avatar)
        val etName = view.findViewById<EditText>(R.id.et_user_profile_name)
        val etDesc = view.findViewById<EditText>(R.id.et_user_profile_desc)
        pendingEditUserAvatarView = ivAvatar

        val currentName = Prefs.getUserChatName(ctx).ifBlank { getString(R.string.default_name) }
        etName.setText(currentName)
        etName.setSelection(currentName.length)
        val currentDesc = Prefs.getUserProfileDesc(ctx).ifBlank { getString(R.string.default_profile_desc) }
        etDesc.setText(currentDesc)
        etDesc.setSelection(currentDesc.length)

        val avatarPath = Prefs.getUserChatAvatarPath(ctx)
        val avatarFile = if (avatarPath.isNotBlank()) File(avatarPath) else null
        if (avatarFile != null && avatarFile.exists()) {
            GlideLocalFiles.load(
                target = ivAvatar,
                file = avatarFile,
                placeholderRes = R.drawable.ic_user_avatar_default,
                circleCrop = true,
                overrideSize = 128
            )
        } else {
            ivAvatar.setImageResource(R.drawable.ic_user_avatar_default)
        }

        ivAvatar.setOnClickListener { pickUserAvatarLauncher.launch("image/*") }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.edit_profile_title))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save_label)) { _, _ ->
                val rawDesc = etDesc.text?.toString().orEmpty()
                val cleanDesc = processProfilePassword(rawDesc)
                Prefs.setUserChatName(ctx, etName.text?.toString().orEmpty())
                Prefs.setUserProfileDesc(ctx, cleanDesc)
                rootRef?.let { refreshUserAvatarCard(it) }
                Utils.toast(ctx, getString(R.string.profile_updated))
            }
            .create()
        dialog.setOnDismissListener { pendingEditUserAvatarView = null }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.9f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun startUserAvatarCrop(sourceUri: Uri) {
        val ctx = requireContext()
        val destFile = File(ctx.cacheDir, "avatar_crop/profile_user_${System.currentTimeMillis()}.jpg")
            .also { it.parentFile?.mkdirs() }
        val destUri = Uri.fromFile(destFile)
        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(92)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(false)
            setShowCropGrid(true)
            setShowCropFrame(true)
            setToolbarTitle(getString(R.string.crop_avatar_title))
            setToolbarColor(android.graphics.Color.parseColor("#1A73E8"))
            setStatusBarColor(android.graphics.Color.parseColor("#1A73E8"))
            setToolbarWidgetColor(android.graphics.Color.WHITE)
        }
        val intent = UCrop.of(sourceUri, destUri)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(1080, 1080)
            .withOptions(options)
            .getIntent(ctx)
        userAvatarCropLauncher.launch(intent)
    }

    private fun saveUserAvatar(uri: Uri) {
        runCatching {
            val ctx = requireContext()
            val destFile = File(ctx.filesDir, "chat_user_avatar.jpg")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setUserChatAvatarPath(ctx, destFile.absolutePath)
            pendingEditUserAvatarView?.let {
                GlideLocalFiles.load(
                    target = it,
                    file = destFile,
                    placeholderRes = R.drawable.ic_user_avatar_default,
                    circleCrop = true,
                    overrideSize = 128
                )
            }
            rootRef?.let { refreshUserAvatarCard(it) }
            Utils.toast(ctx, getString(R.string.avatar_updated))
        }.onFailure {
            if (isAdded) Utils.toast(requireContext(), getString(R.string.avatar_update_failed))
        }
    }

    private fun updateDoubleTapService(isEnabled: Boolean) {
        val intent = Intent(requireContext(), OverlayService::class.java).apply {
            action = if (isEnabled) OverlayService.ACTION_START_DOUBLE_TAP else OverlayService.ACTION_STOP_DOUBLE_TAP
        }
        OverlayService.startCompat(requireContext(), intent)
    }

    private fun updateShizukuPersistenceVisibility(view: View, shizukuEnabled: Boolean) {
        val visibility = if (shizukuEnabled) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.group_shizuku_persistence)?.visibility = visibility
    }

}

