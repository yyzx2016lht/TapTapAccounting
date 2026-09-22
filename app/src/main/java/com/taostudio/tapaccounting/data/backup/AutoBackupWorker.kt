package com.taostudio.tapaccounting.data.backup

import android.content.Context
import android.util.Log
import androidx.work.*
import com.taostudio.tapaccounting.*
import com.taostudio.tapaccounting.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AutoBackupWorker"
        private const val UNIQUE_NAME = "auto_backup_periodic"

        private const val BACKUP_PREFS = "tap_backup_prefs"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_AUTO_BACKUP_INTERVAL_HOURS = "auto_backup_interval_hours"
        private const val KEY_AUTO_BACKUP_CLOUD_ENABLED = "auto_backup_cloud_enabled"
        private const val KEY_AUTO_BACKUP_MODE = "auto_backup_mode"
        private const val KEY_LAST_AUTO_BACKUP_TIME = "last_auto_backup_time"
        private const val KEY_LAST_AUTO_BACKUP_RESULT = "last_auto_backup_result"
        private const val KEY_LAST_LOCAL_BACKUP_SUCCESS = "last_local_backup_success_v2"
        private const val KEY_LAST_CLOUD_BACKUP_SUCCESS = "last_cloud_backup_success_v2"

        private const val CLOUD_PREFS = "tap_cloud_backup_prefs"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USER = "webdav_user"
        private const val KEY_WEBDAV_DIR = "webdav_dir"
        private const val KEY_DEVICE_NAME = "webdav_device_name"

        fun schedule(ctx: Context) {
            val sp = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            if (!sp.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)) {
                cancel(ctx)
                return
            }

            val hours = sp.getInt(KEY_AUTO_BACKUP_INTERVAL_HOURS, 12).toLong()
            val interval = hours.coerceIn(1, 72)

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(interval, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Auto backup scheduled, interval=${interval}h")
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
            Log.d(TAG, "Auto backup cancelled")
        }

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_BACKUP_ENABLED, false)

        fun getIntervalHours(ctx: Context): Int =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_AUTO_BACKUP_INTERVAL_HOURS, 12)

        fun isCloudEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_BACKUP_CLOUD_ENABLED, false)

        fun getBackupMode(ctx: Context): String =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_AUTO_BACKUP_MODE, "lite") ?: "lite"

        fun getLastBackupTime(ctx: Context): Long =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_AUTO_BACKUP_TIME, 0)

        fun getLastBackupResult(ctx: Context): String =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_AUTO_BACKUP_RESULT, "") ?: ""

        fun saveSettings(
            ctx: Context,
            enabled: Boolean,
            intervalHours: Int,
            cloudEnabled: Boolean,
            mode: String
        ) {
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled)
                .putInt(KEY_AUTO_BACKUP_INTERVAL_HOURS, intervalHours.coerceIn(1, 72))
                .putBoolean(KEY_AUTO_BACKUP_CLOUD_ENABLED, cloudEnabled)
                .putString(KEY_AUTO_BACKUP_MODE, mode)
                .apply()
            schedule(ctx)
        }

        private fun buildLiteOptions(): BackupOptions = BackupOptions(
            backupAssets = true, backupCategories = true, backupBills = true,
            backupRules = true, backupChatMessages = true, backupChatMedia = false,
            backupSettingsGeneralBasic = true, backupSettingsGeneralAssets = true,
            backupSettingsGeneralCloud = true, backupSettingsDisplayEntries = true,
            backupSettingsDisplayBills = true, backupSettingsDisplayMultiBill = true,
            backupSettingsAiCore = true, backupSettingsAiChat = true,
            backupSettingsBooks = true, backupSettingsAdvancedRuntime = true,
            backupBanners = true
        )

        fun buildFullOptions(): BackupOptions = BackupOptions(
            backupAssets = true, backupCategories = true, backupBills = true,
            backupRules = true, backupChatMessages = true, backupChatMedia = true,
            backupSettingsGeneralBasic = true, backupSettingsGeneralAssets = true,
            backupSettingsGeneralCloud = true, backupSettingsDisplayEntries = true,
            backupSettingsDisplayBills = true, backupSettingsDisplayMultiBill = true,
            backupSettingsAiCore = true, backupSettingsAiChat = true,
            backupSettingsBooks = true, backupSettingsAdvancedRuntime = true,
            backupBanners = true
        )

        private fun readCloudConfig(ctx: Context): CloudBackupConfig? {
            val sp = ctx.getSharedPreferences(CLOUD_PREFS, Context.MODE_PRIVATE)
            val url = sp.getString(KEY_WEBDAV_URL, "")?.trim().orEmpty()
            val user = sp.getString(KEY_WEBDAV_USER, "")?.trim().orEmpty()
            val pass = CloudBackupCredentials.loadPassword(ctx).orEmpty()
            val dir = sp.getString(KEY_WEBDAV_DIR, "TapAccount")?.trim()?.ifBlank { "TapAccount" } ?: "TapAccount"
            val device = sp.getString(KEY_DEVICE_NAME, "")?.trim()?.ifBlank { android.os.Build.MODEL ?: "device" } ?: (android.os.Build.MODEL ?: "device")
            if (url.isBlank() || user.isBlank() || pass.isBlank()) return null
            return CloudBackupConfig(
                baseUrl = url, username = user, password = pass,
                remoteDir = dir, deviceName = device
            )
        }
    }

    override suspend fun doWork(): Result {
        log("Starting auto backup")
        val ctx = applicationContext

        val sp = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
        val mode = sp.getString(KEY_AUTO_BACKUP_MODE, "lite") ?: "lite"
        val cloudEnabled = sp.getBoolean(KEY_AUTO_BACKUP_CLOUD_ENABLED, false)

        val options = if (mode == "full") buildFullOptions() else buildLiteOptions()

        return try {
            withContext(Dispatchers.IO) {
                val snapshotFile = File(ctx.cacheDir, "auto_snapshot_${System.currentTimeMillis()}.bak")
                try {
                    val created = RecoverySnapshotService(ctx, AppDatabase.getDatabase(ctx)).create(
                        outputFile = snapshotFile,
                        policy = options.toContentPolicy()
                    )

                    // Both destinations publish the exact same authenticated encrypted bytes.
                    val localOk = backupToLocal(ctx, snapshotFile, created.manifest, mode)

                    var cloudOk = true
                    var cloudMsg = ""
                    if (cloudEnabled) {
                        val config = readCloudConfig(ctx)
                        if (config != null) {
                            try {
                                backupToCloud(config, snapshotFile, created.manifest, mode)
                                cloudMsg = "云端同步成功"
                            } catch (e: Exception) {
                                cloudOk = false
                                cloudMsg = "云端同步失败: ${e.message?.take(50)}"
                                Log.w(TAG, "Cloud backup failed", e)
                            }
                        } else {
                            cloudOk = false
                            cloudMsg = "云端未配置，跳过"
                        }
                    }

                    val now = System.currentTimeMillis()
                    val result = buildString {
                        append(if (localOk) "本地备份成功" else "本地备份失败")
                        if (cloudEnabled) append("；$cloudMsg")
                    }
                    val resultEditor = sp.edit()
                        .putLong(KEY_LAST_AUTO_BACKUP_TIME, now)
                        .putString(KEY_LAST_AUTO_BACKUP_RESULT, result)
                    if (localOk) resultEditor.putLong(KEY_LAST_LOCAL_BACKUP_SUCCESS, now)
                    if (cloudEnabled && cloudOk) resultEditor.putLong(KEY_LAST_CLOUD_BACKUP_SUCCESS, now)
                    resultEditor.apply()

                    log("Auto backup completed: $result")
                    val atLeastOneDestinationSucceeded = localOk || (cloudEnabled && cloudOk)
                    if (atLeastOneDestinationSucceeded) Result.success() else Result.retry()
                } finally {
                    snapshotFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed", e)
            val sp2 = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            val message = if (e is BackupPasswordKeyUnavailableException) {
                "等待用户设置备份密码"
            } else {
                "备份异常: ${e.message?.take(50)}"
            }
            sp2.edit()
                .putLong(KEY_LAST_AUTO_BACKUP_TIME, System.currentTimeMillis())
                .putString(KEY_LAST_AUTO_BACKUP_RESULT, message)
                .apply()
            Result.retry()
        }
    }

    private suspend fun backupToLocal(
        ctx: Context,
        snapshotFile: File,
        manifest: BackupV2Manifest,
        mode: String
    ): Boolean {
        // Automatic backups always use the same canonical root directory as the manual Backup
        // action. Historical SAF preferences are deliberately ignored.
        return backupToDefaultDir(ctx, snapshotFile, manifest, mode)
    }

    /** 备份到唯一的 app-specific `files/backups/` 目录。 */
    private suspend fun backupToDefaultDir(
        ctx: Context,
        snapshotFile: File,
        manifest: BackupV2Manifest,
        mode: String
    ): Boolean {
        if (!BackupDefaultDirHelper.hasStoragePermission(ctx)) {
            log("Root backup directory permission is not granted")
            return false
        }
        val dir = try {
            BackupDefaultDirHelper.getDefaultBackupDir(ctx)
        } catch (error: Exception) {
            Log.e(TAG, "Private backup directory unavailable", error)
            return false
        }
        if (!dir.exists() && !dir.mkdirs()) {
            log("Cannot create default backup directory: ${dir.absolutePath}")
            return false
        }

        return try {
            val published = LocalBackupPublisher.publish(
                sourceFile = snapshotFile,
                targetDirectory = dir,
                deviceName = android.os.Build.MODEL ?: "android",
                mode = mode,
                createdAt = Instant.ofEpochMilli(manifest.createdAt),
                backupId = manifest.backupId,
                validate = { file ->
                    check(BackupFileFormatDetector.detect(file) == BackupFileFormat.V3_PASSWORD)
                }
            )
            runCatching { LocalBackupHistory.cleanup(dir) }
                .onFailure { Log.w(TAG, "Local backup retention cleanup failed", it) }
            log("Local backup saved to default dir: ${published.file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Default dir local backup failed", e)
            false
        }
    }

    private suspend fun backupToCloud(
        config: CloudBackupConfig,
        snapshotFile: File,
        manifest: BackupV2Manifest,
        mode: String
    ) {
        val fileName = BackupArtifactNames.create(
            deviceName = config.deviceName,
            mode = mode,
            createdAt = Instant.ofEpochMilli(manifest.createdAt),
            backupId = manifest.backupId
        )
        WebDavClient.uploadBackup(config, fileName, snapshotFile)
        runCatching { WebDavClient.cleanupBackupHistory(config) }
        log("Cloud backup uploaded: $fileName")
    }

    private fun BackupOptions.toContentPolicy(): BackupContentPolicy {
        val dataModules = linkedSetOf<String>()
        if (backupAssets) dataModules += BackupModuleId.ASSETS
        if (backupCategories) dataModules += BackupModuleId.CATEGORIES
        if (backupBills) dataModules += setOf(
            BackupModuleId.BILLS,
            BackupModuleId.DELETED_BILLS,
            BackupModuleId.INVESTMENT_LOTS
        )
        if (backupRules) dataModules += BackupModuleId.RULES
        if (backupChatMessages) dataModules += BackupModuleId.CHAT_MESSAGES
        dataModules += setOf(BackupModuleId.BUDGETS, BackupModuleId.RECURRING_PATTERNS)

        val settingsModules = linkedSetOf<String>()
        if (backupSettingsGeneralBasic) settingsModules += "settings_general_basic"
        if (backupSettingsGeneralAssets) settingsModules += "settings_general_assets"
        if (backupSettingsGeneralCloud) settingsModules += "settings_general_cloud"
        if (backupSettingsDisplayEntries) settingsModules += "settings_display_entries"
        if (backupSettingsDisplayBills) settingsModules += "settings_display_bills"
        if (backupSettingsDisplayMultiBill) settingsModules += "settings_display_multibill"
        if (backupSettingsAiCore) settingsModules += "settings_ai_core"
        if (backupSettingsAiChat) settingsModules += "settings_ai_chat"
        if (backupSettingsBooks) settingsModules += "settings_books"
        if (backupSettingsAdvancedRuntime) settingsModules += "settings_advanced_runtime"
        return BackupContentPolicy(
            dataModules = dataModules,
            settingsModules = settingsModules,
            includeBanners = backupBanners,
            includeChatMedia = backupChatMedia
        )
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        Logger.d(applicationContext, TAG, message)
    }
}
