package com.taostudio.tapaccounting.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.taostudio.tapaccounting.DeviceIdManager
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.*
import com.taostudio.tapaccounting.data.sync.protocol.Manifest
import com.taostudio.tapaccounting.data.sync.protocol.ManifestMember
import com.taostudio.tapaccounting.data.sync.protocol.ManifestValidator
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import com.taostudio.tapaccounting.data.sync.protocol.StrictJsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SharedLedgerService(private val context: Context, private val db: AppDatabase) {
    private val gson = Gson()
    private val webDav = SharedWebDavClient()

    suspend fun create(
        bookName: String,
        ledgerName: String,
        creatorName: String,
        webdavUrl: String,
        webdavUser: String,
        password: String
    ): Long {
        val normalizedCreatorName = creatorName.trim()
        require(normalizedCreatorName.isNotBlank()) { "请输入你的成员名称" }
        require(normalizedCreatorName.length <= Manifest.MAX_MEMBER_DISPLAY_NAME_LENGTH) { "成员名称过长" }
        require(webdavUrl.trim().trimEnd('/') == JIANGUOYUN_WEBDAV_URL.trimEnd('/')) {
            "新共享账本仅支持坚果云 WebDAV"
        }
        val normalizedWebdavUrl = JIANGUOYUN_WEBDAV_URL
        val bookId = db.bookDao().resolveOrCreateId(bookName)
        val book = db.bookDao().getById(bookId) ?: error("无法创建账本身份")
        check(db.sharedLedgerDao().getByBookId(book.id) == null) { "该账本已经是共享账本" }
        val ledgerUuid = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val members = listOf(
            ManifestMember(
                memberId = UUID.randomUUID().toString(),
                displayName = normalizedCreatorName,
                joinOrder = SharedInvitePolicy.CREATOR_JOIN_ORDER,
                joinedAt = createdAt
            )
        )
        val remotePath = "/shared-ledger/$ledgerUuid"
        val config = SharedWebDavClient.Config(normalizedWebdavUrl, webdavUser, password)
        webDav.ensureDirectory(config, "$remotePath/operations")
        val manifest = Manifest(sharedBookId = ledgerUuid, name = ledgerName, createdAt = createdAt, members = members)
        require(ManifestValidator.validate(manifest).isValid) { "共享账本信息无效" }
        webDav.put(config, "$remotePath/meta.json", gson.toJson(manifest))

        val ledgerId = try {
            db.withTransaction {
                val ledgerId = db.sharedLedgerDao().insert(SharedLedger(
                    uuid = ledgerUuid, bookId = book.id, name = ledgerName, webdavUrl = normalizedWebdavUrl,
                    webdavUser = webdavUser, remotePath = remotePath, localMemberId = members.first().memberId,
                    createdAt = manifest.createdAt
                ))
                db.sharedMemberDao().insertAll(members.map { SharedMember(ledgerId = ledgerId, memberId = it.memberId, displayName = it.displayName, joinOrder = it.joinOrder, isLocal = it.joinOrder == 1) })
                db.syncStateDao().save(SyncState(ledgerId, DeviceIdManager.getDeviceId(context)))
                seedHistory(ledgerId, ledgerUuid, book.name, book.id, members.first().memberId)
                ledgerId
            }
        } catch (e: Exception) {
            // P1-15: 远端已建目录但本地失败 → 标记 closed，避免孤儿共享账本
            runCatching {
                webDav.put(config, "$remotePath/closed.json", gson.toJson(mapOf(
                    "closedAt" to System.currentTimeMillis(),
                    "reason" to "local_create_failed"
                )))
            }
            throw e
        }
        SharedCredentials.save(context, ledgerUuid, password)
        SharedSyncScheduler.enqueueNow(context)
        return ledgerId
    }

    suspend fun join(
        invite: SharedInvite,
        password: String,
        memberName: String,
        existingBookName: String? = null
    ): Long {
        check(db.sharedLedgerDao().getByUuid(invite.ledgerId) == null) { "已经加入该共享账本" }
        val config = SharedWebDavClient.Config(invite.webdavUrl, invite.webdavUser, password)
        check(!webDav.exists(config, "${invite.remotePath}/closed.json")) { "该共享账本已解散" }
        var previousMember: ManifestMember? = null
        val (manifest, _) = updateManifest(config, "${invite.remotePath}/meta.json") { current ->
            require(current.sharedBookId == invite.ledgerId) { "邀请与远端账本不一致" }
            val invitedMember = current.members.singleOrNull { it.memberId == invite.memberId }
                ?: error("邀请成员不存在")
            require(invitedMember.joinOrder == invite.joinOrder) { "邀请成员顺序不一致" }
            previousMember = invitedMember
            val joinedMember = SharedInvitePolicy.joinWithName(invitedMember, memberName)
            current.copy(members = current.members.map { member ->
                if (member.memberId == joinedMember.memberId) joinedMember else member
            }) to Unit
        }
        val localName = existingBookName?.trim()?.takeIf { it.isNotBlank() } ?: uniqueBookName(manifest.name)
        val bookId = db.bookDao().resolveOrCreateId(localName)
        check(db.sharedLedgerDao().getByBookId(bookId) == null) { "所选账本已经是共享账本" }
        val ledgerId = try {
            db.withTransaction {
                val id = db.sharedLedgerDao().insert(SharedLedger(
                    uuid = manifest.sharedBookId, bookId = bookId, name = manifest.name,
                    webdavUrl = invite.webdavUrl, webdavUser = invite.webdavUser,
                    remotePath = invite.remotePath, localMemberId = invite.memberId, createdAt = manifest.createdAt
                ))
                db.sharedMemberDao().insertAll(manifest.members.map { SharedMember(ledgerId = id, memberId = it.memberId, displayName = it.displayName, joinOrder = it.joinOrder, isLocal = it.memberId == invite.memberId) })
                db.syncStateDao().save(SyncState(id, DeviceIdManager.getDeviceId(context)))
                seedHistory(id, manifest.sharedBookId, localName, bookId, invite.memberId)
                id
            }
        } catch (e: Exception) {
            // P1-15: 远端已标记加入但本地失败 → 回滚远端成员状态
            val restore = previousMember
            if (restore != null) {
                runCatching {
                    updateManifest(config, "${invite.remotePath}/meta.json") { current ->
                        current.copy(members = current.members.map { member ->
                            if (member.memberId == restore.memberId) restore else member
                        }) to Unit
                    }
                }
            }
            throw e
        }
        SharedCredentials.save(context, invite.ledgerId, password)
        runCatching { SharedSyncEngine(context, db).syncLedger(ledgerId) }
            .onFailure { SharedSyncScheduler.enqueueFullNow(context) }
        return ledgerId
    }

    suspend fun exitKeepingLocalCopy(ledgerId: Long, syncFirst: Boolean = false) {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: return
        if (syncFirst) {
            SharedSyncEngine(context, db).syncLedger(ledgerId)
            check(db.syncQueueDao().count(ledgerId) == 0) { "仍有内容未上传，请联网同步后重试" }
        }
        val bookName = db.bookDao().getById(ledger.bookId)?.name ?: ledger.name
        db.withTransaction {
            db.billDao().clearSharedState(bookName)
            db.budgetDao().clearSharedState(ledger.bookId)
            db.sharedLedgerDao().deleteById(ledgerId)
        }
        SharedCredentials.clear(context, ledger.uuid)
    }

    suspend fun exitDeletingLocalCopy(ledgerId: Long) {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: return
        val bookName = db.bookDao().getById(ledger.bookId)?.name ?: ledger.name
        db.withTransaction {
            db.billDao().deleteAllByBookName(bookName)
            db.budgetDao().deleteAllByBookId(ledger.bookId)
            db.chatMessageDao().deleteAllByBookName(bookName)
            db.sharedLedgerDao().deleteById(ledgerId)
        }
        SharedCredentials.clear(context, ledger.uuid)
    }

    suspend fun dissolve(ledgerId: Long) {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: return
        val localMember = db.sharedMemberDao().get(ledgerId, ledger.localMemberId)
        check(localMember?.joinOrder == 1) { "只有创建者可以解散共享账本" }
        SharedSyncEngine(context, db).syncLedger(ledgerId)
        check(db.syncQueueDao().count(ledgerId) == 0) { "仍有内容未上传，请联网同步后重试" }
        val password = SharedCredentials.load(context, ledger.uuid) ?: error("缺少 WebDAV 应用密码")
        val config = SharedWebDavClient.Config(ledger.webdavUrl, ledger.webdavUser, password)
        webDav.put(config, "${ledger.remotePath}/closed.json", gson.toJson(mapOf(
            "sharedBookId" to ledger.uuid,
            "dissolvedBy" to ledger.localMemberId,
            "dissolvedAt" to System.currentTimeMillis()
        )))
        exitKeepingLocalCopy(ledgerId, syncFirst = false)
    }

    suspend fun createInvite(ledgerId: Long): String {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: error("共享账本不存在")
        val localMember = db.sharedMemberDao().get(ledgerId, ledger.localMemberId)
            ?: error("本机成员不存在")
        check(SharedInvitePolicy.canCreateInvite(localMember.joinOrder)) { "只有创建者可以邀请新成员" }
        val password = SharedCredentials.load(context, ledger.uuid) ?: error("缺少 WebDAV 应用密码")
        val config = SharedWebDavClient.Config(ledger.webdavUrl, ledger.webdavUser, password)
        val (_, remoteMember) = updateManifest(config, "${ledger.remotePath}/meta.json") { manifest ->
            require(manifest.sharedBookId == ledger.uuid) { "远端共享账本信息无效" }
            val invited = SharedInvitePolicy.newMember(manifest.members, UUID.randomUUID().toString())
            manifest.copy(members = (manifest.members + invited).sortedBy { it.joinOrder }) to invited
        }
        db.sharedMemberDao().insert(
            SharedMember(
                ledgerId = ledgerId,
                memberId = remoteMember.memberId,
                displayName = remoteMember.displayName,
                joinOrder = remoteMember.joinOrder,
                isLocal = false
            )
        )
        return encodeInvite(ledger, remoteMember)
    }

    suspend fun inviteText(ledgerId: Long, memberId: String): String {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: error("共享账本不存在")
        val localMember = db.sharedMemberDao().get(ledgerId, ledger.localMemberId)
            ?: error("本机成员不存在")
        check(SharedInvitePolicy.canCreateInvite(localMember.joinOrder)) { "只有创建者可以发送成员邀请" }
        val password = SharedCredentials.load(context, ledger.uuid) ?: error("缺少 WebDAV 应用密码")
        val config = SharedWebDavClient.Config(ledger.webdavUrl, ledger.webdavUser, password)
        val manifest = decodeManifest(webDav.get(config, "${ledger.remotePath}/meta.json"))
        require(manifest.sharedBookId == ledger.uuid && ManifestValidator.validate(manifest).isValid) {
            "远端共享账本信息无效"
        }
        val member = manifest.members.singleOrNull { it.memberId == memberId }
            ?: error("邀请成员不存在")
        require(member.joinOrder in 2..Manifest.MAX_MEMBER_COUNT) { "不能生成创建者身份邀请" }
        if (member.invitedAt != null) check(member.joinedAt == null) { "该成员已经加入" }
        return encodeInvite(ledger, member)
    }

    suspend fun cancelInvite(ledgerId: Long, memberId: String) {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: error("共享账本不存在")
        val localMember = db.sharedMemberDao().get(ledgerId, ledger.localMemberId)
            ?: error("本机成员不存在")
        check(SharedInvitePolicy.canCreateInvite(localMember.joinOrder)) { "只有创建者可以撤销成员邀请" }
        val password = SharedCredentials.load(context, ledger.uuid) ?: error("缺少 WebDAV 应用密码")
        val config = SharedWebDavClient.Config(ledger.webdavUrl, ledger.webdavUser, password)
        updateManifest(config, "${ledger.remotePath}/meta.json") { manifest ->
            require(manifest.sharedBookId == ledger.uuid) { "远端共享账本信息无效" }
            val invitedMember = manifest.members.singleOrNull { it.memberId == memberId }
                ?: error("邀请成员不存在")
            check(SharedInvitePolicy.canCancelInvite(invitedMember)) { "该邀请已被使用或属于旧版成员，不能撤销" }
            manifest.copy(members = manifest.members.filterNot { it.memberId == memberId }) to Unit
        }
        db.sharedMemberDao().delete(ledgerId, memberId)
    }

    suspend fun updateLocalMemberName(ledgerId: Long, displayName: String) {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: error("共享账本不存在")
        val normalized = displayName.trim()
        require(normalized.isNotBlank()) { "请输入你的成员名称" }
        require(normalized.length <= Manifest.MAX_MEMBER_DISPLAY_NAME_LENGTH) { "成员名称过长" }
        val password = SharedCredentials.load(context, ledger.uuid) ?: error("缺少 WebDAV 应用密码")
        val config = SharedWebDavClient.Config(ledger.webdavUrl, ledger.webdavUser, password)
        updateManifest(config, "${ledger.remotePath}/meta.json") { manifest ->
            require(manifest.sharedBookId == ledger.uuid) { "远端共享账本信息无效" }
            require(manifest.members.any { it.memberId == ledger.localMemberId }) { "本机成员不存在" }
            manifest.copy(members = manifest.members.map { member ->
                if (member.memberId == ledger.localMemberId) {
                    member.copy(displayName = normalized, joinedAt = member.joinedAt ?: System.currentTimeMillis())
                } else member
            }) to Unit
        }
        db.sharedMemberDao().updateDisplayName(ledgerId, ledger.localMemberId, normalized)
    }

    suspend fun updateJianguoyunPassword(ledgerId: Long, password: String) {
        require(password.isNotBlank()) { "请输入坚果云应用密码" }
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: error("共享账本不存在")
        val config = SharedWebDavClient.Config(ledger.webdavUrl, ledger.webdavUser, password)
        check(!webDav.exists(config, "${ledger.remotePath}/closed.json")) { "该共享账本已解散" }
        val manifest = decodeManifest(webDav.get(config, "${ledger.remotePath}/meta.json"))
        require(manifest.sharedBookId == ledger.uuid && ManifestValidator.validate(manifest).isValid) {
            "坚果云中的共享账本信息不匹配"
        }
        require(manifest.members.any { it.memberId == ledger.localMemberId }) { "本机成员身份不在远端账本中" }
        SharedCredentials.save(context, ledger.uuid, password)
        SharedSyncScheduler.enqueueFullNow(context)
    }

    private fun encodeInvite(ledger: SharedLedger, member: ManifestMember): String {
        return InviteCodec.encode(SharedInvite(
            ledger.uuid, ledger.name, ledger.webdavUrl, ledger.webdavUser, ledger.remotePath,
            member.memberId, member.displayName, member.joinOrder
        ))
    }

    private suspend fun seedHistory(ledgerId: Long, ledgerUuid: String, bookName: String, bookId: Long, memberId: String) {
        val bills = db.billDao().getAllByBookName(bookName).filter { it.isShareable() }
        val idMap = bills.associate { it.id to (it.sharedId ?: UUID.randomUUID().toString()) }
        bills.sortedBy { it.subType == Bill.SUBTYPE_REFUND }.forEach { old ->
            val entityId = idMap.getValue(old.id)
            val icon = old.cateIcon ?: com.taostudio.tapaccounting.CategoryIconHelper
                .findCategoryIcon(context, old.categoryName, old.type).takeIf { it.isNotBlank() }
            val bill = old.copy(sharedId = entityId, memberId = memberId, isShared = true, sharedRevision = 1,
                sharedDeviceId = DeviceIdManager.getDeviceId(context), relatedSharedId = old.relatedBillId?.let(idMap::get))
                .copy(cateIcon = icon)
            db.billDao().updateBill(bill)
            enqueue(ledgerId, ledgerUuid, "bill", entityId, "create", 1, memberId, billPayload(bill))
        }
        db.budgetDao().getAllByBookId(bookId).forEach { old ->
            val slot = old.categoryName?.trim()?.lowercase().orEmpty().ifBlank { "__total__" }
            val entityId = old.sharedId ?: UUID.nameUUIDFromBytes("$ledgerUuid|${old.yearMonth}|$slot".toByteArray()).toString()
            val budget = old.copy(sharedId = entityId, revision = 1, isShared = true, sharedDeviceId = DeviceIdManager.getDeviceId(context))
            db.budgetDao().update(budget)
            Prefs.enableSharedBudgetDisplayDefaultsIfUnset(context, bookName)
            enqueue(ledgerId, ledgerUuid, "budget", entityId, "create", 1, memberId, SharedBudgetPayloadCodec.encode(budget))
        }
    }

    internal suspend fun enqueue(ledgerId: Long, ledgerUuid: String, type: String, entityId: String, action: String, revision: Long, memberId: String, payload: JsonObject?) {
        val op = Operation(UUID.randomUUID().toString(), action, type, entityId, revision, DeviceIdManager.getDeviceId(context), memberId, System.currentTimeMillis(), payload)
        val json = SharedOperationCodec.encode(op)
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        db.syncOperationDao().insertIgnore(SyncOperation(op.operationId, ledgerId, type, entityId, action, revision, op.deviceId, memberId, json, op.timestamp))
        db.syncQueueDao().insertIgnore(SyncQueue(op.operationId, ledgerId, json, "/shared-ledger/$ledgerUuid/operations/$month/${op.deviceId}/${op.operationId}.json", op.timestamp))
    }

    private fun billPayload(bill: Bill) = gson.toJsonTree(bill.copy(id = 0, accountId = null, toAccountId = null, accountName = "", toAccountName = "", accountBalanceAfter = null, toAccountBalanceAfter = null)).asJsonObject
    internal fun decodeManifest(raw: String): Manifest {
        require(raw.length <= 131_072) { "远端共享账本信息过大" }
        val root = JsonParser.parseString(raw).asJsonObject
        require(StrictJsonParser.parseInt(root.get("schemaVersion"), "schemaVersion") != null)
        require(StrictJsonParser.parseLong(root.get("createdAt"), "createdAt") != null)
        root.getAsJsonArray("members").forEach {
            require(StrictJsonParser.parseInt(it.asJsonObject.get("joinOrder"), "joinOrder") != null)
        }
        return gson.fromJson(root, Manifest::class.java)
    }

    private fun <T> updateManifest(
        config: SharedWebDavClient.Config,
        path: String,
        transform: (Manifest) -> Pair<Manifest, T>
    ): Pair<Manifest, T> {
        repeat(3) { attempt ->
            val resource = webDav.getTextResource(config, path)
            val current = decodeManifest(resource.content)
            require(ManifestValidator.validate(current).isValid) { "远端共享账本信息无效" }
            val (updated, result) = transform(current)
            require(ManifestValidator.validate(updated).isValid) { "共享账本成员信息无效" }
            try {
                webDav.put(config, path, gson.toJson(updated), ifMatch = resource.etag)
                return updated to result
            } catch (error: WebDavHttpException) {
                if (error.statusCode != 412 || resource.etag.isNullOrBlank() || attempt == 2) throw error
            }
        }
        error("共享成员信息更新冲突，请重试")
    }
    private suspend fun uniqueBookName(name: String): String {
        if (db.bookDao().getByName(name) == null) return name
        var index = 2
        while (db.bookDao().getByName("$name ($index)") != null) index++
        return "$name ($index)"
    }

    companion object {
        const val JIANGUOYUN_WEBDAV_URL = "https://dav.jianguoyun.com/dav/"

        fun Bill.isShareable() = type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME) && subType !in setOf(Bill.SUBTYPE_BALANCE_ADJUSTMENT, Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED) && type != Bill.TYPE_REPAYMENT
    }
}
