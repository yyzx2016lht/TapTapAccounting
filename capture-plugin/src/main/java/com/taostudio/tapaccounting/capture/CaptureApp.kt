package com.taostudio.tapaccounting.capture

import android.app.Application
import android.content.Context
import com.taostudio.tapaccounting.capture.data.AppDatabase
import com.taostudio.tapaccounting.capture.data.CaptureEventRepository
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine

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
    }

    companion object {
        fun from(context: Context): CaptureApp = context.applicationContext as CaptureApp
    }
}
