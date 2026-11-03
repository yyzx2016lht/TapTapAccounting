package com.taostudio.tapaccounting.capture.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 入口；类名写在 assets/xposed_init。
 *
 * P0 只做探针：识别微信/支付宝进程并安装日志 hook，不解析、不建待记。
 */
class PluginXposed : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_WECHAT && lpparam.packageName != TARGET_ALIPAY) {
            return
        }
        ProbeHooker.log(
            "loaded package=${lpparam.packageName} process=${lpparam.processName} " +
                "targetSdk=${lpparam.appInfo?.targetSdkVersion ?: "?"}",
        )
        try {
            ProbeHooker.install(lpparam)
        } catch (t: Throwable) {
            // 宿主版本变化可能让类加载失败；绝不能让微信/支付宝崩溃。
            ProbeHooker.log("install failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private companion object {
        const val TARGET_WECHAT = "com.tencent.mm"
        const val TARGET_ALIPAY = "com.eg.android.AlipayGphone"
    }
}
