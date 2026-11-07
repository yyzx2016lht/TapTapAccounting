package com.taostudio.tapaccounting.capture.parse

import com.taostudio.tapaccounting.capture.data.RuleBundleCodec
import org.json.JSONObject
import java.util.Locale

/**
 * 自定义数据规则表单 -> AutoAccounting 可执行的 QuickJS。
 *
 * 生成的规则保持现有规则文件的结构：`var rule_x = { ... }`，
 * 引擎加载后执行 `JSON.stringify(rule_x.get(data))`。
 * 字段值支持两种写法：
 * - `$1`：取正则第 1 个捕获组；
 * - 其他文本：作为固定值（可写固定金额、币种或账户名）。
 */
object RuleJsBuilder {

    const val BILL_EXPEND = "Expend"
    const val BILL_INCOME = "Income"
    const val BILL_TRANSFER = "Transfer"

    data class RuleForm(
        val app: String = "",
        val type: String = RuleBundleCodec.TYPE_DATA,
        val name: String = "",
        val enabled: Boolean = true,
        val pattern: String = "",
        val billType: String = BILL_EXPEND,
        val amountField: String = "$1",
        val merchantField: String = "",
        val itemField: String = "",
        val accountFromField: String = "",
        val accountToField: String = "",
        val feeField: String = "",
        val currencyField: String = "CNY",
        val timeField: String = "",
        val ignoreCase: Boolean = true,
    ) {
        fun toMeta(): JSONObject = JSONObject().apply {
            put("app", app)
            put("type", type)
            put("name", name)
            put("pattern", pattern)
            put("billType", billType)
            put("amountField", amountField)
            put("merchantField", merchantField)
            put("itemField", itemField)
            put("accountFromField", accountFromField)
            put("accountToField", accountToField)
            put("feeField", feeField)
            put("currencyField", currencyField)
            put("timeField", timeField)
            put("ignoreCase", ignoreCase)
        }

        companion object {
            /** 只有带 `pattern` 的 meta 才认为能安全回填表单；否则应走原始 JS 编辑。 */
            fun fromMeta(meta: JSONObject?): RuleForm? {
                if (meta == null) return null
                val pattern = meta.optString("pattern")
                if (pattern.isBlank()) return null
                return RuleForm(
                    app = meta.optString("app"),
                    type = normalizeType(meta.optString("type")),
                    name = meta.optString("name"),
                    enabled = true,
                    pattern = pattern,
                    billType = normalizeBillType(meta.optString("billType")),
                    amountField = meta.optString("amountField", "$1"),
                    merchantField = meta.optString("merchantField"),
                    itemField = meta.optString("itemField"),
                    accountFromField = meta.optString("accountFromField"),
                    accountToField = meta.optString("accountToField"),
                    feeField = meta.optString("feeField"),
                    currencyField = meta.optString("currencyField", "CNY"),
                    timeField = meta.optString("timeField"),
                    ignoreCase = meta.optBoolean("ignoreCase", true),
                )
            }
        }
    }

    fun normalizeType(raw: String): String =
        if (raw.trim().uppercase(Locale.US) == RuleBundleCodec.TYPE_NOTICE) {
            RuleBundleCodec.TYPE_NOTICE
        } else {
            RuleBundleCodec.TYPE_DATA
        }

    fun normalizeBillType(raw: String): String = when (raw.trim()) {
        BILL_INCOME -> BILL_INCOME
        BILL_TRANSFER -> BILL_TRANSFER
        else -> BILL_EXPEND
    }

    fun billTypeLabel(raw: String): String = when (normalizeBillType(raw)) {
        BILL_INCOME -> "收入"
        BILL_TRANSFER -> "转账"
        else -> "支出"
    }

    fun newRuleName(timestamp: Long = System.currentTimeMillis()): String = "rule_user_$timestamp"

    /**
     * “复制系统规则为我的规则”时，必须把 JS 里的变量声明改成新的唯一变量名，
     * 否则引擎执行 `rule_user_x.get(data)` 会找不到变量。
     */
    fun renameRuleVariable(js: String, oldName: String, newName: String): String {
        if (oldName == newName || oldName.isBlank()) return js
        val nameChars = "A-Za-z0-9_\\$"
        return js.replace(
            Regex("(?<![$nameChars])${Regex.escape(oldName)}(?![$nameChars])"),
            newName,
        )
    }

    fun buildJs(form: RuleForm, systemRuleName: String): String {
        require(RuleBundleCodec.Rule.isValidRuleName(systemRuleName)) {
            "规则变量名非法：$systemRuleName"
        }
        val pattern = JSONObject.quote(form.pattern)
        val flags = JSONObject.quote(if (form.ignoreCase) "i" else "")
        val billType = normalizeBillType(form.billType)
        val channel = JSONObject.quote(form.name.ifBlank { "我的规则" })
        return buildString {
            append("var ").append(systemRuleName).append(" = {\n")
            append("  get: function (data) {\n")
            append("    var m = new RegExp(").append(pattern).append(", ").append(flags).append(").exec(String(data));\n")
            append("    if (!m) return null;\n")
            append("    function pick(v) {\n")
            append("      if (v === undefined || v === null) return \"\";\n")
            append("      var s = String(v);\n")
            append("      var r = /^\\$(\\d+)$/.exec(s);\n")
            append("      if (!r) return s;\n")
            append("      var hit = m[parseInt(r[1], 10)];\n")
            append("      return hit === undefined || hit === null ? \"\" : String(hit);\n")
            append("    }\n")
            append("    function num(v) {\n")
            append("      var s = String(pick(v)).replace(/,/g, \"\").trim();\n")
            append("      if (s === \"\") return 0;\n")
            append("      var n = parseFloat(s);\n")
            append("      return isNaN(n) ? 0 : n;\n")
            append("    }\n")
            append("    var rawTime = pick(").append(JSONObject.quote(form.timeField)).append(");\n")
            append("    var timeValue = 0;\n")
            append("    if (rawTime !== \"\") {\n")
            append("      if (/^\\d+(\\.\\d+)?$/.test(rawTime)) {\n")
            append("        timeValue = parseFloat(rawTime);\n")
            append("      } else {\n")
            append("        var parsed = Date.parse(rawTime);\n")
            append("        if (!isNaN(parsed)) timeValue = parsed;\n")
            append("      }\n")
            append("    }\n")
            append("    return {\n")
            append("      type: ").append(JSONObject.quote(billType)).append(",\n")
            append("      money: num(").append(JSONObject.quote(form.amountField)).append("),\n")
            append("      shopName: String(pick(").append(JSONObject.quote(form.merchantField)).append(")),\n")
            append("      shopItem: String(pick(").append(JSONObject.quote(form.itemField)).append(")),\n")
            append("      accountNameFrom: String(pick(").append(JSONObject.quote(form.accountFromField)).append(")),\n")
            append("      accountNameTo: String(pick(").append(JSONObject.quote(form.accountToField)).append(")),\n")
            append("      fee: num(").append(JSONObject.quote(form.feeField)).append("),\n")
            append("      currency: String(pick(").append(JSONObject.quote(form.currencyField)).append(")) || \"CNY\",\n")
            append("      time: timeValue,\n")
            append("      channel: ").append(channel).append("\n")
            append("    };\n")
            append("  }\n")
            append("};\n")
        }
    }
}
