package com.taostudio.tapaccounting

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val tvHint = TextView(activity).apply {
            text = activity.getString(R.string.ai_provider_setup_hint)
            textSize = 13f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(0, 0, 0, 12)
        }

        val spinner = Spinner(activity)
        val names = presets.map { it.displayName }
        spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, names)

        val currentProvider = initialProviderId?.trim().orEmpty().ifBlank { Prefs.getAiProvider(activity) }
        val selectedIndex = presets.indexOfFirst { it.id == currentProvider }.coerceAtLeast(0)
        spinner.setSelection(selectedIndex)
        val draftKeys = mutableMapOf<String, String>()
        var activeProviderIndex = selectedIndex

        fun keyForProvider(providerId: String): String {
            draftKeys[providerId]?.let { return it }
            val saved = Prefs.getAiProviderKey(activity, providerId)
            draftKeys[providerId] = saved
            return saved
        }

        val tvCapabilities = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#667085"))
            setPadding(0, 8, 0, 0)
        }

        val tvProviderNotice = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#B26A00"))
            setPadding(0, 8, 0, 8)
            visibility = View.GONE
        }

        val tvKeyLabel = TextView(activity).apply {
            text = activity.getString(R.string.api_key)
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 8, 0, 6)
        }

        val etKey = EditText(activity).apply {
            hint = presets[selectedIndex].keyHint
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setText(keyForProvider(presets[selectedIndex].id))
        }

        val tvHowToGet = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5C6BC0"))
            setPadding(0, 12, 0, 0)
            isClickable = true
            isFocusable = true
        }

        fun updateProviderUi(index: Int) {
            val preset = presets[index]
            etKey.hint = preset.keyHint
            tvCapabilities.text = AiProviderRegistry.capabilitySummary(preset)
            tvHowToGet.text = activity.getString(R.string.ai_provider_get_key_fmt, preset.displayName)
            val notice = preset.selectionNotice
            if (notice.isNullOrBlank()) {
                tvProviderNotice.visibility = View.GONE
            } else {
                tvProviderNotice.visibility = View.VISIBLE
                tvProviderNotice.text = notice
            }
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != activeProviderIndex) {
                    draftKeys[presets[activeProviderIndex].id] = etKey.text?.toString().orEmpty()
                    activeProviderIndex = position
                    etKey.setText(keyForProvider(presets[position].id))
                    etKey.setSelection(etKey.text?.length ?: 0)
                }
                updateProviderUi(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        tvHowToGet.setOnClickListener {
            val preset = presets[spinner.selectedItemPosition]
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preset.createKeyUrl)))
            }.onFailure {
                Utils.toast(activity, activity.getString(R.string.ai_provider_open_link_failed))
            }
        }
        updateProviderUi(selectedIndex)

        layout.addView(tvHint)
        layout.addView(spinner)
        layout.addView(tvCapabilities)
        layout.addView(tvProviderNotice)
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
                val preset = presets[spinner.selectedItemPosition]
                val input = etKey.text?.toString()?.trim().orEmpty()
                if (input.isBlank()) {
                    etKey.error = activity.getString(R.string.please_input_api_key)
                    return@setOnClickListener
                }
                if (input == "1433223") {
                    Prefs.setAiDetailConfigUnlocked(activity, true)
                    Utils.toast(activity, activity.getString(R.string.ai_config_unlocked))
                    dialog.dismiss()
                    onFinished?.invoke(null)
                    return@setOnClickListener
                }

                confirm.isEnabled = false
                confirm.text = activity.getString(R.string.ai_provider_testing)
                CoroutineScope(Dispatchers.IO).launch {
                    val result = runCatching {
                        val models = AIService.fetchModelsForProvider(activity, preset, input)
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
                                input,
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
                                    apiKey = input,
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
