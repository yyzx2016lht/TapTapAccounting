package com.taostudio.tapaccounting.logic

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.taostudio.tapaccounting.R

/**
 * 共用金额键盘：[layout_amount_keypad] 上的按键接到一个 [EditText]，
 * 统一维护输入规则——两位小数上限、表达式求值、实时结果预览、按键震动、长按连续删除。
 *
 * 主表单（[AccountingFormController]）与金额编辑弹窗（[AmountKeypadDialog]）都使用本类，
 * 规则只维护一份。宿主负责“确定”键的行为（[confirmButton]）以及键盘容器的显隐。
 */
class AmountKeypad(
    private val container: View,
    private val editText: EditText,
    private val maxDecimals: () -> Int = { AmountInputEditor.DEFAULT_MAX_DECIMALS },
    private val hapticsEnabled: () -> Boolean = { true },
    private val previewView: TextView? = container.findViewById(R.id.tv_amount_preview)
) {

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    /** 键盘上的“确定”键，宿主自行设置点击行为。 */
    val confirmButton: View? = container.findViewById(R.id.btn_key_confirm)

    init {
        val digitIds = linkedMapOf(
            R.id.btn_key_0 to "0",
            R.id.btn_key_1 to "1",
            R.id.btn_key_2 to "2",
            R.id.btn_key_3 to "3",
            R.id.btn_key_4 to "4",
            R.id.btn_key_5 to "5",
            R.id.btn_key_6 to "6",
            R.id.btn_key_7 to "7",
            R.id.btn_key_8 to "8",
            R.id.btn_key_9 to "9",
            R.id.btn_key_dot to "."
        )
        digitIds.forEach { (id, value) ->
            container.findViewById<View>(id)?.setOnClickListener {
                tapFeedback()
                applyEdit(
                    AmountInputEditor.insert(
                        raw(), editText.selectionStart, editText.selectionEnd, value, maxDecimals()
                    )
                )
            }
        }

        val operatorIds = linkedMapOf(
            R.id.btn_key_add to "+",
            R.id.btn_key_subtract to "-",
            R.id.btn_key_multiply to "×",
            R.id.btn_key_divide to "÷"
        )
        operatorIds.forEach { (id, value) ->
            container.findViewById<View>(id)?.setOnClickListener {
                tapFeedback()
                applyEdit(
                    AmountInputEditor.insertOperator(
                        raw(), editText.selectionStart, editText.selectionEnd, value
                    )
                )
            }
        }

        container.findViewById<View>(R.id.btn_key_delete)?.apply {
            setOnClickListener {
                tapFeedback()
                applyEdit(AmountInputEditor.delete(raw(), editText.selectionStart, editText.selectionEnd))
            }
            setOnLongClickListener {
                tapFeedback()
                startRepeatDelete()
                true
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopRepeatDelete()
                }
                false
            }
        }

        container.findViewById<View>(R.id.btn_key_clear)?.setOnClickListener {
            tapFeedback()
            editText.setText("")
            editText.setSelection(0)
            refresh()
        }

        refresh()
    }

    fun raw(): String = editText.text?.toString().orEmpty()

    fun valueOrNull(): Double? = AmountExpression.evaluate(raw())

    fun isValid(): Boolean = valueOrNull() != null

    /** 求值后的两位小数文本（`12+3` -> `15.00`）；非法返回 null。 */
    fun formattedValueOrNull(): String? = valueOrNull()?.let(AmountExpression::format)

    /** 收起键盘前把表达式规整成结果（`12+3` -> `15`）；无运算符或非法时保持原样。 */
    fun normalize() {
        val evaluated = valueOrNull() ?: return
        val next = AmountExpression.compact(evaluated)
        if (raw() != next) {
            editText.setText(next)
            editText.setSelection(next.length)
        }
        refresh()
    }

    /** 开始编辑外部带入的已格式化值前调用（`12.00` -> `12`），光标移到末尾。 */
    fun normalizeForEditing() {
        val next = AmountExpression.normalizeForEditing(raw())
        if (raw() != next) {
            editText.setText(next)
        }
        editText.setSelection(editText.text?.length ?: 0)
        refresh()
    }

    /** 刷新确定键可用态与表达式预览。 */
    fun refresh() {
        val enabled = isValid()
        confirmButton?.isEnabled = enabled
        confirmButton?.alpha = if (enabled) 1f else 0.45f
        updatePreview()
    }

    /** 键盘容器隐藏时调用，停止长按连删等后台任务。 */
    fun close() {
        stopRepeatDelete()
    }

    private fun updatePreview() {
        val view = previewView ?: return
        val evaluated = valueOrNull()
        if (AmountExpression.containsOperator(raw()) && evaluated != null) {
            view.text = "= ${AmountExpression.format(evaluated)}"
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
        }
    }

    private fun applyEdit(edit: AmountInputEdit) {
        editText.setText(edit.text)
        editText.setSelection(edit.cursor.coerceIn(0, edit.text.length))
        refresh()
    }

    private fun tapFeedback() {
        if (hapticsEnabled()) {
            container.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun startRepeatDelete() {
        stopRepeatDelete()
        val runnable = object : Runnable {
            override fun run() {
                applyEdit(AmountInputEditor.delete(raw(), editText.selectionStart, editText.selectionEnd))
                repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        repeatHandler.postDelayed(runnable, REPEAT_INTERVAL_MS)
    }

    private fun stopRepeatDelete() {
        repeatRunnable?.let(repeatHandler::removeCallbacks)
        repeatRunnable = null
    }

    private companion object {
        const val REPEAT_INTERVAL_MS = 80L
    }
}
