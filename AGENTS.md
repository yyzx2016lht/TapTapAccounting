# 删除必须可恢复

任何 agent 在本仓库删除文件前，先确认它是否能找回：

1. **未被 Git 跟踪 / 被 .gitignore 忽略**（Git 里没有副本）→ 必须留一份：
   - `trash "<path>"`（进回收站，支持文件、目录、多个参数、通配符）
   - 没有 `trash` 就 `Move-Item "<path>" "$env:TEMP\deleted-<日期>/"`
2. **已被 Git 跟踪且没有未提交修改** → 可以 `rm`，`git restore "<path>"` 能还原。
3. **已被 Git 跟踪但有未提交修改** → 用 `trash`，`git restore` 救不回这些改动。
4. 拿不准 → 一律 `trash`。

禁止：`git clean -fdx`、对未跟踪路径 `rm -rf` / `Remove-Item -Recurse -Force`、清空回收站、先删再重建。

---

# 工作规则（TapTapAccounting）

开工前先读：`README.md`（构建与目录）、`CONTEXT.md`（领域术语）、`docs/ui-audit/UI-AUDIT.md`（已知 UI 债务）。
本文件只放长期有效的规则；长流程放 `.agents/skills/`，按需加载。

## 1. 先说明，再动手

每个任务的第一条回复，先写这三行，再调用任何会修改文件的工具：

```
我要做：<一句话目标>
会改：<文件/目录清单>
怎么验：<命令或真机步骤>
```

只读排查（读文件、`rg`、看日志、截图）可以直接做，但要用一句话说明在查什么。
以下情况先发方案，等我回复「可以」再动：改动超过 3 个文件、改数据库 schema、新增依赖、动签名/发布/CI 配置、删除任何文件、连接真机/安装 APK/截图/任何 `adb` 操作。

## 2. 需求先对齐，再开工

「做什么 / 在哪做 / 怎样算完成」有任何一项不清楚，先提问并停下，不要边猜边写。
一次最多问 3 个问题，每个问题给 2-3 个选项并标出你的推荐；得到明确答复后才改代码。
我说「随便 / 你看着办」时，先给出默认方案和影响范围，再开始。

## 3. 先找现成的，再写新的

按顺序找：本仓库 → 已装依赖 → Google Maven / Maven Central → GitHub / 官方文档。
- 本仓库：先搜同名或同功能的 layout、style、工具类、AI prompt，禁止出现第二套实现。
- 新 UI：先看 `app/src/main/res/layout`、`values/styles.xml`、`colors.xml`、`dimens.xml` 里能直接复用的组件和 token。
- 外部：列 2-3 个候选，写清最近更新、License、体积/依赖数、适配成本；结论只能是「采用 / 借用 / 自建」，自建必须逐条说明候选为什么不行。
- 新增依赖必须先问，并说明对 APK 体积和 minSdk 24 的影响。
- 调研结论先发给我看，等我选定「采用 / 借用 / 自建」后再写代码；纯 bug 修复和文案改动除外。
完整流程见 `.agents/skills/research-first/SKILL.md`。

## 4. UI：一致性优先于「重新设计」

- 只用 `colors.xml` / `dimens.xml` / `styles.xml` 里的 token；不新增硬编码的色值、字号、圆角、间距。
- 新页面/弹窗先抄现有最接近的页面结构，再改内容；顶部栏、按钮、空态、加载态保持同一套。
- 改完先描述预期效果；是否上机截图由我决定，不要自己抓图。
- 已知债务：12 种品牌蓝、24 种字号、21 种圆角、style 复用率 5.2%（见 `docs/ui-audit/UI-AUDIT.md`）。不要制造第 13 种蓝。
- 图标沿用现有 drawable 风格，不用 emoji 当图标；金额、日期、币种走已有格式化实现。

## 5. 验证：默认只在本机跑，不碰手机

默认只跑构建和单测：

```powershell
.\gradlew.bat testDebugUnitTest          # 改 Kotlin
.\gradlew.bat assembleDebug              # 改资源/布局/清单
.\gradlew.bat :capture-plugin:assembleDebug   # 改插件
```

**默认禁止**：`adb install`、`am start`、`input tap/swipe/text`、`screencap`，以及任何 adb 调试和截图。
只有我明确说「装到手机」「截图看看」「复现一下」时才操作设备；动手前先 `adb devices -l` 确认当前连的是哪台，不要用文档里的旧地址。
设备都是真机（无线 ADB），不是模拟器。装机、截图属于第 1 节的「先发方案」：一次只做我要求的那一步，做完就停。
无法上机或不需上机的改动，写清「未在设备验证」及原因即可，不要为了凑证据去装包截图。

命令跑不了要说明原因；不允许把「没验证」写成「已完成」。失败时找根因，不许为了变绿删测试、关 lint、放宽断言。

## 6. 完成时这样报告

- 改了什么（一句话）
- 文件清单（新增/修改）
- 验证命令与结果（贴关键输出即可，不要求截图）
- 没做的 / 有风险的（没有就写「无」）

## 7. 不要做

- 不删无法恢复的文件（见开头规则）。
- 不改 `key.properties`、`release-keystore.jks`、`local.properties`、`build/` 产物。
- 不静默加依赖、不静默改数据库 schema、不静默改 AI prompt 的行为契约。
- 不顺手重构无关代码，不重排 import/格式制造噪音 diff。
- 不把 `archive/` 当活代码修改。
- 不虚构命令输出、截图或测试结果。
