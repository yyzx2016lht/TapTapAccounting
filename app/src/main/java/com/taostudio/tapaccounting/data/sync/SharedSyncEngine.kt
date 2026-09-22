package com.taostudio.tapaccounting.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.data.local.entity.SharedMember
import com.taostudio.tapaccounting.data.local.entity.SyncedRemoteFile
import com.taostudio.tapaccounting.data.local.entity.SyncOperation
import com.taostudio.tapaccounting.data.local.entity.SyncState
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import com.taostudio.tapaccounting.data.sync.protocol.ManifestValidator
import com.taostudio.tapaccounting.widget.ExpenseWidgetUpdater
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.util.UUID

data class SharedSyncRunResult(
    val attemptedLedgerCount: Int,
    val failedLedgerCount: Int
)

class SharedSyncEngine(private val context: Context, private val db: AppDatabase) {
    private val gson = Gson()
    private val webDav = SharedWebDavClient()

    suspend fun syncAll(forceFull: Boolean = false) = SharedSyncGate.global.run {
        val ledgers = db.sharedLedgerDao().getAll()
        var attemptedLedgerCount = 0
        var failedLedgerCount = 0
        ledgers.forEachIndexed { index, ledger ->
            val state = db.syncStateDao().get(ledger.id)
            val pendingUploadCount = db.syncQueueDao().count(ledger.id)
            val mode = SharedSyncPolicy.backgroundMode(
                pendingUploadCount = pendingUploadCount,
                lastSyncTime = state?.lastSyncTime ?: 0L,
                now = System.currentTimeMillis(),
                forceFull = forceFull,
                hasLastError = !state?.lastError.isNullOrBlank()
            )
            if (mode != SharedSyncPolicy.BackgroundMode.SKIP) {
                attemptedLedgerCount++
                runCatching {
                    syncLedgerSerial(
                        ledgerId = ledger.id,
                        fullSync = mode == SharedSyncPolicy.BackgroundMode.FULL
                    )
                }.onFailure { failedLedgerCount++ }
            }
            if (index < ledgers.lastIndex) delay(INTER_LEDGER_DELAY_MS)
        }
        SharedSyncRunResult(attemptedLedgerCount, failedLedgerCount)
    }

    suspend fun syncLedger(ledgerId: Long) = SharedSyncGate.global.run {
        syncLedgerSerial(ledgerId, fullSync = true)
    }

    private suspend fun syncLedgerSerial(ledgerId: Long, fullSync: Boolean) {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: return
        val current = db.syncStateDao().get(ledgerId) ?: SyncState(ledgerId, com.taostudio.tapaccounting.DeviceIdManager.getDeviceId(context))
        try {
            val password = SharedCredentials.load(context, ledger.uuid) ?: error("缺少 WebDAV 应用密码")
            val config = SharedWebDavClient.Config(ledger.webdavUrl, ledger.webdavUser, password)
            throwIfCoolingDown(config)
            db.syncStateDao().save(current.copy(isSyncing = true, lastError = null))
            val localBookName = db.bookDao().getById(ledger.bookId)?.name ?: error("本地账本不存在")
            // Authorization is checked before every upload, not only during periodic full pulls.
            if (webDav.exists(config, "${ledger.remotePath}/closed.json")) {
                SharedLedgerService(context, db).exitKeepingLocalCopy(ledgerId, syncFirst = false)
                return
            }
            refreshMemberNames(ledger.id, ledger.uuid, ledger.remotePath, config)
            enqueueMissingIcons(ledger.id, ledger.uuid, ledger.localMemberId, localBookName)
            webDav.ensureDirectory(config, "${ledger.remotePath}/operations")
            val queuedBatches = db.syncQueueDao().getByLedgerId(ledgerId).chunked(500)
            val hasQueuedBillChanges = queuedBatches.asSequence().flatten().any { queued ->
                SharedOperationCodec.decode(queued.operationJson)?.entityType == "bill"
            }
            if (queuedBatches.isNotEmpty()) webDav.ensureDirectory(config, "${ledger.remotePath}/operations/batches")
            queuedBatches.forEach { batch ->
                try {
                    val first = SharedOperationCodec.decode(batch.first().operationJson) ?: error("本地共享操作无效")
                    val batchId = UUID.nameUUIDFromBytes(batch.joinToString("|") { it.operationId }.toByteArray()).toString()
                    val month = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(batch.first().createdAt))
                    val path = "${ledger.remotePath}/operations/batches/$month/${first.deviceId}/$batchId.json.gz"
                    if (!webDav.exists(config, path)) {
                        webDav.ensureDirectory(config, path.substringBeforeLast('/'))
                        webDav.putGzip(config, path, SharedOperationBundleCodec.encode(batch.map { it.operationJson }))
                    }
                    val relative = path.removePrefix("${ledger.remotePath}/operations/")
                    db.syncedRemoteFileDao().markProcessed(
                        SyncedRemoteFile(ledgerId, relative, System.currentTimeMillis())
                    )
                    batch.forEach { db.syncQueueDao().delete(it.operationId) }
                } catch (e: Exception) {
                    batch.forEach { db.syncQueueDao().markFailure(it.operationId, e.message ?: "上传失败") }
                    throw e
                }
            }
            if (hasQueuedBillChanges) {
                uploadLocalBillSnapshot(ledger.id, ledger.uuid, ledger.remotePath, ledger.localMemberId, localBookName, config)
            }
            if (fullSync) {
                val files = webDav.listOperations(config, "${ledger.remotePath}/operations")
                val pendingFiles = RemoteOperationPlanner.pendingFiles(
                    remoteFiles = files.filter { it.substringAfterLast('/').removeSuffix(".json") != "meta" },
                    knownOperationIds = db.syncOperationDao().getOperationIds(ledgerId).toSet(),
                    processedBundles = db.syncedRemoteFileDao().getProcessedPaths(ledgerId).toSet()
                )
                for (relative in pendingFiles) {
                    val path = "${ledger.remotePath}/operations/${relative.removePrefix("operations/")}"
                    if (path.endsWith(".json.gz", true)) {
                        val bytes = webDav.getBytes(config, path)
                        val bundled = runCatching { SharedOperationBundleCodec.decode(bytes) }.getOrNull() ?: continue
                        bundled.forEach { (op, raw) ->
                            if (!db.syncOperationDao().exists(op.operationId)) applyRemote(ledgerId, ledger.bookId, localBookName, op, raw)
                        }
                        db.syncedRemoteFileDao().markProcessed(
                            SyncedRemoteFile(ledgerId, relative, System.currentTimeMillis())
                        )
                    } else {
                        val raw = webDav.get(config, path)
                        val op = SharedOperationCodec.decode(raw) ?: continue
                        if (!db.syncOperationDao().exists(op.operationId)) applyRemote(ledgerId, ledger.bookId, localBookName, op, raw)
                    }
                }
                reconcileTombstones(ledgerId)
                downloadAndApplyBillSnapshots(ledger.id, ledger.uuid, ledger.remotePath, localBookName, config)
            }
            db.syncStateDao().save(current.copy(
                lastSyncTime = if (fullSync) System.currentTimeMillis() else current.lastSyncTime,
                isSyncing = false,
                lastError = null
            ))
            // 远端账单/预算落地后刷新桌面小组件，避免共享成员记账后本机小组件仍旧数据。
            ExpenseWidgetUpdater.refreshAllDebounced(context)
        } catch (e: Exception) {
            if (e is WebDavHttpException) recordCooldown(e, ledger.webdavUrl, ledger.webdavUser)
            db.syncStateDao().save(current.copy(isSyncing = false, lastError = e.message ?: "同步失败"))
            throw e
        }
    }

    private fun throwIfCoolingDown(config: SharedWebDavClient.Config) {
        val now = System.currentTimeMillis()
        val cooldownUntil = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .getLong(cooldownKey(config.baseUrl, config.username), 0L)
        if (cooldownUntil <= now) return
        val seconds = ((cooldownUntil - now + 999L) / 1_000L).coerceAtLeast(1L)
        error("坚果云正在冷却，请 $seconds 秒后再试")
    }

    private fun recordCooldown(error: WebDavHttpException, baseUrl: String, username: String) {
        val duration = SharedSyncPolicy.cooldownMillis(error.statusCode, error.retryAfterMillis) ?: return
        context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(cooldownKey(baseUrl, username), System.currentTimeMillis() + duration)
            .apply()
    }

    private fun cooldownKey(baseUrl: String, username: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${baseUrl.trim().lowercase()}|${username.trim().lowercase()}".toByteArray())
        return "webdav_cooldown_until_" + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * 每个成员只覆盖自己在当前共享账本中的账单快照。这样遗漏的 delete 操作也能在下次同步时被纠正，
     * 同时不会覆盖另一位成员创建的账单。
     */
    private suspend fun uploadLocalBillSnapshot(
        ledgerId: Long,
        ledgerUuid: String,
        remotePath: String,
        localMemberId: String,
        bookName: String,
        config: SharedWebDavClient.Config
    ) {
        val bills = db.billDao().getAllByBookName(bookName)
            .filter { it.isShared && it.memberId == localMemberId && it.sharedId != null }
            .sortedWith(compareBy<Bill> { it.time }.thenBy { it.sharedId })
            .map { bill ->
                bill.copy(
                    id = 0,
                    bookName = "",
                    accountId = null,
                    toAccountId = null,
                    accountName = "",
                    toAccountName = "",
                    accountBalanceAfter = null,
                    toAccountBalanceAfter = null
                )
            }
        val snapshot = SharedBillSnapshot(
            ledgerId = ledgerUuid,
            memberId = localMemberId,
            deviceId = com.taostudio.tapaccounting.DeviceIdManager.getDeviceId(context),
            generatedAt = System.currentTimeMillis(),
            bills = bills
        )
        webDav.ensureDirectory(config, "$remotePath/snapshots/bills")
        webDav.putGzip(
            config,
            "$remotePath/snapshots/bills/$localMemberId.json.gz",
            SharedBillSnapshotCodec.encode(snapshot)
        )
    }

    private suspend fun downloadAndApplyBillSnapshots(
        ledgerId: Long,
        ledgerUuid: String,
        remotePath: String,
        bookName: String,
        config: SharedWebDavClient.Config
    ) {
        db.sharedMemberDao().getByLedgerId(ledgerId).forEach { member ->
            val path = "$remotePath/snapshots/bills/${member.memberId}.json.gz"
            if (!webDav.exists(config, path)) return@forEach
            val snapshot = SharedBillSnapshotCodec.decode(webDav.getBytes(config, path)) ?: return@forEach
            if (snapshot.ledgerId != ledgerUuid || snapshot.memberId != member.memberId) return@forEach
            applyBillSnapshot(ledgerId, bookName, snapshot)
        }
    }

    private suspend fun applyBillSnapshot(ledgerId: Long, bookName: String, snapshot: SharedBillSnapshot) {
        val localMemberId = db.sharedLedgerDao().getById(ledgerId)?.localMemberId ?: return
        db.withTransaction {
            val incomingById = snapshot.bills.associateBy { it.sharedId!! }
            val current = db.billDao().getAllByBookName(bookName)
                .filter { it.isShared && it.memberId == snapshot.memberId && it.sharedId != null }

            current.filter { it.sharedId !in incomingById }.forEach { existing ->
                if (snapshotCovers(ledgerId, existing.sharedId!!, snapshot.generatedAt)) {
                    db.billDao().delete(existing)
                }
            }

            incomingById.forEach { (sharedId, remote) ->
                if (!snapshotCovers(ledgerId, sharedId, snapshot.generatedAt)) return@forEach
                val existing = db.billDao().getBySharedId(sharedId)
                val localCategoryId = remote.categoryName.takeIf { it.isNotBlank() }
                    ?.let { db.categoryDao().getCategoryByNameAndType(it.substringAfterLast(" - "), remote.type)?.id }
                val incoming = SharedBillAssetBindingPolicy.merge(existing, remote.copy(
                    id = existing?.id ?: 0,
                    bookName = bookName,
                    categoryId = localCategoryId,
                    relatedBillId = remote.relatedSharedId?.let { db.billDao().getBySharedId(it)?.id }
                ), ownedByLocalMember = snapshot.memberId == localMemberId)
                if (existing == null) db.billDao().insertBill(incoming) else db.billDao().updateBill(incoming)
                db.billDao().linkPendingSharedRefunds(sharedId, existing?.id ?: db.billDao().getBySharedId(sharedId)?.id ?: 0)
            }
        }
    }

    /** 不允许较旧快照覆盖该快照生成后产生的增量操作。 */
    private suspend fun snapshotCovers(ledgerId: Long, sharedId: String, generatedAt: Long): Boolean {
        if (db.syncOperationDao().hasDelete(ledgerId, "bill", sharedId)) return false
        val winner = db.syncOperationDao().getWinner(ledgerId, "bill", sharedId) ?: return true
        val operationTime = winner.payload
            ?.let(SharedOperationCodec::decode)
            ?.timestamp
            ?: winner.appliedAt
        return operationTime <= generatedAt
    }

    /**
     * Older clients could persist a delete operation without materialising it
     * when a same-entity update had already won locally. Re-applying known
     * tombstones is idempotent and repairs those existing ledgers.
     */
    private suspend fun reconcileTombstones(ledgerId: Long) = db.withTransaction {
        db.syncOperationDao().getDeletedEntityIds(ledgerId, "bill").forEach { sharedId ->
            db.billDao().getBySharedId(sharedId)?.let { db.billDao().delete(it) }
        }
        db.syncOperationDao().getDeletedEntityIds(ledgerId, "budget").forEach { sharedId ->
            db.budgetDao().getBySharedId(sharedId)?.let { db.budgetDao().delete(it) }
        }
    }

    private suspend fun refreshMemberNames(
        ledgerId: Long,
        ledgerUuid: String,
        remotePath: String,
        config: SharedWebDavClient.Config
    ) {
        val manifest = SharedLedgerService(context, db)
            .decodeManifest(webDav.get(config, "$remotePath/meta.json"))
        require(manifest.sharedBookId == ledgerUuid && ManifestValidator.validate(manifest).isValid) {
            "远端共享账本信息无效"
        }
        val localMemberId = db.sharedLedgerDao().getById(ledgerId)?.localMemberId ?: return
        require(manifest.members.any { it.memberId == localMemberId }) {
            "本机成员身份已不在远端共享账本中"
        }
        db.withTransaction {
            val remoteIds = manifest.members.mapTo(hashSetOf()) { it.memberId }
            db.sharedMemberDao().getByLedgerId(ledgerId)
                .filterNot { it.memberId in remoteIds }
                .forEach { db.sharedMemberDao().delete(ledgerId, it.memberId) }
            manifest.members.forEach { remote ->
                val local = db.sharedMemberDao().get(ledgerId, remote.memberId)
                db.sharedMemberDao().insert(
                    SharedMember(
                        id = local?.id ?: 0L,
                        ledgerId = ledgerId,
                        memberId = remote.memberId,
                        displayName = remote.displayName,
                        joinOrder = remote.joinOrder,
                        isLocal = remote.memberId == localMemberId
                    )
                )
            }
        }
    }

    private suspend fun applyRemote(ledgerId: Long, bookId: Long, bookName: String, op: Operation, raw: String) {
        val localMemberId = db.sharedLedgerDao().getById(ledgerId)?.localMemberId ?: return
        if (db.sharedMemberDao().get(ledgerId, op.memberId) == null) return
        if (op.entityType == "bill") {
            val current = db.billDao().getBySharedId(op.entityId)
            if (current?.memberId != null && current.memberId != op.memberId) return
        }
        db.withTransaction {
            val old = db.syncOperationDao().getWinner(ledgerId, op.entityType, op.entityId)
            val tombstoned = op.type != "delete" && db.syncOperationDao().hasDelete(ledgerId, op.entityType, op.entityId)
            val wins = !tombstoned && wins(old, op)
            if (wins) when (op.entityType) {
                "bill" -> applyBill(bookName, op, ownedByLocalMember = op.memberId == localMemberId)
                "budget" -> applyBudget(bookId, bookName, op)
            }
            db.syncOperationDao().insertIgnore(SyncOperation(op.operationId, ledgerId, op.entityType, op.entityId, op.type, op.revision, op.deviceId, op.memberId, raw, System.currentTimeMillis()))
        }
    }

    private suspend fun enqueueMissingIcons(ledgerId: Long, ledgerUuid: String, localMemberId: String, bookName: String) {
        db.billDao().getAllByBookName(bookName)
            .filter { it.isShared && it.memberId == localMemberId && it.sharedId != null && it.cateIcon.isNullOrBlank() }
            .forEach { bill ->
                val icon = com.taostudio.tapaccounting.CategoryIconHelper
                    .findCategoryIcon(context, bill.categoryName, bill.type)
                    .takeIf { it.isNotBlank() } ?: return@forEach
                val revision = db.syncOperationDao().maxRevision(ledgerId, "bill", bill.sharedId!!) + 1
                val updated = bill.copy(cateIcon = icon, sharedRevision = revision,
                    sharedDeviceId = com.taostudio.tapaccounting.DeviceIdManager.getDeviceId(context))
                db.billDao().updateBill(updated)
                SharedLedgerService(context, db).enqueue(
                    ledgerId, ledgerUuid, "bill", bill.sharedId, "update", revision, localMemberId,
                    gson.toJsonTree(updated.copy(id = 0, accountId = null, toAccountId = null,
                        accountName = "", toAccountName = "", accountBalanceAfter = null,
                        toAccountBalanceAfter = null)).asJsonObject
                )
            }
    }

    private suspend fun applyBill(bookName: String, op: Operation, ownedByLocalMember: Boolean) {
        val existing = db.billDao().getBySharedId(op.entityId)
        if (op.type == "delete") {
            existing?.let { db.billDao().delete(it) }
            return
        }
        val decoded = gson.fromJson(op.payload, Bill::class.java)
        val localCategoryId = decoded.categoryName.takeIf { it.isNotBlank() }
            ?.let { db.categoryDao().getCategoryByNameAndType(it.substringAfterLast(" - "), decoded.type)?.id }
        val incoming = SharedBillAssetBindingPolicy.merge(existing, decoded.copy(
            id = existing?.id ?: 0, bookName = bookName, sharedId = op.entityId,
            memberId = op.memberId, isShared = true, sharedRevision = op.revision, categoryId = localCategoryId,
            sharedDeviceId = op.deviceId,
            relatedBillId = decoded.relatedSharedId?.let { db.billDao().getBySharedId(it)?.id }
        ), ownedByLocalMember)
        val savedId = if (existing == null) db.billDao().insertBill(incoming) else {
            db.billDao().updateBill(incoming)
            existing.id
        }
        db.billDao().linkPendingSharedRefunds(op.entityId, savedId)
    }

    private suspend fun applyBudget(bookId: Long, bookName: String, op: Operation) {
        val existing = db.budgetDao().getBySharedId(op.entityId)
        if (op.type == "delete") {
            existing?.let { db.budgetDao().delete(it) }
            return
        }
        val decoded = SharedBudgetPayloadCodec.decode(op.payload) ?: return
        val localCategoryId = decoded.categoryName?.takeIf { it.isNotBlank() }
            ?.let { db.categoryDao().getCategoryByNameAndType(it.substringAfterLast(" - "), Bill.TYPE_EXPENSE)?.id }
        val localCategoryKey = when {
            decoded.categoryKey == Budget.TOTAL_CATEGORY_KEY -> Budget.TOTAL_CATEGORY_KEY
            localCategoryId != null -> localCategoryId
            else -> stableNegativeKey(op.entityId)
        }
        val incoming = decoded.copy(
            id = existing?.id ?: 0, bookId = bookId, bookName = bookName, sharedId = op.entityId,
            revision = op.revision, isShared = true, sharedDeviceId = op.deviceId,
            categoryId = localCategoryId, categoryKey = localCategoryKey
        )
        if (existing == null) db.budgetDao().insert(incoming) else db.budgetDao().update(incoming)
        Prefs.enableSharedBudgetDisplayDefaultsIfUnset(context, bookName)
    }

    companion object {
        private const val SYNC_PREFS = "shared_sync"
        private const val INTER_LEDGER_DELAY_MS = 750L

        internal fun wins(old: SyncOperation?, incoming: Operation): Boolean = when {
            old == null -> true
            old.action == "delete" -> incoming.type == "delete" && isLaterThan(old, incoming)
            // 删除也要比 revision：过期删除不能覆盖更新的编辑
            incoming.type == "delete" -> isLaterThan(old, incoming)
            else -> isLaterThan(old, incoming)
        }

        private fun isLaterThan(old: SyncOperation, incoming: Operation): Boolean =
            incoming.revision > old.revision ||
                incoming.revision == old.revision && incoming.deviceId > old.deviceId
        private fun stableNegativeKey(id: String): Long {
            val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            var value = 0L
            repeat(8) { value = (value shl 8) or (bytes[it].toLong() and 0xff) }
            return if (value == Long.MIN_VALUE) Long.MIN_VALUE + 1 else -kotlin.math.abs(value).coerceAtLeast(1)
        }
    }
}
