package com.taostudio.tapaccounting

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

class AiConfigActivity : AppCompatActivity() {

    private lateinit var tvCurrentProvider: TextView
    private lateinit var tvProviderCapabilities: TextView
    private lateinit var layoutProviderSelector: View
    private lateinit var switchManualModelSelection: SwitchMaterial
    private lateinit var tvDefaultModelReadonlyHint: TextView
    private lateinit var btnRestoreDefaultModels: MaterialButton
    private lateinit var tvApiKeyState: TextView
    private lateinit var btnEditApiKey: TextView
    private lateinit var layoutApiKeyEditor: View
    private lateinit var etKey: EditText
    private lateinit var btnToggleApiKey: TextView
    private lateinit var btnCancelApiKey: MaterialButton
    private lateinit var rowTextModel: View
    private lateinit var rowVisionModel: View
    private lateinit var rowSpeechModel: View
    private lateinit var tvTextModelValue: TextView
    private lateinit var tvVisionModelValue: TextView
    private lateinit var tvSpeechModelValue: TextView
    private lateinit var switchThinkingText: SwitchMaterial
    private lateinit var switchThinkingVision: SwitchMaterial
    private lateinit var tvThinkingVisionHint: TextView

    private var currentPreset: AiProviderPreset = AiProviderRegistry.allPresets().first()
    private var updatingManualSwitch = false
    private var updatingThinkingSwitches = false
    private var keyVisible = false
    private lateinit var modeModels: MutableMap<String, String>
    private lateinit var allModelsList: MutableList<String>
    private lateinit var refreshProviderState: () -> Unit

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_main_ai)

        findViewById<View>(R.id.btn_back_to_settings).setOnClickListener { finish() }

        currentPreset = AiProviderRegistry.resolvePreset(this)

        tvCurrentProvider = findViewById(R.id.tv_current_provider)
        tvProviderCapabilities = findViewById(R.id.tv_provider_capabilities)
        layoutProviderSelector = findViewById(R.id.layout_provider_selector)
        switchManualModelSelection = findViewById(R.id.switch_manual_model_selection)
        tvDefaultModelReadonlyHint = findViewById(R.id.tv_default_model_readonly_hint)
        btnRestoreDefaultModels = findViewById(R.id.btn_restore_default_models)
        tvApiKeyState = findViewById(R.id.tv_api_key_state)
        btnEditApiKey = findViewById(R.id.btn_edit_api_key)
        layoutApiKeyEditor = findViewById(R.id.layout_api_key_editor)
        etKey = findViewById(R.id.et_api_key)
        btnToggleApiKey = findViewById(R.id.btn_toggle_api_key)
        btnCancelApiKey = findViewById(R.id.btn_cancel_api_key)
        rowTextModel = findViewById(R.id.layout_row_text_model)
        rowVisionModel = findViewById(R.id.layout_row_vision_model)
        rowSpeechModel = findViewById(R.id.layout_row_speech_model)
        tvTextModelValue = findViewById(R.id.tv_text_model_value)
        tvVisionModelValue = findViewById(R.id.tv_vision_model_value)
        tvSpeechModelValue = findViewById(R.id.tv_speech_model_value)
        switchThinkingText = findViewById(R.id.switch_thinking_text)
        switchThinkingVision = findViewById(R.id.switch_thinking_vision)
        tvThinkingVisionHint = findViewById(R.id.tv_thinking_vision_hint)

        val btnTest = findViewById<MaterialButton>(R.id.btn_test_conn)
        val btnSave = findViewById<View>(R.id.btn_save_config)

        modeModels = mutableMapOf(
            "text" to effectiveModelForMode("text"),
            "vision" to effectiveModelForMode("vision"),
            "speech" to effectiveModelForMode("speech")
        )

        allModelsList = mutableListOf()
        val cachedModels = Prefs.getAiModelsCache(this).map { it.trim() }.filter { it.isNotEmpty() }
        if (cachedModels.isNotEmpty()) {
            allModelsList.addAll(cachedModels)
        }

        layoutProviderSelector.setOnClickListener {
            AiProviderSetupDialog.show(
                activity = this,
                initialProviderId = currentPreset.id,
                cancelable = true,
                onFinished = { result ->
                    if (result != null) {
                        applyProviderSetupResult(result)
                    } else {
                        refreshProviderState()
                    }
                }
            )
        }

        // ── API 密钥：默认只显示掩码摘要，点「更换密钥」才展开输入 ──

        btnEditApiKey.setOnClickListener { showApiKeyEditor() }
        btnCancelApiKey.setOnClickListener { hideApiKeyEditor() }
        btnToggleApiKey.setOnClickListener {
            keyVisible = !keyVisible
            applyKeyVisibility()
        }
        findViewById<MaterialButton>(R.id.btn_save_api_key).setOnClickListener {
            val value = etKey.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                Utils.toast(this, getString(R.string.please_input_api_key))
                return@setOnClickListener
            }
            Prefs.setAiProviderKey(this, currentPreset.id, value)
            Utils.toast(this, getString(R.string.ai_key_saved_toast))
            renderApiKeyState()
        }

        rowTextModel.setOnClickListener {
            if (!Prefs.isAiManualModelSelectionEnabled(this)) return@setOnClickListener
            showModelSearchDialog("text")
        }
        rowVisionModel.setOnClickListener {
            if (!currentPreset.supportsVision) {
                showCapabilityNotSupportedToast("vision")
                return@setOnClickListener
            }
            if (!Prefs.isAiManualModelSelectionEnabled(this)) return@setOnClickListener
            showModelSearchDialog("vision")
        }
        rowSpeechModel.setOnClickListener {
            if (!currentPreset.supportsCloudSpeech) {
                showCapabilityNotSupportedToast("speech")
                return@setOnClickListener
            }
            if (!Prefs.isAiManualModelSelectionEnabled(this)) return@setOnClickListener
            showModelSearchDialog("speech")
        }

        switchThinkingText.setOnCheckedChangeListener { _, isChecked ->
            if (updatingThinkingSwitches) return@setOnCheckedChangeListener
            Prefs.setAiThinkingMultiBillEnabled(this, isChecked)
            Prefs.setAiThinkingCategoryRefineEnabled(this, isChecked)
        }
        switchThinkingVision.setOnCheckedChangeListener { _, isChecked ->
            if (updatingThinkingSwitches || !currentPreset.supportsVision) return@setOnCheckedChangeListener
            Prefs.setAiThinkingVisionEnabled(this, isChecked)
        }

        refreshProviderState = {
            currentPreset = AiProviderRegistry.resolvePreset(this)
            tvCurrentProvider.text = currentPreset.displayName
            tvProviderCapabilities.text = AiProviderRegistry.capabilitySummary(currentPreset)
            renderApiKeyState()
            reloadModelsCache()
            syncModeModelsFromPrefs()
            updateUi()
        }

        switchManualModelSelection.isChecked = Prefs.isAiManualModelSelectionEnabled(this)
        switchManualModelSelection.setOnCheckedChangeListener { _, isChecked ->
            if (updatingManualSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                showManualModelDisclaimer {
                    Prefs.setAiManualModelSelectionEnabled(this, true)
                    syncModeModelsFromPrefs()
                    updateUi()
                }
            } else {
                Prefs.setAiManualModelSelectionEnabled(this, false)
                AiProviderRegistry.applyDefaultModels(this, currentPreset)
                syncModeModelsFromPrefs()
                updateUi()
            }
        }
        btnRestoreDefaultModels.setOnClickListener {
            AiProviderRegistry.applyDefaultModels(this, currentPreset)
            syncModeModelsFromPrefs()
            updateUi()
            Utils.toast(this, getString(R.string.ai_default_models_restored))
        }

        btnTest.setOnClickListener {
            val key = Prefs.getAiProviderKey(this, currentPreset.id)
            if (key.isBlank()) {
                Utils.toast(this, getString(R.string.please_input_api_key))
                showApiKeyEditor()
                return@setOnClickListener
            }

            btnTest.isEnabled = false
            btnTest.text = getString(R.string.ai_provider_testing)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val models = AIService.fetchModelsForProvider(this@AiConfigActivity, currentPreset, key)
                    // 能力目录拉取失败不阻塞模型刷新，仅影响视觉列表过滤精度。
                    runCatching { AiModelCatalog.refreshFromNetwork(this@AiConfigActivity) }
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = getString(R.string.refresh_test_model)
                        if (models.isNotEmpty()) {
                            val cleanedModels = models.map { it.trim() }.filter { it.isNotEmpty() }
                            Prefs.setAiModelsCache(this@AiConfigActivity, cleanedModels)
                            allModelsList.clear()
                            allModelsList.addAll(cleanedModels)
                            syncModeModelsFromPrefs()
                            updateUi()
                            Utils.toast(this@AiConfigActivity, getString(R.string.model_loaded_fmt, cleanedModels.size))
                        } else {
                            Prefs.setAiModelsCache(this@AiConfigActivity, emptyList())
                            allModelsList.clear()
                            syncModeModelsFromPrefs()
                            updateUi()
                            Utils.toast(this@AiConfigActivity, getString(R.string.no_model_found))
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = getString(R.string.refresh_test_model)
                        val msg = e.message.orEmpty()
                        Utils.toast(
                            this@AiConfigActivity,
                            when {
                                msg.contains("timeout") || msg.contains("Failed to connect") || msg.contains("Unable to resolve host") -> getString(R.string.timeout_or_network_error)
                                msg.contains("401") -> getString(R.string.auth_failed)
                                else -> getString(R.string.connection_test_failed)
                            }
                        )
                    }
                }
            }
        }

        btnSave.setOnClickListener {
            // 密钥编辑框未收起且已输入内容时，随全局保存一并落盘
            if (layoutApiKeyEditor.visibility == View.VISIBLE) {
                val typed = etKey.text?.toString()?.trim().orEmpty()
                if (typed.isNotEmpty()) {
                    Prefs.setAiProviderKey(this, currentPreset.id, typed)
                }
            }

            if (!Prefs.isAiManualModelSelectionEnabled(this)) {
                AiProviderRegistry.applyDefaultModels(this, currentPreset)
            } else {
                val verifiedModels = allModelsList.toSet()
                if (verifiedModels.isNotEmpty()) {
                    modeModels.keys.toList().forEach { key ->
                        val selected = modeModels[key].orEmpty()
                        if (selected.isNotBlank() && selected !in verifiedModels) {
                            modeModels[key] = ""
                        }
                    }
                }

                // 能力目录可用时，视觉槽位不允许保存纯文本模型（清空后回落到预设默认）。
                val catalogProviderId = currentPreset.catalogProviderId
                if (AiModelCatalog.isProviderCatalogUsable(this, catalogProviderId, allModelsList)) {
                    val selectedVision = modeModels["vision"].orEmpty()
                    if (selectedVision.isNotBlank() &&
                        selectedVision != currentPreset.defaultVisionModel &&
                        AiModelCatalog.supportsImageInput(this, catalogProviderId, selectedVision) == false
                    ) {
                        modeModels["vision"] = ""
                    }
                }

                val textModel = modeModels["text"].orEmpty().ifBlank { currentPreset.defaultTextModel }
                val visionModel = if (currentPreset.supportsVision) {
                    modeModels["vision"].orEmpty().ifBlank { currentPreset.defaultVisionModel }
                } else {
                    ""
                }
                val speechModel = if (currentPreset.supportsCloudSpeech) {
                    modeModels["speech"].orEmpty().ifBlank { currentPreset.defaultSpeechModel }
                } else {
                    ""
                }

                Prefs.setAiModel(this, textModel)
                Prefs.setAiMultiModel(this, textModel)
                Prefs.setAiModifyModel(this, textModel)
                Prefs.setAiCategoryRefineModel(this, textModel)
                Prefs.setAiRouterModel(this, textModel)
                Prefs.setAiRuleModel(this, textModel)
                Prefs.setAiReceiptModel(this, textModel)
                Prefs.setAiReceiptOcrRefineModel(this, textModel)
                Prefs.setAiReceiptVisionModel(this, visionModel)
                Prefs.setAiScreenModel(this, visionModel)
                Prefs.setAiSpeechModel(this, speechModel)
            }

            Prefs.setAiThinkingMultiBillEnabled(this, switchThinkingText.isChecked)
            Prefs.setAiThinkingCategoryRefineEnabled(this, switchThinkingText.isChecked)
            Prefs.setAiThinkingVisionEnabled(
                this,
                switchThinkingVision.isChecked && currentPreset.supportsVision
            )
            Utils.toast(this, getString(R.string.ai_config_saved))
            finish()
        }

        refreshProviderState()
    }

    override fun onResume() {
        super.onResume()
        if (::tvCurrentProvider.isInitialized) {
            refreshProviderState()
        }
    }

    // ───────────────────────── 密钥区状态 ─────────────────────────

    private fun applyKeyVisibility() {
        btnToggleApiKey.text = getString(if (keyVisible) R.string.ai_key_hide else R.string.ai_key_show)
        etKey.transformationMethod =
            if (keyVisible) null else PasswordTransformationMethod.getInstance()
    }

    private fun showApiKeyEditor() {
        layoutApiKeyEditor.visibility = View.VISIBLE
        etKey.setText("")
        keyVisible = false
        applyKeyVisibility()
        etKey.requestFocus()
    }

    private fun hideApiKeyEditor() {
        layoutApiKeyEditor.visibility = View.GONE
        etKey.setText("")
        keyVisible = false
        applyKeyVisibility()
    }

    private fun maskKey(key: String): String =
        if (key.length <= 4) "••••" else "••••" + key.takeLast(4)

    private fun renderApiKeyState() {
        val key = Prefs.getAiProviderKey(this, currentPreset.id)
        if (key.isBlank()) {
            tvApiKeyState.text = getString(R.string.ai_key_state_missing)
            tvApiKeyState.setTextColor(Color.parseColor("#B26A00"))
            btnEditApiKey.text = getString(R.string.ai_key_action_add)
        } else {
            tvApiKeyState.text = getString(R.string.ai_key_state_saved_fmt, maskKey(key))
            tvApiKeyState.setTextColor(Color.parseColor("#333333"))
            btnEditApiKey.text = getString(R.string.ai_key_action_replace)
        }
        hideApiKeyEditor()
    }

    // ───────────────────────── 模型槽位 ─────────────────────────

    private fun updateUi() {
        updateSlotRows()
        updateThinkingSwitches()
        updateModelSelectionUi()
    }

    private fun updateSlotRows() {
        val manual = Prefs.isAiManualModelSelectionEnabled(this)
        fun render(row: View, valueView: TextView, slot: String, supported: Boolean) {
            row.isEnabled = manual
            row.alpha = if (manual) 1f else 0.55f
            val value = if (!supported) {
                getString(R.string.unsupported_provider_hint)
            } else {
                effectiveModelForMode(slot).ifBlank { getString(R.string.unsupported_provider_hint) }
            }
            valueView.text = value
            valueView.setTextColor(
                if (supported) Color.parseColor("#333333") else Color.parseColor("#9AA4B2")
            )
        }
        render(rowTextModel, tvTextModelValue, "text", true)
        render(rowVisionModel, tvVisionModelValue, "vision", currentPreset.supportsVision)
        render(rowSpeechModel, tvSpeechModelValue, "speech", currentPreset.supportsCloudSpeech)
    }

    private fun updateThinkingSwitches() {
        updatingThinkingSwitches = true
        switchThinkingText.isChecked = Prefs.isAiThinkingMultiBillEnabled(this)
        switchThinkingVision.isEnabled = currentPreset.supportsVision
        switchThinkingVision.alpha = if (currentPreset.supportsVision) 1f else 0.45f
        tvThinkingVisionHint.alpha = if (currentPreset.supportsVision) 1f else 0.45f
        switchThinkingVision.isChecked =
            currentPreset.supportsVision && Prefs.isAiThinkingVisionEnabled(this)
        updatingThinkingSwitches = false
    }

    private fun updateModelSelectionUi() {
        val manual = Prefs.isAiManualModelSelectionEnabled(this)
        tvDefaultModelReadonlyHint.visibility = if (manual) View.GONE else View.VISIBLE
        btnRestoreDefaultModels.visibility = if (manual) View.VISIBLE else View.GONE
    }

    private fun showModelSearchDialog(slot: String) {
        if (!Prefs.isAiManualModelSelectionEnabled(this)) return
        if (slot != "text" && !isModeSupported(slot)) {
            showCapabilityNotSupportedToast(slot)
            return
        }

        val catalogProviderId = currentPreset.catalogProviderId
        val catalogUsable = AiModelCatalog.isProviderCatalogUsable(this, catalogProviderId, allModelsList)
        val baseModels: List<String> = when (slot) {
            "vision" -> AiModelCatalog.visionModelsForPicker(
                this,
                catalogProviderId,
                allModelsList,
                currentPreset.defaultVisionModel
            ) ?: allModelsList

            else -> allModelsList
        }
        if (slot == "vision" && baseModels.isEmpty() && allModelsList.isNotEmpty()) {
            Utils.toast(this, getString(R.string.ai_vision_no_capable_model))
            return
        }

        fun displayLabel(model: String): String {
            if (!catalogUsable) return model
            val imageCapable = AiModelCatalog.imageCapabilityBadge(
                this,
                catalogProviderId,
                model,
                currentPreset.defaultVisionModel
            ) ?: return model
            val label = getString(
                if (imageCapable) R.string.ai_model_cap_image else R.string.ai_model_cap_text
            )
            return "$model  ·  $label"
        }

        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etSearch = EditText(this).apply {
            hint = getString(R.string.search_ai_model)
            setSingleLine()
        }
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        dialogLayout.addView(etSearch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        dialogLayout.addView(listView)

        val shownModels = mutableListOf<String>()
        val shownLabels = mutableListOf<String>()
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shownLabels)
        listView.adapter = listAdapter

        fun rebuild(query: String) {
            val filtered = if (query.isEmpty()) {
                baseModels
            } else {
                baseModels.filter { it.contains(query, ignoreCase = true) }
            }
            shownModels.clear()
            shownModels.addAll(filtered)
            shownLabels.clear()
            shownLabels.addAll(filtered.map(::displayLabel))
            listAdapter.notifyDataSetChanged()
        }
        rebuild("")

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.select_ai_model)
            .setView(dialogLayout)
            .create()

        fun showCapabilityEditor(model: String) {
            if (catalogProviderId.isNullOrBlank()) return
            val override = Prefs.getAiModelCapabilityOverride(this, catalogProviderId, model)
            val currentImage = AiModelCatalog.supportsImageInput(
                this,
                catalogProviderId,
                model,
                currentPreset.defaultVisionModel
            ) == true
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            if (!currentImage) {
                actions.add(getString(R.string.ai_model_cap_mark_image) to {
                    Prefs.setAiModelCapabilityOverride(
                        this, catalogProviderId, model, AiModelCatalog.OVERRIDE_IMAGE
                    )
                    Utils.toast(this, getString(R.string.ai_model_cap_saved))
                    rebuild(etSearch.text?.toString()?.trim().orEmpty())
                })
            }
            if (currentImage) {
                actions.add(getString(R.string.ai_model_cap_mark_text) to {
                    Prefs.setAiModelCapabilityOverride(
                        this, catalogProviderId, model, AiModelCatalog.OVERRIDE_TEXT
                    )
                    Utils.toast(this, getString(R.string.ai_model_cap_saved))
                    rebuild(etSearch.text?.toString()?.trim().orEmpty())
                })
            }
            if (override != null) {
                actions.add(getString(R.string.ai_model_cap_clear) to {
                    Prefs.setAiModelCapabilityOverride(this, catalogProviderId, model, null)
                    Utils.toast(this, getString(R.string.ai_model_cap_saved))
                    rebuild(etSearch.text?.toString()?.trim().orEmpty())
                })
            }
            if (currentPreset.supportsVision) {
                actions.add(getString(R.string.ai_model_cap_probe) to {
                    Utils.toast(this, getString(R.string.ai_model_cap_probe_running))
                    CoroutineScope(Dispatchers.IO).launch {
                        val ok = runCatching {
                            AIService.probeVisionInputSupport(this@AiConfigActivity, model)
                        }.getOrDefault(false)
                        withContext(Dispatchers.Main) {
                            Prefs.setAiModelCapabilityOverride(
                                this@AiConfigActivity,
                                catalogProviderId,
                                model,
                                if (ok) AiModelCatalog.OVERRIDE_IMAGE else AiModelCatalog.OVERRIDE_TEXT
                            )
                            Utils.toast(
                                this@AiConfigActivity,
                                getString(
                                    if (ok) R.string.ai_model_cap_probe_ok
                                    else R.string.ai_model_cap_probe_fail
                                )
                            )
                            rebuild(etSearch.text?.toString()?.trim().orEmpty())
                        }
                    }
                })
            }
            if (actions.isEmpty()) return
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.ai_model_cap_title_fmt, model))
                .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                    actions.getOrNull(which)?.second?.invoke()
                }
                .show()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                rebuild(s?.toString()?.trim().orEmpty())
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = shownModels.getOrNull(position) ?: return@setOnItemClickListener
            modeModels[slot] = selected
            updateSlotRows()
            dialog.dismiss()
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            shownModels.getOrNull(position)?.let(::showCapabilityEditor)
            true
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this@AiConfigActivity,
            widthRatio = 0.9f
        )
        dialog.window?.let { win ->
            val targetHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
            win.attributes = win.attributes.apply {
                height = targetHeight
            }
        }
    }

    private fun applyProviderSetupResult(result: AiProviderSetupResult) {
        allModelsList.clear()
        allModelsList.addAll(result.models)
        Prefs.resetChatModelOnProviderChange(this)
        refreshProviderState()
    }

    private fun reloadModelsCache() {
        val cachedModels = Prefs.getAiModelsCache(this).map { it.trim() }.filter { it.isNotEmpty() }
        allModelsList.clear()
        allModelsList.addAll(cachedModels)
    }

    private fun syncModeModelsFromPrefs() {
        modeModels["text"] = effectiveModelForMode("text")
        modeModels["vision"] = effectiveModelForMode("vision")
        modeModels["speech"] = effectiveModelForMode("speech")
    }

    private fun effectiveModelForMode(mode: String): String {
        if (!Prefs.isAiManualModelSelectionEnabled(this)) {
            return when (mode) {
                "vision" -> if (currentPreset.supportsVision) currentPreset.defaultVisionModel else ""
                "speech" -> if (currentPreset.supportsCloudSpeech) currentPreset.defaultSpeechModel else ""
                else -> currentPreset.defaultTextModel
            }
        }
        return when (mode) {
            "text" -> Prefs.getAiMultiModel(this)
            "vision" -> Prefs.getAiReceiptVisionModel(this)
            "speech" -> Prefs.getAiSpeechModel(this)
            else -> ""
        }
    }

    private fun isModeSupported(mode: String): Boolean = when (mode) {
        "vision" -> currentPreset.supportsVision
        "speech" -> currentPreset.supportsCloudSpeech
        else -> true
    }

    private fun showCapabilityNotSupportedToast(mode: String) {
        val capability = when (mode) {
            "vision" -> getString(R.string.ai_capability_vision)
            "speech" -> getString(R.string.ai_capability_speech)
            else -> return
        }
        Utils.toast(
            this,
            getString(R.string.ai_capability_not_supported_fmt, currentPreset.displayName, capability)
        )
    }

    private fun showManualModelDisclaimer(onConfirm: () -> Unit) {
        fun resetSwitch() {
            if (Prefs.isAiManualModelSelectionEnabled(this)) return
            updatingManualSwitch = true
            switchManualModelSelection.isChecked = false
            updatingManualSwitch = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ai_manual_model_disclaimer_title)
            .setMessage(R.string.ai_manual_model_disclaimer_message)
            .setPositiveButton(R.string.confirm_btn) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel_btn) { _, _ -> resetSwitch() }
            .create()
        dialog.setOnCancelListener { resetSwitch() }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }
}
