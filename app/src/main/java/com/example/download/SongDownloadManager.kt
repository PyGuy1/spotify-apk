package com.example.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.PulseLog
import com.example.R
import com.example.db.SongFormatEntity
import com.example.model.DownloadProgressItem
import com.example.model.DownloadStatus
import com.example.model.DownloadedSongRecord
import com.example.model.PulseTrack
import com.example.playback.CacheModule
import com.example.stream.AudioCandidate
import com.example.stream.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@OptIn(UnstableApi::class)
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
  private val cacheModule: CacheModule? = null,
  private val forceAppDirStorage: Boolean = false,
) {
  interface Listener {
    fun onDownloadUpdated(item: DownloadProgressItem)
    fun onLibrarySynced(songs: List<DownloadedSongRecord>)
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val states = ConcurrentHashMap<String, DownloadProgressItem>()
  private val activeJobs = ConcurrentHashMap<String, Job>()
  private val activeCalls = ConcurrentHashMap<String, Call>()
  private val activeDataSources = ConcurrentHashMap<String, DataSource>()
  private val savedUrls = ConcurrentHashMap<String, List<String>>()
  private val listeners = ConcurrentHashMap.newKeySet<Listener>()
  private val notificationManager: NotificationManager? =
    try {
      context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    } catch (_: Exception) {
      null
    }

  @Volatile private var audioQuality: String? = null

  init {
    ensureNotificationChannel()
  }

  fun setAudioQuality(quality: String?) {
    audioQuality = quality?.takeIf { it.isNotBlank() }?.let { StreamResolver.normalizeQuality(it) }
  }

  fun addListener(listener: Listener) {
    listeners.add(listener)
  }

  fun removeListener(listener: Listener) {
    listeners.remove(listener)
  }

  fun getAllDownloadStates(): List<DownloadProgressItem> =
    states.values.sortedByDescending { it.updatedAtMs }

  fun getDownloadState(trackId: String): DownloadProgressItem? = states[trackId]

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
          activeCalls.remove(track.id)?.cancel()
          activeDataSources.remove(track.id)?.let { ds ->
            try {
              ds.close()
            } catch (_: Exception) {}
          }
        }
      }
    activeJobs[track.id] = job
  }

  fun cancelDownload(trackId: String) {
    activeCalls.remove(trackId)?.cancel()
    activeDataSources.remove(trackId)?.let { ds ->
      try {
        ds.close()
      } catch (_: Exception) {}
    }
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
    try {
      notificationManager?.cancel(notifIdFor(trackId))
    } catch (_: Exception) {}
  }

  fun retryDownload(trackId: String) {
    val prev = states[trackId] ?: return
    val urls = savedUrls[trackId].orEmpty()
    enqueueDownload(prev.track, urls)
  }

  fun deleteDownloadedSong(trackId: String, localUri: String): Boolean {
    cancelDownload(trackId)
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

    val candidateMap = LinkedHashMap<String, AudioCandidate>()
    val resolved =
      streamResolver.resolveStreamForTrack(
        track = track,
        explicitQuality = audioQuality,
        forceRefresh = initialCandidates.isEmpty(),
      )
    resolved?.candidates?.forEach { cand ->
      candidateMap.putIfAbsent(cand.url, cand)
    }
    resolved?.audioUrls?.forEach { url ->
      candidateMap.putIfAbsent(
        url,
        AudioCandidate(
          url = url,
          mimeType = track.mimeType,
          bitrate = track.bitrate.takeIf { it > 0 } ?: 128_000,
          sourceHost = StreamResolver.extractHostKey(url),
          itag = track.itag,
          engineSource = resolved.engineUsed,
        ),
      )
    }
    initialCandidates.forEach { url ->
      candidateMap.putIfAbsent(
        url,
        AudioCandidate(
          url = url,
          mimeType = "",
          bitrate = 128_000,
          sourceHost = StreamResolver.extractHostKey(url),
          engineSource = "js-seed",
        ),
      )
    }

    if (candidateMap.isEmpty()) {
      failDownload(track, "No playable audio stream found")
      return
    }

    var lastError = "Download failed"
    var triedFresh = resolved != null && initialCandidates.isEmpty()
    val queue = ArrayDeque(candidateMap.values.toList())

    while (queue.isNotEmpty()) {
      if (!coroutineContext.isActive) throw CancellationException("Download cancelled")
      val candidate = queue.removeFirst()
      try {
        val result = downloadCandidateToStorage(track, candidate, resolved?.engineUsed.orEmpty())
        if (result != null) {
          downloadedRepo.upsertManagedDownload(result)
          if (candidate.itag > 0 || candidate.mimeType.isNotBlank() || candidate.bitrate > 0) {
            downloadedRepo.saveSongFormat(
              SongFormatEntity(
                trackId = track.id,
                itag = candidate.itag,
                mimeType = result.mimeType,
                codecs = candidate.codecs,
                bitrate = candidate.bitrate,
                sampleRate = candidate.sampleRate,
                contentLength = result.sizeBytes,
                loudnessDb = resolved?.loudnessDb,
                engineUsed = candidate.engineSource.ifBlank { resolved?.engineUsed.orEmpty() },
              )
            )
          }
          val done =
            DownloadProgressItem(
              track =
                track.copy(
                  localUri = result.localUri,
                  mimeType = result.mimeType,
                  bitrate = result.bitrate,
                  itag = result.itag,
                ),
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
        PulseLog.w(TAG, "Download candidate failed track=${track.id} host=${candidate.sourceHost}: $lastError")
        if (!triedFresh && queue.isEmpty()) {
          triedFresh = true
          streamResolver.invalidate(track.id)
          val fresh = streamResolver.resolveStreamForTrack(track, explicitQuality = audioQuality, forceRefresh = true)
          fresh?.candidates?.forEach { cand ->
            if (!candidateMap.containsKey(cand.url)) {
              candidateMap[cand.url] = cand
              queue.addLast(cand)
            }
          }
          fresh?.audioUrls?.forEach { url ->
            if (!candidateMap.containsKey(url)) {
              val fallback =
                AudioCandidate(
                  url = url,
                  mimeType = "",
                  bitrate = 128_000,
                  sourceHost = StreamResolver.extractHostKey(url),
                  engineSource = fresh.engineUsed,
                )
              candidateMap[url] = fallback
              queue.addLast(fallback)
            }
          }
        }
      }
    }

    failDownload(track, lastError)
  }

  private suspend fun downloadCandidateToStorage(
    track: PulseTrack,
    candidate: AudioCandidate,
    engineUsed: String,
  ): DownloadedSongRecord? {
    // Prefer Media3 CacheModule DataSource so chunks already in playerCache are reused into downloadCache
    if (cacheModule != null && candidate.headers.isEmpty()) {
      return downloadViaCacheModule(track, candidate, engineUsed)
    }
    return downloadViaOkHttpDirect(track, candidate, engineUsed)
  }

  private suspend fun downloadViaCacheModule(
    track: PulseTrack,
    candidate: AudioCandidate,
    engineUsed: String,
  ): DownloadedSongRecord? {
    val activeCache = cacheModule ?: return null
    val callFactory =
      Call.Factory { request ->
        okHttpClient.newCall(request).also { call -> activeCalls[track.id] = call }
      }
    val upstreamFactory =
      OkHttpDataSource.Factory(callFactory)
        .setUserAgent(USER_AGENT_ANDROID)
        .setDefaultRequestProperties(
          mapOf(
            "Accept" to "audio/*, */*;q=0.5",
            "Accept-Encoding" to "identity",
          )
        )
    val downloadDataSource = activeCache.createDownloadDataSourceFactory(upstreamFactory).createDataSource()
    activeDataSources[track.id] = downloadDataSource

    val spec =
      DataSpec.Builder()
        .setUri(Uri.parse(candidate.url))
        .setKey(track.id)
        .build()

    try {
      val openedLength =
        try {
          downloadDataSource.open(spec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
          throw IllegalStateException("HTTP ${e.responseCode}")
        }
      val totalBytes = if (openedLength == C.LENGTH_UNSET.toLong()) -1L else openedLength
      val responseCt =
        downloadDataSource.responseHeaders.entries
          .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
          ?.value
          ?.firstOrNull()
          .orEmpty()
          .ifBlank { candidate.mimeType }

      val dsInput =
        object : InputStream() {
          private val oneByte = ByteArray(1)

          override fun read(): Int {
            val n = read(oneByte, 0, 1)
            return if (n == -1) -1 else oneByte[0].toInt() and 0xFF
          }

          override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = downloadDataSource.read(b, off, len)
            return if (r == C.RESULT_END_OF_INPUT) -1 else r
          }
        }

      return consumeStreamAndWriteFile(
        track = track,
        candidate = candidate,
        contentType = responseCt,
        totalBytes = totalBytes,
        stream = dsInput,
        onInvalidPayload = { activeCache.removeFromPlayerCache(track.id); activeCache.removeFromDownloadCache(track.id) },
      )
    } finally {
      activeDataSources.remove(track.id)
      try {
        downloadDataSource.close()
      } catch (_: Exception) {}
    }
  }

  private suspend fun downloadViaOkHttpDirect(
    track: PulseTrack,
    candidate: AudioCandidate,
    engineUsed: String,
  ): DownloadedSongRecord? {
    val reqBuilder =
      Request.Builder()
        .url(candidate.url)
        .header("User-Agent", USER_AGENT_ANDROID)
        .header("Accept-Encoding", "identity")
    candidate.headers.forEach { (k, v) ->
      if (v.isNotBlank()) reqBuilder.header(k, v)
    }
    val call = okHttpClient.newCall(reqBuilder.build())
    activeCalls[track.id] = call

    call.execute().use { resp ->
      if (!resp.isSuccessful) {
        throw IllegalStateException("HTTP ${resp.code}")
      }
      val body = resp.body ?: throw IllegalStateException("Empty response body")
      val contentType = resp.header("Content-Type").orEmpty().ifBlank { candidate.mimeType }
      val contentLength = body.contentLength()
      val stream = body.byteStream()
      return consumeStreamAndWriteFile(
        track = track,
        candidate = candidate,
        contentType = contentType,
        totalBytes = contentLength,
        stream = stream,
        onInvalidPayload = {},
      )
    }
  }

  private suspend fun consumeStreamAndWriteFile(
    track: PulseTrack,
    candidate: AudioCandidate,
    contentType: String,
    totalBytes: Long,
    stream: InputStream,
    onInvalidPayload: () -> Unit,
  ): DownloadedSongRecord {
    val headBuf = ByteArray(128)
    var headRead = 0
    while (headRead < headBuf.size) {
      if (!coroutineContext.isActive) throw CancellationException("Download cancelled")
      val r = stream.read(headBuf, headRead, headBuf.size - headRead)
      if (r <= 0) break
      headRead += r
    }
    if (headRead <= 0) throw IllegalStateException("Stream returned 0 bytes")
    val headSlice = headBuf.copyOf(headRead)
    val format = SongFilenameUtils.inspectStreamFormat(contentType, headSlice, candidate.url)
    if (!format.isValidAudio) {
      onInvalidPayload()
      throw IllegalStateException(format.rejectionReason)
    }

    val baseName = SongFilenameUtils.sanitizeBaseName(track.title, track.artist)
    val existingNames = downloadedRepo.listExistingFileNames()
    val fileName = SongFilenameUtils.deduplicateFileName(baseName, format.extension, existingNames)

    val useMediaStore = !forceAppDirStorage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    return if (useMediaStore) {
      writeViaMediaStore(track, candidate, fileName, format.mimeType, headSlice, stream, totalBytes)
    } else {
      writeViaPyMusicAppDir(track, candidate, fileName, format.mimeType, headSlice, stream, totalBytes)
    }
  }

  private suspend fun writeViaMediaStore(
    track: PulseTrack,
    candidate: AudioCandidate,
    fileName: String,
    mimeType: String,
    headBytes: ByteArray,
    stream: InputStream,
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
        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/${DownloadedSongsRepository.PYMUSIC_FOLDER}")
        put(MediaStore.Audio.Media.IS_PENDING, 1)
      }
    val itemUri =
      resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("MediaStore rejected insert")

    try {
      val bytesWritten =
        resolver.openOutputStream(itemUri)?.use { out ->
          copyWithProgress(track, fileName, mimeType, headBytes, stream, out, totalBytes)
        } ?: throw IllegalStateException("Unable to open MediaStore stream")

      if (bytesWritten < MIN_VALID_AUDIO_BYTES) {
        throw IllegalStateException("Audio file truncated ($bytesWritten bytes)")
      }

      values.clear()
      values.put(MediaStore.Audio.Media.IS_PENDING, 0)
      resolver.update(itemUri, values, null, null)

      val uriStr = itemUri.toString()
      val effectiveBitrate = if (candidate.bitrate > 0) candidate.bitrate else track.bitrate
      val effectiveItag = if (candidate.itag > 0) candidate.itag else track.itag
      return DownloadedSongRecord(
        track =
          track.copy(
            localUri = uriStr,
            mimeType = mimeType,
            bitrate = effectiveBitrate,
            itag = effectiveItag,
          ),
        localUri = uriStr,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = bytesWritten,
        addedAtMs = System.currentTimeMillis(),
        isAppManaged = true,
        bitrate = effectiveBitrate,
        itag = effectiveItag,
        cacheStatus =
          if (cacheModule?.hasDownloadCacheContent(track.id) == true) "cached+file" else "completed",
      )
    } catch (t: Throwable) {
      try {
        resolver.delete(itemUri, null, null)
      } catch (_: Exception) {}
      throw t
    }
  }

  private suspend fun writeViaPyMusicAppDir(
    track: PulseTrack,
    candidate: AudioCandidate,
    fileName: String,
    mimeType: String,
    headBytes: ByteArray,
    stream: InputStream,
    totalBytes: Long,
  ): DownloadedSongRecord {
    val dir = downloadedRepo.listAppPyMusicDir()
    val partFile = File(dir, "$fileName.part")
    val finalFile = File(dir, fileName)
    try {
      val bytesWritten =
        FileOutputStream(partFile).use { out ->
          copyWithProgress(track, fileName, mimeType, headBytes, stream, out, totalBytes)
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
      val effectiveBitrate = if (candidate.bitrate > 0) candidate.bitrate else track.bitrate
      val effectiveItag = if (candidate.itag > 0) candidate.itag else track.itag
      return DownloadedSongRecord(
        track =
          track.copy(
            localUri = uriStr,
            mimeType = mimeType,
            bitrate = effectiveBitrate,
            itag = effectiveItag,
          ),
        localUri = uriStr,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = bytesWritten,
        addedAtMs = System.currentTimeMillis(),
        isAppManaged = true,
        bitrate = effectiveBitrate,
        itag = effectiveItag,
        cacheStatus =
          if (cacheModule?.hasDownloadCacheContent(track.id) == true) "cached+file" else "completed",
      )
    } catch (t: Throwable) {
      partFile.delete()
      throw t
    }
  }

  private suspend fun copyWithProgress(
    track: PulseTrack,
    fileName: String,
    mimeType: String,
    headBytes: ByteArray,
    stream: InputStream,
    out: OutputStream,
    totalBytes: Long,
  ): Long {
    out.write(headBytes)
    var bytesWritten = headBytes.size.toLong()
    val buf = ByteArray(16 * 1024)
    var lastEmitMs = 0L
    var lastPct = -1

    while (true) {
      if (!coroutineContext.isActive) {
        throw CancellationException("Download cancelled")
      }
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
    out.flush()
    return bytesWritten
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
    if (notificationManager == null) return
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
    val nm = notificationManager ?: return
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
          .setContentText("Saved to Music/PyMusic")
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
        nm.cancel(notifIdFor(item.track.id))
        return
      }
    }
    try {
      nm.notify(notifIdFor(item.track.id), builder.build())
    } catch (_: SecurityException) {
      // Missing POST_NOTIFICATIONS on Android 13+: download itself still proceeds silently.
    } catch (_: Exception) {}
  }

  private fun notifIdFor(trackId: String): Int = 40_000 + (trackId.hashCode() and 0x7FFF)

  companion object {
    private const val TAG = "SongDownloadManager"
    private const val DOWNLOAD_CHANNEL_ID = PulseDownloadService.DOWNLOAD_CHANNEL_ID
    private const val MIN_VALID_AUDIO_BYTES = 4096L
    private const val USER_AGENT_ANDROID =
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/133.0.0.0 Mobile Safari/537.36"
  }
}
