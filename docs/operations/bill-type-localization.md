# 账单类型本地化（支付捕获插件）

> 2026-11-03。范围：AutoAccounting 规则输出 → 敲敲记账本地账单类型/子类型。
> 当前阶段为**组合 B**：支出、收入、退款、转账、还款都进待记账本，不再只记支出。
> App 侧已接入 `importBills`、资产名折算、分类名折算和直接入账；退款会尝试匹配原支出，匹配不到留在“待关联”。
> 不做 AI、截图、复杂新 UI。
> 本文示例规则名、金额均为手写假数据；真实样本只留在本机 `.tmp_autoaccounting_pull/`。

## 1. 当前结论

- 组合 B 已确认：日常使用需要支出、收入、退款、转账、还款都进账本。
- `BillTypeLocalizer` 已按 B 口径完成；`Income + 还款` 现在是普通收入 `1/0`，不再返回 null。
- `AutoAccountingRuleEngine.parse()` 改为返回 `ParseOutcome`：规则命中即带回 AutoAccounting 类型、本地类型/子类型、`needsRefundLink`；本地类型无法判断时 `localType=null`（待分类），不再对非支出直接返回 null。
- `CapturePayload` / `PaymentCapture` 增加 `billType` / `subType` / `needsRefundLink` / `toAssetName` / `categoryName`；插件 Room 升到 **v4**，提供 `Migration(1,2)`、`(2,3)`、`(3,4)`，保留现有测试数据。
- `CaptureIngestProvider` 按“支出 / 收入 / 转账 / 还款 / 退款 / 待分类”分流：识别出的类型都插入待记；未命中/未知类型插入为“待分类”，不编造型。
- **App 端已接入 `importBills`**：支出/收入/转账/还款/退款都走同一契约；转账/还款要求两端资产；退款可显式传 `relatedBillId`，不传时按分类/金额/时间尝试匹配，匹配不到返回 `needs_refund_link`，不会写成普通收入。
- **资产名已本地化**：`AssetNameResolver` 把“零钱/招商银行储蓄卡(4852)”折成本地“微信/招商银行”；**分类名已本地化**：`CategoryNameResolver` 把 AA 分类折成本地分类，未知分类不硬塞。
- 待记列表新增“入账”按钮：类型明确的条目可直接写入 App；退款也走该按钮，但匹配不到原支出会留在待关联。

## 2. 两套类型模型

### AutoAccounting（3 种）

| 值 | 含义 |
| --- | --- |
| `Expend` | 出账 |
| `Income` | 入账 |
| `Transfer` | 转账 |

AutoAccounting 没有“还款”“退款”类型，只能从规则名（例如“支付宝花呗主动还款”“微信支付商家退款”）识别。

### 敲敲记账（4 种主类型 + 子类型）

| type | 含义 | 规范子类型 |
| --- | --- | --- |
| 0 | 支出 | 0 普通 |
| 1 | 收入 | 0 普通；2 退款 |
| 2 | 转账 | 0 普通；1 还款 |
| 3 | 还款（旧表示） | 只读兼容，不写新数据 |

持久化/统计的规范表示：

- **还款 = `2/1`**（`TYPE_TRANSFER` + `SUBTYPE_REPAYMENT`）。旧数据里的 `3` 由 `MigrationManager` 等兼容读取；新增数据不要输出 `3`。
- **退款 = `1/2`**（`TYPE_INCOME` + `SUBTYPE_REFUND`）。`BillMutationService.saveRefundBill` 会归一成这个值，并要求原账单是支出、写入 `relatedBillId`、分类名加“退款：”前缀。
- **退款不能当普通收入直接入账**。`BillStatsContribution` 对已关联退款计 `refund`；未关联的历史退款暂按负支出处理，直到能找到原账单。

## 3. 组合 B 最终映射表

判断顺序（先命中先返回）：

1. 规则名含“还款”：`Expend`/`Transfer` → `2/1`；`Income` → `1/0`（B 口径）。
2. 规则名含“退款”：`Income` → `1/2` + `needsRefundLink=true`；`Expend`/`Transfer` → null（方向矛盾，待分类）。
3. `Transfer` → `2/0`；不再因“收款/转账”改判。
4. `Expend` + 规则名含“收款/收入/收益/打款” → `1/0`；排除“群收款”。
5. `Expend` → `0/0`；`Income` → `1/0`。
6. 其他/空类型 → null（待分类）。

| AutoAccounting type | 规则名条件（示例） | 本地 type | 本地 subType | needsRefundLink | 说明 |
| --- | --- | --- | --- | --- | --- |
| `Expend` | 普通，或含“转账”（如“微信转账”） | 0 | 0 | false | 转给他人的出账转账按支出 |
| `Expend` | 含“还款” | 2 | 1 | false | 输出规范还款，不输出 3 |
| `Expend` | 含“退款” | — | — | — | null；出账与退款方向矛盾，标记待分类，不猜 |
| `Expend` | 含“收款/收入/收益/打款”，但不含“群收款” | 1 | 0 | false | 防 AutoAccounting 把入账规则误标成 Expend |
| `Expend` | 含“群收款” | 0 | 0 | false | 已知例外：AA 的“微信群收款”只在“你支付了……”时命中，本质是出账 |
| `Transfer` | 含“还款” | 2 | 1 | false | 如“支付宝花呗主动还款/自动还款” |
| `Transfer` | 含“退款” | — | — | — | null；方向矛盾，待分类 |
| `Transfer` | 普通（含“转账”“收款到余额宝”“提现”） | 2 | 0 | false | 账户间调拨 |
| `Income` | 含“退款” | 1 | 2 | **true** | 必须关联原支出，不能当普通收入 |
| `Income` | 含“还款” | 1 | 0 | false | **B 口径**：别人还款给我按普通收入 |
| `Income` | 其他（包括“微信转账”的入账方向） | 1 | 0 | false | 按 AA 类型；方向由 `paysubtype`/`is_payer` 决定 |
| 其他/空 | 任意 | — | — | — | null；不根据金额/商户猜 |

补充：

- `Expend + 转账` 仍然是 `0/0`；“转账”不是排除条件。
- `Transfer + 收款` 仍然是 `2/0`，例如“支付宝转账收款到余额宝”是账户间调拨，不是收入。
- `Income + 退款` 的 `needsRefundLink=true` 只表示“必须找原账单”，不代表已经找到。
- 关键词匹配是规则名包含判断，不做分词；规则名来自 AutoAccounting manifest 的 `name` 字段。

## 4. 已确认口径（组合 B）

- **群收款保留当前例外**：`Expend + 群收款` → 支出 `0/0`；其他 `Expend + 收款/收入/收益/打款` → 收入 `1/0`。
- **`Income + 还款` 按普通收入 `1/0`**，用户明确选 B。
- **方向信任 AutoAccounting 的 `Expend/Income/Transfer` 输出**：`Expend` → 支出 `0/0`；`Income` → 收入 `1/0`；`Transfer` → 转账 `2/0`（还款/退款等规则名例外见 §3）。
- **不要用 `isSend=1` 强制改判支出**；微信转账方向由规则里的 `paysubtype` / `is_payer` 决定：
  - `paysubtype=1` + `isSend=1` → `Expend`（我转给别人）
  - `paysubtype=3` + `isSend=1` → `Income`（收款/别人给我）
  - `paysubtype=4` + `isSend=0` → `Income`（退款）
  - 网页形态 `is_payer=false` → `Income`
- **退款**：`Income + 退款` → `1/2` + `needsRefundLink=true`，不能按普通收入 `1/0` 入账；找不到原支出时保留“待关联退款”，不要丢弃。
- **还款**：`Expend/Transfer + 还款` → 转账 `2/1`；规范输出 2/1，不输出旧值 3。
- **Provider 分流**：支出、收入、转账、还款、退款都要进待记；真正无法判断的才标记待类型/待确认，不编造型。
- **第一阶段边界**：App 端完整导入、分类映射、资产两端、退款原账单关联是后续阶段；本轮没有假装已经完成。

## 5. 第一阶段实现

### 5.1 `BillTypeLocalizer`

`capture-contract/.../BillTypeLocalizer.kt` 按 §3 的顺序实现；关键变化：

- `Income + 还款` 从“返回 null”改为 `1/0`。
- `Income + 退款` 保持 `1/2` + `needsRefundLink=true`。
- `Expend/Transfer + 还款` 保持 `2/1`。
- `Expend/Transfer + 退款` 保持 null（方向矛盾，交给“待分类”）。
- `Expend + 群收款` 保持 `0/0`。

`LocalBillTypeMapping.isExpense` 仍表示“普通支出 0/0”，但不再是唯一可入库条件。

### 5.2 规则引擎返回 `ParseOutcome`

`AutoAccountingRuleEngine` 新增：

```kotlin
sealed interface ParseOutcome {
    data class Matched(val rule: ParsedRule) : ParseOutcome
    data object NoMatch : ParseOutcome
}
```

`ParsedRule`：

```kotlin
val type: String                 // AutoAccounting：Expend / Income / Transfer
val localType: Int?              // 0/1/2；null = 待分类
val localSubType: Int?           // 0/1/2；null = 待分类
val needsRefundLink: Boolean
```

行为：

- 规则命中且金额有效 → `Matched`，**任何类型都返回**，不再只返回支出。
- 本地类型无法判断（`Expend/Transfer + 退款`、未知 type）→ `Matched` 但 `localType=null`；金额/商户/时间仍保留。
- 规则未命中、来源不支持、引擎异常 → `NoMatch`，调用方保留探针值。
- 金额无效的命中会跳过并继续尝试后续规则；全部失败才 `NoMatch`。

日志示例（假数据）：

```text
matched rule=微信支付在线支付 type=Expend money=900 local=0/0 refundLink=false merchant=某商户 channel=微信[微信支付-在线支付]
matched rule=微信支付商家退款 type=Income money=1234 local=1/2 refundLink=true merchant=某商户 channel=微信[商家退款]
matched rule=支付宝花呗主动还款 type=Transfer money=10000 local=2/1 refundLink=false merchant=- channel=支付宝[花呗还款]
matched-but-unclassified rule=某笔退款 type=Expend money=500 local=-/- refundLink=false merchant=- channel=-
```

### 5.3 契约字段与插件 Room v4

`CapturePayload` / `PaymentCapture` 新增：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `billType` | `Int?` | 本地主类型 0/1/2；null = 待分类 |
| `subType` | `Int?` | 本地子类型 0/1/2；null = 未识别 |
| `needsRefundLink` | `Boolean` | 退款必须关联原支出 |
| `toAssetName` | `String?` | 转账/还款的转入资产名 |
| `categoryName` | `String?` | AutoAccounting category.js 推断的分类名 |

插件数据库 `capture.db` 升到 v4：

```kotlin
MIGRATION_1_2: billType / subType / needsRefundLink
MIGRATION_2_3: toAssetName
MIGRATION_3_4: categoryName
```

现有行保持旧值，不重算、不丢数据；旧行在 UI 上显示为“待分类”。当前没有自动回填，后续可一次性重跑规则或手动选类型。`CaptureRepository.toEntity` 已映射这些字段。

### 5.4 Provider 分流

`CaptureIngestProvider.enrichWithRules`：

| 规则结果 | 写入待记 | 类型字段 |
| --- | --- | --- |
| `NoMatch` | 是（保留探针金额/商户/时间） | 保持 payload 原值；通常为 null → 待分类 |
| `Matched` + `localType != null` | 是 | 写 `billType/subType/needsRefundLink` |
| `Matched` + `localType == null` | 是 | 不编造，保留 payload 原值 → 待分类 |

- 所有识别出的类型都插入 `PENDING`，不再只允许 Expend。
- 支出仍走原来的规则解析/金额/商户/时间路径；87 条支出的行为不变。
- 未命中/未知类型插入为“待分类”，不会因为无法判断被丢弃。
- 退款命中后 `needsRefundLink=true`；直接入账时 App 会尝试按分类/金额/时间匹配原支出，匹配不到返回 `needs_refund_link`，留在待关联。
- 插件本地状态仍是 `PENDING`；“入账”/App 回执推进为 `RECORDED`。

### 5.5 待记列表与“自己记”

`PendingListActivity` 只做最小改动，没有重做 UI：

- 每行标题显示本地类型：`支出 / 收入 / 转账 / 还款 / 退款待关联 / 待分类`。
- 状态行补充 `待手动选择类型` / `待关联原支出`。
- “自己记”按 `billType/subType` 透传预填：
  - 支出 → App 表单支出；
  - 收入 → App 表单收入；
  - 转账 / 还款 → App 表单转账（还款为转账+还款子类型），并提示确认转账/还款类型与两端资产；若 App 资产功能关闭会退回支出，需要第二阶段处理；
  - 待分类 → **不写 `type`**，并在提示里要求用户在悬浮窗手动选择，避免强行打开成支出；
  - 退款 → “自己记”仍先弹“退款待关联”，不从悬浮窗生成无关联普通收入。
- 每行新增“入账”：类型明确时直接调 App `importBills`；转账/还款要求两端资产可匹配；退款会尝试自动匹配原支出，匹配不到留在待关联。
- “备注 / 忽略 / 模拟 / 刷新”保持原行为。

## 6. 测试与构建

### 单元测试

```powershell
.\gradlew.bat :capture-contract:test
```

结果：`BUILD SUCCESSFUL`，`BillTypeLocalizerTest` 7 个测试、0 失败。

覆盖：

- 普通支出、支付宝消费/账单列表、出账转账、群收款例外
- 支出规则名含收款/收入/收益/打款时按收入兜底
- `Expend/Transfer + 还款` → `2/1`，且断言不输出旧值 `3`
- `Income + 还款` → `1/0`（B 口径）
- 普通转账、转账收款到余额宝、提现 → `2/0`
- `Income + 退款` → `1/2` + `needsRefundLink=true`
- `Income + 其他`、`Income + 转账` → `1/0`
- `Expend/Transfer + 退款`、未知/空类型 → null
- 空规则名/null 规则名仍按主类型
- 常量与 App `Bill` 的 0/1/2/3、0/1/2 对齐

### App 单测

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.taostudio.tapaccounting.data.local.entity.BillTypeAlignmentTest"
```

结果：`BUILD SUCCESSFUL`，1 个测试、0 失败。

### 备用机合成数据回归

备用机 `172.20.1.22:41103` 无 root，不走微信/支付宝 hook；用插件 `CaptureIngestProvider` 直接注入合成事件：

- 合成收入/转账/还款/退款/待分类各 1 条，5/5 入库，列表类型标签正确。
- 转账打开“自己记”：类型=转账，资产自动折成“招商银行”→“微信”，保存成功，App 生成 type=2 账单。
- 还款打开“自己记”：类型=还款，资产“招商银行”→“花呗”，保存成功，App 生成 type=2/subType=1。
- “入账”直接调 App `importBills`：合成转账成功生成 type=2 账单并写 `external_imports`。
- 退款“入账”在找不到原支出时返回 `needs_refund_link`，留在待关联；没有写成普通收入。
- 测试后 App/插件 DB 均恢复到测试前，未留下合成账单。

### 构建

```powershell
.\gradlew.bat :app:assembleDebug :capture-plugin:assembleDebug
```

结果：`BUILD SUCCESSFUL`。

本轮没有安装到设备，也没有重跑 2 月 87 条真实样本；87 条支出的保证来自“这些规则名都映射 0/0 + 支出分支未改”。测试全部使用手写假规则名/假金额，没有复制 `.tmp_autoaccounting_pull/` 里的真实个人数据。

## 6.5 AutoAccounting 全链路对照（2026-11-04）

参考源码：

- `app/.../xposed/core/utils/AnalysisUtils.kt`
- `.../hooks/wechat/hooks/DatabaseHooker.kt`
- `server/.../db/model/AppDataModel.kt`
- `server/.../tools/BillService.kt`
- `server/.../tools/BillManager.kt` / `TransferRecognizer.kt` / `DuplicateDetector.kt`

AutoAccounting 的做法：

1. hook 侧先做 `MD5(raw)` 去重（30 万条窗口），再走支付关键词白名单/黑名单。
2. 原始事件通过 `JsAPI.analysis` 上报，服务端**每条都写入 `AppDataModel`**；`match=false` 的丰巢、普通通知也会留在数据页，但不生成账单。
3. 规则命中才生成 `BillInfoModel`；未命中返回“未分析到有效账单”，不弹记账窗。
4. 账单再走 `BillManager.groupBillInfo`：
   - `TransferRecognizer` 在时间窗内按金额合并 Income/Expend/Transfer；
   - `DuplicateDetector` 按金额/时间/类型/渠道合并重复通知。
5. 最后才做资产映射、分类、保存、悬浮窗/回填。

本插件的对应实现：

- `CaptureForwarder` 已有 30 秒 raw MD5 去重。
- `CaptureIngestProvider` 现在**只把规则命中或显式带类型的 payload 建成待记**；`NoMatch` 只记日志，不生成账单。
- `CaptureRepository` 按 `source + sourceTxnId` 幂等；转账幂等键为 `transcationid:paysubtype`，转账 `:1` 与退款 `:4` 不再互相覆盖。
- 转账/退款事件如果是 WCDB `message` 表的 insert，可由现有 hook 直接拿到；AutoAccounting 另有 `TransferHooker`（`onGYNetEnd`）作为转账 UI 回调备用，本插件尚未接，后续按实际漏抓情况补。
- 丰巢等非支付 `mMap` 在 hook 侧跳过；RichText 缓存只挂到支付语义的 mMap。

⚠️ 本次真机问题：AutoAccounting 在 00:08 有两条 `paysubtype=4`、`isSend=0` 的退款且 `match=true`；插件 DB 只有 00:05 的转出。插件进程当时仍加载旧模块（`sourceTxnId` 仍是 8 位 msg id），旧幂等键把退款当重复丢掉。APK 更新后必须**强制停止微信/支付宝再打开**，Xposed 才会加载新类。

## 7. 已完成与剩余优化

已完成：
- App `importBills` 已按 `type/subType/needsRefundLink` 分流；支出/收入/转账/还款/退款都可写。
- `AssetNameResolver` 把渠道户名折成本地资产；`CategoryNameResolver` 把 AA 分类折成本地分类。
- 退款直接入账会尝试匹配原支出；匹配不到返回 `needs_refund_link`，留在待关联。
- 插件“入账”按钮直接调 App `importBills`；转账/还款要求两端资产。
- 插件 Room v1→v4 迁移、旧数据保留；备用机合成数据验证通过。

剩余优化：
1. 分类别名表目前是保守映射，未知分类留空；后续用真实规则命中的分类统计再补。
2. 退款自动匹配依赖 App `findLikelyRefundSourceBill`；部分退款/多笔候选需要更明确的 UI。
3. 还款规则识别仍靠规则名含“还款”，部分信用卡规则可能漏。
4. 导入状态机、失败重试、旧行自动回填仍未做。
5. 真实微信/支付宝 hook 仍未在 root 设备验证；备用机无 root，只能用合成数据。
6. UI 仍是功能骨架，资产/分类手选流程可继续压缩。

## 8. 改动文件

- `capture-contract/src/main/kotlin/com/taostudio/tapaccounting/capture/contract/BillTypeLocalizer.kt`（B 口径）
- `capture-contract/src/main/kotlin/com/taostudio/tapaccounting/capture/contract/LocalBillType.kt`（`isExpense` 注释）
- `capture-contract/src/main/kotlin/com/taostudio/tapaccounting/capture/contract/CapturePayload.kt`（`billType` / `subType` / `needsRefundLink`）
- `capture-contract/src/main/kotlin/com/taostudio/tapaccounting/capture/contract/ImportBill.kt`（阶段一导入边界注释）
- `capture-contract/src/test/kotlin/com/taostudio/tapaccounting/capture/contract/BillTypeLocalizerTest.kt`（B 表驱动测试）
- `capture-plugin/src/main/java/com/taostudio/tapaccounting/capture/parse/AutoAccountingRuleEngine.kt`（`ParseOutcome`、可空本地类型、日志）
- `capture-plugin/src/main/java/com/taostudio/tapaccounting/capture/ingest/CaptureIngestProvider.kt`（类型分流、payload 字段透传）
- `capture-plugin/src/main/java/com/taostudio/tapaccounting/capture/data/PaymentCapture.kt`（v2 字段）
- `capture-plugin/src/main/java/com/taostudio/tapaccounting/capture/data/AppDatabase.kt`（`version = 2`、`MIGRATION_1_2`）
- `capture-plugin/src/main/java/com/taostudio/tapaccounting/capture/data/CaptureRepository.kt`（字段映射）
- `capture-plugin/src/main/java/com/taostudio/tapaccounting/capture/ui/PendingListActivity.kt`（类型标签、预填、退款提示）
- `docs/operations/bill-type-localization.md`（本文）
- `docs/operations/capture-plugin-handoff-2026-11-02.md`（进度同步）
