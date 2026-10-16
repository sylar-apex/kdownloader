package org.app.kdownloader.platform

import org.app.kdownloader.model.MediaType

/** Mime types by file extension, for platforms that store a type next to the file. */
object MimeTypes {
    fun of(fileName: String, type: MediaType): String {
        val video = type == MediaType.VIDEO
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "webm" -> if (video) "video/webm" else "audio/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> if (video || type == MediaType.OTHER) "video/mp4" else "audio/mp4"
            else -> when (type) {
                MediaType.VIDEO -> "video/mp4"
                MediaType.AUDIO -> "audio/mpeg"
                MediaType.OTHER -> "application/octet-stream"
            }
        }
    }
}
