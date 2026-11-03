# 支付捕获插件交接（2026-11-02）

## 2026-11-03 组合 B 第一阶段（未安装）

> 本轮未 commit、未改 `app/` 业务代码、未动 `.tmp_autoaccounting_pull/` 里的真实数据。
> 详细口径/映射/测试见 `docs/operations/bill-type-localization.md`。

### 结论

- 用户确认**组合 B**：日常使用需要支出、收入、退款、转账、还款都进账本，不再只记支出。
- `BillTypeLocalizer` 已按 B 修改：`Income + 还款` → 普通收入 `1/0`；退款 `1/2` + `needsRefundLink`；还款仍 `2/1`；群收款仍 `0/0`。
- `AutoAccountingRuleEngine.parse()` 返回 `ParseOutcome`（`Matched` / `NoMatch`），非支出不再直接 null；命中但无法判断类型时 `localType=null`，保留金额/商户等字段。
- 契约和插件数据新增 `billType/subType/needsRefundLink`；插件 Room 升 **v2**，`MIGRATION_1_2` 用 `ALTER TABLE` 保留现有行。
- `CaptureIngestProvider` 已按支出/收入/转账/还款/退款/待分类分流：所有识别出的类型都插入待记；未命中/未知类型插入为“待分类”，不编造型。
- 待记列表显示本地类型标签；“自己记”按类型预填，待分类不写 `type`，退款先提示“待关联原支出”。

### 还没做（第二阶段）

1. App `importBills` 仍是 `not_implemented`；收入/转账/还款/退款还不能真正写入 App 账本。
2. 分类映射、退款“退款：”前缀/原分类、还款分类未做。
3. 转账/还款的资产两端（`accountId` + `toAccountId`）未做。
4. 退款原账单关联未做；当前只保证 `needsRefundLink=true` 并留在待记。
5. App 表单对退款 `subType` 的处理、资产功能关闭时转账降级，需要后续处理。
6. 尚未安装到设备，也未跑 v1→v2 真机 migration 回归。

---

## 2026-11-03 最新进度（最后一次安装 / 回归）

> 设备：备用机 KB2000（`172.20.1.22:41103`）。本轮未 commit、未改 `app/` 业务代码、未动 `.tmp_autoaccounting_pull/`。
>
> 类型本地化：上一轮只在规则层、不落库；最新一轮已升级为组合 B 第一阶段（见上一节）。

### 结论

- **构建**：`.\gradlew.bat :app:assembleDebug :capture-plugin:assembleDebug` → `BUILD SUCCESSFUL in 21s`（7 executed / 73 up-to-date）。
- **安装**：`capture-plugin-debug.apk` 流式安装 `Success`；设备上 `versionName=0.1`、`versionCode=1`、`lastUpdateTime=2026-11-03 20:07:47`。App 未重装。
- **回归**：通过。无崩溃、无规则执行失败；插件首页显示“桥接：已连接”。

### 解析样本

弹窗内容与预期一致：

- 样本 1（微信支付 `mMap`）：规则 `微信支付在线支付`，金额 ¥9.00（900 分），商户 同程旅行，支付方式 招商银行储蓄卡(4852)，渠道 `微信[微信支付-在线支付]`。
- 样本 2（微信转账）：规则 `微信转账`，金额 ¥1.00（100 分），渠道 `微信[转账付款]`。

### 模拟WCDB

- 点击前：共 5 笔 / 待记 4 笔 / 合计 ¥14.00。
- 点击后（3 秒内）：共 7 笔 / 待记 6 笔 / 合计 ¥24.00；新增两条，均为“待记”：
  - ¥9.00 同程旅行 / 招商银行储蓄卡(4852)
  - ¥1.00 转账 / 招商银行储蓄卡
- logcat：两条 `TapCaptureForward` 均 `ack={"ok":true,...,"duplicate":false}`；`TapCaptureRule` 分别为 `executed=15 failures=0 cost=15ms` 和 `executed=1 failures=0 cost=2ms`。
- 按指示没有点“自己记”保存，本轮**没有新增测试账单**。设备上仍留有上一轮的 1 条 9.00 测试账单和 4 条待记，可清理。

### 支付宝 SyncMessage 数组形状修复

- 支付宝 `SyncMessage.getData` 返回的是 JSON **数组**；AutoAccounting 的规则（406/59 等）按数组解析。
- 修复：`ProbeHooker.handleAlipaySyncMessage` 逐条挑出支付/账单消息后，把单条包成 `JSONArray().put(item)` 再作为 `rawJson` 上报（此前只发单条对象，规则拿不到数组）。
- 非数组输入直接跳过，并记日志 `SyncMessage.getData non-array`，避免规则侧异常。
- 该修复已包含在本次构建/安装的 APK 中；真实支付宝 hook 尚未在备用机验证（见下）。

### 真实数据验证（仅计数）

- 全量翻页拉取 AutoAccounting 已匹配数据：微信 **1003** 条、支付宝 **653** 条（2025-09 ~ 2026-02）。
- 其中 2026-02：微信 **58** 条、支付宝 **57** 条，均为 DATA；规则判定支出 **87** 条（微信 44：在线支付 39 + 转账 5；支付宝 43：消费 42 + 账单列表 1），其余 28 条为退款/收入/未命中。
- 转账口径：**转给他人的出账转账按支出处理**（规则输出 `Expend`），不按“转账”一刀切排除；收入/退款/账户间转移才排除。
- 第一页 100 条样本的旧结果：微信 79/100 支出、支付宝 69/100 支出。
- 金额/商户/订单号等明细不写入仓库，仅保留在本地 `.tmp_autoaccounting_pull/main_device/`。

### 2026-02 真实数据端到端验证（2026-11-03）

- 把上述 2 月 **87** 条支出样本投递到备用机插件的真实入口 `CaptureIngestProvider`（token → QuickJS → Room），不是只跑本地 Node 规则。
- **87/87 入库成功**：金额、商户、支付方式、时间与规则输出逐条一致；`needsAmount=false`；`ruleVersion=autoaccounting-1`。
- 支付宝样本验证了单元素数组修复：`SyncMessage` 单条包成 `JSONArray().put(item)` 后规则才能正确解析。
- 非阻断问题：支付宝 `rule_51`（红包）在 42 条样本上抛 `SyntaxError`，`rule_60/61/62` 在 1 条账单列表样本上抛 `TypeError`；引擎跳过失败规则后仍由后续规则命中，87 条全部成功。
- 仍有 2 条「微信转账」被规则输出 `Income`（其中 1 条 `isSend=1`）；这两条暂不按支出，建议单独核对方向/退款语义。
- **待修边界（已由 2026-11-03 组合 B 第一阶段修复，见顶部）**：旧 Provider 对规则返回 `null` 的非支出/未命中仍会插入原始 payload；当时需要显式区分“支出 / 非支出 / 未命中”。现在改为 `ParseOutcome` + 类型分流：所有类型都进待记，未知类型标“待分类”。
- 测试后已恢复测试前的插件 DB；首页回到“共 7 笔 / 待记 6 笔”，没有把真实样本留在待记收件箱。
- 原始样本只留在本机 `.tmp_autoaccounting_pull/`（gitignore）；设备上的临时 payload、DB 备份与 logcat 已清理。

### 仍未做

1. **真实微信/支付宝 hook 未验证**：备用机没有真实支付数据；主设备有真实数据，但不能在上面装 debug 包。
2. **NOTICE 通知 hook 未接**：微信 1 条、支付宝 2 条 NOTICE 规则暂不执行。
3. **资产映射 / AI 代理 / 批量导入 / 备份导出**未做。
4. **UI 仍是功能骨架**，用户明确说后面再优化。

---

> 更早的 2026-11-03 进展（已被上一节取代）：QuickJS 规则引擎已接入；插件已安装到设备；`解析样本` 两条规则在真机解析正确；`自己记` 已能打开 App 悬浮窗并预填金额/时间；WCDB 输出已按 AutoAccounting 规则的 `mMap` / `transfer` 形状构造。剩下真实微信/支付宝支付 + LSPosed 作用域验证。

> 用途：换一个页面/会话继续做时，先读这一份。
> 当前阶段：P0 骨架 + App 桥接 + 探针 + QuickJS 规则引擎 + AutoAccounting 规则已接入；**真实微信/支付宝支付还没测**。
> 手机可以拔掉：数据已经复制到本机。

---

## 1. 一句话状态

- 插件能装、能打开、能显示待记列表、能发通知。
- QuickJS 规则引擎已接入，AutoAccounting 的 116 条启用规则原样执行；两条真实样本在真机解析正确。
- 插件的 Xposed 探针能 hook 微信/支付宝的候选点；微信 WCDB 已按规则需要的 `mMap` / `transfer` / `redPackage` 形状构造。
- 敲敲记账能接收“自己记”，打开现有悬浮窗；真机已看到金额/时间预填。保存回执链路代码已通，但为避免污染真实账本，测试时点了取消。
- 还差：LSPosed 作用域开启后的真实微信/支付宝支付测试；NOTICE 通知 hook 还没接。

---

## 2. 代码状态

### 已构建通过

```powershell
.\gradlew.bat :app:assembleDebug :capture-plugin:assembleDebug
BUILD SUCCESSFUL
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `capture-plugin/build/outputs/apk/debug/capture-plugin-debug.apk`

两个 debug 包使用同一台机器的 debug keystore，`signature` 权限可通。App 若是 release 签名，插件 debug 会被 `CaptureBridgeProvider` 拒绝。

### 新增模块

- `capture-contract/`
  - `CaptureContract.kt`：authority、action、方法名、权限名、`CONTRACT_VERSION=1`
  - `CapturePayload.kt`、`AccountingContext.kt`、`ImportBill.kt`
- `capture-plugin/`
  - `CaptureApp.kt`
  - `data/`：`PaymentCapture`、DAO、Room、Repository
  - `ingest/CaptureIngestProvider.kt`：hook → 插件的 token 入口
  - `notify/CaptureNotifier.kt`：普通常驻通知
  - `hook/PluginXposed.kt`、`ProbeHooker.kt`、`CaptureForwarder.kt`、`ProbeText.kt`、`WechatRichCache.kt`
  - `ui/PendingListActivity.kt`：列表、模拟一笔、自己记、备注、忽略
  - `bridge/CaptureBridgeClient.kt`、`CaptureReconciler.kt`、`BillCreatedReceiver.kt`

### App 侧改动

- 新增 `app/src/main/java/com/taostudio/tapaccounting/capturebridge/CaptureBridgeProvider.kt`
- 新增 `data/local/entity/ExternalImport.kt`、`dao/ExternalImportDao.kt`
- `AppDatabase.kt`：DB_VERSION 39→40，`MIGRATION_39_40`
- `BillMutationService.kt`：`insertBillAndApplyImpact` 支持 `captureId/requestId`，账单 + `external_imports` 同事务，事务提交后广播 `BILL_CREATED`
- `OverlayService.kt` / `OverlayManager.kt` / `AccountingFormController.kt`：支持 `prefill` + `captureId` 透传
- `AndroidManifest.xml`：signature 权限 + `CaptureBridgeProvider` + `<queries>`
- `app/build.gradle.kts`：依赖 `:capture-contract`

### 构建配置

- `settings.gradle.kts`：include `:capture-contract`、`:capture-plugin`；加 `https://api.xposed.info/` 仓库
- `build.gradle.kts`、`gradle/libs.versions.toml`：加 Kotlin JVM 插件别名
- 用户原本未提交的文件（`CategoryKeyValidator.kt`、`RuleLearnPromptHelper.kt`、几个 drawable/layout、`StrictJsonParserTest.kt`、`.gitignore`）没有动。

### 还没 commit

所有改动都在工作区，未提交。真机验证通过后再整理提交。

---

## 3. 从手机拉下来的数据

### 位置

稳定副本（仓库内、已被 `.gitignore` 的 `.tmp_*` 忽略）：

```text
E:\FlipAccounting-AI\.tmp_autoaccounting_pull\
```

临时副本（可能被清理）：

```text
C:\Users\tao\AppData\Local\Temp\opencode\autoaccounting-pull\
```

### 内容

```text
wechat_rules.json        88 条微信规则（JSON）
alipay_rules.json        30 条支付宝规则（JSON）
rules/wechat/            88 个单条 .js + manifest.json
rules/alipay/            30 个单条 .js + manifest.json
common.js                规则公共函数（toFloat/RuleObject/parseWechat/splitShop 等）
category.js              分类规则
category_rules.json      分类规则原始数据
assets_map.json          资产映射
settings.json            AutoAccounting 设置
aa_data.tar.gz           App 数据目录（207KB，基本是 bugly/mmkv/空库）
```

APK 在临时目录：`...\autoaccounting-pull\autoaccounting.apk`（55MB，未复制进仓库）。

### 来源

- 设备：PKX110，Android 17，KernelSU root。
- AutoAccounting：`net.ankio.auto`，`4.0.2-Canary.20261023_1133`。
- 规则不在本地数据库；AutoAccounting 自带本地 server `http://127.0.0.1:52045`。
- 通过 `adb forward tcp:52045 tcp:52045` 后调用：
  - `GET /rule/list?app=com.tencent.mm&limit=200`
  - `GET /rule/list?app=com.eg.android.AlipayGphone&limit=200`
  - `POST /setting/get?key=setting_js_common`
  - `POST /setting/get?key=setting_js_category`
  - `GET /assets/map/list?limit=0`
  - `POST /category/rule/list?page=1&limit=500`
- 规则是“云端”规则；这是订阅后的本地缓存/服务输出。个人使用可以；分发前注意 GPL/第三方规则版权。

---

## 4. 你给的两条真实样本 → 预期解析

### 样本 1：微信支付（mMap）

```json
{"mMap":{"description":"使用招商银行储蓄卡(4852)支付￥9.00","source":"微信支付","title":"已支付¥9.00","display_name":"同程旅行","cachedPayTools":"","cachedPayMoney":"","cachedPayShop":"","t":1770696436886}}
```

匹配规则：`143_微信支付在线支付_DATA.js`（`微信支付在线支付`）

`common.js` 的 `parseWechat` 会：

- 取 `mMap`；
- `source == "微信支付"`；
- `title` 去掉数字/标点 → `已支付`，命中锚点；
- 用 `description` 匹配 `使用(.*?)支付[¥￥](\d+\.\d{2})`。

预期结果：

```text
type        = 支出
amount      = 900 分（¥9.00）
merchant    = 同程旅行
paymentMethod = 招商银行储蓄卡(4852)
paidAt      = 1770696436886
channel     = 微信[微信支付-在线支付]
```

### 样本 2：微信转账

```json
{"type":"transfer","isSend":1,"content":"{\"msg\":{\"appmsg\":{\"wcpayinfo\":{\"feedesc\":\"￥1.00\",\"paysubtype\":\"1\",...}}}}","hookerUser":"吴希城6.4","cachedPayTools":"招商银行储蓄卡","cachedPayMoney":"￥1.00","cachedPayShop":"向吴希城6.4(**城)转账","t":1770711541951}
```

匹配规则：`157_微信转账_DATA.js`（`微信转账`）

预期结果：

```text
type        = 支出
amount      = 100 分（¥1.00）
merchant    = 吴希城6.4
paymentMethod = 招商银行储蓄卡
paidAt      = 1770711541951
channel     = 微信[转账付款]
```

这两条证明：**不用自己猜字段，规则已经能对上**；下一步就是把规则翻译/执行。

---

## 5. 关键规则索引

### 微信（88 条，常用的）

| ID | 名称 | 类型 | 说明 |
| --- | --- | --- | --- |
| 158 | 微信支付 | NOTICE | 微信通知：title=微信支付，text 含“已支付”；金额在 ¥ 后 |
| 157 | 微信转账 | DATA | transfer JSON；`wcpayinfo.feedesc`、`paysubtype`、`isSend`、`cachedPayTools` |
| 156 | 微信账单 | DATA | WebView `ret_code/ret_msg/header/preview` |
| 155 | 微信群收款 | DATA | `content.mMap.content` 匹配“你支付了…发起的” |
| 154 | 微信收红包 | DATA | `retcode/amount/record[0].receiveTime` |
| 153 | 微信支付零钱通转出 | DATA | 文本正则 |
| 148 | 微信支付自动扣款 | DATA | 扣费金额/项目/方式/收单机构 |
| 147 | 微信支付经营收款 | DATA | 收款金额/店铺/顾客 |
| 146 | 微信支付智能穿戴支付 | DATA | 付款金额/手环/商品/方式/收单机构 |
| 145 | 微信支付收款入账 | DATA | 收款金额/账户 |
| 144 | 微信支付提现 | DATA | 提现金额/银行/时间 |
| 143 | 微信支付在线支付 | DATA | **mMap 主力**：`使用…支付¥…`、`付款金额`、`收款方` 等 |
| 142 | 微信支付商家退款 | DATA | 退款金额/商品/商户/到账时间 |
| 141 | 微信支付商家付款 | DATA | 付款金额/收款方/方式 |
| 140 | 微信支付转账 | DATA | 转账详情 |
| 139 | 微信支付二维码收款 | DATA | 收款金额/付款方 |
| 138 | 微信发红包 | DATA | `redPackage` + `cachedPayMoney` |

### 支付宝（30 条，常用的）

| ID | 名称 | 类型 | 说明 |
| --- | --- | --- | --- |
| 406 | 支付宝…支付 | NOTICE | 通知类支付 |
| 65 | 支付宝…银行交易 | NOTICE | 银行通知 |
| 63 | 支付宝…通知 | DATA | 通用支付通知 |
| 62 | 支付宝转账到余额宝 | DATA | 转账/余额宝 |
| 61 | 支付宝转账到银行卡 | DATA | |
| 60 | 支付宝转账 | DATA | |
| 59 | 支付宝账单列表 | DATA | 列表页多笔 |
| 58 | 支付宝…理财 | DATA | |
| 57 | 支付宝…森林 | DATA | |
| 54 | 支付宝…（消费） | DATA | |
| 47 | 支付宝小程序 | DATA | |
| 46 | 支付宝小程序… | DATA | |

> 名称在 PowerShell 里可能显示乱码，直接看 `.tmp_autoaccounting_pull/rules/.../manifest.json` 或 UTF-8 打开 `.js`。

---

## 6. 规则格式

一条规则是 JS：

```js
var rule_157 = function(e,t) {
  return e.get = function(e) {
    var n = JSON.parse(e);
    ...
    return new t.RuleObject(
      t.BillType.Expend, // type
      t.toFloat(...),    // money（元）
      shopName,
      shopItem,
      accountNameFrom,
      accountNameTo,
      fee,
      currency,
      time,
      channel
    );
  }, e;
}({}, common);
```

- `common` 提供 `RuleObject`、`BillType`、`Currency`、`toFloat`、`toDoubleFloat`、`transferCurrency`、`formatDate`、`parseWechat`、`splitShop`、`convertToNumber` 等。
- `parseWechat(data, patterns, source, anchors)` 专门处理微信 `mMap`：先校验 `source`/`title`，再用 `description` 逐个正则匹配。
- 规则返回 `RuleObject`；AutoAccounting 再把它转成 `BillInfoModel`。
- 分类规则是另一套 JS：`getCategory(money,type,shopName,shopItem,time,ruleName)`。
- 资产映射是 `AssetsMapModel`：`name → mapName`，支持 `regex`/`sort`。

---

## 7. 下一步（换页面后从这里继续）

### 方案 A：Kotlin 翻译（推荐 P0）

1. 建 `capture-plugin/.../parse/`：
   - `RuleParser` 接口：`fun parse(source: String, rawJson: String): ParsedCapture?`
   - `WechatMmapParser`：处理 `mMap`，对应规则 143/145/146/147/139 等。
   - `WechatTransferParser`：处理 `type=transfer`，对应 157。
   - `WechatWebViewParser`：处理 `ret_code/ret_msg/header/preview`，对应 156。
   - `WechatNoticeParser`：处理通知 `title=微信支付`，对应 158。
   - `AlipaySyncParser`：处理 SyncMessage JSON 数组。
   - `AlipayH5Parser`：处理 `ariverRpcTraceId` 结果。
2. 复刻 `common.js` 的最小函数：`toFloat`、`toDoubleFloat`、`formatDate`、`splitShop`、`parseWechat`。
3. 在 `CaptureForwarder.forward` 前调用 parser；解析成功填 `amountCents/paidAt/merchant/paymentMethodRaw/sourceTxnId`，失败保留原始 JSON + `needsAmount=true`。
4. 先覆盖你给的两条样本 + 微信通知 + 支付宝通知；再按 `rules/wechat/manifest.json` 补。

### 方案 B：内嵌 QuickJS 跑规则

- 引入 QuickJS Android（如 `com.shiqi.quickjs` 或 `app.cash.quickjs`）。
- 加载 `common.js` + 选中规则 JS；`RuleGenerator` 的 `window.data` 方式执行。
- 优点：118 条规则全覆盖；缺点：引擎/调试/GPL/包体。
- 建议：P0 先 A；规则多起来再 B。

### 方案 C：混合

- 高频规则 Kotlin；冷门规则/新版本用 JS 兜底。

---

## 8. 明天真机测试步骤

1. 装两个 debug APK：
   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb install -r capture-plugin\build\outputs\apk\debug\capture-plugin-debug.apk
   ```
2. LSPosed 只勾微信、支付宝，重启。
3. 打开插件，允许通知。
4. `adb logcat -s TapCaptureProbe`。
5. 微信/支付宝各做一笔 0.01 元支付。
6. 把 `TapCaptureProbe` 的原始 JSON 发回来；对照规则确认字段。
7. 再测“模拟一笔 → 自己记 → 保存 → 插件变 RECORDED”。

详细步骤：`docs/operations/capture-plugin-p0-test.md`。

---

## 9. 注意事项

- 当前是 **探针版**，不要期望金额一定正确；`amountCents` 可能是 null。
- 没做完整去重：同一笔可能被多个 hook 各报一次；先忽略/删除。
- 没有常驻前台服务；通知由插件进程按需更新。
- `openQuickRecord` 后台启动悬浮窗在 Android 12+ 可能被拒；Provider 会返回 `foreground_service_not_allowed`，先点开 App 再试。
- 插件待记不在 App 备份内；卸载插件会丢，先导出（P1）。
- AutoAccounting 规则是第三方/云端内容，个人使用可以；分发前处理 GPL/版权。
- 所有改动未 commit；先别 `git clean`，避免丢工作区改动。
