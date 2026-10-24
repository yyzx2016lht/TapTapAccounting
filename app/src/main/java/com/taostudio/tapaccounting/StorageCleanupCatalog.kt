package com.taostudio.tapaccounting

import android.content.Context
import java.io.File
import java.util.Locale

/** 存储清理分类与文件枚举，供清理页 / 预览页共用。 */
enum class StorageCleanupCategory(
    val id: String,
    val titleRes: Int,
    val iconRes: Int
) {
    VOICE("voice", R.string.chat_voice_files, R.drawable.ic_mic),
    IMAGES("images", R.string.chat_image_files, R.drawable.ic_chat_image),
    CHAT_AVATAR("chat_avatar", R.string.chat_avatar_files, R.drawable.ic_image),
    BOOK_COVER_BG("book_cover_bg", R.string.book_cover_bg, R.drawable.ic_image),
    SCREEN_CAPTURES("screen_captures", R.string.screen_capture_cache_files, R.drawable.ic_chat_camera),
    TEMP_CACHE("temp_cache", R.string.temp_cache_files, R.drawable.ic_tune_outline);

    companion object {
        fun fromId(id: String?): StorageCleanupCategory? =
            values().firstOrNull { it.id == id }
    }
}

data class StorageCleanupGroup(
    val category: StorageCleanupCategory,
    val files: List<File>
) {
    val count: Int get() = files.size
    val bytes: Long get() = files.sumOf { it.length() }
}

object StorageCleanupCatalog {

    fun listGroup(ctx: Context, category: StorageCleanupCategory, olderOnly: Boolean): StorageCleanupGroup {
        val files = when (category) {
            StorageCleanupCategory.VOICE ->
                listFilesInDir(File(ctx.filesDir, "chat_voice"))
                    .filter { !olderOnly || isOlderThanDays(it, 30) }

            StorageCleanupCategory.IMAGES -> buildList {
                addAll(listFilesInDir(File(ctx.filesDir, "chat_images")).filter { !olderOnly || isOlderThanDays(it, 30) })
                addAll(listFilesInDir(File(ctx.filesDir, "chat_pics")).filter { !olderOnly || isOlderThanDays(it, 30) })
            }

            StorageCleanupCategory.CHAT_AVATAR -> buildList {
                addIfFileExists(File(ctx.filesDir, "chat_ai_avatar.jpg"), olderOnly)
                addIfFileExists(File(ctx.filesDir, "chat_user_avatar.jpg"), olderOnly)
                addIfFileExists(File(ctx.filesDir, "chat_ai_avatar.png"), olderOnly)
                addIfFileExists(File(ctx.filesDir, "chat_user_avatar.png"), olderOnly)
            }

            StorageCleanupCategory.BOOK_COVER_BG -> buildList {
                addAll(listFilesInDir(File(ctx.filesDir, "chat_bg")).filter { !olderOnly || isOlderThanDays(it, 30) })
                addIfFileExists(File(ctx.filesDir, "chat_bg.jpg"), olderOnly)
                addIfFileExists(File(ctx.filesDir, "chat_bg.png"), olderOnly)
                addIfFileExists(File(ctx.filesDir, "chat_bg.webp"), olderOnly)
                addIfFileExists(File(ctx.filesDir, "chat_bg.jpeg"), olderOnly)
                addIfFileExists(File(ctx.filesDir, "chat_bg.bmp"), olderOnly)
                addIfFileExists(File(ctx.filesDir, "chat_bg.gif"), olderOnly)
                addAll(listFilesInDir(File(ctx.filesDir, "banners")).filter { !olderOnly || isOlderThanDays(it, 30) })
            }

            StorageCleanupCategory.SCREEN_CAPTURES ->
                listFilesInDir(File(ctx.cacheDir, "screen_captures"))
                    .filter { !olderOnly || isOlderThanDays(it, 30) }

            StorageCleanupCategory.TEMP_CACHE -> buildList {
                addAll(listFilesInDir(File(ctx.cacheDir, "picked_images")))
                addAll(listFilesInDir(File(ctx.cacheDir, "avatar_crop")))
                addAll(listFilesInDir(File(ctx.cacheDir, "banner_crop")))
                ctx.cacheDir.listFiles()
                    ?.filter { it.isFile && shouldCleanupCacheFile(it.name) }
                    ?.forEach { add(it) }
            }
        }.distinctBy { it.absolutePath }

        return StorageCleanupGroup(category, files)
    }

    fun listAll(ctx: Context, olderOnly: Boolean): Map<StorageCleanupCategory, StorageCleanupGroup> =
        StorageCleanupCategory.values().associateWith { listGroup(ctx, it, olderOnly) }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }

    fun isImageFile(file: File): Boolean {
        val n = file.name.lowercase(Locale.getDefault())
        return n.endsWith(".jpg") ||
            n.endsWith(".jpeg") ||
            n.endsWith(".png") ||
            n.endsWith(".webp") ||
            n.endsWith(".bmp") ||
            n.endsWith(".gif")
    }

    fun isAudioFile(file: File): Boolean {
        val n = file.name.lowercase(Locale.getDefault())
        return n.endsWith(".wav") ||
            n.endsWith(".mp3") ||
            n.endsWith(".m4a") ||
            n.endsWith(".amr") ||
            n.endsWith(".aac") ||
            n.endsWith(".ogg") ||
            n.endsWith(".opus")
    }

    fun shouldCleanupCacheFile(name: String): Boolean {
        val n = name.lowercase(Locale.getDefault())
        return n.startsWith("temp_") ||
            n == "voice_input.wav" ||
            n == "chat_voice_input.wav" ||
            n.startsWith("sense_voice") ||
            n.endsWith(".bak")
    }

    fun listFilesInDir(dir: File): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile }.toList()
    }

    private fun MutableList<File>.addIfFileExists(file: File, olderOnly: Boolean) {
        if (file.exists() && file.isFile && (!olderOnly || isOlderThanDays(file, 30))) add(file)
    }

    fun isOlderThanDays(file: File, days: Int): Boolean {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        return file.lastModified() in 1 until cutoff
    }
}
