package com.taostudio.tapaccounting

import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import com.taostudio.tapaccounting.data.backup.AutoBackupWorker
import com.taostudio.tapaccounting.data.backup.BackupDefaultDirHelper
import com.taostudio.tapaccounting.data.backup.BackupAuthenticationException
import com.taostudio.tapaccounting.data.backup.BackupArtifactNames
import com.taostudio.tapaccounting.data.backup.BackupContentPolicy
import com.taostudio.tapaccounting.data.backup.BackupFileFormat
import com.taostudio.tapaccounting.data.backup.BackupFileFormatDetector
import com.taostudio.tapaccounting.data.backup.BackupManager
import com.taostudio.tapaccounting.data.backup.BackupModuleId
import com.taostudio.tapaccounting.data.backup.BackupPinCrypto
import com.taostudio.tapaccounting.data.backup.BackupPasswordCrypto
import com.taostudio.tapaccounting.data.backup.BackupPasswordEnvelope
import com.taostudio.tapaccounting.data.backup.BackupPasswordKdfParameters
import com.taostudio.tapaccounting.data.backup.BackupPasswordKeyMaterial
import com.taostudio.tapaccounting.data.backup.BackupPasswordKeyStore
import com.taostudio.tapaccounting.data.backup.BackupRecoveryCode
import com.taostudio.tapaccounting.data.backup.CloudBackupConfig
import com.taostudio.tapaccounting.data.backup.CloudBackupCredentials
import com.taostudio.tapaccounting.data.backup.CloudBackupEntry
import com.taostudio.tapaccounting.data.backup.CsvManager
import com.taostudio.tapaccounting.data.backup.DataExportManager
import com.taostudio.tapaccounting.data.backup.InvestmentDraftBackupSupport
import com.taostudio.tapaccounting.data.backup.InvestmentDraftRecordBackup
import com.taostudio.tapaccounting.data.backup.RecoverySnapshotService
import com.taostudio.tapaccounting.data.backup.LocalBackupHistory
import com.taostudio.tapaccounting.data.backup.RestoreMediaSelection
import com.taostudio.tapaccounting.data.backup.RestoreMediaTransaction
import com.taostudio.tapaccounting.data.backup.RestorePreferencesTransaction
import com.taostudio.tapaccounting.data.backup.SharedRecoverySecret
import com.taostudio.tapaccounting.data.backup.SharedRecoveryReadiness
import com.taostudio.tapaccounting.data.backup.SharedRecoverySecrets
import com.taostudio.tapaccounting.data.backup.SharedReconnectPreflight
import com.taostudio.tapaccounting.data.backup.SharedLedgerBackup
import com.taostudio.tapaccounting.data.backup.SharedRestoreData
import com.taostudio.tapaccounting.data.backup.SharedRestoreMode
import com.taostudio.tapaccounting.data.backup.assessSharedRecoveryReadiness
import com.taostudio.tapaccounting.data.backup.sharedRecoveryReadiness
import com.taostudio.tapaccounting.data.backup.WebDavClient
import com.google.android.material.switchmaterial.SwitchMaterial
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import com.taostudio.tapaccounting.data.repository.BackupRepository
import com.taostudio.tapaccounting.data.repository.MergeRestoreResult
import com.taostudio.tapaccounting.logic.CategoryNameNormalizer
import com.taostudio.tapaccounting.data.sync.SharedMutationHooks
import com.taostudio.tapaccounting.data.sync.SharedSyncScheduler
import com.taostudio.tapaccounting.ui.dialog.ElegantDatePickerSheet
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 备份与恢复 UI 入口。导出/导入的是 [BackupManager] 的 `.bak` 文件。
 *
 * 降级场景：若需提示用户恢复 [com.taostudio.tapaccounting.data.local.DatabaseDowngradeHelper]
 * 的自动 `.db` 备份，可在此或 [BackupHomeActivity] 检测 `listBackups()` / `getLastBackupInfo()`，
 * 与 `.bak` 流程分开处理。
 */
class BackupActivity : AppCompatActivity() {
    private enum class BackupPreset { LITE, FULL, CUSTOM }
    private enum class PendingStorageAction { BACKUP, RESTORE }

    companion object {
        const val EXTRA_OPEN_SECTION = "backup_open_section"
        const val EXTRA_QUICK_ONESHOT = "backup_quick_oneshot"
        const val SECTION_DO_BACKUP = "do_backup"
        const val SECTION_RESTORE = "restore"
        const val SECTION_SAVE_AS = "save_as"
        const val SECTION_CSV = "csv"
        const val SECTION_CLOUD = "cloud"

        private const val BACKUP_PREFS = "tap_backup_prefs"
        private const val CLOUD_PREFS = "tap_cloud_backup_prefs"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USER = "webdav_user"
        private const val KEY_WEBDAV_DIR = "webdav_dir"
        private const val KEY_DEVICE_NAME = "webdav_device_name"
        private const val MAX_AUTH_ATTEMPTS = 10
        private val RESTORE_MUTEX = Mutex()
    }

    private val backupRepository by lazy { BackupRepository(AppDatabase.getDatabase(this)) }
    private val recoverySnapshotService by lazy { RecoverySnapshotService(this) }
    private var pendingSaveAsKey: BackupPasswordKeyMaterial? = null
    private var pendingStorageAction: PendingStorageAction? = null

    private val saveBackupAsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val passwordKey = pendingSaveAsKey.also { pendingSaveAsKey = null }
        val targetUri = result.data?.data
        if (result.resultCode == RESULT_OK && targetUri != null && passwordKey != null) {
            performBackup(targetUri, passwordKey)
        } else {
            passwordKey?.keyBytes?.fill(0)
        }
        if (result.resultCode != RESULT_OK && isQuickOneShot()) {
            finish()
        }
    }

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let(::showRestoreDialog)
        } else if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_RESTORE) {
            finish()
        }
    }

    private val saveCsvLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let(::performCsvExport)
        } else if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_CSV && isQuickOneShot()) {
            finish()
        }
    }

    private val openCsvLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let(::performCsvImport)
        } else if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_CSV && isQuickOneShot()) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_new)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        setupBackupPresetUi()
        setupAutoBackupUi()
        setupCloudSettingsUi()
        // Do not retain the insecure convenience PIN used by very old backup builds.
        getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE).edit().remove("backup_last_pin_v1").apply()

        findViewById<MaterialButton>(R.id.btn_do_backup).setOnClickListener {
            ensureBackupPasswordReady {
                // 主操作：直接向默认目录发布一个独立加密版本。
                performBackupToDefaultDir()
            }
        }
        findViewById<MaterialButton>(R.id.btn_backup_save_as).setOnClickListener {
            promptBackupPasswordSetup(persistForDirectBackup = false) { passwordKey ->
                pendingSaveAsKey?.keyBytes?.fill(0)
                pendingSaveAsKey = passwordKey
                // 另存为：带时间戳的新文件名，用户选择保存位置
                val fileName = BackupDefaultDirHelper.generateManualBackupFileName()
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_TITLE, fileName)
                }
                saveBackupAsLauncher.launch(intent)
            }
        }
        findViewById<MaterialButton>(R.id.btn_do_restore).setOnClickListener {
            showPrivateBackupBrowser()
        }

        findViewById<MaterialButton>(R.id.btn_export_csv).setOnClickListener {
            val fileName = "TapAccount_Bills_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            saveCsvLauncher.launch(intent)
        }

        findViewById<MaterialButton>(R.id.btn_import_csv).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            openCsvLauncher.launch(intent)
        }
        findViewById<MaterialButton>(R.id.btn_repair_csv_assets).setOnClickListener {
            repairMissingCsvAssetBindings()
        }
        setupCleanupButtons()
        updateBackupProtectionHint()
        updateBackupModeHint()
        handleOpenSectionIntent()
    }

    override fun onDestroy() {
        pendingSaveAsKey?.keyBytes?.fill(0)
        pendingSaveAsKey = null
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BackupDefaultDirHelper.REQUEST_CODE_STORAGE_PERMISSION) {
            resumePendingStorageAction()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BackupDefaultDirHelper.REQUEST_CODE_STORAGE_PERMISSION) {
            resumePendingStorageAction()
        }
    }

    private fun resumePendingStorageAction() {
        val action = pendingStorageAction.also { pendingStorageAction = null }
        if (!BackupDefaultDirHelper.hasStoragePermission(this)) {
            Utils.toast(this, getString(R.string.backup_permission_denied))
            return
        }
        when (action) {
            PendingStorageAction.BACKUP -> performBackupToDefaultDir()
            PendingStorageAction.RESTORE -> showPrivateBackupBrowser()
            null -> updateBackupModeHint()
        }
    }

    private fun setupBackupPresetUi() {
        val rgPreset = findViewById<RadioGroup>(R.id.rg_backup_preset)
        rgPreset.setOnCheckedChangeListener { _, checkedId ->
            val preset = when (checkedId) {
                R.id.rb_backup_preset_full -> BackupPreset.FULL
                R.id.rb_backup_preset_custom -> BackupPreset.CUSTOM
                else -> BackupPreset.LITE
            }
            applyBackupPreset(preset)
        }
        applyBackupPreset(BackupPreset.LITE)
        val assets = findViewById<MaterialCheckBox>(R.id.cb_assets)
        val bills = findViewById<MaterialCheckBox>(R.id.cb_bills)
        bills.setOnCheckedChangeListener { _, checked ->
            if (checked) assets.isChecked = true
        }
        assets.setOnCheckedChangeListener { _, checked ->
            if (!checked && bills.isChecked) bills.isChecked = false
        }
    }

    private fun promptRecoveryCode(
        message: String = getString(R.string.recovery_code_restore_message),
        onCancelled: () -> Unit = {},
        onConfirmed: (BackupRecoveryCode) -> Unit
    ) {
        val input = EditText(this).apply {
            hint = getString(R.string.input_recovery_code)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(false)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.recovery_code_title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(R.string.continue_restore, null)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled() }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.95f,
            cancelOnTouchOutside = false,
            useSolidPanelBackground = true
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val canonical = input.text?.toString().orEmpty().trim().uppercase(Locale.US)
            val code = runCatching { BackupRecoveryCode.parse(canonical) }.getOrNull()
            if (code == null) {
                input.error = getString(R.string.recovery_code_invalid)
            } else {
                dialog.dismiss()
                onConfirmed(code)
            }
        }
    }

    private fun hasUsableBackupPasswordKey(): Boolean {
        val material = runCatching { BackupPasswordKeyStore.load(this) }.getOrNull() ?: return false
        material.keyBytes.fill(0)
        return true
    }

    private fun ensureBackupPasswordReady(onReady: () -> Unit) {
        if (hasUsableBackupPasswordKey()) {
            onReady()
            return
        }
        promptBackupPasswordSetup(persistForDirectBackup = true) { material ->
            material.keyBytes.fill(0)
            onReady()
        }
    }

    /** Direct backups remember a derived key; Save As keeps its independently derived key in memory. */
    private fun promptBackupPasswordSetup(
        persistForDirectBackup: Boolean,
        onReady: (BackupPasswordKeyMaterial) -> Unit
    ) {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val first = EditText(this).apply {
            hint = getString(R.string.backup_password_input_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(BackupPasswordCrypto.MAX_PIN_DIGITS))
        }
        val confirmation = EditText(this).apply {
            hint = getString(R.string.backup_password_confirm_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(BackupPasswordCrypto.MAX_PIN_DIGITS))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(first, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(confirmation, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(
                if (persistForDirectBackup) {
                    R.string.backup_password_setup_title
                } else {
                    R.string.backup_save_as_password_title
                }
            )
            .setMessage(
                if (persistForDirectBackup) {
                    R.string.backup_password_setup_message
                } else {
                    R.string.backup_save_as_password_message
                }
            )
            .setView(content)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.92f,
            cancelOnTouchOutside = false,
            useSolidPanelBackground = true
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val pin = first.text?.toString().orEmpty().trim()
            val repeated = confirmation.text?.toString().orEmpty().trim()
            if (runCatching { BackupPasswordCrypto.requireValidPin(pin) }.isFailure) {
                first.error = getString(R.string.backup_password_invalid)
                return@setOnClickListener
            }
            if (pin != repeated) {
                confirmation.error = getString(R.string.backup_password_mismatch)
                return@setOnClickListener
            }
            button.isEnabled = false
            lifecycleScope.launch(Dispatchers.Default) {
                val result = runCatching {
                    if (persistForDirectBackup) {
                        BackupPasswordKeyStore.configure(this@BackupActivity, pin)
                    } else {
                        BackupPasswordCrypto.create(pin)
                    }
                }
                withContext(Dispatchers.Main) {
                    button.isEnabled = true
                    result.fold(
                        onSuccess = { material ->
                            dialog.dismiss()
                            if (persistForDirectBackup) {
                                updateBackupProtectionHint()
                                updateAutoBackupStatus()
                                Utils.toast(this@BackupActivity, getString(R.string.backup_password_saved))
                            }
                            onReady(material)
                        },
                        onFailure = { error ->
                            Log.e("BackupActivity", "保存备份密码失败", error)
                            Utils.toast(this@BackupActivity, rootCauseMessage(error))
                        }
                    )
                }
            }
        }
    }

    private fun updateBackupProtectionHint() {
        findViewById<TextView>(R.id.tv_backup_portable_hint).text = getString(
            if (hasUsableBackupPasswordKey()) {
                R.string.backup_password_configured_desc
            } else {
                R.string.backup_password_unconfigured_desc
            }
        )
    }

    private fun promptBackupPasswordForRestore(
        onCancelled: () -> Unit,
        onConfirmed: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            hint = getString(R.string.backup_password_input_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(BackupPasswordCrypto.MAX_PIN_DIGITS))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.backup_password_restore_title)
            .setMessage(R.string.backup_password_restore_message)
            .setView(input)
            .setPositiveButton(R.string.continue_restore, null)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled() }
            .create()
        dialog.setOnCancelListener { onCancelled() }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.9f,
            cancelOnTouchOutside = false,
            useSolidPanelBackground = true
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = input.text?.toString().orEmpty().trim()
            if (runCatching { BackupPasswordCrypto.requireValidPin(pin) }.isFailure) {
                input.error = getString(R.string.backup_password_invalid)
            } else {
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                onConfirmed(pin)
            }
        }
    }

    private fun promptSharedRestoreMode(
        onCancelled: () -> Unit = {},
        onSelected: (SharedRestoreMode) -> Unit
    ) {
        val labels = arrayOf(
            "${getString(R.string.shared_restore_reconnect)}\n${getString(R.string.shared_restore_reconnect_desc)}",
            "${getString(R.string.shared_restore_local_copy)}\n${getString(R.string.shared_restore_local_copy_desc)}"
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.shared_restore_title)
            .setMessage(R.string.shared_restore_message)
            .setItems(labels) { _, which ->
                onSelected(
                    if (which == 0) SharedRestoreMode.RECONNECT else SharedRestoreMode.LOCAL_COPY
                )
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled() }
            .create()
        dialog.setOnCancelListener { onCancelled() }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.95f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun promptIncompleteSharedRestore(
        onCancelled: () -> Unit = {},
        onContinueAsLocalCopy: () -> Unit
    ) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.shared_restore_incomplete_title)
            .setMessage(R.string.shared_restore_incomplete_message)
            .setPositiveButton(R.string.shared_restore_continue_local) { _, _ ->
                onContinueAsLocalCopy()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled() }
            .create()
        dialog.setOnCancelListener { onCancelled() }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.92f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun setupAutoBackupUi() {
        val switchEnabled = findViewById<SwitchMaterial>(R.id.switch_auto_backup)
        val switchCloud = findViewById<SwitchMaterial>(R.id.switch_auto_cloud)
        val groupOptions = findViewById<View>(R.id.group_auto_backup_options)
        val rgInterval = findViewById<RadioGroup>(R.id.rg_auto_backup_interval)
        val rgMode = findViewById<RadioGroup>(R.id.rg_auto_backup_mode)

        // Load saved settings
        val enabled = AutoBackupWorker.isEnabled(this)
        val intervalHours = AutoBackupWorker.getIntervalHours(this)
        val cloudEnabled = AutoBackupWorker.isCloudEnabled(this)
        val mode = AutoBackupWorker.getBackupMode(this)

        switchEnabled.isChecked = enabled
        switchCloud.isChecked = cloudEnabled
        groupOptions.visibility = if (enabled) View.VISIBLE else View.GONE

        when (intervalHours) {
            6 -> rgInterval.check(R.id.rb_interval_6h)
            24 -> rgInterval.check(R.id.rb_interval_24h)
            else -> rgInterval.check(R.id.rb_interval_12h)
        }
        rgMode.check(if (mode == "full") R.id.rb_auto_mode_full else R.id.rb_auto_mode_lite)

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasUsableBackupPasswordKey()) {
                switchEnabled.isChecked = false
                ensureBackupPasswordReady { switchEnabled.isChecked = true }
                return@setOnCheckedChangeListener
            }
            groupOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            saveAutoBackupSettings()
        }
        switchCloud.setOnCheckedChangeListener { _, _ -> saveAutoBackupSettings() }
        rgInterval.setOnCheckedChangeListener { _, _ -> saveAutoBackupSettings() }
        rgMode.setOnCheckedChangeListener { _, _ -> saveAutoBackupSettings() }

        updateAutoBackupStatus()
    }

    private fun saveAutoBackupSettings() {
        val enabled = findViewById<SwitchMaterial>(R.id.switch_auto_backup).isChecked
        val cloudEnabled = findViewById<SwitchMaterial>(R.id.switch_auto_cloud).isChecked
        val intervalHours = when (findViewById<RadioGroup>(R.id.rg_auto_backup_interval).checkedRadioButtonId) {
            R.id.rb_interval_6h -> 6
            R.id.rb_interval_24h -> 24
            else -> 12
        }
        val mode = when (findViewById<RadioGroup>(R.id.rg_auto_backup_mode).checkedRadioButtonId) {
            R.id.rb_auto_mode_full -> "full"
            else -> "lite"
        }
        AutoBackupWorker.saveSettings(this, enabled, intervalHours, cloudEnabled, mode)
        updateAutoBackupStatus()
    }

    private fun updateAutoBackupStatus() {
        val tv = findViewById<TextView>(R.id.tv_auto_backup_status)
        if (!AutoBackupWorker.isEnabled(this)) {
            tv.text = getString(R.string.auto_backup_disabled)
            return
        }
        if (!hasUsableBackupPasswordKey()) {
            tv.text = getString(R.string.auto_backup_waiting_for_password)
            return
        }
        val lastTime = AutoBackupWorker.getLastBackupTime(this)
        val lastResult = AutoBackupWorker.getLastBackupResult(this)
        val interval = AutoBackupWorker.getIntervalHours(this)
        val mode = if (AutoBackupWorker.getBackupMode(this) == "full") getString(R.string.backup_full) else getString(R.string.backup_lite)
        val cloud = if (AutoBackupWorker.isCloudEnabled(this)) getString(R.string.auto_backup_cloud_suffix) else ""

        tv.text = if (lastTime > 0) {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            getString(R.string.backup_status_with_history_fmt, sdf.format(Date(lastTime)), lastResult, interval, mode, cloud)
        } else {
            getString(R.string.backup_status_no_history_fmt, interval, mode, cloud)
        }
    }

    // ── Cleanup bills ──────────────────────────────────────────────

    private fun setupCleanupButtons() {
        findViewById<MaterialButton>(R.id.btn_cleanup_by_date).setOnClickListener {
            showDateRangeCleanupDialog()
        }
        findViewById<MaterialButton>(R.id.btn_cleanup_by_book).setOnClickListener {
            showBookCleanupDialog()
        }
        findViewById<MaterialButton>(R.id.btn_cleanup_all).setOnClickListener {
            showCleanupAllDialog()
        }
    }

    private fun showDateRangeCleanupDialog() {
        val cal = Calendar.getInstance()
        val endDate = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startDate = cal.timeInMillis

        showDatePicker(startDate, true) { startMs ->
            showDatePicker(endDate, false) { endMs ->
                if (endMs < startMs) {
                    Utils.toast(this, getString(R.string.invalid_date_range))
                    return@showDatePicker
                }
                val endOfDay = endMs + 86400000L - 1
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(this@BackupActivity)
                    val count = db.billDao().countBillsBetweenTimes(startMs, endOfDay)
                    val sum = db.billDao().sumAmountBetweenTimes(startMs, endOfDay)
                    withContext(Dispatchers.Main) {
                        if (count == 0) {
                            Utils.toast(this@BackupActivity, getString(R.string.no_bills_in_range))
                            return@withContext
                        }
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        confirmCleanup(
                            title = getString(R.string.cleanup_date_range_title),
                            message = getString(R.string.cleanup_date_range_message_fmt, sdf.format(Date(startMs)), sdf.format(Date(endMs)), count, String.format("%.2f", sum)),
                            onConfirm = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val bills = db.billDao().getBillsBetweenTimesList(startMs, endOfDay)
                                    SharedMutationHooks.deleteBillsPermanently(db, bills)
                                    withContext(Dispatchers.Main) {
                                        Utils.toast(this@BackupActivity, getString(R.string.cleaned_count_fmt, count))
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun showBookCleanupDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@BackupActivity)
            val books = db.billDao().getAllBookNames()
            withContext(Dispatchers.Main) {
                if (books.isEmpty()) {
                    Utils.toast(this@BackupActivity, getString(R.string.no_book_data))
                    return@withContext
                }
                var selectedIndex = 0
                val dialog = AlertDialog.Builder(this@BackupActivity)
                    .setTitle(R.string.select_book_to_clear)
                    .setSingleChoiceItems(books.toTypedArray(), 0) { _, which -> selectedIndex = which }
                    .setPositiveButton(R.string.next_step) { _, _ ->
                        val book = books[selectedIndex]
                        lifecycleScope.launch(Dispatchers.IO) {
                            val count = db.billDao().countBillsByBookName(book)
                            withContext(Dispatchers.Main) {
                                if (count == 0) {
                                    Utils.toast(this@BackupActivity, getString(R.string.book_no_bills_fmt, book))
                                    return@withContext
                                }
                                confirmCleanup(
                                    title = getString(R.string.clear_book_title_fmt, book),
                                    message = getString(R.string.clear_book_message_fmt, count),
                                    onConfirm = {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            val bills = db.billDao().getAllByBookName(book)
                                            SharedMutationHooks.deleteBillsPermanently(db, bills)
                                            withContext(Dispatchers.Main) {
                                                Utils.toast(this@BackupActivity, getString(R.string.book_cleared_fmt, book, count))
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
            }
        }
    }

    private fun showCleanupAllDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@BackupActivity)
            val count = db.billDao().getAllBillsList().size
            withContext(Dispatchers.Main) {
                if (count == 0) {
                    Utils.toast(this@BackupActivity, getString(R.string.no_bill_data))
                    return@withContext
                }
                confirmCleanup(
                    title = getString(R.string.clear_all_bills_title),
                    message = getString(R.string.clear_all_bills_message_fmt, count),
                    onConfirm = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val bills = db.billDao().getAllBillsList()
                            SharedMutationHooks.deleteBillsPermanently(db, bills)
                            withContext(Dispatchers.Main) {
                                Utils.toast(this@BackupActivity, getString(R.string.all_cleared_fmt, count))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun showDatePicker(initialMs: Long, isStart: Boolean, onPicked: (Long) -> Unit) {
        ElegantDatePickerSheet.show(
            context = this,
            initialTimeMillis = initialMs,
            onDateSelected = onPicked
        )
    }

    private fun confirmCleanup(title: String, message: String, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.confirm_cleanup) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun applyBackupPreset(preset: BackupPreset) {
        val showCustom = preset == BackupPreset.CUSTOM
        val visibility = if (showCustom) View.VISIBLE else View.GONE
        findViewById<View>(R.id.group_backup_core).visibility = visibility
        findViewById<View>(R.id.group_backup_chat).visibility = visibility
        findViewById<View>(R.id.group_backup_settings).visibility = visibility

        fun setAll(value: Boolean) {
            findViewById<MaterialCheckBox>(R.id.cb_assets).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_categories).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_bills).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_rules).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_chat_messages).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_chat_media).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_general_basic).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_general_assets).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_general_cloud).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_display_entries).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_display_bills).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_display_multibill).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_ai_core).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_ai_chat).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_books).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_advanced_runtime).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_banners).isChecked = value
        }

        val hint = findViewById<TextView>(R.id.tv_backup_preset_hint)
        when (preset) {
            BackupPreset.LITE -> {
                setAll(true)
                findViewById<MaterialCheckBox>(R.id.cb_chat_media).isChecked = false
                hint.text = getString(R.string.backup_lite_hint)
            }
            BackupPreset.FULL -> {
                setAll(true)
                hint.text = getString(R.string.backup_full_hint)
            }
            BackupPreset.CUSTOM -> {
                hint.text = getString(R.string.backup_custom_hint)
            }
        }
    }

    private fun handleOpenSectionIntent() {
        when (intent?.getStringExtra(EXTRA_OPEN_SECTION)) {
            SECTION_DO_BACKUP -> {
                findViewById<MaterialButton>(R.id.btn_do_backup).performClick()
            }
            SECTION_RESTORE -> {
                findViewById<MaterialButton>(R.id.btn_do_restore).performClick()
            }
            SECTION_SAVE_AS -> {
                findViewById<MaterialButton>(R.id.btn_backup_save_as).performClick()
            }
            SECTION_CSV -> {
                if (isQuickOneShot()) {
                    findViewById<MaterialButton>(R.id.btn_import_csv).performClick()
                } else {
                    showCsvQuickActionDialog()
                }
            }
            SECTION_CLOUD -> scrollToSection(R.id.card_cloud_backup)
        }
    }

    private fun isQuickOneShot(): Boolean =
        intent?.getBooleanExtra(EXTRA_QUICK_ONESHOT, false) == true

    private fun scrollToSection(sectionId: Int) {
        val scroll = findViewById<ScrollView>(R.id.backup_scroll)
        val target = findViewById<View>(sectionId)
        scroll.post { scroll.smoothScrollTo(0, target.top) }
    }

    private fun setupCloudSettingsUi() {
        loadCloudSettings()
        findViewById<MaterialButton>(R.id.btn_save_cloud_settings).setOnClickListener {
            saveCloudSettings()
            Utils.toast(this, getString(R.string.cloud_saved))
        }
        findViewById<MaterialButton>(R.id.btn_test_cloud_connection).setOnClickListener {
            saveCloudSettings()
            val config = readCloudConfigOrToast() ?: return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    WebDavClient.testConnection(config)
                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.connection_success)) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.connection_failed)) }
                }
            }
        }
        findViewById<MaterialButton>(R.id.btn_manual_upload).setOnClickListener {
            saveCloudSettings()
            val config = readCloudConfigOrToast() ?: return@setOnClickListener
            val options = collectBackupOptions()
            val modeTag = currentBackupModeTag()
            if (!options.hasAnyModuleSelected()) {
                Utils.toast(this, getString(R.string.select_module))
                return@setOnClickListener
            }
            performCloudUpload(config, options, modeTag)
        }
        findViewById<MaterialButton>(R.id.btn_manual_download).setOnClickListener {
            saveCloudSettings()
            val config = readCloudConfigOrToast() ?: return@setOnClickListener
            performCloudDownload(config)
        }
        findViewById<MaterialButton>(R.id.btn_show_cleanup_policy).setOnClickListener {
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.cloud_retention_title)
                .setMessage(getString(R.string.backup_retain_policy_desc))
                .setPositiveButton(R.string.i_know, null)
                .create()
            OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
        }
    }

    private fun saveCloudSettings() {
        val password = findViewById<EditText>(R.id.et_webdav_pass).text?.toString().orEmpty()
        getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_WEBDAV_URL, findViewById<EditText>(R.id.et_webdav_url).text?.toString().orEmpty().trim())
            .putString(KEY_WEBDAV_USER, findViewById<EditText>(R.id.et_webdav_user).text?.toString().orEmpty().trim())
            .putString(KEY_WEBDAV_DIR, findViewById<EditText>(R.id.et_webdav_dir).text?.toString().orEmpty().trim())
            .putString(KEY_DEVICE_NAME, findViewById<EditText>(R.id.et_device_name).text?.toString().orEmpty().trim())
            .apply()
        CloudBackupCredentials.savePassword(this, password)
    }

    private fun loadCloudSettings() {
        val sp = getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE)
        findViewById<EditText>(R.id.et_webdav_url).setText(sp.getString(KEY_WEBDAV_URL, "https://dav.jianguoyun.com/dav/") ?: "")
        findViewById<EditText>(R.id.et_webdav_user).setText(sp.getString(KEY_WEBDAV_USER, "") ?: "")
        findViewById<EditText>(R.id.et_webdav_pass).setText(CloudBackupCredentials.loadPassword(this) ?: "")
        findViewById<EditText>(R.id.et_webdav_dir).setText(sp.getString(KEY_WEBDAV_DIR, "TapAccount") ?: "TapAccount")
        findViewById<EditText>(R.id.et_device_name).setText(sp.getString(KEY_DEVICE_NAME, android.os.Build.MODEL ?: "android") ?: "android")
    }

    private fun readCloudConfigOrToast(): CloudBackupConfig? {
        val url = findViewById<EditText>(R.id.et_webdav_url).text?.toString().orEmpty().trim()
        val user = findViewById<EditText>(R.id.et_webdav_user).text?.toString().orEmpty().trim()
        val pass = findViewById<EditText>(R.id.et_webdav_pass).text?.toString().orEmpty()
        val dir = findViewById<EditText>(R.id.et_webdav_dir).text?.toString().orEmpty().trim().ifBlank { "TapAccount" }
        val device = findViewById<EditText>(R.id.et_device_name).text?.toString().orEmpty().trim().ifBlank { "device" }

        return when {
            url.isBlank() -> {
                Utils.toast(this, getString(R.string.input_webdav_url))
                null
            }
            user.isBlank() -> {
                Utils.toast(this, getString(R.string.input_webdav_account))
                null
            }
            pass.isBlank() -> {
                Utils.toast(this, getString(R.string.input_webdav_password))
                null
            }
            else -> CloudBackupConfig(
                baseUrl = url,
                username = user,
                password = pass,
                remoteDir = dir,
                deviceName = device
            )
        }
    }

    private fun currentBackupModeTag(): String =
        when (findViewById<RadioGroup>(R.id.rg_backup_preset).checkedRadioButtonId) {
            R.id.rb_backup_preset_full -> "full"
            R.id.rb_backup_preset_custom -> "custom"
            else -> "lite"
        }

    private fun performCloudUpload(
        config: CloudBackupConfig,
        options: BackupOptions,
        modeTag: String
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val tempFile = File(cacheDir, "temp_cloud_upload_$ts.bak")
            try {
                val created = buildBackupArchiveFile(tempFile, options)
                val fileName = BackupArtifactNames.create(
                    deviceName = config.deviceName,
                    mode = modeTag,
                    createdAt = Instant.ofEpochMilli(created.manifest.createdAt),
                    backupId = created.manifest.backupId
                )
                WebDavClient.uploadBackup(config, fileName, tempFile)
                runCatching { WebDavClient.cleanupBackupHistory(config) }
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.uploaded_fmt, fileName))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.upload_failed))
                }
            } finally {
                runCatching { tempFile.delete() }
            }
        }
    }

    private fun performCloudDownload(config: CloudBackupConfig) {
        lifecycleScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { WebDavClient.listAllBackups(config) }
                if (entries.isEmpty()) {
                    Utils.toast(this@BackupActivity, getString(R.string.cloud_no_backup))
                    return@launch
                }
                val labels = entries.map(::cloudBackupLabel).toTypedArray()
                val dialog = AlertDialog.Builder(this@BackupActivity)
                    .setTitle(R.string.choose_cloud_backup)
                    .setItems(labels) { _, which -> downloadCloudBackup(config, entries[which]) }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                OverlayDialogs.showPageCenterDialog(
                    dialog = dialog,
                    ctx = this@BackupActivity,
                    widthRatio = 0.95f,
                    cancelOnTouchOutside = true,
                    useSolidPanelBackground = true
                )
            } catch (e: Exception) {
                Log.e("BackupActivity", "读取云端备份历史失败", e)
                Utils.toast(this@BackupActivity, getString(R.string.download_failed))
            }
        }
    }

    private fun cloudBackupLabel(entry: CloudBackupEntry): String {
        val device = entry.deviceName?.takeIf(String::isNotBlank)
            ?: getString(R.string.cloud_legacy_root)
        val size = entry.contentLength?.takeIf { it > 0L }?.let {
            String.format(Locale.getDefault(), " · %.1f MB", it / (1024.0 * 1024.0))
        }.orEmpty()
        return "$device · ${entry.timestamp}\n${entry.mode.uppercase(Locale.getDefault())}$size"
    }

    private fun downloadCloudBackup(config: CloudBackupConfig, entry: CloudBackupEntry) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tempFile = File(cacheDir, "temp_cloud_restore_${UUID.randomUUID()}.bak")
            try {
                WebDavClient.downloadBackup(config, entry, tempFile)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.downloaded_fmt, entry.name))
                }
                showRestoreDialogFromFile(tempFile)
            } catch (e: Exception) {
                tempFile.delete()
                Log.e("BackupActivity", "下载云端备份失败", e)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.download_failed))
                }
            }
        }
    }

    private fun showCsvQuickActionDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.csv_tools_title)
            .setItems(arrayOf(getString(R.string.export_csv), getString(R.string.import_bills), getString(R.string.fix_asset_binding))) { _, which ->
                when (which) {
                    0 -> findViewById<MaterialButton>(R.id.btn_export_csv).performClick()
                    1 -> findViewById<MaterialButton>(R.id.btn_import_csv).performClick()
                    2 -> repairMissingCsvAssetBindings()
                }
            }
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun performBackup(
        uri: Uri,
        passwordKey: BackupPasswordKeyMaterial? = null
    ) {
        val options = collectBackupOptions()
        if (!options.hasAnyModuleSelected()) {
            passwordKey?.keyBytes?.fill(0)
            Utils.toast(this, getString(R.string.select_module))
            return
        }
        performBackupInternal(uri, options, passwordKey)
    }

    /** 向唯一、用户可见的根目录位置发布一份不可变加密备份。 */
    private fun performBackupToDefaultDir() {
        if (!BackupDefaultDirHelper.hasStoragePermission(this)) {
            pendingStorageAction = PendingStorageAction.BACKUP
            BackupDefaultDirHelper.requestStoragePermissionIfNeeded(this)
            return
        }
        val dir = try {
            BackupDefaultDirHelper.getDefaultBackupDir(this)
        } catch (error: Exception) {
            Utils.toast(this, rootCauseMessage(error))
            return
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Utils.toast(this, getString(R.string.create_file_failed))
            return
        }

        val options = collectBackupOptions()
        if (!options.hasAnyModuleSelected()) {
            Utils.toast(this, getString(R.string.select_module))
            return
        }

        performBackupToDirectory(dir, options)
    }

    private fun collectBackupOptions(): BackupOptions {
        return BackupOptions(
            backupAssets = findViewById<MaterialCheckBox>(R.id.cb_assets).isChecked,
            backupCategories = findViewById<MaterialCheckBox>(R.id.cb_categories).isChecked,
            backupBills = findViewById<MaterialCheckBox>(R.id.cb_bills).isChecked,
            backupRules = findViewById<MaterialCheckBox>(R.id.cb_rules).isChecked,
            backupChatMessages = findViewById<MaterialCheckBox>(R.id.cb_chat_messages).isChecked,
            backupChatMedia = findViewById<MaterialCheckBox>(R.id.cb_chat_media).isChecked,
            backupSettingsGeneralBasic = findViewById<MaterialCheckBox>(R.id.cb_settings_general_basic).isChecked,
            backupSettingsGeneralAssets = findViewById<MaterialCheckBox>(R.id.cb_settings_general_assets).isChecked,
            backupSettingsGeneralCloud = findViewById<MaterialCheckBox>(R.id.cb_settings_general_cloud).isChecked,
            backupSettingsDisplayEntries = findViewById<MaterialCheckBox>(R.id.cb_settings_display_entries).isChecked,
            backupSettingsDisplayBills = findViewById<MaterialCheckBox>(R.id.cb_settings_display_bills).isChecked,
            backupSettingsDisplayMultiBill = findViewById<MaterialCheckBox>(R.id.cb_settings_display_multibill).isChecked,
            backupSettingsAiCore = findViewById<MaterialCheckBox>(R.id.cb_settings_ai_core).isChecked,
            backupSettingsAiChat = findViewById<MaterialCheckBox>(R.id.cb_settings_ai_chat).isChecked,
            backupSettingsBooks = findViewById<MaterialCheckBox>(R.id.cb_settings_books).isChecked,
            backupSettingsAdvancedRuntime = findViewById<MaterialCheckBox>(R.id.cb_settings_advanced_runtime).isChecked,
            backupBanners = findViewById<MaterialCheckBox>(R.id.cb_banners).isChecked
        )
    }

    private fun BackupOptions.hasAnyModuleSelected(): Boolean {
        return backupAssets || backupCategories || backupBills || backupRules ||
            backupChatMessages || backupChatMedia || backupSettingsGeneralBasic || backupSettingsGeneralAssets ||
            backupSettingsGeneralCloud || backupSettingsDisplayEntries || backupSettingsDisplayBills ||
            backupSettingsDisplayMultiBill || backupSettingsAiCore ||
            backupSettingsAiChat || backupSettingsBooks || backupSettingsAdvancedRuntime ||
            backupBanners
    }

    private fun performBackupInternal(
        uri: Uri,
        options: BackupOptions,
        passwordKey: BackupPasswordKeyMaterial? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tempFile = File(cacheDir, "temp_backup_${System.currentTimeMillis()}.bak")
            try {
                val created = buildBackupArchiveFile(tempFile, options, passwordKey)
                val output = contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("无法打开备份目标")
                output.use { target -> tempFile.inputStream().buffered().use { it.copyTo(target) } }

                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, backupSavedMessage(created.manifest.sharedRecoveryReadiness()))
                    if (isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.backup_failed))
                }
            } finally {
                passwordKey?.keyBytes?.fill(0)
                runCatching { tempFile.delete() }
            }
        }
    }

    private fun performBackupToDirectory(
        directory: File,
        options: BackupOptions
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tempFile = File(cacheDir, "temp_local_publish_${System.currentTimeMillis()}.bak")
            try {
                val created = buildBackupArchiveFile(tempFile, options)
                com.taostudio.tapaccounting.data.backup.LocalBackupPublisher.publish(
                    sourceFile = tempFile,
                    targetDirectory = directory,
                    deviceName = android.os.Build.MODEL ?: "android",
                    mode = currentBackupModeTag(),
                    backupId = created.manifest.backupId,
                    validate = { file ->
                        check(BackupFileFormatDetector.detect(file) == BackupFileFormat.V3_PASSWORD) {
                            "备份发布校验失败"
                        }
                    }
                )
                runCatching { LocalBackupHistory.cleanup(directory) }
                    .onFailure { Log.w("BackupActivity", "清理旧本地备份失败", it) }
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, backupSavedMessage(created.manifest.sharedRecoveryReadiness()))
                    if (isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                Log.e("BackupActivity", "本地备份失败", e)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, rootCauseMessage(e))
                }
            } finally {
                tempFile.delete()
            }
        }
    }

    private suspend fun buildBackupArchiveFile(
        outputFile: File,
        options: BackupOptions,
        passwordKey: BackupPasswordKeyMaterial? = null
    ) = recoverySnapshotService.create(
        outputFile = outputFile,
        policy = options.toContentPolicy(),
        suppliedPasswordKey = passwordKey
    )

    private fun backupSavedMessage(readiness: SharedRecoveryReadiness): String = getString(
        when (readiness) {
            SharedRecoveryReadiness.NOT_PRESENT -> R.string.backup_saved_v2
            SharedRecoveryReadiness.READY -> R.string.backup_saved_shared_recovery_ready
            SharedRecoveryReadiness.INCOMPLETE -> R.string.backup_saved_shared_recovery_incomplete
        }
    )

    private fun BackupOptions.toContentPolicy(): BackupContentPolicy {
        val dataModules = linkedSetOf<String>()
        if (backupAssets) dataModules += BackupModuleId.ASSETS
        if (backupCategories) dataModules += BackupModuleId.CATEGORIES
        if (backupBills) {
            dataModules += BackupModuleId.BILLS
            dataModules += BackupModuleId.DELETED_BILLS
            dataModules += BackupModuleId.INVESTMENT_LOTS
        }
        if (backupRules) dataModules += BackupModuleId.RULES
        if (backupChatMessages) dataModules += BackupModuleId.CHAT_MESSAGES
        // Budgets and recurrence rules are core financial records and never silently omitted.
        dataModules += BackupModuleId.BUDGETS
        dataModules += BackupModuleId.RECURRING_PATTERNS

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

    private fun updateBackupModeHint() {
        val tv = findViewById<TextView>(R.id.tv_backup_mode_hint)
        val defaultDir = runCatching { BackupDefaultDirHelper.getDefaultBackupDir(this) }.getOrNull()
        tv.text = when {
            defaultDir == null -> getString(R.string.backup_private_dir_unavailable)
            !BackupDefaultDirHelper.hasStoragePermission(this) ->
                getString(R.string.backup_public_dir_permission_needed, defaultDir.absolutePath)
            defaultDir.exists() -> getString(R.string.backup_private_dir_hint, defaultDir.absolutePath)
            else -> getString(R.string.backup_private_dir_not_exists, defaultDir.absolutePath)
        }
    }

    /** Lists the canonical backup directory; external Save As files remain available separately. */
    private fun showPrivateBackupBrowser() {
        if (!BackupDefaultDirHelper.hasStoragePermission(this)) {
            pendingStorageAction = PendingStorageAction.RESTORE
            BackupDefaultDirHelper.requestStoragePermissionIfNeeded(this)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val directory = runCatching { BackupDefaultDirHelper.getDefaultBackupDir(this@BackupActivity) }
                .getOrNull()
            val files = directory
                ?.also { if (!it.exists()) it.mkdirs() }
                ?.listFiles()
                .orEmpty()
                .asSequence()
                .filter(File::isFile)
                .filter { BackupFileFormatDetector.detect(it) != BackupFileFormat.UNKNOWN }
                .sortedByDescending(File::lastModified)
                .toList()
            withContext(Dispatchers.Main) {
                if (files.isEmpty()) {
                    val dialog = AlertDialog.Builder(this@BackupActivity)
                        .setTitle(R.string.private_backup_browser_title)
                        .setMessage(R.string.private_backup_browser_empty)
                        .setPositiveButton(R.string.choose_other_backup) { _, _ ->
                            launchExternalBackupPicker()
                        }
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            if (isQuickOneShot()) finish()
                        }
                        .create()
                    OverlayDialogs.showPageCenterDialog(
                        dialog = dialog,
                        ctx = this@BackupActivity,
                        widthRatio = 0.92f,
                        cancelOnTouchOutside = false,
                        useSolidPanelBackground = true
                    )
                } else {
                    showPrivateBackupList(files)
                }
            }
        }
    }

    private fun showPrivateBackupList(files: List<File>) {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_private_backup_browser, null)
        val container = content.findViewById<LinearLayout>(R.id.backup_entry_container)
        val scroll = content.findViewById<ScrollView>(R.id.backup_entry_scroll)
        content.findViewById<TextView>(R.id.tv_private_backup_count).text =
            getString(R.string.private_backup_count_fmt, files.size)

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton(R.string.choose_other_backup) { _, _ -> launchExternalBackupPicker() }
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (isQuickOneShot()) finish()
            }
            .create()

        val timeFormat = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.getDefault())
        val legacyTimeFormat = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault())
        files.forEachIndexed { index, file ->
            val parsed = BackupArtifactNames.parse(file.name)
            val row = LayoutInflater.from(this).inflate(
                R.layout.item_private_backup_entry,
                container,
                false
            )
            val mode = parsed?.mode.orEmpty()
            val modeLabel = when (mode) {
                "full" -> getString(R.string.backup_full)
                "custom" -> getString(R.string.backup_custom)
                "lite" -> getString(R.string.backup_lite)
                else -> getString(R.string.private_backup_legacy)
            }
            val (iconBackground, iconTint) = when (mode) {
                "full" -> Color.parseColor("#EAF7EF") to Color.parseColor("#2E8B57")
                "custom" -> Color.parseColor("#F3EEFF") to Color.parseColor("#7656C9")
                "lite" -> Color.parseColor("#EAF1FF") to Color.parseColor("#4F6EDB")
                else -> Color.parseColor("#F1F3F6") to Color.parseColor("#6F7B8D")
            }
            row.findViewById<MaterialCardView>(R.id.card_backup_type_icon)
                .setCardBackgroundColor(iconBackground)
            row.findViewById<ImageView>(R.id.iv_backup_type).imageTintList =
                ColorStateList.valueOf(iconTint)
            row.findViewById<TextView>(R.id.tv_backup_entry_mode).text =
                getString(R.string.private_backup_mode_fmt, modeLabel)
            row.findViewById<TextView>(R.id.tv_backup_entry_latest).visibility =
                if (index == 0) View.VISIBLE else View.GONE
            row.findViewById<TextView>(R.id.tv_backup_entry_time).text =
                parsed?.createdAt?.format(timeFormat)
                    ?: legacyTimeFormat.format(Date(file.lastModified()))

            val deviceName = parsed?.deviceName
                ?.replace('_', ' ')
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: getString(R.string.private_backup_unknown_device)
            row.findViewById<TextView>(R.id.tv_backup_entry_meta).text = getString(
                R.string.private_backup_device_size_fmt,
                deviceName,
                formatBackupFileSize(file.length())
            )
            row.findViewById<TextView>(R.id.tv_backup_entry_filename).text = file.name
            row.setOnClickListener {
                dialog.dismiss()
                stagePrivateBackupForRestore(file)
            }
            container.addView(row)
        }

        if (files.size > 3) {
            scroll.layoutParams = scroll.layoutParams.apply {
                height = (360 * resources.displayMetrics.density).toInt()
                    .coerceAtMost((resources.displayMetrics.heightPixels * 0.48f).toInt())
            }
        }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.94f,
            cancelOnTouchOutside = false,
            useSolidPanelBackground = true
        )
    }

    private fun formatBackupFileSize(bytes: Long): String {
        val kilobytes = bytes / 1024.0
        return if (kilobytes < 1024.0) {
            String.format(Locale.getDefault(), "%.1f KB", kilobytes)
        } else {
            String.format(Locale.getDefault(), "%.1f MB", kilobytes / 1024.0)
        }
    }

    private fun launchExternalBackupPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        openDocumentLauncher.launch(intent)
    }

    /** Restore preparation deletes its staged source, so never pass the durable private copy. */
    private fun stagePrivateBackupForRestore(backupFile: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val stagedSource = File(cacheDir, "restore_source_${UUID.randomUUID()}.bak")
            try {
                backupFile.inputStream().buffered().use { input ->
                    FileOutputStream(stagedSource).buffered().use(input::copyTo)
                }
                showRestoreDialogFromFile(stagedSource)
            } catch (error: Exception) {
                stagedSource.delete()
                Log.e("BackupActivity", "读取本地备份失败", error)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.file_corrupted))
                }
            }
        }
    }

    private fun showRestoreDialog(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            var sourceFile: File? = null
            try {
                val stagedSource = File(cacheDir, "restore_source_${UUID.randomUUID()}.bak")
                sourceFile = stagedSource
                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("无法读取备份文件")
                input.use { source -> FileOutputStream(stagedSource).use { source.copyTo(it) } }
                showRestoreDialogFromFile(stagedSource)
            } catch (e: Exception) {
                sourceFile?.delete()
                Log.e("BackupActivity", "解析备份文件失败", e)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.file_corrupted))
                }
            }
        }
    }

    private suspend fun showRestoreDialogFromFile(sourceFile: File) {
        when (BackupFileFormatDetector.detect(sourceFile)) {
            BackupFileFormat.ZIP -> {
                val payload = File(cacheDir, "restore_payload_${System.currentTimeMillis()}.zip")
                val prepared = recoverySnapshotService.prepareForRestore(sourceFile, payload)
                if (prepared.payloadFile != sourceFile) sourceFile.delete()
                showRestoreModules(prepared.payloadFile, allowSharedReconnect = false)
            }

            BackupFileFormat.V2_ENCRYPTED -> {
                withContext(Dispatchers.Main) {
                    promptRecoveryCodeWithAttempts(sourceFile)
                }
            }

            BackupFileFormat.V3_PASSWORD -> {
                val parameters = BackupPasswordEnvelope.readKdfParameters(sourceFile)
                val storedKey = runCatching { BackupPasswordKeyStore.load(this@BackupActivity) }
                    .getOrNull()
                    ?.takeIf { sameKdfParameters(it.parameters, parameters) }
                if (storedKey != null) {
                    val opened = try {
                        tryPreparePasswordRestore(sourceFile, storedKey, rememberKey = false)
                    } finally {
                        storedKey.keyBytes.fill(0)
                    }
                    if (opened) return
                }
                requestPasswordRestore(
                    sourceFile = sourceFile,
                    parameters = parameters,
                    rememberKey = !hasUsableBackupPasswordKey()
                )
            }

            BackupFileFormat.UNKNOWN -> throw com.taostudio.tapaccounting.data.backup.BackupFormatException(
                "文件不是受支持的备份格式"
            )
        }
    }

    private suspend fun tryPrepareEncryptedRestore(
        sourceFile: File,
        recoveryCode: BackupRecoveryCode
    ): Boolean {
        val payload = File(cacheDir, "restore_payload_${System.currentTimeMillis()}_${UUID.randomUUID()}.zip")
        return try {
            val prepared = recoverySnapshotService.prepareForRestore(sourceFile, payload, recoveryCode)
            sourceFile.delete()
            showRestoreModules(
                prepared.payloadFile,
                allowSharedReconnect = true
            )
            true
        } catch (_: BackupAuthenticationException) {
            payload.delete()
            false
        } catch (e: Exception) {
            payload.delete()
            sourceFile.delete()
            Log.e("BackupActivity", "校验备份归档失败", e)
            withContext(Dispatchers.Main) {
                Utils.toast(this@BackupActivity, getString(R.string.file_corrupted))
            }
            true
        }
    }

    private fun requestPasswordRestore(
        sourceFile: File,
        parameters: BackupPasswordKdfParameters,
        rememberKey: Boolean,
        attemptsRemaining: Int = MAX_AUTH_ATTEMPTS
    ) {
        lifecycleScope.launch(Dispatchers.Main) {
            promptBackupPasswordForRestore(
                onCancelled = { sourceFile.delete() },
                onConfirmed = { pin ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val keyBytes = try {
                            BackupPasswordCrypto.derive(pin, parameters)
                        } catch (error: Exception) {
                            Log.e("BackupActivity", "派生备份密钥失败", error)
                            withContext(Dispatchers.Main) {
                                Utils.toast(this@BackupActivity, rootCauseMessage(error))
                            }
                            sourceFile.delete()
                            return@launch
                        }
                        val material = BackupPasswordKeyMaterial(keyBytes, parameters)
                        val opened = try {
                            tryPreparePasswordRestore(sourceFile, material, rememberKey)
                        } finally {
                            keyBytes.fill(0)
                        }
                        if (!opened) {
                            withContext(Dispatchers.Main) {
                                if (attemptsRemaining <= 1) {
                                    Utils.toast(this@BackupActivity, getString(R.string.auth_too_many_attempts))
                                    sourceFile.delete()
                                } else {
                                    Utils.toast(this@BackupActivity, getString(R.string.backup_password_wrong))
                                    requestPasswordRestore(sourceFile, parameters, rememberKey, attemptsRemaining - 1)
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    private fun promptRecoveryCodeWithAttempts(
        sourceFile: File,
        attemptsRemaining: Int = MAX_AUTH_ATTEMPTS
    ) {
        promptRecoveryCode(onCancelled = { sourceFile.delete() }) { enteredCode ->
            lifecycleScope.launch(Dispatchers.IO) {
                if (!tryPrepareEncryptedRestore(sourceFile, enteredCode)) {
                    withContext(Dispatchers.Main) {
                        if (attemptsRemaining <= 1) {
                            Utils.toast(this@BackupActivity, getString(R.string.auth_too_many_attempts))
                            sourceFile.delete()
                        } else {
                            Utils.toast(this@BackupActivity, getString(R.string.recovery_code_wrong))
                            promptRecoveryCodeWithAttempts(sourceFile, attemptsRemaining - 1)
                        }
                    }
                }
            }
        }
    }

    private suspend fun tryPreparePasswordRestore(
        sourceFile: File,
        material: BackupPasswordKeyMaterial,
        rememberKey: Boolean
    ): Boolean {
        val payload = File(cacheDir, "restore_payload_${System.currentTimeMillis()}_${UUID.randomUUID()}.zip")
        return try {
            val prepared = recoverySnapshotService.prepareForRestore(
                sourceFile = sourceFile,
                clearPayloadFile = payload,
                passwordKey = material.keyBytes
            )
            if (rememberKey) {
                runCatching { BackupPasswordKeyStore.store(this@BackupActivity, material) }
                    .onFailure { Log.w("BackupActivity", "备份已解密，但无法在本机记住密钥", it) }
            }
            sourceFile.delete()
            showRestoreModules(prepared.payloadFile, allowSharedReconnect = true)
            true
        } catch (_: BackupAuthenticationException) {
            payload.delete()
            false
        } catch (error: Exception) {
            payload.delete()
            sourceFile.delete()
            Log.e("BackupActivity", "校验密码加密备份失败", error)
            withContext(Dispatchers.Main) {
                Utils.toast(this@BackupActivity, getString(R.string.file_corrupted))
            }
            true
        }
    }

    private fun sameKdfParameters(
        first: BackupPasswordKdfParameters,
        second: BackupPasswordKdfParameters
    ): Boolean = first.iterations == second.iterations && first.salt.contentEquals(second.salt)

    private suspend fun showRestoreModules(
        tempFile: File,
        allowSharedReconnect: Boolean
    ) {
        val dataMap = BackupManager.restore(tempFile)
        val hasBanners = BackupManager.hasBanners(tempFile)
        val hasChatMedia = BackupManager.hasChatMedia(tempFile)
        val settingsNeedsPin = dataMap["settings_ai_core"]
            ?.let { runCatching { BackupPinCrypto.hasEncryptedApi(parseSettingsRoot(it)) }.getOrDefault(false) }
            ?: false

        withContext(Dispatchers.Main) {
            val view = LayoutInflater.from(this@BackupActivity).inflate(R.layout.dialog_restore_modules, null)
            val moduleViews = mapOf(
                "assets" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_assets),
                "categories" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_categories),
                "bills" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_bills),
                "rules" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_rules),
                "budgets" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_budgets),
                "recurring_patterns" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_recurring_patterns),
                "chat_messages" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_chat_messages),
                "chat_media" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_chat_media),
                "settings_general_basic" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_general_basic),
                "settings_general_assets" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_general_assets),
                "settings_general_cloud" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_general_cloud),
                "settings_display_entries" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_display_entries),
                "settings_display_bills" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_display_bills),
                "settings_display_multibill" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_display_multibill),
                "settings_ai_core" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_ai_core),
                "settings_ai_chat" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_ai_chat),
                "settings_books" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_books),
                "settings_advanced_runtime" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_advanced_runtime),
                "settings_general" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_general_legacy),
                "settings_display" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_display_legacy),
                "settings_advanced" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_advanced_legacy),
                "banners" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_banners)
            )
            val aiCoreHint = view.findViewById<TextView>(R.id.tv_restore_settings_ai_core_hint)
            val rgRestoreMode = view.findViewById<RadioGroup>(R.id.rg_restore_mode)
            val tvModeHint = view.findViewById<TextView>(R.id.tv_restore_mode_hint)

            rgRestoreMode.setOnCheckedChangeListener { _, checkedId ->
                tvModeHint.text = if (checkedId == R.id.rb_restore_merge) {
                    getString(R.string.restore_merge_desc)
                } else {
                    getString(R.string.restore_overwrite_desc)
                }
            }

            var hasModules = false
            moduleViews.forEach { (key, checkBox) ->
                val present = when (key) {
                    "chat_media" -> hasChatMedia
                    "banners" -> hasBanners
                    else -> dataMap.containsKey(key)
                }
                checkBox.visibility = if (present) View.VISIBLE else View.GONE
                checkBox.isChecked = present
                hasModules = hasModules || present
            }
            if (dataMap.containsKey(BackupModuleId.INVESTMENT_LOTS) &&
                moduleViews.getValue("assets").visibility == View.VISIBLE
            ) {
                val assets = moduleViews.getValue("assets")
                val bills = moduleViews.getValue("bills")
                bills.setOnCheckedChangeListener { _, checked ->
                    if (checked) assets.isChecked = true
                }
                assets.setOnCheckedChangeListener { _, checked ->
                    if (!checked && bills.isChecked) bills.isChecked = false
                }
            }
            aiCoreHint.visibility = moduleViews.getValue("settings_ai_core").visibility

            val groupCore = view.findViewById<LinearLayout>(R.id.group_restore_core)
            val groupChat = view.findViewById<LinearLayout>(R.id.group_restore_chat)
            val groupSettings = view.findViewById<LinearLayout>(R.id.group_restore_settings)
            groupCore.visibility = visibleIfAny(
                moduleViews.getValue("assets"),
                moduleViews.getValue("categories"),
                moduleViews.getValue("bills"),
                moduleViews.getValue("rules"),
                moduleViews.getValue("budgets"),
                moduleViews.getValue("recurring_patterns"),
                moduleViews.getValue("banners")
            )
            groupChat.visibility = visibleIfAny(
                moduleViews.getValue("chat_messages"),
                moduleViews.getValue("chat_media")
            )
            groupSettings.visibility = visibleIfAny(
                moduleViews.getValue("settings_general_basic"),
                moduleViews.getValue("settings_general_assets"),
                moduleViews.getValue("settings_general_cloud"),
                moduleViews.getValue("settings_display_entries"),
                moduleViews.getValue("settings_display_bills"),
                moduleViews.getValue("settings_display_multibill"),
                moduleViews.getValue("settings_general"),
                moduleViews.getValue("settings_display"),
                moduleViews.getValue("settings_ai_core"),
                moduleViews.getValue("settings_ai_chat"),
                moduleViews.getValue("settings_books"),
                moduleViews.getValue("settings_advanced_runtime"),
                moduleViews.getValue("settings_advanced")
            )

            if (!hasModules) {
                tempFile.delete()
                Utils.toast(this@BackupActivity, getString(R.string.no_restorable_module))
                return@withContext
            }

            val dialog = AlertDialog.Builder(this@BackupActivity)
                .setView(view)
                .setPositiveButton(R.string.start_restore) { _, _ ->
                    val isMerge = rgRestoreMode.checkedRadioButtonId == R.id.rb_restore_merge

                    var options = RestoreOptions(
                        restoreAssets = moduleViews.getValue("assets").isChecked,
                        restoreCategories = moduleViews.getValue("categories").isChecked,
                        restoreBills = moduleViews.getValue("bills").isChecked,
                        restoreRules = moduleViews.getValue("rules").isChecked,
                        restoreBudgets = moduleViews.getValue("budgets").isChecked,
                        restoreRecurringPatterns = moduleViews.getValue("recurring_patterns").isChecked,
                        restoreChatMessages = moduleViews.getValue("chat_messages").isChecked,
                        restoreChatMedia = moduleViews.getValue("chat_media").isChecked,
                        restoreSettingsGeneralBasic = moduleViews.getValue("settings_general_basic").isChecked,
                        restoreSettingsGeneralAssets = moduleViews.getValue("settings_general_assets").isChecked,
                        restoreSettingsGeneralCloud = moduleViews.getValue("settings_general_cloud").isChecked,
                        restoreSettingsDisplayEntries = moduleViews.getValue("settings_display_entries").isChecked,
                        restoreSettingsDisplayBills = moduleViews.getValue("settings_display_bills").isChecked,
                        restoreSettingsDisplayMultiBill = moduleViews.getValue("settings_display_multibill").isChecked,
                        restoreSettingsAiCore = moduleViews.getValue("settings_ai_core").isChecked,
                        restoreSettingsAiChat = moduleViews.getValue("settings_ai_chat").isChecked,
                        restoreSettingsBooks = moduleViews.getValue("settings_books").isChecked,
                        restoreSettingsAdvancedRuntime = moduleViews.getValue("settings_advanced_runtime").isChecked,
                        restoreSettingsGeneralLegacy = moduleViews.getValue("settings_general").isChecked,
                        restoreSettingsDisplayLegacy = moduleViews.getValue("settings_display").isChecked,
                        restoreSettingsAdvancedLegacy = moduleViews.getValue("settings_advanced").isChecked,
                        restoreBanners = moduleViews.getValue("banners").isChecked
                    )

                    val restoresRelationalData = options.restoreAssets || options.restoreCategories ||
                        options.restoreBills || options.restoreBudgets ||
                        options.restoreRecurringPatterns || options.restoreChatMessages
                    if (!isMerge && restoresRelationalData) {
                        val requiredRoots = listOf(
                            BackupModuleId.ASSETS,
                            BackupModuleId.CATEGORIES,
                            BackupModuleId.BILLS
                        )
                        if (requiredRoots.any { it !in dataMap }) {
                            tempFile.delete()
                            Utils.toast(
                                this@BackupActivity,
                                getString(R.string.restore_overwrite_requires_complete_data)
                            )
                            return@setPositiveButton
                        }
                        // Room regenerates IDs for these roots. Restore every ID consumer in the
                        // same transaction; absent modules from older archives become empty.
                        options = options.copy(
                            restoreAssets = true,
                            restoreCategories = true,
                            restoreBills = true,
                            restoreBudgets = true,
                            restoreRecurringPatterns = true,
                            restoreChatMessages = true
                        )
                    }

                    val execute: (SharedRestoreMode) -> Unit = { sharedMode ->
                        val action: (String?) -> Unit = { legacyPin ->
                            if (isMerge) {
                                mergeRestoreData(
                                    dataMap, options, tempFile, legacyPin, sharedMode
                                )
                            } else {
                                restoreData(
                                    dataMap, options, tempFile, legacyPin, sharedMode
                                )
                            }
                        }
                        // PIN is retained only for importing old V1 archives that encrypted AI keys.
                        if (options.restoreSettingsAiCore && settingsNeedsPin) {
                            promptPinForRestore(onCancelled = { tempFile.delete() }, onPinConfirmed = action)
                        }
                        else action(null)
                    }
                    val sharedReadiness = if (options.restoresDatabaseModules()) {
                        inspectSharedRecoveryReadiness(dataMap)
                    } else {
                        SharedRecoveryReadiness.NOT_PRESENT
                    }
                    when {
                        allowSharedReconnect && sharedReadiness == SharedRecoveryReadiness.READY -> {
                            promptSharedRestoreMode(
                                onCancelled = { tempFile.delete() },
                                onSelected = execute
                            )
                        }
                        allowSharedReconnect && sharedReadiness == SharedRecoveryReadiness.INCOMPLETE -> {
                            promptIncompleteSharedRestore(
                                onCancelled = { tempFile.delete() },
                                onContinueAsLocalCopy = { execute(SharedRestoreMode.LOCAL_COPY) }
                            )
                        }
                        else -> execute(SharedRestoreMode.LOCAL_COPY)
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> tempFile.delete() }
                .create()
            dialog.setOnCancelListener { tempFile.delete() }
            OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, widthRatio = 0.92f, cancelOnTouchOutside = true, useSolidPanelBackground = true)
        }
    }

    private fun restoreData(
        dataMap: Map<String, String>,
        options: RestoreOptions,
        tempFile: File?,
        settingsPin: String?,
        sharedRestoreMode: SharedRestoreMode
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            var restoreLockAcquired = false
            var preferencesTransaction: RestorePreferencesTransaction? = null
            var mediaTransaction: RestoreMediaTransaction? = null
            var roomCommitted = false
            try {
                RESTORE_MUTEX.lock()
                restoreLockAcquired = true
                val restoreDatabase = options.restoresDatabaseModules()
                val investmentDrafts = if (options.restoreAssets) {
                    dataMap[BackupModuleId.INVESTMENT_DRAFTS]
                        ?.let(InvestmentDraftBackupSupport::decode)
                } else {
                    null
                }
                val effectiveSharedMode = if (restoreDatabase) sharedRestoreMode else SharedRestoreMode.LOCAL_COPY
                val parsedSharedData = if (restoreDatabase) parseSharedRestoreData(dataMap) else null
                val sharedSecrets = if (effectiveSharedMode == SharedRestoreMode.RECONNECT) {
                    val ledgers = requireNotNull(parsedSharedData) { "备份缺少共享账本数据" }.ledgers
                    val payload = requireNotNull(dataMap[BackupModuleId.SHARED_SECRETS]) {
                        "备份缺少共享账本恢复凭据，请改选恢复为本地副本"
                    }
                    SharedRecoverySecrets.decode(payload, ledgers.mapTo(hashSetOf(), SharedLedgerBackup::uuid))
                } else {
                    emptyList()
                }
                val sharedRestoreData = if (effectiveSharedMode == SharedRestoreMode.RECONNECT) {
                    SharedReconnectPreflight.validate(
                        this@BackupActivity,
                        AppDatabase.getDatabase(this@BackupActivity),
                        requireNotNull(parsedSharedData),
                        sharedSecrets
                    )
                } else {
                    parsedSharedData
                }
                val newDeviceId = if (effectiveSharedMode == SharedRestoreMode.RECONNECT) {
                    UUID.randomUUID().toString()
                } else {
                    null
                }
                val settingsRoots = parseSelectedSettings(dataMap, options, settingsPin)
                mediaTransaction = stageSelectedRestoreMedia(tempFile, options)
                val preferenceTx = RestorePreferencesTransaction(this@BackupActivity)
                preferencesTransaction = preferenceTx
                var restoredSharedSecretCount = 0

                withContext(NonCancellable) {
                    backupRepository.restoreFullData(
                        assets = if (options.restoreAssets) dataMap["assets"]?.let { DataExportManager.deserializeAssets(it) } else null,
                        bills = if (options.restoreBills) dataMap["bills"]?.let { DataExportManager.deserializeBills(it) } else null,
                        deletedBills = if (options.restoreBills) dataMap["deleted_bills"]
                            ?.let { DataExportManager.deserializeDeletedBills(it) } ?: emptyList() else null,
                        investmentLots = if (options.restoreBills && options.restoreAssets) dataMap["investment_lots"]
                            ?.let { DataExportManager.deserializeInvestmentLots(it) } ?: emptyList() else null,
                        categories = if (options.restoreCategories) dataMap["categories"]?.let { DataExportManager.deserializeCategories(it) } else null,
                        rules = if (options.restoreRules) dataMap["rules"]?.let { DataExportManager.deserializeAiRules(it) } else null,
                        chatMessages = if (options.restoreChatMessages) dataMap["chat_messages"]
                            ?.let { DataExportManager.deserializeChatMessages(it) } ?: emptyList() else null,
                        budgets = if (options.restoreBudgets) dataMap["budgets"]
                            ?.let { DataExportManager.deserializeBudgets(it) } ?: emptyList() else null,
                        recurringPatterns = if (options.restoreRecurringPatterns) dataMap["recurring_patterns"]
                            ?.let { DataExportManager.deserializeRecurringPatterns(it) } ?: emptyList() else null,
                        books = if (restoreDatabase) dataMap[BackupModuleId.BOOKS]?.let(DataExportManager::deserializeBooks) else null,
                        sharedRestoreData = sharedRestoreData,
                        sharedRestoreMode = effectiveSharedMode,
                        newDeviceId = newDeviceId,
                        beforeCommit = {
                            restoredSharedSecretCount = applyRestoreSideEffectsBeforeCommit(
                                options = options,
                                investmentDrafts = investmentDrafts,
                                replaceInvestmentDrafts = true,
                                effectiveSharedMode = effectiveSharedMode,
                                sharedSecrets = sharedSecrets,
                                settingsRoots = settingsRoots,
                                newDeviceId = newDeviceId,
                                mediaTransaction = mediaTransaction
                            )
                        }
                    )
                    roomCommitted = true
                    preferenceTx.commit()
                    mediaTransaction?.let { media ->
                        runCatching(media::commit).onFailure {
                            Log.w("BackupActivity", "恢复已提交，但媒体事务临时文件清理失败", it)
                        }
                    }
                }

                runCatching { syncRestoredRuntimeState(options) }.onFailure {
                    Log.w("BackupActivity", "恢复后运行状态刷新失败", it)
                }
                if (effectiveSharedMode == SharedRestoreMode.RECONNECT && restoredSharedSecretCount > 0) {
                    runCatching { SharedSyncScheduler.enqueueFullNow(this@BackupActivity) }.onFailure {
                        Log.w("BackupActivity", "共享账本恢复成功，但立即同步调度失败", it)
                    }
                }
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.restore_success))
                    if (isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                if (!roomCommitted) {
                    rollbackRestoreSideEffects(preferencesTransaction, mediaTransaction, e)
                }
                Log.e("BackupActivity", "恢复数据失败", e)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, rootCauseMessage(e))
                }
            } finally {
                runCatching { mediaTransaction?.close() }.onFailure {
                    Log.e("BackupActivity", "清理恢复媒体事务失败", it)
                }
                tempFile?.delete()
                if (restoreLockAcquired) RESTORE_MUTEX.unlock()
            }
        }
    }

    private fun mergeRestoreData(
        dataMap: Map<String, String>,
        options: RestoreOptions,
        tempFile: File?,
        settingsPin: String?,
        sharedRestoreMode: SharedRestoreMode
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            var restoreLockAcquired = false
            var preferencesTransaction: RestorePreferencesTransaction? = null
            var mediaTransaction: RestoreMediaTransaction? = null
            var roomCommitted = false
            try {
                RESTORE_MUTEX.lock()
                restoreLockAcquired = true
                val restoreDatabase = options.restoresDatabaseModules()
                val investmentDrafts = if (options.restoreAssets) {
                    dataMap[BackupModuleId.INVESTMENT_DRAFTS]
                        ?.let(InvestmentDraftBackupSupport::decode)
                } else {
                    null
                }
                val effectiveSharedMode = if (restoreDatabase) sharedRestoreMode else SharedRestoreMode.LOCAL_COPY
                val parsedSharedData = if (restoreDatabase) parseSharedRestoreData(dataMap) else null
                val sharedSecrets = if (effectiveSharedMode == SharedRestoreMode.RECONNECT) {
                    val ledgers = requireNotNull(parsedSharedData) { "备份缺少共享账本数据" }.ledgers
                    val payload = requireNotNull(dataMap[BackupModuleId.SHARED_SECRETS]) {
                        "备份缺少共享账本恢复凭据，请改选恢复为本地副本"
                    }
                    SharedRecoverySecrets.decode(payload, ledgers.mapTo(hashSetOf(), SharedLedgerBackup::uuid))
                } else {
                    emptyList()
                }
                val sharedRestoreData = if (effectiveSharedMode == SharedRestoreMode.RECONNECT) {
                    SharedReconnectPreflight.validate(
                        this@BackupActivity,
                        AppDatabase.getDatabase(this@BackupActivity),
                        requireNotNull(parsedSharedData),
                        sharedSecrets
                    )
                } else {
                    parsedSharedData
                }
                val newDeviceId = if (effectiveSharedMode == SharedRestoreMode.RECONNECT) {
                    UUID.randomUUID().toString()
                } else {
                    null
                }
                val settingsRoots = parseSelectedSettings(dataMap, options, settingsPin)
                mediaTransaction = stageSelectedRestoreMedia(tempFile, options)
                val preferenceTx = RestorePreferencesTransaction(this@BackupActivity)
                preferencesTransaction = preferenceTx
                var restoredSharedSecretCount = 0

                val result = withContext(NonCancellable) {
                    val merged = backupRepository.mergeRestoreFullData(
                        assets = if (options.restoreAssets) dataMap["assets"]?.let { DataExportManager.deserializeAssets(it) } else null,
                        bills = if (options.restoreBills) dataMap["bills"]?.let { DataExportManager.deserializeBills(it) } else null,
                        deletedBills = if (options.restoreBills) dataMap["deleted_bills"]?.let { DataExportManager.deserializeDeletedBills(it) } else null,
                        investmentLots = if (options.restoreBills && options.restoreAssets) dataMap["investment_lots"]?.let { DataExportManager.deserializeInvestmentLots(it) } else null,
                        categories = if (options.restoreCategories) dataMap["categories"]?.let { DataExportManager.deserializeCategories(it) } else null,
                        rules = if (options.restoreRules) dataMap["rules"]?.let { DataExportManager.deserializeAiRules(it) } else null,
                        chatMessages = if (options.restoreChatMessages) dataMap["chat_messages"]?.let { DataExportManager.deserializeChatMessages(it) } else null,
                        budgets = if (options.restoreBudgets) dataMap["budgets"]?.let { DataExportManager.deserializeBudgets(it) } else null,
                        recurringPatterns = if (options.restoreRecurringPatterns) dataMap["recurring_patterns"]?.let { DataExportManager.deserializeRecurringPatterns(it) } else null,
                        books = if (restoreDatabase) dataMap[BackupModuleId.BOOKS]?.let(DataExportManager::deserializeBooks) else null,
                        sharedRestoreData = sharedRestoreData,
                        sharedRestoreMode = effectiveSharedMode,
                        newDeviceId = newDeviceId,
                        beforeCommit = {
                            restoredSharedSecretCount = applyRestoreSideEffectsBeforeCommit(
                                options = options,
                                investmentDrafts = investmentDrafts,
                                replaceInvestmentDrafts = false,
                                effectiveSharedMode = effectiveSharedMode,
                                sharedSecrets = sharedSecrets,
                                settingsRoots = settingsRoots,
                                newDeviceId = newDeviceId,
                                mediaTransaction = mediaTransaction
                            )
                        }
                    )
                    roomCommitted = true
                    preferenceTx.commit()
                    mediaTransaction?.let { media ->
                        runCatching(media::commit).onFailure {
                            Log.w("BackupActivity", "合并恢复已提交，但媒体事务临时文件清理失败", it)
                        }
                    }
                    merged
                }

                runCatching { syncRestoredRuntimeState(options) }.onFailure {
                    Log.w("BackupActivity", "合并恢复后运行状态刷新失败", it)
                }
                if (effectiveSharedMode == SharedRestoreMode.RECONNECT && restoredSharedSecretCount > 0) {
                    runCatching { SharedSyncScheduler.enqueueFullNow(this@BackupActivity) }.onFailure {
                        Log.w("BackupActivity", "共享账本合并恢复成功，但立即同步调度失败", it)
                    }
                }
                withContext(Dispatchers.Main) {
                    val msg = buildString {
                        append(getString(R.string.merge_restore_complete))
                        if (result.insertedBills > 0 || result.skippedBills > 0) {
                            append("\n${getString(R.string.merge_restore_bill_fmt, result.insertedBills, result.skippedBills)}")
                        }
                        if (result.insertedAssets > 0 || result.skippedAssets > 0) {
                            append("\n${getString(R.string.merge_restore_asset_fmt, result.insertedAssets, result.skippedAssets)}")
                        }
                        if (result.insertedCategories > 0 || result.skippedCategories > 0) {
                            append("\n${getString(R.string.merge_restore_category_fmt, result.insertedCategories, result.skippedCategories)}")
                        }
                    }
                    Utils.toast(this@BackupActivity, msg)
                    if (isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                if (!roomCommitted) {
                    rollbackRestoreSideEffects(preferencesTransaction, mediaTransaction, e)
                }
                Log.e("BackupActivity", "合并恢复失败", e)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, rootCauseMessage(e))
                }
            } finally {
                runCatching { mediaTransaction?.close() }.onFailure {
                    Log.e("BackupActivity", "清理合并恢复媒体事务失败", it)
                }
                tempFile?.delete()
                if (restoreLockAcquired) RESTORE_MUTEX.unlock()
            }
        }
    }

    private fun stageSelectedRestoreMedia(
        tempFile: File?,
        options: RestoreOptions
    ): RestoreMediaTransaction? {
        if (!options.restoreBanners && !options.restoreChatMedia) return null
        val source = requireNotNull(tempFile) { "恢复媒体暂存文件不存在，请重新选择备份" }
        require(source.isFile) { "恢复媒体暂存文件已失效，请重新选择备份" }
        return RestoreMediaTransaction.stageValidatedZip(
            validatedZip = source,
            filesDir = filesDir,
            selection = RestoreMediaSelection(
                banners = options.restoreBanners,
                chatMedia = options.restoreChatMedia
            )
        )
    }

    /** Runs inside the Room transaction so a failure can roll database rows back as one unit. */
    private suspend fun applyRestoreSideEffectsBeforeCommit(
        options: RestoreOptions,
        investmentDrafts: List<InvestmentDraftRecordBackup>?,
        replaceInvestmentDrafts: Boolean,
        effectiveSharedMode: SharedRestoreMode,
        sharedSecrets: List<SharedRecoverySecret>,
        settingsRoots: List<JSONObject>,
        newDeviceId: String?,
        mediaTransaction: RestoreMediaTransaction?
    ): Int {
        if (newDeviceId != null) DeviceIdManager.replaceDeviceId(this, newDeviceId)
        if (options.restoreAssets) {
            if (investmentDrafts == null && replaceInvestmentDrafts) {
                com.taostudio.tapaccounting.logic.InvestmentLotDraftStorage.clearAll(this)
            } else if (investmentDrafts != null) {
                InvestmentDraftBackupSupport.restore(
                    context = this,
                    records = investmentDrafts,
                    currentAssets = AppDatabase.getDatabase(this).assetDao().getAllAssetsList(),
                    replaceAll = replaceInvestmentDrafts
                )
            }
        }
        val restoredSharedSecretCount = if (effectiveSharedMode == SharedRestoreMode.RECONNECT) {
            SharedRecoverySecrets.restore(this, sharedSecrets)
        } else {
            0
        }
        settingsRoots.forEach { Prefs.importAll(this, it) }

        mediaTransaction?.publish()
        if (options.restoreBanners) fixRestoredBannerPaths(File(filesDir, "banners"))
        if (options.restoreChatMedia) {
            fixRestoredChatPreferencePaths()
            if (options.restoreChatMessages) fixRestoredVoiceMessagePaths()
        }
        return restoredSharedSecretCount
    }

    private fun rollbackRestoreSideEffects(
        preferences: RestorePreferencesTransaction?,
        media: RestoreMediaTransaction?,
        originalFailure: Throwable
    ) {
        runCatching { media?.rollback() }.onFailure { rollbackFailure ->
            originalFailure.addSuppressed(rollbackFailure)
            Log.e("BackupActivity", "恢复媒体回滚失败", rollbackFailure)
        }
        runCatching { preferences?.rollback() }.onFailure { rollbackFailure ->
            originalFailure.addSuppressed(rollbackFailure)
            Log.e("BackupActivity", "恢复设置回滚失败", rollbackFailure)
        }
    }

    private suspend fun fixRestoredVoiceMessagePaths() {
        val dao = AppDatabase.getDatabase(this).chatMessageDao()
        val voiceDir = File(filesDir, "chat_voice")
        val imageDirs = listOf(File(filesDir, "chat_images"), File(filesDir, "chat_pics"))
        val attachmentDir = File(filesDir, "chat_attachments")
        val messages = dao.getAll()
        messages.forEach { msg ->
            var updated = msg
            if (msg.msgType == ChatActivity.MSG_TYPE_USER_VOICE && msg.content.isNotBlank()) {
                runCatching {
                    val obj = JSONObject(msg.content)
                    val oldPath = obj.optString("audioPath")
                    val oldFile = fileFromStoredUri(oldPath)
                    if (oldPath.isNotBlank() && (oldFile == null || !oldFile.exists())) {
                        val restored = File(voiceDir, oldFile?.name ?: File(oldPath).name)
                        if (restored.exists()) {
                            obj.put("audioPath", restored.absolutePath)
                            updated = updated.copy(content = obj.toString())
                        }
                    }
                }
            }
            if (msg.imageUri.isNotBlank() &&
                msg.msgType in setOf(ChatActivity.MSG_TYPE_USER_IMAGE, ChatActivity.MSG_TYPE_USER_FILE)
            ) {
                val oldFile = fileFromStoredUri(msg.imageUri)
                if (oldFile == null || !oldFile.exists()) {
                    val fileName = oldFile?.name ?: File(msg.imageUri).name
                    val restored = if (msg.msgType == ChatActivity.MSG_TYPE_USER_FILE) {
                        File(attachmentDir, fileName).takeIf(File::exists)
                    } else {
                        imageDirs.asSequence().map { File(it, fileName) }.firstOrNull(File::exists)
                    }
                    if (restored != null) updated = updated.copy(imageUri = Uri.fromFile(restored).toString())
                }
            }
            if (updated != msg) dao.update(updated)
        }
    }

    private fun fileFromStoredUri(value: String): File? = runCatching {
        val parsed = Uri.parse(value)
        when (parsed.scheme?.lowercase(Locale.US)) {
            null, "" -> File(value)
            "file" -> parsed.path?.let(::File)
            else -> null
        }
    }.getOrNull()

    private fun fixRestoredChatPreferencePaths() {
        val aiAvatar = listOf(
            File(filesDir, File(Prefs.getAiChatAvatarPath(this)).name),
            File(filesDir, "chat_ai_avatar.jpg"),
            File(filesDir, "chat_ai_avatar.png")
        ).firstOrNull(File::isFile)
        aiAvatar?.let { Prefs.setAiChatAvatarPath(this, it.absolutePath) }
        val userAvatar = listOf(
            File(filesDir, File(Prefs.getUserChatAvatarPath(this)).name),
            File(filesDir, "chat_user_avatar.jpg"),
            File(filesDir, "chat_user_avatar.png")
        ).firstOrNull(File::isFile)
        userAvatar?.let { Prefs.setUserChatAvatarPath(this, it.absolutePath) }
        val bgPath = Prefs.getAiChatBgPath(this)
        if (bgPath.isNotBlank()) {
            val bgFile = File(bgPath)
            val restored = listOf(
                File(File(filesDir, "chat_bg"), bgFile.name),
                File(filesDir, bgFile.name)
            ).firstOrNull(File::isFile)
            if (!bgFile.exists() && restored != null) {
                Prefs.setAiChatBgPath(this, restored.absolutePath)
            }
        }
    }

    private fun fixRestoredBannerPaths(bannerDir: File) {
        val books = BookAccountManager.getBookAccounts(this)
        books.forEach { book ->
            val currentPath = BookAccountManager.getBookBannerPath(this, book)
            if (!currentPath.isNullOrEmpty()) {
                val currentFile = File(currentPath)
                val restoredFile = File(bannerDir, currentFile.name)
                if (!currentFile.exists() && restoredFile.exists()) {
                    BookAccountManager.setBookBannerPath(this, book, restoredFile.absolutePath)
                }
            }
        }
    }

    private fun syncRestoredRuntimeState(options: RestoreOptions) {
        val touchedGeneral = options.restoreSettingsGeneralBasic ||
            options.restoreSettingsGeneralAssets ||
            options.restoreSettingsGeneralCloud ||
            options.restoreSettingsGeneralLegacy
        val touchedAdvanced = options.restoreSettingsAdvancedRuntime ||
            options.restoreSettingsAdvancedLegacy
        if (!touchedGeneral && !touchedAdvanced) return

        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = if (Prefs.isDoubleTapEnabled(this@BackupActivity)) {
                OverlayService.ACTION_START_DOUBLE_TAP
            } else {
                OverlayService.ACTION_STOP_DOUBLE_TAP
            }
        }

        OverlayService.startCompat(this, serviceIntent)
    }

    private fun rootCauseMessage(e: Throwable): String {
        var cur: Throwable = e
        while (cur.cause != null) cur = cur.cause!!
        return cur.message?.takeIf { it.isNotBlank() } ?: e.message?.takeIf { it.isNotBlank() } ?: cur::class.java.simpleName
    }

    private fun parseSettingsRoot(payload: String): JSONObject {
        val parsed = JSONTokener(payload).nextValue()
        return when (parsed) {
            is JSONObject -> parsed
            is String -> JSONObject(parsed)
            else -> throw org.json.JSONException("Unsupported settings payload type: ${parsed?.javaClass?.name}")
        }
    }

    private fun parseSelectedSettings(
        dataMap: Map<String, String>,
        options: RestoreOptions,
        settingsPin: String?
    ): List<JSONObject> {
        val selectedModules = listOf(
            options.restoreSettingsGeneralBasic to "settings_general_basic",
            options.restoreSettingsGeneralAssets to "settings_general_assets",
            options.restoreSettingsGeneralCloud to "settings_general_cloud",
            options.restoreSettingsDisplayEntries to "settings_display_entries",
            options.restoreSettingsDisplayBills to "settings_display_bills",
            options.restoreSettingsDisplayMultiBill to "settings_display_multibill",
            options.restoreSettingsAiChat to "settings_ai_chat",
            options.restoreSettingsBooks to "settings_books",
            options.restoreSettingsAdvancedRuntime to "settings_advanced_runtime",
            options.restoreSettingsGeneralLegacy to "settings_general",
            options.restoreSettingsDisplayLegacy to "settings_display",
            options.restoreSettingsAdvancedLegacy to "settings_advanced"
        )
        val roots = selectedModules.mapNotNull { (enabled, key) ->
            if (enabled) dataMap[key]?.let(::parseSettingsRoot) else null
        }.toMutableList()
        if (options.restoreSettingsAiCore) {
            dataMap["settings_ai_core"]?.let { payload ->
                var root = parseSettingsRoot(payload)
                if (BackupPinCrypto.hasEncryptedApi(root)) {
                    val pin = settingsPin
                        ?: throw IllegalArgumentException("该旧备份中的 API Key 受 PIN 保护，请输入备份 PIN")
                    root = BackupPinCrypto.decryptApiKeyInSettings(root, pin)
                }
                roots += root
            }
        }
        return roots
    }

    private fun parseSharedRestoreData(dataMap: Map<String, String>): SharedRestoreData? {
        val ledgersJson = dataMap[BackupModuleId.SHARED_LEDGERS] ?: return null
        return SharedRestoreData(
            ledgers = DataExportManager.deserializeSharedLedgers(ledgersJson),
            members = dataMap[BackupModuleId.SHARED_MEMBERS]
                ?.let(DataExportManager::deserializeSharedMembers)
                ?: emptyList(),
            pendingQueue = dataMap[BackupModuleId.SYNC_QUEUE]
                ?.let(DataExportManager::deserializePendingSyncQueue)
                ?: emptyList(),
            pendingOperations = dataMap[BackupModuleId.SYNC_OPERATIONS]
                ?.let(DataExportManager::deserializePendingSyncOperations)
                ?: emptyList()
        )
    }

    private fun inspectSharedRecoveryReadiness(
        dataMap: Map<String, String>
    ): SharedRecoveryReadiness = runCatching {
        val data = parseSharedRestoreData(dataMap)
            ?: return@runCatching SharedRecoveryReadiness.NOT_PRESENT
        if (data.ledgers.isEmpty()) return@runCatching SharedRecoveryReadiness.NOT_PRESENT
        val credentialLedgerUuids = dataMap[BackupModuleId.SHARED_SECRETS]
            ?.let { payload ->
                SharedRecoverySecrets.decode(
                    payload,
                    data.ledgers.mapTo(hashSetOf(), SharedLedgerBackup::uuid)
                ).mapTo(hashSetOf(), SharedRecoverySecret::ledgerUuid)
            }
            ?: emptySet()
        assessSharedRecoveryReadiness(data, credentialLedgerUuids)
    }.onFailure { error ->
        Log.w("BackupActivity", "共享身份恢复资料不完整", error)
    }.getOrDefault(SharedRecoveryReadiness.INCOMPLETE)

    private fun RestoreOptions.restoresDatabaseModules(): Boolean =
        restoreAssets || restoreCategories || restoreBills || restoreRules || restoreBudgets ||
            restoreRecurringPatterns || restoreChatMessages

    private fun visibleIfAny(vararg views: View): Int =
        if (views.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE

    private fun promptPinForRestore(
        onCancelled: () -> Unit = {},
        attemptsRemaining: Int = MAX_AUTH_ATTEMPTS,
        onPinConfirmed: (String) -> Unit
    ) {
        val etPin = EditText(this).apply {
            hint = getString(R.string.input_backup_pin)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(BackupPinCrypto.MAX_PIN_DIGITS))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.input_backup_pin)
            .setMessage(getString(R.string.backup_pin_verify_prompt))
            .setView(etPin)
            .setPositiveButton(R.string.continue_restore) { _, _ ->
                val pin = etPin.text?.toString().orEmpty().trim()
                if (!pin.matches(Regex("^\\d{${BackupPinCrypto.LEGACY_MIN_PIN_DIGITS},${BackupPinCrypto.MAX_PIN_DIGITS}}$"))) {
                    Utils.toast(this, getString(R.string.pin_must_be_digits))
                } else {
                    onPinConfirmed(pin)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled() }
            .create()
        dialog.setOnCancelListener { onCancelled() }
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, widthRatio = 0.9f, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun performCsvExport(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bills = AppDatabase.getDatabase(this@BackupActivity).billDao().getAllBillsList()
                contentResolver.openOutputStream(uri)?.use { CsvManager.export(bills, it) }
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.exported_bills_fmt, bills.size))
                    if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_CSV && isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.export_failed)) }
            }
        }
    }

    private fun performCsvImport(uri: Uri) {
        val books = BookAccountManager.getBookAccounts(this)
        if (books.isEmpty()) {
            Utils.toast(this, getString(R.string.no_available_book))
            return
        }
        val selectedBook = BookAccountManager.getSelectedBook(this, books)
        var selectedIndex = books.indexOf(selectedBook).coerceAtLeast(0)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.select_import_book)
            .setSingleChoiceItems(books.toTypedArray(), selectedIndex) { _, which -> selectedIndex = which }
            .setMessage(R.string.csv_import_hint)
            .setPositiveButton(R.string.continue_btn) { _, _ ->
                val targetBook = books.getOrNull(selectedIndex) ?: BookAccountManager.getDefaultBook(this)
                performCsvImportInternal(uri, targetBook)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun performCsvImportInternal(uri: Uri, targetBook: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bills = contentResolver.openInputStream(uri)?.use { CsvManager.import(it, fallbackBookName = targetBook) } ?: emptyList()
                if (bills.isEmpty()) {
                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.parse_failed)) }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    val dialog = AlertDialog.Builder(this@BackupActivity)
                        .setTitle(R.string.confirm_import_title)
                        .setMessage(getString(R.string.confirm_import_message, bills.size, targetBook))
                        .setPositiveButton(R.string.import_btn) { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val db = AppDatabase.getDatabase(this@BackupActivity)
                                    val importResult = importCsvBills(db, bills)
                                    withContext(Dispatchers.Main) {
                                        val assetHint = if (importResult.createdAssetNames.isNotEmpty()) {
                                            getString(R.string.import_asset_hint_fmt, importResult.createdAssetNames.size)
                                        } else {
                                            ""
                                        }
                                        Utils.toast(this@BackupActivity, getString(R.string.import_success_fmt, importResult.billCount, assetHint))

                                        // P0-3: CSV 导入后若有临时资产，跳转审查页
                                        if (importResult.createdAssetNames.isNotEmpty()) {
                                            startActivity(Intent(this@BackupActivity, com.taostudio.tapaccounting.ui.import.ImportReviewActivity::class.java))
                                        }

                                        if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_CSV && isQuickOneShot()) finish()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.import_failed)) }
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .create()
                    OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.csv_parse_failed)) }
            }
        }
    }

    private suspend fun importCsvBills(db: AppDatabase, bills: List<Bill>): CsvImportResult {
        val importedIdMap = mutableMapOf<Long, Long>()
        val pendingRelations = mutableListOf<Pair<Long, Long>>()
        val importedIds = bills.mapNotNull { it.id.takeIf { id -> id > 0L } }.toSet()
        val assetResolution = ensureCsvImportAssets(db, bills)
        val assetByName = assetResolution.assetByName

        for (bill in bills) {
            val accountAsset = assetByName[normalizeAssetImportName(bill.accountName)]
            val toAccountAsset = assetByName[normalizeAssetImportName(bill.toAccountName)]
            val newId = db.billDao().insertBill(
                bill.copy(
                    id = 0L,
                    accountId = accountAsset?.id,
                    toAccountId = toAccountAsset?.id,
                    categoryName = CategoryNameNormalizer.normalizeForStorage(bill.categoryName),
                    relatedBillId = null
                )
            )
            if (bill.id > 0L) {
                importedIdMap[bill.id] = newId
            }
            val oldRelatedId = bill.relatedBillId
            if (oldRelatedId != null && oldRelatedId in importedIds) {
                pendingRelations += newId to oldRelatedId
            }
        }

        pendingRelations.forEach { (newBillId, oldRelatedId) ->
            val mappedRelatedId = importedIdMap[oldRelatedId] ?: return@forEach
            val savedBill = db.billDao().getBillById(newBillId) ?: return@forEach
            db.billDao().updateBill(savedBill.copy(relatedBillId = mappedRelatedId))
        }

        db.billDao().backfillAssetLinksByName()
        return CsvImportResult(
            billCount = bills.size,
            createdAssetNames = assetResolution.createdAssetNames
        )
    }

    private fun repairMissingCsvAssetBindings() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@BackupActivity)
                val result = repairMissingAssetBindings(db)
                withContext(Dispatchers.Main) {
                    val message = if (result.updatedBillCount == 0 && result.createdAssetNames.isEmpty()) {
                        getString(R.string.fix_binding_none)
                    } else {
                        getString(R.string.fix_binding_result_fmt, result.updatedBillCount) +
                            if (result.createdAssetNames.isNotEmpty()) {
                                getString(R.string.fix_binding_created_assets_fmt, result.createdAssetNames.size)
                            } else {
                                ""
                            }
                    }
                    Utils.toast(this@BackupActivity, message)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.fix_failed))
                }
            }
        }
    }

    private suspend fun repairMissingAssetBindings(db: AppDatabase): CsvAssetRepairResult {
        val bills = db.billDao().getAllBillsList().filter { bill ->
            (normalizeAssetImportName(bill.accountName).isNotBlank() && bill.accountId == null) ||
                (normalizeAssetImportName(bill.toAccountName).isNotBlank() && bill.toAccountId == null)
        }
        if (bills.isEmpty()) return CsvAssetRepairResult()

        val assetResolution = ensureCsvImportAssets(db, bills)
        var updatedCount = 0
        bills.forEach { bill ->
            val accountAsset = assetResolution.assetByName[normalizeAssetImportName(bill.accountName)]
            val toAccountAsset = assetResolution.assetByName[normalizeAssetImportName(bill.toAccountName)]
            val updated = bill.copy(
                accountId = bill.accountId ?: accountAsset?.id,
                toAccountId = bill.toAccountId ?: toAccountAsset?.id
            )
            if (updated.accountId != bill.accountId || updated.toAccountId != bill.toAccountId) {
                db.billDao().updateBill(updated)
                updatedCount += 1
            }
        }

        return CsvAssetRepairResult(
            updatedBillCount = updatedCount,
            createdAssetNames = assetResolution.createdAssetNames
        )
    }

    private suspend fun ensureCsvImportAssets(db: AppDatabase, bills: List<Bill>): CsvImportAssetResolution {
        val assetDao = db.assetDao()
        val existing = assetDao.getAllAssetsList().associateBy { normalizeAssetImportName(it.name) }.toMutableMap()
        val createdAssetNames = mutableListOf<String>()
        val required = linkedMapOf<String, String>()
        bills.forEach { bill ->
            listOf(bill.accountName to bill.currency, bill.toAccountName to bill.currency).forEach { (rawName, currency) ->
                val name = normalizeAssetImportName(rawName)
                if (name.isBlank() || existing.containsKey(name) || required.containsKey(name)) return@forEach
                required[name] = currency.ifBlank { "CNY" }
            }
        }

        var nextSortOrder = (assetDao.getMaxSortOrderInCategory(Asset.CATEGORY_FUND) ?: 0) + 10
        required.forEach { (name, currency) ->
            val category = inferImportedAssetCategory(name)
            val sortOrder = if (category == Asset.CATEGORY_FUND) {
                nextSortOrder.also { nextSortOrder += 10 }
            } else {
                (assetDao.getMaxSortOrderInCategory(category) ?: 0) + 10
            }
            val asset = Asset(
                name = name,
                type = "CSV导入待确认",
                balance = 0.0,
                initialBalance = 0.0,
                currency = currency,
                remark = CSV_TEMP_ASSET_MARKER,
                includeInNetAsset = false,
                sortOrder = sortOrder,
                pickerSortOrder = sortOrder,
                assetCategory = category
            )
            val newId = assetDao.insertAsset(asset)
            existing[name] = asset.copy(id = newId)
            createdAssetNames += name
        }

        return CsvImportAssetResolution(existing, createdAssetNames)
    }

    private fun normalizeAssetImportName(raw: String): String {
        val name = raw.trim()
        return when {
            name.isBlank() -> ""
            name == "选择资产" || name == "未知账户" -> ""
            else -> name
        }
    }

    private fun inferImportedAssetCategory(name: String): String {
        return if (
            name.contains("信用卡") ||
            name.contains("花呗") ||
            name.contains("白条") ||
            name.contains("美团月付")
        ) {
            Asset.CATEGORY_CREDIT_CARD
        } else {
            Asset.CATEGORY_FUND
        }
    }
}

private const val CSV_TEMP_ASSET_MARKER = "CSV导入自动创建，请检查资产类型、币种和余额"

private data class CsvImportResult(
    val billCount: Int,
    val createdAssetNames: List<String>
)

private data class CsvImportAssetResolution(
    val assetByName: Map<String, Asset>,
    val createdAssetNames: List<String>
)

private data class CsvAssetRepairResult(
    val updatedBillCount: Int = 0,
    val createdAssetNames: List<String> = emptyList()
)

data class BackupOptions(
    val backupAssets: Boolean,
    val backupCategories: Boolean,
    val backupBills: Boolean,
    val backupRules: Boolean,
    val backupChatMessages: Boolean,
    val backupChatMedia: Boolean,
    val backupSettingsGeneralBasic: Boolean,
    val backupSettingsGeneralAssets: Boolean,
    val backupSettingsGeneralCloud: Boolean,
    val backupSettingsDisplayEntries: Boolean,
    val backupSettingsDisplayBills: Boolean,
    val backupSettingsDisplayMultiBill: Boolean,
    val backupSettingsAiCore: Boolean,
    val backupSettingsAiChat: Boolean,
    val backupSettingsBooks: Boolean,
    val backupSettingsAdvancedRuntime: Boolean,
    val backupBanners: Boolean
)

data class RestoreOptions(
    val restoreAssets: Boolean,
    val restoreCategories: Boolean,
    val restoreBills: Boolean,
    val restoreRules: Boolean,
    val restoreBudgets: Boolean,
    val restoreRecurringPatterns: Boolean,
    val restoreChatMessages: Boolean,
    val restoreChatMedia: Boolean,
    val restoreSettingsGeneralBasic: Boolean,
    val restoreSettingsGeneralAssets: Boolean,
    val restoreSettingsGeneralCloud: Boolean,
    val restoreSettingsDisplayEntries: Boolean,
    val restoreSettingsDisplayBills: Boolean,
    val restoreSettingsDisplayMultiBill: Boolean,
    val restoreSettingsAiCore: Boolean,
    val restoreSettingsAiChat: Boolean,
    val restoreSettingsBooks: Boolean,
    val restoreSettingsAdvancedRuntime: Boolean,
    val restoreSettingsGeneralLegacy: Boolean,
    val restoreSettingsDisplayLegacy: Boolean,
    val restoreSettingsAdvancedLegacy: Boolean,
    val restoreBanners: Boolean
)
