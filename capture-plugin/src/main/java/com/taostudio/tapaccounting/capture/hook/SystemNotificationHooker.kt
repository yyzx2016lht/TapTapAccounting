package com.taostudio.tapaccounting.capture.hook

import android.app.AndroidAppHelper
import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.relay.RelayServer
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统通知 hook，等价于 AutoAccounting 的 AndroidHooker + NotificationHooker。
 *
 * 运行在 system_server（packageName=android）。微信/支付宝/银行 App 的“已支付”
 * 通知在这里统一截获，规则按 NOTICE 解析。它不依赖插件 App 进程，也不依赖
 * 通知使用权，是自动记账“开机即可用”的关键通道。
 *
 * 注意：所有回调都必须吞掉 Throwable，绝不能影响 system_server。
 */
internal object SystemNotificationHooker {

    private const val TAG = "TapCaptureNotice"
    private const val DEDUP_TTL_MS = 60_000L
    private const val MAX_SEEN = 2048

    /** AutoAccounting DefaultData.APP_FILTER 的完整白名单。 */
    private val WHITELIST: Set<String> = setOf(
        "cmb.pb",
        "cn.gov.pbc.dcep",
        "com.sankuai.meituan",
        "com.unionpay",
        "com.tencent.mm",
        "com.eg.android.AlipayGphone",
        "com.jingdong.app.mall",
        "com.taobao.taobao",
        "com.xunmeng.pinduoduo",
        "com.sankuai.waimai",
        "me.ele",
        "com.icbc",
        "com.huawei.wallet",
        "com.mipay.wallet",
        "com.oppo.wallet",
        "com.coloros.wallet",
        "com.vivo.wallet",
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile",
        "com.sdu.didi.psnger",
        "com.wudaokou.hippo",
        "com.ss.android.ugc.aweme",
        "com.smile.gifmaker",
        "com.achievo.vipshop",
        "com.suning.mobile.ebuy",
        "com.xiaomi.youpin",
        "com.jd.jrapp",
        "com.baidu.wallet",
        "com.greenpoint.android.mc10086",
        "com.sinovatech.unicom.ui",
        "com.ct.client",
        "com.chinamworld.main",
        "com.android.bankabc",
        "com.chinamworld.bocmbci",
        "com.bankcomm.Bankcomm",
        "com.yitong.mbank.psbc",
        "com.pingan.papd",
        "com.ecitic.bank.mobile",
        "cn.com.cmbc.newmbank",
        "com.cebbank.mobile.cemb",
        "com.cib.cibmb",
        "cn.com.spdb.mobilebank.per",
        "com.spdbccc.app",
        "com.cgbchina.xpt",
        "com.hxb.mobile.client",
        "com.bankofbeijing.mobilebanking",
        "cn.com.shbank.mper",
        "com.nbbank.mobilebank",
        "com.webank.wemoney",
        "com.mybank.android.phone",
    )

    private val seen = ConcurrentHashMap<String, Long>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookEnqueueNotification(lpparam)
        hookNotificationPosted(lpparam)
        // system_server 是最持久的宿主之一；能抢到 52046 就由它跑 relay。
        val context = systemContext()
        if (context != null) {
            RelayServer.ensureStarted(context)
        } else {
            log("relay context unavailable in system_server")
        }
    }

    /** Android 8+ 的通知入队入口。 */
    private fun hookEnqueueNotification(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClassIfExists(
                "com.android.server.notification.NotificationManagerService",
                lpparam.classLoader,
            ) ?: run {
                log("NotificationManagerService not found")
                return
            }
            var count = 0
            clazz.declaredMethods
                .filter { it.name == "enqueueNotificationInternal" }
                .forEach { method ->
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val pkg = param.args.firstOrNull { it is String } as? String
                                        ?: return
                                    val notification = param.args
                                        .firstOrNull { it is Notification } as? Notification
                                        ?: return
                                    process(pkg, notification)
                                } catch (t: Throwable) {
                                    log("enqueue handle failed: ${t.javaClass.simpleName}: ${t.message}")
                                }
                            }
                        })
                        count++
                    } catch (_: Throwable) {
                        // 单个重载失败不影响其他版本。
                    }
                }
            log("hook installed: enqueueNotificationInternal methods=$count")
        } catch (t: Throwable) {
            log("hook unavailable: enqueueNotificationInternal (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    /** 通知发布给 listener 的入口；不同 Android 版本都存在。 */
    private fun hookNotificationPosted(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClassIfExists(
                "com.android.server.notification.NotificationManagerService",
                lpparam.classLoader,
            ) ?: return
            var count = 0
            clazz.declaredMethods
                .filter { it.name == "notifyCallNotificationEventListenerOnPosted" }
                .forEach { method ->
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val record = param.args.firstOrNull() ?: return
                                    val sbn = XposedHelpers.getObjectField(record, "sbn")
                                        as? StatusBarNotification
                                        ?: return
                                    val notification = sbn.notification ?: return
                                    process(sbn.packageName, notification)
                                } catch (t: Throwable) {
                                    log("posted handle failed: ${t.javaClass.simpleName}: ${t.message}")
                                }
                            }
                        })
                        count++
                    } catch (_: Throwable) {
                        // 忽略单个重载。
                    }
                }
            log("hook installed: notifyCallNotificationEventListenerOnPosted methods=$count")
        } catch (t: Throwable) {
            log("hook unavailable: notifyCallNotificationEventListenerOnPosted (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    private fun process(pkg: String, notification: Notification) {
        val normalizedPkg = pkg.trim()
        if (normalizedPkg.isEmpty() || normalizedPkg !in WHITELIST) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                ?: ""
            ).toString()
        if (title.isEmpty() && text.isEmpty()) return

        val key = md5("$normalizedPkg|$title|$text")
        if (!markSeen(key)) {
            log("skip duplicate pkg=$normalizedPkg")
            return
        }

        val source = when (normalizedPkg) {
            "com.tencent.mm" -> CaptureSource.WECHAT
            "com.eg.android.AlipayGphone" -> CaptureSource.ALIPAY
            else -> normalizedPkg.lowercase(Locale.US)
        }
        val rawJson = JSONObject()
            .put("title", title)
            .put("text", text)
            .put("t", System.currentTimeMillis())
            .toString()

        log("notification pkg=$normalizedPkg title=${title.take(60)} text=${text.take(100)}")
        CaptureForwarder.forward(
            source = source,
            rawJson = rawJson,
            merchant = title.takeIf { it.isNotBlank() },
            dataType = "NOTICE",
            contextOverride = systemContext() ?: AndroidAppHelper.currentApplication(),
        )
    }

    /**
     * system_server 没有 AndroidAppHelper.currentApplication()，用
     * ActivityThread.getSystemContext() 取一个系统 Context 来发广播。
     */
    private fun systemContext(): Context? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val thread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
            ?: XposedHelpers.callStaticMethod(activityThreadClass, "systemMain")
        XposedHelpers.callMethod(thread, "getSystemContext") as? Context
    } catch (_: Throwable) {
        null
    }

    private fun markSeen(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (seen.size > MAX_SEEN) {
            seen.entries.removeAll { now - it.value > DEDUP_TTL_MS }
        }
        val previous = seen.put(key, now)
        return previous == null || now - previous > DEDUP_TTL_MS
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun log(message: String) {
        try {
            XposedBridge.log("[$TAG] $message")
        } catch (_: Throwable) {
            // ignore
        }
    }
}
