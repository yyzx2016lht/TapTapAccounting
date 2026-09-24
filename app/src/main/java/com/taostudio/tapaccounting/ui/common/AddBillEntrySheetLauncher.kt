package com.taostudio.tapaccounting.ui.common

import android.content.Intent
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import com.taostudio.tapaccounting.AiAssistant
import com.taostudio.tapaccounting.ImagePickerActivity
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.logic.AccountingFormController
import com.taostudio.tapaccounting.logic.VoiceInputHandler

object AddBillEntrySheetLauncher {

    fun show(
        activity: AppCompatActivity,
        prefillData: JSONObject? = null,
        onShow: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.layout_floating_window, null)

        val aiAssistant = AiAssistant(activity)

        val formController = AccountingFormController(
            ctx = activity,
            rootView = view,
            onCloseRequest = { _ -> bottomSheet.dismiss() }
        )
        prefillData?.let { formController.fillDataToUi(it, showToast = false) }

        val handleAiResult: (JSONObject) -> Unit = { resultJson ->
            if (resultJson.has("bills")) {
                formController.fillDataToUi(resultJson, showToast = true, forceMultiMode = true)
                val firstBill = resultJson.getJSONArray("bills").optJSONObject(0)
                if (firstBill != null) formController.setCurrency(firstBill.optString("currency", "CNY"))
            } else {
                formController.fillDataToUi(resultJson, showToast = true)
                formController.setCurrency(resultJson.optString("currency", "CNY"))
            }
        }

        val voiceHandler = VoiceInputHandler(activity, aiAssistant, handleAiResult)
        aiAssistant.voiceInputBtnSetup = { btn ->
            voiceHandler.setupVoiceButton(btn)
        }
        voiceHandler.setupVoiceButton(formController.btnVoice)

        val btnAiImage = view.findViewById<ImageView?>(R.id.btn_ai_image)
        if (Prefs.isShowAiImage(activity)) {
            btnAiImage?.visibility = android.view.View.VISIBLE
            btnAiImage?.setOnClickListener {
                ImagePickerActivity.onImagesPicked = { uris ->
                    ImagePickerActivity.onImagesPicked = null
                    ImagePickerActivity.onPickCancelled = null
                    aiAssistant.analyzeImages(uris, handleAiResult)
                }
                ImagePickerActivity.onPickCancelled = {
                    ImagePickerActivity.onImagesPicked = null
                    ImagePickerActivity.onPickCancelled = null
                }
                activity.startActivity(Intent(activity, ImagePickerActivity::class.java))
            }
        } else {
            btnAiImage?.visibility = android.view.View.GONE
        }

        formController.layoutAiTextEntry.setOnClickListener {
            aiAssistant.showInputPanel(hideStreamText = true, onResult = handleAiResult)
        }

        bottomSheet.setOnShowListener { onShow?.invoke() }
        bottomSheet.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                formController.handleBackPressed()
            } else {
                false
            }
        }
        bottomSheet.setOnDismissListener {
            voiceHandler.release()
            formController.destroy()
            aiAssistant.shutdown()
            onDismiss?.invoke()
        }
        bottomSheet.setContentView(view)
        bottomSheet.show()
    }
}


