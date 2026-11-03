package com.taostudio.tapaccounting.capture.hook

import org.json.JSONObject

/**
 * 微信 WCDB 原始数据 -> AutoAccounting 规则要求的 envelope。
 *
 * 单独抽出来是为了：
 * - hook 进程和调试 UI 都能复用同一套构造逻辑；
 * - 不依赖 Xposed，插件进程可以直接跑“模拟 WCDB XML”测试。
 */
internal object WechatEnvelope {

    /** 消息盒子 -> {"mMap": {...}}，对应规则 143/145/146 等。 */
    fun mmapFromXml(
        content: String,
        now: Long,
        cache: WechatRichCache.Snapshot?,
    ): String {
        val mMap = JSONObject().apply {
            put("description", ProbeText.xmlTagText(content, "des") ?: "")
            put("title", ProbeText.xmlTagText(content, "title") ?: "")
            put(
                "display_name",
                ProbeText.xmlTagText(content, "display_name")
                    ?: ProbeText.xmlTagText(content, "nickname")
                    ?: "",
            )
            put("source", ProbeText.xmlTagText(content, "appname") ?: "")
            put("t", now)
            putCacheFields(this, cache)
        }
        return JSONObject().put("mMap", mMap).toString()
    }

    /** 微信转账 -> {"type":"transfer","content":"<msg/appmsg/wcpayinfo JSON>"} */
    fun transferFromXml(
        content: String,
        isSend: Int?,
        cache: WechatRichCache.Snapshot?,
        talker: String?,
        now: Long,
    ): String {
        val contentJson = JSONObject().put(
            "msg",
            JSONObject().put(
                "appmsg",
                JSONObject()
                    .put("wcpayinfo", extractWcpayInfo(content))
                    .put("title", "微信转账")
                    .put("type", "2000"),
            ),
        )
        return JSONObject().apply {
            put("type", "transfer")
            isSend?.let { put("isSend", it) }
            put("content", contentJson.toString())
            put("hookerUser", cache?.payShop ?: talker ?: "")
            put("t", now)
            putCacheFields(this, cache)
        }.toString()
    }

    /** 微信红包 -> {"type":"redPackage","content":"<msg/appmsg/wcpayinfo JSON>"} */
    fun redPacketFromXml(
        content: String,
        isSend: Int?,
        cache: WechatRichCache.Snapshot?,
        talker: String?,
        now: Long,
    ): String {
        val contentJson = JSONObject().put(
            "msg",
            JSONObject().put(
                "appmsg",
                JSONObject().put("wcpayinfo", extractWcpayInfo(content)),
            ),
        )
        return JSONObject().apply {
            put("type", "redPackage")
            isSend?.let { put("isSend", it) }
            put("content", contentJson.toString())
            put("hookerUser", cache?.payShop ?: talker ?: "")
            put("t", now)
            putCacheFields(this, cache)
        }.toString()
    }

    /** 微信群收款 -> {"type":"groupCollection","content":{"mMap":{"content":"..."}}} */
    fun groupFromXml(
        content: String,
        now: Long,
        cache: WechatRichCache.Snapshot?,
    ): String {
        val groupText = ProbeText.xmlTagText(content, "content") ?: content
        val mMap = JSONObject().put("content", groupText)
        return JSONObject().apply {
            put("type", "groupCollection")
            put("content", JSONObject().put("mMap", mMap))
            put("t", now)
            putCacheFields(this, cache)
        }.toString()
    }

    /** 从 XML 或 JSON 里提取 wcpayinfo（转账/红包共用）。 */
    private fun extractWcpayInfo(content: String): JSONObject {
        if (content.trim().startsWith("{")) {
            runCatching {
                val root = JSONObject(content)
                root.optJSONObject("msg")
                    ?.optJSONObject("appmsg")
                    ?.optJSONObject("wcpayinfo")
                    ?.let { return it }
            }
        }
        val out = JSONObject()
        listOf(
            "feedesc",
            "pay_memo",
            "paysubtype",
            "transferid",
            "transcationid",
            "begintransfertime",
            "sendertitle",
            "payer_username",
            "receiver_username",
        ).forEach { key ->
            ProbeText.xmlTagText(content, key)?.let { out.put(key, it) }
        }
        return out
    }

    private fun putCacheFields(target: JSONObject, cache: WechatRichCache.Snapshot?) {
        if (cache == null) return
        target.put("cachedPayMoney", cache.payMoney ?: "")
        target.put("cachedPayTools", cache.payTools ?: "")
        target.put("cachedPayShop", cache.payShop ?: "")
        target.put("cachedAt", cache.at)
    }
}
