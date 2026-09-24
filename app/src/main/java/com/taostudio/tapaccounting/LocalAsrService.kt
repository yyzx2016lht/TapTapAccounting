package com.taostudio.tapaccounting

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

object LocalAsrService {
    private var sherpaRecognizer: OfflineRecognizer? = null
    private var isDownloading = false
    @Volatile private var cancelDownload = false
    // P1-29: 可取消的后台作用域。
    // 注意：本对象是进程级单例，serviceScope 随进程生命周期存续，不提供 shutdown——
    // scope 取消后无法重建，之后所有 serviceScope.launch 都会静默失效（曾经的死代码 shutdown 已移除）。
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var isInitializing = false
    @Volatile private var switchToMirrorRequested = false
    @Volatile private var slowPromptShown = false
    private val initMutex = Mutex()

    private const val MODEL_URL_GITHUB =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    private const val MODEL_MIRROR_BASE =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"
    private const val MODEL_DIR_NAME = "sherpa-onnx-sense-voice"
    private const val EXTRACTED_FOLDER_NAME = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    private const val SLOW_SWITCH_MS = 60_000L
    private const val SLOW_SWITCH_PROGRESS = 10

    private const val MIN_ONNX_BYTES = 5L * 1024L * 1024L
    private const val MIN_TOKENS_BYTES = 16L

    @Volatile private var lastInitError: String? = null

    private data class ModelFiles(
        val dir: File,
        val onnx: File,
        val tokens: File
    )

    private enum class DownloadSource {
        GITHUB_ARCHIVE,
        MIRROR_FILES;

        fun toPrefValue(): String = when (this) {
            GITHUB_ARCHIVE -> "github"
            MIRROR_FILES -> "mirror"
        }

        companion object {
            fun fromPrefValue(value: String?): DownloadSource = when (value) {
                "mirror" -> MIRROR_FILES
                else -> GITHUB_ARCHIVE
            }
        }
    }

    fun getLastInitError(): String? = lastInitError

    fun isRecognizerReady(): Boolean = sherpaRecognizer != null

    private fun pickOnnxFile(dir: File): File? {
        val int8 = File(dir, "model.int8.onnx")
        if (int8.exists()) return int8
        val fp32 = File(dir, "model.onnx")
        if (fp32.exists()) return fp32
        return null
    }

    private fun isModelFilePlausible(file: File, minBytes: Long): Boolean {
        return file.isFile && file.canRead() && file.length() >= minBytes
    }

    private fun hasModelFiles(dir: File): Boolean {
        val tokens = File(dir, "tokens.txt")
        val onnx = pickOnnxFile(dir)
        return onnx != null && tokens.exists()
    }

    private fun findModelFolder(modelDir: File): File? {
        if (!modelDir.exists() || !modelDir.isDirectory) return null

        val scanTargets = LinkedHashSet<File>()
        val defaultFolder = File(modelDir, EXTRACTED_FOLDER_NAME)
        if (defaultFolder.exists()) scanTargets += defaultFolder
        scanTargets += modelDir

        for (dir in modelDir.walkTopDown().maxDepth(6)) {
            if (dir.isDirectory) scanTargets += dir
        }

        var bestDir: File? = null
        var bestScore = Long.MIN_VALUE

        for (dir in scanTargets) {
            if (!dir.isDirectory || !hasModelFiles(dir)) continue

            val onnx = pickOnnxFile(dir) ?: continue
            val tokens = File(dir, "tokens.txt")
            val plausibilityBonus =
                (if (isModelFilePlausible(onnx, MIN_ONNX_BYTES)) 2_000_000_000L else 0L) +
                (if (isModelFilePlausible(tokens, MIN_TOKENS_BYTES)) 1_000_000_000L else 0L)
            val score = plausibilityBonus + onnx.length().coerceAtLeast(0L) + tokens.length().coerceAtLeast(0L)

            if (score > bestScore) {
                bestScore = score
                bestDir = dir
            }
        }

        return bestDir
    }

    private fun resolveModelFiles(modelDir: File): ModelFiles? {
        val folder = findModelFolder(modelDir) ?: return null
        val onnx = pickOnnxFile(folder) ?: return null
        val tokens = File(folder, "tokens.txt")
        if (!tokens.exists()) return null
        return ModelFiles(folder, onnx, tokens)
    }

    private fun dumpModelDirTree(ctx: Context, modelDir: File) {
        if (!modelDir.exists()) {
            Logger.d(ctx, "LocalAsrService", "modelDir does not exist")
            return
        }
        val entries = modelDir.walkTopDown().maxDepth(6).toList()
        val dirCount = entries.count { it.isDirectory }
        val fileCount = entries.count { it.isFile }
        Logger.d(ctx, "LocalAsrService", "modelDir summary: dirs=$dirCount, files=$fileCount")
    }

    private fun sanitizeEntryName(rawName: String): String {
        return rawName
            .replace('\\', '/')
            .trimStart('/')
            .removePrefix("./")
    }

    private fun resolveDestPath(targetDir: File, entryName: String): File? {
        if (entryName.isBlank()) return null
        val base = targetDir.canonicalFile
        val dest = File(base, entryName).canonicalFile
        val basePath = base.path + File.separator
        val inside = dest.path == base.path || dest.path.startsWith(basePath)
        return if (inside) dest else null
    }

    private fun extractTarBz2(ctx: Context, source: InputStream, targetDir: File) {
        BufferedInputStream(source).use { bis ->
            BZip2CompressorInputStream(bis).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        if (cancelDownload) break

                        val entryName = sanitizeEntryName(entry.name)
                        val destPath = resolveDestPath(targetDir, entryName)
                        if (destPath == null) {
                            Logger.d(ctx, "LocalAsrService", "Skip suspicious tar entry")
                            entry = tarIn.nextTarEntry
                            continue
                        }

                        if (entry.isDirectory) {
                            destPath.mkdirs()
                        } else {
                            destPath.parentFile?.mkdirs()
                            FileOutputStream(destPath).use { out ->
                                val buffer = ByteArray(8192)
                                var len: Int
                                while (tarIn.read(buffer).also { len = it } != -1) {
                                    if (cancelDownload) break
                                    out.write(buffer, 0, len)
                                }
                            }
                        }

                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }

    fun installLocalModelWithUI(ctx: Context, uri: android.net.Uri, onComplete: () -> Unit = {}) {
        val targetDir = File(ctx.filesDir, MODEL_DIR_NAME)
        // P1-28: 先解压到 staging，成功后再替换，取消/失败不抹掉可用模型
        val stagingDir = File(ctx.filesDir, MODEL_DIR_NAME + ".staging")
        val importArchive = File(ctx.cacheDir, "sense_voice_import.tar.bz2")
        cancelDownload = false

        val dialog = AlertDialog.Builder(ContextThemeWrapper(ctx, R.style.Theme_TapAccounting))
            .setTitle(ctx.getString(R.string.import_local_model))
            .setMessage(ctx.getString(R.string.preparing_extract))
            .setCancelable(false)
            .setNegativeButton(ctx.getString(R.string.cancel)) { _, _ -> cancelDownload = true }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.84f,
            cancelOnTouchOutside = false,
            useSolidPanelBackground = true
        )

        serviceScope.launch {
            try {
                withContext(Dispatchers.Main) { dialog.setMessage(ctx.getString(R.string.reading_file)) }

                val inputStream = ctx.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        Utils.toast(ctx, ctx.getString(R.string.cannot_read_file))
                    }
                    return@launch
                }

                importArchive.delete()
                inputStream.use { ins ->
                    FileOutputStream(importArchive).use { out ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        var total = 0L
                        while (ins.read(buffer).also { len = it } != -1) {
                            if (cancelDownload) break
                            out.write(buffer, 0, len)
                            total += len
                        }
                        out.flush()
                        if (total <= 0L) {
                            throw IllegalStateException("archive is empty")
                        }
                    }
                }

                if (cancelDownload) {
                    importArchive.delete()
                    stagingDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        Utils.toast(ctx, ctx.getString(R.string.import_canceled))
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { dialog.setMessage(ctx.getString(R.string.extracting_model)) }
                stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                FileInputStream(importArchive).use { fis ->
                    extractTarBz2(ctx, fis, stagingDir)
                }
                importArchive.delete()

                if (cancelDownload) {
                    stagingDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        Utils.toast(ctx, ctx.getString(R.string.import_canceled))
                    }
                    return@launch
                }

                // 成功后才替换正式模型目录
                targetDir.deleteRecursively()
                stagingDir.renameTo(targetDir)

                withContext(Dispatchers.Main) { dialog.setMessage(ctx.getString(R.string.initializing_model)) }
                val ok = initModel(ctx, allowAutoDownload = false)

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    if (ok) {
                        Utils.toast(ctx, ctx.getString(R.string.model_ready))
                        onComplete()
                    } else {
                        Utils.toast(ctx, ctx.getString(R.string.init_failed_fmt, lastInitError ?: ctx.getString(R.string.unknown_error)))
                    }
                }
            } catch (e: Throwable) {
                Logger.dPriv(
                    ctx,
                    "LocalAsrService",
                    "installLocalModelWithUI failed: errType=${e.javaClass.simpleName}",
                    "installLocalModelWithUI detail=${e.message.orEmpty()}"
                )
                importArchive.delete()
                // P1-28: 失败只清 staging，保留原可用模型
                stagingDir.deleteRecursively()
                lastInitError = "导入失败: ${e.message ?: e.javaClass.simpleName}"
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Utils.toast(ctx, lastInitError ?: ctx.getString(R.string.import_failed))
                }
            }
        }
    }

    fun isModelReady(ctx: Context): Boolean {
        val modelDir = File(ctx.filesDir, MODEL_DIR_NAME)
        val files = resolveModelFiles(modelDir) ?: return false
        return isModelFilePlausible(files.onnx, MIN_ONNX_BYTES) &&
            isModelFilePlausible(files.tokens, MIN_TOKENS_BYTES)
    }

    fun deleteModel(ctx: Context) {
        val modelDir = File(ctx.filesDir, MODEL_DIR_NAME)
        if (modelDir.exists()) modelDir.deleteRecursively()
        sherpaRecognizer?.release()
        sherpaRecognizer = null
        isInitializing = false
        lastInitError = null
    }

    suspend fun speechToText(ctx: Context, audioFile: File): String? {
        if (sherpaRecognizer == null) {
            val success = initModel(ctx)
            if (!success) {
                return if (isDownloading) "MODEL_DOWNLOADING" else "WHISPER_NOT_SETUP"
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                val recognizer = sherpaRecognizer ?: return@withContext null
                val stream = recognizer.createStream()

                val floatList = mutableListOf<Float>()
                FileInputStream(audioFile).use { fis ->
                    fis.skip(44)
                    val buf = ByteArray(4096)
                    var nbytes: Int
                    while (fis.read(buf).also { nbytes = it } > 0) {
                        var i = 0
                        while (i + 1 < nbytes) {
                            val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                            floatList.add(sample.toShort().toFloat() / 32768.0f)
                            i += 2
                        }
                    }
                }

                stream.acceptWaveform(floatList.toFloatArray(), 16000)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text
                stream.release()

                if (text.isNotBlank()) text else null
            } catch (e: Throwable) {
                Logger.dPriv(
                    ctx,
                    "LocalAsrService",
                    "speechToText failed: errType=${e.javaClass.simpleName}",
                    "speechToText detail=${e.message.orEmpty()}"
                )
                lastInitError = "识别失败: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
    }

    private fun formatInitError(e: Throwable): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val type = e.javaClass.simpleName
        return if (e is UnsatisfiedLinkError) {
            "离线识别库加载失败($type): $raw"
        } else if (e is NoClassDefFoundError || e is ClassNotFoundException) {
            "离线识别类缺失($type): $raw"
        } else {
            "离线模型初始化失败($type): $raw"
        }
    }

    private suspend fun initModel(ctx: Context, allowAutoDownload: Boolean = true): Boolean {
        val modelDir = File(ctx.filesDir, MODEL_DIR_NAME)

        val files = resolveModelFiles(modelDir)
        if (files == null) {
            lastInitError = "未找到离线模型文件"
            Logger.d(ctx, "LocalAsrService", "Model folder not found")
            dumpModelDirTree(ctx, modelDir)

            if (allowAutoDownload && !isDownloading) {
                downloadModelWithProgress(
                    ctx = ctx,
                    targetDir = modelDir,
                    onProgress = { _, _ -> },
                    onComplete = {},
                    onError = { err -> lastInitError = "模型下载失败: $err" }
                )
            }
            return false
        }

        val onnxOk = isModelFilePlausible(files.onnx, MIN_ONNX_BYTES)
        val tokensOk = isModelFilePlausible(files.tokens, MIN_TOKENS_BYTES)
        if (!onnxOk || !tokensOk) {
            lastInitError = "模型文件不完整: onnx=${files.onnx.length()}B, tokens=${files.tokens.length()}B"
            Logger.d(ctx, "LocalAsrService", lastInitError ?: "Model files invalid")
            dumpModelDirTree(ctx, files.dir)

            if (allowAutoDownload && !isDownloading) {
                modelDir.deleteRecursively()
                downloadModelWithProgress(
                    ctx = ctx,
                    targetDir = modelDir,
                    onProgress = { _, _ -> },
                    onComplete = {},
                    onError = { err -> lastInitError = "模型下载失败: $err" }
                )
            }
            return false
        }

        return initMutex.withLock {
            if (sherpaRecognizer != null) {
                Logger.d(ctx, "LocalAsrService", "initModel: already initialized, skip")
                return@withLock true
            }

            isInitializing = true
            val t0 = System.currentTimeMillis()
            Logger.d(ctx, "LocalAsrService", "initModel: start loading model")
            Logger.d(ctx, "LocalAsrService", "initModel: onnx=${files.onnx.name}(${files.onnx.length()}B) tokens=${files.tokens.name}(${files.tokens.length()}B)")
            try {
                val modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = files.onnx.absolutePath,
                        language = "",
                        useInverseTextNormalization = true
                    ),
                    tokens = files.tokens.absolutePath,
                    numThreads = 2,
                    debug = false
                )

                val config = OfflineRecognizerConfig(modelConfig = modelConfig)
                sherpaRecognizer = OfflineRecognizer(assetManager = null, config = config)

                val elapsed = System.currentTimeMillis() - t0
                lastInitError = null
                Logger.d(ctx, "LocalAsrService", "initModel success: elapsed=${elapsed}ms")
                true
            } catch (e: Throwable) {
                val elapsed = System.currentTimeMillis() - t0
                lastInitError = formatInitError(e)
                val cause = e.cause?.toString() ?: "no cause"
                Logger.d(
                    ctx,
                    "LocalAsrService",
                    "initModel failed: elapsed=${elapsed}ms [${e.javaClass.simpleName}] ${e.message} | cause=$cause"
                )
                false
            } finally {
                isInitializing = false
            }
        }
    }

    // P2-12: 音频线程与识别线程共用，必须加锁
    private val streamSamples = mutableListOf<Float>()
    private val streamSamplesLock = Any()

    /**
     * 在后台提前加载 recognizer（不阻塞调用线程）。
     * 应在用户按下录音按钮时立即调用，使 initModel 的 2 秒耗时与"长按等待 200ms + 用户开口"并发执行。
     */
    fun warmUp(ctx: Context) {
        if (sherpaRecognizer != null || isInitializing || isDownloading) return
        Logger.d(ctx, "LocalAsrService", "warmUp: triggering background initModel")
        serviceScope.launch {
            initModel(ctx, allowAutoDownload = false)
        }
    }

    suspend fun startStreaming(ctx: Context): Boolean {
        if (sherpaRecognizer == null) {
            Logger.d(ctx, "LocalAsrService", "startStreaming: recognizer null, calling initModel...")
            val t0 = System.currentTimeMillis()
            val success = initModel(ctx)
            val elapsed = System.currentTimeMillis() - t0
            Logger.d(ctx, "LocalAsrService", "startStreaming: initModel done success=$success elapsed=${elapsed}ms")
            if (!success) return false
        } else {
            Logger.d(ctx, "LocalAsrService", "startStreaming: recognizer already initialized")
        }
        if (sherpaRecognizer == null) return false
        synchronized(streamSamplesLock) { streamSamples.clear() }
        return true
    }

    fun acceptStreamingData(data: ByteArray, length: Int): String? {
        var i = 0
        while (i + 1 < length) {
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            synchronized(streamSamplesLock) { streamSamples.add(sample.toShort().toFloat() / 32768.0f) }
            i += 2
        }
        return null
    }

    fun resetStreamingBuffer() {
        synchronized(streamSamplesLock) { streamSamples.clear() }
    }

    fun finishStreaming(): String? {
        val rec = sherpaRecognizer ?: return null
        return try {
            val stream = rec.createStream()
            val samplesCopy = synchronized(streamSamplesLock) { streamSamples.toFloatArray() }
            stream.acceptWaveform(samplesCopy, 16000)
            rec.decode(stream)

            val text = rec.getResult(stream).text
            stream.release()

            synchronized(streamSamplesLock) { streamSamples.clear() }
            if (text.isNotBlank()) text else null
        } catch (e: Throwable) {
            lastInitError = "流式识别失败: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    fun downloadModelWithUI(ctx: Context, onComplete: () -> Unit = {}) {
        val targetDir = File(ctx.filesDir, MODEL_DIR_NAME)
        cancelDownload = false
        switchToMirrorRequested = false
        slowPromptShown = false

        val dialog = AlertDialog.Builder(ContextThemeWrapper(ctx, R.style.Theme_TapAccounting))
            .setTitle(ctx.getString(R.string.download_offline_model))
            .setMessage(ctx.getString(R.string.connecting))
            .setCancelable(false)
            .setNegativeButton(ctx.getString(R.string.cancel)) { _, _ -> cancelDownload = true }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.84f,
            cancelOnTouchOutside = false,
            useSolidPanelBackground = true
        )

        downloadModelWithProgress(
            ctx = ctx,
            targetDir = targetDir,
            onProgress = { _, msg -> dialog.setMessage(msg) },
            onSlowGithub = { progress, requestSwitch ->
                if (slowPromptShown) return@downloadModelWithProgress
                slowPromptShown = true
                val slowDialog = AlertDialog.Builder(ContextThemeWrapper(ctx, R.style.Theme_TapAccounting))
                    .setTitle(ctx.getString(R.string.download_slow_title))
                    .setMessage(ctx.getString(R.string.download_slow_message_fmt, progress))
                    .setPositiveButton(ctx.getString(R.string.switch_source)) { _, _ -> requestSwitch() }
                    .setNegativeButton(ctx.getString(R.string.keep_waiting), null)
                    .create()
                OverlayDialogs.showPageCenterDialog(
                    dialog = slowDialog,
                    ctx = ctx,
                    widthRatio = 0.88f,
                    cancelOnTouchOutside = true,
                    useSolidPanelBackground = true
                )
            },
            onComplete = {
                dialog.dismiss()
                Utils.toast(ctx, ctx.getString(R.string.model_ready))
                onComplete()
            },
            onError = { err ->
                dialog.dismiss()
                Utils.toast(ctx, ctx.getString(R.string.download_failed_fmt, err))
            }
        )
    }

    fun downloadModelWithProgress(
        ctx: Context,
        targetDir: File = File(ctx.filesDir, MODEL_DIR_NAME),
        onProgress: (Int, String) -> Unit,
        onSlowGithub: ((Int, () -> Unit) -> Unit)? = null,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isDownloading) {
            onError(ctx.getString(R.string.already_downloading))
            return
        }

        isDownloading = true
        serviceScope.launch {
            val tarFile = File(ctx.cacheDir, "sense_voice.tar.bz2")
            val extractedDir = File(targetDir, EXTRACTED_FOLDER_NAME)
            val preferredSource = DownloadSource.fromPrefValue(Prefs.getAsrDownloadSource(ctx))

            try {
                withContext(Dispatchers.Main) { onProgress(0, ctx.getString(R.string.connecting)) }

                val sourceUsed = downloadWithFallbackStrategy(
                    ctx = ctx,
                    tarFile = tarFile,
                    extractedDir = extractedDir,
                    preferredSource = preferredSource,
                    onProgress = onProgress,
                    onSlowGithub = onSlowGithub
                )

                if (cancelDownload) {
                    tarFile.delete()
                    extractedDir.deleteRecursively()
                    withContext(Dispatchers.Main) { onError(ctx.getString(R.string.download_canceled)) }
                    return@launch
                }

                if (sourceUsed == DownloadSource.GITHUB_ARCHIVE) {
                    withContext(Dispatchers.Main) { onProgress(100, ctx.getString(R.string.extracting_model)) }
                    targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    FileInputStream(tarFile).use { fis ->
                        extractTarBz2(ctx, fis, targetDir)
                    }
                    tarFile.delete()
                } else {
                    targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    val finalDir = File(targetDir, EXTRACTED_FOLDER_NAME)
                    finalDir.deleteRecursively()
                    extractedDir.copyRecursively(finalDir, overwrite = true)
                }

                if (cancelDownload) {
                    targetDir.deleteRecursively()
                    withContext(Dispatchers.Main) { onError(ctx.getString(R.string.extract_canceled)) }
                    return@launch
                }

                withContext(Dispatchers.Main) { onProgress(100, ctx.getString(R.string.initializing_model)) }
                val ok = initModel(ctx, allowAutoDownload = false)
                if (!ok) {
                    val msg = lastInitError ?: ctx.getString(R.string.model_init_failed)
                    withContext(Dispatchers.Main) { onError(msg) }
                    return@launch
                }

                Prefs.setAsrDownloadSource(ctx, sourceUsed.toPrefValue())
                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Throwable) {
                Logger.dPriv(
                    ctx,
                    "LocalAsrService",
                    "downloadModelWithProgress failed: errType=${e.javaClass.simpleName}",
                    "downloadModelWithProgress detail=${e.message.orEmpty()}"
                )
                lastInitError = e.message ?: e.javaClass.simpleName
                targetDir.deleteRecursively()
                tarFile.delete()
                extractedDir.deleteRecursively()
                withContext(Dispatchers.Main) {
                    onError(lastInitError ?: ctx.getString(R.string.unknown_error))
                }
            } finally {
                isDownloading = false
                switchToMirrorRequested = false
                slowPromptShown = false
            }
        }
    }

    private suspend fun downloadWithFallbackStrategy(
        ctx: Context,
        tarFile: File,
        extractedDir: File,
        preferredSource: DownloadSource,
        onProgress: (Int, String) -> Unit,
        onSlowGithub: ((Int, () -> Unit) -> Unit)?
    ): DownloadSource {
        switchToMirrorRequested = false
        tarFile.delete()
        extractedDir.deleteRecursively()

        return if (preferredSource == DownloadSource.MIRROR_FILES) {
            runCatching {
                withContext(Dispatchers.Main) { onProgress(0, ctx.getString(R.string.connecting_mirror)) }
                downloadMirrorFiles(ctx, extractedDir, onProgress)
                DownloadSource.MIRROR_FILES
            }.getOrElse {
                if (cancelDownload) return DownloadSource.MIRROR_FILES
                extractedDir.deleteRecursively()
                withContext(Dispatchers.Main) { onProgress(0, ctx.getString(R.string.mirror_unavailable_switch_github)) }
                val githubDone = downloadGithubArchive(
                    ctx = ctx,
                    tarFile = tarFile,
                    onProgress = onProgress,
                    onSlowGithub = onSlowGithub
                )
                if (githubDone || cancelDownload) DownloadSource.GITHUB_ARCHIVE
                else {
                    withContext(Dispatchers.Main) { onProgress(0, ctx.getString(R.string.github_slow_switch_mirror)) }
                    downloadMirrorFiles(ctx, extractedDir, onProgress)
                    DownloadSource.MIRROR_FILES
                }
            }
        } else {
            val githubDone = downloadGithubArchive(
                ctx = ctx,
                tarFile = tarFile,
                onProgress = onProgress,
                onSlowGithub = onSlowGithub
            )
            if (githubDone || cancelDownload) {
                DownloadSource.GITHUB_ARCHIVE
            } else {
                withContext(Dispatchers.Main) { onProgress(0, ctx.getString(R.string.switching_to_mirror)) }
                downloadMirrorFiles(ctx, extractedDir, onProgress)
                DownloadSource.MIRROR_FILES
            }
        }
    }

    private suspend fun downloadGithubArchive(
        ctx: Context,
        tarFile: File,
        onProgress: (Int, String) -> Unit,
        onSlowGithub: ((Int, () -> Unit) -> Unit)?
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val connection = (URL(MODEL_URL_GITHUB).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            connect()
        }

        val totalLength = connection.contentLengthLong
        connection.inputStream.use { input ->
            BufferedInputStream(input).use { bis ->
                FileOutputStream(tarFile).use { output ->
                    val data = ByteArray(8192)
                    var count: Int
                    var total = 0L
                    var lastProgress = -1

                    while (bis.read(data).also { count = it } != -1) {
                        if (cancelDownload) return false
                        output.write(data, 0, count)
                        total += count

                        if (totalLength > 0) {
                            val progress = ((total * 100) / totalLength).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) {
                                    onProgress(progress, ctx.getString(R.string.downloading_progress_fmt, progress))
                                }
                            }
                            if (!slowPromptShown &&
                                onSlowGithub != null &&
                                System.currentTimeMillis() - startTime >= SLOW_SWITCH_MS &&
                                progress < SLOW_SWITCH_PROGRESS
                            ) {
                                withContext(Dispatchers.Main) {
                                    onSlowGithub.invoke(progress) { switchToMirrorRequested = true }
                                }
                                slowPromptShown = true
                            }
                        }

                        if (switchToMirrorRequested) {
                            output.flush()
                            tarFile.delete()
                            return false
                        }
                    }
                }
            }
        }
        return true
    }

    private suspend fun downloadMirrorFiles(
        ctx: Context,
        extractedDir: File,
        onProgress: (Int, String) -> Unit
    ) {
        extractedDir.deleteRecursively()
        extractedDir.mkdirs()

        val files = listOf(
            "model.int8.onnx" to File(extractedDir, "model.int8.onnx"),
            "tokens.txt" to File(extractedDir, "tokens.txt")
        )

        var totalBytes = 0L
        files.forEach { (name, _) ->
            val conn = (URL(MODEL_MIRROR_BASE + name).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 120_000
                connect()
            }
            val len = conn.contentLengthLong.coerceAtLeast(0L)
            if (len > 0) totalBytes += len
            conn.disconnect()
        }

        var downloaded = 0L
        var lastProgress = -1
        files.forEach { (name, target) ->
            val conn = (URL(MODEL_MIRROR_BASE + name).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                connect()
            }
            conn.inputStream.use { input ->
                BufferedInputStream(input).use { bis ->
                    BufferedOutputStream(FileOutputStream(target)).use { output ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        while (bis.read(buffer).also { count = it } != -1) {
                            if (cancelDownload) return
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (totalBytes > 0) {
                                val progress = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress, ctx.getString(R.string.downloading_from_mirror_fmt, progress))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

