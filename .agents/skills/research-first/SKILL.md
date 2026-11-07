---
name: research-first
description: 写新功能、新页面、新组件或新增依赖之前，先在本仓库、已装依赖、Google Maven/Maven Central 和 GitHub 上找现成实现，给出「采用/借用/自建」结论后再动手。Use when adding a feature, screen, component, dependency, or when about to write something from scratch.
---

# Research First

核心：先看外面有什么，再决定写什么。**自建是默认被拒绝的选项**，除非能逐条说清候选为什么不合适。

## 什么时候必须跑

- 新增页面、弹窗、自定义 View，尤其是「做得好看一点 / 优化一下 UI」
- 新增工具类、格式化函数、日期/金额处理、正则
- 新增 Gradle 依赖、接入第三方 SDK
- 同一类 UI / 逻辑在仓库里已经存在，但你要再写一份
- 用户说「参考一下别的 App」「看看有没有现成的」

以下情况不用跑全流程，先 `rg` 一遍仓库即可：修 bug、改文案、调已有组件的参数、改颜色 token。

## 步骤

### 1. 写清问题和约束（3 句以内）

必须写明：要什么、在哪用、约束。本项目固定约束：

- 只有 `:app` 一个产品模块（另加 `:capture-contract`、`:capture-plugin`）
- `minSdk 24` / `compileSdk 34` / Java 17 / 只打 `arm64-v8a`
- UI 是 XML + Material Components 1.11 + AppCompat，**没有 Compose**
- release 开 minify + shrinkResources，APK 体积敏感
- 依赖版本统一写在 `gradle/libs.versions.toml`（能在 catalog 里加就别写死版本）

### 2. 搜本仓库（至少一次 `rg` + 一次 glob）

- 布局：`app/src/main/res/layout/` 里有没有同结构的页面/弹窗/列表项
- 样式：`values/styles.xml`、`colors.xml`、`dimens.xml`、`themes.xml` 里有没有现成 token
- 代码：`app/src/main/java/com/taostudio/tapaccounting/` 下有没有工具类、适配器、Manager
- 已有依赖：`gradle/libs.versions.toml` + `app/build.gradle.kts`
- 已有能力举例（先确认再用）：
  - 图表 `MPAndroidChart`、裁剪 `uCrop`、日历 `kizitonwose.calendar`、引导 `Spotlight`、Markdown `Markwon`、OCR/推理 `LiteRT`、图片 `Glide`、本地偏好 `Prefs`
  - 金额/币种：`CurrencyManager`；日期：现有 DateUtils / 格式化方法；AI：`AIPrompts` + `AIService`

### 3. 搜外部（按问题类型选地方）

| 问题类型 | 去哪找 | 停在哪 |
|---|---|---|
| 选库 / 选组件 | Google Maven、Maven Central、GitHub | 2-3 个候选有版本、License、最近提交 |
| 有没有人做过 | GitHub 代码搜索、awesome-android、同类开源 App | 读到 2 个真实实现，或 3 个独立来源一致 |
| 官方行为 / API | Android Developers 官方文档、`--help`、changelog | 一条带版本号的一手事实 |
| UI 长什么样 | Material 3 组件规范、GitHub 上同场景 XML 布局 | 找到可直接借用的结构与交互 |

判断候选是否可用：最近 12 个月有提交、License 明确（MIT/Apache 优先）、不要求 `minSdk > 24`、不强制引入 Compose/Hilt、依赖树不大。

### 4. 给结论（必须是一张表）

```
候选：<名称> <版本>
更新：<最近提交/发版时间>
许可：<License>
成本：<体积/MB、依赖数、改动点>
结论：采用 / 借用 / 自建
```

- **采用**：直接装、直接用，说明装哪个版本、放哪个文件。
- **借用**：抄结构或一小段实现，说明抄了什么、改了哪里。
- **自建**：必须逐条列出候选被排除的原因（例如「只在 Compose 下可用」「要求 minSdk 26」「要多引 3 个依赖」），并说明自建要维护什么。

### 5. 等确认再实施

新增依赖、替换现有组件、引入 SDK、改架构前，先问用户。只做被确认的那一步。

## 反模式

- 「没找到现成的，我自己写」——先证明搜过，给出搜过的范围。
- 引一个大库只用其中一个函数。
- 把 GitHub 上的 Compose 方案往 XML 项目里搬。
- 搜索结果不写版本/日期/License，凭印象下结论。
- 为了「好看」新建一套颜色/圆角/字号，而不是复用 token。

## 输出模板

```
问题：…
约束：minSdk 24 / XML / 体积敏感 …
仓库内已有：…（路径）
外部候选：1) … 2) …
结论：采用 / 借用 / 自建 —— 理由：…
需要你确认：…
```
