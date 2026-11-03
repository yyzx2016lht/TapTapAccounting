# 支付捕获插件 P0 安装与探针测试

> 适用：debug 构建；个人设备；LSPosed。
> 目标：验证“模拟捕获 → 通知 → 列表 → 自己记 → 回执”闭环，以及微信/支付宝 hook 是否能装上。

## 1. 构建

```powershell
.\gradlew.bat :app:assembleDebug :capture-plugin:assembleDebug
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `capture-plugin/build/outputs/apk/debug/capture-plugin-debug.apk`

两个 debug 包在同一台机器构建时会使用同一个 debug keystore，`signature` 权限才通。若 App 装的是 release 包，插件 debug 会被 `CaptureBridgeProvider` 拒绝，这是预期。

## 2. 安装

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r capture-plugin\build\outputs\apk\debug\capture-plugin-debug.apk
```

然后在 LSPosed 里：

1. 启用“支付捕获”模块；
2. 作用域只勾 **微信** 和 **支付宝**，不要勾敲敲记账；
3. 重启微信/支付宝（或重启手机）。

## 3. 先测闭环（不依赖微信/支付宝）

1. 打开“支付捕获”App，允许通知权限。
2. 点“模拟一笔” → 通知栏出现“1 笔待记”，列表出现 3.00 微信 菜摊。
3. 点该行“自己记” → 敲敲记账悬浮窗打开，金额/时间/备注已填好。
4. 选分类、保存 → 回到插件列表（或点刷新）→ 该行变 `RECORDED`。
5. 再测“备注”“忽略”，确认列表和通知数量变化。

如果第 3 步提示“未安装或未开放桥接”：

- 确认两个 APK 都是 debug 签名；
- 确认 `CaptureBridgeProvider` 在 App manifest 里、authority 是 `com.taostudio.tapaccounting.capturebridge`；
- 确认插件 manifest 有 `com.taostudio.tapaccounting.permission.CAPTURE_BRIDGE` 的 uses-permission；
- 看 `adb logcat | findstr CaptureBridge`。

## 4. 微信/支付宝探针

### 4.1 看日志

```powershell
adb logcat -s TapCaptureProbe
```

LSPosed 日志里也可以过滤 `TapCaptureProbe`。

### 4.2 微信

逐项确认：

- `hook installed: wcdb.SQLiteDatabase.insertWithOnConflict`
- `hook installed: MMKRichText.appendText`
- `hook installed: com.tencent.xweb.WebView.evaluateJavascript`

然后做一笔真实支付（建议 0.01 元），观察：

- `wcdb.insertWithOnConflict table=message/AppMessage type=...`
- `MMKRichText.appendText ...`
- 是否出现 `nativeWXPayCgiTunnel:ok` / `respbuf`
- 插件列表是否出现“待补金额”条目（探针版不保证金额能解析）

### 4.3 支付宝

逐项确认：

- `hook installed: SyncMessage.getData`
- `hook installed: H5WebView.evaluateJavascript`

做一笔真实支付/打开账单页，观察：

- `SyncMessage.getData len=...`
- H5 是否命中 `tradeNo=` / `ALIPAYVIEWAPPEARED=1`
- 插件列表是否出现“待补金额”条目

### 4.4 如果 hook 装不上

- 目标 App 版本变了：类名/方法可能不存在；看日志里的 `hook unavailable`。
- LSPosed 作用域没勾/没重启：重新勾选并强制停止微信/支付宝。
- 微信 Tinker 热更新：AutoAccounting 会删 patch 并关 Tinker；我们 P0 没做，先观察。
- 支付宝 H5：可能改成原生页；需要另找 hook 点。

## 5. 已知限制（P0）

- **探针版不保证金额/商户/支付方式解析正确**；`amountCents` 可能是 null，列表显示“待补金额”。
- **没有完整去重**：同一笔可能被多个 hook 各报一次；先用“忽略/删除”清理。后续用 `sourceTxnId` + 10–30 秒归并窗口。
- **没有常驻前台服务**：通知由插件进程按需更新；进程被杀后通知仍在，下次捕获再拉起。
- **`openQuickRecord` 后台启动悬浮窗**在 Android 12+ 可能被拒；Provider 会返回 `foreground_service_not_allowed`，此时先点开 App 再试。
- **没有 AI/批量导入**：P1/P2。
- **备份不含插件待记**：卸载插件会丢待记，先导出（P1）。
- **GPL**：参考 AutoAccounting 的 hook 点时，不要直接把 GPL 代码粘进要分发的插件；个人使用可，分发前处理许可证。

## 6. 下一步

1. 把 `adb logcat -s TapCaptureProbe` 里微信/支付宝的真实 JSON 样本发回来；
2. 按样本固化 `CapturePayload` 解析：金额、时间、支付方式、商户、订单号；
3. 加 `sourceTxnId` 去重和 10–30 秒归并；
4. 再接资产映射、AI 代理、批量导入。
