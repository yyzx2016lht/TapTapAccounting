package com.taostudio.tapaccounting.capture.hook

import android.content.ContentValues
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.ValueCallback
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap

/**
 * P0 原始事件探针。
 *
 * 只安装 hook、把原始 JSON 投递成“待补金额”的待记；不在这里做支付成功页的精确解析。
 * 所有回调都 catch Throwable，宿主版本变化最多导致某个 hook 失效，绝不能让微信/支付宝崩溃。
 */
internal object ProbeHooker {

    private const val TAG = "TapCaptureProbe"

    // 微信 WCDB 表 / type 常量（AutoAccounting 对照值）。
    private const val TABLE_MESSAGE = "message"
    private const val TABLE_APP_MESSAGE = "AppMessage"
    private const val MSG_TYPE_BOX = 318767153
    private const val MSG_TYPE_TRANSFER = 419430449
    private const val MSG_TYPE_RED_PACKAGE = 436207665
    private const val MSG_TYPE_GROUP_SYSTEM = 10000
    private const val APPMSG_TYPE_ARTICLE = 5
    private const val APPMSG_TYPE_TRANSFER = 2000

    private const val WECHAT_PAY_MARKER = "nativeWXPayCgiTunnel:ok"
    private const val ALIPAY_PAGE_MARKER = "ALIPAYVIEWAPPEARED=1"

    private const val ALIPAY_POLL_INTERVAL_MS = 200L
    private const val ALIPAY_POLL_TIMEOUT_MS = 120_000L
    private const val ALIPAY_INJECT_WINDOW_MS = 60_000L

    private val WECHAT_TXN_KEYS = setOf(
        "transaction_id",
        "transactionId",
        "transfer_id",
        "transferId",
        "transfer_no",
        "trade_no",
        "tradeNo",
        "bill_no",
        "billNo",
        "order_id",
        "orderId",
        "pay_transaction_id",
        "wx_order_id",
    )

    private val ALIPAY_TXN_KEYS = setOf(
        "tradeNo",
        "trade_no",
        "orderNo",
        "order_no",
        "bizNo",
        "biz_no",
        "transactionId",
        "transaction_id",
        "alipayTradeNo",
        "outTradeNo",
    )

    private val ALIPAY_PAY_KEYWORDS = listOf(
        "支付",
        "账单",
        "交易",
        "收款",
        "付款",
        "金额",
        "消费",
        "扣款",
        "到账",
        "退款",
    )

    private val alipayInjectedAt = ConcurrentHashMap<String, Long>()
    private val alipayPolling = ConcurrentHashMap.newKeySet<String>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "com.tencent.mm" -> {
                hookWechatDatabase(lpparam)
                hookWechatRichText(lpparam)
                hookWechatWebView(lpparam)
            }

            "com.eg.android.AlipayGphone" -> {
                hookAlipaySyncMessage(lpparam)
                hookAlipayWebView(lpparam)
            }

            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 微信

    /** 微信消息/转账落到 WCDB 时的入口。 */
    private fun hookWechatDatabase(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.wcdb.database.SQLiteDatabase",
                lpparam.classLoader,
                "insertWithOnConflict",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            handleWechatInsert(param)
                        } catch (t: Throwable) {
                            log("wcdb handle failed: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                },
            )
            log("hook installed: wcdb.SQLiteDatabase.insertWithOnConflict")
        } catch (t: Throwable) {
            log("hook unavailable: wcdb.SQLiteDatabase.insertWithOnConflict ($t)")
        }
    }

    private fun handleWechatInsert(param: XC_MethodHook.MethodHookParam) {
        val table = param.args.getOrNull(0) as? String ?: return
        if (table != TABLE_MESSAGE && table != TABLE_APP_MESSAGE) return

        val values = param.args.getOrNull(2) as? ContentValues ?: return
        val type = readInt(values, "type") ?: return

        val keep = when (table) {
            TABLE_MESSAGE -> type in setOf(
                MSG_TYPE_BOX,
                MSG_TYPE_TRANSFER,
                MSG_TYPE_RED_PACKAGE,
                MSG_TYPE_GROUP_SYSTEM,
            )

            TABLE_APP_MESSAGE -> type == APPMSG_TYPE_TRANSFER
            else -> false
        }
        if (!keep) return
        if (table == TABLE_APP_MESSAGE && type == APPMSG_TYPE_ARTICLE) return
        if (table == TABLE_MESSAGE && type == MSG_TYPE_GROUP_SYSTEM) {
            val groupContent = readString(values, "content").orEmpty()
            val isGroupCollection = listOf("群收款", "收款", "付款", "转账")
                .any { groupContent.contains(it) }
            if (!isGroupCollection) return
        }

        val now = System.currentTimeMillis()
        val content = readString(values, "content").orEmpty()
        val xml = readString(values, "xml").orEmpty()
        val talker = readString(values, "talker")
        val isSend = readInt(values, "isSend")
        val cache = WechatRichCache.snapshot(now)
        val txnId = readString(values, "msgSvrId", "msgId")

        // 关键：按 AutoAccounting 规则的 envelope 构造 rawJson，
        // 规则引擎（143/157/156/138/155 等）才能直接命中。
        val rawJson = when {
            table == TABLE_MESSAGE && type == MSG_TYPE_BOX ->
                WechatEnvelope.mmapFromXml(content, now, cache)

            table == TABLE_MESSAGE && type == MSG_TYPE_TRANSFER ->
                WechatEnvelope.transferFromXml(content, isSend, cache, talker, now)

            table == TABLE_APP_MESSAGE && type == APPMSG_TYPE_TRANSFER ->
                WechatEnvelope.transferFromXml(xml.ifBlank { content }, isSend, cache, talker, now)

            table == TABLE_MESSAGE && type == MSG_TYPE_RED_PACKAGE ->
                WechatEnvelope.redPacketFromXml(content, isSend, cache, talker, now)

            table == TABLE_MESSAGE && type == MSG_TYPE_GROUP_SYSTEM ->
                WechatEnvelope.groupFromXml(content, now, cache)

            else -> ""
        }
        if (rawJson.isBlank()) return

        val text = listOfNotNull(
            ProbeText.xmlTagText(content, "des"),
            ProbeText.xmlTagText(content, "title"),
            content,
            cache?.payMoney,
            cache?.payTools,
            cache?.payShop,
        ).joinToString("\n")

        val amountCents = ProbeText.parseAmountCents(text, requireCurrency = true)
            ?: cache?.payMoney?.let {
                ProbeText.parseAmountCents(it, requireCurrency = false)
            }
        val paymentMethod = cache?.payTools
            ?.let { ProbeText.findPaymentMethod(it) }
            ?: ProbeText.findPaymentMethod(text)
        val merchant = ProbeText.cleanMerchant(
            cache?.payShop,
            ProbeText.xmlTagText(content, "display_name"),
            ProbeText.xmlTagText(content, "nickname"),
            ProbeText.xmlTagText(content, "title"),
        )

        log(
            "wcdb event table=$table type=$type talker=${talker ?: "-"} " +
                "isSend=${isSend ?: "-"} amount=${amountCents ?: "-"} envelope=" +
                if (rawJson.startsWith("{\"mMap\"")) "mMap" else "rule",
        )
        CaptureForwarder.forward(
            source = CaptureSource.WECHAT,
            rawJson = rawJson,
            merchant = merchant,
            paymentMethodRaw = paymentMethod,
            sourceTxnId = txnId,
            amountCents = amountCents,
            dataType = "DATA",
        )
    }

    /** 微信支付成功页的 RichText 文本节点（金额/支付方式常在这里）。只缓存，不单独上报。 */
    private fun hookWechatRichText(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.kinda.framework.widget.base.MMKRichText",
                lpparam.classLoader,
                "appendText",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val text = param.args.getOrNull(0) as? String ?: return
                            WechatRichCache.record(text)
                            log("MMKRichText.appendText len=${text.length} preview=${text.take(120)}")
                        } catch (t: Throwable) {
                            log("rich text handle failed: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                },
            )
            log("hook installed: MMKRichText.appendText")
        } catch (t: Throwable) {
            log("hook unavailable: MMKRichText.appendText ($t)")
        }
    }

    /** 微信支付 JS Bridge（转账详情/支付成功页）。 */
    private fun hookWechatWebView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.xweb.WebView",
                lpparam.classLoader,
                "evaluateJavascript",
                String::class.java,
                ValueCallback::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val script = param.args.getOrNull(0) as? String ?: return
                            if (!script.contains(WECHAT_PAY_MARKER)) return
                            handleWechatPayBridge(script)
                        } catch (t: Throwable) {
                            log("wechat webview handle failed: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                },
            )
            log("hook installed: com.tencent.xweb.WebView.evaluateJavascript")
        } catch (t: Throwable) {
            log("hook unavailable: com.tencent.xweb.WebView.evaluateJavascript ($t)")
        }
    }

    private fun handleWechatPayBridge(script: String) {
        // 形如：javascript:WeixinJSBridge._handleMessageFromWeixin({...})
        val start = script.indexOf('{')
        val end = script.lastIndexOf('}')
        if (start < 0 || end <= start) {
            log("wechat bridge: no json object in script")
            return
        }

        val root = JSONObject(script.substring(start, end + 1))
        val params = root.optJSONObject("__json_message")
            ?.optJSONObject("__params")
            ?: return

        val payload = when (val respbuf = params.opt("respbuf")) {
            is JSONObject -> respbuf
            is String -> JSONObject(respbuf)
            else -> return
        }
        payload.remove("entrances")
        payload.remove("service_module")

        val now = System.currentTimeMillis()
        val cache = WechatRichCache.snapshot(now)
        cache?.let {
            payload.put("cachedPayMoney", it.payMoney ?: "")
            payload.put("cachedPayTools", it.payTools ?: "")
            payload.put("cachedPayShop", it.payShop ?: "")
            payload.put("cachedAt", it.at)
        }
        payload.put("t", now)

        val header = payload.optJSONObject("header")
        val nickname = header?.optString("nickname", "")?.trim()?.takeIf { it.isNotEmpty() }
        val text = payload.toString()
        val amountCents = ProbeText.parseJsonAmountCents(text)
        val paymentMethod = cache?.payTools
            ?.let { ProbeText.findPaymentMethod(it) }
            ?: ProbeText.findPaymentMethod(text)
        val merchant = nickname ?: ProbeText.cleanMerchant(cache?.payShop)
        val txnId = ProbeText.findTxnId(payload, WECHAT_TXN_KEYS)

        log(
            "wechat bridge event merchant=${merchant ?: "-"} txn=${txnId ?: "-"} " +
                "amount=${amountCents ?: "-"}",
        )
        CaptureForwarder.forward(
            source = CaptureSource.WECHAT,
            rawJson = text,
            merchant = merchant,
            paymentMethodRaw = paymentMethod,
            sourceTxnId = txnId,
            amountCents = amountCents,
            dataType = "DATA",
        )
    }

    // ---------------------------------------------------------------- 支付宝

    /** 支付宝消息盒子：JSON 数组里逐条挑出支付相关消息。 */
    private fun hookAlipaySyncMessage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.alipay.mobile.rome.longlinkservice.syncmodel.SyncMessage",
                lpparam.classLoader,
                "getData",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            handleAlipaySyncMessage(param)
                        } catch (t: Throwable) {
                            log("alipay sync handle failed: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                },
            )
            log("hook installed: SyncMessage.getData")
        } catch (t: Throwable) {
            log("hook unavailable: SyncMessage.getData ($t)")
        }
    }

    private fun handleAlipaySyncMessage(param: XC_MethodHook.MethodHookParam) {
        val data = param.result as? String ?: return
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return
        if (!trimmed.startsWith("[")) {
            log("SyncMessage.getData non-array len=${trimmed.length}")
            return
        }

        val array = JSONArray(trimmed)
        var forwarded = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val text = item.toString()
            // 支付宝消息盒子混有红包/营销/系统消息；P0 只收支付/账单相关。
            if (ALIPAY_PAY_KEYWORDS.none { text.contains(it) }) continue
            // 规则 406/59 等按“JSON 数组”解析；AutoAccounting 的 MessageBoxHooker
            // 也是把单条包成单元素数组后上报，不能只发 item。
            val ruleInput = JSONArray().put(item).toString()

            forwarded++
            val amountCents = ProbeText.parseJsonAmountCents(text)
                ?: ProbeText.parseAmountCents(text, requireCurrency = false)
            val paymentMethod = ProbeText.findPaymentMethod(text)
            val merchant = ProbeText.pickString(
                item,
                "title",
                "subject",
                "merchant",
                "sellerName",
                "shopName",
                "storeName",
            )
            val txnId = ProbeText.findTxnId(item, ALIPAY_TXN_KEYS)
            CaptureForwarder.forward(
                source = CaptureSource.ALIPAY,
                rawJson = ruleInput,
                merchant = merchant,
                paymentMethodRaw = paymentMethod,
                sourceTxnId = txnId,
                amountCents = amountCents,
                dataType = "DATA",
            )
        }
        log("SyncMessage.getData len=${trimmed.length} candidates=$forwarded")
    }

    /** 支付宝 H5 账单页：注入 AlipayJSBridge 包装并轮询结果。 */
    private fun hookAlipayWebView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.alipay.mobile.nebulacore.web.H5WebView",
                lpparam.classLoader,
                "evaluateJavascript",
                String::class.java,
                ValueCallback::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            handleAlipayWebViewCall(param)
                        } catch (t: Throwable) {
                            log("alipay webview handle failed: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                },
            )
            log("hook installed: H5WebView.evaluateJavascript")
        } catch (t: Throwable) {
            log("hook unavailable: H5WebView.evaluateJavascript ($t)")
        }
    }

    private fun handleAlipayWebViewCall(param: XC_MethodHook.MethodHookParam) {
        val script = param.args.getOrNull(0) as? String ?: return
        if (!script.contains(ALIPAY_PAGE_MARKER)) return

        val webView = param.thisObject ?: return
        val url = try {
            XposedHelpers.callMethod(webView, "getUrl") as? String
        } catch (t: Throwable) {
            log("alipay webview getUrl failed: ${t.javaClass.name}: ${t.message}")
            null
        }
        if (url.isNullOrBlank() || !url.contains("tradeNo=", ignoreCase = true)) return

        val now = System.currentTimeMillis()
        val last = alipayInjectedAt[url]
        if (last != null && now - last < ALIPAY_INJECT_WINDOW_MS) return
        alipayInjectedAt[url] = now
        if (alipayInjectedAt.size > 64) {
            alipayInjectedAt.entries.removeAll { now - it.value > ALIPAY_INJECT_WINDOW_MS }
        }

        val main = Handler(Looper.getMainLooper())
        main.post {
            try {
                log("alipay webview inject url=${url.take(200)}")
                XposedHelpers.callMethod(webView, "evaluateJavascript", ALIPAY_INJECT_JS, null)
                startAlipayPolling(main, webView, url)
            } catch (t: Throwable) {
                log("alipay webview inject failed: ${t.javaClass.name}: ${t.message}")
            }
        }
    }

    private fun startAlipayPolling(main: Handler, webView: Any, url: String) {
        if (!alipayPolling.add(url)) return
        val startedAt = SystemClock.uptimeMillis()

        val poll = object : Runnable {
            override fun run() {
                if (!alipayPolling.contains(url)) return
                if (SystemClock.uptimeMillis() - startedAt > ALIPAY_POLL_TIMEOUT_MS) {
                    alipayPolling.remove(url)
                    log("alipay webview polling timeout url=${url.take(120)}")
                    return
                }

                try {
                    XposedHelpers.callMethod(
                        webView,
                        "evaluateJavascript",
                        "javascript:window.ankioResults",
                        object : ValueCallback<String> {
                            override fun onReceiveValue(value: String?) {
                                try {
                                    val result = decodeJsResult(value) ?: return
                                    alipayPolling.remove(url)
                                    forwardAlipayWebResult(result)
                                } catch (t: Throwable) {
                                    log("alipay webview callback failed: ${t.javaClass.name}: ${t.message}")
                                }
                            }
                        },
                    )
                } catch (t: Throwable) {
                    alipayPolling.remove(url)
                    log("alipay webview poll failed: ${t.javaClass.name}: ${t.message}")
                    return
                }

                main.postDelayed(this, ALIPAY_POLL_INTERVAL_MS)
            }
        }
        main.postDelayed(poll, ALIPAY_POLL_INTERVAL_MS)
    }

    private fun forwardAlipayWebResult(raw: String) {
        val obj = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            null
        }
        val amountCents = obj?.let { ProbeText.parseJsonAmountCents(raw) }
            ?: ProbeText.parseAmountCents(raw, requireCurrency = false)
        val paymentMethod = ProbeText.findPaymentMethod(raw)
        val merchant = obj?.let {
            ProbeText.pickString(
                it,
                "merchant",
                "sellerName",
                "shopName",
                "storeName",
                "subject",
                "title",
            )
        }
        val txnId = obj?.let { ProbeText.findTxnId(it, ALIPAY_TXN_KEYS) }

        log(
            "alipay webview result len=${raw.length} merchant=${merchant ?: "-"} " +
                "txn=${txnId ?: "-"} amount=${amountCents ?: "-"}",
        )
        CaptureForwarder.forward(
            source = CaptureSource.ALIPAY,
            rawJson = raw,
            merchant = merchant,
            paymentMethodRaw = paymentMethod,
            sourceTxnId = txnId,
            amountCents = amountCents,
            dataType = "DATA",
        )
    }

    private fun decodeJsResult(value: String?): String? {
        val text = value?.trim() ?: return null
        if (text.isEmpty() || text == "null" || text == "undefined" || text == "{}") return null
        return try {
            when (val parsed = JSONTokener(text).nextValue()) {
                is String -> parsed.takeIf {
                    it.isNotBlank() && it != "null" && it != "undefined" && it != "{}"
                }

                is JSONObject -> parsed.toString().takeIf { it != "{}" }
                is JSONArray -> parsed.toString()
                else -> text
            }
        } catch (_: Throwable) {
            text
        }
    }

    // ---------------------------------------------------------------- 小工具

    private fun readInt(values: ContentValues, key: String): Int? = try {
        values.getAsInteger(key) ?: values.getAsString(key)?.trim()?.toIntOrNull()
    } catch (_: Throwable) {
        null
    }

    private fun readString(values: ContentValues, vararg keys: String): String? {
        for (key in keys) {
            val value = try {
                values.getAsString(key)
            } catch (_: Throwable) {
                null
            }
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** ContentValues -> 可读 JSON；byte[] 只记长度，避免日志/JSON 过大。 */
    private fun copyContentValues(values: ContentValues, out: JSONObject) {
        for (key in values.keySet()) {
            try {
                when (val value = values.get(key)) {
                    null -> Unit
                    is ByteArray -> out.put("${key}Length", value.size)
                    is Number, is Boolean, is String -> out.put(key, value)
                    else -> out.put(key, value.toString())
                }
            } catch (_: Throwable) {
                // 单个字段异常不影响整条消息。
            }
        }
    }

    fun log(message: String) {
        try {
            XposedBridge.log("[$TAG] $message")
        } catch (_: Throwable) {
            // XposedBridge 不可用时静默；探针不能影响宿主。
        }
    }

    private val ALIPAY_INJECT_JS = """
        (function(){
            if (window.__ankioHooked) return;
            if (typeof AlipayJSBridge === 'undefined' || typeof AlipayJSBridge.call !== 'function') return;
            window.__ankioHooked = true;
            window.ankioResults = null;
            var originalCall = AlipayJSBridge.call;
            AlipayJSBridge.call = function(a,b,c){
                if (typeof c === 'function'){
                    return originalCall(a,b,function(d){
                        try {
                            if (d && d.ariverRpcTraceId !== undefined) {
                                window.ankioResults = d;
                            }
                        } catch(e) {}
                        return c(d);
                    });
                }
                return originalCall(a,b,c);
            };
        })();
    """.trimIndent()
}
