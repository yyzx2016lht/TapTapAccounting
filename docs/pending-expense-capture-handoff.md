# 技术交接：支付捕获插件（适配敲敲记账）

> 读者：另一个 AI / 技术评审。你不需要访问仓库；本文把两份源码里相关的部分摘出来，并给出架构提案。
> 配套需求：`docs/pending-expense-capture.md`（同目录，建议一起看）。
> 代码基线：
> - 敲敲记账（TapTapAccounting）：本地仓库 `a3d8dd0`，branch `tap`，`versionName 1.2` / `versionCode 2`，`minSdk 24` / `targetSdk 34`，release 开启 R8。
> - 自动记账（AutoAccounting）：GitHub `AutoAccountingOrg/AutoAccounting`，master，2026-11-02 抓取；模块 `:app/:server/:dex/:hook/:tap/:ocr/:shell`；根目录 `LICENSE` 为 GPL-3.0（部分文件头写 Apache-3.0，疑为笔误，复用前需核实）。
> 标注：✅ = 源码中确认的事实；💡 = 本文建议；❓ = 需要真机/评审确认。
> **v2 修订（2026-11-02）**：采纳评审意见——hook→插件改为 token 鉴权、App 侧 `external_imports`/`getImportStatus` 对账、`capture_id` 幂等与账单同事务、导入 ID 优先、无订单号不自动合并、补支付场景矩阵、状态机/格式修正；并按建议瘦身——**App 提供 AI 代理（不抽 `:ai-core`、不读 key）、插件不常驻 FGS、敲击计数缓存**。

---

## 0. 结论先行

💡 需求本质不是“自动记账”，而是**支付瞬间自动留痕、忙完人工确认**：

- 插件 hook 微信/支付宝，拿到金额、时间、支付方式、商户、订单号；
- 先存成“待记”，可以同时存很多笔；
- 用户有空时逐条补备注、分类，自己记或交给 AI；
- 最终通过敲敲记账现有的写入漏斗落成账单，**不自动入账**。

💡 推荐架构（B3 瘦身版）：

- **插件是独立 APK + LSPosed 模块**，自己持有待记数据库、通知、列表、资产映射、备份；**不常驻前台服务**；
- **敲敲记账只做账本/资产/分类、现有悬浮窗编辑器、AI 代理、最终账单写入**；
- 插件 → App 用 `signature` 保护的 ContentProvider；hook → 插件用 exported Provider + token（hook 在微信/支付宝 UID，过不了 signature）；App → 插件用计数广播；
- 插件**不 hook 敲敲记账**，不读 AI key；AI 分类由 App 的 `startClassify`/`getClassifyResult` 代理完成，插件只收草稿；
- 写账单统一走 App 的 `openQuickRecord` / `importBills`，复用 `AIAccountingSupport` + `BatchBillInserter` + `BillMutationService`，并用 `external_imports` 做幂等和对账。

❓ 请重点评审：

1. 微信到底 hook 哪里最稳：数据库插入、支付成功页、还是通知？
2. 支付宝 H5 JS Bridge 在 2026 年还可不可用？
3. `startClassify`/`getClassifyResult` 的异步代理是否比插件自建 AI 更合理？超时/进程被杀怎么兜底？
4. `capture_id` 幂等 + `external_imports` 同事务，能否覆盖批量部分成功与“自己记”回调丢失？
5. `BatchBillInserter` 从名称改 ID 优先，改动面有多大？
6. Android 14/15 下“不常驻 FGS、按需拉起进程”是否可靠？包可见性/后台限制有什么坑？
7. B3（插件持有收件箱）是否过度设计？A 方案（收件箱放 App）是否更合理？
8. 个人收款码、付款码、小程序 `:appbrand`、App 内支付、免密、组合支付、立减、亲属卡，哪些第一版必须覆盖？

### 0.1 评审意见处理

| 评审项 | 处理 |
| --- | --- |
| 1. hook→插件鉴权 | 采纳：token Provider + IO 超时 + 广播兜底 + `<queries>` |
| 2. `BILL_CREATED` 丢消息 | 采纳：`external_imports` + `getImportStatus` 对账 |
| 3. 幂等同事务 | 采纳：`capture_id` 幂等，和账单同事务；`request_id` 只追踪 |
| 4. 分类/资产 ID 优先 | 采纳：`BatchBillInserter` 扩展 ID；名称只校验 |
| 5. 后台拉起悬浮窗 | 部分采纳：P0 先走通知点开；`openQuickRecord` 真机验证，必要时改 WindowManager/Activity |
| 6. App 做 AI 代理 | 采纳：`startClassify`/`getClassifyResult`；删 `:ai-core`/facade/key |
| 7. 不常驻 FGS | 采纳：普通常驻通知 + 按需拉起 |
| 8. 敲击计数缓存 | 采纳：插件推送 + App 缓存 + 过期刷新 |
| 9. B3 vs A | 保留 B3（插件独立升级/复用是目标），但按 6/7/8 瘦身；A 作为备选记录 |
| 10. 个人收款码 | 采纳：付款给个人算支出；探针覆盖 |
| 11. 支付场景矩阵 | 采纳：见 6.3 |
| 12. 去重规则 | 采纳：无订单号不合并；只对 `captureId` 自动关联 |
| 13. 通知动作/商户记忆 | 采纳到 P1：RemoteInput 备注/忽略、商户→分类/资产 |
| 14. 文档格式/状态机/闹钟 | 采纳：毫秒/分、POSSIBLE_DUPLICATE、WorkManager、P0 缩小 |

---

## 1. 需求摘要（技术语言）

### 1.1 用户场景

菜市场连续消费：17:00 付 3 元、17:04 付 12 元，用户没空记；晚上要打开微信/支付宝账单逐条回忆。目标是把“支付事件”自动变成“待记条目”，把“补备注/分类”延后。

### 1.2 功能清单

| 能力 | 要求 |
| --- | --- |
| 捕获来源 | 微信 + 支付宝（第一版）；只记支出 |
| 自动字段 | 金额、支付时间、支付方式（零钱/银行卡/花呗）、商户（有则记）、订单号（有则记） |
| 待记 | 可同时存多笔；一直保留；每晚汇总提醒 |
| 当下提示 | P0：通知/静默；P1：气泡/完整卡片；默认通知 |
| 通知栏 | 普通常驻“N 笔待记”；锁屏不显示金额；不常驻 FGS |
| 敲击入口 | P1：App 用缓存的待记数量判断；P0 先走通知点开 |
| 单条处理 | “自己记”复用 App 悬浮窗；“AI 记”由 App 的 AI 代理返回草稿 |
| 批量 AI | 逐条补备注 → App 代理分类 → 草稿 → 一键入账 |
| 资产 | 插件保存原始支付方式，映射到 App 资产；映射可管理 |
| 去重 | 订单号优先；无订单号不自动合并；`capture_id` 幂等；只对带 `captureId` 的账单自动关联 |
| 截图 | 不做 |
| 分发 | 个人使用；固定微信/支付宝版本；GPL 规则只作参考 |

### 1.3 非目标

- 不自动入账；不静默写账单。
- 不做退款/转账/收入/红包。
- 不做无 root 方案。
- 不在插件里重写账单编辑器（自己记仍用 App 的悬浮窗）。
- 不做商店发布。

---

## 2. 源码 A：AutoAccounting（自动记账）

### 2.1 它是什么

✅ 一个独立的 Xposed/LSPatch 应用，hook 支付类 App，把捕获到的数据交给**本地服务端**做规则匹配/AI 识别，生成 `BillInfoModel`，再通过 `IAppAdapter` 同步到钱迹等记账 App。

✅ 模块：

```text
:app      Xposed 入口、hook、UI、规则管理、适配器
:server   本地服务端：Room、JS 规则引擎、AI、HTTP 路由
:dex      类查找/版本适配规则
:hook     独立 hook 模块
:tap      独立 tap 模块
:ocr      OCR
:shell    shell
```

✅ 数据流：

```text
微信/支付宝/Android 通知
  └─ PartHooker 产生 raw JSON
       └─ AnalysisUtils.analysisData(pkg, type, data)
            ├─ MD5 去重
            ├─ 白名单/黑名单过滤
            └─ JsAPI.analysis(...) → 本地 server
                 └─ RuleGenerator 拼 JS + JsExecutor(QuickJS)
                      └─ 返回 BillInfoModel
                           └─ AppAdapterManager.adapter().syncBill(...)
                                └─ 钱迹 URI scheme / 其他记账 App
```

### 2.2 Xposed 骨架（可借鉴）

✅ `HookerManifest`：每个目标 App 一个 manifest，声明包名、Application 类名、`partHookers`、类查找规则 `rules`、最低版本、需要附加的权限。

```kotlin
abstract class HookerManifest {
    abstract val packageName: String
    abstract val appName: String
    abstract fun hookLoadPackage()
    abstract var partHookers: MutableList<PartHooker>
    abstract var rules: MutableList<Clazz>
    open var applicationName: String = "android.app.Application"
}
```

✅ `PartHooker`：一个功能一个 hooker，统一走 `analysisData(type, data)` 上报。

```kotlin
abstract class PartHooker : ILogger by XposedLogger {
    protected val manifest by lazy { AppRuntime.manifest }
    fun analysisData(type: DataType, data: String) {
        AnalysisUtils.analysisData(manifest.packageName, type, data)
    }
    open fun hook() {}
}
```

✅ `Hooker` 工具：`after/before/replace/allMethodsEqBefore/onceAfter` 等；所有 hook 失败都 catch `Throwable`（包括 `LinkageError`），避免宿主版本变化导致模块崩溃。

✅ `XposedModule.get()` 注册所有 hooker：`AndroidHooker`、`AutoHooker`、`QianjiHooker`、`WechatHooker`、`AliPayHooker`、`SmsHooker`、`BluetoothHook`。

💡 可借鉴：per-app manifest + per-feature PartHooker + 统一失败捕获 + 类查找规则。比把所有 hook 写在一个类里更容易定位“哪个版本坏了”。

### 2.3 微信 hook（关键）

✅ `WechatHooker`：

- 包名 `com.tencent.mm`；
- Application `com.tencent.tinker.loader.app.TinkerApplication`；
- 会检查 `/data/data/com.tencent.mm/tinker/`，有 `patch-` 就调用 Tinker API 清补丁、关 Tinker，避免热更新导致 hook 失效；
- partHookers：`TransferHooker`、`DatabaseHooker`、`RedPackageHooker`、`ChatUserHooker`、`PayToolsHooker`、`WebViewHooker`。

✅ `DatabaseHooker`（微信 8.0.43 分析）：

```kotlin
val database = Hooker.loader("com.tencent.wcdb.database.SQLiteDatabase")
Hooker.after(database, "insertWithOnConflict",
    String::class.java, String::class.java,
    ContentValues::class.java, Int::class.javaPrimitiveType!!) { param ->
  val tableName = param.args[0] as String
  val contentValues = param.args[2] as ContentValues
  if (tableName != "message" && tableName != "AppMessage") return@after
  val type = contentValues.getAsInteger("type") ?: return@after
  contentValues.put("t", System.currentTimeMillis()) // 微信常缺时间
  // message: 318767153 消息盒子 / 419430449 转账 / 10000 群系统 / 436207665 红包
  // AppMessage: 5 文章 / 2000 转账
}
```

- 消息盒子：把 `content` 的 XML 转 JSON，取 `msg.appmsg.des`（描述）、`title`、`mmreader.template_header.display_name`、`appinfo.appname`，塞进 `mMap` 上报。
- 转账/红包/群收款：保留 XML/ContentValues，附上 `talker`、`isSend`、`PayToolsHooker` 缓存。
- 去重：`MD5HashTable`（30k）按描述/内容 MD5 去重。

✅ `PayToolsHooker`：hook `com.tencent.kinda.framework.widget.base.MMKRichText#appendText(String)`，用正则把文本分成：

- 支付方式：`.*(卡|零钱|经营账户).*`
- 金额：`.*([￥$]).*`
- 收款方：`.*(转账|红包|付款给).*`

缓存 120 秒（`MemoryCache`），随后由 `DatabaseHooker` 的 `putCache()` 附到消息 JSON 上。

💡 这是“金额/支付方式不在同一条消息里”的典型解法：短时缓存 + 下一条消息归并。做支付成功页 hook 也可以复用这个思路（金额、商户、支付方式可能在不同 View）。

⚠️ Tinker 清补丁是激进操作，可能影响微信自身；个人使用可以接受，但不要默认照搬。❓ 需要评估微信新版本是否仍需要。

### 2.4 支付宝 hook（关键）

✅ `AliPayHooker`：

- 包名 `com.eg.android.AlipayGphone`；
- Application `com.alipay.mobile.quinox.LauncherApplication`；
- partHookers：`MessageBoxHooker`、`WebViewHooker`、`SecurityHooker`。

✅ `MessageBoxHooker`：hook `com.alipay.mobile.rome.longlinkservice.syncmodel.SyncMessage` 的 `getData()` 和 `toString()`；`toString` 只负责触发 `getData`；`getData` 返回 JSON 数组，逐条 `analysisData(DataType.DATA, ...)`。

✅ `WebViewHooker`：hook `com.alipay.mobile.nebulacore.web.H5WebView#evaluateJavascript(String, ValueCallback)`：

- 只处理 URL 含 `tradeNo=` 的页面；
- 检测到支付宝账单页后，注入 JS 包装 `AlipayJSBridge.call`，把回调里的 `ariverRpcTraceId` 结果存到 `window.ankioResults`；
- 之后每 100ms 轮询 `javascript:window.ankioResults`，最长 120s；拿到结果就 `analysisData`。

✅ `SecurityHooker`：hook `com.alipay.apmobilesecuritysdk.scanattack.common.ScanAttack`，把所有 Boolean 检测结果改成 false；`getAD104` 只检测虚拟机。⚠️ 这是对抗环境检测，个人使用可接受，但风险/合规要自己判断。

💡 支付宝没有像微信那样 hook 数据库，而是消息盒子 + H5。支付成功页若是 H5，JS Bridge 是可行路线；若是原生页，需要另找 hook 点。❓ 2026 年支付宝版本是否仍适用要真机验证。

### 2.5 Android 通知 hook（补充来源）

✅ `NotificationHooker`：

- hook `com.android.server.notification.NotificationManagerService#enqueueNotificationInternal` 和 `#notifyCallNotificationEventListenerOnPosted`；
- 取 `Notification.extras` 的 `EXTRA_TITLE`、`EXTRA_BIG_TEXT`/`EXTRA_TEXT`/`EXTRA_SUB_TEXT`；
- 按 `pkg+title+text` MD5 去重；
- 白名单过滤（`Setting.LISTENER_APP_LIST`）；
- 短信走 `DataType.DATA`，其他走 `DataType.NOTICE`。

⚠️ 这需要 hook Android System（system_server），比 hook 微信更重；`❓` 是否比 `NotificationListenerService` 更稳需要评估。个人使用可作银行/美团等补充来源，第一版不做。

### 2.6 规则引擎（关键）

✅ `RuleModel`（Room）：

```kotlin
@Entity class RuleModel {
  var id = 0
  var app = ""          // 包名
  var type = ""         // DATA / NOTICE
  var js = ""           // 规则 JS
  var name = ""         // 规则名
  var systemRuleName = ""
  var creator = ""      // system / user
  var struct = ""       // 结构规则（system 为空）
  var autoRecord = false
  var enabled = true
}
```

✅ `RuleGenerator`：把 `common.js` + 每条 `rule.js` 拼成一段 JS，注入 `window.data`，然后：

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

✅ `JsExecutor`：QuickJS（`com.shiqi.quickjs`）；每次执行独立 Runtime/Context，注入 `print` 和 `data`，返回 `evaluate` 或 `print` 收集结果。

✅ 分类规则：`CategoryRuleModel.js`，注入 `getCategory(money, type, shopName, shopItem, time, ruleName)`；先跑用户自定义规则，再跑系统分类规则。返回 `{category...}`。

✅ 资产映射：`AssetsMapModel`：

```kotlin
@Entity(indices = [Index(value = ["name"], unique = true)])
class AssetsMapModel {
  var regex: Boolean = false
  var name: String = ""      // 原始账户名
  var mapName: String = ""   // 映射到的账户名
  var sort: Int = 0          // 越小越优先
}
```

💡 这套规则引擎是 AutoAccounting 的核心资产：JS 规则 + 分类规则 + 资产映射。但它是**服务端**（`:server`）里的 QuickJS，且规则来自订阅/云端。插件若复用，需要：
- 在 Android 内嵌 QuickJS（或把规则转成 Kotlin/正则）；
- 规则存储、版本、启用/停用、测试页；
- 处理 GPL-3.0 许可证。

### 2.7 账单模型

✅ `BillInfoModel`（Room）关键字段：

```kotlin
@Entity data class BillInfoModel(
  var id: Long = 0,
  var type: BillType = BillType.Income,   // Expend/Income/Transfer/...
  var currency: String = "",
  var money: Double = 0.0,
  var fee: Double = 0.0,
  var time: Long = 0,
  var shopName: String = "",
  var shopItem: String = "",
  var cateName: String = "",
  var extendData: String = "",
  var bookName: String = "默认账本",
  var accountNameFrom: String = "",
  var accountNameTo: String = "",
  var app: String = "",                   // 来源 App
  var groupId: Long = -1,                 // 同一次扣款的合并组
  var channel: String = "",               // 更细的渠道
  var state: BillState = BillState.Wait2Edit,
  var remark: String = "",
  var auto: Boolean = false,
  var ruleName: String = "",
  var tags: String = "",
  var flag: Int = 0,
  var rawAccountNameFrom: String = "",
  var rawAccountNameTo: String = ""
)
```

✅ `BillState`：`Synced` / `Wait2Edit` / `Edited`。`Wait2Edit` 就是“待记/待编辑”。

✅ `BillTool.saveBill`：先存本地，`state=Edited`；未开启手动同步时按阈值/实时调用 `adapter.syncBill`；钱迹 Xposed 模式会 `waitSynced` 等回执 5 秒，避免多笔并发串单。

💡 这套“本地先存、状态机、再同步、等回执”的模型可以直接映射到本需求：`Wait2Edit` → `PENDING`，`Synced` → `RECORDED`，`groupId` → 去重/合并。

### 2.8 目标 App 适配器（关键）

✅ `IAppAdapter`：

```kotlin
interface IAppAdapter {
  val pkg: String
  val link: String
  val icon: String
  val desc: String
  val name: String
  fun supportSyncAssets(): Boolean
  fun features(): List<BookFeatures>
  fun syncAssets()
  fun syncWaitBills(billAction: BillAction, bookName: String)
  fun syncBill(billInfoModel: BillInfoModel)
  fun sleep(): Long
}
```

✅ `QianJiAdapter.syncBill`：拼 `qianji://publicapi/addbill?type=...&money=...&time=...&remark=...&catename=...&accountname=...&bookname=...&fee=...&currency=...`，用隐式 Intent 拉起钱迹；Xposed 模式下钱迹写库成功后会回写 `Synced`，非 Xposed 只能拉起即标记。

✅ `AppAdapterManager.adapter()` 按 `PrefManager.bookApp` 选适配器，默认 `AutoAdapter`（只记在本应用）。

💡 对本项目：不需要通用 `IAppAdapter`，只需要一个 `TapBridge` 直接调 App 的 `importBills`。但“适配器”边界值得保留：以后想适配别的记账 App，只换 Bridge 实现。

### 2.9 AutoAccounting 可复用 / 不可直接复用

| 部分 | 结论 |
| --- | --- |
| `HookerManifest`/`PartHooker`/`Hooker` 失败捕获 | ✅ 直接借鉴结构 |
| 微信 DB insert + RichText 缓存 | ✅ 最值得参考的 hook 点 |
| 支付宝 SyncMessage + H5 JS Bridge | ✅ 可参考，但需真机验证 |
| Android 通知 hook | ⚠️ 太重，第一版不做；可换 NotificationListenerService |
| JS 规则 + 分类规则 + 资产映射 | ✅ 思路可复用；代码/规则受 GPL 约束 |
| `BillInfoModel` 状态机/groupId | ✅ 可映射到 PaymentCapture |
| `QianJiAdapter` URI scheme | ❌ 敲敲记账没有 URI scheme；改用 Provider |
| Tinker 清补丁 / 支付宝安全 bypass | ⚠️ 激进，个人使用自行评估，不建议默认 |
| 云端规则/本地 server | ❌ 对本项目过重；插件应本地化 |

### 2.10 如果复用订阅规则

✅ 规则是 JS：输入 `data`，输出至少包含 `money` 的对象；`RuleGenerator` 按顺序跑，第一条 `money > 0` 即停。
✅ 分类是另一套 JS：`getCategory(money, type, shopName, shopItem, time, ruleName)`。
✅ 资产映射是 `AssetsMapModel`：`name → mapName`，支持 `regex` 和 `sort`。

💡 落地步骤：

1. 从 AutoAccounting 导出/同步 `RuleModel`（`app/type/js/name/systemRuleName/creator/struct/autoRecord/enabled`）。
2. 插件本地表存规则，按 `app + type` 取。
3. 内嵌 JS 引擎（QuickJS）或把规则转成 Kotlin/正则；第一版建议只支持 `struct` 或简单正则，复杂 JS 后置。
4. 捕获 JSON → `data` → 规则 → 规范化字段（`money/time/shopName/shopItem/accountNameFrom/cateName`）。
5. 分类：`getCategory` 或本地规则；资产：`AssetsMapModel`。
6. 规则失败/空 → 生成“待补”条目，不丢事件。
7. 规则版本、启用/停用、测试页；标注 system/user 来源。
8. GPL：个人使用可以；分发前必须处理许可证，规则层保持可替换，不把规则硬编码进闭源 APK。

⚠️ 不要直接依赖 AutoAccounting 的本地 server/HTTP；插件应本地执行。

---

## 3. 源码 B：敲敲记账（TapTapAccounting）

### 3.1 构建与结构

✅ `app/build.gradle.kts`：

- `applicationId com.taostudio.tapaccounting`
- `compileSdk 34` / `targetSdk 34` / `minSdk 24`
- `versionCode 2` / `versionName 1.2`
- release `isMinifyEnabled = true`（R8）
- Room、Gson/Retrofit/OkHttp、Media3、TensorFlow Lite、Shizuku 等。

✅ `AndroidManifest.xml` 关键项：

- 权限：`SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`、`RECORD_AUDIO`、`RECEIVE_BOOT_COMPLETED`、`QUERY_ALL_PACKAGES` 等。
- `OverlayService`：`exported=false`，`foregroundServiceType="health|microphone|specialUse"`，`stopWithTask=false`。
- `KeepAliveAccessibilityService`：`BIND_ACCESSIBILITY_SERVICE`。
- `QuickStartTileService`：快捷设置磁贴。
- `BootReceiver`：开机/应用更新。
- 大量 Activity（记账、资产、分类、AI 配置、导入等）。

### 3.2 数据模型

✅ `Bill`（Room，`bills`）：

```kotlin
@Entity(tableName = "bills")
data class Bill(
  val id: Long = 0,
  val type: Int,                 // 0 支出 / 1 收入 / 2 转账 / 3 还款
  val subType: Int = 0,          // 1 还款 / 2 退款 / ...
  val amount: Double,
  val originalAmount: Double = amount,
  val currency: String = "CNY",
  val exchangeRate: Double = 1.0,
  val categoryId: Long? = null,
  val accountId: Long? = null,
  val toAccountId: Long? = null,
  val categoryName: String = "",
  val accountName: String = "",
  val toAccountName: String = "",
  val time: Long,
  val remark: String = "",
  val fee: Double = 0.0,
  val bookName: String = "日常账本",
  val relatedBillId: Long? = null,
  val excludeFromStats: Boolean = false,
  val paymentGroupId: String? = null
)
```

✅ `Asset`（`assets`）：`id/name/type/balance/currency/icon/assetCategory`；`assetCategory` 有 `FUND`、`CREDIT_CARD`、`RECHARGE`、`INVESTMENT`；`type` 是展示类型（如“招商银行”“微信”“支付宝”）。

✅ `Category`（`categories`）：`id/name/type(0支出 1收入)/parentId/iconId/sortOrder`；二级分类通过 `parentId` 关联。

✅ `Book`（`books`）：`id/name`，name 唯一；另有 `BookAccountManager` 用 SharedPreferences 管理账本列表/当前账本/默认账本。

### 3.3 AI 记账链路（重点）

✅ 配置存储：

- `Prefs.getAiKey(ctx)` → `PrefsAiSupport.getAiKey` → `readSecret(prefs, KEY_AI_KEY, KEY_AI_KEY_ENC)`；
- `Prefs.getAiProviderKey(ctx, providerId)` → 按 provider 取 key；
- `Prefs.getAiUrl(ctx)`、`Prefs.getAiModel(ctx)`、`Prefs.getAiProvider(ctx)`；
- `Prefs.isAiConfigured(ctx)`；
- `KeystoreSecretBox`：Android Keystore AES-GCM，alias `flip_ai_secrets`；密文存 SharedPreferences，明文不落盘。

✅ Provider：`AiProviderRegistry` 内置硅基流动、OpenCode Go、DeepSeek、Kimi、通义千问、小米 MiMo 等 OpenAI 兼容预设；`SiliconFlowApi` 用 Retrofit 调 `v1/chat/completions`、`v1/models`、`v1/audio/transcriptions`。

✅ `buildAccountingPromptContext(ctx)`（`AIAccountingSupport.kt`）从 Room/SharedPreferences 收集：

- 资产列表（过滤 `isArchived`；`assetCategory` 区分信用卡；非 CNY 币种）；
- 支出/收入分类树（`CategoryRepository.getCategoryTree`，压平成 `父 - 子`）；
- 当前时间字符串；
- 可用账本；
- `assetFeatureEnabled`。

✅ `AIService.analyzeAccounting(ctx, userInput, ...)`：

1. 取 `Prefs.getAiKey`、`AiModelSlots.resolveTextModel`；
2. `buildAccountingPromptContext`；
3. 加载用户提示词规则（`AiRule`）；
4. `buildAccountingSystemPrompt` + `buildAccountingUserPrompt`；
5. 流式请求 OpenAI 兼容接口；
6. `parseAnalyzeResult` 解析 JSON；
7. `normalizeAccountingWithLocalRules` → `normalizeAccountingResult`；
8. 返回 `JSONObject`。

✅ 输出 JSON schema（`AIPrompts.kt`）：

```json
{
  "single_payment": true,
  "bills": [
    {
      "amount": 12.34,
      "type": 0,
      "asset_name": "微信零钱",
      "category_id": "<候选id>",
      "book_id": "<明确指定账本时的候选id>",
      "time": "2026-06-15 12:30:00",
      "remarks": "黄焖鸡米饭",
      "currency": "CNY",
      "to_asset_name": "",
      "fee": 0.0
    }
  ]
}
```

- 支出/收入用 `category_id`（必须逐字复制候选 id），不输出 `category_name`；
- 资产只能从资产库选；无法匹配留空；
- `single_payment` 判断“这张图是不是一次消费的凭证”。

✅ `normalizeAccountingResult`：

- 修正 `type`（>2 归为转账/还款；无资产功能时只保留支出/收入）；
- 时间规范化（缺时间用当前时间，按 index 递增 1 秒）；
- 资产币种继承；
- `category_id` → `category_name`（通过候选表）；
- 旧格式 `category_name` 做 `> / /::/ / /` → ` - `，匹配候选，匹配不到落“其他”；
- 账本名规范化。

✅ 写入漏斗：

```kotlin
BatchBillInserter.insertBills(ctx, json, billsArray, fallbackBookName, isAssetFeatureEnabled)
  → 逐条 resolve 账本
  → type 裁剪/转账降级
  → asset_name → Asset（accountId/accountName）
  → category_name → leaf name → categoryId
  → Bill(...)
  → BillMutationService.insertBillAndApplyImpact(db, bill)
```

✅ `BillMutationService.insertBillAndApplyImpact`：

- `db.withTransaction`；
- 分类关联对齐（`categoryId` 权威，`categoryName` 快照）；
- 校验汇率；
- `billDao.insertBill`；
- `BillAssetImpactService.applyBillBalanceImpact` 更新资产余额；
- `SharedMutationHooks.enqueueSaved` 共享账本同步；
- `matchRecurringQuietly` 周期账单匹配；
- 通知桌面小组件。

💡 这是“账单写入的唯一漏斗”。插件绝不应该直接 `INSERT bills`；必须走 `importBills` → `BatchBillInserter` → `BillMutationService`，否则资产余额、共享同步、周期账单都会漏。

### 3.4 悬浮窗与预填（重点）

✅ `OverlayManager.showOverlay(prefill: JSONObject?, showSaveOnly: Boolean)`：

- 检查 `SYSTEM_ALERT_WINDOW`；
- 创建 `TYPE_APPLICATION_OVERLAY` 窗口（底部、90% 宽、`FLAG_NOT_TOUCH_MODAL`）；
- `setupLogic(view, prefill, showSaveOnly)`；
- 支持 `removeOverlay`、进入/退出动画、外部点击关闭。

✅ 预填 JSON 字段（由 `AccountingFormController.fillDataToUi` 读取）：

```json
{
  "type": 0,
  "amount": 3.0,
  "currency": "CNY",
  "asset_name": "微信零钱",
  "category_name": "餐饮",
  "time": "2026-11-02 17:00:00",
  "remarks": "青菜",
  "book_name": "日常账本"
}
```

- `remarks` 或 `remark` 都会作为备注；
- `asset_name`/`account`、`to_asset_name`/`to_account` 兼容旧字段；
- 多笔用 `{"bills":[...]}` + `forceMultiMode`；`pendingBills` 队列逐条 `processNextPendingBill()`。

✅ `AiAssistant`：

- `EXTRA_BATCH_SAVE_ALL = "batch_save_all"`（一键全部入账，直接 `BatchBillInserter`）；
- `EXTRA_FORCE_BILL_QUEUE = "force_bill_queue"`（逐条确认队列）；
- `KEY_SINGLE_PAYMENT = "single_payment"`；
- `handleAiResult` 在 `OverlayManager` 里处理 `bills[]`：显式批量 → `saveBillsDirectly`；否则按设置静默保存或填表/排队。

💡 插件“自己记”可以直接调 `showOverlay(prefill)`；插件“AI 记/批量”如果只想出草稿，可以在插件内展示，确认后调 `importBills`，不一定要走 App 的 `AiAssistant` 弹窗。

### 3.5 可复用的 App 能力 / 需要改的点

| 能力 | 现状 | 插件方案 |
| --- | --- | --- |
| 悬浮窗表单 | `OverlayManager.showOverlay(prefill)` | 直接复用；Provider 内启动 |
| 多笔队列 | `pendingBills` / `processNextPendingBill` | 可选；插件列表自己管 |
| AI 多账单确认 | `AiAssistant` + `BatchBillInserter` | 插件只展示草稿；分类由 App 代理 |
| 账单写入 | `BatchBillInserter` + `BillMutationService` | 必须复用；不直接写 Room |
| AI 上下文 | `buildAccountingPromptContext` + `AIPrompts` | App 内部使用；插件不抽 `:ai-core`，只调 `startClassify` |
| AI key | `PrefsAiSupport` + `KeystoreSecretBox` | 插件不读取；完全留在 App |
| 账单查询 | `BillDao.getBillsBetweenTimesList` | 用于去重/疑似提示 |
| 账本/资产/分类 | `BookAccountManager` / `Asset` / `Category` | Provider 暴露只读上下文 |
| 通知/悬浮窗 | `OverlayService` / 权限 | 插件普通通知；P1 气泡 |
| 备份 | `DataExportManager` | App 备份不含插件待记；插件自己导出 |

---

## 4. 提案架构（B3 瘦身版）

### 4.1 模块

```text
:capture-plugin     独立 APK（LSPosed 模块）：hook、规则、Room、通知、列表、AI 代理客户端、Bridge 客户端
:capture-contract   共享库：JSON schema、字段常量、权限名、contractVersion
:app                现有 App：Bridge Provider、AI 代理、导入、预填、敲击缓存、设置
```

💡 建议包名：`com.taostudio.tapaccounting.capture`；与 App 同签名；LSPosed 只勾微信、支付宝，**不勾敲敲记账**。

### 4.2 进程与数据流

```text
微信 / 支付宝进程
  └─ 插件 hook（LSPosed）
       ├─ 支付成功页 / DB 插入 / WebView
       ├─ 解析为 Capture JSON
       └─ CaptureIngestProvider.call（token，IO 线程，2s 超时）
            └─ 插件主进程
                 ├─ Room：payment_captures / capture_asset_mappings
                 ├─ 普通常驻通知 "N 笔待记"（不常驻 FGS）
                 ├─ 待记列表 / 单条卡片 / 备注
                 ├─ 本地草稿 / AI 草稿展示
                 ├─ 计数推送（PENDING_COUNT_CHANGED → App）
                 └─ Bridge 客户端
                      ├─ getAccountingContext
                      ├─ getRecentBills
                      ├─ openQuickRecord
                      ├─ startClassify / getClassifyResult
                      ├─ importBills
                      └─ getImportStatus
                           └─ 敲敲记账
                                ├─ 现有悬浮窗表单
                                ├─ AIService（现有 key/prompt/上下文）
                                ├─ BatchBillInserter
                                ├─ BillMutationService（资产影响/共享同步/周期匹配）
                                └─ bills + external_imports
```

💡 没有“插件 hook 敲敲记账”这一层，也没有插件前台服务。插件进程按需被 Provider/广播拉起；通知本身由 NotificationManager 持有，进程被杀后仍在。

### 4.3 职责边界

| 能力 | 插件 | 敲敲记账 |
| --- | --- | --- |
| hook 微信/支付宝 | ✓ | |
| 解析/规则/版本适配 | ✓ | |
| 待记数据库/收件箱 | ✓ | |
| 通知/列表/备注 | ✓ | |
| 资产映射/去重 | ✓ | |
| AI 草稿展示/批量编辑 | ✓ | |
| 插件备份/导出 | ✓ | |
| AI 分类（key/prompt/上下文） | 调用代理 | ✓ |
| 账本/资产/分类 | 查询 | ✓ |
| 现有记账表单 | 调用 | ✓ |
| 最终账单写入 | 调用 `importBills`/`openQuickRecord` | ✓ |
| 资产影响/共享同步/周期匹配 | | ✓ |
| 敲击入口/动作分发 | 接收信号 | ✓ |

---

## 5. 接口契约（建议）

### 5.1 插件 → App：`CaptureBridgeProvider`（`signature` 权限）

#### `ping()`

```json
{ "contractVersion": 1, "appVersion": "1.2", "importEnabled": true, "aiEnabled": true }
```

#### `getAccountingContext()`

```json
{
  "contractVersion": 1,
  "defaultBookName": "日常账本",
  "assetFeatureEnabled": true,
  "books": [
    { "id": 1, "name": "日常账本" }
  ],
  "assets": [
    { "id": 1, "name": "微信零钱", "type": "微信", "assetCategory": "FUND", "currency": "CNY" },
    { "id": 2, "name": "招行储蓄卡(1234)", "type": "招商银行", "assetCategory": "FUND", "currency": "CNY" },
    { "id": 3, "name": "花呗", "type": "支付宝", "assetCategory": "CREDIT_CARD", "currency": "CNY" }
  ],
  "categories": [
    { "id": 10, "name": "餐饮", "type": 0, "parentId": null },
    { "id": 11, "name": "买菜", "type": 0, "parentId": 10 }
  ]
}
```

- 资产：`assetDao.getAllAssetsList()` 过滤 `isArchived`；
- 分类：`CategoryRepository.getCategoryTree(0/1)`；插件可自行压平成 `父 - 子`；
- 账本：`BookAccountManager.getBookAccounts` + `books` 表；
- `assetFeatureEnabled = Prefs.isAssetFeatureEnabled`。

#### `getRecentBills(paidAt, windowMinutes, limit)`

```json
[
  { "billId": 123, "amountCents": 300, "paidAt": 1762074000000, "accountId": 1, "accountName": "微信零钱", "type": 0 }
]
```

- 用 `BillDao.getBillsBetweenTimesList(paidAt - window, paidAt + window)`；只取支出；限 200 条；
- 只用于提示“可能已记”，不自动关联/删除。

#### `openQuickRecord(prefillJson, captureId)`

- App 在自己的进程内启动 `OverlayService` / `OverlayManager.showOverlay(prefill)`；
- 因为 Provider 在 App 进程，能启动非 exported 的 Service；
- 返回 `{ "accepted": true }`；
- 表单保存成功后，App 在同一个事务里写 `external_imports(capture_id, bill_id)`，并广播 `BILL_CREATED`；
- 用户取消不写行、不广播，插件保持 `PENDING`；
- 广播丢失也不影响：插件用 `getImportStatus` 对账。

#### `startClassify(capturesJson)` / `getClassifyResult(jobId)`

请求：

```json
{
  "captures": [
    {
      "capture_id": "uuid",
      "amount_cents": 300,
      "paid_at": 1762074000000,
      "source": "WECHAT",
      "merchant": "菜摊",
      "payment_method_raw": "零钱",
      "note": "青菜"
    }
  ]
}
```

响应：

```json
{ "jobId": "uuid" }
```

```json
{ "status": "RUNNING", "drafts": [], "error": "" }
```

- App 用现有 `AIService.analyzeAccounting` + `buildAccountingPromptContext` + 自己的 key；**不返回 key**。
- 只返回草稿，不写库；草稿里的 `category_id`/`asset_id` 必须来自 App 的候选集。
- 异步：插件轮询 `getClassifyResult`（500ms~1s，超时 60s）或等 `AI_DRAFTS_READY` 广播。
- App 未配置 AI/超时/进程被杀 → `FAILED`；插件生成本地草稿（金额/时间/资产/备注，分类为空）。

#### `importBills(billsJson, requestId)`

请求：

```json
{
  "request_id": "uuid",
  "bills": [
    {
      "capture_id": "uuid",
      "type": 0,
      "amount_cents": 300,
      "currency": "CNY",
      "paid_at": 1762074000000,
      "asset_id": 1,
      "asset_name": "微信零钱",
      "category_id": 11,
      "category_name": "餐饮 - 买菜",
      "remarks": "青菜",
      "book_name": "日常账本",
      "fee_cents": 0
    }
  ]
}
```

响应：

```json
{
  "ok": true,
  "request_id": "uuid",
  "results": [
    { "capture_id": "uuid", "bill_id": 456, "status": "created" }
  ]
}
```

💡 实现建议：

1. 校验 schema、限制最多 200 条、金额/类型白名单。
2. **ID 优先**：`asset_id`/`category_id` 必须存在；`asset_name`/`category_name` 只做快照/校验，不一致报错。`BatchBillInserter` 需要扩展支持 ID。
3. `AIAccountingSupport.normalizeAccountingResult` 目前会把 `category_id` 转成 `category_name`；导入路径要保留 ID，或先按 ID 解析再规范化。
4. `capture_id` 是幂等键；`external_imports(capture_id PK, bill_id, request_id, created_at)` 与账单写入**同事务**；重复请求返回已有 `billId`。
5. `request_id` 只做批次追踪/日志，不做幂等键。
6. 逐条返回 `status`（created/duplicate/error），部分失败不影响成功项；插件只重试失败项。
7. 资产/分类找不到时明确报错，不要静默落“其他”或空资产（除非产品明确允许）。

#### `getImportStatus(captureIds)`

```json
[
  { "captureId": "uuid", "status": "RECORDED", "billId": 456, "importedAt": 1762074123000 }
]
```

- 查 `external_imports`；状态：`RECORDED` / `IMPORTING` / `UNKNOWN`。
- 插件启动、打开列表、`IMPORTING` 超时后调用，用于对账/恢复；广播只加速。

### 5.2 App → 插件

#### `getPendingCount()`

```json
{ "count": 3, "updatedAt": 1762074000000 }
```

- `signature` 权限；App 启动/恢复时刷新缓存；
- **敲击时不再同步调用**；App 用缓存数量，超时/过期才刷新。

#### `PENDING_COUNT_CHANGED`

- 插件在新增/处理/忽略/恢复后广播 `{count, updatedAt}`；
- App 存本地；敲击时零延迟判断。

#### `ACTION_SHOW_PENDING`

- 显式广播/启动插件列表；App 不再弹自己的空白悬浮窗；
- 插件列表“先不记” → 调 `openQuickRecord(null, null)` 回 App 空白记账。

#### `BILL_CREATED`

```json
{ "billId": 456, "amountCents": 300, "paidAt": 1762074000000, "accountId": 1, "accountName": "微信零钱", "captureId": "uuid?" }
```

- App 账单保存后广播；**只用于加速**；
- 插件按 `captureId` 回填；没有 `captureId` 的手动账单只标 `POSSIBLE_DUPLICATE`，不自动 `DUPLICATE`；
- 广播丢失时用 `getImportStatus` / `getRecentBills` 对账。

### 5.3 hook → 插件：`CaptureIngestProvider`（token）

- hook 进程运行在微信/支付宝 UID，**不能用插件的 `signature` 权限**。
- 插件导出 `CaptureIngestProvider`，用每次构建生成的 token（`BuildConfig.CAPTURE_INGEST_TOKEN` 或 assets）校验；token 不是强安全边界，个人使用可接受。
- 优先 `ContentProvider.call`（IO 线程、2 秒超时、返回 ACK）；失败降级显式广播；插件按 `captureId`/`sourceTxnId` 去重。
- 不要阻塞微信的 DB 插入线程；所有调用放 IO。
- 两边 manifest 加 `<queries>`，覆盖包可见性限制；P0 探针必须验证。
- 如果 token/Provider 方案在真机不稳定，备选 localhost socket（P3）。

### 5.4 AI key 与代理

- App 的 AI key 仍由 `PrefsAiSupport` + `KeystoreSecretBox`（alias `flip_ai_secrets`）保管；**插件不读取、不缓存、不接触**。
- 分类由 App 的 `startClassify`/`getClassifyResult` 完成；插件只发金额/时间/商户/备注等必要字段。
- 这砍掉 `:ai-core`、`@Keep` facade、插件 Keystore、LSPosed 勾选敲敲记账，以及原有风险 6/7。
- 代价：AI 依赖 App；App 未配置/被杀时，插件只能本地草稿或手动分类。

### 5.5 插件数据模型（建议）

`payment_captures`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | captureId（UUID） |
| `source` | TEXT | WECHAT / ALIPAY |
| `sourceTxnId` | TEXT? | 订单号/交易号 |
| `amountCents` | INTEGER? | 金额（分）；缺失为 null |
| `currency` | TEXT | CNY |
| `merchant` | TEXT? | 商户 |
| `paymentMethodRaw` | TEXT? | 原始支付方式 |
| `assetId` / `assetName` | INTEGER?/TEXT? | 映射后的 App 资产 |
| `paidAt` / `capturedAt` | INTEGER | 毫秒 |
| `bookName` | TEXT? | 默认账本 |
| `note` | TEXT? | 用户备注草稿 |
| `status` | TEXT | PENDING/AI_DRAFT/IMPORTING/RECORDED/IGNORED/POSSIBLE_DUPLICATE/DUPLICATE/FAILED |
| `aiDraftJson` | TEXT? | AI 草稿 |
| `importRequestId` | TEXT? | 批次追踪（不做幂等键） |
| `captureGroupId` | TEXT? | 多来源归并组 |
| `importedBillIds` | TEXT? | 成功后 billId 列表 |
| `lastError` | TEXT? | 失败原因 |
| `rawJson` / `ruleVersion` / `sourceAppVersion` | TEXT? | 调试/规则版本 |
| `createdAt` / `updatedAt` | INTEGER | |

`capture_asset_mappings`：

| 字段 | 说明 |
| --- | --- |
| `source` | WECHAT / ALIPAY |
| `matchType` | EXACT / CONTAINS / REGEX |
| `pattern` | 原始支付方式 |
| `assetId` / `assetName` | 目标资产 |
| `priority` | 越小越优先 |
| `enabled` | 0/1 |

状态机：

```text
PENDING ──自己记/导入成功──> RECORDED
PENDING ──AI 生成草稿──────> AI_DRAFT
AI_DRAFT ──确认导入────────> IMPORTING → RECORDED
AI_DRAFT ──放弃草稿/自己记─> PENDING
PENDING/AI_DRAFT ──导入失败→ FAILED（可重试）
PENDING ──疑似已有账单────> POSSIBLE_DUPLICATE
POSSIBLE_DUPLICATE ──确认重复→ DUPLICATE
POSSIBLE_DUPLICATE ──不是重复→ PENDING
PENDING ──忽略────────────> IGNORED
IGNORED ──恢复────────────> PENDING
RECORDED ──账单被删────────> PENDING（P2，可选）
```

“待记数量” = `PENDING + AI_DRAFT + IMPORTING + FAILED + POSSIBLE_DUPLICATE`。

---

## 6. Hook 方案评估（请评审）

### 6.1 微信

| 方案 | 数据 | 稳定性 | 风险 |
| --- | --- | --- | --- |
| A. hook 支付成功页（原生 View/数据对象） | 金额/商户/支付方式/订单号/时间 | 页面结构变化就坏；需要逐版本适配 | 成功页可能很快消失；`FLAG_SECURE` 不影响（不截图） |
| B. hook WCDB `insertWithOnConflict`（AutoAccounting） | 消息 XML/ContentValues | 对消息/转账/红包很全；不依赖页面 | 需要解析 XML；Tinker 热更新可能让 hook 失效；可能抓到非支付消息 |
| C. RichText `appendText` 缓存 + 下一条消息归并 | 金额/支付方式/收款方 | 对“字段分散”很有效 | 120 秒缓存窗口；需要正确归并 |
| D. 通知监听 | 通知标题/文本 | 微信支付通知不可靠/可能没有 | 不适合作为唯一来源 |

💡 建议：第一版以**支付成功页**为主，`PayToolsHooker` 式缓存为辅；同时用 B/D 作为兜底（可配置）。先用探针确认真实页面字段，再决定是否走 B。

❓ 评审点：在 2026 年微信版本上，支付成功页是否仍是原生 View？`insertWithOnConflict` 是否仍可 hook？Tinker 是否仍是问题？

### 6.2 支付宝

| 方案 | 数据 | 稳定性 | 风险 |
| --- | --- | --- | --- |
| A. `SyncMessage.getData()` | 消息 JSON | 消息盒子覆盖面广 | 结构变化；可能是非支付消息 |
| B. `H5WebView.evaluateJavascript` + JS Bridge | 账单页 JSON | 对 H5 账单页有效 | 页面/桥名/URL 变化；轮询 120s |
| C. 支付成功页原生 hook | 金额/商户/支付方式 | 最直接 | 需逐版本适配；页面可能关闭 |
| D. 通知监听 | 通知文本 | 支付宝有通知 | 延迟/被关闭 |

💡 建议：先探针确认你的支付宝版本支付成功页是原生还是 H5；H5 走 JS Bridge，原生走页面 hook；`SyncMessage` 作为兜底。

❓ 评审点：支付宝 `SecurityHooker` 这类 bypass 是否必须？不绕过会不会弹环境异常？个人使用风险如何评估？

### 6.3 支付场景矩阵（探针必须覆盖）

| 场景 | 预期 | 第一版 |
| --- | --- | --- |
| 商户码（扫商家） | 支出；成功页/消息 | P0 |
| 个人收款码（扫个人） | **支出**，不能按“转账”过滤 | P0 |
| 付款码（商家扫你） | 支出；可能无成功页 | P1 探针 |
| 小程序支付（`:appbrand` 进程） | 支出；多进程去重 | P1 探针 |
| App 内调起微信支付 | 支出；可能只有商户 App 结果页 | P1 探针 |
| 免密代扣 / 自动续费 | 支出；可能无 UI | P1 探针 |
| 组合支付（零钱+银行卡） | 记实付总额；支付方式可能多条 | P1 探针 |
| 立减/优惠 | 记实付金额，不记原价 | P0 解析规则 |
| 亲属卡 | 支出；支付方式映射到亲属卡资产 | P2 |
| 退款/转账/红包 | 第一版忽略 | 不做 |

⚠️ 个人收款码是核心场景：微信可能显示“转账给XX/付款给XX”；不能一刀切按“转账”过滤，要用方向字段/上下文判断“我付出去买东西”。

### 6.4 版本适配

✅ AutoAccounting 的做法：

- per-app `HookerManifest` + `rules: List<Clazz>`（类查找规则）；
- `Hooker.loader` 统一加载类；
- 所有 hook 失败 catch `Throwable`；
- `minVersion`；
- 规则/分类/资产映射可独立更新。

💡 建议：

- 记录微信/支付宝版本号 + 规则版本；
- 用 `ClassLoader` + 类名/方法签名/字段锚点查找，不硬编码混淆名；
- 先做“探针模式”：只记录命中/字段，不建待记；跑 3~5 笔真实支付后再固化；
- 设置页显示“最近捕获时间 / 目标版本 / 规则版本 / 失败日志”。

---

## 7. AI 方案（App 代理）

### 7.1 为什么不再抽 `:ai-core` / 不读 key

✅ 原方案要抽 `AIService`，依赖 `Prefs`、`AiModelSlots`、`buildAccountingPromptContext`、`AIPrompts`、`parseAnalyzeResult`、`normalizeAccountingWithLocalRules`、`AiRule`、`Logger`、`CurrencyManager`、`CategoryRepository`、`AppDatabase`；还要 hook App 读 Keystore key。改造面大、R8/Keystore 都是坑。

💡 v2 改为 App 暴露 `startClassify` / `getClassifyResult`：

- App 用自己的 key、prompt、上下文调现有 `AIService.analyzeAccounting`；
- 只返回草稿，不写库；插件只收 `bills[]`；
- 插件不接触 key，不需要 `:ai-core`、`@Keep` facade、插件 Keystore、LSPosed 勾选 App。
- 代价：AI 依赖 App；App 未配置/被杀时，插件只能本地草稿或手动分类。
- 如果以后要让插件独立适配别的记账 App，再考虑 `:ai-core` 或插件自填 key。

### 7.2 接口与超时

- `startClassify(capturesJson)` → `jobId`；
- `getClassifyResult(jobId)` → `RUNNING/READY/FAILED`；
- 插件 500ms~1s 轮询，60s 超时；App 也可以广播 `AI_DRAFTS_READY`。
- App 内部逐条或批量调用 `AIService.analyzeAccounting`；返回草稿时保留 `category_id`/`asset_id`。
- 不能用同步 `call` 等 10~30s；会占住 Provider 线程，且 App 被杀时直接失败。

### 7.3 分类与资产

- App 的 AI 输出用 `category_id`（候选 id）；`normalizeAccountingResult` 目前会转成 `category_name`，导入路径要保留 ID 或先按 ID 解析。
- `BatchBillInserter` 要扩展支持 `category_id`/`asset_id`，名称只做快照/校验；否则“餐饮 - 其他”和“交通 - 其他”会撞名。
- 插件草稿展示分类/资产名称，但提交时带 ID。

### 7.4 失败兜底

- AI 未配置/超时/进程被杀 → 插件生成本地草稿（金额/时间/资产/备注，分类为空），用户从 `getAccountingContext` 选分类；
- 仍可走“自己记”，不阻塞主流程。
- 不自动入账；草稿必须确认。

---

## 8. 去重与幂等

### 8.1 捕获内

- `source + sourceTxnId` 相同 → 合并。
- 无订单号：**不自动合并**。同一来源、金额相同、时间 ±30s 内，标 `POSSIBLE_DUPLICATE`，让用户确认；菜市场连续两笔 3 元不能被吞。
- 支付成功页重复打开 → 命中订单号/疑似规则，不重复计数。

### 8.2 多来源归并

- 成功页 + DB + 通知可能报同一笔；用 10–30 秒窗口 + 金额 + 来源聚类，生成 `captureGroupId`。
- 主捕获保留，其他来源挂同一组；不重复计数；不确定时保留多条并标疑似。

### 8.3 与 App 账单

- **只有带 `captureId` 的账单才自动 `RECORDED`**（自己记/导入）。
- 手动账单（无 `captureId`）：只标 `POSSIBLE_DUPLICATE`，不自动 `DUPLICATE`；`Bill.time` 是用户填的记账时间，`Bill` 没有 `createdAt`，不能可靠自动关联。
- `getRecentBills` / `BILL_CREATED` 用于提示，不用于自动删数据。
- `POSSIBLE_DUPLICATE` 计入待记数量；`DUPLICATE` 不计入。

### 8.4 导入幂等

- `capture_id` 是幂等键；`external_imports(capture_id PK, bill_id, request_id, created_at)` 和账单写入**同事务**。
- 同一 `captureId` 重复导入返回已有 `billId`。
- `request_id` 只做批次追踪/日志，不做幂等键。
- 部分成功：逐条 `status`；插件只重试失败项。
- 不要用文件存幂等记录；崩在两步之间会重复写账单。

⚠️ 需要改 `BatchBillInserter`/`BillMutationService`，让 `captureId` 传入并写 `external_imports`；不要改 `bills` 表结构加 capture 字段。

---

## 9. 敲击入口（P1）

✅ 现状：

- `TapActionRegistry` 的 `ShowOverlayAction` 发 `OverlayService.ACTION_SHOW_OVERLAY`；
- `OverlayService` `exported=false`，`onStartCommand` 调 `overlayManager.showOverlay()`；
- 双击/三击可配置不同动作（AI 输入、截屏等）。

💡 方案：

1. App 读本地缓存的待记数量（插件在新增/处理/忽略后广播 `PENDING_COUNT_CHANGED`）。
2. 数量 > 0 → App 发 `ACTION_SHOW_PENDING`，插件展示列表；否则原 `showOverlay()`。
3. 缓存过期/不确定 → App 调 `getPendingCount()` 刷新（超时 300ms），**不阻塞敲击**。
4. 插件列表“先不记” → `openQuickRecord(null, null)` 回 App 空白记账。
5. App 设置“允许插件接管敲击”默认开；**P0 不启用**，先走通知点开。

⚠️ 不要在每次敲击时同步查 Provider；150~300ms 的等待会让人以为敲击没反应。缓存 + 推送 + 过期刷新。

❓ 评审点：缓存脏 0（插件有 pending 但 App 缓存没收到）怎么兜底？建议 App 在 `onResume`/通知点击时刷新，并在缓存时间超过 5 分钟时后台刷新。

---

## 10. 风险清单

| # | 风险 | 影响 | 建议 |
| --- | --- | --- | --- |
| 1 | 微信/支付宝 hook 版本失效 | 捕获不到 | 探针 + 规则版本 + 固定版本 |
| 2 | 微信 Tinker 热更新 | hook 失效 | 先不删补丁，观察；必要时再处理 |
| 3 | 支付宝环境检测 | 弹异常/限制 | 个人使用评估；不要默认 bypass |
| 4 | 支付成功页字段缺失 | 金额/商户为空 | 缓存归并 + “待补”条目 |
| 5 | 个人收款码方向 | 被当转账漏记 | 方向字段/上下文；探针覆盖 |
| 6 | 包可见性（Android 11+） | Provider/广播静默失败 | `<queries>` + P0 真机验证 |
| 7 | hook→插件鉴权 | signature 不适用 | token + 最小导出面；不阻塞 DB 线程 |
| 8 | 不常驻 FGS | 进程按需拉起失败/通知延迟 | Provider/广播拉起；真机验证；通知保留 |
| 9 | 两个 APK 签名/契约 | Provider 调用失败 | 同签名 + `signature` + `contractVersion` |
| 10 | 导入重复 | 重复账单 | `capture_id` 幂等 + `external_imports` 同事务 |
| 11 | 分类/资产 ID 迁移 | 旧名称路径/改名/删除 | `BatchBillInserter` 支持 ID；逐条报错 |
| 12 | 去重误判 | 漏记/错记 | 无订单号不合并；只标疑似；captureId 才自动 |
| 13 | AI 代理依赖 App | App 未配置/被杀 | 本地草稿 + 手动分类；不阻塞自己记 |
| 14 | 插件卸载/备份 | 待记丢失 | 导出/备份 + 卸载前提醒 |
| 15 | GPL-3.0 | 分发受限 | 个人使用；规则层可替换，不复制代码 |
| 16 | AI 成本/延迟 | 批量慢/花钱 | 只在用户主动触发时调用；本地草稿兜底 |
| 17 | 插件是“小 App” | 维护成本高 | 分阶段；P0 先单条流 |
| 18 | 小程序/多进程 | `:appbrand` 漏捕/重复 | 进程覆盖 + 跨进程归并 |
| 19 | 通知权限 | 不显示通知 | `POST_NOTIFICATIONS`；降级只记 |
| 20 | Android 14/15 后台限制 | 进程拉起失败 | 真机验证；必要时引导白名单/手动打开 |

---

## 11. 分期

### P0：探针 + 微信单条流 + 通知 + 自己记

- `:capture-plugin` + `:capture-contract`；
- 微信支付成功页/消息探针；金额/时间/支付方式/商户/订单号；
- 插件 Room + 普通常驻通知 + 待记列表 + 单条卡片；
- `openQuickRecord` 预填 + `external_imports` + `BILL_CREATED` + `getImportStatus` 对账；
- 基础去重（订单号；无订单号只标疑似）；
- **不做**：敲击接管、AI、FGS、气泡、支付宝。

### P1：支付宝 + 资产映射 + 敲击接管 + 通知动作

- 支付宝支付成功页/消息/H5 探针与适配；
- 资产映射表/管理页/首次未匹配引导；
- 敲击缓存计数 + `ACTION_SHOW_PENDING`；
- 通知 `RemoteInput` 备注 + 忽略；免打扰、锁屏隐私、每晚汇总；
- 规则版本、自检、日志导出；
- 插件备份/导出。

### P2：AI 代理 + 批量导入

- `startClassify`/`getClassifyResult`（App 现有 AI）；
- 单条 AI 草稿 + 批量 AI；
- `importBills` + ID 优先 + 幂等 + 逐条错误；
- 失败重试、部分成功；
- `POSSIBLE_DUPLICATE` 确认/合并 UI。

### P3（可选）

- 通知/短信补充来源；
- 退款冲抵；
- 完整卡片/真气泡；
- 微信/支付宝双开；
- 适配其他记账 App（通用 ingestion API）。

---

## 12. 验收（技术）

1. 微信/支付宝支付成功后 5s 内，插件新增 `PENDING`，通知数量正确。
2. “自己记”预填金额/时间/资产/备注；保存后 `external_imports` 有记录，插件通过 `BILL_CREATED` 或 `getImportStatus` 标 `RECORDED`。
3. “AI 记”由 App 代理返回草稿；确认后 `importBills` 写账单；取消不写。
4. 批量 3 笔 → 3 条草稿 → 全部入账 → 3 条账单，插件全部 `RECORDED`。
5. 手动记账后 3 分钟，对应捕获只标 `POSSIBLE_DUPLICATE`，不自动删除；用户确认后才 `DUPLICATE`。
6. 无待记时敲击，行为与现在完全一致；有待记时（P1）先出列表；“先不记”能回空白记账。
7. 同一 `captureId` 重复导入不产生重复账单。
8. 导入部分失败：成功项已记、失败项可重试。
9. 插件被 kill/重启后，待记与通知恢复；`IMPORTING` 通过 `getImportStatus` 对账。
10. hook 失效：设置页可见，不崩溃、不产生假记录。
11. 插件未安装/契约不匹配：App 敲击回退正常；插件待记不丢。
12. AI 未配置/超时：插件本地草稿 + 手动分类可用；插件不接触 key。
13. 待记不进 App 共享账本同步；插件导出/恢复后仍在。
14. 覆盖率：20 笔真实支付覆盖 7.7 场景矩阵，统计漏捕/误捕；原始事件脱敏回放测试通过。

---

## 13. 给评审 AI 的问题（请逐条回答）

1. **微信 hook 选型**：成功页 vs WCDB insert vs 通知，哪个在 2026 年最稳？是否应该组合？
2. **微信 DB hook**：`SQLiteDatabase.insertWithOnConflict` 是否仍可靠？Tinker 补丁是否必须清？有没有更稳的锚点？
3. **支付宝**：H5 JS Bridge 是否仍可用？原生成功页 hook 的锚点通常是什么？`SecurityHooker` 是否必须？
4. **AI 代理**：`startClassify`/`getClassifyResult` 的异步/超时/进程被杀兜底够不够？是否应该改成同步 `call`？
5. **导入契约**：`capture_id` 幂等 + `external_imports` 同事务，能否覆盖批量部分成功与“自己记”回调丢失？
6. **ID 迁移**：`BatchBillInserter` 从名称改 ID 优先，改动面有多大？旧数据/改名/删除怎么处理？
7. **敲击联动**：缓存计数 + 推送是否够？脏 0 怎么兜底？
8. **Android 14/15**：不常驻 FGS、Provider/广播拉起、包可见性、LSPosed 作用域，有哪些必须提前处理？
9. **B3 vs A**：插件持有收件箱是否过度设计？如果收件箱放 App，能省多少？哪个更符合“插件适配 App”的目标？
10. **去重**：无订单号只标疑似、captureId 才自动关联，够不够？同价多笔/连续支付/手动并发怎么设计更稳？
11. **支付场景**：个人收款码、付款码、小程序 `:appbrand`、App 内支付、免密、组合支付、立减、亲属卡，哪些第一版必须覆盖？
12. **GPL/测试**：AutoAccounting 规则复用边界？没有真实支付时，探针/回放/模拟数据怎么搭？

---

## 14. 附录：源码索引

### AutoAccounting

| 内容 | 路径 |
| --- | --- |
| Xposed 入口 | `app/src/main/java/net/ankio/auto/xposed/XposedModule.kt` |
| Hooker 基类 | `app/src/main/java/net/ankio/auto/xposed/core/api/HookerManifest.kt` |
| PartHooker | `app/src/main/java/net/ankio/auto/xposed/core/api/PartHooker.kt` |
| Hook 工具 | `app/src/main/java/net/ankio/auto/xposed/core/hook/Hooker.kt` |
| 微信 hook | `app/src/main/java/net/ankio/auto/xposed/hooks/wechat/` |
| 微信 DB | `.../hooks/wechat/hooks/DatabaseHooker.kt` |
| 微信 RichText 缓存 | `.../hooks/wechat/hooks/PayToolsHooker.kt` |
| 支付宝 hook | `app/src/main/java/net/ankio/auto/xposed/hooks/alipay/` |
| 支付宝消息盒子 | `.../alipay/hooks/MessageBoxHooker.kt` |
| 支付宝 WebView | `.../alipay/hooks/WebViewHooker.kt` |
| 支付宝安全 bypass | `.../alipay/hooks/SecurityHooker.kt` |
| 通知 hook | `.../hooks/android/hooks/NotificationHooker.kt` |
| 数据分析上报 | `.../xposed/core/utils/AnalysisUtils.kt` |
| 规则模型 | `server/src/main/java/org/ezbook/server/db/model/RuleModel.kt` |
| 规则生成 | `server/src/main/java/org/ezbook/server/engine/RuleGenerator.kt` |
| JS 执行 | `server/src/main/java/org/ezbook/server/engine/JsExecutor.kt` |
| 分类规则 | `server/src/main/java/org/ezbook/server/db/model/CategoryRuleModel.kt` |
| 资产映射 | `server/src/main/java/org/ezbook/server/db/model/AssetsMapModel.kt` |
| 账单模型 | `server/src/main/java/org/ezbook/server/db/model/BillInfoModel.kt` |
| 目标 App 适配 | `app/src/main/java/net/ankio/auto/adapter/IAppAdapter.kt` |
| 钱迹适配 | `app/src/main/java/net/ankio/auto/adapter/QianJiAdapter.kt` |
| 账单保存/同步 | `app/src/main/java/net/ankio/auto/utils/BillTool.kt` |

### 敲敲记账

| 内容 | 路径 |
| --- | --- |
| 账单模型 | `app/src/main/java/com/taostudio/tapaccounting/data/local/entity/Bill.kt` |
| 资产模型 | `.../data/local/entity/Asset.kt` |
| 分类模型 | `.../data/local/entity/Category.kt` |
| 账本模型 | `.../data/local/entity/Book.kt` |
| 账本管理 | `.../BookAccountManager.kt` |
| AI key 存储 | `.../PrefsAiSupport.kt`、`.../data/crypto/KeystoreSecretBox.kt` |
| AI 上下文/规范化 | `.../AIAccountingSupport.kt` |
| AI 请求 | `.../AIService.kt` |
| AI 提示词 | `.../AIPrompts.kt`、`.../AIAccountingPromptBuilder.kt` |
| 批量写入 | `.../logic/BatchBillInserter.kt` |
| 账单写入漏斗 | `.../logic/BillMutationService.kt` |
| 悬浮窗 | `.../OverlayManager.kt` |
| 悬浮窗表单 | `.../logic/AccountingFormController.kt` |
| AI 多账单确认 | `.../AiAssistant.kt` |
| 悬浮窗服务 | `.../OverlayService.kt` |
| 手势动作 | `.../tap/TapAction.kt`、`.../tap/TapActionRegistry` |
| 清单 | `app/src/main/AndroidManifest.xml` |
| 构建 | `app/build.gradle.kts` |

---

## 15. 一句话总结

💡 用独立插件做“支付留痕 + 待记收件箱”，用敲敲记账做“账本 + 编辑器 + AI 代理 + 写入漏斗”；hook 参考 AutoAccounting 的微信 DB/RichText、支付宝消息盒子/H5、per-app manifest 和规则/映射模型；AI 由 App 的 `startClassify`/`getClassifyResult` 代理，插件不接触 key；写账单走 `openQuickRecord`/`importBills` + `external_imports`。请重点判断：hook 选型是否稳、AI 代理异步是否够、ID 导入契约是否完整、B3 是否比 A 更合适。
