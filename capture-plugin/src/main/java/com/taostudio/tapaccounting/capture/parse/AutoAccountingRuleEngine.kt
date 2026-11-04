package com.taostudio.tapaccounting.capture.parse

import android.content.Context
import android.util.Log
import com.shiqi.quickjs.JSContext
import com.shiqi.quickjs.QuickJS
import com.taostudio.tapaccounting.capture.contract.BillTypeLocalizer
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.contract.LocalBillTypeMapping
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 原样执行 AutoAccounting 云端规则（QuickJS）。
 *
 * 规则在插件进程里跑；hook 进程只投递原始 JSON，不加载本类。
 * 规则内容来自 `assets/autoaccounting_rules.json`（GPL/第三方云端内容，仅个人使用）。
 *
 * 设计取舍：
 * - 每次 parse() 建一个 JSRuntime/JSContext，同一个 context 里依次跑候选规则；
 *   不逐条重建 context，避免 85 条规则时把开销放大 85 倍。
 * - 单条规则任何异常都只记日志并跳过，绝不影响整条链路。
 * - 阶段一（组合 B）返回 [ParseOutcome]：命中规则就带回 AutoAccounting 类型、
 *   本地类型/子类型和退款关联标记；本地类型无法判断时 `localType=null`（待分类），
 *   不再像 P0 那样对非支出直接返回 null。规则未命中用 [ParseOutcome.NoMatch] 区分。
 */
object AutoAccountingRuleEngine {

    /** 写入 CapturePayload.ruleVersion 的标记。 */
    const val RULE_VERSION: String = "autoaccounting-1"

    private const val TAG = "TapCaptureRule"
    private const val ASSET_NAME = "autoaccounting_rules.json"
    private const val CATEGORY_ASSET_NAME = "autoaccounting_category.js"
    private const val SECONDS_THRESHOLD = 10_000_000_000L
    private const val DEFAULT_CURRENCY = "CNY"
    private const val MAX_FAILURE_LOGS = 3
    private val SYSTEM_RULE_NAME = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

    private const val PACKAGE_WECHAT = "com.tencent.mm"
    private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"

    /** 规则 JS 里可能调到 console.log（错误分支），给它一个空实现。 */
    private const val CONSOLE_SHIM =
        "var console={log:function(){},warn:function(){},error:function(){},info:function(){}};"

    /** 规则执行结果：区分“没命中”和“命中但本地类型待确认”。 */
    sealed interface ParseOutcome {
        /**
         * 规则命中并得到了可用的金额/时间等字段。
         * [ParsedRule.localType] / [ParsedRule.localSubType] 为 null 表示本地类型无法判断，
         * 导入方应把它当“待分类”，不要按金额/商户猜类型。
         */
        data class Matched(val rule: ParsedRule) : ParseOutcome

        /** 没有启用规则返回可解析结果（规则未命中/规则失败/来源不支持）。 */
        data object NoMatch : ParseOutcome
    }

    data class ParsedRule(
        val ruleName: String,
        /** AutoAccounting 原始类型：Expend / Income / Transfer。 */
        val type: String,
        /** 本地主类型：0 支出 / 1 收入 / 2 转账；无法判断为 null（待分类）。 */
        val localType: Int?,
        /** 本地子类型：0 普通 / 1 还款 / 2 退款；无法判断为 null。 */
        val localSubType: Int?,
        /** 退款需要关联原支出账单。 */
        val needsRefundLink: Boolean,
        val amountCents: Long,
        val paidAt: Long,
        val merchant: String?,
        val shopItem: String?,
        val accountNameFrom: String?,
        val accountNameTo: String?,
        val feeCents: Long,
        val currency: String,
        val channel: String?,
        /** AutoAccounting category.js 推断的分类名；无法推断为 null。 */
        val categoryName: String? = null,
    )

    private data class RuleDef(
        val name: String,
        val systemRuleName: String,
        /** rule.js + `JSON.stringify(rule_x.get(data))`，加载时拼一次。 */
        val code: String,
    )

    private data class RuleSet(
        val common: String,
        /** AutoAccounting category.js；为空表示不做分类推断。 */
        val category: String,
        /** key = "$app|$type"（type 已是 DATA/NOTICE）。 */
        val byKey: Map<String, List<RuleDef>>,
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var ruleSet: RuleSet? = null

    /** QuickJS 实例本身无状态；runtime/context 每次 parse 新建。 */
    private val quickJs: QuickJS by lazy {
        QuickJS.init()
        QuickJS.Builder().build()
    }

    /** 在 CaptureApp.onCreate / Provider 里调用；只接受 application context。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * @param source WECHAT / ALIPAY（也接受包名）
     * @param dataType DATA / NOTICE
     * @param rawJson hook 投递的原始 JSON 字符串
     * @return 命中结果或 [ParseOutcome.NoMatch]；非支出不再返回 null，
     *   而是带回 AutoAccounting 类型和本地类型（无法判断时 localType=null）。
     */
    @Synchronized
    fun parse(source: String, dataType: String, rawJson: String): ParseOutcome {
        if (rawJson.isBlank()) return ParseOutcome.NoMatch
        val pkg = packageForSource(source) ?: run {
            log("unknown source: $source")
            return ParseOutcome.NoMatch
        }
        val type = dataType.trim().uppercase(Locale.US).ifEmpty { "DATA" }
        val rules = ruleSetOrNull() ?: return ParseOutcome.NoMatch
        val candidates = rules.byKey["$pkg|$type"].orEmpty()
        if (candidates.isEmpty()) {
            log("no rules pkg=$pkg type=$type")
            return ParseOutcome.NoMatch
        }

        val startedAt = System.currentTimeMillis()
        var executed = 0
        var failures = 0
        var failureLogs = 0

        fun logFailure(message: String) {
            failureLogs++
            if (failureLogs <= MAX_FAILURE_LOGS) log(message)
        }

        return try {
            quickJs.createJSRuntime().use { runtime ->
                runtime.createJSContext().use { ctx ->
                    installGlobals(ctx, rawJson)
                    try {
                        ctx.evaluate(rules.common, "common.js")
                        ctx.evaluate(CONSOLE_SHIM, "console.js")
                    } catch (t: Throwable) {
                        log("common.js failed: ${describe(t)}")
                        return ParseOutcome.NoMatch
                    }
                    if (rules.category.isNotBlank()) {
                        try {
                            ctx.evaluate(rules.category, "category.js")
                        } catch (t: Throwable) {
                            // 分类是增强项，失败不影响金额/类型。
                            log("category.js failed: ${describe(t)}")
                        }
                    }

                    for (rule in candidates) {
                        executed++
                        val output = try {
                            ctx.evaluate(rule.code, "rule.js", String::class.java)
                        } catch (t: ClassCastException) {
                            // JSON.stringify(undefined) 得到 undefined，String adapter 无法转换；
                            // 语义上等同“该规则没命中”，不当作故障。
                            continue
                        } catch (t: Throwable) {
                            failures++
                            logFailure("rule failed id=${rule.systemRuleName} name=${rule.name}: ${describe(t)}")
                            continue
                        }

                        if (output.isNullOrBlank() || output == "null" || output == "undefined") {
                            continue
                        }

                        val obj = try {
                            JSONObject(output)
                        } catch (t: Throwable) {
                            failures++
                            logFailure("rule output not json name=${rule.name}: ${output.take(200)}")
                            continue
                        }

                        val billType = obj.optString("type")
                        if (billType.isEmpty()) {
                            failures++
                            logFailure("rule output missing type name=${rule.name}: ${output.take(200)}")
                            continue
                        }
                        // 本地化：AA 只有 3 种类型，还款/退款/收入关键词要额外识别；
                        // local 为 null 只表示“待分类”，不影响金额/商户等字段入库。
                        var local = BillTypeLocalizer.localize(billType, rule.name)
                        // 微信“转账退款”和普通转账共用规则名“微信转账”，
                        // 但规则生成的 channel 会带“退款”；以 channel 纠正为退款子类型。
                        val channel = obj.optString("channel", "").trim()
                        if (local?.type == LocalBillType.TYPE_INCOME &&
                            local.subType != LocalBillType.SUBTYPE_REFUND &&
                            channel.contains("退款")
                        ) {
                            local = LocalBillTypeMapping(
                                type = LocalBillType.TYPE_INCOME,
                                subType = LocalBillType.SUBTYPE_REFUND,
                                needsRefundLink = true,
                            )
                        }

                        val money = obj.optDouble("money", Double.NaN)
                        if (money.isNaN() || money <= 0.0) {
                            log("rule ${rule.name} matched but money invalid: ${obj.opt("money")} type=$billType")
                            continue
                        }

                        val parsed = buildParsed(rule.name, billType, obj, money, local)
                        if (parsed == null) {
                            log("rule ${rule.name} matched but amount rounded to 0")
                            continue
                        }
                        val enriched = parsed.copy(
                            categoryName = parseCategory(ctx, parsed, rules.category, type),
                        )
                        if (local == null) {
                            log(
                                "matched-but-unclassified rule=${rule.name} type=$billType " +
                                    "money=${enriched.amountCents} merchant=${enriched.merchant ?: "-"} " +
                                    "category=${enriched.categoryName ?: "-"} " +
                                    "channel=${enriched.channel ?: "-"}",
                            )
                        } else {
                            log(
                                "matched rule=${rule.name} type=$billType money=${enriched.amountCents} " +
                                    "local=${enriched.localType}/${enriched.localSubType} " +
                                    "refundLink=${enriched.needsRefundLink} " +
                                    "category=${enriched.categoryName ?: "-"} " +
                                    "merchant=${enriched.merchant ?: "-"} channel=${enriched.channel ?: "-"}",
                            )
                        }
                        return ParseOutcome.Matched(enriched)
                    }
                    ParseOutcome.NoMatch
                }
            }
        } catch (t: Throwable) {
            log("engine failed: ${describe(t)}")
            ParseOutcome.NoMatch
        } finally {
            log(
                "parse done pkg=$pkg type=$type rules=${candidates.size} " +
                    "executed=$executed failures=$failures cost=${System.currentTimeMillis() - startedAt}ms",
            )
        }
    }

    // ---------------- 内部 ----------------

    private fun installGlobals(ctx: JSContext, rawJson: String) {
        val global = ctx.globalObject
        global.setProperty("print", ctx.createJSFunction { _, _ -> ctx.createJSUndefined() })
        global.setProperty("data", ctx.createJSString(rawJson))
    }

    /**
     * 调 AutoAccounting category.js。
     *
     * 它使用 `a_r_channel` 组合文本做关键词匹配，并按小时判断“早餐/午餐/晚餐”。
     * 分类失败只返回 null，不影响账单本身。
     */
    private fun parseCategory(
        ctx: JSContext,
        parsed: ParsedRule,
        categoryCode: String,
        dataType: String,
    ): String? {
        if (categoryCode.isBlank()) return null
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(parsed.paidAt))
        val money = java.math.BigDecimal.valueOf(parsed.amountCents, 2).toPlainString()
        val prefix = when (dataType) {
            "NOTICE" -> "通知"
            "OCR" -> "OCR"
            else -> "数据"
        }
        // 与 AutoAccounting BillService.categorize 对齐：
        // category.get(money, type, sanitizedShopName, sanitizedShopItem, HH:mm, 规则名+渠道)
        val ruleAndChannel = buildString {
            append(prefix).append('·').append(parsed.ruleName)
            parsed.channel?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
        }
        val expression = "JSON.stringify(category.get(" +
            "$money, " +
            "${JSONObject.quote(parsed.type)}, " +
            "${JSONObject.quote(sanitizeCategoryMeta(parsed.merchant.orEmpty()))}, " +
            "${JSONObject.quote(sanitizeCategoryMeta(parsed.shopItem.orEmpty()))}, " +
            "${JSONObject.quote(time)}, " +
            "${JSONObject.quote(ruleAndChannel)}))"
        return try {
            val raw = ctx.evaluate(expression, "category-call.js", String::class.java)
            if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return null
            val cat = JSONObject(raw).optString("category", "")
                .trim()
                .takeIf { it.isNotEmpty() }
            if ((parsed.needsRefundLink || parsed.localSubType == LocalBillType.SUBTYPE_REFUND) &&
                (cat == null || cat == "收红包" || cat == "其他")
            ) {
                "退款"
            } else {
                cat
            }
        } catch (t: Throwable) {
            log("category call failed: ${describe(t)}")
            null
        }
    }

    /** 与 AutoAccounting 的 sanitizeCategoryMetaForRule 保持一致。 */
    private fun sanitizeCategoryMeta(input: String): String {
        if (input.isEmpty()) return input
        return input
            .replace(Regex("[¥\\$€£￥]\\s*\\d+(?:\\.\\d+)?"), "")
            .replace(Regex("\\d+(?:\\.\\d+)?\\s*[元円块]"), "")
            .replace(Regex("[A-Za-z]{6,}"), "")
            .replace(Regex("[\\s\\-_/]+"), " ")
            .trim()
    }

    private fun buildParsed(
        ruleName: String,
        autoType: String,
        obj: JSONObject,
        money: Double,
        local: LocalBillTypeMapping?,
    ): ParsedRule? {
        val cents = (money * 100.0).roundToLong()
        if (cents <= 0L) return null

        val rawTime = obj.optLong("time", 0L)
        val paidAt = when {
            rawTime <= 0L -> System.currentTimeMillis()
            rawTime < SECONDS_THRESHOLD -> rawTime * 1000L
            else -> rawTime
        }
        val feeCents = (obj.optDouble("fee", 0.0) * 100.0).roundToLong()

        val rawShopName = obj.stringOrNull("shopName")
        val cleanedShopName = rawShopName?.let { raw ->
            Regex("""^待(.+?)确认收款$""").matchEntire(raw)?.groupValues?.getOrNull(1)?.trim()
                ?: raw
        }

        return ParsedRule(
            ruleName = ruleName,
            type = autoType,
            localType = local?.type,
            localSubType = local?.subType,
            needsRefundLink = local?.needsRefundLink ?: false,
            amountCents = cents,
            paidAt = paidAt,
            merchant = cleanedShopName,
            shopItem = obj.stringOrNull("shopItem"),
            accountNameFrom = obj.stringOrNull("accountNameFrom"),
            accountNameTo = obj.stringOrNull("accountNameTo"),
            feeCents = feeCents,
            currency = obj.stringOrNull("currency") ?: DEFAULT_CURRENCY,
            channel = obj.stringOrNull("channel"),
        )
    }

    private fun ruleSetOrNull(): RuleSet? {
        ruleSet?.let { return it }
        return try {
            loadRuleSet().also { ruleSet = it }
        } catch (t: Throwable) {
            log("load rules failed: ${describe(t)}")
            null
        }
    }

    private fun loadRuleSet(): RuleSet {
        val context = appContext ?: throw IllegalStateException("AutoAccountingRuleEngine.init not called")
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val common = root.optString("common")
        require(common.isNotBlank()) { "rules asset missing common.js" }
        val category = runCatching {
            context.assets.open(CATEGORY_ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrElse {
            log("category asset missing: ${describe(it)}")
            ""
        }

        val rules = root.optJSONArray("rules") ?: JSONArray()
        val byKey = LinkedHashMap<String, MutableList<RuleDef>>()
        var skipped = 0
        for (index in 0 until rules.length()) {
            val item = rules.optJSONObject(index) ?: continue
            if (!item.optBoolean("enabled", false)) continue
            val app = item.optString("app")
            val type = item.optString("type").uppercase(Locale.US)
            val js = item.optString("js")
            val systemRuleName = item.optString("systemRuleName")
            val name = item.optString("name")
            if (app.isBlank() || type.isBlank() || js.isBlank() ||
                !SYSTEM_RULE_NAME.matches(systemRuleName)
            ) {
                skipped++
                continue
            }
            val code = js + "\nJSON.stringify(" + systemRuleName + ".get(data))"
            byKey.getOrPut("$app|$type") { ArrayList() }
                .add(RuleDef(name = name, systemRuleName = systemRuleName, code = code))
        }
        log("rules loaded total=${rules.length()} skipped=$skipped category=${category.isNotBlank()}")
        return RuleSet(common = common, category = category, byKey = byKey)
    }

    private fun packageForSource(source: String): String? {
        val raw = source.trim()
        return when (raw.uppercase(Locale.US)) {
            CaptureSource.WECHAT -> PACKAGE_WECHAT
            CaptureSource.ALIPAY -> PACKAGE_ALIPAY
            // 通知/短信 hook 直接使用真实包名（cmb.pb、com.android.phone 等），
            // 规则资产的 app 字段是小写包名，这里统一小写。
            else -> raw.lowercase(Locale.US).takeIf { it.contains('.') }
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").trim().takeIf { it.isNotEmpty() }
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message}".take(300)

    private fun log(message: String) {
        Log.i(TAG, message)
    }
}
