package com.taostudio.tapaccounting.capture

import android.content.Context
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.data.local.AppDatabase

/**
 * 待记收件箱入口的可见性。
 *
 * 主应用没有 hook 能力，只有插件真正投递过一次捕获数据后入口才有意义；
 * 默认隐藏，避免没 root / 没激活插件的用户看到一个永远空着的收件箱。
 *
 * `pendingCount > 0` 是数据保护兜底：插件卸载或模块失效后，已经存在的待记
 * 仍然必须能进去处理，不能因为入口条件变化把数据藏起来。
 */
object CaptureEntryGate {

    /** 插件 APK 当前是否安装。装了不等于激活，激活与否只看 [CapturePrefs.hasHookDelivered]。 */
    fun isPluginInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(CaptureContract.PLUGIN_PACKAGE, 0)
    }.isSuccess

    fun shouldShow(context: Context, pendingCount: Int): Boolean {
        if (pendingCount > 0) return true
        if (!isPluginInstalled(context)) return false
        return CapturePrefs.hasHookDelivered(context)
    }

    /**
     * 升级兼容：本次改动之前已经入过库的待记，同样证明插件 hook 过。
     * 没有这个回填，老用户把待记全部记完/忽略后入口会突然消失。
     */
    suspend fun backfillHookDelivered(context: Context) {
        if (CapturePrefs.hasHookDelivered(context)) return
        val repository = CaptureRepository(
            AppDatabase.getDatabase(context).paymentCaptureDao()
        )
        if (repository.list(1).isNotEmpty()) {
            CapturePrefs.setHookDelivered(context, true)
        }
    }
}
