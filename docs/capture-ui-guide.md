# 敲敲待记 UI 修改指南

> 版本：1.0  
> 适用范围：`:app` 模块待记相关页面（`com.taostudio.tapaccounting.capture.ui.*`）及 `:capture-plugin` 状态页。  
> 核心原则：**规范统一、渐进改造、严格防退化、视觉与业务解耦**。

---

## 1. 改动边界

- **只改视图表现层**：仅允许调整布局层级、间距、字号、圆角、颜色令牌、矢量图标和视觉组件结构。
- **严禁触碰数据流与业务流程核心调用**：
  - 严禁修改或绕过：`load`、`importHistory`、`updateDraft`、`importBills`、`startClassify`；
  - 严禁变更业务协调器：`BillMutationService`、`CaptureImportService`、`CaptureIngestCoordinator` 等；
  - 数据与草稿状态流向由底层 ViewModel / Repository 保证，UI 层仅作为展示与用户事件传递者。
- **作用域隔离**：
  - 颜色令牌与主题（`CaptureTheme`）全局规范化；
  - 具体页面布局调整必须逐页确认，切忌为了单个页面调整而改动全局通用控件产生副作用。

---

## 2. 文件地图

所有待记相关 UI 均收敛在以下核心文件中：

| 文件路径 | 职责范围 |
| --- | --- |
| `app/.../capture/ui/CaptureUi.kt` | UI 核心工具箱：设计令牌（颜色/间距/字号）、组件生成器（卡片、Chip、按钮、图标按钮、波纹背景） |
| `app/.../capture/ui/PendingListActivity.kt` | 待记账单收件箱：待记流列表、草稿编辑、分类/账户/账本选择、批量操作、忽略区展开 |
| `app/.../capture/ui/RuleMappingViews.kt` | 资产映射与规则弹层：账户映射选择器、分类规则映射、来源过滤设置 |
| `app/.../capture/ui/DataRuleViews.kt` | 数据规则管理弹层：自定义规则展示、删除、测试联动 |
| `app/.../capture/ui/SplitConfirmSheet.kt` | 拆分确认弹层：拆分明细核对、金额配平、备注与类型逐项确认 |
| `app/.../capture/ui/CaptureHistoryActivity.kt` | 捕获历史数据页：原始事件只读流、规则命中统计、分段筛选、JSON 底部详情单 |

---

## 3. 设计令牌（Design Tokens）

所有 UI 元素统一使用 `CaptureUi` 中预置的语义化令牌，**严禁在页面内硬编码十六进制颜色**。

### 3.1 色彩系统
- **主品牌色（Brand）**：
  - `CaptureUi.BRAND` (`#5C6BC0`)：核心品牌蓝紫，用于主要强调色；
  - `CaptureUi.BRAND_DARK` (`#3F51B5`)：深色高对比文字与激活状态；
  - `CaptureUi.BRAND_SOFT` (`#EDEFFA`)：浅色柔和背景（Chip 激活、高亮卡片背景）；
  - `CaptureUi.BRAND_BORDER` (`#C5CAE9`)：聚焦或选中边框；
  - **红线**：严禁引入第二种不同色调的“蓝”，全模块色彩保持一致。
- **背景色**：
  - `CaptureUi.BG_PAGE` (`#F4F5F9`)：页面默认底色；
  - `CaptureUi.BG_SURFACE` (`#FFFFFF`)：卡片、顶栏、Sheet 表面底色；
  - `CaptureUi.BG_SUBTLE` (`#F6F8FA`)：浅灰占位底色、输入框背景；
  - `CaptureUi.BORDER` (`#E5E7EB`)：常规卡片与分割线边框。
- **文本颜色**：
  - `CaptureUi.TEXT_PRIMARY` (`#1F2328`)：一级标题、账单金额、关键标签（高对比度）；
  - `CaptureUi.TEXT_SECONDARY` (`#4B5563`)：二级说明、正文字符；
  - `CaptureUi.TEXT_MUTED` (`#6B7684`)：辅助说明、时间戳、未激活提示（对比度满足无障碍要求）。
- **语义色彩**：
  - 成功/入账：`COLOR_SUCCESS` (`#2E7D32`)，浅底 `COLOR_SUCCESS_BG` (`#E8F5E9`)；
  - 警告/待确认：`COLOR_WARN` (`#E65100`)，浅底 `COLOR_WARN_BG` (`#FFF3E0`)；
  - 危险/删除：`DANGER_BORDER` (`#FECACA`)，文字 `#DC2626`。

### 3.2 字号、圆角与点击区
- **字号阶梯**：
  - 页面大标题：`18sp` ~ `20sp`（加粗）；
  - 卡片主内容 / 金额：`15sp` ~ `16sp`；
  - 正文 / 操作按钮：`13sp` ~ `14sp`；
  - 标签 Chip / 状态角标：`11.5sp` ~ `12sp`；
  - 辅助时间戳 / 脚注：`10.5sp` ~ `11sp`。
- **圆角规格**：
  - 卡片容器：`12dp` ~ `14dp`；
  - 交互按钮：`10dp`；
  - 标签 Chip：`8dp` ~ `9dp`；
  - 状态点 / 圆形图标：全圆角（`999dp`）。
- **可点击区域**：
  - 所有按钮与交互热区保证高宽 **≥ 48dp**（视觉尺寸较小时通过 padding 或容器扩展触摸判定）。

---

## 4. 主题与系统控件

- **全局应用 CaptureTheme**：
  - 在 `AndroidManifest.xml` 中，所有待记与捕获 Activity 必须声明 `android:theme="@style/CaptureTheme"`；
  - `CaptureTheme` 继承自 `Theme.Material.Light.NoActionBar`，由系统级样式统一接管 `Switch`、`CheckBox`、`RadioButton` 的主色选中态，并配置 `AlertDialog` 的主色强调；
  - 状态栏与导航栏浅色图标规范：Activity `onCreate` 中统一调用 `CaptureUi.applyLightStatusBar(this)`，保证在浅色顶栏下状态栏系统图标（电量、信号、时间）为深色清晰可读。

---

## 5. 符号与图标规范

- **功能控件强制使用矢量图标（VectorDrawable）**：
  - 严禁使用字符符号（如 `▾`、`▴`、`✕`、`✓`、`●`、`○`）充当交互按钮或状态标识；
  - 功能符号一律使用 `res/drawable/` 下的矢量图标：
    - `ic_chevron_left`（返回）、`ic_chevron_down`（下拉展开）、`ic_chevron_up`（向上收起）；
    - `ic_close`（关闭）、`ic_check`（确认/完成）；
    - `ic_radio_on` / `ic_radio_off`（单选状态）；
    - `ic_refresh`（刷新）、`ic_history`（历史）；
  - 着色一律通过 `CaptureUi.iconButton`、`CaptureUi.icon` 或 `tint` 属性传入，支持随主题动态变色。
- **保留业务内容 Emoji**：
  - 账单分类自带的 Emoji 图标（如 餐饮 🍔、交通 🚗）作为用户数据内容保留，严禁误删或替换为抽象符号。
- **动作按钮以清晰文字为主**：
  - 优先使用明确动作词（如“保存”、“记一笔”、“拆分”、“导入”、“重跑”），而非难以辨识的孤立小图标。

---

## 6. 组件配方与调用标准

### 6.1 卡片容器
```kotlin
val card = CaptureUi.card(context = this).apply {
    // 默认内置 BG_SURFACE 白色表面、12dp 圆角、BORDER 描边
    setPadding(dp(14), dp(12), dp(14), dp(12))
}
```

### 6.2 标签 Chip
```kotlin
val chip = CaptureUi.chip(
    context = this,
    text = "餐饮",
    active = isSelected,
    trailingIcon = R.drawable.ic_chevron_down,
    onClick = { showCategorySelector() }
)
```

### 6.3 按钮规范
```kotlin
// 主要按钮 / 确认按钮
val btnPrimary = CaptureUi.button(
    activity = this,
    text = "保存并记账",
    textColor = Color.WHITE,
    bgColor = CaptureUi.BRAND,
    strokeColor = CaptureUi.BRAND,
    radiusDp = 10,
    paddingV = 10,
) { onSave() }

// 次要按钮 / 浅底强调按钮
val btnSecondary = CaptureUi.button(
    activity = this,
    text = "拆分",
    textColor = CaptureUi.BRAND_DARK,
    bgColor = CaptureUi.BRAND_SOFT,
    strokeColor = CaptureUi.BRAND_BORDER,
    radiusDp = 10,
    paddingV = 10,
) { onSplit() }
```

### 6.4 图标按钮
```kotlin
val btnBack = CaptureUi.iconButton(
    context = this,
    res = R.drawable.ic_chevron_left,
    tint = CaptureUi.TEXT_PRIMARY,
    contentDescription = "返回",
) { finish() }
```

---

## 7. 修改与验证流程

每次涉及 UI 变更时，必须严格执行以下闭环步骤：

1. **单页修改**：一次只专注于一个 Activity 或 Sheet，绝不跨模块连带修改；
2. **本地语法与编译验证**：
   ```powershell
   cd E:\FlipAccounting-AI
   git diff --check
   .\gradlew.bat :app:assembleDebug :capture-plugin:assembleDebug
   ```
3. **安装与真机确认**：
   ```powershell
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. **归档 APK 与提交**：
   编译生成的 APK 归档至 `dist/pending-rules/<任务卡号>/`；
   显式 `git add <目标文件>`，只 commit 不 push。

---

## 8. 常见避坑指南（踩坑实录）

1. **硬编码颜色值**：严禁在布局中直接写 `Color.parseColor("#333333")` 或 `android:textColor="#999999"`，请统一使用 `CaptureUi.TEXT_*`；
2. **Chip 横向超长溢出**：动态文字长度不可控，Chip 容器必须结合横向 `HorizontalScrollView` 或设置 `ellipsize = TextUtils.TruncateAt.END` 与 `maxLines = 1`；
3. **展开“已不计”导致列表跳顶**：点击展开折叠区域时，不要全量触发 `root.removeAllViews()` 重新构建，应仅对折叠容器执行 `visibility = View.VISIBLE / GONE`，避免滚动条跳跃；
4. **`setItems` 裸列表无样式**：`AlertDialog.Builder.setItems` 默认字体过大且无圆角，应使用带有自定义 View 的底部 Sheet 或适配 `CaptureTheme`；
5. **共享设计令牌的连带副作用**：修改 `CaptureUi.kt` 的公用工具方法时，务必检查所有调用方，避免一处调整破坏其他页面布局；
6. **矢量图 Tint 丢失**：部分 Android 系统上如果直接设置 `setImageResource` 未配 `imageTintList` 会导致矢量图变成黑色，统一使用 `CaptureUi.icon` 或 `CaptureUi.tintedDrawable` 处理；
7. **`ScrollView` 大数据量卡顿**：历史事件或待记列表超过 100 条时全量动态 AddView 会产生性能瓶颈，后续版本需推进向 `RecyclerView` 演进。

---

## 9. 验收核对清单（Checklist）

- [ ] **色彩一致性**：无第二种蓝，无未定义的十六进制硬编码；
- [ ] **对比度与无障碍**：主要文字与次要文字均满足明亮对比标准；
- [ ] **极限文本适配**：超长商户名、长备注在手机小屏下正常省略（`...`）而不遮挡金额与按钮；
- [ ] **空态与错误态完整**：无数据时具备居中引导卡片、图标与说明；
- [ ] **系统控件融入主题**：Switch / CheckBox 颜色为品牌主色；
- [ ] **触摸手感**：所有可点项具备水波纹或按压态反馈，热区 ≥ 48dp。

---

## 10. 当前进展与后续待办（债务追踪）

- **已完成项**：
  - 核心品牌色令牌化与统一规范；
  - `CaptureTheme` 接管系统组件与状态栏图标浅色适配；
  - 矢量图标库建立（check, chevron, radio, refresh, history）；
  - 待记收件箱、映射弹层、拆分确认单、历史数据页图标化与主色统一。
- **后续债务项（不阻塞核心业务逻辑，逐步跟进）**：
  - 资产与分类选择器搜索过滤；
  - 待记卡片信息降噪（简化次要辅助字段）；
  - 收件箱全量线性渲染迁移至 `RecyclerView`；
  - 开发者调试工具折叠收纳；
  - 全局暗黑深色模式（Dark Mode）适配。

---

## 11. 铁律声明（不可违背）

1. **绝对不要替换内容 Emoji**；
2. **绝对不要引入第二种蓝色**；
3. **绝对不要使用字符符号（`▾ ▴ ✕ ✓ ● ○`）充当可点控件**；
4. **绝对不要在视图代码中硬编码颜色**；
5. **绝对不要在改 UI 时顺手修改底层数据处理逻辑**。
