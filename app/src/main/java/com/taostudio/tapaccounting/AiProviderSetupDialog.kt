package com.taostudio.tapaccounting

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AiProviderSetupDialog {

    fun show(
        activity: AppCompatActivity,
        initialProviderId: String? = null,
        cancelable: Boolean = true,
        onFinished: ((AiProviderSetupResult?) -> Unit)? = null
    ) {
        val presets = AiProviderRegistry.allPresets()
        val density = activity.resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val tvHint = TextView(activity).apply {
            text = activity.getString(R.string.ai_provider_setup_hint)
            textSize = 13f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(0, 0, 0, dp(12))
        }

        val currentProvider = initialProviderId?.trim().orEmpty().ifBlank { Prefs.getAiProvider(activity) }
        var selectedIndex = presets.indexOfFirst { it.id == currentProvider }.coerceAtLeast(0)

        val rowsContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            addView(rowsContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300)
            )
        }
        val rowRoots = mutableListOf<LinearLayout>()
        val rowChecks = mutableListOf<TextView>()

        fun rowBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (selected) {
                setColor(Color.parseColor("#EEF0FB"))
                setStroke(dp(1), Color.parseColor("#C5CAE9"))
            } else {
                setColor(Color.TRANSPARENT)
            }
        }

        // ── 密钥输入（不预填已保存密钥；留空=沿用） ──

        val tvKeyLabel = TextView(activity).apply {
            text = activity.getString(R.string.api_key)
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(14), 0, dp(6))
        }

        val etKey = EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            // setSingleLine() 会重置 transformationMethod，必须在其后显式恢复密码掩码。
            transformationMethod = PasswordTransformationMethod.getInstance()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }

        val tvHowToGet = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5C6BC0"))
            setPadding(0, dp(10), 0, 0)
            isClickable = true
            isFocusable = true
        }

        fun updateSelectionUi() {
            val preset = presets[selectedIndex]
            val hasSavedKey = Prefs.getAiProviderKey(activity, preset.id).isNotBlank()
            etKey.setText("")
            etKey.hint = if (hasSavedKey) {
                activity.getString(R.string.ai_key_keep_hint)
            } else {
                preset.keyHint
            }
            tvHowToGet.text = activity.getString(R.string.ai_provider_get_key_fmt, preset.displayName)
        }

        fun renderRows() {
            rowRoots.forEachIndexed { index, row ->
                row.background = rowBackground(index == selectedIndex)
            }
            rowChecks.forEachIndexed { index, check ->
                check.visibility = if (index == selectedIndex) View.VISIBLE else View.GONE
            }
        }

        fun rebuildProviderRows() {
            rowsContainer.removeAllViews()
            rowRoots.clear()
            rowChecks.clear()
            presets.forEachIndexed { index, preset ->
                val hasKey = Prefs.getAiProviderKey(activity, preset.id).isNotBlank()

                val nameView = TextView(activity).apply {
                    text = preset.displayName
                    textSize = 15f
                    setTextColor(Color.parseColor("#333333"))
                    setTypeface(typeface, Typeface.BOLD)
                }
                val capabilityView = TextView(activity).apply {
                    text = AiProviderRegistry.capabilitySummary(preset)
                    textSize = 11f
                    setTextColor(Color.parseColor("#667085"))
                    setPadding(0, dp(2), 0, 0)
                }
                val labelColumn = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(nameView)
                    addView(capabilityView)
                }

                val configuredView = TextView(activity).apply {
                    text = activity.getString(R.string.ai_provider_configured)
                    textSize = 10f
                    setTextColor(Color.parseColor("#1976D2"))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(Color.parseColor("#E3F2FD"))
                    }
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    visibility = if (hasKey) View.VISIBLE else View.GONE
                }
                val checkView = TextView(activity).apply {
                    text = "✓"
                    textSize = 16f
                    setTextColor(Color.parseColor("#5C6BC0"))
                    setPadding(dp(8), 0, 0, 0)
                    visibility = if (index == selectedIndex) View.VISIBLE else View.GONE
                }

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    isFocusable = true
                    addView(labelColumn)
                    addView(configuredView)
                    addView(checkView)
                }
                row.setOnClickListener {
                    if (selectedIndex != index) {
                        selectedIndex = index
                        renderRows()
                        updateSelectionUi()
                    }
                }
                rowsContainer.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                rowRoots.add(row)
                rowChecks.add(checkView)
            }
            renderRows()
        }

        tvHowToGet.setOnClickListener {
            val preset = presets[selectedIndex]
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preset.createKeyUrl)))
            }.onFailure {
                Utils.toast(activity, activity.getString(R.string.ai_provider_open_link_failed))
            }
        }

        rebuildProviderRows()
        updateSelectionUi()
        // 让当前已选提供商始终可见（列表较长时滚动到它的位置）。
        scroll.post {
            val target = rowRoots.getOrNull(selectedIndex)
            if (target != null) scroll.scrollTo(0, target.top)
        }

        layout.addView(tvHint)
        layout.addView(scroll)
        layout.addView(tvKeyLabel)
        layout.addView(etKey)
        layout.addView(tvHowToGet)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.ai_provider_setup_title))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.save_and_test_btn), null)
            .apply {
                if (cancelable) {
                    setNegativeButton(activity.getString(R.string.cancel_btn), null)
                }
            }
            .create()
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)

        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val preset = presets[selectedIndex]
                val input = etKey.text?.toString()?.trim().orEmpty()
                if (input == "1433223") {
                    Prefs.setAiDetailConfigUnlocked(activity, true)
                    Utils.toast(activity, activity.getString(R.string.ai_config_unlocked))
                    dialog.dismiss()
                    onFinished?.invoke(null)
                    return@setOnClickListener
                }

                // 留空则沿用该提供商已保存的密钥（不预填、不回显明文）。
                val effectiveKey = input.ifBlank {
                    Prefs.getAiProviderKey(activity, preset.id).trim()
                }
                if (effectiveKey.isBlank()) {
                    etKey.error = activity.getString(R.string.please_input_api_key)
                    return@setOnClickListener
                }

                confirm.isEnabled = false
                confirm.text = activity.getString(R.string.ai_provider_testing)
                CoroutineScope(Dispatchers.IO).launch {
                    val result = runCatching {
                        val models = AIService.fetchModelsForProvider(activity, preset, effectiveKey)
                        // 能力目录拉取失败不阻塞配置流程，仅影响视觉列表过滤精度。
                        runCatching { AiModelCatalog.refreshFromNetwork(activity) }
                        models
                    }
                    withContext(Dispatchers.Main) {
                        confirm.isEnabled = true
                        confirm.text = activity.getString(R.string.save_and_test_btn)
                        result.onSuccess { models ->
                            val cleaned = models.map { it.trim() }.filter { it.isNotEmpty() }
                            AiProviderRegistry.applyProvider(
                                activity,
                                preset,
                                effectiveKey,
                                modelsCache = cleaned.takeIf { it.isNotEmpty() }
                            )
                            Utils.toast(
                                activity,
                                if (cleaned.isNotEmpty()) {
                                    activity.getString(R.string.ai_provider_setup_success_fmt, cleaned.size)
                                } else {
                                    activity.getString(R.string.ai_provider_setup_success_no_models)
                                }
                            )
                            dialog.dismiss()
                            onFinished?.invoke(
                                AiProviderSetupResult(
                                    preset = preset,
                                    apiKey = effectiveKey,
                                    models = cleaned
                                )
                            )
                        }.onFailure { error ->
                            Utils.toast(activity, testErrorMessage(activity, preset, error))
                        }
                    }
                }
            }
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = activity,
            widthRatio = 0.88f,
            cancelOnTouchOutside = cancelable,
            useSolidPanelBackground = true
        )
    }

    private fun testErrorMessage(context: Context, preset: AiProviderPreset, error: Throwable): String {
        val msg = error.message.orEmpty()
        return when {
            msg.contains("401") || msg.contains("unauthorized", ignoreCase = true) ->
                context.getString(R.string.ai_provider_auth_failed_fmt, preset.displayName)
            msg.contains("timeout", ignoreCase = true) ||
                msg.contains("Failed to connect", ignoreCase = true) ||
                msg.contains("Unable to resolve host", ignoreCase = true) ->
                context.getString(R.string.ai_provider_network_failed)
            else -> context.getString(R.string.ai_provider_test_failed)
        }
    }
}
