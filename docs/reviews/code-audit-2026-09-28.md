# 续扫报告（第二轮）：投资结息期次锚点错误

| 项 | 值 |
|----|-----|
| 日期 | 2026-09-28 |
| 范围 | `:app` 主模块（346 个 Kotlin 源文件）；聚焦上一轮 `code-audit-2026-09-22.md` 未覆盖面 + 未提交改动回归 |
| 基线 | `assembleDebug` ✅ · `testDebugUnitTest` ✅ 全绿（未修改任何生产代码） |
| 上一轮状态 | P0-1~P0-6、P1-1~P1-29、P2-1~P2-25 全部已 `[x]` 修复（对照本目录 09-22 报告） |
| 本轮结论 | **发现 1 处资金级缺陷（P0，已用探针测试复现）+ 3 处次要问题；未发现新的崩溃/丢数据级问题** |

---

## 1. P0 — 修改理财收益配置后，结息凭空多计（已验证）

### 现象

在「资产详情 → 理财产品 → 编辑批次」修改年化利率/结息周期，或暂停后再启用之后，
**下一次自动结息会把「起息日到今天」的全部历史按日重新走一遍**，一次性多计一大笔利息，
生成一笔虚高的「自动结息」收入账单，并同步抬高该资产余额。

### 复现（临时探针，测完已删除，未留改动）

固定条件：本金 10000 CNY、年化 3.65%、日结、起息 2026-01-01、修改日 2026-06-15、结息日 2026-06-16。

| 批次状态 | `settledPeriods` | `postedInterest` | 结余 |
|---|---|---|---|
| 正常锚点（`firstPayoutAt = 起息日+1`） | 1 期 | **1.00 元**（正确：1 天利息） | 10001.00 |
| `updateLotSchedule` 之后（锚在修改当天） | **166 期** | **166.36 元** | 10166.36 |

即一次性多计 **165 天利息**；本金与历史越长，放大倍数越大。

### 根因（三处假设不一致）

1. `logic/InvestmentInterestService.kt:187`（`updateLotSchedule`）
   `firstPayoutAt = firstPayoutFor(today, cycle)` —— 锚在**修改当天**，
   而 `startEarningAt` 保持原值不变 → 两者不再同源于起息日。
2. `logic/InvestmentInterestService.kt:569-583`（`payoutOccurrence`）
   `occurrence <= 0` 返回传入的 `first`；`occurrence >= 1` 却忽略 `first`，
   改用 `firstPayoutFor(startEarningDay = start, interval * (occurrence + 1))`。
   → 隐含假设「`firstPayoutAt == startEarningAt + k×周期`」，一旦被 1 打破，
   **期次序列不再单调递增**（第一期在 6/16，第二期跳回 1/3）。
3. `logic/InvestmentInterestService.kt:403-420`（`projectDueSettlement` 第二个循环）
   假定 `due` 随 `occurrence` 递增，逐期累计利息。序列回跳后，
   循环会从 `startEarningAt` 重新走到今天，`daysBetween` 得到 1 天的每一跳都被计息。

> 注：`getOpenLots()` 只取 `status = 0`，暂停批次不参与结算，所以「暂停期间不补算」这层是对的；
> 出错的是「暂停/改配置**之后**」的第一次结算。
> `git log -S` 显示该行自 `f85801d` 起即存在，属上一轮审计遗漏，非近期回归。

### 触发条件

批次已存在超过 2 个结息周期（日结即 2 天以上，非常常见），
用户改动利率 / 周期 / 暂停恢复后，到下一个结息日即触发；
`settleDueInterest` 由 `TapApplication.onCreate`、`InvestmentInterestWorker`（每日）、
`AssetsFragment` 三处调用，同一事务内完成，故必然落库。

### 修复方向（二选一，勿只改一处）

- **A（最小改动）**：`updateLotSchedule` 同时把 `startEarningAt` 挪到 `today`
  （与 `defaultScheduleForBalanceChange` 的语义对齐），使 `firstPayoutAt` 重新与起息日同源。
- **B（更稳）**：`payoutOccurrence` 在 `occurrence >= 1` 时以 `first` 为基准推进
  （`first + k×周期`），不再回落到 `startEarningAt`；并在 `projectDueSettlement` 里
  对 `due <= cursor` 的回跳做防御（把 `occurrence` 推到 `due > cursor` 之外）。
- 无论选哪种，都应补一条回归单测：**「修改 schedule 后跨日结算，期数与金额应与未修改时一致」**。

---

## 2. 次要问题（P2，均为展示/可用性，不写坏数据）

| # | 位置 | 问题 | 影响 |
|---|------|------|------|
| 1 | `logic/CreditCardCycleService.kt:55-78,187-198` | `billedSpend` / `billedRefund` / `paymentsInCycle` 全部按 `bill.amount`、`amountAtTransactionTime()` **原币**求和，未折算到卡币种；同文件链路上的 `AssetBillBalanceHistory` 却是折算过的 | 卡上存在非卡币种账单时，「本期账单金额 / 待还金额」混币相加 |
| 2 | `logic/StatsAmountResolver.kt:23-25` | 转账多币种且未筛币种时，`runCatching { convertToCny(...) }.getOrElse { bill.amount }` 汇率缺失静默退回原币金额当 CNY 汇总 | 与 P0-3「汇率缺失禁止静默跳过」原则不一致（仅统计页数字） |
| 3 | `logic/BillAssetImpactService.kt:222-237` | `ensureRatesForImpact` 无条件把 `bill.currency` 放进必需集合，即使账单币种与资产币种相同（本就无需换算） | 某币种汇率拉取失败时，**同币种**记账与结息也会被中止（P0-3 的过严副作用）；建议 `from == to` 时跳过 |

---

## 3. 未提交改动核查（图标本地化 + 亮屏恢复）

结论：**功能正确，未发现会致错的缺陷**，仅一处安全姿态回退需要注意。

- 图标本地化完整：`assets/icons/icon_map.json` **1128 条映射，0 条指向缺失文件**；
  源码里 3 个硬编码 `http://res*.qianjiapp.com/...`（`AssetIconDefaults.OTHER_ICON_URL`、
  `BillDisplaySettingsActivity.sampleIconUrl`、`InvestmentInterestService.CATEGORY_ICON`）
  **全部已被映射覆盖**；`BundledIconCoverageTest` 的覆盖度校验与本地文件校验均在。
- `BundledIcons.install()` 的 Glide `prepend` 方案对未内置 URL 返回 `handles()=false`，
  不影响聊天图片/票据照片等其它 String 加载；表加载失败时回落网络加载，不会崩。
- `OverlayService.kt:122-131`：屏幕广播由 `RECEIVER_NOT_EXPORTED` 改回 `RECEIVER_EXPORTED`。
  实际风险低（`ACTION_SCREEN_ON/OFF/USER_PRESENT` 是系统保护广播，第三方无法伪造，
  且接收方只读 `action` 不读 `extra`），但注释里「NOT_EXPORTED 收不到这些系统广播」的说法不准确，
  建议后续改成「EXPORTED + 显式 action 白名单」并保留注释说明原因。

---

## 4. 遗留未修（上一轮报告标记 `[x]`，实际未生效）

**P2-7 周期账单月末漂移 —— 参数加了但调用方没传，等于没修。**

`logic/RecurringBillingService.calculateNextExpected` 新增了 `dayOfMonthHint` 参数，
但全项目 **5 个调用点一个都没传**（`grep "calculateNextExpected("`）：

| 位置 | 是否传 hint |
|------|------------|
| `logic/RecurringBillDetector.kt:134` | ❌ |
| `logic/RecurringBillingService.kt:60` | ❌ |
| `logic/RecurringBillingService.kt:117` | ❌ |
| `logic/RecurringBillingService.kt:161` | ❌ |
| `logic/RecurringBillingService.kt:212` | ❌ |
| `ui/recurring/RecurringBillsActivity.kt:532` | ❌（此处 `dayOfMonthHint` 就在手边） |

→ 1/31 → 2/28 → 3/28 的月末漂移仍然存在。
（同批遗留的 `B5 AppListActivity MainScope 仅改名未 cancel` 亦未处理。）

---

## 5. 本轮已核验无问题的点（避免重复排查）

- 数据库迁移链 v5→v38 完整无缺口；未使用 `fallbackToDestructiveMigration()`。
- `DatabaseDowngradeHelper`：降级备份失败确实阻断清库（P0-4 已生效）。
- `BillMutationService` / `BillDeleteHelper` / `BillAssetImpactService` 的 apply/revert 对称性、
  退款 delta 方向、幂等跳过已级联删除账单，均正确且全程在事务内。
- `SharedSyncEngine.applyBillSnapshot` 的 `sharedId!!` 不可达：`SharedBillSnapshotCodec.isValid`
  已强制 `sharedId` 非空且为 UUID，解码失败即返回 null。
- `SharedSyncGate` 用不可重入 `Mutex`，但 `syncLedger` 的调用点均不在持锁块内 → 无死锁；
  `listOperations` 返回的相对路径与 `markProcessed` 写入的前缀一致 → 无重复下载。
- 密钥链路（`KeystoreSecretBox` AES-GCM + 随机 IV、`SecretEnvelope` 封套）正确，
  `PrefsAiSupport.readSecret` 的 `encrypted!!` 有 `isEncrypted` 前置保护。
- `CreditCardCycleService.getDueDay`（P2-8）、`BillDao` 统计 SQL 的 `amount * exchangeRate`
  语义与转账 `→目标币种` 分离，均与上一轮修复目标一致。

---

*生成：第二轮只读续扫 · 2026-09-28 · 未修改任何生产代码*
