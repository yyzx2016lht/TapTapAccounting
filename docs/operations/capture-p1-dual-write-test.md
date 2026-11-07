# P1 联调手册：主应用双写入库 + 规则包导入

> 对应改动：主应用 5 张待记表 / Room 42、`CaptureIngestReceiver/Provider`、
> 主应用通知与调试入口、插件 `CaptureForwarder` 双写、插件规则包导出。
>
> 目标：**P1 阶段不改变旧插件的待记 UI/气泡**。同一条支付会同时进入旧插件和主应用；
> 旧插件继续负责真机可见的待记列表和气泡，主应用只负责落库、通知（默认静默）和规则解析，
> 直到 P2 主应用收件箱上线。

---

## 1. 构建与安装

```powershell
.\gradlew.bat :app:assembleDebug :capture-plugin:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r capture-plugin\build\outputs\apk\debug\capture-plugin-debug.apk
```

两个 APK 必须使用同一个签名（debug 默认共用 debug keystore），
且两边的 `CAPTURE_INGEST_TOKEN` 必须一致（debug 默认 `dev-token`）。

安装后：

1. 打开一次主应用（触发 `TapApplication` 的规则包引导）；
2. LSPosed 里确认插件作用域仍是：微信、支付宝、`android`、`com.android.phone`；
3. 重启手机或重启 `com.android.phone`，让插件新代码在 phone 进程生效。

---

## 2. 先确认规则包已导入

主应用 APK 不内置 AutoAccounting 规则包；`TapApplication` 会尝试从插件
`content://com.taostudio.tapaccounting.capture.ingest/rule_bundle` 导出到：

```text
/data/data/com.taostudio.tapaccounting/files/rule_bundle.json
```

### 2.1 查看状态

```powershell
adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_PING
adb logcat -s TapCaptureDebug TapCaptureRuleImport TapCaptureRule TapCaptureIngestApp
```

日志里应看到类似：

```text
TapCaptureRuleImport: rule bundle imported from openFile: rules=... enabled=...
```

### 2.2 手动强制重新导入

```powershell
adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_IMPORT_RULES --ez force true
```

### 2.3 没有插件时的 adb 兜底导入（debug 包）

```powershell
adb push capture-plugin\src\main\assets\autoaccounting_rules.json /data/local/tmp/rule_bundle.json
adb shell run-as com.taostudio.tapaccounting cp /data/local/tmp/rule_bundle.json files/rule_bundle.json
adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_RELOAD_RULES
```

---

## 3. 模拟一笔支付（不依赖真实付款）

P1 内置了一个只写入主应用的模拟入口：

```powershell
adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_SIMULATE `
  --ei amountCents 1234 `
  --es merchant 测试商户
```

然后看日志：

```powershell
adb logcat -s TapCaptureDebug TapCaptureIngestApp TapCaptureRule
```

期望：

- `TapCaptureIngestApp` 出现 `rule matched ...`；
- 主应用 `payment_captures` 多一条 ¥12.34 的待记；
- 旧插件仍会正常收到它自己的那一份（如果 hook 链路也在跑）。

### 3.1 查看主应用待记数量

```powershell
adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_PING
```

或者直接查库（设备有 sqlite3 时）：

```powershell
adb shell run-as com.taostudio.tapaccounting sqlite3 `
  databases/TapAccount_database `
  "select id, source, amountCents, merchant, status from payment_captures order by paidAt desc limit 10;"
```

没有 sqlite3 时，把库拉到本机：

```powershell
adb exec-out run-as com.taostudio.tapaccounting cat databases/TapAccount_database > TapAccount_database.db
```

### 3.2 临时打开主应用通知

P1 默认静默，避免和旧插件重复提醒。需要验证通知时：

```powershell
adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_SET_PROMPT_MODE --es mode NOTIFICATION
```

验证完改回：

```powershell
adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_SET_PROMPT_MODE --es mode SILENT
```

---

## 4. 真实链路验证

### 4.1 双写不打架

1. 微信真实支付一笔；
2. 旧插件仍应弹气泡/更新通知（P1 不动它）；
3. 主应用日志应出现 `forward app broadcast sent` + `rule matched`；
4. 主应用 `payment_captures` 应新增同金额记录；
5. 两边记录都保留，互不干扰。

### 4.2 微信打字时输入法不被抢

P1 只新增显式广播，没有新增任何 Activity。触发 hook 时：

- 微信输入框不应失焦；
- 不应出现 1px 透明 Activity 闪一下；
- 如果仍然抢焦点，说明走的是旧插件的 `CaptureWakeActivity`（relay 旧逻辑），
  这是 P1 已知遗留，P5 会删除；P1 验收只看“主应用新增的入口不抢焦点”。

### 4.3 主应用被划掉/冻结

1. 从最近任务划掉主应用（不要强行停止）；
2. 再模拟一笔；
3. 主应用应被广播拉起并落库；
4. 如果没落库，抓：

```powershell
adb logcat -d -v time > capture-p1.log
adb shell dumpsys activity processes | findstr /i "taostudio"
```

---

## 5. 回滚

P1 不删除任何旧插件数据：

- 旧插件 `capture.db` 不动；
- 主应用只新增 5 张表，不影响账单/资产/预算；
- 回滚装回旧主应用 + 旧插件即可；
- 主应用新表在旧 APK 里不可见，也不会被旧 APK 删除。

---

## 6. P1 已知边界

- 主应用还没有待记列表/气泡 UI（P2/P3）；
- 主应用默认静默，用户可见提醒仍来自旧插件；
- relay 仍指向旧插件，P5 才切到主应用；
- 主应用 `.bak` 备份暂未包含 5 张待记表（P4 接）；
- 规则包导入依赖插件 0.1 的 `openFile`/`exportLegacyRuleBundle`；
  更老的插件版本只能先升级插件。
