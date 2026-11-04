package com.taostudio.tapaccounting.capture.hook

/**
 * 微信支付成功页的短时缓存。
 *
 * 金额/支付方式/收款方经常不在同一条消息里：MMKR RichText 先出现，WCDB 消息随后落库。
 * 这里缓存最近 120 秒的值，由下一条 WCDB/WebView 事件附带上报；不单独 forward。
 */
internal object WechatRichCache {

    private const val TTL_MS = 120_000L

    private data class Entry(val text: String, val at: Long)

    data class Snapshot(
        val payMoney: String?,
        val payTools: String?,
        val payShop: String?,
        val at: Long,
    )

    private val lock = Any()
    private var money: Entry? = null
    private var tools: Entry? = null
    private var shop: Entry? = null

    fun record(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        val now = System.currentTimeMillis()

        synchronized(lock) {
            if (ProbeText.looksLikeAmount(value)) {
                money = Entry(value, now)
            }
            if (ProbeText.findPaymentMethod(value) != null) {
                tools = Entry(value, now)
            }
            if (ProbeText.looksLikeShop(value)) {
                shop = Entry(value, now)
            }
        }
    }

    fun snapshot(now: Long = System.currentTimeMillis()): Snapshot? {
        synchronized(lock) {
            val currentMoney = money?.takeIf { now - it.at <= TTL_MS }?.text
            val currentTools = tools?.takeIf { now - it.at <= TTL_MS }?.text
            val currentShop = shop?.takeIf { now - it.at <= TTL_MS }?.text
            if (currentMoney == null && currentTools == null && currentShop == null) {
                return null
            }
            return Snapshot(currentMoney, currentTools, currentShop, now)
        }
    }

    /** 消费掉当前缓存的收款方名称，避免数十秒后的退款或群消息误用旧收款方。 */
    fun clearShop() {
        synchronized(lock) {
            shop = null
        }
    }
}
