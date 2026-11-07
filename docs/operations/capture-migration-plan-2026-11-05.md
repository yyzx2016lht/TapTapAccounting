# 待记收件箱迁移到主应用：改造方案（2026-11-05）

> 目标：**主应用持有待记数据、规则、气泡、收件箱 UI；插件退化为「Hook + 原始报文转发 + 兜底队列」**。
>
> 基线：`test-daiji` @ `db6f16f`（当前联调改动已提交保存）。
>
> 本文只做方案，不改代码。实施时按第 4 节分阶段提交，每阶段可单独回滚。

---

## 0. 结论先行

这次迁移不是“把插件代码复制进主应用”，而是**把系统边界重新画一次**：

```text
以前：微信/支付宝 hook → 插件进程（Room + 规则 + 气泡 + UI）→ 主应用（只负责写账单/AI）
以后：微信/支付宝 hook → 主应用（Room + 规则 + 气泡 + UI + 写账单/AI）
                        插件只做：抓原始报文 → 投递 → 投不进去就排队
```

这样改完，当前真机上 90% 的问题会从根上消失：

| 当前问题 | 根因 | 迁移后 |
|---|---|---|
| 插件被 `o-stop` 后待记列表为空 | 主应用跨进程读插件 Provider | 主应用读本地 DB，不依赖插件进程 |
| 微信打字时输入法被顶掉 | Relay 用 1px Activity 解冻插件，抢前台焦点 | 不再启动任何透明 Activity，只用广播/Provider/FGS |
| 气泡不弹 / 延迟十几秒 | 插件进程被冻结，广播/FGS 被 ColorOS 拦截 | 主应用有 FGS + 悬浮窗权限，由主应用画气泡 |
| 两套 Room 来回同步、对账、ACK | 插件和主应用各有一套待记数据 | 待记、规则、账单都在主应用一个库里 |
| 图片记账/拆分要跨进程拿 AI Key、相册权限 | 插件没有主应用的 AI/相册上下文 | 全部在主应用进程内直接调用 |
| 插件 APK 越做越大 | 插件里塞了 Room、QuickJS、UI、通知、保活 | 插件只剩 Hook 和转发，体积回到几百 KB |

---

## 1. 现状盘点

### 1.1 插件现在拥有什么

`capture-plugin` 约 **14.9k 行 Kotlin**，其中 UI 约 **5.9k 行**：

| 层 | 主要文件 | 说明 |
|---|---|---|
| Hook | `hook/ProbeHooker.kt`、`SmsHooker.kt`、`SystemNotificationHooker.kt`、`CaptureForwarder.kt`、`DataFilter.kt` | 微信/支付宝/短信/系统通知抓原始报文 |
| 传输 | `relay/RelayServer.kt`、`service/PluginDirectServer.kt`、`CaptureWakeActivity`、`CaptureKeepAliveService` | 52046 relay、52047 直连、透明 Activity 解冻、FGS 保活 |
| 数据 | `data/AppDatabase.kt` + 5 张表 | `payment_captures`、`capture_events`、`asset_mapping_rules`、`category_rules`、`rule_learning_tasks` |
| 规则 | `parse/AutoAccountingRuleEngine.kt`、`CaptureRuleMatcher.kt`、`RuleBundleStore.kt` | QuickJS 执行 AutoAccounting 规则 + 本地备注/资产规则 |
| 入库 | `ingest/CaptureIngestHandler.kt`、`CaptureIngestProvider/Receiver` | 规则解析 → 去重 → 落库 → 气泡/通知 |
| UI | `ui/PendingListActivity.kt`（约 2.7k 行）、`CaptureHistoryActivity`、`RuleMappingViews`、`DataRuleViews`、`SplitConfirmSheet` | 待记、规则、数据规则、设置、历史 |
| 通知/气泡 | `notify/CaptureNotifier.kt`、`CaptureBubbleManager.kt` | 常驻通知、RemoteInput、付款秒记气泡 |
| 桥接 | `bridge/CaptureBridgeClient.kt`、`RuleLearningManager.kt`、`CaptureReconciler.kt` | 调主应用 AI/拆分/入账，回写状态 |
| 设置 | `data/CapturePrefs.kt` | 提示模式、只记支出、默认种子 |

### 1.2 主应用现在有什么

`app/capturebridge` 约 **2.7k 行**：

| 文件 | 作用 |
|---|---|
| `CaptureBridgeProvider.kt` | 签名保护的桥：AI 归类、拆分、图片拆分、导入账单、账本/资产/分类上下文、规则生成 |
| `CaptureImportService.kt` | 待记 → 账单的唯一写入实现，含幂等 `external_imports`、资产/分类校验、拆分入账 |
| `CapturePluginClient.kt` | 主应用 → 插件 Provider 的客户端，读待记、改草稿、忽略、标记已记 |
| `OverlayPendingCaptureController.kt` | 悬浮窗里的待记细条、待记/新账切换、批量速清、一键归类 |

主应用已有：Room 主库、`BillMutationService`、`BatchBillInserter`、`AIService`、`OverlayService`（FGS）、悬浮窗权限、图片记账、拆分、AI Key、账本/资产/分类。**主应用缺的不是能力，而是待记数据的所有权。**

---

## 2. 目标架构

```text
微信 / 支付宝 / 短信 / 系统通知
        │
        ▼
【tapaccounting-capture.apk】只做三件事：
  1. Hook 抓原始报文（微信 DB/WebView、支付宝、短信、通知）
  2. 转成通用 CapturePayload JSON
  3. 投递：显式广播 → 主应用；同时丢给 com.android.phone relay 兜底
        │
        │ 投递失败：relay 在 com.android.phone 私有目录排队，5 秒重试
        ▼
【敲敲记账主应用】唯一宿主：
  ├─ CaptureIngestReceiver / CaptureIngestProvider（token 鉴权）
  ├─ CaptureIngestCoordinator（规则 → 去重 → 落库）
  ├─ Room：payment_captures / capture_events / asset_mapping_rules /
  │        category_rules / rule_learning_tasks
  ├─ OverlayService（FGS）：秒记气泡 + 现有双击/敲击悬浮窗
  ├─ CaptureNotifier：常驻通知 + RemoteInput 补备注
  ├─ CaptureCenterActivity：待记 / 规则 / 数据规则 / 设置 / 历史
  ├─ CaptureImportService：待记 → 账单（含 external_imports 幂等）
  └─ AIService：智能归类、拆分、图片识单、规则学习
```

### 2.1 职责边界

| 能力 | 插件 | 主应用 |
|---|---|---|
| Hook 微信/支付宝/短信/通知 | ✓ | |
| 原始报文 → CapturePayload | ✓ | |
| 投递 + 兜底队列 | ✓（relay） | |
| 规则解析（QuickJS） | | ✓ |
| 待记 DB / 收件箱 / 通知 / 气泡 | | ✓ |
| AI 归类 / 拆分 / 图片识单 | | ✓ |
| 写账单 / 资产影响 / 共享同步 / 周期匹配 | | ✓ |
| 规则页 / 数据规则 / 历史 | | ✓ |
| 透明 Activity 解冻 / 插件 FGS / 插件悬浮窗 | 删除 | |

### 2.2 UI 迁移映射：是“搬家”，不是“删掉”

插件 0.2 去掉待记 UI，指的是**代码搬到主应用之后，插件 APK 不再保留第二套入口**；功能本身一项都不会少。对应关系：

| 插件现有 UI | 迁移到主应用哪里 | 迁移阶段 |
|---|---|---|
| `ui/PendingListActivity` 待记 tab | `capture/ui/CaptureCenterActivity` 待记 tab | P2 |
| 待记卡片、备注、分类/资产/账本选择、不计/恢复 | 同上 | P2 |
| 拆分工作区、合并明细、差额校验 | 同上 | P2/P3 |
| 一键智能归类、批量入账 | 同上 | P2 |
| `ui/RuleMappingViews`（资产映射/分类规则/设置） | `CaptureCenterActivity` 规则 tab + 设置 tab | P4 |
| `ui/DataRuleViews`（AutoAccounting 数据规则） | `CaptureCenterActivity` 数据规则 tab | P4 |
| `ui/CaptureHistoryActivity`（原始事件/重跑规则） | `CaptureCenterActivity` 历史 tab | P4 |
| `notify/CaptureBubbleManager`（付款秒记气泡） | `OverlayService` 内的 `CaptureBubbleController` | P3 |
| `notify/CaptureNotifier`（常驻通知/RemoteInput） | 主应用 `CaptureNotifier` | P1/P3 |
| `ui/SplitConfirmSheet`（气泡内联拆分） | 优先复用 `CaptureCenterActivity` 拆分工作区；需要内联再平移 | P3 |
| `ui/CaptureImagePickerActivity` | 主应用现有 `ImagePickerActivity` + `CaptureImageAccountingActivity` | P3 |
| 插件设置（提示模式/只记支出/规则包） | 主应用设置页 + `CaptureCenterActivity` 设置 tab | P4 |
| 插件启动页/收件箱入口 | 插件只保留 `ModuleStatusActivity`，按钮“打开敲敲记账” | P5 |

**迁移顺序保证**：P2/P4 把对应 UI 搬完并通过真机回归后，P5 才删除插件里的旧文件。旧代码在 `db6f16f` 和 Git 历史里始终可恢复；旧 APK 的 `capture.db` 也不删除。不会出现“插件 UI 没了、主应用还没接上”的空窗。

---

## 3. 关键设计决策

### 3.1 不新建 `:capture-core` 共享模块

旧交接文档建议把 `PaymentCapture`、`CaptureRepository`、规则引擎抽到 `:capture-core`，让插件和主应用共用。**在当前目标下不建议这么做**：

- 迁移完成后插件只剩 Hook + 转发，不需要 Room/规则/UI；
- 建共享模块会让插件继续被动依赖 QuickJS、Room、规则代码，和“几百 KB 纯 Hook”目标冲突；
- 主应用只需要一个模块内聚的 `com.taostudio.tapaccounting.capture.*` 包，后续真要拆再拆。

**保留 `:capture-contract` 纯 Kotlin 模块**（payload、常量、状态、bill type），插件只依赖它。

### 3.2 传输：广播 + phone relay + 主应用 Provider，不做透明 Activity

推荐链路（按优先级）：

1. **Hook → 主应用显式广播**（`FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES`）  
   - 显式组件不受 Android 11 包可见性过滤；微信/支付宝 hook 已验证对插件可行。
   - 主应用 `CaptureIngestReceiver` 用 `goAsync()` 落库，秒级完成。
2. **同时投递 com.android.phone 的 relay（52046）**  
   - relay 在系统常驻进程里，落盘到自己的私有目录，5 秒重试。
   - relay 优先用 `ContentResolver.call` 调主应用 `CaptureIngestProvider`（主应用会被拉起），失败再发显式广播。
3. **不再启动 1px Activity**  
   - 彻底消除 BAL（后台 Activity 启动）抢焦点、输入法被顶掉的问题。
   - relay 永远只做“调 Provider / 发广播 / 留在队列里”，不做 `startActivity`。
4. **不做插件 TCP 直连（52047）**  
   - 迁移后插件没有常驻进程，52047 没有意义；主应用也不需要长期监听端口。
   - relay 的 Provider 调用已经能做到“拉起主应用 + 低延迟”。

**幂等**：同一条事件会同时走广播和 relay，主应用按 `captureId` / `sourceTxnId` 去重；relay 收到 `ok:true`（含 duplicate）就删队列文件，不需要额外的 app → relay ACK。

**鉴权**：主应用 `CaptureIngestProvider/Receiver` 用 `CAPTURE_INGEST_TOKEN` 校验；token 放在两边 `BuildConfig`，由同一个 Gradle 属性生成，debug 默认 `dev-token`。这不是强安全边界，个人使用足够；签名保护的 `CaptureBridgeProvider` 保持不动。

### 3.3 数据：5 张表进主应用主库

不建议再建一个 `capture.db`。直接进 `AppDatabase`：

- `payment_captures`（待记）
- `capture_events`（原始事件历史）
- `asset_mapping_rules`（支付方式 → 资产）
- `category_rules`（备注/商户 → 分类）
- `rule_learning_tasks`（AI 规则学习队列）

好处：

- 待记 → 账单的 `external_imports` 和待记状态可以放在**同一个 Room 事务**里，彻底去掉 `CaptureReconciler` 的对账；
- 悬浮窗、收件箱、通知、规则页全部本地读，`Failed to find provider info` 这一类问题消失；
- 备份可以按模块扩展，不需要第二套 DB 文件。

代价：`DB_VERSION 41 → 42`，需要写 5 张表的 Migration，并同步 `BackupRepository.getFullData/restoreFullData`（可以分阶段做，先保证升级不丢账单）。

### 3.4 气泡：由 `OverlayService` 托管

- 新增 `OverlayService.ACTION_SHOW_CAPTURE_BUBBLE`，extra 只传 `captureId`。
- `OverlayService` 从本地 DB 读待记，调用 `CaptureBubbleController.show(capture)`。
- 气泡的「智能记账 / 图片记账 / 细改 / 不计」全部在主应用进程内完成：
  - 智能记账 → `CaptureImportService` / `AIService`
  - 图片记账 → 主应用现有 `ImagePickerActivity`
  - 细改 → `OverlayManager.showOverlay(prefill, captureId)`
  - 不计 → 本地 `updateStatus(IGNORED)`
- `stopSelfIfIdle()` 增加 `captureBubbleController.isShowing()` 判断，避免气泡还在服务就被停掉。
- 气泡输入态才申请窗口焦点；用户点击气泡才发生，和“后台偷偷拉 Activity”是两回事。

### 3.5 规则包：不要打进主 APK

- `AutoAccountingRuleEngine` 的 QuickJS wrapper 是 Apache-2.0，可以进主应用。
- `autoaccounting_rules.json` 是 AutoAccounting 的 GPL 规则内容，**不建议直接放进主应用 assets**。
- 做法：主应用 `RuleBundleStore` 只读 `filesDir/rule_bundle.json`；首次由插件 `exportLegacyData` 把规则包推给主应用，或用户在设置页从文件导入。主应用空规则包时只跑本地备注/资产规则。
- 这样主应用 APK 不含 GPL 规则内容，插件仍然是 side-load，商店合规风险最小。

### 3.6 兼容旧插件一个版本

- 主应用保留 `CaptureBridgeProvider` 一个版本，旧插件（0.1）仍然可以调 `openQuickRecord/importBills/classifyNotes/previewSplit`。
- 新插件（0.2）只转发，不再有待记 UI；保留一个只读的 `exportLegacyData` Provider，用来把旧 `capture.db` 和规则包迁到主应用。
- 更新顺序建议：**先装插件 0.2，再装主应用 1.3**。插件 0.2 在没有新主应用时会把事件留在 relay 队列；主应用升级后自动补投，不丢数据。
- 旧插件的 `capture.db` 不删除，保留回滚能力；新插件只读它做迁移。

---

## 4. 分阶段实施

### P1：主应用接收链路（数据层，不动 UI）

**目标**：hook 能把事件送进主应用并落库，主应用能发通知；旧插件仍可用。

改动：

1. `capture-contract`
   - 新增 `APP_INGEST_ACTION`、`APP_INGEST_AUTHORITY`、`APP_INGEST_RECEIVER_CLASS`、`METHOD_LEGACY_EXPORT` 等常量。
2. 主应用 Room
   - 新增 5 个 entity + DAO（从插件平移，包名改为 `com.taostudio.tapaccounting.capture.data`）。
   - `AppDatabase` 版本 42，新增 `MIGRATION_41_42` 建表 + 索引。
   - 新增 `PendingCaptureRepository`、`CaptureEventRepository`。
3. 主应用规则
   - 平移 `AutoAccountingRuleEngine`、`CaptureRuleMatcher`、`RuleBundleCodec/Store`、`RuleJsBuilder`、`RuleLearningPolicy`。
   - `RuleBundleStore` 去掉 assets 兜底，改为只读 `filesDir/rule_bundle.json`。
   - `app/build.gradle.kts` 增加 QuickJS 依赖；release 增加 keep 规则。
4. 主应用接收
   - 新增 `capture/ingest/CaptureIngestCoordinator.kt`：解析 payload → 规则 → 资产映射 → 去重 → 落库 → 通知。
   - 新增 `CaptureIngestReceiver`（exported，token）和 `CaptureIngestProvider`（exported，token）。
   - `AndroidManifest.xml` 注册；`TapApplication.onCreate` 初始化规则引擎。
5. 通知
   - 平移 `CaptureNotifier` + `NotificationActionReceiver` 到主应用，先只做通知，不搬气泡。
6. 插件 Hook
   - `CaptureForwarder` 改为：显式广播到主应用 + 投 relay；保留旧插件广播作为过渡（可开关）。
   - `RelayServer` 改为投主应用 Provider/广播，删除 `startActivity` 透明 Activity 路径。
7. 迁移接口
   - 插件新增 `exportLegacyData(table, offset, limit)` / `exportLegacyRuleBundle()`，用原始 SQLite 读旧 `capture.db`，不依赖 Room。

验收：

- `adb shell am broadcast` 带一条测试 payload → 主应用 `payment_captures` 出现记录，通知出现；
- 主应用被划掉/插件被 `o-stop` 时，广播仍能拉起主应用落库；
- 微信里打字时触发 hook → 输入法不丢焦点；
- 旧插件仍能正常显示它自己的待记（过渡期不破坏）。

### P2：待记收件箱 + 悬浮窗本地化

**目标**：主应用有完整待记列表；悬浮窗细条不再读插件。

改动：

1. 平移插件 `PendingListActivity` 为 `capture/ui/CaptureCenterActivity`（先做「待记」tab）：
   - 卡片、备注、分类、资产、账本、不计/恢复、批量智能归类、一键入账；
   - 拆分工作区、合并明细、图片记账入口；
   - 先不做规则/历史 tab。
2. `CapturePluginClient` 改成 `PendingCaptureStore`（或保留类名但内部读本地 DB），`OverlayPendingCaptureController` 只改数据源，UI 不动。
3. 新增主页入口：
   - 主页顶部「待记 N 笔 · ¥X」胶囊（有待记才显示）；
   - 设置页「自动记账 / 待记收件箱」入口。
4. `CaptureImportService` 入账后在同一事务里把 `payment_captures.status` 置为 `RECORDED`（替代 `markCapturesRecorded`）。

验收：

- 关闭/冻结插件进程，悬浮窗细条、待记列表、一键入账全部正常；
- 待记列表改备注后本地规则立即命中；
- 一键入账写账单 + `external_imports` + 待记状态，重复点击不重复建单。

### P3：气泡 / 通知动作 / 图片 / 拆分

**目标**：付款瞬间的秒记气泡由主应用 FGS 绘制。

改动：

1. 平移 `CaptureBubbleManager` 为 `capture/bubble/CaptureBubbleController`，挂到 `OverlayService`。
2. `CaptureIngestCoordinator` 落库成功后：
   - 提示模式 = BUBBLE：`OverlayService.startCompat(ACTION_SHOW_CAPTURE_BUBBLE, captureId)`；
   - 提示模式 = NOTIFICATION：只刷通知；
   - 提示模式 = SILENT：只落库。
3. 气泡按钮改为进程内调用：
   - 智能记账 → `CaptureImportService.classifyNotes` + `importBills`
   - 图片记账 → 主应用 `ImagePickerActivity`
   - 细改 → `OverlayManager.showOverlay`
   - 不计 → 本地状态
4. 拆分确认页：优先复用 `CaptureCenterActivity` 的拆分工作区；如果保留气泡内联拆分，再平移 `SplitConfirmSheet`。
5. 通知 RemoteInput / 不计动作迁到主应用。

验收：

- 真实支付/模拟支付 → 3 秒内出现气泡；
- 气泡「细改」打开的是主应用现有悬浮窗，金额/资产/时间已填；
- 气泡「图片记账」直接拉起主应用相册/AI，不再跨进程；
- 微信聊天时气泡出现，输入法焦点不被抢。

### P4：规则 / 数据规则 / 历史 / 迁移

**目标**：插件里的规则页、历史页、设置页全部搬进主应用；旧数据迁移完成。

改动：

1. 平移 `RuleMappingViews`、`DataRuleViews`、`CaptureHistoryActivity` 到主应用 `capture/ui`。
2. `CaptureCenterActivity` 增加 tab：待记 / 规则 / 数据规则 / 设置 / 历史。
3. 设置页：提示模式、只记支出、通知权限、规则包导入/导出、从 AutoAccounting 导入。
4. 平移 `AutoAccountingRuleImporter/HistoryImporter/Sync`（localhost 52045）。
5. 数据迁移：
   - 主应用设置 `capture_migrated_from_plugin_v`；
   - 首次启动后台调插件 `exportLegacyData` 分页拉取 5 张表；
   - 规则包写入 `filesDir/rule_bundle.json`；
   - 迁移完成通知「已迁移 N 笔待记 / M 条规则」；
   - 保留插件 `capture.db`，不删除。

验收：

- 旧插件有 20 笔待记 + 50 条规则 → 升级后主应用全部可见；
- 规则页可增删改、样本测试、启停、导入导出；
- 历史页可看原始报文、重跑规则、AI 生成规则；
- 主应用空规则包时只跑本地规则，不崩溃。

### P5：插件彻底瘦身

**目标**：插件只保留 Hook + 转发 + 迁移只读接口。

删除：

- `CaptureApp`（改为最小 Application 或删除）
- `data/*` 全部 Room/仓库/规则/设置
- `parse/*`、`history/*`、`dedup/*`
- `ingest/*`（除迁移 Provider）
- `notify/*`、`ui/*`
- `bridge/*`（CaptureBridgeClient、RuleLearningManager、CaptureReconciler、BillCreatedReceiver）
- `service/*`（KeepAlive、DirectServer、WakeActivity、WakeReceiver、BootReceiver）
- `relay` 里所有 `startActivity` 逻辑

保留：

- `PluginXposed`
- `hook/ProbeHooker.kt`、`SmsHooker.kt`、`SystemNotificationHooker.kt`、`WechatEnvelope.kt`、`WechatRichCache.kt`、`ProbeText.kt`、`DataFilter.kt`
- `hook/CaptureForwarder.kt`（改为投主应用）
- `relay/RelayServer.kt`（改为投主应用 Provider/广播）
- `LegacyCaptureExportProvider`（只读旧 `capture.db` + 规则包）
- 一个极简 `ModuleStatusActivity`（说明模块作用、打开主应用；不驻留）

Manifest 删除：`SYSTEM_ALERT_WINDOW`、`POST_NOTIFICATIONS`、FGS 权限、KeepAlive/Wake/通知/DB 相关组件。保留 Xposed metadata、迁移 Provider、状态 Activity。

验收：

- 插件 APK 体积明显下降（目标：去 QuickJS/Room/UI 后回到几百 KB 量级）；
- 插件不再申请悬浮窗/通知/FGS 权限；
- 微信/支付宝/短信/通知四类来源仍能投递；
- 旧数据仍可导出，回滚旧插件仍可用。

---

## 5. 数据迁移细节

### 5.1 更新顺序

推荐：

1. 安装插件 0.2（forward-only + legacy export）  
   - 新支付事件进 relay 队列；旧 `capture.db` 原地保留。
2. 安装主应用 1.3  
   - 首次启动自动迁移旧 `capture.db`；
   - relay 队列开始补投；
   - 主应用成为唯一收件箱。

如果用户先更新主应用：主应用会提示“请更新插件以完成迁移”，旧插件仍按旧逻辑工作；插件更新后自动迁移。

### 5.2 导出协议

插件 `LegacyCaptureExportProvider`（signature 保护）实现：

```text
counts() -> {ok, tables: {payment_captures: N, capture_events: M, ...}}
exportTable(table, offset, limit) -> {ok, rows: [...], nextOffset}
exportRuleBundle() -> {ok, json}
exportPrefs() -> {ok, promptMode, onlyExpense}
```

- 每次最多 100 行，避免 Binder 1MB 限制；
- `capture_events.rawJson` 可能很大，可以只迁最近 500 条，或迁移时截断；
- 主应用写入用 `INSERT OR REPLACE`，保留原 ID/时间戳；
- 迁移在 Room 事务中分批提交，失败可重试；
- 迁移完成后写 `capture_migrated_from_plugin_v=2`。

### 5.3 回滚

- 插件 `capture.db` 不删除、不修改；
- 主应用新表不影响旧账单；
- 回滚只需装回旧主应用 + 旧插件；旧插件数据仍在；
- 新插件没有待记 UI，回滚时要装回 0.1。

---

## 6. 主应用文件改造清单

### 6.1 数据库

- `app/src/main/java/com/taostudio/tapaccounting/data/local/AppDatabase.kt`
  - `DB_VERSION 41 → 42`
  - entities 增加 5 个
  - `MIGRATION_41_42`
  - 新增 DAO 抽象方法
- `.../capture/data/`：`PaymentCapture`、`CaptureEvent`、`AssetMappingRule`、`CategoryRule`、`RuleLearningTask`、`PendingCaptureRepository`、`CaptureEventRepository`
- `.../capture/parse/`：规则引擎/匹配器/Codec/Store
- `.../capture/dedup/DuplicateDetector.kt`

### 6.2 接收与业务

- `.../capture/ingest/CaptureIngestCoordinator.kt`
- `.../capture/ingest/CaptureIngestReceiver.kt`
- `.../capture/ingest/CaptureIngestProvider.kt`
- `.../capture/notify/CaptureNotifier.kt` + `NotificationActionReceiver.kt`
- `.../capture/bubble/CaptureBubbleController.kt`
- `.../capture/import/`：把 `CaptureImportService` 逐步从 `capturebridge` 迁到 `capture/import`（可先不动，减少 diff）

### 6.3 UI

- `.../capture/ui/CaptureCenterActivity.kt`（由 `PendingListActivity` 平移）
- `.../capture/ui/CaptureHistoryActivity.kt`
- `.../capture/ui/RuleMappingViews.kt`
- `.../capture/ui/DataRuleViews.kt`
- `.../capture/ui/SplitConfirmSheet.kt`
- 主页入口：`HomeFragment` / `HomeBannerController`
- 设置入口：`SettingsActivity` 或 `ProfileFragment`

### 6.4 现有文件改动

- `AndroidManifest.xml`：注册 receiver/provider/activity
- `app/build.gradle.kts`：QuickJS、BuildConfig token、proguard
- `TapApplication.kt`：初始化规则引擎、恢复通知
- `OverlayService.kt`：新增气泡 action、`stopSelfIfIdle` 判断
- `OverlayPendingCaptureController.kt`：数据源改本地
- `CaptureBridgeProvider.kt`：保留兼容；可加 `ingest` 方法给 relay（或单独 Provider）
- `CaptureImportService.kt`：入账时同步待记状态

---

## 7. 测试矩阵

| 场景 | 预期 |
|---|---|
| 主应用冷启动，无插件 | 不显示待记入口，无报错，无额外耗电 |
| 插件被划掉/`o-stop` | 主应用待记列表、悬浮窗细条仍正常 |
| 微信聊天时支付 | 气泡出现，输入法不丢焦点 |
| 支付瞬间杀主应用 | 广播拉起主应用落库；relay 队列补投 |
| 连续 3 笔支付 | 3 条待记，不合并错；通知计数正确 |
| 备注命中规则 | 本地立即归类，不调 AI |
| 备注未命中 | 保持待归类；点智能归类才调 AI |
| 一键入账 | 账单 + external_imports + 待记 RECORDED；重复点击不重复建单 |
| 拆分入账 | N 条账单同 groupId，合计校验；差额拦截 |
| 图片记账 | 相册直接拉起，识别后拆分/合并入账 |
| 旧插件数据迁移 | 20 笔待记 + 50 条规则全部可见；旧 DB 不动 |
| 回滚旧插件 | 旧插件仍能打开并显示旧数据 |
| 重启手机 | relay 队列补投；通知恢复 |
| 关闭通知权限 | 只落库，不崩溃 |
| 关闭悬浮窗权限 | 降级为通知，不崩溃 |

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 广播在 ColorOS 上被延迟 | relay 队列 5 秒重试；关键路径加日志；必要时再加主应用 TCP 直连（备选，不作为第一版） |
| 主应用被系统冻结 | 落库用广播/Provider 拉起；气泡用 FGS；不依赖常驻 |
| Room 42 迁移写错 | 先只加表不动旧表；用旧 APK 造数据覆盖安装验证；备份模块分阶段接 |
| QuickJS release 混淆 | 加 keep 规则；release 包真机跑规则样本测试 |
| 规则包 GPL | 不打进主 APK assets，由插件/文件导入 |
| 新旧插件/主应用版本错配 | 版本检查 + 设置页提示；更新顺序写进发布说明 |
| 迁移数据量大 | 分页导出；raw event 只迁最近 N 条；失败可重试 |
| 双投递重复 | captureId 幂等 + sourceTxnId 去重；relay `ok` 即删队列 |

---

## 9. 建议的第一个 PR

**只做 P1 的「主应用接收链路」**，不改 UI、不删插件代码：

1. `capture-contract` 加常量；
2. 主应用 5 张表 + Migration 42 + DAO；
3. 主应用 `CaptureIngestCoordinator` + Receiver + Provider；
4. 主应用通知（先不做气泡）；
5. 插件 `CaptureForwarder`/`RelayServer` 改为投主应用；
6. 插件 `exportLegacyData` 只读接口；
7. 单测 + 真机 adb 验证。

这样每一步都能独立验证，不会把当前可用的真机测试链路一次性打断。

---

## 10. P1 落地记录（2026-11-05）

P1 已完成的部分：

- 主应用 `AppDatabase` 升到 v42，新增 5 张待记表 + `MIGRATION_41_42`；SQL 从插件
  `AppDatabase_Impl` 生成 schema 对齐，`CaptureSchemaMigrationTest` 用 JVM SQLite +
  Room 导出的 `42.json` 逐列/逐索引校验通过。
- 主应用新增 `CaptureIngestReceiver` / `CaptureIngestProvider`（token 鉴权），
  以及 `CaptureIngestCoordinator`（规则 → 资产映射 → 去重 → 落库 → 通知）。
- 主应用 `RuleBundleStore` 只读 `filesDir/rule_bundle.json`；`CaptureRuleBundleBootstrap`
  首次从插件 `openFile("rule_bundle")` / `exportLegacyRuleBundle()` 拉取并落盘。
  另提供 adb 导入/重载入口（见 `docs/operations/capture-p1-dual-write-test.md`）。
- 主应用通知 + RemoteInput 补备注/不计（P1 默认静默，避免和旧插件重复提醒）。
- 插件 `CaptureForwarder` 在原有直连/relay 之前，额外显式广播给主应用；
  插件原本的本地入库、气泡、通知、relay 全部保留（双写过渡）。
- 插件 `CaptureIngestProvider` 新增 `openFile` 规则包导出和
  `exportLegacyRuleBundle()` 方法；compact JSON 避免 Binder 1MB 限制。
- 新增 P1 联调手册：`docs/operations/capture-p1-dual-write-test.md`。

P1 明确未做的部分（按计划留到后续）：

- 主应用待记 UI / 气泡（P2/P3）；
- relay 从“投插件”改为“投主应用”（P5）；
- `.bak` 备份纳入 5 张待记表（P4）；
- 旧插件数据分页迁移（P4，当前只做了规则包迁移）；
- 插件瘦身/删除旧 UI（P5）。

## 11. P2–P5 落地记录（2026-11-05）

P2–P5 已一并完成：

- **主应用待记 UI**：插件 `PendingListActivity` / `RuleMappingViews` / `DataRuleViews` /
  `CaptureHistoryActivity` / `SplitConfirmSheet` / `CaptureImagePickerActivity` 已平移到主应用
  `com.taostudio.tapaccounting.capture.ui`；主应用可直接打开待记、规则、数据规则、设置、历史。
- **入口**：首页账本头部右上角 `待记 N 笔 ›` 小胶囊（只在首页显示，Room `payment_captures`
  失效自动刷新，不覆盖 FAB/列表）；设置中心「待记收件箱」入口；通知/气泡也可进入。
- **悬浮窗**：`OverlayPendingCaptureController` / `CapturePluginClient` 改为直接读写主应用 Room，
  不再跨进程访问插件 Provider。
- **气泡**：`CaptureBubbleManager` 平移到主应用，由 `OverlayService` 托管；
  `ACTION_SHOW_CAPTURE_BUBBLE` + `CaptureRelayAck` + 6 秒兜底停服务。
- **迁移**：插件 Provider 导出旧 `capture.db`，主应用复制后 `ATTACH DATABASE`，
  按显式列名并入 5 张表（旧库列顺序与主库不同，不能 `SELECT *`）。
  已在真机验证：`payment_captures` / `capture_events` / `asset_mapping_rules` /
  `category_rules` / `rule_learning_tasks` 全部导入成功。
- **规则包**：主应用不内置 GPL 规则包；插件状态页打开时广播 `CAPTURE_PULL_RULES` /
  `CAPTURE_PULL_LEGACY`，主应用在前台回拉。真机导入 `rules=268 enabled=266`。
- **插件瘦身**：插件只保留 Hook + `CaptureForwarder` + `RelayServer` + 旧数据导出 Provider +
  状态页；删除 Room/QuickJS/UI/通知/气泡/保活/1px Activity。debug APK 从约 7.4MB 降到约 1.5MB。
- **relay**：改为优先调主应用 `CaptureIngestProvider`，失败发显式广播，主应用落库后回 ACK；
  不再启动插件 FGS / 透明 Activity。
- **编译**：`:app:assembleDebug`、`:app:assembleRelease`、`:capture-plugin:assembleDebug`、
  `:app:testDebugUnitTest`、`:capture-plugin:testDebugUnitTest` 均通过。

仍未做（明确留到下一轮）：

- `.bak` 备份/恢复还没有纳入 5 张待记表（P4 计划项）；
- 旧插件 `capture.db` 迁移只在当前 v11 schema 上验证，更老 schema 未做兼容；
- 真机微信/支付宝真实支付回归、ColorOS 长时间冻结/重启回归还需要继续跑。

## 12. 已确认的决策

1. **入口位置**：主页顶部胶囊 + 设置页入口都做。
2. **过渡策略**：插件 0.2 去掉待记 UI，但前提是 P2/P4 已把对应 UI 搬进主应用并通过回归；插件只保留状态页 + 转发 + 迁移接口。旧 UI 代码在 Git 历史/旧 APK 中保留，`capture.db` 不删除。
3. **规则包**：主应用不内置 GPL 规则包；由插件迁移或用户从文件导入。主应用只带 Apache-2.0 的 QuickJS 引擎。
4. **更新顺序**：先插件 0.2、再主应用 1.3，写进发布说明。
5. **QuickJS**：主应用引入 Apache-2.0 的 QuickJS wrapper，接受 APK 增大约几百 KB。
