package com.taostudio.tapaccounting.logic

import java.util.Locale
import java.util.Stack

/**
 * 金额表达式的求值与格式化。
 *
 * 主表单键盘与金额编辑弹窗（[com.taostudio.tapaccounting.ui.dialog.AmountKeypadDialog]）
 * 共用同一份规则，避免两处各写一遍后行为分叉（一处会把 `12+3` 求值成 15，另一处却静默变成 0）。
 *
 * 金额一律用 [Locale.US] 格式化：界面与键盘插入的都是字面小数点 `.`，
 * 不能跟着系统区域跑成 `12,00`（多币种场景尤其明显）。
 */
object AmountExpression {

    private val operators = charArrayOf('+', '-', '×', '÷')

    /** 把输入串当作表达式求值；非法（空、只有运算符、除零、多个小数点等）返回 null。 */
    fun evaluate(raw: String): Double? {
        val expr = raw.replace("×", "*").replace("÷", "/").replace("\\s+".toRegex(), "")
        if (expr.isEmpty()) return null
        val values = Stack<Double>()
        val ops = Stack<Char>()
        var i = 0
        while (i < expr.length) {
            val ch = expr[i]
            if (ch.isDigit() || ch == '.' ||
                (ch == '-' && (i == 0 || expr[i - 1] in charArrayOf('+', '-', '*', '/')))
            ) {
                val start = i
                i++
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                val token = expr.substring(start, i)
                values.push(token.toDoubleOrNull() ?: return null)
                continue
            }
            if (ch !in charArrayOf('+', '-', '*', '/')) return null
            while (ops.isNotEmpty() && precedence(ops.peek()) >= precedence(ch)) {
                applyTopOperator(values, ops) ?: return null
            }
            ops.push(ch)
            i++
        }
        while (ops.isNotEmpty()) {
            applyTopOperator(values, ops) ?: return null
        }
        return values.takeIf { it.size == 1 }?.peek()
    }

    /** 标准金额格式：固定两位小数，[Locale.US]（`12.5` -> `12.50`）。 */
    fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

    /** 紧凑金额：末位为零时去掉小数部分（`12.00` -> `12`，`12.50` -> `12.5`）。 */
    fun compact(value: Double): String = format(value).trimEnd('0').trimEnd('.')

    fun containsOperator(raw: String): Boolean = raw.any { it in operators }

    /**
     * 进入编辑态前把已格式化的值还原成可继续输入的形式：
     * `12.00` -> `12`、`12.50` -> `12.5`、`12.` -> `12`。
     *
     * 只处理纯数字（含运算符的表达式原样返回），保证“补尾数”时不会从
     * `12.00` 后面继续追加出一串多余小数。
     */
    fun normalizeForEditing(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        if (containsOperator(trimmed)) return trimmed
        if (!trimmed.matches(Regex("""\d*\.?\d*"""))) return trimmed
        if (!trimmed.contains('.')) return trimmed
        val withoutZeros = trimmed.trimEnd('0')
        val withoutDot = if (withoutZeros.endsWith('.')) withoutZeros.dropLast(1) else withoutZeros
        return withoutDot.ifEmpty { "0" }
    }

    private fun precedence(op: Char): Int = when (op) {
        '*', '/' -> 2
        '+', '-' -> 1
        else -> 0
    }

    private fun applyTopOperator(values: Stack<Double>, ops: Stack<Char>): Double? {
        if (values.size < 2 || ops.isEmpty()) return null
        val right = values.pop()
        val left = values.pop()
        val result = when (ops.pop()) {
            '+' -> left + right
            '-' -> left - right
            '*' -> left * right
            '/' -> if (right == 0.0) return null else left / right
            else -> return null
        }
        values.push(result)
        return result
    }
}
