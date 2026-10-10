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

/** 模型列表缓存静默刷新间隔：超过后打开 AI 设置页时自动重新拉取一次。 */
private const val MODELS_CACHE_REFRESH_TTL_MS = 7L * 24 * 60 * 60 * 1000

class AiConfigActivity : AppCompatActivity() {

    private lateinit var tvCurrentProvider: TextView
    private lateinit var tvProviderCapabilities: TextView
    private lateinit var layoutProviderSelector: View
    private lateinit var switchManualModelSelection: SwitchMaterial
    private lateinit var tvDefaultModelReadonlyHint: TextView
    private lateinit var btnRestoreDefaultModels: View
    private lateinit var layoutRestoreDefaultModels: View
    private lateinit var tvTestConnDesc: TextView
    private lateinit var tvTestConnAction: TextView
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
        layoutRestoreDefaultModels = findViewById(R.id.layout_restore_default_models)
        tvTestConnDesc = findViewById(R.id.tv_test_conn_desc)
        tvTestConnAction = findViewById(R.id.tv_test_conn_action)
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

        val btnTest = findViewById<View>(R.id.btn_test_conn)

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

        findViewById<View>(R.id.btn_edit_api_key_row).setOnClickListener {
            if (layoutApiKeyEditor.visibility == View.VISIBLE) hideApiKeyEditor() else showApiKeyEditor()
        }
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
            if (!Prefs.isAiManualModelSelectionEnabled(this)) {
                Utils.toast(this, getString(R.string.ai_cfg_enable_manual_first))
                return@setOnClickListener
            }
            showModelSearchDialog("text")
        }
        rowVisionModel.setOnClickListener {
            if (!currentPreset.supportsVision) {
                showCapabilityNotSupportedToast("vision")
                return@setOnClickListener
            }
            if (!Prefs.isAiManualModelSelectionEnabled(this)) {
                Utils.toast(this, getString(R.string.ai_cfg_enable_manual_first))
                return@setOnClickListener
            }
            showModelSearchDialog("vision")
        }
        rowSpeechModel.setOnClickListener {
            if (!currentPreset.supportsCloudSpeech) {
                showCapabilityNotSupportedToast("speech")
                return@setOnClickListener
            }
            if (!Prefs.isAiManualModelSelectionEnabled(this)) {
                Utils.toast(this, getString(R.string.ai_cfg_enable_manual_first))
                return@setOnClickListener
            }
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
        findViewById<View>(R.id.layout_thinking_text).setOnClickListener { switchThinkingText.performClick() }
        findViewById<View>(R.id.layout_thinking_vision).setOnClickListener {
            if (currentPreset.supportsVision) switchThinkingVision.performClick()
            else showCapabilityNotSupportedToast("vision")
        }
        (switchManualModelSelection.parent as View).setOnClickListener { switchManualModelSelection.performClick() }

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
            tvTestConnAction.text = getString(R.string.ai_provider_testing)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val models = AIService.fetchModelsForProvider(this@AiConfigActivity, currentPreset, key)
                    // 能力目录拉取失败不阻塞模型刷新，仅影响视觉列表过滤精度。
                    runCatching { AiModelCatalog.refreshFromNetwork(this@AiConfigActivity) }
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        tvTestConnAction.text = getString(R.string.ai_cfg_test_action)
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
                        tvTestConnAction.text = getString(R.string.ai_cfg_test_action)
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

        refreshProviderState()
        maybeRefreshModelsCache()
    }

    /** 已有 Key 但模型列表缓存过期时静默刷新一次，让默认模型自动跟随厂商换代。 */
    private fun maybeRefreshModelsCache() {
        if (!Prefs.isAiConfigured(this)) return
        val age = System.currentTimeMillis() - Prefs.getAiModelsCacheAt(this)
        if (age in 0 until MODELS_CACHE_REFRESH_TTL_MS) return
        val presetSnapshot = currentPreset
        val key = Prefs.getAiProviderKey(this, presetSnapshot.id)
        if (key.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val models = AIService.fetchModelsForProvider(this@AiConfigActivity, presetSnapshot, key)
                    .map { it.trim() }.filter { it.isNotEmpty() }
                if (models.isNotEmpty()) {
                    Prefs.setAiModelsCache(this@AiConfigActivity, models)
                    withContext(Dispatchers.Main) {
                        if (currentPreset.id == presetSnapshot.id) {
                            reloadModelsCache()
                            syncModeModelsFromPrefs()
                            updateUi()
                        }
                    }
                }
            }
        }
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
            tvApiKeyState.setTextColor(getColor(R.color.settings_item_subtitle))
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
        fun render(row: View, valueView: TextView, chevronId: Int, slot: String, supported: Boolean) {
            val editable = manual && supported
            row.alpha = if (supported) 1f else 0.5f
            findViewById<View>(chevronId).visibility = if (editable) View.VISIBLE else View.GONE
            valueView.text = if (!supported) {
                getString(R.string.ai_cfg_unsupported)
            } else {
                modeModels[slot].orEmpty().ifBlank { effectiveModelForMode(slot) }
            }
            valueView.setTextColor(
                Color.parseColor(if (editable) "#1A2744" else "#9AA4B2")
            )
        }
        render(rowTextModel, tvTextModelValue, R.id.iv_text_model_chevron, "text", true)
        render(rowVisionModel, tvVisionModelValue, R.id.iv_vision_model_chevron, "vision", currentPreset.supportsVision)
        render(rowSpeechModel, tvSpeechModelValue, R.id.iv_speech_model_chevron, "speech", currentPreset.supportsCloudSpeech)
        tvTestConnDesc.text = if (allModelsList.isEmpty()) {
            getString(R.string.ai_cfg_test_desc_empty)
        } else {
            getString(R.string.ai_cfg_test_desc_fmt, allModelsList.size)
        }
    }

    private fun updateThinkingSwitches() {
        updatingThinkingSwitches = true
        switchThinkingText.isChecked = Prefs.isAiThinkingMultiBillEnabled(this)
        switchThinkingVision.isEnabled = currentPreset.supportsVision
        (switchThinkingVision.parent as View).alpha = if (currentPreset.supportsVision) 1f else 0.5f
        tvThinkingVisionHint.text = getString(
            if (currentPreset.supportsVision) R.string.ai_cfg_thinking_vision_desc
            else R.string.ai_cfg_thinking_vision_unsupported
        )
        switchThinkingVision.isChecked =
            currentPreset.supportsVision && Prefs.isAiThinkingVisionEnabled(this)
        updatingThinkingSwitches = false
    }

    private fun updateModelSelectionUi() {
        val manual = Prefs.isAiManualModelSelectionEnabled(this)
        tvDefaultModelReadonlyHint.text = getString(
            if (manual) R.string.ai_cfg_manual_desc_on else R.string.ai_cfg_manual_desc_off
        )
        layoutRestoreDefaultModels.visibility = if (manual) View.VISIBLE else View.GONE
    }

    private fun showModelSearchDialog(slot: String) {
        if (!Prefs.isAiManualModelSelectionEnabled(this)) return
        if (slot != "text" && !isModeSupported(slot)) {
            showCapabilityNotSupportedToast(slot)
            return
        }

        if (allModelsList.isEmpty()) {
            Utils.toast(this, getString(R.string.ai_cfg_need_model_list))
            return
        }
        val catalogProviderId = currentPreset.catalogProviderId
        val catalogUsable = AiModelCatalog.isProviderCatalogUsable(this, catalogProviderId, allModelsList)
        val currentModel = modeModels[slot].orEmpty()
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
            if (!catalogUsable) {
                return if (model == currentModel) "✓ $model" else model
            }
            val imageCapable = AiModelCatalog.imageCapabilityBadge(
                this,
                catalogProviderId,
                model,
                currentPreset.defaultVisionModel
            ) ?: return if (model == currentModel) "✓ $model" else model
            val label = getString(
                if (imageCapable) R.string.ai_model_cap_image else R.string.ai_model_cap_text
            )
            val prefix = if (model == currentModel) "✓ " else ""
            return "$prefix$model  ·  $label"
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
        // 让当前已选模型始终可见（长列表时滚动到它的位置）。
        listView.post {
            val index = shownModels.indexOf(currentModel)
            if (index > 0) listView.setSelection(index)
        }

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
            persistModelSlots()
            updateSlotRows()
            dialog.dismiss()
            if (selected != currentModel) {
                Utils.toast(this, getString(R.string.ai_cfg_model_switched_fmt, selected))
            }
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

    /** 选模型即生效：把各槽位写回所有使用该槽位的业务配置。 */
    private fun persistModelSlots() {
        if (!Prefs.isAiManualModelSelectionEnabled(this)) return
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

    private fun effectiveModelForMode(mode: String): String = when (mode) {
        // 展示值与运行时解析保持一致：厂商换代后这里显示的是自动顶替后的模型。
        "vision" -> if (currentPreset.supportsVision) AiModelSlots.resolveVisionModel(this) else ""
        "speech" -> if (currentPreset.supportsCloudSpeech) AiModelSlots.resolveSpeechModel(this) else ""
        else -> AiModelSlots.resolveTextModel(this)
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
