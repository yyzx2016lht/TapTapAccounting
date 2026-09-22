package com.taostudio.tapaccounting.data.sync

import com.taostudio.tapaccounting.data.sync.protocol.Operation
import com.taostudio.tapaccounting.data.local.entity.SyncOperation
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
}
