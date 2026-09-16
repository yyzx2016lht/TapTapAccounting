package com.taostudio.tapaccounting

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 手势服务看门狗。
 *
 * 背景：ColorOS/Osense 会在这个 App 空闲时清掉进程，而 `START_STICKY` 实测完全不兑现
 * （见 keepalive 排查：09-14 23:52 之后再无心跳，15 小时未自愈）。同时 WorkManager
 * 被证明在这台设备上**照常触发**（AutoBackupWorker 06:30 / 12:30 均正常执行），
 * 所以用周期任务当"自愈"通路。
 *
 * 局限（必须知道）：
 *  1. WorkManager 周期任务最短 15 分钟，且受 Doze 影响，实际间隔可能是几十分钟
 *  2. 如果包进入 `stopped` 状态（force-stop），WorkManager 同样不会触发，这条链会断
 *  3. 若系统在拉起的瞬间状态不允许启动前台服务，本次重启会失败并记日志，下次再试
 */
class OverlayWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "OverlayWatchdog"

        /** 15 分钟是 WorkManager 周期任务的硬下限 */
        private const val INTERVAL_MINUTES = 15L

        /** 首次执行延迟，避开进程刚被杀就立刻重拉 */
        private const val INITIAL_DELAY_MINUTES = 1L

        /** 被杀后的重试退避，用来在 15 分钟周期之外多争取几次机会 */
        private const val BACKOFF_SECONDS = 30L

        private const val UNIQUE_NAME = "overlay_watchdog"

        fun schedule(ctx: Context) {
            // 关闭手势时不该再挂看门狗
            if (!Prefs.isDoubleTapEnabled(ctx) && !Prefs.isFlipEnabled(ctx)) {
                cancel(ctx)
                return
            }
            val request = PeriodicWorkRequestBuilder<OverlayWatchdogWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // KEEP：不要因为重复 schedule 把已有的排期重置掉
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Logger.d(ctx, TAG, "watchdog scheduled (every ${INTERVAL_MINUTES}min, keepExisting)")
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
            Logger.d(ctx, TAG, "watchdog cancelled")
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // 手势全关就别再拉服务了
        if (!Prefs.isDoubleTapEnabled(ctx) && !Prefs.isFlipEnabled(ctx)) {
            Logger.d(ctx, TAG, "gestures disabled, skipping")
            return Result.success()
        }

        if (OverlayService.isServiceRunning) {
            Logger.d(ctx, TAG, "service alive, nothing to do")
            return Result.success()
        }

        Logger.d(ctx, TAG, "service missing, attempting restart")
        return try {
            OverlayService.startCompat(ctx, Intent(ctx, OverlayService::class.java))
            Logger.d(ctx, TAG, "restart requested")
            Result.success()
        } catch (e: Exception) {
            // 最常见的是后台启动前台服务被拒（ForegroundServiceStartNotAllowedException）
            Logger.d(
                ctx,
                TAG,
                "restart failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            Result.retry()
        }
    }
}
