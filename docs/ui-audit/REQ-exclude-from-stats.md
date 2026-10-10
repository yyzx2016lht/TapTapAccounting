# 需求计划：账单「不计入收支统计」开关

> 面向外部 AI / 工程师的实现说明。背景与现状截图见 `screenshots/req_01_sheet_top.png`（详情弹层）和 `screenshots/req_05_overlay.png`（悬浮窗记账面板）。
> 仓库：Android，Kotlin + XML 布局，包名 `com.taostudio.tapaccounting`。

---

## 0. 一句话

把「这笔账是否参与收支统计」做成一个**位置固定、文案恒定、状态可见**的开关，给多选补上批量入口，让列表能一眼看出哪些账不计入，顺带清掉一批废弃死代码。**不引入任何新的同步操作类型。**

### 任务清单

| # | 任务 | 章节 |
|---|---|---|
| 1 | 详情弹层按钮：文案/样式/隐藏条件/落库时序 | §3 |
| 2 | 多选批量「不计入」+ 退出多选收窄成 ✕ + 别人的账单不可选中 | §4 §5 |
| 3 | 删除「单笔/多笔」死代码 | §6 |
| 4 | 全屏详情页：UI 自行设计，但落库逻辑必须收敛到一处 | §7-B |
| 5 | 账单列表金额灰化 | §8 |

---

## 1. 这个属性的确切语义

`Bill.excludeFromStats: Boolean`（`data/local/entity/Bill.kt:69`，数据库默认 0/false）。

| 会受影响（读了这个字段） | 位置 |
|---|---|
| 统计页的支出/收入合计、分类占比、环比 | `data/local/dao/BillDao.kt:92, 120, 152, 184`（SQL 全部带 `AND excludeFromStats = 0`） |
| 预算进度 | `logic/BudgetService.kt:88-95`，注释原文「排除 excludeFromStats = true 的账单」 |
| AI 洞察卡 | `logic/insight/InsightEngine.kt:33, 36`，筛选条件 `!it.excludeFromStats` |
| 预算口径下的收支贡献 | `logic/BillStatsContribution.kt:19` |

| **不**受影响 | 说明 |
|---|---|
| 账单列表显示 | 照常显示 |
| **账户 / 资产余额** | `logic/BillAssetImpactService.kt` 只看 `subType`，对 `excludeFromStats` **零引用** → 余额照常变动 |
| 余额轨迹、账单笔数 | 照常 |

一句话：**钱该扣还是扣、余额照样变，但所有统计口径里看不见它。**

### 1.1 哪些账单会变成 `true`

**99% 以上是 `false`。** 来源只有这几处：

| 来源 | 位置 |
|---|---|
| 换币平账（历史数据迁移） | `data/local/AppDatabase.kt:309-319` |
| 投资自动收益（固定 true） | `logic/InvestmentInterestService.kt:518` |
| 余额调整时用户手选"不计入" | `BalanceAdjustmentActivity.kt:156` |
| CSV 导入的"不计收支"类型 | `data/backup/CsvManager.kt:33` |

### 1.2 还有一个隐藏的排除条件

```kotlin
// logic/BillStatsContribution.kt:19
if (bill.excludeFromStats || bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED) {
    return BillStatsContribution()
}
```

`SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED = 4`（`Bill.kt:88`）也会让账单不参与统计，**即使 `excludeFromStats == false`**。

- 存量数据已被 `AppDatabase.kt:308-319` 的迁移归一化（subType 3/4 → `subType = 0` + `excludeFromStats = 1`）
- 现在**唯一**的写入方 `AddAssetActivity.kt:721` 永远写 `subType = SUBTYPE_NORMAL`
- 所以只剩 **CSV 导入**能带进来（`CsvManager.kt:259`）

> **已知遗留 bug（不在本次范围，另开单）**：`BillDao` 的汇总 SQL 只判 `excludeFromStats = 0`，没有判这个 subType，与 `BillStatsContribution` 口径不一致。

---

## 2. 现状与要解决的问题

### 2.1 详情弹层布局

`res/layout/layout_bill_detail_bottom_sheet.xml`：

```
详情                    [计入] [退款] [编辑] [删除]      ← 动作行，第 44-89 行
──────────────────────────────────────────
金额(72dp)                      -¥11.00               ← 第 119-152 行
分类                         吃的 - 三餐
账户                                微信
时间      2026-09-29 11:15
备注                                凉面
账本                            伙食账本
```

弹层共 6 行内容，**正好填满可用高度且不需要滚动**。硬约束：**不得新增行、不得增高。**

### 2.2 要解决的三个问题

1. **标签歧义**：标签原本表示"当前状态"（现在计入就显示「计入」），于是"标签说的"和"点击做的"相反
2. **位置竞争**：「计入」是唯一的实心主按钮，被抬成这屏主操作，但它只是个可逆开关
3. **无批量入口**：多选模式只能移动/删除，改不了这个属性

### 2.3 这个弹层被 5 处复用

| 调用点 | 是否显示该按钮 |
|---|---|
| `ui/main/home/HomeBillSheetsController.kt:313` | 显示（首页账单列表 → 详情） |
| `ui/main/home/BillDetailSheetHelper.kt:232` | 显示 |
| `ui/main/assets/AssetBillDetailSheetController.kt:450` | 显示 |
| `ui/main/home/CalendarActivity.kt:824` | 显示 |
| `ui/main/stats/StatsPopups.kt:578` | 强制 `GONE` |

开关逻辑已经抽在 `ui/common/BillDetailStatsToggle.kt`（4 处调用），本次改造在这个类里集中进行。

---

## 3. 任务一：详情弹层按钮

### 3.1 文案：标签 = 当前状态（不是点击后果）

这是本次最关键的决策，直接决定歧义是否消除。

| 当前状态 | 按钮文本 |
|---|---|
| `excludeFromStats == false`（计入中，默认） | **已计入** |
| `excludeFromStats == true` | **不计入** |

点击 = 翻转状态 + toast 说明**刚刚发生了什么**，然后按钮刷新。

> **为什么不用"点击后果"写法**（上一版试过，已放弃）：标签表示后果时，无论哪种写法，界面上都看不出这笔账现在算不算统计——标签描述的是"点下去会变成什么"。而标签表示状态时，用户一眼就知道当前状态；"点了会变成相反的"由 toast 补足。这是 Android/iOS 上状态按钮的标准形态。

### 3.2 文案对照表

| 用途 | 用这个 | 现成资源 |
|---|---|---|
| 按钮文本（计入中） | `已计入` | **需新增** |
| 按钮文本（已排除） | `不计入` | `R.string.include` / `R.string.exclude`（`strings.xml:721` / `:720`），但值是「计入」「不计入」，**「已计入」需新增** |
| toast（改为排除） | `已设为不计入收支统计` | **需新增** |
| toast（恢复计入） | `已恢复计入收支统计` | **需新增** |
| 全称参考 | — | `R.string.include_in_stats` / `exclude_from_stats`（`strings.xml:1662-1663`，值「计入收支统计」「不计入收支统计」，**当前零引用，可直接复用或改名**） |

命名遵循现有 toast 约定：前缀 `toast_`（`strings.xml:1091-1146` 集中定义），调用统一走 `Utils.kt:16` 的 `toast(ctx, msg)`。

### 3.3 文案已定：「已计入 / 不计入」

> ✅ **已确认**（需求方 2026-10-02）。最初提的是「已统计 / 不统计」，改成「已计入 / 不计入」，理由：
>
> 1. 这个 App 里"统计"单独出现时**一律**指"收支统计"（`strings.xml:1662` 的原文就是「计入**收支**统计」）。单说「不统计」第一反应是"这笔账不记录了"，但它其实照常显示在列表里
> 2. 「已计入 / 不计入」对称，「已计入 / 不统计」不对称

### 3.4 样式

保持描边，不要实心。与「退款」「编辑」同级：

```xml
android:background="@drawable/bg_dialog_button_outline"
android:textColor="@color/text_secondary"
```

- `bg_dialog_button_primary`（实心）不要用于这个按钮
- 选中态不做颜色区分——文案本身已经表达了状态，再加颜色只会增加噪音

### 3.5 隐藏条件（四条，任一命中就不显示）

| 条件 | 判断依据 | 理由 |
|---|---|---|
| 退款单 | `isRefund` | 退款账单的统计归属跟着原账单走（见 §3.6） |
| 转账 / 还款 | `type != TYPE_EXPENSE && type != TYPE_INCOME` | `BillStatsContribution.kt:29-33` 只有 EXPENSE/INCOME 有贡献，`else` 返回全 0 → 切了没有任何可见效果 |
| **别人创建的共享账单** | 见 §5 | `SharedMutationHooks.requireOwner` 会抛异常 |
| `subType == SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED` | 见 §1.2 | 它的排除状态由 subtype 决定，这个开关改不动它，点了会"没反应" |

> 注：数据模型上还款是独立的 `TYPE_REPAYMENT = 3`（`Bill.kt:82`）和 `SUBTYPE_REPAYMENT = 1`（`Bill.kt:85`），不是转账的 subtype。但 UI 语义上它属于"账户间挪钱"（共用 `layout_account_2` + `btn_swap_accounts`），且两者都不参与收支统计，所以隐藏条件统一按 `type` 判。

### 3.6 退款单：按钮不显示，但状态要可见

> ✅ **已确认**：退款单**不显示**该按钮。

退款单本身隐藏按钮，但它引用的原账单可能是不计入的，这个状态必须传达：

- 弹层底部已有的 `layout_original_bill_section`（`layout_bill_detail_bottom_sheet.xml:427+`）里，原账单金额旁应给出「不计入」的提示
- 修改方式：点原账单进入它自己的详情去改。退款的统计归属跟着原账单走，不在退款单上单独改

### 3.7 时序：先写库成功，再刷界面

**这是必须修的正确性问题。** 现有实现先翻转本地变量、刷新按钮，再异步写库：

```kotlin
// logic/BillMutationService.kt:480-489
suspend fun setExcludeFromStats(db: AppDatabase, bill: Bill, exclude: Boolean) {
    db.withTransaction {
        val latest = db.billDao().getBillById(bill.id) ?: return@withTransaction
        SharedMutationHooks.requireOwner(db, latest)          // ← 共享账本非所有者会抛
        val saved = SharedMutationHooks.prepareLocalBill(db, latest.copy(excludeFromStats = exclude))
        db.billDao().updateBill(saved)
        SharedMutationHooks.enqueueSaved(db, saved)
    }
    notifyWidgetsChanged()
}
```

`requireOwner` 抛异常时（`SharedMutationHooks.kt:34`），界面已经显示"改好了"而数据库没改 → 用户看到的状态和统计对不上。

**正确时序：**

```
点击
 └→ 按钮置为 loading / 禁用（防连点）
 └→ 后台调用 setExcludeFromStats()
      ├→ 成功 → 刷新按钮文本 + toast「已设为不计入收支统计」
      └→ 失败 → 按钮恢复原状 + toast「只有自己创建的账单才能修改」（或 requireOwner 的原始文案）
```

### 3.8 落库失败的用户可见性

`requireOwner` 的异常文案是 `"只能编辑自己创建的共享账单"`（`SharedMutationHooks.kt:34`），是给开发看的。UI 层应映射成用户能懂的 toast。

---

## 4. 任务二：多选模式批量「不计入」

### 4.1 现状

`res/layout/fragment_home.xml:384-442`，4 个等分按钮（全部 `layout_width="0dp"` + `layout_weight="1"`，48dp 高，8dp 间距）：

| id | 文案 | 颜色 | 逻辑 |
|---|---|---|---|
| `btn_ms_cancel` | 退出多选 | 灰 | `HomeMultiSelectController.kt:38-40` → `adapter.clearSelection()` |
| `btn_ms_select_all` | 全选 | 深灰 | 同文件 `:41-49` |
| `btn_ms_move_book` | 移动 | 品牌紫 | 同文件 `:50-54` |
| `btn_ms_delete` | 删除(N) | 危险红 | 同文件 `:55-` |

同一套 4 按钮在另外 4 个布局里也有一份：`activity_calendar.xml:249-310`、`activity_bill_search.xml:83-145`、`activity_asset_detail.xml:254-313`、`activity_asset_stats.xml:412-471`。

### 4.2 需求

新增第 5 个按钮 **「不计入」**，点击把所有选中的账单一次性设为不计入统计。

> ✅ **已确认：「退出多选」保留，但收窄成 ✕ 图标。**
>
> 原提案是"把退出多选替换成不统计"，已否决——那会丢掉**唯一的显式退出入口**（BACK 键虽可退出，见 `HomeFragment.kt:273-274`，但不可发现，第一次用的人会以为卡住了）。
>
> 实施要点：
> - `btn_ms_cancel` 从 `layout_weight="1"` 改为固定宽度 48dp，内容换成 ✕ 图标
> - 其余 4 个按钮继续 `layout_weight="1"` 等分，实际宽度变化很小
> - 5 个按钮在 5 个布局里都要改（见 §4.1）

### 4.3 批量落库：必须新写 service 方法

`BillDao.kt:637` 已有批量方法，**但是死代码（零调用）**：

```kotlin
@Query("UPDATE bills SET excludeFromStats = :exclude WHERE id IN (:billIds)")
suspend fun updateExcludeStatsForBills(billIds: List<Long>, exclude: Boolean)
```

**不能直接调它**——单笔版 `BillMutationService.setExcludeFromStats` 里有两样批量版没有的东西：

| 缺失项 | 后果 |
|---|---|
| `SharedMutationHooks.requireOwner(db, latest)` | 共享账本里非所有者能改别人的账单 → **越权** |
| `SharedMutationHooks.enqueueSaved(db, saved)` | 改了不同步给其他成员 → **数据分叉** |
| `notifyWidgetsChanged()` | 桌面小组件不刷新 |

所以要照着单笔版新写一个批量方法，**逐条校验权限后再在一个事务内提交**（`SharedMutationHooks.deleteBillsPermanently:58-74` 是这个模式的现成范例：先整批校验，再 `db.withTransaction { }`，最后 `SharedSyncScheduler.enqueueNow`）。

### 4.4 「全选」要跟着过滤

`HomeAdapter.kt:300`：

```kotlin
selectedBills.addAll(rawBills.filterNot { it.isDeprecated }.map { it.bill })
```

当前只排除了 `isDeprecated`，**没有排除别人的账单**。见 §5。

---

## 5. 共享账本的权限规则

### 5.1 怎么判断"自己的 / 别人的"

`Bill` 上的共享字段（`data/local/entity/Bill.kt`）：`isShared`、`sharedId`、`memberId`、`sharedRevision`、`sharedDeviceId`、`relatedSharedId`。

本机成员身份：`db.sharedLedgerDao().getByBookName(bookName)?.localMemberId`。

现成判断：

```kotlin
// data/sync/SharedMutationHooks.kt:31-35
suspend fun requireOwner(db: AppDatabase, bill: Bill) {
    if (!bill.isShared) return
    val local = db.sharedLedgerDao().getByBookName(bill.bookName)?.localMemberId ?: return
    require(bill.memberId == local) { "只能编辑自己创建的共享账单" }
}
```

> `AssetDetailActivity.getSelectableBills()`（`:1003`）和 `AssetStatsActivity.getSelectableBills()`（`:2233`）**名字像是这个过滤，实际上只是返回全部 `BillRow`，没有任何归属过滤**。不要误以为已有现成实现。

### 5.2 规则

| 场景 | 行为 |
|---|---|
| 非共享账本，任何账单 | 按钮显示 |
| **共享账本，自己创建的账单** | 按钮显示，行为照常 |
| **共享账本，别人创建的账单** | **按钮不显示**；多选时**不可选中**（复选框禁用且点击无效） |

「想让别人看不到这笔账」→ 用已有的**「移动」**移到别的账本（`SharedMutationHooks.moveBills:83-104`，会给对方发 delete + 你这边转成本地账单）。**不需要新增"不共享"状态。**

### 5.3 为什么不做「不共享 / 已共享」

需求方最初提议共享账本里按钮改成「已共享 / 不共享」。评估后建议放弃：

1. **能力重复**：`moveBills` 已经完整实现了等价且语义正确的出口
2. **会撞上难题**：对方那边的副本该变成什么？发 delete = 撤回（不是不共享）；保留但不同步需要给同步协议加**第四种操作类型**（现在只有 create / update / delete）；不发同步 = 两边永久不一致
3. **会产生死按钮**：`SharedLedgerService.kt:359` 的 `isShareable()` 规定余额调整、还款、非收支类型**永远不会共享**，它们 `isShared` 恒为 false，文本会显示「不共享」——**但点了什么也不会发生**

> **决策：按钮语义统一，不分账本类型。恒定表达"这笔账进不进收支统计"。**

---

## 6. 任务三：删除「单笔/多笔」死代码

「单笔/多笔」切换器已被废弃，但控件还留在布局里、被代码强制隐藏。

### 6.1 要删的

| 位置 | 内容 |
|---|---|
| `res/layout/layout_floating_window.xml:52-98` | `layout_bill_mode_switch` 整个节点，内含 `tv_bill_mode`、`rg_bill_mode`、`rb_single`、`rb_multi` |
| `logic/AccountingFormController.kt:313` | `rootView.findViewById<View>(R.id.layout_bill_mode_switch)?.visibility = View.GONE` |
| `OverlayManager.kt:191-192` | 对 `layout_bill_mode_switch` 和 `rg_bill_mode` 的两行 GONE |

删完确认 `rg_bill_mode` / `rb_single` / `rb_multi` / `layout_bill_mode_switch` / `R.string.bill_mode` / `R.string.single_bill` / `R.string.multi_bill` 全部零引用。

### 6.2 不要误删

`Prefs.isMultiBillEnabled` / `Prefs.isMultiBillNotSync`（`Prefs.kt:316, 320`）**仍在使用**，但语义完全不同——那是"AI 识别出一堆账单时要不要自动入账"，被 `OverlayManager.kt:135` 和 `AccountingFormController.kt:2440` 引用。`strings.xml:1104` 的 `toast_multi_bill_canceled` / `:1124` 的 `toast_multi_bill_recognition_canceled` 同理，都属于 AI 识别流程。

---

## 7. 任务四：不在本次范围、但需单独开单

| 编号 | 问题 | 位置 |
|---|---|---|
| A | `BillDao` 汇总 SQL 未判 `SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED`，与 `BillStatsContribution` 口径不一致 | `BillDao.kt:92,120,152,184` vs `BillStatsContribution.kt:19` |
| B | 全屏详情页 `BillDetailActivity` 有一个独立的 Switch（`switchExcludeStats`，`:214`），与弹层按钮是两套 UI、语义不同 | `BillDetailActivity.kt:204, 214, 666`。该页入口被 `Prefs.isIndependentDetailEnabled` 门控（`HomeBillSheetsController.kt:277`），默认到不了 |

> **B 的 UI 形态由实现方自行设计**（用开关、用按钮、用弹窗都可以），但有一条硬约束：
>
> **两处必须共用同一套落库逻辑，不能各自实现。** 具体是：
> - 都必须走 `BillMutationService` 的同一个方法（或同一个新抽出的方法）
> - 都必须经过 `SharedMutationHooks.requireOwner` 权限校验
> - 都必须经过 `SharedMutationHooks.enqueueSaved` 同步入队
> - 都必须在写库成功后才刷新界面（§3.7 的时序）
>
> 只要逻辑收敛到一处，UI 长什么样都不会造成状态分叉。反之如果两处各写一遍，就会出现"在弹层改了、去全屏页显示还是旧的"这类 bug。

---

## 8. 任务五：账单列表里灰化不计入的金额

> ✅ **已确认：要灰化**（需求方 2026-10-02）。

### 8.1 现状

`ui/main/home/HomeAdapter.kt:691-701` 的金额颜色只看 `isDeprecated` / `isRefund` / `type`，**完全不看 `excludeFromStats`** → 不计入的账单在列表里显示的是正常的红/绿，和普通账单没有任何区别。

```kotlin
if (isDeprecated) {
    tvAmount.setTextColor(amountColorRefund)
} else if (isRefund) {
    tvAmount.setTextColor(amountColorRefund)
} else if (bill.type == Bill.TYPE_EXPENSE) {
    tvAmount.setTextColor(amountColorExpense)
} else if (bill.type == Bill.TYPE_INCOME) {
    tvAmount.setTextColor(amountColorIncome)
} else {
    tvAmount.setTextColor(amountColorOther)
}
```

### 8.2 改动

在这段判断里加入 `excludeFromStats` 分支，优先级建议如下：

```
isDeprecated  → 灰        （已废弃，最高优先）
bill.excludeFromStats → 灰  （本次新增）
isRefund      → 灰
type          → 红/绿/其他
```

同时要检查并对齐的其他列表（它们各自复制了一份同样的颜色判断）：

| 列表 | 位置 |
|---|---|
| 首页账单列表 | `ui/main/home/HomeAdapter.kt:691-701` |
| 账单搜索结果 | `ui/main/home/BillSearchActivity.kt` |
| 日历 | `ui/main/home/CalendarActivity.kt:523, 753` |
| 资产详情流水 | `ui/activity/AssetDetailActivity.kt:1337` |
| 资产统计流水 | `ui/activity/AssetStatsActivity.kt:2486` |
| 历史账单 | `ui/activity/HistoryBillActivity.kt:572` |
| 详情弹层 / 全屏详情页 | `HomeBillSheetsController.kt:99, 382`、`BillDetailSheetHelper.kt:141`、`AssetBillDetailSheetController.kt:110, 384`、`BillDetailActivity.kt:434` |

> 这份"金额颜色判断"在 9 个地方各写了一遍，是本仓库已有的重复。本次**至少**要覆盖首页和详情弹层；其余的一致性顺带做掉更好（可抽成一个共用函数）。

### 8.3 用哪个灰

现有灰色常量（都在 `HomeAdapter.kt:67-68`）：

| 常量 | 色值 | 现在的语义 |
|---|---|---|
| `COLOR_AMOUNT_REFUND` | `#9AA1AA` | 退款 / 已废弃 —— "这笔不算真实支出或收入" |
| `COLOR_AMOUNT_OTHER` | `#757575` | 转账 / 还款等非收支类型 |

**建议复用 `#9AA1AA`**：它的语义（不算真实收支流）和"不计入统计"属于同一族，视觉上也一致。

> ⚠️ **待确认 —— 唯一的遗留问题**
>
> 复用 `#9AA1AA` 会带来一个副作用：**退款行和"不计入"行在列表里金额颜色完全相同**，光看金额分不出来。
>
> 两个选项：
> - **接受**：靠分类名/备注/类型符号区分。两者本来语义相近，共用一个灰也说得通
> - **区分**：在不计入的行上额外加一个极简标记（如副行末尾加「不计」二字，或分类名弱化一档）。因为不计入的账单占比 <1%，加标记不会产生噪音，反而能精准定位

---

## 9. 验收清单

### 9.1 详情弹层按钮

- [ ] 普通账本详情弹层：按钮显示「已计入」，点击后变「不计入」并 toast「已设为不计入收支统计」，再点变回「已计入」并 toast「已恢复计入收支统计」
- [ ] 按钮为描边样式，不与「退款/编辑/删除」抢视觉权重
- [ ] 转账单、还款单打开详情：**无**该按钮
- [ ] 退款单打开详情：**无**该按钮；原账单区域能看出原账单是否不计入
- [ ] `SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED` 的账单：**无**该按钮
- [ ] 写库失败（如共享账本竞态）时按钮回到原状并 toast 提示，不会出现"界面说改了但没改"
- [ ] 弹层高度未增加，仍不需要滚动

### 9.2 共享账本权限

- [ ] 共享账本里点开**别人**创建的账单：**无**该按钮
- [ ] 共享账本里点开**自己**创建的账单：按钮显示且可改，改动会同步给其他成员
- [ ] 共享账本多选：别人的账单复选框禁用，点不动
- [ ] 别人的账单点「移动」「删除」也不会被绕过（沿用现有 `requireOwner`，不要新增漏洞）

### 9.3 多选批量

- [ ] 多选栏出现 5 个控件，「退出多选」已收窄成 ✕ 图标（48dp 固定宽）
- [ ] 批量「不计入」只对可选中的（自己的）账单生效
- [ ] 批量操作成功后统计页数字即时变化
- [ ] 批量操作走新的 service 方法，经过 `requireOwner` + `enqueueSaved`（不是直接调 DAO）

### 9.4 列表灰化

- [ ] 首页账单列表里不计入的金额显示为灰色，不再是红/绿
- [ ] 退款行、已废弃行的灰色不受影响
- [ ] 转账/还款行仍是 `#757575`，不被误改
- [ ] 详情弹层里不计入的账单金额同样为灰色

### 9.5 死代码清理

- [ ] `rg_bill_mode` / `rb_single` / `rb_multi` / `layout_bill_mode_switch` / `R.string.bill_mode` / `R.string.single_bill` / `R.string.multi_bill` 全库零引用
- [ ] `Prefs.isMultiBillEnabled` / `isMultiBillNotSync` **仍在**（属于 AI 识别流程，不能删）

### 9.6 回归

- [ ] `BillStatsContribution.kt` / `BudgetService.kt` / `InsightEngine.kt` 的统计口径未受影响
- [ ] 全屏详情页 `BillDetailActivity` 的开关与弹层按钮状态一致（走同一套落库逻辑）
- [ ] 金额键盘、悬浮窗记账、预算进度条等本轮之前的改动未受影响

---

## 10. 相关文件速查

| 关注点 | 文件 |
|---|---|
| 弹层布局 | `res/layout/layout_bill_detail_bottom_sheet.xml` |
| 开关逻辑（要改造） | `ui/common/BillDetailStatsToggle.kt` |
| 单笔落库 | `logic/BillMutationService.kt:480-489` |
| 批量 DAO（死代码） | `data/local/dao/BillDao.kt:635-637` |
| 共享权限钩子 | `data/sync/SharedMutationHooks.kt` |
| 共享判定 | `data/sync/SharedLedgerService.kt:359` |
| 多选控制器 | `ui/main/home/HomeMultiSelectController.kt` |
| 多选布局（5 份） | `fragment_home.xml:384-442`、`activity_calendar.xml:249-310`、`activity_bill_search.xml:83-145`、`activity_asset_detail.xml:254-313`、`activity_asset_stats.xml:412-471` |
| 选中状态 | `ui/main/home/HomeAdapter.kt`（`selectedBills` / `selectAll` / `clearSelection`） |
| 金额颜色判断（9 份重复） | 见 §8.2 表格 |
| 全屏详情页的旧开关 | `ui/activity/BillDetailActivity.kt:204, 214, 666` |
| 统计口径 | `logic/BillStatsContribution.kt`、`logic/BudgetService.kt`、`logic/insight/InsightEngine.kt`、`data/local/dao/BillDao.kt` |
| toast 工具 | `Utils.kt:16` |
| 死代码 | `res/layout/layout_floating_window.xml:52-98`、`logic/AccountingFormController.kt:313`、`OverlayManager.kt:191-192` |

---

## 11. 待确认清单

只剩一条：

1. **列表灰化用哪个灰？** 建议复用 `#9AA1AA`（退款同色），代价是退款行和"不计入"行光看金额分不出来。接受？还是加一个极简标记区分？（见 §8.3）

其余已在 2026-10-02 确认：

- 按钮文本用「已计入 / 不计入」（§3.3）
- 「退出多选」保留并收窄成 ✕（§4.2）
- 退款单不显示按钮，但原账单区域要能看出状态（§3.6）
- 列表里要灰化（§8）
- 全屏详情页的 UI 形态由实现方设计，但落库逻辑必须收敛到一处（§7-B）