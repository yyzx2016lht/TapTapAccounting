package com.taostudio.tapaccounting.capture

import android.content.Context
import com.taostudio.tapaccounting.capture.data.CaptureEventRepository
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.data.local.AppDatabase

/**
 * P2 起待记 UI/气泡都运行在主应用进程内。
 *
 * 插件时代的调用点大量使用 `CaptureApp.from(context).repository/database`；
 * 迁移到主应用后保留这个轻量入口，内部直接指向主应用 Room，不再有独立插件进程/数据库。
 */
class CaptureApp private constructor(private val context: Context) {

    val database: AppDatabase get() = AppDatabase.getDatabase(context)

    val repository: CaptureRepository
        get() = CaptureRepository(database.paymentCaptureDao())

    val eventRepository: CaptureEventRepository
        get() = CaptureEventRepository(database.captureEventDao())

    companion object {
        fun from(context: Context): CaptureApp =
            CaptureApp(context.applicationContext ?: context)
    }
}
