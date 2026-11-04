package com.taostudio.tapaccounting.capture.hook

import com.taostudio.tapaccounting.capture.relay.RelayServer
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 入口；类名写在 assets/xposed_init。
 *
 * 作用域：
 * - 微信 / 支付宝：应用内数据（WCDB、WebView、消息盒子）
 * - android（system_server）：系统通知
 * - com.android.phone（TeleService）：短信
 *
 * 微信/支付宝只 hook 主进程；android/com.android.phone 是常驻系统进程。
 */
class PluginXposed : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            TARGET_WECHAT, TARGET_ALIPAY -> {
                // AutoAccounting 只 hook 宿主主进程；子进程重复 hook 只会增加风险。
                if (lpparam.processName != lpparam.packageName) return
                ProbeHooker.log(
                    "loaded package=${lpparam.packageName} process=${lpparam.processName} " +
                        "targetSdk=${lpparam.appInfo?.targetSdkVersion ?: "?"}",
                )
                try {
                    // 注意：不要在微信/支付宝里启动 RelayServer(52046)，
                    // 否则重启 com.android.phone 时 52046 会被微信抢占，而微信在后台没有 UID 1001 的
                    // startActivity 解冻特权。52046 固定由常驻系统进程 com.android.phone 独占。
                    ProbeHooker.install(lpparam)
                } catch (t: Throwable) {
                    // 宿主版本变化可能让类加载失败；绝不能让微信/支付宝崩溃。
                    ProbeHooker.log("install failed: ${t.javaClass.name}: ${t.message}")
                }
            }

            TARGET_ANDROID -> {
                try {
                    SystemNotificationHooker.install(lpparam)
                } catch (t: Throwable) {
                    // system_server 里绝不能抛出去。
                    ProbeHooker.log("notification install failed: ${t.javaClass.name}: ${t.message}")
                }
            }

            TARGET_PHONE -> {
                if (lpparam.processName != lpparam.packageName) return
                try {
                    // com.android.phone 是常驻系统进程，是 relay 的首选宿主。
                    RelayServer.install(lpparam)
                    SmsHooker.install(lpparam)
                } catch (t: Throwable) {
                    ProbeHooker.log("sms install failed: ${t.javaClass.name}: ${t.message}")
                }
            }
        }
    }

    private companion object {
        const val TARGET_WECHAT = "com.tencent.mm"
        const val TARGET_ALIPAY = "com.eg.android.AlipayGphone"
        const val TARGET_ANDROID = "android"
        const val TARGET_PHONE = "com.android.phone"
    }
}
