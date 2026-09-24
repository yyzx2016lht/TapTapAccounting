package com.taostudio.tapaccounting.data.sync

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import com.taostudio.tapaccounting.data.sync.protocol.StrictJsonParser

object SharedOperationCodec {
    private val gson = Gson()
    fun encode(value: Operation): String = gson.toJson(value)
    fun decode(value: String): Operation? = runCatching {
        require(value.length <= 262_144)
        val root = JsonParser.parseString(value).asJsonObject
        val revision = StrictJsonParser.parseLong(root.get("revision"), "revision") ?: return null
        val timestamp = StrictJsonParser.parseLong(root.get("timestamp"), "timestamp") ?: return null
        val op = gson.fromJson(root, Operation::class.java)
        op.takeIf {
            it.revision == revision && it.timestamp == timestamp &&
                Operation.UUID_PATTERN.matches(it.operationId) && Operation.UUID_PATTERN.matches(it.entityId) &&
                Operation.UUID_PATTERN.matches(it.deviceId) && Operation.UUID_PATTERN.matches(it.memberId) &&
                it.type in setOf("create", "update", "delete") && it.entityType in setOf("bill", "budget") &&
                it.revision > 0 && it.timestamp >= 0 && validPayload(it)
        }
    }.getOrNull()

    private fun validPayload(op: Operation): Boolean = runCatching {
        if (op.type == "delete") return@runCatching true
        val payload = op.payload ?: return@runCatching false
        val amount = payload.get("amount")?.asDouble ?: return@runCatching false
        if (!amount.isFinite() || amount < 0) return@runCatching false
        when (op.entityType) {
            "bill" -> StrictJsonParser.parseInt(payload.get("type"), "bill.type") in setOf(0, 1) &&
                // P1-12: 接受全部合法 subType（0..5），避免丢弃还款/平账/理财估算等
                (StrictJsonParser.parseInt(payload.get("subType"), "bill.subType") ?: -1) in 0..5 &&
                (StrictJsonParser.parseLong(payload.get("time"), "bill.time") ?: -1) >= 0
            "budget" -> Regex("^\\d{4}-(0[1-9]|1[0-2])$").matches(payload.get("yearMonth")?.asString.orEmpty()) &&
                (payload.get("alertThreshold")?.asDouble ?: -1.0) in 0.0..1.0
            else -> false
        }
    }.getOrDefault(false)
}
