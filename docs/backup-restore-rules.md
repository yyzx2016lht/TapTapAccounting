# 备份恢复判定规则

本文件描述恢复流程当前的判定规则，以及每条规则背后的原因。实现见
`data/backup/SharedRestoreModels.kt`、`data/repository/BackupRepository.kt`、`BackupActivity.kt`。

## 一、为什么共享账本会干扰恢复

共享同步协议只能承载两种实体：账单和预算（见 `data/sync/SharedOperationCodec.kt` 中
`entityType in setOf("bill", "budget")`）。分类、资产、规则、周期记账、聊天记录、设置都是
本机数据，没有共享标记，也不参与同步。

因此「本次恢复会不会和共享账本冲突」这个问题，只有一个答案来源：**本次恢复是否会写入账单或预算**。

历史上这条判定被写成了「本机有共享账本 + 选中了任意数据库模块」，分类、资产、规则、聊天都被算进去，
结果是用户只想改一个本地分类，也被要求先退出共享账本。这是本次修正的核心。

## 二、四个正交事实

恢复判定只依赖四件事，互不推导：

| 事实 | 来源 | 含义 |
| --- | --- | --- |
| `writesSharedEntities` | 恢复选项里的 `restoreBills \|\| restoreBudgets` | 本次恢复会不会写入共享实体 |
| `backupReadiness` | 备份里的共享账本描述 + 成员 + 坚果云凭据 | 备份能否恢复原成员身份（`READY` / `INCOMPLETE` / `NOT_PRESENT`） |
| `deviceHasActiveLedger` | 本机 `shared_ledger` 表是否非空 | 本机是否仍在同步某个共享账本 |
| `backupLedgersAlreadyOnDevice` | 备份里的 ledger uuid 是否都还在本机 | 是否属于「同机重连」 |

## 三、决策表

`decideSharedRestore(writesSharedEntities, backupReadiness, deviceHasActiveLedger, backupLedgersAlreadyOnDevice)`：

| writesSharedEntities | deviceHasActiveLedger | backupReadiness | backup 账本都在本机 | 决策 |
| --- | --- | --- | --- | --- |
| 否 | 任意 | 任意 | 任意 | `LOCAL_COPY`：直接按本地副本恢复，不弹任何共享相关弹窗 |
| 是 | 任意 | `READY` | 是 | `ASK_SHARED_MODE`：弹「共享账本恢复方式」（同机时只提供覆盖重连） |
| 是 | 是 | 任意 | 否 | `BLOCKED_BY_ACTIVE_LEDGER`：弹「共享账本正在同步」，只允许恢复本地数据 |
| 是 | 否 | `READY` | 否 | `ASK_SHARED_MODE`：弹「共享账本恢复方式」（重连 / 本地副本） |
| 是 | 否 | `INCOMPLETE` | 否 | `ASK_INCOMPLETE_IDENTITY`：告知只能恢复为本地副本 |
| 是 | 否 | `NOT_PRESENT` | 否 | `LOCAL_COPY` |

`requireSharedRestoreGuard(hasActiveSharedLedgers, writesSharedEntities)` 是决策表的最后一道兜底，
在 Room 事务内、任何表被改动之前执行，拒绝条件与 `BLOCKED_BY_ACTIVE_LEDGER` 完全一致。

### 为什么「备份账本都在本机」可以放行

本机仍连着共享账本、备份里也带着同一个账本时，恢复账单并不会把两个账本的数据混在一起：
`restoreSharedSnapshot` 按 uuid **upsert** 而不是 insert，账本行的本地 id 保持不变，引用它的成员、
outbox、同步状态都不需要搬家；成员表用 preflight 取回的**云端实时名册**覆盖，不会丢新加入的成员。

反过来，备份里带了一个本机没有的账本，恢复账单就会把两个账本的数据混到一起，因此仍然拦截。

同机场景下不提供「恢复为本地副本」：账本行还在，账单却被去掉共享标记，下次同步会把同一批账单再拉回来
变成重复。要恢复为不再同步的副本，用户应当先在账本列表里退出共享账本（那是已有且有确认的显式操作）。

同机重连也**不轮换设备 ID**：本机 outbox 里已有的操作都带着当前设备 ID，轮换会让同一台设备在
`sync_operation` 和 `sync_state` 里出现两个身份。只有真正的换机才轮换——旧设备的 ID 绝不能被复制到
另一台手机上。

## 四、被共享账本占用时的出路

命中 `BLOCKED_BY_ACTIVE_LEDGER` 时，界面不再让用户先选恢复方式再失败，而是前置说明并给出两条路：

- **只恢复本地数据**：用「用户实际勾选」减去账单、预算、聊天记录、聊天媒体，其余照常恢复。
  例如用户只勾了分类，得到的就是只覆盖分类。
- **取消**：用户自己去账本列表退出共享账本后再来。

注意用的是**用户实际勾选**，不是覆盖模式补齐后的集合，否则「只勾分类」会被补齐出的账单重新拖回冲突。

## 五、账单与分类的重新连接

账单保存的是**展示路径**（`吃的 - 三餐`），而 `categories.name` 存的是**叶子名**（`三餐`）。所以按名字
把账单匹配回分类，必须先取叶子。

这里曾经有一个静默 bug：`categoryNameCandidates` 只按 `>` 拆路径，而本应用写入的统一分隔符是
`" - "`（`CategoryNameNormalizer.UNIFIED_CHILD_SEPARATOR`）。结果是「按名字兜底找分类」这条分支对现行
数据永远不命中，凡是 `categoryIdMap` 覆盖不到的账单（例如合并恢复时没勾分类）都会丢掉 `categoryId`。

现在 `categoryNameCandidates` 会同时给出：归一化全名、`" - "` 叶子、`>` 叶子（兼容旧归档）、以及去掉
`退款：` 前缀后的同名变体。

恢复分类时还会执行一次 `relinkBillsWithoutCategory`，把 `categoryId IS NULL` 的账单按
**（叶子名, 收/支类型）唯一命中**重新连回分类：

- 命中唯一 → 回填 `categoryId`
- 同名同类型有多个（本应用允许同名分类）→ 跳过，不猜
- 名字已不存在于分类表 → 跳过，账单保留自己的 `categoryName` 快照

跳过而不是猜，是因为猜错会把支出记到收入分类上，而跳过最差也只是维持现状。

## 六、覆盖恢复的依赖规则
覆盖恢复会重建账单的 Room ID，所以任何存放账单 ID 的表必须同批重写：

```
{账单, 删除记录, 投资批次, 聊天记录}
```

这四者要么都不恢复，要么一起恢复（`requireOverwriteRestoreDependenciesAreComplete`）。

**分类和资产不在这个集合里**。它们按名称对齐，保留本机已有 ID：

- 备份里有、本机也有同名同类型 → 就地更新图标、排序、类型、父级，**ID 不变**
- 备份里有、本机没有 → 新增
- 本机有、备份里没有 → 删除（账单外键由 `SET_NULL` 自动断开，账单自己的 `categoryName` 快照仍在）

因此：

- 「只覆盖分类」不再需要连带恢复账单，也就不再触碰共享账本的冲突面
- 账单、投资批次、聊天记录里对分类和资产的引用不会因为重建 ID 而断裂
- 覆盖资产不会再把本机资产整体换 ID，账单的 `accountId` 只在资产真的被删除时才断开

## 七、不变量

1. 只要 `writesSharedEntities == false`，恢复流程不出现任何共享账本相关的弹窗或拦截。
2. `ASK_SHARED_MODE` 只在两种情况下出现：本机没有活跃共享账本，或备份里的账本本机都还在。
   两种情况都不会让 `restoreSharedSnapshot` 撞上 `shared_ledger.uuid` 唯一索引。
3. `restoreSharedSnapshot` 对账本行是 upsert，对 outbox 是 `insertIgnore` 合并，
   因此同机重连不会丢备份之后产生的待上传操作，也不会改变账本的本地 id。
4. 覆盖恢复后，本机账单对分类/资产的引用要么仍然有效，要么按覆盖语义被显式置空，不会指向无关记录。
5. 账单分类名的存储分隔符唯一：`" - "`（`CategoryNameNormalizer.UNIFIED_CHILD_SEPARATOR`）。
   恢复时的「按名字回填 `categoryId`」先经 `normalizeForStorage`，因此历史遗留的 `>`、`/::/`、
   `·` 等写法同样能匹配上分类。

## 八、同机重连后需要人工确认的点

同机重连会覆盖本机的共享运行状态，这些行为没有自动化测试覆盖（项目只有纯 JVM 单测，没有
Robolectric / instrumentation），发版前需要真机走一遍：

1. 两端各记几笔，本机还有未上传操作时用备份同机重连，确认未上传操作没有被丢弃、随后能上传成功。
2. 重连后确认另一端能看到本机的账单，本机也能拉到另一端的新账单。
3. 共享账本里另一方已退出/账本已解散时，确认 preflight 仍然按原逻辑拒绝重连。
4. 同机重连**不轮换设备 ID**（`reconnectDeviceId`），换机才轮换。否则同一台设备会在
   `sync_operation` 与 `sync_state` 里留下两个身份。

## 九、分类路径分隔符：唯一的规范格式

账单把分类存成**展示路径**（`吃的 - 三餐`），而 `categories.name` 存的是**叶子名**（`三餐`）。
两者之间靠分隔符连接，所以分隔符一旦不统一，账单就会找不到自己的分类——`categoryId` 为空、
分类预算漏算、恢复时按名字回填失效，都是同一个根因的不同表现。

规范格式只有一种：`CategoryNameNormalizer.UNIFIED_CHILD_SEPARATOR` = `" - "`。

`CategoryNameNormalizer.normalizeForStorage` 是唯一的归一化入口，它把
`> / \ | :: : · /::/ /:::/` 等历史写法统一收敛成 `" - "`。

**写入侧**：`BillMutationService`（`replaceBill` / `insertBillAndApplyImpact` / `saveRefundBill`）
是所有账单写入的漏斗，内部调用 `normalizeBillCategoryName`，因此经它落库的分类名必然是规范格式。
恢复/合并路径（`BackupRepository`）在写入前同样先归一化。

**选择器侧**：`OverlayDialogs.showGridCategoryPicker` 是分类选择器的唯一实现，它现在直接输出
规范格式。早前它输出 `"父 > 子"`，只有记账主表单（`AccountingFormController`）记得把它转成
`" - "`，其余调用方（规则弹窗、周期记账、余额调整）都把 `" > "` 原样存进了各自的实体。
改动后这些调用方无需再各自转换；规则保存时也会再走一次 `normalizeForStorage`，顺带治愈历史数据。

排查这类问题时，只要抓住三点：

1. **谁在写 `bills.categoryName`**——应全部经过 `BillMutationService`。
2. **谁在生成分类路径字符串**——应只输出规范格式（`OverlayDialogs.showGridCategoryPicker`）。
3. **谁在按名字查 `bills`**——凡是拿分类名当条件的查询，都必须同时覆盖「裸叶子名」和
   「以 `' - ' || name` 结尾的路径」两种写法，否则对存了展示路径的账单静默不命中。

第 3 点已经在 `BillDao` 里踩过一次：`getBillsByCategoryNameList`、`getBillIdsByCategoryNameList`、
`countBillsByCategoryName`、`migrateCategoryByName`、`syncCategoryNameByOldName` 都写了
`categoryName LIKE '% - ' || :name`，唯独 `sumBudgetExpenseByCategoryName` 漏了。
它只服务「有名字、没 `categoryId`」的历史预算，所以症状是**旧预算的分类已用金额一直显示为 0**，
既不报错也不影响其他页面——属于最难被发现的那类。判断某个查询是否需要路径分支，看它接收的
参数是**叶子名**（来自 `categories.name`）还是**完整路径**（来自 `bills.categoryName`）。

## 十、`categoryId` 缺失：分类预算为何是隐形的

分类预算**完全按 `categoryId` 聚合**：

- `sumBudgetExpenseByCategories` 带 `AND categoryId IN (:categoryIds)`
- `sumBudgetExpenseByAllCategories` 带 `AND categoryId IS NOT NULL`

所以一条账单只要丢了 `categoryId`，即使它的 `categoryName` 明明指向某个分类，**对那个分类的预算
也是不存在的**：已用金额恒为 0。它不报错，统计页照常显示（统计页按 `categoryName` 聚合），
分类详情页也照常列出——只有预算页悄悄少算。

这不是理论问题。真机取证时，一台设备上 4471 条账单里有 **3711 条 `categoryId IS NULL`**（83%）。

修复分两层：

1. **补齐历史数据**：`UncategorizedBillRelinker.relink(db)` 把「有名字、没 id」的账单挂回分类，
   由 `MigrationManager.backfillMissingCategoryIdsIfNeeded` 在启动时执行一次（带 pref 开关，失败不置位、
   下次启动重试）。匹配规则刻意保守：只认「叶子名 + 收支类型」在全表唯一的分类，同名歧义与
   分类已删除一律跳过，宁可漏填也不填错。
2. **恢复后立即补齐**：恢复流程在写完分类/账单后调用同一个 relinker
   （`BackupRepository.relinkBillsWithoutCategory`），避免恢复完还要等下次启动才生效。
3. **跳过明细必须可观测**：回填会返回 `RelinkReport`，记录回填了多少行、扫了多少个名字组，
   以及**跳过**了哪些分类名（同名歧义 / 分类已删除）。`MigrationManager` 用 `Logger`
   把这些写进持久化日志——因为被跳过的账单**仍然对分类预算隐形**，
   这是唯一需要人工确认的部分；没有这份明细就只能靠拉数据库才能定位。
4. **写入漏斗兜底，防止复发**：前两层只治「已经躺在库里的历史账单」。只要还有一条写入路径
   只写 `categoryName`、不写 `categoryId`，同样的 bug 就会持续产生。所以
   `BillMutationService` 的写入漏斗（`normalizeBillCategoryName` → `alignCategoryLink`）统一
   对齐分类关联：缺 id 且有名字、且不是转账时，用**与上面两层完全相同的规则**反查并补齐。
   补齐失败只记警告、绝不让账单写失败。
   这条兜底覆盖了分类选择器之外的全部入口：多笔 AI 识别直存（`OverlayManager.saveJsonToLocal`
   原先完全不写 `categoryId`）、聊天改写、周期账单、退款。
   判定边界由 `BillMutationServiceRelinkTest` 锁住。

一个容易踩的细节：**退款账单的 `type` 是 `TYPE_INCOME`，但它的 `categoryId` 继承自原支出分类**
（见 `BillMutationService.saveRefundBill`）。所以按类型查分类时，退款名必须用 `TYPE_EXPENSE`，
否则所有退款账单都会被跳过。这条规则由 `UncategorizedBillRelinkerTest` 锁住。

仍无法自动修复的部分：**同一收支类型下重名**的分类（例如支出下有两个「衣服」）无法判断归属，
只能人工确认。注意「支出一个「衣服」、收入一个「衣服」」不算歧义——类型本身就能区分，
`uniqueLeafTypeIndex` 会保留这类组合。

## 十一、展示路径快照：分类树变了，账单里的名字必须跟着变

`bills.categoryName` **不是权威数据**，它是展示路径的快照（`"吃的 - 三餐"`）。权威是
`categories` 表 + `bills.categoryId`。

问题在于：**统计页与洞察都按这份快照聚合，从不回查 `categoryId`**
（`StatsViewModel` 里的 `topLevelCached(bill.categoryName)`、`InsightEngine.groupBy { it.categoryName }`）。
所以快照一旦和分类树不一致，同一笔支出就会在统计页上被拆成两组——冒出一个旧名字的
「幽灵分类」。金额本身没错，只是被分到了错的那一组。

会让快照过期的四种分类树变化（此前只处理了第一种）：

| 操作 | 账单快照应该变成 | 此前 |
| --- | --- | --- |
| 一级分类改名（`餐饮 → 吃饭`） | `吃饭 - 早餐` | ❌ 仍是 `餐饮 - 早餐` |
| 二级分类提升为一级 | `早餐` | ❌ 仍是 `餐饮 - 早餐` |
| 一级分类降级为二级 | `餐饮 - 早餐` | ❌ 仍是 `早餐` |
| 拖动换父级 | `旅行 - 交通` | ❌ 仍是 `日常 - 交通` |

修复分两处：

1. **分类树变化时重算已存账单**。`CategoryRepository.resyncBillDisplayNames` 用
   `displayNamesById`（分类树里的真实路径）按 `categoryId` 刷快照，接在
   `updateCategory`（连同子分类一起）、`promoteToParent`、`demoteToChild`、
   `saveOrderedCategoryTree`（只刷真正换了父级的）之后。配套修正两个 SQL：
   - `syncCategoryNameByCategoryId` 改为**保留退款前缀**——退款账单的名字是
     `"退款：<路径>"`，整体覆盖会把前缀吃掉，而退款在统计里要靠前缀单独识别；
   - `syncCategoryNameByOldName`（服务那些没有 `categoryId` 的历史账单）补上
     `categoryName LIKE :oldLeaf || ' - %'` 分支——否则**祖先改名**时旧叶子出现在路径
     开头，匹配不到。同时把 `REPLACE(...)` 换成按位置的 `CASE` 精确替换：`REPLACE` 是
     **全局替换**，分类名互为子串时会误伤——父级「餐」改名为「新餐」后，
     `餐 - 三餐` 会变成 `新餐 - 三新餐`，把子分类的叶子也改掉了。
     三种形态（整串相等 / 路径开头 / 路径末尾）各处理一次；退款账单的 `退款：` 前缀
     先于普通分支判断，避免 `退款：吃的 - 三餐` 被末尾分支抢走。
2. **写入漏斗以 id 为准**。`BillMutationService.alignCategoryLink` 现在两个方向都对齐：
   有 `categoryId` 就按分类树重算展示名（保留退款前缀），没有才按名字反查。
   这一步兜住了「抄来一个过期名字」的写入路径，最典型的是
   `RecurringBillingService.recordDuePattern`——周期模板的 `categoryName` 是创建时的快照，
   分类改名后它会把旧名字连同新的 `categoryId` 一起写进新账单。

3. **绕过漏斗的写入路径必须显式对齐**。有几条路径自己管理资产余额影响，所以不能整个走漏斗，
   但分类关联一样要对齐——它们此前都是直接 `billDao.insertBill(...)`，两处都漏了：
   - `BackupActivity.importCsvBills`（CSV 导入）：`CsvManager` 只解析出 `categoryName`、
     从不给 `categoryId`，于是**导入进来的账单全部对分类预算隐形**；
   - `BillRestoreHelper`（回收站还原）：从 `deleted_bills` 原样搬回 `categoryId`，
     分类若已被删除就会还原出一个悬空 id。

   两条路径改为先过 `BillMutationService.alignCategoryLinkForDirectWrite`。顺带把
   「分类已删除」的语义补齐：悬空 `categoryId` 会被**清空**（对齐外键 `SET_NULL` 的语义），
   清空后还能被启动回填按名字重新认领；留着它则既进不了分类预算、又永远挂在旧名字下。

   判定规则被抽成纯函数 `alignAgainstTree(bill, paths, uniqueByLeafAndType)`，
   漏斗与两条直写路径共用同一份实现，由 `BillMutationServiceRelinkTest` 全量覆盖。

4. **周期模式的分类关联同样以 id 为准**。`recurring_patterns` 有两处按名字关联，都会因改名静默出错：

   - **去重签名**。`RecurringPatternDao.getBySignature` 此前只用 `categoryName` 匹配。分类改名后，
     旧 pattern 的签名（旧名）与新检测出的候选（新名——账单快照已被重算）对不上，于是**同一个周期
     被重复登记一条**；已 `CONFIRMED` 的旧记录不在 `scanRecentBills` 的清理范围内（它只删
     `SUGGESTED`），于是永久留下两条。`matchNewBill` / `hasMatchingBillOnExpectedDay` 本来就按
     `categoryId` 匹配，只有「去重」这一环漏了。现在 SQL 与内存里的 `patternSignature` 都改为
     **有 id 按 id、无 id 才退回按名字**。
   - **编辑保存**。`RecurringBillsActivity` 拿 `existing.categoryName`（过期旧名）去
     `findCategoryByDisplayName` 反查 id，改名后查不到 → 写回 `categoryId = null`。用户只是改个
     金额，分类关联就被清空了。现在改为**沿用已有的 `categoryId`**，只有用户重新选了分类才按名字
     反查（纯函数 `RecurringBillingService.resolvePatternCategoryId`）。顺带把写回的
     `categoryName` 也换成分类树的当前路径，编辑一次就顺手修正历史脏数据。

   展示名快照的同步也补上了：`resyncBillDisplayNames` 现在同时刷 `recurring_patterns.categoryName`
   （周期模式列表与到期提醒都会显示它），无 id 的历史记录走 `syncCategoryNameByOldName` 的就地替换。
   恢复路径 `BackupRepository.prepareRecurringPatternForRestore` 本来就以 `categoryIdMap` 优先、
   名字兜底，无需改动。

一个容易漏的点：`CategoryRepository` 的可选 DAO 参数（`billDao`、`recurringPatternDao`）**写操作必须传**，
否则分类树变化后的重算会静默空转（helper 里 `?: return`）。目前传全的是 `AddCategoryActivity`
（改名）、`SettingsActivity`（改名 / 提升 / 降级 / 拖动）、`OverlayDialogs.showCategorySortDialog`
（拖动）。其余构造点（`TapApplication` / `CategorySortActivity` / 各选择器）只读分类，不受影响——
`CategorySortActivity` 走的是 `saveOrderedCategories`，只改 `sortOrder`、不动 `parentId`，
且列表内只有同级项，不会跨父级拖动，因此安全。以后新增写入型调用时要留意。

判定边界由 `BillMutationServiceRelinkTest`、`CategoryRepositoryTest` 与
`RecurringPatternCategoryLinkTest` 锁住。

## 十二、重复分类：「其他」与「其它」

内置分类库 `category.json` 里这两个名字是**同一个图标的两个条目**（都是 `cateic_other.png`），
而初始种子 `default_category.json` 只给「其它」。手动加过「其他」的账本会同时拥有两个——
语义完全相同，却各自挂着一批账单，在统计页和分类预算里看起来像两个分类。

### 种子侧（防止再发生）

- `default_category.json` 的支出 / 收入两组都统一成「**其他**」；
- `category.json` 删掉排在很后面的重复条目「其它」，保留第 19 行的正式条目「其他」。

### 已有数据侧（`MigrationManager.mergeDuplicateCategoriesIfNeeded`）

**只有两个同时存在才动手**——只有一个的账本（绝大多数）在这里是空操作。

保留**别名组里靠前**的那个（`DuplicateCategoryMerger.ALIAS_GROUPS` 声明为 `["其他", "其它"]`），
方向固定为「其它」并入「其他」：这是《现代汉语词典》的推荐写法，也是 `category.json` 里的正式条目。
要反过来只需交换这一对的顺序。

每个合并动作按顺序做四件事（同一事务）：

1. 改挂已带 `categoryId` 的账单：`bills`、`deleted_bills`、`recurring_patterns` 各一条 `UPDATE`
   —— `deleted_bills.categoryId` **没有外键**，不跟着改就会留下悬空 id；
2. 把只有名字的历史账单**改名**（`syncCategoryNameByOldName`，只影响 `categoryId IS NULL` 的行，
   同时覆盖 `bills` 与 `deleted_bills`）。
   **这里绝不能改挂 `categoryId`**：`migrateCategoryByName` 不区分账单类型，会把收入账单挂到支出分类上
   —— 真机上「其它」的 13 条无编号账单里有 10 条是收入。改名之后由紧随其后的
   `backfillMissingCategoryIdsIfNeeded` 按「叶子名 + 类型」正确认领（退款账单按原支出分类处理），
   那本来就是它的职责；两者是**互补**而非重复。
3. 把保留分类下所有账单的名字快照刷成当前路径（复用 `syncCategoryNameByCategoryId`，
   退款前缀由 SQL 保留，回收站里的也一起刷）；
4. 删掉被并入的分类（只删它自己，不碰子分类）。

**顺序上必须排在 `backfillMissingCategoryIdsIfNeeded` 之前**：先合并，回填才不会把账单
挂到一个马上要被删掉的分类上。

### 三条保守规则（`DuplicateCategoryMerger.plan`）

- 只在**同一个 type** 内配对。「其他」支出与「其它」收入是两个不同的分类，合并会改变账单的类型语义。
- 只处理**根分类**。子分类的账单名带路径前缀（`杂项 - 其它`），按名字迁移的语义完全不同。
- 被并入的那个若**有子分类**，**跳过**并记日志。删它会连累子分类（`parentId` 被外键置空、
  账单名失去父级），那属于用户自己的结构，不能替他决定。

跳过项会写进 `Logger`，否则用户会以为漏了。

判定规则由 `DuplicateCategoryMergerTest` 锁住（9 项）。
