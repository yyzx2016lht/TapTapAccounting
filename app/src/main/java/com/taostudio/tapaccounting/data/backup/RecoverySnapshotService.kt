package com.taostudio.tapaccounting.data.backup

import android.content.Context
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.repository.BackupRepository
import java.io.File
import java.io.IOException
import java.util.UUID

data class CreatedRecoverySnapshot(
    val file: File,
    val manifest: BackupV2Manifest
)

data class PreparedRecoverySnapshot(
    /** ZIP payload used only in app-private cache while the restore dialog is open. */
    val payloadFile: File,
    val manifest: BackupV2Manifest?
)

/**
 * The one path for constructing and opening user recovery snapshots. Manual/automatic and
 * local/WebDAV entry points should differ only in where they publish [CreatedRecoverySnapshot].
 */
class RecoverySnapshotService(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val repository = BackupRepository(database)

    suspend fun create(
        outputFile: File,
        policy: BackupContentPolicy,
        suppliedPasswordKey: BackupPasswordKeyMaterial? = null,
        apiPin: String? = null
    ): CreatedRecoverySnapshot {
        val sourceKey = suppliedPasswordKey ?: BackupPasswordKeyStore.load(context)
            ?: throw BackupPasswordKeyUnavailableException("请先设置 8 至 12 位备份密码")
        val passwordKey = BackupPasswordKeyMaterial(
            keyBytes = sourceKey.keyBytes.copyOf(),
            parameters = BackupPasswordKdfParameters(
                salt = sourceKey.parameters.salt.copyOf(),
                iterations = sourceKey.parameters.iterations
            )
        )
        if (suppliedPasswordKey == null) sourceKey.keyBytes.fill(0)
        outputFile.absoluteFile.parentFile?.mkdirs()
        val clearPayload = File(
            outputFile.absoluteFile.parentFile,
            ".${outputFile.name}.payload.${UUID.randomUUID()}.zip"
        )
        try {
            val fullData = repository.getFullData()
            val effectiveDataModules = effectiveDataModules(policy.dataModules)
            val jsonModules = linkedMapOf<String, JsonModule>()

            effectiveDataModules.forEach { moduleName ->
                fullData[moduleName]?.let { value ->
                    jsonModules[moduleName] = JsonModule(
                        json = DataExportManager.serialize(value)
                    )
                }
            }

            if (BackupModuleId.ASSETS in effectiveDataModules) {
                @Suppress("UNCHECKED_CAST")
                val assets = fullData[BackupModuleId.ASSETS] as? List<Asset> ?: emptyList()
                val drafts = InvestmentDraftBackupSupport.export(context, assets)
                jsonModules[BackupModuleId.INVESTMENT_DRAFTS] = JsonModule(
                    json = InvestmentDraftBackupSupport.encode(drafts)
                )
            }

            if (BackupModuleId.SHARED_LEDGERS in effectiveDataModules) {
                val ledgers = requireSharedLedgerBackups(fullData[BackupModuleId.SHARED_LEDGERS])
                jsonModules[BackupModuleId.SHARED_SECRETS] = JsonModule(
                    json = SharedRecoverySecrets.exportForLedgerUuids(
                        context,
                        ledgers.map(SharedLedgerBackup::uuid)
                    )
                )
            }

            val settings = Prefs.serializeSettingsModules(context)
            policy.settingsModules.forEach { moduleName ->
                settings[moduleName]?.let { raw ->
                    jsonModules[moduleName] = JsonModule(
                        BackupSecretPolicy.prepareEncryptedModule(moduleName, raw, apiPin)
                    )
                }
            }

            // Shared recovery credentials stay in the encrypted-only module; settings are
            // secret-free unless PIN-encrypted API key fields are intentionally present.
            BackupSecretPolicy.requireSecretFree(
                jsonModules.mapValues { it.value.json },
                allowedSecretModules = buildSet {
                    add(BackupModuleId.SHARED_SECRETS)
                    if (apiPin != null) add("settings_ai_core")
                }
            )

            val bannerDir = if (policy.includeBanners) {
                File(context.filesDir, "banners").takeIf(File::isDirectory)
            } else {
                null
            }
            val chatMediaFiles = if (policy.includeChatMedia) {
                BackupMediaRegistry.collectChatMedia(context)
            } else {
                emptyMap()
            }

            BackupManager.backup(
                outputFile = clearPayload,
                dataMap = jsonModules.mapValuesTo(linkedMapOf()) { it.value.json },
                bannerDir = bannerDir,
                chatMediaFiles = chatMediaFiles
            )

            // Inventory the sealed ZIP rather than reading live media a second time. Files may be
            // added or changed while a backup is running; the manifest must describe the exact
            // bytes that were archived and will later be restored.
            BackupManager.validateArchive(clearPayload)
            val archivedJsonModules = BackupManager.restore(clearPayload)
            val manifestModules = BackupManager.inspectArchiveModules(clearPayload, archivedJsonModules)

            val manifest = BackupV2Manifest.create(
                appVersion = appVersionName(),
                dbSchemaVersion = database.openHelper.readableDatabase.version,
                modules = manifestModules,
                payloadFile = clearPayload
            )
            BackupPasswordEnvelope.encrypt(
                payloadFile = clearPayload,
                outputFile = outputFile,
                manifest = manifest,
                keyMaterial = passwordKey
            )
            return CreatedRecoverySnapshot(outputFile, manifest)
        } finally {
            passwordKey.keyBytes.fill(0)
            clearPayload.delete()
        }
    }

    fun prepareForRestore(
        sourceFile: File,
        clearPayloadFile: File,
        recoveryCode: BackupRecoveryCode? = null,
        passwordKey: ByteArray? = null
    ): PreparedRecoverySnapshot {
        return when (BackupFileFormatDetector.detect(sourceFile)) {
            BackupFileFormat.V2_ENCRYPTED -> {
                val code = requireNotNull(recoveryCode) { "该备份需要恢复码" }
                val manifest = BackupV2Envelope.decrypt(sourceFile, clearPayloadFile, code)
                if (manifest.dbSchemaVersion > AppDatabase.CODE_VERSION) {
                    throw BackupFormatException("该备份来自更新版本的应用，请先升级应用后再恢复")
                }
                // Fully authenticate and scan the ZIP before the UI can mutate any app state.
                BackupManager.validateArchive(clearPayloadFile)
                val jsonModules = BackupManager.restore(clearPayloadFile)
                val actualModules = BackupManager.inspectArchiveModules(clearPayloadFile, jsonModules)
                if (actualModules.sortedBy(BackupV2Module::name) !=
                    manifest.modules.sortedBy(BackupV2Module::name)
                ) {
                    throw BackupIntegrityException("备份模块清单与归档内容不一致")
                }
                PreparedRecoverySnapshot(clearPayloadFile, manifest)
            }

            BackupFileFormat.V3_PASSWORD -> {
                val key = requireNotNull(passwordKey) { "该备份需要备份密码" }
                val manifest = BackupPasswordEnvelope.decrypt(sourceFile, clearPayloadFile, key)
                if (manifest.dbSchemaVersion > AppDatabase.CODE_VERSION) {
                    throw BackupFormatException("该备份来自更新版本的应用，请先升级应用后再恢复")
                }
                BackupManager.validateArchive(clearPayloadFile)
                val jsonModules = BackupManager.restore(clearPayloadFile)
                val actualModules = BackupManager.inspectArchiveModules(clearPayloadFile, jsonModules)
                if (actualModules.sortedBy(BackupV2Module::name) !=
                    manifest.modules.sortedBy(BackupV2Module::name)
                ) {
                    throw BackupIntegrityException("备份模块清单与归档内容不一致")
                }
                PreparedRecoverySnapshot(clearPayloadFile, manifest)
            }

            BackupFileFormat.ZIP -> {
                // The selected/downloaded ZIP is already in app-private cache. Validate it in
                // place and transfer ownership to the restore flow instead of requiring 2x space.
                BackupManager.validateArchive(sourceFile)
                BackupManager.restore(sourceFile)
                PreparedRecoverySnapshot(sourceFile, manifest = null)
            }

            BackupFileFormat.UNKNOWN -> throw BackupFormatException("文件不是受支持的备份格式")
        }
    }

    private fun effectiveDataModules(selected: Set<String>): Set<String> {
        if (selected.isEmpty()) return emptySet()
        val result = selected.toMutableSet()
        if (selected.any { it in BackupModuleId.coreFinancial }) {
            result += setOf(
                BackupModuleId.BOOKS,
                BackupModuleId.SHARED_LEDGERS,
                BackupModuleId.SHARED_MEMBERS,
                BackupModuleId.SYNC_QUEUE,
                BackupModuleId.SYNC_OPERATIONS
            )
        }
        if (BackupModuleId.INVESTMENT_LOTS in result) result += BackupModuleId.ASSETS
        return result
    }

    private fun appVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }

    private fun atomicCopy(source: File, destination: File) {
        destination.absoluteFile.parentFile?.mkdirs()
        val staged = File(destination.absoluteFile.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            source.inputStream().buffered().use { input ->
                staged.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("无法替换恢复暂存文件")
            }
            if (!staged.renameTo(destination)) throw IOException("无法提交恢复暂存文件")
        } finally {
            staged.delete()
        }
    }

    private fun commitPayload(source: File, destination: File) {
        destination.absoluteFile.parentFile?.mkdirs()
        // Creation always stages beside its destination, so the normal path consumes no second
        // full-size cache copy. Keep atomicCopy as a cross-filesystem/existing-file fallback.
        if (!destination.exists() && source.renameTo(destination)) return
        atomicCopy(source, destination)
    }

    private data class JsonModule(val json: String)
}
