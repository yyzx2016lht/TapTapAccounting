package com.taostudio.tapaccounting.capture

import android.app.Application
import android.content.Context
import com.taostudio.tapaccounting.capture.bridge.RuleLearningManager
import com.taostudio.tapaccounting.capture.data.AppDatabase
import com.taostudio.tapaccounting.capture.data.CaptureEventRepository
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import com.taostudio.tapaccounting.capture.service.PluginDirectServer

class CaptureApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: CaptureRepository by lazy { CaptureRepository(database.paymentCaptureDao()) }
    val eventRepository: CaptureEventRepository by lazy {
        CaptureEventRepository(database.captureEventDao())
    }

    override fun onCreate() {
        super.onCreate()
        // 规则引擎只在插件进程初始化；不要在 hook 进程加载 QuickJS。
        AutoAccountingRuleEngine.init(this)
        // 渠道在进程启动时创建；即使通知权限被拒，也只是不显示，不影响落库。
        CaptureNotifier.ensureChannel(this)
        // 启动插件进程内直连 TCP 服务（127.0.0.1:52047），供微信/支付宝毫秒级零延迟投递。
        PluginDirectServer.ensureStarted(this)
        // 补跑上次入账后没来得及完成的 AI 规则学习（失败重试最多 2 次）。
        RuleLearningManager.processPendingInBackground(this)
    }

    companion object {
        fun from(context: Context): CaptureApp = context.applicationContext as CaptureApp
    }
}
