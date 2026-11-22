package com.example.download

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.model.DownloadedSongRecord
import com.example.model.PulseTrack
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

class DownloadedSongsRepository(private val context: Context) {
  private val catalogFile = File(context.filesDir, "pulse_downloaded_catalog.json")
  private val lock = Any()
  private val cachedSongs = CopyOnWriteArrayList<DownloadedSongRecord>()

  init {
    loadCatalogFromDisk()
  }

  fun getCachedList(): List<DownloadedSongRecord> = cachedSongs.toList()

  fun findByTrackId(trackId: String): DownloadedSongRecord? =
    cachedSongs.firstOrNull { it.track.id == trackId }

  fun findPlayableLocalUri(trackId: String): String? {
    val rec = findByTrackId(trackId) ?: return null
    return if (uriExistsAndReadable(rec.localUri)) rec.localUri else null
  }

  fun listExistingFileNames(): Set<String> {
    val names = mutableSetOf<String>()
    cachedSongs.forEach { if (it.fileName.isNotBlank()) names.add(it.fileName) }
    queryMediaStorePulseFiles().forEach { names.add(it.fileName) }
    listAppLegacyPulseDir().listFiles()?.forEach { if (it.isFile) names.add(it.name) }
    return names
  }

  fun upsertManagedDownload(record: DownloadedSongRecord): List<DownloadedSongRecord> =
    synchronized(lock) {
      val next =
        mutableListOf(record).apply {
          addAll(cachedSongs.filter { it.track.id != record.track.id && it.localUri != record.localUri })
        }
      cachedSongs.clear()
      cachedSongs.addAll(next)
      saveCatalogToDisk(next)
      next
    }

  fun importExternalUri(uri: Uri): DownloadedSongRecord? {
    if (!uriExistsAndReadable(uri.toString())) return null
    val meta = extractFileMetadata(uri)
    val id = "local_" + abs(uri.toString().hashCode()).toString(36)
    val track =
      PulseTrack(
        id = id,
        title = meta.title,
        artist = meta.artist,
        album = meta.album,
        durationSec = meta.durationSec,
        thumbUrl = "",
        localUri = uri.toString(),
      )
    val record =
      DownloadedSongRecord(
        track = track,
        localUri = uri.toString(),
        fileName = meta.displayName,
        mimeType = meta.mimeType,
        sizeBytes = meta.sizeBytes,
        addedAtMs = System.currentTimeMillis(),
        isAppManaged = false,
      )
    upsertManagedDownload(record)
    return record
  }

  fun deleteSong(trackId: String, localUri: String): Boolean {
    synchronized(lock) {
      val target =
        cachedSongs.firstOrNull {
          (trackId.isNotBlank() && it.track.id == trackId) ||
            (localUri.isNotBlank() && it.localUri == localUri)
        }
      val uriString = target?.localUri?.ifBlank { localUri } ?: localUri
      var fileRemoved = true
      if (uriString.isNotBlank()) {
        fileRemoved =
          try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
              "content" -> {
                val deleted = context.contentResolver.delete(uri, null, null) > 0
                if (!deleted && target?.isAppManaged == false) {
                  try {
                    context.contentResolver.releasePersistableUriPermission(
                      uri,
                      android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                  } catch (_: Exception) {}
                  true
                } else {
                  deleted || !uriExistsAndReadable(uriString)
                }
              }
              "file" -> {
                val path = uri.path
                if (path != null) {
                  val f = File(path)
                  !f.exists() || f.delete()
                } else true
              }
              else -> true
            }
          } catch (_: Exception) {
            !uriExistsAndReadable(uriString)
          }
      }
      val remaining =
        cachedSongs.filterNot {
          (trackId.isNotBlank() && it.track.id == trackId) ||
            (uriString.isNotBlank() && it.localUri == uriString)
        }
      cachedSongs.clear()
      cachedSongs.addAll(remaining)
      saveCatalogToDisk(remaining)
      return fileRemoved
    }
  }

  fun syncWithStorage(): List<DownloadedSongRecord> {
    synchronized(lock) {
      val mediaEntries = queryMediaStorePulseFiles()
      val legacyFiles = listAppLegacyPulseDir().listFiles()?.filter { it.isFile && it.length() > 2048 }.orEmpty()

      val byUri = cachedSongs.associateBy { it.localUri }.toMutableMap()
      val reconciled = mutableListOf<DownloadedSongRecord>()
      val seenUris = mutableSetOf<String>()

      // 1. Reconcile MediaStore items in Music/Pulse/
      for (msEntry in mediaEntries) {
        seenUris.add(msEntry.contentUri)
        val existing = byUri[msEntry.contentUri]
        if (existing != null) {
          reconciled.add(
            existing.copy(
              fileName = msEntry.fileName.ifBlank { existing.fileName },
              sizeBytes = if (msEntry.sizeBytes > 0) msEntry.sizeBytes else existing.sizeBytes,
            )
          )
        } else {
          val parsedTitle =
            msEntry.title.ifBlank { msEntry.fileName.substringBeforeLast('.').ifBlank { "Audio Track" } }
          val id = "local_" + abs(msEntry.contentUri.hashCode()).toString(36)
          val track =
            PulseTrack(
              id = id,
              title = parsedTitle,
              artist = msEntry.artist,
              album = msEntry.album,
              durationSec = msEntry.durationSec,
              localUri = msEntry.contentUri,
            )
          reconciled.add(
            DownloadedSongRecord(
              track = track,
              localUri = msEntry.contentUri,
              fileName = msEntry.fileName,
              mimeType = msEntry.mimeType,
              sizeBytes = msEntry.sizeBytes,
              addedAtMs = msEntry.dateAddedSec * 1000L,
              isAppManaged = true,
            )
          )
        }
      }

      // 2. Reconcile app-local legacy Pulse directory (API 24-28 fallback)
      for (file in legacyFiles) {
        val fileUri = Uri.fromFile(file).toString()
        if (seenUris.contains(fileUri)) continue
        seenUris.add(fileUri)
        val existing = byUri[fileUri]
        if (existing != null) {
          reconciled.add(existing.copy(fileName = file.name, sizeBytes = file.length()))
        } else {
          val meta = extractFileMetadata(Uri.fromFile(file))
          val id = "local_" + abs(fileUri.hashCode()).toString(36)
          val track =
            PulseTrack(
              id = id,
              title = meta.title,
              artist = meta.artist,
              album = meta.album,
              durationSec = meta.durationSec,
              localUri = fileUri,
            )
          reconciled.add(
            DownloadedSongRecord(
              track = track,
              localUri = fileUri,
              fileName = file.name,
              mimeType = meta.mimeType,
              sizeBytes = file.length(),
              addedAtMs = file.lastModified(),
              isAppManaged = true,
            )
          )
        }
      }

      // 3. Preserve non-Music/Pulse imported/SAVed URIs only if they still resolve cleanly
      for (existing in cachedSongs) {
        if (seenUris.contains(existing.localUri)) continue
        if (uriExistsAndReadable(existing.localUri)) {
          reconciled.add(existing)
          seenUris.add(existing.localUri)
        }
      }

      val sorted = reconciled.sortedByDescending { it.addedAtMs }
      cachedSongs.clear()
      cachedSongs.addAll(sorted)
      saveCatalogToDisk(sorted)
      return sorted
    }
  }

  fun listAppLegacyPulseDir(): File =
    File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Pulse")
      .apply { mkdirs() }

  private data class MediaStoreAudioRow(
    val contentUri: String,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val mimeType: String,
    val durationSec: Int?,
    val sizeBytes: Long,
    val dateAddedSec: Long,
  )

  private fun queryMediaStorePulseFiles(): List<MediaStoreAudioRow> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
    val rows = mutableListOf<MediaStoreAudioRow>()
    val projection =
      arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.RELATIVE_PATH,
      )
    val selection =
      "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
    val args = arrayOf("${Environment.DIRECTORY_MUSIC}/Pulse%")
    val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
    try {
      context.contentResolver
        .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args, sort)
        ?.use { cursor ->
          val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
          val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
          val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
          val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
          val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
          val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
          val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
          val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
          val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
          while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val size = cursor.getLong(sizeCol)
            if (size < 2048L) continue
            val contentUri =
              ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
            val rawArtist = cursor.getString(artistCol).orEmpty()
            val artist = if (rawArtist == "<unknown>") "" else rawArtist
            val durMs = cursor.getLong(durCol)
            val durSec = if (durMs > 0L) (durMs / 1000L).toInt() else null
            rows.add(
              MediaStoreAudioRow(
                contentUri = contentUri,
                fileName = cursor.getString(nameCol).orEmpty(),
                title = cursor.getString(titleCol).orEmpty(),
                artist = artist,
                album = cursor.getString(albumCol).orEmpty(),
                mimeType = cursor.getString(mimeCol).orEmpty().ifBlank { "audio/mp4" },
                durationSec = durSec,
                sizeBytes = size,
                dateAddedSec = cursor.getLong(dateCol),
              )
            )
          }
        }
    } catch (_: Exception) {}
    return rows
  }

  private fun uriExistsAndReadable(uriString: String): Boolean {
    if (uriString.isBlank()) return false
    return try {
      val uri = Uri.parse(uriString)
      when (uri.scheme) {
        "file" -> {
          val f = uri.path?.let { File(it) }
          f != null && f.exists() && f.isFile && f.length() > 1024L
        }
        "content" -> {
          context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            afd.length == -1L || afd.length > 1024L
          } ?: false
        }
        else -> false
      }
    } catch (_: Exception) {
      false
    }
  }

  private data class ExtractedMeta(
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int?,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
  )

  private fun extractFileMetadata(uri: Uri): ExtractedMeta {
    var displayName = uri.lastPathSegment.orEmpty().substringAfterLast('/')
    var sizeBytes = 0L
    if (uri.scheme == "content") {
      try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
          if (c.moveToFirst()) {
            val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (nIdx >= 0) displayName = c.getString(nIdx).orEmpty().ifBlank { displayName }
            if (sIdx >= 0) sizeBytes = c.getLong(sIdx)
          }
        }
      } catch (_: Exception) {}
    } else if (uri.scheme == "file" && uri.path != null) {
      val f = File(uri.path!!)
      displayName = f.name
      sizeBytes = f.length()
    }

    val retriever = MediaMetadataRetriever()
    var title = ""
    var artist = ""
    var album = ""
    var durationSec: Int? = null
    var mime = ""
    try {
      if (uri.scheme == "file" && uri.path != null) {
        retriever.setDataSource(uri.path)
      } else {
        retriever.setDataSource(context, uri)
      }
      title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
      artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
      album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
      mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty().trim()
      val durMs =
        retriever
          .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
          ?.toLongOrNull()
      if (durMs != null && durMs > 0L) durationSec = (durMs / 1000L).toInt()
    } catch (_: Exception) {} finally {
      try {
        retriever.release()
      } catch (_: Exception) {}
    }

    val fallbackStem = displayName.substringBeforeLast('.').ifBlank { "Audio Track" }
    if (title.isBlank()) {
      val split = fallbackStem.split(" - ", limit = 2)
      if (split.size == 2 && artist.isBlank()) {
        artist = split[0].trim()
        title = split[1].trim()
      } else {
        title = fallbackStem
      }
    }
    return ExtractedMeta(
      title = title,
      artist = artist,
      album = album,
      durationSec = durationSec,
      mimeType = mime.ifBlank { context.contentResolver.getType(uri).orEmpty().ifBlank { "audio/mp4" } },
      displayName = displayName.ifBlank { "$title.m4a" },
      sizeBytes = sizeBytes,
    )
  }

  private fun loadCatalogFromDisk() {
    if (!catalogFile.exists()) return
    try {
      val arr = JSONArray(catalogFile.readText())
      val loaded =
        buildList {
          for (i in 0 until arr.length()) {
            DownloadedSongRecord.fromJson(arr.optJSONObject(i))?.let { add(it) }
          }
        }
      cachedSongs.clear()
      cachedSongs.addAll(loaded)
    } catch (_: Exception) {}
  }

  private fun saveCatalogToDisk(items: List<DownloadedSongRecord>) {
    try {
      val arr = JSONArray().apply { items.forEach { put(it.toJson()) } }
      val temp = File(catalogFile.parentFile, "${catalogFile.name}.tmp")
      temp.writeText(arr.toString())
      temp.renameTo(catalogFile)
    } catch (_: Exception) {}
  }
}
