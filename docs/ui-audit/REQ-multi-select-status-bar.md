# 功能交接：账单列表多选「已选 N 笔」状态栏（COMFORT-REVIEW 第 14 条）

> 状态：已实现一版（2026-10-10），已装真机验证。本文档用于交给另一个 AI 重做设计。
> 实现者注：当前版本可用但用户对设计不满意（副行文案会跳变、视觉可再优化），重设计时请先读「已知问题」与「设计约束」。

---

## 0. 功能背景（问题出处）

`docs/ui-audit/COMFORT-REVIEW.md` 第 14 条（原文）：

> 长按账单进入多选：每行左侧出现复选框，底部浮出「退出多选 / 全选 / 移动 / 删除(1)」操作栏。
> 不舒服的点：
> 1. 顶部月度统计（本月总支出 ¥1,420.19）原样不动——没有任何标题栏变成"已选 1 笔"的状态条，容易意识不到已进入多选；
> 2. 已选数量唯一的展示是底部「删除(1)」括号里的数字，视线在列表时感知不到；
> 3. 多选模式下点账单行 = 切换选中（不是看详情），第一次用会习惯性点进去看详情，反而选中了一条，也没有说明。

改前截图：`docs/ui-audit/screenshots/n09_multiselect.png`。

---

## 1. 当前实现（行为规格）

### 1.1 进入 / 退出
- **进入**：长按任意可选中账单行（`HomeAdapter` itemView long-click，行文件见 §2.7）。
- **进入后**：
  - 列表行左侧出现复选框（账单行 + 日期分组头都是复选框）；
  - 底部浮出操作栏 `layout_multi_select_actions`：`× / 全选 / 移动 / 不计入 / 删除(N)`；
  - **顶部 banner（246dp 的 `headerBannerLayout`）被一块不透明面板 `layoutMultiSelectSummary` 整体覆盖**，显示：
    - 主标题：`已选 N 笔`（30sp 加粗白字）
    - 副行：金额合计或提示文案（逻辑见 1.3）
  - banner 的原始月度统计 `layoutHeaderSummary` 被隐藏（`View.GONE`）；
  - banner **固定不折叠**：进入时把 `headerBannerLayout` 的 `AppBarLayout.LayoutParams.scrollFlags` 置 0（退出时恢复 `scroll|exitUntilCollapsed`），首次固定时 `homeAppBar.setExpanded(true, true)`，保证列表滚动时"已选 N 笔"始终可见。
- **退出**：底部栏左侧的 × 按钮（`btn_ms_cancel`）、系统返回键、切 Tab、onPause（打开其它页面）都会清空选择退出多选；退出后隐藏面板、恢复 banner 统计显示与滚动折叠行为。
- **点按行为不变**（保持行业标准）：多选内点账单行 = 增选/取消；长按只在未进入多选时用于进入。已有"点按=选中"的肌肉记忆问题（原第 3 条痛点）靠状态面板的计数实时+1 来暗示，未做任何文案教学。

### 1.2 触发更新的唯一入口
`HomeAdapter.onSelectionChanged: ((Int) -> Unit)?`（count = 已选笔数）。触发点包括：长按进入、点按切换、日期分组头勾选、全选/取消全选/清空、× 退出。

### 1.3 副行文案逻辑（当前用户最不满意的点）
文件：`logic/BillSelectionSummary.kt`

- `totals(bills)` 返回 null 的条件（任一满足）：
  1. 选择为空；
  2. 多个币种（`bill.currency` 去重后 > 1）；
  3. 含除 支出(TYPE_EXPENSE=0)/收入(TYPE_INCOME=1) 外的类型（即含 转账 TYPE_TRANSFER=2 或 还款 TYPE_REPAYMENT=3）。
- 可合计时副行 = `支出 ¥X · 收入 ¥Y` 拼接（只列出 >0 的项，符号用 `CurrencyManager.getSymbol` + `AmountFormatHelper.formatCurrency`）。
- 不可合计或两数都为 0 时副行 = `点按账单可增选或取消`。

**这导致**：选得越多（尤其点「全选」），选中集合一旦包含转账/还款或多币种，副行文案就突然从金额变成操作提示，用户感觉像 bug——这正是要重设计的核心。

### 1.4 视觉规格（当前值，供参考、可推翻）
- 面板 `layoutMultiSelectSummary`：match_parent（宽高均随 246dp 的 banner），**不透明**，背景 `@drawable/bg_home_header_gradient`（默认 banner 蓝渐变 `#5F8DFF → #4B72F0 → #3658D9`，angle 305），无圆角；
- 内边距：start/end 24dp，top 82dp（与 banner 统计对齐），bottom 18dp；
- 主标题：30sp、bold、`#FFFFFF`、`sans-serif-medium`、`includeFontPadding=false`、marginTop 12dp；
- 副行：15sp、`#CCFFFFFF`、marginTop 6dp（无 maxLines/ellipsize，设计时可考虑）；
- 顶部固定栏 `layoutStickyTopBar`（56dp，悬浮在 banner 之上：账本/月份/日历/搜索，白色图标）保持可见、叠在面板上方；
- 底部操作栏 72dp 白底（不透明），5 个按钮见 §2.7。

### 1.5 迭代历史（避免重蹈）
1. 初版用 60% 半透明黑遮罩（`#99000000`）→ 实测原 banner 文字透出来和「已选 N 笔」叠字，用户评价"好丑"；
2. 改为**完全不透明全覆盖**（复用 banner 默认渐变），用户接受"全覆盖"方向，但对副行文案跳变不满，遂交出新 AI 设计。

---

## 2. 涉及文件与代码行（当前工作区）

### 2.1 `app/src/main/res/layout/fragment_home.xml`
关键行：
- `33`：`headerBannerLayout`（FrameLayout，高 246dp / minHeight 88dp）
- `38`：`app:layout_scrollFlags="scroll|exitUntilCollapsed"`（折叠行为的来源，多选时被代码改为 0）
- `63`：`layoutHeaderSummary`（banner 原月度统计，多选时被 GONE）
- `249`：`layoutHeaderTopBar`（banner 内一个 `visibility="invisible"` 的历史展示层，实际顶部控件是 sticky bar）
- `282-316`：新增的选择面板（本次功能主体）：

```xml
<!-- 多选状态面板：进入多选时覆盖 banner，突出"已选 N 笔"（见 COMFORT-REVIEW 第 14 条） -->
<LinearLayout
    android:id="@+id/layoutMultiSelectSummary"      <!-- 284 -->
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/bg_home_header_gradient"
    android:orientation="vertical"
    android:paddingStart="24dp"  android:paddingTop="82dp"
    android:paddingEnd="24dp"    android:paddingBottom="18dp"
    android:visibility="gone"    tools:visibility="visible">
    <TextView android:id="@+id/tvMultiSelectSummaryTitle"   <!-- 297 -->
        android:textSize="30sp" android:textStyle="bold" android:textColor="#FFFFFF" ... />
    <TextView android:id="@+id/tvMultiSelectSummaryDetail"  <!-- 309 -->
        android:textSize="15sp" android:textColor="#CCFFFFFF" ... />
</LinearLayout>
```

- `337`：`layoutStickyTopBar`（56dp 固定顶栏，悬浮于 banner 上方，不随折叠消失）
- `422-490`：`layout_multi_select_actions` 底部操作栏（72dp 白底，`layout_gravity="bottom"`，`visibility="gone"`），按钮 id：`btn_ms_cancel`(48dp 圆 ×)、`btn_ms_select_all`、`btn_ms_move_book`、`btn_ms_exclude_stats`、`btn_ms_delete`

### 2.2 `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeUiListController.kt`
- `27-30`：新增构造参数
```kotlin
private val multiSelectSummaryLayout: View,
private val tvMultiSelectSummaryTitle: TextView,
private val tvMultiSelectSummaryDetail: TextView,
private val setBannerPinnedForMultiSelect: (Boolean) -> Unit,
```
- `85-105`：`setupRecyclerView()` 内的显隐接线
```kotlin
homeAdapter.onSelectionChanged = { count ->
    if (homeAdapter.isMultiSelectMode) {
        setIsMultiSelectModeActive(true)
        layoutMultiSelectActions.visibility = View.VISIBLE
        layoutHeaderSummary.visibility = View.GONE            // 90
        updateMultiSelectSummary(count, homeAdapter.selectedBills.toList())  // 91
        setBannerPinnedForMultiSelect(true)                   // 92
        (btnMsDelete as TextView).text = if (count > 0) "删除($count)" else "删除"
        BillStatsMultiSelect.refreshButton(btnMsExcludeStats as TextView, ...)
    } else {
        setIsMultiSelectModeActive(false)
        layoutMultiSelectActions.visibility = View.GONE
        multiSelectSummaryLayout.visibility = View.GONE       // 101
        layoutHeaderSummary.visibility = View.VISIBLE         // 102
        setBannerPinnedForMultiSelect(false)                  // 103
    }
    fabScrollBehavior.syncVisible()
}
```
- `135-159`：`updateMultiSelectSummary(count, bills)`（主/副标题文案组装，含 §1.3 的"合计 or 提示"分支）

### 2.3 `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeFragment.kt`
- `214-216`：字段 `layoutMultiSelectSummary / tvMultiSelectSummaryTitle / tvMultiSelectSummaryDetail`
- `375-377`：`findViewById` 绑定
- `456-459`：传给 `HomeUiListController` 的构造参数（含 `setBannerPinnedForMultiSelect = { setHomeBannerPinnedForMultiSelect(it) }`）
- `1064-1083`：banner 固定/恢复
```kotlin
private fun setHomeBannerPinnedForMultiSelect(pinned: Boolean) {
    val appBar = homeAppBar ?: return
    val lp = headerBannerLayout.layoutParams as? AppBarLayout.LayoutParams ?: return
    val flags = if (pinned) 0 else (SCROLL_FLAG_SCROLL or SCROLL_FLAG_EXIT_UNTIL_COLLAPSED)
    if (lp.scrollFlags != flags) {
        lp.scrollFlags = flags
        headerBannerLayout.layoutParams = lp
        if (pinned) appBar.setExpanded(true, true)
    }
}
```
- 折叠联动（既有机制，勿破坏）：`421-444` AppBar offset 监听会改 `layoutHeaderSummary.alpha`、给 `headerBannerLayout.foreground` 叠白色渐显罩、切状态栏图标颜色；`layoutStickyTopBar` 的顶栏颜色同步函数在 `syncBannerTopBarTextColorByWhiteOverlay`。
- 退出清空选择的三个既有入口：`275`（返回键）、`708`（onPause）、`765`（切 Tab）。

### 2.4 `app/src/main/java/com/taostudio/tapaccounting/logic/BillSelectionSummary.kt`（新增，全文 30 行）
- `13-17`：`data class Totals(expense, income, currency)`
- `19-29`：`fun totals(bills): Totals?`——null 条件见 §1.3

### 2.5 `app/src/main/res/values/strings.xml`
- `333`：`multi_select_summary_title_fmt` = `已选 %1$d 笔`
- `334`：`multi_select_summary_hint` = `点按账单可增选或取消`
- `335`：`multi_select_summary_expense_fmt` = `支出 %1$s`
- `336`：`multi_select_summary_income_fmt` = `收入 %1$s`

### 2.6 测试
`app/src/test/java/com/taostudio/tapaccounting/BillSelectionSummaryTest.kt`（7 例：空选择/纯支出/收支混合/退款计收入/多币种/含转账还款/空币种兜底）。
运行：`gradlew :app:testDebugUnitTest`

### 2.7 相关既有机制（本次未改，但重设计必须了解）
- `ui/main/home/HomeAdapter.kt`：
  - `112` `isMultiSelectMode`；`113` `selectedBills: MutableSet<Bill>`；`116` `onSelectionChanged`
  - `284` `toggleMultiSelectMode()`；`293` `clearSelection()`；`300` `deselectAll()`；`307` `selectAll()`（含 `isOwnedByLocal` 过滤：共享账本里别人的账单不可选）
  - `333-357` 日期分组头的勾选逻辑；`577-599` 行点按切换 + 长按进入
  - `141` `isOwnedByLocal(bill)`（共享账本成员归属判断）
- `ui/main/home/HomeMultiSelectController.kt`：底部栏动作实现——`39` ×（清选择）、`42` 全选/取消全选、`51-54` 移动（`showMoveToBookDialog` 107）、`56-69` 不计入统计（连续处理不清选择）、`70-94` 删除确认（`BillDeleteHelper.deleteBillsAndRevertBalance`，删后 `clearSelection` + toast）
- `MainActivity.kt`：FAB 在多选期间隐藏（`HomeFragment.updateHomeFabVisibilityByDrawerState` 判断 `getIsMultiSelectModeActive()`）
- 其他页面的多选栏（**未动、如要统一需另做**）：`ui/main/home/BillSearchActivity.kt:135-136`、`ui/main/home/CalendarActivity.kt:460-471`

---

## 3. 数据 / 工具速查
- `data/local/entity/Bill.kt`：`type`（`TYPE_EXPENSE=0 / TYPE_INCOME=1 / TYPE_TRANSFER=2 / TYPE_REPAYMENT=3`）、`subType`（`SUBTYPE_REFUND=2` 等）、`amount`（正数，符号由 type 决定）、`currency`（默认 CNY）、`time`
- 金额格式化：`AmountFormatHelper.formatCurrency(symbol, amount)`；币种符号：`CurrencyManager.getSymbol(code)`
- 列表格式化辅助：`ui/main/home/HomeBillFormatHelper.kt`（`formatMoney` 等）

## 4. 已知问题（重设计重点）
1. **副行文案跳变**（用户直接反馈）：能合计 → 显示金额；选中含转账/还款/多币种 → 变操作提示。全选场景必触发，像 bug。
2. 面板**完全覆盖**自定义 banner 颜色/图片：对自定义配色用户是"色变"；对默认蓝用户几乎无缝。
3. 数量与金额的层级、与底部栏信息重复、退出入口摆放等均可重新设计。

## 5. 设计约束（项目约定，务必遵守）
- 用户硬性偏好（`docs/ui-audit/HANDOFF.md`）：**保留多彩风格**；**不动图标**（不替换/重绘 PNG、矢量、emoji，只允许改颜色）；**一次改一处**；改完装真机截图给用户确认后再 commit。
- 颜色语义：支出 `expense_color #FF5252`、收入 `income_color #4CAF50`、品牌/可点操作 `brand_primary #5C6BC0`（深阶 `#3F51B5`）、危险红；**选中态全 App 统一用品牌色**。
- banner 是用户可自定义的（16 色板 + 背景图片 + 文字对比度逻辑），顶部 sticky bar 图标颜色会随折叠/底色同步，重设计面板背景时请注意兼容。
- 设备与验收：真机 PKX110（1216×2640，Android），`adb install -r app/build/outputs/apk/debug/app-debug.apk`；构建 `gradlew :app:assembleDebug`；可用 `uiautomator dump` 取控件坐标做自动化截图验证。
