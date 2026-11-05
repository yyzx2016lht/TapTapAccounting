package com.taostudio.tapaccounting.capture.hook

import android.content.ContentValues
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.ValueCallback
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import dalvik.system.DexFile
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

    private val alipayInjectedAt = ConcurrentHashMap<String, Long>()
    private val alipayPolling = ConcurrentHashMap.newKeySet<String>()
    private val wechatContactCache = ConcurrentHashMap<String, String>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "com.tencent.mm" -> {
                hookWechatDatabase(lpparam)
                hookWechatRichText(lpparam)
                hookWechatWebView(lpparam)
                hookWechatRemittance(lpparam)
                hookWechatLuckyMoney(lpparam)
            }

            "com.eg.android.AlipayGphone" -> {
                hookAlipaySyncMessage(lpparam)
                hookAlipayWebView(lpparam)
                hookAlipaySecurity(lpparam)
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

        // 微信有时不是新 insert，而是 update 转账消息状态（例如转账->退款/收款）。
        // AutoAccounting 只挂了 insert；这里补上 update，values 不带 content 时自然跳过。
        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.wcdb.database.SQLiteDatabase",
                lpparam.classLoader,
                "updateWithOnConflict",
                String::class.java,
                ContentValues::class.java,
                String::class.java,
                Array<String>::class.java,
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val table = param.args.getOrNull(0) as? String ?: return
                            val values = param.args.getOrNull(1) as? ContentValues ?: return
                            handleWechatWrite(param.thisObject, table, values)
                        } catch (t: Throwable) {
                            log("wcdb update handle failed: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                },
            )
            log("hook installed: wcdb.SQLiteDatabase.updateWithOnConflict")
        } catch (t: Throwable) {
            log("hook unavailable: wcdb.SQLiteDatabase.updateWithOnConflict ($t)")
        }
    }

    private fun handleWechatInsert(param: XC_MethodHook.MethodHookParam) {
        val table = param.args.getOrNull(0) as? String ?: return
        val values = param.args.getOrNull(2) as? ContentValues ?: return
        handleWechatWrite(param.thisObject, table, values)
    }

    private fun handleWechatWrite(db: Any?, table: String, values: ContentValues) {
        if (table != TABLE_MESSAGE && table != TABLE_APP_MESSAGE) return
        val type = readInt(values, "type") ?: return

        // AutoAccounting DatabaseHooker 的完整类型集合：
        // - message: 消息盒子 / 转账 / 红包 / 群系统消息
        // - AppMessage: 文章(type=5) / 转账(type=2000)
        // 不再按“是否支付关键词”预过滤：未命中的消息只进原始历史，不建待记。
        val keep = when (table) {
            TABLE_MESSAGE -> type in setOf(
                MSG_TYPE_BOX,
                MSG_TYPE_TRANSFER,
                MSG_TYPE_RED_PACKAGE,
                MSG_TYPE_GROUP_SYSTEM,
            )

            TABLE_APP_MESSAGE -> type in setOf(APPMSG_TYPE_ARTICLE, APPMSG_TYPE_TRANSFER)
            else -> false
        }
        if (!keep) return

        val now = System.currentTimeMillis()
        val content = readString(values, "content").orEmpty()
        val xml = readString(values, "xml").orEmpty()
        val xmlSource = xml.ifBlank { content }
        if (table == TABLE_MESSAGE && type == MSG_TYPE_GROUP_SYSTEM && !content.contains("群收款")) {
            return
        }

        // 过滤服务号消息盒子(318767153)与公众号文章(5)：
        // 必须命中 DataFilter 动账关键词白名单（与 AutoAccounting 保持一致），
        // 彻底阻断普通公众号推文、系统通知与广告营销消息。
        if (table == TABLE_MESSAGE && type == MSG_TYPE_BOX && !DataFilter.matches(xmlSource)) {
            return
        }
        if (table == TABLE_APP_MESSAGE && type == APPMSG_TYPE_ARTICLE && !DataFilter.matches(xmlSource)) {
            return
        }

        val rawTalker = readString(values, "talker")
            ?: ProbeText.xmlTagText(xmlSource, "receiver_username")
            ?: ProbeText.xmlTagText(xmlSource, "payer_username")
        val talker = resolveWechatDisplayName(db, rawTalker) ?: rawTalker
        val isSend = readInt(values, "isSend")
        val cache = WechatRichCache.snapshot(now)
        val fallbackTxnId = readString(values, "msgSvrId", "msgId")

        // 关键：按 AutoAccounting 规则的 envelope 构造 rawJson，
        // 规则引擎（143/157/156/138/155 等）才能直接命中。
        val rawJson = when {
            table == TABLE_APP_MESSAGE && type == APPMSG_TYPE_ARTICLE ->
                contentValuesToJson(values)

            table == TABLE_MESSAGE && type == MSG_TYPE_BOX ->
                WechatEnvelope.mmapFromXml(content, now, cache)

            table == TABLE_MESSAGE && type == MSG_TYPE_TRANSFER ->
                WechatEnvelope.transferFromXml(content, isSend, cache, talker, now)

            table == TABLE_APP_MESSAGE && type == APPMSG_TYPE_TRANSFER ->
                WechatEnvelope.transferFromXml(xmlSource, isSend, cache, talker, now)

            table == TABLE_MESSAGE && type == MSG_TYPE_RED_PACKAGE ->
                WechatEnvelope.redPacketFromXml(content, isSend, cache, talker, now)

            table == TABLE_MESSAGE && type == MSG_TYPE_GROUP_SYSTEM ->
                WechatEnvelope.groupFromXml(content, now, cache)

            else -> ""
        }
        if (rawJson.isBlank()) return

        if (isSend == 1 && (type == MSG_TYPE_TRANSFER || type == APPMSG_TYPE_TRANSFER || type == MSG_TYPE_RED_PACKAGE)) {
            WechatRichCache.clearShop()
        }

        val txnId = when {
            table == TABLE_MESSAGE && type == MSG_TYPE_RED_PACKAGE ->
                WechatEnvelope.redPacketTxnId(rawJson) ?: fallbackTxnId

            table == TABLE_APP_MESSAGE ||
                (table == TABLE_MESSAGE && type == MSG_TYPE_TRANSFER) ->
                WechatEnvelope.transferTxnId(rawJson) ?: fallbackTxnId

            else -> fallbackTxnId
        }

        val text = listOfNotNull(
            ProbeText.xmlTagText(xmlSource, "des"),
            ProbeText.xmlTagText(xmlSource, "title"),
            xmlSource,
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
            talker?.takeIf { type == MSG_TYPE_TRANSFER || type == APPMSG_TYPE_TRANSFER || type == MSG_TYPE_RED_PACKAGE },
            cache?.payShop,
            ProbeText.xmlTagText(xmlSource, "display_name"),
            ProbeText.xmlTagText(xmlSource, "nickname"),
            ProbeText.xmlTagText(xmlSource, "title"),
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

    /**
     * 从当前正在写入的 EnMicroMsg.db (rcontact 表) 把 wxid_xxx 解析成微信备注名或昵称，
     * 解决转账/退款/收红包时商户名变成裸 wxid_xxx 的问题。
     */
    private fun resolveWechatDisplayName(db: Any?, wxid: String?): String? {
        val id = wxid?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!id.startsWith("wxid_") && !id.endsWith("@chatroom") && !id.contains("_")) {
            return id
        }
        wechatContactCache[id]?.let { return it }
        if (db == null) return null

        val cursor = try {
            XposedHelpers.callMethod(
                db,
                "rawQuery",
                "SELECT conRemark, nickname FROM rcontact WHERE username = ? LIMIT 1",
                arrayOf(id),
            )
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            val moved = XposedHelpers.callMethod(cursor, "moveToFirst") as? Boolean ?: false
            if (!moved) return null
            val remark = (XposedHelpers.callMethod(cursor, "getString", 0) as? String)?.trim().orEmpty()
            val nickname = (XposedHelpers.callMethod(cursor, "getString", 1) as? String)?.trim().orEmpty()
            val resolved = remark.ifEmpty { nickname }.takeIf { it.isNotEmpty() }
            if (resolved != null) {
                wechatContactCache[id] = resolved
            }
            resolved
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { XposedHelpers.callMethod(cursor, "close") }
        }
    }

    /**
     * AutoAccounting 的 TransferHooker 等价物：转账详情回调 onGYNetEnd。
     *
     * 微信版本/混淆会让类名变化，AutoAccounting 用 DexKit 扫描；这里用 DexFile 枚举
     * `com.tencent.mm.plugin.remittance.model.*`，找 (int, String, JSONObject) 的 onGYNetEnd。
     * 只接收入账方向，避免与 WCDB 转出重复；入账/退款是 WCDB 最容易漏的路径。
     */
    /**
     * 微信的 Dex 路径：base APK + split APK + Tinker 热补丁 dex。
     *
     * AutoAccounting 直接关闭 Tinker；这里选择不破坏宿主，改为把 patch 目录里的
     * dex 也加入扫描。否则只会扫到 base APK 里的旧类（a0/a6），运行时真正的类
     * 可能在 patch 里（h1/h6），hook 永远不会触发。
     */
    private fun wechatDexPaths(lpparam: XC_LoadPackage.LoadPackageParam): List<String> {
        val appInfo = lpparam.appInfo ?: return emptyList()
        val paths = LinkedHashSet<String>()
        appInfo.sourceDir?.let { paths.add(it) }
        appInfo.splitSourceDirs?.forEach { paths.add(it) }
        val dataDir = appInfo.dataDir
        if (!dataDir.isNullOrBlank()) {
            val tinker = java.io.File(dataDir, "tinker")
            if (tinker.isDirectory) {
                tinker.listFiles()?.asSequence()
                    ?.filter { it.isDirectory && it.name.startsWith("patch-") }
                    ?.forEach { patch ->
                        patch.walkTopDown()
                            .filter { it.isFile && it.extension.equals("dex", ignoreCase = true) }
                            .forEach { paths.add(it.absolutePath) }
                    }
            }
        }
        return paths.toList()
    }

    private fun hookWechatRemittance(lpparam: XC_LoadPackage.LoadPackageParam) {
        var hooked = 0
        for (path in wechatDexPaths(lpparam)) {
            try {
                @Suppress("DEPRECATION")
                val dex = DexFile(path)
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    if (!className.startsWith("com.tencent.mm.plugin.remittance.model.")) continue
                    val clazz = runCatching {
                        XposedHelpers.findClassIfExists(className, lpparam.classLoader)
                    }.getOrNull() ?: continue
                    val method = clazz.declaredMethods.firstOrNull {
                        it.name == "onGYNetEnd" &&
                            it.parameterTypes.size == 3 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            it.parameterTypes[1] == String::class.java &&
                            it.parameterTypes[2] == JSONObject::class.java
                    } ?: continue
                    val ok = runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val json = param.args.getOrNull(2) as? JSONObject ?: return
                                    if (!json.has("is_payer")) return
                                    val isPayer = json.optBoolean("is_payer", false)
                                    // AutoAccounting 的 TransferHooker 两个方向都上报：
                                    // is_payer=true 用 payer_name，false 用 receiver_name。
                                    val nameKey = if (isPayer) "payer_name" else "receiver_name"
                                    val name = json.optString(nameKey, "").trim()
                                    // 规则 157 的转账详情分支用 hookUser；WCDB 分支用 hookerUser。
                                    json.put("hookerUser", name)
                                    json.put("hookUser", name)
                                    val cache = WechatRichCache.snapshot(System.currentTimeMillis())
                                    cache?.payTools?.let { json.put("cachedPayTools", it) }
                                    cache?.payMoney?.let { json.put("cachedPayMoney", it) }
                                    cache?.payShop?.let { json.put("cachedPayShop", it) }
                                    json.put("t", System.currentTimeMillis())
                                    val raw = json.toString()
                                    val baseTxn = sequenceOf(
                                        "transcationid",
                                        "transferid",
                                        "transfer_id",
                                        "transferId",
                                    )
                                        .map { json.optString(it, "").trim() }
                                        .firstOrNull { it.isNotEmpty() && it != "0" }
                                    val subtype = json.optString("paysubtype", "").trim()
                                    val txnKey = baseTxn?.let {
                                        if (subtype.isEmpty()) it else "$it:$subtype"
                                    }
                                    // 与 AutoAccounting 一致：即使没有交易号也上报，
                                    // 由插件侧去重/合并，不能在这里丢数据。
                                    CaptureForwarder.forward(
                                        source = CaptureSource.WECHAT,
                                        rawJson = raw,
                                        merchant = name.takeIf { it.isNotEmpty() },
                                        paymentMethodRaw = cache?.payTools,
                                        sourceTxnId = txnKey,
                                        dataType = "DATA",
                                    )
                                    log(
                                        "remittance callback isPayer=$isPayer " +
                                            "name=$name txn=${txnKey ?: "-"}",
                                    )
                                } catch (t: Throwable) {
                                    log("remittance callback failed: ${t.javaClass.name}: ${t.message}")
                                }
                            }
                        })
                    }.isSuccess
                    if (ok) {
                        hooked++
                        log("hook installed: remittance ${clazz.name}.onGYNetEnd")
                    }
                }
            } catch (t: Throwable) {
                log("remittance scan failed path=$path: ${t.javaClass.name}: ${t.message}")
            }
        }
        log("remittance hooks total=$hooked")
    }

    /**
     * AutoAccounting 的 RedPackageHooker 等价物：红包详情回调 onGYNetEnd。
     *
     * WCDB 里的红包消息往往只有 XML 外壳，领取金额/发送人/领取记录在
     * `com.tencent.mm.plugin.luckymoney.model.*.onGYNetEnd(int, String, JSONObject)`。
     * 规则 154（收红包）直接读 `retcode/amount/record[0].receiveTime`，所以这里
     * 原样上报 JSON，只在外面补 hookerUser 和缓存。
     */
    private fun hookWechatLuckyMoney(lpparam: XC_LoadPackage.LoadPackageParam) {
        var hooked = 0
        for (path in wechatDexPaths(lpparam)) {
            try {
                @Suppress("DEPRECATION")
                val dex = DexFile(path)
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    if (!className.startsWith("com.tencent.mm.plugin.luckymoney.model.")) continue
                    val clazz = runCatching {
                        XposedHelpers.findClassIfExists(className, lpparam.classLoader)
                    }.getOrNull() ?: continue
                    val method = clazz.declaredMethods.firstOrNull {
                        it.name == "onGYNetEnd" &&
                            it.parameterTypes.size == 3 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            it.parameterTypes[1] == String::class.java &&
                            it.parameterTypes[2] == JSONObject::class.java
                    } ?: continue
                    val ok = runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val json = param.args.getOrNull(2) as? JSONObject ?: return
                                    // 只处理红包详情；同一包下还有其他 onGYNetEnd 回调。
                                    if (!json.has("sendId") && !json.has("sendUserName")) return
                                    val name = json.optString("sendUserName", "").trim()
                                    json.put("hookerUser", name)
                                    json.put("hookUser", name)
                                    val cache = WechatRichCache.snapshot(System.currentTimeMillis())
                                    cache?.payTools?.let { json.put("cachedPayTools", it) }
                                    cache?.payMoney?.let { json.put("cachedPayMoney", it) }
                                    cache?.payShop?.let { json.put("cachedPayShop", it) }
                                    json.put("t", System.currentTimeMillis())
                                    val sendId = json.optString("sendId", "").trim()
                                        .takeIf { it.isNotEmpty() }
                                    CaptureForwarder.forward(
                                        source = CaptureSource.WECHAT,
                                        rawJson = json.toString(),
                                        merchant = name.takeIf { it.isNotEmpty() },
                                        paymentMethodRaw = cache?.payTools,
                                        sourceTxnId = sendId,
                                        dataType = "DATA",
                                    )
                                    log("lucky money callback captured sendId=${sendId ?: "-"}")
                                } catch (t: Throwable) {
                                    log("lucky money callback failed: ${t.javaClass.name}: ${t.message}")
                                }
                            }
                        })
                    }.isSuccess
                    if (ok) {
                        hooked++
                        log("hook installed: luckymoney ${clazz.name}.onGYNetEnd")
                    }
                }
            } catch (t: Throwable) {
                log("luckymoney scan failed path=$path: ${t.javaClass.name}: ${t.message}")
            }
        }
        log("luckymoney hooks total=$hooked")
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

    /**
     * 支付宝环境检测 bypass，等价于 AutoAccounting 的 SecurityHooker。
     *
     * 只处理 ScanAttack 这一层；Native/人脸检测仍可能生效。目的是避免 Xposed/root
     * 环境让支付宝弹“登录异常/环境不安全”，不是绕过支付风控。
     */
    private fun hookAlipaySecurity(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClassIfExists(
                "com.alipay.apmobilesecuritysdk.scanattack.common.ScanAttack",
                lpparam.classLoader,
            ) ?: run {
                log("hook skipped: ScanAttack not found")
                return
            }

            var hooked = 0
            clazz.declaredMethods.forEach { method ->
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // 所有 Boolean 检测结果统一返回 false。
                            if (param.result is Boolean) param.result = false
                        }
                    })
                    hooked++
                } catch (_: Throwable) {
                    // 单个方法 hook 失败不影响其他检测。
                }
            }

            clazz.declaredMethods
                .filter { it.name == "getAD104" && it.parameterTypes.size >= 3 }
                .forEach { method ->
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                // 参数 2 是检测类型；4 = 只检测虚拟机。
                                if (param.args.size > 2 && param.args[2] is Int) {
                                    param.args[2] = 4
                                }
                            }
                        })
                    } catch (_: Throwable) {
                        // 忽略。
                    }
                }
            log("hook installed: ScanAttack methods=$hooked")
        } catch (t: Throwable) {
            log("hook unavailable: ScanAttack (${t.javaClass.name}: ${t.message})")
        }
    }

    /** 支付宝消息盒子：JSON 数组里逐条上报，交给规则判断是否账单。 */
    private fun hookAlipaySyncMessage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // AutoAccounting 的 MessageBoxHooker 同时挂 toString：有些路径不会主动调用
        // getData，只调用 toString 写日志；这里主动触发一次 getData，让统一入口处理。
        try {
            XposedHelpers.findAndHookMethod(
                "com.alipay.mobile.rome.longlinkservice.syncmodel.SyncMessage",
                lpparam.classLoader,
                "toString",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "getData")
                        } catch (t: Throwable) {
                            log("alipay toString->getData failed: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                },
            )
            log("hook installed: SyncMessage.toString")
        } catch (t: Throwable) {
            log("hook unavailable: SyncMessage.toString ($t)")
        }

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
            // 核心过滤：匹配 DataFilter 关键词白名单（与 AutoAccounting MessageBoxHooker + AnalysisUtils 完全一致）
            // 排除支付宝内部的所有非账单同步消息（如社交状态、安全检测、生活号推送、广告等）
            if (!DataFilter.matches(text)) {
                continue
            }
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

    /** ContentValues -> JSON 字符串，等价于 AutoAccounting 的 Gson().toJson(contentValues)。 */
    private fun contentValuesToJson(values: ContentValues): String {
        val out = JSONObject()
        copyContentValues(values, out)
        return out.toString()
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
