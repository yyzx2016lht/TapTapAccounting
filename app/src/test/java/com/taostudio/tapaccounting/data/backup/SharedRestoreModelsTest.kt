package com.taostudio.tapaccounting.data.backup

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Book
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.data.local.entity.SharedLedger
import com.taostudio.tapaccounting.data.local.entity.SharedMember
import com.taostudio.tapaccounting.data.local.entity.SyncOperation
import com.taostudio.tapaccounting.data.local.entity.SyncQueue
import com.taostudio.tapaccounting.data.sync.SharedOperationCodec
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedRestoreModelsTest {
    @Test
    fun backupSnapshot_readsPortableSharedLedgerDtos() {
        val portable = portableLedger()

        assertEquals(
            listOf(portable),
            requireSharedLedgerBackups(listOf(portable))
        )
        assertThrows(IllegalArgumentException::class.java) {
            requireSharedLedgerBackups(listOf(fixture().ledger))
        }
    }

    @Test
    fun localCopy_removesSharedMarkersFromBillsAndBudgets() {
        val bill = Bill(
            type = Bill.TYPE_EXPENSE,
            amount = 12.0,
            time = 10,
            sharedId = ENTITY_ID,
            memberId = MEMBER_ID,
            isShared = true,
            sharedRevision = 3,
            sharedDeviceId = OLD_DEVICE_ID,
            relatedSharedId = RELATED_ENTITY_ID
        )
        val budget = Budget(
            bookName = "共同账本",
            categoryId = null,
            categoryName = null,
            yearMonth = "2026-08",
            amount = 100.0,
            createdAt = 1,
            updatedAt = 2,
            sharedId = ENTITY_ID,
            revision = 3,
            isShared = true,
            sharedDeviceId = OLD_DEVICE_ID,
            memberBudgetAllocations = "{}"
        )

        val restoredBill = sanitizeBillForRestore(bill, SharedRestoreMode.LOCAL_COPY)
        val restoredBudget = sanitizeBudgetForRestore(budget, SharedRestoreMode.LOCAL_COPY)

        assertFalse(restoredBill.isShared)
        assertNull(restoredBill.sharedId)
        assertNull(restoredBill.memberId)
        assertEquals(0L, restoredBill.sharedRevision)
        assertNull(restoredBill.sharedDeviceId)
        assertNull(restoredBill.relatedSharedId)
        assertFalse(restoredBudget.isShared)
        assertNull(restoredBudget.sharedId)
        assertEquals(0L, restoredBudget.revision)
        assertNull(restoredBudget.sharedDeviceId)
        assertNull(restoredBudget.memberBudgetAllocations)
    }

    @Test
    fun reconnect_preservesBusinessSharedMarkers() {
        val bill = Bill(
            type = Bill.TYPE_EXPENSE,
            amount = 12.0,
            time = 10,
            sharedId = ENTITY_ID,
            memberId = MEMBER_ID,
            isShared = true,
            sharedRevision = 3,
            sharedDeviceId = OLD_DEVICE_ID
        )

        assertEquals(bill, sanitizeBillForRestore(bill, SharedRestoreMode.RECONNECT))
    }

    @Test
    fun snapshot_usesStableIdsAndExcludesOutboxRuntimeState() {
        val fixture = fixture()

        val data = buildSharedRestoreData(
            books = listOf(fixture.book),
            ledgers = listOf(fixture.ledger),
            members = listOf(fixture.member),
            queue = listOf(fixture.queue),
            pendingOperations = listOf(fixture.storedOperation)
        )

        assertEquals("共同账本", data.ledgers.single().bookName)
        assertEquals(LEDGER_ID, data.members.single().ledgerUuid)
        assertEquals(LEDGER_ID, data.pendingQueue.single().ledgerUuid)
        assertEquals(OPERATION_ID, data.pendingOperations.single().operationId)
        val exportedJson = Gson().toJson(data)
        assertFalse(exportedJson.contains(OLD_DEVICE_ID))
        assertFalse(data.pendingOperations.single().payloadJson.orEmpty().contains("categoryId"))
        assertFalse(data.pendingOperations.single().payloadJson.orEmpty().contains("relatedBillId"))
        assertFalse(exportedJson.contains("retryCount"))
        assertFalse(exportedJson.contains("lastError"))
        assertFalse(exportedJson.contains("old-device-path"))
    }

    @Test
    fun snapshot_normalizesBudgetCategoryKeyWithoutChangingItsScope() {
        val fixture = fixture()
        val payload = JsonObject().apply {
            addProperty("amount", 100.0)
            addProperty("yearMonth", "2026-08")
            addProperty("alertThreshold", 0.8)
            addProperty("categoryKey", 42L)
            addProperty("categoryId", 42L)
            addProperty("bookId", 10L)
            addProperty("sharedDeviceId", OLD_DEVICE_ID)
        }
        val wire = Operation(
            operationId = OPERATION_ID,
            type = "update",
            entityType = "budget",
            entityId = ENTITY_ID,
            revision = 2,
            deviceId = OLD_DEVICE_ID,
            memberId = MEMBER_ID,
            timestamp = 100,
            payload = payload
        )
        val raw = SharedOperationCodec.encode(wire)
        val data = buildSharedRestoreData(
            books = listOf(fixture.book),
            ledgers = listOf(fixture.ledger),
            members = listOf(fixture.member),
            queue = listOf(fixture.queue.copy(operationJson = raw)),
            pendingOperations = listOf(
                fixture.storedOperation.copy(entityType = "budget", payload = raw)
            )
        )

        val portable = JsonParser.parseString(data.pendingOperations.single().payloadJson).asJsonObject
        assertEquals(-1L, portable.get("categoryKey").asLong)
        assertFalse(portable.has("categoryId"))
        assertFalse(portable.has("bookId"))
        assertFalse(portable.has("sharedDeviceId"))
    }

    @Test
    fun reconnect_rewritesDeviceAndResetsQueueRuntimeState() {
        val fixture = fixture()
        val data = buildSharedRestoreData(
            books = listOf(fixture.book),
            ledgers = listOf(fixture.ledger),
            members = listOf(fixture.member),
            queue = listOf(fixture.queue),
            pendingOperations = listOf(fixture.storedOperation)
        )
        validateSharedReconnect(data, NEW_DEVICE_ID, setOf("共同账本"))

        val (operation, queue) = materializePendingOperation(
            operation = data.pendingOperations.single(),
            queue = data.pendingQueue.single(),
            ledgerId = 99,
            ledgerRemotePath = "/shared-ledger/$LEDGER_ID",
            newDeviceId = NEW_DEVICE_ID
        )

        assertEquals(99L, operation.ledgerId)
        assertEquals(NEW_DEVICE_ID, operation.deviceId)
        assertEquals(NEW_DEVICE_ID, SharedOperationCodec.decode(queue.operationJson)?.deviceId)
        assertEquals(0, queue.retryCount)
        assertNull(queue.lastError)
        assertTrue(queue.remotePath.contains(NEW_DEVICE_ID))
        assertFalse(queue.remotePath.contains(OLD_DEVICE_ID))
    }

    @Test
    fun reconnect_rejectsIncompleteOutbox() {
        val fixture = fixture()
        val data = SharedRestoreData(
            ledgers = listOf(portableLedger()),
            members = listOf(portableMember()),
            pendingQueue = listOf(
                PendingSyncQueueBackup(OPERATION_ID, LEDGER_ID, fixture.queue.createdAt)
            ),
            pendingOperations = emptyList()
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateSharedReconnect(data, NEW_DEVICE_ID, emptySet())
        }
    }

    @Test
    fun guard_onlyBlocksRestoresThatWriteSharedEntities() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSharedRestoreGuard(
                hasActiveSharedLedgers = true,
                writesSharedEntities = true
            )
        }
        requireSharedRestoreGuard(hasActiveSharedLedgers = true, writesSharedEntities = false)
        requireSharedRestoreGuard(hasActiveSharedLedgers = false, writesSharedEntities = true)
        requireSharedRestoreGuard(
            hasActiveSharedLedgers = true,
            writesSharedEntities = true,
            sharedRestoreMode = SharedRestoreMode.RECONNECT,
            reconnectsExistingLedgers = true
        )
        assertThrows(IllegalArgumentException::class.java) {
            requireSharedRestoreGuard(
                hasActiveSharedLedgers = true,
                writesSharedEntities = true,
                sharedRestoreMode = SharedRestoreMode.RECONNECT,
                reconnectsExistingLedgers = false
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireSharedRestoreGuard(
                hasActiveSharedLedgers = true,
                writesSharedEntities = true,
                sharedRestoreMode = SharedRestoreMode.LOCAL_COPY,
                reconnectsExistingLedgers = true
            )
        }
    }

    @Test
    fun decision_ignoresSharedStateWhenSelectionIsLocalOnly() {
        assertEquals(
            SharedRestoreDecision.LOCAL_COPY,
            decideSharedRestore(
                writesSharedEntities = false,
                backupReadiness = SharedRecoveryReadiness.READY,
                deviceHasActiveLedger = true,
                backupLedgersAlreadyOnDevice = true
            )
        )
        assertEquals(
            SharedRestoreDecision.LOCAL_COPY,
            decideSharedRestore(
                writesSharedEntities = false,
                backupReadiness = SharedRecoveryReadiness.INCOMPLETE,
                deviceHasActiveLedger = false,
                backupLedgersAlreadyOnDevice = false
            )
        )
    }

    @Test
    fun decision_blocksSharedWritesWhileLedgerIsStillConnected() {
        assertEquals(
            SharedRestoreDecision.BLOCKED_BY_ACTIVE_LEDGER,
            decideSharedRestore(
                writesSharedEntities = true,
                backupReadiness = SharedRecoveryReadiness.READY,
                deviceHasActiveLedger = true,
                backupLedgersAlreadyOnDevice = false
            )
        )
        assertEquals(
            SharedRestoreDecision.BLOCKED_BY_ACTIVE_LEDGER,
            decideSharedRestore(
                writesSharedEntities = true,
                backupReadiness = SharedRecoveryReadiness.NOT_PRESENT,
                deviceHasActiveLedger = true,
                backupLedgersAlreadyOnDevice = false
            )
        )
        assertEquals(
            SharedRestoreDecision.BLOCKED_BY_ACTIVE_LEDGER,
            decideSharedRestore(
                writesSharedEntities = true,
                backupReadiness = SharedRecoveryReadiness.INCOMPLETE,
                deviceHasActiveLedger = true,
                backupLedgersAlreadyOnDevice = true
            )
        )
    }

    @Test
    fun decision_allowsReconnectWhenEveryBackupLedgerIsStillOnThisDevice() {
        assertEquals(
            SharedRestoreDecision.ASK_SHARED_MODE,
            decideSharedRestore(
                writesSharedEntities = true,
                backupReadiness = SharedRecoveryReadiness.READY,
                deviceHasActiveLedger = true,
                backupLedgersAlreadyOnDevice = true
            )
        )
    }

    @Test
    fun decision_asksAboutSharedModeOnlyForResumableIdentity() {
        assertEquals(
            SharedRestoreDecision.ASK_SHARED_MODE,
            decideSharedRestore(
                writesSharedEntities = true,
                backupReadiness = SharedRecoveryReadiness.READY,
                deviceHasActiveLedger = false,
                backupLedgersAlreadyOnDevice = false
            )
        )
        assertEquals(
            SharedRestoreDecision.ASK_INCOMPLETE_IDENTITY,
            decideSharedRestore(
                writesSharedEntities = true,
                backupReadiness = SharedRecoveryReadiness.INCOMPLETE,
                deviceHasActiveLedger = false,
                backupLedgersAlreadyOnDevice = false
            )
        )
        assertEquals(
            SharedRestoreDecision.LOCAL_COPY,
            decideSharedRestore(
                writesSharedEntities = true,
                backupReadiness = SharedRecoveryReadiness.NOT_PRESENT,
                deviceHasActiveLedger = false,
                backupLedgersAlreadyOnDevice = false
            )
        )
    }

    private fun fixture(): Fixture {
        val book = Book(id = 10, name = "共同账本")
        val ledger = SharedLedger(
            id = 20,
            uuid = LEDGER_ID,
            bookId = book.id,
            name = "共同账本",
            webdavUrl = "https://dav.example.test",
            webdavUser = "user",
            remotePath = "/shared-ledger/$LEDGER_ID",
            localMemberId = MEMBER_ID,
            createdAt = 1
        )
        val member = SharedMember(
            id = 30,
            ledgerId = ledger.id,
            memberId = MEMBER_ID,
            displayName = "本机",
            joinOrder = 1,
            isLocal = true
        )
        val payload = JsonObject().apply {
            addProperty("amount", 12.0)
            addProperty("type", Bill.TYPE_EXPENSE)
            addProperty("subType", Bill.SUBTYPE_NORMAL)
            addProperty("time", 100L)
            addProperty("categoryId", 42L)
            addProperty("relatedBillId", 43L)
            addProperty("sharedDeviceId", OLD_DEVICE_ID)
        }
        val wire = Operation(
            operationId = OPERATION_ID,
            type = "update",
            entityType = "bill",
            entityId = ENTITY_ID,
            revision = 2,
            deviceId = OLD_DEVICE_ID,
            memberId = MEMBER_ID,
            timestamp = 100,
            payload = payload
        )
        val raw = SharedOperationCodec.encode(wire)
        return Fixture(
            book = book,
            ledger = ledger,
            member = member,
            queue = SyncQueue(
                operationId = OPERATION_ID,
                ledgerId = ledger.id,
                operationJson = raw,
                remotePath = "old-device-path",
                createdAt = 100,
                retryCount = 7,
                lastError = "network"
            ),
            storedOperation = SyncOperation(
                operationId = OPERATION_ID,
                ledgerId = ledger.id,
                entityType = "bill",
                entityId = ENTITY_ID,
                action = "update",
                revision = 2,
                deviceId = OLD_DEVICE_ID,
                memberId = MEMBER_ID,
                payload = raw,
                appliedAt = 100
            )
        )
    }

    private fun portableLedger() = SharedLedgerBackup(
        uuid = LEDGER_ID,
        bookName = "共同账本",
        name = "共同账本",
        webdavUrl = "https://dav.example.test",
        webdavUser = "user",
        remotePath = "/shared-ledger/$LEDGER_ID",
        localMemberId = MEMBER_ID,
        createdAt = 1
    )

    private fun portableMember() = SharedMemberBackup(
        ledgerUuid = LEDGER_ID,
        memberId = MEMBER_ID,
        displayName = "本机",
        joinOrder = 1,
        isLocal = true
    )

    private data class Fixture(
        val book: Book,
        val ledger: SharedLedger,
        val member: SharedMember,
        val queue: SyncQueue,
        val storedOperation: SyncOperation
    )

    companion object {
        private const val LEDGER_ID = "11111111-1111-4111-8111-111111111111"
        private const val MEMBER_ID = "22222222-2222-4222-8222-222222222222"
        private const val OPERATION_ID = "33333333-3333-4333-8333-333333333333"
        private const val ENTITY_ID = "44444444-4444-4444-8444-444444444444"
        private const val RELATED_ENTITY_ID = "55555555-5555-4555-8555-555555555555"
        private const val OLD_DEVICE_ID = "66666666-6666-4666-8666-666666666666"
        private const val NEW_DEVICE_ID = "77777777-7777-4777-8777-777777777777"
    }
}
