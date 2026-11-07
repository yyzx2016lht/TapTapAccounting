---
name: android-apk-loop
description: 用户明确要求「装到手机 / 截图 / 真机复现 / 回归」时，才用这套构建、安装、观察、操作、复验的闭环。默认不要自动装包或截图。Use only when explicitly asked to build an APK, verify on a device, or reproduce a UI bug on hardware.
---

# Android APK Loop

## 0. 使用前提（硬门槛）

**只有在用户明确说了「装到手机」「截图看看」「在真机上复现」「回归一下」时，才执行本流程。**

- 不自动 `adb install`，不自动 `am start`，不自动 `input tap/swipe/text`，不自动 `screencap`。
- 不为了「凑验证」去连设备；构建 + 单测能说明问题的，就停在构建 + 单测。
- 一次只做用户要求的那一步（例如只装包，不要顺手点进去截图）。
- 用户没有要求截图时，不要截图；描述预期效果让用户自己看。
- 动手前先 `adb devices -l` 确认当前连的设备；**不要用文档或脚本里的旧地址**。

设备都是**真机**（无线 ADB），不是模拟器。历史文档里的 `172.20.1.3:8888` 和当前实际连接的设备可能不是同一台，脚本里写死的地址只作参考，不作为默认值。

## 1. 构建

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat :capture-plugin:assembleDebug    # 改插件时
```

产物：

- 主应用：`app/build/outputs/apk/debug/app-debug.apk`
- 插件：`capture-plugin/build/outputs/apk/debug/capture-plugin-debug.apk`

构建失败先读第一条 error，修最小相关处再重跑；不要连着重跑或关掉检查。

## 2. 安装与启动（仅在用户要求时）

```powershell
adb devices -l
adb -s <当前设备> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <当前设备> shell am start -n com.taostudio.tapaccounting/.MainActivity
```

Activity 名以 `AndroidManifest.xml` 为准。覆盖安装报签名不一致时，先确认设备上装的是不是旧签名包，不要重建 keystore。

## 3. 观察 → 操作 → 复验（仅在用户要求时）

```powershell
.\docs\ui-audit\dump.ps1
.\docs\ui-audit\tap.ps1 -x <x> -y <y>
```

- **坐标只能来自 `dump.ps1` 的 bounds**，不要从截图估算（截图可能被缩放）。
- 每次操作后重新 dump 或截图确认状态变化，不要假设点击生效。
- 截图只在用户要求时执行；`shot.ps1` 里的设备地址是旧的，先改成本次设备或直接 `adb -s <设备> exec-out screencap -p`。
- 滚动/输入：`adb -s <设备> shell input swipe|text|keyevent`。
- 日志：先 `adb logcat -c`，操作后 `adb logcat -d`，过滤 `FATAL|ANR|TapCapture|CaptureBridge|OverlayManager`。

## 4. 判定标准

在用户要求的范围内判定：

1. 目标元素在 `dump.ps1` 输出里出现，或用户指定的页面状态成立
2. 用户要求对比时，截图与改动前无明显差异（布局/颜色/间距）
3. `logcat` 无 `FATAL EXCEPTION`、`ANR in`、`UnsatisfiedLink`

失败时保留：复现步骤 + 相关日志片段（用户要求截图时再附截图路径），再改代码。
无法验证的（真实支付、推送、系统权限弹窗等）明确写「未验证 + 原因」。

## 5. 交付安装包

- debug 包：直接给 `app/build/outputs/apk/debug/app-debug.apk` 绝对路径
- release 包：需要 `key.properties` + `release-keystore.jks`。**缺任何一个都不要新建 keystore**（签名会变，用户无法覆盖安装），先问
- 报告里写清：路径、versionName/versionCode、是否已装到设备、安装命令

## 6. 给 AI 的任务模板（可直接粘贴）

```text
实现：<一句话目标>
范围：<页面/文件；没想好就写「你判断」>
验收：<可见结果，例如「待记列表顶部出现筛选条，勾选后底部出现批量操作栏」>

要求：
1. 先说明你要做什么、改哪些文件、怎么验证，再开始。
2. 开工前先搜仓库和现成方案，说明是采用现成还是自建。
3. 复用现有 colors/dimens/styles，不要新增硬编码值。
4. 只跑 testDebugUnitTest 和 assembleDebug，不要装机、不要截图。
5. 如果要上机验证，先告诉我装哪台、要看什么，等我确认。
```
