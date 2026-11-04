package com.taostudio.tapaccounting.data.sync

import com.taostudio.tapaccounting.data.sync.protocol.Operation
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.SyncOperation
import com.taostudio.tapaccounting.data.sync.SharedLedgerService.Companion.isShareable
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class SharedOperationCodecTest {
    @Test fun `operation round trips strictly`() {
        val op = Operation(
            "123e4567-e89b-42d3-a456-426614174000", "delete", "bill",
            "123e4567-e89b-42d3-a456-426614174001", 3,
            "123e4567-e89b-42d3-a456-426614174002", "123e4567-e89b-42d3-a456-426614174003", 1
        )
        assertEquals(op, SharedOperationCodec.decode(SharedOperationCodec.encode(op)))
    }

    @Test fun `unknown actions are rejected`() {
        val raw = """{"operationId":"123e4567-e89b-42d3-a456-426614174000","type":"replace","entityType":"bill","entityId":"123e4567-e89b-42d3-a456-426614174001","revision":1,"deviceId":"123e4567-e89b-42d3-a456-426614174002","memberId":"123e4567-e89b-42d3-a456-426614174003","timestamp":1}"""
        assertNull(SharedOperationCodec.decode(raw))
    }

    @Test fun `fractional revision is rejected`() {
        val raw = """{"operationId":"123e4567-e89b-42d3-a456-426614174000","type":"delete","entityType":"bill","entityId":"123e4567-e89b-42d3-a456-426614174001","revision":1.5,"deviceId":"123e4567-e89b-42d3-a456-426614174002","memberId":"123e4567-e89b-42d3-a456-426614174003","timestamp":1}"""
        assertNull(SharedOperationCodec.decode(raw))
    }

    @Test fun `invalid bill payload is rejected`() {
        val payload = JsonObject().apply {
            addProperty("amount", -1)
            addProperty("type", 0)
            addProperty("subType", 0)
            addProperty("time", 1)
        }
        val op = Operation(
            "123e4567-e89b-42d3-a456-426614174000", "create", "bill",
            "123e4567-e89b-42d3-a456-426614174001", 1,
            "123e4567-e89b-42d3-a456-426614174002", "123e4567-e89b-42d3-a456-426614174003", 1, payload
        )
        assertNull(SharedOperationCodec.decode(SharedOperationCodec.encode(op)))
    }

    @Test fun `delete tombstone cannot be resurrected`() {
        val old = SyncOperation("old", 1, "bill", "entity", "delete", 2, "a", "member", null, 1)
        val incoming = Operation(
            "123e4567-e89b-42d3-a456-426614174000", "update", "bill",
            "123e4567-e89b-42d3-a456-426614174001", 3,
            "123e4567-e89b-42d3-a456-426614174002", "123e4567-e89b-42d3-a456-426614174003", 1
        )
        assertFalse(SharedSyncEngine.wins(old, incoming))
    }

    @Test fun `delete wins over an update with same or higher revision`() {
        val old = SyncOperation("old", 1, "budget", "entity", "update", 2, "z-device", "member", null, 1)
        val incoming = Operation(
            "123e4567-e89b-42d3-a456-426614174000", "delete", "budget",
            "123e4567-e89b-42d3-a456-426614174001", 3,
            "a-device", "123e4567-e89b-42d3-a456-426614174003", 1
        )

        assertTrue(SharedSyncEngine.wins(old, incoming))
    }

    @Test fun `stale delete loses to a newer update`() {
        val old = SyncOperation("old", 1, "budget", "entity", "update", 3, "z-device", "member", null, 1)
        val incoming = Operation(
            "123e4567-e89b-42d3-a456-426614174000", "delete", "budget",
            "123e4567-e89b-42d3-a456-426614174001", 2,
            "a-device", "123e4567-e89b-42d3-a456-426614174003", 1
        )

        assertFalse(SharedSyncEngine.wins(old, incoming))
    }

    @Test fun `isShareable only allows normal income-expense and refund and aligns with snapshot codec`() {
        val ledgerId = "123e4567-e89b-42d3-a456-426614174000"
        val memberId = "123e4567-e89b-42d3-a456-426614174001"
        val deviceId = "123e4567-e89b-42d3-a456-426614174002"

        val allTypes = listOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER, Bill.TYPE_REPAYMENT)
        val allSubTypes = listOf(
            Bill.SUBTYPE_NORMAL,
            Bill.SUBTYPE_REPAYMENT,
            Bill.SUBTYPE_REFUND,
            Bill.SUBTYPE_BALANCE_ADJUSTMENT,
            Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED,
            Bill.SUBTYPE_INVESTMENT_ESTIMATE
        )

        for (type in allTypes) {
            for (subType in allSubTypes) {
                val bill = Bill(
                    type = type,
                    subType = subType,
                    amount = 10.0,
                    time = 1000L,
                    sharedId = "123e4567-e89b-42d3-a456-426614174099",
                    memberId = memberId,
                    isShared = true
                )
                val expectedShareable = type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME) &&
                    subType in setOf(Bill.SUBTYPE_NORMAL, Bill.SUBTYPE_REFUND)
                assertEquals("type=$type subType=$subType", expectedShareable, bill.isShareable())

                val snapshot = SharedBillSnapshot(
                    ledgerId = ledgerId,
                    memberId = memberId,
                    deviceId = deviceId,
                    generatedAt = 1000L,
                    bills = listOf(bill)
                )
                val canEncodeSnapshot = runCatching { SharedBillSnapshotCodec.encode(snapshot) }.isSuccess
                assertEquals("snapshot alignment for type=$type subType=$subType", expectedShareable, canEncodeSnapshot)
            }
        }
    }
}
