# UI 统一工作交接

最后更新：2026-10-01。上一轮最后一个提交是 `251703c`。

## 用户的硬性偏好（必读）

- **保留多彩风格**。设置页图标底色、各页分区色条本来就是多种颜色，不要统一成单色。曾经改成单色，被用户要求改回。
- **不要动图标**。原有的 PNG、矢量图标和 emoji 都不能替换或重绘。曾经把悬浮窗的 emoji 换成矢量图标，被用户强烈否定并已回退。只允许改按钮填充、文字、描边这类颜色。
- **一次改一块**。改完装到真机、截图给用户看，用户同意后再 `git commit`，然后才进行下一步。
- `UI-AUDIT.md` 只能当线索看。它的建议偏向全局统一单色，和用户偏好冲突。

## 已经改完的部分（均已提交）

各提交的完整改动见 `git show <hash>`，这里只列范围。

| 提交 | 内容 |
| --- | --- |
| `f3ff35d` | 设置页行规格统一：新增 `res/values/styles_settings.xml`（`SettingsRow` / `SettingsBadge` / `SettingsItemTitle` 等），`fragment_profile.xml` 改用这些样式；徽章原有的多种颜色保留 |
| `2f39019` | 支出红统一为 `@color/expense_color`（#FF5252）；Kotlin 侧通过新增的 `BillColors.expense` 取色 |
| `6e61681` | 收入绿统一为 `@color/income_color`（#4CAF50），对应 `BillColors.income`；悬浮记账窗的保存按钮和"AI 对话"文字改为 `brand_primary` |
| `251703c` | 按钮色统一，见下 |

`251703c` 包含：
- 主按钮、绿色确认按钮、文字按钮和链接里散落的多种蓝绿统一为 `brand_primary`（#5C6BC0）。包括多选栏"移动"、顶栏文字操作、记账键盘的 `+ − × ÷`。
- 9 种删除红统一为 `danger_color`（#E53935），包括多选栏删除、编辑页删除、预算删除、`bg_btn_outline_red` 描边。
- 账单详情底部弹窗的"删除"从实心红改为红字加红描边（`bg_dialog_button_danger`，只有这一处在用）。
- 悬浮窗"智能识别"改为 `brand_primary`。用户先选了 AI 紫，看过后改成主题蓝。
- 账单分组头、资产月份头、资产统计分组头的复选框从 #4080FF 改为 `brand_primary`。主题层的 `colorSecondary` 早已是 `brand_primary`（见 `81a4658`）。
- 存储清理页（`activity_storage_cleanup.xml`）重排：
  - 卡片样式与备份页一致。
  - 每个清理项改为单行：复选框加名称，统计数字右对齐。新增样式 `CleanupItemRow` / `CleanupItemCheck` / `CleanupItemStats`，写在 `styles_settings.xml`。
  - 5 个统计格式串去掉了重复的名称前缀。
  - "清理所选内容"按钮套用备份页"清空全部账单"（`activity_backup_new.xml` 里的 `btn_cleanup_all`）的红色描边样式，图标为 `@drawable/ic_delete`，用户已认可。

## 已查过但有意不改的部分（不需要再改）

下面这些颜色看起来"不统一"，但都是数据语义或独立用途，已和用户确认过或已判断为合理：

- **数据语义色**：
  - 余额调整页的差额正负色（`activity_balance_adjustment` 165/199/270 行）。
  - 汇率涨跌（`item_exchange_rate`:71）。
  - AI 输入弹窗里的识别结果状态色（`layout_dialog_ai_input` 160/171 行）。
  - 账本总览余额与卡片底色（`tvTotalBalance`、`item_book_overview_card`）。
  - 对账差额（`activity_asset_reconcile` `tv_diff`）。
  - 首页同步错误提示（`fragment_home` `tvSyncError`）。
- **退款开关**：编辑账单页和悬浮窗里的退款切换色，属于状态色。
- **AI 配置页**的 `tv_edit_prompt`（`layout_main_ai`）。
- **手势权限引导按钮**（`activity_sensitivity`:1305 `btn_gesture_permission_guide`，#E45649）：这是"去开启"类的行动按钮，不是删除，所以没有并入 `danger_color`。
- **所有图标相关的 tint**：`iconTint`、图标徽章底色、emoji 都按用户要求保持原样。
- **很淡的红色底板**：`shape_button_outline_red`（#FFF0F0 / #FFCDCD）、`bg_chat_sidebar_action_danger` 只是浅底，不影响一致性。
- **`dialog_button_danger_text`、`dialog_button_danger_bg` 颜色值**：保留未删，目前已没有布局引用，可以不管。

## 还没做的（用户本轮明确叫停，没有继续）

- **AI 功能页分区色条**（`activity_ai_feature_settings.xml`）：前两个是亮蓝，第三个是淡紫。用户之前选过"对齐这几条的颜色"，但还没动手。做之前先截图给用户确认方向，注意保持多彩风格。
- **设置页徽章颜色**：之前另一个模型在 `c314c2a` 把徽章颜色压暗了（如靛蓝 #2B0DF2、紫 #CC0DF2）。用户没有要求修，除非用户主动提出，否则不要碰。

## 验证工具与真机信息

- 真机：无线 adb `172.20.1.3:8888`，包名 `com.taostudio.tapaccounting`。
- 编译安装：`.\gradlew.bat :app:assembleDebug -q`，然后 `adb -s 172.20.1.3:8888 install -r app/build/outputs/apk/debug/app-debug.apk`。
- 启动：`adb shell monkey -p com.taostudio.tapaccounting -c android.intent.category.LAUNCHER 1`。
- 截图脚本：`docs/ui-audit/shot.ps1 -Name x`、`docs/ui-audit/tap.ps1 -x -y -Name -Wait`。截图宽 461px，对应真机坐标要乘 2.343。
- 坐标会随布局变化，**点之前先 dump 界面拿 bounds**，不要盲点。曾经因为盲点误切过两个开关（已恢复）。
  - 底部导航 y=2284：账单 x134、统计 x403、资产 x675、设置 x944。
  - 设置页（2026-10-01 实测）：备份与恢复 y≈1470，AI 功能 y≈1640，存储清理 y≈1810。
- AI 功能页进入时会自动弹出配置对话框，先点"取消"。
- 本月（10 月）首页暂无账单。看账单详情和多选栏请走搜索页，长按账单进入多选。

## 编辑注意事项

- 仓库的 `.gitattributes` 是 `eol=lf`，但不少布局文件实际是 CRLF，`DailyReportAdapter.kt` 甚至是 `\r\r\n`。批量替换请按字节读写，保持原换行符，确保 diff 只包含改动的行。
- PowerShell 下复杂的正则或引号容易出错，改为在 `.tmp/` 写 Python 脚本执行。.NET 文件 API 要用绝对路径。输出中文前设置 `PYTHONIOENCODING=utf-8`。
- 悬浮窗是 Service 里 inflate 的，不是 AppCompat 环境，着色要用 `android:tint`，不能用 `app:tint`。

## 建议使用的 skills

- `diagnose`：如果改色后出现真机显示和预期不符。
- `ponytail-review`：提交前检查是否引入了不必要的样式抽象。
