package com.taostudio.tapaccounting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

class AiFeatureSettingsActivity : AppCompatActivity() {

    private lateinit var btnManageAiRules: View
    private lateinit var layoutAiStatusCard: View
    private lateinit var tvAiStatusProvider: TextView
    private lateinit var tvAiStatusKey: TextView
    private lateinit var tvAiStatusBadge: TextView
    private lateinit var layoutAiStatusAction: View
    private lateinit var layoutAiStatusCaps: LinearLayout
    private lateinit var layoutAiSetupGuide: View
    private var pendingSetupDialog = false
    private var refreshProviderCapabilities: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_feature_settings)

        findViewById<View>(R.id.btn_back_ai_feature).setOnClickListener { finish() }
        setupAiFeatureSettings()
        pendingSetupDialog = !Prefs.isAiConfigured(this)
    }

    override fun onResume() {
        super.onResume()
        refreshAiStatusCard()
        refreshProviderCapabilities?.invoke()
        if (pendingSetupDialog && !Prefs.isAiConfigured(this)) {
            pendingSetupDialog = false
            showProviderSetupDialog(cancelable = true)
        }
    }

    private fun setupAiFeatureSettings() {
        val layoutAiMain = findViewById<View>(R.id.layout_ai_main_entry)
        val switchAiChatMode = findViewById<CompoundButton>(R.id.switch_ai_chat_mode)
        val layoutOpenAiChatPage = findViewById<View>(R.id.layout_open_ai_chat_page)
        layoutAiMain.visibility = View.VISIBLE

        (switchAiChatMode.parent as? View)?.visibility = View.VISIBLE
        layoutOpenAiChatPage.visibility = View.VISIBLE
        switchAiChatMode.isChecked =
            Prefs.getAiEntryMode(this) == Prefs.AI_ENTRY_MODE_CHAT
        switchAiChatMode.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAiEntryMode(
                this,
                if (isChecked) Prefs.AI_ENTRY_MODE_CHAT
                else Prefs.AI_ENTRY_MODE_TRADITIONAL
            )
        }
        layoutOpenAiChatPage.setOnClickListener {
            switchAiChatMode.performClick()
        }

        Prefs.setShowAiChatEntry(this, false)
        findViewById<CompoundButton>(R.id.switch_local_rule_override)?.apply {
            val unifiedLocalRuleEnabled =
                Prefs.isAiPromptCorrectionEnabled(this@AiFeatureSettingsActivity) ||
                Prefs.isLocalRuleOverrideEnabled(this@AiFeatureSettingsActivity)
            isChecked = unifiedLocalRuleEnabled
            Prefs.setAiPromptCorrectionEnabled(this@AiFeatureSettingsActivity, unifiedLocalRuleEnabled)
            Prefs.setLocalRuleOverrideEnabled(this@AiFeatureSettingsActivity, unifiedLocalRuleEnabled)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setAiPromptCorrectionEnabled(this@AiFeatureSettingsActivity, isChecked)
                Prefs.setLocalRuleOverrideEnabled(this@AiFeatureSettingsActivity, isChecked)
            }
        }

        // 消费洞察开关
        findViewById<CompoundButton>(R.id.switch_insight_cards)?.apply {
            isChecked = Prefs.isInsightCardsEnabled(this@AiFeatureSettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setInsightCardsEnabled(this@AiFeatureSettingsActivity, isChecked)
            }
        }

        findViewById<CompoundButton>(R.id.switch_show_voice).apply {
            isChecked = Prefs.isShowAiVoice(this@AiFeatureSettingsActivity)
            val layoutDetailVoice = this@AiFeatureSettingsActivity.findViewById<View>(R.id.layout_detail_voice)
            layoutDetailVoice.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && ContextCompat.checkSelfPermission(this@AiFeatureSettingsActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                }
                Prefs.setShowAiVoice(this@AiFeatureSettingsActivity, isChecked)
                layoutDetailVoice.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }

        val btnAsrOnline = findViewById<TextView>(R.id.btn_asr_online)
        val btnAsrOffline = findViewById<TextView>(R.id.btn_asr_offline)
        val tvAsrModelStatus = findViewById<TextView>(R.id.tv_asr_model_status)
        val btnAsrModelAction = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_asr_model_action)
        val layoutAsrModel = findViewById<View>(R.id.layout_asr_model)

        fun styleAsrSegment(view: TextView, selected: Boolean) {
            view.setBackgroundResource(
                if (selected) R.drawable.bg_stats_segmented_selected else R.drawable.bg_stats_segmented_unselected
            )
            view.setTextColor(Color.parseColor(if (selected) "#1A2744" else "#8F98A6"))
            view.paint.isFakeBoldText = selected
            view.isSelected = selected
        }

        fun updateAsrUi() {
            val mode = Prefs.getAsrMode(this@AiFeatureSettingsActivity)
            val isOnline = mode == Prefs.ASR_MODE_API
            styleAsrSegment(btnAsrOnline, isOnline)
            styleAsrSegment(btnAsrOffline, !isOnline)
            if (mode == Prefs.ASR_MODE_WHISPER) {
                layoutAsrModel.visibility = View.VISIBLE
                if (LocalAsrService.isModelReady(this@AiFeatureSettingsActivity)) {
                    tvAsrModelStatus.text = getString(R.string.ai_page_asr_model_ready)
                    tvAsrModelStatus.setTextColor(Color.parseColor("#5C6BC0"))
                    btnAsrModelAction.text = getString(R.string.delete_model)
                    btnAsrModelAction.setOnClickListener {
                        val dialog = AlertDialog.Builder(this@AiFeatureSettingsActivity)
                            .setTitle(R.string.delete_model_title)
                            .setMessage(R.string.delete_model_confirm)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                LocalAsrService.deleteModel(this@AiFeatureSettingsActivity)
                                val supportsCloudSpeech = AiProviderRegistry
                                    .resolvePreset(this@AiFeatureSettingsActivity)
                                    .supportsCloudSpeech
                                Prefs.setAsrMode(
                                    this@AiFeatureSettingsActivity,
                                    if (supportsCloudSpeech) Prefs.ASR_MODE_API else Prefs.ASR_MODE_WHISPER
                                )
                                updateAsrUi()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .create()
                        OverlayDialogs.showPageCenterDialog(
                            dialog = dialog,
                            ctx = this@AiFeatureSettingsActivity,
                            cancelOnTouchOutside = true,
                            useSolidPanelBackground = true
                        )
                    }
                } else {
                    tvAsrModelStatus.text = getString(R.string.ai_page_asr_model_missing)
                    tvAsrModelStatus.setTextColor(Color.parseColor("#9AA4B2"))
                    btnAsrModelAction.text = getString(R.string.download_model)
                    btnAsrModelAction.setOnClickListener {
                        val dialog = AlertDialog.Builder(this@AiFeatureSettingsActivity)
                            .setTitle(R.string.install_offline_model)
                            .setMessage(R.string.install_model_options)
                            .setPositiveButton(R.string.online_download) { _, _ ->
                                LocalAsrService.downloadModelWithUI(this@AiFeatureSettingsActivity) {
                                    runOnUiThread { updateAsrUi() }
                                }
                            }
                            .setNeutralButton(R.string.local_import) { _, _ ->
                                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                                startActivityForResult(intent, 2001)
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .create()
                        OverlayDialogs.showPageCenterDialog(
                            dialog = dialog,
                            ctx = this@AiFeatureSettingsActivity,
                            cancelOnTouchOutside = true,
                            useSolidPanelBackground = true
                        )
                    }
                }
            } else {
                layoutAsrModel.visibility = View.GONE
            }
        }

        btnAsrOnline.setOnClickListener {
            Prefs.setAsrMode(this@AiFeatureSettingsActivity, Prefs.ASR_MODE_API)
            updateAsrUi()
        }
        btnAsrOffline.setOnClickListener {
            Prefs.setAsrMode(this@AiFeatureSettingsActivity, Prefs.ASR_MODE_WHISPER)
            updateAsrUi()
        }
        updateAsrUi()

        val layoutImageAccountingSubSettings = findViewById<View>(R.id.layout_image_accounting_sub_settings)
        val switchShowAiImage = findViewById<CompoundButton>(R.id.switch_show_ai_image).apply {
            isChecked = Prefs.isShowAiImage(this@AiFeatureSettingsActivity)
            layoutImageAccountingSubSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShowAiImage(this@AiFeatureSettingsActivity, isChecked)
                layoutImageAccountingSubSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }
        val layoutReceiptImageDraftConfirm = findViewById<View>(R.id.layout_receipt_image_draft_confirm)
        val switchReceiptImageDraftConfirm = findViewById<CompoundButton>(R.id.switch_receipt_image_draft_confirm).apply {
            isChecked = Prefs.isReceiptImageDraftConfirmEnabled(this@AiFeatureSettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setReceiptImageDraftConfirmEnabled(this@AiFeatureSettingsActivity, isChecked)
            }
        }
        val layoutImageAccountingNaturalLanguage = findViewById<View>(R.id.layout_image_accounting_natural_language)
        val switchImageAccountingNaturalLanguage = findViewById<CompoundButton>(R.id.switch_image_accounting_natural_language).apply {
            isChecked = Prefs.isImageAccountingNaturalLanguage(this@AiFeatureSettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setImageAccountingNaturalLanguage(this@AiFeatureSettingsActivity, isChecked)
            }
        }

        // 截屏记账
        val switchScreenAccounting = findViewById<CompoundButton>(R.id.switch_screen_accounting)
        val tvScreenAccountingStatus = findViewById<TextView>(R.id.tv_screen_accounting_desc)
        val layoutScreenAccounting = findViewById<View>(R.id.layout_screen_accounting)
        val layoutScreenAccountingImageFlow = findViewById<View>(R.id.layout_screen_accounting_image_flow)
        val switchScreenAccountingImageFlow = findViewById<CompoundButton>(R.id.switch_screen_accounting_image_flow).apply {
            isChecked = Prefs.isScreenAccountingUseImageFlow(this@AiFeatureSettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setScreenAccountingUseImageFlow(this@AiFeatureSettingsActivity, isChecked)
            }
        }
        var ignoreScreenAccountingToggle = false

        fun updateScreenAccountingImageFlowVisibility() {
            layoutScreenAccountingImageFlow.visibility =
                if (switchScreenAccounting.isChecked) View.VISIBLE else View.GONE
        }

        fun updateScreenAccountingVisibility() {
            val hasAccessibility = KeepAliveAccessibilityService.isServiceEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val hasShizuku = Prefs.isShizukuModeEnabled(this) && ShizukuSafe.isReady(this)
            val available = hasAccessibility || hasShizuku
            val supportsVision = AiProviderRegistry.resolvePreset(this).supportsVision
            // 无权限时行常驻但置灰，副标题提示授权方式；点击开关时 toast 引导。
            layoutScreenAccounting.alpha = if (available && supportsVision) 1f else 0.5f
            if (available) {
                tvScreenAccountingStatus.text = getString(R.string.ai_page_screen_desc)
                tvScreenAccountingStatus.setTextColor(ContextCompat.getColor(this, R.color.settings_item_subtitle))
            } else {
                tvScreenAccountingStatus.text = getString(R.string.ai_tile_screen_need_permission)
                tvScreenAccountingStatus.setTextColor(Color.parseColor("#B26A00"))
            }
            updateScreenAccountingImageFlowVisibility()
        }

        switchScreenAccounting.apply {
            isChecked = Prefs.isShowScreenAccounting(this@AiFeatureSettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                if (ignoreScreenAccountingToggle) return@setOnCheckedChangeListener
                val hasAccessibility = KeepAliveAccessibilityService.isServiceEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                val hasShizuku = Prefs.isShizukuModeEnabled(this@AiFeatureSettingsActivity) && ShizukuSafe.isReady(this@AiFeatureSettingsActivity)
                if (!hasAccessibility && !hasShizuku) {
                    ignoreScreenAccountingToggle = true
                    post {
                        switchScreenAccounting.isChecked = false
                        ignoreScreenAccountingToggle = false
                    }
                    Utils.toast(this@AiFeatureSettingsActivity, getString(R.string.ai_screen_accounting_perm_hint))
                    return@setOnCheckedChangeListener
                }
                Prefs.setShowScreenAccounting(this@AiFeatureSettingsActivity, isChecked)
                updateScreenAccountingImageFlowVisibility()
                Utils.toast(this@AiFeatureSettingsActivity, if (isChecked) getString(R.string.screen_accounting_enabled) else getString(R.string.screen_accounting_disabled))
            }
        }

        updateScreenAccountingVisibility()

        refreshProviderCapabilities = {
            val preset = AiProviderRegistry.resolvePreset(this)
            if (!preset.supportsCloudSpeech && Prefs.getAsrMode(this) == Prefs.ASR_MODE_API) {
                Prefs.setAsrMode(this, Prefs.ASR_MODE_WHISPER)
            }
            btnAsrOnline.isEnabled = preset.supportsCloudSpeech
            btnAsrOnline.alpha = if (preset.supportsCloudSpeech) 1f else 0.45f

            switchShowAiImage.isEnabled = preset.supportsVision
            (switchShowAiImage.parent as? View)?.alpha = if (preset.supportsVision) 1f else 0.45f
            layoutImageAccountingSubSettings.isEnabled = preset.supportsVision
            layoutImageAccountingSubSettings.alpha = if (preset.supportsVision) 1f else 0.45f
            layoutReceiptImageDraftConfirm.isEnabled = preset.supportsVision
            layoutReceiptImageDraftConfirm.alpha = if (preset.supportsVision) 1f else 0.45f
            switchReceiptImageDraftConfirm.isEnabled = preset.supportsVision
            layoutImageAccountingNaturalLanguage.isEnabled = preset.supportsVision
            layoutImageAccountingNaturalLanguage.alpha = if (preset.supportsVision) 1f else 0.45f
            switchImageAccountingNaturalLanguage.isEnabled = preset.supportsVision
            switchScreenAccounting.isEnabled = preset.supportsVision
            switchScreenAccountingImageFlow.isEnabled = preset.supportsVision
            layoutScreenAccountingImageFlow.alpha = if (preset.supportsVision) 1f else 0.45f
            if (!preset.supportsVision) {
                switchShowAiImage.isChecked = false
                switchScreenAccounting.isChecked = false
            }
            updateScreenAccountingImageFlowVisibility()
            updateAsrUi()
            updateScreenAccountingVisibility()
        }
        refreshProviderCapabilities?.invoke()

        btnManageAiRules = findViewById(R.id.btn_manage_ai_rules)
        layoutAiStatusCard = findViewById(R.id.layout_ai_status_card)
        tvAiStatusProvider = findViewById(R.id.tv_ai_status_provider)
        tvAiStatusKey = findViewById(R.id.tv_ai_status_key)
        tvAiStatusBadge = findViewById(R.id.tv_ai_status_badge)
        layoutAiStatusAction = findViewById(R.id.layout_ai_status_action)
        layoutAiStatusCaps = findViewById(R.id.layout_ai_status_caps)
        layoutAiSetupGuide = findViewById(R.id.layout_ai_setup_guide)
        findViewById<View>(R.id.btn_ai_setup).setOnClickListener { showProviderSetupDialog(cancelable = true) }
        btnManageAiRules.setOnClickListener {
            startActivity(Intent(this, AiRuleManageActivity::class.java))
        }
        layoutAiStatusCard.setOnClickListener {
            if (Prefs.isAiConfigured(this)) {
                startActivity(Intent(this, AiConfigActivity::class.java))
            } else {
                showProviderSetupDialog(cancelable = true)
            }
        }
        refreshAiStatusCard()

        try {
            val toggleIds = intArrayOf(
                R.id.switch_ai_chat_mode,
                R.id.switch_local_rule_override,
                R.id.switch_insight_cards,
                R.id.switch_show_voice,
                R.id.switch_show_ai_image,
                R.id.switch_receipt_image_draft_confirm,
                R.id.switch_image_accounting_natural_language,
                R.id.switch_screen_accounting,
                R.id.switch_screen_accounting_image_flow
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
        } catch (_: Exception) {
        }
    }

    private fun refreshAiStatusCard() {
        val configured = Prefs.isAiConfigured(this)
        val preset = AiProviderRegistry.resolvePreset(this)
        if (configured) {
            val key = Prefs.getAiProviderKey(this, preset.id)
            val masked = if (key.length <= 4) "••••" else "••••" + key.takeLast(4)
            val model = AiModelSlots.resolveTextModel(this)
            tvAiStatusProvider.text = preset.displayName
            tvAiStatusKey.text = getString(R.string.ai_page_meta_fmt, model, masked)
            tvAiStatusBadge.text = getString(R.string.ai_page_badge_ready)
            tvAiStatusBadge.setBackgroundResource(R.drawable.bg_ai_pill_ok)
            tvAiStatusBadge.setTextColor(Color.parseColor("#1E9A55"))
            layoutAiStatusAction.visibility = View.VISIBLE
            layoutAiSetupGuide.visibility = View.GONE
            layoutAiStatusCaps.visibility = View.VISIBLE
            renderCapabilityChips(
                listOf(
                    getString(R.string.ai_page_cap_text) to true,
                    getString(R.string.ai_page_cap_vision) to preset.supportsVision,
                    getString(R.string.ai_page_cap_speech) to preset.supportsCloudSpeech
                )
            )
        } else {
            tvAiStatusProvider.text = getString(R.string.ai_page_unconfigured_title)
            tvAiStatusKey.text = getString(R.string.ai_page_unconfigured_desc)
            tvAiStatusBadge.text = getString(R.string.ai_page_badge_unconfigured)
            tvAiStatusBadge.setBackgroundResource(R.drawable.bg_ai_pill_warn)
            tvAiStatusBadge.setTextColor(Color.parseColor("#C77700"))
            layoutAiStatusAction.visibility = View.GONE
            layoutAiStatusCaps.visibility = View.GONE
            layoutAiSetupGuide.visibility = View.VISIBLE
        }
    }

    /** 能力标签：支持的高亮，不支持的置灰加删除线，提示下方哪些开关会被禁用。 */
    private fun renderCapabilityChips(caps: List<Pair<String, Boolean>>) {
        layoutAiStatusCaps.removeAllViews()
        val dp = resources.displayMetrics.density
        caps.forEachIndexed { index, (label, supported) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                if (supported) {
                    setBackgroundResource(R.drawable.bg_ai_chip_on)
                    setTextColor(ContextCompat.getColor(context, R.color.brand_primary))
                } else {
                    setBackgroundResource(R.drawable.bg_ai_chip_off)
                    setTextColor(Color.parseColor("#B4BCC8"))
                    paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) lp.marginStart = (8 * dp).toInt()
            layoutAiStatusCaps.addView(chip, lp)
        }
    }

    private fun showProviderSetupDialog(cancelable: Boolean) {
        AiProviderSetupDialog.show(
            activity = this,
            initialProviderId = Prefs.getAiProvider(this),
            cancelable = cancelable,
            onFinished = { refreshAiStatusCard() }
        )
    }

    private fun showAiDetailConfigUnlockDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.config_key_dialog_title))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val tvHint = TextView(this).apply {
            text = getString(R.string.config_key_dialog_hint)
            textSize = 13f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(0, 0, 0, 16)
        }
        val etPassword = EditText(this).apply {
            hint = getString(R.string.input_key_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        layout.addView(tvHint)
        layout.addView(etPassword)
        builder.setView(layout)
        builder.setPositiveButton(getString(R.string.confirm_btn)) { _, _ ->
            if (etPassword.text.toString().trim() == "1433223") {
                Prefs.setAiDetailConfigUnlocked(this, true)
                refreshAiStatusCard()
                Utils.toast(this, getString(R.string.ai_config_unlocked))
            } else {
                Utils.toast(this, getString(R.string.key_error))
            }
        }
        builder.setNegativeButton(getString(R.string.cancel_btn), null)
        val dialog = builder.create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.8f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun showOcrDebugRecordsDialog() {
        val records = Prefs.getOcrDebugRecords(this)
        if (records.isEmpty()) {
            Utils.toast(this, getString(R.string.no_ocr_record))
            return
        }

        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val items = records.mapIndexed { index, item ->
            val time = formatter.format(java.util.Date(item.timestamp))
            val preview = item.text.replace("\n", " ").take(24)
            "${index + 1}. $time | ${item.source} | $preview"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ocr_record_title, records.size))
            .setItems(items) { _, which ->
                showSingleOcrDebugRecordDialog(records, which)
            }
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.clear_records) { _, _ ->
                Prefs.clearOcrDebugRecords(this)
                Utils.toast(this, getString(R.string.ocr_cleared))
            }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.92f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun showSingleOcrDebugRecordDialog(records: List<OcrDebugRecord>, index: Int) {
        if (index !in records.indices) return

        val item = records[index]
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val content = buildString {
            append("${getString(R.string.time)}：${formatter.format(java.util.Date(item.timestamp))}\n")
            append("${getString(R.string.remark)}：${item.source}\n\n")
            append(item.text)
        }

        val textView = TextView(this).apply {
            text = content
            setPadding(32, 24, 32, 24)
            textSize = 13f
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply { addView(textView) }

        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ocr_record_index, index + 1, records.size))
            .setView(scrollView)
            .setPositiveButton(R.string.copy_this) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ocr_debug_record_$index", item.text))
                Utils.toast(this, getString(R.string.copied_index_fmt, index + 1))
            }
            .setNegativeButton(R.string.back_to_list) { _, _ ->
                showOcrDebugRecordsDialog()
            }

        if (index < records.lastIndex) {
            builder.setNeutralButton(R.string.next_record) { _, _ ->
                showSingleOcrDebugRecordDialog(records, index + 1)
            }
        }

        val dialog = builder.create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.9f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK && data?.data != null) {
            LocalAsrService.installLocalModelWithUI(this, data.data!!) {
                recreate()
            }
        }
    }
}
