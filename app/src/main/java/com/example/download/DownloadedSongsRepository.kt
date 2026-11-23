package com.example.download

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.PulseLog
import com.example.db.DownloadedSongEntity
import com.example.db.PulseDatabase
import com.example.db.SongFormatEntity
import com.example.model.DownloadedSongRecord
import com.example.model.PulseTrack
import com.example.playback.CacheModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

class DownloadedSongsRepository(
  private val context: Context,
  private val database: PulseDatabase? = PulseDatabase.get(context),
  private val cacheModule: CacheModule? = null,
) {
  private val legacyCatalogFile = File(context.filesDir, "pulse_downloaded_catalog.json")
  private val lock = Any()
  private val cachedSongs = CopyOnWriteArrayList<DownloadedSongRecord>()

  init {
    loadCatalogFromDatabaseOrLegacy()
  }

  fun observeDownloadedSongs(): Flow<List<DownloadedSongRecord>>? =
    database?.downloadedSongDao()?.observeAll()?.map { list -> list.map { it.toRecord() } }

  fun getCachedList(): List<DownloadedSongRecord> = cachedSongs.toList()

  fun findByTrackId(trackId: String): DownloadedSongRecord? =
    cachedSongs.firstOrNull { it.track.id == trackId }
      ?: database?.downloadedSongDao()?.findByTrackIdSync(trackId)?.toRecord()

  fun findPlayableLocalUri(trackId: String): String? {
    val rec = findByTrackId(trackId) ?: return null
    return if (uriExistsAndReadable(rec.localUri)) rec.localUri else null
  }

  fun saveSongFormat(format: SongFormatEntity) {
    try {
      database?.songFormatDao()?.upsertSync(format)
    } catch (e: Exception) {
      PulseLog.d(TAG, "Failed saving SongFormatEntity: ${e.message}")
    }
  }

  fun getSongFormat(trackId: String): SongFormatEntity? =
    try {
      database?.songFormatDao()?.findByTrackIdSync(trackId)
    } catch (_: Exception) {
      null
    }

  fun listExistingFileNames(): Set<String> {
    val names = mutableSetOf<String>()
    cachedSongs.forEach { if (it.fileName.isNotBlank()) names.add(it.fileName) }
    queryMediaStoreMusicFiles().forEach { names.add(it.fileName) }
    listAppPyMusicDir().listFiles()?.forEach { if (it.isFile) names.add(it.name) }
    listAppLegacyPulseDir().listFiles()?.forEach { if (it.isFile) names.add(it.name) }
    return names
  }

  fun upsertManagedDownload(record: DownloadedSongRecord): List<DownloadedSongRecord> =
    synchronized(lock) {
      val enriched =
        record.copy(
          cacheStatus =
            if (cacheModule?.hasDownloadCacheContent(record.track.id) == true) "cached+file"
            else record.cacheStatus.ifBlank { "completed" },
        )
      val next =
        mutableListOf(enriched).apply {
          addAll(cachedSongs.filter { it.track.id != enriched.track.id && it.localUri != enriched.localUri })
        }
      cachedSongs.clear()
      cachedSongs.addAll(next)
      persistCatalog(next, singleUpsert = enriched)
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
        sourceType = "local",
        mimeType = meta.mimeType,
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
        cacheStatus = "local_import",
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
      val resolvedTrackId = target?.track?.id?.ifBlank { trackId } ?: trackId
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
      if (resolvedTrackId.isNotBlank()) {
        cacheModule?.removeFromDownloadCache(resolvedTrackId)
      }
      val remaining =
        cachedSongs.filterNot {
          (resolvedTrackId.isNotBlank() && it.track.id == resolvedTrackId) ||
            (uriString.isNotBlank() && it.localUri == uriString)
        }
      cachedSongs.clear()
      cachedSongs.addAll(remaining)
      try {
        if (resolvedTrackId.isNotBlank()) {
          database?.downloadedSongDao()?.deleteByTrackIdSync(resolvedTrackId)
        }
        if (uriString.isNotBlank()) {
          database?.downloadedSongDao()?.deleteByLocalUriSync(uriString)
        }
      } catch (e: Exception) {
        PulseLog.d(TAG, "Room delete fallback rewrite: ${e.message}")
        persistCatalog(remaining)
      }
      return fileRemoved
    }
  }

  fun syncWithStorage(): List<DownloadedSongRecord> {
    synchronized(lock) {
      val mediaEntries = queryMediaStoreMusicFiles()
      val appLocalFiles =
        (listAppPyMusicDir().listFiles()?.toList().orEmpty() +
          listAppLegacyPulseDir().listFiles()?.toList().orEmpty())
          .filter { it.isFile && it.length() > 2048 }

      val byUri = cachedSongs.associateBy { it.localUri }.toMutableMap()
      val reconciled = mutableListOf<DownloadedSongRecord>()
      val seenUris = mutableSetOf<String>()

      // 1. Reconcile MediaStore items in Music/PyMusic/ and legacy Music/Pulse/
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
              sourceType = "local",
              mimeType = msEntry.mimeType,
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

      // 2. Reconcile app-local Music/PyMusic (and legacy Pulse) files
      for (file in appLocalFiles) {
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
              sourceType = "local",
              mimeType = meta.mimeType,
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

      // 3. Preserve non-folder imported URIs only if they still resolve cleanly
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
      persistCatalog(sorted)
      return sorted
    }
  }

  fun listAppPyMusicDir(): File =
    File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, PYMUSIC_FOLDER)
      .apply { mkdirs() }

  fun listAppLegacyPulseDir(): File =
    File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, LEGACY_PULSE_FOLDER)
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

  private fun queryMediaStoreMusicFiles(): List<MediaStoreAudioRow> {
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
      "(${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?) " +
        "AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
    val args =
      arrayOf(
        "${Environment.DIRECTORY_MUSIC}/$PYMUSIC_FOLDER%",
        "${Environment.DIRECTORY_MUSIC}/$LEGACY_PULSE_FOLDER%",
      )
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

  private fun loadCatalogFromDatabaseOrLegacy() {
    val fromRoom =
      try {
        database?.downloadedSongDao()?.getAllSync()?.map { it.toRecord() }.orEmpty()
      } catch (e: Exception) {
        PulseLog.d(TAG, "Room load error: ${e.message}")
        emptyList()
      }

    if (fromRoom.isNotEmpty()) {
      cachedSongs.clear()
      cachedSongs.addAll(fromRoom)
      return
    }

    if (!legacyCatalogFile.exists()) return
    try {
      val arr = JSONArray(legacyCatalogFile.readText())
      val loaded =
        buildList {
          for (i in 0 until arr.length()) {
            DownloadedSongRecord.fromJson(arr.optJSONObject(i))?.let { add(it) }
          }
        }
      cachedSongs.clear()
      cachedSongs.addAll(loaded)
      if (loaded.isNotEmpty() && database != null) {
        database.downloadedSongDao().upsertAllSync(loaded.map { DownloadedSongEntity.fromRecord(it) })
      }
    } catch (_: Exception) {}
  }

  private fun persistCatalog(items: List<DownloadedSongRecord>, singleUpsert: DownloadedSongRecord? = null) {
    if (database != null) {
      try {
        if (singleUpsert != null) {
          database.downloadedSongDao().upsertSync(DownloadedSongEntity.fromRecord(singleUpsert))
        } else {
          database.runInTransaction {
            database.downloadedSongDao().deleteAllSync()
            if (items.isNotEmpty()) {
              database.downloadedSongDao().upsertAllSync(items.map { DownloadedSongEntity.fromRecord(it) })
            }
          }
        }
        return
      } catch (e: Exception) {
        PulseLog.d(TAG, "Room persist fallback to JSON: ${e.message}")
      }
    }
    try {
      val arr = JSONArray().apply { items.forEach { put(it.toJson()) } }
      val temp = File(legacyCatalogFile.parentFile, "${legacyCatalogFile.name}.tmp")
      temp.writeText(arr.toString())
      temp.renameTo(legacyCatalogFile)
    } catch (_: Exception) {}
  }

  companion object {
    private const val TAG = "DownloadedSongsRepo"
    const val PYMUSIC_FOLDER = "PyMusic"
    const val LEGACY_PULSE_FOLDER = "Pulse"
  }
}
