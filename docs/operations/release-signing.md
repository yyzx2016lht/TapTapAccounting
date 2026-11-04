# Release 签名凭证（备忘）

> 本文件只记录**指纹**与流程，**不记录密码**。密码在 `key.properties`（本地，gitignored）
> 和密码管理器里。

## 当前使用的签名

| 项 | 值 |
|---|---|
| keystore | `release-keystore.jks`（仓库根目录，gitignored） |
| alias | `myalias` |
| 算法 | RSA 4096 / SHA384withRSA / 有效期 10000 天 |
| 证书 SHA-256 | `9CE35D1CC2A3CDC3F1C597BAC40D954BA893DD8B15936CA2AC8CC28AAC636B54` |
| 证书 SHA-1 | `见 keytool -list -v` |
| DN | `CN=TapTapAccounting, OU=Mobile, O=taoStudio, C=CN` |
| 生成日期 | 2026-11-02 |
| 备份位置 | `E:\keystore-backup\TapTapAccounting\`（**另需**一份离线介质 / 密码管理器） |

`key.properties` 结构（`storeFile` 相对 `app/` 目录，由 `app/build.gradle.kts` 的
`file()` 解析）：

```properties
storeFile=../release-keystore.jks
storePassword=***
keyAlias=myalias
keyPassword=***
```

校验命令：

```bash
./gradlew :app:signingReport          # 确认 release 变体指向本 keystore
keytool -list -v -keystore release-keystore.jks -alias myalias
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## 为什么这把 key 必须一直用下去

- Android 只允许**同签名**的 APK 覆盖安装。换签名 = 老用户必须卸载重装 = 本地数据丢失。
- `docs/pending-expense-capture.md` 里的 LSPosed 采集插件要用 `signature` 权限和主 App 通信，
  主 App 与插件 APK 必须**同一签名**。以后再换 key，两边的权限就一起断。

## 历史：2026-11 之前的旧 key 已丢失

- 旧 keystore 也是 `release-keystore.jks`（alias `myalias`），证书
  `474BD4B6…2530`（完整指纹见 `docs/operations/敲击保活排查记录-2026-09.md:201`）。
- 该文件只存在于本地，被 `.gitignore` 排除，**从未进入 git 历史**；删库重建后无法恢复
  （已核对 208 个提交 + 全盘搜索，无残留，也没有 release 签名的 APK 残留）。
- 后果：任何用旧 key 签过的包都无法用新 key 覆盖升级。若只是自己设备，装的本来就是
  debug 签名包（`8680401f…c9884`），不受影响；若曾把 release 包发给别人，对方需卸载重装。
- 若该项目曾上架 Google Play 且开启了 Play App Signing：应用签名密钥在 Google 侧，
  可在 Play Console 申请「重置上传密钥」，升级不受影响。

## 规则

1. `release-keystore.jks` / `key.properties` **永不入库**（已在 `.gitignore`）。
2. 生成或更换 key 后，立刻更新本文件的指纹与日期，并同步备份到两处以上。
3. 发布前跑 `docs/operations/release-regression-checklist.md`。
