package com.taostudio.tapaccounting.capture.parse

import android.content.Context
import android.util.Log
import com.shiqi.quickjs.JSContext
import com.shiqi.quickjs.QuickJS
import com.taostudio.tapaccounting.capture.contract.BillTypeLocalizer
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.contract.LocalBillTypeMapping
import com.taostudio.tapaccounting.capture.data.RuleBundleCodec
import com.taostudio.tapaccounting.capture.data.RuleBundleStore
import org.json.JSONObject
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
    )

    private data class RuleDef(
        val name: String,
        val systemRuleName: String,
        /** rule.js + `JSON.stringify(rule_x.get(data))`，加载时拼一次。 */
        val code: String,
    )

    private data class RuleSet(
        val common: String,
        /** key = "$app|$type"（type 已是 DATA/NOTICE）。 */
        val byKey: Map<String, List<RuleDef>>,
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var ruleSet: RuleSet? = null

    /** 规则写入/同步/启停时递增，防止预热线程把旧规则重新写回缓存。 */
    @Volatile
    private var generation = 0

    /** QuickJS 实例本身无状态；runtime/context 每次 parse 新建。 */
    private val quickJs: QuickJS by lazy {
        QuickJS.init()
        QuickJS.Builder().build()
    }

    /** 在 TapApplication.onCreate / Provider 里调用；只接受 application context。 */
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        val firstInit = appContext == null
        appContext = app
        if (firstInit) {
            Thread({
                try {
                    quickJs
                    ruleSetOrNull()
                } catch (_: Throwable) {
                }
            }, "TapCaptureRuleWarmup").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * 规则 bundle 写入、本机同步、导入、启停后调用。
     * 下一次 parse 会从 `filesDir/rule_bundle.json` 重新加载，无需重启 App。
     */
    fun invalidate() {
        generation++
        ruleSet = null
        log("rule set invalidated")
    }

    /** 规则页“样本测试”的结果；[fields] 已按中文标签排好，UI 直接展示。 */
    data class RuleTestResult(
        val matched: Boolean,
        val output: String? = null,
        val fields: List<Pair<String, String>> = emptyList(),
        val error: String? = null,
    )

    /**
     * 用 QuickJS 单独跑一条规则，不写入任何状态；供规则页样本测试使用。
     * 与 [parse] 共用 common.js、console shim 与 data 全局变量，命中字段含义完全一致。
     */
    @Synchronized
    fun testRule(
        common: String,
        js: String,
        systemRuleName: String,
        rawJson: String,
    ): RuleTestResult {
        if (common.isBlank()) return RuleTestResult(false, error = "缺少 common 规则脚本")
        if (js.isBlank()) return RuleTestResult(false, error = "规则 JS 为空")
        if (!SYSTEM_RULE_NAME.matches(systemRuleName)) {
            return RuleTestResult(false, error = "规则变量名非法：$systemRuleName")
        }
        return try {
            quickJs.createJSRuntime().use { runtime ->
                runtime.createJSContext().use { ctx ->
                    installGlobals(ctx, rawJson)
                    try {
                        ctx.evaluate(common, "common.js")
                        ctx.evaluate(CONSOLE_SHIM, "console.js")
                    } catch (t: Throwable) {
                        return RuleTestResult(false, error = "common.js 执行失败：${describe(t)}")
                    }
                    val output = try {
                        ctx.evaluate(
                            js + "\nJSON.stringify(" + systemRuleName + ".get(data))",
                            "rule_test.js",
                            String::class.java,
                        )
                    } catch (_: ClassCastException) {
                        null
                    } catch (t: Throwable) {
                        return RuleTestResult(false, error = "规则执行失败：${describe(t)}")
                    }
                    if (output.isNullOrBlank() || output == "null" || output == "undefined") {
                        return RuleTestResult(false)
                    }
                    val obj = try {
                        JSONObject(output)
                    } catch (t: Throwable) {
                        return RuleTestResult(
                            false,
                            output = output,
                            error = "规则输出不是 JSON 对象",
                        )
                    }
                    RuleTestResult(
                        matched = true,
                        output = obj.toString(),
                        fields = describeFields(obj),
                    )
                }
            }
        } catch (t: Throwable) {
            RuleTestResult(false, error = "测试失败：${describe(t)}")
        }
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
        // 包名统一小写：资产里的支付宝包名是 com.eg.android.AlipayGphone，而用户/同步规则
        // 可能大小写不统一；key 两侧必须同一口径，否则会漏掉全部支付宝规则。
        val pkg = packageForSource(source)?.lowercase(Locale.US) ?: run {
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
                        if (local == null) {
                            log(
                                "matched-but-unclassified rule=${rule.name} type=$billType " +
                                    "money=${parsed.amountCents} merchant=${parsed.merchant ?: "-"} " +
                                    "channel=${parsed.channel ?: "-"}",
                            )
                        } else {
                            log(
                                "matched rule=${rule.name} type=$billType money=${parsed.amountCents} " +
                                    "local=${parsed.localType}/${parsed.localSubType} " +
                                    "refundLink=${parsed.needsRefundLink} " +
                                    "merchant=${parsed.merchant ?: "-"} channel=${parsed.channel ?: "-"}",
                            )
                        }
                        return ParseOutcome.Matched(parsed)
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
            val startGeneration = generation
            val loaded = loadRuleSet()
            // 加载期间用户可能刚保存了规则：旧结果不能覆盖 invalidate 后的空缓存。
            synchronized(this) {
                if (generation == startGeneration) ruleSet = loaded
            }
            loaded
        } catch (t: Throwable) {
            log("load rules failed: ${describe(t)}")
            null
        }
    }

    private fun loadRuleSet(): RuleSet {
        val context = appContext ?: throw IllegalStateException("AutoAccountingRuleEngine.init not called")
        // bundle 存在时以 bundle 为准；不存在/损坏时 RuleBundleStore 回退内置资产。
        val bundle = RuleBundleStore.load(context)
        val common = bundle.common
        if (common.isBlank()) {
            log("rules bundle empty; local rules only")
            return RuleSet(common = "", byKey = emptyMap())
        }

        val byKey = LinkedHashMap<String, MutableList<RuleDef>>()
        var skipped = 0
        var userRules = 0
        // 用户规则优先执行：先匹配“我的”，再匹配系统/导入规则。
        for (rule in RuleBundleCodec.orderedForEngine(bundle.rules)) {
            if (!rule.enabled) continue
            val app = rule.app.trim().lowercase(Locale.US)
            val type = rule.type.trim().uppercase(Locale.US)
            val js = rule.js
            val systemRuleName = rule.systemRuleName
            if (app.isBlank() || type.isBlank() || js.isBlank() ||
                !SYSTEM_RULE_NAME.matches(systemRuleName)
            ) {
                skipped++
                continue
            }
            if (rule.isUser) userRules++
            val code = js + "\nJSON.stringify(" + systemRuleName + ".get(data))"
            byKey.getOrPut("$app|$type") { ArrayList() }
                .add(RuleDef(name = rule.name, systemRuleName = systemRuleName, code = code))
        }
        log(
            "rules loaded total=${bundle.rules.size} user=$userRules " +
                "enabled=${bundle.enabledCount} skipped=$skipped source=${bundle.source}",
        )
        return RuleSet(common = common, byKey = byKey)
    }

    private fun describeFields(obj: JSONObject): List<Pair<String, String>> {
        val fields = ArrayList<Pair<String, String>>()
        fun add(label: String, key: String) {
            if (!obj.has(key) || obj.isNull(key)) return
            val value = obj.opt(key)
            val text = when (value) {
                null, JSONObject.NULL -> return
                is String -> value.trim()
                else -> value.toString()
            }
            if (text.isNotEmpty()) fields.add(label to text)
        }
        add("类型", "type")
        add("金额", "money")
        add("商户", "shopName")
        add("商品", "shopItem")
        add("转出账户", "accountNameFrom")
        add("转入账户", "accountNameTo")
        add("手续费", "fee")
        add("币种", "currency")
        add("时间", "time")
        add("渠道", "channel")
        return fields
    }

    internal fun packageForSource(source: String): String? {
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
