package com.taostudio.tapaccounting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.logic.AmountExpression
import com.taostudio.tapaccounting.logic.AmountKeypad

class AmountKeypadDialog(
    context: Context,
    private val initialAmount: String = "",
    private val onConfirm: (String) -> Unit
) : Dialog(context, R.style.Theme_TapAccounting_Transparent) {

    private lateinit var etAmount: EditText
    private lateinit var keypad: AmountKeypad

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_amount_keypad)

        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(R.style.Animation_TapAccounting_BottomDialogSoft)
        }

        initViews()
        setupKeypad()
    }

    private fun initViews() {
        etAmount = findViewById(R.id.et_amount)
        etAmount.showSoftInputOnFocus = false
        findViewById<View>(R.id.layout_amount_keypad)?.visibility = View.VISIBLE

        // 外部带入的已格式化值（如 12.00）还原成可编辑形式，避免补尾数时被多余小数挡住
        etAmount.setText(AmountExpression.normalizeForEditing(initialAmount))
        etAmount.setSelection(etAmount.text?.length ?: 0)
    }

    private fun setupKeypad() {
        keypad = AmountKeypad(
            container = findViewById(R.id.layout_amount_keypad),
            editText = etAmount,
            hapticsEnabled = { Prefs.isVibrateFeedbackEnabled(context) },
            previewView = findViewById(R.id.tv_amount_preview)
        )
        keypad.confirmButton?.setOnClickListener {
            // 与主表单一致：先求值再回填，避免 12+3 这类表达式被静默当成 0
            val value = keypad.formattedValueOrNull() ?: return@setOnClickListener
            keypad.close()
            onConfirm(value)
            dismiss()
        }
    }

    override fun dismiss() {
        if (::keypad.isInitialized) keypad.close()
        super.dismiss()
    }

    companion object {
        fun show(
            context: Context,
            initialAmount: String = "",
            onConfirm: (String) -> Unit
        ): AmountKeypadDialog {
            val dialog = AmountKeypadDialog(context, initialAmount, onConfirm)
            dialog.show()
            return dialog
        }
    }
}
