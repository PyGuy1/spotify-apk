package com.example.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.model.DownloadProgressItem
import com.example.model.DownloadStatus
import com.example.model.DownloadedSongRecord
import com.example.model.PulseTrack
import com.example.stream.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SongDownloadManager(
  private val context: Context,
  private val streamResolver: StreamResolver,
  private val downloadedRepo: DownloadedSongsRepository,
  private val okHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build(),
) {
  interface Listener {
    fun onDownloadUpdated(item: DownloadProgressItem)
    fun onLibrarySynced(songs: List<DownloadedSongRecord>)
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val states = ConcurrentHashMap<String, DownloadProgressItem>()
  private val activeJobs = ConcurrentHashMap<String, Job>()
  private val activeCalls = ConcurrentHashMap<String, Call>()
  private val savedUrls = ConcurrentHashMap<String, List<String>>()
  private val listeners = ConcurrentHashMap.newKeySet<Listener>()
  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  @Volatile private var audioQuality: String = "normal"

  init {
    ensureNotificationChannel()
  }

  fun setAudioQuality(quality: String) {
    audioQuality = StreamResolver.normalizeQuality(quality)
  }

  fun addListener(listener: Listener) {
    listeners.add(listener)
  }

  fun removeListener(listener: Listener) {
    listeners.remove(listener)
  }

  fun getAllDownloadStates(): List<DownloadProgressItem> =
    states.values.sortedByDescending { it.updatedAtMs }

  fun syncLibraryAndNotify(): List<DownloadedSongRecord> {
    val synced = downloadedRepo.syncWithStorage()
    listeners.forEach { it.onLibrarySynced(synced) }
    return synced
  }

  fun enqueueDownload(track: PulseTrack, candidateUrls: List<String> = emptyList()) {
    if (track.id.isBlank()) return
    // 1. Prevent duplicate download of active task
    val existingJob = activeJobs[track.id]
    if (existingJob != null && existingJob.isActive) {
      states[track.id]?.let { emitState(it) }
      return
    }

    // 2. Check if already downloaded and valid on disk
    val existingLocal = downloadedRepo.findPlayableLocalUri(track.id)
    if (existingLocal != null) {
      val rec = downloadedRepo.findByTrackId(track.id)
      val completed =
        DownloadProgressItem(
          track = track.copy(localUri = existingLocal),
          status = DownloadStatus.COMPLETED,
          progressPercent = 100,
          bytesDownloaded = rec?.sizeBytes ?: 0L,
          totalBytes = rec?.sizeBytes ?: -1L,
          localUri = existingLocal,
          fileName = rec?.fileName.orEmpty(),
          mimeType = rec?.mimeType.orEmpty(),
        )
      states[track.id] = completed
      emitState(completed)
      syncLibraryAndNotify()
      return
    }

    val cleanCandidates = candidateUrls.filter { StreamResolver.isValidStreamUrl(it) }.distinct()
    savedUrls[track.id] = cleanCandidates

    val queued =
      DownloadProgressItem(
        track = track,
        status = DownloadStatus.QUEUED,
        progressPercent = 0,
      )
    states[track.id] = queued
    emitState(queued)
    updateDownloadNotification(queued)

    val job =
      scope.launch {
        try {
          performDownload(track, cleanCandidates)
        } finally {
          activeJobs.remove(track.id)
          activeCalls.remove(track.id)
        }
      }
    activeJobs[track.id] = job
  }

  fun cancelDownload(trackId: String) {
    activeCalls.remove(trackId)?.cancel()
    activeJobs.remove(trackId)?.cancel()
    val prev = states[trackId] ?: return
    val cancelled =
      prev.copy(
        status = DownloadStatus.CANCELLED,
        errorMessage = "Download cancelled",
        updatedAtMs = System.currentTimeMillis(),
      )
    states[trackId] = cancelled
    emitState(cancelled)
    notificationManager.cancel(notifIdFor(trackId))
  }

  fun retryDownload(trackId: String) {
    val prev = states[trackId] ?: return
    val urls = savedUrls[trackId].orEmpty()
    enqueueDownload(prev.track, urls)
  }

  fun deleteDownloadedSong(trackId: String, localUri: String): Boolean {
    val ok = downloadedRepo.deleteSong(trackId, localUri)
    states.remove(trackId)
    syncLibraryAndNotify()
    return ok
  }

  private suspend fun performDownload(track: PulseTrack, initialCandidates: List<String>) {
    val resolving =
      DownloadProgressItem(
        track = track,
        status = DownloadStatus.RESOLVING,
        progressPercent = 0,
      )
    states[track.id] = resolving
    emitState(resolving)
    updateDownloadNotification(resolving)

    val allCandidates = LinkedHashSet<String>()
    allCandidates.addAll(initialCandidates)
    val resolved = streamResolver.resolveStream(track.id, audioQuality, forceRefresh = initialCandidates.isEmpty())
    resolved?.audioUrls?.forEach { allCandidates.add(it) }

    if (allCandidates.isEmpty()) {
      failDownload(track, "No playable audio stream found")
      return
    }

    var lastError = "Download failed"
    var triedFresh = resolved != null

    val urlQueue = ArrayDeque(allCandidates.toList())
    while (urlQueue.isNotEmpty()) {
      val candidateUrl = urlQueue.removeFirst()
      try {
        val result = streamUrlToStorage(track, candidateUrl)
        if (result != null) {
          downloadedRepo.upsertManagedDownload(result)
          val done =
            DownloadProgressItem(
              track = track.copy(localUri = result.localUri),
              status = DownloadStatus.COMPLETED,
              progressPercent = 100,
              bytesDownloaded = result.sizeBytes,
              totalBytes = result.sizeBytes,
              localUri = result.localUri,
              fileName = result.fileName,
              mimeType = result.mimeType,
            )
          states[track.id] = done
          emitState(done)
          updateDownloadNotification(done)
          syncLibraryAndNotify()
          return
        }
      } catch (c: CancellationException) {
        throw c
      } catch (e: Exception) {
        lastError = e.message ?: "Stream transfer interrupted"
        if (!triedFresh && urlQueue.isEmpty()) {
          triedFresh = true
          streamResolver.invalidate(track.id)
          streamResolver.resolveStream(track.id, audioQuality, forceRefresh = true)
            ?.audioUrls
            ?.forEach { urlQueue.addLast(it) }
        }
      }
    }

    failDownload(track, lastError)
  }

  private fun streamUrlToStorage(track: PulseTrack, url: String): DownloadedSongRecord? {
    val req =
      Request.Builder()
        .url(url)
        .header(
          "User-Agent",
          "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
        )
        .build()
    val call = okHttpClient.newCall(req)
    activeCalls[track.id] = call

    call.execute().use { resp ->
      if (!resp.isSuccessful) {
        throw IllegalStateException("HTTP ${resp.code}")
      }
      val body = resp.body ?: throw IllegalStateException("Empty response body")
      val contentType = resp.header("Content-Type").orEmpty()
      val contentLength = body.contentLength()
      val stream = body.byteStream()

      // Read first 128 bytes to inspect magic header & reject HTML/JSON/text pages cleanly
      val headBuf = ByteArray(128)
      var headRead = 0
      while (headRead < headBuf.size) {
        val r = stream.read(headBuf, headRead, headBuf.size - headRead)
        if (r <= 0) break
        headRead += r
      }
      if (headRead <= 0) throw IllegalStateException("Stream returned 0 bytes")
      val headSlice = headBuf.copyOf(headRead)
      val format = SongFilenameUtils.inspectStreamFormat(contentType, headSlice, url)
      if (!format.isValidAudio) {
        throw IllegalStateException(format.rejectionReason)
      }

      val baseName = SongFilenameUtils.sanitizeBaseName(track.title, track.artist)
      val existingNames = downloadedRepo.listExistingFileNames()
      val fileName = SongFilenameUtils.deduplicateFileName(baseName, format.extension, existingNames)

      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        writeViaMediaStore(track, fileName, format.mimeType, headSlice, stream, contentLength)
      } else {
        writeViaLegacyAppMusicDir(track, fileName, format.mimeType, headSlice, stream, contentLength)
      }
    }
  }

  private fun writeViaMediaStore(
    track: PulseTrack,
    fileName: String,
    mimeType: String,
    headBytes: ByteArray,
    stream: java.io.InputStream,
    totalBytes: Long,
  ): DownloadedSongRecord {
    val resolver = context.contentResolver
    val values =
      ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
        put(MediaStore.Audio.Media.TITLE, track.title)
        if (track.artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, track.artist)
        if (track.album.isNotBlank()) put(MediaStore.Audio.Media.ALBUM, track.album)
        put(MediaStore.Audio.Media.IS_MUSIC, 1)
        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Pulse")
        put(MediaStore.Audio.Media.IS_PENDING, 1)
      }
    val itemUri =
      resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("MediaStore rejected insert")

    var bytesWritten = 0L
    try {
      resolver.openOutputStream(itemUri)?.use { out ->
        out.write(headBytes)
        bytesWritten += headBytes.size
        val buf = ByteArray(16 * 1024)
        var lastEmitMs = 0L
        var lastPct = -1
        while (true) {
          val read = stream.read(buf)
          if (read <= 0) break
          out.write(buf, 0, read)
          bytesWritten += read
          val pct =
            if (totalBytes > 0L) ((bytesWritten * 100L) / totalBytes).toInt().coerceIn(0, 99)
            else 0
          val now = System.currentTimeMillis()
          if (now - lastEmitMs >= 250L || (pct != lastPct && pct % 5 == 0)) {
            lastEmitMs = now
            lastPct = pct
            val progressItem =
              DownloadProgressItem(
                track = track,
                status = DownloadStatus.DOWNLOADING,
                progressPercent = pct,
                bytesDownloaded = bytesWritten,
                totalBytes = totalBytes,
                fileName = fileName,
                mimeType = mimeType,
                updatedAtMs = now,
              )
            states[track.id] = progressItem
            emitState(progressItem)
            updateDownloadNotification(progressItem)
          }
        }
      } ?: throw IllegalStateException("Unable to open MediaStore stream")

      if (bytesWritten < MIN_VALID_AUDIO_BYTES) {
        throw IllegalStateException("Audio file truncated ($bytesWritten bytes)")
      }

      values.clear()
      values.put(MediaStore.Audio.Media.IS_PENDING, 0)
      resolver.update(itemUri, values, null, null)

      val uriStr = itemUri.toString()
      return DownloadedSongRecord(
        track = track.copy(localUri = uriStr),
        localUri = uriStr,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = bytesWritten,
        addedAtMs = System.currentTimeMillis(),
        isAppManaged = true,
      )
    } catch (t: Throwable) {
      try {
        resolver.delete(itemUri, null, null)
      } catch (_: Exception) {}
      throw t
    }
  }

  private fun writeViaLegacyAppMusicDir(
    track: PulseTrack,
    fileName: String,
    mimeType: String,
    headBytes: ByteArray,
    stream: java.io.InputStream,
    totalBytes: Long,
  ): DownloadedSongRecord {
    val dir = downloadedRepo.listAppLegacyPulseDir()
    val partFile = File(dir, "$fileName.part")
    val finalFile = File(dir, fileName)
    var bytesWritten = 0L
    try {
      FileOutputStream(partFile).use { out ->
        out.write(headBytes)
        bytesWritten += headBytes.size
        val buf = ByteArray(16 * 1024)
        var lastEmitMs = 0L
        while (true) {
          val read = stream.read(buf)
          if (read <= 0) break
          out.write(buf, 0, read)
          bytesWritten += read
          val pct =
            if (totalBytes > 0L) ((bytesWritten * 100L) / totalBytes).toInt().coerceIn(0, 99)
            else 0
          val now = System.currentTimeMillis()
          if (now - lastEmitMs >= 250L) {
            lastEmitMs = now
            val progressItem =
              DownloadProgressItem(
                track = track,
                status = DownloadStatus.DOWNLOADING,
                progressPercent = pct,
                bytesDownloaded = bytesWritten,
                totalBytes = totalBytes,
                fileName = fileName,
                mimeType = mimeType,
                updatedAtMs = now,
              )
            states[track.id] = progressItem
            emitState(progressItem)
            updateDownloadNotification(progressItem)
          }
        }
      }
      if (bytesWritten < MIN_VALID_AUDIO_BYTES) {
        throw IllegalStateException("Audio file truncated ($bytesWritten bytes)")
      }
      if (finalFile.exists()) finalFile.delete()
      if (!partFile.renameTo(finalFile)) {
        partFile.copyTo(finalFile, overwrite = true)
        partFile.delete()
      }
      val uriStr = Uri.fromFile(finalFile).toString()
      return DownloadedSongRecord(
        track = track.copy(localUri = uriStr),
        localUri = uriStr,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = bytesWritten,
        addedAtMs = System.currentTimeMillis(),
        isAppManaged = true,
      )
    } catch (t: Throwable) {
      partFile.delete()
      throw t
    }
  }

  private fun failDownload(track: PulseTrack, reason: String) {
    val failed =
      DownloadProgressItem(
        track = track,
        status = DownloadStatus.FAILED,
        errorMessage = reason,
      )
    states[track.id] = failed
    emitState(failed)
    updateDownloadNotification(failed)
  }

  private fun emitState(item: DownloadProgressItem) {
    listeners.forEach { it.onDownloadUpdated(item) }
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          DOWNLOAD_CHANNEL_ID,
          "Song Downloads",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Progress and completion alerts for downloaded songs"
          setShowBadge(false)
        }
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun updateDownloadNotification(item: DownloadProgressItem) {
    val builder =
      NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_pulse_notification)
        .setOnlyAlertOnce(true)
        .setContentTitle(item.track.title)
    when (item.status) {
      DownloadStatus.QUEUED,
      DownloadStatus.RESOLVING -> {
        builder
          .setContentText("Preparing download…")
          .setOngoing(true)
          .setProgress(100, 0, true)
      }
      DownloadStatus.DOWNLOADING -> {
        val label =
          if (item.totalBytes > 0) "${item.progressPercent}%"
          else "${item.bytesDownloaded / 1024} KB"
        builder
          .setContentText("Downloading… $label")
          .setOngoing(true)
          .setProgress(100, item.progressPercent, item.totalBytes <= 0)
      }
      DownloadStatus.COMPLETED -> {
        builder
          .setContentText("Saved to Music/Pulse")
          .setOngoing(false)
          .setAutoCancel(true)
          .setProgress(0, 0, false)
      }
      DownloadStatus.FAILED -> {
        builder
          .setContentText("Download failed: ${item.errorMessage}")
          .setOngoing(false)
          .setAutoCancel(true)
          .setProgress(0, 0, false)
      }
      DownloadStatus.CANCELLED -> {
        notificationManager.cancel(notifIdFor(item.track.id))
        return
      }
    }
    try {
      notificationManager.notify(notifIdFor(item.track.id), builder.build())
    } catch (_: SecurityException) {
      // Missing POST_NOTIFICATIONS on Android 13+: download itself still proceeds silently.
    } catch (_: Exception) {}
  }

  private fun notifIdFor(trackId: String): Int = 40_000 + (trackId.hashCode() and 0x7FFF)

  companion object {
    private const val DOWNLOAD_CHANNEL_ID = "pulse_downloads"
    private const val MIN_VALID_AUDIO_BYTES = 4096L
  }
}
