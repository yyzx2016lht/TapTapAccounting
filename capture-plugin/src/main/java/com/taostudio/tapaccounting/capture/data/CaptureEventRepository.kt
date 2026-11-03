package com.taostudio.tapaccounting.capture.data

import java.security.MessageDigest

class CaptureEventRepository(private val dao: CaptureEventDao) {

    suspend fun insert(
        source: String,
        rawType: String,
        rawJson: String,
        receivedAt: Long,
        matched: Boolean,
        ruleName: String?,
        autoType: String?,
        localType: Int?,
        localSubType: Int?,
        amountCents: Long?,
        merchant: String?,
        categoryName: String?,
        channel: String?,
    ): Long {
        val hash = md5(rawJson)
        return dao.insertIgnore(
            CaptureEvent(
                source = source,
                rawType = rawType,
                rawJson = rawJson,
                receivedAt = receivedAt,
                matched = matched,
                ruleName = ruleName,
                autoType = autoType,
                localType = localType,
                localSubType = localSubType,
                amountCents = amountCents,
                merchant = merchant,
                categoryName = categoryName,
                channel = channel,
                rawHash = hash,
            )
        )
    }

    suspend fun listRecent(limit: Int = 500): List<CaptureEvent> = dao.listRecent(limit)

    suspend fun count(): Int = dao.count()

    suspend fun countMatched(): Int = dao.countMatched()

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
