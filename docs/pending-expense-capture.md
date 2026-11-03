# 支付捕获与待记收件箱（v2）

> 状态：需求确认稿，尚未实现
> 形态：独立 Xposed 插件持有待记收件箱；敲敲记账提供账本/编辑器/AI 代理/导入接口
> 适用：个人使用；Android + LSPosed；微信 / 支付宝
> v2 修订：采纳技术评审的硬伤修正（鉴权、回执对账、幂等事务、ID 优先、去重矛盾、场景矩阵），并按建议瘦身（App 做 AI 代理、不常驻 FGS、敲击计数缓存）

## 1. 背景

菜市场、夜市这类连续消费场景里，用户在一个档口付完款就走向下一个档口，中间没有空档掏手机记账。等忙完再回忆时，只能打开微信/支付宝账单一条条看金额，既慢又容易漏。

AutoAccounting 一类插件的做法是 hook 支付数据后直接生成账单。本需求把落点改成**待记记录**：

- 支付发生的那一刻，只做“留痕”：金额、时间、来源、支付方式、商户，绝不丢。
- 用户有空时再逐条处理：补备注、选分类、入账。
- 永远保留人工确认，不静默写账单。

### 1.1 已确认的产品决定

| 问题 | 决定 |
| --- | --- |
| 第一版来源 | 微信 + 支付宝 |
| 形态 | 独立 Xposed 模块 APK；插件持有待记收件箱 |
| 待记数据落点 | 插件 Room；App 不新增待记表和收件箱 UI |
| 设备条件 | root + LSPosed（作用域：微信 / 支付宝；不再勾选敲敲记账） |
| 捕获提示 | P0：通知 / 静默；P1：气泡 / 完整卡片（可设置） |
| 通知栏 | 普通常驻通知：“N 笔待记”；锁屏不显示金额；**不常驻前台服务** |
| 敲击入口 | P1：App 用缓存的待记数量判断；P0 先走通知点开 |
| 点开一条 | 自己记 → 调 App 现有悬浮窗；AI 记 → App 的 AI 代理返回草稿 |
| 资产 | 插件保存 hook 到的支付方式，并映射到 App 的资产（参考 AutoAccounting） |
| 截图 | 不做 |
| AI | **App 暴露 AI 代理（`startClassify` / `getClassifyResult`）**，用 App 自己的 key/上下文分类；插件不读 key、不抽 `:ai-core` |
| 分类 | AI 返回 `category_id`，可改 |
| 保留与提醒 | 一直保留；每晚汇总一次（WorkManager/非精确闹钟） |
| 去重 | 订单号优先；无订单号不自动合并；只对带 `captureId` 的账单自动关联 |
| 资金变动类型 | 只记支出（个人收款码的“付款给个人”算支出） |
| 分发 | 插件独立分发，只自己用，接受固定微信/支付宝版本 |

## 2. 目标与非目标

### 2.1 目标

1. **零操作也不丢**：支付成功后，即使什么都不点，金额、时间、来源、支付方式也已被插件记录。
2. **当场只补一句**：想记就补一句“青菜”，不想记就自动进待记列表；不强迫分类、不强迫入账。
3. **多笔可累积**：待记记录可以同时存在多条，不互相覆盖。
4. **忙完批量处理**：逐条补备注后，可以交给 App 的 AI 一次分类，也可以自己一条条记。
5. **敲击时顺手带出**：App 用缓存的待记数量判断，有待记就让插件展示列表；没有就保持原行为。
6. **插件独立升级**：微信/支付宝适配、待记 UI、通知都在插件内；App 不需要为每次插件升级跟着发版。
7. **不自动入账**：AI 只生成草稿，最终由用户确认，通过 App 的 `importBills` 写账单。
8. **写账单只走 App 的漏斗**：复用 `AIAccountingSupport` 规范化、`BatchBillInserter`、`BillMutationService`，不直接改 App 数据库。

### 2.2 非目标

- 不做截图、OCR、小票识别。
- 不做自动入账，也不做“识别完直接保存”。
- 不做退款、转账、收入、红包、群收款。
- 不做无 root 方案（通知监听 / 无障碍读屏不作为第一版触发源）。
- 不在插件里重写账单编辑器：自己记仍复用 App 的悬浮窗表单。
- 不做应用商店发布，不为通用兼容性做取舍。
- 不做 App 端待记收件箱；App 只提供账本、编辑器、AI 代理、导入接口和敲击信号。

## 3. 术语

**支付捕获（PaymentCapture）**：
一次支付事件在插件本地留下的待处理记录，包含金额、时间、来源、支付方式、商户等。它不是账单，只有导入 App 后才变成 `Bill`。

**待记收件箱**：
插件内所有未处理支付捕获的列表。

**资产映射**：
把 hook 到的原始支付方式（如“零钱”“招商银行储蓄卡(1234)”“花呗”）映射到 App 内 `Asset` 的规则。映射数据存在插件，资产列表从 App 查询。

**桥接接口（Bridge）**：
App 暴露给插件的一组签名保护接口：查上下文、查最近账单、打开记账表单、启动 AI 分类、导入账单、查导入回执。

**AI 代理（`startClassify` / `getClassifyResult`）**：
App 用自己的 API key、提示词、账本/资产/分类上下文做分类，只返回草稿，不写库。插件不接触 key。

**导入回执（external_imports）**：
App 侧记录 `capture_id → bill_id` 的小表，和账单写入同事务，用于幂等和对账。

## 4. 用户场景

### 4.1 菜市场连续支付（核心）

1. 17:00 在 A 档口扫码付 3 元，微信显示“支付成功”。
2. 用户没掏手机，继续走到 B 档口；17:04 又付 12 元。
3. 插件在后台各自留下一条待记记录，通知栏变成“2 笔待记”。
4. 用户全程零操作，但两笔的金额、时间、支付方式都没丢。

### 4.2 晚上补记

1. 21:00 插件发“今天还有 2 笔待记”的汇总提醒。
2. 点开插件列表，看到“17:00 微信 ¥3.00 菜摊”“17:04 微信 ¥12.00 肉摊”。
3. 点第一条，补一句“青菜”；点“自己记”，跳到敲敲记账的悬浮窗，分类选“餐饮”，保存。
4. App 保存后写 `external_imports` 并广播 `BILL_CREATED`；插件把这条标成已记；通知数量减一。
5. 点第二条，补“猪肉”，点“AI 记”，App 的 AI 代理返回分类草稿，确认后 `importBills`。

### 4.3 敲击顺手记（P1）

1. 用户刚付完款，想起要记账，敲两下手机。
2. App 读本地缓存的待记数量（插件在数量变化时推送）。
3. 有 → App 让插件展示待记列表，置顶“刚刚 17:00 微信 ¥3.00”。
4. 用户点“就记这笔”，插件调 App 的悬浮窗表单，金额、时间、资产已填好，光标在备注。
5. 补一句“青菜”、选分类、保存，插件收到回执，标记完成。
6. 没有 → App 走原来的空白记账，行为不变。

### 4.4 批量 AI

1. 插件待记列表里有 5 笔。
2. 点“全部 AI 记”，逐条弹出备注输入：用户只写“青菜”“猪肉”“停车费”。
3. 插件调 App 的 `startClassify`，App 用自己的 AI 分类；插件展示草稿。
4. 用户扫一眼、改分类，点“全部入账”。
5. 插件调用 App 的 `importBills`，App 写账单并返回 `bill_id`；插件标记已记。

### 4.5 支付场景矩阵（探针必须覆盖）

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

## 5. 总体方案

### 5.1 组成

```text
微信 / 支付宝进程
  └─ 支付捕获插件（LSPosed 作用域）
       ├─ hook 支付成功页 / 消息 / WebView
       ├─ 捕获规则 → 标准化 JSON
       └─ 插件内投递（token 鉴权）
            └─ 插件主进程
                 ├─ Room：payment_captures / 资产映射
                 ├─ 普通常驻通知："N 笔待记"
                 ├─ 待记列表 / 单条卡片 / 备注
                 ├─ 本地草稿 / AI 草稿展示
                 └─ Bridge 客户端
                      ├─ 查 App 上下文（账本/资产/分类）
                      ├─ 查最近账单（去重）
                      ├─ openQuickRecord（自己记）
                      ├─ startClassify / getClassifyResult（App AI 代理）
                      ├─ importBills（写账单）
                      └─ getImportStatus（对账）
                           └─ 敲敲记账
                                ├─ 现有悬浮窗表单
                                ├─ AIService（现有 key/prompt/上下文）
                                ├─ BatchBillInserter
                                ├─ BillMutationService（资产影响/共享同步/周期匹配）
                                └─ bills + external_imports
```

### 5.2 为什么是 Xposed

- 微信的支付结果不是系统通知，而是微信内的服务消息；通知监听抓不到或不稳定。
- 支付宝有支付通知，但会延迟、会被用户关闭，不能作为唯一来源。
- 只有 hook 支付成功页/消息/WebView，才能稳定拿到金额、支付方式、商户、订单号和准确时间。
- 第一版只服务个人设备，可以固定微信/支付宝版本，接受升级后适配。

### 5.3 形态：插件持有收件箱（B3 瘦身版）

- 插件是独立 APK（建议包名 `com.taostudio.tapaccounting.capture`），LSPosed 只勾微信/支付宝；**不勾敲敲记账**。
- 插件持有待记收件箱：数据库、通知、列表、备注、资产映射、备份都在插件内。
- App 不做待记表、不做待记列表；只提供账本/资产/分类、现有编辑器、AI 代理、导入接口和敲击信号。
- 为什么仍选 B3：
  - 插件可以独立升级，微信/支付宝适配、待记 UI 不需要 App 发版；
  - 以后更容易适配别的记账 App（把 Bridge 抽象成通用 ingestion API）；
  - 待记数据和 App 账本解耦，插件卸载/重装不影响已有账单。
- 代价（必须承认）：
  - 插件基本是一个小 App：Room、通知、列表、映射、备份都要自己做；
  - 比 A 方案（收件箱放 App）多出插件 UI/DB/备份，但比原 B3 少了 `:ai-core`、AI key、FGS、敲击同步查询；
  - 如果不需要插件独立，A 方案更省；本方案按 B3 继续，是因为“插件可独立升级/复用”是明确目标。

### 5.4 职责边界

| 能力 | 插件 | 敲敲记账 |
| --- | --- | --- |
| hook 微信/支付宝 | ✓ | |
| 解析、规则、版本适配 | ✓ | |
| 待记数据库 / 收件箱 | ✓ | |
| 通知 / 待记列表 / 备注 | ✓ | |
| 资产映射 / 去重 | ✓ | |
| AI 草稿展示 / 批量编辑 | ✓ | |
| 插件备份 / 导出 | ✓ | |
| 账本 / 资产 / 分类 | 查询 | ✓ |
| 现有记账表单（自己记） | 调用 | ✓ |
| AI 分类（key/prompt/上下文） | 调用代理 | ✓ |
| 最终账单写入 | 调用 `importBills` | ✓ |
| 资产影响 / 共享同步 / 周期匹配 | | ✓ |
| 敲击入口 / 动作分发 | 接收信号 | ✓ |

### 5.5 通信契约

#### 5.5.1 插件 → App：`CaptureBridgeProvider`（`signature` 权限）

- `ping()` → `{contractVersion, appVersion, importEnabled, aiEnabled}`
- `getAccountingContext()` → `{books, assets, categories, defaultBookName, assetFeatureEnabled}`
- `getRecentBills(paidAt, windowMinutes, limit)` → 最近支出账单的最小字段集（金额分/时间毫秒/资产 ID/名称）
- `openQuickRecord(prefill, captureId)` → `{accepted}`；保存后 App 写 `external_imports` 并广播 `BILL_CREATED`
- `startClassify(capturesJson)` → `{jobId}`
- `getClassifyResult(jobId)` → `{status: RUNNING/READY/FAILED, drafts, error}`
- `importBills(billsJson, requestId)` → `{ok, requestId, results:[{captureId, billId, status, error}]}`
- `getImportStatus(captureIds)` → `[{captureId, status, billId, importedAt}]`

关键约束：

- 所有金额用 `amountCents`（Long），所有时间用 `paidAt`（Long 毫秒）；App 在边界转成现有 `Double`/字符串。
- `captureId` 是幂等键；`requestId` 只用于批次追踪/日志。
- `importBills` 必须校验 schema、限制条数（≤200）、类型/金额白名单、资产/分类存在性。
- `importBills` 的每条账单和 `external_imports` 插入在**同一个事务**里；重复 `captureId` 直接返回已有 `billId`。
- `openQuickRecord` 的保存路径也必须写 `external_imports`（带 `captureId`）；不能只靠广播。

#### 5.5.2 App → 插件

- `getPendingCount()` → `{count, updatedAt}`（`signature` 权限；App 启动/恢复时刷新缓存）
- `PENDING_COUNT_CHANGED` 广播 → `{count, updatedAt}`；插件在新增/处理/忽略后推送
- `ACTION_SHOW_PENDING` → 插件展示待记列表；App 不再弹自己的空白悬浮窗
- `BILL_CREATED` 广播 → `{billId, amountCents, paidAt, accountId, accountName, captureId?}`；广播只加速，插件仍用 `getImportStatus` 对账
- 插件未安装/超时 → App 回退原有空白记账

#### 5.5.3 hook → 插件：`CaptureIngestProvider`（token 鉴权）

- hook 进程运行在微信/支付宝 UID，**不能用插件的 `signature` 权限**。
- 插件导出 `CaptureIngestProvider`，用每次构建生成的 token（`BuildConfig.CAPTURE_INGEST_TOKEN` 或 assets）校验；token 不是强安全边界，个人使用可接受。
- 优先 `ContentProvider.call`（IO 线程、2 秒超时、返回 ACK）；失败降级显式广播；插件按 `captureId`/`sourceTxnId` 去重。
- 不要阻塞微信的 DB 插入线程；所有调用放 IO。
- 两边 manifest 加 `<queries>`，覆盖包可见性限制；P0 探针必须验证。
- 如果 token/Provider 方案在真机不稳定，备选 localhost socket（P3）。

#### 5.5.4 AI 代理流程

1. 插件把待记条目转成精简 JSON（`captureId/amountCents/paidAt/source/merchant/paymentMethodRaw/note`）。
2. 调 `startClassify(capturesJson)` → `jobId`。
3. App 用现有 `AIService.analyzeAccounting` + `buildAccountingPromptContext` + 自己的 key 分类；只返回草稿，不写库。
4. 插件轮询 `getClassifyResult(jobId)`（500ms~1s，超时 60s），或等 `AI_DRAFTS_READY` 广播。
5. 草稿展示：金额/时间/资产/分类/备注可改。
6. 确认后 `importBills`；失败可重试。
7. AI 不可用 → 插件本地草稿（金额/时间/资产/备注，分类为空），用户从 App 上下文选分类，或走“自己记”。

### 5.6 AI 与配置

- **不再抽 `:ai-core`，不再 hook App 读 key。** App 的 `AIService` 原样使用；插件只调代理。
- App 的 key 仍在 `PrefsAiSupport` + `KeystoreSecretBox`（alias `flip_ai_secrets`），不跨 APK。
- 插件不需要 AI key、不需要 LSPosed 勾选敲敲记账、不需要 `CaptureBridgeFacade`。
- 代价：AI 分类依赖 App；App 被卸载/未配置 AI 时，插件只能本地草稿或手动分类。
- 如果以后要让插件独立适配别的记账 App，再抽 `:ai-core` 或让插件自填 key。

### 5.7 数据归属与备份

- 待记记录、资产映射、草稿、设置：插件数据库/偏好。
- 账单、账本、资产、分类、AI 配置：App。
- App 的备份不包含插件待记；插件自己做导出/备份（JSON/ZIP），至少支持手动导出。
- 卸载插件会丢待记（除非先导出）；卸载 App 不会丢插件待记，但导入/AI 会失败，重装后可恢复。
- 插件待记不进入 App 的共享账本同步；导入成账单后按 App 现有规则处理。

### 5.8 安装与升级

- 两个 APK 使用同一签名；先装敲敲记账，再装插件，并在 LSPosed 中勾选微信、支付宝。
- App 升级不要求插件升级，但 `contractVersion` 必须兼容；插件升级不要求 App 升级。
- 契约不兼容：插件拒绝导入并提示，数据留在插件；App 的敲击回退到原行为。
- 插件需要通知权限；**不需要前台服务/悬浮窗权限**（P0 通知模式）。P1 气泡才需要悬浮窗权限。
- 电池优化白名单可选；插件不常驻 FGS，捕获时按需拉起进程。

### 5.9 App 侧改动清单（v2）

| # | 改动 | 说明 |
| --- | --- | --- |
| 1 | 新增 `CaptureBridgeProvider` | 签名权限；上下文/最近账单/`openQuickRecord`/`startClassify`/`getClassifyResult`/`importBills`/`getImportStatus` |
| 2 | `external_imports` 表 | `capture_id PK`、`bill_id`、`request_id`、`created_at`；和账单写入同事务 |
| 3 | `openQuickRecord` 预填 | 支持 `prefill` + `captureId`；保存时写 `external_imports`；广播 `BILL_CREATED` |
| 4 | `importBills` | 校验/规范化/逐条错误/幂等；复用 `AIAccountingSupport` + `BatchBillInserter` |
| 5 | ID 优先 | `BatchBillInserter` 支持 `asset_id`/`category_id`；名称只做快照与校验；不一致报错 |
| 6 | 敲击缓存 | 接收 `PENDING_COUNT_CHANGED`，存本地；敲击时零延迟判断；启动/恢复时刷新 |
| 7 | AI 代理 | 新增 `startClassify`/`getClassifyResult`，内部调现有 `AIService`；不传 key、不写库 |
| 8 | 设置开关 | “允许插件接管敲击”、“允许插件写入账单”、插件状态 |
| 9 | 包可见性 | manifest 加 `<queries>` 插件包名 |
| 10 | 测试 | Provider 权限、导入幂等、ID 校验、敲击回退、AI 代理超时 |

不改的部分：`bills` 表结构（只加 `external_imports`）、现有记账表单、现有 AI 管线、共享账本同步。

### 5.10 插件侧改动清单（v2）

| # | 改动 | 说明 |
| --- | --- | --- |
| 1 | Xposed 入口 | 微信/支付宝支付成功页/消息/WebView hook、版本适配 |
| 2 | 捕获规则 | 字段映射、正则、版本、失败日志、token 投递 |
| 3 | Room | `payment_captures`、`capture_asset_mappings` |
| 4 | 普通通知 | 常驻“N 笔待记”、每晚提醒；不需要 FGS |
| 5 | 待记列表/单条卡片 | 备注、资产、忽略、删除、批量选择 |
| 6 | AI 代理客户端 | `startClassify`/`getClassifyResult`；草稿展示/编辑 |
| 7 | Bridge 客户端 | 上下文、最近账单、打开表单、导入、回执 |
| 8 | 资产映射 | 从 App 查资产，插件存映射，支持测试 |
| 9 | 去重 | 捕获内 + App 最近账单 + 导入幂等 |
| 10 | 备份/导出 | 待记 + 映射 + 设置；卸载前提醒 |
| 11 | 计数推送 | 数量变化广播给 App；App 缓存 |
| 12 | 自检/日志 | hook 状态、最近捕获、契约版本、目标版本 |

## 6. 数据模型（插件）

### 6.1 payment_captures

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | `captureId`（UUID） |
| `source` | TEXT | `WECHAT` / `ALIPAY` |
| `sourceTxnId` | TEXT? | 订单号/交易号 |
| `amountCents` | INTEGER? | 金额（分）；缺失为 null |
| `currency` | TEXT | `CNY` |
| `merchant` | TEXT? | 商户；拿不到为空 |
| `paymentMethodRaw` | TEXT? | 原始支付方式 |
| `assetId` / `assetName` | INTEGER?/TEXT? | 映射后的 App 资产 |
| `paidAt` / `capturedAt` | INTEGER | 毫秒 |
| `bookName` | TEXT? | 默认账本 |
| `note` | TEXT? | 备注草稿 |
| `status` | TEXT | 见 6.3 |
| `aiDraftJson` | TEXT? | AI 草稿 |
| `importRequestId` | TEXT? | 批次追踪 |
| `importedBillIds` | TEXT? | 成功后的 billId 列表 |
| `lastError` | TEXT? | 失败原因 |
| `needsAmount` | INTEGER | 金额缺失标记 |
| `rawJson` / `ruleVersion` / `sourceAppVersion` | TEXT? | 调试/规则版本 |
| `createdAt` / `updatedAt` | INTEGER | |

索引：`status`、`paidAt`、`source + sourceTxnId`。

### 6.2 capture_asset_mappings

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER PK | |
| `source` | TEXT | `WECHAT` / `ALIPAY` |
| `matchType` | TEXT | `EXACT` / `CONTAINS` / `REGEX` |
| `pattern` | TEXT | 原始支付方式 |
| `assetId` / `assetName` | INTEGER / TEXT | App 资产 |
| `priority` | INTEGER | 越小越优先 |
| `enabled` | INTEGER | 0/1 |
| `createdAt` | INTEGER | |

### 6.3 状态机

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

“待记数量” = `PENDING + AI_DRAFT + IMPORTING + FAILED + POSSIBLE_DUPLICATE`。`DUPLICATE`/`IGNORED` 不计入。终态保留 90 天。

## 7. 捕获

### 7.1 触发条件

- 微信/支付宝出现**支付成功**结果页/消息/WebView 数据。
- 同一笔只触发一次：优先 `sourceTxnId`；没有订单号时，**不按金额+时间自动合并**，只标疑似重复。
- 忽略：支付失败、取消、退款、转账（非消费）、红包、群收款、收入、非 CNY（写日志）。
- 个人收款码：`付款给个人`/`转账给个人` 如果是用户付款买东西，算支出；需要方向字段/上下文判断，不能一刀切。

### 7.2 字段优先级

| 字段 | 来源 | 缺失时 |
| --- | --- | --- |
| 金额 | 成功页/消息/WebView | 生成“待补金额”条目 |
| 支付时间 | 成功页时间，否则捕获时间 | 用捕获时间 |
| 支付方式 | 成功页扣款渠道 | 留空，资产待选 |
| 商户 | 成功页商户名 | 留空，不编造 |
| 订单号 | 成功页/数据对象 | 用兜底去重键 |

### 7.3 解析顺序

1. Hook 支付结果页的数据对象/ViewModel。
2. Hook 结果页 View 树的文本节点，按锚点提取。
3. Hook 支付消息/DB/WebView。
4. 都失败：保留原始数据，生成“待补”条目或只记日志。

### 7.4 规则

- 规则 = 字段映射 + 正则 + 资产映射，带版本号。
- 先用探针跑通真实支付，确认字段，再固化规则。
- 规则失败要可观测：记录 `ruleVersion`、失败原因、原始数据，插件设置页可导出。
- 如果复用 AutoAccounting 的订阅规则：规则是 JS，输入 `data`，输出 `money>0` 的对象；分类是 `getCategory(...)`；资产映射是 `AssetsMapModel`。需要本地 JS 引擎或转成 Kotlin/正则；GPL-3.0 约束。

### 7.5 失败兜底

- **确认是成功支付，但金额解析失败**：仍生成 `PENDING`，`needsAmount=1`，列表显示“待补金额”。
- **连是否成功都无法确认**：不生成待记，只写调试日志。
- **插件进程未运行**：Provider/广播按需拉起，先落库再通知。
- **hook 未生效**：插件设置页显示“未捕获到支付”，给出 LSPosed 作用域、目标版本、最近捕获时间。

## 8. 交互

### 8.1 支付瞬间

P0 用通知，不弹悬浮窗：

| 模式 | P0/P1 | 行为 |
| --- | --- | --- |
| 通知（默认） | P0 | 更新常驻通知“已记 1 笔 · 17:00 微信 ¥3.00”；不打断支付 |
| 静默 | P0 | 只更新计数，不提示 |
| 气泡 | P1 | 底部小卡片，3 秒收起；需要悬浮窗权限 |
| 完整卡片 | P1 | 带备注/保存的卡片，需手动收起 |

- P0 不申请悬浮窗权限；P1 气泡再申请。
- 连续多笔合并为“已记 N 笔”。
- 锁屏/免打扰/通话中：不弹提示，只更新通知。

### 8.2 通知栏

- 普通常驻通知（`setOngoing(true)`），标题“N 笔待记”，内容默认“点开查看”。
- 设置可关“锁屏隐藏金额”；关闭后显示“最近 17:00 微信 ¥3.00”。
- 点击 → 插件待记列表。
- **不需要前台服务**；通知由 Provider/广播/插件进程更新，进程被杀后通知仍在，下次捕获再拉起更新。
- 渠道：`capture_pending`，`IMPORTANCE_LOW`，不响铃、不震动、锁屏 `PRIVATE`。
- P1 通知动作：`RemoteInput` 直接写备注、`忽略` 按钮。

### 8.3 待记列表

- 插件内页面，按时间倒序、按天分组。
- 每条显示：来源、时间、金额、商户、备注、资产；异常角标：
  - 资产未匹配：“资产待选”
  - 金额缺失：“待补金额”
  - 疑似重复：“可能已记”
- 顶部“共 N 笔 · 合计 ¥X”。
- 支持：单条点开、批量选择、忽略、删除（确认）、全部 AI 记。

### 8.4 单条处理

```text
微信 · 17:00
¥3.00
商户：菜摊
资产：微信零钱  [改]
备注：________________
[自己记]  [AI 记]  [忽略]
```

- 备注输入即时保存到 `payment_captures.note`。
- **自己记**：`openQuickRecord(prefill, captureId)`，App 悬浮窗预填；保存后写 `external_imports` + `BILL_CREATED`；插件标 `RECORDED`。
- **AI 记**：`startClassify` → 轮询 `getClassifyResult` → 草稿可改 → `importBills`。
- **忽略**：`IGNORED`，可在“已忽略”恢复。
- 资产未匹配：选 App 资产，默认记住映射。

### 8.5 敲击入口（P1）

1. App 读本地缓存的待记数量（插件推送 `PENDING_COUNT_CHANGED`）。
2. 数量 > 0 → App 发 `ACTION_SHOW_PENDING`，插件展示列表；否则原 `showOverlay()`。
3. 缓存过期/不确定 → App 调 `getPendingCount()` 刷新（超时 300ms），不阻塞敲击。
4. 插件列表“先不记” → `openQuickRecord(null, null)` 回 App 空白记账。
5. App 设置“允许插件接管敲击”默认开；P0 不启用，先走通知点开。

### 8.6 批量 AI

1. 列表顶部“全部 AI 记”。
2. 逐条备注输入，显示进度“第 2/5 笔”。
3. 汇总成数组，`startClassify`；App 分类；插件轮询/等广播。
4. 草稿列表可改分类/资产/备注。
5. “全部入账” → `importBills`；失败逐条重试。
6. AI 不可用 → 本地草稿 + 手动分类；仍可 `importBills`。
7. 不自动入账；必须确认。

### 8.7 每晚汇总

- 默认 21:00，可改/关；**用 WorkManager 或非精确闹钟**，不要 `SCHEDULE_EXACT_ALARM`。
- 仅当待记数量 > 0 时发一次性通知：“今天还有 N 笔待记”，点击进列表。
- 免打扰时静默。

### 8.8 免打扰

- 跟随系统免打扰；免打扰/锁屏/通话中不弹提示、不响提醒。
- 用户正在微信/支付宝内不特殊处理；支付成功页已离开才提示。

## 9. 资产映射

资产在 App 内，插件通过 `getAccountingContext()` 拿资产列表；映射表存在插件。

### 9.1 匹配顺序

1. 用户手动映射（`EXACT`/`CONTAINS`/`REGEX`，按 `priority`）。
2. 内置启发式：
   - 微信 + “零钱”/“零钱通” → 名称含“微信”的资产
   - 支付宝 + “余额”/“余额宝” → 名称含“支付宝”的资产
   - “花呗” → 名称含“花呗”的信用卡资产
   - 银行卡尾号 → 资产名称/备注含后四位
3. 都没命中 → `assetId = null`，列表“资产待选”。

### 9.2 首次未匹配

- 资产选择器提示“hook 到：招商银行储蓄卡(1234)”。
- 选中后默认写入映射，可取消。
- 同一支付方式第二次自动带出。

### 9.3 管理

- 按来源分组：原始支付方式 → 资产。
- 新增/编辑/删除/停用/优先级。
- “测试”输入框：输入“零钱”，显示命中的资产。
- App 资产改名/删除后，下次刷新上下文标记失效；导入逐条报错。

### 9.4 资产功能关闭时

- 不写 `assetId`，只保留 `paymentMethodRaw`。
- 导入时不传资产字段。

## 10. 去重

### 10.1 捕获内

- `source + sourceTxnId` 相同 → 合并。
- 无订单号：**不自动合并**。同一来源、金额相同、时间 ±30s 内，标 `POSSIBLE_DUPLICATE`，让用户确认。
- 支付成功页重复打开 → 命中订单号/疑似规则，不重复计数。

### 10.2 多来源归并

- 成功页 + DB + 通知可能报同一笔；用 10–30 秒归并窗口 + 金额 + 来源聚类，生成一个 `captureGroupId`。
- 主捕获保留，其他来源挂同一组；不重复计数。
- 归并不确定时保留多条，标“可能重复”。

### 10.3 与 App 账单

- **只有带 `captureId` 的账单才自动 `RECORDED`**（自己记/导入）。
- 手动账单（无 `captureId`）：只标 `POSSIBLE_DUPLICATE`，不自动 `DUPLICATE`；因为 `Bill.time` 是用户填的记账时间，`Bill` 没有 `createdAt`。
- `getRecentBills` 用于提示，不用于自动删数据。
- `POSSIBLE_DUPLICATE` 计入待记数量；`DUPLICATE` 不计入。

### 10.4 导入幂等

- `capture_id` 唯一；`external_imports` 和账单同事务。
- 同一 `captureId` 重复导入返回已有 `billId`。
- 部分成功：逐条 `status`；插件只重试失败项。
- `requestId` 只做批次追踪，不做幂等键。

## 11. 设置

### 11.1 插件设置

| 设置 | 默认 | 说明 |
| --- | --- | --- |
| 微信来源 | 开 | hook 微信支付 |
| 支付宝来源 | 开 | hook 支付宝支付 |
| 捕获提示 | 通知 | 通知 / 静默（P1：气泡 / 完整卡片） |
| 常驻通知 | 开 | “N 笔待记” |
| 锁屏隐藏金额 | 开 | |
| 免打扰时静默 | 开 | |
| 每晚汇总 | 开，21:00 | WorkManager/非精确 |
| 去重时间窗 | 30 秒 | 5/10/30/60 |
| 支付资产映射 | — | 管理页 |
| AI 代理 | 自动 | 显示 App 连接状态；不需要 key |
| 规则版本 | 自动 | 目标 App 版本 |
| 原始数据保留 | 7 天 | `rawJson` 自动清空 |
| 备份/导出 | — | 待记 + 映射 + 设置 |
| hook 自检 | — | 状态、最近捕获、契约版本 |
| 测试捕获 | — | 样例数据验证投递链路 |

### 11.2 App 设置

| 设置 | 默认 | 说明 |
| --- | --- | --- |
| 允许插件接管敲击 | 开（P1 生效） | 敲击时用缓存数量判断 |
| 允许插件写入账单 | 开 | 关闭后 `importBills` 拒绝 |
| 插件状态 | — | 插件版本、契约版本、最近捕获、权限检查 |

## 12. 数据、隐私、备份

- 待记数据只存插件本地 Room，不上传。
- AI 分类在 App 内完成：插件只发金额/时间/商户/备注等必要字段给 App，**不传 key**；App 不把 key 暴露给插件。
- App 的 `importBills` 只做本地校验/写库，不联网。
- 插件与 App 的 Provider 使用 `signature` 权限；hook→插件用 token（个人使用，不作为强安全边界）。
- 常驻通知锁屏默认隐藏金额。
- 插件待记不进入 App 备份/共享账本；插件自己导出备份（含敏感数据，需提醒）。
- 卸载插件前提示导出；`rawJson` 7 天清空；终态 90 天。
- “清除全部待记”二次确认。

## 13. 异常与边界

| 场景 | 行为 |
| --- | --- |
| 微信/支付宝升级，hook 失效 | 插件设置页“长时间未捕获”；不崩溃 |
| 成功页字段缺失 | 金额缺失→“待补金额”；商户/资产留空 |
| 无法确认支付成功 | 不生成待记，只写日志 |
| 插件进程被杀 | Provider/广播拉起；先写库再通知；通知仍在 |
| App 未安装/被卸载 | 待记保留；导入/AI 失败并提示；重装恢复 |
| App 拒绝写入 | 导入返回拒绝；待记保留 |
| 契约版本不匹配 | 插件提示；待记保留 |
| LSPosed 未启用/作用域没勾 | 不捕获；设置页检查项 |
| 通知权限被拒 | 只记不提示；引导开启 |
| 连续多笔 | 每条独立；通知累计 |
| 支付成功页重复打开 | 订单号/疑似规则去重 |
| 退款/转账/红包/收入 | 第一版忽略，写日志 |
| 个人收款码 | 付款给个人按支出；方向判断 |
| 非 CNY | 记录但不导入 |
| 微信/支付宝分身 | 不支持，日志标记 |
| 手动已记账 | 标 `POSSIBLE_DUPLICATE`，不自动删 |
| 同价多笔 | 不自动合并；标疑似 |
| 分类/资产改名/删除 | 导入逐条报错；重新选择 |
| App 敲击缓存过期 | 刷新 `getPendingCount`；超时回退 |
| AI 未配置/超时 | 本地草稿 + 手动分类；不阻塞“自己记” |
| 导入部分失败 | 逐条结果；失败可重试；成功不重复 |
| 时区/系统时间变化 | 以支付页时间为准，否则捕获时间 |

## 14. 接入点与模块

### 14.1 模块拆分

```text
:capture-plugin     独立 APK：hook、规则、Room、通知、列表、AI 代理客户端、Bridge 客户端
:capture-contract   共享库：JSON schema、字段常量、权限名、contractVersion
:app                现有 App：Bridge Provider、AI 代理、导入、预填、敲击缓存、设置
```

不再需要 `:ai-core`（v2 由 App 代理）；不再需要 hook 敲敲记账。

### 14.2 App 侧接入点

| 能力 | 现有实现 | 用法 |
| --- | --- | --- |
| 悬浮窗记账 | `OverlayManager.showOverlay(prefill)` | `openQuickRecord` 预填 |
| 多笔队列 | `OverlayManager.pendingBills` / `processNextPendingBill()` | 连续记 |
| AI 分类 | `AIService.analyzeAccounting` + `buildAccountingPromptContext` | `startClassify` |
| AI 规范化 | `AIAccountingSupport.normalizeAccountingResult` | `importBills` 前处理 |
| 批量写入 | `BatchBillInserter.insertBills()` | `importBills` 落库 |
| 账单写入 | `BillMutationService.insertBillAndApplyImpact` | 资产影响/共享同步/周期匹配 |
| 账单保存 | `BillMutationService` / 保存路径 | 写 `external_imports` + `BILL_CREATED` |
| 敲击入口 | `OverlayService` 的 `ACTION_SHOW_OVERLAY` | 读缓存数量，决定是否发 `ACTION_SHOW_PENDING` |
| 账本 | `BookAccountManager` | `getAccountingContext` |
| 资产/分类 | `Asset` / `Category` | `getAccountingContext` |
| 设置 | 现有设置页 | 插件状态/两个开关 |
| 包可见性 | manifest `<queries>` | 插件包名 |

### 14.3 插件侧接入点

- `hook/`：Xposed 入口、微信/支付宝适配、探针导出。
- `rules/`：字段映射、正则、版本。
- `data/`：`PaymentCapture`、`CaptureAssetMapping`、DAO、Repository。
- `ingest/`：token Provider、hook 投递、去重。
- `bridge/`：App Provider 客户端、权限检查、超时/重试。
- `ai/`：`startClassify`/`getClassifyResult` 客户端、草稿模型。
- `ui/`：列表、单条卡片、备注、草稿、映射、设置、日志。
- `notify/`：普通通知、计数推送、每晚提醒。
- `backup/`：导出/恢复。

## 15. 分期

### P0：探针 + 微信单条流 + 通知 + 自己记

- `:capture-plugin` + `:capture-contract`。
- 微信支付成功页/消息探针；金额/时间/支付方式/商户/订单号。
- 插件 Room + 普通常驻通知 + 待记列表 + 单条卡片。
- `openQuickRecord` 预填 + `external_imports` + `BILL_CREATED` 回执 + `getImportStatus` 对账。
- 基础去重（订单号；无订单号只标疑似）。
- **不做**：敲击接管、AI、FGS、气泡、支付宝。

验收：连续 5 笔真实微信支付（含个人收款码），5 笔都留下正确金额和时间；其中 3 笔能正确带出支付方式；通知点开能预填并落账；重复导入不重复。

### P1：支付宝 + 资产映射 + 敲击接管 + 通知动作

- 支付宝支付成功页/消息/H5 探针与适配。
- 资产映射表、管理页、首次未匹配引导。
- 敲击缓存计数 + `ACTION_SHOW_PENDING`。
- 通知 `RemoteInput` 备注 + 忽略；免打扰、锁屏隐私、每晚汇总。
- 规则版本、hook 自检、日志导出。
- 插件备份/导出。

### P2：AI 代理 + 批量导入

- `startClassify`/`getClassifyResult`（App 现有 AI）。
- 单条 AI 草稿 + 批量 AI。
- `importBills` + ID 优先 + 幂等 + 逐条错误。
- 失败重试、部分成功。
- `POSSIBLE_DUPLICATE` 确认/合并 UI。

### P3（可选）

- 通知/短信补充来源。
- 退款冲抵。
- 完整卡片/真气泡。
- 微信/支付宝双开。
- 适配其他记账 App（通用 ingestion API）。

## 16. 验收

1. 微信支付成功 5 秒内：插件新增 `PENDING`，通知数量正确。
2. 支付宝支付成功：同上。
3. 待记列表按时间倒序，金额/时间/来源/支付方式正确；缺失字段有标记。
4. “自己记”：App 悬浮窗预填；保存后 `external_imports` 有记录，插件收到 `BILL_CREATED` 或 `getImportStatus` 对账后标 `RECORDED`。
5. “AI 记”：App 代理返回草稿；确认后 `importBills` 写账单；取消不写。
6. 批量 3 笔：逐条备注 → 3 条草稿 → 全部入账 → 3 条账单；插件全部 `RECORDED`。
7. 手动记账后 3 分钟：对应捕获标 `POSSIBLE_DUPLICATE`，不自动删除；用户确认后才 `DUPLICATE`。
8. 无待记时敲击：行为与现在完全一致。
9. 有待记时敲击（P1）：插件列表先出；“先不记”回空白记账。
10. 锁屏通知不显示金额。
11. 插件被 kill/重启：待记与通知恢复；`IMPORTING` 通过 `getImportStatus` 对账。
12. hook 失效：设置页可见，不崩溃、不产生假记录。
13. 同一 `captureId` 重复导入不产生重复账单。
14. 导入部分失败：成功项已记、失败项可重试。
15. 插件未安装/契约不匹配：App 敲击回退正常；插件待记不丢。
16. 待记不进 App 共享账本同步；插件导出/恢复后仍在。
17. AI 未配置：本地草稿 + 手动分类可用；插件不接触 key。
18. 覆盖率：20 笔真实支付覆盖 7.7 场景矩阵，统计漏捕/误捕；原始事件脱敏回放测试通过。

## 17. 风险与待验证

1. **hook 字段稳定性**：微信/支付宝版本升级改类名/字段；探针 + 规则版本 + 固定版本。
2. **微信 Tinker**：热更新可能让 hook 失效；先不删补丁，观察。
3. **支付宝环境检测**：可能弹异常；个人使用评估，不默认 bypass。
4. **支付成功页字段缺失**：金额/商户为空；缓存归并 + “待补”。
5. **个人收款码方向**：付款给个人 vs 转账；需要方向字段/上下文，探针覆盖。
6. **小程序/多进程**：`:appbrand` 等进程；LSPosed 进程覆盖 + 跨进程去重。
7. **包可见性**：Android 11+ 显式 Provider/广播；`<queries>` + 真机验证。
8. **hook→插件鉴权**：token 不是强边界；Provider 导出面要最小化。
9. **通知权限**：`POST_NOTIFICATIONS` 被拒；降级只记。
10. **App 进程/契约**：`signature` 权限、同签名、`contractVersion`。
11. **AI 代理依赖 App**：App 未配置/被杀；本地草稿兜底。
12. **ID 迁移**：`BatchBillInserter` 从名称改 ID；旧数据/改名/删除要逐条报错。
13. **幂等事务**：`external_imports` + 账单同事务；批量部分成功。
14. **去重误判**：只标疑似；不静默删。
15. **插件备份/卸载**：App 备份不含插件待记；导出提醒。
16. **Android 14/15 后台**：不常驻 FGS 后，Provider/广播拉起是否稳定；真机验证。
17. **GPL-3.0**：AutoAccounting 规则/代码复用；个人使用 vs 分发。
18. **AI 成本/延迟**：App 代理；只在用户主动触发时调用。
19. **插件是“小 App”**：维护成本；分期交付。

## 18. 待拍板

1. **P0 是否接受“先不做敲击接管”**：先通知点开，P1 再接管；推荐是。
2. **AI 代理的异步形态**：`startClassify` + 轮询（推荐）vs 同步 `call`（简单但可能长阻塞）。
3. **插件备份形态**：手动导出 JSON/ZIP（推荐先做）vs 自动备份到本地/WebDAV。
4. **完整卡片/真气泡**：P1 就做，还是 P2 观察使用习惯。
5. **通知动作**：P1 就做 `RemoteInput` 备注/忽略，还是先只做点开列表。
