# 真机测试问题分析与交接（2026-11-05）

> 本文只做**分析、取证与交接**，没有改任何代码。
> 对应分支 `test-daiji` 上 10 项未提交的 F01–F10 修复，以及你在真机上的实测反馈。
> 阅读顺序建议：第 0 节（环境/证据）→ 第 1 节（逐条结论）→ 第 2 节（修复优先级）→ 第 4 节（下次怎么取证）。

---

## 0. 测试环境与证据

| 项 | 值 |
|---|---|
| 设备 | PKX110（ColorOS/HeyTap，Android 17） |
| 主应用 | `com.taostudio.tapaccounting` 1.2，最后更新 2026-11-05 12:04 |
| 插件 | `com.taostudio.tapaccounting.capture` 0.1，最后更新 2026-11-05 12:53 |
| 安装包一致性 | 从设备拉回的插件 APK SHA256 = 本机构建 `capture-plugin-debug.apk`（`92912E51…C5E97`），**设备上跑的就是含最新改动的包** |
| 构建/单测 | `:app:assembleDebug`、`:capture-plugin:assembleDebug` 成功；插件单测 16/16 通过（`DataFilterTest` 4、`RuleLearningPolicyTest` 12） |

### 已抓到的系统日志（logcat，11:16–13:39）

```text
11-05 13:29:10.670 I/am_kill: [0,22657,com.taostudio.tapaccounting.capture,200,
    stop com.taostudio.tapaccounting.capture due to o-stop(40),209360]
```

- 插件进程在 **13:29:10** 被系统/用户停掉（`o-stop`），这正是你反馈“关闭插件后”的时间点。
- 停掉后，插件的 `CaptureIngestProvider` 进入 stopped 状态，主应用再读待记就会走 `Failed to find provider info` 的空列表回退（ISSUE-06 的原始现象）。
- 13:38 之后插件进程（PID 25302）又被拉起来了，`CaptureKeepAliveService` 处于前台服务状态；主应用 `OverlayService` 也活着（PID 6644，FGS）。

### 主应用保活心跳（`/sdcard/Android/data/com.taostudio.tapaccounting/files/keepalive_heartbeat.txt`）

```text
2026-11-05 13:04:00 START gap=62s deaths=787
...
2026-11-05 13:27:00 ALIVE
2026-11-05 13:29:31 ALIVE      ← 中间断了约 2.5 分钟
```

- 主应用进程也在反复重启（历史 `deaths=787`，13:04 刚有一次 62 秒空档），说明“谁该常驻”在这台机器上本身就不稳；这会影响 ISSUE-06/08 的方案选择。

### 日志现状（下次测试前必须处理）

- logcat 里 **11:16–13:39 完全没有 `TapCapture*` / `CaptureBridge` / `OverlayManager` 日志**。
- 主应用文件日志 `app_logs.txt` 最后写入是 **2026-10-19**，说明“日志开关”没开，`Logger` 只往 logcat 写 WARN 及以上。
- 下次复测前先在主应用设置里**打开日志记录**（并确认 `app_logs.txt` 有当天时间戳），否则插件侧的拆分、桥接、分类过程都没有证据。

---

## 1. 你这次反馈的 6 个问题（逐条）

### 1.1 「清除分类（重置为待选）」找不到

**代码位置**：`capture-plugin/.../ui/PendingListActivity.kt:1486-1501`（按钮生成）、`:884`（拆分时隐藏分类 chip）、`:901-921` / `:973-976`（备注/分类只更新局部变量）。

这个按钮不是“永远存在”，而是有 4 个前提，任意一个不满足都会看不到：

1. **只有卡片自己已经有分类时才生成**（`:1487` 的 `item.categoryName` 非空）。你如果是点“待选分类 ▾”进的弹窗，本来就没有“可清除”的分类，按钮按设计不出现。
2. **勾选“拆分记账”后，整笔的分类 chip 被隐藏**（`:884`），所以拆分卡上根本没有入口打开这个弹窗；只能从子明细的“选分类”进去。
3. **快照过期**：`item` 是建卡时的快照。备注防抖触发本地规则（`:915-918`）或 AI 归类（`:973-976`）后，代码只更新了 `currentCategory` 和 chip 文案，没有更新 `item`；此时再点 chip，弹窗拿到的 `item.categoryName` 还是 `null`，于是没有“清除”按钮。**这是最可能的现场原因**（你是先写备注、让规则/AI 归了类，再去找清除）。
4. **备注重算会清空分类**：`CaptureRepository.updateNoteAndReclassify`（`:140-167`）在“非手动分类 + 备注不命中任何规则”时会把 `categoryName/categorySource` 置空。所以有时分类会自己消失，看起来也像“没有清除入口”。

此外还有一个独立 bug：**子明细的“清除”无效**。子明细弹窗走 `onResult` 分支，但“清除分类”回调仍执行 `repo.clearCategory(item.id)`（清整笔）并 `finish("")`；子明细的 `onResult` 收到空串后直接忽略（`if (!chosenCat.isNullOrBlank())`），所以点了等于没点。

**建议**（按成本从低到高）：

- 把 `currentCategory` 显式传给 `showCategoryPickerDialog`，不要依赖 `item` 快照；
- 让“清除分类”始终显示（没分类时可置灰），或干脆在列表顶部加一行“✕ 清除分类”；
- 子明细的清除改为清 `splitSubItems[index].categoryName` 并 `renderSubItems()`，不要动整笔；
- 备注重算导致分类被清空时给一个可见提示（Toast/黄条），避免“分类莫名消失”。

---

### 1.2 是不是“停止输入就直接请求 AI 归类”？

**不是。** 现在的链路是：

| 触发 | 行为 | 是否走网络/AI |
|---|---|---|
| 备注输入中 | 每次输入取消上一个任务，**280ms** 后 `updateNoteAndReclassify` | ❌ 只做本地规则匹配 + 落库（`PendingListActivity.kt:901-921`） |
| 备注框失焦 / 键盘“完成” | `triggerAiClassify()`（`:980-997`） | ✅ 仅当“备注非空 + 当前无分类”才调 AI（`:959-977`） |
| 已有分类时失焦 | 只查本地规则，命中且分类不同 → 弹“是否替换”（`:926-957`） | ❌ 不调 AI |
| 点“智能归类 (N)”批量按钮 | `runBatchAiClassify`（`:1510+`） | ✅ 显式用户动作 |
| 主应用悬浮窗里改备注 | 280ms 后 `updateCaptureDraft` → 插件 `updateDraft`（本地规则） | ❌ 不调 AI |
| 悬浮窗的“智能归类” | `runBatchAiClassification`（`OverlayPendingCaptureController.kt:813+`） | ✅ 显式用户动作 |

补充两点容易踩坑的语义：

- `updateNoteAndReclassify` 对**手动分类**（`categorySource == MANUAL`）是锁定不覆盖的（`:144-151`）；
- 但对非手动分类，备注改动后**如果没有命中任何规则，会把分类清空**（`:158-163`）。这解释了“写了备注分类反而没了”的现象。

如果你在真机上看到“停手就请求 AI”，大概率是**焦点变了**（点了别处、键盘收起），不是防抖逻辑在请求。

---

### 1.3 勾选“拆分记账”后有一行空白

**代码位置**：`PendingListActivity.kt:1040`（`renderSubItems()`）、`:1137`（空状态文案）、`:1322`（勾选监听）。

- 空白行就是 `splitSummaryText`（`:1030-1035`）。它的文案只在 `renderSubItems()` 里赋值（`:1136-1144`）。
- 勾选监听（`:1322-1341`）只做了 4 件事：记状态、隐藏分类 chip、隐藏“悬浮窗细改”、显示工作区；**没有调用 `renderSubItems()`**。
- 首次进入时如果 `item.splitRequested == true`，工作区直接可见（`:1014`），也没有调用 `renderSubItems()`。
- 所以初始状态 `splitSummaryText` 是空串 = 恰好一行空白；只有点过“加一行/删除/拆分”之后才会出现“尚未拆分明细…”文案。

**建议**：在勾选监听里补一次 `renderSubItems()`；初始 `splitRequested == true` 时也调用一次；或者把空状态做成占位布局（虚线框 + 引导文案），而不是 0 高度 TextView。

---

### 1.4 点“📝 按备注 AI 拆单”没有进度/动画

**代码位置**：`PendingListActivity.kt:1191`（`toast("正在按备注拆分…")`）、`:1195-1210`（`bridge.previewSplit`）、`:1040+`（结果一次性重建所有行）。

现状：

1. 只有一个短 Toast，之后到 AI 返回（通常 2–10 秒）之间**界面完全没变化**；
2. 按钮不禁用，可以连点，会并发发起多次请求；
3. 结果回来时 `subItemsContainer` 一次性 `removeAllViews()` 再全量添加，没有逐条出现的效果；
4. 失败时走 `showSplitError`，成功/失败都没有行内的“进行中/已完成”状态。

对照主应用的多账单拆单（`OverlayManager.kt:1142-1169`）：它是用 `postDelayed(350ms)` **逐笔**把结果填进表单，视觉上有“一条一条出现”的节奏。插件这边完全可以复用这个思路：

**建议**：

- 点击后：禁用三个拆分 chip，把按钮文案改成“拆分中…”，在工作区顶部插入一行带 `ProgressBar` 的提示；
- 结果回来后：先保留旧行，逐行 `alpha 0→1 + translationY 8dp→0`，每行间隔 60–80ms；金额合计随之滚动更新；
- 失败时把错误显示在操作行下方（红色小字），不要只弹 Toast；
- 如果希望“AI 边拆边出”，需要把 `previewSplit` 从一次性 JSON 改成流式/分段返回，这是后端接口改动，不建议这轮做。

---

### 1.5 模拟支出金额固定 3.00，没法测小票金额

**代码位置**：`PendingListActivity.kt:2088-2128`（`simulateCapture()`）。

- `amountCents = 300L` 硬编码（`:2098`），`merchant = "示例支出"`，没有任何输入入口；
- “模拟 WCDB 真实事件”也是固定 `¥9.00`（`:2131-2150`）；
- 待记卡片的金额是只读 `TextView`（`:825-830`），生成后也改不了。

所以你无法“先造一笔任意金额的待记，再点识图拆单”。这轮测试图片记账只有两个办法：

1. 真实付一笔（最可靠，但不可控）；
2. 给测试工具加金额入口（建议做）。

**建议**：把“+ 模拟一笔示例支出 (¥3.00)”改成弹一个极简输入框（金额 + 商户名，默认 3.00/示例支出），或长按该按钮弹出金额输入；同时保留固定 3.00 的快捷入口。这样图片记账、拆分差额、分批合并都能用任意金额复现。若暂时不改代码，测试小票只能走真实支付。

---

### 1.6 拆分的提示词是不是这次新加的？

**不是这轮新加的。**

- `AIPrompts.kt:264-269` 的“你是记账拆分助手…”来自 **`c4ed69f feat(F06): 文字拆分记账与支付组合并`**（2026-11-05，F06 阶段）；
- `c23697c feat(F10): 图片记账与拆单合并` 只是复用了它，并补了图片/合并入账链路；
- 这次工作区未提交的改动集中在**拆单 UI 重做**（`PendingListActivity`）、合并入账拦截、图片入口直连，没有改提示词本身。

所以如果你觉得拆分质量和以前不一样，先确认是不是备注格式/金额校验（`SplitAmounts.kt`）在起作用，而不是提示词变了。

---

### 1.7 应用内 AI 面板点“缩小”后小圆立即消失

**这是一条确定的 bug，链路如下**：

1. `AiAssistant.collapseToBubble()`（`AiAssistant.kt:228-241`）：显示小圆（`showMiniBubble()` 成功）→ 回调 `onPanelCollapsed(true)`。
2. 应用内入口在 `AddBillEntrySheetLauncher.kt:31-33`：`onPanelCollapsed = { bottomSheet.dismiss() }`。
3. `BottomSheetDialog` 的 `onDismiss`（`:91-96`）里调用了 `aiAssistant.shutdown()`。
4. `shutdown()`（`AiAssistant.kt:61-70`）：`removeMiniBubble()` + `currentDialog.dismiss()` + `scope.cancel()`。

结果：小圆刚加进 WindowManager 就被移除，面板 Dialog 也被销毁——表现就是“点进小圆，直接关闭”。

对比主应用悬浮窗入口（`OverlayManager.kt:117-132`）：它只是恢复普通表单/隐藏面板，不会 `dismiss` 宿主，所以那条路径正常。

**为什么不能只删掉 `shutdown()`**：这个 `AiAssistant` 的 Dialog 是用 `Activity` 作为 Context 创建的（`createDialog()` 里 `ContextThemeWrapper(ctx, ...)`），`bottomSheet.dismiss()` 之后 Activity 可能很快 finish，隐藏着的 Dialog 会挂在已销毁的窗口上（泄漏/`BadTokenException`）。所以有两种正确方向：

- **方案 A（最小改动）**：收起时不要 `dismiss()` 底部弹层，改成 `bottomSheet.hide()`（或让弹层保持 show 但把内容隐藏），小圆和 Dialog 都留在同一个宿主里；只有用户真正关闭时才 `shutdown()`。
- **方案 B（推荐的长期方案）**：把“小圆 + AI 面板”的宿主从页面级 `AiAssistant` 提升到应用级（像 `OverlayManager` 那样由 `OverlayService`/单例持有，用 `applicationContext` + `WindowManager`），页面只负责“唤起/收起”。这样三个入口（悬浮窗、应用内、小圆）行为一致，也不会被 Activity 生命周期带走。

如果只做 A，要注意 `aside` 弹层在小圆展开时如何恢复（`bottomSheet.show()` + 恢复 `AiAssistant` 的 dialog），否则会出现“小圆点了但面板打不开”。

---

### 1.8 关闭插件后：弹不出悬浮窗，弹出来也不显示待记

**先分清两个“悬浮窗”**，它们属于不同进程：

| 悬浮窗 | 绘制者 | 关闭插件后的结果 |
|---|---|---|
| 付款后秒记气泡（`CaptureBubbleManager.showBubble`） | **插件进程**（`CaptureIngestHandler.kt:129`） | 插件进程被停/冻结 → 不弹 |
| 主应用背部敲击/双击悬浮窗（`OverlayManager`） | 主应用进程（`OverlayService`，FGS） | 窗口能弹，但顶部“待记细条”要读插件 → 空 |

抓到的 `13:29:10 am_kill … capture … o-stop` 正好对应第一种；第二种的“细条为空”来自：

- `OverlayPendingCaptureController.loadIfNeeded()`（`:100-112`）
- → `CapturePluginClient.listPendingCaptures()`（`CapturePluginClient.kt:69-80`）
- → 插件 `CaptureIngestProvider`。插件处于 stopped 状态时返回空列表，控制器直接 `clearPendingContext()`，于是“弹出来了但没有待记”。

另外，这轮加的重试唤醒（`CapturePluginClient.kt:254-268`）**目前不会生效**：

- 它发的是 `ACTION_WAKE_INGEST` 广播（`CaptureContract.kt:45`）；
- 但 `capture-plugin/src/main/AndroidManifest.xml` 里没有这个 action 的 receiver，代码里也没有动态注册（全仓库只有 3 处引用：常量、插件自己的 `RelayServer` 用来 `startActivity(CaptureWakeActivity)`、以及主应用这次新增的 `sendBroadcast`）。
- 所以“读不到 → 发广播 → 睡 60ms → 再读一次”只是原地重试，叫不醒 stopped/冻结的插件。

#### “把待记数据库写到主应用里”能不能解决？

**能解决“读不到待记/待记细条为空”这一类问题，但只搬数据库不够，气泡也要一起搬。** 结论分三层：

1. **主应用持有待记 DB** → 悬浮窗细条、批量入账、图片记账、规则/学习全部本地读，不再依赖插件 ContentProvider，`Failed to find provider info` 这一类问题消失。
2. **付款后的秒记气泡必须由主应用绘制**（或主应用委托的常驻进程）→ 否则“关闭插件不弹窗”仍然存在。主应用已经有 `OverlayService` + 悬浮窗权限 + 前台服务，具备承载条件。
3. **插件仍要保留“投递兜底”**：hook 在微信/支付宝进程里，解析完只能跨进程送给主应用；主应用没起来时要能排队重试（现在 relay 已有队列和 52047 直连，可复用）。插件不再需要“被常驻”，只需要“被唤醒时送一次”。

也就是说，和你文档第三节的收敛方案一致：**数据、规则、气泡、收件箱 UI 全部收到主应用；插件只做“解析 + 投递 + 本地 outbox”**。

#### 如果暂不做大重构，先做哪些“止血”？

按性价比排序：

1. **补上唤醒接收方**（P0，半天）：在插件 manifest 声明一个 `WAKE_INGEST` 的 receiver（`exported=true` + 只做“启动 `CaptureKeepAliveService`/拉起 `CaptureWakeActivity`”这类无敏感数据的动作），或者主应用改为 `startActivity(CaptureWakeActivity)`（插件已 `exported=true`，但需要插件侧的 token，注意不要泄露）。这样“插件被系统停掉后”至少能被拉起来。
2. **主应用缓存最近一次待记快照**（P1）：成功读到后落一份本地缓存；读失败时显示缓存 + “插件未运行，点此打开插件”，不要直接清空细条。
3. **插件被 o-stop 后的恢复入口**（P1）：`PendingListActivity` 是 `exported=true`，主应用在检测到 provider 不可用时可以引导用户点一下打开插件（前台操作，不受 BAL 限制）。
4. **插件自身 FGS 已经存在**（`CaptureKeepAliveService`），但在 ColorOS 上仍会被 `o-stop`；不要指望“保活”能彻底解决，最终还是要收敛到主应用。

#### 迁移到主应用的落地步骤（供后续排期）

1. 新建 `:capture-core` 模块，把 `PaymentCapture`、`CaptureRepository`、`CaptureRuleMatcher`、`RuleLearningPolicy`、`RuleBundleStore` 抽出来，插件和主应用共同依赖；
2. 主应用新增 `pending_captures` 表（Room migration），`CaptureBridgeProvider` 的 `INGEST` 方法改为写本地库；
3. 主应用 `OverlayService` 增加“秒记气泡”视图（复用 `CaptureBubbleManager` 的布局与手势），数据源改为本地库；
4. 插件 `CaptureIngestHandler` 只保留“解析 → outbox → 投递”；投递失败进入本地队列，主应用下次启动/被唤醒时补投；
5. 插件的收件箱页面降级为“只读查看/调试”，或直接移除，入口收敛到主应用首页胶囊；
6. 规则学习、分类规则、资产映射全部以主应用为准；插件只保留“数据规则（hook 报文解析）”的同步包；
7. 回归重点：无 Root 用户不显示任何入口；支付瞬间气泡 3 秒内出现；插件被划掉后主应用仍能看待记。

预估：**3–5 个工作日**（不含真机反复调 ColorOS 保活）。收益：ISSUE-06、ISSUE-08、图片/拆分跨进程的整类问题一起消失。

---

## 2. 修复优先级

| 优先级 | 问题 | 建议动作 | 预估 |
|---|---|---|---|
| P0 | 1.7 应用内小圆立即关闭 | 先 `hide()` 不 `dismiss()`；长期按方案 B 提升宿主 | 0.5–1d |
| P0 | 1.8 唤醒无接收方 | 补 receiver / 改 `startActivity(CaptureWakeActivity)` | 0.5d |
| P1 | 1.3 拆分空白行 | 勾选/初始调用 `renderSubItems()` | 10min |
| P1 | 1.4 拆单无进度 | loading 态 + 禁用连点 + 逐行入场动画 | 0.5d |
| P1 | 1.1 清除分类入口 | 传 `currentCategory`、常显按钮、修子明细清除 | 0.5d |
| P1 | 1.5 模拟金额 | 弹窗输入金额/商户 | 0.5d |
| P2 | 1.2 备注重算清空分类 | 加提示或保留原分类待确认 | 0.5d |
| P2 | 1.8 数据收敛 | 按上节步骤迁移 | 3–5d |

---

## 3. 复测清单（改完后按这个顺序走）

1. 冷启动 → 打开插件 → 主页面副标题、规则页顶部/底部、数据规则样本测试；
2. 模拟一笔（改成任意金额后）→ 不勾拆分：选分类 → 再点分类 chip → 应看到“清除分类”，点了回到“待选分类”；
3. 写备注“苹果”命中的规则 → 确认“发现匹配分类规则”弹窗；保留/替换都要生效；
4. 勾选拆分 → 立即看到“尚未拆分明细…”占位；点 AI 拆单 → 有进行中状态；结果逐条出现；改一行金额 → 合计/差额实时更新；超付拦截、不足补齐、相等入账；
5. 识图拆单 → 系统相册立即弹出 → 选图 → 识图确认；取消后待记保留；
6. 应用内新增账单 → AI 面板 → 缩小 → 小圆出现且**不消失** → 点小圆能展开；
7. 支付一笔（或模拟）→ 秒记气泡；关掉插件进程 → 再支付/双击 → 气泡与待记细条仍应正常（这一步是收敛方案的核心验收）；
8. 普通微信聊天/公众号推文不产生待记；真实支付仍产生待记。

---

## 4. 下次怎么取证（这次缺日志）

1. 主应用设置里打开“日志记录”，确认 `/sdcard/Android/data/com.taostudio.tapaccounting/files/app_logs.txt` 有当天时间；
2. 复现前 `adb logcat -c`，复现后：
   ```powershell
   adb logcat -d -v time > capture.log
   ```
3. 同时保存：
   ```powershell
   adb shell "tail -n 200 /sdcard/Android/data/com.taostudio.tapaccounting/files/keepalive_heartbeat.txt"
   adb shell dumpsys activity processes | findstr /i "taostudio"
   ```
4. 关注关键字：`TapCapture*`、`CaptureBridge`、`OverlayManager`、`TapRuleLearning`、`o-stop`、`am_kill`、`Failed to find provider`。

---

## 5. 还需要你确认的点

1. “关闭插件”具体是**从最近任务划掉**、**设置里强行停止**，还是**系统自己冻结**？（这次日志是 `o-stop`，更像划掉/停止）
2. 你找“清除分类”时，卡片是**勾了拆分**还是普通卡？分类是**手动选的**还是**写备注后自动归的**？
3. 秒记气泡是否要继续保留“插件自己的气泡”外观？如果收敛到主应用，视觉上要不要和现在一致？
4. 图片记账测试是否接受“先加一个模拟金额输入框”，还是必须走真实支付？

---

## 6. 附：本轮未提交改动的范围（供 review 时对照）

- 主应用：`AiAssistant`（小圆拖拽关闭、Activity 上下文）、`AddBillEntrySheetLauncher`（收起联动）、`ImagePickerActivity`/`CaptureImageAccountingActivity`/`AndroidManifest`（PhotoPicker 直连与 Key 后置）、`CapturePluginClient`（重试唤醒）、`CaptureBridgeProvider`（去掉前置 Key 校验）、`bg_ai_mini_bubble`/`ic_ai_minimize`（视觉）；
- 插件：`PendingListActivity`（拆单工作区、备注规则提示、清除分类、前台直启相册）、`RuleMappingViews`（一键转换/迁移卡片）、`DataRuleViews`（样本测试报文、去顶部同步）、`RuleLearningPolicy`/`RuleLearningManager`（手动分类也学习）、`CaptureRepository`/`CategoryRule`（clearCategory、convertAllMerchantRules）、`hook/*` + 新增 `DataFilter.kt`（源头白名单）。
