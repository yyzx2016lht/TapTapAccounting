# 长任务最终回归报告（F11）

- 仓库：`C:\Users\tao\.local\share\opencode\worktree\a3564aa184453a0527aad584b9eacab94782c39e\test-daiji`
- 分支：`test-daiji`
- 日期：2026-11-05
- 编排者 session：`ses_e58a13a2dffe1cW2WQTkPXVACV`

## 1. 构建与测试

```powershell
.\gradlew.bat :app:assembleDebug :capture-plugin:assembleDebug :app:testDebugUnitTest :capture-plugin:testDebugUnitTest :capture-contract:test
```

结果：

```text
BUILD SUCCESSFUL
```

- 单元测试：112 个 suite、653 项，0 failures / 0 errors。
- 无编译错误；仅有项目原有的 Gradle/Kotlin/Android deprecation 警告。

## 2. Commit 与 APK

| ID | Commit | APK 目录 |
|---|---|---|
| F01 | `91f4eba` feat(F01): 文案与来源注册表 | `dist/long-task/F01-copy-source/` |
| F02 | `16acb2f` feat(F02): 分类语义重构 | `dist/long-task/F02-classification-semantics/` |
| F03 | `7fe07db` feat(F03): 轻量智能归类与无备注跳过 | `dist/long-task/F03-smart-classification/` |
| F04 | `503b0af` feat(F04): 气泡三态与直接记账 | `dist/long-task/F04-bubble-three-states/` |
| F05 | `409e177` feat(F05): AI 规则学习 | `dist/long-task/F05-ai-rule-learning/` |
| F06 | `c4ed69f` feat(F06): 文字拆分记账与支付组合并 | `dist/long-task/F06-text-split-payment-group/` |
| F07 | `28012b1` feat(F07): 悬浮窗待记上下文条与待记新账切换 | `dist/long-task/F07-overlay-capture-context/` |
| F08 | `d14fdcf` feat(F08): AI 面板缩小常驻小圆 | `dist/long-task/F08-ai-panel-mini-bubble/` |
| F09 | `a84a243` feat(F09): 规则页与数据规则同步 | `dist/long-task/F09-rule-page-sync/` |
| F10 | `c23697c` feat(F10): 图片记账与拆单合并 | `dist/long-task/F10-image-accounting-split/` |

每个目录均保留 `app-debug.apk` 与 `capture-plugin-debug.apk`，大小均大于 0。最终 F10 构建产物：

- `app-debug.apk`：33,701,363 bytes
- `capture-plugin-debug.apk`：7,433,197 bytes

## 3. 手动验收步骤

> 当前环境没有连接 Android 设备，以下为逐功能手工验收清单；自动化部分已在每项 commit 前跑过编译与单测。

### F01 文案与来源注册表
1. 触发一笔微信/支付宝待记，气泡、通知、待记列表、历史页、规则页的来源名一致。
2. 全局搜索用户可见文案，不再出现“买什么了 / 青菜 / 五花肉 / 菜市场买菜 / 待自动分类”。
3. `capture-contract` 的 `CaptureSourceRegistryTest` 覆盖假来源与未知包名兜底。

### F02 分类语义重构
1. 新 hook 一笔待记，数据库 `categoryName` 为空。
2. 备注写“苹果”，已有 `苹果→水果` 规则时分类变为水果、`categorySource=RULE`。
3. 备注写“沃尔玛”，没有 NOTE/ANY 规则时不因 MERCHANT 规则自动分类。
4. 手动选分类后改备注或点自动归类，分类不被覆盖。

### F03 轻量智能归类与无备注跳过
1. 待记不写备注，保持“待归类”，不产生 AI 分类请求。
2. 备注“苹果”且无规则，提交时调一次轻量 AI 并归到水果。
3. 备注“苹果”且已有规则，不调 AI。
4. 一键入账只入账已分类项，缺分类弹窗逐笔补分类。

### F04 气泡三态与直接记账
1. 气泡摘要态显示 `[智能记账] [细改] [不计]` 与 `✕`。
2. 输入备注后“记这笔”可直接入账普通支出。
3. “不计”后待记数量减少，待记页“已不计 (N)”可恢复。
4. `✕` 后待记仍在；转账/退款/缺资产/可能已记转“细改”。

### F05 AI 规则学习
1. 备注“苹果”、AI 归到水果、确认入账，1-3 秒内 toast `已记住：苹果 → 水果`。
2. 规则页出现来源为“AI 学习”的苹果→水果规则。
3. 下次备注“苹果”不再调 AI。
4. 手动/内置/导入规则不被 AI 覆盖；失败重试最多 2 次，插件启动补跑。

### F06 文字拆分记账与支付组合并
1. 70 元待记备注“苹果30 雪梨40”，勾选拆分 → 2 条账单、同一 `paymentGroupId`、一条回执。
2. 账单页 2 条明细；资产详情 1 条 70 元，可展开 2 条。
3. 不勾选拆分 → 仍为 1 条 70 元。
4. 合计 65 → 提示差额并可补“其他”；只有一项缺金额 → 按差额推算并标注。
5. 重复确认同一 captureId 不重复建单。

### F07 悬浮窗待记上下文条与待记新账切换
1. 有待记时打开悬浮窗，顶部是单行细条，不是双层卡片。
2. 切“新账”填内容 → 切回“待记” → 再切“新账”，内容仍在。
3. 批量面板“记新账”后可切回待记。
4. 单笔待记备注命中本地规则时分类回填。

### F08 AI 面板缩小常驻小圆
1. AI 面板点“缩小” → 54dp 小圆常驻，切到其他 App 仍在。
2. 点小圆恢复同一面板，输入文字仍在。
3. 点面板外面 → 收成小圆；X → 关闭并移除小圆。
4. 普通表单点外面仍关闭；键盘弹出/收起不误收起。

### F09 规则页与数据规则同步
1. 规则页可看到内置数据规则，可搜索、启停。
2. 本机 AutoAccounting 打开时能同步规则与 common/category；关闭时提示“先打开自动记账”。
3. 新增自定义规则，用样本测试命中。
4. 规则变更后重新 hook 生效；用户规则同步时不被覆盖。

### F10 图片记账与拆单合并
1. 70 元待记选一张 30+40 的图片 → 2 条账单、同一支付组。
2. 账单页 2 条；资产详情 1 条 70 元。
3. 图片合计 65 → 按 F06 规则提示/补差额；合计大于付款 → 拒绝。
4. 取消选图、取消结果页、AI 失败 → 待记保持 PENDING。
5. 重复确认不重复建单；插件收到 `BILL_CREATED` 或 onResume 对账后标记 RECORDED。

## 4. 已知限制与风险

- **无真机验收**：所有 UI/悬浮窗/WindowManager/系统图片选择器行为需按第 3 节在真机复核。
- **F05**：App 端 `importBills` 的 `missing_category` AI 兜底不会触发插件规则学习；主路径已覆盖。
- **F06**：`ExternalImport.billId` 只记录首条；若首条账单被单独删除、同组其它账单仍在，再次确认可能重复写入剩余账单。
- **F08**：常驻小圆只对 `OverlayService` 承载的 AI 面板生效；Activity 内嵌 AI 面板无小圆入口。进程被杀后 pin 状态不恢复。
- **F09**：本机同步对返回结构做兼容式解析；不同 AutoAccounting 版本若字段差异过大需真机抓包调整。原始 JS 无沙箱超时。
- **F10**：Android 10+ 后台启动 Activity 可能被系统静默拦截；代码已捕获异常返回 `activity_start_blocked`，仍需真机确认。
- 未做云端订阅/激活码、账单页支付组折叠、普通表单常驻小圆、图片逐条编辑、hook 作用域扩展，符合文档“不做清单”。
