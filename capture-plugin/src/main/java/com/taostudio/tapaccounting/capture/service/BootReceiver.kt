package com.taostudio.tapaccounting.capture.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机/升级后拉起保活服务，避免重启后第一次支付广播被 ColorOS 冻结延迟。
 *
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED 是 Android 12+ 允许后台启动前台服务的豁免场景；
 * 仍然 catch，启动失败不影响广播落库。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> CaptureKeepAliveService.start(context)
        }
    }
}
