# 账单搜索（对标钱迹）

搜索页对照钱迹 `SearchActivity`（包名 `com.mutangtech.qianji`，adb 实机走查）重做：
顶部三下拉（时间 / 账本 / 筛选）+ 关键词框 + 搜索汇总卡 + 日期分组列表。

## 页面结构

`app/src/main/res/layout/activity_bill_search.xml`（白色顶栏，风格对齐资产统计页）：

- 顶栏：返回 + “搜索账单”
- 搜索框：提示词“搜索：分类、备注、账户、金额”，带清除按钮
- 过滤行（白色卡片）：`全部 ▼`｜`账本 ▼`｜`筛选 ▼`，附文字小箭头（`ic_chevron_small`），
  展开时箭头上翻、文字染品牌色；`筛选`在有筛选项时常亮品牌色
- 搜索汇总卡（`MaterialCardView`，16dp 圆角 + 1dp 描边）：
  `搜索汇总 ｜ N笔账单 ｜ 排序钮` + 六宫格
- 结果列表：复用 `HomeAdapter` 的行样式（分类图标行），日期分组头自带收/支小计
- 底部多选操作栏：沿用旧逻辑（删除/移动/不计入统计/全选），作用于当前过滤结果

## 功能

### 关键词搜索

- 匹配范围：备注、分类名（含退款前缀 stripped 后的名字）、账户名、转入账户名、
  账本名、金额（含两位小数写法、原币金额、折人民币金额，`,`/`.` 兼容）
- 输入去抖 200ms 实时过滤；键盘搜索键立即执行并收键盘

### 时间面板（`全部`）

点选即生效，无需确认：

- 分组：按天 / 按月 / 不分组（`HomeAdapter.GroupMode`，见下）
- 账单日期：全部 / 本月 / 上月 / 今年 / 去年
- 自定义：开始日期 - 截止日期（`DatePickerDialog`，选后 preset 变为“自定义”）

### 账本面板

- 选项 = 当前范围内的账本（`scopeBookNames`，从已加载账单按范围去重得到）
- 多选 + 全选 / 确定；全选等价于跟随当前范围（`bookFilter = null`）；
  至少保留一个账本
- 下拉 label：范围名 / 单个账本名 / `N个账本`

### 筛选面板（草稿制，点确定才生效）

- 账单类型（多选丸子）：支出 / 收入 / 转账 / 还款 / 退款。
  退款指 `subType == SUBTYPE_REFUND` 的账单；选支出/收入时自动排除退款，避免重复计数
- 账户（多选弹窗）：`SearchAssetPickerDialog`，复用记账表单的图标宫格样式，
  底部弹出，点选切换，标题显示已选数量，右上清空，底部确认。
  匹配含转入账户（`accountName` / `toAccountName`）
- 分类（多选弹窗）：`OverlayDialogs.showCategoryMultiPicker`，
  复用分类宫格弹窗的视觉（底部弹出、5 列图标宫格、点一级展开二级子面板），
  单选老逻辑（`showGridCategoryPicker`）零改动。多选交互：
  - 有二级的父级：点按只展开/收起，不直接选中；子面板首格「全选」勾选整棵子树
  - 无二级的父级：点按直接勾选自身；二级叶子点按切换
  - 一级名代表整棵子树；底部确认显示数量，提示行点按可清空
- 金额：最小 - 最大，按折人民币绝对值比较
- 重置 / 确定

### 搜索汇总

按当前过滤结果（排序前）实时计算，金额口径均为折人民币：

| 格子 | 口径 |
| --- | --- |
| 总支出 / 总收入 | 复用 `BillStatsContribution.from(bill, cny)` 的 expense / income（与预算统计一致；未关联退款冲减支出） |
| 结余 | 收入 - 支出（可为负，照实显示） |
| 转账/还款 | `TYPE_TRANSFER` / `TYPE_REPAYMENT` 绝对值之和 |
| 退款 | `BillStatsContribution` 的 refund 分量 |
| 不计收支 | `excludeFromStats`（及旧 `SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED`）账单的绝对值之和 |

- 转账/还款、退款、不计收支为 0 时格子直接隐藏（钱迹同款规则）
- 大金额缩写：≥1 万显示 `x.xx万`，≥1 亿显示 `x.xx亿`（去尾零），1 万以下保持原样；
  格子数字 16sp 加粗、单行省略，防三列挤爆

### 分组与排序

- `HomeAdapter.GroupMode`：`DAY`（`MM.dd 周X`）、`MONTH`（`yyyy年MM月`）、`NONE`（无分组头）。
  分组头右侧沿用原有收/支小计（不计入统计的不参与）
- 汇总右上角漏斗钮切换时间升/降序（`HomeAdapter.sortDescending`）；
  升序时图标染品牌色，降序恢复次要色
- 行点击：沿用 `BillDetailSheetHelper` 账单详情 Sheet；改动后回列表自动刷新

### 返回键与键盘

`handleBackPressed` 优先级：关面板 → 退多选 → 收键盘（输入框有焦点时先收键盘）→ 退出。
打开任一下拉面板时自动收键盘，避免挡住面板底部的确定按钮。

## 实现位置

- `ui/main/home/BillSearchActivity.kt`：页面 + 全部过滤状态 + 三个面板 + 汇总计算
- `ui/main/home/HomeAdapter.kt`：新增 `GroupMode`（DAY/MONTH/NONE）+ `sortDescending`，
  `submitList` 的跳过判定同步加入这两个维度；月模式共用 `Header`（`weekdayStr` 为空，绑定时 `trim()`）
- `ui/dialog/SearchAssetPickerDialog.kt`：搜索专用资产多选弹窗
  （复用 `dialog_asset_picker` + `item_asset_grid` 布局，不碰单选宫格逻辑，不带拖拽排序）
- `ui/dialog/OverlayDialogs.kt`：`showCategoryMultiPicker`（多选版分类宫格，
  复用 `dialog_category_picker` + `item_category_grid` + `CategoryRepository.buildCategoryTree` +
  `loadSafeCategoryIcon`；单选版未动）
- 布局：`activity_bill_search.xml`、`panel_bill_search_{time,books,filter}.xml`、
  `item_search_book.xml`
- 资源：`strings.xml`（`search_*` 一组）、`color/search_chip_{bg,text}_selector.xml`、
  `drawable/{bg_search_card,ic_chevron_small}.xml`
- `AndroidManifest.xml`：`BillSearchActivity` 主题必须保持
  `@style/Theme.TapAccounting`（Material 系）。此前用的
  `Theme.TapAccounting.AppCompatNoActionBar` 会导致 `Material Chip` 初始化时
  `IllegalArgumentException` 闪退—— Chips 只能活在 Material 主题下，换主题前先验证。

## 与钱迹的差异（有意为之）

- 提示词无“标签”：`bills` 表没有标签字段，只搜分类/备注/账户/金额（备注里的 `#标签#` 能被关键词命中）
- 汇总无报销/优惠等格子：数据模型没有这些类型，有金额的格子才显示
- 无图片/账单来源筛选：模型里没有对应字段
- 账本下拉默认跟随入口范围（首页点进是当前账本），钱迹默认日常账本——语义一致
- 列表行保留本 App 的分类图标行（比钱迹的彩点信息多），未降级

## 已知限制

- 分类多选弹窗只展示分类表里的树；已删除分类、导入产生的表外分类选不到（关键词仍可搜到）
- 多账本过滤、自定义日期、空结果页只在单账本真机上走过主流程，多账本建议补测
- 汇总金额按折人民币口径，与首页月统计一致；多币种混排时看的是折算值
