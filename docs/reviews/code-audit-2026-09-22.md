# 全项目 Bug 审计与修复跟进报告

| 项 | 值 |
|----|-----|
| 日期 | 2026-09-22 |
| 范围 | `:app` 主模块（约 339 Kotlin 源文件 / 8.2 万行）；不含 `archive/legacy-server`（不参与构建） |
| 方法 | 6 路并行深读（数据层 / 金额逻辑 / AI 聊天 / UI·手势 / 安全 / 测试盲区）+ 历史审计对照 + 单元测试 |
| 单测 | `.\gradlew.bat testDebugUnitTest` → **BUILD SUCCESSFUL** |
| 对照基线 | `docs/archive/2026-06-code-audit/`（2026-06-22） |
| 本轮性质 | **只审不改**；本文档供后续按优先级修复与勾选跟进 |

---

## 0. 执行摘要

- 有效发现约 **140+ 条**（历史仍存在 + 新问题），去重归并后下列 **P0–P2 清单为优先处理面**。
- 单测全绿 **≠** 核心记账路径安全：`BillAssetImpactService`、保存事务、流式 AI、共享同步、备份恢复等高风险模块 **几乎无测试**。
- **日常单币种、单账本记账**主路径大体可用；**多币种转账、统计汇总、备份/共享恢复、图片记账断流**存在可感知或资金级风险。
- 历史 14 条 Critical 中：约 **7 条已修**、**约 7–10 条仍存在或新发现**（含转账目标端、历史汇率、缺率吞异常、WebDAV 明文等）。

### 使用场景风险速判

| 用法 | 风险 |
|------|------|
| 单币种日常记账 | 问题不大，可继续用 |
| 多币种转账 + 看统计 | **余额/统计可能错** → 先修 P0-1、P0-2 |
| 备份/合并恢复/CSV/共享账本 | **数据一致性风险** → P1 数据项 |
| 本地安全（Key/云密码/Gist） | 远程 Gist 覆盖、明文 prefs → P1 安全项 |

---

## 1. 修复任务清单（按优先级勾选）

状态图例：`[ ]` 未开始 · `[x]` 已完成 · `[~]` 部分完成 · `[-]` 不修/驳回

### P0 — 资金 / 数据丢失 / 可双写（建议最先修）

- [x] **P0-1 跨币种转账目标端金额错误**  
  - 位置：`app/src/main/java/com/taostudio/tapaccounting/logic/BillAssetImpactService.kt:311-313`（`targetDeltaInCurrency`）；时间线同源 `logic/AssetBillBalanceHistory.kt:71-72`  
  - 现象：`amount * exchangeRate`，忽略 `_targetCurrency` / `bill.currency`，无 `roundMoneyForCurrency`；目标非 CNY 时余额写成错误币种金额  
  - 状态：历史 Critical **仍存在**  
  - 修法：按源/目标币种 `convertAmountBetweenCurrencies` + 按目标货币舍入；与源端对称；补跨币种转账单测  

- [x] **P0-2 转账统计把「源→目标」汇率当「→CNY」**  
  - 位置：`ui/main/stats/StatsViewModel.kt:106-107,724-725`（`statsAmountOf` / `totalTransfer`）  
  - 现象：多币种且未筛币种时，`amount * exchangeRate` 对转账语义错误，汇总折错  
  - 状态：新问题  
  - 修法：转账单独 CNY 折算或只展示原币；与支出/收入 `exchangeRate` 语义分离  

- [x] **P0-3 汇率缺失静默跳过余额影响**  
  - 位置：`BillAssetImpactService.kt:32-38,101-107`（catch 后返回 0）；未校验入口：`logic/BillRestoreHelper.kt:50-57`、`logic/InvestmentInterestService.kt:497+`（`insertLocalGeneratedBillWithinActiveTransaction`）  
  - 现象：账单落库、余额不动；删除/恢复不对称  
  - 状态：历史问题 **升级为 Critical**（恢复/结息路径）  
  - 修法：恢复/结息/一切 apply 前 `validateRequiredRatesForBill`，失败中止事务；评估是否禁止吞异常  

- [x] **P0-4 降级安装备份失败仍 destructive 清库**  
  - 位置：`data/local/DatabaseDowngradeHelper.kt:60-75`；`data/local/AppDatabase.kt:777-778`（`fallbackToDestructiveMigrationOnDowngrade`）  
  - 现象：`createBackup` 返回 null 只打日志，随后仍清库  
  - 状态：新问题  
  - 修法：备份失败时阻断 destructive（抛错/阻塞打开）或强制先落可验证备份  

- [x] **P0-5 多币种汇率确认路径 `isSaving` 旁路 → 可双写账单**  
  - 位置：`logic/AccountingFormController.kt:1645-1698`（launch 后 return）、`isSaving = true` 在 `:1741`；动画 `withEndAction { handleSave() }` 约 `:842`  
  - 现象：汇率确认分支在加锁前返回，双击/动画重入可双写  
  - 状态：新问题（`isSaving` 主路径已修，旁路仍在）  
  - 修法：所有 `handleSave` 入口先置 `isSaving`，确认回调路径共用同一把锁  

- [x] **P0-6 远程 Gist 静默覆盖 AI API Key / URL**  （2026-09-22 用户确认：远程配置功能整体关闭）  
  - 位置：`RemoteConfigManager.kt:13,76-82`  
  - 现象：固定 GitHub Gist 配置非空则直接 `Prefs.setAiKey` / `setAiUrl`，无签名、无用户确认  
  - 状态：新问题  
  - 修法：删除自动 apply，或签名校验 + 用户逐项确认；优先考虑关闭远程覆盖 Key  

### P1 — 数据一致性 / 安全 / AI 可用性

#### P1-A 金额与余额（接 P0）

- [ ] **P1-1 有退款时编辑支出：净额与 original/退款脱节**  
  - `logic/BillMutationService.kt:163-168`；`amount = coerceIn(0, oldBaseOriginal)` 且固定 `originalAmount`  
  - 修法：`amount = (newOriginal - sum(linked refunds))` 或禁止有退款时改净额语义  
- [ ] **P1-2 范围删除部分退款：支出仍按全额 original 回滚**  
  - `logic/BillDeleteHelper.kt:173-195`  
  - 修法：级联删/撤全部关联退款，或按剩余退款调整 revert  
- [ ] **P1-3 历史余额用实时汇率**  
  - `logic/AssetBillBalanceHistory.kt:22,97-99` → `CurrencyManager.getRate`  
  - 修法：优先 `bill.exchangeRate` / 记账时汇率；补「汇率变动后时间线」测试  
- [ ] **P1-4 `roundMoney` 固定 2 位，零小数货币写脏**  
  - `logic/MoneyConversionService.kt:84-86`；调用：`AssetBillBalanceHistory`、`BillBalanceSnapshotService.kt:56`  
  - 修法：关键路径改 `roundMoneyForCurrency`  
- [ ] **P1-5 收入 revert 用当前 amount 而非 baseOriginalAmount**  
  - `BillAssetImpactService.kt:149`（对比支出 `:137`）  
- [ ] **P1-6 InsightEngine 多币种直接 sum amount**  
  - `logic/insight/InsightEngine.kt:97-98,137-140`  
- [ ] **P1-7 入库金额不做货币感知舍入**  
  - `logic/AmountInputEditor.kt`；`AccountingFormController.kt` 约 1047–1079、1914–1916  

#### P1-B 备份 / 恢复 / 同步

- [ ] **P1-8 合并恢复去重不按 bookName → 串账**  
  - `data/repository/BackupRepository.kt:429-439`  
  - 修法：去重键含 `bookName`/`bookId`；重映射校验  
- [ ] **P1-9 CSV 导入无事务**  
  - `BackupActivity.kt:2435-2474`  
  - 修法：整段 `db.withTransaction`  
- [ ] **P1-10 Gson 绕过 Kotlin 非空/默认值（旧备份 NPE）**  
  - `data/backup/DataExportManager.kt:12-33`；消费：`BackupRepository.restoreFullData` / `mergeRestoreFullData`  
  - 修法：Kotlin 默认值 adapter 或反序列化后 sanitize；历史 09#7 **仍存在**  
- [ ] **P1-11 分类删除/迁移无统一事务**  
  - `data/repository/CategoryRepository.kt:194-248`  
- [ ] **P1-12 共享 codec 过严丢弃 subType∉{0,2}**  
  - `data/sync/SharedOperationCodec.kt:31-34`；`SharedSyncEngine.kt:118/127`  
- [ ] **P1-13 云端操作列表硬顶 10_000 条**  
  - `data/sync/SharedWebDavClient.kt:102-103`  
- [ ] **P1-14 共享 WebDAV 允许重定向（凭据可出网风险）**  
  - `SharedWebDavClient.kt` 未关 `followRedirects`；对照备份侧已关  
- [ ] **P1-15 云 create/join 远端成功本地失败 → 孤儿状态**  
  - `data/sync/SharedLedgerService.kt:56-113`  
- [ ] **P1-16 恢复 beforeCommit 内 prefs/媒体与 Room 非杀进程原子**  
  - `BackupActivity.kt` 约 1845–1884、2068–2105；`RestorePreferencesTransaction` / `RestoreMediaTransaction`  

#### P1-C 安全

- [x] **P1-17 云备份 WebDAV 密码明文 prefs**  
  - `BackupActivity.kt:114-117,873-876`；`AutoBackupWorker.kt:36,138`；prefs `tap_cloud_backup_prefs`  
  - 修法：对齐 `SharedCredentials` Keystore  
  - 2026-09-22：`CloudBackupCredentials` + `KeystoreSecretBox`/`SecretEnvelope`；自动迁移旧明文；`AutoBackupWorker`/`PrefsBackupSupport` 已接入  
- [x] **P1-18 备份派生密钥 Base64 明文 prefs**  
  - `data/backup/BackupPasswordKeyStore.kt:49-60`  
  - 2026-09-22：派生密钥经 `KeystoreSecretBox` 加密存储；旧 Base64 明文读取时自动迁移  
- [x] **P1-19 AI API Key / 多提供商 Key 明文 prefs**  
  - `PrefsAiSupport.kt:8-10,51-99,123-130`  
  - 2026-09-22：`ai_api_key` / `ai_provider_keys_v1` 经 `KeystoreSecretBox` 加密；旧明文自动迁移；导入走 `PrefsAiSupport`  
- [x] **P1-20 `BackupSecretPolicy` / PIN 加密 API Key 未接入生产**  
  - `BackupSecretPolicy.kt` 仅测试；`PrefsBackupSupport` 仍导出明文 `ai_api_key_v1` 等  
  - 2026-09-22：`prepareEncryptedModule`/`stripSecretValues`/`requireSecretFree(allowedSecretModules)` 接入 `RecoverySnapshotService.create`；无 PIN 时剥离明文密钥，有 PIN 时 `BackupPinCrypto` 字段级加密  
- [x] **P1-21 4 位 PIN + 60k PBKDF2；V2 恢复无限重试**  
  - `BackupPinCrypto.kt:15-29`；`BackupActivity.kt` 恢复递归重弹  
  - 2026-09-22：新 PIN 6–8 位 + 200k PBKDF2（解密兼容旧 4 位/60k）；恢复码/备份密码重试上限 10 次  
- [x] **P1-22 Manifest 导出与 cleartext**  
  - `usesCleartextTraffic=true`（`AndroidManifest.xml:52`）  
  - `BootReceiver` exported + `RESTART_SERVICE`（约 123–131）  
  - `QuickStartActivity` exported（约 70–76）  
  - `BackupActivity` exported + VIEW `.bak`（约 159–171）  
  - 2026-09-22：cleartext=false；移除 RESTART_SERVICE filter（恢复改直接拉服务）；QuickStart/Backup 均 exported=false；去掉 VIEW `.bak`  
- [ ] **P1-23 历史 git 中可能残留 keystore/API（需轮换确认，勿打印密钥）**  
  - 2026-09-22：提醒用户轮换历史泄露面的 API Key / keystore 口令（不在仓库打印密钥）  

#### P1-D AI / 聊天

- [ ] **P1-24 多模态二次 adapt 关掉 thinking**  
  - `AIService.kt` 约 1111/1575；`AIServiceCommon.kt:78-113`  
- [ ] **P1-25 图片/截图记账流式未完成直接 throw（丢 partial）**  
  - `AIService.kt` 约 423、548、663、744、938；对齐文字路径 `:1706-1712`  
- [ ] **P1-26 记账失败 forceTextReply=false 静默移除加载气泡**  
  - `ChatMessagePipeline.kt:751-753,964-983`；`ChatActivity` 默认 `forceTextReply=false`  
- [ ] **P1-27 AudioRecord 竞态 + 仅 catch IOException**  
  - `ChatAudioRecordController.kt:74-133`；`logic/VoiceInputHandler.kt` 同类  
  - 注：历史 09 曾驳回「崩溃」结论，但异常面仍建议收严（catch `IllegalStateException` 或先停读线程再 release）  
- [ ] **P1-28 LocalAsrService 重导失败/取消 `deleteRecursively` 抹掉可用模型**  
  - `LocalAsrService.kt:252-300,640-666`  
- [ ] **P1-29 多处 CoroutineScope 永不 cancel**  
  - `AiAssistant.kt:42`；`AccountingFormController.kt:49`；`LocalAsrService.kt:221,480,600`；`AppListActivity.kt:49`；`ProfileFragment.kt:187,526`；`OverlayDialogs` 多处  

### P2 — 崩溃边缘 / 手势 / UI / 迁移 / 其它

- [ ] **P2-1** `MainActivity` 三处 `commitNow()`：`MainActivity.kt:352,911,921` → `commitNowAllowingStateLoss` + stateSaved 守卫  
- [ ] **P2-2** `TapTapTapRT.checkDoubleTapTiming` 相邻间隔计数；超时勿在仅 1 击时 `return 2`（`tap/TapTapTapRT.kt:38-58`）  
- [ ] **P2-3** `TapTfClassifier` FileInputStream 不 close（`tap/TapTfClassifier.kt:60-67`）  
- [ ] **P2-4** 图表 K 格式化 `100K`→`10K`（`ui/main/home/HomeChartController.kt:314-320`）  
- [ ] **P2-5** 小组件写死 `¥`（`widget/ExpenseWidgetRenderer.kt:321`）  
- [ ] **P2-6** 批量删账单主线程 DB（`ui/main/home/HomeMultiSelectController.kt:65-75`）  
- [ ] **P2-7** 周期账单 `calculateNextExpected` 不用 `dayOfMonthHint`，月末漂移（`logic/RecurringBillingService.kt:258-266`）  
- [ ] **P2-8** 信用卡 `dueDay==0` 回落 billingDay；`amountDue` 无期初（`logic/CreditCardCycleService.kt`）  
- [ ] **P2-9** `StatsFragment` viewModels 工厂 `requireContext()`（`StatsFragment.kt:96-99`）  
- [ ] **P2-10** `KeepAliveAccessibilityService` `TYPES_ALL_MASK`（约 :77）  
- [ ] **P2-11** `OverlayService` receiver 注册失败无 flag 回退（约 122–131）  
- [ ] **P2-12** `LocalAsrService.streamSamples` 无同步（约 471+）  
- [ ] **P2-13** `StatsViewModel` `linkedMapOf` 缓存无同步（约 :92）  
- [ ] **P2-14** `EditBillActivity` onDestroy 不 dismiss BottomSheet  
- [ ] **P2-15** `fillDataToUi` `Double.toString()` 科学计数法（`AccountingFormController.kt:2627-2628`）  
- [ ] **P2-16** Widget 刷新协程无 CoroutineExceptionHandler（`BaseExpenseWidgetProvider.kt:36-44`）  
- [ ] **P2-17** 迁移：`MIGRATION_20_21` 吞异常；`MIGRATION_5_6` 表名；v1–v4 无迁移（`AppDatabase.kt`）  
- [ ] **P2-18** 分类树只处理一层子分类；`saveOrderedCategoryTree` 静默提升父节点（`CategoryRepository.kt`）  
- [ ] **P2-19** `AssetRepository` 单例缺 billDao/appDatabase 时跳过清理（`TapApplication` 构造）  
- [ ] **P2-20** 合并恢复 rules/chat/recurring 无去重（`BackupRepository.kt:528+`）  
- [ ] **P2-21** `isRefundLikeRemark` `contains("退款")` 误伤（`CsvManager.kt:279-286`）  
- [ ] **P2-22** Shizuku 改全局 `max_phantom_processes`（`ShizukuShell.kt:56-69`）  
- [ ] **P2-23** `LocalAsrService.kt:528` 错误文案乱码  
- [ ] **P2-24** `fillData`/诊断：看门狗用 `System.currentTimeMillis`（Flip/Tap/Overlay）  
- [ ] **P2-25** Screen/ImagePicker 静态回调悬挂 OverlayManager  

### 已修复（历史项，无需再开单，仅回归参考）

| 项 | 位置/说明 |
|----|-----------|
| 编辑账单资产影响在事务外 | `BillMutationService.replaceBill` 全程 `withTransaction` |
| `handleSave` 无重入锁 | `isSaving` + finally 复位（旁路见 P0-5） |
| 图片压缩 Bitmap 不回收 | `ChatMediaController` / `AiImageCompressor` finally recycle |
| 文字流式 partial 丢弃 | `AIService.kt:1706-1712` |
| `clearCategoryByName` no-op | `BillDao.kt:466-479` |
| merge 聊天 billId 未重映射 | `BackupRepository` `billIdMap` + `remapChatBillReferences` |
| MainActivity 动画路径 commitNow | 828/885 等已 `commitNowAllowingStateLoss` |
| allowBackup=true | 现为 `false` |
| 明文 backup last PIN | 启动删除 |
| 自动备份整包读内存 | `AutoBackupWorker` File 流式上传 |
| displayMessages 多线程写 | 已收敛主线程（09 驳回） |
| 零汇率 1:1 | `MoneyConversionService` 抛异常 |

---

## 2. 测试盲区（修 P0/P1 时应同步补测）

| # | 场景 | 关联 |
|---|------|------|
| 1 | 跨币种转账守恒（源/目标舍入、fee、exchangeRate） | P0-1 |
| 2 | 历史汇率时间线 vs 实时汇率 | P1-3 |
| 3 | 编辑事务原子性（中途异常回滚余额） | P0 相关 |
| 4 | `handleSave` 汇率确认路径防重入 | P0-5 |
| 5 | 旧备份 Gson 缺字段恢复 | P1-10 |
| 6 | 合并恢复 ID 重映射 + 按 book 去重 | P1-8 |
| 7 | Room 迁移链 v5→当前 | P2-17 |
| 8 | 共享同步双端冲突 | P1-12+ |
| 9 | 周期账单到期幂等 + 月末 | P2-7 |
| 10 | 流式断流 partial（图片+文字） | P1-25 |

**零测试高风险模块（优先）**：`BillAssetImpactService`、`AccountingFormController.handleSave`、`BillMutationService`、`AIService` 流式、`SharedSyncEngine`、`BackupRepository` 恢复、`RecurringBillingService`、`BackupPinCrypto`。

删除无意义测试：`ExampleUnitTest.kt`（`2+2`）。

---

## 3. 建议执行顺序

1. **第一批（资金+防丢库）**：P0-1 → P0-2 → P0-3 → P0-4 → P0-5，每条带单测  
2. **第二批（安全止血）**：P0-6、P1-17–P1-22（Gist、明文密码、exported/cleartext）  
3. **第三批（数据一致）**：P1-8–P1-16  
4. **第四批（AI 体验）**：P1-24–P1-29  
5. **第五批（P2 清单）**：按用户可感知度选做（图表 K、三击、FD、commitNow 等）  
6. **并行**：每完成一条，在本文 §1 勾选；Critical 补测进 CI  

---

## 4. 模块扫描来源（可复扫）

| 报告分册 | 覆盖 |
|----------|------|
| 数据层 | `data/**`、Backup/Sync/Repository、迁移 |
| 金额逻辑 | `logic/**`、StatsViewModel、预算/资产/周期/投资 |
| AI 聊天 | AIService、Chat*、LocalAsr、附件 |
| UI/手势 | ui/**、Overlay、tap/**、widget、Manifest 组件 |
| 安全 | Crypto、Prefs 密钥、Manifest、构建、日志 |
| 测试+回归 | `src/test` 对比、历史 09 Critical 对照 |

历史分册：`docs/archive/2026-06-code-audit/`。

---

## 5. 跟进约定

- 修一条：改代码 + 补/改测试 + 本文件对应 `- [x]` + 简短 commit 说明引用 `P0-x`/`P1-x`。  
- 不修/驳回：改为 `- [-]` 并注一行原因（日期）。  
- 新发现：追加到对应优先级，勿塞进「已修复」。  
- 本报告不替代 CI；单测通过是底线，金额路径需专用断言（守恒、舍入、历史汇率）。

---

*生成：全项目只读审计 · 2026-09-22 · 供修复跟进使用*
