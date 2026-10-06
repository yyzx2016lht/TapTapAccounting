package com.taostudio.tapaccounting

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

class AiConfigActivity : AppCompatActivity() {
    private enum class ThinkingBinding {
        TEXT,
        VISION,
        FIXED_OFF
    }

    private lateinit var tvCurrentProvider: TextView
    private lateinit var tvProviderCapabilities: TextView
    private lateinit var layoutProviderSelector: View
    private lateinit var switchManualModelSelection: SwitchMaterial
    private lateinit var tvDefaultModelReadonlyHint: TextView
    private lateinit var btnRestoreDefaultModels: MaterialButton
    private lateinit var tvSelectedModel: TextView
    private lateinit var layoutModelSelector: View
    private lateinit var btnVisionModel: Chip
    private lateinit var btnSpeech: Chip

    private var currentPreset: AiProviderPreset = AiProviderRegistry.allPresets().first()
    private var currentMode = "text"
    private var updatingManualSwitch = false
    private lateinit var etKey: EditText
    private lateinit var modeModels: MutableMap<String, String>
    private lateinit var allModelsList: MutableList<String>
    private lateinit var refreshProviderState: () -> Unit

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_main_ai)

        findViewById<View>(R.id.btn_back_to_settings).setOnClickListener { finish() }

        currentPreset = AiProviderRegistry.resolvePreset(this)

        etKey = findViewById(R.id.et_api_key)
        tvSelectedModel = findViewById(R.id.tv_selected_model)
        layoutModelSelector = findViewById(R.id.layout_model_selector)
        tvCurrentProvider = findViewById(R.id.tv_current_provider)
        tvProviderCapabilities = findViewById(R.id.tv_provider_capabilities)
        layoutProviderSelector = findViewById(R.id.layout_provider_selector)
        switchManualModelSelection = findViewById(R.id.switch_manual_model_selection)
        tvDefaultModelReadonlyHint = findViewById(R.id.tv_default_model_readonly_hint)
        btnRestoreDefaultModels = findViewById(R.id.btn_restore_default_models)

        val btnTextModel = findViewById<Chip>(R.id.btn_text_model)
        btnVisionModel = findViewById(R.id.btn_vision_model)
        btnSpeech = findViewById(R.id.btn_speech_prompt)

        val btnTest = findViewById<MaterialButton>(R.id.btn_test_conn)
        val btnSave = findViewById<View>(R.id.btn_save_config)
        val tvEditPrompt = findViewById<TextView>(R.id.tv_edit_prompt)
        val switchEnableThinkingCurrent = findViewById<SwitchMaterial>(R.id.switch_enable_thinking_current)
        val tvThinkingScopeHint = findViewById<TextView>(R.id.tv_thinking_scope_hint)

        etKey.setText(Prefs.getAiProviderKey(this, currentPreset.id))

        var updatingThinkingUi = false
        modeModels = mutableMapOf(
            "text" to effectiveModelForMode("text"),
            "vision" to effectiveModelForMode("vision"),
            "speech" to effectiveModelForMode("speech")
        )

        allModelsList = mutableListOf()
        tvEditPrompt.visibility = View.GONE

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
        etKey.setText(Prefs.getAiProviderKey(this, currentPreset.id))
        tvCurrentProvider.text = currentPreset.displayName
        tvProviderCapabilities.text = AiProviderRegistry.capabilitySummary(currentPreset)
                        refreshProviderState()
                    }
                }
            )
        }

        fun thinkingBindingForMode(mode: String): ThinkingBinding = when (mode) {
            "text" -> ThinkingBinding.TEXT
            "vision" -> ThinkingBinding.VISION
            else -> ThinkingBinding.FIXED_OFF
        }

        fun isThinkingEnabled(binding: ThinkingBinding): Boolean = when (binding) {
            ThinkingBinding.TEXT -> Prefs.isAiThinkingMultiBillEnabled(this)
            ThinkingBinding.VISION -> Prefs.isAiThinkingVisionEnabled(this)
            ThinkingBinding.FIXED_OFF -> false
        }

        fun setThinkingEnabled(binding: ThinkingBinding, enabled: Boolean) {
            when (binding) {
                ThinkingBinding.TEXT -> {
                    Prefs.setAiThinkingMultiBillEnabled(this, enabled)
                    Prefs.setAiThinkingCategoryRefineEnabled(this, enabled)
                }
                ThinkingBinding.VISION -> Prefs.setAiThinkingVisionEnabled(this, enabled)
                ThinkingBinding.FIXED_OFF -> Unit
            }
        }

        fun updateThinkingUi() {
            val binding = thinkingBindingForMode(currentMode)
            val modeTitle = when (currentMode) {
                "text" -> getString(R.string.text_model_label)
                "vision" -> getString(R.string.vision_model_label)
                "speech" -> getString(R.string.speech_model_label)
                else -> getString(R.string.current_mode_label)
            }
            updatingThinkingUi = true
            when (binding) {
                ThinkingBinding.FIXED_OFF -> {
                    switchEnableThinkingCurrent.isEnabled = false
                    switchEnableThinkingCurrent.isChecked = false
                    switchEnableThinkingCurrent.text = getString(R.string.thinking_fixed_off, modeTitle)
                    tvThinkingScopeHint.text = getString(R.string.thinking_fixed_off_hint)
                }

                else -> {
                    switchEnableThinkingCurrent.isEnabled = true
                    switchEnableThinkingCurrent.isChecked = isThinkingEnabled(binding)
                    switchEnableThinkingCurrent.text = getString(R.string.thinking_enabled, modeTitle)
                    tvThinkingScopeHint.text = when (binding) {
                        ThinkingBinding.TEXT -> getString(R.string.thinking_scope_text)
                        ThinkingBinding.VISION -> getString(R.string.thinking_scope_vision)
                        ThinkingBinding.FIXED_OFF -> ""
                    }
                }
            }
            updatingThinkingUi = false
        }

        switchEnableThinkingCurrent.setOnCheckedChangeListener { _, isChecked ->
            if (updatingThinkingUi) return@setOnCheckedChangeListener
            val binding = thinkingBindingForMode(currentMode)
            if (binding != ThinkingBinding.FIXED_OFF) {
                setThinkingEnabled(binding, isChecked)
            }
        }

        fun showModelSearchDialog() {
            if (!Prefs.isAiManualModelSelectionEnabled(this)) return
            if (!isModeSupported(currentMode)) {
                showCapabilityNotSupportedToast(currentMode)
                return
            }

            val catalogProviderId = currentPreset.catalogProviderId
            val catalogUsable = AiModelCatalog.isProviderCatalogUsable(this, catalogProviderId, allModelsList)
            val baseModels: List<String> = when (currentMode) {
                "vision" -> AiModelCatalog.visionModelsForPicker(
                    this,
                    catalogProviderId,
                    allModelsList,
                    currentPreset.defaultVisionModel
                ) ?: allModelsList

                else -> allModelsList
            }
            if (currentMode == "vision" && baseModels.isEmpty() && allModelsList.isNotEmpty()) {
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
                modeModels[currentMode] = selected
                tvSelectedModel.text = selected
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

        layoutModelSelector.setOnClickListener {
            showModelSearchDialog()
        }

        fun updateCapabilityChips() {
            btnVisionModel.isEnabled = currentPreset.supportsVision
            btnSpeech.isEnabled = currentPreset.supportsCloudSpeech
            btnVisionModel.alpha = if (currentPreset.supportsVision) 1f else 0.45f
            btnSpeech.alpha = if (currentPreset.supportsCloudSpeech) 1f else 0.45f
        }

        fun updateModelSelectionUi() {
            val manual = Prefs.isAiManualModelSelectionEnabled(this)
            layoutModelSelector.isEnabled = manual
            layoutModelSelector.alpha = if (manual) 1f else 0.55f
            tvDefaultModelReadonlyHint.visibility = if (manual) View.GONE else View.VISIBLE
        }

        fun updateUI() {
            btnTextModel.isChecked = currentMode == "text"
            btnVisionModel.isChecked = currentMode == "vision"
            btnSpeech.isChecked = currentMode == "speech"
            tvSelectedModel.text = modeModels[currentMode].orEmpty().ifBlank {
                defaultLabelForUnsupportedMode(currentMode)
            }
            updateThinkingUi()
            updateCapabilityChips()
            updateModelSelectionUi()
        }

        fun switchMode(newMode: String) {
            if (newMode != "text" && !isModeSupported(newMode)) {
                showCapabilityNotSupportedToast(newMode)
                return
            }
            tvSelectedModel.text.toString().takeIf { it.isNotEmpty() }?.let {
                if (!it.startsWith("（")) {
                    modeModels[currentMode] = it
                }
            }
            currentMode = newMode
            updateUI()
        }

        btnTextModel.setOnClickListener { switchMode("text") }
        btnVisionModel.setOnClickListener { switchMode("vision") }
        btnSpeech.setOnClickListener { switchMode("speech") }

        refreshProviderState = {
            currentPreset = AiProviderRegistry.resolvePreset(this)
            tvCurrentProvider.text = currentPreset.displayName
            tvProviderCapabilities.text = AiProviderRegistry.capabilitySummary(currentPreset)
            etKey.setText(Prefs.getAiProviderKey(this, currentPreset.id))
            reloadModelsCache()
            syncModeModelsFromPrefs()
            updateUI()
        }

        switchManualModelSelection.isChecked = Prefs.isAiManualModelSelectionEnabled(this)
        switchManualModelSelection.setOnCheckedChangeListener { _, isChecked ->
            if (updatingManualSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                showManualModelDisclaimer {
                    Prefs.setAiManualModelSelectionEnabled(this, true)
                    btnRestoreDefaultModels.visibility = View.VISIBLE
                    updateModelSelectionUi()
                }
            } else {
                Prefs.setAiManualModelSelectionEnabled(this, false)
                AiProviderRegistry.applyDefaultModels(this, currentPreset)
                modeModels["text"] = effectiveModelForMode("text")
                modeModels["vision"] = effectiveModelForMode("vision")
                modeModels["speech"] = effectiveModelForMode("speech")
                btnRestoreDefaultModels.visibility = View.GONE
                updateModelSelectionUi()
                updateUI()
            }
        }
        btnRestoreDefaultModels.visibility =
            if (Prefs.isAiManualModelSelectionEnabled(this)) View.VISIBLE else View.GONE
        btnRestoreDefaultModels.setOnClickListener {
            AiProviderRegistry.applyDefaultModels(this, currentPreset)
            modeModels["text"] = effectiveModelForMode("text")
            modeModels["vision"] = effectiveModelForMode("vision")
            modeModels["speech"] = effectiveModelForMode("speech")
            updateUI()
            Utils.toast(this, getString(R.string.ai_default_models_restored))
        }

        btnTest.setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.isEmpty()) {
                Utils.toast(this, getString(R.string.please_input_api_key))
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
                        Prefs.setAiKey(this@AiConfigActivity, key)
                        if (models.isNotEmpty()) {
                            val cleanedModels = models.map { it.trim() }.filter { it.isNotEmpty() }
                            Prefs.setAiModelsCache(this@AiConfigActivity, cleanedModels)
                            allModelsList.clear()
                            allModelsList.addAll(cleanedModels)
                            syncModeModelsFromPrefs()
                            updateUI()
                            Utils.toast(this@AiConfigActivity, getString(R.string.model_loaded_fmt, cleanedModels.size))
                        } else {
                            Prefs.setAiModelsCache(this@AiConfigActivity, emptyList())
                            allModelsList.clear()
                            syncModeModelsFromPrefs()
                            updateUI()
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
            tvSelectedModel.text.toString().takeIf { it.isNotEmpty() && !it.startsWith("（") }?.let {
                modeModels[currentMode] = it
            }

            Prefs.setAiKey(this, etKey.text.toString().trim())

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

            val currentThinkingBinding = thinkingBindingForMode(currentMode)
            if (currentThinkingBinding != ThinkingBinding.FIXED_OFF) {
                setThinkingEnabled(currentThinkingBinding, switchEnableThinkingCurrent.isChecked)
            }
            Utils.toast(this, getString(R.string.ai_config_saved))
            finish()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        if (::tvCurrentProvider.isInitialized) {
            refreshProviderState()
        }
    }

    private fun applyProviderSetupResult(result: AiProviderSetupResult) {
        etKey.setText(result.apiKey)
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

    private fun defaultLabelForUnsupportedMode(mode: String): String = when (mode) {
        "vision" -> if (!currentPreset.supportsVision) getString(R.string.unsupported_provider_hint) else ""
        "speech" -> if (!currentPreset.supportsCloudSpeech) getString(R.string.unsupported_provider_hint) else ""
        else -> ""
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
