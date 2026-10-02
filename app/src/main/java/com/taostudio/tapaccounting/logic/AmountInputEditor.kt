package com.taostudio.tapaccounting.logic

data class AmountInputEdit(
    val text: String,
    val cursor: Int
)

object AmountInputEditor {
    private val operators = charArrayOf('+', '-', '×', '÷')

    /** 默认最多两位小数（分）。 */
    const val DEFAULT_MAX_DECIMALS = 2

    fun insert(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        token: String,
        maxDecimals: Int = DEFAULT_MAX_DECIMALS
    ): AmountInputEdit {
        val selection = selection(text, selectionStart, selectionEnd)
        val base = text.removeRange(selection.first, selection.last)
        val cursor = selection.first

        val segmentStart = base.substring(0, cursor).indexOfLast { it in operators } + 1
        val nextOperator = base.indexOfAny(operators, startIndex = cursor)
        val segmentEnd = nextOperator.takeIf { it >= 0 } ?: base.length
        val segment = base.substring(segmentStart, segmentEnd)

        if (token == ".") {
            if (segment.contains('.')) {
                return AmountInputEdit(text, selection.first)
            }
            val inserted = if (cursor == segmentStart) "0." else "."
            return insertAt(base, cursor, inserted)
        }

        // 小数位上限：光标落在小数点之后时，超出位数直接忽略（整数位不设限）。
        if (maxDecimals > 0 && token.length == 1 && token[0].isDigit()) {
            val dotIndex = segment.indexOf('.')
            if (dotIndex >= 0) {
                val cursorOffset = cursor - segmentStart
                val fractionLength = segment.length - dotIndex - 1
                if (cursorOffset > dotIndex && fractionLength >= maxDecimals) {
                    return AmountInputEdit(text, selection.first)
                }
            }
        }

        if (cursor == segmentEnd && segment == "0") {
            val next = base.replaceRange(segmentStart, segmentEnd, token)
            return AmountInputEdit(next, segmentStart + token.length)
        }

        return insertAt(base, cursor, token)
    }

    fun insertOperator(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        operator: String
    ): AmountInputEdit {
        val selection = selection(text, selectionStart, selectionEnd)
        val base = text.removeRange(selection.first, selection.last)
        val cursor = selection.first
        if (operator.length != 1 || operator[0] !in operators || base.isBlank() || cursor == 0) {
            return AmountInputEdit(text, selection.first)
        }

        if (base[cursor - 1] in operators) {
            return AmountInputEdit(base.replaceRange(cursor - 1, cursor, operator), cursor)
        }
        if (cursor < base.length && base[cursor] in operators) {
            return AmountInputEdit(base.replaceRange(cursor, cursor + 1, operator), cursor + 1)
        }
        return insertAt(base, cursor, operator)
    }

    fun delete(
        text: String,
        selectionStart: Int,
        selectionEnd: Int
    ): AmountInputEdit {
        val selection = selection(text, selectionStart, selectionEnd)
        if (selection.first != selection.last) {
            return AmountInputEdit(text.removeRange(selection.first, selection.last), selection.first)
        }
        if (selection.first == 0) return AmountInputEdit(text, 0)

        val cursor = selection.first
        return AmountInputEdit(text.removeRange(cursor - 1, cursor), cursor - 1)
    }

    private fun insertAt(text: String, cursor: Int, token: String): AmountInputEdit {
        return AmountInputEdit(text.replaceRange(cursor, cursor, token), cursor + token.length)
    }

    private fun selection(text: String, start: Int, end: Int): IntRange {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(0, text.length)
        return minOf(safeStart, safeEnd)..maxOf(safeStart, safeEnd)
    }
}
