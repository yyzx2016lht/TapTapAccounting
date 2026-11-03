# AutoAccounting 微信 / 支付宝接入代码索引

> 来源：GitHub `AutoAccountingOrg/AutoAccounting`，master，2026-11-02 抓取。
> 用途：给 `capture-plugin` 的 hook 实现做对照；不是直接可粘贴的补丁。
> 许可证：仓库根目录 `LICENSE` 为 GPL-3.0（部分文件头写 Apache-3.0，疑为笔误）。个人使用可以；如果要分发插件，复用其代码/规则前必须处理 GPL 义务。下面的代码是“阅读参考”，实际移植建议按自己的结构重写。

## 1. 入口与注册

### 1.1 Xposed 入口

`app/src/main/java/net/ankio/auto/xposed/XposedModule.kt`

```kotlin
object XposedModule {
    fun get(): MutableList<HookerManifest> {
        return mutableListOf(
            AndroidHooker(),   // Android 通知
            AutoHooker(),      // 自动记账自身
            QianjiHooker(),    // 钱迹
            WechatHooker(),    // 微信
            AliPayHooker(),    // 支付宝
            SmsHooker(),       // 短信
            BluetoothHook()
        )
    }
}
```

### 1.2 每个 App 的 manifest

`xposed/core/api/HookerManifest.kt`

```kotlin
abstract class HookerManifest {
    abstract val packageName: String
    open var processName: String = ""
    abstract val appName: String
    abstract fun hookLoadPackage()
    abstract var partHookers: MutableList<PartHooker>
    abstract var rules: MutableList<Clazz>
    open var minVersion: Long = 0
    open var permissions: MutableList<String> = mutableListOf()
    open var applicationName: String = "android.app.Application"
    open var clazz = HashMap<String, ClazzResult>()
}
```

### 1.3 功能级 hooker

`xposed/core/api/PartHooker.kt`

```kotlin
abstract class PartHooker : ILogger by XposedLogger {
    protected val manifest by lazy { AppRuntime.manifest }

    fun analysisData(type: DataType, data: String) {
        AnalysisUtils.analysisData(manifest.packageName, type, data)
    }

    open fun hook() {}
}
```

- 每个功能一个 `PartHooker`，`hook()` 里安装 hook。
- `analysisData` 把原始 JSON 交给统一分析链路（AutoAccounting 是本地 server + JS 规则；我们的插件应改成 `CaptureIngestProvider`）。

### 1.4 Hook 工具

`xposed/core/hook/Hooker.kt`

```kotlin
object Hooker {
    fun loader(clazz: String, classloader: ClassLoader? = null): Class<*>
    fun after(clazz: String, method: String, vararg parameterTypes: Any, hook: (MethodHookParam) -> Unit)
    fun before(clazz: String, method: String, vararg parameterTypes: Any, hook: (MethodHookParam) -> Unit)
    fun replace(clazz: String, method: String, vararg parameterTypes: Any, hook: (MethodHookParam) -> Unit)
    fun allMethodsEqBefore(clazz: Class<*>, methodName: String, hook: (MethodHookParam, Method) -> Any?)
    fun onceAfter(clazz: Class<*>, method: String, vararg parameterTypes: Class<*>, hook: (MethodHookParam) -> Boolean)
}
```

- 所有 hook 失败都 catch `Throwable`（包括 `LinkageError`），避免宿主版本变化导致模块崩溃。
- `loader` 统一用 `AppRuntime.classLoader`。

---

## 2. 微信接入

### 2.1 微信 Hooker

`xposed/hooks/wechat/WechatHooker.kt`

```kotlin
class WechatHooker : HookerManifest() {
    override val packageName: String = "com.tencent.mm"
    override val appName: String = "微信"
    override var applicationName = "com.tencent.tinker.loader.app.TinkerApplication"

    override var partHookers: MutableList<PartHooker> = mutableListOf(
        TransferHooker(),
        DatabaseHooker(),
        RedPackageHooker(),
        ChatUserHooker(),
        PayToolsHooker(),
        WebViewHooker()
    )

    override var rules: MutableList<Clazz> = mutableListOf(
        RemittanceModel.rule,
        LuckMoneyModel.rule,
        WechatUserModel.rule,
    )

    override fun hookLoadPackage() {
        // 腾讯 Tinker 热更新会让 hook 失效；AutoAccounting 的做法是删 patch 并关 Tinker。
        // 个人使用可参考，但不要默认照搬。
        closeTinkerIfNeeded()
    }
}
```

### 2.2 WCDB 消息表（主力）

`xposed/hooks/wechat/hooks/DatabaseHooker.kt`

```kotlin
class DatabaseHooker : PartHooker() {
    private companion object {
        const val TABLE_MESSAGE = "message"
        const val TABLE_APP_MESSAGE = "AppMessage"

        const val TYPE_MSG_BOX = 318767153       // 消息盒子（支付通知类）
        const val TYPE_TRANSFER = 419430449      // 转账
        const val TYPE_GROUP_SYSTEM = 10000      // 群系统消息（群收款）
        const val TYPE_RED_PACKAGE = 436207665   // 红包

        const val APPMSG_TYPE_ARTICLE = 5
        const val APPMSG_TYPE_TRANSFER = 2000
    }

    override fun hook() {
        val database = Hooker.loader("com.tencent.wcdb.database.SQLiteDatabase")
        Hooker.after(
            database,
            "insertWithOnConflict",
            String::class.java,
            String::class.java,
            ContentValues::class.java,
            Int::class.javaPrimitiveType!!,
        ) { param ->
            val tableName = param.args[0] as String
            val contentValues = param.args[2] as ContentValues
            if (tableName != TABLE_MESSAGE && tableName != TABLE_APP_MESSAGE) return@after

            val type = contentValues.getAsInteger("type") ?: return@after
            contentValues.put("tableName", tableName)
            contentValues.put("arg", param.args[1] as? String ?: "")
            contentValues.put("t", System.currentTimeMillis()) // 微信常缺时间，补默认值

            when (tableName) {
                TABLE_MESSAGE -> when (type) {
                    TYPE_MSG_BOX -> handleMsgBox(contentValues)
                    TYPE_TRANSFER -> handleMsgTransfer(contentValues)
                    TYPE_GROUP_SYSTEM -> handleMsgGroupCollection(contentValues)
                    TYPE_RED_PACKAGE -> handleMsgRedPackage(contentValues)
                }
                TABLE_APP_MESSAGE -> when (type) {
                    APPMSG_TYPE_ARTICLE -> handleArticle(contentValues)
                    APPMSG_TYPE_TRANSFER -> handleAppTransfer(contentValues)
                }
            }
        }
    }
}
```

消息盒子解析（`handleMsgBox`）：

```kotlin
private fun handleMsgBox(contentValues: ContentValues) {
    val content = contentValues.get("content").toString()
    val json = Gson().fromJson(xmlToJson(content), JsonObject::class.java)
    val msg = json.get("msg").asJsonObject.get("appmsg").asJsonObject

    val tpl = JsonObject().apply {
        addProperty("description", msg.get("des").asString.trim())
        addProperty("title", msg.get("title").asString)
    }

    runCatching {
        val header = msg.get("mmreader").asJsonObject.get("template_header")
        if (header.isJsonObject) {
            tpl.addProperty("display_name", header.asJsonObject.get("display_name").asString)
        }
    }
    runCatching {
        val publisher = msg.get("mmreader").asJsonObject.get("publisher")
        if (publisher.isJsonObject && tpl.get("display_name").asString.isEmpty()) {
            tpl.addProperty("display_name", publisher.asJsonObject.get("nickname").asString)
        }
    }
    runCatching {
        tpl.addProperty(
            "source",
            json.get("msg").asJsonObject.get("appinfo").asJsonObject.get("appname").asString,
        )
    }

    putCache(tpl)
    analysisData(DataType.DATA, JsonObject().apply { add("mMap", tpl) }.toString())
}
```

`putCache` 会把 `PayToolsHooker` 缓存的金额/支付方式/收款方附到 JSON 上：

```kotlin
private fun putCache(json: JsonObject) {
    json.addProperty(PayToolsHooker.PAY_TOOLS, MemoryCache.get(PayToolsHooker.PAY_TOOLS) as? String ?: "")
    json.addProperty(PayToolsHooker.PAY_MONEY, MemoryCache.get(PayToolsHooker.PAY_MONEY) as? String ?: "")
    json.addProperty(PayToolsHooker.PAY_SHOP, MemoryCache.get(PayToolsHooker.PAY_SHOP) as? String ?: "")
    json.addProperty("t", System.currentTimeMillis())
}
```

- `message` 表：消息盒子、转账、群收款、红包。
- `AppMessage` 表：文章/链接（type=5）、转账（type=2000）。
- 去重：`MD5HashTable` 按描述/内容 MD5。
- 其他 handler：`handleMsgTransfer`、`handleMsgGroupCollection`、`handleMsgRedPackage` 都是保留 XML/ContentValues + `talker`/`isSend` + `putCache`。

### 2.3 RichText 缓存（金额/支付方式/收款方）

`xposed/hooks/wechat/hooks/PayToolsHooker.kt`

```kotlin
class PayToolsHooker : PartHooker() {
    companion object {
        const val PAY_TOOLS = "cachedPayTools"
        const val PAY_MONEY = "cachedPayMoney"
        const val PAY_SHOP = "cachedPayShop"
        private const val DURATION_SECONDS = 120L
    }

    override fun hook() {
        Hooker.after(
            "com.tencent.kinda.framework.widget.base.MMKRichText",
            "appendText",
            String::class.java,
        ) { param ->
            val text = param.args[0] as String
            when {
                Regex(".*(卡|零钱|经营账户).*").matches(text) ->
                    MemoryCache.put(PAY_TOOLS, text, DURATION_SECONDS)
                Regex(".*([￥$]).*").matches(text) ->
                    MemoryCache.put(PAY_MONEY, text, DURATION_SECONDS)
                Regex(".*(转账|红包|付款给).*").matches(text) ->
                    MemoryCache.put(PAY_SHOP, text, DURATION_SECONDS)
            }
        }
    }
}
```

- 支付成功页的金额、支付方式、收款方可能分散在不同 View；RichText 缓存 120 秒，等下一条消息/DB 插入归并。
- 这是做“支付成功页 hook”时最值得借鉴的模式。

### 2.4 WebView（微信支付 JS Bridge）

`xposed/hooks/wechat/hooks/WebViewHooker.kt`

```kotlin
class WebViewHooker : PartHooker() {
    private val md5HashTable = MD5HashTable()

    override fun hook() {
        Hooker.after(
            "com.tencent.xweb.WebView",
            "evaluateJavascript",
            String::class.java,
            android.webkit.ValueCallback::class.java,
        ) { param ->
            val json = param.args[0] as String
            if (!json.contains("nativeWXPayCgiTunnel:ok")) return@after

            val jsonStr = json.substring(
                "javascript:WeixinJSBridge._handleMessageFromWeixin(".length,
                json.length - 1,
            )
            runCatching {
                val jsonObject = Gson().fromJson(jsonStr, JsonObject::class.java)
                val params = jsonObject.getAsJsonObject("__json_message").getAsJsonObject("__params")
                val respbuf = params.get("respbuf").asString
                val payload = Gson().fromJson(respbuf, JsonObject::class.java)
                payload.remove("entrances")
                payload.remove("service_module")

                val md5 = MD5HashTable.md5(payload.toString())
                if (md5HashTable.contains(md5)) return@runCatching
                md5HashTable.put(md5)
                analysisData(DataType.DATA, payload.toString())
            }
        }
    }
}
```

- 锚点：JS 字符串里包含 `nativeWXPayCgiTunnel:ok`。
- 真正的数据在 `__json_message.__params.respbuf`（JSON 字符串）。
- 典型字段：`header.nickname`（如“转账-来自微信支付”）、`header.fee`、`preview` 里的“当前状态/转账说明/转账时间/转账单号”。
- 这个 hook 覆盖微信支付成功页/转账详情页，可能比 WCDB 更直接。

### 2.5 转账 / 红包 / 用户

`TransferHooker.kt`

```kotlin
val model = RemittanceModel.clazz()
Hooker.after(model, "onGYNetEnd", Int::class.java, String::class.java, org.json.JSONObject::class.java) {
    val json = it.args[2] as org.json.JSONObject
    val nameKey = if (json.getBoolean("is_payer")) "payer_name" else "receiver_name"
    json.put(WechatUserModel.CHAT_USER, WechatUserModel.get(json.getString(nameKey)))
    json.put("cachedPayTools", DataUtils.get("cachedPayTools"))
    json.put("cachedPayMoney", DataUtils.get("cachedPayMoney"))
    json.put("cachedPayShop", DataUtils.get("cachedPayShop"))
    json.put("t", System.currentTimeMillis())
    analysisData(DataType.DATA, json.toString())
}
```

`RedPackageHooker.kt`

```kotlin
val clazz = LuckMoneyModel.clazz()
Hooker.before(
    clazz, "onGYNetEnd",
    Int::class.javaPrimitiveType!!, String::class.java, JSONObject::class.java,
) { param ->
    val json = param.args[2] as JSONObject
    json.put(WechatUserModel.CHAT_USER, WechatUserModel.get(json.getString("sendUserName")))
    analysisData(DataType.DATA, json.toString())
}
```

`ChatUserHooker.kt`

```kotlin
val clazzUser = WechatUserModel.clazz()
Hooker.after(clazzUser, "convertFrom", Hooker.loader("android.database.Cursor")) { param ->
    WechatUserModel.parse(param.thisObject)
}
```

### 2.6 类查找规则

`RemittanceModel.kt`

```kotlin
override var rule = Clazz(
    type = "class",
    nameRule = "com.tencent.mm.plugin.remittance.model.\\w+",
    methods = arrayListOf(
        ClazzMethod(
            name = "onGYNetEnd",
            returnType = "void",
            parameters = listOf(
                ClazzField(type = "int"),
                ClazzField(type = "java.lang.String"),
                ClazzField(type = "org.json.JSONObject"),
            ),
            strings = listOf("pay_time", "trans_status_name", "is_payer"),
        )
    )
)
```

`LuckMoneyModel.kt` 同理：`com.tencent.mm.plugin.luckymoney.model.\w+` + `onGYNetEnd(int, String, JSONObject)`。

`WechatUserModel.kt`：

```kotlin
override var rule = Clazz(
    type = "class",
    fields = listOf(
        ClazzField(type = "java.lang.String", name = "field_conRemark"),
        ClazzField(type = "java.lang.String", name = "field_nickname"),
        ClazzField(type = "java.lang.String", name = "field_username"),
    ),
    methods = listOf(
        ClazzMethod(
            name = "convertFrom",
            parameters = listOf(ClazzField(type = "android.database.Cursor")),
        )
    )
)
```

- 不硬编码混淆类名；用字段/方法/字符串锚点查找。
- `WechatUserModel` 缓存 `field_conRemark`（备注）优先，其次 `field_nickname`。

---

## 3. 支付宝接入

### 3.1 支付宝 Hooker

`xposed/hooks/alipay/AliPayHooker.kt`

```kotlin
class AliPayHooker : HookerManifest() {
    override val packageName: String = "com.eg.android.AlipayGphone"
    override val appName: String = "支付宝"
    override var applicationName: String = "com.alipay.mobile.quinox.LauncherApplication"

    override var partHookers: MutableList<PartHooker> = mutableListOf(
        MessageBoxHooker(),  // 支付消息盒子
        WebViewHooker(),     // H5 账单页
        SecurityHooker(),    // 环境检测 bypass
    )

    override var rules: MutableList<Clazz> = mutableListOf()
}
```

### 3.2 消息盒子

`xposed/hooks/alipay/hooks/MessageBoxHooker.kt`

```kotlin
class MessageBoxHooker : PartHooker() {
    override fun hook() {
        val syncMessage =
            Hooker.loader("com.alipay.mobile.rome.longlinkservice.syncmodel.SyncMessage")

        // 有些路径不主动调用 getData，只 toString；这里触发 getData，让统一钩子处理。
        Hooker.after(syncMessage, "toString") { param ->
            XposedHelpers.callMethod(param.thisObject, "getData")
        }

        Hooker.after(syncMessage, "getData") { param ->
            val result = param.result as String
            val array = Gson().fromJson(result, JsonArray::class.java)
            array.forEach { jsonObject ->
                val jsonArray = JsonArray().apply { add(jsonObject) }
                analysisData(DataType.DATA, Gson().toJson(jsonArray))
            }
        }
    }
}
```

- 支付宝的同步消息是 JSON 数组；每条单独上报。
- 数据里可能是支付、账单、转账、红包等多种消息；解析要靠规则/关键字。

### 3.3 H5 WebView + JS Bridge

`xposed/hooks/alipay/hooks/WebViewHooker.kt`

```kotlin
class WebViewHooker : PartHooker() {
    companion object {
        const val WAIT_TIME = 100L
        const val MAX_WAIT_MS = 120_000L
    }

    override fun hook() {
        val webView = Hooker.loader("com.alipay.mobile.nebulacore.web.H5WebView")
        Hooker.after(
            webView,
            "evaluateJavascript",
            String::class.java,
            ValueCallback::class.java,
        ) { param ->
            val script = param.args[0] as String
            val obj = param.thisObject
            val url = XposedHelpers.callMethod(obj, "getUrl") as? String ?: return@after
            if (!url.contains("tradeNo=")) return@after

            if (script.contains("(function(){window.ALIPAYVIEWAPPEARED=1})()")) {
                // 注入 JS，包装 AlipayJSBridge.call，把 ariverRpcTraceId 结果存到 window.ankioResults
                val inject = """
                    (function(){
                        if (window.__ankioHooked) return;
                        if (typeof AlipayJSBridge === 'undefined' || typeof AlipayJSBridge.call !== 'function') return;
                        window.__ankioHooked = true;
                        window.ankioResults = {};
                        var alipayCall = AlipayJSBridge.call;
                        AlipayJSBridge.call = function(a,b,c){
                            if (typeof c === 'function'){
                                alipayCall(a,b,function(d){
                                    if (d && d.ariverRpcTraceId !== undefined) {
                                        window.ankioResults = d;
                                    }
                                    c(d);
                                });
                            } else {
                                alipayCall(a,b,c);
                            }
                        };
                    })();
                """.trimIndent()
                XposedHelpers.callMethod(obj, "evaluateJavascript", inject, null)

                var needWait = true
                val resultCallback = ValueCallback<String> { result ->
                    if (result.isNullOrEmpty() || result == "{}" || result == "null") return@ValueCallback
                    needWait = false
                    analysisData(DataType.DATA, result)
                }

                CoroutineUtils.withIO {
                    withTimeout(MAX_WAIT_MS.milliseconds) {
                        while (needWait) {
                            withContext(Dispatchers.Main) {
                                XposedHelpers.callMethod(
                                    obj,
                                    "evaluateJavascript",
                                    "javascript:window.ankioResults",
                                    resultCallback,
                                )
                            }
                            delay(WAIT_TIME.milliseconds)
                        }
                    }
                }
            }
        }
    }
}
```

- 锚点：WebView URL 包含 `tradeNo=`，且脚本包含 `ALIPAYVIEWAPPEARED=1`。
- 注入 `AlipayJSBridge.call` 包装，抓 `ariverRpcTraceId` 结果，轮询 `window.ankioResults`。
- 适合支付宝 H5 账单页；原生支付成功页需要另找 hook 点。

### 3.4 安全检测 bypass

`xposed/hooks/alipay/hooks/SecurityHooker.kt`

```kotlin
class SecurityHooker : PartHooker() {
    override fun hook() {
        val clazz = AppRuntime.classLoader.loadClass(
            "com.alipay.apmobilesecuritysdk.scanattack.common.ScanAttack"
        )

        // 所有 Boolean 检测返回 false
        Hooker.allMethodsAfter(clazz) { param, _ ->
            if (param.result is Boolean) param.result = false
        }

        Hooker.before(
            clazz, "getAD104",
            Context::class.java,
            Int::class.java, Int::class.java,
            Boolean::class.java,
            Int::class.java, Int::class.java,
            String::class.java,
        ) {
            it.args[2] = 4 // 只检测虚拟机
        }
    }
}
```

⚠️ 这是对抗支付宝环境检测；个人使用自行评估，不建议默认照搬或分发。

---

## 4. 共享分析链路

`xposed/core/utils/AnalysisUtils.kt`

```kotlin
object AnalysisUtils {
    fun analysisData(manifestAppPackage: String, type: DataType, data: String) {
        CoroutineUtils.withIO {
            val hash = MD5HashTable.md5(data)
            if (mD5HashTable.contains(hash)) return@withIO
            mD5HashTable.put(data)

            // 白名单/黑名单过滤
            val whitelist = ...
            val blacklist = ...
            if (...) return@withIO

            val result = JsAPI.analysis(type, data, manifestAppPackage)
        }
    }
}
```

- 去重：MD5。
- 过滤：白名单/黑名单。
- 上报：`JsAPI.analysis` → 本地 server → JS 规则 → `BillInfoModel`。
- 我们的插件不需要本地 server；把 `analysisData` 替换成 `CaptureIngestProvider.call(...)`，把 `BillInfoModel` 换成 `CapturePayload`。

## 5. 规则层（解析在哪）

AutoAccounting 的 hook 只负责“抓到原始 JSON”；金额/商户/支付方式的解析在 `:server` 的 JS 规则里：

- `RuleModel`：`app/type/js/name/systemRuleName/creator/struct/autoRecord/enabled`
- `RuleGenerator`：把规则拼成 JS，注入 `window.data`，执行 `rule.obj.get(data)`，要求返回 `money > 0`。
- `CategoryRuleModel`：分类 JS `getCategory(money,type,shopName,shopItem,time,ruleName)`。
- `AssetsMapModel`：资产名映射 `name → mapName`，支持 `regex`、`sort`。

```js
for (var i = 0; i < rules.length; i++) {
  var result = rule.obj.get(data);
  if (result && result.money && parseFloat(result.money) > 0) {
    result.ruleName = rule.name;
    print(JSON.stringify(result));
    break;
  }
}
```

💡 移植到我们插件时有两个选择：

1. **复用规则思路，不跑 AutoAccounting server**：把规则 JS 放插件本地，用 QuickJS 执行；或者把常用规则转成 Kotlin/正则。
2. **自写解析器**：针对微信/支付宝的支付成功页/消息，直接写字段提取；规则只用于兜底。

P0 建议先做“探针”：把原始 JSON 上报到插件，人工确认真实字段，再写解析器；不要一上来就猜金额字段。

---

## 6. 移植到 `capture-plugin` 的映射

| AutoAccounting | capture-plugin |
| --- | --- |
| `WechatHooker` / `AliPayHooker` | `PluginXposed` + `WechatHooker` / `AlipayHooker` |
| `PartHooker.analysisData` | `CaptureIngestProvider.call("ingest", token, payload)` |
| `DatabaseHooker` | 微信 WCDB `insertWithOnConflict` hook |
| `PayToolsHooker` | 微信 `MMKR RichText.appendText` 缓存 |
| `WebViewHooker` | 微信 `com.tencent.xweb.WebView.evaluateJavascript` |
| `MessageBoxHooker` | 支付宝 `SyncMessage.getData` |
| `Alipay WebViewHooker` | 支付宝 `H5WebView.evaluateJavascript` |
| `SecurityHooker` | 可选；个人使用需自行评估 |
| `AnalysisUtils` 去重/过滤 | 插件 `CaptureIngestProvider` 内去重 + 规则 |
| `BillInfoModel` | `CapturePayload` / `PaymentCapture` |
| `IAppAdapter.syncBill` | `CaptureBridgeProvider.openQuickRecord` / `importBills` |

### 6.1 探针模式建议

1. 在 `ProbeHooker` 里安装上述 hook，但**只上报原始 JSON**，不解析。
2. `CapturePayload` 先填：`source`、`paidAt=now`、`capturedAt=now`、`rawJson`；`amountCents=null`、`merchant=null`、`paymentMethodRaw=null`。
3. 插件列表显示“待补金额”；用户/开发者根据 `rawJson` 确认真实字段。
4. 稳定 3~5 笔后，再写解析器，把 `rawJson` 映射成 `amountCents/merchant/paymentMethodRaw/paidAt`。

### 6.2 注意

- **不要阻塞宿主 DB 线程**：`insertWithOnConflict` hook 里只做轻量缓存/投递；`ContentResolver.call` 放 IO，2 秒超时。
- **去重**：同一笔可能同时命中成功页、WCDB、WebView、通知；用 `sourceTxnId` 或 10–30 秒归并窗口。
- **版本适配**：类名/字段用锚点查找，不硬编码混淆名；每个 hook 独立 catch。
- **GPL**：上面的代码是参考；直接复制进插件会让插件受 GPL-3.0 约束。个人使用可以；分发前要么开源插件，要么重写实现。
- **支付宝安全 bypass**：可能违反 ToS/触发风控；个人使用自行评估，不建议默认开启。

---

## 7. 一句话

微信主力是 **WCDB `insertWithOnConflict` + `MMKR RichText.appendText` 缓存**，补充是 `WebView.evaluateJavascript`（`nativeWXPayCgiTunnel:ok`）、转账/红包类；支付宝主力是 **`SyncMessage.getData`**，H5 账单页用 **`H5WebView.evaluateJavascript` + `AlipayJSBridge` 注入**，可选 `ScanAttack` bypass。解析不在 hook 里，而在 JS 规则；我们的插件应把“原始 JSON → CapturePayload”这一步本地化。
