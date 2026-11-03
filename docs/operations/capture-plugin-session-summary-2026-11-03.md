# 支付捕获插件：会话压缩摘要（2026-11-03）

> 给下一个会话/子代理：先读这一份，再按需看 `docs/operations/capture-plugin-handoff-2026-11-02.md`。
> 不要提交；不要改用户未提交的个人改动；不要泄露 `.tmp_autoaccounting_pull/main_device/` 里的个人数据。

## 1. 目标与架构（已定）

- 独立 Xposed 插件持有“待记收件箱”；敲敲记账只做账本/编辑器/最终写入。
- 插件 LSPosed 作用域：微信、支付宝；**不勾敲敲记账**。
- 捕获 → 插件 Room → 常驻通知/列表 → “自己记”调 App 悬浮窗 / “AI 记”后续由 App 代理。
- 写账单：`openQuickRecord` + `external_imports` 同事务 + `getImportStatus` 对账。
- 不做自动入账；P0 只记支出；UI 暂时保持极简（用户明确说后面再优化）。

## 2. 代码状态

构建命令：

```powershell
.\gradlew.bat :app:assembleDebug :capture-plugin:assembleDebug
```

最近一次 `:capture-plugin:assembleDebug` **BUILD SUCCESSFUL**。

新增/关键文件：

- `capture-contract/`：`CaptureContract.kt`、`CapturePayload.kt`、`AccountingContext.kt`、`ImportBill.kt`
- `capture-plugin/`
  - `data/`：`PaymentCapture`、DAO、Room、Repository
  - `ingest/CaptureIngestProvider.kt`：hook → 插件的 token 入口；`enrichWithRules` 调规则引擎
  - `parse/AutoAccountingRuleEngine.kt`：QuickJS 执行 AutoAccounting 116 条启用规则
  - `hook/PluginXposed.kt`、`ProbeHooker.kt`、`CaptureForwarder.kt`、`WechatEnvelope.kt`、`ProbeText.kt`、`WechatRichCache.kt`
  - `ui/PendingListActivity.kt`：极简列表 + 模拟一笔/解析样本/模拟WCDB/自己记/备注/忽略
  - `bridge/CaptureBridgeClient.kt`、`CaptureReconciler.kt`、`BillCreatedReceiver.kt`
- App 侧：
  - `capturebridge/CaptureBridgeProvider.kt`
  - `ExternalImport`、`ExternalImportDao`
  - `AppDatabase` v40 + `MIGRATION_39_40`
  - `BillMutationService`：captureId/requestId，账单 + 回执同事务，`BILL_CREATED`
  - `OverlayService`/`OverlayManager`/`AccountingFormController`：prefill + captureId 透传
  - Manifest：signature 权限 + Provider + `<queries>`

## 3. 已真机验证（备用机 KB2000，`172.20.1.22:41103`）

- 插件安装、启动正常；“桥接：已连接”。
- “解析样本”：两条规则在设备 QuickJS 上正确：
  - mMap ¥9.00 同程旅行 / 招商银行储蓄卡(4852)
  - 转账 ¥1.00 吴希城6.4 / 招商银行储蓄卡
- “模拟WCDB”（XML → `WechatEnvelope` → 规则 → 入库）：
  - 9.00 和 1.00 两条都命中规则并进入待记列表。
- “自己记”：
  - 打开 App 悬浮窗，金额/时间预填；
  - 选资产“招商银行”、分类“网费”，保存后 App bills 新增 9.00 账单；
  - `external_imports` 有 `captureId → billId`；
  - 插件刷新后该条变“已记”，待记数量减一。
- 注意：备用机已产生 1 条 9.00 测试账单 + 几条待记；测试数据可清理。

## 4. 真实规则数据验证（仅本地，已脱敏汇总）

从主设备 AutoAccounting 本地服务拉取已匹配数据到：

```text
E:\FlipAccounting-AI\.tmp_autoaccounting_pull\main_device\
```

- 微信 100 条已匹配样本：规则引擎解析出 79 条支出，规则命中名称 79/79 一致；12 条未命中，9 条非支出。
- 支付宝 100 条：按 **单元素 JSON 数组** 输入，解析出 69 条支出、29 条非支出、2 条未命中。
- 关键修复：支付宝 `SyncMessage` 必须把单条包成 `JSONArray().put(item)` 再上报；规则 406/59 等按数组解析，不能只发 item。
- 结论：规则本身可用；微信 mMap/transfer envelope 与真实数据一致；支付宝已修正数组形状。

## 5. 还没做

1. 安装最新插件 APK（支付宝数组修复后的版本）到备用机并回归。
2. 真实微信/支付宝 hook 未验证（备用机没有数据；主设备有真实数据但不要随便装 debug App）。
3. NOTICE 通知 hook 未接（微信 1 条、支付宝 2 条 NOTICE 规则暂时不执行）。
4. 资产映射 UI、AI 代理、批量导入、备份/导出未做。
5. UI 仍是功能骨架，用户说后面再优化。

## 6. 下一步（子代理优先）

- 构建 `:app:assembleDebug :capture-plugin:assembleDebug`。
- 安装最新 `capture-plugin-debug.apk` 到 `172.20.1.22:41103`。
- 打开插件，点“解析样本”和“模拟WCDB”，确认无回归。
- 更新 `docs/operations/capture-plugin-handoff-2026-11-02.md` 的“最新进度”段落（只写测试结论，不写个人数据）。
- 不 commit，不碰 `app/` 业务代码，不把 `.tmp_autoaccounting_pull/main_device` 内容写入仓库/文档。
