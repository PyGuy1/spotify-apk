package com.example.model

import org.json.JSONArray
import org.json.JSONObject

data class PulseTrack(
  val id: String,
  val title: String,
  val artist: String = "",
  val album: String = "",
  val durationSec: Int? = null,
  val thumbUrl: String = "",
  val ms: Int = 0,
  val tp: Int = 0,
  val v: Int = 0,
  val uq: Int = 0,
  val auto: Int = 0,
  val pn: Int = 0,
  val localUri: String = "",
) {
  fun toJson(): JSONObject =
    JSONObject().apply {
      put("id", id)
      put("title", title)
      put("artist", artist)
      if (album.isNotBlank()) put("album", album)
      if (durationSec != null && durationSec > 0) put("duration", durationSec)
      if (thumbUrl.isNotBlank()) put("thumb", thumbUrl)
      if (ms != 0) put("ms", ms)
      if (tp != 0) put("tp", tp)
      if (v != 0) put("v", v)
      if (uq != 0) put("uq", uq)
      if (auto != 0) put("auto", auto)
      if (pn != 0) put("pn", pn)
      if (localUri.isNotBlank()) put("localUri", localUri)
    }

  companion object {
    fun fromJson(obj: JSONObject?): PulseTrack? {
      if (obj == null) return null
      val id = obj.optString("id", "").trim()
      if (id.isEmpty()) return null
      val durRaw = obj.optDouble("duration", Double.NaN)
      val dur = if (!durRaw.isNaN() && durRaw > 0) durRaw.toInt() else null
      return PulseTrack(
        id = id,
        title = obj.optString("title", "").ifBlank { "Untitled" },
        artist = obj.optString("artist", ""),
        album = obj.optString("album", ""),
        durationSec = dur,
        thumbUrl = obj.optString("thumb", ""),
        ms = if (obj.optBoolean("ms", false)) 1 else obj.optInt("ms", 0),
        tp = if (obj.optBoolean("tp", false)) 1 else obj.optInt("tp", 0),
        v = if (obj.optBoolean("v", false)) 1 else obj.optInt("v", 0),
        uq = if (obj.optBoolean("uq", false)) 1 else obj.optInt("uq", 0),
        auto = if (obj.optBoolean("auto", false)) 1 else obj.optInt("auto", 0),
        pn = if (obj.optBoolean("pn", false)) 1 else obj.optInt("pn", 0),
        localUri = obj.optString("localUri", ""),
      )
    }

    fun listFromJsonArray(rawJson: String?): List<PulseTrack> {
      if (rawJson.isNullOrBlank()) return emptyList()
      return try {
        val arr = JSONArray(rawJson)
        buildList(arr.length()) {
          for (i in 0 until arr.length()) {
            fromJson(arr.optJSONObject(i))?.let { add(it) }
          }
        }
      } catch (_: Exception) {
        emptyList()
      }
    }

    fun listToJsonArray(tracks: List<PulseTrack>): JSONArray =
      JSONArray().apply { tracks.forEach { put(it.toJson()) } }
  }
}

enum class DownloadStatus(val wireName: String) {
  QUEUED("queued"),
  RESOLVING("resolving"),
  DOWNLOADING("downloading"),
  COMPLETED("completed"),
  FAILED("failed"),
  CANCELLED("cancelled"),
}

data class DownloadProgressItem(
  val track: PulseTrack,
  val status: DownloadStatus,
  val progressPercent: Int = 0,
  val bytesDownloaded: Long = 0L,
  val totalBytes: Long = -1L,
  val localUri: String = "",
  val fileName: String = "",
  val mimeType: String = "",
  val errorMessage: String = "",
  val updatedAtMs: Long = System.currentTimeMillis(),
) {
  fun toJson(): JSONObject =
    JSONObject().apply {
      put("id", track.id)
      put("track", track.toJson())
      put("status", status.wireName)
      put("progress", progressPercent.coerceIn(0, 100))
      put("bytesDownloaded", bytesDownloaded)
      put("totalBytes", totalBytes)
      put("localUri", localUri)
      put("fileName", fileName)
      put("mimeType", mimeType)
      put("error", errorMessage)
      put("updatedAt", updatedAtMs)
    }
}

data class DownloadedSongRecord(
  val track: PulseTrack,
  val localUri: String,
  val fileName: String,
  val mimeType: String,
  val sizeBytes: Long,
  val addedAtMs: Long,
  val isAppManaged: Boolean = true,
) {
  fun toJson(): JSONObject =
    track.toJson().apply {
      put("localUri", localUri)
      put("fileName", fileName)
      put("mimeType", mimeType)
      put("sizeBytes", sizeBytes)
      put("addedAt", addedAtMs)
      put("appManaged", isAppManaged)
      put("downloaded", true)
    }

  companion object {
    fun fromJson(obj: JSONObject?): DownloadedSongRecord? {
      val track = PulseTrack.fromJson(obj) ?: return null
      val localUri = obj?.optString("localUri", "")?.trim().orEmpty()
      if (localUri.isEmpty()) return null
      return DownloadedSongRecord(
        track = track.copy(localUri = localUri),
        localUri = localUri,
        fileName = obj?.optString("fileName", "").orEmpty(),
        mimeType = obj?.optString("mimeType", "audio/mp4").orEmpty(),
        sizeBytes = obj?.optLong("sizeBytes", 0L) ?: 0L,
        addedAtMs = obj?.optLong("addedAt", 0L) ?: 0L,
        isAppManaged = obj?.optBoolean("appManaged", true) ?: true,
      )
    }
  }
}

data class PlaybackSnapshot(
  val queue: List<PulseTrack>,
  val currentIndex: Int,
  val isPlaying: Boolean,
  val isBuffering: Boolean,
  val playbackEnded: Boolean,
  val positionSec: Double,
  val durationSec: Double,
  val bufferedPercent: Int,
  val shuffleEnabled: Boolean,
  val repeatEnabled: Boolean,
  val radioMode: Boolean,
  val autoplayEnabled: Boolean,
  val volumePercent: Int,
  val sleepRemainingMs: Long,
  val sleepEndOfSong: Boolean,
  val audioQuality: String,
  val errorMessage: String,
) {
  fun toJson(): JSONObject =
    JSONObject().apply {
      put("queue", PulseTrack.listToJsonArray(queue))
      put("index", currentIndex)
      put("isPlaying", isPlaying)
      put("isBuffering", isBuffering)
      put("playbackEnded", playbackEnded)
      put("position", positionSec)
      put("duration", durationSec)
      put("bufferedPercent", bufferedPercent)
      put("shuffle", shuffleEnabled)
      put("repeat", repeatEnabled)
      put("radioMode", radioMode)
      put("autoplay", autoplayEnabled)
      put("volume", volumePercent)
      put("sleepRemainingMs", sleepRemainingMs)
      put("sleepEndOfSong", sleepEndOfSong)
      put("audioQuality", audioQuality)
      put("error", errorMessage)
      put("engine", "native-media3")
    }
}
