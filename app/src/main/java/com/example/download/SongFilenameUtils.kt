package com.example.download

import java.util.Locale

data class AudioFormatInfo(
  val extension: String,
  val mimeType: String,
  val isValidAudio: Boolean,
  val rejectionReason: String = "",
)

object SongFilenameUtils {
  private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")
  private val MULTI_SPACE = Regex("\\s{2,}")

  fun sanitizeBaseName(title: String, artist: String = "", maxLength: Int = 96): String {
    val cleanTitle = title.replace(ILLEGAL_CHARS, " ").replace(MULTI_SPACE, " ").trim().trim('.')
    val cleanArtist = artist.replace(ILLEGAL_CHARS, " ").replace(MULTI_SPACE, " ").trim().trim('.')
    val combined =
      when {
        cleanArtist.isNotBlank() && cleanTitle.isNotBlank() -> "$cleanArtist - $cleanTitle"
        cleanTitle.isNotBlank() -> cleanTitle
        cleanArtist.isNotBlank() -> cleanArtist
        else -> "Pulse Track"
      }
    val clipped = combined.take(maxLength).trim().trim('.')
    return clipped.ifBlank { "Pulse Track" }
  }

  fun deduplicateFileName(baseName: String, extension: String, existingNames: Set<String>): String {
    val extClean = extension.trim().trimStart('.').lowercase(Locale.US).ifBlank { "m4a" }
    val existingLower = existingNames.map { it.lowercase(Locale.US) }.toSet()
    val first = "$baseName.$extClean"
    if (!existingLower.contains(first.lowercase(Locale.US))) return first
    for (index in 2..999) {
      val candidate = "$baseName ($index).$extClean"
      if (!existingLower.contains(candidate.lowercase(Locale.US))) return candidate
    }
    return "${baseName}_${System.currentTimeMillis()}.$extClean"
  }

  fun inspectStreamFormat(
    contentType: String?,
    headBytes: ByteArray,
    urlHint: String? = null,
    fallbackExt: String = "m4a",
  ): AudioFormatInfo {
    val mime = contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.US)
    if (
      mime.contains("text/html") ||
        mime.contains("application/xhtml") ||
        mime.contains("application/json") ||
        mime.contains("text/plain") ||
        mime.contains("application/xml") ||
        mime.contains("text/xml")
    ) {
      return AudioFormatInfo(fallbackExt, "application/octet-stream", false, "Rejected non-audio MIME: $mime")
    }

    if (looksLikeTextPayload(headBytes)) {
      return AudioFormatInfo(
        fallbackExt,
        "application/octet-stream",
        false,
        "Rejected HTML/JSON/text response body",
      )
    }

    detectMagicFormat(headBytes)?.let { (ext, detectedMime) ->
      return AudioFormatInfo(ext, detectedMime, true)
    }

    return when {
      mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") ->
        AudioFormatInfo("m4a", "audio/mp4", true)
      mime.contains("webm") -> AudioFormatInfo("webm", "audio/webm", true)
      mime.contains("ogg") || mime.contains("opus") -> AudioFormatInfo("ogg", "audio/ogg", true)
      mime.contains("mpeg") || mime.contains("mp3") -> AudioFormatInfo("mp3", "audio/mpeg", true)
      mime.contains("flac") -> AudioFormatInfo("flac", "audio/flac", true)
      mime.contains("wav") -> AudioFormatInfo("wav", "audio/wav", true)
      mime.startsWith("audio/") -> AudioFormatInfo(fallbackExt, mime, true)
      mime.isBlank() || mime == "application/octet-stream" -> {
        val inferred = inferFromUrl(urlHint, fallbackExt)
        if (headBytes.size >= 16) AudioFormatInfo(inferred.first, inferred.second, true)
        else AudioFormatInfo(fallbackExt, "application/octet-stream", false, "Insufficient audio bytes")
      }
      else -> AudioFormatInfo(fallbackExt, mime, false, "Unsupported MIME: $mime")
    }
  }

  private fun looksLikeTextPayload(head: ByteArray): Boolean {
    if (head.isEmpty()) return true
    val prefix = String(head, 0, minOf(head.size, 256), Charsets.UTF_8).trimStart().lowercase(Locale.US)
    return prefix.startsWith("<!doctype") ||
      prefix.startsWith("<html") ||
      prefix.startsWith("<head") ||
      prefix.startsWith("<?xml") ||
      prefix.startsWith("<mpd") ||
      prefix.startsWith("#extm3u") ||
      prefix.startsWith("{") ||
      prefix.startsWith("[")
  }

  private fun detectMagicFormat(head: ByteArray): Pair<String, String>? {
    if (head.size >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
      head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
    ) {
      return "m4a" to "audio/mp4"
    }
    if (head.size >= 4 &&
      head[0] == 0x1A.toByte() &&
      head[1] == 0x45.toByte() &&
      head[2] == 0xDF.toByte() &&
      head[3] == 0xA3.toByte()
    ) {
      return "webm" to "audio/webm"
    }
    if (head.size >= 4 &&
      head[0] == 'O'.code.toByte() &&
      head[1] == 'g'.code.toByte() &&
      head[2] == 'g'.code.toByte() &&
      head[3] == 'S'.code.toByte()
    ) {
      return "ogg" to "audio/ogg"
    }
    if (head.size >= 4 &&
      head[0] == 'f'.code.toByte() &&
      head[1] == 'L'.code.toByte() &&
      head[2] == 'a'.code.toByte() &&
      head[3] == 'C'.code.toByte()
    ) {
      return "flac" to "audio/flac"
    }
    if (head.size >= 3 &&
      head[0] == 'I'.code.toByte() &&
      head[1] == 'D'.code.toByte() &&
      head[2] == '3'.code.toByte()
    ) {
      return "mp3" to "audio/mpeg"
    }
    if (head.size >= 2 &&
      head[0] == 0xFF.toByte() &&
      (head[1].toInt() and 0xE0) == 0xE0
    ) {
      return "mp3" to "audio/mpeg"
    }
    if (head.size >= 12 &&
      head[0] == 'R'.code.toByte() &&
      head[1] == 'I'.code.toByte() &&
      head[2] == 'F'.code.toByte() &&
      head[3] == 'F'.code.toByte() &&
      head[8] == 'W'.code.toByte() &&
      head[9] == 'A'.code.toByte() &&
      head[10] == 'V'.code.toByte() &&
      head[11] == 'E'.code.toByte()
    ) {
      return "wav" to "audio/wav"
    }
    return null
  }

  private fun inferFromUrl(url: String?, fallbackExt: String): Pair<String, String> {
    val lower = url.orEmpty().lowercase(Locale.US)
    return when {
      lower.contains("mime=audio%2fwebm") || lower.contains(".webm") -> "webm" to "audio/webm"
      lower.contains("mime=audio%2fogg") || lower.contains(".ogg") || lower.contains(".opus") ->
        "ogg" to "audio/ogg"
      lower.contains(".mp3") -> "mp3" to "audio/mpeg"
      lower.contains(".flac") -> "flac" to "audio/flac"
      fallbackExt == "webm" -> "webm" to "audio/webm"
      fallbackExt == "ogg" -> "ogg" to "audio/ogg"
      fallbackExt == "mp3" -> "mp3" to "audio/mpeg"
      else -> "m4a" to "audio/mp4"
    }
  }
}
