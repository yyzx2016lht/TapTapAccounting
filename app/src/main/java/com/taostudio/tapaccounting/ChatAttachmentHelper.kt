package com.taostudio.tapaccounting

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.util.Locale

object ChatAttachmentHelper {

    const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
    const val MAX_PDF_BYTES = 20L * 1024L * 1024L
    const val MAX_PDF_PAGES = 6
    /** Internal marker prepended to the multimodal supplement when a PDF was rasterized. */
    const val PDF_PAYLOAD_MARKER = "[PDF_PAGES]"

    fun isImageMime(mime: String): Boolean = mime.startsWith("image/", ignoreCase = true)

    fun isPdfMime(mime: String, fileName: String = ""): Boolean =
        mime.equals("application/pdf", ignoreCase = true) ||
            fileName.lowercase(Locale.getDefault()).endsWith(".pdf")

    /** File picker in chat only accepts PDF. */
    fun isSupportedFilePickerMime(mime: String, fileName: String = ""): Boolean =
        isPdfMime(mime, fileName)

    fun isSupportedImagePickerMime(mime: String): Boolean = isImageMime(mime)

    fun isVideoMime(mime: String): Boolean = mime.startsWith("video/", ignoreCase = true)

    fun isAudioMime(mime: String): Boolean = mime.startsWith("audio/", ignoreCase = true)

    fun audioFormatForMime(mime: String): String = when {
        mime.contains("wav", ignoreCase = true) -> "wav"
        mime.contains("mpeg", ignoreCase = true) || mime.contains("mp3", ignoreCase = true) -> "mp3"
        mime.contains("flac", ignoreCase = true) -> "flac"
        mime.contains("ogg", ignoreCase = true) -> "ogg"
        mime.contains("mp4", ignoreCase = true) || mime.contains("m4a", ignoreCase = true) -> "m4a"
        mime.startsWith("audio/", ignoreCase = true) ->
            mime.substringAfter("audio/", "wav").substringBefore(';').ifBlank { "wav" }
        else -> "wav"
    }

    fun isDocxMime(mime: String, fileName: String): Boolean {
        if (mime.contains("wordprocessingml", ignoreCase = true)) return true
        return fileName.lowercase(Locale.getDefault()).endsWith(".docx")
    }

    fun resolveMime(context: Context, uri: Uri, fileName: String): String {
        val fromResolver = context.contentResolver.getType(uri)?.trim().orEmpty()
        if (fromResolver.isNotBlank() && fromResolver != "application/octet-stream") {
            return fromResolver
        }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "pdf" -> "application/pdf"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "doc" -> "application/msword"
                "md", "markdown" -> "text/markdown"
                "csv" -> "text/csv"
                "json" -> "application/json"
                "txt" -> "text/plain"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "avi" -> "video/x-msvideo"
                "wmv" -> "video/x-ms-wmv"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                "aac" -> "audio/aac"
                "wma" -> "audio/x-ms-wma"
                else -> "application/octet-stream"
            }
    }

    fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        cursor.getString(index)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty().ifBlank { "file" }
    }

    fun historyPlaceholder(mimes: List<String>): String {
        if (mimes.isEmpty()) return "[用户发送了附件]"
        return when {
            mimes.all(::isImageMime) -> "[用户发送了图片]"
            mimes.all(::isPdfMime) -> "[用户发送了 PDF 文件]"
            else -> "[用户发送了附件]"
        }
    }

    fun fileTypeLabel(context: Context, mime: String, fileName: String = ""): String =
        if (isPdfMime(mime, fileName)) {
            context.getString(R.string.chat_file_type_pdf)
        } else {
            context.getString(R.string.chat_file_type_generic)
        }

    fun encodeFileMessageContent(mime: String, fileName: String): String = "$mime|$fileName"

    fun decodeFileMessageContent(content: String): Pair<String, String>? {
        val separator = content.indexOf('|')
        if (separator <= 0 || separator >= content.lastIndex) return null
        val mime = content.substring(0, separator).trim()
        val fileName = content.substring(separator + 1).trim()
        if (mime.isBlank() || fileName.isBlank()) return null
        return mime to fileName
    }

    fun groupAttachmentsForDisplay(attachments: List<PendingImage>): Pair<List<PendingImage>, List<PendingImage>> {
        val images = attachments.filter { it.showsAsImageThumbnail }
        val files = attachments.filter { it.showsAsFileCard }
        return images to files
    }

    /** Flatten pending attachments into base64/mime pairs for the vision API payload. */
    fun flattenForApiPayload(attachments: List<PendingImage>): List<PendingImage> {
        return attachments.flatMap { attachment ->
            when {
                attachment.pdfPagePayloads.isNotEmpty() ->
                    attachment.pdfPagePayloads.map { (base64, mime) ->
                        PendingImage(
                            uri = null,
                            base64 = base64,
                            mime = mime,
                            fileName = attachment.fileName
                        )
                    }
                attachment.base64.isNotBlank() -> listOf(attachment)
                else -> emptyList()
            }
        }
    }

    fun attachmentSummaryLabel(mimes: List<String>): String {
        val images = mimes.count(::isImageMime)
        val pdfs = mimes.count { isPdfMime(it) || it.equals("application/pdf", true) }
        val parts = buildList {
            if (images > 0) add("${images}张图片")
            if (pdfs > 0) add("${pdfs}个 PDF")
            val other = mimes.size - images - pdfs
            if (other > 0) add("${other}个附件")
        }
        return parts.joinToString("、").ifBlank { "附件" }
    }
}
